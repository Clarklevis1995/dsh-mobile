package com.clarklevis.dsh.android.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            // 回调中的能力快照已就绪；同步查询 activeNetwork 可能仍返回切换前的状态。
            mutableState.value = stateFor(capabilities)
        }
    }

    init {
        connectivityManager.registerDefaultNetworkCallback(callback)
    }

    private fun publishCurrentState() {
        mutableState.value = currentState()
    }

    private fun currentState(): GatewayNetworkState {
        val network = connectivityManager.activeNetwork ?: return GatewayNetworkState.UNAVAILABLE
        val capabilities = connectivityManager.getNetworkCapabilities(network)
            ?: return GatewayNetworkState.UNAVAILABLE
        return stateFor(capabilities)
    }

    private fun stateFor(capabilities: NetworkCapabilities): GatewayNetworkState {
        // Mobile Gateway 允许仅局域网可达；不能要求系统已验证公网连通性。
        return if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            GatewayNetworkState.AVAILABLE
        } else {
            GatewayNetworkState.UNAVAILABLE
        }
    }
}
