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
    @Published var selectedTab = 0
    @Published var selectedSessionId: String?
    @Published var sessions: [SessionSummary] = [] { didSet { persistSessions() } }
    @Published var workspaces: [GatewayWorkspace] = []
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
    @Published var protocolNotices: [GatewayNotice] = []
    @Published var endpoint: String { didSet { UserDefaults.standard.set(endpoint, forKey: "gateway.endpoint") } }
    @Published var interfaceStyle: InterfaceStyle = .system
    @Published var lastError: String?
    @Published var waitingForNewSession = false
    @Published var isRefreshing = false
    @Published var modelCatalogs: [String: GatewayModelCatalog] = [:]
    @Published var sessionPermissions: [String: GatewaySessionPermissions] = [:]
    @Published var contextSnapshots: [String: GatewayContextSnapshot] = [:]
    @Published var sessionControlLoadingKinds: Set<String> = []
    @Published var workspaceScrollAnchor: String?
    @Published private(set) var conversationScrollAnchors: [String: String] = [:]
    @Published private(set) var manuallyPositionedSessionIds: Set<String> = []

    let gateway = GatewayClient()
    private var pendingHistorySessionId: String?
    private var historyRequestTokens: [String: UUID] = [:]
    private var conversationProjectionTasks: [String: Task<Void, Never>] = [:]
    private var pendingModelsSessionId: String?
    private var pendingModelSelectionSessionId: String?
    private var pendingPermissionOptionsSessionId: String?
    private var sessionControlRequestTokens: [String: UUID] = [:]
    private static let permissionPresets: Set<String> = ["read-only", "workspace-write", "danger-full-access"]

    init() {
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
    func conversationScrollAnchor(for sessionId: String) -> String? { conversationScrollAnchors[sessionId] }
    func hasManualConversationPosition(for sessionId: String) -> Bool { manuallyPositionedSessionIds.contains(sessionId) }
    func rememberConversationScrollAnchor(_ anchor: String?, for sessionId: String, manual: Bool) {
        if let anchor { conversationScrollAnchors[sessionId] = anchor }
        else { conversationScrollAnchors.removeValue(forKey: sessionId) }
        if manual { manuallyPositionedSessionIds.insert(sessionId) }
        persistConversationScrollPositions()
    }
    var activeWorkspace: GatewayWorkspace? {
        if let id = selectedSessionId, let workspace = workspaces.first(where: { $0.sessionIds.contains(id) }) { return workspace }
        return workspaces.first
    }

    func connect() { gateway.connect(to: endpoint) }
    func refreshRemoteState() {
        guard gateway.state.isConnected else { return }
        isRefreshing = true
        gateway.requestWorkspaces()
        gateway.requestSessions()
        gateway.requestHost()
    }
    func startNewSession() {
        selectedSessionId = nil
        waitingForNewSession = false
        selectedTab = 1
        gateway.subscribe(sessionId: nil)
    }
    func open(_ session: SessionSummary) {
        selectedSessionId = session.id
        selectedTab = 1
        markRead(session.id)
        gateway.subscribe(sessionId: session.id)
        loadHistory(for: session.id)
        refreshSessionControls(for: session.id)
    }
    func loadHistory(for sessionId: String, older: Bool = false) {
        guard gateway.state.isConnected else {
            lastError = "WebSocket 尚未连接，无法加载历史记录"
            return
        }
        pendingHistorySessionId = sessionId
        let token = UUID()
        historyRequestTokens[sessionId] = token
        historyLoadingSessionIds.insert(sessionId)
        historyLoadProgress[sessionId] = HistoryLoadProgress(loaded: 0, total: nil)
        // Keep any in-memory projection visible while the gateway refreshes.
        // The response is merged by sequence and replaces this cache only after
        // a usable projected batch is ready.
        let before = older ? events[sessionId]?.map(\.seq).min() : nil
        gateway.requestHistory(sessionId: sessionId, beforeSeq: before, maxMessages: 60)
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(20))
            guard let self, self.historyRequestTokens[sessionId] == token else { return }
            self.historyRequestTokens[sessionId] = nil
            self.historyLoadingSessionIds.remove(sessionId)
            self.historyLoadProgress[sessionId] = nil
            if self.pendingHistorySessionId == sessionId { self.pendingHistorySessionId = nil }
            self.lastError = "历史记录加载超时，请重试"
        }
    }
    func showWorkspace() {
        selectedTab = 0
        gateway.subscribe(sessionId: nil)
        refreshRemoteState()
    }
    func showSettings() {
        selectedTab = 2
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
    func browseDirectories(path: String? = nil) { gateway.requestDirectories(path: path) }
    func createWorkspace(path: String) { gateway.createWorkspace(path: path) }
    func refreshSessionControls(for sessionId: String) {
        guard gateway.state.isConnected else { return }
        pendingModelsSessionId = sessionId
        pendingPermissionOptionsSessionId = sessionId
        beginSessionControlRequest("models")
        beginSessionControlRequest("permission-options")
        beginSessionControlRequest("context-usage")
        gateway.requestModels(sessionId: sessionId)
        gateway.requestPermissionOptions(sessionId: sessionId)
        gateway.requestContextUsage(sessionId: sessionId)
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
        gateway.sendMessage(text: trimmed, sessionId: selectedSessionId)
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
        case "models":
            if let id = frame.sessionId ?? pendingModelsSessionId {
                modelCatalogs[id] = GatewayModelCatalog(
                    current: frame.current,
                    routable: frame.routable ?? false,
                    groups: frame.groups ?? []
                )
            }
            pendingModelsSessionId = nil
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
        case "directories":
            directoryPath = frame.path
            directoryHome = frame.home
            directoryCrumbs = frame.crumbs ?? []
            directoryEntries = frame.entries ?? []
            notice("目录已加载", "\(frame.path ?? "") · \(directoryEntries.count) 项")
        case "workspace-create":
            if let workspace = frame.workspace {
                if let index = workspaces.firstIndex(where: { $0.id == workspace.id }) { workspaces[index] = workspace } else { workspaces.append(workspace) }
                notice(frame.created == true ? "工作区已创建" : "工作区已存在", workspace.path)
                refreshRemoteState()
            }
        case "error":
            waitingForNewSession = false
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
        // Invalidate the network timeout; processing now has its own token.
        let processingToken = UUID()
        historyRequestTokens[id] = processingToken
        historyLoadProgress[id] = HistoryLoadProgress(loaded: 0, total: rawEvents.count)

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
                self.historyLoadProgress[id] = HistoryLoadProgress(loaded: loaded, total: normalized.count)

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
            self.historyHasMore[id] = frame.hasMore ?? false
            self.finishHistoryLoading(id)
            self.scheduleConversationProjection(for: id)
            self.notice("历史记录已加载", "\(normalized.count) 个事件\(frame.hasMore == true ? " · 可加载更早记录" : "")", sessionId: id)
        }
    }
    private func finishHistoryLoading(_ sessionId: String) {
        historyRequestTokens[sessionId] = nil
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
            } else {
                sessions.append(SessionSummary(id: item.sessionId, title: item.blank ? "空白会话 \(item.sessionId.prefix(8))" : title, lastActivity: date, isRunning: item.running, hasUnread: false))
            }
        }
        sessions.removeAll { archivedSessionIds.contains($0.id) }
        sessions.sort { $0.lastActivity > $1.lastActivity }
    }
    private func applyEvent(_ record: SessionEvent) {
        let event = record.event
        if event.type == "turn/end", record.sessionId == selectedSessionId {
            beginSessionControlRequest("context-usage")
            gateway.requestContextUsage(sessionId: record.sessionId)
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
        ["permission-options", "select-model", "context-usage", "permission", "models"]
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
