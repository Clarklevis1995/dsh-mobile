package com.clarklevis.dsh.android.ui

import android.graphics.Rect
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.clarklevis.dsh.android.localization.appText

/** 坐标均为选择页窗口坐标，视口用于避免菜单覆盖顶部关闭按钮。 */
internal data class DshSelectionToolbarAnchor(val selection: Rect, val viewport: Rect)

/** 菜单不获取焦点，TextView 始终持有原生选区与双端手柄。 */
@Composable
internal fun DshSelectionToolbar(anchor: DshSelectionToolbarAnchor, onCopy: () -> Unit, onSelectAll: () -> Unit, onQuote: (() -> Unit)? = null) {
    val density = LocalDensity.current
    val shadow = with(density) { 16.dp.roundToPx() }
    val gap = with(density) { 10.dp.roundToPx() }
    val handles = with(density) { 24.dp.roundToPx() }
    val position = remember(anchor, shadow, gap, handles) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val panelHeight = popupContentSize.height - shadow * 2
                val above = anchor.selection.top - gap - panelHeight
                val below = anchor.selection.bottom + handles + gap
                val top = when {
                    above >= anchor.viewport.top -> above
                    below + panelHeight <= anchor.viewport.bottom -> below
                    else -> anchor.viewport.top + gap
                }
                val x = anchor.selection.centerX() - popupContentSize.width / 2
                return IntOffset(x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
                    (top - shadow).coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)))
            }
        }
    }
    DshMenuPopup(true, {}, position,
        properties = PopupProperties(focusable = false, dismissOnClickOutside = false),
        shape = CircleShape, whiteSurface = true, shadowPadding = 16.dp) {
        Row(Modifier.testTag("markdown-selection-toolbar"), verticalAlignment = Alignment.CenterVertically) {
            DshMenuAction(appText("复制"), onCopy, Modifier.testTag("markdown-selection-copy"))
            Box(Modifier.width(0.5.dp).height(20.dp).background(Color.Black.copy(alpha = .10f)))
            if (onQuote != null) DshMenuAction("引用", onQuote, Modifier.testTag("markdown-selection-quote"))
            DshMenuAction(appText("全选"), onSelectAll, Modifier.testTag("markdown-selection-all"))
        }
    }
}
