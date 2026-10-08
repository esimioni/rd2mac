package net.simioni.rd2mac

import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import net.simioni.rd2mac.media.MediaReader
import net.simioni.rd2mac.media.SharedSource
import net.simioni.rd2mac.net.OkHttpPoster
import net.simioni.rd2mac.net.RD2Crypto
import net.simioni.rd2mac.net.ResolvedPeer
import net.simioni.rd2mac.net.TransferOrchestrator
import net.simioni.rd2mac.update.TransferActivity
import java.io.File
import java.util.concurrent.Executors

/**
 * Sends what was shared AFTER the share screen is gone. A 147 MB video took 7 s behind
 * the old see-through share screen, which read as a black, frozen phone (29/09/2026).
 * Batches go one at a time, in order, on the app process — the foreground
 * service keeps it alive, so leaving the screen or clearing recents does not stop them.
 * Progress shows in a notification; the outcome in a toast, and in the notification when
 * it failed. Still no phantom sends: "sent ✓" only after the sealed HTTP 200.
 */
object ShareTransfers {
    const val TAG = "RD2Mac.Share"
    private const val PEER_TIMEOUT_MS = 6_000L
    /** A bound, not a budget: released as soon as the batch ends. */
    private const val WAKE_LOCK_MAX_MS = 60 * 60 * 1000L

    private val executor = Executors.newSingleThreadExecutor()

    /** Takes ownership of [sources]: they are closed once their batch is over. */
    fun submit(context: Context, sources: List<SharedSource>) {
        val app = context.applicationContext
        executor.execute { run(app, sources) }
    }

    private fun run(context: Context, sources: List<SharedSource>) {
        val notifier = TransferNotifier(context)
        val spoolDir = File(context.cacheDir, "share-spool/${System.nanoTime()}")
        // The screen may go off mid-transfer: without this the CPU sleeps, the socket
        // stalls and the Mac drops the connection.
        val wakeLock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RD2Mac:share")
        wakeLock.acquire(WAKE_LOCK_MAX_MS)
        TransferActivity.shared.begin()   // a self-update waits for the batch
        val result = try {
            send(context, sources, spoolDir, notifier)
        } catch (e: Exception) {
            Log.e(TAG, "share failed", e)
            Result.Failed(context.getString(R.string.share_failed, sources.size, sources.size))
        } finally {
            TransferActivity.shared.end()
            sources.forEach { runCatching { it.close() } }
            spoolDir.deleteRecursively()
            if (wakeLock.isHeld) wakeLock.release()
        }
        when (result) {
            is Result.Sent -> notifier.clear()
            is Result.Failed -> notifier.failed(result.message)
        }
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun send(context: Context, sources: List<SharedSource>, spoolDir: File,
                     notifier: TransferNotifier): Result {
        notifier.progress(sources.first().name, 0, 1)
        val peer = awaitPeer(PEER_TIMEOUT_MS)
        Log.i(TAG, "send: files=${sources.size} peer=$peer")
        if (peer == null) return Result.Failed(context.getString(
            if (RD2MacState.pairedPeer == null) R.string.share_open_choose else R.string.share_no_mac))
        val payloads = sources.mapNotNull { MediaReader.payload(it, spoolDir) }
        if (payloads.isEmpty()) return Result.Failed(context.getString(R.string.share_read_fail))
        val poster = OkHttpPoster(peerName = deviceDisplayName(context),
                                  token = RD2MacState.pairedPeer?.token,
                                  keyBytes = RD2Crypto.keyBytes(RD2MacState.pairedPeer?.key))
        val outcome = TransferOrchestrator { p, payload, onBytes -> poster.post(p, payload, onBytes) }
            .sendAll(peer, payloads) { index, done, total ->
                val name = payloads[index].filename
                notifier.progress(if (payloads.size == 1) name else "$name (${index + 1}/${payloads.size})", done, total)
            }
        Log.i(TAG, "outcome sent=${outcome.sent} failed=${outcome.failed}")
        val failed = outcome.failed + (sources.size - payloads.size)   // unreadable ones count
        return if (failed == 0) Result.Sent(context.getString(R.string.share_sent, outcome.sent))
        else Result.Failed(context.getString(R.string.share_failed, failed, sources.size))
    }

    /** Wait briefly for discovery to resolve the Mac (the phone may have just woken). */
    private fun awaitPeer(timeoutMs: Long): ResolvedPeer? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            RD2MacState.peer?.let { return it }
            Thread.sleep(150)
        }
        return RD2MacState.peer
    }

    private sealed class Result(val message: String) {
        class Sent(message: String) : Result(message)
        class Failed(message: String) : Result(message)
    }
}

/** The transfer's notification: a progress bar while it runs, the failure if it fails. */
private class TransferNotifier(private val context: Context) {
    private val nm = context.getSystemService(NotificationManager::class.java)
    private var lastPercent = -1
    private var lastAt = 0L

    init { Notifications.ensureChannels(context) }

    /** Throttled: the system drops updates past a few per second. */
    fun progress(label: String, done: Long, total: Long) {
        val percent = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
        val now = SystemClock.elapsedRealtime()
        if (percent == lastPercent || (lastPercent >= 0 && now - lastAt < MIN_UPDATE_MS)) return
        lastPercent = percent
        lastAt = now
        nm.notify(Notifications.TRANSFER_ID, builder()
            .setContentTitle(context.getString(R.string.transfer_sending))
            .setContentText("$label · $percent%")
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build())
    }

    fun failed(message: String) {
        nm.notify(Notifications.TRANSFER_ID, builder()
            .setContentTitle(message)
            .setAutoCancel(true)
            .build())
    }

    fun clear() = nm.cancel(Notifications.TRANSFER_ID)

    private fun builder() = NotificationCompat.Builder(context, Notifications.TRANSFER_CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_rd2)
        .setPriority(NotificationCompat.PRIORITY_LOW)

    private companion object {
        const val MIN_UPDATE_MS = 300L
    }
}
