package com.taycanscreen.app.update

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.taycanscreen.app.BuildConfig
import com.taycanscreen.app.log.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val latest: Int) : UpdateState
    data class Available(val release: Updater.Release) : UpdateState
    data object NeedsPermission : UpdateState
    data class Downloading(val progress: Float) : UpdateState
    data object Installing : UpdateState
    data class Failed(val message: String) : UpdateState
}

class UpdateViewModel(app: Application) : AndroidViewModel(app) {
    val updater = Updater(app)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    fun check() {
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Checking
        viewModelScope.launch {
            _state.value = try {
                val latest = updater.fetchLatest()
                if (latest.versionCode > BuildConfig.VERSION_CODE) UpdateState.Available(latest)
                else UpdateState.UpToDate(latest.versionCode)
            } catch (e: Exception) {
                AppLog.error("update", "Update check failed", e)
                retryWhenFixed()
                UpdateState.Failed(com.taycanscreen.app.util.Friendly.net(e, "Couldn't check for updates. Try again later."))
            }
        }
    }

    private var retryJob: Job? = null

    /**
     * After a failed check: look again by itself as soon as the cause is likely gone, i.e. the box's
     * clock jumps to the right date (it starts some days at 30 July 2025, which breaks secure
     * connections) or the internet comes up. Gives up after 10 minutes.
     */
    private fun retryWhenFixed() {
        retryJob?.cancel()
        retryJob = viewModelScope.launch {
            val wall0 = System.currentTimeMillis()
            val mono0 = com.taycanscreen.app.util.Mono.ms()
            val wasOnline = com.taycanscreen.app.net.NetMonitor.online.value
            repeat(120) {
                delay(5_000)
                if (_state.value !is UpdateState.Failed) return@launch
                val jumped = kotlin.math.abs((System.currentTimeMillis() - wall0) - (com.taycanscreen.app.util.Mono.ms() - mono0)) > 60_000
                val cameOnline = !wasOnline && com.taycanscreen.app.net.NetMonitor.online.value
                if (jumped || cameOnline) {
                    AppLog.info("update", if (jumped) "The box's clock was corrected: checking for updates again" else "Internet is up: checking for updates again")
                    check()
                    return@launch
                }
            }
        }
    }

    fun install(release: Updater.Release) {
        if (!updater.canInstall()) {
            AppLog.warn("update", "Install permission not granted yet")
            _state.value = UpdateState.NeedsPermission
            return
        }
        InstallResultReceiver.lastResult.value = null
        _state.value = UpdateState.Downloading(0f)
        viewModelScope.launch {
            try {
                val apk = updater.download(release) { p -> _state.value = UpdateState.Downloading(p) }
                _state.value = UpdateState.Installing
                // Let go of the car adapter cleanly before the app is replaced. When the old app was
                // simply killed mid-conversation, the next start took up to 100 s to get the car link
                // back (build 158's log); a clean disconnect leaves the adapter ready.
                com.taycanscreen.app.obd.ObdEngine.stop()
                updater.install(apk)
            } catch (e: Exception) {
                AppLog.error("update", "Update failed", e)
                com.taycanscreen.app.obd.ObdEngine.start()
                _state.value = UpdateState.Failed(com.taycanscreen.app.util.Friendly.net(e, "The update didn't download. Tap Check for update to try again."))
            }
        }
    }

    /** Called when the user comes back from the "Install unknown apps" setting. */
    fun onResume() {
        if (_state.value is UpdateState.NeedsPermission && updater.canInstall()) {
            AppLog.info("update", "Install permission granted")
            check()
        }
    }
}
