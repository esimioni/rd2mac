package net.simioni.rd2mac

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile "Receive from Mac": pauses/resumes the automatic clipboard
 * paste in one tap. The Android 13+ system toast on every paste can't be
 * suppressed by apps, so pausing the paste is the way to keep the phone quiet
 * during a Mac-only copy session.
 */
class ClipboardReceiveTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        val on = !RD2MacState.receiveClipboard
        RD2MacService.setClipboardReceive(this, on)
        RD2MacState.receiveClipboard = on   // optimistic; the service persists it
        render()
    }

    private fun render() {
        qsTile?.apply {
            state = if (RD2MacState.receiveClipboard) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = getString(R.string.tile_receive_from_mac)
            updateTile()
        }
    }
}
