package com.clarklevis.dsh.android

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.clarklevis.dsh.android.platform.GatewayLifecycleEvent
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class DshAndroidApplication : Application(), DefaultLifecycleObserver {
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
        hosts = AndroidMultiGatewayStore(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        isInForeground = true
        graph.diagnostics.lifecycle(GatewayLifecycleEvent.FOREGROUND)
        graph.gatewayRuntime.applicationDidBecomeActive()
    }

    override fun onStop(owner: LifecycleOwner) {
        isInForeground = false
        hosts.stopPresence()
        hosts.cancelPairing()
        graph.diagnostics.lifecycle(GatewayLifecycleEvent.BACKGROUND)
        graph.gatewayScope.launch { graph.gatewayRuntime.applicationDidEnterBackground() }
    }
}
