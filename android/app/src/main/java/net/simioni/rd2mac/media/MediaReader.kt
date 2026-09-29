package net.simioni.rd2mac.media

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import net.simioni.rd2mac.net.FilePayload
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

/** Resolves shared `content://` URIs into payloads that stream from the provider — the
 *  bytes are read while they go out, never held (a 147 MB video read whole killed the
 *  app, 29/09/2026). */
object MediaReader {

    /** @param spoolDir where a stream of unknown size is copied first: the wire needs a
     *  Content-Length, and only a provider handing out a pipe has none. */
    fun readPayload(resolver: ContentResolver, uri: Uri, spoolDir: File): FilePayload? {
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val name = queryDisplayName(resolver, uri) ?: fallbackName(mime)
        knownLength(resolver, uri)?.let { length ->
            return FilePayload(name, mime, length) { open(resolver, uri) }
        }
        val spooled = spool(resolver, uri, spoolDir) ?: return null
        return FilePayload(name, mime, spooled.length()) { spooled.inputStream() }
    }

    private fun open(resolver: ContentResolver, uri: Uri): InputStream =
        resolver.openInputStream(uri) ?: throw FileNotFoundException("no stream for $uri")

    /** The content's size as the provider reports it (the file's, for MediaStore). */
    private fun knownLength(resolver: ContentResolver, uri: Uri): Long? = try {
        resolver.openAssetFileDescriptor(uri, "r")?.use { afd -> afd.length.takeIf { it >= 0 } }
    } catch (e: Exception) {
        null
    }

    private fun spool(resolver: ContentResolver, uri: Uri, dir: File): File? {
        dir.mkdirs()
        val file = File.createTempFile("share-", ".part", dir)
        return try {
            open(resolver, uri).use { input -> file.outputStream().use { input.copyTo(it) } }
            file
        } catch (e: Exception) {
            file.delete()
            null
        }
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
