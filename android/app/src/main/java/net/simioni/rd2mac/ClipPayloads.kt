package net.simioni.rd2mac

import android.content.ClipData
import android.content.Context

/**
 * Converts the first item of a [ClipData] into a wire payload (mime, bytes):
 * image URIs are read through the resolver with their real MIME, everything
 * else is coerced to UTF-8 text. Shared by the Quick Settings tile path
 * (ClipboardSendActivity) and the automatic clip-changed push in the service.
 */
object ClipPayloads {

    fun fromClip(context: Context, clip: ClipData?): Pair<String, ByteArray>? {
        val item = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return null

        val uri = item.uri
        val mime = uri?.let { context.contentResolver.getType(it) }
        if (uri != null && mime != null && mime.startsWith("image/")) {
            val bytes = try {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (e: Exception) {
                null
            }
            if (bytes != null) return mime to bytes
        }
        // item.text first: it's plain parcel data. coerceToText does resolver IPC
        // and, called from the background (clip-changed listener), was seen on
        // One UI 16 appending a binder stack-trace line to the real text.
        val text = (item.text ?: item.coerceToText(context)).toString()
        if (text.isEmpty()) return null
        return "text/plain; charset=utf-8" to text.toByteArray(Charsets.UTF_8)
    }
}
