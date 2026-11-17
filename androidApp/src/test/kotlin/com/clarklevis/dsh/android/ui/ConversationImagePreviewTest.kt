package com.clarklevis.dsh.android.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationImagePreviewTest {
    @Test fun `gallery includes both roles and retains older metadata across history windows`() {
        fun image(id: String) = com.clarklevis.dsh.shared.protocol.GatewayImageAttachment(id, "image/png", 10, 20, 20)
        fun item(id: String, time: Double, role: com.clarklevis.dsh.shared.projection.ConversationItemKind, vararg images: String) =
            com.clarklevis.dsh.shared.projection.ConversationItem(id, role, "", "", images.map(::image), epochSeconds = time)
        val user = com.clarklevis.dsh.shared.projection.ConversationItemKind.USER
        val assistant = com.clarklevis.dsh.shared.projection.ConversationItemKind.ASSISTANT
        val recent = mergeConversationGallery(emptyList(), listOf(item("s-10", 10.0, user, "b", "c")))
        val older = mergeConversationGallery(recent, listOf(item("s-2", 2.0, assistant, "a"), item("s-10", 10.0, user, "b", "c")))
        assertEquals(listOf("a", "b", "c"), older.map { it.attachment.attachmentId })
        val newest = mergeConversationGallery(older, listOf(item("s-20", 20.0, assistant, "d", "c")))
        assertEquals(listOf("a", "b", "c", "d"), newest.map { it.attachment.attachmentId })
    }
    @Test
    fun `attachment placeholders preserve aspect ratio within viewport and height limits`() {
        assertEquals(UserAttachmentPreviewSize(320.dp, 160.dp), userSingleAttachmentPreviewSize(1600, 800, 390.dp))
        assertEquals(UserAttachmentPreviewSize(80.dp, 320.dp), userSingleAttachmentPreviewSize(800, 3200, 390.dp))
        val narrow = userSingleAttachmentPreviewSize(1600, 800, 120.dp)
        assertEquals(120f, narrow.width.value, 0.001f)
        assertEquals(60f, narrow.height.value, 0.001f)
        assertEquals(UserAttachmentPreviewSize(40.dp, 20.dp), userSingleAttachmentPreviewSize(40, 20, 390.dp))
    }

    @Test
    fun `pan bounds follow fitted image and reset at original scale`() {
        assertEquals(Offset(200f, 0f), previewBoundedTranslation(Offset(900f, 900f), 2f, 400f, 800f, 1000, 1000))
        assertEquals(Offset(-200f, 0f), previewBoundedTranslation(Offset(-900f, -900f), 2f, 400f, 800f, 1000, 1000))
        assertEquals(Offset.Zero, previewBoundedTranslation(Offset(50f, 50f), 1f, 400f, 800f, 1000, 1000))
    }

    @Test
    fun `initial page stays inside available image range`() {
        assertEquals(0, imagePreviewInitialPage(imageCount = 3, requestedIndex = -1))
        assertEquals(1, imagePreviewInitialPage(imageCount = 3, requestedIndex = 1))
        assertEquals(2, imagePreviewInitialPage(imageCount = 3, requestedIndex = 9))
        assertEquals(0, imagePreviewInitialPage(imageCount = 0, requestedIndex = 9))
    }
}
