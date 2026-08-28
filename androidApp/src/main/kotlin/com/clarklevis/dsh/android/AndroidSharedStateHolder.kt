package com.clarklevis.dsh.android

import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.clarklevis.dsh.android.platform.AndroidGatewayPreferences
import com.clarklevis.dsh.android.platform.GatewayDiagnosticAction
import com.clarklevis.dsh.android.platform.AndroidImagePreprocessor
import com.clarklevis.dsh.android.platform.AndroidPreparedImage
import com.clarklevis.dsh.android.platform.BoundedLruCache
import com.clarklevis.dsh.shared.facade.SharedMobileSnapshot
import com.clarklevis.dsh.shared.facade.SharedMobileStore
import com.clarklevis.dsh.shared.gateway.GatewayConnectionState
import com.clarklevis.dsh.shared.gateway.GatewayPairingPayloadException
import com.clarklevis.dsh.shared.gateway.GatewayRuntimeEvent
import com.clarklevis.dsh.shared.gateway.GatewayRuntimeState
import com.clarklevis.dsh.shared.gateway.GatewayRequests
import com.clarklevis.dsh.shared.gateway.gatewayAttachmentCacheKey
import com.clarklevis.dsh.shared.protocol.GatewayQuestionAnswer
import com.clarklevis.dsh.shared.projection.TrajectoryNode
import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Stable
class AndroidSharedStateHolder(
    store: SharedMobileStore = SharedMobileStore(),
    private val graph: AndroidAppGraph? = null
) {
    private val scope = graph?.let { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    private var runtimeCollection: Job? = null
    private val projectionActor = AndroidProjectionActor(
        projection = AndroidGatewayProjection(store) { sessionId, beforeSequence ->
            graph?.let { appGraph ->
                appGraph.gatewayScope.launch {
                    appGraph.gatewayRuntime.requestHistory(
                        sessionId = sessionId,
                        beforeSequence = beforeSequence,
                        maxMessages = 50,
                        maxBytes = 4 * 1_024 * 1_024,
                        view = "mobile"
                    )
                }
            }
        },
        uiDispatcher = if (graph == null) Dispatchers.Unconfined else Dispatchers.Main.immediate,
        publish = { snapshot = it }
    )
    private val attachmentQueue = ArrayDeque<AttachmentRequest>()
    private var activeAttachment: AttachmentRequest? = null
    private var visibleAttachmentKeys: Set<String> = emptySet()
    private var thumbnailTargetWidthPixels = DEFAULT_THUMBNAIL_TARGET_PIXELS
    private var thumbnailTargetHeightPixels = DEFAULT_THUMBNAIL_TARGET_PIXELS
    private var lastObservedConnection = GatewayConnectionState.DISCONNECTED
    private var inputGeneration = 0L
    private var pendingSelectedSessionId: String? = null
    private val thumbnailCache = BoundedLruCache<String, ImageBitmap>(MAXIMUM_THUMBNAIL_BYTES) {
        it.width.toLong() * it.height * 4
    }
    private val attachmentStateCache = BoundedLruCache<String, AttachmentLoadState>(MAXIMUM_STATUS_COUNT) { 1 }

    var snapshot: SharedMobileSnapshot by mutableStateOf(projectionActor.initialSnapshot)
        private set
    var wirePayload: String by mutableStateOf(DEFAULT_WIRE_PAYLOAD)
    var gatewayState: GatewayRuntimeState by mutableStateOf(GatewayRuntimeState())
        private set
    var endpoint: String by mutableStateOf(AndroidGatewayPreferences.DEFAULT_ENDPOINT)
    var pairingPayload: String by mutableStateOf("")
    var selectedWorkspaceId: String? by mutableStateOf(null)
        private set
    var historyPagingSessionIds: Set<String> by mutableStateOf(emptySet())
        private set
    var trajectoryNodes: List<TrajectoryNode> by mutableStateOf(emptyList())
        private set
    private var trajectoryIsActive = false
    private var messageDraftState: String by mutableStateOf("")
    var messageDraft: String
        get() = messageDraftState
        set(value) {
            if (messageDraftState != value) {
                messageDraftState = value
                inputGeneration += 1
            }
        }
    private var preparedImagesState: List<AndroidPreparedImage> by mutableStateOf(emptyList())
    var preparedImages: List<AndroidPreparedImage>
        get() = preparedImagesState
        private set(value) {
            if (preparedImagesState != value) {
                preparedImagesState = value
                inputGeneration += 1
            }
        }
    var attachmentThumbnails: Map<String, ImageBitmap> by mutableStateOf(emptyMap())
        private set
    var attachmentStates: Map<String, AttachmentLoadState> by mutableStateOf(emptyMap())
        private set
    var platformError: String? by mutableStateOf(null)
        private set

    init {
        graph?.let { appGraph ->
            val holderScope = requireNotNull(scope)
            runtimeCollection = appGraph.gatewayScope.launch {
                launch {
                    appGraph.preferences.snapshots.collect { value ->
                        withContext(Dispatchers.Main.immediate) { endpoint = value.endpoint }
                    }
                }
                launch {
                    appGraph.gatewayRuntime.state.collect { state ->
                        appGraph.diagnostics.runtimeState(state)
                        withContext(Dispatchers.Main.immediate) {
                            gatewayState = state
                            if (
                                state.connection == GatewayConnectionState.CONNECTED &&
                                lastObservedConnection != GatewayConnectionState.CONNECTED &&
                                activeAttachment != null
                            ) {
                                attachmentQueue.addFirst(requireNotNull(activeAttachment))
                                activeAttachment = null
                                drainAttachmentQueue()
                            }
                            lastObservedConnection = state.connection
                        }
                    }
                }
                launch {
                    appGraph.gatewayRuntime.events.collect { event ->
                        appGraph.diagnostics.runtimeEvent(event)
                        when (event) {
                            is GatewayRuntimeEvent.Frame -> {
                                projectionActor.acceptFrame(
                                    event.rawJson,
                                    event.frame,
                                    event.correlatedSessionId
                                ) {
                                    pruneAttachmentStateForSession()
                                    if (event.frame.kind == "history") {
                                        event.correlatedSessionId?.let { sessionId ->
                                            historyPagingSessionIds = historyPagingSessionIds - sessionId
                                        }
                                    }
                                }
                                if (trajectoryIsActive) publishTrajectory()
                            }
                            is GatewayRuntimeEvent.AttachmentCached -> withContext(Dispatchers.Main.immediate) {
                                attachmentCompleted(event.sessionId, event.attachmentId)
                            }
                            is GatewayRuntimeEvent.RequestQueued -> Unit
                            is GatewayRuntimeEvent.RequestCancelled -> {
                                event.targetSessionId?.takeIf { event.requestType == "history" }
                                    ?.let { projectionActor.historyCancelled(it) }
                                if (event.requestType == "attachment") {
                                    withContext(Dispatchers.Main.immediate) {
                                        attachmentFailed(event.targetSessionId, event.correlationId)
                                    }
                                }
                            }
                            is GatewayRuntimeEvent.RequestTimedOut -> {
                                event.targetSessionId?.takeIf { event.requestType == "history" }
                                    ?.let { projectionActor.historyTimedOut(it) }
                                if (event.requestType == "attachment") {
                                    withContext(Dispatchers.Main.immediate) {
                                        attachmentFailed(event.targetSessionId, event.correlationId)
                                    }
                                }
                            }
                            is GatewayRuntimeEvent.RequestRejected -> {
                                withContext(Dispatchers.Main.immediate) {
                                    platformError = "${event.requestType}: ${event.reason}"
                                }
                                event.targetSessionId?.takeIf { event.requestType == "history" }
                                    ?.let { projectionActor.historyCancelled(it) }
                                if (event.requestType == "attachment") {
                                    withContext(Dispatchers.Main.immediate) {
                                        attachmentFailed(event.targetSessionId, event.correlationId)
                                    }
                                }
                            }
                        }
                    }
                }
                launch {
                    runCatching { appGraph.gatewayRuntime.connectStoredIfPaired() }
                        .onFailure {
                            withContext(Dispatchers.Main.immediate) {
                                platformError = "stored-connect-failed"
                            }
                        }
                }
            }
        }
    }

    fun loadFixture() {
        val appGraph = graph
        if (appGraph == null) projectionActor.loadFixtureImmediate(::clearAttachmentUiState)
        else appGraph.gatewayScope.launch { projectionActor.loadFixture(::clearAttachmentUiState) }
    }

    internal suspend fun loadFixtureAndAwaitForTest() {
        projectionActor.loadFixture(::clearAttachmentUiState)
    }

    fun submitWirePayload() {
        val payload = wirePayload
        val appGraph = graph
        if (appGraph == null) {
            runCatching { GatewayWireDecoder.decode(payload) }
                .onSuccess { frame -> projectionActor.acceptFrameImmediate(payload, frame, frame.sessionId) }
                .onFailure { platformError = "decode-failed" }
            return
        }
        appGraph.gatewayScope.launch {
            runCatching { GatewayWireDecoder.decode(payload) }
                .onSuccess { frame ->
                    projectionActor.acceptFrame(payload, frame, frame.sessionId) {
                        pruneAttachmentStateForSession()
                    }
                }
                .onFailure {
                    withContext(Dispatchers.Main.immediate) { platformError = "decode-failed" }
                }
        }
    }

    fun selectSession(sessionId: String) {
        graph?.diagnostics?.intent(GatewayDiagnosticAction.SELECT_SESSION, hasSession = true)
        pendingSelectedSessionId = sessionId
        inputGeneration += 1
        val afterPublish = {
            if (pendingSelectedSessionId == sessionId) pendingSelectedSessionId = null
            visibleAttachmentKeys = emptySet()
            pruneAttachmentStateForSession()
        }
        val appGraph = graph
        if (appGraph == null) {
            projectionActor.selectSessionImmediate(sessionId, afterPublish)
        } else {
            appGraph.gatewayScope.launch {
                projectionActor.selectSession(sessionId, afterPublish)
                if (gatewayState.connection == GatewayConnectionState.CONNECTED) {
                    appGraph.gatewayRuntime.subscribe(sessionId)
                }
            }
        }
    }

    fun prepareNewSession() {
        pendingSelectedSessionId = null
        inputGeneration += 1
        val afterPublish = {
            visibleAttachmentKeys = emptySet()
            pruneAttachmentStateForSession()
        }
        val appGraph = graph
        if (appGraph == null) projectionActor.selectSessionImmediate(null, afterPublish)
        else appGraph.gatewayScope.launch {
            projectionActor.selectSession(null, afterPublish)
            if (gatewayState.connection == GatewayConnectionState.CONNECTED) {
                appGraph.gatewayRuntime.subscribe(null)
            }
        }
    }

    fun selectWorkspace(workspaceId: String?) {
        selectedWorkspaceId = workspaceId
    }

    fun refreshProductState() {
        val appGraph = graph ?: return
        if (gatewayState.connection != GatewayConnectionState.CONNECTED) return
        appGraph.gatewayScope.launch {
            appGraph.gatewayRuntime.sendRequest(GatewayRequests.simple("workspaces"))
            appGraph.gatewayRuntime.requestSessions()
            appGraph.gatewayRuntime.sendRequest(GatewayRequests.simple("agent-presets"))
            appGraph.gatewayRuntime.sendRequest(GatewayRequests.simple("defaults"))
            appGraph.gatewayRuntime.sendRequest(GatewayRequests.simple("default-model"))
            appGraph.gatewayRuntime.sendRequest(GatewayRequests.simple("models"))
            appGraph.gatewayRuntime.sendRequest(GatewayRequests.simple("host"))
        }
    }

    fun pingGateway() {
        val appGraph = graph ?: return
        if (gatewayState.connection != GatewayConnectionState.CONNECTED) return
        appGraph.gatewayScope.launch { appGraph.gatewayRuntime.sendRequest(GatewayRequests.ping()) }
    }

    fun search(query: String) {
        val normalized = query.trim()
        if (normalized.isEmpty()) return
        graph?.let { appGraph ->
            appGraph.gatewayScope.launch {
                appGraph.gatewayRuntime.sendRequest(GatewayRequests.search(normalized))
            }
        }
    }

    fun loadOlderHistory() {
        val sessionId = snapshot.selectedSessionId ?: return
        val before = snapshot.selectedHistoryEarliestSequence ?: return
        if (!snapshot.selectedHistoryHasMore || sessionId in historyPagingSessionIds) return
        val appGraph = graph ?: return
        historyPagingSessionIds = historyPagingSessionIds + sessionId
        appGraph.gatewayScope.launch {
            val accepted = appGraph.gatewayRuntime.requestHistory(
                sessionId = sessionId,
                beforeSequence = before,
                maxMessages = 50,
                maxBytes = 4 * 1_024 * 1_024,
                view = "mobile"
            )
            if (!accepted) withContext(Dispatchers.Main.immediate) {
                historyPagingSessionIds = historyPagingSessionIds - sessionId
            }
        }
    }

    fun setTrajectoryActive(active: Boolean) {
        trajectoryIsActive = active
        if (active) graph?.gatewayScope?.launch { publishTrajectory() }
    }

    private suspend fun publishTrajectory() {
        val nodes = projectionActor.trajectory(snapshot.selectedSessionId)
        withContext(Dispatchers.Main.immediate) {
            if (trajectoryIsActive) trajectoryNodes = nodes
        }
    }

    fun setDefault(target: String, value: String) {
        graph?.let { appGraph ->
            appGraph.gatewayScope.launch {
                appGraph.gatewayRuntime.sendRequest(GatewayRequests.setDefault(target, value))
                refreshProductState()
            }
        }
    }

    fun saveDefaultModel(provider: String, model: String, reasoningEffort: String?) {
        graph?.let { appGraph ->
            appGraph.gatewayScope.launch {
                appGraph.gatewayRuntime.sendRequest(
                    GatewayRequests.saveDefaultModel(provider, model, reasoningEffort)
                )
                refreshProductState()
            }
        }
    }

    fun selectModel(provider: String, model: String, reasoningEffort: String?) {
        val sessionId = snapshot.selectedSessionId ?: return
        graph?.let { appGraph ->
            appGraph.gatewayScope.launch {
                appGraph.gatewayRuntime.sendRequest(
                    GatewayRequests.selectModel(sessionId, provider, model, reasoningEffort)
                )
                refreshSessionControls()
            }
        }
    }

    fun setSessionPermission(name: String) {
        val sessionId = snapshot.selectedSessionId ?: return
        graph?.let { appGraph ->
            appGraph.gatewayScope.launch {
                appGraph.gatewayRuntime.sendRequest(GatewayRequests.setPermission(sessionId, name))
                refreshSessionControls()
            }
        }
    }

    fun refreshSessionControls() {
        val sessionId = snapshot.selectedSessionId ?: return
        val appGraph = graph ?: return
        if (gatewayState.connection != GatewayConnectionState.CONNECTED) return
        appGraph.gatewayScope.launch {
            appGraph.gatewayRuntime.sendRequest(GatewayRequests.sessionControl("models", sessionId))
            appGraph.gatewayRuntime.sendRequest(GatewayRequests.sessionControl("permission-options", sessionId))
            appGraph.gatewayRuntime.sendRequest(GatewayRequests.sessionControl("context-usage", sessionId))
            appGraph.gatewayRuntime.sendRequest(GatewayRequests.sessionControl("session-stats", sessionId))
        }
    }

    fun answerQuestion(rpcId: String, sessionId: String, answers: List<GatewayQuestionAnswer>) {
        graph?.let { appGraph ->
            appGraph.gatewayScope.launch { appGraph.gatewayRuntime.answerQuestion(rpcId, sessionId, answers) }
        }
    }

    fun cancelQuestion(rpcId: String, sessionId: String) {
        graph?.let { appGraph ->
            appGraph.gatewayScope.launch { appGraph.gatewayRuntime.cancelQuestion(rpcId, sessionId) }
        }
    }

    fun reset() {
        inputGeneration += 1
        pendingSelectedSessionId = null
        val appGraph = graph
        if (appGraph == null) projectionActor.resetImmediate(::clearAttachmentUiState)
        else appGraph.gatewayScope.launch { projectionActor.reset(::clearAttachmentUiState) }
    }

    private fun clearAttachmentUiState() {
        attachmentQueue.clear()
        activeAttachment = null
        visibleAttachmentKeys = emptySet()
        thumbnailCache.retainKeys(emptySet())
        attachmentStateCache.retainKeys(emptySet())
        attachmentThumbnails = emptyMap()
        attachmentStates = emptyMap()
    }

    fun connect() {
        val appGraph = graph ?: return
        appGraph.diagnostics.intent(GatewayDiagnosticAction.CONNECT)
        platformError = null
        val target = endpoint.trim()
        appGraph.gatewayScope.launch {
            val failed = runCatching { appGraph.gatewayRuntime.connect(target) }.isFailure
            if (failed) withContext(Dispatchers.Main.immediate) { platformError = "connect-failed" }
        }
    }

    fun pair() {
        pair(pairingPayload)
    }

    fun pair(
        payload: String,
        reportFailureGlobally: Boolean = true,
        onFailure: (String) -> Unit = {}
    ) {
        val appGraph = graph ?: return
        appGraph.diagnostics.intent(GatewayDiagnosticAction.PAIR)
        platformError = null
        appGraph.gatewayScope.launch {
            val result = runCatching { appGraph.gatewayRuntime.pair(payload) }
            withContext(Dispatchers.Main.immediate) {
                if (result.isSuccess) {
                    if (pairingPayload == payload) pairingPayload = ""
                } else {
                    val message = if (result.exceptionOrNull() is GatewayPairingPayloadException) {
                        result.exceptionOrNull()?.message ?: "配对信息无效"
                    } else {
                        "无法提交配对信息，请稍后重试。"
                    }
                    if (reportFailureGlobally) platformError = message
                    onFailure(message)
                }
            }
        }
    }

    fun disconnect() {
        graph?.let { appGraph ->
            appGraph.diagnostics.intent(GatewayDiagnosticAction.DISCONNECT)
            appGraph.gatewayScope.launch { appGraph.gatewayRuntime.disconnect() }
        }
    }

    fun refreshSessions() {
        graph?.let { appGraph ->
            appGraph.diagnostics.intent(GatewayDiagnosticAction.REFRESH_SESSIONS)
            appGraph.gatewayScope.launch { appGraph.gatewayRuntime.requestSessions() }
        }
    }

    fun prepareImage(uri: Uri) {
        val appGraph = graph ?: return
        appGraph.diagnostics.intent(GatewayDiagnosticAction.PREPARE_IMAGE)
        platformError = null
        scope?.launch {
            runCatching { appGraph.imagePreprocessor.prepare(uri) }
                .onSuccess { image ->
                    val nextCount = preparedImages.size + 1
                    val nextBytes = preparedImages.sumOf(AndroidPreparedImage::byteCount) + image.byteCount
                    val nextBase64Characters = preparedImages.sumOf { it.outgoing.base64Data.length } +
                        image.outgoing.base64Data.length
                    if (
                        nextCount > AndroidImagePreprocessor.MAXIMUM_IMAGE_COUNT ||
                        nextBytes > AndroidImagePreprocessor.MAXIMUM_TOTAL_BYTES ||
                        nextBase64Characters > AndroidImagePreprocessor.MAXIMUM_TOTAL_BASE64_CHARACTERS
                    ) {
                        platformError = "image-selection-limit-exceeded"
                    } else {
                        preparedImages = preparedImages + image
                    }
                }
                .onFailure { platformError = "image-preprocess-failed" }
        }
    }

    fun removePreparedImage(index: Int) {
        preparedImages = preparedImages.filterIndexed { itemIndex, _ -> itemIndex != index }
    }

    fun sendMessage() {
        val appGraph = graph ?: return
        val submission = captureMessageSubmission()
        appGraph.diagnostics.intent(
            GatewayDiagnosticAction.SEND_MESSAGE,
            hasSession = submission.sessionId != null,
            imageCount = submission.images.size
        )
        appGraph.gatewayScope.launch {
            val sent = appGraph.gatewayRuntime.sendMessage(
                text = submission.draft,
                images = submission.images.map(AndroidPreparedImage::outgoing),
                sessionId = submission.sessionId,
                workspaceId = selectedWorkspaceId,
                clientTimeZone = TimeZone.getDefault().id
            )
            withContext(Dispatchers.Main.immediate) { applyMessageSendResult(submission, sent) }
        }
    }

    internal fun applyMessageSendResult(sent: Boolean) {
        applyMessageSendResult(captureMessageSubmission(), sent)
    }

    internal fun captureMessageSubmissionForTest(): MessageSubmission = captureMessageSubmission()

    internal fun applyMessageSendResultForTest(submission: MessageSubmission, sent: Boolean) {
        applyMessageSendResult(submission, sent)
    }

    private fun captureMessageSubmission(): MessageSubmission = MessageSubmission(
        generation = inputGeneration,
        sessionId = pendingSelectedSessionId ?: snapshot.selectedSessionId,
        draft = messageDraft,
        images = preparedImages.toList()
    )

    private fun applyMessageSendResult(submission: MessageSubmission, sent: Boolean) {
        if (!sent) return
        if (
            inputGeneration != submission.generation ||
            messageDraft != submission.draft ||
            preparedImages != submission.images ||
            (pendingSelectedSessionId ?: snapshot.selectedSessionId) != submission.sessionId
        ) {
            return
        }
        messageDraft = ""
        preparedImages = emptyList()
    }

    internal fun setPreparedImagesForTest(images: List<AndroidPreparedImage>) {
        preparedImages = images
    }

    internal fun failAttachmentForTest(attachmentId: String) {
        val key = cacheKeyForCurrentSession(attachmentId) ?: return
        removeQueuedAttachment(key)
        markAttachmentFailed(key)
    }

    internal fun queuedAttachmentIdsForTest(): List<String> =
        attachmentQueue.map(AttachmentRequest::attachmentId)

    internal fun commitVisibleThumbnailForTest(
        sessionId: String,
        attachmentId: String,
        bitmap: ImageBitmap
    ) {
        val key = gatewayAttachmentCacheKey(sessionId, attachmentId)
        visibleAttachmentKeys += key
        commitThumbnail(key, bitmap)
    }

    internal val thumbnailCacheWeightForTest: Long
        get() = thumbnailCache.currentWeight()

    fun clearPlatformError() {
        platformError = null
    }

    fun showPlatformError(message: String) {
        platformError = message
    }

    fun updateVisibleAttachments(attachmentIds: Set<String>) {
        val selectedSessionId = snapshot.selectedSessionId ?: return
        val sessionIds = currentSessionAttachmentIds()
        visibleAttachmentKeys = (attachmentIds intersect sessionIds).mapTo(mutableSetOf()) {
            gatewayAttachmentCacheKey(selectedSessionId, it)
        }
        pruneAttachmentStateForSession()
        visibleAttachmentKeys.forEach { cacheKey ->
            val attachmentId = attachmentIdFromCacheKey(cacheKey)
            val thumbnail = thumbnailCache.get(cacheKey)
            val state = attachmentStateCache.get(cacheKey)
            if (thumbnail == null && state == AttachmentLoadState.LOADED) {
                attachmentStateCache.put(cacheKey, AttachmentLoadState.DEFERRED)
            }
            if (
                thumbnail == null &&
                attachmentStateCache.get(cacheKey) !in setOf(
                    AttachmentLoadState.LOADING,
                    AttachmentLoadState.FAILED,
                    AttachmentLoadState.DEFERRED
                ) &&
                activeAttachment?.cacheKey != cacheKey &&
                attachmentQueue.none { it.cacheKey == cacheKey }
            ) {
                attachmentStateCache.put(cacheKey, AttachmentLoadState.LOADING)
                attachmentQueue.addLast(AttachmentRequest(selectedSessionId, attachmentId, cacheKey))
            }
        }
        publishAttachmentState()
        drainAttachmentQueue()
    }

    fun retryAttachment(attachmentId: String) {
        val key = cacheKeyForCurrentSession(attachmentId) ?: return
        if (key !in visibleAttachmentKeys) return
        graph?.diagnostics?.intent(GatewayDiagnosticAction.RETRY_ATTACHMENT, hasSession = true)
        attachmentStateCache.put(key, AttachmentLoadState.IDLE)
        updateVisibleAttachments(visibleAttachmentKeys.mapTo(mutableSetOf(), ::attachmentIdFromCacheKey))
    }

    fun updateThumbnailTargetSize(widthPixels: Int, heightPixels: Int) {
        if (widthPixels > 0) thumbnailTargetWidthPixels = widthPixels
        if (heightPixels > 0) thumbnailTargetHeightPixels = heightPixels
    }

    fun close() {
        projectionActor.close()
        runtimeCollection?.cancel()
        scope?.cancel()
    }

    val canSend: Boolean
        get() = gatewayState.connection == GatewayConnectionState.CONNECTED &&
            (messageDraft.isNotBlank() || preparedImages.isNotEmpty())

    private fun pruneAttachmentStateForSession() {
        val sessionId = snapshot.selectedSessionId
        val sessionKeys = if (sessionId == null) emptySet() else currentSessionAttachmentIds().mapTo(mutableSetOf()) {
            gatewayAttachmentCacheKey(sessionId, it)
        }
        thumbnailCache.retainKeys(sessionKeys)
        attachmentStateCache.retainKeys(sessionKeys)
        visibleAttachmentKeys = visibleAttachmentKeys intersect sessionKeys
        val retainedRequests = attachmentQueue.filter { it.cacheKey in visibleAttachmentKeys }
        attachmentQueue.clear()
        attachmentQueue.addAll(retainedRequests)
        publishAttachmentState()
    }

    private fun drainAttachmentQueue() {
        val appGraph = graph ?: return
        if (activeAttachment != null) return
        val request = attachmentQueue.removeFirstOrNull() ?: return
        activeAttachment = request
        appGraph.gatewayScope.launch {
            val cached = appGraph.gatewayRuntime.readCachedAttachment(request.sessionId, request.attachmentId)
            if (cached != null) {
                val published = publishThumbnail(request, cached)
                withContext(Dispatchers.Main.immediate) {
                    if (!published) markAttachmentFailed(request.cacheKey)
                    finishActiveAttachment(request.cacheKey)
                }
            } else if (!appGraph.gatewayRuntime.requestAttachment(request.sessionId, request.attachmentId)) {
                withContext(Dispatchers.Main.immediate) {
                    markAttachmentFailed(request.cacheKey)
                    finishActiveAttachment(request.cacheKey)
                }
            }
        }
    }

    private fun attachmentCompleted(sessionId: String, attachmentId: String) {
        val appGraph = graph ?: return
        val cacheKey = gatewayAttachmentCacheKey(sessionId, attachmentId)
        val request = activeAttachment?.takeIf { it.cacheKey == cacheKey } ?: return
        appGraph.gatewayScope.launch {
            val bytes = appGraph.gatewayRuntime.readCachedAttachment(sessionId, attachmentId)
            val published = bytes != null && publishThumbnail(request, bytes)
            withContext(Dispatchers.Main.immediate) {
                if (!published) markAttachmentFailed(cacheKey)
                finishActiveAttachment(cacheKey)
            }
        }
    }

    private fun attachmentFailed(sessionId: String?, attachmentId: String?) {
        val id = attachmentId ?: return
        val session = sessionId ?: activeAttachment?.sessionId ?: return
        val cacheKey = gatewayAttachmentCacheKey(session, id)
        if (activeAttachment?.cacheKey != cacheKey) return
        removeQueuedAttachment(cacheKey)
        markAttachmentFailed(cacheKey)
        finishActiveAttachment(cacheKey)
    }

    private fun removeQueuedAttachment(cacheKey: String) {
        val retained = attachmentQueue.filterNot { it.cacheKey == cacheKey }
        attachmentQueue.clear()
        attachmentQueue.addAll(retained)
    }

    private fun finishActiveAttachment(cacheKey: String) {
        if (activeAttachment?.cacheKey != cacheKey) return
        activeAttachment = null
        drainAttachmentQueue()
    }

    private suspend fun publishThumbnail(request: AttachmentRequest, bytes: ByteArray): Boolean {
        val appGraph = graph ?: return false
        if (!isAttachmentVisible(request.cacheKey)) return false
        val bitmap = appGraph.attachmentThumbnailer.decode(
            bytes,
            thumbnailTargetWidthPixels,
            thumbnailTargetHeightPixels
        ) ?: return false
        val imageBitmap = bitmap.asImageBitmap()
        return withContext(Dispatchers.Main.immediate) {
            if (!isAttachmentVisible(request.cacheKey)) {
                bitmap.recycle()
                return@withContext false
            }
            commitThumbnail(request.cacheKey, imageBitmap)
        }
    }

    private fun commitThumbnail(cacheKey: String, imageBitmap: ImageBitmap): Boolean {
        val evicted = thumbnailCache.put(cacheKey, imageBitmap)
        attachmentStateCache.put(cacheKey, AttachmentLoadState.LOADED)
        evicted.filterNot { it == cacheKey }.forEach {
            attachmentStateCache.put(it, AttachmentLoadState.DEFERRED)
        }
        if (cacheKey in evicted) attachmentStateCache.put(cacheKey, AttachmentLoadState.DEFERRED)
        publishAttachmentState()
        return cacheKey !in evicted
    }

    private fun isAttachmentVisible(cacheKey: String): Boolean = cacheKey in visibleAttachmentKeys

    private fun currentSessionAttachmentIds(): Set<String> =
        snapshot.conversation.flatMap { it.images }.mapTo(mutableSetOf()) { it.attachmentId }

    private fun markAttachmentFailed(cacheKey: String) {
        val currentKeys = snapshot.selectedSessionId?.let { sessionId ->
            currentSessionAttachmentIds().mapTo(mutableSetOf()) { gatewayAttachmentCacheKey(sessionId, it) }
        }.orEmpty()
        if (cacheKey !in currentKeys) return
        attachmentStateCache.put(
            cacheKey,
            if (cacheKey in visibleAttachmentKeys) AttachmentLoadState.FAILED
            else AttachmentLoadState.IDLE
        )
        publishAttachmentState()
    }

    private fun publishAttachmentState() {
        val sessionId = snapshot.selectedSessionId
        if (sessionId == null) {
            attachmentThumbnails = emptyMap()
            attachmentStates = emptyMap()
            return
        }
        attachmentThumbnails = thumbnailCache.snapshot().mapKeys { attachmentIdFromCacheKey(it.key) }
        attachmentStates = attachmentStateCache.snapshot().mapKeys { attachmentIdFromCacheKey(it.key) }
    }

    private fun cacheKeyForCurrentSession(attachmentId: String): String? =
        snapshot.selectedSessionId?.let { gatewayAttachmentCacheKey(it, attachmentId) }

    private fun attachmentIdFromCacheKey(cacheKey: String): String {
        val firstColon = cacheKey.indexOf(':')
        val sessionLength = cacheKey.substring(0, firstColon).toInt()
        return cacheKey.substring(firstColon + 1 + sessionLength + 1)
    }

    private data class AttachmentRequest(
        val sessionId: String,
        val attachmentId: String,
        val cacheKey: String
    )

    internal data class MessageSubmission(
        val generation: Long,
        val sessionId: String?,
        val draft: String,
        val images: List<AndroidPreparedImage>
    )

    companion object {
        private const val MAXIMUM_THUMBNAIL_BYTES = 16L * 1_024 * 1_024
        private const val MAXIMUM_STATUS_COUNT = 256L
        private const val DEFAULT_THUMBNAIL_TARGET_PIXELS = 720
        const val DEFAULT_WIRE_PAYLOAD =
            """{"sessionId":"android-demo","seq":4,"time":1786937355,"event":{"type":"assistant/message","turn":1,"step":1,"text":"最终消息会替换流式临时消息。"}}"""
    }
}

enum class AttachmentLoadState { IDLE, LOADING, LOADED, FAILED, DEFERRED }
