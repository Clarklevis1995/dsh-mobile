package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.gateway.BoundedGatewayRuntimeEventQueue
import com.clarklevis.dsh.shared.gateway.GatewayRuntimeEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BoundedGatewayRuntimeEventQueueTest {
    @Test
    fun byteBudgetBackpressuresProducerUntilConsumerFinishesEvent() = runTest {
        val queue = BoundedGatewayRuntimeEventQueue(maximumEvents = 8, maximumBytes = 512)
        val releaseConsumer = CompletableDeferred<Unit>()
        backgroundScope.launch {
            queue.flow.collect {
                releaseConsumer.await()
            }
        }
        runCurrent()

        queue.emit(GatewayRuntimeEvent.RequestQueued("first", "first"), estimatedBytes = 512)
        runCurrent()
        val second = async {
            queue.emit(GatewayRuntimeEvent.RequestQueued("second", "second"), estimatedBytes = 512)
        }
        runCurrent()
        assertFalse(second.isCompleted)
        releaseConsumer.complete(Unit)
        runCurrent()
        assertTrue(second.isCompleted)
    }
}
