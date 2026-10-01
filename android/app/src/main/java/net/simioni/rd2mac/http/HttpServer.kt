package net.simioni.rd2mac.http

import net.simioni.rd2mac.io.BoundedInputStream
import net.simioni.rd2mac.io.Streams
import net.simioni.rd2mac.update.TransferActivity
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors

/**
 * Minimal, dependency-free HTTP/1.1 server for the RD2Mac protocol. Reads the
 * body by explicit Content-Length (no chunked), so binary uploads (`POST
 * /receive`) work cleanly — the reason we don't use NanoHTTPD. Mirrors the Swift
 * `HTTPServer`. The handler runs on a worker thread and returns synchronously;
 * `POST /receive` persists to MediaStore before returning, so a 200 is a real
 * delivery.
 *
 * Bodies STREAM both ways: the handler reads the request body straight off the
 * socket, and a response body is written straight onto it — a video never has to
 * fit in the app heap (byte-array bodies killed the app on a 147 MB video,
 * 29/09/2026).
 */
class HttpServer(
    private val port: Int,
    /** Per-read socket timeout; a silent client is answered 408 and dropped. */
    private val readTimeoutMs: Int = 30_000,
    /** Where the requests [tracks] picks count as transfers in flight, from their
     *  request line until the response body is written (self-update waits for them). */
    private val activity: TransferActivity? = null,
    private val tracks: (method: String, path: String) -> Boolean = { _, _ -> false },
    private val handler: (Request) -> Response,
) {
    class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,   // lowercased keys
        /** Declared Content-Length, already within [MAX_BODY_BYTES]. */
        val contentLength: Long,
        /** The body straight off the socket, ending after [contentLength] bytes — or
         *  earlier if the client hangs up, so a consumer of the exact length must check. */
        val body: InputStream,
    ) {
        fun header(name: String): String? = headers[name.lowercase()]

        /** The whole body in memory — for small control messages (the /pair JSON);
         *  past [limit] it is refused with 413. */
        fun bodyBytes(limit: Int = MAX_SMALL_BODY_BYTES): ByteArray {
            if (contentLength > limit) throw PayloadTooLarge(limit.toLong())
            return Streams.readExactly(body, contentLength.toInt())
        }
    }

    /** A handler refuses a body too big for what it does with it → 413. */
    class PayloadTooLarge(limit: Long) : Exception("payload exceeds $limit bytes")

    class Response(
        val status: Int,
        val headers: MutableMap<String, String> = mutableMapOf(),
        val body: Body = Body.of(ByteArray(0)),
    ) {
        companion object {
            fun text(status: Int, s: String) =
                bytes(status, "text/plain; charset=utf-8", s.toByteArray(Charsets.UTF_8))

            fun json(status: Int, s: String) =
                bytes(status, "application/json", s.toByteArray(Charsets.UTF_8))

            fun bytes(status: Int, mime: String, data: ByteArray, extra: Map<String, String> = emptyMap()) =
                Response(status, headersFor(mime, extra), Body.of(data))

            /** A body of exactly [length] bytes, put on the socket by [write] as it is
             *  produced. [source] is closed once the response is over, written or not. */
            fun stream(status: Int, mime: String, length: Long, source: Closeable,
                       extra: Map<String, String> = emptyMap(), write: (OutputStream) -> Unit) =
                Response(status, headersFor(mime, extra), Body(length, source, write))

            private fun headersFor(mime: String, extra: Map<String, String>) =
                (mutableMapOf("Content-Type" to mime) + extra).toMutableMap()
        }
    }

    /** A response body: its exact length (the Content-Length) and how to write it. */
    class Body(val length: Long, private val source: Closeable?, private val write: (OutputStream) -> Unit) :
        Closeable {
        fun writeTo(out: OutputStream) = write(out)
        override fun close() { source?.close() }

        companion object {
            fun of(bytes: ByteArray) = Body(bytes.size.toLong(), null) { it.write(bytes) }
        }
    }

    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var running = false

    /** The actually-bound port (useful when constructed with port 0). */
    val boundPort: Int
        get() = serverSocket?.localPort ?: port

    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        try {
            ss.bind(InetSocketAddress(port))
        } catch (e: BindException) {
            // Another process owns the preferred port. Fall back to an ephemeral
            // one instead of crashing the service — Bonjour advertises the port
            // actually bound, so peers still find us.
            ss.bind(InetSocketAddress(0))
        }
        serverSocket = ss
        running = true
        pool.execute { acceptLoop(ss) }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        pool.shutdownNow()
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val socket = try {
                ss.accept()
            } catch (e: Exception) {
                if (running) continue else break
            }
            pool.execute { handleConnection(socket) }
        }
    }

    private fun handleConnection(socket: Socket) {
        socket.use { s ->
            var tracked = false
            try {
                s.soTimeout = readTimeoutMs
                val response = when (val outcome = parseRequest(s.getInputStream())) {
                    is Parse.Ok -> {
                        tracked = activity != null && tracks(outcome.request.method, outcome.request.path)
                        if (tracked) activity?.begin()
                        respond(outcome.request)
                    }
                    Parse.TooLarge -> Response.text(413, "payload exceeds $MAX_BODY_BYTES bytes")
                    Parse.Bad -> Response.text(400, "Bad Request")
                }
                response.body.use { writeResponse(s, response) }
            } catch (e: SocketTimeoutException) {
                try { writeResponse(s, Response.text(408, "request timeout")) } catch (_: Exception) {}
            } catch (_: Exception) {
                // client vanished mid-transfer; nothing to do
            } finally {
                if (tracked) activity?.end()
            }
        }
    }

    /** Runs the handler, then drains what it left of the body: closing a socket with
     *  unread input resets it, and the client would see the reset, not the answer. */
    private fun respond(request: Request): Response {
        val response = try {
            handler(request)
        } catch (e: SocketTimeoutException) {
            throw e                                         // silent client → 408
        } catch (e: PayloadTooLarge) {
            Response.text(413, e.message ?: "payload too large")
        } catch (e: Exception) {
            Response.text(500, "handler error: ${e.message}")
        }
        try { Streams.drain(request.body, MAX_DRAIN_BYTES) } catch (_: IOException) {}
        return response
    }

    private sealed interface Parse {
        class Ok(val request: Request) : Parse
        object TooLarge : Parse
        object Bad : Parse
    }

    private fun parseRequest(input: InputStream): Parse {
        // Read header bytes up to and including CRLFCRLF, capped so a client that
        // never ends its header block can't buffer unbounded memory.
        val headerBuf = ByteArrayOutputStream()
        var b3 = -1; var b2 = -1; var b1 = -1
        while (true) {
            val b = input.read()
            if (b < 0) return Parse.Bad
            headerBuf.write(b)
            if (headerBuf.size() > MAX_HEADER_BYTES) return Parse.Bad
            if (b3 == 13 && b2 == 10 && b1 == 13 && b == 10) break  // \r\n\r\n
            b3 = b2; b2 = b1; b1 = b
        }
        val headerText = headerBuf.toByteArray().toString(Charsets.UTF_8)
        val lines = headerText.split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return Parse.Bad

        val parts = lines[0].split(" ")
        if (parts.size < 2) return Parse.Bad
        val method = parts[0].uppercase()
        val (path, query) = splitTarget(parts[1])

        val headers = HashMap<String, String>()
        for (i in 1 until lines.size) {
            val idx = lines[i].indexOf(':')
            if (idx > 0) {
                val key = lines[i].substring(0, idx).trim().lowercase()
                val value = lines[i].substring(idx + 1).trim()
                headers[key] = value
            }
        }

        // Parse as Long: a declared length beyond the cap is refused before a single
        // body byte is read.
        val declared = headers["content-length"]?.toLongOrNull() ?: 0L
        if (declared < 0) return Parse.Bad
        if (declared > MAX_BODY_BYTES) return Parse.TooLarge
        return Parse.Ok(Request(method, path, query, headers, declared, BoundedInputStream(input, declared)))
    }

    private fun splitTarget(target: String): Pair<String, Map<String, String>> {
        val q = target.indexOf('?')
        if (q < 0) return target to emptyMap()
        val path = target.substring(0, q)
        val query = HashMap<String, String>()
        for (pair in target.substring(q + 1).split("&")) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            if (eq < 0) query[decode(pair)] = ""
            else query[decode(pair.substring(0, eq))] = decode(pair.substring(eq + 1))
        }
        return path to query
    }

    private fun decode(s: String): String =
        try { java.net.URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }

    private fun writeResponse(socket: Socket, response: Response) {
        val out = BufferedOutputStream(socket.getOutputStream())
        response.headers["Content-Length"] = response.body.length.toString()
        response.headers["Connection"] = "close"
        val head = StringBuilder("HTTP/1.1 ${response.status} ${reason(response.status)}\r\n")
        for ((k, v) in response.headers) head.append("$k: $v\r\n")
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.UTF_8))
        response.body.writeTo(out)
        out.flush()
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"
        404 -> "Not Found"; 408 -> "Request Timeout"; 413 -> "Payload Too Large"
        500 -> "Internal Server Error"; 501 -> "Not Implemented"
        else -> "Status"
    }

    companion object {
        /** Same cap as the Mac, so the protocol has one number (PROTOCOL.md). Bodies
         *  stream to storage here, so it bounds a transfer, not the heap. */
        const val MAX_BODY_BYTES = 4L * 1024 * 1024 * 1024
        /** Bodies read whole into memory ([Request.bodyBytes]): control JSON, a few
         *  hundred bytes in practice. */
        const val MAX_SMALL_BODY_BYTES = 64 * 1024
        /** How much of an unread body is still consumed so the client gets the answer;
         *  past it the connection is just closed. */
        const val MAX_DRAIN_BYTES = 16L * 1024 * 1024
        /** No legitimate RD2Mac request carries more than a few hundred header bytes. */
        const val MAX_HEADER_BYTES = 64 * 1024
    }
}
