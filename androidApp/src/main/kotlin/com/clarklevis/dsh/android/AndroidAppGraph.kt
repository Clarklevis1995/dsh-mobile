package com.clarklevis.dsh.android

import android.app.Application
import com.clarklevis.dsh.android.platform.AndroidAttachmentCache
import com.clarklevis.dsh.android.platform.AndroidAttachmentThumbnailer
import com.clarklevis.dsh.android.platform.AndroidGatewayClock
import com.clarklevis.dsh.android.platform.AndroidGatewayCredentialStore
import com.clarklevis.dsh.android.platform.AndroidGatewayDiagnostics
import com.clarklevis.dsh.android.platform.AndroidGatewayPreferences
import com.clarklevis.dsh.android.platform.AndroidImagePreprocessor
import com.clarklevis.dsh.android.platform.AndroidNetworkMonitor
import com.clarklevis.dsh.shared.gateway.GatewayRuntime
import com.clarklevis.dsh.shared.platform.GatewayAttachmentCache
import com.clarklevis.dsh.shared.platform.GatewayClock
import com.clarklevis.dsh.shared.platform.GatewayCredentialStore
import com.clarklevis.dsh.shared.platform.GatewayNetworkMonitor
import com.clarklevis.dsh.shared.platform.GatewayPreferences
import com.clarklevis.dsh.shared.platform.GatewayTransport
import com.clarklevis.dsh.shared.protocol.GatewayFrame
import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AndroidAppGraph(
    val application: Application,
    transportOverride: GatewayTransport? = null,
    preferencesOverride: GatewayPreferences? = null,
    credentialStoreOverride: GatewayCredentialStore? = null,
    attachmentCacheOverride: GatewayAttachmentCache? = null,
    networkMonitorOverride: GatewayNetworkMonitor? = null,
    clockOverride: GatewayClock? = null,
    frameDecoderOverride: ((String) -> GatewayFrame)? = null,
    val gatewayLocalId: String = "legacy",
    expectedGatewayId: String? = null,
    trustedEndpoints: List<String> = emptyList(),
    onIdentity: suspend (GatewayFrame, String) -> Unit = { _, _ -> },
    internal val storedConnectionPrerequisite: suspend () -> Unit = {
        (application as? DshAndroidApplication)?.awaitTunnelForStoredConnection()
    }
) {
    /** 生命周期/UI 提交使用 Main；Gateway decode、MVI 与磁盘协调使用单线程后台 dispatcher。 */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val gatewayDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
    val gatewayScope = CoroutineScope(SupervisorJob() + gatewayDispatcher)
    internal val diagnostics = AndroidGatewayDiagnostics.forApplication(application)
    val preferences: GatewayPreferences = preferencesOverride ?: AndroidGatewayPreferences(application)
    val credentialStore: GatewayCredentialStore =
        credentialStoreOverride ?: AndroidGatewayCredentialStore(application)
    val attachmentCache: GatewayAttachmentCache =
        attachmentCacheOverride ?: AndroidAttachmentCache(application, gatewayId = gatewayLocalId)
    val attachmentThumbnailer = AndroidAttachmentThumbnailer()
    val imagePreprocessor = AndroidImagePreprocessor(application.contentResolver)
    internal val agentNotifications by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidAgentNotificationManager(application)
    }
    val networkMonitor: GatewayNetworkMonitor = networkMonitorOverride ?: AndroidNetworkMonitor(application)

    // 连接是否经内嵌节点，由应用层策略决定（节点在跑且已进 tailnet 才走它）；传输层不判断地址属于哪个网段。
    private val routeClientProvider: () -> okhttp3.OkHttpClient = {
        (application as? DshAndroidApplication)?.routeClient() ?: com.clarklevis.dsh.android.platform.OkHttpGatewayTransport.defaultClient()
    }
    private val routeAllowsRtc: (String) -> Boolean = { endpoint ->
        // 经节点时不尝试 RTC：节点的 CONNECT 出口只带 TCP，WebRTC 的 socket 会绕过它。
        (application as? DshAndroidApplication)?.tunnel?.proxyForRoute() == null &&
            com.clarklevis.dsh.shared.transport.GatewayTransportPolicy.allowsRtc(endpoint)
    }
    val transport: GatewayTransport = transportOverride ?: com.clarklevis.dsh.android.platform.RtcGatewayTransport(
        application,
        gatewayScope,
        diagnostics,
        rtcPolicy = routeAllowsRtc,
        clientProvider = routeClientProvider
    )
    internal val connectionMethod: kotlinx.coroutines.flow.StateFlow<String>
        get() = (transport as? com.clarklevis.dsh.android.platform.RtcGatewayTransport)?.connectionMethod
            ?: kotlinx.coroutines.flow.MutableStateFlow("WebSocket")
    internal val connectionPaths
        get() = (transport as? com.clarklevis.dsh.android.platform.RtcGatewayTransport)?.connectionPaths
            ?: kotlinx.coroutines.flow.MutableStateFlow(emptyMap<String, com.clarklevis.dsh.shared.transport.RtcSelectedPath>())
    val gatewayRuntime = GatewayRuntime(
        transport = transport,
        preferences = preferences,
        credentials = credentialStore,
        attachmentCache = attachmentCache,
        networkMonitor = networkMonitor,
        clock = clockOverride ?: AndroidGatewayClock,
        scope = gatewayScope,
        frameDecoder = frameDecoderOverride ?: GatewayWireDecoder::decode,
        frameDecodingDispatcher = Dispatchers.Default,
        expectedGatewayId = expectedGatewayId,
        trustedEndpoints = trustedEndpoints,
        onIdentity = onIdentity
    )
    var pairingHandler: ((String) -> Unit)? = null
    var gatewayDisplayName: String = ""
    val stateHolder: AndroidSharedStateHolder by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidSharedStateHolder(graph = this)
    }
}
