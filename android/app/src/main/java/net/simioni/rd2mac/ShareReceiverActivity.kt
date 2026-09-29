package net.simioni.rd2mac

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import net.simioni.rd2mac.media.MediaReader
import net.simioni.rd2mac.media.SharedSource

/**
 * Share target: the phone's only "RD2Mac" entry in the share sheet. It only OPENS the
 * shared items — while the share's read grant is alive — hands them to [ShareTransfers]
 * and finishes at once, so the user is back in the app they shared from. The transfer,
 * its progress and its real outcome ("sent ✓" only after HTTP 200) live there.
 */
class ShareReceiverActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RD2MacService.start(this) // ensure discovery is live even if app was never opened

        val uris = extractUris(intent)
        when {
            uris.isEmpty() -> finishWith(getString(R.string.share_nothing))
            RD2MacState.pairedPeer == null -> finishWith(getString(R.string.share_open_choose))
            // Opening is provider IPC (a cloud item may even download first): off the UI thread.
            else -> Thread { open(uris) }.start()
        }
    }

    private fun extractUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND ->
            listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE ->
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
        else -> emptyList()
    }

    /** Runs on a bare thread: an exception escaping it would kill the whole process —
     *  the service with it — so every failure ends in a toast instead. */
    private fun open(uris: List<Uri>) {
        val sources = mutableListOf<SharedSource>()
        val message = try {
            uris.mapNotNullTo(sources) { MediaReader.open(contentResolver, it) }
            if (sources.isEmpty()) getString(R.string.share_read_fail)
            else {
                ShareTransfers.submit(this, sources.toList())
                getString(R.string.share_sending, sources.size)
            }
        } catch (e: Exception) {
            Log.e(ShareTransfers.TAG, "share failed", e)
            sources.forEach { runCatching { it.close() } }
            getString(R.string.share_failed, uris.size, uris.size)
        }
        runOnUiThread { finishWith(message) }
    }

    private fun finishWith(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }
}
