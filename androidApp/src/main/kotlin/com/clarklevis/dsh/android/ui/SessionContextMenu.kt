package com.clarklevis.dsh.android.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupPositionProvider
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.android.R

/** 与 iOS 会话菜单对齐：浅色圆角面板、前置图标及红色归档操作。 */
@Composable
internal fun SessionContextMenu(
    expanded: Boolean,
    pressOffset: IntOffset?,
    isPinned: Boolean,
    canPin: Boolean,
    onDismissRequest: () -> Unit,
    onCopyId: () -> Unit,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onArchive: () -> Unit
) {
    val shadow = with(LocalDensity.current) { 16.dp.roundToPx() }
    val positionProvider = remember(pressOffset, shadow) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize
            ): IntOffset {
                val point = pressOffset?.let { IntOffset(anchorBounds.left + it.x, anchorBounds.top + it.y) }
                    ?: anchorBounds.center
                return dshMenuPosition(IntRect(point, IntSize.Zero), windowSize, popupContentSize,
                    layoutDirection, shadow, IntOffset.Zero)
            }
        }
    }
    DshMenuPopup(expanded, onDismissRequest, positionProvider,
        Modifier.width(224.dp).testTag("session-context-menu"), shadowPadding = 16.dp) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            val ink = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
            SessionMenuAction(appText("复制对话 ID"), R.drawable.ic_copy_message, ink, onCopyId)
            SessionMenuAction(
                if (isPinned) appText("取消置顶") else appText("置顶"),
                if (isPinned) R.drawable.ic_session_unpin else R.drawable.ic_session_pin, ink, onTogglePin,
                enabled = canPin
            )
            SessionMenuAction(appText("重命名"), R.drawable.ic_session_rename, ink, onRename)
            SessionMenuAction(appText("删除（归档）"), R.drawable.ic_session_archive, Color(0xFFFF3B30), onArchive)
        }
    }
}

@Composable
private fun SessionMenuAction(title: String, @DrawableRes icon: Int, tint: Color, onClick: () -> Unit, enabled: Boolean = true) {
    DshMenuItem(text = { Text(title, color = tint, fontSize = 17.sp, lineHeight = 22.sp) },
        leadingIcon = { Icon(painterResource(icon), null, Modifier.size(20.dp), tint = tint) },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp, vertical = 8.dp),
        minHeight = 44.dp, enabled = enabled, onClick = onClick)
}
