package com.clarklevis.dsh.shared.facade

import com.clarklevis.dsh.shared.SharedModuleInfo
import com.clarklevis.dsh.shared.domain.QuestionAction
import com.clarklevis.dsh.shared.domain.QuestionReducer
import com.clarklevis.dsh.shared.domain.QuestionState
import com.clarklevis.dsh.shared.domain.SessionListAction
import com.clarklevis.dsh.shared.domain.SessionListReducer
import com.clarklevis.dsh.shared.domain.SessionListState
import com.clarklevis.dsh.shared.domain.SessionSummary
import com.clarklevis.dsh.shared.domain.normalizeEpochSeconds
import com.clarklevis.dsh.shared.projection.ConversationItem
import com.clarklevis.dsh.shared.projection.ConversationProjectionLabels
import com.clarklevis.dsh.shared.projection.ConversationProjector
import com.clarklevis.dsh.shared.protocol.GatewayPendingQuestionRequest
import com.clarklevis.dsh.shared.protocol.GatewayAgentPreset
import com.clarklevis.dsh.shared.protocol.GatewayContextSnapshot
import com.clarklevis.dsh.shared.protocol.GatewayHostSnapshot
import com.clarklevis.dsh.shared.protocol.GatewayModelCatalog
import com.clarklevis.dsh.shared.protocol.GatewayModelSelection
import com.clarklevis.dsh.shared.protocol.GatewayQuestionAction
import com.clarklevis.dsh.shared.protocol.GatewaySessionPermissions
import com.clarklevis.dsh.shared.protocol.GatewaySessionStatsSnapshot
import com.clarklevis.dsh.shared.protocol.GatewaySessionSummary
import com.clarklevis.dsh.shared.protocol.GatewaySearchItem
import com.clarklevis.dsh.shared.protocol.GatewayWorkspace
import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder
import com.clarklevis.dsh.shared.protocol.SessionEvent
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.time.Clock

data class SharedMobileSnapshot(
    val sessions: List<SessionSummary> = emptyList(),
    val workspaces: List<GatewayWorkspace> = emptyList(),
    val searchResultSessionIds: List<String> = emptyList(),
    val selectedSessionId: String? = null,
    val conversation: List<ConversationItem> = emptyList(),
    val pendingQuestions: List<GatewayPendingQuestionRequest> = emptyList(),
    val pendingQuestionCount: Int = 0,
    val agentPresets: List<GatewayAgentPreset> = emptyList(),
    val agentPresetDefault: String? = null,
    val permissionDefault: String? = null,
    val defaultModel: GatewayModelSelection? = null,
    val modelCatalog: GatewayModelCatalog? = null,
    val permissions: GatewaySessionPermissions? = null,
    val contextSnapshot: GatewayContextSnapshot? = null,
    val statsSnapshot: GatewaySessionStatsSnapshot? = null,
    val hostSnapshot: GatewayHostSnapshot? = null,
    val selectedHistoryHasMore: Boolean = false,
    val selectedHistoryEarliestSequence: Int? = null,
    val lastFrameKind: String? = null,
    val lastError: String? = null
)

/**
 * Android 与 SwiftUI 的粗粒度共享业务入口。
 *
 * 平台层负责网络、持久化与线程切换；调用方必须在自己的串行 UI/store
 * 上下文中提交 intent。该类只做 wire decode、Reducer 和纯投影。
 */
class SharedMobileStore(
    private val nowEpochSeconds: () -> Double = {
        Clock.System.now().toEpochMilliseconds().toDouble() / 1_000.0
    }
) {
    private var sessionListState = SessionListState()
    private var questionState = QuestionState()
    private val eventsBySession = mutableMapOf<String, List<SessionEvent>>()
    private var workspaces = emptyList<GatewayWorkspace>()
    private var searchResultSessionIds = emptyList<String>()
    private var agentPresets = emptyList<GatewayAgentPreset>()
    private var agentPresetDefault: String? = null
    private var permissionDefault: String? = null
    private var defaultModel: GatewayModelSelection? = null
    private var modelCatalog: GatewayModelCatalog? = null
    private var permissions: GatewaySessionPermissions? = null
    private var contextSnapshot: GatewayContextSnapshot? = null
    private var statsSnapshot: GatewaySessionStatsSnapshot? = null
    private var hostSnapshot: GatewayHostSnapshot? = null
    private var lastFrameKind: String? = null
    private var lastError: String? = null

    fun snapshot(): SharedMobileSnapshot = makeSnapshot()

    fun selectSession(sessionId: String?): SharedMobileSnapshot {
        sessionListState = SessionListReducer.reduce(sessionListState, SessionListAction.Select(sessionId))
        sessionId?.let {
            sessionListState = SessionListReducer.reduce(sessionListState, SessionListAction.MarkRead(it))
        }
        return makeSnapshot()
    }

    fun acceptFrame(json: String): SharedMobileSnapshot {
        try {
            val frame = GatewayWireDecoder.decode(json)
            lastFrameKind = frame.kind
            lastError = null
            when (frame.kind) {
                "sessions" -> {
                    val sessions = frame.items.orEmpty().mapNotNull { item ->
                        runCatching {
                            wireJson.decodeFromJsonElement(GatewaySessionSummary.serializer(), item.toJsonElement())
                        }.getOrNull()
                    }
                    sessionListState = SessionListReducer.reduce(
                        sessionListState,
                        SessionListAction.RemoteSessionsReceived(sessions)
                    )
                }
                "workspaces" -> {
                    workspaces = frame.items.orEmpty().mapNotNull { item ->
                        runCatching {
                            wireJson.decodeFromJsonElement(GatewayWorkspace.serializer(), item.toJsonElement())
                        }.getOrNull()
                    }
                }
                "search" -> {
                    searchResultSessionIds = frame.items.orEmpty().mapNotNull { item ->
                        runCatching {
                            wireJson.decodeFromJsonElement(GatewaySearchItem.serializer(), item.toJsonElement())
                        }.getOrNull()?.sessionId
                    }
                }
                "agent-presets" -> {
                    agentPresets = frame.presets.orEmpty()
                    agentPresetDefault = frame.agentPresetDefault
                        ?: agentPresets.firstOrNull { it.isDefault }?.id
                }
                "defaults" -> {
                    agentPresetDefault = frame.agentPresetDefault ?: agentPresetDefault
                    permissionDefault = frame.permissionDefault ?: permissionDefault
                }
                "default-model", "save-default-model" -> {
                    defaultModel = frame.current ?: frame.selection ?: frame.saved ?: defaultModel
                }
                "models" -> modelCatalog = GatewayModelCatalog(
                    current = frame.current,
                    routable = frame.routable != false,
                    groups = frame.groups.orEmpty()
                )
                "permission-options", "permission" -> {
                    permissions = frame.sessionPermissions ?: permissions
                }
                "context-usage" -> contextSnapshot = GatewayContextSnapshot(
                    asOfSeq = frame.asOfSeq,
                    tokenUsage = frame.tokenUsage,
                    pressure = frame.contextPressure
                )
                "session-stats" -> statsSnapshot = GatewaySessionStatsSnapshot(
                    asOfSeq = frame.asOfSeq,
                    stats = frame.sessionStats,
                    tokenUsage = frame.tokenUsage?.let { usage ->
                        com.clarklevis.dsh.shared.protocol.GatewaySessionTokenUsage(usage.totals)
                    },
                    contextPressure = frame.contextPressure
                )
                "host" -> hostSnapshot = GatewayHostSnapshot(
                    version = frame.version,
                    cwd = frame.cwd,
                    provider = frame.provider,
                    model = frame.model,
                    attachedSessions = frame.attachedSessions,
                    canOpenPath = frame.canOpenPath
                )
                "event" -> {
                    val event = frame.event
                    val sessionId = frame.sessionId
                    val sequence = frame.seq
                    val time = frame.time
                    if (event != null && sessionId != null && sequence != null && time != null) {
                        acceptEvent(SessionEvent(sessionId, sequence, time, event))
                    }
                }
                "history" -> {
                    val sessionId = frame.sessionId ?: sessionListState.selectedSessionId
                    if (sessionId != null) {
                        val normalized = frame.events.orEmpty().map { it.normalized(sessionId) }
                        eventsBySession[sessionId] = normalized
                        val insertedAtEpochSeconds = normalized
                            .maxOfOrNull { normalizeEpochSeconds(it.time) }
                            ?: frame.time?.let(::normalizeEpochSeconds)
                            ?: nowEpochSeconds()
                        sessionListState = SessionListReducer.reduce(
                            sessionListState,
                            SessionListAction.KnownSessionAdded(sessionId, insertedAtEpochSeconds)
                        )
                    }
                }
                "question-requested" -> {
                    val rpcId = frame.rpcId
                    val sessionId = frame.sessionId
                    val questions = frame.questions
                    if (rpcId != null && sessionId != null && !questions.isNullOrEmpty()) {
                        questionState = QuestionReducer.reduce(
                            questionState,
                            QuestionAction.RequestReceived(
                                GatewayPendingQuestionRequest(rpcId, sessionId, questions, frame.replay == true)
                            )
                        )
                    }
                }
                "question-response" -> {
                    val rpcId = frame.rpcId
                    val action = when (frame.action) {
                        "answer" -> GatewayQuestionAction.ANSWER
                        "cancel" -> GatewayQuestionAction.CANCEL
                        else -> null
                    }
                    if (rpcId != null && action != null && frame.accepted != null) {
                        questionState = QuestionReducer.reduce(
                            questionState,
                            QuestionAction.ResponseReceived(rpcId, action, frame.accepted, frame.reason)
                        )
                    }
                }
                "question-resolved" -> frame.rpcId?.let { rpcId ->
                    questionState = QuestionReducer.reduce(questionState, QuestionAction.Resolved(rpcId))
                }
            }
        } catch (error: Throwable) {
            lastError = error.message ?: error::class.simpleName ?: "decode-error"
        }
        return makeSnapshot()
    }

    fun loadManualTestFixture(): SharedMobileSnapshot {
        reset()
        acceptFrame("""{"kind":"sessions","items":[{"sessionId":"android-demo","updatedAt":1786937352000,"running":true,"blank":false,"cwd":"/tmp/kmp-demo","agentPreset":"standard"}]}""")
        selectSession("android-demo")
        acceptFrame("""{"kind":"event","sessionId":"android-demo","seq":1,"time":1786937352,"event":{"type":"user/message","source":"user","text":"请验证 Android 是否使用共享 Reducer"}}""")
        acceptFrame("""{"sessionId":"android-demo","seq":2,"time":1786937353,"event":{"type":"assistant/chunk","turn":1,"step":1,"chunkType":"text-delta","text":"共享协议解码、"}}""")
        acceptFrame("""{"sessionId":"android-demo","seq":3,"time":1786937354,"event":{"type":"assistant/chunk","turn":1,"step":1,"chunkType":"text-delta","text":"Reducer 与投影已接入。"}}""")
        acceptFrame("""{"kind":"question-requested","rpcId":"android-question","sessionId":"android-demo","replay":false,"questions":[{"id":"result","question":"人工测试是否通过？","options":[{"label":"通过"},{"label":"需要修复"}]}]}""")
        return makeSnapshot()
    }

    fun reset(): SharedMobileSnapshot {
        sessionListState = SessionListState()
        questionState = QuestionState()
        eventsBySession.clear()
        workspaces = emptyList()
        searchResultSessionIds = emptyList()
        agentPresets = emptyList()
        agentPresetDefault = null
        permissionDefault = null
        defaultModel = null
        modelCatalog = null
        permissions = null
        contextSnapshot = null
        statsSnapshot = null
        hostSnapshot = null
        lastFrameKind = null
        lastError = null
        return makeSnapshot()
    }

    private fun acceptEvent(record: SessionEvent) {
        sessionListState = SessionListReducer.reduce(
            sessionListState,
            SessionListAction.EventReceived(record, normalizeEpochSeconds(record.time))
        )
        val existing = eventsBySession[record.sessionId].orEmpty()
        val records = existing.associateBy(SessionEvent::seq).toMutableMap().apply { put(record.seq, record) }
        eventsBySession[record.sessionId] = records.values.sortedBy(SessionEvent::seq)
    }

    private fun makeSnapshot(): SharedMobileSnapshot {
        val selected = sessionListState.selectedSessionId
        return SharedMobileSnapshot(
            sessions = sessionListState.sessions,
            workspaces = workspaces,
            searchResultSessionIds = searchResultSessionIds,
            selectedSessionId = selected,
            conversation = selected?.let { ConversationProjector.make(eventsBySession[it].orEmpty()) }.orEmpty(),
            pendingQuestions = questionState.pendingRequests,
            pendingQuestionCount = questionState.pendingRequests.size,
            agentPresets = agentPresets,
            agentPresetDefault = agentPresetDefault,
            permissionDefault = permissionDefault,
            defaultModel = defaultModel,
            modelCatalog = modelCatalog,
            permissions = permissions,
            contextSnapshot = contextSnapshot,
            statsSnapshot = statsSnapshot,
            hostSnapshot = hostSnapshot,
            lastFrameKind = lastFrameKind,
            lastError = lastError
        )
    }
}

/** Swift/Objective-C 友好的稳定门面，避免平台 UI 直接依赖内部 Reducer。 */
class SharedMobileFacade {
    fun moduleSummary(): String = SharedModuleInfo.summary()

    fun decodeFrameKind(json: String): String? =
        runCatching { GatewayWireDecoder.decode(json).kind }.getOrNull()

    fun makeStore(): SharedMobileStore = SharedMobileStore()

    fun makeSessionListStore(
        newSessionTitle: String,
        remoteSessionPrefix: String,
        blankSessionPrefix: String
    ): SharedSessionListStore = SharedSessionListStore(
        newSessionTitle = newSessionTitle,
        remoteSessionPrefix = remoteSessionPrefix,
        blankSessionPrefix = blankSessionPrefix
    )

    fun makeQuestionStore(): SharedQuestionStore = SharedQuestionStore()

    fun makeSessionControlStore(): SharedSessionControlStore = SharedSessionControlStore()

    fun makeTrajectoryStore(): SharedTrajectoryStore = SharedTrajectoryStore()

    fun makeHistoryStore(): SharedHistoryStore = SharedHistoryStore()

    fun makeConversationStore(
        userMessage: String,
        context: String,
        streamingAssistant: String,
        streamingReasoning: String,
        finalAssistant: String,
        finalReasoning: String,
        assemblingTool: String,
        toolResultDone: String,
        toolResultFailed: String
    ): SharedConversationStore = SharedConversationStore(
        ConversationProjectionLabels(
            userMessage = userMessage,
            context = context,
            streamingAssistant = streamingAssistant,
            streamingReasoning = streamingReasoning,
            finalAssistant = finalAssistant,
            finalReasoning = finalReasoning,
            assemblingTool = assemblingTool,
            toolResultDone = toolResultDone,
            toolResultFailed = toolResultFailed
        )
    )

}
