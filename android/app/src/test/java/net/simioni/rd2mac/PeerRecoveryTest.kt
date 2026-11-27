package net.simioni.rd2mac

import net.simioni.rd2mac.net.PairedPeer
import net.simioni.rd2mac.net.PeerIdentity
import net.simioni.rd2mac.net.PeerRecovery
import net.simioni.rd2mac.net.ResolvedPeer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The unicast way back to the paired Mac. The bug (27/11/2026): a failed request
 * dropped the Mac's address, and from then on only mDNS could bring it back. On a
 * Wi-Fi that delivered none of the Mac's announcements (six in 30 min, all logged by
 * the Mac's mDNSResponder), the phone said "Looking on the network…" for half an
 * hour — while `GET /ping` at the Mac's last address answered at once. A share
 * failed and the Mac's clipboard never arrived.
 */
class PeerRecoveryTest {

    private val mac = PairedPeer("mac-id", "Mac", "10.0.0.5", 53318, token = "t", key = "k")
    private val atLastAddress = ResolvedPeer("mac-id", "Mac", "10.0.0.5", 53318, Platform.MACOS)

    private fun recovery() = PeerRecovery(everyMs = 15_000, debounceMs = 2_000)

    // when to knock

    @Test fun lostMacIsTriedAtItsLastAddressRightAway() {
        assertEquals(atLastAddress, recovery().due(mac, resolved = false, onWifi = true, now = 0))
    }

    @Test fun notTriedWhileResolved() {
        assertNull(recovery().due(mac, resolved = true, onWifi = true, now = 0))
    }

    @Test fun notTriedWithoutAPairingOrAnAddress() {
        assertNull(recovery().due(null, resolved = false, onWifi = true, now = 0))
        // Paired from the Mac's side before discovery resolved it: no address yet.
        assertNull(recovery().due(mac.copy(host = ""), resolved = false, onWifi = true, now = 0))
    }

    @Test fun notTriedOffWifi() {
        // On mobile data a LAN address is unreachable, and each try would wake the radio.
        assertNull(recovery().due(mac, resolved = false, onWifi = false, now = 0))
    }

    @Test fun retriedEveryPeriodWhileTheMacStaysLost() {
        val r = recovery()
        assertNotNull(r.due(mac, resolved = false, onWifi = true, now = 0))
        assertNull(r.due(mac, resolved = false, onWifi = true, now = 14_999))
        assertNotNull(r.due(mac, resolved = false, onWifi = true, now = 15_000))
    }

    @Test fun forcedTryIsOnlyDebounced() {
        val r = recovery()
        assertNotNull(r.due(mac, resolved = false, onWifi = true, now = 0, forced = true))
        // A burst of reasons (screen opened + share waiting) costs one probe.
        assertNull(r.due(mac, resolved = false, onWifi = true, now = 1_999, forced = true))
        assertNotNull(r.due(mac, resolved = false, onWifi = true, now = 2_000, forced = true))
    }

    @Test fun macDroppedRightAfterItsRecoveryWaitsThePeriod() {
        // An address that answers /ping but fails the poll must not loop
        // probe → adopt → drop → probe on every tick.
        val r = recovery()
        assertNotNull(r.due(mac, resolved = false, onWifi = true, now = 0))
        assertNull(r.due(mac, resolved = true, onWifi = true, now = 1_000))
        assertNull(r.due(mac, resolved = false, onWifi = true, now = 5_000))
        assertNotNull(r.due(mac, resolved = false, onWifi = true, now = 15_000))
    }

    // what answers

    @Test fun adoptsTheAddressWhenThePairedMacAnswers() {
        val answer = PeerIdentity("mac-id", "Mac renamed", Platform.MACOS)
        assertEquals(atLastAddress.copy(name = "Mac renamed"), PeerRecovery.confirm(atLastAddress, answer))
    }

    @Test fun ignoresAnotherDeviceAtTheOldAddress() {
        // The DHCP lease may have gone to someone else since.
        val stranger = PeerIdentity("other-id", "Mac", Platform.MACOS)
        assertNull(PeerRecovery.confirm(atLastAddress, stranger))
    }

    @Test fun ignoresNoAnswer() {
        assertNull(PeerRecovery.confirm(atLastAddress, null))
    }

    @Test fun parsesThePingAnswer() {
        val body = """{"id":"mac-id","platform":"macos","name":"Mac","v":2}"""
        assertEquals(PeerIdentity("mac-id", "Mac", Platform.MACOS), PeerIdentity.parse(body))
    }

    @Test fun anythingButAnIdentityParsesToNull() {
        assertNull(PeerIdentity.parse(null))
        assertNull(PeerIdentity.parse("<html>not json</html>"))
        assertNull(PeerIdentity.parse("""{"name":"Mac","platform":"macos"}"""))   // no id confirms nobody
    }

    // the address it knocks on

    @Test fun lastKnownIsThePairedMacAtItsStoredAddress() {
        assertEquals(atLastAddress, mac.lastKnown())
        assertNull(mac.copy(host = "").lastKnown())
    }

    @Test fun pairingFollowsTheMacToANewAddress() {
        val moved = atLastAddress.copy(host = "10.0.0.9", port = 53320)
        assertEquals(mac.copy(host = "10.0.0.9", port = 53320), mac.movedTo(moved))
    }

    @Test fun sameAddressOrAnotherPeerLeavesThePairingAlone() {
        assertNull(mac.movedTo(atLastAddress))
        assertNull(mac.movedTo(atLastAddress.copy(id = "other-id", host = "10.0.0.9")))
    }
}