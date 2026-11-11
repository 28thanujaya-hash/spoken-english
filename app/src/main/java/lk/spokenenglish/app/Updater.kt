package lk.spokenenglish.app

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * In-app updates. Every GitHub build publishes the APK as the latest release of the public repo
 * BuildConfig.UPDATE_REPO (tag "v<versionCode>", file "spoken-english.apk"). The app checks it,
 * downloads a newer one in the background and installs it when he is not in the middle of talking.
 *
 * Android 12+: after the first update installed this way, later updates install without asking
 * (the app is then its own installer). The very first one may show Android's "Update?" box.
 */
class Updater(private val activity: Activity, private val note: (String) -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private val prefs = activity.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private var checking = false
    private var ready: File? = null
    private var readyCode = 0
    private var installing = false
    private var askedPermission = false

    /** Called when a newer version has been downloaded. */
    var onReady: (() -> Unit)? = null

    /** Checks at most every 3 hours unless forced (app start). */
    fun check(force: Boolean = false) {
        if (checking || ready != null || BuildConfig.UPDATE_REPO.isBlank()) return
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("updateCheckedAt", 0L) < 3 * 3600_000L) return
        checking = true
        thread {
            val result = runCatching { findAndDownload() }
            main.post {
                checking = false
                prefs.edit().putLong("updateCheckedAt", System.currentTimeMillis())
                    .putString("updateLast", result.exceptionOrNull()?.message ?: (if (ready != null) "found build $readyCode" else "up to date"))
                    .apply()
            }
        }
    }

    /** Installs a downloaded update. Call when he is idle (not talking, no reply pending). */
    fun installIfReady() {
        val apk = ready ?: return
        if (installing) return
        if (!activity.packageManager.canRequestPackageInstalls()) {
            if (!askedPermission) {
                askedPermission = true
                android.app.AlertDialog.Builder(activity)
                    .setTitle("Allow app updates")
                    .setMessage("A new version is ready. To let the app update itself, turn on \"Allow from this source\" on the next screen, then come back. You only do this once.")
                    .setPositiveButton("Open settings") { _, _ ->
                        runCatching {
                            activity.startActivity(
                                Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    android.net.Uri.parse("package:" + activity.packageName))
                            )
                        }
                    }
                    .setNegativeButton("Later", null)
                    .show()
            }
            return
        }
        installing = true
        note("A new version is ready. Updating now: the app will close for a moment. Open it again afterwards.")
        main.postDelayed({ install(apk) }, 1500)
    }

    /** Shows "Updated" once after a new version starts. */
    fun greetAfterUpdate() {
        val last = prefs.getInt("lastRunVersion", 0)
        if (last != 0 && last < BuildConfig.VERSION_CODE) note("Updated to ${BuildConfig.VERSION_NAME}.")
        prefs.edit().putInt("lastRunVersion", BuildConfig.VERSION_CODE).apply()
    }

    private fun findAndDownload() {
        val api = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
        val con = (api.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000; readTimeout = 15000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        val body = try {
            if (con.responseCode != 200) throw Exception("update check HTTP ${con.responseCode}")
            con.inputStream.bufferedReader().use { it.readText() }
        } finally { con.disconnect() }

        val j = JSONObject(body)
        val code = j.optString("tag_name").removePrefix("v").toIntOrNull() ?: return
        if (code <= BuildConfig.VERSION_CODE) return
        val assets = j.optJSONArray("assets") ?: return
        var url: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url"); break }
        }
        if (url.isNullOrBlank()) return

        val file = File(activity.cacheDir, "update-$code.apk")
        activity.cacheDir.listFiles()?.filter { it.name.startsWith("update-") && it != file }?.forEach { it.delete() }
        if (!file.exists() || file.length() == 0L) {
            val tmp = File(activity.cacheDir, "update-$code.part")
            val dl = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000; readTimeout = 60000; instanceFollowRedirects = true
            }
            try {
                if (dl.responseCode != 200) throw Exception("download HTTP ${dl.responseCode}")
                dl.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            } finally { dl.disconnect() }
            if (!tmp.renameTo(file)) throw Exception("could not save the update")
        }
        main.post { ready = file; readyCode = code; onReady?.invoke() }
    }

    private fun install(apk: File) {
        try {
            val installer = activity.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(activity.packageName)
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("app.apk", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pi = PendingIntent.getBroadcast(activity, id, Intent(activity, UpdateReceiver::class.java), flags)
                session.commit(pi.intentSender)
            }
        } catch (e: Exception) {
            installing = false
            prefs.edit().putString("updateLast", "install failed: ${e.message}").apply()
            note("The update couldn't install this time. It will try again later.")
        }
    }
}

/** Receives the installer's answer. If Android wants a confirmation, shows its "Update?" box. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirm) }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> prefs.edit().putString("updateLast", "installed").apply()
            else -> prefs.edit().putString(
                "updateLast",
                "install status $status: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}"
            ).apply()
        }
    }
}
