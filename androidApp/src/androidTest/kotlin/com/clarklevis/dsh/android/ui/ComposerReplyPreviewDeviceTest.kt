package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.clarklevis.dsh.shared.protocol.GatewayTextPart
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ComposerReplyPreviewDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun quotesUseThreeColumnsAndStopGrowingAfterThreeRowsWhileRemainingRemovable() {
        fun references(count: Int) = buildList {
            add(GatewayTextPart("text", "保留正文"))
            repeat(count) { number ->
                add(GatewayTextPart("quote", "引用${number + 1}：" + "这是需要截断的引用内容。".repeat(8)))
                if (number == 1) add(GatewayTextPart("text", "中间正文也保留"))
            }
        }
        var parts by mutableStateOf(references(3))
        var removedIndex = -1
        compose.setContent {
            DshTheme {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    ComposerReplyPreviews(parts) { index ->
                        removedIndex = index
                        parts = parts.filterIndexed { original, _ -> original != index }
                    }
                    Text("正在编辑的正文", Modifier.testTag("body"))
                }
            }
        }
        fun cell(index: Int) = compose.onNodeWithTag("composer-reply-cell-$index").fetchSemanticsNode().boundsInRoot
        val first = cell(1); val second = cell(2); val third = cell(4)
        assertEquals(first.top, second.top, 1f)
        assertEquals(second.top, third.top, 1f)
        assertTrue(first.right < second.left && second.right < third.left)
        assertEquals(first.width, third.width, 1f)
        compose.runOnIdle { parts = references(4) }
        assertTrue(cell(5).top > cell(1).bottom)
        compose.runOnIdle { parts = references(9) }
        val grid = compose.onNodeWithTag("composer-reply-grid")
        val maximumHeight = grid.fetchSemanticsNode().boundsInRoot.height
        val bodyTop = compose.onNodeWithTag("body").fetchSemanticsNode().boundsInRoot.top
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "composer-quote-grid.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.runOnIdle { parts = references(10) }
        assertEquals(maximumHeight, grid.fetchSemanticsNode().boundsInRoot.height, 1f)
        assertEquals(bodyTop, compose.onNodeWithTag("body").fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.runOnIdle { parts = references(12) }
        assertEquals(maximumHeight, grid.fetchSemanticsNode().boundsInRoot.height, 1f)
        compose.onNodeWithTag("composer-reply-cell-1").performScrollTo().assertIsDisplayed()
        val expected = parts.indexOfFirst { it.text.startsWith("引用12：") }
        compose.onNodeWithTag("composer-reply-cell-$expected").performScrollTo().assertIsDisplayed()
        compose.onNode(hasTestTag("composer-reply-remove") and
            hasAnyAncestor(hasTestTag("composer-reply-cell-$expected"))).performClick()
        compose.runOnIdle {
            assertEquals(expected, removedIndex)
            assertEquals(11, parts.count { it.type == "quote" })
            assertEquals(references(12).filter { it.type == "text" }, parts.filter { it.type == "text" })
        }
        assertEquals(bodyTop, compose.onNodeWithTag("body").fetchSemanticsNode().boundsInRoot.top, 1f)
    }

    @Test fun deletingFirstDuplicateRemovesItsCloseAndKeepsSurvivorCloseStationary() {
        var parts by mutableStateOf(List(2) { GatewayTextPart("quote", "完全相同的引用内容") })
        val removed = mutableListOf<Int>()
        compose.setContent {
            DshTheme {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    ComposerReplyPreviews(parts) { index ->
                        removed += index
                        parts = parts.filterIndexed { original, _ -> original != index }
                    }
                    Text("保留正文")
                }
            }
        }
        fun close(id: Long) = compose.onNode(hasTestTag("composer-reply-remove") and
            hasAnyAncestor(hasTestTag("composer-reply-id-$id")))
        val survivor = compose.onNodeWithTag("composer-reply-id-1")
        val originalCloseID = close(1).fetchSemanticsNode().id
        val originalClose = close(1).fetchSemanticsNode().boundsInRoot
        val originalCell = survivor.fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        close(0).performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("composer-reply-id-0").assertDoesNotExist()
        var previousWidth = originalCell.width
        var intermediate = false
        repeat(18) {
            compose.mainClock.advanceTimeByFrame()
            val cell = survivor.fetchSemanticsNode().boundsInRoot
            val button = close(1).fetchSemanticsNode().boundsInRoot
            close(1).assertIsDisplayed()
            assertEquals("存活叉号必须保留同一个节点", originalCloseID, close(1).fetchSemanticsNode().id)
            assertEquals(originalClose.right, button.right, 1f)
            assertEquals(originalClose.top, button.top, 1f)
            assertEquals(originalCell.right, cell.right, 1f)
            assertTrue(button.left >= cell.left && button.right <= cell.right + 1f)
            assertTrue(cell.width >= previousWidth - 1f)
            if (cell.width > originalCell.width + 2 && cell.width < originalCell.width * 1.9f) intermediate = true
            previousWidth = cell.width
        }
        assertTrue("保留 Cell 的宽度应连续变化", intermediate)
        assertTrue(previousWidth > originalCell.width * 1.9f)
        close(1).performClick()
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("composer-reply-preview").assertDoesNotExist()
        assertEquals(listOf(0, 0), removed)
        compose.onNodeWithText("保留正文").assertExists()
        compose.mainClock.autoAdvance = true
    }

    @Test fun shrinkingRowsKeepsTheSurvivingCellAndCloseVisible() {
        var parts by mutableStateOf(List(4) { GatewayTextPart("quote", "引用$it") })
        compose.setContent {
            DshTheme { Column(Modifier.padding(16.dp)) {
                ComposerReplyPreviews(parts) { index -> parts = parts.filterIndexed { original, _ -> original != index } }
            } }
        }
        fun close(id: Long) = compose.onNode(hasTestTag("composer-reply-remove") and
            hasAnyAncestor(hasTestTag("composer-reply-id-$id")))
        val closeID = close(3).fetchSemanticsNode().id
        compose.mainClock.autoAdvance = false
        close(0).performClick()
        repeat(18) {
            compose.mainClock.advanceTimeByFrame()
            close(3).assertIsDisplayed()
            assertEquals(closeID, close(3).fetchSemanticsNode().id)
        }
        compose.mainClock.autoAdvance = true
    }

    @Test fun quotePreviewAnimatesLayoutOnInsertionAndRemoval() {
        var parts by mutableStateOf(emptyList<GatewayTextPart>())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            DshTheme {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    ComposerReplyPreviews(parts, spacingAfter = 12.dp, onRemove = { parts = emptyList() })
                    Text("保留输入正文", Modifier.testTag("body"))
                }
            }
        }
        val initialY = compose.onNodeWithTag("body").fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle { parts = listOf(GatewayTextPart("quote", "需要引用的内容")) }
        compose.mainClock.advanceTimeBy(100)
        val middleY = compose.onNodeWithTag("body").fetchSemanticsNode().boundsInRoot.top
        compose.mainClock.advanceTimeBy(156)
        val expandedY = compose.onNodeWithTag("body").fetchSemanticsNode().boundsInRoot.top
        assertTrue(middleY > initialY && middleY < expandedY)
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        assertEquals((72f + 12f) * density, expandedY - initialY, 1f)
        compose.mainClock.advanceTimeBy(100)
        assertEquals(expandedY, compose.onNodeWithTag("body").fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.onNodeWithTag("composer-reply-remove").performClick()
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("composer-reply-text").assertTextEquals("需要引用的内容")
        val shrinkingY = compose.onNodeWithTag("body").fetchSemanticsNode().boundsInRoot.top
        assertTrue(shrinkingY > initialY && shrinkingY < expandedY)
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("composer-reply-preview").assertDoesNotExist()
        assertEquals(initialY, compose.onNodeWithTag("body").fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.onNodeWithText("保留输入正文").assertExists()
    }

    @Test fun nativeEditorAndHintFollowQuoteHeightOnEveryFrame() {
        var parts by mutableStateOf(emptyList<GatewayTextPart>())
        var body by mutableStateOf("")
        compose.setContent {
            DshTheme {
                Column(Modifier.fillMaxWidth().padding(16.dp).testTag("quote-stack")) {
                    ComposerQuoteStack(parts, visible = true, onRemove = {}) {
                        androidx.compose.foundation.text.BasicTextField(body, onValueChange = { body = it },
                            modifier = Modifier.fillMaxWidth().testTag("native-editor"),
                            decorationBox = { editor ->
                                androidx.compose.foundation.layout.Box {
                                    if (body.isEmpty()) Text("描述你想要构建的内容", Modifier.testTag("hint"))
                                    editor()
                                }
                            })
                    }
                }
            }
        }
        val editor = compose.onNodeWithTag("native-editor")
        val stack = compose.onNodeWithTag("quote-stack")
        val editorID = editor.fetchSemanticsNode().id
        for (draft in listOf("", "保留正在编辑的正文")) {
            compose.runOnIdle { body = draft }
            val baseline = stack.fetchSemanticsNode().boundsInRoot
            val baselineY = editor.fetchSemanticsNode().boundsInRoot.top
            compose.mainClock.autoAdvance = false
            compose.runOnIdle { parts = listOf(GatewayTextPart("quote", "被引用的内容")) }
            var intermediate = 0
            fun assertFrame() {
                val height = stack.fetchSemanticsNode().boundsInRoot.height - baseline.height
                val movement = editor.fetchSemanticsNode().boundsInRoot.top - baselineY
                assertEquals("引用与正文必须逐帧同步", height, movement, 1f)
                if (height > 2f && height < 150f) intermediate++
                if (draft.isEmpty()) {
                    assertEquals(editor.fetchSemanticsNode().boundsInRoot.top,
                        compose.onNodeWithTag("hint", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top, 1f)
                }
            }
            repeat(18) { compose.mainClock.advanceTimeByFrame(); assertFrame() }
            val expanded = stack.fetchSemanticsNode().boundsInRoot.height
            assertTrue(expanded > baseline.height + 80f)
            compose.mainClock.advanceTimeBy(80)
            assertEquals(expanded, stack.fetchSemanticsNode().boundsInRoot.height, 1f)
            compose.runOnIdle { parts = emptyList() }
            repeat(18) { compose.mainClock.advanceTimeByFrame(); assertFrame() }
            assertTrue(intermediate > 0)
            assertEquals(baseline.height, stack.fetchSemanticsNode().boundsInRoot.height, 1f)
            assertEquals(editorID, editor.fetchSemanticsNode().id)
            compose.runOnIdle { assertEquals(draft, body) }
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun footerReplyAppearsBesideCopyAndQuotesPlainText() {
        var quote by mutableStateOf<String?>(null)
        var supportsQuotes by mutableStateOf(true)
        val source = "**需要回复的正文**"
        compose.setContent {
            DshTheme {
                val onReply: ((String) -> Unit)? = if (supportsQuotes) ({ quote = it }) else null
                CompositionLocalProvider(LocalMessageQuote provides onReply) {
                    Column(Modifier.padding(16.dp)) {
                        AssistantMessageActions(source)
                        quote?.let { ComposerReplyPreview(it, onRemove = { quote = null }) }
                        Text("保留输入框正文")
                    }
                }
            }
        }
        val copy = compose.onNodeWithContentDescription(com.clarklevis.dsh.android.localization.appText("复制正文"))
        val reply = compose.onNodeWithTag("message-reply")
        assertTrue(reply.fetchSemanticsNode().boundsInRoot.left > copy.fetchSemanticsNode().boundsInRoot.right)
        reply.performClick()
        compose.onNodeWithTag("composer-reply-text").assertTextEquals("需要回复的正文")
        compose.onNodeWithText("保留输入框正文").assertExists()
        compose.runOnIdle { supportsQuotes = false }
        compose.onNodeWithTag("message-reply").assertDoesNotExist()
        copy.assertExists()
    }

    @Test fun longReplyUsesTwoLinesAndCanBeRemovedWithoutClearingBody() {
        var visible by mutableStateOf(true)
        val body = "请继续解释这个计划"
        val quote = "刚那 10 道已经排成 5 天计划了，每天 2 道，按套路成对、难度递进。".repeat(8)
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxWidth().background(Color.White).padding(16.dp)) {
                    if (visible) ComposerReplyPreview(quote, onRemove = { visible = false })
                    Text(body, Modifier.padding(top = 16.dp))
                }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("composer-reply-text").performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            it(layouts)
        }
        assertEquals(2, layouts.single().lineCount)
        assertTrue(layouts.single().hasVisualOverflow)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.getExternalFilesDir(null), "composer-reply-preview.png").outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithTag("composer-reply-remove").performClick()
        compose.onNodeWithTag("composer-reply-preview").assertDoesNotExist()
        compose.onNodeWithText(body).assertExists()
    }
    @Test fun paragraphMenuCanReplyWithPlainText() {
        var rootView: View? = null
        var quote by mutableStateOf<String?>(null)
        compose.setContent {
            rootView = LocalView.current
            DshTheme {
                CompositionLocalProvider(LocalMessageQuote provides { quote = it }) {
                    DshMenuHost {
                        Column(Modifier.fillMaxWidth().padding(top = 120.dp, start = 24.dp, end = 24.dp)) {
                            DshMarkdownText("**需要回复的正文**", Modifier.testTag("reply-source"))
                            quote?.let { ComposerReplyPreview(it, onRemove = { quote = null }) }
                        }
                    }
                }
            }
        }
        compose.runOnIdle { requireNotNull(rootView?.findMarkdownTextView()).performLongClick() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 3_000)
        compose.onNodeWithText(com.clarklevis.dsh.android.localization.appText("复制")).assertExists()
        compose.onNodeWithText(com.clarklevis.dsh.android.localization.appText("选择文字")).assertExists()
        compose.onNodeWithText(com.clarklevis.dsh.android.localization.appText("回复")).performClick()
        compose.onNodeWithTag("composer-reply-text").assertTextEquals("需要回复的正文")
    }

    private fun View.findMarkdownTextView(): DshMarkdownTextView? {
        if (this is DshMarkdownTextView) return this
        if (this is ViewGroup) for (index in 0 until childCount) {
            getChildAt(index).findMarkdownTextView()?.let { return it }
        }
        return null
    }

}
