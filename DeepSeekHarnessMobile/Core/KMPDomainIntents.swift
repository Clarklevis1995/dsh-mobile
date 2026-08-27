import Foundation

/// iOS 平台层发往 KMP SessionList Store 的用户/网关 Intent。
/// 这里只描述跨层输入，不包含 Swift 领域状态或 Reducer。
enum KMPSessionListIntent {
    case select(String?)
    case setArchivedSessionIDs(Set<String>)
    case remoteSessionsReceived([GatewaySessionSummary])
    case messageSent(sessionID: String, agentPreset: String?)
    case knownSessionAdded(String)
    case eventReceived(SessionEvent)
    case markRead(String)
}

/// Gateway 路由及平台投影发往 KMP SessionControl Store 的输入。
/// 状态转换与校验只存在于 commonMain。
enum KMPSessionControlAction {
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

/// KMP History 状态面向 SwiftUI 的轻量展示值。
struct HistoryLoadProgress: Equatable {
    var loaded: Int
    var total: Int?
}
