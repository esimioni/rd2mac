package net.simioni.rd2mac

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which broadcasts bring the service back. A user-confirmed self-update (0.7 → 0.8
 *  on the S23, 2026-10-23) never delivered MY_PACKAGE_REPLACED, so the phone stayed
 *  off the network until the app was opened by hand; a standing keep-alive alarm,
 *  which outlives the update, now covers that door too. */
class RestartPolicyTest {

    @Test fun bootAndUpdateAlwaysStart() {
        assertTrue(RestartPolicy.shouldStart(Intent.ACTION_BOOT_COMPLETED, running = false))
        assertTrue(RestartPolicy.shouldStart(Intent.ACTION_MY_PACKAGE_REPLACED, running = false))
    }

    @Test fun keepAliveRestartsOnlyAServiceThatIsGone() {
        assertTrue(RestartPolicy.shouldStart(KeepAlive.ACTION, running = false))
        assertFalse(RestartPolicy.shouldStart(KeepAlive.ACTION, running = true))
    }

    @Test fun anythingElseIsIgnored() {
        assertFalse(RestartPolicy.shouldStart("com.example.SOMETHING", running = false))
        assertFalse(RestartPolicy.shouldStart(null, running = false))
    }
}