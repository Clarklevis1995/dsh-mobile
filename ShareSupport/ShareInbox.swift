import Foundation

struct SharedConversation: Codable, Identifiable {
    var gatewayID: String
    var sessionID: String
    var title: String
    var workspace: String
    var workspaceID: String? = nil
    var workspaceScope: String { gatewayID + ":" + (workspaceID ?? "__ungrouped__") }
    var id: String { gatewayID + ":" + sessionID }
    var suggestionIdentifier: String { "whaleness.conversation." + Data(id.utf8).base64EncodedString() }
}

struct SharedIncomingDraft: Codable, Identifiable {
    var id: UUID
    var target: SharedConversation
    var text: String
    var files: [String]
    var createsNewConversation: Bool? = nil
}

struct SharedRecentConversation: Codable {
    var conversation: SharedConversation
    var interactedAt: Date
}

/// 主应用和扩展只交换本地值，不向扩展暴露网关凭据。
enum ShareInbox {
    #if DEBUG
    static var testDirectory: URL?
    #endif
    static var root: URL? {
        #if DEBUG
        if let testDirectory { return testDirectory }
        #endif
        guard let group = Bundle.main.object(forInfoDictionaryKey: "ShareAppGroup") as? String else { return nil }
        return FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group)?
            .appendingPathComponent("IncomingShares", isDirectory: true)
    }
    static func directory() throws -> URL {
        guard let root else { throw CocoaError(.fileNoSuchFile) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        return root
    }
    static func catalog() -> [SharedConversation] {
        guard let url = root?.appendingPathComponent("catalog.json"), let data = try? Data(contentsOf: url) else { return [] }
        return (try? JSONDecoder().decode([SharedConversation].self, from: data)) ?? []
    }
    static func updateCatalog(gatewayID: String, conversations: [SharedConversation]) {
        guard let directory = try? directory() else { return }
        let entries = catalog().filter { $0.gatewayID != gatewayID } + conversations
        try? JSONEncoder().encode(entries).write(to: directory.appendingPathComponent("catalog.json"), options: .atomic)
    }
    static func setActiveGateway(_ gatewayID: String) {
        guard gatewayID != "unpaired", let directory = try? directory() else { return }
        try? PropertyListEncoder().encode([gatewayID]).write(to: directory.appendingPathComponent("active-gateway.plist"), options: .atomic)
    }
    static func defaultDestination() -> SharedConversation {
        let gatewayID = root.flatMap { try? Data(contentsOf: $0.appendingPathComponent("active-gateway.plist")) }
            .flatMap { try? PropertyListDecoder().decode([String].self, from: $0) }?.first ?? ""
        return SharedConversation(gatewayID: gatewayID, sessionID: "", title: "新会话", workspace: "默认工作区")
    }
    static func recentConversations() -> [SharedRecentConversation] {
        guard let url = root?.appendingPathComponent("recent-conversations.plist"), let data = try? Data(contentsOf: url) else { return [] }
        return (try? PropertyListDecoder().decode([SharedRecentConversation].self, from: data)) ?? []
    }
    @discardableResult
    static func recordConversation(_ conversation: SharedConversation, at date: Date = .now) -> [SharedRecentConversation] {
        var recent = recentConversations().filter { $0.conversation.id != conversation.id }
        recent.append(SharedRecentConversation(conversation: conversation, interactedAt: date))
        recent.sort { $0.interactedAt > $1.interactedAt }
        recent = Array(recent.prefix(3))
        if let directory = try? directory() {
            try? PropertyListEncoder().encode(recent).write(to: directory.appendingPathComponent("recent-conversations.plist"), options: .atomic)
        }
        return recent
    }
    static func suggestedDestination(identifier: String) -> SharedConversation? {
        catalog().first { $0.suggestionIdentifier == identifier }
            ?? recentConversations().first { $0.conversation.suggestionIdentifier == identifier }?.conversation
    }
    static func resolve(_ draft: SharedIncomingDraft, to conversation: SharedConversation) throws -> SharedIncomingDraft {
        var resolved = draft
        resolved.target = conversation
        resolved.createsNewConversation = false
        try save(resolved)
        return resolved
    }

    static func pending() -> [SharedIncomingDraft] {
        guard let root, let urls = try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil) else { return [] }
        return urls.filter { $0.pathExtension == "json" && $0.lastPathComponent != "catalog.json" }.sorted { $0.lastPathComponent < $1.lastPathComponent }.compactMap {
            guard let data = try? Data(contentsOf: $0) else { return nil }
            return try? JSONDecoder().decode(SharedIncomingDraft.self, from: data)
        }
    }
    static func save(_ draft: SharedIncomingDraft) throws {
        try JSONEncoder().encode(draft).write(to: directory().appendingPathComponent(draft.id.uuidString + ".json"), options: .atomic)
    }
    static func remove(_ draft: SharedIncomingDraft) {
        guard let root else { return }
        try? FileManager.default.removeItem(at: root.appendingPathComponent(draft.id.uuidString + ".json"))
        try? FileManager.default.removeItem(at: root.appendingPathComponent(draft.id.uuidString))
    }
    static func fileURLs(_ draft: SharedIncomingDraft) -> [URL] {
        guard let root else { return [] }
        return draft.files.filter { !$0.contains("/") && $0 != "." && $0 != ".." }.map {
            root.appendingPathComponent(draft.id.uuidString).appendingPathComponent($0)
        }
    }
}
