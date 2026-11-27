package com.clarklevis.dsh.android

import com.clarklevis.dsh.android.platform.AndroidPreparedImage
import com.clarklevis.dsh.shared.gateway.GatewayOutgoingImage
import com.clarklevis.dsh.shared.gateway.GatewayRuntimeEvent
import com.clarklevis.dsh.shared.protocol.GatewayFrame
import com.clarklevis.dsh.shared.protocol.GatewayWorkspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidSharedStateHolderTest {
    @Test
    fun composerDraftSurvivesReopeningAndIsIsolatedBetweenOrdinarySessions() = runTest {
        val holder = AndroidSharedStateHolder()
        try {
            holder.selectSession("a")
            holder.messageDraft = "**还没发送**\n第二行"
            holder.selectSession("a")
            assertEquals("**还没发送**\n第二行", holder.messageDraft)
            holder.selectSession("b")
            assertEquals("", holder.messageDraft)
            holder.messageDraft = "B 的草稿"
            holder.selectSession("a")
            assertEquals("**还没发送**\n第二行", holder.messageDraft)
            holder.messageDraft = ""
            holder.selectSession("b")
            assertEquals("B 的草稿", holder.messageDraft)
            holder.selectSession("a")
            assertEquals("", holder.messageDraft)
        } finally { holder.close() }
    }

    @Test
    fun allScopeKeepsDefaultWorkspaceAndDoesNotReportObsoleteCreationRestriction() = runTest {
        val holder = AndroidSharedStateHolder()
        try {
            holder.selectWorkspace(AndroidSharedStateHolder.ALL_WORKSPACES_ID)
            val selected = holder.snapshot.selectedSessionId
            assertNull("全部会话没有隐式选中的具体工作区", holder.activeWorkspace)
            assertFalse("没有网关时不应创建会话", holder.prepareNewSession())
            assertNull("不能恢复已移除的必须先选择工作区限制", holder.platformError)
            assertEquals(selected, holder.snapshot.selectedSessionId)
            assertEquals(AndroidSharedStateHolder.ALL_WORKSPACES_ID, holder.selectedWorkspaceId)
        } finally { holder.close() }
    }

    // StateHolder 的平台适配器依赖 Main dispatcher；JVM 测试不使用 Android Looper。
    @Before
    fun setUpMainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun resetMainDispatcher() = Dispatchers.resetMain()

    @Test
    fun sessionCreationResponseFromMainRuntimeConsumerCompletesPendingRequest() = runTest {
        val pending = AndroidPendingSessionCreation()
        val response = pending.begin("create-1")

        pending.accept(
            GatewayRuntimeEvent.Frame(
                rawJson = "redacted",
                frame = GatewayFrame(
                    kind = "session-created",
                    requestId = "create-1",
                    sessionId = "session-1"
                ),
                correlatedSessionId = "session-1"
            )
        )

        assertTrue(response.isCompleted)
        assertEquals(
            "session-1",
            (response.await() as GatewayRuntimeEvent.Frame).frame.sessionId
        )
    }

    @Test
    fun unrelatedSessionCreationResponseDoesNotCompletePendingRequest() {
        val pending = AndroidPendingSessionCreation()
        val response = pending.begin("create-1")

        pending.accept(
            GatewayRuntimeEvent.Frame(
                rawJson = "redacted",
                frame = GatewayFrame(
                    kind = "session-created",
                    requestId = "another-request",
                    sessionId = "another-session"
                ),
                correlatedSessionId = "another-session"
            )
        )

        assertEquals(false, response.isCompleted)
        pending.clear("create-1")
    }

    @Test
    fun workspaceSelectionRestoresAConcreteScopeInsteadOfAllSessions() {
        val workspaces = listOf(
            GatewayWorkspace("w1", "/one", "One", listOf("s1"), "now", "now"),
            GatewayWorkspace("w2", "/two", "Two", listOf("s2"), "now", "now")
        )

        assertEquals("w2", resolveWorkspaceSelection("w2", workspaces))
        assertEquals("w1", resolveWorkspaceSelection("missing", workspaces))
        assertEquals(
            AndroidSharedStateHolder.UNGROUPED_WORKSPACE_ID,
            resolveWorkspaceSelection(null, emptyList())
        )
    }

    @Test
    fun directoryAndWorkspaceFramesDriveAndroidBrowserState() {
        val holder = AndroidSharedStateHolder()
        holder.wirePayload =
            """{"kind":"directories","path":"/Users/mobile","home":"/Users/mobile","crumbs":[{"name":"mobile","path":"/Users/mobile","hidden":false}],"entries":[{"name":"project","path":"/Users/mobile/project","hidden":false}]}"""
        holder.submitWirePayload()

        assertEquals("/Users/mobile", holder.directoryPath)
        assertEquals("project", holder.directoryEntries.single().name)
        assertEquals(false, holder.directoryIsLoading)

        holder.wirePayload =
            """{"kind":"workspace-create","created":true,"workspace":{"workspaceId":"w-new","path":"/Users/mobile/project","title":"project","sessionIds":[],"createdAt":"now","updatedAt":"now"}}"""
        holder.submitWirePayload()

        assertEquals("w-new", holder.selectedWorkspaceId)
        assertEquals("/Users/mobile/project", holder.workspaceCreationCompletedPath)
        assertEquals("w-new", holder.activeWorkspace?.workspaceId)
    }

    @Test
    fun fixtureAndWireInputAreReducedBySharedStore() {
        val holder = AndroidSharedStateHolder()
        holder.loadFixture()
        assertEquals(2, holder.snapshot.conversation.size)
        assertEquals(1, holder.snapshot.pendingQuestionCount)

        holder.submitWirePayload()
        assertEquals("event", holder.snapshot.lastFrameKind)
        assertEquals("The final message replaces the temporary streamed message.", holder.snapshot.conversation.last().text)
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
        assertEquals(0L, holder.successfulMessageSendCount)

        holder.applyMessageSendResult(true)
        assertEquals("", holder.messageDraft)
        assertEquals(0, holder.preparedImages.size)
        assertEquals(1L, holder.successfulMessageSendCount)
    }

    @Test
    fun genuiSubmissionPreservesComposerDraftAndAttachments() {
        val holder = AndroidSharedStateHolder()
        val image = AndroidPreparedImage(
            GatewayOutgoingImage("image/png", "AQID", "draft.png"),
            width = 1,
            height = 1,
            byteCount = 3
        )
        holder.messageDraft = "keep this draft"
        holder.setPreparedImagesForTest(listOf(image))
        val submission = AndroidSharedStateHolder.MessageSubmission(
            generation = 0L,
            sessionId = null,
            draft = "Blue",
            images = emptyList(),
            preserveComposer = true
        )

        holder.applyMessageSendResultForTest(submission, sent = true)

        assertEquals("keep this draft", holder.messageDraft)
        assertEquals(listOf(image), holder.preparedImages)
        assertEquals(0L, holder.successfulMessageSendCount)
    }

    @Test
    fun genuiActionIntentValidatesSessionCardStateAndSearchContextEndToEnd() {
        val projection = AndroidGatewayProjection()
        projection.selectSession("session-a")
        val spec = """{"items":[{"type":"choices","title":"Deploy","prompt":"Target?","mode":"multi","submit":"Go","action":"choose-target","items":[{"label":"Staging"},{"label":"Prod"}]}]}"""
        val fence = "```dsh-ui\\n${spec.replace("\"", "\\\"")}\\n```"
        val raw = """{"kind":"event","sessionId":"session-a","seq":1,"time":100,"event":{"type":"assistant/message","text":"$fence"}}"""
        projection.acceptFrame(raw, GatewayWireDecoder.decode(raw), "session-a")

        val block = projection.snapshot().conversation.single().segments!!
            .filterIsInstance<com.clarklevis.dsh.shared.projection.GenuiSegment>()
            .single()
        val blockId = block.id
        val contentVersion = block.contentVersion
        val choicesAction = """{"version":1,"action":"choose-target","payload":{"type":"choices","question":"Target?","selected":["Staging","Prod"],"mode":"multi"}}"""

        val valid = projection.resolveGenuiAction(
            com.clarklevis.dsh.shared.projection.GenuiActionIntent(
                sessionId = "session-a",
                activeSessionId = "session-a",
                blockId = blockId,
                contentVersion = contentVersion,
                actionJson = choicesAction,
                isSearchContext = false
            )
        )
        assertNotNull(valid)
        assertEquals(com.clarklevis.dsh.shared.projection.GenuiActionType.SEND_MESSAGE, valid!!.type)
        assertTrue(valid.text!!.contains("choose-target"))
        assertTrue(valid.text!!.contains("Staging"))
        assertTrue(valid.text!!.contains("Prod"))

        assertNull(
            projection.resolveGenuiAction(
                com.clarklevis.dsh.shared.projection.GenuiActionIntent(
                    sessionId = "session-a",
                    activeSessionId = "session-b",
                    blockId = blockId,
                    contentVersion = contentVersion,
                    actionJson = choicesAction,
                    isSearchContext = false
                )
            )
        )
        assertNull(
            projection.resolveGenuiAction(
                com.clarklevis.dsh.shared.projection.GenuiActionIntent(
                    sessionId = "session-a",
                    activeSessionId = "session-a",
                    blockId = "stale-block-id",
                    contentVersion = contentVersion,
                    actionJson = choicesAction,
                    isSearchContext = false
                )
            )
        )
        assertNull(
            projection.resolveGenuiAction(
                com.clarklevis.dsh.shared.projection.GenuiActionIntent(
                    sessionId = "session-a",
                    activeSessionId = "session-a",
                    blockId = blockId,
                    contentVersion = contentVersion,
                    actionJson = choicesAction,
                    isSearchContext = true
                )
            )
        )
        projection.close()
    }

    @Test
    fun genuiEffectIgnoresStaleSessionForExpandDetailsAndCopy() {
        val holder = AndroidSharedStateHolder()
        holder.loadFixture()
        val activeSession = holder.snapshot.selectedSessionId!!

        holder.handleGenuiAction(
            com.clarklevis.dsh.shared.projection.GenuiActionEffect(
                type = com.clarklevis.dsh.shared.projection.GenuiActionType.EXPAND_DETAILS,
                sessionId = "other-session",
                blockId = "other-session-1-genui-0",
                contentVersion = "a".repeat(64),
                details = "Should not open"
            ),
            sourceSessionId = "other-session"
        )
        assertEquals(null, holder.genuiDetails)

        holder.handleGenuiAction(
            com.clarklevis.dsh.shared.projection.GenuiActionEffect(
                type = com.clarklevis.dsh.shared.projection.GenuiActionType.EXPAND_DETAILS,
                sessionId = activeSession,
                blockId = "$activeSession-1-genui-0",
                contentVersion = "a".repeat(64),
                details = "Active session details"
            ),
            sourceSessionId = activeSession
        )
        assertEquals("Active session details", holder.genuiDetails)
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
        assertEquals("", holder.messageDraft)
        holder.messageDraft = "other session draft"
        holder.applyMessageSendResultForTest(submission, sent = true)
        assertEquals("other session draft", holder.messageDraft)
        assertTrue(holder.preparedImages.isEmpty())
        holder.selectSession("android-demo")

        assertEquals("new draft", holder.messageDraft)
        assertEquals(listOf(newImage), holder.preparedImages)
        assertEquals(0L, holder.successfulMessageSendCount)
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
