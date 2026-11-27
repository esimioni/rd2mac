package net.simioni.rd2mac.media

import net.simioni.rd2mac.net.FilePayload
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A failed share's files, kept on the phone for "Try again". The share screen is gone
 * long before the send fails, and its source may be too: One UI's "Delete shared
 * screenshots" deletes a screenshot as soon as it is shared, so a failed send lost it
 * for good (27/11/2026). A batch stays until it arrives, the user dismisses the notice,
 * or [sweep] finds it older than [MAX_AGE_MS].
 *
 * One directory per batch under [root]: the files are stored as `0`, `1`, … and their
 * names live only in the manifest — a name comes from another app and never becomes a
 * path. [now] is the clock the batch ids (and so their age) are read from.
 */
class PendingShares(private val root: File, private val now: () -> Long = System::currentTimeMillis) {

    data class Item(val name: String, val mime: String, val file: File) {
        /** Streams the kept bytes, re-readable for every attempt. */
        fun payload(): FilePayload = FilePayload.of(file, name, mime)
    }

    /** Keeps a copy of each of [payloads] as a new batch and returns its id; one that
     *  can't be read is left out. Null when nothing could be kept — then nothing is left
     *  behind either. */
    fun keep(payloads: List<FilePayload>): String? {
        if (payloads.isEmpty()) return null
        val (id, dir) = newBatchDir()
        val items = JSONArray()
        payloads.forEach { payload ->
            val stored = items.length().toString()
            val ok = runCatching {
                payload.open().use { input -> File(dir, stored).outputStream().use { input.copyTo(it) } }
            }.isSuccess
            if (ok) items.put(JSONObject().put(NAME, payload.filename).put(MIME, payload.mime).put(FILE, stored))
            else File(dir, stored).delete()
        }
        if (items.length() == 0) {
            dir.deleteRecursively()
            return null
        }
        writeManifest(dir, items)
        return id
    }

    /** The batch's files, in order; null when it is gone (arrived, dismissed, expired). */
    fun load(id: String): List<Item>? {
        val dir = batchDir(id) ?: return null
        val items = readManifest(dir) ?: return null
        return (0 until items.length()).map { i ->
            val o = items.getJSONObject(i)
            Item(o.getString(NAME), o.getString(MIME), File(dir, o.getString(FILE)))
        }
    }

    /** After an attempt on the batch as [load] returned it: the files at [failedIndexes]
     *  stay, the rest arrived and go. A batch left empty is removed. */
    fun settle(id: String, failedIndexes: List<Int>) {
        val dir = batchDir(id) ?: return
        val items = readManifest(dir) ?: return discard(id)
        val keep = failedIndexes.toSet()
        val left = JSONArray()
        for (i in 0 until items.length()) {
            val o = items.getJSONObject(i)
            if (i in keep) left.put(o) else File(dir, o.getString(FILE)).delete()
        }
        if (left.length() == 0) discard(id) else writeManifest(dir, left)
    }

    fun discard(id: String) {
        batchDir(id)?.deleteRecursively()
    }

    /** Drops batches older than [maxAgeMs] — the net under a notice that vanished
     *  without telling us (process killed, notifications off). */
    fun sweep(maxAgeMs: Long = MAX_AGE_MS) {
        val cutoff = now() - maxAgeMs
        root.listFiles()?.forEach { dir ->
            val born = dir.name.substringBefore('-').toLongOrNull()
            if (born == null || born < cutoff) dir.deleteRecursively()
        }
    }

    /** `<millis>-<n>`: the time is the batch's age, `n` tells apart two in one millisecond. */
    private fun newBatchDir(): Pair<String, File> {
        root.mkdirs()
        val born = now()
        var n = 0
        while (true) {
            val id = "$born-$n"
            val dir = File(root, id)
            if (dir.mkdir()) return id to dir
            n++
        }
    }

    /** Only ids this store made — anything else (`..`, a path) is no batch. */
    private fun batchDir(id: String): File? {
        if (!ID.matches(id)) return null
        return File(root, id).takeIf { it.isDirectory }
    }

    private fun writeManifest(dir: File, items: JSONArray) =
        File(dir, MANIFEST).writeText(JSONObject().put(ITEMS, items).toString())

    private fun readManifest(dir: File): JSONArray? =
        runCatching { JSONObject(File(dir, MANIFEST).readText()).getJSONArray(ITEMS) }.getOrNull()

    companion object {
        const val MAX_AGE_MS = 7 * 24 * 3600_000L
        private val ID = Regex("""\d+-\d+""")
        private const val MANIFEST = "batch.json"
        private const val ITEMS = "items"
        private const val NAME = "name"
        private const val MIME = "mime"
        private const val FILE = "file"
    }
}