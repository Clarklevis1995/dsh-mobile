import Foundation
import UIKit
import Security
import OSLog
import Network
import DeepSeekHarnessShared

@MainActor
final class GatewayClient: ObservableObject {
    private static let presetLogger = LocalLogger(subsystem: "ai.dsh.mobile.ios", category: "agent-presets")
    private static let lifecycleLogger = LocalLogger(subsystem: "ai.dsh.mobile.ios", category: "gateway-lifecycle")
    private var backgroundEnteredAt: Date?

    // 仅记录传输元数据，禁止写入地址、凭据、消息正文和服务端原始关闭原因。
    private func traceLifecycle(_ event: String) {
        let elapsed = backgroundEnteredAt.map { Date().timeIntervalSince($0) } ?? -1
        Self.lifecycleLogger.notice("[GatewayLifecycle] channel=\(self.channel) event=\(event) background=\(self.isApplicationInBackground) backgroundSeconds=\(elapsed) connected=\(self.state.isConnected) wantsConnection=\(self.wantsConnection) taskId=\(self.socket?.taskIdentifier ?? -1)")
    }

    private let channel: String
    var requestDecorator: ((String) -> String?)?
    var conversationTarget: ((String) -> MobileConversationTarget?)?
    private var subscriptionPending = false
    private var pendingSubscriptionID: String?
    private var desiredSubscriptionID: String?
    private var subscriptionDirty = false
    private var subscriptionTimeout: Task<Void, Never>?
    private var subscriptionGeneration = UUID()
    private var subscriptionRecoveryAttempts = 0
    private static let maximumSubscriptionRecoveryAttempts = 2

    var subscriptionRecoveryExhausted: Bool {
        subscriptionRecoveryAttempts >= Self.maximumSubscriptionRecoveryAttempts
    }

    func cancelSubscriptionRecovery() {
        subscriptionRecoveryAttempts = 0
        desiredSubscriptionID = nil
        resetSubscriptionRequests()
    }

    /// subscribed 没有 requestId；换新连接隔离旧确认，只恢复当前订阅，不重放写操作。
    @discardableResult
    func recoverSubscription(sessionID: String) -> Bool {
        guard channel == "control", wantsConnection, state.isConnected,
              !probeOnly, !isManualPairingAttempt, !isApplicationInBackground,
              desiredSubscriptionID == sessionID, !subscriptionRecoveryExhausted,
              let endpoint else { return false }
        subscriptionRecoveryAttempts += 1
        traceLifecycle("subscription-recovery attempt=\(subscriptionRecoveryAttempts)")
        forceNextWebsocket = true
        beginConnection(to: endpoint.absoluteString, pairingCode: nil, resetReportedFailure: false)
        return true
    }

    func resetSubscriptionRequests() {
        subscriptionTimeout?.cancel()
        subscriptionTimeout = nil
        subscriptionGeneration = UUID()
        subscriptionPending = false
        pendingSubscriptionID = nil
        subscriptionDirty = false
    }

    /// subscribed 无 requestId，必须单飞并合并快速切换意图。
    func acceptSubscriptionAck(sessionID: String?) -> Bool {
        // 兼容未经过前台选择入口的历史订阅与旧网关；托管切换仍严格单飞。
        guard subscriptionPending else { return true }
        guard pendingSubscriptionID == sessionID else { return false }
        traceLifecycle("subscription-ack dirty=\(subscriptionDirty)")
        subscriptionTimeout?.cancel()
        subscriptionTimeout = nil
        subscriptionPending = false
        if subscriptionDirty {
            subscriptionDirty = false
            subscribe(sessionId: desiredSubscriptionID)
            return false
        }
        subscriptionRecoveryAttempts = 0
        return true
    }

    /// 仅供已通过 AssistantStreamState 校验的快照调用，不接受任意历史页作为确认。
    @discardableResult
    func acceptSubscriptionSnapshot(sessionID: String) -> Bool {
        guard subscriptionPending, pendingSubscriptionID == sessionID else { return false }
        return acceptSubscriptionAck(sessionID: sessionID)
    }

    var credentialID: String?
    var expectedGatewayID: String?
    var trustedEndpoints: [String] = []
    var onIdentity: ((GatewayFrame, String) throws -> Void)?
    var probeOnly = false
    private var reconnectAttempt = 0
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
    private let foregroundRtcTimeout: Duration
    private let subscriptionTimeoutInterval: Duration
    private let rtcPolicy: (String) -> Bool

    init(
        channel: String = "control",
        credentials: (any GatewayCredentialStoring)? = nil,
        heartbeatInterval: Duration = .seconds(30),
        probeTimeout: Duration = .seconds(5),
        foregroundRtcTimeout: Duration = .seconds(3),
        subscriptionTimeoutInterval: Duration = .seconds(15),
        rtcPolicy: @escaping (String) -> Bool = { GatewayTransportPolicy.shared.allowsRtc(endpoint: $0) }
    ) {
        self.channel = channel
        self.credentials = credentials ?? SystemGatewayCredentialStore.shared
        self.heartbeatInterval = heartbeatInterval
        self.probeTimeout = probeTimeout
        self.foregroundRtcTimeout = foregroundRtcTimeout
        self.subscriptionTimeoutInterval = subscriptionTimeoutInterval
        self.rtcPolicy = rtcPolicy
    }
#if DEBUG
    private var testRequestTransport: ((String) -> Void)?
    var receivedFrameBarrierForTesting: ((Data) async -> Void)?
    var incomingFrameDecoderForTesting: ((Data) async throws -> (GatewayFrame?, String?))?
    var recoveryStateForTesting: (wantsConnection: Bool, reconnectAttempt: Int, isManualPairing: Bool) {
        (wantsConnection, reconnectAttempt, isManualPairingAttempt)
    }

    /// 测试使用明确的已连接传输，避免仅投递 hello 却仍处于断线状态的伪在线 fixture。
    func installConnectedTransportForTesting(_ send: @escaping (String) -> Void = { _ in }) {
        testRequestTransport = send
        state = .connected
    }

    func installRtcConnectionForTesting(_ connection: any GatewayRtcConnection) {
        rtcLink = connection
        connectionMethod = "WebRTC"
        lastSuccessfulConnectionWasRtc = true
    }

    func recoverNetworkConnectionForTesting() { recoverNetworkConnection() }
#endif

    /// History responses contain raw trajectory events (including request
    /// context) and can exceed URLSessionWebSocketTask's 1 MiB default.
    /// Gateway v0.1.12 normally keeps history pages below 4 MiB. Retain a much
    /// larger transport ceiling for the documented case where one indivisible
    /// event is itself larger than the page budget.
    private static let maximumIncomingMessageSize = 64 * 1024 * 1024

    @Published private(set) var state: ConnectionState = .disconnected
    private struct NetworkDiagnosticsObserver {
        let pong: (String) -> Void
        let traffic: (Int, Int) -> Void
    }
    private var networkObservers: [UUID: NetworkDiagnosticsObserver] = [:]
    private var diagnosticsCapabilities = Set<String>()
    private var forwardNetworkTraffic: ((Int, Int) -> Void)?
    private var networkTrafficObserved: (() -> Bool)?
    var networkDiagnosticsConnectionKey: String { connectionGeneration.uuidString }
    var networkDiagnosticsSupported: Bool { diagnosticsCapabilities.contains("network-diagnostics-v1") }
#if DEBUG
    var networkDiagnosticsObserverCountForTesting: Int { networkObservers.count }
#endif

    func addNetworkDiagnosticsObserver(id: UUID, pong: @escaping (String) -> Void, traffic: @escaping (Int, Int) -> Void) {
        networkObservers[id] = NetworkDiagnosticsObserver(pong: pong, traffic: traffic)
    }
    func removeNetworkDiagnosticsObserver(id: UUID) { networkObservers.removeValue(forKey: id) }

    private func recordNetworkTraffic(sent: Int = 0, received: Int = 0) {
        for observer in networkObservers.values { observer.traffic(sent, received) }
        if networkTrafficObserved?() == true { forwardNetworkTraffic?(sent, received) }
    }
    private func recordNetworkSent(_ text: String) {
        if !networkObservers.isEmpty || networkTrafficObserved?() == true { recordNetworkTraffic(sent: text.utf8.count) }
    }

    func sendNetworkDiagnosticsProbe(_ requestID: String) async throws {
        guard state.isConnected, networkDiagnosticsSupported, let socket else { throw URLError(.notConnectedToInternet) }
        let data = try JSONSerialization.data(withJSONObject: ["type": "ping", "requestId": requestID])
        let payload = String(decoding: data, as: UTF8.self)
        let generation = connectionGeneration
        if let rtcLink, rtcLink.isRtc { try await rtcLink.send(payload, channel: "control") }
        else { try await socket.send(.string(payload)) }
        guard connectionGeneration == generation, self.socket === socket else { throw CancellationError() }
        recordNetworkSent(payload)
    }

    @Published private(set) var connectionMethod = ""
    @Published private(set) var connectionPaths: [String: RtcSelectedPath] = [:]
    private var rtcLink: (any GatewayRtcConnection)?
    private var forceNextWebsocket = false
    private var upgradeAfterPairing = false

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
    private var lastSuccessfulEndpoint: URL?
    private var lastSuccessfulConnectionWasRtc = false
    private var backgroundConnectionWasRtc = false
    private var foregroundFastRetryAvailable = false
    private var foregroundRtcRetryAvailable = false
    private var foregroundRtcTimeoutTask: Task<Void, Never>?
    private var foregroundRecoveryStartedAt: TimeInterval?
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
        subscriptionTimeout?.cancel()
        networkPathMonitor?.cancel()
        outboundTask?.cancel()
        receiveTask?.cancel()
        connectionPreparationTask?.cancel()
        reconnectTask?.cancel()
        connectionTimeoutTask?.cancel()
        foregroundProbeTask?.cancel()
        foregroundRtcTimeoutTask?.cancel()
        heartbeatTask?.cancel()
        socket?.cancel(with: .goingAway, reason: nil)
        transportSession.invalidateAndCancel()
    }

    func connect(to rawEndpoint: String) {
        cancelSubscriptionRecovery()
        lastSuccessfulEndpoint = nil
        lastSuccessfulConnectionWasRtc = false
        backgroundConnectionWasRtc = false
        foregroundFastRetryAvailable = false
        foregroundRtcRetryAvailable = false
        foregroundRecoveryStartedAt = nil
        forceNextWebsocket = false
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
        cancelSubscriptionRecovery()
        lastSuccessfulEndpoint = nil
        lastSuccessfulConnectionWasRtc = false
        backgroundConnectionWasRtc = false
        foregroundFastRetryAvailable = false
        foregroundRtcRetryAvailable = false
        foregroundRecoveryStartedAt = nil
        isRecoveringFromBackground = false
        isManualPairingAttempt = true
        beginConnection(to: payload.publicUrl, pairingCode: payload.pairingCode, resetReportedFailure: true)
    }

    /// 只记录场景状态。连接的生命周期由用户的连接意图与真实传输故障决定；
    /// 进入后台本身不再主动关闭 WebSocket。
    func applicationDidEnterBackground() {
        networkRecovery.cancel()
        if !isApplicationInBackground { backgroundConnectionWasRtc = lastSuccessfulConnectionWasRtc }
        foregroundRtcTimeoutTask?.cancel()
        foregroundRtcTimeoutTask = nil
        // 保留在途订阅和最后一次选择，仅取消前台等待期限。
        subscriptionTimeout?.cancel()
        subscriptionTimeout = nil
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
        let wasInBackground = isApplicationInBackground
        backgroundEnteredAt = nil
        isApplicationInBackground = false
        if wasInBackground { startSubscriptionTimeout() }
        guard wantsConnection, !isManualPairingAttempt, !probeOnly else { return }
        // 首次 active 通知可能晚于冷启动连接任务；不能打断正在进行的握手。
        guard wasInBackground || state != .connecting else { return }
        if wasInBackground {
            foregroundFastRetryAvailable = lastSuccessfulEndpoint != nil
            foregroundRtcRetryAvailable = backgroundConnectionWasRtc
            backgroundConnectionWasRtc = false
            foregroundRecoveryStartedAt = ProcessInfo.processInfo.systemUptime
        }
        reconnectAttempt = 0
        if state.isConnected {
            // 后台可能已经先恢复了 WS；保留进入后台时的 RTC 偏好。
            if wasInBackground, foregroundRtcRetryAvailable, rtcLink?.isRtc != true,
               let endpoint = lastSuccessfulEndpoint ?? endpoint, rtcPolicy(endpoint.absoluteString),
               expectedGatewayID != nil, channel == "control" {
                reconnectForegroundTransport(to: endpoint)
                return
            }
            conversationClient?.applicationDidBecomeActive()
            probeForegroundConnection()
            return
        }
        guard let endpoint = lastSuccessfulEndpoint ?? endpoint else { return }
        reconnectTask?.cancel()
        reconnectTask = nil
        if wasInBackground, lastSuccessfulEndpoint != nil {
            reconnectForegroundTransport(to: endpoint)
            return
        }
        beginConnection(
            to: endpoint.absoluteString,
            pairingCode: pairingCode,
            resetReportedFailure: false
        )
    }

    /// 前台恢复最多尝试一次新的 RTC；限时失败后立即改用 WS，避免循环协商。
    private func reconnectForegroundTransport(to endpoint: URL) {
        let retryRtc = foregroundRtcRetryAvailable && rtcPolicy(endpoint.absoluteString) &&
            expectedGatewayID != nil && channel == "control"
        foregroundRtcRetryAvailable = false
        forceNextWebsocket = !retryRtc
        if retryRtc {
            traceLifecycle("foreground-fast-reconnect transport=webrtc")
        } else {
            foregroundFastRetryAvailable = false
            traceLifecycle("foreground-fast-reconnect transport=websocket")
        }
        beginConnection(to: endpoint.absoluteString, pairingCode: nil, resetReportedFailure: false,
                        rtcRecoveryTimeout: retryRtc ? foregroundRtcTimeout : nil)
    }

    private func beginConnection(to rawEndpoint: String, pairingCode: String?, resetReportedFailure: Bool,
                                 rtcRecoveryTimeout: Duration? = nil) {
        traceLifecycle("begin-connection")
        disconnectTransport(reconnect: false)
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
            let rtcAllowed = self.rtcPolicy(url.absoluteString)
            let negotiate = self.channel == "control" && !self.probeOnly && token?.isEmpty == false &&
                pairingCode == nil && self.expectedGatewayID != nil && !self.forceNextWebsocket && rtcAllowed
            if self.channel == "control", !self.probeOnly, !negotiate {
                let reason: String
                if !rtcAllowed { reason = "lan-websocket" }
                else if pairingCode != nil { reason = "pairing" }
                else if token?.isEmpty != false { reason = "no-credential" }
                else if self.expectedGatewayID == nil { reason = "no-gateway-identity" }
                else { reason = "websocket-recovery" }
                GatewayRtcLog.write(RtcDiagnostics.shared.message(event: "upgrade-skipped", reason: reason,
                    phase: "connecting", channel: "control", mode: "websocket", elapsedMs: 0))
            }
            self.forceNextWebsocket = false
            if negotiate { request.setValue("negotiate-v1", forHTTPHeaderField: "X-DSH-Transport-Intent") }
            let socket = self.transportSession.webSocketTask(with: request)
            socket.maximumMessageSize = Self.maximumIncomingMessageSize
            self.socket = socket
            if negotiate, let gatewayID = self.expectedGatewayID {
                self.rtcLink = GatewayRtcLink(expectedGatewayID: gatewayID, wsSend: { [weak self, weak socket] text in
                    guard let self, let socket, self.socket === socket else { throw URLError(.cancelled) }
                    try await socket.send(.string(text))
                }, deliver: { [weak self, weak socket] channel, data in
                    guard let self, let socket, self.socket === socket else { return }
                    if channel == "control" { await self.receiveBusinessData(data, socket: socket) }
                    else { await self.receiveRtcConversation(data, socket: socket) }
                }, selected: { [weak self, weak socket] method in
                    guard let self, let socket, self.socket === socket else { return }
                    self.connectionMethod = method
                }, pathChanged: { [weak self, weak socket] paths in
                    guard let self, let socket, self.socket === socket else { return }
                    self.connectionPaths = paths
                }, failure: { [weak self, weak socket] recoverable in
                    guard let self, let socket, self.socket === socket else { return }
                    self.forceNextWebsocket = recoverable
                    self.fail(recoverable ? "RTC 连接已中断，正在使用 WebSocket 重新连接。" : "RTC 身份或传输校验失败，请重新连接。",
                              shouldReconnect: recoverable)
                })
            }
            socket.resume()
            self.startConnectionTimeout(for: socket)
            if negotiate, let rtcRecoveryTimeout {
                self.foregroundRtcTimeoutTask = Task { [weak self, weak socket] in
                    do { try await Task.sleep(for: rtcRecoveryTimeout) } catch { return }
                    guard let self, let socket, self.socket === socket,
                          self.connectionGeneration == generation, !self.state.isConnected,
                          !self.isApplicationInBackground else { return }
                    self.traceLifecycle("foreground-rtc-recovery-timeout fallback=websocket")
                    self.fail("RTC 恢复超时，正在使用 WebSocket 恢复连接。", shouldReconnect: true, reportFailure: false)
                }
            }
            self.receiveTask = Task { [weak self] in await self?.receiveLoop(socket) }
        }
    }

    func disconnect(reconnect: Bool = false) {
        cancelSubscriptionRecovery()
        backgroundConnectionWasRtc = false
        foregroundRtcRetryAvailable = false
        foregroundFastRetryAvailable = false
        disconnectTransport(reconnect: reconnect)
    }

    private func disconnectTransport(reconnect: Bool) {
        foregroundRtcTimeoutTask?.cancel()
        foregroundRtcTimeoutTask = nil
        connectionGeneration = UUID()
        rtcLink?.close()
        rtcLink = nil
        connectionMethod = ""
        connectionPaths = [:]
        diagnosticsCapabilities.removeAll()
        upgradeAfterPairing = false
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
        if desiredSubscriptionID != sessionId { subscriptionRecoveryAttempts = 0 }
        desiredSubscriptionID = sessionId
        if subscriptionPending { subscriptionDirty = true; return }
        subscriptionPending = true
        pendingSubscriptionID = sessionId
        startSubscriptionTimeout()
        gatewayApprovalTrace("transport subscribe hasSession=\(sessionId?.isEmpty == false)")
        if let sessionId, !sessionId.isEmpty {
            send(["type": "subscribe", "sessionId": sessionId, "assistantStream": true])
        } else {
            send(["type": "unsubscribe"])
        }
    }

    private func startSubscriptionTimeout() {
        guard subscriptionPending, !isApplicationInBackground else { return }
        let generation = UUID()
        subscriptionGeneration = generation
        let timeout = subscriptionTimeoutInterval
        subscriptionTimeout?.cancel()
        subscriptionTimeout = Task { [weak self] in
            do { try await Task.sleep(for: timeout) } catch { return }
            guard let self, !Task.isCancelled, self.subscriptionPending,
                  self.subscriptionGeneration == generation else { return }
            let target = self.pendingSubscriptionID
            let superseded = self.subscriptionDirty && target != self.desiredSubscriptionID
            let latest = self.desiredSubscriptionID
            self.traceLifecycle("subscription-timeout superseded=\(superseded) hasSession=\(target?.isEmpty == false)")
            self.resetSubscriptionRequests()
            if superseded {
                // 旧请求无响应也必须继续处理退出/切换，不能丢掉最后一次导航意图。
                self.subscribe(sessionId: latest)
                return
            }
            // 退出订阅不应把已离开的会话重新带回全局错误弹窗。
            guard let target, !target.isEmpty else { return }
            let isChild = self.conversationTarget?(target)?.parentSessionId != nil
            self.onFrame?(GatewayFrame(kind: "error", sessionId: target, code: "subscription-timeout",
                message: isChild ? "成员会话订阅超时，请重试或返回 Lead" : "会话订阅超时，请重试",
                requestType: "subscribe"))
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

    /// 在 UI 清空草稿前决定承载。给 JSON 转义和协议元数据保留上界，避免编码大型 Base64 阻塞主线程。
    func prepareMessageTransport(text: String, images: [GatewayOutgoingImage]) -> Bool {
        guard let rtcLink, rtcLink.isRtc else { return true }
        var upperBound = 65536
        for scalar in text.unicodeScalars {
            switch scalar.value {
            case 0...31: upperBound += 6
            case 34, 47, 92: upperBound += 2
            default: upperBound += scalar.utf8.count
            }
        }
        for image in images { upperBound += ((image.data.count + 2) / 3) * 8 + (image.name?.utf8.count ?? 0) * 6 }
        guard upperBound > rtcLink.maximumMessageBytes else { return true }
        GatewayRtcLog.write(RtcDiagnostics.shared.message(event: "fallback-requested", reason: "message-too-large",
            phase: "active", channel: "group", mode: "webrtc", elapsedMs: rtcLink.elapsedMilliseconds))
        // 确认尚未发送，换新 owner；由用户保留草稿并重新提交，不重放任何写操作。
        forceNextWebsocket = true
        if let endpoint { beginConnection(to: endpoint.absoluteString, pairingCode: nil, resetReportedFailure: false) }
        return false
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
        let generation = connectionGeneration
        Task { [weak self] in
            do {
                let payload = try await Task.detached(priority: .userInitiated) {
                    try JSONEncoder().encode(request)
                }.value
                if let self, self.connectionGeneration == generation, self.state.isConnected {
                    // 图片 Base64 已在后台编码；直接使用固定地址，不在主线程重新解析整条消息。
                    let client = self.conversationClient ?? self
                    client.sendRequestPayload(String(decoding: payload, as: UTF8.self), decorate: false)
                }
            } catch {
                guard let self, !Task.isCancelled, self.connectionGeneration == generation else { return }
                await self.handleFailure(error)
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
                guard self?.socket === socket else { return }
                self?.sendRequestPayload(String(decoding: payload, as: UTF8.self))
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
        if let rtcLink, rtcLink.isRtc {
            let type = (try? JSONDecoder().decode(RequestEnvelope.self, from: Data(payload.utf8)).type) ?? ""
            let target = Self.usesConversationChannel(type) ? "conversation" : "control"
            Task { [weak self, weak rtcLink] in
                guard let self, let rtcLink, self.rtcLink === rtcLink else { return }
                do {
                    try await rtcLink.send(payload, channel: target)
                    if self.rtcLink === rtcLink { self.recordNetworkSent(payload) }
                }
                catch GatewayMgwCodec.Failure.tooLarge {
                    guard !Task.isCancelled, self.rtcLink === rtcLink else { return }
                    let envelope = (try? JSONSerialization.jsonObject(with: Data(payload.utf8))) as? [String: Any]
                    self.onFrame?(GatewayFrame(kind: "error", code: "transport/ws-required",
                        message: "消息超过 RTC 传输上限，请使用 WebSocket 连接后重新提交。", requestType: type,
                        requestId: envelope?["requestId"] as? String))
                } catch {
                    guard self.rtcLink === rtcLink else { return }
                    self.forceNextWebsocket = true
                    self.fail("RTC 发送中断，请确认操作状态后重试。", shouldReconnect: true)
                }
            }
            return
        }
        if let conversationClient,
           let type = try? JSONDecoder().decode(RequestEnvelope.self, from: Data(payload.utf8)).type,
           Self.usesConversationChannel(type) {
            conversationClient.sendRequestPayload(payload)
            return
        }
        // 凭据读取也属于会话通道的连接准备阶段，此时 socket 尚未创建。
        // 必须先入队，否则重连后的首个订阅会被静默丢弃，留下绿灯和订阅超时。
        if deferUntilConversationHello(payload) { return }
        guard let socket else {
            state = .failed(String(localized: "state.websocket.not-connected", defaultValue: "WebSocket 尚未连接"))
            return
        }
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
                if self.socket === socket { self.recordNetworkSent(payload) }
                if logsPresets { Self.presetLogger.info("send completed") }
            } catch { await self.handleFailure(error, socket: socket) }
        }
    }

    private func deferUntilConversationHello(_ payload: String) -> Bool {
        guard channel == "conversation", wantsConnection, state == .connecting else { return false }
        let bytes = payload.utf8.count
        guard pendingConversationPayloads.count < 64,
              pendingConversationBytes + bytes <= 16 * 1024 * 1024 else {
            fail("会话通道尚未就绪，请稍后重试。", shouldReconnect: false)
            return true
        }
        pendingConversationPayloads.append(payload)
        pendingConversationBytes += bytes
        traceLifecycle("conversation-request-buffered socketReady=\(socket != nil) count=\(pendingConversationPayloads.count)")
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
        let generation = connectionGeneration
        do {
            while !Task.isCancelled {
                let message = try await socket.receive()
                let data: Data
                switch message {
                case .string(let text): data = Data(text.utf8)
                case .data(let payload): data = payload
                @unknown default: continue
                }
#if DEBUG
                await receivedFrameBarrierForTesting?(data)
#endif
                // receive 的等待可以跨越 WS/RTC 切换；旧帧不能交给新连接的状态机。
                guard !Task.isCancelled, self.socket === socket, connectionGeneration == generation else { return }
                if let rtcLink { try await rtcLink.webSocket(data) }
                else { await receiveBusinessData(data, socket: socket) }
            }
        } catch is CancellationError {
            return
        } catch {
            await handleFailure(error, socket: socket)
        }
    }

    private func decodeBusinessFrame(_ data: Data) async throws -> (GatewayFrame?, String?) {
#if DEBUG
        if let incomingFrameDecoderForTesting { return try await incomingFrameDecoderForTesting(data) }
#endif
        return try await Task.detached(priority: .userInitiated) { try Self.decodeIncomingFrame(data) }.value
    }

    private func receiveBusinessData(_ data: Data, socket: URLSessionWebSocketTask) async {
        guard self.socket === socket else { return }
        recordNetworkTraffic(received: data.count)
        do {
            // 语音帧使用独立公共解码器，所有 JSON 扫描仍在后台完成。
            let decoded = try await decodeBusinessFrame(data)
            guard !Task.isCancelled, self.socket === socket else { return }
            if let speech = decoded.1 {
                if state.isConnected { onSpeechFrame?(speech) }
                return
            }
            guard let frame = decoded.0 else { return }
            if frame.kind == "pong", let requestID = frame.requestId, requestID.hasPrefix("network-check:") {
                for observer in networkObservers.values { observer.pong(requestID) }
                return
            }
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
                return
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
                    upgradeAfterPairing = channel == "control" && !probeOnly && expectedGatewayID != nil && rtcPolicy(endpoint.absoluteString)
                    // 一次性配对已完成；hello 前断线也应使用长期 token 自动恢复。
                    isManualPairingAttempt = false
                    startMonitoringNetworkChanges()
                } catch {
                    guard !Task.isCancelled, self.socket === socket else { return }
                    fail(String(localized: "pairing.token.keychain-failed", defaultValue: "配对成功，但无法将设备 token 写入 Keychain：\(error.localizedDescription)"), shouldReconnect: false)
                    return
                }
            } else if frame.kind == "hello" {
                diagnosticsCapabilities = Set(frame.capabilities ?? [])
                if upgradeAfterPairing, let endpoint {
                    upgradeAfterPairing = false
                    beginConnection(to: endpoint.absoluteString, pairingCode: nil, resetReportedFailure: false)
                    return
                }
                if channel == "control", !probeOnly, rtcLink?.isRtc != true, frame.capabilities?.contains("split-channels") == true {
                    openConversationChannel()
                }
                // `hello` is the protocol's authentication boundary.
                // Debug mode may explicitly return authenticated=false.
                connectionTimeoutTask?.cancel()
                connectionTimeoutTask = nil
                foregroundRtcTimeoutTask?.cancel()
                foregroundRtcTimeoutTask = nil
                isManualPairingAttempt = false
                startMonitoringNetworkChanges()
                reconnectAttempt = 0
                lastSuccessfulEndpoint = endpoint
                lastSuccessfulConnectionWasRtc = rtcLink?.isRtc == true
                foregroundFastRetryAvailable = false
                foregroundRtcRetryAvailable = false
                if let started = foregroundRecoveryStartedAt {
                    let elapsed = Int((ProcessInfo.processInfo.systemUptime - started) * 1000)
                    traceLifecycle("foreground-recovered elapsedMs=\(elapsed)")
                    foregroundRecoveryStartedAt = nil
                }
                if connectionMethod.isEmpty { connectionMethod = "WebSocket" }
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
            guard !Task.isCancelled, self.socket === socket else { return }
            // One future or malformed frame must not tear down an otherwise healthy socket.
            onFrame?(GatewayFrame(kind: "error", code: "decode-failed", message: GatewayWireDecoder.failureDescription(error)))
        }
    }


    private func receiveRtcConversation(_ data: Data, socket: URLSessionWebSocketTask) async {
        guard self.socket === socket else { return }
        recordNetworkTraffic(received: data.count)
        do {
            let decoded = try await decodeBusinessFrame(data)
            guard !Task.isCancelled, self.socket === socket, state.isConnected else { return }
            if let speech = decoded.1 { onSpeechFrame?(speech); return }
            guard let frame = decoded.0, frame.kind != "hello", frame.kind != "paired" else { return }
            deliverApplicationFrame(frame, data: data)
        } catch {
            guard !Task.isCancelled, self.socket === socket else { return }
            forceNextWebsocket = true
            fail("RTC 业务消息格式校验失败。", shouldReconnect: false)
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
        client.networkTrafficObserved = { [weak self] in self?.networkObservers.isEmpty == false }
        client.forwardNetworkTraffic = { [weak self] sent, received in self?.recordNetworkTraffic(sent: sent, received: received) }
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
            Self.presetLogger.info("received decoded response kind=\(frame.kind) bytes=\(data.count)")
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
        foregroundRtcTimeoutTask?.cancel()
        foregroundRtcTimeoutTask = nil
        if let rtcLink, rtcLink.isRtc {
            forceNextWebsocket = true
            GatewayRtcLog.write(RtcDiagnostics.shared.message(event: shouldReconnect ? "fallback-requested" : "blocked",
                reason: "connection-lost", phase: "active", channel: channel, mode: "webrtc", elapsedMs: rtcLink.elapsedMilliseconds))
        }
        connectionGeneration = UUID()
        rtcLink?.close()
        rtcLink = nil
        connectionMethod = ""
        connectionPaths = [:]
        diagnosticsCapabilities.removeAll()
        upgradeAfterPairing = false
        connectionPreparationTask?.cancel()
        connectionPreparationTask = nil
        traceLifecycle("failure-cleanup reconnect=\(shouldReconnect) report=\(reportFailure)")
        onConnectionInvalidated?()
        resetSubscriptionRequests()
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
        if foregroundFastRetryAvailable, !isApplicationInBackground, let endpoint = lastSuccessfulEndpoint ?? endpoint {
            reconnectTask = Task { [weak self] in
                guard let self, !Task.isCancelled, self.wantsConnection else { return }
                self.reconnectForegroundTransport(to: endpoint)
            }
            return
        }
        reconnectAttempt += 1
        let delay = min(30, 2 * (1 << min(reconnectAttempt - 1, 4)))
        traceLifecycle("schedule-reconnect attempt=\(reconnectAttempt) delaySeconds=\(delay)")
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
        // RTC 协商本身会带来多次路径通知；正在连接时保留原有超时，避免不断推倒重来。
        guard state != .connecting else {
            traceLifecycle("network-path-recovery-deferred reason=handshake-in-progress")
            return
        }
        reconnectAttempt = 0
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
        if let rtcLink, rtcLink.isRtc {
            traceLifecycle("foreground-rtc-probe-ping channels=control,conversation")
            foregroundProbeTask = Task { [weak self, weak socket] in
                do {
                    try await rtcLink.checkHealth(timeout: min(self?.probeTimeout ?? .seconds(2), .seconds(2)))
                    guard let self, let socket, self.socket === socket,
                          !self.isApplicationInBackground, self.foregroundProbeSequence == sequence else { return }
                    self.foregroundProbeTask = nil
                    self.foregroundFastRetryAvailable = false
                    self.foregroundRtcRetryAvailable = false
                    self.foregroundRecoveryStartedAt = nil
                    self.isRecoveringFromBackground = false
                    self.traceLifecycle("foreground-rtc-probe-pong channels=control,conversation")
                    self.onConnectionReady?()
                } catch {
                    guard !Task.isCancelled, let self, let socket, self.socket === socket,
                          !self.isApplicationInBackground, self.foregroundProbeSequence == sequence else { return }
                    self.foregroundProbeTask = nil
                    if case let GatewayRtcHealthProbe.Failure.timeout(channels) = error {
                        self.traceLifecycle("foreground-rtc-probe-failed reason=pong-timeout channels=\(channels.joined(separator: ","))")
                    } else {
                        self.traceLifecycle("foreground-rtc-probe-failed reason=business-channel-send-failed")
                    }
                    self.fail("RTC 业务通道未响应，正在恢复连接。", shouldReconnect: true, reportFailure: false)
                }
            }
            return
        }
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
                    self.foregroundFastRetryAvailable = false
                    self.foregroundRtcRetryAvailable = false
                    self.foregroundRecoveryStartedAt = nil
                    self.isRecoveringFromBackground = false
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
