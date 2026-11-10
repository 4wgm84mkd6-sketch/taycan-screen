package com.taycanscreen.app.util

/** Turns a network failure into words for the driver. The technical detail stays in the log. */
object Friendly {
    fun net(e: Throwable, fallback: String): String {
        val m = "${e.javaClass.simpleName} ${e.message ?: ""}"
        return when {
            // The box starts with a date in 2025 until it syncs; secure connections fail until then.
            "SSL" in m || "CertPath" in m || "Chain validation" in m || "Certificate" in m ->
                "The box's clock isn't set yet. Try again in a minute."
            "UnknownHost" in m || "Unable to resolve host" in m || "ConnectException" in m ||
                "SocketTimeout" in m || "timeout" in m.lowercase() || "connection abort" in m.lowercase() ->
                "No internet right now. Try again when the box is online."
            else -> fallback
        }
    }
}
