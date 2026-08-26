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
            case .requested(let action, let sessionID, _, let replay):
                guard case .requestReceived(let request) = action else {
                    return make("question", "requested", sessionId: sessionID, replay: replay)
                }
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
            case .response(let action, let rpcID, let wasNotPending):
                guard case .responseReceived(_, let responseAction, let accepted, _) = action else {
                    return make("question", "response", rpcId: rpcID)
                }
                return make(
                    "question",
                    "response",
                    rpcId: rpcID,
                    action: responseAction.rawValue,
                    accepted: accepted,
                    outcome: wasNotPending ? "not-pending" : nil
                )
            case .resolved(_, let rpcID, let sessionID, let cancelled):
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
