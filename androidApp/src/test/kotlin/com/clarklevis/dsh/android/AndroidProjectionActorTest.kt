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
    fun frameThenSelectPublishesInOrderAndOldFrameCannotOverwriteSelection() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val publishedSelections = mutableListOf<String?>()
        val actor = AndroidProjectionActor(
            projection = AndroidGatewayProjection(),
            uiDispatcher = dispatcher,
            publish = { publishedSelections += it.selectedSessionId }
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
            publish = publications::add
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
