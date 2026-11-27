package net.simioni.rd2mac

import net.simioni.rd2mac.net.BrowsePolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Continuous mDNS browse only while it's actually needed; once the paired Mac
 *  is resolved and nobody is looking at the pick list, the browse goes dormant
 *  (transfers run on the last-known address; a connectivity failure invalidates
 *  it, which flips `pairedResolved` back to false and wakes the browse). */
class BrowsePolicyTest {

    @Test fun unpairedAlwaysBrowses() {
        assertTrue(BrowsePolicy.shouldBrowse(paired = false, pairedResolved = false, uiVisible = false))
    }

    @Test fun pairedButUnresolvedBrowses() {
        assertTrue(BrowsePolicy.shouldBrowse(paired = true, pairedResolved = false, uiVisible = false))
    }

    @Test fun uiOpenBrowsesEvenWhenResolved() {
        assertTrue(BrowsePolicy.shouldBrowse(paired = true, pairedResolved = true, uiVisible = true))
    }

    @Test fun pairedResolvedAndUiClosedGoesDormant() {
        assertFalse(BrowsePolicy.shouldBrowse(paired = true, pairedResolved = true, uiVisible = false))
    }

    /** 27/11/2026: a browse running for 38+ min never reported the paired Mac through
     *  six of its re-announcements; a fresh one resolved it 30 ms after starting. */
    @Test fun lostPairedMacGetsAFreshBrowseWhenSomeoneIsWaiting() {
        assertTrue(BrowsePolicy.shouldRestart(paired = true, pairedResolved = false))
    }

    @Test fun resolvedOrUnpairedKeepsTheRunningBrowse() {
        assertFalse(BrowsePolicy.shouldRestart(paired = true, pairedResolved = true))
        // Unpaired, the browse feeds the pick list: restarting would only blank it.
        assertFalse(BrowsePolicy.shouldRestart(paired = false, pairedResolved = false))
    }
}
