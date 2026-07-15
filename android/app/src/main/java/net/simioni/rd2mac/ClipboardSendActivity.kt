package net.simioni.rd2mac

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import net.simioni.rd2mac.net.ClipboardClient
import net.simioni.rd2mac.net.RD2Crypto

/**
 * Transparent, instantly-finishing activity that reads THIS phone's clipboard
 * (allowed only with window focus — which an Activity has, unlike a service)
 * and pushes it to the Mac. Launched from the Quick Settings tile — the ONE
 * Android→Mac path: AOSP grants clipboard reads only to the focused app / IME,
 * so every "automatic" background capture (a11y direct read, gesture-triggered
 * launches) either returns null or steals window focus. Proven dead ends —
 * don't re-attempt; the tile is the design.
 */
class ClipboardSendActivity : Activity() {

    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // no content view — stays invisible; acts once it gains focus
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled) return
        handled = true
        sendClipboard()
    }

    private fun sendClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val peer = RD2MacState.peer ?: return finishWith(getString(R.string.clip_no_mac))
        val payload = ClipPayloads.fromClip(this, cm.primaryClip)
            ?: return finishWith(getString(R.string.clip_empty))

        // Remember the manual send too, so the poll doesn't re-paste its echo.
        RD2MacState.clipGuard.shouldPush(payload.second)

        val token = RD2MacState.pairedPeer?.token
        val key = RD2Crypto.keyBytes(RD2MacState.pairedPeer?.key)
        Thread {
            val ok = ClipboardClient.push(peer, token, key, payload.first, payload.second)
            runOnUiThread { finishWith(getString(if (ok) R.string.clip_sent else R.string.clip_fail)) }
        }.start()
    }

    private fun finishWith(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        finish()
    }
}
