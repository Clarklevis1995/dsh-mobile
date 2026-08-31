package com.clarklevis.dsh.android

import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidProjectionActorTest {
    @Test
    fun streamingChunksBatchByDisplayCadenceAndFlushBeforeFinalMessage() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val publications = mutableListOf<com.clarklevis.dsh.shared.facade.SharedMobileSnapshot>()
        var nowNanos = 1L
        val actor = AndroidProjectionActor(
            projection = AndroidGatewayProjection(),
            uiDispatcher = dispatcher,
            publish = { snapshot, _ -> publications += snapshot },
            nowNanos = { nowNanos }
        )
        actor.selectSession("session-a")

        fun chunk(sequence: Int, text: String): Pair<String, com.clarklevis.dsh.shared.protocol.GatewayFrame> {
            val raw =
                """{"sessionId":"session-a","seq":$sequence,"time":$sequence,"event":{"type":"assistant/chunk","turn":1,"step":1,"chunkType":"text-delta","text":"$text"}}"""
            return raw to GatewayWireDecoder.decode(raw)
        }

        val first = chunk(1, "a")
        assertTrue(actor.acceptStreamingFrame(first.first, first.second, "session-a"))
        repeat(10) { index ->
            val next = chunk(index + 2, "b")
            assertTrue(!actor.acceptStreamingFrame(next.first, next.second, "session-a"))
        }
        assertEquals("a", publications.last().conversation.single().text)

        nowNanos += 50_000_000L
        val cadence = chunk(12, "c")
        assertTrue(actor.acceptStreamingFrame(cadence.first, cadence.second, "session-a"))
        assertEquals("a" + "b".repeat(10) + "c", publications.last().conversation.single().text)

        val expected = "a" + "b".repeat(10) + "c"
        val finalRaw =
            """{"sessionId":"session-a","seq":13,"time":13,"event":{"type":"assistant/message","turn":1,"step":1,"text":"$expected"}}"""
        actor.acceptFrame(finalRaw, GatewayWireDecoder.decode(finalRaw), "session-a")
        assertEquals(expected, publications.last().conversation.single().text)
        assertTrue(publications.last().conversation.none { it.id.startsWith("stream-") })
        actor.close()
    }

    @Test
    fun frameThenSelectPublishesInOrderAndOldFrameCannotOverwriteSelection() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val publishedSelections = mutableListOf<String?>()
        val actor = AndroidProjectionActor(
            projection = AndroidGatewayProjection(),
            uiDispatcher = dispatcher,
            publish = { snapshot, _ -> publishedSelections += snapshot.selectedSessionId }
        )
        val sessions =
            """{"kind":"sessions","items":[{"sessionId":"session-a","updatedAt":1,"running":false,"blank":false},{"sessionId":"session-b","updatedAt":2,"running":false,"blank":false}]}"""
        actor.acceptFrame(sessions, GatewayWireDecoder.decode(sessions), null)
        actor.selectSession("session-a")

        val frame =
            """{"sessionId":"session-a","seq":1,"time":1,"event":{"type":"user/message","text":"old-selection"}}"""
        backgroundScope.launch(dispatcher) {
            actor.acceptFrame(frame, GatewayWireDecoder.decode(frame), "session-a")
        }
        backgroundScope.launch(dispatcher) { actor.selectSession("session-b") }
        runCurrent()

        assertEquals("session-b", publishedSelections.last())
        assertEquals(listOf("session-a", "session-b"), publishedSelections.takeLast(2))
        actor.close()
    }

    @Test
    fun concurrentFrameResetAndFixtureCannotPublishPreResetComputationLast() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val publications = mutableListOf<com.clarklevis.dsh.shared.facade.SharedMobileSnapshot>()
        val actor = AndroidProjectionActor(
            projection = AndroidGatewayProjection(),
            uiDispatcher = dispatcher,
            publish = { snapshot, _ -> publications += snapshot }
        )
        actor.loadFixture()
        val frame = AndroidSharedStateHolder.DEFAULT_WIRE_PAYLOAD
        backgroundScope.launch(dispatcher) {
            actor.acceptFrame(frame, GatewayWireDecoder.decode(frame), "android-demo")
        }
        backgroundScope.launch(dispatcher) { actor.reset() }
        backgroundScope.launch(dispatcher) { actor.loadFixture() }
        runCurrent()

        val final = publications.last()
        assertEquals("android-demo", final.selectedSessionId)
        assertEquals(2, final.conversation.size)
        assertEquals(1, final.pendingQuestionCount)
        assertTrue(publications.takeLast(3)[1].conversation.isEmpty())
        actor.close()
    }
}
