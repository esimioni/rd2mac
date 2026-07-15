package net.simioni.rd2mac

import net.simioni.rd2mac.net.ProximityGate
import net.simioni.rd2mac.net.ProximityMonitor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Threshold -75 dBm ("same room"), TTL 90s, hysteresis 8 dB, arming = 3
 *  strong readings within 6s (a lone multipath spike through a closed door
 *  must never re-arm "near" — observed live on 2026-07-10 at 15:16). */
class ProximityGateTest {

    private fun gate() = ProximityGate(nearRssi = -75, ttlMs = 90_000L, hysteresis = 8,
                                       armCount = 3, armWindowMs = 6_000L)

    /** Feed [count] strong readings 300ms apart (a genuine in-range burst). */
    private fun ProximityGate.strongBurst(startMs: Long, count: Int = 3, rssi: Int = -60): Long {
        var t = startMs
        repeat(count) { onReading(rssi = rssi, nowMs = t); t += 300 }
        return t - 300
    }

    @Test fun strongBurstMarksNear() {
        val g = gate()
        g.strongBurst(0)
        assertTrue(g.isNear(nowMs = 1_000))
        assertTrue(g.isNear(nowMs = 89_000))
    }

    @Test fun aSingleSpikeNeverArms() {
        val g = gate()
        assertFalse(g.onReading(rssi = -50, nowMs = 0))    // one loud reading
        assertFalse(g.onReading(rssi = -50, nowMs = 300))  // even two
        assertFalse(g.isNear(nowMs = 1_000))
    }

    @Test fun staleSpikesOutsideTheWindowDoNotAccumulate() {
        val g = gate()
        g.onReading(rssi = -60, nowMs = 0)
        g.onReading(rssi = -60, nowMs = 300)
        g.onReading(rssi = -60, nowMs = 10_000)   // 2 old + 1 fresh: window expired
        assertFalse(g.isNear(nowMs = 10_100))
        g.onReading(rssi = -60, nowMs = 10_300)
        g.onReading(rssi = -60, nowMs = 10_600)   // 3 fresh within 6s → near
        assertTrue(g.isNear(nowMs = 10_700))
    }

    @Test fun silenceDecaysToFarAfterTtl() {
        val g = gate()
        val last = g.strongBurst(0)
        assertFalse("Mac gone (no readings) must decay to far", g.isNear(nowMs = last + 90_001))
    }

    @Test fun weakReadingNeverActivatesNear() {
        val g = gate()
        repeat(5) { assertFalse(g.onReading(rssi = -85, nowMs = it * 300L)) }  // below -75
        assertFalse(g.isNear(nowMs = 2_000))
    }

    /** Borderline (within hysteresis) keeps an existing near alive but can't
     *  start one — stops flapping at the threshold edge. */
    @Test fun borderlineKeepsNearAliveButCannotStartIt() {
        val g = gate()
        repeat(3) { g.onReading(rssi = -80, nowMs = it * 300L) }  // -80 can't start near
        assertFalse(g.isNear(nowMs = 1_000))
        g.strongBurst(1_000, rssi = -70)                          // genuine burst: near
        assertTrue(g.onReading(rssi = -80, nowMs = 60_000))       // -80 keeps it alive
        assertTrue(g.isNear(nowMs = 149_000))                     // TTL extended from 60s
    }

    @Test fun startsFarUntilFirstGoodBurst() {
        assertFalse(gate().isNear(nowMs = 0))
    }

    // MARK: "Same desk" calibration (home measurements 2026-07-10: desk ≈ -40,
    // bathroom through a thick wall ≈ -67/-69 — the default 8 dB hysteresis on
    // the old -60 threshold kept maintaining "near" from the bathroom forever).

    private fun deskGate() = ProximityGate(
        nearRssi = net.simioni.rd2mac.net.ProximityLevels.DESK,
        ttlMs = 90_000L,
        hysteresis = net.simioni.rd2mac.net.ProximityLevels.hysteresisFor(
            net.simioni.rd2mac.net.ProximityLevels.DESK),
    )

    @Test fun deskLevelUsesTightHysteresis() {
        assertTrue(net.simioni.rd2mac.net.ProximityLevels.hysteresisFor(
            net.simioni.rd2mac.net.ProximityLevels.DESK) < 8)
        org.junit.Assert.assertEquals(8, net.simioni.rd2mac.net.ProximityLevels.hysteresisFor(
            net.simioni.rd2mac.net.ProximityLevels.ROOM))
    }

    @Test fun deskGateHoldsAtTheDeskAndDropsTheBathroom() {
        val g = deskGate()
        var t = 0L
        repeat(3) { g.onReading(rssi = -40, nowMs = t); t += 300 }   // at the desk
        assertTrue(g.isNear(nowMs = t))
        // Phone moved behind the thick wall: steady -67 must NOT maintain…
        var walk = t
        while (walk < t + 100_000) { g.onReading(rssi = -67, nowMs = walk); walk += 1_000 }
        assertFalse("bathroom floor must decay the desk level", g.isNear(nowMs = walk))
        // …while a slightly-weakened desk reading (-52, within 4 dB) does.
        val g2 = deskGate()
        var t2 = 0L
        repeat(3) { g2.onReading(rssi = -40, nowMs = t2); t2 += 300 }
        g2.onReading(rssi = -52, nowMs = 80_000)
        assertTrue("−52 is still the desk", g2.isNear(nowMs = 160_000))
    }

    /** The monitor scans in bursts (CYCLE_MS apart): the service's gate TTL must
     *  survive one missed burst (RF fluke) without flapping to "far", but two
     *  consecutive silent cycles mean the Mac is really gone. */
    @Test fun serviceTtlSurvivesOneMissedBurstCycle() {
        val g = ProximityGate(nearRssi = -75, ttlMs = ProximityMonitor.TTL_MS)
        g.strongBurst(0)
        assertTrue("one missed burst must not flap", g.isNear(nowMs = 2 * ProximityMonitor.CYCLE_MS))
        assertFalse("two misses = really gone", g.isNear(nowMs = 3 * ProximityMonitor.CYCLE_MS))
    }
}
