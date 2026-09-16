package com.clarklevis.dsh.shared.facade

import com.clarklevis.dsh.shared.gateway.GatewayRequest
import com.clarklevis.dsh.shared.gateway.GatewayRequests
import com.clarklevis.dsh.shared.protocol.GatewayAgentPreset
import com.clarklevis.dsh.shared.protocol.GatewayFrame
import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder

/** 每个 Gateway 独立持有；只允许当前页面的最新请求提交状态。 */
data class SharedSessionAgentPresetSnapshot(
    val sessionId: String? = null,
    val supported: Boolean = false,
    val connected: Boolean = false,
    val agentPreset: String? = null,
    val locked: Boolean = false,
    val known: Boolean = false,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val submitting: Boolean = false,
    val presets: List<GatewayAgentPreset> = emptyList(),
    val catalogLoaded: Boolean = false,
    val modeSelectionEnabled: Boolean = true
) {
    val visible: Boolean get() = supported && sessionId != null && modeSelectionEnabled && !locked && !submitting
    val canSelect: Boolean get() = visible && connected && known && catalogLoaded && !loading && !saving
    val blocksSending: Boolean get() = supported && sessionId != null && (saving || submitting || (!locked && !known))
}

data class SharedSessionAgentPresetTransition(
    val snapshot: SharedSessionAgentPresetSnapshot,
    val requests: List<GatewayRequest> = emptyList(),
    val refreshCommands: Boolean = false,
    val invalidSession: Boolean = false,
    val error: String? = null
)

class SharedSessionAgentPresetStore {
    private var state = SharedSessionAgentPresetSnapshot()
    private var ordinal = 0L
    private var queryId: String? = null
    private var selectionId: String? = null
    private var presetRevision = 0L
    private var queryRevision = 0L
    private var selectionRevision = 0L
    private var lastSequence = -1L
    private val lockedSessions = mutableSetOf<String>()

    fun snapshot(): SharedSessionAgentPresetSnapshot = state

    fun open(sessionId: String?, supported: Boolean, connected: Boolean): SharedSessionAgentPresetTransition {
        val sameSession = state.sessionId == sessionId
        // 同一页的模型/权限刷新不能打断正在保存的模式。
        if (sameSession && (state.saving || state.submitting) && state.connected && connected) return result()
        queryId = null
        selectionId = null
        if (!sameSession) { presetRevision = 0; lastSequence = -1 }
        state = state.copy(
            sessionId = sessionId, supported = supported, connected = connected,
            agentPreset = state.agentPreset.takeIf { sameSession },
            locked = sessionId in lockedSessions, known = false, loading = false,
            saving = false, submitting = state.submitting && sameSession,
            catalogLoaded = false
        )
        val query = query()
        return query.copy(requests = query.requests + if (supported && connected && sessionId != null) {
            listOf(GatewayRequests.simple("agent-presets"))
        } else emptyList())
    }

    fun leave(): SharedSessionAgentPresetTransition {
        queryId = null
        selectionId = null
        state = state.copy(sessionId = null, known = false, saving = false, loading = false, submitting = false)
        return result()
    }

    fun disconnected(): SharedSessionAgentPresetTransition {
        queryId = null
        selectionId = null
        state = state.copy(connected = false, known = false, saving = false, loading = false)
        return result()
    }

    fun query(): SharedSessionAgentPresetTransition {
        val sessionId = state.sessionId ?: return result()
        if (!state.connected || !state.supported || state.saving) return result()
        queryId = "preset-state-${++ordinal}"
        queryRevision = presetRevision
        state = state.copy(known = false, loading = true)
        return result(listOf(GatewayRequests.sessionAgentPreset(sessionId, requireNotNull(queryId))))
    }

    fun select(presetId: String): SharedSessionAgentPresetTransition {
        if (!state.canSelect || presetId == state.agentPreset ||
            state.presets.none { it.id == presetId && it.broken != true }) return result()
        selectionId = "preset-select-${++ordinal}"
        selectionRevision = presetRevision
        queryId = null
        state = state.copy(saving = true, loading = false)
        return result(listOf(GatewayRequests.selectAgentPreset(requireNotNull(state.sessionId), presetId, requireNotNull(selectionId))))
    }

    /** 先提交本地禁用状态，再执行网络发送；失败只能通过查询恢复。 */
    fun beginMessage(): SharedSessionAgentPresetTransition {
        if (!state.supported || state.locked || state.blocksSending) return result()
        queryId = null
        state = state.copy(submitting = true, loading = false)
        return result()
    }

    fun requestFailed(type: String, sessionId: String?, requestId: String?, message: String?): SharedSessionAgentPresetTransition {
        if (sessionId != state.sessionId || sessionId == null) return result()
        if (type in setOf("message", "command-execute") && state.submitting) return query()
        if (type == "select-agent-preset" && requestId != null && requestId == selectionId) {
            selectionId = null
            state = state.copy(saving = false, known = false)
            return query().copy(error = message ?: "模式切换未确认，请重试")
        }
        if (type == "session-agent-preset" && requestId != null && requestId == queryId) {
            queryId = null
            state = state.copy(loading = false, known = false, submitting = false)
            return result(error = message ?: "无法读取当前模式，请重试")
        }
        return result()
    }

    fun acceptJson(json: String): SharedSessionAgentPresetTransition = accept(GatewayWireDecoder.decode(json))

    fun accept(frame: GatewayFrame): SharedSessionAgentPresetTransition {
        if (frame.kind == "agent-presets") {
            state = state.copy(
                presets = frame.presets.orEmpty(), catalogLoaded = frame.presets != null,
                modeSelectionEnabled = frame.modeSelectionEnabled != false
            )
            return result()
        }
        val id = frame.sessionId ?: return result()
        if (frame.kind == "sent" || (frame.kind == "session-agent-preset-updated" && frame.locked == true)) {
            lockedSessions.add(id)
        }
        if (id != state.sessionId) return result()
        when (frame.kind) {
            "command-executed" -> if (state.submitting) return query()
            "sent" -> {
                queryId = null
                state = state.copy(locked = true, submitting = false, loading = false)
            }
            "session-agent-preset-updated" -> {
                // seq 不连续，仅用于拒绝旧值；锁定是单向事实，独立合并。
                if (frame.locked == true) state = state.copy(locked = true, submitting = false)
                val sequence = frame.seq?.toLong()
                if (sequence != null && sequence <= lastSequence) return result()
                if (sequence != null) lastSequence = sequence
                frame.agentPreset?.let {
                    presetRevision++
                    state = state.copy(agentPreset = it)
                    return result(refreshCommands = true)
                }
            }
            "session-agent-preset" -> {
                if (frame.requestId == null || frame.requestId != queryId) return result()
                queryId = null
                if (frame.locked == true) lockedSessions.add(id)
                val previousPreset = state.agentPreset
                state = state.copy(
                    agentPreset = if (queryRevision == presetRevision) frame.agentPreset ?: state.agentPreset else state.agentPreset,
                    locked = id in lockedSessions,
                    known = frame.locked != null && !frame.agentPreset.isNullOrBlank(),
                    loading = false, submitting = false
                )
                return result(refreshCommands = previousPreset != state.agentPreset)
            }
            "select-agent-preset" -> {
                if (frame.requestId == null || frame.requestId != selectionId) return result()
                selectionId = null
                state = state.copy(
                    agentPreset = if (selectionRevision == presetRevision) frame.agentPreset ?: state.agentPreset else state.agentPreset,
                    saving = false
                )
                return if (frame.agentPreset.isNullOrBlank()) query() else result(refreshCommands = true)
            }
            "error" -> {
                val type = frame.requestType ?: return result()
                if (type in setOf("message", "command-execute")) return requestFailed(type, id, null, frame.message)
                val matches = when (type) {
                    "session-agent-preset" -> frame.requestId != null && frame.requestId == queryId
                    "select-agent-preset" -> frame.requestId != null && frame.requestId == selectionId
                    else -> false
                }
                if (!matches) return result()
                queryId = null
                selectionId = null
                state = state.copy(loading = false, saving = false, submitting = if (type == "session-agent-preset") false else state.submitting)
                val error = frame.message ?: frame.code ?: "模式操作失败"
                return when (frame.code) {
                    "agent-preset/locked" -> {
                        lockedSessions.add(id)
                        state = state.copy(locked = true, submitting = false)
                        result(error = error)
                    }
                    "session/not-found" -> leave().copy(invalidSession = true, error = error)
                    "agent-preset/not-found", "agent-preset/invalid" -> {
                        if (type == "session-agent-preset") state = state.copy(known = false)
                        result(requests = listOf(GatewayRequests.simple("agent-presets")), error = error)
                    }
                    else -> {
                        state = state.copy(known = false)
                        if (type == "select-agent-preset") query().copy(error = error) else result(error = error)
                    }
                }
            }
            "history", "session-snapshot" -> {
                // 投影可恢复名称，绝不以事件数量推断可编辑性。
                val preset = frame.projections?.get("values")?.get("agentPreset")?.stringValue
                val sequence = frame.projections?.get("asOfSeq")?.doubleValue?.toLong()
                if (!state.known && !state.saving && !state.submitting && presetRevision == 0L &&
                    preset != null && sequence != null && sequence > lastSequence) {
                    lastSequence = sequence
                    val changed = state.agentPreset != preset
                    state = state.copy(agentPreset = preset)
                    return result(refreshCommands = changed)
                }
            }
        }
        return result()
    }

    private fun result(
        requests: List<GatewayRequest> = emptyList(),
        refreshCommands: Boolean = false,
        error: String? = null
    ) = SharedSessionAgentPresetTransition(state, requests, refreshCommands, error = error)
}
