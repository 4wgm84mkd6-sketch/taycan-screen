package com.taycanscreen.app

import android.app.Application
import com.taycanscreen.app.log.AppLog

class TaycanApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppLog.info(
            "app",
            "Started Taycan Screen build ${BuildConfig.VERSION_CODE} (${BuildConfig.VERSION_NAME}), " +
                "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}), " +
                "device ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
        )
    }
}
