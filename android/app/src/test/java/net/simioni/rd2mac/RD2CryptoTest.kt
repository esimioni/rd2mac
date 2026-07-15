package net.simioni.rd2mac

import net.simioni.rd2mac.net.RD2Crypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Interop proof: given the SAME fixed private keys, the Android crypto must derive
 * the exact key/token/SAS the Swift `CryptoTests` emitted (vector below). If this
 * passes, ECDH + HKDF + SAS agree byte-for-byte across platforms.
 */
class RD2CryptoTest {
    private val privA = ByteArray(32) { 0x11 }
    private val privB = ByteArray(32) { 0x22 }

    // Vector emitted by the Swift CryptoTests.
    private val swiftPubA = "BAIX5hfwtkQ5KCePlpmeaaI6TywVK99tbN9m5bgCgtTtGUp968uXcS0t2jyoWqh2Wlb0X8dYWZZS8ol8ZTBuV5Q="
    private val swiftPubB = "BNZak5d8qj0bCBhS/1ennkZfFmBXcwS66tUF3TpIWJzzUBheiVNy32Ih6joTdVfkc/3bZ1XwW9UHw8Uz/OnJEoU="
    private val expectedKey = "69014a103caa297b1137fcb9af45669c2c408327ee7eb8ae7600be3345e1b4dc"
    private val expectedToken = "01fe9f4750d72ebe579c5fd37438a294"
    private val expectedSas = "473162"

    @Test fun derivesSameSessionAsSwift() {
        val sA = RD2Crypto.agree(RD2Crypto.privateKeyFromRaw(privA), swiftPubB)
        assertEquals(expectedKey, sA.keyBytes.joinToString("") { "%02x".format(it) })
        assertEquals(expectedToken, sA.token)
        assertEquals(expectedSas, sA.sas)

        val sB = RD2Crypto.agree(RD2Crypto.privateKeyFromRaw(privB), swiftPubA)
        assertEquals(expectedKey, sB.keyBytes.joinToString("") { "%02x".format(it) })
    }

    @Test fun sealOpenRoundTrip() {
        val key = ByteArray(32) { 0xAB.toByte() }
        val msg = "secret payload".toByteArray()
        val blob = RD2Crypto.seal(msg, key)
        assertArrayEquals(msg, RD2Crypto.open(blob, key))
    }
}
