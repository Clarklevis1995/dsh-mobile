package com.clarklevis.dsh.android.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.platform.app.InstrumentationRegistry
import com.clarklevis.dsh.android.MobileScheduledTask
import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder
import com.clarklevis.dsh.shared.protocol.JsonValue
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ScheduledTaskHistorySheetDeviceTest {
    @get:Rule val locale = ChineseDeviceLocaleRule()
    @get:Rule val compose = createComposeRule()

    @Test fun largeHistoryKeepsTopBoundaryFooterAndCloseVisibleInBothThemes() {
        val task = MobileScheduledTask.from(GatewayWireDecoder.decode("""{"kind":"schedule-catalog","items":[{"id":"history-task","sessionId":"history-session","status":"active","kind":"daily","title":"每日新闻汇总","prompt":"汇总科技和金融新闻","time":"08:00:00.000","timeZone":"Asia/Shanghai","scheduledAt":"2099-01-31T00:00:00Z"}]}""").items!!.single())!!
        var count by mutableStateOf(100)
        var dark by mutableStateOf(false)
        var open by mutableStateOf(true)
        var loadedCursor: String? = null
        var openedSession: String? = null
        var safeTop = 0f
        var extraGap = 0f
        var windowHeight = 0
        compose.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                val density = LocalDensity.current
                safeTop = WindowInsets.safeDrawing.getTop(density).toFloat()
                extraGap = with(density) { androidx.compose.ui.unit.Dp(8f).toPx() }
                windowHeight = LocalWindowInfo.current.containerSize.height
                DshTheme {
                    ScheduleTheme {
                        Box(Modifier.fillMaxSize().background(Color(0xFF768090))) {
                            Text("底层定时任务页面")
                            if (open) ScheduledTaskHistorySheet(
                                task = task,
                                history = JsonValue.ObjectValue(mapOf(
                                    "records" to JsonValue.ArrayValue(List(count) { index ->
                                        JsonValue.ObjectValue(mapOf("scheduledAt" to JsonValue.StringValue("2099-01-31T08:00:00Z"),
                                            "title" to JsonValue.StringValue("第${index + 1}次运行记录"),
                                            "prompt" to JsonValue.StringValue("长轨迹正文：检索科技和金融新闻，整理来源并汇总。\n".repeat(100))))
                                    }),
                                    "nextBefore" to JsonValue.StringValue("older-cursor")
                                )),
                                loading = false, error = null,
                                onLoadHistory = { loadedCursor = it },
                                onDismiss = { open = false },
                                onOpenSession = { openedSession = it }
                            )
                        }
                    }
                }
            }
        }
        compose.onAllNodesWithText("详情")[0].performClick()
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark = night }
            val sheet = compose.onNodeWithTag("scheduled-task-history-sheet")
            sheet.assertIsDisplayed()
            val bounds = sheet.fetchSemanticsNode().boundsInRoot
            assertTrue("面板顶部必须在状态栏和额外留白下方", bounds.top >= safeTop + extraGap - 2f)
            assertTrue(bounds.bottom <= windowHeight + 2f)
            compose.onNodeWithText("关闭").assertIsDisplayed()
            compose.onNodeWithText("关联会话").assertIsDisplayed()
            val footer = compose.onNodeWithTag("scheduled-task-history-session-footer").fetchSemanticsNode().boundsInRoot
            assertTrue("底栏材质必须延伸到底部安全区", footer.bottom >= windowHeight - 2f)
            val screenshot = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            val top = (bounds.top + 3).toInt()
            fun brightness(x: Int): Int {
                val pixel = screenshot.getPixel(x, top)
                return (android.graphics.Color.red(pixel) + android.graphics.Color.green(pixel) + android.graphics.Color.blue(pixel)) / 3
            }
            assertTrue("圆角外必须露出遮罩而非整块面板背景", abs(brightness((bounds.left + 3).toInt()) - brightness(bounds.center.x.toInt())) > 24)
            val footerPixel = screenshot.getPixel((footer.left + 4).toInt(), (footer.top + 4).toInt())
            val footerLightness = (android.graphics.Color.red(footerPixel) + android.graphics.Color.green(footerPixel) + android.graphics.Color.blue(footerPixel)) / 3
            assertTrue("底栏材质必须跟随面板深浅色", if (night) footerLightness < 70 else footerLightness > 225)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            File(context.getExternalFilesDir(null), if (night) "history-sheet-dark.png" else "history-sheet-light.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        compose.onNodeWithTag("scheduled-task-history-records").performScrollToIndex(101)
        compose.onNodeWithText("加载更早轨迹").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("older-cursor", loadedCursor) }
        compose.onNodeWithText("关联会话").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("history-session", openedSession); count = 0 }
        compose.onNodeWithText("暂无运行轨迹").assertIsDisplayed()
        val emptyBounds = compose.onNodeWithTag("scheduled-task-history-sheet").fetchSemanticsNode().boundsInRoot
        assertTrue(emptyBounds.top >= safeTop + extraGap - 2f)
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithTag("scheduled-task-history-sheet").assertDoesNotExist()
    }
}
