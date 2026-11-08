package com.nitpicker.fakes

import com.nitpicker.domain.NetworkState
import com.nitpicker.pipeline.ConnectivityMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeNetworkGate(initial: NetworkState = NetworkState.ONLINE) : ConnectivityMonitor {
    private val mutable = MutableStateFlow(initial)
    override val state = mutable.asStateFlow()

    fun setState(value: NetworkState) {
        mutable.value = value
    }
}
