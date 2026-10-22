package net.simioni.rd2mac

import net.simioni.rd2mac.net.ProximityBeacon
import net.simioni.rd2mac.net.RD2Crypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which beacon the phone listens for. Every RD2Mac Mac used to advertise the same
 *  UUID, so the phone heard a second Mac in the house as its own (2026-10-22: −74 dBm
 *  with the paired Mac's app quit). Now each pairing has its own, rotated daily. */
class ProximityBeaconTest {

    private val key = ByteArray(32) { 0x11 }
    private val midnightUtc = 1_792_627_200_000L   // 2026-10-22T00:00:00Z = day 20748

    @Test fun dayChangesAtMidnightUtc() {
        assertEquals(20748, ProximityBeacon.day(midnightUtc))
        assertEquals(20747, ProximityBeacon.day(midnightUtc - 1))
    }

    /** The Mac switches at 00:00 UTC and the two clocks needn't agree to the second. */
    @Test fun listensForYesterdayTodayAndTomorrow() {
        val noon = midnightUtc + 12 * 3_600_000L
        assertEquals((20747L..20749L).map { RD2Crypto.beaconUuid(key, it) },
            ProximityBeacon.uuidsAround(key, noon))
    }

    @Test fun anotherPairingsBeaconIsNotHeard() {
        val other = ByteArray(32) { 0x22 }
        val mine = ProximityBeacon.uuidsAround(key, midnightUtc).toSet()
        assertTrue(ProximityBeacon.uuidsAround(other, midnightUtc).none { it in mine })
    }
}