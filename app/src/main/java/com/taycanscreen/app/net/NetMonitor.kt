package com.taycanscreen.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.taycanscreen.app.log.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Logs every time the box gains or loses internet (Wi-Fi / 4G), and exposes "online" to the screens. */
object NetMonitor {
    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online.asStateFlow()
    private val _label = MutableStateFlow("")
    /** e.g. "4G" or "Wi-Fi", empty when offline. */
    val label: StateFlow<String> = _label.asStateFlow()

    fun init(context: Context) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        fun describe(caps: NetworkCapabilities?): String = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "4G"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "other"
        }
        fun refresh(reason: String) {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            val ok = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val label = if (ok) describe(caps) else ""
            if (ok != _online.value || label != _label.value) {
                AppLog.info("net", "${if (ok) "Online via $label" else "OFFLINE"} ($reason; active=${describe(caps)})")
            }
            _online.value = ok
            _label.value = label
        }
        refresh("start")
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refresh("network available")
            override fun onLost(network: Network) = refresh("network lost")
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refresh("capabilities changed")
        })
    }
}
