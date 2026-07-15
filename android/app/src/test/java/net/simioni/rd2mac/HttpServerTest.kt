package net.simioni.rd2mac

import net.simioni.rd2mac.http.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL

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
}
