package com.clarklevis.dsh.android

import com.clarklevis.dsh.shared.facade.SharedMobileSnapshot
import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidHistoryPresentationActorTest {
    @Test
    fun snapshotTimeoutAndSwitchCannotLeaveSpinnerOrTimeOutAnotherSession() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val publications = mutableListOf<SharedMobileSnapshot>()
        val actor = AndroidProjectionActor(
            AndroidGatewayProjection(),
            dispatcher,
            { snapshot, _ -> publications += snapshot },
            backgroundDispatcher = dispatcher,
            snapshotTimeoutMillis = 100
        )
        try {
            actor.accept(HISTORY_HELLO)
            actor.selectSession("s")
            advanceTimeBy(50)
            actor.selectSession("b")
            advanceTimeBy(51)
            runCurrent()
            assertTrue(publications.last().selectedHistoryIsLoading)
            advanceTimeBy(50)
            runCurrent()
            assertFalse(publications.last().selectedHistoryIsLoading)
            assertEquals("b", publications.last().selectedSessionId)
            assertNotNull(publications.last().lastError)
            actor.selectSession("s")
            actor.disconnected()
            advanceTimeBy(200)
            runCurrent()
            assertFalse(publications.last().selectedHistoryIsLoading)
        } finally {
            actor.close()
        }
    }

    @Test
    fun snapshotPublicationNeverContainsEmptyConversationWithFinishedLoading() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val publications = mutableListOf<SharedMobileSnapshot>()
        val actor = AndroidProjectionActor(
            AndroidGatewayProjection(),
            dispatcher,
            { snapshot, _ -> publications += snapshot },
            backgroundDispatcher = dispatcher
        )
        try {
            actor.accept(HISTORY_HELLO)
            actor.selectSession("s")
            publications.clear()
            actor.accept(HISTORY_SUBSCRIBED)
            actor.accept(HISTORY_SNAPSHOT)
            assertTrue(publications.all { it.selectedHistoryIsLoading || it.conversation.isNotEmpty() })
            assertFalse(publications.last().selectedHistoryIsLoading)
        } finally {
            actor.close()
        }
    }

    private suspend fun AndroidProjectionActor.accept(raw: String) = acceptFrame(raw, GatewayWireDecoder.decode(raw), null)
}
