package com.clarklevis.dsh.android.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.Spanned
import android.text.style.ClickableSpan
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.clarklevis.dsh.shared.protocol.PresentedFile
import android.text.TextPaint
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.platform.LocalView
import io.noties.markwon.ext.tables.TableRowSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DshMarkdownTextDeviceTest {
    @Test
    fun codeBackgroundsScrollWithTextInBothRenderers() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.runOnIdle {
            val density = context.resources.displayMetrics.density
            val width = (360 * density).toInt()
            val height = (480 * density).toInt()
            val source = "说明 `inline_before` 与普通正文。\n\n" +
                "# 标题\n\n```python\n" + (1..12).joinToString("\n") { "line_$it = $it" } +
                "\n```\n\n后续 `inline_after` 和正文。\n\n" + "继续阅读 `code`。\n\n".repeat(20)
            for (selectionPage in listOf(true, false)) {
                val view = DshMarkdownTextView(context).apply {
                    isSelectionPage = selectionPage
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 17 * density)
                    setPadding((20 * density).toInt(), (16 * density).toInt(),
                        (20 * density).toInt(), (24 * density).toInt())
                    setTextColor(Color.BLACK)
                    includeFontPadding = false
                    isVerticalScrollBarEnabled = false
                    if (selectionPage) {
                        text = renderMarkdownSelectionText(source, textSize, density,
                            DshSelectionStyle.LIGHT_CODE, DshSelectionStyle.LIGHT_ACCENT, Color.GRAY)
                    } else {
                        buildDshMarkwon(context, testPalette()).setMarkdown(this, source)
                    }
                }
                // 通过父 View 实际绘制子 View，让 Android 负责滚动变换，不在测试中手动平移画布。
                val root = android.widget.FrameLayout(context).apply {
                    setBackgroundColor(Color.WHITE)
                    addView(view, android.widget.FrameLayout.LayoutParams(-1, -1))
                    measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    layout(0, 0, width, height)
                }
                fun capture(): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                    root.draw(Canvas(it))
                }
                val initial = capture()
                try {
                    for (distance in listOf(60, 140, 60, 0)) {
                        val scroll = (distance * density).toInt()
                        view.scrollTo(0, scroll)
                        val image = capture()
                        try {
                            for (y in view.totalPaddingTop + 2 until height - view.extendedPaddingBottom - scroll - 2 step 3) {
                                for (x in view.compoundPaddingLeft + 2 until width - view.compoundPaddingRight - 2 step 3) {
                                    assertEquals("滚动后背景必须随正文移动：selectionPage=$selectionPage, scroll=$scroll, x=$x, y=$y",
                                        initial.getPixel(x, y + scroll), image.getPixel(x, y))
                                }
                            }
                            for (y in 0 until view.extendedPaddingTop step 3) {
                                assertEquals("背景不能越过正文上边距", Color.WHITE, image.getPixel(width / 2, y))
                            }
                        } finally { image.recycle() }
                    }
                } finally { initial.recycle() }
            }
        }
    }

    @Test
    fun glassSelectionToolbarKeepsNativeSelectionAndCopiesAfterSelectAll() {
        val source = "说明文字用于检查菜单后方的正文会被模糊。".repeat(8) +
            "\n\n你好 😀 **世界**\n\n```python\n    print('test')\n```" + "\n\n后续正文。".repeat(100)
        compose.setContent { DshTheme { DshMarkdownSelectionPage(source) {} } }
        val view = findSelectionPageTextView()
        compose.waitUntil(5_000) { view.hasSelectionActionMode }
        compose.runOnIdle {
            val start = view.text.toString().indexOf("你好")
            android.text.Selection.setSelection(view.text as android.text.Spannable, start, start + "你好 😀 世界".length)
        }
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("markdown-selection-toolbar")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("markdown-selection-toolbar").assertIsDisplayed()
        saveSelectionScreenshot("selection-white-glass")
        var anchor: DshSelectionToolbarAnchor? = null
        val originalCallback = view.onSelectionToolbarChanged
        compose.runOnIdle {
            view.onSelectionToolbarChanged = { anchor = it; originalCallback?.invoke(it) }
            view.scrollBy(0, 40)
        }
        compose.waitUntil(5_000) { anchor != null }
        val before = requireNotNull(anchor)
        compose.runOnIdle { anchor = null; view.scrollBy(0, 20) }
        compose.waitUntil(5_000) { anchor != null }
        compose.runOnIdle {
            assertEquals("正文滚动不能改变浮层所用的可见视口", before.viewport, requireNotNull(anchor).viewport)
            assertEquals(before.selection.top - 20, requireNotNull(anchor).selection.top)
            view.onSelectionToolbarChanged = originalCallback
        }
        compose.onNodeWithTag("markdown-selection-all").performClick()
        compose.waitUntil(5_000) { view.selectionStart == 0 && view.selectionEnd == view.text.length }
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("markdown-selection-copy")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle { assertTrue("浮层操作不能抢走正文选区的焦点", view.hasFocus()) }
        compose.onNodeWithTag("markdown-selection-copy").performClick()
        compose.runOnIdle {
            assertFalse(view.hasSelectionActionMode)
            val clipboard = view.context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            assertEquals(markdownSelectionText(view.text, 0, view.text.length), clipboard.primaryClip?.getItemAt(0)?.text.toString())
        }
    }

    @Test
    fun selectionCodeAtDocumentStartWrapsWithoutChangingCopiedWhitespace() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.runOnIdle {
            val code = "    value = \"你好 😀\"\n\n\tprint(\"这是一行用于检查窄屏代码折行后复制仍保持原始换行与缩进的说明文字\")\n"
            val text = renderMarkdownSelectionText("```python\n${code}```\n\n- [x] 完成\n- [ ] 待办", 17f, 1f,
                DshSelectionStyle.LIGHT_CODE, DshSelectionStyle.LIGHT_ACCENT, Color.GRAY)
            val view = DshMarkdownTextView(context).apply { isSelectionPage = true; setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 17f); this.text = text }
            view.measure(View.MeasureSpec.makeMeasureSpec(260, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            val start = text.toString().indexOf("\tprint")
            val end = start + code.substringAfter("\tprint").trimEnd('\n').length + "\tprint".length
            assertTrue(view.layout.getLineForOffset(end - 1) > view.layout.getLineForOffset(start))
            assertEquals(code.removeSuffix("\n"), markdownSelectionText(text, 1, 1 + code.length - 1))
            assertTrue(markdownSelectionText(text, 0, text.length).contains("☑ 完成\n☐ 待办"))
            assertFalse(markdownSelectionText(text, 0, text.length).startsWith("\n"))
        }
    }

    @Test
    fun selectionPageUsesSharedTypographyCardsAndBlueSelection() {
        val oldLocale = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.SIMPLIFIED_CHINESE)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("selection-light-palette-test", android.content.Context.MODE_PRIVATE)
        val appearance = AppearanceSettings(preferences).apply { selectInterfaceStyle(InterfaceStyle.LIGHT) }
        try {
            compose.setContent { DshTheme(appearance) { DshMarkdownSelectionPage(selectionParityMarkdown) {} } }
            val view = findSelectionPageTextView()
            compose.waitUntil(5_000) { view.hasSelectionActionMode }
            compose.runOnIdle {
                val value = view.text as Spanned
                val density = view.resources.displayMetrics.density
                assertEquals(17 * density, view.textSize, 1f)
                assertEquals(0x33007AFF, view.highlightColor)
                assertEquals(value.toString().indexOf('\n'), view.selectionEnd)
                val heading = value.toString().indexOf("整体流水线")
                val sizes = value.getSpans(heading, heading + 1, android.text.style.AbsoluteSizeSpan::class.java)
                assertEquals(17 * 1.65f * density, sizes.single().size.toFloat(), 1f)
                val card = value.getSpans(0, value.length, DshSelectionCodeSpan::class.java).single()
                assertEquals("python", card.language)
                assertEquals(DshSelectionStyle.LIGHT_CODE, card.background)
                assertTrue(value.toString().contains("中文、emoji 😀"))
                val plain = markdownSelectionText(value, 0, value.length)
                assertTrue(plain.contains("image = load(\"你好 😀\")\n\n    print(image)"))
                assertFalse(plain.contains("PYTHON"))
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE)
                view.draw(canvas)
                val blankOffset = value.toString().indexOf("\n\n    print") + 1
                val y = view.totalPaddingTop + view.layout.getLineBaseline(view.layout.getLineForOffset(blankOffset))
                assertEquals(DshSelectionStyle.LIGHT_CODE, bitmap.getPixel(view.width / 2, y))
                bitmap.recycle()
            }
            saveSelectionScreenshot("selection-parity-android")
        } finally {
            java.util.Locale.setDefault(oldLocale)
            preferences.edit().clear().commit()
        }
    }

    @Test
    fun selectionPageUsesSameDarkPaletteAsIOS() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("markdown_selection_style_test", android.content.Context.MODE_PRIVATE)
        val appearance = AppearanceSettings(preferences).apply { selectInterfaceStyle(InterfaceStyle.LIGHT) }
        val oldLocale = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.SIMPLIFIED_CHINESE)
        try {
            compose.setContent { DshTheme(appearance) { DshMarkdownSelectionPage(selectionParityMarkdown) {} } }
            val view = findSelectionPageTextView()
            compose.waitUntil(5_000) { view.hasSelectionActionMode }
            val selection = view.selectionStart to view.selectionEnd
            compose.runOnIdle { appearance.selectInterfaceStyle(InterfaceStyle.DARK) }
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals(selection, view.selectionStart to view.selectionEnd)
                assertEquals(Color.WHITE, view.currentTextColor)
                assertEquals(0x330A84FF, view.highlightColor)
                val value = view.text as Spanned
                assertEquals(DshSelectionStyle.DARK_CODE,
                    value.getSpans(0, value.length, DshSelectionCodeSpan::class.java).single().background)
            }
            saveSelectionScreenshot("selection-parity-android-dark")
        } finally {
            java.util.Locale.setDefault(oldLocale)
            preferences.edit().clear().apply()
        }
    }

    private fun saveSelectionScreenshot(name: String) {
        // 前一项复制测试的系统剪贴板提示需先消失，避免遮住当前页面。
        android.os.SystemClock.sleep(4_000)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val file = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "$name.png")
        file.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
    }

    @Test
    fun longPressMenuCopiesWholeMessageAndSelectionPagePreservesReadingLayout() {
        var rootView: View? = null
        val full = "第一段 **加粗**\n\n```kotlin\nfun hello() {\n    println(\"你好\")\n}\n```\n\n" +
            "中间内容。".repeat(400) + "\n\n最后一段 😀"
        compose.setContent {
            rootView = LocalView.current
            DshTheme {
                DshLazyMarkdownText(
                    markdown = "最后一段 😀",
                    modifier = Modifier.padding(top = 120.dp, start = 20.dp, end = 20.dp),
                    copyMarkdown = full
                )
            }
        }
        lateinit var originalText: CharSequence
        var originalHeight = 0
        compose.runOnIdle {
            val view = requireNotNull(rootView?.findMarkdownTextView())
            originalText = view.text
            originalHeight = view.height
            view.performLongClick()
        }
        // 窗口动画由 WindowManager 驱动，Compose 空闲并不代表浮层已经稳定。
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 3_000)
        compose.onNodeWithText(com.clarklevis.dsh.android.localization.appText("复制")).performClick()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 3_000)
        compose.runOnIdle {
            val context = requireNotNull(rootView).context
            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            assertEquals(markdownPlainText(full), clipboard.primaryClip?.getItemAt(0)?.text.toString())
            requireNotNull(rootView?.findMarkdownTextView()).performLongClick()
        }
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 3_000)
        compose.onNodeWithText(com.clarklevis.dsh.android.localization.appText("选择文字")).performClick()
        val selectionView = findSelectionPageTextView()
        compose.waitUntil(5_000) { selectionView.hasSelectionActionMode }
        compose.runOnIdle {
            assertTrue(selectionView.text.startsWith("第一段 加粗"))
            assertTrue(selectionView.text.endsWith("最后一段 😀"))
            assertTrue(!(selectionView.text as Spanned).getSpans(0, selectionView.text.length, DshCodeCopySpan::class.java).any())
            android.text.Selection.setSelection(selectionView.text as android.text.Spannable, 0, selectionView.text.length)
            selectionView.onTextContextMenuItem(android.R.id.copy)
            assertSame(originalText, rootView?.findMarkdownTextView()?.text)
            assertEquals(originalHeight, rootView?.findMarkdownTextView()?.height)
        }
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withContentDescription(
            com.clarklevis.dsh.android.localization.appText("选择文字")
        )).perform(androidx.test.espresso.action.ViewActions.swipeUp())
        compose.runOnIdle { assertTrue("选择页必须能够独立滚动", selectionView.scrollY > 0) }
        compose.onNodeWithTag("markdown-selection-close").performClick()
        compose.runOnIdle {
            assertSame(originalText, rootView?.findMarkdownTextView()?.text)
            assertEquals(originalHeight, rootView?.findMarkdownTextView()?.height)
        }
    }

    @Test
    fun markdownCopyStripsMarkupAndPreservesCodeAndTableContents() {
        val source = "# 标题\n\n**加粗**、`行内代码`与[链接](https://example.com)\n\n" +
            "| 名称 | 值 |\n| --- | --- |\n| 中文 | 😀 |\n\n```kotlin\n  val x = 1\n\n\tprintln(x)\n```"
        val plain = markdownPlainText(source)
        assertTrue(plain.contains("加粗、行内代码与链接"))
        assertTrue(plain.contains("中文\t😀"))
        assertTrue(plain.contains("  val x = 1\n\n\tprintln(x)"))
        assertTrue(!plain.contains("```"))
        assertTrue(!plain.contains("https://example.com"))
    }

    @Test
    fun selectionPageTableCellsRemainSelectableText() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.runOnIdle {
            val rendered = renderMarkdownSelectionText("| 名称 | 值 |\n| --- | --- |\n| 中文 | 😀 |",
                17f, 1f, DshSelectionStyle.LIGHT_CODE, DshSelectionStyle.LIGHT_ACCENT, Color.GRAY)
            assertTrue(rendered.toString().contains("中文\t😀"))
            assertTrue(rendered.getSpans(0, rendered.length, TableRowSpan::class.java).isEmpty())
        }
    }

    @Test
    fun eachCodeBlockCopiesOnlyItsOriginalCodeIncludingWhitespace() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.runOnIdle {
            val markwon = buildDshMarkwon(context, testPalette())
            val first = "  val label = \"a\u00a0b\"\n\n\tprintln(label)\n"
            val second = "print('second')\n"
            val rendered = markwon.toMarkdown("```kotlin\n$first```\n\n~~~python\n$second~~~")
            val buttons = rendered.getSpans(0, rendered.length, DshCodeCopySpan::class.java)
                .sortedBy { rendered.getSpanStart(it) }
            assertEquals(listOf(first, second), buttons.map { it.code })
            val view = DshMarkdownTextView(context)
            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            buttons.forEach { button ->
                button.onClick(view)
                assertEquals(button.code, clipboard.primaryClip?.getItemAt(0)?.text.toString())
            }
            val selected = markdownSelectionText(rendered, 0, rendered.length)
            assertTrue(!selected.contains("复制代码"))
            assertTrue(selected.contains("a\u00a0b"))
        }
    }

    @Test
    fun selectionPageStartsNativeSelectionAndCopiesUnicodeRange() {
        compose.setContent {
            DshTheme { DshMarkdownSelectionPage("你好 😀 **世界**\n\n第二段 `code`。") {} }
        }
        val view = findSelectionPageTextView()
        compose.waitUntil(5_000) { view.hasSelectionActionMode }
        compose.runOnIdle {
            assertTrue("原生选区必须有起点", view.selectionStart >= 0)
            assertTrue("原生选区必须包含内容", view.selectionEnd > view.selectionStart)
            val start = view.text.toString().indexOf("😀")
            android.text.Selection.setSelection(view.text as android.text.Spannable, start, start + "😀 世界".length)
            assertTrue(view.onTextContextMenuItem(android.R.id.copy))
            val clipboard = view.context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            assertEquals("😀 世界", clipboard.primaryClip?.getItemAt(0)?.text.toString())
        }
    }

    private fun findSelectionPageTextView(): DshMarkdownTextView {
        var result: DshMarkdownTextView? = null
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withContentDescription(
            com.clarklevis.dsh.android.localization.appText("选择文字")
        )).check { view, error ->
            if (error != null) throw error
            result = view as DshMarkdownTextView
        }
        return requireNotNull(result)
    }

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun tableFileLinksUseCurrentViewCallbackAfterRebinding() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val opened = mutableListOf<String>()
        compose.runOnIdle {
            val markwon = buildDshMarkwon(context, testPalette())
            val view = DshMarkdownTextView(context)
            view.onOpenFile = { opened += "child-1:$it" }
            markwon.setMarkdown(view, "| 文件 | 描述 |\n| --- | --- |\n| [segments.json](promo/analysis/segments.json) | 交付 |")
            view.measure(
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            val bitmap = Bitmap.createBitmap(800, view.measuredHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val text = view.text as Spanned
            val link = text.getSpans(0, text.length, TableRowSpan::class.java).flatMap { row ->
                val cell = requireNotNull(row.findLayoutForHorizontalOffset(0)).text as Spanned
                cell.getSpans(0, cell.length, ClickableSpan::class.java).toList()
            }.single()
            link.onClick(view)
            view.onOpenFile = { opened += "child-2:$it" }
            link.onClick(view)
            bitmap.recycle()
        }
        assertEquals(listOf("child-1:promo/analysis/segments.json", "child-2:promo/analysis/segments.json"), opened)
    }

    @Test
    fun fileLinksAndKnownCodeUseCurrentCallbackWhileAmbiguousCodeStaysPlain() {
        var rootView: View? = null
        val source = mutableStateOf("s1")
        val opened = mutableListOf<String>()
        var originalText: CharSequence? = null
        val markdown = "[正文](<build/旅游%20攻略.pdf#L24>) `tutorial.pdf` `report.pdf` `unknown.pdf` [网页](https://example.com)"
        compose.setContent {
            rootView = LocalView.current
            val session = source.value
            DshTheme {
                DshLazyMarkdownText(markdown, knownFiles = listOf(
                    PresentedFile("build/tutorial.pdf"), PresentedFile("a/report.pdf"), PresentedFile("b/report.pdf")
                ), onOpenFile = { opened += "$session:$it" })
            }
        }
        fun clickFileReferences() {
            val view = requireNotNull(rootView?.findMarkdownTextView())
            val text = view.text as Spanned
            val spans = text.getSpans(0, text.length, ClickableSpan::class.java)
            val labels = spans.map { text.subSequence(text.getSpanStart(it), text.getSpanEnd(it)).toString() }
            assertEquals(setOf("正文", "tutorial.pdf", "网页"), labels.toSet())
            spans.filterIndexed { index, _ -> labels[index] != "网页" }.forEach { it.onClick(view) }
        }
        compose.runOnIdle {
            clickFileReferences()
            originalText = requireNotNull(rootView?.findMarkdownTextView()).text
            source.value = "s2"
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertSame("只改变会话回调不能重新设置正文和触发测量", originalText, rootView?.findMarkdownTextView()?.text)
            clickFileReferences()
        }
        assertEquals(listOf("s1:build/旅游 攻略.pdf", "s1:build/tutorial.pdf", "s2:build/旅游 攻略.pdf", "s2:build/tutorial.pdf"), opened)
    }

    @Test
    fun knownFileChangesAndCallbackRemovalRebindOnlyAffectedLinks() {
        var rootView: View? = null
        val files = mutableStateOf(listOf(PresentedFile("a/report.pdf")))
        val enabled = mutableStateOf(true)
        val opened = mutableListOf<String>()
        compose.setContent {
            rootView = LocalView.current
            DshTheme {
                DshLazyMarkdownText(
                    "请看 `report.pdf`",
                    knownFiles = files.value,
                    onOpenFile = if (enabled.value) ({ path -> opened += path }) else null
                )
            }
        }
        fun clickSingleLink() {
            val view = requireNotNull(rootView?.findMarkdownTextView())
            val text = view.text as Spanned
            text.getSpans(0, text.length, ClickableSpan::class.java).single().onClick(view)
        }
        compose.runOnIdle {
            clickSingleLink()
            files.value = listOf(PresentedFile("b/report.pdf"))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            clickSingleLink()
            enabled.value = false
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val text = requireNotNull(rootView?.findMarkdownTextView()).text as Spanned
            assertTrue(text.getSpans(0, text.length, ClickableSpan::class.java).isEmpty())
            enabled.value = true
        }
        compose.waitForIdle()
        compose.runOnIdle { clickSingleLink() }
        assertEquals(listOf("a/report.pdf", "b/report.pdf", "b/report.pdf"), opened)
    }

    @Test
    fun gfmTableIsRenderedAsTableSpans() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val markwon = buildDshMarkwon(context, testPalette())
        val markdown = """
            | 优先级 | 主文案 | 感觉 |
            | :--- | :--- | :---: |
            | ⭐ 推荐 | **平安喜乐** | 温暖 |
            | 备选 | ~~旧文案~~ | 克制 |
        """.trimIndent()

        val rendered = markwon.toMarkdown(markdown)
        val tableRows = rendered.getSpans(
            0,
            rendered.length,
            TableRowSpan::class.java
        )

        assertTrue("GFM 表格应生成 TableRowSpan", tableRows.isNotEmpty())
        assertFalse("表格分隔符不应作为普通正文显示", rendered.toString().contains(":---"))
    }

    @Test
    fun historyTablesImmediatelyAfterBoldLabelsRenderAfterLazySplitting() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val markwon = buildDshMarkwon(context, testPalette())
        val markdown = """
            如下（都是实测值，非目测）：

            ## 整体：深灰黑单色调 + 一个蓝色点缀

            **背景与容器**
            | 用途 | 色值 | 占比 |
            |---|---|---|
            | 页面背景（主色，近纯黑带一点冷灰） | `#151517` | 约 67% |
            | 输入卡片背景 | `#2C2C2E` | 约 25% |
            | 卡片内高亮/层次（略微偏离） | `#2D2C2F` / `#2B2C2F` | 约 3% |
            | 全图平均色 | `#1F1F22` | — |
            **文字与图标**
            | 用途 | 色值 |
            |---|---|
            | 主标题「探索未至之境」、工作区名「测试 2」 | `#FFFFFF` 纯白 |
        """.trimIndent()

        val rendered = splitMarkdownForLazyLayout(markdown).map(markwon::toMarkdown)
        val rows = rendered.sumOf {
            it.getSpans(0, it.length, TableRowSpan::class.java).size
        }
        assertEquals("两个历史表格都应渲染，包含表头共七行", 7, rows)
        assertTrue(rendered.joinToString("\n").contains("背景与容器"))
        assertTrue(rendered.joinToString("\n").contains("文字与图标"))
        assertFalse(rendered.joinToString("\n").contains("|---|"))
    }

    @Test
    fun longStreamingAndFinalMarkdownBothKeepTheDocumentTail() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val paragraphs = List(80) { index ->
            "第${index + 1}段：${"这是一段用于验证安卓长文本不会中断的内容。".repeat(8)}"
        }
        val streamingText = paragraphs.joinToString("\n\n")
        var rootView: View? = null
        compose.setContent {
            rootView = LocalView.current
            DshTheme {
                DshStreamingAwareMarkdownText(
                    markdown = streamingText,
                    isStreaming = true
                )
            }
        }
        compose.waitForIdle()
        // Markdown 使用 AndroidView 内的 TextView，正文不在 Compose 的 Text 语义节点中。
        compose.runOnIdle {
            val textView = requireNotNull(rootView?.findMarkdownTextView())
            assertTrue(textView.isShown)
            assertTrue(textView.text.contains(paragraphs.first()))
            assertTrue(textView.text.contains(paragraphs.last()))
            val layout = requireNotNull(textView.layout)
            assertEquals(textView.text.length, layout.getLineEnd(layout.lineCount - 1))
        }

        val markwon = buildDshMarkwon(
            context,
            testPalette()
        )
        val renderedFinal = markwon.toMarkdown(streamingText).toString()
        assertTrue(renderedFinal.contains(paragraphs.first()))
        assertTrue(renderedFinal.contains(paragraphs.last()))
    }

    @Test
    fun inlineCodeUsesRoundedBackgroundAndStillWrapsAcrossLines() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val palette = testPalette()
        val markwon = buildDshMarkwon(context, palette)
        val rendered = markwon.toMarkdown("前缀 `inline-code` 后缀")
        val span = rendered.getSpans(
            0,
            rendered.length,
            DshInlineCodeSpan::class.java
        ).single()
        assertEquals(palette.inlineCodeCornerRadiusPx, span.cornerRadiusPx, 0f)

        val textView = DshMarkdownTextView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
            textSize = 20f
            text = rendered
        }
        textView.measure(
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        textView.layout(0, 0, textView.measuredWidth, textView.measuredHeight)
        val bitmap = Bitmap.createBitmap(
            textView.measuredWidth,
            textView.measuredHeight,
            Bitmap.Config.ARGB_8888
        )
        textView.draw(Canvas(bitmap))

        val spanStart = rendered.getSpanStart(span)
        val spanEnd = rendered.getSpanEnd(span)
        val layout = requireNotNull(textView.layout)
        val codePaint = TextPaint(textView.paint).also(span::updateDrawState)
        val baseline = layout.getLineBaseline(0).toFloat()
        val top = (baseline + codePaint.fontMetrics.ascent - span.verticalPaddingPx)
            .coerceAtLeast(layout.getLineTop(0).toFloat())
        val left = (layout.getPrimaryHorizontal(spanStart) - span.horizontalPaddingPx)
            .coerceAtLeast(0f)
        val right = (layout.getPrimaryHorizontal(spanEnd) + span.horizontalPaddingPx)
            .coerceAtMost(layout.width.toFloat())
        val cornerAlpha = Color.alpha(
            bitmap.getPixel(left.toInt(), top.toInt())
        )
        val topCenterAlpha = Color.alpha(
            bitmap.getPixel(((left + right) / 2f).toInt(), (top + 1f).toInt())
        )
        assertTrue("圆角外侧像素应保持透明", cornerAlpha < 128)
        assertTrue("圆角顶部中央应绘制背景", topCenterAlpha > 128)

        val longRendered = markwon.toMarkdown(
            "命令 `rm /Users/lichaofan/__dsh_approval_demo__.txt` 结束"
        )
        val longSpan = longRendered.getSpans(
            0,
            longRendered.length,
            DshInlineCodeSpan::class.java
        ).single()
        textView.text = longRendered
        textView.measure(
            View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        textView.layout(0, 0, textView.measuredWidth, textView.measuredHeight)
        assertTrue(
            "长行内代码必须保持可换行",
            textView.layout.getLineForOffset(longRendered.getSpanStart(longSpan)) <
                textView.layout.getLineForOffset(longRendered.getSpanEnd(longSpan) - 1)
        )
    }

    private fun View.findMarkdownTextView(): DshMarkdownTextView? {
        if (this is DshMarkdownTextView) return this
        if (this is ViewGroup) {
            for (index in 0 until childCount) {
                getChildAt(index).findMarkdownTextView()?.let { return it }
            }
        }
        return null
    }

    private fun testPalette() = DshMarkdownPalette(
        textColor = 0xFF10131A.toInt(),
        linkColor = 0xFF1478F2.toInt(),
        tableBorderColor = 0x3310131A,
        tableHeaderColor = 0x1410131A,
        tableOddRowColor = 0x0910131A,
        taskOutlineColor = 0x7310131A,
        tableBorderWidthPx = 1,
        tableCellPaddingPx = 8,
        inlineCodeBackgroundColor = 0xFFCBD5E1.toInt(),
        inlineCodeCornerRadiusPx = 10f,
        inlineCodeHorizontalPaddingPx = 8f,
        inlineCodeVerticalPaddingPx = 2f
    )
}

// 与 iOS testSelectionPageSharedVisualFixture 使用相同内容。
private val selectionParityMarkdown = "下面按 **数据怎么流** 和 `palette.py` 两层讲。\n\n" +
    "# 整体流水线\n\n```python\nimage = load(\"你好 😀\")\n\n    print(image)\n```\n\n" +
    "## 常量与字体\n\n- **亮度**：保留粗体和行内 `LUMA`。\n- 中文、emoji 😀 和跨段落选择。"
