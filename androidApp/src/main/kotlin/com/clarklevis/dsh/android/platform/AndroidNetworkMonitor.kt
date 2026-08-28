package com.clarklevis.dsh.android.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.clarklevis.dsh.shared.platform.GatewayNetworkMonitor
import com.clarklevis.dsh.shared.platform.GatewayNetworkState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidNetworkMonitor(context: Context) : GatewayNetworkMonitor {
    private val connectivityManager =
        context.getSystemService(ConnectivityManager::class.java)
    private val mutableState = MutableStateFlow(currentState())

    override val state: StateFlow<GatewayNetworkState> = mutableState.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = publishCurrentState()
        override fun onLost(network: Network) = publishCurrentState()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
            publishCurrentState()
    }

    init {
        connectivityManager.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(),
            callback
        )
    }

    private fun publishCurrentState() {
        mutableState.value = currentState()
    }

    private fun currentState(): GatewayNetworkState {
        val network = connectivityManager.activeNetwork ?: return GatewayNetworkState.UNAVAILABLE
        val capabilities = connectivityManager.getNetworkCapabilities(network)
            ?: return GatewayNetworkState.UNAVAILABLE
        // Mobile Gateway 允许仅局域网可达；不能要求系统已验证公网连通性。
        return if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            GatewayNetworkState.AVAILABLE
        } else {
            GatewayNetworkState.UNAVAILABLE
        }
    }
}
