package net.simioni.rd2mac

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Persists a clipboard image received from the Mac into app cache and exposes it
 * as a content:// URI via FileProvider, so it can be put on the clipboard and
 * pasted into other apps. One fixed filename (the clipboard holds one image).
 */
object ClipboardImageStore {
    fun uriFor(context: Context, bytes: ByteArray): Uri? = try {
        val dir = File(context.cacheDir, "clipboard").apply { mkdirs() }
        val file = File(dir, "clip.png")
        file.writeBytes(bytes)
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    } catch (e: Exception) {
        null
    }
}
