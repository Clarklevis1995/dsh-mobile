import Foundation

@MainActor
final class GatewayClient: ObservableObject {
    /// History responses contain raw trajectory events (including request
    /// context) and can exceed URLSessionWebSocketTask's 1 MiB default.
    /// Keep a bounded but practical ceiling so a valid history frame does not
    /// tear down the socket before AppStore can decode it.
    private static let maximumIncomingMessageSize = 16 * 1024 * 1024

    @Published private(set) var state: ConnectionState = .disconnected
    @Published private(set) var serverPort: Int?
    @Published private(set) var clientCount: Int?

    var onFrame: ((GatewayFrame) -> Void)?
    private var socket: URLSessionWebSocketTask?
    private var receiveTask: Task<Void, Never>?
    private var reconnectTask: Task<Void, Never>?
    private var endpoint: URL?
    private var wantsConnection = false

    deinit {
        receiveTask?.cancel()
        reconnectTask?.cancel()
        socket?.cancel(with: .goingAway, reason: nil)
    }

    func connect(to rawEndpoint: String) {
        disconnect(reconnect: false)
        guard let url = URL(string: rawEndpoint), ["ws", "wss"].contains(url.scheme?.lowercased() ?? "") else {
            state = .failed("请输入有效的 ws:// 或 wss:// 地址")
            return
        }
        wantsConnection = true
        endpoint = url
        state = .connecting
        let socket = URLSession.shared.webSocketTask(with: url)
        socket.maximumMessageSize = Self.maximumIncomingMessageSize
        self.socket = socket
        socket.resume()
        receiveTask = Task { [weak self] in await self?.receiveLoop(socket) }
    }

    func disconnect(reconnect: Bool = false) {
        wantsConnection = reconnect
        reconnectTask?.cancel()
        reconnectTask = nil
        receiveTask?.cancel()
        receiveTask = nil
        socket?.cancel(with: .normalClosure, reason: nil)
        socket = nil
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
    func createWorkspace(path: String) { send(["type": "workspace-create", "path": path]) }
    func requestModels(sessionId: String) {
        send(["type": "models", "sessionId": sessionId])
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
    func requestHistory(sessionId: String, beforeSeq: Int? = nil, maxMessages: Int = 50) {
        var payload: [String: Any] = ["type": "history", "sessionId": sessionId, "maxMessages": maxMessages]
        if let beforeSeq { payload["beforeSeq"] = beforeSeq }
        send(payload)
    }

    func subscribe(sessionId: String?) {
        if let sessionId, !sessionId.isEmpty {
            send(["type": "subscribe", "sessionId": sessionId])
        } else {
            send(["type": "unsubscribe"])
        }
    }

    func sendMessage(text: String, sessionId: String?) {
        var payload: [String: Any] = ["type": "message", "text": text]
        if let sessionId, !sessionId.isEmpty { payload["sessionId"] = sessionId }
        send(payload)
    }

    private func send(_ object: [String: Any]) {
        guard let socket else {
            state = .failed("WebSocket 尚未连接")
            return
        }
        do {
            let data = try JSONSerialization.data(withJSONObject: object)
            let text = String(decoding: data, as: UTF8.self)
            Task {
                do { try await socket.send(.string(text)) }
                catch { handleFailure(error) }
            }
        } catch {
            state = .failed(error.localizedDescription)
        }
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
                    if frame.kind == "hello" {
                        state = .connected
                        serverPort = frame.port
                        clientCount = frame.clients
                    }
                    onFrame?(frame)
                } catch {
                    // One future or malformed frame must not tear down an otherwise healthy socket.
                    onFrame?(GatewayFrame(kind: "error", code: "decode-failed", message: error.localizedDescription))
                }
            }
        } catch is CancellationError {
            return
        } catch {
            handleFailure(error)
        }
    }

    private func handleFailure(_ error: Error) {
        guard wantsConnection else { return }
        state = .failed(error.localizedDescription)
        socket = nil
        receiveTask = nil
        reconnectTask?.cancel()
        reconnectTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(2))
            guard let self, self.wantsConnection, let endpoint = self.endpoint else { return }
            self.connect(to: endpoint.absoluteString)
        }
    }
}
