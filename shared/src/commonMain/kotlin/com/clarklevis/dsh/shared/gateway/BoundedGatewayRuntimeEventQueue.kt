package com.clarklevis.dsh.shared.gateway

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runtime 到平台投影的单消费者有界队列。
 *
 * Channel 限制事件数量，byte budget 限制队列持有的文本/DTO 估算字节；消费者完成一次
 * `emit` 后才归还预算，因此慢投影会对 WebSocket collector 施加真实背压。
 */
internal class BoundedGatewayRuntimeEventQueue(
    private val maximumEvents: Int,
    private val maximumBytes: Long
) {
    private data class Entry(val event: GatewayRuntimeEvent, val weight: Long)

    private val entries = Channel<Entry>(maximumEvents)
    private val spaceAvailable = Channel<Unit>(Channel.CONFLATED)
    private val budgetLock = Mutex()
    private var queuedBytes = 0L

    val flow: Flow<GatewayRuntimeEvent> = flow {
        for (entry in entries) {
            try {
                emit(entry.event)
            } finally {
                budgetLock.withLock { queuedBytes -= entry.weight }
                spaceAvailable.trySend(Unit)
            }
        }
    }

    suspend fun emit(event: GatewayRuntimeEvent, estimatedBytes: Long = CONTROL_EVENT_BYTES) {
        val weight = estimatedBytes.coerceIn(CONTROL_EVENT_BYTES, maximumBytes)
        while (true) {
            val reserved = budgetLock.withLock {
                if (queuedBytes + weight <= maximumBytes) {
                    queuedBytes += weight
                    true
                } else {
                    false
                }
            }
            if (reserved) break
            spaceAvailable.receive()
        }
        try {
            entries.send(Entry(event, weight))
        } catch (error: Throwable) {
            budgetLock.withLock { queuedBytes -= weight }
            spaceAvailable.trySend(Unit)
            throw error
        }
    }

    companion object {
        private const val CONTROL_EVENT_BYTES = 256L
    }
}
