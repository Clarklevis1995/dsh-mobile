package com.clarklevis.dsh.android

import com.clarklevis.dsh.shared.cache.ChatCacheDiagnostics
import com.clarklevis.dsh.shared.cache.ChatCacheRestoreSkipReason
import com.clarklevis.dsh.shared.facade.CachedHistoryWindow
import com.clarklevis.dsh.shared.facade.SharedConversationBootstrap
import com.clarklevis.dsh.shared.facade.SharedConversationPatch
import com.clarklevis.dsh.shared.facade.SharedConversationStore
import com.clarklevis.dsh.shared.facade.SharedHistoryBootstrap
import com.clarklevis.dsh.shared.facade.SharedHistoryEffect
import com.clarklevis.dsh.shared.facade.SharedHistoryPatch
import com.clarklevis.dsh.shared.facade.SharedHistoryStore
import com.clarklevis.dsh.shared.facade.SharedMobileApprovalSubmission
import com.clarklevis.dsh.shared.facade.SharedMobileSnapshot
import com.clarklevis.dsh.shared.facade.SharedMobileStore
import com.clarklevis.dsh.shared.facade.SharedMviEvent
import com.clarklevis.dsh.shared.projection.ConversationItem
import com.clarklevis.dsh.shared.projection.ConversationProjectionLabels
import com.clarklevis.dsh.shared.projection.ConversationProjector
import com.clarklevis.dsh.shared.projection.TrajectoryNode
import com.clarklevis.dsh.shared.projection.TrajectoryProjection
import com.clarklevis.dsh.shared.protocol.GatewayFrame
import com.clarklevis.dsh.shared.protocol.GatewayPendingApprovalRequest
import com.clarklevis.dsh.shared.protocol.JsonValue
import com.clarklevis.dsh.shared.protocol.SessionEvent
import com.clarklevis.dsh.shared.sync.AssistantStreamState
import com.clarklevis.dsh.shared.sync.HistorySessionState
import com.clarklevis.dsh.shared.sync.HistorySyncConfiguration
import java.util.Locale
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val adapterJson = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
    encodeDefaults = true
}

/** Android 的 KMP MVI 适配器：history/live 水位与流式 patch 均由现有共享 Store 决定。 */
internal class AndroidGatewayProjection(
    private val mobileStore: SharedMobileStore = SharedMobileStore(),
    private val historyStore: SharedHistoryStore = SharedHistoryStore(
        HistorySyncConfiguration(pagesPerBatch = 1)
    ),
    private val conversationStore: SharedConversationStore = SharedConversationStore(mobileConversationLabels()),
    private val onResubscribe: (String) -> Unit = {},
    private val nowMonotonicSeconds: () -> Double = { System.nanoTime() / 1e9 },
    private val onHistoryPageRequested: (sessionId: String, beforeSequence: Int?, historyFormatVersion: Int?) -> Unit = { _, _, _ -> }
) {
    private data class SearchFragment(
        val sessionId: String,
        val targetSequence: Int,
        val window: CachedHistoryWindow,
        val items: List<ConversationItem>
    )
    enum class SearchFragmentDisposition { REPLACED, EXPIRED }
    private var searchFragment: SearchFragment? = null
    private var assistantStream = AssistantStreamState()
    private val recentSessions = LinkedHashMap<String, Unit>(8, 0.75f, true)
    private var usesAssistantStream = false
    private var helloReceived = false
    private var helloHistoryFormat: Int? = null
    var onCacheChanged: (String, Boolean) -> Unit = { _, _ -> }
    var onCacheInvalidated: (String) -> Unit = {}
    private var waitGeneration = 0L
    var snapshotWait: Pair<String, Long>? = null
        private set
    private val historyEvents = mutableMapOf<String, List<SessionEvent>>()
    private var trajectoryHistorySessionId: String? = null
    private var trajectoryHistoryEvents: List<SessionEvent>? = null
    private var trajectoryHistoryNodes: List<TrajectoryNode> = emptyList()
    private val historyLastSequences = mutableMapOf<String, Int>()
    private val historyErrors = mutableMapOf<String, String>()
    private val historyHasMore = mutableMapOf<String, Boolean>()
    private val historySessionStates = mutableMapOf<String, HistorySessionState>()
    private var historyPendingSessionId: String? = null
    private val conversationItems = mutableMapOf<String, List<ConversationItem>>()
    private val conversationLastSequences = mutableMapOf<String, Int>()
    private var controlSnapshot = mobileStore.snapshot()
    private val turnTiming = com.clarklevis.dsh.shared.facade.SharedTurnTimingStore()
    private var lastFrameKind: String? = null
    private var queueSessionIds = emptySet<String>()
    private var lastError: String? = null
    private val historyEnvelope = MviEnvelopeValidator("history")
    private val conversationEnvelope = MviEnvelopeValidator("conversation")
    private val historySubscription = historyStore.subscribe(::acceptHistoryMviEvent)
    private val conversationSubscription = conversationStore.subscribe(::acceptConversationMviEvent)

    fun snapshot(): SharedMobileSnapshot {
        val approvalDetails = controlSnapshot.pendingApprovals.mapNotNull { request ->
            approvalArguments(request)?.let { request.rpcId to it }
        }.toMap()
        val commandPreviews = controlSnapshot.pendingApprovals.mapNotNull { request ->
            val arguments = approvalDetails[request.rpcId]
            val preview = arguments?.get("cmd")?.stringValue
                ?: arguments?.get("command")?.stringValue
                ?: controlSnapshot.approvalCommandPreviews[request.rpcId]
            preview?.let { request.rpcId to it }
        }.toMap()
        val normal = controlSnapshot.copy(
            activeTurnTiming = controlSnapshot.selectedSessionId?.let(turnTiming::snapshot),
            selectedHistoryError = controlSnapshot.selectedSessionId?.let(historyErrors::get),
            conversation = controlSnapshot.selectedSessionId?.let(conversationItems::get).orEmpty(),
            approvalCommandPreviews = commandPreviews,
            approvalDetails = approvalDetails,
            selectedHistoryHasMore = controlSnapshot.selectedSessionId?.let(historyHasMore::get) == true,
            selectedHistoryEarliestSequence = controlSnapshot.selectedSessionId
                ?.let(historyEvents::get)?.firstOrNull()?.seq,
            selectedHistoryIsLoading = controlSnapshot.selectedSessionId
                ?.let(historySessionStates::get)?.isLoading == true ||
                (snapshotWait != null && snapshotWait?.first == controlSnapshot.selectedSessionId),
            selectedHistoryIsLoadingOlder = controlSnapshot.selectedSessionId
                ?.let(historySessionStates::get)?.isLoadingOlder == true,
            selectedHistoryLoadedEventCount = controlSnapshot.selectedSessionId
                ?.let(historySessionStates::get)?.progress?.loaded ?: 0,
            selectedHistoryTotalEventCount = controlSnapshot.selectedSessionId
                ?.let(historySessionStates::get)?.progress?.total,
            lastFrameKind = lastFrameKind ?: controlSnapshot.lastFrameKind,
            lastError = lastError ?: controlSnapshot.lastError
        )
        val fragment = searchFragment?.takeIf { it.sessionId == normal.selectedSessionId } ?: return normal
        return normal.copy(
            conversation = fragment.items,
            selectedHistoryError = null,
            selectedHistoryHasMore = false,
            selectedHistoryEarliestSequence = fragment.window.events.firstOrNull()?.seq,
            selectedHistoryIsLoading = false,
            selectedHistoryIsLoadingOlder = false,
            selectedHistoryLoadedEventCount = fragment.window.events.size,
            selectedHistoryTotalEventCount = null
        )
    }

    private fun approvalArguments(request: GatewayPendingApprovalRequest): JsonValue? {
        val targetCallId = request.callId
        val historyArguments = targetCallId?.let { callId ->
            historyEvents[request.sessionId]
                ?.lastOrNull { it.event.callId == callId }
                ?.event
                ?.arguments
                ?.normalizedJsonValue()
        }
        return historyArguments ?: controlSnapshot.approvalDetails[request.rpcId]
    }

    fun selectSession(sessionId: String?, offline: Boolean = false): SharedMobileSnapshot {
        searchFragment = null
        sessionId?.let { recentSessions[it] = Unit }
        cancelSnapshotWait()
        if (!offline) controlSnapshot.selectedSessionId?.let { conversationStore.clearAssistantChunks(it) }
        assistantStream.selectSession(sessionId)
        controlSnapshot = mobileStore.selectSession(sessionId)
        if (sessionId == null || offline) return snapshot()
        if (usesAssistantStream) awaitSnapshot(sessionId) else startHistory(sessionId, older = false)
        return snapshot()
    }

    fun canRestoreOfflineCatalog(): Boolean = mobileStore.canRestoreOfflineCatalog()

    fun canRestoreOfflineWorkspaces(): Boolean = mobileStore.canRestoreOfflineWorkspaces()

    fun hasOfflineCatalogAuthority(): Boolean =
        !mobileStore.canRestoreOfflineSessions() || !mobileStore.canRestoreOfflineWorkspaces()

    fun exportOfflineCatalog(selectedWorkspaceId: String?): String = mobileStore.exportOfflineCatalog(selectedWorkspaceId)

    fun restoreOfflineCatalog(json: String): SharedMobileSnapshot {
        controlSnapshot = mobileStore.restoreOfflineCatalog(json)
        return snapshot()
    }

    fun offlineHistoryUnavailable(sessionId: String, older: Boolean): SharedMobileSnapshot {
        historyCancelled(sessionId)
        if (older) historyHasMore[sessionId] = false
        return snapshot()
    }

    fun hydrateCachedWindow(sessionId: String, json: String): SharedMobileSnapshot {
        if (historyEvents.containsKey(sessionId)) {
            ChatCacheDiagnostics.restoreSkipped(ChatCacheRestoreSkipReason.ALREADY_LOADED)
            return snapshot()
        }
        val window = runCatching { adapterJson.decodeFromString<CachedHistoryWindow>(json).also { it.validate(sessionId) } }
            .getOrNull()
        if (window == null) {
            ChatCacheDiagnostics.restoreSkipped(ChatCacheRestoreSkipReason.INVALID_WINDOW)
            return snapshot()
        }
        if (helloReceived && window.historyFormatVersion != helloHistoryFormat) {
            ChatCacheDiagnostics.restoreSkipped(ChatCacheRestoreSkipReason.FORMAT_CHANGED)
            onCacheInvalidated(sessionId)
            return snapshot()
        }
        if (historyStore.hydrateCachedWindow(sessionId, json).accepted) {
            assistantStream.rememberCachedFormat(sessionId, window.historyFormatVersion)
            conversationStore.replaceTypedSession(sessionId, historyEvents[sessionId].orEmpty())
        }
        return snapshot()
    }

    fun hydrateTypedWindow(sessionId: String, window: CachedHistoryWindow, replaceExisting: Boolean = false): SharedMobileSnapshot {
        if (historyEvents.containsKey(sessionId) && (!replaceExisting || !historyStore.canPrependCachedPage(sessionId))) return snapshot()
        if (helloReceived && window.historyFormatVersion != helloHistoryFormat) {
            onCacheInvalidated(sessionId)
            return snapshot()
        }
        if (replaceExisting) {
            historyStore.clearSession(sessionId)
            conversationStore.clearSession(sessionId)
        }
        if (historyStore.hydrateTypedCachedWindow(sessionId, window).accepted) {
            historyHasMore[sessionId] = window.hasMore
            assistantStream.rememberCachedFormat(sessionId, window.historyFormatVersion)
            conversationStore.replaceTypedSession(sessionId, historyEvents[sessionId].orEmpty())
        }
        return snapshot()
    }

    fun closeSearchFragment(): SharedMobileSnapshot {
        searchFragment = null
        return snapshot()
    }

    fun hasNormalHistory(sessionId: String): Boolean = historyEvents.containsKey(sessionId)

    private fun canPresentCachedSearchTarget(sessionId: String, sequence: Int): Boolean {
        if (!historyEvents.containsKey(sessionId) || historyStore.canPrependCachedPage(sessionId)) return true
        val first = historyEvents[sessionId]?.firstOrNull()?.seq ?: return false
        return historyHasMore[sessionId] == true && sequence < first
    }

    /** 搜索旧页仅作为只读展示，不修改同步窗口、水位或现有 stream 基线。 */
    fun showSearchFragment(sessionId: String, sequence: Int, window: CachedHistoryWindow): Boolean {
        window.validate(sessionId)
        if (window.events.none { it.seq == sequence } || !canPresentCachedSearchTarget(sessionId, sequence)) return false
        if (helloReceived && window.historyFormatVersion != helloHistoryFormat) return false
        searchFragment = SearchFragment(
            sessionId,
            sequence,
            window,
            ConversationProjector.make(window.events, mobileConversationLabels())
        )
        return true
    }

    fun dropObsoleteSearchFragment(): SearchFragmentDisposition? {
        val fragment = searchFragment ?: return null
        val id = fragment.sessionId
        if (controlSnapshot.selectedSessionId != id) {
            searchFragment = null
            return null
        }
        if (!historyStore.canPrependCachedPage(id) && containsSequence(id, fragment.targetSequence)) {
            searchFragment = null
            return SearchFragmentDisposition.REPLACED
        }
        if (!canPresentCachedSearchTarget(id, fragment.targetSequence)) {
            searchFragment = null
            return SearchFragmentDisposition.EXPIRED
        }
        return null
    }

    /** 活跃会话不淘汰；旧窗口由 actor 完成持久化后再释放。 */
    fun trimInactive(maximumSessions: Int = 6): List<String> {
        val protected = controlSnapshot.sessions.filter { it.isRunning }.map { it.id }.toSet() +
            listOfNotNull(controlSnapshot.selectedSessionId) +
            controlSnapshot.pendingApprovals.map { it.sessionId } + controlSnapshot.pendingQuestions.map { it.sessionId }
        val keep = recentSessions.keys.toList().asReversed().take(maximumSessions).toMutableSet() + protected
        var bytes = keep.sumOf { id -> historyStore.estimatedRetainedBytes(id) + conversationStore.estimatedRetainedBytes(id) }
        val bounded = keep.toMutableSet()
        recentSessions.keys.toList().forEach { id ->
            if (bytes > 24L * 1024 * 1024 && id !in protected && id in bounded) {
                bytes -= historyStore.estimatedRetainedBytes(id) + conversationStore.estimatedRetainedBytes(id)
                bounded.remove(id)
            }
        }
        val removed = historyStore.retainSessions(bounded.toList())
        mobileStore.retainCachedSessions(bounded.toList())
        assistantStream.retainSessions(bounded.toList())
        removed.forEach {
            historyEvents.remove(it)
            historyLastSequences.remove(it)
            historySessionStates.remove(it)
            conversationStore.clearSession(it)
            conversationItems.remove(it)
            conversationLastSequences.remove(it)
            historyHasMore.remove(it)
            historyErrors.remove(it)
            recentSessions.remove(it)
            if (trajectoryHistorySessionId == it) {
                trajectoryHistorySessionId = null
                trajectoryHistoryEvents = null
                trajectoryHistoryNodes = emptyList()
            }
        }
        return removed
    }

    fun canPrependCachedPage(sessionId: String): Boolean = historyStore.canPrependCachedPage(sessionId)

    fun canPrependCachedPage(sessionId: String, window: CachedHistoryWindow): Boolean =
        canPrependCachedPage(sessionId) && window.historyFormatVersion == assistantStream.formatVersion(sessionId)

    fun prependCachedPage(sessionId: String, window: CachedHistoryWindow): SharedMobileSnapshot {
        if (!canPrependCachedPage(sessionId, window)) return snapshot()
        if (historyStore.prependTypedCachedPage(sessionId, window).accepted) {
            historyHasMore[sessionId] = window.hasMore
            conversationStore.replaceTypedSession(sessionId, historyEvents[sessionId].orEmpty())
        }
        return snapshot()
    }

    fun containsSequence(sessionId: String, sequence: Int): Boolean = historyEvents[sessionId]?.any { it.seq == sequence } == true

    fun cacheSessionIds(): List<String> = historyEvents.keys.toList()

    fun cachedWindowJson(sessionId: String): String? = historyStore.cachedWindowJson(
        sessionId,
        assistantStream.formatVersion(sessionId),
        assistantStream.persistentCursorFor(sessionId)
    )

    fun loadHistory(sessionId: String, older: Boolean): SharedMobileSnapshot {
        if (usesAssistantStream && !older && sessionId == controlSnapshot.selectedSessionId) {
            assistantStream.selectSession(sessionId)
            conversationStore.clearAssistantChunks(sessionId)
            awaitSnapshot(sessionId)
            onResubscribe(sessionId)
        } else startHistory(sessionId, older)
        return snapshot()
    }

    fun disconnected(): SharedMobileSnapshot {
        cancelSnapshotWait()
        assistantStream.disconnect()
        helloReceived = false
        historySessionStates.keys.toList().forEach(historyStore::cancelled)
        // 离线仅改变来源标记，保留相同 seq 空间的已加载正文，允许继续读取本地旧页。
        historyEvents.toMap().forEach { (id, records) ->
            if (!historyStore.canPrependCachedPage(id)) {
                val window = CachedHistoryWindow(
                    sessionId = id,
                    historyFormatVersion = assistantStream.formatVersion(id),
                    persistentCursor = assistantStream.persistentCursorFor(id),
                    events = records,
                    hasMore = historyHasMore[id] == true,
                    nextBeforeSequence = records.firstOrNull()?.seq
                )
                historyStore.clearSession(id)
                historyStore.hydrateTypedCachedWindow(id, window)
            }
        }
        return snapshot()
    }

    fun catchUpSelectedHistoryAfterReconnect(): SharedMobileSnapshot {
        controlSnapshot.selectedSessionId?.let {
            if (usesAssistantStream) {
                if (!assistantStream.hasBaseline(it)) awaitSnapshot(it)
            } else {
                startHistory(it, older = false)
            }
        }
        return snapshot()
    }

    fun submitApprovalDecision(
        rpcId: String,
        outcome: String,
        isConnected: Boolean
    ): SharedMobileApprovalSubmission {
        val result = mobileStore.submitApprovalDecision(rpcId, outcome, isConnected)
        controlSnapshot = result.snapshot
        return result.copy(snapshot = snapshot())
    }

    fun approvalRequestFailed(rpcId: String, message: String?): SharedMobileSnapshot {
        controlSnapshot = mobileStore.approvalRequestFailed(rpcId, message)
        return snapshot()
    }

    fun approvalSessionRequestsFailed(sessionId: String, message: String?): SharedMobileSnapshot {
        controlSnapshot = mobileStore.approvalSessionRequestsFailed(sessionId, message)
        return snapshot()
    }

    private fun startHistory(sessionId: String, older: Boolean) {
        historyErrors.remove(sessionId)
        val local = historyEvents[sessionId].orEmpty()
        historyStore.start(
            sessionId = sessionId,
            older = older,
            hasLocalEvents = local.isNotEmpty(),
            earliestLocalSequence = local.firstOrNull()?.seq
        )
    }

    fun acceptFrame(rawJson: String, frame: GatewayFrame, correlatedSessionId: String?): SharedMobileSnapshot {
        lastFrameKind = frame.kind
        if (frame.kind == "session-queues" && frame.queues != null) {
            val queues = requireNotNull(frame.queues)
            (queueSessionIds + queues.keys).forEach { sessionId ->
                val result = conversationStore.replaceSteeringMessages(sessionId, adapterJson.encodeToString(queues[sessionId].orEmpty()))
                if (!result.accepted) lastError = result.errorMessage
            }
            queueSessionIds = queues.keys
        } else if (frame.kind == "session-queue" && frame.sessionId != null && frame.items != null) {
            val sessionId = requireNotNull(frame.sessionId)
            queueSessionIds = queueSessionIds + sessionId
            val result = conversationStore.replaceSteeringMessages(sessionId, adapterJson.encodeToString(requireNotNull(frame.items)))
            if (!result.accepted) lastError = result.errorMessage
        }
        if (frame.kind == "hello") {
            (frame.at ?: frame.time)?.let { turnTiming.observeServerTime(it, nowMonotonicSeconds()) }
            cancelSnapshotWait()
            usesAssistantStream = "assistant-stream-v1" in frame.capabilities.orEmpty()
            helloReceived = true
            helloHistoryFormat = frame.historyFormatVersion
        }
        val id = frame.sessionId ?: correlatedSessionId
        if (frame.kind == "history" && id != null && assistantStream.hasBaseline(id) &&
            historySessionStates[id]?.isLoadingOlder != true) return snapshot()
        val update = assistantStream.accept(frame)
        if (update.accepted) lastError = null
        update.invalidatedSessionIds.forEach {
            turnTiming.clear(it)
            onCacheInvalidated(it)
            historyStore.clearSession(it)
            conversationStore.clearSession(it)
            historyHasMore.remove(it)
        }
        if (update.clearTransient) update.sessionId?.let { conversationStore.clearAssistantChunks(it) }
        update.error?.let { lastError = it }
        if (update.resubscribe && id != null) {
            awaitSnapshot(id)
            onResubscribe(id)
        } else if (
            (update.error != null || (frame.kind == "error" && frame.requestType in setOf(null, "subscribe"))) &&
            (id == null || id == snapshotWait?.first)
        ) {
            cancelSnapshotWait()
        }
        val streamError = update.error
        if (streamError != null && !update.resubscribe && id != null) {
            historyErrors[id] = streamError
            historyCancelled(id)
        }
        if (frame.kind == "error" && frame.requestType in setOf("history", "subscribe") && id != null) {
            historyErrors[id] = frame.message ?: "历史记录加载失败"
            historyCancelled(id)
        }
        if (!update.accepted) return snapshot()
        if (frame.kind == "assistant-stream") {
            frame.frame?.get("time")?.doubleValue?.let { turnTiming.observeServerTime(it, nowMonotonicSeconds()) }
        }
        val previouslyVisible = controlSnapshot.sessions.map { it.id }.toSet()
        when (frame.kind) {
            "session-snapshot" -> if (id != null) {
                historyErrors.remove(id)
                if (snapshotWait?.first == id) snapshotWait = null
                historyStore.clearSession(id)
                conversationStore.clearSession(id)
                val records = frame.events.orEmpty().map { it.normalized(id) }
                updateTurnTimingHistory(id, records)
                historyStore.installSnapshot(id, adapterJson.encodeToString(records), frame.hasMore == true, frame.nextBeforeSeq)
                historyHasMore[id] = frame.hasMore == true
                conversationStore.replaceSession(id, adapterJson.encodeToString(records))
                controlSnapshot = mobileStore.acceptFrame(rawJson)
            }
            "assistant-stream", "session-stream-reset", "subscribed" -> Unit
            "history" -> acceptHistory(frame, correlatedSessionId)
            "event" -> acceptLive(rawJson, frame)
            "hello" -> if (usesAssistantStream) controlSnapshot.selectedSessionId?.let(::awaitSnapshot)
            "paired", "attachment" -> Unit
            else -> {
                if (frame.kind == "sent" && id != null &&
                    controlSnapshot.sessions.none { it.id == id && it.isRunning } && turnTiming.snapshot(id) == null) {
                    turnTiming.awaitStart(id, historyLastSequences[id] ?: -1)
                }
                controlSnapshot = mobileStore.acceptFrame(rawJson)
                if (frame.kind == "sent" && frame.sessionId == controlSnapshot.selectedSessionId) assistantStream.selectSession(controlSnapshot.selectedSessionId)
            }
        }
        if (frame.kind in setOf("sessions", "session-archives", "session-archived", "workspaces")) {
            val removed = previouslyVisible - controlSnapshot.sessions.map { it.id }.toSet()
            (removed + frame.archivedSessionIds.orEmpty()).forEach { sessionId ->
                onCacheInvalidated(sessionId)
                historyStore.clearSession(sessionId)
                conversationStore.clearSession(sessionId)
            }
        }
        if (id != null && update.attemptId != null && update.chunksJson != "[]") {
            conversationStore.assistantChunks(id, requireNotNull(update.attemptId), update.chunksJson)
        }
        return snapshot()
    }

    fun reset(): SharedMobileSnapshot {
        searchFragment = null
        cancelSnapshotWait()
        assistantStream = AssistantStreamState()
        usesAssistantStream = false
        helloReceived = false
        helloHistoryFormat = null
        (historyEvents.keys + queueSessionIds).toSet().forEach {
            historyStore.clearSession(it)
            conversationStore.clearSession(it)
        }
        historyEvents.clear()
        trajectoryHistorySessionId = null
        trajectoryHistoryEvents = null
        trajectoryHistoryNodes = emptyList()
        historyLastSequences.clear()
        historyErrors.clear()
        historyHasMore.clear()
        historySessionStates.clear()
        historyPendingSessionId = null
        conversationItems.clear()
        conversationLastSequences.clear()
        queueSessionIds = emptySet()
        controlSnapshot = mobileStore.reset()
        lastFrameKind = null
        lastError = null
        return snapshot()
    }

    fun loadFixture(): SharedMobileSnapshot {
        reset()
        controlSnapshot = mobileStore.loadManualTestFixture()
        controlSnapshot.selectedSessionId?.let { sessionId ->
            conversationItems[sessionId] = controlSnapshot.conversation
        }
        lastFrameKind = controlSnapshot.lastFrameKind
        return snapshot()
    }

    fun close() {
        historySubscription.cancel()
        conversationSubscription.cancel()
    }

    fun historyTimedOut(sessionId: String) {
        historyErrors[sessionId] = "历史记录加载超时，请重试"
        if (snapshotWait?.first == sessionId) {
            snapshotWait = null
            lastError = "历史记录加载超时，请重试"
        }
        historyStore.timedOut(sessionId)
    }

    fun historyCancelled(sessionId: String) {
        if (snapshotWait?.first == sessionId) snapshotWait = null
        historyStore.cancelled(sessionId)
    }

    private fun awaitSnapshot(sessionId: String) {
        historyErrors.remove(sessionId)
        cancelSnapshotWait()
        historyStore.awaitSnapshot(sessionId)
        snapshotWait = sessionId to ++waitGeneration
    }

    private fun cancelSnapshotWait() {
        snapshotWait?.first?.let(historyStore::cancelled)
        snapshotWait = null
    }

    fun trajectory(sessionId: String?): List<TrajectoryNode> {
        if (sessionId == null) return emptyList()
        val events = historyEvents[sessionId].orEmpty()
        if (trajectoryHistorySessionId != sessionId || trajectoryHistoryEvents !== events) {
            trajectoryHistoryNodes = TrajectoryProjection.make(events)
            trajectoryHistoryEvents = events
            trajectoryHistorySessionId = sessionId
        }
        val transient = assistantStream.transientTrajectoryNodes(sessionId)
        return if (transient.isEmpty()) trajectoryHistoryNodes else trajectoryHistoryNodes + transient
    }

    internal fun acceptHistoryMviEventForTest(event: SharedMviEvent) = acceptHistoryMviEvent(event)

    internal fun acceptConversationMviEventForTest(event: SharedMviEvent) =
        acceptConversationMviEvent(event)

    private fun acceptHistory(frame: GatewayFrame, correlatedSessionId: String?) {
        val sessionId = correlatedSessionId?.takeIf(String::isNotBlank)
        if (sessionId == null) {
            lastError = "history-correlation-missing"
            return
        }
        historyErrors.remove(sessionId)
        val normalized = frame.events.orEmpty().map { it.normalized(sessionId) }
        historyStore.processingStarted(sessionId, normalized.size, frame.hasMore == true)
        val result = historyStore.pageReceived(
            sessionId = sessionId,
            eventsJson = adapterJson.encodeToString(normalized),
            byteCount = frame.bytes ?: 0,
            hasMore = frame.hasMore == true,
            nextBeforeSequence = frame.nextBeforeSeq,
            remoteActivityTimestamp = frame.time
        )
        if (!result.accepted) {
            lastError = result.errorCode ?: "history-page-failed"
            return
        }
        historyHasMore[sessionId] = frame.hasMore == true
        updateTurnTimingHistory(sessionId, historyEvents[sessionId].orEmpty())
        if (assistantStream.activeAttemptId() == null) {
            controlSnapshot = mobileStore.reconcileHistory(sessionId, historyEvents[sessionId].orEmpty())
        }
        conversationStore.replaceTypedSession(sessionId, historyEvents[sessionId].orEmpty())
        assistantStream.activeAttemptId()?.takeIf { assistantStream.hasBaseline(sessionId) }?.let {
            conversationStore.assistantChunks(sessionId, it, assistantStream.replayChunksJson())
        }
    }

    private fun acceptLive(rawJson: String, frame: GatewayFrame) {
        val sessionId = frame.sessionId
        val sequence = frame.seq
        val timestamp = frame.time
        val gatewayEvent = frame.event
        if (sessionId.isNullOrBlank() || sequence == null || timestamp == null || gatewayEvent == null) {
            lastError = "live-event-invalid"
            return
        }
        turnTiming.receiveLiveEvent(sessionId, sequence, timestamp, gatewayEvent.type, nowMonotonicSeconds())
        // ConversationStore 已经增量处理 token。旧 MobileStore 会为每个 chunk 复制全部
        // SessionEvent 并从头重建 Conversation；长回复因此越到后面越慢。chunk 不会更新
        // Session 列表元数据，禁止再进入这条兼容性全量投影路径。
        if (gatewayEvent.type != "assistant/chunk") {
            controlSnapshot = mobileStore.acceptFrame(rawJson)
        }
        val record = SessionEvent(sessionId, sequence, timestamp, gatewayEvent, frame.surfaceOp, frame.sourceEventSeqs)
        val recordJson = adapterJson.encodeToString(record)
        val historyResult = historyStore.liveEventReceived(recordJson)
        if (!historyResult.accepted) {
            lastError = historyResult.errorCode ?: "history-live-failed"
            return
        }
        val conversationResult = conversationStore.receiveEvent(recordJson)
        if (!conversationResult.accepted) {
            conversationStore.replaceTypedSession(sessionId, historyEvents[sessionId].orEmpty())
        }
    }

    private fun updateTurnTimingHistory(sessionId: String, records: List<SessionEvent>) {
        turnTiming.receiveHistory(sessionId, records.mapNotNull { record ->
            if (record.event.type !in setOf("turn/start", "turn/end")) return@mapNotNull null
            com.clarklevis.dsh.shared.facade.SharedTurnBoundary(
                record.seq, record.time, record.event.type == "turn/start"
            )
        })
    }

    private fun acceptHistoryMviEvent(event: SharedMviEvent) {
        if (!historyEnvelope.validate(event)) {
            lastError = "history-envelope-invalid"
            return
        }
        val plan = runCatching { planHistoryEvent(event) }.getOrElse {
            historyEnvelope.reject()
            lastError = "history-adapter-failed"
            return
        }
        historyEvents.clear()
        historyEvents.putAll(plan.eventsBySession)
        historyLastSequences.clear()
        historyLastSequences.putAll(plan.lastSequencesBySession)
        historySessionStates.clear()
        historySessionStates.putAll(plan.sessionStatesBySession)
        historyPendingSessionId = plan.pendingSessionId
        historyEnvelope.commit(event)
        if (event.kind == "error") lastError = event.errorCode ?: "history-store-error"
        if (event.kind == "transition" &&
            !event.transactionId.startsWith("history-cache:") &&
            !event.transactionId.startsWith("history-clear:")
        ) {
            val patch = adapterJson.decodeFromString<SharedHistoryPatch>(requireNotNull(event.statePayloadJson))
            if (patch.eventPatch?.authoritative == true) {
                // 磁盘副本的展示序号不是已确认水位；允许本次权威窗口重建较短的正文。
                conversationLastSequences.remove(patch.sessionId)
            }
            if ((patch.eventPatch != null || patch.outcome == "completed") &&
                patch.eventPatch?.record?.event?.type != "assistant/chunk"
            ) {
                val immediate = patch.eventPatch?.record?.event?.type == "assistant/message" ||
                    event.transactionId.startsWith("history-snapshot:") ||
                    patch.outcome == "completed"
                onCacheChanged(patch.sessionId, immediate)
            }
        }
        plan.effects.forEach { effect ->
            runCatching { onHistoryPageRequested(effect.sessionId, effect.beforeSequence, assistantStream.formatVersion(effect.sessionId)) }
                .onFailure { lastError = "history-effect-failed" }
        }
    }

    private fun planHistoryEvent(event: SharedMviEvent): HistoryPlan {
        if (event.kind == "error") {
            require(event.statePayloadJson == null && decodeHistoryEffects(event).isEmpty())
            require(!event.errorCode.isNullOrBlank())
            return HistoryPlan(
                historyEvents.toMap(),
                historyLastSequences.toMap(),
                historySessionStates.toMap(),
                historyPendingSessionId,
                emptyList()
            )
        }
        if (event.kind == "snapshot") {
            val bootstrap = adapterJson.decodeFromString<SharedHistoryBootstrap>(
                requireNotNull(event.statePayloadJson)
            )
            require(bootstrap.schema == 1 && decodeHistoryEffects(event).isEmpty())
            bootstrap.eventsBySession.forEach { (sessionId, records) ->
                require(sessionId.isNotBlank() && records.all { it.sessionId == sessionId })
                require(records.zipWithNext().all { (left, right) -> left.seq < right.seq })
            }
            return HistoryPlan(
                bootstrap.eventsBySession,
                bootstrap.eventsBySession.mapValues { it.value.lastOrNull()?.seq ?: -1 },
                bootstrap.state.sessions,
                bootstrap.state.pendingSessionId,
                emptyList()
            )
        }
        require(event.kind == "transition")
        val patch = adapterJson.decodeFromString<SharedHistoryPatch>(
            requireNotNull(event.statePayloadJson)
        )
        require(patch.schema == 1 && patch.sessionId.isNotBlank())
        require(patch.outcome in HISTORY_OUTCOMES)
        val effects = decodeHistoryEffects(event)
        require(effects.all { it.action == "request-page" && it.sessionId == patch.sessionId })
        require((patch.outcome == "request-page") == (effects.size == 1))
        val next = historyEvents.toMutableMap()
        val nextSequences = historyLastSequences.toMutableMap()
        val nextSessionStates = historySessionStates.toMutableMap()
        patch.session?.let { nextSessionStates[patch.sessionId] = it }
        val nextPendingSessionId = if (patch.pendingSessionChanged) {
            patch.pendingSessionId
        } else {
            historyPendingSessionId
        }
        patch.eventPatch?.let { eventPatch ->
            val old = next[patch.sessionId].orEmpty()
            next[patch.sessionId] = when (eventPatch.kind) {
                "replace" -> {
                    val replacement = requireNotNull(eventPatch.replacementEvents)
                    require(eventPatch.record == null)
                    require(replacement.all { it.sessionId == patch.sessionId })
                    require(replacement.zipWithNext().all { (left, right) -> left.seq < right.seq })
                    val previousTail = nextSequences[patch.sessionId] ?: old.lastOrNull()?.seq ?: -1
                    val replacementTail = replacement.lastOrNull()?.seq ?: -1
                    require(eventPatch.authoritative || replacement.isEmpty() || replacementTail >= previousTail)
                    if (replacement.isEmpty()) nextSequences.remove(patch.sessionId)
                    else nextSequences[patch.sessionId] = replacementTail
                    replacement
                }
                "append", "upsert" -> {
                    val record = requireNotNull(eventPatch.record)
                    require(eventPatch.replacementEvents == null && record.sessionId == patch.sessionId)
                    val previousTail = nextSequences[patch.sessionId] ?: old.lastOrNull()?.seq ?: -1
                    if (eventPatch.kind == "append") {
                        require(record.seq > previousTail && eventPatch.index == old.size)
                    } else {
                        require(record.seq <= previousTail && eventPatch.index != null)
                    }
                    val merged = old.associateBy(SessionEvent::seq).toMutableMap().apply {
                        put(record.seq, record)
                    }.values.sortedBy(SessionEvent::seq)
                    eventPatch.index?.let { require(it == merged.indexOfFirst { item -> item.seq == record.seq }) }
                    nextSequences[patch.sessionId] = maxOf(previousTail, record.seq)
                    merged
                }
                else -> error("unknown history patch kind")
            }
        }
        return HistoryPlan(
            next,
            nextSequences,
            nextSessionStates,
            nextPendingSessionId,
            effects
        )
    }

    private fun decodeHistoryEffects(event: SharedMviEvent): List<SharedHistoryEffect> =
        adapterJson.decodeFromString(event.effectsJson)

    private fun acceptConversationMviEvent(event: SharedMviEvent) {
        if (!conversationEnvelope.validate(event)) {
            lastError = "conversation-envelope-invalid"
            return
        }
        val plan = runCatching { planConversationEvent(event) }.getOrElse {
            conversationEnvelope.reject()
            lastError = "conversation-adapter-failed"
            return
        }
        conversationItems.clear()
        conversationItems.putAll(plan.itemsBySession)
        conversationLastSequences.clear()
        conversationLastSequences.putAll(plan.lastSequencesBySession)
        conversationEnvelope.commit(event)
        if (event.kind == "error") lastError = event.errorCode ?: "conversation-store-error"
    }

    private fun planConversationEvent(event: SharedMviEvent): ConversationPlan {
        require(adapterJson.decodeFromString<List<SharedHistoryEffect>>(event.effectsJson).isEmpty())
        if (event.kind == "error") {
            require(event.statePayloadJson == null && !event.errorCode.isNullOrBlank())
            return ConversationPlan(conversationItems.toMap(), conversationLastSequences.toMap())
        }
        if (event.kind == "snapshot") {
            val bootstrap = adapterJson.decodeFromString<SharedConversationBootstrap>(
                requireNotNull(event.statePayloadJson)
            )
            require(bootstrap.schema == 1)
            return ConversationPlan(conversationItems.toMap(), conversationLastSequences.toMap())
        }
        require(event.kind == "transition")
        val patch = adapterJson.decodeFromString<SharedConversationPatch>(
            requireNotNull(event.statePayloadJson)
        )
        require(patch.schema == 1 && patch.sessionId.isNotBlank() && patch.lastSequence >= -1)
        val next = conversationItems.toMutableMap()
        val nextSequences = conversationLastSequences.toMutableMap()
        val previousSequence = nextSequences[patch.sessionId] ?: -1
        if (patch.replacesAll) {
            val replacement = requireNotNull(patch.replacementItems)
            require(patch.operations.isEmpty())
            require(replacement.map(ConversationItem::id).distinct().size == replacement.size)
            val isExplicitClear = replacement.isEmpty() && patch.lastSequence == -1
            require(isExplicitClear || patch.lastSequence >= previousSequence)
            next[patch.sessionId] = replacement
            if (isExplicitClear) nextSequences.remove(patch.sessionId)
            else nextSequences[patch.sessionId] = patch.lastSequence
            return ConversationPlan(next, nextSequences)
        }
        require(patch.replacementItems == null)
        // 不可见持久事件可以只推进水位；独立临时流仍不能使水位倒退。
        require(patch.lastSequence >= previousSequence)
        require(patch.operations.isNotEmpty() || patch.lastSequence > previousSequence)
        val items = next[patch.sessionId].orEmpty().toMutableList()
        patch.operations.forEach { operation ->
            when (operation.kind) {
                "insert" -> {
                    val item = requireNotNull(operation.item)
                    require(operation.itemId == null && operation.delta == null)
                    require(items.none { it.id == item.id })
                    items += item
                }
                "append-text" -> {
                    require(operation.item == null && !operation.itemId.isNullOrBlank() && operation.delta != null)
                    val index = items.indexOfFirst { it.id == operation.itemId }
                    require(index >= 0)
                    items[index] = items[index].copy(
                        text = items[index].text + operation.delta,
                        epochSeconds = operation.epochSeconds ?: items[index].epochSeconds
                    )
                }
                "replace" -> {
                    val item = requireNotNull(operation.item)
                    require(operation.itemId == item.id && operation.delta == null && operation.epochSeconds == null)
                    val index = items.indexOfFirst { it.id == item.id }
                    require(index >= 0)
                    items[index] = item
                }
                "remove" -> {
                    require(operation.item == null && !operation.itemId.isNullOrBlank() && operation.delta == null)
                    require(items.removeAll { it.id == operation.itemId })
                }
                else -> error("unknown conversation operation")
            }
        }
        next[patch.sessionId] = items
        nextSequences[patch.sessionId] = patch.lastSequence
        return ConversationPlan(next, nextSequences)
    }

    private data class HistoryPlan(
        val eventsBySession: Map<String, List<SessionEvent>>,
        val lastSequencesBySession: Map<String, Int>,
        val sessionStatesBySession: Map<String, HistorySessionState>,
        val pendingSessionId: String?,
        val effects: List<SharedHistoryEffect>
    )

    private data class ConversationPlan(
        val itemsBySession: Map<String, List<ConversationItem>>,
        val lastSequencesBySession: Map<String, Int>
    )

    companion object {
        private val HISTORY_OUTCOMES = setOf(
            "none", "request-page", "stopped", "completed", "failed"
        )
    }
}

private fun mobileConversationLabels(): ConversationProjectionLabels {
    val isChinese = Locale.getDefault().language.startsWith("zh")
    return if (isChinese) {
        ConversationProjectionLabels(
            commandRunning = "正在执行…",
            commandCompacting = "正在压缩…",
            commandCompleted = "已完成",
            commandFailed = "执行失败",
            compactedHistory = "已压缩 {items} 条历史记录（约 {tokens} tokens）",
            interrupted = "生成已中断",
            modelAttemptFailed = "模型请求失败",
            modelRetrying = "正在重试模型请求",
            modelRetried = "已重试模型请求",
            generationFailed = "生成失败",
            generationCancelled = "生成已取消",
            outputTruncated = "输出已截断，保留开头和末尾"
        )
    } else {
        ConversationProjectionLabels()
    }
}

internal class MviEnvelopeValidator(private val expectedDomain: String) {
    private var initialized = false
    private var failed = false
    private var lastSequence = 0L
    private val recentTransactions = ArrayDeque<String>()
    private val transactionSet = mutableSetOf<String>()
    internal val retainedTransactionCountForTest: Int get() = transactionSet.size

    fun validate(event: SharedMviEvent): Boolean {
        if (failed) return false
        val validBase = event.schema == 2 && event.domain == expectedDomain &&
            event.transactionId.isNotBlank() && event.transactionId !in transactionSet &&
            event.metadataJson == null
        val validSequence = when (event.kind) {
            "snapshot" -> !initialized
            "transition", "error" -> initialized && event.sequence == lastSequence + 1
            else -> false
        }
        if (!validBase || !validSequence) {
            failed = true
            return false
        }
        return true
    }

    fun commit(event: SharedMviEvent) {
        check(!failed)
        initialized = true
        lastSequence = event.sequence
        recentTransactions.addLast(event.transactionId)
        transactionSet += event.transactionId
        if (recentTransactions.size > MAXIMUM_RECENT_TRANSACTIONS) {
            transactionSet -= recentTransactions.removeFirst()
        }
    }

    fun reject() {
        failed = true
    }

    companion object {
        private const val MAXIMUM_RECENT_TRANSACTIONS = 64
    }
}
