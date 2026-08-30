package com.clarklevis.dsh.android

import com.clarklevis.dsh.shared.projection.TrajectoryNodeKind
import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidTrajectoryRealtimeProjectionTest {
    @Test
    fun streamingFramesContinuouslyUpdateTrajectoryBeforeFinalMessage() {
        val projection = AndroidGatewayProjection()
        projection.selectSession("session-a")

        projection.accept(frame(1, "assistant/chunk", "A", "text-delta"))
        val first = projection.trajectory("session-a")
        assertEquals("A", first.single { it.kind == TrajectoryNodeKind.ASSISTANT }.subtitle)
        assertTrue(first.any { it.kind == TrajectoryNodeKind.REQUEST })

        projection.accept(frame(2, "assistant/chunk", "B", "text-delta"))
        val second = projection.trajectory("session-a")
        assertEquals("AB", second.single { it.kind == TrajectoryNodeKind.ASSISTANT }.subtitle)
        assertTrue(second.single { it.kind == TrajectoryNodeKind.ASSISTANT }.endSequence > first.single {
            it.kind == TrajectoryNodeKind.ASSISTANT
        }.endSequence)

        projection.accept(frame(3, "assistant/message", "完成", null))
        val completed = projection.trajectory("session-a")
        assertEquals("完成", completed.single { it.kind == TrajectoryNodeKind.ASSISTANT }.subtitle)
        assertTrue(completed.none { it.id.startsWith("assistant-stream-") })
        projection.close()
    }

    private fun AndroidGatewayProjection.accept(raw: String) {
        acceptFrame(raw, GatewayWireDecoder.decode(raw), "session-a")
    }

    private fun frame(sequence: Int, type: String, text: String, chunkType: String?): String =
        if (chunkType == null) {
            """{"sessionId":"session-a","seq":$sequence,"time":${100 + sequence},"event":{"type":"$type","turn":1,"step":1,"text":"$text"}}"""
        } else {
            """{"sessionId":"session-a","seq":$sequence,"time":${100 + sequence},"event":{"type":"$type","turn":1,"step":1,"chunkType":"$chunkType","text":"$text"}}"""
        }
}
