package net.simioni.rd2mac

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts the foreground service after a reboot **and after the app is updated**,
 * so the Mac can reach the phone without the user opening the app first (keeps
 * "pull without touching the phone" true across restarts). Both BOOT_COMPLETED and
 * MY_PACKAGE_REPLACED are allowed FGS start reasons.
 *
 * The update case is not theoretical: installing 0.5 over 0.4 left the phone dead
 * on the network until the app was opened by hand (seen 12/08/2026) — the exact
 * failure the user asked us to remove, arriving through the update door.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                RD2MacService.start(context)
        }
    }
}
