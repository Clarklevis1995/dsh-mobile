import XCTest
import UIKit
import ImageIO
import UniformTypeIdentifiers
import class DeepSeekHarnessShared.SharedQuestionResult
import class DeepSeekHarnessShared.SharedMviEvent
import class DeepSeekHarnessShared.SharedMviDispatchResult
import class DeepSeekHarnessShared.SharedSessionControlResult
import class DeepSeekHarnessShared.SharedSessionControlStore
import class DeepSeekHarnessShared.SharedSessionListResult
import class DeepSeekHarnessShared.KotlinLong
import class DeepSeekHarnessShared.KotlinInt
import class DeepSeekHarnessShared.KotlinDouble
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

private func swiftAuditRange(
    from startMarker: String,
    through endMarker: String,
    in source: NSString
) throws -> NSRange {
    let startRange = source.range(of: startMarker)
    guard startRange.location != NSNotFound else {
        throw NSError(
            domain: "Stage9SourceAudit",
            code: 1,
            userInfo: [NSLocalizedDescriptionKey: "找不到源码审计起始标记：\(startMarker)"]
        )
    }
    let searchRange = NSRange(
        location: NSMaxRange(startRange),
        length: source.length - NSMaxRange(startRange)
    )
    let endRange = source.range(of: endMarker, options: [], range: searchRange)
    guard endRange.location != NSNotFound else {
        throw NSError(
            domain: "Stage9SourceAudit",
            code: 2,
            userInfo: [NSLocalizedDescriptionKey: "找不到源码审计结束标记：\(endMarker)"]
        )
    }
    return NSRange(
        location: startRange.location,
        length: NSMaxRange(endRange) - startRange.location
    )
}

private func stage9WriteMatches(for property: String, in source: String) throws -> [NSTextCheckingResult] {
    let escapedProperty = NSRegularExpression.escapedPattern(for: property)
    let nestedAccess = "(?:(?:\\s*\\[[^\\]\\n]+\\])|(?:\\s*[?!]?\\.\\s*[A-Za-z_][A-Za-z0-9_]*))*"
    let writePatterns = [
        "(?<![A-Za-z0-9_\\.])(?:self\\.)?\(escapedProperty)\(nestedAccess)\\s*(?:=|[+\\-*/%&|^]=)(?!=)",
        "&\\s*(?:self\\.)?\(escapedProperty)\(nestedAccess)(?![A-Za-z0-9_])",
        "(?<![A-Za-z0-9_\\.])(?:self\\.)?\(escapedProperty)\(nestedAccess)\\s*\\.\\s*(?:append|appendContentsOf|insert|popFirst|popLast|remove|removeAll|removeFirst|removeLast|removeSubrange|replaceSubrange|removeValue|updateValue|update|merge|swapAt|sort|reverse|shuffle|partition|reserveCapacity|formUnion|formIntersection|formSymmetricDifference|subtract|toggle|withUnsafeMutableBytes|withUnsafeMutablePointer|withUnsafeMutableBufferPointer|withContiguousMutableStorageIfAvailable)\\s*\\("
    ]
    return try writePatterns.flatMap { pattern in
        try NSRegularExpression(pattern: pattern).matches(
            in: source,
            range: NSRange(source.startIndex..., in: source)
        )
    }
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
    func testKMPConversationAdapterConsumesIncrementalPushEvents() throws {
        let adapter = KMPConversationStoreAdapter()
        var publishedTexts: [[String]] = []
        adapter.onChange = { change in
            publishedTexts.append(change.items.map(\.text))
        }

        try adapter.receive(SessionEvent(
            sessionId: "s1", seq: 1, time: 1,
            event: GatewayEvent(
                type: "assistant/chunk", turn: 1, step: 1,
                text: "Hel", chunkType: "text-delta"
            )
        ))
        try adapter.receive(SessionEvent(
            sessionId: "s1", seq: 2, time: 2,
            event: GatewayEvent(
                type: "assistant/chunk", turn: 1, step: 1,
                text: "lo", chunkType: "text-delta"
            )
        ))

        XCTAssertTrue(adapter.isOperational)
        XCTAssertEqual(adapter.items(for: "s1").map(\.text), ["Hello"])
        XCTAssertEqual(publishedTexts, [["Hel"], ["Hello"]])

        let baseline = [
            SessionEvent(
                sessionId: "s1", seq: 3, time: 3,
                event: GatewayEvent(type: "user/message", text: "新基线", source: "user")
            )
        ]
        try adapter.replace(sessionID: "s1", events: baseline)
        XCTAssertEqual(adapter.items(for: "s1").map(\.text), ["新基线"])

        try adapter.clear(sessionID: "s1")
        XCTAssertTrue(adapter.items(for: "s1").isEmpty)
    }

    @MainActor
    func testKMPConversationAdapterFailsClosedBeforeMalformedPatchPublishes() {
        let bridge = MalformedConversationEventBridge()
        let adapter = KMPConversationStoreAdapter(bridge: bridge)
        var publishCount = 0
        adapter.onChange = { _ in publishCount += 1 }

        XCTAssertThrowsError(try adapter.receive(SessionEvent(
            sessionId: "s1", seq: 1, time: 1,
            event: GatewayEvent(
                type: "assistant/chunk", turn: 1, step: 1,
                text: "x", chunkType: "text-delta"
            )
        )))
        XCTAssertFalse(adapter.isOperational)
        XCTAssertEqual(publishCount, 0)
        XCTAssertTrue(adapter.items(for: "s1").isEmpty)
        XCTAssertEqual(bridge.receiveCallCount, 1)

        XCTAssertThrowsError(try adapter.receive(SessionEvent(
            sessionId: "s1", seq: 2, time: 2,
            event: GatewayEvent(type: "user/message", text: "不会进入 KMP", source: "user")
        )))
        XCTAssertEqual(bridge.receiveCallCount, 1)
    }

    @MainActor
    func testKMPConversationProjectionMatchesSwiftMigrationFixture() throws {
        let image = GatewayImageAttachment(
            attachmentId: "image-1", mediaType: "image/png", bytes: 4,
            width: 1, height: 1, name: "pixel.png"
        )
        let records = [
            SessionEvent(sessionId: "s1", seq: 1, time: 1, event: GatewayEvent(
                type: "user/message", text: "上下文", source: "plugin",
                raw: .object(["source": .object(["plugin": .string("skill")])])
            )),
            SessionEvent(sessionId: "s1", seq: 2, time: 2, event: GatewayEvent(
                type: "user/message", text: "看图", source: "user", images: [image]
            )),
            SessionEvent(sessionId: "s1", seq: 3, time: 3, event: GatewayEvent(
                type: "assistant/chunk", turn: 1, step: 1,
                text: "临时", chunkType: "text-delta"
            )),
            SessionEvent(sessionId: "s1", seq: 4, time: 4, event: GatewayEvent(
                type: "assistant/message", turn: 1, step: 1,
                text: "完成", reasoning: "思考"
            )),
            SessionEvent(sessionId: "s1", seq: 5, time: 5, event: GatewayEvent(
                type: "tool/call", callId: "call-1", name: "Read",
                arguments: .object(["path": .string("README.md")])
            )),
            SessionEvent(sessionId: "s1", seq: 6, time: 6, event: GatewayEvent(
                type: "tool/result", callId: "call-1", isError: false, preview: "内容"
            ))
        ]
        let swiftBaseline = ConversationItem.make(from: records)
        let adapter = KMPConversationStoreAdapter()

        try adapter.replace(sessionID: "s1", events: records)
        let kmp = adapter.items(for: "s1")

        XCTAssertEqual(kmp.map(\.id), swiftBaseline.map(\.id))
        XCTAssertEqual(kmp.map(\.kind), swiftBaseline.map(\.kind))
        XCTAssertEqual(kmp.map(\.title), swiftBaseline.map(\.title))
        for (kmpItem, swiftItem) in zip(kmp, swiftBaseline) where kmpItem.kind != .tool {
            XCTAssertEqual(kmpItem.text, swiftItem.text)
        }
        XCTAssertTrue(kmp.first(where: { $0.kind == .tool })?.text.contains("README.md") == true)
        XCTAssertEqual(kmp.map(\.images), swiftBaseline.map(\.images))
        XCTAssertEqual(kmp.map(\.isError), swiftBaseline.map(\.isError))
        XCTAssertEqual(kmp.map(\.date), swiftBaseline.map(\.date))
    }

    @MainActor
    func testKMPTrajectoryAdapterProjectsNodesAndTokenUsage() throws {
        let usage: JSONValue = .object([
            "inputTokens": .number(10),
            "cacheReadTokens": .number(4),
            "outputTokens": .number(8),
            "reasoningTokens": .number(3)
        ])
        let records = [
            SessionEvent(
                sessionId: "s1", seq: 1, time: 1,
                event: GatewayEvent(type: "user/message", text: "执行", source: "user")
            ),
            SessionEvent(
                sessionId: "s1", seq: 2, time: 2,
                event: GatewayEvent(
                    type: "assistant/message", turn: 1, step: 1,
                    text: "完成", usage: usage
                )
            ),
            SessionEvent(
                sessionId: "s1", seq: 3, time: 3,
                event: GatewayEvent(
                    type: "tool/call", turn: 1, step: 1,
                    callId: "c1", name: "Read"
                )
            )
        ]
        let swiftBaseline = TrajectoryProjection.make(from: records)
        let adapter = KMPTrajectoryStoreAdapter()
        var changes: [KMPTrajectoryChange] = []
        adapter.onChange = { changes.append($0) }

        try adapter.replace(sessionID: "s1", events: records)
        let kmp = adapter.nodes(for: "s1")

        XCTAssertFalse(
            kmp.isEmpty,
            "KMP Trajectory 为空：error=\(String(describing: adapter.runtimeError)), changes=\(changes.map { ($0.sessionID, $0.nodes.count) })"
        )
        XCTAssertEqual(kmp.map(\.id), swiftBaseline.map(\.id))
        XCTAssertEqual(kmp.map(\.kind), swiftBaseline.map(\.kind))
        XCTAssertEqual(kmp.map(\.subtitle), swiftBaseline.map(\.subtitle))
        let request = try XCTUnwrap(kmp.first(where: { $0.kind == .request })?.request)
        XCTAssertEqual(request.usage.uncachedInput, 10)
        XCTAssertEqual(request.usage.cachedInput, 4)
        XCTAssertEqual(request.usage.output, 8)
        XCTAssertEqual(request.usage.reasoning, 3)
        XCTAssertEqual(request.usage.content, 5)
    }

    @MainActor
    func testKMPTrajectoryAdapterFailsClosedBeforeMalformedUpdatePublishes() {
        let bridge = MalformedTrajectoryEventBridge()
        let adapter = KMPTrajectoryStoreAdapter(bridge: bridge)
        var publishCount = 0
        adapter.onChange = { _ in publishCount += 1 }

        XCTAssertThrowsError(try adapter.receive(SessionEvent(
            sessionId: "s1", seq: 1, time: 1,
            event: GatewayEvent(
                type: "assistant/chunk", turn: 1, step: 1,
                text: "x", chunkType: "text-delta"
            )
        )))
        XCTAssertFalse(adapter.isOperational)
        XCTAssertEqual(publishCount, 0)
        XCTAssertTrue(adapter.nodes(for: "s1").isEmpty)
    }

    @MainActor
    func testAppStoreActivatesTrajectoryProjectionOnlyForVisiblePage() {
        let store = AppStore(preferences: AppPreferencesSpy(
            endpoint: "ws://127.0.0.1:3080/ws/mobile",
            selectedWorkspaceID: nil,
            sessions: []
        ))
        store.events["s1"] = [
            SessionEvent(
                sessionId: "s1", seq: 1, time: 1,
                event: GatewayEvent(type: "user/message", text: "one", source: "user")
            )
        ]
        let timeline = store.trajectoryTimeline(for: "s1")
        XCTAssertTrue(timeline.nodes.isEmpty)

        store.setTrajectoryProjectionActive(sessionID: "s1", isActive: true)
        XCTAssertEqual(timeline.nodes.map(\.subtitle), ["one"])

        store.setTrajectoryProjectionActive(sessionID: "s1", isActive: false)
        store.events["s1"]?.append(SessionEvent(
            sessionId: "s1", seq: 2, time: 2,
            event: GatewayEvent(type: "user/message", text: "two", source: "user")
        ))
        XCTAssertEqual(timeline.nodes.map(\.subtitle), ["one"])
        store.setTrajectoryProjectionActive(sessionID: "s1", isActive: true)
        XCTAssertEqual(timeline.nodes.map(\.subtitle), ["one", "two"])
    }

    @MainActor
    func testKMPHistoryAdapterOwnsPaginationWatermarkAndLiveTailMerge() throws {
        let adapter = KMPHistoryStoreAdapter()
        var changes: [KMPHistoryChange] = []
        adapter.onChange = { changes.append($0) }

        try adapter.start(
            sessionID: "s1",
            older: false,
            hasLocalEvents: false,
            earliestLocalSequence: nil
        )
        XCTAssertEqual(changes.last?.effect, KMPHistoryEffect(action: "request-page", sessionId: "s1", beforeSequence: nil))
        XCTAssertTrue(changes.last?.loadingSessionIDs.contains("s1") == true)

        try adapter.processingStarted(sessionID: "s1", rawEventCount: 2, hasMore: false)
        let live = SessionEvent(
            sessionId: "s1", seq: 2, time: 2,
            event: GatewayEvent(type: "assistant/message", text: "live-final")
        )
        try adapter.receive(live)
        try adapter.pageReceived(
            sessionID: "s1",
            events: [
                SessionEvent(
                    sessionId: "s1", seq: 1, time: 1,
                    event: GatewayEvent(type: "user/message", text: "history")
                ),
                SessionEvent(
                    sessionId: "s1", seq: 2, time: 2,
                    event: GatewayEvent(type: "assistant/message", text: "stale-history")
                )
            ],
            byteCount: 32,
            hasMore: false,
            nextBeforeSequence: nil,
            remoteActivityTimestamp: 2
        )

        XCTAssertEqual(adapter.events(for: "s1").map(\.seq), [1, 2])
        XCTAssertEqual(adapter.events(for: "s1").last?.event.text, "live-final")
        XCTAssertEqual(adapter.syncedActivityTimestamp(for: "s1"), 2)
        XCTAssertEqual(changes.last?.outcome, "completed")
        XCTAssertEqual(changes.last?.completedEventCount, 2)
        XCTAssertFalse(changes.last?.loadingSessionIDs.contains("s1") == true)
    }

    @MainActor
    func testKMPHistoryAdapterFailsClosedBeforeMalformedEventPatchPublishes() {
        let adapter = KMPHistoryStoreAdapter(bridge: MalformedHistoryEventBridge())
        var publishCount = 0
        adapter.onChange = { _ in publishCount += 1 }

        XCTAssertThrowsError(try adapter.receive(SessionEvent(
            sessionId: "s1", seq: 1, time: 1,
            event: GatewayEvent(type: "assistant/message", text: "one")
        )))
        XCTAssertFalse(adapter.isOperational)
        XCTAssertEqual(publishCount, 0)
        XCTAssertTrue(adapter.events(for: "s1").isEmpty)
    }

    func testStage9WriteAuditRecognizesDirectNestedInoutAndCollectionMutations() throws {
        let defects: [(property: String, source: String)] = [
            ("sessions", "sessions = replacement"),
            ("sessions", "sessions += replacement"),
            ("sessions", "sessions.append(candidate)"),
            ("sessions", "sessions.popLast()"),
            ("sessions", "sessions[0].title = replacement"),
            ("modelCatalogs", "modelCatalogs[id]?.current = selection"),
            ("modelCatalogs", "modelCatalogs[id]?.groups[0].models[0].name = replacement"),
            ("modelCatalogs", "modelCatalogs.removeValue(forKey: id)"),
            ("pendingQuestionRequests", "pendingQuestionRequests.swapAt(0, 1)"),
            ("questionRequestStatuses", "consume(&questionRequestStatuses)"),
            ("sessions", "consume(&sessions[0])"),
            ("sessions", "mutate(&sessions[0].title)"),
            ("modelCatalogs", "mutate(&self.modelCatalogs[id]!.groups[0])"),
            ("pendingModelsSessionId", "pendingModelsSessionId = rogueSession"),
            ("isPendingGlobalModelsRequest", "isPendingGlobalModelsRequest = true"),
            ("pendingModelSelectionSessionId", "pendingModelSelectionSessionId = rogueSession"),
            ("pendingPermissionOptionsSessionId", "pendingPermissionOptionsSessionId = rogueSession")
        ]
        for defect in defects {
            XCTAssertFalse(
                try stage9WriteMatches(for: defect.property, in: defect.source).isEmpty,
                "静态写边界门禁必须识别：\(defect.source)"
            )
        }
        XCTAssertTrue(
            try stage9WriteMatches(for: "sessions", in: "let count = sessions.count").isEmpty,
            "只读属性访问不得被误判为写入"
        )
    }

    func testStage9ProductSourcesKeepKMPAsTheOnlyBasicDomainWriter() throws {
        let repositoryRoot = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let productRoot = repositoryRoot.appendingPathComponent("DeepSeekHarnessMobile")
        let enumerator = try XCTUnwrap(
            FileManager.default.enumerator(
                at: productRoot,
                includingPropertiesForKeys: nil
            )
        )
        let sourceURLs = enumerator.compactMap { item -> URL? in
            guard let url = item as? URL, url.pathExtension == "swift" else { return nil }
            return url
        }
        XCTAssertFalse(sourceURLs.isEmpty)

        let legacyReducerDefinitions = Set([
            productRoot.appendingPathComponent("Core/SessionListReducer.swift").standardizedFileURL.path,
            productRoot.appendingPathComponent("Core/QuestionReducer.swift").standardizedFileURL.path,
            productRoot.appendingPathComponent("Core/SessionControlReducer.swift").standardizedFileURL.path
        ])
        let nonReducerSources = sourceURLs.filter {
            !legacyReducerDefinitions.contains($0.standardizedFileURL.path)
        }
        for reducerSymbol in [
            "SessionListReducer",
            "QuestionReducer",
            "SessionControlReducer"
        ] {
            let referencePattern = try NSRegularExpression(
                pattern: "\\b\(NSRegularExpression.escapedPattern(for: reducerSymbol))\\b"
            )
            for sourceURL in nonReducerSources {
                let source = try String(contentsOf: sourceURL, encoding: .utf8)
                let fullRange = NSRange(source.startIndex..., in: source)
                XCTAssertNil(
                    referencePattern.firstMatch(in: source, range: fullRange),
                    "阶段 9 已收口，除保留的 Reducer 定义外，产品 Swift 源不得引用 \(reducerSymbol)：\(sourceURL.path)"
                )
            }
        }

        let identifierPattern = try NSRegularExpression(pattern: "\\b[A-Za-z_][A-Za-z0-9_]*\\b")
        let rollbackTerms = ["use", "enable", "disable", "prefer", "force", "flag", "fallback", "rollback", "legacy"]
        for sourceURL in sourceURLs {
            let source = try String(contentsOf: sourceURL, encoding: .utf8)
            let fullRange = NSRange(source.startIndex..., in: source)
            let suspiciousIdentifiers = identifierPattern.matches(in: source, range: fullRange).compactMap { match -> String? in
                guard let range = Range(match.range, in: source) else { return nil }
                let identifier = String(source[range])
                let normalized = identifier.lowercased()
                guard normalized.contains("kmp") || normalized.contains("swift") else { return nil }
                return rollbackTerms.contains(where: normalized.contains) ? identifier : nil
            }
            XCTAssertTrue(
                suspiciousIdentifiers.isEmpty,
                "阶段 9 人工验收后不得引入疑似 KMP/Swift 运行时切换标识符：\(sourceURL.path) \(suspiciousIdentifiers)"
            )
        }

        let appStoreSource = try String(
            contentsOf: productRoot.appendingPathComponent("Core/AppStore.swift"),
            encoding: .utf8
        )
        for requiredKMPWritePath in [
            "kmpSessionListStore.reduce",
            "kmpQuestionStore.reduce",
            "kmpSessionControlStore.reduce"
        ] {
            XCTAssertTrue(
                appStoreSource.contains(requiredKMPWritePath),
                "AppStore 必须继续通过 KMP 提交基础领域状态：\(requiredKMPWritePath)"
            )
        }

        let appStoreNSString = appStoreSource as NSString
        let allowedAssignmentScopes = try Dictionary(
            uniqueKeysWithValues: ["initialization", "session-list", "question", "session-control"].map { name in
                (
                    name,
                    try swiftAuditRange(
                        from: "// stage9-kmp-write-scope: \(name)-begin",
                        through: "// stage9-kmp-write-scope: \(name)-end",
                        in: appStoreNSString
                    )
                )
            }
        )
        let migratedProperties: [String: Set<String>] = [
            "selectedSessionId": ["initialization", "session-list"],
            "sessions": ["initialization", "session-list"],
            "archivedSessionIds": ["initialization", "session-list"],
            "pendingQuestionRequests": ["initialization", "question"],
            "questionRequestStatuses": ["initialization", "question"],
            "modelCatalogs": ["session-control"],
            "globalModelCatalog": ["session-control"],
            "sessionPermissions": ["session-control"],
            "contextSnapshots": ["session-control"],
            "sessionStatsSnapshots": ["session-control"],
            "sessionControlLoadingKinds": ["session-control"],
            "agentPresets": ["session-control"],
            "agentPresetsAuthorable": ["session-control"],
            "agentPresetsHasDocument": ["session-control"],
            "agentPresetDefault": ["session-control"],
            "permissionDefault": ["session-control"],
            "defaultModelSelection": ["session-control"],
            "defaultConfigurationLoadingKinds": ["session-control"],
            "pendingModelsSessionId": ["session-control"],
            "isPendingGlobalModelsRequest": ["session-control"],
            "pendingModelSelectionSessionId": ["session-control"],
            "pendingPermissionOptionsSessionId": ["session-control"]
        ]
        let privateRoutingProperties: Set<String> = [
            "pendingModelsSessionId",
            "isPendingGlobalModelsRequest",
            "pendingModelSelectionSessionId",
            "pendingPermissionOptionsSessionId"
        ]
        for (property, allowedScopes) in migratedProperties {
            if privateRoutingProperties.contains(property) {
                XCTAssertTrue(
                    appStoreSource.contains("private var \(property)"),
                    "已迁移 SessionControl 路由字段必须保持私有：\(property)"
                )
            } else {
                XCTAssertTrue(
                    appStoreSource.contains("@Published private(set) var \(property)"),
                    "已迁移 snapshot 属性必须使用 private(set) 限制外部写入：\(property)"
                )
            }
            let writeMatches = try stage9WriteMatches(for: property, in: appStoreSource)
            for match in writeMatches {
                let lineRange = appStoreNSString.lineRange(for: match.range)
                let line = appStoreNSString.substring(with: lineRange)
                if line.contains("@Published private(set) var \(property)")
                    || line.contains("private var \(property)") {
                    continue
                }
                let enclosingScopes = allowedAssignmentScopes.compactMap { name, range in
                    NSLocationInRange(match.range.location, range) ? name : nil
                }
                XCTAssertTrue(
                    enclosingScopes.contains(where: allowedScopes.contains),
                    "\(property) 只能在初始化或指定 KMP snapshot 发布函数写入，实际位置：\(line.trimmingCharacters(in: .whitespacesAndNewlines))"
                )
            }
        }
    }

    func testStage9ProductBasicDomainsUseIntentDispatchAndEventSubscription() throws {
        let repositoryRoot = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let appStoreSource = try String(
            contentsOf: repositoryRoot.appendingPathComponent("DeepSeekHarnessMobile/Core/AppStore.swift"),
            encoding: .utf8
        )
        let adapterSource = try String(
            contentsOf: repositoryRoot.appendingPathComponent("DeepSeekHarnessMobile/Core/KMPSharedAdapter.swift"),
            encoding: .utf8
        )
        for callback in [
            "kmpSessionListStore.onSnapshot",
            "kmpQuestionStore.onTransition",
            "kmpSessionControlStore.onTransition"
        ] {
            XCTAssertTrue(appStoreSource.contains(callback), "产品必须订阅 KMP Event：\(callback)")
        }
        for subscription in [
            "extension SharedSessionListStore: KMPSessionListEventBridging",
            "extension SharedQuestionStore: KMPQuestionEventBridging",
            "extension SharedSessionControlStore: KMPSessionControlEventBridging"
        ] {
            XCTAssertTrue(adapterSource.contains(subscription), "Adapter 必须接入可取消订阅：\(subscription)")
        }
        for forbidden in [
            "applySessionControlTransition(kmpSessionControlStore.reduce",
            "applySessionControlTransition(self.kmpSessionControlStore.reduce"
        ] {
            XCTAssertFalse(
                appStoreSource.contains(forbidden),
                "产品不得从 mutation 返回值发布状态或执行 effect：\(forbidden)"
            )
        }
        XCTAssertEqual(
            appStoreSource.components(separatedBy: "executeQuestionEffect(transition.effect)").count - 1,
            1,
            "Question effect 只能在 KMP Event 订阅回调中执行一次"
        )
        XCTAssertTrue(
            appStoreSource.contains("if !kmpSessionListStore.usesEventStream")
                && appStoreSource.contains("if !kmpQuestionStore.usesEventStream")
                && appStoreSource.contains("if !kmpSessionControlStore.usesEventStream"),
            "同步结果仅允许作为无订阅测试 bridge 的兼容路径"
        )
    }

    func testStage10ConversationProductPathUsesOnlyKMPProjection() throws {
        let repositoryRoot = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let productRoot = repositoryRoot.appendingPathComponent("DeepSeekHarnessMobile")
        let legacyDefinition = productRoot
            .appendingPathComponent("Core/ConversationProjection.swift")
            .standardizedFileURL.path
        let legacyHistoryDefinition = productRoot
            .appendingPathComponent("Core/HistoryReducer.swift")
            .standardizedFileURL.path
        let enumerator = try XCTUnwrap(FileManager.default.enumerator(
            at: productRoot,
            includingPropertiesForKeys: nil
        ))
        let productSources = enumerator.compactMap { item -> URL? in
            guard let url = item as? URL,
                  url.pathExtension == "swift",
                  url.standardizedFileURL.path != legacyDefinition,
                  url.standardizedFileURL.path != legacyHistoryDefinition else { return nil }
            return url
        }
        for symbol in [
            "ConversationProjector", "ConversationHistoryRebase", "ConversationItem.fold",
            "HistoryReducer", "HistoryEventMerger"
        ] {
            for sourceURL in productSources {
                let source = try String(contentsOf: sourceURL, encoding: .utf8)
                XCTAssertFalse(
                    source.contains(symbol),
                    "阶段 10.2 后产品 Swift 不得恢复 Conversation 领域投影：\(symbol) @ \(sourceURL.path)"
                )
            }
        }

        let appStoreSource = try String(
            contentsOf: productRoot.appendingPathComponent("Core/AppStore.swift"),
            encoding: .utf8
        )
        for required in [
            "kmpConversationStore.onChange",
            "kmpConversationStore.receive(record)",
            "kmpConversationStore.replace(sessionID:",
            "kmpTrajectoryStore.onChange",
            "kmpTrajectoryStore.receive(records)",
            "kmpTrajectoryStore.replace(sessionID:",
            "kmpHistoryStore.onChange",
            "kmpHistoryStore.receive(record)",
            "kmpHistoryStore.pageReceived("
        ] {
            XCTAssertTrue(appStoreSource.contains(required), "Conversation 产品路径缺少 KMP Intent/Event：\(required)")
        }
        XCTAssertFalse(
            appStoreSource.contains("conversationProjectors"),
            "AppStore 不得继续持有 Swift Conversation projector 状态"
        )
        let trajectoryViewSource = try String(
            contentsOf: productRoot.appendingPathComponent("Views/TrajectoryView.swift"),
            encoding: .utf8
        )
        XCTAssertFalse(
            trajectoryViewSource.contains("TrajectoryProjection.make("),
            "TrajectoryView 不得继续执行 Swift Trajectory 业务投影"
        )

        let appStoreNSString = appStoreSource as NSString
        let historyPublishScope = try swiftAuditRange(
            from: "// stage10-kmp-write-scope: history-begin",
            through: "// stage10-kmp-write-scope: history-end",
            in: appStoreNSString
        )
        for property in [
            "historyHasMore", "historyLoadingSessionIds",
            "historyLoadingOlderSessionIds", "historyLoadProgress"
        ] {
            XCTAssertTrue(
                appStoreSource.contains("@Published private(set) var \(property)"),
                "History UI 镜像必须对 AppStore 外部只读：\(property)"
            )
            let writes = try stage9WriteMatches(for: property, in: appStoreSource)
            XCTAssertFalse(writes.isEmpty, "History UI 镜像必须由 KMP event 发布：\(property)")
            XCTAssertTrue(
                writes.allSatisfy { NSLocationInRange($0.range.location, historyPublishScope) },
                "History UI 镜像只允许在 KMP History change 发布块内写入：\(property)"
            )
        }
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

    func testGatewayFrameRouterBuildsQuestionPayloadAndRejectsMalformedRequest() throws {
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
        guard case .question(.requested(let request, let sessionID, let preview, let replay)) =
                GatewayFrameRouter.route(valid, context: context) else {
            return XCTFail("有效问题应生成 requested action")
        }
        XCTAssertEqual(sessionID, "s1")
        XCTAssertEqual(preview, "继续？")
        XCTAssertTrue(replay)
        XCTAssertEqual(request.rpcId, "rpc-1")

        let invalid = try GatewayWireDecoder.decode(Data(
            #"{"kind":"question-requested","sessionId":"s1","questions":[]}"#.utf8
        ))
        guard case .question(.invalidRequest(let sessionID)) =
                GatewayFrameRouter.route(invalid, context: context) else {
            return XCTFail("缺少 rpcId 的问题必须显式路由为 invalidRequest")
        }
        XCTAssertEqual(sessionID, "s1")
    }

    func testGatewayFrameRouterPreservesMissingSessionForKMPRequestCorrelation() throws {
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
            return XCTFail("select-model 应保留网关原始 target")
        }
        XCTAssertNil(sessionID)
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

    @MainActor
    func testQuestionBackgroundAllowanceDoesNotEndAnotherTurnAndAcceptedRestoresTurn() {
        let application = BackgroundTaskApplicationSpy()
        let controller = AgentBackgroundExecutionController(application: application)

        controller.begin(sessionID: "s1", startsNewTurn: true)
        controller.beginQuestionAnswer(rpcID: "rpc-1", sessionID: "s1")
        controller.releaseQuestionAnswer(rpcID: "rpc-1")

        XCTAssertTrue(controller.isAgentWorkActive)
        XCTAssertEqual(controller.outstandingTurns, 1)
        XCTAssertTrue(controller.questionAllowanceSessionIDs.isEmpty)
        XCTAssertEqual(application.endedIdentifiers.count, 0)

        controller.turnEnded(sessionID: "s1")
        XCTAssertFalse(controller.isAgentWorkActive)
        XCTAssertEqual(application.endedIdentifiers.count, 1)

        controller.beginQuestionAnswer(rpcID: "rpc-restored", sessionID: "s2")
        XCTAssertEqual(controller.outstandingTurns, 0)
        controller.questionAnswerAccepted(rpcID: "rpc-restored")
        XCTAssertEqual(controller.outstandingTurns, 1)
        XCTAssertTrue(controller.questionAllowanceSessionIDs.isEmpty)
        XCTAssertTrue(controller.keepsConnectionAlive)

        controller.turnEnded(sessionID: "s2")
        XCTAssertFalse(controller.isAgentWorkActive)
        XCTAssertEqual(application.endedIdentifiers.count, 2)
    }

    @MainActor
    func testBackgroundExecutionTracksTurnsAndAcceptedQuestionsPerSession() {
        let application = BackgroundTaskApplicationSpy()
        let controller = AgentBackgroundExecutionController(application: application)

        controller.begin(sessionID: "s1", startsNewTurn: true)
        controller.beginQuestionAnswer(rpcID: "rpc-s2-a", sessionID: "s2")
        controller.beginQuestionAnswer(rpcID: "rpc-s2-b", sessionID: "s2")
        controller.questionAnswerAccepted(rpcID: "rpc-s2-a")
        controller.questionAnswerAccepted(rpcID: "rpc-s2-b")

        XCTAssertEqual(controller.outstandingTurnsBySessionID, ["s1": 1, "s2": 1])
        XCTAssertEqual(controller.outstandingTurns, 2)
        XCTAssertNil(controller.sessionID)
        XCTAssertEqual(application.beginCallCount, 1)

        controller.turnEnded(sessionID: "s1")
        XCTAssertEqual(controller.outstandingTurnsBySessionID, ["s2": 1])
        XCTAssertTrue(controller.keepsConnectionAlive)
        XCTAssertEqual(application.endedIdentifiers.count, 0)

        controller.turnEnded(sessionID: "s2")
        XCTAssertFalse(controller.isAgentWorkActive)
        XCTAssertEqual(application.endedIdentifiers.count, 1)
    }

    @MainActor
    func testBackgroundExecutionReleasesOnlyTargetSessionAllowancesAndCancelClearsAll() {
        let application = BackgroundTaskApplicationSpy()
        let controller = AgentBackgroundExecutionController(application: application)

        controller.beginQuestionAnswer(rpcID: "rpc-s1-a", sessionID: "s1")
        controller.beginQuestionAnswer(rpcID: "rpc-s1-b", sessionID: "s1")
        controller.beginQuestionAnswer(rpcID: "rpc-s2", sessionID: "s2")
        controller.releaseQuestionAnswers(sessionID: "s1")

        XCTAssertEqual(controller.questionAllowanceSessionIDs, ["rpc-s2": "s2"])
        XCTAssertTrue(controller.keepsConnectionAlive)
        XCTAssertEqual(application.endedIdentifiers.count, 0)

        controller.cancel()
        XCTAssertTrue(controller.outstandingTurnsBySessionID.isEmpty)
        XCTAssertEqual(controller.unassociatedOutstandingTurns, 0)
        XCTAssertTrue(controller.questionAllowanceSessionIDs.isEmpty)
        XCTAssertFalse(controller.keepsConnectionAlive)
        XCTAssertEqual(application.endedIdentifiers.count, 1)
    }

    @MainActor
    func testBackgroundTaskExpirationClearsAllInternalActivity() async {
        let application = BackgroundTaskApplicationSpy()
        let controller = AgentBackgroundExecutionController(application: application)
        var expirationCount = 0
        controller.onBackgroundAllowanceExpired = { expirationCount += 1 }

        controller.begin(sessionID: "s1", startsNewTurn: true)
        controller.beginQuestionAnswer(rpcID: "rpc-1", sessionID: "s1")
        controller.applicationDidEnterBackground()
        application.expirationHandler?()
        await Task.yield()

        XCTAssertFalse(controller.isAgentWorkActive)
        XCTAssertFalse(controller.keepsConnectionAlive)
        XCTAssertEqual(controller.outstandingTurns, 0)
        XCTAssertTrue(controller.outstandingTurnsBySessionID.isEmpty)
        XCTAssertEqual(controller.unassociatedOutstandingTurns, 0)
        XCTAssertNil(controller.sessionID)
        XCTAssertTrue(controller.questionAllowanceSessionIDs.isEmpty)
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

    @MainActor
    func testKMPSessionListAdapterPublishesChangedSnapshotThroughEventStream() throws {
        let adapter = KMPSessionListStoreAdapter(sessions: [])
        var snapshots: [KMPSessionListSnapshot] = []
        adapter.onSnapshot = { snapshot, error in
            XCTAssertNil(error)
            if let snapshot { snapshots.append(snapshot) }
        }

        _ = try adapter.reduce(
            .knownSessionAdded("session-event"),
            now: Date(timeIntervalSince1970: 100)
        )
        _ = try adapter.reduce(
            .knownSessionAdded("session-event"),
            now: Date(timeIntervalSince1970: 100)
        )

        XCTAssertTrue(adapter.usesEventStream)
        XCTAssertEqual(snapshots.count, 1)
        XCTAssertEqual(snapshots.first?.sessions.first?.id, "session-event")
    }

    @MainActor
    func testKMPSessionListAdapterMapsPersistenceAndOwnsSessionStateTransitions() throws {
        let persisted = SessionSummary(
            id: "existing",
            title: "旧标题",
            lastActivity: Date(timeIntervalSince1970: 10),
            isRunning: false,
            hasUnread: true,
            agentPreset: "keep"
        )
        let adapter = KMPSessionListStoreAdapter(
            sessions: [persisted],
            selectedSessionId: "selected"
        )
        XCTAssertNil(adapter.initializationError)
        XCTAssertNil(adapter.runtimeError)
        XCTAssertTrue(adapter.isOperational)
        XCTAssertEqual(adapter.snapshot.persistedSessions, [persisted])

        _ = try adapter.reduce(.setArchivedSessionIDs(["archived"]))
        let remote = [
            GatewaySessionSummary(
                sessionId: "existing",
                updatedAt: 2_000,
                running: true,
                blank: false,
                cwd: "/tmp/existing"
            ),
            GatewaySessionSummary(
                sessionId: "new",
                updatedAt: 3_000,
                running: false,
                blank: false,
                cwd: "/tmp/new",
                agentPreset: "standard"
            ),
            GatewaySessionSummary(
                sessionId: "archived",
                updatedAt: 4_000,
                running: false,
                blank: false,
                cwd: "/tmp/archived"
            )
        ]
        var snapshot = try adapter.reduce(.remoteSessionsReceived(remote))
        XCTAssertEqual(snapshot.persistedSessions.map(\.id), ["new", "existing"])
        XCTAssertEqual(snapshot.persistedSessions.last?.agentPreset, "keep")
        XCTAssertEqual(snapshot.persistedSessions.last?.isRunning, true)
        XCTAssertEqual(snapshot.persistedSessions.last?.hasUnread, true)

        snapshot = try adapter.reduce(.eventReceived(SessionEvent(
            sessionId: "new",
            seq: 1,
            time: 3_001,
            event: GatewayEvent(type: "turn/start")
        )), now: Date(timeIntervalSince1970: 9_999))
        XCTAssertEqual(snapshot.persistedSessions.first?.id, "new")
        XCTAssertEqual(snapshot.persistedSessions.first?.isRunning, true)
        XCTAssertEqual(snapshot.persistedSessions.first?.hasUnread, true)

        _ = try adapter.reduce(.select("new"))
        snapshot = try adapter.reduce(.markRead("new"))
        XCTAssertEqual(snapshot.selectedSessionId, "new")
        XCTAssertEqual(snapshot.persistedSessions.first?.hasUnread, false)

        snapshot = try adapter.reduce(
            .knownSessionAdded("known"),
            now: Date(timeIntervalSince1970: 8_000)
        )
        XCTAssertEqual(
            snapshot.persistedSessions.first { $0.id == "known" }?.lastActivity,
            Date(timeIntervalSince1970: 8_000)
        )
    }

    @MainActor
    func testKMPSessionListAdapterFailsClosedAfterRestoreFailure() throws {
        let persisted = SessionSummary(
            id: "persisted",
            title: "必须保留",
            lastActivity: Date(timeIntervalSince1970: 123),
            isRunning: true,
            hasUnread: true
        )
        let bridge = FailingRestoreSessionListBridge()
        let adapter = KMPSessionListStoreAdapter(sessions: [persisted], bridge: bridge)

        XCTAssertFalse(adapter.isInitialized)
        XCTAssertNotNil(adapter.initializationError)
        XCTAssertEqual(adapter.snapshot.persistedSessions, [persisted])

        XCTAssertThrowsError(
            try adapter.reduce(
                .knownSessionAdded("must-not-reduce"),
                now: Date(timeIntervalSince1970: 9_999)
            )
        ) { error in
            guard case KMPSessionListStoreError.initializationFailed = error else {
                return XCTFail("预期 initializationFailed，实际为 \(error)")
            }
        }
        XCTAssertEqual(bridge.nonRestoreCallCount, 0)
        XCTAssertEqual(adapter.snapshot.persistedSessions, [persisted])
    }

    @MainActor
    func testKMPSessionListAdapterFailsClosedAfterMalformedRuntimeSnapshot() throws {
        let persisted = SessionSummary(
            id: "persisted",
            title: "必须保留",
            lastActivity: Date(timeIntervalSince1970: 123),
            isRunning: true,
            hasUnread: true
        )
        let bridge = MalformedMutationSessionListBridge()
        let adapter = KMPSessionListStoreAdapter(sessions: [persisted], bridge: bridge)

        XCTAssertTrue(adapter.isOperational)
        XCTAssertThrowsError(
            try adapter.reduce(
                .knownSessionAdded("hidden-kmp-session"),
                now: Date(timeIntervalSince1970: 9_999)
            )
        ) { error in
            guard case KMPSessionListStoreError.invalidSnapshot = error else {
                return XCTFail("预期 invalidSnapshot，实际为 \(error)")
            }
        }
        XCTAssertEqual(bridge.mutationCallCount, 1)
        XCTAssertNotNil(adapter.runtimeError)
        XCTAssertFalse(adapter.isOperational)
        XCTAssertEqual(adapter.snapshot.persistedSessions, [persisted])

        XCTAssertThrowsError(
            try adapter.reduce(
                .knownSessionAdded("must-not-reach-bridge"),
                now: Date(timeIntervalSince1970: 10_000)
            )
        ) { error in
            guard case KMPSessionListStoreError.runtimeFailed = error else {
                return XCTFail("预期 runtimeFailed，实际为 \(error)")
            }
        }
        XCTAssertEqual(bridge.mutationCallCount, 1)
        XCTAssertEqual(adapter.snapshot.persistedSessions, [persisted])
    }

    @MainActor
    func testAppStoreDoesNotPersistAfterMalformedKMPRuntimeSnapshot() {
        let persisted = SessionSummary(
            id: "persisted",
            title: "必须保留",
            lastActivity: Date(timeIntervalSince1970: 123),
            isRunning: false,
            hasUnread: false
        )
        let preferences = AppPreferencesSpy(
            endpoint: "wss://injected.example/ws/mobile",
            selectedWorkspaceID: nil,
            sessions: [persisted]
        )
        let bridge = MalformedMutationSessionListBridge()
        let store = AppStore(preferences: preferences, sessionListBridge: bridge)

        store.addKnownSession("hidden-kmp-session")

        XCTAssertEqual(store.sessions, [persisted])
        XCTAssertEqual(preferences.savedSessionSnapshots, [])
        XCTAssertEqual(bridge.mutationCallCount, 1)
        XCTAssertNotNil(store.lastError)

        store.addKnownSession("must-not-reach-bridge")

        XCTAssertEqual(store.sessions, [persisted])
        XCTAssertEqual(preferences.savedSessionSnapshots, [])
        XCTAssertEqual(bridge.mutationCallCount, 1)
    }

    @MainActor
    func testKMPQuestionAdapterPublishesStateAndEffectThroughEventStream() {
        let request = questionRequest()
        let answers = [
            GatewayQuestionAnswer(id: "direction", selected: ["架构"]),
            GatewayQuestionAnswer(id: "notes", selected: [], custom: "保持原生 UI")
        ]
        let adapter = KMPQuestionStoreAdapter()
        var pushed: [KMPQuestionTransition] = []
        adapter.onTransition = { pushed.append($0) }

        _ = adapter.reduce(.requestReceived(request))
        let returned = adapter.reduce(.submitAnswer(
            rpcID: request.rpcId,
            answers: answers,
            isConnected: true
        ))
        _ = adapter.reduce(.submitAnswer(
            rpcID: request.rpcId,
            answers: answers,
            isConnected: true
        ))

        XCTAssertTrue(adapter.usesEventStream)
        XCTAssertEqual(pushed.count, 2)
        XCTAssertEqual(pushed.last?.snapshot, returned.snapshot)
        XCTAssertEqual(pushed.last?.effect?.action, "answer")
    }

    @MainActor
    func testKMPQuestionAdapterMatchesSwiftFixtureAndEmitsEffectOnce() {
        let request = questionRequest()
        let answers = [
            GatewayQuestionAnswer(id: "direction", selected: ["架构"]),
            GatewayQuestionAnswer(id: "notes", selected: [], custom: "保持原生 UI")
        ]
        let adapter = KMPQuestionStoreAdapter()
        var swiftState = QuestionState()

        var transition = adapter.reduce(.requestReceived(request))
        QuestionReducer.reduce(state: &swiftState, action: .requestReceived(request))
        XCTAssertNil(transition.error)
        XCTAssertEqual(transition.snapshot.pendingRequests, swiftState.pendingRequests)
        XCTAssertEqual(transition.snapshot.platformStatuses, swiftState.requestStatuses)

        transition = adapter.reduce(.submitAnswer(
            rpcID: request.rpcId,
            answers: answers,
            isConnected: true
        ))
        QuestionReducer.reduce(
            state: &swiftState,
            action: .submit(request: request, submission: .answer(answers), isConnected: true)
        )
        XCTAssertEqual(transition.snapshot.platformStatuses, swiftState.requestStatuses)
        XCTAssertEqual(transition.effect?.action, "answer")
        XCTAssertEqual(transition.effect?.answers, answers)

        // 第二次相同 intent 不能再次产生网络 effect。
        transition = adapter.reduce(.submitAnswer(
            rpcID: request.rpcId,
            answers: answers,
            isConnected: true
        ))
        XCTAssertNil(transition.effect)

        transition = adapter.reduce(.responseReceived(
            rpcID: request.rpcId,
            action: .answer,
            accepted: true,
            reason: nil
        ))
        QuestionReducer.reduce(
            state: &swiftState,
            action: .responseReceived(rpcID: request.rpcId, action: .answer, accepted: true, reason: nil)
        )
        XCTAssertEqual(transition.snapshot.platformStatuses, swiftState.requestStatuses)

        var replay = request
        replay.replay = true
        transition = adapter.reduce(.requestReceived(replay))
        QuestionReducer.reduce(state: &swiftState, action: .requestReceived(replay))
        XCTAssertEqual(transition.snapshot.pendingRequests, swiftState.pendingRequests)
        XCTAssertEqual(transition.snapshot.platformStatuses, swiftState.requestStatuses)

        transition = adapter.reduce(.resolved(rpcID: request.rpcId))
        QuestionReducer.reduce(state: &swiftState, action: .resolved(rpcID: request.rpcId))
        XCTAssertEqual(transition.snapshot.pendingRequests, swiftState.pendingRequests)
        XCTAssertEqual(transition.snapshot.platformStatuses, swiftState.requestStatuses)
    }

    @MainActor
    func testKMPQuestionAdapterFailsClosedBeforeMalformedEffectCanExecute() {
        let bridge = MalformedMutationQuestionBridge()
        let adapter = KMPQuestionStoreAdapter(bridge: bridge)
        XCTAssertTrue(adapter.isOperational)

        let transition = adapter.reduce(.requestReceived(questionRequest()))
        XCTAssertNotNil(transition.error)
        XCTAssertNil(transition.effect)
        XCTAssertFalse(adapter.isOperational)
        XCTAssertEqual(bridge.mutationCallCount, 1)

        let second = adapter.reduce(.reset)
        XCTAssertNotNil(second.error)
        XCTAssertNil(second.effect)
        XCTAssertEqual(bridge.mutationCallCount, 1)
    }

    @MainActor
    func testAppStoreNeverExecutesSemanticallyMismatchedQuestionEffects() {
        let request = questionRequest()
        let answers = [
            GatewayQuestionAnswer(id: "direction", selected: ["架构"]),
            GatewayQuestionAnswer(id: "notes", selected: [], custom: "保持原生 UI")
        ]

        for mismatch in SemanticQuestionEffectMismatch.allCases {
            let bridge = SemanticMismatchQuestionBridge(request: request, mismatch: mismatch)
            let executor = GatewayQuestionEffectExecutorSpy()
            let backgroundApplication = BackgroundTaskApplicationSpy()
            let backgroundController = AgentBackgroundExecutionController(application: backgroundApplication)
            let store = AppStore(
                preferences: AppPreferencesSpy(
                    endpoint: "wss://injected.example/ws/mobile",
                    selectedWorkspaceID: nil,
                    sessions: []
                ),
                questionBridge: bridge,
                questionEffectExecutor: executor,
                backgroundExecutionController: backgroundController
            )

            store.answerQuestion(request, answers: answers)

            XCTAssertEqual(executor.answerCalls.count, 0, "\(mismatch)")
            XCTAssertEqual(executor.cancelCallCount, 0, "\(mismatch)")
            XCTAssertEqual(bridge.submitCallCount, 1, "\(mismatch)")
            XCTAssertNotNil(store.lastError, "\(mismatch)")
            XCTAssertFalse(backgroundController.isAgentWorkActive, "\(mismatch)")

            // 一次语义错配后永久 fail closed，后续 intent 不再进入 KMP bridge。
            store.answerQuestion(request, answers: answers)
            XCTAssertEqual(bridge.submitCallCount, 1, "\(mismatch)")
            XCTAssertEqual(executor.answerCalls.count, 0, "\(mismatch)")
        }
    }

    @MainActor
    func testAppStoreCoordinatesQuestionAllowanceAcrossTerminalRoutes() throws {
        func makeStore() -> (
            store: AppStore,
            controller: AgentBackgroundExecutionController,
            executor: GatewayQuestionEffectExecutorSpy,
            request: GatewayPendingQuestionRequest
        ) {
            let request = questionRequest()
            let controller = AgentBackgroundExecutionController(application: BackgroundTaskApplicationSpy())
            let executor = GatewayQuestionEffectExecutorSpy()
            let store = AppStore(
                preferences: AppPreferencesSpy(
                    endpoint: "wss://injected.example/ws/mobile",
                    selectedWorkspaceID: nil,
                    sessions: []
                ),
                questionBridge: QuestionLifecycleBridge(request: request),
                questionEffectExecutor: executor,
                backgroundExecutionController: controller
            )
            store.answerQuestion(request, answers: [
                GatewayQuestionAnswer(id: "direction", selected: ["架构"]),
                GatewayQuestionAnswer(id: "notes", selected: [], custom: "保持原生 UI")
            ])
            XCTAssertEqual(executor.answerCalls.count, 1)
            XCTAssertEqual(controller.questionAllowanceSessionIDs[request.rpcId], request.sessionId)
            XCTAssertEqual(controller.outstandingTurns, 0)
            return (store, controller, executor, request)
        }

        do {
            let fixture = makeStore()
            fixture.store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(
                #"{"kind":"question-response","rpcId":"rpc-question","action":"answer","accepted":false,"reason":"not-pending"}"#.utf8
            )))
            XCTAssertFalse(fixture.controller.isAgentWorkActive)
            XCTAssertEqual(fixture.controller.outstandingTurns, 0)
        }

        do {
            let fixture = makeStore()
            fixture.store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(
                #"{"kind":"question-response","rpcId":"rpc-question","action":"answer","accepted":false,"reason":"bad-response"}"#.utf8
            )))
            XCTAssertFalse(fixture.controller.isAgentWorkActive)
            XCTAssertEqual(fixture.controller.outstandingTurns, 0)
        }

        do {
            let fixture = makeStore()
            fixture.store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(
                #"{"kind":"error","requestType":"question-answer","rpcId":"rpc-question","sessionId":"session-question","code":"failed"}"#.utf8
            )))
            XCTAssertFalse(fixture.controller.isAgentWorkActive)
            XCTAssertEqual(fixture.controller.outstandingTurns, 0)
        }

        do {
            let fixture = makeStore()
            fixture.store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(
                #"{"kind":"hello","protocol":3,"capabilities":[],"authenticated":true,"clients":1}"#.utf8
            )))
            XCTAssertFalse(fixture.controller.isAgentWorkActive)
            XCTAssertEqual(fixture.controller.outstandingTurns, 0)
        }

        do {
            let fixture = makeStore()
            fixture.store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(
                #"{"kind":"question-response","rpcId":"rpc-question","action":"answer","accepted":true}"#.utf8
            )))
            XCTAssertTrue(fixture.controller.questionAllowanceSessionIDs.isEmpty)
            XCTAssertEqual(fixture.controller.outstandingTurns, 1)

            fixture.store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(
                #"{"kind":"question-resolved","rpcId":"rpc-question","sessionId":"session-question","outcome":"answered"}"#.utf8
            )))
            XCTAssertEqual(fixture.controller.outstandingTurns, 1)
            fixture.controller.turnEnded(sessionID: fixture.request.sessionId)
            XCTAssertFalse(fixture.controller.isAgentWorkActive)
        }
    }

    @MainActor
    func testQuestionFailureWithoutRpcIDFailsAndReleasesAllRequestsInSession() throws {
        let controller = AgentBackgroundExecutionController(application: BackgroundTaskApplicationSpy())
        let store = AppStore(
            preferences: AppPreferencesSpy(
                endpoint: "wss://injected.example/ws/mobile",
                selectedWorkspaceID: nil,
                sessions: []
            ),
            backgroundExecutionController: controller
        )
        for (rpcID, sessionID) in [("rpc-s1-a", "s1"), ("rpc-s1-b", "s1"), ("rpc-s2", "s2")] {
            store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(
                #"{"kind":"question-requested","rpcId":"\#(rpcID)","sessionId":"\#(sessionID)","questions":[{"id":"q1","question":"继续？"}]}"#.utf8
            )))
            controller.beginQuestionAnswer(rpcID: rpcID, sessionID: sessionID)
        }

        store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(
            #"{"kind":"error","requestType":"question-answer","sessionId":"s1","code":"failed"}"#.utf8
        )))

        XCTAssertEqual(store.questionRequestStatuses["rpc-s1-a"], .rejected("failed"))
        XCTAssertEqual(store.questionRequestStatuses["rpc-s1-b"], .rejected("failed"))
        XCTAssertEqual(store.questionRequestStatuses["rpc-s2"], .idle)
        XCTAssertEqual(controller.questionAllowanceSessionIDs, ["rpc-s2": "s2"])
        XCTAssertEqual(store.pendingQuestionRequests.count, 3)
    }

    @MainActor
    func testAppStoreQuestionRoutePublishesKMPStateAndHelloResetsIt() throws {
        let store = AppStore(preferences: AppPreferencesSpy(
            endpoint: "wss://injected.example/ws/mobile",
            selectedWorkspaceID: nil,
            sessions: []
        ))
        let requested = try GatewayWireDecoder.decode(Data(
            #"{"kind":"question-requested","rpcId":"rpc-app","sessionId":"s-app","questions":[{"id":"q1","question":"继续？","options":[{"label":"是"}]}]}"#.utf8
        ))
        store.gateway.onFrame?(requested)
        let request = try XCTUnwrap(store.pendingQuestionRequests.first)
        XCTAssertEqual(store.questionRequestStatuses[request.rpcId], .idle)

        store.answerQuestion(request, answers: [GatewayQuestionAnswer(id: "q1", selected: ["是"])])
        XCTAssertEqual(
            store.questionRequestStatuses[request.rpcId],
            .rejected(String(localized: "q.rejected.ws.disconnected.answer", defaultValue: "WebSocket 已断开，重连后再提交答案。"))
        )

        let notPending = try GatewayWireDecoder.decode(Data(
            #"{"kind":"question-response","rpcId":"rpc-app","action":"answer","accepted":false,"reason":"not-pending"}"#.utf8
        ))
        store.gateway.onFrame?(notPending)
        XCTAssertTrue(store.pendingQuestionRequests.isEmpty)
        XCTAssertNil(store.questionRequestStatuses[request.rpcId])

        store.gateway.onFrame?(requested)
        let hello = try GatewayWireDecoder.decode(Data(
            #"{"kind":"hello","protocol":3,"capabilities":[],"authenticated":true,"clients":1}"#.utf8
        ))
        store.gateway.onFrame?(hello)
        XCTAssertTrue(store.pendingQuestionRequests.isEmpty)
        XCTAssertTrue(store.questionRequestStatuses.isEmpty)
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
    func testKMPSessionControlAdapterPublishesMutationThroughEventStream() {
        let adapter = KMPSessionControlStoreAdapter()
        var pushed: [KMPSessionControlTransition] = []
        adapter.onTransition = { pushed.append($0) }

        let returned = adapter.reduce(.requestModels(
            sessionID: "session-event",
            isConnected: true
        ))

        XCTAssertTrue(adapter.usesEventStream)
        XCTAssertEqual(adapter.lastEventSequence, 1)
        XCTAssertEqual(pushed.count, 1)
        XCTAssertEqual(pushed.first?.snapshot, returned.snapshot)
        XCTAssertEqual(pushed.first?.effects.first?.sessionId, "session-event")

        _ = adapter.reduce(.requestModels(sessionID: "session-event", isConnected: true))
        XCTAssertEqual(pushed.count, 1, "unchanged intent 不得重复推送 UI event")
    }

    @MainActor
    func testKMPPushAdaptersFailClosedBeforePublishingDuplicateSequence() {
        let sessionList = KMPSessionListStoreAdapter(sessions: [])
        let question = KMPQuestionStoreAdapter()
        let control = KMPSessionControlStoreAdapter()

        sessionList.receive(SharedMviEvent(
            schema: 2, sequence: sessionList.lastEventSequence ?? 0,
            transactionId: "duplicate-list", domain: "session-list", kind: "transition",
            statePayloadJson: nil, effectsJson: "[]", metadataJson: nil,
            errorCode: nil, errorMessage: nil
        ))
        question.receive(SharedMviEvent(
            schema: 2, sequence: question.lastEventSequence ?? 0,
            transactionId: "duplicate-question", domain: "question", kind: "transition",
            statePayloadJson: nil, effectsJson: "[]", metadataJson: nil,
            errorCode: nil, errorMessage: nil
        ))
        control.receive(SharedMviEvent(
            schema: 2, sequence: control.lastEventSequence ?? 0,
            transactionId: "duplicate-control", domain: "session-control", kind: "transition",
            statePayloadJson: nil, effectsJson: "[]", metadataJson: nil,
            errorCode: nil, errorMessage: nil
        ))

        XCTAssertFalse(sessionList.isOperational)
        XCTAssertFalse(question.isOperational)
        XCTAssertFalse(control.isOperational)
    }

    @MainActor
    func testKMPSessionControlAdapterMatchesSwiftFixtureAndEmitsRequestEffectOnce() {
        let adapter = KMPSessionControlStoreAdapter()
        var swiftState = SessionControlState()

        var transition = adapter.reduce(.requestModels(sessionID: "session-1", isConnected: true))
        XCTAssertNil(transition.error)
        XCTAssertEqual(transition.effects.count, 1)
        XCTAssertEqual(transition.effects.first?.kind, "models")
        XCTAssertEqual(transition.effects.first?.sessionId, "session-1")
        XCTAssertEqual(
            transition.snapshot.requestTokens["models"],
            transition.effects.first?.requestToken
        )

        // 尚未完成的同 kind 请求不得再次生成网络 I/O。
        transition = adapter.reduce(.requestModels(sessionID: "session-2", isConnected: true))
        XCTAssertNil(transition.error)
        XCTAssertTrue(transition.effects.isEmpty)
        XCTAssertEqual(transition.snapshot.pendingModelsSessionId, "session-1")
        XCTAssertEqual(transition.snapshot.queuedRequestTargets["models"]?.sessionId, "session-2")

        let selected = GatewayModelSelection(provider: "openai", model: "gpt-5", reasoningEffort: "high")
        let modelsAction = SessionControlAction.modelsReceived(
            sessionID: "session-1",
            current: selected,
            routable: true,
            groups: [],
            isGlobalRequest: false
        )
        transition = adapter.reduce(.action(modelsAction))
        SessionControlReducer.reduce(state: &swiftState, action: modelsAction)
        XCTAssertEqual(transition.snapshot.modelCatalogs, swiftState.modelCatalogs)
        XCTAssertEqual(transition.effects.first?.sessionId, "session-2")
        XCTAssertEqual(transition.snapshot.pendingModelsSessionId, "session-2")
        XCTAssertFalse(transition.snapshot.explicitSessionRequiredKinds.contains("models"))

        transition = adapter.reduce(.action(.modelsReceived(
            sessionID: "session-2",
            current: nil,
            routable: true,
            groups: [],
            isGlobalRequest: false
        )))
        XCTAssertTrue(transition.snapshot.loadingKinds.isEmpty)
        XCTAssertNil(transition.snapshot.requestTokens["models"])
        XCTAssertNil(transition.snapshot.pendingModelsSessionId)

        let permissions = GatewaySessionPermissions(
            options: [
                GatewayPermissionOption(value: "read-only", name: "Read"),
                GatewayPermissionOption(value: "future", name: "Future")
            ],
            currentValue: "read-only"
        )
        let permissionsAction = SessionControlAction.permissionsReceived(
            sessionID: "session-1",
            permissions: permissions
        )
        _ = adapter.reduce(.requestPermissionOptions(sessionID: "session-1", isConnected: true))
        transition = adapter.reduce(.action(permissionsAction))
        SessionControlReducer.reduce(state: &swiftState, action: permissionsAction)
        XCTAssertEqual(transition.snapshot.sessionPermissions, swiftState.sessionPermissions)
        XCTAssertEqual(
            transition.snapshot.sessionPermissions["session-1"]?.options?.map(\.value),
            ["read-only"]
        )
    }

    @MainActor
    func testKMPSessionControlAdapterMergesHistoryAndRealtimePartialSnapshots() {
        let adapter = KMPSessionControlStoreAdapter()
        _ = adapter.reduce(.projection(.contextReceived(
            sessionID: "session-1",
            asOfSequence: 10,
            tokenUsage: GatewayTokenUsage(uncachedInputTokens: 12),
            pressure: nil,
            breakdown: GatewayContextBreakdown(systemTokens: 4)
        )))
        var transition = adapter.reduce(.projection(.contextReceived(
            sessionID: "session-1",
            asOfSequence: nil,
            tokenUsage: nil,
            pressure: GatewayContextPressure(pressureTokens: 30, contextWindow: 100),
            breakdown: nil
        )))
        XCTAssertEqual(transition.snapshot.contextSnapshots["session-1"]?.asOfSeq, 10)
        XCTAssertEqual(
            transition.snapshot.contextSnapshots["session-1"]?.tokenUsage?.uncachedInputTokens,
            12
        )
        XCTAssertEqual(transition.snapshot.contextSnapshots["session-1"]?.pressure?.pressureTokens, 30)
        XCTAssertEqual(transition.snapshot.contextSnapshots["session-1"]?.breakdown?.systemTokens, 4)

        _ = adapter.reduce(.projection(.statsReceived(
            sessionID: "session-1",
            asOfSequence: 20,
            stats: GatewaySessionStats(turns: 2, steps: 5),
            tokenUsageTotals: GatewaySessionTokenUsageTotals(inputTokens: 100),
            contextPressure: nil
        )))
        transition = adapter.reduce(.projection(.statsReceived(
            sessionID: "session-1",
            asOfSequence: nil,
            stats: nil,
            tokenUsageTotals: nil,
            contextPressure: GatewayContextPressure(projectedTokens: 80, contextWindow: 1_000)
        )))
        XCTAssertEqual(transition.snapshot.sessionStatsSnapshots["session-1"]?.stats?.turns, 2)
        XCTAssertEqual(
            transition.snapshot.sessionStatsSnapshots["session-1"]?.tokenUsage?.totals?.inputTokens,
            100
        )
        XCTAssertEqual(
            transition.snapshot.sessionStatsSnapshots["session-1"]?.contextPressure?.projectedTokens,
            80
        )

        let largeSequence = Int(Int32.max) + 42
        transition = adapter.reduce(.projection(.contextReceived(
            sessionID: "session-1",
            asOfSequence: largeSequence,
            tokenUsage: nil,
            pressure: nil,
            breakdown: nil
        )))
        XCTAssertEqual(transition.snapshot.contextSnapshots["session-1"]?.asOfSeq, largeSequence)
    }

    @MainActor
    func testKMPSessionControlPatchOnlyPublishesAffectedSessionAndSkipsUnchangedMutation() {
        let adapter = KMPSessionControlStoreAdapter()
        _ = adapter.reduce(.projection(.contextReceived(
            sessionID: "session-a",
            asOfSequence: 1,
            tokenUsage: GatewayTokenUsage(uncachedInputTokens: 1),
            pressure: nil,
            breakdown: nil
        )))
        _ = adapter.reduce(.projection(.contextReceived(
            sessionID: "session-b",
            asOfSequence: 2,
            tokenUsage: GatewayTokenUsage(uncachedInputTokens: 2),
            pressure: nil,
            breakdown: nil
        )))

        var transition = adapter.reduce(.projection(.contextReceived(
            sessionID: "session-a",
            asOfSequence: 3,
            tokenUsage: nil,
            pressure: GatewayContextPressure(pressureTokens: 30, contextWindow: 100),
            breakdown: nil
        )))
        XCTAssertNil(transition.error)
        XCTAssertEqual(
            Set(transition.patch?.contextSnapshotsUpsert.keys.map { $0 } ?? []),
            ["session-a"]
        )
        XCTAssertTrue(transition.patch?.sessionStatsSnapshotsUpsert.isEmpty == true)
        XCTAssertEqual(transition.snapshot.contextSnapshots["session-b"]?.asOfSeq, 2)

        transition = adapter.reduce(.projection(.contextReceived(
            sessionID: "session-a",
            asOfSequence: 3,
            tokenUsage: nil,
            pressure: GatewayContextPressure(pressureTokens: 30, contextWindow: 100),
            breakdown: nil
        )))
        XCTAssertNil(transition.error)
        XCTAssertTrue(transition.applied)
        XCTAssertFalse(transition.committed)
        XCTAssertNil(transition.patch)
    }

    @MainActor
    func testKMPSessionControlClearDrainsOldNilTerminalThenStartsQueuedTarget() {
        let adapter = KMPSessionControlStoreAdapter()
        let active = adapter.reduce(.requestModels(sessionID: "session-a", isConnected: true))
        _ = adapter.reduce(.requestModels(sessionID: "session-b", isConnected: true))

        let cleared = adapter.reduce(.clearSessionData(sessionID: "session-a"))
        XCTAssertNil(cleared.error)
        XCTAssertTrue(cleared.retiredRequestKinds.isEmpty)
        XCTAssertTrue(cleared.effects.isEmpty)
        XCTAssertEqual(active.effects.count, 1)
        XCTAssertEqual(cleared.snapshot.requestTokens["models"], active.effects.first?.requestToken)
        XCTAssertEqual(cleared.snapshot.activeRequestTargets["models"]?.sessionId, "session-a")
        XCTAssertEqual(cleared.snapshot.queuedRequestTargets["models"]?.sessionId, "session-b")
        XCTAssertTrue(cleared.snapshot.drainingRequestKinds.contains("models"))

        let drained = adapter.reduce(.action(.modelsReceived(
            sessionID: nil, current: nil, routable: true, groups: [], isGlobalRequest: false
        )))
        XCTAssertTrue(drained.applied)
        XCTAssertEqual(drained.effects.count, 1)
        XCTAssertEqual(drained.effects.first?.sessionId, "session-b")
        XCTAssertNil(drained.snapshot.modelCatalogs["session-a"])
        XCTAssertFalse(drained.snapshot.drainingRequestKinds.contains("models"))
        XCTAssertFalse(drained.snapshot.explicitSessionRequiredKinds.contains("models"))

        let completed = adapter.reduce(.action(.modelsReceived(
            sessionID: nil, current: nil, routable: true, groups: [], isGlobalRequest: false
        )))
        XCTAssertTrue(completed.applied)
        XCTAssertNil(completed.snapshot.activeRequestTargets["models"])
        XCTAssertNotNil(completed.snapshot.modelCatalogs["session-b"])
    }

    @MainActor
    func testKMPSessionControlPermissionOptionsNilTerminalUsesSameDrainBoundary() {
        let adapter = KMPSessionControlStoreAdapter()
        _ = adapter.reduce(.requestPermissionOptions(sessionID: "session-a", isConnected: true))
        _ = adapter.reduce(.requestPermissionOptions(sessionID: "session-b", isConnected: true))
        _ = adapter.reduce(.clearSessionData(sessionID: "session-a"))
        let permissions = GatewaySessionPermissions(
            options: [], currentValue: "read-only"
        )

        let drained = adapter.reduce(.action(.permissionsReceived(
            sessionID: nil, permissions: permissions
        )))
        XCTAssertEqual(drained.effects.first?.sessionId, "session-b")
        XCTAssertNil(drained.snapshot.sessionPermissions["session-a"])
        let completed = adapter.reduce(.action(.permissionsReceived(
            sessionID: nil, permissions: permissions
        )))
        XCTAssertTrue(completed.applied)
        XCTAssertEqual(completed.snapshot.sessionPermissions["session-b"]?.currentValue, "read-only")
    }

    @MainActor
    func testKMPSessionControlDrainingTimeoutQuarantinesWithoutQueuedIO() {
        let adapter = KMPSessionControlStoreAdapter()
        let active = adapter.reduce(.requestModels(sessionID: "session-a", isConnected: true))
        _ = adapter.reduce(.requestModels(sessionID: "session-b", isConnected: true))
        _ = adapter.reduce(.clearSessionData(sessionID: "session-a"))
        let token = active.effects.first!.requestToken

        let timedOut = adapter.reduce(.requestTimedOut(
            kind: "models", isDefault: false, requestToken: token
        ))
        XCTAssertNil(timedOut.error)
        XCTAssertTrue(timedOut.effects.isEmpty)
        XCTAssertNil(timedOut.snapshot.activeRequestTargets["models"])
        XCTAssertNil(timedOut.snapshot.queuedRequestTargets["models"])
        XCTAssertTrue(timedOut.snapshot.quarantinedRequestKinds.contains("models"))

        for sessionID in [nil, "session-a"] as [String?] {
            let late = adapter.reduce(.action(.modelsReceived(
                sessionID: sessionID, current: nil, routable: true, groups: [], isGlobalRequest: false
            )))
            XCTAssertFalse(late.applied)
            XCTAssertNil(late.snapshot.modelCatalogs["session-a"])
            XCTAssertTrue(late.effects.isEmpty)
        }
        let retry = adapter.reduce(.requestModels(sessionID: "session-b", isConnected: true))
        guard case .bridge(let code, _) = retry.error else {
            return XCTFail("quarantine 中不得发送 queued/retry 请求")
        }
        XCTAssertEqual(code, "request-quarantined")
        XCTAssertTrue(adapter.reduce(.requestsDisconnected).snapshot.quarantinedRequestKinds.isEmpty)
    }

    @MainActor
    func testKMPSessionControlBatchClearDropsAlsoClearedQueuedTargetWithoutEffect() {
        for sessionIDs in [Set(["session-a", "session-b"]), Set(["session-b", "session-a"])] {
            let adapter = KMPSessionControlStoreAdapter()
            _ = adapter.reduce(.requestModels(sessionID: "session-a", isConnected: true))
            _ = adapter.reduce(.requestModels(sessionID: "session-b", isConnected: true))

            let cleared = adapter.reduce(.clearSessionsData(sessionIDs: sessionIDs))
            XCTAssertNil(cleared.error)
            XCTAssertTrue(cleared.effects.isEmpty)
            XCTAssertEqual(cleared.snapshot.activeRequestTargets["models"]?.sessionId, "session-a")
            XCTAssertNil(cleared.snapshot.queuedRequestTargets["models"])
            XCTAssertTrue(cleared.snapshot.drainingRequestKinds.contains("models"))

            let drained = adapter.reduce(.action(.modelsReceived(
                sessionID: nil, current: nil, routable: true, groups: [], isGlobalRequest: false
            )))
            XCTAssertTrue(drained.effects.isEmpty)
            XCTAssertNil(drained.snapshot.activeRequestTargets["models"])
        }
    }

    @MainActor
    func testKMPSessionControlLegacyNoOpsDoNotRequestFullSnapshot() {
        let bridge = MalformedSessionControlBridge()
        let adapter = KMPSessionControlStoreAdapter(bridge: bridge)
        XCTAssertEqual(bridge.snapshotCallCount, 1)

        let resolved = adapter.reduce(.action(.modelSelectionResolved))
        let missingFinish = adapter.reduce(.action(.requestFinished("models")))
        XCTAssertNil(resolved.patch)
        XCTAssertNil(missingFinish.patch)
        XCTAssertFalse(resolved.committed)
        XCTAssertFalse(missingFinish.committed)
        XCTAssertEqual(bridge.snapshotCallCount, 1)
    }

    @MainActor
    func testKMPSessionControlPatchAppliesRemovalAndMalformedPatchFailsAtomically() {
        let adapter = KMPSessionControlStoreAdapter()
        _ = adapter.reduce(.projection(.contextReceived(
            sessionID: "session-a", asOfSequence: 1,
            tokenUsage: nil, pressure: nil, breakdown: nil
        )))
        _ = adapter.reduce(.projection(.contextReceived(
            sessionID: "session-b", asOfSequence: 2,
            tokenUsage: nil, pressure: nil, breakdown: nil
        )))
        let removed = adapter.reduce(.clearSessionData(sessionID: "session-a"))
        XCTAssertNil(removed.error)
        XCTAssertNil(removed.snapshot.contextSnapshots["session-a"])
        XCTAssertEqual(removed.snapshot.contextSnapshots["session-b"]?.asOfSeq, 2)
        XCTAssertEqual(removed.patch?.contextSnapshotsRemove, ["session-a"])

        var malformed = KMPSessionControlPatch.empty
        malformed.contextSnapshotsUpsert = ["session-b": GatewayContextSnapshot(asOfSeq: 9)]
        let malformedAdapter = KMPSessionControlStoreAdapter(
            bridge: PatchQueueSessionControlBridge(patches: [malformed])
        )
        let original = malformedAdapter.snapshot
        let failed = malformedAdapter.reduce(.projection(.contextReceived(
            sessionID: "session-a", asOfSequence: nil,
            tokenUsage: nil, pressure: nil, breakdown: nil
        )))
        guard case .invalidPatch = failed.error else {
            return XCTFail("跨 session 注入必须以 invalidPatch fail-closed")
        }
        XCTAssertFalse(malformedAdapter.isOperational)
        XCTAssertEqual(failed.snapshot, original)
        XCTAssertTrue(failed.effects.isEmpty)
    }

    @MainActor
    func testKMPSessionControlClearRejectsPatchThatOmitsExistingRemoval() {
        let adapter = KMPSessionControlStoreAdapter(
            bridge: ClearOmissionSessionControlBridge()
        )
        let original = adapter.snapshot

        let transition = adapter.reduce(.clearSessionData(sessionID: "session-a"))

        guard case .invalidPatch = transition.error else {
            return XCTFail("clear patch 遗漏既有 session 数据 removal 必须 fail-closed")
        }
        XCTAssertEqual(transition.snapshot, original)
        XCTAssertNotNil(transition.snapshot.contextSnapshots["session-a"])
        XCTAssertTrue(transition.effects.isEmpty)
        XCTAssertFalse(adapter.isOperational)
    }

    @MainActor
    func testKMPSessionControlDrainRejectsBusinessProjectionFromTombstoneResponse() {
        let adapter = KMPSessionControlStoreAdapter(
            bridge: DrainBusinessInjectionSessionControlBridge()
        )
        let original = adapter.snapshot

        let transition = adapter.reduce(.action(.modelsReceived(
            sessionID: nil, current: nil, routable: true, groups: [], isGlobalRequest: false
        )))

        guard case .invalidPatch = transition.error else {
            return XCTFail("drain tombstone 终态携带业务 upsert 必须 fail-closed")
        }
        XCTAssertEqual(transition.snapshot, original)
        XCTAssertNil(transition.snapshot.modelCatalogs["session-a"])
        XCTAssertTrue(transition.effects.isEmpty)
        XCTAssertFalse(adapter.isOperational)
    }

    @MainActor
    func testKMPSessionControlPatchRejectsUnknownSchemaAndUnknownFields() throws {
        func encoded(_ patch: KMPSessionControlPatch, mutate: (inout [String: Any]) -> Void) throws -> String {
            var object = try XCTUnwrap(
                JSONSerialization.jsonObject(with: JSONEncoder().encode(patch)) as? [String: Any]
            )
            mutate(&object)
            return String(decoding: try JSONSerialization.data(withJSONObject: object), as: UTF8.self)
        }
        var valid = KMPSessionControlPatch.empty
        valid.contextSnapshotsUpsert = ["session-a": GatewayContextSnapshot(asOfSeq: 1)]
        let payloads = [
            try encoded(valid) { $0["futureTopLevel"] = true },
            try encoded(valid) { object in
                var contexts = object["contextSnapshotsUpsert"] as! [String: Any]
                var context = contexts["session-a"] as! [String: Any]
                context["futureNested"] = 1
                contexts["session-a"] = context
                object["contextSnapshotsUpsert"] = contexts
            },
            try encoded({
                var patch = valid
                patch.schema = 3
                return patch
            }()) { _ in }
        ]

        for payload in payloads {
            let adapter = KMPSessionControlStoreAdapter(
                bridge: RawPatchSessionControlBridge(payload: payload)
            )
            let original = adapter.snapshot
            let transition = adapter.reduce(.projection(.contextReceived(
                sessionID: "session-a", asOfSequence: 1,
                tokenUsage: nil, pressure: nil, breakdown: nil
            )))
            guard case .invalidPatch = transition.error else {
                return XCTFail("未知 schema/字段必须以 invalidPatch fail-closed")
            }
            XCTAssertEqual(transition.snapshot, original)
            XCTAssertTrue(transition.effects.isEmpty)
            XCTAssertFalse(adapter.isOperational)
        }
    }

    @MainActor
    func testAppStoreArchiveClearsKMPControlStateAndRejectsLateResponse() throws {
        let bridge = SharedSessionControlStore()
        _ = bridge.mergeContextProjection(
            sessionId: "session-a", asOfSequence: KotlinLong(longLong: 1),
            tokenUsageJson: nil, pressureJson: nil, breakdownJson: nil
        )
        _ = bridge.requestModels(sessionId: "session-a", isConnected: true)
        let store = AppStore(
            preferences: AppPreferencesSpy(
                endpoint: "wss://injected.example/ws/mobile",
                selectedWorkspaceID: nil,
                sessions: [SessionSummary(
                    id: "session-a",
                    title: "A",
                    lastActivity: Date(timeIntervalSince1970: 1),
                    isRunning: false,
                    hasUnread: false
                )]
            ),
            sessionControlBridge: bridge
        )

        store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(
            #"{"kind":"workspaces","items":[],"archivedSessionIds":["session-a"]}"#.utf8
        )))
        var snapshot = try JSONDecoder().decode(
            KMPSessionControlSnapshot.self,
            from: Data(try XCTUnwrap(bridge.snapshot().snapshotJson).utf8)
        )
        XCTAssertNil(snapshot.contextSnapshots["session-a"])
        XCTAssertEqual(snapshot.activeRequestTargets["models"]?.sessionId, "session-a")
        XCTAssertNotNil(snapshot.requestTokens["models"])
        XCTAssertTrue(snapshot.drainingRequestKinds.contains("models"))

        _ = bridge.modelsReceived(
            sessionId: nil, currentJson: nil, routable: true,
            groupsJson: "[]", isGlobalRequest: false
        )
        snapshot = try JSONDecoder().decode(
            KMPSessionControlSnapshot.self,
            from: Data(try XCTUnwrap(bridge.snapshot().snapshotJson).utf8)
        )
        XCTAssertNil(snapshot.modelCatalogs["session-a"])
        XCTAssertNil(snapshot.activeRequestTargets["models"])
        XCTAssertFalse(snapshot.drainingRequestKinds.contains("models"))
    }

    @MainActor
    func testAppStoreBatchArchiveDrainsBeforeRoutingReplacementEffect() throws {
        let bridge = SharedSessionControlStore()
        _ = bridge.requestModels(sessionId: "session-a", isConnected: true)
        _ = bridge.requestModels(sessionId: "session-b", isConnected: true)
        let executor = SessionControlEffectExecutorSpy()
        let store = AppStore(
            preferences: AppPreferencesSpy(
                endpoint: "wss://injected.example/ws/mobile",
                selectedWorkspaceID: nil,
                sessions: [
                    SessionSummary(id: "session-a", title: "A", lastActivity: .now, isRunning: false, hasUnread: false),
                    SessionSummary(id: "session-b", title: "B", lastActivity: .now, isRunning: false, hasUnread: false)
                ]
            ),
            sessionControlBridge: bridge,
            sessionControlEffectExecutor: executor
        )

        store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(
            #"{"kind":"workspaces","items":[],"archivedSessionIds":["session-a"]}"#.utf8
        )))
        XCTAssertTrue(executor.modelsTargets.isEmpty)

        // A 的真实 nil-session 终态只解除 drain；随后才发送仍存活的 B。
        store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(
            #"{"kind":"models","groups":[],"routable":true}"#.utf8
        )))
        XCTAssertEqual(executor.modelsTargets, ["session-b"])
        XCTAssertNil(store.modelCatalogs["session-a"])

        store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(
            #"{"kind":"models","groups":[],"routable":true}"#.utf8
        )))
        XCTAssertNotNil(store.modelCatalogs["session-b"])
    }

    @MainActor
    func testAppStoreBatchArchiveDoesNotStartQueuedSessionAlsoInClearSet() throws {
        let bridge = SharedSessionControlStore()
        _ = bridge.requestModels(sessionId: "session-a", isConnected: true)
        _ = bridge.requestModels(sessionId: "session-b", isConnected: true)
        let executor = SessionControlEffectExecutorSpy()
        let store = AppStore(
            preferences: AppPreferencesSpy(
                endpoint: "wss://injected.example/ws/mobile",
                selectedWorkspaceID: nil,
                sessions: [
                    SessionSummary(id: "session-a", title: "A", lastActivity: .now, isRunning: false, hasUnread: false),
                    SessionSummary(id: "session-b", title: "B", lastActivity: .now, isRunning: false, hasUnread: false)
                ]
            ),
            sessionControlBridge: bridge,
            sessionControlEffectExecutor: executor
        )

        store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(
            #"{"kind":"workspaces","items":[],"archivedSessionIds":["session-b","session-a"]}"#.utf8
        )))
        XCTAssertTrue(executor.modelsTargets.isEmpty)
        store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(
            #"{"kind":"models","groups":[],"routable":true}"#.utf8
        )))
        XCTAssertTrue(executor.modelsTargets.isEmpty)
        XCTAssertNil(bridge.snapshot().snapshotJson.flatMap { Data($0.utf8) }.flatMap {
            try? JSONDecoder().decode(KMPSessionControlSnapshot.self, from: $0)
        }?.activeRequestTargets["models"])
    }

    @MainActor
    func testKMPSessionControlRepeatedLegacyResponsesRemainOperational() {
        let adapter = KMPSessionControlStoreAdapter()
        let permissions = GatewaySessionPermissions(
            options: [GatewayPermissionOption(value: "read-only", name: "Read")],
            currentValue: "read-only"
        )

        _ = adapter.reduce(.requestModels(sessionID: "session-1", isConnected: true))
        _ = adapter.reduce(.action(.modelsReceived(
            sessionID: nil,
            current: nil,
            routable: true,
            groups: [],
            isGlobalRequest: false
        )))
        var transition = adapter.reduce(.requestModels(sessionID: "session-1", isConnected: true))
        XCTAssertNil(transition.error)
        XCTAssertFalse(transition.snapshot.explicitSessionRequiredKinds.contains("models"))
        transition = adapter.reduce(.action(.modelsReceived(
            sessionID: nil,
            current: nil,
            routable: true,
            groups: [],
            isGlobalRequest: false
        )))
        XCTAssertNil(transition.error)

        _ = adapter.reduce(.requestPermissionOptions(sessionID: "session-1", isConnected: true))
        _ = adapter.reduce(.action(.permissionsReceived(sessionID: nil, permissions: permissions)))
        transition = adapter.reduce(.requestPermissionOptions(sessionID: "session-1", isConnected: true))
        XCTAssertNil(transition.error)
        XCTAssertFalse(transition.snapshot.explicitSessionRequiredKinds.contains("permission-options"))
        transition = adapter.reduce(.action(.permissionsReceived(sessionID: nil, permissions: permissions)))
        XCTAssertNil(transition.error)
        XCTAssertTrue(adapter.isOperational)
        XCTAssertFalse(transition.snapshot.loadingKinds.contains("permission-options"))
    }

    @MainActor
    func testKMPSessionControlTimeoutKeepsSnapshotInvariantValidAndAllowsRetry() {
        let adapter = KMPSessionControlStoreAdapter()
        _ = adapter.reduce(.requestModels(sessionID: "session-a", isConnected: true))
        _ = adapter.reduce(.action(.modelsReceived(
            sessionID: "session-a",
            current: nil,
            routable: true,
            groups: [],
            isGlobalRequest: false
        )))
        let started = adapter.reduce(.requestModels(sessionID: "session-b", isConnected: true))
        let token = try! XCTUnwrap(started.snapshot.requestTokens["models"])
        XCTAssertFalse(started.snapshot.explicitSessionRequiredKinds.contains("models"))

        let timedOut = adapter.reduce(.requestTimedOut(
            kind: "models",
            isDefault: false,
            requestToken: token
        ))
        XCTAssertNil(timedOut.error)
        XCTAssertTrue(adapter.isOperational)
        XCTAssertFalse(timedOut.snapshot.quarantinedRequestKinds.contains("models"))
        XCTAssertFalse(timedOut.snapshot.explicitSessionRequiredKinds.contains("models"))
        XCTAssertNil(timedOut.snapshot.activeRequestTargets["models"])

        let retried = adapter.reduce(.requestModels(sessionID: "session-b", isConnected: true))
        XCTAssertNil(retried.error)
        XCTAssertEqual(retried.effects.first?.sessionId, "session-b")

        let permissionStarted = adapter.reduce(
            .requestPermissionOptions(sessionID: "session-b", isConnected: true)
        )
        let permissionToken = try! XCTUnwrap(
            permissionStarted.snapshot.requestTokens["permission-options"]
        )
        let permissionTimedOut = adapter.reduce(.requestTimedOut(
            kind: "permission-options",
            isDefault: false,
            requestToken: permissionToken
        ))
        XCTAssertFalse(
            permissionTimedOut.snapshot.quarantinedRequestKinds.contains("permission-options")
        )
        XCTAssertEqual(
            adapter.reduce(.requestPermissionOptions(
                sessionID: "session-b",
                isConnected: true
            )).effects.first?.sessionId,
            "session-b"
        )
    }

    @MainActor
    func testKMPSessionControlAdapterPermanentlyFailsClosedAfterMalformedCommittedSnapshot() {
        let bridge = MalformedSessionControlBridge()
        let adapter = KMPSessionControlStoreAdapter(bridge: bridge)
        XCTAssertTrue(adapter.isOperational)

        var transition = adapter.reduce(.requestModels(sessionID: "session-1", isConnected: true))
        guard case .invalidPatch = transition.error else {
            return XCTFail("结构缺失的已提交 payload 必须报 invalidPatch")
        }
        XCTAssertTrue(transition.effects.isEmpty)
        XCTAssertFalse(adapter.isOperational)
        XCTAssertEqual(bridge.requestCallCount, 1)

        transition = adapter.reduce(.requestModels(sessionID: "session-2", isConnected: true))
        XCTAssertNotNil(transition.error)
        XCTAssertTrue(transition.effects.isEmpty)
        XCTAssertEqual(bridge.requestCallCount, 1)
    }

    @MainActor
    func testKMPSessionControlAdapterPermanentlyFailsClosedOnInconsistentResultSignals() {
        for defect in InvalidSessionControlResultBridge.Defect.allCases {
            let bridge = InvalidSessionControlResultBridge(defect: defect)
            let adapter = KMPSessionControlStoreAdapter(bridge: bridge)
            let original = adapter.snapshot

            var transition = adapter.reduce(.requestModels(sessionID: "session-1", isConnected: true))
            switch defect {
            case .uncommittedSnapshotMutation, .committedWithoutMutation:
                guard case .invalidPatch = transition.error else {
                    XCTFail("\(defect) 必须报 invalidPatch")
                    continue
                }
            case .effectWithoutApplied, .inconsistentCompletion:
                guard case .invalidSnapshot = transition.error else {
                    XCTFail("\(defect) 必须报 invalidSnapshot")
                    continue
                }
            }
            XCTAssertTrue(transition.effects.isEmpty, "\(defect)")
            XCTAssertEqual(transition.snapshot, original, "\(defect)")
            XCTAssertFalse(adapter.isOperational, "\(defect)")

            transition = adapter.reduce(.requestModels(sessionID: "session-2", isConnected: true))
            XCTAssertNotNil(transition.error, "\(defect)")
            XCTAssertTrue(transition.effects.isEmpty, "\(defect)")
            XCTAssertEqual(bridge.requestCallCount, 1, "\(defect)")
        }
    }

    @MainActor
    func testKMPSessionControlRejectsSemanticEffectBeforeAppStoreIO() {
        let executor = SessionControlEffectExecutorSpy()
        let appStore = AppStore(
            preferences: AppPreferencesSpy(
                endpoint: "wss://injected.example/ws/mobile",
                selectedWorkspaceID: nil,
                sessions: []
            ),
            sessionControlEffectExecutor: executor
        )

        let normal = KMPSessionControlStoreAdapter().reduce(
            .requestModels(sessionID: "session-1", isConnected: true)
        )
        XCTAssertNil(normal.error)
        normal.effects.forEach(appStore.executeSessionControlEffect)
        XCTAssertEqual(executor.modelsTargets, ["session-1"])

        let invalidAdapter = KMPSessionControlStoreAdapter(bridge: SemanticInvalidSessionControlEffectBridge())
        let invalid = invalidAdapter.reduce(.requestModels(sessionID: "session-1", isConnected: true))
        guard case .invalidEffect = invalid.error else {
            return XCTFail("语义不匹配 effect 必须报 invalidEffect")
        }
        XCTAssertTrue(invalid.effects.isEmpty)
        invalid.effects.forEach(appStore.executeSessionControlEffect)
        XCTAssertEqual(executor.modelsTargets, ["session-1"])
    }

    @MainActor
    func testKMPSessionControlRejectsIntentSmugglingMissingPayloadAndProjectionEffect() {
        var transition = KMPSessionControlStoreAdapter(
            bridge: IntentSmugglingSessionControlBridge()
        ).reduce(.requestModels(sessionID: "session-1", isConnected: true))
        guard case .invalidEffect = transition.error else {
            return XCTFail("跨 session 请求 effect 必须以 invalidEffect fail-closed")
        }
        XCTAssertTrue(transition.effects.isEmpty)

        let selection = GatewayModelSelection(provider: "openai", model: "gpt-5")
        transition = KMPSessionControlStoreAdapter(
            bridge: MissingPayloadSessionControlBridge()
        ).reduce(.selectModel(sessionID: "session-1", selection: selection, isConnected: true))
        guard case .invalidEffect = transition.error else {
            return XCTFail("缺失 intent payload 的 effect 必须以 invalidEffect fail-closed")
        }
        XCTAssertTrue(transition.effects.isEmpty)

        transition = KMPSessionControlStoreAdapter(
            bridge: ProjectionSmugglingSessionControlBridge()
        ).reduce(.projection(.contextReceived(
            sessionID: "session-1",
            asOfSequence: 1,
            tokenUsage: nil,
            pressure: nil,
            breakdown: nil
        )))
        guard case .invalidEffect = transition.error else {
            return XCTFail("projection 携带平台 effect 必须以 invalidEffect fail-closed")
        }
        XCTAssertTrue(transition.effects.isEmpty)
    }

    @MainActor
    func testKMPSessionControlRejectsCrossKindControlInjectionBeforePublishAndIO() {
        let bridge = ControlCrossKindSmugglingSessionControlBridge()
        let adapter = KMPSessionControlStoreAdapter(bridge: bridge)
        let original = adapter.snapshot
        let executor = SessionControlEffectExecutorSpy()

        let transition = adapter.reduce(.requestModels(sessionID: "session-1", isConnected: true))
        guard case .invalidPatch = transition.error else {
            return XCTFail("当前 models intent 不得修改 permission-options control 字段")
        }
        XCTAssertEqual(transition.snapshot, original)
        XCTAssertEqual(adapter.snapshot, original)
        XCTAssertTrue(transition.effects.isEmpty)
        transition.effects.forEach { effect in
            if effect.kind == "models" { executor.requestModels(sessionId: effect.sessionId) }
        }
        XCTAssertTrue(executor.modelsTargets.isEmpty)
        XCTAssertFalse(adapter.isOperational)
    }

    @MainActor
    func testNegativeControlResponseImmediatelyCompletesCurrentGeneration() throws {
        let bridge = SharedSessionControlStore()
        let selection = GatewayModelSelection(provider: "openai", model: "gpt-5")
        _ = bridge.selectModel(
            sessionId: "session-1",
            selectionJson: String(decoding: try JSONEncoder().encode(selection), as: UTF8.self),
            isConnected: true
        )
        let store = AppStore(
            preferences: AppPreferencesSpy(
                endpoint: "wss://injected.example/ws/mobile",
                selectedWorkspaceID: nil,
                sessions: []
            ),
            sessionControlBridge: bridge
        )
        XCTAssertTrue(store.sessionControlLoadingKinds.contains("select-model"))

        store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(
            #"{"kind":"select-model","sessionId":"session-1"}"#.utf8
        )))

        XCTAssertFalse(store.sessionControlLoadingKinds.contains("select-model"))
        let snapshot = try JSONDecoder().decode(
            KMPSessionControlSnapshot.self,
            from: Data(bridge.snapshot().snapshotJson!.utf8)
        )
        XCTAssertNil(snapshot.requestTokens["select-model"])
    }

    @MainActor
    func testHelloResetsPreviousConnectionGenerationBeforeRefresh() throws {
        let bridge = SharedSessionControlStore()
        let old = bridge.requestModels(sessionId: "old-session", isConnected: true)
        let oldToken = try JSONDecoder().decode(
            [KMPSessionControlEffect].self,
            from: Data(old.effectsJson.utf8)
        ).first!.requestToken
        let store = AppStore(
            preferences: AppPreferencesSpy(
                endpoint: "wss://injected.example/ws/mobile",
                selectedWorkspaceID: nil,
                sessions: []
            ),
            sessionControlBridge: bridge
        )

        store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(
            #"{"kind":"hello","protocol":3,"capabilities":[],"authenticated":true,"clients":1}"#.utf8
        )))

        let snapshot = try JSONDecoder().decode(
            KMPSessionControlSnapshot.self,
            from: Data(bridge.snapshot().snapshotJson!.utf8)
        )
        XCTAssertNotEqual(snapshot.requestTokens["models"], oldToken)
        XCTAssertNil(snapshot.activeRequestTargets["models"]?.sessionId)
        XCTAssertTrue(snapshot.previousCompletedRequestTargets.isEmpty)
    }

    @MainActor
    func testLegacyNilResponseFinishesOnlyTheCurrentNormalGeneration() throws {
        let bridge = SharedSessionControlStore()
        _ = bridge.requestModels(sessionId: "session-a", isConnected: true)
        _ = bridge.modelsReceived(
            sessionId: "session-a",
            currentJson: nil,
            routable: true,
            groupsJson: "[]",
            isGlobalRequest: false
        )
        let second = bridge.requestModels(sessionId: "session-b", isConnected: true)
        let secondToken = try JSONDecoder().decode(
            [KMPSessionControlEffect].self,
            from: Data(second.effectsJson.utf8)
        ).first!.requestToken
        let store = AppStore(
            preferences: AppPreferencesSpy(
                endpoint: "wss://injected.example/ws/mobile",
                selectedWorkspaceID: nil,
                sessions: []
            ),
            sessionControlBridge: bridge
        )

        // 显式回显的旧 session 不能结束当前 generation。
        store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(
            #"{"kind":"models","sessionId":"session-a","groups":[]}"#.utf8
        )))
        var snapshot = try JSONDecoder().decode(
            KMPSessionControlSnapshot.self,
            from: Data(bridge.snapshot().snapshotJson!.utf8)
        )
        XCTAssertEqual(snapshot.requestTokens["models"], secondToken)

        // 真实 Gateway 的 models 帧不回显 sessionId，绑定当前唯一 active request。
        store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(
            #"{"kind":"models","groups":[]}"#.utf8
        )))
        snapshot = try JSONDecoder().decode(
            KMPSessionControlSnapshot.self,
            from: Data(bridge.snapshot().snapshotJson!.utf8)
        )
        XCTAssertNil(snapshot.requestTokens["models"])
    }

    @MainActor
    func testCorrelatedNegativeDefaultResponseFinishesWithoutWaitingForTimeout() throws {
        let bridge = SharedSessionControlStore()
        _ = bridge.setDefault(target: "permission", value: "read-only", isConnected: true)
        _ = bridge.globalDefaultApplied(target: "permission", value: "read-only")
        _ = bridge.setDefault(target: "permission", value: "workspace-write", isConnected: true)
        let store = AppStore(
            preferences: AppPreferencesSpy(
                endpoint: "wss://injected.example/ws/mobile",
                selectedWorkspaceID: nil,
                sessions: []
            ),
            sessionControlBridge: bridge
        )
        XCTAssertTrue(store.defaultConfigurationLoadingKinds.contains("set-default"))

        store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(
            #"{"kind":"set-default","applied":false,"target":"permission","value":"workspace-write"}"#.utf8
        )))

        XCTAssertFalse(store.defaultConfigurationLoadingKinds.contains("set-default"))
        XCTAssertNotNil(store.lastError)
        XCTAssertEqual(store.protocolNotices.last?.isError, true)
        let snapshot = try JSONDecoder().decode(
            KMPSessionControlSnapshot.self,
            from: Data(bridge.snapshot().snapshotJson!.utf8)
        )
        XCTAssertNil(snapshot.requestTokens["set-default"])
    }

    @MainActor
    func testTokenlessNegativeDefaultResponsesFinishTheOnlyActiveGeneration() throws {
        let selection = GatewayModelSelection(provider: "openai", model: "gpt-5")
        let selectionJSON = String(decoding: try JSONEncoder().encode(selection), as: UTF8.self)

        let saveBridge = SharedSessionControlStore()
        _ = saveBridge.saveDefaultModel(selectionJson: selectionJSON, isConnected: true)
        _ = saveBridge.defaultModelSaved(selectionJson: selectionJSON)
        _ = saveBridge.saveDefaultModel(selectionJson: selectionJSON, isConnected: true)
        let saveStore = AppStore(
            preferences: AppPreferencesSpy(
                endpoint: "wss://injected.example/ws/mobile",
                selectedWorkspaceID: nil,
                sessions: []
            ),
            sessionControlBridge: saveBridge
        )
        saveStore.gateway.onFrame?(try GatewayWireDecoder.decode(Data(
            #"{"kind":"save-default-model"}"#.utf8
        )))
        XCTAssertFalse(saveStore.defaultConfigurationLoadingKinds.contains("save-default-model"))
        XCTAssertNotNil(saveStore.lastError)
        XCTAssertEqual(saveStore.protocolNotices.last?.isError, true)

        let defaultBridge = SharedSessionControlStore()
        _ = defaultBridge.setDefault(target: "permission", value: "read-only", isConnected: true)
        _ = defaultBridge.globalDefaultApplied(target: "permission", value: "read-only")
        _ = defaultBridge.setDefault(target: "permission", value: "read-only", isConnected: true)
        let defaultStore = AppStore(
            preferences: AppPreferencesSpy(
                endpoint: "wss://injected.example/ws/mobile",
                selectedWorkspaceID: nil,
                sessions: []
            ),
            sessionControlBridge: defaultBridge
        )
        defaultStore.gateway.onFrame?(try GatewayWireDecoder.decode(Data(
            #"{"kind":"set-default","applied":false,"target":"permission","value":"read-only"}"#.utf8
        )))
        XCTAssertFalse(defaultStore.defaultConfigurationLoadingKinds.contains("set-default"))
        XCTAssertNotNil(defaultStore.lastError)
        XCTAssertEqual(defaultStore.protocolNotices.last?.isError, true)
    }

    @MainActor
    func testAppStoreIgnoresUncorrelatedSessionControlResponses() throws {
        let executor = SessionControlEffectExecutorSpy()
        let store = AppStore(
            preferences: AppPreferencesSpy(
                endpoint: "wss://injected.example/ws/mobile",
                selectedWorkspaceID: nil,
                sessions: []
            ),
            sessionControlEffectExecutor: executor
        )
        let frames = [
            #"{"kind":"agent-presets","presets":[{"id":"standard","isDefault":true}],"authorable":true,"hasDocument":true}"#,
            #"{"kind":"defaults","agentPresetDefault":"standard","permissionDefault":"ask"}"#,
            #"{"kind":"models","sessionId":"session-1","current":{"provider":"openai","model":"gpt-5"},"routable":true,"groups":[]}"#,
            #"{"kind":"permission-options","sessionId":"session-1","sessionPermissions":{"options":[{"value":"read-only","name":"Read"},{"value":"future","name":"Future"}],"currentValue":"read-only"}}"#,
            #"{"kind":"context-usage","sessionId":"session-1","asOfSeq":10,"tokenUsage":{"uncachedInputTokens":12}}"#,
            #"{"kind":"session-stats","sessionId":"session-1","asOfSeq":20,"sessionStats":{"turns":2},"tokenUsage":{"totals":{"inputTokens":100}}}"#,
            #"{"kind":"set-default","applied":true,"target":"permission","value":"ask"}"#
        ]
        for json in frames {
            store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(json.utf8)))
        }

        XCTAssertTrue(store.agentPresets.isEmpty)
        XCTAssertNil(store.agentPresetDefault)
        XCTAssertNil(store.permissionDefault)
        XCTAssertTrue(store.modelCatalogs.isEmpty)
        XCTAssertTrue(store.sessionPermissions.isEmpty)
        XCTAssertTrue(store.contextSnapshots.isEmpty)
        XCTAssertTrue(store.sessionStatsSnapshots.isEmpty)
        XCTAssertEqual(executor.defaultsRequestCount, 0)
        XCTAssertTrue(store.protocolNotices.isEmpty)
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

@MainActor
private final class SessionControlEffectExecutorSpy: GatewaySessionControlEffectExecuting {
    private(set) var modelsTargets: [String?] = []
    private(set) var defaultsRequestCount = 0
    func requestModels(sessionId: String?) { modelsTargets.append(sessionId) }
    func requestPermissionOptions(sessionId: String?) {}
    func requestContextUsage(sessionId: String) {}
    func requestSessionStats(sessionId: String) {}
    func requestAgentPresets() {}
    func requestDefaults() { defaultsRequestCount += 1 }
    func requestDefaultModel() {}
    func selectModel(sessionId: String, provider: String, model: String, reasoningEffort: String?) {}
    func setPermission(sessionId: String, name: String) {}
    func saveDefaultModel(provider: String, model: String, reasoningEffort: String?) {}
    func setDefault(target: String, value: String) {}
}

private final class IntentSmugglingSessionControlBridge: MalformedSessionControlBridge {
    override func requestModels(sessionId: String?, isConnected: Bool) -> SharedSessionControlResult {
        forgedRequestResult(target: .init(kind: "models", isDefault: false, sessionId: "session-2"))
    }
}

private final class MissingPayloadSessionControlBridge: MalformedSessionControlBridge {
    override func selectModel(
        sessionId: String,
        selectionJson: String,
        isConnected: Bool
    ) -> SharedSessionControlResult {
        forgedRequestResult(
            target: .init(
                kind: "select-model",
                isDefault: false,
                sessionId: sessionId,
                provider: "openai",
                model: "gpt-5"
            ),
            effectTarget: .init(
                kind: "select-model",
                isDefault: false,
                sessionId: sessionId,
                provider: "openai",
                model: nil
            )
        )
    }
}

private final class ProjectionSmugglingSessionControlBridge: MalformedSessionControlBridge {
    override func mergeContextProjection(
        sessionId: String,
        asOfSequence: KotlinLong?,
        tokenUsageJson: String?,
        pressureJson: String?,
        breakdownJson: String?
    ) -> SharedSessionControlResult {
        forgedRequestResult(target: .init(kind: "context-usage", isDefault: false, sessionId: sessionId))
    }
}

private final class ControlCrossKindSmugglingSessionControlBridge: MalformedSessionControlBridge {
    override func requestModels(sessionId: String?, isConnected: Bool) -> SharedSessionControlResult {
        let models = KMPSessionControlRequestTarget(
            kind: "models", isDefault: false, sessionId: sessionId
        )
        let permissions = KMPSessionControlRequestTarget(
            kind: "permission-options", isDefault: false, sessionId: "session-2"
        )
        var snapshot = KMPSessionControlSnapshot.empty
        snapshot.loadingKinds = ["models", "permission-options"]
        snapshot.pendingModelsSessionId = sessionId
        snapshot.pendingPermissionOptionsSessionId = "session-2"
        snapshot.requestTokens = ["models": "models:1", "permission-options": "permission-options:1"]
        snapshot.activeRequestTargets = ["models": models, "permission-options": permissions]
        var patch = KMPSessionControlPatch.empty
        patch.control = snapshot.controlPatch
        let effect = KMPSessionControlEffect(
            kind: "models", requestKey: "models", requestToken: "models:1",
            sessionId: sessionId, provider: nil, model: nil, reasoningEffort: nil,
            target: nil, value: nil
        )
        return SharedSessionControlResult(
            snapshotJson: Self.encode(patch),
            effectsJson: Self.encode([effect]),
            errorCode: nil,
            errorMessage: nil,
            applied: true,
            committed: true,
            completedKind: nil,
            completedRequestToken: nil
        )
    }
}

private final class PatchQueueSessionControlBridge: MalformedSessionControlBridge {
    private var patches: [KMPSessionControlPatch]

    init(patches: [KMPSessionControlPatch]) {
        self.patches = patches
    }

    override func mergeContextProjection(
        sessionId: String,
        asOfSequence: KotlinLong?,
        tokenUsageJson: String?,
        pressureJson: String?,
        breakdownJson: String?
    ) -> SharedSessionControlResult {
        guard !patches.isEmpty else { return unimplemented() }
        return SharedSessionControlResult(
            snapshotJson: Self.encode(patches.removeFirst()),
            effectsJson: "[]",
            errorCode: nil,
            errorMessage: nil,
            applied: true,
            committed: true,
            completedKind: nil,
            completedRequestToken: nil
        )
    }
}

private final class ClearOmissionSessionControlBridge: MalformedSessionControlBridge {
    private let initial: KMPSessionControlSnapshot = {
        var snapshot = KMPSessionControlSnapshot.empty
        let target = KMPSessionControlRequestTarget(
            kind: "models", isDefault: false, sessionId: "session-a"
        )
        snapshot.contextSnapshots["session-a"] = GatewayContextSnapshot(asOfSeq: 1)
        snapshot.loadingKinds = ["models"]
        snapshot.pendingModelsSessionId = "session-a"
        snapshot.requestTokens = ["models": "models:1"]
        snapshot.activeRequestTargets = ["models": target]
        return snapshot
    }()

    override func snapshot() -> SharedSessionControlResult {
        SharedSessionControlResult(
            snapshotJson: MalformedSessionControlBridge.encode(initial),
            effectsJson: "[]", errorCode: nil, errorMessage: nil,
            applied: false, committed: false,
            completedKind: nil, completedRequestToken: nil
        )
    }

    override func clearSessionData(sessionId: String) -> SharedSessionControlResult {
        var next = initial
        next.drainingRequestKinds = ["models"]
        var patch = KMPSessionControlPatch.empty
        // 故意遗漏 contextSnapshotsRemove，模拟损坏/不兼容 KMP bridge。
        patch.control = next.controlPatch
        return SharedSessionControlResult(
            snapshotJson: MalformedSessionControlBridge.encode(patch),
            effectsJson: "[]", errorCode: nil, errorMessage: nil,
            applied: true, committed: true,
            completedKind: nil, completedRequestToken: nil
        )
    }
}

private final class DrainBusinessInjectionSessionControlBridge: MalformedSessionControlBridge {
    private let initial: KMPSessionControlSnapshot = {
        var snapshot = KMPSessionControlSnapshot.empty
        let active = KMPSessionControlRequestTarget(
            kind: "models", isDefault: false, sessionId: "session-a"
        )
        let queued = KMPSessionControlRequestTarget(
            kind: "models", isDefault: false, sessionId: "session-b"
        )
        snapshot.loadingKinds = ["models"]
        snapshot.pendingModelsSessionId = "session-a"
        snapshot.requestTokens = ["models": "models:1"]
        snapshot.activeRequestTargets = ["models": active]
        snapshot.queuedRequestTargets = ["models": queued]
        snapshot.drainingRequestKinds = ["models"]
        return snapshot
    }()

    override func snapshot() -> SharedSessionControlResult {
        SharedSessionControlResult(
            snapshotJson: MalformedSessionControlBridge.encode(initial),
            effectsJson: "[]", errorCode: nil, errorMessage: nil,
            applied: false, committed: false,
            completedKind: nil, completedRequestToken: nil
        )
    }

    override func modelsReceived(
        sessionId: String?, currentJson: String?, routable: Bool,
        groupsJson: String, isGlobalRequest: Bool
    ) -> SharedSessionControlResult {
        let replacement = KMPSessionControlRequestTarget(
            kind: "models", isDefault: false, sessionId: "session-b"
        )
        var next = KMPSessionControlSnapshot.empty
        next.loadingKinds = ["models"]
        next.pendingModelsSessionId = "session-b"
        next.requestTokens = ["models": "models:2"]
        next.activeRequestTargets = ["models": replacement]
        var patch = KMPSessionControlPatch.empty
        // 故意在 tombstone 终态重新注入已清理 A 的业务数据。
        patch.modelCatalogsUpsert["session-a"] = GatewayModelCatalog(
            current: nil, routable: true, groups: []
        )
        patch.control = next.controlPatch
        let effect = KMPSessionControlEffect(
            kind: "models", requestKey: "models", requestToken: "models:2",
            sessionId: "session-b", provider: nil, model: nil,
            reasoningEffort: nil, target: nil, value: nil
        )
        return SharedSessionControlResult(
            snapshotJson: MalformedSessionControlBridge.encode(patch),
            effectsJson: MalformedSessionControlBridge.encode([effect]),
            errorCode: nil, errorMessage: nil,
            applied: true, committed: true,
            completedKind: "models", completedRequestToken: "models:1"
        )
    }
}

private final class RawPatchSessionControlBridge: MalformedSessionControlBridge {
    private let payload: String

    init(payload: String) {
        self.payload = payload
    }

    override func mergeContextProjection(
        sessionId: String,
        asOfSequence: KotlinLong?,
        tokenUsageJson: String?,
        pressureJson: String?,
        breakdownJson: String?
    ) -> SharedSessionControlResult {
        SharedSessionControlResult(
            snapshotJson: payload,
            effectsJson: "[]",
            errorCode: nil,
            errorMessage: nil,
            applied: true,
            committed: true,
            completedKind: nil,
            completedRequestToken: nil
        )
    }
}

private final class SemanticInvalidSessionControlEffectBridge: MalformedSessionControlBridge {
    override func requestModels(sessionId: String?, isConnected: Bool) -> SharedSessionControlResult {
        requestCallCount += 1
        var snapshot = KMPSessionControlSnapshot.empty
        let target = KMPSessionControlRequestTarget(
            kind: "models",
            isDefault: false,
            sessionId: sessionId,
            provider: nil,
            model: nil,
            reasoningEffort: nil,
            target: nil,
            value: nil
        )
        snapshot.loadingKinds = ["models"]
        snapshot.pendingModelsSessionId = sessionId
        snapshot.requestTokens = ["models": "models:1"]
        snapshot.activeRequestTargets = ["models": target]
        var patch = KMPSessionControlPatch.empty
        patch.control = snapshot.controlPatch
        let effect = KMPSessionControlEffect(
            kind: "models",
            requestKey: "models",
            requestToken: "models:1",
            sessionId: "wrong-session",
            provider: nil,
            model: nil,
            reasoningEffort: nil,
            target: nil,
            value: nil
        )
        return SharedSessionControlResult(
            snapshotJson: Self.encode(patch),
            effectsJson: Self.encode([effect]),
            errorCode: nil,
            errorMessage: nil,
            applied: true,
            committed: true,
            completedKind: nil,
            completedRequestToken: nil
        )
    }
}

private final class InvalidSessionControlResultBridge: MalformedSessionControlBridge {
    enum Defect: CaseIterable {
        case uncommittedSnapshotMutation
        case effectWithoutApplied
        case committedWithoutMutation
        case inconsistentCompletion
    }

    private let defect: Defect

    init(defect: Defect) {
        self.defect = defect
    }

    override func requestModels(sessionId: String?, isConnected: Bool) -> SharedSessionControlResult {
        requestCallCount += 1
        let valid = forgedRequestResult(
            target: .init(kind: "models", isDefault: false, sessionId: sessionId)
        )
        switch defect {
        case .uncommittedSnapshotMutation:
            return SharedSessionControlResult(
                snapshotJson: valid.snapshotJson,
                effectsJson: "[]",
                errorCode: nil,
                errorMessage: nil,
                applied: true,
                committed: false,
                completedKind: nil,
                completedRequestToken: nil
            )
        case .effectWithoutApplied:
            return SharedSessionControlResult(
                snapshotJson: valid.snapshotJson,
                effectsJson: valid.effectsJson,
                errorCode: nil,
                errorMessage: nil,
                applied: false,
                committed: true,
                completedKind: nil,
                completedRequestToken: nil
            )
        case .committedWithoutMutation:
            return SharedSessionControlResult(
                snapshotJson: Self.encode(KMPSessionControlPatch.empty),
                effectsJson: "[]",
                errorCode: nil,
                errorMessage: nil,
                applied: true,
                committed: true,
                completedKind: nil,
                completedRequestToken: nil
            )
        case .inconsistentCompletion:
            return SharedSessionControlResult(
                snapshotJson: valid.snapshotJson,
                effectsJson: "[]",
                errorCode: nil,
                errorMessage: nil,
                applied: true,
                committed: true,
                completedKind: "models",
                completedRequestToken: "models:missing"
            )
        }
    }
}

private class MalformedSessionControlBridge: KMPSessionControlStoreBridging {
    fileprivate(set) var requestCallCount = 0
    fileprivate(set) var snapshotCallCount = 0

    fileprivate static func encode<T: Encodable>(_ value: T) -> String {
        String(decoding: try! JSONEncoder().encode(value), as: UTF8.self)
    }

    func snapshot() -> SharedSessionControlResult {
        snapshotCallCount += 1
        let json = try! JSONEncoder().encode(KMPSessionControlSnapshot.empty)
        return SharedSessionControlResult(
            snapshotJson: String(decoding: json, as: UTF8.self),
            effectsJson: "[]",
            errorCode: nil,
            errorMessage: nil,
            applied: false,
            committed: false,
            completedKind: nil,
            completedRequestToken: nil
        )
    }

    func requestModels(sessionId: String?, isConnected: Bool) -> SharedSessionControlResult {
        requestCallCount += 1
        return SharedSessionControlResult(
            snapshotJson: "{}",
            effectsJson: "[]",
            errorCode: nil,
            errorMessage: nil,
            applied: true,
            committed: true,
            completedKind: nil,
            completedRequestToken: nil
        )
    }

    func selectModel(
        sessionId: String,
        selectionJson: String,
        isConnected: Bool
    ) -> SharedSessionControlResult { unimplemented() }

    func clearSessionData(sessionId: String) -> SharedSessionControlResult { unimplemented() }

    func modelsReceived(
        sessionId: String?, currentJson: String?, routable: Bool,
        groupsJson: String, isGlobalRequest: Bool
    ) -> SharedSessionControlResult { unimplemented() }

    func mergeContextProjection(
        sessionId: String,
        asOfSequence: KotlinLong?,
        tokenUsageJson: String?,
        pressureJson: String?,
        breakdownJson: String?
    ) -> SharedSessionControlResult { unimplemented() }

    fileprivate func forgedRequestResult(
        target: KMPSessionControlRequestTarget,
        effectTarget: KMPSessionControlRequestTarget? = nil
    ) -> SharedSessionControlResult {
        let token = "\(target.kind):forged"
        var snapshot = KMPSessionControlSnapshot.empty
        if target.isDefault {
            snapshot.defaultConfigurationLoadingKinds = [target.kind]
        } else {
            snapshot.loadingKinds = [target.kind]
        }
        snapshot.requestTokens = [target.kind: token]
        snapshot.activeRequestTargets = [target.kind: target]
        if target.kind == "models" {
            snapshot.pendingModelsSessionId = target.sessionId
            snapshot.isPendingGlobalModelsRequest = target.sessionId == nil
        }
        if target.kind == "select-model" { snapshot.pendingModelSelectionSessionId = target.sessionId }
        if target.kind == "permission-options" { snapshot.pendingPermissionOptionsSessionId = target.sessionId }
        var patch = KMPSessionControlPatch.empty
        patch.control = snapshot.controlPatch
        let effectTarget = effectTarget ?? target
        let effect = KMPSessionControlEffect(
            kind: effectTarget.kind,
            requestKey: effectTarget.kind,
            requestToken: token,
            sessionId: effectTarget.sessionId,
            provider: effectTarget.provider,
            model: effectTarget.model,
            reasoningEffort: effectTarget.reasoningEffort,
            target: effectTarget.target,
            value: effectTarget.value
        )
        return SharedSessionControlResult(
            snapshotJson: Self.encode(patch),
            effectsJson: Self.encode([effect]),
            errorCode: nil,
            errorMessage: nil,
            applied: true,
            committed: true,
            completedKind: nil,
            completedRequestToken: nil
        )
    }
}

private extension KMPSessionControlStoreBridging {
    func unimplemented() -> SharedSessionControlResult {
        SharedSessionControlResult(
            snapshotJson: nil,
            effectsJson: "[]",
            errorCode: "unexpected-test-call",
            errorMessage: nil,
            applied: false,
            committed: false,
            completedKind: nil,
            completedRequestToken: nil
        )
    }
    func requestPermissionOptions(sessionId: String, isConnected: Bool) -> SharedSessionControlResult { unimplemented() }
    func requestContextUsage(sessionId: String, isConnected: Bool) -> SharedSessionControlResult { unimplemented() }
    func requestSessionStats(sessionId: String, isConnected: Bool) -> SharedSessionControlResult { unimplemented() }
    func requestAgentPresets(isConnected: Bool) -> SharedSessionControlResult { unimplemented() }
    func requestDefaults(isConnected: Bool) -> SharedSessionControlResult { unimplemented() }
    func requestDefaultModel(isConnected: Bool) -> SharedSessionControlResult { unimplemented() }
    func setPermission(sessionId: String, value: String, isConnected: Bool) -> SharedSessionControlResult { unimplemented() }
    func saveDefaultModel(selectionJson: String, isConnected: Bool) -> SharedSessionControlResult { unimplemented() }
    func setDefault(target: String, value: String, isConnected: Bool) -> SharedSessionControlResult { unimplemented() }
    func clearSessionData(sessionId: String) -> SharedSessionControlResult { unimplemented() }
    func clearSessionsData(sessionIdsJson: String) -> SharedSessionControlResult { unimplemented() }
    func agentPresetsReceived(presetsJson: String, authorable: Bool, hasDocument: Bool) -> SharedSessionControlResult { unimplemented() }
    func defaultsReceived(agentPreset: String?, permission: String?) -> SharedSessionControlResult { unimplemented() }
    func defaultModelReceived(selectionJson: String?) -> SharedSessionControlResult { unimplemented() }
    func globalDefaultApplied(target: String, value: String) -> SharedSessionControlResult { unimplemented() }
    func modelsReceived(sessionId: String?, currentJson: String?, routable: Bool, groupsJson: String, isGlobalRequest: Bool) -> SharedSessionControlResult { unimplemented() }
    func defaultModelSaved(selectionJson: String?) -> SharedSessionControlResult { unimplemented() }
    func modelSelected(sessionId: String?, selectionJson: String) -> SharedSessionControlResult { unimplemented() }
    func permissionsReceived(sessionId: String?, permissionsJson: String) -> SharedSessionControlResult { unimplemented() }
    func permissionSelected(sessionId: String?, value: String) -> SharedSessionControlResult { unimplemented() }
    func contextReceived(sessionId: String?, asOfSequence: KotlinLong?, tokenUsageJson: String?, pressureJson: String?, breakdownJson: String?) -> SharedSessionControlResult { unimplemented() }
    func statsReceived(sessionId: String?, asOfSequence: KotlinLong?, statsJson: String?, tokenUsageTotalsJson: String?, contextPressureJson: String?) -> SharedSessionControlResult { unimplemented() }
    func mergeContextProjection(sessionId: String, asOfSequence: KotlinLong?, tokenUsageJson: String?, pressureJson: String?, breakdownJson: String?) -> SharedSessionControlResult { unimplemented() }
    func mergeStatsProjection(sessionId: String, asOfSequence: KotlinLong?, statsJson: String?, tokenUsageTotalsJson: String?, contextPressureJson: String?) -> SharedSessionControlResult { unimplemented() }
    func mergePermissionsProjection(sessionId: String, permissionsJson: String) -> SharedSessionControlResult { unimplemented() }
    func mergeModelProjection(sessionId: String, selectionJson: String) -> SharedSessionControlResult { unimplemented() }
    func mergePermissionProjection(sessionId: String, value: String) -> SharedSessionControlResult { unimplemented() }
    func requestFinished(kind: String, isDefault: Bool, requestToken: String?) -> SharedSessionControlResult { unimplemented() }
    func requestTimedOut(kind: String, isDefault: Bool, requestToken: String?) -> SharedSessionControlResult { unimplemented() }
    func requestFailed(kind: String, isDefault: Bool, requestToken: String?) -> SharedSessionControlResult { unimplemented() }
    func requestsDisconnected() -> SharedSessionControlResult { unimplemented() }
}

private final class FailingRestoreSessionListBridge: KMPSessionListStoreBridging {
    private(set) var nonRestoreCallCount = 0

    func snapshot() -> SharedSessionListResult { unexpectedCall() }

    func restore(snapshotJson: String) -> SharedSessionListResult {
        SharedSessionListResult(
            snapshotJson: nil,
            errorCode: "session-list-restore-failed",
            errorMessage: "injected restore failure"
        )
    }

    func selectSession(sessionId: String?) -> SharedSessionListResult { unexpectedCall() }

    func setArchivedSessionIds(sessionIdsJson: String) -> SharedSessionListResult { unexpectedCall() }

    func receiveRemoteSessions(sessionsJson: String) -> SharedSessionListResult { unexpectedCall() }

    func messageSent(
        sessionId: String,
        agentPreset: String?,
        insertedAtEpochSeconds: Double
    ) -> SharedSessionListResult {
        unexpectedCall()
    }

    func addKnownSession(
        sessionId: String,
        insertedAtEpochSeconds: Double
    ) -> SharedSessionListResult {
        unexpectedCall()
    }

    func receiveEvent(
        eventJson: String,
        insertedAtEpochSeconds: Double
    ) -> SharedSessionListResult {
        unexpectedCall()
    }

    func markRead(sessionId: String) -> SharedSessionListResult { unexpectedCall() }

    private func unexpectedCall() -> SharedSessionListResult {
        nonRestoreCallCount += 1
        return SharedSessionListResult(
            snapshotJson: "{\"sessions\":[],\"archivedSessionIds\":[]}",
            errorCode: nil,
            errorMessage: nil
        )
    }
}

private final class MalformedMutationSessionListBridge: KMPSessionListStoreBridging {
    private(set) var mutationCallCount = 0

    func snapshot() -> SharedSessionListResult { malformedMutation() }

    func restore(snapshotJson: String) -> SharedSessionListResult {
        SharedSessionListResult(
            snapshotJson: snapshotJson,
            errorCode: nil,
            errorMessage: nil
        )
    }

    func selectSession(sessionId: String?) -> SharedSessionListResult { malformedMutation() }

    func setArchivedSessionIds(sessionIdsJson: String) -> SharedSessionListResult {
        malformedMutation()
    }

    func receiveRemoteSessions(sessionsJson: String) -> SharedSessionListResult {
        malformedMutation()
    }

    func messageSent(
        sessionId: String,
        agentPreset: String?,
        insertedAtEpochSeconds: Double
    ) -> SharedSessionListResult {
        malformedMutation()
    }

    func addKnownSession(
        sessionId: String,
        insertedAtEpochSeconds: Double
    ) -> SharedSessionListResult {
        malformedMutation()
    }

    func receiveEvent(
        eventJson: String,
        insertedAtEpochSeconds: Double
    ) -> SharedSessionListResult {
        malformedMutation()
    }

    func markRead(sessionId: String) -> SharedSessionListResult { malformedMutation() }

    private func malformedMutation() -> SharedSessionListResult {
        mutationCallCount += 1
        return SharedSessionListResult(
            snapshotJson: "not-json",
            errorCode: nil,
            errorMessage: nil
        )
    }
}

private final class MalformedMutationQuestionBridge: KMPQuestionStoreBridging {
    private(set) var mutationCallCount = 0

    func snapshot() -> SharedQuestionResult {
        SharedQuestionResult(
            snapshotJson: #"{"pendingRequests":[],"requestStatuses":{}}"#,
            effectJson: nil,
            errorCode: nil,
            errorMessage: nil
        )
    }

    func reset() -> SharedQuestionResult { malformedMutation() }
    func requestReceived(requestJson: String) -> SharedQuestionResult { malformedMutation() }
    func submitAnswer(rpcId: String, answersJson: String, isConnected: Bool) -> SharedQuestionResult {
        malformedMutation()
    }
    func submitCancel(rpcId: String, isConnected: Bool) -> SharedQuestionResult { malformedMutation() }
    func responseReceived(
        rpcId: String,
        action: String,
        accepted: Bool,
        reason: String?
    ) -> SharedQuestionResult {
        malformedMutation()
    }
    func resolved(rpcId: String) -> SharedQuestionResult { malformedMutation() }
    func requestFailed(rpcId: String, message: String?) -> SharedQuestionResult { malformedMutation() }
    func sessionRequestsFailed(sessionId: String, message: String?) -> SharedQuestionResult { malformedMutation() }

    private func malformedMutation() -> SharedQuestionResult {
        mutationCallCount += 1
        return SharedQuestionResult(
            snapshotJson: #"{"pendingRequests":[],"requestStatuses":{}}"#,
            effectJson: "not-json",
            errorCode: nil,
            errorMessage: nil
        )
    }
}

private enum SemanticQuestionEffectMismatch: CaseIterable {
    case rpcID
    case sessionID
    case snapshotStatus
    case emptyAnswers
    case answerOrder
}

private final class SemanticMismatchQuestionBridge: KMPQuestionStoreBridging {
    let request: GatewayPendingQuestionRequest
    let mismatch: SemanticQuestionEffectMismatch
    private(set) var submitCallCount = 0

    init(request: GatewayPendingQuestionRequest, mismatch: SemanticQuestionEffectMismatch) {
        self.request = request
        self.mismatch = mismatch
    }

    func snapshot() -> SharedQuestionResult {
        result(snapshot: KMPQuestionSnapshot(
            pendingRequests: [request],
            requestStatuses: [request.rpcId: KMPQuestionStatusSnapshot(kind: "idle")]
        ))
    }

    func reset() -> SharedQuestionResult { snapshot() }
    func requestReceived(requestJson: String) -> SharedQuestionResult { snapshot() }

    func submitAnswer(rpcId: String, answersJson: String, isConnected: Bool) -> SharedQuestionResult {
        submitCallCount += 1
        let submittedAnswers = (try? JSONDecoder().decode(
            [GatewayQuestionAnswer].self,
            from: Data(answersJson.utf8)
        )) ?? []
        let status = mismatch == .snapshotStatus
            ? KMPQuestionStatusSnapshot(kind: "accepted", action: "answer")
            : KMPQuestionStatusSnapshot(kind: "submitting", action: "answer")
        let effectAnswers: [GatewayQuestionAnswer]
        switch mismatch {
        case .emptyAnswers:
            effectAnswers = []
        case .answerOrder:
            effectAnswers = Array(submittedAnswers.reversed())
        default:
            effectAnswers = submittedAnswers
        }
        return result(
            snapshot: KMPQuestionSnapshot(
                pendingRequests: [request],
                requestStatuses: [request.rpcId: status]
            ),
            effect: KMPQuestionEffect(
                action: "answer",
                rpcId: mismatch == .rpcID ? "rpc-other" : request.rpcId,
                sessionId: mismatch == .sessionID ? "session-other" : request.sessionId,
                answers: effectAnswers
            )
        )
    }

    func submitCancel(rpcId: String, isConnected: Bool) -> SharedQuestionResult { snapshot() }
    func responseReceived(
        rpcId: String,
        action: String,
        accepted: Bool,
        reason: String?
    ) -> SharedQuestionResult { snapshot() }
    func resolved(rpcId: String) -> SharedQuestionResult { snapshot() }
    func requestFailed(rpcId: String, message: String?) -> SharedQuestionResult { snapshot() }
    func sessionRequestsFailed(sessionId: String, message: String?) -> SharedQuestionResult { snapshot() }

    private func result(
        snapshot: KMPQuestionSnapshot,
        effect: KMPQuestionEffect? = nil
    ) -> SharedQuestionResult {
        let encoder = JSONEncoder()
        return SharedQuestionResult(
            snapshotJson: String(decoding: try! encoder.encode(snapshot), as: UTF8.self),
            effectJson: effect.map { String(decoding: try! encoder.encode($0), as: UTF8.self) },
            errorCode: nil,
            errorMessage: nil
        )
    }
}

private final class QuestionLifecycleBridge: KMPQuestionStoreBridging {
    private let request: GatewayPendingQuestionRequest
    private var snapshotValue: KMPQuestionSnapshot

    init(request: GatewayPendingQuestionRequest) {
        self.request = request
        snapshotValue = KMPQuestionSnapshot(
            pendingRequests: [request],
            requestStatuses: [request.rpcId: KMPQuestionStatusSnapshot(kind: "idle")]
        )
    }

    func snapshot() -> SharedQuestionResult { result() }

    func reset() -> SharedQuestionResult {
        snapshotValue = .empty
        return result()
    }

    func requestReceived(requestJson: String) -> SharedQuestionResult { result() }

    func submitAnswer(rpcId: String, answersJson: String, isConnected: Bool) -> SharedQuestionResult {
        let answers = (try? JSONDecoder().decode(
            [GatewayQuestionAnswer].self,
            from: Data(answersJson.utf8)
        )) ?? []
        snapshotValue.requestStatuses[rpcId] = KMPQuestionStatusSnapshot(kind: "submitting", action: "answer")
        return result(effect: KMPQuestionEffect(
            action: "answer",
            rpcId: rpcId,
            sessionId: request.sessionId,
            answers: answers
        ))
    }

    func submitCancel(rpcId: String, isConnected: Bool) -> SharedQuestionResult { result() }

    func responseReceived(
        rpcId: String,
        action: String,
        accepted: Bool,
        reason: String?
    ) -> SharedQuestionResult {
        if accepted {
            snapshotValue.requestStatuses[rpcId] = KMPQuestionStatusSnapshot(kind: "accepted", action: action)
        } else if reason == "not-pending" {
            snapshotValue = .empty
        } else {
            snapshotValue.requestStatuses[rpcId] = KMPQuestionStatusSnapshot(
                kind: "rejected",
                failureCode: "SERVER_REJECTED",
                failureArgument: reason
            )
        }
        return result()
    }

    func resolved(rpcId: String) -> SharedQuestionResult {
        snapshotValue = .empty
        return result()
    }

    func requestFailed(rpcId: String, message: String?) -> SharedQuestionResult {
        snapshotValue.requestStatuses[rpcId] = KMPQuestionStatusSnapshot(
            kind: "rejected",
            failureCode: "REQUEST_FAILED",
            failureArgument: message
        )
        return result()
    }

    func sessionRequestsFailed(sessionId: String, message: String?) -> SharedQuestionResult {
        for request in snapshotValue.pendingRequests where request.sessionId == sessionId {
            snapshotValue.requestStatuses[request.rpcId] = KMPQuestionStatusSnapshot(
                kind: "rejected",
                failureCode: "REQUEST_FAILED",
                failureArgument: message
            )
        }
        return result()
    }

    private func result(effect: KMPQuestionEffect? = nil) -> SharedQuestionResult {
        let encoder = JSONEncoder()
        return SharedQuestionResult(
            snapshotJson: String(decoding: try! encoder.encode(snapshotValue), as: UTF8.self),
            effectJson: effect.map { String(decoding: try! encoder.encode($0), as: UTF8.self) },
            errorCode: nil,
            errorMessage: nil
        )
    }
}

@MainActor
private final class GatewayQuestionEffectExecutorSpy: GatewayQuestionEffectExecuting {
    private(set) var answerCalls: [(rpcID: String, sessionID: String, answers: [GatewayQuestionAnswer])] = []
    private(set) var cancelCallCount = 0

    func answerQuestion(rpcId: String, sessionId: String, answers: [GatewayQuestionAnswer]) {
        answerCalls.append((rpcId, sessionId, answers))
    }

    func cancelQuestion(rpcId: String, sessionId: String) {
        cancelCallCount += 1
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

private final class MalformedConversationEventBridge:
    KMPConversationStoreBridging,
    KMPConversationEventBridging
{
    private var handler: ((SharedMviEvent) -> Void)?
    private(set) var receiveCallCount = 0

    func observeConversationEvents(
        _ handler: @escaping (SharedMviEvent) -> Void
    ) -> () -> Void {
        self.handler = handler
        handler(SharedMviEvent(
            schema: 2,
            sequence: 0,
            transactionId: "snapshot:conversation:0",
            domain: "conversation",
            kind: "snapshot",
            statePayloadJson: #"{"schema":1}"#,
            effectsJson: "[]",
            metadataJson: nil,
            errorCode: nil,
            errorMessage: nil
        ))
        return { [weak self] in self?.handler = nil }
    }

    func receiveEvent(eventJson: String) -> SharedMviDispatchResult {
        receiveCallCount += 1
        handler?(SharedMviEvent(
            schema: 2,
            sequence: 1,
            transactionId: "conversation-event:1",
            domain: "conversation",
            kind: "transition",
            statePayloadJson: #"{"schema":1,"sessionId":"s1","operations":[{"kind":"append-text","itemId":"missing","delta":"x","epochSeconds":1}],"replacesAll":false,"lastSequence":1}"#,
            effectsJson: "[]",
            metadataJson: nil,
            errorCode: nil,
            errorMessage: nil
        ))
        return SharedMviDispatchResult(
            accepted: true,
            transactionId: "conversation-event:1",
            eventSequence: KotlinLong(longLong: 1),
            errorCode: nil,
            errorMessage: nil
        )
    }

    func replaceSession(sessionId: String, eventsJson: String) -> SharedMviDispatchResult {
        SharedMviDispatchResult(
            accepted: false, transactionId: nil, eventSequence: nil,
            errorCode: "unsupported", errorMessage: nil
        )
    }

    func clearSession(sessionId: String) -> SharedMviDispatchResult {
        SharedMviDispatchResult(
            accepted: false, transactionId: nil, eventSequence: nil,
            errorCode: "unsupported", errorMessage: nil
        )
    }
}

private final class MalformedTrajectoryEventBridge:
    KMPTrajectoryStoreBridging,
    KMPTrajectoryEventBridging
{
    private var handler: ((SharedMviEvent) -> Void)?

    func observeTrajectoryEvents(
        _ handler: @escaping (SharedMviEvent) -> Void
    ) -> () -> Void {
        self.handler = handler
        handler(SharedMviEvent(
            schema: 2, sequence: 0,
            transactionId: "snapshot:trajectory:0",
            domain: "trajectory", kind: "snapshot",
            statePayloadJson: #"{"schema":1}"#,
            effectsJson: "[]", metadataJson: nil,
            errorCode: nil, errorMessage: nil
        ))
        return { [weak self] in self?.handler = nil }
    }

    func receiveEvent(eventJson: String) -> SharedMviDispatchResult {
        handler?(SharedMviEvent(
            schema: 2, sequence: 1,
            transactionId: "trajectory-event:1",
            domain: "trajectory", kind: "transition",
            statePayloadJson: #"{"schema":1,"sessionId":"s1","operations":[{"kind":"update","itemId":"missing","subtitleDelta":"x","endSequence":1,"endEpochSeconds":1,"appendedRecords":[]}],"replacesAll":false,"lastSequence":1}"#,
            effectsJson: "[]", metadataJson: nil,
            errorCode: nil, errorMessage: nil
        ))
        return SharedMviDispatchResult(
            accepted: true, transactionId: "trajectory-event:1",
            eventSequence: KotlinLong(longLong: 1),
            errorCode: nil, errorMessage: nil
        )
    }

    func receiveEvents(eventsJson: String) -> SharedMviDispatchResult {
        receiveEvent(eventJson: eventsJson)
    }

    func replaceSession(sessionId: String, eventsJson: String) -> SharedMviDispatchResult {
        SharedMviDispatchResult(
            accepted: false, transactionId: nil, eventSequence: nil,
            errorCode: "unsupported", errorMessage: nil
        )
    }

    func clearSession(sessionId: String) -> SharedMviDispatchResult {
        SharedMviDispatchResult(
            accepted: false, transactionId: nil, eventSequence: nil,
            errorCode: "unsupported", errorMessage: nil
        )
    }
}

private final class MalformedHistoryEventBridge:
    KMPHistoryStoreBridging,
    KMPHistoryEventBridging
{
    private var handler: ((SharedMviEvent) -> Void)?

    func observeHistoryEvents(_ handler: @escaping (SharedMviEvent) -> Void) -> () -> Void {
        self.handler = handler
        handler(SharedMviEvent(
            schema: 2, sequence: 0,
            transactionId: "snapshot:history:0",
            domain: "history", kind: "snapshot",
            statePayloadJson: #"{"schema":1,"state":{"sessions":{},"pendingSessionId":null},"eventsBySession":{}}"#,
            effectsJson: "[]", metadataJson: nil,
            errorCode: nil, errorMessage: nil
        ))
        return { [weak self] in self?.handler = nil }
    }

    func liveEventReceived(eventJson: String) -> SharedMviDispatchResult {
        handler?(SharedMviEvent(
            schema: 2, sequence: 1,
            transactionId: "history-live:1",
            domain: "history", kind: "transition",
            statePayloadJson: #"{"schema":1,"sessionId":"s1","session":null,"pendingSessionId":null,"pendingSessionChanged":false,"eventPatch":{"kind":"append","record":{"sessionId":"s1","seq":1,"time":1,"event":{"type":"assistant/message","text":"one"}},"index":9,"replacementEvents":null},"outcome":"none","failureCode":null,"completedEventCount":null,"completedByteCount":null,"completedHasMore":null}"#,
            effectsJson: "[]", metadataJson: nil,
            errorCode: nil, errorMessage: nil
        ))
        return accepted("history-live:1")
    }

    func start(sessionId: String, older: Bool, hasLocalEvents: Bool, earliestLocalSequence: KotlinInt?) -> SharedMviDispatchResult { unsupported() }
    func processingStarted(sessionId: String, rawEventCount: Int32, hasMore: Bool) -> SharedMviDispatchResult { unsupported() }
    func pageReceived(
        sessionId: String,
        eventsJson: String,
        byteCount: Int32,
        hasMore: Bool,
        nextBeforeSequence: KotlinInt?,
        remoteActivityTimestamp: KotlinDouble?
    ) -> SharedMviDispatchResult { unsupported() }
    func timedOut(sessionId: String) -> SharedMviDispatchResult { unsupported() }
    func cancelled(sessionId: String) -> SharedMviDispatchResult { unsupported() }
    func clearSession(sessionId: String) -> SharedMviDispatchResult { unsupported() }

    private func accepted(_ transactionID: String) -> SharedMviDispatchResult {
        SharedMviDispatchResult(
            accepted: true, transactionId: transactionID,
            eventSequence: KotlinLong(longLong: 1), errorCode: nil, errorMessage: nil
        )
    }

    private func unsupported() -> SharedMviDispatchResult {
        SharedMviDispatchResult(
            accepted: false, transactionId: nil, eventSequence: nil,
            errorCode: "unsupported", errorMessage: nil
        )
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
