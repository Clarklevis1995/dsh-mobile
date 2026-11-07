import XCTest
import DeepSeekHarnessShared
@testable import DeepSeekHarnessMobile

final class ChatHistoryCacheTests: XCTestCase {
    @MainActor
    func testSubscriptionAndEmptyQueueDoNotForceCachedHistoryOntoMainThread() async throws {
        let directory = try makeDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let suite = UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = UserDefaultsAppPreferences(userDefaults: defaults)
        preferences.automaticallyConnect = false
        let session = SessionSummary(id: "s1", title: "缓存会话", lastActivity: .distantPast,
                                     isRunning: false, hasUnread: false)
        preferences.saveSessions([session])
        let cache = ChatHistoryCache(url: directory.appendingPathComponent("subscription.db"))
        let saved = await cache.save(gatewayID: "g", sessionID: session.id, json: try window(session.id, text: "后台缓存正文"))
        XCTAssertTrue(saved)
        let store = AppStore(preferences: preferences, gatewayLocalID: "g", chatHistoryCache: cache)
        defer { store.deactivateGateway() }
        store.gateway.installConnectedTransportForTesting()
        store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(
            #"{"kind":"hello","historyFormatVersion":1,"capabilities":["assistant-stream-v1"]}"#.utf8)))
        await store.awaitPendingKMPEventDeliveriesForTesting()
        let gate = ChatProtectionCommitBarrier()
        store.conversationHistoryPreparationForTesting = { input in
            await gate.blockFirstCommit()
            return try KMPConversationStoreAdapter.prepareHistory(input)
        }
        _ = await store.prepareConversation(for: session)
        for _ in 0..<200 {
            if await gate.hasEntered { break }
            try await Task.sleep(for: .milliseconds(5))
        }
        let entered = await gate.hasEntered
        XCTAssertTrue(entered)
        await store.activatePreparedConversation(sessionID: session.id)
        for json in [
            #"{"kind":"subscribed","sessionId":"s1","subscriptionId":"sub","assistantStream":true}"#,
            #"{"kind":"session-queue","sessionId":"s1","items":[]}"#,
            #"{"kind":"session-queues","queues":{"s1":[]}}"#
        ] {
            store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(json.utf8)))
            await store.awaitPendingKMPEventDeliveriesForTesting(includingHistoryPreparation: false)
        }
        try await Task.sleep(for: .milliseconds(50))
        XCTAssertTrue(store.renderedConversationItems[session.id, default: []].isEmpty,
            "无临时内容的订阅和空队列不能绕过后台 worker，同步重算缓存")
        await gate.resume()
        await store.awaitPendingKMPEventDeliveriesForTesting()
        await store.awaitConversationProjectionForTesting(sessionID: session.id, expectedText: "后台缓存正文")
        XCTAssertEqual(store.renderedConversationItems[session.id]?.last?.text, "后台缓存正文")
        XCTAssertNil(store.lastError)
        await cache.close()
    }

    @MainActor
    func testHistoryReconciliationInsertsMissingHeadWithoutReplacingCachedRows() async throws {
        let history = KMPHistoryStoreAdapter()
        let conversation = KMPConversationStoreAdapter()
        let cached = (2...81).map { sequence in
            SessionEvent(sessionId: "s", seq: sequence, time: Double(sequence),
                event: GatewayEvent(type: "user/message", text: "正文 \(sequence)"))
        }
        try conversation.replace(sessionID: "s", events: cached)
        var fullReplacements = 0
        conversation.onChange = { if $0.replacesAll { fullReplacements += 1 } }
        let authoritative = [SessionEvent(sessionId: "s", seq: 1, time: 1,
            event: GatewayEvent(type: "user/message", text: "补回首条"))] + cached
        _ = try history.installSnapshot(sessionID: "s", events: authoritative, hasMore: false, nextBeforeSequence: nil)
        let snapshot = try XCTUnwrap(history.projectionSnapshot(for: "s"))
        XCTAssertFalse(try conversation.appendHistory(sessionID: "s", snapshot: snapshot, lastSequence: 81))
        let input = try XCTUnwrap(conversation.historyPreparation(sessionID: "s", snapshot: snapshot))
        let prepared = try await Task.detached { try KMPConversationStoreAdapter.prepareHistory(input) }.value
        XCTAssertTrue(try conversation.commitPreparedHistory(prepared, sessionID: "s", lastSequence: 81))
        XCTAssertEqual(fullReplacements, 0)
        XCTAssertEqual(conversation.items(for: "s").count, 81)
        XCTAssertEqual(conversation.items(for: "s").first?.text, "补回首条")
        XCTAssertEqual(conversation.items(for: "s").dropFirst().map(\.text), cached.map { $0.event.text! })
        XCTAssertNil(conversation.runtimeError)
    }

    @MainActor
    func testHistoryAppendPreservesBaselineAndHandlesAuthoritativeDeletion() async throws {
        let history = KMPHistoryStoreAdapter()
        let conversation = KMPConversationStoreAdapter()
        let old = SessionEvent(sessionId: "s", seq: 1, time: 1, event: GatewayEvent(type: "user/message", text: "原有正文"))
        let new = SessionEvent(sessionId: "s", seq: 2, time: 2, event: GatewayEvent(type: "user/message", text: "新增正文"))
        try conversation.replace(sessionID: "s", events: [old])
        _ = try history.installSnapshot(sessionID: "s", events: [old, new], hasMore: false, nextBeforeSequence: nil)
        let snapshot = try XCTUnwrap(history.projectionSnapshot(for: "s"))
        XCTAssertTrue(try conversation.appendHistory(sessionID: "s", snapshot: snapshot, lastSequence: 2))
        XCTAssertEqual(conversation.items(for: "s").map(\.text), ["原有正文", "新增正文"])
        _ = try history.installSnapshot(sessionID: "s", events: [], hasMore: false, nextBeforeSequence: nil)
        let empty = try XCTUnwrap(history.projectionSnapshot(for: "s"))
        XCTAssertFalse(try conversation.appendHistory(sessionID: "s", snapshot: empty, lastSequence: -1))
        let input = try XCTUnwrap(conversation.historyPreparation(sessionID: "s", snapshot: empty))
        let prepared = try await Task.detached { try KMPConversationStoreAdapter.prepareHistory(input) }.value
        XCTAssertTrue(try conversation.commitPreparedHistory(prepared, sessionID: "s", lastSequence: -1))
        XCTAssertTrue(conversation.items(for: "s").isEmpty)
        XCTAssertEqual(conversation.lastSequenceBySessionID["s"], -1)
        XCTAssertNil(conversation.runtimeError)
    }

    @MainActor
    func testReadingBatchContinuesToolOnlyRoomPageUntilBodyWithoutAnotherGesture() async throws {
        let directory = try makeDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let suite = UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = UserDefaultsAppPreferences(userDefaults: defaults)
        preferences.automaticallyConnect = false
        let session = SessionSummary(id: "reading", title: "离线批量", lastActivity: .distantPast, isRunning: false, hasUnread: false)
        preferences.saveSessions([session])
        let cache = ChatHistoryCache(url: directory.appendingPathComponent("reading.db"))
        let records = (1...400).map { seq -> [String: Any] in
            let event: [String: Any]
            if seq <= 60 { event = ["type": "user/message", "text": "old \(seq)"] }
            else if seq == 400 { event = ["type": "assistant/message", "text": "existing"] }
            else { event = ["type": "tool/call", "name": "fixture", "arguments": ["value": "tool use"]] }
            return ["sessionId": session.id, "seq": seq, "time": Double(seq), "event": event]
        }
        let object: [String: Any] = ["schema": 1, "sessionId": session.id, "historyFormatVersion": 1,
            "persistentCursor": 400, "hasMore": false, "events": records]
        let saved = await cache.save(gatewayID: "reading-g", sessionID: session.id,
            json: String(decoding: try JSONSerialization.data(withJSONObject: object), as: UTF8.self), generation: 1)
        XCTAssertTrue(saved)
        let store = AppStore(preferences: preferences, gatewayLocalID: "reading-g", chatHistoryCache: cache)
        defer { store.deactivateGateway() }
        _ = await store.prepareConversation(for: session)
        await store.awaitPendingKMPEventDeliveriesForTesting()
        await store.awaitConversationProjectionForTesting(sessionID: session.id, expectedText: "existing")
        XCTAssertEqual(store.events[session.id]?.first?.seq, 321, "首屏仍保持80事件，不改变首屏成本")
        let consumer = UUID()
        var began = 0, terminal = 0
        var outcomes: [HistoryPresentationOutcome] = []
        store.attachHistoryPresentationConsumer(sessionID: session.id, consumer: .init(id: consumer) {
            if case .began = $0 { began += 1 }
            if case .terminal = $0 { terminal += 1 }
            if case .finished(_, let result) = $0 { outcomes.append(result) }
        })
        XCTAssertTrue(store.requestOlderHistoryPresentation(sessionID: session.id, consumerID: consumer,
            readingWindow: CGSize(width: 320, height: 200)))
        let timeline = store.conversationTimeline(for: session.id)
        for _ in 0..<600 where timeline.currentSnapshot.historyPresentationStamp == nil { try await Task.sleep(for: .milliseconds(5)) }
        XCTAssertEqual(timeline.currentSnapshot.items.filter { $0.kind == .user }.count, 60)
        XCTAssertEqual(timeline.currentSnapshot.items.filter { $0.kind == .tool }.count, 339)
        XCTAssertEqual(began, 1)
        XCTAssertEqual(terminal, 1)
        XCTAssertTrue(outcomes.isEmpty)
        XCTAssertFalse(store.gateway.state.isConnected)
        let stamp = try XCTUnwrap(timeline.currentSnapshot.historyPresentationStamp)
        store.acknowledgeHistoryPresentation(sessionID: session.id, consumerID: consumer, stamp: stamp)
        XCTAssertEqual(outcomes, [.presented])
        store.detachHistoryPresentationConsumer(sessionID: session.id, consumerID: consumer)
        await cache.close()
    }

    @MainActor
    func testOfflineConversationRestoresRoomContentThenAcceptsAuthoritativeSnapshot() async throws {
        let directory = try makeDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let suite = UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = UserDefaultsAppPreferences(userDefaults: defaults)
        preferences.automaticallyConnect = false
        let session = SessionSummary(id: "s1", title: "聊天", lastActivity: Date(timeIntervalSince1970: 1),
                                     isRunning: false, hasUnread: false)
        preferences.saveSessions([session])
        let cache = ChatHistoryCache(url: directory.appendingPathComponent("history.db"))
        let saved = await cache.save(gatewayID: "g", sessionID: "s1", json: try window("s1", text: "磁盘正文"), generation: 1)
        XCTAssertTrue(saved)
        let store = AppStore(preferences: preferences, gatewayLocalID: "g", chatHistoryCache: cache)
        let prepared = await store.prepareConversation(for: session)
        XCTAssertTrue(prepared)
        await store.awaitConversationProjectionForTesting(sessionID: "s1", expectedText: "磁盘正文")
        XCTAssertEqual(store.renderedConversationItems["s1"]?.last?.text, "磁盘正文")
        XCTAssertEqual(store.sessions.first?.lastActivity, session.lastActivity, "缓存恢复不能作为新消息更新会话")
        for json in [
            #"{"kind":"hello","historyFormatVersion":1,"capabilities":["assistant-stream-v1"]}"#,
            #"{"kind":"subscribed","sessionId":"s1","subscriptionId":"sub","assistantStream":true}"#,
            #"{"kind":"session-snapshot","sessionId":"s1","subscriptionId":"sub","streamId":"stream","historyFormatVersion":1,"cursor":9,"hasMore":false,"events":[{"type":"assistant/message","seq":9,"time":9,"data":{"message":{"content":[{"type":"text","text":"服务端正文"}]}}}]}"#
        ] {
            store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(json.utf8)))
            await store.awaitPendingKMPEventDeliveriesForTesting()
        }
        await store.awaitConversationProjectionForTesting(sessionID: "s1", expectedText: "服务端正文")
        XCTAssertEqual(store.renderedConversationItems["s1"]?.last?.text, "服务端正文")
        XCTAssertNil(store.lastError)
        store.deactivateGateway()
        await cache.close()
    }

    @MainActor
    func testColdLaunchDoesNotQueryLegacySelectedSubagentOnWorkspace() async throws {
        let directory = try makeDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let suite = UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let cache = ChatHistoryCache(url: directory.appendingPathComponent("legacy-child.db"))
        let lead = SessionSummary(id: "lead", title: "团队", lastActivity: .now, isRunning: false, hasUnread: false)
        let snapshot = KMPSessionListSnapshot(sessions: [lead], archivedSessionIds: [], selectedSessionId: "scout")
        let json = try XCTUnwrap(SharedOfflineCatalogCodec().create(
            sessionListJson: String(decoding: JSONEncoder().encode(snapshot), as: UTF8.self),
            workspacesJson: "[]", selectedWorkspaceId: nil))
        let owner = cache.makeProtectionOwner(gatewayID: "legacy-child")
        let saved = await cache.saveOfflineCatalog(owner: owner, json: json)
        XCTAssertTrue(saved)
        let store = AppStore(preferences: UserDefaultsAppPreferences(userDefaults: defaults), gatewayLocalID: "legacy-child", chatHistoryCache: cache)
        await store.awaitOfflineCatalogForTesting()
        XCTAssertEqual(store.selectedSessionId, "scout")
        XCTAssertFalse(store.teamStore.isChild(sessionId: "scout"))
        var sent: [[String: Any]] = []
        store.gateway.installConnectedTransportForTesting { payload in
            sent.append((try? JSONSerialization.jsonObject(with: Data(payload.utf8))) as? [String: Any] ?? [:])
        }
        store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(#"{"kind":"hello","protocol":3,"historyFormatVersion":4,"capabilities":["agent-team-v1","subagent-conversations-v1","session-agent-preset","tasks","goals"]}"#.utf8)))
        await store.awaitPendingKMPEventDeliveriesForTesting()
        XCTAssertFalse(sent.contains { $0["sessionId"] as? String == "scout" }, "主页不能用缓存中的裸子 ID 查询普通会话接口")
        XCTAssertNil(store.lastError)
        store.deactivateGateway()
        await cache.close()
    }

    @MainActor
    func testTeamAddressSurvivesRestartAndRestoresLeadSelection() async throws {
        let directory = try makeDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let suite = UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = UserDefaultsAppPreferences(userDefaults: defaults, gatewayID: "team-host")
        let cache = ChatHistoryCache(url: directory.appendingPathComponent("team-routing.db"))
        let lead = SessionSummary(id: "lead", title: "团队", lastActivity: .now, isRunning: false, hasUnread: false)
        preferences.saveSessions([lead])
        let store = AppStore(preferences: preferences, gatewayLocalID: "team-host", chatHistoryCache: cache)
        await store.awaitOfflineCatalogForTesting()
        store.gateway.installConnectedTransportForTesting()
        let hello = try GatewayWireDecoder.decode(Data(#"{"kind":"hello","protocol":3,"historyFormatVersion":4,"capabilities":["agent-team-v1","subagent-conversations-v1","tasks","goals"]}"#.utf8))
        store.gateway.onFrame?(hello)
        _ = await store.prepareConversation(for: lead)
        store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(#"{"kind":"agent-team-updated","sessionId":"lead","asOfSeq":8,"agentTeam":{"members":[{"id":"lead","name":"lead","role":"lead","phase":"active"},{"id":"scout","name":"素材分析","role":"teammate","phase":"active"}],"tasks":[]}}"#.utf8)))
        await store.selectTeamMember(try XCTUnwrap(store.teamSnapshot.members.last))
        XCTAssertEqual(store.selectedSessionId, "scout")
        await store.awaitOfflineCatalogForTesting()
        store.deactivateGateway()
        let reopened = AppStore(preferences: preferences, gatewayLocalID: "team-host", chatHistoryCache: cache)
        await reopened.awaitOfflineCatalogForTesting()
        XCTAssertEqual(reopened.selectedSessionId, "lead")
        XCTAssertTrue(reopened.teamStore.isChild(sessionId: "scout"))
        var sent: [[String: Any]] = []
        reopened.gateway.installConnectedTransportForTesting { payload in
            sent.append((try? JSONSerialization.jsonObject(with: Data(payload.utf8))) as? [String: Any] ?? [:])
        }
        reopened.gateway.onFrame?(hello)
        reopened.refreshSessionControls(for: "scout")
        let childRequests = sent.filter { $0["sessionId"] as? String == "scout" }
        XCTAssertEqual(Set(childRequests.compactMap { $0["type"] as? String }), ["tasks", "goal"])
        XCTAssertTrue(childRequests.allSatisfy { $0["parentSessionId"] as? String == "lead" && $0["subagentMode"] as? String == "continuable" })
        XCTAssertNil(reopened.lastError)
        let other = AppStore(preferences: UserDefaultsAppPreferences(userDefaults: defaults, gatewayID: "other-host"), gatewayLocalID: "other-host", chatHistoryCache: cache)
        await other.awaitOfflineCatalogForTesting()
        XCTAssertFalse(other.teamStore.isChild(sessionId: "scout"))
        other.deactivateGateway()
        reopened.deactivateGateway()
        await cache.close()
    }

    @MainActor
    func testOfflineCatalogSurvivesPreferencesLossAndAuthoritativeEmptyDoesNotResurrect() async throws {
        let directory = try makeDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let suite = UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = UserDefaultsAppPreferences(userDefaults: defaults)
        preferences.automaticallyConnect = false
        var cache = ChatHistoryCache(url: directory.appendingPathComponent("catalog.db"))
        let store = AppStore(preferences: preferences, gatewayLocalID: "catalog-g", chatHistoryCache: cache)
        await store.awaitOfflineCatalogForTesting()
        for json in [
            #"{"kind":"sessions","items":[{"sessionId":"s1","updatedAt":1,"running":false,"blank":false},{"sessionId":"s2","updatedAt":2,"running":false,"blank":false}]}"#,
            #"{"kind":"workspaces","items":[{"workspaceId":"w1","title":"项目一","path":"/one","sessionIds":["s1"],"createdAt":"","updatedAt":""},{"workspaceId":"w2","title":"项目二","path":"/two","sessionIds":["s2"],"createdAt":"","updatedAt":""}]}"#
        ] {
            store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(json.utf8)))
            await store.awaitPendingKMPEventDeliveriesForTesting()
        }
        store.selectWorkspace(try XCTUnwrap(store.workspaces.last))
        _ = await cache.save(gatewayID: "catalog-g", sessionID: "s2", json: try window("s2", text: "离线缓存正文"), generation: 1)
        _ = await store.prepareConversation(for: try XCTUnwrap(store.sessions.first { $0.id == "s2" }))
        await store.awaitConversationProjectionForTesting(sessionID: "s2", expectedText: "离线缓存正文")
        await store.awaitOfflineCatalogForTesting()
        store.gateway.onConnectionFailure?("断网")
        store.disconnect()
        store.resumeWorkspace()
        XCTAssertEqual(store.workspaces.count, 2)
        XCTAssertEqual(store.sessions.count, 2)
        XCTAssertEqual(store.selectedWorkspaceId, "w2")
        XCTAssertEqual(store.selectedSessionId, "s2")
        XCTAssertEqual(store.selectedConversationItems.last?.text, "离线缓存正文")
        XCTAssertNil(store.lastError)
        XCTAssertTrue(store.historyLoadingSessionIds.isEmpty)
        store.deactivateGateway()
        await cache.close()
        cache = ChatHistoryCache(url: directory.appendingPathComponent("catalog.db"))
        defaults.removePersistentDomain(forName: suite)
        let reopened = AppStore(preferences: UserDefaultsAppPreferences(userDefaults: defaults), gatewayLocalID: "catalog-g", chatHistoryCache: cache)
        await reopened.connectOnColdLaunchIfPaired()
        await reopened.awaitOfflineCatalogForTesting()
        XCTAssertNil(reopened.lastError, "凭据丢失时仍应允许离线阅读，不弹配对警告")
        XCTAssertFalse(reopened.gateway.state.isConnected)
        XCTAssertEqual(Set(reopened.sessions.map(\.id)), ["s1", "s2"])
        XCTAssertEqual(reopened.activeWorkspace?.id, "w2")
        XCTAssertEqual(reopened.selectedSessionId, "s2")
        XCTAssertTrue(reopened.events.isEmpty, "目录恢复不能安装历史或流基线")
        reopened.search("缓存正文")
        for _ in 0..<100 {
            if !reopened.searchResults.isEmpty { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertEqual(reopened.searchResults.first?.sessionId, "s2")
        _ = await reopened.prepareConversation(for: try XCTUnwrap(reopened.sessions.first { $0.id == "s2" }))
        await reopened.awaitConversationProjectionForTesting(sessionID: "s2", expectedText: "离线缓存正文")
        XCTAssertEqual(reopened.selectedConversationItems.last?.text, "离线缓存正文")
        // 缺失/损坏 items 不可解释为权威空列表。
        for json in [#"{"kind":"sessions"}"#, #"{"kind":"sessions","items":[{"bad":"payload"}]}"#,
                     #"{"kind":"workspaces"}"#, #"{"kind":"workspaces","items":[{"bad":"payload"}]}"#] {
            reopened.gateway.onFrame?(try GatewayWireDecoder.decode(Data(json.utf8)))
        }
        await reopened.awaitPendingKMPEventDeliveriesForTesting()
        XCTAssertEqual(reopened.sessions.count, 2)
        XCTAssertEqual(reopened.workspaces.count, 2)
        reopened.gateway.onFrame?(try GatewayWireDecoder.decode(Data(#"{"kind":"sessions","items":[]}"#.utf8)))
        reopened.gateway.onFrame?(try GatewayWireDecoder.decode(Data(#"{"kind":"workspaces","items":[]}"#.utf8)))
        await reopened.awaitPendingKMPEventDeliveriesForTesting()
        await reopened.awaitOfflineCatalogForTesting()
        XCTAssertTrue(reopened.sessions.isEmpty)
        XCTAssertTrue(reopened.workspaces.isEmpty)
        reopened.deactivateGateway()
        await cache.close()
        cache = ChatHistoryCache(url: directory.appendingPathComponent("catalog.db"))
        let empty = AppStore(preferences: UserDefaultsAppPreferences(userDefaults: defaults), gatewayLocalID: "catalog-g", chatHistoryCache: cache)
        await empty.awaitOfflineCatalogForTesting()
        XCTAssertTrue(empty.sessions.isEmpty)
        XCTAssertTrue(empty.workspaces.isEmpty)
        empty.deactivateGateway()
        await cache.close()
    }

    @MainActor
    func testLateOfflineCatalogDoesNotOverwriteNetworkOrUserWorkspaceSelection() async throws {
        let directory = try makeDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let suite = UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let cache = ChatHistoryCache(url: directory.appendingPathComponent("late-catalog.db"))
        let old = AppStore(preferences: UserDefaultsAppPreferences(userDefaults: defaults), gatewayLocalID: "g", chatHistoryCache: cache)
        await old.awaitOfflineCatalogForTesting()
        old.addKnownSession("old")
        await old.awaitPendingKMPEventDeliveriesForTesting()
        await old.awaitOfflineCatalogForTesting()
        old.deactivateGateway()
        let barrier = ChatProtectionCommitBarrier()
        let current = AppStore(preferences: UserDefaultsAppPreferences(userDefaults: defaults), gatewayLocalID: "g", chatHistoryCache: cache,
            offlineCatalogRestoreBarrier: { await barrier.blockFirstCommit() })
        while !(await barrier.hasEntered) { await Task.yield() }
        current.gateway.onFrame?(try GatewayWireDecoder.decode(Data(#"{"kind":"sessions","items":[{"sessionId":"new","updatedAt":2,"running":false,"blank":false}]}"#.utf8)))
        await current.awaitPendingKMPEventDeliveriesForTesting()
        current.selectedWorkspaceId = "user-workspace"
        await barrier.resume()
        await current.awaitOfflineCatalogForTesting()
        XCTAssertEqual(current.sessions.map(\.id), ["new"])
        XCTAssertEqual(current.selectedWorkspaceId, "user-workspace")
        current.deactivateGateway()
        let foreign = AppStore(preferences: UserDefaultsAppPreferences(userDefaults: defaults, gatewayID: "foreign"), gatewayLocalID: "foreign", chatHistoryCache: cache)
        await foreign.awaitOfflineCatalogForTesting()
        XCTAssertTrue(foreign.sessions.isEmpty)
        foreign.deactivateGateway()
        await cache.close()
    }

    @MainActor
    func testCatalogRestoresComponentsIndependentlyAndPreservesUserSelection() async throws {
        let directory = try makeDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let suite = UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let cache = ChatHistoryCache(url: directory.appendingPathComponent("components.db"))
        let oldSessions = ["s1", "s2"].map { SessionSummary(id: $0, title: $0, lastActivity: .distantPast, isRunning: false, hasUnread: false) }
        let snapshot = KMPSessionListSnapshot(sessions: oldSessions, archivedSessionIds: ["archived"], selectedSessionId: "s1")
        let workspaceJSON = #"[{"workspaceId":"w-old","title":"旧项目","path":"/old","sessionIds":["s1","s2"],"createdAt":"","updatedAt":""}]"#
        let json = try XCTUnwrap(SharedOfflineCatalogCodec().create(
            sessionListJson: String(decoding: JSONEncoder().encode(snapshot), as: UTF8.self),
            workspacesJson: workspaceJSON, selectedWorkspaceId: "w-old"))
        for mode in ["sessions", "workspaces", "workspaces-without-archives", "selection"] {
            defaults.removePersistentDomain(forName: suite)
            let owner = cache.makeProtectionOwner(gatewayID: "g")
            let saved = await cache.saveOfflineCatalog(owner: owner, json: json)
            XCTAssertTrue(saved)
            let barrier = ChatProtectionCommitBarrier()
            let store = AppStore(preferences: UserDefaultsAppPreferences(userDefaults: defaults), gatewayLocalID: "g", chatHistoryCache: cache,
                offlineCatalogRestoreBarrier: { await barrier.blockFirstCommit() })
            while !(await barrier.hasEntered) { await Task.yield() }
            if mode == "sessions" {
                store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(#"{"kind":"sessions","items":[]}"#.utf8)))
            } else if mode.hasPrefix("workspaces") {
                let value = mode == "workspaces" ? #"{"kind":"workspaces","items":[],"archivedSessionIds":[]}"# : #"{"kind":"workspaces","items":[]}"#
                store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(value.utf8)))
            } else {
                _ = await store.prepareConversation(for: oldSessions[1])
                store.selectedWorkspaceId = "user-selection"
            }
            await store.awaitPendingKMPEventDeliveriesForTesting()
            await barrier.resume()
            await store.awaitOfflineCatalogForTesting()
            if mode == "sessions" {
                XCTAssertTrue(store.sessions.isEmpty)
                XCTAssertEqual(store.workspaces.first?.id, "w-old")
                XCTAssertEqual(store.archivedSessionIds, ["archived"], "新 sessions 先到仍需独立补归档目录")
            } else if mode.hasPrefix("workspaces") {
                XCTAssertEqual(Set(store.sessions.map(\.id)), ["s1", "s2"])
                XCTAssertTrue(store.workspaces.isEmpty)
                if mode == "workspaces" {
                    XCTAssertTrue(store.archivedSessionIds.isEmpty, "新工作区中的归档权威空列表须保留")
                } else {
                    XCTAssertEqual(store.archivedSessionIds, ["archived"], "未携带归档字段的工作区响应不能清空旧归档")
                }
            } else {
                XCTAssertEqual(Set(store.sessions.map(\.id)), ["s1", "s2"])
                XCTAssertEqual(store.workspaces.first?.id, "w-old")
                XCTAssertEqual(store.selectedSessionId, "s2")
                XCTAssertEqual(store.selectedWorkspaceId, "user-selection")
            }
            store.deactivateGateway()
        }
        await cache.close()
    }

    @MainActor
    func testDisconnectKeepsAuthoritativeTailAndReadsOlderCacheWithoutChangingNormalCursor() async throws {
        let directory = try makeDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let suite = UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = UserDefaultsAppPreferences(userDefaults: defaults)
        let session = SessionSummary(id: "paged", title: "分页", lastActivity: .distantPast, isRunning: false, hasUnread: false)
        preferences.saveSessions([session])
        let cache = ChatHistoryCache(url: directory.appendingPathComponent("authority-older.db"))
        let records = (1...200).map { seq -> [String: Any] in
            ["sessionId": "paged", "seq": seq, "time": Double(seq), "event": ["type": "assistant/message", "text": "消息 \(seq)"]]
        }
        let json = String(decoding: try JSONSerialization.data(withJSONObject: ["schema": 1, "sessionId": "paged", "historyFormatVersion": 1,
            "persistentCursor": 200, "hasMore": false, "events": records]), as: UTF8.self)
        _ = await cache.save(gatewayID: "g", sessionID: "paged", json: json, generation: 1)
        let store = AppStore(preferences: preferences, gatewayLocalID: "g", chatHistoryCache: cache)
        await store.awaitOfflineCatalogForTesting()
        _ = await store.prepareConversation(for: session)
        for value in [#"{"kind":"hello","historyFormatVersion":1,"capabilities":["assistant-stream-v1"]}"#,
                      #"{"kind":"subscribed","sessionId":"paged","subscriptionId":"sub","assistantStream":true}"#] {
            store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(value.utf8)))
            await store.awaitPendingKMPEventDeliveriesForTesting()
        }
        let tail = (121...200).map { sequence -> [String: Any] in
            ["type": "assistant/message", "seq": sequence, "time": Double(sequence),
             "data": ["message": ["content": [["type": "text", "text": "消息 \(sequence)"]]]]]
        }
        let frame = try JSONSerialization.data(withJSONObject: ["kind": "session-snapshot", "sessionId": "paged", "subscriptionId": "sub", "streamId": "stream",
            "historyFormatVersion": 1, "cursor": 200, "hasMore": true, "nextBeforeSeq": 121, "events": Array(tail)])
        store.gateway.onFrame?(try GatewayWireDecoder.decode(frame))
        await store.awaitPendingKMPEventDeliveriesForTesting()
        _ = await cache.save(gatewayID: "g", sessionID: "paged", json: json, generation: DispatchTime.now().uptimeNanoseconds)
        store.gateway.onConnectionFailure?("网络断开")
        await store.awaitPendingKMPEventDeliveriesForTesting()
        XCTAssertEqual(store.events["paged"]?.count, 80)
        store.loadHistory(for: "paged", older: true)
        for _ in 0..<100 {
            if store.selectedEvents.first?.seq == 41 { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertEqual(store.selectedEvents.first?.seq, 41)
        XCTAssertEqual(store.selectedEvents.last?.seq, 200)
        XCTAssertEqual(store.events["paged"]?.count, 80, "只读离线页不得改写正常网络窗口")
        store.loadHistory(for: "paged", older: true)
        for _ in 0..<100 {
            if store.selectedEvents.first?.seq == 1 { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertEqual(store.selectedEvents.first?.seq, 1)
        XCTAssertFalse(store.selectedHistoryHasMore)
        XCTAssertNil(store.lastError)
        XCTAssertTrue(store.historyLoadingSessionIds.isEmpty)
        await store.returnToLatestConversation()
        XCTAssertEqual(store.selectedEvents.first?.seq, 121)
        await cache.clear()
        store.loadHistory(for: "paged", older: true)
        for _ in 0..<100 {
            if store.offlineHistoryBoundarySessionIds.contains("paged") { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertFalse(store.selectedHistoryHasMore)
        _ = await cache.save(gatewayID: "g", sessionID: "paged", json: json, generation: DispatchTime.now().uptimeNanoseconds)
        store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(#"{"kind":"hello","historyFormatVersion":1,"capabilities":["assistant-stream-v1"]}"#.utf8)))
        store.gateway.onConnectionFailure?("再次离线")
        await store.awaitPendingKMPEventDeliveriesForTesting()
        XCTAssertFalse(store.offlineHistoryBoundarySessionIds.contains("paged"), "新连接代际必须允许重试新的缓存旧页")
        store.loadHistory(for: "paged", older: true)
        for _ in 0..<100 {
            if store.selectedEvents.first?.seq == 41 { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertEqual(store.selectedEvents.first?.seq, 41)
        XCTAssertEqual(store.events["paged"]?.first?.seq, 121)
        store.deactivateGateway()
        await cache.close()
    }

    @MainActor
    func testOfflineUncachedSessionDoesNotWaitForSnapshotOrShowTransportAlert() async throws {
        let directory = try makeDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let suite = UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let cache = ChatHistoryCache(url: directory.appendingPathComponent("missing.db"))
        let store = AppStore(preferences: UserDefaultsAppPreferences(userDefaults: defaults), gatewayLocalID: "g", historyRequestTimeout: .milliseconds(20), chatHistoryCache: cache)
        await store.awaitOfflineCatalogForTesting()
        store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(#"{"kind":"hello","historyFormatVersion":1,"capabilities":["assistant-stream-v1"]}"#.utf8)))
        let session = SessionSummary(id: "missing", title: "未缓存", lastActivity: .distantPast, isRunning: false, hasUnread: false)
        _ = await store.prepareConversation(for: session)
        try await Task.sleep(for: .milliseconds(80))
        XCTAssertEqual(store.historyLoadErrors["missing"], .notCached)
        XCTAssertFalse(store.isPreparingConversation("missing"))
        XCTAssertTrue(store.historyLoadingSessionIds.isEmpty)
        store.resumeWorkspace()
        XCTAssertNil(store.lastError)
        store.deactivateGateway()
        await cache.close()
    }

    func testClosedCacheRejectsLateReadProtectionAndCatalogWithoutRecreatingDirectory() async throws {
        let directory = try makeDirectory()
        let cache = ChatHistoryCache(url: directory.appendingPathComponent("closed.db"))
        let owner = cache.makeProtectionOwner(gatewayID: "g")
        _ = await cache.save(gatewayID: "g", sessionID: "s", json: try window("s"), generation: 1)
        await cache.close()
        try FileManager.default.removeItem(at: directory)
        let lateRead = await cache.load(gatewayID: "g", sessionID: "s")
        let latePage = await cache.loadTyped(gatewayID: "g", sessionID: "s")
        let lateCatalog = await cache.loadOfflineCatalog(gatewayID: "g")
        let lateSave = await cache.saveOfflineCatalog(owner: owner, json: "{}")
        await cache.protect(owner: owner, sessionIDs: ["s"])
        await cache.releaseProtection(owner)?.value
        _ = await cache.statistics()
        await cache.close()
        XCTAssertNil(lateRead)
        XCTAssertNil(latePage)
        XCTAssertNil(lateCatalog)
        XCTAssertFalse(lateSave)
        XCTAssertFalse(FileManager.default.fileExists(atPath: directory.path))
    }

    func testRoomPersistsAcrossInstancesAndSeparatesGateways() async throws {
        let directory = try makeDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("history.db")
        let first = ChatHistoryCache(url: url)
        let saved = await first.save(gatewayID: "g1", sessionID: "s1", json: try window("s1", text: "聊天正文"), generation: 1)
        XCTAssertTrue(saved)
        await first.close()
        let reopened = ChatHistoryCache(url: url)
        let restored = await reopened.load(gatewayID: "g1", sessionID: "s1")
        XCTAssertTrue(restored?.contains("聊天正文") == true)
        XCTAssertTrue(restored?.contains("999") == true)
        let foreign = await reopened.load(gatewayID: "g2", sessionID: "s1")
        XCTAssertNil(foreign)
        await reopened.close()
        let signature = try Data(contentsOf: url).prefix(15)
        XCTAssertEqual(String(decoding: signature, as: UTF8.self), "SQLite format 3")
    }

    func testRoomEvictsLeastRecentlyReadWindowsToLowWatermark() async throws {
        let directory = try makeDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let size = try await cachedCost(directory)
        let cache = ChatHistoryCache(url: directory.appendingPathComponent("history.db"), maximumBytes: size * 3, targetBytes: size * 2)
        for id in ["s1", "s2", "s3"] {
            let saved = await cache.save(gatewayID: "g", sessionID: id, json: try window(id), generation: 1)
            XCTAssertTrue(saved)
        }
        _ = await cache.load(gatewayID: "g", sessionID: "s1")
        let saved = await cache.save(gatewayID: "g", sessionID: "s4", json: try window("s4"), generation: 1)
        XCTAssertTrue(saved)
        let recent = await cache.load(gatewayID: "g", sessionID: "s1")
        let oldest = await cache.load(gatewayID: "g", sessionID: "s2")
        let secondOldest = await cache.load(gatewayID: "g", sessionID: "s3")
        let newest = await cache.load(gatewayID: "g", sessionID: "s4")
        XCTAssertNotNil(recent)
        XCTAssertNil(oldest)
        XCTAssertNil(secondOldest)
        XCTAssertNotNil(newest)
        await cache.close()
    }

    func testNewProtectionOwnerSurvivesSuspendedOldCommitAndLateRelease() async throws {
        let directory = try makeDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let barrier = ChatProtectionCommitBarrier()
        let cache = ChatHistoryCache(url: directory.appendingPathComponent("owner.db"),
            protectionCommitBarrier: { await barrier.blockFirstCommit() })
        for id in ["old", "new"] {
            _ = await cache.save(gatewayID: "g", sessionID: id, json: try window(id), generation: 1)
        }
        let oldOwner = cache.makeProtectionOwner(gatewayID: "g")
        let oldCommit = Task { await cache.protect(owner: oldOwner, sessionIDs: ["old"]) }
        while !(await barrier.hasEntered) { await Task.yield() }
        let newOwner = cache.makeProtectionOwner(gatewayID: "g")
        let newCommit = Task { await cache.protect(owner: newOwner, sessionIDs: ["new"]) }
        // 旧 close 可晚于新注册；既不能清新保护，也不能让旧 pending write 复活保护。
        let oldRelease = cache.releaseProtection(oldOwner)
        let oldPending = Task { await cache.protect(owner: oldOwner, sessionIDs: ["old"]) }
        await barrier.resume()
        await oldCommit.value
        await newCommit.value
        await oldPending.value
        await oldRelease?.value
        await cache.trim(to: 0)
        let newContent = await cache.load(gatewayID: "g", sessionID: "new")
        let oldContent = await cache.load(gatewayID: "g", sessionID: "old")
        XCTAssertNotNil(newContent)
        XCTAssertNil(oldContent)
        await cache.releaseProtection(newOwner)?.value
        await cache.close()
    }

    func testReleasedProtectionOwnerCannotBeRevivedByPendingWork() async throws {
        let directory = try makeDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let cache = ChatHistoryCache(url: directory.appendingPathComponent("released.db"))
        _ = await cache.save(gatewayID: "g", sessionID: "old", json: try window("old"), generation: 1)
        let owner = cache.makeProtectionOwner(gatewayID: "g")
        await cache.protect(owner: owner, sessionIDs: ["old"])
        let release = cache.releaseProtection(owner)
        // 模拟 scheduleChatCacheWrite 在 epoch() suspend 后才到达 protect。
        await cache.protect(owner: owner, sessionIDs: ["old"])
        await release?.value
        await cache.trim(to: 0)
        let content = await cache.load(gatewayID: "g", sessionID: "old")
        XCTAssertNil(content)
        await cache.close()
    }

    func testProtectedWindowsAndFailedWriteKeepOldContentAndRejectLateWrites() async throws {
        let directory = try makeDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let size = try await cachedCost(directory)
        let cache = ChatHistoryCache(url: directory.appendingPathComponent("history.db"), maximumBytes: size * 2, targetBytes: size)
        for id in ["s1", "s2"] {
            let saved = await cache.save(gatewayID: "g", sessionID: id, json: try window(id), generation: 1)
            XCTAssertTrue(saved)
        }
        await cache.protect(owner: cache.makeProtectionOwner(gatewayID: "g"), sessionIDs: ["s1", "s2"])
        let full = await cache.save(gatewayID: "g", sessionID: "s3", json: try window("s3"), generation: 1)
        XCTAssertFalse(full)
        let original = await cache.load(gatewayID: "g", sessionID: "s1")
        XCTAssertNotNil(original)
        let malformed = await cache.save(gatewayID: "g", sessionID: "s1", json: "{}", generation: 2)
        XCTAssertFalse(malformed)
        let afterFailure = await cache.load(gatewayID: "g", sessionID: "s1")
        XCTAssertNotNil(afterFailure)
        await cache.remove(gatewayID: "g", sessionID: "s1", generation: 4)
        let late = await cache.save(gatewayID: "g", sessionID: "s1", json: try window("s1"), generation: 3)
        XCTAssertFalse(late)
        let removed = await cache.load(gatewayID: "g", sessionID: "s1")
        XCTAssertNil(removed)
        await cache.close()
    }

    @MainActor
    func testOfflineSearchFindsCachedBodyWithoutSessionMetadata() async throws {
        let directory = try makeDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let suite = UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = UserDefaultsAppPreferences(userDefaults: defaults)
        preferences.automaticallyConnect = false
        let cache = ChatHistoryCache(url: directory.appendingPathComponent("search.db"))
        let saved = await cache.save(gatewayID: "offline", sessionID: "missing", json: try window("missing", text: "视频缓存方案"), generation: 1)
        XCTAssertTrue(saved)
        let store = AppStore(preferences: preferences, gatewayLocalID: "offline", chatHistoryCache: cache)
        store.search("缓存")
        for _ in 0..<100 {
            if !store.searchResults.isEmpty { break }
            try await Task.sleep(for: .milliseconds(20))
        }
        XCTAssertEqual(store.searchResults.first?.sessionId, "missing")
        XCTAssertTrue(store.searchCoverage.contains("已缓存"))
        let session = SessionSummary(id: "missing", title: "缓存结果", lastActivity: .distantPast, isRunning: false, hasUnread: false)
        _ = await store.prepareConversation(for: session)
        await store.awaitConversationProjectionForTesting(sessionID: "missing", expectedText: "视频缓存方案")
        XCTAssertEqual(store.selectedConversationItems.last?.text, "视频缓存方案")
        store.search("")
        XCTAssertTrue(store.searchResults.isEmpty)
        store.deactivateGateway()
        await cache.close()
    }

    @MainActor
    func testVisitedSessionMemoryReleasesInactiveProjections() async throws {
        let directory = try makeDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let suite = UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = UserDefaultsAppPreferences(userDefaults: defaults)
        preferences.automaticallyConnect = false
        let sessions = (0..<9).map { SessionSummary(id: "s\($0)", title: "会话", lastActivity: .distantPast, isRunning: false, hasUnread: false) }
        preferences.saveSessions(sessions)
        let cache = ChatHistoryCache(url: directory.appendingPathComponent("bounded.db"))
        for session in sessions {
            _ = await cache.save(gatewayID: "g", sessionID: session.id, json: try window(session.id, text: session.id), generation: 1)
        }
        let store = AppStore(preferences: preferences, gatewayLocalID: "g", chatHistoryCache: cache)
        for session in sessions {
            _ = await store.prepareConversation(for: session)
            await store.awaitConversationProjectionForTesting(sessionID: session.id, expectedText: session.id)
            try await Task.sleep(for: .milliseconds(30))
        }
        for _ in 0..<100 {
            if store.renderedConversationItems.count <= 6 { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertLessThanOrEqual(store.renderedConversationItems.count, 6)
        XCTAssertEqual(store.renderedConversationItems["s8"]?.last?.text, "s8")
        store.handleMemoryWarning()
        for _ in 0..<100 {
            if store.renderedConversationItems.count == 1 { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertEqual(store.renderedConversationItems.count, 1)
        XCTAssertEqual(store.renderedConversationItems["s8"]?.last?.text, "s8")
        _ = await store.prepareConversation(for: sessions[0])
        await store.awaitConversationProjectionForTesting(sessionID: "s0", expectedText: "s0")
        XCTAssertEqual(store.renderedConversationItems["s0"]?.last?.text, "s0")
        store.deactivateGateway()
        await cache.close()
    }

    func testClearInvalidatesDelayedWritesAndSearchAcrossReopen() async throws {
        let directory = try makeDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let cache = ChatHistoryCache(url: directory.appendingPathComponent("clear.db"))
        let epoch = await cache.epoch()
        _ = await cache.save(gatewayID: "g", sessionID: "s1", json: try window("s1", text: "检索正文"), generation: 1, epoch: epoch)
        await cache.clear()
        let late = await cache.save(gatewayID: "g", sessionID: "s1", json: try window("s1"), generation: 2, epoch: epoch)
        XCTAssertFalse(late)
        let restored = await cache.load(gatewayID: "g", sessionID: "s1")
        XCTAssertNil(restored)
        let results = await cache.search(gatewayID: "g", query: "正文")
        XCTAssertTrue(results?.contains("\"results\":[]") == true)
        await cache.close()
    }

    @MainActor
    func testOfflineOlderPagesAndSearchContextCanReturnToLatest() async throws {
        let directory = try makeDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let suite = UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = UserDefaultsAppPreferences(userDefaults: defaults)
        preferences.automaticallyConnect = false
        let session = SessionSummary(id: "paged", title: "分页", lastActivity: .distantPast, isRunning: false, hasUnread: false)
        preferences.saveSessions([session])
        var cache = ChatHistoryCache(url: directory.appendingPathComponent("page.db"))
        let records = (1...200).map { seq -> [String: Any] in
            ["sessionId": "paged", "seq": seq, "time": Double(seq),
             "event": ["type": "assistant/message", "text": seq == 12 ? "needle-old" : "消息 \(seq)"]]
        }
        let object: [String: Any] = ["schema": 1, "sessionId": "paged", "historyFormatVersion": 1,
            "persistentCursor": 200, "hasMore": false, "events": records]
        let json = String(decoding: try JSONSerialization.data(withJSONObject: object), as: UTF8.self)
        let saved = await cache.save(gatewayID: "g", sessionID: "paged", json: json, generation: 1)
        XCTAssertTrue(saved)
        await cache.close()
        cache = ChatHistoryCache(url: directory.appendingPathComponent("page.db"))
        let coldStart = DispatchTime.now().uptimeNanoseconds
        let cold = await cache.loadTyped(gatewayID: "g", sessionID: "paged")
        let coldMilliseconds = Double(DispatchTime.now().uptimeNanoseconds - coldStart) / 1_000_000
        let warmStart = DispatchTime.now().uptimeNanoseconds
        let warm = await cache.loadTyped(gatewayID: "g", sessionID: "paged")
        let warmMilliseconds = Double(DispatchTime.now().uptimeNanoseconds - warmStart) / 1_000_000
        XCTAssertEqual(cold?.events.count, 80)
        XCTAssertEqual(warm?.events.count, 80)
        print("[ChatCacheBenchmark] savedEvents=200 tailEvents=80 coldMilliseconds=\(coldMilliseconds) warmMilliseconds=\(warmMilliseconds)")
        let store = AppStore(preferences: preferences, gatewayLocalID: "g", chatHistoryCache: cache)
        _ = await store.prepareConversation(for: session)
        for _ in 0..<100 {
            if store.events["paged"]?.count == 80 { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertEqual(store.events["paged"]?.first?.seq, 121)
        store.search("needle-old")
        for _ in 0..<100 {
            if !store.searchResults.isEmpty { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        _ = await store.prepareConversation(for: session)
        for _ in 0..<100 {
            if store.isShowingSearchContext { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertEqual(store.selectedEvents.last?.seq, 12, "暖会话的旧搜索命中也必须可查看")
        XCTAssertEqual(store.events["paged"]?.first?.seq, 121, "片段不能替换正常窗口")
        XCTAssertEqual(store.events["paged"]?.last?.seq, 200)
        await store.returnToLatestConversation()
        XCTAssertEqual(store.selectedEvents.last?.seq, 200)
        XCTAssertFalse(store.isShowingSearchContext)
        store.loadHistory(for: "paged", older: true)
        for _ in 0..<100 {
            if store.events["paged"]?.count == 160 { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertEqual(store.events["paged"]?.first?.seq, 41)
        store.loadHistory(for: "paged", older: true)
        for _ in 0..<100 {
            if store.events["paged"]?.count == 200 { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertEqual(store.events["paged"]?.first?.seq, 1)
        XCTAssertFalse(store.historyHasMore["paged"] ?? true)
        await cache.clear()
        _ = await store.prepareConversation(for: session)
        try await Task.sleep(for: .milliseconds(30))
        XCTAssertFalse(store.isShowingSearchContext, "正常窗口已有命中时直接定位，无需磁盘片段")
        XCTAssertNotNil(store.conversationSearchTarget, "磁盘清空不能使仍在热窗口的命中失效")
        XCTAssertEqual(store.selectedEvents.last?.seq, 200)
        _ = await cache.save(gatewayID: "g", sessionID: "paged", json: json, generation: DispatchTime.now().uptimeNanoseconds)
        store.deactivateGateway()
        // 使用全新 UI store，模拟冷搜索打开旧页，避免把不连续的尾页误合并。
        let search = AppStore(preferences: preferences, gatewayLocalID: "g", chatHistoryCache: cache)
        search.search("needle-old")
        for _ in 0..<100 {
            if !search.searchResults.isEmpty { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        _ = await search.prepareConversation(for: session)
        for _ in 0..<100 {
            if search.selectedEvents.last?.seq == 12 { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertEqual(search.selectedEvents.last?.seq, 12)
        XCTAssertNotNil(search.conversationSearchTarget)
        await search.returnToLatestConversation()
        await search.awaitPendingKMPEventDeliveriesForTesting()
        XCTAssertEqual(search.events["paged"]?.last?.seq, 200)
        XCTAssertEqual(search.events["paged"]?.first?.seq, 121)
        XCTAssertNil(search.conversationSearchTarget)
        search.handleMemoryWarning()
        search.deactivateGateway()
        await cache.close()
    }

    @MainActor
    func testStaleSearchTargetRejectsMissingPageAndUnrelatedOlderPage() async throws {
        let directory = try makeDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        for hasOlderPage in [true, false] {
            let suite = UUID().uuidString
            let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
            defer { defaults.removePersistentDomain(forName: suite) }
            let preferences = UserDefaultsAppPreferences(userDefaults: defaults)
            preferences.automaticallyConnect = false
            let session = SessionSummary(id: "s1", title: "搜索", lastActivity: .distantPast, isRunning: false, hasUnread: false)
            let cache = ChatHistoryCache(url: directory.appendingPathComponent("stale-\(hasOlderPage).db"))
            _ = await cache.save(gatewayID: "g", sessionID: "s1", json: try window("s1", text: "needle-old"), generation: 1)
            let store = AppStore(preferences: preferences, gatewayLocalID: "g", chatHistoryCache: cache)
            store.search("needle-old")
            for _ in 0..<100 {
                if !store.searchResults.isEmpty { break }
                try await Task.sleep(for: .milliseconds(10))
            }
            XCTAssertFalse(store.searchResults.isEmpty)
            if hasOlderPage {
                let older = try window("s1", text: "unrelated").replacingOccurrences(of: "\"seq\":8", with: "\"seq\":7")
                _ = await cache.save(gatewayID: "g", sessionID: "s1", json: older, generation: DispatchTime.now().uptimeNanoseconds)
            } else { await cache.clear() }
            _ = await store.prepareConversation(for: session)
            for _ in 0..<100 {
                if store.conversationSearchTarget == nil { break }
                try await Task.sleep(for: .milliseconds(10))
            }
            XCTAssertNil(store.conversationSearchTarget)
            XCTAssertFalse(store.isShowingSearchContext)
            XCTAssertTrue(store.selectedEvents.isEmpty)
            store.deactivateGateway()
            await cache.close()
        }
    }

    @MainActor
    func testWarmAuthoritativeSearchContextKeepsStreamAndEmptySnapshotInvalidatesTarget() async throws {
        let directory = try makeDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let suite = UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = UserDefaultsAppPreferences(userDefaults: defaults)
        preferences.automaticallyConnect = false
        let session = SessionSummary(id: "s1", title: "搜索", lastActivity: .distantPast, isRunning: false, hasUnread: false)
        preferences.saveSessions([session])
        let cache = ChatHistoryCache(url: directory.appendingPathComponent("warm-search.db"))
        _ = await cache.save(gatewayID: "g", sessionID: "s1", json: try window("s1", text: "needle-old"), generation: 1)
        let store = AppStore(preferences: preferences, gatewayLocalID: "g", chatHistoryCache: cache)
        _ = await store.prepareConversation(for: session)
        await store.awaitConversationProjectionForTesting(sessionID: "s1", expectedText: "needle-old")
        for json in [
            #"{"kind":"hello","historyFormatVersion":1,"capabilities":["assistant-stream-v1"]}"#,
            #"{"kind":"subscribed","sessionId":"s1","subscriptionId":"sub","assistantStream":true}"#,
            #"{"kind":"session-snapshot","sessionId":"s1","subscriptionId":"sub","streamId":"stream","historyFormatVersion":1,"cursor":100,"hasMore":true,"nextBeforeSeq":100,"events":[{"type":"assistant/message","seq":100,"time":100,"data":{"message":{"content":[{"type":"text","text":"server-latest"}]}}}]}"#
        ] {
            store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(json.utf8)))
            await store.awaitPendingKMPEventDeliveriesForTesting()
        }
        await store.awaitConversationProjectionForTesting(sessionID: "s1", expectedText: "server-latest")
        // 磁盘保存更早片段，正常 UI 已是网关权威尾页且仍有 older 区间。
        _ = await cache.save(gatewayID: "g", sessionID: "s1", json: try window("s1", text: "needle-old"), generation: DispatchTime.now().uptimeNanoseconds)
        store.search("needle-old")
        for _ in 0..<100 {
            if !store.searchResults.isEmpty { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        _ = await store.prepareConversation(for: session)
        for _ in 0..<100 {
            if store.isShowingSearchContext { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertTrue(store.isShowingSearchContext)
        XCTAssertEqual(store.selectedEvents.last?.seq, 8)
        XCTAssertEqual(store.events["s1"]?.last?.seq, 100)
        await store.returnToLatestConversation()
        XCTAssertEqual(store.selectedEvents.last?.seq, 100)
        _ = await store.prepareConversation(for: session)
        for _ in 0..<100 {
            if store.isShowingSearchContext { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertTrue(store.isShowingSearchContext)
        store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(#"{"kind":"session-snapshot","sessionId":"s1","subscriptionId":"sub","streamId":"stream","historyFormatVersion":1,"cursor":101,"hasMore":false,"events":[]}"#.utf8)))
        await store.awaitPendingKMPEventDeliveriesForTesting()
        XCTAssertFalse(store.isShowingSearchContext, "同会话搜索不能重置订阅，否则完整空快照会被错误丢弃")
        XCTAssertTrue(store.events["s1"]?.isEmpty == true)
        XCTAssertNil(store.conversationSearchTarget)
        _ = await cache.save(gatewayID: "g", sessionID: "s1", json: try window("s1", text: "needle-old"), generation: DispatchTime.now().uptimeNanoseconds)
        store.search("needle-old")
        for _ in 0..<100 {
            if !store.searchResults.isEmpty { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        _ = await store.prepareConversation(for: session)
        try await Task.sleep(for: .milliseconds(60))
        XCTAssertFalse(store.isShowingSearchContext, "完整权威空历史后，旧磁盘命中不能复活已删除内容")
        XCTAssertNil(store.conversationSearchTarget)
        store.deactivateGateway()
        await cache.close()
    }

    func testRemoteSearchSerializesLegacyResponsesAndRejectsStaleQuery() {
        var gate = RemoteSearchGate()
        XCTAssertTrue(gate.begin("A"))
        XCTAssertFalse(gate.begin("B"))
        XCTAssertEqual(gate.complete(echoedQuery: nil, desiredQuery: "B"), false)
        XCTAssertTrue(gate.begin("B"))
        XCTAssertNil(gate.complete(echoedQuery: "A", desiredQuery: "B"))
        XCTAssertEqual(gate.inFlight, "B")
        gate.timedOut()
        XCTAssertFalse(gate.begin("C"))
        XCTAssertEqual(gate.complete(echoedQuery: nil, desiredQuery: "C"), false)
        XCTAssertTrue(gate.begin("C"))
        XCTAssertEqual(gate.complete(echoedQuery: nil, desiredQuery: "C"), true)
        XCTAssertTrue(gate.begin("D"))
        gate.reset()
        XCTAssertTrue(gate.begin("E"))
    }

    private func cachedCost(_ directory: URL) async throws -> Int64 {
        let probe = ChatHistoryCache(url: directory.appendingPathComponent("quota-probe.db"))
        let saved = await probe.save(gatewayID: "g", sessionID: "s1", json: try window("s1"), generation: 1)
        XCTAssertTrue(saved)
        let statistics = await probe.statistics()
        let json = try XCTUnwrap(statistics)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any])
        let bytes = try XCTUnwrap(object["logicalBytes"] as? NSNumber).int64Value
        await probe.close()
        return bytes
    }

    @MainActor
    func testDetachedSearchCachePageReleasesBusyWithoutClearingNewConsumerOperation() async throws {
        let directory = try makeDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let suite = UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = UserDefaultsAppPreferences(userDefaults: defaults)
        preferences.automaticallyConnect = false
        let session = SessionSummary(id: "s1", title: "搜索", lastActivity: .distantPast, isRunning: false, hasUnread: false)
        preferences.saveSessions([session])
        let cache = ChatHistoryCache(url: directory.appendingPathComponent("presentation-search.db"))
        _ = await cache.save(gatewayID: "g", sessionID: session.id, json: try window(session.id, text: "needle-old"), generation: 1)
        let store = AppStore(preferences: preferences, gatewayLocalID: "g", chatHistoryCache: cache)
        store.search("needle-old")
        for _ in 0..<100 where store.searchResults.isEmpty { try await Task.sleep(for: .milliseconds(10)) }
        XCTAssertFalse(store.searchResults.isEmpty)
        _ = await store.prepareConversation(for: session)
        for _ in 0..<100 where !store.isShowingSearchContext { try await Task.sleep(for: .milliseconds(10)) }
        XCTAssertTrue(store.isShowingSearchContext)
        XCTAssertTrue(store.selectedHistoryHasMore)
        let oldConsumer = UUID(), newConsumer = UUID()
        var oldOutcomes: [HistoryPresentationOutcome] = []
        var newOutcomes: [HistoryPresentationOutcome] = []
        store.attachHistoryPresentationConsumer(sessionID: session.id, consumer: .init(id: oldConsumer) {
            if case .finished(_, let outcome) = $0 { oldOutcomes.append(outcome) }
        })
        XCTAssertTrue(store.requestOlderHistoryPresentation(sessionID: session.id, consumerID: oldConsumer))
        XCTAssertTrue(store.searchContextLoadingOlder)
        // MainActor 不让旧 cache task 在 detach/reattach 之间先返回。
        store.detachHistoryPresentationConsumer(sessionID: session.id, consumerID: oldConsumer)
        store.attachHistoryPresentationConsumer(sessionID: session.id, consumer: .init(id: newConsumer) {
            if case .finished(_, let outcome) = $0 { newOutcomes.append(outcome) }
        })
        XCTAssertFalse(store.requestOlderHistoryPresentation(sessionID: session.id, consumerID: newConsumer))
        for _ in 0..<200 where store.searchContextLoadingOlder { try await Task.sleep(for: .milliseconds(5)) }
        XCTAssertFalse(store.searchContextLoadingOlder, "旧op被detach也必须清理自己的cache busy")
        XCTAssertEqual(oldOutcomes, [.detached])
        XCTAssertTrue(newOutcomes.isEmpty, "旧任务的defer不可结束新consumer的操作")
        XCTAssertTrue(store.requestOlderHistoryPresentation(sessionID: session.id, consumerID: newConsumer))
        for _ in 0..<200 where newOutcomes.isEmpty { try await Task.sleep(for: .milliseconds(5)) }
        XCTAssertEqual(newOutcomes, [.empty])
        store.detachHistoryPresentationConsumer(sessionID: session.id, consumerID: newConsumer)
        store.deactivateGateway()
        await cache.close()
    }

    @MainActor
    func testHistoryPresentationCacheMissTransfersToNetworkWithoutSecondAcceptedCall() async throws {
        let directory = try makeDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let suite = UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = UserDefaultsAppPreferences(userDefaults: defaults)
        preferences.automaticallyConnect = false
        let session = SessionSummary(id: "s1", title: "缓存回退", lastActivity: .distantPast, isRunning: false, hasUnread: false)
        preferences.saveSessions([session])
        let cache = ChatHistoryCache(url: directory.appendingPathComponent("presentation-fallback.db"))
        _ = await cache.save(gatewayID: "g", sessionID: session.id, json: try window(session.id, text: "cached"), generation: 1)
        let store = AppStore(preferences: preferences, gatewayLocalID: "g", chatHistoryCache: cache)
        _ = await store.prepareConversation(for: session)
        await store.awaitPendingKMPEventDeliveriesForTesting()
        await store.awaitConversationProjectionForTesting(sessionID: session.id, expectedText: "cached")
        store.gateway.installConnectedTransportForTesting()
        let consumerID = UUID()
        var accepted = 0
        var outcomes: [HistoryPresentationOutcome] = []
        store.attachHistoryPresentationConsumer(sessionID: session.id, consumer: .init(id: consumerID) {
            if case .began = $0 { accepted += 1 }
            if case .finished(_, let outcome) = $0 { outcomes.append(outcome) }
        })
        XCTAssertTrue(store.requestOlderHistoryPresentation(sessionID: session.id, consumerID: consumerID))
        XCTAssertEqual(accepted, 1, "Room await 前已准入并计数")
        for _ in 0..<200 where !store.historyLoadingSessionIds.contains(session.id) { try await Task.sleep(for: .milliseconds(5)) }
        XCTAssertTrue(store.historyLoadingSessionIds.contains(session.id), "没有更早缓存时沿原路径转 network")
        XCTAssertEqual(accepted, 1, "同一个已接受的 cache 调用转 network 不能再次 Began")
        store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(
            #"{"kind":"history","sessionId":"s1","historyFormatVersion":1,"events":[],"hasMore":false}"#.utf8)))
        await store.awaitPendingKMPEventDeliveriesForTesting()
        for _ in 0..<200 where outcomes.isEmpty { try await Task.sleep(for: .milliseconds(5)) }
        XCTAssertEqual(outcomes, [.empty])
        XCTAssertEqual(accepted, 1)
        store.detachHistoryPresentationConsumer(sessionID: session.id, consumerID: consumerID)
        store.deactivateGateway()
        await cache.close()
    }

    private func window(_ sessionID: String, text: String = String(repeating: "正", count: 1024)) throws -> String {
        let value: [String: Any] = ["schema": 1, "sessionId": sessionID, "historyFormatVersion": 1,
            "persistentCursor": 999, "hasMore": true, "nextBeforeSequence": 7,
            "events": [["sessionId": sessionID, "seq": 8, "time": 8,
                        "event": ["type": "assistant/message", "text": text, "reasoning": "推理正文"]]]]
        return String(decoding: try JSONSerialization.data(withJSONObject: value, options: .sortedKeys), as: UTF8.self)
    }

    private func makeDirectory() throws -> URL {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }
}

private actor ChatProtectionCommitBarrier {
    private(set) var hasEntered = false
    private var continuation: CheckedContinuation<Void, Never>?

    func blockFirstCommit() async {
        guard !hasEntered else { return }
        hasEntered = true
        await withCheckedContinuation { continuation = $0 }
    }

    func resume() {
        continuation?.resume()
        continuation = nil
    }
}
