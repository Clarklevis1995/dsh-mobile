package com.clarklevis.dsh.android.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import com.clarklevis.dsh.android.AndroidSharedStateHolder
import com.clarklevis.dsh.android.MobileScheduledTask
import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder
import java.io.File
import java.io.FileInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ScheduledTaskEditSheetDeviceTest {
    @get:Rule val locale = ChineseDeviceLocaleRule()
    @get:Rule val compose = createComposeRule()

    @Test fun editSheetKeepsTopBoundaryWhileKeyboardAndFormScrollChange() {
        val holder = AndroidSharedStateHolder()
        val task = MobileScheduledTask.from(GatewayWireDecoder.decode("""{"kind":"schedule-catalog","items":[{"id":"edit-task","sessionId":"edit-session","status":"active","kind":"daily","title":"每日新闻汇总","prompt":"检索科技和金融新闻，整理来源并汇总。","time":"08:00:00.000","timeZone":"Asia/Shanghai","scheduledAt":"2099-01-31T00:00:00Z"}]}""").items!!.single())!!
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command: String): String = automation.executeShellCommand(command).use {
            FileInputStream(it.fileDescriptor).bufferedReader().readText().trim()
        }
        val originalImeSetting = shell("settings get secure show_ime_with_hard_keyboard")
        val originalFlags = automation.serviceInfo.flags
        var open by mutableStateOf(true)
        var minimumTop = 0f
        fun capture(name: String) {
            val screenshot = checkNotNull(automation.takeScreenshot())
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            File(context.getExternalFilesDir(null), name).outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        fun imeVisible() = automation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        try {
            shell("settings put secure show_ime_with_hard_keyboard 1")
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            compose.setContent {
                val density = LocalDensity.current
                minimumTop = WindowInsets.safeDrawing.getTop(density) + with(density) { androidx.compose.ui.unit.Dp(8f).toPx() }
                DshTheme {
                    ScheduleTheme {
                        Box(Modifier.fillMaxSize().background(Color(0xFF768090))) {
                            if (open) ScheduledTaskEditSheet(task, holder, onDismiss = { open = false })
                        }
                    }
                }
            }
            val panel = compose.onNodeWithTag("scheduled-task-edit-sheet")
            panel.assertIsDisplayed()
            val top = panel.fetchSemanticsNode().boundsInRoot.top
            assertTrue("编辑面板必须在顶部安全区下方", top >= minimumTop - 2f)
            compose.onNodeWithText("取消").assertIsDisplayed()
            compose.onNodeWithText("保存").assertIsDisplayed()
            capture("schedule-edit-boundary.png")
            val title = compose.onNodeWithTag("scheduled-task-edit-title")
            title.performTouchInput { click() }
            compose.waitUntil(5000) { imeVisible() }
            title.performTextInput("（调整）")
            compose.onNodeWithText("保存").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithText("时区").performScrollTo().assertIsDisplayed()
            assertEquals("键盘不能改变面板上边界", top, panel.fetchSemanticsNode().boundsInRoot.top, 2f)
            capture("schedule-edit-keyboard.png")
            compose.onNodeWithText("编辑定时任务").performTouchInput { click() }
            compose.waitUntil(5000) { !imeVisible() }
            title.assertIsNotFocused()
            compose.onNodeWithText("取消").performTouchInput { click() }
            panel.assertDoesNotExist()
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
            shell(if (originalImeSetting == "null") "settings delete secure show_ime_with_hard_keyboard" else "settings put secure show_ime_with_hard_keyboard $originalImeSetting")
            compose.runOnIdle { holder.close() }
        }
    }
}
