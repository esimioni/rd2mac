package net.simioni.rd2mac.net

import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import net.simioni.rd2mac.RD2Mac
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The three UI proximity levels (dBm), calibrated against real home
 * measurements (2026-07-10): desk ≈ -40; bathroom behind a thick wall +
 * closet ≈ -67/-69; so the physical gap that exists is desk-vs-everything
 * (~25 dB) — DESK must maintain strictly above the through-wall floor, while
 * ROOM/HOUSE deliberately read "somewhere at home" vs "away".
 */
object ProximityLevels {
    const val DESK = -50
    const val ROOM = -75
    const val HOUSE = -90

    /** DESK's whole point is separating the desk (-40) from the next room
     *  (-68) — the default 8 dB hysteresis would bridge that gap. */
    fun hysteresisFor(nearRssi: Int): Int = if (nearRssi >= DESK) 4 else 8
}

/**
 * Pure near/far decision from BLE RSSI readings of the Mac's beacon. RSSI is
 * noisy, so the two directions are asymmetric:
 *  - ENTERING "near" takes [armCount] readings at/above the threshold within
 *    [armWindowMs] (one scan burst) — a lone multipath spike through a closed
 *    door re-armed "near" with the phone genuinely away (seen live 2026-07-10);
 *  - STAYING near is lenient: any reading within [hysteresis] dB below the
 *    threshold extends the TTL (no flapping at the edge);
 *  - silence (Mac off / out of range) decays to "far" when [ttlMs] expires.
 * Unit-tested without Android.
 */
class ProximityGate(
    private val nearRssi: Int,
    private val ttlMs: Long = 90_000L,
    private val hysteresis: Int = 8,
    private val armCount: Int = 3,
    private val armWindowMs: Long = 6_000L,
) {
    @Volatile private var nearUntil = 0L
    /** Timestamps of at-threshold readings while far (pruned to the window). */
    private val strongAt = ArrayDeque<Long>()

    /** Feed one scan reading; returns the current near/far state. */
    fun onReading(rssi: Int, nowMs: Long): Boolean {
        if (isNear(nowMs)) {
            if (rssi >= nearRssi - hysteresis) nearUntil = nowMs + ttlMs
        } else if (rssi >= nearRssi) {
            strongAt.addLast(nowMs)
            while (strongAt.isNotEmpty() && nowMs - strongAt.first() > armWindowMs) {
                strongAt.removeFirst()
            }
            if (strongAt.size >= armCount) {
                strongAt.clear()
                nearUntil = nowMs + ttlMs
            }
        }
        return isNear(nowMs)
    }

    fun isNear(nowMs: Long): Boolean = nowMs < nearUntil

    /** True when the reading fed at [nowMs] renewed "near" — armed it, or
     *  extended its TTL. The rest of that scan burst can't tell the gate anything
     *  new, so the monitor stops listening there ([BurstPolicy]). */
    fun renewedAt(nowMs: Long): Boolean = nearUntil == nowMs + ttlMs
}

/**
 * Pure decision of when the phone tells the Mac its gate status (menu display):
 * every state change immediately, plus a keepalive while enabled so the Mac can
 * expire the row once reports stop (phone gone / service dead). "Gate off" is
 * reported once — a disabled gate must not keep chatting. Unit-tested.
 */
class ProximityReporter(private val keepaliveMs: Long = 60_000L) {
    private var lastEnabled: Boolean? = null
    private var lastNear = false
    private var lastSentAt = 0L

    fun shouldReport(enabled: Boolean, near: Boolean, nowMs: Long): Boolean {
        val changed = lastEnabled != enabled || (enabled && lastNear != near)
        val keepalive = enabled && nowMs - lastSentAt >= keepaliveMs
        if (!changed && !keepalive) return false
        lastEnabled = enabled
        lastNear = near
        lastSentAt = nowMs
        return true
    }
}

/**
 * Which beacon the phone listens for: its own pairing's ([RD2Crypto.beaconUuid]),
 * which the Mac rotates at 00:00 UTC. Every Mac used to advertise one shared UUID,
 * so a second RD2Mac Mac in the house counted as "my Mac" (2026-10-22). Pure;
 * mirror of the Swift `ProximityBeacon`.
 */
object ProximityBeacon {
    private const val DAY_MS = 86_400_000L

    fun day(epochMs: Long): Long = Math.floorDiv(epochMs, DAY_MS)

    /** Yesterday's, today's and tomorrow's: the two clocks needn't agree to the second. */
    fun uuidsAround(keyBytes: ByteArray, nowMs: Long): List<String> =
        (-1L..1L).map { RD2Crypto.beaconUuid(keyBytes, day(nowMs) + it) }
}

/**
 * When the monitor spends scan results. Samsung's Bluetooth stack counts the
 * results each app receives with the screen OFF (1/4/8 h windows) and Device
 * care lists the app as a battery hog past its limit, offering to restrict it —
 * which would stop the service from ever restarting. A 6 s LOW_LATENCY burst
 * hears the Mac's beacon ~25 times: 737 results/h overnight (2026-10-22). So:
 *  - a burst stops at the first reading that renews the gate (1 result, not ~25);
 *  - screen off + near: keep scanning — "near" stays alive while the phone sits
 *    dark on the desk, and the gate still notices it leave;
 *  - screen off + far: don't, and stop a burst already running when the screen
 *    goes dark — arming "near" for a phone nobody is looking at buys nothing;
 *    the screen coming on scans at once instead (a kick).
 * Pure, unit-tested.
 */
object BurstPolicy {
    /** Minimum gap between scan starts a kick respects: Android fails a scan
     *  after 5 starts in 30 s (SCAN_FAILED_SCANNING_TOO_FREQUENTLY, no retry). */
    const val MIN_START_GAP_MS = 10_000L

    fun shouldScan(screenOn: Boolean, near: Boolean): Boolean = screenOn || near

    /** Screen just came on: look for the Mac now instead of at the next cycle. */
    fun shouldKick(near: Boolean, sinceLastStartMs: Long): Boolean =
        !near && sinceLastStartMs >= MIN_START_GAP_MS
}

/**
 * Wires [ProximityGate] to the Mac's BLE beacon in short LOW_LATENCY *bursts*
 * ([BURST_MS] every [CYCLE_MS]) instead of a continuous scan:
 *  - the BLE radio is silent ~90% of the time, so it doesn't sit on the antenna
 *    path it shares with Wi-Fi (a continuous scan measurably degrades Wi-Fi —
 *    multicast/mDNS discovery by other apps is the first casualty);
 *  - one start per cycle can never trip Android's 5-starts-per-30s scan
 *    throttle (SCAN_FAILED_SCANNING_TOO_FREQUENTLY), and a failed/blocked start
 *    simply retries on the next cycle — the old code stopped scanning forever;
 *  - average radio time ≈ the old continuous LOW_POWER scan (10% duty cycle),
 *    so battery is a wash while detection *during* a burst is immediate.
 * The gate's TTL must span at least two cycles so a single missed burst (RF
 * fluke) doesn't flap the state to "far". A burst ends early once the gate has
 * its answer, and a far gate doesn't scan while the screen is off ([BurstPolicy]).
 *
 * [onEvaluate] fires on the main thread every [EVAL_MS] with the current state,
 * driving the UI and the Mac status reports — including the decay to "far" when
 * the beacon goes silent, which no scan callback would ever deliver.
 */
class ProximityMonitor(
    context: Context,
    private val gate: ProximityGate,
    /** The paired Mac's beacon UUIDs right now ([ProximityBeacon]); empty = no
     *  pairing key, nothing to listen for. Asked at every burst (day rotation). */
    private val beaconUuids: () -> List<String>,
    private val onEvaluate: (near: Boolean) -> Unit = {},
) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val power = appContext.getSystemService(PowerManager::class.java)
    private var scanning = false
    private var running = false
    /** elapsedRealtime of the last scan start (null: none yet) — paces kicks. */
    private var lastStartAt: Long? = null

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // Raw readings at debug level: threshold calibration material
            // (`adb logcat -s RD2Mac.Proximity:V` while walking around).
            Log.d(TAG, "rssi=${result.rssi}")
            val now = System.currentTimeMillis()
            gate.onReading(result.rssi, now)
            if (gate.renewedAt(now)) stopScanNow()   // answered: more results only cost
        }
        override fun onScanFailed(errorCode: Int) {
            // 6 = throttled (too many starts), 2 = registration failed, … —
            // whatever it was, the next burst is a fresh attempt.
            Log.w(TAG, "BLE scan failed: $errorCode (next burst retries)")
            scanning = false
        }
    }

    private val burstStart = object : Runnable {
        override fun run() {
            if (!running) return
            if (BurstPolicy.shouldScan(power.isInteractive, isNear())) {
                startScanBurst()
                handler.postDelayed(burstStop, BURST_MS)
            }
            handler.postDelayed(this, CYCLE_MS)
        }
    }
    private val burstStop = Runnable { stopScanNow() }

    /** Screen on: a far gate skipped its bursts in the dark — scan now, and
     *  restart the cycle from here. Screen off: a far gate's running burst stops
     *  (one started while the screen was on, or a kick by a notification's
     *  edge lighting, kept collecting dark results: 8–12 each, 2026-10-22). */
    private val screenChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!running) return
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    if (scanning) return
                    val since = lastStartAt?.let { SystemClock.elapsedRealtime() - it } ?: Long.MAX_VALUE
                    if (!BurstPolicy.shouldKick(isNear(), since)) return
                    Log.i(TAG, "screen on, gate far — scanning now")
                    handler.removeCallbacks(burstStart)
                    handler.post(burstStart)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    if (!scanning || BurstPolicy.shouldScan(screenOn = false, near = isNear())) return
                    Log.i(TAG, "screen off, gate far — burst stopped")
                    stopScanNow()
                }
            }
        }
    }
    private val evaluate = object : Runnable {
        override fun run() {
            if (!running) return
            onEvaluate(isNear())
            handler.postDelayed(this, EVAL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        // Screen on/off only reach receivers registered at runtime.
        appContext.registerReceiver(screenChanges,
            IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) },
            Context.RECEIVER_NOT_EXPORTED)
        handler.post(burstStart)
        handler.post(evaluate)
    }

    fun stop() {
        if (running) appContext.unregisterReceiver(screenChanges)
        running = false
        handler.removeCallbacks(burstStart)
        handler.removeCallbacks(burstStop)
        handler.removeCallbacks(evaluate)
        stopScanNow()
    }

    fun isNear(): Boolean = gate.isNear(System.currentTimeMillis())

    /** Requires BLUETOOTH_SCAN (checked by the UI before enabling the gate). A
     *  SecurityException or an off adapter just skips this burst. */
    private fun startScanBurst() {
        if (scanning) return
        val scanner = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
            .adapter?.bluetoothLeScanner
        if (scanner == null) {
            Log.w(TAG, "BLE unavailable (Bluetooth off?) — next burst retries")
            return
        }
        val filters = beaconUuids().map {
            ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(it)).build()
        }
        if (filters.isEmpty()) {
            Log.w(TAG, "no pairing key — no beacon to listen for")
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)   // continuous RX, but only for the burst
            .build()
        try {
            scanner.startScan(filters, settings, callback)
            scanning = true
            lastStartAt = SystemClock.elapsedRealtime()
        } catch (e: SecurityException) {
            Log.w(TAG, "BLE scan not permitted", e)
        }
    }

    private fun stopScanNow() {
        handler.removeCallbacks(burstStop)
        if (!scanning) return
        scanning = false
        try {
            (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
                .adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "RD2Mac.Proximity"
        const val BURST_MS = 6_000L
        const val CYCLE_MS = 60_000L
        const val EVAL_MS = 5_000L
        /** Gate TTL spanning 2 cycles + margin: one missed burst ≠ "far". */
        const val TTL_MS = 2 * CYCLE_MS + 15_000L
    }
}

/**
 * Reports the gate status to the Mac (`POST /proximity`, token + sealed body
 * like every transfer). Best-effort: a failure is retried by the next keepalive.
 */
object ProximityClient {

    fun report(peer: ResolvedPeer, token: String?, keyBytes: ByteArray?,
               enabled: Boolean, near: Boolean, onError: () -> Unit = {}): Boolean {
        val url = "http://${ClipboardClient.bracketed(peer)}:${peer.port}${RD2Mac.Path.PROXIMITY}"
        val json = """{"enabled":$enabled,"near":$near}""".toByteArray(Charsets.UTF_8)
        val payload = keyBytes?.let { RD2Crypto.seal(json, it) } ?: json
        val builder = Request.Builder().url(url)
            .post(payload.toRequestBody("application/json".toMediaTypeOrNull()))
        if (!token.isNullOrEmpty()) builder.header(RD2Mac.Header.TOKEN, token)
        return RD2Http.execute(builder.build(), onError) { it.code == 200 } ?: false
    }
}
