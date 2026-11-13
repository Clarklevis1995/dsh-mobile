package com.clarklevis.dsh.android.ui

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.clarklevis.dsh.shared.facade.*
import com.clarklevis.dsh.shared.protocol.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WorkspaceChangesDeviceTest {
    @get:Rule val compose = createComposeRule()

    private fun fixture(): SharedWorkspaceChangesSnapshot {
        val files = listOf(
            WorkspaceChangedFile(".gitignore", ".gitignore", 1, 1),
            WorkspaceChangedFile("src/ConversationView.kt", "src/ConversationView.kt", 160, 0)
        )
        val first = WorkspaceChangesDiff("text", ".gitignore", ".gitignore", true, true, hunks = listOf(
            WorkspaceDiffHunk(51, 2, 51, 2, listOf(" # 本地工程不应提交绝对路径", "-/promo/onetake-old", "+/promo/onetake-*"))))
        val second = WorkspaceChangesDiff("text", "src/ConversationView.kt", "src/ConversationView.kt", true, true,
            hunks = listOf(WorkspaceDiffHunk(113, 0, 113, 160, (1..160).map { "+    val line$it = \"保留中文与缩进\"" })))
        return SharedWorkspaceChangesSnapshot(sessionId = "test", seq = 160, turn = 3, supported = true, connected = true,
            summary = WorkspaceChangesSummary(3, 2, 161, 1, files), previews = listOf(
                WorkspaceFilePreview(0, first, first.rows()), WorkspaceFilePreview(1, second, second.rows())))
    }

    @Test fun inlineDiffAndSheetStayTogetherDuringDragAndBoundaryFlings() {
        var state by mutableStateOf(fixture())
        compose.setContent { DshTheme {
            WorkspaceChangesSheet(state, { state = state.copy(selectedIndex = it) }, {}, {})
        } }
        compose.waitForIdle()
        compose.onNodeWithText(".gitignore").assertIsDisplayed()
        compose.onNodeWithText("+/promo/onetake-*").assertIsDisplayed()
        compose.onNodeWithText("ConversationView.kt").performClick()
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
        list.performScrollToIndex(165)
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
}
