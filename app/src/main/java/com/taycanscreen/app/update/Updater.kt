package com.taycanscreen.app.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.taycanscreen.app.BuildConfig
import com.taycanscreen.app.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update from the repository's GitHub Releases.
 *
 * The CI workflow tags every build "build-<versionCode>" and attaches the APK as
 * "taycan-screen.apk". We read the latest release, compare its versionCode with ours,
 * download the APK and hand it to PackageInstaller.
 */
class Updater(private val context: Context) {

    data class Release(val versionCode: Int, val tag: String, val apkUrl: String, val notes: String)

    companion object {
        const val APK_NAME = "taycan-screen.apk"
        private const val TAG = "update"
        private const val TAG_PREFIX = "build-"
    }

    val repo: String get() = BuildConfig.GITHUB_REPO

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Intent that opens this app's "Install unknown apps" switch in Settings. */
    fun installPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    suspend fun fetchLatest(): Release = withContext(Dispatchers.IO) {
        require(repo.isNotBlank()) { "This build doesn't know its GitHub repository (local build?)" }
        val url = "https://api.github.com/repos/$repo/releases/latest"
        AppLog.info(TAG, "Checking $url (installed build ${BuildConfig.VERSION_CODE})")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "taycan-screen-app")
        }
        try {
            val code = conn.responseCode
            if (code != 200) {
                val body = conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(300)
                throw IOException("GitHub answered HTTP $code ${body ?: ""}".trim())
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.getString("tag_name")
            val versionCode = tag.removePrefix(TAG_PREFIX).toIntOrNull()
                ?: throw IOException("Unexpected release tag '$tag'")
            val assets = json.getJSONArray("assets")
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name") == APK_NAME) apkUrl = a.getString("browser_download_url")
            }
            Release(
                versionCode = versionCode,
                tag = tag,
                apkUrl = apkUrl ?: throw IOException("Release $tag has no $APK_NAME"),
                notes = json.optString("body", ""),
            ).also { AppLog.info(TAG, "Latest release: $tag") }
        } finally {
            conn.disconnect()
        }
    }

    suspend fun download(release: Release, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "build-${release.versionCode}.apk")
        AppLog.info(TAG, "Downloading ${release.apkUrl}")
        val conn = openFollowingRedirects(URL(release.apkUrl))
        try {
            val total = conn.contentLengthLong
            var done = 0L
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
            AppLog.info(TAG, "Downloaded ${out.length()} bytes")
            // Never hand Android half a file.
            if (total > 0 && done != total) throw IOException("Download stopped early ($done of $total bytes)")
            out
        } finally {
            conn.disconnect()
        }
    }

    /** GitHub release downloads redirect to a storage host; follow up to 5 hops by hand. */
    private fun openFollowingRedirects(start: URL): HttpURLConnection {
        var url = start
        repeat(6) {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "taycan-screen-app")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val next = conn.getHeaderField("Location")
                conn.disconnect()
                if (next == null) throw IOException("Redirect without Location")
                url = URL(url, next)
            } else if (code != 200) {
                conn.disconnect()
                throw IOException("Download failed: HTTP $code")
            } else {
                return conn
            }
        }
        throw IOException("Too many redirects")
    }

    /** Starts installation. Android then shows its own confirm screen (see InstallResultReceiver). */
    suspend fun install(apk: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Lets Android skip the confirm screen once this app installed the previous version itself.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        AppLog.info(TAG, "Installing ${apk.name} (session $sessionId)")
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallResultReceiver::class.java)
                .setAction(InstallResultReceiver.ACTION)
                .setPackage(context.packageName)
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
    }
}
