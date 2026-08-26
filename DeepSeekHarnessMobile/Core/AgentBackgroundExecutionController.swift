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
    private(set) var isAgentWorkActive = false
    private(set) var outstandingTurns = 0
    private(set) var sessionID: String?

    var onBackgroundAllowanceExpired: (() -> Void)?

    var keepsConnectionAlive: Bool {
        isAgentWorkActive && taskIdentifier != .invalid
    }

    convenience init() {
        self.init(application: UIApplication.shared)
    }

    init(application: BackgroundTaskApplication) {
        self.application = application
    }

    func applicationDidBecomeActive() {
        applicationIsInBackground = false
    }

    func applicationDidEnterBackground() {
        applicationIsInBackground = true
    }

    func begin(sessionID: String?, startsNewTurn: Bool) {
        if startsNewTurn {
            outstandingTurns += 1
        } else if outstandingTurns == 0 {
            outstandingTurns = 1
        }
        isAgentWorkActive = true
        if let sessionID { self.sessionID = sessionID }
        guard taskIdentifier == .invalid else { return }
        taskIdentifier = application.beginBackgroundTask(
            withName: "Complete DSH Agent Turn"
        ) { [weak self] in
            Task { @MainActor [weak self] in self?.expire() }
        }
    }

    func associateSessionIfNeeded(_ sessionID: String) {
        guard isAgentWorkActive, self.sessionID == nil else { return }
        self.sessionID = sessionID
    }

    func turnEnded(sessionID: String) {
        guard isAgentWorkActive, self.sessionID == sessionID else { return }
        outstandingTurns = max(0, outstandingTurns - 1)
        if outstandingTurns == 0 { finish() }
    }

    func cancel() {
        isAgentWorkActive = false
        outstandingTurns = 0
        sessionID = nil
        endTask()
    }

    private func finish() {
        cancel()
        if applicationIsInBackground { onBackgroundAllowanceExpired?() }
    }

    private func expire() {
        endTask()
        if applicationIsInBackground { onBackgroundAllowanceExpired?() }
    }

    private func endTask() {
        let identifier = taskIdentifier
        guard identifier != .invalid else { return }
        taskIdentifier = .invalid
        application.endBackgroundTask(identifier)
    }
}
