package com.clarklevis.dsh.android.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.clarklevis.dsh.android.DshAndroidApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tsnetgo.Tsnetgo

/**
 * 内嵌 Tailscale 节点的生命周期所有者。
 *
 * 两条硬约束决定了它的位置与形态：
 * 1) 它必须比 AndroidAppGraph 活得久——每次切主机图都会被 cancel 重建，节点不能跟着死；
 * 2) 节点启动不能发生在 transport.open() 里——open() 在 gatewayDispatcher 上串行执行，
 *    Runtime 只在 open 返回后才挂尝试超时，把几十秒的 bring-up 放进去会白烧一次重连退避。
 * 所以这里只被用户动作（设置页开关）与网络变化驱动，连接路径自己不去启动它。
 */
internal enum class TunnelUiStage { DISABLED, STARTING, NEEDS_LOGIN, WAITING_ROUTES, READY, FAILED }

/** 面向 UI 的失败种类：白名单，不把底层异常原样抛给用户。 */
internal object TunnelFailureKind {
    const val INTERFACE_SOURCE = "interface-source"
    const val NATIVE_LOAD = "native-load"
    const val BACKEND_START = "backend-start"
    const val LOGIN_WAIT = "login-wait"
    const val STATUS = "status"
    const val UNKNOWN = "unknown"
}

internal data class TailscaleTunnelUiState(
    val stage: TunnelUiStage = TunnelUiStage.DISABLED,
    val authUrl: String? = null,
    val peerCount: Int = 0,
    val nodeAddress: String? = null,
    val failureKind: String? = null,
    /** 是否真的还在跟踪状态。转圈必须反映这件事，不能反映"处于过渡态"——
     *  否则停止跟踪后界面仍然一直转，就是在骗用户。 */
    val isPolling: Boolean = false
)

internal data class TunnelConfig(val nodeName: String, val authKey: String)

internal class TailscaleTunnelController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val configProvider: () -> TunnelConfig,
    private val diagnostics: AndroidGatewayDiagnostics = AndroidGatewayDiagnostics.disabled()
) {
    private val tunnel by lazy { TsnetTunnel(context, diagnostics) }
    private val mutableState = MutableStateFlow(TailscaleTunnelUiState())
    val state: StateFlow<TailscaleTunnelUiState> = mutableState.asStateFlow()

    private var drive: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var active = false

    /**
     * 连接路径策略要问的问题：现在有没有**可用**的内部节点出口。
     *
     * 只看"节点在跑"是不够的：真机实测，隧道打开但没登录（backend=NeedsLogin）时，
     * 把连接送进节点会让本来正常的局域网直连也断掉（网关侧 0 连接、反复重连）。
     * 判据是后端状态 Running —— 也就是节点确实进了 tailnet、能承载包。
     * 已知残留窗口：Running 之后 peers 仍会从 0 涨上来（实测约 10 秒），那一段经节点可能连不上；
     * 需要"直连失败→经节点重试"来兜，那是下一步，不在这里偷偷加。
     */
    fun proxyForRoute(): TsnetProxy? {
        if (!active) return null
        if (!tunnel.statusJson().contains("\"state\":\"Running\"")) return null
        return tunnel.proxyInfo()
    }

    fun setEnabled(enabled: Boolean) {
        if (enabled) start() else stop()
    }

    fun start() {
        if (!pushInterfaces()) {
            mutableState.value = TailscaleTunnelUiState(stage = TunnelUiStage.FAILED, failureKind = TunnelFailureKind.INTERFACE_SOURCE)
            return
        }
        watchNetworks()
        val config = configProvider()
        active = true
        (context.applicationContext as? DshAndroidApplication)?.setTunnelKeepAlive(true)
        mutableState.value = TailscaleTunnelUiState(stage = TunnelUiStage.STARTING, isPolling = true)
        drive?.cancel()
        drive = scope.launch(Dispatchers.IO) {
            // srv.Up() 会阻塞到 Running 或超时；没有 Auth Key 时它不可能自己成功。
            // 所以第一次只等 12s：让登录链接尽快可见，之后交给下面的跟踪循环。
            var stage = apply(tunnel.bringUp(config.authKey, config.nodeName, timeoutSeconds = 12L), bringUp = true)
            var waitMs = POLL_MIN_MS
            // 用户没关开关就一直跟踪：登录发生在浏览器里，完成时间不可预测。
            // 之前 40 次 × 5 秒（200 秒）的硬上限会在用户还没登录完时静默停止跟踪，
            // 而界面还在转圈——那是骗人。
            while (stage.isTransient && active) {
                delay(waitMs)
                if (!active) break
                waitMs = (waitMs * 2).coerceAtMost(POLL_MAX_MS)
                stage = apply(tunnel.statusJson(), bringUp = false)
            }
        }
    }

    fun stop() {
        active = false
        (context.applicationContext as? DshAndroidApplication)?.setTunnelKeepAlive(false)
        drive?.cancel()
        drive = null
        runCatching { tunnel.shutdown() }
        unwatchNetworks()
        mutableState.value = TailscaleTunnelUiState(stage = TunnelUiStage.DISABLED)
    }

    /** 网络变化后重新推一份接口快照。Go 侧没有暴露 netmon.InjectEvent，所以我们只能替换数据源，
     *  不能强制它立刻重采——这一点在界面上不装作“已立即生效”。 */
    private fun pushInterfaces(): Boolean = runCatching {
        Tsnetgo.setInterfacesJSON(JavaInterfaceSnapshot.json())
    }.isSuccess

    private fun watchNetworks() {
        if (networkCallback != null) return
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { pushInterfaces() }
            override fun onLost(network: Network) { pushInterfaces() }
        }
        runCatching {
            manager.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                callback
            )
        }.onSuccess { networkCallback = callback }
    }

    private fun unwatchNetworks() {
        val callback = networkCallback ?: return
        networkCallback = null
        (context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
            ?.unregisterNetworkCallback(callback)
    }

    /**
     * 把一次状态 JSON 变成 UI 状态；未到位的状态继续轮询。
     * 实测事实：BackendState=Running 之后 peers 仍可能为 0（路由未就绪），首次探测会超时、
     * 之后才成功——所以 Running+peers=0 是「正在建立连接」，不是错误。
     */
    private fun apply(json: String, bringUp: Boolean): TailscaleTunnelUiState {
        val backend = Regex("\"state\":\"([A-Za-z]*)\"").find(json)?.groupValues?.getOrNull(1).orEmpty()
        val peers = Regex("\"peers\":(\\d+)").find(json)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val authUrl = Regex("\"authURL\":\"(https:[^\"]*)\"").find(json)?.groupValues?.getOrNull(1)
        val address = Regex("\"tailscaleIPs\":\\[\"([^\"]+)\"").find(json)?.groupValues?.getOrNull(1)
        val error = Regex("\"error\":\"([^\"]*)\"").find(json)?.groupValues?.getOrNull(1).orEmpty()

        // 「这台设备登录过」的事实要留到偏好里：只有登录过，下次启动才尊重那个开关；
        // 开了但从未登录的情况不该在下次启动时自动生效（用户要求）。
        if (backend == "Running") {
            (context.applicationContext as? DshAndroidApplication)?.markTunnelAuthenticated()
        }

        val next = when {
            backend == "Running" && peers > 0 -> TailscaleTunnelUiState(
                stage = TunnelUiStage.READY, peerCount = peers, nodeAddress = address
            )
            backend == "Running" -> TailscaleTunnelUiState(
                stage = TunnelUiStage.WAITING_ROUTES, nodeAddress = address
            )
            backend == "NeedsLogin" || !authUrl.isNullOrEmpty() -> TailscaleTunnelUiState(
                stage = TunnelUiStage.NEEDS_LOGIN, authUrl = authUrl, nodeAddress = address
            )
            backend == "Starting" || backend == "NoState" -> TailscaleTunnelUiState(
                stage = TunnelUiStage.STARTING, authUrl = authUrl, nodeAddress = address
            )
            error.contains("netlinkrib") || error.contains("interface") -> TailscaleTunnelUiState(
                stage = TunnelUiStage.FAILED, failureKind = TunnelFailureKind.INTERFACE_SOURCE
            )
            error.contains("native-load") -> TailscaleTunnelUiState(
                stage = TunnelUiStage.FAILED, failureKind = TunnelFailureKind.NATIVE_LOAD
            )
            bringUp && error.isNotBlank() -> TailscaleTunnelUiState(
                stage = TunnelUiStage.FAILED,
                failureKind = if (error.contains("deadline") || error.contains("timeout")) {
                    TunnelFailureKind.LOGIN_WAIT
                } else {
                    TunnelFailureKind.BACKEND_START
                }
            )
            else -> TailscaleTunnelUiState(
                stage = TunnelUiStage.FAILED, failureKind = TunnelFailureKind.UNKNOWN
            )
        }
        val withPolling = next.copy(isPolling = active && next.isTransient)
        mutableState.value = withPolling
        return withPolling
    }

    private val TailscaleTunnelUiState.isTransient: Boolean
        get() = stage == TunnelUiStage.NEEDS_LOGIN ||
            stage == TunnelUiStage.STARTING ||
            stage == TunnelUiStage.WAITING_ROUTES

    private companion object {
        const val POLL_MIN_MS = 5_000L
        const val POLL_MAX_MS = 30_000L
    }
}
