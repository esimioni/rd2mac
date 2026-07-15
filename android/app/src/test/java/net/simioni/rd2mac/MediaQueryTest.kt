package net.simioni.rd2mac

import net.simioni.rd2mac.media.MediaQuery
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaQueryTest {

    @Test fun skipDefaultsToNewest() {
        assertEquals(0, MediaQuery.skip(null))   // old Mac never sends skip → newest
        assertEquals(0, MediaQuery.skip(""))
    }

    @Test fun skipParsesPositions() {
        assertEquals(0, MediaQuery.skip("0"))
        assertEquals(2, MediaQuery.skip("2"))
    }

    @Test fun skipNeverNegativeOrGarbage() {
        assertEquals(0, MediaQuery.skip("-3"))    // negative offset makes no sense
        assertEquals(0, MediaQuery.skip("abc"))   // garbage → newest
    }
}
