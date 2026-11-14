package com.clarklevis.dsh.android.ui

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur

/** 回底按钮悬浮在输入区上方，其显隐不参与输入区高度和列表底部留白计算。 */
@Composable
internal fun ConversationComposerChrome(
    modifier: Modifier = Modifier,
    composerModifier: Modifier = Modifier,
    jumpVisible: Boolean,
    jumpButton: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    Column(modifier) {
        AnimatedVisibility(
            visible = jumpVisible,
            modifier = Modifier.align(Alignment.CenterHorizontally),
            enter = fadeIn(tween(160)) + scaleIn(tween(160), initialScale = 0.85f),
            exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.85f)
        ) { jumpButton() }
        Column(composerModifier) { content() }
    }
}

private val LocalComposerBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/** 只录制聊天正文，输入框与胶囊不会把自身再次采样进玻璃。 */
@Composable
internal fun ComposerGlassBackdropHost(content: @Composable (Modifier) -> Unit) {
    val backdrop = rememberLayerBackdrop()
    val reduced = LocalUiEffectsReduced.current
    CompositionLocalProvider(LocalComposerBackdrop provides backdrop.takeUnless { reduced }) {
        content(if (reduced) Modifier else Modifier.layerBackdrop(backdrop))
    }
}

/** 输入框和文件改动胶囊共用材质；着色、描边与阴影和 iOS 输入框一致。 */
@Composable
internal fun ComposerGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape,
    content: @Composable () -> Unit
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val backdrop = LocalComposerBackdrop.current
    val tint = if (dark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7)
    val glass = backdrop != null && !LocalUiEffectsReduced.current && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val material = if (glass) Modifier.drawPlainBackdrop(
        backdrop = requireNotNull(backdrop),
        shape = { shape },
        effects = { blur(24.dp.toPx()) },
        onDrawSurface = { drawRect(tint.copy(alpha = if (dark) 0.52f else 0.48f)) }
    ) else Modifier
    Surface(
        modifier = modifier.dropShadow(shape, Shadow(
            radius = 10.dp,
            color = Color.Black.copy(alpha = if (dark) 0.24f else 0.10f),
            offset = DpOffset(0.dp, 2.dp)
        )).then(material),
        shape = shape,
        color = if (glass) Color.Transparent else tint.copy(alpha = 0.96f),
        border = BorderStroke(0.7.dp, Color.White.copy(alpha = if (dark) 0.13f else 0.56f)),
        content = content
    )
}
