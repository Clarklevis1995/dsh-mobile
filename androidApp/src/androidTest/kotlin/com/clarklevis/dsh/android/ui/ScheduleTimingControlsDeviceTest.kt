package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.clarklevis.dsh.shared.protocol.ScheduleTimingDraft
import com.clarklevis.dsh.shared.protocol.ScheduleTargetDraft
import com.clarklevis.dsh.shared.protocol.GatewayWorkspace
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.After
import java.io.File
import java.util.Locale

class ScheduleTimingControlsDeviceTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var previousLocale: Locale
    @Before fun useChineseLabels() { previousLocale = Locale.getDefault(); Locale.setDefault(Locale.SIMPLIFIED_CHINESE) }
    @After fun restoreLocale() { Locale.setDefault(previousLocale) }

    @Test fun destinationModesShowWorkspaceOnlyForNewConversations() {
        var draft by mutableStateOf(ScheduleTargetDraft("session", ""))
        val workspaces = listOf(GatewayWorkspace("b", "/projects/b", "测试项目 B", emptyList(), "", ""))
        compose.setContent {
            DshTheme { ScheduleTheme {
                DshMenuHost {
                    Column(Modifier.width(360.dp).padding(20.dp)) {
                        ScheduleTargetControls(draft, true, workspaces, true) { draft = it }
                    }
                }
            } }
        }
        compose.onNodeWithTag("schedule-workspace-picker").assertDoesNotExist()
        compose.onNodeWithTag("schedule-target-picker").performClick()
        val optionPositions = listOf("当前关联会话", "新建会话并复用", "每次新建会话").map {
            compose.onNode(hasText(it) and hasAnyAncestor(hasTestTag("dsh-selection-popup")),
                useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left
        }
        assertEquals(optionPositions[0], optionPositions[1], 1f)
        assertEquals(optionPositions[1], optionPositions[2], 1f)
        compose.onNodeWithText("新建会话并复用").performClick()
        compose.onNodeWithTag("schedule-workspace-picker").assertIsDisplayed().performClick()
        compose.onNodeWithText("搜索工作区").performTextInput("B")
        compose.onNodeWithText("测试项目 B").performClick()
        compose.runOnIdle { assertEquals(ScheduleTargetDraft("new_once", "/projects/b"), draft) }
        compose.onNodeWithTag("schedule-target-picker").performClick()
        compose.onNodeWithText("每次新建会话").performClick()
        compose.onNodeWithTag("schedule-workspace-picker").assertIsDisplayed().performClick()
        compose.onNodeWithText("使用默认工作区").performClick()
        compose.runOnIdle { assertEquals(ScheduleTargetDraft("new", ""), draft) }
        compose.onNodeWithTag("schedule-target-picker").performClick()
        compose.onNodeWithText("当前关联会话").performClick()
        compose.onNodeWithTag("schedule-workspace-picker").assertDoesNotExist()
    }

    @Test fun repeatMenuWeekdaySelectionAndTimeZoneSearchWorkInNarrowSheet() {
        var draft by mutableStateOf(ScheduleTimingDraft("daily", "1", "2099-01-31", "08:00:37", "Asia/Shanghai", "1,3,5", "31", ""))
        compose.setContent {
            DshTheme { ScheduleTheme {
                DshMenuHost {
                    Column(Modifier.width(360.dp).verticalScroll(rememberScrollState()).padding(20.dp)) {
                        ScheduleTimingControls(draft, true) { draft = it }
                    }
                }
            } }
        }
        val centers = listOf("frequency", "interval", "date", "time", "zone").map {
            compose.onNodeWithTag("schedule-$it-picker").fetchSemanticsNode().boundsInRoot.center.y
        }
        val gaps = centers.zipWithNext { a, b -> b - a }
        gaps.forEach { assertEquals(gaps.first(), it, 1f) }
        compose.onNodeWithTag("schedule-interval-picker").performClick()
        compose.onNodeWithTag("schedule-interval-wheel").assertIsDisplayed()
            .performTouchInput { swipeUp(durationMillis = 600) }
        compose.waitUntil(5000) {
            compose.onNodeWithTag("schedule-interval-wheel").fetchSemanticsNode()
                .config[SemanticsProperties.StateDescription] != "1"
        }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals("1", draft.interval) }
        compose.onNodeWithTag("schedule-interval-picker").performClick()
        compose.onNodeWithTag("schedule-interval-wheel").performTouchInput { swipeUp(durationMillis = 600) }
        compose.waitUntil(5000) {
            compose.onNodeWithTag("schedule-interval-wheel").fetchSemanticsNode()
                .config[SemanticsProperties.StateDescription] != "1"
        }
        compose.onNodeWithText("确定").performClick()
        compose.runOnIdle { assertTrue(draft.interval.toInt() > 1) }
        compose.onNodeWithTag("schedule-frequency-picker").performClick()
        for (label in listOf("不重复", "每分钟", "每小时", "每天", "每周", "每月", "自定义")) {
            assertTrue(compose.onAllNodesWithText(label, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        }
        compose.onNodeWithText("每周", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("schedule-clock-23-小时").assertDoesNotExist()
        compose.onNodeWithTag("schedule-time-picker").performScrollTo().performClick()
        compose.onNodeWithTag("schedule-clock-23-小时").assertExists()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("schedule-clock-23-小时").assertDoesNotExist()
        compose.runOnIdle { assertEquals("08:00:37", draft.time) }
        compose.onNodeWithTag("schedule-time-picker").performClick()
        compose.onNodeWithText("确定").performClick()
        compose.runOnIdle { assertEquals("08:00:37", draft.time) }
        compose.onNodeWithText("周二").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("1,2,3,5", draft.weekdays) }
        compose.onNodeWithTag("schedule-zone-picker").performScrollTo().performClick()
        compose.onNodeWithText("搜索时区").performTextInput("New_York")
        compose.onNodeWithText("America/New_York").performClick()
        compose.runOnIdle { assertEquals("America/New_York", draft.timeZone); assertEquals("08:00:37", draft.time) }
        compose.onNodeWithTag("schedule-frequency-picker").performScrollTo().performClick()
        compose.onNodeWithText("每月", useUnmergedTree = true).performClick()
        compose.onNodeWithText("每月几号").performScrollTo().assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onRoot().captureToImage().let { image ->
            File(context.getExternalFilesDir(null), "schedule-timing-editor.png").outputStream().use {
                image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    @Test fun tappingOutsideEditorClearsFocusWithoutSwallowingButtonAction() {
        var text by mutableStateOf("")
        var clicks = 0
        compose.setContent {
            DshTheme { ScheduleTheme {
                ScheduleKeyboardDismissHost {
                    Column(Modifier.fillMaxSize().padding(20.dp)) {
                        ScheduleFilledField(text, { text = it }, modifier = Modifier.testTag("editor"))
                        androidx.compose.material3.Text("空白区域", Modifier.testTag("outside").padding(20.dp))
                        androidx.compose.material3.TextButton(onClick = { clicks++ }, modifier = Modifier.testTag("action")) {
                            androidx.compose.material3.Text("其他操作")
                        }
                    }
                }
            } }
        }
        compose.onNodeWithTag("editor").performClick().performTextInput("测试正文")
        compose.onNodeWithTag("editor").assertIsFocused().performClick().assertIsFocused()
        compose.onNodeWithTag("outside").performTouchInput { click() }
        compose.onNodeWithTag("editor").assertIsNotFocused()
        compose.onNodeWithTag("editor").performClick().assertIsFocused()
        compose.onNodeWithTag("action").performTouchInput { click() }
        compose.onNodeWithTag("editor").assertIsNotFocused()
        compose.runOnIdle { assertEquals(1, clicks); assertEquals("测试正文", text) }
    }
}
