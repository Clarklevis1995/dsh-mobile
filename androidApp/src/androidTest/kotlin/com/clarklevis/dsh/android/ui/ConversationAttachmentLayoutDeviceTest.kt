package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.clarklevis.dsh.shared.protocol.GatewayImageAttachment
import com.clarklevis.dsh.android.AttachmentLoadState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ConversationAttachmentLayoutDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun loadingFailureAndReloadKeepMixedImageGridBounds() {
        val thumbnails = mutableStateOf(emptyMap<String, ImageBitmap>())
        val states = mutableStateOf(emptyMap<String, AttachmentLoadState>())
        val attachments = listOf(
            GatewayImageAttachment("landscape", "image/png", 100, 1600, 800),
            GatewayImageAttachment("portrait", "image/png", 100, 800, 3200),
            GatewayImageAttachment("square", "image/png", 100, 800, 800)
        )
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(300.dp).testTag("grid")) {
                    AttachmentGrid(attachments, thumbnails.value, states.value, {})
                }
            }
        }
        val bounds = compose.onNodeWithTag("grid").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            thumbnails.value = attachments.associate { it.attachmentId to ImageBitmap(20, 20) }
        }
        compose.waitForIdle()
        assertEquals(bounds, compose.onNodeWithTag("grid").fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle {
            thumbnails.value = emptyMap()
            states.value = attachments.associate { it.attachmentId to AttachmentLoadState.FAILED }
        }
        compose.waitForIdle()
        assertEquals(bounds, compose.onNodeWithTag("grid").fetchSemanticsNode().boundsInRoot)
    }
}
