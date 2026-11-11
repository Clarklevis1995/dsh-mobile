package com.clarklevis.dsh.android.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Density
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.clarklevis.dsh.android.IncomingShare
import com.clarklevis.dsh.shared.domain.SessionSummary
import com.clarklevis.dsh.shared.protocol.GatewayWorkspace
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class IncomingShareSheetDeviceTest {
    @get:Rule val locale = ChineseDeviceLocaleRule()
    @get:Rule val compose = createComposeRule()
    private var opened: String? = null
    private var created: String? = null
    private var creations = 0
    private var dismissed = false
    private val data = ShareDestinations(
        listOf(SessionSummary("a", "移动端分享体验", 6.0, true, false),
            SessionSummary("b", "设计资料整理", 5.0, true, false),
            SessionSummary("c", "旅行规划", 4.0, true, false),
            SessionSummary("d", "滚动性能优化", 3.0, false, false)),
        listOf(GatewayWorkspace("work", "/work", "移动端工程", listOf("a", "d"), "", ""),
            GatewayWorkspace("personal", "/personal", "个人空间", listOf("b", "c"), "", "")))

    private fun mount(dark: Boolean = false, busy: Boolean = false, fontScale: Float = 1f,
                      destinations: ShareDestinations = data, fileCount: Int = 1) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "share-sheet-preview.png")
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(70, 145, 220))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val files = (0 until fileCount).map { index ->
            val copy = File(context.cacheDir, "share-preview-$index.png")
            file.copyTo(copy, overwrite = true)
            Uri.fromFile(copy)
        }
        compose.setContent {
            val config = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                this.fontScale = fontScale
            }
            CompositionLocalProvider(LocalConfiguration provides config) {
                DshTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) { AdaptiveWindowHost {
                    IncomingShareSheet(IncomingShare("test", "https://example.com/research", files),
                        destinations, "work", busy, null, { dismissed = true }, { opened = it }, { created = it; creations++ })
                } } }
            }
        }
        compose.onNodeWithTag("incoming-share-sheet").assertIsDisplayed()
    }

    @Test fun switchWorkspaceSearchAndOpenConversation() {
        mount()
        compose.onNodeWithTag("share-workspace-selector").performClick()
        compose.onNodeWithTag("share-workspace-personal").performClick()
        compose.onNodeWithTag("share-session-a").assertDoesNotExist()
        compose.onNodeWithTag("share-session-search").performTextInput("资料")
        compose.onNodeWithTag("share-session-c").assertDoesNotExist()
        compose.onNodeWithTag("share-session-b").performClick()
        compose.runOnIdle { assertEquals("b", opened); assertFalse(dismissed) }
    }

    @Test fun crossWorkspaceShortcutAndLightScreenshot() {
        mount()
        screenshot("share-sheet-light.png")
        compose.onNodeWithTag("share-recent-b").performClick()
        compose.runOnIdle { assertEquals("b", opened) }
    }

    @Test fun previewBackPreservesWorkspaceAndCreationTargetInDarkMode() {
        mount(dark = true)
        compose.onNodeWithTag("share-workspace-selector").performClick()
        screenshot("share-workspaces-dark.png")
        compose.onNodeWithTag("share-workspace-personal").performClick()
        compose.onNodeWithTag("share-preview").performClick()
        compose.onNodeWithText("https://example.com/research").assertIsDisplayed()
        compose.onNodeWithTag("share-close").performClick()
        screenshot("share-sheet-dark.png")
        compose.onNodeWithTag("share-new").performClick()
        compose.runOnIdle { assertEquals("personal", created); assertFalse(dismissed) }
    }

    @Test fun busyPreventsDuplicateSelectionAndDismissal() {
        mount(busy = true)
        compose.onNodeWithTag("share-new").assertIsNotEnabled()
        compose.onNodeWithTag("share-close").assertIsNotEnabled()
        compose.onNodeWithTag("share-workspace-selector").assertIsNotEnabled()
        compose.onNodeWithTag("share-creating").assertIsDisplayed()
    }

    @Test fun largeTextKeepsWorkspacePickerAndNewConversationReachable() {
        mount(fontScale = 1.5f)
        compose.onNodeWithTag("share-workspace-selector").performScrollTo().performClick()
        compose.onNodeWithTag("share-workspace-__ungrouped__").performScrollTo().performClick()
        compose.onNodeWithTag("share-new").performScrollTo().performClick()
        compose.runOnIdle { assertNull(created); assertEquals(1, creations); assertFalse(dismissed) }
        screenshot("share-sheet-large-text.png")
    }

    @Test fun sessionListFastFlingsAtBothEdgesDoNotMoveSheet() {
        val sessions = (0 until 60).map { index ->
            SessionSummary("long-$index", "会话 $index", (100 - index).toDouble(), false, false)
        }
        mount(destinations = ShareDestinations(sessions,
            listOf(GatewayWorkspace("work", "/work", "移动端工程", sessions.map { it.id }, "", ""))))
        val list = compose.onNodeWithTag("share-destinations-sessions")
        list.performScrollToNode(hasTestTag("share-session-long-59"))
        assertSheetStaysPinnedDuringFlings(list, up = true)
        list.performScrollToIndex(0)
        assertSheetStaysPinnedDuringFlings(list, up = false)
        list.performScrollToNode(hasTestTag("share-session-long-20"))
        compose.onNodeWithTag("share-session-long-20").performClick()
        compose.runOnIdle { assertEquals("long-20", opened); assertFalse(dismissed) }
    }

    @Test fun workspaceListFastFlingsAtBothEdgesDoNotMoveSheet() {
        val workspaces = (0 until 35).map { index ->
            GatewayWorkspace("w$index", "/w$index", "工作区 $index", emptyList(), "", "")
        }
        mount(destinations = ShareDestinations(emptyList(), workspaces))
        compose.onNodeWithTag("share-workspace-selector").performClick()
        val list = compose.onNodeWithTag("share-destinations-workspaces")
        list.performScrollToNode(hasTestTag("share-workspace-__ungrouped__"))
        assertSheetStaysPinnedDuringFlings(list, up = true)
        list.performScrollToIndex(0)
        assertSheetStaysPinnedDuringFlings(list, up = false)
        list.performScrollToNode(hasTestTag("share-workspace-w20"))
        compose.onNodeWithTag("share-workspace-w20").performClick()
        compose.onNodeWithTag("share-new").performClick()
        compose.runOnIdle { assertEquals("w20", created); assertEquals(1, creations) }
    }

    @Test fun previewListFastFlingsStayIndependentAndHandleStillDismissesSheet() {
        mount(fileCount = 20)
        compose.onNodeWithTag("share-preview").performClick()
        val list = compose.onNodeWithTag("share-destinations-preview")
        list.performScrollToIndex(20)
        assertSheetStaysPinnedDuringFlings(list, up = true)
        list.performScrollToIndex(0)
        assertSheetStaysPinnedDuringFlings(list, up = false)
        compose.onNodeWithTag("share-close").performClick()
        compose.onNodeWithTag("share-sheet-handle", useUnmergedTree = true).performTouchInput {
            swipe(start = center, end = Offset(center.x, center.y + 1_800f), durationMillis = 350)
        }
        compose.waitUntil(3_000) { dismissed }
    }

    private fun assertSheetStaysPinnedDuringFlings(list: SemanticsNodeInteraction, up: Boolean) {
        val header = compose.onNodeWithTag("share-sheet-header")
        val handle = compose.onNodeWithTag("share-sheet-handle", useUnmergedTree = true)
        val headerTop = header.fetchSemanticsNode().boundsInRoot.top
        val handleTop = handle.fetchSemanticsNode().boundsInRoot.top
        repeat(4) {
            compose.mainClock.autoAdvance = false
            try {
                list.performTouchInput {
                    if (up) swipeUp(durationMillis = 80) else swipeDown(durationMillis = 80)
                }
                repeat(45) { frame ->
                    compose.mainClock.advanceTimeByFrame()
                    assertEquals("列表到边界后的惯性不能移动标题，帧 $frame", headerTop.toDouble(),
                        header.fetchSemanticsNode().boundsInRoot.top.toDouble(), 1.0)
                    assertEquals("列表到边界后的惯性不能移动拖动条，帧 $frame", handleTop.toDouble(),
                        handle.fetchSemanticsNode().boundsInRoot.top.toDouble(), 1.0)
                }
            } finally {
                compose.mainClock.autoAdvance = true
            }
            compose.waitForIdle()
            compose.runOnIdle { assertFalse(dismissed) }
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), name)
        file.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
    }
}
