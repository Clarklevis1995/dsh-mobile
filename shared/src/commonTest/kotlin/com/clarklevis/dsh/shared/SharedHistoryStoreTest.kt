package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.facade.SharedHistoryBootstrap
import com.clarklevis.dsh.shared.facade.SharedHistoryEffect
import com.clarklevis.dsh.shared.facade.SharedHistoryPatch
import com.clarklevis.dsh.shared.facade.SharedHistoryStore
import com.clarklevis.dsh.shared.facade.SharedMviEvent
import com.clarklevis.dsh.shared.facade.SharedMviEventObserver
import com.clarklevis.dsh.shared.protocol.GatewayEvent
import com.clarklevis.dsh.shared.protocol.SessionEvent
import com.clarklevis.dsh.shared.protocol.ToolDelta
import com.clarklevis.dsh.shared.protocol.wireJson
import com.clarklevis.dsh.shared.sync.HistorySyncConfiguration
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedHistoryStoreTest {
    @Test
    fun paginationStateAndNextCursorEffectShareOneTransaction() {
        val store = SharedHistoryStore()
        val received = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(received::add))
        assertEquals(1, wireJson.decodeFromString<SharedHistoryBootstrap>(received.single().statePayloadJson!!).schema)

        assertTrue(store.start("s1", older = false, hasLocalEvents = false, earliestLocalSequence = null).accepted)
        assertEquals(SharedHistoryEffect("request-page", "s1", null), effects(received.last()).single())
        assertTrue(patch(received.last()).session?.isLoading == true)

        store.processingStarted("s1", rawEventCount = 2, hasMore = true)
        val page = listOf(event(10, "ten"), event(11, "eleven"))
        store.pageReceived(
            sessionId = "s1",
            eventsJson = wireJson.encodeToString(page),
            byteCount = 100,
            hasMore = true,
            nextBeforeSequence = 9,
            remoteActivityTimestamp = 20.0
        )
        val next = patch(received.last())
        assertEquals("replace", next.eventPatch?.kind)
        assertEquals(listOf(10, 11), next.eventPatch?.replacementEvents?.map { it.seq })
        assertEquals(SharedHistoryEffect("request-page", "s1", 9), effects(received.last()).single())

        store.pageReceived(
            sessionId = "s1",
            eventsJson = wireJson.encodeToString(listOf(event(8, "eight"), event(9, "nine"))),
            byteCount = 80,
            hasMore = false,
            nextBeforeSequence = null,
            remoteActivityTimestamp = 20.0
        )
        val completed = patch(received.last())
        assertEquals("completed", completed.outcome)
        assertFalse(completed.session!!.isLoading)
        assertEquals(20.0, completed.session.syncedActivityTimestamp)
        assertTrue(effects(received.last()).isEmpty())
    }

    @Test
    fun liveTailWinsHistoryDuplicatesAndAdvancesWatermark() {
        val store = SharedHistoryStore()
        val received = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(received::add))
        store.start("s1", older = false, hasLocalEvents = false, earliestLocalSequence = null)
        store.pageReceived(
            "s1",
            wireJson.encodeToString(listOf(event(1, "history"))),
            byteCount = 10,
            hasMore = false,
            nextBeforeSequence = null,
            remoteActivityTimestamp = 1.0
        )

        store.liveEventReceived(wireJson.encodeToString(event(2, "live")))
        assertEquals("append", patch(received.last()).eventPatch?.kind)
        assertEquals(2.0, patch(received.last()).session?.syncedActivityTimestamp)

        store.start("s1", older = false, hasLocalEvents = true, earliestLocalSequence = 1)
        store.pageReceived(
            "s1",
            wireJson.encodeToString(listOf(event(2, "stale-history"), event(0, "older"))),
            byteCount = 10,
            hasMore = false,
            nextBeforeSequence = null,
            remoteActivityTimestamp = 2.0
        )
        val rebased = patch(received.last()).eventPatch!!.replacementEvents!!
        assertEquals(listOf(0, 1, 2), rebased.map { it.seq })
        assertEquals("live", rebased.last().event.text)
    }

    @Test
    fun duplicateAndOutOfOrderLiveEventsUseIndexedUpsert() {
        val store = SharedHistoryStore()
        val received = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(received::add))

        store.liveEventReceived(wireJson.encodeToString(event(2, "two")))
        store.liveEventReceived(wireJson.encodeToString(event(1, "one")))
        var update = patch(received.last()).eventPatch!!
        assertEquals("upsert", update.kind)
        assertEquals(0, update.index)

        store.liveEventReceived(wireJson.encodeToString(event(2, "two-final")))
        update = patch(received.last()).eventPatch!!
        assertEquals("upsert", update.kind)
        assertEquals(1, update.index)
        assertEquals("two-final", update.record?.event?.text)
    }

    @Test
    fun malformedPageAndCursorLoopFailClosedWithoutReplacingEvents() {
        val store = SharedHistoryStore(HistorySyncConfiguration(pagesPerBatch = 3))
        val received = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(received::add))
        store.liveEventReceived(wireJson.encodeToString(event(1, "local")))
        val beforeErrorCount = received.size

        val malformed = store.pageReceived(
            "s1", "not-json", 1, false, null, null
        )
        assertFalse(malformed.accepted)
        assertEquals(beforeErrorCount + 1, received.size)
        assertEquals("error", received.last().kind)

        store.start("s1", older = false, hasLocalEvents = true, earliestLocalSequence = 1)
        store.pageReceived(
            "s1", wireJson.encodeToString(listOf(event(1, "local"))), 1, true, 5, null
        )
        store.pageReceived(
            "s1", wireJson.encodeToString(emptyList<SessionEvent>()), 0, true, 5, null
        )
        val failed = patch(received.last())
        assertEquals("failed", failed.outcome)
        assertEquals("REPEATED_CURSOR", failed.failureCode)
        assertNull(failed.eventPatch)
    }

    // --- Streaming deltas share one turn-level `session.seq` ------------------

    @Test
    fun sameSequenceChunksAreUpsertsMarkedAsStreamDeltas() {
        val store = SharedHistoryStore()
        val received = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(received::add))

        // The first chunk of a step arrives at the turn's watermark and takes
        // the ordinary append path.
        assertTrue(store.liveEventReceived(wireJson.encodeToString(chunk(7, "Hel"))).accepted)
        var forwarded = patch(received.last()).eventPatch!!
        assertEquals("append", forwarded.kind)
        assertFalse(forwarded.streamDelta)

        // Everything after it shares that watermark. The wire kind stays
        // `upsert`, so a consumer that does not know the marker keeps its
        // existing handling; the marker is what says this upsert continues a
        // live assistant row rather than replacing it.
        assertTrue(store.liveEventReceived(wireJson.encodeToString(chunk(7, "lo"))).accepted)
        forwarded = patch(received.last()).eventPatch!!
        assertEquals("upsert", forwarded.kind)
        assertTrue(forwarded.streamDelta)
        // The published record is the raw fragment: the conversation lane
        // appends what it receives to a row it already holds.
        assertEquals("lo", forwarded.record?.event?.text)

        assertTrue(store.liveEventReceived(wireJson.encodeToString(chunk(7, " there"))).accepted)
        forwarded = patch(received.last()).eventPatch!!
        assertTrue(forwarded.streamDelta)
        assertEquals(" there", forwarded.record?.event?.text)

        // The journal holds exactly one record per `seq` and makes no claim to
        // accumulate a stream. A rebuild must read the authoritative final
        // event, never a folded fragment log.
        assertEquals(" there", retained(store, "s1").single { it.seq == 7 }.event.text)

        assertTrue(store.liveEventReceived(wireJson.encodeToString(chunk(7, "!"))).accepted)

        // The authoritative finalization at the SAME durable seq replaces the
        // transient record and must NOT be marked as a live delta.
        assertTrue(store.liveEventReceived(wireJson.encodeToString(event(7, "Hello there!"))).accepted)
        val final = patch(received.last()).eventPatch!!
        assertEquals("upsert", final.kind)
        assertFalse(final.streamDelta)
        assertEquals("Hello there!", final.record?.event?.text)
        assertEquals("Hello there!", retained(store, "s1").single { it.seq == 7 }.event.text)

        // Nothing may have been consumed by a whole-list replacement.
        assertNull(final.replacementEvents)
    }

    @Test
    fun reasoningToolCallAndChunkTypeTransitionsStayOnTheStreamDeltaLane() {
        val store = SharedHistoryStore()
        val received = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(received::add))

        assertTrue(store.liveEventReceived(wireJson.encodeToString(reasoning(4, "thin"))).accepted)
        assertEquals("append", patch(received.last()).eventPatch?.kind)
        assertTrue(store.liveEventReceived(wireJson.encodeToString(reasoning(4, "king"))).accepted)
        var forwarded = patch(received.last()).eventPatch!!
        assertTrue(forwarded.streamDelta)
        assertEquals("king", forwarded.record?.event?.text)

        // A different chunk type inside the same turn and step is still the same
        // live row: reasoning gives way to text and the projector routes the two
        // independently, so `chunkType` must not be part of the predicate.
        assertTrue(store.liveEventReceived(wireJson.encodeToString(chunk(4, "answer"))).accepted)
        forwarded = patch(received.last()).eventPatch!!
        assertTrue(forwarded.streamDelta, "a chunk-type transition inside one step is still a live delta")
        assertEquals("answer", forwarded.record?.event?.text)

        assertTrue(store.liveEventReceived(wireJson.encodeToString(toolCall(5, "{\"a\""))).accepted)
        assertEquals("append", patch(received.last()).eventPatch?.kind)
        assertTrue(store.liveEventReceived(wireJson.encodeToString(toolCall(5, ":1}"))).accepted)
        forwarded = patch(received.last()).eventPatch!!
        assertTrue(forwarded.streamDelta)
        assertEquals(":1}", forwarded.record?.event?.tool?.argumentsDelta)
    }

    @Test
    fun nonDeltaChunksAndUnknownChunkTypesAreForwardedAsStreamDeltasNotDropped() {
        val store = SharedHistoryStore()
        val received = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(received::add))

        assertTrue(store.liveEventReceived(wireJson.encodeToString(chunk(9, "x"))).accepted)
        val before = received.size

        // `usage`/`finish`/`block-start`/`block-end` carry no display text but
        // do carry correlation and accounting, so they must be routed like any
        // other same-sequence chunk. An unknown chunk type must travel the same
        // way rather than being dropped to make a test pass.
        listOf("usage", "finish", "block-start", "block-end", "some-future-chunk-type").forEach { type ->
            assertTrue(
                store.liveEventReceived(wireJson.encodeToString(rawChunk(9, type))).accepted,
                "chunk type $type was rejected"
            )
            val forwarded = patch(received.last()).eventPatch!!
            assertTrue(forwarded.streamDelta, "chunk type $type did not take the stream-delta lane")
            assertEquals(type, forwarded.record?.event?.chunkType)
        }
        assertTrue(received.size > before)
    }

    @Test
    fun outOfOrderGapFillIsNotAStreamDelta() {
        val store = SharedHistoryStore()
        val received = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(received::add))

        store.liveEventReceived(wireJson.encodeToString(chunk(5, "five")))
        // A chunk for an EARLIER watermark is a gap fill, not a delta: it inserts
        // at a new position and must keep the rebaseline path.
        store.liveEventReceived(wireJson.encodeToString(chunk(2, "two")))
        val gapFill = patch(received.last()).eventPatch!!
        assertEquals("upsert", gapFill.kind)
        assertFalse(gapFill.streamDelta)
        assertEquals(0, gapFill.index)
    }

    @Test
    fun chunkArrivingAfterAnAuthoritativeMessageIsNotAStreamDelta() {
        val store = SharedHistoryStore()
        val received = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(received::add))

        assertTrue(store.liveEventReceived(wireJson.encodeToString(event(6, "done"))).accepted)
        // A late or reordered chunk supersedes an authoritative record, not a
        // transient one, so it must not be folded into the finalized row.
        assertTrue(store.liveEventReceived(wireJson.encodeToString(chunk(6, "late"))).accepted)
        val forwarded = patch(received.last()).eventPatch!!
        assertEquals("upsert", forwarded.kind)
        assertFalse(forwarded.streamDelta, "a chunk must not extend an authoritative message")
    }

    @Test
    fun sameSequenceChunkFromAnotherTurnOrStepIsNotAStreamDelta() {
        val store = SharedHistoryStore()
        val received = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(received::add))

        assertTrue(
            store.liveEventReceived(wireJson.encodeToString(chunk(3, "first", turn = 1, step = 1))).accepted
        )
        assertTrue(
            store.liveEventReceived(wireJson.encodeToString(chunk(3, "second", turn = 1, step = 2))).accepted
        )
        var forwarded = patch(received.last()).eventPatch!!
        assertEquals("upsert", forwarded.kind)
        assertFalse(forwarded.streamDelta, "a chunk from another step is not a continuation")

        assertTrue(
            store.liveEventReceived(wireJson.encodeToString(chunk(3, "third", turn = 2, step = 2))).accepted
        )
        forwarded = patch(received.last()).eventPatch!!
        assertFalse(forwarded.streamDelta, "a chunk from another turn is not a continuation")
    }

    private fun patch(event: SharedMviEvent): SharedHistoryPatch =
        wireJson.decodeFromString(event.statePayloadJson!!)

    /**
     * The retained journal as the store would hand it to a new subscriber. A
     * fresh subscription bootstraps the current `eventsBySession`, which is how
     * the retained record is observable from the outside. It is one record per
     * `seq` — not a stream log — so it must never be read as accumulated text.
     */
    private fun retained(store: SharedHistoryStore, sessionId: String): List<SessionEvent> {
        val bootstrap = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(bootstrap::add))
        return wireJson.decodeFromString<SharedHistoryBootstrap>(bootstrap.single().statePayloadJson!!)
            .eventsBySession[sessionId].orEmpty()
    }

    private fun effects(event: SharedMviEvent): List<SharedHistoryEffect> =
        wireJson.decodeFromString(event.effectsJson)

    private fun event(sequence: Int, text: String) = SessionEvent(
        sessionId = "s1",
        seq = sequence,
        time = sequence.toDouble(),
        event = GatewayEvent(type = "assistant/message", text = text)
    )

    private fun chunk(sequence: Int, text: String, turn: Int = 1, step: Int = 1) = SessionEvent(
        sessionId = "s1",
        seq = sequence,
        time = sequence.toDouble(),
        event = GatewayEvent(
            type = "assistant/chunk",
            turn = turn,
            step = step,
            chunkType = "text-delta",
            text = text
        )
    )

    private fun reasoning(sequence: Int, text: String) = SessionEvent(
        sessionId = "s1",
        seq = sequence,
        time = sequence.toDouble(),
        event = GatewayEvent(
            type = "assistant/chunk",
            turn = 1,
            step = 1,
            chunkType = "reasoning-delta",
            text = text
        )
    )

    private fun toolCall(sequence: Int, argumentsDelta: String) = SessionEvent(
        sessionId = "s1",
        seq = sequence,
        time = sequence.toDouble(),
        event = GatewayEvent(
            type = "assistant/chunk",
            turn = 1,
            step = 1,
            chunkType = "tool-call-delta",
            tool = ToolDelta(id = "call-1", name = "run_code", argumentsDelta = argumentsDelta)
        )
    )

    private fun rawChunk(sequence: Int, chunkType: String) = SessionEvent(
        sessionId = "s1",
        seq = sequence,
        time = sequence.toDouble(),
        event = GatewayEvent(type = "assistant/chunk", turn = 1, step = 1, chunkType = chunkType)
    )
}
