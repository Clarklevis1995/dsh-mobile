package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.facade.SharedMviEvent
import com.clarklevis.dsh.shared.facade.SharedMviEventObserver
import com.clarklevis.dsh.shared.facade.SharedTrajectoryPatch
import com.clarklevis.dsh.shared.facade.SharedTrajectoryStore
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

class SharedTrajectoryStoreTest {
    @Test
    fun streamingTrajectoryUsesNodeUpdatesWithoutAccumulatedSubtitle() {
        val store = SharedTrajectoryStore()
        val received = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(received::add))

        store.receiveEvent(eventJson(1, chunk("A")))
        val first = patch(received.last())
        assertEquals(listOf("insert", "insert"), first.operations.map { it.kind })

        store.receiveEvent(eventJson(2, chunk("B")))
        val payload = received.last().statePayloadJson!!
        val second = wireJson.decodeFromString<SharedTrajectoryPatch>(payload)
        assertEquals(listOf("update", "update"), second.operations.map { it.kind })
        assertEquals(listOf("", "B"), second.operations.map { it.subtitleDelta })
        assertTrue(second.operations.all { it.item == null })
        assertFalse("AB" in payload)

        store.receiveEvent(eventJson(3, GatewayEvent("assistant/message", turn = 1, step = 1, text = "Done")))
        val final = patch(received.last())
        assertTrue(final.operations.any { it.kind == "remove" && it.itemId == "assistant-stream-1-1" })
        assertTrue(final.operations.any { it.kind in setOf("replace", "update") && it.itemId == "request-1-1" })
        assertTrue(final.operations.any { it.kind == "insert" && it.item?.id == "assistant-s1-3" })
    }

    @Test
    fun noVisualEventProducesAckWithoutPayloadAndToolResultUpdatesOneNode() {
        val store = SharedTrajectoryStore()
        val received = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(received::add))

        val ignored = store.receiveEvent(eventJson(1, GatewayEvent("turn/start", turn = 1)))
        assertTrue(ignored.accepted)
        assertNull(ignored.transactionId)
        assertEquals(1, received.size)

        store.receiveEvent(eventJson(2, GatewayEvent("tool/call", callId = "c1", name = "Read")))
        store.receiveEvent(eventJson(3, GatewayEvent("tool/result", callId = "c1", preview = "ok")))
        val result = patch(received.last())
        assertEquals(1, result.operations.size)
        assertEquals("update", result.operations.single().kind)
        assertEquals("ok", result.operations.single().subtitleDelta)
    }

    @Test
    fun baselineReplaceClearAndOutOfOrderFailureAreExplicit() {
        val store = SharedTrajectoryStore()
        val received = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(received::add))
        val baseline = listOf(
            event(1, GatewayEvent("user/message", text = "question")),
            event(2, GatewayEvent("assistant/message", turn = 1, step = 1, text = "answer"))
        )
        assertTrue(store.replaceSession("s1", wireJson.encodeToString(baseline)).accepted)
        assertTrue(patch(received.last()).replacesAll)

        val duplicate = store.receiveEvent(eventJson(2, GatewayEvent("assistant/message", text = "duplicate")))
        assertFalse(duplicate.accepted)
        assertEquals("trajectory-event-failed", duplicate.errorCode)
        assertEquals("error", received.last().kind)

        assertTrue(store.clearSession("s1").accepted)
        assertTrue(patch(received.last()).replacementNodes.orEmpty().isEmpty())
    }

    @Test
    fun burstEventsAreCommittedInOneTransition() {
        val store = SharedTrajectoryStore()
        val received = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(received::add))
        val batch = listOf(event(1, chunk("A")), event(2, chunk("B")))

        val result = store.receiveEvents(wireJson.encodeToString(batch))

        assertTrue(result.accepted)
        assertEquals(2, received.size)
        val transition = patch(received.last())
        assertEquals(2, transition.lastSequence)
        assertEquals("AB", transition.operations.last().item?.subtitle)
    }

    private fun patch(event: SharedMviEvent): SharedTrajectoryPatch =
        wireJson.decodeFromString(event.statePayloadJson!!)

    private fun chunk(text: String) =
        GatewayEvent("assistant/chunk", turn = 1, step = 1, chunkType = "text-delta", text = text)

    private fun event(sequence: Int, gatewayEvent: GatewayEvent) =
        SessionEvent("s1", sequence, sequence.toDouble(), gatewayEvent)

    private fun eventJson(sequence: Int, gatewayEvent: GatewayEvent): String =
        wireJson.encodeToString(event(sequence, gatewayEvent))
}
