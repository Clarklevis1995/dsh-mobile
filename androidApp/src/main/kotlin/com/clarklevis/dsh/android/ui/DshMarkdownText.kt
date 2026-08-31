package com.clarklevis.dsh.android.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.Spanned
import android.text.SpannedString
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import android.text.style.MetricAffectingSpan
import android.util.TypedValue
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.appcompat.widget.AppCompatTextView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import io.noties.markwon.core.CorePlugin
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.core.spans.CodeSpan
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TableAwareMovementMethod
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tables.TableTheme
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.movement.MovementMethodPlugin
import org.commonmark.node.Code
import org.commonmark.node.ThematicBreak
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

internal data class DshMarkdownPalette(
    val textColor: Int,
    val linkColor: Int,
    val tableBorderColor: Int,
    val tableHeaderColor: Int,
    val tableOddRowColor: Int,
    val taskOutlineColor: Int,
    val tableBorderWidthPx: Int,
    val tableCellPaddingPx: Int,
    val inlineCodeBackgroundColor: Int,
    val inlineCodeCornerRadiusPx: Float,
    val inlineCodeHorizontalPaddingPx: Float,
    val inlineCodeVerticalPaddingPx: Float
)

private data class MarkdownRenderTag(
    val source: String,
    val palette: DshMarkdownPalette
)

private data class MarkdownRenderCacheKey(
    val source: String,
    val palette: DshMarkdownPalette
)

/**
 * LazyColumn 会在长会话滚动时销毁并重新创建离屏 TextView。缓存不可变的 Spanned，避免每次
 * 用户气泡跨入可视区时，都在主线程重新解析紧随其后的完整 assistant Markdown。
 */
private object DshMarkdownRenderCache {
    private const val MAX_SOURCE_CHARACTERS = 512_000
    private val values = LinkedHashMap<MarkdownRenderCacheKey, Spanned>(16, 0.75f, true)
    private var sourceCharacters = 0

    @Synchronized
    fun get(source: String, palette: DshMarkdownPalette): Spanned? =
        values[MarkdownRenderCacheKey(source, palette)]

    @Synchronized
    fun put(source: String, palette: DshMarkdownPalette, rendered: Spanned): Spanned {
        val key = MarkdownRenderCacheKey(source, palette)
        values.remove(key)?.let { sourceCharacters -= key.source.length }
        val immutable = SpannedString(rendered)
        values[key] = immutable
        sourceCharacters += source.length
        val iterator = values.entries.iterator()
        while (sourceCharacters > MAX_SOURCE_CHARACTERS && iterator.hasNext()) {
            sourceCharacters -= iterator.next().key.source.length
            iterator.remove()
        }
        return immutable
    }
}

private fun renderedMarkdown(
    markwon: Markwon,
    markdown: String,
    palette: DshMarkdownPalette
): Spanned = DshMarkdownRenderCache.get(markdown, palette)
    ?: DshMarkdownRenderCache.put(markdown, palette, markwon.toMarkdown(markdown))

/** Markwon 的 TablePlugin 带有可变 visitor，只在单独的后台线程串行解析。 */
private object DshAsyncMarkdownRenderer {
    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dsh-markdown-renderer").apply { priority = Thread.MIN_PRIORITY }
    }.asCoroutineDispatcher()
    private val markwons = mutableMapOf<DshMarkdownPalette, Markwon>()

    suspend fun render(
        context: Context,
        markdown: String,
        palette: DshMarkdownPalette
    ): Spanned = withContext(dispatcher) {
        DshMarkdownRenderCache.get(markdown, palette) ?: renderedMarkdown(
            markwon = markwons.getOrPut(palette) { buildDshMarkwon(context, palette) },
            markdown = markdown,
            palette = palette
        )
    }
}

internal class DshMarkdownPreloader internal constructor(
    private val context: Context,
    private val palette: DshMarkdownPalette
) {
    suspend fun preload(markdowns: List<String>) {
        markdowns.forEach { markdown ->
            DshAsyncMarkdownRenderer.render(context, markdown, palette)
        }
    }
}

@Composable
internal fun rememberDshMarkdownPreloader(): DshMarkdownPreloader {
    val context = LocalContext.current.applicationContext
    val palette = dshMarkdownPalette(compact = false)
    return remember(context, palette) { DshMarkdownPreloader(context, palette) }
}

/** 主线程只共享实例执行轻量的 TextView 插件回调，不参与 LazyColumn 的 Markdown 解析。 */
private object DshMarkdownTextApplier {
    private val markwons = mutableMapOf<DshMarkdownPalette, Markwon>()

    @Synchronized
    fun get(context: Context, palette: DshMarkdownPalette): Markwon =
        markwons.getOrPut(palette) { buildDshMarkwon(context, palette) }
}

@Composable
private fun dshMarkdownPalette(compact: Boolean): DshMarkdownPalette {
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    return DshMarkdownPalette(
        textColor = colors.onSurface.copy(alpha = if (compact) 0.68f else 1f).toArgb(),
        linkColor = DshColors.Ocean.toArgb(),
        tableBorderColor = colors.onSurface.copy(alpha = 0.20f).toArgb(),
        tableHeaderColor = colors.onSurface.copy(alpha = 0.08f).toArgb(),
        tableOddRowColor = colors.onSurface.copy(alpha = 0.035f).toArgb(),
        taskOutlineColor = colors.onSurface.copy(alpha = 0.45f).toArgb(),
        tableBorderWidthPx = with(density) { 1.dp.roundToPx() },
        tableCellPaddingPx = with(density) { 8.dp.roundToPx() },
        inlineCodeBackgroundColor = colors.onSurface.copy(alpha = 0.075f).toArgb(),
        inlineCodeCornerRadiusPx = with(density) { 6.dp.toPx() },
        inlineCodeHorizontalPaddingPx = with(density) { 4.dp.toPx() },
        inlineCodeVerticalPaddingPx = with(density) { 1.dp.toPx() }
    )
}

@Composable
internal fun DshMarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val palette = dshMarkdownPalette(compact)
    val markwon = remember(context.applicationContext, palette) {
        DshMarkdownTextApplier.get(context.applicationContext, palette)
    }
    val textSizeSp = if (compact) 14f else 16f
    val lineSpacingExtra = with(density) { (if (compact) 2.dp else 4.dp).toPx() }
    DshRenderedMarkdownText(
        markdown = markdown,
        rendered = renderedMarkdown(markwon, markdown, palette),
        palette = palette,
        textSizeSp = textSizeSp,
        lineSpacingExtra = lineSpacingExtra,
        modifier = modifier
    )
}

private data class PresentedMarkdown(
    val source: String,
    val rendered: Spanned
)

/**
 * LazyColumn 专用 Markdown：首次出现时直接提供富文本，后续流式内容在后台解析期间继续
 * 保留上一份富文本。它只会发生“富文本 -> 更新后的富文本”，不会回退成 Markdown 源码。
 */
@Composable
internal fun DshLazyMarkdownText(
    markdown: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current.applicationContext
    val palette = dshMarkdownPalette(compact = false)
    val markwon = remember(context, palette) {
        DshMarkdownTextApplier.get(context, palette)
    }
    var presented by remember(palette) {
        mutableStateOf(
            PresentedMarkdown(
                source = markdown,
                rendered = renderedMarkdown(markwon, markdown, palette)
            )
        )
    }
    LaunchedEffect(context, markdown, palette) {
        if (presented.source != markdown) {
            presented = PresentedMarkdown(
                source = markdown,
                rendered = DshAsyncMarkdownRenderer.render(context, markdown, palette)
            )
        }
    }

    val density = LocalDensity.current
    DshRenderedMarkdownText(
        markdown = presented.source,
        rendered = presented.rendered,
        palette = palette,
        textSizeSp = 16f,
        lineSpacingExtra = with(density) { 4.dp.toPx() },
        modifier = modifier
    )
}

@Composable
private fun DshRenderedMarkdownText(
    markdown: String,
    rendered: Spanned,
    palette: DshMarkdownPalette,
    textSizeSp: Float,
    lineSpacingExtra: Float,
    modifier: Modifier
) {
    val context = LocalContext.current.applicationContext
    val markwon = remember(context, palette) {
        DshMarkdownTextApplier.get(context, palette)
    }
    AndroidView(
        factory = { viewContext ->
            DshMarkdownTextView(viewContext).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
                includeFontPadding = false
                setPadding(0, 0, 0, 0)
                setLineSpacing(lineSpacingExtra, 1f)
                linksClickable = true
            }
        },
        onReset = { textView ->
            // Opt in to AndroidView reuse inside LazyColumn. update 会在下一次测量前写入新内容。
            textView.tag = null
        },
        update = { textView ->
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            textView.setLineSpacing(lineSpacingExtra, 1f)
            textView.setTextColor(palette.textColor)
            textView.setLinkTextColor(palette.linkColor)
            val nextTag = MarkdownRenderTag(markdown, palette)
            if (textView.tag != nextTag) {
                markwon.setParsedMarkdown(textView, rendered)
                textView.tag = nextTag
            }
        },
        modifier = modifier.fillMaxWidth()
    )
}

/**
 * 流式阶段不反复解析整篇 Markdown。最终 assistant/message 到达后 ID 会切换为
 * 持久化事件 ID，再一次性使用 Markwon 渲染完整正文。
 */
@Composable
@Suppress("UNUSED_PARAMETER")
internal fun DshStreamingAwareMarkdownText(
    markdown: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier
) {
    DshLazyMarkdownText(markdown, modifier)
}

internal fun buildDshMarkwon(
    context: Context,
    palette: DshMarkdownPalette
): Markwon {
    val tableTheme = TableTheme.emptyBuilder()
        .tableBorderColor(palette.tableBorderColor)
        .tableBorderWidth(palette.tableBorderWidthPx)
        .tableCellPadding(palette.tableCellPaddingPx)
        .tableHeaderRowBackgroundColor(palette.tableHeaderColor)
        .tableEvenRowBackgroundColor(Color.Transparent.toArgb())
        .tableOddRowBackgroundColor(palette.tableOddRowColor)
        .build()

    return Markwon.builder(context)
        .usePlugin(CorePlugin.create())
        .usePlugin(
            object : AbstractMarkwonPlugin() {
                override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
                    builder.setFactory(Code::class.java) { configuration, _ ->
                        DshInlineCodeSpan(
                            theme = configuration.theme(),
                            backgroundColor = palette.inlineCodeBackgroundColor,
                            cornerRadiusPx = palette.inlineCodeCornerRadiusPx,
                            horizontalPaddingPx = palette.inlineCodeHorizontalPaddingPx,
                            verticalPaddingPx = palette.inlineCodeVerticalPaddingPx
                        )
                    }
                    builder.setFactory(ThematicBreak::class.java) { _, _ ->
                        MarkdownHairlineSpan(palette.tableBorderColor)
                    }
                }
            }
        )
        .usePlugin(SoftBreakAddsNewLinePlugin.create())
        .usePlugin(StrikethroughPlugin.create())
        .usePlugin(TablePlugin.create(tableTheme))
        .usePlugin(
            TaskListPlugin.create(
                DshColors.Ocean.toArgb(),
                palette.taskOutlineColor,
                Color.White.toArgb()
            )
        )
        .usePlugin(HtmlPlugin.create())
        .usePlugin(MovementMethodPlugin.create(TableAwareMovementMethod.create()))
        .build()
}

/**
 * Markwon 默认用 TextPaint.bgColor 绘制行内代码，只能得到直角矩形。这里保留默认
 * CodeSpan 的字体/字号/前景色设置，并由 TextView 按实际换行片段绘制圆角背景。
 */
internal class DshInlineCodeSpan(
    theme: MarkwonTheme,
    internal val backgroundColor: Int,
    internal val cornerRadiusPx: Float,
    internal val horizontalPaddingPx: Float,
    internal val verticalPaddingPx: Float
) : MetricAffectingSpan() {
    private val delegate = CodeSpan(theme)

    override fun updateMeasureState(textPaint: TextPaint) {
        delegate.updateMeasureState(textPaint)
    }

    override fun updateDrawState(textPaint: TextPaint) {
        delegate.updateDrawState(textPaint)
        textPaint.bgColor = android.graphics.Color.TRANSPARENT
    }
}

internal class DshMarkdownTextView(context: Context) : AppCompatTextView(context) {
    override fun onDraw(canvas: Canvas) {
        drawInlineCodeBackgrounds(canvas)
        super.onDraw(canvas)
    }

    private fun drawInlineCodeBackgrounds(canvas: Canvas) {
        val spanned = text as? Spanned ?: return
        val textLayout = layout ?: return
        val spans = spanned.getSpans(0, spanned.length, DshInlineCodeSpan::class.java)
        if (spans.isEmpty()) return

        val saveCount = canvas.save()
        canvas.translate(
            (compoundPaddingLeft - scrollX).toFloat(),
            (extendedPaddingTop - scrollY).toFloat()
        )
        spans.forEach { span ->
            drawInlineCodeSpan(canvas, textLayout, spanned, span)
        }
        canvas.restoreToCount(saveCount)
    }

    private fun drawInlineCodeSpan(
        canvas: Canvas,
        textLayout: Layout,
        spanned: Spanned,
        span: DshInlineCodeSpan
    ) {
        val spanStart = spanned.getSpanStart(span)
        val spanEnd = spanned.getSpanEnd(span)
        if (spanStart < 0 || spanEnd <= spanStart) return

        val codePaint = TextPaint(paint).also(span::updateDrawState)
        val fontMetrics = codePaint.fontMetrics
        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = span.backgroundColor
            style = Paint.Style.FILL
        }
        val firstLine = textLayout.getLineForOffset(spanStart)
        val lastLine = textLayout.getLineForOffset((spanEnd - 1).coerceAtLeast(spanStart))
        for (line in firstLine..lastLine) {
            val segmentStart = maxOf(spanStart, textLayout.getLineStart(line))
            var segmentEnd = minOf(spanEnd, textLayout.getLineEnd(line))
            while (segmentEnd > segmentStart && spanned[segmentEnd - 1] == '\n') {
                segmentEnd -= 1
            }
            if (segmentEnd <= segmentStart) continue

            val startX = textLayout.getPrimaryHorizontal(segmentStart)
            val endX = textLayout.getPrimaryHorizontal(segmentEnd)
            val left = (minOf(startX, endX) - span.horizontalPaddingPx).coerceAtLeast(0f)
            val right = (maxOf(startX, endX) + span.horizontalPaddingPx)
                .coerceAtMost(textLayout.width.toFloat())
            val baseline = textLayout.getLineBaseline(line).toFloat()
            val top = (baseline + fontMetrics.ascent - span.verticalPaddingPx)
                .coerceAtLeast(textLayout.getLineTop(line).toFloat())
            val bottom = (baseline + fontMetrics.descent + span.verticalPaddingPx)
                .coerceAtMost(textLayout.getLineBottom(line).toFloat())
            canvas.drawRoundRect(
                RectF(left, top, right, bottom),
                span.cornerRadiusPx,
                span.cornerRadiusPx,
                backgroundPaint
            )
        }
    }
}

/** Markwon 的内置矩形分割线最少会占两个物理像素；这里直接绘制单像素 hairline。 */
private class MarkdownHairlineSpan(
    private val color: Int
) : LeadingMarginSpan {
    override fun getLeadingMargin(first: Boolean): Int = 0

    override fun drawLeadingMargin(
        canvas: Canvas,
        paint: Paint,
        x: Int,
        dir: Int,
        top: Int,
        baseline: Int,
        bottom: Int,
        text: CharSequence,
        start: Int,
        end: Int,
        first: Boolean,
        layout: Layout
    ) {
        val linePaint = Paint(paint).apply {
            color = this@MarkdownHairlineSpan.color
            style = Paint.Style.STROKE
            strokeWidth = 1f
            isAntiAlias = false
        }
        val left = if (dir > 0) x.toFloat() else (x - canvas.width).toFloat()
        val right = if (dir > 0) canvas.width.toFloat() else x.toFloat()
        val centerY = (top + bottom) / 2f
        canvas.drawLine(left, centerY, right, centerY, linePaint)
    }
}
