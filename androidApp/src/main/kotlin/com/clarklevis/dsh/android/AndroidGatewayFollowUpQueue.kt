package com.clarklevis.dsh.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * 接收事件的消费者不能等待 Runtime 锁：Runtime 可能正持锁等待该消费者释放队列预算。
 * 后续网络请求交给独立、按提交顺序执行的 worker，消费事件时只做非阻塞入队。
 */
internal class AndroidGatewayFollowUpQueue(
    scope: CoroutineScope,
    private val onFailure: (Throwable) -> Unit,
    capacity: Int = 64
) {
    private val requests = Channel<suspend () -> Unit>(capacity)
    private val worker = scope.launch {
        for (request in requests) {
            try {
                request()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                onFailure(error)
            }
        }
    }

    fun submit(request: suspend () -> Unit) {
        if (requests.trySend(request).isFailure) {
            onFailure(IllegalStateException("后续请求队列已满或已关闭，请重新连接后重试。"))
        }
    }

    fun close() {
        requests.cancel()
        worker.cancel()
    }
}
