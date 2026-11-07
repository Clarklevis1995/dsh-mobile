package com.clarklevis.dsh.android.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.clarklevis.dsh.shared.protocol.ScheduleTimingDraft
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CalendarPickerDeviceTest {
    @get:Rule val locale = ChineseDeviceLocaleRule()
    @get:Rule val compose = createComposeRule()

    @Test fun logCalendarKeepsSystemSheetAndBlueSelectionInBothThemes() {
        val now = System.currentTimeMillis()
        val day = java.util.Calendar.getInstance().apply { timeInMillis = now }.get(java.util.Calendar.DAY_OF_MONTH)
        var dark by mutableStateOf(false)
        compose.setContent {
            val config = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            CompositionLocalProvider(LocalConfiguration provides config) {
                DshTheme { ScheduleTheme { DshMenuHost {
                    LogDateTimeSheet("start-date", logHourStart(now), now - 24 * LOG_HOUR_MILLIS, {}, {})
                } } }
            }
        }
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark = night }
            compose.onNodeWithText("开始日期").assertIsDisplayed()
            compose.onNodeWithText("灰色日期不在可导出的日志范围内").assertIsDisplayed()
            val sheet = compose.onNodeWithTag("log-editor").fetchSemanticsNode().boundsInRoot
            assertEquals("日志面板两侧不内缩", 0f, sheet.left, 1f)
            val selected = compose.onNodeWithTag("log-calendar-day-$day").assertIsSelected().fetchSemanticsNode().boundsInRoot
            val screenshot = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            val background = screenshot.getPixel((sheet.left + 5).toInt(), (sheet.top + 160).toInt())
            assertTrue("日志面板背景跟随深浅色", if (night) android.graphics.Color.red(background) < 40 else android.graphics.Color.red(background) > 245)
            val blue = screenshot.getPixel((selected.left + selected.width * 0.18f).toInt(), selected.center.y.toInt())
            assertTrue("日志选中日期必须是蓝色", android.graphics.Color.blue(blue) > 200 && android.graphics.Color.red(blue) < 80)
            var hasWhiteDate = false
            for (y in (selected.center.y - selected.height * 0.2f).toInt()..(selected.center.y + selected.height * 0.2f).toInt()) {
                for (x in (selected.center.x - selected.width * 0.2f).toInt()..(selected.center.x + selected.width * 0.2f).toInt()) {
                    if (android.graphics.Color.red(screenshot.getPixel(x, y)) > 220) hasWhiteDate = true
                }
            }
            assertTrue("蓝色选中日期在夜间也使用白色文字", hasWhiteDate)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            File(context.getExternalFilesDir(null), if (night) "log-calendar-shared-dark.png" else "log-calendar-shared-light.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    @Test fun taskDateUsesAnchoredCalendarAndYearWheelWithoutTimeZoneDrift() {
        var dark by mutableStateOf(false)
        var draft by mutableStateOf(ScheduleTimingDraft("daily", "1", "2040-02-28", "08:00:00.000", "Pacific/Kiritimati", "1", "28", ""))
        compose.setContent {
            val config = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            CompositionLocalProvider(LocalConfiguration provides config) {
                DshTheme { ScheduleTheme { DshMenuHost {
                    Box(Modifier.fillMaxSize().background(androidx.compose.material3.MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.width(390.dp).padding(20.dp)) {
                        ScheduleTimingControls(draft, true) { draft = it }
                    }
                    }
                } } }
            }
        }
        compose.onNodeWithTag("schedule-date-picker").performClick()
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark = night }
            compose.onNodeWithTag("dsh-date-calendar").assertIsDisplayed()
            compose.onNodeWithTag("dsh-date-calendar-day-28").assertIsSelected()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            File(context.getExternalFilesDir(null), if (night) "task-calendar-dark.png" else "task-calendar-light.png").outputStream().use {
                checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()).compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        compose.onNodeWithTag("dsh-date-calendar-month-toggle").performClick()
        compose.onNodeWithTag("dsh-date-calendar-year-picker-list").performScrollToIndex(2043)
        compose.waitUntil(5000) {
            compose.onNodeWithTag("dsh-date-calendar-year-picker").fetchSemanticsNode()
                .config[SemanticsProperties.StateDescription] == "2044"
        }
        compose.onNodeWithTag("dsh-date-calendar-month-toggle").performClick()
        compose.onNodeWithText("2044年2月").assertIsDisplayed()
        compose.onNodeWithTag("dsh-date-calendar-day-29").performClick()
        compose.onNodeWithTag("dsh-date-calendar").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("2044-02-29", draft.date)
            assertEquals("Pacific/Kiritimati", draft.timeZone)
        }
        compose.onNodeWithTag("schedule-date-picker").performClick()
        compose.onNodeWithTag("dsh-date-calendar-day-29").assertIsSelected()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithTag("dsh-date-calendar").assertDoesNotExist()
        compose.runOnIdle { assertEquals("2044-02-29", draft.date) }
        val tomorrow = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_MONTH, 1) }
        compose.runOnIdle {
            dark = false
            draft = draft.copy(date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(tomorrow.time),
                timeZone = TimeZone.getDefault().id)
        }
        compose.onNodeWithTag("schedule-date-picker").performClick()
        compose.onNodeWithTag("dsh-date-calendar-day-${tomorrow.get(java.util.Calendar.DAY_OF_MONTH)}").assertIsSelected()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "task-calendar-adjacent.png").outputStream().use {
            checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()).compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun commonCalendarDisablesOutOfRangeDaysAcrossMonthAndLeapDay() {
        val zone = TimeZone.getTimeZone("UTC")
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zone }
        fun date(value: String) = format.parse(value)!!.time
        var selected by mutableLongStateOf(date("2024-02-28"))
        compose.setContent {
            DshTheme { ScheduleTheme {
                Column(Modifier.width(320.dp).padding(12.dp)) {
                    DshCalendar(selected, date("2024-02-28"), date("2024-03-02"), zone) { selected = it }
                }
            } }
        }
        compose.onNodeWithTag("dsh-calendar-day-27").assertIsNotEnabled()
        compose.onNodeWithTag("dsh-calendar-day-29").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("2024-02-29", format.format(selected)) }
        compose.onNodeWithContentDescription("上个月").assertIsNotEnabled()
        compose.onNodeWithContentDescription("下个月").performClick()
        compose.onNodeWithTag("dsh-calendar-day-3").assertIsNotEnabled()
        compose.onNodeWithContentDescription("下个月").assertIsNotEnabled()
        compose.onNodeWithTag("dsh-calendar-day-1").performClick()
        compose.runOnIdle { assertEquals("2024-03-01", format.format(selected)) }
    }
}
