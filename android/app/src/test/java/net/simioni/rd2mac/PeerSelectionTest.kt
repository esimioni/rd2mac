package net.simioni.rd2mac

import net.simioni.rd2mac.net.PeerSelection
import net.simioni.rd2mac.net.ResolvedPeer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure active-peer selection — mirror of the Swift `PairingTests`. */
class PeerSelectionTest {

    private val a = ResolvedPeer("1", "A", "10.0.0.1", 53318, Platform.MACOS)
    private val b = ResolvedPeer("2", "B", "10.0.0.2", 53318, Platform.MACOS)

    @Test fun selectsPairedPeerFromList() {
        assertEquals(b, PeerSelection.activePeer(listOf(a, b), "2"))
    }

    @Test fun nullWhenPairedPeerAbsentFromNetwork() {
        assertNull(PeerSelection.activePeer(listOf(a, b), "99"))
    }

    @Test fun nullWhenUnpaired() {
        assertNull(PeerSelection.activePeer(listOf(a, b), null))
        assertNull(PeerSelection.activePeer(listOf(a, b), ""))
    }
}
