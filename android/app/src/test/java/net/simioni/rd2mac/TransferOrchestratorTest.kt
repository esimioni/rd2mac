package net.simioni.rd2mac

import net.simioni.rd2mac.net.FilePayload
import net.simioni.rd2mac.net.ResolvedPeer
import net.simioni.rd2mac.net.RetrySender
import net.simioni.rd2mac.net.TransferOrchestrator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferOrchestratorTest {

    private val peer = ResolvedPeer("mac-1", "Mac", "127.0.0.1", 53318, Platform.MACOS)
    private val noDelay = RetrySender(maxAttempts = 3, sleeper = {})
    private fun payload(name: String) = FilePayload(name, "image/jpeg", ByteArray(3))

    @Test fun allSucceed() {
        val orch = TransferOrchestrator(noDelay) { _, _ -> true }
        val outcome = orch.sendAll(peer, listOf(payload("a"), payload("b")))
        assertEquals(2, outcome.sent)
        assertEquals(0, outcome.failed)
        assertTrue(outcome.allSent)
    }

    @Test fun oneFailureIsCountedNotSwallowed() {
        val orch = TransferOrchestrator(noDelay) { _, p -> p.filename != "b" }
        val outcome = orch.sendAll(peer, listOf(payload("a"), payload("b")))
        assertEquals(1, outcome.sent)
        assertEquals(1, outcome.failed)
        assertFalse(outcome.allSent)
    }
}
