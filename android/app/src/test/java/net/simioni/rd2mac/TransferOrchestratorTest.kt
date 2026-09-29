package net.simioni.rd2mac

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
    private fun payload(name: String) = bytesPayload(name, "image/jpeg", ByteArray(3))

    @Test fun allSucceed() {
        val orch = TransferOrchestrator(noDelay) { _, _, _ -> true }
        val outcome = orch.sendAll(peer, listOf(payload("a"), payload("b")))
        assertEquals(2, outcome.sent)
        assertEquals(0, outcome.failed)
        assertTrue(outcome.allSent)
    }

    @Test fun oneFailureIsCountedNotSwallowed() {
        val orch = TransferOrchestrator(noDelay) { _, p, _ -> p.filename != "b" }
        val outcome = orch.sendAll(peer, listOf(payload("a"), payload("b")))
        assertEquals(1, outcome.sent)
        assertEquals(1, outcome.failed)
        assertFalse(outcome.allSent)
    }

    /** The share runs after its screen is gone, so its progress is all the user sees:
     *  one bar for the whole batch, ending exactly at the total. */
    @Test fun progressCoversTheWholeBatch() {
        val seen = mutableListOf<Triple<Int, Long, Long>>()
        val orch = TransferOrchestrator(noDelay) { _, p, onBytes ->
            onBytes(p.length / 2)
            onBytes(p.length)
            true
        }
        val batch = listOf(bytesPayload("a", "image/jpeg", ByteArray(10)), bytesPayload("b", "image/jpeg", ByteArray(20)))
        orch.sendAll(peer, batch) { index, done, total -> seen += Triple(index, done, total) }
        assertTrue(seen.all { it.third == 30L })
        assertEquals(listOf(5L, 10L, 10L, 20L, 30L, 30L), seen.map { it.second })
        assertEquals(listOf(0, 0, 0, 1, 1, 1), seen.map { it.first })
    }

    /** A failed file still moves the bar past it — the batch ends at its total either way. */
    @Test fun failedFileStillCompletesTheBar() {
        var last = 0L
        val orch = TransferOrchestrator(noDelay) { _, p, _ -> p.filename != "a" }
        orch.sendAll(peer, listOf(payload("a"), payload("b"))) { _, done, _ -> last = done }
        assertEquals(6L, last)
    }
}
