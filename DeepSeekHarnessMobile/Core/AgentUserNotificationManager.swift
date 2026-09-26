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
    private var recentIdentifiers: [String]
    private var recentIdentifierSet: Set<String>
    @Published private(set) var pendingSessionRoute: AgentNotificationSessionRoute?

    private override init() {
        let identifiers = UserDefaults.standard.stringArray(forKey: defaultsKey) ?? []
        recentIdentifiers = identifiers
        recentIdentifierSet = Set(identifiers)
        super.init()
        center.delegate = self
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
                )
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

    private func scheduleOnce(
        identifier: String,
        threadIdentifier: String,
        gatewayID: String,
        sessionID: String,
        title: String,
        body: String
    ) {
        guard recentIdentifierSet.insert(identifier).inserted else { return }
        recentIdentifiers.append(identifier)
        if recentIdentifiers.count > maximumRememberedIdentifierCount {
            let overflow = recentIdentifiers.count - maximumRememberedIdentifierCount
            let removed = recentIdentifiers.prefix(overflow)
            recentIdentifiers.removeFirst(overflow)
            recentIdentifierSet.subtract(removed)
        }
        UserDefaults.standard.set(recentIdentifiers, forKey: defaultsKey)

        // 前台事件已经在会话界面展示；仍记录标识，避免重连后补弹旧通知。
        guard UIApplication.shared.applicationState == .background else { return }

        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        content.threadIdentifier = threadIdentifier
        content.userInfo = ["gatewayID": gatewayID, "sessionID": sessionID]
        center.add(UNNotificationRequest(identifier: identifier, content: content, trigger: nil))
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
