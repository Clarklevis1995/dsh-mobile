package com.clarklevis.dsh.android.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupPositionProvider
import com.clarklevis.dsh.android.R

internal data class DshSelectionOption(
    val key: String,
    val label: String,
    @param:DrawableRes val iconRes: Int? = null,
    val enabled: Boolean = true
)

/** 设置页共用的浮层选项卡；由调用方的 Box 提供锚点。 */
@Composable
internal fun DshSelectionPopup(
    expanded: Boolean,
    options: List<DshSelectionOption>,
    selectedKey: String?,
    onDismissRequest: () -> Unit,
    onSelect: (String) -> Unit,
    alignToEnd: Boolean = false,
    menuWidth: Dp = 208.dp,
    labelMaxLines: Int = 1
) {
    val density = LocalDensity.current
    val margin = with(density) { 8.dp.roundToPx() }
    val overlap = with(density) { 12.dp.roundToPx() }
    val shadowPadding = 30.dp
    val shadowInset = with(density) { shadowPadding.roundToPx() }
    val positionProvider = remember(margin, overlap, alignToEnd, shadowInset) {
        SelectionPopupPositionProvider(margin, overlap, alignToEnd, shadowInset)
    }
    DshMenuPopup(expanded, onDismissRequest, positionProvider,
        Modifier.width(menuWidth).testTag("dsh-selection-popup"), shadowPadding = shadowPadding) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
            options.forEach { option ->
                val selected = option.key == selectedKey
                DshMenuItem(text = { Text(option.label, fontSize = 15.sp, maxLines = labelMaxLines, overflow = TextOverflow.Ellipsis) },
                    enabled = option.enabled,
                    modifier = Modifier.semantics { this.selected = selected },
                    leadingIcon = {
                        val icon = if (selected) R.drawable.ic_menu_check else option.iconRes
                        if (icon != null) Icon(painterResource(icon), null, Modifier.size(18.dp))
                        else Spacer(Modifier.size(18.dp))
                    },
                    onClick = { onSelect(option.key) })
            }
        }
    }
}

private class SelectionPopupPositionProvider(
    private val margin: Int,
    private val overlap: Int,
    private val alignToEnd: Boolean,
    private val shadowInset: Int
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val horizontalInset = if (alignToEnd) margin - shadowInset else margin
        val maxX = (windowSize.width - popupContentSize.width - horizontalInset).coerceAtLeast(horizontalInset.coerceAtMost(0))
        val maxY = (windowSize.height - popupContentSize.height - margin).coerceAtLeast(0)
        val above = anchorBounds.bottom - popupContentSize.height + overlap
        val preferredY = if (above >= margin) above else anchorBounds.bottom
        val preferredX = when {
            !alignToEnd -> anchorBounds.left
            layoutDirection == LayoutDirection.Ltr -> anchorBounds.right + shadowInset - popupContentSize.width
            else -> anchorBounds.left - shadowInset
        }
        return IntOffset(
            x = preferredX.coerceIn(horizontalInset.coerceAtMost(maxX), maxX),
            y = preferredY.coerceIn(margin.coerceAtMost(maxY), maxY)
        )
    }
}
