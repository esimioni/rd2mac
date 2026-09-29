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

    const val SERVICE_ID = 1
    const val PAIRING_ID = 2
    const val TRANSFER_ID = 3

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
    }
}
