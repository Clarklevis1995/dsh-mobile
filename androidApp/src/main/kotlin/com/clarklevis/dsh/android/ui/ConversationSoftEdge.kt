package com.clarklevis.dsh.android.ui

import android.graphics.RenderEffect
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur

/** 模糊半径和底色都逐渐减弱，保留标题后方的正文轮廓，不绘制固定灰色材质带。 */
@Composable
internal fun Modifier.conversationTopGlass(backdrop: LayerBackdrop): Modifier {
    val tint = MaterialTheme.colorScheme.surface
    val veil = Brush.verticalGradient(
        0f to tint.copy(alpha = 0.96f),
        0.4f to tint.copy(alpha = 0.86f),
        0.7f to tint.copy(alpha = 0.70f),
        1f to tint.copy(alpha = 0f)
    )
    val surface = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Modifier.drawPlainBackdrop(
            backdrop,
            shape = { RectangleShape },
            effects = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    progressiveEdgeBlur()
                } else {
                    blur(12.dp.toPx())
                }
            },
            onDrawSurface = { drawRect(veil) }
        )
    } else {
        Modifier.background(veil)
    }
    return graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .then(surface)
        .drawWithContent {
            drawContent()
            drawRect(
                Brush.verticalGradient(0f to Color.White, 0.45f to Color.White, 1f to Color.Transparent),
                blendMode = BlendMode.DstIn
            )
        }
}

/** 两次可分离高斯采样只作用于顶部区域，避免屏幕截图回读和逐帧 CPU 模糊。 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun BackdropEffectScope.progressiveEdgeBlur() {
    val horizontal = obtainRuntimeShader("conversation-soft-edge-horizontal", SOFT_EDGE_BLUR_SHADER).apply {
        setFloatUniform("inputSize", size.width, size.height)
        setFloatUniform("direction", 1f, 0f)
        setFloatUniform("maxRadius", 12.dp.toPx())
    }
    val vertical = obtainRuntimeShader("conversation-soft-edge-vertical", SOFT_EDGE_BLUR_SHADER).apply {
        setFloatUniform("inputSize", size.width, size.height)
        setFloatUniform("direction", 0f, 1f)
        setFloatUniform("maxRadius", 12.dp.toPx())
    }
    renderEffect = RenderEffect.createChainEffect(
        RenderEffect.createRuntimeShaderEffect(vertical, "source"),
        RenderEffect.createRuntimeShaderEffect(horizontal, "source")
    )
}

private val SOFT_EDGE_BLUR_SHADER = """
    uniform shader source;
    uniform float2 inputSize;
    uniform float2 direction;
    uniform float maxRadius;

    half4 sampleAt(float2 p) {
        return source.eval(clamp(p, float2(0.5), inputSize - float2(0.5)));
    }

    half4 main(float2 p) {
        float progress = clamp(1.0 - p.y / inputSize.y, 0.0, 1.0);
        float radius = maxRadius * smoothstep(0.0, 1.0, progress);
        float2 step = direction * radius / 3.230769;
        half4 result = sampleAt(p) * 0.227027;
        result += sampleAt(p + step * 1.384615) * 0.316216;
        result += sampleAt(p - step * 1.384615) * 0.316216;
        result += sampleAt(p + step * 3.230769) * 0.070270;
        result += sampleAt(p - step * 3.230769) * 0.070270;
        return result;
    }
""".trimIndent()
