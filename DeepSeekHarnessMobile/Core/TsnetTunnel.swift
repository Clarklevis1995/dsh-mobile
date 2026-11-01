import Foundation
import Tsnetgo

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
final class TsnetNodeClient: @unchecked Sendable {
    /// 一次经节点的拨号入口：本地端口 + 它代表的目标。
    struct Forward {
        let localPort: Int
        let target: String
    }

    private let queue = DispatchQueue(label: "dsh.tsnetnode")
    private var node: TsnetgoNode?
    private var lastStatusJson = ""
    private var lastErrorValue = ""

    var lastError: String {
        queue.sync { lastErrorValue }
    }

    private static func stateDirectory() -> URL {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        let dir = support.appendingPathComponent("tsnet-state", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        // 节点状态含 tailnet 私钥：不该被备份带走，也不该在换设备时复用。
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var mutable = dir
        try? mutable.setResourceValues(values)
        return dir
    }

    /// 启动节点并等一次状态；返回状态 JSON，失败信息同时在返回串与 lastError 里。
    /// stage 只用于把「卡在哪一步」带出去：真机上没有可附加的调试器，卡在 native 里就是黑盒。
    func bringUp(hostname: String, authKey: String, timeoutSeconds: Int,
                 stage: @escaping (String) -> Void = { _ in }) async -> String {
        await withCheckedContinuation { (cont: CheckedContinuation<String, Never>) in
            queue.async {
                stage("swift-entered")
                if self.node == nil {
                    stage("swift-before-native-start")
                    var startError: NSError?
                    self.node = TsnetgoStart(hostname, Self.stateDirectory().path, &startError)
                    stage("swift-after-native-start")
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
                stage("swift-before-native-bringup")
                let json = current.bringUp(Int(timeoutSeconds))
                stage("swift-after-native-bringup")
                self.lastStatusJson = json
                let ok = json.contains("\"ok\":true")
                self.lastErrorValue = ok ? "" : "bring-up-failed"
                Self.log("bring-up", ok: ok, json: json)
                if ok {
                    // 连上之后接受别人广播的子网路由（等价 tailscale set --accept-routes）。
                    // 不做这一步就到不了网关广播的局域网段：安卓上先实测过，iOS 真机同一条必需
                    // （蜂窝下没有它就完全到不了）。
                    let routes = current.acceptRoutes()
                    let routesOk = routes.contains("\"ok\":true")
                    if !routesOk { self.lastErrorValue = "routes-not-accepted" }
                    Self.log("routes", ok: routesOk, json: routes)
                }
                cont.resume(returning: json)
            }
        }
    }

    func statusJson() async -> String {
        await withCheckedContinuation { (cont: CheckedContinuation<String, Never>) in
            queue.async {
                guard let current = self.node else { cont.resume(returning: self.lastStatusJson); return }
                let json = current.statusNow()
                self.lastStatusJson = json
                Self.log("status", ok: json.contains("\"ok\":true"), json: json)
                cont.resume(returning: json)
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

    func shutdown() async {
        await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
            queue.async {
                // Go 侧的 Close() 会在 10 秒内返回错误而不是无限吊住；绑定把 error 变成抛出，
                // 所以接住并留诊断——关闭失败不能让调用方崩。
                do { _ = try self.node?.close() } catch {
                    self.lastErrorValue = "close-failed: \(error.localizedDescription)"
                    Self.log("close", ok: false, json: "{}")
                }
                self.node = nil
                cont.resume()
            }
        }
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
        TunnelDiagnostics.tunnel(stage: stage, ok: ok, backend: backend, peers: peers,
                                authUrl: authUrl, extra: extra)
    }

    private static func firstMatch(_ pattern: String, in text: String) -> String? {
        guard let expression = try? NSRegularExpression(pattern: pattern),
              let found = expression.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)),
              found.numberOfRanges > 1, let range = Range(found.range(at: 1), in: text) else { return nil }
        return String(text[range])
    }
}
