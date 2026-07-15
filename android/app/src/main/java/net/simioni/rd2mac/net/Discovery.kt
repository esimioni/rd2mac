package net.simioni.rd2mac.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import net.simioni.rd2mac.Platform
import net.simioni.rd2mac.RD2Mac
import java.net.Inet4Address
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

data class ResolvedPeer(
    val id: String,          // peer's stable per-install id (from the TXT record)
    val name: String,
    val host: String,
    val port: Int,
    val platform: Platform,
)

/**
 * When the continuous mDNS browse should run. Once the paired Mac is resolved
 * and nobody is looking at the pick list, the browse goes DORMANT: transfers use
 * the last-known address, and a connectivity failure invalidates it (flipping
 * `pairedResolved` to false), which wakes the browse to re-resolve. Advertising
 * is unaffected — it's passive and the Mac needs it to find this phone.
 */
object BrowsePolicy {
    fun shouldBrowse(paired: Boolean, pairedResolved: Boolean, uiVisible: Boolean): Boolean =
        !paired || !pairedResolved || uiVisible
}

/**
 * Filters `registerDefaultNetworkCallback.onAvailable` down to REAL network
 * changes: the callback fires immediately for the current network on
 * registration, and treating that as a change invalidated the freshly resolved
 * peer at every service start. Pure (network is an opaque token), unit-tested.
 */
class NetworkChangeGate {
    private var current: Any? = null

    /** True only when an already-established network is replaced by another. */
    fun isChange(network: Any): Boolean {
        val previous = current
        current = network
        return previous != null && previous != network
    }
}

/**
 * Bonjour/mDNS via NsdManager: advertises this phone as `_rd2mac._tcp` and
 * browses for the Mac. Mirrors the Swift `PeerAdvertiser` / `PeerBrowser`.
 *
 * NsdManager historically fails a `resolveService` call while another resolve is
 * in flight (FAILURE_ALREADY_ACTIVE), so resolves are serialized through a queue.
 * Resolve callbacks arrive on a binder thread — `onResolve` consumers must hop to
 * the main thread if they touch UI.
 */
class Discovery(context: Context) {

    private val nsd = context.applicationContext
        .getSystemService(Context.NSD_SERVICE) as NsdManager

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    // Serialize resolves.
    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    private val resolving = AtomicBoolean(false)

    // Live peers, keyed by their (unique) Bonjour instance name. Guarded by its
    // own monitor: resolve callbacks and onServiceLost arrive on binder threads.
    private val peers = LinkedHashMap<String, ResolvedPeer>()

    fun advertise(name: String, port: Int, txt: Map<String, String>) {
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = RD2Mac.SERVICE_TYPE
            setPort(port)
            for ((k, v) in txt) setAttribute(k, v)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "advertised as ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "advertise failed: $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        registrationListener = listener
    }

    /** True while a browse is registered (the dormant state is `false`). */
    val browsing: Boolean get() = discoveryListener != null

    fun browse(excludePlatform: Platform, onPeersChanged: (List<ResolvedPeer>) -> Unit) {
        if (browsing) return
        Log.i(TAG, "browse started")
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                Log.i(TAG, "found: ${service.serviceName} type=${service.serviceType}")
                if (service.serviceType.contains("rd2mac", ignoreCase = true)) {
                    resolveQueue.add(service)
                    pumpResolve(excludePlatform, onPeersChanged)
                }
            }
            override fun onServiceLost(service: NsdServiceInfo) {
                val changed = synchronized(peers) { peers.remove(service.serviceName) != null }
                if (changed) onPeersChanged(snapshot())
            }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery start failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        nsd.discoverServices(RD2Mac.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        discoveryListener = listener
    }

    private fun pumpResolve(excludePlatform: Platform, onPeersChanged: (List<ResolvedPeer>) -> Unit) {
        if (!resolving.compareAndSet(false, true)) return
        val next = resolveQueue.poll()
        if (next == null) {
            resolving.set(false)
            // Re-check: another thread may have enqueued between our poll() and
            // set(false) and lost its CAS — without this the item would sit until
            // the next mDNS event.
            if (resolveQueue.isNotEmpty()) pumpResolve(excludePlatform, onPeersChanged)
            return
        }

        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onServiceResolved(info: NsdServiceInfo) {
                emit(info, excludePlatform, onPeersChanged)
                resolving.set(false)
                pumpResolve(excludePlatform, onPeersChanged)
            }
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "resolve failed for ${info.serviceName}: $errorCode")
                resolving.set(false)
                pumpResolve(excludePlatform, onPeersChanged)
            }
        })
    }

    private fun emit(info: NsdServiceInfo, excludePlatform: Platform,
                     onPeersChanged: (List<ResolvedPeer>) -> Unit) {
        val attrs = info.attributes ?: emptyMap()
        val platform = Platform.from(attrs["platform"]?.toString(Charsets.UTF_8))
        val id = attrs["id"]?.toString(Charsets.UTF_8) ?: ""
        val host = preferredHost(info)
        Log.i(TAG, "resolved: ${info.serviceName} host=$host port=${info.port} platform=$platform")
        if (platform == excludePlatform) return               // ignore ourselves
        if (host == null) return
        synchronized(peers) {
            peers[info.serviceName] = ResolvedPeer(id, info.serviceName, host, info.port, platform)
        }
        onPeersChanged(snapshot())
    }

    private fun snapshot(): List<ResolvedPeer> = synchronized(peers) { peers.values.toList() }

    /** Prefer IPv4 (mirrors the Swift `preferredIP`): a v6 link-local without its
     *  scope id ("fe80::…") is unconnectable once stringified. API 34+ exposes
     *  all resolved addresses; before that there's only the single `host`. */
    private fun preferredHost(info: NsdServiceInfo): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val addrs = info.hostAddresses
            (addrs.firstOrNull { it is Inet4Address } ?: addrs.firstOrNull())?.hostAddress
        } else {
            @Suppress("DEPRECATION")
            info.host?.hostAddress
        }

    /** Stop browsing but KEEP the resolved peers: while dormant, transfers keep
     *  using the last-known address (a failure there invalidates it via [forget]). */
    fun stopBrowse() {
        if (!browsing) return
        Log.i(TAG, "browse stopped (dormant — using last-known addresses)")
        discoveryListener?.let { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }
        discoveryListener = null
        resolveQueue.clear()
    }

    /** Drop one peer's cached resolution (its address failed / network changed);
     *  returns the updated snapshot. The caller re-evaluates the browse policy —
     *  an unresolved paired peer wakes the browse to find it again. */
    fun forget(name: String): List<ResolvedPeer> {
        synchronized(peers) { peers.remove(name) }
        return snapshot()
    }

    fun stop() {
        registrationListener?.let { try { nsd.unregisterService(it) } catch (_: Exception) {} }
        registrationListener = null
        stopBrowse()
        synchronized(peers) { peers.clear() }
    }

    companion object { private const val TAG = "RD2Mac.Discovery" }
}
