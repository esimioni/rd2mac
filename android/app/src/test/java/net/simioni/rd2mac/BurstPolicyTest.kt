package net.simioni.rd2mac

import net.simioni.rd2mac.net.BurstPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the proximity monitor spends a scan burst. Samsung's Bluetooth stack
 *  counts every scan result an app receives with the screen OFF, and Device care
 *  flags the app past its limit (2026-10-22: 737 results/h overnight). A near
 *  gate keeps scanning (its burst stops at the first renewing reading); a far one
 *  waits for the screen, which re-checks it at once. */
class BurstPolicyTest {

    @Test fun screenOnAlwaysScans() {
        assertTrue(BurstPolicy.shouldScan(screenOn = true, near = false))
        assertTrue(BurstPolicy.shouldScan(screenOn = true, near = true))
    }

    /** Keeps "near" alive while the phone sits dark on the desk, and notices it leave. */
    @Test fun screenOffKeepsANearGateScanning() {
        assertTrue(BurstPolicy.shouldScan(screenOn = false, near = true))
    }

    @Test fun screenOffFarGateDoesNotScan() {
        assertFalse(BurstPolicy.shouldScan(screenOn = false, near = false))
    }

    @Test fun screenOnKicksAFarGate() {
        assertTrue(BurstPolicy.shouldKick(near = false, sinceLastStartMs = BurstPolicy.MIN_START_GAP_MS))
    }

    @Test fun aNearGateNeedsNoKick() {
        assertFalse(BurstPolicy.shouldKick(near = true, sinceLastStartMs = 60_000))
    }

    /** Android fails a scan after 5 starts in 30 s (code 6, never retried): a
     *  screen switched on and off every second must stay under it. */
    @Test fun kicksNeverCrowdTheScanThrottle() {
        assertFalse(BurstPolicy.shouldKick(near = false,
            sinceLastStartMs = BurstPolicy.MIN_START_GAP_MS - 1))
        assertTrue(30_000 / BurstPolicy.MIN_START_GAP_MS + 1 < 5)
    }
}