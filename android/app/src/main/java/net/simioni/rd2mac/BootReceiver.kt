package net.simioni.rd2mac

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Restarts the foreground service after a reboot **and after the app is updated**,
 * so the Mac can reach the phone without the user opening the app first (keeps
 * "pull without touching the phone" true across restarts). Both BOOT_COMPLETED and
 * MY_PACKAGE_REPLACED are allowed FGS start reasons.
 *
 * The update case is not theoretical: installing 0.5 over 0.4 left the phone dead
 * on the network until the app was opened by hand (seen 12/08/2026) — the exact
 * failure the user asked us to remove, arriving through the update door. And a
 * user-confirmed self-update can skip MY_PACKAGE_REPLACED altogether (2026-10-23),
 * so the [KeepAlive] alarm lands here too.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!RestartPolicy.shouldStart(intent.action, RD2MacState.running)) return
        // The keep-alive is not an exempt start reason: it lands only while the app
        // is exempt from battery optimisation ("keep running"), else Android refuses.
        runCatching { RD2MacService.start(context) }
            .onFailure { Log.w(TAG, "service not restarted (${intent.action}): $it") }
    }

    private companion object {
        const val TAG = "RD2Mac.Restart"
    }
}
