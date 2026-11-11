package com.clarklevis.dsh.android

import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAgentNotificationsTest {
    @Test
    fun backgroundRemindersIncludePendingQuestionsAndApprovalsAcrossSessions() {
        val projection = AndroidGatewayProjection()
        accept(projection,
            """{"kind":"question-requested","rpcId":"q1","sessionId":"s1","questions":[{"id":"q","question":"继续？"}]}""")
        accept(projection,
            """{"kind":"approval-requested","rpcId":"a1","sessionId":"s2","approvalId":"a","toolName":"Bash","replay":true}""")

        val frames = pendingAgentNotificationFrames(projection.snapshot())
        assertEquals(setOf("question-requested", "approval-requested"), frames.map { it.kind }.toSet())
        assertEquals(setOf("q1", "a1"), frames.map { it.rpcId }.toSet())
        assertEquals(setOf("s1", "s2"), frames.map { it.sessionId }.toSet())
    }

    @Test
    fun resolvedRequestsAndAcceptedApprovalsAreNotRemindedWhenLeavingForeground() {
        val projection = AndroidGatewayProjection()
        accept(projection,
            """{"kind":"question-requested","rpcId":"q1","sessionId":"s1","questions":[{"id":"q","question":"继续？"}]}""")
        accept(projection,
            """{"kind":"approval-requested","rpcId":"a1","sessionId":"s1","approvalId":"a","toolName":"Bash"}""")
        accept(projection,
            """{"kind":"question-resolved","rpcId":"q1","sessionId":"s1","outcome":"answered"}""")
        accept(projection,
            """{"kind":"approval-response","rpcId":"a1","outcome":"allowed-once","accepted":true}""")
        assertTrue(pendingAgentNotificationFrames(projection.snapshot()).isEmpty())
    }

    @Test
    fun replayedPendingRequestsDoNotMultiplyBackgroundReminderCandidates() {
        val projection = AndroidGatewayProjection()
        val raw = """{"kind":"question-requested","rpcId":"q1","sessionId":"s1","replay":true,"questions":[{"id":"q","question":"继续？"}]}"""
        repeat(3) { accept(projection, raw) }
        assertEquals(1, pendingAgentNotificationFrames(projection.snapshot()).size)
    }

    @Test
    fun notificationHistorySurvivesRestartAndBoundsItsStorage() {
        val history = AgentNotificationHistory(emptyList(), maximumCount = 2)
        history.remember("question:gateway:q1")
        history.remember("approval:gateway:a1")
        history.remember("approval:gateway:a1")
        val restored = AgentNotificationHistory(history.identifiers, maximumCount = 2)
        assertTrue(restored.contains("question:gateway:q1"))
        assertTrue(restored.contains("approval:gateway:a1"))
        restored.remember("question:gateway:q2")
        assertFalse(restored.contains("question:gateway:q1"))
        assertEquals(listOf("approval:gateway:a1", "question:gateway:q2"), restored.identifiers)
    }

    private fun accept(projection: AndroidGatewayProjection, raw: String) {
        projection.acceptFrame(raw, GatewayWireDecoder.decode(raw), null)
    }
}
