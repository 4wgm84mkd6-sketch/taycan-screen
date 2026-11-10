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

    /** The box's connection for the status icon: online, transport and Wi-Fi signal (dBm, if known). */
    data class Link(val online: Boolean, val transport: String, val rssi: Int?)
    private val _link = MutableStateFlow(Link(true, "", null))
    val link: StateFlow<Link> = _link.asStateFlow()
    private var lastBars = -1

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
            val rssi = if (android.os.Build.VERSION.SDK_INT >= 29 && caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                caps.signalStrength.takeIf { it != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED && it < 0 }
            } else null
            _link.value = Link(ok, describe(caps), rssi)
            val bars = when { !ok -> 0; rssi == null || rssi >= -60 -> 3; rssi >= -72 -> 2; else -> 1 }
            if (bars != lastBars) {
                lastBars = bars
                AppLog.info("net", "Hotspot icon: $bars bar(s) (${describe(caps)}${rssi?.let { ", $it dBm" } ?: ""})")
            }
        }
        refresh("start")
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refresh("network available")
            override fun onLost(network: Network) = refresh("network lost")
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refresh("capabilities changed")
        })
    }
}
