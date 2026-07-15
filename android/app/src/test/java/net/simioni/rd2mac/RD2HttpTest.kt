package net.simioni.rd2mac

import net.simioni.rd2mac.http.HttpServer
import net.simioni.rd2mac.net.RD2Http
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

/** `RD2Http.execute` maps responses and signals CONNECTIVITY failures via
 *  `onError` — the hook the service uses to invalidate a stale peer address
 *  (and wake the dormant mDNS browse). A reachable server that answers, even
 *  non-200, is not a connectivity failure. */
class RD2HttpTest {

    @Test fun mapsAReachableResponse() {
        val server = HttpServer(0) { HttpServer.Response.text(200, "pong") }
        server.start()
        try {
            var errored = false
            val result = RD2Http.execute(
                Request.Builder().url("http://127.0.0.1:${server.boundPort}/ping").build(),
                onError = { errored = true },
            ) { it.code }
            assertEquals(200, result)
            assertFalse(errored)
        } finally {
            server.stop()
        }
    }

    @Test fun connectionFailureFiresOnErrorAndReturnsNull() {
        // Grab an ephemeral port and close it: nothing listens there.
        val deadPort = ServerSocket(0).use { it.localPort }
        var errored = false
        val result = RD2Http.execute(
            Request.Builder().url("http://127.0.0.1:$deadPort/ping").build(),
            onError = { errored = true },
        ) { it.code }
        assertNull(result)
        assertTrue(errored)
    }
}
