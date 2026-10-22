package net.simioni.rd2mac

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import net.simioni.rd2mac.http.HttpServer
import net.simioni.rd2mac.net.ClipboardGate
import net.simioni.rd2mac.net.ClipboardLoopGuard
import net.simioni.rd2mac.net.ProximityBeacon
import net.simioni.rd2mac.net.ProximityClient
import net.simioni.rd2mac.net.ProximityGate
import net.simioni.rd2mac.net.ProximityMonitor
import net.simioni.rd2mac.net.ProximityReporter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import net.simioni.rd2mac.media.MediaLocationPolicy
import net.simioni.rd2mac.media.MediaQuery
import net.simioni.rd2mac.media.MediaSaver
import net.simioni.rd2mac.media.MediaStoreRepo
import net.simioni.rd2mac.media.ServedFile
import net.simioni.rd2mac.net.BrowsePolicy
import net.simioni.rd2mac.net.ClipboardClient
import net.simioni.rd2mac.net.Discovery
import net.simioni.rd2mac.net.PairedPeer
import net.simioni.rd2mac.net.PairingAuth
import net.simioni.rd2mac.net.PairingStore
import net.simioni.rd2mac.net.PeerSelection
import net.simioni.rd2mac.net.RD2Crypto
import net.simioni.rd2mac.net.ResolvedPeer
import net.simioni.rd2mac.net.WireBody
import net.simioni.rd2mac.update.AppUpdater
import net.simioni.rd2mac.update.TransferActivity
import org.json.JSONObject
import java.io.EOFException
import java.security.GeneralSecurityException

/** Live service state, observed by the UI (same process, personal use). */
object RD2MacState {
    @Volatile var running = false
    @Volatile var boundPort = 0
    /** Every Mac currently on the network — the pick list shown in the UI. */
    @Volatile var livePeers: List<ResolvedPeer> = emptyList()
    /** The Mac the user chose (persisted; loaded on service start, updated by the UI). */
    @Volatile var pairedPeer: PairedPeer? = null
    /** The peer we actually talk to: the paired one, if it's on the network now. */
    val peer: ResolvedPeer? get() = PeerSelection.activePeer(livePeers, pairedPeer?.id)
    /** Whether this phone pulls + pastes the Mac's clipboard (mirror of the switch). */
    @Volatile var receiveClipboard = false
    /** Whether photos leave with their GPS Exif tags (mirror of the switch; only
     *  true while ACCESS_MEDIA_LOCATION is actually held — see MediaLocationPolicy). */
    @Volatile var keepPhotoLocation = false
    /** Proximity gate switch (mirror) and live near/far status for the UI. */
    @Volatile var proximityEnabled = false
    @Volatile var proximityNear = false
    /** MainActivity is on screen (its pick list needs a live mDNS browse). Set
     *  directly by the Activity (same process) — a startService intent from
     *  onPause can be dropped once the app is leaving the foreground. */
    @Volatile var uiVisible = false
    /** Service hook: re-evaluate the browse policy after a state change. */
    @Volatile var evalBrowse: (() -> Unit)? = null
    /** A pairing the Mac initiated with us, awaiting the user to compare the code. */
    @Volatile var pendingPairing: PendingPairing? = null
    /** Shared by the service (auto push/paste) and the tile path, so a manual
     *  send is also remembered and its poll echo suppressed. */
    val clipGuard = ClipboardLoopGuard()
    var onChange: (() -> Unit)? = null
    fun notifyChange() = Handler(Looper.getMainLooper()).post { onChange?.invoke() }
}

/** A pairing the peer initiated, held until the user compares the SAS code. */
class PendingPairing(val id: String, val name: String, val session: RD2Crypto.Session)

/**
 * Foreground service holding the HTTP server + Bonjour alive so the Mac can
 * reach the phone with the app "closed". Serves /ping, /pair, /receive,
 * /screenshot/latest and /media/latest, and runs the clipboard receive-poll.
 */
class RD2MacService : Service() {

    private lateinit var server: HttpServer
    private lateinit var discovery: Discovery
    private lateinit var pairing: PairingStore
    private var started = false

    private val clipHandler = Handler(Looper.getMainLooper())
    /** Single worker for all polls/pushes: serializes them (no overlapping
     *  transfers) and avoids a fresh Thread per tick. */
    private val clipExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var clipPolling = false
    /** Written by the poll worker, reset by the main thread on (re)start. */
    @Volatile private var lastClipVersion = ClipboardGate.UNPRIMED
    /** Cuts the paste→tile-push→poll→paste echo loop in both directions. */
    private val clipGuard get() = RD2MacState.clipGuard
    /** Only alive while the proximity option gates an active sync direction. */
    private var proximity: ProximityMonitor? = null
    /** Threshold the running monitor was built with (rebuild only on change). */
    private var proxRssi = 0
    /** When to tell the Mac about gate state (change + keepalive). */
    private val proxReporter = ProximityReporter()

    override fun onCreate() {
        super.onCreate()
        pairing = PairingStore(this)
        Notifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Idempotent: onStartCommand fires again on every startService (e.g. from
        // the share activity). Don't re-bind the socket — that throws EADDRINUSE.
        if (!started) {
            started = true
            startAsForeground(getString(R.string.notif_ready))
            startServer()
            startDiscovery()
            RD2MacState.running = true
            RD2MacState.boundPort = server.boundPort      // real port (fallback when busy)
            RD2MacState.pairedPeer = pairing.pairedPeer   // restore the chosen Mac
            RD2MacState.receiveClipboard = pairing.receiveClipboard
            RD2MacState.keepPhotoLocation = effectiveKeepPhotoLocation()
            RD2MacState.proximityEnabled = pairing.proximityEnabled
            RD2MacState.notifyChange()
            if (pairing.receiveClipboard) startClipboardPolling()
            updateProximityMonitor()
            AppUpdater.start(this)
        }
        when (intent?.action) {
            ACTION_SET_CLIPBOARD -> {
                val on = intent.getBooleanExtra(EXTRA_ON, false)
                pairing.receiveClipboard = on
                RD2MacState.receiveClipboard = on
                if (on) startClipboardPolling() else stopClipboardPolling()
                updateProximityMonitor()
                RD2MacState.notifyChange()   // tiles/UI reflect the toggle
            }
            ACTION_SET_PHOTO_LOCATION -> {
                pairing.keepPhotoLocation = intent.getBooleanExtra(EXTRA_ON, false)
                RD2MacState.keepPhotoLocation = effectiveKeepPhotoLocation()
                RD2MacState.notifyChange()
            }
            ACTION_SET_PROXIMITY -> {
                val on = intent.getBooleanExtra(EXTRA_ON, false)
                pairing.proximityEnabled = on
                pairing.proximityRssi = intent.getIntExtra(EXTRA_RSSI, pairing.proximityRssi)
                RD2MacState.proximityEnabled = on
                updateProximityMonitor()
                RD2MacState.notifyChange()
            }
        }
        return START_STICKY
    }

    /** The switch, reconciled with the permission it depends on (revocable in
     *  Settings without telling the app). */
    private fun effectiveKeepPhotoLocation(): Boolean = MediaLocationPolicy.effective(
        switchOn = pairing.keepPhotoLocation,
        permissionGranted = checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION)
            == PackageManager.PERMISSION_GRANTED)

    /**
     * The user cleared the recents list. The service is declared
     * `stopWithTask="false"`, so AOSP keeps it running — but Samsung's One UI kills
     * the whole process anyway, and that is exactly how the app "disappears" when
     * a user clears everything without meaning to close RD2Mac. Re-arm from the
     * outside: an alarm one second out restarts the service after the kill.
     *
     * It only lands if the app is exempt from battery optimisation, which is why
     * the screen offers that switch — a restricted app can't start a foreground
     * service from the background (Android 12+).
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val restart = PendingIntent.getForegroundService(
            this, 0, Intent(applicationContext, RD2MacService::class.java),
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
        (getSystemService(ALARM_SERVICE) as android.app.AlarmManager)
            .set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 1000, restart)
        super.onTaskRemoved(rootIntent)
    }

    /** The BLE scan runs only while the option is on AND some automatic sync
     *  needs gating. A call that changes nothing is a no-op: rebuilding the
     *  monitor on every toggle reset the gate to "far" AND stacked scan starts
     *  toward Android's 5-per-30s throttle (SCAN_FAILED_SCANNING_TOO_FREQUENTLY
     *  with no retry — the original "Mac away at 15 cm" bug). */
    private fun updateProximityMonitor() {
        val wanted = pairing.proximityEnabled && pairing.receiveClipboard
        val rssi = pairing.proximityRssi
        if (wanted == (proximity != null) && (!wanted || rssi == proxRssi)) return
        proximity?.stop()
        proximity = null
        proxRssi = rssi
        if (wanted) {
            proximity = ProximityMonitor(
                this,
                ProximityGate(rssi, ttlMs = ProximityMonitor.TTL_MS,
                    hysteresis = net.simioni.rd2mac.net.ProximityLevels.hysteresisFor(rssi)),
                beaconUuids = {
                    keyBytes()?.let { ProximityBeacon.uuidsAround(it, System.currentTimeMillis()) }
                        ?: emptyList()
                },
            ) { near -> onProximityEvaluated(near) }.also { it.start() }
        } else {
            if (RD2MacState.proximityNear) {
                RD2MacState.proximityNear = false
                RD2MacState.notifyChange()
            }
            // One "gate off" report drops the Mac's menu row right away (its
            // stale-report TTL would clear it eventually anyway).
            reportProximity(enabled = false, near = false)
        }
    }

    /** Periodic evaluation from the monitor (main thread): drives the UI state —
     *  including the TTL decay to "far", which no scan callback would deliver —
     *  and the status reports the Mac shows in its menu. */
    private fun onProximityEvaluated(near: Boolean) {
        if (RD2MacState.proximityNear != near) {
            RD2MacState.proximityNear = near
            RD2MacState.notifyChange()
        }
        reportProximity(enabled = true, near = near)
    }

    /** Send the gate status to the Mac when the reporter says it's due. */
    private fun reportProximity(enabled: Boolean, near: Boolean) {
        val peer = RD2MacState.peer ?: return
        if (!proxReporter.shouldReport(enabled, near, System.currentTimeMillis())) return
        val token = RD2MacState.pairedPeer?.token
        val key = keyBytes()
        clipExecutor.execute {
            val ok = ProximityClient.report(peer, token, key, enabled, near,
                onError = ::onPeerUnreachable)
            Log.i(CLIP_TAG, "prox report enabled=$enabled near=$near → ${if (ok) "OK" else "FAILED"}")
        }
    }

    /** True ⇒ automatic sync suppressed (option on + Mac's beacon not heard).
     *  Manual actions (tile, share) are never gated. */
    private fun proximityBlocked(): Boolean {
        if (!pairing.proximityEnabled) return false
        return !(proximity?.isNear() ?: false)
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        stopClipboardPolling()
        proximity?.stop()
        proximity = null
        clipExecutor.shutdownNow()
        RD2MacState.evalBrowse = null
        try {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                .unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
        if (::server.isInitialized) server.stop()
        if (::discovery.isInitialized) discovery.stop()
        RD2MacState.running = false
        RD2MacState.notifyChange()
        super.onDestroy()
    }

    // MARK: clipboard receive — poll the Mac and paste (Android 10+ allows WRITING
    // the clipboard from the background; only reading it needs focus).

    private fun startClipboardPolling() {
        if (clipPolling) return
        clipPolling = true
        lastClipVersion = ClipboardGate.UNPRIMED
        clipHandler.post(clipPoll)
    }

    private fun stopClipboardPolling() {
        clipPolling = false
        clipHandler.removeCallbacks(clipPoll)
    }

    private val clipPoll = object : Runnable {
        override fun run() {
            if (!clipPolling) return
            clipExecutor.execute { pollClipboardOnce() }   // network off the main thread
            clipHandler.postDelayed(this, CLIP_POLL_MS)
        }
    }

    private fun pollClipboardOnce() {
        if (proximityBlocked()) return
        val peer = RD2MacState.peer ?: return
        val data = ClipboardClient.pull(peer, RD2MacState.pairedPeer?.token, keyBytes(),
            lastClipVersion, onError = ::onPeerUnreachable) ?: return
        val (next, paste) = ClipboardGate.advance(lastClipVersion, data.version)
        lastClipVersion = next
        if (paste) clipHandler.post { pasteToClipboard(data) }   // clipboard writes on the main thread
    }

    private fun pasteToClipboard(data: net.simioni.rd2mac.net.ClipboardData) {
        if (!clipGuard.shouldPaste(data.bytes)) return   // echo of our own push
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (data.contentType.startsWith("image/")) {
            val uri = ClipboardImageStore.uriFor(this, data.bytes) ?: return
            cm.setPrimaryClip(ClipData.newUri(contentResolver, "RD2Mac", uri))
        } else {
            cm.setPrimaryClip(ClipData.newPlainText("RD2Mac", String(data.bytes, Charsets.UTF_8)))
        }
    }

    // MARK: server

    private fun startServer() {
        server = HttpServer(RD2Mac.DEFAULT_PORT, activity = TransferActivity.shared,
                            tracks = ::isPhoneTransfer) { request -> route(request) }
        server.start()
    }

    private fun route(request: HttpServer.Request): HttpServer.Response = when {
        request.method == "GET" && request.path == RD2Mac.Path.PING ->
            HttpServer.Response.json(200, identityJson())          // open: health + discovery

        request.method == "POST" && request.path == RD2Mac.Path.PAIR ->
            handlePair(request)                                    // open: establishes the token

        request.method == "POST" && request.path == RD2Mac.Path.RECEIVE ->
            if (authorized(request)) handleReceive(request) else unauthorized()

        request.method == "GET" && request.path == RD2Mac.Path.SCREENSHOT_LATEST ->
            if (authorized(request)) serveFile(MediaStoreRepo.latestScreenshot(
                contentResolver, MediaQuery.skip(request.query["skip"]),
                RD2MacState.keepPhotoLocation))
            else unauthorized()

        request.method == "GET" && request.path == RD2Mac.Path.MEDIA_LATEST ->
            if (authorized(request)) serveFile(MediaStoreRepo.latestMedia(
                contentResolver, request.query["type"] ?: "image",
                MediaQuery.skip(request.query["skip"]), RD2MacState.keepPhotoLocation))
            else unauthorized()

        else -> HttpServer.Response.text(404, "no route for ${request.method} ${request.path}")
    }

    private fun authorized(request: HttpServer.Request): Boolean =
        PairingAuth.authorize(RD2MacState.pairedPeer?.token, request.header(RD2Mac.Header.TOKEN))

    private fun unauthorized() = HttpServer.Response.text(401, "unauthorized")

    /** A Mac initiated pairing: accept if free (or same peer re-pairing), adopt its
     *  token, and persist. Reject a hijack of an existing pairing (403). */
    private fun handlePair(request: HttpServer.Request): HttpServer.Response {
        val raw = request.bodyBytes()   // oversized/stalled body → the server's 413/408
        val body = try {
            JSONObject(String(raw, Charsets.UTF_8))
        } catch (e: Exception) {
            return HttpServer.Response.text(400, "bad pair request")
        }
        val id = body.optString("id")
        val name = body.optString("name")
        val pubKey = body.optString("pubKey")
        if (id.isEmpty() || pubKey.isEmpty()) return HttpServer.Response.text(400, "bad pair request")
        if (!PairingAuth.acceptPair(RD2MacState.pairedPeer?.id, id))
            return HttpServer.Response.text(403, "already paired with another device")
        val myKeys = RD2Crypto.generateKeyPair()
        val session = try {
            RD2Crypto.agree(myKeys.private, pubKey)
        } catch (e: Exception) {
            return HttpServer.Response.text(400, "bad public key")
        }
        // Hold until the user compares the code in the Activity; then it's saved.
        RD2MacState.pendingPairing = PendingPairing(id, name, session)
        RD2MacState.notifyChange()
        // UI not on screen (onChange only lives between onResume/onPause) → the
        // dialog can't appear; notify so the pairing doesn't strand half-done
        // (Mac saves after its confirm, phone would never see the code).
        if (RD2MacState.onChange == null) showPairingNotification(name)
        val reply = JSONObject()
            .put("id", pairing.deviceId)
            .put("name", deviceDisplayName(this))
            .put("pubKey", RD2Crypto.publicKeyBase64(myKeys.public))
            .toString()
        return HttpServer.Response.json(200, reply)
    }

    /** Streams the item straight from MediaStore to the socket, sealed on the way. */
    private fun serveFile(file: ServedFile?): HttpServer.Response {
        if (file == null) return HttpServer.Response.text(404, "no media found")
        val key = keyBytes()
        val wire = WireBody(key)
        return HttpServer.Response.stream(
            200, file.mime, wire.wireLength(file.length), source = file,
            extra = mapOf(RD2Mac.Header.FILENAME to HeaderCrypt.encode(file.filename, key)),
        ) { out -> wire.write(file.content, file.length, out) }
    }

    /** Streams the body into a pending MediaStore entry, opened on the way; the entry
     *  is published only after the GCM tag checks out. */
    private fun handleReceive(request: HttpServer.Request): HttpServer.Response {
        val name = request.header(RD2Mac.Header.FILENAME)?.let { HeaderCrypt.decode(it, keyBytes()) }
            ?: "rd2mac-file"
        val mime = request.header("Content-Type") ?: "application/octet-stream"
        val wire = WireBody(keyBytes())
        var bytes = 0L
        val saved = try {
            MediaSaver.save(contentResolver, name, mime) { out ->
                bytes = wire.read(request.body, request.contentLength, out)
            }
        } catch (e: GeneralSecurityException) {
            return HttpServer.Response.text(400, "decrypt failed")
        } catch (e: EOFException) {
            return HttpServer.Response.text(400, "body shorter than its Content-Length")
        } ?: return HttpServer.Response.text(500, "save failed")
        // Sealed response: proves to the sender WE hold the pairing key — a bare
        // 200 could be forged by an active MITM that never persisted anything.
        val result = JSONObject().put("saved", saved).put("bytes", bytes).toString()
        return HttpServer.Response.bytes(
            200, "application/octet-stream", encryptBody(result.toByteArray(Charsets.UTF_8)))
    }

    // Body encryption with the paired peer's AES key (no key ⇒ passthrough).
    private fun keyBytes(): ByteArray? = RD2Crypto.keyBytes(RD2MacState.pairedPeer?.key)
    private fun encryptBody(data: ByteArray): ByteArray {
        val key = keyBytes() ?: return data
        return RD2Crypto.seal(data, key)
    }

    private fun identityJson(): String = JSONObject()
        .put("id", pairing.deviceId)
        .put("name", deviceDisplayName(this))
        .put("platform", Platform.ANDROID.wire)
        .put("v", RD2Mac.PROTOCOL_VERSION)
        .toString()

    // MARK: discovery — advertise always (passive; the Mac finds us through it),
    // browse per BrowsePolicy: dormant once the paired Mac is resolved and the
    // pick list isn't on screen. Transfers then run on the last-known address; a
    // connectivity failure invalidates it (onPeerUnreachable), which flips the
    // policy back to browsing until re-resolved.

    private fun startDiscovery() {
        discovery = Discovery(this)
        val txt = mapOf(
            "id" to pairing.deviceId,
            "name" to deviceDisplayName(this),
            "platform" to Platform.ANDROID.wire,
            "v" to "${RD2Mac.PROTOCOL_VERSION}",
        )
        discovery.advertise(deviceDisplayName(this), server.boundPort, txt)
        RD2MacState.evalBrowse = { clipHandler.post { updateBrowse() } }
        updateBrowse()
        (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
            .registerDefaultNetworkCallback(networkCallback)
    }

    /** Start/stop the browse to match the policy. Idempotent and cheap. */
    private fun updateBrowse() {
        val paired = RD2MacState.pairedPeer != null
        val resolved = RD2MacState.peer != null
        val wanted = BrowsePolicy.shouldBrowse(paired, resolved, RD2MacState.uiVisible)
        Log.i(DISCOVERY_TAG,
            "browse eval: paired=$paired resolved=$resolved ui=${RD2MacState.uiVisible} → ${if (wanted) "on" else "dormant"}")
        if (wanted) {
            discovery.browse(excludePlatform = Platform.ANDROID) { peers ->
                RD2MacState.livePeers = peers
                RD2MacState.notifyChange()
                val active = RD2MacState.peer
                updateNotification(if (active != null) getString(R.string.notif_mac, active.name)
                                   else getString(R.string.notif_searching))
                clipHandler.post { updateBrowse() }   // resolved → maybe go dormant
            }
        } else {
            discovery.stopBrowse()
        }
    }

    /** A request to the paired Mac failed at the connectivity level: its cached
     *  address is no longer good (IP change / Mac gone). Drop it — the UI shows
     *  "searching" and the browse policy wakes to re-resolve. Runs off the
     *  network executor, so hop to the main thread. */
    private fun onPeerUnreachable() {
        clipHandler.post {
            val peer = RD2MacState.peer ?: return@post
            RD2MacState.livePeers = discovery.forget(peer.name)
            RD2MacState.notifyChange()
            updateNotification(getString(R.string.notif_searching))
            // NSD never re-emits "found" for a service that didn't blink — a
            // browse that's already running would stay blind to the peer we
            // just dropped. Restart it so the fresh query re-fires the events.
            discovery.stopBrowse()
            updateBrowse()
        }
    }

    /** Real network replacement (Wi-Fi → other Wi-Fi / cell): every cached
     *  address is suspect — re-resolve. The gate skips the immediate callback
     *  for the network we're already on. */
    private val netGate = net.simioni.rd2mac.net.NetworkChangeGate()
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (netGate.isChange(network)) onPeerUnreachable()
        }
    }


    // MARK: notification

    private fun startAsForeground(status: String) {
        val notification = buildNotification(status)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // specialUse: a persistent LAN server has no dataSync-style timeout.
            startForeground(Notifications.SERVICE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifications.SERVICE_ID, notification)
        }
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(Notifications.SERVICE_ID, buildNotification(status))
    }

    private fun buildNotification(status: String): Notification =
        NotificationCompat.Builder(this, Notifications.SERVICE_CHANNEL)
            .setContentTitle("RD2Mac")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_stat_rd2)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    /** Mac initiated pairing while the UI is closed: surface it, or the Mac ends
     *  up paired one-sided (401 on every transfer) with no clue on the phone. */
    private fun showPairingNotification(peerName: String) {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(Notifications.PAIRING_ID, NotificationCompat.Builder(this, Notifications.PAIRING_CHANNEL)
            .setContentTitle(getString(R.string.notif_pair_title))
            .setContentText(getString(R.string.notif_pair_body, peerName))
            .setSmallIcon(R.drawable.ic_stat_rd2)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build())
    }

    companion object {
        private const val CLIP_TAG = "RD2Mac.Clip"
        private const val DISCOVERY_TAG = "RD2Mac.Discovery"
        private const val CLIP_POLL_MS = 1500L
        private const val ACTION_SET_CLIPBOARD = "net.simioni.rd2mac.SET_CLIPBOARD"
        private const val ACTION_SET_PHOTO_LOCATION = "net.simioni.rd2mac.SET_PHOTO_LOCATION"
        private const val ACTION_SET_PROXIMITY = "net.simioni.rd2mac.SET_PROXIMITY"
        private const val EXTRA_ON = "on"
        private const val EXTRA_RSSI = "rssi"

        fun start(context: Context) {
            startService(context, Intent(context, RD2MacService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RD2MacService::class.java))
        }

        /** Toggle clipboard receiving; the service persists it and starts/stops polling. */
        fun setClipboardReceive(context: Context, on: Boolean) {
            startService(context, Intent(context, RD2MacService::class.java)
                .setAction(ACTION_SET_CLIPBOARD).putExtra(EXTRA_ON, on))
        }

        /** Toggle sending photos with their GPS Exif tags (permission asked by the UI). */
        fun setKeepPhotoLocation(context: Context, on: Boolean) {
            startService(context, Intent(context, RD2MacService::class.java)
                .setAction(ACTION_SET_PHOTO_LOCATION).putExtra(EXTRA_ON, on))
        }

        /** Toggle/configure the proximity gate (threshold in dBm). */
        fun setProximity(context: Context, on: Boolean, rssi: Int) {
            startService(context, Intent(context, RD2MacService::class.java)
                .setAction(ACTION_SET_PROXIMITY).putExtra(EXTRA_ON, on).putExtra(EXTRA_RSSI, rssi))
        }

        /** MainActivity on/off screen — its pick list needs the browse live;
         *  leaving re-evaluates whether the browse can go dormant. Direct state,
         *  not a service intent: startService from onPause (app leaving the
         *  foreground) can be silently dropped by the platform. */
        fun notifyUiVisible(visible: Boolean) {
            RD2MacState.uiVisible = visible
            RD2MacState.evalBrowse?.invoke()
        }

        private fun startService(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
