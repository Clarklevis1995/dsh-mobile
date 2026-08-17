import XCTest
@testable import DeepSeekHarnessMobile

final class GatewayProtocolTests: XCTestCase {
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

    func testConversationHidesInfrastructureEventsAndPluginPrompts() {
        let records = [
            SessionEvent(sessionId: "s1", seq: 1, time: 1, event: GatewayEvent(type: "permission/preset")),
            SessionEvent(sessionId: "s1", seq: 2, time: 2, event: GatewayEvent(type: "user/message", text: "runtime", source: "plugin")),
            SessionEvent(sessionId: "s1", seq: 3, time: 3, event: GatewayEvent(type: "user/message", text: "你好", source: "user")),
            SessionEvent(sessionId: "s1", seq: 4, time: 4, event: GatewayEvent(type: "assistant/message", text: "你好！", reasoning: "思考")),
            SessionEvent(sessionId: "s1", seq: 5, time: 5, event: GatewayEvent(type: "tool/call", name: "Read"))
        ]
        let items = ConversationItem.make(from: records)
        XCTAssertEqual(items.map(\.title), ["你", "Think", "DeepSeek", "Read"])
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
}
