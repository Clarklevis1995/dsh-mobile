package com.clarklevis.dsh.android.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import android.text.style.LeadingMarginSpan
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import io.noties.markwon.core.CorePlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TableAwareMovementMethod
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tables.TableTheme
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.movement.MovementMethodPlugin
import org.commonmark.node.ThematicBreak

internal data class DshMarkdownPalette(
    val textColor: Int,
    val linkColor: Int,
    val tableBorderColor: Int,
    val tableHeaderColor: Int,
    val tableOddRowColor: Int,
    val taskOutlineColor: Int,
    val tableBorderWidthPx: Int,
    val tableCellPaddingPx: Int
)

private data class MarkdownRenderTag(
    val source: String,
    val palette: DshMarkdownPalette
)

@Composable
internal fun DshMarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val palette = DshMarkdownPalette(
        textColor = colors.onSurface.copy(alpha = if (compact) 0.68f else 1f).toArgb(),
        linkColor = DshColors.Ocean.toArgb(),
        tableBorderColor = colors.onSurface.copy(alpha = 0.20f).toArgb(),
        tableHeaderColor = colors.onSurface.copy(alpha = 0.08f).toArgb(),
        tableOddRowColor = colors.onSurface.copy(alpha = 0.035f).toArgb(),
        taskOutlineColor = colors.onSurface.copy(alpha = 0.45f).toArgb(),
        tableBorderWidthPx = with(density) { 1.dp.roundToPx() },
        tableCellPaddingPx = with(density) { 8.dp.roundToPx() }
    )
    val markwon = remember(context.applicationContext, palette) {
        buildDshMarkwon(context.applicationContext, palette)
    }
    val textSizeSp = if (compact) 14f else 16f
    val lineSpacingExtra = with(density) { (if (compact) 2.dp else 4.dp).toPx() }

    AndroidView(
        factory = { viewContext ->
            TextView(viewContext).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
                includeFontPadding = false
                setPadding(0, 0, 0, 0)
                setLineSpacing(lineSpacingExtra, 1f)
                linksClickable = true
            }
        },
        update = { textView ->
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            textView.setLineSpacing(lineSpacingExtra, 1f)
            textView.setTextColor(palette.textColor)
            textView.setLinkTextColor(palette.linkColor)
            val nextTag = MarkdownRenderTag(markdown, palette)
            if (textView.tag != nextTag) {
                markwon.setMarkdown(textView, markdown)
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
internal fun DshStreamingAwareMarkdownText(
    markdown: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier
) {
    if (isStreaming) {
        Text(
            text = markdown,
            modifier = modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp,
            lineHeight = 24.sp
        )
    } else {
        DshMarkdownText(markdown, modifier)
    }
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
