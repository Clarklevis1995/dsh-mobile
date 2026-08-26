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
