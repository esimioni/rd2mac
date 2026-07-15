package net.simioni.rd2mac.media

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import net.simioni.rd2mac.net.FilePayload

/** Resolves shared `content://` URIs into in-memory payloads to POST to the Mac. */
object MediaReader {

    fun readPayload(resolver: ContentResolver, uri: Uri): FilePayload? {
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val name = queryDisplayName(resolver, uri) ?: fallbackName(mime)
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        return FilePayload(name, mime, bytes)
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return c.getString(idx)
            }
        }
        return null
    }

    private fun fallbackName(mime: String): String {
        val ext = when {
            mime.startsWith("image/") -> mime.substringAfter('/').ifEmpty { "jpg" }
            mime.startsWith("video/") -> mime.substringAfter('/').ifEmpty { "mp4" }
            else -> "bin"
        }
        return "rd2mac-${System.currentTimeMillis()}.$ext"
    }
}
