package net.simioni.rd2mac.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.util.Log
import net.simioni.rd2mac.BuildConfig
import net.simioni.rd2mac.net.RD2Http
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Self-update, checked once a day against the public GitHub release (Eduardo,
 * 01/10/2026) — the phone's half of the Mac's `Updater`. A newer APK is downloaded,
 * checked against GitHub's size and digest and against our own package, and installed
 * once no transfer has moved for a while.
 *
 * Android 12+ lets an app update ITSELF without asking (USER_ACTION_NOT_REQUIRED), but
 * only once the user has allowed it to "Install unknown apps". Until then the system
 * asks — through a notification ([UpdateInstallReceiver]), since an app in the
 * background can't open the prompt itself. The install kills this process; the
 * service comes back through MY_PACKAGE_REPLACED.
 */
object AppUpdater {
    const val TAG = "RD2Mac.Update"
    private const val PREFS = "rd2mac_update"
    private const val PREF_LAST_CHECK = "last_check"
    /** A release whose APK turned out unusable here (not a newer build of us). */
    private const val PREF_REJECTED = "rejected_version"
    private const val TICK_MINUTES = 60L
    private const val QUIET_RETRY_SECONDS = 60L

    private val executor = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var started = false

    // Touched only on the executor's thread.
    private var staged: StagedApk? = null
    private var waitingForQuiet = false

    private class StagedApk(val file: File, val version: String)

    /** Idempotent: the service calls it on every start. */
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        executor.scheduleWithFixedDelay({ guarded { tick(app) } }, 1, TICK_MINUTES, TimeUnit.MINUTES)
    }

    /** An exception escaping a scheduled task would cancel every later tick. */
    private fun guarded(work: () -> Unit) = try {
        work()
    } catch (e: Exception) {
        Log.w(TAG, "update check failed", e)
    }

    private fun tick(context: Context) {
        staged?.let { installWhenQuiet(context, it); return }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(PREF_LAST_CHECK, -1L).takeIf { it >= 0 }
        if (!UpdateSchedule.isDue(last, System.currentTimeMillis())) return
        // An 11 MB APK never goes over mobile data: the hourly tick tries again on Wi-Fi.
        if (context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered) return
        val release = fetchRelease() ?: return
        prefs.edit().putLong(PREF_LAST_CHECK, System.currentTimeMillis()).apply()
        val installed = installedPackage(context)
        if (!UpdatePolicy.wants(release.version, installed.versionName ?: "0",
                                prefs.getString(PREF_REJECTED, null))) return
        val apk = download(context, release) ?: return
        if (!isNewerBuildOfUs(context, apk, installed.longVersionCode)) {
            Log.w(TAG, "${release.version}: the APK is not a newer build of this app")
            prefs.edit().putString(PREF_REJECTED, release.version).apply()   // final for this release
            apk.delete()
            return
        }
        staged = StagedApk(apk, release.version).also { installWhenQuiet(context, it) }
    }

    private fun fetchRelease(): Release? {
        val request = Request.Builder().url(BuildConfig.UPDATE_FEED_URL)
            .header("Accept", "application/vnd.github+json").build()
        return RD2Http.execute(request) { response ->
            if (response.isSuccessful) UpdateFeed.parse(response.body?.string().orEmpty(), UpdateFeed.ANDROID_ASSET)
            else null
        }
    }

    /** The APK, kept only when its size and digest match what the release declares. */
    private fun download(context: Context, release: Release): File? {
        val dir = File(context.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
        val apk = File(dir, UpdateFeed.ANDROID_ASSET)
        val saved = RD2Http.execute(Request.Builder().url(release.assetUrl).build()) { response ->
            if (!response.isSuccessful) return@execute false
            response.body?.byteStream()?.use { input -> apk.outputStream().use { input.copyTo(it) } } != null
        } ?: false
        val rejected = when {
            !saved -> "download failed"
            apk.length() != release.size -> "${apk.length()} B, expected ${release.size}"
            release.sha256 != null && FileDigest.sha256(apk) != release.sha256 -> "SHA-256 differs from the release's"
            else -> null
        }
        if (rejected != null) {
            Log.w(TAG, "${release.version}: download rejected — $rejected")
            apk.delete()
            return null
        }
        return apk
    }

    /** Same package, higher version code. The signature Android checks itself at install:
     *  only a build signed with our key can replace us. */
    private fun isNewerBuildOfUs(context: Context, apk: File, installedCode: Long): Boolean {
        val info = context.packageManager.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(0))
            ?: return false
        return info.packageName == context.packageName && info.longVersionCode > installedCode
    }

    private fun installedPackage(context: Context): PackageInfo =
        context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))

    private fun installWhenQuiet(context: Context, apk: StagedApk) {
        if (waitingForQuiet) return
        if (!TransferActivity.shared.isQuiet) {
            waitingForQuiet = true
            executor.schedule({
                waitingForQuiet = false
                guarded { installWhenQuiet(context, apk) }
            }, QUIET_RETRY_SECONDS, TimeUnit.SECONDS)
            return
        }
        staged = null
        install(context, apk)
    }

    private fun install(context: Context, apk: StagedApk) {
        val installer = context.packageManager.packageInstaller
        // A confirmation the user never tapped (yesterday's update) gives way to this one.
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            setSize(apk.file.length())
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.file.length()).use { out ->
                apk.file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val result = Intent(context, UpdateInstallReceiver::class.java)
                .putExtra(UpdateInstallReceiver.EXTRA_VERSION, apk.version)
            // Mutable: the installer fills in the status extras.
            val callback = PendingIntent.getBroadcast(context, sessionId, result,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(callback.intentSender)
        }
        Log.i(TAG, "update ${apk.version} committed (session $sessionId)")
    }
}

object FileDigest {
    /** Lowercase hex SHA-256 of [file], read in chunks. */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
