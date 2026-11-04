import Foundation
import UIKit
import DeepSeekHarnessShared

/// 每条记录保存一个独立的节点身份；同一登录账号在不同 tailnet 中也可以分别保存。
struct TailscaleAccount: Codable, Identifiable, Equatable, Sendable {
    var id: UUID = UUID()
    var name: String = ""
    var loginName: String?
    var tailnetName: String?
    var nodeName: String = ""
    var authenticated: Bool = false
    var usesLegacyState: Bool = false

    var displayName: String {
        let name = shared.displayName
        return name.isEmpty ? String(localized: "已登录账号") : name
    }

    func stateDirectory(in support: URL) -> URL {
        support.appendingPathComponent(shared.stateRelativePath, isDirectory: true)
    }
}

/// 只持久化登录成功的账号；添加中的节点由控制器临时持有。
@MainActor
final class TailscaleTunnelSettings: ObservableObject {
    static let shared = TailscaleTunnelSettings(
        defaults: UserDefaults(suiteName: "tailscale_tunnel") ?? .standard)

    private let catalog: TailscaleAccountCatalog

    @Published private(set) var enabled: Bool
    @Published private(set) var accounts: [TailscaleAccount]
    @Published private(set) var selectedAccountID: UUID?
    @Published private(set) var authKey: String

    var selectedAccount: TailscaleAccount {
        TailscaleAccount(shared: catalog.selectedAccount)
    }
    var authenticated: Bool { selectedAccount.authenticated }
    var nodeName: String { selectedAccount.nodeName }

    private let defaults: UserDefaults
    private static let keyEnabled = "tunnel_enabled"
    private static let keyAuthKey = "tunnel_auth_key"
    private static let keyAccounts = "tunnel_accounts_v1"

    init(defaults: UserDefaults) {
        self.defaults = defaults
        enabled = defaults.bool(forKey: Self.keyEnabled)
        authKey = defaults.string(forKey: Self.keyAuthKey) ?? ""
        let legacy = TailscaleAccount(
            nodeName: defaults.string(forKey: "tunnel_node_name") ?? "",
            authenticated: defaults.bool(forKey: "tunnel_authenticated"), usesLegacyState: true)
        catalog = TailscaleAccountCatalog(
            savedJSON: defaults.data(forKey: Self.keyAccounts).flatMap { String(data: $0, encoding: .utf8) },
            legacyAccount: legacy.shared)
        accounts = catalog.accounts.map { TailscaleAccount(shared: $0) }
        selectedAccountID = catalog.selectedID.flatMap(UUID.init(uuidString:))
        persistAccounts()
    }

    func selectEnabled(_ value: Bool) {
        enabled = value
        defaults.set(value, forKey: Self.keyEnabled)
    }

    @discardableResult
    func selectAccount(_ id: UUID) -> Bool {
        let changed = selectedAccountID != id
        guard catalog.select(id: id.uuidString) else { return false }
        if changed { selectAuthKey("") }
        persistAccounts()
        return true
    }

    func renameAccount(_ id: UUID, name: String) {
        catalog.rename(id: id.uuidString, name: name)
        persistAccounts()
    }

    func removeAccount(_ id: UUID) -> TailscaleAccount? {
        let selected = selectedAccountID == id
        guard let removed = catalog.remove(id: id.uuidString, replacementID: UUID().uuidString) else { return nil }
        if selected {
            selectEnabled(false)
            selectAuthKey("")
        }
        if removed.usesLegacyState || catalog.accounts.isEmpty {
            defaults.removeObject(forKey: "tunnel_authenticated")
            defaults.removeObject(forKey: "tunnel_node_name")
        }
        persistAccounts()
        return TailscaleAccount(shared: removed)
    }

    func saveAuthenticatedAccount(_ account: TailscaleAccount, loginName: String?, tailnetName: String?) {
        catalog.saveAuthenticated(account: account.shared, loginName: loginName, tailnetName: tailnetName)
        selectAuthKey("")
        persistAccounts()
    }

    func markAuthenticated(accountID: UUID, loginName: String? = nil, tailnetName: String? = nil) {
        guard selectedAccount.id == accountID else { return }
        catalog.markAuthenticated(id: accountID.uuidString, loginName: loginName, tailnetName: tailnetName)
        selectAuthKey("")
        persistAccounts()
    }

    func shouldAutoStart() -> Bool { enabled && authenticated }

    @discardableResult
    func selectNodeName(_ raw: String) -> Bool {
        guard catalog.setNodeName(raw: raw) else { return false }
        defaults.set(catalog.selectedAccount.nodeName, forKey: "tunnel_node_name")
        persistAccounts()
        return true
    }

    func selectAuthKey(_ raw: String) {
        let value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard value != authKey else { return }
        authKey = value
        if value.isEmpty { defaults.removeObject(forKey: Self.keyAuthKey) }
        else { defaults.set(value, forKey: Self.keyAuthKey) }
    }

    func effectiveAuthKey() -> String { authKey }
    func effectiveNodeName() -> String { nodeName.isEmpty ? Self.defaultNodeName() : nodeName }

    private func persistAccounts() {
        accounts = catalog.accounts.map { TailscaleAccount(shared: $0) }
        selectedAccountID = catalog.selectedID.flatMap(UUID.init(uuidString:))
        defaults.set(catalog.snapshotJSON().data(using: .utf8), forKey: Self.keyAccounts)
    }

    static func normalizeNodeName(_ raw: String) -> String? {
        TailscalePolicy.shared.normalizeNodeName(raw: raw)
    }

    static func defaultNodeName() -> String {
        TailscalePolicy.shared.defaultNodeName(deviceName: UIDevice.current.name, platform: "ios")
    }
}

extension TailscaleAccount {
    var shared: DeepSeekHarnessShared.TailscaleAccount {
        DeepSeekHarnessShared.TailscaleAccount(id: id.uuidString, name: name, loginName: loginName,
            tailnetName: tailnetName, nodeName: nodeName, authenticated: authenticated, usesLegacyState: usesLegacyState)
    }

    init(shared: DeepSeekHarnessShared.TailscaleAccount) {
        self.init(id: UUID(uuidString: shared.id)!, name: shared.name, loginName: shared.loginName,
            tailnetName: shared.tailnetName, nodeName: shared.nodeName,
            authenticated: shared.authenticated, usesLegacyState: shared.usesLegacyState)
    }
}
