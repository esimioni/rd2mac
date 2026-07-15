package net.simioni.rd2mac

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

/**
 * Quick Settings tile "Send to Mac": sends this phone's clipboard to the paired
 * Mac in one tap. Reading the clipboard needs window focus, so the tile launches
 * the transparent [ClipboardSendActivity], which reads and pushes it.
 */
class ClipboardTileService : TileService() {
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, ClipboardSendActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
