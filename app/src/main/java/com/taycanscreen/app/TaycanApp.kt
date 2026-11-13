package com.taycanscreen.app

import android.app.Application
import com.taycanscreen.app.log.AppLog

class TaycanApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        com.taycanscreen.app.audio.EqEngine.init(this)
        com.taycanscreen.app.music.SpotifyWeb.init(this)
        com.taycanscreen.app.net.NetMonitor.init(this)
        com.taycanscreen.app.nav.Nav.init(this)
        com.taycanscreen.app.car.TripComputer.start(this)
        com.taycanscreen.app.car.SpeedLimit.init(this)
        AppLog.info(
            "app",
            "Started Taycan Screen build ${BuildConfig.VERSION_CODE} (${BuildConfig.VERSION_NAME}), " +
                "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}), " +
                "device ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
        )
        // The box starts with its clock at 30 July 2025 and can take minutes to correct it; until then
        // Spotify and every secure connection fail. Record how the box is set up to keep time, to find
        // out whether anything can be done about it from here. Reads only; changes nothing.
        val cr = contentResolver
        val auto = runCatching { android.provider.Settings.Global.getInt(cr, android.provider.Settings.Global.AUTO_TIME, -1) }.getOrDefault(-1)
        val server = runCatching { android.provider.Settings.Global.getString(cr, "ntp_server") }.getOrNull()
        val su = runCatching {
            listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/vendor/bin/su", "/su/bin/su").filter { java.io.File(it).exists() }
        }.getOrDefault(emptyList())
        AppLog.info(
            "clock",
            "Box time-keeping: automatic time ${when (auto) { 1 -> "on"; 0 -> "off"; else -> "unknown" }}, " +
                "time server ${server ?: "the system default"}, system build ${android.os.Build.TYPE}/${android.os.Build.TAGS}, " +
                "su ${if (su.isEmpty()) "not found" else su.joinToString()}",
        )
        // Boot timing: how long Android had been running when Taycan Screen started.
        AppLog.info(
            "boot",
            "Android up ${android.os.SystemClock.elapsedRealtime() / 1000} s when Taycan Screen started " +
                "(uptime excluding sleep ${android.os.SystemClock.uptimeMillis() / 1000} s)",
        )
    }
}
