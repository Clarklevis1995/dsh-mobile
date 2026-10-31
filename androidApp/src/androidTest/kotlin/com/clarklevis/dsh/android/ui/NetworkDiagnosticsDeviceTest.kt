package com.clarklevis.dsh.android.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import com.clarklevis.dsh.shared.diagnostics.NetworkDiagnosticsSample
import com.clarklevis.dsh.shared.diagnostics.NetworkDiagnosticsSnapshot
import com.clarklevis.dsh.shared.transport.RtcSelectedPath
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NetworkDiagnosticsDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectedRoutesShowBothChannelsAndDoNotRetainOldEndpoints() {
        val original = Locale.getDefault()
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        var paths by mutableStateOf(
            mapOf(
                "control" to RtcSelectedPath("local", "UDP", "192.168.1.20:50000", "192.168.1.94:51000", "host", "host"),
                "conversation" to RtcSelectedPath("relay", "UDP", "203.0.113.1:50000", "203.0.113.2:51000", "relay", "host"),
            ),
        )
        try {
            compose.setContent { DshTheme { RtcConnectionPathDetails(paths) } }
            compose.onNodeWithText("控制通道：内网直连 · UDP").assertIsDisplayed()
            compose.onNodeWithText("会话通道：TURN 中继 · UDP").assertIsDisplayed()
            compose.onNodeWithText("192.168.1.20:50000 → 192.168.1.94:51000").assertIsDisplayed()
            compose.runOnUiThread { paths = emptyMap() }
            compose.onNodeWithText("控制通道：正在确认路径").assertIsDisplayed()
            compose.onNodeWithText("192.168.1.20:50000 → 192.168.1.94:51000").assertDoesNotExist()
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test fun backgroundAndPageExitCancelProbesAndForegroundRestartsExactlyOnce() {
        val original = Locale.getDefault()
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        val active = AtomicInteger()
        val probes = AtomicInteger()
        var visible by mutableStateOf(true)
        lateinit var owner: LifecycleOwner
        lateinit var registry: LifecycleRegistry
        val observe = {
            flow {
                active.incrementAndGet()
                try {
                    while (true) {
                        probes.incrementAndGet()
                        emit(NetworkDiagnosticsSnapshot(connected = true, supported = true, connectionMethod = "WebSocket"))
                        delay(100)
                    }
                } finally {
                    active.decrementAndGet()
                }
            }
        }
        try {
            compose.runOnUiThread {
                owner = object : LifecycleOwner {
                    override val lifecycle = LifecycleRegistry(this).also { registry = it }
                }
                registry.currentState = Lifecycle.State.RESUMED
            }
            compose.setContent {
                DshTheme {
                    CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                        if (visible) NetworkDiagnosticsPanel(observe)
                    }
                }
            }
            compose.waitUntil { probes.get() >= 2 }
            assertEquals(1, active.get())
            compose.runOnUiThread { registry.currentState = Lifecycle.State.CREATED }
            compose.waitUntil { active.get() == 0 }
            val paused = probes.get()
            Thread.sleep(350)
            assertEquals(paused, probes.get())
            compose.runOnUiThread { registry.currentState = Lifecycle.State.RESUMED }
            compose.waitUntil { probes.get() > paused }
            assertEquals(1, active.get())
            compose.runOnUiThread { visible = false }
            compose.waitUntil { active.get() == 0 }
            val closed = probes.get()
            Thread.sleep(350)
            assertEquals(closed, probes.get())
            compose.runOnUiThread {
                registry.currentState = Lifecycle.State.CREATED
                registry.currentState = Lifecycle.State.RESUMED
            }
            Thread.sleep(250)
            assertEquals("关闭页面后前后台切换也不能重新检测", closed, probes.get())
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test fun liveChartSupportsAllFourMetricsAndShowsExplicitMissingValues() {
        val original = Locale.getDefault()
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        try {
            val sample = NetworkDiagnosticsSample(1.0, 35.0, 4.0, 0.0, 2048.0, 1024.0, 1)
            compose.setContent {
                DshTheme {
                    NetworkDiagnosticsContent(
                        NetworkDiagnosticsSnapshot(
                            connected = true,
                            supported = true,
                            connectionMethod = "WebSocket",
                            samples = listOf(sample, sample.copy(elapsedSeconds = 2.0, roundTripMs = 42.0)),
                            roundTripMs = 42.0,
                            jitterMs = 4.0,
                            lossPercent = 0.0,
                            receivedBytesPerSecond = 2048.0
                        )
                    )
                }
            }
            compose.onNodeWithTag("network-live-chart").assertIsDisplayed()
            compose.onNodeWithText("42.0 ms").assertIsDisplayed()
            for (metric in listOf("丢包", "抖动", "流量", "延迟")) {
                compose.onNodeWithText(metric).performClick()
                compose.onNodeWithTag("network-live-chart").assertIsDisplayed()
                val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
                val file = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "network-$metric.png")
                file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
            assertEquals("—", networkNumber(-1.0, " ms"))
            assertEquals("—", networkNumber(Double.NaN, "%"))
        } finally {
            Locale.setDefault(original)
        }
    }
}
