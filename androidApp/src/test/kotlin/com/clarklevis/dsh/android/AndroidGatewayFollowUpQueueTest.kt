package com.clarklevis.dsh.android

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidGatewayFollowUpQueueTest {
    @Test
    fun requestsKeepOrderAndOverflowFailsWithoutBlockingConsumer() = runTest {
        val gate = CompletableDeferred<Unit>()
        val delivered = mutableListOf<Int>()
        val failures = mutableListOf<Throwable>()
        val queue = AndroidGatewayFollowUpQueue(backgroundScope, { failures += it }, capacity = 2)
        queue.submit { gate.await(); delivered += 1 }
        runCurrent()
        queue.submit { delivered += 2 }
        queue.submit { delivered += 3 }
        queue.submit { delivered += 4 }
        assertEquals(1, failures.size)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(1, 2, 3), delivered)
        queue.close()
    }

    @Test
    fun closeCancelsWaitingRequestAndDiscardsPendingRequests() = runTest {
        val gate = CompletableDeferred<Unit>()
        val delivered = mutableListOf<Int>()
        val failures = mutableListOf<Throwable>()
        val queue = AndroidGatewayFollowUpQueue(backgroundScope, { failures += it })
        queue.submit { gate.await(); delivered += 1 }
        runCurrent()
        queue.submit { delivered += 2 }
        queue.close()
        gate.complete(Unit)
        runCurrent()
        assertTrue(delivered.isEmpty())
        assertTrue(failures.isEmpty())
    }
}
