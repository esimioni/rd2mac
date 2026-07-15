package net.simioni.rd2mac.net

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * The peer this device has explicitly chosen to talk to, persisted so it never
 * changes until the user picks another (or forgets). Mirror of the Swift
 * `PairedPeer`. Pairing is independent per side.
 */
data class PairedPeer(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    /** Auth token derived from the ECDH secret (header). */
    val token: String,
    /** AES-256 key (base64) derived from the same secret; encrypts content. */
    val key: String = "",
)

/**
 * Pure authorization rules for the handshake — mirror of the Swift `PairingAuth`.
 * Keep them byte-for-byte identical so both sides agree.
 */
object PairingAuth {
    /** A transfer is allowed only when paired AND the caller presents the exact
     *  token. Not paired → accept nothing (keeps strangers out on public Wi-Fi). */
    fun authorize(pairedToken: String?, requestToken: String?): Boolean {
        if (pairedToken.isNullOrEmpty()) return false
        return requestToken == pairedToken
    }

    /** Accept `POST /pair` when free or when the same peer re-pairs; reject a
     *  different peer hijacking an established pairing (owner must Forget first). */
    fun acceptPair(currentId: String?, incomingId: String): Boolean {
        if (currentId.isNullOrEmpty()) return true
        return currentId == incomingId
    }
}

/**
 * Pure selection of the active peer — no Android deps, so it's unit-testable and
 * shared by both the service and the share path. Mirror of the Swift
 * `PeerSelection`.
 */
object PeerSelection {
    /** The live peer whose id matches the paired one; null when unpaired or the
     *  paired peer isn't on the network right now. */
    fun activePeer(live: List<ResolvedPeer>, pairedId: String?): ResolvedPeer? {
        if (pairedId.isNullOrEmpty()) return null
        return live.firstOrNull { it.id == pairedId }
    }
}

/**
 * Persists this device's stable id and the chosen peer in SharedPreferences.
 * Mirror of the Swift `PairingStore` (UserDefaults).
 */
class PairingStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("rd2mac", Context.MODE_PRIVATE)

    /** Stable per-install id, generated once on first read and persisted. */
    val deviceId: String
        get() {
            prefs.getString(KEY_DEVICE_ID, null)?.let { if (it.isNotEmpty()) return it }
            val fresh = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, fresh).apply()
            return fresh
        }

    var pairedPeer: PairedPeer?
        get() {
            val id = prefs.getString(KEY_ID, null) ?: return null
            return PairedPeer(
                id = id,
                name = prefs.getString(KEY_NAME, "") ?: "",
                host = prefs.getString(KEY_HOST, "") ?: "",
                port = prefs.getInt(KEY_PORT, 0),
                token = prefs.getString(KEY_TOKEN, "") ?: "",
                key = prefs.getString(KEY_KEY, "") ?: "",
            )
        }
        set(value) {
            val e = prefs.edit()
            if (value == null) {
                e.remove(KEY_ID).remove(KEY_NAME).remove(KEY_HOST).remove(KEY_PORT)
                    .remove(KEY_TOKEN).remove(KEY_KEY)
            } else {
                e.putString(KEY_ID, value.id).putString(KEY_NAME, value.name)
                    .putString(KEY_HOST, value.host).putInt(KEY_PORT, value.port)
                    .putString(KEY_TOKEN, value.token).putString(KEY_KEY, value.key)
            }
            e.apply()
        }

    fun pair(peer: ResolvedPeer, session: RD2Crypto.Session) =
        pair(peer.id, peer.name, session, peer.host, peer.port)

    /** Same, for a peer known only by id/name (incoming pairing before discovery
     *  resolves it — host/port are just a last-known cache anyway). */
    fun pair(id: String, name: String, session: RD2Crypto.Session,
             host: String = "", port: Int = net.simioni.rd2mac.RD2Mac.DEFAULT_PORT) {
        pairedPeer = PairedPeer(id, name, host, port,
            session.token, java.util.Base64.getEncoder().encodeToString(session.keyBytes))
    }

    fun forget() {
        pairedPeer = null
    }

    /** Whether this phone pulls + pastes the Mac's clipboard (receive switch). Off by default. */
    var receiveClipboard: Boolean
        get() = prefs.getBoolean(KEY_RCV_CLIP, false)
        set(value) = prefs.edit().putBoolean(KEY_RCV_CLIP, value).apply()

    /** Optional proximity gate: automatic clipboard sync only while the Mac's BLE
     *  beacon is heard above [proximityRssi]. Off by default. */
    var proximityEnabled: Boolean
        get() = prefs.getBoolean(KEY_PROX_ON, false)
        set(value) = prefs.edit().putBoolean(KEY_PROX_ON, value).apply()

    /** RSSI threshold for "near" (dBm; ~-60 desk, ~-75 room, ~-90 house). */
    var proximityRssi: Int
        get() = prefs.getInt(KEY_PROX_RSSI, -75)
        set(value) = prefs.edit().putInt(KEY_PROX_RSSI, value).apply()

    companion object {
        private const val KEY_DEVICE_ID = "deviceId"
        private const val KEY_ID = "pairedId"
        private const val KEY_NAME = "pairedName"
        private const val KEY_HOST = "pairedHost"
        private const val KEY_PORT = "pairedPort"
        private const val KEY_TOKEN = "pairedToken"
        private const val KEY_KEY = "pairedKey"
        private const val KEY_RCV_CLIP = "receiveClipboard"
        private const val KEY_PROX_ON = "proximityEnabled"
        private const val KEY_PROX_RSSI = "proximityRssi"
    }
}
