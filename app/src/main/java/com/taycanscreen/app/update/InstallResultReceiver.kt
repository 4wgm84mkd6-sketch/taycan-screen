package com.taycanscreen.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.taycanscreen.app.log.AppLog
import kotlinx.coroutines.flow.MutableStateFlow

class InstallResultReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION = "com.taycanscreen.app.INSTALL_RESULT"

        /** Last install outcome, shown on screen. Null while nothing has been reported. */
        val lastResult = MutableStateFlow<String?>(null)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                AppLog.info("update", "Android is asking for confirmation")
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(confirm)
                        lastResult.value = "Tap Update on Android's screen to finish."
                    } catch (e: Exception) {
                        AppLog.error("update", "Couldn't open the confirm screen", e)
                        lastResult.value = "Couldn't open Android's install screen: ${e.message}"
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                AppLog.info("update", "Install succeeded")
                lastResult.value = "Installed. Reopen the app."
            }
            else -> {
                AppLog.error("update", "Install failed: status $status, $message")
                lastResult.value = "Install failed (status $status): ${message ?: "no details"}"
            }
        }
    }
}
