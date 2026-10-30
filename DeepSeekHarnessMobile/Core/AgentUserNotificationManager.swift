import Combine
import Foundation
import UIKit
import UserNotifications

struct AgentNotificationSessionRoute: Equatable {
    let gatewayID: String
    let sessionID: String
}

/// 将需要用户关注的 Agent 状态转换为系统本地通知。
/// 事件标识会持久化并限制数量，避免网关重连或事件重放时重复提醒。
@MainActor
final class AgentUserNotificationManager: NSObject, @preconcurrency UNUserNotificationCenterDelegate {
    static let shared = AgentUserNotificationManager()

    private let center = UNUserNotificationCenter.current()
    private let defaultsKey = "agentUserNotification.recentIdentifiers"
    private let maximumRememberedIdentifierCount = 256
    private let defaults: UserDefaults
    private let isInBackground: () -> Bool
    private let scheduleNotification: (UNNotificationRequest) -> Void
    private var recentIdentifiers: [String]
    private var recentIdentifierSet: Set<String>
    @Published private(set) var pendingSessionRoute: AgentNotificationSessionRoute?

    init(
        defaults: UserDefaults = .standard,
        isInBackground: @escaping () -> Bool = { UIApplication.shared.applicationState == .background },
        scheduleNotification: ((UNNotificationRequest) -> Void)? = nil
    ) {
        self.defaults = defaults
        self.isInBackground = isInBackground
        self.scheduleNotification = scheduleNotification ?? { UNUserNotificationCenter.current().add($0) }
        let identifiers = defaults.stringArray(forKey: defaultsKey) ?? []
        recentIdentifiers = identifiers
        recentIdentifierSet = Set(identifiers)
        super.init()
        if scheduleNotification == nil { center.delegate = self }
    }

    func requestAuthorizationIfNeeded() async {
        let settings = await center.notificationSettings()
        guard settings.authorizationStatus == .notDetermined else { return }
        _ = try? await center.requestAuthorization(options: [.alert, .sound])
    }

    func notifyApprovalRequired(
        gatewayID: String,
        requestID: String,
        sessionID: String,
        sessionTitle: String,
        detail: String?
    ) {
        let normalizedDetail = detail?.trimmingCharacters(in: .whitespacesAndNewlines)
        scheduleOnce(
            identifier: "agent.approval.\(gatewayID).\(requestID)",
            threadIdentifier: "agent.session.\(gatewayID).\(sessionID)",
            gatewayID: gatewayID,
            sessionID: sessionID,
            title: String(localized: "agent.notification.approval.title", defaultValue: "需要审批"),
            body: normalizedDetail?.isEmpty == false
                ? String(
                    localized: "agent.notification.approval.body",
                    defaultValue: "\(sessionTitle)：\(normalizedDetail!)"
                )
                : String(
                    localized: "agent.notification.approval.body.generic",
                    defaultValue: "\(sessionTitle)：Agent 请求批准本次操作"
                ),
            requiresResponse: true
        )
    }

    func notifyExecutionEnded(
        gatewayID: String,
        eventID: String,
        sessionID: String,
        sessionTitle: String,
        failed: Bool
    ) {
        scheduleOnce(
            identifier: "agent.execution-ended.\(gatewayID).\(eventID)",
            threadIdentifier: "agent.session.\(gatewayID).\(sessionID)",
            gatewayID: gatewayID,
            sessionID: sessionID,
            title: failed
                ? String(localized: "agent.notification.execution.failed.title", defaultValue: "执行失败")
                : String(localized: "agent.notification.execution.completed.title", defaultValue: "执行完成"),
            body: failed
                ? String(
                    localized: "agent.notification.execution.failed.body",
                    defaultValue: "\(sessionTitle)：打开 App 查看执行结果"
                )
                : String(
                    localized: "agent.notification.execution.completed.body",
                    defaultValue: "\(sessionTitle)：Agent 已完成本次任务"
                )
        )
    }

    func notifyQuestionRequired(
        gatewayID: String,
        requestID: String,
        sessionID: String,
        sessionTitle: String,
        prompt: String?
    ) {
        let normalizedPrompt = prompt?.trimmingCharacters(in: .whitespacesAndNewlines)
        scheduleOnce(
            identifier: "agent.question.\(gatewayID).\(requestID)",
            threadIdentifier: "agent.session.\(gatewayID).\(sessionID)",
            gatewayID: gatewayID,
            sessionID: sessionID,
            title: String(localized: "agent.notification.question.title", defaultValue: "需要回答"),
            body: normalizedPrompt?.isEmpty == false
                ? String(
                    localized: "agent.notification.question.body",
                    defaultValue: "\(sessionTitle)：\(normalizedPrompt!)"
                )
                : String(
                    localized: "agent.notification.question.body.generic",
                    defaultValue: "\(sessionTitle)：Agent 正在等待你的回答或选择"
                ),
            requiresResponse: true
        )
    }

    private func scheduleOnce(
        identifier: String,
        threadIdentifier: String,
        gatewayID: String,
        sessionID: String,
        title: String,
        body: String,
        requiresResponse: Bool = false
    ) {
        let inBackground = isInBackground()
        // 前台收到的待处理请求不标记为已提醒，切到后台后由待处理列表补发。
        guard inBackground || !requiresResponse else { return }
        guard recentIdentifierSet.insert(identifier).inserted else { return }
        recentIdentifiers.append(identifier)
        if recentIdentifiers.count > maximumRememberedIdentifierCount {
            let overflow = recentIdentifiers.count - maximumRememberedIdentifierCount
            let removed = recentIdentifiers.prefix(overflow)
            recentIdentifiers.removeFirst(overflow)
            recentIdentifierSet.subtract(removed)
        }
        defaults.set(recentIdentifiers, forKey: defaultsKey)

        // 前台执行结果已在会话界面展示，记录标识以免重连后补弹旧结果。
        guard inBackground else { return }

        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        content.threadIdentifier = threadIdentifier
        content.userInfo = ["gatewayID": gatewayID, "sessionID": sessionID]
        scheduleNotification(UNNotificationRequest(identifier: identifier, content: content, trigger: nil))
    }

    func clearPendingSessionRoute(_ route: AgentNotificationSessionRoute) {
        if pendingSessionRoute == route { pendingSessionRoute = nil }
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse
    ) async {
        guard response.actionIdentifier == UNNotificationDefaultActionIdentifier,
              let gatewayID = response.notification.request.content.userInfo["gatewayID"] as? String,
              let sessionID = response.notification.request.content.userInfo["sessionID"] as? String,
              !gatewayID.isEmpty, !sessionID.isEmpty else { return }
        pendingSessionRoute = AgentNotificationSessionRoute(gatewayID: gatewayID, sessionID: sessionID)
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        []
    }
}
