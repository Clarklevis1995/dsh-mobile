package com.clarklevis.dsh.android.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.roundToInt

/** 不替换原生输入节点；焦点、输入法组合文字及菜单状态随几何变化保留。 */
@Composable
internal fun ComposerMorphLayout(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit,
    editor: @Composable () -> Unit,
    tools: @Composable () -> Unit,
    action: @Composable () -> Unit
) {
    val animation = remember { Animatable(if (expanded) 1f else 0f) }
    val editorMeasureCache = remember { ComposerEditorMeasureCache() }
    val textLineHeight = with(LocalDensity.current) { MaterialTheme.typography.bodyLarge.lineHeight.toPx() }
    LaunchedEffect(expanded) {
        animation.animateTo(if (expanded) 1f else 0f, tween(220, easing = LinearOutSlowInEasing))
    }
    val hiddenSemantics = if (expanded) Modifier else Modifier.clearAndSetSemantics { }
    Layout(modifier = modifier.fillMaxWidth(), content = {
        Box { leading() }
        Box { editor() }
        Box(Modifier.graphicsLayer { alpha = animation.value }.then(hiddenSemantics)) { tools() }
        Box(Modifier.graphicsLayer { alpha = animation.value }.then(hiddenSemantics)) { action() }
    }) { items, constraints ->
        // 动画值仅在测量/绘制阶段读取，不让每一帧重新组合输入区及工具菜单。
        val progress = animation.value
        val width = constraints.maxWidth
        val rowHeight = 42.dp.roundToPx()
        val leadingSize = 32.dp.roundToPx()
        val leadingSpace = 44.dp.roundToPx()
        val toolsStart = 39.dp.roundToPx()
        val gap = 12.dp.roundToPx()
        val editorWidth = (width - leadingSpace * (1f - progress)).roundToInt().coerceAtLeast(1)
        val editorMaxHeight = if (expanded) 120.dp.roundToPx() else 38.dp.roundToPx()
        val editorItem = items[1].measure(Constraints(maxWidth = editorWidth, minWidth = editorWidth,
            maxHeight = editorMaxHeight))
        if (expanded) editorMeasureCache.expandedHeight = editorItem.height
        val editorHeight = maxOf(editorItem.height, editorMeasureCache.expandedHeight)
        val leadingItem = items[0].measure(Constraints.fixed(leadingSize, leadingSize))
        val toolsItem = items[2].measure(Constraints.fixed((width - toolsStart - rowHeight - 7.dp.roundToPx()).coerceAtLeast(1), rowHeight))
        val actionItem = items[3].measure(Constraints.fixed(rowHeight, rowHeight))
        val top = (6.dp.toPx() * progress).roundToInt()
        val bottom = (4.dp.toPx() * progress).roundToInt()
        val height = rowHeight + top + bottom + ((editorHeight + gap) * progress).roundToInt()
        layout(width, height) {
            val rowY = height - bottom - rowHeight
            leadingItem.placeRelative(0, rowY + (rowHeight - leadingSize) / 2)
            // 与 iOS 一致：hint 和正文始终顶部对齐，居中偏移只由动画进度控制。
            val compactTextTop = ((rowHeight - textLineHeight) / 2f).coerceAtLeast(0f)
            editorItem.placeRelative((leadingSpace * (1f - progress)).roundToInt(),
                top + (compactTextTop * (1f - progress)).roundToInt())
            // 收紧时不放置辅助控件：既不占空间，也不能被透明区域误触。
            if (expanded || progress > 0f) {
                toolsItem.placeRelative(toolsStart, rowY)
                actionItem.placeRelative(width - rowHeight, rowY)
            }
        }
    }
}

private class ComposerEditorMeasureCache {
    var expandedHeight: Int = 0
}

/** 在列表测量阶段读取覆盖层高度，避免高度动画驱动会话内容逐帧重组。 */
internal class ComposerViewportPadding(
    private val bottom: State<Dp>,
    private val top: Dp = 0.dp,
    private val bottomExtra: Dp = 0.dp
) : PaddingValues {
    override fun calculateLeftPadding(layoutDirection: LayoutDirection) = 0.dp
    override fun calculateTopPadding() = top
    override fun calculateRightPadding(layoutDirection: LayoutDirection) = 0.dp
    override fun calculateBottomPadding() = bottom.value + bottomExtra
}
