import XCTest
@testable import DeepSeekHarnessMobile

final class MultiGatewayTests: XCTestCase {
    private let gatewayID = "d56a1098-8519-43a1-9dce-fb99863bf5bb"

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
    func testLegacyMigrationIsIdempotentAndPreservesWorkspace() throws {
        let defaults = UserDefaults(suiteName: "multi-migrate-\(UUID())")!
        defaults.set("wss://legacy.example/ws/mobile", forKey: "gateway.endpoint")
        defaults.set("workspace-a", forKey: "gateway.selectedWorkspaceId")
        let first = MultiGatewayStore(defaults: defaults)
        let second = MultiGatewayStore(defaults: defaults)
        XCTAssertEqual(first.profiles.count, 1)
        XCTAssertEqual(first.profiles, second.profiles)
        XCTAssertEqual(first.activeStore.selectedWorkspaceId, "workspace-a")
        XCTAssertEqual(first.activeStore.gatewayLocalID, first.activeProfile?.id)
    }

    @MainActor
    func testSwitchReplacesBusinessContainerAndRejectsOldCallbacks() throws {
        let defaults = UserDefaults(suiteName: "multi-switch-\(UUID())")!
        let first = GatewayProfile(gatewayName: "同名电脑", endpoints: ["wss://a.example/ws/mobile"])
        let second = GatewayProfile(gatewayName: "同名电脑", endpoints: ["wss://b.example/ws/mobile"])
        defaults.set(try JSONEncoder().encode([first, second]), forKey: "gateway.profiles.v1")
        let hosts = MultiGatewayStore(defaults: defaults)
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
    func testRemovingCurrentHostClearsStateAndDoesNotRemigrateLegacy() throws {
        let defaults = UserDefaults(suiteName: "multi-remove-\(UUID())")!
        defaults.set("wss://legacy.example/ws/mobile", forKey: "gateway.endpoint")
        let hosts = MultiGatewayStore(defaults: defaults)
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
        XCTAssertTrue(restored.profiles.isEmpty)
        XCTAssertNil(restored.activeStore.selectedWorkspaceId)
        hosts.select(profile, connect: false)
        XCTAssertNil(hosts.activeID)
    }

    @MainActor
    func testBatchRemovingHostsAlsoClearsActiveContainer() throws {
        let defaults = UserDefaults(suiteName: "multi-batch-remove-\(UUID())")!
        let first = GatewayProfile(gatewayName: "A", endpoints: ["wss://a.example/ws/mobile"])
        let second = GatewayProfile(gatewayName: "B", endpoints: ["wss://b.example/ws/mobile"])
        let retained = GatewayProfile(gatewayName: "C", endpoints: ["wss://c.example/ws/mobile"])
        defaults.set(try JSONEncoder().encode([first, second, retained]), forKey: "gateway.profiles.v1")
        defaults.set(first.id, forKey: "gateway.activeID")
        let hosts = MultiGatewayStore(defaults: defaults)
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
}
