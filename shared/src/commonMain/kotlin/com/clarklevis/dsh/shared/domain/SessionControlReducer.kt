package com.clarklevis.dsh.shared.domain

import com.clarklevis.dsh.shared.protocol.GatewayAgentPreset
import com.clarklevis.dsh.shared.protocol.GatewayContextBreakdown
import com.clarklevis.dsh.shared.protocol.GatewayContextPressure
import com.clarklevis.dsh.shared.protocol.GatewayContextSnapshot
import com.clarklevis.dsh.shared.protocol.GatewayModelCatalog
import com.clarklevis.dsh.shared.protocol.GatewayModelGroup
import com.clarklevis.dsh.shared.protocol.GatewayModelSelection
import com.clarklevis.dsh.shared.protocol.GatewaySessionPermissions
import com.clarklevis.dsh.shared.protocol.GatewaySessionStats
import com.clarklevis.dsh.shared.protocol.GatewaySessionStatsSnapshot
import com.clarklevis.dsh.shared.protocol.GatewaySessionTokenUsage
import com.clarklevis.dsh.shared.protocol.GatewaySessionTokenUsageTotals
import com.clarklevis.dsh.shared.protocol.GatewayTokenUsage

data class SessionControlState(
    val modelCatalogs: Map<String, GatewayModelCatalog> = emptyMap(),
    val globalModelCatalog: GatewayModelCatalog? = null,
    val sessionPermissions: Map<String, GatewaySessionPermissions> = emptyMap(),
    val contextSnapshots: Map<String, GatewayContextSnapshot> = emptyMap(),
    val sessionStatsSnapshots: Map<String, GatewaySessionStatsSnapshot> = emptyMap(),
    val agentPresets: List<GatewayAgentPreset> = emptyList(),
    val agentPresetsAuthorable: Boolean = false,
    val agentPresetsHasDocument: Boolean = false,
    val agentPresetDefault: String? = null,
    val permissionDefault: String? = null,
    val defaultModelSelection: GatewayModelSelection? = null,
    val loadingKinds: Set<String> = emptySet(),
    val defaultConfigurationLoadingKinds: Set<String> = emptySet(),
    val pendingModelsSessionId: String? = null,
    val isPendingGlobalModelsRequest: Boolean = false,
    val pendingModelSelectionSessionId: String? = null,
    val pendingPermissionOptionsSessionId: String? = null
)

sealed interface SessionControlAction {
    data class AgentPresetsReceived(val presets: List<GatewayAgentPreset>, val authorable: Boolean, val hasDocument: Boolean) : SessionControlAction
    data class DefaultsReceived(val agentPreset: String?, val permission: String?) : SessionControlAction
    data class DefaultModelReceived(val selection: GatewayModelSelection?) : SessionControlAction
    data class GlobalDefaultApplied(val target: String, val value: String) : SessionControlAction
    data class ModelsReceived(
        val sessionId: String?,
        val current: GatewayModelSelection?,
        val routable: Boolean,
        val groups: List<GatewayModelGroup>,
        val isGlobalRequest: Boolean
    ) : SessionControlAction
    data class ModelSelected(val sessionId: String, val selection: GatewayModelSelection) : SessionControlAction
    data class PermissionsReceived(val sessionId: String, val permissions: GatewaySessionPermissions) : SessionControlAction
    data class PermissionSelected(val sessionId: String, val value: String) : SessionControlAction
    data class ContextReceived(
        val sessionId: String,
        val asOfSequence: Int?,
        val tokenUsage: GatewayTokenUsage?,
        val pressure: GatewayContextPressure?,
        val breakdown: GatewayContextBreakdown?
    ) : SessionControlAction
    data class StatsReceived(
        val sessionId: String,
        val asOfSequence: Int?,
        val stats: GatewaySessionStats?,
        val tokenUsageTotals: GatewaySessionTokenUsageTotals?,
        val contextPressure: GatewayContextPressure?
    ) : SessionControlAction
    data class RequestStarted(val kind: String) : SessionControlAction
    data class RequestFinished(val kind: String) : SessionControlAction
    data class RequestTimedOut(val kind: String) : SessionControlAction
    data class DefaultRequestStarted(val kind: String) : SessionControlAction
    data class DefaultRequestFinished(val kind: String) : SessionControlAction
    data class DefaultRequestTimedOut(val kind: String) : SessionControlAction
    data class ModelsRequestTargeted(val sessionId: String?) : SessionControlAction
    data class ModelSelectionTargeted(val sessionId: String) : SessionControlAction
    data object ModelSelectionResolved : SessionControlAction
    data class PermissionOptionsTargeted(val sessionId: String) : SessionControlAction
    data object PermissionOptionsResolved : SessionControlAction
}

object SessionControlReducer {
    private val supportedPermissionPresets = setOf("read-only", "workspace-write", "danger-full-access")

    fun reduce(state: SessionControlState, action: SessionControlAction): SessionControlState = when (action) {
        is SessionControlAction.AgentPresetsReceived -> state.copy(
            agentPresets = action.presets,
            agentPresetsAuthorable = action.authorable,
            agentPresetsHasDocument = action.hasDocument,
            agentPresetDefault = state.agentPresetDefault ?: action.presets.firstOrNull { it.isDefault }?.id
        )
        is SessionControlAction.DefaultsReceived -> state.copy(agentPresetDefault = action.agentPreset, permissionDefault = action.permission)
        is SessionControlAction.DefaultModelReceived -> state.copy(defaultModelSelection = action.selection)
        is SessionControlAction.GlobalDefaultApplied -> when (action.target) {
            "agent-preset" -> state.copy(agentPresetDefault = action.value)
            "permission" -> state.copy(permissionDefault = action.value)
            else -> state
        }
        is SessionControlAction.ModelsReceived -> {
            val catalog = GatewayModelCatalog(action.current, action.routable, action.groups)
            when {
                action.sessionId != null -> state.copy(modelCatalogs = state.modelCatalogs + (action.sessionId to catalog))
                action.isGlobalRequest -> state.copy(globalModelCatalog = GatewayModelCatalog(null, false, action.groups))
                else -> state
            }
        }
        is SessionControlAction.ModelSelected -> {
            val old = state.modelCatalogs[action.sessionId] ?: GatewayModelCatalog(null, true, emptyList())
            state.copy(modelCatalogs = state.modelCatalogs + (action.sessionId to old.copy(current = action.selection)))
        }
        is SessionControlAction.PermissionsReceived -> state.copy(
            sessionPermissions = state.sessionPermissions + (
                action.sessionId to action.permissions.copy(
                    options = action.permissions.options.orEmpty().filter { it.value in supportedPermissionPresets }
                )
            )
        )
        is SessionControlAction.PermissionSelected -> {
            val old = state.sessionPermissions[action.sessionId] ?: GatewaySessionPermissions()
            state.copy(sessionPermissions = state.sessionPermissions + (
                action.sessionId to old.copy(currentValue = action.value, preset = action.value)
            ))
        }
        is SessionControlAction.ContextReceived -> {
            val old = state.contextSnapshots[action.sessionId] ?: GatewayContextSnapshot()
            state.copy(contextSnapshots = state.contextSnapshots + (
                action.sessionId to old.copy(
                    asOfSeq = action.asOfSequence ?: old.asOfSeq,
                    tokenUsage = action.tokenUsage ?: old.tokenUsage,
                    pressure = action.pressure ?: old.pressure,
                    breakdown = action.breakdown ?: old.breakdown
                )
            ))
        }
        is SessionControlAction.StatsReceived -> {
            val old = state.sessionStatsSnapshots[action.sessionId] ?: GatewaySessionStatsSnapshot()
            state.copy(sessionStatsSnapshots = state.sessionStatsSnapshots + (
                action.sessionId to old.copy(
                    asOfSeq = action.asOfSequence ?: old.asOfSeq,
                    stats = action.stats ?: old.stats,
                    tokenUsage = action.tokenUsageTotals?.let(::GatewaySessionTokenUsage) ?: old.tokenUsage,
                    contextPressure = action.contextPressure ?: old.contextPressure
                )
            ))
        }
        is SessionControlAction.RequestStarted -> state.copy(loadingKinds = state.loadingKinds + action.kind)
        is SessionControlAction.RequestFinished -> finishRequest(state, action.kind)
        is SessionControlAction.RequestTimedOut -> finishRequest(state, action.kind)
        is SessionControlAction.DefaultRequestStarted -> state.copy(defaultConfigurationLoadingKinds = state.defaultConfigurationLoadingKinds + action.kind)
        is SessionControlAction.DefaultRequestFinished -> state.copy(defaultConfigurationLoadingKinds = state.defaultConfigurationLoadingKinds - action.kind)
        is SessionControlAction.DefaultRequestTimedOut -> state.copy(defaultConfigurationLoadingKinds = state.defaultConfigurationLoadingKinds - action.kind)
        is SessionControlAction.ModelsRequestTargeted -> state.copy(
            pendingModelsSessionId = action.sessionId,
            isPendingGlobalModelsRequest = action.sessionId == null
        )
        is SessionControlAction.ModelSelectionTargeted -> state.copy(pendingModelSelectionSessionId = action.sessionId)
        SessionControlAction.ModelSelectionResolved -> state.copy(pendingModelSelectionSessionId = null)
        is SessionControlAction.PermissionOptionsTargeted -> state.copy(pendingPermissionOptionsSessionId = action.sessionId)
        SessionControlAction.PermissionOptionsResolved -> state.copy(pendingPermissionOptionsSessionId = null)
    }

    private fun finishRequest(state: SessionControlState, kind: String): SessionControlState = state.copy(
        loadingKinds = state.loadingKinds - kind,
        pendingModelsSessionId = if (kind == "models") null else state.pendingModelsSessionId,
        isPendingGlobalModelsRequest = if (kind == "models") false else state.isPendingGlobalModelsRequest
    )
}
