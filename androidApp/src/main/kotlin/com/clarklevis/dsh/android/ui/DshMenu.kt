package com.clarklevis.dsh.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

internal val DshMenuShadowPadding = 24.dp

/** 应用通用的竖向菜单；统一材质、菜单项、边界和开关动画。 */
@Composable
internal fun DshMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier.width(240.dp),
    offset: DpOffset = DpOffset(0.dp, 8.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val shadowPadding = with(density) { DshMenuShadowPadding.roundToPx() }
    val offsetPx = with(density) { IntOffset(offset.x.roundToPx(), offset.y.roundToPx()) }
    var origin by remember { mutableStateOf(TransformOrigin.Center) }
    val positionProvider =
        remember(shadowPadding, offsetPx) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    val position =
                        dshMenuPosition(anchorBounds, windowSize, popupContentSize, layoutDirection, shadowPadding, offsetPx)
                    origin =
                        TransformOrigin(
                            ((anchorBounds.center.x - position.x).toFloat() / popupContentSize.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                            ((anchorBounds.center.y - position.y).toFloat() / popupContentSize.height.coerceAtLeast(1)).coerceIn(0f, 1f),
                        )
                    return position
                }
            }
        }
    DshMenuPopup(expanded, onDismissRequest, positionProvider, modifier, transformOrigin = origin) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), content = content)
    }

}

/** 按可见面板计算锚点，再减去阴影留白，避免加留白后菜单整体偏移。 */
internal fun dshMenuPosition(
    anchor: IntRect,
    window: IntSize,
    popup: IntSize,
    direction: LayoutDirection,
    shadowPadding: Int,
    offset: IntOffset,
): IntOffset {
    val width = (popup.width - shadowPadding * 2).coerceAtLeast(1)
    val height = (popup.height - shadowPadding * 2).coerceAtLeast(1)
    val maximumX = (window.width - popup.width).coerceAtLeast(0)
    val maximumY = (window.height - popup.height).coerceAtLeast(0)
    val start = if (direction == LayoutDirection.Ltr) anchor.left + offset.x else anchor.right - width - offset.x
    val end = if (direction == LayoutDirection.Ltr) anchor.right - width + offset.x else anchor.left - offset.x
    val x =
        listOf(start, end).firstOrNull { it - shadowPadding in 0..maximumX }
            ?: end.coerceIn(shadowPadding, maximumX + shadowPadding)
    val below = anchor.bottom + offset.y - shadowPadding
    val above = anchor.top - offset.y - height - shadowPadding
    val y =
        when {
            below in 0..maximumY -> below
            above in 0..maximumY -> above
            anchor.center.y < window.height / 2 -> below
            else -> above
        }.coerceIn(0, maximumY)
    return IntOffset(x - shadowPadding, y)
}

/** 所有菜单共用弹窗生命周期、屏幕边界、阴影留白与淡入缩放动效。 */
@Composable
internal fun DshMenuPopup(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    positionProvider: PopupPositionProvider,
    modifier: Modifier = Modifier,
    properties: PopupProperties = PopupProperties(focusable = true),
    transformOrigin: TransformOrigin = TransformOrigin.Center,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(26.dp),
    whiteSurface: Boolean = false,
    shadowPadding: androidx.compose.ui.unit.Dp = DshMenuShadowPadding,
    content: @Composable () -> Unit
) {
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = expanded
    if (!visibility.currentState && !visibility.targetState) return
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val maxWidth = with(density) { window.width.toDp() - shadowPadding * 2 }.coerceAtLeast(1.dp)
    val maxHeight = with(density) { window.height.toDp() - shadowPadding * 2 }.coerceAtLeast(1.dp)
    Popup(popupPositionProvider = positionProvider, onDismissRequest = onDismissRequest, properties = properties) {
        AnimatedVisibility(visibleState = visibility,
            enter = fadeIn(tween(180)) + scaleIn(tween(220), initialScale = .94f, transformOrigin = transformOrigin),
            exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f, transformOrigin = transformOrigin)) {
            Box(Modifier.padding(shadowPadding)) {
                DshMenuSurface(Modifier.widthIn(max = maxWidth).heightIn(max = maxHeight).then(modifier),
                    shape, whiteSurface, content)
            }
        }
    }
}

/** 特殊箭头面板和横向工具栏同样复用这个材质，不能各自配置另一套菜单外观。 */
@Composable
internal fun DshMenuSurface(
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(26.dp),
    whiteSurface: Boolean = false,
    content: @Composable () -> Unit
) {
    Box(modifier.dropShadow(shape, Shadow(radius = 12.dp, color = Color.Black.copy(alpha = .12f),
        offset = DpOffset(0.dp, 3.dp))).dshMenuGlass(shape, whiteSurface)) {
        CompositionLocalProvider(
            LocalContentColor provides if (whiteSurface) Color(0xFF10131A) else MaterialTheme.colorScheme.onSurface,
            LocalTextStyle provides MaterialTheme.typography.bodyLarge
        ) { content() }
    }
}

/** 保留图标、禁用项与多行文案能力，不引入 Material 下拉菜单的默认颜色和尺寸。 */
@Composable
internal fun DshMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 10.dp),
    minHeight: Dp = 48.dp
) {
    androidx.compose.foundation.layout.Row(
        modifier.fillMaxWidth().heightIn(min = minHeight).alpha(if (enabled) 1f else .38f)
            .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(contentPadding),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        leadingIcon?.invoke()
        Box(Modifier.weight(1f)) { text() }
        trailingIcon?.invoke()
    }
}

/** 横向文字操作不抢走输入或选区焦点，文字尺寸和命中区域统一维护。 */
@Composable
internal fun DshMenuAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.heightIn(min = 44.dp).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
        .padding(horizontal = 18.dp, vertical = 8.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
        androidx.compose.material3.Text(label, fontSize = 17.sp)
    }
}

/** 供正文长按等非 Compose 锚点调用，坐标仍属于当前窗口。 */
@Composable
internal fun DshPointMenu(point: IntOffset?, onDismiss: () -> Unit, modifier: Modifier = Modifier.width(240.dp),
    content: @Composable ColumnScope.() -> Unit) {
    var previous by remember { mutableStateOf(point) }
    androidx.compose.runtime.SideEffect { if (point != null) previous = point }
    val anchor = point ?: previous ?: return
    val shadow = with(LocalDensity.current) { DshMenuShadowPadding.roundToPx() }
    val position = remember(anchor, shadow) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
                popupContentSize: IntSize): IntOffset = dshMenuPosition(IntRect(anchor.x, anchor.y, anchor.x, anchor.y),
                windowSize, popupContentSize, layoutDirection, shadow, IntOffset.Zero)
        }
    }
    DshMenuPopup(point != null, onDismiss, position, modifier) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), content = content)
    }
}
