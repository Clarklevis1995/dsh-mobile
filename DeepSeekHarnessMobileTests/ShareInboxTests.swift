import XCTest
import SwiftUI
import UIKit
import Intents
import class DeepSeekHarnessShared.GatewayQuoteTextParts
@testable import DeepSeekHarnessMobile

final class ShareInboxTests: XCTestCase {
    private var directory: URL!
    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        ShareInbox.testDirectory = directory
    }
    override func tearDownWithError() throws {
        ShareInbox.testDirectory = nil
        try? FileManager.default.removeItem(at: directory)
    }
    func testAcknowledgingOneSharePreservesOtherShareAndItsFiles() throws {
        let target = SharedConversation(gatewayID: "host", sessionID: "chat", title: "会话", workspace: "工作区")
        let first = SharedIncomingDraft(id: UUID(), target: target, text: "https://example.com", files: ["photo.png"])
        let second = SharedIncomingDraft(id: UUID(), target: target, text: "第二次分享", files: ["file.pdf"])
        for draft in [first, second] {
            let folder = try ShareInbox.directory().appendingPathComponent(draft.id.uuidString)
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            try Data([1, 2, 3]).write(to: folder.appendingPathComponent(draft.files[0]))
            try ShareInbox.save(draft)
        }
        XCTAssertEqual(ShareInbox.pending().count, 2)
        ShareInbox.remove(first)
        XCTAssertEqual(ShareInbox.pending().map(\.id), [second.id])
        XCTAssertTrue(FileManager.default.fileExists(atPath: ShareInbox.fileURLs(second)[0].path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: directory.appendingPathComponent(first.id.uuidString).path))
    }
    func testCatalogRefreshKeepsOtherGatewaysAndReplacesStaleSessions() {
        let first = SharedConversation(gatewayID: "one", sessionID: "old", title: "旧会话", workspace: "A")
        let second = SharedConversation(gatewayID: "two", sessionID: "chat", title: "会话", workspace: "B")
        ShareInbox.updateCatalog(gatewayID: "one", conversations: [first])
        ShareInbox.updateCatalog(gatewayID: "two", conversations: [second])
        ShareInbox.updateCatalog(gatewayID: "one", conversations: [])
        XCTAssertEqual(ShareInbox.catalog().map(\.gatewayID), ["two"])
    }
    func testFileReferencesCannotEscapeShareDirectory() {
        let target = SharedConversation(gatewayID: "host", sessionID: "chat", title: "会话", workspace: "A")
        let draft = SharedIncomingDraft(id: UUID(), target: target, text: "", files: ["../secret", "/absolute", "..", ".", "safe.pdf"])
        XCTAssertEqual(ShareInbox.fileURLs(draft).map(\.lastPathComponent), ["safe.pdf"])
    }
}

extension ShareInboxTests {
    func testHostAppDeclaresMessageIntentForShareSuggestions() {
        let activityTypes = Bundle.main.object(forInfoDictionaryKey: "NSUserActivityTypes") as? [String]
        XCTAssertTrue(activityTypes?.contains("INSendMessageIntent") == true,
            "主应用必须声明 INSendMessageIntent，否则系统会拒绝会话建议上报，即使分享扩展已声明支持。")
    }

    @MainActor
    func testRestoringRecentConversationsPreservesMessageDatesAndRunsOnce() async throws {
        let suite = "ShareSuggestionsTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        for index in 0..<3 {
            let target = SharedConversation(gatewayID: "host", sessionID: "chat-\(index)", title: "会话", workspace: "A")
            ShareInbox.recordConversation(target, at: Date(timeIntervalSince1970: Double(index + 1)))
        }
        let original = ShareInbox.recentConversations()
        var submitted: [INInteraction] = []
        let suggestions = ConversationShareSuggestions(defaults: defaults) { interaction in
            submitted.append(interaction)
            return true
        }
        suggestions.restoreRecentConversationsIfNeeded()
        await suggestions.awaitPendingDonationsForTesting()
        XCTAssertEqual(submitted.map { $0.dateInterval?.start }, original.map { Optional($0.interactedAt) })
        XCTAssertEqual(submitted.compactMap { ($0.intent as? INSendMessageIntent)?.conversationIdentifier },
            original.map { $0.conversation.suggestionIdentifier })
        XCTAssertTrue(submitted.allSatisfy { $0.direction == .outgoing })
        // 进程重建后也不能把已补交的历史聊天再次上报。
        let restarted = ConversationShareSuggestions(defaults: defaults) { interaction in
            submitted.append(interaction)
            return true
        }
        restarted.restoreRecentConversationsIfNeeded()
        await restarted.awaitPendingDonationsForTesting()
        XCTAssertEqual(submitted.count, 3)
        XCTAssertEqual(ShareInbox.recentConversations().map(\.interactedAt), original.map(\.interactedAt))
    }

    @MainActor
    func testRejectedRestorationIsRetriedWithoutCreatingANewMessage() async throws {
        let suite = "ShareSuggestionsTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let target = SharedConversation(gatewayID: "host", sessionID: "chat", title: "会话", workspace: "A")
        let originalDate = Date(timeIntervalSince1970: 100)
        ShareInbox.recordConversation(target, at: originalDate)
        var count = 0
        let suggestions = ConversationShareSuggestions(defaults: defaults) { _ in
            count += 1
            return count > 1
        }
        suggestions.restoreRecentConversationsIfNeeded()
        await suggestions.awaitPendingDonationsForTesting()
        suggestions.restoreRecentConversationsIfNeeded()
        await suggestions.awaitPendingDonationsForTesting()
        suggestions.restoreRecentConversationsIfNeeded()
        await suggestions.awaitPendingDonationsForTesting()
        XCTAssertEqual(count, 2, "失败后重试，成功后停止补交")
        XCTAssertEqual(ShareInbox.recentConversations().first?.interactedAt, originalDate)
        XCTAssertTrue(ShareInbox.pending().isEmpty)
    }

    @MainActor
    func testSystemAcceptsDeclaredMessageIntent() async throws {
        #if targetEnvironment(simulator)
        let target = SharedConversation(gatewayID: "share-regression-test", sessionID: UUID().uuidString,
            title: "分享建议回归测试", workspace: "测试")
        let interaction = ConversationShareSuggestions.makeInteraction(for:
            SharedRecentConversation(conversation: target, interactedAt: .now))
        let donationError: Error? = await withCheckedContinuation { continuation in
            interaction.donate { continuation.resume(returning: $0) }
        }
        // 模拟器系统集成测试结束后清理测试交互，不污染分享建议。
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            INInteraction.delete(with: [interaction.identifier]) { _ in continuation.resume() }
        }
        XCTAssertNil(donationError, "系统必须实际接受上报：\(donationError?.localizedDescription ?? "")")
        #else
        throw XCTSkip("仅在模拟器中提交测试交互，避免改变真机推荐数据")
        #endif
    }

    func testRecentConversationsKeepThreeDistinctTargetsInMessageOrder() {
        for index in 0..<4 {
            let target = SharedConversation(gatewayID: "host", sessionID: "chat-\(index)", title: "会话 \(index)", workspace: "A")
            ShareInbox.recordConversation(target, at: Date(timeIntervalSince1970: Double(index)))
        }
        XCTAssertEqual(ShareInbox.recentConversations().map { $0.conversation.sessionID }, ["chat-3", "chat-2", "chat-1"])
        let revisited = SharedConversation(gatewayID: "host", sessionID: "chat-1", title: "重命名会话", workspace: "A")
        ShareInbox.recordConversation(revisited, at: Date(timeIntervalSince1970: 10))
        XCTAssertEqual(ShareInbox.recentConversations().map { $0.conversation.sessionID }, ["chat-1", "chat-3", "chat-2"])
        XCTAssertEqual(ShareInbox.suggestedDestination(identifier: revisited.suggestionIdentifier)?.title, "重命名会话")
        XCTAssertNil(ShareInbox.suggestedDestination(identifier: "unknown"))
        XCTAssertTrue(ShareInbox.pending().isEmpty, "配置和最近会话数据不能被识别成待处理分享")
    }

    func testDefaultIconUsesActiveGatewayWithoutReusingExistingSession() {
        XCTAssertEqual(ShareInbox.defaultDestination().gatewayID, "")
        ShareInbox.setActiveGateway("host-two")
        let recent = SharedConversation(gatewayID: "host-one", sessionID: "existing-chat", title: "已有会话", workspace: "其他工作区")
        ShareInbox.recordConversation(recent)
        let target = ShareInbox.defaultDestination()
        XCTAssertEqual(target.gatewayID, "host-two")
        XCTAssertEqual(target.sessionID, "")
        XCTAssertEqual(target.workspace, "默认工作区")
        XCTAssertEqual(ShareInbox.suggestedDestination(identifier: recent.suggestionIdentifier)?.sessionID, "existing-chat")
    }

    func testResolvingNewSessionPersistsOneCreationAndKeepsAttachmentReferences() throws {
        let draft = SharedIncomingDraft(id: UUID(), target: ShareInbox.defaultDestination(), text: "https://example.com",
            files: ["image.png"], createsNewConversation: true)
        try ShareInbox.save(draft)
        let target = SharedConversation(gatewayID: "host", sessionID: "created-session", title: "新会话", workspace: "默认工作区")
        let resolved = try ShareInbox.resolve(draft, to: target)
        XCTAssertEqual(resolved.id, draft.id)
        XCTAssertEqual(resolved.files, draft.files)
        XCTAssertEqual(resolved.createsNewConversation, false)
        let restored = try XCTUnwrap(ShareInbox.pending().first)
        XCTAssertEqual(restored.target.sessionID, "created-session")
        XCTAssertEqual(restored.createsNewConversation, false, "恢复时不能再创建第二个会话")
    }
}

extension ShareInboxTests {
    @MainActor
    func testSharedLinkBecomesQuoteInNewDefaultWorkspaceDraftWithoutSending() async throws {
        let gateway = GatewayClient()
        let store = AppStore(preferences: ShareTestPreferences(), gatewayClient: gateway, gatewayLocalID: "share-test", chatHistoryCache: nil)
        defer { store.disconnect() }
        var requests: [[String: Any]] = []
        gateway.installConnectedTransportForTesting { text in
            guard let request = try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any] else { return }
            requests.append(request)
            if request["type"] as? String == "session-create", let id = request["requestId"] as? String {
                Task { @MainActor in
                    _ = gateway.acceptSessionCreationFrame(GatewayFrame(kind: "session-created", sessionId: "shared-new-session", requestId: id))
                }
            }
        }
        gateway.onFrame?(try GatewayWireDecoder.decode(Data(
            #"{"kind":"hello","protocol":3,"capabilities":["session-create","message-quotes-v1"]}"#.utf8)))
        await store.awaitPendingKMPEventDeliveriesForTesting()
        let created = await store.prepareNewConversation(useDefaultWorkspace: true)
        XCTAssertTrue(created)
        let create = try XCTUnwrap(requests.first { $0["type"] as? String == "session-create" })
        XCTAssertNil(create["workspaceId"], "默认工作区不能被当前选择的工作区覆盖")
        XCTAssertNil(create["cwd"])
        let shared = SharedIncomingDraft(id: UUID(), target: ShareInbox.defaultDestination(), text: "https://example.com/shared", files: [], createsNewConversation: true)
        let coordinator = IncomingShareCoordinator.shared
        let previousPending = coordinator.pending
        defer { coordinator.pending = previousPending }
        coordinator.pending = shared
        try coordinator.resolveNewConversation(shared, to: SharedConversation(gatewayID: "share-test", sessionID: "shared-new-session", title: "新会话", workspace: "默认工作区"))
        let host = UIHostingController(rootView: ConversationView(activeView: .constant(0)).environmentObject(store))
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 390, height: 844)
        window.rootViewController = host
        window.makeKeyAndVisible()
        defer { window.isHidden = true; window.rootViewController = nil }
        host.view.frame = window.bounds
        host.view.layoutIfNeeded()
        for _ in 0..<100 where coordinator.pending != nil { try await Task.sleep(for: .milliseconds(20)) }
        XCTAssertNil(coordinator.pending, "会话加载后应消费分享草稿")
        try await Task.sleep(for: .milliseconds(300))
        host.view.layoutIfNeeded()
        let rendered = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
            window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
        }
        let quoteJSON = try XCTUnwrap(GatewayQuoteTextParts.shared.parseJson(text: store.slashCommands.inputText))
        let parts = try JSONDecoder().decode([GatewayTextPart].self, from: Data(quoteJSON.utf8))
        XCTAssertEqual(parts.filter { $0.type == "quote" }.map(\.text), [shared.text],
            "实际输入框草稿需要保留 quote 类型，而不是把 URL 插入正文")
        let screenshot = XCTAttachment(image: rendered)
        screenshot.name = "分享链接进入新会话引用草稿"
        screenshot.lifetime = .keepAlways
        add(screenshot)
        XCTAssertFalse(requests.contains { ["message", "send"].contains($0["type"] as? String ?? "") }, "导入分享不能自动发送消息")
        XCTAssertEqual(requests.filter { $0["type"] as? String == "session-create" }.count, 1)
    }


}

private final class ShareTestPreferences: AppPreferences {
    var endpoint = "ws://share-test.invalid/ws/mobile"
    var automaticallyConnect = false
    var selectedWorkspaceID: String? = "different-workspace"
    func loadSessions() -> [SessionSummary] { [] }
    func saveSessions(_ sessions: [SessionSummary]) {}
    func performMigrations() {}
}

extension ShareInboxTests {
    @MainActor
    func testDeepLinkPrefersRequestedShareWithoutDiscardingOlderPendingDraft() throws {
        let coordinator = IncomingShareCoordinator.shared
        let previous = coordinator.pending
        defer { coordinator.pending = previous }
        let older = SharedIncomingDraft(id: UUID(), target: SharedConversation(gatewayID: "old-host", sessionID: "old-session", title: "旧会话", workspace: "A"), text: "older", files: [])
        let requested = SharedIncomingDraft(id: UUID(), target: ShareInbox.defaultDestination(), text: "newest", files: [], createsNewConversation: true)
        try ShareInbox.save(older)
        try ShareInbox.save(requested)
        coordinator.pending = older
        coordinator.refresh(preferredID: requested.id)
        XCTAssertEqual(coordinator.pending?.id, requested.id)
        XCTAssertEqual(Set(ShareInbox.pending().map(\.id)), Set([older.id, requested.id]))
    }
}
