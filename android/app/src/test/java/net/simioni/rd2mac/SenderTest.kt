package net.simioni.rd2mac

import net.simioni.rd2mac.http.HttpServer
import net.simioni.rd2mac.net.FilePayload
import net.simioni.rd2mac.net.OkHttpPoster
import net.simioni.rd2mac.net.RD2Crypto
import net.simioni.rd2mac.net.RD2Http
import net.simioni.rd2mac.net.ResolvedPeer
import net.simioni.rd2mac.net.RetrySender
import net.simioni.rd2mac.net.TransferOrchestrator
import net.simioni.rd2mac.net.WireBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * OkHttpPoster against a real local HttpServer. Focus: the delivery confirmation
 * is only trusted when the peer proves it holds the pairing key (sealed 200 body)
 * — a plain 200 could come from an active MITM that never persisted anything,
 * which would resurrect the phantom-send bug the project exists to kill.
 */
class SenderTest {

    private val key = ByteArray(32) { 0x2A }
    private fun peer(port: Int) = ResolvedPeer("mac-id", "TestMac", "127.0.0.1", port, Platform.MACOS)
    private fun payload() = bytesPayload("note.txt", "text/plain", "hello".toByteArray())
    // Literal instead of org.json: the android.jar test stub throws "not mocked".
    private fun receiveResult() = """{"saved":"/tmp/note.txt","bytes":5}"""

    private fun sealed200() = HttpServer.Response.bytes(
        200, "application/octet-stream", RD2Crypto.seal(receiveResult().toByteArray(), key))

    private fun withServer(handler: (HttpServer.Request) -> HttpServer.Response, test: (Int) -> Unit) {
        val server = HttpServer(0, handler = handler)
        server.start()
        try { test(server.boundPort) } finally { server.stop() }
    }

    @Test fun postAcceptsSealed200() {
        withServer({ req ->
            // Echo protocol: prove we decrypted the upload, answer with a sealed body.
            RD2Crypto.open(req.bodyBytes(), key)
            sealed200()
        }) { port ->
            assertTrue(OkHttpPoster("phone", token = "tok", keyBytes = key).post(peer(port), payload()))
        }
    }

    /** Symptom (B3): a bare plaintext 200 was trusted — anyone able to spoof the
     *  server (ARP MITM on hotel Wi-Fi) could fake delivery without the key. */
    @Test fun postRejectsPlaintext200WhenPaired() {
        withServer({ HttpServer.Response.json(200, receiveResult()) }) { port ->
            assertFalse(
                "plain 200 must not count as delivery when a key is pledged",
                OkHttpPoster("phone", token = "tok", keyBytes = key).post(peer(port), payload()))
        }
    }

    @Test fun postRejectsNon200() {
        withServer({ HttpServer.Response.text(500, "save failed") }) { port ->
            assertFalse(OkHttpPoster("phone", token = "tok", keyBytes = key).post(peer(port), payload()))
        }
    }

    /** Unpaired legacy path (no key): a 200 still counts — nothing to verify against. */
    @Test fun postWithoutKeyAcceptsPlain200() {
        withServer({ HttpServer.Response.json(200, receiveResult()) }) { port ->
            assertTrue(OkHttpPoster("phone").post(peer(port), payload()))
        }
    }

    /**
     * THE bug (29/09/2026): sharing a 147 MB video read it whole and sealed it in memory
     * (~3 copies) → OutOfMemoryError → the app died, service included. A payload bigger
     * than the whole heap must reach the peer intact, and only the sealed 200 proves it.
     */
    @Test(timeout = 180_000) fun postStreamsAPayloadBiggerThanTheHeap() {
        val length = biggerThanTheHeap()
        var received: ByteArray? = null
        withServer({ req ->
            val sink = DigestSink()
            WireBody(key).read(req.body, req.contentLength, sink)
            received = sink.digest()
            sealed200()
        }) { port ->
            val big = FilePayload("big.mp4", "video/mp4", length) { PatternInputStream(length) }
            assertTrue(OkHttpPoster("phone", token = "tok", keyBytes = key).post(peer(port), big))
        }
        assertArrayEquals(sha256Of(PatternInputStream(length)), received)
    }

    /** Progress counts the file's own bytes as they go out, up to its length. */
    @Test fun postReportsProgressUpToThePayloadLength() {
        val counts = mutableListOf<Long>()
        val length = 300_000L
        withServer({ req ->
            WireBody(key).read(req.body, req.contentLength, DigestSink())
            sealed200()
        }) { port ->
            val payload = FilePayload("p.bin", "application/octet-stream", length) { PatternInputStream(length) }
            assertTrue(OkHttpPoster("phone", token = "tok", keyBytes = key).post(peer(port), payload) { counts += it })
        }
        assertEquals(length, counts.last())
        assertEquals(counts.sorted(), counts)
    }

    /** The bytes are never kept between attempts: a retry re-opens the source and
     *  delivers the whole file again. */
    @Test fun retryReopensThePayload() {
        val requests = AtomicInteger()
        val opens = AtomicInteger()
        val msg = "retry me".toByteArray()
        var delivered: ByteArray? = null
        withServer({ req ->
            val body = RD2Crypto.open(req.bodyBytes(), key)
            if (requests.incrementAndGet() == 1) HttpServer.Response.text(500, "save failed")
            else { delivered = body; sealed200() }
        }) { port ->
            val payload = FilePayload("r.txt", "text/plain", msg.size.toLong()) {
                opens.incrementAndGet(); msg.inputStream()
            }
            val poster = OkHttpPoster("phone", token = "tok", keyBytes = key)
            val outcome = TransferOrchestrator(RetrySender(sleeper = {})) { peer, p, onBytes -> poster.post(peer, p, onBytes) }
                .sendAll(peer(port), listOf(payload))
            assertEquals(1, outcome.sent)
        }
        assertEquals(2, opens.get())
        assertArrayEquals(msg, delivered)
    }

    /** One process-wide OkHttpClient: a fresh client per call leaked a dispatcher
     *  thread pool + connection pool every 1.5s clipboard poll (B1). */
    @Test fun sharedClientIsASingleton() {
        assertSame(RD2Http.client, RD2Http.client)
    }
}
