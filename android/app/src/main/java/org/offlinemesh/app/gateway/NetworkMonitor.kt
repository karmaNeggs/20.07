package org.offlinemesh.app.gateway

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Whether the phone currently has any internet-capable network (Wi-Fi, cellular, hotspot). */
class NetworkMonitor(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _online = MutableStateFlow(false)
    val online: StateFlow<Boolean> = _online
    private val _unmetered = MutableStateFlow(false)

    /** True on an unmetered network (normally Wi-Fi). Files only travel when this is true. */
    val unmetered: StateFlow<Boolean> = _unmetered
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { _online.value = true }
        override fun onLost(network: Network) { _online.value = currentlyOnline(); _unmetered.value = false }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            _online.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            _unmetered.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }
    }

    fun start() {
        if (registered) return
        _online.value = currentlyOnline()
        cm.registerDefaultNetworkCallback(callback)
        registered = true
    }

    fun stop() {
        if (!registered) return
        cm.unregisterNetworkCallback(callback)
        registered = false
    }

    private fun currentlyOnline(): Boolean {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
