package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.facade.SharedTurnBoundary
import com.clarklevis.dsh.shared.facade.SharedTurnTimingStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SharedTurnTimingStoreTest {
    @Test
    fun invalidNewStartCannotReuseOldClock() {
        val store = SharedTurnTimingStore()
        store.receiveLiveEvent("s", 10, 1_000.0, "turn/start", 100.0)
        store.receiveLiveEvent("s", 11, 0.0, "turn/start", 110.0)
        assertNull(store.snapshot("s"))
        store.receiveHistory("s", listOf(SharedTurnBoundary(12, Double.NaN, true)))
        assertNull(store.snapshot("s"))
    }

    @Test
    fun clearingInvalidatedHistoryAllowsSequenceToRestart() {
        val store = SharedTurnTimingStore()
        store.receiveLiveEvent("s", 100, 1_000.0, "turn/start", 100.0)
        store.clear("s")
        store.receiveLiveEvent("s", 1, 2_000.0, "turn/start", 200.0)
        assertEquals(1, store.snapshot("s")?.startSequence)
        assertEquals(0.0, store.snapshot("s")?.elapsedSeconds(200.0))
    }

    @Test
    fun liveTurnStartsAtZeroDespiteServerClockOffset() {
        val store = SharedTurnTimingStore()
        store.receiveLiveEvent("s", 10, 1_000.0, "turn/start", 5_000.0)
        assertEquals(0.0, store.snapshot("s")?.elapsedSeconds(5_000.0))
        assertEquals(8.0, store.snapshot("s")?.elapsedSeconds(5_008.0))
    }

    @Test
    fun newTurnDoesNotReusePreviousUserOrStartTime() {
        val store = SharedTurnTimingStore()
        store.receiveLiveEvent("s", 1, 1_000.0, "turn/start", 100.0)
        store.receiveLiveEvent("s", 2, 1_086.0, "turn/end", 186.0)
        store.awaitStart("s", 2)
        assertNull(store.snapshot("s"))
        store.receiveLiveEvent("s", 3, 2_054.0, "turn/start", 1_154.0)
        assertEquals(8.0, store.snapshot("s")?.elapsedSeconds(1_162.0))
    }

    @Test
    fun duplicateStartsAndOlderPagesCannotResetLiveClock() {
        val store = SharedTurnTimingStore()
        store.receiveLiveEvent("s", 10, 1_000.0, "turn/start", 100.0)
        store.receiveLiveEvent("s", 10, 1_000.0, "turn/start", 108.0)
        store.receiveHistory("s", listOf(SharedTurnBoundary(9, 990.0, false)))
        assertEquals(8.0, store.snapshot("s")?.elapsedSeconds(108.0))
    }

    @Test
    fun restoredTurnUsesServerClockReferenceInsteadOfPhoneWallClock() {
        val store = SharedTurnTimingStore()
        store.observeServerTime(1_086.0, 5_000.0)
        store.receiveHistory("s", listOf(SharedTurnBoundary(10, 1_000.0, true)))
        assertEquals(91.0, store.snapshot("s")?.elapsedSeconds(5_005.0))
    }

    @Test
    fun missingStartOrClockReferenceStaysUnknownUntilSynchronized() {
        val store = SharedTurnTimingStore()
        assertNull(store.snapshot("s"))
        store.receiveHistory("s", listOf(SharedTurnBoundary(10, 1_000.0, true)))
        assertNull(store.snapshot("s"))
        store.observeServerTime(1_010.0, 5_000.0)
        assertEquals(10.0, store.snapshot("s")?.elapsedSeconds(5_000.0))
    }

    @Test
    fun waitingForNewStartCannotBeSatisfiedByCachedOldTurn() {
        val store = SharedTurnTimingStore()
        store.observeServerTime(2_054.0, 5_000.0)
        store.awaitStart("s", 20)
        store.receiveHistory("s", listOf(SharedTurnBoundary(10, 1_000.0, true)))
        assertNull(store.snapshot("s"))
        store.receiveLiveEvent("s", 21, 2_054.0, "turn/start", 5_000.0)
        assertNotNull(store.snapshot("s"))
    }

    @Test
    fun endStopsOnlyMatchingSessionAndOldEndCannotStopNewerTurn() {
        val store = SharedTurnTimingStore()
        store.receiveLiveEvent("a", 10, 1_000.0, "turn/start", 100.0)
        store.receiveLiveEvent("b", 20, 1_000.0, "turn/start", 100.0)
        store.receiveLiveEvent("a", 11, 1_010.0, "turn/end", 110.0)
        assertNull(store.snapshot("a"))
        assertNotNull(store.snapshot("b"))
        store.receiveHistory("b", listOf(SharedTurnBoundary(19, 990.0, false)))
        assertNotNull(store.snapshot("b"))
    }

    @Test
    fun millisecondsAreNormalizedAndRestoreDoesNotRestartExistingAnchor() {
        val store = SharedTurnTimingStore()
        store.receiveLiveEvent("s", 10, 1_800_000_000_000.0, "turn/start", 100.0)
        store.receiveHistory("s", listOf(SharedTurnBoundary(10, 1_800_000_000_000.0, true)))
        assertEquals(1_800_000_000.0, store.snapshot("s")?.startEpochSeconds)
        assertEquals(30.0, store.snapshot("s")?.elapsedSeconds(130.0))
    }
}
