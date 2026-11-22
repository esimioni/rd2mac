package net.simioni.rd2mac

import net.simioni.rd2mac.net.ClipboardClient
import net.simioni.rd2mac.net.PeerAddress
import net.simioni.rd2mac.net.ResolvedPeer
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.Inet6Address
import java.net.InetAddress

/** Which resolved address becomes a peer's host — a host every client then puts
 *  in a URL. The phone's service crashed twice on 2026-11-20: mDNS had resolved
 *  the Mac only as a scoped v6 link-local, and OkHttp refuses a scope id in a
 *  URL host (IllegalArgumentException out of the clipboard poll). */
class PeerAddressTest {

    private val v4 = InetAddress.getByName("10.0.0.5")
    private val linkLocal: InetAddress =
        Inet6Address.getByAddress(null, InetAddress.getByName("fe80::1").address, 2)
    private val global = InetAddress.getByName("2001:db8::7")

    /** The URL a client builds for [host] (ClipboardClient, Proximity, Sender). */
    private fun urlOf(host: String) = Request.Builder()
        .url("http://${ClipboardClient.bracketed(peerAt(host))}:53318/ping")
        .build().url

    private fun peerAt(host: String) = ResolvedPeer("1", "Mac", host, 53318, Platform.MACOS)

    @Test fun ipv4WinsEvenListedAfterAV6() {
        assertEquals("10.0.0.5", PeerAddress.pick(listOf(linkLocal, v4)))
    }

    @Test fun aScopedLinkLocalAloneIsNoAddress() {
        // The premise: as a host it makes a URL OkHttp refuses — the crash.
        assertThrows(IllegalArgumentException::class.java) { urlOf(linkLocal.hostAddress!!) }
        assertNull(PeerAddress.pick(listOf(linkLocal)))
    }

    @Test fun aRoutableV6IsUsedWhenThereIsNoV4() {
        val host = PeerAddress.pick(listOf(linkLocal, global))!!
        assertEquals("2001:db8::7", urlOf(host).host)
    }

    @Test fun nothingResolvedIsNoAddress() {
        assertNull(PeerAddress.pick(emptyList()))
    }
}