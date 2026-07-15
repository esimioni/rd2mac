package net.simioni.rd2mac

import net.simioni.rd2mac.net.ClipboardGate
import org.junit.Assert.assertEquals
import org.junit.Test

class ClipboardGateTest {

    /** Symptom: flipping the receive switch pasted whatever the Mac had copied
     *  hours earlier. The first response only sets the baseline. */
    @Test fun firstResponsePrimesWithoutPasting() {
        assertEquals(7 to false, ClipboardGate.advance(ClipboardGate.UNPRIMED, 7))
    }

    @Test fun newVersionAfterBaselinePastes() {
        assertEquals(8 to true, ClipboardGate.advance(7, 8))
    }

    @Test fun unchangedVersionDoesNothing() {
        assertEquals(7 to false, ClipboardGate.advance(7, 7))
    }

    /** Service restart re-primes: no re-paste of the same Mac clipboard on reboot. */
    @Test fun restartCycleNeverRepastesOldContent() {
        var (last, paste) = ClipboardGate.advance(ClipboardGate.UNPRIMED, 42)
        assertEquals(false, paste)
        val second = ClipboardGate.advance(last, 42)
        assertEquals(42 to false, second)
    }
}
