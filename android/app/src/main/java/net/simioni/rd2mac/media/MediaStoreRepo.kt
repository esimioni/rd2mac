package net.simioni.rd2mac.media

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore

class ServedFile(
    val filename: String,
    val mime: String,
    val bytes: ByteArray,
)

/**
 * Reads the newest screenshot / gallery item from MediaStore. Backs
 * `GET /screenshot/latest` and `GET /media/latest` — the phone side of the Mac's
 * pull. Requires READ_MEDIA_IMAGES / READ_MEDIA_VIDEO (granted in MainActivity).
 */
object MediaStoreRepo {

    /**
     * The `skip`-th most recent screenshot (skip=0 is the newest); falls back to the
     * `skip`-th most recent image when no Screenshots folder exists. `skip` powers
     * the Mac's multi-item pull (it asks skip=0,1,2,… for the N newest).
     */
    fun latestScreenshot(resolver: ContentResolver, skip: Int = 0): ServedFile? {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("%Screenshots%")
        return newest(resolver, collection, mediaProjection, selection, args, skip)
            ?: latestMedia(resolver, "image", skip)
    }

    /** The `skip`-th most recent item of the given type ("image" default, or "video"). */
    fun latestMedia(resolver: ContentResolver, type: String, skip: Int = 0): ServedFile? {
        val collection = if (type == "video")
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        return newest(resolver, collection, mediaProjection, null, null, skip)
    }

    // Generic MediaColumns — valid for both the Images and Video collections.
    private val mediaProjection = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.MIME_TYPE,
    )

    private fun newest(
        resolver: ContentResolver,
        collection: Uri,
        projection: Array<String>,
        selection: String?,
        args: Array<String>?,
        skip: Int = 0,
    ): ServedFile? {
        val sort = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        resolver.query(collection, projection, selection, args, sort)?.use { c ->
            // moveToPosition(skip) selects the (skip)-th newest; false when the phone
            // has fewer items than requested → the Mac's pull loop stops there.
            if (!c.moveToPosition(skip)) return null
            val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
            val name = c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
                ?: "media-$id"
            val mime = c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE))
                ?: "application/octet-stream"
            val uri = ContentUris.withAppendedId(collection, id)
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            return ServedFile(name, mime, bytes)
        }
        return null
    }
}
