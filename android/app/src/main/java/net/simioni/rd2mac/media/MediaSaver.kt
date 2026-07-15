package net.simioni.rd2mac.media

import android.content.ContentResolver
import android.content.ContentValues
import android.provider.MediaStore
import net.simioni.rd2mac.FileNaming
import net.simioni.rd2mac.SaveDestination
import net.simioni.rd2mac.SaveRouting

/**
 * Saves a file received from the Mac into MediaStore (scoped storage). Per
 * PROTOCOL.md: images/videos → gallery (Pictures/Movies, RD2Mac subfolder),
 * everything else → Downloads. Backs `POST /receive` on the phone.
 */
object MediaSaver {

    /** @return the saved content URI as a string, or null on failure. */
    fun save(resolver: ContentResolver, filename: String, mime: String, bytes: ByteArray): String? {
        val name = FileNaming.sanitize(filename)
        val (collection, relativePath) = when (SaveRouting.destinationFor(mime)) {
            SaveDestination.GALLERY ->
                if (mime.startsWith("video/"))
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "Movies/RD2Mac"
                else
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "Pictures/RD2Mac"
            SaveDestination.DOWNLOADS ->
                MediaStore.Downloads.EXTERNAL_CONTENT_URI to "Download/RD2Mac"
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)   // hide until fully written
        }

        val uri = resolver.insert(collection, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: run {
                resolver.delete(uri, null, null)
                return null
            }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            return null
        }

        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri.toString()
    }
}
