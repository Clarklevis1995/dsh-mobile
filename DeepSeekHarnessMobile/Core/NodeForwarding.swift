import Foundation

/// 内嵌隧道里「逻辑端点」与「连接端点」唯一的分离点。
///
/// 不变式（这里就是被测的东西）：
/// 1. 只有 host 与 port 被换成本地转发器的端口；scheme、path、query 原样保留。
/// 2. 这条函数不碰凭据、档案标识、日志里的 endpoint——它们继续用逻辑端点。
///    错了不会崩，只会连到错的主机或取到错的凭据，那种错最难查，所以钉在这里。
enum NodeForwarding {
    /// 哪些端点可以由节点承载。只允许明文：wss 走不了裸 TCP 转发器——TLS 的主机名会对不上
    /// （连的是 127.0.0.1）。要做就得在隧道内做 TLS，iOS 16 没有现成 API，这是明确没做的能力。
    static func canCarry(_ url: URL) -> Bool {
        let scheme = (url.scheme ?? "").lowercased()
        return (scheme == "ws" || scheme == "http") && hostPort(url) != nil
    }

    /// 转发目标。端口缺失时按协议补明文默认值（80）；这里不猜 wss，因为那条根本不被承载。
    static func hostPort(_ url: URL) -> (host: String, port: Int)? {
        guard let host = url.host?.trimmingCharacters(in: .whitespacesAndNewlines), !host.isEmpty else { return nil }
        if let port = url.port { return (host, port) }
        let scheme = (url.scheme ?? "").lowercased()
        guard scheme == "ws" || scheme == "http" else { return nil }
        return (host, 80)
    }

    /// 把逻辑端点映射成连接端点：只换 host 与端口，其余照搬（包括空路径——不补 "/"，
    /// 补了就等于改了用户填的 URL）。
    static func connectURL(for logical: URL, localPort: Int) -> URL? {
        guard localPort > 0 else { return nil }
        var components = URLComponents()
        components.scheme = logical.scheme
        components.host = "127.0.0.1"
        components.port = localPort
        components.path = logical.path
        components.query = logical.query
        return components.url
    }
}
