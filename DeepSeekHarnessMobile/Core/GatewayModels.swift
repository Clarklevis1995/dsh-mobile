import Foundation
import ImageIO

@inline(__always)
func gatewayApprovalTrace(_ message: @autoclosure () -> String) {
#if DEBUG
    print("[DshApproval] \(message())")
#endif
}

enum JSONValue: Codable, Hashable, Sendable {
    case string(String), number(Double), bool(Bool), object([String: JSONValue]), array([JSONValue]), null

    init(from decoder: Decoder) throws {
        let value = try decoder.singleValueContainer()
        if value.decodeNil() { self = .null }
        else if let decoded = try? value.decode(Bool.self) { self = .bool(decoded) }
        else if let decoded = try? value.decode(Double.self) { self = .number(decoded) }
        else if let decoded = try? value.decode(String.self) { self = .string(decoded) }
        else if let decoded = try? value.decode([String: JSONValue].self) { self = .object(decoded) }
        else { self = .array(try value.decode([JSONValue].self)) }
    }

    func encode(to encoder: Encoder) throws {
        var value = encoder.singleValueContainer()
        switch self {
        case .string(let item): try value.encode(item)
        case .number(let item): try value.encode(item)
        case .bool(let item): try value.encode(item)
        case .object(let item): try value.encode(item)
        case .array(let item): try value.encode(item)
        case .null: try value.encodeNil()
        }
    }

    var displayText: String {
        guard let data = try? JSONEncoder.pretty.encode(self) else { return "" }
        return String(decoding: data, as: UTF8.self)
    }

    /// Pretty JSON for tool payloads. Some providers deliver tool arguments as
    /// a JSON string instead of an object, so unwrap and format that shape too.
    var jsonDisplayText: String {
        if case .string(let value) = self,
           let data = value.data(using: .utf8),
           let object = try? JSONSerialization.jsonObject(with: data),
           JSONSerialization.isValidJSONObject(object),
           let formatted = try? JSONSerialization.data(withJSONObject: object, options: [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]) {
            return String(decoding: formatted, as: UTF8.self)
        }
        return displayText
    }

    /// 将供应商以 JSON 字符串承载的 tool arguments 规范化为真正的 JSON 值。
    var normalizedValue: JSONValue {
        guard case .string(let value) = self,
              let data = value.data(using: .utf8),
              let decoded = try? JSONDecoder().decode(JSONValue.self, from: data) else {
            return self
        }
        return decoded
    }

    var objectValue: [String: JSONValue]? { if case .object(let value) = self { value } else { nil } }
    var arrayValue: [JSONValue]? { if case .array(let value) = self { value } else { nil } }
    var stringValue: String? { if case .string(let value) = self { value } else { nil } }
    var doubleValue: Double? { if case .number(let value) = self { value } else { nil } }
    var boolValue: Bool? { if case .bool(let value) = self { value } else { nil } }

    subscript(key: String) -> JSONValue? { objectValue?[key] }

    func decode<T: Decodable>(_ type: T.Type) -> T? {
        guard let data = try? JSONEncoder().encode(self) else { return nil }
        return try? JSONDecoder().decode(type, from: data)
    }
}

struct GatewayFrame: Codable, Sendable {
    var kind: String
    var `protocol`: Int?
    var capabilities: [String]?
    var authenticated: Bool?
    var token: String?
    var device: GatewayDevice?
    var port: Int?
    var clients: Int?
    var at: Double?
    var sessionId: String?
    var seq: Int?
    var time: Double?
    var event: GatewayEvent?
    var code: String?
    var message: String?
    var mode: String?
    var command: JSONValue?
    var requestType: String?
    var items: [JSONValue]?
    var events: [RawSessionEvent]?
    var hasMore: Bool?
    var nextBeforeSeq: Int?
    var bytes: Int?
    var view: String?
    var query: String?
    var archivedSessionIds: [String]?
    var workspace: GatewayWorkspace?
    var created: Bool?
    var version: String?
    var cwd: String?
    var provider: String?
    var model: String?
    var attachedSessions: Int?
    var canOpenPath: Bool?
    var path: String?
    var requestId: String?
    var transferId: String?
    var name: String?
    var mediaType: String?
    var size: Int64?
    var chunkBytes: Int?
    var offset: Int64?
    var eof: Bool?
    var sha256: String?
    var home: String?
    var crumbs: [GatewayDirectoryItem]?
    var entries: [GatewayDirectoryItem]?
    var truncated: Bool?
    var current: GatewayModelSelection?
    var routable: Bool?
    var groups: [GatewayModelGroup]?
    var failures: [JSONValue]?
    var selected: GatewayModelSelection?
    var selection: GatewayModelSelection?
    var saved: GatewayModelSelection?
    var namespace: JSONValue?
    var sessionPermissions: GatewaySessionPermissions?
    var set: String?
    var asOfSeq: Int?
    var sessionStats: GatewaySessionStats?
    var tokenUsage: GatewayTokenUsage?
    var contextPressure: GatewayContextPressure?
    var projections: JSONValue?
    var presets: [GatewayAgentPreset]?
    var authorable: Bool?
    var hasDocument: Bool?
    var agentPresetDefault: String?
    var permissionDefault: String?
    var target: String?
    var value: String?
    var applied: Bool?
    // Human-in-the-loop question protocol (Mobile Gateway v0.5.0).
    var rpcId: String?
    var questions: [GatewayQuestion]?
    var replay: Bool?
    var action: String?
    var accepted: Bool?
    var reason: String?
    var outcome: String?
    // Human-in-the-loop approval protocol (Mobile Gateway v0.6.6).
    var approvalId: String?
    var toolName: String?
    var callId: String?
    // Image attachment protocol (Mobile Gateway v0.6.0 / protocol 3).
    var attachment: GatewayImageAttachment?
    var data: String?
}

struct GatewayImageAttachment: Codable, Hashable, Sendable, Identifiable {
    var attachmentId: String
    var mediaType: String
    var bytes: Int
    var width: Int
    var height: Int
    var name: String?

    var id: String { attachmentId }
}

struct GatewayOutgoingImage: Hashable, Sendable, Identifiable {
    var id = UUID()
    var mediaType: String
    var data: Data
    var name: String? = nil
}

struct GatewayImageDimensions: Equatable, Sendable {
    var width: Int
    var height: Int

    var longestSide: Int { max(width, height) }
}

enum GatewayImageInspector {
    /// DSH 0.1.1's default attachment-store per-side limit.
    static let maximumPixelSide = 2_000

    static func dimensions(of data: Data) -> GatewayImageDimensions? {
        let options = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let source = CGImageSourceCreateWithData(data as CFData, options),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, options) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? NSNumber,
              let height = properties[kCGImagePropertyPixelHeight] as? NSNumber,
              width.intValue > 0,
              height.intValue > 0 else { return nil }
        let orientation = (properties[kCGImagePropertyOrientation] as? NSNumber)?.intValue ?? 1
        if [5, 6, 7, 8].contains(orientation) {
            return GatewayImageDimensions(width: height.intValue, height: width.intValue)
        }
        return GatewayImageDimensions(width: width.intValue, height: height.intValue)
    }

    /// Returns the EXIF/CGImage orientation stored in the first frame. A
    /// missing orientation is equivalent to `.up` (1).
    static func orientation(of data: Data) -> Int {
        let options = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let source = CGImageSourceCreateWithData(data as CFData, options),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, options) as? [CFString: Any] else {
            return 1
        }
        return (properties[kCGImagePropertyOrientation] as? NSNumber)?.intValue ?? 1
    }

    static func isWithinPixelLimits(_ dimensions: GatewayImageDimensions) -> Bool {
        dimensions.longestSide <= maximumPixelSide
    }
}

struct GatewayQuestionOption: Codable, Hashable, Sendable, Identifiable {
    var label: String
    var description: String?
    var id: String { label }
}

struct GatewayQuestionIntent: Codable, Hashable, Sendable {
    var kind: String
    var approve: String?
}

struct GatewayQuestion: Codable, Hashable, Sendable, Identifiable {
    var id: String
    var header: String?
    var question: String
    var detail: String?
    var options: [GatewayQuestionOption]?
    var multiSelect: Bool?
    var intent: GatewayQuestionIntent?

    var allowsMultipleSelections: Bool { multiSelect == true }
}

struct GatewayPendingQuestionRequest: Codable, Hashable, Sendable, Identifiable {
    var rpcId: String
    var sessionId: String
    var questions: [GatewayQuestion]
    var replay: Bool
    var id: String { rpcId }
}

struct GatewayQuestionAnswer: Codable, Hashable, Sendable {
    var id: String
    var selected: [String]
    var custom: String?

    init(id: String, selected: [String], custom: String? = nil) {
        self.id = id
        self.selected = selected
        let normalized = custom?.trimmingCharacters(in: .whitespacesAndNewlines)
        self.custom = normalized?.isEmpty == false ? normalized : nil
    }
}

enum GatewayQuestionAction: String, Hashable, Sendable {
    case answer
    case cancel
}

enum GatewayQuestionRequestStatus: Equatable, Sendable {
    case idle
    case submitting(GatewayQuestionAction)
    case accepted(GatewayQuestionAction)
    case rejected(String)

    var isBusy: Bool {
        switch self {
        case .submitting, .accepted: true
        case .idle, .rejected: false
        }
    }
}

struct GatewayPendingApprovalRequest: Codable, Hashable, Sendable, Identifiable {
    var rpcId: String
    var sessionId: String
    var approvalId: String
    var toolName: String
    var callId: String?
    var reason: String?
    var replay: Bool
    var id: String { rpcId }
}

enum GatewayApprovalOutcome: String, Codable, Hashable, Sendable {
    case allowedOnce = "allowed-once"
    case rejected
}

enum GatewayApprovalRequestStatus: Equatable, Sendable {
    case idle
    case submitting(GatewayApprovalOutcome)
    case accepted(GatewayApprovalOutcome)
    case failed(String)

    var isBusy: Bool {
        switch self {
        case .submitting, .accepted: true
        case .idle, .failed: false
        }
    }
}

struct GatewayDevice: Codable, Hashable, Sendable {
    var id: String
    var name: String?
    var createdAt: Double?
}

struct GatewayPairingPayload: Codable, Hashable, Sendable {
    var version: Int
    var publicUrl: String
    var pairingCode: String
    var expiresAt: Double

    var endpoint: URL? { URL(string: publicUrl) }
    var expirationDate: Date { Date(timeIntervalSince1970: expiresAt / 1_000) }
}

/// The gateway's v0.1.6 live-event broadcaster currently omits `kind: "event"`
/// even though query/control responses include `kind`. Normalize that wire quirk
/// here so one malformed discriminator cannot discard an otherwise valid event.
enum GatewayWireDecoder {
    static func decode(_ data: Data) throws -> GatewayFrame {
        guard var object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return try JSONDecoder().decode(GatewayFrame.self, from: data)
        }
        if object["kind"] == nil,
           object["sessionId"] is String,
           object["seq"] is NSNumber,
           object["event"] is [String: Any] {
            object["kind"] = "event"
        }
        let normalized = try JSONSerialization.data(withJSONObject: object)
        return try JSONDecoder().decode(GatewayFrame.self, from: normalized)
    }
}

struct GatewayWorkspace: Codable, Hashable, Sendable, Identifiable {
    var workspaceId: String
    var path: String
    var title: String
    var sessionIds: [String]
    var createdAt: String
    var updatedAt: String
    var id: String { workspaceId }
}

struct GatewaySessionSummary: Codable, Hashable, Sendable {
    var sessionId: String
    var updatedAt: Double
    var running: Bool
    var blank: Bool
    var parentSessionId: String?
    var origin: String?
    var cwd: String?
    var agentPreset: String?
    var projections: JSONValue?

    var projectedTitle: String? {
        projections?["values"]?["title"]?.stringValue
    }
}

struct GatewaySearchItem: Codable, Hashable, Sendable, Identifiable {
    var sessionId: String
    var snippet: String
    var id: String { sessionId }
}

struct GatewayDirectoryItem: Codable, Hashable, Sendable, Identifiable {
    var name: String
    var path: String
    var hidden: Bool = false
    var kind: String?
    var bytes: Int64?
    var modifiedAt: Double?
    var mediaType: String?
    var id: String { path }

    init(
        name: String,
        path: String,
        hidden: Bool = false,
        kind: String? = nil,
        bytes: Int64? = nil,
        modifiedAt: Double? = nil,
        mediaType: String? = nil
    ) {
        self.name = name
        self.path = path
        self.hidden = hidden
        self.kind = kind
        self.bytes = bytes
        self.modifiedAt = modifiedAt
        self.mediaType = mediaType
    }

    private enum CodingKeys: String, CodingKey {
        case name, path, hidden, kind, bytes, modifiedAt, mediaType
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        name = try container.decode(String.self, forKey: .name)
        path = try container.decode(String.self, forKey: .path)
        hidden = try container.decodeIfPresent(Bool.self, forKey: .hidden) ?? false
        kind = try container.decodeIfPresent(String.self, forKey: .kind)
        bytes = try container.decodeIfPresent(Int64.self, forKey: .bytes)
        modifiedAt = try container.decodeIfPresent(Double.self, forKey: .modifiedAt)
        mediaType = try container.decodeIfPresent(String.self, forKey: .mediaType)
    }
}

struct WorkspaceLocalFile: Identifiable, Equatable {
    var url: URL
    var sessionID: String
    var remotePath: String
    var name: String
    var mediaType: String
    var purpose: String
    var id: String { url.path }
}

struct GatewayHostSnapshot: Codable, Hashable, Sendable {
    var version: String?
    var cwd: String?
    var provider: String?
    var model: String?
    var attachedSessions: Int?
    var canOpenPath: Bool?
}

struct GatewayModelSelection: Codable, Hashable, Sendable {
    var provider: String
    var model: String
    var reasoningEffort: String?
}

struct GatewayReasoningEffort: Codable, Hashable, Sendable, Identifiable {
    var id: String
    var name: String
}

struct GatewayModelReasoning: Codable, Hashable, Sendable {
    var efforts: [GatewayReasoningEffort]
    var defaultEffort: String?
}

struct GatewayModelItem: Codable, Hashable, Sendable, Identifiable {
    var id: String
    var name: String
    var reasoning: GatewayModelReasoning?
}

struct GatewayModelGroup: Codable, Hashable, Sendable, Identifiable {
    var id: String
    var name: String
    var models: [GatewayModelItem]
}

struct GatewayModelCatalog: Codable, Hashable, Sendable {
    var current: GatewayModelSelection?
    var routable: Bool
    var groups: [GatewayModelGroup]
}

struct GatewayPermissionOption: Codable, Hashable, Sendable, Identifiable {
    var value: String
    var name: String
    var id: String { value }
}

struct GatewayAgentPreset: Codable, Hashable, Sendable, Identifiable {
    var id: String
    var trust: JSONValue?
    var isDefault: Bool
    var name: String?
    var description: String?
    var broken: Bool?

    var displayName: String {
        if let name, !name.isEmpty { return name }
        return L10n.presetModeName(for: id)
    }

    var displayDescription: String {
        if let description, !description.isEmpty { return description }
        return L10n.presetModeBlurb(for: id)
    }
}

struct GatewaySessionPermissions: Codable, Hashable, Sendable {
    var options: [GatewayPermissionOption]? = nil
    var currentValue: String? = nil
    var preset: String? = nil
    var sandbox: String? = nil
    var approval: String? = nil
}

struct GatewayTokenUsage: Codable, Hashable, Sendable {
    var uncachedInputTokens: Int?
    var outputTokens: Int?
    var cacheReadTokens: Int?
    var cacheWriteTokens: Int?
    var totals: GatewaySessionTokenUsageTotals?
}

struct GatewayContextPressure: Codable, Hashable, Sendable {
    var pressureTokens: Int?
    var projectedTokens: Int?
    var contextWindow: Int?
}

struct GatewayContextBreakdown: Codable, Hashable, Sendable {
    var systemTokens: Int?
    var toolsTokens: Int?
    var messageTokens: Int?
}

struct GatewayContextSnapshot: Codable, Hashable, Sendable {
    var asOfSeq: Int? = nil
    var tokenUsage: GatewayTokenUsage? = nil
    var pressure: GatewayContextPressure? = nil
    var breakdown: GatewayContextBreakdown? = nil
}

/// Lightweight session projection returned by `session-stats`.  It is kept
/// separate from context-usage because the gateway exposes a nested token
/// totals object for this endpoint.
struct GatewaySessionStats: Codable, Hashable, Sendable {
    var turns: Int?
    var steps: Int?
    var llmMs: Double?
    var toolMs: Double?
    var ttftMs: Double?
    var ttftSteps: Int?
    var decodeMs: Double?
    var decodeTokens: Int?
    var lastTurn: Int?
    var openStep: Int?
    var pendingCalls: JSONValue?
}

struct GatewaySessionTokenUsage: Codable, Hashable, Sendable {
    var totals: GatewaySessionTokenUsageTotals?
}

struct GatewaySessionTokenUsageTotals: Codable, Hashable, Sendable {
    var inputTokens: Int?
    var outputTokens: Int?
    var cacheReadTokens: Int?
    var cacheWriteTokens: Int?
    var reasoningTokens: Int?
}

struct GatewaySessionStatsSnapshot: Codable, Hashable, Sendable {
    var asOfSeq: Int? = nil
    var stats: GatewaySessionStats? = nil
    var tokenUsage: GatewaySessionTokenUsage? = nil
    var contextPressure: GatewayContextPressure? = nil
}

struct RawSessionEvent: Codable, Hashable, Sendable {
    var type: String
    var seq: Int
    var time: Double
    var data: JSONValue

    func normalized(sessionId: String) -> SessionEvent {
        SessionEvent(sessionId: sessionId, seq: seq, time: time, event: normalizedEvent)
    }

    private var normalizedEvent: GatewayEvent {
        let turn = data["turn"]?.doubleValue.map(Int.init)
        let step = data["step"]?.doubleValue.map(Int.init)
        switch type {
        case "user/message":
            return GatewayEvent(
                type: type,
                text: textBlocks(data["content"]),
                source: data["source"]?["kind"]?.stringValue,
                images: imageBlocks(data["content"]),
                raw: data
            )
        case "assistant/chunk":
            let chunk = data["chunk"]
            let chunkType = chunk?["type"]?.stringValue
            var tool: ToolDelta?
            if chunkType == "tool-call-delta" {
                tool = ToolDelta(id: chunk?["id"]?.stringValue, name: chunk?["name"]?.stringValue, argumentsDelta: chunk?["argumentsDelta"]?.stringValue)
            }
            return GatewayEvent(type: type, turn: turn, step: step, text: chunk?["text"]?.stringValue, chunkType: chunkType, tool: tool, usage: chunk?["usage"], finish: FinishInfo(kind: chunk?["reason"]?["kind"]?.stringValue), raw: data)
        case "assistant/message":
            let blocks = data["message"]?["content"]?.arrayValue ?? []
            let text = blocks.filter { $0["type"]?.stringValue == "text" }.compactMap { $0["text"]?.stringValue }.joined()
            let reasoning = blocks.filter { $0["type"]?.stringValue == "reasoning" }.compactMap { $0["text"]?.stringValue }.joined()
            let calls = blocks.compactMap { block -> ToolCall? in
                guard block["type"]?.stringValue == "tool-call", let id = block["id"]?.stringValue, let name = block["name"]?.stringValue else { return nil }
                return ToolCall(id: id, name: name, arguments: block["arguments"])
            }
            return GatewayEvent(
                type: type,
                turn: turn,
                step: step,
                text: text,
                usage: data["usage"],
                reasoning: reasoning,
                toolCalls: calls,
                images: imageBlocks(data["message"]?["content"]),
                raw: data
            )
        case "tool/call":
            return GatewayEvent(type: type, turn: turn, step: step, callId: data["callId"]?.stringValue, name: data["name"]?.stringValue, arguments: data["arguments"], raw: data)
        case "tool/result":
            let preview = toolResultText(data["message"])
            return GatewayEvent(type: type, turn: turn, step: step, callId: data["message"]?["source"]?["callId"]?.stringValue, isError: data["error"] != nil && data["error"] != .null, preview: preview, raw: data)
        case "tool/code-dispatch-start":
            return GatewayEvent(
                type: type,
                name: data["name"]?.stringValue,
                arguments: data["arguments"],
                rootCallId: data["rootCallId"]?.stringValue,
                parentCallId: data["parentCallId"]?.stringValue,
                subCallId: data["subCallId"]?.stringValue,
                raw: data
            )
        case "tool/code-dispatch":
            return GatewayEvent(
                type: type,
                name: data["name"]?.stringValue,
                arguments: data["arguments"],
                isError: data["isError"]?.boolValue,
                preview: textBlocks(data["content"]),
                rootCallId: data["rootCallId"]?.stringValue,
                parentCallId: data["parentCallId"]?.stringValue,
                subCallId: data["subCallId"]?.stringValue,
                raw: data
            )
        case "turn/start", "turn/end", "step/start", "step/end":
            return GatewayEvent(type: type, turn: turn, step: step, reason: data["reason"]?["kind"]?.stringValue, raw: data)
        case "session/title":
            return GatewayEvent(type: type, text: data["title"]?.stringValue, raw: data)
        default:
            return GatewayEvent(type: type, turn: turn, step: step, text: data["text"]?.stringValue, raw: data)
        }
    }

    private func textBlocks(_ value: JSONValue?) -> String {
        (value?.arrayValue ?? []).filter { $0["type"]?.stringValue == "text" }.compactMap { $0["text"]?.stringValue }.joined()
    }

    private func imageBlocks(_ value: JSONValue?) -> [GatewayImageAttachment] {
        (value?.arrayValue ?? []).compactMap { block in
            guard block["type"]?.stringValue == "image" else { return nil }
            return block["attachment"]?.decode(GatewayImageAttachment.self)
        }
    }

    private func toolResultText(_ message: JSONValue?) -> String {
        let outer = message?["content"]?.arrayValue ?? []
        return outer.flatMap { $0["content"]?.arrayValue ?? [] }.filter { $0["type"]?.stringValue == "text" }.compactMap { $0["text"]?.stringValue }.joined()
    }
}

struct GatewayEvent: Codable, Hashable, Sendable, Identifiable {
    var type: String
    var turn: Int?
    var step: Int?
    var text: String?
    var source: String?
    var chunkType: String?
    var tool: ToolDelta?
    var usage: JSONValue?
    var finish: FinishInfo?
    var reasoning: String?
    var toolCalls: [ToolCall]?
    var images: [GatewayImageAttachment]?
    var callId: String?
    var name: String?
    var arguments: JSONValue?
    var isError: Bool?
    var preview: String?
    var reason: String?
    var rootCallId: String?
    var parentCallId: String?
    var subCallId: String?
    var raw: JSONValue?

    var id: String {
        "\(type)-\(turn ?? -1)-\(step ?? -1)-\(callId ?? "")-\(text?.hashValue ?? 0)"
    }
}

struct ToolDelta: Codable, Hashable, Sendable {
    var id: String?
    var name: String?
    var argumentsDelta: String?
}

struct ToolCall: Codable, Hashable, Sendable, Identifiable {
    var id: String
    var name: String
    var arguments: JSONValue?
}

struct FinishInfo: Codable, Hashable, Sendable { var kind: String? }

struct SessionEvent: Codable, Hashable, Sendable, Identifiable {
    var sessionId: String
    var seq: Int
    var time: Double
    var event: GatewayEvent

    var id: String { "\(sessionId)-\(seq)" }
    var date: Date { Date(timeIntervalSince1970: time > 10_000_000_000 ? time / 1000 : time) }
}

struct SessionSummary: Codable, Hashable, Identifiable {
    var id: String
    var title: String
    var lastActivity: Date
    var isRunning: Bool
    var hasUnread: Bool
    var agentPreset: String? = nil
}

struct GatewayNotice: Identifiable, Hashable {
    let id = UUID()
    var sessionId: String?
    var title: String
    var text: String
    var isError: Bool = false
    var date: Date = .now
}

enum ConnectionState: Equatable {
    case disconnected, connecting, connected, failed(String)

    var label: String {
        switch self {
        case .disconnected: String(localized: "未连接")
        case .connecting: String(localized: "连接中")
        case .connected: String(localized: "已连接")
        case .failed: String(localized: "连接失败")
        }
    }

    var isConnected: Bool { self == .connected }
}

extension JSONEncoder {
    static let pretty: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        return encoder
    }()
}
