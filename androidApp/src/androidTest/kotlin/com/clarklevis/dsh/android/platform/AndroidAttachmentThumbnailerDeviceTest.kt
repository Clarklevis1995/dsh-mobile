package com.clarklevis.dsh.android.platform

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidAttachmentThumbnailerDeviceTest {
    @Test
    fun highResolutionCompressedImageIsDecodedAsBoundedThumbnail() = runBlocking {
        val source = Bitmap.createBitmap(4_096, 2_048, Bitmap.Config.RGB_565)
        val compressed = try {
            ByteArrayOutputStream().use { output ->
                assertTrue(source.compress(Bitmap.CompressFormat.JPEG, 85, output))
                output.toByteArray()
            }
        } finally {
            source.recycle()
        }

        val thumbnail = AndroidAttachmentThumbnailer().decode(compressed)
        assertNotNull(thumbnail)
        requireNotNull(thumbnail)
        assertTrue(thumbnail.width <= AndroidAttachmentThumbnailer.MAXIMUM_THUMBNAIL_SIDE)
        assertTrue(thumbnail.height <= AndroidAttachmentThumbnailer.MAXIMUM_THUMBNAIL_SIDE)
        assertTrue(
            thumbnail.width.toLong() * thumbnail.height <=
                AndroidAttachmentThumbnailer.MAXIMUM_THUMBNAIL_PIXELS
        )
        thumbnail.recycle()
    }
}
