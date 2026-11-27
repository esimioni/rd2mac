package net.simioni.rd2mac

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/** Every notification channel and id the app uses, in one place: a channel's importance
 *  locks at its first creation, and two owners of one id overwrite each other. */
object Notifications {
    const val SERVICE_CHANNEL = "rd2mac_service"
    const val PAIRING_CHANNEL = "rd2mac_pairing"
    const val TRANSFER_CHANNEL = "rd2mac_transfers"
    const val UPDATE_CHANNEL = "rd2mac_updates"
    const val FAILED_CHANNEL = "rd2mac_send_failed"

    const val SERVICE_ID = 1
    const val PAIRING_ID = 2
    const val TRANSFER_ID = 3
    const val UPDATE_ID = 4

    /** One notice per failed batch (each with its own "Try again"), clear of the ids above. */
    fun failedShareId(batch: String): Int = 1_000 + Math.floorMod(batch.hashCode(), 100_000)

    /** Idempotent — whoever posts first creates them. */
    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(
            SERVICE_CHANNEL, context.getString(R.string.app_name), NotificationManager.IMPORTANCE_MIN
        ).apply { description = context.getString(R.string.channel_desc) })
        // A pending pairing needs the user's eyes NOW (the Mac is waiting on the code).
        nm.createNotificationChannel(NotificationChannel(
            PAIRING_CHANNEL, context.getString(R.string.channel_pair), NotificationManager.IMPORTANCE_HIGH))
        // A progress bar in the shade, silent: the user just started this transfer.
        nm.createNotificationChannel(NotificationChannel(
            TRANSFER_CHANNEL, context.getString(R.string.channel_transfers), NotificationManager.IMPORTANCE_LOW))
        // Only when an update needs the user's tap (the first one, before "Install unknown
        // apps" is allowed); a silent update posts nothing.
        nm.createNotificationChannel(NotificationChannel(
            UPDATE_CHANNEL, context.getString(R.string.channel_updates), NotificationManager.IMPORTANCE_DEFAULT))
        // A send that failed after its screen was gone: it pops up, or nobody sees it
        // (27/11/2026 — a toast 6 s late and a silent line; the screenshot was lost).
        nm.createNotificationChannel(NotificationChannel(
            FAILED_CHANNEL, context.getString(R.string.channel_failed), NotificationManager.IMPORTANCE_HIGH))
    }
}
