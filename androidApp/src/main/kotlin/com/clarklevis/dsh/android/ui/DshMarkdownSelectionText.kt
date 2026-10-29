package com.clarklevis.dsh.android.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TabStopSpan
import android.text.style.TypefaceSpan
import com.clarklevis.dsh.android.localization.appText
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import kotlin.math.roundToInt

/** 与 iOS renderSelectableText 使用同一套尺寸，避免继承阅读页 Markwon 的标题线和块间距。 */
internal object DshSelectionStyle {
    const val BODY_SIZE = 17
    const val HORIZONTAL_INSET = 20
    const val TOP_INSET = 16
    const val BOTTOM_INSET = 24
    const val PARAGRAPH_SPACING = 10
    const val CODE_HEADER = 34
    const val CODE_PADDING = 12
    const val CODE_RADIUS = 9
    const val LIGHT_ACCENT = 0xFF007AFF.toInt()
    const val DARK_ACCENT = 0xFF0A84FF.toInt()
    const val LIGHT_CODE = 0xFFEBEBED.toInt()
    const val DARK_CODE = 0xFF2C2C2E.toInt()
}

internal class DshSelectionDecorationSpan
internal class DshSelectionCodeSpan(val language: String, val background: Int, val labelColor: Int)
internal class DshSelectionInlineCodeSpan(val size: Float, val background: Int)

/** 装饰行只占布局高度，段距附着在段落末行，不通过额外空段落撑高正文。 */
private class SelectionLineHeight(private val extra: Int = 0, private val fixed: Int? = null) : LineHeightSpan {
    override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, v: Int,
                              fm: Paint.FontMetricsInt) {
        if (fixed != null) {
            fm.ascent = -fixed
            fm.descent = 0
            fm.top = fm.ascent
            fm.bottom = 0
        } else if (text is Spanned && end >= text.getSpanEnd(this)) {
            fm.descent += extra
            fm.bottom = maxOf(fm.bottom, fm.descent)
        }
    }
}

internal fun renderMarkdownSelectionText(source: String, fontSizePx: Float, density: Float,
                                         codeBackground: Int, linkColor: Int, labelColor: Int): Spanned {
    val output = SpannableStringBuilder()
    fun span(value: Any, start: Int, end: Int = output.length) {
        if (end > start) output.setSpan(value, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    fun paragraphStart(): Int = output.lastIndexOf('\n', (output.length - 2).coerceAtLeast(0)).let { it + 1 }
    fun finishBlock(extra: Int = DshSelectionStyle.PARAGRAPH_SPACING) {
        if (output.isEmpty()) return
        output.append('\n')
        span(SelectionLineHeight(extra = (extra * density).roundToInt()), paragraphStart())
    }
    fun children(node: Node, visit: (Node) -> Unit) {
        var child = node.firstChild
        while (child != null) { visit(child); child = child.next }
    }
    fun inline(node: Node) {
        val start = output.length
        when (node) {
            is Text -> output.append(node.literal)
            is Code -> {
                output.append(node.literal)
                span(TypefaceSpan("monospace"), start)
                span(AbsoluteSizeSpan((fontSizePx * .86f).roundToInt()), start)
                span(DshSelectionInlineCodeSpan(fontSizePx * .86f, codeBackground), start)
            }
            is SoftLineBreak -> output.append(' ')
            is HardLineBreak -> output.append('\u2028')
            is HtmlInline -> Unit
            else -> {
                children(node, ::inline)
                when (node) {
                    is StrongEmphasis -> span(StyleSpan(Typeface.BOLD), start)
                    is Emphasis -> span(StyleSpan(Typeface.ITALIC), start)
                    is Strikethrough -> span(StrikethroughSpan(), start)
                    is Link -> span(ForegroundColorSpan(linkColor), start)
                }
            }
        }
    }
    fun code(language: String, literal: String) {
        val start = output.length
        output.append('\n')
        span(DshSelectionDecorationSpan(), start)
        span(AbsoluteSizeSpan(1), start)
        span(SelectionLineHeight(fixed = (DshSelectionStyle.CODE_HEADER * density).roundToInt()), start)
        val codeStart = output.length
        val displayedCode = literal.removeSuffix("\n").ifEmpty { "\n" }
        val dark = androidx.core.graphics.ColorUtils.calculateLuminance(codeBackground) < 0.5
        output.append(DshCodeSyntaxHighlighter.highlight(displayedCode, language, dark))
        finishBlock(DshSelectionStyle.CODE_PADDING + DshSelectionStyle.PARAGRAPH_SPACING)
        span(TypefaceSpan("monospace"), codeStart)
        // 先按逻辑字号取整，与 iOS 的 14pt 等宽代码一致。
        span(AbsoluteSizeSpan((kotlin.math.round(fontSizePx / density * .8f) * density).roundToInt()), codeStart)
        span(LeadingMarginSpan.Standard((DshSelectionStyle.CODE_PADDING * density).roundToInt()), codeStart)
        span(DshSelectionCodeSpan(language, codeBackground, labelColor), start)
    }
    fun block(node: Node) {
        when (node) {
            is Paragraph -> { children(node, ::inline); finishBlock() }
            is Heading -> {
                val start = output.length
                children(node, ::inline)
                finishBlock()
                span(StyleSpan(Typeface.BOLD), start)
                span(AbsoluteSizeSpan((fontSizePx * when (node.level) { 1 -> 1.65f; 2 -> 1.35f; else -> 1.1f }).roundToInt()), start)
            }
            is FencedCodeBlock -> code(node.info.orEmpty().trim().split(Regex("\\s+")).first(), node.literal)
            is IndentedCodeBlock -> code("", node.literal)
            is BulletList -> children(node) { item ->
                val start = output.length
                output.append("• ")
                children(item, ::block)
                val marker = output.subSequence(start, minOf(start + 6, output.length)).toString()
                if (marker == "• [ ] " || marker == "• [x] " || marker == "• [X] ") {
                    output.replace(start, start + 6, if (marker == "• [ ] ") "☐ " else "☑ ")
                }
            }
            is OrderedList -> {
                var number = node.startNumber
                children(node) { item -> output.append("${number++}. "); children(item, ::block) }
            }
            is TableRow -> {
                val start = output.length
                children(node, ::block)
                output.append('\n')
                for (column in 1..output.subSequence(start, output.length).count { it == '\t' }) {
                    span(TabStopSpan.Standard((column * 56 * density).roundToInt()), start)
                }
            }
            is TableCell -> { children(node, ::inline); if (node.next != null) output.append('\t') }
            is HtmlBlock, is ThematicBreak -> Unit
            else -> children(node, ::block)
        }
    }
    block(markdownInteractionParser.parse(source))
    if (output.endsWith("\n")) output.delete(output.length - 1, output.length)
    return output
}

/** 卡片背景独立于文字绘制，系统选区仍可跨过空行、折行和多个代码块。 */
internal fun DshMarkdownTextView.drawSelectionCodeCards(canvas: Canvas) {
    val spanned = text as? Spanned ?: return
    val textLayout = layout ?: return
    val density = resources.displayMetrics.density
    withTextBackgroundCanvas(canvas) {
        spanned.getSpans(0, spanned.length, DshSelectionCodeSpan::class.java).forEach { card ->
            val start = spanned.getSpanStart(card)
            val end = spanned.getSpanEnd(card)
            if (end <= start) return@forEach
            val first = textLayout.getLineForOffset(start)
            val last = textLayout.getLineForOffset(end - 1)
            val codePaint = TextPaint(paint).apply { typeface = Typeface.MONOSPACE; textSize = kotlin.math.round(textSize / density * .8f) * density }
            val rect = RectF(0f, textLayout.getLineTop(first).toFloat(), textLayout.width.toFloat(),
                textLayout.getLineBaseline(last) + codePaint.fontMetrics.descent + DshSelectionStyle.CODE_PADDING * density)
            val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = card.background }
            canvas.drawRoundRect(rect, DshSelectionStyle.CODE_RADIUS * density, DshSelectionStyle.CODE_RADIUS * density, backgroundPaint)
            val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = card.labelColor; typeface = Typeface.MONOSPACE; textSize = 11 * density
            }
            canvas.drawText(card.language.ifEmpty { appText("代码") }.uppercase(java.util.Locale.ROOT),
                DshSelectionStyle.CODE_PADDING * density, rect.top + 10 * density - labelPaint.fontMetrics.ascent, labelPaint)
        }
        // BackgroundColorSpan 会把段距也涂成背景，改为只绘制等宽字形所在的区域。
        spanned.getSpans(0, spanned.length, DshSelectionInlineCodeSpan::class.java).forEach { inline ->
            val start = spanned.getSpanStart(inline)
            val end = spanned.getSpanEnd(inline)
            val codePaint = TextPaint(paint).apply { typeface = Typeface.MONOSPACE; textSize = inline.size }
            val metrics = codePaint.fontMetrics
            val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = inline.background }
            for (line in textLayout.getLineForOffset(start)..textLayout.getLineForOffset(end - 1)) {
                val left = textLayout.getPrimaryHorizontal(maxOf(start, textLayout.getLineStart(line)))
                val right = textLayout.getPrimaryHorizontal(minOf(end, textLayout.getLineEnd(line)))
                val baseline = textLayout.getLineBaseline(line)
                canvas.drawRect(minOf(left, right), baseline + metrics.ascent,
                    maxOf(left, right), baseline + metrics.descent, backgroundPaint)
            }
        }
    }
}
