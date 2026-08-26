import Foundation

struct SessionControlState: Equatable {
    var modelCatalogs: [String: GatewayModelCatalog] = [:]
    var globalModelCatalog: GatewayModelCatalog?
    var sessionPermissions: [String: GatewaySessionPermissions] = [:]
    var contextSnapshots: [String: GatewayContextSnapshot] = [:]
    var sessionStatsSnapshots: [String: GatewaySessionStatsSnapshot] = [:]
    var agentPresets: [GatewayAgentPreset] = []
    var agentPresetsAuthorable = false
    var agentPresetsHasDocument = false
    var agentPresetDefault: String?
    var permissionDefault: String?
    var defaultModelSelection: GatewayModelSelection?
    var loadingKinds: Set<String> = []
    var defaultConfigurationLoadingKinds: Set<String> = []
    var pendingModelsSessionID: String?
    var isPendingGlobalModelsRequest = false
    var pendingModelSelectionSessionID: String?
    var pendingPermissionOptionsSessionID: String?
}

enum SessionControlAction {
    case agentPresetsReceived([GatewayAgentPreset], authorable: Bool, hasDocument: Bool)
    case defaultsReceived(agentPreset: String?, permission: String?)
    case defaultModelReceived(GatewayModelSelection?)
    case globalDefaultApplied(target: String, value: String)
    case modelsReceived(
        sessionID: String?,
        current: GatewayModelSelection?,
        routable: Bool,
        groups: [GatewayModelGroup],
        isGlobalRequest: Bool
    )
    case modelSelected(sessionID: String?, selection: GatewayModelSelection)
    case permissionsReceived(sessionID: String?, permissions: GatewaySessionPermissions)
    case permissionSelected(sessionID: String?, value: String)
    case contextReceived(
        sessionID: String?,
        asOfSequence: Int?,
        tokenUsage: GatewayTokenUsage?,
        pressure: GatewayContextPressure?,
        breakdown: GatewayContextBreakdown?
    )
    case statsReceived(
        sessionID: String?,
        asOfSequence: Int?,
        stats: GatewaySessionStats?,
        tokenUsageTotals: GatewaySessionTokenUsageTotals?,
        contextPressure: GatewayContextPressure?
    )
    case requestStarted(String)
    case requestFinished(String)
    case requestTimedOut(String)
    case defaultConfigurationRequestStarted(String)
    case defaultConfigurationRequestFinished(String)
    case defaultConfigurationRequestTimedOut(String)
    case modelsRequestTargeted(sessionID: String?)
    case modelSelectionTargeted(sessionID: String)
    case modelSelectionResolved
    case permissionOptionsTargeted(sessionID: String)
    case permissionOptionsResolved
}

enum SessionControlReducer {
    static func reduce(state: inout SessionControlState, action: SessionControlAction) {
        switch action {
        case .agentPresetsReceived(let presets, let authorable, let hasDocument):
            state.agentPresets = presets
            state.agentPresetsAuthorable = authorable
            state.agentPresetsHasDocument = hasDocument
            if state.agentPresetDefault == nil {
                state.agentPresetDefault = presets.first(where: \.isDefault)?.id
            }
        case .defaultsReceived(let agentPreset, let permission):
            state.agentPresetDefault = agentPreset
            state.permissionDefault = permission
        case .defaultModelReceived(let selection):
            state.defaultModelSelection = selection
        case .globalDefaultApplied(let target, let value):
            if target == "agent-preset" { state.agentPresetDefault = value }
            if target == "permission" { state.permissionDefault = value }
        case .modelsReceived(let sessionID, let current, let routable, let groups, let isGlobalRequest):
            let catalog = GatewayModelCatalog(current: current, routable: routable, groups: groups)
            if let sessionID {
                state.modelCatalogs[sessionID] = catalog
            } else if isGlobalRequest {
                state.globalModelCatalog = GatewayModelCatalog(current: nil, routable: false, groups: groups)
            }
        case .modelSelected(let sessionID, let selection):
            guard let sessionID else { return }
            var catalog = state.modelCatalogs[sessionID]
                ?? GatewayModelCatalog(current: nil, routable: true, groups: [])
            catalog.current = selection
            state.modelCatalogs[sessionID] = catalog
        case .permissionsReceived(let sessionID, var permissions):
            guard let sessionID else { return }
            permissions.options = (permissions.options ?? []).filter {
                supportedPermissionPresets.contains($0.value)
            }
            state.sessionPermissions[sessionID] = permissions
        case .permissionSelected(let sessionID, let value):
            guard let sessionID else { return }
            var permissions = state.sessionPermissions[sessionID] ?? GatewaySessionPermissions()
            permissions.currentValue = value
            permissions.preset = value
            state.sessionPermissions[sessionID] = permissions
        case .contextReceived(let sessionID, let asOfSequence, let tokenUsage, let pressure, let breakdown):
            guard let sessionID else { return }
            var snapshot = state.contextSnapshots[sessionID] ?? GatewayContextSnapshot()
            snapshot.asOfSeq = asOfSequence ?? snapshot.asOfSeq
            snapshot.tokenUsage = tokenUsage ?? snapshot.tokenUsage
            snapshot.pressure = pressure ?? snapshot.pressure
            snapshot.breakdown = breakdown ?? snapshot.breakdown
            state.contextSnapshots[sessionID] = snapshot
        case .statsReceived(let sessionID, let asOfSequence, let stats, let totals, let pressure):
            guard let sessionID else { return }
            var snapshot = state.sessionStatsSnapshots[sessionID] ?? GatewaySessionStatsSnapshot()
            snapshot.asOfSeq = asOfSequence ?? snapshot.asOfSeq
            snapshot.stats = stats ?? snapshot.stats
            if let totals { snapshot.tokenUsage = GatewaySessionTokenUsage(totals: totals) }
            snapshot.contextPressure = pressure ?? snapshot.contextPressure
            state.sessionStatsSnapshots[sessionID] = snapshot
        case .requestStarted(let kind):
            state.loadingKinds.insert(kind)
        case .requestFinished(let kind), .requestTimedOut(let kind):
            state.loadingKinds.remove(kind)
            if kind == "models" {
                state.pendingModelsSessionID = nil
                state.isPendingGlobalModelsRequest = false
            }
        case .defaultConfigurationRequestStarted(let kind):
            state.defaultConfigurationLoadingKinds.insert(kind)
        case .defaultConfigurationRequestFinished(let kind),
             .defaultConfigurationRequestTimedOut(let kind):
            state.defaultConfigurationLoadingKinds.remove(kind)
        case .modelsRequestTargeted(let sessionID):
            state.pendingModelsSessionID = sessionID
            state.isPendingGlobalModelsRequest = sessionID == nil
        case .modelSelectionTargeted(let sessionID):
            state.pendingModelSelectionSessionID = sessionID
        case .modelSelectionResolved:
            state.pendingModelSelectionSessionID = nil
        case .permissionOptionsTargeted(let sessionID):
            state.pendingPermissionOptionsSessionID = sessionID
        case .permissionOptionsResolved:
            state.pendingPermissionOptionsSessionID = nil
        }
    }

    private static let supportedPermissionPresets: Set<String> = [
        "read-only", "workspace-write", "danger-full-access"
    ]
}
