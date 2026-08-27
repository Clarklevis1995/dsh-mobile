package com.clarklevis.dsh.shared.facade

import com.clarklevis.dsh.shared.domain.SessionListAction
import com.clarklevis.dsh.shared.domain.SessionListLabels
import com.clarklevis.dsh.shared.domain.SessionListReducer
import com.clarklevis.dsh.shared.domain.SessionListState
import com.clarklevis.dsh.shared.domain.SessionSummary
import com.clarklevis.dsh.shared.protocol.GatewaySessionSummary
import com.clarklevis.dsh.shared.protocol.SessionEvent
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** Swift/Objective-C 只消费的稳定会话值，不暴露 Reducer 内部可变实现。 */
@Serializable
data class SharedSessionSummarySnapshot(
    val id: String,
    val title: String,
    val lastActivityEpochSeconds: Double,
    val isRunning: Boolean,
    val hasUnread: Boolean,
    val agentPreset: String? = null
)

/** iOS 发布和持久化适配器所需的完整会话列表快照。 */
@Serializable
data class SharedSessionListSnapshot(
    val sessions: List<SharedSessionSummarySnapshot> = emptyList(),
    val archivedSessionIds: List<String> = emptyList(),
    val selectedSessionId: String? = null
)

/**
 * 所有 Kotlin 异常都在桥接边界内转换，禁止越过 Swift 调用栈。
 * 成功但状态未变化时 snapshotJson 为 null，避免流式事件重复复制完整会话列表。
 */
data class SharedSessionListResult(
    val snapshotJson: String?,
    val errorCode: String?,
    val errorMessage: String?
) {
    val isSuccess: Boolean get() = errorCode == null
}

/**
 * iOS SessionList 的单一业务状态来源。
 *
 * Swift 负责 MainActor 串行提交、ObservableObject 发布、UserDefaults 与网络 I/O；
 * 本类只持有领域状态并运行纯 Reducer。
 */
class SharedSessionListStore(
    newSessionTitle: String,
    remoteSessionPrefix: String,
    blankSessionPrefix: String
) {
    private val labels = SessionListLabels(
        newSessionTitle = newSessionTitle,
        remoteSessionPrefix = remoteSessionPrefix,
        blankSessionPrefix = blankSessionPrefix
    )
    private var state = SessionListState()
    private val events = SharedMviEventEmitter("session-list")

    fun subscribe(observer: SharedMviEventObserver): SharedMviSubscription = try {
        events.subscribe(observer, wireJson.encodeToString(state.toSnapshot()))
    } catch (error: Throwable) {
        events.subscribeError(
            observer,
            "session-list-subscribe-failed",
            error.message ?: error::class.simpleName ?: "unknown-error"
        )
    }

    fun snapshot(): SharedSessionListResult = try {
        snapshotResult(state)
    } catch (error: Throwable) {
        failure("snapshot", error)
    }

    fun restore(snapshotJson: String): SharedSessionListResult = mutate("restore", alwaysSnapshot = true) {
        val snapshot = wireJson.decodeFromString<SharedSessionListSnapshot>(snapshotJson)
        SessionListState(
            sessions = snapshot.sessions.map(SharedSessionSummarySnapshot::toDomain),
            archivedSessionIds = snapshot.archivedSessionIds.toSet(),
            selectedSessionId = snapshot.selectedSessionId
        )
    }

    fun selectSession(sessionId: String?): SharedSessionListResult = reduce(
        "select",
        SessionListAction.Select(sessionId)
    )

    fun setArchivedSessionIds(sessionIdsJson: String): SharedSessionListResult = mutate("archive") {
        val sessionIds = wireJson.decodeFromString<List<String>>(sessionIdsJson).toSet()
        SessionListReducer.reduce(state, SessionListAction.SetArchivedSessionIds(sessionIds), labels)
    }

    fun receiveRemoteSessions(sessionsJson: String): SharedSessionListResult = mutate("remote-sessions") {
        val sessions = wireJson.decodeFromString<List<GatewaySessionSummary>>(sessionsJson)
        SessionListReducer.reduce(state, SessionListAction.RemoteSessionsReceived(sessions), labels)
    }

    fun messageSent(
        sessionId: String,
        agentPreset: String?,
        insertedAtEpochSeconds: Double
    ): SharedSessionListResult = reduce(
        "message-sent",
        SessionListAction.MessageSent(sessionId, agentPreset, insertedAtEpochSeconds)
    )

    fun addKnownSession(
        sessionId: String,
        insertedAtEpochSeconds: Double
    ): SharedSessionListResult = reduce(
        "known-session",
        SessionListAction.KnownSessionAdded(sessionId, insertedAtEpochSeconds)
    )

    fun receiveEvent(
        eventJson: String,
        insertedAtEpochSeconds: Double
    ): SharedSessionListResult = mutate("event") {
        val event = wireJson.decodeFromString<SessionEvent>(eventJson)
        SessionListReducer.reduce(
            state,
            SessionListAction.EventReceived(event, insertedAtEpochSeconds),
            labels
        )
    }

    fun markRead(sessionId: String): SharedSessionListResult = reduce(
        "mark-read",
        SessionListAction.MarkRead(sessionId)
    )

    private fun reduce(operation: String, action: SessionListAction): SharedSessionListResult =
        mutate(operation) { SessionListReducer.reduce(state, action, labels) }

    private inline fun mutate(
        operation: String,
        alwaysSnapshot: Boolean = false,
        transform: () -> SessionListState
    ): SharedSessionListResult = try {
        val next = transform()
        val changed = next != state
        if (changed || alwaysSnapshot) {
            // 先完成可能失败的序列化，再提交状态，保证桥接写入具备原子性。
            val result = snapshotResult(next)
            state = next
            events.emitTransition(
                transactionId = "$operation:${events.currentSequence + 1}",
                statePayloadJson = result.snapshotJson
            )
            result
        } else {
            unchanged()
        }
    } catch (error: Throwable) {
        failure(operation, error)
    }

    private fun snapshotResult(value: SessionListState): SharedSessionListResult = SharedSessionListResult(
        snapshotJson = wireJson.encodeToString(value.toSnapshot()),
        errorCode = null,
        errorMessage = null
    )

    private fun failure(operation: String, error: Throwable): SharedSessionListResult {
        val code = "session-list-$operation-failed"
        val message = error.message ?: error::class.simpleName ?: "unknown-error"
        events.emitError("$code:${events.currentSequence + 1}", code, message)
        return SharedSessionListResult(
            snapshotJson = null,
            errorCode = code,
            errorMessage = message
        )
    }

    private fun unchanged(): SharedSessionListResult = SharedSessionListResult(
        snapshotJson = null,
        errorCode = null,
        errorMessage = null
    )
}

private fun SharedSessionSummarySnapshot.toDomain(): SessionSummary = SessionSummary(
    id = id,
    title = title,
    lastActivityEpochSeconds = lastActivityEpochSeconds,
    isRunning = isRunning,
    hasUnread = hasUnread,
    agentPreset = agentPreset
)

private fun SessionSummary.toSnapshot(): SharedSessionSummarySnapshot = SharedSessionSummarySnapshot(
    id = id,
    title = title,
    lastActivityEpochSeconds = lastActivityEpochSeconds,
    isRunning = isRunning,
    hasUnread = hasUnread,
    agentPreset = agentPreset
)

private fun SessionListState.toSnapshot(): SharedSessionListSnapshot = SharedSessionListSnapshot(
    sessions = sessions.map(SessionSummary::toSnapshot),
    archivedSessionIds = archivedSessionIds.sorted(),
    selectedSessionId = selectedSessionId
)
