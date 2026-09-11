import SwiftUI
import Combine

/// 每次切换创建全新的业务容器。资料 ID 是持久化、凭证和缓存的共同命名空间。
@MainActor
final class MultiGatewayStore: ObservableObject {
    @Published private(set) var profiles: [GatewayProfile] = []
    @Published private(set) var activeStore: AppStore
    @Published private(set) var activeID: String?
    @Published private(set) var pairingStore: AppStore?
    @Published private(set) var onlineIDs: Set<String> = []
    @Published var error: String?
    private let defaults: UserDefaults
    private var pendingProfile: GatewayProfile?
    private var observation: AnyCancellable?
    private var pairingObservation: AnyCancellable?
    private var probes: [GatewayClient] = []
    private static let profilesKey = "gateway.profiles.v1"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        activeStore = AppStore(preferences: UserDefaultsAppPreferences(userDefaults: defaults, gatewayID: "unpaired"), gatewayLocalID: "unpaired")
        do {
            if let data = defaults.data(forKey: Self.profilesKey) {
                profiles = try JSONDecoder().decode([GatewayProfile].self, from: data)
                guard Set(profiles.map(\.id)).count == profiles.count,
                      profiles.allSatisfy({ GatewayIdentity.isValid($0.id) && !$0.endpoints.isEmpty && $0.endpoints.count <= 16 }) else {
                    throw GatewayProfileError.address
                }
            } else {
                let legacy = UserDefaultsAppPreferences(userDefaults: defaults)
                if defaults.string(forKey: "gateway.endpoint") != nil {
                    let profile = GatewayProfile(gatewayName: URL(string: legacy.endpoint)?.host ?? "原有主机", endpoints: [legacy.endpoint])
                    let scoped = UserDefaultsAppPreferences(userDefaults: defaults, gatewayID: profile.id)
                    scoped.endpoint = legacy.endpoint
                    scoped.saveSessions(legacy.loadSessions())
                    scoped.selectedWorkspaceID = legacy.selectedWorkspaceID
                    let client = GatewayClient()
                    client.credentialID = profile.id
                    if let url = URL(string: legacy.endpoint) { try client.migrateCredential(from: url) }
                    try migrateLegacyFiles(to: profile.id)
                    profiles = [profile]
                    persist()
                }
            }
            if let profile = profiles.first(where: { $0.id == defaults.string(forKey: "gateway.activeID") }) ?? profiles.first {
                activate(profile, connect: false)
            } else {
                installPairingHandler(on: activeStore)
            }
        } catch { self.error = "读取或迁移主机资料失败：\(error.localizedDescription)" }
    }

    var activeProfile: GatewayProfile? { profiles.first { $0.id == activeID } }

    func select(_ profile: GatewayProfile, connect: Bool = true) {
        guard profiles.contains(where: { $0.id == profile.id }) else { return }
        cancelPairing()
        if activeID == profile.id, activeStore.gateway.state.isConnected { return }
        activate(profile, connect: connect)
    }

    private func makeStore(_ profile: GatewayProfile) -> AppStore {
        let preferences = UserDefaultsAppPreferences(userDefaults: defaults, gatewayID: profile.id)
        preferences.endpoint = profile.connectionEndpoints.first ?? profile.endpoints[0]
        let store = AppStore(preferences: preferences, gatewayLocalID: profile.id)
        store.gatewayDisplayName = profile.displayName
        store.gateway.credentialID = profile.id
        store.gateway.expectedGatewayID = profile.gatewayId
        store.gateway.trustedEndpoints = profile.endpoints
        installPairingHandler(on: store)
        store.gateway.onIdentity = { [weak self] frame, _ in
            guard let self else { return }
            if let id = frame.gatewayId,
               self.profiles.contains(where: { $0.id != profile.id && $0.gatewayId?.lowercased() == id.lowercased() }) {
                throw GatewayProfileError.conflict
            }
        }
        let receive = store.gateway.onFrame
        store.gateway.onFrame = { [weak self, weak store] frame in
            guard let self, let store else { return }
            receive?(frame)
            if frame.kind == "paired" || frame.kind == "hello" {
                self.acceptIdentity(frame, store: store)
            }
        }
        return store
    }

    private func installPairingHandler(on store: AppStore) {
        store.pairingHandler = { [weak self] raw in try self?.pair(raw) }
    }

    private func activate(_ profile: GatewayProfile, connect: Bool) {
        let style = activeStore.interfaceStyle
        activeStore.deactivateGateway()
        activeStore = makeStore(profile)
        activeStore.interfaceStyle = style
        activeID = profile.id
        defaults.set(profile.id, forKey: "gateway.activeID")
        observeActive()
        if connect { activeStore.connect() }
    }

    private func observeActive() {
        observation = activeStore.gateway.$state.sink { [weak self] state in
            guard let self, let id = self.activeID else { return }
            if state.isConnected { self.onlineIDs.insert(id) } else { self.onlineIDs.remove(id) }
        }
    }

    func pair(_ raw: String) throws {
        let payload = try PairingPayloadParser.parse(raw)
        let endpoints = try GatewayIdentity.endpoints(payload)
        let existing = profiles.first {
            if let id = payload.gatewayId { return $0.gatewayId?.lowercased() == id.lowercased() }
            return $0.gatewayId == nil && $0.endpoints.contains(endpoints[0])
        }
        var profile = existing ?? GatewayProfile(
            gatewayId: payload.gatewayId?.lowercased(),
            gatewayName: payload.gatewayName ?? URL(string: endpoints[0])?.host ?? "新主机",
            endpoints: endpoints,
            deviceKind: GatewayIdentity.isLocal(URL(string: endpoints[0])?.host ?? "") ? "desktopcomputer" : "server.rack"
        )
        // 重新扫码是用户对本次地址集合的明确认可；首选地址优先。
        profile.endpoints = endpoints
        profile.preferredEndpoint = endpoints[0]
        if let name = payload.gatewayName, !name.isEmpty { profile.gatewayName = name }
        cancelPairing()
        let store = makeStore(profile)
        pendingProfile = profile
        pairingStore = store
        pairingObservation = store.gateway.$state.sink { [weak self] state in
            if case .failed(let detail) = state {
                Task { @MainActor [weak self] in self?.error = detail }
            }
        }
        var normalized = payload
        normalized.publicUrl = endpoints[0]
        store.gateway.connectForPairing(normalized)
    }

    private func acceptIdentity(_ frame: GatewayFrame, store: AppStore) {
        let isPending = store === pairingStore
        guard isPending || store === activeStore else { return }
        let profile = isPending ? pendingProfile : activeProfile
        guard var profile else { return }
        if let id = frame.gatewayId { profile.gatewayId = id.lowercased() }
        if let name = frame.gatewayName, !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            profile.gatewayName = name
        }
        if let device = frame.device { profile.remoteDeviceId = device.id }
        if isPending { pendingProfile = profile }
        guard frame.kind == "hello" else {
            if isPending && profile.id == activeID { activeStore.gateway.disconnect() }
            return
        }
        profile.lastConnectedAt = .now
        profile.preferredEndpoint = store.gateway.connectedEndpoint ?? store.endpoint
        store.gatewayDisplayName = profile.displayName
        if let index = profiles.firstIndex(where: { $0.id == profile.id }) { profiles[index] = profile }
        else { profiles.append(profile) }
        persist()
        if isPending {
            store.interfaceStyle = activeStore.interfaceStyle
            activeStore.deactivateGateway()
            activeID = profile.id
            activeStore = store
            defaults.set(profile.id, forKey: "gateway.activeID")
            pairingStore = nil
            pendingProfile = nil
            pairingObservation = nil
            observeActive()
        }
    }

    func cancelPairing() {
        pairingStore?.deactivateGateway()
        pairingStore = nil
        pendingProfile = nil
        pairingObservation = nil
    }

    func edit(_ profile: GatewayProfile, alias: String, kind: String) {
        guard let index = profiles.firstIndex(where: { $0.id == profile.id }) else { return }
        profiles[index].alias = String(alias.trimmingCharacters(in: .whitespacesAndNewlines).prefix(80))
        profiles[index].deviceKind = kind == "server.rack" ? kind : "desktopcomputer"
        if activeID == profile.id { activeStore.gatewayDisplayName = profiles[index].displayName }
        persist()
    }

    func remove(_ profile: GatewayProfile) {
        remove(ids: Set([profile.id]))
    }

    func remove(ids: Set<String>) {
        let knownIDs = ids.intersection(Set(profiles.map(\.id)))
        guard !knownIDs.isEmpty else { return }
        cancelPairing()
        stopProbes()
        if activeID.map(knownIDs.contains) == true {
            let style = activeStore.interfaceStyle
            observation = nil
            activeStore.deactivateGateway()
            activeID = nil
            defaults.removeObject(forKey: "gateway.activeID")
            activeStore = AppStore(preferences: UserDefaultsAppPreferences(userDefaults: defaults, gatewayID: "unpaired"), gatewayLocalID: "unpaired")
            activeStore.interfaceStyle = style
            installPairingHandler(on: activeStore)
        }
        knownIDs.forEach { GatewayClient.forgetCredential(for: $0) }
        profiles.removeAll { knownIDs.contains($0.id) }
        onlineIDs.subtract(knownIDs)
        persist()
    }

    /// 已配对资料的控制通道短暂验证 hello，不订阅会话、不发业务请求。
    /// 只有列表可见且 App 前台时运行；最多两个探测并发，每地址最多三秒。
    func refreshPresence() async {
        let candidates = profiles.filter { $0.id != activeID || !activeStore.gateway.state.isConnected }
        for profile in candidates { onlineIDs.remove(profile.id) }
        for start in stride(from: 0, to: candidates.count, by: 2) {
            guard !Task.isCancelled else { return }
            let group = Array(candidates[start..<min(start + 2, candidates.count)])
            await withTaskGroup(of: Void.self) { tasks in
                for profile in group {
                    tasks.addTask { @MainActor [weak self] in await self?.probe(profile) }
                }
            }
        }
    }

    private func probe(_ profile: GatewayProfile) async {
        let client = GatewayClient()
        client.credentialID = profile.id
        client.expectedGatewayID = profile.gatewayId
        client.trustedEndpoints = profile.endpoints
        client.probeOnly = true
        client.onIdentity = { [weak self] frame, _ in
            if let id = frame.gatewayId, self?.profiles.contains(where: { $0.id != profile.id && $0.gatewayId?.lowercased() == id.lowercased() }) == true {
                throw GatewayProfileError.conflict
            }
        }
        probes.append(client)
        defer { client.disconnect(); probes.removeAll { $0 === client } }
        for endpoint in profile.connectionEndpoints {
            guard !Task.isCancelled, profiles.contains(where: { $0.id == profile.id }) else { return }
            guard client.hasStoredCredential(for: endpoint) else { return }
            client.connect(to: endpoint)
            for _ in 0..<12 {
                do { try await Task.sleep(for: .milliseconds(250)) } catch { return }
                if client.state.isConnected {
                    if profiles.contains(where: { $0.id == profile.id }) { onlineIDs.insert(profile.id) }
                    return
                }
                if case .failed = client.state { break }
            }
            client.disconnect()
        }
    }

    func stopProbes() {
        probes.forEach { $0.disconnect() }
        probes.removeAll()
        onlineIDs = activeStore.gateway.state.isConnected ? Set([activeID].compactMap { $0 }) : []
    }

    private func migrateLegacyFiles(to id: String) throws {
        let manager = FileManager.default
        if let caches = manager.urls(for: .cachesDirectory, in: .userDomainMask).first {
            let source = caches.appendingPathComponent("DshMobile/ImageAttachments")
            let target = caches.appendingPathComponent("GatewayAttachments/\(id)")
            if manager.fileExists(atPath: source.path), !manager.fileExists(atPath: target.path) {
                try manager.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
                try manager.copyItem(at: source, to: target)
            }
        }
        // 下载位置保留原 URI/bookmark，仅给旧资源索引补上本地主机前缀。
        let key = "workspaceDownloadLocations.v1"
        if let data = defaults.data(forKey: key),
           var locations = try JSONSerialization.jsonObject(with: data) as? [String: Any] {
            for (key, value) in locations {
                guard let decoded = Data(base64Encoded: key), let identity = String(data: decoded, encoding: .utf8) else { continue }
                locations[Data("\(id):\(identity)".utf8).base64EncodedString()] = value
            }
            defaults.set(try JSONSerialization.data(withJSONObject: locations), forKey: key)
        }
    }

    private func persist() {
        do { defaults.set(try JSONEncoder().encode(profiles), forKey: Self.profilesKey) }
        catch { self.error = "保存主机资料失败：\(error.localizedDescription)" }
    }
}
