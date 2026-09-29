package net.simioni.rd2mac

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import net.simioni.rd2mac.media.MediaLocationPolicy
import net.simioni.rd2mac.net.PairClient
import net.simioni.rd2mac.net.PairingStore
import net.simioni.rd2mac.net.ProximityLevels
import net.simioni.rd2mac.net.RD2Crypto
import net.simioni.rd2mac.net.ResolvedPeer

/**
 * Home screen: shows service status, lets the user pick which Mac to pair with
 * (running the handshake so both sides share a secret token), and remembers the
 * choice. Follows the system light/dark theme (Material 3 DayNight).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var pairing: PairingStore
    private lateinit var statusView: TextView
    private lateinit var macList: LinearLayout
    private lateinit var emptyState: TextView
    private lateinit var forgetButton: MaterialButton
    private lateinit var clipboardSwitch: MaterialSwitch
    private lateinit var photoLocationSwitch: MaterialSwitch
    private lateinit var photoLocationHint: TextView
    private lateinit var keepAliveSwitch: MaterialSwitch
    private lateinit var keepAliveHint: TextView
    private lateinit var proximitySwitch: MaterialSwitch
    private lateinit var proximityLevels: android.widget.RadioGroup
    private lateinit var proximityStatus: TextView
    /** The one SAS dialog on screen; a newer pairing supersedes it (its session
     *  is already stale — confirming it would save a key the Mac no longer has). */
    private var sasDialog: androidx.appcompat.app.AlertDialog? = null
    private val connectedGreen = Color.parseColor("#2E9E5B")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        pairing = PairingStore(this)
        statusView = findViewById(R.id.statusView)
        macList = findViewById(R.id.macList)
        emptyState = findViewById(R.id.emptyState)
        forgetButton = findViewById(R.id.forgetButton)
        forgetButton.setOnClickListener { forget() }
        clipboardSwitch = findViewById(R.id.clipboardSwitch)
        clipboardSwitch.setOnClickListener {
            RD2MacService.setClipboardReceive(this, clipboardSwitch.isChecked)
        }

        photoLocationSwitch = findViewById(R.id.photoLocationSwitch)
        photoLocationHint = findViewById(R.id.photoLocationHint)
        photoLocationSwitch.setOnClickListener { onPhotoLocationToggled() }
        keepAliveSwitch = findViewById(R.id.keepAliveSwitch)
        keepAliveHint = findViewById(R.id.keepAliveHint)
        keepAliveSwitch.setOnClickListener { onKeepAliveToggled() }

        proximitySwitch = findViewById(R.id.proximitySwitch)
        proximitySwitch.setOnClickListener { onProximityToggled() }
        proximityLevels = findViewById(R.id.proximityLevels)
        proximityLevels.setOnCheckedChangeListener { _, _ ->
            // Only a real user change reaches the service — render() also checks
            // the persisted level programmatically, and re-applying that reset
            // the BLE monitor (gate back to "far") on every screen open.
            if (RD2MacState.proximityEnabled && selectedRssi() != pairing.proximityRssi) {
                applyProximity(true)
            }
        }
        proximityStatus = findViewById(R.id.proximityStatus)

        requestNeededPermissions()
        RD2MacService.start(this)
    }

    // MARK: photo location

    /** Turning it on needs ACCESS_MEDIA_LOCATION: without it MediaProvider serves a
     *  redacted copy of every photo and the coordinates are gone before we read it. */
    private fun onPhotoLocationToggled() {
        val on = photoLocationSwitch.isChecked
        if (on && checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_MEDIA_LOCATION), REQ_MEDIA_LOC)
            return   // applied (or reverted) in onRequestPermissionsResult
        }
        RD2MacService.setKeepPhotoLocation(this, on)
    }

    // MARK: keep running

    /** The system decides this one, so the switch just opens the right dialog and
     *  `render()` reads back the real state — there is no app-side flag to keep. */
    private fun onKeepAliveToggled() {
        val power = getSystemService(POWER_SERVICE) as android.os.PowerManager
        val exempt = power.isIgnoringBatteryOptimizations(packageName)
        keepAliveSwitch.isChecked = exempt      // the dialog's answer is what counts
        if (exempt) {
            // Only the user can take the exemption back — send them where it lives.
            Toast.makeText(this, getString(R.string.keep_alive_off_hint), Toast.LENGTH_LONG).show()
            startActivity(android.content.Intent(
                android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } else {
            startActivity(android.content.Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:$packageName")))
        }
    }

    // MARK: proximity gate

    /** Turning the gate on needs BLUETOOTH_SCAN; denied → the gate would silently
     *  block all sync forever, so fail safe: revert the switch. */
    private fun onProximityToggled() {
        val on = proximitySwitch.isChecked
        if (on && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_SCAN), REQ_BT)
            return   // applied (or reverted) in onRequestPermissionsResult
        }
        applyProximity(on)
    }

    private fun applyProximity(on: Boolean) {
        RD2MacService.setProximity(this, on, selectedRssi())
    }

    private fun selectedRssi(): Int = when (proximityLevels.checkedRadioButtonId) {
        R.id.proxDesk -> ProximityLevels.DESK
        R.id.proxHouse -> ProximityLevels.HOUSE
        else -> ProximityLevels.ROOM
    }

    private fun checkedIdFor(rssi: Int): Int = when {
        rssi >= ProximityLevels.DESK -> R.id.proxDesk
        rssi <= ProximityLevels.HOUSE -> R.id.proxHouse
        else -> R.id.proxRoom
    }

    override fun onResume() {
        super.onResume()
        RD2MacState.onChange = { runOnUiThread { render() } }
        RD2MacService.notifyUiVisible(true)    // pick list needs a live browse
        render()
    }

    override fun onPause() {
        super.onPause()
        RD2MacState.onChange = null
        RD2MacService.notifyUiVisible(false)   // browse may go dormant again
    }

    private fun render() {
        val paired = RD2MacState.pairedPeer
        val active = RD2MacState.peer
        val sb = SpannableStringBuilder()
        sb.append(getString(if (RD2MacState.running) R.string.service_on else R.string.service_off))
        if (RD2MacState.running && paired?.key?.isNotEmpty() == true) {
            sb.append(" — ")
            sb.appendLockIcon()
            sb.appendColored(" " + getString(R.string.status_encrypted), connectedGreen)
        }
        sb.append("\n\n")
        when {
            paired == null -> sb.appendColored(getString(R.string.status_no_mac), mutedColor())
            active != null -> {
                sb.append(getString(R.string.status_mac, paired.name)).append("\n")
                sb.appendColored(getString(R.string.status_connected), connectedGreen)
            }
            else -> {
                sb.append(getString(R.string.status_mac, paired.name)).append("\n")
                sb.appendColored(getString(R.string.status_searching), mutedColor())
            }
        }
        statusView.text = sb

        val peers = RD2MacState.livePeers.sortedBy { it.name.lowercase() }
        macList.removeAllViews()
        for (peer in peers) macList.addView(macRow(peer, isPaired = peer.id == paired?.id))
        emptyState.visibility = if (peers.isEmpty()) View.VISIBLE else View.GONE
        forgetButton.visibility = if (paired != null) View.VISIBLE else View.GONE
        clipboardSwitch.isChecked = RD2MacState.receiveClipboard
        // Both of these can change outside the app (permission revoked in Settings,
        // battery exemption granted in a system dialog) — read the truth every time.
        val keepLocation = MediaLocationPolicy.effective(
            switchOn = pairing.keepPhotoLocation,
            permissionGranted = checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION)
                == PackageManager.PERMISSION_GRANTED)
        photoLocationSwitch.isChecked = keepLocation
        // Off doesn't mean "no location": Android strips it from the formats it can
        // parse (JPEG) and leaves it in the ones it can't (HEIC). Say so, or the
        // switch promises a control it doesn't have.
        photoLocationHint.visibility = if (keepLocation) View.GONE else View.VISIBLE
        val exempt = (getSystemService(POWER_SERVICE) as android.os.PowerManager)
            .isIgnoringBatteryOptimizations(packageName)
        keepAliveSwitch.isChecked = exempt
        keepAliveHint.visibility = if (exempt) View.GONE else View.VISIBLE

        val proxOn = RD2MacState.proximityEnabled
        proximitySwitch.isChecked = proxOn
        proximityLevels.visibility = if (proxOn) View.VISIBLE else View.GONE
        proximityStatus.visibility = proximityLevels.visibility
        if (proxOn) {
            if (proximityLevels.checkedRadioButtonId == View.NO_ID) {
                proximityLevels.check(checkedIdFor(pairing.proximityRssi))
            }
            if (RD2MacState.proximityNear) {
                proximityStatus.text = getString(R.string.prox_near)
                proximityStatus.setTextColor(connectedGreen)
            } else {
                proximityStatus.text = getString(R.string.prox_far)
                proximityStatus.setTextColor(mutedColor())
            }
        }

        // If the Mac initiated pairing, prompt to compare the code (consume once).
        RD2MacState.pendingPairing?.let { p ->
            RD2MacState.pendingPairing = null
            (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .cancel(Notifications.PAIRING_ID)
            showSasDialog(p.session.sas, p.name) {
                pairing.pair(p.id, p.name, p.session)
                RD2MacState.pairedPeer = pairing.pairedPeer
                RD2MacState.notifyChange()
                render()
            }
        }
    }

    private fun macRow(peer: ResolvedPeer, isPaired: Boolean): View {
        val row = LayoutInflater.from(this).inflate(R.layout.item_mac, macList, false)
        row.findViewById<TextView>(R.id.macName).text = peer.name
        row.findViewById<TextView>(R.id.macCheck).visibility = if (isPaired) View.VISIBLE else View.GONE
        row.setOnClickListener { pair(peer) }
        return row
    }

    /** Pairing handshake: exchange ECDH public keys, then show the 6-digit code for
     *  the user to compare with the Mac before saving (guards against MITM). */
    private fun pair(peer: ResolvedPeer) {
        val myKeys = RD2Crypto.generateKeyPair()
        val myPub = RD2Crypto.publicKeyBase64(myKeys.public)
        Thread {
            val peerPub = PairClient.pair(peer, pairing.deviceId, deviceDisplayName(this), myPub)
            val session = peerPub?.let { runCatching { RD2Crypto.agree(myKeys.private, it) }.getOrNull() }
            runOnUiThread {
                if (session == null) {
                    Toast.makeText(this, getString(R.string.already_paired), Toast.LENGTH_LONG).show()
                    render()
                } else {
                    showSasDialog(session.sas, peer.name) {
                        pairing.pair(peer, session)
                        RD2MacState.pairedPeer = pairing.pairedPeer
                        RD2MacState.notifyChange()
                        render()
                    }
                }
            }
        }.start()
    }

    /** Show the pairing code; run [onConfirm] only if the user confirms it matches.
     *  Dismisses any SAS dialog already on screen: a newer handshake replaced its
     *  session, so confirming the old code would persist a dead key (401s). */
    private fun showSasDialog(sas: String, peerName: String, onConfirm: () -> Unit) {
        sasDialog?.dismiss()
        sasDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.sas_title, peerName))
            .setMessage(getString(R.string.sas_body, sas))
            .setPositiveButton(getString(R.string.sas_confirm)) { _, _ -> onConfirm() }
            .setNegativeButton(getString(R.string.sas_cancel), null)
            .setCancelable(false)
            .show()
    }

    private fun forget() {
        pairing.forget()
        RD2MacState.pairedPeer = null
        RD2MacState.notifyChange()
        render()
    }

    private fun SpannableStringBuilder.appendColored(text: String, color: Int) {
        val start = length
        append(text)
        setSpan(ForegroundColorSpan(color), start, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** Inline green padlock, sized to the status text (E2E-encrypted indicator). */
    private fun SpannableStringBuilder.appendLockIcon() {
        val icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_lock)?.mutate() ?: return
        icon.setTint(connectedGreen)
        val size = (statusView.textSize * 1.05f).toInt()
        icon.setBounds(0, 0, size, size)
        val start = length
        append("￼")
        setSpan(ImageSpan(icon, ImageSpan.ALIGN_CENTER), start, length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** Muted (greyed-out) text color from the theme — works in light & dark. */
    private fun mutedColor(): Int {
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.textColorSecondary, tv, true)
        return if (tv.resourceId != 0) ContextCompat.getColor(this, tv.resourceId) else tv.data
    }

    private fun requestNeededPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val wanted = listOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        val toRequest = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (toRequest.isNotEmpty()) requestPermissions(toRequest.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>,
                                            grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_PERMS -> {
                // Media denied → the Mac's pull-screenshot/pull-media silently finds
                // nothing; say it once instead of leaving a mystery.
                val mediaDenied = permissions.zip(grantResults.toTypedArray()).any { (perm, result) ->
                    perm != Manifest.permission.POST_NOTIFICATIONS &&
                        result != PackageManager.PERMISSION_GRANTED
                }
                if (mediaDenied) {
                    Toast.makeText(this, getString(R.string.perm_media_denied), Toast.LENGTH_LONG).show()
                }
            }
            REQ_MEDIA_LOC -> {
                val granted = grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    RD2MacService.setKeepPhotoLocation(this, true)
                } else {
                    photoLocationSwitch.isChecked = false
                    Toast.makeText(this, getString(R.string.photo_location_denied),
                        Toast.LENGTH_LONG).show()
                }
                render()
            }
            REQ_BT -> {
                val granted = grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    applyProximity(true)
                } else {
                    proximitySwitch.isChecked = false   // fail safe (see onProximityToggled)
                    Toast.makeText(this, getString(R.string.prox_perm_denied), Toast.LENGTH_LONG).show()
                }
                render()
            }
        }
    }

    companion object {
        private const val REQ_PERMS = 1
        private const val REQ_BT = 2
        private const val REQ_MEDIA_LOC = 3
    }
}
