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
    private const val SHOTS_TO_SEND = 8
    /** Upload in pieces so a weak mobile signal only has to carry a small request at a time. */
    private const val PART_BYTES = 256 * 1024
    private const val TRIES_PER_PART = 4

    /** Thrown when GitHub rejects the stored sign-in, so the screen can ask to sign in again. */
    class SignInNeeded(message: String) : Exception(message)

    /** Returns a short human-readable result. */
    suspend fun upload(context: Context): String = withContext(Dispatchers.IO) {
        if (!GitHubAuth.configured) return@withContext "Not set up yet."
        val token = GitHubAuth.token(context) ?: throw SignInNeeded("Sign in to GitHub first.")
        val current = AppLog.currentFile() // also flushes it
        // Always the current session first, then the most recent earlier ones.
        val files = (listOfNotNull(current) + AppLog.sessionFiles(context))
            .distinctBy { it.absolutePath }
            .take(SESSIONS_TO_SEND)
        if (files.isEmpty()) return@withContext "No logs yet."

        val stamp = LocalDateTime.now()
        val folder = "logs/${stamp.format(DateTimeFormatter.ISO_LOCAL_DATE)}/" +
            "${stamp.format(DateTimeFormatter.ofPattern("HHmmss"))}-build${BuildConfig.VERSION_CODE}"
        var sent = 0
        for (f in files) {
            val bytes = gzip(f)
            // Small files go up whole; bigger ones as name.gz.part01, part02… (joined again by Claude).
            val parts = if (bytes.size <= PART_BYTES) listOf(bytes)
            else (bytes.indices step PART_BYTES).map { bytes.copyOfRange(it, minOf(it + PART_BYTES, bytes.size)) }
            for ((i, part) in parts.withIndex()) {
                val path = if (parts.size == 1) "$folder/${f.name}.gz" else "$folder/${f.name}.gz.part%02d".format(i + 1)
                var result: String? = null
                for (attempt in 1..TRIES_PER_PART) {
                    result = putFile(token, path, part, "Log ${f.name} (${i + 1}/${parts.size}) from build ${BuildConfig.VERSION_CODE}")
                    if (result == null) break
                    AppLog.warn(TAG, "Upload of $path failed (try $attempt): $result")
                    if (attempt < TRIES_PER_PART) kotlinx.coroutines.delay(3_000L * attempt)
                }
                if (result != null) {
                    return@withContext "Upload failed: $result" + if (sent > 0) " ($sent of ${files.size} files sent)" else "" +
                        ". The log is kept on the box; try again with a better signal."
                }
            }
            sent++
        }
        // Screenshots of the Drive screen (Screenshots.kt), newest first, not sent before.
        var shots = 0
        for (f in Screenshots.unsent(context).take(SHOTS_TO_SEND)) {
            val path = "$folder/screenshots/${f.name}"
            var result: String? = null
            for (attempt in 1..TRIES_PER_PART) {
                result = putFile(token, path, f.readBytes(), "Screenshot ${f.name} from build ${BuildConfig.VERSION_CODE}")
                if (result == null) break
                AppLog.warn(TAG, "Upload of $path failed (try $attempt): $result")
                if (attempt < TRIES_PER_PART) kotlinx.coroutines.delay(3_000L * attempt)
            }
            if (result != null) break // logs are up; screenshots try again next time
            Screenshots.markSent(f)
            shots++
        }
        AppLog.info(TAG, "Uploaded $sent log file(s) and $shots screenshot(s) to ${BuildConfig.LOG_REPO}/$folder")
        "Sent $sent log file(s)" + (if (shots > 0) " and $shots screenshot(s)" else "") + ". Tell Claude \"sent\"."
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
