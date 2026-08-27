package com.clarklevis.dsh.shared.facade

import com.clarklevis.dsh.shared.domain.SessionControlAction
import com.clarklevis.dsh.shared.domain.SessionControlReducer
import com.clarklevis.dsh.shared.domain.SessionControlRequestTarget
import com.clarklevis.dsh.shared.domain.SessionControlState
import com.clarklevis.dsh.shared.protocol.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** Swift effect 层应且仅应执行一次的 SessionControl I/O 描述。 */
@Serializable
data class SharedSessionControlEffect(
    val kind: String,
    val requestKey: String,
    val requestToken: String,
    val sessionId: String? = null,
    val provider: String? = null,
    val model: String? = null,
    val reasoningEffort: String? = null,
    val target: String? = null,
    val value: String? = null
)

data class SharedSessionControlResult(
    val snapshotJson: String?,
    val effectsJson: String,
    val errorCode: String?,
    val errorMessage: String?,
    /** 当前输入是否被 reducer 接受；迟到/无法关联响应为 false。 */
    val applied: Boolean = false,
    /** 当前输入是否真正提交了新快照。 */
    val committed: Boolean = false,
    /** 仅在当前输入原子结束一个 active generation 时提供。 */
    val completedKind: String? = null,
    val completedRequestToken: String? = null
) {
    val isSuccess: Boolean get() = errorCode == null
}

/**
 * SessionControl 唯一业务状态源。网关不回显 request token，因此同 kind 严格串行：
 * 正在飞行时只保留最新的不同 target，完成/失败/超时与启动 queued 在同一事务中完成。
 *
 * 正常成功后的同 target 刷新依赖 Gateway 的协议约束：每个请求只返回一个终态响应。
 * 因为响应不携带 token，客户端无法区分违反该约束的重复终态响应与下一代正常响应；
 * quarantine 只用于超时/失败或已知存在迟到歧义的切换，不永久封锁正常刷新。
 */
class SharedSessionControlStore {
    private var state = SessionControlState()
    private var nextRequestOrdinal = 0L

    fun snapshot(): SharedSessionControlResult = try {
        success(state, emptyList(), alwaysSnapshot = true, commit = false)
    } catch (error: Throwable) {
        failure("snapshot", error)
    }

    fun requestModels(sessionId: String?, isConnected: Boolean) = request(
        SessionControlRequestTarget("models", false, sessionId = sessionId), isConnected
    )
    fun requestPermissionOptions(sessionId: String, isConnected: Boolean) = request(
        SessionControlRequestTarget("permission-options", false, sessionId = sessionId), isConnected
    )
    fun requestContextUsage(sessionId: String, isConnected: Boolean) = request(
        SessionControlRequestTarget("context-usage", false, sessionId = sessionId), isConnected
    )
    fun requestSessionStats(sessionId: String, isConnected: Boolean) = request(
        SessionControlRequestTarget("session-stats", false, sessionId = sessionId), isConnected
    )
    fun requestAgentPresets(isConnected: Boolean) = request(SessionControlRequestTarget("agent-presets", true), isConnected)
    fun requestDefaults(isConnected: Boolean) = request(SessionControlRequestTarget("defaults", true), isConnected)
    fun requestDefaultModel(isConnected: Boolean) = request(SessionControlRequestTarget("default-model", true), isConnected)

    fun selectModel(sessionId: String, selectionJson: String, isConnected: Boolean): SharedSessionControlResult = try {
        val selection = wireJson.decodeFromString<GatewayModelSelection>(selectionJson)
        request(
            SessionControlRequestTarget(
                "select-model", false, sessionId, selection.provider, selection.model, selection.reasoningEffort
            ), isConnected
        )
    } catch (error: Throwable) { failure("select-model", error) }

    fun setPermission(sessionId: String, value: String, isConnected: Boolean): SharedSessionControlResult {
        if (value !in supportedPermissionPresets) return rejected("unsupported-permission", value)
        return request(SessionControlRequestTarget("permission", false, sessionId = sessionId, value = value), isConnected)
    }

    fun saveDefaultModel(selectionJson: String, isConnected: Boolean): SharedSessionControlResult = try {
        val selection = wireJson.decodeFromString<GatewayModelSelection>(selectionJson)
        request(
            SessionControlRequestTarget(
                "save-default-model", true, provider = selection.provider, model = selection.model,
                reasoningEffort = selection.reasoningEffort
            ), isConnected
        )
    } catch (error: Throwable) { failure("save-default-model", error) }

    fun setDefault(target: String, value: String, isConnected: Boolean): SharedSessionControlResult {
        when (target) {
            "permission" -> if (value !in supportedDefaultPermissionPresets) {
                return rejected("unsupported-default-permission", value)
            }
            "agent-preset" -> if (state.agentPresets.none { it.id == value && it.broken != true }) {
                return rejected("invalid-agent-preset", value)
            }
            else -> return rejected("unsupported-default-target", target)
        }
        return request(SessionControlRequestTarget("set-default", true, target = target, value = value), isConnected)
    }

    // received API 只处理网关响应；提交前必须匹配 active kind + target。
    fun agentPresetsReceived(presetsJson: String, authorable: Boolean, hasDocument: Boolean) =
        decodeResponse("agent-presets", null) {
            SessionControlAction.AgentPresetsReceived(
                wireJson.decodeFromString<List<GatewayAgentPreset>>(presetsJson), authorable, hasDocument
            )
        }

    fun defaultsReceived(agentPreset: String?, permission: String?) = response(
        "defaults", null, SessionControlAction.DefaultsReceived(agentPreset, permission)
    )

    fun defaultModelReceived(selectionJson: String?) = decodeResponse("default-model", null) {
        SessionControlAction.DefaultModelReceived(selectionJson?.let(wireJson::decodeFromString))
    }

    fun defaultModelSaved(selectionJson: String?) = decodeResponse("save-default-model", null) {
        val selection = selectionJson?.let { wireJson.decodeFromString<GatewayModelSelection>(it) }
        val active = state.activeRequestTargets["save-default-model"]
        if (selection == null || active?.provider != selection.provider || active.model != selection.model ||
            active.reasoningEffort != selection.reasoningEffort
        ) null else SessionControlAction.DefaultModelReceived(selection)
    }

    fun globalDefaultApplied(target: String, value: String) = response(
        "set-default", SessionControlRequestTarget("set-default", true, target = target, value = value),
        SessionControlAction.GlobalDefaultApplied(target, value)
    )

    fun modelsReceived(
        sessionId: String?, currentJson: String?, routable: Boolean, groupsJson: String,
        isGlobalRequest: Boolean
    ) = decodeResponse("models", sessionId?.let { SessionControlRequestTarget("models", false, sessionId = it) }) {
        val active = state.activeRequestTargets["models"] ?: return@decodeResponse null
        val boundSession = sessionId ?: active.sessionId
        SessionControlAction.ModelsReceived(
            boundSession, currentJson?.let(wireJson::decodeFromString), routable,
            wireJson.decodeFromString<List<GatewayModelGroup>>(groupsJson),
            active.sessionId == null || isGlobalRequest
        )
    }

    fun modelSelected(sessionId: String?, selectionJson: String) = decodeResponse(
        "select-model", sessionId?.let { SessionControlRequestTarget("select-model", false, sessionId = it) }
    ) {
        val active = state.activeRequestTargets["select-model"] ?: return@decodeResponse null
        val selection = wireJson.decodeFromString<GatewayModelSelection>(selectionJson)
        if (selection.provider != active.provider || selection.model != active.model ||
            selection.reasoningEffort != active.reasoningEffort
        ) null else SessionControlAction.ModelSelected(sessionId ?: active.sessionId ?: return@decodeResponse null, selection)
    }

    fun permissionsReceived(sessionId: String?, permissionsJson: String) = decodeResponse(
        "permission-options", sessionId?.let { SessionControlRequestTarget("permission-options", false, sessionId = it) }
    ) {
        val active = state.activeRequestTargets["permission-options"] ?: return@decodeResponse null
        val permissions = decodeValidPermissions(permissionsJson)
        SessionControlAction.PermissionsReceived(sessionId ?: active.sessionId ?: return@decodeResponse null, permissions)
    }

    fun permissionSelected(sessionId: String?, value: String): SharedSessionControlResult {
        val active = state.activeRequestTargets["permission"] ?: return unchanged()
        if (sessionId != null && sessionId != active.sessionId) return unchanged()
        if (active.value != value) return unchanged()
        if (value !in supportedPermissionPresets) return rejected("invalid-permission-state", value)
        return response(
            "permission", sessionId?.let { SessionControlRequestTarget("permission", false, sessionId = it) },
            SessionControlAction.PermissionSelected(sessionId ?: active.sessionId ?: return unchanged(), value)
        )
    }

    fun contextReceived(
        sessionId: String?, asOfSequence: Long?, tokenUsageJson: String?, pressureJson: String?,
        breakdownJson: String?
    ) = decodeResponse(
        "context-usage", sessionId?.let { SessionControlRequestTarget("context-usage", false, sessionId = it) }
    ) {
        val active = state.activeRequestTargets["context-usage"] ?: return@decodeResponse null
        SessionControlAction.ContextReceived(
            sessionId ?: active.sessionId ?: return@decodeResponse null, asOfSequence,
            tokenUsageJson?.let { wireJson.decodeFromString<GatewayTokenUsage>(it) },
            pressureJson?.let { wireJson.decodeFromString<GatewayContextPressure>(it) },
            breakdownJson?.let { wireJson.decodeFromString<GatewayContextBreakdown>(it) }
        )
    }

    fun statsReceived(
        sessionId: String?, asOfSequence: Long?, statsJson: String?, tokenUsageTotalsJson: String?,
        contextPressureJson: String?
    ) = decodeResponse(
        "session-stats", sessionId?.let { SessionControlRequestTarget("session-stats", false, sessionId = it) }
    ) {
        val active = state.activeRequestTargets["session-stats"] ?: return@decodeResponse null
        SessionControlAction.StatsReceived(
            sessionId ?: active.sessionId ?: return@decodeResponse null, asOfSequence,
            statsJson?.let { wireJson.decodeFromString<GatewaySessionStats>(it) },
            tokenUsageTotalsJson?.let { wireJson.decodeFromString<GatewaySessionTokenUsageTotals>(it) },
            contextPressureJson?.let { wireJson.decodeFromString<GatewayContextPressure>(it) }
        )
    }

    // history/event 投影不是 request/response，只合并状态，不触碰 active identity。
    fun mergeContextProjection(
        sessionId: String, asOfSequence: Long?, tokenUsageJson: String?, pressureJson: String?,
        breakdownJson: String?
    ) = decodeAndReduce("merge-context-projection") {
        SessionControlAction.ContextReceived(
            sessionId, asOfSequence,
            tokenUsageJson?.let { wireJson.decodeFromString<GatewayTokenUsage>(it) },
            pressureJson?.let { wireJson.decodeFromString<GatewayContextPressure>(it) },
            breakdownJson?.let { wireJson.decodeFromString<GatewayContextBreakdown>(it) }
        )
    }

    fun mergePermissionsProjection(sessionId: String, permissionsJson: String) =
        decodeAndReduce("merge-permissions-projection") {
            SessionControlAction.PermissionsReceived(
                sessionId, decodeValidPermissions(permissionsJson)
            )
        }

    fun mergeModelProjection(sessionId: String, selectionJson: String) = decodeAndReduce("merge-model-projection") {
        SessionControlAction.ModelSelected(sessionId, wireJson.decodeFromString(selectionJson))
    }

    fun mergePermissionProjection(sessionId: String, value: String): SharedSessionControlResult {
        if (value !in supportedPermissionPresets) return rejected("invalid-permission-state", value)
        return reduce("merge-permission-projection", SessionControlAction.PermissionSelected(sessionId, value))
    }

    fun requestFinished(kind: String, isDefault: Boolean, requestToken: String?) =
        terminate(kind, isDefault, requestToken)
    fun requestTimedOut(kind: String, isDefault: Boolean, requestToken: String?) =
        terminate(kind, isDefault, requestToken)
    fun requestFailed(kind: String, isDefault: Boolean, requestToken: String?) =
        terminate(kind, isDefault, requestToken)
    fun requestsDisconnected(): SharedSessionControlResult = mutate("requests-disconnected") {
        Transition(synchronizePendingTargets(state.copy(
            loadingKinds = emptySet(),
            defaultConfigurationLoadingKinds = emptySet(),
            requestTokens = emptyMap(),
            activeRequestTargets = emptyMap(),
            queuedRequestTargets = emptyMap(),
            previousCompletedRequestTargets = emptyMap(),
            explicitSessionRequiredKinds = emptySet(),
            quarantinedRequestKinds = emptySet()
        )), applied = true)
    }

    private fun request(target: SessionControlRequestTarget, isConnected: Boolean): SharedSessionControlResult {
        if (!isConnected) return rejected("not-connected", target.kind)
        if (!target.hasCompletePayload()) return rejected("invalid-request-target", target.kind)
        val active = state.activeRequestTargets[target.kind]
        if (active != null) {
            if (active == target) {
                // latest-wins：A -> queued B -> request A 表示用户重新选择 A，必须撤销 B。
                if (state.queuedRequestTargets[target.kind] == null) return unchanged()
                return mutate("cancel-queued-${target.kind}") {
                    Transition(
                        state.copy(queuedRequestTargets = state.queuedRequestTargets - target.kind),
                        applied = true
                    )
                }
            }
            if (state.queuedRequestTargets[target.kind] == target) return unchanged()
            return mutate("queue-${target.kind}") {
                Transition(
                    state.copy(queuedRequestTargets = state.queuedRequestTargets + (target.kind to target)),
                    applied = true
                )
            }
        }
        return mutate("request-${target.kind}") { start(state, target) }
    }

    private fun terminate(
        kind: String,
        isDefault: Boolean,
        requestToken: String?
    ): SharedSessionControlResult {
        val active = state.activeRequestTargets[kind] ?: return unchanged()
        if (active.isDefault != isDefault || requestToken == null || state.requestTokens[kind] != requestToken) {
            return unchanged()
        }
        return mutate("terminate-$kind") {
            // Mobile Gateway 的 control 响应不回显客户端 request token，models 与
            // permission-options 也不回显 sessionId。超时/失败只能结束当前 active
            // generation；永久 quarantine 会把一次失败放大成重连前始终不可用。
            // 若用户已切换到 queued target，则在同一事务启动最新 target。
            complete(state, kind, null)
        }
    }

    private fun response(
        kind: String, explicitTarget: SessionControlRequestTarget?, action: SessionControlAction?
    ): SharedSessionControlResult {
        val active = state.activeRequestTargets[kind] ?: return unchanged()
        if (explicitTarget != null && !sameResponseTarget(active, explicitTarget)) return unchanged()
        if (action == null) return unchanged()
        return mutate("response-$kind") { complete(state, kind, action) }
    }

    private inline fun decodeResponse(
        kind: String, explicitTarget: SessionControlRequestTarget?, action: () -> SessionControlAction?
    ): SharedSessionControlResult = try {
        response(kind, explicitTarget, action())
    } catch (error: Throwable) { failure(kind, error) }

    private fun start(base: SessionControlState, target: SessionControlRequestTarget): Transition {
        val token = "${target.kind}:${++nextRequestOrdinal}"
        val next = synchronizePendingTargets(
            base.copy(
                loadingKinds = if (target.isDefault) base.loadingKinds else base.loadingKinds + target.kind,
                defaultConfigurationLoadingKinds = if (target.isDefault) {
                    base.defaultConfigurationLoadingKinds + target.kind
                } else base.defaultConfigurationLoadingKinds,
                requestTokens = base.requestTokens + (target.kind to token),
                activeRequestTargets = base.activeRequestTargets + (target.kind to target),
                queuedRequestTargets = base.queuedRequestTargets - target.kind,
                // 真实 Mobile Gateway 的 models / permission-options 成功帧不会回显
                // sessionId。每个 kind 只有一个 active generation，因此缺失的 sessionId
                // 必须绑定 active target；显式回显且不匹配时仍由 response() 拒绝。
                explicitSessionRequiredKinds = base.explicitSessionRequiredKinds - target.kind,
                quarantinedRequestKinds = base.quarantinedRequestKinds - target.kind
            )
        )
        return Transition(next, listOf(target.effect(token)), applied = true)
    }

    private fun complete(
        base: SessionControlState,
        kind: String,
        action: SessionControlAction?
    ): Transition {
        val completedTarget = base.activeRequestTargets[kind] ?: return Transition(base)
        val completedToken = base.requestTokens[kind] ?: return Transition(base)
        val reduced = action?.let { SessionControlReducer.reduce(base, it) } ?: base
        val cleared = synchronizePendingTargets(
            reduced.copy(
                loadingKinds = reduced.loadingKinds - kind,
                defaultConfigurationLoadingKinds = reduced.defaultConfigurationLoadingKinds - kind,
                requestTokens = reduced.requestTokens - kind,
                activeRequestTargets = reduced.activeRequestTargets - kind,
                previousCompletedRequestTargets = reduced.previousCompletedRequestTargets +
                    (kind to completedTarget),
                explicitSessionRequiredKinds = reduced.explicitSessionRequiredKinds - kind
            )
        )
        val queued = cleared.queuedRequestTargets[kind] ?: return Transition(
            cleared,
            applied = true,
            completedKind = kind,
            completedRequestToken = completedToken
        )
        val started = start(cleared, queued)
        return started.copy(completedKind = kind, completedRequestToken = completedToken)
    }

    private fun synchronizePendingTargets(input: SessionControlState): SessionControlState {
        val models = input.activeRequestTargets["models"]
        return input.copy(
            pendingModelsSessionId = models?.sessionId,
            isPendingGlobalModelsRequest = models != null && models.sessionId == null,
            pendingModelSelectionSessionId = input.activeRequestTargets["select-model"]?.sessionId,
            pendingPermissionOptionsSessionId = input.activeRequestTargets["permission-options"]?.sessionId
        )
    }

    private fun sameResponseTarget(active: SessionControlRequestTarget, response: SessionControlRequestTarget) =
        active.kind == response.kind && active.sessionId == response.sessionId &&
            (response.target == null || active.target == response.target) &&
            (response.value == null || active.value == response.value)

    private fun SessionControlRequestTarget.effect(token: String) = SharedSessionControlEffect(
        kind, kind, token, sessionId, provider, model, reasoningEffort, target, value
    )

    private fun SessionControlRequestTarget.hasCompletePayload(): Boolean = when (kind) {
        "models" -> (sessionId == null || sessionId.isNotBlank()) && provider == null && model == null &&
            reasoningEffort == null && target == null && value == null && !isDefault
        "permission-options", "context-usage", "session-stats" -> sessionId?.isNotBlank() == true &&
            provider == null && model == null && reasoningEffort == null && target == null && value == null && !isDefault
        "agent-presets", "defaults", "default-model" -> sessionId == null && provider == null && model == null &&
            reasoningEffort == null && target == null && value == null && isDefault
        "select-model" -> sessionId?.isNotBlank() == true && provider?.isNotBlank() == true &&
            model?.isNotBlank() == true && target == null && value == null && !isDefault
        "permission" -> sessionId?.isNotBlank() == true && value?.isNotBlank() == true &&
            provider == null && model == null && reasoningEffort == null && target == null && !isDefault
        "save-default-model" -> sessionId == null && provider?.isNotBlank() == true && model?.isNotBlank() == true &&
            target == null && value == null && isDefault
        "set-default" -> sessionId == null && provider == null && model == null && reasoningEffort == null &&
            target in setOf("permission", "agent-preset") && value?.isNotBlank() == true && isDefault
        else -> false
    }

    fun mergeStatsProjection(
        sessionId: String, asOfSequence: Long?, statsJson: String?, tokenUsageTotalsJson: String?,
        contextPressureJson: String?
    ) = decodeAndReduce("merge-stats-projection") {
        SessionControlAction.StatsReceived(
            sessionId, asOfSequence,
            statsJson?.let { wireJson.decodeFromString<GatewaySessionStats>(it) },
            tokenUsageTotalsJson?.let { wireJson.decodeFromString<GatewaySessionTokenUsageTotals>(it) },
            contextPressureJson?.let { wireJson.decodeFromString<GatewayContextPressure>(it) }
        )
    }

    private fun decodeValidPermissions(permissionsJson: String): GatewaySessionPermissions {
        val permissions = wireJson.decodeFromString<GatewaySessionPermissions>(permissionsJson)
        val current = permissions.currentValue ?: permissions.preset
        require(current == null || current in supportedPermissionPresets) {
            "invalid-permission-state:$current"
        }
        return permissions
    }

    private fun reduce(operation: String, action: SessionControlAction) = mutate(operation) {
        Transition(SessionControlReducer.reduce(state, action), applied = true)
    }
    private inline fun decodeAndReduce(operation: String, action: () -> SessionControlAction) = try {
        reduce(operation, action())
    } catch (error: Throwable) { failure(operation, error) }
    private inline fun mutate(operation: String, transform: () -> Transition): SharedSessionControlResult = try {
        val transition = transform()
        val committed = transition.state != state
        success(
            transition.state, transition.effects,
            alwaysSnapshot = transition.effects.isNotEmpty() || committed,
            commit = true,
            applied = transition.applied,
            committed = committed,
            completedKind = transition.completedKind,
            completedRequestToken = transition.completedRequestToken
        )
    } catch (error: Throwable) { failure(operation, error) }
    private fun success(
        next: SessionControlState,
        effects: List<SharedSessionControlEffect>,
        alwaysSnapshot: Boolean,
        commit: Boolean,
        applied: Boolean = false,
        committed: Boolean = false,
        completedKind: String? = null,
        completedRequestToken: String? = null
    ): SharedSessionControlResult {
        val snapshotJson = if (alwaysSnapshot) wireJson.encodeToString(next) else null
        val effectsJson = wireJson.encodeToString(effects)
        if (commit) state = next
        return SharedSessionControlResult(
            snapshotJson, effectsJson, null, null,
            applied, committed, completedKind, completedRequestToken
        )
    }
    private fun unchanged() = SharedSessionControlResult(null, "[]", null, null)
    private fun rejected(code: String, argument: String) = SharedSessionControlResult(null, "[]", code, argument)
    private fun failure(operation: String, error: Throwable) = SharedSessionControlResult(
        null, "[]", "session-control-$operation-failed",
        error.message ?: error::class.simpleName ?: "unknown-error"
    )

    private data class Transition(
        val state: SessionControlState,
        val effects: List<SharedSessionControlEffect> = emptyList(),
        val applied: Boolean = false,
        val completedKind: String? = null,
        val completedRequestToken: String? = null
    )
    private companion object {
        val supportedPermissionPresets = setOf("read-only", "workspace-write", "danger-full-access")
        val supportedDefaultPermissionPresets = supportedPermissionPresets + "ask"
    }
}
