package com.clarklevis.dsh.shared.facade

import com.clarklevis.dsh.shared.gateway.GatewayRequest
import com.clarklevis.dsh.shared.gateway.GatewayRequests
import com.clarklevis.dsh.shared.protocol.GatewayFrame
import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder
import com.clarklevis.dsh.shared.protocol.WorkspaceChangesSummary
import com.clarklevis.dsh.shared.protocol.WorkspaceChangesDiff
import com.clarklevis.dsh.shared.protocol.WorkspaceDiffRow

data class WorkspaceFilePreview(val index: Int, val diff: WorkspaceChangesDiff, val rows: List<WorkspaceDiffRow>)

data class SharedWorkspaceChangesSnapshot(
    val sessionId: String? = null,
    val seq: Int? = null,
    val turn: Int? = null,
    val supported: Boolean = false,
    val connected: Boolean = false,
    val summary: WorkspaceChangesSummary? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val selectedIndex: Int? = null,
    val diff: WorkspaceChangesDiff? = null,
    val rows: List<WorkspaceDiffRow> = emptyList(),
    val diffLoading: Boolean = false,
    val diffError: String? = null,
    val previews: List<WorkspaceFilePreview> = emptyList()
) {
    val visible: Boolean get() = supported && seq != null && (summary == null || summary.total > 0)
}

data class SharedWorkspaceChangesTransition(
    val snapshot: SharedWorkspaceChangesSnapshot,
    val requests: List<GatewayRequest> = emptyList()
)

/** 每个网关独立持有；仅保留当前摘要和有界的 diff 缓存，避免大文件常驻内存。 */
class SharedWorkspaceChangesStore {
    private var state = SharedWorkspaceChangesSnapshot()
    private val latest = linkedMapOf<String, Pair<Int, Int>>() // session → (seq, turn)
    private var ordinal = 0L
    private var summaryId: String? = null
    private var diffId: String? = null

    fun snapshot(): SharedWorkspaceChangesSnapshot = state

    fun open(sessionId: String?, supported: Boolean, connected: Boolean): SharedWorkspaceChangesTransition {
        val coordinate = latest[sessionId]
        val changed = sessionId != state.sessionId || coordinate?.first != state.seq
        if (changed) {
            summaryId = null
            diffId = null
            state = SharedWorkspaceChangesSnapshot(sessionId, coordinate?.first, coordinate?.second)
        }
        if (!connected || !supported) {
            summaryId = null
            diffId = null
            state = state.copy(
                error = if (state.loading) "disconnected" else state.error,
                diffError = if (state.diffLoading) "disconnected" else state.diffError,
                loading = false, diffLoading = false
            )
        }
        val reconnected = connected && !state.connected
        state = state.copy(supported = supported, connected = connected)
        return if (state.summary == null && state.seq != null && !state.loading &&
            (changed || reconnected || state.error == null)) retrySummary() else result()
    }

    fun retrySummary(): SharedWorkspaceChangesTransition {
        val session = state.sessionId ?: return result()
        val seq = state.seq ?: return result()
        if (!state.connected || !state.supported || state.loading) return result()
        summaryId = "changes-summary-${++ordinal}"
        state = state.copy(loading = true, error = null)
        return result(listOf(GatewayRequests.workspaceChanges(session, seq, null, requireNotNull(summaryId))))
    }

    fun selectFile(index: Int): SharedWorkspaceChangesTransition {
        if (index !in state.summary?.files.orEmpty().indices || !state.connected || !state.supported) return result()
        state.previews.firstOrNull { it.index == index }?.let {
            diffId = null
            state = state.copy(selectedIndex = index, diff = it.diff, rows = it.rows, diffLoading = false, diffError = null)
            return result()
        }
        diffId = "changes-diff-${++ordinal}"
        state = state.copy(selectedIndex = index, diff = null, rows = emptyList(), diffLoading = true, diffError = null)
        return result(listOf(GatewayRequests.workspaceChanges(requireNotNull(state.sessionId),
            requireNotNull(state.seq), index, requireNotNull(diffId))))
    }

    fun closeDiff(): SharedWorkspaceChangesTransition {
        diffId = null
        state = state.copy(selectedIndex = null, diff = null, rows = emptyList(), diffLoading = false, diffError = null, previews = emptyList())
        return result()
    }

    fun requestFailed(requestId: String): SharedWorkspaceChangesTransition = fail(requestId, "request-timeout")

    fun acceptJson(json: String): SharedWorkspaceChangesTransition = accept(GatewayWireDecoder.decode(json))

    fun accept(frame: GatewayFrame): SharedWorkspaceChangesTransition {
        val session = frame.sessionId
        if (session != null) {
            val events = when (frame.kind) {
                "event" -> listOfNotNull(frame.event?.let { event -> frame.seq?.let { seq -> seq to event } })
                "history", "session-snapshot" -> frame.events.orEmpty().filter { it.type == "workspace/changes" }.map { it.seq to it.normalized(session).event }
                else -> emptyList()
            }
            events.forEach { (seq, event) ->
                if (event.type == "workspace/changes" && event.turn != null && seq >= 0 &&
                    seq > (latest[session]?.first ?: -1)) {
                    latest[session] = seq to event.turn
                    if (latest.size > 64) latest.remove(latest.keys.first())
                }
            }
            if (session == state.sessionId && latest[session]?.first != state.seq) {
                return open(session, state.supported, state.connected)
            }
        }
        val id = frame.requestId ?: return result()
        if (frame.kind == "error" && (id == summaryId || id == diffId)) {
            // requestId 是必要条件；错误帧也必须来自原会话/坐标。
            if (session != state.sessionId || frame.seq != state.seq) return result()
            return fail(id, frame.code ?: "workspace-changes-failed")
        }
        if (session != state.sessionId || frame.seq != state.seq) return result()
        when {
            frame.kind == "workspace-changes-summary" && id == summaryId -> {
                summaryId = null
                state = state.copy(summary = frame.summary, loading = false,
                    error = if (frame.summary == null) "invalid-response" else null)
            }
            frame.kind == "workspace-changes-diff" && id == diffId && frame.index == state.selectedIndex -> {
                diffId = null
                val rows = frame.diff?.rows().orEmpty()
                var previews = state.previews
                frame.diff?.let { diff ->
                    previews = previews.filter { it.index != frame.index } + WorkspaceFilePreview(requireNotNull(frame.index), diff, rows)
                    // 字符与行对象均有成本；仅保留有界的最近预览，驱逐后允许重新查询。
                    while (previews.size > 1 && previews.sumOf { preview -> preview.rows.sumOf { it.text.length.toLong() + 80L } } > 8_000_000L) {
                        previews = previews.drop(1)
                    }
                }
                state = state.copy(diff = frame.diff, rows = rows, diffLoading = false, previews = previews,
                    diffError = if (frame.diff == null) "invalid-response" else null)
            }
        }
        return result()
    }

    private fun fail(id: String, code: String): SharedWorkspaceChangesTransition {
        if (id == summaryId) {
            summaryId = null
            state = state.copy(loading = false, error = code)
        }
        if (id == diffId) {
            diffId = null
            state = state.copy(diffLoading = false, diffError = code)
        }
        return result()
    }

    private fun result(requests: List<GatewayRequest> = emptyList()) = SharedWorkspaceChangesTransition(state, requests)
}
