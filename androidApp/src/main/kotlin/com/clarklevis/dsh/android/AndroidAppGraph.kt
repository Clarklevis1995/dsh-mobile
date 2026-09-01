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
    frameDecoderOverride: ((String) -> GatewayFrame)? = null
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
        attachmentCacheOverride ?: AndroidAttachmentCache(application)
    val attachmentThumbnailer = AndroidAttachmentThumbnailer()
    val imagePreprocessor = AndroidImagePreprocessor(application.contentResolver)
    val networkMonitor: GatewayNetworkMonitor = networkMonitorOverride ?: AndroidNetworkMonitor(application)
    val transport: GatewayTransport = transportOverride ?: OkHttpGatewayTransport(diagnostics = diagnostics)
    val gatewayRuntime = GatewayRuntime(
        transport = transport,
        preferences = preferences,
        credentials = credentialStore,
        attachmentCache = attachmentCache,
        networkMonitor = networkMonitor,
        clock = clockOverride ?: AndroidGatewayClock,
        scope = gatewayScope,
        frameDecoder = frameDecoderOverride ?: GatewayWireDecoder::decode
    )
    val stateHolder: AndroidSharedStateHolder by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidSharedStateHolder(graph = this)
    }
}
