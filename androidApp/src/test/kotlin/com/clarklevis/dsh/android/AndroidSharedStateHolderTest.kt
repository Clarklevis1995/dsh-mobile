package com.clarklevis.dsh.android

import com.clarklevis.dsh.android.platform.AndroidPreparedImage
import com.clarklevis.dsh.shared.gateway.GatewayOutgoingImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSharedStateHolderTest {
    @Test
    fun fixtureAndWireInputAreReducedBySharedStore() {
        val holder = AndroidSharedStateHolder()
        holder.loadFixture()
        assertEquals(2, holder.snapshot.conversation.size)
        assertEquals(1, holder.snapshot.pendingQuestionCount)

        holder.submitWirePayload()
        assertEquals("event", holder.snapshot.lastFrameKind)
        assertEquals("最终消息会替换流式临时消息。", holder.snapshot.conversation.last().text)
        assertNotNull(holder.snapshot.selectedSessionId)
    }

    @Test
    fun busySendKeepsDraftAndImagesUntilRequestIsAccepted() {
        val holder = AndroidSharedStateHolder()
        holder.messageDraft = "keep me"
        holder.setPreparedImagesForTest(
            listOf(
                AndroidPreparedImage(
                    GatewayOutgoingImage("image/png", "AQID", "pixel.png"),
                    width = 1,
                    height = 1,
                    byteCount = 3
                )
            )
        )
        holder.applyMessageSendResult(false)
        assertEquals("keep me", holder.messageDraft)
        assertEquals(1, holder.preparedImages.size)

        holder.applyMessageSendResult(true)
        assertEquals("", holder.messageDraft)
        assertEquals(0, holder.preparedImages.size)
    }

    @Test
    fun acceptedSubmissionDoesNotClearOrResendInputEditedAfterClick() {
        val holder = AndroidSharedStateHolder()
        holder.loadFixture()
        val originalImage = AndroidPreparedImage(
            GatewayOutgoingImage("image/png", "AQID", "original.png"),
            width = 1,
            height = 1,
            byteCount = 3
        )
        holder.messageDraft = "original draft"
        holder.setPreparedImagesForTest(listOf(originalImage))
        val submission = holder.captureMessageSubmissionForTest()
        assertEquals("android-demo", submission.sessionId)
        assertEquals("original draft", submission.draft)
        assertEquals(listOf(originalImage), submission.images)

        val newImage = originalImage.copy(
            outgoing = GatewayOutgoingImage("image/png", "BAUG", "new.png")
        )
        holder.messageDraft = "new draft"
        holder.setPreparedImagesForTest(listOf(newImage))
        holder.selectSession("another-session")
        holder.applyMessageSendResultForTest(submission, sent = true)

        assertEquals("new draft", holder.messageDraft)
        assertEquals(listOf(newImage), holder.preparedImages)
        assertEquals("original draft", submission.draft)
        assertEquals(listOf(originalImage), submission.images)
    }

    @Test
    fun visibleAttachmentFailureIsNegativeCachedUntilExplicitRetry() {
        val holder = AndroidSharedStateHolder()
        holder.loadFixture()
        holder.wirePayload =
            """{"sessionId":"android-demo","seq":4,"time":1786937355,"event":{"type":"assistant/message","turn":2,"step":1,"text":"image","images":[{"attachmentId":"visible-image","mediaType":"image/png","bytes":3,"width":1,"height":1}]}}"""
        holder.submitWirePayload()
        holder.updateVisibleAttachments(setOf("visible-image"))
        assertEquals(AttachmentLoadState.LOADING, holder.attachmentStates["visible-image"])
        assertEquals(listOf("visible-image"), holder.queuedAttachmentIdsForTest())

        holder.failAttachmentForTest("visible-image")
        holder.updateVisibleAttachments(setOf("visible-image"))
        assertEquals(AttachmentLoadState.FAILED, holder.attachmentStates["visible-image"])
        assertTrue(holder.queuedAttachmentIdsForTest().isEmpty())

        holder.retryAttachment("visible-image")
        assertEquals(AttachmentLoadState.LOADING, holder.attachmentStates["visible-image"])
        assertEquals(listOf("visible-image"), holder.queuedAttachmentIdsForTest())
    }
}
