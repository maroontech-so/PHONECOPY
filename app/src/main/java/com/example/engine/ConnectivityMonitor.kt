package com.example.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class NetworkStatus {
    ONLINE_WIFI,
    ONLINE_CELLULAR,
    ONLINE_OTHER,
    OFFLINE
}

class ConnectivityMonitor(
    context: Context,
    private val onConnectivityRestored: () -> Unit = {}
) {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _status = MutableStateFlow(readCurrentStatus())
    val status: StateFlow<NetworkStatus> = _status.asStateFlow()

    @Volatile
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            updateAndNotify()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            updateAndNotify()
        }

        override fun onLost(network: Network) {
            _status.value = readCurrentStatus()
        }
    }

    fun start() {
        if (registered) return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching {
            connectivityManager.registerNetworkCallback(request, callback)
            registered = true
        }
        _status.value = readCurrentStatus()
    }

    fun stop() {
        if (!registered) return
        runCatching {
            connectivityManager.unregisterNetworkCallback(callback)
        }
        registered = false
    }

    fun isOnline(): Boolean = _status.value != NetworkStatus.OFFLINE

    private fun updateAndNotify() {
        val prev = _status.value
        val current = readCurrentStatus()
        _status.value = current
        if (prev == NetworkStatus.OFFLINE && current != NetworkStatus.OFFLINE) {
            onConnectivityRestored()
        }
    }

    fun readCurrentStatus(): NetworkStatus {
        val active = connectivityManager.activeNetwork ?: return NetworkStatus.OFFLINE
        val caps = connectivityManager.getNetworkCapabilities(active) ?: return NetworkStatus.OFFLINE
        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (!hasInternet) return NetworkStatus.OFFLINE
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkStatus.ONLINE_WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkStatus.ONLINE_CELLULAR
            else -> NetworkStatus.ONLINE_OTHER
        }
    }
}
