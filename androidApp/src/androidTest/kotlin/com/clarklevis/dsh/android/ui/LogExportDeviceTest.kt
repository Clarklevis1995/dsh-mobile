package com.clarklevis.dsh.android.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.platform.app.InstrumentationRegistry
import com.clarklevis.dsh.android.platform.HourlyLogStore
import com.clarklevis.dsh.android.platform.LocalAppLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.zip.ZipInputStream

class LogExportDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rangeEntryOpensSeparateDateAndTimeSheetsAndCancelKeepsRange() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = Locale.getDefault()
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        val now = System.currentTimeMillis()
        val yesterday = now - 24 * LOG_HOUR_MILLIS
        HourlyLogStore(File(context.filesDir, "diagnostic-logs")).append("log export UI test", Date(yesterday))
        LocalAppLog.start(context)
        try {
            val appearance = AppearanceSettings(context.getSharedPreferences("log-export-ui-test", Context.MODE_PRIVATE))
            compose.setContent { DshTheme(appearance) { LogExportScreen {} } }
            compose.onNodeWithTag("log-save").assertIsDisplayed()
            compose.onNodeWithTag("log-share").assertIsDisplayed()
            val save = compose.onNodeWithTag("log-save").fetchSemanticsNode().boundsInRoot
            val share = compose.onNodeWithTag("log-share").fetchSemanticsNode().boundsInRoot
            assertEquals(save.width, save.height, 1f)
            assertEquals(save.width, share.width, 1f)
            assertTrue(
                save.top <
                    compose
                        .onNodeWithTag("log-range-picker")
                        .fetchSemanticsNode()
                        .boundsInRoot.top,
            )
            compose.onNodeWithText("1小时").assertIsSelected()
            compose.onNodeWithText("导出后选择保存位置或分享给他人").assertDoesNotExist()
            compose.onNodeWithText("起止时间").assertDoesNotExist()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("正在统计日志…").fetchSemanticsNodes().isEmpty() }
            verifyExportAndShare(context)
            capture(context, "log-export-main")
            val time = logTimeText(logHourStart(now))
            compose.onNodeWithTag("log-start-time").assertTextEquals(time)
            compose.onNodeWithTag("log-start-date").performClick()
            compose.onNodeWithText("开始日期").assertIsDisplayed()
            compose.onNodeWithTag("log-calendar").assertIsDisplayed()
            capture(context, "log-export-calendar")
            val day = Calendar.getInstance().apply { timeInMillis = yesterday }.get(Calendar.DAY_OF_MONTH)
            // 测试数据覆盖昨天，选中日期应保留原来的整点小时。
            if (day < Calendar.getInstance().get(Calendar.DAY_OF_MONTH)) {
                compose.onNodeWithTag("log-calendar-day-$day").performClick()
                compose.onNodeWithText("完成").performClick()
                compose.onNodeWithTag("log-start-date").assertTextEquals(logDateText(yesterday))
            } else {
                compose.onNodeWithText("取消").performClick()
            }
            compose.onNodeWithTag("log-start-time").performClick()
            compose.onNodeWithText("开始时间").assertIsDisplayed()
            compose.onNodeWithTag("log-native-hour-picker").assertIsDisplayed()
            compose.onNodeWithText("上下滑动选择整点").assertIsDisplayed()
            compose.onNodeWithText("上下滑动选择小时和分钟").assertDoesNotExist()
            capture(context, "log-export-time")
            compose.onNodeWithText("取消").performClick()
            compose.onNodeWithTag("log-start-time").assertTextEquals(time)
            compose.onNodeWithTag("log-end-time").performClick()
            compose.onNodeWithTag("log-native-hour-picker-list").performScrollToIndex(1)
            compose.waitForIdle()
            compose.onNodeWithText("完成").performClick()
            compose.onNodeWithTag("log-end-time").assertTextEquals("01:00")
            compose.onNodeWithText("自定义").assertIsSelected()
            compose.onNodeWithTag("log-share").assertIsDisplayed()
        } finally {
            Locale.setDefault(original)
        }
    }

    private fun verifyExportAndShare(context: Context) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val launched = ConcurrentLinkedQueue<Intent>()
        val monitor =
            object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                    if (intent.action in listOf(Intent.ACTION_CREATE_DOCUMENT, Intent.ACTION_CHOOSER)) {
                        launched.add(intent)
                        return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                    }
                    return null
                }
            }
        instrumentation.addMonitor(monitor)
        try {
            compose.onNodeWithTag("log-save").performClick()
            compose.waitUntil(5_000) { launched.any { it.action == Intent.ACTION_CREATE_DOCUMENT } }
            assertEquals("application/zip", launched.first { it.action == Intent.ACTION_CREATE_DOCUMENT }.type)
            compose.waitForIdle()
            compose.onNodeWithTag("log-share").performClick()
            compose.waitUntil(5_000) { launched.any { it.action == Intent.ACTION_CHOOSER } }
            @Suppress("DEPRECATION")
            val send = checkNotNull(launched.first { it.action == Intent.ACTION_CHOOSER }.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("application/zip", send.type)
            assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            @Suppress("DEPRECATION")
            val uri = checkNotNull(send.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))
            ZipInputStream(context.contentResolver.openInputStream(uri)).use { zip ->
                var text = ""
                while (zip.nextEntry != null) text += zip.readBytes().toString(Charsets.UTF_8)
                assertTrue(text.contains("INFO App started"))
                assertTrue(!text.contains("log export UI test")) // 昨天的记录不应混入当前小时。
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun capture(
        context: Context,
        name: String,
    ) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        // 系统截图等待底部面板动画提交到 Surface，避免截到上一帧。
        android.os.SystemClock.sleep(300)
        checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()).let { bitmap ->
            File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
