package com.clarklevis.dsh.android.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** 全应用统一的 iOS 风格胶囊开关，默认 51×31；回调为空时由外层设置行处理切换语义。 */
@Composable
internal fun DshSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    preserveDisabledColors: Boolean = false,
    wideTrack: Boolean = false,
) {
    val width = if (wideTrack) 63.dp else 51.dp
    val height = if (wideTrack) 28.dp else 31.dp
    val thumbWidth = if (wideTrack) 37.dp else 27.dp
    val secondary = if (isSystemInDarkTheme()) {
        Color(0xFFEBEBF5).copy(alpha = 0.6f)
    } else {
        Color(0xFF3C3C43).copy(alpha = 0.6f)
    }
    val blue = dshSwitchTrackColor()
    val track by animateColorAsState(
        if (checked) blue.copy(alpha = if (enabled || preserveDisabledColors) 1f else 0.5f)
        else secondary.copy(alpha = if (wideTrack) 0.15f else 0.3f),
        animationSpec = tween(180),
        label = "dsh-switch-track"
    )
    val thumbOffset by animateDpAsState(
        if (checked) width - thumbWidth - 4.dp else 0.dp,
        animationSpec = tween(180),
        label = "dsh-switch-thumb"
    )
    Box(
        modifier.size(width, height).clip(CircleShape)
            .background(track)
            .then(
                if (onCheckedChange != null) {
                    Modifier.toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
                } else Modifier
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(Modifier.offset(x = thumbOffset).padding(2.dp).size(thumbWidth, height - 4.dp).background(Color.White, CircleShape))
    }
}
