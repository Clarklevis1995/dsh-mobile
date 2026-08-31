package com.clarklevis.dsh.android.platform

import com.clarklevis.dsh.shared.platform.GatewayTransportEvent
import com.clarklevis.dsh.shared.platform.GatewayTransportFrame
import com.clarklevis.dsh.shared.platform.GatewayTransportState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.onEach

internal class BoundedTransportEventQueue(
    private val maximumFrameBytes: Long,
    private val frameOverheadBytes: Long = 0L
) {
    // State 数量只由 transport 生命周期产生；使用无界控制通道可保证 failure 永不因
    // frame 背压而丢失，frame 本身仍由下方总字节硬上限约束。
    private val channel = Channel<GatewayTransportEvent>(Channel.UNLIMITED)
    private val budgetLock = Any()
    private var frameBytes = 0L

    val events: Flow<GatewayTransportEvent> = channel.consumeAsFlow().onEach { event ->
        if (event is GatewayTransportEvent.Frame) {
            synchronized(budgetLock) {
                frameBytes -= event.value.queueWeight()
            }
        }
    }

    fun offerFrame(frame: GatewayTransportFrame): Boolean {
        val weight = frame.queueWeight()
        synchronized(budgetLock) {
            if (frameBytes + weight > maximumFrameBytes) return false
            frameBytes += weight
        }
        if (channel.trySend(GatewayTransportEvent.Frame(frame)).isSuccess) return true
        synchronized(budgetLock) { frameBytes -= weight }
        return false
    }

    fun offerState(state: GatewayTransportState): Boolean =
        channel.trySend(GatewayTransportEvent.State(state)).isSuccess

    private fun GatewayTransportFrame.queueWeight(): Long =
        byteCount.toLong() + frameOverheadBytes
}
