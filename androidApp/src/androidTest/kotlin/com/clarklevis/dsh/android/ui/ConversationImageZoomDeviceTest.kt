package com.clarklevis.dsh.android.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ConversationImageZoomDeviceTest {
    @get:Rule val compose = createComposeRule()

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
