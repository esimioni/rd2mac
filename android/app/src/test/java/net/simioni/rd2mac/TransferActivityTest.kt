package net.simioni.rd2mac

import net.simioni.rd2mac.http.HttpServer
import net.simioni.rd2mac.update.TransferActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** What a self-update waits for before it kills the app. Mirror of the Mac's tests. */
class TransferActivityTest {

    @Test fun quietOnlyWhenNothingMovedForTheQuietPeriod() {
        var now = 1_800_000_000_000L
        val activity = TransferActivity { now }
        assertTrue(activity.isQuiet)                         // nothing ever moved
        activity.begin()
        assertFalse(activity.isQuiet)
        activity.begin()                                     // two at once
        activity.end()
        assertFalse(activity.isQuiet)                        // one still going
        activity.end()
        assertFalse(activity.isQuiet)                        // a batch's next file may follow
        now += TransferActivity.QUIET_PERIOD_MS - 1
        assertFalse(activity.isQuiet)
        now += 1
        assertTrue(activity.isQuiet)
    }

    @Test fun aTransferThatThrowsStillEnds() {
        var now = 1_800_000_000_000L
        val activity = TransferActivity { now }
        runCatching { activity.track { throw IllegalStateException("cut") } }
        now += TransferActivity.QUIET_PERIOD_MS
        assertTrue(activity.isQuiet)                         // not stuck "in flight"
    }

    @Test fun onlyAPhoneTransferCountsAndUntilItsBodyIsWritten() {
        var now = 1_800_000_000_000L
        val activity = TransferActivity { now }
        val quietDuring = ConcurrentHashMap<String, Boolean>()
        val server = HttpServer(0, activity = activity, tracks = ::isPhoneTransfer) { request ->
            quietDuring[request.path] = activity.isQuiet
            HttpServer.Response.text(200, "ok")
        }
        server.start()
        try {
            // The ping first: right after a transfer, the quiet period would still be running.
            for (path in listOf(RD2Mac.Path.PING, RD2Mac.Path.MEDIA_LATEST)) {
                val conn = URL("http://127.0.0.1:${server.boundPort}$path").openConnection() as HttpURLConnection
                assertEquals(200, conn.responseCode)
                conn.inputStream.use { it.readBytes() }
            }
        } finally {
            server.stop()
        }
        assertEquals(true, quietDuring[RD2Mac.Path.PING])           // a ping is not a transfer
        assertEquals(false, quietDuring[RD2Mac.Path.MEDIA_LATEST])  // a pull is
        now += TransferActivity.QUIET_PERIOD_MS
        val deadline = System.currentTimeMillis() + 2_000           // the server thread ends it
        while (!activity.isQuiet && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(activity.isQuiet)
    }

    @Test fun whichRequestsArePhoneTransfers() {
        assertTrue(isPhoneTransfer("POST", RD2Mac.Path.RECEIVE))
        assertTrue(isPhoneTransfer("GET", RD2Mac.Path.SCREENSHOT_LATEST))
        assertTrue(isPhoneTransfer("GET", RD2Mac.Path.MEDIA_LATEST))
        assertFalse(isPhoneTransfer("GET", RD2Mac.Path.PING))
        assertFalse(isPhoneTransfer("POST", RD2Mac.Path.CLIPBOARD))
        assertFalse(isPhoneTransfer("GET", RD2Mac.Path.RECEIVE))
    }
}
