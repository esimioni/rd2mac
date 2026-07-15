package net.simioni.rd2mac

import org.junit.Assert.assertEquals
import org.junit.Test

class HeaderCodecTest {

    /** Fixed vector shared with the Swift `testHeaderEncodingFixedVector`. */
    @Test fun fixedVectorMatchesSwift() {
        assertEquals("A1%20b%2Epng", HeaderCodec.encode("A1 b.png"))
        assertEquals("A1 b.png", HeaderCodec.decode("A1%20b%2Epng"))
    }

    @Test fun roundTripPreservesUnicodeAndSpaces() {
        val name = "Café screenshot 2026 (1).png"
        assertEquals(name, HeaderCodec.decode(HeaderCodec.encode(name)))
    }

    @Test fun decodeToleratesStrayPercent() {
        // A lone % that isn't a valid escape is passed through, not crashed on.
        assertEquals("100%done", HeaderCodec.decode("100%done"))
    }
}
