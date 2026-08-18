import SwiftUI

enum InterfaceStyle: String, CaseIterable, Identifiable {
    case system, light, dark
    var id: String { rawValue }
    var title: String { switch self { case .system: "跟随系统"; case .light: "浅色"; case .dark: "深色" } }
    var colorScheme: ColorScheme? { switch self { case .system: nil; case .light: .light; case .dark: .dark } }
}

struct HistoryLoadProgress: Equatable {
    var loaded: Int
    var total: Int?
}

@MainActor
final class AppStore: ObservableObject {
    static let ungroupedWorkspaceID = "__ungrouped__"

    @Published var selectedSessionId: String?
    @Published var sessions: [SessionSummary] = [] { didSet { persistSessions() } }
    @Published var workspaces: [GatewayWorkspace] = []
    @Published var selectedWorkspaceId: String? {
        didSet { UserDefaults.standard.set(selectedWorkspaceId, forKey: "gateway.selectedWorkspaceId") }
    }
    @Published var archivedSessionIds: Set<String> = []
    @Published var events: [String: [SessionEvent]] = [:]
    @Published var renderedConversationItems: [String: [ConversationItem]] = [:]
    @Published var historyHasMore: [String: Bool] = [:]
    @Published var historyLoadingSessionIds: Set<String> = []
    @Published var historyLoadProgress: [String: HistoryLoadProgress] = [:]
    @Published var searchResults: [GatewaySearchItem] = []
    @Published var hostSnapshot: GatewayHostSnapshot?
    @Published var directoryPath: String?
    @Published var directoryHome: String?
    @Published var directoryCrumbs: [GatewayDirectoryItem] = []
    @Published var directoryEntries: [GatewayDirectoryItem] = []
    @Published var directoryIsLoading = false
    @Published var workspaceCreationIsLoading = false
    @Published var protocolNotices: [GatewayNotice] = []
    @Published var endpoint: String { didSet { UserDefaults.standard.set(endpoint, forKey: "gateway.endpoint") } }
    @Published var interfaceStyle: InterfaceStyle = .system
    @Published var lastError: String?
    @Published var waitingForNewSession = false
    @Published var isRefreshing = false
    @Published var modelCatalogs: [String: GatewayModelCatalog] = [:]
    @Published var globalModelCatalog: GatewayModelCatalog?
    @Published var sessionPermissions: [String: GatewaySessionPermissions] = [:]
    @Published var contextSnapshots: [String: GatewayContextSnapshot] = [:]
    @Published var sessionStatsSnapshots: [String: GatewaySessionStatsSnapshot] = [:]
    @Published var sessionControlLoadingKinds: Set<String> = []
    @Published var agentPresets: [GatewayAgentPreset] = []
    @Published var agentPresetsAuthorable = false
    @Published var agentPresetsHasDocument = false
    @Published var agentPresetDefault: String?
    @Published var permissionDefault: String?
    @Published var defaultModelSelection: GatewayModelSelection?
    @Published var defaultConfigurationLoadingKinds: Set<String> = []
    @Published var workspaceScrollAnchor: String?
    @Published private(set) var conversationScrollAnchors: [String: String] = [:]
    @Published private(set) var manuallyPositionedSessionIds: Set<String> = []

    let gateway = GatewayClient()
    private var pendingHistorySessionId: String?
    private var historyRequestTokens: [String: UUID] = [:]
    private var historyPaginationCursors: [String: Set<Int>] = [:]
    private var historyLoadedEventCounts: [String: Int] = [:]
    private var historyLoadedByteCounts: [String: Int] = [:]
    /// The remote session activity timestamp covered by a completed history load.
    /// This intentionally remains an in-memory cache: events are not persisted
    /// across launches, so a fresh process must fetch history again.
    private var historySyncedActivityDates: [String: Date] = [:]
    private var conversationProjectionTasks: [String: Task<Void, Never>] = [:]
    private var pendingModelsSessionId: String?
    private var isPendingGlobalModelsRequest = false
    private var pendingModelSelectionSessionId: String?
    private var pendingPermissionOptionsSessionId: String?
    private var sessionControlRequestTokens: [String: UUID] = [:]
    private var defaultConfigurationRequestTokens: [String: UUID] = [:]
    private static let permissionPresets: Set<String> = ["read-only", "workspace-write", "danger-full-access"]
    private static let defaultPermissionPresets: Set<String> = ["ask", "read-only", "workspace-write", "danger-full-access"]
    private static let historyPageByteBudget = 4 * 1024 * 1024

    init() {
        selectedWorkspaceId = UserDefaults.standard.string(forKey: "gateway.selectedWorkspaceId")
        endpoint = UserDefaults.standard.string(forKey: "gateway.endpoint") ?? "ws://127.0.0.1:3080/ws/mobile"
        workspaceScrollAnchor = nil
        if let data = UserDefaults.standard.data(forKey: "gateway.sessions"),
           let decoded = try? JSONDecoder().decode([SessionSummary].self, from: data) { sessions = decoded }
        if let data = UserDefaults.standard.data(forKey: "gateway.conversationScrollAnchors"),
           let decoded = try? JSONDecoder().decode([String: String].self, from: data) {
            conversationScrollAnchors = decoded
        }
        if let data = UserDefaults.standard.data(forKey: "gateway.manuallyPositionedSessionIds"),
           let decoded = try? JSONDecoder().decode(Set<String>.self, from: data) {
            manuallyPositionedSessionIds = decoded
        }
        gateway.onFrame = { [weak self] frame in self?.handle(frame) }
    }

    var selectedEvents: [SessionEvent] {
        guard let selectedSessionId else { return [] }
        return events[selectedSessionId, default: []]
    }
    var selectedConversationItems: [ConversationItem] {
        guard let selectedSessionId else { return [] }
        return renderedConversationItems[selectedSessionId, default: []]
    }
    var selectedNotices: [GatewayNotice] {
        Array(protocolNotices.filter { $0.sessionId == nil || $0.sessionId == selectedSessionId }.suffix(20))
    }
    var selectedSession: SessionSummary? { sessions.first { $0.id == selectedSessionId } }
    var selectedModelCatalog: GatewayModelCatalog? { selectedSessionId.flatMap { modelCatalogs[$0] } }
    var selectedPermissions: GatewaySessionPermissions? { selectedSessionId.flatMap { sessionPermissions[$0] } }
    var selectedContextSnapshot: GatewayContextSnapshot? { selectedSessionId.flatMap { contextSnapshots[$0] } }
    var selectedSessionStatsSnapshot: GatewaySessionStatsSnapshot? { selectedSessionId.flatMap { sessionStatsSnapshots[$0] } }
    func conversationScrollAnchor(for sessionId: String) -> String? { conversationScrollAnchors[sessionId] }
    func hasManualConversationPosition(for sessionId: String) -> Bool { manuallyPositionedSessionIds.contains(sessionId) }
    func rememberConversationScrollAnchor(_ anchor: String?, for sessionId: String, manual: Bool) {
        if let anchor { conversationScrollAnchors[sessionId] = anchor }
        else { conversationScrollAnchors.removeValue(forKey: sessionId) }
        if manual {
            manuallyPositionedSessionIds.insert(sessionId)
        } else {
            manuallyPositionedSessionIds.remove(sessionId)
        }
        persistConversationScrollPositions()
    }
    var activeWorkspace: GatewayWorkspace? {
        guard !isUngroupedWorkspaceSelected else { return nil }
        if let selectedWorkspaceId,
           let workspace = workspaces.first(where: { $0.id == selectedWorkspaceId }) {
            return workspace
        }
        return workspaces.first
    }

    func selectWorkspace(_ workspace: GatewayWorkspace) {
        selectedWorkspaceId = workspace.id
    }
    func selectUngroupedWorkspace() {
        selectedWorkspaceId = Self.ungroupedWorkspaceID
    }
    var isUngroupedWorkspaceSelected: Bool {
        selectedWorkspaceId == Self.ungroupedWorkspaceID
    }
    var ungroupedSessions: [SessionSummary] {
        let groupedSessionIds = Set(workspaces.flatMap(\.sessionIds))
        return sessions.filter { !groupedSessionIds.contains($0.id) }
    }

    func connect() { gateway.connect(to: endpoint) }
    func refreshRemoteState() {
        guard gateway.state.isConnected else { return }
        isRefreshing = true
        gateway.requestWorkspaces()
        gateway.requestSessions()
        gateway.requestHost()
    }
    func refreshDefaultConfiguration() {
        guard gateway.state.isConnected else { return }
        beginDefaultConfigurationRequest("agent-presets")
        beginDefaultConfigurationRequest("defaults")
        beginDefaultConfigurationRequest("default-model")
        gateway.requestAgentPresets()
        gateway.requestDefaults()
        gateway.requestDefaultModel()
    }
    func setDefaultAgentPreset(_ id: String) {
        guard agentPresets.contains(where: { $0.id == id && $0.broken != true }) else {
            lastError = "无法将未知或已损坏的 Agent 预设设为默认值：\(id)"
            return
        }
        setGlobalDefault(target: "agent-preset", value: id)
    }
    func setDefaultPermission(_ value: String) {
        guard Self.defaultPermissionPresets.contains(value) else {
            lastError = "不支持的默认权限：\(value)"
            return
        }
        setGlobalDefault(target: "permission", value: value)
    }
    /// The default-model picker in Settings is not tied to any session, so it
    /// uses the session-independent `{"type":"models"}` variant to fetch the
    /// global provider/model catalog (falling back to any already-cached
    /// per-session catalog if the global one hasn't loaded yet).
    var anyModelCatalog: GatewayModelCatalog? {
        globalModelCatalog ?? modelCatalogs.values.first(where: { !$0.groups.isEmpty })
    }
    func ensureModelCatalogForDefaults() {
        guard gateway.state.isConnected else { return }
        guard anyModelCatalog == nil else { return }
        guard !sessionControlLoadingKinds.contains("models") else { return }
        isPendingGlobalModelsRequest = true
        beginSessionControlRequest("models")
        gateway.requestModels()
    }
    func saveDefaultModel(provider: String, model: String, reasoningEffort: String?) {
        guard gateway.state.isConnected else {
            lastError = "请先连接 DeepSeek Harness"
            return
        }
        beginDefaultConfigurationRequest("save-default-model")
        gateway.saveDefaultModel(provider: provider, model: model, reasoningEffort: reasoningEffort)
    }
    func startNewSession() {
        selectedSessionId = nil
        waitingForNewSession = false
        gateway.subscribe(sessionId: nil)
        // The composer for an unsaved session mirrors the deployment defaults.
        // Refresh here so changes made by WebUI or another client are visible
        // before the user sends the first message.
        refreshDefaultConfiguration()
    }
    func open(_ session: SessionSummary) {
        selectedSessionId = session.id
        markRead(session.id)
        gateway.subscribe(sessionId: session.id)
        if agentPresets.isEmpty {
            beginDefaultConfigurationRequest("agent-presets")
            gateway.requestAgentPresets()
        }
        if shouldRefreshHistory(for: session) {
            loadHistory(for: session.id)
        }
        refreshSessionControls(for: session.id)
    }

    private func shouldRefreshHistory(for session: SessionSummary) -> Bool {
        guard !historyLoadingSessionIds.contains(session.id) else { return false }
        guard let syncedActivity = historySyncedActivityDates[session.id] else { return true }
        return session.lastActivity > syncedActivity
    }
    func loadHistory(for sessionId: String, older: Bool = false) {
        guard gateway.state.isConnected else {
            lastError = "WebSocket 尚未连接，无法加载历史记录"
            return
        }
        pendingHistorySessionId = sessionId
        historyLoadingSessionIds.insert(sessionId)
        historyLoadProgress[sessionId] = HistoryLoadProgress(loaded: 0, total: nil)
        historyPaginationCursors[sessionId] = []
        historyLoadedEventCounts[sessionId] = 0
        historyLoadedByteCounts[sessionId] = 0
        historyHasMore[sessionId] = false
        // Keep any in-memory projection visible while the gateway refreshes.
        // The response is merged by sequence and replaces this cache only after
        // a usable projected batch is ready.
        let before = older ? events[sessionId]?.map(\.seq).min() : nil
        if let before {
            historyPaginationCursors[sessionId] = [before]
        }
        requestHistoryPage(for: sessionId, beforeSeq: before)
    }

    private func requestHistoryPage(for sessionId: String, beforeSeq: Int?) {
        let token = UUID()
        historyRequestTokens[sessionId] = token
        gateway.requestHistory(
            sessionId: sessionId,
            beforeSeq: beforeSeq,
            maxMessages: 60,
            maxBytes: Self.historyPageByteBudget,
            view: "conversation"
        )
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(20))
            guard let self, self.historyRequestTokens[sessionId] == token else { return }
            self.finishHistoryLoading(sessionId)
            self.lastError = "历史记录加载超时，请重试"
        }
    }
    func resumeWorkspace() {
        gateway.subscribe(sessionId: nil)
        refreshRemoteState()
    }
    func addKnownSession(_ id: String) {
        let normalized = id.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalized.isEmpty else { return }
        upsertSession(id: normalized, title: "远端会话 \(normalized.prefix(8))")
    }
    func search(_ query: String) {
        let normalized = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if normalized.isEmpty { searchResults = [] } else { gateway.searchSessions(normalized) }
    }
    func browseDirectories(path: String? = nil) {
        guard gateway.state.isConnected else {
            lastError = "请先连接 DeepSeek Harness"
            return
        }
        directoryIsLoading = true
        gateway.requestDirectories(path: path)
    }
    func createWorkspace(path: String) {
        guard gateway.state.isConnected else {
            lastError = "请先连接 DeepSeek Harness"
            return
        }
        workspaceCreationIsLoading = true
        gateway.createWorkspace(path: path)
    }
    func refreshSessionControls(for sessionId: String) {
        guard gateway.state.isConnected else { return }
        pendingModelsSessionId = sessionId
        pendingPermissionOptionsSessionId = sessionId
        beginSessionControlRequest("models")
        beginSessionControlRequest("permission-options")
        beginSessionControlRequest("context-usage")
        beginSessionControlRequest("session-stats")
        gateway.requestModels(sessionId: sessionId)
        gateway.requestPermissionOptions(sessionId: sessionId)
        gateway.requestContextUsage(sessionId: sessionId)
        gateway.requestSessionStats(sessionId: sessionId)
    }
    func selectModel(provider: String, model: String, reasoningEffort: String?) {
        guard let sessionId = selectedSessionId else { return }
        pendingModelSelectionSessionId = sessionId
        beginSessionControlRequest("select-model")
        gateway.selectModel(
            sessionId: sessionId,
            provider: provider,
            model: model,
            reasoningEffort: reasoningEffort
        )
    }
    func setPermission(_ name: String) {
        guard let sessionId = selectedSessionId else { return }
        guard Self.permissionPresets.contains(name) else {
            lastError = "不支持的访问权限：\(name)。可用状态为 read-only、workspace-write、danger-full-access。"
            return
        }
        beginSessionControlRequest("permission")
        gateway.setPermission(sessionId: sessionId, name: name)
    }
    func send(_ text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        guard gateway.state.isConnected else { lastError = "请先在设置中连接 DeepSeek Harness"; return }
        waitingForNewSession = selectedSessionId == nil
        gateway.sendMessage(
            text: trimmed,
            sessionId: selectedSessionId,
            workspaceId: selectedSessionId == nil ? activeWorkspace?.id : nil
        )
    }
    func title(for sessionId: String) -> String { sessions.first(where: { $0.id == sessionId })?.title ?? "DeepSeek Harness" }

    private func handle(_ frame: GatewayFrame) {
        switch frame.kind {
        case "hello":
            notice("网关已连接", "Mobile protocol v\(frame.protocol ?? 1) · \(frame.clients ?? 1) 个客户端")
            refreshRemoteState()
            if let selectedSessionId { refreshSessionControls(for: selectedSessionId) }
        case "pong":
            notice("心跳正常", frame.at.map { Date(timeIntervalSince1970: $0 / 1000).formatted(date: .omitted, time: .standard) } ?? "pong")
        case "subscribed":
            notice("事件订阅", frame.sessionId.map { "仅接收 \($0.prefix(12))…" } ?? "接收全部会话事件", sessionId: frame.sessionId)
        case "sent": handleSent(frame)
        case "event": handleLiveEvent(frame)
        case "workspaces":
            workspaces = decodeItems(frame.items, as: GatewayWorkspace.self)
            if selectedWorkspaceId == nil || (
                selectedWorkspaceId != Self.ungroupedWorkspaceID &&
                !workspaces.contains(where: { $0.id == selectedWorkspaceId })
            ) {
                selectedWorkspaceId = workspaces.first?.id ?? Self.ungroupedWorkspaceID
            }
            archivedSessionIds = Set(frame.archivedSessionIds ?? [])
            notice("工作区已同步", "\(workspaces.count) 个工作区")
        case "sessions":
            applyRemoteSessions(decodeItems(frame.items, as: GatewaySessionSummary.self))
            isRefreshing = false
            notice("会话列表已同步", "\(sessions.count) 个会话")
        case "history": applyHistoryProgressively(frame)
        case "search":
            searchResults = decodeItems(frame.items, as: GatewaySearchItem.self)
            notice("搜索完成", "\(searchResults.count) 条结果\(frame.hasMore == true ? "，还有更多" : "")")
        case "host":
            hostSnapshot = GatewayHostSnapshot(version: frame.version, cwd: frame.cwd, provider: frame.provider, model: frame.model, attachedSessions: frame.attachedSessions, canOpenPath: frame.canOpenPath)
            notice("宿主信息", [frame.version, frame.provider, frame.model].compactMap { $0 }.joined(separator: " · "))
        case "agent-presets":
            agentPresets = frame.presets ?? []
            agentPresetsAuthorable = frame.authorable ?? false
            agentPresetsHasDocument = frame.hasDocument ?? false
            if agentPresetDefault == nil {
                agentPresetDefault = agentPresets.first(where: \.isDefault)?.id
            }
            finishDefaultConfigurationRequest("agent-presets")
        case "defaults":
            agentPresetDefault = frame.agentPresetDefault
            permissionDefault = frame.permissionDefault
            finishDefaultConfigurationRequest("defaults")
        case "default-model":
            defaultModelSelection = frame.selection
            finishDefaultConfigurationRequest("default-model")
        case "save-default-model":
            if let saved = frame.saved {
                defaultModelSelection = saved
                notice("默认模型已更新", [saved.model, saved.reasoningEffort].compactMap { $0 }.joined(separator: " · "))
            } else {
                lastError = "服务端未确认默认模型更新。"
            }
            finishDefaultConfigurationRequest("save-default-model")
        case "set-default":
            if frame.applied == true, let target = frame.target, let value = frame.value {
                if target == "agent-preset" { agentPresetDefault = value }
                if target == "permission" { permissionDefault = value }
                notice("默认配置已更新", "\(target) · \(value)")
                finishDefaultConfigurationRequest("set-default")
                beginDefaultConfigurationRequest("defaults")
                gateway.requestDefaults()
            } else {
                finishDefaultConfigurationRequest("set-default")
                lastError = "服务端未确认默认配置更新。"
            }
        case "models":
            if let id = frame.sessionId ?? pendingModelsSessionId {
                modelCatalogs[id] = GatewayModelCatalog(
                    current: frame.current,
                    routable: frame.routable ?? false,
                    groups: frame.groups ?? []
                )
            } else if isPendingGlobalModelsRequest {
                globalModelCatalog = GatewayModelCatalog(
                    current: nil,
                    routable: false,
                    groups: frame.groups ?? []
                )
            }
            finishSessionControlRequest("models")
        case "select-model":
            if let id = frame.sessionId ?? pendingModelSelectionSessionId,
               let selected = frame.selected {
                var catalog = modelCatalogs[id] ?? GatewayModelCatalog(current: nil, routable: true, groups: [])
                catalog.current = selected
                modelCatalogs[id] = catalog
                notice("模型已切换", [selected.model, selected.reasoningEffort].compactMap { $0 }.joined(separator: " · "), sessionId: id)
            }
            pendingModelSelectionSessionId = nil
            finishSessionControlRequest("select-model")
        case "permission-options":
            if let id = frame.sessionId ?? pendingPermissionOptionsSessionId,
               let permissions = frame.sessionPermissions {
                applyPermissions(permissions, sessionId: id, source: "permission-options")
            }
            pendingPermissionOptionsSessionId = nil
            finishSessionControlRequest("permission-options")
        case "permission":
            if let id = frame.sessionId ?? selectedSessionId, let name = frame.set {
                if Self.permissionPresets.contains(name) {
                    var permissions = sessionPermissions[id] ?? GatewaySessionPermissions()
                    permissions.currentValue = name
                    permissions.preset = name
                    sessionPermissions[id] = permissions
                    notice("权限已切换", name, sessionId: id)
                } else {
                    reportInvalidPermission(name, sessionId: id, source: "permission")
                }
            }
            finishSessionControlRequest("permission")
        case "context-usage":
            if let id = frame.sessionId ?? selectedSessionId {
                var snapshot = contextSnapshots[id] ?? GatewayContextSnapshot()
                snapshot.asOfSeq = frame.asOfSeq ?? snapshot.asOfSeq
                snapshot.tokenUsage = frame.tokenUsage ?? snapshot.tokenUsage
                snapshot.pressure = frame.contextPressure ?? snapshot.pressure
                contextSnapshots[id] = snapshot
            }
            finishSessionControlRequest("context-usage")
        case "session-stats":
            if let id = frame.sessionId ?? selectedSessionId {
                var snapshot = sessionStatsSnapshots[id] ?? GatewaySessionStatsSnapshot()
                snapshot.asOfSeq = frame.asOfSeq ?? snapshot.asOfSeq
                snapshot.stats = frame.sessionStats ?? snapshot.stats
                // The statistics endpoint uses a nested `tokenUsage.totals`
                // object, while context-usage returns the older flat shape.
                if let totals = frame.tokenUsage?.totals {
                    snapshot.tokenUsage = GatewaySessionTokenUsage(totals: totals)
                }
                snapshot.contextPressure = frame.contextPressure ?? snapshot.contextPressure
                sessionStatsSnapshots[id] = snapshot
            }
            finishSessionControlRequest("session-stats")
        case "directories":
            directoryIsLoading = false
            directoryPath = frame.path
            directoryHome = frame.home
            directoryCrumbs = frame.crumbs ?? []
            directoryEntries = frame.entries ?? []
            notice("目录已加载", "\(frame.path ?? "") · \(directoryEntries.count) 项")
        case "workspace-create":
            workspaceCreationIsLoading = false
            if let workspace = frame.workspace {
                if let index = workspaces.firstIndex(where: { $0.id == workspace.id }) { workspaces[index] = workspace } else { workspaces.append(workspace) }
                selectedWorkspaceId = workspace.id
                notice(frame.created == true ? "工作区已创建" : "工作区已存在", workspace.path)
                refreshRemoteState()
            }
        case "error":
            waitingForNewSession = false
            if frame.requestType == "directories" { directoryIsLoading = false }
            if frame.requestType == "workspace-create" { workspaceCreationIsLoading = false }
            if let requestType = frame.requestType,
               ["agent-presets", "defaults", "default-model", "set-default", "save-default-model"].contains(requestType) {
                finishDefaultConfigurationRequest(requestType)
            }
            if frame.requestType == "history", let id = frame.sessionId ?? pendingHistorySessionId {
                finishHistoryLoading(id)
            }
            let detail = [frame.code, frame.message].compactMap { $0 }.joined(separator: ": ")
            let failedRequest = frame.requestType ?? frame.code.flatMap(sessionControlKind(from:)) ?? frame.message.flatMap(sessionControlKind(from:))
            if let failedRequest { finishSessionControlRequest(failedRequest) }
            if frame.requestType == nil, failedRequest == nil {
                // A malformed gateway error cannot be correlated safely. Stop
                // all composer spinners and surface the error instead.
                for kind in Array(sessionControlLoadingKinds) { finishSessionControlRequest(kind) }
            }
            lastError = detail
            notice("请求失败\(frame.requestType.map { " · \($0)" } ?? "")", detail, sessionId: frame.sessionId, isError: true)
        default: notice("未知网关响应", frame.kind)
        }
    }

    private func handleSent(_ frame: GatewayFrame) {
        guard let id = frame.sessionId else { return }
        if selectedSessionId == nil { selectedSessionId = id }
        waitingForNewSession = false
        upsertSession(id: id, title: "新建 Harness 会话")
        if let index = sessions.firstIndex(where: { $0.id == id }) {
            sessions[index].agentPreset = agentPresetDefault
        }
        notice(frame.command == nil ? "消息已发送" : "命令已执行", frame.command?.displayText ?? "\(id.prefix(12))…", sessionId: id)
        gateway.subscribe(sessionId: id)
        gateway.requestSessions()
        refreshSessionControls(for: id)
    }
    private func handleLiveEvent(_ frame: GatewayFrame) {
        guard let id = frame.sessionId, let seq = frame.seq, let time = frame.time, let event = frame.event else { return }
        merge(SessionEvent(sessionId: id, seq: seq, time: time, event: event))
    }
    private func applyHistoryProgressively(_ frame: GatewayFrame) {
        guard let id = frame.sessionId ?? pendingHistorySessionId ?? selectedSessionId else { return }
        applyHistoryProjections(frame.projections, sessionId: id)
        let rawEvents = frame.events ?? []
        let pageEventOffset = historyLoadedEventCounts[id, default: 0]
        // Invalidate the network timeout; processing now has its own token.
        let processingToken = UUID()
        historyRequestTokens[id] = processingToken
        historyLoadProgress[id] = HistoryLoadProgress(
            loaded: pageEventOffset,
            total: frame.hasMore == true ? nil : pageEventOffset + rawEvents.count
        )

        Task { [weak self] in
            let normalized = await Task.detached(priority: .userInitiated) {
                rawEvents.map { $0.normalized(sessionId: id) }
            }.value
            guard let self, self.historyRequestTokens[id] == processingToken else { return }

            let existing = self.events[id, default: []]
            let projectionSource = await Task.detached(priority: .userInitiated) {
                var records = Dictionary(uniqueKeysWithValues: existing.map { ($0.seq, $0) })
                for record in normalized { records[record.seq] = record }
                return records.values.sorted { $0.seq < $1.seq }
            }.value
            let projectedItems = await Task.detached(priority: .userInitiated) {
                ConversationItem.make(from: projectionSource)
            }.value
            guard self.historyRequestTokens[id] == processingToken else { return }

            // Show the newest messages immediately. Older rows are prepended in
            // small batches so Markdown layout never monopolizes the main thread.
            // If a cached projection exists, keep it untouched until the complete
            // replacement is ready. Replacing a measured LazyVStack piecemeal can
            // leave SwiftUI holding an offset outside the new content bounds.
            let currentRenderedCount = self.renderedConversationItems[id, default: []].count
            let publishesProgressively = currentRenderedCount == 0
            var visibleItemStart = max(0, projectedItems.count - max(12, currentRenderedCount))
            if projectedItems.isEmpty && publishesProgressively {
                self.renderedConversationItems[id] = []
            } else if publishesProgressively {
                self.renderedConversationItems[id] = Array(projectedItems[visibleItemStart..<projectedItems.count])
            }

            var recordsBySequence = Dictionary(uniqueKeysWithValues: existing.map { ($0.seq, $0) })
            let batchSize = 800
            var loaded = 0
            while loaded < normalized.count {
                guard self.historyRequestTokens[id] == processingToken else { return }
                let end = min(loaded + batchSize, normalized.count)
                let batch = Array(normalized[loaded..<end])
                loaded = end
                // Preserve live events that may arrive while history is being
                // normalized and progressively published.
                let liveSnapshot = self.events[id, default: []]
                let recordsSnapshot = recordsBySequence
                recordsBySequence = await Task.detached(priority: .userInitiated) {
                    var records = recordsSnapshot
                    for record in batch { records[record.seq] = record }
                    for record in liveSnapshot { records[record.seq] = record }
                    return records
                }.value
                let recordsToSort = recordsBySequence
                let sortedRecords = await Task.detached(priority: .userInitiated) {
                    recordsToSort.values.sorted { $0.seq < $1.seq }
                }.value
                self.events[id] = sortedRecords
                self.historyLoadProgress[id] = HistoryLoadProgress(
                    loaded: pageEventOffset + loaded,
                    total: frame.hasMore == true ? nil : pageEventOffset + normalized.count
                )

                if publishesProgressively && visibleItemStart > 0 {
                    visibleItemStart = max(0, visibleItemStart - 24)
                    self.renderedConversationItems[id] = Array(projectedItems[visibleItemStart..<projectedItems.count])
                }
                // Give SwiftUI a rendering opportunity between history batches.
                try? await Task.sleep(for: .milliseconds(10))
            }

            while publishesProgressively && visibleItemStart > 0 {
                guard self.historyRequestTokens[id] == processingToken else { return }
                visibleItemStart = max(0, visibleItemStart - 24)
                self.renderedConversationItems[id] = Array(projectedItems[visibleItemStart..<projectedItems.count])
                try? await Task.sleep(for: .milliseconds(10))
            }

            // Commit the complete projection before ending the loading phase.
            // Yielding here lets LazyVStack measure the new Markdown rows while
            // automatic live-message scrolling is still suppressed.
            self.renderedConversationItems[id] = projectedItems
            await Task.yield()
            try? await Task.sleep(for: .milliseconds(24))

            self.historyLoadedEventCounts[id] = pageEventOffset + normalized.count
            self.historyLoadedByteCounts[id, default: 0] += frame.bytes ?? 0
            self.historyHasMore[id] = frame.hasMore ?? false

            if frame.hasMore == true {
                guard let cursor = frame.nextBeforeSeq else {
                    let message = "网关返回 hasMore:true，但缺少 nextBeforeSeq，已停止自动续页。"
                    self.finishHistoryLoading(id)
                    self.scheduleConversationProjection(for: id)
                    self.lastError = message
                    self.notice("历史记录分页失败", message, sessionId: id, isError: true)
                    return
                }
                var seenCursors = self.historyPaginationCursors[id, default: []]
                guard !seenCursors.contains(cursor) else {
                    let message = "网关重复返回历史游标 \(cursor)，已停止自动续页以避免循环。"
                    self.finishHistoryLoading(id)
                    self.scheduleConversationProjection(for: id)
                    self.lastError = message
                    self.notice("历史记录分页失败", message, sessionId: id, isError: true)
                    return
                }
                seenCursors.insert(cursor)
                self.historyPaginationCursors[id] = seenCursors
                self.requestHistoryPage(for: id, beforeSeq: cursor)
                return
            }

            let totalEvents = self.historyLoadedEventCounts[id, default: 0]
            let totalBytes = self.historyLoadedByteCounts[id, default: 0]
            if let activity = self.sessions.first(where: { $0.id == id })?.lastActivity {
                self.historySyncedActivityDates[id] = activity
            }
            self.finishHistoryLoading(id)
            self.scheduleConversationProjection(for: id)
            let byteDetail = totalBytes > 0 ? " · \(ByteCountFormatter.string(fromByteCount: Int64(totalBytes), countStyle: .file))" : ""
            self.notice("历史记录已加载", "\(totalEvents) 个事件\(byteDetail)", sessionId: id)
        }
    }
    private func finishHistoryLoading(_ sessionId: String) {
        historyRequestTokens[sessionId] = nil
        historyPaginationCursors[sessionId] = nil
        historyLoadedEventCounts[sessionId] = nil
        historyLoadedByteCounts[sessionId] = nil
        historyLoadingSessionIds.remove(sessionId)
        historyLoadProgress[sessionId] = nil
        if pendingHistorySessionId == sessionId { pendingHistorySessionId = nil }
    }
    private func applyHistoryProjections(_ projections: JSONValue?, sessionId: String) {
        guard let values = projections?["values"] else { return }
        var snapshot = contextSnapshots[sessionId] ?? GatewayContextSnapshot()
        snapshot.asOfSeq = projections?["asOfSeq"]?.doubleValue.map(Int.init) ?? snapshot.asOfSeq
        snapshot.tokenUsage = values["tokenUsage"]?.decode(GatewayTokenUsage.self) ?? snapshot.tokenUsage
        snapshot.pressure = values["contextPressure"]?.decode(GatewayContextPressure.self) ?? snapshot.pressure
        snapshot.breakdown = values["contextBreakdown"]?.decode(GatewayContextBreakdown.self) ?? snapshot.breakdown
        contextSnapshots[sessionId] = snapshot
        if let permissions = values["permissions"]?.decode(GatewaySessionPermissions.self) {
            applyPermissions(permissions, sessionId: sessionId, source: "history.projections")
        }
    }
    private func merge(_ record: SessionEvent) {
        var list = events[record.sessionId, default: []]
        if let index = list.firstIndex(where: { $0.seq == record.seq }) { list[index] = record } else { list.append(record) }
        events[record.sessionId] = list.sorted { $0.seq < $1.seq }
        applyEvent(record)
        // Once the cache has been fully established, subscribed live events are
        // already the newest local content. Advance the watermark so reopening
        // the session does not download the same history again.
        if let syncedActivity = historySyncedActivityDates[record.sessionId] {
            historySyncedActivityDates[record.sessionId] = max(syncedActivity, record.date)
        }
        scheduleConversationProjection(for: record.sessionId)
    }
    private func scheduleConversationProjection(for sessionId: String) {
        guard !historyLoadingSessionIds.contains(sessionId) else { return }
        conversationProjectionTasks[sessionId]?.cancel()
        let snapshot = events[sessionId, default: []]
        conversationProjectionTasks[sessionId] = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(40))
            guard !Task.isCancelled else { return }
            let items = await Task.detached(priority: .userInitiated) {
                ConversationItem.make(from: snapshot)
            }.value
            guard let self, !Task.isCancelled else { return }
            self.renderedConversationItems[sessionId] = items
            self.conversationProjectionTasks[sessionId] = nil
        }
    }
    private func applyRemoteSessions(_ remote: [GatewaySessionSummary]) {
        for item in remote where !archivedSessionIds.contains(item.sessionId) {
            let fallback = item.cwd.map { URL(fileURLWithPath: $0).lastPathComponent }.flatMap { $0.isEmpty ? nil : $0 }
            let title = item.projectedTitle ?? fallback ?? "远端会话 \(item.sessionId.prefix(8))"
            let date = Date(timeIntervalSince1970: item.updatedAt > 10_000_000_000 ? item.updatedAt / 1000 : item.updatedAt)
            if let index = sessions.firstIndex(where: { $0.id == item.sessionId }) {
                sessions[index].title = title
                sessions[index].lastActivity = date
                sessions[index].isRunning = item.running
                sessions[index].agentPreset = item.agentPreset ?? sessions[index].agentPreset
            } else {
                sessions.append(SessionSummary(
                    id: item.sessionId,
                    title: item.blank ? "空白会话 \(item.sessionId.prefix(8))" : title,
                    lastActivity: date,
                    isRunning: item.running,
                    hasUnread: false,
                    agentPreset: item.agentPreset
                ))
            }
        }
        sessions.removeAll { archivedSessionIds.contains($0.id) }
        sessions.sort { $0.lastActivity > $1.lastActivity }
    }
    private func applyEvent(_ record: SessionEvent) {
        let event = record.event
        if event.type == "turn/end", record.sessionId == selectedSessionId {
            beginSessionControlRequest("context-usage")
            beginSessionControlRequest("session-stats")
            gateway.requestContextUsage(sessionId: record.sessionId)
            gateway.requestSessionStats(sessionId: record.sessionId)
        }
        if event.type == "permission/preset",
           let preset = event.raw?["preset"]?.stringValue ?? event.raw?["name"]?.stringValue {
            if Self.permissionPresets.contains(preset) {
                var permissions = sessionPermissions[record.sessionId] ?? GatewaySessionPermissions()
                permissions.currentValue = preset
                permissions.preset = preset
                sessionPermissions[record.sessionId] = permissions
            } else {
                reportInvalidPermission(preset, sessionId: record.sessionId, source: "permission/preset")
            }
        }
        if event.type == "request/header", let config = event.raw?["header"]?["config"] {
            let provider = config["provider"]?.stringValue
            let model = config["model"]?.stringValue
            if let provider, let model {
                var catalog = modelCatalogs[record.sessionId] ?? GatewayModelCatalog(current: nil, routable: true, groups: [])
                catalog.current = GatewayModelSelection(
                    provider: provider,
                    model: model,
                    reasoningEffort: config["reasoningEffort"]?.stringValue
                )
                modelCatalogs[record.sessionId] = catalog
            }
        }
        let title: String? = event.type == "user/message" ? event.text.map { String($0.prefix(28)) } : (event.type == "session/title" ? event.text : nil)
        upsertSession(id: record.sessionId, title: title)
        guard let index = sessions.firstIndex(where: { $0.id == record.sessionId }) else { return }
        sessions[index].lastActivity = record.date
        if event.type == "turn/start" { sessions[index].isRunning = true }
        if event.type == "turn/end" { sessions[index].isRunning = false }
        if selectedSessionId != record.sessionId { sessions[index].hasUnread = true }
        sessions.sort { $0.lastActivity > $1.lastActivity }
    }
    private func decodeItems<T: Decodable>(_ items: [JSONValue]?, as type: T.Type) -> [T] { (items ?? []).compactMap { $0.decode(type) } }
    private func notice(_ title: String, _ text: String, sessionId: String? = nil, isError: Bool = false) {
        protocolNotices.append(GatewayNotice(sessionId: sessionId, title: title, text: text, isError: isError))
        if protocolNotices.count > 100 { protocolNotices.removeFirst(protocolNotices.count - 100) }
    }

    private func beginSessionControlRequest(_ kind: String) {
        let token = UUID()
        sessionControlRequestTokens[kind] = token
        sessionControlLoadingKinds.insert(kind)
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(12))
            guard let self, self.sessionControlRequestTokens[kind] == token else { return }
            self.finishSessionControlRequest(kind)
            self.lastError = "\(kind) 请求超时，请检查 Mobile Gateway。"
        }
    }

    private func finishSessionControlRequest(_ kind: String) {
        sessionControlRequestTokens[kind] = nil
        sessionControlLoadingKinds.remove(kind)
        if kind == "models" {
            pendingModelsSessionId = nil
            isPendingGlobalModelsRequest = false
        }
    }

    private func setGlobalDefault(target: String, value: String) {
        guard gateway.state.isConnected else {
            lastError = "请先连接 DeepSeek Harness"
            return
        }
        beginDefaultConfigurationRequest("set-default")
        gateway.setDefault(target: target, value: value)
    }

    private func beginDefaultConfigurationRequest(_ kind: String) {
        let token = UUID()
        defaultConfigurationRequestTokens[kind] = token
        defaultConfigurationLoadingKinds.insert(kind)
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(12))
            guard let self, self.defaultConfigurationRequestTokens[kind] == token else { return }
            self.finishDefaultConfigurationRequest(kind)
            self.lastError = "\(kind) 请求超时，请检查 Mobile Gateway v0.1.11。"
        }
    }

    private func finishDefaultConfigurationRequest(_ kind: String) {
        defaultConfigurationRequestTokens[kind] = nil
        defaultConfigurationLoadingKinds.remove(kind)
    }

    private func applyPermissions(_ incoming: GatewaySessionPermissions, sessionId: String, source: String) {
        let current = incoming.currentValue ?? incoming.preset
        if let current, !Self.permissionPresets.contains(current) {
            reportInvalidPermission(current, sessionId: sessionId, source: source)
            return
        }
        var permissions = incoming
        permissions.options = (incoming.options ?? []).filter { Self.permissionPresets.contains($0.value) }
        sessionPermissions[sessionId] = permissions
    }

    private func reportInvalidPermission(_ value: String, sessionId: String, source: String) {
        finishSessionControlRequest("permission")
        finishSessionControlRequest("permission-options")
        let detail = "权限状态 \"\(value)\" 无效；仅支持 read-only、workspace-write、danger-full-access。"
        lastError = detail
        notice("权限状态错误 · \(source)", detail, sessionId: sessionId, isError: true)
    }

    private func sessionControlKind(from value: String) -> String? {
        ["permission-options", "select-model", "context-usage", "session-stats", "permission", "models"]
            .first { value.localizedCaseInsensitiveContains($0) }
    }
    private func upsertSession(id: String, title: String?) {
        if let index = sessions.firstIndex(where: { $0.id == id }) {
            if let title, !title.isEmpty, sessions[index].title.hasPrefix("新建") || sessions[index].title.hasPrefix("远端") { sessions[index].title = title }
        } else {
            sessions.insert(SessionSummary(id: id, title: title ?? "远端会话 \(id.prefix(8))", lastActivity: .now, isRunning: false, hasUnread: false), at: 0)
        }
    }

    private func persistConversationScrollPositions() {
        if let data = try? JSONEncoder().encode(conversationScrollAnchors) {
            UserDefaults.standard.set(data, forKey: "gateway.conversationScrollAnchors")
        }
        if let data = try? JSONEncoder().encode(manuallyPositionedSessionIds) {
            UserDefaults.standard.set(data, forKey: "gateway.manuallyPositionedSessionIds")
        }
    }
    private func markRead(_ id: String) {
        guard let index = sessions.firstIndex(where: { $0.id == id }) else { return }
        sessions[index].hasUnread = false
    }
    private func persistSessions() {
        if let data = try? JSONEncoder().encode(sessions) { UserDefaults.standard.set(data, forKey: "gateway.sessions") }
    }
}
