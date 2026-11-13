package com.clarklevis.dsh.android

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.clarklevis.dsh.android.platform.GatewayLifecycleEvent
import com.clarklevis.dsh.android.ui.AppearanceSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DshAndroidApplication :
    Application(),
    DefaultLifecycleObserver {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppearanceSettings.localizedContext(base))
    }

    internal val shareInbox by lazy { IncomingShareInbox(this) }

    internal fun receiveShare(intent: Intent) {
        tunnelScope.launch { shareInbox.receive(this@DshAndroidApplication, intent) }
    }

    lateinit var hosts: AndroidMultiGatewayStore
        private set

    // 内嵌 Tailscale 节点的所有者。它必须活过每一次 AndroidAppGraph 重建（切主机会 cancel 图），
    // 所以放在 Application 上，而不是挂在图或某个 Activity 的生命周期里。
    private val tunnelScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate
    )

    private val backgroundPreferences by lazy { getSharedPreferences("background_connection", MODE_PRIVATE) }
    internal var backgroundKeepAliveEnabled by mutableStateOf(false)
        private set

    internal fun setBackgroundKeepAlive(enabled: Boolean) {
        backgroundPreferences.edit().putBoolean("enabled", enabled).apply()
        backgroundKeepAliveEnabled = enabled
        hosts.applyConnectionKeepAlive()
    }

    /** 隧道开启时请求保持前台服务；最终由 AndroidMultiGatewayStore 统一裁决（回合与隧道是或关系）。 */
    internal var tunnelKeepAliveRequested = false
        private set

    /**
     * 连接路径策略（VPN 语义）：内部节点可用，连接就走它的 CONNECT 出口；否则直连。
     * 传输层不需要知道这里为什么有代理，也不判断地址属于哪个网段。
     */
    internal fun routeClient(): okhttp3.OkHttpClient {
        val proxy = tunnel.proxyForRoute()
        graph.diagnostics.transportRoute(routedThroughNode = proxy != null)
        if (proxy == null) return com.clarklevis.dsh.android.platform.OkHttpGatewayTransport.defaultClient()
        return com.clarklevis.dsh.android.platform.OkHttpGatewayTransport.proxiedClient(
            proxy.host,
            proxy.port,
            proxy.user,
            proxy.pass
        )
    }

    internal fun setTunnelKeepAlive(requested: Boolean) {
        tunnelKeepAliveRequested = requested
        if (::hosts.isInitialized) hosts.applyConnectionKeepAlive()
    }

    internal val tunnelSettings by lazy {
        com.clarklevis.dsh.android.ui.TailscaleTunnelSettings(
            com.clarklevis.dsh.android.ui.TailscaleTunnelSettings.preferences(this)
        )
    }

    internal val tunnel: com.clarklevis.dsh.android.platform.TailscaleTunnelController by lazy {
        com.clarklevis.dsh.android.platform.TailscaleTunnelController(
            this,
            tunnelScope,
            settings = tunnelSettings,
            // 隧道必须自己发诊断：它是应用级对象，不跟着 AndroidAppGraph 重建，
            // 不传就落到默认的 disabled()，线上什么都不留（这次就是这么丢的）。
            diagnostics = com.clarklevis.dsh.android.platform.AndroidGatewayDiagnostics.forApplication(this)
        )
    }
    val graph: AndroidAppGraph get() = hosts.activeGraph

    @Volatile
    internal var isInForeground = false
        private set
    internal var pendingNotificationSession by mutableStateOf<AndroidNotificationSessionRoute?>(null)
        private set

    internal fun openNotificationSession(intent: Intent?) {
        val gatewayId = intent?.getStringExtra(EXTRA_NOTIFICATION_GATEWAY_ID)?.takeIf(String::isNotBlank)
        val sessionId = intent?.getStringExtra(EXTRA_NOTIFICATION_SESSION_ID)?.takeIf(String::isNotBlank)
        if (gatewayId != null && sessionId != null) {
            pendingNotificationSession = AndroidNotificationSessionRoute(gatewayId, sessionId)
        }
    }

    internal fun clearNotificationSession(route: AndroidNotificationSessionRoute) {
        if (pendingNotificationSession == route) pendingNotificationSession = null
    }

    override fun onCreate() {
        super<Application>.onCreate()
        ru.noties.jlatexmath.JLatexMathAndroid.init(this)
        com.clarklevis.dsh.android.platform.LocalAppLog.start(this)
        tunnelScope.launch(Dispatchers.IO) {
            com.clarklevis.dsh.android.platform.devicetools.AndroidReminderReceiver.restore(this@DshAndroidApplication)
        }
        backgroundKeepAliveEnabled = backgroundPreferences.getBoolean("enabled", false)
        hosts = AndroidMultiGatewayStore(this)
        maintainCacheStorage()
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    private fun maintainCacheStorage() {
        graph.gatewayScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val cache = com.clarklevis.dsh.android.platform.AndroidChatHistoryCache.shared(this@DshAndroidApplication)
            try {
                cache.trimToBytes(72L * AndroidCacheDiskManager.MIB)
                AndroidCacheStorage.shared(this@DshAndroidApplication).maintain()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                com.clarklevis.dsh.android.platform.LocalAppLog.w("CacheStorage", "maintenance-failed reason=storage-error")
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        com.clarklevis.dsh.android.platform.LocalAppLog.i("AppLifecycle", "trim-memory level=$level")
        if (!::hosts.isInitialized) return
        graph.stateHolder.trimMemory()
        com.clarklevis.dsh.android.platform.AndroidAttachmentCache.trimMemory()
        com.clarklevis.dsh.android.ui.PluginIconImageCache.trimMemory()
        graph.applicationScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            com.clarklevis.dsh.android.ui.trimMarkdownMemory()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }

    override fun onStart(owner: LifecycleOwner) {
        isInForeground = true
        hosts.applyConnectionKeepAlive()
        graph.diagnostics.lifecycle(GatewayLifecycleEvent.FOREGROUND)
        if (!tunnel.hasSession) autoStartTunnelIfEnabled()
        // 浏览器授权返回时保留原节点和候选节点，立即刷新授权结果。
        tunnel.refreshAfterReturningToApp()
        graph.gatewayRuntime.applicationDidBecomeActive()
    }

    /** 冷启动可能早于 onStart；先恢复已保存的隧道，再让网关选择连接出口。 */
    internal suspend fun awaitTunnelForStoredConnection() = withContext(Dispatchers.Main.immediate) {
        if (!tunnel.hasSession) autoStartTunnelIfEnabled()
        tunnel.awaitReadyForStoredConnection()
    }

    private fun autoStartTunnelIfEnabled() {
        val settings = tunnelSettings
        when {
            settings.shouldAutoStart() -> {
                graph.diagnostics.tunnel("start", ok = true, backendState = "auto-start")
                tunnel.start()
            }
            // 开了但从没登录过：不自动启动，并把开关本身落回关闭——
            // 否则界面会显示"已开启"而实际什么都没跑，那是骗人。
            settings.enabled -> {
                settings.selectEnabled(false)
                graph.diagnostics.tunnel("auto-start", ok = false, backendState = "none")
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        isInForeground = false
        graph.stateHolder.notifyPendingAgentRequests()
        maintainCacheStorage()
        hosts.stopPresence()
        hosts.cancelPairing()
        graph.diagnostics.lifecycle(GatewayLifecycleEvent.BACKGROUND)
        graph.gatewayScope.launch { graph.gatewayRuntime.applicationDidEnterBackground() }
    }
}
