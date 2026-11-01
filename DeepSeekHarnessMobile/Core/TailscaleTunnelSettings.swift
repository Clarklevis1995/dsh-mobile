import Foundation
import UIKit

/// 内嵌 Tailscale 隧道的本地偏好。
///
/// 键名与安卓完全一致（独立 suite `tailscale_tunnel` 下的 tunnel_enabled /
/// tunnel_authenticated / tunnel_node_name / tunnel_auth_key）：两个平台的规则要一模一样，
/// 同名字不是形式主义——真机排障时一份日志口径能说清两边（今天就是这样对照出来的）。
///
/// 不放进网关作用域：隧道要在「连接之前」就可读可写，而主机相关的偏好只在已配对主机上存在。
@MainActor
final class TailscaleTunnelSettings: ObservableObject {
    static let shared = TailscaleTunnelSettings(
        defaults: UserDefaults(suiteName: "tailscale_tunnel") ?? .standard)

    @Published private(set) var enabled: Bool
    @Published private(set) var authenticated: Bool
    @Published private(set) var nodeName: String
    @Published private(set) var authKey: String

    private let defaults: UserDefaults

    private static let keyEnabled = "tunnel_enabled"
    private static let keyAuthenticated = "tunnel_authenticated"
    private static let keyNodeName = "tunnel_node_name"
    private static let keyAuthKey = "tunnel_auth_key"

    private init(defaults: UserDefaults) {
        self.defaults = defaults
        self.enabled = defaults.bool(forKey: Self.keyEnabled)
        self.authenticated = defaults.bool(forKey: Self.keyAuthenticated)
        self.nodeName = defaults.string(forKey: Self.keyNodeName) ?? ""
        self.authKey = defaults.string(forKey: Self.keyAuthKey) ?? ""
    }

    func selectEnabled(_ value: Bool) {
        if value == enabled { return }
        enabled = value
        defaults.set(value, forKey: Self.keyEnabled)
    }

    /// 「这台设备登录过」这个事实：只有在节点真的进过 Running（含用 Auth Key 直接认证成功）
    /// 之后才为真。它决定开关值值不值得被记住：没登录过的「开启」不该在下次启动时自动生效。
    func markAuthenticated() {
        if authenticated { return }
        authenticated = true
        defaults.set(true, forKey: Self.keyAuthenticated)
    }

    func shouldAutoStart() -> Bool { enabled && authenticated }

    /// 返回 false 表示不合法：调用方保留原值并提示，不把脏值写进偏好。
    @discardableResult
    func selectNodeName(_ raw: String) -> Bool {
        guard let normalized = Self.normalizeNodeName(raw) else { return false }
        if normalized == nodeName { return true }
        nodeName = normalized
        defaults.set(normalized, forKey: Self.keyNodeName)
        return true
    }

    func selectAuthKey(_ raw: String) {
        let value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if value == authKey { return }
        authKey = value
        defaults.set(value, forKey: Self.keyAuthKey)
    }

    /// 留空 = 走交互式登录（浏览器授权）；非空 = 用 Auth Key 直接注册。
    func effectiveAuthKey() -> String { authKey }

    func effectiveNodeName() -> String {
        nodeName.isEmpty ? Self.defaultNodeName() : nodeName
    }

    // MARK: 命名规则（与安卓同一套）

    private static let nodeNamePattern = "^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$"

    /// Tailscale 主机名规则：小写字母/数字/连字符，首尾必须是字母或数字，最长 63。
    static func normalizeNodeName(_ raw: String) -> String? {
        var candidate = raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        candidate = candidate.replacingOccurrences(of: "[\\s_.]+", with: "-", options: .regularExpression)
        candidate = candidate.replacingOccurrences(of: "-{2,}", with: "-", options: .regularExpression)
        candidate = candidate.trimmingCharacters(in: CharacterSet(charactersIn: "-"))
        return candidate.range(of: nodeNamePattern, options: .regularExpression) == candidate.startIndex..<candidate.endIndex
            ? candidate : nil
    }

    /// 默认名取设备名。中文设备名会被过滤掉，只剩 ASCII 部分；什么都不剩就用固定的 dsh-ios。
    static func defaultNodeName() -> String {
        let slug = UIDevice.current.name.lowercased()
            .replacingOccurrences(of: "[^a-z0-9]+", with: "-", options: .regularExpression)
            .trimmingCharacters(in: CharacterSet(charactersIn: "-"))
        let trimmed = String(slug.prefix(40)).trimmingCharacters(in: CharacterSet(charactersIn: "-"))
        return normalizeNodeName("dsh-\(trimmed)") ?? "dsh-ios"
    }
}
