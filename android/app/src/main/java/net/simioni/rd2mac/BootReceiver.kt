package net.simioni.rd2mac

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts the foreground service after a reboot, so the Mac can reach the phone
 * without the user opening the app first (keeps "pull without touching the phone"
 * true across restarts). BOOT_COMPLETED is an allowed FGS start reason.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            RD2MacService.start(context)
        }
    }
}
