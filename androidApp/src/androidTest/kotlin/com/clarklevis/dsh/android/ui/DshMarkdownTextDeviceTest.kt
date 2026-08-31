package com.clarklevis.dsh.android.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.TextPaint
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.noties.markwon.ext.tables.TableRowSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DshMarkdownTextDeviceTest {
    @get:Rule
    val compose = createComposeRule()

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
    fun longStreamingAndFinalMarkdownBothKeepTheDocumentTail() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val paragraphs = List(80) { index ->
            "第${index + 1}段：${"这是一段用于验证安卓长文本不会中断的内容。".repeat(8)}"
        }
        val streamingText = paragraphs.joinToString("\n\n")
        compose.setContent {
            DshTheme {
                DshStreamingAwareMarkdownText(
                    markdown = streamingText,
                    isStreaming = true
                )
            }
        }
        compose.onNodeWithText(streamingText).assertIsDisplayed()

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
