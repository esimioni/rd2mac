package net.simioni.rd2mac.net

import net.simioni.rd2mac.RD2Mac
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** One clipboard payload pulled from / pushed to the Mac. */
class ClipboardData(val version: Int, val contentType: String, val bytes: ByteArray)

/**
 * Kills the sync loop that bidirectional clipboard would otherwise create:
 * Mac copy → phone pastes → phone's clip-changed listener fires → push to Mac →
 * Mac changeCount bumps → phone polls it back → paste → … forever. Both
 * directions are cut by fingerprinting the last content that crossed each way.
 * Pure Kotlin (SHA-256 only) — unit-tested without Android.
 */
class ClipboardLoopGuard {
    private var lastInbound: String? = null   // last content pasted here FROM the Mac
    private var lastOutbound: String? = null  // last content pushed from here TO the Mac

    /** Mac → phone: call before pasting. False ⇒ it's the echo of our own push. */
    fun shouldPaste(bytes: ByteArray): Boolean {
        val fp = fingerprint(bytes)
        if (fp == lastOutbound) return false
        lastInbound = fp
        return true
    }

    /** Phone → Mac: call when the local clipboard changes. False ⇒ it's the Mac's
     *  own content that we just pasted locally (or an unchanged re-copy). */
    fun shouldPush(bytes: ByteArray): Boolean {
        val fp = fingerprint(bytes)
        if (fp == lastInbound || fp == lastOutbound) return false
        lastOutbound = fp
        return true
    }

    private fun fingerprint(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}

/**
 * Pure decision for the receive-poll: given the last seen version and an incoming
 * one, what to store and whether to paste. The first response after (re)starting
 * polling only primes the baseline — pasting it would drop whatever the Mac
 * copied hours before the switch was turned on into the phone's clipboard.
 */
object ClipboardGate {
    const val UNPRIMED = -1

    /** @return (new last version, paste?) */
    fun advance(last: Int, incoming: Int): Pair<Int, Boolean> = when {
        incoming == last -> last to false        // unchanged
        last == UNPRIMED -> incoming to false    // baseline: don't paste pre-switch content
        else -> incoming to true
    }
}

/**
 * Clipboard client to the Mac. PULL (`GET /clipboard?since=`) receives what the
 * Mac copied; PUSH (`POST /clipboard`) sends this phone's clipboard. Both carry
 * the paired token. Reading the phone's OWN clipboard must happen in a focused
 * Activity (Android 10+ restriction), so PUSH is called from the tile activity.
 */
object ClipboardClient {

    /** GET the Mac clipboard if it changed since `since`. Payload on 200, null on
     *  204/failure; a connectivity failure additionally fires [onError]. */
    fun pull(peer: ResolvedPeer, token: String?, keyBytes: ByteArray?, since: Int,
             onError: () -> Unit = {}): ClipboardData? {
        val url = "http://${bracketed(peer)}:${peer.port}${RD2Mac.Path.CLIPBOARD}?since=$since"
        val builder = Request.Builder().url(url).get()
        if (!token.isNullOrEmpty()) builder.header(RD2Mac.Header.TOKEN, token)
        return RD2Http.execute(builder.build(), onError) { resp ->
            if (resp.code != 200) return@execute null
            val version = resp.header(RD2Mac.Header.CLIP_VERSION)?.toIntOrNull()
                ?: return@execute null
            val ct = resp.header("Content-Type") ?: "text/plain"
            val raw = resp.body?.bytes() ?: return@execute null
            // A decrypt failure (key mismatch) is not a connectivity error: null
            // without onError, so it can't invalidate a perfectly good address.
            val bytes = if (keyBytes == null) raw
                        else runCatching { RD2Crypto.open(raw, keyBytes) }.getOrNull()
                            ?: return@execute null
            ClipboardData(version, ct, bytes)
        }
    }

    /** POST the phone's clipboard to the Mac. True on 200 (Mac accepted + pasted);
     *  a connectivity failure additionally fires [onError]. */
    fun push(peer: ResolvedPeer, token: String?, keyBytes: ByteArray?,
             contentType: String, bytes: ByteArray, onError: () -> Unit = {}): Boolean {
        val url = "http://${bracketed(peer)}:${peer.port}${RD2Mac.Path.CLIPBOARD}"
        val payload = keyBytes?.let { RD2Crypto.seal(bytes, it) } ?: bytes
        val body = payload.toRequestBody(contentType.toMediaTypeOrNull())
        val builder = Request.Builder().url(url).post(body).header("Content-Type", contentType)
        if (!token.isNullOrEmpty()) builder.header(RD2Mac.Header.TOKEN, token)
        return RD2Http.execute(builder.build(), onError) { it.code == 200 } ?: false
    }

    /** IPv6 literals need brackets in a URL. */
    internal fun bracketed(peer: ResolvedPeer): String =
        if (peer.host.contains(":")) "[${peer.host}]" else peer.host
}
