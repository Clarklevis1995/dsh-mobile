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
    val replacementEvents: List<SessionEvent>? = null,
    /**
     * True when this patch's `record` is a live assistant delta that continues the
     * row the journal already holds at this `seq`, rather than a structural
     * replacement of it. `kind` stays `"upsert"` so consumers that do not know the
     * marker keep their existing (correct, if slower) upsert handling; defaulted so
     * older payloads and hand-written fixtures decode unchanged.
     */
    val streamDelta: Boolean = false
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

    /** 订阅快照承担首屏历史请求；仅发布加载状态，不额外发起普通分页请求。 */
    fun awaitSnapshot(sessionId: String): SharedMviDispatchResult =
        reduce("await-snapshot", sessionId, HistoryAction.AwaitSnapshot(sessionId))

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
        // Whether this record continues a live assistant row is decided from the
        // record that occupied the sequence BEFORE the merge. Reading it back out
        // of `merged.events` would compare the incoming record with itself and
        // make the predicate meaningless. A non-null `previousEvent` is exactly
        // "the record superseded a sequence that already existed" — which is why
        // the merge result needs no extra flag for this decision.
        val previousIndex = oldEvents.binarySearchBy(record.seq) { it.seq }
        val previousEvent = if (previousIndex >= 0) oldEvents[previousIndex].event else null
        // A same-sequence record is a live delta only when it supersedes another
        // transient chunk of the SAME turn and step. Anything else — the
        // authoritative `assistant/message`, a chunk from another turn or step, or
        // a gap fill — must keep the full rebaseline path. `chunkType` is
        // deliberately not compared: reasoning, text, tool, block, usage and
        // finish frames legitimately transition while sharing turn, step and seq.
        // Duplicate delivery is still indistinguishable from a legitimately
        // repeated fragment; that needs the host's per-chunk identity and is out of
        // scope here.
        val streamDelta = previousEvent != null &&
            previousEvent.type == "assistant/chunk" &&
            record.event.type == "assistant/chunk" &&
            previousEvent.turn == record.event.turn &&
            previousEvent.step == record.event.step
        val reduction = HistoryReducer.reduce(
            state,
            HistoryAction.LiveEventReceived(record.sessionId, record.time),
            configuration
        )
        Transition(
            state = reduction.state,
            eventsBySession = eventsBySession + (record.sessionId to merged.events),
            eventPatch = SharedHistoryEventPatch(
                // `replacedOrInsertedOutOfOrder` means the merge was not a plain
                // append. A record that landed on its OWN sequence is the ordinary
                // shape of a live turn — DSH gives every chunk of a turn the same
                // `session.seq` — so it is published as an upsert. A record
                // inserted at a position it did not occupy is a gap fill. The
                // `streamDelta` marker is what tells the platform this particular
                // upsert continues a live assistant row and may be folded into it
                // instead of triggering a rebaseline.
                kind = if (merged.replacedOrInsertedOutOfOrder) "upsert" else "append",
                record = record,
                index = index,
                streamDelta = streamDelta
            ),
            result = reduction.result,
            sessionId = record.sessionId
        )
    }

    /** 原子安装订阅窗口。显式 cursor 属于独立流状态，不能从精简 events 推导。 */
    fun installSnapshot(sessionId: String, eventsJson: String, hasMore: Boolean,
        nextBeforeSequence: Int?): SharedMviDispatchResult = dispatch("snapshot", sessionId) {
        val records = wireJson.decodeFromString<List<SessionEvent>>(eventsJson)
        require(records.all { it.sessionId == sessionId })
        val normalized = records.associateBy(SessionEvent::seq).values.sortedBy(SessionEvent::seq)
        Transition(
            state = state.copy(
                sessions = state.sessions + (sessionId to HistorySessionState(
                    hasMore = hasMore, nextBeforeSequence = nextBeforeSequence
                )),
                pendingSessionId = state.pendingSessionId?.takeUnless { it == sessionId }
            ),
            eventsBySession = eventsBySession + (sessionId to normalized),
            eventPatch = SharedHistoryEventPatch("replace", replacementEvents = normalized),
            result = HistoryResult.None
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
        if (transition.state == state && transition.eventsBySession == eventsBySession && transition.result == HistoryResult.None && transition.eventPatch == null) {
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
