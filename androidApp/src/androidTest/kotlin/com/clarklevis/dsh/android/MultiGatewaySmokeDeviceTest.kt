package com.clarklevis.dsh.android

import android.os.SystemClock
import android.util.Base64
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.clarklevis.dsh.shared.gateway.GatewayConnectionState
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/** 需显式启用，并先启动 scripts/multi_gateway_smoke_server.py；普通设备回归跳过。 */
class MultiGatewaySmokeDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val hosts get() = (instrumentation.targetContext.applicationContext as DshAndroidApplication).hosts

    @Test
    fun sameResourceIdsRemainIsolatedAcrossHostsAndPresenceChecks() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("multiGatewaySmoke") == "true")
        ActivityScenario.launch(MainActivity::class.java).use {
            eventually {
                hosts.ready &&
                    hosts.activeGraph.networkMonitor.state.value == com.clarklevis.dsh.shared.platform.GatewayNetworkState.AVAILABLE
            }
            pair("A", 18781, "d56a1098-8519-43a1-9dce-fb99863bf5bb")
            eventually { showsOnly("A") }
            pair("B", 18782, "a56a1098-8519-43a1-9dce-fb99863bf5bb")
            eventually { showsOnly("B") }
            instrumentation.runOnMainSync {
                hosts.select(hosts.profiles.first { it.gatewayName == "Smoke A" })
            }
            eventually { showsOnly("A") }
            instrumentation.runOnMainSync { hosts.startPresence() }
            try {
                eventually {
                    hosts.profiles.filter { it.gatewayName in setOf("Smoke A", "Smoke B") }
                        .all { it.localId in hosts.onlineIds }
                }
            } finally {
                instrumentation.runOnMainSync { hosts.stopPresence() }
            }
            instrumentation.runOnMainSync {
                hosts.remove(hosts.profiles.filter { it.gatewayName in setOf("Smoke A", "Smoke B") })
            }
            eventually {
                hosts.profiles.none { it.gatewayName in setOf("Smoke A", "Smoke B") } &&
                    hosts.activeId == null && hosts.activeGraph.stateHolder.snapshot.sessions.isEmpty() &&
                    hosts.activeGraph.stateHolder.snapshot.workspaces.isEmpty()
            }
        }
    }

    private fun pair(name: String, port: Int, gatewayId: String) {
        val payload = JSONObject()
            .put("version", 2)
            .put("publicUrl", "ws://10.0.2.2:$port/ws/mobile")
            .put("pairingCode", "smoke-$name")
            .put("expiresAt", System.currentTimeMillis() + 60_000)
            .put("gatewayId", gatewayId)
            .put("gatewayName", "Smoke $name")
        val encoded = Base64.encodeToString(payload.toString().toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        instrumentation.runOnMainSync { hosts.pair(encoded) }
    }

    private fun showsOnly(name: String): Boolean {
        val graph = hosts.activeGraph
        val snapshot = graph.stateHolder.snapshot
        return graph.gatewayRuntime.state.value.connection == GatewayConnectionState.CONNECTED &&
            hosts.activeProfile?.gatewayName == "Smoke $name" &&
            snapshot.workspaces.singleOrNull()?.path == "/smoke/$name" &&
            snapshot.sessions.singleOrNull()?.title == "$name only session"
    }

    private fun eventually(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var matches = false
            instrumentation.runOnMainSync { matches = condition() }
            if (matches) return
            SystemClock.sleep(100)
        }
        var detail = ""
        instrumentation.runOnMainSync {
            val manager = instrumentation.targetContext.getSystemService(android.net.ConnectivityManager::class.java)
            detail = "active=${hosts.activeProfile?.gatewayName}, error=${hosts.error}, " +
                "state=${hosts.activeGraph.gatewayRuntime.state.value.connection}, " +
                "monitor=${hosts.activeGraph.networkMonitor.state.value}, default=${manager.activeNetwork}, " +
                "capabilities=${manager.getNetworkCapabilities(manager.activeNetwork)}"
        }
        throw AssertionError("等待模拟网关状态超时：$detail")
    }
}
