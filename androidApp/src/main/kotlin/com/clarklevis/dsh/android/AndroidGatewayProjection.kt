package com.clarklevis.dsh.android

import com.clarklevis.dsh.shared.facade.SharedConversationBootstrap
import com.clarklevis.dsh.shared.facade.SharedConversationPatch
import com.clarklevis.dsh.shared.facade.SharedConversationStore
import com.clarklevis.dsh.shared.facade.SharedHistoryBootstrap
import com.clarklevis.dsh.shared.facade.SharedHistoryEffect
import com.clarklevis.dsh.shared.facade.SharedHistoryPatch
import com.clarklevis.dsh.shared.facade.SharedHistoryStore
import com.clarklevis.dsh.shared.facade.SharedMviEvent
import com.clarklevis.dsh.shared.facade.SharedMobileSnapshot
import com.clarklevis.dsh.shared.facade.SharedMobileApprovalSubmission
import com.clarklevis.dsh.shared.facade.SharedMobileStore
import com.clarklevis.dsh.shared.projection.ConversationItem
import com.clarklevis.dsh.shared.projection.TrajectoryNode
import com.clarklevis.dsh.shared.projection.TrajectoryProjection
import com.clarklevis.dsh.shared.protocol.GatewayFrame
import com.clarklevis.dsh.shared.protocol.GatewayPendingApprovalRequest
import com.clarklevis.dsh.shared.protocol.JsonValue
import com.clarklevis.dsh.shared.protocol.SessionEvent
import com.clarklevis.dsh.shared.sync.HistorySessionState
import com.clarklevis.dsh.shared.sync.HistorySyncConfiguration
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
    private val conversationStore: SharedConversationStore = SharedConversationStore(),
    private val onHistoryPageRequested: (sessionId: String, beforeSequence: Int?) -> Unit = { _, _ -> }
) {
    private val historyEvents = mutableMapOf<String, List<SessionEvent>>()
    private val historyLastSequences = mutableMapOf<String, Int>()
    private val historyHasMore = mutableMapOf<String, Boolean>()
    private val historySessionStates = mutableMapOf<String, HistorySessionState>()
    private var historyPendingSessionId: String? = null
    private val conversationItems = mutableMapOf<String, List<ConversationItem>>()
    private val conversationLastSequences = mutableMapOf<String, Int>()
    private var controlSnapshot = mobileStore.snapshot()
    private var lastFrameKind: String? = null
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
        return controlSnapshot.copy(
            conversation = controlSnapshot.selectedSessionId?.let(conversationItems::get).orEmpty(),
            approvalCommandPreviews = commandPreviews,
            approvalDetails = approvalDetails,
            selectedHistoryHasMore = controlSnapshot.selectedSessionId?.let(historyHasMore::get) == true,
            selectedHistoryEarliestSequence = controlSnapshot.selectedSessionId
                ?.let(historyEvents::get)?.firstOrNull()?.seq,
            selectedHistoryIsLoading = controlSnapshot.selectedSessionId
                ?.let(historySessionStates::get)?.isLoading == true,
            selectedHistoryIsLoadingOlder = controlSnapshot.selectedSessionId
                ?.let(historySessionStates::get)?.isLoadingOlder == true,
            selectedHistoryLoadedEventCount = controlSnapshot.selectedSessionId
                ?.let(historySessionStates::get)?.progress?.loaded ?: 0,
            selectedHistoryTotalEventCount = controlSnapshot.selectedSessionId
                ?.let(historySessionStates::get)?.progress?.total,
            lastFrameKind = lastFrameKind ?: controlSnapshot.lastFrameKind,
            lastError = lastError ?: controlSnapshot.lastError
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

    fun selectSession(sessionId: String?): SharedMobileSnapshot {
        controlSnapshot = mobileStore.selectSession(sessionId)
        if (sessionId == null) return snapshot()
        startHistory(sessionId, older = false)
        return snapshot()
    }

    fun loadHistory(sessionId: String, older: Boolean): SharedMobileSnapshot {
        startHistory(sessionId, older)
        return snapshot()
    }

    fun catchUpSelectedHistoryAfterReconnect(): SharedMobileSnapshot {
        controlSnapshot.selectedSessionId?.let { startHistory(it, older = false) }
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
        lastError = null
        when (frame.kind) {
            "history" -> acceptHistory(frame, correlatedSessionId)
            "event" -> acceptLive(rawJson, frame)
            "paired", "hello", "attachment" -> Unit
            else -> controlSnapshot = mobileStore.acceptFrame(rawJson)
        }
        return snapshot()
    }

    fun reset(): SharedMobileSnapshot {
        historyEvents.keys.toList().forEach {
            historyStore.clearSession(it)
            conversationStore.clearSession(it)
        }
        historyEvents.clear()
        historyLastSequences.clear()
        historyHasMore.clear()
        historySessionStates.clear()
        historyPendingSessionId = null
        conversationItems.clear()
        conversationLastSequences.clear()
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
        historyStore.timedOut(sessionId)
    }

    fun historyCancelled(sessionId: String) {
        historyStore.cancelled(sessionId)
    }

    fun trajectory(sessionId: String?): List<TrajectoryNode> =
        sessionId?.let(historyEvents::get)?.let(TrajectoryProjection::make).orEmpty()

    internal fun acceptHistoryMviEventForTest(event: SharedMviEvent) = acceptHistoryMviEvent(event)

    internal fun acceptConversationMviEventForTest(event: SharedMviEvent) =
        acceptConversationMviEvent(event)

    private fun acceptHistory(frame: GatewayFrame, correlatedSessionId: String?) {
        val sessionId = correlatedSessionId?.takeIf(String::isNotBlank)
        if (sessionId == null) {
            lastError = "history-correlation-missing"
            return
        }
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
        conversationStore.replaceSession(sessionId, adapterJson.encodeToString(historyEvents[sessionId].orEmpty()))
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
        // ConversationStore 已经增量处理 token。旧 MobileStore 会为每个 chunk 复制全部
        // SessionEvent 并从头重建 Conversation；长回复因此越到后面越慢。chunk 不会更新
        // Session 列表元数据，禁止再进入这条兼容性全量投影路径。
        if (gatewayEvent.type != "assistant/chunk") {
            controlSnapshot = mobileStore.acceptFrame(rawJson)
        }
        val record = SessionEvent(sessionId, sequence, timestamp, gatewayEvent)
        val recordJson = adapterJson.encodeToString(record)
        val historyResult = historyStore.liveEventReceived(recordJson)
        if (!historyResult.accepted) {
            lastError = historyResult.errorCode ?: "history-live-failed"
            return
        }
        val conversationResult = conversationStore.receiveEvent(recordJson)
        if (!conversationResult.accepted) {
            conversationStore.replaceSession(sessionId, adapterJson.encodeToString(historyEvents[sessionId].orEmpty()))
        }
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
        plan.effects.forEach { effect ->
            runCatching { onHistoryPageRequested(effect.sessionId, effect.beforeSequence) }
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
                    require(replacement.isEmpty() || replacementTail >= previousTail)
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
        require(patch.operations.isNotEmpty() && patch.lastSequence > previousSequence)
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
