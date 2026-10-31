// 协议版本、字节偏移和资源预算与网关契约逐项对应，保留原值便于审计。
@file:Suppress("MagicNumber")

package com.clarklevis.dsh.android.platform

import android.content.Context
import com.clarklevis.dsh.shared.gateway.SplitGatewayTransport
import com.clarklevis.dsh.shared.platform.GatewayConnectionSpec
import com.clarklevis.dsh.shared.platform.GatewayRtcTransport
import com.clarklevis.dsh.shared.platform.GatewayTransport
import com.clarklevis.dsh.shared.platform.GatewayTransportEvent
import com.clarklevis.dsh.shared.platform.GatewayTransportFrame
import com.clarklevis.dsh.shared.platform.GatewayTransportState
import com.clarklevis.dsh.shared.transport.GatewayTransportPolicy
import com.clarklevis.dsh.shared.transport.RtcPathInspector
import com.clarklevis.dsh.shared.transport.RtcSelectedPath
import com.clarklevis.dsh.shared.transport.RtcUpgradeAction
import com.clarklevis.dsh.shared.transport.RtcUpgradeMachine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** 鉴权 WS 始终保留；Split 只看到最终业务 hello，不会提前启动 conversation WS。 */
internal class RtcGatewayTransport(
    private val context: Context,
    private val scope: CoroutineScope,
    private val diagnostics: AndroidGatewayDiagnostics,
    private val rtcPolicy: (String) -> Boolean = GatewayTransportPolicy::allowsRtc,
) : GatewayRtcTransport {
    private val signal = OkHttpGatewayTransport(diagnostics = diagnostics)
    private val conversation = OkHttpGatewayTransport(diagnostics = diagnostics)
    private val controlLane = Lane("control")
    private val conversationLane = Lane("conversation")
    private val split = SplitGatewayTransport(controlLane, conversationLane)
    private val lock = Mutex()
    private var spec: GatewayConnectionSpec? = null
    private var epoch = 0L
    private var machine: RtcUpgradeMachine? = null
    private val peers = mutableMapOf<String, AndroidRtcPeer>()
    private var deadline: Job? = null
    private var idleCheck: Job? = null
    private var skipNextRtc = false
    private var rtcStartedAt = 0L
    private var lastRtcDiagnostic = ""
    private val status = MutableStateFlow("")
    val connectionMethod: StateFlow<String> = status.asStateFlow()
    private val paths = MutableStateFlow<Map<String, RtcSelectedPath>>(emptyMap())
    val connectionPaths: StateFlow<Map<String, RtcSelectedPath>> = paths.asStateFlow()

    private data class NativeEvent(
        val epoch: Long,
        val channel: String,
        val kind: String,
        val text: String,
        val bytes: ByteArray?,
    )

    private val nativeEvents = Channel<NativeEvent>(512)
    private val nativeBytes = AtomicLong()

    init {
        scope.launch {
            signal.events.collect { event ->
                lock.withLock {
                    val current = spec ?: return@withLock
                    val generation =
                        when (event) {
                            is GatewayTransportEvent.State -> event.value.generation
                            is GatewayTransportEvent.Frame -> event.value.generation
                        }
                    if (generation != current.generation) return@withLock
                    when (event) {
                        is GatewayTransportEvent.Frame -> {
                            machine?.let { runActions(it.webSocket(event.value.text)) }
                                ?: controlLane.emit(event)
                        }

                        is GatewayTransportEvent.State -> {
                            if (event.value is GatewayTransportState.Failed) {
                                if (machine?.mode == "webrtc") {
                                    traceRtc("fallback-requested", "connection-lost", "control")
                                    skipNextRtc = true
                                }
                                resetRtc()
                                machine?.close()
                                status.value = ""
                            }
                            controlLane.emit(event)
                        }
                    }
                }
            }
        }
        scope.launch {
            conversation.events.collect { event ->
                val generation =
                    when (event) {
                        is GatewayTransportEvent.State -> event.value.generation
                        is GatewayTransportEvent.Frame -> event.value.generation
                    }
                if (generation == spec?.generation && machine?.mode != "webrtc") conversationLane.emit(event)
            }
        }
        scope.launch {
            for (event in nativeEvents) {
                nativeBytes.addAndGet(-(event.bytes?.size ?: 0).toLong())
                lock.withLock {
                    if (event.epoch != epoch || spec == null) return@withLock
                    val current = machine ?: return@withLock
                    if (current.phase !in setOf("negotiating", "selecting-webrtc", "active")) return@withLock
                    if (event.kind == "path-stats") {
                        if (current.mode == "webrtc" && current.phase == "active") {
                            val path = RtcPathInspector.inspect(event.text)
                            paths.value = if (path == null) paths.value - event.channel else paths.value + (event.channel to path)
                        }
                        return@withLock
                    }
                    val actions =
                        try {
                            when (event.kind) {
                                "offer", "candidate", "end-of-candidates" -> {
                                    current.localSignal(event.channel, event.kind, event.text)
                                }

                                "open" -> {
                                    current.rtcOpened(event.channel)
                                }

                                "text" -> {
                                    current.rtcText(
                                        event.channel,
                                        requireNotNull(event.bytes).decodeToString(throwOnInvalidSequence = true),
                                    )
                                }

                                "binary" -> {
                                    peers[event.channel]
                                        ?.receive(
                                            requireNotNull(event.bytes),
                                        )?.let { current.rtcMessage(event.channel, it) }
                                        ?: emptyList()
                                }

                                "unavailable" -> {
                                    traceRtc("native-failure", event.text.ifEmpty { "native-rtc-unavailable" }, event.channel)
                                    current.rtcUnavailable()
                                }

                                else -> {
                                    traceRtc("native-failure", event.text.ifEmpty { "protocol-validation-failed" }, event.channel)
                                    current.securityFailure()
                                }
                            }
                        } catch (_: Exception) {
                            current.securityFailure()
                        }
                    runActions(actions)
                }
            }
        }
    }

    override fun resetRtcRecovery() {
        skipNextRtc = false
    }

    override val state = controlLane.state
    override val events = split.events
    override val conversationEvents = split.conversationEvents

    override suspend fun open(spec: GatewayConnectionSpec) = split.open(spec)

    override suspend fun confirmControlHandshake() = split.confirmControlHandshake()

    override suspend fun send(text: String) = split.send(text)

    override suspend fun close() = split.close()

    private suspend fun runActions(actions: List<RtcUpgradeAction>) {
        val current = machine
        val key = current?.let { "${it.diagnosticReason}:${it.diagnosticPhase}" }
        if (current != null && current.diagnosticReason.isNotEmpty() && key != lastRtcDiagnostic) {
            lastRtcDiagnostic = key.orEmpty()
            val event =
                when {
                    actions.any { it.kind == "security-failure" } -> "blocked"
                    actions.any { it.kind == "rtc-close" } -> "fallback-requested"
                    else -> "upgrade-skipped"
                }
            traceRtc(event, current.diagnosticReason, phase = current.diagnosticPhase)
        }
        try {
            executeActions(actions)
        } catch (
            cancelled: CancellationException,
        ) {
            throw cancelled
        } catch (_: Exception) {
            traceRtc("blocked", "frame-handling-failed")
            machine?.let { executeActions(it.securityFailure()) }
        }
    }

    // 按状态机给出的顺序执行整组动作，提交和首个 ping 不能拆成并发工作。
    @Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
    private suspend fun executeActions(actions: List<RtcUpgradeAction>) {
        for (action in actions) {
            val current = machine ?: return
            when (action.kind) {
                "ws-send" -> {
                    try {
                        signal.send(action.text)
                    } catch (_: Exception) {
                        traceRtc("native-failure", "signaling-send-failed")
                        runActions(current.rtcUnavailable())
                        return
                    }
                }

                "deadline" -> {
                    deadline?.cancel()
                    val captured = epoch
                    val total = action.text.toLong()
                    deadline =
                        scope.launch {
                            delay((total - 1500).coerceAtLeast(1))
                            lock.withLock { if (captured == epoch) machine?.let { runActions(it.deadline()) } }
                            delay(minOf(total, 1500))
                            lock.withLock { if (captured == epoch) machine?.let { runActions(it.deadline()) } }
                        }
                }

                "cancel-deadline" -> {
                    deadline?.cancel()
                    deadline = null
                }

                "rtc-start" -> {
                    val captured = epoch
                    try {
                        val overflowed =
                            java.util.concurrent.atomic
                                .AtomicBoolean()
                        val peer =
                            AndroidRtcPeer(context, action.channel, action.text) { kind, text, bytes ->
                                if (overflowed.get()) return@AndroidRtcPeer
                                val size = bytes?.size ?: 0
                                if (nativeBytes.addAndGet(size.toLong()) > 4 * 1024 * 1024 ||
                                    !nativeEvents.trySend(NativeEvent(captured, action.channel, kind, text, bytes)).isSuccess
                                ) {
                                    nativeBytes.addAndGet(-size.toLong())
                                    // 队列耗尽必须结束整组，不能丢帧继续执行业务。
                                    if (overflowed.compareAndSet(false, true)) {
                                        scope.launch {
                                            lock.withLock {
                                                if (captured == epoch) machine?.let { runActions(it.securityFailure()) }
                                            }
                                        }
                                    }
                                }
                            }
                        peers[action.channel] = peer
                        peer.start()
                    } catch (_: Exception) {
                        traceRtc("native-failure", "runtime-unavailable", action.channel)
                        runActions(current.rtcUnavailable())
                        return
                    } catch (_: LinkageError) {
                        traceRtc("native-failure", "runtime-unavailable", action.channel)
                        runActions(current.rtcUnavailable())
                        return
                    }
                }

                "rtc-signal" -> {
                    peers[action.channel]?.signal(action.text)
                }

                "rtc-text" -> {
                    peers[action.channel]?.text(action.text)
                }

                "rtc-send" -> {
                    peers[action.channel]?.send(action.text, current.maxFrameBytes, current.maxLogicalMessageBytes)
                }

                "rtc-close" -> {
                    resetRtc()
                }

                "selected" -> {
                    traceRtc("selected", current.diagnosticReason.ifEmpty { "none" })
                    status.value = if (action.text == "webrtc") "WebRTC" else "WebSocket"
                    if (action.text == "webrtc") {
                        startIdleCheck()
                    }
                }

                "deliver" -> {
                    val lane = if (action.channel == "control") controlLane else conversationLane
                    val generation = spec?.generation ?: return
                    lane.emit(
                        GatewayTransportEvent.Frame(GatewayTransportFrame(generation, action.text, action.text.encodeToByteArray().size)),
                    )
                }

                "retry-websocket", "security-failure" -> {
                    val generation = spec?.generation ?: return
                    skipNextRtc = action.kind == "retry-websocket"
                    status.value = ""
                    signal.close()
                    conversation.close()
                    controlLane.emit(
                        GatewayTransportEvent.State(
                            GatewayTransportState.Failed(
                                generation,
                                reason = "transport-${action.kind}",
                                recoverable = skipNextRtc,
                            ),
                        ),
                    )
                }
            }
        }
    }

    private fun startIdleCheck() {
        peers.values.forEach { it.requestPathStats() }
        val captured = epoch
        idleCheck =
            scope.launch {
                var ticks = 0
                while (isActive) {
                    delay(1000)
                    lock.withLock {
                        if (captured != epoch) return@launch
                        if (runCatching { peers.values.forEach { it.checkIdle() } }.isFailure) {
                            machine?.let { runActions(it.securityFailure()) }
                        }
                        ticks += 1
                        if (ticks <= 3 || ticks % 5 == 0) peers.values.forEach { it.requestPathStats() }
                    }
                }
            }
    }

    private fun traceRtc(
        event: String,
        reason: String,
        channel: String = "group",
        phase: String? = null,
    ) {
        diagnostics.rtc(
            event,
            reason,
            phase ?: machine?.phase.orEmpty(),
            channel,
            machine?.mode?.ifEmpty { "none" } ?: "none",
            android.os.SystemClock.elapsedRealtime() - rtcStartedAt,
        )
    }

    private fun resetRtc() {
        paths.value = emptyMap()
        peers.values.forEach { it.close() }
        peers.clear()
        idleCheck?.cancel()
        idleCheck = null
    }

    private inner class Lane(
        private val name: String,
    ) : GatewayTransport {
        private val queue = BoundedTransportEventQueue(24L * 1024 * 1024, 512)
        private val mutableState = MutableStateFlow<GatewayTransportState>(GatewayTransportState.Closed())
        private var ready = false
        private val pending = ArrayDeque<GatewayTransportEvent.Frame>()
        private var pendingBytes = 0
        override val state = mutableState.asStateFlow()
        override val events = queue.events

        @Suppress("ComplexCondition")
        suspend fun emit(event: GatewayTransportEvent) {
            if (name == "conversation" && machine?.mode == "webrtc" && !ready && event is GatewayTransportEvent.Frame) {
                pendingBytes += event.value.byteCount + 512
                if (pendingBytes > 24 * 1024 * 1024) {
                    machine?.let { runActions(it.securityFailure()) }
                    return
                }
                pending.addLast(event)
                return
            }
            val accepted =
                when (event) {
                    is GatewayTransportEvent.State -> {
                        mutableState.value = event.value
                        queue.offerState(event.value)
                    }

                    is GatewayTransportEvent.Frame -> {
                        queue.offerFrame(event.value)
                    }
                }
            if (!accepted) machine?.let { runActions(it.securityFailure()) }
        }

        private fun traceRtcOpening(
            spec: GatewayConnectionSpec,
            negotiate: Boolean,
        ) {
            rtcStartedAt = android.os.SystemClock.elapsedRealtime()
            lastRtcDiagnostic = ""
            val reason =
                when {
                    !rtcPolicy(spec.endpoint) -> "lan-websocket"
                    negotiate -> "none"
                    !spec.pairingCode.isNullOrBlank() -> "pairing"
                    spec.bearerToken.isNullOrBlank() -> "no-credential"
                    spec.expectedGatewayId.isNullOrBlank() -> "no-gateway-identity"
                    else -> "websocket-recovery"
                }
            diagnostics.rtc(
                if (negotiate) "upgrade-requested" else "upgrade-skipped",
                reason,
                "connecting",
                "control",
                if (negotiate) "none" else "websocket",
                0,
            )
        }

        override suspend fun open(spec: GatewayConnectionSpec) {
            if (name == "control") {
                lock.withLock {
                    epoch++
                    this@RtcGatewayTransport.spec = spec
                    val negotiate =
                        !skipNextRtc && !spec.bearerToken.isNullOrBlank() && spec.pairingCode == null &&
                            !spec.expectedGatewayId.isNullOrBlank() && rtcPolicy(spec.endpoint)
                    traceRtcOpening(spec, negotiate)
                    skipNextRtc = false
                    machine = if (negotiate) RtcUpgradeMachine(spec.expectedGatewayId!!, UUID.randomUUID().toString()) else null
                    status.value = ""
                    signal.open(spec.copy(negotiateRtc = negotiate))
                }
            } else if (machine?.mode == "webrtc") {
                ready = true
                while (pending.isNotEmpty()) emit(pending.removeFirst())
                pendingBytes = 0
            } else {
                status.value = "WebSocket"
                conversation.open(spec.copy(negotiateRtc = false))
            }
        }

        // SDK、背压或编码异常都经过整组清理，再原样抛给 Runtime 保留草稿。
        @Suppress("TooGenericExceptionCaught")
        override suspend fun send(text: String) {
            val current = machine
            if (current?.phase == "active") {
                val peer = peers[name] ?: error("transport-closed")
                try {
                    peer.send(text, current.maxFrameBytes, current.maxLogicalMessageBytes)
                } catch (error: Exception) {
                    // 过大请求尚未发送，保留上层草稿；任何已发送故障均不重放写操作。
                    lock.withLock { runActions(current.rtcUnavailable()) }
                    throw error
                }
            } else if (name == "control") {
                signal.send(text)
            } else {
                conversation.send(text)
            }
        }

        override suspend fun close() {
            ready = false
            pending.clear()
            pendingBytes = 0
            if (name == "control") {
                lock.withLock {
                    epoch++
                    machine?.close()
                    machine = null
                    spec = null
                    resetRtc()
                    deadline?.cancel()
                    deadline = null
                    status.value = ""
                    signal.close()
                }
            } else {
                conversation.close()
            }
        }
    }
}
