package net.simioni.rd2mac.net

import net.simioni.rd2mac.Platform
import net.simioni.rd2mac.RD2Mac
import okhttp3.Request
import org.json.JSONObject

/** A `/ping` answer: who is at that address (PROTOCOL.md). */
data class PeerIdentity(val id: String, val name: String, val platform: Platform) {
    companion object {
        /** Null for anything that isn't an identity — an empty id confirms nobody. */
        fun parse(body: String?): PeerIdentity? {
            if (body == null) return null
            val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
            val id = json.optString("id")
            if (id.isEmpty()) return null
            return PeerIdentity(id, json.optString("name"), Platform.from(json.optString("platform")))
        }
    }
}

/**
 * The unicast way back to the paired Mac, for when mDNS can't find it.
 *
 * Once a request fails at the connectivity level the Mac's address is dropped
 * (`onPeerUnreachable`), and the browse alone has to bring it back. On a Wi-Fi that
 * drops the Mac's multicast that never happens: measured 27/11/2026, half an hour of
 * "Looking on the network…" through six re-announcements, while `GET /ping` at the
 * Mac's last address answered at once. So while the paired Mac is lost, its last
 * address is also knocked on directly — the same move the Mac makes before dropping
 * a phone that went quiet on multicast (`PeerBrowser.pingProbe`).
 *
 * Pure: the clock and the network belong to the caller, which asks [due] on every
 * tick and hands the `/ping` answer to [confirm].
 */
class PeerRecovery(
    private val everyMs: Long = EVERY_MS,
    private val debounceMs: Long = DEBOUNCE_MS,
) {
    /** When the last knock went out; null = never, so the first one is due at once. */
    private var lastTry: Long? = null

    /**
     * The address to knock on now, or null: nothing to recover (resolved, unpaired,
     * no address ever known), not on Wi-Fi (a LAN address is unreachable on mobile
     * data, and each try would wake the radio), or not due yet. The period runs from
     * the last knock, not from the loss: a Mac lost after hours resolved is tried on
     * the next tick, and one dropped right after a recovery waits out the period, so
     * an address that answers `/ping` but fails the poll can't loop. [forced] = a
     * person is waiting (screen open, share, tile): only debounced, so a burst of
     * reasons costs one knock.
     */
    fun due(paired: PairedPeer?, resolved: Boolean, onWifi: Boolean, now: Long,
            forced: Boolean = false): ResolvedPeer? {
        if (resolved || !onWifi) return null
        val candidate = paired?.lastKnown() ?: return null
        val last = lastTry
        if (last != null && now - last < if (forced) debounceMs else everyMs) return null
        lastTry = now
        return candidate
    }

    companion object {
        const val EVERY_MS = 15_000L
        const val DEBOUNCE_MS = 2_000L
        /** The caller's tick — finer than [EVERY_MS], so timer jitter can't skip a period. */
        const val TICK_MS = 5_000L

        /** The peer to adopt when [identity] — the answer at [candidate]'s address — is
         *  the paired Mac itself; null otherwise (the address may belong to another
         *  device by now). The name comes from the answer: the Mac may have been renamed. */
        fun confirm(candidate: ResolvedPeer, identity: PeerIdentity?): ResolvedPeer? =
            if (identity == null || identity.id != candidate.id) null
            else candidate.copy(name = identity.name.ifEmpty { candidate.name })
    }
}

/** `GET /ping` — who is at an address. */
object PingClient {
    /** Null when nobody answers. Never fires `onPeerUnreachable`: while the Mac is
     *  lost, a knock that goes unanswered is the expected outcome. */
    fun identity(peer: ResolvedPeer): PeerIdentity? {
        val url = "http://${ClipboardClient.bracketed(peer)}:${peer.port}${RD2Mac.Path.PING}"
        return RD2Http.execute(Request.Builder().url(url).get().build()) { resp ->
            if (resp.code == 200) PeerIdentity.parse(resp.body?.string()) else null
        }
    }
}