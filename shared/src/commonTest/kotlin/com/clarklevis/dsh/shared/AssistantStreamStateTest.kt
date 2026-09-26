package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.gateway.GatewayRequests
import com.clarklevis.dsh.shared.facade.SharedMobileStore
import com.clarklevis.dsh.shared.projection.ConversationProjector
import com.clarklevis.dsh.shared.protocol.GatewayEvent
import com.clarklevis.dsh.shared.protocol.SessionEvent
import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder
import com.clarklevis.dsh.shared.protocol.wireJson
import com.clarklevis.dsh.shared.sync.AssistantChunk
import com.clarklevis.dsh.shared.sync.AssistantStreamState
import kotlinx.serialization.decodeFromString
import kotlin.test.*

class AssistantStreamStateTest {
    @Test
    fun openingFailureIsDeliveredWithoutBaselineAndOldSubscriptionRemainsIgnored() {
        val state = subscribed()
        val failure = """{"kind":"session-stream-reset","sessionId":"s","subscriptionId":"sub","streamId":"opening","retrying":false,"message":"Host refuses this format v0 Session"}"""
        assertFalse(state.acceptJson(failure.replace("\"sub\"", "\"old\"")).accepted)
        val result = state.acceptJson(failure)
        assertTrue(result.accepted)
        assertEquals("Host refuses this format v0 Session", result.error)
        assertFalse(state.hasBaseline("s"))
        assertTrue(state.acceptJson(snapshot()).accepted)
        assertFalse(state.acceptJson(failure).accepted)
        assertTrue(state.hasBaseline("s"))
    }

    @Test
    fun prefixAndDeltasUseAttemptIdentityAndNeverAdvancePersistentCursor() {
        val state = subscribed()
        val baseline = state.acceptJson(snapshot())
        val projector = ConversationProjector()
        projector.rebuild(listOf(SessionEvent("s", 40, 100.0, GatewayEvent("user/message", text = "Hi"))))
        projector.foldAssistantChunks("s:1", decode(baseline.chunksJson))
        val delta = state.acceptJson(chunk())
        projector.foldAssistantChunks("s:1", decode(delta.chunksJson))
        assertEquals("Hello world", projector.items.last().text)
        assertEquals(41, state.persistentCursor)
        assertEquals(40, projector.lastSequence)
        assertFalse(state.acceptJson(chunk()).accepted)
        assertEquals(2, decode(state.replayChunksJson()).size)
        val final = state.acceptJson(event())
        assertTrue(final.accepted)
        assertTrue(final.clearTransient)
        projector.clearAssistantChunks()
        projector.fold(listOf(SessionEvent("s", 42, 102.0, GatewayEvent("assistant/message", 2, 3, "Hello world"))))
        assertEquals(2, projector.items.size)
        assertEquals("[]", state.replayChunksJson())
        assertFalse(state.acceptJson(event()).accepted)
        assertTrue(state.acceptJson(end()).clearTransient)
    }

    @Test
    fun resetFreezesStreamAndNewSnapshotReplacesCursorAndPrefix() {
        val state = subscribed()
        state.acceptJson(snapshot())
        val reset = state.acceptJson("""{"kind":"session-stream-reset","sessionId":"s","subscriptionId":"sub","streamId":"stream","retrying":true}""")
        assertTrue(reset.clearTransient)
        assertFalse(state.acceptJson(chunk()).accepted)
        state.acceptJson(snapshot("new", 20))
        assertEquals(20, state.persistentCursor)
        assertFalse(state.acceptJson(event()).accepted)
        assertFalse(state.acceptJson(chunk()).accepted)
        assertTrue(state.acceptJson(chunk().replace("\"stream\"", "\"new\"")).accepted)
    }

    @Test
    fun gapsRequestFreshSubscriptionAndPermanentResetSurfacesError() {
        val state = subscribed()
        state.acceptJson(snapshot())
        assertTrue(state.acceptJson(chunk().replace("\"revision\":3", "\"revision\":5")).resubscribe)
        assertFalse(state.acceptJson(event()).accepted)
        state.acceptJson(snapshot())
        assertTrue(state.acceptJson(chunk().replace("\"index\":1", "\"index\":9")).resubscribe)
        state.acceptJson(snapshot())
        val reset = state.acceptJson("""{"kind":"session-stream-reset","sessionId":"s","subscriptionId":"sub","streamId":"stream","retrying":false,"message":"Unsupported format"}""")
        assertEquals("Unsupported format", reset.error)
        assertFalse(reset.resubscribe)
    }

    @Test
    fun switchingAndDisconnectRejectLateSubscriptionsAndStreamFrames() {
        val state = subscribed()
        state.acceptJson(snapshot())
        state.selectSession("other")
        assertFalse(state.acceptJson(ack).accepted)
        assertFalse(state.acceptJson(snapshot()).accepted)
        assertFalse(state.acceptJson(chunk()).accepted)
        state.selectSession("s")
        state.acceptJson(ack)
        state.acceptJson(snapshot())
        state.disconnect()
        assertFalse(state.acceptJson(chunk()).accepted)
        assertEquals("[]", state.replayChunksJson())
    }

    @Test
    fun formatMismatchDiscardsCoordinatesInsteadOfRestampingThem() {
        val state = subscribed()
        state.acceptJson(snapshot())
        val failure = state.acceptJson("""{"kind":"error","sessionId":"s","code":"history-format-mismatch","resetRequired":true,"historyFormatVersion":4}""")
        assertEquals(listOf("s"), failure.invalidatedSessionIds)
        assertNull(state.formatVersion("s"))
        assertNull(state.persistentCursor)
        assertTrue(failure.resubscribe)
        assertFailsWith<IllegalArgumentException> { GatewayRequests.history("s", beforeSequence = 20) }
        assertTrue(GatewayRequests.history("s", beforeSequence = 20, historyFormatVersion = 3).payload.contains("\"historyFormatVersion\":3"))
        assertTrue(GatewayRequests.subscribe("s").payload.contains("\"assistantStream\":true"))
    }

    @Test
    fun v3CacheIsReplacedByV4Baseline() {
        val state = subscribed()
        state.acceptJson(snapshot())
        assertEquals(3, state.formatVersion("s"))
        val update = state.acceptJson("""{"kind":"hello","historyFormatVersion":4,"capabilities":["assistant-stream-v1"]}""")
        assertEquals(listOf("s"), update.invalidatedSessionIds)
        assertNull(state.persistentCursor)
        state.acceptJson(ack)
        assertTrue(state.acceptJson(snapshot().replace("\"historyFormatVersion\":3", "\"historyFormatVersion\":4")).accepted)
        assertEquals(4, state.formatVersion("s"))
        assertEquals(41, state.persistentCursor)
    }

    @Test
    fun unversionedHistoryInvalidatesCurrentCacheAndRequestsNewBaseline() {
        val state = subscribed()
        state.acceptJson(snapshot())
        val update = state.acceptJson("""{"kind":"history","sessionId":"s","events":[]}""")
        assertFalse(update.accepted)
        assertTrue(update.resubscribe)
        assertEquals(listOf("s"), update.invalidatedSessionIds)
        assertNull(state.formatVersion("s"))
        assertNull(state.persistentCursor)
    }

    @Test
    fun emptyAttemptMayEndWithoutCreatingDurableMessage() {
        val state = subscribed()
        state.acceptJson(snapshot().replace("\"activeAttempt\":", "\"unused\":"))
        assertEquals("[]", state.replayChunksJson())
        assertTrue(state.acceptJson("""{"kind":"assistant-stream","sessionId":"s","subscriptionId":"sub","streamId":"stream","frame":{"type":"start","attemptId":"s:2","revision":3,"turn":2,"step":3,"startedAfterSeq":41}}""").accepted)
        val end = end().replace("s:1", "s:2").replace("\"index\":2", "\"index\":0")
            .replace("{\"kind\":\"committed\",\"eventType\":\"assistant/message\",\"seq\":42}", "{\"kind\":\"abandoned\"}")
        assertTrue(state.acceptJson(end).accepted)
        assertNull(state.activeAttemptId())
        assertEquals(41, state.persistentCursor)
    }

    private fun subscribed() = AssistantStreamState().apply {
        selectSession("s")
        acceptJson("""{"kind":"hello","historyFormatVersion":3,"capabilities":["assistant-stream-v1"]}""")
        acceptJson(ack)
    }

    @Test
    fun compactPrefixPreservesMixedChunksAndUnknownPayloads() {
        val state = subscribed()
        val source = snapshot().replace("\"texts\":[\"Hello\"],\"dt\":[]", "\"texts\":[\"Hel\",\"lo\"],\"dt\":[7]")
            .replace("\"nextIndex\":1", "\"nextIndex\":2")
        val result = decode(state.acceptJson(source).chunksJson)
        assertEquals(listOf(100.0, 107.0), result.map { it.time })
        val usage = chunk().replace("\"index\":1", "\"index\":2")
            .replace("{\"type\":\"text-delta\",\"index\":0,\"text\":\" world\"}", "{\"type\":\"usage\",\"usage\":{\"outputTokens\":9}}")
        assertTrue(state.acceptJson(usage).accepted)
        assertTrue(state.replayChunksJson().contains("outputTokens"))
    }

    @Test
    fun committedEndWithoutDurableEventRequestsRecovery() {
        val state = subscribed()
        state.acceptJson(snapshot())
        state.acceptJson(chunk())
        assertTrue(state.acceptJson(end()).resubscribe)
        assertEquals("[]", state.replayChunksJson())
    }

    @Test
    fun controlBaselineReplacesMissingKeysAndMissingSessions() {
        val store = SharedMobileStore()
        store.selectSession("s")
        store.acceptFrame("""{"kind":"tasks-updated","sessionId":"s","asOfSeq":9,"todos":[{"content":"Old","status":"pending"}]}""")
        store.acceptFrame("""{"kind":"goal-updated","sessionId":"s","asOfSeq":9,"goal":{"goal":{"id":"g","revision":1,"objective":"Old","phase":"active"},"roundsStarted":1}}""")
        assertNotNull(store.snapshot().goalSnapshot?.goal)
        var snapshot = store.acceptFrame("""{"kind":"projection-baseline","projections":{"s":{"asOfSeq":10,"values":{"todos":[]}}}}""")
        assertTrue(snapshot.taskSnapshot?.tasks?.isEmpty() == true)
        assertNull(snapshot.goalSnapshot?.goal)
        snapshot = store.acceptFrame("""{"kind":"projection-baseline","projections":{}}""")
        assertNull(snapshot.goalSnapshot)
        assertNull(snapshot.taskSnapshot)
    }

    @Test
    fun historicalToolFailureAndInterruptedAssistantSurviveNormalization() {
        val frame = GatewayWireDecoder.decode("""{"kind":"history","events":[{"type":"tool/result","seq":1,"time":1,"data":{"error":false,"message":{"content":[{"type":"tool-result","isError":true,"content":[{"type":"text","text":"failed"}]}]}}},{"type":"assistant/message","seq":2,"time":2,"data":{"turn":1,"step":1,"interrupted":true,"usage":{"outputTokens":1},"message":{"content":[{"type":"text","text":"Partial"}]}}},{"type":"assistant/attempt","seq":3,"time":3,"data":{"turn":1,"step":2,"stream":[{"type":"text-chunks","time0":3,"index":0,"texts":["Attempt"],"dt":[]}]}}]}""")
        val records = frame.events.orEmpty().map { it.normalized("s") }
        assertTrue(records[0].event.isError == true)
        assertTrue(records[1].event.interrupted == true)
        assertNotNull(records[1].event.usage)
        assertNotNull(records[2].event.stream)
        val projector = ConversationProjector().apply { rebuild(records) }
        assertEquals("Attempt", projector.items.last().text)
        assertTrue(projector.items.last().isError)
    }

    @Test
    fun v4ToolResultKeepsTextAndFailure() {
        val frame = GatewayWireDecoder.decode("""{"kind":"history","historyFormatVersion":4,"events":[{"type":"tool/result","seq":1,"time":1,"data":{"turn":1,"step":0,"message":{"role":"tool","toolCallId":"call-1","source":{"kind":"tool","callId":"call-1"},"isError":true,"content":[{"type":"text","text":"permission denied"}]},"error":{"code":"permission-denied","reason":"rejected"}}}]}""")
        val result = frame.events.orEmpty().single().normalized("s").event
        assertEquals("call-1", result.callId)
        assertEquals("permission denied", result.preview)
        assertTrue(result.isError == true)
        assertEquals("rejected", result.raw?.get("error")?.get("reason")?.stringValue)
    }

    @Test
    fun defaultPermissionCatalogReachesMobileSnapshot() {
        val snapshot = SharedMobileStore().acceptFrame(
            """{"kind":"defaults","agentPresetDefault":"standard","permissionDefault":"ask","permissionDefaultOptions":[{"value":"ask","name":"Ask"},{"value":"code","name":"Code","description":"Code access"}]}"""
        )
        assertEquals("ask", snapshot.permissionDefault)
        assertEquals(listOf("ask", "code"), snapshot.permissionDefaultOptions.map { it.value })
        assertEquals("Code access", snapshot.permissionDefaultOptions.last().description)
    }

    private fun decode(json: String) = wireJson.decodeFromString<List<AssistantChunk>>(json)
    private val ack = """{"kind":"subscribed","sessionId":"s","subscriptionId":"sub","assistantStream":true}"""
    private fun snapshot(stream: String = "stream", cursor: Int = 41) =
        """{"kind":"session-snapshot","sessionId":"s","subscriptionId":"sub","streamId":"$stream","historyFormatVersion":3,"cursor":$cursor,"replace":true,"events":[],"assistantStream":{"revision":2,"activeAttempt":{"attemptId":"s:1","turn":2,"step":3,"startedAfterSeq":41,"nextIndex":1,"stream":[{"type":"text-chunks","index":0,"time0":100,"texts":["Hello"],"dt":[]}]}}}"""
    private fun chunk() = """{"kind":"assistant-stream","sessionId":"s","subscriptionId":"sub","streamId":"stream","frame":{"type":"chunk","attemptId":"s:1","revision":3,"index":1,"time":101,"turn":2,"step":3,"chunk":{"type":"text-delta","index":0,"text":" world"}}}"""
    private fun event() = """{"kind":"event","sessionId":"s","subscriptionId":"sub","streamId":"stream","seq":42,"time":102,"event":{"type":"assistant/message","turn":2,"step":3,"text":"Hello world"}}"""
    private fun end() = """{"kind":"assistant-stream","sessionId":"s","subscriptionId":"sub","streamId":"stream","frame":{"type":"end","attemptId":"s:1","revision":4,"index":2,"turn":2,"step":3,"outcome":{"kind":"committed","eventType":"assistant/message","seq":42}}}"""
}
