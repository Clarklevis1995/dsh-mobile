package com.clarklevis.dsh.android.platform

import android.graphics.Bitmap
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

class AndroidImageSaverDeviceTest {
    @Test fun savedImageKeepsOriginalBytesAndMimeType() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            bitmap.recycle()
            it.toByteArray()
        }
        val uri = saveImageToPhotos(context, bytes, "image/png")
        try {
            assertEquals("image/png", context.contentResolver.getType(uri))
            context.contentResolver.openInputStream(uri)!!.use {
                assertArrayEquals(bytes, it.readBytes())
            }
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }
}
