import Foundation
import OSLog
import DeepSeekHarnessShared

/// SwiftUI/AppStore 与 KMP 之间的薄边界。
struct KMPSharedAdapter {
    private let facade = SharedMobileFacade()

    var moduleSummary: String { facade.moduleSummary() }

    /// 坏 JSON 返回 nil，禁止 Kotlin 异常越过 Swift 边界。
    func decodeFrameKind(_ json: String) -> String? {
        facade.decodeFrameKind(json: json)
    }

    func makeStore() -> SharedMobileStore { facade.makeStore() }
    func makeShadowFacade() -> SharedShadowFacade { facade.makeShadowFacade() }
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

/// AppStore 的 MainActor 是唯一串行入口；本适配器只映射稳定 JSON 值并调用 KMP。
@MainActor
final class KMPSessionListStoreAdapter {
    private let store: any KMPSessionListStoreBridging
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

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
    }

    @discardableResult
    func reduce(_ action: SessionListAction, now: Date = .now) throws -> KMPSessionListSnapshot {
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
        do {
            snapshot = try decode(result)
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
        return decode(result, requiresSnapshot: false, intent: intent)
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

struct KMPShadowRouteFingerprint: Codable, Equatable, CustomStringConvertible {
    var category: String
    var route: String
    var sessionId: String?
    var rpcId: String?
    var requestType: String?
    var finishRequest: String?
    var action: String?
    var accepted: Bool?
    var hasMore: Bool?
    var itemCount: Int?
    var replay: Bool?
    var outcome: String?
    var target: String?
    var value: String?
    var applied: Bool?
    var malformedReason: String?

    var description: String {
        (try? JSONEncoder().encode(self)).map { String(decoding: $0, as: UTF8.self) }
            ?? "<invalid-fingerprint>"
    }
}

struct KMPShadowPlatformEffect: Codable, Equatable {
    var kind: String
    var sessionId: String?
    var requestType: String?
    var rpcId: String?
}

struct KMPShadowDifference: Equatable {
    var frameKind: String
    var swift: KMPShadowRouteFingerprint?
    var kmp: KMPShadowRouteFingerprint?
    var errorCode: String?
    var errorMessage: String?
}

struct KMPShadowValidationResult {
    var fingerprint: KMPShadowRouteFingerprint?
    var effects: [KMPShadowPlatformEffect]
    var difference: KMPShadowDifference?
}

/// 所有影子调用在 AppStore 的 MainActor 上串行发生，且只比较、不执行 effect。
@MainActor
final class KMPShadowValidator {
    private let facade: SharedShadowFacade
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()
    private let logger = Logger(subsystem: "com.clarklevis.dsh.mobile", category: "KMPShadow")

    private(set) var differences: [KMPShadowDifference] = []

    init(facade: SharedShadowFacade = KMPSharedAdapter().makeShadowFacade()) {
        self.facade = facade
        logger.notice("KMP 只读影子验证已启用；影子 effect 不会执行")
    }

    @discardableResult
    func validate(
        frame: GatewayFrame,
        context: GatewayFrameRoutingContext,
        swiftRoute: GatewayFrameRoute
    ) -> KMPShadowValidationResult {
        do {
            let frameJSON = String(decoding: try encoder.encode(frame), as: UTF8.self)
            let contextJSON = String(decoding: try encoder.encode(context), as: UTF8.self)
            let result = facade.routeFrame(frameJson: frameJSON, contextJson: contextJSON)
            let swiftFingerprint = Self.fingerprint(frame: frame, route: swiftRoute)
            guard result.isSuccess, let routeJSON = result.routeJson else {
                return record(KMPShadowDifference(
                    frameKind: frame.kind,
                    swift: swiftFingerprint,
                    kmp: nil,
                    errorCode: result.errorCode,
                    errorMessage: result.errorMessage
                ))
            }

            let kmpFingerprint = try decoder.decode(
                KMPShadowRouteFingerprint.self,
                from: Data(routeJSON.utf8)
            )
            let effects = try decoder.decode(
                [KMPShadowPlatformEffect].self,
                from: Data(result.effectsJson.utf8)
            )
            guard swiftFingerprint == kmpFingerprint else {
                return record(
                    KMPShadowDifference(
                        frameKind: frame.kind,
                        swift: swiftFingerprint,
                        kmp: kmpFingerprint,
                        errorCode: "route-mismatch",
                        errorMessage: nil
                    ),
                    fingerprint: kmpFingerprint,
                    effects: effects
                )
            }
            return KMPShadowValidationResult(
                fingerprint: kmpFingerprint,
                effects: effects,
                difference: nil
            )
        } catch {
            return record(KMPShadowDifference(
                frameKind: frame.kind,
                swift: Self.fingerprint(frame: frame, route: swiftRoute),
                kmp: nil,
                errorCode: "swift-bridge-error",
                errorMessage: error.localizedDescription
            ))
        }
    }

    private func record(
        _ difference: KMPShadowDifference,
        fingerprint: KMPShadowRouteFingerprint? = nil,
        effects: [KMPShadowPlatformEffect] = []
    ) -> KMPShadowValidationResult {
        differences.append(difference)
        logger.error("KMP 影子差异 frame=\(difference.frameKind, privacy: .public) code=\(difference.errorCode ?? "unknown", privacy: .public) swift=\(String(describing: difference.swift), privacy: .public) kmp=\(String(describing: difference.kmp), privacy: .public)")
        return KMPShadowValidationResult(
            fingerprint: fingerprint,
            effects: effects,
            difference: difference
        )
    }

    static func fingerprint(
        frame: GatewayFrame,
        route: GatewayFrameRoute
    ) -> KMPShadowRouteFingerprint {
        switch route {
        case .connection(let route):
            switch route {
            case .paired: return make("connection", "paired")
            case .hello: return make("connection", "hello")
            case .pong: return make("connection", "pong")
            case .subscribed(let sessionID):
                return make("connection", "subscribed", sessionId: sessionID)
            }
        case .content(let route):
            switch route {
            case .sent(let sessionID, _):
                return make("content", "sent", sessionId: sessionID)
            case .liveEvent(let record):
                return make("content", "live-event", sessionId: record.sessionId)
            case .workspaces(let items, _):
                return make("content", "workspaces", itemCount: items.count)
            case .sessions(let items):
                return make("content", "sessions", itemCount: items.count)
            case .history(let payload):
                return make(
                    "content",
                    "history",
                    sessionId: payload.sessionID,
                    hasMore: payload.hasMore,
                    itemCount: payload.rawEvents.count
                )
            case .attachment(let payload):
                return make("content", "attachment", sessionId: payload.sessionID)
            case .search(let items, let hasMore):
                return make("content", "search", hasMore: hasMore, itemCount: items.count)
            case .host:
                return make("content", "host")
            }
        case .control(let route):
            return controlFingerprint(route)
        case .workspace(let route):
            switch route {
            case .directories(_, _, _, let entries):
                return make("workspace", "directories", itemCount: entries.count)
            case .directoryCreated:
                return make("workspace", "directory-create")
            case .workspaceCreated(_, let created):
                return make("workspace", "workspace-create", applied: created)
            }
        case .question(let route):
            switch route {
            case .requested(let request, let sessionID, _, let replay):
                return make(
                    "question",
                    "requested",
                    sessionId: sessionID,
                    rpcId: request.rpcId,
                    itemCount: request.questions.count,
                    replay: replay
                )
            case .invalidRequest(let sessionID):
                return make(
                    "question",
                    "invalid-request",
                    sessionId: sessionID,
                    malformedReason: "missing-request-fields"
                )
            case .response(let rpcID, let responseAction, let accepted, _, let wasNotPending):
                return make(
                    "question",
                    "response",
                    rpcId: rpcID,
                    action: responseAction.rawValue,
                    accepted: accepted,
                    outcome: wasNotPending ? "not-pending" : nil
                )
            case .resolved(let rpcID, let sessionID, let cancelled):
                return make(
                    "question",
                    "resolved",
                    sessionId: sessionID,
                    rpcId: rpcID,
                    outcome: cancelled ? "cancelled" : frame.outcome
                )
            }
        case .failure(let payload):
            return make(
                "failure",
                "error",
                sessionId: payload.sessionID,
                rpcId: payload.rpcID,
                requestType: payload.requestType
            )
        case .ignored:
            return make(
                "ignored",
                frame.kind,
                malformedReason: malformedReason(for: frame)
            )
        case .unknown(let kind):
            return make("unknown", kind)
        }
    }

    private static func controlFingerprint(_ route: GatewayControlRoute) -> KMPShadowRouteFingerprint {
        switch route {
        case .action(let action, let finishRequest):
            switch action {
            case .agentPresetsReceived(let presets, _, _):
                return make("control", "agent-presets", finishRequest: finishRequest, itemCount: presets.count)
            case .defaultsReceived:
                return make("control", "defaults", finishRequest: finishRequest)
            case .defaultModelReceived:
                return make("control", "default-model", finishRequest: finishRequest)
            case .modelsReceived(let sessionID, _, _, let groups, let isGlobalRequest):
                return make(
                    "control",
                    "models",
                    sessionId: sessionID,
                    finishRequest: finishRequest,
                    action: isGlobalRequest ? "global" : "session",
                    itemCount: groups.count
                )
            case .contextReceived(let sessionID, _, _, _, _):
                return make(
                    "control",
                    "context-usage",
                    sessionId: sessionID,
                    finishRequest: finishRequest,
                    action: "context-received"
                )
            case .statsReceived(let sessionID, _, _, _, _):
                return make(
                    "control",
                    "session-stats",
                    sessionId: sessionID,
                    finishRequest: finishRequest,
                    action: "stats-received"
                )
            case .requestFinished(let request):
                return make(
                    "control",
                    request,
                    finishRequest: finishRequest,
                    action: "request-finished"
                )
            default:
                return make("control", "unsupported-action", finishRequest: finishRequest)
            }
        case .saveDefaultModel:
            return make("control", "save-default-model")
        case .setDefault(let applied, let target, let value):
            return make(
                "control",
                "set-default",
                target: target,
                value: value,
                applied: applied
            )
        case .modelSelected(let sessionID, _):
            return make("control", "select-model", sessionId: sessionID)
        case .permissionOptions(let sessionID, let permissions):
            return make(
                "control",
                "permission-options",
                sessionId: sessionID,
                itemCount: permissions?.options?.count ?? 0
            )
        case .permissionSelected(let sessionID, let value):
            return make("control", "permission", sessionId: sessionID, value: value)
        }
    }

    private static func malformedReason(for frame: GatewayFrame) -> String? {
        switch frame.kind {
        case "sent": return "missing-session-id"
        case "event":
            var missing: [String] = []
            if frame.sessionId == nil { missing.append("session-id") }
            if frame.seq == nil { missing.append("sequence") }
            if frame.time == nil { missing.append("time") }
            if frame.event == nil { missing.append("event") }
            return "missing-\(missing.joined(separator: "-"))"
        case "attachment": return "missing-attachment"
        case "question-response", "question-resolved": return "missing-rpc-id"
        default: return nil
        }
    }

    private static func make(
        _ category: String,
        _ route: String,
        sessionId: String? = nil,
        rpcId: String? = nil,
        requestType: String? = nil,
        finishRequest: String? = nil,
        action: String? = nil,
        accepted: Bool? = nil,
        hasMore: Bool? = nil,
        itemCount: Int? = nil,
        replay: Bool? = nil,
        outcome: String? = nil,
        target: String? = nil,
        value: String? = nil,
        applied: Bool? = nil,
        malformedReason: String? = nil
    ) -> KMPShadowRouteFingerprint {
        KMPShadowRouteFingerprint(
            category: category,
            route: route,
            sessionId: sessionId,
            rpcId: rpcId,
            requestType: requestType,
            finishRequest: finishRequest,
            action: action,
            accepted: accepted,
            hasMore: hasMore,
            itemCount: itemCount,
            replay: replay,
            outcome: outcome,
            target: target,
            value: value,
            applied: applied,
            malformedReason: malformedReason
        )
    }
}
