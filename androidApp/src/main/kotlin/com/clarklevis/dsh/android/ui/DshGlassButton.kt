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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/** 全局操作按钮沿用日志导出页的玻璃、细描边与轻阴影规格。 */
@Composable
internal fun Modifier.dshGlassControl(shape: Shape = CircleShape): Modifier {
    val surface = if (isSystemInDarkTheme()) Color(0xFF1C1C1E) else Color.White
    if (LocalUiEffectsReduced.current) {
        return background(surface.copy(alpha = 0.92f), shape)
            .border(0.5.dp, Color(0xFF8E8E93).copy(alpha = 0.4f), shape)
            .clip(shape)
    }
    val shadowColor = Color.Black.copy(alpha = if (isSystemInDarkTheme()) 0.20f else 0.08f)
    return dshOuterShadow(shape, Shadow(radius = 6.dp, color = shadowColor, offset = DpOffset(0.dp, 2.dp)))
        .dshLiquidGlass(shape, DshGlassStyle.CONTROL, shadowsEnabled = false)
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

/** 投影只绘制在轮廓外，透明内容不会透出阴影；共享系统阴影缓存。 */
@Composable
internal fun Modifier.dshOuterShadow(
    shape: Shape,
    shadow: Shadow,
): Modifier {
    val graphics = LocalGraphicsContext.current
    val painter = remember(graphics, shape, shadow) { graphics.shadowContext.createDropShadowPainter(shape, shadow) }
    return drawWithCache {
        val outline = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
        onDrawWithContent {
            clipPath(outline, ClipOp.Difference) {
                with(painter) { draw(size) }
            }
            drawContent()
        }
    }
}
