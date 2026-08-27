package com.clarklevis.dsh.shared.sync

import com.clarklevis.dsh.shared.protocol.SessionEvent
import kotlinx.serialization.Serializable

@Serializable
data class HistorySyncConfiguration(
    val pageMessageLimit: Int = 60,
    val pageByteBudget: Int = 4 * 1024 * 1024,
    val pagesPerBatch: Int = 2
)

@Serializable data class HistoryLoadProgress(val loaded: Int, val total: Int?)
@Serializable enum class HistoryBatchKind { LATEST, OLDER }
@Serializable enum class HistoryFailureCode { MISSING_NEXT_CURSOR, REPEATED_CURSOR }

@Serializable
data class HistorySessionState(
    val hasMore: Boolean = false,
    val isLoading: Boolean = false,
    val isLoadingOlder: Boolean = false,
    val progress: HistoryLoadProgress? = null,
    val seenCursors: Set<Int> = emptySet(),
    val nextBeforeSequence: Int? = null,
    val pageCount: Int = 0,
    val batchKind: HistoryBatchKind? = null,
    val loadedEventCount: Int = 0,
    val loadedByteCount: Int = 0,
    val syncedActivityTimestamp: Double? = null
)

@Serializable
data class HistoryState(
    val sessions: Map<String, HistorySessionState> = emptyMap(),
    val pendingSessionId: String? = null
)

sealed interface HistoryAction {
    data class Start(val sessionId: String, val older: Boolean, val hasLocalEvents: Boolean, val earliestLocalSequence: Int?) : HistoryAction
    data class ProcessingStarted(val sessionId: String, val rawEventCount: Int, val hasMore: Boolean) : HistoryAction
    data class PageCommitted(
        val sessionId: String,
        val eventCount: Int,
        val byteCount: Int,
        val hasMore: Boolean,
        val nextBeforeSequence: Int?,
        val earliestLocalSequence: Int?,
        val remoteActivityTimestamp: Double?
    ) : HistoryAction
    data class LiveEventReceived(val sessionId: String, val activityTimestamp: Double) : HistoryAction
    data class TimedOut(val sessionId: String) : HistoryAction
    data class Cancelled(val sessionId: String) : HistoryAction
}

sealed interface HistoryResult {
    data object None : HistoryResult
    data class RequestPage(val beforeSequence: Int?) : HistoryResult
    data object Stopped : HistoryResult
    data class Completed(val eventCount: Int, val byteCount: Int, val hasMore: Boolean) : HistoryResult
    data class Failed(val code: HistoryFailureCode, val cursor: Int? = null) : HistoryResult
}

data class HistoryReduction(val state: HistoryState, val result: HistoryResult)

object HistoryReducer {
    fun reduce(
        state: HistoryState,
        action: HistoryAction,
        configuration: HistorySyncConfiguration = HistorySyncConfiguration()
    ): HistoryReduction = when (action) {
        is HistoryAction.Start -> start(state, action)
        is HistoryAction.ProcessingStarted -> {
            val session = state.sessions[action.sessionId]
            if (session?.isLoading != true) unchanged(state) else update(
                state,
                action.sessionId,
                session.copy(progress = HistoryLoadProgress(session.loadedEventCount, if (action.hasMore) null else session.loadedEventCount + action.rawEventCount))
            )
        }
        is HistoryAction.PageCommitted -> commit(state, action, configuration)
        is HistoryAction.LiveEventReceived -> {
            val session = state.sessions[action.sessionId]
            val synced = session?.syncedActivityTimestamp
            if (session == null || synced == null) unchanged(state) else update(
                state,
                action.sessionId,
                session.copy(syncedActivityTimestamp = maxOf(synced, action.activityTimestamp))
            )
        }
        is HistoryAction.TimedOut -> stop(state, action.sessionId)
        is HistoryAction.Cancelled -> stop(state, action.sessionId)
    }

    private fun start(state: HistoryState, action: HistoryAction.Start): HistoryReduction {
        var session = state.sessions[action.sessionId] ?: HistorySessionState()
        if (session.isLoading || (action.older && !session.hasMore)) return unchanged(state)
        val before = if (action.older) session.nextBeforeSequence ?: action.earliestLocalSequence else null
        session = session.copy(
            isLoading = true,
            isLoadingOlder = action.older,
            progress = HistoryLoadProgress(0, null),
            seenCursors = before?.let(::setOf).orEmpty(),
            pageCount = 0,
            batchKind = if (action.older) HistoryBatchKind.OLDER else HistoryBatchKind.LATEST,
            loadedEventCount = 0,
            loadedByteCount = 0,
            hasMore = if (!action.older && !action.hasLocalEvents) false else session.hasMore,
            nextBeforeSequence = if (!action.older && !action.hasLocalEvents) null else session.nextBeforeSequence
        )
        if (action.older && before == null) {
            return update(state, action.sessionId, finish(session).copy(hasMore = false), HistoryResult.Stopped)
        }
        return HistoryReduction(
            state.copy(
                sessions = state.sessions + (action.sessionId to session),
                pendingSessionId = action.sessionId
            ),
            HistoryResult.RequestPage(before)
        )
    }

    private fun commit(
        state: HistoryState,
        action: HistoryAction.PageCommitted,
        configuration: HistorySyncConfiguration
    ): HistoryReduction {
        var session = state.sessions[action.sessionId]
        if (session?.isLoading != true) return unchanged(state)
        session = session.copy(
            loadedEventCount = session.loadedEventCount + action.eventCount,
            loadedByteCount = session.loadedByteCount + action.byteCount,
            hasMore = action.hasMore,
            pageCount = session.pageCount + 1,
            progress = HistoryLoadProgress(session.loadedEventCount + action.eventCount, if (action.hasMore) null else session.loadedEventCount + action.eventCount),
            nextBeforeSequence = when {
                action.hasMore && action.earliestLocalSequence != null -> action.earliestLocalSequence
                action.nextBeforeSequence != null -> action.nextBeforeSequence
                !action.hasMore -> null
                else -> session.nextBeforeSequence
            }
        )
        if (action.hasMore && session.pageCount < configuration.pagesPerBatch) {
            val cursor = action.nextBeforeSequence ?: return fail(state, action.sessionId, session, HistoryFailureCode.MISSING_NEXT_CURSOR)
            if (cursor in session.seenCursors) return fail(state, action.sessionId, session, HistoryFailureCode.REPEATED_CURSOR, cursor)
            session = session.copy(seenCursors = session.seenCursors + cursor)
            return update(state, action.sessionId, session, HistoryResult.RequestPage(cursor))
        }
        if (session.batchKind == HistoryBatchKind.LATEST && action.remoteActivityTimestamp != null) {
            session = session.copy(syncedActivityTimestamp = action.remoteActivityTimestamp)
        }
        val result = HistoryResult.Completed(session.loadedEventCount, session.loadedByteCount, session.hasMore)
        return update(state, action.sessionId, finish(session), result, clearPending = true)
    }

    private fun stop(state: HistoryState, id: String): HistoryReduction {
        val session = state.sessions[id] ?: return unchanged(state)
        return update(state, id, finish(session), HistoryResult.Stopped, clearPending = true)
    }

    private fun fail(
        state: HistoryState,
        id: String,
        session: HistorySessionState,
        code: HistoryFailureCode,
        cursor: Int? = null
    ) = update(
        state,
        id,
        finish(session).copy(hasMore = false, nextBeforeSequence = null),
        HistoryResult.Failed(code, cursor),
        clearPending = true
    )

    private fun finish(session: HistorySessionState) = session.copy(
        isLoading = false,
        isLoadingOlder = false,
        progress = null,
        seenCursors = emptySet(),
        pageCount = 0,
        batchKind = null,
        loadedEventCount = 0,
        loadedByteCount = 0
    )

    private fun update(
        state: HistoryState,
        id: String,
        session: HistorySessionState,
        result: HistoryResult = HistoryResult.None,
        clearPending: Boolean = false
    ) = HistoryReduction(
        state.copy(
            sessions = state.sessions + (id to session),
            pendingSessionId = if (clearPending && state.pendingSessionId == id) null else state.pendingSessionId
        ),
        result
    )

    private fun unchanged(state: HistoryState) = HistoryReduction(state, HistoryResult.None)
}

data class HistoryEventMergeResult(val events: List<SessionEvent>, val replacedOrInsertedOutOfOrder: Boolean)

object HistoryEventMerger {
    fun merge(record: SessionEvent, events: List<SessionEvent>): HistoryEventMergeResult {
        if (events.lastOrNull()?.seq?.let { record.seq > it } != false) {
            return HistoryEventMergeResult(events + record, false)
        }
        var low = 0
        var high = events.size
        while (low < high) {
            val middle = (low + high) / 2
            if (events[middle].seq < record.seq) low = middle + 1 else high = middle
        }
        val result = events.toMutableList()
        if (low < result.size && result[low].seq == record.seq) result[low] = record else result.add(low, record)
        return HistoryEventMergeResult(result, true)
    }
}
