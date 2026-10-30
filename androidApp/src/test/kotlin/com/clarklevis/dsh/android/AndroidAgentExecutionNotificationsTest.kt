package com.clarklevis.dsh.android

import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAgentExecutionNotificationsTest {
    @Test
    fun liveCompletionDoesNotRequireTimestamp() {
        val tracker = AndroidAgentExecutionNotifications()
        val ended = accept(tracker, """{"kind":"event","sessionId":"s","seq":42,"event":{"type":"turn/end"}}""")
        assertEquals("42", ended?.eventId)
        assertFalse(requireNotNull(ended).failed)
    }

    @Test
    fun missingSequenceUsesTurnIdentityAndClassifiesFailure() {
        val tracker = AndroidAgentExecutionNotifications()
        val ended = accept(tracker, """{"kind":"event","sessionId":"s","event":{"type":"turn/end","turn":7,"reason":"error"}}""")
        assertEquals("turn-7", ended?.eventId)
        assertTrue(requireNotNull(ended).failed)
    }

    @Test
    fun minimalTerminalFrameEndsObservedRunOnlyOnceAndNextRunCanNotify() {
        val tracker = AndroidAgentExecutionNotifications()
        val sent = """{"kind":"sent","sessionId":"s"}"""
        val end = """{"kind":"event","sessionId":"s","event":{"type":"turn/end"}}"""
        accept(tracker, sent)
        val first = accept(tracker, end)
        assertNotNull(first)
        assertNull(accept(tracker, end))
        accept(tracker, sent)
        assertNotEquals(first?.eventId, accept(tracker, end)?.eventId)
    }

    @Test
    fun reconnectSnapshotCanCompletePreviouslyObservedRunningSession() {
        val tracker = AndroidAgentExecutionNotifications()
        tracker.accept(GatewayWireDecoder.decode("""{"kind":"sessions"}"""), setOf("s"))
        val ended = accept(tracker, completedSnapshot)
        assertEquals("42", ended?.eventId)
        assertNull(accept(tracker, completedSnapshot))
    }

    @Test
    fun openingOldCompletedSessionDoesNotSendCompletionNotification() {
        assertNull(accept(AndroidAgentExecutionNotifications(), completedSnapshot))
    }

    @Test
    fun oldCompletionCannotEndANewerRunningTurn() {
        val tracker = AndroidAgentExecutionNotifications()
        accept(tracker, """{"kind":"event","sessionId":"s","seq":43,"event":{"type":"turn/start","turn":3}}""")
        assertNull(accept(tracker, completedSnapshot))
        assertNull(accept(tracker, """{"kind":"event","sessionId":"s","seq":42,"event":{"type":"turn/end","turn":2}}"""))
        assertNotNull(accept(tracker, """{"kind":"event","sessionId":"s","seq":44,"event":{"type":"turn/end","turn":3}}"""))
    }

    @Test
    fun historicalCompletionWithActiveAttemptDoesNotEndCurrentTask() {
        val tracker = AndroidAgentExecutionNotifications()
        val active = completedSnapshot.dropLast(1) + """, "assistantStream":{"activeAttempt":{"attemptId":"current"}}}"""
        assertNull(accept(tracker, active))
        assertNull(accept(tracker, completedSnapshot))
        assertNotNull(accept(tracker, """{"kind":"event","sessionId":"s","seq":43,"event":{"type":"turn/end"}}"""))
    }

    private fun accept(tracker: AndroidAgentExecutionNotifications, raw: String): AndroidExecutionEndedNotification? =
        tracker.accept(GatewayWireDecoder.decode(raw), emptySet())

    private val completedSnapshot = """{"kind":"session-snapshot","sessionId":"s","cursor":42,"events":[{"type":"turn/end","seq":42,"time":100,"data":{"turn":2,"reason":"completed"}}]}"""
}
