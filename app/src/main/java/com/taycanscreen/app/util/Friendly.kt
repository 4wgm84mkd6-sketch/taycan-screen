package com.taycanscreen.app.util

import com.taycanscreen.app.BuildConfig
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Turns a network failure into words for the driver. The technical detail stays in the log. */
object Friendly {
    /**
     * True while the box's date is impossible: earlier than the day this build was made. Some starts
     * begin at 30 July 2025 until the box syncs its clock; secure connections fail until then
     * ("Chain validation failed"), because every certificate looks not yet valid.
     */
    fun clockIsWrong(nowMs: Long = System.currentTimeMillis()): Boolean =
        BuildConfig.BUILD_TIME_MS > 0 && nowMs < BuildConfig.BUILD_TIME_MS - 86_400_000L

    private fun isSecurityFailure(m: String) =
        "SSL" in m || "CertPath" in m || "Chain validation" in m || "Certificate" in m

    fun net(e: Throwable, fallback: String, nowMs: Long = System.currentTimeMillis()): String {
        val m = "${e.javaClass.simpleName} ${e.message ?: ""}"
        return when {
            isSecurityFailure(m) && clockIsWrong(nowMs) -> {
                val says = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM yyyy"))
                "The box's clock is wrong (it says $says), so secure connections fail. " +
                    "It corrects itself shortly after the box gets internet, and this will try again by itself."
            }
            isSecurityFailure(m) -> "Couldn't make a secure connection. Try again in a minute."
            "UnknownHost" in m || "Unable to resolve host" in m || "ConnectException" in m ||
                "SocketTimeout" in m || "timeout" in m.lowercase() || "connection abort" in m.lowercase() ->
                "No internet right now. Try again when the box is online."
            else -> fallback
        }
    }
}
