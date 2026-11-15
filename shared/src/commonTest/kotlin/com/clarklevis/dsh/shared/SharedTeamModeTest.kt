package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.facade.SharedTeamStore
import com.clarklevis.dsh.shared.facade.TeamModeCommand
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlin.test.*

class SharedTeamModeTest {
    private fun store(enabled: Boolean = false, used: Boolean = false): SharedTeamStore = SharedTeamStore().also {
        it.acceptFrameJson("""{"kind":"hello","capabilities":["agent-team-mode-v1"]}""")
        it.acceptFrameJson("""{"kind":"session-snapshot","sessionId":"lead","projections":{"asOfSeq":10,"values":{"agentTeam":{"enabled":$enabled,"tasks":[]},"modelSelection":{"lastUsed":${if (used) "{}" else "null"}}}}}""")
    }
    private fun begin(s: SharedTeamStore, enabled: Boolean, confirmed: Boolean = false): TeamModeCommand =
        wireJson.decodeFromString(requireNotNull(s.setEnabledRequestJson("lead", enabled, confirmed, true)))
    private fun ack(s: SharedTeamStore, command: TeamModeCommand, enabled: Boolean, seq: Int = 11): Boolean =
        s.acceptFrameJson("""{"kind":"agent-team-mode-set","sessionId":"lead","requestId":"${command.requestId}","agentTeam":{"enabled":$enabled,"tasks":[]},"asOfSeq":$seq,"projectionState":"ready"}""")

    @Test fun changeWaitsForCorrelatedDurableProjection() {
        val s = store()
        assertTrue(s.modeSnapshot("lead").canChange)
        val command = begin(s, true)
        assertEquals(false, s.modeSnapshot("lead").enabled)
        assertTrue(s.modeSnapshot("lead").pending)
        assertNull(s.setEnabledRequestJson("lead", true, false, true))
        assertFalse(s.acceptFrameJson("""{"kind":"agent-team-mode-set","sessionId":"lead","requestId":"wrong","agentTeam":{"enabled":true},"asOfSeq":11,"projectionState":"ready"}"""))
        assertTrue(ack(s, command, true))
        assertEquals(true, s.modeSnapshot("lead").enabled)
        assertFalse(s.modeSnapshot("lead").pending)
    }
    @Test fun disablingUsedModelRequiresExplicitConfirmation() {
        val s = store(true, true)
        assertTrue(s.modeSnapshot("lead").requiresDisableConfirmation)
        assertNull(s.setEnabledRequestJson("lead", false, false, true))
        assertTrue(ack(s, begin(s, false, true), false))
        assertEquals(false, s.modeSnapshot("lead").enabled)
    }
    @Test fun unavailableOldGatewayChildAndDisconnectedCannotWrite() {
        val s = store()
        assertNull(s.setEnabledRequestJson("lead", true, false, false))
        s.acceptFrameJson("""{"kind":"subagent-identity-updated","sessionId":"child","asOfSeq":11,"subagent":{"parentSessionId":"lead","name":"reviewer"}}""")
        assertFalse(s.modeSnapshot("child").supported)
        assertNull(s.setEnabledRequestJson("child", true, false, true))
        s.acceptFrameJson("""{"kind":"hello","capabilities":[]}""")
        assertFalse(s.modeSnapshot("lead").supported)
        assertNull(s.setEnabledRequestJson("lead", true, false, true))
    }
    @Test fun disconnectDoesNotReplayOrTrustLateReply() {
        val s = store()
        val command = begin(s, true)
        s.modeConnectionLost()
        assertNotNull(s.modeSnapshot("lead").error)
        assertFalse(ack(s, command, true))
        s.acceptFrameJson("""{"kind":"hello","capabilities":["agent-team-mode-v1"]}""")
        assertFalse(s.modeSnapshot("lead").canChange)
        assertNull(s.setEnabledRequestJson("lead", true, false, true))
    }
    @Test fun failedFlushDoesNotInventRollbackAndRefreshCanResolveIt() {
        val s = store()
        val command = begin(s, true)
        s.acceptFrameJson("""{"kind":"agent-team-updated","sessionId":"lead","asOfSeq":11,"agentTeam":{"enabled":true,"tasks":[]}}""")
        assertTrue(s.modeRequestFailed("lead", command.requestId, "flush failed"))
        assertEquals(true, s.modeSnapshot("lead").enabled)
        assertFalse(s.modeSnapshot("lead").pending)
        assertNotNull(s.modeSnapshot("lead").error)
        assertFalse(ack(s, command, false))
    }
    @Test fun newerExternalProjectionWinsOverOldAcknowledgement() {
        val s = store()
        val command = begin(s, true)
        s.acceptFrameJson("""{"kind":"agent-team-updated","sessionId":"lead","asOfSeq":13,"agentTeam":{"enabled":false,"tasks":[]}}""")
        assertTrue(ack(s, command, true, 12))
        assertEquals(false, s.modeSnapshot("lead").enabled)
        assertFalse(s.modeSnapshot("lead").pending)
    }
    @Test fun incompleteSuccessIsUnconfirmed() {
        val s = store()
        val command = begin(s, true)
        assertTrue(s.acceptFrameJson("""{"kind":"agent-team-mode-set","sessionId":"lead","requestId":"${command.requestId}","agentTeam":{"enabled":true}}"""))
        assertEquals(false, s.modeSnapshot("lead").enabled)
        assertNotNull(s.modeSnapshot("lead").error)
    }
}
