package com.clarklevis.dsh.android.ui

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/** Android 7–11 共用一张低分辨率模糊背景。采样只包含背景，滚动时按卡片位置取样。 */
internal class LegacyFrostedBackdrop {
    internal data class Sample(val bitmap: ImageBitmap, val size: IntSize)
    var origin by mutableStateOf(Offset.Zero)
    var sample by mutableStateOf<Sample?>(null)
    var sampling = false
    var sampledAt = 0L
    var attemptedSize = IntSize.Zero
}

@Composable
internal fun Modifier.captureFrostedBackdrop(backdrop: LegacyFrostedBackdrop): Modifier {
    val layer = rememberGraphicsLayer()
    val scope = rememberCoroutineScope()
    return onGloballyPositioned { backdrop.origin = it.positionInRoot() }.drawWithContent {
        drawContent()
        val fullSize = size
        val sourceSize = IntSize(fullSize.width.roundToInt(), fullSize.height.roundToInt())
        val now = SystemClock.uptimeMillis()
        if (sourceSize.width == 0 || sourceSize.height == 0 || backdrop.sampling ||
            (backdrop.attemptedSize == sourceSize && now - backdrop.sampledAt < 750)) return@drawWithContent
        backdrop.sampledAt = now
        backdrop.attemptedSize = sourceSize
        backdrop.sampling = true
        val scale = (224f / fullSize.maxDimension).coerceAtMost(1f)
        val sampleWidth = (sourceSize.width * scale).roundToInt().coerceAtLeast(1)
        // 旧系统的 ImageReader 读回按行排列，补齐宽度可以避免 GPU 的行对齐填充进入像素。
        val sampleSize = IntSize(
            (sampleWidth + 63) / 64 * 64,
            (sourceSize.height * scale).roundToInt().coerceAtLeast(1)
        )
        layer.record(sampleSize) {
            drawContext.size = fullSize
            scale(sampleSize.width / fullSize.width, sampleSize.height / fullSize.height, pivot = Offset.Zero) {
                this@drawWithContent.drawContent()
            }
        }
        val blurRadiusPx = 15.dp.toPx()
        val radiusX = (blurRadiusPx * sampleSize.width / fullSize.width).roundToInt().coerceAtLeast(1)
        val radiusY = (blurRadiusPx * sampleSize.height / fullSize.height).roundToInt().coerceAtLeast(1)
        scope.launch {
            try {
                withFrameNanos { }
                val snapshot = withTimeoutOrNull(750) { layer.toImageBitmap() } ?: return@launch
                val blurred = withContext(Dispatchers.Default) { snapshot.asAndroidBitmap().blurBackdrop(radiusX, radiusY) }
                backdrop.sample = LegacyFrostedBackdrop.Sample(blurred, sourceSize)
            } catch (error: CancellationException) {
                throw error
            } catch (_: RuntimeException) {
                // 设备读回失败时，下一次背景绘制可重试，期间继续使用已取得的画面。
            } finally {
                backdrop.sampling = false
            }
        }
    }
}

@Composable
internal fun Modifier.drawFrostedBackdrop(backdrop: LegacyFrostedBackdrop): Modifier {
    var origin by remember { mutableStateOf(Offset.Zero) }
    return onGloballyPositioned { origin = it.positionInRoot() }.drawWithContent {
        backdrop.sample?.let { sample ->
            drawImage(sample.bitmap, dstOffset = (backdrop.origin - origin).round(),
                dstSize = sample.size, srcOffset = IntOffset.Zero)
        }
        drawContent()
    }
}

private fun Bitmap.blurBackdrop(radiusX: Int, radiusY: Int): ImageBitmap {
    val readable = copy(Bitmap.Config.ARGB_8888, false) ?: return asImageBitmap()
    val pixels = IntArray(width * height)
    readable.getPixels(pixels, 0, width, 0, 0, width, height)
    readable.recycle()
    val intermediate = IntArray(pixels.size)
    blurLines(pixels, intermediate, width, height, 1, width, radiusX)
    blurLines(intermediate, pixels, height, width, width, 1, radiusY)
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** 水平与垂直各一次滑动均值，只保留每行或每列当前窗口的色值总和。 */
private fun blurLines(source: IntArray, output: IntArray, length: Int, lines: Int,
    step: Int, stride: Int, radius: Int) {
    val count = radius * 2 + 1
    repeat(lines) { line ->
        val start = line * stride
        fun pixelAt(index: Int): Int = source[start + index.coerceIn(0, length - 1) * step]
        var red = 0
        var green = 0
        var blue = 0
        for (index in -radius..radius) {
            val pixel = pixelAt(index)
            red += (pixel ushr 16) and 0xff
            green += (pixel ushr 8) and 0xff
            blue += pixel and 0xff
        }
        repeat(length) { index ->
            output[start + index * step] = (0xff shl 24) or ((red / count) shl 16) or
                ((green / count) shl 8) or (blue / count)
            val leaving = pixelAt(index - radius)
            val entering = pixelAt(index + radius + 1)
            red += ((entering ushr 16) and 0xff) - ((leaving ushr 16) and 0xff)
            green += ((entering ushr 8) and 0xff) - ((leaving ushr 8) and 0xff)
            blue += (entering and 0xff) - (leaving and 0xff)
        }
    }
}
