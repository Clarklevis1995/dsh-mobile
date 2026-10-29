package com.clarklevis.dsh.android.ui

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DshMarkdownCodeCardDeviceTest {
    @get:Rule val locale = ChineseDeviceLocaleRule()
    @get:Rule val compose = createComposeRule()

    @Test
    fun cardsKeepHeadersInsideAndCopyOnlyOriginalCodeWithWhitespace() {
        val code = "    value = \"你好 😀 a\u00a0b\"\n\n\tprintln(value)\n"
        val source = "```kotlin title=example\n$code```\n\n用法：\n\n```bash\necho done\n```"
        var root: View? = null
        compose.setContent {
            root = LocalView.current
            DshTheme { DshMenuHost { DshMarkdownText(source, Modifier.width(340.dp)) } }
        }
        compose.onAllNodesWithTag("markdown-code-card").assertCountEquals(2)
        compose.onAllNodesWithTag("markdown-code-copy").assertCountEquals(2)
        compose.onNodeWithText("KOTLIN").assertIsDisplayed()
        compose.onNodeWithText("BASH").assertIsDisplayed()
        compose.onNodeWithText("复制代码").assertDoesNotExist()
        compose.onNodeWithText("Copy code").assertDoesNotExist()
        val card = compose.onAllNodesWithTag("markdown-code-card")[0].fetchSemanticsNode().boundsInRoot
        val label = compose.onNodeWithText("KOTLIN").fetchSemanticsNode().boundsInRoot
        val copy = compose.onAllNodesWithTag("markdown-code-copy")[0].fetchSemanticsNode().boundsInRoot
        assertTrue(label.left >= card.left && label.top >= card.top)
        assertTrue(copy.right <= card.right && copy.top >= card.top)
        assertTrue(copy.left > label.left)
        compose.onAllNodesWithTag("markdown-code-copy")[0].performClick()
        compose.runOnIdle {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(code, clipboard.primaryClip?.getItemAt(0)?.text.toString())
        }
        compose.runOnIdle { requireNotNull(root?.findCodeScroll()).codeView.performLongClick() }
        compose.onNodeWithText("选择文字").assertIsDisplayed().performClick()
        var selection: DshMarkdownTextView? = null
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withContentDescription("选择文字"))
            .check { view, error ->
                if (error != null) throw error
                selection = view as DshMarkdownTextView
            }
        compose.runOnIdle {
            val view = requireNotNull(selection)
            val plain = markdownSelectionText(view.text, 0, view.text.length)
            assertTrue(plain.contains(code.removeSuffix("\n")))
            assertFalse(plain.contains("复制代码"))
        }
    }

    @Test
    fun longLinesScrollInsideCodeWithoutChangingPagerOrLogicalLines() {
        val code = "def crop(img):\n    return img.crop((left, 0, left + target_w, h)) # " + "保留缩进与长行".repeat(12) + "\n"
        var root: View? = null
        var currentPage = -1
        compose.setContent {
            root = LocalView.current
            DshTheme {
                DshMenuHost {
                    val pager = rememberPagerState(pageCount = { 2 })
                    val scope = rememberCoroutineScope()
                    currentPage = pager.currentPage
                    ConversationPagerChrome(pager, "code", { scope.launch { pager.scrollToPage(it) } }, topBar = {}) { page, inset ->
                        if (page == 0) Column(Modifier.width(340.dp).padding(top = inset + 12.dp)) {
                            DshMarkdownText("```python\n$code```")
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val scroll = requireNotNull(root?.findCodeScroll())
        compose.runOnIdle {
            assertEquals(2, scroll.codeView.layout.lineCount)
            assertTrue(scroll.codeView.width > scroll.width)
        }
        compose.onNodeWithTag("markdown-code-scroll").performTouchInput { swipeLeft() }
        compose.runOnIdle {
            assertTrue("长代码应在卡片内横向滚动", scroll.scrollX > 0)
            assertEquals("代码内滑动不应切换对话页", 0, currentPage)
        }
    }

    @Test
    fun mixedTablesAndCodeKeepOrderAndTheme() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("code-card-theme-test", Context.MODE_PRIVATE)
        val appearance = AppearanceSettings(preferences).apply { selectInterfaceStyle(InterfaceStyle.LIGHT) }
        val source = "前言\n\n```python\ndef greet():\n    return 42\n```\n\n" +
            "| 列 | 值 |\n|---|---|\n| A | B |\n\n    echo unnamed\n\n结尾"
        try {
            compose.setContent {
                DshTheme(appearance) { DshMenuHost { DshMarkdownText(source, Modifier.width(340.dp)) } }
            }
            compose.onAllNodesWithTag("markdown-code-card").assertCountEquals(2)
            compose.onNodeWithTag("markdown-table-card").assertIsDisplayed()
            compose.onNodeWithText("代码").assertIsDisplayed()
            for ((style, background, name) in listOf(
                Triple(InterfaceStyle.LIGHT, 0xFFE9E9EB.toInt(), "light"),
                Triple(InterfaceStyle.DARK, 0xFF2C2C2E.toInt(), "dark")
            )) {
                compose.runOnIdle { appearance.selectInterfaceStyle(style) }
                compose.waitForIdle()
                val image = compose.onAllNodesWithTag("markdown-code-card")[0].captureToImage()
                assertEquals(background, image.toPixelMap()[image.width / 2, 2].toArgb())
                context.cacheDir.resolve("code-card-$name.png").outputStream().use {
                    image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        } finally {
            preferences.edit().clear().commit()
        }
    }

    @Test
    fun nestedCodeKeepsItsListInsetAndUnknownLanguageKeepsCopyVisible() {
        val source = "```python\nprint('top')\n```\n\n- 列表项\n\n    ```verylonglanguagenameforcheckingcopybuttonbounds\n    print('nested')\n    ```\n\n    列表继续"
        compose.setContent { DshTheme { DshMenuHost { DshMarkdownText(source, Modifier.width(280.dp)) } } }
        compose.onAllNodesWithTag("markdown-code-card").assertCountEquals(2)
        val cards = compose.onAllNodesWithTag("markdown-code-card")
        assertTrue(cards[1].fetchSemanticsNode().boundsInRoot.left > cards[0].fetchSemanticsNode().boundsInRoot.left)
        val copy = compose.onAllNodesWithTag("markdown-code-copy")[1]
        copy.assertIsDisplayed()
        assertTrue(copy.fetchSemanticsNode().boundsInRoot.right <= cards[1].fetchSemanticsNode().boundsInRoot.right)
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("列表继续"))
            .check { view, error ->
                if (error != null) throw error
                val text = (view as DshMarkdownTextView).text as android.text.Spanned
                assertTrue(text.getSpans(0, text.length, io.noties.markwon.core.spans.BulletListItemSpan::class.java).isEmpty())
            }
    }

    private fun View.findCodeScroll(): DshCodeScrollView? {
        if (this is DshCodeScrollView) return this
        if (this is ViewGroup) for (index in 0 until childCount) getChildAt(index).findCodeScroll()?.let { return it }
        return null
    }

}
