package com.clarklevis.dsh.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Path
import android.graphics.Typeface
import android.os.Bundle
import android.text.Selection
import android.text.Spannable
import android.text.Spanned
import android.text.TextPaint
import android.text.method.ArrowKeyMovementMethod
import android.text.style.ClickableSpan
import android.text.style.LineHeightSpan
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.appcompat.widget.AppCompatTextView
import com.clarklevis.dsh.android.localization.appText
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.core.CoreProps
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.node.Code
import org.commonmark.node.Document
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension

internal val markdownInteractionParser = Parser.builder()
    .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create()))
    .customBlockParserFactory(DshTableBlockParserFactory())
    .build()

internal fun markdownPlainText(source: String): String = markdownPlainText(markdownInteractionParser.parse(source))

/** 复制直接遍历 AST，避免把表格占位符、代码背景的 NBSP 和复制按钮文案写入剪贴板。 */
internal fun markdownPlainText(node: Node): String {
    fun children(parent: Node, separator: String = ""): String = buildList {
        var child = parent.firstChild
        while (child != null) {
            add(markdownPlainText(child))
            child = child.next
        }
    }.joinToString(separator)
    return when (node) {
        is Text -> node.literal
        is Code -> node.literal
        is FencedCodeBlock -> node.literal
        is IndentedCodeBlock -> node.literal
        is SoftLineBreak, is HardLineBreak -> "\n"
        is HtmlInline, is HtmlBlock, is ThematicBreak -> ""
        is TableRow -> children(node, "\t").trimEnd('\n') + "\n"
        is TableCell -> children(node)
        is Paragraph, is Heading -> children(node) + "\n\n"
        is ListItem -> children(node).trimEnd('\n') + "\n"
        is Document -> children(node).trimEnd('\n')
        else -> children(node)
    }
}

internal fun Context.copyMarkdownText(value: String) {
    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
        .setPrimaryClip(ClipData.newPlainText("DeepSeek", value))
}

internal class DshCodeCopySpan(val code: String) : ClickableSpan() {
    override fun onClick(widget: View) {
        widget.context.copyMarkdownText(code)
        Toast.makeText(widget.context, appText("已复制"), Toast.LENGTH_SHORT).show()
    }

    override fun updateDrawState(ds: TextPaint) {
        ds.color = ds.linkColor
        ds.isUnderlineText = false
        ds.typeface = Typeface.DEFAULT
    }
}

/** 标记整个代码栏，选取正文复制时排除其文案。 */
internal class DshCodeHeaderSpan(private val minimumHeight: Int) : LineHeightSpan {
    override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, v: Int,
                              fm: android.graphics.Paint.FontMetricsInt) {
        val extra = (minimumHeight - (fm.descent - fm.ascent)).coerceAtLeast(0)
        fm.ascent -= extra / 2
        fm.descent += extra - extra / 2
        fm.top = minOf(fm.top, fm.ascent)
        fm.bottom = maxOf(fm.bottom, fm.descent)
    }
}
internal class DshCodePaddingSpan

internal class DshMarkdownCopyPlugin(
    private val buttonHeightPx: Int,
    private val includeCopyButtons: Boolean = true
) : AbstractMarkwonPlugin() {
    override fun configureVisitor(builder: MarkwonVisitor.Builder) {
        builder.on(FencedCodeBlock::class.java) { visitor, node ->
            renderCode(visitor, node, node.info, node.literal)
        }
        builder.on(IndentedCodeBlock::class.java) { visitor, node ->
            renderCode(visitor, node, null, node.literal)
        }
    }

    private fun renderCode(visitor: MarkwonVisitor, node: Node, info: String?, code: String) {
        visitor.blockStart(node)
        val cardStart = visitor.length()
        if (includeCopyButtons) {
            val headerStart = visitor.length()
            visitor.builder().append(info?.substringBefore(' ')?.takeIf { it.isNotBlank() } ?: appText("代码"))
                .append("  ·  ")
            val buttonStart = visitor.length()
            visitor.builder().append(appText("复制代码"))
            visitor.setSpans(buttonStart, DshCodeCopySpan(code))
            visitor.forceNewLine()
            visitor.setSpans(headerStart, DshCodeHeaderSpan(buttonHeightPx))
        }

        val start = visitor.length()
        visitor.builder().append('\u00a0').append('\n')
        visitor.setSpans(start, DshCodePaddingSpan())
        val highlighted = visitor.configuration().syntaxHighlight().highlight(info, code)
        visitor.builder().append(highlighted)
        visitor.ensureNewLine()
        val paddingStart = visitor.length()
        visitor.builder().append('\u00a0')
        visitor.setSpans(paddingStart, DshCodePaddingSpan())
        CoreProps.CODE_BLOCK_INFO.set(visitor.renderProps(), info)
        visitor.setSpansForNodeOptional(node, start)
        // 阅读层将完整范围替换为卡片；原有富文本仍可供原生选择和兼容渲染使用。
        visitor.setSpans(cardStart, DshCodeBlockMarker(
            info.orEmpty().trim().split(Regex("\\s+")).first(), code, highlighted
        ))
        visitor.blockEnd(node)
    }
}

/** 阅读视图只展示消息菜单；独立选择页启用系统选择手柄与复制菜单。 */
internal open class DshSelectableMarkdownView(context: Context) : AppCompatTextView(context) {
    internal var plainTextProvider: (() -> String)? = null
    internal var onSelectText: (() -> Unit)? = null
    internal var isSelectionPage = false
    internal var onSelectionToolbarChanged: ((DshSelectionToolbarAnchor?) -> Unit)? = null
    private var selecting = false
    internal var onMessageMenuRequested: ((androidx.compose.ui.unit.IntOffset?) -> Unit)? = null
    private var touchX = 0f
    private var touchY = 0f
    private var selectionMode: ActionMode? = null
    internal val hasSelectionActionMode: Boolean get() = selectionMode != null
    private val showSelectionToolbar = Runnable { publishSelectionToolbar() }

    init {
        setTextIsSelectable(true)
        setOnLongClickListener {
            if (isSelectionPage) false else {
                showMessageMenu()
                true
            }
        }
        customSelectionActionModeCallback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                selectionMode = mode
                if (isSelectionPage) {
                    // 保留 ActionMode 来管理原生手柄，操作栏由白色毛玻璃浮层绘制。
                    menu.clear()
                    scheduleSelectionToolbar()
                }
                return true
            }
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                if (isSelectionPage) {
                    menu.clear()
                    scheduleSelectionToolbar()
                    return true
                }
                menu.findItem(android.R.id.copy)?.title = appText("复制")
                menu.findItem(android.R.id.selectAll)?.title = appText("全选")
                menu.removeItem(android.R.id.cut)
                menu.removeItem(android.R.id.paste)
                menu.removeItem(android.R.id.shareText)
                menu.removeItem(android.R.id.textAssist)
                return true
            }
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = false
            override fun onDestroyActionMode(mode: ActionMode) {
                selectionMode = null
                removeCallbacks(showSelectionToolbar)
                onSelectionToolbarChanged?.invoke(null)
                selecting = false
                movementMethod = if (isSelectionPage) ArrowKeyMovementMethod.getInstance()
                    else io.noties.markwon.ext.tables.TableAwareMovementMethod.create()
            }
        }
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (isSelectionPage && selectionMode != null) scheduleSelectionToolbar()
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (isSelectionPage && selectionMode != null) scheduleSelectionToolbar()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (isSelectionPage && selectionMode != null) scheduleSelectionToolbar()
    }

    private fun scheduleSelectionToolbar() {
        removeCallbacks(showSelectionToolbar)
        onSelectionToolbarChanged?.invoke(null)
        // 拖动手柄或滚动时先避让，选区稳定后再显示，避免遮住操作位置。
        postDelayed(showSelectionToolbar, 150)
    }

    private fun publishSelectionToolbar() {
        val textLayout = layout ?: return
        val start = minOf(selectionStart, selectionEnd)
        val end = maxOf(selectionStart, selectionEnd)
        if (!isAttachedToWindow || selectionMode == null || start < 0 || end <= start || end > text.length) return
        val path = Path()
        textLayout.getSelectionPath(start, end, path)
        val bounds = RectF()
        path.computeBounds(bounds, true)
        bounds.offset((compoundPaddingLeft - scrollX).toFloat(), (extendedPaddingTop - scrollY).toFloat())
        val visible = Rect()
        if (!getLocalVisibleRect(visible)) return
        // getLocalVisibleRect 包含 View 自身的滚动偏移，先转为实际视口坐标。
        visible.offset(-scrollX, -scrollY)
        if (!bounds.intersect(RectF(visible))) {
            onSelectionToolbarChanged?.invoke(null)
            return
        }
        val location = IntArray(2).also(::getLocationInWindow)
        bounds.offset(location[0].toFloat(), location[1].toFloat())
        visible.offset(location[0], location[1])
        onSelectionToolbarChanged?.invoke(DshSelectionToolbarAnchor(
            Rect().also(bounds::roundOut), visible
        ))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            touchX = event.x
            touchY = event.y
        }
        return super.onTouchEvent(event)
    }

    internal fun resetTextInteraction() {
        removeCallbacks(showSelectionToolbar)
        onSelectionToolbarChanged?.invoke(null)
        onMessageMenuRequested?.invoke(null)
        selectionMode?.finish()
        selecting = false
        (text as? Spannable)?.let(Selection::removeSelection)
    }

    internal fun beginTextSelection() {
        if (selecting || !isAttachedToWindow || text.isEmpty()) return
        onMessageMenuRequested?.invoke(null)
        selecting = true
        movementMethod = ArrowKeyMovementMethod.getInstance()
        requestFocus()
        val textLayout = layout ?: run { selecting = false; return }
        val line = textLayout.getLineForVertical((touchY - totalPaddingTop + scrollY).toInt().coerceAtLeast(0))
        val decoration = (text as? Spanned)?.getSpans(0, text.length, DshSelectionDecorationSpan::class.java)
            ?.firstOrNull { (text as Spanned).getSpanStart(it) == 0 }
        val start = if (isSelectionPage) decoration?.let { (text as Spanned).getSpanEnd(it) } ?: 0
            else textLayout.getLineStart(line)
        val end = if (isSelectionPage) text.indexOf('\n', start).let { if (it < 0) text.length else it }
            else textLayout.getLineEnd(line).coerceAtMost(text.length)
        // 公开的选区操作会同时启用 Editor 的系统手柄和浮动菜单，无需反射隐藏 API。
        performAccessibilityAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, maxOf(start + 1, end).coerceAtMost(text.length))
        })
    }

    override fun onTextContextMenuItem(id: Int): Boolean {
        if (id != android.R.id.copy) return super.onTextContextMenuItem(id)
        val start = minOf(selectionStart, selectionEnd).coerceAtLeast(0)
        val end = maxOf(selectionStart, selectionEnd).coerceAtMost(text.length)
        if (end <= start) return false
        context.copyMarkdownText(markdownSelectionText(text, start, end))
        selectionMode?.finish()
        return true
    }

    private fun showMessageMenu() {
        val location = IntArray(2).also(::getLocationInWindow)
        onMessageMenuRequested?.invoke(androidx.compose.ui.unit.IntOffset(
            location[0] + touchX.toInt(), location[1] + touchY.toInt()))
    }

    override fun onDetachedFromWindow() {
        resetTextInteraction()
        super.onDetachedFromWindow()
    }
}

internal fun markdownSelectionText(text: CharSequence, start: Int, end: Int): String {
    val spans = text as? Spanned ?: return text.subSequence(start, end).toString()
    val excluded = BooleanArray(end - start)
    val decorations = spans.getSpans(start, end, DshCodeHeaderSpan::class.java).toList() +
        spans.getSpans(start, end, DshCodePaddingSpan::class.java).toList() +
        spans.getSpans(start, end, DshSelectionDecorationSpan::class.java).toList()
    decorations.forEach { span ->
        for (index in maxOf(start, spans.getSpanStart(span)) until minOf(end, spans.getSpanEnd(span))) {
            excluded[index - start] = true
        }
    }
    spans.getSpans(start, end, DshInlineCodeSpan::class.java).forEach { span ->
        listOf(spans.getSpanStart(span), spans.getSpanEnd(span) - 1).forEach { index ->
            if (index in start until end && text[index] == '\u00a0') excluded[index - start] = true
        }
    }
    return buildString {
        for (index in start until end) {
            if (!excluded[index - start]) append(if (text[index] == '\u2028') '\n' else text[index])
        }
    }
}
