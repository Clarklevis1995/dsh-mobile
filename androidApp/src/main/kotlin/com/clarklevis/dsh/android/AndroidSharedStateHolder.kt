package com.clarklevis.dsh.android

import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import com.clarklevis.dsh.shared.facade.SharedSlashCommandSnapshot
import com.clarklevis.dsh.shared.facade.SharedSlashCommandStore
import com.clarklevis.dsh.shared.facade.SharedSlashCommandTransition
import com.clarklevis.dsh.shared.facade.SharedWorkspaceFileStore
import com.clarklevis.dsh.shared.facade.SharedWorkspaceFileTransition
import com.clarklevis.dsh.shared.gateway.GatewayConnectionState
import com.clarklevis.dsh.shared.gateway.GatewayPairingPayloadException
import com.clarklevis.dsh.shared.gateway.GatewayRuntimeEvent
import com.clarklevis.dsh.shared.gateway.GatewayRuntimeState
import com.clarklevis.dsh.shared.gateway.GatewayRequests
import com.clarklevis.dsh.shared.gateway.gatewayAttachmentCacheKey
import com.clarklevis.dsh.shared.protocol.GatewayDirectoryItem
import com.clarklevis.dsh.shared.protocol.GatewayFrame
import com.clarklevis.dsh.shared.protocol.GatewayGoalRef
import com.clarklevis.dsh.shared.protocol.GatewayQuestionAnswer
import com.clarklevis.dsh.shared.protocol.GatewayApprovalOutcome
import com.clarklevis.dsh.shared.protocol.GatewayWorkspace
import com.clarklevis.dsh.shared.projection.TrajectoryNode
import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder
import java.util.TimeZone
import java.util.Locale
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Stable
class AndroidSharedStateHolder(
    store: SharedMobileStore = SharedMobileStore(),
    private val graph: AndroidAppGraph? = null
) {
    private val workspaceFileStore = SharedWorkspaceFileStore()
    private val slashCommandStore = SharedSlashCommandStore()
    private val scope = graph?.let { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    private var runtimeCollection: Job? = null
    private val projectionActor = AndroidProjectionActor(
        projection = AndroidGatewayProjection(store) { sessionId, beforeSequence ->
            graph?.let { appGraph ->
                appGraph.gatewayScope.launch {
                    appGraph.gatewayRuntime.requestHistory(
                        sessionId = sessionId,
                        beforeSequence = beforeSequence,
                        maxMessages = HISTORY_PAGE_MESSAGE_LIMIT,
                        maxBytes = HISTORY_PAGE_BYTE_BUDGET,
                        view = HISTORY_VIEW
                    )
                }
            }
        },
        uiDispatcher = if (graph == null) Dispatchers.Unconfined else Dispatchers.Main.immediate,
        publish = ::publishProjectionSnapshot
    )
    private var pendingStreamingSnapshot: SharedMobileSnapshot? = null
    private var streamingSnapshotPublishJob: Job? = null
    private val attachmentQueue = ArrayDeque<AttachmentRequest>()
    private var activeAttachment: AttachmentRequest? = null
    private var visibleAttachmentKeys: Set<String> = emptySet()
    private var thumbnailTargetWidthPixels = DEFAULT_THUMBNAIL_TARGET_PIXELS
    private var thumbnailTargetHeightPixels = DEFAULT_THUMBNAIL_TARGET_PIXELS
    private var lastObservedConnection = GatewayConnectionState.DISCONNECTED
    private var workspacePreferenceLoaded = false
    private var hasReceivedWorkspaces = false
    private var pendingDirectoryCreationParentPath: String? = null
    private var workspaceFileOutput: FileOutputStream? = null
    private var workspaceFileTemporaryFile: File? = null
    private var recentlyCreatedWorkspace: GatewayWorkspace? by mutableStateOf(null)
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
    var directoryPath: String? by mutableStateOf(null)
        private set
    var directoryHome: String? by mutableStateOf(null)
        private set
    var directoryCrumbs: List<GatewayDirectoryItem> by mutableStateOf(emptyList())
        private set
    var directoryEntries: List<GatewayDirectoryItem> by mutableStateOf(emptyList())
        private set
    var directoryIsLoading: Boolean by mutableStateOf(false)
        private set
    var directoryCreationIsLoading: Boolean by mutableStateOf(false)
        private set
    var workspaceCreationIsLoading: Boolean by mutableStateOf(false)
        private set
    var createdDirectoryPathToReveal: String? by mutableStateOf(null)
        private set
    var workspaceCreationCompletedPath: String? by mutableStateOf(null)
        private set
    var workspaceFilePath: String by mutableStateOf(".")
        private set
    var workspaceFileEntries: List<GatewayDirectoryItem> by mutableStateOf(emptyList())
        private set
    var workspaceFilesAreLoading: Boolean by mutableStateOf(false)
        private set
    var workspaceFileDownloadProgress: Float? by mutableStateOf(null)
        private set
    var workspaceFileDownloadPath: String? by mutableStateOf(null)
        private set
    var workspaceFileDownloadPurpose: String? by mutableStateOf(null)
        private set
    var completedWorkspaceFile: AndroidWorkspaceLocalFile? by mutableStateOf(null)
        private set
    var slashCommands: SharedSlashCommandSnapshot by mutableStateOf(slashCommandStore.snapshot())
        private set
    var historyPagingSessionIds: Set<String> by mutableStateOf(emptySet())
        private set
    var trajectoryNodes: List<TrajectoryNode> by mutableStateOf(emptyList())
        private set
    private var trajectoryIsActive = false
    private var messageDraftState: String by mutableStateOf("")
    private var pendingCommandSubmission: MessageSubmission? = null
    var messageDraft: String
        get() = messageDraftState
        set(value) {
            if (messageDraftState != value) {
                messageDraftState = value
                inputGeneration += 1
                applySlashCommandTransition(
                    slashCommandStore.updateInput(
                        sessionId = pendingSelectedSessionId ?: snapshot.selectedSessionId,
                        text = value,
                        isConnected = gatewayState.connection == GatewayConnectionState.CONNECTED,
                        isSupported = "commands" in gatewayState.capabilities,
                        locale = Locale.getDefault().toLanguageTag()
                    )
                )
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
    var successfulMessageSendCount: Long by mutableLongStateOf(0L)
        private set
    var defaultConfigurationLoadingKinds: Set<String> by mutableStateOf(emptySet())
        private set
    var agentPresetsAuthorable: Boolean by mutableStateOf(false)
        private set
    var agentPresetsHasDocument: Boolean by mutableStateOf(false)
        private set
    var goalMutationKind: String? by mutableStateOf(null)
        private set

    init {
        graph?.let { appGraph ->
            val holderScope = requireNotNull(scope)
            runtimeCollection = appGraph.gatewayScope.launch {
                launch {
                    appGraph.preferences.snapshots.collect { value ->
                        withContext(Dispatchers.Main.immediate) {
                            endpoint = value.endpoint
                            selectedWorkspaceId = value.selectedWorkspaceId
                            workspacePreferenceLoaded = true
                            if (hasReceivedWorkspaces) reconcileWorkspaceSelection()
                        }
                    }
                }
                launch {
                    appGraph.gatewayRuntime.state.collect { state ->
                        appGraph.diagnostics.runtimeState(state)
                        var shouldCatchUpSelectedHistory = false
                        var sessionIdToRefresh: String? = null
                        withContext(Dispatchers.Main.immediate) {
                            val didReconnect = state.connection == GatewayConnectionState.CONNECTED &&
                                lastObservedConnection != GatewayConnectionState.CONNECTED
                            gatewayState = state
                            if (state.connection != GatewayConnectionState.CONNECTED &&
                                state.connection != GatewayConnectionState.CONNECTING &&
                                state.connection != GatewayConnectionState.AUTHENTICATING
                            ) {
                                defaultConfigurationLoadingKinds = emptySet()
                                clearWorkspaceRequestLoading()
                            }
                            if (
                                didReconnect &&
                                activeAttachment != null
                            ) {
                                attachmentQueue.addFirst(requireNotNull(activeAttachment))
                                activeAttachment = null
                                drainAttachmentQueue()
                            }
                            if (didReconnect) {
                                shouldCatchUpSelectedHistory = snapshot.selectedSessionId != null
                                sessionIdToRefresh = snapshot.selectedSessionId
                            }
                            lastObservedConnection = state.connection
                        }
                        // 订阅只补发重连后的实时事件。重新拉取 latest history，按 seq 与
                        // 本地 live tail 合并，补齐应用在后台断线期间由其他端发送的用户消息。
                        if (shouldCatchUpSelectedHistory) {
                            projectionActor.catchUpSelectedHistoryAfterReconnect()
                        }
                        sessionIdToRefresh?.let { requestSessionControls(appGraph, it) }
                    }
                }
                launch {
                    appGraph.gatewayRuntime.events.collect { event ->
                        appGraph.diagnostics.runtimeEvent(event)
                        when (event) {
                            is GatewayRuntimeEvent.Frame -> {
                                if (event.frame.kind in GOAL_MUTATION_RESPONSE_KINDS) {
                                    withContext(Dispatchers.Main.immediate) { goalMutationKind = null }
                                    event.frame.sessionId?.let { sessionId ->
                                        appGraph.gatewayRuntime.sendRequest(GatewayRequests.goal(sessionId))
                                    }
                                }
                                if (event.frame.kind == "command-executed") {
                                    handleCommandExecuted(event.frame)
                                }
                                if (event.frame.kind in SLASH_COMMAND_FRAME_KINDS ||
                                    (event.frame.kind == "error" && event.frame.requestType in SLASH_COMMAND_REQUEST_TYPES)
                                ) {
                                    applySlashCommandTransition(slashCommandStore.acceptFrame(event.rawJson))
                                }
                                if (event.frame.kind in WORKSPACE_FILE_FRAME_KINDS) {
                                    applyWorkspaceFileTransition(
                                        appGraph,
                                        workspaceFileStore.acceptFrame(event.rawJson)
                                    )
                                }
                                val isStreamingChunk = event.frame.kind == "event" &&
                                    event.frame.event?.type == "assistant/chunk"
                                val streamingProjectionFlushed = if (isStreamingChunk) {
                                    projectionActor.acceptStreamingFrame(
                                        event.rawJson,
                                        event.frame,
                                        event.correlatedSessionId
                                    )
                                } else {
                                    projectionActor.acceptFrame(
                                        event.rawJson,
                                        event.frame,
                                        event.correlatedSessionId
                                    ) {
                                        pruneAttachmentStateForSession()
                                        handleWorkspaceFrame(event.frame)
                                        if (event.frame.kind == "history") {
                                            event.correlatedSessionId?.let { sessionId ->
                                                historyPagingSessionIds = historyPagingSessionIds - sessionId
                                            }
                                        }
                                    }
                                    false
                                }
                                if (!isStreamingChunk) {
                                    withContext(Dispatchers.Main.immediate) {
                                        defaultConfigurationLoadingKinds =
                                            defaultConfigurationLoadingKinds - event.frame.kind
                                        if (event.frame.kind == "agent-presets") {
                                            agentPresetsAuthorable = event.frame.authorable == true
                                            agentPresetsHasDocument = event.frame.hasDocument == true
                                        }
                                        if (event.frame.kind.startsWith("approval")) {
                                            appGraph.diagnostics.approval(
                                                stage = "frame-applied",
                                                frameKind = event.frame.kind,
                                                hasRpc = !event.frame.rpcId.isNullOrBlank(),
                                                hasSession = !event.frame.sessionId.isNullOrBlank(),
                                                hasApprovalId = !event.frame.approvalId.isNullOrBlank(),
                                                hasTool = !event.frame.toolName.isNullOrBlank(),
                                                replay = event.frame.replay == true,
                                                pendingCount = snapshot.pendingApprovals.size,
                                                selectedVisible = snapshot.pendingApprovals.any {
                                                    it.sessionId == snapshot.selectedSessionId
                                                }
                                            )
                                        }
                                    }
                                    if (event.frame.kind == "sent") {
                                        event.frame.sessionId?.takeIf(String::isNotBlank)?.let { sessionId ->
                                            handleSentSession(appGraph, sessionId)
                                        }
                                    }
                                }
                                if (trajectoryIsActive && (!isStreamingChunk || streamingProjectionFlushed)) {
                                    publishTrajectory()
                                }
                            }
                            is GatewayRuntimeEvent.AttachmentCached -> withContext(Dispatchers.Main.immediate) {
                                attachmentCompleted(event.sessionId, event.attachmentId)
                            }
                            is GatewayRuntimeEvent.RequestQueued -> Unit
                            is GatewayRuntimeEvent.RequestCancelled -> {
                                if (event.requestType == "command-execute") {
                                    pendingCommandSubmission = null
                                }
                                if (event.requestType in SLASH_COMMAND_REQUEST_TYPES) {
                                    applySlashCommandTransition(
                                        slashCommandStore.requestFailed(event.requestType, event.reason)
                                    )
                                }
                                if (event.requestType in WORKSPACE_FILE_REQUEST_TYPES) {
                                    applyWorkspaceFileTransition(
                                        appGraph,
                                        workspaceFileStore.requestFailed(
                                            event.requestType,
                                            event.reason,
                                            event.correlationId
                                        )
                                    )
                                }
                                withContext(Dispatchers.Main.immediate) {
                                    defaultConfigurationLoadingKinds =
                                        defaultConfigurationLoadingKinds - event.requestType
                                    if (event.requestType == "history") {
                                        event.targetSessionId?.let {
                                            historyPagingSessionIds = historyPagingSessionIds - it
                                        }
                                    }
                                }
                                event.targetSessionId?.takeIf { event.requestType == "history" }
                                    ?.let { projectionActor.historyCancelled(it) }
                                if (event.requestType == "attachment") {
                                    withContext(Dispatchers.Main.immediate) {
                                        attachmentFailed(event.targetSessionId, event.correlationId)
                                    }
                                }
                                if (event.requestType == "approval-response") {
                                    event.correlationId?.let {
                                        projectionActor.approvalRequestFailed(it, event.reason)
                                    }
                                }
                                finishGoalMutationAfterFailure(
                                    appGraph,
                                    event.requestType,
                                    event.targetSessionId,
                                    event.reason
                                )
                            }
                            is GatewayRuntimeEvent.RequestTimedOut -> {
                                if (event.requestType == "command-execute") {
                                    pendingCommandSubmission = null
                                }
                                if (event.requestType in SLASH_COMMAND_REQUEST_TYPES) {
                                    applySlashCommandTransition(
                                        slashCommandStore.requestFailed(event.requestType, "request-timeout")
                                    )
                                }
                                if (event.requestType in WORKSPACE_FILE_REQUEST_TYPES) {
                                    applyWorkspaceFileTransition(
                                        appGraph,
                                        workspaceFileStore.requestFailed(
                                            event.requestType,
                                            "request-timeout",
                                            event.correlationId
                                        )
                                    )
                                }
                                withContext(Dispatchers.Main.immediate) {
                                    defaultConfigurationLoadingKinds =
                                        defaultConfigurationLoadingKinds - event.requestType
                                    clearWorkspaceRequestLoading(event.requestType)
                                    if (event.requestType == "history") {
                                        event.targetSessionId?.let {
                                            historyPagingSessionIds = historyPagingSessionIds - it
                                        }
                                    }
                                }
                                event.targetSessionId?.takeIf { event.requestType == "history" }
                                    ?.let { projectionActor.historyTimedOut(it) }
                                if (event.requestType == "attachment") {
                                    withContext(Dispatchers.Main.immediate) {
                                        attachmentFailed(event.targetSessionId, event.correlationId)
                                    }
                                }
                                if (event.requestType == "approval-response") {
                                    event.correlationId?.let {
                                        projectionActor.approvalRequestFailed(it, "request-timeout")
                                    }
                                }
                                finishGoalMutationAfterFailure(
                                    appGraph,
                                    event.requestType,
                                    event.targetSessionId,
                                    "request-timeout"
                                )
                            }
                            is GatewayRuntimeEvent.RequestRejected -> {
                                if (event.requestType == "command-execute") {
                                    pendingCommandSubmission = null
                                }
                                if (event.requestType in SLASH_COMMAND_REQUEST_TYPES) {
                                    applySlashCommandTransition(
                                        slashCommandStore.requestFailed(event.requestType, event.reason)
                                    )
                                }
                                if (event.requestType in WORKSPACE_FILE_REQUEST_TYPES) {
                                    applyWorkspaceFileTransition(
                                        appGraph,
                                        workspaceFileStore.requestFailed(
                                            event.requestType,
                                            event.reason,
                                            event.correlationId
                                        )
                                    )
                                }
                                withContext(Dispatchers.Main.immediate) {
                                    defaultConfigurationLoadingKinds =
                                        defaultConfigurationLoadingKinds - event.requestType
                                    clearWorkspaceRequestLoading(event.requestType)
                                    platformError = "${event.requestType}: ${event.reason}"
                                    if (event.requestType == "history") {
                                        event.targetSessionId?.let {
                                            historyPagingSessionIds = historyPagingSessionIds - it
                                        }
                                    }
                                }
                                event.targetSessionId?.takeIf { event.requestType == "history" }
                                    ?.let { projectionActor.historyCancelled(it) }
                                if (event.requestType == "attachment") {
                                    withContext(Dispatchers.Main.immediate) {
                                        attachmentFailed(event.targetSessionId, event.correlationId)
                                    }
                                }
                                if (event.requestType == "approval-response") {
                                    val correlationId = event.correlationId
                                    val targetSessionId = event.targetSessionId
                                    if (correlationId != null) {
                                        projectionActor.approvalRequestFailed(
                                            correlationId,
                                            event.reason
                                        )
                                    } else if (targetSessionId != null) {
                                        projectionActor.approvalSessionRequestsFailed(
                                            targetSessionId,
                                            event.reason
                                        )
                                    }
                                }
                                finishGoalMutationAfterFailure(
                                    appGraph,
                                    event.requestType,
                                    event.targetSessionId,
                                    event.reason
                                )
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
                .onSuccess { frame ->
                    projectionActor.acceptFrameImmediate(payload, frame, frame.sessionId)
                    handleWorkspaceFrame(frame)
                }
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
        applySlashCommandTransition(slashCommandStore.reset(sessionId))
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
                if (trajectoryIsActive) publishTrajectory()
                if (gatewayState.connection == GatewayConnectionState.CONNECTED) {
                    appGraph.gatewayRuntime.subscribe(sessionId)
                    appGraph.diagnostics.approval(
                        stage = "session-subscribed",
                        hasSession = true,
                        pendingCount = snapshot.pendingApprovals.size,
                        selectedVisible = snapshot.pendingApprovals.any {
                            it.sessionId == snapshot.selectedSessionId
                        }
                    )
                }
            }
        }
    }

    fun prepareNewSession() {
        pendingSelectedSessionId = null
        inputGeneration += 1
        applySlashCommandTransition(slashCommandStore.reset(null))
        val afterPublish = {
            visibleAttachmentKeys = emptySet()
            pruneAttachmentStateForSession()
        }
        val appGraph = graph
        if (appGraph == null) projectionActor.selectSessionImmediate(null, afterPublish)
        else appGraph.gatewayScope.launch {
            projectionActor.selectSession(null, afterPublish)
            if (trajectoryIsActive) publishTrajectory()
            if (gatewayState.connection == GatewayConnectionState.CONNECTED) {
                appGraph.gatewayRuntime.subscribe(null)
            }
        }
    }

    val availableWorkspaces: List<GatewayWorkspace>
        get() {
            val created = recentlyCreatedWorkspace ?: return snapshot.workspaces
            return if (snapshot.workspaces.any { it.workspaceId == created.workspaceId }) {
                snapshot.workspaces
            } else {
                snapshot.workspaces + created
            }
        }

    val activeWorkspace: GatewayWorkspace?
        get() {
            if (selectedWorkspaceId == UNGROUPED_WORKSPACE_ID) return null
            return availableWorkspaces.firstOrNull { it.workspaceId == selectedWorkspaceId }
                ?: availableWorkspaces.firstOrNull()
        }

    val isUngroupedWorkspaceSelected: Boolean
        get() = selectedWorkspaceId == UNGROUPED_WORKSPACE_ID || availableWorkspaces.isEmpty()

    fun selectWorkspace(workspaceId: String?) {
        val resolved = workspaceId ?: resolveWorkspaceSelection(null, availableWorkspaces)
        applyWorkspaceSelection(resolved, persist = true)
    }

    fun beginDirectoryBrowsing() {
        directoryPath = null
        directoryHome = null
        directoryCrumbs = emptyList()
        directoryEntries = emptyList()
        createdDirectoryPathToReveal = null
        workspaceCreationCompletedPath = null
        browseDirectories()
    }

    fun browseDirectories(path: String? = null) {
        val appGraph = graph ?: return
        if (gatewayState.connection != GatewayConnectionState.CONNECTED) {
            platformError = "请先连接 DeepSeek Harness"
            return
        }
        directoryIsLoading = true
        appGraph.gatewayScope.launch {
            val accepted = appGraph.gatewayRuntime.sendRequest(GatewayRequests.directories(path))
            if (!accepted) withContext(Dispatchers.Main.immediate) {
                directoryIsLoading = false
                platformError = "目录请求正在处理中，请稍后重试"
            }
        }
    }

    fun createDirectory(parentPath: String, name: String) {
        val appGraph = graph ?: return
        if (gatewayState.connection != GatewayConnectionState.CONNECTED) {
            platformError = "请先连接 DeepSeek Harness"
            return
        }
        val normalizedName = name.trim()
        if (normalizedName.isEmpty()) {
            platformError = "文件夹名称不能为空"
            return
        }
        pendingDirectoryCreationParentPath = parentPath
        directoryCreationIsLoading = true
        appGraph.gatewayScope.launch {
            val accepted = appGraph.gatewayRuntime.sendRequest(
                GatewayRequests.createDirectory(parentPath, normalizedName)
            )
            if (!accepted) withContext(Dispatchers.Main.immediate) {
                pendingDirectoryCreationParentPath = null
                directoryCreationIsLoading = false
                platformError = "文件夹创建请求正在处理中，请稍后重试"
            }
        }
    }

    fun acknowledgeCreatedDirectoryReveal(path: String) {
        if (createdDirectoryPathToReveal == path) createdDirectoryPathToReveal = null
    }

    fun createWorkspace(path: String) {
        val appGraph = graph ?: return
        if (gatewayState.connection != GatewayConnectionState.CONNECTED) {
            platformError = "请先连接 DeepSeek Harness"
            return
        }
        workspaceCreationCompletedPath = null
        workspaceCreationIsLoading = true
        appGraph.gatewayScope.launch {
            val accepted = appGraph.gatewayRuntime.sendRequest(GatewayRequests.createWorkspace(path))
            if (!accepted) withContext(Dispatchers.Main.immediate) {
                workspaceCreationIsLoading = false
                platformError = "工作区创建请求正在处理中，请稍后重试"
            }
        }
    }

    fun acknowledgeWorkspaceCreation(path: String) {
        if (workspaceCreationCompletedPath == path) workspaceCreationCompletedPath = null
    }

    fun browseWorkspaceFiles(path: String? = null) {
        val appGraph = graph ?: return
        val sessionId = snapshot.selectedSessionId
        if (sessionId == null) {
            platformError = "请先打开一个已有会话"
            return
        }
        if (gatewayState.connection != GatewayConnectionState.CONNECTED) {
            platformError = "请先连接 DeepSeek Harness"
            return
        }
        if ("file-downloads" !in gatewayState.capabilities) {
            platformError = "当前 Mobile Gateway 不支持文件下载，请升级并重启网关。"
            return
        }
        appGraph.gatewayScope.launch {
            applyWorkspaceFileTransition(
                appGraph,
                workspaceFileStore.load(sessionId, path, UUID.randomUUID().toString())
            )
        }
    }

    fun openWorkspaceFile(entry: GatewayDirectoryItem, purpose: String) {
        val appGraph = graph ?: return
        val sessionId = snapshot.selectedSessionId ?: return
        if (entry.kind != "file") return
        completedWorkspaceFile = null
        appGraph.gatewayScope.launch {
            applyWorkspaceFileTransition(
                appGraph,
                workspaceFileStore.download(
                    sessionId,
                    entry.path,
                    UUID.randomUUID().toString(),
                    purpose
                )
            )
        }
    }

    fun cancelWorkspaceFileDownload() {
        val appGraph = graph ?: return
        appGraph.gatewayScope.launch {
            applyWorkspaceFileTransition(appGraph, workspaceFileStore.cancel())
        }
    }

    fun consumeCompletedWorkspaceFile() {
        completedWorkspaceFile = null
    }

    fun refreshProductState() {
        val appGraph = graph ?: return
        if (gatewayState.connection != GatewayConnectionState.CONNECTED) return
        defaultConfigurationLoadingKinds = defaultConfigurationLoadingKinds + setOf(
            "agent-presets",
            "defaults",
            "default-model",
            "models"
        )
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

    fun refreshDefaultConfiguration() {
        val appGraph = graph ?: return
        if (gatewayState.connection != GatewayConnectionState.CONNECTED) return
        defaultConfigurationLoadingKinds = defaultConfigurationLoadingKinds + setOf(
            "agent-presets",
            "defaults",
            "default-model"
        )
        appGraph.gatewayScope.launch {
            appGraph.gatewayRuntime.sendRequest(GatewayRequests.simple("agent-presets"))
            appGraph.gatewayRuntime.sendRequest(GatewayRequests.simple("defaults"))
            appGraph.gatewayRuntime.sendRequest(GatewayRequests.simple("default-model"))
        }
    }

    fun ensureDefaultModelConfiguration() {
        val appGraph = graph ?: return
        if (gatewayState.connection != GatewayConnectionState.CONNECTED) return
        defaultConfigurationLoadingKinds =
            defaultConfigurationLoadingKinds + setOf("models", "default-model")
        appGraph.gatewayScope.launch {
            appGraph.gatewayRuntime.sendRequest(GatewayRequests.simple("models"))
            appGraph.gatewayRuntime.sendRequest(GatewayRequests.simple("default-model"))
        }
    }

    fun pingGateway() {
        val appGraph = graph ?: return
        if (gatewayState.connection != GatewayConnectionState.CONNECTED) return
        appGraph.gatewayScope.launch { appGraph.gatewayRuntime.sendRequest(GatewayRequests.ping()) }
    }

    fun reloadSelectedHistory() {
        val sessionId = snapshot.selectedSessionId ?: return
        val appGraph = graph ?: return
        if (gatewayState.connection != GatewayConnectionState.CONNECTED) return
        appGraph.gatewayScope.launch {
            projectionActor.loadHistory(sessionId, older = false)
        }
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
        snapshot.selectedHistoryEarliestSequence ?: return
        if (
            !snapshot.selectedHistoryHasMore ||
            snapshot.selectedHistoryIsLoading ||
            sessionId in historyPagingSessionIds
        ) return
        val appGraph = graph ?: return
        historyPagingSessionIds = historyPagingSessionIds + sessionId
        appGraph.gatewayScope.launch {
            projectionActor.loadHistory(sessionId, older = true)
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
        val appGraph = graph ?: return
        defaultConfigurationLoadingKinds = defaultConfigurationLoadingKinds + "set-default"
        appGraph.gatewayScope.launch {
            val accepted = appGraph.gatewayRuntime.sendRequest(GatewayRequests.setDefault(target, value))
            if (!accepted) withContext(Dispatchers.Main.immediate) {
                defaultConfigurationLoadingKinds = defaultConfigurationLoadingKinds - "set-default"
            }
        }
    }

    fun saveDefaultModel(provider: String, model: String, reasoningEffort: String?) {
        val appGraph = graph ?: return
        defaultConfigurationLoadingKinds = defaultConfigurationLoadingKinds + "save-default-model"
        appGraph.gatewayScope.launch {
            val accepted = appGraph.gatewayRuntime.sendRequest(
                GatewayRequests.saveDefaultModel(provider, model, reasoningEffort)
            )
            if (!accepted) withContext(Dispatchers.Main.immediate) {
                defaultConfigurationLoadingKinds =
                    defaultConfigurationLoadingKinds - "save-default-model"
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
            requestSessionControls(appGraph, sessionId)
        }
    }

    private suspend fun handleSentSession(appGraph: AndroidAppGraph, sessionId: String) {
        appGraph.gatewayRuntime.subscribe(sessionId)
        appGraph.gatewayRuntime.requestSessions()
        requestSessionControls(appGraph, sessionId)
    }

    private suspend fun requestSessionControls(appGraph: AndroidAppGraph, sessionId: String) {
        appGraph.gatewayRuntime.sendRequest(GatewayRequests.sessionControl("models", sessionId))
        appGraph.gatewayRuntime.sendRequest(GatewayRequests.sessionControl("permission-options", sessionId))
        appGraph.gatewayRuntime.sendRequest(GatewayRequests.sessionControl("context-usage", sessionId))
        appGraph.gatewayRuntime.sendRequest(GatewayRequests.sessionControl("session-stats", sessionId))
        if ("tasks" in gatewayState.capabilities) {
            appGraph.gatewayRuntime.sendRequest(GatewayRequests.tasks(sessionId))
        }
        if ("goals" in gatewayState.capabilities) {
            appGraph.gatewayRuntime.sendRequest(GatewayRequests.goal(sessionId))
        }
    }

    fun editGoal(objective: String) {
        submitGoalMutation("goal-edit") { sessionId, ref ->
            GatewayRequests.editGoal(sessionId, ref, objective = objective)
        }
    }

    fun pauseGoal() = submitGoalMutation("goal-pause") { sessionId, ref ->
        GatewayRequests.goalAction("goal-pause", sessionId, ref)
    }

    fun resumeGoal() = submitGoalMutation("goal-resume") { sessionId, ref ->
        GatewayRequests.goalAction("goal-resume", sessionId, ref)
    }

    fun clearGoal() = submitGoalMutation("goal-clear") { sessionId, ref ->
        GatewayRequests.goalAction("goal-clear", sessionId, ref)
    }

    private fun submitGoalMutation(
        type: String,
        request: (sessionId: String, ref: GatewayGoalRef) -> com.clarklevis.dsh.shared.gateway.GatewayRequest
    ) {
        val appGraph = graph ?: return
        val sessionId = snapshot.selectedSessionId
        val goal = snapshot.goalSnapshot?.goal?.goal
        if (sessionId == null || goal == null) return
        if (goal.phase.lowercase() == "complete") return
        if (gatewayState.connection != GatewayConnectionState.CONNECTED) {
            platformError = "请先连接 DeepSeek Harness"
            return
        }
        if ("goals" !in gatewayState.capabilities) {
            platformError = "当前 Mobile Gateway 不支持目标管理，请升级并重启网关。"
            return
        }
        if (goalMutationKind != null) return
        goalMutationKind = type
        appGraph.gatewayScope.launch {
            val accepted = appGraph.gatewayRuntime.sendRequest(request(sessionId, goal.ref))
            if (!accepted) withContext(Dispatchers.Main.immediate) {
                goalMutationKind = null
                platformError = "目标操作正在处理中，请稍后重试。"
            }
        }
    }

    private suspend fun finishGoalMutationAfterFailure(
        appGraph: AndroidAppGraph,
        requestType: String,
        sessionId: String?,
        reason: String
    ) {
        if (requestType !in GOAL_MUTATION_REQUEST_TYPES) return
        withContext(Dispatchers.Main.immediate) {
            goalMutationKind = null
            platformError = if (reason == "request-timeout") {
                "目标操作超时，已刷新当前目标后可重试。"
            } else {
                "目标操作未完成，已刷新当前目标后可重试。"
            }
        }
        sessionId?.let { appGraph.gatewayRuntime.sendRequest(GatewayRequests.goal(it)) }
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

    fun respondToApproval(rpcId: String, outcome: GatewayApprovalOutcome) {
        val appGraph = graph ?: return
        appGraph.gatewayScope.launch {
            val wireOutcome = when (outcome) {
                GatewayApprovalOutcome.ALLOWED_ONCE -> "allowed-once"
                GatewayApprovalOutcome.REJECTED -> "rejected"
            }
            val effect = projectionActor.submitApprovalDecision(
                rpcId,
                wireOutcome,
                gatewayState.connection == GatewayConnectionState.CONNECTED
            ) ?: return@launch
            val accepted = appGraph.gatewayRuntime.sendRequest(
                GatewayRequests.approvalResponse(
                    effect.rpcId,
                    effect.sessionId,
                    effect.approvalId,
                    outcome
                )
            )
            if (!accepted) {
                projectionActor.approvalRequestFailed(rpcId, "request-busy")
            }
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

    fun selectSlashCommand(name: String) {
        applySlashCommandTransition(slashCommandStore.selectCommand(name))
    }

    fun selectSlashCatalogItem(id: String) {
        applySlashCommandTransition(slashCommandStore.selectItem(id))
    }

    fun selectSlashCommandOption(optionId: String) {
        applySlashCommandTransition(slashCommandStore.selectOption(optionId))
    }

    fun dismissSlashCommandMenus() {
        applySlashCommandTransition(slashCommandStore.dismissMenus())
    }

    fun clearActiveSlashCommand() {
        applySlashCommandTransition(slashCommandStore.clearActiveCommand())
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
            val commandExecution = slashCommandStore.commandExecutionForInput(submission.draft)
            if (commandExecution != null) {
                if (submission.images.isNotEmpty() && !commandExecution.allowsImages) {
                    withContext(Dispatchers.Main.immediate) {
                        platformError = "此命令不支持图片"
                    }
                    return@launch
                }
                val sent = appGraph.gatewayRuntime.executeCommand(
                    line = commandExecution.line,
                    images = submission.images.map(AndroidPreparedImage::outgoing),
                    sessionId = submission.sessionId
                )
                withContext(Dispatchers.Main.immediate) {
                    if (sent) {
                        pendingCommandSubmission = submission
                    }
                }
            } else {
                val sent = appGraph.gatewayRuntime.sendMessage(
                    text = submission.draft,
                    images = submission.images.map(AndroidPreparedImage::outgoing),
                    sessionId = submission.sessionId,
                    workspaceId = activeWorkspace?.workspaceId,
                    clientTimeZone = TimeZone.getDefault().id
                )
                withContext(Dispatchers.Main.immediate) { applyMessageSendResult(submission, sent) }
            }
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
        draft = composedMessageText(),
        images = preparedImages.toList()
    )

    private fun applyMessageSendResult(submission: MessageSubmission, sent: Boolean) {
        if (!sent) return
        if (
            inputGeneration != submission.generation ||
            composedMessageText() != submission.draft ||
            preparedImages != submission.images ||
            (pendingSelectedSessionId ?: snapshot.selectedSessionId) != submission.sessionId
        ) {
            return
        }
        messageDraft = ""
        applySlashCommandTransition(slashCommandStore.clearActiveCommand())
        preparedImages = emptyList()
        successfulMessageSendCount += 1
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
        streamingSnapshotPublishJob?.cancel()
        projectionActor.close()
        runtimeCollection?.cancel()
        scope?.cancel()
    }

    val canSend: Boolean
        get() = gatewayState.connection == GatewayConnectionState.CONNECTED &&
            pendingCommandSubmission == null &&
            (composedMessageText().isNotBlank() || preparedImages.isNotEmpty())

    private fun composedMessageText(): String {
        return messageDraft
    }

    private fun applySlashCommandTransition(transition: SharedSlashCommandTransition) {
        slashCommands = transition.snapshot
        transition.snapshot.lastError?.let { platformError = slashCommandErrorMessage(it) }
        val replacementText = transition.replacementText
        if (replacementText != null && messageDraftState != replacementText) {
            messageDraftState = replacementText
            inputGeneration += 1
        } else if (replacementText == null && transition.clearDraft && messageDraftState.isNotEmpty()) {
            messageDraftState = ""
            inputGeneration += 1
        }
        if (transition.selectedModel != null ||
            (transition.clearDraft && transition.snapshot.selections.isNotEmpty())
        ) refreshSessionControls()
        transition.submitText?.let(::sendSlashCommandImmediately)
        transition.commandExecution?.let(::executeSlashCommandImmediately)
        val request = transition.request ?: return
        val appGraph = graph ?: return
        appGraph.gatewayScope.launch {
            val accepted = appGraph.gatewayRuntime.sendRequest(request)
            if (!accepted) withContext(Dispatchers.Main.immediate) {
                applySlashCommandTransition(
                    slashCommandStore.requestFailed(request.requestType, "request-busy")
                )
            }
        }
    }

    private fun sendSlashCommandImmediately(text: String) {
        val appGraph = graph ?: return
        val sessionId = snapshot.selectedSessionId ?: return
        appGraph.gatewayScope.launch {
            val sent = appGraph.gatewayRuntime.sendMessage(
                text = text,
                images = emptyList(),
                sessionId = sessionId,
                workspaceId = null,
                clientTimeZone = TimeZone.getDefault().id
            )
            if (sent) withContext(Dispatchers.Main.immediate) {
                successfulMessageSendCount += 1
            }
        }
    }

    private fun executeSlashCommandImmediately(command: com.clarklevis.dsh.shared.facade.SharedSlashCommandExecution) {
        val appGraph = graph ?: return
        val sessionId = snapshot.selectedSessionId ?: return
        appGraph.gatewayScope.launch {
            appGraph.gatewayRuntime.executeCommand(command.line, emptyList(), sessionId)
        }
    }

    private fun handleCommandExecuted(frame: GatewayFrame) {
        val result = frame.result?.objectValue
        val succeeded = result?.get("kind")?.stringValue == "success"
        val detail = result?.get("text")?.stringValue
        if (succeeded) {
            pendingCommandSubmission?.let { applyMessageSendResult(it, true) }
        } else {
            platformError = detail ?: frame.message ?: "命令执行失败"
        }
        pendingCommandSubmission = null
    }

    private fun slashCommandErrorMessage(code: String): String = when (code) {
        "command-frame-invalid", "command-catalog-invalid", "command-options-invalid",
        "command-selection-invalid" -> "斜杠命令协议响应无效"
        "request-timeout" -> "斜杠命令请求超时"
        "request-busy" -> "斜杠命令请求正在处理中"
        else -> "斜杠命令操作失败：$code"
    }

    /**
     * KMP 继续无损消费每个 token；Compose 只按稳定显示节奏接收最新快照。
     * 仅 assistant/chunk 可以合并；final、history、切换 session 等结构变化立即提交。
     */
    private fun publishProjectionSnapshot(
        next: SharedMobileSnapshot,
        coalesceWithDisplayFrame: Boolean
    ) {
        val holderScope = scope
        if (holderScope == null || !coalesceWithDisplayFrame) {
            pendingStreamingSnapshot = null
            streamingSnapshotPublishJob?.cancel()
            streamingSnapshotPublishJob = null
            snapshot = next
            return
        }

        pendingStreamingSnapshot = next
        if (streamingSnapshotPublishJob?.isActive == true) return
        streamingSnapshotPublishJob = holderScope.launch {
            delay(STREAMING_SNAPSHOT_INTERVAL_MILLISECONDS)
            pendingStreamingSnapshot?.let { snapshot = it }
            pendingStreamingSnapshot = null
            streamingSnapshotPublishJob = null
        }
    }

    private fun handleWorkspaceFrame(frame: GatewayFrame) {
        when (frame.kind) {
            "workspaces" -> {
                hasReceivedWorkspaces = true
                recentlyCreatedWorkspace = recentlyCreatedWorkspace?.takeUnless { created ->
                    snapshot.workspaces.any { it.workspaceId == created.workspaceId }
                }
                if (workspacePreferenceLoaded) reconcileWorkspaceSelection()
            }
            "directories" -> {
                directoryIsLoading = false
                directoryPath = frame.path
                directoryHome = frame.home
                directoryCrumbs = frame.crumbs.orEmpty()
                directoryEntries = frame.entries.orEmpty()
            }
            "directory-create" -> {
                directoryCreationIsLoading = false
                val parentPath = pendingDirectoryCreationParentPath
                pendingDirectoryCreationParentPath = null
                createdDirectoryPathToReveal = frame.path
                parentPath?.let(::browseDirectories)
            }
            "workspace-create" -> {
                workspaceCreationIsLoading = false
                val workspace = frame.workspace
                if (workspace == null) {
                    platformError = "Gateway 未返回创建后的工作区"
                    return
                }
                recentlyCreatedWorkspace = workspace
                workspaceCreationCompletedPath = workspace.path
                applyWorkspaceSelection(workspace.workspaceId, persist = true)
                refreshProductState()
            }
            "error" -> {
                clearWorkspaceRequestLoading(frame.requestType)
                if (frame.requestType in WORKSPACE_REQUEST_TYPES) {
                    platformError = frame.message ?: "工作区请求失败"
                }
            }
        }
    }

    private suspend fun applyWorkspaceFileTransition(
        appGraph: AndroidAppGraph,
        transition: SharedWorkspaceFileTransition
    ) {
        if (transition.discardTransferId != null) closeWorkspaceTemporaryFile(remove = true)
        val snapshot = transition.snapshot
        try {
            if (snapshot.activeDownload != null && workspaceFileOutput == null) {
                openWorkspaceTemporaryFile(appGraph, snapshot.activeDownload?.name ?: "download")
            }
            transition.appendBase64Data?.let { encoded ->
                val bytes = Base64.decode(encoded, Base64.NO_WRAP)
                requireNotNull(workspaceFileOutput).write(bytes)
            }
        } catch (_: Throwable) {
            val cancelled = workspaceFileStore.cancel()
            closeWorkspaceTemporaryFile(remove = true)
            withContext(Dispatchers.Main.immediate) {
                workspaceFileDownloadProgress = null
                workspaceFileDownloadPath = null
                workspaceFileDownloadPurpose = null
                platformError = "写入下载文件失败"
            }
            cancelled.request?.let { appGraph.gatewayRuntime.sendRequest(it) }
            return
        }

        var completed: AndroidWorkspaceLocalFile? = null
        transition.completion?.let { completion ->
            workspaceFileOutput?.fd?.sync()
            closeWorkspaceTemporaryFile(remove = false)
            val file = workspaceFileTemporaryFile
            if (file == null || file.length() != completion.size) {
                file?.delete()
                workspaceFileTemporaryFile = null
                withContext(Dispatchers.Main.immediate) {
                    platformError = "下载文件的本地大小校验失败"
                }
                return
            }
            completed = AndroidWorkspaceLocalFile(
                file = file,
                sessionId = completion.sessionId,
                remotePath = completion.path,
                name = completion.name,
                mediaType = completion.mediaType,
                purpose = completion.purpose
            )
        }

        withContext(Dispatchers.Main.immediate) {
            workspaceFilePath = snapshot.path
            workspaceFileEntries = snapshot.entries
            workspaceFilesAreLoading = snapshot.isLoading
            workspaceFileDownloadProgress = snapshot.activeDownload?.let { active ->
                if (active.size == 0L) 0f
                else (active.receivedBytes.toFloat() / active.size.toFloat()).coerceIn(0f, 1f)
            }
            workspaceFileDownloadPath = snapshot.activeDownload?.path
            workspaceFileDownloadPurpose = snapshot.activeDownload?.purpose
            snapshot.lastError?.let { platformError = workspaceFileErrorMessage(it) }
            completed?.let { completedWorkspaceFile = it }
        }
        transition.request?.let { appGraph.gatewayRuntime.sendRequest(it) }
    }

    private fun openWorkspaceTemporaryFile(appGraph: AndroidAppGraph, name: String) {
        closeWorkspaceTemporaryFile(remove = true)
        val directory = File(
            appGraph.application.cacheDir,
            "workspace-files/${UUID.randomUUID()}"
        ).apply { check(mkdirs() || isDirectory) }
        val file = File(directory, File(name).name)
        workspaceFileTemporaryFile = file
        workspaceFileOutput = FileOutputStream(file, false)
    }

    private fun closeWorkspaceTemporaryFile(remove: Boolean) {
        runCatching { workspaceFileOutput?.close() }
        workspaceFileOutput = null
        if (remove) {
            workspaceFileTemporaryFile?.let { file ->
                file.delete()
                file.parentFile?.delete()
            }
            workspaceFileTemporaryFile = null
        }
    }

    private fun workspaceFileErrorMessage(code: String): String = when (code) {
        "file-download-integrity-failed" -> "文件完整性校验失败，请重新下载。"
        "download-busy" -> "已有文件正在下载，请稍后再试。"
        "file-download-offset-mismatch" -> "文件分块顺序异常，下载已取消。"
        else -> "工作区文件请求失败：$code"
    }

    private fun applyWorkspaceSelection(workspaceId: String, persist: Boolean) {
        if (selectedWorkspaceId == workspaceId) return
        selectedWorkspaceId = workspaceId
        if (!persist) return
        graph?.let { appGraph ->
            appGraph.gatewayScope.launch {
                val current = appGraph.preferences.load()
                appGraph.preferences.update(current.copy(selectedWorkspaceId = workspaceId))
            }
        }
    }

    private fun reconcileWorkspaceSelection() {
        val resolved = resolveWorkspaceSelection(selectedWorkspaceId, availableWorkspaces)
        applyWorkspaceSelection(resolved, persist = resolved != selectedWorkspaceId)
    }

    private fun clearWorkspaceRequestLoading(requestType: String? = null) {
        if (requestType == null || requestType == "directories") directoryIsLoading = false
        if (requestType == null || requestType == "directory-create") {
            directoryCreationIsLoading = false
            pendingDirectoryCreationParentPath = null
        }
        if (requestType == null || requestType == "workspace-create") {
            workspaceCreationIsLoading = false
        }
    }

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
        private const val STREAMING_SNAPSHOT_INTERVAL_MILLISECONDS = 32L
        private const val HISTORY_PAGE_MESSAGE_LIMIT = 60
        private const val HISTORY_PAGE_BYTE_BUDGET = 4 * 1_024 * 1_024
        private const val HISTORY_VIEW = "conversation"
        const val UNGROUPED_WORKSPACE_ID = "__ungrouped__"

        private val WORKSPACE_REQUEST_TYPES = setOf(
            "directories",
            "directory-create",
            "workspace-create"
        )
        private val SLASH_COMMAND_REQUEST_TYPES = setOf("commands", "command-options", "command-select")
        private val SLASH_COMMAND_FRAME_KINDS = setOf("commands", "command-options", "command-selected")
        private val GOAL_MUTATION_REQUEST_TYPES = setOf(
            "goal-edit", "goal-pause", "goal-resume", "goal-clear"
        )
        private val GOAL_MUTATION_RESPONSE_KINDS = GOAL_MUTATION_REQUEST_TYPES
        private val WORKSPACE_FILE_FRAME_KINDS = setOf(
            "file-list", "file-download-opened", "file-download-chunk", "file-download-cancelled"
        )
        private val WORKSPACE_FILE_REQUEST_TYPES = setOf(
            "file-list", "file-download-open", "file-download-read", "file-download-cancel"
        )
        private const val MAXIMUM_THUMBNAIL_BYTES = 16L * 1_024 * 1_024
        private const val MAXIMUM_STATUS_COUNT = 256L
        private const val DEFAULT_THUMBNAIL_TARGET_PIXELS = 720
        const val DEFAULT_WIRE_PAYLOAD =
            """{"sessionId":"android-demo","seq":4,"time":1786937355,"event":{"type":"assistant/message","turn":1,"step":1,"text":"最终消息会替换流式临时消息。"}}"""
    }
}

internal fun resolveWorkspaceSelection(
    preferredWorkspaceId: String?,
    workspaces: List<GatewayWorkspace>
): String = when {
    preferredWorkspaceId == AndroidSharedStateHolder.UNGROUPED_WORKSPACE_ID ->
        AndroidSharedStateHolder.UNGROUPED_WORKSPACE_ID
    workspaces.any { it.workspaceId == preferredWorkspaceId } -> requireNotNull(preferredWorkspaceId)
    workspaces.isNotEmpty() -> workspaces.first().workspaceId
    else -> AndroidSharedStateHolder.UNGROUPED_WORKSPACE_ID
}

enum class AttachmentLoadState { IDLE, LOADING, LOADED, FAILED, DEFERRED }

data class AndroidWorkspaceLocalFile(
    val file: File,
    val sessionId: String,
    val remotePath: String,
    val name: String,
    val mediaType: String,
    val purpose: String
)
