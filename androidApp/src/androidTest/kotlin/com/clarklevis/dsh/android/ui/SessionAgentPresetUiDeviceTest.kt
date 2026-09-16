package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.clarklevis.dsh.shared.facade.SharedSessionAgentPresetStore
import com.clarklevis.dsh.shared.protocol.GatewayFrame
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SessionAgentPresetUiDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun menuSwitchesRepeatedlyDisablesBrokenModeAndHidesOnFirstSend() {
        val store = SharedSessionAgentPresetStore()
        val query = store.open("s1", true, true).requests.first()
        store.acceptJson("""{"kind":"agent-presets","presets":[{"id":"standard","name":"标准模式","isDefault":true},{"id":"minimal","name":"极简模式"},{"id":"broken","name":"损坏模式","broken":"缺少插件"}]}""")
        store.accept(GatewayFrame(kind = "session-agent-preset", sessionId = "s1", requestId = query.correlationId, agentPreset = "standard", locked = false))
        var state by mutableStateOf(store.snapshot())
        compose.setContent {
            DshTheme {
                Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.BottomStart) {
                    SessionAgentPresetControl(state, onSelect = { id ->
                        val request = store.select(id).requests.single()
                        state = store.accept(GatewayFrame(kind = "select-agent-preset", sessionId = "s1", requestId = request.correlationId, agentPreset = id)).snapshot
                    }, onRetry = {})
                }
            }
        }
        compose.onNodeWithTag("session-agent-preset-capsule").performClick()
        compose.waitForIdle()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "session-agent-preset-menu.png")
            .outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
        compose.onNodeWithTag("session-agent-preset-broken").assertIsNotEnabled()
        compose.onNodeWithText("缺少插件").assertIsDisplayed()
        compose.onNodeWithText("完整工具，适合日常开发").assertIsDisplayed()
        compose.onNodeWithTag("session-agent-preset-minimal").performClick()
        compose.onNodeWithText("极简模式").assertIsDisplayed()
        assertEquals("s1", state.sessionId)
        compose.onNodeWithTag("session-agent-preset-capsule").performClick()
        compose.onNodeWithTag("session-agent-preset-standard").performClick()
        compose.onNodeWithText("标准模式").assertIsDisplayed()
        compose.runOnIdle { state = store.beginMessage().snapshot }
        compose.onNodeWithTag("session-agent-preset-capsule").assertDoesNotExist()
        compose.runOnIdle { state = store.accept(GatewayFrame(kind = "sent", sessionId = "s1")).snapshot }
        compose.onNodeWithTag("session-agent-preset-capsule").assertDoesNotExist()
    }
}
