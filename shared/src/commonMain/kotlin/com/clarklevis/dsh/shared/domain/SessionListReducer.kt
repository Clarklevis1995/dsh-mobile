package com.clarklevis.dsh.shared.domain

import com.clarklevis.dsh.shared.protocol.GatewaySessionSummary
import com.clarklevis.dsh.shared.protocol.SessionEvent

data class SessionSummary(
    val id: String,
    val title: String,
    val lastActivityEpochSeconds: Double,
    val isRunning: Boolean,
    val hasUnread: Boolean,
    val agentPreset: String? = null
)

data class SessionListLabels(
    val newSessionTitle: String = "New session",
    val remoteSessionPrefix: String = "Remote session ",
    val blankSessionPrefix: String = "Blank session "
) {
    fun remoteTitle(id: String): String = remoteSessionPrefix + id.take(8)
    fun blankTitle(id: String): String = blankSessionPrefix + id.take(8)
}

data class SessionListState(
    val sessions: List<SessionSummary> = emptyList(),
    val archivedSessionIds: Set<String> = emptySet(),
    val selectedSessionId: String? = null
)

sealed interface SessionListAction {
    data class Select(val sessionId: String?) : SessionListAction
    data class SetArchivedSessionIds(val sessionIds: Set<String>) : SessionListAction
    data class RemoteSessionsReceived(val sessions: List<GatewaySessionSummary>) : SessionListAction
    data class MessageSent(
        val sessionId: String,
        val agentPreset: String?,
        val insertedAtEpochSeconds: Double
    ) : SessionListAction
    data class KnownSessionAdded(
        val sessionId: String,
        val insertedAtEpochSeconds: Double
    ) : SessionListAction
    data class EventReceived(
        val event: SessionEvent,
        val insertedAtEpochSeconds: Double
    ) : SessionListAction
    data class MarkRead(val sessionId: String) : SessionListAction
}

object SessionListReducer {
    fun reduce(
        state: SessionListState,
        action: SessionListAction,
        labels: SessionListLabels = SessionListLabels()
    ): SessionListState = when (action) {
        is SessionListAction.Select -> state.copy(selectedSessionId = action.sessionId)
        is SessionListAction.SetArchivedSessionIds -> state.copy(archivedSessionIds = action.sessionIds)
        is SessionListAction.RemoteSessionsReceived -> mergeRemoteSessions(state, action.sessions, labels)
        is SessionListAction.MessageSent -> {
            val sessions = upsert(
                state.sessions,
                action.sessionId,
                labels.newSessionTitle,
                labels,
                action.insertedAtEpochSeconds
            ).map {
                if (it.id == action.sessionId) it.copy(agentPreset = action.agentPreset) else it
            }
            state.copy(
                sessions = sessions,
                selectedSessionId = state.selectedSessionId ?: action.sessionId
            )
        }
        is SessionListAction.KnownSessionAdded -> state.copy(
            sessions = upsert(
                state.sessions,
                action.sessionId,
                labels.remoteTitle(action.sessionId),
                labels,
                action.insertedAtEpochSeconds
            )
        )
        is SessionListAction.EventReceived -> applyEvent(
            state,
            action.event,
            labels,
            action.insertedAtEpochSeconds
        )
        is SessionListAction.MarkRead -> state.copy(
            sessions = state.sessions.map {
                if (it.id == action.sessionId) it.copy(hasUnread = false) else it
            }
        )
    }

    private fun mergeRemoteSessions(
        state: SessionListState,
        remote: List<GatewaySessionSummary>,
        labels: SessionListLabels
    ): SessionListState {
        val sessions = state.sessions.associateBy(SessionSummary::id).toMutableMap()
        remote.filterNot { it.sessionId in state.archivedSessionIds }.forEach { item ->
            val fallback = item.cwd?.trimEnd('/')?.substringAfterLast('/')?.takeIf(String::isNotEmpty)
            val title = item.projectedTitle ?: fallback ?: labels.remoteTitle(item.sessionId)
            val timestamp = normalizeEpochSeconds(item.updatedAt)
            val existing = sessions[item.sessionId]
            sessions[item.sessionId] = existing?.copy(
                title = title,
                lastActivityEpochSeconds = timestamp,
                isRunning = item.running,
                agentPreset = item.agentPreset ?: existing.agentPreset
            ) ?: SessionSummary(
                id = item.sessionId,
                title = if (item.blank) labels.blankTitle(item.sessionId) else title,
                lastActivityEpochSeconds = timestamp,
                isRunning = item.running,
                hasUnread = false,
                agentPreset = item.agentPreset
            )
        }
        return state.copy(sessions = sessions.values
            .filterNot { it.id in state.archivedSessionIds }
            .sortedByDescending(SessionSummary::lastActivityEpochSeconds))
    }

    private fun applyEvent(
        state: SessionListState,
        record: SessionEvent,
        labels: SessionListLabels,
        insertedAtEpochSeconds: Double
    ): SessionListState {
        val event = record.event
        val title = when (event.type) {
            "user/message" -> event.text?.take(28)
            "session/title" -> event.text
            else -> null
        }
        var sessions = upsert(state.sessions, record.sessionId, title, labels, insertedAtEpochSeconds)
        val updatesMetadata = event.type in setOf(
            "user/message", "session/title", "turn/start", "turn/end", "assistant/message"
        )
        if (!updatesMetadata) return state.copy(sessions = sessions)
        sessions = sessions.map { session ->
            if (session.id != record.sessionId) return@map session
            session.copy(
                lastActivityEpochSeconds = normalizeEpochSeconds(record.time),
                isRunning = when (event.type) {
                    "turn/start" -> true
                    "turn/end" -> false
                    else -> session.isRunning
                },
                hasUnread = state.selectedSessionId != record.sessionId
            )
        }.sortedByDescending(SessionSummary::lastActivityEpochSeconds)
        return state.copy(sessions = sessions)
    }

    private fun upsert(
        sessions: List<SessionSummary>,
        id: String,
        title: String?,
        labels: SessionListLabels,
        insertedAtEpochSeconds: Double
    ): List<SessionSummary> {
        val index = sessions.indexOfFirst { it.id == id }
        if (index < 0) {
            return listOf(
                SessionSummary(
                    id,
                    title ?: labels.remoteTitle(id),
                    insertedAtEpochSeconds,
                    false,
                    false
                )
            ) + sessions
        }
        val existing = sessions[index]
        val canReplace = existing.title == labels.newSessionTitle ||
            existing.title.startsWith(labels.remoteSessionPrefix)
        if (title.isNullOrEmpty() || !canReplace) return sessions
        return sessions.toMutableList().apply { this[index] = existing.copy(title = title) }
    }
}

internal fun normalizeEpochSeconds(value: Double): Double =
    if (value > 10_000_000_000) value / 1_000 else value
