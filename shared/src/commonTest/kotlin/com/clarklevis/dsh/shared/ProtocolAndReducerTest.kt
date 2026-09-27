package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.domain.QuestionAction
import com.clarklevis.dsh.shared.domain.ApprovalAction
import com.clarklevis.dsh.shared.domain.ApprovalReducer
import com.clarklevis.dsh.shared.domain.ApprovalRequestStatus
import com.clarklevis.dsh.shared.domain.ApprovalState
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
import com.clarklevis.dsh.shared.protocol.GatewayGoalRef
import com.clarklevis.dsh.shared.protocol.GatewayApprovalOutcome
import com.clarklevis.dsh.shared.protocol.GatewayPendingApprovalRequest
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
    fun taskAndGoalRequestsMatchGatewayContractAndCarryCasReference() {
        assertEquals("{\"type\":\"tasks\",\"sessionId\":\"s1\"}", GatewayRequests.tasks("s1").payload)
        assertEquals("{\"type\":\"goal\",\"sessionId\":\"s1\"}", GatewayRequests.goal("s1").payload)
        val ref = GatewayGoalRef("goal-1", 7)
        assertEquals(
            "{\"type\":\"goal-edit\",\"sessionId\":\"s1\",\"ref\":{\"id\":\"goal-1\",\"revision\":7},\"objective\":\"完成 Android app\"}",
            GatewayRequests.editGoal("s1", ref, objective = "完成 Android app").payload
        )
        assertEquals(
            "{\"type\":\"goal-pause\",\"sessionId\":\"s1\",\"ref\":{\"id\":\"goal-1\",\"revision\":7}}",
            GatewayRequests.goalAction("goal-pause", "s1", ref).payload
        )
        assertEquals(GatewayRequestLanePolicy.REJECT_IF_BUSY, GatewayRequests.goalAction("goal-clear", "s1", ref).lanePolicy)
    }

    @Test
    fun scheduleRequestsCarrySessionBindingAndCompleteExpectedRecord() {
        assertEquals("{\"type\":\"schedule-catalog\"}", GatewayRequests.scheduleCatalog().payload)
        assertEquals("{\"type\":\"schedule-list\",\"sessionId\":\"s1\"}", GatewayRequests.scheduleList("s1").payload)
        assertEquals(
            "{\"type\":\"schedule-history\",\"sessionId\":\"s1\",\"id\":\"task-1\",\"limit\":20,\"before\":\"message-1\"}",
            GatewayRequests.scheduleHistory("s1", "task-1", 20, "message-1").payload
        )
        val expected = JsonValue.ObjectValue(mapOf(
            "id" to JsonValue.StringValue("task-1"),
            "kind" to JsonValue.StringValue("every"),
            "title" to JsonValue.StringValue("检查构建"),
            "prompt" to JsonValue.StringValue("检查结果"),
            "everySeconds" to JsonValue.NumberValue(300.0),
            "scheduledAt" to JsonValue.StringValue("2099-01-01T00:00:00.000Z")
        ))
        val update = GatewayRequests.scheduleUpdate("s1", "task-1", expected, title = "检查新构建", requestId = "edit-1")
        assertEquals(GatewayRequestLanePolicy.REJECT_IF_BUSY, update.lanePolicy)
        assertTrue(update.payload.contains("\"expected\":{\"id\":\"task-1\""))
        assertTrue(update.payload.contains("\"requestId\":\"edit-1\""))
        assertEquals(GatewayRequestLanePolicy.REJECT_IF_BUSY, GatewayRequests.scheduleDelete("s1", "task-1").lanePolicy)
    }

    @Test
    fun scheduleResponsesKeepCatalogHistoryAndConflictFields() {
        val catalog = GatewayWireDecoder.decode(
            """{"kind":"schedule-catalog","items":[{"id":"task-1","sessionId":"s1","status":"active","kind":"every","title":"检查构建","prompt":"检查结果","everySeconds":300,"scheduledAt":"2099-01-01T00:00:00.000Z"}]}"""
        )
        assertEquals("s1", catalog.items?.single()?.get("sessionId")?.stringValue)
        val history = GatewayWireDecoder.decode(
            """{"kind":"schedule-history","sessionId":"s1","id":"task-1","records":[{"scheduledAt":"2099-01-01T00:00:00.000Z","deliveredAt":"2099-01-01T00:00:01.000Z","messageId":"message-1"}],"earlierRecordsUnavailable":false,"earlierRecordsPruned":false,"retention":{"days":30,"records":200},"nextBefore":"message-1"}"""
        )
        assertEquals("message-1", history.records?.single()?.get("messageId")?.stringValue)
        assertEquals("message-1", history.nextBefore)
        val conflict = GatewayWireDecoder.decode(
            """{"kind":"schedule-update","sessionId":"s1","id":"task-1","updated":false,"code":"schedule_conflict"}"""
        )
        assertEquals("schedule_conflict", conflict.code)
        assertEquals(false, conflict.updated)
    }

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
    fun wireDecoderAcceptsPermissionOptionsWithValueAndName() {
        val frame = GatewayWireDecoder.decode(
            """{"kind":"permission-options","sessionId":"s1","options":[{"value":"ask","name":"Ask"}],"defaultOptions":[{"value":"ask","name":"Ask"}],"defaultPreset":"ask","sessionPermissions":{"currentValue":"ask","options":[{"value":"ask","name":"Ask"}]}}"""
        )
        assertEquals("permission-options", frame.kind)
        assertEquals(1, frame.options?.size)
        assertEquals("ask", frame.sessionPermissions?.currentValue)
        assertEquals("ask", frame.sessionPermissions?.options?.single()?.value)
    }

    @Test
    fun wireDecoderReportsMissingPermissionOptionField() {
        val payload =
            """{"kind":"permission-options","sessionPermissions":{"options":[{"value":"ask"}]}}"""
        val failure = runCatching { GatewayWireDecoder.decode(payload) }.exceptionOrNull()
        assertTrue(failure != null)
        assertTrue(GatewayWireDecoder.failureSummary(payload, failure).contains("缺少必填字段 name"))
    }

    @Test
    fun wireDecoderAcceptsStructuredToolFailureFromGateway() {
        val frame = GatewayWireDecoder.decode(
            """{"kind":"event","sessionId":"s1","seq":9000,"time":1,"event":{"type":"tool/result","turn":1,"step":0,"callId":"failed-call","isError":true,"preview":"failed","error":{"name":"ToolError","code":"DENIED","reason":"permission denied"}}}"""
        )
        assertEquals("tool/result", frame.event?.type)
        assertEquals("permission denied", frame.event?.error)
        assertEquals(true, frame.event?.isError)

        val legacy = GatewayWireDecoder.decode(
            """{"kind":"event","sessionId":"s1","seq":9001,"time":2,"event":{"type":"tool/result","error":"legacy error"}}"""
        )
        assertEquals("legacy error", legacy.event?.error)
    }

    @Test
    fun wireDecoderFailureReportsFrameAndFieldWithoutPayload() {
        val payload = """{"kind":"event","sessionId":"private-session","event":{"type":"tool/result","turn":"wrong-type","preview":"private-message"}}"""
        val failure = runCatching { GatewayWireDecoder.decode(payload) }.exceptionOrNull()
        assertTrue(failure != null)
        val detail = GatewayWireDecoder.failureSummary(payload, failure)
        assertTrue(detail.contains("event/tool/result"))
        assertTrue(detail.contains("decode-failed"))
        assertTrue(detail.contains("\$.event.turn"), detail)
        assertTrue(!detail.contains("private-session"))
        assertTrue(!detail.contains("private-message"))

        val explicit = GatewayWireDecoder.failureSummary(
            payload,
            IllegalArgumentException("Expected an int at path: \$.event.turn; value=private-message")
        )
        assertTrue(explicit.contains("\$.event.turn"))
        assertTrue(explicit.contains("需要 integer，收到 string"))
        assertTrue(!explicit.contains("private-message"))
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
    fun approvalWireContractAndReducerEnforceOneShotDecision() {
        val frame = GatewayWireDecoder.decode(GatewayProtocolFixtures.REPLAYED_APPROVAL_REQUEST)
        assertEquals("rpc-approval-1", frame.rpcId)
        assertEquals("approval-1", frame.approvalId)
        assertEquals("Bash", frame.toolName)
        assertEquals("call-1", frame.callId)
        assertEquals("需要读取系统版本", frame.reason)
        assertEquals("允许读取系统版本？", frame.displayReason?.get("zh-CN"))
        assertEquals(true, frame.replay)

        val request = GatewayPendingApprovalRequest(
            rpcId = "rpc-approval-1",
            sessionId = "s1",
            approvalId = "approval-1",
            toolName = "Bash",
            callId = "call-1",
            reason = "需要读取系统版本",
            displayReason = frame.displayReason,
            replay = true
        )
        assertEquals("允许读取系统版本？", request.localizedReason("zh-CN"))
        assertEquals("Allow reading system version?", request.localizedReason("en-US"))
        var state = ApprovalReducer.reduce(
            ApprovalState(),
            ApprovalAction.RequestReceived(request)
        )
        state = ApprovalReducer.reduce(
            state,
            ApprovalAction.Submit(request, GatewayApprovalOutcome.ALLOWED_ONCE, isConnected = true)
        )
        assertEquals(
            GatewayApprovalOutcome.ALLOWED_ONCE,
            assertIs<ApprovalRequestStatus.Submitting>(
                state.requestStatuses[request.rpcId]
            ).outcome
        )
        state = ApprovalReducer.reduce(state, ApprovalAction.Resolved(request.rpcId))
        assertTrue(state.pendingRequests.isEmpty())
        assertNull(state.requestStatuses[request.rpcId])

        val response = GatewayRequests.approvalResponse(
            rpcId = request.rpcId,
            sessionId = request.sessionId,
            approvalId = request.approvalId,
            outcome = GatewayApprovalOutcome.ALLOWED_ONCE
        )
        assertEquals(
            "{\"type\":\"approval-response\",\"rpcId\":\"rpc-approval-1\",\"sessionId\":\"s1\",\"approvalId\":\"approval-1\",\"outcome\":\"allowed-once\"}",
            response.payload
        )
        assertEquals(GatewayRequestLanePolicy.REJECT_IF_BUSY, response.lanePolicy)
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
    fun sessionCancelUsesDedicatedResponseLaneAndKeepsSessionIdentity() {
        val request = GatewayRequests.sessionCancel("session-1")

        assertEquals("session-cancel", request.requestType)
        assertEquals("session-cancelled", request.responseKind)
        assertEquals("session-1", request.targetSessionId)
        assertEquals(GatewayRequestLanePolicy.REJECT_IF_BUSY, request.lanePolicy)
        assertEquals("{\"type\":\"session-cancel\",\"sessionId\":\"session-1\"}", request.payload)
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
    fun sessionControlReducerKeepsCatalogPermissionsAndClearsLoadingTarget() {
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
        assertEquals(listOf("read-only", "unsupported"), state.sessionPermissions["s1"]?.options?.map { it.value })
        state = SessionControlReducer.reduce(state, SessionControlAction.RequestFinished("models"))
        assertNull(state.pendingModelsSessionId)
        assertTrue("models" !in state.loadingKinds)
    }
}
