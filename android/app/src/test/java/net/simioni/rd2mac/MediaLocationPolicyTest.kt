package net.simioni.rd2mac

import net.simioni.rd2mac.media.MediaLocationPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaLocationPolicyTest {

    @Test
    fun `on and granted keeps the location`() {
        assertTrue(MediaLocationPolicy.effective(switchOn = true, permissionGranted = true))
    }

    @Test
    fun `switch left on after the permission was revoked in Settings is off`() {
        // The revoke never reaches the app: without this the UI would claim photos
        // carry coordinates while MediaProvider strips every one of them.
        assertFalse(MediaLocationPolicy.effective(switchOn = true, permissionGranted = false))
    }

    @Test
    fun `permission granted but switch off stays off`() {
        assertFalse(MediaLocationPolicy.effective(switchOn = false, permissionGranted = true))
    }
}
