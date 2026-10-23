package net.simioni.rd2mac

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * A standing alarm that brings the service back when its process is gone. The
 * door it covers: after a user-confirmed self-update (0.7 → 0.8 on the S23,
 * 2026-10-23) Android never delivered MY_PACKAGE_REPLACED, so nothing restarted
 * the app and the phone stayed off the network until it was opened by hand — while
 * an install of the same APK through `pm` came back in 1.5 s through that same
 * broadcast. Alarms outlive an update (the alarm service keeps a package's alarms on
 * PACKAGE_REMOVED with EXTRA_REPLACING; only a user's force-stop drops them), so the
 * alarm the old version armed starts the new one.
 *
 * Non-wakeup and inexact: it rides wakeups that happen anyway. While the service
 * runs, the receiver sees it and returns ([RestartPolicy]).
 */
object KeepAlive {
    const val ACTION = "net.simioni.rd2mac.action.KEEP_ALIVE"
    const val INTERVAL_MS = 15 * 60 * 1000L

    /** Idempotent: re-arming replaces the alarm (same PendingIntent). */
    fun arm(context: Context) {
        val alarm = PendingIntent.getBroadcast(context, 0,
            Intent(context, BootReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        context.getSystemService(AlarmManager::class.java).setInexactRepeating(
            AlarmManager.RTC, System.currentTimeMillis() + INTERVAL_MS, INTERVAL_MS, alarm)
    }
}

/** Which broadcasts (re)start the service. Pure, unit-tested. */
object RestartPolicy {
    fun shouldStart(action: String?, running: Boolean): Boolean = when (action) {
        Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> true
        KeepAlive.ACTION -> !running
        else -> false
    }
}