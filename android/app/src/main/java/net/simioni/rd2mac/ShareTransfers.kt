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
import net.simioni.rd2mac.media.PendingShares
import net.simioni.rd2mac.media.SharedSource
import net.simioni.rd2mac.net.FilePayload
import net.simioni.rd2mac.net.OkHttpPoster
import net.simioni.rd2mac.net.RD2Crypto
import net.simioni.rd2mac.net.TransferOrchestrator
import net.simioni.rd2mac.update.TransferActivity
import java.io.File
import java.util.concurrent.Executors

/**
 * Sends what was shared AFTER the share screen is gone. A 147 MB video took 7 s behind
 * the old see-through share screen, which read as a black, frozen phone (29/09/2026).
 * Batches go one at a time, in order, on the app process — the foreground
 * service keeps it alive, so leaving the screen or clearing recents does not stop them.
 * Progress shows in a notification, the outcome in a toast. A failure keeps the files
 * that did not arrive ([PendingShares]) behind a notice that pops up with "Try again":
 * by then the source may be gone (One UI deletes a shared screenshot at once).
 * Still no phantom sends: "sent ✓" only after the sealed HTTP 200.
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
        executor.execute {
            runBatch(app, sources.size, keptOnCrash = null) { notifier -> shareNew(app, sources, notifier) }
        }
    }

    /** "Try again" on a kept batch — gone already (sent, dismissed) means nothing to do. */
    fun retry(context: Context, batchId: String) {
        val app = context.applicationContext
        executor.execute {
            val store = pending(app)
            val items = store.load(batchId)
            if (items == null) {
                Log.i(TAG, "retry: batch $batchId is gone (sent or dismissed)")
                return@execute
            }
            runBatch(app, items.size, keptOnCrash = batchId) { notifier ->
                notifier.progress(items.first().name, 0, 1)
                val payloads = items.map { it.payload() }
                val delivery = deliver(app, payloads, notifier)
                store.settle(batchId, delivery.failed)
                if (delivery.failed.isEmpty()) sent(app, payloads.size)
                else Result.Failed(delivery.failureMessage(app, payloads.size), kept = batchId)
            }
        }
    }

    /** Its notice was dismissed: the kept files go. Queued behind any retry of them. */
    fun discard(context: Context, batchId: String) {
        val app = context.applicationContext
        executor.execute { pending(app).discard(batchId) }
    }

    private fun pending(context: Context) = PendingShares(File(context.filesDir, "pending-shares"))

    private fun shareNew(context: Context, sources: List<SharedSource>, notifier: TransferNotifier): Result {
        val spoolDir = File(context.cacheDir, "share-spool/${System.nanoTime()}")
        try {
            pending(context).sweep()
            notifier.progress(sources.first().name, 0, 1)
            val payloads = sources.mapNotNull { MediaReader.payload(it, spoolDir) }
            if (payloads.isEmpty()) return Result.Failed(context.getString(R.string.share_read_fail))
            val delivery = deliver(context, payloads, notifier)
            val unreadable = sources.size - payloads.size
            if (delivery.failed.isEmpty() && unreadable == 0) return sent(context, payloads.size)
            // Copied while the shared files are still open: they close right after.
            val kept = pending(context).keep(delivery.failed.map { payloads[it] })
            return Result.Failed(delivery.failureMessage(context, sources.size, unreadable), kept)
        } finally {
            sources.forEach { runCatching { it.close() } }
            spoolDir.deleteRecursively()
        }
    }

    /** [failed] = positions that did not arrive; [noMac] = why none could, when the Mac
     *  was never found. */
    private class Delivery(val failed: List<Int>, val noMac: String?) {
        /** [unreadable] = files that never became payloads; they failed too. */
        fun failureMessage(context: Context, total: Int, unreadable: Int = 0): String =
            noMac ?: context.getString(R.string.share_failed, failed.size + unreadable, total)
    }

    private fun deliver(context: Context, payloads: List<FilePayload>, notifier: TransferNotifier): Delivery {
        val peer = RD2MacState.awaitPeer(PEER_TIMEOUT_MS)
        Log.i(TAG, "send: files=${payloads.size} peer=$peer")
        if (peer == null) return Delivery(payloads.indices.toList(), context.getString(
            if (RD2MacState.pairedPeer == null) R.string.share_open_choose else R.string.share_no_mac))
        val poster = OkHttpPoster(peerName = deviceDisplayName(context),
                                  token = RD2MacState.pairedPeer?.token,
                                  keyBytes = RD2Crypto.keyBytes(RD2MacState.pairedPeer?.key))
        val outcome = TransferOrchestrator { p, payload, onBytes -> poster.post(p, payload, onBytes) }
            .sendAll(peer, payloads) { index, done, total ->
                val name = payloads[index].filename
                notifier.progress(if (payloads.size == 1) name else "$name (${index + 1}/${payloads.size})", done, total)
            }
        Log.i(TAG, "outcome sent=${outcome.sent} failed=${outcome.failed}")
        return Delivery(outcome.failedIndexes, noMac = null)
    }

    private fun sent(context: Context, count: Int) = Result.Sent(context.getString(R.string.share_sent, count))

    /** One batch, new or retried: kept awake, announced to a self-update, and ended by a
     *  toast plus the notification it calls for. */
    private fun runBatch(context: Context, count: Int, keptOnCrash: String?,
                         work: (TransferNotifier) -> Result) {
        val notifier = TransferNotifier(context)
        // The screen may go off mid-transfer: without this the CPU sleeps, the socket
        // stalls and the Mac drops the connection.
        val wakeLock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RD2Mac:share")
        wakeLock.acquire(WAKE_LOCK_MAX_MS)
        TransferActivity.shared.begin()   // a self-update waits for the batch
        val result = try {
            work(notifier)
        } catch (e: Exception) {
            Log.e(TAG, "share failed", e)
            Result.Failed(context.getString(R.string.share_failed, count, count), keptOnCrash)
        } finally {
            TransferActivity.shared.end()
            if (wakeLock.isHeld) wakeLock.release()
        }
        when (result) {
            is Result.Sent -> notifier.clear()
            is Result.Failed -> notifier.failed(result.message, result.kept)
        }
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
        }
    }

    private sealed class Result(val message: String) {
        class Sent(message: String) : Result(message)
        /** [kept] = the batch "Try again" sends; null when nothing could be kept. */
        class Failed(message: String, val kept: String? = null) : Result(message)
    }
}

/** The transfer's notifications: a progress bar while it runs, the failure if it fails. */
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

    /** With files [kept], a notice that pops up, one per batch: "Try again" sends them,
     *  dismissing it lets them go. No tap-to-retry on the body: an auto-cancelled tap must
     *  never be mistaken for a dismissal. Without, the quiet line it always was. */
    fun failed(message: String, kept: String?) {
        if (kept == null) {
            nm.notify(Notifications.TRANSFER_ID, builder()
                .setContentTitle(message)
                .setAutoCancel(true)
                .build())
            return
        }
        nm.cancel(Notifications.TRANSFER_ID)   // the progress bar: the notice has its own id
        nm.notify(Notifications.failedShareId(kept),
            NotificationCompat.Builder(context, Notifications.FAILED_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_rd2)
                .setContentTitle(message)
                .setContentText(context.getString(R.string.share_kept))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .addAction(0, context.getString(R.string.share_retry),
                    ShareRetryReceiver.intent(context, ShareRetryReceiver.ACTION_RETRY, kept))
                .setDeleteIntent(ShareRetryReceiver.intent(context, ShareRetryReceiver.ACTION_DISCARD, kept))
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