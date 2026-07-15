package net.simioni.rd2mac.net

import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
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
 * fluke) doesn't flap the state to "far".
 *
 * [onEvaluate] fires on the main thread every [EVAL_MS] with the current state,
 * driving the UI and the Mac status reports — including the decay to "far" when
 * the beacon goes silent, which no scan callback would ever deliver.
 */
class ProximityMonitor(
    context: Context,
    private val gate: ProximityGate,
    private val onEvaluate: (near: Boolean) -> Unit = {},
) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var scanning = false
    private var running = false

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // Raw readings at debug level: threshold calibration material
            // (`adb logcat -s RD2Mac.Proximity:V` while walking around).
            Log.d(TAG, "rssi=${result.rssi}")
            gate.onReading(result.rssi, System.currentTimeMillis())
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
            startScanBurst()
            handler.postDelayed(burstStop, BURST_MS)
            handler.postDelayed(this, CYCLE_MS)
        }
    }
    private val burstStop = Runnable { stopScanNow() }
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
        handler.post(burstStart)
        handler.post(evaluate)
    }

    fun stop() {
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
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid.fromString(RD2Mac.BLE_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)   // continuous RX, but only for the burst
            .build()
        try {
            scanner.startScan(listOf(filter), settings, callback)
            scanning = true
        } catch (e: SecurityException) {
            Log.w(TAG, "BLE scan not permitted", e)
        }
    }

    private fun stopScanNow() {
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
