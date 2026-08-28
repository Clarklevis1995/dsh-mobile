package com.clarklevis.dsh.android.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

class AndroidAttachmentThumbnailer {
    suspend fun decode(
        bytes: ByteArray,
        targetWidthPixels: Int = MAXIMUM_THUMBNAIL_SIDE,
        targetHeightPixels: Int = MAXIMUM_THUMBNAIL_SIDE
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (bytes.isEmpty() || bytes.size > MAXIMUM_COMPRESSED_BYTES) return@withContext null
        if (targetWidthPixels <= 0 || targetHeightPixels <= 0) return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (!validSourceBounds(bounds.outWidth, bounds.outHeight)) return@withContext null
        val maximumSide = minOf(MAXIMUM_THUMBNAIL_SIDE, max(targetWidthPixels, targetHeightPixels))
        val maximumPixels = minOf(
            MAXIMUM_THUMBNAIL_PIXELS,
            targetWidthPixels.toLong() * targetHeightPixels
        )
        val sample = thumbnailSampleSize(bounds.outWidth, bounds.outHeight, maximumSide, maximumPixels)
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return@withContext null
        val sideScale = maximumSide.toDouble() / max(decoded.width, decoded.height)
        val pixelScale = kotlin.math.sqrt(
            maximumPixels.toDouble() / (decoded.width.toLong() * decoded.height).coerceAtLeast(1L)
        )
        val scale = minOf(1.0, sideScale, pixelScale)
        if (scale >= 1.0) return@withContext decoded
        val scaled = decoded.scale(
            max(1, (decoded.width * scale).toInt()),
            max(1, (decoded.height * scale).toInt())
        )
        if (scaled !== decoded) decoded.recycle()
        scaled
    }

    private fun validSourceBounds(width: Int, height: Int): Boolean =
        width in 1..MAXIMUM_SOURCE_SIDE && height in 1..MAXIMUM_SOURCE_SIDE &&
            width.toLong() * height <= MAXIMUM_SOURCE_PIXELS

    companion object {
        const val MAXIMUM_COMPRESSED_BYTES = 32 * 1_024 * 1_024
        const val MAXIMUM_SOURCE_SIDE = 16_384
        const val MAXIMUM_SOURCE_PIXELS = 80_000_000L
        const val MAXIMUM_THUMBNAIL_SIDE = 1_024
        const val MAXIMUM_THUMBNAIL_PIXELS = 1_048_576L
    }
}

internal fun thumbnailSampleSize(
    width: Int,
    height: Int,
    maximumSide: Int = AndroidAttachmentThumbnailer.MAXIMUM_THUMBNAIL_SIDE,
    maximumPixels: Long = AndroidAttachmentThumbnailer.MAXIMUM_THUMBNAIL_PIXELS
): Int {
    var sample = 1
    while (
        max(width / sample, height / sample) > maximumSide ||
        (width / sample).toLong() * (height / sample) > maximumPixels
    ) {
        sample *= 2
    }
    return sample
}
