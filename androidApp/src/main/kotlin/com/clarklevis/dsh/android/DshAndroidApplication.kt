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
    lateinit var hosts: AndroidMultiGatewayStore
        private set
    val graph: AndroidAppGraph get() = hosts.activeGraph

    override fun onCreate() {
        super<Application>.onCreate()
        hosts = AndroidMultiGatewayStore(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        graph.diagnostics.lifecycle(GatewayLifecycleEvent.FOREGROUND)
        graph.gatewayRuntime.applicationDidBecomeActive()
    }

    override fun onStop(owner: LifecycleOwner) {
        hosts.stopPresence()
        hosts.cancelPairing()
        graph.diagnostics.lifecycle(GatewayLifecycleEvent.BACKGROUND)
        graph.gatewayScope.launch { graph.gatewayRuntime.applicationDidEnterBackground() }
    }
}
