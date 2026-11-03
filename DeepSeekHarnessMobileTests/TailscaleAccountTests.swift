import SwiftUI
import XCTest
@testable import DeepSeekHarnessMobile

private actor AccountTestNode: TsnetNodeServing {
    static let running = #"{"state":"Running","routesReady":true,"peers":1,"loginName":"user@example.com","tailnetName":"work.example","tailscaleIPs":["100.100.1.19"]}"#
    static let login = #"{"state":"NeedsLogin","authURL":"https://login.tailscale.com/a/new"}"#
    let localPort: Int
    init(localPort: Int = 12345) { self.localPort = localPort }
    private(set) var directories: [URL] = []
    private(set) var authKeys: [String] = []
    private(set) var events: [String] = []
    private(set) var statusWaiting = false
    private var response = AccountTestNode.login
    private var closeSucceeds = true
    private var holdFirstStart = false
    private var firstStart: CheckedContinuation<String, Never>?
    private var holdStatus = false
    private var pendingStatus: CheckedContinuation<String, Never>?

    func configure(response: String = AccountTestNode.login, holdFirstStart: Bool = false, closeSucceeds: Bool = true) {
        self.response = response
        self.holdFirstStart = holdFirstStart
        self.closeSucceeds = closeSucceeds
    }
    func setResponse(_ value: String) { response = value }
    func bringUp(hostname: String, authKey: String, stateDirectory: URL, timeoutSeconds: Int) async -> String {
        directories.append(stateDirectory)
        authKeys.append(authKey)
        events.append("start")
        if holdFirstStart && directories.count == 1 {
            return await withCheckedContinuation { firstStart = $0 }
        }
        return response
    }
    func releaseStart() { firstStart?.resume(returning: Self.running); firstStart = nil }
    func releaseStartWithLoginURL() { firstStart?.resume(returning: Self.login); firstStart = nil }
    func pauseStatus() { holdStatus = true }
    func releaseStatus() { pendingStatus?.resume(returning: Self.running); pendingStatus = nil; holdStatus = false }
    func statusJson() async -> String {
        if holdStatus {
            return await withCheckedContinuation { pendingStatus = $0; statusWaiting = true }
        }
        return response
    }
    func forward(toHost host: String, port: Int, targetLabel: String) async -> TsnetNodeClient.Forward? {
        events.append("forward")
        return .init(localPort: localPort, target: targetLabel)
    }
    func shutdown() async -> Bool { events.append("close"); return closeSucceeds }
}

@MainActor
final class TailscaleAccountTests: XCTestCase {
    private func withFixture(_ body: (TailscaleTunnelSettings, UserDefaults, URL) async throws -> Void) async throws {
        let name = "tailscale-accounts-test-\(UUID())"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: name))
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(name, isDirectory: true)
        defer {
            defaults.removePersistentDomain(forName: name)
            try? FileManager.default.removeItem(at: root)
        }
        try await body(TailscaleTunnelSettings(defaults: defaults), defaults, root)
    }

    private func waitUntil(timeout: TimeInterval = 3, _ condition: () async -> Bool, file: StaticString = #filePath, line: UInt = #line) async throws {
        let deadline = ProcessInfo.processInfo.systemUptime + timeout
        while !(await condition()) {
            guard ProcessInfo.processInfo.systemUptime < deadline else {
                XCTFail("等待账号状态超时", file: file, line: line)
                return
            }
            try await Task.sleep(for: .milliseconds(10))
        }
    }

    private func makeController(_ settings: TailscaleTunnelSettings, nodes: [AccountTestNode], root: URL) -> TailscaleTunnelController {
        var remaining = nodes
        return TailscaleTunnelController(settings: settings, makeClient: {
            guard !remaining.isEmpty else {
                XCTFail("创建了预期之外的节点")
                return AccountTestNode()
            }
            return remaining.removeFirst()
        }, supportDirectory: root)
    }

    private func saveAccount(_ settings: TailscaleTunnelSettings, name: String = "个人", legacy: Bool = false,
                             loginName: String = "personal@example.com", tailnetName: String = "home.example") -> TailscaleAccount {
        let account = TailscaleAccount(name: name, usesLegacyState: legacy)
        settings.saveAuthenticatedAccount(account, loginName: loginName, tailnetName: tailnetName)
        return settings.selectedAccount
    }

    func testFreshInstallationHasNoAccountRows() async throws {
        try await withFixture { settings, defaults, _ in
            XCTAssertTrue(settings.accounts.isEmpty)
            XCTAssertNil(settings.selectedAccountID)
            XCTAssertFalse(settings.shouldAutoStart())
            let restored = TailscaleTunnelSettings(defaults: defaults)
            XCTAssertTrue(restored.accounts.isEmpty)
        }
    }

    func testSelectingAccountNeedingLoginReopensExistingURLWithoutRestartingNode() async throws {
        try await withFixture { settings, _, root in
            let original = saveAccount(settings)
            let node = AccountTestNode()
            let controller = makeController(settings, nodes: [node], root: root)
            defer { controller.stop() }
            controller.setEnabled(true)
            try await waitUntil { controller.state.stage == .needsLogin }
            controller.selectAccount(original.id)
            XCTAssertEqual(controller.accountAddition?.loginURL?.absoluteString, "https://login.tailscale.com/a/new")
            XCTAssertEqual(controller.accountAddition?.account.id, original.id)
            XCTAssertEqual(controller.accountAddition?.isReauthentication, true)
            controller.cancelAccountAddition()
            XCTAssertNil(controller.accountAddition)
            let cancelledEvents = await node.events
            XCTAssertEqual(cancelledEvents, ["start"])
            controller.selectAccount(original.id)
            XCTAssertNotNil(controller.confirmAccountAddition())
            XCTAssertNil(controller.accountAddition)
            await node.setResponse(AccountTestNode.running)
            await controller.refreshAfterReturningToApp()
            XCTAssertEqual(controller.state.stage, .ready)
            XCTAssertEqual(settings.accounts.count, 1)
            XCTAssertEqual(settings.selectedAccountID, original.id)
            let events = await node.events
            XCTAssertEqual(events, ["start"])
        }
    }

    func testLegacyIdentityIsPreservedWithoutMigratingUnfinishedRows() async throws {
        try await withFixture { _, defaults, root in
            defaults.removeObject(forKey: "tunnel_accounts_v1")
            defaults.set(true, forKey: "tunnel_enabled")
            defaults.set(true, forKey: "tunnel_authenticated")
            defaults.set("my-iphone", forKey: "tunnel_node_name")
            let settings = TailscaleTunnelSettings(defaults: defaults)
            XCTAssertEqual(settings.accounts.count, 1)
            XCTAssertEqual(settings.selectedAccount.stateDirectory(in: root).lastPathComponent, "tsnet-state")
            XCTAssertEqual(settings.nodeName, "my-iphone")
            XCTAssertTrue(settings.shouldAutoStart())
            XCTAssertEqual(TailscaleTunnelSettings(defaults: defaults).accounts, settings.accounts)

            struct OldStore: Codable { var accounts: [TailscaleAccount]; var selectedID: UUID }
            let draft = TailscaleAccount(name: "待登录账号")
            let old = OldStore(accounts: settings.accounts + [draft], selectedID: draft.id)
            defaults.set(try JSONEncoder().encode(old), forKey: "tunnel_accounts_v1")
            let upgraded = TailscaleTunnelSettings(defaults: defaults)
            XCTAssertEqual(upgraded.accounts, settings.accounts)
            XCTAssertEqual(upgraded.selectedAccountID, settings.selectedAccountID)
        }
    }

    func testAdditionDoesNotPersistOrSelectPendingAccount() async throws {
        try await withFixture { settings, defaults, root in
            let original = saveAccount(settings)
            settings.selectAuthKey("must-not-be-reused")
            let node = AccountTestNode()
            let controller = makeController(settings, nodes: [node], root: root)
            defer { controller.stop() }
            controller.beginAccountAddition()
            XCTAssertEqual(settings.accounts, [original])
            XCTAssertEqual(settings.selectedAccountID, original.id)
            XCTAssertNil(controller.confirmAccountAddition())
            try await waitUntil { controller.accountAddition?.loginURL != nil }
            XCTAssertEqual(controller.accountAddition?.loginURL?.absoluteString, "https://login.tailscale.com/a/new")
            XCTAssertEqual(TailscaleTunnelSettings(defaults: defaults).accounts, [original])
            let keys = await node.authKeys
            XCTAssertEqual(keys, [""])
        }
    }

    func testConfirmedLoginIsDetectedOnReturnAndAutomaticallySaved() async throws {
        try await withFixture { settings, defaults, root in
            let node = AccountTestNode()
            let controller = makeController(settings, nodes: [node], root: root)
            defer { controller.stop() }
            controller.beginAccountAddition()
            try await waitUntil { controller.accountAddition?.loginURL != nil }
            let pendingID = try XCTUnwrap(controller.accountAddition?.account.id)
            XCTAssertNotNil(controller.confirmAccountAddition())
            XCTAssertTrue(settings.accounts.isEmpty)
            await node.setResponse(AccountTestNode.running)
            await controller.refreshAfterReturningToApp()
            XCTAssertNil(controller.accountAddition)
            XCTAssertEqual(controller.state.stage, .ready)
            XCTAssertEqual(settings.selectedAccountID, pendingID)
            XCTAssertEqual(settings.selectedAccount.loginName, "user@example.com")
            XCTAssertEqual(settings.selectedAccount.tailnetName, "work.example")
            XCTAssertTrue(settings.shouldAutoStart())
            XCTAssertEqual(TailscaleTunnelSettings(defaults: defaults).accounts, settings.accounts)
        }
    }

    func testPollingFinishesConfirmedLoginWithoutManualReconnect() async throws {
        try await withFixture { settings, _, root in
            let node = AccountTestNode()
            let controller = makeController(settings, nodes: [node], root: root)
            defer { controller.stop() }
            controller.beginAccountAddition()
            try await waitUntil { controller.accountAddition?.loginURL != nil }
            _ = controller.confirmAccountAddition()
            await node.setResponse(AccountTestNode.running)
            try await waitUntil(timeout: 8) {
                controller.accountAddition == nil && controller.state.stage == .ready
            }
            XCTAssertEqual(settings.accounts.count, 1)
            XCTAssertTrue(settings.shouldAutoStart())
        }
    }

    func testUnconfirmedSuccessDoesNotEnterSavedAccountList() async throws {
        try await withFixture { settings, _, root in
            let node = AccountTestNode()
            await node.configure(response: AccountTestNode.running)
            let controller = makeController(settings, nodes: [node], root: root)
            defer { controller.stop() }
            controller.beginAccountAddition()
            try await waitUntil { controller.accountAddition?.stage == .ready }
            XCTAssertEqual(controller.state.stage, .disabled)
            XCTAssertTrue(settings.accounts.isEmpty)
            XCTAssertNotNil(controller.accountAddition)
        }
    }

    func testAddingAndCancellingKeepCurrentNodeAndForwardersAlive() async throws {
        try await withFixture { settings, _, root in
            let original = saveAccount(settings, legacy: true)
            let active = AccountTestNode(localPort: 11111)
            await active.configure(response: AccountTestNode.running)
            let pending = AccountTestNode(localPort: 22222)
            await pending.configure(holdFirstStart: true)
            let controller = makeController(settings, nodes: [active, pending], root: root)
            defer { controller.stop() }
            controller.setEnabled(true)
            try await waitUntil { controller.state.stage == .ready }
            let endpoint = "ws://100.100.1.19:3081/ws/mobile"
            let before = await controller.forwarder(for: endpoint)
            XCTAssertEqual(before?.localPort, 11111)
            controller.beginAccountAddition()
            try await waitUntil { await pending.directories.count == 1 }
            let directory = try XCTUnwrap(controller.accountAddition?.account.stateDirectory(in: root))
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            try Data("temporary-state".utf8).write(to: directory.appendingPathComponent("tailscaled.state"))
            let duringStartup = await controller.forwarder(for: endpoint)
            XCTAssertEqual(duringStartup?.localPort, 11111)
            XCTAssertEqual(controller.state.stage, .ready)
            XCTAssertFalse(controller.state.isPolling)
            await pending.releaseStartWithLoginURL()
            try await waitUntil { controller.accountAddition?.loginURL != nil }
            _ = controller.confirmAccountAddition()
            let duringLogin = await controller.forwarder(for: endpoint)
            XCTAssertEqual(duringLogin?.localPort, 11111)
            controller.cancelAccountAddition()
            try await waitUntil { await pending.events.contains("close") }
            let after = await controller.forwarder(for: endpoint)
            XCTAssertEqual(after?.localPort, 11111)
            XCTAssertEqual(settings.selectedAccountID, original.id)
            XCTAssertEqual(settings.accounts.count, 1)
            XCTAssertEqual(controller.state.stage, .ready)
            XCTAssertFalse(FileManager.default.fileExists(atPath: directory.path))
            let activeEvents = await active.events
            XCTAssertEqual(activeEvents.filter { $0 == "start" }.count, 1)
            XCTAssertFalse(activeEvents.contains("close"))
        }
    }

    func testCandidatePromotesOnlyAfterLoginAndRoutesReady() async throws {
        try await withFixture { settings, _, root in
            let original = saveAccount(settings, legacy: true)
            let active = AccountTestNode(localPort: 11111)
            await active.configure(response: AccountTestNode.running
                .replacingOccurrences(of: "user@example.com", with: "personal@example.com")
                .replacingOccurrences(of: "work.example", with: "home.example"))
            let pending = AccountTestNode(localPort: 22222)
            let controller = makeController(settings, nodes: [active, pending], root: root)
            defer { controller.stop() }
            controller.setEnabled(true)
            try await waitUntil { controller.state.stage == .ready }
            controller.beginAccountAddition()
            try await waitUntil { controller.accountAddition?.loginURL != nil }
            let newID = try XCTUnwrap(controller.accountAddition?.account.id)
            _ = controller.confirmAccountAddition()
            await pending.setResponse(AccountTestNode.running.replacingOccurrences(of: "\"routesReady\":true", with: "\"routesReady\":false"))
            await controller.refreshAfterReturningToApp()
            XCTAssertEqual(settings.selectedAccountID, original.id)
            XCTAssertEqual(controller.accountAddition?.stage, .waitingRoutes)
            XCTAssertEqual(controller.state.stage, .ready)
            let before = await controller.forwarder(for: "ws://100.100.1.19:3081/ws/mobile")
            XCTAssertEqual(before?.localPort, 11111)
            let activeBefore = await active.events
            XCTAssertFalse(activeBefore.contains("close"))
            await pending.setResponse(AccountTestNode.running)
            await controller.refreshAfterReturningToApp()
            XCTAssertNil(controller.accountAddition)
            XCTAssertEqual(settings.selectedAccountID, newID)
            XCTAssertEqual(settings.accounts.count, 2)
            XCTAssertTrue(settings.shouldAutoStart())
            try await waitUntil { await active.events.contains("close") }
            let after = await controller.forwarder(for: "ws://100.100.1.19:3081/ws/mobile")
            XCTAssertEqual(after?.localPort, 22222)
            let pendingEvents = await pending.events
            XCTAssertEqual(pendingEvents.filter { $0 == "start" }.count, 1)
            XCTAssertFalse(pendingEvents.contains("close"))
            XCTAssertTrue(settings.selectedAccount.nodeName.hasSuffix(newID.uuidString.prefix(8).lowercased()))
        }
    }

    func testCancelBeforeLinkArrivesIgnoresLateAuthentication() async throws {
        try await withFixture { settings, defaults, root in
            let node = AccountTestNode()
            await node.configure(holdFirstStart: true)
            let controller = makeController(settings, nodes: [node], root: root)
            controller.beginAccountAddition()
            try await waitUntil { await node.directories.count == 1 }
            controller.cancelAccountAddition()
            await node.releaseStart()
            try await waitUntil { await node.events.contains("close") }
            XCTAssertEqual(controller.state.stage, .disabled)
            XCTAssertTrue(settings.accounts.isEmpty)
            XCTAssertTrue(TailscaleTunnelSettings(defaults: defaults).accounts.isEmpty)
        }
    }

    func testCancelAfterOpeningBrowserIgnoresLateStatus() async throws {
        try await withFixture { settings, _, root in
            let node = AccountTestNode()
            let controller = makeController(settings, nodes: [node], root: root)
            controller.beginAccountAddition()
            try await waitUntil { controller.accountAddition?.loginURL != nil }
            _ = controller.confirmAccountAddition()
            await node.pauseStatus()
            let refresh = Task { await controller.refreshAfterReturningToApp() }
            try await waitUntil { await node.statusWaiting }
            controller.cancelAccountAddition()
            await node.releaseStatus()
            await refresh.value
            XCTAssertTrue(settings.accounts.isEmpty)
            XCTAssertNil(controller.accountAddition)
            XCTAssertEqual(controller.state.stage, .disabled)
        }
    }

    func testReloggingSameAccountKeepsOneRowAndNewNodeAfterRestart() async throws {
        try await withFixture { settings, defaults, root in
            let original = saveAccount(settings, legacy: true,
                loginName: "user@example.com", tailnetName: "work.example")
            let active = AccountTestNode(localPort: 11111)
            await active.configure(response: AccountTestNode.running)
            let pending = AccountTestNode(localPort: 22222)
            let controller = makeController(settings, nodes: [active, pending], root: root)
            defer { controller.stop() }
            controller.setEnabled(true)
            try await waitUntil { controller.state.stage == .ready }
            controller.beginAccountAddition()
            try await waitUntil { controller.accountAddition?.loginURL != nil }
            let newAccount = try XCTUnwrap(controller.accountAddition?.account)
            XCTAssertEqual(settings.selectedAccountID, original.id)
            XCTAssertEqual(settings.accounts.count, 1)
            _ = controller.confirmAccountAddition()
            await pending.setResponse(AccountTestNode.running)
            await controller.refreshAfterReturningToApp()
            XCTAssertNil(controller.accountAddition)
            XCTAssertEqual(settings.accounts.count, 1)
            XCTAssertEqual(settings.selectedAccountID, newAccount.id)
            XCTAssertEqual(settings.selectedAccount.name, original.name)
            XCTAssertFalse(settings.selectedAccount.usesLegacyState)
            let forward = await controller.forwarder(for: "ws://100.100.1.19:3081/ws/mobile")
            XCTAssertEqual(forward?.localPort, 22222)
            try await waitUntil { await active.events.contains("close") }
            let restored = TailscaleTunnelSettings(defaults: defaults)
            XCTAssertEqual(restored.accounts, settings.accounts)
            controller.stop()
            try await waitUntil { await pending.events.contains("close") }
            let reopened = AccountTestNode()
            await reopened.configure(response: AccountTestNode.running)
            let restarted = makeController(restored, nodes: [reopened], root: root)
            defer { restarted.stop() }
            restarted.autostartIfNeeded()
            try await waitUntil { restarted.state.stage == .ready }
            let directories = await reopened.directories
            XCTAssertEqual(directories, [newAccount.stateDirectory(in: root)])
            XCTAssertEqual(restored.accounts.count, 1)
        }
    }

    func testExistingDuplicateRowsKeepSelectedIdentityAndRemark() async throws {
        try await withFixture { _, defaults, _ in
            struct Store: Encodable { let accounts: [TailscaleAccount]; let selectedID: UUID }
            let original = TailscaleAccount(name: "个人账号", loginName: "user@example.com",
                tailnetName: "work.example", authenticated: true, usesLegacyState: true)
            let current = TailscaleAccount(loginName: original.loginName,
                tailnetName: original.tailnetName, nodeName: "new-node", authenticated: true)
            defaults.set(try JSONEncoder().encode(Store(accounts: [original, current], selectedID: current.id)),
                forKey: "tunnel_accounts_v1")
            let restored = TailscaleTunnelSettings(defaults: defaults)
            XCTAssertEqual(restored.accounts.count, 1)
            XCTAssertEqual(restored.selectedAccountID, current.id)
            XCTAssertEqual(restored.selectedAccount.nodeName, current.nodeName)
            XCTAssertEqual(restored.selectedAccount.name, original.name)
            XCTAssertFalse(restored.selectedAccount.usesLegacyState)
            XCTAssertEqual(TailscaleTunnelSettings(defaults: defaults).accounts, restored.accounts)
        }
    }

    func testDeletingLastAccountStopsTunnelAndRemovesSavedCredentials() async throws {
        try await withFixture { settings, defaults, root in
            let original = saveAccount(settings, legacy: true)
            let directory = original.stateDirectory(in: root)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            try Data("test-state".utf8).write(to: directory.appendingPathComponent("state"))
            let active = AccountTestNode()
            await active.configure(response: AccountTestNode.running)
            let controller = makeController(settings, nodes: [active], root: root)
            defer { controller.stop() }
            controller.setEnabled(true)
            try await waitUntil { controller.state.stage == .ready }
            settings.selectAuthKey("temporary-key")
            controller.deleteAccount(original.id)
            XCTAssertTrue(settings.accounts.isEmpty)
            XCTAssertNil(settings.selectedAccountID)
            XCTAssertFalse(settings.enabled)
            XCTAssertEqual(settings.authKey, "")
            XCTAssertEqual(controller.state.stage, .disabled)
            try await waitUntil { !FileManager.default.fileExists(atPath: directory.path) }
            let restored = TailscaleTunnelSettings(defaults: defaults)
            XCTAssertTrue(restored.accounts.isEmpty)
            XCTAssertFalse(restored.shouldAutoStart())
            XCTAssertFalse(restored.selectedAccount.usesLegacyState)
            XCTAssertNotEqual(restored.selectedAccount.id, original.id)
            XCTAssertEqual(restored.selectedAccount.id, settings.selectedAccount.id)
            XCTAssertNotEqual(restored.selectedAccount.stateDirectory(in: root), directory)
            let forward = await controller.forwarder(for: "ws://100.100.1.19:3081/ws/mobile")
            XCTAssertNil(forward)
        }
    }

    func testDeletingOtherAccountKeepsCurrentTunnelAndPendingLogin() async throws {
        try await withFixture { settings, _, root in
            let original = saveAccount(settings)
            let other = saveAccount(settings, name: "工作", loginName: "work@example.com")
            settings.selectAccount(original.id)
            let directory = other.stateDirectory(in: root)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let active = AccountTestNode(localPort: 11111)
            await active.configure(response: AccountTestNode.running)
            let candidate = AccountTestNode()
            let controller = makeController(settings, nodes: [active, candidate], root: root)
            defer { controller.stop() }
            controller.setEnabled(true)
            try await waitUntil { controller.state.stage == .ready }
            controller.beginAccountAddition()
            try await waitUntil { controller.accountAddition?.loginURL != nil }
            let pendingID = controller.accountAddition?.account.id
            controller.deleteAccount(other.id)
            try await waitUntil { !FileManager.default.fileExists(atPath: directory.path) }
            XCTAssertEqual(settings.accounts.count, 1)
            XCTAssertEqual(settings.selectedAccountID, original.id)
            XCTAssertTrue(settings.enabled)
            XCTAssertEqual(controller.accountAddition?.account.id, pendingID)
            XCTAssertEqual(controller.state.stage, .ready)
            let forward = await controller.forwarder(for: "ws://100.100.1.19:3081/ws/mobile")
            XCTAssertEqual(forward?.localPort, 11111)
            let currentEvents = await active.events
            let candidateEvents = await candidate.events
            XCTAssertFalse(currentEvents.contains("close"))
            XCTAssertFalse(candidateEvents.contains("close"))
        }
    }

    func testDeletingCurrentAccountCancelsCandidateAndIgnoresLateStatus() async throws {
        try await withFixture { settings, _, root in
            let original = saveAccount(settings)
            let active = AccountTestNode()
            await active.configure(response: AccountTestNode.running)
            let candidate = AccountTestNode()
            let controller = makeController(settings, nodes: [active, candidate], root: root)
            defer { controller.stop() }
            controller.setEnabled(true)
            try await waitUntil { controller.state.stage == .ready }
            controller.beginAccountAddition()
            try await waitUntil { controller.accountAddition?.loginURL != nil }
            await active.pauseStatus()
            let refresh = Task { await controller.refreshAfterReturningToApp() }
            try await waitUntil { await active.statusWaiting }
            controller.deleteAccount(original.id)
            await active.releaseStatus()
            await refresh.value
            XCTAssertTrue(settings.accounts.isEmpty)
            XCTAssertNil(controller.accountAddition)
            XCTAssertEqual(controller.state.stage, .disabled)
            try await waitUntil { await candidate.events.contains("close") }
        }
    }

    func testSwitchRestoresSavedAccountDirectoriesAndClearsAuthKey() async throws {
        try await withFixture { settings, _, root in
            let first = saveAccount(settings, legacy: true)
            let second = saveAccount(settings, name: "工作", loginName: "work@example.com")
            settings.selectAuthKey("temporary-key")
            let node = AccountTestNode()
            await node.configure(response: AccountTestNode.running
                .replacingOccurrences(of: "user@example.com", with: "work@example.com")
                .replacingOccurrences(of: "work.example", with: "home.example"))
            let next = AccountTestNode()
            await next.configure(response: AccountTestNode.running
                .replacingOccurrences(of: "user@example.com", with: "personal@example.com")
                .replacingOccurrences(of: "work.example", with: "home.example"))
            let controller = makeController(settings, nodes: [node, next], root: root)
            defer { controller.stop() }
            controller.setEnabled(true)
            try await waitUntil { controller.state.stage == .ready }
            controller.selectAccount(first.id)
            try await waitUntil { await next.directories.count == 1 && controller.state.stage == .ready }
            let previousPaths = await node.directories
            let nextPaths = await next.directories
            XCTAssertEqual(previousPaths, [second.stateDirectory(in: root)])
            XCTAssertEqual(nextPaths, [first.stateDirectory(in: root)])
            XCTAssertEqual(settings.authKey, "")
            XCTAssertEqual(settings.accounts.count, 2)
        }
    }

    func testCandidateFailureAndCleanupFailureDoNotAffectCurrentNode() async throws {
        try await withFixture { settings, _, root in
            let original = saveAccount(settings)
            let active = AccountTestNode(localPort: 11111)
            await active.configure(response: AccountTestNode.running)
            let pending = AccountTestNode()
            await pending.configure(response: #"{"error":"timeout"}"#, closeSucceeds: false)
            let controller = makeController(settings, nodes: [active, pending], root: root)
            defer { controller.stop() }
            controller.setEnabled(true)
            try await waitUntil { controller.state.stage == .ready }
            controller.beginAccountAddition()
            try await waitUntil { controller.accountAddition?.failed == true }
            XCTAssertEqual(controller.state.stage, .ready)
            XCTAssertEqual(settings.selectedAccountID, original.id)
            controller.cancelAccountAddition()
            try await waitUntil { await pending.events.contains("close") }
            let forward = await controller.forwarder(for: "ws://100.100.1.19:3081/ws/mobile")
            XCTAssertEqual(forward?.localPort, 11111)
            let events = await active.events
            XCTAssertFalse(events.contains("close"))
        }
    }

    func testCancelAndAddAgainDoNotWaitForAbandonedLogin() async throws {
        try await withFixture { settings, _, root in
            let original = saveAccount(settings)
            let active = AccountTestNode()
            await active.configure(response: AccountTestNode.running)
            let abandoned = AccountTestNode()
            await abandoned.configure(holdFirstStart: true)
            let next = AccountTestNode()
            let controller = makeController(settings, nodes: [active, abandoned, next], root: root)
            defer { controller.stop() }
            controller.setEnabled(true)
            try await waitUntil { controller.state.stage == .ready }
            controller.beginAccountAddition()
            try await waitUntil { await abandoned.directories.count == 1 }
            let firstID = controller.accountAddition?.account.id
            controller.cancelAccountAddition()
            controller.beginAccountAddition()
            try await waitUntil { controller.accountAddition?.loginURL != nil }
            let nextID = controller.accountAddition?.account.id
            XCTAssertNotEqual(firstID, nextID)
            await abandoned.releaseStart()
            try await waitUntil { await abandoned.events.contains("close") }
            XCTAssertEqual(controller.accountAddition?.account.id, nextID)
            XCTAssertEqual(settings.selectedAccountID, original.id)
            XCTAssertEqual(settings.accounts.count, 1)
            XCTAssertEqual(controller.state.stage, .ready)
        }
    }

    func testCurrentStatusCanRecoverDuringCandidateLogin() async throws {
        try await withFixture { settings, _, root in
            let original = saveAccount(settings)
            let active = AccountTestNode(localPort: 11111)
            await active.configure(response: AccountTestNode.running)
            let pending = AccountTestNode()
            let controller = makeController(settings, nodes: [active, pending], root: root)
            defer { controller.stop() }
            controller.setEnabled(true)
            try await waitUntil { controller.state.stage == .ready }
            controller.beginAccountAddition()
            try await waitUntil { controller.accountAddition?.loginURL != nil }
            await active.setResponse(#"{"error":"temporary status failure"}"#)
            let unavailable = await controller.forwarder(for: "ws://100.100.1.19:3081/ws/mobile")
            XCTAssertNil(unavailable)
            await active.setResponse(AccountTestNode.running)
            let recovered = await controller.forwarder(for: "ws://100.100.1.19:3081/ws/mobile")
            XCTAssertEqual(recovered?.localPort, 11111)
            XCTAssertEqual(controller.state.stage, .ready)
            XCTAssertEqual(settings.selectedAccountID, original.id)
            XCTAssertNotNil(controller.accountAddition)
            let events = await active.events
            XCTAssertFalse(events.contains("close"))
        }
    }

    func testFailedDirectoryCleanupCannotBeBypassedByRetrying() async throws {
        try await withFixture { settings, _, root in
            let original = saveAccount(settings, legacy: true)
            let second = saveAccount(settings, name: "工作", loginName: "work@example.com")
            settings.selectAccount(original.id)
            let active = AccountTestNode()
            await active.configure(response: AccountTestNode.running, closeSucceeds: false)
            let neverStarted = AccountTestNode()
            let retry = AccountTestNode()
            let secondRetry = AccountTestNode()
            let controller = makeController(settings, nodes: [active, neverStarted, retry, secondRetry], root: root)
            defer { controller.stop() }
            controller.setEnabled(true)
            try await waitUntil { controller.state.stage == .ready }
            controller.selectAccount(second.id)
            try await waitUntil { controller.state.stage == .failed }
            controller.selectAccount(original.id)
            try await waitUntil { controller.state.stage == .failed }
            controller.start()
            try await waitUntil { controller.state.stage == .failed }
            let firstRetryStarts = await retry.directories.count
            let secondRetryStarts = await secondRetry.directories.count
            XCTAssertEqual(firstRetryStarts, 0)
            XCTAssertEqual(secondRetryStarts, 0)
            XCTAssertEqual(controller.state.failureKind, .shutdown)
        }
    }

    func testLoginDialogUsesTruncatedLabelAndBlueCopyText() throws {
        let coordinator = TailscaleAccountLoginAlert.Coordinator()
        coordinator.addition = TailscaleAccountAddition(account: TailscaleAccount())
        let preparing = coordinator.makeAlert()
        XCTAssertFalse(preparing.confirmButton.isEnabled)
        let url = try XCTUnwrap(URL(string: "https://login.tailscale.com/a/AbC123"))
        coordinator.addition?.loginURL = url
        let ready = coordinator.makeAlert()
        XCTAssertEqual(ready.loginURLLabel.text, url.absoluteString)
        XCTAssertEqual(ready.loginURLLabel.numberOfLines, 1)
        XCTAssertEqual(ready.loginURLLabel.lineBreakMode, .byTruncatingMiddle)
        XCTAssertNil(ready.copyButton.currentImage)
        XCTAssertEqual(ready.copyButton.titleColor(for: .normal), UIColor.systemBlue)
        XCTAssertTrue(ready.confirmButton.isEnabled)
        var copiedURL: String?
        coordinator.writeClipboard = { copiedURL = $0 }
        ready.copyButton.sendActions(for: .touchUpInside)
        XCTAssertEqual(copiedURL, url.absoluteString)
        XCTAssertEqual(ready.loginURLLabel.text, url.absoluteString)
    }

    func testSettingsRendersOnlySavedAccountsWithSmallerDescriptions() async throws {
        try await withFixture { settings, _, root in
            let first = saveAccount(settings)
            let second = saveAccount(settings, name: "工作", loginName: "work@example.com")
            settings.renameAccount(first.id, name: "personal@example.com")
            settings.selectAccount(second.id)
            let node = AccountTestNode()
            await node.configure(holdFirstStart: true)
            let controller = makeController(settings, nodes: [node], root: root)
            let view = NavigationStack {
                TailscaleTunnelSettingsView(settings: settings, tunnel: controller)
            }.modifier(AppFontScope()).environment(\.locale, Locale(identifier: "zh-Hans"))
            let host = UIHostingController(rootView: view)
            let frame = CGRect(x: 0, y: 0, width: 390, height: 844)
            let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
            let window = scene.map { UIWindow(windowScene: $0) } ?? UIWindow(frame: frame)
            window.frame = frame
            window.overrideUserInterfaceStyle = .light
            window.rootViewController = host
            host.view.frame = window.bounds
            window.makeKeyAndVisible()
            window.layoutIfNeeded()
            host.view.setNeedsLayout()
            host.view.layoutIfNeeded()
            defer { window.isHidden = true }
            try await Task.sleep(for: .milliseconds(200))
            host.view.layoutIfNeeded()
            let image = UIGraphicsImageRenderer(bounds: host.view.bounds).image { context in
                host.view.layer.render(in: context.cgContext)
            }
            XCTAssertEqual(image.size.width, 390)
            let attachment = XCTAttachment(image: image)
            attachment.name = "Tailscale 已登录账号列表"
            attachment.lifetime = .keepAlways
            add(attachment)

            // 校验弹窗在链接迟到时仍然更新，而不只是更新控制器字段。
            func findAlert(in controller: UIViewController) -> TailscaleLoginDialogController? {
                if let alert = controller.presentedViewController as? TailscaleLoginDialogController { return alert }
                return controller.children.lazy.compactMap { findAlert(in: $0) }.first
            }
            controller.beginAccountAddition()
            defer { controller.cancelAccountAddition() }
            // 先让等待状态的弹窗完整呈现，再模拟日志里的链接迟到，验证原弹窗实时更新。
            try await waitUntil { findAlert(in: host) != nil }
            let alert = try XCTUnwrap(findAlert(in: host))
            try await Task.sleep(for: .milliseconds(400))
            XCTAssertFalse(alert.confirmButton.isEnabled)
            XCTAssertNil(alert.loginURLLabel.text)
            await node.setResponse(AccountTestNode.login)
            await node.releaseStartWithLoginURL()
            try await waitUntil { controller.accountAddition?.loginURL != nil && alert.confirmButton.isEnabled }
            XCTAssertTrue(findAlert(in: host) === alert)
            XCTAssertEqual(alert.loginURLLabel.text, "https://login.tailscale.com/a/new")
            try await Task.sleep(for: .milliseconds(300))
            alert.view.setNeedsLayout()
            alert.view.layoutIfNeeded()
            XCTAssertGreaterThan(alert.view.bounds.width, 0)
            let alertImage = UIGraphicsImageRenderer(bounds: alert.view.bounds).image { _ in
                XCTAssertTrue(alert.view.drawHierarchy(in: alert.view.bounds, afterScreenUpdates: true))
            }
            let alertAttachment = XCTAttachment(image: alertImage)
            alertAttachment.name = "Tailscale 添加账号登录弹窗"
            alertAttachment.lifetime = .keepAlways
            add(alertAttachment)
        }
    }
}
