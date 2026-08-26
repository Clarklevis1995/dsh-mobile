import Foundation

struct GatewayFrameRoutingContext {
    var selectedSessionID: String?
    var pendingHistorySessionID: String?
    var pendingModelsSessionID: String?
    var isPendingGlobalModelsRequest: Bool
    var pendingModelSelectionSessionID: String?
    var pendingPermissionOptionsSessionID: String?
}

struct GatewayHelloPayload {
    var protocolVersion: Int
    var capabilities: [String]
    var authenticated: Bool
    var clients: Int
}

struct GatewayHistoryPayload: Sendable {
    var sessionID: String?
    var rawEvents: [RawSessionEvent]
    var hasMore: Bool
    var nextBeforeSequence: Int?
    var byteCount: Int
    var projections: JSONValue?
}

struct GatewayAttachmentPayload: Sendable {
    var sessionID: String?
    var attachment: GatewayImageAttachment
    var base64Data: String?
}

struct GatewayFailurePayload {
    var requestType: String?
    var code: String?
    var message: String?
    var sessionID: String?
    var rpcID: String?
}

enum GatewayConnectionRoute {
    case paired(deviceName: String?)
    case hello(GatewayHelloPayload)
    case pong(timestampMilliseconds: Double?)
    case subscribed(sessionID: String?)
}

enum GatewayContentRoute {
    case sent(sessionID: String, command: JSONValue?)
    case liveEvent(SessionEvent)
    case workspaces([GatewayWorkspace], archivedSessionIDs: Set<String>)
    case sessions([GatewaySessionSummary])
    case history(GatewayHistoryPayload)
    case attachment(GatewayAttachmentPayload)
    case search([GatewaySearchItem], hasMore: Bool)
    case host(GatewayHostSnapshot)
}

enum GatewayControlRoute {
    case action(SessionControlAction, finishRequest: String?)
    case saveDefaultModel(GatewayModelSelection?)
    case setDefault(applied: Bool, target: String?, value: String?)
    case modelSelected(sessionID: String?, selection: GatewayModelSelection?)
    case permissionOptions(sessionID: String?, permissions: GatewaySessionPermissions?)
    case permissionSelected(sessionID: String?, value: String?)
}

enum GatewayWorkspaceRoute {
    case directories(
        path: String?,
        home: String?,
        crumbs: [GatewayDirectoryItem],
        entries: [GatewayDirectoryItem]
    )
    case directoryCreated(path: String?)
    case workspaceCreated(GatewayWorkspace?, created: Bool)
}

enum GatewayQuestionRoute {
    case requested(
        action: QuestionAction,
        sessionID: String,
        preview: String,
        replay: Bool
    )
    case invalidRequest(sessionID: String?)
    case response(
        action: QuestionAction,
        rpcID: String,
        wasNotPending: Bool
    )
    case resolved(
        action: QuestionAction,
        rpcID: String,
        sessionID: String?,
        cancelled: Bool
    )
}

enum GatewayFrameRoute {
    case connection(GatewayConnectionRoute)
    case content(GatewayContentRoute)
    case control(GatewayControlRoute)
    case workspace(GatewayWorkspaceRoute)
    case question(GatewayQuestionRoute)
    case failure(GatewayFailurePayload)
    case ignored
    case unknown(String)
}

enum GatewayFrameRouter {
    static func route(
        _ frame: GatewayFrame,
        context: GatewayFrameRoutingContext
    ) -> GatewayFrameRoute {
        switch frame.kind {
        case "paired":
            return .connection(.paired(deviceName: frame.device?.name))
        case "hello":
            return .connection(.hello(GatewayHelloPayload(
                protocolVersion: frame.protocol ?? 1,
                capabilities: frame.capabilities ?? [],
                authenticated: frame.authenticated != false,
                clients: frame.clients ?? 1
            )))
        case "pong":
            return .connection(.pong(timestampMilliseconds: frame.at))
        case "subscribed":
            return .connection(.subscribed(sessionID: frame.sessionId))
        case "sent":
            guard let sessionID = frame.sessionId else { return .ignored }
            return .content(.sent(sessionID: sessionID, command: frame.command))
        case "event":
            guard let sessionID = frame.sessionId,
                  let sequence = frame.seq,
                  let time = frame.time,
                  let event = frame.event else { return .ignored }
            return .content(.liveEvent(SessionEvent(
                sessionId: sessionID,
                seq: sequence,
                time: time,
                event: event
            )))
        case "workspaces":
            return .content(.workspaces(
                decodeItems(frame.items, as: GatewayWorkspace.self),
                archivedSessionIDs: Set(frame.archivedSessionIds ?? [])
            ))
        case "sessions":
            return .content(.sessions(decodeItems(frame.items, as: GatewaySessionSummary.self)))
        case "history":
            return .content(.history(GatewayHistoryPayload(
                sessionID: frame.sessionId ?? context.pendingHistorySessionID ?? context.selectedSessionID,
                rawEvents: frame.events ?? [],
                hasMore: frame.hasMore ?? false,
                nextBeforeSequence: frame.nextBeforeSeq,
                byteCount: frame.bytes ?? 0,
                projections: frame.projections
            )))
        case "attachment":
            guard let attachment = frame.attachment else { return .ignored }
            return .content(.attachment(GatewayAttachmentPayload(
                sessionID: frame.sessionId,
                attachment: attachment,
                base64Data: frame.data
            )))
        case "search":
            return .content(.search(
                decodeItems(frame.items, as: GatewaySearchItem.self),
                hasMore: frame.hasMore == true
            ))
        case "host":
            return .content(.host(GatewayHostSnapshot(
                version: frame.version,
                cwd: frame.cwd,
                provider: frame.provider,
                model: frame.model,
                attachedSessions: frame.attachedSessions,
                canOpenPath: frame.canOpenPath
            )))
        case "agent-presets":
            return .control(.action(.agentPresetsReceived(
                frame.presets ?? [],
                authorable: frame.authorable ?? false,
                hasDocument: frame.hasDocument ?? false
            ), finishRequest: "agent-presets"))
        case "defaults":
            return .control(.action(.defaultsReceived(
                agentPreset: frame.agentPresetDefault,
                permission: frame.permissionDefault
            ), finishRequest: "defaults"))
        case "default-model":
            return .control(.action(
                .defaultModelReceived(frame.selection),
                finishRequest: "default-model"
            ))
        case "save-default-model":
            return .control(.saveDefaultModel(frame.saved))
        case "set-default":
            return .control(.setDefault(
                applied: frame.applied == true,
                target: frame.target,
                value: frame.value
            ))
        case "models":
            return .control(.action(.modelsReceived(
                sessionID: frame.sessionId ?? context.pendingModelsSessionID,
                current: frame.current,
                routable: frame.routable ?? false,
                groups: frame.groups ?? [],
                isGlobalRequest: context.isPendingGlobalModelsRequest
            ), finishRequest: "models"))
        case "select-model":
            return .control(.modelSelected(
                sessionID: frame.sessionId ?? context.pendingModelSelectionSessionID,
                selection: frame.selected
            ))
        case "permission-options":
            return .control(.permissionOptions(
                sessionID: frame.sessionId ?? context.pendingPermissionOptionsSessionID,
                permissions: frame.sessionPermissions
            ))
        case "permission":
            return .control(.permissionSelected(
                sessionID: frame.sessionId ?? context.selectedSessionID,
                value: frame.set
            ))
        case "context-usage":
            guard let sessionID = frame.sessionId ?? context.selectedSessionID else {
                return .control(.action(.requestFinished("context-usage"), finishRequest: "context-usage"))
            }
            return .control(.action(.contextReceived(
                sessionID: sessionID,
                asOfSequence: frame.asOfSeq,
                tokenUsage: frame.tokenUsage,
                pressure: frame.contextPressure,
                breakdown: nil
            ), finishRequest: "context-usage"))
        case "session-stats":
            guard let sessionID = frame.sessionId ?? context.selectedSessionID else {
                return .control(.action(.requestFinished("session-stats"), finishRequest: "session-stats"))
            }
            return .control(.action(.statsReceived(
                sessionID: sessionID,
                asOfSequence: frame.asOfSeq,
                stats: frame.sessionStats,
                tokenUsageTotals: frame.tokenUsage?.totals,
                contextPressure: frame.contextPressure
            ), finishRequest: "session-stats"))
        case "directories":
            return .workspace(.directories(
                path: frame.path,
                home: frame.home,
                crumbs: frame.crumbs ?? [],
                entries: frame.entries ?? []
            ))
        case "directory-create":
            return .workspace(.directoryCreated(path: frame.path))
        case "workspace-create":
            return .workspace(.workspaceCreated(frame.workspace, created: frame.created == true))
        case "question-requested":
            guard let rpcID = frame.rpcId,
                  !rpcID.isEmpty,
                  let sessionID = frame.sessionId,
                  !sessionID.isEmpty,
                  let questions = frame.questions,
                  !questions.isEmpty else {
                return .question(.invalidRequest(sessionID: frame.sessionId))
            }
            let request = GatewayPendingQuestionRequest(
                rpcId: rpcID,
                sessionId: sessionID,
                questions: questions,
                replay: frame.replay == true
            )
            return .question(.requested(
                action: .requestReceived(request),
                sessionID: sessionID,
                preview: questions.first?.question ?? "",
                replay: frame.replay == true
            ))
        case "question-response":
            guard let rpcID = frame.rpcId else { return .ignored }
            let reason = frame.reason ?? "bad-response"
            return .question(.response(
                action: .responseReceived(
                    rpcID: rpcID,
                    action: GatewayQuestionAction(rawValue: frame.action ?? "") ?? .answer,
                    accepted: frame.accepted == true,
                    reason: reason
                ),
                rpcID: rpcID,
                wasNotPending: frame.accepted != true && reason == "not-pending"
            ))
        case "question-resolved":
            guard let rpcID = frame.rpcId else { return .ignored }
            return .question(.resolved(
                action: .resolved(rpcID: rpcID),
                rpcID: rpcID,
                sessionID: frame.sessionId,
                cancelled: frame.outcome == "cancelled"
            ))
        case "error":
            return .failure(GatewayFailurePayload(
                requestType: frame.requestType,
                code: frame.code,
                message: frame.message,
                sessionID: frame.sessionId,
                rpcID: frame.rpcId
            ))
        default:
            return .unknown(frame.kind)
        }
    }

    private static func decodeItems<T: Decodable>(_ items: [JSONValue]?, as type: T.Type) -> [T] {
        (items ?? []).compactMap { $0.decode(type) }
    }
}
