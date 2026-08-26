import XCTest
import UIKit
import ImageIO
import UniformTypeIdentifiers
@testable import DeepSeekHarnessMobile

private enum GatewayProtocolParityFixtures {
    // 这些样本与 shared/commonTest/GatewayProtocolFixtures.kt 逐字保持一致。
    static let liveEventWithoutKind = #"{"sessionId":"s1","seq":7,"time":1001,"event":{"type":"assistant/chunk","turn":1,"step":1,"chunkType":"reasoning-delta","text":"thinking"}}"#
    static let replayedQuestionRequest = #"{"kind":"question-requested","rpcId":"rpc-1","sessionId":"s1","replay":true,"questions":[{"id":"direction","header":"研究方向","question":"你想研究哪个方向？","detail":"请选择最感兴趣的方向","options":[{"label":"核心架构 (推荐)","description":"了解插件分层"},{"label":"移动端"}],"multiSelect":true},{"id":"notes","question":"还有什么要求？","multiSelect":false}]}"#
    static let imageAttachment = #"{"kind":"attachment","sessionId":"s1","attachment":{"attachmentId":"att-1","mediaType":"image/png","bytes":8,"width":1,"height":1},"data":"iVBORw0K"}"#
    static let historyImage = #"{"kind":"history","events":[{"type":"user/message","seq":1,"time":1786937352,"data":{"content":[{"type":"image","attachment":{"attachmentId":"att-history","mediaType":"image/webp","bytes":42,"width":100,"height":80,"name":"image.webp"}}],"source":{"kind":"user"}}}],"hasMore":false}"#
}

private enum GatewayShadowRouteFixtures {
    struct Fixture {
        var json: String
        var category: String
        var route: String
    }

    // 与 shared/commonTest/GatewayProtocolFixtures.ALL_ROUTES 逐项保持一致。
    static let all = [
        Fixture(json: #"{"kind":"paired"}"#, category: "connection", route: "paired"),
        Fixture(json: #"{"kind":"hello","protocol":3,"capabilities":["images"],"authenticated":true,"clients":2}"#, category: "connection", route: "hello"),
        Fixture(json: #"{"kind":"pong","at":1000}"#, category: "connection", route: "pong"),
        Fixture(json: #"{"kind":"subscribed","sessionId":"s1"}"#, category: "connection", route: "subscribed"),
        Fixture(json: #"{"kind":"sent","sessionId":"s1"}"#, category: "content", route: "sent"),
        Fixture(json: #"{"kind":"event","sessionId":"s1","seq":1,"time":1000,"event":{"type":"assistant/message","text":"done"}}"#, category: "content", route: "live-event"),
        Fixture(json: #"{"kind":"workspaces","items":[],"archivedSessionIds":[]}"#, category: "content", route: "workspaces"),
        Fixture(json: #"{"kind":"sessions","items":[]}"#, category: "content", route: "sessions"),
        Fixture(json: #"{"kind":"history","events":[],"hasMore":false}"#, category: "content", route: "history"),
        Fixture(json: #"{"kind":"attachment","sessionId":"s1","attachment":{"attachmentId":"a1","mediaType":"image/png","bytes":1,"width":1,"height":1}}"#, category: "content", route: "attachment"),
        Fixture(json: #"{"kind":"search","items":[],"hasMore":true}"#, category: "content", route: "search"),
        Fixture(json: #"{"kind":"host","version":"1.0"}"#, category: "content", route: "host"),
        Fixture(json: #"{"kind":"agent-presets","presets":[],"authorable":false,"hasDocument":false}"#, category: "control", route: "agent-presets"),
        Fixture(json: #"{"kind":"defaults","agentPresetDefault":"standard","permissionDefault":"ask"}"#, category: "control", route: "defaults"),
        Fixture(json: #"{"kind":"default-model","selection":{"provider":"openai","model":"gpt-5"}}"#, category: "control", route: "default-model"),
        Fixture(json: #"{"kind":"save-default-model","saved":{"provider":"openai","model":"gpt-5"}}"#, category: "control", route: "save-default-model"),
        Fixture(json: #"{"kind":"set-default","applied":true,"target":"permission","value":"ask"}"#, category: "control", route: "set-default"),
        Fixture(json: #"{"kind":"models","groups":[],"routable":true}"#, category: "control", route: "models"),
        Fixture(json: #"{"kind":"select-model","selected":{"provider":"openai","model":"gpt-5"}}"#, category: "control", route: "select-model"),
        Fixture(json: #"{"kind":"permission-options","sessionPermissions":{"options":[]}}"#, category: "control", route: "permission-options"),
        Fixture(json: #"{"kind":"permission","set":"workspace-write"}"#, category: "control", route: "permission"),
        Fixture(json: #"{"kind":"context-usage","asOfSeq":8}"#, category: "control", route: "context-usage"),
        Fixture(json: #"{"kind":"session-stats","asOfSeq":8}"#, category: "control", route: "session-stats"),
        Fixture(json: #"{"kind":"directories","entries":[],"crumbs":[]}"#, category: "workspace", route: "directories"),
        Fixture(json: #"{"kind":"directory-create","path":"/tmp/new"}"#, category: "workspace", route: "directory-create"),
        Fixture(json: #"{"kind":"workspace-create","created":false}"#, category: "workspace", route: "workspace-create"),
        Fixture(json: #"{"kind":"question-requested","rpcId":"rpc-1","sessionId":"s1","replay":true,"questions":[{"id":"q1","question":"继续？"}]}"#, category: "question", route: "requested"),
        Fixture(json: #"{"kind":"question-response","rpcId":"rpc-1","action":"cancel","accepted":false,"reason":"not-pending"}"#, category: "question", route: "response"),
        Fixture(json: #"{"kind":"question-resolved","rpcId":"rpc-1","sessionId":"s1","outcome":"cancelled"}"#, category: "question", route: "resolved"),
        Fixture(json: #"{"kind":"error","requestType":"history","code":"failed","sessionId":"s1","rpcId":"rpc-1"}"#, category: "failure", route: "error"),
        Fixture(json: #"{"kind":"future-frame"}"#, category: "unknown", route: "future-frame"),
        Fixture(json: #"{"kind":"sent"}"#, category: "ignored", route: "sent"),
        Fixture(json: #"{"kind":"event"}"#, category: "ignored", route: "event"),
        Fixture(json: #"{"kind":"attachment"}"#, category: "ignored", route: "attachment"),
        Fixture(json: #"{"kind":"question-requested","sessionId":"s1","questions":[]}"#, category: "question", route: "invalid-request"),
        Fixture(json: #"{"kind":"question-response"}"#, category: "ignored", route: "question-response"),
        Fixture(json: #"{"kind":"question-resolved"}"#, category: "ignored", route: "question-resolved")
    ]
}

final class GatewayProtocolTests: XCTestCase {
    func testKMPSharedAdapterLinksFrameworkAndNormalizesFixture() {
        let adapter = KMPSharedAdapter()

        XCTAssertEqual(adapter.moduleSummary, "DeepSeekHarnessShared · schema 1")
        XCTAssertEqual(
            adapter.decodeFrameKind(GatewayProtocolParityFixtures.liveEventWithoutKind),
            "event"
        )
        XCTAssertEqual(adapter.makeStore().loadManualTestFixture().sessions.count, 1)
        XCTAssertNil(adapter.decodeFrameKind("abc"))
    }

    @MainActor
    func testKMPShadowRoutesAllKnownAndMalformedFramesWithoutDifferences() throws {
        let context = GatewayFrameRoutingContext(
            selectedSessionID: "selected",
            pendingHistorySessionID: "history-session",
            pendingModelsSessionID: "models-session",
            isPendingGlobalModelsRequest: false,
            pendingModelSelectionSessionID: "selection-session",
            pendingPermissionOptionsSessionID: "permission-session"
        )
        let validator = KMPShadowValidator()

        for fixture in GatewayShadowRouteFixtures.all {
            let frame = try GatewayWireDecoder.decode(Data(fixture.json.utf8))
            let swiftRoute = GatewayFrameRouter.route(frame, context: context)
            let result = validator.validate(frame: frame, context: context, swiftRoute: swiftRoute)

            XCTAssertNil(result.difference, "\(fixture.route): \(String(describing: result.difference))")
            XCTAssertEqual(result.fingerprint?.category, fixture.category, fixture.route)
            XCTAssertEqual(result.fingerprint?.route, fixture.route, fixture.route)
        }
        XCTAssertTrue(validator.differences.isEmpty)
    }

    func testDecodesDirectoryCreateResponse() throws {
        let data = #"{"kind":"directory-create","path":"/tmp/workspace/Sources"}"#.data(using: .utf8)!
        let frame = try JSONDecoder().decode(GatewayFrame.self, from: data)

        XCTAssertEqual(frame.kind, "directory-create")
        XCTAssertEqual(frame.path, "/tmp/workspace/Sources")

        let context = GatewayFrameRoutingContext(
            selectedSessionID: nil,
            pendingHistorySessionID: nil,
            pendingModelsSessionID: nil,
            isPendingGlobalModelsRequest: false,
            pendingModelSelectionSessionID: nil,
            pendingPermissionOptionsSessionID: nil
        )
        guard case .workspace(.directoryCreated(let path)) =
                GatewayFrameRouter.route(frame, context: context) else {
            return XCTFail("directory-create 应路由为 workspace.directoryCreated")
        }
        XCTAssertEqual(path, "/tmp/workspace/Sources")
    }

    func testGatewayFrameRouterMapsHelloAndLiveEventPayloads() throws {
        let context = GatewayFrameRoutingContext(
            selectedSessionID: "selected",
            pendingHistorySessionID: nil,
            pendingModelsSessionID: nil,
            isPendingGlobalModelsRequest: false,
            pendingModelSelectionSessionID: nil,
            pendingPermissionOptionsSessionID: nil
        )
        let hello = try GatewayWireDecoder.decode(Data(
            #"{"kind":"hello","protocol":3,"capabilities":["images"],"authenticated":true,"clients":2}"#.utf8
        ))
        guard case .connection(.hello(let payload)) = GatewayFrameRouter.route(hello, context: context) else {
            return XCTFail("hello 应路由为 connection.hello")
        }
        XCTAssertEqual(payload.protocolVersion, 3)
        XCTAssertEqual(payload.capabilities, ["images"])
        XCTAssertTrue(payload.authenticated)
        XCTAssertEqual(payload.clients, 2)

        let event = try GatewayWireDecoder.decode(Data(
            #"{"kind":"event","sessionId":"s1","seq":7,"time":1000,"event":{"type":"assistant/message","text":"done"}}"#.utf8
        ))
        guard case .content(.liveEvent(let record)) = GatewayFrameRouter.route(event, context: context) else {
            return XCTFail("event 应路由为 content.liveEvent")
        }
        XCTAssertEqual(record.sessionId, "s1")
        XCTAssertEqual(record.seq, 7)
        XCTAssertEqual(record.event.text, "done")
    }

    func testGatewayFrameRouterBuildsQuestionDomainActionsAndRejectsMalformedRequest() throws {
        let context = GatewayFrameRoutingContext(
            selectedSessionID: nil,
            pendingHistorySessionID: nil,
            pendingModelsSessionID: nil,
            isPendingGlobalModelsRequest: false,
            pendingModelSelectionSessionID: nil,
            pendingPermissionOptionsSessionID: nil
        )
        let valid = try GatewayWireDecoder.decode(Data(
            #"{"kind":"question-requested","rpcId":"rpc-1","sessionId":"s1","replay":true,"questions":[{"id":"q1","question":"继续？"}]}"#.utf8
        ))
        guard case .question(.requested(let action, let sessionID, let preview, let replay)) =
                GatewayFrameRouter.route(valid, context: context) else {
            return XCTFail("有效问题应生成 requested action")
        }
        XCTAssertEqual(sessionID, "s1")
        XCTAssertEqual(preview, "继续？")
        XCTAssertTrue(replay)
        var state = QuestionState()
        QuestionReducer.reduce(state: &state, action: action)
        XCTAssertEqual(state.pendingRequests.first?.rpcId, "rpc-1")

        let invalid = try GatewayWireDecoder.decode(Data(
            #"{"kind":"question-requested","sessionId":"s1","questions":[]}"#.utf8
        ))
        guard case .question(.invalidRequest(let sessionID)) =
                GatewayFrameRouter.route(invalid, context: context) else {
            return XCTFail("缺少 rpcId 的问题必须显式路由为 invalidRequest")
        }
        XCTAssertEqual(sessionID, "s1")
    }

    func testGatewayFrameRouterUsesPendingSessionControlTargets() throws {
        let context = GatewayFrameRoutingContext(
            selectedSessionID: "selected",
            pendingHistorySessionID: nil,
            pendingModelsSessionID: "models-session",
            isPendingGlobalModelsRequest: false,
            pendingModelSelectionSessionID: "selection-session",
            pendingPermissionOptionsSessionID: "permission-session"
        )
        let selected = try GatewayWireDecoder.decode(Data(
            #"{"kind":"select-model","selected":{"provider":"openai","model":"gpt-5"}}"#.utf8
        ))
        guard case .control(.modelSelected(let sessionID, let selection)) =
                GatewayFrameRouter.route(selected, context: context) else {
            return XCTFail("select-model 应使用 pending session")
        }
        XCTAssertEqual(sessionID, "selection-session")
        XCTAssertEqual(selection?.model, "gpt-5")
    }

    func testAttachmentLoaderDeduplicatesAndHonorsConcurrencyLimit() {
        func attachment(_ id: String) -> GatewayImageAttachment {
            GatewayImageAttachment(
                attachmentId: id,
                mediaType: "image/png",
                bytes: 1,
                width: 1,
                height: 1
            )
        }
        var loader = AttachmentLoader(maximumConcurrentRequests: 2)
        let initial = loader.enqueue(
            [attachment("a"), attachment("b"), attachment("a"), attachment("cached"), attachment("c")],
            sessionID: "s1",
            isCached: { $0 == "cached" }
        )
        XCTAssertEqual(initial.map(\.attachment.id), ["a", "b"])
        XCTAssertEqual(loader.inFlightAttachmentIDs, ["a", "b"])
        XCTAssertEqual(loader.queuedAttachmentIDs, ["c"])

        let next = loader.complete(attachmentID: "a", isCached: { _ in false })
        XCTAssertEqual(next.map(\.attachment.id), ["c"])
        XCTAssertEqual(loader.inFlightAttachmentIDs, ["b", "c"])
        loader.reset()
        XCTAssertTrue(loader.inFlightAttachmentIDs.isEmpty)
        XCTAssertTrue(loader.queuedAttachmentIDs.isEmpty)
    }

    @MainActor
    func testAgentBackgroundExecutionControllerTracksTurnsAndEndsTask() {
        let application = BackgroundTaskApplicationSpy()
        let controller = AgentBackgroundExecutionController(application: application)
        var expirationCount = 0
        controller.onBackgroundAllowanceExpired = { expirationCount += 1 }

        controller.begin(sessionID: nil, startsNewTurn: true)
        controller.associateSessionIfNeeded("s1")
        controller.begin(sessionID: "s1", startsNewTurn: true)
        XCTAssertTrue(controller.keepsConnectionAlive)
        XCTAssertEqual(controller.outstandingTurns, 2)
        XCTAssertEqual(application.beginCallCount, 1)

        controller.applicationDidEnterBackground()
        controller.turnEnded(sessionID: "s1")
        XCTAssertEqual(controller.outstandingTurns, 1)
        controller.turnEnded(sessionID: "s1")
        XCTAssertFalse(controller.keepsConnectionAlive)
        XCTAssertEqual(application.endedIdentifiers.count, 1)
        XCTAssertEqual(expirationCount, 1)
    }

    func testUserDefaultsAppPreferencesUsesDefaultsAndRoundTripsValues() throws {
        let suiteName = "AppPreferencesTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let preferences = UserDefaultsAppPreferences(userDefaults: defaults)

        XCTAssertEqual(preferences.endpoint, UserDefaultsAppPreferences.defaultEndpoint)
        XCTAssertNil(preferences.selectedWorkspaceID)
        XCTAssertEqual(preferences.loadSessions(), [])

        let sessions = [
            SessionSummary(
                id: "session-1",
                title: "持久化测试",
                lastActivity: Date(timeIntervalSince1970: 1_000),
                isRunning: true,
                hasUnread: true,
                agentPreset: "default"
            )
        ]
        preferences.endpoint = "wss://gateway.example/ws/mobile"
        preferences.selectedWorkspaceID = "workspace-1"
        preferences.saveSessions(sessions)

        let reloaded = UserDefaultsAppPreferences(userDefaults: defaults)
        XCTAssertEqual(reloaded.endpoint, "wss://gateway.example/ws/mobile")
        XCTAssertEqual(reloaded.selectedWorkspaceID, "workspace-1")
        XCTAssertEqual(reloaded.loadSessions(), sessions)

        reloaded.selectedWorkspaceID = nil
        XCTAssertNil(defaults.object(forKey: "gateway.selectedWorkspaceId"))
    }

    func testUserDefaultsAppPreferencesPreservesExistingKeysAndRunsLegacyCleanup() throws {
        let suiteName = "AppPreferencesMigrationTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
        defaults.set("ws://legacy-host:3080/ws/mobile", forKey: "gateway.endpoint")
        defaults.set(["session-1": 42], forKey: "gateway.conversationScrollAnchors")
        defaults.set(["session-1"], forKey: "gateway.manuallyPositionedSessionIds")
        let preferences = UserDefaultsAppPreferences(userDefaults: defaults)

        preferences.performMigrations()

        XCTAssertEqual(preferences.endpoint, "ws://legacy-host:3080/ws/mobile")
        XCTAssertNil(defaults.object(forKey: "gateway.conversationScrollAnchors"))
        XCTAssertNil(defaults.object(forKey: "gateway.manuallyPositionedSessionIds"))
    }

    @MainActor
    func testAppStoreLoadsInjectedPreferencesAndPersistsEachSessionChangeOnce() {
        let initialSession = SessionSummary(
            id: "existing",
            title: "已有会话",
            lastActivity: Date(timeIntervalSince1970: 900),
            isRunning: false,
            hasUnread: false
        )
        let preferences = AppPreferencesSpy(
            endpoint: "wss://injected.example/ws/mobile",
            selectedWorkspaceID: "workspace-injected",
            sessions: [initialSession]
        )

        let store = AppStore(preferences: preferences)

        XCTAssertEqual(store.endpoint, preferences.endpoint)
        XCTAssertEqual(store.selectedWorkspaceId, preferences.selectedWorkspaceID)
        XCTAssertEqual(store.sessions, [initialSession])
        XCTAssertEqual(preferences.performMigrationsCallCount, 1)
        XCTAssertEqual(preferences.savedSessionSnapshots, [])

        store.addKnownSession("new-session")
        XCTAssertEqual(preferences.savedSessionSnapshots.count, 1)
        XCTAssertEqual(preferences.savedSessionSnapshots.last?.map(\.id), ["new-session", "existing"])

        store.addKnownSession("new-session")
        XCTAssertEqual(preferences.savedSessionSnapshots.count, 1)
    }

    func testImageAttachmentCachePersistsAndExpires() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("image-cache-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        var currentDate = Date(timeIntervalSince1970: 1_000)
        let payload = Data("cached-image".utf8)
        let cache = ImageAttachmentCache(
            directoryURL: directory,
            ttl: 60,
            memoryCostLimit: 1,
            now: { currentDate }
        )

        cache.store(payload, for: "attachment/unsafe-path")
        cache.removeAllFromMemory()
        XCTAssertEqual(cache.data(for: "attachment/unsafe-path"), payload)

        cache.removeAllFromMemory()
        currentDate.addTimeInterval(61)
        XCTAssertNil(cache.data(for: "attachment/unsafe-path"))
        XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: directory.path).isEmpty)
    }

    func testImagePixelLimitPreflight() {
        let accepted = GatewayImageDimensions(width: 2_000, height: 2_000)
        let sideTooLong = GatewayImageDimensions(width: 2_001, height: 1_000)

        XCTAssertTrue(GatewayImageInspector.isWithinPixelLimits(accepted))
        XCTAssertFalse(GatewayImageInspector.isWithinPixelLimits(sideTooLong))
    }

    func testImagePreprocessorDownsizesToDSHLimits() throws {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let image = UIGraphicsImageRenderer(size: CGSize(width: 3_000, height: 1_500), format: format).image { context in
            UIColor.systemBlue.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 3_000, height: 1_500))
        }
        let source = try XCTUnwrap(image.pngData())
        let prepared = try GatewayImagePreprocessor.prepare(data: source, mediaType: "image/png")

        XCTAssertEqual(prepared.mediaType, "image/jpeg")
        XCTAssertLessThanOrEqual(prepared.data.count, GatewayImagePreprocessor.maximumBytes)
        XCTAssertLessThanOrEqual(prepared.dimensions.longestSide, GatewayImagePreprocessor.maximumPixelSide)
        XCTAssertLessThanOrEqual(
            prepared.dimensions.width * prepared.dimensions.height,
            GatewayImagePreprocessor.maximumPixels
        )
        XCTAssertEqual(prepared.dimensions.width, 2_000)
        XCTAssertEqual(prepared.dimensions.height, 1_000)
    }

    func testImagePreprocessorNormalizesOrientationWithoutChangingAspectRatio() throws {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let image = UIGraphicsImageRenderer(size: CGSize(width: 40, height: 20), format: format).image { context in
            UIColor.systemOrange.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 40, height: 20))
        }
        let sourceData = try XCTUnwrap(image.jpegData(compressionQuality: 0.9))
        let source = try XCTUnwrap(CGImageSourceCreateWithData(sourceData as CFData, nil))
        let orientedData = NSMutableData()
        let destination = try XCTUnwrap(
            CGImageDestinationCreateWithData(
                orientedData,
                UTType.jpeg.identifier as CFString,
                1,
                nil
            )
        )
        CGImageDestinationAddImageFromSource(
            destination,
            source,
            0,
            [kCGImagePropertyOrientation: 6] as CFDictionary
        )
        XCTAssertTrue(CGImageDestinationFinalize(destination))

        let input = orientedData as Data
        XCTAssertEqual(GatewayImageInspector.dimensions(of: input), GatewayImageDimensions(width: 20, height: 40))

        let prepared = try GatewayImagePreprocessor.prepare(data: input, mediaType: "image/jpeg")

        XCTAssertEqual(prepared.dimensions, GatewayImageDimensions(width: 20, height: 40))
        XCTAssertEqual(GatewayImageInspector.dimensions(of: prepared.data), GatewayImageDimensions(width: 20, height: 40))
        XCTAssertEqual(GatewayImageInspector.orientation(of: prepared.data), 1)
    }

    func testDecodesProtocolThreeImageCapability() throws {
        let json = #"{"kind":"hello","protocol":3,"capabilities":["images"],"authenticated":true}"#
        let frame = try GatewayWireDecoder.decode(Data(json.utf8))

        XCTAssertEqual(frame.protocol, 3)
        XCTAssertEqual(frame.capabilities, ["images"])
    }

    func testDecodesLiveImageReference() throws {
        let json = #"{"kind":"event","sessionId":"s1","seq":7,"time":1001,"event":{"type":"user/message","text":"描述它","source":"user","images":[{"attachmentId":"att-1","mediaType":"image/jpeg","bytes":12,"width":3,"height":4,"name":"photo.jpg"}]}}"#
        let frame = try GatewayWireDecoder.decode(Data(json.utf8))

        XCTAssertEqual(frame.event?.images?.first?.attachmentId, "att-1")
        XCTAssertEqual(frame.event?.images?.first?.mediaType, "image/jpeg")
    }

    func testDecodesAttachmentPayload() throws {
        let json = GatewayProtocolParityFixtures.imageAttachment
        let frame = try GatewayWireDecoder.decode(Data(json.utf8))

        XCTAssertEqual(frame.attachment?.attachmentId, "att-1")
        XCTAssertEqual(frame.data, "iVBORw0K")
    }

    func testDecodesToolEvent() throws {
        let json = #"{"kind":"event","sessionId":"s1","seq":4,"time":1000,"event":{"type":"tool/call","turn":1,"step":2,"callId":"c1","name":"Bash","arguments":{"cmd":"pwd"}}}"#
        let frame = try JSONDecoder().decode(GatewayFrame.self, from: Data(json.utf8))
        XCTAssertEqual(frame.sessionId, "s1")
        XCTAssertEqual(frame.event?.name, "Bash")
        XCTAssertEqual(frame.event?.arguments?.displayText.contains("pwd"), true)
    }

    func testDecodesAssistantChunk() throws {
        let json = #"{"kind":"event","sessionId":"s1","seq":5,"time":1001,"event":{"type":"assistant/chunk","turn":1,"step":2,"chunkType":"text-delta","text":"hello"}}"#
        let frame = try JSONDecoder().decode(GatewayFrame.self, from: Data(json.utf8))
        XCTAssertEqual(frame.event?.chunkType, "text-delta")
        XCTAssertEqual(frame.event?.text, "hello")
    }

    func testDecodesLiveEventWhenGatewayOmitsKind() throws {
        let json = GatewayProtocolParityFixtures.liveEventWithoutKind
        let frame = try GatewayWireDecoder.decode(Data(json.utf8))
        XCTAssertEqual(frame.kind, "event")
        XCTAssertEqual(frame.event?.type, "assistant/chunk")
        XCTAssertEqual(frame.event?.text, "thinking")
    }

    func testConversationShowsPluginPromptsAsCompactContextRows() {
        let records = [
            SessionEvent(sessionId: "s1", seq: 1, time: 1, event: GatewayEvent(type: "permission/preset")),
            SessionEvent(sessionId: "s1", seq: 2, time: 2, event: GatewayEvent(type: "user/message", text: "runtime", source: "plugin")),
            SessionEvent(sessionId: "s1", seq: 3, time: 3, event: GatewayEvent(type: "user/message", text: "你好", source: "user")),
            SessionEvent(sessionId: "s1", seq: 4, time: 4, event: GatewayEvent(type: "assistant/message", text: "你好！", reasoning: "思考")),
            SessionEvent(sessionId: "s1", seq: 5, time: 5, event: GatewayEvent(type: "tool/call", name: "Read"))
        ]
        let items = ConversationItem.make(from: records)
        // Expected titles resolve through the same localized constants the
        // projection uses, so this passes under any test locale.
        XCTAssertEqual(items.map(\.title), [
            L10n.contextInjectionTitle("plugin"),
            L10n.userMessageTitle,
            "Think",
            "DeepSeek",
            "Read",
        ])
    }

    func testHistoryRebaseKeepsLiveTailAndDeduplicatesOverlap() throws {
        let history = [
            SessionEvent(sessionId: "s1", seq: 1, time: 1, event: GatewayEvent(type: "user/message", text: "开始", source: "user")),
            SessionEvent(sessionId: "s1", seq: 2, time: 2, event: GatewayEvent(type: "assistant/chunk", turn: 1, step: 1, text: "旧", chunkType: "text-delta"))
        ]
        let liveAtBuildStart = [
            SessionEvent(sessionId: "s1", seq: 2, time: 2, event: GatewayEvent(type: "assistant/chunk", turn: 1, step: 1, text: "A", chunkType: "text-delta")),
            SessionEvent(sessionId: "s1", seq: 3, time: 3, event: GatewayEvent(type: "assistant/chunk", turn: 1, step: 1, text: "B", chunkType: "text-delta"))
        ]

        var rebase = ConversationHistoryRebase.build(history: history, current: liveAtBuildStart)
        XCTAssertEqual(rebase.events.map(\.seq), [1, 2, 3])
        XCTAssertEqual(try XCTUnwrap(rebase.projector.items.last).text, "AB")

        let liveAfterBuild = liveAtBuildStart + [
            SessionEvent(sessionId: "s1", seq: 4, time: 4, event: GatewayEvent(type: "assistant/chunk", turn: 1, step: 1, text: "C", chunkType: "text-delta"))
        ]
        rebase.appendLiveTail(from: liveAfterBuild)

        XCTAssertEqual(rebase.events.map(\.seq), [1, 2, 3, 4])
        XCTAssertEqual(try XCTUnwrap(rebase.projector.items.last).text, "ABC")
    }

    func testHistoryRebaseLetsCompletedLiveMessageSupersedePartialHistory() throws {
        let history = [
            SessionEvent(sessionId: "s1", seq: 1, time: 1, event: GatewayEvent(type: "assistant/chunk", turn: 2, step: 1, text: "partial", chunkType: "text-delta"))
        ]
        let live = [
            SessionEvent(sessionId: "s1", seq: 2, time: 2, event: GatewayEvent(type: "assistant/message", turn: 2, step: 1, text: "final"))
        ]

        let rebase = ConversationHistoryRebase.build(history: history, current: live)

        XCTAssertEqual(rebase.projector.items.count, 1)
        XCTAssertEqual(try XCTUnwrap(rebase.projector.items.first).title, "DeepSeek")
        XCTAssertEqual(try XCTUnwrap(rebase.projector.items.first).text, "final")
    }

    func testRunCodeUsesJSONToolRendering() {
        let payload: JSONValue = .string(#"{"language":"python","code":"print(1)"}"#)
        let record = SessionEvent(
            sessionId: "s1",
            seq: 1,
            time: 1,
            event: GatewayEvent(type: "tool/call", name: "run_code", arguments: payload)
        )
        let item = ConversationItem.make(from: [record]).first
        XCTAssertEqual(item?.title, "run_code")
        XCTAssertEqual(item?.kind, .jsonTool)
        XCTAssertTrue(item?.text.contains("\"language\" : \"python\"") == true)
    }

    func testTrajectoryAggregatesChunksAndPairsToolResult() {
        let events = [
            SessionEvent(sessionId: "s1", seq: 0, time: 1, event: GatewayEvent(type: "permission/preset")),
            SessionEvent(sessionId: "s1", seq: 1, time: 2, event: GatewayEvent(type: "user/message", text: "查文件", source: "user")),
            SessionEvent(sessionId: "s1", seq: 2, time: 3, event: GatewayEvent(type: "assistant/chunk", turn: 1, step: 1, text: "先", chunkType: "reasoning-delta")),
            SessionEvent(sessionId: "s1", seq: 3, time: 4, event: GatewayEvent(type: "assistant/chunk", turn: 1, step: 1, text: "读取", chunkType: "reasoning-delta")),
            SessionEvent(sessionId: "s1", seq: 4, time: 5, event: GatewayEvent(type: "assistant/message", turn: 1, step: 1, reasoning: "先读取", toolCalls: [])),
            SessionEvent(sessionId: "s1", seq: 5, time: 6, event: GatewayEvent(type: "tool/call", turn: 1, step: 1, callId: "c1", name: "Read", arguments: .object(["path": .string("a.swift")]))),
            SessionEvent(sessionId: "s1", seq: 6, time: 7, event: GatewayEvent(type: "tool/result", turn: 1, step: 1, callId: "c1", isError: false, preview: "contents"))
        ]
        let nodes = TrajectoryProjection.make(from: events)
        XCTAssertEqual(nodes.map(\.kind), [.input, .request, .assistant, .tool])
        XCTAssertEqual(nodes[1].request?.number, 1)
        XCTAssertEqual(nodes[2].subtitle, "先读取")
        XCTAssertTrue(nodes[3].subtitle.contains("contents"))
        XCTAssertEqual(nodes[3].records.count, 2)
    }

    func testTrajectoryProjectsRequestUsageAndSubtool() throws {
        let headerData: JSONValue = .object([
            "header": .object([
                "config": .object([
                    "provider": .string("deepseek-official"),
                    "model": .string("deepseek-v4-flash"),
                    "reasoningEffort": .string("high")
                ]),
                "tools": .array([
                    .object([
                        "name": .string("run_code"),
                        "description": .string("Run code"),
                        "parameters": .object(["type": .string("object")])
                    ])
                ])
            ])
        ])
        let usage: JSONValue = .object([
            "inputTokens": .number(327),
            "cacheReadTokens": .number(9216),
            "outputTokens": .number(208),
            "reasoningTokens": .number(93)
        ])
        let events = [
            SessionEvent(sessionId: "s1", seq: 1, time: 1, event: GatewayEvent(type: "request/header", raw: headerData)),
            SessionEvent(sessionId: "s1", seq: 2, time: 2, event: GatewayEvent(type: "assistant/chunk", turn: 4, step: 1, text: "thinking", chunkType: "reasoning-delta")),
            SessionEvent(sessionId: "s1", seq: 3, time: 3, event: GatewayEvent(type: "assistant/message", turn: 4, step: 1, text: "done", usage: usage, toolCalls: [ToolCall(id: "c1", name: "run_code")])),
            SessionEvent(sessionId: "s1", seq: 4, time: 4, event: GatewayEvent(type: "tool/call", turn: 4, step: 1, callId: "c1", name: "run_code", arguments: .object(["code": .string("return 1")]))),
            SessionEvent(sessionId: "s1", seq: 5, time: 5, event: GatewayEvent(type: "tool/code-dispatch-start", name: "bash", arguments: .object(["command": .string("pwd")]), rootCallId: "c1", parentCallId: "c1", subCallId: "c1:code:1")),
            SessionEvent(sessionId: "s1", seq: 6, time: 6, event: GatewayEvent(type: "tool/code-dispatch", name: "bash", arguments: .object(["command": .string("pwd")]), isError: false, preview: "/tmp", rootCallId: "c1", parentCallId: "c1", subCallId: "c1:code:1"))
        ]
        let nodes = TrajectoryProjection.make(from: events)
        XCTAssertEqual(nodes.map(\.kind), [.request, .assistant, .tool, .subtool])
        XCTAssertEqual(nodes[0].request?.usage.totalInput, 9543)
        XCTAssertEqual(nodes[0].request?.usage.content, 115)
        XCTAssertEqual(nodes[0].request?.subtoolCalls, 1)
        XCTAssertEqual(nodes[2].tool?.schema?["name"]?.stringValue, "run_code")
        XCTAssertEqual(nodes[3].tool?.hierarchy, "run_code")
        XCTAssertEqual(nodes[3].records.count, 2)
    }

    func testDecodesSessionListItems() throws {
        let json = #"{"kind":"sessions","items":[{"sessionId":"s1","updatedAt":1786937352,"running":true,"blank":false,"cwd":"/tmp/project","agentPreset":"standard"}]}"#
        let frame = try JSONDecoder().decode(GatewayFrame.self, from: Data(json.utf8))
        let item = try XCTUnwrap(frame.items?.first?.decode(GatewaySessionSummary.self))
        XCTAssertEqual(item.sessionId, "s1")
        XCTAssertTrue(item.running)
        XCTAssertEqual(item.cwd, "/tmp/project")
    }

    func testNormalizesRawHistoryMessage() throws {
        let json = #"{"kind":"history","events":[{"type":"user/message","seq":1,"time":1786937352,"data":{"content":[{"type":"text","text":"历史消息"}]}}],"hasMore":false}"#
        let frame = try JSONDecoder().decode(GatewayFrame.self, from: Data(json.utf8))
        let event = try XCTUnwrap(frame.events?.first?.normalized(sessionId: "s1"))
        XCTAssertEqual(event.event.type, "user/message")
        XCTAssertEqual(event.event.text, "历史消息")
    }

    func testNormalizesRawHistoryImageReferenceAndProjectsPureImageMessage() throws {
        let json = GatewayProtocolParityFixtures.historyImage
        let frame = try GatewayWireDecoder.decode(Data(json.utf8))
        let event = try XCTUnwrap(frame.events?.first?.normalized(sessionId: "s1"))
        let item = try XCTUnwrap(ConversationItem.make(from: [event]).first)

        XCTAssertEqual(event.event.images?.first?.attachmentId, "att-history")
        XCTAssertEqual(item.kind, .user)
        XCTAssertEqual(item.text, "")
        XCTAssertEqual(item.images.first?.mediaType, "image/webp")
    }

    func testNormalizesRawSubtoolDispatch() throws {
        let json = #"{"kind":"history","events":[{"type":"tool/code-dispatch-start","seq":8,"time":1786937352,"data":{"rootCallId":"root","parentCallId":"root","subCallId":"root:code:1","name":"bash","arguments":{"command":"pwd"}}},{"type":"tool/code-dispatch","seq":9,"time":1786937353,"data":{"rootCallId":"root","parentCallId":"root","subCallId":"root:code:1","name":"bash","arguments":{"command":"pwd"},"isError":false,"content":[{"type":"text","text":"/tmp"}]}}],"hasMore":false}"#
        let frame = try JSONDecoder().decode(GatewayFrame.self, from: Data(json.utf8))
        let events = try XCTUnwrap(frame.events).map { $0.normalized(sessionId: "s1") }
        XCTAssertEqual(events[0].event.subCallId, "root:code:1")
        XCTAssertEqual(events[0].event.arguments?["command"]?.stringValue, "pwd")
        XCTAssertEqual(events[1].event.preview, "/tmp")
        XCTAssertEqual(events[1].event.isError, false)
    }

    func testDecodesSessionControlResponsesAndHistoryProjections() throws {
        let modelsJSON = #"{"kind":"models","current":{"provider":"deepseek-official","model":"deepseek-v4-flash","reasoningEffort":"high"},"routable":true,"groups":[{"id":"deepseek-official","name":"DeepSeek","models":[{"id":"deepseek-v4-flash","name":"DeepSeek-V4-Flash","reasoning":{"efforts":[{"id":"off","name":"Off"},{"id":"high","name":"High"}],"defaultEffort":"high"}}]}],"failures":[]}"#
        let models = try GatewayWireDecoder.decode(Data(modelsJSON.utf8))
        XCTAssertEqual(models.current?.model, "deepseek-v4-flash")
        XCTAssertEqual(models.groups?.first?.models.first?.reasoning?.efforts.map(\.id), ["off", "high"])

        let permissionsJSON = #"{"kind":"permission-options","namespace":{"value":{"defaultPreset":"workspace-write"}},"sessionPermissions":{"options":[{"value":"read-only","name":"read-only"},{"value":"danger-full-access","name":"danger-full-access"}],"currentValue":"danger-full-access"}}"#
        let permissions = try GatewayWireDecoder.decode(Data(permissionsJSON.utf8))
        XCTAssertEqual(permissions.sessionPermissions?.currentValue, "danger-full-access")
        XCTAssertEqual(permissions.sessionPermissions?.options?.count, 2)

        let usageJSON = #"{"kind":"context-usage","sessionId":"s1","asOfSeq":260245,"tokenUsage":{"uncachedInputTokens":613950,"outputTokens":268657,"cacheReadTokens":72057728,"cacheWriteTokens":0},"contextPressure":{"pressureTokens":434856,"projectedTokens":435317,"contextWindow":1000000}}"#
        let usage = try GatewayWireDecoder.decode(Data(usageJSON.utf8))
        XCTAssertEqual(usage.contextPressure?.pressureTokens, 434856)
        XCTAssertEqual(usage.contextPressure?.contextWindow, 1_000_000)

        let historyJSON = #"{"kind":"history","sessionId":"s1","events":[],"hasMore":false,"projections":{"asOfSeq":260245,"values":{"contextBreakdown":{"systemTokens":4404,"toolsTokens":8247,"messageTokens":357987},"permissions":{"currentValue":"workspace-write"}}}}"#
        let history = try GatewayWireDecoder.decode(Data(historyJSON.utf8))
        let breakdown = history.projections?["values"]?["contextBreakdown"]?.decode(GatewayContextBreakdown.self)
        XCTAssertEqual(breakdown?.toolsTokens, 8247)
        XCTAssertEqual(history.projections?["values"]?["permissions"]?["currentValue"]?.stringValue, "workspace-write")
    }

    func testDecodesReplayedHumanQuestionRequest() throws {
        let json = GatewayProtocolParityFixtures.replayedQuestionRequest
        let frame = try GatewayWireDecoder.decode(Data(json.utf8))

        XCTAssertEqual(frame.rpcId, "rpc-1")
        XCTAssertEqual(frame.sessionId, "s1")
        XCTAssertEqual(frame.replay, true)
        XCTAssertEqual(frame.questions?.count, 2)
        XCTAssertEqual(frame.questions?.first?.options?.first?.label, "核心架构 (推荐)")
        XCTAssertEqual(frame.questions?.first?.allowsMultipleSelections, true)
    }

    func testDecodesQuestionResponseAndResolution() throws {
        let response = try GatewayWireDecoder.decode(Data(#"{"kind":"question-response","rpcId":"rpc-1","sessionId":"s1","action":"answer","accepted":false,"reason":"not-pending"}"#.utf8))
        XCTAssertEqual(response.action, "answer")
        XCTAssertEqual(response.accepted, false)
        XCTAssertEqual(response.reason, "not-pending")

        let resolved = try GatewayWireDecoder.decode(Data(#"{"kind":"question-resolved","rpcId":"rpc-1","sessionId":"s1","outcome":"answered"}"#.utf8))
        XCTAssertEqual(resolved.outcome, "answered")
    }

    func testPairingPayloadParserAcceptsValidTrimmedPayload() throws {
        let now = Date(timeIntervalSince1970: 1_000)
        let encoded = try pairingPayloadString(
            publicURL: "wss://gateway.example/ws/mobile",
            expiresAt: 1_001_000
        )

        let payload = try PairingPayloadParser.parse("  \n\(encoded)\t", now: now)

        XCTAssertEqual(payload.version, 2)
        XCTAssertEqual(payload.publicUrl, "wss://gateway.example/ws/mobile")
        XCTAssertEqual(payload.pairingCode, "one-time-code")
    }

    func testPairingPayloadParserRejectsInvalidBase64URLAndJSON() throws {
        XCTAssertThrowsError(try PairingPayloadParser.parse("bad=value")) {
            XCTAssertEqual($0 as? PairingPayloadError, .invalidBase64URL)
        }
        let nonJSON = base64URL(Data("not-json".utf8))
        XCTAssertThrowsError(try PairingPayloadParser.parse(nonJSON)) {
            XCTAssertEqual($0 as? PairingPayloadError, .invalidJSON)
        }
    }

    func testPairingPayloadParserRejectsUnsupportedVersion() throws {
        let encoded = try pairingPayloadString(version: 3)
        XCTAssertThrowsError(try PairingPayloadParser.parse(encoded, now: .distantPast)) {
            XCTAssertEqual($0 as? PairingPayloadError, .unsupportedVersion(3))
        }
    }

    func testPairingPayloadParserRejectsInvalidEndpoint() throws {
        for endpoint in ["https://gateway.example/ws/mobile", "ws:///missing-host", "not-a-url"] {
            let encoded = try pairingPayloadString(publicURL: endpoint)
            XCTAssertThrowsError(try PairingPayloadParser.parse(encoded, now: .distantPast)) {
                XCTAssertEqual($0 as? PairingPayloadError, .invalidEndpoint)
            }
        }
    }

    func testPairingPayloadParserRejectsInvalidPairingCode() throws {
        for code in ["", "contains,comma", "contains space", "contains\nnewline"] {
            let encoded = try pairingPayloadString(pairingCode: code)
            XCTAssertThrowsError(try PairingPayloadParser.parse(encoded, now: .distantPast)) {
                XCTAssertEqual($0 as? PairingPayloadError, .invalidCode)
            }
        }
    }

    func testPairingPayloadParserRejectsExpiredPayloadAtBoundary() throws {
        let now = Date(timeIntervalSince1970: 1_000)
        let encoded = try pairingPayloadString(expiresAt: 1_000_000)
        XCTAssertThrowsError(try PairingPayloadParser.parse(encoded, now: now)) {
            XCTAssertEqual($0 as? PairingPayloadError, .expired)
        }
    }

    func testSessionListReducerMergesSortsAndFiltersRemoteSessions() {
        var state = SessionListState(
            sessions: [SessionSummary(
                id: "existing",
                title: "旧标题",
                lastActivity: Date(timeIntervalSince1970: 10),
                isRunning: false,
                hasUnread: true,
                agentPreset: "keep-if-missing"
            )],
            archivedSessionIDs: ["archived"]
        )
        let remote = [
            GatewaySessionSummary(sessionId: "existing", updatedAt: 2_000, running: true, blank: false, cwd: "/tmp/existing"),
            GatewaySessionSummary(sessionId: "new", updatedAt: 3_000_000, running: false, blank: false, cwd: "/tmp/new", agentPreset: "standard"),
            GatewaySessionSummary(sessionId: "archived", updatedAt: 4_000, running: false, blank: false, cwd: "/tmp/archived")
        ]

        SessionListReducer.reduce(state: &state, action: .remoteSessionsReceived(remote))

        XCTAssertEqual(state.sessions.map(\.id), ["new", "existing"])
        XCTAssertEqual(state.sessions.first?.title, "new")
        XCTAssertEqual(state.sessions.first?.agentPreset, "standard")
        XCTAssertEqual(state.sessions.last?.title, "existing")
        XCTAssertEqual(state.sessions.last?.isRunning, true)
        XCTAssertEqual(state.sessions.last?.hasUnread, true)
        XCTAssertEqual(state.sessions.last?.agentPreset, "keep-if-missing")
    }

    func testSessionListReducerUpdatesRunningUnreadTitleAndReadState() {
        var state = SessionListState(selectedSessionID: "selected")
        let title = "这是一个超过二十八个字符的会话标题，用来验证标题截断逻辑是否保持不变"
        let userEvent = SessionEvent(
            sessionId: "other",
            seq: 1,
            time: 100,
            event: GatewayEvent(type: "user/message", text: title, source: "user")
        )
        SessionListReducer.reduce(state: &state, action: .eventReceived(userEvent))
        XCTAssertEqual(state.sessions.first?.title, String(title.prefix(28)))
        XCTAssertEqual(state.sessions.first?.hasUnread, true)

        let turnStart = SessionEvent(sessionId: "other", seq: 2, time: 101, event: GatewayEvent(type: "turn/start"))
        SessionListReducer.reduce(state: &state, action: .eventReceived(turnStart))
        XCTAssertEqual(state.sessions.first?.isRunning, true)

        let turnEnd = SessionEvent(sessionId: "other", seq: 3, time: 102, event: GatewayEvent(type: "turn/end"))
        SessionListReducer.reduce(state: &state, action: .eventReceived(turnEnd))
        XCTAssertEqual(state.sessions.first?.isRunning, false)

        SessionListReducer.reduce(state: &state, action: .markRead("other"))
        XCTAssertEqual(state.sessions.first?.hasUnread, false)
    }

    func testSessionListReducerMessageSentSelectsOnlyWhenNeededAndKeepsSingleSession() {
        var state = SessionListState()
        SessionListReducer.reduce(state: &state, action: .messageSent(sessionID: "created", agentPreset: "standard"))
        XCTAssertEqual(state.selectedSessionID, "created")
        XCTAssertEqual(state.sessions.map(\.id), ["created"])
        XCTAssertEqual(state.sessions.first?.agentPreset, "standard")

        SessionListReducer.reduce(state: &state, action: .messageSent(sessionID: "created", agentPreset: "updated"))
        XCTAssertEqual(state.sessions.count, 1)
        XCTAssertEqual(state.sessions.first?.agentPreset, "updated")

        SessionListReducer.reduce(state: &state, action: .select("existing-selection"))
        SessionListReducer.reduce(state: &state, action: .messageSent(sessionID: "second", agentPreset: nil))
        XCTAssertEqual(state.selectedSessionID, "existing-selection")
    }

    func testQuestionReducerAddsRequestAndReplayPreservesSubmissionStatus() {
        let request = questionRequest()
        var state = QuestionState()

        QuestionReducer.reduce(state: &state, action: .requestReceived(request))
        XCTAssertEqual(state.pendingRequests, [request])
        XCTAssertEqual(state.requestStatuses[request.rpcId], .idle)

        QuestionReducer.reduce(
            state: &state,
            action: .submit(
                request: request,
                submission: .answer([
                    GatewayQuestionAnswer(id: "direction", selected: ["架构"]),
                    GatewayQuestionAnswer(id: "notes", selected: [], custom: "保持原生 UI")
                ]),
                isConnected: true
            )
        )
        XCTAssertEqual(state.requestStatuses[request.rpcId], .submitting(.answer))

        var replay = request
        replay.replay = true
        QuestionReducer.reduce(state: &state, action: .requestReceived(replay))
        XCTAssertEqual(state.pendingRequests, [replay])
        XCTAssertEqual(state.requestStatuses[request.rpcId], .submitting(.answer))
    }

    func testQuestionReducerRejectsDisconnectedAnswerAndCancel() {
        let request = questionRequest()
        var state = QuestionState(pendingRequests: [request])

        QuestionReducer.reduce(
            state: &state,
            action: .submit(request: request, submission: .answer([]), isConnected: false)
        )
        XCTAssertEqual(
            state.requestStatuses[request.rpcId],
            .rejected(String(localized: "q.rejected.ws.disconnected.answer", defaultValue: "WebSocket 已断开，重连后再提交答案。"))
        )

        QuestionReducer.reduce(
            state: &state,
            action: .submit(request: request, submission: .cancel, isConnected: false)
        )
        XCTAssertEqual(
            state.requestStatuses[request.rpcId],
            .rejected(String(localized: "q.rejected.ws.disconnected.skip", defaultValue: "WebSocket 已断开，重连后再跳过问题。"))
        )
    }

    func testQuestionReducerValidatesAnswerOrderOptionsAndSingleSelection() {
        let request = questionRequest()
        var state = QuestionState(pendingRequests: [request])

        QuestionReducer.reduce(
            state: &state,
            action: .submit(
                request: request,
                submission: .answer([GatewayQuestionAnswer(id: "notes", selected: [])]),
                isConnected: true
            )
        )
        XCTAssertEqual(
            state.requestStatuses[request.rpcId],
            .rejected(String(localized: "答案必须按原顺序覆盖整组问题。"))
        )

        QuestionReducer.reduce(
            state: &state,
            action: .submit(
                request: request,
                submission: .answer([
                    GatewayQuestionAnswer(id: "direction", selected: ["不存在"]),
                    GatewayQuestionAnswer(id: "notes", selected: [])
                ]),
                isConnected: true
            )
        )
        XCTAssertEqual(
            state.requestStatuses[request.rpcId],
            .rejected(String(
                format: String(localized: "q.rejected.bad-options"),
                "研究方向"
            ))
        )

        QuestionReducer.reduce(
            state: &state,
            action: .submit(
                request: request,
                submission: .answer([
                    GatewayQuestionAnswer(id: "direction", selected: ["架构", "架构"]),
                    GatewayQuestionAnswer(id: "notes", selected: [])
                ]),
                isConnected: true
            )
        )
        XCTAssertEqual(
            state.requestStatuses[request.rpcId],
            .rejected(String(
                format: String(localized: "q.rejected.bad-options"),
                "研究方向"
            ))
        )

        QuestionReducer.reduce(
            state: &state,
            action: .submit(
                request: request,
                submission: .answer([
                    GatewayQuestionAnswer(id: "direction", selected: ["架构"]),
                    GatewayQuestionAnswer(id: "notes", selected: ["简洁"], custom: "也要完整")
                ]),
                isConnected: true
            )
        )
        XCTAssertEqual(
            state.requestStatuses[request.rpcId],
            .rejected(String(localized: "单选题只能选择一个选项，且不能同时填写自定义答案。"))
        )
    }

    func testQuestionReducerHandlesAcceptedRejectedAndNotPendingResponses() {
        let request = questionRequest()
        var state = QuestionState(
            pendingRequests: [request],
            requestStatuses: [request.rpcId: .submitting(.answer)]
        )

        QuestionReducer.reduce(
            state: &state,
            action: .responseReceived(
                rpcID: request.rpcId,
                action: .answer,
                accepted: true,
                reason: nil
            )
        )
        XCTAssertEqual(state.requestStatuses[request.rpcId], .accepted(.answer))

        QuestionReducer.reduce(
            state: &state,
            action: .responseReceived(
                rpcID: request.rpcId,
                action: .answer,
                accepted: false,
                reason: "bad-response"
            )
        )
        XCTAssertEqual(
            state.requestStatuses[request.rpcId],
            .rejected(String(
                format: String(localized: "q.rejected.server-refused"),
                "bad-response"
            ))
        )

        QuestionReducer.reduce(
            state: &state,
            action: .responseReceived(
                rpcID: request.rpcId,
                action: .answer,
                accepted: false,
                reason: "not-pending"
            )
        )
        XCTAssertTrue(state.pendingRequests.isEmpty)
        XCTAssertNil(state.requestStatuses[request.rpcId])
    }

    func testQuestionReducerHandlesFailureResolutionAndReset() {
        let request = questionRequest()
        var state = QuestionState(
            pendingRequests: [request],
            requestStatuses: [request.rpcId: .submitting(.cancel)]
        )

        QuestionReducer.reduce(
            state: &state,
            action: .requestFailed(rpcID: request.rpcId, message: "")
        )
        XCTAssertEqual(
            state.requestStatuses[request.rpcId],
            .rejected(String(localized: "服务端拒绝了问题响应。"))
        )

        QuestionReducer.reduce(state: &state, action: .resolved(rpcID: request.rpcId))
        XCTAssertTrue(state.pendingRequests.isEmpty)
        XCTAssertNil(state.requestStatuses[request.rpcId])

        QuestionReducer.reduce(state: &state, action: .requestReceived(request))
        QuestionReducer.reduce(state: &state, action: .reset)
        XCTAssertEqual(state, QuestionState())
    }

    func testSessionControlReducerTracksRequestLifecycles() {
        var state = SessionControlState()

        SessionControlReducer.reduce(state: &state, action: .requestStarted("models"))
        SessionControlReducer.reduce(state: &state, action: .requestStarted("context-usage"))
        SessionControlReducer.reduce(
            state: &state,
            action: .defaultConfigurationRequestStarted("defaults")
        )
        XCTAssertEqual(state.loadingKinds, ["models", "context-usage"])
        XCTAssertEqual(state.defaultConfigurationLoadingKinds, ["defaults"])

        SessionControlReducer.reduce(state: &state, action: .requestFinished("context-usage"))
        SessionControlReducer.reduce(
            state: &state,
            action: .defaultConfigurationRequestFinished("defaults")
        )
        XCTAssertEqual(state.loadingKinds, ["models"])
        XCTAssertTrue(state.defaultConfigurationLoadingKinds.isEmpty)

        SessionControlReducer.reduce(state: &state, action: .requestTimedOut("models"))
        XCTAssertTrue(state.loadingKinds.isEmpty)
    }

    func testSessionControlReducerCorrelatesSessionAndGlobalRequests() {
        var state = SessionControlState()

        SessionControlReducer.reduce(
            state: &state,
            action: .modelsRequestTargeted(sessionID: "session-1")
        )
        SessionControlReducer.reduce(
            state: &state,
            action: .modelSelectionTargeted(sessionID: "session-1")
        )
        SessionControlReducer.reduce(
            state: &state,
            action: .permissionOptionsTargeted(sessionID: "session-1")
        )
        XCTAssertEqual(state.pendingModelsSessionID, "session-1")
        XCTAssertFalse(state.isPendingGlobalModelsRequest)
        XCTAssertEqual(state.pendingModelSelectionSessionID, "session-1")
        XCTAssertEqual(state.pendingPermissionOptionsSessionID, "session-1")

        SessionControlReducer.reduce(state: &state, action: .modelSelectionResolved)
        SessionControlReducer.reduce(state: &state, action: .permissionOptionsResolved)
        SessionControlReducer.reduce(
            state: &state,
            action: .modelsRequestTargeted(sessionID: nil)
        )
        XCTAssertNil(state.pendingModelSelectionSessionID)
        XCTAssertNil(state.pendingPermissionOptionsSessionID)
        XCTAssertNil(state.pendingModelsSessionID)
        XCTAssertTrue(state.isPendingGlobalModelsRequest)

        SessionControlReducer.reduce(state: &state, action: .requestFinished("models"))
        XCTAssertFalse(state.isPendingGlobalModelsRequest)
        XCTAssertNil(state.pendingModelsSessionID)
    }

    func testSessionControlReducerUpdatesModelsAndDefaults() {
        var state = SessionControlState()
        let selected = GatewayModelSelection(provider: "openai", model: "gpt-5", reasoningEffort: "high")

        SessionControlReducer.reduce(
            state: &state,
            action: .modelsReceived(
                sessionID: "session-1",
                current: nil,
                routable: true,
                groups: [],
                isGlobalRequest: false
            )
        )
        SessionControlReducer.reduce(
            state: &state,
            action: .modelSelected(sessionID: "session-1", selection: selected)
        )
        XCTAssertEqual(state.modelCatalogs["session-1"]?.current, selected)
        XCTAssertEqual(state.modelCatalogs["session-1"]?.routable, true)

        SessionControlReducer.reduce(
            state: &state,
            action: .modelsReceived(
                sessionID: nil,
                current: selected,
                routable: true,
                groups: [],
                isGlobalRequest: true
            )
        )
        XCTAssertNotNil(state.globalModelCatalog)
        XCTAssertNil(state.globalModelCatalog?.current)

        SessionControlReducer.reduce(
            state: &state,
            action: .defaultsReceived(agentPreset: "standard", permission: "read-only")
        )
        SessionControlReducer.reduce(
            state: &state,
            action: .globalDefaultApplied(target: "permission", value: "workspace-write")
        )
        SessionControlReducer.reduce(state: &state, action: .defaultModelReceived(selected))
        XCTAssertEqual(state.agentPresetDefault, "standard")
        XCTAssertEqual(state.permissionDefault, "workspace-write")
        XCTAssertEqual(state.defaultModelSelection, selected)
    }

    func testSessionControlReducerFiltersPermissionsAndMergesContextSnapshots() {
        var state = SessionControlState()
        let permissions = GatewaySessionPermissions(
            options: [
                GatewayPermissionOption(value: "read-only", name: "只读"),
                GatewayPermissionOption(value: "unsupported", name: "未知")
            ],
            currentValue: "read-only"
        )
        SessionControlReducer.reduce(
            state: &state,
            action: .permissionsReceived(sessionID: "session-1", permissions: permissions)
        )
        SessionControlReducer.reduce(
            state: &state,
            action: .permissionSelected(sessionID: "session-1", value: "workspace-write")
        )
        XCTAssertEqual(state.sessionPermissions["session-1"]?.options?.map(\.value), ["read-only"])
        XCTAssertEqual(state.sessionPermissions["session-1"]?.currentValue, "workspace-write")
        XCTAssertEqual(state.sessionPermissions["session-1"]?.preset, "workspace-write")

        let usage = GatewayTokenUsage(uncachedInputTokens: 10, outputTokens: 3)
        let breakdown = GatewayContextBreakdown(systemTokens: 1, toolsTokens: 2, messageTokens: 3)
        SessionControlReducer.reduce(
            state: &state,
            action: .contextReceived(
                sessionID: "session-1",
                asOfSequence: 20,
                tokenUsage: usage,
                pressure: nil,
                breakdown: nil
            )
        )
        SessionControlReducer.reduce(
            state: &state,
            action: .contextReceived(
                sessionID: "session-1",
                asOfSequence: nil,
                tokenUsage: nil,
                pressure: nil,
                breakdown: breakdown
            )
        )
        XCTAssertEqual(state.contextSnapshots["session-1"]?.asOfSeq, 20)
        XCTAssertEqual(state.contextSnapshots["session-1"]?.tokenUsage, usage)
        XCTAssertEqual(state.contextSnapshots["session-1"]?.breakdown, breakdown)
    }

    func testSessionControlReducerMergesSessionStatsWithoutDiscardingExistingValues() {
        var state = SessionControlState()
        let totals = GatewaySessionTokenUsageTotals(inputTokens: 100, outputTokens: 20)
        let pressure = GatewayContextPressure(pressureTokens: 80, projectedTokens: 90, contextWindow: 1_000)

        SessionControlReducer.reduce(
            state: &state,
            action: .statsReceived(
                sessionID: "session-1",
                asOfSequence: 30,
                stats: nil,
                tokenUsageTotals: totals,
                contextPressure: nil
            )
        )
        SessionControlReducer.reduce(
            state: &state,
            action: .statsReceived(
                sessionID: "session-1",
                asOfSequence: nil,
                stats: nil,
                tokenUsageTotals: nil,
                contextPressure: pressure
            )
        )
        XCTAssertEqual(state.sessionStatsSnapshots["session-1"]?.asOfSeq, 30)
        XCTAssertEqual(state.sessionStatsSnapshots["session-1"]?.tokenUsage?.totals, totals)
        XCTAssertEqual(state.sessionStatsSnapshots["session-1"]?.contextPressure, pressure)
    }

    @MainActor
    func testRequestTrackerFiresOnlyLatestGeneration() async throws {
        let tracker = RequestTracker()
        var timeouts: [String] = []
        tracker.begin("models", timeout: .milliseconds(10)) { timeouts.append("stale") }
        tracker.begin("models", timeout: .milliseconds(30)) { timeouts.append("latest") }

        try await Task.sleep(for: .milliseconds(60))

        XCTAssertEqual(timeouts, ["latest"])
        XCTAssertTrue(tracker.activeKeys.isEmpty)
    }

    @MainActor
    func testRequestTrackerFinishAndFinishAllCancelTimeouts() async throws {
        let tracker = RequestTracker()
        var timeoutCount = 0
        tracker.begin("models", timeout: .milliseconds(20)) { timeoutCount += 1 }
        tracker.begin("stats", timeout: .milliseconds(20)) { timeoutCount += 1 }
        tracker.finish("models")
        tracker.finishAll()

        try await Task.sleep(for: .milliseconds(40))

        XCTAssertEqual(timeoutCount, 0)
        XCTAssertTrue(tracker.activeKeys.isEmpty)
    }

    func testHistoryReducerLoadsTwoPageBatchAndKeepsPaginationCursor() {
        var state = HistoryState()
        XCTAssertEqual(
            HistoryReducer.reduce(
                state: &state,
                action: .start(
                    sessionID: "session-1",
                    older: false,
                    hasLocalEvents: true,
                    earliestLocalSequence: 100
                )
            ),
            .requestPage(beforeSequence: nil)
        )
        XCTAssertEqual(
            HistoryReducer.reduce(
                state: &state,
                action: .pageCommitted(
                    sessionID: "session-1",
                    eventCount: 60,
                    byteCount: 1_000,
                    hasMore: true,
                    nextBeforeSequence: 50,
                    earliestLocalSequence: 100,
                    remoteActivityTimestamp: nil
                )
            ),
            .requestPage(beforeSequence: 50)
        )

        let activity = Date(timeIntervalSince1970: 500)
        XCTAssertEqual(
            HistoryReducer.reduce(
                state: &state,
                action: .pageCommitted(
                    sessionID: "session-1",
                    eventCount: 40,
                    byteCount: 500,
                    hasMore: true,
                    nextBeforeSequence: 10,
                    earliestLocalSequence: 50,
                    remoteActivityTimestamp: activity.timeIntervalSince1970
                )
            ),
            .completed(eventCount: 100, byteCount: 1_500, hasMore: true)
        )
        XCTAssertEqual(state.sessions["session-1"]?.nextBeforeSequence, 50)
        XCTAssertEqual(
            state.sessions["session-1"]?.syncedActivityTimestamp,
            activity.timeIntervalSince1970
        )
        XCTAssertEqual(state.sessions["session-1"]?.isLoading, false)
    }

    func testHistoryReducerStopsOlderLoadWithoutCursorAndRejectsInvalidPagination() {
        var noCursorState = HistoryState(sessions: ["session-1": HistorySessionState(hasMore: true)])
        XCTAssertEqual(
            HistoryReducer.reduce(
                state: &noCursorState,
                action: .start(
                    sessionID: "session-1",
                    older: true,
                    hasLocalEvents: false,
                    earliestLocalSequence: nil
                )
            ),
            .stopped
        )
        XCTAssertEqual(noCursorState.sessions["session-1"]?.hasMore, false)

        var missingCursorState = HistoryState()
        _ = HistoryReducer.reduce(
            state: &missingCursorState,
            action: .start(
                sessionID: "session-1",
                older: false,
                hasLocalEvents: false,
                earliestLocalSequence: nil
            )
        )
        XCTAssertEqual(
            HistoryReducer.reduce(
                state: &missingCursorState,
                action: .pageCommitted(
                    sessionID: "session-1",
                    eventCount: 1,
                    byteCount: 0,
                    hasMore: true,
                    nextBeforeSequence: nil,
                    earliestLocalSequence: 1,
                    remoteActivityTimestamp: nil
                )
            ),
            .failed(String(localized: "history.pagination.stopped.hasmore", defaultValue: "网关返回 hasMore:true，但缺少 nextBeforeSeq，已停止自动续页。"))
        )
    }

    func testHistoryReducerRejectsRepeatedCursor() {
        var session = HistorySessionState(hasMore: true)
        session.nextBeforeSequence = 100
        var state = HistoryState(sessions: ["session-1": session])
        _ = HistoryReducer.reduce(
            state: &state,
            action: .start(
                sessionID: "session-1",
                older: true,
                hasLocalEvents: true,
                earliestLocalSequence: 100
            )
        )
        let result = HistoryReducer.reduce(
            state: &state,
            action: .pageCommitted(
                sessionID: "session-1",
                eventCount: 10,
                byteCount: 10,
                hasMore: true,
                nextBeforeSequence: 100,
                earliestLocalSequence: 100,
                remoteActivityTimestamp: nil
            )
        )
        guard case .failed(let message) = result else {
            return XCTFail("重复历史游标必须停止分页并返回失败")
        }
        XCTAssertTrue(message.contains("100"))
        XCTAssertFalse(state.sessions["session-1"]?.isLoading ?? true)
    }

    func testHistoryEventMergerAppendsInOrderAndReplacesDuplicateSequence() {
        func event(_ sequence: Int, _ text: String) -> SessionEvent {
            SessionEvent(
                sessionId: "session-1",
                seq: sequence,
                time: Double(sequence),
                event: GatewayEvent(type: "assistant/message", text: text)
            )
        }
        var events = [event(1, "one"), event(3, "old-three")]
        XCTAssertTrue(
            HistoryEventMerger.merge(event(2, "two"), into: &events).replacedOrInsertedOutOfOrder
        )
        XCTAssertTrue(
            HistoryEventMerger.merge(event(3, "new-three"), into: &events).replacedOrInsertedOutOfOrder
        )
        XCTAssertFalse(
            HistoryEventMerger.merge(event(4, "four"), into: &events).replacedOrInsertedOutOfOrder
        )
        XCTAssertEqual(events.map(\.seq), [1, 2, 3, 4])
        XCTAssertEqual(events[2].event.text, "new-three")
    }

    @MainActor
    func testHistorySyncEngineInvalidatesLateGenerationsAndTimeouts() async throws {
        let engine = HistorySyncEngine()
        var timeoutCount = 0
        engine.beginRequest(sessionID: "session-1", timeout: .milliseconds(100)) {
            timeoutCount += 1
        }
        let processing = engine.beginProcessing(sessionID: "session-1")
        XCTAssertTrue(engine.isCurrent(processing, sessionID: "session-1"))
        try await Task.sleep(for: .milliseconds(150))
        XCTAssertEqual(timeoutCount, 0)

        engine.beginRequest(sessionID: "session-1", timeout: .milliseconds(100)) {
            timeoutCount += 1
        }
        try await Task.sleep(for: .milliseconds(200))
        XCTAssertEqual(timeoutCount, 1)
        XCTAssertFalse(engine.isActive(sessionID: "session-1"))
    }

    func testQuestionAnswerTrimsCustomText() {
        let answer = GatewayQuestionAnswer(id: "custom", selected: [], custom: "  自定义回答  ")
        XCTAssertEqual(answer.custom, "自定义回答")
        XCTAssertNil(GatewayQuestionAnswer(id: "empty", selected: [], custom: "  \n ").custom)
    }

    private func questionRequest() -> GatewayPendingQuestionRequest {
        GatewayPendingQuestionRequest(
            rpcId: "rpc-question",
            sessionId: "session-question",
            questions: [
                GatewayQuestion(
                    id: "direction",
                    question: "研究方向",
                    options: [
                        GatewayQuestionOption(label: "架构"),
                        GatewayQuestionOption(label: "界面")
                    ],
                    multiSelect: true
                ),
                GatewayQuestion(
                    id: "notes",
                    question: "补充要求",
                    options: [GatewayQuestionOption(label: "简洁")],
                    multiSelect: false
                )
            ],
            replay: false
        )
    }

    private func pairingPayloadString(
        version: Int = 2,
        publicURL: String = "ws://gateway.example:3080/ws/mobile",
        pairingCode: String = "one-time-code",
        expiresAt: Double = 4_102_444_800_000
    ) throws -> String {
        let payload = GatewayPairingPayload(
            version: version,
            publicUrl: publicURL,
            pairingCode: pairingCode,
            expiresAt: expiresAt
        )
        return base64URL(try JSONEncoder().encode(payload))
    }

    private func base64URL(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}

private final class AppPreferencesSpy: AppPreferences {
    var endpoint: String
    var selectedWorkspaceID: String?
    private let sessions: [SessionSummary]
    private(set) var savedSessionSnapshots: [[SessionSummary]] = []
    private(set) var performMigrationsCallCount = 0

    init(endpoint: String, selectedWorkspaceID: String?, sessions: [SessionSummary]) {
        self.endpoint = endpoint
        self.selectedWorkspaceID = selectedWorkspaceID
        self.sessions = sessions
    }

    func loadSessions() -> [SessionSummary] { sessions }

    func saveSessions(_ sessions: [SessionSummary]) {
        savedSessionSnapshots.append(sessions)
    }

    func performMigrations() {
        performMigrationsCallCount += 1
    }
}

@MainActor
private final class BackgroundTaskApplicationSpy: BackgroundTaskApplication {
    private(set) var beginCallCount = 0
    private(set) var endedIdentifiers: [UIBackgroundTaskIdentifier] = []
    private(set) var expirationHandler: (@Sendable () -> Void)?

    func beginBackgroundTask(
        withName taskName: String?,
        expirationHandler handler: (@Sendable () -> Void)?
    ) -> UIBackgroundTaskIdentifier {
        beginCallCount += 1
        expirationHandler = handler
        return UIBackgroundTaskIdentifier(rawValue: beginCallCount)
    }

    func endBackgroundTask(_ identifier: UIBackgroundTaskIdentifier) {
        endedIdentifiers.append(identifier)
    }
}
