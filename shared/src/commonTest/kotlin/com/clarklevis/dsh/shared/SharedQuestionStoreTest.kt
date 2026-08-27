package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.facade.SharedQuestionEffect
import com.clarklevis.dsh.shared.facade.SharedQuestionSnapshot
import com.clarklevis.dsh.shared.facade.SharedQuestionStore
import com.clarklevis.dsh.shared.facade.SharedMviEvent
import com.clarklevis.dsh.shared.facade.SharedMviEventObserver
import com.clarklevis.dsh.shared.protocol.GatewayPendingQuestionRequest
import com.clarklevis.dsh.shared.protocol.GatewayQuestion
import com.clarklevis.dsh.shared.protocol.GatewayQuestionAnswer
import com.clarklevis.dsh.shared.protocol.GatewayQuestionOption
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedQuestionStoreTest {
    @Test
    fun subscriptionPushesStateAndEffectInOneTransaction() {
        val store = SharedQuestionStore()
        val events = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(events::add))
        val request = request()
        store.requestReceived(wireJson.encodeToString(request))
        val answers = listOf(
            GatewayQuestionAnswer("direction", listOf("架构")),
            GatewayQuestionAnswer("notes", emptyList(), "原生 UI")
        )
        store.submitAnswer(request.rpcId, wireJson.encodeToString(answers), true)
        store.submitAnswer(request.rpcId, wireJson.encodeToString(answers), true)

        assertEquals(listOf(0L, 1L, 2L), events.map { it.sequence })
        val submitted = events.last()
        assertEquals("transition", submitted.kind)
        assertEquals(
            "submitting",
            snapshot(submitted.statePayloadJson).requestStatuses.getValue(request.rpcId).kind
        )
        val effects = wireJson.decodeFromString<List<SharedQuestionEffect>>(submitted.effectsJson)
        assertEquals(1, effects.size)
        assertEquals("answer", effects.single().action)
    }

    @Test
    fun requestReplayAndAnswerProduceOneOrderedEffect() {
        val store = SharedQuestionStore()
        val request = request()
        val requested = store.requestReceived(wireJson.encodeToString(request))
        assertTrue(requested.isSuccess)
        assertEquals("idle", snapshot(requested.snapshotJson).requestStatuses.getValue(request.rpcId).kind)

        val answers = listOf(
            GatewayQuestionAnswer("direction", listOf("移动端", "架构")),
            GatewayQuestionAnswer("notes", emptyList(), " 保持原生 UI ")
        )
        val submitted = store.submitAnswer(request.rpcId, wireJson.encodeToString(answers), true)
        val effect = effect(submitted.effectJson)
        assertEquals("submitting", snapshot(submitted.snapshotJson).requestStatuses.getValue(request.rpcId).kind)
        assertEquals("answer", effect.action)
        assertEquals(listOf("direction", "notes"), effect.answers.orEmpty().map { it.id })
        assertEquals(listOf("移动端", "架构"), effect.answers.orEmpty().first().selected)

        // 重复点击和 reconnect replay 均保留 busy 状态，不得再次触发平台 I/O。
        assertNull(store.submitAnswer(request.rpcId, wireJson.encodeToString(answers), true).effectJson)
        val replayed = store.requestReceived(wireJson.encodeToString(request.copy(replay = true)))
        assertEquals("submitting", snapshot(replayed.snapshotJson).requestStatuses.getValue(request.rpcId).kind)
        assertNull(store.submitAnswer(request.rpcId, wireJson.encodeToString(answers), true).effectJson)
    }

    @Test
    fun validationAndDisconnectedPathsNeverProduceEffects() {
        val store = storeWithRequest()
        val wrongOrder = listOf(GatewayQuestionAnswer("notes", emptyList()))
        val invalidOrder = store.submitAnswer("rpc-question", wireJson.encodeToString(wrongOrder), true)
        assertEquals(
            "INVALID_ANSWER_ORDER",
            snapshot(invalidOrder.snapshotJson).requestStatuses.getValue("rpc-question").failureCode
        )
        assertNull(invalidOrder.effectJson)

        val invalidOption = answers(direction = listOf("不存在"))
        val invalid = store.submitAnswer("rpc-question", wireJson.encodeToString(invalidOption), true)
        assertEquals(
            "INVALID_OR_DUPLICATE_OPTIONS",
            snapshot(invalid.snapshotJson).requestStatuses.getValue("rpc-question").failureCode
        )
        assertNull(invalid.effectJson)

        val duplicate = store.submitAnswer(
            "rpc-question",
            wireJson.encodeToString(answers(direction = listOf("架构", "架构"))),
            true
        )
        assertEquals(
            "INVALID_OR_DUPLICATE_OPTIONS",
            snapshot(store.snapshot().snapshotJson).requestStatuses.getValue("rpc-question").failureCode
        )
        assertNull(duplicate.effectJson)

        val singleViolation = answers(notes = listOf("简洁"), notesCustom = "也要完整")
        val rejected = store.submitAnswer("rpc-question", wireJson.encodeToString(singleViolation), true)
        assertEquals(
            "SINGLE_SELECTION_REQUIRED",
            snapshot(rejected.snapshotJson).requestStatuses.getValue("rpc-question").failureCode
        )
        assertNull(rejected.effectJson)

        val disconnected = store.submitAnswer("rpc-question", wireJson.encodeToString(answers()), false)
        assertEquals(
            "DISCONNECTED_ANSWER",
            snapshot(disconnected.snapshotJson).requestStatuses.getValue("rpc-question").failureCode
        )
        assertNull(disconnected.effectJson)

        val cancelled = store.submitCancel("rpc-question", false)
        assertEquals(
            "DISCONNECTED_CANCEL",
            snapshot(cancelled.snapshotJson).requestStatuses.getValue("rpc-question").failureCode
        )
        assertNull(cancelled.effectJson)
    }

    @Test
    fun responseFailureResolvedAndResetMatchIosLifecycle() {
        val store = storeWithRequest()
        val submitted = store.submitCancel("rpc-question", true)
        assertEquals("cancel", effect(submitted.effectJson).action)
        assertNull(store.submitCancel("rpc-question", true).effectJson)

        val accepted = store.responseReceived("rpc-question", "cancel", true, null)
        assertEquals("accepted", snapshot(accepted.snapshotJson).requestStatuses.getValue("rpc-question").kind)

        val refused = store.responseReceived("rpc-question", "cancel", false, "bad-response")
        assertEquals(
            "SERVER_REJECTED",
            snapshot(refused.snapshotJson).requestStatuses.getValue("rpc-question").failureCode
        )

        val failed = store.requestFailed("rpc-question", "gateway: refused")
        val failedStatus = snapshot(failed.snapshotJson).requestStatuses.getValue("rpc-question")
        assertEquals("REQUEST_FAILED", failedStatus.failureCode)
        assertEquals("gateway: refused", failedStatus.failureArgument)

        val notPending = store.responseReceived("rpc-question", "answer", false, "not-pending")
        assertTrue(snapshot(notPending.snapshotJson).pendingRequests.isEmpty())

        store.requestReceived(wireJson.encodeToString(request()))
        assertTrue(snapshot(store.resolved("rpc-question").snapshotJson).pendingRequests.isEmpty())

        store.requestReceived(wireJson.encodeToString(request()))
        val reset = snapshot(store.reset().snapshotJson)
        assertTrue(reset.pendingRequests.isEmpty())
        assertTrue(reset.requestStatuses.isEmpty())
    }

    @Test
    fun sessionFailureAtomicallyRejectsEveryMatchingRequestOnly() {
        val store = SharedQuestionStore()
        listOf(
            request(rpcId = "rpc-s1-a", sessionId = "s1"),
            request(rpcId = "rpc-s1-b", sessionId = "s1"),
            request(rpcId = "rpc-s2", sessionId = "s2")
        ).forEach { assertTrue(store.requestReceived(wireJson.encodeToString(it)).isSuccess) }

        val failed = snapshot(store.sessionRequestsFailed("s1", "gateway: refused").snapshotJson)

        assertEquals("REQUEST_FAILED", failed.requestStatuses.getValue("rpc-s1-a").failureCode)
        assertEquals("REQUEST_FAILED", failed.requestStatuses.getValue("rpc-s1-b").failureCode)
        assertEquals("gateway: refused", failed.requestStatuses.getValue("rpc-s1-a").failureArgument)
        assertEquals("idle", failed.requestStatuses.getValue("rpc-s2").kind)
        assertEquals(3, failed.pendingRequests.size)
    }

    @Test
    fun malformedAndNotPendingInputsAreStructuredAndDoNotMutateState() {
        val store = SharedQuestionStore()
        val malformed = store.requestReceived("not-json")
        assertFalse(malformed.isSuccess)
        assertEquals("question-request-received-failed", malformed.errorCode)
        assertTrue(snapshot(store.snapshot().snapshotJson).pendingRequests.isEmpty())

        val absent = store.submitCancel("missing", true)
        assertFalse(absent.isSuccess)
        assertEquals("question-not-pending", absent.errorCode)
        assertNull(absent.effectJson)
        assertTrue(snapshot(store.snapshot().snapshotJson).pendingRequests.isEmpty())
    }

    private fun storeWithRequest(): SharedQuestionStore = SharedQuestionStore().also {
        assertTrue(it.requestReceived(wireJson.encodeToString(request())).isSuccess)
    }

    private fun request(
        rpcId: String = "rpc-question",
        sessionId: String = "session-question"
    ) = GatewayPendingQuestionRequest(
        rpcId = rpcId,
        sessionId = sessionId,
        replay = false,
        questions = listOf(
            GatewayQuestion(
                id = "direction",
                question = "研究方向",
                options = listOf(GatewayQuestionOption("架构"), GatewayQuestionOption("移动端")),
                multiSelect = true
            ),
            GatewayQuestion(
                id = "notes",
                question = "补充要求",
                options = listOf(GatewayQuestionOption("简洁")),
                multiSelect = false
            )
        )
    )

    private fun answers(
        direction: List<String> = listOf("架构"),
        notes: List<String> = emptyList(),
        notesCustom: String? = "保持原生 UI"
    ) = listOf(
        GatewayQuestionAnswer("direction", direction),
        GatewayQuestionAnswer("notes", notes, notesCustom)
    )

    private fun snapshot(json: String?): SharedQuestionSnapshot =
        wireJson.decodeFromString(checkNotNull(json))

    private fun effect(json: String?): SharedQuestionEffect =
        wireJson.decodeFromString(checkNotNull(json))
}
