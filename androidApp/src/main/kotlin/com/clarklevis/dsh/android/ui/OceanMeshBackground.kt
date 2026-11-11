package com.clarklevis.dsh.android.ui

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.MeshGradientPainter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.core.os.trace
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** 纯几何网格与矢量光弧，不使用位图贴图或逐像素噪声着色器。 */
@Composable
internal fun OceanMeshBackground(
    time: () -> Float,
    modifier: Modifier = Modifier,
    largeWindow: Boolean = false
) {
    val fieldColors = if (largeWindow) oceanTabletMeshColors else oceanMeshColors
    val primaryStyle = if (largeWindow) tabletPrimaryRibbon else phonePrimaryRibbon
    val bottomStyle = if (largeWindow) tabletBottomRibbon else phoneBottomRibbon
    val mesh = remember(time, largeWindow) {
        // drawVertices 从 API 29 开始支持硬件加速；旧系统使用标准矢量渐变。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MeshGradientPainter(rows = 4, columns = 4, hasBicubicColor = false) {
                val t = time() * 0.10f
                for (row in 0..4) {
                    for (column in 0..4) {
                        val interior = row in 1..3 && column in 1..3
                        val phase = row * 1.7f + column * 1.3f
                        // 固定外缘，避免控制点移动后窗口边界露底。
                        val x = column * 0.25f + if (interior) sin(t + phase) * 0.055f else 0f
                        val y = row * 0.25f + if (interior) cos(t * 0.83f + phase) * 0.045f else 0f
                        setVertex(row, column, Offset(x, y), fieldColors[row * 5 + column])
                    }
                }
            }
        } else null
    }
    val ribbon = remember(largeWindow) {
        if (mesh != null) oceanRibbonPainter(primaryStyle) else null
    }
    val bottomRibbon = remember(largeWindow) {
        if (mesh != null) oceanRibbonPainter(bottomStyle) else null
    }
    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().drawWithCache {
            val fallback = Brush.radialGradient(
                colors = if (largeWindow) {
                    listOf(Color(0xFF124D84), Color(0xFF10375C), Color(0xFF091521))
                } else listOf(Color(0xFF092C54), Color(0xFF06172D), Color(0xFF02070C)),
                center = Offset(size.width * if (largeWindow) 0.52f else 0.85f, size.height * 0.18f),
                radius = max(size.width, size.height) * if (largeWindow) 1.05f else 0.80f
            )
            // 按逻辑尺寸细分网格，再放大矢量顶点；不是低分辨率位图或纹理放大。
            // 官方目标间距为 8 像素，这里换为约 24dp 的几何间距，控制每帧颜色插值量。
            // 最终仍在全尺寸 Canvas 上绘制矢量三角形，不降低像素输出分辨率。
            val geometryScale = density.coerceAtLeast(1f) * 3f
            val geometrySize = Size(size.width / geometryScale, size.height / geometryScale)
            onDrawBehind {
                if (mesh == null) {
                    drawRect(fallback)
                } else {
                    scale(geometryScale, pivot = Offset.Zero) {
                        trace("OceanMesh:field") { with(mesh) { draw(geometrySize) } }
                    }
                }
            }
        })
        OceanRibbon(ribbon, time, primaryStyle, lower = false)
        OceanRibbon(bottomRibbon, time, bottomStyle, lower = true)
    }
}

/** 光弧记录为独立矢量绘制指令；动画只更新图层变换，不重复进行网格颜色插值。 */
@Composable
private fun OceanRibbon(
    painter: MeshGradientPainter?,
    time: () -> Float,
    style: OceanRibbonStyle,
    lower: Boolean
) {
    Box(
        Modifier.fillMaxSize()
            .graphicsLayer { translationY = sin(time() * 0.075f) * size.height * 0.012f }
            .drawWithCache {
                val geometryScale = density.coerceAtLeast(1f) * 3f
                val geometrySize = Size(size.width / geometryScale, size.height / geometryScale)
                // 旧系统沿同一对角方向绘制透明渐变光带，不使用离屏模糊。
                val direction = Offset(size.width, size.height * (style.slope + style.curvature))
                val length = hypot(direction.x, direction.y).coerceAtLeast(1f)
                val normal = Offset(-direction.y / length, direction.x / length)
                val center = Offset(size.width * 0.5f, size.height * style.centerY(0.5f))
                val halfWidth = size.height * style.width * 0.5f * direction.x / length
                val colors = style.colors
                val fallback = Brush.linearGradient(
                    *colors.mapIndexed { index, color ->
                        index / (colors.size - 1f) to color
                    }.toTypedArray(),
                    start = center - normal * halfWidth,
                    end = center + normal * halfWidth
                )
                val fallbackBounds = ribbonBounds(style, size)
                onDrawBehind {
                    if (painter == null) {
                        clipPath(fallbackBounds) { drawRect(fallback) }
                    } else {
                        scale(geometryScale, pivot = Offset.Zero) {
                            trace(if (lower) "OceanMesh:lowerRibbon" else "OceanMesh:ribbon") {
                                with(painter) { draw(geometrySize) }
                            }
                        }
                    }
                }
            }
    )
}

/** 大屏独立构图：主光带从左上角进入，更宽地穿过中部；手机保留较细的弯折光带。 */
private fun oceanRibbonPainter(style: OceanRibbonStyle): MeshGradientPainter =
    MeshGradientPainter(rows = RIBBON_ROWS, columns = RIBBON_COLUMNS, hasBicubicColor = false) {
        val stepX = 1.30f / RIBBON_COLUMNS
        for (row in 0..RIBBON_ROWS) {
            val bandFraction = row.toFloat() / RIBBON_ROWS - 0.5f
            for (column in 0..RIBBON_COLUMNS) {
                val x = column * stepX - 0.15f
                val centerY = style.centerY(x)
                val width = style.widthAt(x)
                val band = bandFraction * width
                // 显式切线保持相邻色带平行，避免自动切线让柔边局部挤压、形成亮斑。
                val tangent = Offset(stepX / 3f, style.edgeSlope(x, bandFraction) * stepX / 3f)
                val verticalTangent = Offset(0f, width / RIBBON_ROWS / 3f)
                setVertex(
                    row, column, Offset(x, centerY + band), style.colors[row],
                    leftControlPoint = -tangent,
                    rightControlPoint = tangent,
                    topControlPoint = -verticalTangent,
                    bottomControlPoint = verticalTangent
                )
            }
        }
    }

// 中心是等亮度平台，外缘连续渐隐；不叠加单独的中心高光。
private fun ribbonColors(color: Color, opacity: Float): List<Color> =
    List(RIBBON_ROWS + 1) { row ->
        val distance = abs(row.toFloat() / RIBBON_ROWS - 0.5f)
        val feather = smoothStep((0.5f - distance) / 0.375f)
        color.copy(alpha = opacity * feather)
    }

private fun smoothStep(value: Float): Float {
    val t = value.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private const val RIBBON_ROWS = 16
private const val RIBBON_COLUMNS = 8

private val oceanRibbonColors = ribbonColors(Color(0xFFD4D3C8), opacity = 0.30f)
private val oceanBottomRibbonColors = ribbonColors(Color(0xFFC8C6B6), opacity = 0.20f)
// 大屏主光带使用低亮度、高覆盖率的内芯，减少蓝色底纹透入造成的空心感。
private val tabletRibbonColors = ribbonColors(Color(0xFF627785), opacity = 0.94f)

private data class OceanRibbonStyle(
    val startY: Float,
    val slope: Float,
    val curvature: Float,
    val width: Float,
    val colors: List<Color>
) {
    fun centerY(x: Float): Float = startY + slope * x + curvature * x * x

    fun widthAt(x: Float): Float = width * (1f - 0.45f * smoothStep((x - 0.35f) / 0.65f))

    fun edgeSlope(x: Float, bandFraction: Float): Float {
        val t = ((x - 0.35f) / 0.65f).coerceIn(0f, 1f)
        val widthSlope = -width * 0.45f * 6f * t * (1f - t) / 0.65f
        return slope + 2f * curvature * x + bandFraction * widthSlope
    }
}

/** 旧系统的渐变也限制在收窄后的路径内，避免尾端柔边相交。 */
private fun ribbonBounds(style: OceanRibbonStyle, size: Size): Path = Path().apply {
    fun point(x: Float, fraction: Float) =
        Offset(x * size.width, (style.centerY(x) + fraction * style.widthAt(x)) * size.height)
    val start = point(-0.15f, -0.5f)
    moveTo(start.x, start.y)
    for (column in 1..32) {
        val point = point(-0.15f + column * 1.30f / 32f, -0.5f)
        lineTo(point.x, point.y)
    }
    for (column in 32 downTo 0) {
        val point = point(-0.15f + column * 1.30f / 32f, 0.5f)
        lineTo(point.x, point.y)
    }
    close()
}

private val phonePrimaryRibbon = OceanRibbonStyle(0.30f, 0.50f, -0.30f, 0.36f, oceanRibbonColors)
private val phoneBottomRibbon = OceanRibbonStyle(0.99f, -0.03f, -0.17f, 0.40f, oceanBottomRibbonColors)
private val tabletPrimaryRibbon = OceanRibbonStyle(0.015f, 1.10f, -0.53f, 0.44f, tabletRibbonColors)
private val tabletBottomRibbon = OceanRibbonStyle(0.99f, 0.03f, -0.07f, 0.36f, oceanBottomRibbonColors)

private val oceanMeshColors = arrayOf(
    Color(0xFF041020), Color(0xFF061A34), Color(0xFF082344), Color(0xFF061D38), Color(0xFF041329),
    Color(0xFF030A13), Color(0xFF06152A), Color(0xFF082344), Color(0xFF082A50), Color(0xFF06203C),
    Color(0xFF02070D), Color(0xFF030C18), Color(0xFF041227), Color(0xFF061D3A), Color(0xFF08274A),
    Color(0xFF010508), Color(0xFF02070D), Color(0xFF030D1A), Color(0xFF07162B), Color(0xFF0A2038),
    Color(0xFF080909), Color(0xFF0B0E10), Color(0xFF0B1621), Color(0xFF0A1C30), Color(0xFF07213B)
)

private val oceanTabletMeshColors = arrayOf(
    Color(0xFF09233D), Color(0xFF0D3F72), Color(0xFF0D447B), Color(0xFF0B3B6D), Color(0xFF082B50),
    Color(0xFF0D3A64), Color(0xFF124B7F), Color(0xFF124D84), Color(0xFF10497F), Color(0xFF0B3E6F),
    Color(0xFF0B3155), Color(0xFF104371), Color(0xFF12477A), Color(0xFF144774), Color(0xFF10375C),
    Color(0xFF071A30), Color(0xFF0B2B49), Color(0xFF123B5F), Color(0xFF173C5B), Color(0xFF183546),
    Color(0xFF091521), Color(0xFF102332), Color(0xFF1B303A), Color(0xFF243B43), Color(0xFF112A3D)
)
