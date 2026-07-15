package net.simioni.rd2mac

import net.simioni.rd2mac.net.PairingAuth
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure handshake authorization — mirror of the Swift `PairingAuth` tests. */
class PairingAuthTest {

    @Test fun authorizeRequiresPairingAndMatchingToken() {
        assertTrue(PairingAuth.authorize("t", "t"))
        assertFalse(PairingAuth.authorize("t", "x"))
        assertFalse(PairingAuth.authorize("t", null))
        assertFalse(PairingAuth.authorize(null, "t"))  // unpaired: accept nothing
        assertFalse(PairingAuth.authorize("", ""))     // unpaired
    }

    @Test fun acceptPairGuardsEstablishedPairing() {
        assertTrue(PairingAuth.acceptPair(null, "a"))  // free
        assertTrue(PairingAuth.acceptPair("", "a"))    // free
        assertTrue(PairingAuth.acceptPair("a", "a"))   // same peer re-pairs
        assertFalse(PairingAuth.acceptPair("a", "b"))  // hijack attempt
    }
}
