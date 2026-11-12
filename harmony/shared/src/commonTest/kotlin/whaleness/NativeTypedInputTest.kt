package whaleness

import com.clarklevis.dsh.shared.facade.SharedConversationStore
import com.clarklevis.dsh.shared.facade.SharedHistoryStore
import com.clarklevis.dsh.shared.facade.SharedMviEventObserver
import com.clarklevis.dsh.shared.protocol.GatewayEvent
import com.clarklevis.dsh.shared.protocol.SessionEvent
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeTypedInputTest {
    @Test
    fun typedHistoryMatchesWireSnapshotPaginationAndLiveUpdates() {
        val native = SharedHistoryStore()
        val wire = SharedHistoryStore()
        val nativePatches = mutableListOf<JsonElement>()
        val wirePatches = mutableListOf<JsonElement>()
        native.subscribePatches { _, patch -> nativePatches += wireJson.parseToJsonElement(wireJson.encodeToString(patch)) }
        wire.subscribe(
            SharedMviEventObserver { event ->
                if (event.kind == "transition") wirePatches += wireJson.parseToJsonElement(event.statePayloadJson!!)
            },
        )
        val records = mutableListOf(record(4, "superseded"), record(4, "canonical 🙂"))
        val json = wireJson.encodeToString(records)
        assertTrue(native.installTypedSnapshot("s1", records, true, 4).accepted)
        records.clear()
        assertTrue(wire.installSnapshot("s1", json, true, 4).accepted)
        assertEquals(wirePatches, nativePatches)
        native.start("s1", true, true, 4)
        wire.start("s1", true, true, 4)
        val older = listOf(record(2, "older"), record(1, "oldest"))
        assertTrue(native.pageReceivedTyped("s1", older, 100, false, null, null).accepted)
        assertTrue(wire.pageReceived("s1", wireJson.encodeToString(older), 100, false, null, null).accepted)
        assertTrue(native.liveTypedEventReceived(record(5, "latest")).accepted)
        assertTrue(wire.liveEventReceived(wireJson.encodeToString(record(5, "latest"))).accepted)
        assertEquals(wirePatches, nativePatches)
    }

    @Test
    fun invalidTypedSnapshotAndPageDoNotReplaceThePreviousSession() {
        val store = SharedHistoryStore()
        val snapshots = mutableListOf<String>()
        store.subscribePatches { event, patch ->
            store.projectionSnapshotForEvent(patch.sessionId, event.sequence)?.let { snapshots += it.eventsJson() }
        }
        assertTrue(store.installTypedSnapshot("s1", listOf(record(1, "safe")), false, null).accepted)
        assertFalse(store.installTypedSnapshot("s1", listOf(record(2, "foreign").copy(sessionId = "other")), false, null).accepted)
        assertFalse(store.pageReceivedTyped("s1", listOf(record(0, "foreign").copy(sessionId = "other")), 1, false, null, null).accepted)
        assertTrue(store.liveTypedEventReceived(record(2, "continued")).accepted)
        assertTrue("safe" in snapshots.last())
        assertTrue("continued" in snapshots.last())
        assertFalse("foreign" in snapshots.last())
    }

    @Test
    fun typedConversationMatchesWireIncludingSameSequenceStreamDelta() {
        val native = SharedConversationStore()
        val wire = SharedConversationStore()
        val nativePatches = mutableListOf<JsonElement>()
        val wirePatches = mutableListOf<JsonElement>()
        native.subscribePatches { _, patch -> nativePatches += wireJson.parseToJsonElement(wireJson.encodeToString(patch)) }
        wire.subscribe(
            SharedMviEventObserver { event ->
                if (event.kind == "transition") wirePatches += wireJson.parseToJsonElement(event.statePayloadJson!!)
            },
        )
        val chunk =
            record(
                2,
                "Hel🙂",
            ).copy(event = GatewayEvent("assistant/chunk", turn = 2, step = 1, chunkType = "text-delta", text = "Hel🙂"))
        assertFalse(native.receiveTypedStreamDelta(chunk).accepted)
        assertFalse(wire.receiveStreamDelta(wireJson.encodeToString(chunk)).accepted)
        val baseline = listOf(record(1, "baseline"))
        native.replaceTypedSession("s1", baseline)
        wire.replaceSession("s1", wireJson.encodeToString(baseline))
        assertTrue(native.receiveTypedEvent(chunk).accepted)
        assertTrue(wire.receiveEvent(wireJson.encodeToString(chunk)).accepted)
        val delta = chunk.copy(event = chunk.event.copy(text = "lo"))
        assertTrue(native.receiveTypedStreamDelta(delta).accepted)
        assertTrue(wire.receiveStreamDelta(wireJson.encodeToString(delta)).accepted)
        val final = record(3, "Hel🙂lo").copy(event = GatewayEvent("assistant/message", turn = 2, step = 1, text = "Hel🙂lo"))
        assertTrue(native.receiveTypedEvent(final).accepted)
        assertTrue(wire.receiveEvent(wireJson.encodeToString(final)).accepted)
        assertEquals(wirePatches, nativePatches)
    }

    @Test
    fun typedLiveRecordStillRejectsNonFiniteTime() {
        val store = SharedHistoryStore()
        var updates = 0
        store.subscribePatches { _, _ -> updates++ }
        assertFalse(store.liveTypedEventReceived(record(1, "invalid").copy(time = Double.NaN)).accepted)
        assertEquals(0, updates)
        assertTrue(store.liveTypedEventReceived(record(1, "valid")).accepted)
        assertEquals(1, updates)
    }

    private fun record(
        sequence: Int,
        text: String,
    ): SessionEvent =
        SessionEvent("s1", sequence, sequence.toDouble(), GatewayEvent("assistant/message", turn = sequence, step = 1, text = text))
}
