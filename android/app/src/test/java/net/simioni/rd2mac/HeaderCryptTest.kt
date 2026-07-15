package net.simioni.rd2mac

import org.junit.Assert.assertEquals
import org.junit.Test

class HeaderCryptTest {

    private val key = ByteArray(32) { 0x2A }
    private val name = "Relatório final (cópia) 2026.pdf"

    @Test fun sealedRoundTripWithKey() {
        val encoded = HeaderCrypt.encode(name, key)
        // Sealed form must not leak the filename to a sniffer holding the token.
        org.junit.Assert.assertFalse(encoded.contains("Relat"))
        assertEquals(name, HeaderCrypt.decode(encoded, key))
    }

    @Test fun withoutKeyFallsBackToPercentEncoding() {
        val encoded = HeaderCrypt.encode(name, null)
        assertEquals(HeaderCodec.encode(name), encoded)
        assertEquals(name, HeaderCrypt.decode(encoded, null))
    }

    /** A plain-encoded value arriving where a key exists (loopback hop, older
     *  peer) must still decode — never a hard failure over a filename. */
    @Test fun decodeWithKeyToleratesPlainValue() {
        assertEquals(name, HeaderCrypt.decode(HeaderCodec.encode(name), key))
    }
}
