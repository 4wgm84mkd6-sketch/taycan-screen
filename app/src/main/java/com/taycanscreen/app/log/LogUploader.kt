package com.taycanscreen.app.log

import android.content.Context
import android.util.Base64
import com.taycanscreen.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPOutputStream

/**
 * "Send log to Claude": uploads the newest session logs (gzip) to the owner's private log repo
 * (<owner>/taycan-logs) through the GitHub contents API, so Claude can read them directly.
 *
 * Uses the box's own GitHub sign-in (GitHubAuth device flow); no credential is built into the app.
 */
object LogUploader {
    private const val TAG = "log"
    private const val SESSIONS_TO_SEND = 3

    /** Thrown when GitHub rejects the stored sign-in, so the screen can ask to sign in again. */
    class SignInNeeded(message: String) : Exception(message)

    /** Returns a short human-readable result. */
    suspend fun upload(context: Context): String = withContext(Dispatchers.IO) {
        if (!GitHubAuth.configured) return@withContext "Not set up yet."
        val token = GitHubAuth.token(context) ?: throw SignInNeeded("Sign in to GitHub first.")
        AppLog.currentFile() // flushes the current session
        val files = AppLog.logDir(context).listFiles { f -> f.name.endsWith(".log") }
            ?.sortedByDescending { it.name }
            ?.take(SESSIONS_TO_SEND)
            .orEmpty()
        if (files.isEmpty()) return@withContext "No logs yet."

        val stamp = LocalDateTime.now()
        val folder = "logs/${stamp.format(DateTimeFormatter.ISO_LOCAL_DATE)}/" +
            "${stamp.format(DateTimeFormatter.ofPattern("HHmmss"))}-build${BuildConfig.VERSION_CODE}"
        var sent = 0
        for (f in files) {
            val result = putFile(token, "$folder/${f.name}.gz", gzip(f), "Log ${f.name} from build ${BuildConfig.VERSION_CODE}")
            if (result != null) {
                AppLog.error(TAG, "Upload of ${f.name} failed: $result")
                return@withContext "Upload failed: $result" + if (sent > 0) " ($sent of ${files.size} sent)" else ""
            }
            sent++
        }
        AppLog.info(TAG, "Uploaded $sent log file(s) to ${BuildConfig.LOG_REPO}/$folder")
        "Sent $sent log file(s). Tell Claude \"sent\"."
    }

    private fun gzip(f: File): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { gz -> f.inputStream().use { it.copyTo(gz) } }
        return out.toByteArray()
    }

    /** Creates one file in the log repo. Returns null on success, else a short reason. */
    private fun putFile(token: String, path: String, bytes: ByteArray, message: String): String? {
        val url = URL("https://api.github.com/repos/${BuildConfig.LOG_REPO}/contents/$path")
        val body = JSONObject()
            .put("message", message)
            .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .toString()
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "taycan-screen-app")
            setRequestProperty("Content-Type", "application/json")
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            when (val code = conn.responseCode) {
                200, 201 -> null
                401 -> throw SignInNeeded("GitHub sign-in expired. Sign in again.")
                403, 404 -> "The token can't write to ${BuildConfig.LOG_REPO} (HTTP $code)."
                else -> "HTTP $code " + (conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(200) ?: "")
            }
        } catch (e: SignInNeeded) {
            throw e
        } catch (e: Exception) {
            "${e.javaClass.simpleName}: ${e.message}"
        } finally {
            conn.disconnect()
        }
    }
}
