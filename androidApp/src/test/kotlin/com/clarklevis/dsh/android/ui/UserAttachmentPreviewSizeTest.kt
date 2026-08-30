package com.clarklevis.dsh.android.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class UserAttachmentPreviewSizeTest {
    @Test
    fun landscapeImageKeepsAspectRatioWithinIosMaximum() {
        assertEquals(
            UserAttachmentPreviewSize(width = 320.dp, height = 240.dp),
            userSingleAttachmentPreviewSize(4_000, 3_000, 337.dp)
        )
    }

    @Test
    fun portraitImageUsesMaximumHeightWithoutCropping() {
        assertEquals(
            UserAttachmentPreviewSize(width = 240.dp, height = 320.dp),
            userSingleAttachmentPreviewSize(3_000, 4_000, 337.dp)
        )
    }

    @Test
    fun narrowViewportConstrainsWidthAndPreservesRatio() {
        assertEquals(
            UserAttachmentPreviewSize(width = 280.dp, height = 210.dp),
            userSingleAttachmentPreviewSize(4_000, 3_000, 280.dp)
        )
    }
}
