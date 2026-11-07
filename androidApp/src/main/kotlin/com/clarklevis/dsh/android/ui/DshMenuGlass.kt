package com.clarklevis.dsh.android.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur

private val LocalDshMenuBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** 只录制宿主页面；Popup 位于独立窗口，不会把自己的文字和阴影再次采样。 */
@Composable
internal fun DshMenuBackdropHost(content: @Composable (Modifier) -> Unit) {
    val effectsReduced = LocalUiEffectsReduced.current
    val layer = rememberLayerBackdrop()
    val backdrop = remember(layer) { DshWindowBackdrop(layer) }
    CompositionLocalProvider(LocalDshMenuBackdrop provides backdrop.takeUnless { effectsReduced }) {
        content(if (effectsReduced) Modifier else Modifier.layerBackdrop(layer).onGloballyPositioned {
            backdrop.sourceOrigin = it.positionOnScreen()
        })
    }
}

/** Popup 与 Activity 的窗口原点不同，必须用屏幕坐标对齐采样区域。 */
private class DshWindowBackdrop(private val source: LayerBackdrop) : Backdrop {
    var sourceOrigin by mutableStateOf(Offset.Unspecified)
    override val isCoordinatesDependent: Boolean = true

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        val position = coordinates?.takeIf { it.isAttached }?.positionOnScreen() ?: return
        if (!sourceOrigin.x.isFinite() || !position.x.isFinite()) return
        val offset = position - sourceOrigin
        withTransform({ translate(-offset.x, -offset.y) }) {
            drawLayer(source.graphicsLayer)
        }
    }
}

/** 所有菜单共用透明度、模糊和描边；阴影由外层统一绘制，避免叠加。 */
@Composable
internal fun Modifier.dshMenuGlass(shape: Shape, whiteSurface: Boolean = false): Modifier {
    val dark = !whiteSurface && MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val tint = if (whiteSurface) Color.White else MaterialTheme.colorScheme.surface
    return dshBackdropGlass(
        shape = shape,
        tint = tint,
        surfaceAlpha = if (whiteSurface) 0.78f else if (dark) 0.70f else 0.60f,
        fallback = tint.copy(alpha = 0.92f),
        dark = dark
    )
}

/** 与 iOS 首页重命名弹窗一致的浅蓝玻璃，独立窗口沿用页面的屏幕坐标采样。 */
@Composable
internal fun Modifier.dshLightDialogGlass(shape: Shape): Modifier = dshBackdropGlass(
    shape = shape,
    tint = Color(0xFFC2DFFF),
    surfaceAlpha = 0.78f,
    fallback = Color(0xFFACC8E8),
    dark = false
)

/** 选择面板采样父面板，底色先遮住未模糊正文，深浅色跟随应用主题。 */
@Composable
internal fun Modifier.dshWheelSheetGlass(shape: Shape): Modifier {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val tint = if (dark) Color(0xFF252529) else Color(0xFFEDEDF2)
    return background(tint, shape).dshBackdropGlass(
        shape = shape, tint = tint, surfaceAlpha = if (dark) 0.9f else 0.85f,
        fallback = tint, dark = dark
    )
}

@Composable
private fun Modifier.dshBackdropGlass(
    shape: Shape,
    tint: Color,
    surfaceAlpha: Float,
    fallback: Color,
    dark: Boolean
): Modifier {
    val backdrop = LocalDshMenuBackdrop.current
    val surface = if (!LocalUiEffectsReduced.current && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && backdrop != null) {
        Modifier.drawPlainBackdrop(
            backdrop = backdrop,
            // 箭头位置在 Popup 完成定位后才确定。模糊库的内部裁切层不会随
            // 形状更新而重新放置，因此只采样矩形，由外层统一裁切实际轮廓。
            shape = { RectangleShape },
            effects = { blur(24.dp.toPx()) },
            onDrawSurface = { drawRect(tint.copy(alpha = surfaceAlpha)) }
        )
    } else {
        Modifier.background(fallback)
    }
    return clip(shape).then(surface).border(
        0.5.dp,
        (if (dark) Color.White else Color.Black).copy(alpha = 0.06f),
        shape
    )
}
