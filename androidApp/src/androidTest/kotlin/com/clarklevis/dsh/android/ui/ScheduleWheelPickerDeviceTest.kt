package com.clarklevis.dsh.android.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ScheduleWheelPickerDeviceTest {
    @get:Rule val locale = ChineseDeviceLocaleRule()
    @get:Rule val compose = createComposeRule()

    @Test fun commonWheelTicksOnUserChangesWithoutVibratingOnInitialDisplayOrRedraw() {
        val events = mutableListOf<HapticFeedbackType>()
        val feedback = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { events.add(hapticFeedbackType) }
        }
        var selected by mutableIntStateOf(8)
        var redraw by mutableIntStateOf(0)
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides feedback) {
                DshTheme { ScheduleTheme {
                    Column(Modifier.fillMaxSize().padding(20.dp)) {
                        Text("重绘 $redraw")
                        DshNumberWheel(selected, 0, 23, "小时", "haptic-test-wheel",
                            format = { it.toString() }, onChange = { selected = it })
                    }
                } }
            }
        }
        compose.runOnIdle { assertTrue("首次展示不应震动", events.isEmpty()) }
        compose.onNodeWithTag("haptic-test-wheel").performTouchInput { swipeUp(durationMillis = 600) }
        compose.waitForIdle()
        var count = 0
        compose.runOnIdle {
            assertTrue("跨过刻度时必须反馈", events.isNotEmpty())
            assertTrue(events.all { it == HapticFeedbackType.SegmentTick })
            assertTrue(selected > 8)
            count = events.size
            redraw++
        }
        compose.runOnIdle { assertEquals("相同刻度重绘不额外震动", count, events.size) }
    }

    @Test fun intervalWheelHasBoundedValuesAndOnlyConfirmsDraftInBothThemes() {
        var open by mutableStateOf(true)
        var dark by mutableStateOf(false)
        var value by mutableStateOf("1")
        var confirms = 0
        compose.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                DshTheme { ScheduleTheme {
                    Box(Modifier.fillMaxSize().background(Color(0xFF768090))) {
                        if (open) ScheduleIntervalPicker(value, "天", { open = false }, {
                            value = it; confirms++; open = false
                        })
                    }
                } }
            }
        }
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark = night }
            compose.onNodeWithText("1 天").assertIsDisplayed()
            compose.onNodeWithText("10000 天").assertDoesNotExist()
            val sheet = compose.onNodeWithTag("schedule-wheel-sheet").fetchSemanticsNode().boundsInRoot
            val wheel = compose.onNodeWithTag("schedule-interval-wheel").fetchSemanticsNode().boundsInRoot
            val band = compose.onNodeWithTag("schedule-interval-wheel-selection").fetchSemanticsNode().boundsInRoot
            val valueBounds = compose.onNodeWithText("1 天").fetchSemanticsNode().boundsInRoot
            assertEquals(wheel.center.y, band.center.y, 1f)
            assertEquals(band.center.y, valueBounds.center.y, 2f)
            assertTrue("面板两侧保留留白", sheet.left > 0)
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            val screenshot = checkNotNull(automation.takeScreenshot())
            val pixel = screenshot.getPixel((sheet.left + 30).toInt(), (sheet.top + 170).toInt())
            val brightness = (android.graphics.Color.red(pixel) + android.graphics.Color.green(pixel) + android.graphics.Color.blue(pixel)) / 3
            assertTrue("材质随主题变化", if (night) brightness < 80 else brightness > 200)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            File(context.getExternalFilesDir(null), if (night) "schedule-wheel-dark.png" else "schedule-wheel-light.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        compose.onNodeWithTag("schedule-interval-wheel").performTouchInput { swipeDown(durationMillis = 500) }
        compose.waitForIdle()
        compose.onNodeWithTag("schedule-interval-wheel").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1"))
        compose.onNodeWithTag("schedule-interval-wheel-list").performScrollToIndex(9999)
        compose.waitUntil(5000) {
            compose.onNodeWithTag("schedule-interval-wheel").fetchSemanticsNode().config[SemanticsProperties.StateDescription] == "10000"
        }
        compose.onNodeWithText("10000 天").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals("1", value); assertEquals(0, confirms); open = true; value = "10000" }
        compose.onNodeWithText("10000 天").assertIsDisplayed()
        compose.onNodeWithText("1 天").assertDoesNotExist()
        compose.onNodeWithTag("schedule-interval-wheel").performTouchInput { swipeUp(durationMillis = 500) }
        compose.waitForIdle()
        compose.onNodeWithText("确定").performClick()
        compose.runOnIdle { assertEquals("10000", value); assertEquals(1, confirms) }
    }
}
