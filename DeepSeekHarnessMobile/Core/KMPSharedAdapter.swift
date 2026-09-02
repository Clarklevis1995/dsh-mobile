import Foundation
import DeepSeekHarnessShared

// MARK: - Conversation incremental MVI

private struct KMPConversationBootstrap: Decodable {
    let schema: Int
}

private struct KMPConversationItemSnapshot: Decodable {
    let id: String
    let kind: String
    let title: String
    let text: String
    let images: [GatewayImageAttachment]
    let isError: Bool
    let epochSeconds: Double

    var item: ConversationItem? {
        guard !id.isEmpty,
              epochSeconds.isFinite,
              let mappedKind = ConversationItem.Kind(kmpWireValue: kind) else { return nil }
        return ConversationItem(
            id: id,
            kind: mappedKind,
            title: title,
            text: text,
            images: images,
            isError: isError,
            date: Date(timeIntervalSince1970: epochSeconds)
        )
    }
}

private extension ConversationItem.Kind {
    init?(kmpWireValue: String) {
        switch kmpWireValue {
        case "USER": self = .user
        case "CONTEXT": self = .context
        case "ASSISTANT": self = .assistant
        case "REASONING": self = .reasoning
        case "TOOL": self = .tool
        case "JSON_TOOL": self = .jsonTool
        case "TOOL_RESULT": self = .toolResult
        case "STATUS": self = .status
        case "SYSTEM": self = .system
        default: return nil
        }
    }
}

private struct KMPConversationOperation: Decodable {
    let kind: String
    let item: KMPConversationItemSnapshot?
    let itemId: String?
    let delta: String?
    let epochSeconds: Double?
}

private struct KMPConversationPatch: Decodable {
    let schema: Int
    let sessionId: String
    let operations: [KMPConversationOperation]
    let replacesAll: Bool
    let replacementItems: [KMPConversationItemSnapshot]?
    let lastSequence: Int
}

enum KMPConversationStoreError: LocalizedError, Equatable {
    case encoding(String)
    case bridge(code: String, message: String?)
    case invalidEvent(String)
    case runtimeFailed(String)

    var errorDescription: String? {
        switch self {
        case .encoding(let message):
            "无法编码 iOS Conversation 输入：\(message)"
        case .bridge(let code, let message):
            "KMP Conversation 失败（\(code)）：\(message ?? "无详细信息")"
        case .invalidEvent(let message):
            "KMP Conversation 增量事件无效：\(message)"
        case .runtimeFailed(let message):
            "KMP Conversation 事件流已停止：\(message)"
        }
    }
}

protocol KMPConversationStoreBridging: AnyObject {
    func receiveEvent(eventJson: String) -> SharedMviDispatchResult
    func replaceSession(sessionId: String, eventsJson: String) -> SharedMviDispatchResult
    func clearSession(sessionId: String) -> SharedMviDispatchResult
}

extension SharedConversationStore: KMPConversationStoreBridging {}

protocol KMPConversationEventBridging: AnyObject {
    func observeConversationEvents(
        _ handler: @escaping (SharedMviEvent) -> Void
    ) -> () -> Void
}

extension SharedConversationStore: KMPConversationEventBridging {
    func observeConversationEvents(
        _ handler: @escaping (SharedMviEvent) -> Void
    ) -> () -> Void {
        let observer = KMPSharedMviEventObserver(handler: handler)
        let subscription = subscribe(observer: observer)
        return {
            _ = observer
            subscription.cancel()
        }
    }
}

private enum KMPConversationIntent {
    case event(sessionID: String, sequence: Int)
    case replace(sessionID: String, lastSequence: Int)
    case clear(sessionID: String)
}

struct KMPConversationChange {
    let sessionID: String
    let items: [ConversationItem]
    let replacesAll: Bool
}

/// 高频 Conversation 状态只存在于 KMP projector；Swift 按有序 patch 维护 UI 镜像，
/// 不再自行执行领域 fold。MainActor 保证 dispatch、event 与 UI 发布严格串行。
@MainActor
final class KMPConversationStoreAdapter {
    private let store: any KMPConversationStoreBridging
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()
    private var cancelEventSubscription: (() -> Void)?
    private var pendingIntent: KMPConversationIntent?
    private var pushedError: KMPConversationStoreError?
    private(set) var itemsBySessionID: [String: [ConversationItem]] = [:]
    private(set) var lastSequenceBySessionID: [String: Int] = [:]
    private(set) var lastEventSequence: Int64?
    private(set) var runtimeError: KMPConversationStoreError?
    var onChange: ((KMPConversationChange) -> Void)?
    var onError: ((KMPConversationStoreError) -> Void)?
    var isOperational: Bool { runtimeError == nil }

    init(
        bridge: (any KMPConversationStoreBridging)? = nil,
        facade: SharedMobileFacade = SharedMobileFacade()
    ) {
        store = bridge ?? facade.makeConversationStore(
            userMessage: L10n.userMessageTitle,
            context: L10n.contextInjectionTitle(""),
            streamingAssistant: L10n.streamingAssistantTitle,
            streamingReasoning: L10n.streamingReasoningTitle,
            finalAssistant: "DeepSeek",
            finalReasoning: "Think",
            assemblingTool: L10n.assemblingToolTitle,
            toolResultDone: L10n.toolResultDoneTitle,
            toolResultFailed: L10n.toolResultFailedTitle,
            commandRunning: String(localized: "command.running", defaultValue: "正在执行…"),
            commandCompacting: String(localized: "command.compacting", defaultValue: "正在压缩…"),
            commandCompleted: String(localized: "command.completed", defaultValue: "已完成"),
            commandFailed: String(localized: "command.failed", defaultValue: "执行失败"),
            compactedHistory: String(
                localized: "command.compacted-history",
                defaultValue: "已压缩 {items} 条历史记录（约 {tokens} tokens）"
            )
        )
        guard let eventStore = store as? any KMPConversationEventBridging else {
            failClosed(.runtimeFailed("Conversation bridge 不支持 MVI event stream"))
            return
        }
        cancelEventSubscription = eventStore.observeConversationEvents { [weak self] event in
            MainActor.assumeIsolated { self?.receive(event) }
        }
    }

    func items(for sessionID: String) -> [ConversationItem] {
        itemsBySessionID[sessionID] ?? []
    }

    func receive(_ event: SessionEvent) throws {
        try dispatch(.event(sessionID: event.sessionId, sequence: event.seq)) {
            store.receiveEvent(eventJson: try encode(event))
        }
    }

    func replace(sessionID: String, events: [SessionEvent]) throws {
        let lastSequence = events.map(\.seq).max() ?? -1
        try dispatch(.replace(sessionID: sessionID, lastSequence: lastSequence)) {
            store.replaceSession(sessionId: sessionID, eventsJson: try encode(events))
        }
    }

    func clear(sessionID: String) throws {
        try dispatch(.clear(sessionID: sessionID)) {
            store.clearSession(sessionId: sessionID)
        }
    }

    private func dispatch(
        _ intent: KMPConversationIntent,
        operation: () throws -> SharedMviDispatchResult
    ) throws {
        if let runtimeError {
            throw KMPConversationStoreError.runtimeFailed(runtimeError.localizedDescription)
        }
        pendingIntent = intent
        pushedError = nil
        defer { pendingIntent = nil }
        let result: SharedMviDispatchResult
        do {
            result = try operation()
        } catch {
            throw KMPConversationStoreError.encoding(error.localizedDescription)
        }
        if let pushedError { throw pushedError }
        guard result.accepted else {
            throw KMPConversationStoreError.bridge(
                code: result.errorCode ?? "unknown-error",
                message: result.errorMessage
            )
        }
    }

    private func receive(_ event: SharedMviEvent) {
        guard event.schema == 2,
              event.domain == "conversation",
              !event.transactionId.isEmpty else {
            failClosed(.invalidEvent("MVI 信封 schema、domain 或 transactionId 无效"))
            return
        }
        if event.kind == "snapshot" {
            guard lastEventSequence == nil,
                  let payload = event.statePayloadJson,
                  let bootstrap = try? decoder.decode(KMPConversationBootstrap.self, from: Data(payload.utf8)),
                  bootstrap.schema == 1 else {
                failClosed(.invalidEvent("订阅基线无效"))
                return
            }
            lastEventSequence = event.sequence
            return
        }
        guard let previous = lastEventSequence,
              event.sequence == previous + 1 else {
            failClosed(.runtimeFailed("MVI event sequence 不连续"))
            return
        }
        lastEventSequence = event.sequence
        switch event.kind {
        case "error":
            let error = KMPConversationStoreError.bridge(
                code: event.errorCode ?? "unknown-error",
                message: event.errorMessage
            )
            pushedError = error
            onError?(error)
        case "transition":
            guard let payload = event.statePayloadJson else {
                failClosed(.invalidEvent("transition 缺少 patch"))
                return
            }
            do {
                let patch = try decoder.decode(KMPConversationPatch.self, from: Data(payload.utf8))
                let change = try apply(patch, intent: pendingIntent)
                onChange?(change)
            } catch let error as KMPConversationStoreError {
                failClosed(error)
            } catch {
                failClosed(.invalidEvent(error.localizedDescription))
            }
        default:
            failClosed(.runtimeFailed("未知 MVI event kind：\(event.kind)"))
        }
    }

    private func apply(
        _ patch: KMPConversationPatch,
        intent: KMPConversationIntent?
    ) throws -> KMPConversationChange {
        guard patch.schema == 1, !patch.sessionId.isEmpty else {
            throw KMPConversationStoreError.invalidEvent("patch schema 或 sessionId 无效")
        }
        switch intent {
        case .event(let sessionID, let sequence):
            guard patch.sessionId == sessionID,
                  patch.lastSequence == sequence,
                  !patch.replacesAll,
                  patch.replacementItems == nil,
                  !patch.operations.isEmpty else {
                throw KMPConversationStoreError.invalidEvent("live patch 与当前 event intent 不一致")
            }
        case .replace(let sessionID, let lastSequence):
            guard patch.sessionId == sessionID,
                  patch.lastSequence == lastSequence,
                  patch.replacesAll,
                  patch.replacementItems != nil,
                  patch.operations.isEmpty else {
                throw KMPConversationStoreError.invalidEvent("replace patch 与当前 intent 不一致")
            }
        case .clear(let sessionID):
            guard patch.sessionId == sessionID,
                  patch.lastSequence == -1,
                  patch.replacesAll,
                  patch.replacementItems?.isEmpty == true,
                  patch.operations.isEmpty else {
                throw KMPConversationStoreError.invalidEvent("clear patch 与当前 intent 不一致")
            }
        case nil:
            throw KMPConversationStoreError.invalidEvent("transition 缺少对应 UI intent")
        }

        var next = itemsBySessionID[patch.sessionId] ?? []
        if patch.replacesAll {
            guard let replacementItems = patch.replacementItems else {
                throw KMPConversationStoreError.invalidEvent("replace patch 缺少 replacementItems")
            }
            next = try replacementItems.map { snapshot in
                guard let item = snapshot.item else {
                    throw KMPConversationStoreError.invalidEvent("replacement item wire value 无效")
                }
                return item
            }
            guard Set(next.map(\.id)).count == next.count else {
                throw KMPConversationStoreError.invalidEvent("replacement item id 重复")
            }
        } else {
            for operation in patch.operations {
                switch operation.kind {
                case "insert":
                    guard let item = operation.item?.item,
                          operation.itemId == nil,
                          operation.delta == nil,
                          operation.epochSeconds == nil,
                          !next.contains(where: { $0.id == item.id }) else {
                        throw KMPConversationStoreError.invalidEvent("insert operation 无效")
                    }
                    next.append(item)
                case "append-text":
                    guard operation.item == nil,
                          let itemID = operation.itemId, !itemID.isEmpty,
                          let delta = operation.delta, !delta.isEmpty,
                          let epochSeconds = operation.epochSeconds, epochSeconds.isFinite,
                          let index = next.firstIndex(where: { $0.id == itemID }) else {
                        throw KMPConversationStoreError.invalidEvent("append-text operation 无效")
                    }
                    let old = next[index]
                    next[index] = ConversationItem(
                        id: old.id,
                        kind: old.kind,
                        title: old.title,
                        text: old.text + delta,
                        images: old.images,
                        isError: old.isError,
                        date: Date(timeIntervalSince1970: epochSeconds)
                    )
                case "replace":
                    guard let item = operation.item?.item,
                          let itemID = operation.itemId, item.id == itemID,
                          operation.delta == nil,
                          operation.epochSeconds == nil,
                          let index = next.firstIndex(where: { $0.id == itemID }) else {
                        throw KMPConversationStoreError.invalidEvent("replace operation 无效")
                    }
                    next[index] = item
                case "remove":
                    guard operation.item == nil,
                          let itemID = operation.itemId, !itemID.isEmpty,
                          operation.delta == nil,
                          operation.epochSeconds == nil,
                          let index = next.firstIndex(where: { $0.id == itemID }) else {
                        throw KMPConversationStoreError.invalidEvent("remove operation 无效")
                    }
                    next.remove(at: index)
                default:
                    throw KMPConversationStoreError.invalidEvent("未知 operation：\(operation.kind)")
                }
            }
        }
        itemsBySessionID[patch.sessionId] = next
        lastSequenceBySessionID[patch.sessionId] = patch.lastSequence
        return KMPConversationChange(
            sessionID: patch.sessionId,
            items: next,
            replacesAll: patch.replacesAll
        )
    }

    private func encode<T: Encodable>(_ value: T) throws -> String {
        String(decoding: try encoder.encode(value), as: UTF8.self)
    }

    private func failClosed(_ error: KMPConversationStoreError) {
        if runtimeError == nil { runtimeError = error }
        pushedError = error
        onError?(error)
    }
}

// MARK: - Trajectory incremental MVI

private struct KMPTrajectoryBootstrap: Decodable { let schema: Int }

private struct KMPTrajectoryUsageSnapshot: Decodable {
    let uncachedInput: Int
    let cachedInput: Int
    let output: Int
    let reasoning: Int

    var usage: RequestTokenUsage {
        RequestTokenUsage(
            uncachedInput: uncachedInput,
            cachedInput: cachedInput,
            output: output,
            reasoning: reasoning
        )
    }
}

private struct KMPTrajectoryRequestSnapshot: Decodable {
    let number: Int
    let turn: Int?
    let step: Int?
    let provider: String?
    let model: String?
    let options: JSONValue?
    let usage: KMPTrajectoryUsageSnapshot
    let cumulativeUsage: KMPTrajectoryUsageSnapshot
    let toolCalls: Int
    let subtoolCalls: Int

    var request: TrajectoryRequest {
        TrajectoryRequest(
            number: number,
            turn: turn,
            step: step,
            provider: provider,
            model: model,
            options: options,
            usage: usage.usage,
            cumulativeUsage: cumulativeUsage.usage,
            toolCalls: toolCalls,
            subtoolCalls: subtoolCalls
        )
    }
}

private struct KMPTrajectoryToolSnapshot: Decodable {
    let hierarchy: String
    let schema: JSONValue?
    var tool: TrajectoryTool { TrajectoryTool(hierarchy: hierarchy, schema: schema) }
}

private struct KMPTrajectoryNodeSnapshot: Decodable {
    let id: String
    let kind: String
    let title: String
    let subtitle: String
    let startSequence: Int
    let endSequence: Int
    let startEpochSeconds: Double
    let endEpochSeconds: Double
    let records: [SessionEvent]
    let request: KMPTrajectoryRequestSnapshot?
    let tool: KMPTrajectoryToolSnapshot?

    var node: TrajectoryNode? {
        guard !id.isEmpty,
              startSequence <= endSequence,
              startEpochSeconds.isFinite,
              endEpochSeconds.isFinite,
              let kind = TrajectoryNode.Kind(kmpWireValue: kind) else { return nil }
        return TrajectoryNode(
            id: id,
            kind: kind,
            title: title,
            subtitle: subtitle,
            startSeq: startSequence,
            endSeq: endSequence,
            start: Date(timeIntervalSince1970: startEpochSeconds),
            end: Date(timeIntervalSince1970: endEpochSeconds),
            records: records,
            request: request?.request,
            tool: tool?.tool
        )
    }
}

private extension TrajectoryNode.Kind {
    init?(kmpWireValue: String) {
        switch kmpWireValue {
        case "INPUT": self = .input
        case "CONTEXT": self = .context
        case "REQUEST": self = .request
        case "ASSISTANT": self = .assistant
        case "TOOL": self = .tool
        case "SUBTOOL": self = .subtool
        default: return nil
        }
    }
}

private struct KMPTrajectoryOperation: Decodable {
    let kind: String
    let item: KMPTrajectoryNodeSnapshot?
    let itemId: String?
    let index: Int?
    let subtitleDelta: String?
    let endSequence: Int?
    let endEpochSeconds: Double?
    let appendedRecords: [SessionEvent]
}

private struct KMPTrajectoryPatch: Decodable {
    let schema: Int
    let sessionId: String
    let operations: [KMPTrajectoryOperation]
    let replacesAll: Bool
    let replacementNodes: [KMPTrajectoryNodeSnapshot]?
    let lastSequence: Int
}

enum KMPTrajectoryStoreError: LocalizedError, Equatable {
    case encoding(String)
    case bridge(code: String, message: String?)
    case invalidEvent(String)
    case runtimeFailed(String)

    var errorDescription: String? {
        switch self {
        case .encoding(let message): "无法编码 iOS Trajectory 输入：\(message)"
        case .bridge(let code, let message): "KMP Trajectory 失败（\(code)）：\(message ?? "无详细信息")"
        case .invalidEvent(let message): "KMP Trajectory 增量事件无效：\(message)"
        case .runtimeFailed(let message): "KMP Trajectory 事件流已停止：\(message)"
        }
    }
}

protocol KMPTrajectoryStoreBridging: AnyObject {
    func receiveEvent(eventJson: String) -> SharedMviDispatchResult
    func receiveEvents(eventsJson: String) -> SharedMviDispatchResult
    func replaceSession(sessionId: String, eventsJson: String) -> SharedMviDispatchResult
    func clearSession(sessionId: String) -> SharedMviDispatchResult
}

extension SharedTrajectoryStore: KMPTrajectoryStoreBridging {}

protocol KMPTrajectoryEventBridging: AnyObject {
    func observeTrajectoryEvents(_ handler: @escaping (SharedMviEvent) -> Void) -> () -> Void
}

extension SharedTrajectoryStore: KMPTrajectoryEventBridging {
    func observeTrajectoryEvents(_ handler: @escaping (SharedMviEvent) -> Void) -> () -> Void {
        let observer = KMPSharedMviEventObserver(handler: handler)
        let subscription = subscribe(observer: observer)
        return {
            _ = observer
            subscription.cancel()
        }
    }
}

private enum KMPTrajectoryIntent {
    case event(sessionID: String, sequence: Int)
    case replace(sessionID: String, lastSequence: Int)
    case clear(sessionID: String)
}

struct KMPTrajectoryChange {
    let sessionID: String
    let nodes: [TrajectoryNode]
    let replacesAll: Bool
}

@MainActor
final class KMPTrajectoryStoreAdapter {
    private let store: any KMPTrajectoryStoreBridging
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()
    private var cancelEventSubscription: (() -> Void)?
    private var pendingIntent: KMPTrajectoryIntent?
    private var pushedError: KMPTrajectoryStoreError?
    private(set) var nodesBySessionID: [String: [TrajectoryNode]] = [:]
    private(set) var lastEventSequence: Int64?
    private(set) var runtimeError: KMPTrajectoryStoreError?
    var onChange: ((KMPTrajectoryChange) -> Void)?
    var onError: ((KMPTrajectoryStoreError) -> Void)?
    var isOperational: Bool { runtimeError == nil }

    init(
        bridge: (any KMPTrajectoryStoreBridging)? = nil,
        facade: SharedMobileFacade = SharedMobileFacade()
    ) {
        store = bridge ?? facade.makeTrajectoryStore()
        guard let eventStore = store as? any KMPTrajectoryEventBridging else {
            failClosed(.runtimeFailed("Trajectory bridge 不支持 MVI event stream"))
            return
        }
        cancelEventSubscription = eventStore.observeTrajectoryEvents { [weak self] event in
            MainActor.assumeIsolated { self?.receive(event) }
        }
    }

    func nodes(for sessionID: String) -> [TrajectoryNode] {
        nodesBySessionID[sessionID] ?? []
    }

    func receive(_ event: SessionEvent) throws {
        try dispatch(.event(sessionID: event.sessionId, sequence: event.seq)) {
            store.receiveEvent(eventJson: try encode(event))
        }
    }

    func receive(_ events: [SessionEvent]) throws {
        guard let first = events.first, let last = events.last else { return }
        guard events.allSatisfy({ $0.sessionId == first.sessionId }) else {
            throw KMPTrajectoryStoreError.encoding("Trajectory batch 包含多个 session")
        }
        try dispatch(.event(sessionID: first.sessionId, sequence: last.seq)) {
            store.receiveEvents(eventsJson: try encode(events))
        }
    }

    func replace(sessionID: String, events: [SessionEvent]) throws {
        try dispatch(.replace(sessionID: sessionID, lastSequence: events.map(\.seq).max() ?? -1)) {
            store.replaceSession(sessionId: sessionID, eventsJson: try encode(events))
        }
    }

    func clear(sessionID: String) throws {
        try dispatch(.clear(sessionID: sessionID)) {
            store.clearSession(sessionId: sessionID)
        }
    }

    private func dispatch(
        _ intent: KMPTrajectoryIntent,
        operation: () throws -> SharedMviDispatchResult
    ) throws {
        if let runtimeError { throw KMPTrajectoryStoreError.runtimeFailed(runtimeError.localizedDescription) }
        pendingIntent = intent
        pushedError = nil
        defer { pendingIntent = nil }
        let result: SharedMviDispatchResult
        do { result = try operation() }
        catch { throw KMPTrajectoryStoreError.encoding(error.localizedDescription) }
        if let pushedError { throw pushedError }
        guard result.accepted else {
            throw KMPTrajectoryStoreError.bridge(
                code: result.errorCode ?? "unknown-error",
                message: result.errorMessage
            )
        }
    }

    private func receive(_ event: SharedMviEvent) {
        guard event.schema == 2,
              event.domain == "trajectory",
              !event.transactionId.isEmpty else {
            failClosed(.invalidEvent("MVI 信封无效"))
            return
        }
        if event.kind == "snapshot" {
            guard lastEventSequence == nil,
                  let payload = event.statePayloadJson,
                  let bootstrap = try? decoder.decode(KMPTrajectoryBootstrap.self, from: Data(payload.utf8)),
                  bootstrap.schema == 1 else {
                failClosed(.invalidEvent("订阅基线无效"))
                return
            }
            lastEventSequence = event.sequence
            return
        }
        guard let previous = lastEventSequence, event.sequence == previous + 1 else {
            failClosed(.runtimeFailed("MVI event sequence 不连续"))
            return
        }
        lastEventSequence = event.sequence
        switch event.kind {
        case "error":
            let error = KMPTrajectoryStoreError.bridge(
                code: event.errorCode ?? "unknown-error",
                message: event.errorMessage
            )
            pushedError = error
            onError?(error)
        case "transition":
            guard let payload = event.statePayloadJson else {
                failClosed(.invalidEvent("transition 缺少 patch"))
                return
            }
            do {
                let patch = try decoder.decode(KMPTrajectoryPatch.self, from: Data(payload.utf8))
                let change = try apply(patch, intent: pendingIntent)
                onChange?(change)
            } catch let error as KMPTrajectoryStoreError {
                failClosed(error)
            } catch {
                failClosed(.invalidEvent(error.localizedDescription))
            }
        default:
            failClosed(.runtimeFailed("未知 MVI event kind：\(event.kind)"))
        }
    }

    private func apply(
        _ patch: KMPTrajectoryPatch,
        intent: KMPTrajectoryIntent?
    ) throws -> KMPTrajectoryChange {
        guard patch.schema == 1, !patch.sessionId.isEmpty else {
            throw KMPTrajectoryStoreError.invalidEvent("patch schema 或 sessionId 无效")
        }
        switch intent {
        case .event(let sessionID, let sequence):
            guard patch.sessionId == sessionID,
                  patch.lastSequence == sequence,
                  !patch.replacesAll,
                  patch.replacementNodes == nil,
                  !patch.operations.isEmpty else {
                throw KMPTrajectoryStoreError.invalidEvent("live patch 与 event intent 不一致")
            }
        case .replace(let sessionID, let lastSequence):
            guard patch.sessionId == sessionID,
                  patch.lastSequence == lastSequence,
                  patch.replacesAll,
                  patch.replacementNodes != nil,
                  patch.operations.isEmpty else {
                throw KMPTrajectoryStoreError.invalidEvent("replace patch 与 intent 不一致")
            }
        case .clear(let sessionID):
            guard patch.sessionId == sessionID,
                  patch.lastSequence == -1,
                  patch.replacesAll,
                  patch.replacementNodes?.isEmpty == true,
                  patch.operations.isEmpty else {
                throw KMPTrajectoryStoreError.invalidEvent("clear patch 与 intent 不一致")
            }
        case nil:
            throw KMPTrajectoryStoreError.invalidEvent("transition 缺少对应 intent")
        }

        var next = nodesBySessionID[patch.sessionId] ?? []
        if patch.replacesAll {
            next = try (patch.replacementNodes ?? []).map { snapshot in
                guard let node = snapshot.node else {
                    throw KMPTrajectoryStoreError.invalidEvent("replacement node wire value 无效")
                }
                return node
            }
            guard Set(next.map(\.id)).count == next.count else {
                throw KMPTrajectoryStoreError.invalidEvent("replacement node id 重复")
            }
        } else {
            for operation in patch.operations {
                try apply(operation, to: &next)
            }
        }
        nodesBySessionID[patch.sessionId] = next
        return KMPTrajectoryChange(
            sessionID: patch.sessionId,
            nodes: next,
            replacesAll: patch.replacesAll
        )
    }

    private func apply(
        _ operation: KMPTrajectoryOperation,
        to nodes: inout [TrajectoryNode]
    ) throws {
        switch operation.kind {
        case "insert":
            guard let node = operation.item?.node,
                  let index = operation.index,
                  (0...nodes.count).contains(index),
                  operation.itemId == nil,
                  !nodes.contains(where: { $0.id == node.id }) else {
                throw KMPTrajectoryStoreError.invalidEvent("insert operation 无效")
            }
            nodes.insert(node, at: index)
        case "remove":
            guard operation.item == nil,
                  let itemID = operation.itemId,
                  let index = nodes.firstIndex(where: { $0.id == itemID }) else {
                throw KMPTrajectoryStoreError.invalidEvent("remove operation 无效")
            }
            nodes.remove(at: index)
        case "move":
            guard operation.item == nil,
                  let itemID = operation.itemId,
                  let source = nodes.firstIndex(where: { $0.id == itemID }),
                  let target = operation.index,
                  nodes.indices.contains(target) else {
                throw KMPTrajectoryStoreError.invalidEvent("move operation 无效")
            }
            let node = nodes.remove(at: source)
            nodes.insert(node, at: target)
        case "replace":
            guard let node = operation.item?.node,
                  operation.itemId == node.id,
                  let index = nodes.firstIndex(where: { $0.id == node.id }) else {
                throw KMPTrajectoryStoreError.invalidEvent("replace operation 无效")
            }
            nodes[index] = node
        case "update":
            guard operation.item == nil,
                  let itemID = operation.itemId,
                  let index = nodes.firstIndex(where: { $0.id == itemID }),
                  let subtitleDelta = operation.subtitleDelta,
                  let endSequence = operation.endSequence,
                  let endEpochSeconds = operation.endEpochSeconds,
                  endEpochSeconds.isFinite,
                  endSequence >= nodes[index].endSeq,
                  (!subtitleDelta.isEmpty || !operation.appendedRecords.isEmpty || endSequence > nodes[index].endSeq) else {
                throw KMPTrajectoryStoreError.invalidEvent("update operation 无效")
            }
            nodes[index].subtitle += subtitleDelta
            nodes[index].endSeq = endSequence
            nodes[index].end = Date(timeIntervalSince1970: endEpochSeconds)
            nodes[index].records.append(contentsOf: operation.appendedRecords)
        default:
            throw KMPTrajectoryStoreError.invalidEvent("未知 operation：\(operation.kind)")
        }
    }

    private func encode<T: Encodable>(_ value: T) throws -> String {
        String(decoding: try encoder.encode(value), as: UTF8.self)
    }

    private func failClosed(_ error: KMPTrajectoryStoreError) {
        if runtimeError == nil { runtimeError = error }
        pushedError = error
        onError?(error)
    }
}

// MARK: - History incremental MVI

private struct KMPHistoryLoadProgressSnapshot: Decodable {
    let loaded: Int
    let total: Int?
}

private struct KMPHistorySessionSnapshot: Decodable {
    let hasMore: Bool
    let isLoading: Bool
    let isLoadingOlder: Bool
    let progress: KMPHistoryLoadProgressSnapshot?
    let seenCursors: [Int]
    let nextBeforeSequence: Int?
    let pageCount: Int
    let batchKind: String?
    let loadedEventCount: Int
    let loadedByteCount: Int
    let syncedActivityTimestamp: Double?

    var isValid: Bool {
        pageCount >= 0 && loadedEventCount >= 0 && loadedByteCount >= 0
            && (!isLoadingOlder || isLoading)
            && (syncedActivityTimestamp?.isFinite ?? true)
            && (progress.map { progress in
                progress.loaded >= 0 && (progress.total.map { $0 >= progress.loaded } ?? true)
            } ?? true)
            && (batchKind == nil || batchKind == "LATEST" || batchKind == "OLDER")
    }

    var uiProgress: HistoryLoadProgress? {
        progress.map { HistoryLoadProgress(loaded: $0.loaded, total: $0.total) }
    }
}

private struct KMPHistoryStateSnapshot: Decodable {
    let sessions: [String: KMPHistorySessionSnapshot]
    let pendingSessionId: String?
}

private struct KMPHistoryBootstrap: Decodable {
    let schema: Int
    let state: KMPHistoryStateSnapshot
    let eventsBySession: [String: [SessionEvent]]
}

private struct KMPHistoryEventPatch: Decodable {
    let kind: String
    let record: SessionEvent?
    let index: Int?
    let replacementEvents: [SessionEvent]?
}

private struct KMPHistoryPatch: Decodable {
    let schema: Int
    let sessionId: String
    let session: KMPHistorySessionSnapshot?
    let pendingSessionId: String?
    let pendingSessionChanged: Bool
    let eventPatch: KMPHistoryEventPatch?
    let outcome: String
    let failureCode: String?
    let completedEventCount: Int?
    let completedByteCount: Int?
    let completedHasMore: Bool?
}

struct KMPHistoryEffect: Decodable, Equatable {
    let action: String
    let sessionId: String
    let beforeSequence: Int?
}

enum KMPHistoryStoreError: LocalizedError, Equatable {
    case encoding(String)
    case bridge(code: String, message: String?)
    case invalidEvent(String)
    case runtimeFailed(String)

    var errorDescription: String? {
        switch self {
        case .encoding(let message): "无法编码 iOS History 输入：\(message)"
        case .bridge(let code, let message): "KMP History 失败（\(code)）：\(message ?? "无详细信息")"
        case .invalidEvent(let message): "KMP History 增量事件无效：\(message)"
        case .runtimeFailed(let message): "KMP History 事件流已停止：\(message)"
        }
    }
}

protocol KMPHistoryStoreBridging: AnyObject {
    func start(
        sessionId: String,
        older: Bool,
        hasLocalEvents: Bool,
        earliestLocalSequence: KotlinInt?
    ) -> SharedMviDispatchResult
    func processingStarted(sessionId: String, rawEventCount: Int32, hasMore: Bool) -> SharedMviDispatchResult
    func pageReceived(
        sessionId: String,
        eventsJson: String,
        byteCount: Int32,
        hasMore: Bool,
        nextBeforeSequence: KotlinInt?,
        remoteActivityTimestamp: KotlinDouble?
    ) -> SharedMviDispatchResult
    func liveEventReceived(eventJson: String) -> SharedMviDispatchResult
    func timedOut(sessionId: String) -> SharedMviDispatchResult
    func cancelled(sessionId: String) -> SharedMviDispatchResult
    func clearSession(sessionId: String) -> SharedMviDispatchResult
}

extension SharedHistoryStore: KMPHistoryStoreBridging {}

protocol KMPHistoryEventBridging: AnyObject {
    func observeHistoryEvents(_ handler: @escaping (SharedMviEvent) -> Void) -> () -> Void
}

extension SharedHistoryStore: KMPHistoryEventBridging {
    func observeHistoryEvents(_ handler: @escaping (SharedMviEvent) -> Void) -> () -> Void {
        let observer = KMPSharedMviEventObserver(handler: handler)
        let subscription = subscribe(observer: observer)
        return {
            _ = observer
            subscription.cancel()
        }
    }
}

private enum KMPHistoryIntent {
    case start(sessionID: String)
    case processing(sessionID: String)
    case page(sessionID: String)
    case live(sessionID: String, sequence: Int)
    case timeout(sessionID: String)
    case cancel(sessionID: String)
    case clear(sessionID: String)

    var sessionID: String {
        switch self {
        case .start(let id), .processing(let id), .page(let id), .live(let id, _),
             .timeout(let id), .cancel(let id), .clear(let id): id
        }
    }
}

struct KMPHistoryChange {
    let sessionID: String
    let events: [SessionEvent]
    let eventPatchKind: String?
    let eventRecord: SessionEvent?
    let hasMore: [String: Bool]
    let loadingSessionIDs: Set<String>
    let loadingOlderSessionIDs: Set<String>
    let progress: [String: HistoryLoadProgress]
    let outcome: String
    let failureCode: String?
    let completedEventCount: Int?
    let completedByteCount: Int?
    let completedHasMore: Bool?
    let effect: KMPHistoryEffect?
}

@MainActor
final class KMPHistoryStoreAdapter {
    private let store: any KMPHistoryStoreBridging
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()
    private var cancelEventSubscription: (() -> Void)?
    private var pendingIntent: KMPHistoryIntent?
    private var pushedError: KMPHistoryStoreError?
    private var sessions: [String: KMPHistorySessionSnapshot] = [:]
    private(set) var eventsBySessionID: [String: [SessionEvent]] = [:]
    private(set) var pendingSessionID: String?
    private(set) var lastEventSequence: Int64?
    private(set) var runtimeError: KMPHistoryStoreError?
    var onChange: ((KMPHistoryChange) -> Void)?
    var onError: ((KMPHistoryStoreError) -> Void)?
    var isOperational: Bool { runtimeError == nil }

    init(
        bridge: (any KMPHistoryStoreBridging)? = nil,
        facade: SharedMobileFacade = SharedMobileFacade()
    ) {
        store = bridge ?? facade.makeHistoryStore()
        guard let eventStore = store as? any KMPHistoryEventBridging else {
            failClosed(.runtimeFailed("History bridge 不支持 MVI event stream"))
            return
        }
        cancelEventSubscription = eventStore.observeHistoryEvents { [weak self] event in
            MainActor.assumeIsolated { self?.receive(event) }
        }
    }

    func events(for sessionID: String) -> [SessionEvent] { eventsBySessionID[sessionID] ?? [] }
    func syncedActivityTimestamp(for sessionID: String) -> Double? { sessions[sessionID]?.syncedActivityTimestamp }

    func start(sessionID: String, older: Bool, hasLocalEvents: Bool, earliestLocalSequence: Int?) throws {
        try dispatch(.start(sessionID: sessionID)) {
            store.start(
                sessionId: sessionID,
                older: older,
                hasLocalEvents: hasLocalEvents,
                earliestLocalSequence: try kotlinInt(earliestLocalSequence)
            )
        }
    }

    func processingStarted(sessionID: String, rawEventCount: Int, hasMore: Bool) throws {
        try dispatch(.processing(sessionID: sessionID)) {
            store.processingStarted(
                sessionId: sessionID,
                rawEventCount: try int32(rawEventCount),
                hasMore: hasMore
            )
        }
    }

    func pageReceived(
        sessionID: String,
        events: [SessionEvent],
        byteCount: Int,
        hasMore: Bool,
        nextBeforeSequence: Int?,
        remoteActivityTimestamp: Double?
    ) throws {
        try dispatch(.page(sessionID: sessionID)) {
            store.pageReceived(
                sessionId: sessionID,
                eventsJson: try encode(events),
                byteCount: try int32(byteCount),
                hasMore: hasMore,
                nextBeforeSequence: try kotlinInt(nextBeforeSequence),
                remoteActivityTimestamp: remoteActivityTimestamp.map { KotlinDouble(double: $0) }
            )
        }
    }

    func receive(_ event: SessionEvent) throws {
        try dispatch(.live(sessionID: event.sessionId, sequence: event.seq)) {
            store.liveEventReceived(eventJson: try encode(event))
        }
    }

    func timedOut(sessionID: String) throws {
        try dispatch(.timeout(sessionID: sessionID)) { store.timedOut(sessionId: sessionID) }
    }

    func cancel(sessionID: String) throws {
        try dispatch(.cancel(sessionID: sessionID)) { store.cancelled(sessionId: sessionID) }
    }

    func clear(sessionID: String) throws {
        try dispatch(.clear(sessionID: sessionID)) { store.clearSession(sessionId: sessionID) }
    }

    private func dispatch(
        _ intent: KMPHistoryIntent,
        operation: () throws -> SharedMviDispatchResult
    ) throws {
        if let runtimeError { throw KMPHistoryStoreError.runtimeFailed(runtimeError.localizedDescription) }
        pendingIntent = intent
        pushedError = nil
        defer { pendingIntent = nil }
        let result: SharedMviDispatchResult
        do { result = try operation() }
        catch { throw KMPHistoryStoreError.encoding(error.localizedDescription) }
        if let pushedError { throw pushedError }
        guard result.accepted else {
            throw KMPHistoryStoreError.bridge(
                code: result.errorCode ?? "unknown-error",
                message: result.errorMessage
            )
        }
    }

    private func receive(_ event: SharedMviEvent) {
        guard event.schema == 2, event.domain == "history", !event.transactionId.isEmpty else {
            failClosed(.invalidEvent("MVI 信封无效"))
            return
        }
        if event.kind == "snapshot" {
            guard lastEventSequence == nil,
                  let payload = event.statePayloadJson,
                  let bootstrap = try? decoder.decode(KMPHistoryBootstrap.self, from: Data(payload.utf8)),
                  bootstrap.schema == 1,
                  bootstrap.state.sessions.values.allSatisfy(\.isValid),
                  bootstrap.eventsBySession.allSatisfy({ key, records in
                      records.allSatisfy { $0.sessionId == key }
                          && records.map(\.seq) == records.map(\.seq).sorted()
                          && Set(records.map(\.seq)).count == records.count
                  }) else {
                failClosed(.invalidEvent("订阅基线无效"))
                return
            }
            sessions = bootstrap.state.sessions
            pendingSessionID = bootstrap.state.pendingSessionId
            eventsBySessionID = bootstrap.eventsBySession
            lastEventSequence = event.sequence
            return
        }
        guard let previous = lastEventSequence, event.sequence == previous + 1 else {
            failClosed(.runtimeFailed("MVI event sequence 不连续"))
            return
        }
        lastEventSequence = event.sequence
        switch event.kind {
        case "error":
            let error = KMPHistoryStoreError.bridge(
                code: event.errorCode ?? "unknown-error",
                message: event.errorMessage
            )
            pushedError = error
            onError?(error)
        case "transition":
            guard let payload = event.statePayloadJson else {
                failClosed(.invalidEvent("transition 缺少 patch"))
                return
            }
            do {
                let patch = try decoder.decode(KMPHistoryPatch.self, from: Data(payload.utf8))
                let effects = try decoder.decode([KMPHistoryEffect].self, from: Data(event.effectsJson.utf8))
                guard effects.count <= 1 else { throw KMPHistoryStoreError.invalidEvent("effect 数量无效") }
                let change = try apply(patch, effect: effects.first, intent: pendingIntent)
                onChange?(change)
            } catch let error as KMPHistoryStoreError {
                failClosed(error)
            } catch {
                failClosed(.invalidEvent(error.localizedDescription))
            }
        default:
            failClosed(.runtimeFailed("未知 MVI event kind：\(event.kind)"))
        }
    }

    private func apply(
        _ patch: KMPHistoryPatch,
        effect: KMPHistoryEffect?,
        intent: KMPHistoryIntent?
    ) throws -> KMPHistoryChange {
        guard patch.schema == 1,
              !patch.sessionId.isEmpty,
              let intent,
              intent.sessionID == patch.sessionId,
              patch.session?.isValid ?? true,
              patch.pendingSessionChanged || patch.pendingSessionId == pendingSessionID,
              ["none", "request-page", "stopped", "completed", "failed"].contains(patch.outcome),
              (patch.outcome == "failed") == (patch.failureCode != nil),
              (patch.outcome == "completed") == (patch.completedEventCount != nil),
              (patch.outcome == "completed") == (patch.completedByteCount != nil),
              (patch.outcome == "completed") == (patch.completedHasMore != nil),
              (patch.completedEventCount.map { $0 >= 0 } ?? true),
              (patch.completedByteCount.map { $0 >= 0 } ?? true) else {
            throw KMPHistoryStoreError.invalidEvent("patch 与 intent 或 wire state 不一致")
        }
        if let effect {
            guard effect.action == "request-page",
                  effect.sessionId == patch.sessionId,
                  patch.outcome == "request-page",
                  intentMatchesRequestEffect(intent) else {
                throw KMPHistoryStoreError.invalidEvent("effect 与同事务 intent/state 不一致")
            }
        } else if patch.outcome == "request-page" {
            throw KMPHistoryStoreError.invalidEvent("request-page 缺少 effect")
        }

        var nextSessions = sessions
        if let session = patch.session { nextSessions[patch.sessionId] = session }
        else { nextSessions.removeValue(forKey: patch.sessionId) }
        let nextPending = patch.pendingSessionChanged ? patch.pendingSessionId : pendingSessionID
        var nextEvents = eventsBySessionID[patch.sessionId] ?? []
        if let eventPatch = patch.eventPatch {
            try apply(eventPatch, sessionID: patch.sessionId, intent: intent, to: &nextEvents)
        } else if case .live = intent {
            throw KMPHistoryStoreError.invalidEvent("live intent 缺少 event patch")
        }

        sessions = nextSessions
        pendingSessionID = nextPending
        if nextEvents.isEmpty { eventsBySessionID.removeValue(forKey: patch.sessionId) }
        else { eventsBySessionID[patch.sessionId] = nextEvents }
        return makeChange(
            sessionID: patch.sessionId,
            events: nextEvents,
            eventPatchKind: patch.eventPatch?.kind,
            eventRecord: patch.eventPatch?.record,
            outcome: patch.outcome,
            failureCode: patch.failureCode,
            completedEventCount: patch.completedEventCount,
            completedByteCount: patch.completedByteCount,
            completedHasMore: patch.completedHasMore,
            effect: effect
        )
    }

    private func apply(
        _ patch: KMPHistoryEventPatch,
        sessionID: String,
        intent: KMPHistoryIntent,
        to records: inout [SessionEvent]
    ) throws {
        switch patch.kind {
        case "append":
            guard case .live(let expectedSession, let expectedSequence) = intent,
                  expectedSession == sessionID,
                  let record = patch.record,
                  record.sessionId == sessionID,
                  record.seq == expectedSequence,
                  patch.index == records.count,
                  patch.replacementEvents == nil,
                  record.seq > (records.last?.seq ?? -1) else {
                throw KMPHistoryStoreError.invalidEvent("append event patch 无效")
            }
            records.append(record)
        case "upsert":
            guard case .live(let expectedSession, let expectedSequence) = intent,
                  expectedSession == sessionID,
                  let record = patch.record,
                  record.sessionId == sessionID,
                  record.seq == expectedSequence,
                  let index = patch.index,
                  (0...records.count).contains(index),
                  patch.replacementEvents == nil else {
                throw KMPHistoryStoreError.invalidEvent("upsert event patch 无效")
            }
            if index < records.count, records[index].seq == record.seq { records[index] = record }
            else { records.insert(record, at: index) }
            guard records.map(\.seq) == records.map(\.seq).sorted(),
                  Set(records.map(\.seq)).count == records.count else {
                throw KMPHistoryStoreError.invalidEvent("upsert 后事件顺序无效")
            }
        case "replace":
            guard isPageOrClear(intent),
                  patch.record == nil,
                  patch.index == nil,
                  let replacement = patch.replacementEvents,
                  replacement.allSatisfy({ $0.sessionId == sessionID }),
                  replacement.map(\.seq) == replacement.map(\.seq).sorted(),
                  Set(replacement.map(\.seq)).count == replacement.count else {
                throw KMPHistoryStoreError.invalidEvent("replace event patch 无效")
            }
            records = replacement
        default:
            throw KMPHistoryStoreError.invalidEvent("未知 event patch：\(patch.kind)")
        }
    }

    private func makeChange(
        sessionID: String,
        events: [SessionEvent],
        eventPatchKind: String?,
        eventRecord: SessionEvent?,
        outcome: String,
        failureCode: String?,
        completedEventCount: Int?,
        completedByteCount: Int?,
        completedHasMore: Bool?,
        effect: KMPHistoryEffect?
    ) -> KMPHistoryChange {
        KMPHistoryChange(
            sessionID: sessionID,
            events: events,
            eventPatchKind: eventPatchKind,
            eventRecord: eventRecord,
            hasMore: sessions.mapValues(\.hasMore),
            loadingSessionIDs: Set(sessions.compactMap { $0.value.isLoading ? $0.key : nil }),
            loadingOlderSessionIDs: Set(sessions.compactMap { $0.value.isLoadingOlder ? $0.key : nil }),
            progress: Dictionary(uniqueKeysWithValues: sessions.compactMap { key, value in
                value.uiProgress.map { (key, $0) }
            }),
            outcome: outcome,
            failureCode: failureCode,
            completedEventCount: completedEventCount,
            completedByteCount: completedByteCount,
            completedHasMore: completedHasMore,
            effect: effect
        )
    }

    private func intentMatchesRequestEffect(_ intent: KMPHistoryIntent) -> Bool {
        switch intent { case .start, .page: true; default: false }
    }

    private func isClear(_ intent: KMPHistoryIntent) -> Bool {
        if case .clear = intent { return true }
        return false
    }

    private func isPageOrClear(_ intent: KMPHistoryIntent) -> Bool {
        switch intent {
        case .page, .clear: true
        default: false
        }
    }

    private func int32(_ value: Int) throws -> Int32 {
        guard let exact = Int32(exactly: value) else { throw KMPHistoryStoreError.encoding("Int 超出 KMP Int 范围") }
        return exact
    }

    private func kotlinInt(_ value: Int?) throws -> KotlinInt? {
        try value.map { KotlinInt(int: try int32($0)) }
    }

    private func encode<T: Encodable>(_ value: T) throws -> String {
        String(decoding: try encoder.encode(value), as: UTF8.self)
    }

    private func failClosed(_ error: KMPHistoryStoreError) {
        if runtimeError == nil { runtimeError = error }
        pushedError = error
        onError?(error)
    }
}

struct KMPSessionSummarySnapshot: Codable, Equatable {
    var id: String
    var title: String
    var lastActivityEpochSeconds: Double
    var isRunning: Bool
    var hasUnread: Bool
    var agentPreset: String?

    init(_ session: SessionSummary) {
        id = session.id
        title = session.title
        lastActivityEpochSeconds = session.lastActivity.timeIntervalSince1970
        isRunning = session.isRunning
        hasUnread = session.hasUnread
        agentPreset = session.agentPreset
    }

    var persistedSession: SessionSummary {
        SessionSummary(
            id: id,
            title: title,
            lastActivity: Date(timeIntervalSince1970: lastActivityEpochSeconds),
            isRunning: isRunning,
            hasUnread: hasUnread,
            agentPreset: agentPreset
        )
    }
}

/// KMP 会话状态与 iOS ObservableObject/UserDefaults 模型之间的显式值映射。
struct KMPSessionListSnapshot: Codable, Equatable {
    var sessions: [KMPSessionSummarySnapshot]
    var archivedSessionIds: [String]
    var selectedSessionId: String?

    init(
        sessions: [SessionSummary],
        archivedSessionIds: Set<String>,
        selectedSessionId: String?
    ) {
        self.sessions = sessions.map(KMPSessionSummarySnapshot.init)
        self.archivedSessionIds = archivedSessionIds.sorted()
        self.selectedSessionId = selectedSessionId
    }

    var persistedSessions: [SessionSummary] { sessions.map(\.persistedSession) }
    var archivedSessionIDSet: Set<String> { Set(archivedSessionIds) }
}

enum KMPSessionListStoreError: LocalizedError, Equatable {
    case encoding(String)
    case bridge(code: String, message: String?)
    case invalidSnapshot(String)
    case initializationFailed(String)
    case runtimeFailed(String)

    var errorDescription: String? {
        switch self {
        case .encoding(let message):
            "无法编码 iOS SessionList 输入：\(message)"
        case .bridge(let code, let message):
            "KMP SessionList 失败（\(code)）：\(message ?? "无详细信息")"
        case .invalidSnapshot(let message):
            "无法解码 KMP SessionList 快照：\(message)"
        case .initializationFailed(let message):
            "KMP SessionList 初始化失败，已停止后续状态写入：\(message)"
        case .runtimeFailed(let message):
            "KMP SessionList 运行期快照失效，已停止后续状态写入：\(message)"
        }
    }
}

/// 将 Kotlin 门面收窄成可替换边界，确保初始化失败路径可以在 Swift 单测中验证。
protocol KMPSessionListStoreBridging: AnyObject {
    func snapshot() -> SharedSessionListResult
    func restore(snapshotJson: String) -> SharedSessionListResult
    func selectSession(sessionId: String?) -> SharedSessionListResult
    func setArchivedSessionIds(sessionIdsJson: String) -> SharedSessionListResult
    func receiveRemoteSessions(sessionsJson: String) -> SharedSessionListResult
    func messageSent(
        sessionId: String,
        agentPreset: String?,
        insertedAtEpochSeconds: Double
    ) -> SharedSessionListResult
    func addKnownSession(sessionId: String, insertedAtEpochSeconds: Double) -> SharedSessionListResult
    func receiveEvent(eventJson: String, insertedAtEpochSeconds: Double) -> SharedSessionListResult
    func markRead(sessionId: String) -> SharedSessionListResult
}

extension SharedSessionListStore: KMPSessionListStoreBridging {}

private protocol KMPSessionListEventBridging: AnyObject {
    func subscribe(observer: SharedMviEventObserver) -> SharedMviSubscription
}

extension SharedSessionListStore: KMPSessionListEventBridging {}

/// AppStore 的 MainActor 是唯一串行入口；本适配器只映射稳定 JSON 值并调用 KMP。
@MainActor
final class KMPSessionListStoreAdapter {
    private let store: any KMPSessionListStoreBridging
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()
    private var eventObserver: KMPSharedMviEventObserver?
    private var eventSubscription: SharedMviSubscription?
    private var pushedSnapshot: KMPSessionListSnapshot?
    private var pushedError: KMPSessionListStoreError?
    private(set) var lastEventSequence: Int64?
    private var receivesPushedEvents = false
    var onSnapshot: ((KMPSessionListSnapshot?, KMPSessionListStoreError?) -> Void)?

    private(set) var snapshot: KMPSessionListSnapshot
    private(set) var initializationError: KMPSessionListStoreError?
    /// KMP 已接受 mutation、但返回的成功快照无法由 Swift 解码时，永久关闭该实例。
    /// 继续调用 bridge 会基于 Swift 不可见的 Kotlin 状态推进，造成 UI/持久化分叉。
    private(set) var runtimeError: KMPSessionListStoreError?
    var isInitialized: Bool { initializationError == nil }
    var isOperational: Bool { initializationError == nil && runtimeError == nil }

    init(
        sessions: [SessionSummary],
        archivedSessionIds: Set<String> = [],
        selectedSessionId: String? = nil,
        bridge: (any KMPSessionListStoreBridging)? = nil,
        facade: SharedMobileFacade = SharedMobileFacade()
    ) {
        let initial = KMPSessionListSnapshot(
            sessions: sessions,
            archivedSessionIds: archivedSessionIds,
            selectedSessionId: selectedSessionId
        )
        snapshot = initial
        store = bridge ?? facade.makeSessionListStore(
                newSessionTitle: L10n.newSessionPlaceholderTitle,
                remoteSessionPrefix: L10n.remoteSessionTitle(""),
                blankSessionPrefix: L10n.blankSessionTitle("")
            )
        do {
            let json = try encode(initial)
            snapshot = try decode(store.restore(snapshotJson: json))
        } catch let error as KMPSessionListStoreError {
            initializationError = error
        } catch {
            let mapped = KMPSessionListStoreError.invalidSnapshot(error.localizedDescription)
            initializationError = mapped
        }
        if initializationError == nil,
           let eventStore = store as? any KMPSessionListEventBridging {
            receivesPushedEvents = true
            let observer = KMPSharedMviEventObserver { [weak self] event in
                MainActor.assumeIsolated { self?.receive(event) }
            }
            eventObserver = observer
            eventSubscription = eventStore.subscribe(observer: observer)
        }
    }

    @discardableResult
    func reduce(_ action: KMPSessionListIntent, now: Date = .now) throws -> KMPSessionListSnapshot {
        if let initializationError {
            throw KMPSessionListStoreError.initializationFailed(
                initializationError.localizedDescription
            )
        }
        if let runtimeError {
            throw KMPSessionListStoreError.runtimeFailed(
                runtimeError.localizedDescription
            )
        }
        pushedSnapshot = nil
        pushedError = nil
        let result: SharedSessionListResult
        switch action {
        case .select(let sessionID):
            result = store.selectSession(sessionId: sessionID)
        case .setArchivedSessionIDs(let sessionIDs):
            result = store.setArchivedSessionIds(sessionIdsJson: try encode(sessionIDs.sorted()))
        case .remoteSessionsReceived(let sessions):
            result = store.receiveRemoteSessions(sessionsJson: try encode(sessions))
        case .messageSent(let sessionID, let agentPreset):
            result = store.messageSent(
                sessionId: sessionID,
                agentPreset: agentPreset,
                insertedAtEpochSeconds: now.timeIntervalSince1970
            )
        case .knownSessionAdded(let sessionID):
            result = store.addKnownSession(
                sessionId: sessionID,
                insertedAtEpochSeconds: now.timeIntervalSince1970
            )
        case .eventReceived(let event):
            result = store.receiveEvent(
                eventJson: try encode(event),
                insertedAtEpochSeconds: now.timeIntervalSince1970
            )
        case .markRead(let sessionID):
            result = store.markRead(sessionId: sessionID)
        }
        if receivesPushedEvents {
            if let pushedError { throw pushedError }
            if let pushedSnapshot { return pushedSnapshot }
            if result.isSuccess, result.snapshotJson == nil { return snapshot }
            let error = KMPSessionListStoreError.runtimeFailed(
                "KMP mutation 未发布对应的 MVI event"
            )
            runtimeError = error
            throw error
        }
        do {
            snapshot = try decode(result)
            // 仅用于不实现 Event 订阅的故障注入 bridge。产品 Store 只走推送路径。
            onSnapshot?(snapshot, nil)
        } catch let error as KMPSessionListStoreError {
            // 仅在 bridge 声称成功却交回 Swift 无法理解的快照时永久 fail-closed。
            // 编码错误发生在 bridge 调用前；显式 bridge 失败也没有隐藏的状态提交。
            if case .invalidSnapshot = error {
                runtimeError = error
            }
            throw error
        }
        return snapshot
    }

    func receive(_ event: SharedMviEvent) {
        guard event.schema == 2,
              event.domain == "session-list",
              !event.transactionId.isEmpty else {
            publish(error: .runtimeFailed("KMP SessionList event 信封无效"))
            return
        }
        if event.kind == "snapshot" {
            guard lastEventSequence == nil,
                  let payload = event.statePayloadJson,
                  let baseline = try? decoder.decode(
                      KMPSessionListSnapshot.self,
                      from: Data(payload.utf8)
                  ),
                  baseline == snapshot else {
                publish(error: .invalidSnapshot("订阅基线与初始化快照不一致"))
                return
            }
            lastEventSequence = event.sequence
            return
        }
        guard let previous = lastEventSequence,
              event.sequence == previous + 1 else {
            publish(error: .runtimeFailed("KMP SessionList event sequence 不连续"))
            return
        }
        lastEventSequence = event.sequence
        if event.kind == "error" {
            publish(error: .bridge(
                code: event.errorCode ?? "unknown-error",
                message: event.errorMessage
            ))
            return
        }
        guard event.kind == "transition" else {
            publish(error: .runtimeFailed("未知 SessionList event kind：\(event.kind)"))
            return
        }
        do {
            let next = try decode(SharedSessionListResult(
                snapshotJson: event.statePayloadJson,
                errorCode: nil,
                errorMessage: nil
            ))
            snapshot = next
            pushedSnapshot = next
            onSnapshot?(next, nil)
        } catch let error as KMPSessionListStoreError {
            publish(error: error)
        } catch {
            publish(error: .invalidSnapshot(error.localizedDescription))
        }
    }

    private func publish(error: KMPSessionListStoreError) {
        runtimeError = error
        pushedError = error
        onSnapshot?(nil, error)
    }

    private func encode<T: Encodable>(_ value: T) throws -> String {
        do {
            return String(decoding: try encoder.encode(value), as: UTF8.self)
        } catch {
            throw KMPSessionListStoreError.encoding(error.localizedDescription)
        }
    }

    private func decode(_ result: SharedSessionListResult) throws -> KMPSessionListSnapshot {
        guard result.isSuccess else {
            throw KMPSessionListStoreError.bridge(
                code: result.errorCode ?? "unknown-error",
                message: result.errorMessage
            )
        }
        // KMP 对无状态变化的流式事件返回空快照，避免反复跨桥复制完整列表。
        guard let json = result.snapshotJson else { return snapshot }
        do {
            return try decoder.decode(KMPSessionListSnapshot.self, from: Data(json.utf8))
        } catch {
            throw KMPSessionListStoreError.invalidSnapshot(error.localizedDescription)
        }
    }
}

struct KMPQuestionStatusSnapshot: Codable, Equatable {
    var kind: String
    var action: String?
    var failureCode: String?
    var failureArgument: String?

    var platformStatus: GatewayQuestionRequestStatus {
        switch kind {
        case "submitting":
            return .submitting(GatewayQuestionAction(rawValue: action ?? "") ?? .answer)
        case "accepted":
            return .accepted(GatewayQuestionAction(rawValue: action ?? "") ?? .answer)
        case "rejected":
            return .rejected(localizedFailure)
        default:
            return .idle
        }
    }

    private var localizedFailure: String {
        switch failureCode {
        case "DISCONNECTED_ANSWER":
            String(localized: "q.rejected.ws.disconnected.answer", defaultValue: "WebSocket 已断开，重连后再提交答案。")
        case "DISCONNECTED_CANCEL":
            String(localized: "q.rejected.ws.disconnected.skip", defaultValue: "WebSocket 已断开，重连后再跳过问题。")
        case "INVALID_ANSWER_ORDER":
            String(localized: "答案必须按原顺序覆盖整组问题。")
        case "INVALID_OR_DUPLICATE_OPTIONS":
            String(
                format: String(localized: "q.rejected.bad-options", defaultValue: "“%@”包含无效或重复选项。"),
                failureArgument ?? ""
            )
        case "SINGLE_SELECTION_REQUIRED":
            String(localized: "单选题只能选择一个选项，且不能同时填写自定义答案。")
        case "REQUEST_FAILED":
            if let failureArgument, !failureArgument.isEmpty {
                failureArgument
            } else {
                String(localized: "服务端拒绝了问题响应。")
            }
        case "SERVER_REJECTED":
            String(
                format: String(localized: "q.rejected.server-refused", defaultValue: "服务端未接受答案（%@），请检查后重试。"),
                failureArgument ?? "bad-response"
            )
        default:
            String(localized: "服务端拒绝了问题响应。")
        }
    }
}

struct KMPQuestionSnapshot: Codable, Equatable {
    var pendingRequests: [GatewayPendingQuestionRequest]
    var requestStatuses: [String: KMPQuestionStatusSnapshot]

    var platformStatuses: [String: GatewayQuestionRequestStatus] {
        requestStatuses.mapValues(\.platformStatus)
    }

    static let empty = KMPQuestionSnapshot(pendingRequests: [], requestStatuses: [:])

    var hasValidWireValues: Bool {
        let validFailureCodes = Set([
            "DISCONNECTED_ANSWER", "DISCONNECTED_CANCEL", "INVALID_ANSWER_ORDER",
            "INVALID_OR_DUPLICATE_OPTIONS", "SINGLE_SELECTION_REQUIRED",
            "SERVER_REJECTED", "REQUEST_FAILED"
        ])
        return Set(pendingRequests.map(\.rpcId)).count == pendingRequests.count
            && requestStatuses.values.allSatisfy { status in
                switch status.kind {
                case "idle": status.action == nil && status.failureCode == nil
                case "submitting", "accepted":
                    status.action.flatMap(GatewayQuestionAction.init(rawValue:)) != nil
                        && status.failureCode == nil
                case "rejected": status.action == nil && status.failureCode.map(validFailureCodes.contains) == true
                default: false
                }
            }
    }
}

struct KMPQuestionEffect: Codable, Equatable {
    var action: String
    var rpcId: String
    var sessionId: String
    var answers: [GatewayQuestionAnswer]?

    var hasValidWireValues: Bool {
        switch GatewayQuestionAction(rawValue: action) {
        case .answer: answers != nil
        case .cancel: answers == nil
        case nil: false
        }
    }
}

enum KMPQuestionIntent {
    case reset
    case requestReceived(GatewayPendingQuestionRequest)
    case submitAnswer(rpcID: String, answers: [GatewayQuestionAnswer], isConnected: Bool)
    case submitCancel(rpcID: String, isConnected: Bool)
    case responseReceived(rpcID: String, action: GatewayQuestionAction, accepted: Bool, reason: String?)
    case resolved(rpcID: String)
    case requestFailed(rpcID: String, message: String)
    case sessionRequestsFailed(sessionID: String, message: String)
}

enum KMPQuestionStoreError: LocalizedError, Equatable {
    case encoding(String)
    case bridge(code: String, message: String?)
    case invalidSnapshot(String)
    case invalidEffect(String)
    case initializationFailed(String)
    case runtimeFailed(String)

    var errorDescription: String? {
        switch self {
        case .encoding(let message): "无法编码 iOS Question 输入：\(message)"
        case .bridge(let code, let message): "KMP Question 失败（\(code)）：\(message ?? "无详细信息")"
        case .invalidSnapshot(let message): "无法解码 KMP Question 快照：\(message)"
        case .invalidEffect(let message): "无法解码 KMP Question effect：\(message)"
        case .initializationFailed(let message): "KMP Question 初始化失败，已停止后续状态写入：\(message)"
        case .runtimeFailed(let message): "KMP Question 运行期结果失效，已停止后续状态写入：\(message)"
        }
    }
}

protocol KMPQuestionStoreBridging: AnyObject {
    func snapshot() -> SharedQuestionResult
    func reset() -> SharedQuestionResult
    func requestReceived(requestJson: String) -> SharedQuestionResult
    func submitAnswer(rpcId: String, answersJson: String, isConnected: Bool) -> SharedQuestionResult
    func submitCancel(rpcId: String, isConnected: Bool) -> SharedQuestionResult
    func responseReceived(
        rpcId: String,
        action: String,
        accepted: Bool,
        reason: String?
    ) -> SharedQuestionResult
    func resolved(rpcId: String) -> SharedQuestionResult
    func requestFailed(rpcId: String, message: String?) -> SharedQuestionResult
    func sessionRequestsFailed(sessionId: String, message: String?) -> SharedQuestionResult
}

extension SharedQuestionStore: KMPQuestionStoreBridging {}

private protocol KMPQuestionEventBridging: AnyObject {
    func subscribe(observer: SharedMviEventObserver) -> SharedMviSubscription
}

extension SharedQuestionStore: KMPQuestionEventBridging {}

struct KMPQuestionTransition {
    var snapshot: KMPQuestionSnapshot
    var effect: KMPQuestionEffect?
    var error: KMPQuestionStoreError?
}

/// AppStore 的 MainActor 是 Question 状态和 effect 的唯一串行提交入口。
@MainActor
final class KMPQuestionStoreAdapter {
    private let store: any KMPQuestionStoreBridging
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()
    private var eventObserver: KMPSharedMviEventObserver?
    private var eventSubscription: SharedMviSubscription?
    private var pendingIntent: KMPQuestionIntent?
    private var pushedTransition: KMPQuestionTransition?
    private(set) var lastEventSequence: Int64?
    private var receivesPushedEvents = false
    var onTransition: ((KMPQuestionTransition) -> Void)?

    private(set) var snapshot: KMPQuestionSnapshot = .empty
    private(set) var initializationError: KMPQuestionStoreError?
    private(set) var runtimeError: KMPQuestionStoreError?
    var isOperational: Bool { initializationError == nil && runtimeError == nil }

    init(
        bridge: (any KMPQuestionStoreBridging)? = nil,
        facade: SharedMobileFacade = SharedMobileFacade()
    ) {
        store = bridge ?? facade.makeQuestionStore()
        let transition = decode(store.snapshot(), requiresSnapshot: true, intent: nil)
        snapshot = transition.snapshot
        initializationError = transition.error
        if initializationError == nil,
           let eventStore = store as? any KMPQuestionEventBridging {
            receivesPushedEvents = true
            let observer = KMPSharedMviEventObserver { [weak self] event in
                MainActor.assumeIsolated { self?.receive(event) }
            }
            eventObserver = observer
            eventSubscription = eventStore.subscribe(observer: observer)
        }
    }

    /// 非抛出入口。成功快照和 effect 必须同时可解码，否则永久 fail closed 且不执行 I/O。
    @discardableResult
    func reduce(_ intent: KMPQuestionIntent) -> KMPQuestionTransition {
        if let initializationError {
            return failed(.initializationFailed(initializationError.localizedDescription))
        }
        if let runtimeError {
            return failed(.runtimeFailed(runtimeError.localizedDescription))
        }

        pendingIntent = intent
        pushedTransition = nil
        defer { pendingIntent = nil }
        let result: SharedQuestionResult
        do {
            switch intent {
            case .reset:
                result = store.reset()
            case .requestReceived(let request):
                result = store.requestReceived(requestJson: try encode(request))
            case .submitAnswer(let rpcID, let answers, let isConnected):
                result = store.submitAnswer(
                    rpcId: rpcID,
                    answersJson: try encode(answers),
                    isConnected: isConnected
                )
            case .submitCancel(let rpcID, let isConnected):
                result = store.submitCancel(rpcId: rpcID, isConnected: isConnected)
            case .responseReceived(let rpcID, let action, let accepted, let reason):
                result = store.responseReceived(
                    rpcId: rpcID,
                    action: action.rawValue,
                    accepted: accepted,
                    reason: reason
                )
            case .resolved(let rpcID):
                result = store.resolved(rpcId: rpcID)
            case .requestFailed(let rpcID, let message):
                result = store.requestFailed(rpcId: rpcID, message: message)
            case .sessionRequestsFailed(let sessionID, let message):
                result = store.sessionRequestsFailed(sessionId: sessionID, message: message)
            }
        } catch {
            return failed(.encoding(error.localizedDescription))
        }
        if receivesPushedEvents {
            if let pushedTransition { return pushedTransition }
            if result.isSuccess, result.snapshotJson == nil, result.effectJson == nil {
                return KMPQuestionTransition(snapshot: snapshot, effect: nil, error: nil)
            }
            return failClosed(.runtimeFailed("KMP mutation 未发布对应的 MVI event"))
        }
        let transition = decode(result, requiresSnapshot: false, intent: intent)
        // 仅用于不实现 Event 订阅的故障注入 bridge。
        onTransition?(transition)
        return transition
    }

    func receive(_ event: SharedMviEvent) {
        guard event.schema == 2,
              event.domain == "question",
              !event.transactionId.isEmpty else {
            publish(failClosed(.runtimeFailed("KMP Question event 信封无效")))
            return
        }
        if event.kind == "snapshot" {
            guard lastEventSequence == nil,
                  let payload = event.statePayloadJson,
                  let baseline = try? decoder.decode(
                      KMPQuestionSnapshot.self,
                      from: Data(payload.utf8)
                  ),
                  baseline.hasValidWireValues,
                  baseline == snapshot else {
                publish(failClosed(.invalidSnapshot("订阅基线与初始化快照不一致")))
                return
            }
            lastEventSequence = event.sequence
            return
        }
        guard let previous = lastEventSequence,
              event.sequence == previous + 1 else {
            publish(failClosed(.runtimeFailed("KMP Question event sequence 不连续")))
            return
        }
        lastEventSequence = event.sequence
        if event.kind == "error" {
            let transition = failed(.bridge(
                code: event.errorCode ?? "unknown-error",
                message: event.errorMessage
            ))
            pushedTransition = transition
            onTransition?(transition)
            return
        }
        guard event.kind == "transition" else {
            publish(failClosed(.runtimeFailed("未知 Question event kind：\(event.kind)")))
            return
        }
        let effectJson: String?
        do {
            let effects = try decoder.decode(
                [KMPQuestionEffect].self,
                from: Data(event.effectsJson.utf8)
            )
            guard effects.count <= 1 else {
                publish(failClosed(.invalidEffect("Question event 至多包含一个 effect")))
                return
            }
            effectJson = try effects.first.map(encode)
        } catch {
            publish(failClosed(.invalidEffect(error.localizedDescription)))
            return
        }
        let transition = decode(
            SharedQuestionResult(
                snapshotJson: event.statePayloadJson,
                effectJson: effectJson,
                errorCode: nil,
                errorMessage: nil
            ),
            requiresSnapshot: false,
            intent: pendingIntent
        )
        pushedTransition = transition
        onTransition?(transition)
    }

    private func publish(_ transition: KMPQuestionTransition) {
        pushedTransition = transition
        onTransition?(transition)
    }

    private func encode<T: Encodable>(_ value: T) throws -> String {
        String(decoding: try encoder.encode(value), as: UTF8.self)
    }

    private func decode(
        _ result: SharedQuestionResult,
        requiresSnapshot: Bool,
        intent: KMPQuestionIntent?
    ) -> KMPQuestionTransition {
        guard result.isSuccess else {
            return failed(.bridge(
                code: result.errorCode ?? "unknown-error",
                message: result.errorMessage
            ))
        }

        let nextSnapshot: KMPQuestionSnapshot
        if let json = result.snapshotJson {
            do {
                nextSnapshot = try decoder.decode(KMPQuestionSnapshot.self, from: Data(json.utf8))
            } catch {
                return failClosed(.invalidSnapshot(error.localizedDescription))
            }
            guard nextSnapshot.hasValidWireValues else {
                return failClosed(.invalidSnapshot("KMP 返回了未知状态或动作"))
            }
        } else if requiresSnapshot {
            return failClosed(.invalidSnapshot("KMP 未返回初始化快照"))
        } else {
            nextSnapshot = snapshot
        }

        let effect: KMPQuestionEffect?
        if let json = result.effectJson {
            guard result.snapshotJson != nil else {
                return failClosed(.invalidEffect("effect 缺少同事务快照"))
            }
            do {
                effect = try decoder.decode(KMPQuestionEffect.self, from: Data(json.utf8))
            } catch {
                return failClosed(.invalidEffect(error.localizedDescription))
            }
            guard effect?.hasValidWireValues == true else {
                return failClosed(.invalidEffect("KMP 返回了未知动作或不完整 payload"))
            }
            guard let effect,
                  effectIsSemanticallyValid(effect, in: nextSnapshot, for: intent) else {
                return failClosed(.invalidEffect("effect 与同事务快照或提交 intent 语义不一致"))
            }
        } else {
            effect = nil
        }

        snapshot = nextSnapshot
        return KMPQuestionTransition(snapshot: snapshot, effect: effect, error: nil)
    }

    private func effectIsSemanticallyValid(
        _ effect: KMPQuestionEffect,
        in nextSnapshot: KMPQuestionSnapshot,
        for intent: KMPQuestionIntent?
    ) -> Bool {
        guard !effect.rpcId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !effect.sessionId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let request = nextSnapshot.pendingRequests.first(where: { $0.rpcId == effect.rpcId }),
              request.sessionId == effect.sessionId,
              let status = nextSnapshot.requestStatuses[effect.rpcId],
              status.kind == "submitting",
              status.action == effect.action,
              status.failureCode == nil else {
            return false
        }

        switch (GatewayQuestionAction(rawValue: effect.action), intent) {
        case (.answer?, .submitAnswer(let rpcID, let submittedAnswers, _)):
            guard effect.rpcId == rpcID,
                  let answers = effect.answers,
                  !answers.isEmpty,
                  answers == submittedAnswers else {
                return false
            }
            let questionIDs = request.questions.map(\.id)
            let answerIDs = answers.map(\.id)
            return !questionIDs.isEmpty
                && Set(questionIDs).count == questionIDs.count
                && answerIDs == questionIDs
                && Set(answerIDs) == Set(questionIDs)
        case (.cancel?, .submitCancel(let rpcID, _)):
            return effect.rpcId == rpcID && effect.answers == nil
        default:
            // KMP effect 只能由当次 submit intent 产生；其他 reducer 事务不得偷渡 I/O。
            return false
        }
    }

    private func failed(_ error: KMPQuestionStoreError) -> KMPQuestionTransition {
        KMPQuestionTransition(snapshot: snapshot, effect: nil, error: error)
    }

    private func failClosed(_ error: KMPQuestionStoreError) -> KMPQuestionTransition {
        runtimeError = error
        return failed(error)
    }
}

// MARK: - Approval MVI

struct KMPApprovalStatusSnapshot: Codable, Equatable {
    let kind: String
    let outcome: String?
    let failureCode: String?
    let failureArgument: String?

    var platformStatus: GatewayApprovalRequestStatus {
        let parsed = outcome.flatMap(GatewayApprovalOutcome.init(rawValue:)) ?? .rejected
        switch kind {
        case "submitting": return .submitting(parsed)
        case "accepted": return .accepted(parsed)
        case "rejected": return .failed(localizedFailure)
        default: return .idle
        }
    }

    private var localizedFailure: String {
        switch failureCode {
        case "DISCONNECTED":
            String(localized: "WebSocket 已断开，重连后再处理审批。")
        case "SERVER_REJECTED":
            String(format: String(localized: "服务端未接受审批决定（%@），请重试。"), failureArgument ?? "bad-response")
        default:
            failureArgument?.isEmpty == false
                ? failureArgument!
                : String(localized: "审批响应发送失败，请重试。")
        }
    }
}

struct KMPApprovalSnapshot: Codable, Equatable {
    let pendingRequests: [GatewayPendingApprovalRequest]
    let requestStatuses: [String: KMPApprovalStatusSnapshot]

    static let empty = KMPApprovalSnapshot(pendingRequests: [], requestStatuses: [:])

    var platformStatuses: [String: GatewayApprovalRequestStatus] {
        requestStatuses.mapValues(\.platformStatus)
    }
}

struct KMPApprovalEffect: Codable, Equatable {
    let action: String
    let rpcId: String
    let sessionId: String
    let approvalId: String
    let outcome: String
}

enum KMPApprovalIntent {
    case reset
    case requestReceived(GatewayPendingApprovalRequest)
    case submitDecision(rpcID: String, outcome: GatewayApprovalOutcome, isConnected: Bool)
    case responseReceived(rpcID: String, outcome: GatewayApprovalOutcome, accepted: Bool, reason: String?)
    case resolved(rpcID: String)
    case requestFailed(rpcID: String, message: String)
    case sessionRequestsFailed(sessionID: String, message: String)
}

enum KMPApprovalStoreError: LocalizedError, Equatable {
    case encoding(String)
    case bridge(code: String, message: String?)
    case invalidSnapshot(String)
    case invalidEffect(String)

    var errorDescription: String? {
        switch self {
        case .encoding(let message): "无法编码 iOS Approval 输入：\(message)"
        case .bridge(let code, let message): "KMP Approval 失败（\(code)）：\(message ?? "无详细信息")"
        case .invalidSnapshot(let message): "无法解码 KMP Approval 快照：\(message)"
        case .invalidEffect(let message): "无法解码 KMP Approval effect：\(message)"
        }
    }
}

protocol KMPApprovalStoreBridging: AnyObject {
    func snapshot() -> SharedApprovalResult
    func reset() -> SharedApprovalResult
    func requestReceived(requestJson: String) -> SharedApprovalResult
    func submitDecision(rpcId: String, outcome: String, isConnected: Bool) -> SharedApprovalResult
    func responseReceived(rpcId: String, outcome: String, accepted: Bool, reason: String?) -> SharedApprovalResult
    func resolved(rpcId: String) -> SharedApprovalResult
    func requestFailed(rpcId: String, message: String?) -> SharedApprovalResult
    func sessionRequestsFailed(sessionId: String, message: String?) -> SharedApprovalResult
}

extension SharedApprovalStore: KMPApprovalStoreBridging {}

struct KMPApprovalTransition {
    let snapshot: KMPApprovalSnapshot
    let effect: KMPApprovalEffect?
    let error: KMPApprovalStoreError?
}

@MainActor
final class KMPApprovalStoreAdapter {
    private let store: any KMPApprovalStoreBridging
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()
    private(set) var snapshot: KMPApprovalSnapshot = .empty

    init(
        bridge: (any KMPApprovalStoreBridging)? = nil,
        facade: SharedMobileFacade = SharedMobileFacade()
    ) {
        store = bridge ?? facade.makeApprovalStore()
        let transition = decode(store.snapshot(), requiresSnapshot: true)
        snapshot = transition.snapshot
    }

    func reduce(_ intent: KMPApprovalIntent) -> KMPApprovalTransition {
        do {
            let result: SharedApprovalResult
            switch intent {
            case .reset:
                result = store.reset()
            case .requestReceived(let request):
                result = store.requestReceived(requestJson: try encode(request))
            case .submitDecision(let rpcID, let outcome, let isConnected):
                result = store.submitDecision(rpcId: rpcID, outcome: outcome.rawValue, isConnected: isConnected)
            case .responseReceived(let rpcID, let outcome, let accepted, let reason):
                result = store.responseReceived(
                    rpcId: rpcID,
                    outcome: outcome.rawValue,
                    accepted: accepted,
                    reason: reason
                )
            case .resolved(let rpcID):
                result = store.resolved(rpcId: rpcID)
            case .requestFailed(let rpcID, let message):
                result = store.requestFailed(rpcId: rpcID, message: message)
            case .sessionRequestsFailed(let sessionID, let message):
                result = store.sessionRequestsFailed(sessionId: sessionID, message: message)
            }
            return decode(result, requiresSnapshot: false)
        } catch {
            return KMPApprovalTransition(snapshot: snapshot, effect: nil, error: .encoding(error.localizedDescription))
        }
    }

    private func decode(_ result: SharedApprovalResult, requiresSnapshot: Bool) -> KMPApprovalTransition {
        guard result.isSuccess else {
            return KMPApprovalTransition(
                snapshot: snapshot,
                effect: nil,
                error: .bridge(code: result.errorCode ?? "unknown-error", message: result.errorMessage)
            )
        }
        var next = snapshot
        if let json = result.snapshotJson {
            do { next = try decoder.decode(KMPApprovalSnapshot.self, from: Data(json.utf8)) }
            catch {
                return KMPApprovalTransition(snapshot: snapshot, effect: nil, error: .invalidSnapshot(error.localizedDescription))
            }
        } else if requiresSnapshot {
            return KMPApprovalTransition(snapshot: snapshot, effect: nil, error: .invalidSnapshot("KMP 未返回初始化快照"))
        }
        let effect: KMPApprovalEffect?
        if let json = result.effectJson {
            do { effect = try decoder.decode(KMPApprovalEffect.self, from: Data(json.utf8)) }
            catch {
                return KMPApprovalTransition(snapshot: snapshot, effect: nil, error: .invalidEffect(error.localizedDescription))
            }
            guard effect?.action == "respond",
                  effect.flatMap({ GatewayApprovalOutcome(rawValue: $0.outcome) }) != nil,
                  next.pendingRequests.contains(where: {
                      $0.rpcId == effect?.rpcId && $0.sessionId == effect?.sessionId && $0.approvalId == effect?.approvalId
                  }) else {
                return KMPApprovalTransition(snapshot: snapshot, effect: nil, error: .invalidEffect("effect 与待审批请求不匹配"))
            }
        } else {
            effect = nil
        }
        snapshot = next
        return KMPApprovalTransition(snapshot: next, effect: effect, error: nil)
    }

    private func encode<T: Encodable>(_ value: T) throws -> String {
        String(decoding: try encoder.encode(value), as: UTF8.self)
    }
}

struct KMPSessionControlSnapshot: Codable, Equatable {
    var modelCatalogs: [String: GatewayModelCatalog]
    var globalModelCatalog: GatewayModelCatalog?
    var sessionPermissions: [String: GatewaySessionPermissions]
    var contextSnapshots: [String: GatewayContextSnapshot]
    var sessionStatsSnapshots: [String: GatewaySessionStatsSnapshot]
    var agentPresets: [GatewayAgentPreset]
    var agentPresetsAuthorable: Bool
    var agentPresetsHasDocument: Bool
    var agentPresetDefault: String?
    var permissionDefault: String?
    var defaultModelSelection: GatewayModelSelection?
    var loadingKinds: Set<String>
    var defaultConfigurationLoadingKinds: Set<String>
    var pendingModelsSessionId: String?
    var isPendingGlobalModelsRequest: Bool
    var pendingModelSelectionSessionId: String?
    var pendingPermissionOptionsSessionId: String?
    var requestTokens: [String: String]
    var activeRequestTargets: [String: KMPSessionControlRequestTarget]
    var queuedRequestTargets: [String: KMPSessionControlRequestTarget]
    var previousCompletedRequestTargets: [String: KMPSessionControlRequestTarget]
    var explicitSessionRequiredKinds: Set<String>
    var drainingRequestKinds: Set<String>
    var quarantinedRequestKinds: Set<String>

    static let empty = KMPSessionControlSnapshot(
        modelCatalogs: [:],
        globalModelCatalog: nil,
        sessionPermissions: [:],
        contextSnapshots: [:],
        sessionStatsSnapshots: [:],
        agentPresets: [],
        agentPresetsAuthorable: false,
        agentPresetsHasDocument: false,
        agentPresetDefault: nil,
        permissionDefault: nil,
        defaultModelSelection: nil,
        loadingKinds: [],
        defaultConfigurationLoadingKinds: [],
        pendingModelsSessionId: nil,
        isPendingGlobalModelsRequest: false,
        pendingModelSelectionSessionId: nil,
        pendingPermissionOptionsSessionId: nil,
        requestTokens: [:],
        activeRequestTargets: [:],
        queuedRequestTargets: [:],
        previousCompletedRequestTargets: [:],
        explicitSessionRequiredKinds: [],
        drainingRequestKinds: [],
        quarantinedRequestKinds: []
    )

    var hasValidWireValues: Bool {
        let sessionKinds = Set(["models", "permission-options", "context-usage", "session-stats", "select-model", "permission"])
        let defaultKinds = Set(["agent-presets", "defaults", "default-model", "set-default", "save-default-model"])
        let loading = loadingKinds.union(defaultConfigurationLoadingKinds)
        let activeKinds = Set(activeRequestTargets.keys)
        let activeClassificationIsValid = activeRequestTargets.allSatisfy { kind, target in
            target.kind == kind
                && (target.isDefault ? defaultKinds.contains(kind) : sessionKinds.contains(kind))
                && target.hasCompletePayload
        }
        let queuedIsValid = queuedRequestTargets.allSatisfy { kind, target in
            target.kind == kind && target.hasCompletePayload
                && activeRequestTargets[kind] != nil && target != activeRequestTargets[kind]
        }
        let previousIsValid = previousCompletedRequestTargets.allSatisfy { kind, target in
            target.kind == kind
                && (target.isDefault ? defaultKinds.contains(kind) : sessionKinds.contains(kind))
                && target.hasCompletePayload
        }
        let models = activeRequestTargets["models"]
        return loadingKinds.isSubset(of: sessionKinds)
            && defaultConfigurationLoadingKinds.isSubset(of: defaultKinds)
            && Set(requestTokens.keys) == loading
            && requestTokens.values.allSatisfy { !$0.isEmpty }
            && activeKinds == loading
            && activeClassificationIsValid
            && queuedIsValid
            && previousIsValid
            && explicitSessionRequiredKinds.isSubset(of: activeKinds)
            && explicitSessionRequiredKinds.allSatisfy { activeRequestTargets[$0]?.sessionId != nil }
            && drainingRequestKinds.isSubset(of: activeKinds)
            && drainingRequestKinds.isDisjoint(with: explicitSessionRequiredKinds)
            && drainingRequestKinds.isDisjoint(with: quarantinedRequestKinds)
            && quarantinedRequestKinds.isDisjoint(with: activeKinds)
            && quarantinedRequestKinds.isDisjoint(with: Set(queuedRequestTargets.keys))
            && pendingModelsSessionId == models?.sessionId
            && isPendingGlobalModelsRequest == (models != nil && models?.sessionId == nil)
            && pendingModelSelectionSessionId == activeRequestTargets["select-model"]?.sessionId
            && pendingPermissionOptionsSessionId == activeRequestTargets["permission-options"]?.sessionId
    }

    var controlPatch: KMPSessionControlControlPatch {
        KMPSessionControlControlPatch(
            loadingKinds: loadingKinds,
            defaultConfigurationLoadingKinds: defaultConfigurationLoadingKinds,
            pendingModelsSessionId: pendingModelsSessionId,
            isPendingGlobalModelsRequest: isPendingGlobalModelsRequest,
            pendingModelSelectionSessionId: pendingModelSelectionSessionId,
            pendingPermissionOptionsSessionId: pendingPermissionOptionsSessionId,
            requestTokens: requestTokens,
            activeRequestTargets: activeRequestTargets,
            queuedRequestTargets: queuedRequestTargets,
            previousCompletedRequestTargets: previousCompletedRequestTargets,
            explicitSessionRequiredKinds: explicitSessionRequiredKinds,
            drainingRequestKinds: drainingRequestKinds,
            quarantinedRequestKinds: quarantinedRequestKinds
        )
    }

    fileprivate mutating func apply(_ control: KMPSessionControlControlPatch) {
        loadingKinds = control.loadingKinds
        defaultConfigurationLoadingKinds = control.defaultConfigurationLoadingKinds
        pendingModelsSessionId = control.pendingModelsSessionId
        isPendingGlobalModelsRequest = control.isPendingGlobalModelsRequest
        pendingModelSelectionSessionId = control.pendingModelSelectionSessionId
        pendingPermissionOptionsSessionId = control.pendingPermissionOptionsSessionId
        requestTokens = control.requestTokens
        activeRequestTargets = control.activeRequestTargets
        queuedRequestTargets = control.queuedRequestTargets
        previousCompletedRequestTargets = control.previousCompletedRequestTargets
        explicitSessionRequiredKinds = control.explicitSessionRequiredKinds
        drainingRequestKinds = control.drainingRequestKinds
        quarantinedRequestKinds = control.quarantinedRequestKinds
    }
}

struct KMPSessionControlControlPatch: Codable, Equatable {
    var loadingKinds: Set<String>
    var defaultConfigurationLoadingKinds: Set<String>
    var pendingModelsSessionId: String?
    var isPendingGlobalModelsRequest: Bool
    var pendingModelSelectionSessionId: String?
    var pendingPermissionOptionsSessionId: String?
    var requestTokens: [String: String]
    var activeRequestTargets: [String: KMPSessionControlRequestTarget]
    var queuedRequestTargets: [String: KMPSessionControlRequestTarget]
    var previousCompletedRequestTargets: [String: KMPSessionControlRequestTarget]
    var explicitSessionRequiredKinds: Set<String>
    var drainingRequestKinds: Set<String>
    var quarantinedRequestKinds: Set<String>
}

/// KMP SessionControl 跨边界增量协议。大字典只携带受影响 session 的分片。
struct KMPSessionControlPatch: Codable, Equatable {
    var schema: Int
    var modelCatalogsUpsert: [String: GatewayModelCatalog]
    var modelCatalogsRemove: Set<String>
    var sessionPermissionsUpsert: [String: GatewaySessionPermissions]
    var sessionPermissionsRemove: Set<String>
    var contextSnapshotsUpsert: [String: GatewayContextSnapshot]
    var contextSnapshotsRemove: Set<String>
    var sessionStatsSnapshotsUpsert: [String: GatewaySessionStatsSnapshot]
    var sessionStatsSnapshotsRemove: Set<String>
    var globalModelCatalogChanged: Bool
    var globalModelCatalog: GatewayModelCatalog?
    var agentPresetsChanged: Bool
    var agentPresets: [GatewayAgentPreset]?
    var agentPresetsAuthorable: Bool?
    var agentPresetsHasDocument: Bool?
    var agentPresetDefaultChanged: Bool
    var agentPresetDefault: String?
    var permissionDefaultChanged: Bool
    var permissionDefault: String?
    var defaultModelSelectionChanged: Bool
    var defaultModelSelection: GatewayModelSelection?
    var control: KMPSessionControlControlPatch?

    static let empty = KMPSessionControlPatch(
        schema: 2,
        modelCatalogsUpsert: [:],
        modelCatalogsRemove: [],
        sessionPermissionsUpsert: [:],
        sessionPermissionsRemove: [],
        contextSnapshotsUpsert: [:],
        contextSnapshotsRemove: [],
        sessionStatsSnapshotsUpsert: [:],
        sessionStatsSnapshotsRemove: [],
        globalModelCatalogChanged: false,
        globalModelCatalog: nil,
        agentPresetsChanged: false,
        agentPresets: nil,
        agentPresetsAuthorable: nil,
        agentPresetsHasDocument: nil,
        agentPresetDefaultChanged: false,
        agentPresetDefault: nil,
        permissionDefaultChanged: false,
        permissionDefault: nil,
        defaultModelSelectionChanged: false,
        defaultModelSelection: nil,
        control: nil
    )
}

struct KMPSessionControlRequestTarget: Codable, Equatable {
    var kind: String
    var isDefault: Bool
    var sessionId: String?
    var provider: String?
    var model: String?
    var reasoningEffort: String?
    var target: String?
    var value: String?

    init(
        kind: String,
        isDefault: Bool,
        sessionId: String? = nil,
        provider: String? = nil,
        model: String? = nil,
        reasoningEffort: String? = nil,
        target: String? = nil,
        value: String? = nil
    ) {
        self.kind = kind
        self.isDefault = isDefault
        self.sessionId = sessionId
        self.provider = provider
        self.model = model
        self.reasoningEffort = reasoningEffort
        self.target = target
        self.value = value
    }

    var hasCompletePayload: Bool {
        let session = sessionId?.isEmpty == false
        let hasProvider = provider?.isEmpty == false
        let hasModel = model?.isEmpty == false
        let hasTarget = target?.isEmpty == false
        let hasValue = value?.isEmpty == false
        switch kind {
        case "models":
            return !isDefault && (sessionId == nil || session) && provider == nil && model == nil
                && reasoningEffort == nil && target == nil && value == nil
        case "permission-options", "context-usage", "session-stats":
            return !isDefault && session && provider == nil && model == nil
                && reasoningEffort == nil && target == nil && value == nil
        case "agent-presets", "defaults", "default-model":
            return isDefault && sessionId == nil && provider == nil && model == nil
                && reasoningEffort == nil && target == nil && value == nil
        case "select-model":
            return !isDefault && session && hasProvider && hasModel && target == nil && value == nil
        case "permission":
            return !isDefault && session && hasValue && provider == nil && model == nil
                && reasoningEffort == nil && target == nil
        case "save-default-model":
            return isDefault && sessionId == nil && hasProvider && hasModel && target == nil && value == nil
        case "set-default":
            return isDefault && sessionId == nil && provider == nil && model == nil
                && reasoningEffort == nil && hasTarget && hasValue
                && (target == "permission" || target == "agent-preset")
        default:
            return false
        }
    }
}

struct KMPSessionControlEffect: Codable, Equatable {
    var kind: String
    var requestKey: String
    var requestToken: String
    var sessionId: String?
    var provider: String?
    var model: String?
    var reasoningEffort: String?
    var target: String?
    var value: String?
}

enum KMPSessionControlIntent {
    case action(KMPSessionControlAction)
    case projection(KMPSessionControlAction)
    case defaultModelSaved(GatewayModelSelection?)
    case requestModels(sessionID: String?, isConnected: Bool)
    case requestPermissionOptions(sessionID: String, isConnected: Bool)
    case requestContextUsage(sessionID: String, isConnected: Bool)
    case requestSessionStats(sessionID: String, isConnected: Bool)
    case requestAgentPresets(isConnected: Bool)
    case requestDefaults(isConnected: Bool)
    case requestDefaultModel(isConnected: Bool)
    case selectModel(sessionID: String, selection: GatewayModelSelection, isConnected: Bool)
    case setPermission(sessionID: String, value: String, isConnected: Bool)
    case saveDefaultModel(selection: GatewayModelSelection, isConnected: Bool)
    case setDefault(target: String, value: String, isConnected: Bool)
    case clearSessionData(sessionID: String)
    case clearSessionsData(sessionIDs: Set<String>)
    case requestFinished(kind: String, isDefault: Bool, requestToken: String)
    case requestTimedOut(kind: String, isDefault: Bool, requestToken: String)
    case requestFailed(kind: String, isDefault: Bool, requestToken: String)
    case requestsDisconnected
}

enum KMPSessionControlStoreError: LocalizedError, Equatable {
    case encoding(String)
    case bridge(code: String, message: String?)
    case invalidSnapshot(String)
    case invalidPatch(String)
    case invalidEffect(String)
    case initializationFailed(String)
    case runtimeFailed(String)

    var errorDescription: String? {
        switch self {
        case .encoding(let message): "无法编码 iOS SessionControl 输入：\(message)"
        case .bridge(let code, let message): "KMP SessionControl 失败（\(code)）：\(message ?? "无详细信息")"
        case .invalidSnapshot(let message): "无法解码 KMP SessionControl 快照：\(message)"
        case .invalidPatch(let message): "KMP SessionControl 增量 patch 无效：\(message)"
        case .invalidEffect(let message): "无法解码 KMP SessionControl effect：\(message)"
        case .initializationFailed(let message): "KMP SessionControl 初始化失败，已停止后续状态写入：\(message)"
        case .runtimeFailed(let message): "KMP SessionControl 运行期结果失效，已停止后续状态写入：\(message)"
        }
    }
}

protocol KMPSessionControlStoreBridging: AnyObject {
    func snapshot() -> SharedSessionControlResult
    func requestModels(sessionId: String?, isConnected: Bool) -> SharedSessionControlResult
    func requestPermissionOptions(sessionId: String, isConnected: Bool) -> SharedSessionControlResult
    func requestContextUsage(sessionId: String, isConnected: Bool) -> SharedSessionControlResult
    func requestSessionStats(sessionId: String, isConnected: Bool) -> SharedSessionControlResult
    func requestAgentPresets(isConnected: Bool) -> SharedSessionControlResult
    func requestDefaults(isConnected: Bool) -> SharedSessionControlResult
    func requestDefaultModel(isConnected: Bool) -> SharedSessionControlResult
    func selectModel(sessionId: String, selectionJson: String, isConnected: Bool) -> SharedSessionControlResult
    func setPermission(sessionId: String, value: String, isConnected: Bool) -> SharedSessionControlResult
    func saveDefaultModel(selectionJson: String, isConnected: Bool) -> SharedSessionControlResult
    func setDefault(target: String, value: String, isConnected: Bool) -> SharedSessionControlResult
    func clearSessionData(sessionId: String) -> SharedSessionControlResult
    func clearSessionsData(sessionIdsJson: String) -> SharedSessionControlResult
    func agentPresetsReceived(presetsJson: String, authorable: Bool, hasDocument: Bool) -> SharedSessionControlResult
    func defaultsReceived(agentPreset: String?, permission: String?) -> SharedSessionControlResult
    func defaultModelReceived(selectionJson: String?) -> SharedSessionControlResult
    func globalDefaultApplied(target: String, value: String) -> SharedSessionControlResult
    func modelsReceived(
        sessionId: String?,
        currentJson: String?,
        routable: Bool,
        groupsJson: String,
        isGlobalRequest: Bool
    ) -> SharedSessionControlResult
    func defaultModelSaved(selectionJson: String?) -> SharedSessionControlResult
    func modelSelected(sessionId: String?, selectionJson: String) -> SharedSessionControlResult
    func permissionsReceived(sessionId: String?, permissionsJson: String) -> SharedSessionControlResult
    func permissionSelected(sessionId: String?, value: String) -> SharedSessionControlResult
    func contextReceived(
        sessionId: String?,
        asOfSequence: KotlinLong?,
        tokenUsageJson: String?,
        pressureJson: String?,
        breakdownJson: String?
    ) -> SharedSessionControlResult
    func statsReceived(
        sessionId: String?,
        asOfSequence: KotlinLong?,
        statsJson: String?,
        tokenUsageTotalsJson: String?,
        contextPressureJson: String?
    ) -> SharedSessionControlResult
    func mergeContextProjection(sessionId: String, asOfSequence: KotlinLong?, tokenUsageJson: String?, pressureJson: String?, breakdownJson: String?) -> SharedSessionControlResult
    func mergeStatsProjection(sessionId: String, asOfSequence: KotlinLong?, statsJson: String?, tokenUsageTotalsJson: String?, contextPressureJson: String?) -> SharedSessionControlResult
    func mergePermissionsProjection(sessionId: String, permissionsJson: String) -> SharedSessionControlResult
    func mergeModelProjection(sessionId: String, selectionJson: String) -> SharedSessionControlResult
    func mergePermissionProjection(sessionId: String, value: String) -> SharedSessionControlResult
    func requestFinished(kind: String, isDefault: Bool, requestToken: String?) -> SharedSessionControlResult
    func requestTimedOut(kind: String, isDefault: Bool, requestToken: String?) -> SharedSessionControlResult
    func requestFailed(kind: String, isDefault: Bool, requestToken: String?) -> SharedSessionControlResult
    func requestsDisconnected() -> SharedSessionControlResult
}

extension SharedSessionControlStore: KMPSessionControlStoreBridging {}

/// 生产 KMP store 提供推送事件；旧测试 bridge 仍可只实现同步结果协议。
private protocol KMPSessionControlEventBridging: AnyObject {
    func subscribe(observer: SharedMviEventObserver) -> SharedMviSubscription
}

extension SharedSessionControlStore: KMPSessionControlEventBridging {}

private final class KMPSharedMviEventObserver: NSObject, SharedMviEventObserver {
    private let handler: (SharedMviEvent) -> Void

    init(handler: @escaping (SharedMviEvent) -> Void) {
        self.handler = handler
    }

    func onEvent(event: SharedMviEvent) {
        handler(event)
    }
}

private struct KMPSessionControlEventMetadata: Decodable {
    let schema: Int
    let applied: Bool
    let committed: Bool
    let completedKind: String?
    let completedRequestToken: String?
}

struct KMPSessionControlTransition {
    var snapshot: KMPSessionControlSnapshot
    /// nil 表示本次没有业务状态提交；初始化快照由 AppStore init 单独发布。
    var patch: KMPSessionControlPatch?
    var effects: [KMPSessionControlEffect]
    var applied: Bool
    var committed: Bool
    var completedKind: String?
    var completedRequestToken: String?
    var retiredRequestKinds: Set<String>
    var error: KMPSessionControlStoreError?

    func completed(_ kind: String) -> Bool {
        applied && committed && completedKind == kind && completedRequestToken?.isEmpty == false
    }
}

/// MainActor 是 SessionControl 状态与 effect 的唯一 Swift 串行提交入口。
@MainActor
final class KMPSessionControlStoreAdapter {
    private let store: any KMPSessionControlStoreBridging
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()
    private var eventObserver: KMPSharedMviEventObserver?
    private var eventSubscription: SharedMviSubscription?
    private var pendingIntent: KMPSessionControlIntent?
    private var pushedTransition: KMPSessionControlTransition?
    private(set) var lastEventSequence: Int64?
    private var receivesPushedEvents = false
    var onTransition: ((KMPSessionControlTransition) -> Void)?

    private(set) var snapshot: KMPSessionControlSnapshot = .empty
    private(set) var initializationError: KMPSessionControlStoreError?
    private(set) var runtimeError: KMPSessionControlStoreError?
    var isOperational: Bool { initializationError == nil && runtimeError == nil }

    init(
        bridge: (any KMPSessionControlStoreBridging)? = nil,
        facade: SharedMobileFacade = SharedMobileFacade()
    ) {
        store = bridge ?? facade.makeSessionControlStore()
        let transition = decode(store.snapshot(), requiresSnapshot: true, intent: nil)
        snapshot = transition.snapshot
        initializationError = transition.error
        if initializationError == nil,
           let eventStore = store as? any KMPSessionControlEventBridging {
            receivesPushedEvents = true
            let observer = KMPSharedMviEventObserver { [weak self] event in
                MainActor.assumeIsolated {
                    self?.receive(event)
                }
            }
            eventObserver = observer
            eventSubscription = eventStore.subscribe(observer: observer)
        }
    }

    @discardableResult
    func reduce(_ intent: KMPSessionControlIntent) -> KMPSessionControlTransition {
        if let initializationError {
            return failed(.initializationFailed(initializationError.localizedDescription))
        }
        if let runtimeError {
            return failed(.runtimeFailed(runtimeError.localizedDescription))
        }

        let result: SharedSessionControlResult
        pendingIntent = intent
        pushedTransition = nil
        defer { pendingIntent = nil }
        do {
            switch intent {
            case .action(let action):
                guard let reduced = try reduceAction(action, isProjection: false) else {
                    return unchanged()
                }
                result = reduced
            case .projection(let action):
                guard let reduced = try reduceAction(action, isProjection: true) else {
                    return unchanged()
                }
                result = reduced
            case .defaultModelSaved(let selection):
                result = store.defaultModelSaved(selectionJson: try selection.map(encode))
            case .requestModels(let sessionID, let connected):
                result = store.requestModels(sessionId: sessionID, isConnected: connected)
            case .requestPermissionOptions(let sessionID, let connected):
                result = store.requestPermissionOptions(sessionId: sessionID, isConnected: connected)
            case .requestContextUsage(let sessionID, let connected):
                result = store.requestContextUsage(sessionId: sessionID, isConnected: connected)
            case .requestSessionStats(let sessionID, let connected):
                result = store.requestSessionStats(sessionId: sessionID, isConnected: connected)
            case .requestAgentPresets(let connected):
                result = store.requestAgentPresets(isConnected: connected)
            case .requestDefaults(let connected):
                result = store.requestDefaults(isConnected: connected)
            case .requestDefaultModel(let connected):
                result = store.requestDefaultModel(isConnected: connected)
            case .selectModel(let sessionID, let selection, let connected):
                result = store.selectModel(
                    sessionId: sessionID,
                    selectionJson: try encode(selection),
                    isConnected: connected
                )
            case .setPermission(let sessionID, let value, let connected):
                result = store.setPermission(sessionId: sessionID, value: value, isConnected: connected)
            case .saveDefaultModel(let selection, let connected):
                result = store.saveDefaultModel(selectionJson: try encode(selection), isConnected: connected)
            case .setDefault(let target, let value, let connected):
                result = store.setDefault(target: target, value: value, isConnected: connected)
            case .clearSessionData(let sessionID):
                result = store.clearSessionData(sessionId: sessionID)
            case .clearSessionsData(let sessionIDs):
                result = store.clearSessionsData(sessionIdsJson: try encode(sessionIDs.sorted()))
            case .requestFinished(let kind, let isDefault, let token):
                result = store.requestFinished(kind: kind, isDefault: isDefault, requestToken: token)
            case .requestTimedOut(let kind, let isDefault, let token):
                result = store.requestTimedOut(kind: kind, isDefault: isDefault, requestToken: token)
            case .requestFailed(let kind, let isDefault, let token):
                result = store.requestFailed(kind: kind, isDefault: isDefault, requestToken: token)
            case .requestsDisconnected:
                result = store.requestsDisconnected()
            }
        } catch {
            return failed(.encoding(error.localizedDescription))
        }
        if receivesPushedEvents {
            if let pushedTransition { return pushedTransition }
            if result.isSuccess,
               result.snapshotJson == nil,
               result.effectsJson == "[]" {
                // 无业务变化时 KMP 不推送 event；同步返回值只保留 dispatch ack，
                // 不携带/发布业务状态，也不执行平台 effect。
                return KMPSessionControlTransition(
                    snapshot: snapshot,
                    patch: nil,
                    effects: [],
                    applied: result.applied,
                    committed: result.committed,
                    completedKind: result.completedKind,
                    completedRequestToken: result.completedRequestToken,
                    retiredRequestKinds: [],
                    error: nil
                )
            }
            return failClosed(.runtimeFailed("KMP mutation 未发布对应的 MVI event"))
        }
        let transition = decode(result, requiresSnapshot: false, intent: intent)
        // 仅用于不实现 Event 订阅的故障注入 bridge。
        onTransition?(transition)
        return transition
    }

    func receive(_ event: SharedMviEvent) {
        guard event.schema == 2,
              event.domain == "session-control",
              !event.transactionId.isEmpty else {
            publish(failClosed(.runtimeFailed("KMP MVI event 信封无效")))
            return
        }
        if event.kind == "snapshot" {
            guard lastEventSequence == nil,
                  let payload = event.statePayloadJson,
                  let baseline = try? decoder.decode(
                      KMPSessionControlSnapshot.self,
                      from: Data(payload.utf8)
                  ),
                  baseline.hasValidWireValues,
                  baseline == snapshot else {
                publish(failClosed(.invalidSnapshot("订阅基线与初始化快照不一致")))
                return
            }
            lastEventSequence = event.sequence
            return
        }
        guard let previous = lastEventSequence,
              event.sequence == previous + 1 else {
            publish(failClosed(.runtimeFailed("KMP MVI event sequence 不连续")))
            return
        }
        lastEventSequence = event.sequence
        switch event.kind {
        case "error":
            let transition = failed(.bridge(
                code: event.errorCode ?? "unknown-error",
                message: event.errorMessage
            ))
            pushedTransition = transition
            onTransition?(transition)
        case "transition":
            guard let metadataJson = event.metadataJson,
                  let metadata = try? decoder.decode(
                      KMPSessionControlEventMetadata.self,
                      from: Data(metadataJson.utf8)
                  ),
                  metadata.schema == 1 else {
                publish(failClosed(.invalidPatch("SessionControl event metadata 无效")))
                return
            }
            let result = SharedSessionControlResult(
                snapshotJson: event.statePayloadJson,
                effectsJson: event.effectsJson,
                errorCode: nil,
                errorMessage: nil,
                applied: metadata.applied,
                committed: metadata.committed,
                completedKind: metadata.completedKind,
                completedRequestToken: metadata.completedRequestToken
            )
            let transition = decode(result, requiresSnapshot: false, intent: pendingIntent)
            pushedTransition = transition
            onTransition?(transition)
        default:
            publish(failClosed(.runtimeFailed("未知 KMP MVI event kind：\(event.kind)")))
        }
    }

    private func publish(_ transition: KMPSessionControlTransition) {
        pushedTransition = transition
        onTransition?(transition)
    }

    private func reduceAction(
        _ action: KMPSessionControlAction,
        isProjection: Bool
    ) throws -> SharedSessionControlResult? {
        switch action {
        case .agentPresetsReceived(let presets, let authorable, let hasDocument):
            return store.agentPresetsReceived(
                presetsJson: try encode(presets),
                authorable: authorable,
                hasDocument: hasDocument
            )
        case .defaultsReceived(let agentPreset, let permission):
            return store.defaultsReceived(agentPreset: agentPreset, permission: permission)
        case .defaultModelReceived(let selection):
            return store.defaultModelReceived(selectionJson: try selection.map(encode))
        case .globalDefaultApplied(let target, let value):
            return store.globalDefaultApplied(target: target, value: value)
        case .modelsReceived(let sessionID, let current, let routable, let groups, let global):
            return store.modelsReceived(
                sessionId: sessionID,
                currentJson: try current.map(encode),
                routable: routable,
                groupsJson: try encode(groups),
                isGlobalRequest: global
            )
        case .modelSelected(let sessionID, let selection):
            if isProjection {
                guard let sessionID else { throw KMPSessionControlStoreError.encoding("模型投影缺少 sessionId") }
                return store.mergeModelProjection(sessionId: sessionID, selectionJson: try encode(selection))
            }
            return store.modelSelected(sessionId: sessionID, selectionJson: try encode(selection))
        case .permissionsReceived(let sessionID, let permissions):
            if isProjection {
                guard let sessionID else { throw KMPSessionControlStoreError.encoding("权限投影缺少 sessionId") }
                return store.mergePermissionsProjection(sessionId: sessionID, permissionsJson: try encode(permissions))
            }
            return store.permissionsReceived(sessionId: sessionID, permissionsJson: try encode(permissions))
        case .permissionSelected(let sessionID, let value):
            if isProjection {
                guard let sessionID else { throw KMPSessionControlStoreError.encoding("权限事件投影缺少 sessionId") }
                return store.mergePermissionProjection(sessionId: sessionID, value: value)
            }
            return store.permissionSelected(sessionId: sessionID, value: value)
        case .contextReceived(let sessionID, let sequence, let usage, let pressure, let breakdown):
            let sequence = sequence.map { KotlinLong(longLong: Int64($0)) }
            if isProjection {
                guard let sessionID else { throw KMPSessionControlStoreError.encoding("Context 投影缺少 sessionId") }
                return store.mergeContextProjection(
                    sessionId: sessionID, asOfSequence: sequence,
                    tokenUsageJson: try usage.map(encode), pressureJson: try pressure.map(encode),
                    breakdownJson: try breakdown.map(encode)
                )
            } else {
                return store.contextReceived(
                    sessionId: sessionID, asOfSequence: sequence,
                    tokenUsageJson: try usage.map(encode), pressureJson: try pressure.map(encode),
                    breakdownJson: try breakdown.map(encode)
                )
            }
        case .statsReceived(let sessionID, let sequence, let stats, let totals, let pressure):
            let sequence = sequence.map { KotlinLong(longLong: Int64($0)) }
            if isProjection {
                guard let sessionID else { throw KMPSessionControlStoreError.encoding("Stats 投影缺少 sessionId") }
                return store.mergeStatsProjection(
                    sessionId: sessionID, asOfSequence: sequence,
                    statsJson: try stats.map(encode), tokenUsageTotalsJson: try totals.map(encode),
                    contextPressureJson: try pressure.map(encode)
                )
            } else {
                return store.statsReceived(
                    sessionId: sessionID, asOfSequence: sequence,
                    statsJson: try stats.map(encode), tokenUsageTotalsJson: try totals.map(encode),
                    contextPressureJson: try pressure.map(encode)
                )
            }
        case .requestStarted:
            throw KMPSessionControlStoreError.encoding("Swift 不得直接启动 KMP 请求状态")
        case .requestFinished(let kind):
            guard let token = snapshot.requestTokens[kind] else { return nil }
            return store.requestFinished(kind: kind, isDefault: false, requestToken: token)
        case .requestTimedOut(let kind):
            guard let token = snapshot.requestTokens[kind] else { return nil }
            return store.requestTimedOut(kind: kind, isDefault: false, requestToken: token)
        case .defaultConfigurationRequestStarted:
            throw KMPSessionControlStoreError.encoding("Swift 不得直接启动 KMP 默认配置请求状态")
        case .defaultConfigurationRequestFinished(let kind):
            guard let token = snapshot.requestTokens[kind] else { return nil }
            return store.requestFinished(kind: kind, isDefault: true, requestToken: token)
        case .defaultConfigurationRequestTimedOut(let kind):
            guard let token = snapshot.requestTokens[kind] else { return nil }
            return store.requestTimedOut(kind: kind, isDefault: true, requestToken: token)
        case .modelsRequestTargeted, .modelSelectionTargeted, .permissionOptionsTargeted:
            throw KMPSessionControlStoreError.encoding("请求 target 必须与 KMP effect 在同一事务建立")
        case .modelSelectionResolved, .permissionOptionsResolved:
            return nil
        }
    }

    private func encode<T: Encodable>(_ value: T) throws -> String {
        String(decoding: try encoder.encode(value), as: UTF8.self)
    }

    private func decode(
        _ result: SharedSessionControlResult,
        requiresSnapshot: Bool,
        intent: KMPSessionControlIntent?
    ) -> KMPSessionControlTransition {
        guard result.isSuccess else {
            return failed(.bridge(code: result.errorCode ?? "unknown-error", message: result.errorMessage))
        }
        let oldSnapshot = snapshot
        let nextSnapshot: KMPSessionControlSnapshot
        let patch: KMPSessionControlPatch?
        if result.committed, let json = result.snapshotJson {
            do {
                try validatePatchEnvelope(Data(json.utf8))
                let decoded = try decoder.decode(KMPSessionControlPatch.self, from: Data(json.utf8))
                nextSnapshot = try apply(decoded, to: oldSnapshot)
                patch = decoded
            } catch {
                return failClosed(.invalidPatch(error.localizedDescription))
            }
        } else if let json = result.snapshotJson {
            do {
                if !requiresSnapshot,
                   let object = try JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any],
                   object["schema"] != nil {
                    return failClosed(.invalidPatch("patch payload 缺少 committed 信号"))
                }
                nextSnapshot = try decoder.decode(KMPSessionControlSnapshot.self, from: Data(json.utf8))
                patch = nil
            } catch {
                return failClosed(.invalidSnapshot(error.localizedDescription))
            }
            guard nextSnapshot.hasValidWireValues else {
                return failClosed(.invalidSnapshot("loading kind、request token 或 target 关联无效"))
            }
        } else if requiresSnapshot {
            return failClosed(.invalidSnapshot("KMP 未返回初始化快照"))
        } else {
            nextSnapshot = snapshot
            patch = nil
        }

        guard !result.committed || result.snapshotJson != nil else {
            return failClosed(.invalidSnapshot("committed 结果缺少同事务快照"))
        }
        let completedKind = result.completedKind
        let completedToken = result.completedRequestToken
        guard (completedKind == nil) == (completedToken == nil) else {
            return failClosed(.invalidSnapshot("completed kind/token 必须同时存在"))
        }
        let effects: [KMPSessionControlEffect]
        do {
            effects = try decoder.decode([KMPSessionControlEffect].self, from: Data(result.effectsJson.utf8))
        } catch {
            return failClosed(.invalidEffect(error.localizedDescription))
        }

        // result 的控制信号必须描述同一个原子事务。任何坏信号都会永久 fail closed，
        // 且在 snapshot 提交和 effect 暴露前返回，保证零平台 I/O。
        if result.committed != (patch != nil) {
            return failClosed(.invalidPatch("committed 与增量 patch 必须同时存在"))
        }
        if !requiresSnapshot, !result.committed, result.snapshotJson != nil, nextSnapshot != oldSnapshot {
            // 显式 snapshot() 只能用于无变化的诊断/兼容路径，不得绕过 patch 提交状态。
            return failClosed(.invalidSnapshot("未 committed 的完整快照不得修改状态"))
        }
        guard result.applied || (!result.committed && effects.isEmpty && completedKind == nil) else {
            return failClosed(.invalidSnapshot("未 applied 的结果不得提交、完成 generation 或产生 effect"))
        }
        if !effects.isEmpty && (!result.applied || !result.committed || result.snapshotJson == nil) {
            return failClosed(.invalidEffect("非空 effect 必须来自 applied + committed 的同事务快照"))
        }
        let stableTokenKinds = Set(oldSnapshot.requestTokens.keys).intersection(nextSnapshot.requestTokens.keys)
        guard stableTokenKinds.allSatisfy({ kind in
            oldSnapshot.requestTokens[kind] != nextSnapshot.requestTokens[kind]
                || oldSnapshot.activeRequestTargets[kind] == nextSnapshot.activeRequestTargets[kind]
        }) else {
            return failClosed(.invalidSnapshot("同 token 的 active target 不得变化"))
        }
        let retiredKinds = Set(oldSnapshot.requestTokens.compactMap { kind, token in
            nextSnapshot.requestTokens[kind] == token ? nil : kind
        })
        if let completedKind, let completedToken {
            guard result.applied,
                  result.committed,
                  !completedKind.isEmpty,
                  !completedToken.isEmpty,
                  retiredKinds == [completedKind],
                  oldSnapshot.requestTokens[completedKind] == completedToken,
                  oldSnapshot.activeRequestTargets[completedKind] != nil,
                  nextSnapshot.requestTokens[completedKind] != completedToken else {
                return failClosed(.invalidSnapshot("completed generation 与提交前后快照不一致"))
            }
        } else if !retiredKinds.isEmpty {
            let retirementIsValid: Bool
            switch intent {
            case .some(.requestsDisconnected):
                retirementIsValid = effects.isEmpty
            default:
                retirementIsValid = false
            }
            // 断线或会话清理可一次退役多个 generation；其他事务必须明确回传 kind/token。
            guard retirementIsValid else {
                return failClosed(.invalidSnapshot("request token 已变化但缺少 completed generation 信号"))
            }
        }
        let effectCountIsValid: Bool
        effectCountIsValid = effects.count <= 1
        guard effectCountIsValid,
              effects.allSatisfy({
                  effectIsSemanticallyValid(
                      $0,
                      oldSnapshot: oldSnapshot,
                      nextSnapshot: nextSnapshot,
                      intent: intent,
                      completedKind: completedKind
                  )
              }) else {
            return failClosed(.invalidEffect("effect 与快照、request token 或 intent 语义不一致"))
        }
        if let patch, !patchIsSemanticallyValid(
            patch,
            intent: intent,
            oldSnapshot: oldSnapshot,
            nextSnapshot: nextSnapshot
        ) {
            return failClosed(.invalidPatch("patch 携带了当前 intent 不允许的 session 或字段"))
        }

        snapshot = nextSnapshot
        return KMPSessionControlTransition(
            snapshot: snapshot,
            patch: patch,
            effects: effects,
            applied: result.applied,
            committed: result.committed,
            completedKind: completedKind,
            completedRequestToken: completedToken,
            retiredRequestKinds: retiredKinds,
            error: nil
        )
    }

    private func apply(
        _ patch: KMPSessionControlPatch,
        to old: KMPSessionControlSnapshot
    ) throws -> KMPSessionControlSnapshot {
        guard patch.schema == 2 else {
            throw KMPSessionControlStoreError.invalidSnapshot("未知 patch schema: \(patch.schema)")
        }
        var next = old
        var changed = false

        func validateKeys<T>(
            _ upserts: [String: T],
            _ removals: Set<String>,
            oldValues: [String: T],
            equals: (T, T) -> Bool
        ) throws where T: Equatable {
            guard Set(upserts.keys).isDisjoint(with: removals),
                  upserts.keys.allSatisfy({ !$0.isEmpty }),
                  removals.allSatisfy({ !$0.isEmpty && oldValues[$0] != nil }),
                  upserts.allSatisfy({ key, value in
                      guard let oldValue = oldValues[key] else { return true }
                      return !equals(oldValue, value)
                  }) else {
                throw KMPSessionControlStoreError.invalidSnapshot("patch upsert/remove 重叠、冗余或引用了不存在的 key")
            }
        }

        try validateKeys(
            patch.modelCatalogsUpsert, patch.modelCatalogsRemove,
            oldValues: old.modelCatalogs, equals: ==
        )
        try validateKeys(
            patch.sessionPermissionsUpsert, patch.sessionPermissionsRemove,
            oldValues: old.sessionPermissions, equals: ==
        )
        try validateKeys(
            patch.contextSnapshotsUpsert, patch.contextSnapshotsRemove,
            oldValues: old.contextSnapshots, equals: ==
        )
        try validateKeys(
            patch.sessionStatsSnapshotsUpsert, patch.sessionStatsSnapshotsRemove,
            oldValues: old.sessionStatsSnapshots, equals: ==
        )

        if !patch.modelCatalogsUpsert.isEmpty || !patch.modelCatalogsRemove.isEmpty {
            patch.modelCatalogsRemove.forEach { next.modelCatalogs.removeValue(forKey: $0) }
            next.modelCatalogs.merge(patch.modelCatalogsUpsert) { _, new in new }
            changed = true
        }
        if !patch.sessionPermissionsUpsert.isEmpty || !patch.sessionPermissionsRemove.isEmpty {
            patch.sessionPermissionsRemove.forEach { next.sessionPermissions.removeValue(forKey: $0) }
            next.sessionPermissions.merge(patch.sessionPermissionsUpsert) { _, new in new }
            changed = true
        }
        if !patch.contextSnapshotsUpsert.isEmpty || !patch.contextSnapshotsRemove.isEmpty {
            patch.contextSnapshotsRemove.forEach { next.contextSnapshots.removeValue(forKey: $0) }
            next.contextSnapshots.merge(patch.contextSnapshotsUpsert) { _, new in new }
            changed = true
        }
        if !patch.sessionStatsSnapshotsUpsert.isEmpty || !patch.sessionStatsSnapshotsRemove.isEmpty {
            patch.sessionStatsSnapshotsRemove.forEach { next.sessionStatsSnapshots.removeValue(forKey: $0) }
            next.sessionStatsSnapshots.merge(patch.sessionStatsSnapshotsUpsert) { _, new in new }
            changed = true
        }

        guard patch.globalModelCatalogChanged || patch.globalModelCatalog == nil else {
            throw KMPSessionControlStoreError.invalidSnapshot("globalModelCatalog payload 缺少 changed 标记")
        }
        if patch.globalModelCatalogChanged {
            guard old.globalModelCatalog != patch.globalModelCatalog else {
                throw KMPSessionControlStoreError.invalidSnapshot("globalModelCatalog patch 没有变化")
            }
            next.globalModelCatalog = patch.globalModelCatalog
            changed = true
        }
        guard patch.agentPresetsChanged == (patch.agentPresets != nil) else {
            throw KMPSessionControlStoreError.invalidSnapshot("agentPresets payload 与 changed 标记不一致")
        }
        if let presets = patch.agentPresets {
            guard old.agentPresets != presets else {
                throw KMPSessionControlStoreError.invalidSnapshot("agentPresets patch 没有变化")
            }
            next.agentPresets = presets
            changed = true
        }
        if let value = patch.agentPresetsAuthorable {
            guard old.agentPresetsAuthorable != value else {
                throw KMPSessionControlStoreError.invalidSnapshot("agentPresetsAuthorable patch 没有变化")
            }
            next.agentPresetsAuthorable = value
            changed = true
        }
        if let value = patch.agentPresetsHasDocument {
            guard old.agentPresetsHasDocument != value else {
                throw KMPSessionControlStoreError.invalidSnapshot("agentPresetsHasDocument patch 没有变化")
            }
            next.agentPresetsHasDocument = value
            changed = true
        }
        try applyNullable(
            changedFlag: patch.agentPresetDefaultChanged,
            value: patch.agentPresetDefault,
            oldValue: old.agentPresetDefault,
            name: "agentPresetDefault",
            assign: { next.agentPresetDefault = $0 },
            changed: &changed
        )
        try applyNullable(
            changedFlag: patch.permissionDefaultChanged,
            value: patch.permissionDefault,
            oldValue: old.permissionDefault,
            name: "permissionDefault",
            assign: { next.permissionDefault = $0 },
            changed: &changed
        )
        try applyNullable(
            changedFlag: patch.defaultModelSelectionChanged,
            value: patch.defaultModelSelection,
            oldValue: old.defaultModelSelection,
            name: "defaultModelSelection",
            assign: { next.defaultModelSelection = $0 },
            changed: &changed
        )
        if let control = patch.control {
            let oldControl = old.controlPatch
            guard control != oldControl else {
                throw KMPSessionControlStoreError.invalidSnapshot("control patch 没有变化")
            }
            next.apply(control)
            changed = true
        }

        guard changed, next.hasValidWireValues else {
            throw KMPSessionControlStoreError.invalidSnapshot("patch 为空或生成了非法状态")
        }
        return next
    }

    private enum PatchSection: Hashable {
        case modelCatalogs, globalModelCatalog, sessionPermissions, contextSnapshots
        case sessionStatsSnapshots, agentPresets, defaults, defaultModel, control
    }

    private func patchIsSemanticallyValid(
        _ patch: KMPSessionControlPatch,
        intent: KMPSessionControlIntent?,
        oldSnapshot: KMPSessionControlSnapshot,
        nextSnapshot: KMPSessionControlSnapshot
    ) -> Bool {
        guard controlChangeIsBound(
            patch.control,
            intent: intent,
            oldSnapshot: oldSnapshot,
            nextSnapshot: nextSnapshot
        ) else { return false }
        let touched = patchSections(patch)
        func keysAreBound(_ keys: Set<String>, to sessionID: String?) -> Bool {
            guard let sessionID, !sessionID.isEmpty else { return keys.isEmpty }
            return keys.isSubset(of: [sessionID])
        }
        func modelKeysAreBound(to sessionID: String?) -> Bool {
            keysAreBound(
                Set(patch.modelCatalogsUpsert.keys).union(patch.modelCatalogsRemove),
                to: sessionID
            )
        }
        func permissionKeysAreBound(to sessionID: String?) -> Bool {
            keysAreBound(
                Set(patch.sessionPermissionsUpsert.keys).union(patch.sessionPermissionsRemove),
                to: sessionID
            )
        }
        func contextKeysAreBound(to sessionID: String?) -> Bool {
            keysAreBound(
                Set(patch.contextSnapshotsUpsert.keys).union(patch.contextSnapshotsRemove),
                to: sessionID
            )
        }
        func statsKeysAreBound(to sessionID: String?) -> Bool {
            keysAreBound(
                Set(patch.sessionStatsSnapshotsUpsert.keys).union(patch.sessionStatsSnapshotsRemove),
                to: sessionID
            )
        }
        func validateAction(_ action: KMPSessionControlAction, projection: Bool) -> Bool {
            let control: Set<PatchSection> = projection ? [] : [.control]
            let responseKind: String? = switch action {
            case .modelsReceived: "models"
            case .modelSelected: "select-model"
            case .permissionsReceived: "permission-options"
            case .permissionSelected: "permission"
            case .contextReceived: "context-usage"
            case .statsReceived: "session-stats"
            default: nil
            }
            // draining response 只是被清理 generation 的 tombstone 终态：允许它
            // 完成 control 代际并产生 queued effect，但绝不允许重新投影业务数据。
            if let responseKind, oldSnapshot.drainingRequestKinds.contains(responseKind) {
                return !projection && touched.isSubset(of: [.control])
            }
            switch action {
            case .agentPresetsReceived:
                return touched.isSubset(of: control.union([.agentPresets, .defaults]))
            case .defaultsReceived, .globalDefaultApplied:
                return touched.isSubset(of: control.union([.defaults]))
            case .defaultModelReceived:
                return touched.isSubset(of: control.union([.defaultModel]))
            case .modelsReceived(let sessionID, _, _, _, let global):
                let bound = sessionID ?? oldSnapshot.activeRequestTargets["models"]?.sessionId
                let allowed: Set<PatchSection> = global || bound == nil
                    ? control.union([.globalModelCatalog])
                    : control.union([.modelCatalogs])
                return touched.isSubset(of: allowed) && modelKeysAreBound(to: bound)
            case .modelSelected(let sessionID, _):
                let bound = sessionID ?? oldSnapshot.activeRequestTargets["select-model"]?.sessionId
                return touched.isSubset(of: control.union([.modelCatalogs]))
                    && modelKeysAreBound(to: bound)
            case .permissionsReceived(let sessionID, _):
                let bound = sessionID ?? oldSnapshot.activeRequestTargets["permission-options"]?.sessionId
                return touched.isSubset(of: control.union([.sessionPermissions]))
                    && permissionKeysAreBound(to: bound)
            case .permissionSelected(let sessionID, _):
                let bound = sessionID ?? oldSnapshot.activeRequestTargets["permission"]?.sessionId
                return touched.isSubset(of: control.union([.sessionPermissions]))
                    && permissionKeysAreBound(to: bound)
            case .contextReceived(let sessionID, _, _, _, _):
                let bound = sessionID ?? oldSnapshot.activeRequestTargets["context-usage"]?.sessionId
                return touched.isSubset(of: control.union([.contextSnapshots]))
                    && contextKeysAreBound(to: bound)
            case .statsReceived(let sessionID, _, _, _, _):
                let bound = sessionID ?? oldSnapshot.activeRequestTargets["session-stats"]?.sessionId
                return touched.isSubset(of: control.union([.sessionStatsSnapshots]))
                    && statsKeysAreBound(to: bound)
            case .requestStarted, .requestFinished, .requestTimedOut,
                 .defaultConfigurationRequestStarted, .defaultConfigurationRequestFinished,
                 .defaultConfigurationRequestTimedOut, .modelsRequestTargeted,
                 .modelSelectionTargeted, .modelSelectionResolved,
                 .permissionOptionsTargeted, .permissionOptionsResolved:
                return touched.isSubset(of: control)
            }
        }

        switch intent {
        case .action(let action):
            return validateAction(action, projection: false)
        case .projection(let action):
            return validateAction(action, projection: true)
        case .defaultModelSaved:
            return touched.isSubset(of: [.defaultModel, .control])
        case .requestModels, .requestPermissionOptions, .requestContextUsage,
             .requestSessionStats, .requestAgentPresets, .requestDefaults,
             .requestDefaultModel, .selectModel, .setPermission, .saveDefaultModel,
             .setDefault, .requestFinished, .requestTimedOut, .requestFailed,
             .requestsDisconnected:
            return touched.isSubset(of: [.control])
        case .clearSessionData(let sessionID):
            return clearPatchIsBound(
                patch, to: [sessionID], touched: touched,
                oldSnapshot: oldSnapshot, nextSnapshot: nextSnapshot
            )
        case .clearSessionsData(let sessionIDs):
            return clearPatchIsBound(
                patch, to: sessionIDs, touched: touched,
                oldSnapshot: oldSnapshot, nextSnapshot: nextSnapshot
            )
        case .none:
            return false
        }
    }

    private func clearPatchIsBound(
        _ patch: KMPSessionControlPatch,
        to sessionIDs: Set<String>,
        touched: Set<PatchSection>,
        oldSnapshot: KMPSessionControlSnapshot,
        nextSnapshot: KMPSessionControlSnapshot
    ) -> Bool {
        let allowed: Set<PatchSection> = [
            .modelCatalogs, .sessionPermissions, .contextSnapshots,
            .sessionStatsSnapshots, .control
        ]
        func expectedRemovals<T>(_ values: [String: T]) -> Set<String> {
            Set(values.keys).intersection(sessionIDs)
        }
        let expectedModelRemovals = expectedRemovals(oldSnapshot.modelCatalogs)
        let expectedPermissionRemovals = expectedRemovals(oldSnapshot.sessionPermissions)
        let expectedContextRemovals = expectedRemovals(oldSnapshot.contextSnapshots)
        let expectedStatsRemovals = expectedRemovals(oldSnapshot.sessionStatsSnapshots)
        let nextContainsClearedSession =
            !Set(nextSnapshot.modelCatalogs.keys).isDisjoint(with: sessionIDs)
            || !Set(nextSnapshot.sessionPermissions.keys).isDisjoint(with: sessionIDs)
            || !Set(nextSnapshot.contextSnapshots.keys).isDisjoint(with: sessionIDs)
            || !Set(nextSnapshot.sessionStatsSnapshots.keys).isDisjoint(with: sessionIDs)
        return touched.isSubset(of: allowed)
            && patch.modelCatalogsUpsert.isEmpty
            && patch.sessionPermissionsUpsert.isEmpty
            && patch.contextSnapshotsUpsert.isEmpty
            && patch.sessionStatsSnapshotsUpsert.isEmpty
            && patch.modelCatalogsRemove == expectedModelRemovals
            && patch.sessionPermissionsRemove == expectedPermissionRemovals
            && patch.contextSnapshotsRemove == expectedContextRemovals
            && patch.sessionStatsSnapshotsRemove == expectedStatsRemovals
            && !nextContainsClearedSession
    }

    private func controlChangeIsBound(
        _ control: KMPSessionControlControlPatch?,
        intent: KMPSessionControlIntent?,
        oldSnapshot: KMPSessionControlSnapshot,
        nextSnapshot: KMPSessionControlSnapshot
    ) -> Bool {
        guard control != nil else { return true }

        func changedKeys<T: Equatable>(_ old: [String: T], _ next: [String: T]) -> Set<String> {
            Set(old.keys).union(next.keys).filter { old[$0] != next[$0] }
        }
        var changedKinds = oldSnapshot.loadingKinds.symmetricDifference(nextSnapshot.loadingKinds)
        changedKinds.formUnion(
            oldSnapshot.defaultConfigurationLoadingKinds.symmetricDifference(
                nextSnapshot.defaultConfigurationLoadingKinds
            )
        )
        changedKinds.formUnion(changedKeys(oldSnapshot.requestTokens, nextSnapshot.requestTokens))
        changedKinds.formUnion(changedKeys(
            oldSnapshot.activeRequestTargets,
            nextSnapshot.activeRequestTargets
        ))
        changedKinds.formUnion(changedKeys(
            oldSnapshot.queuedRequestTargets,
            nextSnapshot.queuedRequestTargets
        ))
        changedKinds.formUnion(changedKeys(
            oldSnapshot.previousCompletedRequestTargets,
            nextSnapshot.previousCompletedRequestTargets
        ))
        changedKinds.formUnion(
            oldSnapshot.explicitSessionRequiredKinds.symmetricDifference(
                nextSnapshot.explicitSessionRequiredKinds
            )
        )
        changedKinds.formUnion(
            oldSnapshot.drainingRequestKinds.symmetricDifference(
                nextSnapshot.drainingRequestKinds
            )
        )
        changedKinds.formUnion(
            oldSnapshot.quarantinedRequestKinds.symmetricDifference(
                nextSnapshot.quarantinedRequestKinds
            )
        )
        if oldSnapshot.pendingModelsSessionId != nextSnapshot.pendingModelsSessionId
            || oldSnapshot.isPendingGlobalModelsRequest != nextSnapshot.isPendingGlobalModelsRequest {
            changedKinds.insert("models")
        }
        if oldSnapshot.pendingModelSelectionSessionId != nextSnapshot.pendingModelSelectionSessionId {
            changedKinds.insert("select-model")
        }
        if oldSnapshot.pendingPermissionOptionsSessionId != nextSnapshot.pendingPermissionOptionsSessionId {
            changedKinds.insert("permission-options")
        }

        func responseKind(_ action: KMPSessionControlAction) -> String? {
            switch action {
            case .agentPresetsReceived: "agent-presets"
            case .defaultsReceived: "defaults"
            case .defaultModelReceived: "default-model"
            case .globalDefaultApplied: "set-default"
            case .modelsReceived: "models"
            case .modelSelected: "select-model"
            case .permissionsReceived: "permission-options"
            case .permissionSelected: "permission"
            case .contextReceived: "context-usage"
            case .statsReceived: "session-stats"
            case .requestFinished(let kind), .requestTimedOut(let kind): kind
            case .defaultConfigurationRequestFinished(let kind),
                 .defaultConfigurationRequestTimedOut(let kind): kind
            case .requestStarted, .defaultConfigurationRequestStarted,
                 .modelsRequestTargeted, .modelSelectionTargeted, .modelSelectionResolved,
                 .permissionOptionsTargeted, .permissionOptionsResolved:
                nil
            }
        }

        if let target = directRequestTarget(for: intent) {
            guard changedKinds.isSubset(of: [target.kind]) else { return false }
            let newTargets = [
                nextSnapshot.activeRequestTargets[target.kind],
                nextSnapshot.queuedRequestTargets[target.kind]
            ].compactMap { $0 }
            return newTargets.allSatisfy {
                $0 == target || $0 == oldSnapshot.activeRequestTargets[target.kind]
            }
        }

        switch intent {
        case .action(let action):
            guard let kind = responseKind(action) else { return false }
            return changedKinds.isSubset(of: [kind])
        case .projection:
            return false
        case .defaultModelSaved:
            return changedKinds.isSubset(of: ["save-default-model"])
        case .requestFinished(let kind, _, _), .requestTimedOut(let kind, _, _),
             .requestFailed(let kind, _, _):
            return changedKinds.isSubset(of: [kind])
        case .requestsDisconnected:
            return nextSnapshot.loadingKinds.isEmpty
                && nextSnapshot.defaultConfigurationLoadingKinds.isEmpty
                && nextSnapshot.requestTokens.isEmpty
                && nextSnapshot.activeRequestTargets.isEmpty
                && nextSnapshot.queuedRequestTargets.isEmpty
                && nextSnapshot.previousCompletedRequestTargets.isEmpty
                && nextSnapshot.explicitSessionRequiredKinds.isEmpty
                && nextSnapshot.drainingRequestKinds.isEmpty
                && nextSnapshot.quarantinedRequestKinds.isEmpty
                && nextSnapshot.pendingModelsSessionId == nil
                && !nextSnapshot.isPendingGlobalModelsRequest
                && nextSnapshot.pendingModelSelectionSessionId == nil
                && nextSnapshot.pendingPermissionOptionsSessionId == nil
        case .clearSessionData(let sessionID):
            return drainControlIsBound(
                sessionIDs: [sessionID], changedKinds: changedKinds,
                oldSnapshot: oldSnapshot, nextSnapshot: nextSnapshot
            )
        case .clearSessionsData(let sessionIDs):
            return drainControlIsBound(
                sessionIDs: sessionIDs, changedKinds: changedKinds,
                oldSnapshot: oldSnapshot, nextSnapshot: nextSnapshot
            )
        case .requestModels, .requestPermissionOptions, .requestContextUsage,
             .requestSessionStats, .requestAgentPresets, .requestDefaults,
             .requestDefaultModel, .selectModel, .setPermission, .saveDefaultModel,
             .setDefault, .none:
            // disconnected request 不应产生已提交 patch；connected request 已由 directTarget 分支处理。
            return false
        }
    }

    private func drainControlIsBound(
        sessionIDs: Set<String>,
        changedKinds: Set<String>,
        oldSnapshot: KMPSessionControlSnapshot,
        nextSnapshot: KMPSessionControlSnapshot
    ) -> Bool {
        let relatedKinds = Set(
            oldSnapshot.activeRequestTargets.filter { $0.value.sessionId.map(sessionIDs.contains) == true }.keys
        ).union(
            oldSnapshot.queuedRequestTargets.filter { $0.value.sessionId.map(sessionIDs.contains) == true }.keys
        ).union(
            oldSnapshot.previousCompletedRequestTargets.filter {
                $0.value.sessionId.map(sessionIDs.contains) == true
            }.keys
        )
        guard changedKinds.isSubset(of: relatedKinds) else { return false }
        var expected = oldSnapshot
        let newlyDraining = Set(
            oldSnapshot.activeRequestTargets.filter { $0.value.sessionId.map(sessionIDs.contains) == true }.keys
        )
        expected.queuedRequestTargets = expected.queuedRequestTargets.filter {
            $0.value.sessionId.map(sessionIDs.contains) != true
        }
        expected.previousCompletedRequestTargets = expected.previousCompletedRequestTargets.filter {
            $0.value.sessionId.map(sessionIDs.contains) != true
        }
        expected.explicitSessionRequiredKinds.subtract(newlyDraining)
        expected.drainingRequestKinds.formUnion(newlyDraining)
        return nextSnapshot.controlPatch == expected.controlPatch
    }

    private func patchSections(_ patch: KMPSessionControlPatch) -> Set<PatchSection> {
        var sections: Set<PatchSection> = []
        if !patch.modelCatalogsUpsert.isEmpty || !patch.modelCatalogsRemove.isEmpty {
            sections.insert(.modelCatalogs)
        }
        if patch.globalModelCatalogChanged { sections.insert(.globalModelCatalog) }
        if !patch.sessionPermissionsUpsert.isEmpty || !patch.sessionPermissionsRemove.isEmpty {
            sections.insert(.sessionPermissions)
        }
        if !patch.contextSnapshotsUpsert.isEmpty || !patch.contextSnapshotsRemove.isEmpty {
            sections.insert(.contextSnapshots)
        }
        if !patch.sessionStatsSnapshotsUpsert.isEmpty || !patch.sessionStatsSnapshotsRemove.isEmpty {
            sections.insert(.sessionStatsSnapshots)
        }
        if patch.agentPresetsChanged
            || patch.agentPresetsAuthorable != nil
            || patch.agentPresetsHasDocument != nil {
            sections.insert(.agentPresets)
        }
        if patch.agentPresetDefaultChanged || patch.permissionDefaultChanged {
            sections.insert(.defaults)
        }
        if patch.defaultModelSelectionChanged { sections.insert(.defaultModel) }
        if patch.control != nil { sections.insert(.control) }
        return sections
    }

    /// JSONDecoder 会忽略未知字段；patch 是可执行状态协议，因此必须在解码前严格检查层级字段。
    private func validatePatchEnvelope(_ data: Data) throws {
        let raw = try JSONSerialization.jsonObject(with: data)
        guard let patch = raw as? [String: Any] else {
            throw KMPSessionControlStoreError.invalidPatch("patch 顶层必须是 object")
        }

        func object(_ value: Any, _ path: String) throws -> [String: Any] {
            guard let result = value as? [String: Any] else {
                throw KMPSessionControlStoreError.invalidPatch("\(path) 必须是 object")
            }
            return result
        }
        func array(_ value: Any, _ path: String) throws -> [Any] {
            guard let result = value as? [Any] else {
                throw KMPSessionControlStoreError.invalidPatch("\(path) 必须是 array")
            }
            return result
        }
        func allow(_ value: Any, keys: Set<String>, path: String) throws -> [String: Any] {
            let result = try object(value, path)
            let unknown = Set(result.keys).subtracting(keys)
            guard unknown.isEmpty else {
                throw KMPSessionControlStoreError.invalidPatch(
                    "\(path) 包含未知字段 \(unknown.sorted())；新语义必须升级 schema"
                )
            }
            return result
        }
        func ifPresent(_ value: Any?, _ body: (Any) throws -> Void) rethrows {
            guard let value, !(value is NSNull) else { return }
            try body(value)
        }
        func validateSelection(_ value: Any, _ path: String) throws {
            _ = try allow(value, keys: ["provider", "model", "reasoningEffort"], path: path)
        }
        func validatePressure(_ value: Any, _ path: String) throws {
            _ = try allow(
                value,
                keys: ["pressureTokens", "projectedTokens", "contextWindow"],
                path: path
            )
        }
        func validateTotals(_ value: Any, _ path: String) throws {
            _ = try allow(
                value,
                keys: ["inputTokens", "outputTokens", "cacheReadTokens", "cacheWriteTokens", "reasoningTokens"],
                path: path
            )
        }
        func validateCatalog(_ value: Any, _ path: String) throws {
            let catalog = try allow(value, keys: ["current", "routable", "groups"], path: path)
            try ifPresent(catalog["current"]) { try validateSelection($0, "\(path).current") }
            try ifPresent(catalog["groups"]) { groupsValue in
                for (groupIndex, groupValue) in try array(groupsValue, "\(path).groups").enumerated() {
                    let groupPath = "\(path).groups[\(groupIndex)]"
                    let group = try allow(groupValue, keys: ["id", "name", "models"], path: groupPath)
                    try ifPresent(group["models"]) { modelsValue in
                        for (modelIndex, modelValue) in try array(modelsValue, "\(groupPath).models").enumerated() {
                            let modelPath = "\(groupPath).models[\(modelIndex)]"
                            let model = try allow(modelValue, keys: ["id", "name", "reasoning"], path: modelPath)
                            try ifPresent(model["reasoning"]) { reasoningValue in
                                let reasoningPath = "\(modelPath).reasoning"
                                let reasoning = try allow(
                                    reasoningValue,
                                    keys: ["efforts", "defaultEffort"],
                                    path: reasoningPath
                                )
                                try ifPresent(reasoning["efforts"]) { effortsValue in
                                    for (index, effort) in try array(
                                        effortsValue,
                                        "\(reasoningPath).efforts"
                                    ).enumerated() {
                                        _ = try allow(
                                            effort,
                                            keys: ["id", "name"],
                                            path: "\(reasoningPath).efforts[\(index)]"
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        func validatePermission(_ value: Any, _ path: String) throws {
            let permission = try allow(
                value,
                keys: ["options", "currentValue", "preset", "sandbox", "approval"],
                path: path
            )
            try ifPresent(permission["options"]) { options in
                for (index, option) in try array(options, "\(path).options").enumerated() {
                    _ = try allow(option, keys: ["value", "name"], path: "\(path).options[\(index)]")
                }
            }
        }
        func validateContext(_ value: Any, _ path: String) throws {
            let context = try allow(
                value,
                keys: ["asOfSeq", "tokenUsage", "pressure", "breakdown"],
                path: path
            )
            try ifPresent(context["tokenUsage"]) { usageValue in
                let usagePath = "\(path).tokenUsage"
                let usage = try allow(
                    usageValue,
                    keys: ["uncachedInputTokens", "outputTokens", "cacheReadTokens", "cacheWriteTokens", "totals"],
                    path: usagePath
                )
                try ifPresent(usage["totals"]) { try validateTotals($0, "\(usagePath).totals") }
            }
            try ifPresent(context["pressure"]) { try validatePressure($0, "\(path).pressure") }
            try ifPresent(context["breakdown"]) {
                _ = try allow(
                    $0,
                    keys: ["systemTokens", "toolsTokens", "messageTokens"],
                    path: "\(path).breakdown"
                )
            }
        }
        func validateStats(_ value: Any, _ path: String) throws {
            let snapshot = try allow(
                value,
                keys: ["asOfSeq", "stats", "tokenUsage", "contextPressure"],
                path: path
            )
            try ifPresent(snapshot["stats"]) {
                _ = try allow(
                    $0,
                    keys: [
                        "turns", "steps", "llmMs", "toolMs", "ttftMs", "ttftSteps",
                        "decodeMs", "decodeTokens", "lastTurn", "openStep", "pendingCalls"
                    ],
                    path: "\(path).stats"
                )
                // pendingCalls 是协议中的 JSONValue，其子树字段由 Gateway 业务语义管理。
            }
            try ifPresent(snapshot["tokenUsage"]) { usageValue in
                let usage = try allow(usageValue, keys: ["totals"], path: "\(path).tokenUsage")
                try ifPresent(usage["totals"]) { try validateTotals($0, "\(path).tokenUsage.totals") }
            }
            try ifPresent(snapshot["contextPressure"]) {
                try validatePressure($0, "\(path).contextPressure")
            }
        }
        func validateTarget(_ value: Any, _ path: String) throws {
            _ = try allow(
                value,
                keys: ["kind", "isDefault", "sessionId", "provider", "model", "reasoningEffort", "target", "value"],
                path: path
            )
        }
        func validateObjectMap(
            _ value: Any?,
            path: String,
            validator: (Any, String) throws -> Void
        ) throws {
            try ifPresent(value) { mapValue in
                for (key, item) in try object(mapValue, path) {
                    try validator(item, "\(path).\(key)")
                }
            }
        }

        let topKeys: Set<String> = [
            "schema", "modelCatalogsUpsert", "modelCatalogsRemove",
            "sessionPermissionsUpsert", "sessionPermissionsRemove",
            "contextSnapshotsUpsert", "contextSnapshotsRemove",
            "sessionStatsSnapshotsUpsert", "sessionStatsSnapshotsRemove",
            "globalModelCatalogChanged", "globalModelCatalog",
            "agentPresetsChanged", "agentPresets", "agentPresetsAuthorable",
            "agentPresetsHasDocument", "agentPresetDefaultChanged", "agentPresetDefault",
            "permissionDefaultChanged", "permissionDefault", "defaultModelSelectionChanged",
            "defaultModelSelection", "control"
        ]
        let strictPatch = try allow(patch, keys: topKeys, path: "$")
        try validateObjectMap(strictPatch["modelCatalogsUpsert"], path: "$.modelCatalogsUpsert", validator: validateCatalog)
        try validateObjectMap(strictPatch["sessionPermissionsUpsert"], path: "$.sessionPermissionsUpsert", validator: validatePermission)
        try validateObjectMap(strictPatch["contextSnapshotsUpsert"], path: "$.contextSnapshotsUpsert", validator: validateContext)
        try validateObjectMap(strictPatch["sessionStatsSnapshotsUpsert"], path: "$.sessionStatsSnapshotsUpsert", validator: validateStats)
        try ifPresent(strictPatch["globalModelCatalog"]) { try validateCatalog($0, "$.globalModelCatalog") }
        try ifPresent(strictPatch["agentPresets"]) { presetsValue in
            for (index, presetValue) in try array(presetsValue, "$.agentPresets").enumerated() {
                _ = try allow(
                    presetValue,
                    keys: ["id", "trust", "isDefault", "name", "description", "broken"],
                    path: "$.agentPresets[\(index)]"
                )
                // trust 是显式 JSONValue 信任边界，不对其业务子树做未知字段判定。
            }
        }
        try ifPresent(strictPatch["defaultModelSelection"]) {
            try validateSelection($0, "$.defaultModelSelection")
        }
        try ifPresent(strictPatch["control"]) { controlValue in
            let controlPath = "$.control"
            let control = try allow(
                controlValue,
                keys: [
                    "loadingKinds", "defaultConfigurationLoadingKinds", "pendingModelsSessionId",
                    "isPendingGlobalModelsRequest", "pendingModelSelectionSessionId",
                    "pendingPermissionOptionsSessionId", "requestTokens", "activeRequestTargets",
                    "queuedRequestTargets", "previousCompletedRequestTargets",
                    "explicitSessionRequiredKinds", "drainingRequestKinds",
                    "quarantinedRequestKinds"
                ],
                path: controlPath
            )
            for mapName in ["activeRequestTargets", "queuedRequestTargets", "previousCompletedRequestTargets"] {
                try validateObjectMap(
                    control[mapName],
                    path: "\(controlPath).\(mapName)",
                    validator: validateTarget
                )
            }
        }
    }

    private func applyNullable<T: Equatable>(
        changedFlag: Bool,
        value: T?,
        oldValue: T?,
        name: String,
        assign: (T?) -> Void,
        changed: inout Bool
    ) throws {
        guard changedFlag else {
            guard value == nil else {
                throw KMPSessionControlStoreError.invalidSnapshot("\(name) payload 缺少 changed 标记")
            }
            return
        }
        guard oldValue != value else {
            throw KMPSessionControlStoreError.invalidSnapshot("\(name) patch 没有变化")
        }
        assign(value)
        changed = true
    }

    private func effectIsSemanticallyValid(
        _ effect: KMPSessionControlEffect,
        oldSnapshot: KMPSessionControlSnapshot,
        nextSnapshot: KMPSessionControlSnapshot,
        intent: KMPSessionControlIntent?,
        completedKind: String?
    ) -> Bool {
        guard effect.kind == effect.requestKey,
              !effect.requestToken.isEmpty,
              nextSnapshot.requestTokens[effect.requestKey] == effect.requestToken,
              let active = nextSnapshot.activeRequestTargets[effect.requestKey],
              effect.kind == active.kind,
              effectPayloadIsComplete(effect) else { return false }
        let matchesActive = effect.sessionId == active.sessionId
            && effect.provider == active.provider
            && effect.model == active.model
            && effect.reasoningEffort == active.reasoningEffort
            && effect.target == active.target
            && effect.value == active.value
        guard matchesActive else { return false }

        if let directTarget = directRequestTarget(for: intent) {
            return completedKind == nil && directTarget == active
        }
        // response/failure/timeout 只能启动提交前已经存在的 queued target；projection 和普通
        // 无 queued response 不能借 KMP 异常结果偷渡网络 I/O。
        guard let completedKind,
              completedKind == effect.requestKey,
              oldSnapshot.queuedRequestTargets[completedKind] == active else { return false }
        switch intent {
        case .action, .defaultModelSaved, .requestFinished, .requestTimedOut, .requestFailed:
            return true
        case .projection, .requestsDisconnected, .none,
             .requestModels, .requestPermissionOptions, .requestContextUsage, .requestSessionStats,
             .requestAgentPresets, .requestDefaults, .requestDefaultModel, .selectModel,
             .setPermission, .saveDefaultModel, .setDefault, .clearSessionData,
             .clearSessionsData:
            return false
        }
    }

    private func directRequestTarget(for intent: KMPSessionControlIntent?) -> KMPSessionControlRequestTarget? {
        switch intent {
        case .requestModels(let sessionID, true):
            return .init(kind: "models", isDefault: false, sessionId: sessionID)
        case .requestPermissionOptions(let sessionID, true):
            return .init(kind: "permission-options", isDefault: false, sessionId: sessionID)
        case .requestContextUsage(let sessionID, true):
            return .init(kind: "context-usage", isDefault: false, sessionId: sessionID)
        case .requestSessionStats(let sessionID, true):
            return .init(kind: "session-stats", isDefault: false, sessionId: sessionID)
        case .requestAgentPresets(true):
            return .init(kind: "agent-presets", isDefault: true)
        case .requestDefaults(true):
            return .init(kind: "defaults", isDefault: true)
        case .requestDefaultModel(true):
            return .init(kind: "default-model", isDefault: true)
        case .selectModel(let sessionID, let selection, true):
            return .init(
                kind: "select-model", isDefault: false, sessionId: sessionID,
                provider: selection.provider, model: selection.model,
                reasoningEffort: selection.reasoningEffort
            )
        case .setPermission(let sessionID, let value, true):
            return .init(kind: "permission", isDefault: false, sessionId: sessionID, value: value)
        case .saveDefaultModel(let selection, true):
            return .init(
                kind: "save-default-model", isDefault: true,
                provider: selection.provider, model: selection.model,
                reasoningEffort: selection.reasoningEffort
            )
        case .setDefault(let target, let value, true):
            return .init(kind: "set-default", isDefault: true, target: target, value: value)
        default:
            return nil
        }
    }

    private func effectPayloadIsComplete(_ effect: KMPSessionControlEffect) -> Bool {
        let session = effect.sessionId?.isEmpty == false
        let provider = effect.provider?.isEmpty == false
        let model = effect.model?.isEmpty == false
        let target = effect.target?.isEmpty == false
        let value = effect.value?.isEmpty == false
        switch effect.kind {
        case "models":
            return (effect.sessionId == nil || session)
                && effect.provider == nil && effect.model == nil && effect.reasoningEffort == nil
                && effect.target == nil && effect.value == nil
        case "permission-options", "context-usage", "session-stats":
            return session && effect.provider == nil && effect.model == nil
                && effect.reasoningEffort == nil && effect.target == nil && effect.value == nil
        case "agent-presets", "defaults", "default-model":
            return effect.sessionId == nil && effect.provider == nil && effect.model == nil
                && effect.reasoningEffort == nil && effect.target == nil && effect.value == nil
        case "select-model":
            return session && provider && model && effect.target == nil && effect.value == nil
        case "permission":
            return session && value && effect.provider == nil && effect.model == nil
                && effect.reasoningEffort == nil && effect.target == nil
        case "save-default-model":
            return provider && model && effect.sessionId == nil && effect.target == nil && effect.value == nil
        case "set-default":
            return target && value && effect.sessionId == nil && effect.provider == nil
                && effect.model == nil && effect.reasoningEffort == nil
                && (effect.target == "permission" || effect.target == "agent-preset")
        default:
            return false
        }
    }

    private func failed(_ error: KMPSessionControlStoreError) -> KMPSessionControlTransition {
        KMPSessionControlTransition(
            snapshot: snapshot,
            patch: nil,
            effects: [],
            applied: false,
            committed: false,
            completedKind: nil,
            completedRequestToken: nil,
            retiredRequestKinds: [],
            error: error
        )
    }

    private func unchanged() -> KMPSessionControlTransition {
        KMPSessionControlTransition(
            snapshot: snapshot,
            patch: nil,
            effects: [],
            applied: false,
            committed: false,
            completedKind: nil,
            completedRequestToken: nil,
            retiredRequestKinds: [],
            error: nil
        )
    }

    private func failClosed(_ error: KMPSessionControlStoreError) -> KMPSessionControlTransition {
        runtimeError = error
        return failed(error)
    }
}
