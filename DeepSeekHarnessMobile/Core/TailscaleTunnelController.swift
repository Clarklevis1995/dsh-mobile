import Foundation
import DeepSeekHarnessShared

/// SwiftUI 展示类型；业务状态和账号调度由 KMP 的 TailscaleSessionManager 统一处理。
enum TunnelUiStage: Equatable {
    case disabled, starting, needsLogin, waitingRoutes, ready, failed
}

enum TunnelFailureKind: String {
    case interfaceSource = "interface-source"
    case nativeLoad = "native-load"
    case backendStart = "backend-start"
    case loginWait = "login-wait"
    case status = "status"
    case shutdown = "shutdown"
    case unknown = "unknown"
}

struct TailscaleTunnelUiState {
    var stage: TunnelUiStage = .disabled
    var authUrl: String?
    var peerCount: Int = 0
    var nodeAddress: String?
    var failureKind: TunnelFailureKind?
    /// 是否真的还在跟踪状态。转圈必须反映这件事，不能反映「处于过渡态」——
    /// 否则停止跟踪后界面仍然一直转，就是在骗用户。
    var isPolling = false

    var isTransient: Bool {
        stage == .needsLogin || stage == .starting || stage == .waitingRoutes
    }
}

struct TailscaleAccountAddition: Equatable {
    let account: TailscaleAccount
    var loginURL: URL?
    var confirmed = false
    var failed = false
    var stage: TunnelUiStage = .starting
    var isReauthentication = false
}

/// 当前节点负责业务转发；候选节点只处理新增账号登录，认证与路由就绪后再接管。
@MainActor
final class TailscaleTunnelController: ObservableObject {
    static let shared = TailscaleTunnelController()

    @Published private(set) var state = TailscaleTunnelUiState()
    @Published private(set) var accountAddition: TailscaleAccountAddition?

    private let settings: TailscaleTunnelSettings
    private let host: TailscaleIOSSessionHost
    private let sessions: TailscaleSessionFacade
    private var listener: TailscaleIOSSessionListener?

    init(settings: TailscaleTunnelSettings? = nil,
         makeClient: @escaping () -> any TsnetNodeServing = { TsnetNodeClient() },
         supportDirectory: URL? = nil) {
        let settings = settings ?? .shared
        self.settings = settings
        host = TailscaleIOSSessionHost(settings: settings, makeClient: makeClient,
            directory: supportDirectory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0])
        sessions = TailscaleSessionFacade(host: host)
        let listener = TailscaleIOSSessionListener { [weak self] in self?.publish() }
        self.listener = listener
        sessions.observe(listener: listener)
    }

    deinit { sessions.dispose() }

    func autostartIfNeeded() {
        guard settings.shouldAutoStart() else { return }
        start()
    }

    func setEnabled(_ enabled: Bool) {
        settings.selectEnabled(enabled)
        enabled ? start() : stop()
    }

    func start() {
        sessions.start(account: settings.selectedAccount.shared, authKey: settings.effectiveAuthKey(),
            defaultHostname: TailscaleTunnelSettings.defaultNodeName())
        publish()
    }

    func stop() { sessions.stop(); publish() }

    func beginAccountAddition() {
        var account = TailscaleAccount()
        account.nodeName = TailscalePolicy.shared.candidateNodeName(
            base: TailscaleTunnelSettings.defaultNodeName(), id: account.id.uuidString)
        sessions.beginAddition(account: account.shared)
        publish()
    }

    func confirmAccountAddition() -> URL? {
        let raw = sessions.confirmAddition()
        publish()
        return raw.flatMap(URL.init(string:))
    }

    func cancelAccountAddition() { sessions.cancelAddition(); publish() }

    func selectAccount(_ id: UUID) {
        guard settings.accounts.contains(where: { $0.id == id }) else { return }
        if sessions.currentAccountID == id.uuidString {
            sessions.promptCurrentLogin()
            publish()
            return
        }
        guard settings.selectAccount(id) else { return }
        settings.selectEnabled(true)
        start()
        sessions.promptCurrentLogin()
        publish()
    }

    func deleteAccount(_ id: UUID) {
        let selected = settings.selectedAccountID == id
        guard let account = settings.removeAccount(id) else { return }
        sessions.deleteAccount(account: account.shared, selected: selected)
        publish()
    }

    func refreshAfterReturningToApp() async {
        await withCheckedContinuation { continuation in
            sessions.refresh(result: TailscaleIOSRefreshResult { continuation.resume() })
        }
        publish()
    }

    func forwarder(for endpoint: String) async -> TsnetNodeClient.Forward? {
        guard let token = sessions.currentSessionToken,
              let node = sessions.currentNode as? TailscaleIOSNode,
              let url = URL(string: endpoint), NodeForwarding.canCarry(url),
              let (host, port) = NodeForwarding.hostPort(url) else { return nil }
        let json = await node.client.statusJson()
        guard sessions.acceptCurrentStatus(token: token, json: json) else { return nil }
        let forward = await node.client.forward(toHost: host, port: port, targetLabel: "\(host):\(port)")
        guard sessions.currentSessionToken == token else { return nil }
        return forward
    }

    private func publish() {
        state = TailscaleTunnelUiState(shared: sessions.state)
        accountAddition = sessions.addition.map {
            TailscaleAccountAddition(account: TailscaleAccount(shared: $0.account),
                loginURL: $0.loginURL.flatMap(URL.init(string:)), confirmed: $0.confirmed,
                failed: $0.status.stage == .failed, stage: TunnelUiStage(shared: $0.status.stage),
                isReauthentication: $0.isReauthentication)
        }
    }
}

private extension TunnelUiStage {
    init(shared: TailscaleStage) {
        switch shared {
        case .disabled: self = .disabled
        case .starting: self = .starting
        case .needsLogin: self = .needsLogin
        case .waitingRoutes: self = .waitingRoutes
        case .ready: self = .ready
        default: self = .failed
        }
    }
}

private extension TailscaleTunnelUiState {
    init(shared: TailscaleStatus) {
        self.init(stage: TunnelUiStage(shared: shared.stage), authUrl: shared.authUrl,
            peerCount: Int(shared.peerCount), nodeAddress: shared.nodeAddress,
            failureKind: shared.failureKind.flatMap(TunnelFailureKind.init(rawValue:)),
            isPolling: shared.isTransient)
    }
}

/// Swift 仅提供原生 SDK、文件目录和已保存账号的读写。
private final class TailscaleIOSSessionHost: NSObject, TailscaleSessionHost {
    private let settings: TailscaleTunnelSettings
    private let makeClient: () -> any TsnetNodeServing
    private let directory: URL

    @MainActor init(settings: TailscaleTunnelSettings, makeClient: @escaping () -> any TsnetNodeServing, directory: URL) {
        self.settings = settings; self.makeClient = makeClient; self.directory = directory
    }

    func makeNode(account: DeepSeekHarnessShared.TailscaleAccount) -> any TailscaleNativeNode {
        TailscaleIOSNode(client: makeClient(), directory: directory.appendingPathComponent(account.stateRelativePath, isDirectory: true))
    }

    func saveAccount(account: DeepSeekHarnessShared.TailscaleAccount, status: TailscaleStatus) {
        MainActor.assumeIsolated {
            settings.saveAuthenticatedAccount(TailscaleAccount(shared: account), loginName: status.loginName, tailnetName: status.tailnetName)
            settings.selectEnabled(true)
        }
    }

    func discardAccount(account: DeepSeekHarnessShared.TailscaleAccount) {
        let path = directory.appendingPathComponent(account.stateRelativePath)
        do {
            if FileManager.default.fileExists(atPath: path.path) { try FileManager.default.removeItem(at: path) }
        } catch { TunnelDiagnostics.tunnel(stage: "discard-account", ok: false) }
    }
}

private final class TailscaleIOSSessionListener: NSObject, TailscaleSessionListener {
    private let update: @MainActor () -> Void
    init(update: @escaping @MainActor () -> Void) { self.update = update }
    func changed(state: TailscaleStatus, addition: TailscaleAddition?) {
        MainActor.assumeIsolated { update() }
    }
}

private final class TailscaleIOSNode: NSObject, TailscaleNativeNode {
    let client: any TsnetNodeServing
    private let directory: URL
    init(client: any TsnetNodeServing, directory: URL) { self.client = client; self.directory = directory }
    func begin(authKey: String, hostname: String, result: any TailscaleStringResult) {
        Task { @MainActor in
            let json = await client.bringUp(hostname: hostname, authKey: authKey, stateDirectory: directory, timeoutSeconds: 12)
            result.complete(value: json)
        }
    }
    func status(result: any TailscaleStringResult) {
        Task { @MainActor in result.complete(value: await client.statusJson()) }
    }
    func close(result: any TailscaleCloseResult) {
        Task { @MainActor in result.complete(closed: await client.shutdown()) }
    }
}

private final class TailscaleIOSRefreshResult: NSObject, TailscaleCloseResult {
    private let completion: () -> Void
    init(completion: @escaping () -> Void) { self.completion = completion }
    func complete(closed: Bool) { completion() }
}
