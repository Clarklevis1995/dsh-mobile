package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay

/**
 * Tailscale 的九点标记，按官方 macOS 菜单栏素材实测的几何与时序绘制。
 *
 * 依据（不是照抄图形，是从本机安装的客户端里量出来的）：
 *   /Applications/Tailscale.app/Contents/Resources/Assets.car
 *     StatusBarIconDot1 … StatusBarIconDot16  连接中动画，16 帧
 *     StatusBarIcon                           已连接（静态）
 *     StatusBarIconDimmed                     未连接（全暗，不动）
 *   把 16 帧放大到 176px 后量：九个点的中心在 176 画布的 39.5/87.5/135.5，
 *   间距 48px，点半径约 16.2px —— 换算到 48 单位视口就是 centers=10.8/23.9/37.0、r=4.4。
 *   静态标记（已连接）点亮的点实测为中间一行加下中：位置 4、5、6、8（1 起算），
 *   其余五个网格点恒为 alpha=77/255（≈0.30）。
 *   每帧点亮哪些点是把 16 帧逐点采样 alpha 得到的，见下面 ConnectingFrames。
 *
 * 刻意不变色：连接中与已连接都用同一个白色，只靠"闪/不闪"区分。
 * 帧间隔官方没有公开依据（各平台 GUI 不开源），100ms 是我们自己的取值。
 */
internal object TailscaleMark {
    /** 48 单位视口里的九点中心，索引 = 行*3 + 列，左上为 0。 */
    val centers: List<Pair<Float, Float>> = listOf(
        10.8f to 10.8f, 23.9f to 10.8f, 37.0f to 10.8f,
        10.8f to 23.9f, 23.9f to 23.9f, 37.0f to 23.9f,
        10.8f to 37.0f, 23.9f to 37.0f, 37.0f to 37.0f
    )
    const val radius = 4.4f
    const val viewport = 48f

    /** 未点亮的点的不透明度：实测 77/255。 */
    const val dimAlpha = 77f / 255f

    /** 连接中的 16 帧（0 起算的点索引），逐帧采样官方素材得到。 */
    val connectingFrames: List<Set<Int>> = listOf(
        setOf(1, 2, 3, 5, 8),
        setOf(1, 2, 5, 7),
        setOf(1, 2, 8),
        setOf(2, 4),
        setOf(1),
        setOf(5),
        emptySet(),
        setOf(2),
        setOf(6),
        setOf(6),
        setOf(6, 7),
        setOf(3, 6, 7),
        setOf(3, 4, 7),
        setOf(3, 4, 7, 8),
        setOf(3, 4, 5, 8),
        setOf(1, 4, 5, 6, 8)
    )

    /** 已连接时的静态点亮：官方 StatusBarIcon 实测为 4、5、6、8（1 起算）。 */
    val connectedFrame: Set<Int> = setOf(3, 4, 5, 7)

    const val frameMillis = 100L
}

/**
 * 画九点标记。[connected] 为真时是静态的"⊥"点亮；为假时按官方 16 帧的时序闪。
 * 颜色统一用传入的 color（调用方给白色），不做状态配色。
 */
@Composable
internal fun TailscaleMarkIcon(connected: Boolean, color: Color, modifier: Modifier = Modifier) {
    var frame by remember { mutableIntStateOf(0) }
    if (!connected) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(TailscaleMark.frameMillis)
                frame = (frame + 1) % TailscaleMark.connectingFrames.size
            }
        }
    }
    val lit = if (connected) TailscaleMark.connectedFrame else TailscaleMark.connectingFrames[frame]
    Canvas(modifier) {
        val k = size.minDimension / TailscaleMark.viewport
        TailscaleMark.centers.forEachIndexed { index, (ux, uy) ->
            drawCircle(
                color = color.copy(alpha = if (index in lit) color.alpha else color.alpha * TailscaleMark.dimAlpha),
                radius = TailscaleMark.radius * k,
                center = Offset(ux * k, uy * k)
            )
        }
    }
}
