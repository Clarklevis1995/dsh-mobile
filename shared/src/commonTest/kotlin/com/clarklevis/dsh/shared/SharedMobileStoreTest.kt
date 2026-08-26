package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.facade.SharedMobileFacade
import com.clarklevis.dsh.shared.facade.SharedMobileStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedMobileStoreTest {
    @Test
    fun facadeNormalizesWireKindForPlatformAdapters() {
        assertEquals(
            "event",
            SharedMobileFacade().decodeFrameKind(GatewayProtocolFixtures.LIVE_EVENT_WITHOUT_KIND)
        )
    }

    @Test
    fun manualFixtureUsesSharedReducersAndProjection() {
        val store = SharedMobileStore()
        val snapshot = store.loadManualTestFixture()
        assertEquals("android-demo", snapshot.selectedSessionId)
        assertEquals(1, snapshot.sessions.size)
        assertTrue(snapshot.sessions.single().isRunning)
        assertEquals(2, snapshot.conversation.size)
        assertEquals("共享协议解码、Reducer 与投影已接入。", snapshot.conversation.last().text)
        assertEquals(1, snapshot.pendingQuestionCount)
        assertEquals("question-requested", snapshot.lastFrameKind)
        assertNull(snapshot.lastError)
    }

    @Test
    fun invalidFrameIsReportedWithoutDestroyingCurrentState() {
        val store = SharedMobileStore()
        store.loadManualTestFixture()
        val snapshot = store.acceptFrame("not-json")
        assertEquals(1, snapshot.sessions.size)
        assertTrue(!snapshot.lastError.isNullOrBlank())
    }
}
