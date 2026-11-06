package com.clarklevis.dsh.android.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.android.platform.AndroidVoiceInputAdapter
import com.clarklevis.dsh.shared.facade.SharedVoiceInputSnapshot
import com.clarklevis.dsh.shared.facade.VoiceInputInteraction
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.CancellationException

/** 独立的语音输入区，文字输入框不会安装录音手势。 */
@Suppress("ktlint:standard:function-naming", "FunctionNaming", "MagicNumber")
@Composable
internal fun VoiceHoldInput(voice: AndroidVoiceInputAdapter, modifier: Modifier = Modifier) {
    val state = voice.state
    Box(
        modifier.fillMaxWidth().heightIn(min = 38.dp)
            .testTag("composer-voice-hold")
            .voiceHoldGesture(
                voice,
                state.voiceMode && (!state.active || state.inputBlocked || state.touchPending),
                LocalDensity.current.density
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(appText("按住说话"), style = MaterialTheme.typography.bodyLarge)
    }
}

/** 底部遮罩的固定几何比例；粒子收束与柱高只使用共享层提供的真实音量。 */
@Suppress("ktlint:standard:function-naming", "FunctionNaming", "MagicNumber")
@Composable
fun VoiceInputPanel(
    voice: AndroidVoiceInputAdapter,
    modifier: Modifier = Modifier,
) {
    val currentState = voice.state
    // 退出动画保留最后可见画面，避免取消状态在淡出前瞬间切回蓝色。
    val presentation = remember(voice) { VoicePanelPresentation(currentState) }
    if (currentState.overlayVisible) presentation.snapshot = currentState
    val state = presentation.snapshot
    val transition = rememberInfiniteTransition(label = "voice-particles")
    val time by transition.animateFloat(
        0f,
        (2 * PI).toFloat(),
        infiniteRepeatable(tween(20_000, easing = LinearEasing)),
        label = "particle-drift",
    )
    val gathering by animateFloatAsState(state.visualActivity, tween(if (state.waveformActive) 160 else 320), label = "particle-gathering")
    val isDark = isSystemInDarkTheme()
    val palette = voiceOverlayPalette(isDark, state.cancelArmed)
    val haloColor = if (isDark) MaterialTheme.colorScheme.background else Color.White
    val haloStops =
        remember(haloColor) {
            Array(33) { index ->
                val radius = index / 32f
                val progress = ((radius - 0.5f) / 0.5f).coerceIn(0f, 1f)
                radius to haloColor.copy(alpha = 1 - progress * progress * (3 - 2 * progress))
            }
        }
    val base = palette.base
    val glow = palette.glow
    val ink = palette.ink
    val interactions = remember { MutableInteractionSource() }
    Box(
        modifier
            .testTag(
                "voice-recording-overlay",
            ).clickable(enabled = currentState.overlayVisible, interactionSource = interactions, indication = null) {},
    ) {
        Canvas(Modifier.fillMaxSize()) {
            voice.overlayDrawn()
            drawVoiceOuterHalo(haloStops)
            drawIntoCanvas { canvas ->
                canvas.saveLayer(Rect(Offset.Zero, size), Paint())
                drawVoiceBackdrop(base, glow)
                repeat(PARTICLE_COUNT) { drawVoiceParticle(it, state, time, gathering, ink) }
                drawVoiceEllipticalMask()
                canvas.restore()
            }
        }
        Column(
            modifier = Modifier.align(Alignment.Center).padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val textColor = if (state.recordingLimitApproaching) MaterialTheme.colorScheme.error else ink.copy(alpha = 0.9f)
            Text(state.status?.let { appText(it) }.orEmpty(), color = textColor, fontSize = 17.sp)
            if (state.phase == "recording") {
                Text(state.recordingTimeLabel, color = textColor, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

/** 浅色模式使用明亮雾蓝与深色前景，深色模式保留柔和亮度；两端色值一致。 */
private data class VoiceOverlayPalette(
    val base: Color,
    val glow: Color,
    val ink: Color,
)

@Suppress("MagicNumber")
private fun voiceOverlayPalette(
    isDark: Boolean,
    cancelling: Boolean,
): VoiceOverlayPalette =
    when {
        isDark && cancelling -> VoiceOverlayPalette(Color(0xFF5B3543), Color(0xFF895668), Color(0xFFFAE5EB))
        isDark -> VoiceOverlayPalette(Color(0xFF203B5C), Color(0xFF39658F), Color(0xFFE3F0FF))
        cancelling -> VoiceOverlayPalette(Color(0xFFF0ACBA), Color(0xFFFFD6DA), Color(0xFF642434))
        else -> VoiceOverlayPalette(Color(0xFF73A9E7), Color(0xFFAFD7FA), Color(0xFF173C65))
    }

private class VoicePanelPresentation(
    var snapshot: SharedVoiceInputSnapshot,
)

private const val BAR_COUNT = 45
private const val PARTICLES_PER_BAR = 13
private const val PARTICLE_COUNT = BAR_COUNT * PARTICLES_PER_BAR

private fun particleSeed(
    index: Int,
    salt: Double,
): Float {
    val value = sin(index * 127.1 + salt) * 43758.5453
    return (value - floor(value)).toFloat()
}

// 以下比例仅描述遮罩的渐变、粒子排布与柱形几何。
@Suppress("MagicNumber")
private fun DrawScope.drawVoiceBackdrop(
    base: Color,
    glow: Color,
) {
    drawRect(base)
    drawRect(
        Brush.radialGradient(
            listOf(glow.copy(alpha = 0.72f), Color.Transparent),
            center = Offset(center.x, size.height * 0.63f),
            radius = size.width * 0.9f,
        ),
    )
}

/** 独立绘制在彩色蒙层后方，避免被内层蒙版裁掉外缘。 */
@Suppress("MagicNumber")
private fun DrawScope.drawVoiceOuterHalo(stops: Array<Pair<Float, Color>>) {
    val fadeHeight = size.height * 0.78f
    val radiusX = size.width * 1.12f
    clipRect(bottom = fadeHeight) {
        withTransform({
            translate(left = size.width / 2, top = fadeHeight)
            scale(scaleX = radiusX, scaleY = fadeHeight, pivot = Offset.Zero)
        }) {
            drawRect(
                Brush.radialGradient(*stops, center = Offset.Zero, radius = 1f),
                topLeft = Offset(-size.width / (2 * radiusX), -1f),
                size = Size(size.width / radiusX, 1f),
            )
        }
    }
}

// .5 等于视图侧边的归一化半径；加宽羽化范围，保证与下方实色区域无接缝。
private val VOICE_EDGE_STOPS =
    Array(33) { index ->
        val radius = index / 32f
        val progress = ((radius - 0.5f) / 0.5f).coerceIn(0f, 1f)
        radius to Color.Black.copy(alpha = 1 - progress * progress * (3 - 2 * progress))
    }

/** 顶部按高度比例平滑过渡到页面背景，下方覆盖导航栏。 */
@Suppress("MagicNumber")
private fun DrawScope.drawVoiceEllipticalMask() {
    val fadeHeight = (size.height * 0.72f).roundToInt().toFloat()
    val radiusX = size.width
    clipRect(bottom = fadeHeight) {
        withTransform({
            translate(left = size.width / 2, top = fadeHeight)
            scale(scaleX = radiusX, scaleY = fadeHeight, pivot = Offset.Zero)
        }) {
            drawRect(
                Brush.radialGradient(
                    *VOICE_EDGE_STOPS,
                    center = Offset.Zero,
                    radius = 1f,
                ),
                topLeft = Offset(-size.width / (2 * radiusX), -1f),
                size = Size(size.width / radiusX, 1f),
                blendMode = BlendMode.DstIn,
            )
        }
    }
}

@Suppress("MagicNumber")
private fun DrawScope.drawVoiceParticle(
    index: Int,
    state: SharedVoiceInputSnapshot,
    time: Float,
    gathering: Float,
    ink: Color,
) {
    val unit = 1.dp.toPx()
    val column = index % BAR_COUNT
    val row = index / BAR_COUNT
    val pitch = minOf(6.4f * unit, (size.width - 48 * unit) / BAR_COUNT)
    val waveWidth = pitch * (BAR_COUNT - 1)
    val edge = sin(column.toFloat() / (BAR_COUNT - 1) * PI).toFloat()
    val height = (5 + state.waveformLevels[column] * 56 * (0.35f + 0.65f * edge)) * unit
    val freeX = 16 * unit + particleSeed(index, 1.0) * (size.width - 32 * unit)
    val freeY = size.height * (0.16f + particleSeed(index, 8.0) * 0.79f)
    // 整数频率让 20 秒漂移周期首尾连续，避免循环时跳变。
    val frequencyX = if (particleSeed(index, 3.0) > 0.5f) 2 else 1
    val frequencyY = if (particleSeed(index, 4.0) > 0.5f) 2 else 1
    val driftX = (sin(time * frequencyX + index) * 14 + cos(time + particleSeed(index, 10.0) * (2 * PI).toFloat()) * 5) * unit
    val driftY = (cos(time * frequencyY + index) * 12 + sin(time + particleSeed(index, 11.0) * (2 * PI).toFloat()) * 4) * unit
    val targetX = (size.width - waveWidth) / 2 + column * pitch
    val centerY = size.height * 0.68f
    val targetY = centerY + (row.toFloat() / (PARTICLES_PER_BAR - 1) - 0.5f) * height
    val x = (freeX + driftX) * (1 - gathering) + targetX * gathering
    val y = (freeY + driftY) * (1 - gathering) + targetY * gathering
    // 自由粒子独立漂浮与呼吸；波形目标使用中心包络和更轻的扰动。
    val freePhase = particleSeed(index, 12.0) * (2 * PI).toFloat()
    val sizeFrequency = if (particleSeed(index, 13.0) > 0.5f) 2 else 1
    val lightFrequency = if (particleSeed(index, 14.0) > 0.5f) 2 else 1
    val freePulse = sin(time * sizeFrequency + freePhase)
    val freeShimmer = cos(time * lightFrequency + freePhase * 1.7f)
    val freeRadius = (0.7f + particleSeed(index, 5.0) * 0.8f) * (1 + freePulse * 0.18f)
    val freeAlpha = (0.12f + particleSeed(index, 6.0) * 0.26f) * (0.72f + freeShimmer * 0.28f)
    val phase = particleSeed(index, 9.0) * (2 * PI).toFloat()
    val pulse = sin(time + phase)
    val shimmer = cos(time + phase * 1.7f)
    val waveRadius = (0.8f + edge * 0.5f + (particleSeed(index, 5.0) - 0.5f) * 0.12f) * (1 + pulse * 0.025f)
    val waveAlpha = (0.26f + edge * 0.4f + (particleSeed(index, 6.0) - 0.5f) * 0.06f) * (0.98f + shimmer * 0.02f)
    val radius = (freeRadius * (1 - gathering) + waveRadius * gathering) * unit
    val alpha = freeAlpha * (1 - gathering) + waveAlpha * gathering
    drawCircle(ink.copy(alpha = alpha), radius, Offset(x, y))
}

/** 仅挂在语音态的按住说话区域；短按丢弃预采集，达到共享阈值后接管触摸。 */
fun Modifier.voiceHoldGesture(
    voice: AndroidVoiceInputAdapter,
    enabled: Boolean,
    density: Float,
): Modifier =
    if (!enabled) {
        this
    } else {
        pointerInput(voice, enabled) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                voice.touchDown(down.uptimeMillis)
                var holding = false
                try {
                    var completed = false
                    withTimeoutOrNull(VoiceInputInteraction.LONG_PRESS_MILLISECONDS) {
                        waitForUpOrCancellation(PointerEventPass.Initial)
                        completed = true
                    }
                    if (completed) return@awaitEachGesture
                    currentEvent.changes.forEach { it.consume() }
                    holding = true
                    voice.press()
                    while (true) {
                        val change =
                            awaitPointerEvent(
                                PointerEventPass.Initial,
                            ).changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) {
                            change?.consume()
                            voice.release()
                            break
                        }
                        voice.move((change.position.y - down.position.y) / density)
                        change.consume()
                    }
                } catch (cancelled: CancellationException) {
                    if (holding) voice.cancel()
                    throw cancelled
                } finally {
                    if (!holding) voice.touchFinished()
                }
            }
        }
    }
