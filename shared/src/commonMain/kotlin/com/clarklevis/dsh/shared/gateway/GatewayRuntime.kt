package com.clarklevis.dsh.shared.gateway


import com.clarklevis.dsh.shared.diagnostics.NetworkDiagnosticsSnapshot
import com.clarklevis.dsh.shared.diagnostics.NetworkDiagnosticsStore
import com.clarklevis.dsh.shared.platform.GatewayAttachmentCache
import com.clarklevis.dsh.shared.platform.GatewayClock
import com.clarklevis.dsh.shared.platform.GatewayConnectionSpec
import com.clarklevis.dsh.shared.platform.GatewayCredentialStore
import com.clarklevis.dsh.shared.platform.GatewayNetworkMonitor
import com.clarklevis.dsh.shared.platform.GatewayNetworkState
import com.clarklevis.dsh.shared.platform.GatewayPreferences
import com.clarklevis.dsh.shared.platform.GatewaySplitTransport
import com.clarklevis.dsh.shared.platform.GatewayTransport
import com.clarklevis.dsh.shared.platform.GatewayTransportEvent
import com.clarklevis.dsh.shared.platform.GatewayTransportFrame
import com.clarklevis.dsh.shared.platform.GatewayTransportState
import com.clarklevis.dsh.shared.protocol.GatewayFrame
import com.clarklevis.dsh.shared.protocol.GatewayQuestionAnswer
import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

enum class GatewayConnectionState {
    DISCONNECTED,
    WAITING_FOR_NETWORK,
    CONNECTING,
    AUTHENTICATING,
    CONNECTED,
    FAILED,
    SUSPENDED
}

data class GatewayRuntimeState(
    val connection: GatewayConnectionState = GatewayConnectionState.DISCONNECTED,
    val endpoint: String? = null,
    val networkAvailable: Boolean = true,
    val serverPort: Int? = null,
    val clientCount: Int? = null,
    val protocolVersion: Int? = null,
    val capabilities: Set<String> = emptySet(),
    val reconnectAttempt: Int = 0,
    val activeTurnSessionIds: Set<String> = emptySet(),
    val hasUnassociatedTurn: Boolean = false,
    val lastError: String? = null
) {
    val shouldKeepAliveInBackground: Boolean
        get() = activeTurnSessionIds.isNotEmpty() || hasUnassociatedTurn
}

sealed interface GatewayRuntimeEvent {
    data class Frame(
        val rawJson: String,
        val frame: GatewayFrame,
        val correlatedSessionId: String?,
        val isBackgroundHistory: Boolean = false
    ) : GatewayRuntimeEvent {
        override fun toString(): String =
            "Frame(kind=${frame.kind}, correlatedSessionId=$correlatedSessionId, rawJson=<redacted>)"
    }

    data class AttachmentCached(val sessionId: String, val attachmentId: String) : GatewayRuntimeEvent
    data class RequestQueued(val requestType: String, val responseKind: String) : GatewayRuntimeEvent
    data class RequestCancelled(
        val requestType: String,
        val targetSessionId: String?,
        val reason: String,
        val correlationId: String? = null,
        val isBackgroundHistory: Boolean = false
    ) : GatewayRuntimeEvent
    data class RequestTimedOut(
        val requestType: String,
        val targetSessionId: String?,
        val correlationId: String? = null,
        val isBackgroundHistory: Boolean = false
    ) : GatewayRuntimeEvent
    data class RequestRejected(
        val requestType: String,
        val reason: String,
        val targetSessionId: String? = null,
        val correlationId: String? = null,
        val isBackgroundHistory: Boolean = false
    ) : GatewayRuntimeEvent
}

/** 所有可变状态与平台 I/O 均经过同一串行边界。 */
class GatewayRuntime(
    private val transport: GatewayTransport,
    private val preferences: GatewayPreferences,
    private val credentials: GatewayCredentialStore,
    private val attachmentCache: GatewayAttachmentCache,
    private val networkMonitor: GatewayNetworkMonitor,
    private val clock: GatewayClock,
    private val scope: CoroutineScope,
    private val requestTimeoutMilliseconds: Long = DEFAULT_REQUEST_TIMEOUT_MILLISECONDS,
    private val recoveryWindowMilliseconds: Long = DEFAULT_RECOVERY_WINDOW_MILLISECONDS,
    private val connectionAttemptTimeoutMilliseconds: Long = DEFAULT_CONNECTION_ATTEMPT_TIMEOUT_MILLISECONDS,
    private val frameDecoder: (String) -> GatewayFrame = GatewayWireDecoder::decode,
    private val frameDecodingDispatcher: CoroutineDispatcher? = null,
    private var expectedGatewayId: String? = null,
    private val trustedEndpoints: List<String> = emptyList(),
    private val onIdentity: suspend (GatewayFrame, String) -> Unit = { _, _ -> },
    private val foregroundProbeTimeoutMilliseconds: Long = DEFAULT_FOREGROUND_PROBE_TIMEOUT_MILLISECONDS,
    private val diagnosticsNow: () -> Double = createNetworkDiagnosticsClock()
) {
    private val diagnosticsStore = NetworkDiagnosticsStore()
    private var diagnosticsLease = 0L

    /** 冷流的订阅即检测生命周期；页面消失或进入后台取消收集后，不再发探测包。 */
    fun monitorNetworkDiagnostics(connectionMethod: () -> String): Flow<NetworkDiagnosticsSnapshot> =
        flow {
            val lease = serialized {
                diagnosticsLease++
                diagnosticsStore.start(Random.nextLong().toString(16), diagnosticsNow())
                diagnosticsLease
            }
            try {
                while (true) {
                    val snapshot = serialized {
                        if (lease != diagnosticsLease) return@serialized null
                        val now = diagnosticsNow()
                        diagnosticsStore.connection(
                            connectionGeneration.toString(), connectionMethod(),
                            mutableState.value.connection == GatewayConnectionState.CONNECTED,
                            NetworkDiagnosticsStore.CAPABILITY in mutableState.value.capabilities,
                            now
                        )
                        diagnosticsStore.tick(now)
                        diagnosticsStore.nextProbe(now)?.let { id ->
                            val payload = buildJsonObject {
                                put("type", JsonPrimitive("ping"))
                                put("requestId", JsonPrimitive(id))
                            }.toString()
                            try {
                                transport.send(payload)
                                diagnosticsStore.recordTraffic(payload.encodeToByteArray().size.toLong(), 0)
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { diagnosticsStore.sendFailed(id, diagnosticsNow()) }
                        }
                        diagnosticsStore.snapshot()
                    } ?: break
                    emit(snapshot)
                    delay(1000)
                }
            } finally {
                withContext(NonCancellable) {
                    serialized { if (diagnosticsLease == lease) diagnosticsStore.stop() }
                }
            }
        }

    private val serialization = Mutex()
    private val mutableState = MutableStateFlow(
        GatewayRuntimeState(networkAvailable = networkMonitor.state.value == GatewayNetworkState.AVAILABLE)
    )
    private val eventQueue = BoundedGatewayRuntimeEventQueue(
        maximumEvents = MAXIMUM_RUNTIME_EVENT_COUNT,
        maximumBytes = MAXIMUM_RUNTIME_EVENT_BYTES
    )
    private val conversationQueue = BoundedGatewayRuntimeEventQueue(
        maximumEvents = MAXIMUM_RUNTIME_EVENT_COUNT,
        maximumBytes = MAXIMUM_RUNTIME_EVENT_BYTES
    )
    val conversationEvents: Flow<GatewayRuntimeEvent> = conversationQueue.flow

    private val pendingLanes = mutableMapOf<String, PendingLane>()
    private val deferredRequests = ArrayDeque<GatewayRequest>()
    private val activeTurnCountsBySession = mutableMapOf<String, Int>()
    private val queuedSessionIds = mutableSetOf<String>()
    private var unassociatedTurnCount = 0
    private var desiredConnection = false
    private var reconnectBlocked = false
    /** 手动配对码是一次性的；失败后绝不能在后台反复重连。 */
    private var isManualPairingAttempt = false
    private var upgradeAfterPairing = false
    private var pairingCode: String? = null
    private var endpoint: String? = null
    private var subscribedSessionId: String? = null
    private val conversationTargets = mutableMapOf<String, com.clarklevis.dsh.shared.facade.ConversationTarget>()

    suspend fun registerConversationTarget(targetJson: String) = serialized {
        val target = com.clarklevis.dsh.shared.protocol.wireJson.decodeFromString<com.clarklevis.dsh.shared.facade.ConversationTarget>(targetJson)
        conversationTargets[target.sessionId] = target
    }

    private var isInBackground = false
    private var connectionGeneration = 0L
    private var requestGeneration = 0L
    private var reconnectJob: Job? = null
    private var recoveryDeadlineJob: Job? = null
    private var connectionAttemptTimeoutJob: Job? = null
    private var foregroundProbeJob: Job? = null
    private var foregroundRetryWindowJob: Job? = null
    private var foregroundFastRetryAvailable = false
    private var lastConnectedEndpoint: String? = null
    private var foregroundResumeEndpoint: String? = null
    private var foregroundResumeWindowJob: Job? = null

    val state: StateFlow<GatewayRuntimeState> = mutableState.asStateFlow()
    /**
     * 单消费者有界事件流。collector 内不要等待 Runtime 的挂起操作（如 sendRequest）；
     * 生产者可能持有 serialization 等待队列预算。后续请求必须独立提交，先让 collector 返回。
     */
    val events: Flow<GatewayRuntimeEvent> = eventQueue.flow

    init {
        collectSafely(transport.events, ERROR_INCOMING_FLOW, serializeHandler = false, handler = ::handleTransportEvent)
        collectSafely(
            (transport as? GatewaySplitTransport)?.conversationEvents ?: emptyFlow(),
            ERROR_INCOMING_FLOW,
            serializeHandler = false,
            handler = ::handleTransportEvent
        )
        collectSafely(networkMonitor.state, ERROR_NETWORK_FLOW, handler = ::handleNetworkLocked)
    }

    suspend fun connect(endpoint: String) = serialized {
        upgradeAfterPairing = false
        (transport as? com.clarklevis.dsh.shared.platform.GatewayRtcTransport)?.resetRtcRecovery()
        requireWebSocketEndpoint(endpoint)
        require(trustedEndpoints.isEmpty() || endpoint in trustedEndpoints) { "地址未经此主机确认" }
        cancelForegroundResumeLocked()
        lastConnectedEndpoint = null
        val existing = preferences.load()
        preferences.update(existing.copy(endpoint = endpoint, automaticallyConnect = true))
        this.endpoint = endpoint
        pairingCode = null
        isManualPairingAttempt = false
        desiredConnection = true
        reconnectBlocked = false
        reconnectJob?.cancel()
        recoveryDeadlineJob?.cancel()
        recoveryDeadlineJob = null
        openTransportLocked(preserveTurns = false)
    }

    suspend fun pair(encodedPayload: String) = serialized {
        upgradeAfterPairing = false
        val payload = GatewayPairingPayloadParser.parse(encodedPayload, clock.nowEpochMilliseconds())
        GatewayIdentity.validate(expectedGatewayId, payload.gatewayId)
        cancelForegroundResumeLocked()
        lastConnectedEndpoint = null
        expectedGatewayId = payload.gatewayId ?: expectedGatewayId
        val existing = preferences.load()
        preferences.update(existing.copy(endpoint = payload.publicUrl, automaticallyConnect = true))
        endpoint = payload.publicUrl
        pairingCode = payload.pairingCode
        isManualPairingAttempt = true
        desiredConnection = true
        reconnectBlocked = false
        reconnectJob?.cancel()
        recoveryDeadlineJob?.cancel()
        recoveryDeadlineJob = null
        openTransportLocked(preserveTurns = false)
    }

    suspend fun connectStoredIfPaired(): Boolean = serialized {
        val stored = preferences.load()
        if (!stored.automaticallyConnect) return@serialized false
        if (
            desiredConnection && endpoint == stored.endpoint &&
            mutableState.value.connection in IDEMPOTENT_CONNECTION_STATES
        ) {
            return@serialized true
        }
        val token = runCatching { credentials.loadToken(stored.endpoint) }.getOrElse {
            failOpenLocked(ERROR_CREDENTIAL_ACCESS)
            return@serialized false
        }
        if (token.isNullOrBlank()) return@serialized false
        requireWebSocketEndpoint(stored.endpoint)
        cancelForegroundResumeLocked()
        lastConnectedEndpoint = null
        endpoint = stored.endpoint
        pairingCode = null
        isManualPairingAttempt = false
        desiredConnection = true
        reconnectBlocked = false
        reconnectJob?.cancel()
        recoveryDeadlineJob?.cancel()
        recoveryDeadlineJob = null
        openTransportLocked(preserveTurns = false)
        true
    }

    suspend fun disconnect() = serialized {
        upgradeAfterPairing = false
        desiredConnection = false
        reconnectBlocked = false
        pairingCode = null
        isManualPairingAttempt = false
        reconnectJob?.cancel()
        reconnectJob = null
        recoveryDeadlineJob?.cancel()
        recoveryDeadlineJob = null
        connectionAttemptTimeoutJob?.cancel()
        connectionAttemptTimeoutJob = null
        connectionGeneration += 1
        clearConnectionWorkLocked()
        runCatching { transport.close() }
        mutableState.value = mutableState.value.copy(
            connection = GatewayConnectionState.DISCONNECTED,
            reconnectAttempt = 0,
            lastError = null
        )
        val stored = preferences.load()
        preferences.update(stored.copy(automaticallyConnect = false))
    }

    suspend fun applicationDidEnterBackground() = serialized {
        // 切后台不代表用户取消连接；空闲时不启动前台服务，也不主动关闭健康 socket。
        isInBackground = true
        cancelForegroundResumeLocked()
        cancelForegroundProbeLocked()
        updateRequestTimeoutsLocked()
    }

    fun applicationDidBecomeActive() {
        scope.launch {
            serialized {
                val wasInBackground = isInBackground
                isInBackground = false
                updateRequestTimeoutsLocked()
                // 恢复窗口或普通连接握手超时只结束本次尝试；回到前台允许重新连接。
                // 鉴权、身份校验和一次性配对失败仍保留各自的阻断条件。
                if (desiredConnection && !isManualPairingAttempt &&
                    mutableState.value.lastError in setOf(ERROR_RECOVERY_TIMEOUT, ERROR_CONNECTION_TIMEOUT)) {
                    reconnectBlocked = false
                }
                if (canReconnectLocked()) {
                    if (wasInBackground) startForegroundResumeLocked()
                    if (mutableState.value.connection == GatewayConnectionState.CONNECTED) {
                        if (wasInBackground) {
                            probeForegroundConnectionLocked()
                            sendRequestLocked(GatewayRequests.simple("sessions"))
                        }
                    } else if (wasInBackground && foregroundResumeEndpoint != null) {
                        // An attempt started before backgrounding may be stuck on an unreachable fallback.
                        // A real foreground transition gets one fresh attempt at the last authenticated endpoint.
                        endpoint = foregroundResumeEndpoint
                        cancelForegroundResumeLocked()
                        reconnectJob?.cancel()
                        openTransportLocked(preserveTurns = true)
                    } else if (connectionAttemptTimeoutJob?.isActive != true) {
                        // 回前台直接尝试上一次可用地址，并取消后台退避等待。
                        scheduleReconnectLocked(immediate = true)
                    }
                }
            }
        }
    }

    suspend fun sendRequest(request: GatewayRequest): Boolean = serialized { sendRequestLocked(request) }

    suspend fun requestSessions(): Boolean = sendRequest(GatewayRequests.simple("sessions"))

    suspend fun requestHistory(
        sessionId: String,
        beforeSequence: Int? = null,
        maxMessages: Int = 50,
        maxBytes: Int? = null,
        view: String? = null,
        historyFormatVersion: Int? = null
    ): Boolean = serialized {
        if (beforeSequence != null && historyFormatVersion == null) {
            return@serialized sendRequestLocked(GatewayRequests.history(sessionId, maxMessages = maxMessages,
                maxBytes = maxBytes, view = view))
        }
        sendRequestLocked(GatewayRequests.history(sessionId, beforeSequence, maxMessages, maxBytes, view,
            historyFormatVersion))
    }

    /** 后台预加载只允许占用空闲 lane，绝不入队、合并或取消前台请求。 */
    suspend fun tryRequestHistoryInBackground(
        sessionId: String,
        maxMessages: Int = 80,
        maxBytes: Int = 256 * 1024,
        view: String? = null,
        historyFormatVersion: Int? = null
    ): Boolean = serialized {
        if (mutableState.value.connection != GatewayConnectionState.CONNECTED || pendingLanes["history"] != null) {
            return@serialized false
        }
        // 后台请求也必须走地址装饰，子智能体不能退化为裸 sessionId。
        sendRequestLocked(GatewayRequests.history(sessionId, maxMessages = maxMessages, maxBytes = maxBytes,
            view = view, historyFormatVersion = historyFormatVersion).copy(timeoutMilliseconds = 10_000, isBackgroundHistory = true))
    }

    suspend fun requestAttachment(sessionId: String, attachmentId: String): Boolean =
        sendRequest(GatewayRequests.attachment(sessionId, attachmentId))

    suspend fun readCachedAttachment(sessionId: String, attachmentId: String): ByteArray? =
        runCatching { attachmentCache.read(gatewayAttachmentCacheKey(sessionId, attachmentId)) }.getOrNull()

    suspend fun subscribe(sessionId: String?): Boolean = serialized {
        subscribedSessionId = sessionId?.takeIf(String::isNotBlank)
        sendRequestLocked(GatewayRequests.subscribe(subscribedSessionId))
    }

    suspend fun sendMessage(
        text: String,
        images: List<GatewayOutgoingImage>,
        sessionId: String?,
        workspaceId: String?,
        clientTimeZone: String,
        mode: String = "queue",
        receipts: List<String> = emptyList(),
        requestId: String? = null
    ): Boolean = serialized {
        if (text.isBlank() && images.isEmpty() && receipts.isEmpty()) return@serialized false
        if (!outgoingImagesAreWithinLimits(images)) {
            rejectLocked("message", ERROR_IMAGE_LIMITS)
            return@serialized false
        }
        markTurnLocked(sessionId)
        val sent = sendRequestLocked(
            GatewayRequests.message(text.trim(), images, sessionId, workspaceId, clientTimeZone, mode, receipts, requestId)
        )
        if (!sent) releaseTurnLocked(sessionId)
        sent
    }

    suspend fun executeCommand(
        line: String,
        images: List<GatewayOutgoingImage>,
        sessionId: String?
    ): Boolean = serialized {
        val targetSessionId = sessionId?.takeIf(String::isNotBlank) ?: return@serialized false
        if (line.isBlank()) return@serialized false
        if (!outgoingImagesAreWithinLimits(images)) {
            rejectLocked("command-execute", ERROR_IMAGE_LIMITS)
            return@serialized false
        }
        sendRequestLocked(GatewayRequests.commandExecute(targetSessionId, line.trim(), images))
    }

    suspend fun answerQuestion(
        rpcId: String,
        sessionId: String,
        answers: List<GatewayQuestionAnswer>
    ): Boolean = sendRequest(GatewayRequests.questionAnswer(rpcId, sessionId, answers))

    suspend fun cancelQuestion(rpcId: String, sessionId: String): Boolean =
        sendRequest(GatewayRequests.questionCancel(rpcId, sessionId))

    private suspend fun sendRequestLocked(input: GatewayRequest): Boolean {
        val target = conversationTargets[input.targetSessionId]
        val payload = target?.let { runCatching { it.decorate(input.payload) }.getOrNull() } ?: if (target == null) input.payload else {
            rejectLocked(input.requestType, "subagent/not-resumable", input.targetSessionId, input.correlationId)
            return false
        }
        val request = input.copy(payload = payload)

        if (mutableState.value.connection != GatewayConnectionState.CONNECTED) {
            rejectLocked(
                request.requestType,
                ERROR_NOT_CONNECTED,
                request.targetSessionId,
                request.correlationId
            )
            return false
        }
        val lane = pendingLanes[request.responseKind]
        if (lane != null) {
            when (request.lanePolicy) {
                GatewayRequestLanePolicy.REJECT_IF_BUSY -> {
                    rejectLocked(
                        request.requestType,
                        ERROR_REQUEST_BUSY,
                        request.targetSessionId,
                        request.correlationId
                    )
                    return false
                }
                GatewayRequestLanePolicy.FIFO -> lane.queued.addLast(request)
                GatewayRequestLanePolicy.COALESCE_LATEST -> {
                    while (lane.queued.isNotEmpty()) {
                        emitCancelledLocked(lane.queued.removeFirst(), ERROR_COALESCED)
                    }
                    lane.queued.addLast(request)
                }
            }
            emitEventLocked(GatewayRuntimeEvent.RequestQueued(request.requestType, request.responseKind))
            return true
        }
        return sendAsActiveLocked(request)
    }

    private suspend fun sendAsActiveLocked(request: GatewayRequest): Boolean {
        val pendingId = ++requestGeneration
        val transportGeneration = connectionGeneration
        val pending = PendingRequest(request, transportGeneration, pendingId)
        pendingLanes[request.responseKind] = PendingLane(
            active = pending,
            queued = ArrayDeque()
        )
        updateRequestTimeoutsLocked()
        return runCatching { transport.send(request.payload) }.fold(
            onSuccess = {
                if (diagnosticsStore.isActive) diagnosticsStore.recordTraffic(request.payload.encodeToByteArray().size.toLong(), 0)
                true
            },
            onFailure = { error ->
                pendingLanes.remove(request.responseKind)?.active?.timeoutJob?.cancel()
                rejectLocked(
                    request.requestType,
                    if (error.message == "transport/ws-required") "transport/ws-required" else ERROR_SEND_FAILED,
                    request.targetSessionId,
                    request.correlationId,
                    request.isBackgroundHistory
                )
                false
            }
        )
    }

    private suspend fun completeLaneLocked(responseKind: String) {
        val lane = pendingLanes.remove(responseKind) ?: return
        if (responseKind == "pong") {
            foregroundProbeJob?.cancel()
            foregroundProbeJob = null
            // 控制通道 Pong 可能先于后台积压的对话通道 failure 到达。
            // 前台恢复窗口内仍保留一次立即恢复机会，避免重新进入退避。
        }
        lane.active.timeoutJob?.cancel()
        val queued = lane.queued.removeFirstOrNull() ?: return
        // 正常响应已经确定 active 请求结束；下一请求必须沿用当前 socket 串行发送。
        // generation 回收只留给 timeout/transport failure，否则 hello 后的重复快照会形成重连闭环。
        if (mutableState.value.connection == GatewayConnectionState.CONNECTED) {
            if (sendAsActiveLocked(queued)) {
                val nextLane = pendingLanes[responseKind]
                while (lane.queued.isNotEmpty()) {
                    nextLane?.queued?.addLast(lane.queued.removeFirst())
                }
            } else {
                while (lane.queued.isNotEmpty()) deferredRequests.addLast(lane.queued.removeFirst())
                recycleConnectionForDeferredLocked()
            }
        } else {
            deferredRequests.addLast(queued)
            while (lane.queued.isNotEmpty()) deferredRequests.addLast(lane.queued.removeFirst())
        }
    }

    private suspend fun openTransportLocked(preserveTurns: Boolean) {
        cancelForegroundProbeLocked()
        connectionAttemptTimeoutJob?.cancel()
        connectionAttemptTimeoutJob = null
        if (!networkAvailableLocked()) {
            if (isManualPairingAttempt) {
                reconnectBlocked = true
                mutableState.value = mutableState.value.copy(
                    connection = GatewayConnectionState.FAILED,
                    lastError = ERROR_NETWORK_LOST
                )
                return
            }
            mutableState.value = mutableState.value.copy(connection = GatewayConnectionState.WAITING_FOR_NETWORK)
            return
        }
        if (isInBackground && !mutableState.value.shouldKeepAliveInBackground) {
            mutableState.value = mutableState.value.copy(connection = GatewayConnectionState.SUSPENDED)
            return
        }
        val target = endpoint ?: return
        if (trustedEndpoints.isNotEmpty() && target !in trustedEndpoints) {
            reconnectBlocked = true
            desiredConnection = false
            failOpenLocked("untrusted-gateway-endpoint")
            return
        }
        val immutablePairingCode = pairingCode
        val credentialSnapshot = runCatching {
            credentials.loadOrCreateDeviceId() to
                if (immutablePairingCode == null) credentials.loadToken(target) else null
        }.getOrElse {
            failOpenLocked(ERROR_CREDENTIAL_ACCESS)
            return
        }
        val spec = GatewayConnectionSpec(
            generation = ++connectionGeneration,
            endpoint = target,
            deviceId = credentialSnapshot.first,
            bearerToken = credentialSnapshot.second,
            pairingCode = immutablePairingCode,
            expectedGatewayId = expectedGatewayId
        )
        cancelPendingLocked(ERROR_CONNECTION_REPLACED)
        if (!preserveTurns) {
            while (deferredRequests.isNotEmpty()) {
                emitCancelledLocked(deferredRequests.removeFirst(), ERROR_CONNECTION_REPLACED)
            }
            clearTurnsLocked()
        }
        mutableState.value = mutableState.value.copy(
            connection = GatewayConnectionState.CONNECTING,
            endpoint = target,
            lastError = null
        )
        runCatching { transport.open(spec) }
            .onSuccess { startConnectionAttemptTimeoutLocked(spec.generation) }
            .onFailure { failOpenLocked(ERROR_OPEN_FAILED) }
    }

    private suspend fun handleTransportStateLocked(transportState: GatewayTransportState) {
        if (transportState.generation != 0L && transportState.generation != connectionGeneration) return
        when (transportState) {
            is GatewayTransportState.Closed -> Unit
            is GatewayTransportState.Opening -> mutableState.value = mutableState.value.copy(
                connection = GatewayConnectionState.CONNECTING
            )
            is GatewayTransportState.Open -> mutableState.value = mutableState.value.copy(
                connection = GatewayConnectionState.AUTHENTICATING
            )
            is GatewayTransportState.Failed -> handleTransportFailureLocked(transportState)
        }
    }

    private suspend fun handleTransportFailureLocked(failure: GatewayTransportState.Failed) {
        val resumeEndpoint = foregroundResumeEndpoint.takeIf { !isInBackground }
        val immediateForegroundRetry = (foregroundFastRetryAvailable || resumeEndpoint != null) && !isInBackground
        cancelForegroundResumeLocked()
        cancelForegroundProbeLocked()
        // failure 与 frame 共用有序通道；处理 failure 时立即作废代次，随后到达的旧帧只能被拒绝。
        connectionAttemptTimeoutJob?.cancel()
        connectionAttemptTimeoutJob = null
        connectionGeneration += 1
        val authenticationFailure = failure.httpStatus == 401 || failure.closeCode == 4003
        if (failure.httpStatus == 401 && pairingCode == null) {
            endpoint?.let { runCatching { credentials.deleteToken(it) } }
        }
        if (authenticationFailure || !failure.recoverable || isManualPairingAttempt) reconnectBlocked = true
        cancelPendingLocked(failure.stableErrorCode())
        val shouldReconnect = canReconnectLocked() && failure.recoverable && !authenticationFailure
        if (!shouldReconnect) clearTurnsLocked() else startRecoveryDeadlineLocked()
        mutableState.value = mutableState.value.copy(
            connection = if (shouldReconnect) GatewayConnectionState.CONNECTING else GatewayConnectionState.FAILED,
            lastError = failure.stableErrorCode()
        )
        if (shouldReconnect) {
            if (resumeEndpoint != null) endpoint = resumeEndpoint
            scheduleReconnectLocked(immediate = immediateForegroundRetry)
        }
    }

    private data class FrameDelivery(val event: GatewayRuntimeEvent.Frame, val bytes: Long)

    private suspend fun handleTransportEvent(event: GatewayTransportEvent) {
        when (event) {
            is GatewayTransportEvent.State -> serialized { handleTransportStateLocked(event.value) }
            is GatewayTransportEvent.Frame -> {
                // 大 history 页的解码和对话队列背压均不得占用控制请求的锁。
                val frame = try {
                    frameDecodingDispatcher?.let { dispatcher ->
                        withContext(dispatcher) { frameDecoder(event.value.text) }
                    } ?: frameDecoder(event.value.text)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    val detail = GatewayWireDecoder.failureSummary(event.value.text, error)
                    serialized { rejectLocked("decode", detail) }
                    return
                }
                val delivery = serialized { handleIncomingFrameLocked(event.value, frame) } ?: return
                val queue = if (transport is GatewaySplitTransport && frame.kind in setOf(
                    "hello", "event", "history", "subscribed", "session-snapshot", "assistant-stream", "session-stream-reset"
                )) {
                    conversationQueue
                } else eventQueue
                queue.emit(delivery.event, delivery.bytes)
            }
        }
    }

    private suspend fun handleIncomingFrameLocked(transportFrame: GatewayTransportFrame, frame: GatewayFrame): FrameDelivery? {
        if (transportFrame.generation != connectionGeneration) {
            rejectLocked("transport", ERROR_STALE_FRAME)
            return null
        }
        diagnosticsStore.recordTraffic(0, transportFrame.byteCount.toLong())
        if (frame.kind == "pong" && frame.requestId?.startsWith(
                NetworkDiagnosticsStore.PROBE_PREFIX
            ) == true) {
            diagnosticsStore.acceptPong(frame.requestId, diagnosticsNow())
            return null
        }
        if (frame.kind == "hello" && isManualPairingAttempt && pairingCode != null) {
            reconnectBlocked = true
            desiredConnection = false
            failOpenLocked(ERROR_PAIR_TOKEN_MISSING)
            return null
        }
        if (frame.kind == "paired" || frame.kind == "hello") {
            try {
                GatewayIdentity.validate(expectedGatewayId, frame.gatewayId)
                onIdentity(frame, endpoint.orEmpty())
                expectedGatewayId = frame.gatewayId ?: expectedGatewayId
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                reconnectBlocked = true
                desiredConnection = false
                failOpenLocked("gateway-identity-mismatch")
                return null
            }
        }
        if (frame.kind == "paired") {
            val target = endpoint
            val token = frame.token
            if (target == null || token.isNullOrBlank()) {
                reconnectBlocked = true
                desiredConnection = false
                failOpenLocked(ERROR_PAIR_TOKEN_MISSING)
                return null
            }
            if (runCatching { credentials.saveToken(target, token) }.isFailure) {
                reconnectBlocked = true
                desiredConnection = false
                failOpenLocked(ERROR_CREDENTIAL_ACCESS)
                return null
            }
            pairingCode = null
            upgradeAfterPairing = transport is com.clarklevis.dsh.shared.platform.GatewayRtcTransport &&
                com.clarklevis.dsh.shared.transport.GatewayTransportPolicy.allowsRtc(target)
        }
        if (frame.kind == "hello" && upgradeAfterPairing) {
            // 长期凭据已提交成功；换新鉴权连接协商，旧配对连接不执行业务。
            upgradeAfterPairing = false
            isManualPairingAttempt = false
            openTransportLocked(preserveTurns = false)
            return null
        }
        if (frame.kind == "hello") {
            try {
                (transport as? GatewaySplitTransport)?.confirmControlHandshake()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Throwable) { failOpenLocked("conversation-open-failed"); return null }
            connectionAttemptTimeoutJob?.cancel()
            connectionAttemptTimeoutJob = null
            isManualPairingAttempt = false
            recoveryDeadlineJob?.cancel()
            recoveryDeadlineJob = null
            lastConnectedEndpoint = endpoint
            mutableState.value = mutableState.value.copy(
                connection = GatewayConnectionState.CONNECTED,
                serverPort = frame.port,
                clientCount = frame.clients,
                protocolVersion = frame.protocolVersion ?: 1,
                capabilities = frame.capabilities.orEmpty().toSet(),
                reconnectAttempt = 0,
                lastError = null
            )
            replayAfterHelloLocked()
        }

        val correlation = correlateLocked(frame)
        if (!correlation.accepted) return null
        if (frame.kind == "error") {
            if (frame.resetRequired == true || frame.requestType?.startsWith("speech-") == true ||
                frame.requestType?.startsWith("file-") == true ||
                frame.requestType in setOf("session-agent-preset", "select-agent-preset", "agent-team", "subagents") ||
                conversationTargets[frame.sessionId]?.parentSessionId != null) {
                return FrameDelivery(GatewayRuntimeEvent.Frame(transportFrame.text, frame, correlation.sessionId, correlation.isBackgroundHistory),
                    transportFrame.byteCount.toLong())
            }
            rejectLocked(
                frame.requestType ?: "gateway",
                ERROR_GATEWAY_REQUEST,
                correlation.sessionId,
                responseCorrelation(frame),
                correlation.isBackgroundHistory
            )
            return null
        }
        if (frame.kind == "sent" && unassociatedTurnCount > 0 && !frame.sessionId.isNullOrBlank()) {
            unassociatedTurnCount -= 1
            activeTurnCountsBySession[frame.sessionId] =
                activeTurnCountsBySession.getOrElse(frame.sessionId) { 0 } + 1
            publishTurnStateLocked()
        }
        if ((frame.kind == "sent" || (frame.kind == "event" && frame.event?.type == "turn/start")) && !frame.sessionId.isNullOrBlank()) {
            activeTurnCountsBySession[frame.sessionId] = 1
            publishTurnStateLocked()
        }
        if (frame.kind == "session-queues" && frame.queues != null) {
            queuedSessionIds.clear()
            queuedSessionIds.addAll(frame.queues.filterValues { items -> items.any { it["placement"]?.stringValue == "queued" } }.keys)
            publishTurnStateLocked()
        }
        if (frame.kind == "session-queue" && !frame.sessionId.isNullOrBlank()) {
            if (frame.items.orEmpty().any { it["placement"]?.stringValue == "queued" }) queuedSessionIds.add(frame.sessionId)
            else queuedSessionIds.remove(frame.sessionId)
            publishTurnStateLocked()
        }
        val endedLastBackgroundTurn = frame.kind == "event" && frame.event?.type == "turn/end"
        if (endedLastBackgroundTurn) releaseTurnLocked(frame.sessionId)
        if (frame.kind == "attachment") {
            val cached = cacheAttachmentLocked(frame)
            completeLaneLocked("attachment")
            if (!cached) return null
        }
        val safeFrame = when (frame.kind) {
            "paired" -> frame.copy(token = null)
            "attachment" -> frame.copy(data = null)
            else -> frame
        }
        val rawWasCleaned = frame.kind == "paired" || frame.kind == "attachment"
        val safeRaw = if (rawWasCleaned) wireJson.encodeToString(safeFrame) else transportFrame.text
        val estimatedBytes = if (rawWasCleaned) {
            safeRaw.length.toLong() * 2 + FRAME_OBJECT_OVERHEAD_BYTES
        } else {
            transportFrame.byteCount.toLong() * FRAME_RETENTION_MULTIPLIER + FRAME_OBJECT_OVERHEAD_BYTES
        }
        return FrameDelivery(GatewayRuntimeEvent.Frame(safeRaw, safeFrame, correlation.sessionId, correlation.isBackgroundHistory), estimatedBytes)
    }

    private suspend fun correlateLocked(frame: GatewayFrame): Correlation {
        // 成员资料使用有 requestId 的独立只读通道，不占用或完成前台 history lane。
        val profileId = frame.requestId?.takeIf { it.startsWith("team-profile-") }
        if (profileId != null && frame.kind in setOf("history", "error")) {
            val lane = pendingLanes[profileId] ?: return Correlation(false, null)
            val request = lane.active.request
            if (request.requestType != "history" || request.correlationId != profileId ||
                lane.active.generation != connectionGeneration || !sessionMatches(request, frame.sessionId)) {
                return Correlation(false, null)
            }
            completeLaneLocked(profileId)
            return Correlation(true, frame.sessionId ?: request.targetSessionId)
        }
        if (frame.kind in UNCORRELATED_KINDS) return Correlation(true, frame.sessionId)
        if (frame.kind == "error") {
            val requestType = frame.requestType
            if (requestType == null) {
                cancelPendingLocked(ERROR_GATEWAY_REQUEST, releaseMessageTurns = true)
                return Correlation(true, frame.sessionId)
            }
            val entry = pendingLanes.entries.firstOrNull { it.value.active.request.requestType == requestType }
            if (entry == null) {
                // 订阅回执已完成，后续服务撤销仍交由语音 Store 校验订阅 ID。
                if (requestType == "speech-subscribe" && frame.requestId != null) return Correlation(true, null)
                rejectLocked("gateway", ERROR_NO_ACTIVE_REQUEST, frame.sessionId)
                return Correlation(false, frame.sessionId)
            }
            val request = entry.value.active.request
            if (!sessionMatches(request, frame.sessionId)) {
                rejectLocked(
                    "transport",
                    ERROR_SESSION_MISMATCH,
                    frame.sessionId,
                    responseCorrelation(frame)
                )
                return Correlation(false, null)
            }
            if (request.correlationId != null) {
                val explicitCorrelation = explicitResponseCorrelation(request, frame)
                if (explicitCorrelation == null) {
                cancelLaneLocked(entry.key, ERROR_GATEWAY_REQUEST)
                    return Correlation(false, request.targetSessionId)
                }
                if (request.correlationId != explicitCorrelation) {
                    rejectLocked(
                        "transport",
                        ERROR_SESSION_MISMATCH,
                        frame.sessionId,
                        explicitCorrelation
                    )
                    return Correlation(false, null)
                }
            }
            if (requestType == "message") releaseTurnLocked(request.targetSessionId)
            completeLaneLocked(entry.key)
            return Correlation(true, frame.sessionId ?: request.targetSessionId, request.isBackgroundHistory)
        }
        val lane = pendingLanes[frame.kind]
        if (lane == null) {
            if (frame.kind in RESPONSE_KINDS_REQUIRING_ACTIVE_REQUEST) {
                rejectLocked(
                    frame.kind,
                    ERROR_NO_ACTIVE_REQUEST,
                    frame.sessionId,
                    responseCorrelation(frame)
                )
                return Correlation(false, null)
            }
            return Correlation(true, frame.sessionId)
        }
        if (
            lane.active.generation != connectionGeneration ||
            !sessionMatches(lane.active.request, frame.sessionId) ||
            !responseCorrelationMatches(lane.active.request, frame)
        ) {
            rejectLocked(
                "transport",
                ERROR_SESSION_MISMATCH,
                frame.sessionId,
                responseCorrelation(frame)
            )
            return Correlation(false, null)
        }
        // 有后续切换时，旧确认只释放单飞通道，禁止建立旧订阅基线（包括 A→B→A）。
        val supersededSubscription = frame.kind == "subscribed" && lane.queued.isNotEmpty()
        val sessionId = frame.sessionId ?: lane.active.request.targetSessionId
        val isBackgroundHistory = lane.active.request.isBackgroundHistory
        if (frame.kind != "attachment") completeLaneLocked(frame.kind)
        return Correlation(!supersededSubscription, sessionId, isBackgroundHistory)
    }

    private fun sessionMatches(request: GatewayRequest, explicitSessionId: String?): Boolean =
        explicitSessionId == null || request.targetSessionId == null || explicitSessionId == request.targetSessionId

    private fun responseCorrelationMatches(request: GatewayRequest, frame: GatewayFrame): Boolean =
        when (request.responseKind) {
            "sent" -> request.correlationId == null || request.correlationId == frame.requestId
            "queue-item-updated" -> request.correlationId == frame.itemId
            "attachment" -> request.correlationId == frame.attachment?.attachmentId
            "question-response", "approval-response" -> request.correlationId == frame.rpcId
            "speech-catalog", "speech-subscribed", "speech-unsubscribed", "speech-preparing",
            "speech-preparation-cancelled", "speech-transcript", "speech-cancelled",
            "file-upload-completed" -> request.correlationId == frame.requestId || (frame.uploadId != null && runCatching {
                val payload = wireJson.parseToJsonElement(request.payload) as kotlinx.serialization.json.JsonObject
                (payload["uploadId"] as? JsonPrimitive)?.content == frame.uploadId
            }.getOrDefault(false))
            "file-upload-opened", "file-upload-chunk-accepted", "file-upload-cancelled",
            "session-created", "session-agent-preset", "select-agent-preset", "file-resolved", "file-list", "file-download-opened" -> request.correlationId == frame.requestId
            "file-download-chunk", "file-download-cancelled" -> request.correlationId == frame.transferId
            "command-options", "command-selected" -> request.correlationId == frame.command?.stringValue
            else -> true
        }

    private fun responseCorrelation(frame: GatewayFrame): String? =
        frame.itemId ?: frame.attachment?.attachmentId ?: frame.rpcId ?: frame.requestId ?: frame.transferId ?:
            frame.command?.stringValue

    private fun explicitResponseCorrelation(request: GatewayRequest, frame: GatewayFrame): String? =
        when (request.responseKind) {
            "queue-item-updated" -> frame.itemId
            "attachment" -> frame.attachment?.attachmentId
            "question-response", "approval-response" -> frame.rpcId
            "speech-catalog", "speech-subscribed", "speech-unsubscribed", "speech-preparing",
            "speech-preparation-cancelled", "speech-transcript", "speech-cancelled",
            "sent", "file-upload-opened", "file-upload-chunk-accepted", "file-upload-completed", "file-upload-cancelled",
            "session-created", "session-agent-preset", "select-agent-preset", "file-resolved", "file-list", "file-download-opened" -> frame.requestId
            "file-download-chunk", "file-download-cancelled" -> frame.transferId
            "command-options", "command-selected" -> frame.command?.stringValue
            else -> null
        }

    private fun outgoingImagesAreWithinLimits(images: List<GatewayOutgoingImage>): Boolean {
        if (images.size > MAXIMUM_OUTGOING_IMAGE_COUNT) return false
        var totalCharacters = 0L
        images.forEach { image ->
            if (!image.mediaType.startsWith("image/") || image.base64Data.length > MAXIMUM_OUTGOING_IMAGE_CHARACTERS) {
                return false
            }
            totalCharacters += image.base64Data.length
            if (totalCharacters > MAXIMUM_OUTGOING_TOTAL_BASE64_CHARACTERS) return false
        }
        return true
    }

    private suspend fun cacheAttachmentLocked(frame: GatewayFrame): Boolean {
        if (frame.kind != "attachment") return true
        val attachment = frame.attachment
        val bytes = frame.data?.let(::decodeBase64)
        if (
            attachment == null || bytes == null || attachment.bytes < 0 ||
            bytes.size != attachment.bytes || bytes.size > MAXIMUM_ATTACHMENT_BYTES
        ) {
            rejectLocked(
                "attachment",
                ERROR_ATTACHMENT_INVALID,
                frame.sessionId,
                frame.attachment?.attachmentId
            )
            return false
        }
        val sessionId = frame.sessionId?.takeIf(String::isNotBlank)
        if (sessionId == null) {
            rejectLocked("attachment", ERROR_ATTACHMENT_INVALID, null, attachment.attachmentId)
            return false
        }
        val committed = runCatching {
            attachmentCache.write(gatewayAttachmentCacheKey(sessionId, attachment.attachmentId), bytes)
        }
            .getOrDefault(false)
        if (!committed) {
            rejectLocked(
                "attachment",
                ERROR_ATTACHMENT_CACHE_WRITE,
                frame.sessionId,
                attachment.attachmentId
            )
            return false
        }
        emitEventLocked(GatewayRuntimeEvent.AttachmentCached(sessionId, attachment.attachmentId))
        return true
    }

    private suspend fun handleNetworkLocked(network: GatewayNetworkState) {
        val available = network == GatewayNetworkState.AVAILABLE
        mutableState.value = mutableState.value.copy(networkAvailable = available)
        if (!available) {
            reconnectJob?.cancel()
            connectionGeneration += 1
            runCatching { transport.close() }
            cancelPendingLocked(ERROR_NETWORK_LOST)
            if (isManualPairingAttempt) {
                connectionAttemptTimeoutJob?.cancel()
                connectionAttemptTimeoutJob = null
                reconnectBlocked = true
                clearTurnsLocked()
                mutableState.value = mutableState.value.copy(
                    connection = GatewayConnectionState.FAILED,
                    lastError = ERROR_NETWORK_LOST
                )
                return
            }
            startRecoveryDeadlineLocked()
            if (desiredConnection && !reconnectBlocked) {
                mutableState.value = mutableState.value.copy(
                    connection = GatewayConnectionState.WAITING_FOR_NETWORK,
                    lastError = null
                )
            }
        } else if (canReconnectLocked()) {
            scheduleReconnectLocked(immediate = true)
        }
    }

    private suspend fun handleCollectorFailureLocked(code: String) {
        connectionGeneration += 1
        clearConnectionWorkLocked()
        mutableState.value = mutableState.value.copy(connection = GatewayConnectionState.FAILED, lastError = code)
        rejectLocked("transport", code)
    }

    private suspend fun failOpenLocked(code: String) {
        connectionGeneration += 1
        connectionAttemptTimeoutJob?.cancel()
        connectionAttemptTimeoutJob = null
        runCatching { transport.close() }
        clearConnectionWorkLocked()
        mutableState.value = mutableState.value.copy(connection = GatewayConnectionState.FAILED, lastError = code)
    }

    private suspend fun clearConnectionWorkLocked() {
        cancelForegroundResumeLocked()
        cancelPendingLocked(ERROR_CONNECTION_CLOSED)
        while (deferredRequests.isNotEmpty()) {
            emitCancelledLocked(deferredRequests.removeFirst(), ERROR_CONNECTION_CLOSED)
        }
        clearTurnsLocked()
    }

    private suspend fun cancelPendingLocked(reason: String, releaseMessageTurns: Boolean = false) {
        cancelForegroundProbeLocked()
        pendingLanes.values.forEach { lane ->
            lane.active.timeoutJob?.cancel()
            if (releaseMessageTurns && lane.active.request.requestType == "message") {
                releaseTurnLocked(lane.active.request.targetSessionId)
            }
            emitCancelledLocked(lane.active.request, reason)
            while (lane.queued.isNotEmpty()) emitCancelledLocked(lane.queued.removeFirst(), reason)
        }
        pendingLanes.clear()
    }

    private suspend fun cancelLaneLocked(responseKind: String, reason: String) {
        if (responseKind == "pong") cancelForegroundProbeLocked()
        val lane = pendingLanes.remove(responseKind) ?: return
        lane.active.timeoutJob?.cancel()
        if (lane.active.request.requestType == "message") {
            releaseTurnLocked(lane.active.request.targetSessionId)
        }
        emitCancelledLocked(lane.active.request, reason)
        while (lane.queued.isNotEmpty()) emitCancelledLocked(lane.queued.removeFirst(), reason)
    }

    private fun clearTurnsLocked() {
        queuedSessionIds.clear()
        activeTurnCountsBySession.clear()
        unassociatedTurnCount = 0
        publishTurnStateLocked()
    }

    private suspend fun handleRequestTimeoutLocked(
        responseKind: String,
        pendingId: Long,
        transportGeneration: Long
    ) {
        val lane = pendingLanes[responseKind] ?: return
        if (lane.active.id != pendingId || lane.active.generation != transportGeneration) return
        if (responseKind.startsWith("team-profile-")) {
            pendingLanes.remove(responseKind)
            lane.active.timeoutJob = null
            emitEventLocked(GatewayRuntimeEvent.RequestTimedOut(lane.active.request.requestType,
                lane.active.request.targetSessionId, lane.active.request.correlationId))
            return
        }
        if (lane.active.request.requestType.startsWith("file-upload-")) {
            lane.active.timeoutJob = null
            emitEventLocked(GatewayRuntimeEvent.RequestTimedOut(lane.active.request.requestType,
                lane.active.request.targetSessionId, lane.active.request.correlationId))
            completeLaneLocked(responseKind)
            return
        }
        if (isInBackground && !mutableState.value.shouldKeepAliveInBackground) {
            lane.active.timeoutJob = null
            return
        }
        pendingLanes.remove(responseKind)
        // 当前正在执行的 timeout job 不得取消自身，否则下一处挂起点会中止终态发布。
        if (lane.active.request.requestType == "message") {
            releaseTurnLocked(lane.active.request.targetSessionId)
        }
        while (lane.queued.isNotEmpty()) deferredRequests.addLast(lane.queued.removeFirst())
        emitEventLocked(
            GatewayRuntimeEvent.RequestTimedOut(
                lane.active.request.requestType,
                lane.active.request.targetSessionId,
                lane.active.request.correlationId,
                lane.active.request.isBackgroundHistory
            )
        )
        if (lane.active.request.requestType == "message" && runCatching {
            val payload = wireJson.parseToJsonElement(lane.active.request.payload) as kotlinx.serialization.json.JsonObject
            (payload["files"] as? kotlinx.serialization.json.JsonArray)?.isNotEmpty() == true
        }.getOrDefault(false)) return
        if (mutableState.value.connection != GatewayConnectionState.SUSPENDED) {
            recycleConnectionForDeferredLocked()
        }
    }

    private suspend fun recycleConnectionForDeferredLocked() {
        connectionGeneration += 1
        cancelPendingLocked(ERROR_CONNECTION_RECYCLED)
        runCatching { transport.close() }
        if (canReconnectLocked()) {
            mutableState.value = mutableState.value.copy(connection = GatewayConnectionState.CONNECTING)
            openTransportLocked(preserveTurns = true)
        } else {
            // socket 已因请求超时关闭，不能继续暴露 CONNECTED；前台恢复依赖真实状态。
            mutableState.value = mutableState.value.copy(
                connection = GatewayConnectionState.FAILED,
                lastError = ERROR_CONNECTION_RECYCLED
            )
        }
    }

    private fun startRecoveryDeadlineLocked() {
        if (!mutableState.value.shouldKeepAliveInBackground || recoveryDeadlineJob?.isActive == true) return
        recoveryDeadlineJob = scope.launch {
            delay(recoveryWindowMilliseconds)
            serialized {
                if (mutableState.value.connection != GatewayConnectionState.CONNECTED) {
                    reconnectJob?.cancel()
                    reconnectBlocked = true
                    connectionGeneration += 1
                    cancelPendingLocked(ERROR_RECOVERY_TIMEOUT)
                    while (deferredRequests.isNotEmpty()) {
                        emitCancelledLocked(deferredRequests.removeFirst(), ERROR_RECOVERY_TIMEOUT)
                    }
                    runCatching { transport.close() }
                    clearTurnsLocked()
                    mutableState.value = mutableState.value.copy(
                        connection = GatewayConnectionState.FAILED,
                        lastError = ERROR_RECOVERY_TIMEOUT
                    )
                }
                recoveryDeadlineJob = null
            }
        }
    }

    private suspend fun emitCancelledLocked(request: GatewayRequest, reason: String) {
        emitEventLocked(
            GatewayRuntimeEvent.RequestCancelled(
                request.requestType,
                request.targetSessionId,
                reason,
                request.correlationId,
                request.isBackgroundHistory
            )
        )
    }

    private fun markTurnLocked(sessionId: String?) {
        if (sessionId.isNullOrBlank()) {
            unassociatedTurnCount += 1
        } else {
            activeTurnCountsBySession[sessionId] = activeTurnCountsBySession.getOrElse(sessionId) { 0 } + 1
        }
        publishTurnStateLocked()
    }

    private fun releaseTurnLocked(sessionId: String?) {
        if (sessionId.isNullOrBlank()) {
            unassociatedTurnCount = (unassociatedTurnCount - 1).coerceAtLeast(0)
        } else {
            val remaining = activeTurnCountsBySession.getOrElse(sessionId) { 0 } - 1
            if (remaining > 0) activeTurnCountsBySession[sessionId] = remaining
            else activeTurnCountsBySession.remove(sessionId)
        }
        publishTurnStateLocked()
    }

    private fun publishTurnStateLocked() {
        mutableState.value = mutableState.value.copy(
            activeTurnSessionIds = activeTurnCountsBySession.keys.toSet() + queuedSessionIds,
            hasUnassociatedTurn = unassociatedTurnCount > 0
        )
        updateRequestTimeoutsLocked()
    }

    private fun updateRequestTimeoutsLocked() {
        val shouldRun = !isInBackground || mutableState.value.shouldKeepAliveInBackground
        pendingLanes.forEach { (responseKind, lane) ->
            val pending = lane.active
            if (!shouldRun) {
                pending.timeoutJob?.cancel()
                pending.timeoutJob = null
            } else if (pending.timeoutJob == null) {
                // 空闲后台不把延迟或进程暂停当成网络故障；回前台后重新给出完整响应窗口。
                // 保留原 lane 与 generation，迟到响应仍对应原请求，不会串到下一请求。
                pending.timeoutJob = scope.launch {
                    delay(pending.request.timeoutMilliseconds ?: requestTimeoutMilliseconds)
                    serialized { handleRequestTimeoutLocked(responseKind, pending.id, pending.generation) }
                }
            }
        }
    }

    private fun networkAvailableLocked(): Boolean =
        mutableState.value.networkAvailable && networkMonitor.state.value == GatewayNetworkState.AVAILABLE

    private fun canReconnectLocked(): Boolean =
        desiredConnection && !reconnectBlocked && !isManualPairingAttempt && networkAvailableLocked() &&
            (!isInBackground || mutableState.value.shouldKeepAliveInBackground)

    private fun startConnectionAttemptTimeoutLocked(generation: Long) {
        connectionAttemptTimeoutJob?.cancel()
        connectionAttemptTimeoutJob = scope.launch {
            delay(connectionAttemptTimeoutMilliseconds)
            serialized {
                if (
                    generation != connectionGeneration ||
                    mutableState.value.connection !in setOf(
                        GatewayConnectionState.CONNECTING,
                        GatewayConnectionState.AUTHENTICATING
                    )
                ) {
                    return@serialized
                }
                if (!isManualPairingAttempt && trustedEndpoints.size > 1) {
                    runCatching { transport.close() }
                    mutableState.value = mutableState.value.copy(connection = GatewayConnectionState.FAILED, lastError = ERROR_CONNECTION_TIMEOUT)
                    scheduleReconnectLocked(immediate = false)
                    return@serialized
                }
                // 一次性配对超时后不自动重用原配对码。
                reconnectJob?.cancel()
                reconnectBlocked = true
                connectionGeneration += 1
                cancelPendingLocked(ERROR_CONNECTION_TIMEOUT)
                while (deferredRequests.isNotEmpty()) {
                    emitCancelledLocked(deferredRequests.removeFirst(), ERROR_CONNECTION_TIMEOUT)
                }
                runCatching { transport.close() }
                clearTurnsLocked()
                mutableState.value = mutableState.value.copy(
                    connection = GatewayConnectionState.FAILED,
                    lastError = ERROR_CONNECTION_TIMEOUT
                )
                connectionAttemptTimeoutJob = null
            }
        }
    }

    private fun scheduleReconnectLocked(immediate: Boolean) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            if (!immediate) {
                val attempt = serialized {
                    val next = (mutableState.value.reconnectAttempt + 1).coerceAtMost(6)
                    mutableState.value = mutableState.value.copy(reconnectAttempt = next)
                    next
                }
                clock.delay((2_000L shl (attempt - 1)).coerceAtMost(30_000L))
            }
            serialized {
                if (canReconnectLocked()) {
                    if (!immediate && trustedEndpoints.size > 1) {
                        endpoint = trustedEndpoints[(trustedEndpoints.indexOf(endpoint) + 1) % trustedEndpoints.size]
                    }
                    openTransportLocked(preserveTurns = true)
                }
            }
        }
    }

    private fun startForegroundResumeLocked() {
        cancelForegroundResumeLocked()
        foregroundResumeEndpoint = lastConnectedEndpoint ?: return
        foregroundResumeWindowJob = scope.launch {
            delay(FOREGROUND_RESUME_RETRY_WINDOW_MILLISECONDS)
            serialized {
                foregroundResumeWindowJob = null
                foregroundResumeEndpoint = null
            }
        }
    }

    private fun cancelForegroundResumeLocked() {
        foregroundResumeWindowJob?.cancel()
        foregroundResumeWindowJob = null
        foregroundResumeEndpoint = null
    }

    private suspend fun probeForegroundConnectionLocked() {
        cancelForegroundProbeLocked()
        foregroundFastRetryAvailable = true
        foregroundRetryWindowJob = scope.launch {
            delay(foregroundProbeTimeoutMilliseconds)
            serialized {
                foregroundRetryWindowJob = null
                foregroundFastRetryAvailable = false
            }
        }
        val generation = connectionGeneration
        // 若已有手动 Ping 在等待，沿用同一 lane，防止无关联 ID 的 Pong 串到新请求。
        if (pendingLanes["pong"] == null && !sendRequestLocked(GatewayRequests.ping())) {
            cancelForegroundProbeLocked()
            recycleConnectionForDeferredLocked()
            return
        }
        val pendingId = pendingLanes["pong"]?.active?.id ?: return
        foregroundProbeJob = scope.launch {
            delay(foregroundProbeTimeoutMilliseconds)
            serialized {
                if (isInBackground || connectionGeneration != generation ||
                    mutableState.value.connection != GatewayConnectionState.CONNECTED ||
                    pendingLanes["pong"]?.active?.id != pendingId) {
                    return@serialized
                }
                // 不等待 30 秒周期心跳或普通请求超时；当前 job 不取消自身。
                foregroundProbeJob = null
                foregroundFastRetryAvailable = false
                recycleConnectionForDeferredLocked()
            }
        }
    }

    private fun cancelForegroundProbeLocked() {
        foregroundProbeJob?.cancel()
        foregroundProbeJob = null
        foregroundRetryWindowJob?.cancel()
        foregroundRetryWindowJob = null
        foregroundFastRetryAvailable = false
    }

    private suspend fun rejectLocked(
        requestType: String,
        code: String,
        targetSessionId: String? = null,
        correlationId: String? = null,
        isBackgroundHistory: Boolean = false
    ) {
        emitEventLocked(
            GatewayRuntimeEvent.RequestRejected(
                requestType,
                code,
                targetSessionId,
                correlationId,
                isBackgroundHistory
            )
        )
    }

    private suspend fun replayAfterHelloLocked() {
        val replay = buildList {
            while (deferredRequests.isNotEmpty()) add(deferredRequests.removeFirst())
        }
        val replayKinds = replay.mapTo(mutableSetOf(), GatewayRequest::responseKind)
        if ("workspaces" !in replayKinds) sendRequestLocked(GatewayRequests.simple("workspaces"))
        if ("sessions" !in replayKinds) sendRequestLocked(GatewayRequests.simple("sessions"))
        if ("subscribed" !in replayKinds) {
            subscribedSessionId?.let { sendRequestLocked(GatewayRequests.subscribe(it)) }
        }
        replay.forEach { sendRequestLocked(it) }
    }

    private suspend fun emitEventLocked(event: GatewayRuntimeEvent, estimatedBytes: Long = 256L) {
        eventQueue.emit(event, estimatedBytes)
    }

    private fun <T> collectSafely(flow: Flow<T>, code: String, serializeHandler: Boolean = true, handler: suspend (T) -> Unit) {
        scope.launch {
            try {
                flow.collect { value ->
                    try {
                        if (serializeHandler) serialized { handler(value) } else handler(value)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        serialized { handleCollectorFailureLocked(code) }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                serialized { handleCollectorFailureLocked(code) }
            }
        }
    }

    private suspend fun <T> serialized(block: suspend () -> T): T {
        serialization.lock()
        return try {
            block()
        } finally {
            serialization.unlock()
        }
    }

    private data class PendingRequest(
        val request: GatewayRequest,
        val generation: Long,
        val id: Long,
        var timeoutJob: Job? = null
    )
    private data class PendingLane(
        val active: PendingRequest,
        val queued: ArrayDeque<GatewayRequest>
    )
    private data class Correlation(val accepted: Boolean, val sessionId: String?, val isBackgroundHistory: Boolean = false)

    private fun GatewayTransportState.Failed.stableErrorCode(): String = when {
        httpStatus == 401 -> ERROR_AUTH_REQUIRED
        httpStatus == 503 -> ERROR_GATEWAY_UNAVAILABLE
        closeCode == 4003 -> ERROR_AUTH_REQUIRED
        closeCode == 4004 -> ERROR_GATEWAY_DISABLED
        else -> reason?.takeIf { it in STABLE_TRANSPORT_CODES } ?: ERROR_TRANSPORT_FAILED
    }

    companion object {
        private const val DEFAULT_REQUEST_TIMEOUT_MILLISECONDS = 30_000L
        private const val DEFAULT_RECOVERY_WINDOW_MILLISECONDS = 60_000L
        private const val DEFAULT_CONNECTION_ATTEMPT_TIMEOUT_MILLISECONDS = 15_000L
        private const val DEFAULT_FOREGROUND_PROBE_TIMEOUT_MILLISECONDS = 2_000L
        private const val FOREGROUND_RESUME_RETRY_WINDOW_MILLISECONDS = 5_000L
        private const val MAXIMUM_ATTACHMENT_BYTES = 8 * 1_024 * 1_024
        private const val MAXIMUM_OUTGOING_IMAGE_COUNT = 4
        private const val MAXIMUM_OUTGOING_IMAGE_CHARACTERS = 4_893_356
        private const val MAXIMUM_OUTGOING_TOTAL_BASE64_CHARACTERS = 16L * 1_024 * 1_024
        private const val MAXIMUM_RUNTIME_EVENT_COUNT = 8
        private const val MAXIMUM_RUNTIME_EVENT_BYTES = 48L * 1_024 * 1_024
        private const val FRAME_RETENTION_MULTIPLIER = 3L
        private const val FRAME_OBJECT_OVERHEAD_BYTES = 4_096L
        private const val ERROR_NOT_CONNECTED = "not-connected"
        private const val ERROR_SEND_FAILED = "send-failed"
        private const val ERROR_OPEN_FAILED = "open-failed"
        private const val ERROR_STALE_FRAME = "stale-frame"
        private const val ERROR_NO_ACTIVE_REQUEST = "no-active-request"
        private const val ERROR_SESSION_MISMATCH = "session-mismatch"
        private const val ERROR_PAIR_TOKEN_MISSING = "pair-token-missing"
        private const val ERROR_CREDENTIAL_ACCESS = "credential-access-failed"
        private const val ERROR_ATTACHMENT_INVALID = "attachment-invalid"
        private const val ERROR_ATTACHMENT_CACHE_WRITE = "attachment-cache-write-failed"
        private const val ERROR_INCOMING_FLOW = "incoming-flow-failed"
        private const val ERROR_NETWORK_FLOW = "network-flow-failed"
        private const val ERROR_AUTH_REQUIRED = "authentication-required"
        private const val ERROR_GATEWAY_UNAVAILABLE = "gateway-unavailable"
        private const val ERROR_GATEWAY_DISABLED = "gateway-disabled"
        private const val ERROR_TRANSPORT_FAILED = "transport-failed"
        private const val ERROR_GATEWAY_REQUEST = "gateway-request-failed"
        private const val ERROR_IMAGE_LIMITS = "image-limits-exceeded"
        private const val ERROR_REQUEST_BUSY = "request-busy"
        private const val ERROR_COALESCED = "request-coalesced"
        private const val ERROR_CONNECTION_REPLACED = "connection-replaced"
        private const val ERROR_CONNECTION_CLOSED = "connection-closed"
        private const val ERROR_CONNECTION_RECYCLED = "connection-recycled"
        private const val ERROR_NETWORK_LOST = "network-lost"
        private const val ERROR_RECOVERY_TIMEOUT = "recovery-timeout"
        private const val ERROR_CONNECTION_TIMEOUT = "connection-timeout"
        private val STABLE_TRANSPORT_CODES = setOf(
            "incoming-overflow",
            "incoming-message-too-large",
            "websocket-failure"
        )
        private val UNCORRELATED_KINDS = setOf(
            "event", "hello", "paired", "question-requested", "question-resolved",
            "approval-requested", "approval-resolved", "tasks-updated", "goal-updated",
            "session-snapshot", "assistant-stream", "session-stream-reset", "projection-baseline",
            "session-agent-preset-updated"
        )
        private val RESPONSE_KINDS_REQUIRING_ACTIVE_REQUEST = setOf(
            "history", "attachment", "sent", "question-response", "approval-response",
            "file-resolved", "file-list", "file-download-opened", "file-download-chunk", "file-download-cancelled",
            "session-cancelled", "session-created", "queue-item-updated",
            "session-agent-preset", "select-agent-preset",
            "speech-catalog", "speech-subscribed", "speech-unsubscribed", "speech-preparing",
            "speech-preparation-cancelled", "speech-transcript", "speech-cancelled"
        )
        private val IDEMPOTENT_CONNECTION_STATES = setOf(
            GatewayConnectionState.CONNECTING,
            GatewayConnectionState.AUTHENTICATING,
            GatewayConnectionState.CONNECTED,
            GatewayConnectionState.WAITING_FOR_NETWORK
        )
    }
}

/** attachmentId 尚未由协议保证跨 session 全局唯一，因此缓存键必须包含 session。 */
fun gatewayAttachmentCacheKey(sessionId: String, attachmentId: String): String =
    "${sessionId.length}:$sessionId:$attachmentId"

private fun createNetworkDiagnosticsClock(): () -> Double {
    val origin = kotlin.time.TimeSource.Monotonic.markNow()
    return { origin.elapsedNow().inWholeNanoseconds / 1_000_000.0 }
}
