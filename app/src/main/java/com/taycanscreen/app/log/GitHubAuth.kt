package com.taycanscreen.app.log

import android.content.Context
import com.taycanscreen.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * GitHub device-flow sign-in for the owner's "taycan-logs" GitHub App.
 *
 * Only the App's public Client ID is built into the app. The box shows a short code, the owner
 * enters it at github.com/login/device on their phone, and the resulting user token is stored in
 * this app's private storage on the box. Because the App is installed only on the taycan-logs repo
 * with Contents read/write, the token can't reach anything else.
 */
object GitHubAuth {
    private const val TAG = "log"
    private const val PREFS = "github"
    private const val KEY_TOKEN = "token"

    data class DeviceCode(val deviceCode: String, val userCode: String, val verificationUri: String, val intervalSec: Int, val expiresInSec: Int)

    val configured: Boolean get() = BuildConfig.LOG_CLIENT_ID.isNotBlank() && BuildConfig.LOG_REPO.isNotBlank()

    fun token(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TOKEN, null)

    fun signOut(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_TOKEN).apply()
        AppLog.info(TAG, "Signed out of GitHub")
    }

    suspend fun start(): DeviceCode = withContext(Dispatchers.IO) {
        val json = postForm("https://github.com/login/device/code", mapOf("client_id" to BuildConfig.LOG_CLIENT_ID))
        if (json.has("error")) throw IOException(json.optString("error_description", json.getString("error")))
        DeviceCode(
            deviceCode = json.getString("device_code"),
            userCode = json.getString("user_code"),
            verificationUri = json.optString("verification_uri", "https://github.com/login/device"),
            intervalSec = json.optInt("interval", 5),
            expiresInSec = json.optInt("expires_in", 900),
        ).also { AppLog.info(TAG, "GitHub sign-in started (code shown on screen)") }
    }

    /** Waits for the owner to enter the code. Saves and returns the token, or throws with a reason. */
    suspend fun waitForToken(context: Context, code: DeviceCode): String {
        var interval = code.intervalSec.coerceAtLeast(5)
        val deadline = System.currentTimeMillis() + code.expiresInSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000L)
            val json = withContext(Dispatchers.IO) {
                postForm(
                    "https://github.com/login/oauth/access_token",
                    mapOf(
                        "client_id" to BuildConfig.LOG_CLIENT_ID,
                        "device_code" to code.deviceCode,
                        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                    ),
                )
            }
            val token = json.optString("access_token", "")
            if (token.isNotEmpty()) {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TOKEN, token).apply()
                AppLog.info(TAG, "Signed in to GitHub")
                return token
            }
            when (val err = json.optString("error", "")) {
                "authorization_pending" -> Unit
                "slow_down" -> interval += 5
                "expired_token" -> throw IOException("The code expired. Tap Sign in again.")
                "access_denied" -> throw IOException("Sign-in was cancelled on GitHub.")
                else -> throw IOException(json.optString("error_description", err.ifEmpty { "Unexpected reply" }))
            }
        }
        throw IOException("The code expired. Tap Sign in again.")
    }

    private fun postForm(url: String, fields: Map<String, String>): JSONObject {
        val body = fields.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("User-Agent", "taycan-screen-app")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
            return try {
                JSONObject(text)
            } catch (_: Exception) {
                throw IOException("GitHub answered HTTP ${conn.responseCode}")
            }
        } finally {
            conn.disconnect()
        }
    }
}
