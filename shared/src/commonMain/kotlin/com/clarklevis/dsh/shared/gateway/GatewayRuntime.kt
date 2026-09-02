package com.clarklevis.dsh.shared.gateway

import com.clarklevis.dsh.shared.platform.GatewayAttachmentCache
import com.clarklevis.dsh.shared.platform.GatewayClock
import com.clarklevis.dsh.shared.platform.GatewayConnectionSpec
import com.clarklevis.dsh.shared.platform.GatewayCredentialStore
import com.clarklevis.dsh.shared.platform.GatewayNetworkMonitor
import com.clarklevis.dsh.shared.platform.GatewayNetworkState
import com.clarklevis.dsh.shared.platform.GatewayPreferences
import com.clarklevis.dsh.shared.platform.GatewayTransport
import com.clarklevis.dsh.shared.platform.GatewayTransportFrame
import com.clarklevis.dsh.shared.platform.GatewayTransportEvent
import com.clarklevis.dsh.shared.platform.GatewayTransportState
import com.clarklevis.dsh.shared.protocol.GatewayFrame
import com.clarklevis.dsh.shared.protocol.GatewayQuestionAnswer
import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.encodeToString

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
        val correlatedSessionId: String?
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
        val correlationId: String? = null
    ) : GatewayRuntimeEvent
    data class RequestTimedOut(
        val requestType: String,
        val targetSessionId: String?,
        val correlationId: String? = null
    ) : GatewayRuntimeEvent
    data class RequestRejected(
        val requestType: String,
        val reason: String,
        val targetSessionId: String? = null,
        val correlationId: String? = null
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
    private val frameDecoder: (String) -> GatewayFrame = GatewayWireDecoder::decode
) {
    private val serialization = Mutex()
    private val mutableState = MutableStateFlow(
        GatewayRuntimeState(networkAvailable = networkMonitor.state.value == GatewayNetworkState.AVAILABLE)
    )
    private val eventQueue = BoundedGatewayRuntimeEventQueue(
        maximumEvents = MAXIMUM_RUNTIME_EVENT_COUNT,
        maximumBytes = MAXIMUM_RUNTIME_EVENT_BYTES
    )
    private val pendingLanes = mutableMapOf<String, PendingLane>()
    private val deferredRequests = ArrayDeque<GatewayRequest>()
    private val activeTurnCountsBySession = mutableMapOf<String, Int>()
    private var unassociatedTurnCount = 0
    private var desiredConnection = false
    private var reconnectBlocked = false
    private var pairingCode: String? = null
    private var endpoint: String? = null
    private var subscribedSessionId: String? = null
    private var isInBackground = false
    private var connectionGeneration = 0L
    private var requestGeneration = 0L
    private var reconnectJob: Job? = null
    private var recoveryDeadlineJob: Job? = null

    val state: StateFlow<GatewayRuntimeState> = mutableState.asStateFlow()
    val events: Flow<GatewayRuntimeEvent> = eventQueue.flow

    init {
        collectSafely(transport.events, ERROR_INCOMING_FLOW) { event ->
            when (event) {
                is GatewayTransportEvent.Frame -> handleIncomingFrameLocked(event.value)
                is GatewayTransportEvent.State -> handleTransportStateLocked(event.value)
            }
        }
        collectSafely(networkMonitor.state, ERROR_NETWORK_FLOW, ::handleNetworkLocked)
    }

    suspend fun connect(endpoint: String) = serialized {
        requireWebSocketEndpoint(endpoint)
        val existing = preferences.load()
        preferences.update(existing.copy(endpoint = endpoint))
        this.endpoint = endpoint
        pairingCode = null
        desiredConnection = true
        reconnectBlocked = false
        reconnectJob?.cancel()
        recoveryDeadlineJob?.cancel()
        recoveryDeadlineJob = null
        openTransportLocked(preserveTurns = false)
    }

    suspend fun pair(encodedPayload: String) = serialized {
        val payload = GatewayPairingPayloadParser.parse(encodedPayload, clock.nowEpochMilliseconds())
        val existing = preferences.load()
        preferences.update(existing.copy(endpoint = payload.publicUrl))
        endpoint = payload.publicUrl
        pairingCode = payload.pairingCode
        desiredConnection = true
        reconnectBlocked = false
        reconnectJob?.cancel()
        recoveryDeadlineJob?.cancel()
        recoveryDeadlineJob = null
        openTransportLocked(preserveTurns = false)
    }

    suspend fun connectStoredIfPaired(): Boolean = serialized {
        val stored = preferences.load()
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
        endpoint = stored.endpoint
        pairingCode = null
        desiredConnection = true
        reconnectBlocked = false
        reconnectJob?.cancel()
        recoveryDeadlineJob?.cancel()
        recoveryDeadlineJob = null
        openTransportLocked(preserveTurns = false)
        true
    }

    suspend fun disconnect() = serialized {
        desiredConnection = false
        reconnectBlocked = false
        pairingCode = null
        reconnectJob?.cancel()
        reconnectJob = null
        recoveryDeadlineJob?.cancel()
        recoveryDeadlineJob = null
        connectionGeneration += 1
        clearConnectionWorkLocked()
        runCatching { transport.close() }
        mutableState.value = mutableState.value.copy(
            connection = GatewayConnectionState.DISCONNECTED,
            reconnectAttempt = 0,
            lastError = null
        )
    }

    suspend fun applicationDidEnterBackground() = serialized {
        isInBackground = true
        if (!mutableState.value.shouldKeepAliveInBackground) {
            connectionGeneration += 1
            cancelPendingLocked(ERROR_BACKGROUND_SUSPENDED)
            runCatching { transport.close() }
            mutableState.value = mutableState.value.copy(connection = GatewayConnectionState.SUSPENDED)
        }
    }

    fun applicationDidBecomeActive() {
        scope.launch {
            serialized {
                isInBackground = false
                if (canReconnectLocked() && mutableState.value.connection != GatewayConnectionState.CONNECTED) {
                    scheduleReconnectLocked(immediate = true)
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
        view: String? = null
    ): Boolean = sendRequest(
        GatewayRequests.history(sessionId, beforeSequence, maxMessages, maxBytes, view)
    )

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
        clientTimeZone: String
    ): Boolean = serialized {
        if (text.isBlank() && images.isEmpty()) return@serialized false
        if (!outgoingImagesAreWithinLimits(images)) {
            rejectLocked("message", ERROR_IMAGE_LIMITS)
            return@serialized false
        }
        markTurnLocked(sessionId)
        val sent = sendRequestLocked(
            GatewayRequests.message(text.trim(), images, sessionId, workspaceId, clientTimeZone)
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

    private suspend fun sendRequestLocked(request: GatewayRequest): Boolean {
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
        val timeoutJob = scope.launch {
            delay(requestTimeoutMilliseconds)
            serialized { handleRequestTimeoutLocked(request.responseKind, pendingId, transportGeneration) }
        }
        pendingLanes[request.responseKind] = PendingLane(
            active = PendingRequest(request, transportGeneration, pendingId, timeoutJob),
            queued = ArrayDeque()
        )
        return runCatching { transport.send(request.payload) }.fold(
            onSuccess = { true },
            onFailure = {
                pendingLanes.remove(request.responseKind)?.active?.timeoutJob?.cancel()
                rejectLocked(
                    request.requestType,
                    ERROR_SEND_FAILED,
                    request.targetSessionId,
                    request.correlationId
                )
                false
            }
        )
    }

    private suspend fun completeLaneLocked(responseKind: String) {
        val lane = pendingLanes.remove(responseKind) ?: return
        lane.active.timeoutJob.cancel()
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
        if (!networkAvailableLocked()) {
            mutableState.value = mutableState.value.copy(connection = GatewayConnectionState.WAITING_FOR_NETWORK)
            return
        }
        if (isInBackground && !mutableState.value.shouldKeepAliveInBackground) {
            mutableState.value = mutableState.value.copy(connection = GatewayConnectionState.SUSPENDED)
            return
        }
        val target = endpoint ?: return
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
            pairingCode = immutablePairingCode
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
        runCatching { transport.open(spec) }.onFailure { failOpenLocked(ERROR_OPEN_FAILED) }
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
        // failure 与 frame 共用有序通道；处理 failure 时立即作废代次，随后到达的旧帧只能被拒绝。
        connectionGeneration += 1
        val authenticationFailure = failure.httpStatus == 401 || failure.closeCode == 4003
        if (failure.httpStatus == 401 && pairingCode == null) {
            endpoint?.let { runCatching { credentials.deleteToken(it) } }
        }
        if (authenticationFailure || !failure.recoverable) reconnectBlocked = true
        cancelPendingLocked(failure.stableErrorCode())
        val shouldReconnect = canReconnectLocked() && failure.recoverable && !authenticationFailure
        if (!shouldReconnect) clearTurnsLocked() else startRecoveryDeadlineLocked()
        mutableState.value = mutableState.value.copy(
            connection = if (shouldReconnect) GatewayConnectionState.CONNECTING else GatewayConnectionState.FAILED,
            lastError = failure.stableErrorCode()
        )
        if (shouldReconnect) scheduleReconnectLocked(immediate = false)
    }

    private suspend fun handleIncomingFrameLocked(transportFrame: GatewayTransportFrame) {
        if (transportFrame.generation != connectionGeneration) {
            rejectLocked("transport", ERROR_STALE_FRAME)
            return
        }
        val frame = runCatching { frameDecoder(transportFrame.text) }.getOrElse {
            rejectLocked("decode", ERROR_DECODE_FAILED)
            return
        }
        if (frame.kind == "paired") {
            val target = endpoint
            val token = frame.token
            if (target == null || token.isNullOrBlank()) {
                rejectLocked("pair", ERROR_PAIR_TOKEN_MISSING)
                return
            }
            if (runCatching { credentials.saveToken(target, token) }.isFailure) {
                rejectLocked("pair", ERROR_CREDENTIAL_ACCESS)
                return
            }
            pairingCode = null
        }
        if (frame.kind == "hello") {
            recoveryDeadlineJob?.cancel()
            recoveryDeadlineJob = null
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
        if (!correlation.accepted) return
        if (frame.kind == "error") {
            rejectLocked(
                frame.requestType ?: "gateway",
                ERROR_GATEWAY_REQUEST,
                correlation.sessionId,
                responseCorrelation(frame)
            )
            suspendBackgroundConnectionIfIdleLocked()
            return
        }
        if (frame.kind == "sent" && unassociatedTurnCount > 0 && !frame.sessionId.isNullOrBlank()) {
            unassociatedTurnCount -= 1
            activeTurnCountsBySession[frame.sessionId] =
                activeTurnCountsBySession.getOrElse(frame.sessionId) { 0 } + 1
            publishTurnStateLocked()
        }
        val endedLastBackgroundTurn = frame.kind == "event" && frame.event?.type == "turn/end"
        if (endedLastBackgroundTurn) releaseTurnLocked(frame.sessionId)
        if (frame.kind == "attachment") {
            val cached = cacheAttachmentLocked(frame)
            completeLaneLocked("attachment")
            if (!cached) return
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
        emitEventLocked(
            GatewayRuntimeEvent.Frame(safeRaw, safeFrame, correlation.sessionId),
            estimatedBytes
        )
        if (endedLastBackgroundTurn) suspendBackgroundConnectionIfIdleLocked()
    }

    private suspend fun correlateLocked(frame: GatewayFrame): Correlation {
        if (frame.kind in UNCORRELATED_KINDS) return Correlation(true, frame.sessionId)
        if (frame.kind == "error") {
            val requestType = frame.requestType
            if (requestType == null) {
                cancelPendingLocked(ERROR_GATEWAY_REQUEST, releaseMessageTurns = true)
                return Correlation(true, frame.sessionId)
            }
            val entry = pendingLanes.entries.firstOrNull { it.value.active.request.requestType == requestType }
            if (entry == null) {
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
            return Correlation(true, frame.sessionId ?: request.targetSessionId)
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
        val sessionId = frame.sessionId ?: lane.active.request.targetSessionId
        if (frame.kind != "attachment") completeLaneLocked(frame.kind)
        return Correlation(true, sessionId)
    }

    private fun sessionMatches(request: GatewayRequest, explicitSessionId: String?): Boolean =
        explicitSessionId == null || request.targetSessionId == null || explicitSessionId == request.targetSessionId

    private fun responseCorrelationMatches(request: GatewayRequest, frame: GatewayFrame): Boolean =
        when (request.responseKind) {
            "attachment" -> request.correlationId == frame.attachment?.attachmentId
            "question-response", "approval-response" -> request.correlationId == frame.rpcId
            "file-list", "file-download-opened" -> request.correlationId == frame.requestId
            "file-download-chunk", "file-download-cancelled" -> request.correlationId == frame.transferId
            "command-options", "command-selected" -> request.correlationId == frame.command?.stringValue
            else -> true
        }

    private fun responseCorrelation(frame: GatewayFrame): String? =
        frame.attachment?.attachmentId ?: frame.rpcId ?: frame.requestId ?: frame.transferId ?:
            frame.command?.stringValue

    private fun explicitResponseCorrelation(request: GatewayRequest, frame: GatewayFrame): String? =
        when (request.responseKind) {
            "attachment" -> frame.attachment?.attachmentId
            "question-response", "approval-response" -> frame.rpcId
            "file-list", "file-download-opened" -> frame.requestId
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
        runCatching { transport.close() }
        clearConnectionWorkLocked()
        mutableState.value = mutableState.value.copy(connection = GatewayConnectionState.FAILED, lastError = code)
    }

    private suspend fun clearConnectionWorkLocked() {
        cancelPendingLocked(ERROR_CONNECTION_CLOSED)
        while (deferredRequests.isNotEmpty()) {
            emitCancelledLocked(deferredRequests.removeFirst(), ERROR_CONNECTION_CLOSED)
        }
        clearTurnsLocked()
    }

    private suspend fun cancelPendingLocked(reason: String, releaseMessageTurns: Boolean = false) {
        pendingLanes.values.forEach { lane ->
            lane.active.timeoutJob.cancel()
            if (releaseMessageTurns && lane.active.request.requestType == "message") {
                releaseTurnLocked(lane.active.request.targetSessionId)
            }
            emitCancelledLocked(lane.active.request, reason)
            while (lane.queued.isNotEmpty()) emitCancelledLocked(lane.queued.removeFirst(), reason)
        }
        pendingLanes.clear()
    }

    private suspend fun cancelLaneLocked(responseKind: String, reason: String) {
        val lane = pendingLanes.remove(responseKind) ?: return
        lane.active.timeoutJob.cancel()
        if (lane.active.request.requestType == "message") {
            releaseTurnLocked(lane.active.request.targetSessionId)
        }
        emitCancelledLocked(lane.active.request, reason)
        while (lane.queued.isNotEmpty()) emitCancelledLocked(lane.queued.removeFirst(), reason)
    }

    private fun clearTurnsLocked() {
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
                lane.active.request.correlationId
            )
        )
        suspendBackgroundConnectionIfIdleLocked()
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
                request.correlationId
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
            activeTurnSessionIds = activeTurnCountsBySession.keys.toSet(),
            hasUnassociatedTurn = unassociatedTurnCount > 0
        )
    }

    private fun networkAvailableLocked(): Boolean =
        mutableState.value.networkAvailable && networkMonitor.state.value == GatewayNetworkState.AVAILABLE

    private fun canReconnectLocked(): Boolean =
        desiredConnection && !reconnectBlocked && networkAvailableLocked() &&
            (!isInBackground || mutableState.value.shouldKeepAliveInBackground)

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
            serialized { if (canReconnectLocked()) openTransportLocked(preserveTurns = true) }
        }
    }

    private suspend fun rejectLocked(
        requestType: String,
        code: String,
        targetSessionId: String? = null,
        correlationId: String? = null
    ) {
        emitEventLocked(
            GatewayRuntimeEvent.RequestRejected(
                requestType,
                code,
                targetSessionId,
                correlationId
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

    private suspend fun suspendBackgroundConnectionIfIdleLocked() {
        if (!isInBackground || mutableState.value.shouldKeepAliveInBackground) return
        connectionGeneration += 1
        reconnectJob?.cancel()
        reconnectJob = null
        recoveryDeadlineJob?.cancel()
        recoveryDeadlineJob = null
        cancelPendingLocked(ERROR_BACKGROUND_SUSPENDED)
        while (deferredRequests.isNotEmpty()) {
            emitCancelledLocked(deferredRequests.removeFirst(), ERROR_BACKGROUND_SUSPENDED)
        }
        runCatching { transport.close() }
        mutableState.value = mutableState.value.copy(connection = GatewayConnectionState.SUSPENDED)
    }

    private suspend fun emitEventLocked(event: GatewayRuntimeEvent, estimatedBytes: Long = 256L) {
        eventQueue.emit(event, estimatedBytes)
    }

    private fun <T> collectSafely(flow: Flow<T>, code: String, handler: suspend (T) -> Unit) {
        scope.launch {
            try {
                flow.collect { value ->
                    try {
                        serialized { handler(value) }
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
        val timeoutJob: Job
    )
    private data class PendingLane(
        val active: PendingRequest,
        val queued: ArrayDeque<GatewayRequest>
    )
    private data class Correlation(val accepted: Boolean, val sessionId: String?)

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
        private const val ERROR_DECODE_FAILED = "decode-failed"
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
        private const val ERROR_BACKGROUND_SUSPENDED = "background-suspended"
        private const val ERROR_NETWORK_LOST = "network-lost"
        private const val ERROR_RECOVERY_TIMEOUT = "recovery-timeout"
        private val STABLE_TRANSPORT_CODES = setOf(
            "incoming-overflow",
            "incoming-message-too-large",
            "websocket-failure"
        )
        private val UNCORRELATED_KINDS = setOf(
            "event", "hello", "paired", "pong", "question-requested", "question-resolved",
            "approval-requested", "approval-resolved"
        )
        private val RESPONSE_KINDS_REQUIRING_ACTIVE_REQUEST = setOf(
            "history", "attachment", "sent", "question-response", "approval-response",
            "file-list", "file-download-opened", "file-download-chunk", "file-download-cancelled"
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
