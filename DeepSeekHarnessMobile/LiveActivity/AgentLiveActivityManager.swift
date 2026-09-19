import ActivityKit
import Foundation

/// 将 Gateway 的会话事件串行投影为系统实时活动。
/// 本地 ActivityKit 更新依赖 App 仍有后台执行时间；后续接入 APNs 时可复用相同状态模型。
@MainActor
final class AgentLiveActivityManager {
    static let shared = AgentLiveActivityManager()

    private let staleInterval: TimeInterval = 90
    private let updateCoalescingDelay: Duration = .milliseconds(700)
    private var activities: [String: Activity<AgentActivityAttributes>] = [:]
    private var latestStates: [String: AgentActivityAttributes.ContentState] = [:]
    private var pendingRunningStates: [String: AgentActivityAttributes.ContentState] = [:]
    private var updateWorkers: [String: Task<Void, Never>] = [:]
    private var updateWorkerTokens: [String: UUID] = [:]
    private var operationTails: [String: Task<Void, Never>] = [:]
    private var endingSessionKeys: Set<String> = []
    private var recentlyEndedActivities: [String: Activity<AgentActivityAttributes>] = [:]
    private var recentlyEndedCleanupTasks: [String: Task<Void, Never>] = [:]

    private init() {
        // ActivityKit can restore more than one activity after an app/widget
        // rebuild. The system then puts the newest one in the small secondary
        // island while a stale activity occupies the main island, which looks
        // like an empty black pill. This product presents the current Agent
        // session, so retain only the newest restorable activity.
        let restored = Activity<AgentActivityAttributes>.activities
            .sorted { $0.content.state.updatedAt > $1.content.state.updatedAt }
        for (index, activity) in restored.enumerated() {
            guard index == 0, !activity.content.state.phase.isTerminal else {
                Task { await activity.end(nil, dismissalPolicy: .immediate) }
                continue
            }
            let activityKey = key(
                gatewayID: activity.attributes.gatewayID,
                sessionID: activity.attributes.sessionID
            )
            activities[activityKey] = activity
            latestStates[activityKey] = activity.content.state
        }
    }

    func sessionStarted(
        gatewayID: String,
        sessionID: String,
        title: String,
        turn: Int?,
        sourceLabel: String? = nil
    ) {
        let activityKey = key(gatewayID: gatewayID, sessionID: sessionID)
        if endingSessionKeys.remove(activityKey) != nil {
            cancelRunningUpdate(activityKey: activityKey)
            activities[activityKey] = nil
            latestStates[activityKey] = nil
            operationTails[activityKey] = nil
        }
        dismissRecentlyEndedActivity(activityKey: activityKey)
        upsert(
            gatewayID: gatewayID,
            sessionID: sessionID,
            title: title,
            state: state(
                phase: .running,
                status: "正在执行",
                detail: "Agent 正在处理",
                secondaryDetail: turn.map { "第 \($0) 轮 · 等待执行步骤" } ?? "正在等待执行步骤",
                sourceLabel: sourceLabel,
                stepKind: .preparing
            )
        )
    }

    func stepStarted(
        gatewayID: String,
        sessionID: String,
        title: String,
        step: Int?,
        sourceLabel: String? = nil
    ) {
        upsert(
            gatewayID: gatewayID,
            sessionID: sessionID,
            title: title,
            state: state(
                phase: .running,
                status: "正在执行",
                detail: "正在分析执行步骤",
                secondaryDetail: step.map { "第 \($0) 步 · Agent 正在处理" } ?? "Agent 正在处理",
                sourceLabel: sourceLabel,
                stepKind: .reasoning
            )
        )
    }

    /// 用最新一条语义执行轨迹更新实时活动。实时活动只显示当前步骤，完整历史仍保留在会话中。
    func progressUpdated(
        gatewayID: String,
        sessionID: String,
        title: String,
        headline: String,
        detail: String?,
        kind: AgentActivityStepKind,
        toolName: String? = nil,
        sourceLabel: String? = nil
    ) {
        upsert(
            gatewayID: gatewayID,
            sessionID: sessionID,
            title: title,
            state: state(
                phase: .running,
                status: "正在执行",
                detail: headline,
                secondaryDetail: detail,
                sourceLabel: sourceLabel,
                toolName: toolName,
                stepKind: kind
            )
        )
    }

    func toolStarted(
        gatewayID: String,
        sessionID: String,
        title: String,
        toolName: String?,
        sourceLabel: String? = nil
    ) {
        let displayName = toolName?.trimmingCharacters(in: .whitespacesAndNewlines)
        upsert(
            gatewayID: gatewayID,
            sessionID: sessionID,
            title: title,
            state: state(
                phase: .running,
                status: "正在执行",
                detail: displayName?.isEmpty == false ? "正在执行 \(displayName!)" : "正在调用工具",
                secondaryDetail: "Agent 正在处理当前步骤",
                sourceLabel: sourceLabel,
                toolName: toolName,
                stepKind: .tool
            )
        )
    }

    func awaitingApproval(
        gatewayID: String,
        request: GatewayPendingApprovalRequest,
        title: String,
        command: String?,
        sourceLabel: String? = nil
    ) {
        upsert(
            gatewayID: gatewayID,
            sessionID: request.sessionId,
            title: title,
            state: state(
                phase: .awaitingApproval,
                status: "需要批准 · 仅限本次",
                detail: request.reason ?? request.toolName,
                secondaryDetail: "仅授权本次操作",
                sourceLabel: sourceLabel,
                command: command,
                rpcID: request.rpcId,
                approvalID: request.approvalId,
                toolName: request.toolName
            )
        )
    }

    /// 审批请求有时先于对应的工具参数到达。工具事件补齐命令后，只更新审批卡片内容，
    /// 保持当前审批阶段，避免尾随的普通进度把“等待审批”覆盖成“正在执行”。
    func enrichApprovalCommand(
        gatewayID: String,
        sessionID: String,
        rpcID: String,
        command: String?
    ) {
        guard let command = command?.trimmingCharacters(in: .whitespacesAndNewlines),
              !command.isEmpty else { return }
        let activityKey = key(gatewayID: gatewayID, sessionID: sessionID)
        guard !endingSessionKeys.contains(activityKey),
              let activity = activities[activityKey] else { return }

        var current = latestStates[activityKey] ?? activity.content.state
        guard current.rpcID == rpcID,
              current.command?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty != false else {
            return
        }
        switch current.phase {
        case .awaitingApproval, .submittingApproval, .failed:
            current.command = command
            current.updatedAt = .now
            enqueueUpdate(activityKey: activityKey, activity: activity, state: current)
        case .running, .approved, .rejected, .completed:
            return
        }
    }

    /// 主机会在会话创建后异步生成标题。直接更新现有 Activity，避免必须等到
    /// 下一条执行进度到达后，灵动岛才显示最新标题。
    func sessionTitleUpdated(gatewayID: String, sessionID: String, title: String) {
        guard let title = normalizedTitle(title) else { return }
        let activityKey = key(gatewayID: gatewayID, sessionID: sessionID)
        guard !endingSessionKeys.contains(activityKey),
              let activity = activities[activityKey] else { return }

        var current = latestStates[activityKey] ?? activity.content.state
        guard current.sessionTitle != title else { return }
        current.sessionTitle = title
        current.updatedAt = .now
        enqueueUpdate(activityKey: activityKey, activity: activity, state: current)
    }

    func approvalSubmitting(
        gatewayID: String,
        request: GatewayPendingApprovalRequest,
        outcome: GatewayApprovalOutcome,
        title: String,
        command: String?,
        sourceLabel: String? = nil
    ) {
        let detail = outcome == .allowedOnce ? "正在提交「允许一次」" : "正在提交「拒绝」"
        upsert(
            gatewayID: gatewayID,
            sessionID: request.sessionId,
            title: title,
            state: state(
                phase: .submittingApproval,
                status: "正在提交决定",
                detail: detail,
                secondaryDetail: "正在等待主机确认",
                sourceLabel: sourceLabel,
                command: command,
                rpcID: request.rpcId,
                approvalID: request.approvalId,
                toolName: request.toolName
            )
        )
    }

    func approvalResolved(
        gatewayID: String,
        sessionID: String,
        rpcID: String,
        outcome: GatewayApprovalOutcome,
        title: String,
        sourceLabel: String? = nil
    ) {
        let approved = outcome == .allowedOnce
        updateExisting(
            gatewayID: gatewayID,
            sessionID: sessionID,
            fallbackTitle: title,
            state: state(
                phase: approved ? .approved : .rejected,
                status: approved ? "已允许本次操作" : "已拒绝本次操作",
                detail: approved ? "批准已送达" : "本次命令未执行",
                secondaryDetail: approved ? "等待 Agent 更新下一步" : "决定已送达，等待 Agent 后续状态",
                sourceLabel: sourceLabel,
                rpcID: rpcID
            )
        )
    }

    func approvalFailed(
        gatewayID: String,
        sessionID: String,
        rpcID: String,
        title: String,
        reason: String?,
        sourceLabel: String? = nil
    ) {
        updateExisting(
            gatewayID: gatewayID,
            sessionID: sessionID,
            fallbackTitle: title,
            state: state(
                phase: .failed,
                status: "未能确认审批结果",
                detail: reason?.isEmpty == false ? reason : "确认结果后才能继续操作",
                secondaryDetail: "确认结果后才能继续操作",
                sourceLabel: sourceLabel,
                rpcID: rpcID
            ),
            preservesCommand: true
        )
    }

    func sessionEnded(
        gatewayID: String,
        sessionID: String,
        title: String,
        failed: Bool,
        sourceLabel: String? = nil
    ) {
        let activityKey = key(gatewayID: gatewayID, sessionID: sessionID)
        guard !endingSessionKeys.contains(activityKey), let activity = activities[activityKey] else { return }

        let current = latestStates[activityKey] ?? activity.content.state
        var finalState: AgentActivityAttributes.ContentState
        if current.phase == .rejected {
            finalState = state(
                phase: .rejected,
                status: "已拒绝本次操作",
                detail: "本次命令未执行",
                secondaryDetail: "决定已送达，Agent 已停止当前操作",
                sourceLabel: sourceLabel ?? current.sourceLabel,
                rpcID: current.rpcID
            )
        } else {
            finalState = state(
                phase: failed ? .failed : .completed,
                status: failed ? "执行失败" : "执行完成",
                detail: failed ? "执行未完成" : "任务已完成",
                secondaryDetail: failed ? "打开 App 查看详情" : "Agent 已完成本次任务",
                sourceLabel: sourceLabel ?? current.sourceLabel
            )
        }
        finalState.sessionTitle = normalizedTitle(title) ?? current.sessionTitle

        // 一旦收到 turn/end，就从可更新集合移除并封住后续脉冲，避免较早的 running 更新反写。
        endingSessionKeys.insert(activityKey)
        latestStates[activityKey] = finalState
        let runningWorker = cancelRunningUpdate(activityKey: activityKey)
        activities[activityKey] = nil
        recentlyEndedActivities[activityKey] = nil
        recentlyEndedCleanupTasks.removeValue(forKey: activityKey)?.cancel()
        enqueue(activityKey: activityKey) {
            if let runningWorker { await runningWorker.value }
            let content = ActivityContent(state: finalState, staleDate: nil)
            await activity.end(
                content,
                dismissalPolicy: .immediate
            )
        }
    }

    /// sessions 快照用于补偿后台重连期间可能漏掉的 turn/end。
    func reconcileSessions(gatewayID: String, runningBySessionID: [String: Bool]) {
        let systemActivities = Activity<AgentActivityAttributes>.activities.filter {
            // 覆盖安装、KeyStore 恢复或网关配置迁移后，本地 gatewayID 可能
            // 与旧 Activity 属性不同。Session ID 由主机生成且唯一，只要当前
            // 快照认识该 Session，也必须参与结束态与重复实例清理。
            $0.attributes.gatewayID == gatewayID
                || runningBySessionID[$0.attributes.sessionID] != nil
        }
        let newestRunningActivity = systemActivities
            .filter {
                $0.attributes.gatewayID == gatewayID
                    && runningBySessionID[$0.attributes.sessionID] == true
                    && !$0.content.state.phase.isTerminal
            }
            .max { $0.content.state.updatedAt < $1.content.state.updatedAt }

        for activity in systemActivities {
            let activityKey = key(
                gatewayID: activity.attributes.gatewayID,
                sessionID: activity.attributes.sessionID
            )
            let sessionEnded = runningBySessionID[activity.attributes.sessionID] == false
            let duplicate = runningBySessionID[activity.attributes.sessionID] == true
                && activity.id != newestRunningActivity?.id
            let terminal = activity.content.state.phase.isTerminal

            if sessionEnded || duplicate || terminal {
                removeTracking(activityKey: activityKey, activityID: activity.id)
                Task { await activity.end(nil, dismissalPolicy: .immediate) }
            } else if activity.id == newestRunningActivity?.id {
                // ActivityKit 可能在 App 被系统终止后恢复 Activity。重新登记系统中
                // 的唯一有效实例，让后续进度和 turn/end 能继续更新同一张卡片。
                activities[activityKey] = activity
                latestStates[activityKey] = activity.content.state
            }
        }
    }

    /// 后台保活脉冲不改变业务状态，只延长当前实时活动的有效期。
    func refreshActiveActivities() {
        for (activityKey, activity) in activities where !endingSessionKeys.contains(activityKey) {
            var currentState = latestStates[activityKey] ?? activity.content.state
            guard !currentState.phase.isTerminal else { continue }
            currentState.updatedAt = .now
            enqueueUpdate(activityKey: activityKey, activity: activity, state: currentState)
        }
    }

    private func upsert(
        gatewayID: String,
        sessionID: String,
        title: String,
        state: AgentActivityAttributes.ContentState
    ) {
        var state = state
        state.sessionTitle = normalizedTitle(title) ?? state.sessionTitle
        let activityKey = key(gatewayID: gatewayID, sessionID: sessionID)
        guard !endingSessionKeys.contains(activityKey) else { return }
        let currentState = latestStates[activityKey] ?? activities[activityKey]?.content.state
        guard AgentLiveActivityUpdatePolicy.allows(current: currentState, incoming: state) else { return }
        latestStates[activityKey] = state
        if let activity = activities[activityKey] {
            enqueueUpdate(activityKey: activityKey, activity: activity, state: state)
            return
        }

        // 进程被系统重建时，本地字典可能尚未登记 ActivityKit 已恢复的实例。
        // 复用系统中的同一活动，避免为同一 Session 再创建一张卡片，造成主岛
        // 空白、当前卡片被挤到副岛。
        let restoredMatches = Activity<AgentActivityAttributes>.activities
            .filter {
                key(
                    gatewayID: $0.attributes.gatewayID,
                    sessionID: $0.attributes.sessionID
                ) == activityKey && !$0.content.state.phase.isTerminal
            }
            .sorted { $0.content.state.updatedAt > $1.content.state.updatedAt }
        if let restored = restoredMatches.first {
            activities[activityKey] = restored
            latestStates[activityKey] = restored.content.state
            for duplicate in restoredMatches.dropFirst() {
                Task { await duplicate.end(nil, dismissalPolicy: .immediate) }
            }
            enqueueUpdate(activityKey: activityKey, activity: restored, state: state)
            return
        }

        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        dismissOtherActivities(except: activityKey)
        let attributes = AgentActivityAttributes(
            gatewayID: gatewayID,
            sessionID: sessionID,
            sessionTitle: title,
            startedAt: .now
        )
        do {
            let activity = try Activity.request(
                attributes: attributes,
                content: ActivityContent(
                    state: state,
                    staleDate: Date().addingTimeInterval(staleInterval)
                ),
                pushType: nil
            )
            activities[activityKey] = activity
        } catch {
#if DEBUG
            print("[AgentLiveActivity] 无法创建实时活动：\(error.localizedDescription)")
#endif
        }
    }

    private func updateExisting(
        gatewayID: String,
        sessionID: String,
        fallbackTitle: String,
        state: AgentActivityAttributes.ContentState,
        preservesCommand: Bool = false
    ) {
        let activityKey = key(gatewayID: gatewayID, sessionID: sessionID)
        if let activity = activities[activityKey] {
            var merged = state
            let current = latestStates[activityKey] ?? activity.content.state
            merged.sessionTitle = normalizedTitle(fallbackTitle) ?? current.sessionTitle
            merged.sourceLabel = merged.sourceLabel ?? current.sourceLabel
            if preservesCommand { merged.command = merged.command ?? current.command }
            enqueueUpdate(activityKey: activityKey, activity: activity, state: merged)
        } else {
            upsert(gatewayID: gatewayID, sessionID: sessionID, title: fallbackTitle, state: state)
        }
    }

    private func enqueueUpdate(
        activityKey: String,
        activity: Activity<AgentActivityAttributes>,
        state: AgentActivityAttributes.ContentState
    ) {
        guard !endingSessionKeys.contains(activityKey) else { return }
        latestStates[activityKey] = state

        if state.phase == .running {
            pendingRunningStates[activityKey] = state
            startRunningUpdateWorkerIfNeeded(activityKey: activityKey, activity: activity)
            return
        }

        let runningWorker = cancelRunningUpdate(activityKey: activityKey)
        enqueue(activityKey: activityKey) {
            if let runningWorker { await runningWorker.value }
            await activity.update(ActivityContent(
                state: state,
                staleDate: state.phase.isTerminal ? nil : Date().addingTimeInterval(self.staleInterval)
            ), alertConfiguration: self.alertConfiguration(for: state))
        }
    }

    private func alertConfiguration(
        for state: AgentActivityAttributes.ContentState
    ) -> AlertConfiguration? {
        guard state.phase == .awaitingApproval else { return nil }
        return AlertConfiguration(
            title: "需要审批",
            body: "Agent 请求批准本次操作",
            sound: .default
        )
    }

    private func normalizedTitle(_ title: String?) -> String? {
        guard let normalized = title?.trimmingCharacters(in: .whitespacesAndNewlines),
              !normalized.isEmpty else { return nil }
        return normalized
    }

    /// 高频执行轨迹采用 latest-wins：每个窗口只向 ActivityKit 提交最新状态，
    /// 防止几十个工具事件形成长队列后，灵动岛一直显示数分钟前的步骤。
    private func startRunningUpdateWorkerIfNeeded(
        activityKey: String,
        activity: Activity<AgentActivityAttributes>
    ) {
        guard updateWorkers[activityKey] == nil else { return }
        let token = UUID()
        updateWorkerTokens[activityKey] = token
        updateWorkers[activityKey] = Task { @MainActor [weak self] in
            guard let self else { return }
            defer {
                if self.updateWorkerTokens[activityKey] == token {
                    self.updateWorkers[activityKey] = nil
                    self.updateWorkerTokens[activityKey] = nil
                }
            }

            while !Task.isCancelled {
                do {
                    try await Task.sleep(for: self.updateCoalescingDelay)
                } catch {
                    return
                }
                guard !Task.isCancelled,
                      !self.endingSessionKeys.contains(activityKey),
                      let state = self.pendingRunningStates.removeValue(forKey: activityKey) else {
                    return
                }

                // 审批等关键状态必须先完成，之后才允许新的运行状态覆盖它。
                if let criticalOperation = self.operationTails[activityKey] {
                    await criticalOperation.value
                }
                guard !Task.isCancelled, !self.endingSessionKeys.contains(activityKey) else { return }
                await activity.update(ActivityContent(
                    state: state,
                    staleDate: Date().addingTimeInterval(self.staleInterval)
                ))

                if self.pendingRunningStates[activityKey] == nil { return }
            }
        }
    }

    @discardableResult
    private func cancelRunningUpdate(activityKey: String) -> Task<Void, Never>? {
        pendingRunningStates[activityKey] = nil
        updateWorkerTokens[activityKey] = nil
        let worker = updateWorkers.removeValue(forKey: activityKey)
        worker?.cancel()
        return worker
    }

    private func enqueue(
        activityKey: String,
        operation: @escaping @MainActor () async -> Void
    ) {
        let previous = operationTails[activityKey]
        operationTails[activityKey] = Task { @MainActor in
            if let previous { await previous.value }
            await operation()
        }
    }

    /// Only one Agent session owns the Dynamic Island at a time. Dismissing
    /// superseded activities also prevents iOS from switching the current one
    /// to the small secondary island and leaving an obsolete main island blank.
    private func dismissOtherActivities(except activityKey: String) {
        let activeKeys = activities.keys.filter { $0 != activityKey }
        for key in activeKeys {
            guard let activity = activities.removeValue(forKey: key) else { continue }
            cancelRunningUpdate(activityKey: key)
            latestStates[key] = nil
            operationTails[key] = nil
            endingSessionKeys.remove(key)
            Task { await activity.end(nil, dismissalPolicy: .immediate) }
        }

        let endedKeys = recentlyEndedActivities.keys.filter { $0 != activityKey }
        for key in endedKeys {
            dismissRecentlyEndedActivity(activityKey: key)
        }

        // 进程恢复时，ActivityKit 可能还持有未进入本地字典的旧实例。
        // 创建新活动前一并清理，避免旧实例占据主岛而新实例被挤到副岛。
        for activity in Activity<AgentActivityAttributes>.activities {
            let systemKey = key(
                gatewayID: activity.attributes.gatewayID,
                sessionID: activity.attributes.sessionID
            )
            guard systemKey != activityKey else { continue }
            removeTracking(activityKey: systemKey, activityID: activity.id)
            Task { await activity.end(nil, dismissalPolicy: .immediate) }
        }
    }

    private func removeTracking(activityKey: String, activityID: String) {
        if activities[activityKey]?.id == activityID {
            activities[activityKey] = nil
            latestStates[activityKey] = nil
            cancelRunningUpdate(activityKey: activityKey)
            operationTails[activityKey] = nil
            endingSessionKeys.remove(activityKey)
        }
        if recentlyEndedActivities[activityKey]?.id == activityID {
            recentlyEndedActivities[activityKey] = nil
            recentlyEndedCleanupTasks.removeValue(forKey: activityKey)?.cancel()
        }
    }

    private func dismissRecentlyEndedActivity(activityKey: String) {
        recentlyEndedCleanupTasks.removeValue(forKey: activityKey)?.cancel()
        guard let activity = recentlyEndedActivities.removeValue(forKey: activityKey) else { return }
        Task { await activity.end(nil, dismissalPolicy: .immediate) }
    }

    private func state(
        phase: AgentActivityPhase,
        status: String,
        detail: String? = nil,
        secondaryDetail: String? = nil,
        sourceLabel: String? = nil,
        command: String? = nil,
        rpcID: String? = nil,
        approvalID: String? = nil,
        toolName: String? = nil,
        stepKind: AgentActivityStepKind? = nil
    ) -> AgentActivityAttributes.ContentState {
        AgentActivityAttributes.ContentState(
            phase: phase,
            status: status,
            detail: detail,
            secondaryDetail: secondaryDetail,
            sourceLabel: sourceLabel,
            command: command,
            rpcID: rpcID,
            approvalID: approvalID,
            toolName: toolName,
            stepKind: stepKind,
            updatedAt: .now
        )
    }

    private func key(gatewayID: String, sessionID: String) -> String {
        "\(gatewayID)::\(sessionID)"
    }
}

/// 审批状态优先于同一工具调用随后到达的普通执行轨迹。
/// Gateway 会先发送 approval/requested，随后仍可能补发一次 tool/command 进度；
/// 后者不能把灵动岛从“等待审批”改回“正在执行”。
enum AgentLiveActivityUpdatePolicy {
    static func allows(
        current: AgentActivityAttributes.ContentState?,
        incoming: AgentActivityAttributes.ContentState
    ) -> Bool {
        guard incoming.phase == .running, let current else { return true }
        switch current.phase {
        case .awaitingApproval, .submittingApproval:
            return false
        case .failed:
            // 带 rpcID 的失败是“审批结果未确认”，必须保留检查入口。
            return current.rpcID == nil
        case .running, .approved, .rejected, .completed:
            return true
        }
    }
}
