package com.clarklevis.dsh.android

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.clarklevis.dsh.android.platform.GatewayLifecycleEvent
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class DshAndroidApplication : Application(), DefaultLifecycleObserver {
    lateinit var graph: AndroidAppGraph
        private set

    override fun onCreate() {
        super<Application>.onCreate()
        graph = AndroidAppGraph(this)
        graph.diagnostics.lifecycle(GatewayLifecycleEvent.APPLICATION_CREATED)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        graph.applicationScope.launch {
            graph.attachmentCache.removeExpired()
        }
        graph.applicationScope.launch {
            graph.gatewayRuntime.state
                .map { it.shouldKeepAliveInBackground }
                .distinctUntilChanged()
                .collect { active ->
                    if (active) {
                        graph.diagnostics.lifecycle(GatewayLifecycleEvent.KEEP_ALIVE_START, keepAlive = true)
                        GatewayConnectionService.start(this@DshAndroidApplication)
                    } else {
                        graph.diagnostics.lifecycle(GatewayLifecycleEvent.KEEP_ALIVE_STOP, keepAlive = false)
                        GatewayConnectionService.stop(this@DshAndroidApplication)
                    }
                }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        graph.diagnostics.lifecycle(GatewayLifecycleEvent.FOREGROUND)
        graph.gatewayRuntime.applicationDidBecomeActive()
    }

    override fun onStop(owner: LifecycleOwner) {
        graph.diagnostics.lifecycle(GatewayLifecycleEvent.BACKGROUND)
        graph.gatewayScope.launch { graph.gatewayRuntime.applicationDidEnterBackground() }
    }
}
