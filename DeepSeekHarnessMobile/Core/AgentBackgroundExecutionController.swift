import UIKit

@MainActor
protocol BackgroundTaskApplication: AnyObject {
    func beginBackgroundTask(
        withName taskName: String?,
        expirationHandler handler: (@Sendable () -> Void)?
    ) -> UIBackgroundTaskIdentifier
    func endBackgroundTask(_ identifier: UIBackgroundTaskIdentifier)
}

extension UIApplication: BackgroundTaskApplication {}

@MainActor
final class AgentBackgroundExecutionController {
    private let application: BackgroundTaskApplication
    private var taskIdentifier: UIBackgroundTaskIdentifier = .invalid
    private(set) var applicationIsInBackground = false
    private(set) var outstandingTurnsBySessionID: [String: Int] = [:]
    private(set) var unassociatedOutstandingTurns = 0
    private(set) var questionAllowanceSessionIDs: [String: String] = [:]

    var outstandingTurns: Int {
        unassociatedOutstandingTurns + outstandingTurnsBySessionID.values.reduce(0, +)
    }

    /// 兼容诊断用途：仅当全部活动都确定属于同一个 session 时返回该值。
    var sessionID: String? {
        guard unassociatedOutstandingTurns == 0 else { return nil }
        let sessionIDs = Set(outstandingTurnsBySessionID.keys)
            .union(questionAllowanceSessionIDs.values)
        return sessionIDs.count == 1 ? sessionIDs.first : nil
    }

    var onBackgroundAllowanceExpired: (() -> Void)?

    var keepsConnectionAlive: Bool {
        guard isAgentWorkActive else { return false }
        return !applicationIsInBackground || taskIdentifier != .invalid
    }

    var isAgentWorkActive: Bool {
        outstandingTurns > 0 || !questionAllowanceSessionIDs.isEmpty
    }

    convenience init() {
        self.init(application: UIApplication.shared)
    }

    init(application: BackgroundTaskApplication) {
        self.application = application
    }

    func applicationDidBecomeActive() {
        applicationIsInBackground = false
        // 前台运行不需要 UIApplication 后台额度。业务 turn 计数继续保留，
        // 下次进入后台时若仍在执行会重新申请。
        endTask()
    }

    func applicationDidEnterBackground() {
        applicationIsInBackground = true
        if isAgentWorkActive { beginTaskIfNeeded() }
    }

    func begin(sessionID: String?, startsNewTurn: Bool) {
        if let sessionID, !sessionID.isEmpty {
            if startsNewTurn {
                outstandingTurnsBySessionID[sessionID, default: 0] += 1
            } else if outstandingTurnsBySessionID[sessionID] == nil {
                outstandingTurnsBySessionID[sessionID] = 1
            }
        } else if startsNewTurn {
            unassociatedOutstandingTurns += 1
        } else if outstandingTurns == 0 {
            unassociatedOutstandingTurns = 1
        }
        if applicationIsInBackground { beginTaskIfNeeded() }
    }

    /// Human Question answer 在服务端确认前拥有独立的临时保活额度，
    /// 不会增加 turn 计数，因而失败时不会误结束其他正在执行的 turn。
    func beginQuestionAnswer(rpcID: String, sessionID: String) {
        guard !rpcID.isEmpty, !sessionID.isEmpty else { return }
        questionAllowanceSessionIDs[rpcID] = sessionID
        if applicationIsInBackground { beginTaskIfNeeded() }
    }

    /// accepted 表示 Agent 将继续执行：有现存 turn 时只释放临时额度，
    /// 恢复的待回答问题没有本地 turn 时则将额度原子转为一个 turn。
    func questionAnswerAccepted(rpcID: String) {
        guard let acceptedSessionID = questionAllowanceSessionIDs.removeValue(forKey: rpcID) else { return }
        if outstandingTurnsBySessionID[acceptedSessionID] == nil {
            outstandingTurnsBySessionID[acceptedSessionID] = 1
        }
        finishIfInactive()
    }

    func releaseQuestionAnswer(rpcID: String) {
        guard questionAllowanceSessionIDs.removeValue(forKey: rpcID) != nil else { return }
        finishIfInactive()
    }

    func releaseAllQuestionAnswers() {
        guard !questionAllowanceSessionIDs.isEmpty else { return }
        questionAllowanceSessionIDs.removeAll()
        finishIfInactive()
    }

    func releaseQuestionAnswers(sessionID: String) {
        let previousCount = questionAllowanceSessionIDs.count
        questionAllowanceSessionIDs = questionAllowanceSessionIDs.filter { $0.value != sessionID }
        guard questionAllowanceSessionIDs.count != previousCount else { return }
        finishIfInactive()
    }

    private func beginTaskIfNeeded() {
        guard taskIdentifier == .invalid else { return }
        taskIdentifier = application.beginBackgroundTask(
            withName: "Complete DSH Agent Turn"
        ) { [weak self] in
            Task { @MainActor [weak self] in self?.expire() }
        }
    }

    func associateSessionIfNeeded(_ sessionID: String) {
        guard !sessionID.isEmpty, unassociatedOutstandingTurns > 0 else { return }
        outstandingTurnsBySessionID[sessionID, default: 0] += unassociatedOutstandingTurns
        unassociatedOutstandingTurns = 0
    }

    func turnEnded(sessionID: String) {
        releaseQuestionAnswers(sessionID: sessionID)
        if let count = outstandingTurnsBySessionID[sessionID] {
            if count > 1 {
                outstandingTurnsBySessionID[sessionID] = count - 1
            } else {
                outstandingTurnsBySessionID.removeValue(forKey: sessionID)
            }
        }
        finishIfInactive()
    }

    func cancel() {
        outstandingTurnsBySessionID.removeAll()
        unassociatedOutstandingTurns = 0
        questionAllowanceSessionIDs.removeAll()
        endTask()
    }

    private func finishIfInactive() {
        guard !isAgentWorkActive, endTask() else { return }
        if applicationIsInBackground { onBackgroundAllowanceExpired?() }
    }

    private func expire() {
        // UIKit 已撤销保活额度，内部状态也必须在通知平台层前原子清空。
        outstandingTurnsBySessionID.removeAll()
        unassociatedOutstandingTurns = 0
        questionAllowanceSessionIDs.removeAll()
        let didEndTask = endTask()
        if didEndTask && applicationIsInBackground { onBackgroundAllowanceExpired?() }
    }

    @discardableResult
    private func endTask() -> Bool {
        let identifier = taskIdentifier
        guard identifier != .invalid else { return false }
        taskIdentifier = .invalid
        application.endBackgroundTask(identifier)
        return true
    }
}
