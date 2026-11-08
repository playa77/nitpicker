package com.nitpicker.pipeline

import android.content.Context
import android.net.*
import com.nitpicker.domain.NetworkState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface ConnectivityMonitor {
    val state: StateFlow<NetworkState>
}

class SystemConnectivityMonitor(context: Context) : ConnectivityMonitor, AutoCloseable {
    private val manager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val mutable = MutableStateFlow(NetworkState.OFFLINE)
    override val state = mutable.asStateFlow()

    private fun refresh() {
        val caps = manager.getNetworkCapabilities(manager.activeNetwork)
        mutable.value =
            if (
                caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            )
                NetworkState.ONLINE
            else NetworkState.OFFLINE
    }

    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                refresh()
            }

            override fun onLost(network: Network) {
                refresh()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                refresh()
            }

            override fun onUnavailable() {
                mutable.value = NetworkState.OFFLINE
            }
        }

    init {
        manager.registerDefaultNetworkCallback(callback)
        refresh()
    }

    override fun close() {
        manager.unregisterNetworkCallback(callback)
    }
}
