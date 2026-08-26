import Foundation

protocol AppPreferences: AnyObject {
    var endpoint: String { get set }
    var selectedWorkspaceID: String? { get set }

    func loadSessions() -> [SessionSummary]
    func saveSessions(_ sessions: [SessionSummary])
    func performMigrations()
}

final class UserDefaultsAppPreferences: AppPreferences {
    static let defaultEndpoint = "ws://127.0.0.1:3080/ws/mobile"

    private enum Key {
        static let endpoint = "gateway.endpoint"
        static let selectedWorkspaceID = "gateway.selectedWorkspaceId"
        static let sessions = "gateway.sessions"
        static let conversationScrollAnchors = "gateway.conversationScrollAnchors"
        static let manuallyPositionedSessionIDs = "gateway.manuallyPositionedSessionIds"
    }

    private let userDefaults: UserDefaults

    init(userDefaults: UserDefaults = .standard) {
        self.userDefaults = userDefaults
    }

    var endpoint: String {
        get { userDefaults.string(forKey: Key.endpoint) ?? Self.defaultEndpoint }
        set { userDefaults.set(newValue, forKey: Key.endpoint) }
    }

    var selectedWorkspaceID: String? {
        get { userDefaults.string(forKey: Key.selectedWorkspaceID) }
        set {
            if let newValue {
                userDefaults.set(newValue, forKey: Key.selectedWorkspaceID)
            } else {
                userDefaults.removeObject(forKey: Key.selectedWorkspaceID)
            }
        }
    }

    func loadSessions() -> [SessionSummary] {
        guard let data = userDefaults.data(forKey: Key.sessions) else { return [] }
        return (try? JSONDecoder().decode([SessionSummary].self, from: data)) ?? []
    }

    func saveSessions(_ sessions: [SessionSummary]) {
        guard let data = try? JSONEncoder().encode(sessions) else { return }
        userDefaults.set(data, forKey: Key.sessions)
    }

    func performMigrations() {
        userDefaults.removeObject(forKey: Key.conversationScrollAnchors)
        userDefaults.removeObject(forKey: Key.manuallyPositionedSessionIDs)
    }
}
