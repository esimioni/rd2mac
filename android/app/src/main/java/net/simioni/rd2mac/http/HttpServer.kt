package net.simioni.rd2mac.http

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
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
 */
class HttpServer(
    private val port: Int,
    /** Per-read socket timeout; a silent client is answered 408 and dropped. */
    private val readTimeoutMs: Int = 30_000,
    private val handler: (Request) -> Response,
) {
    data class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,   // lowercased keys
        val body: ByteArray,
    ) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    class Response(
        val status: Int,
        val headers: MutableMap<String, String> = mutableMapOf(),
        val body: ByteArray = ByteArray(0),
    ) {
        companion object {
            fun text(status: Int, s: String) = Response(
                status,
                mutableMapOf("Content-Type" to "text/plain; charset=utf-8"),
                s.toByteArray(Charsets.UTF_8),
            )

            fun json(status: Int, s: String) = Response(
                status,
                mutableMapOf("Content-Type" to "application/json"),
                s.toByteArray(Charsets.UTF_8),
            )

            fun bytes(status: Int, mime: String, data: ByteArray, extra: Map<String, String> = emptyMap()) =
                Response(status, (mutableMapOf("Content-Type" to mime) + extra).toMutableMap(), data)
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
            try {
                s.soTimeout = readTimeoutMs
                val response = when (val outcome = parseRequest(s.getInputStream())) {
                    is Parse.Ok -> try {
                        handler(outcome.request)
                    } catch (e: Exception) {
                        Response.text(500, "handler error: ${e.message}")
                    }
                    Parse.TooLarge -> Response.text(413, "payload exceeds ${MAX_BODY_BYTES} bytes")
                    Parse.Bad -> Response.text(400, "Bad Request")
                }
                writeResponse(s, response)
            } catch (e: SocketTimeoutException) {
                try { writeResponse(s, Response.text(408, "request timeout")) } catch (_: Exception) {}
            } catch (_: Exception) {
                // client vanished mid-transfer; nothing to do
            }
        }
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

        // Parse as Long first: a declared length beyond the cap (or beyond Int)
        // is refused before any allocation — the body is held fully in memory.
        val declared = headers["content-length"]?.toLongOrNull() ?: 0L
        if (declared < 0) return Parse.Bad
        if (declared > MAX_BODY_BYTES) return Parse.TooLarge

        val contentLength = declared.toInt()
        val body = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val r = input.read(body, read, contentLength - read)
            if (r < 0) break
            read += r
        }
        return Parse.Ok(Request(method, path, query, headers, body))
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
        response.headers["Content-Length"] = response.body.size.toString()
        response.headers["Connection"] = "close"
        val head = StringBuilder("HTTP/1.1 ${response.status} ${reason(response.status)}\r\n")
        for ((k, v) in response.headers) head.append("$k: $v\r\n")
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.UTF_8))
        out.write(response.body)
        out.flush()
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"
        404 -> "Not Found"; 408 -> "Request Timeout"; 413 -> "Payload Too Large"
        500 -> "Internal Server Error"; 501 -> "Not Implemented"
        else -> "Status"
    }

    companion object {
        /** Bodies are held fully in memory (2-3 copies at peak with sealing), so
         *  the practical ceiling is the app heap — refuse anything bigger with a
         *  clean 413 (PROTOCOL.md) instead of dying on allocation. */
        const val MAX_BODY_BYTES = 256L * 1024 * 1024
        /** No legitimate RD2Mac request carries more than a few hundred header bytes. */
        const val MAX_HEADER_BYTES = 64 * 1024
    }
}
