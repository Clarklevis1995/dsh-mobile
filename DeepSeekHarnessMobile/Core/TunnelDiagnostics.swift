import Foundation

/// 隧道与连接路径的诊断输出：只有一处开关，语义与安卓的 `AndroidGatewayDiagnostics.enabled`
/// 相同（可调试构建才写；发布包不刷日志）。字段名两边一致，所以一份日志口径能并排读两个平台。
enum TunnelDiagnostics {
    static let enabled: Bool = {
        #if DEBUG
        return true
        #else
        return false
        #endif
    }()

    private static let logger = LocalLogger(subsystem: "ai.dsh.mobile.ios", category: "tsnet")

    /// 安卓那边是 `tsnet tunnel stage=… ok=… backend=… peers=… authUrl=…`；
    /// iOS 这边类别已经是 [tsnet]，所以正文不再重复那个前缀，字段一一对应。
    static func tunnel(stage: String, ok: Bool, backend: String? = nil, peers: Int = 0,
                      authUrl: Bool = false, extra: String = "") {
        guard enabled else { return }
        logger.info("tunnel stage=\(stage) ok=\(ok ? 1 : 0)"
            + " backend=\(backend ?? "") peers=\(min(max(peers, 0), 4096)) authUrl=\(authUrl ? 1 : 0)\(extra)")
    }

    static func transport(_ message: String) {
        guard enabled else { return }
        logger.info("transport \(message)")
    }
}
