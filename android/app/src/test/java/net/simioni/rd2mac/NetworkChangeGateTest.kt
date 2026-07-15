package net.simioni.rd2mac

import net.simioni.rd2mac.net.NetworkChangeGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `registerDefaultNetworkCallback` fires `onAvailable` immediately for the
 *  CURRENT network — treating that as "network changed" invalidated the freshly
 *  resolved peer right at service startup (and NSD never re-emits a service
 *  that didn't blink, so the peer stayed unresolved). Only a real replacement
 *  counts as a change. */
class NetworkChangeGateTest {

    @Test fun firstNetworkIsNotAChange() {
        assertFalse(NetworkChangeGate().isChange("wifi-1"))
    }

    @Test fun sameNetworkAgainIsNotAChange() {
        val g = NetworkChangeGate()
        g.isChange("wifi-1")
        assertFalse(g.isChange("wifi-1"))
    }

    @Test fun replacementIsAChange() {
        val g = NetworkChangeGate()
        g.isChange("wifi-1")
        assertTrue(g.isChange("cell-1"))
    }

    @Test fun stableAfterAChangeIsQuiet() {
        val g = NetworkChangeGate()
        g.isChange("wifi-1")
        g.isChange("cell-1")
        assertFalse(g.isChange("cell-1"))
    }
}
