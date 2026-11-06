package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.runtime.key
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.LaunchedEffect
import com.clarklevis.dsh.shared.protocol.GatewayTextPart
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.android.localization.appText

/** 引用与编辑器处于同一个无额外间距的容器，位移只由引用占位高度驱动。 */
@Composable
internal fun ComposerQuoteStack(
    parts: List<GatewayTextPart>,
    visible: Boolean,
    onRemove: (Int) -> Unit,
    content: @Composable () -> Unit
) {
    Column {
        ComposerReplyPreviews(parts, visible = visible, spacingAfter = 12.dp, onRemove = onRemove)
        content()
    }
}

@Composable
internal fun ComposerReplyPreviews(
    parts: List<GatewayTextPart>,
    visible: Boolean = true,
    spacingAfter: Dp = 0.dp,
    onRemove: (Int) -> Unit
) {
    val identities = remember { ComposerQuoteIdentity() }
    val quotes = identities.update(parts)
    val displayed = if (quotes.isEmpty()) identities.lastVisible else quotes
    // 引用高度和下方间距共用唯一的展开过渡，下方编辑器直接跟随测量结果。
    AnimatedVisibility(
        visible = visible && quotes.isNotEmpty(),
        enter = expandVertically(tween(220, easing = LinearOutSlowInEasing), expandFrom = Alignment.Top) +
            fadeIn(tween(220, easing = LinearOutSlowInEasing)),
        exit = shrinkVertically(tween(220, easing = LinearOutSlowInEasing), shrinkTowards = Alignment.Top) +
            fadeOut(tween(220, easing = LinearOutSlowInEasing))
    ) {
        Box(Modifier.padding(bottom = spacingAfter)) {
            val columns = displayed.size.coerceIn(1, 3)
            val rows = (displayed.size + columns - 1) / columns
            val visibleRows = rows.coerceIn(1, 3)
            val rowHeight = with(LocalDensity.current) { maxOf(72.dp, 23.sp.toDp() * 2 + 24.dp) }
            val state = rememberScrollState()
            var previousCount by remember { mutableStateOf(0) }
            LaunchedEffect(displayed.size) {
                // 等待新内容完成一次测量，滚到最新条目的实际底部。
                withFrameNanos { }
                if (displayed.size > previousCount && rows > 3) {
                    if (previousCount == 0) state.scrollTo(state.maxValue)
                    else state.animateScrollTo(state.maxValue)
                }
                previousCount = displayed.size
            }
            val viewportHeight = quoteAnimatedHeight(rowHeight * visibleRows + 8.dp * (visibleRows - 1))
            val canvasHeight = quoteAnimatedHeight(rowHeight * rows + 8.dp * (rows - 1))
            BoxWithConstraints(Modifier.fillMaxWidth().quoteHeight(viewportHeight)
                .verticalScroll(state, enabled = rows > 3).testTag("composer-reply-grid")) {
                val density = LocalDensity.current
                val gap = with(density) { 8.dp.toPx() }
                val cellHeight = with(density) { rowHeight.toPx() }
                val cellWidth = ((constraints.maxWidth - (columns - 1) * gap) / columns).coerceAtLeast(0f)
                Box(Modifier.fillMaxWidth().quoteHeight(canvasHeight)) {
                    displayed.forEachIndexed { slot, quote ->
                        key(quote.id) {
                            val x = (slot % columns) * (cellWidth + gap)
                            val y = (slot / columns) * (cellHeight + gap)
                            ComposerQuoteCellFrame(Rect(x, y, x + cellWidth, y + cellHeight)) {
                                Box(Modifier.fillMaxSize().testTag("composer-reply-id-${quote.id}")) {
                                    Box(Modifier.fillMaxSize().testTag("composer-reply-cell-${quote.partIndex}")) {
                                        ComposerReplyPreview(quote.text, compact = columns > 1,
                                            modifier = Modifier.fillMaxSize(), onRemove = {
                                                identities.remove(quote.id, parts)?.let(onRemove)
                                            })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 实际的视口与画布一起收缩，不能只改变外层报告的尺寸。 */
@Composable
private fun quoteAnimatedHeight(target: Dp): Animatable<Float, androidx.compose.animation.core.AnimationVector1D> {
    val pixels = with(LocalDensity.current) { target.toPx() }
    val height = remember { Animatable(pixels) }
    LaunchedEffect(pixels) { height.animateTo(pixels, tween(220, easing = LinearOutSlowInEasing)) }
    return height
}

private fun Modifier.quoteHeight(height: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>) = layout { measurable, constraints ->
    val pixels = height.value.roundToInt().coerceAtLeast(0)
    val content = measurable.measure(constraints.copy(minHeight = pixels, maxHeight = pixels))
    layout(content.width, pixels) { content.placeRelative(0, 0) }
}

/** 一份矩形同时控制整张 Cell 的位置和尺寸，叉号始终在当前 Cell 右上角。 */
@Composable
private fun ComposerQuoteCellFrame(target: Rect, content: @Composable () -> Unit) {
    val frame = remember { Animatable(target, Rect.VectorConverter) }
    LaunchedEffect(target) { frame.animateTo(target, tween(220, easing = LinearOutSlowInEasing)) }
    Layout(modifier = Modifier.fillMaxSize(), content = content) { items, constraints ->
        val rect = frame.value
        val cell = items.single().measure(Constraints.fixed(rect.width.roundToInt().coerceAtLeast(0),
            rect.height.roundToInt().coerceAtLeast(0)))
        layout(constraints.maxWidth, constraints.maxHeight) {
            cell.placeRelative(rect.left.roundToInt(), rect.top.roundToInt())
        }
    }
}

@Composable
internal fun ComposerReplyPreview(text: String, onRemove: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val dark = isSystemInDarkTheme()
    val background = if (dark) Color(0xFF292B2F) else Color(0xFFF1F1F1)
    val foreground = if (dark) Color(0xFFF2F2F3) else Color(0xFF202124)
    Box(modifier.fillMaxWidth().background(background, RoundedCornerShape(18.dp))
        .testTag("composer-reply-preview")) {
        Text(text, modifier = Modifier.padding(if (compact) 10.dp else 12.dp)
            .padding(end = if (compact) 28.dp else 32.dp).testTag("composer-reply-text"),
            fontSize = if (compact) 14.sp else 16.sp, lineHeight = if (compact) 20.sp else 23.sp, color = foreground,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        IconButton(onClick = onRemove,
            modifier = Modifier.align(Alignment.TopEnd).size(44.dp).testTag("composer-reply-remove")) {
            Box(Modifier.size(24.dp).background(if (dark) Color(0xFF414348) else Color.White, CircleShape),
                contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_close), appText("移除引用"), Modifier.size(12.dp), tint = foreground)
            }
        }
    }
}
