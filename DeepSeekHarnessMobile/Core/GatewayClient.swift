import Foundation
import UIKit
import Security
import OSLog
import Network

@MainActor
final class GatewayClient: ObservableObject {
    private static let presetLogger = Logger(subsystem: "ai.dsh.mobile.ios", category: "agent-presets")
    private static let lifecycleLogger = Logger(subsystem: "ai.dsh.mobile.ios", category: "gateway-lifecycle")
    private var backgroundEnteredAt: Date?

    // 仅记录传输元数据，禁止写入地址、凭据、消息正文和服务端原始关闭原因。
    private func traceLifecycle(_ event: String) {
        let elapsed = backgroundEnteredAt.map { Date().timeIntervalSince($0) } ?? -1
        Self.lifecycleLogger.notice("[GatewayLifecycle] channel=\(self.channel, privacy: .public) event=\(event, privacy: .public) background=\(self.isApplicationInBackground) backgroundSeconds=\(elapsed) connected=\(self.state.isConnected) wantsConnection=\(self.wantsConnection) taskId=\(self.socket?.taskIdentifier ?? -1)")
    }

    private let channel: String
    var requestDecorator: ((String) -> String?)?
    var conversationTarget: ((String) -> MobileConversationTarget?)?
    private var subscriptionPending = false
    private var pendingSubscriptionID: String?
    private var desiredSubscriptionID: String?
    private var subscriptionDirty = false
    private var subscriptionTimeout: Task<Void, Never>?

    func resetSubscriptionRequests() {
        subscriptionTimeout?.cancel()
        subscriptionTimeout = nil
        subscriptionPending = false
        subscriptionDirty = false
    }

    /// subscribed 无 requestId，必须单飞并合并快速切换意图。
    func acceptSubscriptionAck(sessionID: String?) -> Bool {
        // 兼容未经过前台选择入口的历史订阅与旧网关；托管切换仍严格单飞。
        guard subscriptionPending else { return true }
        guard pendingSubscriptionID == sessionID else { return false }
        subscriptionTimeout?.cancel()
        subscriptionTimeout = nil
        subscriptionPending = false
        if subscriptionDirty {
            subscriptionDirty = false
            subscribe(sessionId: desiredSubscriptionID)
            return false
        }
        return true
    }

    var credentialID: String?
    var expectedGatewayID: String?
    var trustedEndpoints: [String] = []
    var onIdentity: ((GatewayFrame, String) throws -> Void)?
    var probeOnly = false
    private var reconnectAttempt = 0
    /// 本轮网络环境下**已超时失败**的局域网地址（精确到具体 endpoint）。
    /// 只记录超时，不记录鉴权/DNS 等其他失败——避免一个地址的问题波及全部局域网地址。
    private var timedOutLocalEndpoints: Set<String> = []
    private var networkPathMonitor: NWPathMonitor?
    private lazy var networkRecovery = GatewayNetworkRecovery(
        canRecover: { [weak self] in
            guard let self else { return false }
            return self.wantsConnection && self.channel == "control" && !self.probeOnly &&
                !self.isManualPairingAttempt && !self.isApplicationInBackground
        },
        recover: { [weak self] in self?.recoverNetworkConnection() }
    )
    private var pendingConversationPayloads: [String] = []
    private var pendingConversationBytes = 0
    private var outboundTask: Task<Void, Never>?
    private let transportSession = URLSession(configuration: .ephemeral, delegate: GatewayRedirectBlocker(), delegateQueue: nil)

    static func forgetCredential(for profileID: String) {
        let url = URL(string: "https://gateway-credential.invalid/\(profileID)")!
        Task { await SystemGatewayCredentialStore.shared.deleteToken(for: url) }
    }

    private func credentialURL(_ endpoint: URL) -> URL {
        credentialID.map { URL(string: "https://gateway-credential.invalid/\($0)")! } ?? endpoint
    }

    func migrateCredential(from endpoint: URL) async throws {
        guard credentialID != nil, try await credentials.loadToken(for: credentialURL(endpoint)) == nil,
              let token = try await credentials.loadToken(for: endpoint) else { return }
        try await credentials.saveToken(token, for: credentialURL(endpoint))
    }

    private var conversationClient: GatewayClient?
    private var onTransportFailure: ((String, Bool) -> Void)?
    private let credentials: any GatewayCredentialStoring
    private let heartbeatInterval: Duration
    private let probeTimeout: Duration

    init(
        channel: String = "control",
        credentials: (any GatewayCredentialStoring)? = nil,
        heartbeatInterval: Duration = .seconds(30),
        probeTimeout: Duration = .seconds(5)
    ) {
        self.channel = channel
        self.credentials = credentials ?? SystemGatewayCredentialStore.shared
        self.heartbeatInterval = heartbeatInterval
        self.probeTimeout = probeTimeout
    }
#if DEBUG
    private var testRequestTransport: ((String) -> Void)?
    var recoveryStateForTesting: (wantsConnection: Bool, reconnectAttempt: Int, isManualPairing: Bool) {
        (wantsConnection, reconnectAttempt, isManualPairingAttempt)
    }

    /// 测试使用明确的已连接传输，避免仅投递 hello 却仍处于断线状态的伪在线 fixture。
    func installConnectedTransportForTesting(_ send: @escaping (String) -> Void = { _ in }) {
        testRequestTransport = send
        state = .connected
    }
#endif

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
    var onSpeechFrame: ((String) -> Void)?
    var onConnectionFailure: ((String) -> Void)?
    /// 传输生命周期与错误弹窗分离；静默恢复也必须废弃旧请求。
    var onConnectionInvalidated: (() -> Void)?
    var onConnectionCheckStarted: (() -> Void)?
    var onConnectionReady: (() -> Void)?
    private var socket: URLSessionWebSocketTask?
    private var receiveTask: Task<Void, Never>?
    private var connectionPreparationTask: Task<Void, Never>?
    private var connectionGeneration = UUID()
    private var reconnectTask: Task<Void, Never>?
    private var connectionTimeoutTask: Task<Void, Never>?
    private var heartbeatTask: Task<Void, Never>?
    private var foregroundProbeTask: Task<Void, Never>?
    private var foregroundProbeSequence = 0
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
        networkPathMonitor?.cancel()
        outboundTask?.cancel()
        receiveTask?.cancel()
        connectionPreparationTask?.cancel()
        reconnectTask?.cancel()
        connectionTimeoutTask?.cancel()
        foregroundProbeTask?.cancel()
        heartbeatTask?.cancel()
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
    func hasStoredCredential(for rawEndpoint: String) async -> Bool {
        guard let url = URL(string: rawEndpoint),
              ["ws", "wss"].contains(url.scheme?.lowercased() ?? "") else {
            return false
        }
        return (try? await credentials.loadToken(for: credentialURL(url)))?.isEmpty == false
    }

    func connectForPairing(_ payload: GatewayPairingPayload) {
        isRecoveringFromBackground = false
        isManualPairingAttempt = true
        beginConnection(to: payload.publicUrl, pairingCode: payload.pairingCode, resetReportedFailure: true)
    }

    /// 只记录场景状态。连接的生命周期由用户的连接意图与真实传输故障决定；
    /// 进入后台本身不再主动关闭 WebSocket。
    func applicationDidEnterBackground() {
        networkRecovery.cancel()
        foregroundProbeTask?.cancel()
        foregroundProbeTask = nil
        foregroundProbeSequence += 1
        backgroundEnteredAt = Date()
        isApplicationInBackground = true
        traceLifecycle("enter-background")
        isRecoveringFromBackground = true
        conversationClient?.applicationDidEnterBackground()
    }

    /// Restores the transport immediately instead of waiting for the delayed
    /// reconnect loop. A successful `hello` clears recovery mode.
    func applicationDidBecomeActive() {
        traceLifecycle("become-active")
        backgroundEnteredAt = nil
        isApplicationInBackground = false
        guard wantsConnection, !isManualPairingAttempt, !probeOnly else { return }
        reconnectAttempt = 0
        if state.isConnected {
            conversationClient?.applicationDidBecomeActive()
            probeForegroundConnection()
            return
        }
        guard let endpoint else { return }
        reconnectTask?.cancel()
        reconnectTask = nil
        beginConnection(
            to: endpoint.absoluteString,
            pairingCode: pairingCode,
            resetReportedFailure: false
        )
    }

    private func beginConnection(to rawEndpoint: String, pairingCode: String?, resetReportedFailure: Bool) {
        traceLifecycle("begin-connection")
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
        startMonitoringNetworkChanges()
        if resetReportedFailure { lastReportedFailure = nil }
        state = .connecting
        var request = URLRequest(url: url)
        request.setValue(channel, forHTTPHeaderField: "X-DSH-Channel")
        request.setValue(Data(UIDevice.current.name.utf8).base64EncodedString(), forHTTPHeaderField: "X-DSH-Device-Name")
        let generation = connectionGeneration
        let credentialEndpoint = credentialURL(url)
        connectionPreparationTask = Task { [weak self, credentials] in
            let deviceID: String
            do {
                deviceID = try await credentials.loadOrCreateDeviceID()
            } catch {
                guard let self, !Task.isCancelled, self.connectionGeneration == generation else { return }
                self.fail(String(localized: "gateway.device-id.unavailable", defaultValue: "无法读取或创建设备唯一标识：\(error.localizedDescription)"),
                          shouldReconnect: self.canRetryCredentialAccess(error))
                return
            }
            guard !Task.isCancelled else { return }
            let token: String?
            do {
                token = pairingCode == nil ? try await credentials.loadToken(for: credentialEndpoint) : nil
            } catch {
                // 临时读取失败不是 token 缺失，不能发起匿名握手或删除有效凭据。
                guard let self, !Task.isCancelled, self.connectionGeneration == generation else { return }
                self.fail(error.localizedDescription, shouldReconnect: self.canRetryCredentialAccess(error))
                return
            }
            guard let self, !Task.isCancelled, self.connectionGeneration == generation,
                  self.wantsConnection else { return }
            self.connectionPreparationTask = nil
            request.setValue(deviceID, forHTTPHeaderField: "X-DSH-Device-ID")
            if let pairingCode {
                request.setValue("dsh-mobile-v1, dsh-pair.\(pairingCode)", forHTTPHeaderField: "Sec-WebSocket-Protocol")
            } else {
                if let token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
                request.setValue("dsh-mobile-v1", forHTTPHeaderField: "Sec-WebSocket-Protocol")
            }
            let socket = self.transportSession.webSocketTask(with: request)
            socket.maximumMessageSize = Self.maximumIncomingMessageSize
            self.socket = socket
            socket.resume()
            self.startConnectionTimeout(for: socket)
            self.receiveTask = Task { [weak self] in await self?.receiveLoop(socket) }
        }
    }

    func disconnect(reconnect: Bool = false) {
        connectionGeneration = UUID()
        connectionPreparationTask?.cancel()
        connectionPreparationTask = nil
        onConnectionInvalidated?()
        networkRecovery.cancel()
        resetSubscriptionRequests()
        traceLifecycle("explicit-disconnect reconnect=\(reconnect)")
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
        heartbeatTask?.cancel()
        heartbeatTask = nil
        foregroundProbeTask?.cancel()
        foregroundProbeTask = nil
        foregroundProbeSequence += 1
        receiveTask?.cancel()
        receiveTask = nil
        socket?.cancel(with: .normalClosure, reason: nil)
        socket = nil
        pairingCode = nil
        isManualPairingAttempt = false
        serverPort = nil
        clientCount = nil
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
    func requestScheduleCatalog() {
        send(["type": "schedule-catalog"])
    }
    func requestScheduleList(sessionId: String) {
        send(["type": "schedule-list", "sessionId": sessionId])
    }
    func requestScheduleHistory(sessionId: String, id: String, limit: Int, before: String? = nil) {
        var payload: [String: Any] = ["type": "schedule-history", "sessionId": sessionId, "id": id, "limit": limit]
        if let before { payload["before"] = before }
        send(payload)
    }
    func updateSchedule(
        sessionId: String, id: String, expected: JSONValue,
        title: String? = nil, prompt: String? = nil, change: JSONValue? = nil,
        requestId: String? = nil
    ) {
        guard let data = try? JSONEncoder().encode(expected),
              let expectedObject = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return }
        var payload: [String: Any] = ["type": "schedule-update", "sessionId": sessionId, "id": id, "expected": expectedObject]
        if let title { payload["title"] = title }
        if let prompt { payload["prompt"] = prompt }
        if let change {
            guard let data = try? JSONEncoder().encode(change),
                  let changeObject = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return }
            payload["change"] = changeObject
        }
        if let requestId { payload["requestId"] = requestId }
        send(payload)
    }
    func deleteSchedule(sessionId: String, id: String, requestId: String? = nil) {
        var payload: [String: Any] = ["type": "schedule-delete", "sessionId": sessionId, "id": id]
        if let requestId { payload["requestId"] = requestId }
        send(payload)
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
        view: String? = nil,
        historyFormatVersion: Int? = nil
    ) {
        var payload: [String: Any] = ["type": "history", "sessionId": sessionId, "maxMessages": maxMessages]
        if let beforeSeq, let historyFormatVersion {
            guard beforeSeq >= 0 else { return }
            payload["beforeSeq"] = beforeSeq
            payload["historyFormatVersion"] = historyFormatVersion
        }
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
        desiredSubscriptionID = sessionId
        if subscriptionPending { subscriptionDirty = true; return }
        subscriptionPending = true
        pendingSubscriptionID = sessionId
        subscriptionTimeout?.cancel()
        subscriptionTimeout = Task { [weak self] in
            do { try await Task.sleep(for: .seconds(15)) } catch { return }
            guard let self, self.subscriptionPending else { return }
            let target = self.pendingSubscriptionID
            self.resetSubscriptionRequests()
            self.onFrame?(GatewayFrame(kind: "error", sessionId: target, code: "subscription-timeout",
                message: "成员会话订阅超时，请重试或返回 Lead", requestType: "subscribe"))
        }
        gatewayApprovalTrace("transport subscribe hasSession=\(sessionId?.isEmpty == false)")
        if let sessionId, !sessionId.isEmpty {
            send(["type": "subscribe", "sessionId": sessionId, "assistantStream": true])
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
        receipts: [String] = [],
        sessionId: String?,
        workspaceId: String? = nil,
        mode: String = "queue",
        requestId: String? = nil
    ) {
        let address = sessionId.flatMap { conversationTarget?($0) }
        let request = GatewayMessageRequest(
            sessionId: sessionId?.isEmpty == false ? sessionId : nil,
            parentSessionId: address?.parentSessionId,
            subagentMode: address?.subagentMode,
            text: text,
            images: images.map {
                GatewayMessageRequest.Image(
                    mediaType: $0.mediaType,
                    data: $0.data,
                    name: $0.name
                )
            },
            files: receipts.isEmpty ? nil : receipts.map { GatewayMessageRequest.File(receiptId: $0) },
            workspaceId: sessionId == nil && workspaceId?.isEmpty == false ? workspaceId : nil,
            clientTimeZone: TimeZone.current.identifier,
            mode: mode,
            requestId: requestId
        )
        Task { [weak self] in
            do {
                let payload = try await Task.detached(priority: .userInitiated) {
                    try JSONEncoder().encode(request)
                }.value
                if let self {
                    // 图片 Base64 已在后台编码；直接使用固定地址，不在主线程重新解析整条消息。
                    let client = self.conversationClient ?? self
                    client.sendRequestPayload(String(decoding: payload, as: UTF8.self), decorate: false)
                }
            } catch {
                await self?.handleFailure(error)
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
                await self?.handleFailure(error, socket: socket)
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
    func sendRequestPayload(_ originalPayload: String, decorate: Bool = true) {
        let payload: String
        if decorate, let requestDecorator {
            guard let decorated = requestDecorator(originalPayload) else {
                // 文件预览不能静默丢弃，否则状态机会一直停在 0%。
                if let object = try? JSONSerialization.jsonObject(with: Data(originalPayload.utf8)) as? [String: Any],
                   let type = object["type"] as? String, type.hasPrefix("file-") {
                    onFrame?(GatewayFrame(kind: "error", sessionId: object["sessionId"] as? String,
                        code: "file-workspace-unavailable", message: "无法确定文件所属工作区，请重新打开会话后重试。",
                        requestType: type, requestId: object["requestId"] as? String,
                        transferId: object["transferId"] as? String))
                }
                return
            }
            payload = decorated
        } else { payload = originalPayload }

#if DEBUG
        if let testRequestTransport {
            if state.isConnected { testRequestTransport(payload) }
            return
        }
#endif
        if let conversationClient,
           let type = try? JSONDecoder().decode(RequestEnvelope.self, from: Data(payload.utf8)).type,
           Self.usesConversationChannel(type) {
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
        guard let data = try? JSONSerialization.data(withJSONObject: object) else { return }
        sendRequestPayload(String(decoding: data, as: UTF8.self))
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
            } catch { await self.handleFailure(error, socket: socket) }
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

    // 解复用只负责传输格式；ASR 协议字段与状态由公共层处理。
    nonisolated static func decodeIncomingFrame(_ data: Data) throws -> (GatewayFrame?, String?) {
        struct SpeechEnvelope: Decodable { let kind: String?; let requestType: String? }
        let header = try? JSONDecoder().decode(SpeechEnvelope.self, from: data)
        if header?.kind?.hasPrefix("speech-") == true ||
            (header?.kind == "error" && header?.requestType?.hasPrefix("speech-") == true) {
            return (nil, String(decoding: data, as: UTF8.self))
        }
        return (try GatewayWireDecoder.decode(data), nil)
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
                    // 语音帧使用独立公共解码器，所有 JSON 扫描仍在后台完成。
                    let decoded = try await Task.detached(priority: .userInitiated) {
                        try Self.decodeIncomingFrame(data)
                    }.value
                    guard !Task.isCancelled, self.socket === socket else { return }
                    if let speech = decoded.1 {
                        if state.isConnected { onSpeechFrame?(speech) }
                        continue
                    }
                    guard let frame = decoded.0 else { continue }
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
                            try await credentials.saveToken(token, for: credentialURL(endpoint))
                            guard !Task.isCancelled, self.socket === socket else { return }
                            pairingCode = nil
                            // 一次性配对已完成；hello 前断线也应使用长期 token 自动恢复。
                            isManualPairingAttempt = false
                            startMonitoringNetworkChanges()
                        } catch {
                            guard !Task.isCancelled, self.socket === socket else { return }
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
                        startMonitoringNetworkChanges()
                        reconnectAttempt = 0
                        timedOutLocalEndpoints.removeAll()
                        state = .connected
                        onConnectionReady?()
                        traceLifecycle("hello-connected")
                        startHeartbeat(for: socket)
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
                    onFrame?(GatewayFrame(kind: "error", code: "decode-failed", message: GatewayWireDecoder.failureDescription(error)))
                }
            }
        } catch is CancellationError {
            return
        } catch {
            await handleFailure(error, socket: socket)
        }
    }

    private func openConversationChannel() {
        guard conversationClient == nil, let endpoint else { return }
        let client = GatewayClient(channel: "conversation", credentials: credentials,
                                   heartbeatInterval: heartbeatInterval, probeTimeout: probeTimeout)
        client.credentialID = credentialID
        client.expectedGatewayID = expectedGatewayID
        client.trustedEndpoints = trustedEndpoints
        conversationClient = client
        client.onFrame = { [weak self, weak client] frame in
            guard let self, self.conversationClient === client,
                  frame.kind != "hello", frame.kind != "paired" else { return }
            self.onFrame?(frame)
        }
        client.onTransportFailure = { [weak self, weak client] detail, shouldReconnect in
            guard let self, let client, self.conversationClient === client else { return }
            // A transient failure on the split conversation socket must restore
            // both channels. Keep non-recoverable authentication failures terminal.
            self.fail(
                detail,
                shouldReconnect: shouldReconnect,
                reportFailure: !(self.isApplicationInBackground || self.isRecoveringFromBackground)
            )
        }
        // 长期凭据已在控制连接 paired 帧中保存，绝不重复使用一次性配对码。
        if isApplicationInBackground { client.applicationDidEnterBackground() }
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

    private func handleFailure(_ error: Error, socket: URLSessionWebSocketTask? = nil) async {
        guard wantsConnection else { return }
        let nsError = error as NSError
        let isCurrentSocket = socket == nil || socket === self.socket
        traceLifecycle("transport-failure domain=\(nsError.domain) code=\(nsError.code) closeCode=\(socket?.closeCode.rawValue ?? -1) httpStatus=\(Self.httpResponse(from: socket, error: nsError)?.statusCode ?? -1) currentSocket=\(isCurrentSocket) hasCloseReason=\(socket?.closeReason != nil)")
        // `beginConnection` deliberately cancels the previous task before it
        // installs the replacement. Its receive loop can finish one actor turn
        // later, after `wantsConnection` has become true again. Never let that
        // stale cancellation overwrite the new connection or surface as a
        // user-facing "连接失败" alert.
        if let socket, socket !== self.socket { return }
        // 旧 socket 已由上面的身份检查排除；当前 socket 的系统取消也属于真实断线。
        let statusCode = Self.httpResponse(from: socket, error: nsError)?.statusCode
        let closeCode = socket?.closeCode.rawValue
        let closeReason = socket?.closeReason.flatMap { String(data: $0, encoding: .utf8) }
        let detail: String
        let shouldReconnect: Bool
        let shouldReportFailure: Bool
        switch (statusCode, closeCode) {
        case (401, _):
            if pairingCode == nil, let endpoint,
               socket?.originalRequest?.value(forHTTPHeaderField: "Authorization") != nil {
                let generation = connectionGeneration
                await credentials.deleteToken(for: credentialURL(endpoint))
                guard generation == connectionGeneration, socket === self.socket else { return }
            }
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
            let connectionFailure = String(
                localized: "websocket.connect-failed.detail",
                defaultValue: "WebSocket 连接失败：\(nsError.localizedDescription)（\(nsError.domain) \(nsError.code)）\(reasonSuffix)"
            )
            detail = connectionFailure + (Self.certificateTrustAdvice(for: nsError).map { "\n\n\($0)" } ?? "")
            shouldReconnect = true
            shouldReportFailure = !(isApplicationInBackground || isRecoveringFromBackground)
        }
        fail(
            detail,
            shouldReconnect: shouldReconnect && !isManualPairingAttempt,
            reportFailure: shouldReportFailure
        )
    }

    private func canRetryCredentialAccess(_ error: Error) -> Bool {
        (error as? GatewayKeychainError)?.isTemporarilyUnavailable == true &&
            !isManualPairingAttempt && !probeOnly
    }

    static func certificateTrustAdvice(for error: NSError) -> String? {
        guard error.domain == NSURLErrorDomain else { return nil }
        switch error.code {
        case NSURLErrorServerCertificateHasBadDate,
             NSURLErrorServerCertificateUntrusted,
             NSURLErrorServerCertificateHasUnknownRoot,
             NSURLErrorServerCertificateNotYetValid:
            return String(
                localized: "gateway.certificate-trust.advice",
                defaultValue: "如果确认这是安全的个人服务器，请在网关中更新公网配置，并确保其公网证书有效后重试。"
            )
        default:
            return nil
        }
    }

    private func fail(_ detail: String, shouldReconnect: Bool, reportFailure: Bool = true) {
        connectionGeneration = UUID()
        connectionPreparationTask?.cancel()
        connectionPreparationTask = nil
        traceLifecycle("failure-cleanup reconnect=\(shouldReconnect) report=\(reportFailure)")
        onConnectionInvalidated?()
        outboundTask?.cancel()
        outboundTask = nil
        heartbeatTask?.cancel()
        heartbeatTask = nil
        foregroundProbeTask?.cancel()
        foregroundProbeTask = nil
        foregroundProbeSequence += 1
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
        onTransportFailure?(detail, shouldReconnect)
        if reportFailure, lastReportedFailure != detail {
            lastReportedFailure = detail
            onConnectionFailure?(detail)
        }
        reconnectTask?.cancel()
        guard shouldReconnect, channel == "control" else { return }
        guard !probeOnly else { wantsConnection = false; return }
        reconnectAttempt += 1
        let delay = min(30, 2 * (1 << min(reconnectAttempt - 1, 4)))
        traceLifecycle("schedule-reconnect attempt=\(reconnectAttempt) delaySeconds=\(delay)")
        reconnectTask = Task { [weak self] in
            do { try await Task.sleep(for: .seconds(delay)) } catch { return }
            guard let self, !Task.isCancelled, self.wantsConnection, var endpoint = self.endpoint else { return }
            if self.trustedEndpoints.count > 1,
               let index = self.trustedEndpoints.firstIndex(of: endpoint.absoluteString) {
                // 跳过本轮已超时的局域网地址（精确到地址，不是整个局域网）。
                let count = self.trustedEndpoints.count
                var candidateIndex = (index + 1) % count
                for _ in 0..<count {
                    if let candidate = URL(string: self.trustedEndpoints[candidateIndex]),
                       !self.timedOutLocalEndpoints.contains(candidate.absoluteString) {
                        endpoint = candidate
                        break
                    }
                    candidateIndex = (candidateIndex + 1) % count
                }
            }
            self.beginConnection(
                to: endpoint.absoluteString,
                pairingCode: self.pairingCode,
                resetReportedFailure: false
            )
        }
    }

    private func startMonitoringNetworkChanges() {
        // 对话通道由控制通道统一恢复；临时探测和一次性配对不订阅网络变化。
        guard channel == "control", !probeOnly, !isManualPairingAttempt,
              networkPathMonitor == nil else { return }
        let monitor = NWPathMonitor()
        networkPathMonitor = monitor
        monitor.pathUpdateHandler = { [weak self] path in
            let snapshot = GatewayNetworkRecovery.Path(path)
            Task { @MainActor [weak self] in self?.networkRecovery.update(snapshot) }
        }
        monitor.start(queue: DispatchQueue(label: "ai.dsh.mobile.gateway-network"))
    }

    private func recoverNetworkConnection() {
        traceLifecycle("network-path-recovery")
        reconnectAttempt = 0
        // 网络环境可能已变化（如从家到公司），之前超时的地址可能已恢复。
        timedOutLocalEndpoints.removeAll()
        if state.isConnected {
            // 两条 socket 都可能停留在旧网络，即使控制通道的 ping 已成功。
            probeForegroundConnection()
            conversationClient?.probeForegroundConnection()
        } else if let endpoint {
            reconnectTask?.cancel()
            reconnectTask = nil
            beginConnection(to: endpoint.absoluteString, pairingCode: nil, resetReportedFailure: false)
        }
    }

    /// URLSession 可能尚未报告后台或网络切换断线；以 ping 验证连接，超时则自动恢复。
    private func probeForegroundConnection() {
        guard let socket, state.isConnected, foregroundProbeTask == nil else { return }
        onConnectionCheckStarted?()
        foregroundProbeSequence += 1
        let sequence = foregroundProbeSequence
        traceLifecycle("foreground-probe-ping")
        foregroundProbeTask = Task { [weak self, weak socket] in
            do { try await Task.sleep(for: self?.probeTimeout ?? .seconds(5)) } catch { return }
            guard let self, let socket, self.socket === socket,
                  !self.isApplicationInBackground, self.foregroundProbeSequence == sequence else { return }
            self.traceLifecycle("foreground-probe-timeout")
            await self.handleFailure(URLError(.timedOut), socket: socket)
        }
        socket.sendPing { [weak self, weak socket] error in
            Task { @MainActor [weak self, weak socket] in
                guard let self, let socket, self.socket === socket,
                      self.foregroundProbeSequence == sequence else { return }
                self.foregroundProbeTask?.cancel()
                self.foregroundProbeTask = nil
                if let error {
                    await self.handleFailure(error, socket: socket)
                } else {
                    self.traceLifecycle("foreground-probe-pong")
                    self.onConnectionReady?()
                }
            }
        }
    }

    /// Keep both control and conversation sockets active through idle tunnels.
    /// WebSocket ping frames do not add application-level requests or responses.
    private func startHeartbeat(for socket: URLSessionWebSocketTask) {
        heartbeatTask?.cancel()
        heartbeatTask = Task { [weak self, weak socket] in
            while !Task.isCancelled {
                do { try await Task.sleep(for: self?.heartbeatInterval ?? .seconds(30)) } catch { return }
                guard let self, let socket, self.socket === socket, self.state.isConnected else { return }
                self.traceLifecycle("heartbeat-ping")
                if !self.isApplicationInBackground {
                    // 普通前台心跳也必须有截止时间，防止 TCP 黑洞一直显示在线。
                    self.probeForegroundConnection()
                    continue
                }
                socket.sendPing { [weak self, weak socket] error in
                    guard let error else { return }
                    Task { @MainActor [weak self, weak socket] in
                        await self?.handleFailure(error, socket: socket)
                    }
                }
            }
        }
    }

    /// 判断 endpoint 是否指向局域网/私有网段地址。
    /// 目的：局域网地址失败时应快速超时跳到公网地址，而不是等满 15 秒。
    static func isLocalNetworkEndpoint(_ url: URL) -> Bool {
        guard let host = url.host?.lowercased() else { return false }
        if host.hasSuffix(".local") { return true }
        let parts = host.split(separator: ".").compactMap { Int($0) }
        guard parts.count == 4 else { return false }
        switch (parts[0], parts[1]) {
        case (10, _): return true                                  // 10.0.0.0/8
        case (192, 168): return true                               // 192.168.0.0/16
        case (172, 16...31): return true                           // 172.16.0.0/12
        case (169, 254): return true                               // 169.254.0.0/16 链路本地
        default: return false
        }
    }

    private func startConnectionTimeout(for socket: URLSessionWebSocketTask) {
        connectionTimeoutTask?.cancel()
        connectionTimeoutTask = Task { [weak self, weak socket] in
            // 局域网地址快速失败（3s），公网地址沿用 15s：
            // 出门时逐个试局域网地址不应各等 15 秒。
            let isLocal = self?.endpoint.map(Self.isLocalNetworkEndpoint) ?? false
            try? await Task.sleep(for: .seconds(isLocal ? 3 : 15))
            guard let self,
                  let socket,
                  self.socket === socket,
                  self.wantsConnection,
                  !self.state.isConnected else { return }
            socket.cancel(with: .goingAway, reason: nil)
            // 只有「超时」才说明该地址在当前网络下不可达，记录具体地址；
            // 鉴权失败、DNS 解析失败等不记录，避免误伤其他局域网地址。
            if let ep = self.endpoint, Self.isLocalNetworkEndpoint(ep) {
                self.timedOutLocalEndpoints.insert(ep.absoluteString)
            }
            self.fail(
                String(localized: "connection.timeout", defaultValue: "连接超时，请检查网络或配对信息后重试。"),
                shouldReconnect: !self.isManualPairingAttempt && !self.probeOnly
            )
        }
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

/// 合并系统网络通知；初次通知只建立基线，断网会取消尚未执行的恢复。
@MainActor
final class GatewayNetworkRecovery {
    struct Path: Equatable, Sendable {
        let isSatisfied: Bool
        private let systemPath: NWPath?
        private let interfaces: [String]

        init(_ path: NWPath) {
            isSatisfied = path.status == .satisfied
            // 使用系统的路径等价判断，涵盖接口不变但路由变化的情况。
            systemPath = path
            interfaces = []
        }

#if DEBUG
        init(isSatisfied: Bool, interfaces: [String] = []) {
            self.isSatisfied = isSatisfied
            systemPath = nil
            self.interfaces = interfaces
        }
#endif
    }

    private let debounce: Duration
    private let canRecover: () -> Bool
    private let recover: () -> Void
    private var previousPath: Path?
    private var pendingTask: Task<Void, Never>?

    init(debounce: Duration = .seconds(1), canRecover: @escaping () -> Bool, recover: @escaping () -> Void) {
        self.debounce = debounce
        self.canRecover = canRecover
        self.recover = recover
    }

    deinit { pendingTask?.cancel() }

    func cancel() {
        pendingTask?.cancel()
        pendingTask = nil
    }

    func update(_ path: Path) {
        let previous = previousPath
        previousPath = path
        guard previous != path else { return }
        cancel()
        guard previous != nil, path.isSatisfied, canRecover() else { return }
        pendingTask = Task { [weak self, debounce] in
            do { try await Task.sleep(for: debounce) } catch { return }
            guard let self, !Task.isCancelled, self.canRecover() else { return }
            self.pendingTask = nil
            self.recover()
        }
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

    struct File: Encodable, Sendable { let receiptId: String }
    let type = "message"
    var sessionId: String?
    var parentSessionId: String?
    var subagentMode: String?
    var text: String
    var images: [Image]
    var files: [File]?
    var workspaceId: String?
    var clientTimeZone: String
    var mode: String
    var requestId: String?
}

private struct GatewayCommandExecuteRequest: Encodable, Sendable {
    let type = "command-execute"
    var sessionId: String
    var line: String
    var images: [GatewayMessageRequest.Image]
}

protocol GatewayCredentialStoring: Sendable {
    func loadToken(for endpoint: URL) async throws -> String?
    func saveToken(_ token: String, for endpoint: URL) async throws
    func deleteToken(for endpoint: URL) async
    func loadOrCreateDeviceID() async throws -> String
}

// 所有连接共享后台 actor，避免 Keychain IPC 占用主线程，并串行化设备 ID 创建与凭证读写。
private actor SystemGatewayCredentialStore: GatewayCredentialStoring {
    static let shared = SystemGatewayCredentialStore()
    func loadToken(for endpoint: URL) throws -> String? {
        try Task.checkCancellation()
        return try GatewayTokenStore.load(for: endpoint)
    }
    func saveToken(_ token: String, for endpoint: URL) throws {
        try Task.checkCancellation()
        try GatewayTokenStore.save(token, for: endpoint)
    }
    func deleteToken(for endpoint: URL) { GatewayTokenStore.delete(for: endpoint) }
    func loadOrCreateDeviceID() throws -> String {
        try Task.checkCancellation()
        return try GatewayDeviceIdentityStore.loadOrCreate()
    }
}

struct GatewayKeychainError: LocalizedError {
    let status: OSStatus

    var isTemporarilyUnavailable: Bool {
        status == errSecInteractionNotAllowed || status == errSecNotAvailable
    }

    var errorDescription: String? {
        (SecCopyErrorMessageString(status, nil) as String?) ??
            String(localized: "keychain.error.status", defaultValue: "Keychain 错误 \(status)")
    }
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
        guard status == errSecItemNotFound else { throw GatewayKeychainError(status: status) }

        let value = UUID().uuidString.lowercased()
        var insertion = query
        insertion.removeValue(forKey: kSecReturnData as String)
        insertion.removeValue(forKey: kSecMatchLimit as String)
        insertion[kSecValueData as String] = Data(value.utf8)
        insertion[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let addStatus = SecItemAdd(insertion as CFDictionary, nil)
        if addStatus == errSecDuplicateItem { return try loadOrCreate() }
        guard addStatus == errSecSuccess else { throw GatewayKeychainError(status: addStatus) }
        return value
    }
}

private enum GatewayTokenStore {
    private static let service = "ai.dsh.mobile.ios.gateway-token"

    static func load(for endpoint: URL) throws -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account(for: endpoint),
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw GatewayKeychainError(status: status) }
        guard let data = result as? Data, let token = String(data: data, encoding: .utf8),
              !token.isEmpty else { throw GatewayKeychainError(status: errSecDecode) }
        return token
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
            guard status == errSecSuccess else { throw GatewayKeychainError(status: status) }
        } else if updateStatus != errSecSuccess {
            throw GatewayKeychainError(status: updateStatus)
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
