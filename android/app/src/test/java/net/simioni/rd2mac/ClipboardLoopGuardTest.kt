package net.simioni.rd2mac

import net.simioni.rd2mac.net.ClipboardLoopGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The loop scenario: Mac copy → phone pastes → clip-changed listener fires →
 * would push back to Mac → Mac changeCount bumps → phone would poll it back → ∞.
 * The guard must cut both directions while letting genuinely new content flow.
 */
class ClipboardLoopGuardTest {

    private val fromMac = "copied on the Mac".toByteArray()
    private val fromPhone = "copied on the phone".toByteArray()

    @Test fun macContentPastesButDoesNotBounceBack() {
        val guard = ClipboardLoopGuard()
        assertTrue(guard.shouldPaste(fromMac))          // paste it locally
        assertFalse(guard.shouldPush(fromMac))          // listener fires: do NOT echo to Mac
    }

    @Test fun phoneCopyPushesButItsPollEchoDoesNotRepaste() {
        val guard = ClipboardLoopGuard()
        assertTrue(guard.shouldPush(fromPhone))         // send to Mac
        assertFalse(guard.shouldPaste(fromPhone))       // Mac serves it back: do NOT re-paste
    }

    @Test fun newContentFlowsInBothDirectionsAfterEchoes() {
        val guard = ClipboardLoopGuard()
        assertTrue(guard.shouldPaste(fromMac))
        assertFalse(guard.shouldPush(fromMac))
        assertTrue(guard.shouldPush(fromPhone))         // a real new phone copy still goes out
        assertTrue(guard.shouldPaste("new mac copy".toByteArray()))
    }

    /** Copying the exact same content twice on the phone stays suppressed — the
     *  Mac already holds it; a re-push would only re-bump its changeCount. */
    @Test fun identicalRecopyIsNotRepushed() {
        val guard = ClipboardLoopGuard()
        assertTrue(guard.shouldPush(fromPhone))
        assertFalse(guard.shouldPush(fromPhone))
    }
}
