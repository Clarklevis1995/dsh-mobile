import SwiftUI

enum InterfaceStyle: String, CaseIterable, Identifiable {
    case system, light, dark
    var id: String { rawValue }
    var title: String { switch self { case .system: String(localized: "跟随系统"); case .light: String(localized: "浅色"); case .dark: String(localized: "深色") } }
    var colorScheme: ColorScheme? { switch self { case .system: nil; case .light: .light; case .dark: .dark } }
}

enum AppLanguage: String, CaseIterable, Identifiable {
    case system
    case simplifiedChinese = "zh-Hans"
    case english = "en"

    static let preferenceKey = "app.language"
    private static let appleLanguagesKey = "AppleLanguages"

    var id: String { rawValue }

    var title: String {
        switch self {
        case .system: String(localized: "跟随系统")
        case .simplifiedChinese: "简体中文"
        case .english: "English"
        }
    }

    static func load(from defaults: UserDefaults = .standard) -> AppLanguage {
        guard let value = defaults.string(forKey: preferenceKey),
              let language = AppLanguage(rawValue: value) else {
            return .system
        }
        return language
    }

    func apply(to defaults: UserDefaults = .standard) {
        defaults.set(rawValue, forKey: Self.preferenceKey)
        switch self {
        case .system:
            defaults.removeObject(forKey: Self.appleLanguagesKey)
        case .simplifiedChinese, .english:
            defaults.set([rawValue], forKey: Self.appleLanguagesKey)
        }
    }
}

@MainActor
final class AppStore: ObservableObject {
    static let ungroupedWorkspaceID = "__ungrouped__"

    @Published var selectedSessionId: String?
    @Published var sessions: [SessionSummary] = []
    @Published var workspaces: [GatewayWorkspace] = []
    @Published var selectedWorkspaceId: String? {
        didSet { preferences.selectedWorkspaceID = selectedWorkspaceId }
    }
    @Published var archivedSessionIds: Set<String> = []
    /// Raw protocol storage. UI updates are driven by the throttled compact
    /// conversation projection instead of invalidating every view for every
    /// token frame.
    var events: [String: [SessionEvent]] = [:]
    /// Compact presentation snapshots are intentionally not published through
    /// AppStore. Token deltas go straight to a session-scoped timeline so they
    /// cannot invalidate the header, pager, composer, or trajectory page.
    private(set) var renderedConversationItems: [String: [ConversationItem]] = [:]
    @Published private(set) var conversationContentSessionIds: Set<String> = []
    @Published var historyHasMore: [String: Bool] = [:]
    @Published var historyLoadingSessionIds: Set<String> = []
    @Published var historyLoadingOlderSessionIds: Set<String> = []
    @Published var historyLoadProgress: [String: HistoryLoadProgress] = [:]
    @Published var searchResults: [GatewaySearchItem] = []
    @Published var hostSnapshot: GatewayHostSnapshot?
    @Published var directoryPath: String?
    @Published var directoryHome: String?
    @Published var directoryCrumbs: [GatewayDirectoryItem] = []
    @Published var directoryEntries: [GatewayDirectoryItem] = []
    @Published var directoryIsLoading = false
    @Published var directoryCreationIsLoading = false
    @Published private(set) var createdDirectoryPathToReveal: String?
    @Published var workspaceCreationIsLoading = false
    @Published var protocolNotices: [GatewayNotice] = []
    @Published var endpoint: String { didSet { preferences.endpoint = endpoint } }
    @Published var interfaceStyle: InterfaceStyle = .system
    @Published var appLanguage: AppLanguage {
        didSet { appLanguage.apply() }
    }
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
    @Published private(set) var pendingQuestionRequests: [GatewayPendingQuestionRequest] = []
    @Published private(set) var questionRequestStatuses: [String: GatewayQuestionRequestStatus] = [:]
    @Published private(set) var supportsImages = false

    let gateway = GatewayClient()
    private let preferences: AppPreferences
    /// SessionList 的唯一业务状态来源；Swift 属性只是 UI/持久化快照。
    private let kmpSessionListStore: KMPSessionListStoreAdapter
    private var historyState = HistoryState()
    private let historySyncEngine = HistorySyncEngine()
    /// The remote session activity timestamp covered by a completed history load.
    /// This intentionally remains an in-memory cache: events are not persisted
    /// across launches, so a fresh process must fetch history again.
    private var conversationProjectionDrivers: [String: ConversationProjectionDriver] = [:]
    private var conversationTimelines: [String: ConversationTimeline] = [:]
    /// Invalidates an in-flight cold projection when a completed history
    /// baseline is atomically installed for the same session.
    private var conversationProjectionEpochs: [String: Int] = [:]
    /// Incremental per-session projector state. Kept alive across live
    /// updates so most projection ticks only fold the handful of events that
    /// arrived since the previous tick, instead of replaying the whole
    /// session; see `projectIncrementally(for:)`.
    private var conversationProjectors: [String: ConversationProjector] = [:]
    /// Decoded bytes live outside the raw/history message models. A bounded
    /// memory layer fronts a seven-day, purgeable on-disk cache.
    private let imageAttachmentCache = ImageAttachmentCache()
    private var attachmentLoader = AttachmentLoader()
    private var pendingModelsSessionId: String?
    private var isPendingGlobalModelsRequest = false
    private var pendingModelSelectionSessionId: String?
    private var pendingPermissionOptionsSessionId: String?
    private var pendingDirectoryCreationParentPath: String?
    private let sessionControlRequestTracker = RequestTracker()
    private let defaultConfigurationRequestTracker = RequestTracker()
    /// Navigation preparation is intentionally cheap. Remote activation begins
    /// from the destination lifecycle, after NavigationStack installs its bar.
    private var preparedConversationActivationKey: String?
    private var activeConversationActivationKey: String?
    private var presentsNextConnectionFailureAsAlert = true
    private var hasHandledColdLaunchConnection = false
    private let backgroundExecutionController = AgentBackgroundExecutionController()
#if DEBUG
    /// 只记录路由差异；影子 effect 永远不执行，也不参与产品状态写入。
    private let kmpShadowValidator = KMPShadowValidator()
#endif
    private static let permissionPresets: Set<String> = ["read-only", "workspace-write", "danger-full-access"]
    private static let defaultPermissionPresets: Set<String> = ["ask", "read-only", "workspace-write", "danger-full-access"]
    private static let defaultConfigurationRequestKinds: Set<String> = [
        "agent-presets", "defaults", "default-model", "set-default", "save-default-model"
    ]
    private static let newConversationActivationKey = "__new-conversation__"

    init(
        preferences: AppPreferences = UserDefaultsAppPreferences(),
        sessionListBridge: (any KMPSessionListStoreBridging)? = nil
    ) {
        self.preferences = preferences
        let persistedSessions = preferences.loadSessions()
        let kmpSessionListStore = KMPSessionListStoreAdapter(
            sessions: persistedSessions,
            bridge: sessionListBridge
        )
        self.kmpSessionListStore = kmpSessionListStore
        appLanguage = AppLanguage.load()
        selectedWorkspaceId = preferences.selectedWorkspaceID
        endpoint = preferences.endpoint
        selectedSessionId = kmpSessionListStore.snapshot.selectedSessionId
        sessions = kmpSessionListStore.snapshot.persistedSessions
        archivedSessionIds = kmpSessionListStore.snapshot.archivedSessionIDSet
        if let error = kmpSessionListStore.initializationError {
            lastError = error.localizedDescription
        }
        preferences.performMigrations()
        gateway.onFrame = { [weak self] frame in self?.handle(frame) }
        gateway.onConnectionFailure = { [weak self] detail in
            self?.handleConnectionFailure(detail)
        }
        backgroundExecutionController.onBackgroundAllowanceExpired = { [weak self] in
            self?.gateway.backgroundExecutionDidExpire()
        }
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
    var selectedPendingQuestionRequest: GatewayPendingQuestionRequest? {
        guard let selectedSessionId else { return nil }
        return pendingQuestionRequests.first { $0.sessionId == selectedSessionId }
    }
    func imageData(for attachmentId: String) -> Data? {
        imageAttachmentCache.data(for: attachmentId)
    }
    func conversationTimeline(for sessionId: String) -> ConversationTimeline {
        if let timeline = conversationTimelines[sessionId] { return timeline }
        let timeline = ConversationTimeline()
        conversationTimelines[sessionId] = timeline
        if let items = renderedConversationItems[sessionId], !items.isEmpty {
            timeline.publish(items)
        }
        return timeline
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

    func connect() {
        resetOutstandingRequests()
        presentsNextConnectionFailureAsAlert = true
        lastError = nil
        gateway.connect(to: endpoint)
    }

    /// Restores a previously paired gateway without turning the initial,
    /// intentionally unpaired state into a transport error.
    func connectOnColdLaunchIfPaired() {
        guard !hasHandledColdLaunchConnection else { return }
        hasHandledColdLaunchConnection = true
        guard gateway.hasStoredCredential(for: endpoint) else {
            lastError = String(localized: "尚未连接到 DeepSeek Harness。请点击主页右上角的 🔑 按钮，扫描配对二维码或手动输入 Token 进行连接。")
            return
        }
        connect()
    }

    func handleScenePhase(_ phase: ScenePhase) {
        switch phase {
        case .active:
            backgroundExecutionController.applicationDidBecomeActive()
            gateway.applicationDidBecomeActive()
        case .background:
            backgroundExecutionController.applicationDidEnterBackground()
            imageAttachmentCache.removeExpiredFiles()
            gateway.applicationDidEnterBackground(
                keepConnectionAlive: backgroundExecutionController.keepsConnectionAlive
            )
        case .inactive:
            break
        @unknown default:
            break
        }
    }

    func pair(usingQRCode rawValue: String, presentsFailureAlert: Bool = true) throws {
        let payload = try PairingPayloadParser.parse(rawValue)
        resetOutstandingRequests()
        presentsNextConnectionFailureAsAlert = presentsFailureAlert
        lastError = nil
        endpoint = payload.publicUrl
        gateway.connectForPairing(payload)
    }

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
            lastError = String(localized: "agent.preset.default.invalid", defaultValue: "无法将未知或已损坏的 Agent 预设设为默认值：\(id)")
            return
        }
        setGlobalDefault(target: "agent-preset", value: id)
    }
    func setDefaultPermission(_ value: String) {
        guard Self.defaultPermissionPresets.contains(value) else {
            lastError = String(localized: "permissions.default.unsupported", defaultValue: "不支持的默认权限：\(value)")
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
        reduceSessionControl(.modelsRequestTargeted(sessionID: nil))
        beginSessionControlRequest("models")
        gateway.requestModels()
    }
    func saveDefaultModel(provider: String, model: String, reasoningEffort: String?) {
        guard gateway.state.isConnected else {
            lastError = String(localized: "请先连接 DeepSeek Harness")
            return
        }
        beginDefaultConfigurationRequest("save-default-model")
        gateway.saveDefaultModel(provider: provider, model: model, reasoningEffort: reasoningEffort)
    }
    func prepareNewConversation() {
        reduceSessionList(.select(nil))
        waitingForNewSession = false
        preparedConversationActivationKey = Self.newConversationActivationKey
        activeConversationActivationKey = nil
    }

    func prepareConversation(for session: SessionSummary) {
        reduceSessionList(.select(session.id))
        waitingForNewSession = false
        preparedConversationActivationKey = session.id
        activeConversationActivationKey = nil
    }

    /// Activates the already-pushed conversation. The yield points separate
    /// independent request groups so they cannot monopolize a transition frame.
    func activatePreparedConversation(sessionID: String?) async {
        let activationKey = sessionID ?? Self.newConversationActivationKey
        guard preparedConversationActivationKey == activationKey,
              activeConversationActivationKey != activationKey,
              sessionID == selectedSessionId,
              gateway.state.isConnected else { return }

        activeConversationActivationKey = activationKey
        guard !Task.isCancelled else { return }

        if let sessionID {
            markRead(sessionID)
            gateway.subscribe(sessionId: sessionID)
        } else {
            gateway.subscribe(sessionId: nil)
        }

        await Task.yield()
        guard !Task.isCancelled,
              preparedConversationActivationKey == activationKey else { return }

        if agentPresets.isEmpty {
            beginDefaultConfigurationRequest("agent-presets")
            gateway.requestAgentPresets()
        }

        if let sessionID,
           let session = sessions.first(where: { $0.id == sessionID }),
           shouldRefreshHistory(for: session) {
            loadHistory(for: sessionID)
        }

        await Task.yield()
        guard !Task.isCancelled,
              preparedConversationActivationKey == activationKey else { return }

        if let sessionID {
            refreshSessionControls(for: sessionID)
        } else {
            // An unsaved session mirrors the deployment defaults before send.
            refreshDefaultConfiguration()
        }
    }

    private func shouldRefreshHistory(for session: SessionSummary) -> Bool {
        guard !historyLoadingSessionIds.contains(session.id) else { return false }
        guard let syncedTimestamp = historyState.sessions[session.id]?.syncedActivityTimestamp else { return true }
        let syncedActivity = Date(timeIntervalSince1970: syncedTimestamp)
        // A completed history baseline may subsequently be extended by the
        // subscribed live tail. Both sources are already present locally, so
        // compare the remote summary against the newest covered activity
        // instead of the older history-request timestamp alone.
        let latestLocalActivity = events[session.id]?.last?.date ?? syncedActivity
        let coveredActivity = max(syncedActivity, latestLocalActivity)
        return session.lastActivity > coveredActivity
    }
    func loadHistory(for sessionId: String, older: Bool = false) {
        guard gateway.state.isConnected else {
            lastError = String(localized: "WebSocket 尚未连接，无法加载历史记录")
            return
        }
        let result = reduceHistory(.start(
            sessionID: sessionId,
            older: older,
            hasLocalEvents: !events[sessionId, default: []].isEmpty,
            earliestLocalSequence: events[sessionId]?.first?.seq
        ))
        if case .requestPage(let beforeSequence) = result {
            requestHistoryPage(for: sessionId, beforeSeq: beforeSequence)
        }
    }

    private func requestHistoryPage(for sessionId: String, beforeSeq: Int?) {
        historySyncEngine.beginRequest(sessionID: sessionId, timeout: .seconds(20)) { [weak self] in
            guard let self else { return }
            let hasUsableLocalContent = !self.events[sessionId, default: []].isEmpty
                || !self.renderedConversationItems[sessionId, default: []].isEmpty
            _ = self.reduceHistory(.timedOut(sessionID: sessionId))
            self.scheduleConversationProjection(for: sessionId)
            if hasUsableLocalContent {
                self.notice(
                    String(localized: "历史记录刷新超时"),
                    String(localized: "已保留本地内容并继续接收实时事件"),
                    sessionId: sessionId
                )
            } else {
                self.lastError = String(localized: "历史记录加载超时，请重试")
            }
        }
        gateway.requestHistory(
            sessionId: sessionId,
            beforeSeq: beforeSeq,
            maxMessages: historySyncEngine.configuration.pageMessageLimit,
            maxBytes: historySyncEngine.configuration.pageByteBudget,
            view: "conversation"
        )
    }
    func resumeWorkspace() {
        preparedConversationActivationKey = nil
        activeConversationActivationKey = nil
        gateway.subscribe(sessionId: nil)
        refreshRemoteState()
    }
    func addKnownSession(_ id: String) {
        let normalized = id.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalized.isEmpty else { return }
        reduceSessionList(.knownSessionAdded(normalized))
    }
    func search(_ query: String) {
        let normalized = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if normalized.isEmpty { searchResults = [] } else { gateway.searchSessions(normalized) }
    }
    func browseDirectories(path: String? = nil) {
        guard gateway.state.isConnected else {
            lastError = String(localized: "请先连接 DeepSeek Harness")
            return
        }
        directoryIsLoading = true
        gateway.requestDirectories(path: path)
    }
    func createDirectory(parentPath: String, name: String) {
        guard gateway.state.isConnected else {
            lastError = String(localized: "请先连接 DeepSeek Harness")
            return
        }
        let normalizedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalizedName.isEmpty else {
            lastError = String(localized: "文件夹名称不能为空")
            return
        }
        pendingDirectoryCreationParentPath = parentPath
        directoryCreationIsLoading = true
        gateway.createDirectory(path: parentPath, name: normalizedName)
    }
    func acknowledgeCreatedDirectoryReveal(path: String) {
        guard createdDirectoryPathToReveal == path else { return }
        createdDirectoryPathToReveal = nil
    }
    func createWorkspace(path: String) {
        guard gateway.state.isConnected else {
            lastError = String(localized: "请先连接 DeepSeek Harness")
            return
        }
        workspaceCreationIsLoading = true
        gateway.createWorkspace(path: path)
    }
    func refreshSessionControls(for sessionId: String) {
        guard gateway.state.isConnected else { return }
        reduceSessionControl(.modelsRequestTargeted(sessionID: sessionId))
        reduceSessionControl(.permissionOptionsTargeted(sessionID: sessionId))
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
        reduceSessionControl(.modelSelectionTargeted(sessionID: sessionId))
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
            lastError = String(localized: "permissions.error.invalid", defaultValue: "不支持的访问权限：\(name)。可用状态为 read-only、workspace-write、danger-full-access。")
            return
        }
        beginSessionControlRequest("permission")
        gateway.setPermission(sessionId: sessionId, name: name)
    }
    @discardableResult
    func send(_ text: String, images: [GatewayOutgoingImage] = []) -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty || !images.isEmpty else { return false }
        guard gateway.state.isConnected else { lastError = String(localized: "请先在设置中连接 DeepSeek Harness"); return false }
        guard images.isEmpty || supportsImages else {
            lastError = String(localized: "当前 Mobile Gateway 不支持图片，请升级并重启 dsh web。")
            return false
        }
        waitingForNewSession = selectedSessionId == nil
        beginAgentBackgroundExecution(for: selectedSessionId, startsNewTurn: true)
        gateway.sendMessage(
            text: trimmed,
            images: images,
            sessionId: selectedSessionId,
            workspaceId: selectedSessionId == nil ? activeWorkspace?.id : nil
        )
        return true
    }

    func answerQuestion(_ request: GatewayPendingQuestionRequest, answers: [GatewayQuestionAnswer]) {
        reduceQuestion(.submit(
            request: request,
            submission: .answer(answers),
            isConnected: gateway.state.isConnected
        ))
        guard questionRequestStatuses[request.rpcId] == .submitting(.answer) else { return }
        beginAgentBackgroundExecution(for: request.sessionId, startsNewTurn: false)
        gateway.answerQuestion(rpcId: request.rpcId, sessionId: request.sessionId, answers: answers)
    }

    func cancelQuestion(_ request: GatewayPendingQuestionRequest) {
        reduceQuestion(.submit(
            request: request,
            submission: .cancel,
            isConnected: gateway.state.isConnected
        ))
        guard questionRequestStatuses[request.rpcId] == .submitting(.cancel) else { return }
        gateway.cancelQuestion(rpcId: request.rpcId, sessionId: request.sessionId)
    }
    func title(for sessionId: String) -> String { sessions.first(where: { $0.id == sessionId })?.title ?? "DeepSeek Harness" }

    private func handle(_ frame: GatewayFrame) {
        let context = GatewayFrameRoutingContext(
            selectedSessionID: selectedSessionId,
            pendingHistorySessionID: historyState.pendingSessionID,
            pendingModelsSessionID: pendingModelsSessionId,
            isPendingGlobalModelsRequest: isPendingGlobalModelsRequest,
            pendingModelSelectionSessionID: pendingModelSelectionSessionId,
            pendingPermissionOptionsSessionID: pendingPermissionOptionsSessionId
        )
        let route = GatewayFrameRouter.route(frame, context: context)
#if DEBUG
        kmpShadowValidator.validate(frame: frame, context: context, swiftRoute: route)
#endif
        handle(route)
    }

    private func handle(_ route: GatewayFrameRoute) {
        switch route {
        case .connection(let route): handleConnectionRoute(route)
        case .content(let route): handleContentRoute(route)
        case .control(let route): handleControlRoute(route)
        case .workspace(let route): handleWorkspaceRoute(route)
        case .question(let route): handleQuestionRoute(route)
        case .failure(let payload): handleFailure(payload)
        case .ignored: break
        case .unknown(let kind): notice(String(localized: "未知网关响应"), kind)
        }
    }

    private func handleConnectionRoute(_ route: GatewayConnectionRoute) {
        switch route {
        case .paired(let deviceName):
            notice(
                String(localized: "设备配对成功"),
                deviceName ?? String(localized: "长期凭据已安全保存到 Keychain")
            )
        case .hello(let payload):
            reduceQuestion(.reset)
            supportsImages = payload.protocolVersion >= 3 && payload.capabilities.contains("images")
            attachmentLoader.reset()
            presentsNextConnectionFailureAsAlert = true
            let authentication = payload.authenticated
                ? String(localized: "设备鉴权成功")
                : String(localized: "Debug 未鉴权")
            notice(
                String(localized: "网关已连接"),
                String(
                    localized: "gateway.connected.detail",
                    defaultValue: "\(authentication) · Mobile protocol v\(payload.protocolVersion) · \(payload.clients) 个客户端"
                )
            )
            for (sessionID, records) in events {
                enqueueImageAttachments(in: records, sessionId: sessionID)
            }
            refreshRemoteState()
            refreshDefaultConfiguration()
            if preparedConversationActivationKey != nil {
                Task { [weak self] in
                    guard let self else { return }
                    await self.activatePreparedConversation(sessionID: self.selectedSessionId)
                }
            }
        case .pong(let timestamp):
            let detail = timestamp.map {
                Date(timeIntervalSince1970: $0 / 1_000).formatted(date: .omitted, time: .standard)
            } ?? "pong"
            notice(String(localized: "心跳正常"), detail)
        case .subscribed(let sessionID):
            let detail = sessionID.map {
                String(localized: "events.subscribe.single", defaultValue: "仅接收 \($0.prefix(12))…")
            } ?? String(localized: "接收全部会话事件")
            notice(
                String(localized: "notice.event.subscription", defaultValue: "事件订阅"),
                detail,
                sessionId: sessionID
            )
        }
    }

    private func handleContentRoute(_ route: GatewayContentRoute) {
        switch route {
        case .sent(let sessionID, let command):
            handleSent(sessionID: sessionID, command: command)
        case .liveEvent(let record):
            merge(record)
            enqueueImageAttachments(record.event.images ?? [], sessionId: record.sessionId)
        case .workspaces(let received, let archivedSessionIDs):
            workspaces = received
            if selectedWorkspaceId == nil || (
                selectedWorkspaceId != Self.ungroupedWorkspaceID &&
                !workspaces.contains(where: { $0.id == selectedWorkspaceId })
            ) {
                selectedWorkspaceId = workspaces.first?.id ?? Self.ungroupedWorkspaceID
            }
            reduceSessionList(.setArchivedSessionIDs(archivedSessionIDs))
            notice(
                String(localized: "notice.workspaces.synced", defaultValue: "工作区已同步"),
                String(localized: "workspaces.count", defaultValue: "\(workspaces.count) 个工作区")
            )
        case .sessions(let received):
            reduceSessionList(.remoteSessionsReceived(received))
            isRefreshing = false
            notice(
                String(localized: "notice.sessions.synced", defaultValue: "会话列表已同步"),
                String(localized: "sessions.count", defaultValue: "\(sessions.count) 个会话")
            )
        case .history(let payload):
            applyHistoryRebased(payload)
        case .attachment(let payload):
            handleImageAttachment(payload)
        case .search(let received, let hasMore):
            searchResults = received
            notice(
                String(localized: "notice.search.finished", defaultValue: "搜索完成"),
                String(
                    localized: "search.results.count",
                    defaultValue: "\(received.count) 条结果\(hasMore ? String(localized: "search.results.more", defaultValue: "，还有更多") : "")"
                )
            )
        case .host(let snapshot):
            hostSnapshot = snapshot
            notice(
                String(localized: "宿主信息"),
                [snapshot.version, snapshot.provider, snapshot.model].compactMap { $0 }.joined(separator: " · ")
            )
        }
    }

    private func handleControlRoute(_ route: GatewayControlRoute) {
        switch route {
        case .action(let action, let finishRequest):
            reduceSessionControl(action)
            if let finishRequest {
                if Self.defaultConfigurationRequestKinds.contains(finishRequest) {
                    finishDefaultConfigurationRequest(finishRequest)
                } else {
                    finishSessionControlRequest(finishRequest)
                }
            }
        case .saveDefaultModel(let saved):
            if let saved {
                reduceSessionControl(.defaultModelReceived(saved))
                notice(
                    String(localized: "默认模型已更新"),
                    [saved.model, saved.reasoningEffort].compactMap { $0 }.joined(separator: " · ")
                )
            } else {
                lastError = String(localized: "服务端未确认默认模型更新。")
            }
            finishDefaultConfigurationRequest("save-default-model")
        case .setDefault(let applied, let target, let value):
            if applied, let target, let value {
                reduceSessionControl(.globalDefaultApplied(target: target, value: value))
                notice(
                    String(localized: "notice.defaults.updated", defaultValue: "默认配置已更新"),
                    String(localized: "defaults.updated.detail", defaultValue: "\(target) · \(value)")
                )
                finishDefaultConfigurationRequest("set-default")
                beginDefaultConfigurationRequest("defaults")
                gateway.requestDefaults()
            } else {
                finishDefaultConfigurationRequest("set-default")
                lastError = String(localized: "服务端未确认默认配置更新。")
            }
        case .modelSelected(let sessionID, let selection):
            if let sessionID, let selection {
                reduceSessionControl(.modelSelected(sessionID: sessionID, selection: selection))
                notice(
                    String(localized: "模型已切换"),
                    [selection.model, selection.reasoningEffort].compactMap { $0 }.joined(separator: " · "),
                    sessionId: sessionID
                )
            }
            reduceSessionControl(.modelSelectionResolved)
            finishSessionControlRequest("select-model")
        case .permissionOptions(let sessionID, let permissions):
            if let sessionID, let permissions {
                applyPermissions(permissions, sessionId: sessionID, source: "permission-options")
            }
            reduceSessionControl(.permissionOptionsResolved)
            finishSessionControlRequest("permission-options")
        case .permissionSelected(let sessionID, let value):
            if let sessionID, let value {
                if Self.permissionPresets.contains(value) {
                    reduceSessionControl(.permissionSelected(sessionID: sessionID, value: value))
                    notice(String(localized: "权限已切换"), value, sessionId: sessionID)
                } else {
                    reportInvalidPermission(value, sessionId: sessionID, source: "permission")
                }
            }
            finishSessionControlRequest("permission")
        }
    }

    private func handleWorkspaceRoute(_ route: GatewayWorkspaceRoute) {
        switch route {
        case .directories(let path, let home, let crumbs, let entries):
            directoryIsLoading = false
            directoryPath = path
            directoryHome = home
            directoryCrumbs = crumbs
            directoryEntries = entries
            notice(
                String(localized: "目录已加载"),
                String(localized: "directory.loaded.detail", defaultValue: "\(path ?? "") · \(entries.count) 项")
            )
        case .directoryCreated(let path):
            directoryCreationIsLoading = false
            let parentPath = pendingDirectoryCreationParentPath
            pendingDirectoryCreationParentPath = nil
            if let path {
                createdDirectoryPathToReveal = path
                notice(String(localized: "文件夹已创建"), path)
            }
            // The protocol requires refreshing the parent after creation. Use
            // the request's captured parent so the response cannot accidentally
            // refresh a directory the user navigated to while it was in flight.
            if let parentPath {
                browseDirectories(path: parentPath)
            }
        case .workspaceCreated(let workspace, let created):
            workspaceCreationIsLoading = false
            guard let workspace else { return }
            if let index = workspaces.firstIndex(where: { $0.id == workspace.id }) {
                workspaces[index] = workspace
            } else {
                workspaces.append(workspace)
            }
            selectedWorkspaceId = workspace.id
            notice(
                created ? String(localized: "工作区已创建") : String(localized: "工作区已存在"),
                workspace.path
            )
            refreshRemoteState()
        }
    }

    private func handleQuestionRoute(_ route: GatewayQuestionRoute) {
        switch route {
        case .requested(let action, let sessionID, let preview, let replay):
            reduceQuestion(action)
            notice(
                replay ? String(localized: "待回答问题已恢复") : String(localized: "Agent 正在等待回答"),
                preview.isEmpty ? String(localized: "请回答 Agent 的问题") : preview,
                sessionId: sessionID
            )
        case .invalidRequest(let sessionID):
            notice(
                String(localized: "问题请求无效"),
                String(localized: "缺少 rpcId、sessionId 或 questions。"),
                sessionId: sessionID,
                isError: true
            )
        case .response(let action, let rpcID, let wasNotPending):
            if wasNotPending,
               let request = pendingQuestionRequests.first(where: { $0.rpcId == rpcID }) {
                notice(
                    String(localized: "问题已在其他端处理"),
                    String(localized: "当前响应未生效。"),
                    sessionId: request.sessionId
                )
            }
            reduceQuestion(action)
        case .resolved(let action, let rpcID, let sessionID, let cancelled):
            let pendingSessionID = pendingQuestionRequests.first { $0.rpcId == rpcID }?.sessionId
            reduceQuestion(action)
            let outcome = cancelled ? String(localized: "已跳过") : String(localized: "已提交")
            notice(
                String(localized: "q.outcome.title", defaultValue: "Agent 问题\(outcome)"),
                String(localized: "等待 Agent 继续执行。"),
                sessionId: sessionID ?? pendingSessionID
            )
        }
    }

    private func handleFailure(_ payload: GatewayFailurePayload) {
        waitingForNewSession = false
        if payload.requestType == "directories" { directoryIsLoading = false }
        if payload.requestType == "directory-create" {
            directoryCreationIsLoading = false
            pendingDirectoryCreationParentPath = nil
        }
        if payload.requestType == "workspace-create" { workspaceCreationIsLoading = false }
        if let requestType = payload.requestType,
           Self.defaultConfigurationRequestKinds.contains(requestType) {
            finishDefaultConfigurationRequest(requestType)
        }
        if payload.requestType == "history",
           let sessionID = payload.sessionID ?? historyState.pendingSessionID {
            finishHistoryLoading(sessionID)
        }
        let detail = [payload.code, payload.message].compactMap { $0 }.joined(separator: ": ")
        if let requestType = payload.requestType,
           ["question-answer", "question-cancel"].contains(requestType),
           let rpcID = payload.rpcID
                ?? pendingQuestionRequests.first(where: { $0.sessionId == payload.sessionID })?.rpcId {
            reduceQuestion(.requestFailed(rpcID: rpcID, message: detail))
        }
        let failedRequest = payload.requestType
            ?? payload.code.flatMap(sessionControlKind(from:))
            ?? payload.message.flatMap(sessionControlKind(from:))
        if let failedRequest { finishSessionControlRequest(failedRequest) }
        if payload.requestType == nil, failedRequest == nil {
            for kind in Array(sessionControlLoadingKinds) { finishSessionControlRequest(kind) }
        }
        lastError = detail
        notice(
            String(
                localized: "request.failed.title",
                defaultValue: "请求失败\(payload.requestType.map { " · \($0)" } ?? "")"
            ),
            detail,
            sessionId: payload.sessionID,
            isError: true
        )
    }

    private func handleSent(sessionID: String, command: JSONValue?) {
        backgroundExecutionController.associateSessionIfNeeded(sessionID)
        waitingForNewSession = false
        reduceSessionList(.messageSent(sessionID: sessionID, agentPreset: agentPresetDefault))
        notice(
            command == nil ? String(localized: "消息已发送") : String(localized: "命令已执行"),
            command?.displayText
                ?? String(localized: "session.preview.id", defaultValue: "\(sessionID.prefix(12))…"),
            sessionId: sessionID
        )
        gateway.subscribe(sessionId: sessionID)
        gateway.requestSessions()
        refreshSessionControls(for: sessionID)
    }
    private func applyHistoryRebased(_ payload: GatewayHistoryPayload) {
        guard let id = payload.sessionID else { return }
        // A timed-out request may still produce a late response. It must not
        // overwrite newer live content after the app has already fallen back
        // to subscription-driven incremental rendering.
        guard historyLoadingSessionIds.contains(id), historySyncEngine.isActive(sessionID: id) else { return }
        applyHistoryProjections(payload.projections, sessionId: id)
        let rawEvents = payload.rawEvents
        // Invalidate the network timeout; processing now has its own token.
        let processingToken = historySyncEngine.beginProcessing(sessionID: id)
        _ = reduceHistory(.processingStarted(
            sessionID: id,
            rawEventCount: rawEvents.count,
            hasMore: payload.hasMore
        ))

        Task { [weak self] in
            let normalized = await Task.detached(priority: .userInitiated) {
                rawEvents.map { $0.normalized(sessionId: id) }
            }.value
            guard let self, self.historySyncEngine.isCurrent(processingToken, sessionID: id) else { return }

            // Build history beside the installed live projector. If a rare
            // out-of-order live event lands below the candidate watermark while
            // this background work runs, rebuild against the newer snapshot.
            // Normal strictly ascending chunks take the fast path: they are
            // folded into the candidate once, immediately before commit.
            var sourceEpoch = self.conversationProjectionEpochs[id, default: 0]
            var sourceEvents = self.events[id, default: []]
            while true {
                let rebaseSource = sourceEvents
                var rebase = await Task.detached(priority: .userInitiated) {
                    ConversationHistoryRebase.build(history: normalized, current: rebaseSource)
                }.value
                guard self.historySyncEngine.isCurrent(processingToken, sessionID: id) else { return }

                // Only duplicate/out-of-order live mutations advance this
                // epoch. Normal chunks append concurrently without invalidating
                // the history build and are picked up below by binary-searching
                // the candidate watermark.
                if self.conversationProjectionEpochs[id, default: 0] != sourceEpoch {
                    sourceEpoch = self.conversationProjectionEpochs[id, default: 0]
                    sourceEvents = self.events[id, default: []]
                    continue
                }

                rebase.appendLiveTail(from: self.events[id, default: []])

                // The commit is one main-actor transaction. Invalidate any
                // cold projection that started from the pre-history snapshot,
                // then publish the rebased baseline with the caught-up tail.
                self.conversationProjectionEpochs[id, default: 0] &+= 1
                self.events[id] = rebase.events
                self.conversationProjectors[id] = rebase.projector
                self.publishConversationItems(rebase.projector.items, for: id)
                self.enqueueImageAttachments(in: rebase.events, sessionId: id)
                break
            }

            let result = self.reduceHistory(.pageCommitted(
                sessionID: id,
                eventCount: normalized.count,
                byteCount: payload.byteCount,
                hasMore: payload.hasMore,
                nextBeforeSequence: payload.nextBeforeSequence,
                earliestLocalSequence: self.events[id]?.first?.seq,
                remoteActivityTimestamp: self.sessions.first(where: { $0.id == id })?.lastActivity.timeIntervalSince1970
            ))
            self.handleHistoryResult(result, sessionID: id)
        }
    }
    private func finishHistoryLoading(_ sessionId: String) {
        historySyncEngine.finish(sessionID: sessionId)
        _ = reduceHistory(.cancelled(sessionID: sessionId))
    }
    private func applyHistoryProjections(_ projections: JSONValue?, sessionId: String) {
        guard let values = projections?["values"] else { return }
        reduceSessionControl(.contextReceived(
            sessionID: sessionId,
            asOfSequence: projections?["asOfSeq"]?.doubleValue.map(Int.init),
            tokenUsage: values["tokenUsage"]?.decode(GatewayTokenUsage.self),
            pressure: values["contextPressure"]?.decode(GatewayContextPressure.self),
            breakdown: values["contextBreakdown"]?.decode(GatewayContextBreakdown.self)
        ))
        if let permissions = values["permissions"]?.decode(GatewaySessionPermissions.self) {
            applyPermissions(permissions, sessionId: sessionId, source: "history.projections")
        }
    }
    private func merge(_ record: SessionEvent) {
        let sessionId = record.sessionId
        var sessionEvents = events[sessionId, default: []]
        let mergeResult = HistoryEventMerger.merge(record, into: &sessionEvents)
        events[sessionId] = sessionEvents
        if mergeResult.replacedOrInsertedOutOfOrder {
            // A duplicate or out-of-order frame may fall behind lastSeq and
            // therefore cannot be folded safely into the append-only cache.
            conversationProjectors[sessionId] = nil
            conversationProjectionEpochs[sessionId, default: 0] &+= 1
        }
        applyEvent(record)
        // Once the cache has been fully established, subscribed live events are
        // already the newest local content. Advance the watermark so reopening
        // the session does not download the same history again.
        _ = reduceHistory(.liveEventReceived(
            sessionID: sessionId,
            activityTimestamp: record.date.timeIntervalSince1970
        ))
        scheduleConversationProjection(for: sessionId)
    }

    private func enqueueImageAttachments(in records: [SessionEvent], sessionId: String) {
        enqueueImageAttachments(records.flatMap { $0.event.images ?? [] }, sessionId: sessionId)
    }

    private func enqueueImageAttachments(_ attachments: [GatewayImageAttachment], sessionId: String) {
        guard supportsImages else { return }
        let requests = attachmentLoader.enqueue(attachments, sessionID: sessionId) {
            imageAttachmentCache.data(for: $0) != nil
        }
        requestImageAttachments(requests)
    }

    private func requestImageAttachments(_ requests: [AttachmentLoadRequest]) {
        for request in requests {
            gateway.requestAttachment(
                sessionId: request.sessionID,
                attachmentId: request.attachment.id
            )
        }
    }

    private func handleImageAttachment(_ payload: GatewayAttachmentPayload) {
        let attachment = payload.attachment
        let nextRequests = attachmentLoader.complete(attachmentID: attachment.id) {
            imageAttachmentCache.data(for: $0) != nil
        }
        defer { requestImageAttachments(nextRequests) }
        guard let encoded = payload.base64Data,
              let decoded = Data(base64Encoded: encoded),
              decoded.count == attachment.bytes else {
            notice(
                String(localized: "notice.image.load-failed", defaultValue: "图片加载失败"),
                String(localized: "image.invalid.base64", defaultValue: "附件 \(attachment.id.prefix(12))… 的 Base64 或字节数无效。"),
                sessionId: payload.sessionID,
                isError: true
            )
            return
        }
        imageAttachmentCache.store(decoded, for: attachment.id)
        guard let sessionId = payload.sessionID,
              let items = renderedConversationItems[sessionId] else { return }
        // The row revision also includes cache presence, so this publication
        // replaces only rows whose attachment just became renderable.
        conversationTimeline(for: sessionId).publish(items)
    }

    private func scheduleConversationProjection(for sessionId: String) {
        let driver: ConversationProjectionDriver
        if let existing = conversationProjectionDrivers[sessionId] {
            driver = existing
        } else {
            driver = ConversationProjectionDriver { [weak self] in
                guard let self else { return }
                await self.projectIncrementally(for: sessionId)
            }
            conversationProjectionDrivers[sessionId] = driver
        }
        driver.invalidate()
    }
    /// Projects the compact conversation timeline for `sessionId`. Reuses
    /// the session's `ConversationProjector` across ticks: once it has been
    /// seeded (cold rebuild, paid at most once per session per process), a
    /// tick during active streaming only folds the events that arrived since
    /// the previous tick — a handful of chunks — instead of replaying the
    /// entire event log. This is what keeps rendering from falling further
    /// and further behind the WebSocket as a session grows, which is what
    /// happened with the previous "rebuild everything every tick" approach.
    private func projectIncrementally(for sessionId: String) async {
        let epoch = conversationProjectionEpochs[sessionId, default: 0]
        let all = events[sessionId, default: []]
        let projector = conversationProjectors[sessionId] ?? ConversationProjector()
        if projector.lastSeq < 0 {
            let items = await Task.detached(priority: .userInitiated) {
                projector.rebuild(from: all)
                return projector.items
            }.value
            guard !Task.isCancelled,
                  conversationProjectionEpochs[sessionId, default: 0] == epoch else { return }
            conversationProjectors[sessionId] = projector
            publishConversationItems(items, for: sessionId)
            return
        }
        let startIndex = ConversationProjector.firstIndexAfter(projector.lastSeq, in: all)
        guard startIndex < all.count else { return }
        projector.fold(Array(all[startIndex...]))
        conversationProjectors[sessionId] = projector
        publishConversationItems(projector.items, for: sessionId)
    }
    private func publishConversationItems(_ items: [ConversationItem], for sessionId: String) {
        let previouslyHadContent = conversationContentSessionIds.contains(sessionId)
        renderedConversationItems[sessionId] = items
        conversationTimeline(for: sessionId).publish(items)
        if items.isEmpty {
            if previouslyHadContent { conversationContentSessionIds.remove(sessionId) }
        } else if !previouslyHadContent {
            conversationContentSessionIds.insert(sessionId)
        }
    }
    private func applyEvent(_ record: SessionEvent) {
        let event = record.event
        if event.type == "turn/end" {
            backgroundExecutionController.turnEnded(sessionID: record.sessionId)
        }
        if event.type == "turn/end", record.sessionId == selectedSessionId {
            beginSessionControlRequest("context-usage")
            beginSessionControlRequest("session-stats")
            gateway.requestContextUsage(sessionId: record.sessionId)
            gateway.requestSessionStats(sessionId: record.sessionId)
        }
        if event.type == "permission/preset",
           let preset = event.raw?["preset"]?.stringValue ?? event.raw?["name"]?.stringValue {
            if Self.permissionPresets.contains(preset) {
                reduceSessionControl(.permissionSelected(sessionID: record.sessionId, value: preset))
            } else {
                reportInvalidPermission(preset, sessionId: record.sessionId, source: "permission/preset")
            }
        }
        if event.type == "request/header", let config = event.raw?["header"]?["config"] {
            let provider = config["provider"]?.stringValue
            let model = config["model"]?.stringValue
            if let provider, let model {
                reduceSessionControl(.modelSelected(
                    sessionID: record.sessionId,
                    selection: GatewayModelSelection(
                    provider: provider,
                    model: model,
                    reasoningEffort: config["reasoningEffort"]?.stringValue
                    )
                ))
            }
        }
        // Token, reasoning and tool deltas belong exclusively to the session
        // timeline. Updating this @Published array for every packet used to
        // invalidate the complete app hierarchy, re-sort the sidebar, encode
        // it and write UserDefaults while the user was trying to scroll.
        reduceSessionList(.eventReceived(record))
    }
    private func notice(_ title: String, _ text: String, sessionId: String? = nil, isError: Bool = false) {
        protocolNotices.append(GatewayNotice(sessionId: sessionId, title: title, text: text, isError: isError))
        if protocolNotices.count > 100 { protocolNotices.removeFirst(protocolNotices.count - 100) }
    }

    private func beginSessionControlRequest(_ kind: String) {
        reduceSessionControl(.requestStarted(kind))
        sessionControlRequestTracker.begin(kind, timeout: .seconds(12)) { [weak self] in
            guard let self else { return }
            self.reduceSessionControl(.requestTimedOut(kind))
            self.lastError = String(localized: "control.request.timeout", defaultValue: "\(kind) 请求超时，请检查 Mobile Gateway。")
        }
    }

    private func handleConnectionFailure(_ detail: String) {
        // Keep the prepared destination, but require a fresh activation after
        // the transport reconnects and emits its next hello frame.
        activeConversationActivationKey = nil
        // A transport/authentication failure invalidates every outstanding
        // business request. Cancel their timeout tokens first so an unrelated
        // "agent-presets 请求超时" cannot replace the real WebSocket cause.
        resetOutstandingRequests()
        if presentsNextConnectionFailureAsAlert {
            lastError = detail
        }
        presentsNextConnectionFailureAsAlert = true
        backgroundExecutionController.cancel()
    }

    private func beginAgentBackgroundExecution(for sessionID: String?, startsNewTurn: Bool) {
        backgroundExecutionController.begin(sessionID: sessionID, startsNewTurn: startsNewTurn)
    }

    private func resetOutstandingRequests() {
        for kind in Array(sessionControlLoadingKinds) { finishSessionControlRequest(kind) }
        for kind in Array(defaultConfigurationLoadingKinds) { finishDefaultConfigurationRequest(kind) }
        for id in Array(historyLoadingSessionIds) { finishHistoryLoading(id) }
        directoryIsLoading = false
        directoryCreationIsLoading = false
        pendingDirectoryCreationParentPath = nil
        workspaceCreationIsLoading = false
        isRefreshing = false
        waitingForNewSession = false
    }

    private func finishSessionControlRequest(_ kind: String) {
        sessionControlRequestTracker.finish(kind)
        reduceSessionControl(.requestFinished(kind))
    }

    private func setGlobalDefault(target: String, value: String) {
        guard gateway.state.isConnected else {
            lastError = String(localized: "请先连接 DeepSeek Harness")
            return
        }
        beginDefaultConfigurationRequest("set-default")
        gateway.setDefault(target: target, value: value)
    }

    private func beginDefaultConfigurationRequest(_ kind: String) {
        reduceSessionControl(.defaultConfigurationRequestStarted(kind))
        defaultConfigurationRequestTracker.begin(kind, timeout: .seconds(12)) { [weak self] in
            guard let self else { return }
            self.reduceSessionControl(.defaultConfigurationRequestTimedOut(kind))
            self.lastError = String(localized: "control.request.timeout.v0111", defaultValue: "\(kind) 请求超时，请检查 Mobile Gateway v0.1.11。")
        }
    }

    private func finishDefaultConfigurationRequest(_ kind: String) {
        defaultConfigurationRequestTracker.finish(kind)
        reduceSessionControl(.defaultConfigurationRequestFinished(kind))
    }

    private func applyPermissions(_ incoming: GatewaySessionPermissions, sessionId: String, source: String) {
        let current = incoming.currentValue ?? incoming.preset
        if let current, !Self.permissionPresets.contains(current) {
            reportInvalidPermission(current, sessionId: sessionId, source: source)
            return
        }
        reduceSessionControl(.permissionsReceived(sessionID: sessionId, permissions: incoming))
    }

    private func reportInvalidPermission(_ value: String, sessionId: String, source: String) {
        finishSessionControlRequest("permission")
        finishSessionControlRequest("permission-options")
        let detail = String(localized: "permissions.state.invalid", defaultValue: "权限状态 \"\(value)\" 无效；仅支持 read-only、workspace-write、danger-full-access。")
        lastError = detail
        notice(String(localized: "permissions.state.error.source", defaultValue: "权限状态错误 · \(source)"), detail, sessionId: sessionId, isError: true)
    }

    private func sessionControlKind(from value: String) -> String? {
        ["permission-options", "select-model", "context-usage", "session-stats", "permission", "models"]
            .first { value.localizedCaseInsensitiveContains($0) }
    }
    private func markRead(_ id: String) {
        reduceSessionList(.markRead(id))
    }
    private func reduceSessionList(_ action: SessionListAction) {
        let snapshot: KMPSessionListSnapshot
        do {
            snapshot = try kmpSessionListStore.reduce(action)
        } catch {
            lastError = error.localizedDescription
            return
        }
        let mappedSessions = snapshot.persistedSessions
        if mappedSessions != sessions {
            sessions = mappedSessions
            persistSessions()
        }
        let mappedArchivedSessionIDs = snapshot.archivedSessionIDSet
        if mappedArchivedSessionIDs != archivedSessionIds {
            archivedSessionIds = mappedArchivedSessionIDs
        }
        if snapshot.selectedSessionId != selectedSessionId {
            selectedSessionId = snapshot.selectedSessionId
        }
    }
    private func reduceQuestion(_ action: QuestionAction) {
        var state = QuestionState(
            pendingRequests: pendingQuestionRequests,
            requestStatuses: questionRequestStatuses
        )
        QuestionReducer.reduce(state: &state, action: action)
        if state.pendingRequests != pendingQuestionRequests {
            pendingQuestionRequests = state.pendingRequests
        }
        if state.requestStatuses != questionRequestStatuses {
            questionRequestStatuses = state.requestStatuses
        }
    }
    @discardableResult
    private func reduceHistory(_ action: HistoryAction) -> HistoryResult {
        let result = HistoryReducer.reduce(state: &historyState, action: action)
        let hasMore = historyState.sessions.mapValues(\.hasMore)
        let loading = Set(historyState.sessions.compactMap { $0.value.isLoading ? $0.key : nil })
        let loadingOlder = Set(historyState.sessions.compactMap { $0.value.isLoadingOlder ? $0.key : nil })
        let progress = Dictionary(uniqueKeysWithValues: historyState.sessions.compactMap { key, value in
            value.progress.map { (key, $0) }
        })
        if hasMore != historyHasMore { historyHasMore = hasMore }
        if loading != historyLoadingSessionIds { historyLoadingSessionIds = loading }
        if loadingOlder != historyLoadingOlderSessionIds { historyLoadingOlderSessionIds = loadingOlder }
        if progress != historyLoadProgress { historyLoadProgress = progress }
        return result
    }
    private func handleHistoryResult(_ result: HistoryResult, sessionID: String) {
        switch result {
        case .none:
            break
        case .requestPage(let beforeSequence):
            requestHistoryPage(for: sessionID, beforeSeq: beforeSequence)
        case .stopped:
            historySyncEngine.finish(sessionID: sessionID)
        case .failed(let message):
            historySyncEngine.finish(sessionID: sessionID)
            lastError = message
            notice(String(localized: "notice.history.pagination-failed", defaultValue: "历史记录分页失败"), message, sessionId: sessionID, isError: true)
        case .completed(let eventCount, let byteCount, let hasMore):
            historySyncEngine.finish(sessionID: sessionID)
            let byteDetail = byteCount > 0
                ? " · \(ByteCountFormatter.string(fromByteCount: Int64(byteCount), countStyle: .file))"
                : ""
            let moreDetail = hasMore ? String(localized: "history.swipe.up.hint", defaultValue: " · 向上滑动加载更早记录") : ""
            notice(
                String(localized: "notice.history.loaded", defaultValue: "历史记录已加载"),
                String(localized: "history.loaded.detail", defaultValue: "\(eventCount) 个事件\(byteDetail)\(moreDetail)"),
                sessionId: sessionID
            )
        }
    }
    private func reduceSessionControl(_ action: SessionControlAction) {
        var state = SessionControlState(
            modelCatalogs: modelCatalogs,
            globalModelCatalog: globalModelCatalog,
            sessionPermissions: sessionPermissions,
            contextSnapshots: contextSnapshots,
            sessionStatsSnapshots: sessionStatsSnapshots,
            agentPresets: agentPresets,
            agentPresetsAuthorable: agentPresetsAuthorable,
            agentPresetsHasDocument: agentPresetsHasDocument,
            agentPresetDefault: agentPresetDefault,
            permissionDefault: permissionDefault,
            defaultModelSelection: defaultModelSelection,
            loadingKinds: sessionControlLoadingKinds,
            defaultConfigurationLoadingKinds: defaultConfigurationLoadingKinds,
            pendingModelsSessionID: pendingModelsSessionId,
            isPendingGlobalModelsRequest: isPendingGlobalModelsRequest,
            pendingModelSelectionSessionID: pendingModelSelectionSessionId,
            pendingPermissionOptionsSessionID: pendingPermissionOptionsSessionId
        )
        SessionControlReducer.reduce(state: &state, action: action)
        if state.modelCatalogs != modelCatalogs { modelCatalogs = state.modelCatalogs }
        if state.globalModelCatalog != globalModelCatalog { globalModelCatalog = state.globalModelCatalog }
        if state.sessionPermissions != sessionPermissions { sessionPermissions = state.sessionPermissions }
        if state.contextSnapshots != contextSnapshots { contextSnapshots = state.contextSnapshots }
        if state.sessionStatsSnapshots != sessionStatsSnapshots { sessionStatsSnapshots = state.sessionStatsSnapshots }
        if state.agentPresets != agentPresets { agentPresets = state.agentPresets }
        if state.agentPresetsAuthorable != agentPresetsAuthorable {
            agentPresetsAuthorable = state.agentPresetsAuthorable
        }
        if state.agentPresetsHasDocument != agentPresetsHasDocument {
            agentPresetsHasDocument = state.agentPresetsHasDocument
        }
        if state.agentPresetDefault != agentPresetDefault { agentPresetDefault = state.agentPresetDefault }
        if state.permissionDefault != permissionDefault { permissionDefault = state.permissionDefault }
        if state.defaultModelSelection != defaultModelSelection {
            defaultModelSelection = state.defaultModelSelection
        }
        if state.loadingKinds != sessionControlLoadingKinds {
            sessionControlLoadingKinds = state.loadingKinds
        }
        if state.defaultConfigurationLoadingKinds != defaultConfigurationLoadingKinds {
            defaultConfigurationLoadingKinds = state.defaultConfigurationLoadingKinds
        }
        pendingModelsSessionId = state.pendingModelsSessionID
        isPendingGlobalModelsRequest = state.isPendingGlobalModelsRequest
        pendingModelSelectionSessionId = state.pendingModelSelectionSessionID
        pendingPermissionOptionsSessionId = state.pendingPermissionOptionsSessionID
    }
    private func persistSessions() {
        preferences.saveSessions(sessions)
    }
}
