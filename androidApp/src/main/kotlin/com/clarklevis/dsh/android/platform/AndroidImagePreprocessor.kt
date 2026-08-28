package com.clarklevis.dsh.android.platform

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import com.clarklevis.dsh.shared.gateway.GatewayOutgoingImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class AndroidPreparedImage(
    val outgoing: GatewayOutgoingImage,
    val width: Int,
    val height: Int,
    val byteCount: Int
)

class AndroidImagePreprocessor(
    private val contentResolver: ContentResolver
) {
    suspend fun prepare(uri: Uri): AndroidPreparedImage = withContext(Dispatchers.IO) {
        val declaredSize = querySize(uri)
        if (declaredSize != null && declaredSize > MAXIMUM_INPUT_BYTES) {
            throw IllegalArgumentException("image-input-too-large")
        }
        val original = contentResolver.openInputStream(uri)?.use {
            readBytesWithLimit(it, MAXIMUM_INPUT_BYTES)
        }
            ?: throw IllegalArgumentException("无法读取所选图片")
        val bounds = decodeBounds(original)
        val transform = runCatching {
            contentResolver.openInputStream(uri)?.use {
                val exif = ExifInterface(it)
                ImageTransform(exif.rotationDegrees, exif.isFlipped)
            }
        }.getOrNull() ?: ImageTransform()
        val orientedWidth = if (transform.rotationDegrees == 90 || transform.rotationDegrees == 270) {
            bounds.second
        } else {
            bounds.first
        }
        val orientedHeight = if (transform.rotationDegrees == 90 || transform.rotationDegrees == 270) {
            bounds.first
        } else {
            bounds.second
        }
        val mediaType = contentResolver.getType(uri)?.takeIf { it.startsWith("image/") }
            ?: "image/jpeg"
        if (
            accepts(original.size, orientedWidth, orientedHeight) &&
            transform.rotationDegrees == 0 &&
            !transform.isFlipped
        ) {
            return@withContext AndroidPreparedImage(
                outgoing = GatewayOutgoingImage(
                    mediaType = mediaType,
                    base64Data = Base64.encodeToString(original, Base64.NO_WRAP),
                    name = queryDisplayName(uri)
                ),
                width = orientedWidth,
                height = orientedHeight,
                byteCount = original.size
            )
        }

        val fitted = fittedSize(orientedWidth, orientedHeight)
        val sampleSize = sampleSize(bounds.first, bounds.second, fitted.first, fitted.second)
        val decoded = BitmapFactory.decodeByteArray(
            original,
            0,
            original.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize }
        ) ?: throw IllegalArgumentException("无法解码所选图片")
        val rotated = applyTransform(decoded, transform)
        if (rotated !== decoded) decoded.recycle()
        try {
            encodeJpeg(rotated, fitted.first, fitted.second, queryDisplayName(uri))
        } finally {
            rotated.recycle()
        }
    }

    private fun encodeJpeg(
        source: Bitmap,
        initialWidth: Int,
        initialHeight: Int,
        name: String?
    ): AndroidPreparedImage {
        var width = initialWidth
        var height = initialHeight
        while (width >= 1 && height >= 1) {
            val scaled = source.scale(width, height)
            val opaque = createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Canvas(opaque).apply {
                drawColor(Color.WHITE)
                drawBitmap(scaled, 0f, 0f, null)
            }
            if (scaled !== source) scaled.recycle()
            try {
                for (quality in 90 downTo 30 step 10) {
                    val stream = ByteArrayOutputStream()
                    if (!opaque.compress(Bitmap.CompressFormat.JPEG, quality, stream)) continue
                    val encoded = stream.toByteArray()
                    if (accepts(encoded.size, width, height)) {
                        return AndroidPreparedImage(
                            outgoing = GatewayOutgoingImage(
                                mediaType = "image/jpeg",
                                base64Data = Base64.encodeToString(encoded, Base64.NO_WRAP),
                                name = name
                            ),
                            width = width,
                            height = height,
                            byteCount = encoded.size
                        )
                    }
                }
            } finally {
                opaque.recycle()
            }
            width = max(1, floor(width * 0.8).toInt())
            height = max(1, floor(height * 0.8).toInt())
            if (width == 1 && height == 1) break
        }
        throw IllegalArgumentException("无法将图片压缩到 DSH 允许的大小")
    }

    private fun decodeBounds(bytes: ByteArray): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            throw IllegalArgumentException("无法解码所选图片")
        }
        return options.outWidth to options.outHeight
    }

    private fun fittedSize(width: Int, height: Int): Pair<Int, Int> {
        val dimensionScale = min(1.0, MAXIMUM_PIXEL_SIDE.toDouble() / max(width, height))
        val pixelScale = min(1.0, sqrt(MAXIMUM_PIXELS.toDouble() / (width.toDouble() * height)))
        val scale = min(dimensionScale, pixelScale)
        return max(1, floor(width * scale).toInt()) to max(1, floor(height * scale).toInt())
    }

    private fun sampleSize(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= targetWidth && height / (sample * 2) >= targetHeight) {
            sample *= 2
        }
        return sample
    }

    private fun applyTransform(source: Bitmap, transform: ImageTransform): Bitmap {
        if (transform.rotationDegrees == 0 && !transform.isFlipped) return source
        val matrix = Matrix().apply {
            if (transform.isFlipped) postScale(-1f, 1f)
            if (transform.rotationDegrees != 0) postRotate(transform.rotationDegrees.toFloat())
        }
        return Bitmap.createBitmap(
            source,
            0,
            0,
            source.width,
            source.height,
            matrix,
            true
        )
    }

    private fun queryDisplayName(uri: Uri): String? {
        val cursor = contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        ) ?: return null
        return cursor.use {
            if (!it.moveToFirst()) null else it.getString(0)?.takeIf(String::isNotBlank)
        }
    }

    private fun querySize(uri: Uri): Long? {
        val cursor = contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.SIZE),
            null,
            null,
            null
        ) ?: return null
        return cursor.use {
            if (!it.moveToFirst() || it.isNull(0)) null else it.getLong(0).takeIf { size -> size >= 0 }
        }
    }

    private fun accepts(byteCount: Int, width: Int, height: Int): Boolean =
        byteCount <= MAXIMUM_BYTES &&
            max(width, height) <= MAXIMUM_PIXEL_SIDE &&
            width.toLong() * height <= MAXIMUM_PIXELS

    private data class ImageTransform(
        val rotationDegrees: Int = 0,
        val isFlipped: Boolean = false
    )

    companion object {
        const val MAXIMUM_BYTES = 3_670_016
        const val MAXIMUM_INPUT_BYTES = 32 * 1_024 * 1_024
        const val MAXIMUM_IMAGE_COUNT = 4
        const val MAXIMUM_TOTAL_BYTES = 12 * 1_024 * 1_024
        const val MAXIMUM_TOTAL_BASE64_CHARACTERS = 16 * 1_024 * 1_024
        const val MAXIMUM_PIXEL_SIDE = 2_000
        const val MAXIMUM_PIXELS = 40_000_000L
    }
}

internal fun readBytesWithLimit(input: InputStream, maximumBytes: Int): ByteArray {
    require(maximumBytes >= 0) { "maximumBytes must not be negative" }
    val output = ByteArrayOutputStream(minOf(maximumBytes, DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        if (total > maximumBytes) throw IllegalArgumentException("image-input-too-large")
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
