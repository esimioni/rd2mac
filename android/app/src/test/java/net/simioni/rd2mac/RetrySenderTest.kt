package net.simioni.rd2mac

import net.simioni.rd2mac.net.RetrySender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetrySenderTest {

    private val noSleep: (Long) -> Unit = {}

    @Test fun succeedsAfterTransientFailures() {
        var calls = 0
        val sender = RetrySender(maxAttempts = 3, sleeper = noSleep)
        val ok = sender.send { calls++; calls >= 2 }   // fail once, then succeed
        assertTrue(ok)
        assertEquals(2, calls)
    }

    /** The phantom-send guarantee: if nothing ever really delivered, never report success. */
    @Test fun neverReportsSuccessWhenAllAttemptsFail() {
        var calls = 0
        val sender = RetrySender(maxAttempts = 3, sleeper = noSleep)
        val ok = sender.send { calls++; false }
        assertFalse(ok)
        assertEquals(3, calls)
    }

    @Test fun exceptionCountsAsFailureNotSuccess() {
        val sender = RetrySender(maxAttempts = 2, sleeper = noSleep)
        val ok = sender.send { throw RuntimeException("network down") }
        assertFalse(ok)
    }
}
