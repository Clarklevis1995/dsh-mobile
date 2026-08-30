package com.clarklevis.dsh.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationImagePreviewTest {
    @Test
    fun `initial page stays inside available image range`() {
        assertEquals(0, imagePreviewInitialPage(imageCount = 3, requestedIndex = -1))
        assertEquals(1, imagePreviewInitialPage(imageCount = 3, requestedIndex = 1))
        assertEquals(2, imagePreviewInitialPage(imageCount = 3, requestedIndex = 9))
        assertEquals(0, imagePreviewInitialPage(imageCount = 0, requestedIndex = 9))
    }
}
