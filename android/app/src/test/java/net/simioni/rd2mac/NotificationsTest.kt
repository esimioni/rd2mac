package net.simioni.rd2mac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each failed batch gets its own notice — and its own "Try again" — so two failures
 *  never overwrite each other or one of the app's fixed notices. */
class NotificationsTest {

    private val fixed = setOf(Notifications.SERVICE_ID, Notifications.PAIRING_ID,
                              Notifications.TRANSFER_ID, Notifications.UPDATE_ID)

    @Test fun aBatchKeepsItsNoticeId() {
        assertEquals(Notifications.failedShareId("1795790750710-0"), Notifications.failedShareId("1795790750710-0"))
    }

    @Test fun noticeIdsStayClearOfTheFixedOnes() {
        val ids = (0 until 500).map { Notifications.failedShareId("1795790750${it}-0") }
        assertTrue(ids.none { it in fixed })
        // A hash that lands negative still maps into the range.
        assertTrue(Notifications.failedShareId("polygenelubricants") >= 1_000)
    }
}