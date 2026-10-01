package net.simioni.rd2mac.update

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.app.NotificationCompat
import net.simioni.rd2mac.Notifications
import net.simioni.rd2mac.R

/**
 * The installer's verdict on a self-update. The only one that needs the user is
 * "pending user action" — the first update, before "Install unknown apps" was allowed
 * for RD2Mac, or an Android version that raised the bar: a notification hands them the
 * system's own prompt. Success needs nothing (the new version is already running) and
 * a failure waits for the next day's check.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) ?: return
                askToConfirm(context, confirm, intent.getStringExtra(EXTRA_VERSION).orEmpty())
            }
            PackageInstaller.STATUS_SUCCESS ->
                context.getSystemService(NotificationManager::class.java).cancel(Notifications.UPDATE_ID)
            else -> Log.w(AppUpdater.TAG, "update not installed: status=$status " +
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
    }

    private fun askToConfirm(context: Context, confirm: Intent, version: String) {
        Notifications.ensureChannels(context)
        val tap = PendingIntent.getActivity(context, 0, confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, Notifications.UPDATE_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_rd2)
            .setContentTitle(context.getString(R.string.update_ready_title))
            .setContentText(context.getString(R.string.update_ready_text, version))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(Notifications.UPDATE_ID, notification)
    }

    companion object {
        const val EXTRA_VERSION = "net.simioni.rd2mac.update.VERSION"
    }
}
