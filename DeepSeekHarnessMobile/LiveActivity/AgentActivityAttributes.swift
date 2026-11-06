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
        /// Widget chrome text built by the app in the app language. The widget keeps no
        /// user-facing literals of its own. Optional so activities started by an older build
        /// still decode; the widget then shows only `status`/`detail` and hides label-only chrome.
        var labels: AgentActivityLabels? = nil
    }

    let gatewayID: String
    let sessionID: String
    let sessionTitle: String
    let startedAt: Date
}

/// Localized chrome strings the widget renders verbatim, filled by the app for the current phase.
struct AgentActivityLabels: Codable, Hashable {
    /// Header link, e.g. "Details".
    var details: String
    /// Failure action opening the session, e.g. "View details".
    var viewDetails: String
    /// Footer link opening the session, e.g. "View session".
    var viewSession: String
    /// Failure action that re-checks the result.
    var checkResult: String
    /// iOS 16.1 fallback shown instead of the approval buttons.
    var handleInApp: String
    var reject: String
    var allowOnce: String
    /// Shown when an approval has no command text.
    var awaitingOperationDetail: String
    /// Shown while an approval decision is being confirmed by the host.
    var awaitingHostConfirmation: String
    /// Expanded-island badge when the activity is stale.
    var staleBadge: String
    /// Step title when the activity is stale.
    var staleTitle: String
    /// Expanded-island badge for the current phase.
    var badge: String
    /// Compact trailing badge for the current phase (empty while running).
    var compactBadge: String
    /// Step title and detail used when `detail`/`secondaryDetail` are empty.
    var defaultTitle: String
    var defaultDetail: String
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
