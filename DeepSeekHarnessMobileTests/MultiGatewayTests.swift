import SwiftUI
import XCTest
@testable import DeepSeekHarnessMobile

@MainActor
private final class MigrationTestCredentials: GatewayCredentialStoring {
    func loadToken(for endpoint: URL) -> String? { nil }
    func saveToken(_ token: String, for endpoint: URL) {}
    func deleteToken(for endpoint: URL) {}
    func loadOrCreateDeviceID() -> String { "migration-test-device" }
}

private actor ColdLaunchProfileCredentials: GatewayCredentialStoring {
    let profileID: String
    private(set) var tokenReads = 0
    init(profileID: String) { self.profileID = profileID }
    func loadToken(for endpoint: URL) -> String? {
        tokenReads += 1
        return endpoint.absoluteString == "https://gateway-credential.invalid/\(profileID)" ? "saved-device-token" : nil
    }
    func saveToken(_ token: String, for endpoint: URL) {}
    func deleteToken(for endpoint: URL) {}
    func loadOrCreateDeviceID() async throws -> String {
        // 停在凭据准备阶段，验证启动连接意图而不打开真实网络连接。
        try await Task.sleep(for: .seconds(30))
        return "cold-launch-device"
    }
}

final class MultiGatewayTests: XCTestCase {
    private let gatewayID = "d56a1098-8519-43a1-9dce-fb99863bf5bb"

    @MainActor
    func testRestoredExternalGatewayAutoConnectsWithoutDependingOnRootViewTask() async throws {
        let suite = "external-cold-launch-\(UUID())"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let external = "wss://external.example/ws/mobile"
        let profile = GatewayProfile(gatewayId: gatewayID, gatewayName: "外网主机",
                                     endpoints: ["ws://192.168.1.94:3081/ws/mobile", external], preferredEndpoint: external)
        defaults.set(try JSONEncoder().encode([profile]), forKey: "gateway.profiles.v1")
        defaults.set(profile.id, forKey: "gateway.activeID")
        let credentials = ColdLaunchProfileCredentials(profileID: profile.id)
        let hosts = MultiGatewayStore(defaults: defaults, credentials: credentials)
        await hosts.waitUntilReady()
        defer { hosts.activeStore.deactivateGateway() }
        let deadline = ProcessInfo.processInfo.systemUptime + 1
        while !hosts.activeStore.gateway.recoveryStateForTesting.wantsConnection,
              ProcessInfo.processInfo.systemUptime < deadline { try await Task.sleep(for: .milliseconds(10)) }
        XCTAssertEqual(hosts.activeStore.endpoint, external)
        XCTAssertEqual(hosts.activeStore.gateway.state, .connecting)
        XCTAssertTrue(hosts.activeStore.gateway.recoveryStateForTesting.wantsConnection)
        let reads = await credentials.tokenReads
        XCTAssertGreaterThan(reads, 0)
    }

    @MainActor
    func testRestoredManuallyDisconnectedGatewayDoesNotAutoConnect() async throws {
        let suite = "manual-cold-launch-\(UUID())"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let profile = GatewayProfile(gatewayName: "手动断开的主机", endpoints: ["wss://external.example/ws/mobile"])
        defaults.set(try JSONEncoder().encode([profile]), forKey: "gateway.profiles.v1")
        UserDefaultsAppPreferences(userDefaults: defaults, gatewayID: profile.id).automaticallyConnect = false
        let credentials = ColdLaunchProfileCredentials(profileID: profile.id)
        let hosts = MultiGatewayStore(defaults: defaults, credentials: credentials)
        await hosts.waitUntilReady()
        defer { hosts.activeStore.deactivateGateway() }
        try await Task.sleep(for: .milliseconds(100))
        XCTAssertEqual(hosts.activeStore.gateway.state, .disconnected)
        XCTAssertFalse(hosts.activeStore.gateway.recoveryStateForTesting.wantsConnection)
        let reads = await credentials.tokenReads
        XCTAssertEqual(reads, 0)
    }

    func testIdentityCannotDowngradeOrChange() throws {
        try GatewayIdentity.validate(expected: nil, received: nil)
        try GatewayIdentity.validate(expected: gatewayID, received: gatewayID.uppercased())
        XCTAssertThrowsError(try GatewayIdentity.validate(expected: gatewayID, received: nil))
        XCTAssertThrowsError(try GatewayIdentity.validate(expected: gatewayID, received: "a56a1098-8519-43a1-9dce-fb99863bf5bb"))
        XCTAssertThrowsError(try GatewayIdentity.validate(expected: nil, received: "host-name"))
    }

    func testPairingDecodesNewFieldsAndRetainsLegacyCompatibility() throws {
        let payload = GatewayPairingPayload(version: 2, publicUrl: "wss://gateway.example/ws/mobile", pairingCode: "once", expiresAt: 4_102_444_800_000, gatewayId: gatewayID, gatewayName: "我的电脑", endpoints: ["ws://192.168.1.10:3081/ws/mobile"])
        let encoded = try JSONEncoder().encode(payload).base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
        let result = try PairingPayloadParser.parse(encoded)
        XCTAssertEqual(result.gatewayId, gatewayID)
        XCTAssertEqual(try GatewayIdentity.endpoints(result).count, 2)
    }

    func testAddressesRejectCredentialLeakAndUnboundedCandidates() throws {
        for endpoint in ["ws://10.bad.0.0.1/ws/mobile", "ws://example.com/ws/mobile", "wss://user:pass@example.com/ws/mobile", "wss://example.com/ws/mobile?a=b", "wss://0.0.0.0/ws/mobile"] {
            XCTAssertThrowsError(try GatewayIdentity.endpoint(endpoint))
        }
        let payload = GatewayPairingPayload(version: 2, publicUrl: "wss://gateway.example/ws/mobile", pairingCode: "once", expiresAt: 4_102_444_800_000, endpoints: (0..<16).map { "wss://host\($0).example/ws/mobile" })
        XCTAssertThrowsError(try GatewayIdentity.endpoints(payload))
    }

    func testPreferencesIsolateSameResourceIDs() throws {
        let defaults = UserDefaults(suiteName: "multi-test-\(UUID())")!
        let first = UserDefaultsAppPreferences(userDefaults: defaults, gatewayID: "A")
        let second = UserDefaultsAppPreferences(userDefaults: defaults, gatewayID: "B")
        first.selectedWorkspaceID = "same-id"
        first.endpoint = "wss://a.example/ws/mobile"
        XCTAssertNil(second.selectedWorkspaceID)
        XCTAssertNotEqual(first.endpoint, second.endpoint)
        second.selectedWorkspaceID = "other"
        XCTAssertEqual(first.selectedWorkspaceID, "same-id")
    }

    @MainActor
    func testLegacyMigrationIsIdempotentAndPreservesWorkspace() async throws {
        let defaults = UserDefaults(suiteName: "multi-migrate-\(UUID())")!
        defaults.set("wss://legacy.example/ws/mobile", forKey: "gateway.endpoint")
        defaults.set("workspace-a", forKey: "gateway.selectedWorkspaceId")
        let first = MultiGatewayStore(defaults: defaults, credentials: MigrationTestCredentials())
        await first.waitUntilReady()
        let second = MultiGatewayStore(defaults: defaults, credentials: MigrationTestCredentials())
        await second.waitUntilReady()
        XCTAssertEqual(first.profiles.count, 1)
        XCTAssertEqual(first.profiles, second.profiles)
        XCTAssertEqual(first.activeStore.selectedWorkspaceId, "workspace-a")
        XCTAssertEqual(first.activeStore.gatewayLocalID, first.activeProfile?.id)
    }

    @MainActor
    func testSelectingDisconnectedCurrentGatewayKeepsBusinessContainerAndHomeState() async throws {
        let suite = "same-gateway-\(UUID())"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let profile = GatewayProfile(gatewayName: "离线电脑", endpoints: ["wss://a.example/ws/mobile"])
        defaults.set(try JSONEncoder().encode([profile]), forKey: "gateway.profiles.v1")
        let hosts = MultiGatewayStore(defaults: defaults)
        await hosts.waitUntilReady()
        let current = hosts.activeStore
        current.selectedWorkspaceId = "workspace-kept"
        hosts.select(profile, connect: false)
        XCTAssertTrue(current === hosts.activeStore)
        hosts.select(profile)
        XCTAssertTrue(current === hosts.activeStore)
        XCTAssertEqual(current.selectedWorkspaceId, "workspace-kept")
        current.disconnect()
        current.deactivateGateway()
    }

    @MainActor
    func testSwitchReplacesBusinessContainerAndRejectsOldCallbacks() async throws {
        let defaults = UserDefaults(suiteName: "multi-switch-\(UUID())")!
        let first = GatewayProfile(gatewayName: "同名电脑", endpoints: ["wss://a.example/ws/mobile"])
        let second = GatewayProfile(gatewayName: "同名电脑", endpoints: ["wss://b.example/ws/mobile"])
        defaults.set(try JSONEncoder().encode([first, second]), forKey: "gateway.profiles.v1")
        let hosts = MultiGatewayStore(defaults: defaults)
        await hosts.waitUntilReady()
        let old = hosts.activeStore
        let delayed = old.gateway.onFrame
        old.selectedWorkspaceId = "same-id"
        hosts.select(second, connect: false)
        XCTAssertFalse(old === hosts.activeStore)
        XCTAssertNil(old.gateway.onFrame)
        XCTAssertNil(hosts.activeStore.selectedWorkspaceId)
        delayed?(GatewayFrame(kind: "pong"))
        old.selectedWorkspaceId = "late-a"
        XCTAssertNil(hosts.activeStore.selectedWorkspaceId)
        hosts.activeStore.selectedWorkspaceId = "workspace-b"
        hosts.select(first, connect: false)
        XCTAssertEqual(hosts.activeStore.selectedWorkspaceId, "late-a")
        XCTAssertNotEqual(hosts.activeStore.gatewayLocalID, second.id)
    }

    @MainActor
    func testRemovingCurrentHostClearsStateAndDoesNotRemigrateLegacy() async throws {
        let defaults = UserDefaults(suiteName: "multi-remove-\(UUID())")!
        defaults.set("wss://legacy.example/ws/mobile", forKey: "gateway.endpoint")
        let hosts = MultiGatewayStore(defaults: defaults, credentials: MigrationTestCredentials())
        await hosts.waitUntilReady()
        let profile = try XCTUnwrap(hosts.activeProfile)
        let old = hosts.activeStore
        old.selectedWorkspaceId = "old-workspace"
        hosts.remove(profile)
        XCTAssertTrue(hosts.profiles.isEmpty)
        XCTAssertNil(hosts.activeID)
        XCTAssertFalse(old === hosts.activeStore)
        XCTAssertNil(hosts.activeStore.selectedWorkspaceId)
        XCTAssertNil(old.gateway.onFrame)
        let restored = MultiGatewayStore(defaults: defaults)
        await restored.waitUntilReady()
        XCTAssertTrue(restored.profiles.isEmpty)
        XCTAssertNil(restored.activeStore.selectedWorkspaceId)
        hosts.select(profile, connect: false)
        XCTAssertNil(hosts.activeID)
    }

    @MainActor
    func testBatchRemovingHostsAlsoClearsActiveContainer() async throws {
        let defaults = UserDefaults(suiteName: "multi-batch-remove-\(UUID())")!
        let first = GatewayProfile(gatewayName: "A", endpoints: ["wss://a.example/ws/mobile"])
        let second = GatewayProfile(gatewayName: "B", endpoints: ["wss://b.example/ws/mobile"])
        let retained = GatewayProfile(gatewayName: "C", endpoints: ["wss://c.example/ws/mobile"])
        defaults.set(try JSONEncoder().encode([first, second, retained]), forKey: "gateway.profiles.v1")
        defaults.set(first.id, forKey: "gateway.activeID")
        let hosts = MultiGatewayStore(defaults: defaults)
        await hosts.waitUntilReady()
        hosts.activeStore.selectedWorkspaceId = "workspace-a"

        hosts.remove(ids: Set([first.id, second.id]))

        XCTAssertEqual(hosts.profiles, [retained])
        XCTAssertNil(hosts.activeID)
        XCTAssertNil(hosts.activeStore.selectedWorkspaceId)
    }

    func testPreferredEndpointCannotAddTrust() {
        let profile = GatewayProfile(gatewayName: "电脑", endpoints: ["wss://trusted.example/ws/mobile"], preferredEndpoint: "wss://evil.example/ws/mobile")
        XCTAssertEqual(profile.connectionEndpoints, profile.endpoints)
    }

    @MainActor
    func testSwitcherSheetRendersWithoutEnvironmentObject() async throws {
        let first = GatewayProfile(gatewayName: "电脑 A", endpoints: ["wss://a.example/ws/mobile"])
        let second = GatewayProfile(gatewayName: "电脑 B", endpoints: ["wss://b.example/ws/mobile"])
        for profiles in [[GatewayProfile](), [first, second]] {
            let suite = "switcher-sheet-\(UUID())"
            let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
            defer { defaults.removePersistentDomain(forName: suite) }
            defaults.set(try JSONEncoder().encode(profiles), forKey: "gateway.profiles.v1")
            defaults.set(second.id, forKey: "gateway.activeID")
            let hosts = MultiGatewayStore(defaults: defaults, credentials: MigrationTestCredentials())
            await hosts.waitUntilReady()
            defer { hosts.stopProbes(); hosts.activeStore.deactivateGateway() }

            // 独立承载弹窗，不注入环境对象，覆盖呈现边界丢失依赖的崩溃。
            let controller = UIHostingController(rootView:
                GatewaySwitcherSheet(hosts: hosts).environment(\.scenePhase, .background)
            )
            let size = controller.sizeThatFits(in: CGSize(width: 390, height: 640))

            XCTAssertTrue(size.width.isFinite && size.height.isFinite)
            XCTAssertGreaterThan(size.width, 0)
            XCTAssertGreaterThan(size.height, 0)
        }
    }
}
