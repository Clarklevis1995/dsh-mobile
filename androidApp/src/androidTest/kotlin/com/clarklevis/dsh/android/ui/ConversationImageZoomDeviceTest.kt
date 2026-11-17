package com.clarklevis.dsh.android.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.swipeLeft
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.clarklevis.dsh.shared.protocol.GatewayImageAttachment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ConversationImageZoomDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun singleTapDismissesButDoubleTapStillZooms() {
        var visible by mutableStateOf(true)
        val saves = java.util.concurrent.atomic.AtomicInteger(0)
        val attachment = GatewayImageAttachment("tap-image", "image/png", 10, 200, 200)
        compose.setContent {
            DshTheme {
                if (visible) ConversationImagePreviewDialog(ConversationImagePreviewRequest(listOf(attachment), 0),
                    mapOf(attachment.attachmentId to ImageBitmap(200, 200)), { visible = false },
                    loadOriginal = { saves.incrementAndGet(); null })
            }
        }
        compose.onNodeWithTag("conversation-image-preview").performTouchInput { doubleClick(center) }
        compose.runOnIdle { assertTrue(visible) }
        compose.onNodeWithTag("conversation-image-preview").performTouchInput { longClick(center) }
        compose.mainClock.advanceTimeBy(300)
        val saveLabel = com.clarklevis.dsh.android.localization.appText("保存到相册")
        compose.onNodeWithText(saveLabel).assertIsDisplayed()
        compose.runOnIdle { assertTrue(visible); assertEquals(0, saves.get()) }
        compose.onNodeWithText(saveLabel).performClick()
        compose.waitUntil(5_000) { saves.get() == 1 }
        compose.onNodeWithTag("conversation-image-preview").performTouchInput { click(center) }
        compose.waitUntil(5_000) { !visible }
    }

    @Test fun pagingKeepsSelectedImageWhenOlderImagesArePrependedAndReleasesOnClose() {
        fun image(id: String) = GatewayImageAttachment(id, "image/png", 10, 20, 20, name = id)
        val recent = listOf(image("first"), image("second"), image("third"))
        var request by mutableStateOf(ConversationImagePreviewRequest(recent, 1))
        var visible by mutableStateOf(true)
        var loading = emptySet<String>()
        val thumbnail = Bitmap.createBitmap(240, 180, Bitmap.Config.ARGB_8888).apply {
            eraseColor(AndroidColor.rgb(24, 112, 170))
        }.asImageBitmap()
        compose.setContent {
            DshTheme {
                if (visible) ConversationImagePreviewDialog(request,
                    request.attachments.associate { it.attachmentId to thumbnail }, {},
                    onVisibleImagesChanged = { loading = it })
            }
        }
        compose.onNodeWithText("2 / 3").assertIsDisplayed()
        compose.runOnIdle { request = request.copy(attachments = listOf(image("older")) + recent) }
        compose.onNodeWithText("3 / 4").assertIsDisplayed()
        compose.onNodeWithTag("conversation-image-preview").performTouchInput { swipeLeft() }
        compose.onNodeWithText("4 / 4").assertIsDisplayed()
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        java.io.File(context.getExternalFilesDir(null), "session-gallery-preview.png").outputStream().use {
            compose.onNodeWithTag("conversation-image-preview").captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.runOnIdle { assertEquals(setOf("second", "third"), loading); visible = false }
        compose.runOnIdle { assertTrue(loading.isEmpty()) }
    }

    @Test fun downloadButtonUsesTheCurrentlySelectedImage() {
        val attachments = listOf(
            GatewayImageAttachment("sender-first", "image/png", 10, 20, 20),
            GatewayImageAttachment("sender-second", "image/png", 10, 20, 20)
        )
        val requested = java.util.concurrent.atomic.AtomicReference<String?>()
        compose.setContent {
            DshTheme {
                ConversationImagePreviewDialog(
                    ConversationImagePreviewRequest(attachments, 1),
                    attachments.associate { it.attachmentId to ImageBitmap(20, 20) }, {},
                    loadOriginal = { requested.set(it.attachmentId); null }
                )
            }
        }
        compose.onNodeWithTag("conversation-image-download").assertIsDisplayed().assertIsEnabled()
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        java.io.File(context.getExternalFilesDir(null), "sender-image-download-preview.png").outputStream().use {
            compose.onNodeWithTag("conversation-image-preview").captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithTag("conversation-image-download").performClick()
        compose.waitUntil { requested.get() != null }
        assertEquals("sender-second", requested.get())
    }

    @Test fun downloadButtonWaitsUntilTheImageIsLoaded() {
        val attachment = GatewayImageAttachment("sender-loading", "image/png", 10, 20, 20)
        compose.setContent {
            DshTheme {
                ConversationImagePreviewDialog(ConversationImagePreviewRequest(listOf(attachment), 0), emptyMap(), {})
            }
        }
        compose.onNodeWithTag("conversation-image-download").assertIsDisplayed().assertIsNotEnabled()
    }

    @Test fun zoomedImageMovesBeforeTheFingerIsLifted() {
        val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        for (y in 0 until 400) for (x in 0 until 400) {
            bitmap.setPixel(x, y, if (x < 200) AndroidColor.RED else AndroidColor.BLUE)
        }
        val image = bitmap.asImageBitmap()
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(300.dp).testTag("preview-viewport")) {
                    ZoomablePreviewImage(image, "preview")
                }
            }
        }
        val viewport = compose.onNodeWithTag("preview-viewport")
        viewport.performTouchInput { doubleClick(center) }
        compose.waitForIdle()
        val before = viewport.captureToImage().toPixelMap()
        val x = (before.width * 0.6f).toInt()
        val y = before.height / 2
        assertTrue(before[x, y].blue > 0.9f)
        // 分两批注入触摸，第一批只移动，不发送 up；此时就必须已经绘制新位置。
        viewport.performTouchInput {
            down(center)
            moveBy(Offset(width * 0.2f, 0f), delayMillis = 100)
        }
        compose.waitForIdle()
        val dragging = viewport.captureToImage().toPixelMap()
        assertTrue("手指未抬起时图片就应跟随移动", dragging[x, y].red > 0.9f)
        viewport.performTouchInput { up() }
        compose.waitForIdle()
        val released = viewport.captureToImage().toPixelMap()
        assertEquals(dragging[x, y], released[x, y])
    }

    @Test fun doubleTapPinchAndLongPressWorkTogether() {
        var zoomed = false
        var longPressed = false
        val image = ImageBitmap(400, 400)
        compose.setContent {
            MaterialTheme {
                ZoomablePreviewImage(image, "preview", onZoomChanged = { zoomed = it }, onLongPress = { longPressed = true })
            }
        }
        val preview = compose.onNodeWithContentDescription("preview")
        preview.performTouchInput { doubleClick() }
        compose.waitUntil { zoomed }
        preview.performTouchInput { longClick() }
        compose.runOnIdle { assertTrue(longPressed) }
        preview.performTouchInput { doubleClick() }
        compose.waitUntil { !zoomed }
        preview.performTouchInput {
            pinch(start0 = Offset(center.x - 40, center.y), end0 = Offset(center.x - 140, center.y),
                  start1 = Offset(center.x + 40, center.y), end1 = Offset(center.x + 140, center.y), durationMillis = 500)
        }
        compose.waitUntil { zoomed }
        compose.runOnIdle { assertTrue(zoomed) }
        preview.performTouchInput { doubleClick() }
        compose.waitUntil { !zoomed }
        compose.runOnIdle { assertFalse(zoomed) }
    }
}
