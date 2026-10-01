package net.simioni.rd2mac

/** Wire-protocol constants. Mirror of PROTOCOL.md and the Swift `RD2Mac` enum. */
object RD2Mac {
    const val SERVICE_TYPE = "_rd2mac._tcp"
    /** v2: sealed /receive response + sealed filename headers on paired transfers. */
    const val PROTOCOL_VERSION = 2
    const val DEFAULT_PORT = 53318
    /** BLE beacon the Mac advertises (proximity gate); mirror of the Swift constant. */
    const val BLE_SERVICE_UUID = "cd8a379d-ddd9-4f84-8a70-98aa480baee3"

    object Header {
        const val TOKEN = "X-RD2Mac-Token"
        const val FILENAME = "X-RD2Mac-Filename"
        const val PEER = "X-RD2Mac-Peer"
        const val CLIP_VERSION = "X-RD2Mac-Clip-Version"
    }

    object Path {
        const val PING = "/ping"
        const val PAIR = "/pair"
        const val RECEIVE = "/receive"
        const val CLIPBOARD = "/clipboard"
        const val PROXIMITY = "/proximity"
        const val SCREENSHOT_LATEST = "/screenshot/latest"
        const val MEDIA_LATEST = "/media/latest"
    }
}

/** The phone server's file transfers — what a self-update must not cut. Mirror of the
 *  Mac's `isMacTransfer`. */
fun isPhoneTransfer(method: String, path: String): Boolean =
    (method == "POST" && path == RD2Mac.Path.RECEIVE) ||
        (method == "GET" && (path == RD2Mac.Path.SCREENSHOT_LATEST || path == RD2Mac.Path.MEDIA_LATEST))

enum class Platform(val wire: String) {
    MACOS("macos"), ANDROID("android"), UNKNOWN("unknown");

    companion object {
        fun from(value: String?): Platform =
            entries.firstOrNull { it.wire == value } ?: UNKNOWN
    }
}

enum class SaveDestination { GALLERY, DOWNLOADS }

/**
 * Pure destination routing (no Android deps, unit-testable). Per PROTOCOL.md:
 * images/videos land in the gallery (MediaStore Pictures/DCIM), everything else
 * in Downloads.
 */
object SaveRouting {
    fun destinationFor(mime: String?): SaveDestination {
        val m = mime?.lowercase() ?: return SaveDestination.DOWNLOADS
        return if (m.startsWith("image/") || m.startsWith("video/"))
            SaveDestination.GALLERY
        else
            SaveDestination.DOWNLOADS
    }
}

/**
 * Filename sanitize — mirrors `FileNaming.sanitize` on the Mac side.
 * Strips any directory prefix (path-traversal defense) and null bytes, while
 * preserving normal characters like spaces. Empty / "." / ".." fall back.
 */
object FileNaming {
    fun sanitize(raw: String): String {
        val last = raw.trim().substringAfterLast('/').substringAfterLast('\\')
        val cleaned = last.filter { it.code != 0 }
        return if (cleaned.isEmpty() || cleaned == "." || cleaned == "..")
            "rd2mac-file"
        else
            cleaned
    }
}
