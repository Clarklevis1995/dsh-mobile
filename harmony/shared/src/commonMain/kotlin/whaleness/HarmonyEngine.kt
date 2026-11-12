package whaleness

import com.clarklevis.dsh.shared.domain.SessionListAction
import com.clarklevis.dsh.shared.domain.SessionListReducer
import com.clarklevis.dsh.shared.domain.SessionListState
import com.clarklevis.dsh.shared.facade.CachedHistoryWindow
import com.clarklevis.dsh.shared.facade.SharedAgentNotification
import com.clarklevis.dsh.shared.facade.SharedAgentNotificationStore
import com.clarklevis.dsh.shared.facade.SharedConversationPatch
import com.clarklevis.dsh.shared.facade.SharedConversationStore
import com.clarklevis.dsh.shared.facade.SharedHistoryStore
import com.clarklevis.dsh.shared.gateway.GatewayIdentity
import com.clarklevis.dsh.shared.gateway.GatewayPairingPayloadParser
import com.clarklevis.dsh.shared.gateway.GatewayProfile
import com.clarklevis.dsh.shared.gateway.GatewayRequest
import com.clarklevis.dsh.shared.gateway.GatewayRequests
import com.clarklevis.dsh.shared.projection.ConversationProjectionLabels
import com.clarklevis.dsh.shared.protocol.GatewayDirectoryItem
import com.clarklevis.dsh.shared.protocol.GatewayEvent
import com.clarklevis.dsh.shared.protocol.GatewayHostSnapshot
import com.clarklevis.dsh.shared.protocol.GatewayQuestionAnswer
import com.clarklevis.dsh.shared.protocol.GatewaySearchItem
import com.clarklevis.dsh.shared.protocol.GatewaySessionSummary
import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder
import com.clarklevis.dsh.shared.protocol.GatewayWorkspace
import com.clarklevis.dsh.shared.protocol.SessionEvent
import com.clarklevis.dsh.shared.protocol.wireJson
import com.clarklevis.dsh.shared.sync.AssistantStreamState
import com.clarklevis.dsh.shared.sync.HistorySyncConfiguration
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.random.Random

@Serializable
data class HarmonyCommand(
    val op: String,
    val frame: String = "",
    val channel: String = "control",
    val input: String = "",
    val endpoint: String = "",
    val pairingCode: String = "",
    val expectedGatewayId: String? = null,
    val sessionId: String? = null,
    val workspaceId: String? = null,
    val text: String = "",
    val timeZone: String = "Asia/Shanghai",
    val rpcId: String = "",
    val answers: List<GatewayQuestionAnswer> = emptyList(),
    val outcome: String = "",
    val requestKey: String = "",
    val path: String? = null,
    val query: String = "",
    val title: String = "",
    val locale: String? = null,
    val draftRevision: Long = 0,
    val mode: String = "queue",
    val provider: String? = null,
    val model: String? = null,
    val reasoningEffort: String? = null,
    val target: String = "",
    val value: String = "",
    val itemId: String? = null,
    val rows: List<List<String>> = emptyList(),
    val keepState: Boolean = false,
    val profile: GatewayProfile? = null,
    val nowMillis: Long = 0,
    val fileName: String = "",
    val byteSize: Long = 0,
    val sha256: String = "",
    val byteCount: Int = 0,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
)

@Serializable
data class HarmonySession(
    val id: String,
    val title: String,
    val running: Boolean,
    val cwd: String?,
    val lastActivityEpochSeconds: Double = 0.0,
    val hasConversation: Boolean = true,
    val unread: Boolean = false,
)

@Serializable
data class HarmonyDirectory(
    val path: String = "",
    val home: String = "",
    val crumbs: List<GatewayDirectoryItem> = emptyList(),
    val entries: List<GatewayDirectoryItem> = emptyList(),
)

@Serializable
data class HarmonyState(
    val connected: Boolean,
    val hostSnapshot: GatewayHostSnapshot? = null,
    val pendingRequest: Boolean,
    val pendingMessageSessionId: String?,
    val pendingOperations: List<String>,
    val questions: List<HarmonyQuestion>,
    val approvals: List<HarmonyApproval>,
    val sessions: List<HarmonySession>,
    val workspaces: List<GatewayWorkspace>,
    val selectedSessionId: String?,
    val hasMore: Boolean,
    val loadingHistory: Boolean,
    val pendingQuestionCount: Int,
    val pendingApprovalCount: Int,
    val archivedSessions: List<HarmonySession> = emptyList(),
    val capabilities: List<String> = emptyList(),
    val directory: HarmonyDirectory = HarmonyDirectory(),
    val directoryLoading: Boolean = false,
    val catalogBusy: List<String> = emptyList(),
    val searchQuery: String = "",
    val searchSessionIds: List<String> = emptyList(),
    val searching: Boolean = false,
    val catalogLoading: Boolean = false,
    val generating: Boolean = false,
    val historyRevision: Long = 0,
    val childConversation: Boolean = false,
    val canSend: Boolean = true,
    val operationTimeouts: Map<String, Long> = emptyMap(),
    val retainedHistorySessions: Int = 0,
    val historyMemoryBytes: Long = 0,
    val cachedHistory: Boolean = false,
    val catalogRevision: Long = 0,
)

@Serializable
data class HarmonyOutbound(
    val channel: String,
    val text: String,
    val requestKey: String? = null,
)

@Serializable
data class HarmonyPairing(
    val endpoint: String,
    val pairingCode: String,
    val gatewayId: String?,
    val endpoints: List<String> = listOf(endpoint),
    val gatewayName: String = "",
)

@Serializable
data class HarmonyResult(
    val ok: Boolean = true,
    val state: HarmonyState? = null,
    val patches: List<SharedConversationPatch> = emptyList(),
    val outbound: List<HarmonyOutbound> = emptyList(),
    val pairing: HarmonyPairing? = null,
    val token: String? = null,
    val gatewayId: String? = null,
    val openConversation: Boolean = false,
    val sent: Boolean = false,
    val sentSessionId: String? = null,
    val fatal: Boolean = false,
    val error: String? = null,
    val profile: GatewayProfile? = null,
    val presentation: HarmonyPresentation? = null,
    val trajectory: HarmonyTrajectory? = null,
    val controls: HarmonyControls? = null,
    val schedules: HarmonySchedules? = null,
    val team: HarmonyTeam? = null,
    val uploads: HarmonyUploads? = null,
    val uploadRead: HarmonyUploadRead? = null,
    val attachment: HarmonyAttachment? = null,
    val workspaceFiles: HarmonyWorkspaceFiles? = null,
    val fileIo: HarmonyFileIo? = null,
    val fileDiscard: String? = null,
    val fileCompletion: HarmonyFileCompletion? = null,
    val goalTasks: HarmonyGoalTasks? = null,
    val voice: HarmonyVoice? = null,
    val notifications: List<SharedAgentNotification> = emptyList(),
    val voiceEffects: List<HarmonyVoiceEffect> = emptyList(),
    val cache: HarmonyCacheExport? = null,
    val plugins: HarmonyPlugins? = null,
    val slash: HarmonySlash? = null,
    val draftMutation: HarmonyDraftMutation? = null,
    val features: HarmonySessionFeatures? = null,
    val restoredDrafts: Map<String, String> = emptyMap(),
)

/** ArkUI 只镜像视图补丁，协议、历史合并、流式状态机均复用主项目。入口由 UI 线程串行调用。 */
class HarmonyEngine {
    private var sessions = SessionListState()
    private val recentHistorySessions = mutableListOf<String>()
    private var historyMemoryBytes = 0L
    private var catalogRevision = 0L
    private var sessionsConfirmed = false
    private var workspacesConfirmed = false
    private var catalogRestored = false
    private var cacheResult: HarmonyCacheExport? = null
    private var workspaces = emptyList<GatewayWorkspace>()
    private var expectedGatewayId: String? = null
    private var preparedEndpoint: String? = null
    private var normalizedProfile: GatewayProfile? = null
    private var capabilities = emptyList<String>()
    private var hostSnapshot: GatewayHostSnapshot? = null
    private var directory = HarmonyDirectory()

    private data class CatalogRequest(
        val key: String,
        val target: String?,
    )

    private val catalogRequests = mutableMapOf<String, CatalogRequest>()
    private var searchQuery = ""
    private var searchSessionIds = emptyList<String>()
    private var catalogKey: String? = null
    private val catalogRemaining = mutableSetOf<String>()
    private var pendingSearch: Pair<String, String>? = null
    private var controlReady = false
    private var conversationReady = false
    private var split = false
    private var usesStream = false
    private val stream = AssistantStreamState()
    private val history = SharedHistoryStore(HistorySyncConfiguration(pagesPerBatch = 1))
    private val conversation =
        SharedConversationStore(
            ConversationProjectionLabels(
                userMessage = "你",
                streamingReasoning = "思考",
                finalReasoning = "思考",
                toolResultDone = "工具结果",
                toolResultFailed = "工具失败",
            ),
        )
    private val patches = mutableListOf<SharedConversationPatch>()
    private val outbound = mutableListOf<HarmonyOutbound>()
    private val more = mutableMapOf<String, Boolean>()
    private val cursors = mutableMapOf<String, Int?>()
    private val loading = mutableSetOf<String>()
    private val interactions = InteractionStore()
    private val trajectorySupport = TrajectorySupport()
    private val workspaceSupport = WorkspaceSupport()
    private var workspaceResult: HarmonyWorkspaceFiles? = null
    private var fileIo: HarmonyFileIo? = null
    private var fileDiscard: String? = null
    private var fileCompletion: HarmonyFileCompletion? = null
    private val attachmentSupport = AttachmentSupport()
    private var attachmentResult: HarmonyAttachment? = null
    private val uploadSupport = UploadSupport()
    private var uploadsResult: HarmonyUploads? = null
    private var uploadRead: HarmonyUploadRead? = null
    private val teamSupport = TeamSupport()
    private var teamResult: HarmonyTeam? = null
    private val scheduleSupport = ScheduleSupport()
    private var schedulesResult: HarmonySchedules? = null
    private val pluginSupport = PluginSupport()
    private var pluginsResult: HarmonyPlugins? = null
    private val slashSupport = SlashSupport()
    private var notice: String? = null
    private var slashResult: HarmonySlash? = null
    private var draftMutation: HarmonyDraftMutation? = null
    private val sessionFeatures = SessionFeatures()
    private var featuresResult: HarmonySessionFeatures? = null
    private val restoredDrafts = mutableMapOf<String, String>()
    private val notificationStore = SharedAgentNotificationStore()
    private val notifications = mutableListOf<SharedAgentNotification>()
    private val voiceSupport = VoiceSupport()
    private val goalSupport = GoalSupport()
    private var goalTasksResult: HarmonyGoalTasks? = null
    private val controlSupport = ControlSupport()
    private var controlsResult: HarmonyControls? = null
    private var trajectoryResult: HarmonyTrajectory? = null
    private var historyRevision = 0L
    private val requestNamespace = Random.nextBytes(16).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    private var requestSequence = 0L
    private var pendingCreate: String? = null
    private var pendingMessage: String? = null
    private var pendingMessageSessionId: String? = null
    private var sentSessionId: String? = null
    private val loadingOlder = mutableSetOf<String>()
    private val historyOperations = mutableMapOf<String, String>()
    private var pairing: HarmonyPairing? = null
    private var receivedToken: String? = null
    private var openConversation = false
    private var sent = false

    init {
        conversation.subscribePatches { _, patch -> patches += patch }
        history.subscribePatches { event, patch ->
            val change = patch.eventPatch ?: return@subscribePatches
            val snapshot =
                history.projectionSnapshotForEvent(patch.sessionId, event.sequence)
                    ?: return@subscribePatches
            trajectorySupport.received(snapshot)
            historyRevision++
            val result =
                when {
                    change.streamDelta && change.record != null && conversation.hasProjection(patch.sessionId) -> {
                        conversation.receiveTypedStreamDelta(change.record)
                    }

                    change.kind == "append" && change.record != null && conversation.hasProjection(patch.sessionId) -> {
                        conversation.receiveTypedEvent(change.record)
                    }

                    else -> {
                        conversation.replaceHistorySnapshot(patch.sessionId, snapshot)
                    }
                }
            check(result.accepted) { "会话投影更新失败" }
        }
    }

    fun dispatch(json: String): String {
        notifications.clear()
        patches.clear()
        outbound.clear()
        pairing = null
        receivedToken = null
        openConversation = false
        sent = false
        sentSessionId = null
        normalizedProfile = null
        trajectoryResult = null
        controlsResult = null
        featuresResult = null
        slashResult = null
        pluginsResult = null
        schedulesResult = null
        teamResult = null
        uploadsResult = null
        uploadRead = null
        attachmentResult = null
        workspaceResult = null
        fileIo = null
        fileDiscard = null
        fileCompletion = null
        cacheResult = null
        goalTasksResult = null
        notice = null
        draftMutation = null
        restoredDrafts.clear()
        return try {
            val command = wireJson.decodeFromString<HarmonyCommand>(json)
            if (command.op.startsWith("render-")) {
                wireJson.encodeToString(HarmonyResult(presentation = PresentationSupport.render(command)))
            } else if (command.op.startsWith("voice-")) {
                val voice =
                    voiceSupport.command(
                        command,
                        sessions.selectedSessionId,
                        connected(),
                        capabilities,
                        teamSupport.isChild(sessions.selectedSessionId),
                    )
                wireJson.encodeToString(
                    HarmonyResult(
                        voice = voice.view,
                        voiceEffects = voice.effects,
                        outbound = voice.requests.map { HarmonyOutbound("control", it.payload) },
                    ),
                )
            } else {
                execute(command)
                if (command.op in
                    setOf("select", "team-child-open", "team-root", "cache-import-window", "cache-export")
                ) {
                    trimHistoryMemory()
                }
                wireJson.encodeToString(result())
            }
        } catch (error: Exception) {
            // 不透传解码器异常，避免错误包含原始输入或配对凭据。
            val safe =
                if (error is kotlinx.serialization.SerializationException) {
                    "网关数据格式无效"
                } else {
                    error.message?.takeIf { it.length < 160 } ?: "操作失败，请重新连接"
                }
            wireJson.encodeToString(
                result().copy(
                    ok = false,
                    error = safe,
                    fatal = commandIsHandshake(json),
                ),
            )
        }
    }

    private fun commandIsHandshake(json: String): Boolean =
        runCatching {
            val command = wireJson.decodeFromString<HarmonyCommand>(json)
            command.op == "frame" && GatewayWireDecoder.decode(command.frame).kind in setOf("paired", "hello")
        }.getOrDefault(false)

    private fun execute(command: HarmonyCommand) {
        when (command.op) {
            "prepare" -> {
                if (command.keepState) {
                    require(command.input.isBlank() && command.pairingCode.isBlank()) { "恢复连接不能更换配对身份" }
                    require(
                        if (expectedGatewayId != null) {
                            expectedGatewayId.equals(command.expectedGatewayId, true)
                        } else {
                            command.expectedGatewayId == null && preparedEndpoint == command.endpoint
                        },
                    ) { "恢复的主机身份不一致" }
                }
                disconnect()
                if (!command.keepState) {
                    sessions = SessionListState()
                    sessionsConfirmed = false
                    recentHistorySessions.clear()
                    historyMemoryBytes = 0
                    catalogRevision = 0
                    workspacesConfirmed = false
                    catalogRestored = false
                    workspaces = emptyList()
                    interactions.reset()
                    trajectorySupport.reset()
                    goalSupport.reset()
                    controlSupport.reset()
                    sessionFeatures.reset()
                    slashSupport.reset()
                    pluginSupport.reset()
                    scheduleSupport.reset()
                    teamSupport.reset()
                    uploadSupport.reset()
                    attachmentSupport.reset()
                    workspaceSupport.reset()
                    schedulesResult = null
                    pluginsResult = null
                    controlsResult = null
                    featuresResult = null
                    more.clear()
                    cursors.clear()
                    directory = HarmonyDirectory()
                    searchQuery = ""
                    searchSessionIds = emptyList()
                }
                if (command.input.isNotBlank()) {
                    val payload = GatewayPairingPayloadParser.parse(command.input, command.nowMillis)
                    pairing =
                        HarmonyPairing(
                            payload.publicUrl,
                            payload.pairingCode,
                            payload.gatewayId,
                            GatewayIdentity.endpoints(payload),
                            payload.gatewayName ?: GatewayIdentity.host(payload.publicUrl),
                        )
                    expectedGatewayId = payload.gatewayId
                } else {
                    GatewayIdentity.validateEndpoint(command.endpoint)
                    require(command.pairingCode.none { it.isWhitespace() || it.isISOControl() || it == ',' }) {
                        "一次性配对码格式无效"
                    }
                    expectedGatewayId = command.expectedGatewayId
                    pairing =
                        HarmonyPairing(
                            command.endpoint,
                            command.pairingCode,
                            expectedGatewayId,
                            gatewayName = GatewayIdentity.host(command.endpoint),
                        )
                }
                preparedEndpoint = pairing?.endpoint
            }

            "goal-refresh", "goal-edit", "goal-pause", "goal-resume", "goal-clear" -> {
                try {
                    applyGoals(goalSupport.command(command))
                } catch (failure: IllegalArgumentException) {
                    applyGoals(
                        goalSupport.rejected(failure.message ?: "目标操作未完成"),
                    )
                    throw failure
                }
            }

            "controls-open", "controls-defaults", "controls-model", "controls-permission", "controls-save-model", "controls-default" -> {
                if (command.sessionId != null) require(command.sessionId == sessions.selectedSessionId) { "会话已切换" }
                applyControls(controlSupport.command(command, connected()))
            }

            "cache-export" -> {
                val catalog =
                    if (connected() &&
                        sessionsConfirmed &&
                        workspacesConfirmed &&
                        catalogKey == null
                    ) {
                        CacheSupport.catalog(sessions, workspaces, command.workspaceId, teamSupport.routing(), teamSupport.avatars())
                    } else {
                        null
                    }
                val id = command.sessionId ?: sessions.selectedSessionId
                val json =
                    id
                        ?.takeIf {
                            !sessions.sessions.any { row -> row.id == it && row.isRunning } &&
                                (it != sessions.selectedSessionId || stream.activeAttemptId() == null)
                        }?.let {
                            history.cachedWindowJsonWithinBudget(
                                it,
                                stream.formatVersion(it),
                                stream.persistentCursorFor(it),
                                2L * 1024 * 1024,
                                400,
                            )
                        }
                cacheResult = HarmonyCacheExport(catalog, json?.let { listOf(CacheSupport.window(it)) }.orEmpty())
            }

            "cache-import-catalog" -> {
                if (!sessionsConfirmed && !workspacesConfirmed && !catalogRestored && !connected()) {
                    val cached = CacheSupport.restore(command.input)
                    sessions = cached.sessions
                    workspaces = cached.workspaces
                    catalogRestored = true
                    teamSupport.restore(cached.targets, cached.avatars)
                    select(sessions.selectedSessionId, preserve = true)
                }
            }

            "cache-import-window" -> {
                val id = requireNotNull(command.sessionId)
                require(id == sessions.selectedSessionId && command.input.length <= 3 * 1024 * 1024) { "离线会话已变化或缓存过大" }
                val window = wireJson.decodeFromString<CachedHistoryWindow>(command.input)
                window.validate(id)
                val cached = history.canPrependCachedPage(id)
                check(history.hydrateTypedCachedWindow(id, window).accepted) { "离线历史格式不可用" }
                if (history.canPrependCachedPage(id)) stream.rememberCachedFormat(id, window.historyFormatVersion)
                if (!cached && history.canPrependCachedPage(id)) {
                    more[id] = window.hasMore
                    cursors[id] = window.nextBeforeSequence
                }
            }

            "files-open", "files-list", "files-resolve", "files-reference", "files-cancel", "files-close", "files-write-complete" -> {
                applyWorkspace(
                    workspaceSupport.command(
                        command,
                        sessions.sessions
                            .firstOrNull {
                                it.id ==
                                    sessions.selectedSessionId
                            }?.cwd,
                    ),
                )
            }

            "attachment-open" -> {
                val id = requireNotNull(sessions.selectedSessionId)
                require(connected() && command.sessionId == id && !teamSupport.isChild(id)) { "当前会话暂不支持图片读取" }
                applyAttachment(attachmentSupport.open(id, requireNotNull(command.itemId), conversation.projectedItems(id)))
            }

            "image-add", "image-remove", "file-add", "file-retry", "file-remove", "file-chunk", "file-read-failed" -> {
                if (command.op in
                    setOf("image-add", "image-remove", "file-add", "file-retry", "file-remove")
                ) {
                    require(pendingMessage == null) { "请等待消息确认后再修改附件" }
                }
                applyUploads(uploadSupport.command(command))
            }

            "team-open", "team-refresh" -> {
                applyTeam(
                    teamSupport.open(
                        requireNotNull(command.sessionId).also {
                            require(it == sessions.selectedSessionId) { "会话已切换" }
                        },
                        command.op == "team-refresh",
                    ),
                )
            }

            "team-child-open" -> {
                val id = requireNotNull(command.sessionId)
                require(teamSupport.child(id)) { "该成员会话当前不可打开" }
                select(id, preserve = conversation.hasProjection(id))
            }

            "schedules-open", "schedule-edit-open", "schedule-save", "schedule-delete" -> {
                applySchedules(scheduleSupport.command(command, connected()))
            }

            "plugins-open", "plugins-toggle" -> {
                applyPlugins(pluginSupport.command(command, connected()))
            }

            "slash-input", "slash-pick", "slash-option", "slash-dismiss" -> {
                require(command.sessionId == sessions.selectedSessionId) { "会话已切换" }
                applySlash(slashSupport.command(command, connected()), command)
            }

            "agent-select", "agent-refresh", "queue-action" -> {
                val id = requireNotNull(command.sessionId)
                require(id == sessions.selectedSessionId) { "会话已切换" }
                applyFeatures(
                    when (command.op) {
                        "agent-select" -> sessionFeatures.selectAgent(id, command.value, connected())
                        "queue-action" -> sessionFeatures.queueAction(id, requireNotNull(command.itemId), command.value, connected())
                        else -> sessionFeatures.open(id, capabilities, connected())
                    },
                )
            }

            "trajectory", "trajectory-detail" -> {
                val id = requireNotNull(command.sessionId)
                require(id == sessions.selectedSessionId) { "会话已切换" }
                trajectoryResult = trajectorySupport.read(id, stream.transientTrajectoryNodes(id), command.itemId)
            }

            "validate-profile" -> {
                val profile = requireNotNull(command.profile)
                require(profile.localId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "主机标识无效" }
                require(profile.gatewayName.trim().length in 1..128 && profile.alias.orEmpty().length <= 128) { "主机名称长度无效" }
                require((profile.gatewayName + profile.alias.orEmpty()).none { it.isISOControl() }) { "主机名称包含无效字符" }
                require(profile.endpoints.size in 1..16) { "主机需要 1～16 个地址" }
                profile.endpoints.forEach(GatewayIdentity::validateEndpoint)
                GatewayIdentity.validate(profile.gatewayId, profile.gatewayId)
                require(profile.preferredEndpoint == null || profile.preferredEndpoint in profile.endpoints) { "首选地址不在候选列表中" }
                normalizedProfile =
                    profile.copy(
                        gatewayName = profile.gatewayName.trim(),
                        alias = profile.alias?.trim()?.takeIf(String::isNotBlank),
                        endpoints = profile.endpoints.distinct(),
                        remoteDeviceId = null,
                    )
            }

            "rename" -> {
                require(connected()) { "请先连接主机" }
                require(command.title.trim().length in 1..200) { "名称长度应为 1～200 个字符" }
                val id = requireNotNull(command.sessionId)
                beginCatalog("session-rename", id)
                send(GatewayRequests.renameSession(id, command.title))
            }

            "archive" -> {
                require(connected()) { "请先连接主机" }
                val id = requireNotNull(command.sessionId)
                beginCatalog("session-archive", id)
                send(GatewayRequests.archiveSession(id))
            }

            "directories" -> {
                require(connected()) { "请先连接主机" }
                beginCatalog("directories", command.path)
                send(GatewayRequests.directories(command.path))
            }

            "workspace-create" -> {
                require(connected()) { "请先连接主机" }
                val path = requireNotNull(command.path).trim()
                require(path.isNotBlank() && path.length <= 4096 && path.none { it.isISOControl() }) { "目录无效" }
                beginCatalog("workspace-create", path)
                send(GatewayRequests.createWorkspace(path))
            }

            "search" -> {
                searchQuery = command.query.trim().take(500)
                searchSessionIds = emptyList()
                if (connected()) pumpSearch()
            }

            "disconnect" -> {
                disconnect()
            }

            "release-history" -> {
                require(!connected()) { "已连接主机的阅读历史不能被跨主机回收" }
                history.inactiveSessionsOutside(emptyList()).forEach { id ->
                    history.clearSession(id)
                    conversation.clearSession(id)
                    trajectorySupport.remove(id)
                }
                recentHistorySessions.clear()
                stream.retainSessions(emptyList())
                more.clear()
                cursors.clear()
                historyMemoryBytes = 0
            }

            "resume" -> {
                require(connected()) { "请先连接主机" }
                refreshCatalog()
                sessions.selectedSessionId?.let { select(it, preserve = true) }
            }

            "question-answer", "question-cancel" -> {
                interactions
                    .submitQuestion(
                        command.rpcId,
                        command.sessionId,
                        command.answers,
                        command.op == "question-cancel",
                        connected(),
                    )?.let(::send)
            }

            "approval-decision" -> {
                interactions
                    .submitApproval(
                        command.rpcId,
                        command.sessionId,
                        command.outcome,
                        connected(),
                    )?.let(::send)
            }

            "request-failed" -> {
                interactions.fail(command.requestKey, "请求失败或超时，结果尚未确认，请核对后重试")
            }

            "operation-timeout" -> {
                goalSupport.timedOut(command.requestKey)?.let {
                    applyGoals(it)
                    return
                }
                if (command.requestKey.startsWith("workspace-")) {
                    applyWorkspace(workspaceSupport.timedOut(command.requestKey))
                    return
                }

                if (command.requestKey.startsWith("attachment:")) {
                    applyAttachment(attachmentSupport.timedOut(command.requestKey))
                    return
                }

                if (command.requestKey.startsWith("uploads:")) {
                    applyUploads(uploadSupport.timedOut(command.requestKey))
                    return
                }

                if (command.requestKey.startsWith("team:")) {
                    applyTeam(teamSupport.timedOut(command.requestKey))
                    return
                }

                if (command.requestKey.startsWith("schedules:")) {
                    applySchedules(scheduleSupport.timedOut(command.requestKey))
                    return
                }

                if (command.requestKey.startsWith("plugins:")) {
                    applyPlugins(pluginSupport.timedOut(command.requestKey))
                    return
                }

                if (command.requestKey.startsWith("features:slash")) {
                    val session = slashSupport.executingSession()
                    applySlash(slashSupport.timedOut(command.requestKey))
                    if (session != null) applyFeatures(sessionFeatures.messageFailed(session))
                    return
                }

                if (command.requestKey.startsWith("features:")) {
                    applyFeatures(sessionFeatures.timedOut(command.requestKey))
                    return
                }

                if (command.requestKey.startsWith("controls:")) {
                    controlSupport.timedOut(command.requestKey)?.let(::applyControls)
                    return
                }

                if (command.requestKey == "create:$pendingCreate") pendingCreate = null
                if (command.requestKey == "message:$pendingMessage") {
                    applyFeatures(sessionFeatures.messageFailed(pendingMessageSessionId))
                    pendingMessage = null
                    pendingMessageSessionId = null
                }
                historyOperations.entries.firstOrNull { it.value == command.requestKey }?.key?.let {
                    history.timedOut(it)
                    loading -= it
                    loadingOlder -= it
                    historyOperations.remove(it)
                }
                catalogRequests.entries
                    .firstOrNull { it.value.key == command.requestKey }
                    ?.key
                    ?.let(catalogRequests::remove)
                if (catalogKey == command.requestKey) {
                    catalogKey = null
                    catalogRemaining.clear()
                }
                if (pendingSearch?.second == command.requestKey) {
                    val timedOutQuery = pendingSearch?.first
                    pendingSearch = null
                    pumpSearch(completedQuery = timedOutQuery)
                }
                error("请求超时，结果尚未确认；请检查会话后再操作")
            }

            "frame" -> {
                accept(command)
            }

            "select" -> {
                require(connected() || command.sessionId == null || sessions.sessions.any { it.id == command.sessionId }) { "此会话不在离线目录中" }
                select(
                    command.sessionId,
                    preserve =
                        !connected() || (command.keepState && command.sessionId?.let(conversation::hasProjection) == true),
                )
            }

            "refresh" -> {
                require(connected()) { "请先连接主机" }
                refreshCatalog()
                pumpSearch()
            }

            "create" -> {
                require(connected()) { "请先连接主机" }
                check(pendingCreate == null) { "正在创建会话" }
                val id = nextRequestId()
                pendingCreate = id
                send(GatewayRequests.createSession(id, command.workspaceId))
            }

            "send" -> {
                require(teamSupport.canSend(sessions.selectedSessionId)) { "此成员会话仅支持阅读" }
                if (teamSupport.isChild(sessions.selectedSessionId)) {
                    sendMessage(command)
                    return
                }
                val input = command.copy(op = "slash-input", sessionId = sessions.selectedSessionId)
                applySlash(slashSupport.command(input, connected()), input)
                slashSupport.requireCanSend(command.text)
                val execution = slashSupport.executionFor(command.text)
                if (execution != null) {
                    require(!uploadSupport.hasFiles()) { "执行命令前请先移除附件" }
                    executeSlash(requireNotNull(sessions.selectedSessionId), execution)
                } else {
                    sendMessage(command)
                }
            }

            "older" -> {
                val id = requireNotNull(sessions.selectedSessionId)
                require(connected() && more[id] == true && id !in loading) { "没有可加载的历史记录" }
                val cursor = requireNotNull(cursors[id]) { "历史分页游标缺失" }
                val format = requireNotNull(stream.formatVersion(id)) { "历史格式未知，请重新连接" }
                loading += id
                historyOperations[id] = "history:${nextRequestId()}"
                loadingOlder += id
                history.start(id, true, true, cursor)
                send(
                    GatewayRequests.history(
                        id,
                        cursor,
                        maxMessages = 40,
                        maxBytes = 512_000,
                        historyFormatVersion = format,
                    ),
                )
            }

            "cancel" -> {
                require(teamSupport.canSend(sessions.selectedSessionId)) { "此成员会话仅支持阅读" }
                require(connected()) { "请先连接主机" }
                send(GatewayRequests.sessionCancel(requireNotNull(sessions.selectedSessionId)))
            }

            "timeout" -> {
                pendingCreate = null
                pendingMessage = null
                pendingMessageSessionId = null
                loading.toList().forEach { history.timedOut(it) }
                loading.clear()
                loadingOlder.clear()
                historyOperations.clear()
                error("请求超时，结果尚未确认；请检查会话后再操作")
            }

            "state" -> {
                Unit
            }

            else -> {
                error("不支持的操作")
            }
        }
    }

    private fun accept(command: HarmonyCommand) {
        require(command.frame.length <= 12 * 1024 * 1024) { "网关数据超过大小限制" }
        val frame = GatewayWireDecoder.decode(command.frame)
        if (frame.kind in setOf("paired", "hello")) {
            GatewayIdentity.validate(expectedGatewayId, frame.gatewayId)
            expectedGatewayId = frame.gatewayId ?: expectedGatewayId
            if (frame.kind == "paired") {
                receivedToken = requireNotNull(frame.token?.takeIf(String::isNotBlank)) { "配对响应缺少凭据" }
                return
            }
            require(frame.authenticated != false) { "主机认证失败，请重新配对" }
            if (command.channel == "conversation") {
                require(controlReady && split) { "会话连接尚未初始化" }
                conversationReady = true
                sessions.selectedSessionId?.let { select(it, preserve = true) }
                return
            }
            interactions.reset()
            controlReady = true
            hostSnapshot = null
            send(GatewayRequests.simple("host"))
            capabilities = frame.capabilities.orEmpty()
            uploadSupport.connected()
            applyTeam(teamSupport.accept(command.frame, frame, true))
            slashSupport.configure(capabilities)
            applyFeatures(sessionFeatures.configure(capabilities))
            split = "split-channels" in frame.capabilities.orEmpty()
            usesStream = "assistant-stream-v1" in frame.capabilities.orEmpty()
            stream.accept(frame)
            openConversation = split
            refreshCatalog()
            pumpSearch()
            if (!split) sessions.selectedSessionId?.let { select(it, preserve = true) }
            return
        }
        require(controlReady) { "主机尚未完成握手" }
        if (frame.kind != "session-snapshot") goalSupport.accept(command.frame, frame)?.let(::applyGoals)
        if (frame.resetRequired != true) {
            workspaceSupport.accept(frame, command.frame)?.let {
                applyWorkspace(it)
                return
            }
        }
        if (frame.resetRequired != true) {
            attachmentSupport.accept(frame)?.let {
                applyAttachment(it)
                return
            }
        }
        if (frame.kind == "attachment" ||
            (frame.kind == "error" && frame.resetRequired != true && frame.requestType == "attachment")
        ) {
            return
        }
        uploadSupport.accept(frame)?.let(::applyUploads)
        if (frame.kind == "error" && frame.resetRequired != true && frame.requestType?.startsWith("file-upload-") == true) return
        applyTeam(teamSupport.accept(command.frame, frame))
        if (frame.requestId?.startsWith("team-profile-") == true && frame.resetRequired != true) return
        if (frame.kind == "error" && frame.resetRequired != true && frame.requestType in setOf("agent-team", "subagents")) return
        applyFeatures(sessionFeatures.accept(frame, command.frame, command.channel == "control"))
        if (command.channel == "control") pluginSupport.accept(frame, connected())?.let(::applyPlugins)
        if (command.channel == "control") scheduleSupport.accept(frame, connected())?.let(::applySchedules)
        if (command.channel == "control") applySlash(slashSupport.accept(frame, command.frame))
        if (command.channel == "control") {
            controlSupport.accept(frame)?.let {
                applyControls(it)
                return
            }
        }
        if (frame.kind == "error" &&
            frame.resetRequired != true &&
            frame.requestType in
            setOf(
                "session-agent-preset",
                "select-agent-preset",
                "queue-update",
                "commands",
                "command-options",
                "command-select",
                "command-execute",
            )
        ) {
            return
        }
        if (frame.kind == "error" &&
            frame.resetRequired != true &&
            frame.requestType in setOf("plugin-catalog", "plugin-bundle-set-enabled", "plugin-entry-set-enabled")
        ) {
            return
        }
        if (frame.kind == "error" &&
            frame.resetRequired != true &&
            frame.requestType in setOf("schedule-catalog", "schedule-update", "schedule-delete")
        ) {
            return
        }
        when (frame.kind) {
            "host" -> {
                if (command.channel == "control") {
                    hostSnapshot =
                        GatewayHostSnapshot(
                            version = frame.version,
                            dshVersion = frame.dshVersion,
                            cwd = frame.cwd,
                            provider = frame.provider,
                            model = frame.model,
                            attachedSessions = frame.attachedSessions,
                            canOpenPath = frame.canOpenPath,
                        )
                }
            }
            "sessions" -> {
                val incoming =
                    requireNotNull(frame.items).map {
                        wireJson.decodeFromString<GatewaySessionSummary>(it.toJsonElement().toString())
                    }
                require(incoming.all { it.sessionId.isNotBlank() }) { "会话列表无效" }
                sessions = SessionListReducer.reduce(sessions, SessionListAction.RemoteSessionsReceived(incoming))
                sessionsConfirmed = true
                catalogRevision++
                finishCatalog("sessions")
            }

            "workspaces" -> {
                workspaces =
                    requireNotNull(frame.items).map {
                        wireJson.decodeFromString<GatewayWorkspace>(it.toJsonElement().toString())
                    }
                workspacesConfirmed = true
                catalogRevision++
                finishCatalog("workspaces")
            }

            "session-archives", "session-archived" -> {
                catalogRevision++
                frame.archivedSessionIds?.let {
                    if (frame.kind == "session-archived" && catalogRequests["session-archive"]?.target == frame.sessionId) {
                        catalogRequests.remove("session-archive")
                    }
                    sessions = SessionListReducer.reduce(sessions, SessionListAction.SetArchivedSessionIds(it.toSet()))
                    if (sessions.selectedSessionId != null && sessions.selectedSessionId in it) select(null)
                }
            }

            "session-renamed", "session-title-changed" -> {
                catalogRevision++
                val id = requireNotNull(frame.sessionId)
                if (frame.kind == "session-renamed" && catalogRequests["session-rename"]?.target == id) {
                    catalogRequests.remove("session-rename")
                }
                if (frame.title != null && frame.seq != null) {
                    sessions =
                        SessionListReducer.reduce(
                            sessions,
                            SessionListAction.EventReceived(
                                SessionEvent(
                                    id,
                                    frame.seq,
                                    frame.time ?: command.nowMillis / 1000.0,
                                    GatewayEvent(type = "session/title", text = frame.title),
                                ),
                                command.nowMillis / 1000.0,
                            ),
                        )
                }
            }

            "directories" -> {
                if (catalogRequests.containsKey("directories")) {
                    directory =
                        HarmonyDirectory(frame.path.orEmpty(), frame.home.orEmpty(), frame.crumbs.orEmpty(), frame.entries.orEmpty())
                    catalogRequests.remove("directories")
                }
            }

            "workspace-create", "workspace-created" -> {
                if (catalogRequests.containsKey("workspace-create")) {
                    catalogRequests.remove("workspace-create")
                    frame.workspace?.let { workspace ->
                        workspaces =
                            workspaces.filterNot { it.workspaceId == workspace.workspaceId } + workspace
                    }
                    send(GatewayRequests.simple("workspaces"))
                }
            }

            "search" -> {
                if (pendingSearch?.first == frame.query) {
                    if (searchQuery == frame.query) {
                        searchSessionIds =
                            frame.items
                                .orEmpty()
                                .map {
                                    wireJson.decodeFromString<GatewaySearchItem>(it.toJsonElement().toString()).sessionId
                                }.distinct()
                    }
                    pendingSearch = null
                    pumpSearch(completedQuery = frame.query)
                }
            }

            "session-created" -> {
                if (pendingCreate != null && frame.requestId == pendingCreate) {
                    pendingCreate = null
                    val id = requireNotNull(frame.sessionId)
                    sessions = SessionListReducer.reduce(sessions, SessionListAction.KnownSessionAdded(id, command.nowMillis / 1000.0))
                    select(id)
                }
            }

            "sent" -> {
                if (pendingMessage != null &&
                    frame.sessionId == pendingMessageSessionId &&
                    (frame.requestId == pendingMessage || (frame.requestId == null && !uploadSupport.requiresNonce()))
                ) {
                    catalogRevision++
                    sentSessionId = pendingMessageSessionId
                    applyUploads(uploadSupport.accepted(pendingMessageSessionId))
                    pendingMessage = null
                    pendingMessageSessionId = null
                    sent = true
                    frame.sessionId?.let {
                        sessions =
                            SessionListReducer.reduce(
                                sessions,
                                SessionListAction.MessageSent(it, null, command.nowMillis / 1000.0),
                            )
                    }
                }
            }

            "error" -> {
                interactions.accept(frame)
                frame.requestType?.let { type ->
                    val request = catalogRequests[type]
                    if (request != null && (request.target == null || frame.sessionId == null || request.target == frame.sessionId)) {
                        catalogRequests.remove(type)
                    }
                }
                if (frame.code == "session/attachment-invalid" &&
                    frame.requestId == pendingMessage
                ) {
                    applyUploads(uploadSupport.invalidReceipts())
                }
                when (frame.requestType) {
                    "session-create" -> {
                        if (frame.requestId == null || frame.requestId == pendingCreate) pendingCreate = null
                    }

                    "message" -> {
                        if ((frame.sessionId == null || frame.sessionId == pendingMessageSessionId) &&
                            (frame.requestId == pendingMessage || (frame.requestId == null && !uploadSupport.requiresNonce()))
                        ) {
                            pendingMessage = null
                            pendingMessageSessionId = null
                        }
                    }

                    "history", "subscribe" -> {
                        frame.sessionId?.let {
                            history.cancelled(it)
                            loading -= it
                            loadingOlder -= it
                            historyOperations.remove(it)
                        }
                    }
                }
                if (frame.resetRequired != true) error(frame.message?.take(120) ?: "网关拒绝了请求")
            }
        }
        if (frame.kind != "error") interactions.accept(frame)
        if (frame.kind == "history" && frame.sessionId !in loading) return
        if (frame.kind == "history" &&
            frame.sessionId != null &&
            usesStream &&
            stream.hasBaseline(frame.sessionId) &&
            frame.sessionId !in loadingOlder
        ) {
            return
        }
        val update = stream.accept(frame)
        update.invalidatedSessionIds.forEach {
            history.clearSession(it)
            conversation.clearSession(it)
        }
        if (update.clearTransient) update.sessionId?.let { conversation.clearAssistantChunks(it) }
        if (update.resubscribe) update.sessionId?.let(::select)
        if (!update.accepted) return
        if (frame.kind in setOf("question-requested", "approval-requested", "event")) {
            notificationStore
                .accept(
                    frame,
                    interactions
                        .questionRows()
                        .filter { it.sessionId == frame.sessionId }
                        .map { it.rpcId }
                        .toSet(),
                    interactions
                        .approvalRows()
                        .filter { it.sessionId == frame.sessionId }
                        .map { it.rpcId }
                        .toSet(),
                )?.let(notifications::add)
        }
        if (frame.kind == "session-snapshot") goalSupport.accept(command.frame, frame)?.let(::applyGoals)
        if (frame.kind in setOf("session-snapshot", "history", "event")) historyRevision++
        val id = frame.sessionId ?: sessions.selectedSessionId
        when (frame.kind) {
            "session-snapshot" -> {
                if (id != null) {
                    val records = frame.events.orEmpty().map { it.normalized(id) }
                    records.forEach(interactions::capture)
                    history.installTypedSnapshot(id, records, frame.hasMore == true, frame.nextBeforeSeq)
                    more[id] = frame.hasMore == true
                    cursors[id] = frame.nextBeforeSeq
                    loading -= id
                    loadingOlder -= id
                    historyOperations.remove(id)
                }
            }

            "history" -> {
                if (id != null) {
                    if (id !in loading) return
                    val records = frame.events.orEmpty().map { it.normalized(id) }
                    records.forEach(interactions::capture)
                    history.pageReceivedTyped(
                        id,
                        records,
                        frame.bytes ?: command.frame.length,
                        frame.hasMore == true,
                        frame.nextBeforeSeq,
                        null,
                    )
                    more[id] = frame.hasMore == true
                    cursors[id] = frame.nextBeforeSeq
                    loading -= id
                    loadingOlder -= id
                    historyOperations.remove(id)
                }
            }

            "event" -> {
                if (id != null && frame.seq != null && frame.time != null && frame.event != null) {
                    val record = SessionEvent(id, frame.seq, frame.time, frame.event)
                    interactions.capture(record)
                    sessions =
                        SessionListReducer.reduce(
                            sessions,
                            SessionListAction.EventReceived(record, command.nowMillis / 1000.0),
                        )
                    val accepted = history.liveTypedEventReceived(record)
                    check(accepted.accepted) { "实时事件更新失败" }
                }
            }
        }
        if (update.attemptId != null && id != null) {
            check(conversation.assistantChunks(id, update.attemptId, update.chunksJson).accepted) { "流式投影失败" }
        }
        update.error?.let { error(it) }
    }

    private fun beginCatalog(
        type: String,
        target: String?,
    ) {
        check(type !in catalogRequests) { "正在处理，请等待确认" }
        catalogRequests[type] = CatalogRequest("catalog:${nextRequestId()}", target)
    }

    private fun refreshCatalog() {
        if (catalogKey != null) return
        catalogKey = "catalog:${nextRequestId()}"
        catalogRemaining += setOf("sessions", "workspaces")
        send(GatewayRequests.simple("sessions"))
        send(GatewayRequests.simple("workspaces"))
    }

    private fun finishCatalog(type: String) {
        catalogRemaining -= type
        if (catalogRemaining.isEmpty()) catalogKey = null
    }

    private fun pumpSearch(completedQuery: String? = null) {
        if (pendingSearch != null || searchQuery.isBlank() || searchQuery == completedQuery) return
        pendingSearch = searchQuery to "search:${nextRequestId()}"
        send(GatewayRequests.search(searchQuery))
    }

    private fun sessionRow(row: com.clarklevis.dsh.shared.domain.SessionSummary) =
        HarmonySession(row.id, row.title, row.isRunning, row.cwd, row.lastActivityEpochSeconds, row.hasConversation != false, row.hasUnread)

    private fun select(
        id: String?,
        preserve: Boolean = false,
    ) {
        val online = connected()
        if (id != null) {
            recentHistorySessions.remove(id)
            recentHistorySessions.add(id)
        }
        sessions.selectedSessionId?.takeIf { it != id }?.let {
            history.cancelled(it)
            loading -= it
            loadingOlder -= it
            historyOperations.remove(it)
        }
        sessions = SessionListReducer.reduce(sessions, SessionListAction.Select(id))
        attachmentSupport.reset()
        applyWorkspace(
            workspaceSupport.bind(
                id,
                id?.let(teamSupport::workspaceSession),
                connected() && "file-downloads" in capabilities,
                "file-references-v1" in capabilities,
            ),
        )
        stream.selectSession(id)
        applyUploads(
            uploadSupport.select(
                id,
                connected() && "file-upload-v1" in capabilities && !teamSupport.isChild(id),
                connected() && !teamSupport.isChild(id),
            ),
        )
        applySlash(slashSupport.open(if (!online || teamSupport.isChild(id)) null else id))
        applyGoals(goalSupport.open(id, online, capabilities, teamSupport.isChild(id)))
        applyFeatures(sessionFeatures.open(if (!online || teamSupport.isChild(id)) null else id, capabilities, connected()))
        if (id != null) {
            if (online && !teamSupport.isChild(id)) applyTeam(teamSupport.open(id, false))
            sessions = SessionListReducer.reduce(sessions, SessionListAction.MarkRead(id))
            if (!preserve) {
                history.clearSession(id)
                conversation.clearSession(id)
                conversation.replaceTypedSession(id, emptyList())
            }
            if (!online) return
            loading += id
            historyOperations[id] = "history:${nextRequestId()}"
            if (usesStream) history.awaitSnapshot(id) else history.start(id, false, false, null)
        }
        if (!online) return
        send(GatewayRequests.subscribe(id))
        if (id != null && !usesStream) send(GatewayRequests.history(id, maxMessages = 40, maxBytes = 512_000))
    }

    /** 共享层决定估算和可释放性；当前页、父会话及未确认发送保持租约。 */
    private fun trimHistoryMemory() {
        val selected = sessions.selectedSessionId
        val protected =
            listOfNotNull(
                selected,
                selected?.takeIf(teamSupport::isChild)?.let(teamSupport::workspaceSession),
                pendingMessageSessionId,
                slashSupport.executingSession(),
            ).toSet()
        val keep = protected.toMutableSet()
        var bytes = keep.sumOf(history::estimatedRetainedBytes)
        recentHistorySessions.asReversed().forEach { id ->
            if (id !in keep) {
                val estimate = history.estimatedRetainedBytes(id)
                if (keep.size < 4 && bytes + estimate <= 48L * 1024 * 1024) {
                    keep += id
                    bytes += estimate
                }
            }
        }
        history.inactiveSessionsOutside(keep.toList()).forEach { id ->
            history.clearSession(id)
            conversation.clearSession(id)
            trajectorySupport.remove(id)
            more.remove(id)
            cursors.remove(id)
            historyOperations.remove(id)
        }
        recentHistorySessions.retainAll(keep)
        stream.retainSessions(keep.toList())
        historyMemoryBytes = bytes
    }

    private fun send(request: GatewayRequest) {
        val channel =
            if (split && request.requestType in setOf("message", "history", "subscribe", "unsubscribe")) {
                "conversation"
            } else {
                "control"
            }
        val key =
            if (request.requestType in setOf("question-answer", "question-cancel", "approval-response")) {
                request.correlationId?.let { InteractionStore.requestKey(request.requestType, it) }
            } else {
                null
            }
        if (request.requestType == "sessions") teamSupport.sessionsRequested()
        val payload = teamSupport.decorate(request.payload) ?: error("此成员会话不支持该操作")
        outbound += HarmonyOutbound(channel, payload, key)
    }

    private fun disconnect() {
        controlReady = false
        hostSnapshot = null
        conversationReady = false
        split = false
        stream.disconnect()
        interactions.disconnected()
        applyGoals(goalSupport.disconnected())
        applyControls(controlSupport.disconnected())
        applyFeatures(sessionFeatures.disconnected())
        applySlash(slashSupport.disconnected())
        applyPlugins(pluginSupport.disconnected())
        applySchedules(scheduleSupport.disconnected())
        applyTeam(teamSupport.disconnected())
        applyUploads(uploadSupport.disconnected())
        attachmentSupport.reset()
        applyWorkspace(workspaceSupport.bind(sessions.selectedSessionId, null, false, false))
        loading.toList().forEach { history.cancelled(it) }
        loading.clear()
        loadingOlder.clear()
        historyOperations.clear()
        pendingCreate = null
        pendingMessage = null
        pendingMessageSessionId = null
        catalogRequests.clear()
        pendingSearch = null
        catalogKey = null
        catalogRemaining.clear()
    }

    private fun sendMessage(command: HarmonyCommand) {
        if (!teamSupport.isChild(sessions.selectedSessionId)) slashSupport.requireCanSend(command.text)

        require(connected()) { "请先连接主机" }
        require((command.text.isNotBlank() || uploadSupport.ready()) && command.text.length <= 100_000) { "消息为空或超过长度限制" }
        check(pendingMessage == null) { "上一条消息尚未确认" }
        val id = requireNotNull(sessions.selectedSessionId) { "请先选择或创建会话" }
        require(command.mode == "queue" || (command.mode == "steer" && "queue-control" in capabilities)) { "主机未开放插话能力" }
        if (!teamSupport.isChild(id)) applyFeatures(sessionFeatures.beforeMessage(id))
        pendingMessage = uploadSupport.submission(command.text, nextRequestId())
        pendingMessageSessionId = id
        send(
            GatewayRequests.message(
                command.text,
                uploadSupport.images(),
                id,
                null,
                command.timeZone,
                mode = command.mode,
                receipts = uploadSupport.receipts(),
                requestId = pendingMessage,
            ),
        )
    }

    private fun executeSlash(
        sessionId: String,
        line: String,
    ) {
        require(line.isNotBlank() && line.length <= 100_000) { "命令为空或超过长度限制" }
        slashSupport.requireCanSend(line)
        require(connected() && pendingMessage == null) { "请等待当前消息确认" }
        applyFeatures(sessionFeatures.beforeMessage(sessionId))
        applySlash(slashSupport.beginExecution(sessionId, line))
    }

    private fun applySlash(
        change: SlashChange,
        command: HarmonyCommand? = null,
    ) {
        if (change.view != null) slashResult = change.view
        change.request?.let(::send)
        change.model?.let { model -> sessions.selectedSessionId?.let { applyControls(controlSupport.commandSelectedModel(it, model)) } }
        change.submit?.let { sendMessage(requireNotNull(command).copy(text = it)) }
        change.execution?.let { executeSlash(requireNotNull(sessions.selectedSessionId), it) }
        change.acknowledgedSession?.let {
            sent = true
            sentSessionId = it
        }
        if (change.mutation != null) draftMutation = change.mutation
    }

    private fun applyFeatures(change: FeatureChange) {
        if (change.invalidSession) {
            select(null)
            refreshCatalog()
            notice = "会话已不存在，请重新选择"
            return
        }
        if (change.refreshCommands) sessions.selectedSessionId?.let { applySlash(slashSupport.invalidate(it)) }
        if (change.view != null) featuresResult = change.view
        restoredDrafts.putAll(change.restoredDrafts)
        change.requests.forEach(::send)
    }

    private fun applyWorkspace(change: WorkspaceChange) {
        if (change.view != null) workspaceResult = change.view
        if (change.io != null) fileIo = change.io
        if (change.discard != null) fileDiscard = change.discard
        if (change.completion != null) fileCompletion = change.completion
        change.requests.forEach(::send)
    }

    private fun applyAttachment(change: AttachmentChange) {
        if (change.view != null) attachmentResult = change.view
        change.request?.let(::send)
    }

    private fun applyUploads(change: UploadChange) {
        if (change.view != null) uploadsResult = change.view
        if (change.read != null) uploadRead = change.read
        change.requests.forEach(::send)
    }

    private fun applyTeam(change: TeamChange) {
        if (change.view != null) teamResult = change.view
        change.requests.forEach(::send)
    }

    private fun applySchedules(change: ScheduleChange) {
        if (change.view != null) schedulesResult = change.view
        change.requests.forEach(::send)
    }

    private fun applyPlugins(change: PluginChange) {
        if (change.view != null) pluginsResult = change.view
        change.requests.forEach(::send)
    }

    private fun applyGoals(change: GoalChange) {
        goalTasksResult = change.view
        change.requests.forEach(::send)
    }

    private fun applyControls(change: ControlChange) {
        if (change.view != null) controlsResult = change.view
        change.requests.forEach(::send)
    }

    private fun nextRequestId(): String = "harmony-$requestNamespace-${++requestSequence}"

    private fun connected(): Boolean = controlReady && (!split || conversationReady)

    private fun result() =
        HarmonyResult(
            state =
                HarmonyState(
                    connected = connected(),
                    hostSnapshot = hostSnapshot,
                    pendingRequest =
                        pendingCreate != null ||
                            pendingMessage != null ||
                            slashSupport.executingSession() != null ||
                            sessions.selectedSessionId in loading,
                    pendingMessageSessionId = pendingMessageSessionId ?: slashSupport.executingSession(),
                    pendingOperations =
                        listOfNotNull(
                            pendingCreate?.let { "create:$it" },
                            pendingMessage?.let { "message:$it" },
                            pendingSearch?.second,
                            catalogKey,
                        ) + historyOperations.values + catalogRequests.values.map { it.key } + controlSupport.pendingKeys() +
                            sessionFeatures.pendingKeys() +
                            slashSupport.pendingKeys() + pluginSupport.pendingKeys() + scheduleSupport.pendingKeys() +
                            goalSupport.pendingKeys() + teamSupport.pendingKeys() +
                            uploadSupport.pendingKeys() +
                            attachmentSupport.pendingKeys() +
                            workspaceSupport.pendingKeys(),
                    questions = interactions.questionRows(),
                    approvals = interactions.approvalRows(),
                    sessions = sessions.sessions.filterNot { it.id in sessions.archivedSessionIds || it.isSubagent }.map(::sessionRow),
                    archivedSessions =
                        sessions.sessions.filter { it.id in sessions.archivedSessionIds && !it.isSubagent }.map(
                            ::sessionRow,
                        ),
                    workspaces = workspaces,
                    selectedSessionId = sessions.selectedSessionId,
                    hasMore = more[sessions.selectedSessionId] == true,
                    loadingHistory = sessions.selectedSessionId in loading,
                    pendingQuestionCount = interactions.questionRows().size,
                    pendingApprovalCount = interactions.approvalRows().size,
                    capabilities = capabilities,
                    directory = directory,
                    directoryLoading = "directories" in catalogRequests,
                    catalogBusy = catalogRequests.keys.toList(),
                    searchQuery = searchQuery,
                    searchSessionIds = searchSessionIds,
                    searching = pendingSearch != null && searchQuery.isNotBlank(),
                    catalogLoading = catalogKey != null,
                    historyRevision = historyRevision,
                    childConversation = teamSupport.isChild(sessions.selectedSessionId),
                    canSend = teamSupport.canSend(sessions.selectedSessionId),
                    operationTimeouts = uploadSupport.timeouts(),
                    retainedHistorySessions = recentHistorySessions.size,
                    historyMemoryBytes = historyMemoryBytes,
                    cachedHistory = sessions.selectedSessionId?.let(history::canPrependCachedPage) == true,
                    catalogRevision = catalogRevision,
                    generating =
                        stream.activeAttemptId() != null || sessions.sessions.any { it.id == sessions.selectedSessionId && it.isRunning },
                ),
            patches = patches.toList(),
            outbound = outbound.toList(),
            pairing = pairing,
            token = receivedToken,
            error = notice,
            gatewayId = expectedGatewayId,
            openConversation = openConversation,
            sent = sent,
            sentSessionId = sentSessionId,
            profile = normalizedProfile,
            trajectory = trajectoryResult,
            controls = controlsResult,
            features = featuresResult,
            slash = slashResult,
            plugins = pluginsResult,
            schedules = schedulesResult,
            team = teamResult,
            uploads = uploadsResult,
            uploadRead = uploadRead,
            attachment = attachmentResult,
            workspaceFiles = workspaceResult,
            fileIo = fileIo,
            fileDiscard = fileDiscard,
            fileCompletion = fileCompletion,
            goalTasks = goalTasksResult,
            notifications = notifications.toList(),
            cache = cacheResult,
            draftMutation = draftMutation,
            restoredDrafts = restoredDrafts.toMap(),
        )
}
