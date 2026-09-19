import AppIntents
import Foundation

struct AgentApprovalIntentRequest: Hashable, Sendable {
    let gatewayID: String
    let sessionID: String
    let rpcID: String
    let approvalID: String
    let outcome: AgentApprovalIntentOutcome
}

enum AgentApprovalIntentOutcome: String, Hashable, Sendable {
    case allowedOnce
    case rejected
}

/// LiveActivityIntent 会在主 App 进程中执行。这里用一个很窄的桥接层，
/// 将系统按钮动作交给当前的 MultiGatewayStore，避免 Widget 直接持有连接状态。
@MainActor
enum AgentLiveActivityIntentBridge {
    typealias Handler = @MainActor @Sendable (AgentApprovalIntentRequest) -> Bool

    private static var handler: Handler?

    static func install(_ handler: @escaping Handler) {
        self.handler = handler
    }

    static func submit(_ request: AgentApprovalIntentRequest) -> Bool {
        handler?(request) ?? false
    }
}

struct AllowAgentActionIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "允许本次操作"
    static let description = IntentDescription("允许 Agent 执行当前待审批操作。")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @Parameter(title: "Gateway ID") var gatewayID: String
    @Parameter(title: "Session ID") var sessionID: String
    @Parameter(title: "RPC ID") var rpcID: String
    @Parameter(title: "Approval ID") var approvalID: String

    init() {}

    init(gatewayID: String, sessionID: String, rpcID: String, approvalID: String) {
        self.gatewayID = gatewayID
        self.sessionID = sessionID
        self.rpcID = rpcID
        self.approvalID = approvalID
    }

    @MainActor
    func perform() async throws -> some IntentResult {
        _ = AgentLiveActivityIntentBridge.submit(AgentApprovalIntentRequest(
            gatewayID: gatewayID,
            sessionID: sessionID,
            rpcID: rpcID,
            approvalID: approvalID,
            outcome: .allowedOnce
        ))
        return .result()
    }
}

struct RejectAgentActionIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "拒绝操作"
    static let description = IntentDescription("拒绝 Agent 执行当前待审批操作。")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @Parameter(title: "Gateway ID") var gatewayID: String
    @Parameter(title: "Session ID") var sessionID: String
    @Parameter(title: "RPC ID") var rpcID: String
    @Parameter(title: "Approval ID") var approvalID: String

    init() {}

    init(gatewayID: String, sessionID: String, rpcID: String, approvalID: String) {
        self.gatewayID = gatewayID
        self.sessionID = sessionID
        self.rpcID = rpcID
        self.approvalID = approvalID
    }

    @MainActor
    func perform() async throws -> some IntentResult {
        _ = AgentLiveActivityIntentBridge.submit(AgentApprovalIntentRequest(
            gatewayID: gatewayID,
            sessionID: sessionID,
            rpcID: rpcID,
            approvalID: approvalID,
            outcome: .rejected
        ))
        return .result()
    }
}
