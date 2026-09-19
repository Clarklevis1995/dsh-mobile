import XCTest
import CryptoKit
import Network
@testable import DeepSeekHarnessMobile

/// 回归：冷启动自动连接的凭据门禁。
///
/// 原实现把“Keychain 条目此刻不可读”（设备未首次解锁、App 预热）当成“没有凭据”，
/// 并且在检查之前就消费掉一次性标记，于是自动连接被静默放弃，只有手动点“连接”
/// （绕开这道门禁）才能连上。这些测试用真实 `AppStore`/`GatewayClient` 加本地
/// mock 网关覆盖该边界，不依赖真实 DSH Host。
final class GatewayCredentialRecoveryTests: XCTestCase {
    private var previousCredentialStore: GatewayCredentialStoring!

    override func setUp() {
        super.setUp()
        previousCredentialStore = MainActor.assumeIsolated { GatewayClient.credentialStore }
    }

    override func tearDown() {
        let previous = previousCredentialStore
        MainActor.assumeIsolated { GatewayClient.credentialStore = previous! }
        previousCredentialStore = nil
        super.tearDown()
    }

    // MARK: - Tests

    @MainActor
    func testColdLaunchWithUnreadableCredentialConnectsAfterSceneBecomesActive() async throws {
        try skipUnlessDeviceIdentityIsReadable()
        let token = "recovery-token"
        let store = StubCredentialStore()
        store.unavailable = true
        GatewayClient.credentialStore = store
        try await withMockGateway { port in
            let app = makeStore(port: port)
            defer { app.deactivateGateway() }
            store.token = token

            XCTAssertEqual(
                app.gateway.credentialState(for: app.endpoint),
                .temporarilyUnavailable,
                "测试前提：凭据此刻不可读"
            )

            // 冷启动：RootNavigationHost.task
            app.connectOnColdLaunchIfPaired()

            // 读不到凭据时必须保持“待自动连接”而不是记成已处理，也不能弹错误。
            XCTAssertNil(app.lastError, "凭据暂时不可读不能变成用户可见失败")
            XCTAssertEqual(app.gateway.state, .disconnected, "凭据不可读时不应盲目打开未鉴权 socket")
            app.handleScenePhase(.active)

            try await waitUntil(timeout: 6) { @MainActor in app.gateway.state.isConnected }
            XCTAssertEqual(app.gateway.state, .connected, "解锁后回前台必须补上自动连接")
            XCTAssertNil(app.lastError)
        }
    }

    @MainActor
    func testColdLaunchOnTemporarilyUnreadableCredentialStillReachesGateway() async throws {
        try skipUnlessDeviceIdentityIsReadable()
        let store = StubCredentialStore()
        store.unavailable = true
        GatewayClient.credentialStore = store
        try await withMockGateway { port in
            let app = makeStore(port: port)
            defer { app.deactivateGateway() }

            app.connectOnColdLaunchIfPaired()
            XCTAssertEqual(app.gateway.state, .disconnected)

            // 解锁后凭据依然读不到（例如条目被系统清理）：仍要按文档允许的
            // 本地 Debug 方式尝试一次，而不是永久停在“未连接 + 一次弹窗”。
            app.connectOnColdLaunchIfPaired()
            try await waitUntil(timeout: 6) { @MainActor in app.gateway.state.isConnected }
            XCTAssertEqual(app.gateway.state, .connected)
        }
    }

    @MainActor
    func testColdLaunchWithoutAnyCredentialStillBlocksTheSocket() async throws {
        try skipUnlessDeviceIdentityIsReadable()
        let store = StubCredentialStore()
        GatewayClient.credentialStore = store
        try await withMockGateway { port in
            let app = makeStore(port: port)
            defer { app.deactivateGateway() }

            app.connectOnColdLaunchIfPaired()
            XCTAssertNotNil(app.lastError, "未配对的主机仍要引导用户扫码")
            let error = app.lastError
            app.lastError = nil

            // 从未配对的主机不重试：否则每次回前台都会重复弹出同一个提示。
            app.handleScenePhase(.active)
            XCTAssertEqual(app.gateway.state, .disconnected)
            XCTAssertNil(app.lastError)

            // 手动“连接”不受门禁约束，仍然可以立即尝试（本地 Debug / 重新配对）。
            app.connect()
            try await waitUntil(timeout: 6) { @MainActor in app.gateway.state.isConnected }
            XCTAssertEqual(app.gateway.state, .connected)
            XCTAssertNotNil(error)
        }
    }

    @MainActor
    func testForegroundReturnDoesNotChurnAnAlreadyConnectedSocket() async throws {
        try skipUnlessDeviceIdentityIsReadable()
        let store = StubCredentialStore()
        GatewayClient.credentialStore = store
        try await withMockGateway { port in
            let app = makeStore(port: port)
            defer { app.deactivateGateway() }
            store.token = "recovery-token"

            app.connectOnColdLaunchIfPaired()
            try await waitUntil(timeout: 6) { @MainActor in app.gateway.state.isConnected }
            let connectedEndpoint = app.gateway.connectedEndpoint

            for _ in 0..<3 { app.handleScenePhase(.active) }
            try await Task.sleep(for: .milliseconds(200))

            XCTAssertEqual(app.gateway.state, .connected, "已连接时回前台不得重开会话")
            XCTAssertEqual(app.gateway.connectedEndpoint, connectedEndpoint)
        }
    }

    /// 主机列表的在线指示灯同样依赖凭据可读性。凭据一次读不到时若直接判定离线，
    /// 已配对且在线的手机就会一直显示灰灯——这正是“连接一直挂起”的另一种表现。
    /// 该测试只覆盖 presence 判定的重试（不涉及真实网络）：第一次 Keychain 读失败、
    /// 之后恢复可读时，探测必须重新读到凭据，而不是把主机判定为离线。
    @MainActor
    func testPresenceProbeKeepsPairedHostOnlineAfterAFailedCredentialRead() async throws {
        try skipUnlessDeviceIdentityIsReadable()
        let endpoint = "ws://127.0.0.1:6553/ws/mobile"
        let gatewayID = "d56a1098-8519-43a1-9dce-fb99863bf5bb"
        let store = StubCredentialStore()
        store.token = "presence-token"
        store.failingLoads = 1
        GatewayClient.credentialStore = store

        let defaults = try XCTUnwrap(UserDefaults(suiteName: "presence-credential-\(UUID().uuidString)"))
        let profile = GatewayProfile(
            gatewayId: gatewayID,
            gatewayName: "手机",
            endpoints: [endpoint]
        )
        defaults.set(try JSONEncoder().encode([profile]), forKey: "gateway.profiles.v1")
        let hosts = MultiGatewayStore(defaults: defaults)
        defer { hosts.activeStore.deactivateGateway() }
        XCTAssertEqual(hosts.activeID, profile.id, "资料应在启动时激活为当前主机")
        XCTAssertTrue(hosts.onlineIDs.contains(profile.id))

        await hosts.refreshPresence()

        XCTAssertGreaterThanOrEqual(store.loads, 2, "presence 必须在读失败后重试，而不是直接判定离线")
        XCTAssertTrue(
            hosts.onlineIDs.contains(profile.id),
            "一次 Keychain 读失败不得把已配对主机判定为离线（名单先被清空，只有重新读到凭据才会补回）"
        )
    }

    @MainActor
    func testLegacyHasStoredCredentialReportsReadabilityOnly() {
        let store = StubCredentialStore()
        GatewayClient.credentialStore = store
        let endpoint = "ws://127.0.0.1:3080/ws/mobile"
        let client = GatewayClient()

        XCTAssertFalse(client.hasStoredCredential(for: endpoint))
        XCTAssertEqual(client.credentialState(for: endpoint), .missing)

        store.unavailable = true
        XCTAssertFalse(client.hasStoredCredential(for: endpoint))
        XCTAssertEqual(client.credentialState(for: endpoint), .temporarilyUnavailable)

        store.unavailable = false
        store.token = "token"
        XCTAssertTrue(client.hasStoredCredential(for: endpoint))
        XCTAssertEqual(client.credentialState(for: endpoint), .available("token"))
    }

    // MARK: - Helpers

    private func skipUnlessDeviceIdentityIsReadable() throws {
        do {
            _ = try GatewayDeviceIdentityStore.loadOrCreate()
        } catch {
            throw XCTSkip("本机 Keychain 不可用，无法在不接触真实凭据的前提下覆盖连接路径：\(error)")
        }
    }

    @MainActor
    private func makeStore(port: UInt16) -> AppStore {
        let endpoint = "ws://127.0.0.1:\(port)/ws/mobile"
        return AppStore(preferences: RecoveryPreferencesSpy(
            endpoint: endpoint,
            selectedWorkspaceID: nil,
            sessions: []
        ))
    }

    @MainActor
    private func waitUntil(
        timeout: TimeInterval,
        _ condition: () async throws -> Bool
    ) async throws {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if try await condition() { return }
            try await Task.sleep(for: .milliseconds(25))
        }
        let matched = try await condition()
        XCTAssertTrue(matched, "等待条件超时（\(timeout)s）")
    }
}

/// 本文件私有的偏好实现：`GatewayProtocolTests` 里的同类是 private，跨文件不可见。
final class RecoveryPreferencesSpy: AppPreferences {
    var endpoint: String
    var selectedWorkspaceID: String?
    private let sessions: [SessionSummary]

    init(endpoint: String, selectedWorkspaceID: String?, sessions: [SessionSummary]) {
        self.endpoint = endpoint
        self.selectedWorkspaceID = selectedWorkspaceID
        self.sessions = sessions
    }

    func loadSessions() -> [SessionSummary] { sessions }
    func saveSessions(_ sessions: [SessionSummary]) {}
    func performMigrations() {}
}

/// 可控的凭据读取：分别模拟“条目不存在”“此刻读到 Keychain 错误”和“前若干次读失败”。
final class StubCredentialStore: GatewayCredentialStoring, @unchecked Sendable {
    var token: String?
    var unavailable = false
    /// 前 N 次读取抛出“暂时不可读”，用于验证调用方的重试。
    var failingLoads = 0
    private(set) var loads = 0

    func load(for endpoint: URL) throws -> String? {
        loads += 1
        if unavailable || loads <= failingLoads {
            throw GatewayCredentialError.unavailable(errSecInteractionNotAllowed)
        }
        return token
    }

    func save(_ token: String, for endpoint: URL) throws { self.token = token }

    func delete(for endpoint: URL) { token = nil }
}

/// 极简本地 WebSocket 网关：完成握手后立即发送 control `hello`。
/// 只实现测试需要的部分（单帧文本、无分片、不回 pong）。
final class MockGatewayServer: @unchecked Sendable {
    private let listener: NWListener
    private let queue = DispatchQueue(label: "mock-gateway")
    private var connections: [NWConnection] = []

    init(port: UInt16) throws {
        guard let value = NWEndpoint.Port(rawValue: port) else {
            throw NSError(domain: "MockGatewayServer", code: 1, userInfo: [NSLocalizedDescriptionKey: "非法端口"])
        }
        listener = try NWListener(using: .tcp, on: value)
    }

    static func start() async throws -> MockGatewayServer {
        for value in UInt16(52_400)...UInt16(52_460) {
            guard let server = try? MockGatewayServer(port: value) else { continue }
            if await server.startIfAvailable() { return server }
        }
        throw NSError(domain: "MockGatewayServer", code: 2, userInfo: [NSLocalizedDescriptionKey: "没有可用测试端口"])
    }

    var port: UInt16 { listener.port?.rawValue ?? 0 }

    func stop() {
        queue.sync {
            connections.forEach { $0.cancel() }
            connections.removeAll()
        }
        listener.cancel()
    }

    private func startIfAvailable() async -> Bool {
        let ready = ReadyFlag()
        listener.stateUpdateHandler = { state in
            switch state {
            case .ready: ready.resume(true)
            case .failed, .cancelled: ready.resume(false)
            default: break
            }
        }
        listener.newConnectionHandler = { [weak self] connection in
            self?.queue.async { self?.handle(connection) }
        }
        listener.start(queue: queue)
        let available = await ready.value()
        if !available { listener.cancel() }
        return available
    }

    private func handle(_ connection: NWConnection) {
        connections.append(connection)
        connection.start(queue: queue)
        var buffer = Data()
        func receive() {
            connection.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { [weak self] data, _, isComplete, error in
                guard let self else { return }
                if let data, !data.isEmpty {
                    buffer.append(data)
                    guard let text = String(data: buffer, encoding: .utf8) else { receive(); return }
                    if text.contains("\r\n\r\n"), let key = Self.header("Sec-WebSocket-Key", in: text) {
                        let protocols = Self.header("Sec-WebSocket-Protocol", in: text)?
                            .split(separator: ",")
                            .map { $0.trimmingCharacters(in: .whitespaces) } ?? []
                        let ace = Data(Insecure.SHA1.hash(data: Data((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").utf8))).base64EncodedString()
                        var response = "HTTP/1.1 101 Switching Protocols\r\n"
                        response += "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                        response += "Sec-WebSocket-Accept: \(ace)\r\n"
                        if let selected = protocols.first { response += "Sec-WebSocket-Protocol: \(selected)\r\n" }
                        response += "\r\n"
                        connection.send(content: Data(response.utf8), completion: .contentProcessed { _ in
                            connection.send(content: Self.textFrame(Self.helloFrame), completion: .contentProcessed { _ in })
                        })
                        return
                    }
                }
                if isComplete || error != nil { connection.cancel(); return }
                receive()
            }
        }
        receive()
    }

    private static func header(_ name: String, in request: String) -> String? {
        request
            .components(separatedBy: "\r\n")
            .first { $0.lowercased().hasPrefix(name.lowercased() + ":") }?
            .split(separator: ":", maxSplits: 1)
            .last
            .map { $0.trimmingCharacters(in: .whitespaces) }
    }

    private static func textFrame(_ text: String) -> Data {
        let payload = Data(text.utf8)
        var frame = Data([0x81])
        if payload.count < 126 {
            frame.append(UInt8(payload.count))
        } else if payload.count < 65_536 {
            frame.append(126)
            frame.append(UInt8((payload.count >> 8) & 0xFF))
            frame.append(UInt8(payload.count & 0xFF))
        } else {
            frame.append(127)
            for shift in stride(from: 56, through: 0, by: -8) {
                frame.append(UInt8((payload.count >> shift) & 0xFF))
            }
        }
        frame.append(payload)
        return frame
    }

    static let helloFrame = #"{"kind":"hello","gatewayId":"11111111-1111-4111-8111-111111111111","gatewayName":"Mock","protocol":3,"authenticated":true,"port":3080,"clients":1}"#

    private final class ReadyFlag: @unchecked Sendable {
        private let lock = NSLock()
        private var result: Bool?
        private var continuation: CheckedContinuation<Bool, Never>?

        func resume(_ value: Bool) {
            lock.lock()
            defer { lock.unlock() }
            guard result == nil else { return }
            result = value
            if let continuation {
                self.continuation = nil
                continuation.resume(returning: value)
            }
        }

        func value() async -> Bool {
            await withCheckedContinuation { continuation in
                lock.lock()
                defer { lock.unlock() }
                if let result {
                    continuation.resume(returning: result)
                } else {
                    self.continuation = continuation
                }
            }
        }
    }
}

@MainActor
private func withMockGateway<T>(_ body: (UInt16) async throws -> T) async throws -> T {
    let server = try await MockGatewayServer.start()
    defer { server.stop() }
    return try await body(server.port)
}
