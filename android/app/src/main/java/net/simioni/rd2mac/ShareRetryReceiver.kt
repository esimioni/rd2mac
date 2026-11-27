package net.simioni.rd2mac

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** The failed-share notice's two ways out: "Try again" sends the kept files, dismissing
 *  the notice discards them ([net.simioni.rd2mac.media.PendingShares]). */
class ShareRetryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val batch = intent.getStringExtra(EXTRA_BATCH) ?: return
        when (intent.action) {
            ACTION_RETRY -> {
                // An action button doesn't close its notice — and closing it from code is
                // not a dismissal, so the files stay for the retry.
                context.getSystemService(NotificationManager::class.java)
                    .cancel(Notifications.failedShareId(batch))
                // Finding the Mac takes the service's discovery; the tap allows starting it.
                runCatching { RD2MacService.start(context) }
                ShareTransfers.retry(context, batch)
            }
            ACTION_DISCARD -> ShareTransfers.discard(context, batch)
        }
    }

    companion object {
        const val ACTION_RETRY = "net.simioni.rd2mac.RETRY_SHARE"
        const val ACTION_DISCARD = "net.simioni.rd2mac.DISCARD_SHARE"
        private const val EXTRA_BATCH = "batch"

        /** One per batch and action: the request code is the batch's notice id, and the
         *  action tells "Try again" from the dismissal. */
        fun intent(context: Context, action: String, batch: String): PendingIntent =
            PendingIntent.getBroadcast(context, Notifications.failedShareId(batch),
                Intent(context, ShareRetryReceiver::class.java).setAction(action).putExtra(EXTRA_BATCH, batch),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}