package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.projection.ConversationItem
import com.clarklevis.dsh.shared.projection.ConversationItemKind
import com.clarklevis.dsh.shared.projection.ConversationProjector
import com.clarklevis.dsh.shared.protocol.GatewayEvent
import com.clarklevis.dsh.shared.protocol.JsonValue
import com.clarklevis.dsh.shared.protocol.SessionEvent
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RetryAttemptProjectionTest {
    @Test
    fun threeEmptyFailuresFollowedBySuccessLeaveOnlyTheAnswer() {
        val projector = ConversationProjector()
        projector.fold(listOf(
            attempt(1), retry(2), attempt(3), retry(4),
            attempt(5, message = "Connection error."), retry(6), answer(7)
        ))
        assertEquals(listOf("Saved."), projector.items.map { it.text })
        assertFalse(projector.items.single().isError)
        assertEquals(7, projector.lastSequence)
    }

    @Test
    fun startingARetryRemovesOnlyTheEmptyFailurePlaceholder() {
        val projector = ConversationProjector()
        projector.fold(listOf(attempt(1)))
        assertEquals(1, projector.items.size)
        val operations = projector.foldWithOperations(listOf(retry(2)))
        assertTrue(projector.items.isEmpty())
        assertEquals("remove", operations.single().kind)
    }

    @Test
    fun terminalEmptyFailureRemainsVisibleWithItsActualCause() {
        val projector = ConversationProjector()
        projector.fold(listOf(attempt(1), retry(2), attempt(3), event(4, GatewayEvent("turn/end", turn = 1, reason = "error"))))
        assertEquals(1, projector.items.size)
        assertTrue(projector.items.single().isError)
        assertTrue(projector.items.single().text.contains("Request timed out."))
    }

    @Test
    fun missingRetryEventsStillCollapseEmptyFailuresOnSuccess() {
        val projector = ConversationProjector()
        projector.fold(listOf(attempt(1), attempt(2), attempt(3)))
        assertEquals(1, projector.items.size)
        projector.fold(listOf(answer(4)))
        assertEquals(listOf("Saved."), projector.items.map { it.text })
    }

    @Test
    fun partialReplyRemainsVisibleAfterRetryAndSuccess() {
        val projector = ConversationProjector()
        projector.fold(listOf(attempt(1, partial = "Partial answer"), retry(2), answer(3)))
        assertEquals(listOf("Partial answer", "Saved."), projector.items.map { it.text })
        assertTrue(projector.items.first().isError)
    }

    @Test
    fun successInAnotherStepOrTurnDoesNotEraseAnUnrelatedFailure() {
        val projector = ConversationProjector()
        projector.fold(listOf(attempt(1), answer(2, step = 2), answer(3, turn = 2)))
        assertEquals(3, projector.items.size)
        assertTrue(projector.items.first().isError)
    }

    @Test
    fun absentStepIdentityDoesNotMergeUnrelatedAttempts() {
        val projector = ConversationProjector()
        projector.fold(listOf(attempt(1, step = null), attempt(2, step = null), answer(3)))
        assertEquals(3, projector.items.size)
    }

    @Test
    fun cancellationWithoutAnErrorFinishIsNotHiddenByRetry() {
        val projector = ConversationProjector()
        val stream = JsonValue.fromJsonElement(Json.parseToJsonElement("""[{"type":"chunk","time":1,"chunk":{"type":"finish","reason":{"kind":"cancelled"}}}]"""))
        projector.fold(listOf(event(1, GatewayEvent("assistant/attempt", turn = 1, step = 1, stream = stream)), retry(2), answer(3)))
        assertEquals(2, projector.items.size)
        assertTrue(projector.items.first().isError)
    }

    @Test
    fun failedToolCallAttemptIsNotTreatedAsAnEmptyRequest() {
        val projector = ConversationProjector()
        val stream = JsonValue.fromJsonElement(Json.parseToJsonElement("""[{"type":"tool-call-chunks","time0":1,"index":0,"id":"call-1","name":"read","args":["{}"],"dt":[]},{"type":"chunk","time":1,"chunk":{"type":"finish","reason":{"kind":"error","failure":{"message":"Connection error."}}}}]"""))
        projector.fold(listOf(event(1, GatewayEvent("assistant/attempt", turn = 1, step = 1, stream = stream)), retry(2), answer(3)))
        assertEquals(2, projector.items.size)
        assertTrue(projector.items.first().isError)
    }

    @Test
    fun removingFailurePlaceholderDoesNotInvalidateAnotherStepsStreamIndex() {
        val projector = ConversationProjector()
        projector.fold(listOf(
            attempt(1),
            event(2, GatewayEvent("assistant/chunk", turn = 2, step = 1, chunkType = "text-delta", text = "Sav")),
            retry(3),
            event(4, GatewayEvent("assistant/chunk", turn = 2, step = 1, chunkType = "text-delta", text = "ed.")),
            answer(5, turn = 2)
        ))
        assertEquals(listOf("Saved."), projector.items.map { it.text })
        assertFalse(projector.items.single().isError)
    }

    @Test
    fun incrementalOperationsMatchRebuiltHistoryIncludingFollowingStreams() {
        val source = listOf(
            attempt(1), retry(2),
            event(3, GatewayEvent("assistant/chunk", turn = 1, step = 1, chunkType = "text-delta", text = "Sav")),
            answer(4)
        )
        val projector = ConversationProjector()
        val rendered = mutableListOf<ConversationItem>()
        source.forEach { record ->
            projector.foldWithOperations(listOf(record)).forEach { op ->
                when (op.kind) {
                    "insert" -> rendered += requireNotNull(op.item)
                    "remove" -> rendered.removeAll { it.id == op.itemId }
                    "replace" -> rendered[rendered.indexOfFirst { it.id == op.itemId }] = requireNotNull(op.item)
                    "append-text" -> {
                        val index = rendered.indexOfFirst { it.id == op.itemId }
                        rendered[index] = rendered[index].copy(text = rendered[index].text + op.delta.orEmpty(), epochSeconds = requireNotNull(op.epochSeconds))
                    }
                }
            }
            assertEquals(projector.items, rendered)
        }
        assertEquals(ConversationProjector.make(source), rendered)
        assertEquals(ConversationItemKind.ASSISTANT, rendered.single().kind)
        projector.reset()
        projector.fold(listOf(attempt(1), answer(2)))
        assertEquals(listOf("Saved."), projector.items.map { it.text })
    }

    private fun attempt(seq: Int, message: String = "Request timed out.", partial: String = "", step: Int? = 1): SessionEvent {
        val chunks = if (partial.isEmpty()) "" else """{"type":"text-chunks","time0":1,"index":0,"texts":["$partial"],"dt":[]},"""
        val stream = JsonValue.fromJsonElement(Json.parseToJsonElement("""[$chunks{"type":"chunk","time":1,"chunk":{"type":"usage","usage":{"inputTokens":0,"outputTokens":0}}},{"type":"chunk","time":1,"chunk":{"type":"finish","reason":{"kind":"error","failure":{"message":"$message","code":"TIMEOUT"}}}}]"""))
        return event(seq, GatewayEvent("assistant/attempt", turn = 1, step = step, stream = stream))
    }

    private fun retry(seq: Int) = event(seq, GatewayEvent("llm/retry-started", turn = 1, step = 1))
    private fun answer(seq: Int, turn: Int = 1, step: Int = 1) = event(seq, GatewayEvent("assistant/message", turn = turn, step = step, text = "Saved."))
    private fun event(seq: Int, value: GatewayEvent) = SessionEvent("synthetic-retry-session", seq, seq.toDouble(), value)
}
