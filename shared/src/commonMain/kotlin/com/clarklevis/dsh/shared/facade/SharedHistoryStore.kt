package com.clarklevis.dsh.shared.facade

import com.clarklevis.dsh.shared.protocol.SessionEvent
import com.clarklevis.dsh.shared.protocol.wireJson
import com.clarklevis.dsh.shared.sync.HistoryAction
import com.clarklevis.dsh.shared.sync.HistoryEventMerger
import com.clarklevis.dsh.shared.sync.HistoryFailureCode
import com.clarklevis.dsh.shared.sync.HistoryReducer
import com.clarklevis.dsh.shared.sync.HistoryResult
import com.clarklevis.dsh.shared.sync.HistorySessionState
import com.clarklevis.dsh.shared.sync.HistoryState
import com.clarklevis.dsh.shared.sync.HistorySyncConfiguration
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
data class SharedHistoryBootstrap(
    val schema: Int = 1,
    val state: HistoryState = HistoryState(),
    val eventsBySession: Map<String, List<SessionEvent>> = emptyMap()
)

@Serializable
data class SharedHistoryEventPatch(
    val kind: String,
    val record: SessionEvent? = null,
    val index: Int? = null,
    val replacementEvents: List<SessionEvent>? = null
)

@Serializable
data class SharedHistoryPatch(
    val schema: Int = 1,
    val sessionId: String,
    val session: HistorySessionState? = null,
    val pendingSessionId: String? = null,
    val pendingSessionChanged: Boolean = false,
    val eventPatch: SharedHistoryEventPatch? = null,
    val outcome: String = "none",
    val failureCode: String? = null,
    val completedEventCount: Int? = null,
    val completedByteCount: Int? = null,
    val completedHasMore: Boolean? = null
)

@Serializable
data class SharedHistoryEffect(
    val action: String,
    val sessionId: String,
    val beforeSequence: Int? = null
)

/**
 * History 的唯一业务状态源。
 *
 * 平台只负责网络请求、超时定时器和 UI 发布；分页 cursor、加载状态、水位以及
 * history/live tail 的有序去重全部在这里完成。所有状态与 effect 先完成序列化，
 * 再原子提交并发布同一 MVI 事务。
 */
class SharedHistoryStore(
    private val configuration: HistorySyncConfiguration = HistorySyncConfiguration()
) {
    private var state = HistoryState()
    private var eventsBySession: Map<String, List<SessionEvent>> = emptyMap()
    private val events = SharedMviEventEmitter("history")

    fun subscribe(observer: SharedMviEventObserver): SharedMviSubscription = try {
        events.subscribe(
            observer,
            wireJson.encodeToString(SharedHistoryBootstrap(state = state, eventsBySession = eventsBySession))
        )
    } catch (error: Throwable) {
        events.subscribeError(
            observer,
            "history-subscribe-failed",
            error.message ?: error::class.simpleName ?: "unknown-error"
        )
    }

    fun start(
        sessionId: String,
        older: Boolean,
        hasLocalEvents: Boolean,
        earliestLocalSequence: Int?
    ): SharedMviDispatchResult = reduce(
        "start",
        sessionId,
        HistoryAction.Start(sessionId, older, hasLocalEvents, earliestLocalSequence)
    )

    fun processingStarted(
        sessionId: String,
        rawEventCount: Int,
        hasMore: Boolean
    ): SharedMviDispatchResult = reduce(
        "processing",
        sessionId,
        HistoryAction.ProcessingStarted(sessionId, rawEventCount, hasMore)
    )

    fun pageReceived(
        sessionId: String,
        eventsJson: String,
        byteCount: Int,
        hasMore: Boolean,
        nextBeforeSequence: Int?,
        remoteActivityTimestamp: Double?
    ): SharedMviDispatchResult = dispatch("page", sessionId) {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        val page = wireJson.decodeFromString<List<SessionEvent>>(eventsJson)
        require(page.all { it.sessionId == sessionId }) { "history page contains another session" }
        require(remoteActivityTimestamp == null || remoteActivityTimestamp.isFinite()) {
            "remoteActivityTimestamp must be finite"
        }
        val oldEvents = eventsBySession[sessionId].orEmpty()
        val merged = mergeHistoryPage(page, oldEvents)
        val reduction = HistoryReducer.reduce(
            state,
            HistoryAction.PageCommitted(
                sessionId = sessionId,
                eventCount = page.size,
                byteCount = byteCount,
                hasMore = hasMore,
                nextBeforeSequence = nextBeforeSequence,
                earliestLocalSequence = merged.firstOrNull()?.seq,
                remoteActivityTimestamp = remoteActivityTimestamp
            ),
            configuration
        )
        Transition(
            state = reduction.state,
            eventsBySession = if (merged == oldEvents) eventsBySession else eventsBySession + (sessionId to merged),
            eventPatch = if (merged == oldEvents) null else SharedHistoryEventPatch(
                kind = "replace",
                replacementEvents = merged
            ),
            result = reduction.result
        )
    }

    fun liveEventReceived(eventJson: String): SharedMviDispatchResult = dispatch("live", null) {
        val record = wireJson.decodeFromString<SessionEvent>(eventJson)
        require(record.sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(record.time.isFinite()) { "event time must be finite" }
        val oldEvents = eventsBySession[record.sessionId].orEmpty()
        val merged = HistoryEventMerger.merge(record, oldEvents)
        val index = merged.events.binarySearchBy(record.seq) { it.seq }
        check(index >= 0) { "merged event missing" }
        val reduction = HistoryReducer.reduce(
            state,
            HistoryAction.LiveEventReceived(record.sessionId, record.time),
            configuration
        )
        Transition(
            state = reduction.state,
            eventsBySession = eventsBySession + (record.sessionId to merged.events),
            eventPatch = SharedHistoryEventPatch(
                kind = if (merged.replacedOrInsertedOutOfOrder) "upsert" else "append",
                record = record,
                index = index
            ),
            result = reduction.result,
            sessionId = record.sessionId
        )
    }

    fun timedOut(sessionId: String): SharedMviDispatchResult =
        reduce("timeout", sessionId, HistoryAction.TimedOut(sessionId))

    fun cancelled(sessionId: String): SharedMviDispatchResult =
        reduce("cancel", sessionId, HistoryAction.Cancelled(sessionId))

    fun clearSession(sessionId: String): SharedMviDispatchResult = dispatch("clear", sessionId) {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        val nextSessions = state.sessions - sessionId
        val nextState = state.copy(
            sessions = nextSessions,
            pendingSessionId = state.pendingSessionId?.takeUnless { it == sessionId }
        )
        val hadEvents = sessionId in eventsBySession
        Transition(
            state = nextState,
            eventsBySession = eventsBySession - sessionId,
            eventPatch = if (hadEvents) SharedHistoryEventPatch(
                kind = "replace",
                replacementEvents = emptyList()
            ) else null,
            result = HistoryResult.None
        )
    }

    private fun reduce(
        operation: String,
        sessionId: String,
        action: HistoryAction
    ): SharedMviDispatchResult = dispatch(operation, sessionId) {
        val reduction = HistoryReducer.reduce(state, action, configuration)
        Transition(state = reduction.state, eventsBySession = eventsBySession, result = reduction.result)
    }

    private inline fun dispatch(
        operation: String,
        fallbackSessionId: String?,
        block: () -> Transition
    ): SharedMviDispatchResult = try {
        val transition = block()
        val sessionId = transition.sessionId ?: fallbackSessionId
        require(!sessionId.isNullOrBlank()) { "sessionId must not be blank" }
        if (transition.state == state && transition.eventsBySession == eventsBySession && transition.result == HistoryResult.None) {
            return SharedMviDispatchResult(true, null, null)
        }
        val patch = SharedHistoryPatch(
            sessionId = sessionId,
            session = transition.state.sessions[sessionId],
            pendingSessionId = transition.state.pendingSessionId,
            pendingSessionChanged = transition.state.pendingSessionId != state.pendingSessionId,
            eventPatch = transition.eventPatch,
            outcome = transition.result.outcome(),
            failureCode = (transition.result as? HistoryResult.Failed)?.code?.name,
            completedEventCount = (transition.result as? HistoryResult.Completed)?.eventCount,
            completedByteCount = (transition.result as? HistoryResult.Completed)?.byteCount,
            completedHasMore = (transition.result as? HistoryResult.Completed)?.hasMore
        )
        val effect = transition.result.effect(sessionId)
        val statePayload = wireJson.encodeToString(patch)
        val effectsPayload = wireJson.encodeToString(effect?.let(::listOf).orEmpty())
        state = transition.state
        eventsBySession = transition.eventsBySession
        val event = events.emitTransition(
            transactionId = "history-$operation:${events.currentSequence + 1}",
            statePayloadJson = statePayload,
            effectsJson = effectsPayload
        )
        SharedMviDispatchResult(true, event.transactionId, event.sequence)
    } catch (error: Throwable) {
        val code = "history-$operation-failed"
        val message = error.message ?: error::class.simpleName ?: "unknown-error"
        val event = events.emitError("$code:${events.currentSequence + 1}", code, message)
        SharedMviDispatchResult(false, event.transactionId, event.sequence, code, message)
    }

    private data class Transition(
        val state: HistoryState,
        val eventsBySession: Map<String, List<SessionEvent>>,
        val eventPatch: SharedHistoryEventPatch? = null,
        val result: HistoryResult,
        val sessionId: String? = null
    )

    private fun mergeHistoryPage(
        page: List<SessionEvent>,
        live: List<SessionEvent>
    ): List<SessionEvent> {
        val records = linkedMapOf<Int, SessionEvent>()
        page.forEach { records[it.seq] = it }
        // 实时 lane 胜出，避免晚到 history 覆盖已收到的最终/增量事件。
        live.forEach { records[it.seq] = it }
        return records.values.sortedBy(SessionEvent::seq)
    }

    private fun HistoryResult.effect(sessionId: String): SharedHistoryEffect? = when (this) {
        is HistoryResult.RequestPage -> SharedHistoryEffect("request-page", sessionId, beforeSequence)
        else -> null
    }

    private fun HistoryResult.outcome(): String = when (this) {
        HistoryResult.None -> "none"
        is HistoryResult.RequestPage -> "request-page"
        HistoryResult.Stopped -> "stopped"
        is HistoryResult.Completed -> "completed"
        is HistoryResult.Failed -> "failed"
    }
}
