// 玻璃数值沿用日志导出页的视觉规格。
@file:Suppress("MagicNumber")

package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** 全局操作按钮沿用日志导出页的玻璃、细描边与轻阴影规格。 */
@Composable
internal fun Modifier.dshGlassControl(shape: Shape = CircleShape): Modifier {
    val surface = if (isSystemInDarkTheme()) Color(0xFF1C1C1E) else Color.White
    return shadow(4.dp, shape, ambientColor = Color.Black.copy(alpha = 0.06f), spotColor = Color.Black.copy(alpha = 0.06f))
        .dshLiquidGlass(shape, DshGlassStyle.CONTROL)
        .background(surface.copy(alpha = 0.45f), shape)
        .border(0.5.dp, Color(0xFF8E8E93).copy(alpha = 0.4f), shape)
        .clip(shape)
}

@Composable
internal fun dshGlassForeground(): Color = if (isSystemInDarkTheme()) Color.White else Color.Black

@Suppress("FunctionNaming", "ktlint:standard:function-naming", "LongParameterList")
@Composable
internal fun DshGlassButton(
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = CircleShape,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalContentColor provides dshGlassForeground().copy(alpha = if (enabled) 1f else 0.3f)) {
        Box(
            modifier
                .dshGlassControl(shape)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}
