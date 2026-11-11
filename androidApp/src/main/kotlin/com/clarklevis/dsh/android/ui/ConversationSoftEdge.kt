package com.clarklevis.dsh.android.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur

/** 标题后保留毛玻璃，模糊与底色共用连续的透明度遮罩。 */
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
    return graphicsLayer {
        // 仅隔离顶栏覆盖层，DstIn 不得擦除后方的消息或栅格化整个会话。
        compositingStrategy = CompositingStrategy.Offscreen
    }.drawWithCache {
        val edge = protectedHeight.toPx().coerceIn(0f, size.height)
        val end = (edge + fadeHeight.toPx()).coerceAtMost(size.height).coerceAtLeast(edge + 1f)
        val stops = Array(33) { index ->
            val t = index / 32f
            // smoothstep 两端斜率为零，避免渐变起止处形成可见折线。
            val alpha = 1f - t * t * (3f - 2f * t)
            t to Color.Black.copy(alpha = alpha)
        }
        val mask = Brush.verticalGradient(*stops, startY = edge, endY = end)
        onDrawWithContent {
            drawContent()
            drawRect(mask, blendMode = BlendMode.DstIn)
        }
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
