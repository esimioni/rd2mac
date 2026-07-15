package net.simioni.rd2mac

import net.simioni.rd2mac.http.HttpServer
import net.simioni.rd2mac.net.FilePayload
import net.simioni.rd2mac.net.OkHttpPoster
import net.simioni.rd2mac.net.RD2Crypto
import net.simioni.rd2mac.net.RD2Http
import net.simioni.rd2mac.net.ResolvedPeer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OkHttpPoster against a real local HttpServer. Focus: the delivery confirmation
 * is only trusted when the peer proves it holds the pairing key (sealed 200 body)
 * — a plain 200 could come from an active MITM that never persisted anything,
 * which would resurrect the phantom-send bug the project exists to kill.
 */
class SenderTest {

    private val key = ByteArray(32) { 0x2A }
    private fun peer(port: Int) = ResolvedPeer("mac-id", "TestMac", "127.0.0.1", port, Platform.MACOS)
    private fun payload() = FilePayload("note.txt", "text/plain", "hello".toByteArray())
    // Literal instead of org.json: the android.jar test stub throws "not mocked".
    private fun receiveResult() = """{"saved":"/tmp/note.txt","bytes":5}"""

    private fun withServer(handler: (HttpServer.Request) -> HttpServer.Response, test: (Int) -> Unit) {
        val server = HttpServer(0, handler = handler)
        server.start()
        try { test(server.boundPort) } finally { server.stop() }
    }

    @Test fun postAcceptsSealed200() {
        withServer({ req ->
            // Echo protocol: prove we decrypted the upload, answer with a sealed body.
            RD2Crypto.open(req.body, key)
            HttpServer.Response.bytes(
                200, "application/octet-stream",
                RD2Crypto.seal(receiveResult().toByteArray(), key))
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

    /** One process-wide OkHttpClient: a fresh client per call leaked a dispatcher
     *  thread pool + connection pool every 1.5s clipboard poll (B1). */
    @Test fun sharedClientIsASingleton() {
        assertSame(RD2Http.client, RD2Http.client)
    }
}
