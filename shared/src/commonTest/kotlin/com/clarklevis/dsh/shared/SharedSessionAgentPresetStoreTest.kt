package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.facade.SharedSessionAgentPresetStore
import com.clarklevis.dsh.shared.protocol.GatewayFrame
import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedSessionAgentPresetStoreTest {
    private fun ready(store: SharedSessionAgentPresetStore = SharedSessionAgentPresetStore(), id: String = "s1"): SharedSessionAgentPresetStore {
        val request = store.open(id, true, true).requests.first()
        store.acceptJson("""{"kind":"agent-presets","presets":[{"id":"standard","isDefault":true},{"id":"minimal"},{"id":"broken","broken":"缺少插件"}]}""")
        store.accept(GatewayFrame(kind = "session-agent-preset", sessionId = id, requestId = request.correlationId, agentPreset = "standard", locked = false))
        return store
    }

    @Test fun repeatedSwitchesKeepSessionAndWaitForAcknowledgement() {
        val store = ready()
        for (preset in listOf("minimal", "standard")) {
            val before = store.snapshot().agentPreset
            val request = store.select(preset).requests.single()
            assertEquals("s1", request.targetSessionId)
            assertEquals(before, store.snapshot().agentPreset)
            assertTrue(store.snapshot().blocksSending)
            assertTrue(store.select(preset).requests.isEmpty())
            store.accept(GatewayFrame(kind = "select-agent-preset", sessionId = "s1", requestId = request.correlationId, agentPreset = preset))
            assertEquals(preset, store.snapshot().agentPreset)
            assertTrue(store.snapshot().canSelect)
        }
    }

    @Test fun commandSubmissionRechecksWhetherItStartedAConversation() {
        val store = ready()
        store.beginMessage()
        assertFalse(store.snapshot().canSelect)
        val query = store.accept(GatewayFrame(kind = "command-executed", sessionId = "s1")).requests.single()
        store.accept(GatewayFrame(kind = "session-agent-preset", sessionId = "s1", requestId = query.correlationId, agentPreset = "standard", locked = false))
        assertTrue(store.snapshot().canSelect)
        store.beginMessage()
        val recovery = store.requestFailed("command-execute", "s1", null, "timeout")
        assertEquals("session-agent-preset", recovery.requests.single().requestType)
        assertFalse(store.snapshot().canSelect)
    }

    @Test fun delayedQueryAndSelectionCannotUndoLock() {
        val store = ready()
        val query = store.query().requests.single()
        store.acceptJson("""{"kind":"session-agent-preset-updated","sessionId":"s1","locked":true,"seq":13}""")
        store.accept(GatewayFrame(kind = "session-agent-preset", sessionId = "s1", requestId = query.correlationId, agentPreset = "standard", locked = false))
        assertTrue(store.snapshot().locked)
        assertFalse(store.snapshot().visible)
        val other = ready()
        val selection = other.select("minimal").requests.single()
        other.acceptJson("""{"kind":"session-agent-preset-updated","sessionId":"s1","locked":true}""")
        other.accept(GatewayFrame(kind = "select-agent-preset", sessionId = "s1", requestId = selection.correlationId, agentPreset = "minimal"))
        assertTrue(other.snapshot().locked)
        assertFalse(other.snapshot().canSelect)
    }

    @Test fun acceptedMessageStaysLockedAfterStopFinishReconnectAndReentry() {
        val store = ready()
        store.beginMessage()
        assertFalse(store.snapshot().visible)
        assertFalse(store.snapshot().canSelect)
        store.accept(GatewayFrame(kind = "sent", sessionId = "s1"))
        store.acceptJson("""{"kind":"session-cancelled","sessionId":"s1","accepted":true}""")
        store.acceptJson("""{"kind":"event","sessionId":"s1","event":{"type":"turn/end"}}""")
        store.disconnected()
        store.leave()
        val query = store.open("s1", true, true).requests.first()
        store.accept(GatewayFrame(kind = "session-agent-preset", sessionId = "s1", requestId = query.correlationId, agentPreset = "standard", locked = false))
        assertTrue(store.snapshot().locked)
        assertFalse(store.snapshot().blocksSending)
    }

    @Test fun messageFailureRequiresFreshQueryBeforeUnlocking() {
        val store = ready()
        val oldQuery = store.query().requests.single()
        // 查询未完成时不能发送，先完成该次查询。
        store.accept(GatewayFrame(kind = "session-agent-preset", sessionId = "s1", requestId = oldQuery.correlationId, agentPreset = "standard", locked = false))
        store.beginMessage()
        val recovery = store.requestFailed("message", "s1", null, "network").requests.single()
        assertFalse(store.snapshot().canSelect)
        store.accept(GatewayFrame(kind = "session-agent-preset", sessionId = "s1", requestId = oldQuery.correlationId, agentPreset = "standard", locked = false))
        assertFalse(store.snapshot().canSelect)
        store.accept(GatewayFrame(kind = "session-agent-preset", sessionId = "s1", requestId = recovery.correlationId, agentPreset = "standard", locked = false))
        assertTrue(store.snapshot().canSelect)
    }

    @Test fun refreshDuringSendDoesNotReopenSelection() {
        val store = ready()
        store.beginMessage()
        assertTrue(store.open("s1", true, true).requests.isEmpty())
        assertTrue(store.snapshot().submitting)
        store.disconnected()
        assertTrue(store.open("s1", true, true).requests.isNotEmpty())
    }

    @Test fun lateResponsesCannotChangeAnotherPageOrAnotherGateway() {
        val store = ready()
        val old = store.select("minimal").requests.single()
        ready(store, "s2")
        store.accept(GatewayFrame(kind = "select-agent-preset", sessionId = "s1", requestId = old.correlationId, agentPreset = "minimal"))
        assertEquals("s2", store.snapshot().sessionId)
        assertEquals("standard", store.snapshot().agentPreset)
        val otherGateway = ready()
        store.accept(GatewayFrame(kind = "session-agent-preset-updated", sessionId = "s1", locked = true))
        assertFalse(otherGateway.snapshot().locked)
        store.leave()
        assertFalse(store.snapshot().visible)
    }

    @Test fun newestQueryWinsAndNotificationWinsOverEarlierQuery() {
        val store = ready()
        val old = store.query().requests.single()
        val latest = store.query().requests.single()
        store.accept(GatewayFrame(kind = "session-agent-preset", sessionId = "s1", requestId = old.correlationId, agentPreset = "wrong", locked = false))
        assertTrue(store.snapshot().loading)
        val update = store.acceptJson("""{"kind":"session-agent-preset-updated","sessionId":"s1","agentPreset":"minimal","seq":22}""")
        assertTrue(update.refreshCommands)
        store.accept(GatewayFrame(kind = "session-agent-preset", sessionId = "s1", requestId = latest.correlationId, agentPreset = "standard", locked = false))
        assertEquals("minimal", store.snapshot().agentPreset)
        store.acceptJson("""{"kind":"session-agent-preset-updated","sessionId":"s1","agentPreset":"standard","seq":21}""")
        assertEquals("minimal", store.snapshot().agentPreset)
    }

    @Test fun unavailableAndTimedOutStateRemainDisabledAndCanBeRetried() {
        val store = ready()
        val request = store.query().requests.single()
        store.accept(GatewayFrame(kind = "error", sessionId = "s1", requestId = request.correlationId, requestType = "session-agent-preset", code = "agent-preset/unavailable"))
        assertFalse(store.snapshot().canSelect)
        assertTrue(store.snapshot().blocksSending)
        val retry = store.query().requests.single()
        store.requestFailed("session-agent-preset", "s1", retry.correlationId, "timeout")
        assertFalse(store.snapshot().loading)
        assertFalse(store.snapshot().canSelect)
        assertTrue(store.query().requests.isNotEmpty())
    }

    @Test fun selectionErrorsPreserveOriginalAndRefreshCatalog() {
        for (code in listOf("agent-preset/not-found", "agent-preset/invalid")) {
            val store = ready()
            val request = store.select("minimal").requests.single()
            val failed = store.accept(GatewayFrame(kind = "error", sessionId = "s1", requestId = request.correlationId, requestType = "select-agent-preset", code = code, message = "不可用"))
            assertEquals("standard", store.snapshot().agentPreset)
            assertEquals("agent-presets", failed.requests.single().requestType)
            assertEquals("不可用", failed.error)
            assertTrue(store.snapshot().canSelect)
        }
    }

    @Test fun selectionTimeoutQueriesInsteadOfRepeatingMutation() {
        val store = ready()
        val request = store.select("minimal").requests.single()
        val recovery = store.requestFailed("select-agent-preset", "s1", request.correlationId, "timeout")
        assertEquals("session-agent-preset", recovery.requests.single().requestType)
        assertEquals("standard", store.snapshot().agentPreset)
        assertTrue(store.snapshot().blocksSending)
        store.accept(GatewayFrame(kind = "select-agent-preset", sessionId = "s1", requestId = request.correlationId, agentPreset = "minimal"))
        assertEquals("standard", store.snapshot().agentPreset)
        val query = recovery.requests.single()
        val restored = store.accept(GatewayFrame(kind = "session-agent-preset", sessionId = "s1", requestId = query.correlationId, agentPreset = "minimal", locked = false))
        assertTrue(restored.refreshCommands)
        assertEquals("minimal", restored.snapshot.agentPreset)
    }

    @Test fun lockedErrorHidesAndMissingSessionExits() {
        for (code in listOf("agent-preset/locked", "session/not-found")) {
            val store = ready()
            val request = store.select("minimal").requests.single()
            val failed = store.accept(GatewayFrame(kind = "error", sessionId = "s1", requestId = request.correlationId, requestType = "select-agent-preset", code = code))
            assertFalse(store.snapshot().visible)
            assertEquals(code == "session/not-found", failed.invalidSession)
        }
    }

    @Test fun hostSettingAndOldGatewayHideEntryWithoutChangingLock() {
        val store = ready()
        store.acceptJson("""{"kind":"agent-presets","presets":[],"modeSelectionEnabled":false}""")
        assertFalse(store.snapshot().visible)
        assertFalse(store.snapshot().locked)
        val unsupported = store.open("s1", false, true)
        assertTrue(unsupported.requests.isEmpty())
        assertFalse(unsupported.snapshot.blocksSending)
    }

    @Test fun brokenReasonAndMissingDefaultDecodeAndCannotBeSelected() {
        val store = ready()
        val preset = store.snapshot().presets.last()
        assertEquals(true, preset.broken)
        assertEquals("缺少插件", preset.brokenReason)
        assertFalse(preset.isDefault)
        assertTrue(store.select("broken").requests.isEmpty())
        val frame = GatewayWireDecoder.decode("""{"kind":"session-agent-preset-updated","sessionId":"s1","locked":true}""")
        assertEquals(true, frame.locked)
    }

    @Test fun currentProjectionDoesNotUseGlobalDefaultOrUnlockSession() {
        val store = SharedSessionAgentPresetStore()
        val request = store.open("s1", true, true).requests.first()
        store.acceptJson("""{"kind":"history","sessionId":"s1","projections":{"asOfSeq":25,"values":{"agentPreset":"minimal"}}}""")
        assertEquals("minimal", store.snapshot().agentPreset)
        store.acceptJson("""{"kind":"agent-presets","presets":[{"id":"standard","isDefault":true}]}""")
        assertEquals("minimal", store.snapshot().agentPreset)
        store.accept(GatewayFrame(kind = "session-agent-preset", sessionId = "s1", requestId = request.correlationId, agentPreset = "standard", locked = false))
        assertEquals("standard", store.snapshot().agentPreset)
        store.acceptJson("""{"kind":"history","sessionId":"s1","projections":{"asOfSeq":26,"values":{"agentPreset":"minimal"}}}""")
        assertEquals("standard", store.snapshot().agentPreset)
    }
}
