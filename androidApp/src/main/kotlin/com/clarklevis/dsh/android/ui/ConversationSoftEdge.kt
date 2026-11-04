package com.clarklevis.dsh.android.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur

/** 标题后保留毛玻璃；下缘只渐隐底色，避免模糊副本与原始正文叠出重影。 */
@Composable
internal fun Modifier.conversationTopGlass(
    backdrop: LayerBackdrop,
    protectedHeight: Dp,
    fadeHeight: Dp,
): Modifier = topBarGlassOverlay(backdrop, protectedHeight, fadeHeight, MaterialTheme.colorScheme.surface)

/** 顶栏采样实际滚动内容，下沿使用渐变衔接；旧系统使用半透明底色降级。 */
@Composable
internal fun Modifier.topBarGlassOverlay(
    backdrop: LayerBackdrop,
    protectedHeight: Dp,
    fadeHeight: Dp,
    tint: Color,
    surfaceAlpha: Float = 0.95f,
): Modifier {
    val surface = Modifier.topBarGlassSurface(backdrop, tint, surfaceAlpha)
    return drawWithContent {
        val edge = protectedHeight.toPx().coerceAtMost(size.height)
        clipRect(bottom = edge) { this@drawWithContent.drawContent() }
        drawRect(
            brush =
                Brush.verticalGradient(
                    colors = listOf(tint.copy(alpha = surfaceAlpha), Color.Transparent),
                    startY = edge,
                    endY = edge + fadeHeight.toPx(),
                ),
            topLeft = Offset(0f, edge),
            size = Size(size.width, size.height - edge),
        )
    }.then(surface)
}

/** 矩形顶栏毛玻璃，边界外不绘制模糊或渐变。裁剪范围由顶栏宿主确定。 */
@Composable
internal fun Modifier.topBarGlassSurface(
    backdrop: LayerBackdrop,
    tint: Color,
    surfaceAlpha: Float = 0.85f,
): Modifier =
    if (!LocalUiEffectsReduced.current && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        drawPlainBackdrop(
            backdrop,
            shape = { RectangleShape },
            effects = { blur(24.dp.toPx()) },
            onDrawSurface = { drawRect(tint.copy(alpha = surfaceAlpha)) },
        )
    } else {
        background(tint.copy(alpha = surfaceAlpha))
    }
