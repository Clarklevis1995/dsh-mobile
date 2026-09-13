package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.facade.SharedConversationBootstrap
import com.clarklevis.dsh.shared.facade.SharedConversationPatch
import com.clarklevis.dsh.shared.facade.SharedConversationStore
import com.clarklevis.dsh.shared.facade.SharedMviEvent
import com.clarklevis.dsh.shared.facade.SharedMviEventObserver
import com.clarklevis.dsh.shared.protocol.GatewayEvent
import com.clarklevis.dsh.shared.protocol.SessionEvent
import com.clarklevis.dsh.shared.protocol.ToolDelta
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedConversationStoreTest {
    @Test
    fun invisiblePersistentEventEmitsWatermarkWithoutRowOperations() {
        val store = SharedConversationStore()
        val received = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(received::add))
        store.receiveEvent(eventJson(1, GatewayEvent("user/message", text = "Hi")))
        for ((offset, type) in listOf("turn/start", "step/start", "request/header").withIndex()) {
            val previousCount = received.size
            assertTrue(store.receiveEvent(eventJson(offset + 2, GatewayEvent(type))).accepted)
            assertEquals(previousCount + 1, received.size)
            assertEquals(offset + 2, patch(received.last()).lastSequence)
            assertTrue(patch(received.last()).operations.isEmpty())
            assertFalse(patch(received.last()).replacesAll)
        }
        assertTrue(store.assistantChunks("s1", "attempt", """[{"time":5,"chunk":{"type":"text-delta","index":0,"text":"Hello"}}]""").accepted)
        assertEquals(4, patch(received.last()).lastSequence)
        assertEquals("insert", patch(received.last()).operations.single().kind)
    }

    @Test
    fun streamingTextUsesInsertThenDeltaOnlyPatchAndFinalReplacement() {
        val store = SharedConversationStore()
        val received = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(received::add))

        assertEquals(1, wireJson.decodeFromString<SharedConversationBootstrap>(received.single().statePayloadJson!!).schema)

        assertTrue(store.receiveEvent(eventJson(1, chunk("Hel"))).accepted)
        val first = patch(received.last())
        assertEquals("insert", first.operations.single().kind)
        assertEquals("Hel", first.operations.single().item?.text)

        assertTrue(store.receiveEvent(eventJson(2, chunk("lo"))).accepted)
        val secondPayload = received.last().statePayloadJson!!
        val second = wireJson.decodeFromString<SharedConversationPatch>(secondPayload)
        assertEquals("append-text", second.operations.single().kind)
        assertEquals("lo", second.operations.single().delta)
        assertNull(second.operations.single().item)
        assertFalse("Hello" in secondPayload)

        assertTrue(store.receiveEvent(eventJson(3, GatewayEvent("assistant/message", turn = 1, step = 1, text = "Hello!"))).accepted)
        val final = patch(received.last())
        assertEquals(listOf("replace"), final.operations.map { it.kind })
        assertEquals("stream-text-1-1", final.operations.single().itemId)
        assertEquals("stream-text-1-1", final.operations.single().item?.id)
        assertEquals("Hello!", final.operations.single().item?.text)
    }

    @Test
    fun tokenPatchSizeDependsOnDeltaRatherThanAccumulatedText() {
        val store = SharedConversationStore()
        val received = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(received::add))
        store.receiveEvent(eventJson(1, chunk("x".repeat(4_096))))
        store.receiveEvent(eventJson(2, chunk("a")))
        val shortPatchSize = received.last().statePayloadJson!!.length
        store.receiveEvent(eventJson(3, chunk("b")))
        val laterPatchSize = received.last().statePayloadJson!!.length

        assertEquals(shortPatchSize, laterPatchSize)
        assertTrue(laterPatchSize < 256)
    }

    @Test
    fun historyBaselineReplacesAllAndDuplicateLiveEventFailsWithoutTransition() {
        val store = SharedConversationStore()
        val received = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(received::add))
        val baseline = listOf(
            event(1, GatewayEvent("user/message", text = "question")),
            event(2, GatewayEvent("assistant/message", turn = 1, step = 1, text = "answer"))
        )

        assertTrue(store.replaceSession("s1", wireJson.encodeToString(baseline)).accepted)
        val replacement = patch(received.last())
        assertTrue(replacement.replacesAll)
        assertEquals(listOf("question", "answer"), replacement.replacementItems?.map { it.text })
        assertEquals(2, replacement.lastSequence)

        val duplicate = store.receiveEvent(eventJson(2, GatewayEvent("assistant/message", text = "duplicate")))
        assertFalse(duplicate.accepted)
        assertEquals("conversation-event-failed", duplicate.errorCode)
        assertEquals("error", received.last().kind)

        assertTrue(store.receiveEvent(eventJson(3, GatewayEvent("assistant/message", turn = 2, step = 1, text = "tail"))).accepted)
        assertEquals("tail", patch(received.last()).operations.single().item?.text)
    }

    @Test
    fun clearEmitsEmptyReplacementAndCancelledObserverStopsReceiving() {
        val store = SharedConversationStore()
        val received = mutableListOf<SharedMviEvent>()
        val subscription = store.subscribe(SharedMviEventObserver(received::add))
        store.receiveEvent(eventJson(1, GatewayEvent("user/message", text = "one")))
        assertTrue(store.clearSession("s1").accepted)
        val cleared = patch(received.last())
        assertTrue(cleared.replacesAll)
        assertTrue(cleared.replacementItems.orEmpty().isEmpty())

        subscription.cancel()
        store.receiveEvent(eventJson(2, GatewayEvent("user/message", text = "two")))
        assertEquals(3, received.size)
    }

    // --- Row-local fold for chunks that share their turn's `session.seq` -------

    @Test
    fun sameSequenceStreamDeltasAccumulateIntoOneRowWithoutRebaseline() {
        val store = SharedConversationStore()
        val received = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(received::add))

        // The step's first chunk establishes the row through the ordinary path.
        assertTrue(store.receiveEvent(eventJson(7, chunk("Hel"))).accepted)
        assertEquals(listOf("insert"), patch(received.last()).operations.map { it.kind })

        // DSH shares one `session.seq` across every chunk of a turn, so the
        // monotonic guard in `receiveEvent` rejects the rest of the step. The
        // stream path must accept them and stay row-local: no `replacesAll`, no
        // whole-history reserialisation, and never a second `insert`.
        assertTrue(store.receiveStreamDelta(eventJson(7, chunk("lo"))).accepted)
        val second = patch(received.last())
        assertEquals(listOf("append-text"), second.operations.map { it.kind })
        assertEquals("lo", second.operations.single().delta)
        assertEquals("stream-text-1-1", second.operations.single().itemId)
        assertFalse(second.replacesAll)
        assertNull(second.replacementItems)

        assertTrue(store.receiveStreamDelta(eventJson(7, chunk(" there"))).accepted)
        assertEquals(" there", patch(received.last()).operations.single().delta)
        assertTrue(store.receiveStreamDelta(eventJson(7, chunk("!"))).accepted)
        val fourth = patch(received.last())
        assertEquals(listOf("append-text"), fourth.operations.map { it.kind })
        assertEquals("stream-text-1-1", fourth.operations.single().itemId)
    }

    @Test
    fun sameSequenceReasoningDeltasAccumulateIntoReasoningRow() {
        val store = SharedConversationStore()
        val received = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(received::add))

        assertTrue(store.receiveEvent(eventJson(2, reasoning("thin"))).accepted)
        assertTrue(store.receiveStreamDelta(eventJson(2, reasoning("king"))).accepted)
        val patchValue = patch(received.last())
        assertEquals(listOf("append-text"), patchValue.operations.map { it.kind })
        assertEquals("king", patchValue.operations.single().delta)
        assertEquals("stream-reason-1-1", patchValue.operations.single().itemId)
        assertFalse(patchValue.replacesAll)
    }

    @Test
    fun sameSequenceToolCallDeltasAccumulateIntoOneToolRow() {
        val store = SharedConversationStore()
        val received = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(received::add))

        assertTrue(store.receiveEvent(eventJson(3, toolCall("{\"a\""))).accepted)
        assertTrue(store.receiveStreamDelta(eventJson(3, toolCall(":1}"))).accepted)
        val patchValue = patch(received.last())
        assertEquals(listOf("append-text"), patchValue.operations.map { it.kind })
        assertEquals(":1}", patchValue.operations.single().delta)
        assertEquals("stream-tool-call-1", patchValue.operations.single().itemId)
        assertFalse(patchValue.replacesAll)
    }

    @Test
    fun finalAssistantMessageAtTheSameSequenceStillFinalizesTheStreamedRow() {
        val store = SharedConversationStore()
        val received = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(received::add))

        store.receiveEvent(eventJson(7, chunk("Hel")))
        store.receiveStreamDelta(eventJson(7, chunk("lo")))

        // `assistant/message` carries the authoritative text for the same
        // durable `seq`. It must be applied as finalization, not rejected for
        // not being strictly newer, or the finished reply never appears.
        val result = store.receiveStreamDelta(
            eventJson(7, GatewayEvent("assistant/message", turn = 1, step = 1, text = "Hello there"))
        )
        assertTrue(result.accepted)
        val patchValue = patch(received.last())
        assertEquals(listOf("replace"), patchValue.operations.map { it.kind })
        assertEquals("stream-text-1-1", patchValue.operations.single().itemId)
        assertEquals("Hello there", patchValue.operations.single().item?.text)
        assertFalse(patchValue.replacesAll)
    }

    @Test
    fun streamDeltaWithoutBaselineFailsClosed() {
        val store = SharedConversationStore()
        val received = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(received::add))

        // No projector means no row to mutate. Fail closed so the platform can
        // fall back to a baseline load instead of silently dropping content.
        val result = store.receiveStreamDelta(eventJson(1, chunk("orphan")))
        assertFalse(result.accepted)
        assertEquals("conversation-stream-failed", result.errorCode)
        assertFalse(store.hasProjection("s1"))
    }

    @Test
    fun streamPatchSizeStaysProportionalToTheDeltaNotTheAccumulatedRow() {
        val store = SharedConversationStore()
        val received = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(received::add))

        store.receiveEvent(eventJson(7, chunk("x".repeat(4_096))))
        store.receiveStreamDelta(eventJson(7, chunk("a")))
        val early = received.last().statePayloadJson!!.length
        store.receiveStreamDelta(eventJson(7, chunk("b")))
        val late = received.last().statePayloadJson!!.length

        // A rebaseline would carry the accumulated text, so a patch that grows
        // with the row is the regression signature of `replace` coming back.
        assertEquals(early, late)
    }

    @Test
    fun nonDeltaAndUnknownChunkTypesAreInertRatherThanErrors() {
        val store = SharedConversationStore()
        val received = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(received::add))
        store.receiveEvent(eventJson(7, chunk("Hel")))
        val before = received.size

        // Nothing here has display content, so the projector emits no operation.
        // They must still be accepted: dropping unknown chunk types to make a
        // test pass would silently discard future protocol additions.
        listOf("usage", "finish", "block-start", "block-end", "some-future-chunk-type").forEach { type ->
            val result = store.receiveStreamDelta(
                eventJson(7, GatewayEvent("assistant/chunk", turn = 1, step = 1, chunkType = type))
            )
            assertTrue(result.accepted, "chunk type $type was rejected")
        }
        assertEquals(before, received.size)
    }

    @Test
    fun hasProjectionTracksBaselineLifecycle() {
        val store = SharedConversationStore()
        assertFalse(store.hasProjection("s1"))
        store.receiveEvent(eventJson(1, chunk("Hel")))
        assertTrue(store.hasProjection("s1"))
        store.clearSession("s1")
        assertFalse(store.hasProjection("s1"))
    }

    private fun patch(event: SharedMviEvent): SharedConversationPatch =
        wireJson.decodeFromString(event.statePayloadJson!!)

    private fun chunk(text: String) =
        GatewayEvent("assistant/chunk", turn = 1, step = 1, chunkType = "text-delta", text = text)

    private fun reasoning(text: String) =
        GatewayEvent("assistant/chunk", turn = 1, step = 1, chunkType = "reasoning-delta", text = text)

    private fun toolCall(argumentsDelta: String) =
        GatewayEvent(
            "assistant/chunk",
            turn = 1,
            step = 1,
            chunkType = "tool-call-delta",
            tool = ToolDelta(id = "call-1", name = "run_code", argumentsDelta = argumentsDelta)
        )

    private fun event(sequence: Int, gatewayEvent: GatewayEvent) =
        SessionEvent("s1", sequence, sequence.toDouble(), gatewayEvent)

    private fun eventJson(sequence: Int, gatewayEvent: GatewayEvent): String =
        wireJson.encodeToString(event(sequence, gatewayEvent))
}
