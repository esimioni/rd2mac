package net.simioni.rd2mac

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import net.simioni.rd2mac.media.MediaReader
import net.simioni.rd2mac.net.OkHttpPoster
import net.simioni.rd2mac.net.RD2Crypto
import net.simioni.rd2mac.net.ResolvedPeer
import net.simioni.rd2mac.net.TransferOrchestrator

/**
 * Share target: the phone's only "RD2Mac" entry in the share sheet. Sends the
 * shared items straight to the Mac (no device picker) and reports the REAL
 * outcome — "enviado ✓" only after HTTP 200, otherwise an explicit failure.
 * Runs translucent (no full-screen UI), just toasts and finishes.
 */
class ShareReceiverActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RD2MacService.start(this) // ensure discovery is live even if app was never opened

        val uris = extractUris(intent)
        if (uris.isEmpty()) {
            toast(getString(R.string.share_nothing))
            finish()
            return
        }
        Thread { handleSend(uris) }.start()
    }

    private fun extractUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND ->
            listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE ->
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
        else -> emptyList()
    }

    private fun handleSend(uris: List<Uri>) {
        val peer = awaitPeer(PEER_TIMEOUT_MS)
        Log.i(TAG, "handleSend: uris=${uris.size} peer=$peer")
        if (peer == null) {
            finishWith(getString(
                if (RD2MacState.pairedPeer == null) R.string.share_open_choose else R.string.share_no_mac))
            return
        }
        val payloads = uris.mapNotNull { MediaReader.readPayload(contentResolver, it) }
        Log.i(TAG, "payloads read=${payloads.size}")
        if (payloads.isEmpty()) {
            finishWith(getString(R.string.share_read_fail))
            return
        }
        val poster = OkHttpPoster(peerName = deviceDisplayName(this),
                                  token = RD2MacState.pairedPeer?.token,
                                  keyBytes = RD2Crypto.keyBytes(RD2MacState.pairedPeer?.key))
        val outcome = TransferOrchestrator(post = poster::post).sendAll(peer, payloads)
        Log.i(TAG, "outcome sent=${outcome.sent} failed=${outcome.failed}")
        finishWith(
            if (outcome.allSent) getString(R.string.share_sent, outcome.sent)
            else getString(R.string.share_failed, outcome.failed, payloads.size)
        )
    }

    /** Wait briefly for discovery to resolve the Mac (phone may have just woken). */
    private fun awaitPeer(timeoutMs: Long): ResolvedPeer? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            RD2MacState.peer?.let { return it }
            Thread.sleep(150)
        }
        return RD2MacState.peer
    }

    private fun finishWith(message: String) = runOnUiThread {
        toast(message)
        finish()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val PEER_TIMEOUT_MS = 6000L
        private const val TAG = "RD2Mac.Share"
    }
}
