package com.taycanscreen.app.update

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.taycanscreen.app.BuildConfig
import com.taycanscreen.app.log.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
                UpdateState.Failed(com.taycanscreen.app.util.Friendly.net(e, "Couldn't check for updates. Try again later."))
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
                updater.install(apk)
            } catch (e: Exception) {
                AppLog.error("update", "Update failed", e)
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
