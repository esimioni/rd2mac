package net.simioni.rd2mac

import net.simioni.rd2mac.net.ProximityLine
import org.junit.Assert.assertEquals
import org.junit.Test

/** The line under the proximity switch. The bug (27/11/2026): it read "Mac nearby ✓ —
 *  sync active" from the Bluetooth beacon alone, for half an hour in which the phone
 *  could not find the Mac on the network and nothing synced. */
class ProximityLineTest {

    @Test fun syncIsActiveOnlyWithTheMacNearAndOnTheNetwork() {
        assertEquals(ProximityLine.SYNC_ACTIVE, ProximityLine.of(near = true, macOnNetwork = true))
    }

    @Test fun nearButNotOnTheNetworkSaysSo() {
        assertEquals(ProximityLine.NOT_ON_NETWORK, ProximityLine.of(near = true, macOnNetwork = false))
    }

    @Test fun awayIsAwayWhateverTheNetworkSays() {
        assertEquals(ProximityLine.AWAY, ProximityLine.of(near = false, macOnNetwork = true))
        assertEquals(ProximityLine.AWAY, ProximityLine.of(near = false, macOnNetwork = false))
    }
}