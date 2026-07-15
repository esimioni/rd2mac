package net.simioni.rd2mac.net

import net.simioni.rd2mac.HeaderCodec
import net.simioni.rd2mac.HeaderCrypt
import net.simioni.rd2mac.RD2Mac
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * The one process-wide OkHttpClient. Every HTTP call in the app (transfers, pair,
 * clipboard poll) goes through this instance: each OkHttpClient owns a dispatcher
 * thread pool + connection pool, so building one per call — with the clipboard
 * polling every 1.5s — leaked dozens of idle pools.
 */
object RD2Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Execute + map a response; a CONNECTIVITY failure (connect/timeout — the
     *  peer's cached address is bad or it's gone) fires [onError] and yields
     *  null. A reachable server answering non-200 is NOT a connectivity error —
     *  handle status codes inside [map]. */
    fun <T> execute(request: Request, onError: () -> Unit = {}, map: (okhttp3.Response) -> T?): T? =
        try {
            client.newCall(request).execute().use(map)
        } catch (e: Exception) {
            onError()
            null
        }
}

class FilePayload(
    val filename: String,
    val mime: String,
    val bytes: ByteArray,
)

/**
 * Retry policy that guarantees NO silent success: reports success only when an
 * attempt genuinely returns true (a real HTTP 200). An exception or a non-200 is
 * a failure and triggers a retry; exhausting attempts reports failure. This is
 * the core of the phantom-send fix. Delays and sleep are injected so tests don't
 * actually wait.
 */
class RetrySender(
    private val maxAttempts: Int = 3,
    private val backoffMs: (attempt: Int) -> Long = { i ->
        longArrayOf(0L, 500L, 1500L).getOrElse(i) { 3000L }
    },
    private val sleeper: (Long) -> Unit = { if (it > 0) Thread.sleep(it) },
) {
    /** @param attempt performs one delivery; returns true only on a real success. */
    fun send(attempt: () -> Boolean): Boolean {
        for (i in 0 until maxAttempts) {
            sleeper(backoffMs(i))
            val ok = try { attempt() } catch (_: Exception) { false }
            if (ok) return true
        }
        return false
    }
}

/**
 * Sends a batch of payloads to one peer, each through the retry policy, and
 * aggregates the outcome. Kept free of Android deps so it is unit-testable; the
 * Activity only feeds it payloads and renders the result.
 */
class TransferOrchestrator(
    private val retry: RetrySender = RetrySender(),
    private val post: (ResolvedPeer, FilePayload) -> Boolean,
) {
    data class Outcome(val sent: Int, val failed: Int) {
        val allSent: Boolean get() = failed == 0 && sent > 0
    }

    fun sendAll(peer: ResolvedPeer, payloads: List<FilePayload>): Outcome {
        var sent = 0
        var failed = 0
        for (payload in payloads) {
            if (retry.send { post(peer, payload) }) sent++ else failed++
        }
        return Outcome(sent, failed)
    }
}

/** Real `POST /receive` over OkHttp. Returns true only on an HTTP 200 whose body
 *  proves the peer holds the pairing key (sealed response — see PROTOCOL.md). */
class OkHttpPoster(
    private val peerName: String,
    private val token: String? = null,
    private val keyBytes: ByteArray? = null,
    private val client: OkHttpClient = RD2Http.client,
) {
    fun post(peer: ResolvedPeer, payload: FilePayload): Boolean {
        val host = if (peer.host.contains(":")) "[${peer.host}]" else peer.host
        val url = "http://$host:${peer.port}${RD2Mac.Path.RECEIVE}"
        val bytes = keyBytes?.let { RD2Crypto.seal(payload.bytes, it) } ?: payload.bytes
        val body = bytes.toRequestBody(payload.mime.toMediaTypeOrNull())
        val builder = Request.Builder()
            .url(url)
            .post(body)
            // Filename sealed with the pairing key (S1): the peer name is public
            // Bonjour data, but filenames are content metadata.
            .header(RD2Mac.Header.FILENAME, HeaderCrypt.encode(payload.filename, keyBytes))
            .header(RD2Mac.Header.PEER, HeaderCodec.encode(peerName))
        if (!token.isNullOrEmpty()) builder.header(RD2Mac.Header.TOKEN, token)
        return client.newCall(builder.build()).execute().use { resp ->
            if (resp.code != 200) false
            else confirmedDelivery(resp.body?.bytes())
        }
    }

    /** A 200 counts as delivery only when the body opens with the pairing key —
     *  an active MITM can forge a bare 200, resurrecting the phantom-send bug. */
    private fun confirmedDelivery(body: ByteArray?): Boolean {
        val key = keyBytes ?: return true    // unpaired legacy path: nothing to verify against
        if (body == null) return false
        return try { RD2Crypto.open(body, key); true } catch (e: Exception) { false }
    }
}

/** POST /pair handshake: send our ECDH public key, get the peer's back (null on 403/fail). */
object PairClient {
    fun pair(peer: ResolvedPeer, myId: String, myName: String, myPubKey: String): String? {
        val host = if (peer.host.contains(":")) "[${peer.host}]" else peer.host
        val url = "http://$host:${peer.port}${RD2Mac.Path.PAIR}"
        val json = org.json.JSONObject()
            .put("id", myId).put("name", myName).put("pubKey", myPubKey).toString()
        val body = json.toRequestBody("application/json".toMediaTypeOrNull())
        val req = Request.Builder().url(url).post(body).build()
        return try {
            RD2Http.client.newCall(req).execute().use { resp ->
                if (resp.code != 200) return null
                org.json.JSONObject(resp.body?.string() ?: return null).getString("pubKey")
            }
        } catch (e: Exception) {
            null
        }
    }
}
