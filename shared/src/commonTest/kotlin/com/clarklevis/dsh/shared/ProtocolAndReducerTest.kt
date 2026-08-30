package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.domain.QuestionAction
import com.clarklevis.dsh.shared.domain.QuestionFailureCode
import com.clarklevis.dsh.shared.domain.QuestionReducer
import com.clarklevis.dsh.shared.domain.QuestionRequestStatus
import com.clarklevis.dsh.shared.domain.QuestionState
import com.clarklevis.dsh.shared.domain.QuestionSubmission
import com.clarklevis.dsh.shared.domain.SessionControlAction
import com.clarklevis.dsh.shared.domain.SessionControlReducer
import com.clarklevis.dsh.shared.domain.SessionControlState
import com.clarklevis.dsh.shared.domain.SessionListAction
import com.clarklevis.dsh.shared.domain.SessionListReducer
import com.clarklevis.dsh.shared.domain.SessionListState
import com.clarklevis.dsh.shared.gateway.GatewayRequestLanePolicy
import com.clarklevis.dsh.shared.gateway.GatewayRequests
import com.clarklevis.dsh.shared.protocol.GatewayFrame
import com.clarklevis.dsh.shared.protocol.GatewayPendingQuestionRequest
import com.clarklevis.dsh.shared.protocol.GatewayPermissionOption
import com.clarklevis.dsh.shared.protocol.GatewayQuestion
import com.clarklevis.dsh.shared.protocol.GatewayQuestionAnswer
import com.clarklevis.dsh.shared.protocol.GatewayQuestionOption
import com.clarklevis.dsh.shared.protocol.GatewaySessionPermissions
import com.clarklevis.dsh.shared.protocol.GatewaySessionSummary
import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder
import com.clarklevis.dsh.shared.protocol.JsonValue
import com.clarklevis.dsh.shared.protocol.SessionEvent
import com.clarklevis.dsh.shared.protocol.GatewayEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolAndReducerTest {
    @Test
    fun workspaceDirectoryRequestsMatchTheIosGatewayContract() {
        assertEquals("{\"type\":\"directories\"}", GatewayRequests.directories().payload)
        assertEquals(
            "{\"type\":\"directories\",\"path\":\"/Users/mobile\"}",
            GatewayRequests.directories("/Users/mobile").payload
        )

        val createDirectory = GatewayRequests.createDirectory("/Users/mobile", "project")
        assertEquals(
            "{\"type\":\"directory-create\",\"path\":\"/Users/mobile\",\"name\":\"project\"}",
            createDirectory.payload
        )
        assertEquals(GatewayRequestLanePolicy.REJECT_IF_BUSY, createDirectory.lanePolicy)

        val createWorkspace = GatewayRequests.createWorkspace("/Users/mobile/project")
        assertEquals(
            "{\"type\":\"workspace-create\",\"path\":\"/Users/mobile/project\"}",
            createWorkspace.payload
        )
        assertEquals(GatewayRequestLanePolicy.REJECT_IF_BUSY, createWorkspace.lanePolicy)
    }

    @Test
    fun wireDecoderNormalizesMissingEventKindAndPreservesPayload() {
        val frame = GatewayWireDecoder.decode(GatewayProtocolFixtures.LIVE_EVENT_WITHOUT_KIND)
        assertEquals("event", frame.kind)
        assertEquals("s1", frame.sessionId)
        assertEquals(7, frame.seq)
        assertEquals("assistant/chunk", frame.event?.type)
        assertEquals("thinking", frame.event?.text)
    }

    @Test
    fun wireDecoderDecodesQuestionAndImageMetadataFixtures() {
        val question = GatewayWireDecoder.decode(GatewayProtocolFixtures.REPLAYED_QUESTION_REQUEST)
        assertEquals("rpc-1", question.rpcId)
        assertEquals(2, question.questions?.size)
        assertEquals("你想研究哪个方向？", question.questions?.first()?.question)
        assertEquals("核心架构 (推荐)", question.questions?.first()?.options?.first()?.label)
        assertEquals(true, question.questions?.first()?.allowsMultipleSelections)

        val image = GatewayWireDecoder.decode(GatewayProtocolFixtures.IMAGE_ATTACHMENT)
        assertEquals("att-1", image.attachment?.attachmentId)
        assertEquals(1, image.attachment?.width)
        assertEquals(1, image.attachment?.height)
        assertEquals("iVBORw0K", image.data)

        val history = GatewayWireDecoder.decode(GatewayProtocolFixtures.HISTORY_IMAGE)
        val normalized = history.events?.single()?.normalized("s1")
        assertEquals("att-history", normalized?.event?.images?.single()?.attachmentId)
        assertEquals("image/webp", normalized?.event?.images?.single()?.mediaType)
    }

    @Test
    fun jsonValueSupportsNestedLookupPrettyPrintingAndIntegerSearch() {
        val value = JsonValue.ObjectValue(
            mapOf(
                "values" to JsonValue.ObjectValue(mapOf("title" to JsonValue.StringValue("KMP"))),
                "usage" to JsonValue.ObjectValue(mapOf("outputTokens" to JsonValue.NumberValue(42.0)))
            )
        )
        assertEquals("KMP", value["values"]?.get("title")?.stringValue)
        assertEquals(42, value.firstInteger(setOf("outputTokens")))
        assertTrue(value.displayText().contains("\"usage\""))
    }

    @Test
    fun sessionListReducerMatchesRemoteMergeAndUnreadSemantics() {
        var state = SessionListState(selectedSessionId = "selected")
        state = SessionListReducer.reduce(
            state,
            SessionListAction.RemoteSessionsReceived(
                listOf(
                    GatewaySessionSummary(
                        sessionId = "s1",
                        updatedAt = 1_700_000_000_000.0,
                        running = true,
                        blank = false,
                        cwd = "/tmp/workspace",
                        projections = JsonValue.ObjectValue(
                            mapOf("values" to JsonValue.ObjectValue(mapOf("title" to JsonValue.StringValue("共享标题"))))
                        )
                    )
                )
            )
        )
        assertEquals("共享标题", state.sessions.single().title)
        assertEquals(1_700_000_000.0, state.sessions.single().lastActivityEpochSeconds)
        state = SessionListReducer.reduce(
            state,
            SessionListAction.EventReceived(
                SessionEvent("s1", 2, 1_700_000_001.0, GatewayEvent("assistant/message", text = "done")),
                insertedAtEpochSeconds = 1_700_000_001.0
            )
        )
        assertTrue(state.sessions.single().hasUnread)
    }

    @Test
    fun sessionListReducerUsesPlatformTimestampOnlyWhenItCreatesLocalSummary() {
        var state = SessionListReducer.reduce(
            SessionListState(),
            SessionListAction.KnownSessionAdded("known", insertedAtEpochSeconds = 123.0)
        )
        assertEquals(123.0, state.sessions.single().lastActivityEpochSeconds)

        state = SessionListReducer.reduce(
            state,
            SessionListAction.EventReceived(
                SessionEvent("chunk-only", 1, 200.0, GatewayEvent("assistant/chunk", text = "delta")),
                insertedAtEpochSeconds = 456.0
            )
        )
        assertEquals(456.0, state.sessions.first { it.id == "chunk-only" }.lastActivityEpochSeconds)
    }

    @Test
    fun questionReducerValidatesAnswerAndHandlesNotPending() {
        val request = GatewayPendingQuestionRequest(
            rpcId = "rpc-1",
            sessionId = "s1",
            replay = false,
            questions = listOf(
                GatewayQuestion(
                    id = "q1",
                    question = "方向",
                    options = listOf(GatewayQuestionOption("架构")),
                    multiSelect = false
                )
            )
        )
        var state = QuestionReducer.reduce(QuestionState(), QuestionAction.RequestReceived(request))
        state = QuestionReducer.reduce(
            state,
            QuestionAction.Submit(
                request,
                QuestionSubmission.Answer(listOf(GatewayQuestionAnswer("q1", listOf("不存在")))),
                isConnected = true
            )
        )
        val rejected = assertIs<QuestionRequestStatus.Rejected>(state.requestStatuses["rpc-1"])
        assertEquals(QuestionFailureCode.INVALID_OR_DUPLICATE_OPTIONS, rejected.failure.code)

        state = QuestionReducer.reduce(
            state,
            QuestionAction.ResponseReceived("rpc-1", com.clarklevis.dsh.shared.protocol.GatewayQuestionAction.ANSWER, false, "not-pending")
        )
        assertTrue(state.pendingRequests.isEmpty())
        assertNull(state.requestStatuses["rpc-1"])
    }

    @Test
    fun sessionControlReducerFiltersPermissionsAndClearsLoadingTarget() {
        var state = SessionControlReducer.reduce(
            SessionControlState(),
            SessionControlAction.ModelsRequestTargeted("s1")
        )
        state = SessionControlReducer.reduce(state, SessionControlAction.RequestStarted("models"))
        state = SessionControlReducer.reduce(
            state,
            SessionControlAction.PermissionsReceived(
                "s1",
                GatewaySessionPermissions(
                    options = listOf(
                        GatewayPermissionOption("read-only", "Read only"),
                        GatewayPermissionOption("unsupported", "Unsupported")
                    )
                )
            )
        )
        assertEquals(listOf("read-only"), state.sessionPermissions["s1"]?.options?.map { it.value })
        state = SessionControlReducer.reduce(state, SessionControlAction.RequestFinished("models"))
        assertNull(state.pendingModelsSessionId)
        assertTrue("models" !in state.loadingKinds)
    }
}
