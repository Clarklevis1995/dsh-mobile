package com.clarklevis.dsh.android.ui

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.clarklevis.dsh.shared.facade.*
import com.clarklevis.dsh.shared.protocol.*
import com.clarklevis.dsh.android.AndroidWorkspaceLocalFile
import com.clarklevis.dsh.android.localization.appText
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WorkspaceChangesDeviceTest {
    @get:Rule val compose = createComposeRule()

    private fun fixture(): SharedWorkspaceChangesSnapshot {
        val files = listOf(
            WorkspaceChangedFile("README.md", "README.md", 1, 1),
            WorkspaceChangedFile("app.js", "app.js", 160, 0)
        )
        val first = WorkspaceChangesDiff("text", "README.md", "README.md", true, true, hunks = listOf(
            WorkspaceDiffHunk(51, 2, 51, 2, listOf(" # 本地工程不应提交绝对路径", "-/promo/onetake-old", "+/promo/onetake-*"))))
        val second = WorkspaceChangesDiff("text", "app.js", "app.js", true, true,
            hunks = listOf(WorkspaceDiffHunk(113, 0, 113, 160, (1..160).map { "+    const line$it = \"保留中文与缩进，并验证长代码自然换行而且不会遮挡下一行\";" })))
        return SharedWorkspaceChangesSnapshot(sessionId = "test", seq = 160, turn = 3, supported = true, connected = true,
            summary = WorkspaceChangesSummary(3, 2, 161, 1, files), selectedIndex = 0, diff = first, rows = first.rows(), previews = listOf(
                WorkspaceFilePreview(0, first, first.rows()), WorkspaceFilePreview(1, second, second.rows())))
    }

    @Test fun inlineDiffAndSheetStayTogetherDuringDragAndBoundaryFlings() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.screenWidthDp < 768)
        var state by mutableStateOf(fixture())
        compose.setContent { DshTheme {
            WorkspaceChangesSheet(state, { index ->
                val preview = state.previews.first { it.index == index }
                state = state.copy(selectedIndex = index, diff = preview.diff, rows = preview.rows)
            }, {}, {})
        } }
        compose.waitForIdle()
        compose.onAllNodesWithText("README.md").onFirst().assertIsDisplayed()
        compose.onNodeWithText("/promo/onetake-*").assertIsDisplayed()
        val page = compose.onNodeWithTag("workspace-file-page").fetchSemanticsNode().boundsInRoot
        val content = compose.onNodeWithTag("workspace-changes-content").fetchSemanticsNode().boundsInRoot
        assertEquals("页数必须相对面板居中", content.center.x, page.center.x, 1f)
        compose.onNodeWithText("上一个文件").assertIsDisplayed()
        compose.onNodeWithText("下一个文件").assertIsDisplayed()
        compose.onNodeWithTag("workspace-file-selector").performClick()
        compose.waitUntil(3_000) { nativeFileItem("app.js") != null }
        screenshot("workspace-review-menu.png")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.waitUntil(3_000) { nativeFileItem("app.js") == null }
        compose.onNodeWithTag("workspace-file-selector").performClick()
        compose.waitUntil(3_000) { nativeFileItem("app.js") != null }
        var item = requireNotNull(nativeFileItem("app.js"))
        while (!item.isClickable && item.parent != null) item = item.parent
        assertTrue(item.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        compose.waitUntil(3_000) { nativeFileItem("app.js") == null }
        compose.onNodeWithText("/promo/onetake-*").assertDoesNotExist()
        val firstRow = compose.onNodeWithTag("workspace-diff-row-1").fetchSemanticsNode().boundsInRoot
        val nextRow = compose.onNodeWithTag("workspace-diff-row-2").fetchSemanticsNode().boundsInRoot
        val text = compose.onNodeWithTag("workspace-diff-text-1").fetchSemanticsNode().boundsInRoot
        assertTrue("正文不可越出所属逻辑行", text.bottom <= firstRow.bottom + 1)
        assertTrue("相邻行不可重叠", nextRow.top >= firstRow.bottom - 1)
        compose.onNodeWithTag("workspace-wrap-lines").performClick()
        compose.waitForIdle()
        assertTrue("关闭换行后每个逻辑行仅占一行", compose.onNodeWithTag("workspace-diff-row-1").fetchSemanticsNode().boundsInRoot.height < firstRow.height)
        val gutterX = compose.onNodeWithTag("workspace-diff-number-1").fetchSemanticsNode().boundsInRoot.left
        compose.onNodeWithTag("workspace-changes-list").performTouchInput { swipeLeft(durationMillis = 300) }
        compose.waitForIdle()
        assertEquals("整个文件横移时行号保持固定", gutterX, compose.onNodeWithTag("workspace-diff-number-1").fetchSemanticsNode().boundsInRoot.left, 1f)
        compose.onNodeWithTag("workspace-wrap-lines").performClick()
        compose.waitForIdle()
        compose.waitForIdle()
        val sheet = compose.onNodeWithTag("workspace-changes-handle", useUnmergedTree = true)
        val header = compose.onNodeWithTag("workspace-changes-header")
        val gap = header.fetchSemanticsNode().boundsInRoot.top - sheet.fetchSemanticsNode().boundsInRoot.top
        val start = sheet.fetchSemanticsNode().boundsInRoot.top
        sheet.performTouchInput { down(center); moveBy(Offset(0f, 100f)) }
        compose.waitForIdle()
        val down = sheet.fetchSemanticsNode().boundsInRoot.top
        assertTrue("面板拖动位置: $start → $down", down > start)
        assertEquals(gap, header.fetchSemanticsNode().boundsInRoot.top - down, 2f)
        sheet.performTouchInput { moveBy(Offset(0f, -40f)) }
        compose.waitForIdle()
        assertTrue(sheet.fetchSemanticsNode().boundsInRoot.top < down)
        assertEquals(gap, header.fetchSemanticsNode().boundsInRoot.top - sheet.fetchSemanticsNode().boundsInRoot.top, 2f)
        sheet.performTouchInput { up() }
        compose.waitForIdle()
        val fixed = header.fetchSemanticsNode().boundsInRoot.top
        val list = compose.onNodeWithTag("workspace-changes-list")
        list.performScrollToIndex(160)
        repeat(4) {
            compose.mainClock.autoAdvance = false
            list.performTouchInput { swipeUp(durationMillis = 90) }
            repeat(24) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                assertEquals(fixed, header.fetchSemanticsNode().boundsInRoot.top, 2f)
            }
            compose.mainClock.autoAdvance = true
        }
        list.performScrollToIndex(0)
        repeat(4) {
            compose.mainClock.autoAdvance = false
            list.performTouchInput { swipeDown(durationMillis = 90) }
            repeat(24) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                assertEquals(fixed, header.fetchSemanticsNode().boundsInRoot.top, 2f)
            }
            compose.mainClock.autoAdvance = true
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "workspace-changes-sheet.png").outputStream().use {
            compose.onNodeWithTag("workspace-changes-content").captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun wideReviewKeepsFileDirectoryVisible() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.screenWidthDp >= 768)
        var state by mutableStateOf(fixture())
        compose.setContent { DshTheme {
            WorkspaceChangesSheet(state, { index ->
                val preview = state.previews.first { it.index == index }
                state = state.copy(selectedIndex = index, diff = preview.diff, rows = preview.rows)
            }, {}, {})
        } }
        compose.waitForIdle()
        compose.onNodeWithTag("workspace-file-selector").assertDoesNotExist()
        compose.onNodeWithTag("workspace-file-1").performClick()
        compose.onNodeWithTag("workspace-file-0").assertIsDisplayed()
        compose.onNodeWithTag("workspace-diff-row-1").assertIsDisplayed()
        screenshot("workspace-review-wide.png")
    }

    @Test fun wrapIconSwitchesWithoutRecreatingList() {
        var state by mutableStateOf(fixture())
        compose.setContent { DshTheme {
            WorkspaceChangesSheet(state, { index ->
                val preview = state.previews.first { it.index == index }
                state = state.copy(selectedIndex = index, diff = preview.diff, rows = preview.rows)
            }, {}, {})
        } }
        compose.onNodeWithText("合并视图").assertDoesNotExist()
        val wide = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.screenWidthDp >= 768
        compose.onNodeWithTag(if (wide) "workspace-file-1" else "workspace-file-next").performClick()
        val list = compose.onNodeWithTag("workspace-changes-list")
        list.performScrollToIndex(60)
        val button = compose.onNodeWithTag("workspace-wrap-lines")
        val originalButton = button.fetchSemanticsNode()
        val originalBounds = originalButton.boundsInRoot
        val originalList = list.fetchSemanticsNode()
        repeat(2) { iteration ->
            button.performClick()
            compose.waitForIdle()
            val currentButton = button.fetchSemanticsNode()
            val currentList = list.fetchSemanticsNode()
            assertEquals("保持同一个按钮", originalButton.id, currentButton.id)
            assertEquals("切换图标保持同一点击区域", originalBounds, currentButton.boundsInRoot)
            compose.onNodeWithText("自动换行").assertDoesNotExist()
            compose.onNodeWithTag(if (iteration == 0) "workspace-wrap-off-icon" else "workspace-wrap-on-icon", useUnmergedTree = true).assertExists()
            assertEquals(if (iteration == 0) "关闭" else "开启", currentButton.config[SemanticsProperties.StateDescription])
            assertEquals("不能重建正文列表", originalList.id, currentList.id)
            assertTrue("切换换行后不能跳回开头", currentList.config[SemanticsProperties.VerticalScrollAxisRange].value() >= 50f)
            screenshot(if (iteration == 0) "workspace-wrap-off.png" else "workspace-wrap-on.png")
        }
    }

    @Test fun filePathOpensCompletePreviewAndReturnsToSelectedDiff() {
        checkFilePathPreview(1f)
    }

    @Test fun longFilePathAtLargeFontSize() {
        checkFilePathPreview(1.5f)
    }

    private fun checkFilePathPreview(fontScale: Float) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = if (fontScale > 1f) "/Users/demo/很长的项目目录/another-long-folder/file-changes-demo" else "/Users/demo/file-changes-demo"
        val suffix = if (fontScale > 1f) "-large" else ""
        val fullFile = File(context.cacheDir, "review-full-app.js").apply {
            writeText("// 完整文件中未修改的内容\nconst original = true;\nconst updated = true;\n")
        }
        var state by mutableStateOf(fixture())
        var preview by mutableStateOf<AndroidWorkspaceLocalFile?>(null)
        var requestedPath: String? = null
        try {
            compose.setContent { DshTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    WorkspaceChangesSheet(state, { index ->
                        val selected = state.previews.first { it.index == index }
                        state = state.copy(selectedIndex = index, diff = selected.diff, rows = selected.rows)
                    }, {}, {}, workspaceRoot = root, openFile = { path ->
                        requestedPath = path
                        preview = AndroidWorkspaceLocalFile(fullFile, "test", path, "app.js", "text/javascript", "preview")
                    })
                    preview?.let { file ->
                        WorkspaceFilePreviewSheet(file, { preview = null }) { padding ->
                            WorkspaceFilePreviewContent(file, {}, padding)
                        }
                    }
                }
            } }
            val wide = context.resources.configuration.screenWidthDp >= 768
            compose.onNodeWithTag(if (wide) "workspace-file-1" else "workspace-file-next").performClick()
            val path = compose.onNodeWithTag("workspace-open-file")
            path.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "$root/app.js"))
            screenshot("workspace-file-path$suffix.png")
            path.performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("workspace-code-document").fetchSemanticsNodes().isNotEmpty() }
            assertEquals("读取保持协议原始路径", "app.js", requestedPath)
            compose.onNodeWithTag("workspace-preview-title").assertTextEquals("app.js")
            screenshot("workspace-full-file-from-diff$suffix.png")
            compose.onNodeWithContentDescription(appText("关闭文件预览")).performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("workspace-file-preview-sheet").fetchSemanticsNodes().isEmpty() }
            path.assertIsDisplayed()
            path.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "$root/app.js"))
            compose.onNodeWithTag("workspace-diff-row-1").assertIsDisplayed()
        } finally { fullFile.delete() }
    }

    private fun nativeFileItem(name: String): android.view.accessibility.AccessibilityNodeInfo? {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val info = automation.serviceInfo
        if (info.flags and android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS == 0) {
            info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            automation.serviceInfo = info
        }
        return automation.rootInActiveWindow?.findAccessibilityNodeInfosByText(name)
            ?.firstOrNull { it.viewIdResourceName?.endsWith("/title") == true }
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // 原生 Popup 的转场不由 Compose 测试时钟驱动，等待首屏转场完成后取图。
        android.os.SystemClock.sleep(300)
        File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
