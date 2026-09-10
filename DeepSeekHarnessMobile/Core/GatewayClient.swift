import Foundation
import Security
import OSLog

@MainActor
final class GatewayClient: ObservableObject {
    private static let presetLogger = Logger(subsystem: "ai.dsh.mobile.ios", category: "agent-presets")
    private let channel: String
    var credentialID: String?
    var expectedGatewayID: String?
    var trustedEndpoints: [String] = []
    var onIdentity: ((GatewayFrame, String) throws -> Void)?
    var probeOnly = false
    private var reconnectAttempt = 0
    private var pendingConversationPayloads: [String] = []
    private var pendingConversationBytes = 0
    private var outboundTask: Task<Void, Never>?
    private let transportSession = URLSession(configuration: .ephemeral, delegate: GatewayRedirectBlocker(), delegateQueue: nil)

    static func forgetCredential(for profileID: String) {
        GatewayTokenStore.delete(for: URL(string: "https://gateway-credential.invalid/\(profileID)")!)
    }

    private func credentialURL(_ endpoint: URL) -> URL {
        credentialID.map { URL(string: "https://gateway-credential.invalid/\($0)")! } ?? endpoint
    }

    func migrateCredential(from endpoint: URL) throws {
        guard credentialID != nil, GatewayTokenStore.load(for: credentialURL(endpoint)) == nil,
              let token = GatewayTokenStore.load(for: endpoint) else { return }
        try GatewayTokenStore.save(token, for: credentialURL(endpoint))
    }

    private var conversationClient: GatewayClient?

    init(channel: String = "control") {
        self.channel = channel
    }
    /// History responses contain raw trajectory events (including request
    /// context) and can exceed URLSessionWebSocketTask's 1 MiB default.
    /// Gateway v0.1.12 normally keeps history pages below 4 MiB. Retain a much
    /// larger transport ceiling for the documented case where one indivisible
    /// event is itself larger than the page budget.
    private static let maximumIncomingMessageSize = 64 * 1024 * 1024

    @Published private(set) var state: ConnectionState = .disconnected
    @Published private(set) var serverPort: Int?
    @Published private(set) var clientCount: Int?

    var onFrame: ((GatewayFrame) -> Void)?
    /// 仅命令菜单需要原始 JSON；对话流不能触发它的解析和 UI 发布。
    var onCommandFrame: ((String) -> Void)?
    var onConnectionFailure: ((String) -> Void)?
    private var socket: URLSessionWebSocketTask?
    private var receiveTask: Task<Void, Never>?
    private var reconnectTask: Task<Void, Never>?
    private var connectionTimeoutTask: Task<Void, Never>?
    private var sessionCreationContinuations: [String: CheckedContinuation<String, Error>] = [:]

    func createSession(workspaceId: String?) async throws -> String {
        try Task.checkCancellation()
        guard state.isConnected else { throw URLError(.notConnectedToInternet) }
        let requestId = UUID().uuidString
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                sessionCreationContinuations[requestId] = continuation
                var payload: [String: Any] = ["type": "session-create", "requestId": requestId]
                if let workspaceId { payload["workspaceId"] = workspaceId }
                send(payload)
                Task { [weak self] in
                    try? await Task.sleep(for: .seconds(15))
                    self?.sessionCreationContinuations.removeValue(forKey: requestId)?
                        .resume(throwing: URLError(.timedOut))
                }
            }
        } onCancel: {
            Task { @MainActor [weak self] in
                self?.sessionCreationContinuations.removeValue(forKey: requestId)?
                    .resume(throwing: CancellationError())
            }
        }
    }

    private func failSessionCreations() {
        let pending = sessionCreationContinuations
        sessionCreationContinuations.removeAll()
        for continuation in pending.values {
            continuation.resume(throwing: URLError(.networkConnectionLost))
        }
    }

    /// 迟到的创建响应也在此消费，避免修改已经离开的页面。
    func acceptSessionCreationFrame(_ frame: GatewayFrame) -> Bool {
        guard frame.kind == "session-created" ||
                (frame.kind == "error" && frame.requestType == "session-create") else { return false }
        guard let requestId = frame.requestId,
              let continuation = sessionCreationContinuations.removeValue(forKey: requestId) else { return true }
        if frame.kind == "session-created", let sessionId = frame.sessionId, !sessionId.isEmpty {
            continuation.resume(returning: sessionId)
        } else {
            continuation.resume(throwing: NSError(
                domain: "GatewaySessionCreation", code: 1,
                userInfo: [NSLocalizedDescriptionKey: frame.message ?? "创建会话失败"]
            ))
        }
        return true
    }

    var connectedEndpoint: String? { endpoint?.absoluteString }
    private var endpoint: URL?
    private var wantsConnection = false
    private var pairingCode: String?
    /// 手动配对码是一次性的，失败后不能拿旧码在后台重复尝试。
    private var isManualPairingAttempt = false
    private var lastReportedFailure: String?
    /// iOS may suspend and tear down a normal WebSocket after the app moves
    /// into the background. Keep this lifecycle state separate from protocol
    /// failures so an expected transport interruption doesn't become a modal
    /// error when the app returns to the foreground.
    private var isApplicationInBackground = false
    private var isRecoveringFromBackground = false

    deinit {
        outboundTask?.cancel()
        receiveTask?.cancel()
        reconnectTask?.cancel()
        connectionTimeoutTask?.cancel()
        socket?.cancel(with: .goingAway, reason: nil)
        transportSession.invalidateAndCancel()
    }

    func connect(to rawEndpoint: String) {
        isRecoveringFromBackground = false
        isManualPairingAttempt = false
        beginConnection(to: rawEndpoint, pairingCode: nil, resetReportedFailure: true)
    }

    /// Cold launch should only restore a connection for a device that has
    /// already completed pairing. Opening an unauthenticated socket merely to
    /// discover that pairing is required produces a misleading failure alert.
    func hasStoredCredential(for rawEndpoint: String) -> Bool {
        guard let url = URL(string: rawEndpoint),
              ["ws", "wss"].contains(url.scheme?.lowercased() ?? "") else {
            return false
        }
        return GatewayTokenStore.load(for: credentialURL(url))?.isEmpty == false
    }

    func connectForPairing(_ payload: GatewayPairingPayload) {
        isRecoveringFromBackground = false
        isManualPairingAttempt = true
        beginConnection(to: payload.publicUrl, pairingCode: payload.pairingCode, resetReportedFailure: true)
    }

    /// Records the scene transition and optionally keeps the transport alive
    /// while AppStore owns a finite UIKit background-task assertion.
    func applicationDidEnterBackground(keepConnectionAlive: Bool) {
        isApplicationInBackground = true
        isRecoveringFromBackground = true
        if !keepConnectionAlive {
            suspendTransportForBackground()
        }
    }

    /// Restores the transport immediately instead of waiting for the delayed
    /// reconnect loop. A successful `hello` clears recovery mode.
    func applicationDidBecomeActive() {
        isApplicationInBackground = false
        guard wantsConnection, !state.isConnected, let endpoint else { return }
        reconnectTask?.cancel()
        reconnectTask = nil
        beginConnection(
            to: endpoint.absoluteString,
            pairingCode: pairingCode,
            resetReportedFailure: false
        )
    }

    /// Called when iOS revokes the finite background execution allowance.
    /// Closing deliberately avoids repeatedly reconnecting while suspended.
    func backgroundExecutionDidExpire() {
        guard isApplicationInBackground else { return }
        suspendTransportForBackground()
    }

    private func beginConnection(to rawEndpoint: String, pairingCode: String?, resetReportedFailure: Bool) {
        disconnect(reconnect: false)
        guard let url = URL(string: rawEndpoint), ["ws", "wss"].contains(url.scheme?.lowercased() ?? "") else {
            fail(String(localized: "二维码中的 publicUrl 不是有效的 ws:// 或 wss:// 地址"), shouldReconnect: false)
            return
        }
        if !trustedEndpoints.isEmpty && !trustedEndpoints.contains(url.absoluteString) {
            fail("地址未经此主机确认，请重新扫码添加地址。", shouldReconnect: false)
            return
        }
        wantsConnection = true
        endpoint = url
        self.pairingCode = pairingCode
        isManualPairingAttempt = pairingCode != nil
        if resetReportedFailure { lastReportedFailure = nil }
        state = .connecting
        var request = URLRequest(url: url)
        request.setValue(channel, forHTTPHeaderField: "X-DSH-Channel")
        do {
            request.setValue(try GatewayDeviceIdentityStore.loadOrCreate(), forHTTPHeaderField: "X-DSH-Device-ID")
        } catch {
            fail(String(localized: "gateway.device-id.unavailable", defaultValue: "无法读取或创建设备唯一标识：\(error.localizedDescription)"), shouldReconnect: false)
            return
        }
        if let pairingCode {
            request.setValue("dsh-mobile-v1, dsh-pair.\(pairingCode)", forHTTPHeaderField: "Sec-WebSocket-Protocol")
        } else if let token = GatewayTokenStore.load(for: credentialURL(url)) {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            request.setValue("dsh-mobile-v1", forHTTPHeaderField: "Sec-WebSocket-Protocol")
        } else {
            // This still permits the explicitly documented local Debug mode.
            // A production gateway responds with HTTP 401 and the UI routes the
            // user to pairing instead of silently treating the socket as ready.
            request.setValue("dsh-mobile-v1", forHTTPHeaderField: "Sec-WebSocket-Protocol")
        }
        let socket = transportSession.webSocketTask(with: request)
        socket.maximumMessageSize = Self.maximumIncomingMessageSize
        self.socket = socket
        socket.resume()
        startConnectionTimeout(for: socket)
        receiveTask = Task { [weak self] in await self?.receiveLoop(socket) }
    }

    func disconnect(reconnect: Bool = false) {
        outboundTask?.cancel()
        outboundTask = nil
        pendingConversationPayloads.removeAll()
        pendingConversationBytes = 0
        conversationClient?.disconnect()
        conversationClient = nil
        failSessionCreations()
        wantsConnection = reconnect
        reconnectTask?.cancel()
        reconnectTask = nil
        connectionTimeoutTask?.cancel()
        connectionTimeoutTask = nil
        receiveTask?.cancel()
        receiveTask = nil
        socket?.cancel(with: .normalClosure, reason: nil)
        socket = nil
        pairingCode = nil
        isManualPairingAttempt = false
        state = .disconnected
    }

    func ping() { send(["type": "ping"]) }

    func requestWorkspaces() { send(["type": "workspaces"]) }
    func requestSessions() { send(["type": "sessions"]) }
    func requestHost() { send(["type": "host"]) }
    func searchSessions(_ query: String) { send(["type": "search", "query": query]) }
    func requestDirectories(path: String? = nil) {
        var payload: [String: Any] = ["type": "directories"]
        if let path, !path.isEmpty { payload["path"] = path }
        send(payload)
    }
    func createDirectory(path: String, name: String) {
        send(["type": "directory-create", "path": path, "name": name])
    }
    func createWorkspace(path: String) { send(["type": "workspace-create", "path": path]) }
    func requestModels(sessionId: String? = nil) {
        var payload: [String: Any] = ["type": "models"]
        if let sessionId, !sessionId.isEmpty { payload["sessionId"] = sessionId }
        send(payload)
    }
    func requestProviders() {
        send(["type": "providers"])
    }
    func selectModel(sessionId: String, provider: String, model: String, reasoningEffort: String?) {
        var payload: [String: Any] = [
            "type": "select-model",
            "sessionId": sessionId,
            "provider": provider,
            "model": model
        ]
        if let reasoningEffort, !reasoningEffort.isEmpty { payload["reasoningEffort"] = reasoningEffort }
        send(payload)
    }
    func requestPermissionOptions(sessionId: String?) {
        var payload: [String: Any] = ["type": "permission-options"]
        if let sessionId, !sessionId.isEmpty { payload["sessionId"] = sessionId }
        send(payload)
    }
    func setPermission(sessionId: String, name: String) {
        send(["type": "permission", "sessionId": sessionId, "name": name])
    }
    func requestContextUsage(sessionId: String) {
        send(["type": "context-usage", "sessionId": sessionId])
    }
    func requestSessionStats(sessionId: String) {
        send(["type": "session-stats", "sessionId": sessionId])
    }
    func requestTasks(sessionId: String) {
        send(["type": "tasks", "sessionId": sessionId])
    }
    func requestGoal(sessionId: String) {
        send(["type": "goal", "sessionId": sessionId])
    }
    func editGoal(sessionId: String, ref: GatewayGoalReference, objective: String) {
        send([
            "type": "goal-edit",
            "sessionId": sessionId,
            "ref": ["id": ref.id, "revision": ref.revision],
            "objective": objective
        ])
    }
    func pauseGoal(sessionId: String, ref: GatewayGoalReference) {
        sendGoalAction("goal-pause", sessionId: sessionId, ref: ref)
    }
    func resumeGoal(sessionId: String, ref: GatewayGoalReference) {
        sendGoalAction("goal-resume", sessionId: sessionId, ref: ref)
    }
    func clearGoal(sessionId: String, ref: GatewayGoalReference) {
        sendGoalAction("goal-clear", sessionId: sessionId, ref: ref)
    }

    private func sendGoalAction(_ type: String, sessionId: String, ref: GatewayGoalReference) {
        send([
            "type": type,
            "sessionId": sessionId,
            "ref": ["id": ref.id, "revision": ref.revision]
        ])
    }
    func requestAgentPresets() {
        send(["type": "agent-presets"])
    }
    func requestDefaults() {
        send(["type": "defaults"])
    }
    func requestDefaultModel() {
        send(["type": "default-model"])
    }
    func saveDefaultModel(provider: String, model: String, reasoningEffort: String?) {
        var payload: [String: Any] = ["type": "save-default-model", "provider": provider, "model": model]
        if let reasoningEffort, !reasoningEffort.isEmpty { payload["reasoningEffort"] = reasoningEffort }
        send(payload)
    }
    func setDefault(target: String, value: String) {
        send(["type": "set-default", "target": target, "value": value])
    }
    func requestHistory(
        sessionId: String,
        beforeSeq: Int? = nil,
        maxMessages: Int = 50,
        maxBytes: Int? = nil,
        view: String? = nil
    ) {
        var payload: [String: Any] = ["type": "history", "sessionId": sessionId, "maxMessages": maxMessages]
        if let beforeSeq { payload["beforeSeq"] = beforeSeq }
        if let maxBytes { payload["maxBytes"] = maxBytes }
        if let view { payload["view"] = view }
        send(payload)
    }

    func requestAttachment(sessionId: String, attachmentId: String) {
        send([
            "type": "attachment",
            "sessionId": sessionId,
            "attachmentId": attachmentId
        ])
    }

    func subscribe(sessionId: String?) {
        gatewayApprovalTrace("transport subscribe hasSession=\(sessionId?.isEmpty == false)")
        if let sessionId, !sessionId.isEmpty {
            send(["type": "subscribe", "sessionId": sessionId])
        } else {
            send(["type": "unsubscribe"])
        }
    }

    func archiveSession(sessionId: String) {
        send(["type": "session-archive", "sessionId": sessionId])
    }

    func renameSession(sessionId: String, title: String) {
        send(["type": "session-rename", "sessionId": sessionId, "title": title])
    }

    func cancelSession(sessionId: String) {
        send(["type": "session-cancel", "sessionId": sessionId])
    }

    func sendMessage(
        text: String,
        images: [GatewayOutgoingImage] = [],
        sessionId: String?,
        workspaceId: String? = nil
    ) {
        if let conversationClient {
            conversationClient.sendMessage(text: text, images: images, sessionId: sessionId, workspaceId: workspaceId)
            return
        }
        guard let socket else {
            state = .failed(String(localized: "state.websocket.not-connected", defaultValue: "WebSocket 尚未连接"))
            return
        }
        let request = GatewayMessageRequest(
            sessionId: sessionId?.isEmpty == false ? sessionId : nil,
            text: text,
            images: images.map {
                GatewayMessageRequest.Image(
                    mediaType: $0.mediaType,
                    data: $0.data,
                    name: $0.name
                )
            },
            workspaceId: sessionId == nil && workspaceId?.isEmpty == false ? workspaceId : nil,
            clientTimeZone: TimeZone.current.identifier
        )
        Task { [weak self] in
            do {
                let payload = try await Task.detached(priority: .userInitiated) {
                    try JSONEncoder().encode(request)
                }.value
                try await socket.send(.string(String(decoding: payload, as: UTF8.self)))
            } catch {
                self?.handleFailure(error, socket: socket)
            }
        }
    }

    func executeCommand(
        line: String,
        images: [GatewayOutgoingImage] = [],
        sessionId: String?
    ) {
        guard let sessionId, !sessionId.isEmpty else {
            state = .failed(String(localized: "state.websocket.not-connected", defaultValue: "WebSocket 尚未连接"))
            return
        }
        let request = GatewayCommandExecuteRequest(
            sessionId: sessionId,
            line: line,
            images: images.map {
                GatewayMessageRequest.Image(mediaType: $0.mediaType, data: $0.data, name: $0.name)
            }
        )
        Task { [weak self] in
            guard let socket = self?.socket else { return }
            do {
                let payload = try await Task.detached(priority: .userInitiated) {
                    try JSONEncoder().encode(request)
                }.value
                try await socket.send(.string(String(decoding: payload, as: UTF8.self)))
            } catch {
                self?.handleFailure(error, socket: socket)
            }
        }
    }

    func answerQuestion(rpcId: String, sessionId: String, answers: [GatewayQuestionAnswer]) {
        let encodedAnswers: [[String: Any]] = answers.map { answer in
            var value: [String: Any] = [
                "id": answer.id,
                "selected": answer.selected
            ]
            if let custom = answer.custom { value["custom"] = custom }
            return value
        }
        send([
            "type": "question-answer",
            "rpcId": rpcId,
            "sessionId": sessionId,
            "answers": encodedAnswers
        ])
    }

    func cancelQuestion(rpcId: String, sessionId: String) {
        send([
            "type": "question-cancel",
            "rpcId": rpcId,
            "sessionId": sessionId
        ])
    }

    func respondToApproval(
        rpcId: String,
        sessionId: String,
        approvalId: String,
        outcome: GatewayApprovalOutcome
    ) {
        send([
            "type": "approval-response",
            "rpcId": rpcId,
            "sessionId": sessionId,
            "approvalId": approvalId,
            "outcome": outcome.rawValue
        ])
    }

    /// KMP 文件状态机已经生成并校验过的协议请求。平台 transport 只负责发送。
    func sendRequestPayload(_ payload: String) {
        if let type = try? JSONDecoder().decode(RequestEnvelope.self, from: Data(payload.utf8)).type,
           Self.usesConversationChannel(type), let conversationClient {
            conversationClient.sendRequestPayload(payload)
            return
        }
        guard let socket else {
            state = .failed(String(localized: "state.websocket.not-connected", defaultValue: "WebSocket 尚未连接"))
            return
        }
        if deferUntilConversationHello(payload) { return }
        write(payload, to: socket)
    }

    private func send(_ object: [String: Any]) {
        if let type = object["type"] as? String,
           Self.usesConversationChannel(type),
           let conversationClient {
            conversationClient.send(object)
            return
        }
        guard let socket else {
            state = .failed(String(localized: "state.websocket.not-connected", defaultValue: "WebSocket 尚未连接"))
            return
        }
        do {
            let data = try JSONSerialization.data(withJSONObject: object)
            let text = String(decoding: data, as: UTF8.self)
            if deferUntilConversationHello(text) { return }
            write(text, to: socket, logsPresets: object["type"] as? String == "agent-presets")
        } catch {
            state = .failed(error.localizedDescription)
        }
    }

    /// 每条通道按提交顺序发送；hello 前的缓冲不会被后来的写操作插队。
    private func write(_ payload: String, to socket: URLSessionWebSocketTask, logsPresets: Bool = false) {
        let previous = outboundTask
        outboundTask = Task { [weak self] in
            await previous?.value
            guard let self, !Task.isCancelled, self.socket === socket else { return }
            do {
                if logsPresets { Self.presetLogger.info("send started") }
                try await socket.send(.string(payload))
                if logsPresets { Self.presetLogger.info("send completed") }
            } catch { self.handleFailure(error, socket: socket) }
        }
    }

    private func deferUntilConversationHello(_ payload: String) -> Bool {
        guard channel == "conversation", !state.isConnected else { return false }
        let bytes = payload.utf8.count
        guard pendingConversationPayloads.count < 64,
              pendingConversationBytes + bytes <= 16 * 1024 * 1024 else {
            fail("会话通道尚未就绪，请稍后重试。", shouldReconnect: false)
            return true
        }
        pendingConversationPayloads.append(payload)
        pendingConversationBytes += bytes
        return true
    }

    nonisolated static func usesConversationChannel(_ requestType: String) -> Bool {
        ["message", "history", "subscribe", "unsubscribe"].contains(requestType)
    }

    private struct RequestEnvelope: Decodable {
        let type: String
    }

    private func receiveLoop(_ socket: URLSessionWebSocketTask) async {
        do {
            while !Task.isCancelled {
                let message = try await socket.receive()
                let data: Data
                switch message {
                case .string(let text): data = Data(text.utf8)
                case .data(let payload): data = payload
                @unknown default: continue
                }
                do {
                    // A history page can contain thousands of raw events. JSON
                    // decoding must not occupy the main actor that drives SwiftUI.
                    let frame = try await Task.detached(priority: .userInitiated) {
                        try GatewayWireDecoder.decode(data)
                    }.value
                    guard !Task.isCancelled, self.socket === socket else { return }
                    if frame.kind.hasPrefix("approval") {
                        gatewayApprovalTrace(
                            "transport received kind=\(frame.kind) hasRpc=\(frame.rpcId?.isEmpty == false) " +
                            "hasSession=\(frame.sessionId?.isEmpty == false) " +
                            "hasApprovalId=\(frame.approvalId?.isEmpty == false) " +
                            "hasTool=\(frame.toolName?.isEmpty == false) replay=\(frame.replay == true)"
                        )
                    }
                    if frame.kind == "hello", isManualPairingAttempt, pairingCode != nil {
                        fail("配对未返回长期凭据，请重新生成二维码。", shouldReconnect: false)
                        return
                    }
                    if frame.kind == "paired" || frame.kind == "hello" {
                        do {
                            try GatewayIdentity.validate(expected: expectedGatewayID, received: frame.gatewayId)
                            // 身份回调必须先校验冲突，再写入凭证或分发业务帧。
                            try onIdentity?(frame, endpoint?.absoluteString ?? "")
                            if let id = frame.gatewayId { expectedGatewayID = id.lowercased() }
                        } catch {
                            fail(error.localizedDescription, shouldReconnect: false)
                            return
                        }
                    } else if !state.isConnected {
                        continue
                    }
                    if frame.kind == "paired" {
                        guard let endpoint, let token = frame.token, !token.isEmpty else {
                            fail(String(localized: "pairing.response.missing-token", defaultValue: "配对响应缺少长期设备 token，未保存凭据"), shouldReconnect: false)
                            return
                        }
                        do {
                            try GatewayTokenStore.save(token, for: credentialURL(endpoint))
                            pairingCode = nil
                        } catch {
                            fail(String(localized: "pairing.token.keychain-failed", defaultValue: "配对成功，但无法将设备 token 写入 Keychain：\(error.localizedDescription)"), shouldReconnect: false)
                            return
                        }
                    } else if frame.kind == "hello" {
                        if channel == "control", !probeOnly, frame.capabilities?.contains("split-channels") == true {
                            openConversationChannel()
                        }
                        // `hello` is the protocol's authentication boundary.
                        // Debug mode may explicitly return authenticated=false.
                        connectionTimeoutTask?.cancel()
                        connectionTimeoutTask = nil
                        isManualPairingAttempt = false
                        reconnectAttempt = 0
                        state = .connected
                        lastReportedFailure = nil
                        isRecoveringFromBackground = false
                        serverPort = frame.port
                        clientCount = frame.clients
                        let pending = pendingConversationPayloads
                        pendingConversationPayloads.removeAll()
                        pendingConversationBytes = 0
                        for payload in pending { sendRequestPayload(payload) }
                    }
                    deliverApplicationFrame(frame, data: data)
                } catch {
                    // One future or malformed frame must not tear down an otherwise healthy socket.
                    onFrame?(GatewayFrame(kind: "error", code: "decode-failed", message: error.localizedDescription))
                }
            }
        } catch is CancellationError {
            return
        } catch {
            handleFailure(error, socket: socket)
        }
    }

    private func openConversationChannel() {
        guard conversationClient == nil, let endpoint else { return }
        let client = GatewayClient(channel: "conversation")
        client.credentialID = credentialID
        client.expectedGatewayID = expectedGatewayID
        client.trustedEndpoints = trustedEndpoints
        conversationClient = client
        client.onFrame = { [weak self, weak client] frame in
            guard let self, self.conversationClient === client,
                  frame.kind != "hello", frame.kind != "paired" else { return }
            self.onFrame?(frame)
        }
        client.onConnectionFailure = { [weak self, weak client] detail in
            guard let self, self.conversationClient === client else { return }
            self.fail(detail, shouldReconnect: false)
        }
        // 长期凭据已在控制连接 paired 帧中保存，绝不重复使用一次性配对码。
        client.connect(to: endpoint.absoluteString)
    }

    func deliverApplicationFrame(_ frame: GatewayFrame, data: Data) {
        if frame.kind == "agent-presets" || frame.requestType == "agent-presets" {
            Self.presetLogger.info("received decoded response kind=\(frame.kind, privacy: .public) bytes=\(data.count)")
        }
        switch frame.kind {
        case "commands", "command-options", "command-selected":
            onCommandFrame?(String(decoding: data, as: UTF8.self))
        case "error" where ["commands", "command-options", "command-select"].contains(frame.requestType):
            onCommandFrame?(String(decoding: data, as: UTF8.self))
        default:
            break
        }
        if !acceptSessionCreationFrame(frame) { onFrame?(frame) }
    }

    private func handleFailure(_ error: Error, socket: URLSessionWebSocketTask? = nil) {
        guard wantsConnection else { return }
        let nsError = error as NSError
        // `beginConnection` deliberately cancels the previous task before it
        // installs the replacement. Its receive loop can finish one actor turn
        // later, after `wantsConnection` has become true again. Never let that
        // stale cancellation overwrite the new connection or surface as a
        // user-facing "连接失败" alert.
        if let socket, socket !== self.socket { return }
        if nsError.domain == NSURLErrorDomain, nsError.code == NSURLErrorCancelled { return }
        let statusCode = Self.httpResponse(from: socket, error: nsError)?.statusCode
        let closeCode = socket?.closeCode.rawValue
        let closeReason = socket?.closeReason.flatMap { String(data: $0, encoding: .utf8) }
        let detail: String
        let shouldReconnect: Bool
        let shouldReportFailure: Bool
        switch (statusCode, closeCode) {
        case (401, _):
            if pairingCode == nil, let endpoint { GatewayTokenStore.delete(for: credentialURL(endpoint)) }
            detail = String(localized: "auth.failed.401", defaultValue: "鉴权失败（HTTP 401）：设备 token 无效、已被吊销，或配对码已过期/使用过，请重新扫码配对。")
            shouldReconnect = false
            shouldReportFailure = true
        case (503, _):
            detail = String(localized: "gateway.503.disabled", defaultValue: "移动网关暂未开启（HTTP 503）。已保留设备凭据，开启网关后会自动重连。")
            shouldReconnect = true
            shouldReportFailure = !(isApplicationInBackground || isRecoveringFromBackground)
        case (_, 4003):
            detail = String(localized: "auth.reenabled.4003", defaultValue: "服务端已重新开启设备鉴权（WebSocket 4003），请使用已保存的设备凭据重连或重新扫码。")
            shouldReconnect = false
            shouldReportFailure = true
        case (_, 4004):
            detail = String(localized: "gateway.closed.4004", defaultValue: "移动网关已关闭（WebSocket 4004）。已保留设备凭据，重新开启后会自动重连。")
            shouldReconnect = true
            shouldReportFailure = !(isApplicationInBackground || isRecoveringFromBackground)
        default:
            let reasonSuffix = closeReason.flatMap { $0.isEmpty ? nil : String(localized: "close.server-reason.suffix", defaultValue: "；服务端原因：\($0)") } ?? ""
            detail = String(localized: "websocket.connect-failed.detail", defaultValue: "WebSocket 连接失败：\(nsError.localizedDescription)（\(nsError.domain) \(nsError.code)）\(reasonSuffix)")
            shouldReconnect = true
            shouldReportFailure = !(isApplicationInBackground || isRecoveringFromBackground)
        }
        fail(
            detail,
            shouldReconnect: shouldReconnect && !isManualPairingAttempt,
            reportFailure: shouldReportFailure
        )
    }

    private func fail(_ detail: String, shouldReconnect: Bool, reportFailure: Bool = true) {
        outboundTask?.cancel()
        outboundTask = nil
        pendingConversationPayloads.removeAll()
        pendingConversationBytes = 0
        conversationClient?.disconnect()
        conversationClient = nil
        failSessionCreations()
        connectionTimeoutTask?.cancel()
        connectionTimeoutTask = nil
        state = .failed(detail)
        socket?.cancel(with: .goingAway, reason: nil)
        socket = nil
        receiveTask?.cancel()
        receiveTask = nil
        if !shouldReconnect { wantsConnection = false }
        if reportFailure, lastReportedFailure != detail {
            lastReportedFailure = detail
            onConnectionFailure?(detail)
        }
        reconnectTask?.cancel()
        guard shouldReconnect, channel == "control" else { return }
        guard !probeOnly else { wantsConnection = false; return }
        reconnectAttempt += 1
        let delay = min(30, 2 * (1 << min(reconnectAttempt - 1, 4)))
        reconnectTask = Task { [weak self] in
            do { try await Task.sleep(for: .seconds(delay)) } catch { return }
            guard let self, !Task.isCancelled, self.wantsConnection, var endpoint = self.endpoint else { return }
            if self.trustedEndpoints.count > 1,
               let index = self.trustedEndpoints.firstIndex(of: endpoint.absoluteString),
               let next = URL(string: self.trustedEndpoints[(index + 1) % self.trustedEndpoints.count]) {
                endpoint = next
            }
            self.beginConnection(
                to: endpoint.absoluteString,
                pairingCode: self.pairingCode,
                resetReportedFailure: false
            )
        }
    }

    private func startConnectionTimeout(for socket: URLSessionWebSocketTask) {
        connectionTimeoutTask?.cancel()
        connectionTimeoutTask = Task { [weak self, weak socket] in
            try? await Task.sleep(for: .seconds(15))
            guard let self,
                  let socket,
                  self.socket === socket,
                  self.wantsConnection,
                  !self.state.isConnected else { return }
            socket.cancel(with: .goingAway, reason: nil)
            self.fail(
                String(localized: "connection.timeout", defaultValue: "连接超时，请检查网络或配对信息后重试。"),
                shouldReconnect: !self.isManualPairingAttempt && !self.probeOnly
            )
        }
    }

    private func suspendTransportForBackground() {
        conversationClient?.disconnect()
        conversationClient = nil
        failSessionCreations()
        wantsConnection = true
        reconnectTask?.cancel()
        reconnectTask = nil
        connectionTimeoutTask?.cancel()
        connectionTimeoutTask = nil
        receiveTask?.cancel()
        receiveTask = nil
        socket?.cancel(with: .goingAway, reason: nil)
        socket = nil
        state = .disconnected
    }

    private static func httpResponse(from socket: URLSessionWebSocketTask?, error: NSError) -> HTTPURLResponse? {
        if let response = socket?.response as? HTTPURLResponse { return response }
        for key in ["NSErrorFailingURLResponseKey", "NSURLErrorFailingURLResponseErrorKey"] {
            if let response = error.userInfo[key] as? HTTPURLResponse { return response }
        }
        if let underlying = error.userInfo[NSUnderlyingErrorKey] as? NSError {
            return httpResponse(from: socket, error: underlying)
        }
        return nil
    }
}

private struct GatewayMessageRequest: Encodable, Sendable {
    struct Image: Encodable, Sendable {
        var mediaType: String
        // JSONEncoder serializes Data as standard Base64 without a Data URL
        // prefix, exactly matching protocol 3.
        var data: Data
        var name: String?
    }

    let type = "message"
    var sessionId: String?
    var text: String
    var images: [Image]
    var workspaceId: String?
    var clientTimeZone: String
}

private struct GatewayCommandExecuteRequest: Encodable, Sendable {
    let type = "command-execute"
    var sessionId: String
    var line: String
    var images: [GatewayMessageRequest.Image]
}

private enum GatewayDeviceIdentityStore {
    private static let service = "ai.dsh.mobile.ios.device-identity"
    private static let account = "installation"

    static func loadOrCreate() throws -> String {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecSuccess,
           let data = result as? Data,
           let stored = String(data: data, encoding: .utf8),
           !stored.isEmpty {
            return stored
        }
        guard status == errSecItemNotFound else { throw KeychainError(status: status) }

        let value = UUID().uuidString.lowercased()
        var insertion = query
        insertion.removeValue(forKey: kSecReturnData as String)
        insertion.removeValue(forKey: kSecMatchLimit as String)
        insertion[kSecValueData as String] = Data(value.utf8)
        insertion[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let addStatus = SecItemAdd(insertion as CFDictionary, nil)
        if addStatus == errSecDuplicateItem { return try loadOrCreate() }
        guard addStatus == errSecSuccess else { throw KeychainError(status: addStatus) }
        return value
    }

    private struct KeychainError: LocalizedError {
        let status: OSStatus
        var errorDescription: String? {
            (SecCopyErrorMessageString(status, nil) as String?) ?? String(localized: "keychain.error.status", defaultValue: "Keychain 错误 \(status)")
        }
    }
}

private enum GatewayTokenStore {
    private static let service = "ai.dsh.mobile.ios.gateway-token"

    static func load(for endpoint: URL) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account(for: endpoint),
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func save(_ token: String, for endpoint: URL) throws {
        let account = account(for: endpoint)
        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        let attributes: [String: Any] = [
            kSecValueData as String: Data(token.utf8),
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]
        let updateStatus = SecItemUpdate(base as CFDictionary, attributes as CFDictionary)
        if updateStatus == errSecItemNotFound {
            var insertion = base
            attributes.forEach { insertion[$0.key] = $0.value }
            let status = SecItemAdd(insertion as CFDictionary, nil)
            guard status == errSecSuccess else { throw KeychainError(status: status) }
        } else if updateStatus != errSecSuccess {
            throw KeychainError(status: updateStatus)
        }
    }

    static func delete(for endpoint: URL) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account(for: endpoint)
        ]
        SecItemDelete(query as CFDictionary)
    }

    private static func account(for endpoint: URL) -> String {
        endpoint.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
    }

    private struct KeychainError: LocalizedError {
        let status: OSStatus
        var errorDescription: String? {
            (SecCopyErrorMessageString(status, nil) as String?) ?? String(localized: "keychain.error.status", defaultValue: "Keychain 错误 \(status)")
        }
    }
}

/// 拒绝 WebSocket 握手重定向，避免向其他来源转发主机凭据。
private final class GatewayRedirectBlocker: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}
