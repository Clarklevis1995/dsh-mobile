// Compose 组件沿用项目 PascalCase；图表索引、颜色和尺寸是展示参数。
@file:Suppress("FunctionNaming", "MagicNumber")

package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.shared.diagnostics.NetworkDiagnosticsSample
import kotlin.math.max

@Composable
internal fun NetworkLineChart(samples: List<NetworkDiagnosticsSample>, metric: Int, color: Color, secondColor: Color) {
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    val label = appText("最近一分钟网络状态折线图")
    val maxValue =
        if (metric == 1) {
            100.0
        } else {
            max(
                1.0,
                samples.maxOfOrNull {
                    max(metricValue(it, metric), if (metric == 3) it.sentBytesPerSecond / 1024.0 else 0.0)
                } ?: 1.0
            ) * 1.15
        }
    val empty = samples.none { metricValue(it, metric) >= 0 }
    Column {
        Text(
            networkNumber(
                maxValue,
                if (metric == 3) {
                    " KiB/s"
                } else if (metric == 1) {
                    "%"
                } else {
                    " ms"
                }
            ),
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clipToBounds()
                    .semantics { contentDescription = label }
                    .testTag("network-live-chart")
            ) {
                repeat(4) { line ->
                    val y = size.height * line / 3
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                }

                drawNetworkSeries(samples, metric, false, color, maxValue)
                if (metric == 3) drawNetworkSeries(samples, metric, true, secondColor, maxValue)
            }
            if (empty) {
                Text(
                    appText("等待探测结果…"),
                    Modifier.padding(top = 44.dp),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun DrawScope.drawNetworkSeries(
    samples: List<NetworkDiagnosticsSample>,
    metric: Int,
    transmit: Boolean,
    color: Color,
    maxValue: Double
) {
    val last = samples.lastOrNull()?.elapsedSeconds ?: 0.0
    val path = Path()
    var previousSegment: Int? = null
    var hasPoint = false
    for (sample in samples) {
        val value = if (transmit) sample.sentBytesPerSecond / 1024.0 else metricValue(sample, metric)
        if (value < 0) {
            hasPoint = false
            continue
        }
        val x = ((sample.elapsedSeconds - (last - 60)) / 60 * size.width).toFloat()
        val y = (size.height * (1 - value / maxValue)).toFloat()
        val breakSegment = metric != 3 && metric != 1 && previousSegment != sample.segment
        if (!hasPoint || breakSegment) {
            path.moveTo(x, y)
        } else {
            path.lineTo(x, y)
        }
        drawCircle(color, 1.8.dp.toPx(), Offset(x, y))
        hasPoint = true
        previousSegment = sample.segment
    }
    drawPath(path, color, style = Stroke(1.8.dp.toPx()))
}

private fun metricValue(sample: NetworkDiagnosticsSample, metric: Int): Double = when (metric) {
    1 -> sample.lossPercent
    2 -> sample.jitterMs
    3 -> sample.receivedBytesPerSecond / 1024.0
    else -> sample.roundTripMs
}
