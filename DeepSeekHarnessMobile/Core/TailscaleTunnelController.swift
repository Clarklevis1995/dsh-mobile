import Foundation
import Tsnetgo

/// 面向 UI 的阶段与失败种类。名字与安卓一致（`TailscaleTunnelController.kt`），
/// 两平台的规则要能并排读；失败种类是白名单，不把底层异常原样抛给用户。
enum TunnelUiStage {
    case disabled, starting, needsLogin, waitingRoutes, ready, failed
}

enum TunnelFailureKind: String {
    case interfaceSource = "interface-source"
    case nativeLoad = "native-load"
    case backendStart = "backend-start"
    case loginWait = "login-wait"
    case status = "status"
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

/// 内嵌 Tailscale 节点的生命周期所有者。
///
/// 两条硬约束决定了它的位置与形态（与安卓同源，见 `TailscaleTunnelController.kt` 的注释）：
/// 1) 它必须比一次连接尝试活得久：节点不能跟着连接的重建而死；
/// 2) 节点启动不能发生在连接的打开里：那层只在 open 返回后才挂尝试超时，
///    把几十秒的 bring-up 放进去会白烧一次重连退避。
/// 所以这里只被用户动作（设置页开关）与 app 启动时的自动恢复驱动，连接路径自己不启动它。
@MainActor
final class TailscaleTunnelController: ObservableObject {
    static let shared = TailscaleTunnelController()

    @Published private(set) var state = TailscaleTunnelUiState()

    private let client = TsnetNodeClient()
    private var drive: Task<Void, Never>?
    private var active = false
    /// 这次进程里节点是否已经起到过 Running。只用来决定「能不能现查状态」：
    /// 没起到过的时候现查会阻塞到引擎报状态（实测冷启动 7 秒），那不该发生在连接路径里。
    private var hasBroughtUp = false

    private static let pollMinSeconds = 5
    private static let pollMaxSeconds = 30
    /// bring-up 第一次只等 12 秒：没有 Auth Key 时它不可能自己成功，等长只是让登录链接出现得晚。
    private static let firstBringUpDeadline = 12

    // MARK: 生命周期

    /// app 启动时：只有「开着」且「这台设备登录过」才自动开。
    /// 没登录过的开启不该在下次启动时自动生效（这是用户明确要求的规则，安卓同一条）。
    func autostartIfNeeded() {
        let settings = TailscaleTunnelSettings.shared
        guard settings.shouldAutoStart() else {
            TunnelDiagnostics.tunnel(stage: "auto-start", ok: false, backend: "none")
            return
        }
        TunnelDiagnostics.tunnel(stage: "start", ok: true, backend: "auto-start")
        start()
    }

    func setEnabled(_ enabled: Bool) {
        enabled ? start() : stop()
    }

    func start() {
        active = true
        let settings = TailscaleTunnelSettings.shared
        let nodeName = settings.effectiveNodeName()
        let authKey = settings.effectiveAuthKey()
        state = TailscaleTunnelUiState(stage: .starting, isPolling: true)
        drive?.cancel()
        drive = Task { [weak self] in
            guard let self else { return }
            // srv.Up() 会阻塞到 Running 或超时。iOS 不需要像安卓那样推接口快照：
            // 那层是因为 Android 的 Go netmon 拿不到系统接口，而 iOS 的 netmon 直接看得到
            // （defaultRoute=en0），这一点在 tsnetgo 里已经确认过。
            var stage = self.apply(json: await self.client.bringUp(
                hostname: nodeName, authKey: authKey,
                timeoutSeconds: Self.firstBringUpDeadline), bringUp: true)
            var waitSeconds = Self.pollMinSeconds
            // 用户没关开关就一直跟踪：登录发生在浏览器里，完成时间不可预测。
            // 之前安卓上 40 次 × 5 秒的硬上限会在用户还没登录完时静默停止跟踪，
            // 而界面还在转圈——那是骗人。这里沿用修好之后的行为。
            while stage.isTransient && self.active {
                try? await Task.sleep(nanoseconds: UInt64(waitSeconds) * 1_000_000_000)
                if !self.active { return }
                waitSeconds = min(waitSeconds * 2, Self.pollMaxSeconds)
                stage = self.apply(json: await self.client.statusJson(), bringUp: false)
            }
        }
    }

    func stop() {
        active = false
        hasBroughtUp = false
        drive?.cancel()
        drive = nil
        Task { await client.shutdown() }
        state = TailscaleTunnelUiState(stage: .disabled)
    }

    // MARK: 连接路径要问的问题

    /// 为这个逻辑端点准备本地入口（127.0.0.1:随机端口 → 目标）。
    /// 拿不到就返回 nil，调用方走直连：那是原版本来就走的路，不是降级兜底。
    ///
    /// 只在「节点这次已经起过」时才去查状态。原因是实测：冷启动（挂起约 20 分钟后进程被回收）
    /// 时连接与节点同时开始，结果连接在打开里等了 7 秒——tsnet 的状态查询要等到引擎报出状态
    /// 才返回。这正是本文件开头第二条硬约束要防的：不确定的 bring-up 不该烧进一次连接尝试
    /// （如果节点进不了 Running，白烧的就是整轮尝试超时加一次退避）。
    /// 而节点到了 Running 之后，状态查询在同一秒返回（多次轮询实测），所以那种情况下照常现查——
    /// 不拿缓存当事实：判据必须是当前的后端状态。
    func forwarder(for endpoint: String) async -> TsnetNodeClient.Forward? {
        guard active, hasBroughtUp else { return nil }
        // 承载规则与端点映射只有一处定义：NodeForwarding（它有测试钉着）。
        guard let url = URL(string: endpoint), NodeForwarding.canCarry(url),
              let (host, port) = NodeForwarding.hostPort(url) else { return nil }
        let json = await client.statusJson()
        _ = apply(json: json, bringUp: false)
        // 只看「节点在跑」不够：真机实测，打开但没登录（NeedsLogin）时把连接送进节点，
        // 本来正常的局域网直连也会断掉。判据是后端状态 Running——即节点确实进了 tailnet、能承载包。
        // 已知残留窗口：Running 之后 peers 仍会从 0 涨上来（实测约 10 秒），那一段经节点可能连不上。
        guard json.contains("\"state\":\"Running\"") else { return nil }
        return await client.forward(toHost: host, port: port, targetLabel: "\(host):\(port)")
    }

    // MARK: 状态解释

    /// 把一次状态 JSON 变成 UI 状态；未到位的状态继续轮询。
    /// 实测事实：BackendState=Running 之后 peers 仍可能为 0（路由未就绪），首次连接会超时、
    /// 之后才成功——所以 Running + peers=0 是「正在建立连接」，不是错误。
    @discardableResult
    private func apply(json: String, bringUp: Bool) -> TailscaleTunnelUiState {
        let backend = group(#""state":"([A-Za-z]*)""#, in: json) ?? ""
        let peers = Int(group(#""peers":(\d+)"#, in: json) ?? "") ?? 0
        let authUrl = group(#""authURL":"(https:[^"]*)""#, in: json)
        let address = group(#""tailscaleIPs":\["([^"]+)""#, in: json)
        let error = group(#""error":"([^"]*)""#, in: json) ?? ""

        // 「这台设备登录过」要落到偏好里：只有登录过，下次启动才尊重那个开关。
        if backend == "Running" {
            hasBroughtUp = true
            TailscaleTunnelSettings.shared.markAuthenticated()
        }

        var next = TailscaleTunnelUiState(nodeAddress: address)
        switch (backend, peers, authUrl) {
        case ("Running", let count, _) where count > 0:
            next = TailscaleTunnelUiState(stage: .ready, peerCount: count, nodeAddress: address)
        case ("Running", _, _):
            next = TailscaleTunnelUiState(stage: .waitingRoutes, nodeAddress: address)
        case ("NeedsLogin", _, _), (_, _, .some):
            next = TailscaleTunnelUiState(stage: .needsLogin, authUrl: authUrl, nodeAddress: address)
        case ("Starting", _, _), ("NoState", _, _):
            next = TailscaleTunnelUiState(stage: .starting, authUrl: authUrl, nodeAddress: address)
        default:
            if error.contains("netlinkrib") || error.contains("interface") {
                next = TailscaleTunnelUiState(stage: .failed, nodeAddress: address, failureKind: .interfaceSource)
            } else if error.contains("native-load") {
                next = TailscaleTunnelUiState(stage: .failed, nodeAddress: address, failureKind: .nativeLoad)
            } else if bringUp && !error.isEmpty {
                next = TailscaleTunnelUiState(stage: .failed, nodeAddress: address,
                    failureKind: (error.contains("deadline") || error.contains("timeout")) ? .loginWait : .backendStart)
            } else {
                next = TailscaleTunnelUiState(stage: .failed, nodeAddress: address, failureKind: .unknown)
            }
        }
        next.isPolling = active && next.isTransient
        state = next
        return next
    }

    private func group(_ pattern: String, in text: String) -> String? {
        guard let expression = try? NSRegularExpression(pattern: pattern),
              let found = expression.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)),
              found.numberOfRanges > 1, let range = Range(found.range(at: 1), in: text) else { return nil }
        return String(text[range])
    }
}
