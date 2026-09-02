package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.facade.SharedConversationBootstrap
import com.clarklevis.dsh.shared.facade.SharedConversationPatch
import com.clarklevis.dsh.shared.facade.SharedConversationStore
import com.clarklevis.dsh.shared.facade.SharedMviEvent
import com.clarklevis.dsh.shared.facade.SharedMviEventObserver
import com.clarklevis.dsh.shared.protocol.GatewayEvent
import com.clarklevis.dsh.shared.protocol.SessionEvent
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

    private fun patch(event: SharedMviEvent): SharedConversationPatch =
        wireJson.decodeFromString(event.statePayloadJson!!)

    private fun chunk(text: String) =
        GatewayEvent("assistant/chunk", turn = 1, step = 1, chunkType = "text-delta", text = text)

    private fun event(sequence: Int, gatewayEvent: GatewayEvent) =
        SessionEvent("s1", sequence, sequence.toDouble(), gatewayEvent)

    private fun eventJson(sequence: Int, gatewayEvent: GatewayEvent): String =
        wireJson.encodeToString(event(sequence, gatewayEvent))
}
