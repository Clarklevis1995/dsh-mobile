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
import com.clarklevis.dsh.android.platform.OkHttpGatewayTransport
import com.clarklevis.dsh.shared.gateway.GatewayRuntime
import com.clarklevis.dsh.shared.protocol.GatewayFrame
import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder
import com.clarklevis.dsh.shared.platform.GatewayAttachmentCache
import com.clarklevis.dsh.shared.platform.GatewayClock
import com.clarklevis.dsh.shared.platform.GatewayCredentialStore
import com.clarklevis.dsh.shared.platform.GatewayNetworkMonitor
import com.clarklevis.dsh.shared.platform.GatewayPreferences
import com.clarklevis.dsh.shared.platform.GatewayTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
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
    onIdentity: suspend (GatewayFrame, String) -> Unit = { _, _ -> }
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
    val networkMonitor: GatewayNetworkMonitor = networkMonitorOverride ?: AndroidNetworkMonitor(application)
    /**
     * 并发候选采纳：每条候选是一条独立的控制+会话通道，首个完成应用层握手的候选转正。
     * 平台侧只负责"为一个地址造一条通道"，拨号策略留在共享层。
     */
    val transport: GatewayTransport = transportOverride ?: com.clarklevis.dsh.shared.gateway.RacingGatewayTransport(
        // 平台侧唯一职责：为一个候选地址造一条控制+会话通道。不重试、不轮换、不判断哪个地址更好。
        factory = { _, _ ->
            com.clarklevis.dsh.shared.gateway.SplitGatewayTransport(
                OkHttpGatewayTransport(diagnostics = diagnostics),
                OkHttpGatewayTransport(diagnostics = diagnostics)
            )
        },
        scope = gatewayScope,
        totalBudgetMilliseconds = RACE_TOTAL_BUDGET_MILLISECONDS
    )
    val gatewayRuntime = GatewayRuntime(
        transport = transport,
        // 竞速预算必须小于运行时的单次尝试超时，否则运行时兜底会先触发，
        // 竞速收集到的失败原因永远到不了 lastError。
        connectionAttemptTimeoutMilliseconds = RACE_TOTAL_BUDGET_MILLISECONDS + RUNTIME_TIMEOUT_MARGIN_MILLISECONDS,
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

    private companion object {
        const val RACE_TOTAL_BUDGET_MILLISECONDS = 12_000L
        const val RUNTIME_TIMEOUT_MARGIN_MILLISECONDS = 3_000L
    }
}
