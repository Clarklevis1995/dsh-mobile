package com.clarklevis.dsh.android.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.clarklevis.dsh.android.R

internal data class DshSelectionOption(
    val key: String,
    val label: String,
    @param:DrawableRes val iconRes: Int? = null
)

/** 设置页共用的浮层选项卡；由调用方的 Box 提供锚点。 */
@Composable
internal fun DshSelectionPopup(
    expanded: Boolean,
    options: List<DshSelectionOption>,
    selectedKey: String?,
    onDismissRequest: () -> Unit,
    onSelect: (String) -> Unit
) {
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = expanded
    // 保留浮层至退场动画结束。
    if (!visibility.currentState && !visibility.targetState) return
    val density = LocalDensity.current
    val margin = with(density) { 8.dp.roundToPx() }
    val overlap = with(density) { 12.dp.roundToPx() }
    val positionProvider = remember(margin, overlap) { SelectionPopupPositionProvider(margin, overlap) }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true, clippingEnabled = false)
    ) {
        AnimatedVisibility(
            visibleState = visibility,
            enter = fadeIn(tween(160)) + scaleIn(
                animationSpec = tween(180),
                initialScale = 0.94f,
                transformOrigin = TransformOrigin(0.5f, 1f)
            ),
            exit = fadeOut(tween(120)) + scaleOut(
                animationSpec = tween(120),
                targetScale = 0.96f,
                transformOrigin = TransformOrigin(0.5f, 1f)
            )
        ) {
            Box(Modifier.padding(30.dp)) {
                val shape = RoundedCornerShape(22.dp)
                Surface(
                    modifier = Modifier.width(208.dp).dropShadow(
                        shape = shape,
                        shadow = Shadow(
                            radius = 14.dp,
                            color = Color.Black.copy(alpha = 0.17f),
                            offset = DpOffset(0.dp, 6.dp)
                        )
                    ).testTag("dsh-selection-popup"),
                    shape = shape,
                    color = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                    border = BorderStroke(
                        0.5.dp,
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                    )
                ) {
                    Column(Modifier.padding(vertical = 2.dp)) {
                        options.forEach { option ->
                            val selected = option.key == selectedKey
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable(role = Role.RadioButton) { onSelect(option.key) }
                                    .heightIn(min = 44.dp)
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val icon = if (selected) R.drawable.ic_menu_check else option.iconRes
                                if (icon != null) {
                                    Icon(
                                        painter = painterResource(icon),
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                } else {
                                    Spacer(Modifier.size(18.dp))
                                }
                                Text(
                                    text = option.label,
                                    modifier = Modifier.padding(start = 12.dp),
                                    fontSize = 15.sp,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private class SelectionPopupPositionProvider(
    private val margin: Int,
    private val overlap: Int
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width - margin).coerceAtLeast(0)
        val maxY = (windowSize.height - popupContentSize.height - margin).coerceAtLeast(0)
        val above = anchorBounds.bottom - popupContentSize.height + overlap
        val preferredY = if (above >= margin) above else anchorBounds.bottom
        return IntOffset(
            x = anchorBounds.left.coerceIn(margin.coerceAtMost(maxX), maxX),
            y = preferredY.coerceIn(margin.coerceAtMost(maxY), maxY)
        )
    }
}
