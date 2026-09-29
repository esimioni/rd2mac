package net.simioni.rd2mac

import net.simioni.rd2mac.http.HttpServer
import net.simioni.rd2mac.io.Streams
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The HttpServer uses only java.net, so it runs in a plain JVM unit test. */
class HttpServerTest {

    private fun okServer() = HttpServer(0) { HttpServer.Response.text(200, "ok") }

    /** Open a raw socket, send [head], half-close, and read the status line back. */
    private fun rawExchange(port: Int, head: String): String =
        Socket("127.0.0.1", port).use { s ->
            s.getOutputStream().write(head.toByteArray(Charsets.ISO_8859_1))
            s.shutdownOutput()
            s.getInputStream().bufferedReader().readLine() ?: ""
        }

    private fun withServer(server: HttpServer, test: (Int) -> Unit) {
        server.start()
        try { test(server.boundPort) } finally { server.stop() }
    }

    private fun post(port: Int, body: ByteArray): HttpURLConnection =
        (URL("http://127.0.0.1:$port/receive").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setFixedLengthStreamingMode(body.size)
            outputStream.use { it.write(body) }
        }

    @Test fun servesGetRequest() {
        val server = HttpServer(0) { req ->
            if (req.method == "GET" && req.path == "/ping")
                HttpServer.Response.json(200, """{"ok":true}""")
            else
                HttpServer.Response.text(404, "no route")
        }
        server.start()
        try {
            val conn = URL("http://127.0.0.1:${server.boundPort}/ping")
                .openConnection() as HttpURLConnection
            assertEquals(200, conn.responseCode)
            assertTrue(conn.inputStream.bufferedReader().readText().contains("ok"))
        } finally {
            server.stop()
        }
    }

    /** A busy port must NOT crash the service: start() falls back to an ephemeral
     *  port (Bonjour advertises the real one). Another process owning 53318 used
     *  to throw out of onStartCommand and kill the app. */
    @Test fun bindingBusyPortFallsBackToEphemeral() {
        val first = okServer()
        first.start()
        try {
            val second = HttpServer(first.boundPort) { HttpServer.Response.text(200, "ok") }
            second.start()   // must not throw
            try {
                assertNotEquals(first.boundPort, second.boundPort)
                assertTrue(second.boundPort > 0)
                val conn = URL("http://127.0.0.1:${second.boundPort}/x")
                    .openConnection() as HttpURLConnection
                assertEquals(200, conn.responseCode)
            } finally {
                second.stop()
            }
        } finally {
            first.stop()
        }
    }

    /** A Content-Length beyond the body cap must be refused up front with 413 —
     *  before this the server tried to allocate whatever the client declared
     *  (trivial remote OOM on the declared hotel-Wi-Fi threat model). */
    @Test fun oversizedContentLengthGets413() {
        val server = okServer()
        server.start()
        try {
            val status = rawExchange(
                server.boundPort,
                "POST /receive HTTP/1.1\r\nContent-Length: 999999999999\r\n\r\n")
            assertTrue("expected 413, got: $status", status.contains("413"))
        } finally {
            server.stop()
        }
    }

    /** A negative Content-Length crashed body allocation (NegativeArraySizeException)
     *  and the connection died with no response. Must be a clean 400. */
    @Test fun negativeContentLengthGets400() {
        val server = okServer()
        server.start()
        try {
            val status = rawExchange(
                server.boundPort,
                "POST /receive HTTP/1.1\r\nContent-Length: -5\r\n\r\n")
            assertTrue("expected 400, got: $status", status.contains("400"))
        } finally {
            server.stop()
        }
    }

    /** Unbounded header accumulation is capped: a request that never finishes its
     *  header block is cut off with 400 instead of buffering forever. */
    @Test fun oversizedHeadersGet400() {
        val server = okServer()
        server.start()
        try {
            val junk = "GET /x HTTP/1.1\r\n" + "A".repeat(80 * 1024) // no CRLFCRLF end
            val status = rawExchange(server.boundPort, junk)
            assertTrue("expected 400, got: $status", status.contains("400"))
        } finally {
            server.stop()
        }
    }

    /** A client that goes silent mid-request must not pin a worker thread forever:
     *  the read timeout answers 408 and closes. */
    @Test fun silentClientGets408() {
        val server = HttpServer(0, readTimeoutMs = 300) { HttpServer.Response.text(200, "ok") }
        server.start()
        try {
            Socket("127.0.0.1", server.boundPort).use { s ->
                s.getOutputStream().write("GET /x HTTP/1.1\r\n".toByteArray()) // never finishes
                s.soTimeout = 5_000
                val line = s.getInputStream().bufferedReader().readLine() ?: ""
                assertTrue("expected 408, got: $line", line.contains("408"))
            }
        } finally {
            server.stop()
        }
    }

    /** The handler gets the body as a stream, bounded to Content-Length. */
    @Test fun handlerReadsTheBodyAsAStream() {
        withServer(HttpServer(0) { req ->
            HttpServer.Response.text(200, "${req.contentLength}:${String(req.body.readBytes())}")
        }) { port ->
            val conn = post(port, "hello".toByteArray())
            assertEquals(200, conn.responseCode)
            assertEquals("5:hello", conn.inputStream.bufferedReader().readText())
        }
    }

    /**
     * THE bug, receive side (29/09/2026): bodies were read whole before the handler ran —
     * capped at 256 MiB for that reason, and still ~3 copies past the app heap once
     * decrypted. A body bigger than the whole heap must stream through to the handler.
     */
    @Test(timeout = 120_000) fun streamsARequestBodyBiggerThanTheHeap() {
        val length = biggerThanTheHeap()
        var digest: ByteArray? = null
        withServer(HttpServer(0) { req ->
            val sink = DigestSink()
            Streams.copyExactly(req.body, req.contentLength, sink)
            digest = sink.digest()
            HttpServer.Response.text(200, "ok")
        }) { port ->
            Socket("127.0.0.1", port).use { s ->
                val out = s.getOutputStream().buffered(64 * 1024)
                out.write("POST /receive HTTP/1.1\r\nContent-Length: $length\r\n\r\n".toByteArray())
                PatternInputStream(length).copyTo(out, 64 * 1024)
                out.flush()
                val status = s.getInputStream().bufferedReader().readLine() ?: ""
                assertTrue("expected 200, got: $status", status.contains("200"))
            }
        }
        assertArrayEquals(sha256Of(PatternInputStream(length)), digest)
    }

    /** The pull side: a response body bigger than the heap goes out whole, with its
     *  exact Content-Length. */
    @Test(timeout = 120_000) fun streamsAResponseBodyBiggerThanTheHeap() {
        val length = biggerThanTheHeap()
        withServer(HttpServer(0) {
            HttpServer.Response.stream(200, "video/mp4", length, source = Closeable {}) { out ->
                Streams.copyExactly(PatternInputStream(length), length, out)
            }
        }) { port ->
            val conn = URL("http://127.0.0.1:$port/media/latest").openConnection() as HttpURLConnection
            assertEquals(200, conn.responseCode)
            assertEquals(length, conn.contentLengthLong)
            val sink = DigestSink()
            conn.inputStream.use { it.copyTo(sink, 64 * 1024) }
            assertEquals(length, sink.count)
            assertArrayEquals(sha256Of(PatternInputStream(length)), sink.digest())
        }
    }

    /** A served file's descriptor is released once the response is done — or MediaStore
     *  descriptors leak one per pull. */
    @Test fun streamedResponseReleasesItsSource() {
        val closed = CountDownLatch(1)
        withServer(HttpServer(0) {
            HttpServer.Response.stream(200, "text/plain", 2, source = Closeable { closed.countDown() }) {
                it.write("ok".toByteArray())
            }
        }) { port ->
            val conn = URL("http://127.0.0.1:$port/x").openConnection() as HttpURLConnection
            assertEquals("ok", conn.inputStream.bufferedReader().readText())
            assertTrue("source never closed", closed.await(5, TimeUnit.SECONDS))
        }
    }

    /** Control bodies read into memory keep a small cap of their own, far below the
     *  streaming cap. */
    @Test fun smallBodyPastItsLimitGets413() {
        withServer(HttpServer(0) { req ->
            req.bodyBytes()
            HttpServer.Response.text(200, "ok")
        }) { port ->
            assertEquals(413, post(port, ByteArray(HttpServer.MAX_SMALL_BODY_BYTES + 1)).responseCode)
        }
    }

    /** A handler that answers without reading the body (401 to a stranger) still gets its
     *  answer to the client: the server drains the rest instead of resetting the socket. */
    @Test fun unreadBodyIsDrainedSoTheClientGetsTheResponse() {
        withServer(HttpServer(0) { HttpServer.Response.text(401, "unauthorized") }) { port ->
            assertEquals(401, post(port, ByteArray(4 * 1024 * 1024)).responseCode)
        }
    }

    /** A client that stalls mid-BODY — which the handler now reads — still gets 408. */
    @Test fun clientSilentMidBodyGets408() {
        withServer(HttpServer(0, readTimeoutMs = 300) { req ->
            req.bodyBytes()
            HttpServer.Response.text(200, "ok")
        }) { port ->
            Socket("127.0.0.1", port).use { s ->
                s.getOutputStream().write("POST /pair HTTP/1.1\r\nContent-Length: 10\r\n\r\nabc".toByteArray())
                s.soTimeout = 5_000
                val line = s.getInputStream().bufferedReader().readLine() ?: ""
                assertTrue("expected 408, got: $line", line.contains("408"))
            }
        }
    }
}
