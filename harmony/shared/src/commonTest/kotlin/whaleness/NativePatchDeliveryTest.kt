package whaleness

import com.clarklevis.dsh.shared.facade.SharedConversationPatch
import com.clarklevis.dsh.shared.facade.SharedConversationStore
import com.clarklevis.dsh.shared.facade.SharedHistoryEffect
import com.clarklevis.dsh.shared.facade.SharedHistoryProjectionSnapshot
import com.clarklevis.dsh.shared.facade.SharedHistoryStore
import com.clarklevis.dsh.shared.facade.SharedMviEvent
import com.clarklevis.dsh.shared.facade.SharedMviEventEmitter
import com.clarklevis.dsh.shared.facade.SharedMviEventObserver
import com.clarklevis.dsh.shared.protocol.GatewayEvent
import com.clarklevis.dsh.shared.protocol.SessionEvent
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativePatchDeliveryTest {
    @Test
    fun typedConversationSkipsJsonAndMixedSubscriptionsPreserveWirePayload() {
        val store = SharedConversationStore()
        val typed = mutableListOf<Pair<SharedMviEvent, SharedConversationPatch>>()
        store.subscribePatches { _, _ -> error("observer failure") }
        val subscription = store.subscribePatches { event, patch -> typed += event to patch }
        assertTrue(typed.isEmpty())
        assertTrue(store.receiveEvent(record(1, "first")).accepted)
        assertNull(typed.single().first.statePayloadJson)
        assertEquals(
            "first",
            typed
                .single()
                .second.operations
                .single()
                .item
                ?.text,
        )

        val wire = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(wire::add))
        assertTrue(store.receiveEvent(record(2, "second")).accepted)
        assertEquals(listOf(1L, 2L), typed.map { it.first.sequence })
        assertEquals(typed.last().second, wireJson.decodeFromString<SharedConversationPatch>(wire.last().statePayloadJson!!))
        subscription.cancel()
        subscription.cancel()
        store.receiveEvent(record(3, "third"))
        assertEquals(2, typed.size)
        assertEquals(3L, wire.last().sequence)
    }

    @Test
    fun reentrantHistoryKeepsExactSnapshotsAndWireOrderUntilDeliveryFinishes() {
        val store = SharedHistoryStore()
        val order = mutableListOf<String>()
        val snapshots = mutableListOf<SharedHistoryProjectionSnapshot>()
        store.subscribePatches { event, patch ->
            order += "typed:${event.sequence}"
            snapshots += assertNotNull(store.projectionSnapshotForEvent(patch.sessionId, event.sequence))
            if (event.sequence == 1L) assertTrue(store.liveEventReceived(record(2, "second")).accepted)
        }
        store.subscribe(
            SharedMviEventObserver { event ->
                if (event.kind == "transition") {
                    order += "wire:${event.sequence}"
                    assertNotNull(store.projectionSnapshotForEvent("s1", event.sequence))
                }
            },
        )
        assertTrue(store.installSnapshot("s1", "[${record(1, "first")}]", false, null).accepted)
        assertEquals(listOf("typed:1", "wire:1", "typed:2", "wire:2"), order)
        assertFalse("second" in snapshots.first().eventsJson())
        assertTrue("second" in snapshots.last().eventsJson())
        assertNull(store.projectionSnapshotForEvent("s1", 1))
        assertNull(store.projectionSnapshotForEvent("s1", 2))
    }

    @Test
    fun preparedHistoryCommitsFullTypedPatchAndIgnoresStalePreparation() {
        val history = SharedHistoryStore()
        var snapshot: SharedHistoryProjectionSnapshot? = null
        history.subscribePatches { event, patch -> snapshot = history.projectionSnapshotForEvent(patch.sessionId, event.sequence) }
        val text = "long baseline\n".repeat(1000)
        history.installSnapshot("s1", "[${record(1, text)}]", false, null)
        val conversation = SharedConversationStore()
        val received = mutableListOf<Pair<SharedMviEvent, SharedConversationPatch>>()
        conversation.subscribePatches { event, patch -> received += event to patch }
        val prepared = assertNotNull(conversation.prepareHistorySnapshot("s1", assertNotNull(snapshot))).prepare()
        assertTrue(assertNotNull(conversation.commitPreparedHistory(prepared)).accepted)
        assertNull(received.single().first.statePayloadJson)
        assertEquals(
            text,
            received
                .single()
                .second.replacementItems
                ?.single()
                ?.text,
        )
        assertNull(conversation.commitPreparedHistory(prepared))
        assertEquals(1, received.size)
    }

    @Test
    fun typedHistoryCanCancelWithoutRetainingTransactionSnapshots() {
        val store = SharedHistoryStore()
        val received = mutableListOf<SharedMviEvent>()
        val subscription =
            store.subscribePatches { event, patch ->
                assertNull(event.statePayloadJson)
                assertNotNull(store.projectionSnapshotForEvent(patch.sessionId, event.sequence))
                received += event
            }
        store.installSnapshot("s1", "[${record(1, "first")}]", false, null)
        subscription.cancel()
        store.liveEventReceived(record(2, "second"))
        assertEquals(1, received.size)
        assertNull(store.projectionSnapshotForEvent("s1", received.single().sequence))
    }

    @Test
    fun lateWireSubscriberDoesNotReceiveQueuedTransactionsBeforeItsSnapshot() {
        val store = SharedConversationStore()
        val wire = mutableListOf<SharedMviEvent>()
        store.subscribePatches { event, _ ->
            if (event.sequence == 1L) {
                store.receiveEvent(record(2, "queued"))
                store.subscribe(SharedMviEventObserver(wire::add))
            }
        }
        store.receiveEvent(record(1, "first"))
        assertEquals(listOf(2L), wire.map { it.sequence })
        store.receiveEvent(record(3, "newer"))
        assertEquals(listOf(2L, 3L), wire.map { it.sequence })
        assertNotNull(wire.last().statePayloadJson)
    }

    @Test
    fun lateTypedSubscriberStartsAfterItsRegistrationSequence() {
        val store = SharedConversationStore()
        val received = mutableListOf<Long>()
        store.subscribePatches { event, _ ->
            if (event.sequence == 1L) {
                store.receiveEvent(record(2, "queued"))
                store.subscribePatches { newer, _ -> received += newer.sequence }
            }
        }
        store.receiveEvent(record(1, "first"))
        assertTrue(received.isEmpty())
        store.receiveEvent(record(3, "newer"))
        assertEquals(listOf(3L), received)
    }

    @Test
    fun wireOnlyReentryAlsoRespectsNewSubscriberSnapshotBaseline() {
        val emitter = SharedMviEventEmitter("public-wire")
        val received = mutableListOf<SharedMviEvent>()
        emitter.subscribe(
            SharedMviEventObserver { event ->
                if (event.kind == "transition" && event.sequence == 1L) {
                    emitter.emitTransition("queued", "{}")
                    emitter.subscribe(SharedMviEventObserver(received::add), "{}")
                }
            },
            "{}",
        )
        emitter.emitTransition("first", "{}")
        assertEquals(listOf(2L), received.map { it.sequence })
        emitter.emitTransition("newer", "{}")
        assertEquals(listOf(2L, 3L), received.map { it.sequence })
    }

    @Test
    fun typedHistoryStillCarriesTheSameNetworkEffects() {
        val history = SharedHistoryStore()
        val events = mutableListOf<SharedMviEvent>()
        history.subscribePatches { event, _ -> events += event }
        assertTrue(history.start("s1", false, false, null).accepted)
        assertNull(events.single().statePayloadJson)
        val effect = wireJson.decodeFromString<List<SharedHistoryEffect>>(events.single().effectsJson).single()
        assertEquals("s1", effect.sessionId)
    }

    private fun record(
        sequence: Int,
        text: String,
    ): String =
        wireJson.encodeToString(
            SessionEvent("s1", sequence, sequence.toDouble(), GatewayEvent("assistant/message", turn = sequence, step = 1, text = text)),
        )
}
