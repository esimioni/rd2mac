package net.simioni.rd2mac

import net.simioni.rd2mac.net.ProximityReporter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decides when the phone tells the Mac its proximity-gate status: on any state
 *  change immediately, plus a keepalive while enabled (so the Mac can expire a
 *  stale row after silence); "gate off" is reported once, never kept alive. */
class ProximityReporterTest {

    private fun reporter() = ProximityReporter(keepaliveMs = 60_000L)

    @Test fun firstEvaluationReports() {
        assertTrue(reporter().shouldReport(enabled = true, near = false, nowMs = 0))
    }

    @Test fun unchangedStateStaysQuietUntilKeepalive() {
        val r = reporter()
        r.shouldReport(enabled = true, near = false, nowMs = 0)
        assertFalse(r.shouldReport(enabled = true, near = false, nowMs = 30_000))
        assertTrue("keepalive due", r.shouldReport(enabled = true, near = false, nowMs = 60_000))
        assertFalse(r.shouldReport(enabled = true, near = false, nowMs = 90_000))
    }

    @Test fun nearFlipReportsImmediately() {
        val r = reporter()
        r.shouldReport(enabled = true, near = false, nowMs = 0)
        assertTrue(r.shouldReport(enabled = true, near = true, nowMs = 5_000))
    }

    @Test fun disablingReportsOnceThenStaysQuiet() {
        val r = reporter()
        r.shouldReport(enabled = true, near = true, nowMs = 0)
        assertTrue("the off transition must reach the Mac", r.shouldReport(enabled = false, near = false, nowMs = 1_000))
        assertFalse("no keepalive while disabled", r.shouldReport(enabled = false, near = false, nowMs = 120_000))
    }

    @Test fun reenablingReports() {
        val r = reporter()
        r.shouldReport(enabled = true, near = true, nowMs = 0)
        r.shouldReport(enabled = false, near = false, nowMs = 1_000)
        assertTrue(r.shouldReport(enabled = true, near = true, nowMs = 2_000))
    }

    /** A near flip inside the keepalive window must not delay the next keepalive
     *  (the sent-at clock advances with every report). */
    @Test fun keepaliveCountsFromLastReport() {
        val r = reporter()
        r.shouldReport(enabled = true, near = false, nowMs = 0)
        r.shouldReport(enabled = true, near = true, nowMs = 50_000)    // change → sent
        assertFalse(r.shouldReport(enabled = true, near = true, nowMs = 100_000))  // 50s since last
        assertTrue(r.shouldReport(enabled = true, near = true, nowMs = 110_000))   // 60s since last
    }
}
