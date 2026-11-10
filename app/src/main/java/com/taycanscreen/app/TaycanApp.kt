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
        // Boot timing: how long Android had been running when Taycan Screen started.
        AppLog.info(
            "boot",
            "Android up ${android.os.SystemClock.elapsedRealtime() / 1000} s when Taycan Screen started " +
                "(uptime excluding sleep ${android.os.SystemClock.uptimeMillis() / 1000} s)",
        )
    }
}
