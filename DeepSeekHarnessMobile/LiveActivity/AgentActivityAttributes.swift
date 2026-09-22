import ActivityKit
import Foundation

/// 主 App 与 Widget Extension 共享的实时活动协议。
struct AgentActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        /// 会话标题会在 Agent 启动后由主机重命名，因此必须放在可更新状态中。
        /// 保留 Attributes 中的初始标题作为旧活动的兼容回退值。
        var sessionTitle: String? = nil
        var phase: AgentActivityPhase
        var status: String
        var detail: String?
        var secondaryDetail: String?
        var sourceLabel: String?
        var command: String?
        var rpcID: String?
        var approvalID: String?
        var toolName: String?
        var stepKind: AgentActivityStepKind?
        var updatedAt: Date
    }

    let gatewayID: String
    let sessionID: String
    let sessionTitle: String
    let startedAt: Date
}

/// 当前显示在实时活动中的执行轨迹类型。
///
/// 该字段是可选的，确保升级后仍能解码系统中由旧版本创建的实时活动。
enum AgentActivityStepKind: String, Codable, Hashable {
    case preparing
    case context
    case reasoning
    case writing
    case editing
    case reading
    case command
    case searching
    case tool
    case result
    case message
    case delivery
}

enum AgentActivityPhase: String, Codable, Hashable {
    case running
    case awaitingChoice
    case awaitingApproval
    case submittingApproval
    case approved
    case rejected
    case failed
    case completed

    var isTerminal: Bool {
        switch self {
        // 允许/拒绝只是一次操作的中间结果，Agent 仍可能继续执行并更新下一步。
        case .failed, .completed: true
        case .running, .awaitingChoice, .awaitingApproval, .submittingApproval, .approved, .rejected: false
        }
    }
}
