package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.diagnostics.NetworkDiagnosticsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkDiagnosticsStoreTest {
    private fun store() = NetworkDiagnosticsStore().apply {
        start("test", 0.0)
        connection("generation-1", "WebSocket", true, true, 0.0)
    }

    @Test fun correlatedProbeMeasuresRttAndIgnoresOrdinaryOrLatePong() {
        val store = store()
        val id = assertNotNull(store.nextProbe(0.0))
        assertNull(store.nextProbe(1000.0))
        assertFalse(store.acceptPong("ordinary-ping", 10.0))
        assertTrue(store.acceptPong(id, 35.0))
        assertFalse(store.acceptPong(id, 40.0))
        assertEquals(35.0, store.snapshot().roundTripMs)
        assertEquals(1, store.snapshot().successfulProbes)
    }

    @Test fun timeoutLossJitterAndP95UseCompletedProbesOnly() {
        val store = store()
        store.acceptPong(assertNotNull(store.nextProbe(0.0)), 20.0)
        store.acceptPong(assertNotNull(store.nextProbe(1000.0)), 1040.0)
        store.nextProbe(2000.0)
        assertEquals(0.0, store.tick(4000.0).lossPercent)
        val state = store.tick(5000.0)
        assertEquals(100.0 / 3, state.lossPercent)
        assertEquals(20.0, state.jitterMs)
        assertEquals(30.0, state.averageRoundTripMs)
        assertEquals(40.0, state.p95RoundTripMs)
        assertEquals(-1.0, state.roundTripMs)
    }

    @Test fun leavingPageAndSwitchingConnectionNeverCountCancelledProbesAsLoss() {
        val store = store()
        val id = assertNotNull(store.nextProbe(0.0))
        store.stop()
        assertFalse(store.acceptPong(id, 5000.0))
        assertEquals(0, store.tick(10000.0).timedOutProbes)
        assertNull(store.nextProbe(10000.0))
        store.start("second-run", 10000.0)
        store.connection("generation-2", "WebRTC", true, true, 10000.0)
        val newId = assertNotNull(store.nextProbe(10000.0))
        assertFalse(store.acceptPong(id, 10020.0))
        assertTrue(store.acceptPong(newId, 10025.0))
        store.connection("generation-3", "WebSocket", true, true, 11000.0)
        assertEquals(-1.0, store.snapshot().roundTripMs)
        assertTrue(store.snapshot().samples.isEmpty())
    }

    @Test fun ratesAreApplicationBytesAndHistoryIsBounded() {
        val store = store()
        store.recordTraffic(1024, 2048)
        assertEquals(2048.0, store.tick(1000.0).receivedBytesPerSecond)
        assertEquals(1024.0, store.snapshot().sentBytesPerSecond)
        for (second in 2..100) store.tick(second * 1000.0)
        assertEquals(60, store.snapshot().samples.size)
        assertEquals(0.0, store.snapshot().receivedBytesPerSecond)
    }

    @Test fun disconnectedAndOldGatewaysNeverSendProbesOrClaimZeroLoss() {
        val store = store()
        store.connection("old", "WebSocket", true, false, 0.0)
        assertNull(store.nextProbe(1000.0))
        assertEquals(-1.0, store.tick(1000.0).lossPercent)
        store.connection("offline", "", false, true, 2000.0)
        assertNull(store.nextProbe(2000.0))
        store.recordTraffic(200, 200)
        assertEquals(0.0, store.tick(3000.0).receivedBytesPerSecond)
    }

    @Test fun expiredPongCannotSatisfyANewerProbeAndWindowExpires() {
        val store = store()
        val old = assertNotNull(store.nextProbe(0.0))
        assertFalse(store.acceptPong(old, 3000.0))
        val next = assertNotNull(store.nextProbe(3000.0))
        assertFalse(store.acceptPong(old, 3001.0))
        assertTrue(store.acceptPong(next, 3010.0))
        assertEquals(10.0, store.snapshot().roundTripMs)
        assertEquals(-1.0, store.tick(64000.0).lossPercent)
    }
}
