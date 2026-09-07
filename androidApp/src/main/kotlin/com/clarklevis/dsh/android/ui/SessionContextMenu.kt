package com.clarklevis.dsh.android.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.clarklevis.dsh.android.R

/** 与 iOS 会话菜单对齐：浅色圆角面板、前置图标及红色归档操作。 */
@Composable
internal fun SessionContextMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onRename: () -> Unit,
    onArchive: () -> Unit
) {
    if (!expanded) return
    val margin = with(LocalDensity.current) { 8.dp.roundToPx() }
    val positionProvider = remember(margin) { SessionMenuPositionProvider(margin) }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true)
    ) {
        // 为阴影留出空间，避免弹出窗口裁切圆角外的阴影。
        Box(Modifier.padding(16.dp)) {
            Surface(
                modifier = Modifier.width(260.dp).testTag("session-context-menu"),
                shape = RoundedCornerShape(32.dp),
                color = Color(0xF5BECADF),
                contentColor = Color(0xFF17202D),
                border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.38f)),
                tonalElevation = 0.dp,
                shadowElevation = 16.dp
            ) {
                Column(
                    Modifier.background(
                        Brush.linearGradient(listOf(Color.White.copy(alpha = 0.15f), Color.Transparent))
                    ).padding(vertical = 8.dp)
                ) {
                    SessionMenuAction("重命名", R.drawable.ic_session_rename, Color(0xFF17202D), onRename)
                    SessionMenuAction("删除（归档）", R.drawable.ic_session_archive, Color(0xFFFF3B30), onArchive)
                }
            }
        }
    }
}

@Composable
private fun SessionMenuAction(
    title: String,
    @DrawableRes icon: Int,
    tint: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp).padding(horizontal = 28.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(title, color = tint, fontSize = 17.sp, lineHeight = 22.sp)
    }
}

private class SessionMenuPositionProvider(private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width - margin).coerceAtLeast(0)
        val maxY = (windowSize.height - popupContentSize.height - margin).coerceAtLeast(0)
        val above = anchorBounds.top - popupContentSize.height
        val preferredY = if (above >= margin) above else anchorBounds.bottom
        return IntOffset(
            ((anchorBounds.left + anchorBounds.right - popupContentSize.width) / 2)
                .coerceIn(margin.coerceAtMost(maxX), maxX),
            preferredY.coerceIn(margin.coerceAtMost(maxY), maxY)
        )
    }
}
