import XCTest
import UIKit
@testable import DeepSeekHarnessMobile

final class GatewayProtocolTests: XCTestCase {
    func testDecodesDirectoryCreateResponse() throws {
        let data = #"{"kind":"directory-create","path":"/tmp/workspace/Sources"}"#.data(using: .utf8)!
        let frame = try JSONDecoder().decode(GatewayFrame.self, from: data)

        XCTAssertEqual(frame.kind, "directory-create")
        XCTAssertEqual(frame.path, "/tmp/workspace/Sources")
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
        let json = #"{"kind":"attachment","sessionId":"s1","attachment":{"attachmentId":"att-1","mediaType":"image/png","bytes":8,"width":1,"height":1},"data":"iVBORw0K"}"#
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
        let json = #"{"sessionId":"s1","seq":7,"time":1001,"event":{"type":"assistant/chunk","turn":1,"step":1,"chunkType":"reasoning-delta","text":"thinking"}}"#
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
        let json = #"{"kind":"history","events":[{"type":"user/message","seq":1,"time":1786937352,"data":{"content":[{"type":"image","attachment":{"attachmentId":"att-history","mediaType":"image/webp","bytes":42,"width":100,"height":80,"name":"image.webp"}}],"source":{"kind":"user"}}}],"hasMore":false}"#
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
        let json = #"{"kind":"question-requested","rpcId":"rpc-1","sessionId":"s1","replay":true,"questions":[{"id":"direction","header":"研究方向","question":"你想研究哪个方向？","detail":"请选择最感兴趣的方向","options":[{"label":"核心架构 (推荐)","description":"了解插件分层"},{"label":"移动端"}],"multiSelect":true},{"id":"notes","question":"还有什么要求？","multiSelect":false}]}"#
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

    func testQuestionAnswerTrimsCustomText() {
        let answer = GatewayQuestionAnswer(id: "custom", selected: [], custom: "  自定义回答  ")
        XCTAssertEqual(answer.custom, "自定义回答")
        XCTAssertNil(GatewayQuestionAnswer(id: "empty", selected: [], custom: "  \n ").custom)
    }
}

/// Guards the pure staleness arithmetic that decides when the open
/// conversation re-fetches history after a reconnect/background gap: the
/// backgrounding staleness bug (session list refreshed, chat never caught
/// up) must stay reproducible in a unit test.
final class ConversationReconciliationTests: XCTestCase {
    private func decision(
        remote: Date,
        synced: Date?,
        latestEvent: Date?,
        loading: Bool = false
    ) -> ConversationReconciliation.HistoryDecision {
        ConversationReconciliation.historyDecision(
            remoteLastActivity: remote,
            syncedActivity: synced,
            latestLocalEventDate: latestEvent,
            isHistoryLoading: loading
        )
    }

    func testInFlightLoadAlwaysSkips() {
        let t0 = Date(timeIntervalSince1970: 1_000)
        let t1 = t0.addingTimeInterval(60)
        let t2 = t0.addingTimeInterval(120)
        XCTAssertEqual(decision(remote: t2, synced: t1, latestEvent: t1, loading: true), .skipLoading)
        XCTAssertEqual(decision(remote: t2, synced: nil, latestEvent: nil, loading: true), .skipLoading)
    }

    func testNoLocalKnowledgeNeedsBaseline() {
        // No baseline and no cached events: nothing to compare against.
        XCTAssertEqual(
            decision(remote: Date(timeIntervalSince1970: 1_060), synced: nil, latestEvent: nil),
            .needsBaseline
        )
    }

    func testRemoteAheadOfCoverageReloads() {
        let t0 = Date(timeIntervalSince1970: 1_000)
        let t1 = t0.addingTimeInterval(60)
        let t2 = t0.addingTimeInterval(120)
        XCTAssertEqual(decision(remote: t2, synced: t1, latestEvent: nil), .reloadHistory)
        // Live events without a completed baseline still count as coverage,
        // so only a genuinely newer remote summary reloads.
        XCTAssertEqual(decision(remote: t2, synced: nil, latestEvent: t1), .reloadHistory)
    }

    func testCurrentCoverageSkips() {
        let t0 = Date(timeIntervalSince1970: 1_000)
        let t1 = t0.addingTimeInterval(60)
        XCTAssertEqual(decision(remote: t1, synced: t1, latestEvent: nil), .skipLoading)
        XCTAssertEqual(decision(remote: t0, synced: nil, latestEvent: t1), .skipLoading)
        // A completed baseline ahead of the remote summary also short-circuits.
        XCTAssertEqual(decision(remote: t0, synced: t1, latestEvent: nil), .skipLoading)
        // Equal timestamps count as covered, not behind.
        XCTAssertEqual(decision(remote: t1, synced: nil, latestEvent: t1), .skipLoading)
        XCTAssertEqual(decision(remote: t1, synced: t0, latestEvent: t1), .skipLoading)
    }

    func testActivationAndReconcilePoliciesDifferOnlyOnBaseline() {
        // Activation (conversation push / transport hello) may establish a
        // first baseline; reconcile (every sessions frame) may not.
        XCTAssertEqual(ConversationReconciliation.activationShouldLoadHistory(.needsBaseline), true)
        XCTAssertEqual(ConversationReconciliation.reconcileShouldLoadHistory(.needsBaseline), false)
        XCTAssertEqual(ConversationReconciliation.activationShouldLoadHistory(.reloadHistory), true)
        XCTAssertEqual(ConversationReconciliation.reconcileShouldLoadHistory(.reloadHistory), true)
        XCTAssertEqual(ConversationReconciliation.activationShouldLoadHistory(.skipLoading), false)
        XCTAssertEqual(ConversationReconciliation.reconcileShouldLoadHistory(.skipLoading), false)
    }

    func testTransportReactivationForcesHistoryReload() {
        // The host's updatedAt only advances on creation or a new human
        // prompt, so a session whose turn streamed while the transport was
        // down compares as "current" (.skipLoading) despite the missed
        // output. A transport-generation re-activation must reload anyway;
        // loadHistory's in-flight guard prevents stacking.
        XCTAssertEqual(
            ConversationReconciliation.activationShouldLoadHistory(
                .skipLoading, isTransportReactivation: true
            ),
            true
        )
        XCTAssertEqual(
            ConversationReconciliation.activationShouldLoadHistory(
                .needsBaseline, isTransportReactivation: true
            ),
            true
        )
        XCTAssertEqual(
            ConversationReconciliation.activationShouldLoadHistory(
                .reloadHistory, isTransportReactivation: true
            ),
            true
        )
        // Plain navigation pushes keep the watermark policy.
        XCTAssertEqual(
            ConversationReconciliation.activationShouldLoadHistory(
                .skipLoading, isTransportReactivation: false
            ),
            false
        )
    }

    // MARK: - Open-conversation disposition (archived pop / missing notice)

    private func disposition(
        selected: String?,
        prepared: String? = "s1",
        archived: Set<String> = [],
        present: Set<String> = ["s1"],
        unconfirmed: Set<String> = []
    ) -> ConversationReconciliation.OpenConversationDisposition {
        ConversationReconciliation.openConversationDisposition(
            selectedSessionId: selected,
            preparedActivationKey: prepared,
            archivedSessionIds: archived,
            presentSessionIds: present,
            unconfirmedLocallyCreatedSessionIds: unconfirmed
        )
    }

    func testDispositionRequiresOpenConversation() {
        XCTAssertEqual(disposition(selected: nil), .notOpen)
        // Prepared key pointing at another destination (or the
        // __new-conversation__ sentinel, which never equals a session id)
        // means no open conversation to act on.
        XCTAssertEqual(disposition(selected: "s1", prepared: "s2"), .notOpen)
        XCTAssertEqual(disposition(selected: "s1", prepared: nil), .notOpen)
        XCTAssertEqual(
            disposition(selected: "s1", prepared: "__new-conversation__"),
            .notOpen
        )
    }

    func testArchivedSessionDismissesToWorkspace() {
        // Archived wins when the id is genuinely absent from the raw presence
        // set (the server omits archived sessions from summaries, so a fresh
        // frame always produces this cell).
        XCTAssertEqual(
            disposition(selected: "s1", archived: ["s1"], present: []),
            .dismissToWorkspace
        )
        // Archived + raw-present is the stale-set cell (un-archived on the
        // desktop, workspaces frame not yet re-fetched): a raw-listed id is
        // active by construction, so it must NOT dismiss.
        XCTAssertEqual(
            disposition(selected: "s1", archived: ["s1"], present: ["s1"]),
            .continueReconciling
        )
    }

    func testArchivedWinsOverUnconfirmedLocallyCreated() {
        // A locally created session can legitimately end up archived before
        // any sessions frame lists it; the archived check precedes the
        // unconfirmed exemption.
        XCTAssertEqual(
            disposition(selected: "s1", archived: ["s1"], present: [], unconfirmed: ["s1"]),
            .dismissToWorkspace
        )
    }

    func testMissingSessionShowsNoticeOnlyWhenNotExempt() {
        XCTAssertEqual(disposition(selected: "s1", present: []), .showMissingNotice)
        // A locally created send not yet listed by any remote frame is exempt
        // from the deletion notice (pre-send snapshot race).
        XCTAssertEqual(
            disposition(selected: "s1", present: [], unconfirmed: ["s1"]),
            .continueReconciling
        )
    }

    func testPresentSessionContinuesReconciling() {
        XCTAssertEqual(disposition(selected: "s1"), .continueReconciling)
    }
}
