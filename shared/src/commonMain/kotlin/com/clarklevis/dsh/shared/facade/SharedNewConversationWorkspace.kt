package com.clarklevis.dsh.shared.facade

/** 新会话归属独立于首页筛选；第一条指令提交后不可再改。 */
data class NewConversationWorkspaceSnapshot(
    val sessionId: String? = null,
    val workspaceId: String? = null,
    val previousSessionId: String? = null,
    val changing: Boolean = false,
    val locked: Boolean = false
) {
    val visible: Boolean get() = sessionId != null && !locked
}

class SharedNewConversationWorkspace {
    private var state = NewConversationWorkspaceSnapshot()
    private var ordinal = 0L
    private var pending: Pair<Long, String?>? = null
    fun snapshot(): NewConversationWorkspaceSnapshot = state
    fun open(sessionId: String?, workspaceId: String?) {
        pending = null
        state = NewConversationWorkspaceSnapshot(sessionId, workspaceId)
    }
    fun begin(workspaceId: String?): Long {
        if (!state.visible || state.changing || state.workspaceId == workspaceId) return 0
        pending = ++ordinal to workspaceId
        state = state.copy(changing = true)
        return ordinal
    }
    fun complete(token: Long, sessionId: String): Boolean {
        val target = pending?.takeIf { it.first == token } ?: return false
        pending = null
        state = NewConversationWorkspaceSnapshot(sessionId, target.second, state.sessionId)
        return true
    }
    fun fail(token: Long) {
        if (pending?.first != token) return
        pending = null
        state = state.copy(changing = false)
    }
    fun lock(sessionId: String?) {
        if (state.sessionId != sessionId) return
        pending = null
        state = state.copy(changing = false, locked = true)
    }
    fun clear() { pending = null; state = NewConversationWorkspaceSnapshot() }
}
