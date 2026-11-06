package com.clarklevis.dsh.android.ui

import androidx.compose.material3.MaterialTheme
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
