package net.simioni.rd2mac.update

import org.json.JSONObject

/** The newest public release, as the GitHub API describes it — mirror of the Mac's
 *  `Release` (Updates.swift): both apps update from the same feed. */
data class Release(
    /** Dotted version, the tag without its leading "v" ("v0.6" → "0.6"). */
    val version: String,
    val assetUrl: String,
    val size: Long,
    /** Lowercase hex SHA-256 of the asset, from GitHub's "sha256:<hex>" digest. */
    val sha256: String?,
)

object UpdateFeed {
    /** The asset the phone installs from. */
    const val ANDROID_ASSET = "RD2Mac.apk"

    /** The release and its asset named [asset]; null when the JSON lacks either, or the
     *  release is a draft or a pre-release. */
    fun parse(json: String, asset: String): Release? = try {
        val root = JSONObject(json)
        val assets = root.getJSONArray("assets")
        val file = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name") == asset }
        if (file == null || root.optBoolean("draft") || root.optBoolean("prerelease")) null
        else Release(
            version = root.getString("tag_name").removePrefix("v"),
            assetUrl = file.getString("browser_download_url"),
            size = file.getLong("size"),
            sha256 = sha256Hex(if (file.isNull("digest")) null else file.optString("digest")),
        )
    } catch (e: Exception) {
        null
    }

    /** "sha256:<64 hex>" → the hex, lowercased; anything else → null. */
    internal fun sha256Hex(digest: String?): String? {
        val hex = digest?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase()
        return hex?.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }
}

object AppVersion {
    /** True when [candidate] is a strictly higher dotted version than [current]
     *  ("0.10" > "0.9", "1.0" > "0.9.9", "0.6" == "0.6.0"). Anything that is not dotted
     *  numbers is never newer: an unreadable tag must not trigger an install. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = numbers(candidate) ?: return false
        val b = numbers(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** ASCII digits only, part by part (as on the Mac): no sign, no other script's digits. */
    private fun numbers(version: String): List<Int>? {
        val parts = version.split(".")
        val values = parts.mapNotNull { p -> p.toIntOrNull()?.takeIf { p.all { it in '0'..'9' } } }
        return values.takeIf { it.size == parts.size }
    }
}

object UpdatePolicy {
    /** Download only a version newer than the installed one that this device has not
     *  already turned down. A release whose asset did not change — the APK reused by a
     *  Mac-only release — would otherwise be fetched and refused every single day. */
    fun wants(version: String, installed: String, rejected: String?): Boolean =
        version != rejected && AppVersion.isNewer(version, installed)
}

object UpdateSchedule {
    /** One check a day. */
    const val INTERVAL_MS = 24 * 60 * 60 * 1000L

    /** Due when never checked, a day after the last check, or when the clock went back
     *  behind the last check (otherwise a wrong clock could silence updates for good). */
    fun isDue(lastCheck: Long?, now: Long): Boolean {
        if (lastCheck == null) return true
        val elapsed = now - lastCheck
        return elapsed >= INTERVAL_MS || elapsed < 0
    }
}
