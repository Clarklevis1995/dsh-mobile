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
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.android.platform.AndroidGatewayPreferences
import com.clarklevis.dsh.android.platform.AndroidImagePreprocessor
import com.clarklevis.dsh.android.platform.AndroidPreparedImage
import com.clarklevis.dsh.android.platform.BoundedLruCache
import com.clarklevis.dsh.android.platform.GatewayDiagnosticAction
import com.clarklevis.dsh.shared.facade.SharedMobileSnapshot
import com.clarklevis.dsh.shared.facade.SharedMobileStore
import com.clarklevis.dsh.shared.facade.SharedSessionAgentPresetStore
import com.clarklevis.dsh.shared.facade.SharedSessionAgentPresetTransition
import com.clarklevis.dsh.shared.facade.SharedSlashCommandSnapshot
import com.clarklevis.dsh.shared.facade.SharedSlashCommandStore
import com.clarklevis.dsh.shared.facade.SharedSlashCommandTransition
import com.clarklevis.dsh.shared.facade.SharedWorkspaceFileStore
import com.clarklevis.dsh.shared.facade.SharedWorkspaceFileTransition
import com.clarklevis.dsh.shared.gateway.GatewayConnectionState
import com.clarklevis.dsh.shared.gateway.GatewayPairingPayloadException
import com.clarklevis.dsh.shared.gateway.GatewayRequestLanePolicy
import com.clarklevis.dsh.shared.gateway.GatewayRequests
import com.clarklevis.dsh.shared.gateway.GatewayRuntimeEvent
import com.clarklevis.dsh.shared.gateway.GatewayRuntimeState
import com.clarklevis.dsh.shared.gateway.gatewayAttachmentCacheKey
import com.clarklevis.dsh.shared.projection.TrajectoryNode
import com.clarklevis.dsh.shared.protocol.GatewayApprovalOutcome
import com.clarklevis.dsh.shared.protocol.GatewayDirectoryItem
import com.clarklevis.dsh.shared.protocol.GatewayFrame
import com.clarklevis.dsh.shared.protocol.GatewayGoalRef
import com.clarklevis.dsh.shared.protocol.GatewayQuestionAnswer
import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder
import com.clarklevis.dsh.shared.protocol.GatewayWorkspace
import com.clarklevis.dsh.shared.protocol.JsonValue
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Stable
class AndroidSharedStateHolder(
    store: SharedMobileStore = SharedMobileStore(),
    private val graph: AndroidAppGraph? = null,
    voiceStore: com.clarklevis.dsh.shared.facade.SharedVoiceInputStore = com.clarklevis.dsh.shared.facade.SharedVoiceInputStore(),
    voiceContext: android.content.Context? = graph?.application
) {
    private val teamStore = com.clarklevis.dsh.shared.facade.SharedTeamStore()
    var teamSnapshot by mutableStateOf(com.clarklevis.dsh.shared.facade.TeamSnapshot())
        private set
    private var teamLeadSessionId: String? = null
    var childSessionIds: Set<String> by mutableStateOf(emptySet())
        private set
    private var persistedTeamRouting = "[]"
        set(value) {
            field = value
            childSessionIds = kotlinx.serialization.json.Json
                .decodeFromString<List<com.clarklevis.dsh.shared.facade.ConversationTarget>>(value)
                .filter { it.parentSessionId != null }.mapTo(mutableSetOf()) { it.sessionId }
        }

    fun isSubagentSession(sessionId: String): Boolean =
        sessionId in childSessionIds || snapshot.sessions.any { it.id == sessionId && it.isSubagent }
    private var persistedTeamAvatars = "[]"
    private var switchingTeamMember = false
    private val teamDrafts = mutableMapOf<String, Pair<String, List<AndroidPreparedImage>>>()
    val teamSessionTitle get() = snapshot.sessions.firstOrNull { it.id == teamLeadSessionId }?.title ?: "Lead 会话"
    val isViewingTeamMember get() = snapshot.selectedSessionId?.let(teamStore::isChild) == true
    val selectedMemberCanSend get() = snapshot.selectedSessionId?.let(teamStore::canSend) != false
    val selectedSessionIsRunning get() = teamSnapshot.members.firstOrNull { it.id == snapshot.selectedSessionId }?.running
        ?: (snapshot.sessions.firstOrNull { it.id == snapshot.selectedSessionId }?.isRunning == true)

    fun refreshTeam() {
        requestTeam(refresh = true)
    }

    private fun requestTeam(refresh: Boolean) {
        val lead = teamLeadSessionId ?: return
        val appGraph = graph ?: return
        if (gatewayState.connection != GatewayConnectionState.CONNECTED) return
        for (kind in listOf("agent-team", "subagents")) {
            val payload = (if (refresh) teamStore.queryJson(lead, kind) else teamStore.queryIfNeededJson(lead, kind)) ?: continue
            val request = com.clarklevis.dsh.shared.gateway.GatewayRequest(kind, kind, lead,
                lanePolicy = GatewayRequestLanePolicy.COALESCE_LATEST, payload = payload)
            appGraph.gatewayScope.launch { appGraph.gatewayRuntime.sendRequest(request) }
        }
        prepareTeamMemberProfiles()
    }

    private fun prepareTeamMemberProfiles() {
        val lead = teamLeadSessionId ?: return
        val appGraph = graph ?: return
        if (gatewayState.connection != GatewayConnectionState.CONNECTED) return
        val requests = kotlinx.serialization.json.Json.decodeFromString<List<com.clarklevis.dsh.shared.facade.TeamMemberProfileRequest>>(
            teamStore.memberProfileRequestsJson(lead)
        )
        for (request in requests) {
            val target = teamStore.targetJson(request.sessionId)
            appGraph.gatewayScope.launch {
                appGraph.gatewayRuntime.registerConversationTarget(target)
                appGraph.gatewayRuntime.sendRequest(com.clarklevis.dsh.shared.gateway.GatewayRequest(
                    requestType = "history", responseKind = request.requestId, targetSessionId = request.sessionId,
                    correlationId = request.requestId, payload = request.payload, timeoutMilliseconds = 10_000
                ))
            }
        }
    }

    fun selectTeamMember(member: com.clarklevis.dsh.shared.facade.TeamMemberView) {
        if (!member.canOpen || member.id == (pendingSelectedSessionId ?: snapshot.selectedSessionId)) return
        switchingTeamMember = true
        try { selectSession(member.id) } finally { switchingTeamMember = false }
    }

    private suspend fun registerTeamConversationTarget(sessionId: String) {
        val appGraph = graph ?: return
        val target = withContext(Dispatchers.Main.immediate) { teamStore.targetJson(sessionId) }
        appGraph.gatewayRuntime.registerConversationTarget(target)
    }

    private val workspaceFileStore = SharedWorkspaceFileStore()
    private val slashCommandStore = SharedSlashCommandStore()
    val voiceInput = com.clarklevis.dsh.android.platform.AndroidVoiceInputAdapter(voiceContext, ::sendVoiceRequest, voiceStore)
    private fun sendVoiceRequest(request: com.clarklevis.dsh.shared.gateway.GatewayRequest) {
        val appGraph = graph ?: return
        appGraph.gatewayScope.launch {
            if (!appGraph.gatewayRuntime.sendRequest(request)) withContext(Dispatchers.Main.immediate) {
                voiceInput.requestFailed(request.correlationId, appText("语音请求未发送，请检查连接"))
            }
        }
    }
    private val sessionAgentPresetStore = SharedSessionAgentPresetStore()
    var sessionAgentPreset by mutableStateOf(sessionAgentPresetStore.snapshot())
        private set
    private val scope = graph?.let { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    val fileUploads = com.clarklevis.dsh.android.platform.AndroidFileUploadController(graph, scope) { platformError = it }

    fun prepareFiles(uris: List<Uri>, pickerSessionId: String?) {
        if (pickerSessionId != snapshot.selectedSessionId || isViewingTeamMember) return
        scope?.launch {
            if (snapshot.selectedSessionId == null && !prepareNewSession()) return@launch
            fileUploads.select(snapshot.selectedSessionId, !isViewingTeamMember)
            voiceInput.setVoiceMode(false)
            fileUploads.importFiles(uris)
        }
    }
    private var runtimeCollection: Job? = null
    private var catalogWriteJob: Job? = null
    private var closed = false
    private var hasOfflineCatalogState = false
    private var catalogLoadCompleted = false
    private var workspaceSelectedByUser = false
    private val gatewayFollowUps = graph?.let { appGraph ->
        AndroidGatewayFollowUpQueue(appGraph.gatewayScope, onFailure = { error ->
            scope?.launch { platformError = error.message ?: appText("后续请求提交失败") }
        })
    }
    private val projectionActor = AndroidProjectionActor(
        projection = AndroidGatewayProjection(store, onResubscribe = { sessionId ->
            graph?.let { appGraph ->
                appGraph.gatewayScope.launch {
                    if (appGraph.gatewayRuntime.state.value.connection == GatewayConnectionState.CONNECTED) {
                        registerTeamConversationTarget(sessionId)
                        appGraph.gatewayRuntime.subscribe(sessionId)
                    }
                }
            }
        }) { sessionId, beforeSequence, historyFormatVersion ->
            graph?.let { appGraph ->
                appGraph.gatewayScope.launch {
                    if (appGraph.gatewayRuntime.state.value.connection != GatewayConnectionState.CONNECTED) return@launch
                    registerTeamConversationTarget(sessionId)
                    appGraph.gatewayRuntime.requestHistory(
                        sessionId = sessionId,
                        beforeSequence = beforeSequence,
                        maxMessages = if (beforeSequence == null) HISTORY_PAGE_MESSAGE_LIMIT else OLDER_HISTORY_PAGE_MESSAGE_LIMIT,
                        maxBytes = HISTORY_PAGE_BYTE_BUDGET,
                        view = HISTORY_VIEW,
                        historyFormatVersion = historyFormatVersion
                    )
                }
            }
        },
        uiDispatcher = if (graph == null) Dispatchers.Unconfined else Dispatchers.Main.immediate,
        publish = ::publishProjectionSnapshot,
        chatCache = graph?.let { com.clarklevis.dsh.android.platform.AndroidChatHistoryCache.shared(it.application) },
        gatewayCacheId = graph?.gatewayLocalId ?: "legacy",
        backgroundDispatcher = graph?.gatewayDispatcher ?: Dispatchers.Default,
        onDiskChanged = {
            graph?.let { AndroidCacheStorage.shared(it.application).maintain() }
            Unit
        },
        onSearchNavigationResolved = { found, fragment ->
            val wasFragment = localSearchIsFragment
            localSearchIsOpening = false
            localSearchIsFragment = fragment
            if (!found) {
                localSearchScrollTarget = null
                localSearchContextSessionId = null
                localSearchContextTarget = null
                platformError = appText("本地搜索结果已失效，请重新搜索。")
            } else if (wasFragment && !fragment) {
                localSearchScrollTarget = localSearchContextTarget
            }
        }
    )
    private var prefetchUsesAssistantStream = false
    private val chatPrefetcher = graph?.let { appGraph ->
        val cache = com.clarklevis.dsh.android.platform.AndroidChatHistoryCache.shared(appGraph.application)
        AndroidChatPrefetcher(
            scope = appGraph.gatewayScope,
            plan = { excluded, selected ->
                val candidates = withContext(Dispatchers.Main.immediate) {
                    kotlinx.serialization.json.JsonArray(snapshot.sessions.filter {
                        it.isVisibleInHistory && it.id !in excluded
                    }.map { session ->
                        kotlinx.serialization.json.buildJsonObject {
                            put("id", kotlinx.serialization.json.JsonPrimitive(session.id))
                            put("lastActivityEpochSeconds", kotlinx.serialization.json.JsonPrimitive(session.lastActivityEpochSeconds))
                            put("isRunning", kotlinx.serialization.json.JsonPrimitive(session.isRunning))
                            put("hasUnread", kotlinx.serialization.json.JsonPrimitive(session.hasUnread))
                            put("hasConversation", kotlinx.serialization.json.JsonPrimitive(session.hasConversation != false))
                        }
                    }).toString()
                }
                val ranked = cache.planPrefetch(appGraph.gatewayLocalId, candidates, selected)
                (kotlinx.serialization.json.Json.parseToJsonElement(ranked) as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty()
            },
            ticket = { cache.prefetchTicket(appGraph.gatewayLocalId, it) },
            request = { id, format ->
                registerTeamConversationTarget(id)
                appGraph.gatewayRuntime.tryRequestHistoryInBackground(id, maxMessages = 80, maxBytes = 256 * 1024,
                    view = HISTORY_VIEW, historyFormatVersion = format)
            },
            accept = { id, raw, ticket -> cache.acceptPrefetchHistory(appGraph.gatewayLocalId, id, raw, ticket) },
            cancelTicket = { id, ticket -> cache.cancelPrefetch(appGraph.gatewayLocalId, id, ticket) },
            log = appGraph.diagnostics::chatPrefetch
        )
    }
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
    private var workspaceFileTemporaryLease: java.io.Closeable? = null
    private var completedWorkspaceFileLease: java.io.Closeable? = null
    private val cacheStorage = graph?.let { AndroidCacheStorage.shared(it.application) }
    private var workspaceFileCacheEpoch = 0L
    @Volatile private var workspaceFileOperationId = UUID.randomUUID()
    private var recentlyCreatedWorkspace: GatewayWorkspace? by mutableStateOf(null)
    private var inputGeneration = 0L
    private var pendingSelectedSessionId: String? = null
    private val pendingSessionCreation = AndroidPendingSessionCreation()
    private val thumbnailCache = BoundedLruCache<String, ImageBitmap>(MAXIMUM_THUMBNAIL_BYTES) {
        it.width.toLong() * it.height * 4
    }
    private val attachmentStateCache = BoundedLruCache<String, AttachmentLoadState>(MAXIMUM_STATUS_COUNT) { 1 }

    var snapshot: SharedMobileSnapshot by mutableStateOf(projectionActor.initialSnapshot)
        private set
    var wirePayload: String by mutableStateOf(DEFAULT_WIRE_PAYLOAD)
    var gatewayState: GatewayRuntimeState by mutableStateOf(GatewayRuntimeState())
        private set
    var scheduledTasks: List<MobileScheduledTask> by mutableStateOf(emptyList())
        private set
    var scheduledTasksLoading: Boolean by mutableStateOf(false)
        private set
    var scheduledTasksError: String? by mutableStateOf(null)
        private set
    var scheduledTaskPendingId: String? by mutableStateOf(null)
        private set
    var scheduledTaskCompletedRequestId: String? by mutableStateOf(null)
        private set
    var scheduledTaskMutationError: String? by mutableStateOf(null)
        private set
    var pluginCatalog: MobilePluginCatalog? by mutableStateOf(null)
        private set
    var pluginsLoading: Boolean by mutableStateOf(false)
        private set
    var pluginsError: String? by mutableStateOf(null)
        private set
    var pluginPendingName: String? by mutableStateOf(null)
        private set
    var pluginNotice: String? by mutableStateOf(null)
        private set
    private var pluginCatalogRequestId: String? = null
    private var pluginToggleRequestId: String? = null
    private var pluginPageVisited = false
    private var scheduledTaskMutationRequestId: String? = null
    private var scheduledTaskMutationKind: String? = null
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
    var conversationDirectorySessionId: String? = null
        private set
    var conversationDirectoryPath: String? by mutableStateOf(null)
        private set
    var conversationFileIsOpening: Boolean by mutableStateOf(false)
        private set
    var workspaceFilePreviewProgress: Float by mutableStateOf(0f)
        private set
    var workspaceFilePreviewRequest: AndroidWorkspaceFilePreviewRequest? by mutableStateOf(null)
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
    private var trajectoryPublishJob: Job? = null
    private var trajectoryPublishPending = false
    private var messageDraftState: String by mutableStateOf("")
    private var pendingCommandSubmission: MessageSubmission? = null
    var messageDraft: String
        get() = messageDraftState
        set(value) {
            if (messageDraftState != value) {
                messageDraftState = value
                voiceInput.draftChanged(value)
                inputGeneration += 1
                applySlashCommandTransition(
                    slashCommandStore.updateInput(
                        sessionId = pendingSelectedSessionId ?: snapshot.selectedSessionId,
                        text = value,
                        isConnected = gatewayState.connection == GatewayConnectionState.CONNECTED,
                        isSupported = "commands" in gatewayState.capabilities && !isViewingTeamMember,
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
    private val queueStore = com.clarklevis.dsh.shared.facade.SharedQueueStore()
    var queueState by mutableStateOf(queueStore.snapshot())
        private set
    val selectedQueueItems get() = queueState.queues[snapshot.selectedSessionId].orEmpty()

    fun updateQueuedMessage(itemId: String, action: String) {
        val appGraph = graph ?: return
        val sessionId = snapshot.selectedSessionId ?: return
        if ("queue-control" !in gatewayState.capabilities || gatewayState.connection != GatewayConnectionState.CONNECTED) return
        val request = queueStore.beginAction(sessionId, itemId, action) ?: return
        queueState = queueStore.snapshot()
        appGraph.gatewayScope.launch {
            val sent = appGraph.gatewayRuntime.sendRequest(request)
            if (!sent) withContext(Dispatchers.Main.immediate) {
                queueState = queueStore.failPending(appText("队列操作未发送，请重试"))
                platformError = queueState.lastError
            }
        }
    }

    private fun acceptQueueFrame(json: String) {
        queueState = queueStore.acceptFrame(json)
        queueState.lastError?.let { platformError = it }
        restoreQueuedDraft()
    }

    private fun restoreQueuedDraft() {
        val sessionId = snapshot.selectedSessionId ?: return
        val text = queueStore.takeDraft(sessionId) ?: return
        clearActiveSlashCommand()
        messageDraft = if (messageDraft.isEmpty()) text else messageDraft + "\n\n" + text
        queueDraftRestoreCount += 1
    }

    private var pendingMessageSubmission: MessageSubmission? by mutableStateOf(null)
    var queueDraftRestoreCount by mutableLongStateOf(0L)
        private set

    var cancellingSessionIds: Set<String> by mutableStateOf(emptySet())
        private set

    private val remoteSearch = AndroidRemoteSearch()
    private var remoteSearchTimeout: Job? = null
    var remoteSearchResultQuery: String? by mutableStateOf(null)
        private set

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
                    if (!snapshot.selectedHistoryIsLoadingOlder && frame.kind in setOf("history", "error", "session-stream-reset")) {
                        frame.sessionId?.let { historyPagingSessionIds = historyPagingSessionIds - it }
                    }
                    handleWorkspaceFrame(frame)
                }
                .onFailure { error -> platformError = GatewayWireDecoder.failureSummary(payload, error) }
            return
        }
        appGraph.gatewayScope.launch {
            runCatching { GatewayWireDecoder.decode(payload) }
                .onSuccess { frame ->
                    projectionActor.acceptFrame(payload, frame, frame.sessionId) {
                        pruneAttachmentStateForSession()
                        handleWorkspaceFrame(frame)
                        if (frame.hasOfflineCatalogMutation()) {
                            if (snapshot.sessions.isNotEmpty() || snapshot.workspaces.isNotEmpty() ||
                                frame.kind in setOf("session-archives", "session-archived")
                            ) hasOfflineCatalogState = true
                            scheduleOfflineCatalogSave()
                        }
                    }
                }
                .onFailure { error ->
                    withContext(Dispatchers.Main.immediate) {
                        platformError = GatewayWireDecoder.failureSummary(payload, error)
                    }
                }
        }
    }

    var localSearchScrollTarget: AndroidLocalChatMatch? by mutableStateOf(null)
        private set
    private var pendingSearchTarget: AndroidLocalChatMatch? = null
    private var localSearchContextTarget: AndroidLocalChatMatch? = null
    var localSearchIsOpening by mutableStateOf(false)
        private set
    var localSearchIsFragment by mutableStateOf(false)
        private set
    var localSearchContextSessionId: String? by mutableStateOf(null)
        private set
    var cacheViewGeneration by mutableLongStateOf(0L)
        private set

    fun returnToLatestCached() {
        val appGraph = graph ?: return
        val id = snapshot.selectedSessionId ?: return
        appGraph.gatewayScope.launch {
            val hadNormal = projectionActor.returnToLatestCached(id)
            withContext(Dispatchers.Main.immediate) {
                localSearchContextSessionId = null
                localSearchContextTarget = null
                localSearchScrollTarget = null
                localSearchIsOpening = false
                localSearchIsFragment = false
                cacheViewGeneration += 1
            }
            if (!hadNormal && gatewayState.connection == GatewayConnectionState.CONNECTED) projectionActor.loadHistory(id, older = false)
        }
    }

    fun prepareLocalChatMatch(match: AndroidLocalChatMatch) {
        pendingSearchTarget = match
    }
    fun consumeLocalSearchScrollTarget() {
        localSearchScrollTarget = null
    }

    fun selectSession(sessionId: String) {
        fileUploads.select(sessionId, !isViewingTeamMember && !switchingTeamMember)
        if (switchingTeamMember || isViewingTeamMember) {
            (pendingSelectedSessionId ?: snapshot.selectedSessionId)?.let { teamDrafts[it] = messageDraft to preparedImages }
            val saved = teamDrafts[sessionId]
            messageDraft = saved?.first.orEmpty()
            preparedImages = saved?.second.orEmpty()
        }
        if (!switchingTeamMember) {
            teamLeadSessionId = sessionId
            teamSnapshot = teamStore.snapshot(sessionId)
            persistTeamAvatarsIfChanged()
            requestTeam(refresh = false)
        }
        val targetJson = teamStore.targetJson(sessionId)
        teamStore.sessionsRequested()
        chatPrefetcher?.foregroundChanged(sessionId, true, !prefetchUsesAssistantStream)
        graph?.let { appGraph ->
            appGraph.gatewayScope.launch(Dispatchers.IO) {
                runCatching {
                    com.clarklevis.dsh.android.platform.AndroidChatHistoryCache.shared(appGraph.application)
                        .recordSessionOpen(appGraph.gatewayLocalId, sessionId)
                }
            }
        }
        val searchTarget = pendingSearchTarget?.takeIf { it.sessionId == sessionId }
        val preserveSubscription = searchTarget != null && sessionId == snapshot.selectedSessionId
        pendingSearchTarget = null
        localSearchScrollTarget = searchTarget
        localSearchContextSessionId = searchTarget?.sessionId
        localSearchContextTarget = searchTarget
        localSearchIsOpening = searchTarget != null
        localSearchIsFragment = false
        if (sessionId != snapshot.selectedSessionId) {
            cancelWorkspaceFileDownload()
            releaseCompletedWorkspaceFile()
        }
        graph?.diagnostics?.intent(GatewayDiagnosticAction.SELECT_SESSION, hasSession = true)
        applySessionAgentPresetTransition(sessionAgentPresetStore.leave())
        pendingSelectedSessionId = sessionId
        inputGeneration += 1
        applySlashCommandTransition(slashCommandStore.reset(sessionId))
        val afterPublish = {
            if (pendingSelectedSessionId == sessionId) pendingSelectedSessionId = null
            scheduleOfflineCatalogSave()
            visibleAttachmentKeys = emptySet()
            pruneAttachmentStateForSession()
        }
        val appGraph = graph
        if (appGraph == null) {
            projectionActor.selectSessionImmediate(sessionId, afterPublish)
        } else {
            appGraph.gatewayScope.launch {
                appGraph.gatewayRuntime.registerConversationTarget(targetJson)
                projectionActor.selectSession(
                    sessionId,
                    offline = gatewayState.connection != GatewayConnectionState.CONNECTED,
                    targetSequence = searchTarget?.seq,
                    afterPublish = afterPublish
                )
                if (trajectoryIsActive) publishTrajectory()
                if (!preserveSubscription && gatewayState.connection == GatewayConnectionState.CONNECTED) {
                    appGraph.gatewayRuntime.subscribe(sessionId)
                    appGraph.gatewayRuntime.requestSessions()
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

    fun refreshScheduledTasks() {
        val appGraph = graph
        if (appGraph == null || gatewayState.connection != GatewayConnectionState.CONNECTED) {
            scheduledTasksLoading = false
            scheduledTasksError = appText("连接网关后可查看定时任务")
            return
        }
        scheduledTasksLoading = true
        scheduledTasksError = null
        appGraph.gatewayScope.launch {
            val sent = appGraph.gatewayRuntime.sendRequest(GatewayRequests.scheduleCatalog())
            if (!sent) withContext(Dispatchers.Main.immediate) {
                scheduledTasksLoading = false
                scheduledTasksError = appText("定时任务请求未发送，请重试")
            }
        }
    }

    fun refreshPlugins() {
        pluginPageVisited = true
        val appGraph = graph
        if (appGraph == null || gatewayState.connection != GatewayConnectionState.CONNECTED) {
            pluginsLoading = false
            pluginsError = appText("连接网关后可查看插件")
            return
        }
        val requestId = UUID.randomUUID().toString()
        pluginCatalogRequestId = requestId
        pluginsLoading = true
        pluginsError = null
        appGraph.gatewayScope.launch {
            val sent = appGraph.gatewayRuntime.sendRequest(GatewayRequests.pluginCatalog(requestId))
            if (!sent) withContext(Dispatchers.Main.immediate) {
                if (pluginCatalogRequestId == requestId) {
                    pluginsLoading = false
                    pluginsError = appText("插件目录请求未发送，请重试")
                }
            }
        }
        scope?.launch {
            delay(12000)
            if (pluginCatalogRequestId == requestId && pluginsLoading) {
                pluginsLoading = false
                pluginsError = appText("插件目录请求超时，请重试")
            }
        }
    }

    fun setPluginEnabled(name: String, enabled: Boolean, entryId: String? = null) {
        val appGraph = graph ?: return
        if (gatewayState.connection != GatewayConnectionState.CONNECTED ||
            pluginCatalog?.managementAvailable != true || pluginToggleRequestId != null) return
        val requestId = UUID.randomUUID().toString()
        pluginToggleRequestId = requestId
        pluginPendingName = name
        pluginNotice = null
        appGraph.gatewayScope.launch {
            val request = if (entryId == null) {
                GatewayRequests.pluginBundleSetEnabled(requestId, name, enabled)
            } else GatewayRequests.pluginEntrySetEnabled(requestId, entryId, enabled)
            val sent = appGraph.gatewayRuntime.sendRequest(request)
            if (!sent) withContext(Dispatchers.Main.immediate) {
                if (pluginToggleRequestId == requestId) {
                    pluginToggleRequestId = null
                    pluginPendingName = null
                    pluginNotice = appText("操作未发送，请重试")
                }
            }
        }
        scope?.launch {
            delay(15000)
            if (pluginToggleRequestId == requestId) {
                pluginToggleRequestId = null
                pluginPendingName = null
                pluginNotice = appText("操作结果待确认，请刷新插件目录")
                refreshPlugins()
            }
        }
    }

    fun clearPluginNotice() { pluginNotice = null }

    private fun acceptPluginFrame(frame: GatewayFrame) {
        when {
            frame.kind == "plugin-catalog" && frame.requestId == pluginCatalogRequestId -> {
                pluginCatalog = MobilePluginCatalog(
                    managementAvailable = frame.managementAvailable == true,
                    settingsWritable = frame.settingsWritable == true,
                    bundles = frame.bundles.orEmpty().map(::MobilePluginBundle),
                    plugins = frame.plugins.orEmpty().map(::MobilePluginEntry),
                    officialItems = frame.officialItems.orEmpty().map(::MobileOfficialPlugin)
                )
                pluginsLoading = false
                pluginsError = if (frame.managementAvailable == true) null else appText("当前 Host 不支持插件管理")
            }
            // The matching mutation response refreshes the catalog once.
            frame.kind == "plugins-changed" && pluginPageVisited && pluginToggleRequestId == null -> refreshPlugins()
            frame.kind in setOf("plugin-bundle-set-enabled", "plugin-entry-set-enabled") &&
                frame.requestId == pluginToggleRequestId -> {
                val application = frame.result?.get("application")?.stringValue
                pluginNotice = when (application) {
                    "applied" -> null
                    "restart-required" -> appText("设置已保存，请重启 DSH Web 后刷新目录")
                    "overridden" -> appText("插件状态被其他配置覆盖，请检查 Host 配置")
                    else -> frame.result?.get("error")?.stringValue ?: appText("插件操作未生效")
                }
                pluginToggleRequestId = null
                pluginPendingName = null
                refreshPlugins()
            }
            frame.kind == "error" && frame.requestType == "plugin-catalog" &&
                frame.requestId == pluginCatalogRequestId -> {
                pluginsLoading = false
                pluginsError = frame.message ?: appText("插件目录加载失败")
            }
            frame.kind == "error" && frame.requestType in setOf(
                "plugin-bundle-set-enabled", "plugin-entry-set-enabled"
            ) && frame.requestId == pluginToggleRequestId -> {
                pluginToggleRequestId = null
                pluginPendingName = null
                pluginNotice = frame.message ?: appText("插件操作失败")
                refreshPlugins()
            }
        }
    }

    fun updateScheduledTask(
        task: MobileScheduledTask,
        title: String,
        prompt: String,
        change: JsonValue?
    ): String? {
        val requestId = beginScheduledTaskMutation(task, "schedule-update") ?: return null
        val appGraph = graph ?: return null
        appGraph.gatewayScope.launch {
            val sent = appGraph.gatewayRuntime.sendRequest(GatewayRequests.scheduleUpdate(
                task.sessionId, task.id, task.raw,
                title = title.trim(), prompt = prompt.trim(), change = change, requestId = requestId
            ))
            if (!sent) withContext(Dispatchers.Main.immediate) {
                if (scheduledTaskMutationRequestId == requestId) finishScheduledTaskMutation(appText("操作未发送，请重试"))
            }
        }
        return requestId
    }

    fun deleteScheduledTask(task: MobileScheduledTask): String? {
        val requestId = beginScheduledTaskMutation(task, "schedule-delete") ?: return null
        val appGraph = graph ?: return null
        appGraph.gatewayScope.launch {
            val sent = appGraph.gatewayRuntime.sendRequest(GatewayRequests.scheduleDelete(
                task.sessionId, task.id, requestId
            ))
            if (!sent) withContext(Dispatchers.Main.immediate) {
                if (scheduledTaskMutationRequestId == requestId) finishScheduledTaskMutation(appText("操作未发送，请重试"))
            }
        }
        return requestId
    }

    private fun beginScheduledTaskMutation(task: MobileScheduledTask, kind: String): String? {
        if (gatewayState.connection != GatewayConnectionState.CONNECTED) {
            scheduledTaskMutationError = appText("连接网关后才能修改定时任务")
            return null
        }
        if (scheduledTaskPendingId != null) return null
        if (scheduledTasks.firstOrNull { it.id == task.id && it.sessionId == task.sessionId }?.raw != task.raw) {
            scheduledTaskMutationError = appText("任务已变化，列表已刷新，请重新操作")
            refreshScheduledTasks()
            return null
        }
        val requestId = UUID.randomUUID().toString()
        scheduledTaskMutationRequestId = requestId
        scheduledTaskMutationKind = kind
        scheduledTaskPendingId = task.id
        scheduledTaskMutationError = null
        return requestId
    }

    private fun finishScheduledTaskMutation(error: String?) {
        if (error == null) scheduledTaskCompletedRequestId = scheduledTaskMutationRequestId
        scheduledTaskMutationError = error
        scheduledTaskPendingId = null
        scheduledTaskMutationRequestId = null
        scheduledTaskMutationKind = null
        refreshScheduledTasks()
    }

    private fun scheduledTaskFailureMessage(code: String?, message: String?): String = when (code) {
        "schedule_conflict" -> appText("任务已被其他设备修改，列表已刷新，请重新打开编辑")
        "schedule_ended" -> appText("任务已结束，无法再编辑")
        "schedule_not_found" -> appText("任务已不存在，列表已刷新")
        else -> message ?: appText("操作失败，请稍后重试")
    }

    private var preparingNewSession = false

    suspend fun prepareNewSession(): Boolean {
        teamLeadSessionId = null
        teamSnapshot = com.clarklevis.dsh.shared.facade.TeamSnapshot()
        if (preparingNewSession) return false
        val appGraph = graph ?: return false
        if (gatewayState.connection != GatewayConnectionState.CONNECTED) {
            platformError = appText("请先连接 DeepSeek Harness")
            return false
        }
        if ("session-create" !in gatewayState.capabilities) {
            platformError = appText("请更新并重启 Mobile Gateway，以支持发送消息前配置新会话")
            return false
        }
        preparingNewSession = true
        try {
            val requestId = UUID.randomUUID().toString()
            val workspaceId = activeWorkspace?.workspaceId
            // 先登记再发送，快速返回的本地网关也不会丢失创建结果。
            val response = pendingSessionCreation.begin(requestId)
            val event = try {
                if (!appGraph.gatewayRuntime.sendRequest(
                        GatewayRequests.createSession(requestId, workspaceId)
                    )
                ) {
                    null
                } else {
                    withTimeoutOrNull(15_000) { response.await() }
                }
            } finally {
                pendingSessionCreation.clear(requestId)
            }
            val frame = (event as? GatewayRuntimeEvent.Frame)?.frame
            val sessionId = frame?.sessionId?.takeIf(String::isNotBlank)
            if (frame?.kind != "session-created" || sessionId == null) {
                platformError = frame?.message ?: appText("创建会话失败，请检查连接后重试")
                return false
            }
            pendingSelectedSessionId = sessionId
            inputGeneration += 1
            applySlashCommandTransition(slashCommandStore.reset(sessionId))
            projectionActor.selectSession(sessionId) {
                pendingSelectedSessionId = null
                visibleAttachmentKeys = emptySet()
                pruneAttachmentStateForSession()
            }
            if (snapshot.selectedSessionId == sessionId && snapshot.selectedHistoryLoadedEventCount == 0) {
                appGraph.gatewayRuntime.subscribe(sessionId)
            }
            appGraph.gatewayRuntime.requestSessions()
            requestSessionControls(appGraph, sessionId)
            return true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            platformError = error.message ?: appText("创建会话失败")
            return false
        } finally {
            preparingNewSession = false
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
            if (selectedWorkspaceId == UNGROUPED_WORKSPACE_ID ||
                selectedWorkspaceId == ALL_WORKSPACES_ID) return null
            return availableWorkspaces.firstOrNull { it.workspaceId == selectedWorkspaceId }
                ?: availableWorkspaces.firstOrNull()
        }

    val isUngroupedWorkspaceSelected: Boolean
        get() = selectedWorkspaceId == UNGROUPED_WORKSPACE_ID || availableWorkspaces.isEmpty()

    fun selectWorkspace(workspaceId: String?) {
        workspaceSelectedByUser = true
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
            platformError = appText("请先连接 DeepSeek Harness")
            return
        }
        directoryIsLoading = true
        appGraph.gatewayScope.launch {
            val accepted = appGraph.gatewayRuntime.sendRequest(GatewayRequests.directories(path))
            if (!accepted) withContext(Dispatchers.Main.immediate) {
                directoryIsLoading = false
                platformError = appText("目录请求正在处理中，请稍后重试")
            }
        }
    }

    fun createDirectory(parentPath: String, name: String) {
        val appGraph = graph ?: return
        if (gatewayState.connection != GatewayConnectionState.CONNECTED) {
            platformError = appText("请先连接 DeepSeek Harness")
            return
        }
        val normalizedName = name.trim()
        if (normalizedName.isEmpty()) {
            platformError = appText("文件夹名称不能为空")
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
                platformError = appText("文件夹创建请求正在处理中，请稍后重试")
            }
        }
    }

    fun acknowledgeCreatedDirectoryReveal(path: String) {
        if (createdDirectoryPathToReveal == path) createdDirectoryPathToReveal = null
    }

    fun createWorkspace(path: String) {
        val appGraph = graph ?: return
        if (gatewayState.connection != GatewayConnectionState.CONNECTED) {
            platformError = appText("请先连接 DeepSeek Harness")
            return
        }
        workspaceCreationCompletedPath = null
        workspaceCreationIsLoading = true
        appGraph.gatewayScope.launch {
            val accepted = appGraph.gatewayRuntime.sendRequest(GatewayRequests.createWorkspace(path))
            if (!accepted) withContext(Dispatchers.Main.immediate) {
                workspaceCreationIsLoading = false
                platformError = appText("工作区创建请求正在处理中，请稍后重试")
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
            platformError = appText("请先打开一个已有会话")
            return
        }
        if (gatewayState.connection != GatewayConnectionState.CONNECTED) {
            platformError = appText("请先连接 DeepSeek Harness")
            return
        }
        if ("file-downloads" !in gatewayState.capabilities) {
            platformError = appText("当前 Mobile Gateway 不支持文件下载，请升级并重启网关。")
            return
        }
        val workspaceSessionId = teamStore.workspaceSessionId(sessionId)
        appGraph.gatewayScope.launch {
            workspaceFileStore.setWorkspaceSession(sessionId, workspaceSessionId)
            applyWorkspaceFileTransition(
                appGraph,
                workspaceFileStore.load(sessionId, path, UUID.randomUUID().toString())
            )
        }
    }

    fun openWorkspaceFile(entry: GatewayDirectoryItem, purpose: String) {
        val appGraph = graph ?: return
        val sessionId = snapshot.selectedSessionId ?: return
        if (entry.kind != "file" || workspaceFilePreviewRequest != null || conversationFileIsOpening) return
        releaseCompletedWorkspaceFile()
        val previewsFile = purpose == "preview" && !entry.name.endsWith(".apk", ignoreCase = true)
        if (previewsFile) {
            platformError = null
            conversationFileIsOpening = true
            workspaceFilePreviewProgress = 0f
            workspaceFilePreviewRequest = AndroidWorkspaceFilePreviewRequest(sessionId, entry.path, entry.name)
        }
        val operationId = UUID.randomUUID().also {
            workspaceFileOperationId = it
            workspaceFileCacheEpoch = cacheStorage?.categoryEpoch(AndroidCacheDiskManager.PREVIEWS) ?: 0
        }
        val workspaceSessionId = teamStore.workspaceSessionId(sessionId)
        appGraph.gatewayScope.launch {
            if (operationId != workspaceFileOperationId || sessionId != snapshot.selectedSessionId) return@launch
            workspaceFileStore.setWorkspaceSession(sessionId, workspaceSessionId)
            if (purpose == "preview" && !entry.name.endsWith(".apk", ignoreCase = true)) {
                applyWorkspaceFileTransition(appGraph, workspaceFileStore.resolve(
                    sessionId, entry.path, UUID.randomUUID().toString(),
                    "file-references-v1" in gatewayState.capabilities, useCache = true
                ))
                return@launch
            }
            applyWorkspaceFileTransition(
                appGraph,
                workspaceFileStore.download(
                    sessionId,
                    entry.path,
                    UUID.randomUUID().toString(),
                    if (purpose == "preview" && entry.name.endsWith(".apk", ignoreCase = true)) "install" else purpose
                )
            )
        }
    }

    fun openConversationFile(gatewayId: String, sessionId: String, path: String) {
        val appGraph = graph ?: return
        if (gatewayId != gatewayLocalId || sessionId != snapshot.selectedSessionId) return
        // 后台准备预览时忽略重复点击，避免弹出非必要的 download-busy 提示。
        if (workspaceFilePreviewRequest != null || conversationFileIsOpening) return
        if (gatewayState.connection != GatewayConnectionState.CONNECTED) {
            platformError = appText("请先连接 DeepSeek Harness")
            return
        }
        if ("file-downloads" !in gatewayState.capabilities) {
            platformError = appText("当前网关不支持文件下载，请升级网关。")
            return
        }
        releaseCompletedWorkspaceFile()
        conversationFileIsOpening = true
        platformError = null
        workspaceFilePreviewProgress = 0f
        workspaceFilePreviewRequest = AndroidWorkspaceFilePreviewRequest(sessionId, path, path.substringAfterLast('/'))
        val operationId = UUID.randomUUID().also {
            workspaceFileOperationId = it
            workspaceFileCacheEpoch = cacheStorage?.categoryEpoch(AndroidCacheDiskManager.PREVIEWS) ?: 0
        }
        val workspaceSessionId = teamStore.workspaceSessionId(sessionId)
        appGraph.gatewayScope.launch {
            if (operationId != workspaceFileOperationId || sessionId != snapshot.selectedSessionId) return@launch
            workspaceFileStore.setWorkspaceSession(sessionId, workspaceSessionId)
            applyWorkspaceFileTransition(appGraph, workspaceFileStore.resolveReference(
                sessionId, path, UUID.randomUUID().toString(),
                "file-references-v1" in gatewayState.capabilities,
                snapshot.workspaces.firstOrNull { workspaceSessionId in it.sessionIds }?.path
            ))
        }
    }

    fun consumeConversationDirectoryPath() {
        conversationDirectorySessionId = null
        conversationDirectoryPath = null
    }

    fun cancelWorkspaceFileDownload() {
        workspaceFileOperationId = UUID.randomUUID()
        conversationFileIsOpening = false
        workspaceFilePreviewRequest = null
        workspaceFilePreviewProgress = 0f
        if (completedWorkspaceFile == null || completedWorkspaceFile?.purpose == "preview") releaseCompletedWorkspaceFile()
        val appGraph = graph ?: return
        appGraph.gatewayScope.launch {
            applyWorkspaceFileTransition(appGraph, workspaceFileStore.cancel())
            closeWorkspaceTemporaryFile(remove = true)
        }
    }

    fun consumeCompletedWorkspaceFile() {
        releaseCompletedWorkspaceFile()
    }

    private fun releaseCompletedWorkspaceFile() {
        val file = completedWorkspaceFile?.file
        completedWorkspaceFile = null
        completedWorkspaceFileLease?.close()
        completedWorkspaceFileLease = null
        if (file != null && file.parentFile?.parentFile?.name == "workspace-files") {
            cacheStorage?.deleteTemporary(file)
        }
    }

    /** 导出读操作持有独立租约，关闭预览不会删掉尚在复制的临时文件。 */
    fun leaseWorkspaceFile(file: File): java.io.Closeable? = cacheStorage?.lease(file)

    fun releaseWorkspaceTemporaryFile(file: File) {
        cacheStorage?.deleteTemporary(file)
    }

    fun retainExternalWorkspaceFile(file: File) {
        cacheStorage?.retainExternal(file)
    }

    fun refreshProductState() {
        refreshTeam()
        teamStore.sessionsRequested()
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

    fun archiveSession(sessionId: String) {
        val appGraph = graph ?: return
        if (gatewayState.connection != GatewayConnectionState.CONNECTED) return
        appGraph.gatewayScope.launch {
            appGraph.gatewayRuntime.sendRequest(GatewayRequests.archiveSession(sessionId))
        }
    }

    fun renameSession(sessionId: String, title: String) {
        val appGraph = graph ?: return
        if (gatewayState.connection != GatewayConnectionState.CONNECTED || title.isBlank()) return
        appGraph.gatewayScope.launch {
            appGraph.gatewayRuntime.sendRequest(GatewayRequests.renameSession(sessionId, title))
        }
    }

    var localChatMatches: List<AndroidLocalChatMatch> by mutableStateOf(emptyList())
        private set
    var localSearchHasMore by mutableStateOf(false)
        private set
    private var localSearchJob: Job? = null
    private var localSearchQuery = ""

    fun search(query: String) {
        val normalized = query.trim()
        localSearchQuery = normalized
        remoteSearchResultQuery = null
        val remoteQuery = if (gatewayState.connection == GatewayConnectionState.CONNECTED) {
            remoteSearch.request(normalized)
        } else {
            remoteSearch.disconnected()
            null
        }
        localSearchJob?.cancel()
        localChatMatches = emptyList()
        localSearchHasMore = false
        if (normalized.isEmpty()) return
        val appGraph = graph ?: return
        localSearchJob = scope?.launch {
            val cache = com.clarklevis.dsh.android.platform.AndroidChatHistoryCache.shared(appGraph.application)
            val matches = try {
                withContext(Dispatchers.IO) {
                    val payload = cache.search(gatewayLocalId, normalized, null, 50)
                    AndroidLocalChatMatches.decode(payload)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                AndroidLocalChatMatches()
            }
            if (localSearchQuery == normalized) {
                localChatMatches = matches.results
                localSearchHasMore = matches.hasMore
            }
        }
        if (gatewayState.connection == GatewayConnectionState.CONNECTED && remoteQuery != null) sendRemoteSearch(remoteQuery)
    }

    private fun sendRemoteSearch(query: String) {
        val appGraph = graph ?: return
        appGraph.gatewayScope.launch { appGraph.gatewayRuntime.sendRequest(GatewayRequests.search(query)) }
        remoteSearchTimeout?.cancel()
        remoteSearchTimeout = scope?.launch {
            delay(15_000)
            remoteSearch.timeout(query)
        }
    }

    var storageUsage: AndroidStorageUsage? by mutableStateOf(null)
        private set
    var cacheStorageNotice: String? by mutableStateOf(null)
        private set
    var cacheStorageError: String? by mutableStateOf(null)
        private set
    var cacheMaintenanceRunning by mutableStateOf(false)
        private set

    fun refreshCacheStorage(clear: Boolean = false, category: CacheStorageCategory? = null) {
        val appGraph = graph ?: return
        if (cacheMaintenanceRunning) return
        cacheMaintenanceRunning = true
        cacheStorageNotice = null
        cacheStorageError = null
        val previousBytes = storageUsage?.totalBytes
        val clearChats = clear || category == CacheStorageCategory.CHATS
        if (clearChats) chatPrefetcher?.stop("clear")
        scope?.launch {
            try {
                val stats = withContext(Dispatchers.IO) {
                    if (clearChats) projectionActor.clearDiskCache()
                    val disk = requireNotNull(cacheStorage)
                    when {
                        clear -> disk.maintain(clear = true)
                        category == CacheStorageCategory.IMAGES -> disk.clearCategory(AndroidCacheDiskManager.IMAGES)
                        category == CacheStorageCategory.PREVIEWS -> disk.clearCategory(AndroidCacheDiskManager.PREVIEWS)
                        category == CacheStorageCategory.TEMPORARY -> disk.clearCategory(AndroidCacheDiskManager.TEMPORARY)
                        category == CacheStorageCategory.UPDATES -> disk.clearCategory(AndroidCacheDiskManager.UPDATES)
                        else -> disk.statistics()
                    }
                }
                storageUsage = AndroidStorageUsage(
                    stats.chatBytes, stats.imageBytes, stats.previewBytes, stats.temporaryBytes,
                    stats.updateBytes, stats.protectedBytes, stats.freeBytes
                )
                if (clear || category != null) {
                    val reclaimed = ((previousBytes ?: stats.totalBytes) - stats.totalBytes).coerceAtLeast(0)
                    cacheStorageNotice = if (reclaimed > 0) {
                        com.clarklevis.dsh.android.localization.appText(
                            "已释放 %1\$s", android.text.format.Formatter.formatFileSize(appGraph.application, reclaimed)
                        )
                    } else {
                        com.clarklevis.dsh.android.localization.appText("没有更多可回收内容，正在使用的文件会保留。")
                    }
                }
                if (clearChats) {
                    scheduleOfflineCatalogSave()
                    localChatMatches = emptyList()
                }
                if (clear || category == CacheStorageCategory.IMAGES) {
                    thumbnailCache.retainKeys(emptySet())
                    attachmentStateCache.retainKeys(emptySet())
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                cacheStorageError = com.clarklevis.dsh.android.localization.appText("存储空间读取或清理失败，请稍后重试。")
            } finally {
                cacheMaintenanceRunning = false
            }
        }
    }

    fun loadOlderHistory(): Boolean {
        val sessionId = snapshot.selectedSessionId ?: return false
        snapshot.selectedHistoryEarliestSequence ?: return false
        if (
            !snapshot.selectedHistoryHasMore ||
            snapshot.selectedHistoryIsLoading ||
            sessionId in historyPagingSessionIds
        ) return false
        historyPagingSessionIds = historyPagingSessionIds + sessionId
        val appGraph = graph
        if (appGraph == null) {
            projectionActor.loadHistoryImmediate(sessionId, older = true)
            return true
        }
        android.util.Log.d("HistoryPresentation", "stage=request-start rows=${snapshot.conversation.size}")
        appGraph.gatewayScope.launch {
            try {
                projectionActor.loadHistory(sessionId, older = true, offline = offlineBrowsing) {
                    if (!snapshot.selectedHistoryIsLoadingOlder) historyPagingSessionIds = historyPagingSessionIds - sessionId
                }
            } catch (cancelled: CancellationException) {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main.immediate) {
                    historyPagingSessionIds = historyPagingSessionIds - sessionId
                }
                throw cancelled
            } catch (error: Exception) {
                projectionActor.historyCancelled(sessionId)
                withContext(Dispatchers.Main.immediate) {
                    historyPagingSessionIds = historyPagingSessionIds - sessionId
                    platformError = appText("历史记录加载失败")
                }
                android.util.Log.w("HistoryPresentation", "stage=request-failed type=${error.javaClass.simpleName}")
            }
        }
        return true
    }

    fun setTrajectoryActive(active: Boolean) {
        trajectoryIsActive = active
        if (!active) {
            trajectoryPublishJob?.cancel()
            trajectoryPublishJob = null
            trajectoryPublishPending = false
        }
        if (active) graph?.gatewayScope?.launch { publishTrajectory() }
    }

    private suspend fun requestTrajectoryPublish(streaming: Boolean) {
        withContext(Dispatchers.Main.immediate) {
            if (streaming) {
                trajectoryPublishPending = true
                scheduleTrajectoryPublish()
            } else {
                trajectoryPublishJob?.cancel()
                trajectoryPublishJob = null
                trajectoryPublishPending = false
            }
        }
        if (!streaming) publishTrajectory()
    }

    private fun scheduleTrajectoryPublish() {
        if (!trajectoryIsActive || trajectoryPublishJob?.isActive == true) {
            return
        }
        trajectoryPublishPending = false
        trajectoryPublishJob = graph?.gatewayScope?.launch {
            delay(TRAJECTORY_STREAM_INTERVAL_MILLISECONDS)
            publishTrajectory()
            withContext(Dispatchers.Main.immediate) {
                trajectoryPublishJob = null
                if (trajectoryPublishPending) scheduleTrajectoryPublish()
            }
        }
    }

    private suspend fun publishTrajectory() {
        val sessionId = snapshot.selectedSessionId
        val nodes = projectionActor.trajectory(sessionId)
        withContext(Dispatchers.Main.immediate) {
            if (trajectoryIsActive && snapshot.selectedSessionId == sessionId) trajectoryNodes = nodes
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

    fun refreshSessionAgentPreset() {
        if (isViewingTeamMember) { leaveSessionAgentPreset(); return }
        applySessionAgentPresetTransition(sessionAgentPresetStore.open(
            snapshot.selectedSessionId,
            "session-agent-preset" in gatewayState.capabilities,
            gatewayState.connection == GatewayConnectionState.CONNECTED
        ))
    }

    fun leaveSessionAgentPreset() {
        applySessionAgentPresetTransition(sessionAgentPresetStore.leave())
    }

    fun selectSessionAgentPreset(presetId: String) {
        applySessionAgentPresetTransition(sessionAgentPresetStore.select(presetId))
    }

    private fun applySessionAgentPresetTransition(transition: SharedSessionAgentPresetTransition) {
        sessionAgentPreset = transition.snapshot
        transition.error?.let { platformError = it }
        if (transition.refreshCommands) transition.snapshot.sessionId?.let { sessionId ->
            applySlashCommandTransition(slashCommandStore.invalidateCatalog(
                sessionId, "commands" in gatewayState.capabilities, Locale.getDefault().toLanguageTag()
            ))
        }
        if (transition.invalidSession) {
            refreshSessions()
            graph?.gatewayScope?.launch { projectionActor.selectSession(null) }
        }
        val appGraph = graph ?: return
        transition.requests.forEach { request ->
            gatewayFollowUps?.submit {
                if (!appGraph.gatewayRuntime.sendRequest(request)) {
                    withContext(Dispatchers.Main.immediate) {
                        applySessionAgentPresetTransition(sessionAgentPresetStore.requestFailed(
                            request.requestType, request.targetSessionId, request.correlationId, appText("请求发送失败，请重试")
                        ))
                    }
                }
            }
        }
    }

    fun refreshContextUsage() {
        val sessionId = snapshot.selectedSessionId ?: return
        val appGraph = graph ?: return
        if (gatewayState.connection != GatewayConnectionState.CONNECTED) return
        appGraph.gatewayScope.launch {
            appGraph.gatewayRuntime.sendRequest(GatewayRequests.sessionControl("context-usage", sessionId))
        }
    }

    private suspend fun handleSentSession(appGraph: AndroidAppGraph, sessionId: String) {
        gatewayFollowUps?.submit {
            val isCurrent = withContext(Dispatchers.Main.immediate) {
                (pendingSelectedSessionId ?: snapshot.selectedSessionId) == sessionId
            }
            if (isCurrent) appGraph.gatewayRuntime.subscribe(sessionId)
            appGraph.gatewayRuntime.requestSessions()
            requestSessionControls(appGraph, sessionId)
        }
    }

    private suspend fun requestSessionControls(appGraph: AndroidAppGraph, sessionId: String) {
        withContext(Dispatchers.Main.immediate) {
            if (snapshot.selectedSessionId == sessionId) refreshSessionAgentPreset()
        }
        if (withContext(Dispatchers.Main.immediate) { teamStore.isChild(sessionId) }) {
            if ("tasks" in gatewayState.capabilities) appGraph.gatewayRuntime.sendRequest(GatewayRequests.tasks(sessionId))
            if ("goals" in gatewayState.capabilities) appGraph.gatewayRuntime.sendRequest(GatewayRequests.goal(sessionId))
            return
        }
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
            platformError = appText("请先连接 DeepSeek Harness")
            return
        }
        if ("goals" !in gatewayState.capabilities) {
            platformError = appText("当前 Mobile Gateway 不支持目标管理，请升级并重启网关。")
            return
        }
        if (goalMutationKind != null) return
        goalMutationKind = type
        appGraph.gatewayScope.launch {
            val accepted = appGraph.gatewayRuntime.sendRequest(request(sessionId, goal.ref))
            if (!accepted) withContext(Dispatchers.Main.immediate) {
                goalMutationKind = null
                platformError = appText("目标操作正在处理中，请稍后重试。")
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
                appText("目标操作超时，已刷新当前目标后可重试。")
            } else {
                appText("目标操作未完成，已刷新当前目标后可重试。")
            }
        }
        sessionId?.let {
            gatewayFollowUps?.submit { appGraph.gatewayRuntime.sendRequest(GatewayRequests.goal(it)) }
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

    val gatewayLocalId: String get() = graph?.gatewayLocalId ?: "legacy"
    val gatewayDisplayName: String get() = graph?.gatewayDisplayName.orEmpty()

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
        appGraph.pairingHandler?.let { handler -> handler(payload); return }
        appGraph.diagnostics.intent(GatewayDiagnosticAction.PAIR)
        platformError = null
        appGraph.gatewayScope.launch {
            val result = runCatching { appGraph.gatewayRuntime.pair(payload) }
            withContext(Dispatchers.Main.immediate) {
                if (result.isSuccess) {
                    if (pairingPayload == payload) pairingPayload = ""
                } else {
                    val message = if (result.exceptionOrNull() is GatewayPairingPayloadException) {
                        result.exceptionOrNull()?.message ?: appText("配对信息无效")
                    } else {
                        appText("无法提交配对信息，请稍后重试。")
                    }
                    if (reportFailureGlobally) platformError = message
                    onFailure(message)
                }
            }
        }
    }

    fun disconnect() {
        cancelWorkspaceFileDownload()
        releaseCompletedWorkspaceFile()
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
        val imageTarget = snapshot.selectedSessionId
        platformError = null
        scope?.launch {
            runCatching { appGraph.imagePreprocessor.prepare(uri) }
                .onSuccess { image ->
                    val destination = if (snapshot.selectedSessionId == imageTarget) preparedImages else teamDrafts[imageTarget]?.second.orEmpty()
                    val nextCount = destination.size + 1
                    val nextBytes = destination.sumOf(AndroidPreparedImage::byteCount) + image.byteCount
                    val nextBase64Characters = destination.sumOf { it.outgoing.base64Data.length } +
                        image.outgoing.base64Data.length
                    if (
                        nextCount > AndroidImagePreprocessor.MAXIMUM_IMAGE_COUNT ||
                        nextBytes > AndroidImagePreprocessor.MAXIMUM_TOTAL_BYTES ||
                        nextBase64Characters > AndroidImagePreprocessor.MAXIMUM_TOTAL_BASE64_CHARACTERS
                    ) {
                        platformError = "image-selection-limit-exceeded"
                    } else {
                        if (snapshot.selectedSessionId == imageTarget) preparedImages = destination + image
                        else if (imageTarget != null) teamDrafts[imageTarget] = teamDrafts[imageTarget]?.first.orEmpty() to (destination + image)
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

    fun sendMessage(mode: String = "queue") {
        if (!selectedMemberCanSend) { platformError = "此子智能体会话为只读，无法继续对话"; return }
        if (localSearchIsFragment || localSearchIsOpening) {
            platformError = appText("当前为只读本地搜索片段，请先返回最新消息。")
            return
        }
        val appGraph = graph ?: return
        if (pendingMessageSubmission != null || sessionAgentPreset.blocksSending) return
        if (fileUploads.preparing || (fileUploads.drafts.isNotEmpty() &&
            (!fileUploads.canSend || fileUploads.sessionId != snapshot.selectedSessionId || isViewingTeamMember))) return
        val submission = captureMessageSubmission(mode)
        val commandExecution = if (isViewingTeamMember) null else slashCommandStore.commandExecutionForInput(submission.draft)
        if (commandExecution != null && submission.fileIds.isNotEmpty()) {
            platformError = "斜杠命令不支持文件，请使用普通消息发送"
            return
        }
        if (commandExecution == null || submission.images.isEmpty() || commandExecution.allowsImages) {
            applySessionAgentPresetTransition(sessionAgentPresetStore.beginMessage())
        }
        pendingMessageSubmission = submission
        appGraph.diagnostics.intent(
            GatewayDiagnosticAction.SEND_MESSAGE,
            hasSession = submission.sessionId != null,
            imageCount = submission.images.size
        )
        appGraph.gatewayScope.launch {
            if (commandExecution != null) {
                if (submission.images.isNotEmpty() && !commandExecution.allowsImages) {
                    withContext(Dispatchers.Main.immediate) {
                        pendingMessageSubmission = null
                        platformError = appText("此命令不支持图片")
                    }
                    return@launch
                }
                val sent = appGraph.gatewayRuntime.executeCommand(
                    line = commandExecution.line,
                    images = submission.images.map(AndroidPreparedImage::outgoing),
                    sessionId = submission.sessionId
                )
                withContext(Dispatchers.Main.immediate) {
                    pendingMessageSubmission = null
                    if (sent) {
                        pendingCommandSubmission = submission
                    } else {
                        applySessionAgentPresetTransition(sessionAgentPresetStore.requestFailed(
                            "command-execute", submission.sessionId, null, null
                        ))
                    }
                }
            } else {
                val sent = appGraph.gatewayRuntime.sendMessage(
                    text = submission.draft,
                    images = submission.images.map(AndroidPreparedImage::outgoing),
                    sessionId = submission.sessionId,
                    workspaceId = activeWorkspace?.workspaceId,
                    clientTimeZone = TimeZone.getDefault().id,
                    mode = mode,
                    receipts = submission.receipts,
                    requestId = submission.requestId.takeIf { submission.fileIds.isNotEmpty() }
                )
                if (!sent) withContext(Dispatchers.Main.immediate) {
                    pendingMessageSubmission = null
                    applySessionAgentPresetTransition(sessionAgentPresetStore.requestFailed(
                        "message", submission.sessionId, null, null
                    ))
                }
            }
        }
    }

    fun cancelSelectedSession() {
        if (!selectedMemberCanSend) return
        val appGraph = graph ?: return
        val sessionId = snapshot.selectedSessionId ?: return
        val isRunning = selectedSessionIsRunning
        if (
            !isRunning ||
            "session-cancel" !in gatewayState.capabilities ||
            gatewayState.connection != GatewayConnectionState.CONNECTED ||
            sessionId in cancellingSessionIds
        ) return
        cancellingSessionIds = cancellingSessionIds + sessionId
        appGraph.gatewayScope.launch {
            val accepted = appGraph.gatewayRuntime.sendRequest(GatewayRequests.sessionCancel(sessionId))
            if (!accepted) withContext(Dispatchers.Main.immediate) {
                cancellingSessionIds = cancellingSessionIds - sessionId
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

    private fun captureMessageSubmission(mode: String = "queue"): MessageSubmission = MessageSubmission(
        generation = inputGeneration,
        sessionId = pendingSelectedSessionId ?: snapshot.selectedSessionId,
        draft = composedMessageText(),
        images = preparedImages.toList(),
        fileIds = fileUploads.drafts.map { it.id },
        receipts = fileUploads.receipts,
        requestId = if (fileUploads.drafts.isEmpty()) UUID.randomUUID().toString()
            else fileUploads.submission(composedMessageText(), preparedImages.map { System.identityHashCode(it) }.toString() + "|" + mode)
    )

    private fun applyMessageSendResult(submission: MessageSubmission, sent: Boolean) {
        if (!sent) return
        fileUploads.accepted(submission.fileIds)
        if (submission.sessionId != (pendingSelectedSessionId ?: snapshot.selectedSessionId)) {
            val saved = teamDrafts[submission.sessionId]
            if (saved?.first == submission.draft && saved.second == submission.images) {
                teamDrafts[submission.sessionId!!] = "" to emptyList()
            }
            return
        }
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

    fun trimMemory() {
        chatPrefetcher?.stop("memory")
        thumbnailCache.retainKeys(visibleAttachmentKeys)
        attachmentStateCache.retainKeys(visibleAttachmentKeys)
        publishAttachmentState()
        graph?.gatewayScope?.launch { projectionActor.trimMemory() }
    }

    fun close() {
        fileUploads.clear()
        closed = true
        catalogWriteJob?.cancel()
        chatPrefetcher?.stop("close")
        workspaceFileOperationId = UUID.randomUUID()
        thumbnailCache.retainKeys(emptySet())
        attachmentStateCache.retainKeys(emptySet())
        attachmentThumbnails = emptyMap()
        releaseCompletedWorkspaceFile()
        closeWorkspaceTemporaryFile(remove = true)
        gatewayFollowUps?.close()
        streamingSnapshotPublishJob?.cancel()
        projectionActor.close()
        localSearchJob?.cancel()
        runtimeCollection?.cancel()
        scope?.cancel()
    }

    val offlineBrowsing: Boolean
        get() = graph != null && gatewayState.connection != GatewayConnectionState.CONNECTED

    private fun scheduleOfflineCatalogSave() {
        val appGraph = graph ?: return
        if (closed || !catalogLoadCompleted) return
        catalogWriteJob?.cancel()
        catalogWriteJob = appGraph.gatewayScope.launch {
            val cache = com.clarklevis.dsh.android.platform.AndroidChatHistoryCache.shared(appGraph.application)
            val ticket = cache.offlineCatalogTicket(appGraph.gatewayLocalId)
            delay(150)
            if (!hasOfflineCatalogState && !projectionActor.hasOfflineCatalogAuthority()) return@launch
            val workspace = withContext(Dispatchers.Main.immediate) { selectedWorkspaceId }
            val baseCatalog = projectionActor.exportOfflineCatalog(workspace)
            val routing = withContext(Dispatchers.Main.immediate) { teamStore.routingJson() }
            val avatars = withContext(Dispatchers.Main.immediate) { teamStore.avatarAssignmentsJson() }
            val codec = com.clarklevis.dsh.shared.facade.SharedOfflineCatalogCodec()
            val routed = codec.withConversationTargets(baseCatalog, routing) ?: return@launch
            val catalog = codec.withTeamAvatars(routed, avatars) ?: return@launch
            val saved = withContext(Dispatchers.IO) { cache.saveOfflineCatalog(appGraph.gatewayLocalId, catalog, ticket) }
            appGraph.diagnostics.offlineCatalog(if (saved) "saved" else "skip-write", snapshot.sessions.size)
        }
    }

    private fun persistTeamAvatarsIfChanged() {
        val avatars = teamStore.avatarAssignmentsJson()
        if (avatars != persistedTeamAvatars) {
            persistedTeamAvatars = avatars
            scheduleOfflineCatalogSave()
        }
    }

    val messageSubmissionPending: Boolean get() = pendingMessageSubmission != null

    val canSend: Boolean
        get() = gatewayState.connection == GatewayConnectionState.CONNECTED && selectedMemberCanSend &&
            !localSearchIsFragment &&
            !localSearchIsOpening &&
            !sessionAgentPreset.blocksSending &&
            pendingCommandSubmission == null && pendingMessageSubmission == null &&
            !fileUploads.preparing && (fileUploads.drafts.isEmpty() || fileUploads.canSend) &&
            (composedMessageText().isNotBlank() || preparedImages.isNotEmpty() || fileUploads.drafts.isNotEmpty())

    val showsSessionStopButton: Boolean
        get() {
            val sessionId = snapshot.selectedSessionId ?: return false
            return composedMessageText().isBlank() && preparedImages.isEmpty() && fileUploads.drafts.isEmpty() && !fileUploads.preparing &&
                "session-cancel" in gatewayState.capabilities &&
                selectedMemberCanSend && selectedSessionIsRunning
        }

    val canCancelSelectedSession: Boolean
        get() = showsSessionStopButton &&
            gatewayState.connection == GatewayConnectionState.CONNECTED &&
            snapshot.selectedSessionId !in cancellingSessionIds

    private fun handleSessionCancellationFrame(frame: GatewayFrame) {
        val sessionId = frame.sessionId ?: return
        when {
            frame.kind == "session-cancelled" && frame.accepted != true -> {
                cancellingSessionIds = cancellingSessionIds - sessionId
                platformError = appText("停止请求未被服务端接受")
            }
            frame.kind == "event" && frame.event?.type == "turn/end" -> {
                cancellingSessionIds = cancellingSessionIds - sessionId
            }
        }
    }

    private fun notifyUserForAgentFrame(appGraph: AndroidAppGraph, frame: GatewayFrame) {
        if (frame.kind != "approval-requested" &&
            (frame.kind != "event" || frame.event?.type != "turn/end")
        ) return
        val sessionId = frame.sessionId?.takeIf(String::isNotBlank) ?: return
        val sessionTitle = snapshot.sessions.firstOrNull { it.id == sessionId }
            ?.title?.takeIf(String::isNotBlank) ?: "DeepSeek Harness"
        when {
            frame.kind == "approval-requested" -> {
                val request = snapshot.pendingApprovals.firstOrNull {
                    it.sessionId == sessionId && it.rpcId == frame.rpcId
                } ?: return
                appGraph.agentNotifications.notifyApprovalRequired(
                    gatewayId = appGraph.gatewayLocalId,
                    requestId = request.rpcId,
                    sessionId = sessionId,
                    sessionTitle = sessionTitle,
                    detail = request.localizedReason(Locale.getDefault().toLanguageTag()) ?: request.toolName
                )
            }
            frame.kind == "event" && frame.event?.type == "turn/end" -> {
                val sequence = frame.seq ?: return
                if (frame.time == null) return
                val event = frame.event ?: return
                val failed = event.isError == true || event.interrupted == true ||
                    !event.error.isNullOrBlank() ||
                    event.reason?.lowercase(Locale.ROOT) in setOf(
                        "error", "failed", "cancelled", "canceled", "interrupted", "aborted"
                    )
                appGraph.agentNotifications.notifyExecutionEnded(
                    gatewayId = appGraph.gatewayLocalId,
                    sessionId = sessionId,
                    sequence = sequence,
                    sessionTitle = sessionTitle,
                    failed = failed
                )
            }
        }
    }

    private fun handleSessionCancellationFailure(requestType: String, sessionId: String?) {
        if (requestType != "session-cancel") return
        sessionId?.let { cancellingSessionIds = cancellingSessionIds - it }
    }

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
        if (localSearchIsFragment || localSearchIsOpening) return
        if (sessionAgentPreset.blocksSending) return
        applySessionAgentPresetTransition(sessionAgentPresetStore.beginMessage())
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
            withContext(Dispatchers.Main.immediate) {
                if (sent) successfulMessageSendCount += 1
                else applySessionAgentPresetTransition(sessionAgentPresetStore.requestFailed(
                    "message", sessionId, null, null
                ))
            }
        }
    }

    private fun executeSlashCommandImmediately(command: com.clarklevis.dsh.shared.facade.SharedSlashCommandExecution) {
        val appGraph = graph ?: return
        val sessionId = snapshot.selectedSessionId ?: return
        if (sessionAgentPreset.blocksSending) return
        applySessionAgentPresetTransition(sessionAgentPresetStore.beginMessage())
        appGraph.gatewayScope.launch {
            if (!appGraph.gatewayRuntime.executeCommand(command.line, emptyList(), sessionId)) {
                withContext(Dispatchers.Main.immediate) {
                    applySessionAgentPresetTransition(sessionAgentPresetStore.requestFailed(
                        "command-execute", sessionId, null, null
                    ))
                }
            }
        }
    }

    private fun handleCommandExecuted(frame: GatewayFrame) {
        val result = frame.result?.objectValue
        val succeeded = result?.get("kind")?.stringValue == "success"
        val detail = result?.get("text")?.stringValue
        if (succeeded) {
            pendingCommandSubmission?.let { applyMessageSendResult(it, true) }
        } else {
            platformError = detail ?: frame.message ?: appText("命令执行失败")
        }
        pendingCommandSubmission = null
    }

    private fun slashCommandErrorMessage(code: String): String = when (code) {
        "command-frame-invalid", "command-catalog-invalid", "command-options-invalid",
        "command-selection-invalid" -> appText("斜杠命令协议响应无效")
        "request-timeout" -> appText("斜杠命令请求超时")
        "request-busy" -> appText("斜杠命令请求正在处理中")
        else -> appText("斜杠命令操作失败：%1\$s", code)
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
            publishSnapshot(next)
            return
        }

        pendingStreamingSnapshot = next
        if (streamingSnapshotPublishJob?.isActive == true) return
        streamingSnapshotPublishJob = holderScope.launch {
            delay(if (trajectoryIsActive) TRAJECTORY_STREAM_INTERVAL_MILLISECONDS else STREAMING_SNAPSHOT_INTERVAL_MILLISECONDS)
            pendingStreamingSnapshot?.let(::publishSnapshot)
            pendingStreamingSnapshot = null
            streamingSnapshotPublishJob = null
        }
    }

    private fun publishSnapshot(next: SharedMobileSnapshot) {
        val context = localSearchContextSessionId
        if (context != null && next.selectedSessionId != context && pendingSelectedSessionId != context) {
            localSearchContextSessionId = null
            localSearchContextTarget = null
            localSearchScrollTarget = null
            localSearchIsFragment = false
            localSearchIsOpening = false
        }
        snapshot = next
        updatePrefetchForeground(next)
        restoreQueuedDraft()
        val runningSessionIds = next.sessions
            .filter { it.isRunning }
            .mapTo(mutableSetOf()) { it.id }
        cancellingSessionIds = cancellingSessionIds.intersect(runningSessionIds)
    }

    private fun updatePrefetchForeground(state: SharedMobileSnapshot) {
        chatPrefetcher?.foregroundChanged(state.selectedSessionId,
            state.isChatPrefetchBusy(),
            !prefetchUsesAssistantStream)
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
                    platformError = appText("Gateway 未返回创建后的工作区")
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
                    platformError = frame.message ?: appText("工作区请求失败")
                }
            }
        }
    }

    private suspend fun applyWorkspaceFileTransition(
        appGraph: AndroidAppGraph,
        transition: SharedWorkspaceFileTransition,
        cachedFile: File? = null,
        cachedLease: java.io.Closeable? = null,
        operationId: UUID = workspaceFileOperationId
    ) {
        transition.directory?.let { directory ->
            withContext(Dispatchers.Main.immediate) {
                if (operationId == workspaceFileOperationId && directory.sessionId == snapshot.selectedSessionId) {
                    conversationFileIsOpening = false
                    workspaceFilePreviewRequest = null
                    workspaceFilePreviewProgress = 0f
                    workspaceFilePath = directory.path
                    workspaceFileEntries = emptyList()
                    workspaceFilesAreLoading = true
                    conversationDirectorySessionId = directory.sessionId
                    conversationDirectoryPath = directory.path
                }
            }
            return
        }
        val storage = requireNotNull(cacheStorage)
        var readyLease: java.io.Closeable? = cachedLease
        var pendingTemporaryFile: File? = null
        try {
            val cache = AndroidWorkspacePreviewCache(File(appGraph.application.cacheDir, "workspace-previews"), gatewayLocalId, storage)
            transition.cacheLookup?.let { lookup ->
                val file = withContext(Dispatchers.IO) { cache.find(lookup) { readyLease = storage.lease(it) } }
                if (operationId != workspaceFileOperationId) {
                    readyLease?.close()
                    return
                }
                val next = workspaceFileStore.completeCacheLookup(lookup.requestId, file != null)
                val transferredLease = readyLease
                readyLease = null
                applyWorkspaceFileTransition(appGraph, next, file, transferredLease, operationId)
                return
            }
            if (transition.discardTransferId != null) closeWorkspaceTemporaryFile(remove = true)
            val snapshot = transition.snapshot
            try {
                if (snapshot.activeDownload != null && workspaceFileOutput == null) {
                    check(storage.canWriteTemporary(snapshot.activeDownload?.size ?: 0))
                    openWorkspaceTemporaryFile(appGraph, snapshot.activeDownload?.name ?: "download")
                }
                transition.appendBase64Data?.let { encoded ->
                    val bytes = Base64.decode(encoded, Base64.NO_WRAP)
                    check(storage.canWriteTemporary(bytes.size.toLong()))
                    requireNotNull(workspaceFileOutput).write(bytes)
                }
            } catch (_: Throwable) {
                val cancelled = workspaceFileStore.cancel()
                closeWorkspaceTemporaryFile(remove = true)
                withContext(Dispatchers.Main.immediate) {
                    workspaceFileDownloadProgress = null
                    workspaceFileDownloadPath = null
                    workspaceFileDownloadPurpose = null
                    platformError = appText("写入下载文件失败")
                    conversationFileIsOpening = false
                }
                cancelled.request?.let { request ->
                    gatewayFollowUps?.submit { appGraph.gatewayRuntime.sendRequest(request) }
                }
                return
            }

            var completed: AndroidWorkspaceLocalFile? = transition.cacheHit?.let { hit ->
                cachedFile?.let { file ->
                    AndroidWorkspaceLocalFile(
                        file,
                        hit.sessionId,
                        hit.path,
                        hit.name,
                        hit.mediaType,
                        "preview"
                    )
                }
            }
            transition.completion?.let { completion ->
                workspaceFileOutput?.fd?.sync()
                closeWorkspaceTemporaryFile(remove = false)
                val file = workspaceFileTemporaryFile
                if (file == null || file.length() != completion.size) {
                    closeWorkspaceTemporaryFile(remove = true)
                    withContext(Dispatchers.Main.immediate) {
                        platformError = appText("下载文件的本地大小校验失败")
                        conversationFileIsOpening = false
                    }
                    return
                }
                workspaceFileTemporaryFile = null
                pendingTemporaryFile = file
                val temporaryLease = workspaceFileTemporaryLease
                workspaceFileTemporaryLease = null
                val saved = try {
                    withContext(Dispatchers.IO) {
                        cache.store(file, completion, workspaceFileCacheEpoch) { readyLease = storage.lease(it) }
                    }
                } catch (error: Throwable) {
                    temporaryLease?.close()
                    throw error
                }
                if (saved != null) {
                    temporaryLease?.close()
                    storage.deleteTemporary(file)
                    pendingTemporaryFile = null
                } else {
                    readyLease = temporaryLease
                }
                if (operationId != workspaceFileOperationId) {
                    readyLease?.close()
                    if (saved == null) storage.deleteTemporary(file)
                    return
                }
                completed = AndroidWorkspaceLocalFile(
                    file = saved ?: file,
                    sessionId = completion.sessionId,
                    remotePath = completion.path,
                    name = completion.name,
                    mediaType = completion.mediaType,
                    purpose = completion.purpose
                )
            }

            withContext(Dispatchers.Main.immediate) {
                if (operationId != workspaceFileOperationId) {
                    readyLease?.close()
                    completed?.file?.let(storage::deleteTemporary)
                    return@withContext
                }
                if (workspaceFilePreviewRequest != null) {
                    val active = snapshot.activeDownload
                    if (active?.purpose == "preview" && active.size > 0) {
                        workspaceFilePreviewProgress = (active.receivedBytes.toFloat() / active.size).coerceIn(0f, 1f)
                    } else if (snapshot.lastError == null &&
                        (
                            transition.appendBase64Data != null ||
                                transition.completion?.purpose == "preview" ||
                                transition.cacheHit != null
                            )
                    ) {
                        workspaceFilePreviewProgress = 1f
                    }
                }
                workspaceFilePath = snapshot.path
                workspaceFileEntries = snapshot.entries
                workspaceFilesAreLoading = snapshot.isLoading
                workspaceFileDownloadProgress = snapshot.activeDownload?.let { active ->
                    if (active.size == 0L) {
                        0f
                    } else {
                        (active.receivedBytes.toFloat() / active.size.toFloat()).coerceIn(0f, 1f)
                    }
                }
                workspaceFileDownloadPath = snapshot.activeDownload?.path
                workspaceFileDownloadPurpose = snapshot.activeDownload?.purpose
                snapshot.lastError?.let {
                    platformError = workspaceFileErrorMessage(it)
                    conversationFileIsOpening = false
                }
                completed?.let {
                    conversationFileIsOpening = false
                    if (it.sessionId == this@AndroidSharedStateHolder.snapshot.selectedSessionId) {
                        if (readyLease != null) {
                            completedWorkspaceFileLease?.close()
                            completedWorkspaceFileLease = readyLease
                            readyLease = null
                        }
                        pendingTemporaryFile = null
                        completedWorkspaceFile = it
                    } else {
                        readyLease?.close()
                        storage.deleteTemporary(it.file)
                    }
                }
            }
            transition.request?.let { request ->
                gatewayFollowUps?.submit {
                    // 取消指令始终发送，快速重开也不能让服务端旧 transfer 残留。
                    if (request.requestType == "file-download-cancel" || operationId == workspaceFileOperationId) {
                        // 快速重开时，旧的 stat 响应可能仍在途中；解析请求排队，避免误报 request-busy。
                        val next = if (request.requestType == "file-resolve") {
                            request.copy(lanePolicy = GatewayRequestLanePolicy.COALESCE_LATEST)
                        } else {
                            request
                        }
                        appGraph.gatewayRuntime.sendRequest(next)
                    }
                }
            }
        } finally {
            readyLease?.close()
            pendingTemporaryFile?.let(storage::deleteTemporary)
        }
    }

    private fun openWorkspaceTemporaryFile(appGraph: AndroidAppGraph, name: String) = requireNotNull(cacheStorage).access {
        closeWorkspaceTemporaryFile(remove = true)
        val directory = File(
            appGraph.application.cacheDir,
            "workspace-files/${UUID.randomUUID()}"
        ).apply { check(mkdirs() || isDirectory) }
        val file = File(directory, AndroidCacheDiskManager.safeFileName(name))
        workspaceFileTemporaryLease = cacheStorage?.lease(file)
        workspaceFileTemporaryFile = file
        workspaceFileOutput = FileOutputStream(file, false)
    }

    private fun closeWorkspaceTemporaryFile(remove: Boolean) {
        runCatching { workspaceFileOutput?.close() }
        workspaceFileOutput = null
        if (remove) {
            workspaceFileTemporaryLease?.close()
            workspaceFileTemporaryLease = null
            workspaceFileTemporaryFile?.let { file ->
                if (cacheStorage != null) {
                    cacheStorage.deleteTemporary(file)
                } else {
                    file.delete()
                    file.parentFile?.delete()
                }
            }
            workspaceFileTemporaryFile = null
        }
    }

    private fun workspaceFileErrorMessage(code: String): String = when (code) {
        "file-references-upgrade-required" -> appText("此文件需要新版网关解析，请升级 Mobile Gateway。")
        "file-download-integrity-failed" -> appText("文件完整性校验失败，请重新下载。")
        "download-busy" -> appText("已有文件正在下载，请稍后再试。")
        "file-download-offset-mismatch" -> appText("文件分块顺序异常，下载已取消。")
        else -> appText("工作区文件请求失败：%1\$s", code)
    }

    private fun applyWorkspaceSelection(workspaceId: String, persist: Boolean) {
        if (selectedWorkspaceId == workspaceId) return
        selectedWorkspaceId = workspaceId
        if (!persist) return
        scheduleOfflineCatalogSave()
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
            } else if (appGraph.gatewayRuntime.state.value.connection != GatewayConnectionState.CONNECTED ||
                !appGraph.gatewayRuntime.requestAttachment(request.sessionId, request.attachmentId)
            ) {
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
        val images: List<AndroidPreparedImage>,
        val fileIds: List<String> = emptyList(),
        val receipts: List<String> = emptyList(),
        val requestId: String = UUID.randomUUID().toString()
    )

    // 所有实例状态初始化完成后才启动异步观察；回调可在构造线程返回前到达。
    init {
        graph?.let { appGraph ->
            val holderScope = requireNotNull(scope)
            runtimeCollection = appGraph.gatewayScope.launch {
                launch {
                    val cache = com.clarklevis.dsh.android.platform.AndroidChatHistoryCache.shared(appGraph.application)
                    val generation = projectionActor.offlineCatalogRestoreGeneration()
                    val catalog = try {
                        withContext(Dispatchers.IO) { cache.loadOfflineCatalog(appGraph.gatewayLocalId) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    if (catalog != null) {
                        withContext(Dispatchers.Main.immediate) {
                            com.clarklevis.dsh.shared.facade.SharedOfflineCatalogCodec()
                                .conversationTargetsJson(catalog)?.let { teamStore.restoreRoutingJson(it) }
                            persistedTeamRouting = teamStore.routingJson()
                            com.clarklevis.dsh.shared.facade.SharedOfflineCatalogCodec()
                                .teamAvatarsJson(catalog)?.let { teamStore.restoreAvatarAssignmentsJson(it) }
                            persistedTeamAvatars = teamStore.avatarAssignmentsJson()
                        }
                        val restored = projectionActor.restoreOfflineCatalog(catalog, generation) { restoredWorkspaces ->
                            hasOfflineCatalogState = true
                            if (restoredWorkspaces && !workspaceSelectedByUser && selectedWorkspaceId == null) {
                                selectedWorkspaceId = com.clarklevis.dsh.shared.facade.SharedOfflineCatalogCodec().selectedWorkspaceId(catalog)
                            }
                        }
                        appGraph.diagnostics.offlineCatalog(if (restored) "restore" else "skip-late", snapshot.sessions.size)
                    } else appGraph.diagnostics.offlineCatalog("miss", 0)
                    catalogLoadCompleted = true
                    withContext(Dispatchers.Main.immediate) { scheduleOfflineCatalogSave() }
                }
                launch {
                    appGraph.preferences.snapshots.collect { value ->
                        withContext(Dispatchers.Main.immediate) {
                            endpoint = value.endpoint
                            if (!workspaceSelectedByUser) selectedWorkspaceId = value.selectedWorkspaceId ?: selectedWorkspaceId
                            workspacePreferenceLoaded = true
                            if (hasReceivedWorkspaces) reconcileWorkspaceSelection()
                        }
                    }
                }
                launch {
                    appGraph.gatewayRuntime.state.collect { state ->
                        appGraph.diagnostics.runtimeState(state)
                        chatPrefetcher?.connectionChanged(state.connection == GatewayConnectionState.CONNECTED)
                        var shouldCatchUpSelectedHistory = false
                        var sessionIdToRefresh: String? = null
                        withContext(Dispatchers.Main.immediate) {
                            val didReconnect = state.connection == GatewayConnectionState.CONNECTED &&
                                lastObservedConnection != GatewayConnectionState.CONNECTED
                            gatewayState = state
                            voiceInput.connectionChanged(state.connection == GatewayConnectionState.CONNECTED,
                                "speech-input-v1" in state.capabilities)
                            if (didReconnect) {
                                queueState = queueStore.resetConnection()
                                fileUploads.connected(snapshot.selectedSessionId, "file-upload-v1" in state.capabilities)
                            }
                            if (state.connection != GatewayConnectionState.CONNECTED) {
                                fileUploads.disconnected()
                                remoteSearch.disconnected()
                                remoteSearchTimeout?.cancel()
                                remoteSearchResultQuery = null
                                applySessionAgentPresetTransition(sessionAgentPresetStore.disconnected())
                                pendingMessageSubmission = null
                                cancellingSessionIds = emptySet()
                            }
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
                                // 离线目录中的选中项不代表用户已打开该会话。
                                sessionIdToRefresh = snapshot.selectedSessionId?.takeIf { teamLeadSessionId != null }
                                shouldCatchUpSelectedHistory = sessionIdToRefresh != null
                                if (pluginPageVisited) refreshPlugins()
                            }
                            if (state.connection != GatewayConnectionState.CONNECTED && pluginToggleRequestId != null) {
                                pluginNotice = if (pluginPendingName == "dsh-plugin-mobile-gateway") {
                                    appText("网关连接已断开，停用结果待重连确认。若已停用，请从 DSH Web 或 CLI 重新开启。")
                                } else appText("连接已断开，操作结果待重连确认")
                                pluginToggleRequestId = null
                                pluginPendingName = null
                            }
                            lastObservedConnection = state.connection
                        }
                        if (state.connection != GatewayConnectionState.CONNECTED) {
                            withContext(Dispatchers.Main.immediate) { conversationFileIsOpening = false }
                            workspaceFileOperationId = UUID.randomUUID()
                            applyWorkspaceFileTransition(appGraph, workspaceFileStore.cancel())
                            projectionActor.disconnected()
                        }
                        // 订阅只补发重连后的实时事件。重新拉取 latest history，按 seq 与
                        // 本地 live tail 合并，补齐应用在后台断线期间由其他端发送的用户消息。
                        if (shouldCatchUpSelectedHistory) {
                            projectionActor.catchUpSelectedHistoryAfterReconnect()
                        }
                        sessionIdToRefresh?.let { requestSessionControls(appGraph, it) }
                    }
                }
                // 两条物理线路分别消费，文件响应不等待对话投影队列清空。
                for (eventStream in listOf(appGraph.gatewayRuntime.events, appGraph.gatewayRuntime.conversationEvents)) {
                    launch {
                        eventStream.collect { event ->
                            // Runtime events 是单消费者流。所有一次性响应也必须由这里分发，
                            // 否则另起 collector 会与投影竞争并随机吞掉 session-created。
                            pendingSessionCreation.accept(event)
                            appGraph.diagnostics.runtimeEvent(event)
                            val profileId = when (event) {
                                is GatewayRuntimeEvent.RequestCancelled -> event.correlationId
                                is GatewayRuntimeEvent.RequestTimedOut -> event.correlationId
                                is GatewayRuntimeEvent.RequestRejected -> event.correlationId
                                else -> null
                            }?.takeIf { it.startsWith("team-profile-") }
                            if (profileId != null) {
                                withContext(Dispatchers.Main.immediate) {
                                    teamStore.finishMemberProfileRequest(profileId)
                                    prepareTeamMemberProfiles()
                                }
                                return@collect
                            }
                            if (event is GatewayRuntimeEvent.RequestTimedOut && event.requestType.startsWith("file-upload-")) {
                                withContext(Dispatchers.Main.immediate) { event.correlationId?.let(fileUploads::timedOut) }
                                return@collect
                            }
                            if (chatPrefetcher?.interceptTerminal(event) == true) return@collect
                            when (event) {
                                is GatewayRuntimeEvent.Frame -> {
                                    if (event.frame.kind.startsWith("file-upload-") || event.frame.requestType?.startsWith("file-upload-") == true) {
                                        withContext(Dispatchers.Main.immediate) { fileUploads.accept(event.frame) }
                                        return@collect
                                    }
                                    if (event.frame.kind == "error" && event.frame.requestType in WORKSPACE_FILE_REQUEST_TYPES) {
                                        applyWorkspaceFileTransition(appGraph, workspaceFileStore.acceptFrame(event.rawJson))
                                        return@collect
                                    }
                                    if (event.frame.kind in setOf("history", "error") && event.frame.requestId?.startsWith("team-profile-") == true) {
                                        withContext(Dispatchers.Main.immediate) {
                                            if (teamStore.acceptFrameJson(event.rawJson)) {
                                                teamLeadSessionId?.let { teamSnapshot = teamStore.snapshot(it) }
                                                persistTeamAvatarsIfChanged()
                                            }
                                            prepareTeamMemberProfiles()
                                        }
                                        return@collect
                                    }
                                    if (event.frame.kind in setOf("hello", "sessions", "session-status", "projection-baseline", "agent-team", "agent-team-updated", "subagents", "subagents-updated", "subagent-identity-updated", "subagent-timing-updated", "session-snapshot", "history", "error")) {
                                        withContext(Dispatchers.Main.immediate) {
                                            if (teamStore.acceptFrameJson(event.rawJson)) {
                                                teamLeadSessionId?.let { teamSnapshot = teamStore.snapshot(it) }
                                                prepareTeamMemberProfiles()
                                                persistTeamAvatarsIfChanged()
                                                val routing = teamStore.routingJson()
                                                if (routing != persistedTeamRouting) {
                                                    persistedTeamRouting = routing
                                                    scheduleOfflineCatalogSave()
                                                }
                                                if (event.frame.kind == "hello") refreshTeam()
                                                if (event.frame.kind in setOf("session-snapshot", "subagent-identity-updated")) {
                                                    snapshot.selectedSessionId?.let { selected ->
                                                        val address = teamStore.targetJson(selected)
                                                        appGraph.gatewayScope.launch { appGraph.gatewayRuntime.registerConversationTarget(address) }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    // 预加载失败只结束对应缓存任务，不能冒充当前会话的错误弹窗。
                                    if (chatPrefetcher?.intercept(event.rawJson, event.frame, event.correlatedSessionId, event.isBackgroundHistory) == true) return@collect
                                    if (event.frame.kind == "error" && !event.isBackgroundHistory &&
                                        event.frame.requestType in setOf("message", "subscribe", "history", "session-cancel")) {
                                        withContext(Dispatchers.Main.immediate) {
                                            platformError = event.frame.message ?: "子智能体请求失败"
                                            if (event.frame.requestType == "message") {
                                                pendingMessageSubmission?.takeIf {
                                                    event.frame.requestId == it.requestId || (it.fileIds.isEmpty() && event.frame.requestId == null)
                                                }?.let {
                                                    if (event.frame.code == "session/attachment-invalid") fileUploads.invalidReceipts()
                                                    pendingMessageSubmission = null
                                                }
                                            }
                                        }
                                    }
                                    if (event.frame.kind == "search") {
                                        if (gatewayState.connection != GatewayConnectionState.CONNECTED) return@collect
                                        val searchResponse = remoteSearch.response(event.frame.query)
                                        if (!searchResponse.matched) return@collect
                                        withContext(Dispatchers.Main.immediate) {
                                            remoteSearchTimeout?.cancel()
                                            searchResponse.nextQuery?.let(::sendRemoteSearch)
                                            remoteSearchResultQuery = searchResponse.acceptedQuery
                                        }
                                        if (searchResponse.acceptedQuery == null) return@collect
                                    }
                                    withContext(Dispatchers.Main.immediate) { voiceInput.accept(event.rawJson) }
                                    withContext(Dispatchers.Main.immediate) {
                                        applySessionAgentPresetTransition(sessionAgentPresetStore.accept(
                                            if (event.frame.sessionId == null && event.correlatedSessionId != null) {
                                                event.frame.copy(sessionId = event.correlatedSessionId)
                                            } else event.frame
                                        ))
                                        handleSessionCancellationFrame(event.frame)
                                        acceptPluginFrame(event.frame)
                                        if (event.frame.kind == scheduledTaskMutationKind &&
                                            event.frame.requestId == scheduledTaskMutationRequestId
                                        ) {
                                            val succeeded = if (event.frame.kind == "schedule-update") {
                                                event.frame.updated == true
                                            } else event.frame.deleted == true
                                            finishScheduledTaskMutation(
                                                if (succeeded) null else scheduledTaskFailureMessage(event.frame.code, event.frame.message)
                                            )
                                        } else if (event.frame.kind == "error" &&
                                            event.frame.requestType == scheduledTaskMutationKind &&
                                            (event.frame.requestId == null || event.frame.requestId == scheduledTaskMutationRequestId)
                                        ) {
                                            finishScheduledTaskMutation(scheduledTaskFailureMessage(event.frame.code, event.frame.message))
                                        }
                                        when (event.frame.kind) {
                                            "schedule-catalog" -> {
                                                scheduledTasks = event.frame.items.orEmpty().mapNotNull(MobileScheduledTask::from)
                                                    .sortedWith(compareBy<MobileScheduledTask> { it.status != "active" }.thenBy { it.scheduledAt })
                                                scheduledTasksLoading = false
                                                scheduledTasksError = null
                                            }
                                            "schedule-changed" -> refreshScheduledTasks()
                                            "error" -> if (event.frame.requestType == "schedule-catalog") {
                                                scheduledTasksLoading = false
                                                scheduledTasksError = event.frame.message ?: appText("定时任务加载失败")
                                            }
                                        }
                                        if (event.frame.kind == "sent") {
                                            pendingMessageSubmission?.takeIf { event.frame.requestId == it.requestId || (it.fileIds.isEmpty() && event.frame.requestId == null) }?.let {
                                                applyMessageSendResult(it, true)
                                                pendingMessageSubmission = null
                                            }
                                        }
                                        if (event.frame.kind in setOf("session-queues", "session-queue", "queue-item-updated") ||
                                            (event.frame.kind == "error" && event.frame.requestType == "queue-update")
                                        ) acceptQueueFrame(event.rawJson)
                                    }
                                    if (event.frame.kind in GOAL_MUTATION_RESPONSE_KINDS) {
                                        withContext(Dispatchers.Main.immediate) { goalMutationKind = null }
                                        event.frame.sessionId?.let { sessionId ->
                                            gatewayFollowUps?.submit {
                                                appGraph.gatewayRuntime.sendRequest(GatewayRequests.goal(sessionId))
                                            }
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
                                            if (event.frame.hasOfflineCatalogMutation()) {
                                                if (snapshot.sessions.isNotEmpty() || snapshot.workspaces.isNotEmpty() ||
                                                    event.frame.kind in setOf("session-archives", "session-archived")
                                                ) hasOfflineCatalogState = true
                                                scheduleOfflineCatalogSave()
                                            }
                                            if (event.frame.kind == "history") {
                                                event.correlatedSessionId?.let { sessionId ->
                                                    historyPagingSessionIds = historyPagingSessionIds - sessionId
                                                }
                                            }
                                        }
                                        false
                                    }
                                    if (event.frame.kind == "hello") {
                                        prefetchUsesAssistantStream = "assistant-stream-v1" in event.frame.capabilities.orEmpty()
                                        updatePrefetchForeground(snapshot)
                                        chatPrefetcher?.helloReceived(event.frame.historyFormatVersion)
                                    }
                                    if (event.frame.kind == "sessions") chatPrefetcher?.sessionsReceived()
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
                                            notifyUserForAgentFrame(appGraph, event.frame)
                                        }
                                        if (event.frame.kind in setOf("session-archives", "session-archived")) {
                                            gatewayFollowUps?.submit { appGraph.gatewayRuntime.requestSessions() }
                                        }
                                        if (event.frame.kind == "sent") {
                                            event.frame.sessionId?.takeIf(String::isNotBlank)?.let { sessionId ->
                                                handleSentSession(appGraph, sessionId)
                                            }
                                        }
                                    }
                                    if (trajectoryIsActive &&
                                        (!isStreamingChunk || streamingProjectionFlushed) &&
                                        event.frame.kind in TRAJECTORY_FRAME_KINDS &&
                                        (event.frame.sessionId ?: event.correlatedSessionId).let {
                                            it == null || it == snapshot.selectedSessionId
                                        }
                                    ) {
                                        requestTrajectoryPublish(
                                            streaming = isStreamingChunk || event.frame.kind == "assistant-stream"
                                        )
                                    }
                                }
                                is GatewayRuntimeEvent.AttachmentCached -> withContext(Dispatchers.Main.immediate) {
                                    attachmentCompleted(event.sessionId, event.attachmentId)
                                }
                                is GatewayRuntimeEvent.RequestQueued -> Unit
                                is GatewayRuntimeEvent.RequestCancelled -> {
                                    if (event.requestType == scheduledTaskMutationKind &&
                                        event.correlationId == scheduledTaskMutationRequestId
                                    ) withContext(Dispatchers.Main.immediate) {
                                        finishScheduledTaskMutation(appText("操作已取消，请刷新任务后重试"))
                                    }
                                    withContext(Dispatchers.Main.immediate) {
                                        applySessionAgentPresetTransition(sessionAgentPresetStore.requestFailed(
                                            event.requestType, event.targetSessionId, event.correlationId, appText("请求未完成，请重试")
                                        ))
                                        handleSessionCancellationFailure(event.requestType, event.targetSessionId)
                                        if (event.requestType == "message") pendingMessageSubmission = null
                                        if (event.requestType == "queue-update") {
                                            queueState = queueStore.failPending(appText("队列操作未完成，请检查最新状态后重试"))
                                            platformError = queueState.lastError
                                        }
                                    }
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
                                    if (event.requestType.startsWith("speech-")) withContext(Dispatchers.Main.immediate) {
                                        voiceInput.requestFailed(event.correlationId, appText("语音服务请求超时，请重试"))
                                    }
                                    if (event.requestType == scheduledTaskMutationKind &&
                                        event.correlationId == scheduledTaskMutationRequestId
                                    ) withContext(Dispatchers.Main.immediate) {
                                        finishScheduledTaskMutation(appText("操作超时，请刷新任务后重试"))
                                    }
                                    if (event.requestType == "schedule-catalog") {
                                        withContext(Dispatchers.Main.immediate) {
                                            scheduledTasksLoading = false
                                            scheduledTasksError = appText("定时任务请求超时，请重试")
                                        }
                                    }
                                    withContext(Dispatchers.Main.immediate) {
                                        applySessionAgentPresetTransition(sessionAgentPresetStore.requestFailed(
                                            event.requestType, event.targetSessionId, event.correlationId, appText("请求未完成，请重试")
                                        ))
                                        handleSessionCancellationFailure(event.requestType, event.targetSessionId)
                                        if (event.requestType == "message") pendingMessageSubmission = null
                                        if (event.requestType == "queue-update") {
                                            queueState = queueStore.failPending(appText("队列操作未完成，请检查最新状态后重试"))
                                            platformError = queueState.lastError
                                        }
                                    }
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
                                    // 旧 socket 的迟到响应已被 Runtime 丢弃，只保留诊断，不打断离线阅读。
                                    if (event.requestType == "transport" && event.reason == "stale-frame") return@collect
                                    if (event.requestType.startsWith("speech-")) withContext(Dispatchers.Main.immediate) {
                                        voiceInput.requestFailed(event.correlationId, appText("语音请求未完成，请重试"))
                                    }
                                    if (event.requestType == scheduledTaskMutationKind &&
                                        event.correlationId == scheduledTaskMutationRequestId
                                    ) withContext(Dispatchers.Main.immediate) {
                                        finishScheduledTaskMutation(appText("操作未被接受，请刷新任务后重试"))
                                    }
                                    withContext(Dispatchers.Main.immediate) {
                                        applySessionAgentPresetTransition(sessionAgentPresetStore.requestFailed(
                                            event.requestType, event.targetSessionId, event.correlationId, appText("请求未完成，请重试")
                                        ))
                                        handleSessionCancellationFailure(event.requestType, event.targetSessionId)
                                        if (event.requestType == "message") pendingMessageSubmission = null
                                        if (event.requestType == "queue-update") {
                                            queueState = queueStore.failPending(appText("队列操作未完成，请检查最新状态后重试"))
                                            platformError = queueState.lastError
                                        }
                                    }
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

    companion object {
        private const val STREAMING_SNAPSHOT_INTERVAL_MILLISECONDS = 32L
        private const val TRAJECTORY_STREAM_INTERVAL_MILLISECONDS = 100L
        private val TRAJECTORY_FRAME_KINDS = setOf(
            "session-snapshot", "history", "event", "assistant-stream",
            "session-stream-reset", "subscribed", "hello", "error"
        )
        private const val HISTORY_PAGE_MESSAGE_LIMIT = 60
        private const val OLDER_HISTORY_PAGE_MESSAGE_LIMIT = 120
        private const val HISTORY_PAGE_BYTE_BUDGET = 4 * 1_024 * 1_024
        private const val HISTORY_VIEW = "conversation"
        const val UNGROUPED_WORKSPACE_ID = "__ungrouped__"
        /** 2026-10-27（fork）：跨工作区"全部会话"视图的哨兵 id。 */
        const val ALL_WORKSPACES_ID = "__all_sessions__"

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
            "file-resolved", "file-list", "file-download-opened", "file-download-chunk", "file-download-cancelled"
        )
        private val WORKSPACE_FILE_REQUEST_TYPES = setOf(
            "file-resolve", "file-list", "file-download-open", "file-download-read", "file-download-cancel"
        )
        private const val MAXIMUM_THUMBNAIL_BYTES = 16L * 1_024 * 1_024
        private const val MAXIMUM_STATUS_COUNT = 256L
        private const val DEFAULT_THUMBNAIL_TARGET_PIXELS = 720
        const val DEFAULT_WIRE_PAYLOAD =
            """{"sessionId":"android-demo","seq":4,"time":1786937355,"event":{"type":"assistant/message","turn":1,"step":1,"text":"最终消息会替换流式临时消息。"}}"""
    }
}

/**
 * 把唯一 Runtime 事件消费者收到的创建响应交还给发起方。
 *
 * 响应可能在调用方开始 await 前到达，因此使用 CompletableDeferred 保存结果；锁只保护一个
 * 很短的内存状态变更，不跨协程挂起。
 */
internal class AndroidPendingSessionCreation {
    private data class Pending(
        val requestId: String,
        val response: CompletableDeferred<GatewayRuntimeEvent>
    )

    private val lock = Any()
    private var pending: Pending? = null

    fun begin(requestId: String): CompletableDeferred<GatewayRuntimeEvent> = synchronized(lock) {
        check(pending == null) { "A session creation request is already pending" }
        CompletableDeferred<GatewayRuntimeEvent>().also { response ->
            pending = Pending(requestId, response)
        }
    }

    fun accept(event: GatewayRuntimeEvent) {
        synchronized(lock) {
            val active = pending ?: return
            if (!event.matchesSessionCreation(active.requestId)) return
            active.response.complete(event)
            pending = null
        }
    }

    fun clear(requestId: String) {
        synchronized(lock) {
            if (pending?.requestId == requestId) pending = null
        }
    }
}

private fun GatewayRuntimeEvent.matchesSessionCreation(requestId: String): Boolean = when (this) {
    is GatewayRuntimeEvent.Frame -> frame.requestId == requestId &&
        (frame.kind == "session-created" ||
            frame.kind == "error" && frame.requestType == "session-create")
    is GatewayRuntimeEvent.RequestCancelled -> correlationId == requestId
    is GatewayRuntimeEvent.RequestTimedOut -> correlationId == requestId
    is GatewayRuntimeEvent.RequestRejected -> correlationId == requestId
    else -> false
}

internal fun resolveWorkspaceSelection(
    preferredWorkspaceId: String?,
    workspaces: List<GatewayWorkspace>
): String = when {
    preferredWorkspaceId == AndroidSharedStateHolder.ALL_WORKSPACES_ID ->
        AndroidSharedStateHolder.ALL_WORKSPACES_ID
    preferredWorkspaceId == AndroidSharedStateHolder.UNGROUPED_WORKSPACE_ID ->
        AndroidSharedStateHolder.UNGROUPED_WORKSPACE_ID
    workspaces.any { it.workspaceId == preferredWorkspaceId } -> requireNotNull(preferredWorkspaceId)
    workspaces.isNotEmpty() -> workspaces.first().workspaceId
    else -> AndroidSharedStateHolder.UNGROUPED_WORKSPACE_ID
}

enum class AttachmentLoadState { IDLE, LOADING, LOADED, FAILED, DEFERRED }

data class AndroidWorkspaceFilePreviewRequest(
    val sessionId: String,
    val path: String,
    val name: String,
    val id: UUID = UUID.randomUUID()
)

data class AndroidWorkspaceLocalFile(
    val file: File,
    val sessionId: String,
    val remotePath: String,
    val name: String,
    val mediaType: String,
    val purpose: String
)
