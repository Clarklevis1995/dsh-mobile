package com.clarklevis.dsh.android.ui
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.caverock.androidsvg.SVG
import com.clarklevis.dsh.android.platform.thumbnailSampleSize
import java.io.File

private const val MAXIMUM_IMAGE_SIDE = 2048
private const val DEFAULT_SVG_SIDE = 512f

/** 限制解码后的像素数，不把远端原图整份读入内存；新系统同时处理 EXIF 方向。 */
internal fun decodeWorkspaceImage(file: File, name: String, mediaType: String): Bitmap = when {
    name.endsWith(".svg", true) || mediaType.substringBefore(';').trim().equals("image/svg+xml", true) -> decodeSvg(file)
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> decodeModernImage(file)
    else -> decodeLegacyImage(file)
}

private fun decodeSvg(file: File): Bitmap {
    val svg = file.inputStream().use(SVG::getFromInputStream)
    val width = svg.documentWidth.takeIf { it > 0 } ?: svg.documentViewBox?.width() ?: DEFAULT_SVG_SIDE
    val height = svg.documentHeight.takeIf { it > 0 } ?: svg.documentViewBox?.height() ?: DEFAULT_SVG_SIDE
    val scale = minOf(1f, MAXIMUM_IMAGE_SIDE / maxOf(width, height))
    return Bitmap.createBitmap(
        (width * scale).toInt().coerceAtLeast(1),
        (height * scale).toInt().coerceAtLeast(1),
        Bitmap.Config.ARGB_8888
    ).also {
        svg.setDocumentWidth("100%")
        svg.setDocumentHeight("100%")
        svg.renderToCanvas(Canvas(it))
    }
}

@androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
private fun decodeModernImage(file: File): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
    val scale = minOf(1f, MAXIMUM_IMAGE_SIDE.toFloat() / maxOf(info.size.width, info.size.height))
    decoder.setTargetSize(
        (info.size.width * scale).toInt().coerceAtLeast(1),
        (info.size.height * scale).toInt().coerceAtLeast(1)
    )
}

private fun decodeLegacyImage(file: File): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0)
    val options = BitmapFactory.Options().apply {
        inSampleSize = thumbnailSampleSize(
            bounds.outWidth,
            bounds.outHeight,
            MAXIMUM_IMAGE_SIDE,
            MAXIMUM_IMAGE_SIDE.toLong() * MAXIMUM_IMAGE_SIDE
        )
    }
    val bitmap = requireNotNull(BitmapFactory.decodeFile(file.path, options))
    val exif = runCatching { ExifInterface(file) }.getOrNull()
    val transform = exif?.let {
        Matrix().apply {
            if (it.isFlipped) postScale(-1f, 1f)
            postRotate(it.rotationDegrees.toFloat())
        }
    }
    return if (transform == null || transform.isIdentity) {
        bitmap
    } else {
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, true).also {
            if (it !== bitmap) bitmap.recycle()
        }
    }
}
