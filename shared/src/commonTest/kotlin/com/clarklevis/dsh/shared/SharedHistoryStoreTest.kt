package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.facade.SharedHistoryBootstrap
import com.clarklevis.dsh.shared.facade.SharedHistoryEffect
import com.clarklevis.dsh.shared.facade.SharedHistoryPatch
import com.clarklevis.dsh.shared.facade.SharedHistoryStore
import com.clarklevis.dsh.shared.facade.SharedMviEvent
import com.clarklevis.dsh.shared.facade.SharedMviEventObserver
import com.clarklevis.dsh.shared.protocol.GatewayEvent
import com.clarklevis.dsh.shared.protocol.SessionEvent
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

    private fun patch(event: SharedMviEvent): SharedHistoryPatch =
        wireJson.decodeFromString(event.statePayloadJson!!)

    private fun effects(event: SharedMviEvent): List<SharedHistoryEffect> =
        wireJson.decodeFromString(event.effectsJson)

    private fun event(sequence: Int, text: String) = SessionEvent(
        sessionId = "s1",
        seq = sequence,
        time = sequence.toDouble(),
        event = GatewayEvent(type = "assistant/message", text = text)
    )
}
