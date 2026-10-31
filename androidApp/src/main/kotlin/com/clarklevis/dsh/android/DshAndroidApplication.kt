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
import kotlinx.coroutines.launch

class DshAndroidApplication : Application(), DefaultLifecycleObserver {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppearanceSettings.localizedContext(base))
    }

    lateinit var hosts: AndroidMultiGatewayStore
        private set
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
        com.clarklevis.dsh.android.platform.LocalAppLog.start(this)
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
        graph.diagnostics.lifecycle(GatewayLifecycleEvent.FOREGROUND)
        graph.gatewayRuntime.applicationDidBecomeActive()
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
