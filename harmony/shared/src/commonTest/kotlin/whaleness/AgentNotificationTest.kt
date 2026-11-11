package whaleness

import com.clarklevis.dsh.shared.facade.SharedAgentNotificationStore
import com.clarklevis.dsh.shared.protocol.GatewayFrame
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentNotificationTest {
    private val store = SharedAgentNotificationStore()

    private fun read(json: String) = wireJson.decodeFromString<GatewayFrame>(json)

    @Test fun onlyConfirmedPendingInteractionCreatesNoticeAndReplayIsIgnored() {
        val frame = read("""{"kind":"approval-requested","sessionId":"a","rpcId":"r"}""")
        assertNull(store.accept(frame, emptySet(), emptySet()))
        assertEquals("approval", store.accept(frame, emptySet(), setOf("r"))?.kind)
        assertNull(store.accept(frame, emptySet(), setOf("r")))
        assertEquals("approval", store.accept(frame.copy(sessionId = "b"), emptySet(), setOf("r"))?.kind)
    }

    @Test fun completionUsesStableSequenceAndFailureReasonWithoutContent() {
        val frame = read("""{"kind":"event","sessionId":"a","seq":15,"time":10,"event":{"type":"turn/end","reason":"cancelled"}}""")
        assertTrue(store.accept(frame, emptySet(), emptySet())!!.failed)
        assertNull(store.accept(frame, emptySet(), emptySet()))
        assertFalse(store.accept(frame.copy(seq = 16, event = frame.event!!.copy(reason = "complete")), emptySet(), emptySet())!!.failed)
    }

    @Test fun coldHistoryAndUnsequencedFramesNeverCreateNotifications() {
        val frame = read("""{"kind":"event","sessionId":"a","event":{"type":"turn/end"}}""")
        assertNull(store.accept(frame, emptySet(), emptySet()))
        assertNull(store.accept(frame.copy(kind = "session-snapshot", seq = 1, time = 1.0), emptySet(), emptySet()))
    }
}
