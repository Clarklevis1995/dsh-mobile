package com.clarklevis.dsh.android.platform

import com.clarklevis.dsh.shared.platform.GatewayTransportEvent
import com.clarklevis.dsh.shared.platform.GatewayTransportFrame
import com.clarklevis.dsh.shared.platform.GatewayTransportState
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedTransportEventQueueTest {
    @Test
    fun failureAndFramesShareOneDeterministicOrder() = runTest {
        val queue = BoundedTransportEventQueue(maximumFrameCount = 2, maximumFrameBytes = 6)
        assertTrue(queue.offerFrame(GatewayTransportFrame(1, "old", 3)))
        assertTrue(
            queue.offerState(
                GatewayTransportState.Failed(
                    generation = 1,
                    reason = "incoming-overflow",
                    recoverable = true
                )
            )
        )
        assertTrue(queue.offerFrame(GatewayTransportFrame(1, "late", 3)))
        assertFalse(queue.offerFrame(GatewayTransportFrame(1, "overflow", 3)))

        val received = async { queue.events.take(3).toList() }.await()
        assertEquals(
            listOf("old", "incoming-overflow", "late"),
            received.map {
                when (it) {
                    is GatewayTransportEvent.Frame -> it.value.text
                    is GatewayTransportEvent.State -> (it.value as GatewayTransportState.Failed).reason
                }
            }
        )
    }

    @Test
    fun callbackBurstHasAuditableOverflowAndStillDeliversFailureAfterAcceptedFrames() = runTest {
        val queue = BoundedTransportEventQueue(maximumFrameCount = 32, maximumFrameBytes = 1_024)
        repeat(32) { index ->
            assertTrue(queue.offerFrame(GatewayTransportFrame(9, "frame-$index", 32)))
        }
        assertFalse(queue.offerFrame(GatewayTransportFrame(9, "overflow", 32)))
        assertTrue(
            queue.offerState(
                GatewayTransportState.Failed(
                    generation = 9,
                    reason = "incoming-overflow",
                    recoverable = true
                )
            )
        )
        val received = async { queue.events.take(33).toList() }.await()
        assertEquals((0 until 32).map { "frame-$it" }, received.take(32).map {
            (it as GatewayTransportEvent.Frame).value.text
        })
        assertEquals(
            "incoming-overflow",
            ((received.last() as GatewayTransportEvent.State).value as GatewayTransportState.Failed).reason
        )
    }
}
