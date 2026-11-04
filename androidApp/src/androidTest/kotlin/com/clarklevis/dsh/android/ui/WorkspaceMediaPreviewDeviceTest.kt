package com.clarklevis.dsh.android.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.widget.VideoView
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clarklevis.dsh.android.AndroidWorkspaceLocalFile
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspaceMediaPreviewDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun imageWithGenericMimeTypeRendersInsideTheSharedPreviewSheet() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val image = File(context.cacheDir, "inline-preview.png")
        try {
            val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.BLUE)
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            val file = localFile(image)
            compose.setContent {
                DshTheme {
                    WorkspaceFilePreviewSheet(file, {}) { padding -> WorkspaceFilePreviewContent(file, {}, padding) }
                }
            }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("workspace-image-preview").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("workspace-image-preview").assertIsDisplayed()
            compose.onNodeWithTag("workspace-media-preview-error").assertDoesNotExist()
        } finally {
            image.delete()
        }
    }

    @Test
    fun imageDecodeHonorsExifAndBoundsLargeImages() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val image = File(context.cacheDir, "rotated-preview.jpg")
        try {
            val bitmap = Bitmap.createBitmap(4096, 1024, Bitmap.Config.ARGB_8888)
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bitmap.recycle()
            ExifInterface(image).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val decoded = decodeWorkspaceImage(image, image.name, "image/jpeg")
            try {
                assertTrue(decoded.width < decoded.height)
                assertTrue(maxOf(decoded.width, decoded.height) <= 2048)
            } finally {
                decoded.recycle()
            }
        } finally {
            image.delete()
        }
    }

    @Test
    fun videoPlaysPausesSeeksAndReleasesWhenPreviewCloses() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val videoFile = File(instrumentation.targetContext.cacheDir, "inline-preview.mp4")
        instrumentation.context.assets.open("media/preview.mp4").use { input ->
            videoFile.outputStream().use(input::copyTo)
        }
        try {
            lateinit var owner: PreviewLifecycleOwner
            compose.runOnIdle {
                owner = PreviewLifecycleOwner()
                owner.lifecycle.currentState = Lifecycle.State.RESUMED
            }
            var visible by mutableStateOf(true)
            var root: View? = null
            val file = localFile(videoFile)
            compose.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    DshTheme {
                        if (visible) {
                            WorkspaceFilePreviewSheet(file, { visible = false }) { padding ->
                                root = LocalView.current
                                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                                    WorkspaceFilePreviewContent(file, {}, padding)
                                }
                            }
                        }
                    }
                }
            }
            waitForPlayer()
            lateinit var player: VideoView
            compose.runOnIdle {
                player = requireNotNull(findVideo(requireNotNull(root)))
                assertFalse(player.isPlaying)
            }
            compose.onNodeWithTag("workspace-video-play-pause").performClick()
            compose.waitUntil(5_000) {
                var advanced = false
                compose.runOnIdle { advanced = player.isPlaying && player.currentPosition > 250 }
                advanced
            }
            compose.onNodeWithTag("workspace-video-play-pause").performClick()
            compose.runOnIdle { assertFalse(player.isPlaying) }
            if (Build.VERSION.SDK_INT >= 26) {
                lateinit var frame: Bitmap
                compose.runOnIdle { frame = Bitmap.createBitmap(player.width, player.height, Bitmap.Config.ARGB_8888) }
                try {
                    val copied = CountDownLatch(1)
                    var copyResult = -1
                    PixelCopy.request(player, frame, {
                        copyResult = it
                        copied.countDown()
                    }, Handler(Looper.getMainLooper()))
                    assertTrue(copied.await(5, TimeUnit.SECONDS))
                    assertEquals(PixelCopy.SUCCESS, copyResult)
                    assertTrue(
                        "视频应实际显示解码画面",
                        (1..9).any {
                            frame.getPixel(frame.width * it / 10, frame.height / 2) and 0xFFFFFF != 0
                        }
                    )
                } finally {
                    frame.recycle()
                }
            }
            compose.onNodeWithTag("workspace-video-progress").performTouchInput { click(center) }
            compose.waitUntil(5_000) {
                var sought = false
                compose.runOnIdle { sought = player.currentPosition >= 1_500 }
                sought
            }
            compose.onNodeWithTag("workspace-video-play-pause").performClick()
            compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.CREATED }
            compose.runOnIdle { assertFalse(player.isPlaying) }
            compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
            waitForPlayer()
            compose.runOnIdle { assertFalse(player.isPlaying) }
            compose.runOnIdle { visible = false }
            compose.onNodeWithTag("workspace-video-preview").assertDoesNotExist()
            compose.runOnIdle {
                assertFalse(player.isPlaying)
                assertEquals(-1, player.duration)
            }
        } finally {
            videoFile.delete()
        }
    }

    @Test
    fun damagedImageShowsExternalOpenInsteadOfAnEndlessSpinner() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val image = File(context.cacheDir, "broken-preview.png").apply { writeText("invalid image data") }
        try {
            val file = localFile(image)
            compose.setContent {
                DshTheme {
                    WorkspaceFilePreviewSheet(file, {}) { padding -> WorkspaceFilePreviewContent(file, {}, padding) }
                }
            }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("workspace-media-preview-error").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("workspace-media-preview-error").assertIsDisplayed()
        } finally {
            image.delete()
        }
    }

    private fun waitForPlayer() {
        compose.waitUntil(10_000) {
            runCatching {
                compose.onNodeWithTag("workspace-video-play-pause").assertIsEnabled()
                true
            }.getOrDefault(false)
        }
    }

    private fun localFile(file: File) = AndroidWorkspaceLocalFile(
        file,
        "media-test",
        file.name,
        file.name,
        "application/octet-stream",
        "preview"
    )

    private fun findVideo(view: View): VideoView? {
        if (view is VideoView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findVideo(view.getChildAt(index))?.let { return it }
        return null
    }

    private class PreviewLifecycleOwner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }
}
