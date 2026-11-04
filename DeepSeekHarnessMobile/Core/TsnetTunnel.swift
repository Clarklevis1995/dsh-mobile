import Foundation
import Tsnetgo

protocol TsnetNodeServing: Sendable {
    func bringUp(hostname: String, authKey: String, stateDirectory: URL, timeoutSeconds: Int) async -> String
    func statusJson() async -> String
    func forward(toHost host: String, port: Int, targetLabel: String) async -> TsnetNodeClient.Forward?
    func shutdown() async -> Bool
    func isHealthy() async -> Bool
    func prepareAfterResume() async -> Bool
}

extension TsnetNodeServing {
    func isHealthy() async -> Bool { true }
    func prepareAfterResume() async -> Bool { true }
}

/// 进程内的 Tailscale 节点：Go/tsnet 经 gomobile 绑定，跑在 app 进程里。
/// 不建 TUN、不申请系统 VPN 槽位；app 只连 127.0.0.1 上的本地入口。
///
/// 边界刻意保持窄：跨语言只传字符串与端口号，字节流全部留在 Go 侧——回调跨语言会带来
/// 线程与生命周期问题（Swift 侧要持有对象、Go 侧要能取消），而这条路径上没有东西需要回调。
///
/// 与安卓不同的一点：安卓用 startProxy（OkHttp 的 WebSocket 支持 HTTP 代理，一句
/// `.proxy(...)` 就够）。iOS 16.x 没有给 URLSession 配代理的 API（proxyConfigurations 是 17+），
/// 所以 iOS 走 forwardTo：127.0.0.1 上一个随机端口原样转发到目标，URLSessionWebSocketTask 连它，
/// 握手、帧、关闭码都由系统栈完成（实测第一帧就是插件的 paired）。代价记两条：
/// wss 走不了这条（TLS 的主机名对不上），以及 app 必须把「逻辑端点」和「连接端点」分开——
/// 凭据、档案、日志一律用逻辑端点，只有 socket 连的是本地端口。
final class TsnetNodeClient: TsnetNodeServing, @unchecked Sendable {
    /// 一次经节点的拨号入口：本地端口 + 它代表的目标。
    struct Forward {
        let localPort: Int
        let target: String
    }

    private let queue = DispatchQueue(label: "dsh.tsnetnode")
    private var node: TsnetgoNode?
    private var lastStatusJson = ""
    private var lastErrorValue = ""
    private var routesAccepted = false

    var lastError: String {
        queue.sync { lastErrorValue }
    }

    private static func prepareStateDirectory(_ dir: URL) throws {
        // 公共日志回退目录不随候选账号删除，也不应进入设备备份。
        let logs = dir.deletingLastPathComponent().appendingPathComponent("tsnet-logs", isDirectory: true)
        for directory in [dir, logs] {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            var mutable = directory
            try mutable.setResourceValues(values)
        }
    }

    /// 启动节点并等一次状态；返回状态 JSON，失败信息同时在返回串与 lastError 里。
    func bringUp(hostname: String, authKey: String, stateDirectory: URL, timeoutSeconds: Int) async -> String {
        await withCheckedContinuation { (cont: CheckedContinuation<String, Never>) in
            queue.async {
                if self.node == nil {
                    do { try Self.prepareStateDirectory(stateDirectory) } catch {
                        self.lastErrorValue = "state-directory-failed"
                        cont.resume(returning: "{\"ok\":false,\"error\":\"state-directory-failed\"}")
                        return
                    }
                    var startError: NSError?
                    self.node = TsnetgoStart(hostname, stateDirectory.path, &startError)
                    if self.node == nil {
                        let message = startError?.localizedDescription ?? "unknown"
                        self.lastErrorValue = "start-failed: \(message)"
                        Self.log("start", ok: false, json: "{}")
                        cont.resume(returning: "{\"ok\":false,\"stage\":\"start\",\"error\":\"\(message)\"}")
                        return
                    }
                }
                guard let current = self.node else {
                    cont.resume(returning: self.lastStatusJson)
                    return
                }
                // 只有 Debug 构建把节点内部日志转出去：真机排障需要知道它自己怎么说
                // （路由有没有装、EditPrefs 有没有被拒），发布包不该刷日志。
                current.setVerbose(Self.verboseLoggingAllowed)
                current.setAuthKey(authKey.trimmingCharacters(in: .whitespacesAndNewlines))
                // 交互式登录只等链接；浏览器授权完成由控制器随后持续检测。
                let raw = authKey.isEmpty
                    ? current.beginLogin(Int(timeoutSeconds))
                    : current.bringUp(Int(timeoutSeconds))
                let json = self.prepareRoutes(in: raw, current: current)
                self.lastStatusJson = json
                let ok = json.contains("\"ok\":true")
                self.lastErrorValue = ok ? "" : "bring-up-failed"
                Self.log("bring-up", ok: ok, json: json)
                cont.resume(returning: json)
            }
        }
    }

    func statusJson() async -> String {
        await withCheckedContinuation { (cont: CheckedContinuation<String, Never>) in
            queue.async {
                guard let current = self.node else { cont.resume(returning: self.lastStatusJson); return }
                let json = self.prepareRoutes(in: current.statusNow(), current: current)
                self.lastStatusJson = json
                Self.log("status", ok: json.contains("\"ok\":true"), json: json)
                cont.resume(returning: json)
            }
        }
    }

    /// 重试时只检查底层损坏标记，不能清理其他网关仍在使用的转发器。
    func isHealthy() async -> Bool {
        await withCheckedContinuation { continuation in
            queue.async {
                let healthy = self.node.map { !$0.needsRecovery() } ?? false
                Self.log("health-check", ok: healthy, json: "{}")
                continuation.resume(returning: healthy)
            }
        }
    }

    func prepareAfterResume() async -> Bool {
        await withCheckedContinuation { continuation in
            queue.async {
                guard let current = self.node else { continuation.resume(returning: false); return }
                let healthy = !current.needsRecovery()
                current.resetLocalListeners()
                Self.log("resume-check", ok: healthy, json: "{}")
                continuation.resume(returning: healthy)
            }
        }
    }

    /// 在 127.0.0.1 上起（或复用）一个到 host:port 的裸 TCP 转发器，返回本地端口。
    /// 同一目标复用同一个监听器，所以反复连接不会攒出一堆端口。
    func forward(toHost host: String, port: Int, targetLabel: String) async -> Forward? {
        await withCheckedContinuation { (cont: CheckedContinuation<Forward?, Never>) in
            queue.async {
                guard let current = self.node else { cont.resume(returning: nil); return }
                var ret0 = 0
                // 绑定把 Go 的 error 映射成 Swift 的 throws，返回值本身不带信息：成没成看有没有抛，
                // 端口在 ret0 里。
                do {
                    try current.forward(to: host, port: Int(port), ret0_: &ret0)
                } catch {
                    self.lastErrorValue = "forward-failed: \(error.localizedDescription)"
                    Self.log("forward", ok: false, json: "{}")
                    cont.resume(returning: nil)
                    return
                }
                guard ret0 > 0 else {
                    self.lastErrorValue = "forward-failed: no local port"
                    Self.log("forward", ok: false, json: "{}")
                    cont.resume(returning: nil)
                    return
                }
                Self.log("forward", ok: true, json: "{}", extra: " target=\(targetLabel) local=\(ret0)")
                cont.resume(returning: Forward(localPort: ret0, target: targetLabel))
            }
        }
    }

    func logsJson() async -> String {
        await withCheckedContinuation { (cont: CheckedContinuation<String, Never>) in
            queue.async { cont.resume(returning: self.node?.logs() ?? "{}") }
        }
    }

    func forwardsJson() async -> String {
        await withCheckedContinuation { (cont: CheckedContinuation<String, Never>) in
            queue.async { cont.resume(returning: self.node?.forwardsJSON() ?? "[]") }
        }
    }

    func shutdown() async -> Bool {
        await withCheckedContinuation { (cont: CheckedContinuation<Bool, Never>) in
            queue.async {
                do { _ = try self.node?.close() } catch {
                    self.lastErrorValue = "close-failed: \(error.localizedDescription)"
                    Self.log("close", ok: false, json: "{}")
                    // 关闭超时可能仍在刷写旧目录，不允许启动另一个身份。
                    cont.resume(returning: false)
                    return
                }
                self.node = nil
                self.lastStatusJson = ""
                self.lastErrorValue = ""
                self.routesAccepted = false
                cont.resume(returning: true)
            }
        }
    }

    /// 把路由配置结果带给控制器，候选节点不能只凭 Running 就抢占当前连接。
    private func prepareRoutes(in json: String, current: TsnetgoNode) -> String {
        guard let data = json.data(using: .utf8),
              var payload = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              payload["state"] as? String == "Running" else { return json }
        acceptRoutesIfNeeded(current)
        payload["routesReady"] = routesAccepted
        guard let result = try? JSONSerialization.data(withJSONObject: payload),
              let text = String(data: result, encoding: .utf8) else { return json }
        return text
    }

    private func acceptRoutesIfNeeded(_ current: TsnetgoNode) {
        guard !routesAccepted else { return }
        let routes = current.acceptRoutes()
        routesAccepted = routes.contains("\"ok\":true")
        if !routesAccepted { lastErrorValue = "routes-not-accepted" }
        Self.log("routes", ok: routesAccepted, json: routes)
    }

    private static var verboseLoggingAllowed: Bool {
        #if DEBUG
        return true
        #else
        return false
        #endif
    }

    /// 只记非敏感字段：状态 JSON 里的 tailnet 地址与节点密钥不进日志。字段名与安卓对齐，
    /// 并且走同一个开关（TunnelDiagnostics.enabled）。
    private static func log(_ stage: String, ok: Bool, json: String, extra: String = "") {
        let peers = Int(firstMatch(#""peers":(\d+)"#, in: json) ?? "") ?? 0
        let backend = firstMatch(#""state":"([A-Za-z]*)""#, in: json)
        let authUrl = json.contains("\"authURL\":\"http")
        let sdkStage = firstMatch(#""stage":"([A-Za-z-]*)""#, in: json) ?? ""
        let timedOut = json.contains("context deadline exceeded") || json.contains("timeout")
        TunnelDiagnostics.tunnel(stage: stage, ok: ok, backend: backend, peers: peers,
                                authUrl: authUrl, extra: " sdkStage=\(sdkStage) timeout=\(timedOut ? 1 : 0)" + extra)
    }

    private static func firstMatch(_ pattern: String, in text: String) -> String? {
        guard let expression = try? NSRegularExpression(pattern: pattern),
              let found = expression.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)),
              found.numberOfRanges > 1, let range = Range(found.range(at: 1), in: text) else { return nil }
        return String(text[range])
    }
}
