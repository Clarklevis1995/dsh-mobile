import UIKit
import OSLog

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
    private static let logger = LocalLogger(subsystem: "ai.dsh.mobile.ios", category: "agent-background")

    private func traceBackground(_ event: String) {
        Self.logger.notice("[AgentBackground] event=\(event) background=\(self.applicationIsInBackground) turns=\(self.outstandingTurns) questions=\(self.questionAllowanceSessionIDs.count) queued=\(self.queuedSessionIDs.count) idleAllowance=\(self.holdsIdleBackgroundAllowance) hasTask=\(self.taskIdentifier != .invalid) longKeepAlive=\(self.longRunningKeepAlive?.isKeepingAlive == true)")
    }

    private let application: BackgroundTaskApplication
    private let longRunningKeepAlive: AgentLongRunningKeepAlive?
    private var taskIdentifier: UIBackgroundTaskIdentifier = .invalid
    private var holdsIdleBackgroundAllowance = false
    private var taskGeneration: UUID?
    private(set) var applicationIsInBackground = false
    private(set) var outstandingTurnsBySessionID: [String: Int] = [:]
    private(set) var unassociatedOutstandingTurns = 0
    private var queuedSessionIDs: Set<String> = []
    private(set) var questionAllowanceSessionIDs: [String: String] = [:]

    var outstandingTurns: Int {
        unassociatedOutstandingTurns + outstandingTurnsBySessionID.values.reduce(0, +)
    }

    /// 兼容诊断用途：仅当全部活动都确定属于同一个 session 时返回该值。
    var sessionID: String? {
        guard unassociatedOutstandingTurns == 0 else { return nil }
        let sessionIDs = Set(outstandingTurnsBySessionID.keys)
            .union(questionAllowanceSessionIDs.values)
            .union(queuedSessionIDs)
        return sessionIDs.count == 1 ? sessionIDs.first : nil
    }

    var onBackgroundAllowanceExpired: (() -> Void)?
    var onKeepAlivePulse: (() -> Void)?

    var keepsConnectionAlive: Bool {
        if applicationIsInBackground && holdsIdleBackgroundAllowance {
            return taskIdentifier != .invalid
        }
        guard isAgentWorkActive else { return false }
        return !applicationIsInBackground
            || taskIdentifier != .invalid
            || longRunningKeepAlive?.isKeepingAlive == true
    }

    var isAgentWorkActive: Bool {
        outstandingTurns > 0 || !questionAllowanceSessionIDs.isEmpty || !queuedSessionIDs.isEmpty
    }

    func isAgentWorkActive(sessionID: String) -> Bool {
        outstandingTurnsBySessionID[sessionID, default: 0] > 0
            || questionAllowanceSessionIDs.values.contains(sessionID)
            || queuedSessionIDs.contains(sessionID)
    }

    convenience init() {
        self.init(
            application: UIApplication.shared,
            longRunningKeepAlive: AgentBackgroundKeepAliveManager.shared
        )
    }

    init(
        application: BackgroundTaskApplication,
        longRunningKeepAlive: AgentLongRunningKeepAlive? = nil
    ) {
        self.application = application
        self.longRunningKeepAlive = longRunningKeepAlive
    }

    func applicationDidBecomeActive() {
        traceBackground("become-active")
        applicationIsInBackground = false
        holdsIdleBackgroundAllowance = false
        longRunningKeepAlive?.applicationDidBecomeActive()
        // 前台运行不需要 UIApplication 后台额度。业务 turn 计数继续保留，
        // 下次进入后台时若仍在执行会重新申请。
        endTask()
    }

    func applicationDidEnterBackground() {
        guard !applicationIsInBackground else { return }
        applicationIsInBackground = true
        longRunningKeepAlive?.applicationDidEnterBackground()
        // 空闲连接也申请一次有限额度；它不计入 Agent 任务，不启用定位或音频。
        holdsIdleBackgroundAllowance = !isAgentWorkActive
        beginTaskIfNeeded()
        if taskIdentifier == .invalid { holdsIdleBackgroundAllowance = false }
        traceBackground("enter-background")
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
        syncLongRunningKeepAlive()
        if applicationIsInBackground { beginTaskIfNeeded() }
    }

    /// Human Question answer 在服务端确认前拥有独立的临时保活额度，
    /// 不会增加 turn 计数，因而失败时不会误结束其他正在执行的 turn。
    func beginQuestionAnswer(rpcID: String, sessionID: String) {
        guard !rpcID.isEmpty, !sessionID.isEmpty else { return }
        questionAllowanceSessionIDs[rpcID] = sessionID
        syncLongRunningKeepAlive()
        if applicationIsInBackground { beginTaskIfNeeded() }
    }

    /// accepted 表示 Agent 将继续执行：有现存 turn 时只释放临时额度，
    /// 恢复的待回答问题没有本地 turn 时则将额度原子转为一个 turn。
    func questionAnswerAccepted(rpcID: String) {
        guard let acceptedSessionID = questionAllowanceSessionIDs.removeValue(forKey: rpcID) else { return }
        if outstandingTurnsBySessionID[acceptedSessionID] == nil {
            outstandingTurnsBySessionID[acceptedSessionID] = 1
        }
        syncLongRunningKeepAlive()
        finishIfInactive()
    }

    func releaseQuestionAnswer(rpcID: String) {
        guard questionAllowanceSessionIDs.removeValue(forKey: rpcID) != nil else { return }
        syncLongRunningKeepAlive()
        finishIfInactive()
    }

    func releaseAllQuestionAnswers() {
        guard !questionAllowanceSessionIDs.isEmpty else { return }
        questionAllowanceSessionIDs.removeAll()
        syncLongRunningKeepAlive()
        finishIfInactive()
    }

    func releaseQuestionAnswers(sessionID: String) {
        let previousCount = questionAllowanceSessionIDs.count
        questionAllowanceSessionIDs = questionAllowanceSessionIDs.filter { $0.value != sessionID }
        guard questionAllowanceSessionIDs.count != previousCount else { return }
        syncLongRunningKeepAlive()
        finishIfInactive()
    }

    private func beginTaskIfNeeded() {
        guard taskIdentifier == .invalid else { return }
        let generation = UUID()
        taskGeneration = generation
        taskIdentifier = application.beginBackgroundTask(
            withName: isAgentWorkActive ? "Complete DSH Agent Turn" : "Preserve DSH Gateway Connection"
        ) { [weak self] in
            Task { @MainActor [weak self] in
                guard let self, self.taskGeneration == generation else { return }
                self.expire()
            }
        }
        if taskIdentifier == .invalid { taskGeneration = nil }
        traceBackground("background-task-requested")
    }

    func associateSessionIfNeeded(_ sessionID: String) {
        guard !sessionID.isEmpty, unassociatedOutstandingTurns > 0 else { return }
        outstandingTurnsBySessionID[sessionID, default: 0] += unassociatedOutstandingTurns
        unassociatedOutstandingTurns = 0
        syncLongRunningKeepAlive()
    }

    func messageAccepted(sessionID: String) {
        associateSessionIfNeeded(sessionID)
        // 排队/插话并不各自代表一个新 turn，不能按点击发送次数累计保活。
        outstandingTurnsBySessionID[sessionID] = 1
        syncLongRunningKeepAlive()
    }

    func updateQueuedSessions(_ sessionIDs: Set<String>) {
        queuedSessionIDs = sessionIDs
        syncLongRunningKeepAlive()
        if applicationIsInBackground && isAgentWorkActive { beginTaskIfNeeded() }
        finishIfInactive()
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
        syncLongRunningKeepAlive()
        finishIfInactive()
    }

    /// 会话快照用于补偿后台断线期间漏掉的 turn/end。快照明确标记为已停止时，
    /// 一次性释放该会话的所有 turn、排队项与问答额度，避免定位/音频保活残留。
    func reconcileSessions(runningBySessionID: [String: Bool]) {
        let endedSessionIDs = Set(
            runningBySessionID.compactMap { sessionID, isRunning in
                isRunning ? nil : sessionID
            }
        )
        guard !endedSessionIDs.isEmpty else { return }

        outstandingTurnsBySessionID = outstandingTurnsBySessionID.filter {
            !endedSessionIDs.contains($0.key)
        }
        queuedSessionIDs.subtract(endedSessionIDs)
        questionAllowanceSessionIDs = questionAllowanceSessionIDs.filter {
            !endedSessionIDs.contains($0.value)
        }
        syncLongRunningKeepAlive()
        finishIfInactive()
    }

    func cancel() {
        holdsIdleBackgroundAllowance = false
        queuedSessionIDs.removeAll()
        outstandingTurnsBySessionID.removeAll()
        unassociatedOutstandingTurns = 0
        questionAllowanceSessionIDs.removeAll()
        syncLongRunningKeepAlive()
        endTask()
    }

    private func finishIfInactive() {
        guard !isAgentWorkActive else { return }
        // 从空闲后台窗口开始的任务复用同一额度，任务结束不提前关闭窗口。
        guard !holdsIdleBackgroundAllowance else { return }
        endTask()
        if applicationIsInBackground { onBackgroundAllowanceExpired?() }
    }

    private func expire() {
        traceBackground("background-task-expired")
        holdsIdleBackgroundAllowance = false
        let didEndTask = endTask()
        guard longRunningKeepAlive?.isKeepingAlive != true else { return }
        queuedSessionIDs.removeAll()
        // UIKit 已撤销保活额度且长期保活不可用，内部状态也必须原子清空。
        outstandingTurnsBySessionID.removeAll()
        unassociatedOutstandingTurns = 0
        questionAllowanceSessionIDs.removeAll()
        syncLongRunningKeepAlive()
        if didEndTask && applicationIsInBackground { onBackgroundAllowanceExpired?() }
    }

    private func syncLongRunningKeepAlive() {
        if isAgentWorkActive {
            // MultiGatewayStore 可能短暂创建非活动 AppStore；只有真正开始工作的
            // controller 才能取得共享保活管理器的心跳回调。
            longRunningKeepAlive?.onPulse = { [weak self] in self?.onKeepAlivePulse?() }
        }
        longRunningKeepAlive?.setAgentWorkActive(isAgentWorkActive)
    }

    @discardableResult
    private func endTask() -> Bool {
        let identifier = taskIdentifier
        guard identifier != .invalid else { return false }
        taskIdentifier = .invalid
        taskGeneration = nil
        traceBackground("end-background-task")
        application.endBackgroundTask(identifier)
        return true
    }
}
