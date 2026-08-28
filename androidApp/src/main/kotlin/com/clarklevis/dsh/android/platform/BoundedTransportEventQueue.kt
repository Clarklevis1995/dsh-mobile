package com.clarklevis.dsh.android.platform

import com.clarklevis.dsh.shared.platform.GatewayTransportEvent
import com.clarklevis.dsh.shared.platform.GatewayTransportFrame
import com.clarklevis.dsh.shared.platform.GatewayTransportState
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.onEach

internal class BoundedTransportEventQueue(
    private val maximumFrameCount: Int,
    private val maximumFrameBytes: Long
) {
    // State 数量只由 transport 生命周期产生；使用无界控制通道可保证 failure 永不因
    // frame 背压而丢失，frame 本身仍由下方 count/bytes 两个硬上限约束。
    private val channel = Channel<GatewayTransportEvent>(Channel.UNLIMITED)
    private val frameCount = AtomicInteger()
    private val frameBytes = AtomicLong()

    val events: Flow<GatewayTransportEvent> = channel.consumeAsFlow().onEach { event ->
        if (event is GatewayTransportEvent.Frame) {
            frameCount.decrementAndGet()
            frameBytes.addAndGet(-event.value.byteCount.toLong())
        }
    }

    fun offerFrame(frame: GatewayTransportFrame): Boolean {
        val count = frameCount.incrementAndGet()
        val bytes = frameBytes.addAndGet(frame.byteCount.toLong())
        if (count > maximumFrameCount || bytes > maximumFrameBytes) {
            rollbackFrame(frame)
            return false
        }
        if (channel.trySend(GatewayTransportEvent.Frame(frame)).isSuccess) return true
        rollbackFrame(frame)
        return false
    }

    fun offerState(state: GatewayTransportState): Boolean =
        channel.trySend(GatewayTransportEvent.State(state)).isSuccess

    private fun rollbackFrame(frame: GatewayTransportFrame) {
        frameCount.decrementAndGet()
        frameBytes.addAndGet(-frame.byteCount.toLong())
    }
}
