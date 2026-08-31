package com.clarklevis.dsh.android.platform

import com.clarklevis.dsh.shared.platform.GatewayConnectionSpec
import com.clarklevis.dsh.shared.platform.GatewayTransport
import com.clarklevis.dsh.shared.platform.GatewayTransportFrame
import com.clarklevis.dsh.shared.platform.GatewayTransportEvent
import com.clarklevis.dsh.shared.platform.GatewayTransportState
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

internal class OkHttpGatewayTransport(
    private val client: OkHttpClient = defaultClient(),
    private val diagnostics: AndroidGatewayDiagnostics = AndroidGatewayDiagnostics.disabled()
) : GatewayTransport {
    private val activeGeneration = AtomicLong()
    private val mutableState = MutableStateFlow<GatewayTransportState>(GatewayTransportState.Closed())
    private val eventQueue = BoundedTransportEventQueue(
        maximumFrameBytes = MAXIMUM_QUEUED_BYTES,
        frameOverheadBytes = QUEUED_FRAME_OVERHEAD_BYTES
    )
    private val lock = Any()
    private var socket: WebSocket? = null

    override val state: StateFlow<GatewayTransportState> = mutableState.asStateFlow()
    override val events: Flow<GatewayTransportEvent> = eventQueue.events

    override suspend fun open(spec: GatewayConnectionSpec) {
        diagnostics.transportOpening(
            generation = spec.generation,
            hasBearer = !spec.bearerToken.isNullOrBlank(),
            hasPairingCode = !spec.pairingCode.isNullOrBlank()
        )
        activeGeneration.set(spec.generation)
        synchronized(lock) {
            socket?.cancel()
            socket = null
        }
        publishState(GatewayTransportState.Opening(spec.generation))
        val request = Request.Builder()
            .url(spec.endpoint)
            .header(DEVICE_ID_HEADER, spec.deviceId)
            .header(
                "Sec-WebSocket-Protocol",
                spec.pairingCode?.let { "$MOBILE_PROTOCOL, $PAIR_PROTOCOL_PREFIX$it" }
                    ?: MOBILE_PROTOCOL
            )
            .apply {
                spec.bearerToken?.takeIf(String::isNotBlank)?.let {
                    header("Authorization", "Bearer $it")
                }
            }
            .build()
        val listener = Listener(spec.generation)
        synchronized(lock) { socket = client.newWebSocket(request, listener) }
    }

    override suspend fun send(text: String) {
        val active = synchronized(lock) { socket }
            ?: throw IllegalStateException("not-connected")
        val accepted = active.send(text)
        diagnostics.transportSend(activeGeneration.get(), text.length, accepted)
        check(accepted) { "send-queue-closed" }
    }

    override suspend fun close() {
        val closedGeneration = activeGeneration.getAndSet(0)
        val active = synchronized(lock) {
            val value = socket
            socket = null
            value
        }
        active?.close(NORMAL_CLOSURE, null)
        publishState(GatewayTransportState.Closed(closedGeneration))
    }

    private inner class Listener(private val listenerGeneration: Long) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!isCurrent(listenerGeneration, webSocket)) return
            publishState(GatewayTransportState.Open(listenerGeneration))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val byteCount = utf8ByteCountWithinLimit(text, MAXIMUM_INCOMING_MESSAGE_SIZE)
            if (byteCount == null) {
                failIncoming(webSocket, "incoming-message-too-large", MESSAGE_TOO_BIG)
            } else {
                acceptMessage(webSocket, text, byteCount)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (bytes.size > MAXIMUM_INCOMING_MESSAGE_SIZE) {
                failIncoming(webSocket, "incoming-message-too-large", MESSAGE_TOO_BIG)
                return
            }
            acceptMessage(webSocket, bytes.utf8(), bytes.size)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!isCurrent(listenerGeneration, webSocket)) return
            synchronized(lock) { socket = null }
            publishState(GatewayTransportState.Failed(
                generation = listenerGeneration,
                closeCode = code,
                reason = when (code) {
                    AUTHENTICATION_REQUIRED -> "authentication-required"
                    POLICY_VIOLATION -> "policy-violation"
                    else -> "websocket-failure"
                },
                recoverable = code !in setOf(AUTHENTICATION_REQUIRED, POLICY_VIOLATION)
            ))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!isCurrent(listenerGeneration, webSocket)) return
            synchronized(lock) { socket = null }
            publishState(GatewayTransportState.Failed(
                generation = listenerGeneration,
                httpStatus = response?.code,
                reason = "websocket-failure",
                recoverable = response?.code != HTTP_UNAUTHORIZED
            ))
        }

        private fun acceptMessage(webSocket: WebSocket, text: String, byteCount: Int) {
            if (!isCurrent(listenerGeneration, webSocket)) return
            if (byteCount > MAXIMUM_INCOMING_MESSAGE_SIZE) {
                failIncoming(webSocket, "incoming-message-too-large", MESSAGE_TOO_BIG)
                return
            }
            val frame = GatewayTransportFrame(listenerGeneration, text, byteCount)
            if (!eventQueue.offerFrame(frame)) {
                failIncoming(webSocket, "incoming-overflow", TRY_AGAIN_LATER)
            } else {
                diagnostics.transportFrame(listenerGeneration, byteCount)
            }
        }

        private fun failIncoming(webSocket: WebSocket, reasonCode: String, closeCode: Int) {
            if (!isCurrent(listenerGeneration, webSocket)) return
            synchronized(lock) { socket = null }
            activeGeneration.compareAndSet(listenerGeneration, 0)
            webSocket.close(closeCode, reasonCode)
            publishState(GatewayTransportState.Failed(
                generation = listenerGeneration,
                closeCode = closeCode,
                reason = reasonCode,
                recoverable = true
            ))
        }
    }

    private fun isCurrent(candidate: Long, candidateSocket: WebSocket): Boolean =
        candidate == activeGeneration.get() && synchronized(lock) { socket === candidateSocket }

    private fun publishState(state: GatewayTransportState) {
        diagnostics.transportState(state)
        mutableState.value = state
        check(eventQueue.offerState(state)) { "transport-event-state-overflow" }
    }

    companion object {
        private const val DEVICE_ID_HEADER = "X-DSH-Device-ID"
        private const val MOBILE_PROTOCOL = "dsh-mobile-v1"
        private const val PAIR_PROTOCOL_PREFIX = "dsh-pair."
        internal const val MAXIMUM_INCOMING_MESSAGE_SIZE = 16 * 1_024 * 1_024
        // OkHttp 的 WebSocket 回调不能挂起，固定“帧数”上限会把大量小 token 误判为
        // 内存溢出。与 iOS 的串行 receive loop 一样保持有序、不丢帧，仅用总字节预算
        // 做硬保护；每帧额外计入对象/Channel 开销，避免微小帧绕过内存上限。
        internal const val MAXIMUM_QUEUED_BYTES = 24L * 1_024 * 1_024
        internal const val QUEUED_FRAME_OVERHEAD_BYTES = 512L
        private const val NORMAL_CLOSURE = 1000
        private const val POLICY_VIOLATION = 1008
        private const val MESSAGE_TOO_BIG = 1009
        private const val TRY_AGAIN_LATER = 1013
        private const val AUTHENTICATION_REQUIRED = 4003
        private const val HTTP_UNAUTHORIZED = 401

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

/** 计算 UTF-8 长度但不复制整帧；超过限制时提前停止。 */
internal fun utf8ByteCountWithinLimit(text: String, maximumBytes: Int): Int? {
    var byteCount = 0
    var index = 0
    while (index < text.length) {
        val character = text[index]
        byteCount += when {
            character.code <= 0x7f -> 1
            character.code <= 0x7ff -> 2
            character.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate() -> {
                index += 1
                4
            }
            else -> 3
        }
        if (byteCount > maximumBytes) return null
        index += 1
    }
    return byteCount
}
