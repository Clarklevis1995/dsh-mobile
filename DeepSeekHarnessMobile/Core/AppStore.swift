import Combine
import SwiftUI
import UIKit

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

struct HistoryLoadProgress: Equatable {
    var loaded: Int
    var total: Int?
}

/// Pure staleness arithmetic for conversation history. One definition of
/// "behind", with the activation-vs-reconcile policy made explicit instead of
/// duplicated across call sites: activation may establish a first baseline,
/// the per-`sessions`-frame reconcile only reloads on a concrete gap.
enum ConversationReconciliation {
    enum HistoryDecision: Equatable {
        /// A load is already in flight, or local coverage is already current.
        case skipLoading
        /// Nothing is known locally — no completed baseline and no cached events.
        case needsBaseline
        /// Remote activity is strictly ahead of the newest covered activity.
        case reloadHistory
    }

    /// A completed history baseline may subsequently be extended by the
    /// subscribed live tail; both sources count as coverage. A session with
    /// live events but no completed baseline is still covered up to its
    /// newest event and only reloads when the remote summary moves past it.
    ///
    /// The comparison is intentionally strict: equality counts as covered
    /// (a >= comparison would reload on every frame), so same-timestamp
    /// missed events only recover once remote activity moves strictly ahead.
    static func historyDecision(
        remoteLastActivity: Date,
        syncedActivity: Date?,
        latestLocalEventDate: Date?,
        isHistoryLoading: Bool
    ) -> HistoryDecision {
        if isHistoryLoading { return .skipLoading }
        // Newest covered activity, without allocating a temporary array on
        // every `sessions` frame (Optional is not Comparable).
        let coveredActivity: Date?
        if let synced = syncedActivity, let event = latestLocalEventDate {
            coveredActivity = max(synced, event)
        } else {
            coveredActivity = syncedActivity ?? latestLocalEventDate
        }
        guard let coveredActivity else { return .needsBaseline }
        return remoteLastActivity > coveredActivity ? .reloadHistory : .skipLoading
    }

    /// Activation (conversation push, transport `hello`): the one path allowed
    /// to establish a first baseline for a session with no local content.
    static func activationShouldLoadHistory(_ decision: HistoryDecision) -> Bool {
        decision != .skipLoading
    }

    /// Reconcile (every `sessions` frame): never fetches for a session it
    /// cannot compare against local coverage, so a brand-new session's own
    /// first send does not trigger a redundant full history fetch.
    static func reconcileShouldLoadHistory(_ decision: HistoryDecision) -> Bool {
        decision == .reloadHistory
    }

    /// What the reconcile should do with the currently open conversation
    /// once a fresh `sessions` frame has arrived.
    enum OpenConversationDisposition: Equatable {
        /// No conversation is open (nothing selected, or the prepared key is
        /// the `__new-conversation__` sentinel / another session).
        case notOpen
        /// The open session was archived on another device. Product decision:
        /// archiving means the user does not want to see it anywhere, so the
        /// app pops back to the workspace list instead of anchoring on it.
        case dismissToWorkspace
        /// The open session is absent from the authoritative list: deleted on
        /// another device. Cached content stays visible under a notice.
        case showMissingNotice
        /// The session is present (or a locally created send not yet listed);
        /// proceed to the staleness check.
        case continueReconciling
    }

    static func openConversationDisposition(
        selectedSessionId: String?,
        preparedActivationKey: String?,
        archivedSessionIds: Set<String>,
        presentSessionIds: Set<String>,
        unconfirmedLocallyCreatedSessionIds: Set<String>
    ) -> OpenConversationDisposition {
        guard let id = selectedSessionId, preparedActivationKey == id else { return .notOpen }
        // Archived is checked before presence: an archived id necessarily
        // references a real server-side session (it cannot appear in the
        // archived list through any local race), and the server omits
        // archived sessions from the active summary list, so it would
        // otherwise fall through to the missing-notice branch.
        if archivedSessionIds.contains(id) { return .dismissToWorkspace }
        guard presentSessionIds.contains(id) || unconfirmedLocallyCreatedSessionIds.contains(id) else {
            return .showMissingNotice
        }
        return .continueReconciling
    }
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
    @Published var endpoint: String { didSet { UserDefaults.standard.set(endpoint, forKey: "gateway.endpoint") } }
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
    /// Increments when the open conversation must be dismissed (its session
    /// was archived on another device). RootNavigationHost subscribes via
    /// `conversationDismissalPublisher` and pops the navigation stack; the
    /// resulting empty path drives `resumeWorkspace()` for cleanup.
    @Published private(set) var conversationDismissalToken = 0

    let gateway = GatewayClient()
    private var pendingHistorySessionId: String?
    private var historyRequestTokens: [String: UUID] = [:]
    private var historyPaginationCursors: [String: Set<Int>] = [:]
    private var historyNextBeforeSeq: [String: Int] = [:]
    private var historyBatchPageCounts: [String: Int] = [:]
    private var historyBatchKinds: [String: HistoryBatchKind] = [:]
    private var historyLoadedEventCounts: [String: Int] = [:]
    private var historyLoadedByteCounts: [String: Int] = [:]
    /// The remote session activity timestamp covered by a completed history load.
    /// This intentionally remains an in-memory cache: events are not persisted
    /// across launches, so a fresh process must fetch history again.
    private var historySyncedActivityDates: [String: Date] = [:]
    /// Sessions whose disappearance from the authoritative list has already
    /// been surfaced once, so repeated `sessions` frames stay silent.
    private var missingConversationNoticeSessionIds: Set<String> = []
    /// Sessions created by this device's own sends that no remote `sessions`
    /// frame has listed yet. They are exempt from the missing-session check
    /// so a pre-send snapshot response cannot produce a false "deleted"
    /// notice for a conversation that just came to life.
    private var unconfirmedLocallyCreatedSessionIds: Set<String> = []
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
    private var queuedImageAttachmentIDs: Set<String> = []
    private var imageAttachmentQueue: [(sessionId: String, attachment: GatewayImageAttachment)] = []
    private var inFlightImageAttachmentIDs: Set<String> = []
    private var pendingModelsSessionId: String?
    private var isPendingGlobalModelsRequest = false
    private var pendingModelSelectionSessionId: String?
    private var pendingPermissionOptionsSessionId: String?
    private var pendingDirectoryCreationParentPath: String?
    private var sessionControlRequestTokens: [String: UUID] = [:]
    private var defaultConfigurationRequestTokens: [String: UUID] = [:]
    /// Navigation preparation is intentionally cheap. Remote activation begins
    /// from the destination lifecycle, after NavigationStack installs its bar.
    private var preparedConversationActivationKey: String?
    private var activeConversationActivationKey: String?
    private var presentsNextConnectionFailureAsAlert = true
    private var hasHandledColdLaunchConnection = false
    private var applicationIsInBackground = false
    private var userInitiatedAgentWorkIsActive = false
    private var outstandingUserInitiatedTurns = 0
    private var backgroundAgentSessionID: String?
    private var agentBackgroundTask: UIBackgroundTaskIdentifier = .invalid
    private static let permissionPresets: Set<String> = ["read-only", "workspace-write", "danger-full-access"]
    private static let defaultPermissionPresets: Set<String> = ["ask", "read-only", "workspace-write", "danger-full-access"]
    private static let historyPageByteBudget = 4 * 1024 * 1024
    private static let historyPagesPerBatch = 2
    private static let newConversationActivationKey = "__new-conversation__"

    private enum HistoryBatchKind {
        case latest
        case older
    }

    init() {
        appLanguage = AppLanguage.load()
        selectedWorkspaceId = UserDefaults.standard.string(forKey: "gateway.selectedWorkspaceId")
        endpoint = UserDefaults.standard.string(forKey: "gateway.endpoint") ?? "ws://127.0.0.1:3080/ws/mobile"
        if let data = UserDefaults.standard.data(forKey: "gateway.sessions"),
           let decoded = try? JSONDecoder().decode([SessionSummary].self, from: data) { sessions = decoded }
        UserDefaults.standard.removeObject(forKey: "gateway.conversationScrollAnchors")
        UserDefaults.standard.removeObject(forKey: "gateway.manuallyPositionedSessionIds")
        gateway.onFrame = { [weak self] frame in self?.handle(frame) }
        gateway.onConnectionFailure = { [weak self] detail in
            self?.handleConnectionFailure(detail)
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

    /// View-side subscription for `conversationDismissalToken`. `onReceive`
    /// works on views that hold the store as an unobserved reference (the
    /// equatable navigation host never re-evaluates its body, so onChange
    /// would never fire).
    var conversationDismissalPublisher: AnyPublisher<Void, Never> {
        $conversationDismissalToken
            .dropFirst()
            .map { _ in () }
            .eraseToAnyPublisher()
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
            applicationIsInBackground = false
            gateway.applicationDidBecomeActive()
        case .background:
            applicationIsInBackground = true
            imageAttachmentCache.removeExpiredFiles()
            let hasBackgroundAllowance = userInitiatedAgentWorkIsActive && agentBackgroundTask != .invalid
            gateway.applicationDidEnterBackground(keepConnectionAlive: hasBackgroundAllowance)
        case .inactive:
            break
        @unknown default:
            break
        }
    }

    func pair(usingQRCode rawValue: String, presentsFailureAlert: Bool = true) throws {
        let trimmed = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let data = Self.decodeStrictBase64URL(trimmed) else {
            throw PairingPayloadError.invalidBase64URL
        }
        let payload: GatewayPairingPayload
        do {
            payload = try JSONDecoder().decode(GatewayPairingPayload.self, from: data)
        } catch {
            throw PairingPayloadError.invalidJSON
        }
        guard payload.version == 2 else {
            throw PairingPayloadError.unsupportedVersion(payload.version)
        }
        guard let url = payload.endpoint,
              ["ws", "wss"].contains(url.scheme?.lowercased() ?? ""),
              url.host != nil else {
            throw PairingPayloadError.invalidEndpoint
        }
        guard !payload.pairingCode.isEmpty,
              !payload.pairingCode.contains(","),
              payload.pairingCode.unicodeScalars.allSatisfy({
                  !CharacterSet.whitespacesAndNewlines.contains($0) &&
                  !CharacterSet.controlCharacters.contains($0)
              }) else {
            throw PairingPayloadError.invalidCode
        }
        guard payload.expirationDate > .now else {
            throw PairingPayloadError.expired
        }
        resetOutstandingRequests()
        presentsNextConnectionFailureAsAlert = presentsFailureAlert
        lastError = nil
        endpoint = payload.publicUrl
        gateway.connectForPairing(payload)
    }

    private static func decodeStrictBase64URL(_ value: String) -> Data? {
        guard !value.isEmpty,
              !value.contains("="),
              value.unicodeScalars.allSatisfy({ scalar in
                  CharacterSet.alphanumerics.contains(scalar) || scalar == "-" || scalar == "_"
              }),
              value.count % 4 != 1 else { return nil }
        var base64 = value.replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        base64 += String(repeating: "=", count: (4 - base64.count % 4) % 4)
        return Data(base64Encoded: base64, options: [])
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
            lastError = String(localized: "pairing.error.broken-preset", defaultValue: "无法将未知或已损坏的 Agent 预设设为默认值：\(id)")
            return
        }
        setGlobalDefault(target: "agent-preset", value: id)
    }
    func setDefaultPermission(_ value: String) {
        guard Self.defaultPermissionPresets.contains(value) else {
            lastError = String(localized: "permissions.error.unsupported-default", defaultValue: "不支持的默认权限：\(value)")
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
            lastError = String(localized: "请先连接 DeepSeek Harness")
            return
        }
        beginDefaultConfigurationRequest("save-default-model")
        gateway.saveDefaultModel(provider: provider, model: model, reasoningEffort: reasoningEffort)
    }
    func prepareNewConversation() {
        selectedSessionId = nil
        waitingForNewSession = false
        preparedConversationActivationKey = Self.newConversationActivationKey
        activeConversationActivationKey = nil
    }

    func prepareConversation(for session: SessionSummary) {
        selectedSessionId = session.id
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

    private func historyDecision(for session: SessionSummary) -> ConversationReconciliation.HistoryDecision {
        ConversationReconciliation.historyDecision(
            remoteLastActivity: session.lastActivity,
            syncedActivity: historySyncedActivityDates[session.id],
            latestLocalEventDate: events[session.id]?.last?.date,
            isHistoryLoading: historyLoadingSessionIds.contains(session.id)
        )
    }

    private func shouldRefreshHistory(for session: SessionSummary) -> Bool {
        ConversationReconciliation.activationShouldLoadHistory(historyDecision(for: session))
    }
    func loadHistory(for sessionId: String, older: Bool = false) {
        guard gateway.state.isConnected else {
            lastError = String(localized: "WebSocket 尚未连接，无法加载历史记录")
            return
        }
        guard !historyLoadingSessionIds.contains(sessionId) else { return }
        if older, historyHasMore[sessionId] != true { return }
        pendingHistorySessionId = sessionId
        historyLoadingSessionIds.insert(sessionId)
        if older { historyLoadingOlderSessionIds.insert(sessionId) }
        historyLoadProgress[sessionId] = HistoryLoadProgress(loaded: 0, total: nil)
        historyPaginationCursors[sessionId] = []
        historyBatchPageCounts[sessionId] = 0
        historyBatchKinds[sessionId] = older ? .older : .latest
        historyLoadedEventCounts[sessionId] = 0
        historyLoadedByteCounts[sessionId] = 0
        if !older, events[sessionId, default: []].isEmpty {
            historyHasMore[sessionId] = false
            historyNextBeforeSeq[sessionId] = nil
        }
        // Keep the installed projector alive. History is built as a separate
        // baseline and atomically rebased under the still-advancing live tail.
        let before = older
            ? historyNextBeforeSeq[sessionId] ?? events[sessionId]?.map(\.seq).min()
            : nil
        if older, before == nil {
            finishHistoryLoading(sessionId)
            historyHasMore[sessionId] = false
            return
        }
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
            let hasUsableLocalContent = !self.events[sessionId, default: []].isEmpty
                || !self.renderedConversationItems[sessionId, default: []].isEmpty
            self.finishHistoryLoading(sessionId)
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
    }
    func resumeWorkspace() {
        preparedConversationActivationKey = nil
        activeConversationActivationKey = nil
        // The open conversation's lifecycle ended; its disappearance-notice
        // bookkeeping should not outlive it.
        missingConversationNoticeSessionIds.removeAll()
        gateway.subscribe(sessionId: nil)
        refreshRemoteState()
    }
    func addKnownSession(_ id: String) {
        let normalized = id.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalized.isEmpty else { return }
        upsertSession(id: normalized, title: L10n.remoteSessionTitle(normalized.prefix(8)))
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
        guard gateway.state.isConnected else {
            questionRequestStatuses[request.rpcId] = .rejected(String(localized: "q.rejected.ws.disconnected.answer", defaultValue: "WebSocket 已断开，重连后再提交答案。"))
            return
        }
        guard request.questions.map(\.id) == answers.map(\.id) else {
            questionRequestStatuses[request.rpcId] = .rejected(String(localized: "答案必须按原顺序覆盖整组问题。"))
            return
        }
        for (question, answer) in zip(request.questions, answers) {
            let allowedLabels = Set((question.options ?? []).map(\.label))
            guard Set(answer.selected).count == answer.selected.count,
                  answer.selected.allSatisfy(allowedLabels.contains) else {
                questionRequestStatuses[request.rpcId] = .rejected(String(localized: "q.rejected.bad-options", defaultValue: "“\(question.question)”包含无效或重复选项。"))
                return
            }
            if !question.allowsMultipleSelections {
                guard answer.selected.count <= 1,
                      !(answer.selected.count == 1 && answer.custom != nil) else {
                    questionRequestStatuses[request.rpcId] = .rejected(String(localized: "单选题只能选择一个选项，且不能同时填写自定义答案。"))
                    return
                }
            }
        }
        questionRequestStatuses[request.rpcId] = .submitting(.answer)
        beginAgentBackgroundExecution(for: request.sessionId, startsNewTurn: false)
        gateway.answerQuestion(rpcId: request.rpcId, sessionId: request.sessionId, answers: answers)
    }

    func cancelQuestion(_ request: GatewayPendingQuestionRequest) {
        guard gateway.state.isConnected else {
            questionRequestStatuses[request.rpcId] = .rejected(String(localized: "q.rejected.ws.disconnected.skip", defaultValue: "WebSocket 已断开，重连后再跳过问题。"))
            return
        }
        questionRequestStatuses[request.rpcId] = .submitting(.cancel)
        gateway.cancelQuestion(rpcId: request.rpcId, sessionId: request.sessionId)
    }
    func title(for sessionId: String) -> String { sessions.first(where: { $0.id == sessionId })?.title ?? "DeepSeek Harness" }

    private func handle(_ frame: GatewayFrame) {
        switch frame.kind {
        case "paired":
            notice(String(localized: "设备配对成功"), frame.device?.name ?? String(localized: "长期凭据已安全保存到 Keychain"))
        case "hello":
            // `hello` starts a new authenticated transport generation. The
            // gateway immediately replays every still-pending request after
            // it, so clearing here prevents questions cancelled by a DSH
            // restart from lingering while replayed rpcIds are deduplicated.
            pendingQuestionRequests.removeAll()
            questionRequestStatuses.removeAll()
            supportsImages = (frame.protocol ?? 1) >= 3 && (frame.capabilities ?? []).contains("images")
            inFlightImageAttachmentIDs.removeAll()
            queuedImageAttachmentIDs.removeAll()
            imageAttachmentQueue.removeAll()
            presentsNextConnectionFailureAsAlert = true
            let authentication = frame.authenticated == false ? String(localized: "Debug 未鉴权") : String(localized: "设备鉴权成功")
            notice(String(localized: "网关已连接"), String(localized: "gateway.connected.detail", defaultValue: "\(authentication) · Mobile protocol v\(frame.protocol ?? 1) · \(frame.clients ?? 1) 个客户端"))
            for (sessionId, records) in events {
                enqueueImageAttachments(in: records, sessionId: sessionId)
            }
            refreshRemoteState()
            refreshDefaultConfiguration()
            // Every `hello` begins a new transport generation: the server-side
            // subscription from the previous connection is gone. Background
            // suspension tears the transport down without the failure callback
            // that normally invalidates an activation, so reset it here to let
            // the open conversation re-activate on the new generation —
            // re-subscribing live events and catching up missed history.
            activeConversationActivationKey = nil
            // History loads still marked in-flight also belong to the previous
            // generation: the server never retransmits their responses, and
            // without this reset the re-activation below would short-circuit
            // to .skipLoading for up to the 20 s request timeout — leaving a
            // first-baseline fetch that was interrupted by backgrounding
            // permanently unfetched. Their timeout tasks see a cleared token
            // and stay silent.
            for staleSessionId in Array(historyLoadingSessionIds) {
                finishHistoryLoading(staleSessionId)
            }
            if preparedConversationActivationKey != nil {
                Task { [weak self] in
                    guard let self else { return }
                    await self.activatePreparedConversation(sessionID: self.selectedSessionId)
                }
            }
        case "pong":
            notice(String(localized: "心跳正常"), frame.at.map { Date(timeIntervalSince1970: $0 / 1000).formatted(date: .omitted, time: .standard) } ?? "pong")
        case "subscribed":
            notice(String(localized: "notice.event.subscription", defaultValue: "事件订阅"), frame.sessionId.map { String(localized: "events.subscribe.single", defaultValue: "仅接收 \($0.prefix(12))…") } ?? String(localized: "接收全部会话事件"), sessionId: frame.sessionId)
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
            notice(String(localized: "notice.workspaces.synced", defaultValue: "工作区已同步"), String(localized: "workspaces.count", defaultValue: "\(workspaces.count) 个工作区"))
        case "sessions":
            let remoteSummaries = decodeItems(frame.items, as: GatewaySessionSummary.self)
            applyRemoteSessions(remoteSummaries)
            // The frame's own id set is the authoritative presence signal —
            // the upsert-only local list cannot detect hard deletions.
            let presentSessionIds = Set(
                remoteSummaries
                    .filter { !archivedSessionIds.contains($0.sessionId) }
                    .map(\.sessionId)
            )
            unconfirmedLocallyCreatedSessionIds.subtract(presentSessionIds)
            isRefreshing = false
            reconcileOpenConversationWithRemoteState(presentSessionIds: presentSessionIds)
            notice(String(localized: "notice.sessions.synced", defaultValue: "会话列表已同步"), String(localized: "sessions.count", defaultValue: "\(sessions.count) 个会话"))
        case "history": applyHistoryRebased(frame)
        case "attachment": handleImageAttachment(frame)
        case "search":
            searchResults = decodeItems(frame.items, as: GatewaySearchItem.self)
            notice(String(localized: "notice.search.finished", defaultValue: "搜索完成"), String(localized: "search.results.count", defaultValue: "\(searchResults.count) 条结果\(frame.hasMore == true ? String(localized: "search.results.more", defaultValue: "，还有更多") : "")"))
        case "host":
            hostSnapshot = GatewayHostSnapshot(version: frame.version, cwd: frame.cwd, provider: frame.provider, model: frame.model, attachedSessions: frame.attachedSessions, canOpenPath: frame.canOpenPath)
            notice(String(localized: "宿主信息"), [frame.version, frame.provider, frame.model].compactMap { $0 }.joined(separator: " · "))
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
                notice(String(localized: "默认模型已更新"), [saved.model, saved.reasoningEffort].compactMap { $0 }.joined(separator: " · "))
            } else {
                lastError = String(localized: "服务端未确认默认模型更新。")
            }
            finishDefaultConfigurationRequest("save-default-model")
        case "set-default":
            if frame.applied == true, let target = frame.target, let value = frame.value {
                if target == "agent-preset" { agentPresetDefault = value }
                if target == "permission" { permissionDefault = value }
                notice(String(localized: "notice.defaults.updated", defaultValue: "默认配置已更新"), String(localized: "defaults.updated.detail", defaultValue: "\(target) · \(value)"))
                finishDefaultConfigurationRequest("set-default")
                beginDefaultConfigurationRequest("defaults")
                gateway.requestDefaults()
            } else {
                finishDefaultConfigurationRequest("set-default")
                lastError = String(localized: "服务端未确认默认配置更新。")
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
                notice(String(localized: "模型已切换"), [selected.model, selected.reasoningEffort].compactMap { $0 }.joined(separator: " · "), sessionId: id)
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
                    notice(String(localized: "权限已切换"), name, sessionId: id)
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
            notice(String(localized: "目录已加载"), String(localized: "directory.loaded.detail", defaultValue: "\(frame.path ?? "") · \(directoryEntries.count) 项"))
        case "directory-create":
            directoryCreationIsLoading = false
            let parentPath = pendingDirectoryCreationParentPath
            pendingDirectoryCreationParentPath = nil
            if let path = frame.path {
                createdDirectoryPathToReveal = path
                notice(String(localized: "文件夹已创建"), path)
            }
            // The protocol requires refreshing the parent after creation. Use
            // the request's captured parent so the response cannot accidentally
            // refresh a directory the user navigated to while it was in flight.
            if let parentPath {
                browseDirectories(path: parentPath)
            }
        case "workspace-create":
            workspaceCreationIsLoading = false
            if let workspace = frame.workspace {
                if let index = workspaces.firstIndex(where: { $0.id == workspace.id }) { workspaces[index] = workspace } else { workspaces.append(workspace) }
                selectedWorkspaceId = workspace.id
                notice(frame.created == true ? String(localized: "工作区已创建") : String(localized: "工作区已存在"), workspace.path)
                refreshRemoteState()
            }
        case "question-requested":
            handleQuestionRequested(frame)
        case "question-response":
            handleQuestionResponse(frame)
        case "question-resolved":
            handleQuestionResolved(frame)
        case "error":
            waitingForNewSession = false
            if frame.requestType == "directories" { directoryIsLoading = false }
            if frame.requestType == "directory-create" {
                directoryCreationIsLoading = false
                pendingDirectoryCreationParentPath = nil
            }
            if frame.requestType == "workspace-create" { workspaceCreationIsLoading = false }
            if let requestType = frame.requestType,
               ["agent-presets", "defaults", "default-model", "set-default", "save-default-model"].contains(requestType) {
                finishDefaultConfigurationRequest(requestType)
            }
            if frame.requestType == "history", let id = frame.sessionId ?? pendingHistorySessionId {
                finishHistoryLoading(id)
            }
            let detail = [frame.code, frame.message].compactMap { $0 }.joined(separator: ": ")
            if let requestType = frame.requestType,
               ["question-answer", "question-cancel"].contains(requestType),
               let rpcId = frame.rpcId ?? pendingQuestionRequests.first(where: { $0.sessionId == frame.sessionId })?.rpcId {
                questionRequestStatuses[rpcId] = .rejected(detail.isEmpty ? String(localized: "服务端拒绝了问题响应。") : detail)
            }
            let failedRequest = frame.requestType ?? frame.code.flatMap(sessionControlKind(from:)) ?? frame.message.flatMap(sessionControlKind(from:))
            if let failedRequest { finishSessionControlRequest(failedRequest) }
            if frame.requestType == nil, failedRequest == nil {
                // A malformed gateway error cannot be correlated safely. Stop
                // all composer spinners and surface the error instead.
                for kind in Array(sessionControlLoadingKinds) { finishSessionControlRequest(kind) }
            }
            lastError = detail
            notice(String(localized: "request.failed.title", defaultValue: "请求失败\(frame.requestType.map { " · \($0)" } ?? "")"), detail, sessionId: frame.sessionId, isError: true)
        default: notice(String(localized: "未知网关响应"), frame.kind)
        }
    }

    private func handleQuestionRequested(_ frame: GatewayFrame) {
        guard let rpcId = frame.rpcId,
              !rpcId.isEmpty,
              let sessionId = frame.sessionId,
              !sessionId.isEmpty,
              let questions = frame.questions,
              !questions.isEmpty else {
            notice(String(localized: "问题请求无效"), String(localized: "缺少 rpcId、sessionId 或 questions。"), sessionId: frame.sessionId, isError: true)
            return
        }
        let request = GatewayPendingQuestionRequest(
            rpcId: rpcId,
            sessionId: sessionId,
            questions: questions,
            replay: frame.replay == true
        )
        if let index = pendingQuestionRequests.firstIndex(where: { $0.rpcId == rpcId }) {
            pendingQuestionRequests[index] = request
        } else {
            pendingQuestionRequests.append(request)
            questionRequestStatuses[rpcId] = .idle
        }
        notice(
            frame.replay == true ? String(localized: "待回答问题已恢复") : String(localized: "Agent 正在等待回答"),
            questions.first?.question ?? String(localized: "请回答 Agent 的问题"),
            sessionId: sessionId
        )
    }

    private func handleQuestionResponse(_ frame: GatewayFrame) {
        guard let rpcId = frame.rpcId else { return }
        let action = GatewayQuestionAction(rawValue: frame.action ?? "") ?? .answer
        if frame.accepted == true {
            questionRequestStatuses[rpcId] = .accepted(action)
            return
        }
        let reason = frame.reason ?? "bad-response"
        if reason == "not-pending" {
            if let request = pendingQuestionRequests.first(where: { $0.rpcId == rpcId }) {
                notice(String(localized: "问题已在其他端处理"), String(localized: "当前响应未生效。"), sessionId: request.sessionId)
            }
            pendingQuestionRequests.removeAll { $0.rpcId == rpcId }
            questionRequestStatuses[rpcId] = nil
        } else {
            questionRequestStatuses[rpcId] = .rejected(String(localized: "q.rejected.server-refused", defaultValue: "服务端未接受答案（\(reason)），请检查后重试。"))
        }
    }

    private func handleQuestionResolved(_ frame: GatewayFrame) {
        guard let rpcId = frame.rpcId else { return }
        let request = pendingQuestionRequests.first { $0.rpcId == rpcId }
        pendingQuestionRequests.removeAll { $0.rpcId == rpcId }
        questionRequestStatuses[rpcId] = nil
        let outcome = frame.outcome == "cancelled" ? String(localized: "已跳过") : String(localized: "已提交")
        notice(String(localized: "q.outcome.title", defaultValue: "Agent 问题\(outcome)"), String(localized: "等待 Agent 继续执行。"), sessionId: frame.sessionId ?? request?.sessionId)
    }

    private func handleSent(_ frame: GatewayFrame) {
        guard let id = frame.sessionId else { return }
        if userInitiatedAgentWorkIsActive, backgroundAgentSessionID == nil {
            backgroundAgentSessionID = id
        }
        if selectedSessionId == nil {
            selectedSessionId = id
            // The pushed "new conversation" destination has just become a
            // real session. Align the prepared key so the next transport
            // generation's hello re-activation targets the real session id
            // (rather than the __new-conversation__ sentinel) and can
            // re-subscribe it, and drop the stale sentinel activation so
            // `prepared == active` holds again once that activation runs.
            // Track the id as locally created until a remote sessions frame
            // confirms it, so a pre-send snapshot of the session list cannot
            // read as a deletion.
            preparedConversationActivationKey = id
            activeConversationActivationKey = nil
            unconfirmedLocallyCreatedSessionIds.insert(id)
        }
        waitingForNewSession = false
        upsertSession(id: id, title: L10n.newSessionPlaceholderTitle)
        if let index = sessions.firstIndex(where: { $0.id == id }) {
            sessions[index].agentPreset = agentPresetDefault
        }
        notice(frame.command == nil ? String(localized: "消息已发送") : String(localized: "命令已执行"), frame.command?.displayText ?? String(localized: "session.preview.id", defaultValue: "\(id.prefix(12))…"), sessionId: id)
        gateway.subscribe(sessionId: id)
        gateway.requestSessions()
        refreshSessionControls(for: id)
    }
    private func handleLiveEvent(_ frame: GatewayFrame) {
        guard let id = frame.sessionId, let seq = frame.seq, let time = frame.time, let event = frame.event else { return }
        merge(SessionEvent(sessionId: id, seq: seq, time: time, event: event))
        enqueueImageAttachments(event.images ?? [], sessionId: id)
    }
    private func applyHistoryRebased(_ frame: GatewayFrame) {
        guard let id = frame.sessionId ?? pendingHistorySessionId ?? selectedSessionId else { return }
        // A timed-out request may still produce a late response. It must not
        // overwrite newer live content after the app has already fallen back
        // to subscription-driven incremental rendering.
        guard historyLoadingSessionIds.contains(id), historyRequestTokens[id] != nil else { return }
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
                guard self.historyRequestTokens[id] == processingToken else { return }

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

            self.historyLoadProgress[id] = HistoryLoadProgress(
                loaded: pageEventOffset + normalized.count,
                total: frame.hasMore == true ? nil : pageEventOffset + normalized.count
            )

            self.historyLoadedEventCounts[id] = pageEventOffset + normalized.count
            self.historyLoadedByteCounts[id, default: 0] += frame.bytes ?? 0
            self.historyHasMore[id] = frame.hasMore ?? false
            self.historyBatchPageCounts[id, default: 0] += 1

            if frame.hasMore == true, let earliestLocalSeq = self.events[id]?.first?.seq {
                // A latest-tail refresh can run while many older pages are
                // already cached. Continue from the oldest local event rather
                // than rewinding to page three and downloading cached pages
                // again on the next upward pagination request.
                self.historyNextBeforeSeq[id] = earliestLocalSeq
            } else if let cursor = frame.nextBeforeSeq {
                self.historyNextBeforeSeq[id] = cursor
            } else if frame.hasMore != true {
                self.historyNextBeforeSeq[id] = nil
            }

            if frame.hasMore == true,
               self.historyBatchPageCounts[id, default: 0] < Self.historyPagesPerBatch {
                guard let cursor = frame.nextBeforeSeq else {
                    let message = String(localized: "history.pagination.stopped.hasmore", defaultValue: "网关返回 hasMore:true，但缺少 nextBeforeSeq，已停止自动续页。")
                    self.historyHasMore[id] = false
                    self.historyNextBeforeSeq[id] = nil
                    self.finishHistoryLoading(id)
                    self.lastError = message
                    self.notice(String(localized: "notice.history.pagination-failed", defaultValue: "历史记录分页失败"), message, sessionId: id, isError: true)
                    return
                }
                var seenCursors = self.historyPaginationCursors[id, default: []]
                guard !seenCursors.contains(cursor) else {
                    let message = String(localized: "history.cursor.loop", defaultValue: "网关重复返回历史游标 \(cursor)，已停止自动续页以避免循环。")
                    self.historyHasMore[id] = false
                    self.historyNextBeforeSeq[id] = nil
                    self.finishHistoryLoading(id)
                    self.lastError = message
                    self.notice(String(localized: "notice.history.pagination-failed", defaultValue: "历史记录分页失败"), message, sessionId: id, isError: true)
                    return
                }
                seenCursors.insert(cursor)
                self.historyPaginationCursors[id] = seenCursors
                self.requestHistoryPage(for: id, beforeSeq: cursor)
                return
            }

            let totalEvents = self.historyLoadedEventCounts[id, default: 0]
            let totalBytes = self.historyLoadedByteCounts[id, default: 0]
            // Loading the newest batch establishes a tail watermark even when
            // older pages still exist. Reopening the session can therefore use
            // the in-memory pages immediately and only refresh when the remote
            // session has genuinely changed.
            if self.historyBatchKinds[id] == .latest,
               let activity = self.sessions.first(where: { $0.id == id })?.lastActivity {
                self.historySyncedActivityDates[id] = activity
            }
            self.finishHistoryLoading(id)
            let byteDetail = totalBytes > 0 ? " · \(ByteCountFormatter.string(fromByteCount: Int64(totalBytes), countStyle: .file))" : ""
            let moreDetail = self.historyHasMore[id] == true ? String(localized: "history.swipe.up.hint", defaultValue: " · 向上滑动加载更早记录") : ""
            self.notice(String(localized: "notice.history.loaded", defaultValue: "历史记录已加载"), String(localized: "history.loaded.detail", defaultValue: "\(totalEvents) 个事件\(byteDetail)\(moreDetail)"), sessionId: id)
        }
    }
    private func finishHistoryLoading(_ sessionId: String) {
        historyRequestTokens[sessionId] = nil
        historyPaginationCursors[sessionId] = nil
        historyBatchPageCounts[sessionId] = nil
        historyBatchKinds[sessionId] = nil
        historyLoadedEventCounts[sessionId] = nil
        historyLoadedByteCounts[sessionId] = nil
        historyLoadingSessionIds.remove(sessionId)
        historyLoadingOlderSessionIds.remove(sessionId)
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
        let sessionId = record.sessionId
        if let lastSeq = events[sessionId]?.last?.seq, record.seq > lastSeq {
            // Live gateway traffic is normally strictly seq-ascending. Keep
            // that hot path O(1): the previous implementation copied and
            // sorted the complete event log for every token delta.
            events[sessionId, default: []].append(record)
        } else {
            var list = events[sessionId, default: []]
            var low = 0
            var high = list.count
            while low < high {
                let middle = (low + high) / 2
                if list[middle].seq < record.seq { low = middle + 1 } else { high = middle }
            }
            if low < list.count, list[low].seq == record.seq {
                list[low] = record
            } else {
                list.insert(record, at: low)
            }
            events[sessionId] = list
            // A duplicate or out-of-order frame may fall behind lastSeq and
            // therefore cannot be folded safely into the append-only cache.
            conversationProjectors[sessionId] = nil
            conversationProjectionEpochs[sessionId, default: 0] &+= 1
        }
        applyEvent(record)
        // Once the cache has been fully established, subscribed live events are
        // already the newest local content. Advance the watermark so reopening
        // the session does not download the same history again.
        if let syncedActivity = historySyncedActivityDates[sessionId] {
            historySyncedActivityDates[sessionId] = max(syncedActivity, record.date)
        }
        scheduleConversationProjection(for: sessionId)
    }

    private func enqueueImageAttachments(in records: [SessionEvent], sessionId: String) {
        enqueueImageAttachments(records.flatMap { $0.event.images ?? [] }, sessionId: sessionId)
    }

    private func enqueueImageAttachments(_ attachments: [GatewayImageAttachment], sessionId: String) {
        guard supportsImages else { return }
        for attachment in attachments {
            guard imageAttachmentCache.data(for: attachment.id) == nil else { continue }
            guard !queuedImageAttachmentIDs.contains(attachment.id),
                  !inFlightImageAttachmentIDs.contains(attachment.id) else { continue }
            queuedImageAttachmentIDs.insert(attachment.id)
            imageAttachmentQueue.append((sessionId, attachment))
        }
        pumpImageAttachmentQueue()
    }

    private func pumpImageAttachmentQueue() {
        while inFlightImageAttachmentIDs.count < 3, !imageAttachmentQueue.isEmpty {
            let request = imageAttachmentQueue.removeFirst()
            queuedImageAttachmentIDs.remove(request.attachment.id)
            guard imageAttachmentCache.data(for: request.attachment.id) == nil else { continue }
            inFlightImageAttachmentIDs.insert(request.attachment.id)
            gateway.requestAttachment(
                sessionId: request.sessionId,
                attachmentId: request.attachment.id
            )
        }
    }

    private func handleImageAttachment(_ frame: GatewayFrame) {
        guard let attachment = frame.attachment else { return }
        inFlightImageAttachmentIDs.remove(attachment.id)
        defer { pumpImageAttachmentQueue() }
        guard let encoded = frame.data,
              let decoded = Data(base64Encoded: encoded),
              decoded.count == attachment.bytes else {
            notice(String(localized: "notice.image.load-failed", defaultValue: "图片加载失败"), String(localized: "image.invalid.base64", defaultValue: "附件 \(attachment.id.prefix(12))… 的 Base64 或字节数无效。"), sessionId: frame.sessionId, isError: true)
            return
        }
        imageAttachmentCache.store(decoded, for: attachment.id)
        guard let sessionId = frame.sessionId,
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
    private func applyRemoteSessions(_ remote: [GatewaySessionSummary]) {
        for item in remote where !archivedSessionIds.contains(item.sessionId) {
            let fallback = item.cwd.map { URL(fileURLWithPath: $0).lastPathComponent }.flatMap { $0.isEmpty ? nil : $0 }
            let title = item.projectedTitle ?? fallback ?? L10n.remoteSessionTitle(item.sessionId.prefix(8))
            let date = Date(timeIntervalSince1970: item.updatedAt > 10_000_000_000 ? item.updatedAt / 1000 : item.updatedAt)
            if let index = sessions.firstIndex(where: { $0.id == item.sessionId }) {
                sessions[index].title = title
                sessions[index].lastActivity = date
                sessions[index].isRunning = item.running
                sessions[index].agentPreset = item.agentPreset ?? sessions[index].agentPreset
            } else {
                sessions.append(SessionSummary(
                    id: item.sessionId,
                    title: item.blank ? L10n.blankSessionTitle(item.sessionId.prefix(8)) : title,
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
    /// A fresh session summary is the first authoritative signal that the open
    /// conversation fell behind while the app was disconnected or backgrounded:
    /// `subscribe` is only an event filter, so live frames carry output produced
    /// *after* the transport re-subscribes and never replay the gap. Re-activation
    /// on `hello` runs before this response arrives and therefore compares
    /// against the stale summary; reconciling here, with the updated list,
    /// closes that race.
    ///
    /// Safe to call on every `sessions` frame: the decision helper
    /// short-circuits in-flight loads, and sessions without established local
    /// coverage only reload when remote activity is genuinely ahead — a
    /// brand-new session's own first send therefore never triggers a
    /// redundant full history fetch.
    private func reconcileOpenConversationWithRemoteState(presentSessionIds: Set<String>) {
        guard let id = selectedSessionId else { return }
        switch ConversationReconciliation.openConversationDisposition(
            selectedSessionId: selectedSessionId,
            preparedActivationKey: preparedConversationActivationKey,
            archivedSessionIds: archivedSessionIds,
            presentSessionIds: presentSessionIds,
            unconfirmedLocallyCreatedSessionIds: unconfirmedLocallyCreatedSessionIds
        ) {
        case .notOpen:
            return
        case .dismissToWorkspace:
            // The session was archived on another device: archiving is a
            // "do not show me this anywhere" signal, so pop back to the
            // workspace list instead of anchoring on it. Clear the activation
            // state immediately so a subsequent frame cannot re-emit; the
            // empty navigation path then drives resumeWorkspace() for the
            // unsubscribe/refresh cleanup.
            preparedConversationActivationKey = nil
            activeConversationActivationKey = nil
            missingConversationNoticeSessionIds.remove(id)
            conversationDismissalToken &+= 1
            return
        case .showMissingNotice:
            // The authoritative frame no longer lists the open session: it
            // was deleted on another device (archived was handled above).
            // Cached content stays visible, and navigation chrome is
            // immutable, so surface a notice instead of silently swapping a
            // different session underneath the pushed title. Notify once per
            // disappearance. A session created by this device's own send that
            // no remote frame has confirmed yet is exempt — a pre-send
            // snapshot response must not read as a deletion.
            if missingConversationNoticeSessionIds.insert(id).inserted {
                notice(
                    String(localized: "notice.session.missing.title", defaultValue: "会话已不可用"),
                    String(localized: "notice.session.missing.detail", defaultValue: "当前会话已在其他设备被删除，显示的仍是本地缓存内容。"),
                    sessionId: id,
                    isError: true
                )
            }
            return
        case .continueReconciling:
            break
        }
        missingConversationNoticeSessionIds.remove(id)
        guard let session = sessions.first(where: { $0.id == id }),
              ConversationReconciliation.reconcileShouldLoadHistory(historyDecision(for: session)) else { return }
        // loadHistory re-checks the in-flight guard internally, so a race
        // with the hello-triggered activation Task cannot double-load.
        loadHistory(for: id)
    }
    private func applyEvent(_ record: SessionEvent) {
        let event = record.event
        if event.type == "turn/end",
           userInitiatedAgentWorkIsActive,
           backgroundAgentSessionID == record.sessionId {
            outstandingUserInitiatedTurns = max(0, outstandingUserInitiatedTurns - 1)
            if outstandingUserInitiatedTurns == 0 {
                finishAgentBackgroundExecution()
            }
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
        // Token, reasoning and tool deltas belong exclusively to the session
        // timeline. Updating this @Published array for every packet used to
        // invalidate the complete app hierarchy, re-sort the sidebar, encode
        // it and write UserDefaults while the user was trying to scroll.
        let updatesSessionMetadata = event.type == "user/message"
            || event.type == "session/title"
            || event.type == "turn/start"
            || event.type == "turn/end"
            || event.type == "assistant/message"
        guard updatesSessionMetadata else { return }
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
    }

    private func beginAgentBackgroundExecution(for sessionID: String?, startsNewTurn: Bool) {
        if startsNewTurn {
            outstandingUserInitiatedTurns += 1
        } else if outstandingUserInitiatedTurns == 0 {
            // Answering an interactive question resumes the current Agent
            // turn. Treat it as one outstanding turn when the original send
            // happened before this process began tracking background work.
            outstandingUserInitiatedTurns = 1
        }
        userInitiatedAgentWorkIsActive = true
        if let sessionID {
            backgroundAgentSessionID = sessionID
        }
        guard agentBackgroundTask == .invalid else { return }

        agentBackgroundTask = UIApplication.shared.beginBackgroundTask(withName: "Complete DSH Agent Turn") { [weak self] in
            guard let self else { return }
            self.expireAgentBackgroundExecution()
        }
    }

    private func finishAgentBackgroundExecution() {
        userInitiatedAgentWorkIsActive = false
        outstandingUserInitiatedTurns = 0
        backgroundAgentSessionID = nil
        endAgentBackgroundTask()
        if applicationIsInBackground {
            gateway.backgroundExecutionDidExpire()
        }
    }

    private func expireAgentBackgroundExecution() {
        endAgentBackgroundTask()
        if applicationIsInBackground {
            gateway.backgroundExecutionDidExpire()
        }
    }

    private func endAgentBackgroundTask() {
        let identifier = agentBackgroundTask
        guard identifier != .invalid else { return }
        agentBackgroundTask = .invalid
        UIApplication.shared.endBackgroundTask(identifier)
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
        sessionControlRequestTokens[kind] = nil
        sessionControlLoadingKinds.remove(kind)
        if kind == "models" {
            pendingModelsSessionId = nil
            isPendingGlobalModelsRequest = false
        }
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
        let token = UUID()
        defaultConfigurationRequestTokens[kind] = token
        defaultConfigurationLoadingKinds.insert(kind)
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(12))
            guard let self, self.defaultConfigurationRequestTokens[kind] == token else { return }
            self.finishDefaultConfigurationRequest(kind)
            self.lastError = String(localized: "control.request.timeout.v0111", defaultValue: "\(kind) 请求超时，请检查 Mobile Gateway v0.1.11。")
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
        let detail = String(localized: "permissions.state.invalid", defaultValue: "权限状态 \"\(value)\" 无效；仅支持 read-only、workspace-write、danger-full-access。")
        lastError = detail
        notice(String(localized: "permissions.state.error.source", defaultValue: "权限状态错误 · \(source)"), detail, sessionId: sessionId, isError: true)
    }

    private func sessionControlKind(from value: String) -> String? {
        ["permission-options", "select-model", "context-usage", "session-stats", "permission", "models"]
            .first { value.localizedCaseInsensitiveContains($0) }
    }
    private func upsertSession(id: String, title: String?) {
        if let index = sessions.firstIndex(where: { $0.id == id }) {
            if let title, !title.isEmpty, sessions[index].title == L10n.newSessionPlaceholderTitle || sessions[index].title.hasPrefix(L10n.remoteSessionTitlePrefix) { sessions[index].title = title }
        } else {
            sessions.insert(SessionSummary(id: id, title: title ?? L10n.remoteSessionTitle(id.prefix(8)), lastActivity: .now, isRunning: false, hasUnread: false), at: 0)
        }
    }

    private func markRead(_ id: String) {
        guard let index = sessions.firstIndex(where: { $0.id == id }) else { return }
        // Mutating an already-false array element still triggers `sessions`'
        // didSet, which encodes the complete list and writes UserDefaults on
        // the main actor. Avoid paying that cost on every navigation push.
        guard sessions[index].hasUnread else { return }
        sessions[index].hasUnread = false
    }
    private func persistSessions() {
        if let data = try? JSONEncoder().encode(sessions) { UserDefaults.standard.set(data, forKey: "gateway.sessions") }
    }
}

private enum PairingPayloadError: LocalizedError {
    case invalidBase64URL
    case invalidJSON
    case unsupportedVersion(Int)
    case invalidEndpoint
    case invalidCode
    case expired

    var errorDescription: String? {
        switch self {
        case .invalidBase64URL:
            String(localized: "配对内容不是有效的 Base64URL 字符串。")
        case .invalidJSON:
            String(localized: "Base64URL 解码后的内容不是有效的 DeepSeek Harness 配对 JSON。")
        case .unsupportedVersion(let version):
            String(localized: "pairing.unsupported.version", defaultValue: "不支持的配对协议版本 \(version)，当前客户端需要 version 2。")
        case .invalidEndpoint:
            String(localized: "二维码中的 publicUrl 不是有效的 WebSocket 地址。")
        case .invalidCode:
            String(localized: "二维码中的一次性 pairingCode 无效。")
        case .expired:
            String(localized: "二维码配对码已经过期，请在 WebUI 中重新生成。")
        }
    }
}
