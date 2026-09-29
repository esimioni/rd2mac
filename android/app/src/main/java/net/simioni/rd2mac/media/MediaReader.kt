package net.simioni.rd2mac.media

import android.content.ContentResolver
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import net.simioni.rd2mac.net.FilePayload
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * A shared item, opened while the share's read grant is alive. The transfer runs after
 * the share screen is gone — and the grant with it — but a descriptor opened before
 * stays readable. Close it once the transfer is over.
 */
class SharedSource internal constructor(
    val name: String,
    val mime: String,
    internal val afd: AssetFileDescriptor,
) : Closeable {
    override fun close() = afd.close()
}

/** Resolves shared `content://` URIs into payloads that stream from the provider — the
 *  bytes are read while they go out, never held (a 147 MB video read whole killed the
 *  app, 29/09/2026). */
object MediaReader {

    /** Opens [uri] now; no byte is read yet. Null when the provider refuses it. */
    fun open(resolver: ContentResolver, uri: Uri): SharedSource? {
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val name = queryDisplayName(resolver, uri) ?: fallbackName(mime)
        val afd = try {
            resolver.openAssetFileDescriptor(uri, "r")
        } catch (e: Exception) {
            null
        } ?: return null
        return SharedSource(name, mime, afd)
    }

    /**
     * The payload that streams [source]: straight from its descriptor when it is a file
     * of known size — each attempt re-reads it from the start — else copied into
     * [spoolDir] first (a pipe has neither a size for the Content-Length nor a start to
     * go back to).
     */
    fun payload(source: SharedSource, spoolDir: File): FilePayload? {
        val afd = source.afd
        val length = afd.length                  // the file's own size when none was declared
        if (length >= 0 && seekTo(afd.parcelFileDescriptor, afd.startOffset)) {
            return FilePayload(source.name, source.mime, length) { reread(afd) }
        }
        val spooled = spool(afd, spoolDir) ?: return null
        return FilePayload(source.name, source.mime, spooled.length()) { spooled.inputStream() }
    }

    /** A fresh stream over the same open file, from its start — the grant may be gone. */
    private fun reread(afd: AssetFileDescriptor): InputStream {
        val dup = afd.parcelFileDescriptor.dup()
        if (!seekTo(dup, afd.startOffset)) {
            dup.close()
            throw IOException("shared file can no longer be rewound")
        }
        return ParcelFileDescriptor.AutoCloseInputStream(dup)
    }

    private fun seekTo(pfd: ParcelFileDescriptor, offset: Long): Boolean = try {
        Os.lseek(pfd.fileDescriptor, offset, OsConstants.SEEK_SET)
        true
    } catch (e: ErrnoException) {
        false
    }

    private fun spool(afd: AssetFileDescriptor, dir: File): File? {
        dir.mkdirs()
        val file = File.createTempFile("share-", ".part", dir)
        return try {
            afd.createInputStream().use { input -> file.outputStream().use { input.copyTo(it) } }
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
