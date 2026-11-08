package com.nitpicker.pipeline

import android.net.*
import com.nitpicker.domain.NetworkState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ConnectivityMonitorTest {
    @Test
    fun validatedInternetIsRequiredAndCallbackIsUnregistered() {
        val manager =
            RuntimeEnvironment.getApplication().getSystemService(ConnectivityManager::class.java)
        val shadow = shadowOf(manager)
        val active = manager.activeNetwork!!
        val capabilities = NetworkCapabilities()
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadow.setNetworkCapabilities(active, capabilities)
        val existing = shadow.networkCallbacks.toSet()
        val before = existing.size
        val monitor = SystemConnectivityMonitor(RuntimeEnvironment.getApplication())
        assertEquals(NetworkState.OFFLINE, monitor.state.value)
        assertEquals(before + 1, shadow.networkCallbacks.size)
        val callback = shadow.networkCallbacks.single { it !in existing }
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadow.setNetworkCapabilities(active, capabilities)
        callback.onCapabilitiesChanged(active, capabilities)
        assertEquals(NetworkState.ONLINE, monitor.state.value)
        shadow.clearAllNetworks()
        callback.onLost(active)
        assertEquals(NetworkState.OFFLINE, monitor.state.value)
        monitor.close()
        assertEquals(before, shadow.networkCallbacks.size)
    }
}
