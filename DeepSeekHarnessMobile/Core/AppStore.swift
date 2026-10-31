import SwiftUI
import UniformTypeIdentifiers
import DeepSeekHarnessShared

/// 连续时钟包含设备休眠时间，同时不受手机日期、时区或校时影响。
enum AgentTurnMonotonicClock {
    private static let clock = ContinuousClock()
    private static let origin = clock.now
    static var now: Double {
        let elapsed = origin.duration(to: clock.now).components
        return Double(elapsed.seconds) + Double(elapsed.attoseconds) / 1e18
    }
}

struct AgentLiveActivityProgress: Equatable {
    let headline: String
    let detail: String?
    let kind: AgentActivityStepKind
    let toolName: String?
}

private struct AgentLiveActivityStreamBuffer {
    var attemptID: String
    var chunkType: String
    var text: String
    var toolName: String?
    var step: Int?
}

/// 把 Gateway 的执行轨迹压缩成实时活动可显示的一行当前状态。
enum AgentLiveActivityEventProjection {
    static func progress(
        for event: GatewayEvent,
        completedToolLabel: String? = nil
    ) -> AgentLiveActivityProgress? {
        switch event.type {
        case "user/message" where event.source?.isEmpty == false && event.source != "user":
            return progress(
                "上下文注入 · \(event.source!)",
                detail: preview(event.text),
                kind: .context
            )
        case "step/start":
            return progress(
                "正在分析执行步骤",
                detail: event.step.map { "第 \($0) 步" },
                kind: .reasoning
            )
        case "step/end":
            return progress(
                event.step.map { "第 \($0) 步已完成" } ?? "当前步骤已完成",
                detail: "正在准备下一步",
                kind: .result
            )
        case "request/context":
            let source = firstText(
                event.raw?["source"]?["kind"], event.raw?["source"], event.raw?["catalog"],
                event.raw?["skill"], event.raw?["name"]
            )
            return progress(
                source.map { "上下文注入 · \($0)" } ?? "上下文注入",
                detail: preview(firstText(event.raw?["text"], event.raw?["content"])),
                kind: .context
            )
        case "request/header":
            let provider = event.raw?["header"]?["config"]?["provider"]?.stringValue
            let model = event.raw?["header"]?["config"]?["model"]?.stringValue
            return progress(
                "准备 Agent 上下文",
                detail: preview([provider, model].compactMap { $0 }.joined(separator: " · ")),
                kind: .context
            )
        case "assistant/message":
            if let text = preview(event.text), !text.isEmpty {
                return progress("Agent 回复", detail: text, kind: .message)
            }
            if let reasoning = preview(event.reasoning), !reasoning.isEmpty {
                return progress("思考", detail: reasoning, kind: .reasoning)
            }
            if let call = event.toolCalls?.first {
                return toolProgress(name: call.name, arguments: call.arguments)
            }
            return nil
        case "assistant/attempt":
            return progress("正在重新生成回复", detail: "Agent 正在重试当前步骤", kind: .reasoning)
        case "tool/call", "tool/code-dispatch-start":
            return toolProgress(name: event.name, arguments: event.arguments)
        case "tool/result", "tool/code-dispatch":
            let failed = event.isError == true
            let label = completedToolLabel?.trimmingCharacters(in: .whitespacesAndNewlines)
            let headline = label?.isEmpty == false
                ? "\(label!)\(failed ? "失败" : "完成")"
                : (failed ? "工具执行失败" : "工具执行完成")
            return progress(
                headline,
                detail: preview(event.preview ?? event.error),
                kind: failed ? .tool : .result,
                toolName: event.name
            )
        case "command/run":
            return progress(
                "执行命令",
                detail: preview(event.args ?? firstText(event.raw?["command"], event.raw?["text"])),
                kind: .command
            )
        case "command/done":
            return progress(
                event.isError == true ? "命令执行失败" : "命令执行完成",
                detail: preview(event.error ?? event.outcome ?? event.text),
                kind: event.isError == true ? .command : .result
            )
        case "compaction/start":
            return progress("正在整理会话上下文", detail: "压缩历史记录以继续执行", kind: .context)
        case "compaction/end":
            return progress("会话上下文已整理", detail: "Agent 正在继续执行", kind: .result)
        case "artifact/delivered", "file/delivered", "delivery/file":
            return progress(
                "交付文件",
                detail: preview(firstText(event.raw?["path"], event.raw?["file"]) ?? event.text),
                kind: .delivery
            )
        case "permission/preset":
            return progress(
                "应用权限配置",
                detail: preview(firstText(event.raw?["preset"], event.raw?["name"])),
                kind: .preparing
            )
        default:
            return nil
        }
    }

    static func streamingProgress(
        chunkType: String,
        text: String?,
        toolName: String?
    ) -> AgentLiveActivityProgress? {
        switch chunkType {
        case "reasoning-delta":
            return progress("思考", detail: preview(text), kind: .reasoning)
        case "text-delta":
            return progress("正在生成回复", detail: preview(text), kind: .message)
        case "tool-call-delta":
            return progress(
                toolName?.isEmpty == false ? "准备调用 \(toolName!)" : "正在准备工具调用",
                detail: nil,
                kind: .tool,
                toolName: toolName
            )
        default:
            return nil
        }
    }

    private static func toolProgress(name: String?, arguments: JSONValue?) -> AgentLiveActivityProgress {
        let displayName = name?.trimmingCharacters(in: .whitespacesAndNewlines)
        let summary = ToolActivitySummaryFormatter.shared.summarize(
            name: displayName?.isEmpty == false ? displayName! : "工具",
            arguments: arguments?.jsonDisplayText ?? ""
        )
        let detail = [summary.detail, summary.annotation]
            .filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
            .joined(separator: " · ")
        let kind: AgentActivityStepKind
        switch summary.label {
        case "写入": kind = .writing
        case "修改": kind = .editing
        case "读取": kind = .reading
        case "执行": kind = .command
        case "搜索", "列出", "获取": kind = .searching
        default: kind = .tool
        }
        return progress(
            summary.label,
            detail: preview(detail),
            kind: kind,
            toolName: name
        )
    }

    private static func progress(
        _ headline: String,
        detail: String?,
        kind: AgentActivityStepKind,
        toolName: String? = nil
    ) -> AgentLiveActivityProgress {
        AgentLiveActivityProgress(
            headline: headline,
            detail: detail?.isEmpty == false ? detail : nil,
            kind: kind,
            toolName: toolName
        )
    }

    private static func firstText(_ values: JSONValue?...) -> String? {
        for value in values {
            if let text = value?.stringValue, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                return text
            }
        }
        return nil
    }

    private static func preview(_ value: String?) -> String? {
        guard let value else { return nil }
        let normalized = value
            .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalized.isEmpty else { return nil }
        return String(normalized.prefix(180))
    }
}

@MainActor
protocol GatewayQuestionEffectExecuting: AnyObject {
    func answerQuestion(rpcId: String, sessionId: String, answers: [GatewayQuestionAnswer])
    func cancelQuestion(rpcId: String, sessionId: String)
}

extension GatewayClient: GatewayQuestionEffectExecuting {}

@MainActor
protocol GatewayApprovalEffectExecuting: AnyObject {
    func respondToApproval(
        rpcId: String,
        sessionId: String,
        approvalId: String,
        outcome: GatewayApprovalOutcome
    )
}

extension GatewayClient: GatewayApprovalEffectExecuting {}

@MainActor
protocol GatewaySessionControlEffectExecuting: AnyObject {
    func requestModels(sessionId: String?)
    func requestPermissionOptions(sessionId: String?)
    func requestContextUsage(sessionId: String)
    func requestSessionStats(sessionId: String)
    func requestAgentPresets()
    func requestDefaults()
    func requestDefaultModel()
    func selectModel(sessionId: String, provider: String, model: String, reasoningEffort: String?)
    func setPermission(sessionId: String, name: String)
    func saveDefaultModel(provider: String, model: String, reasoningEffort: String?)
    func setDefault(target: String, value: String)
}

extension GatewayClient: GatewaySessionControlEffectExecuting {}

private extension GatewayQuestionRequestStatus {
    var isAnswerInFlight: Bool {
        switch self {
        case .submitting(.answer), .accepted(.answer): true
        case .idle, .submitting(.cancel), .accepted(.cancel), .rejected: false
        }
    }
}

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

private struct TrajectoryHistoryVersion: Equatable {
    let revision: Int
    let count: Int
    let lastSequence: Int?
}

@MainActor
final class AppStore: ObservableObject {
    static let ungroupedWorkspaceID = WorkspaceSessionScope.ungrouped
    static let allWorkspacesID = WorkspaceSessionScope.all

    let teamStore = SharedTeamStore()
    @Published private(set) var teamSnapshot = MobileTeamSnapshot()
    @Published private(set) var teamLeadSessionID: String?
    @Published private(set) var persistedTeamRouting = "[]"
    private var persistedTeamAvatars = "[]"
    private var teamMemberProfileTimeouts: [String: Task<Void, Never>] = [:]

    var teamSessionTitle: String {
        sessions.first { $0.id == teamLeadSessionID }?.title ?? "Lead 会话"
    }

    var isViewingTeamMember: Bool { selectedSessionId.map { teamStore.isChild(sessionId: $0) } == true }
    var selectedMemberCanSend: Bool { selectedSessionId.map { teamStore.canSend(sessionId: $0) } ?? true }

    private func publishTeamSnapshot() {
        guard let lead = teamLeadSessionID,
              let data = teamStore.snapshotJson(leadSessionId: lead).data(using: .utf8),
              let snapshot = try? JSONDecoder().decode(MobileTeamSnapshot.self, from: data) else { return }
        if teamSnapshot != snapshot { teamSnapshot = snapshot }
        let avatars = teamStore.avatarAssignmentsJson()
        if avatars != persistedTeamAvatars {
            persistedTeamAvatars = avatars
            persistOfflineCatalog()
        }
        prepareTeamMemberProfiles()
    }

    private struct TeamMemberProfileRequest: Decodable {
        let sessionId: String
        let requestId: String
        let payload: String
    }

    private func prepareTeamMemberProfiles() {
        guard let lead = teamLeadSessionID, gateway.state.isConnected,
              let data = teamStore.memberProfileRequestsJson(leadSessionId: lead).data(using: .utf8),
              let requests = try? JSONDecoder().decode([TeamMemberProfileRequest].self, from: data) else { return }
        for request in requests {
            gateway.sendRequestPayload(request.payload)
            teamMemberProfileTimeouts[request.requestId] = Task { [weak self] in
                do { try await Task.sleep(for: .seconds(10)) } catch { return }
                guard let self else { return }
                teamMemberProfileTimeouts.removeValue(forKey: request.requestId)
                teamStore.finishMemberProfileRequest(requestId: request.requestId)
                prepareTeamMemberProfiles()
            }
        }
    }

    func refreshTeam() {
        requestTeam(refresh: true)
    }

    private func ensureTeamLoaded() {
        requestTeam(refresh: false)
    }

    private func requestTeam(refresh: Bool) {
        guard let lead = teamLeadSessionID, gateway.state.isConnected else { return }
        for kind in ["agent-team", "subagents"] {
            let payload = refresh
                ? teamStore.queryJson(leadSessionId: lead, kind: kind)
                : teamStore.queryIfNeededJson(leadSessionId: lead, kind: kind)
            if let payload { gateway.sendRequestPayload(payload) }
        }
    }

    func selectTeamMember(_ member: MobileTeamMember) async {
        guard member.canOpen, let lead = teamLeadSessionID,
              member.id != kmpSessionListStore.snapshot.selectedSessionId else { return }
        let summary = sessions.first { $0.id == member.id } ?? SessionSummary(
            id: member.id, title: member.name, lastActivity: Date(), isRunning: member.running == true, hasUnread: false)
        guard await prepareConversation(for: summary, teamLeadID: lead) else { return }
        await activatePreparedConversation(sessionID: member.id)
    }

    @Published private(set) var selectedSessionId: String?
    @Published private(set) var sessions: [SessionSummary] = []
    @Published private(set) var scheduledTasks: [ScheduledTask] = []
    @Published private(set) var scheduledTasksLoading = false
    @Published private(set) var scheduledTasksError: String?
    private var scheduledTasksRequestID = UUID()
    @Published private(set) var scheduledTaskPendingID: String?
    @Published private(set) var scheduledTaskCompletedRequestID: String?
    @Published private(set) var scheduledTaskMutationError: String?
    @Published private(set) var pluginCatalog: MobilePluginCatalog?
    @Published private(set) var pluginsLoading = false
    @Published private(set) var pluginsError: String?
    @Published private(set) var pluginPendingName: String?
    @Published private(set) var pluginNotice: String?
    private var pluginCatalogRequestID: String?
    private var pluginToggleRequestID: String?
    private var pluginPageVisited = false
    private var scheduledTaskMutationRequestID: String?
    private var scheduledTaskMutationKind: String?
    @Published var workspaces: [GatewayWorkspace] = []
    @Published var selectedWorkspaceId: String? {
        didSet {
            preferences.selectedWorkspaceID = selectedWorkspaceId
            offlineWorkspaceSelectionRevision &+= 1
            persistOfflineCatalog()
        }
    }
    @Published private(set) var archivedSessionIds: Set<String> = []
    /// Raw protocol storage. UI updates are driven by the throttled compact
    /// conversation projection instead of invalidating every view for every
    /// token frame.
    var events: [String: [SessionEvent]] = [:]
    /// Compact presentation snapshots are intentionally not published through
    /// AppStore. Token deltas go straight to a session-scoped timeline so they
    /// cannot invalidate the header, pager, composer, or trajectory page.
    private(set) var renderedConversationItems: [String: [ConversationItem]] = [:]
    @Published private(set) var conversationContentSessionIds: Set<String> = []
    @Published private(set) var conversationPreparingSessionIDs: Set<String> = []
    @Published private(set) var historyLoadErrors: [String: String] = [:]
    @Published private(set) var historyHasMore: [String: Bool] = [:]
    @Published private(set) var offlineHistoryBoundarySessionIds: Set<String> = []
    private var offlineHistoryLoadingOlderSessionIds: [String: UUID] = [:]
    @Published private(set) var historyLoadingSessionIds: Set<String> = []
    @Published private(set) var historyLoadingOlderSessionIds: Set<String> = []
    @Published private(set) var historyLoadProgress: [String: HistoryLoadProgress] = [:]
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
    @Published private(set) var modelCatalogs: [String: GatewayModelCatalog] = [:]
    @Published private(set) var globalModelCatalog: GatewayModelCatalog?
    @Published private(set) var sessionPermissions: [String: GatewaySessionPermissions] = [:]
    @Published private(set) var contextSnapshots: [String: GatewayContextSnapshot] = [:]
    @Published private(set) var sessionStatsSnapshots: [String: GatewaySessionStatsSnapshot] = [:]
    @Published private(set) var sessionControlLoadingKinds: Set<String> = []
    @Published private(set) var agentPresets: [GatewayAgentPreset] = []
    @Published private(set) var agentPresetsLoadError: String?
    @Published private(set) var agentPresetsAuthorable = false
    @Published private(set) var agentPresetsHasDocument = false
    @Published private(set) var agentPresetDefault: String?
    @Published private(set) var permissionDefault: String?
    @Published private(set) var permissionDefaultOptions: [GatewayPermissionOption] = []
    @Published private(set) var defaultModelSelection: GatewayModelSelection?
    @Published private(set) var defaultConfigurationLoadingKinds: Set<String> = []
    @Published private(set) var pendingQuestionRequests: [GatewayPendingQuestionRequest] = []
    @Published private(set) var questionRequestStatuses: [String: GatewayQuestionRequestStatus] = [:]
    @Published private(set) var pendingApprovalRequests: [GatewayPendingApprovalRequest] = []
    @Published private(set) var approvalRequestStatuses: [String: GatewayApprovalRequestStatus] = [:]
    let fileUploads: FileUploadController
    private var submittedUploadIDs: [String] = []

    @Published private(set) var supportsImages = false
    @Published private(set) var supportsFileDownloads = false
    private var supportsFileReferences = false
    @Published private(set) var supportsSlashCommands = false
    private var supportsSessionCreation = false
    private var supportsSessionAgentPreset = false
    @Published private(set) var sessionAgentPreset = SharedSessionAgentPresetStore().snapshot()
    private let sessionAgentPresetStore = SharedSessionAgentPresetStore()
    private var sessionAgentPresetTimeout: Task<Void, Never>?
    private var isPreparingNewConversation = false
    @Published private(set) var supportsTasks = false
    @Published private(set) var supportsGoals = false
    @Published private(set) var supportsSessionCancel = false
    @Published private(set) var cancellingSessionIDs: Set<String> = []
    @Published private(set) var taskProjections: [String: GatewayTasksProjection] = [:]
    @Published private(set) var goalProjections: [String: GatewayGoalProjection] = [:]
    @Published private(set) var goalMutationKind: String?
    @Published private(set) var slashCommands: SharedSlashCommandSnapshot
    @Published private(set) var commandSubmissionPending = false
    @Published private(set) var commandDraftClearToken = 0
    @Published private(set) var workspaceFilePath = "."
    @Published private(set) var workspaceFileEntries: [GatewayDirectoryItem] = []
    @Published private(set) var workspaceFilesAreLoading = false
    @Published private(set) var workspaceFileDownloadProgress: Double?
    @Published private(set) var workspaceFileDownloadPath: String?
    @Published private(set) var workspaceFileDownloadPurpose: String?
    @Published private(set) var workspaceFilePreviewProgress: Double = 0
    @Published private(set) var workspaceFilePreviewRequest: WorkspaceFilePreviewRequest?
    @Published private(set) var workspaceFilePreviewError: String?
    @Published var completedWorkspaceFile: WorkspaceLocalFile? {
        didSet {
            if let old = oldValue, old.url != completedWorkspaceFile?.url {
                let temporary = old.url.path.hasPrefix(WorkspacePreviewCache.temporaryRoot.path + "/")
                WorkspaceFileLifetime.shared.release(old.url, deleteTemporary: temporary)
            }
        }
    }
    @Published private(set) var conversationFilePreviewRequested = false
    @Published private(set) var conversationFileIsOpening = false {
        didSet { if !conversationFileIsOpening { workspaceFilePreviewTimeoutTask?.cancel() } }
    }
    @Published var conversationDirectoryPath: String?
    private(set) var conversationDirectorySessionID: String?

    let gateway: GatewayClient
    let voiceInput: VoiceInputAdapter
    let gatewayLocalID: String
    var pairingHandler: ((String) throws -> Void)?
    var gatewayDisplayName = ""
    private var offlineCatalogRestoreTask: Task<Void, Never>?
    private var offlineCatalogWriteTask: Task<Void, Never>?
    private var offlineCatalogRestored = false
    private var offlineCatalogActive = true
    private var offlineSessionRevision: UInt64 = 0
    private var offlineSessionSelectionRevision: UInt64 = 0
    private var offlineArchiveRevision: UInt64 = 0
    private var offlineWorkspaceRevision: UInt64 = 0
    private var offlineWorkspaceSelectionRevision: UInt64 = 0
    private var offlineCatalogWriteRevision: UInt64 = 0
    private let chatHistoryCache: ChatHistoryCache?
    private let chatCacheProtectionOwner: ChatHistoryCache.ProtectionOwner?
    private var chatCacheWrites: [String: Task<Void, Never>] = [:]
    private var chatPrefetchRecoverySessionID: String?
    private lazy var chatPrefetcher: ChatSessionPrefetcher = {
        ChatSessionPrefetcher(
            rank: { [weak self] candidates, selected in
                guard let self, let cache = self.chatHistoryCache, let owner = self.chatCacheProtectionOwner else { return [] }
                return await cache.planPrefetch(owner: owner, candidates: candidates, selected: selected)
            },
            ticket: { [weak self] id in
                guard let self, let cache = self.chatHistoryCache, let owner = self.chatCacheProtectionOwner else { return nil }
                return await cache.prefetchTicket(owner: owner, sessionID: id)
            },
            accept: { [weak self] id, json, ticket in
                guard let self, let cache = self.chatHistoryCache, let owner = self.chatCacheProtectionOwner else { return false }
                return await cache.acceptPrefetch(owner: owner, sessionID: id, json: json, ticket: ticket)
            },
            invalidate: { [chatHistoryCache, chatCacheProtectionOwner] id, ticket in
                guard let cache = chatHistoryCache, let owner = chatCacheProtectionOwner else { return }
                await cache.cancelPrefetch(owner: owner, sessionID: id, ticket: ticket)
            },
            request: { [weak self] id, _ in
                self?.gateway.requestHistory(sessionId: id, maxMessages: 80, maxBytes: 256 * 1024,
                    view: "conversation")
            },
            foregroundReady: { [weak self] id in
                guard let self, self.selectedSessionId == id, self.historyLoadingSessionIds.contains(id) else { return }
                self.requestHistoryPage(for: id, beforeSeq: self.historyLoadingOlderSessionIds.contains(id) ? self.events[id]?.first?.seq : nil)
            },
            reconnectForeground: { [weak self] id in
                guard let self, self.selectedSessionId == id else { return }
                self.chatPrefetchRecoverySessionID = id
                self.resetOutstandingRequests()
                // 旧协议不能在原 socket 上丢槽重发；只在前台确需该会话时重建一次连接。
                self.gateway.connect(to: self.endpoint)
            }
        )
    }()

    private var restoringChatCache = false
    private var chatCacheSelectionGeneration = 0
    private var chatCacheHelloReceived = false
    private var chatCacheHelloFormat: Int?
    private var conversationMemoryAccess: [String: UInt64] = [:]
    private var conversationMemoryTrimTask: Task<Void, Never>?
    private var conversationMemoryTrimID = UUID()
    private var evictedConversationSessionIDs: Set<String> = []
    private var localSearchTask: Task<Void, Never>?
    private var localSearchQuery = ""
    private var remoteSearchGate = RemoteSearchGate()
    private var remoteSearchTimeout: Task<Void, Never>?
    private var localSearchResults: [GatewaySearchItem] = []
    private var localSearchSequences: [String: Int] = [:]
    @Published private(set) var conversationSearchTarget: String?
    @Published private(set) var isShowingSearchContext = false
    @Published private(set) var searchContextHasMore = false
    @Published private(set) var searchContextLoadingOlder = false
    private var searchContextLoadingOperationID: UUID?
    private var searchContextRecords: [SessionEvent] = []
    private var searchContextTimeline: ConversationTimeline?
    private var searchContextProjector: KMPConversationStoreAdapter?
    @Published private(set) var searchCoverage = ""
    @Published private(set) var storageUsage: AppStorageUsage?
    @Published private(set) var cacheStorageNotice: String?
    @Published private(set) var cacheStorageBusy = false
    private let preferences: AppPreferences
    /// SessionList 的唯一业务状态来源；Swift 属性只是 UI/持久化快照。
    private let kmpSessionListStore: KMPSessionListStoreAdapter
    /// Human Question 的唯一业务状态来源；Swift 属性只发布 KMP 快照。
    private let kmpQuestionStore: KMPQuestionStoreAdapter
    private let questionEffectExecutor: any GatewayQuestionEffectExecuting
    /// 操作审批状态与一次性响应 effect 的唯一业务来源。
    private let kmpApprovalStore: KMPApprovalStoreAdapter
    private let approvalEffectExecutor: any GatewayApprovalEffectExecuting
    /// SessionControl 的唯一业务状态来源；Swift 属性只发布 KMP 快照。
    private let kmpSessionControlStore: KMPSessionControlStoreAdapter
    private let sessionControlEffectExecutor: any GatewaySessionControlEffectExecuting
    /// Conversation projector 的唯一业务状态来源；Swift 只在屏幕刷新节奏发布 KMP patch 镜像。
    private let kmpConversationStore: KMPConversationStoreAdapter
    /// Trajectory 仅在页面活跃时由 KMP 增量计算，避免后台 token 产生无用工作。
    private let kmpTrajectoryStore: KMPTrajectoryStoreAdapter
    /// History pagination、cursor、水位与 raw event 去重的唯一业务状态来源。
    private let kmpHistoryStore: KMPHistoryStoreAdapter
    /// 文件列表、分块偏移和 SHA-256 完整性校验由 KMP commonMain 统一负责。
    private let kmpWorkspaceFileStore: DeepSeekHarnessShared.SharedWorkspaceFileStore
    /// 斜杠命令目录、UI 描述符、二级选项与请求关联由 commonMain 统一解析。
    private let kmpSlashCommandStore = DeepSeekHarnessShared.SharedSlashCommandStore()
    private let queueStore = DeepSeekHarnessShared.SharedQueueStore()
    @Published private(set) var queueState = DeepSeekHarnessShared.SharedQueueSnapshot(queues: [:], pendingItemId: nil, lastError: nil)
    @Published private(set) var queueRevision = 0
    @Published private(set) var messageSubmissionPending = false
    private var messageSubmissionRequestID: String?
    private var messageSubmissionSessionID: String?
    @Published private(set) var messageAcceptedRevision = 0
    private var messageSubmissionTimeout: Task<Void, Never>?
    @Published private(set) var supportsQueueControl = false
    private var queueActionTimeout: Task<Void, Never>?
    private var queueProjectionSessionIDs: Set<String> = []

    var selectedQueueItems: [DeepSeekHarnessShared.SharedQueueItem] {
        queueState.queues[selectedSessionId ?? ""] ?? []
    }

    func takeQueuedDraft() -> String? {
        guard let id = selectedSessionId else { return nil }
        return queueStore.takeDraft(sessionId: id)
    }

    func updateQueuedMessage(_ itemID: String, action: String) {
        guard supportsQueueControl, gateway.state.isConnected, let id = selectedSessionId,
              let request = queueStore.beginAction(sessionId: id, itemId: itemID, action: action) else { return }
        queueState = queueStore.snapshot()
        gateway.sendRequestPayload(request.payload)
        queueActionTimeout?.cancel()
        queueActionTimeout = Task { [weak self] in
            do { try await Task.sleep(for: .seconds(20)) } catch { return }
            guard let self, self.queueState.pendingItemId == itemID else { return }
            self.queueState = self.queueStore.failPending(message: "队列操作超时，请检查队列最新状态后重试")
            self.lastError = self.queueState.lastError
        }
    }

    private func acceptQueueFrame(_ frame: GatewayFrame) {
        guard ["session-queues", "session-queue", "queue-item-updated"].contains(frame.kind) ||
                (frame.kind == "error" && frame.requestType == "queue-update"),
              let data = try? JSONEncoder().encode(frame) else { return }
        do {
            if frame.kind == "session-queues", let queues = frame.queues {
                for id in queueProjectionSessionIDs.union(queues.keys) {
                    guard queueProjectionSessionIDs.contains(id) || !(queues[id] ?? []).isEmpty else { continue }
                    try flushPendingConversationHistory(sessionID: id)
                    try kmpConversationStore.replaceSteeringMessages(sessionID: id, items: queues[id] ?? [])
                }
                queueProjectionSessionIDs = Set(queues.filter { !$0.value.isEmpty }.keys)
            } else if frame.kind == "session-queue", let id = frame.sessionId, let items = frame.items {
                if queueProjectionSessionIDs.contains(id) || !items.isEmpty {
                    try flushPendingConversationHistory(sessionID: id)
                    try kmpConversationStore.replaceSteeringMessages(sessionID: id, items: items)
                }
                if items.isEmpty { queueProjectionSessionIDs.remove(id) }
                else { queueProjectionSessionIDs.insert(id) }
            }
        } catch {
            lastError = error.localizedDescription
        }
        queueState = queueStore.acceptFrame(json: String(decoding: data, as: UTF8.self))
        if queueState.pendingItemId == nil { queueActionTimeout?.cancel() }
        if let error = queueState.lastError { lastError = error }
        backgroundExecutionController.updateQueuedSessions(Set(queueState.queues.filter { !$0.value.isEmpty }.keys))
        queueRevision &+= 1
    }

    /// KMP Store 会在 dispatch Intent 的同一 MainActor 调用栈内同步推送 Event。
    /// SwiftUI 的 `.task`/`.onChange` 或控件 Binding 可能仍处于 view update；直接
    /// 修改 `@Published` 会触发未定义行为。这里保持 FIFO，并统一在下一次
    /// MainActor 调度中发布 UI 镜像和执行同事务 effect。
    private var pendingKMPEventDeliveries: [@MainActor () -> Void] = []
    private var isKMPEventDeliveryScheduled = false
    private let historySyncEngine = HistorySyncEngine()
    private var assistantStreamState = AssistantStreamState()
    private var liveActivityStreamBuffers: [String: AgentLiveActivityStreamBuffer] = [:]
    private var liveActivityStreamTasks: [String: Task<Void, Never>] = [:]
    private var liveActivityToolLabels: [String: [String: String]] = [:]
    private var usesAssistantStream = false
    private var pendingSnapshotSessionID: String?
    private let historyRequestTimeout: Duration
    private let sessionControlRequestTimeout: Duration
    private var conversationProjectionDrivers: [String: ConversationProjectionDriver] = [:]
    private var conversationTimelines: [String: ConversationTimeline] = [:]
    private var trajectoryTimelines: [String: TrajectoryTimeline] = [:]
    private var activeTrajectorySessionIDs: Set<String> = []
    private var trajectoryProjectionDrivers: [String: ConversationProjectionDriver] = [:]
    private var pendingTrajectoryEvents: [String: [SessionEvent]] = [:]
    private var trajectoryBatchSessionID: String?
    private var trajectoryHistoryRevisions: [String: Int] = [:]
    private var trajectoryProjectedVersions: [String: TrajectoryHistoryVersion] = [:]
    /// Invalidates an in-flight cold projection when a completed history
    /// baseline is atomically installed for the same session.
    private var conversationProjectionEpochs: [String: Int] = [:]
    private let historyPresentationAdmission = HistoryPresentationAdmission()
    private var historyReadingBatches: [String: HistoryReadingBatch] = [:]
    private var historyPresentationRequiredEpochs: [String: Int] = [:]
    private var historyPresentationCommittedEpochs: [String: Int] = [:]
    private struct ConversationHistoryRequest {
        let records: [SessionEvent]
        let snapshot: SharedHistoryProjectionSnapshot
        let input: KMPConversationHistoryPreparation
        let epoch: Int
    }
    private var pendingConversationHistory: [String: ConversationHistoryRequest] = [:]
    private var activeConversationHistory: [String: ConversationHistoryRequest] = [:]
    // 物理 worker 未返回时不释放 slot；逻辑取消只使 epoch/pending 失效。
    private var conversationHistoryWorkerIDs: [String: UUID] = [:]
    /// Decoded bytes live outside the raw/history message models. A bounded
    /// memory layer fronts a seven-day, purgeable on-disk cache.
    private let imageAttachmentCache: ImageAttachmentCache
    private var attachmentLoader = AttachmentLoader()
    private var pendingModelsSessionId: String?
    private var isPendingGlobalModelsRequest = false
    private var pendingModelSelectionSessionId: String?
    private var pendingPermissionOptionsSessionId: String?
    private var pendingDirectoryCreationParentPath: String?
    private var workspaceFileHandle: FileHandle?
    private var workspaceFileOperationID = UUID()
    private var workspaceFilePreviewLoad: ((String) -> Void)?
    private var workspaceFilePreviewNeedsConnection = false
    private var workspaceFilePreviewTimeoutTask: Task<Void, Never>?
    private let filePreviewTimeout: Duration
    private var workspaceFileTemporaryURL: URL?
    private var workspaceFileOperationCache: WorkspacePreviewCache?
    private var imageAttachmentRequestEpochs: [String: UInt64] = [:]
    private let sessionControlRequestTracker = RequestTracker()
    private let defaultConfigurationRequestTracker = RequestTracker()
    private var sessionControlRequestsSuspended = false
    private var deferredSessionControlEffects: [KMPSessionControlEffect] = []
    /// Navigation preparation is intentionally cheap. Remote activation begins
    /// from the destination lifecycle, after NavigationStack installs its bar.
    private var preparedConversationActivationKey: String?
    private var activeConversationActivationKey: String?
    /// 返回主页后仍在运行的会话需要保留事件订阅，供实时活动和通知使用。
    private var backgroundMonitoredSessionID: String?
    private var presentsNextConnectionFailureAsAlert = false
    private var hasHandledColdLaunchConnection = false
    private var isCheckingColdLaunchCredential = false
    private let backgroundExecutionController: AgentBackgroundExecutionController
    private let turnTiming = SharedTurnTimingStore()
    private let agentNotifications: AgentUserNotificationManager
    private static let defaultConfigurationRequestKinds: Set<String> = [
        "agent-presets", "defaults", "default-model", "set-default", "save-default-model"
    ]
    private static let workspaceFileFrameKinds: Set<String> = [
        "file-resolved", "file-list", "file-download-opened", "file-download-chunk", "file-download-cancelled"
    ]
    private static let goalMutationResponseKinds: Set<String> = [
        "goal-edit", "goal-pause", "goal-resume", "goal-clear"
    ]
    private static let newConversationActivationKey = "__new-conversation__"

    init(
        preferences: AppPreferences = UserDefaultsAppPreferences(),
        gatewayClient: GatewayClient? = nil,
        gatewayLocalID: String = "legacy",
        sessionListBridge: (any KMPSessionListStoreBridging)? = nil,
        questionBridge: (any KMPQuestionStoreBridging)? = nil,
        approvalBridge: (any KMPApprovalStoreBridging)? = nil,
        sessionControlBridge: (any KMPSessionControlStoreBridging)? = nil,
        conversationBridge: (any KMPConversationStoreBridging)? = nil,
        trajectoryBridge: (any KMPTrajectoryStoreBridging)? = nil,
        historyBridge: (any KMPHistoryStoreBridging)? = nil,
        questionEffectExecutor: (any GatewayQuestionEffectExecuting)? = nil,
        approvalEffectExecutor: (any GatewayApprovalEffectExecuting)? = nil,
        sessionControlEffectExecutor: (any GatewaySessionControlEffectExecuting)? = nil,
        backgroundExecutionController: AgentBackgroundExecutionController? = nil,
        agentNotifications: AgentUserNotificationManager? = nil,
        historyRequestTimeout: Duration = .seconds(20),
        filePreviewTimeout: Duration = .seconds(20),
        sessionControlRequestTimeout: Duration = .seconds(12),
        chatHistoryCache: ChatHistoryCache? = .shared,
        voiceInput: VoiceInputAdapter? = nil,
        offlineCatalogRestoreBarrier: (@Sendable () async -> Void)? = nil
    ) {
        gateway = gatewayClient ?? GatewayClient()
        self.voiceInput = voiceInput ?? VoiceInputAdapter()
        self.filePreviewTimeout = filePreviewTimeout
        self.historyRequestTimeout = historyRequestTimeout
        self.sessionControlRequestTimeout = sessionControlRequestTimeout
        self.gatewayLocalID = gatewayLocalID
        self.fileUploads = FileUploadController(gatewayID: gatewayLocalID)
        imageAttachmentCache = ImageAttachmentCache(directoryURL: FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first?.appendingPathComponent("GatewayAttachments/\(gatewayLocalID)", isDirectory: true))
        slashCommands = kmpSlashCommandStore.snapshot()
        self.preferences = preferences
        self.chatHistoryCache = historyBridge == nil ? chatHistoryCache : nil
        self.chatCacheProtectionOwner = historyBridge == nil ? chatHistoryCache?.makeProtectionOwner(gatewayID: gatewayLocalID) : nil
        self.questionEffectExecutor = questionEffectExecutor ?? gateway
        self.approvalEffectExecutor = approvalEffectExecutor ?? gateway
        self.backgroundExecutionController = backgroundExecutionController ?? AgentBackgroundExecutionController()
        self.agentNotifications = agentNotifications ?? .shared
        let persistedSessions = preferences.loadSessions()
        let kmpSessionListStore = KMPSessionListStoreAdapter(
            sessions: persistedSessions,
            bridge: sessionListBridge
        )
        self.kmpSessionListStore = kmpSessionListStore
        let kmpQuestionStore = KMPQuestionStoreAdapter(bridge: questionBridge)
        let kmpApprovalStore = KMPApprovalStoreAdapter(bridge: approvalBridge)
        self.kmpApprovalStore = kmpApprovalStore
        self.kmpQuestionStore = kmpQuestionStore
        let kmpSessionControlStore = KMPSessionControlStoreAdapter(bridge: sessionControlBridge)
        self.kmpSessionControlStore = kmpSessionControlStore
        let kmpConversationStore = KMPConversationStoreAdapter(bridge: conversationBridge)
        self.kmpConversationStore = kmpConversationStore
        let kmpTrajectoryStore = KMPTrajectoryStoreAdapter(bridge: trajectoryBridge)
        self.kmpTrajectoryStore = kmpTrajectoryStore
        let kmpHistoryStore = KMPHistoryStoreAdapter(bridge: historyBridge)
        self.kmpHistoryStore = kmpHistoryStore
        self.kmpWorkspaceFileStore = SharedMobileFacade().makeWorkspaceFileStore()
        self.sessionControlEffectExecutor = sessionControlEffectExecutor ?? gateway
        appLanguage = AppLanguage.load()
        selectedWorkspaceId = preferences.selectedWorkspaceID
        endpoint = preferences.endpoint
        // stage9-kmp-write-scope: initialization-begin
        selectedSessionId = kmpSessionListStore.snapshot.selectedSessionId
        sessions = kmpSessionListStore.snapshot.persistedSessions
        archivedSessionIds = kmpSessionListStore.snapshot.archivedSessionIDSet
        pendingQuestionRequests = kmpQuestionStore.snapshot.pendingRequests
        questionRequestStatuses = kmpQuestionStore.snapshot.platformStatuses
        pendingApprovalRequests = kmpApprovalStore.snapshot.pendingRequests
        approvalRequestStatuses = kmpApprovalStore.snapshot.platformStatuses
        // stage9-kmp-write-scope: initialization-end
        applySessionControlSnapshot(kmpSessionControlStore.snapshot)
        if let error = kmpSessionListStore.initializationError {
            lastError = error.localizedDescription
        }
        if let error = kmpQuestionStore.initializationError {
            lastError = error.localizedDescription
        }
        if let error = kmpSessionControlStore.initializationError {
            lastError = error.localizedDescription
        }
        preferences.performMigrations()
        imageAttachmentCache.onDataLoaded = { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self, let id = self.selectedSessionId else { return }
                if self.isShowingSearchContext, let timeline = self.searchContextTimeline {
                    timeline.publish(timeline.currentSnapshot.items)
                }
                if let items = self.renderedConversationItems[id] { self.conversationTimeline(for: id).publish(items) }
            }
        }
        let historyCache = chatHistoryCache
        if historyCache === ChatHistoryCache.shared {
            Task { _ = await AppCacheMaintenance.shared.run(cache: historyCache) }
        }
        kmpSessionListStore.onSnapshot = { [weak self] snapshot, error in
            self?.enqueueKMPEventDelivery { [weak self] in
                self?.handleSessionListEvent(snapshot: snapshot, error: error)
            }
        }
        kmpQuestionStore.onTransition = { [weak self] transition in
            self?.enqueueKMPEventDelivery { [weak self] in
                self?.handleQuestionEvent(transition)
            }
        }
        kmpSessionControlStore.onTransition = { [weak self] transition in
            self?.enqueueKMPEventDelivery { [weak self] in
                self?.handleSessionControlEvent(transition)
            }
        }
        kmpConversationStore.onChange = { [weak self] change in
            self?.enqueueKMPEventDelivery { [weak self] in
                self?.scheduleConversationProjection(for: change.sessionID)
            }
        }
        kmpConversationStore.onError = { [weak self] error in
            self?.enqueueKMPEventDelivery { [weak self] in
                self?.lastError = error.localizedDescription
            }
        }
        kmpTrajectoryStore.onChange = { [weak self] change in
            let publishedByBatch = self?.trajectoryBatchSessionID == change.sessionID
            self?.enqueueKMPEventDelivery { [weak self] in
                if !publishedByBatch { self?.publishTrajectory(sessionID: change.sessionID) }
            }
        }
        kmpTrajectoryStore.onError = { [weak self] error in
            self?.enqueueKMPEventDelivery { [weak self] in
                self?.lastError = error.localizedDescription
            }
        }
        kmpHistoryStore.onChange = { [weak self] change in
            self?.enqueueKMPEventDelivery { [weak self] in
                self?.handleHistoryChange(change)
            }
        }
        kmpHistoryStore.onError = { [weak self] error in
            self?.enqueueKMPEventDelivery { [weak self] in
                self?.lastError = error.localizedDescription
                self?.historyPresentationAdmission.cancelAll(outcome: .failed)
            }
        }
        fileUploads.changed = { [weak self] in self?.objectWillChange.send() }
        fileUploads.send = { [weak self] payload in self?.gateway.sendRequestPayload(payload, decorate: false) }
        fileUploads.reportError = { [weak self] message in self?.lastError = message }
        self.voiceInput.bind(gateway)
        gateway.conversationTarget = { [weak self] id in
            guard let self, let data = self.teamStore.targetJson(sessionId: id).data(using: .utf8) else { return nil }
            return try? JSONDecoder().decode(MobileConversationTarget.self, from: data)
        }
        gateway.requestDecorator = { [weak self] payload in
            guard let self else { return nil }
            if payload.contains("\"sessions\"") { self.teamStore.sessionsRequested() }
            return self.teamStore.decorateRequestJson(payload: payload)
        }
        gateway.onFrame = { [weak self] frame in self?.handle(frame) }
        gateway.onCommandFrame = { [weak self] json in
            guard let self else { return }
            self.applySlashCommandTransition(self.kmpSlashCommandStore.acceptFrame(json: json))
        }
        gateway.onConnectionFailure = { [weak self] detail in
            self?.handleConnectionFailure(detail)
        }
        gateway.onConnectionInvalidated = { [weak self] in
            self?.pauseSessionControlRequests()
            if let self, self.conversationFileIsOpening, self.workspaceFilePreviewNeedsConnection {
                self.failWorkspaceFilePreview(String(localized: "连接已中断，请恢复网络后重试。"))
            }
            self?.resetOutstandingRequests()
        }
        gateway.onConnectionCheckStarted = { [weak self] in self?.pauseSessionControlRequests() }
        gateway.onConnectionReady = { [weak self] in self?.resumeSessionControlRequests() }
        offlineCatalogRestoreTask = Task { [weak self] in
            await offlineCatalogRestoreBarrier?()
            guard let self, offlineCatalogActive else { return }
            let json = await self.chatHistoryCache?.loadOfflineCatalog(gatewayID: gatewayLocalID)
            guard offlineCatalogActive, !Task.isCancelled else { return }
            restoreOfflineCatalog(json)
        }
        self.backgroundExecutionController.onKeepAlivePulse = { [weak self] in
            if self?.gateway.state.isConnected == true { self?.gateway.ping() }
            AgentLiveActivityManager.shared.refreshActiveActivities()
        }
    }

    var selectedTurnTiming: SharedTurnTimingSnapshot? {
        selectedSessionId.flatMap { turnTiming.snapshot(sessionId: $0) }
    }

    var selectedEvents: [SessionEvent] {
        guard let selectedSessionId else { return [] }
        return isShowingSearchContext ? searchContextRecords : events[selectedSessionId, default: []]
    }
    var selectedConversationItems: [ConversationItem] {
        guard let selectedSessionId else { return [] }
        return isShowingSearchContext ? searchContextTimeline?.currentSnapshot.items ?? [] : renderedConversationItems[selectedSessionId, default: []]
    }
    var selectedNotices: [GatewayNotice] {
        Array(protocolNotices.filter { $0.sessionId == nil || $0.sessionId == selectedSessionId }.suffix(20))
    }
    var selectedSession: SessionSummary? {
        if let member = teamSnapshot.members.first(where: { $0.id == selectedSessionId }), member.role != "lead" {
            return SessionSummary(id: member.id, title: member.name, lastActivity: Date(), isRunning: member.running == true, hasUnread: false)
        }
        return sessions.first { $0.id == selectedSessionId }
    }
    var isCancellingSelectedSession: Bool {
        selectedSessionId.map(cancellingSessionIDs.contains) == true
    }
    var selectedModelCatalog: GatewayModelCatalog? { selectedSessionId.flatMap { modelCatalogs[$0] } }
    var selectedPermissions: GatewaySessionPermissions? { selectedSessionId.flatMap { sessionPermissions[$0] } }
    var selectedContextSnapshot: GatewayContextSnapshot? { selectedSessionId.flatMap { contextSnapshots[$0] } }
    var selectedSessionStatsSnapshot: GatewaySessionStatsSnapshot? { selectedSessionId.flatMap { sessionStatsSnapshots[$0] } }
    var selectedTaskProjection: GatewayTasksProjection? { selectedSessionId.flatMap { taskProjections[$0] } }
    var selectedGoalProjection: GatewayGoalProjection? {
        selectedSessionId
            .flatMap { goalProjections[$0] }
            .flatMap { projection in
                projection.goal?.goal.phase.lowercased() == "complete" ? nil : projection
            }
    }
    var selectedPendingQuestionRequest: GatewayPendingQuestionRequest? {
        guard let selectedSessionId else { return nil }
        return pendingQuestionRequests.first { $0.sessionId == selectedSessionId }
    }
    var selectedPendingApprovalRequest: GatewayPendingApprovalRequest? {
        guard let selectedSessionId else { return nil }
        return pendingApprovalRequests.first { $0.sessionId == selectedSessionId }
    }

    func approvalArguments(for request: GatewayPendingApprovalRequest) -> JSONValue? {
        guard let callID = request.callId else { return nil }
        return events[request.sessionId, default: []]
            .last(where: { $0.event.callId == callID })?
            .event
            .arguments?
            .normalizedValue
    }

    func commandPreview(for request: GatewayPendingApprovalRequest) -> String? {
        if let arguments = approvalArguments(for: request),
           let command = arguments["cmd"]?.stringValue ?? arguments["command"]?.stringValue {
            return command
        }
        guard let callID = request.callId,
              let rawText = renderedConversationItems[request.sessionId, default: []]
              .last(where: { $0.id == "tool-\(callID)" })?.text else {
            return nil
        }
        let normalized = JSONValue.string(rawText).normalizedValue
        return normalized["cmd"]?.stringValue
            ?? normalized["command"]?.stringValue
            ?? (normalized.objectValue == nil ? rawText : nil)
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
    func displayedConversationTimeline(for sessionID: String) -> ConversationTimeline {
        if isShowingSearchContext, sessionID == selectedSessionId, let searchContextTimeline { return searchContextTimeline }
        return conversationTimeline(for: sessionID)
    }
    func attachHistoryPresentationConsumer(sessionID: String, consumer: HistoryPresentationConsumer) {
        historyPresentationAdmission.attach(sessionID: sessionID, consumer: HistoryPresentationConsumer(id: consumer.id) { [weak self] event in
            if case .finished(let id, _) = event {
                self?.conversationTimelines[sessionID]?.clearHistoryPresentationStamp(operationID: id)
                self?.searchContextTimeline?.clearHistoryPresentationStamp(operationID: id)
                if self?.historyReadingBatches.removeValue(forKey: sessionID) != nil {
                    // 失败、切换或卸载消费者时也释放已收到的有效历史。
                    self?.scheduleConversationProjection(for: sessionID)
                }
            }
            consumer.onEvent(event)
            if case .began = event { self?.updateChatPrefetchState() }
            if case .finished = event { self?.updateChatPrefetchState() }
        })
    }

    func detachHistoryPresentationConsumer(sessionID: String, consumerID: UUID) {
        historyPresentationAdmission.detach(sessionID: sessionID, consumerID: consumerID)
    }

    func acknowledgeHistoryPresentation(sessionID: String, consumerID: UUID, stamp: HistoryPresentationStamp) {
        historyPresentationAdmission.acknowledge(sessionID: sessionID, consumerID: consumerID, stamp: stamp,
            domainLoading: kmpHistoryStore.isLoading(sessionID: sessionID))
    }

    @discardableResult
    func requestOlderHistoryPresentation(sessionID: String, consumerID: UUID, readingWindow: CGSize? = nil) -> Bool {
        guard sessionID == selectedSessionId,
              let operationID = historyPresentationAdmission.begin(sessionID: sessionID, consumerID: consumerID,
                domainLoading: kmpHistoryStore.isLoading(sessionID: sessionID), hasMore: selectedHistoryHasMore,
                producerBusy: pendingConversationHistory[sessionID] != nil || activeConversationHistory[sessionID] != nil
                    || offlineHistoryLoadingOlderSessionIds[sessionID] != nil || searchContextLoadingOlder,
                minimumHistoryEventSequence: (kmpHistoryStore.lastEventSequence ?? 0) + 1, activate: false) else { return false }
        if !isShowingSearchContext, selectedSession?.isRunning != true,
           gateway.state.isConnected || kmpHistoryStore.canPrependCachedPage(sessionID: sessionID),
           let before = events[sessionID]?.first?.seq {
            let size = readingWindow ?? CGSize(width: 340, height: 560)
            historyReadingBatches[sessionID] = HistoryReadingBatch(operationID: operationID,
                originalIDs: Set(conversationTimeline(for: sessionID).currentSnapshot.items.map(\.id)),
                viewportHeight: max(160, Double(size.height)), contentWidth: max(120, Double(size.width)),
                fontScale: Double(UIFont.appPreferredFont(forTextStyle: .body).pointSize / 17),
                startedAt: ProcessInfo.processInfo.systemUptime, beforeSequence: before,
                minimumEventSequence: (kmpHistoryStore.lastEventSequence ?? 0) + 1)
            appLog("history-read-batch stage=start")
        }
        loadHistory(for: sessionID, older: true, presentationOperationID: operationID)
        return historyPresentationAdmission.matches(sessionID: sessionID, operationID: operationID)
    }

    private func requireHistoryPresentation(sessionID: String, historyEventSequence: Int64) {
        historyPresentationRequiredEpochs[sessionID, default: 0] &+= 1
        historyPresentationAdmission.requireProjection(sessionID: sessionID,
            epoch: historyPresentationRequiredEpochs[sessionID, default: 0], historyEventSequence: historyEventSequence)
    }

    private func finishCachedHistoryPresentation(sessionID: String, operationID: UUID?) {
        guard let operationID else { return }
        // Cached mutations enqueue their exact MVI snapshot before the UI terminal.
        drainKMPEventDeliveries()
        guard !kmpHistoryStore.isLoading(sessionID: sessionID) else { return }
        if completeHistoryReadingPage(sessionID: sessionID) { return }
        historyPresentationAdmission.terminal(sessionID: sessionID, domainLoading: false,
            operationID: operationID)
    }

    /// 一个阅读批次只发送一次终态给 viewport，传输页终态只用于继续补页。
    private func completeHistoryReadingPage(sessionID: String, failed: Bool = false,
        historyEventSequence: Int64? = nil) -> Bool {
        guard var batch = historyReadingBatches[sessionID] else { return false }
        guard historyEventSequence.map({ $0 >= batch.minimumEventSequence }) ?? true else { return true }
        if !batch.responseReady { batch.rounds += 1 }
        batch.responseReady = true
        batch.failed = batch.failed || failed
        historyReadingBatches[sessionID] = batch
        scheduleConversationProjection(for: sessionID)
        return true
    }

    var selectedHistoryHasMore: Bool {
        if isShowingSearchContext { return searchContextHasMore }
        guard let id = selectedSessionId else { return false }
        return historyHasMore[id] == true && (gateway.state.isConnected || !offlineHistoryBoundarySessionIds.contains(id))
    }

    private func publishTrajectory(sessionID: String) {
        guard !evictedConversationSessionIDs.contains(sessionID) else { return }
        let transient = kmpTrajectoryStore.decodeTransientNodes(assistantStreamState.transientTrajectoryJson(sessionId: sessionID))
        trajectoryTimeline(for: sessionID).publish(kmpTrajectoryStore.nodes(for: sessionID) + transient)
    }

    private func trajectoryHistoryVersion(for sessionID: String) -> TrajectoryHistoryVersion {
        let records = events[sessionID, default: []]
        return TrajectoryHistoryVersion(
            revision: trajectoryHistoryRevisions[sessionID, default: 0],
            count: records.count,
            lastSequence: records.last?.seq
        )
    }

    func trajectoryTimeline(for sessionId: String?) -> TrajectoryTimeline {
        let timelineID = sessionId ?? "__no-session__"
        if let timeline = trajectoryTimelines[timelineID] { return timeline }
        let nodes = sessionId.map(kmpTrajectoryStore.nodes(for:)) ?? []
        let timeline = TrajectoryTimeline(initialNodes: nodes)
        trajectoryTimelines[timelineID] = timeline
        return timeline
    }

    private func enqueueKMPEventDelivery(_ delivery: @escaping @MainActor () -> Void) {
        pendingKMPEventDeliveries.append(delivery)
        guard !isKMPEventDeliveryScheduled else { return }
        isKMPEventDeliveryScheduled = true
        Task { @MainActor [weak self] in
            // 明确越过当前 SwiftUI transaction，而不只是在同一个 MainActor
            // executor turn 的尾部重入发布。
            await Task.yield()
            self?.drainKMPEventDeliveries()
        }
    }

    private func drainKMPEventDeliveries() {
        // 不在 action 之间 suspend；同一批及 drain 期间继续产生的 KMP Event
        // 都按订阅回调顺序提交，避免 state patch 与 effect 被其他 Intent 穿插。
        while !pendingKMPEventDeliveries.isEmpty {
            let deliveries = pendingKMPEventDeliveries
            pendingKMPEventDeliveries.removeAll(keepingCapacity: true)
            deliveries.forEach { $0() }
        }
        isKMPEventDeliveryScheduled = false
    }

#if DEBUG
    var conversationHistoryPreparationForTesting: (@Sendable (KMPConversationHistoryPreparation) async throws -> KMPPreparedConversationHistory)?
    private(set) var conversationHistoryReusedPreparationCountForTesting = 0
    var conversationHistoryWorkerCountForTesting: Int { conversationHistoryWorkerIDs.count }
    /// XCTest 只等待生产使用的同一延迟交付队列，不提供同步发布旁路。
    var chatPrefetchRequestCountForTesting: Int { chatPrefetcher.requestCount }

    func awaitPendingKMPEventDeliveriesForTesting(includingHistoryPreparation: Bool = true) async {
        while isKMPEventDeliveryScheduled || !pendingKMPEventDeliveries.isEmpty ||
            includingHistoryPreparation && !conversationHistoryWorkerIDs.isEmpty {
            await Task.yield()
        }
    }

    /// 投影本身由 display link 按帧节流，测试需要等它真正落盘才能断言渲染文本。
    /// 这里同样不提供同步旁路：只让出执行权，直到该 session 的投影驱动跑完一次。
    func awaitConversationProjectionForTesting(sessionID: String, expectedText: String) async {
        for _ in 0..<200 {
            if renderedConversationItems[sessionID]?.last?.text == expectedText ||
                sessionID == selectedSessionId && selectedConversationItems.last?.text == expectedText { return }
            try? await Task.sleep(for: .milliseconds(5))
        }
    }
#endif
    func setTrajectoryProjectionActive(sessionID: String?, isActive: Bool) {
        guard let sessionID else { return }
        if isActive {
            guard activeTrajectorySessionIDs.insert(sessionID).inserted else { return }
            pendingTrajectoryEvents[sessionID] = nil
            let version = trajectoryHistoryVersion(for: sessionID)
            if trajectoryProjectedVersions[sessionID] == version {
                publishTrajectory(sessionID: sessionID)
            } else {
                do {
                    try kmpTrajectoryStore.replace(
                        sessionID: sessionID,
                        events: events[sessionID, default: []]
                    )
                    trajectoryProjectedVersions[sessionID] = version
                } catch {
                    lastError = error.localizedDescription
                }
            }
        } else {
            activeTrajectorySessionIDs.remove(sessionID)
            pendingTrajectoryEvents[sessionID] = nil
            trajectoryProjectionDrivers[sessionID]?.stop()
        }
    }
    var activeWorkspace: GatewayWorkspace? {
        guard !isUngroupedWorkspaceSelected, !isAllWorkspacesSelected else { return nil }
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
    func selectAllWorkspaces() {
        selectedWorkspaceId = Self.allWorkspacesID
    }
    var isAllWorkspacesSelected: Bool {
        selectedWorkspaceId == Self.allWorkspacesID
    }
    var isUngroupedWorkspaceSelected: Bool {
        selectedWorkspaceId == Self.ungroupedWorkspaceID
    }
    var ungroupedSessions: [SessionSummary] {
        let groupedSessionIds = Set(workspaces.flatMap(\.sessionIds))
        return historySessions.filter { !groupedSessionIds.contains($0.id) }
    }
    func isSubagentSession(_ sessionID: String) -> Bool {
        teamStore.isChild(sessionId: sessionID) || sessions.first { $0.id == sessionID }?.isSubagent == true
    }
    var historySessions: [SessionSummary] {
        sessions.filter { $0.isVisibleInHistory && !teamStore.isChild(sessionId: $0.id) }
    }

    /// 废弃整个业务容器，旧异步工作最多只能触达已断开的旧 GatewayClient。
    func deactivateGateway() {
        offlineCatalogActive = false
        offlineCatalogRestoreTask?.cancel()
        offlineCatalogWriteTask?.cancel()
        chatPrefetchRecoverySessionID = nil
        chatPrefetcher.stop(reason: "deactivate")
        chatCacheSelectionGeneration &+= 1
        conversationMemoryTrimTask?.cancel()
        conversationMemoryTrimTask = nil
        chatCacheWrites.values.forEach { $0.cancel() }
        chatCacheWrites.removeAll()
        if let chatHistoryCache, let chatCacheProtectionOwner { chatHistoryCache.releaseProtection(chatCacheProtectionOwner) }
        pairingHandler = nil
        resetOutstandingRequests()
        backgroundExecutionController.cancel()
        gateway.onFrame = nil
        gateway.onCommandFrame = nil
        gateway.onConnectionFailure = nil
        gateway.onConnectionInvalidated = nil
        gateway.onConnectionCheckStarted = nil
        gateway.onConnectionReady = nil
        fileUploads.clear()
        gateway.disconnect()
        pendingKMPEventDeliveries.removeAll()
        attachmentLoader.reset()
        localSearchTask?.cancel()
        remoteSearchTimeout?.cancel()
        cancelWorkspaceFileDownload()
        clearSearchContext()
        completedWorkspaceFile = nil
        imageAttachmentCache.removeAllFromMemory()
    }

    func connect() {
        preferences.automaticallyConnect = true
        resetOutstandingRequests()
        presentsNextConnectionFailureAsAlert = false
        lastError = nil
        gateway.connect(to: endpoint)
    }

    func disconnect() {
        chatPrefetchRecoverySessionID = nil
        chatPrefetcher.stop(reason: "disconnect")
        preferences.automaticallyConnect = false
        resetOutstandingRequests()
        resetRemoteSearchForOffline()
        showOfflineHistoryBoundary()
        gateway.disconnect()
    }

    func allowAutomaticConnection() {
        preferences.automaticallyConnect = true
    }

    /// Restores a previously paired gateway without turning the initial,
    /// intentionally unpaired state into a transport error.
    func connectOnColdLaunchIfPaired() async {
        guard offlineCatalogActive, !hasHandledColdLaunchConnection,
              !isCheckingColdLaunchCredential else { return }
        guard case .disconnected = gateway.state else { return }
        guard preferences.automaticallyConnect else { return }
        // 离线目录可能仍在异步恢复；缺少凭据只维持未连接状态，配对入口始终可见。
        isCheckingColdLaunchCredential = true
        defer { isCheckingColdLaunchCredential = false }
        let requestedEndpoint = endpoint
        guard await gateway.hasStoredCredential(for: requestedEndpoint), !Task.isCancelled,
              offlineCatalogActive, preferences.automaticallyConnect,
              endpoint == requestedEndpoint else { return }
        guard case .disconnected = gateway.state else { return }
        hasHandledColdLaunchConnection = true
        connect()
    }

    func handleScenePhase(_ phase: ScenePhase) {
        switch phase {
        case .active:
            backgroundExecutionController.applicationDidBecomeActive()
            gateway.applicationDidBecomeActive()
            // 首次读取 Keychain 失败时没有建立连接意图；解锁回前台后允许再次恢复。
            if case .disconnected = gateway.state {
                Task { [weak self] in await self?.connectOnColdLaunchIfPaired() }
            }
            refreshTeam()
            // 健康连接恢复前台不会重新 hello；补查后台期间漏掉的会话终态。
            if gateway.state.isConnected { gateway.requestSessions() }
        case .background:
            notifyPendingAgentRequests()
            pauseSessionControlRequests()
            chatPrefetcher.stop(reason: "background")
            backgroundExecutionController.applicationDidEnterBackground()
            imageAttachmentCache.removeExpiredFiles()
            let historyCache = chatHistoryCache
            if historyCache === ChatHistoryCache.shared {
                Task { _ = await AppCacheMaintenance.shared.run(cache: historyCache) }
            }
            gateway.applicationDidEnterBackground()
            if !gateway.state.isConnected {
                applySessionAgentPresetTransition(sessionAgentPresetStore.disconnected())
            }
        case .inactive:
            break
        @unknown default:
            break
        }
    }

    func pair(usingQRCode rawValue: String, presentsFailureAlert: Bool = true) throws {
        if let pairingHandler { try pairingHandler(rawValue); return }
        let payload = try PairingPayloadParser.parse(rawValue)
        resetOutstandingRequests()
        presentsNextConnectionFailureAsAlert = presentsFailureAlert
        lastError = nil
        endpoint = payload.publicUrl
        allowAutomaticConnection()
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
        dispatchSessionControl(.requestAgentPresets(isConnected: true))
        dispatchSessionControl(.requestDefaults(isConnected: true))
        dispatchSessionControl(.requestDefaultModel(isConnected: true))
    }
    func retryAgentPresets() {
        guard gateway.state.isConnected else {
            agentPresetsLoadError = String(localized: "请连接网关后重试。")
            return
        }
        dispatchSessionControl(.requestAgentPresets(isConnected: true))
    }
    func setDefaultAgentPreset(_ id: String) {
        setGlobalDefault(target: "agent-preset", value: id)
    }
    func setDefaultPermission(_ value: String) {
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
        dispatchSessionControl(.requestModels(sessionID: nil, isConnected: true))
    }
    func saveDefaultModel(provider: String, model: String, reasoningEffort: String?) {
        guard gateway.state.isConnected else {
            lastError = String(localized: "请先连接 DeepSeek Harness")
            return
        }
        dispatchSessionControl(.saveDefaultModel(
            selection: GatewayModelSelection(
                provider: provider,
                model: model,
                reasoningEffort: reasoningEffort
            ),
            isConnected: true
        ))
    }
    func ensureFileUploadSession() async -> Bool {
        guard !isViewingTeamMember else { return false }
        if selectedSessionId != nil { return true }
        guard supportsSessionCreation else {
            lastError = String(localized: "当前网关不支持创建附件会话，请升级网关")
            return false
        }
        return await prepareNewConversation() && selectedSessionId != nil
    }

    func prepareNewConversation() async -> Bool {
        guard !isAllWorkspacesSelected else {
            lastError = WorkspaceSessionCopy.creationPrompt
            return false
        }
        teamLeadSessionID = nil
        teamSnapshot = MobileTeamSnapshot()
        guard !isPreparingNewConversation else { return false }
        if let id = selectedSessionId { historyPresentationAdmission.cancel(sessionID: id, outcome: .detached) }
        clearSearchContext()
        leaveSessionAgentPreset()
        isPreparingNewConversation = true
        updateChatPrefetchState()
        defer { isPreparingNewConversation = false; updateChatPrefetchState() }
        var sessionID: String?
        if supportsSessionCreation {
            do {
                sessionID = try await gateway.createSession(workspaceId: activeWorkspace?.id)
            } catch {
                if !Task.isCancelled { lastError = error.localizedDescription }
                return false
            }
        }
        guard !Task.isCancelled else { return false }
        cancelSnapshotWait()
        if let sessionID { addKnownSession(sessionID) }
        guard dispatchSessionListIntent(.select(sessionID)) else { return false }
        backgroundMonitoredSessionID = nil
        waitingForNewSession = false
        preparedConversationActivationKey = sessionID ?? Self.newConversationActivationKey
        activeConversationActivationKey = nil
        await commitPendingKMPEventsAfterViewUpdate()
        return selectedSessionId == sessionID
    }

    func prepareConversation(for session: SessionSummary, teamLeadID: String? = nil) async -> Bool {
        teamLeadSessionID = teamLeadID ?? session.id
        publishTeamSnapshot()
        if teamLeadID == nil { ensureTeamLoaded() }
        if let id = selectedSessionId, id != session.id { historyPresentationAdmission.cancel(sessionID: id, outcome: .detached) }
        chatPrefetcher.prioritizeSelection(sessionID: session.id, assistantStream: usesAssistantStream)
        leaveSessionAgentPreset()
        clearSearchContext()
        evictedConversationSessionIDs.remove(session.id)
        cancelSnapshotWait()
        let preservesStream = localSearchSequences[session.id] != nil && assistantStreamState.hasBaseline(sessionId: session.id)
        if !preservesStream {
            if gateway.state.isConnected, let previous = selectedSessionId {
                try? flushPendingConversationHistory(sessionID: previous)
                try? kmpConversationStore.clearAssistantChunks(sessionID: previous)
            }
            assistantStreamState.selectSession(sessionId: session.id)
        }
        guard dispatchSessionListIntent(.select(session.id)) else { return false }
        if let cache = chatHistoryCache, let owner = chatCacheProtectionOwner {
            Task { await cache.recordSessionOpen(owner: owner, sessionID: session.id) }
        }
        updateChatPrefetchState()
        backgroundMonitoredSessionID = nil
        waitingForNewSession = false
        preparedConversationActivationKey = session.id
        activeConversationActivationKey = nil
        if gateway.state.isConnected, usesAssistantStream, !preservesStream { beginSnapshotWait(for: session.id) }
        await commitPendingKMPEventsAfterViewUpdate()
        chatCacheSelectionGeneration &+= 1
        let generation = chatCacheSelectionGeneration
        let searchSequence = localSearchSequences[session.id]
        conversationSearchTarget = searchSequence.map { "message-\(session.id)-\($0)" }
        let searchAlreadyVisible = searchSequence.map { sequence in
            events[session.id, default: []].contains { $0.seq == sequence }
        } ?? false
        if let chatHistoryCache, let protectionOwner = chatCacheProtectionOwner, !searchAlreadyVisible {
            let gatewayID = gatewayLocalID
            let protected = Set(sessions.filter(\.isRunning).map(\.id)).union([session.id])
            Task { [weak self] in
                await chatHistoryCache.protect(owner: protectionOwner, sessionIDs: protected)
                let window = await chatHistoryCache.loadTyped(gatewayID: gatewayID, sessionID: session.id,
                    before: searchSequence.map { $0 == Int.max ? $0 : $0 + 1 })
                guard let self else { return }
                guard generation == self.chatCacheSelectionGeneration, self.selectedSessionId == session.id else {
                    ChatCacheDiagnostics.shared.restoreSkipped(reason: .selectionChanged)
                    return
                }
                guard let window else {
                    if searchSequence != nil { self.invalidateSearchContext(sessionID: session.id) }
                    else if !self.gateway.state.isConnected, self.events[session.id, default: []].isEmpty {
                        self.historyLoadErrors[session.id] = "此会话尚未缓存，连接后可加载聊天内容"
                        self.conversationPreparingSessionIDs.remove(session.id)
                    }
                    return
                }
                let format = window.historyFormatVersion?.intValue
                if !self.chatCacheHelloReceived || format == self.chatCacheHelloFormat {
                    if let searchSequence {
                        if !window.events.contains(where: { $0.seq == searchSequence }) ||
                            self.searchTargetWasInvalidated(sessionID: session.id, sequence: searchSequence) {
                            self.invalidateSearchContext(sessionID: session.id)
                        } else { self.presentSearchContext(window) }
                        return
                    }
                    self.historyLoadErrors[session.id] = nil
                    self.restoringChatCache = true
                    defer { self.restoringChatCache = false }
                    if (try? self.kmpHistoryStore.hydrateCachedWindow(sessionID: session.id, window: window)) == true {
                        self.assistantStreamState.rememberCachedFormat(sessionId: session.id,
                            historyFormatVersion: format.map { KotlinInt(int: Int32($0)) })
                        self.drainKMPEventDeliveries()
                    }
                } else {
                    ChatCacheDiagnostics.shared.restoreSkipped(reason: .formatChanged)
                    if self.gateway.state.isConnected { self.invalidateChatCache(sessionID: session.id) }
                    else if self.events[session.id, default: []].isEmpty {
                        self.historyLoadErrors[session.id] = "缓存格式与上次连接不一致，连接后可重新加载聊天内容"
                        self.conversationPreparingSessionIDs.remove(session.id)
                    }
                }
            }
        }
        conversationMemoryAccess[session.id] = DispatchTime.now().uptimeNanoseconds
        scheduleInactiveConversationMemoryTrim(replacing: true)
        return selectedSessionId == session.id
    }

    private func searchTargetWasInvalidated(sessionID: String, sequence: Int) -> Bool {
        guard let authoritative = events[sessionID], !historyLoadingSessionIds.contains(sessionID),
              !kmpHistoryStore.canPrependCachedPage(sessionID: sessionID),
              !authoritative.contains(where: { $0.seq == sequence }) else { return false }
        return !(historyHasMore[sessionID] == true && authoritative.first.map { sequence < $0.seq } == true)
    }

    private func invalidateSearchContext(sessionID: String) {
        clearSearchContext()
        conversationSearchTarget = nil
        localSearchSequences[sessionID] = nil
        localSearchResults.removeAll { $0.sessionId == sessionID }
        searchResults.removeAll { $0.sessionId == sessionID }
        notice("搜索结果已失效", "本地缓存或网关历史已不包含此内容，请重新搜索。", sessionId: sessionID)
    }

    private func clearSearchContext() {
        if isShowingSearchContext, let id = selectedSessionId { historyPresentationAdmission.cancel(sessionID: id) }
        conversationSearchTarget = nil
        isShowingSearchContext = false
        searchContextHasMore = false
        searchContextLoadingOlder = false
        searchContextLoadingOperationID = nil
        searchContextRecords = []
        searchContextTimeline = nil
        searchContextProjector = nil
    }

    /// 搜索上下文单独投影，网络权威窗口和实时 cursor 始终保留。
    private func presentSearchContext(_ window: CachedHistoryWindow, prepend: Bool = false) {
        let history = KMPHistoryStoreAdapter()
        guard (try? history.hydrateCachedWindow(sessionID: window.sessionId, window: window)) == true,
              let page = history.eventsBySessionID[window.sessionId] else { return }
        let records: [SessionEvent]
        if prepend {
            let known = Set(searchContextRecords.map(\.seq))
            records = (page.filter { !known.contains($0.seq) } + searchContextRecords).sorted { $0.seq < $1.seq }
        } else { records = page }
        let projector = KMPConversationStoreAdapter()
        guard (try? projector.replace(sessionID: window.sessionId, events: records)) != nil else { return }
        searchContextRecords = records
        searchContextProjector = projector
        let timeline = prepend ? searchContextTimeline ?? ConversationTimeline() : ConversationTimeline()
        if prepend {
            requireHistoryPresentation(sessionID: window.sessionId, historyEventSequence: Int64.max)
            historyPresentationCommittedEpochs[window.sessionId] = historyPresentationRequiredEpochs[window.sessionId, default: 0]
        }
        timeline.publish(projector.items(for: window.sessionId), historyPresentationStamp: historyPresentationAdmission.stamp(
            sessionID: window.sessionId, committedEpoch: historyPresentationCommittedEpochs[window.sessionId, default: 0]))
        searchContextTimeline = timeline
        searchContextHasMore = window.hasMore
        isShowingSearchContext = true
        enqueueImageAttachments(in: page, sessionId: window.sessionId)
    }

    func returnToLatestConversation() async {
        guard let id = selectedSessionId else { return }
        conversationSearchTarget = nil
        clearSearchContext()
        if events[id] == nil, let chatHistoryCache {
            let generation = chatCacheSelectionGeneration
            if let window = await chatHistoryCache.loadTyped(gatewayID: gatewayLocalID, sessionID: id),
               generation == chatCacheSelectionGeneration, selectedSessionId == id, events[id] == nil {
                restoringChatCache = true
                defer { restoringChatCache = false }
                _ = try? kmpHistoryStore.hydrateCachedWindow(sessionID: id, window: window)
                drainKMPEventDeliveries()
            }
        }
        if gateway.state.isConnected, !assistantStreamState.hasBaseline(sessionId: id) { loadHistory(for: id) }
    }

    func refreshScheduledTasks() {
        guard gateway.state.isConnected else {
            scheduledTasksLoading = false
            scheduledTasksError = "连接网关后可查看定时任务"
            return
        }
        scheduledTasksLoading = true
        scheduledTasksError = nil
        let requestID = UUID()
        scheduledTasksRequestID = requestID
        gateway.requestScheduleCatalog()
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(12))
            guard let self, self.scheduledTasksRequestID == requestID,
                  self.scheduledTasksLoading else { return }
            self.scheduledTasksLoading = false
            self.scheduledTasksError = "定时任务请求超时，请重试"
        }
    }

    func refreshPlugins() {
        pluginPageVisited = true
        guard gateway.state.isConnected else {
            pluginsLoading = false
            pluginsError = "连接网关后可查看插件"
            return
        }
        let requestID = UUID().uuidString
        pluginCatalogRequestID = requestID
        pluginsLoading = true
        pluginsError = nil
        gateway.sendRequestPayload("{\"type\":\"plugin-catalog\",\"requestId\":\"\(requestID)\"}")
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(12))
            guard let self, self.pluginCatalogRequestID == requestID, self.pluginsLoading else { return }
            self.pluginsLoading = false
            self.pluginsError = "插件目录请求超时，请重试"
        }
    }

    func setPluginEnabled(name: String, enabled: Bool, entryID: String? = nil) {
        guard gateway.state.isConnected, pluginCatalog?.managementAvailable == true,
              pluginToggleRequestID == nil else { return }
        let requestID = UUID().uuidString
        pluginToggleRequestID = requestID
        pluginPendingName = name
        pluginNotice = nil
        let payload: [String: Any] = entryID.map {
            ["type": "plugin-entry-set-enabled", "requestId": requestID, "entryId": $0, "enabled": enabled]
        } ?? ["type": "plugin-bundle-set-enabled", "requestId": requestID, "name": name, "enabled": enabled]
        guard let data = try? JSONSerialization.data(withJSONObject: payload) else { return }
        gateway.sendRequestPayload(String(decoding: data, as: UTF8.self))
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(15))
            guard let self, self.pluginToggleRequestID == requestID else { return }
            self.pluginToggleRequestID = nil
            self.pluginPendingName = nil
            self.pluginNotice = "操作结果待确认，请刷新插件目录"
            self.refreshPlugins()
        }
    }

    func clearPluginNotice() { pluginNotice = nil }

    private func acceptPluginFrame(_ frame: GatewayFrame) {
        switch frame.kind {
        case "plugin-catalog" where frame.requestId == pluginCatalogRequestID:
            pluginCatalog = MobilePluginCatalog(
                managementAvailable: frame.managementAvailable == true,
                settingsWritable: frame.settingsWritable == true,
                bundles: (frame.bundles ?? []).map(MobilePluginBundle.init),
                plugins: (frame.plugins ?? []).map(MobilePluginEntry.init),
                officialItems: (frame.officialItems ?? []).map(MobileOfficialPlugin.init)
            )
            pluginsLoading = false
            pluginsError = frame.managementAvailable == true ? nil : "当前 Host 不支持插件管理"
        case "plugins-changed":
            // The mutation response refreshes the catalog. Avoid a second fetch
            // for the same Host change while that response is still pending.
            if pluginPageVisited && pluginToggleRequestID == nil { refreshPlugins() }
        case "plugin-bundle-set-enabled", "plugin-entry-set-enabled":
            guard frame.requestId == pluginToggleRequestID else { return }
            let application = frame.result?["application"]?.stringValue
            switch application {
            case "applied": pluginNotice = nil
            case "restart-required": pluginNotice = "设置已保存，请重启 DSH Web 后刷新目录"
            case "overridden": pluginNotice = "插件状态被其他配置覆盖，请检查 Host 配置"
            default: pluginNotice = frame.result?["error"]?.stringValue ?? "插件操作未生效"
            }
            pluginToggleRequestID = nil
            pluginPendingName = nil
            refreshPlugins()
        case "error" where frame.requestType == "plugin-catalog" && frame.requestId == pluginCatalogRequestID:
            pluginsLoading = false
            pluginsError = frame.message ?? "插件目录加载失败"
        case "error" where ["plugin-bundle-set-enabled", "plugin-entry-set-enabled"].contains(frame.requestType ?? "") && frame.requestId == pluginToggleRequestID:
            pluginToggleRequestID = nil
            pluginPendingName = nil
            pluginNotice = frame.message ?? "插件操作失败"
            refreshPlugins()
        default: break
        }
    }

    @discardableResult
    func updateScheduledTask(_ task: ScheduledTask, title: String, prompt: String, change: JSONValue?) -> String? {
        guard beginScheduledTaskMutation(task, kind: "schedule-update") else { return nil }
        let requestID = scheduledTaskMutationRequestID!
        gateway.updateSchedule(
            sessionId: task.sessionID, id: task.id, expected: task.raw,
            title: title.trimmingCharacters(in: .whitespacesAndNewlines),
            prompt: prompt.trimmingCharacters(in: .whitespacesAndNewlines),
            change: change, requestId: requestID
        )
        return requestID
    }

    @discardableResult
    func deleteScheduledTask(_ task: ScheduledTask) -> String? {
        guard beginScheduledTaskMutation(task, kind: "schedule-delete") else { return nil }
        let requestID = scheduledTaskMutationRequestID!
        gateway.deleteSchedule(sessionId: task.sessionID, id: task.id, requestId: requestID)
        return requestID
    }

    private func beginScheduledTaskMutation(_ task: ScheduledTask, kind: String) -> Bool {
        guard gateway.state.isConnected else {
            scheduledTaskMutationError = "连接网关后才能修改定时任务"
            return false
        }
        guard scheduledTaskPendingID == nil else { return false }
        guard scheduledTasks.first(where: { $0.id == task.id && $0.sessionID == task.sessionID })?.raw == task.raw else {
            scheduledTaskMutationError = "任务已变化，列表已刷新，请重新操作"
            refreshScheduledTasks()
            return false
        }
        let requestID = UUID().uuidString
        scheduledTaskMutationRequestID = requestID
        scheduledTaskMutationKind = kind
        scheduledTaskPendingID = task.id
        scheduledTaskMutationError = nil
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(15))
            guard let self, self.scheduledTaskMutationRequestID == requestID else { return }
            self.finishScheduledTaskMutation(error: "操作超时，请刷新任务后重试")
        }
        return true
    }

    private func finishScheduledTaskMutation(error: String?) {
        if error == nil { scheduledTaskCompletedRequestID = scheduledTaskMutationRequestID }
        scheduledTaskMutationError = error
        scheduledTaskPendingID = nil
        scheduledTaskMutationRequestID = nil
        scheduledTaskMutationKind = nil
        refreshScheduledTasks()
    }

    /// UI Intent 可能来自 SwiftUI 正在更新的调用栈，因此 KMP Event 仍延迟
    /// 到下一次 MainActor turn 发布；导航必须等待该发布完成，避免目标页面首帧
    /// 读取上一个 Session 的 UI 镜像。
    private func commitPendingKMPEventsAfterViewUpdate() async {
        await Task.yield()
        drainKMPEventDeliveries()
    }

    /// Activates the already-pushed conversation. The yield points separate
    /// independent request groups so they cannot monopolize a transition frame.
    func activatePreparedConversation(sessionID: String?) async {
        let activationKey = sessionID ?? Self.newConversationActivationKey
        guard preparedConversationActivationKey == activationKey,
              activeConversationActivationKey != activationKey,
              // UI mirror deliberately publishes on the next MainActor turn;
              // navigation activation must compare against the authoritative
              // KMP state so it cannot lose the destination `.task` race.
              sessionID == kmpSessionListStore.snapshot.selectedSessionId,
              gateway.state.isConnected else { return }
        guard !Task.isCancelled else { return }

        // Do not claim activation from a task that was already cancelled by a
        // NavigationStack replacement. Otherwise a later valid activation sees
        // the key and skips the subscribe request even though none was sent.
        activeConversationActivationKey = activationKey

        ensureTeamLoaded()
        if let sessionID {
            markRead(sessionID)
            subscribeToSession(sessionID)
            gateway.requestSessions()
        } else {
            subscribeToSession(nil)
        }

        await Task.yield()
        guard !Task.isCancelled,
              preparedConversationActivationKey == activationKey else { return }

        if agentPresets.isEmpty {
            dispatchSessionControl(.requestAgentPresets(isConnected: true))
        }

        if !usesAssistantStream, let sessionID,
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
        guard let syncedTimestamp = kmpHistoryStore.syncedActivityTimestamp(for: session.id) else { return true }
        let syncedActivity = Date(timeIntervalSince1970: syncedTimestamp)
        // A completed history baseline may subsequently be extended by the
        // subscribed live tail. Both sources are already present locally, so
        // compare the remote summary against the newest covered activity
        // instead of the older history-request timestamp alone.
        let latestLocalActivity = events[session.id]?.last?.date ?? syncedActivity
        let coveredActivity = max(syncedActivity, latestLocalActivity)
        return session.lastActivity > coveredActivity
    }
    func loadHistory(for sessionId: String, older: Bool = false, presentationOperationID: UUID? = nil) {
        if older, presentationOperationID == nil, let consumerID = historyPresentationAdmission.activeConsumerID(sessionID: sessionId) {
            _ = requestOlderHistoryPresentation(sessionID: sessionId, consumerID: consumerID)
            return
        }
        if older, isShowingSearchContext, sessionId == selectedSessionId,
           let chatHistoryCache, let before = searchContextRecords.first?.seq {
            guard !searchContextLoadingOlder else {
                if let presentationOperationID { historyPresentationAdmission.cancel(sessionID: sessionId, operationID: presentationOperationID) }
                return
            }
            if let presentationOperationID { historyPresentationAdmission.accept(sessionID: sessionId, operationID: presentationOperationID) }
            searchContextLoadingOlder = true
            let cacheOperationID = UUID()
            searchContextLoadingOperationID = cacheOperationID
            let selection = chatCacheSelectionGeneration
            Task { [weak self] in
                guard let self else { return }
                defer {
                    if searchContextLoadingOperationID == cacheOperationID {
                        searchContextLoadingOperationID = nil
                        searchContextLoadingOlder = false
                    }
                    finishCachedHistoryPresentation(sessionID: sessionId, operationID: presentationOperationID)
                }
                let epoch = await chatHistoryCache.epoch()
                let page = await chatHistoryCache.loadTyped(gatewayID: gatewayLocalID, sessionID: sessionId, before: before,
                    limit: historyReadingBatches[sessionId] == nil ? 80 : 160)
                let currentEpoch = await chatHistoryCache.epoch()
                guard presentationOperationID.map({ historyPresentationAdmission.matches(sessionID: sessionId, operationID: $0) }) ?? true else { return }
                guard selection == chatCacheSelectionGeneration, isShowingSearchContext,
                      selectedSessionId == sessionId, before == searchContextRecords.first?.seq else { return }
                if epoch == currentEpoch, let page, !page.events.isEmpty { presentSearchContext(page, prepend: true) }
                else { searchContextHasMore = false }
            }
            return
        }
        if older, !gateway.state.isConnected, !kmpHistoryStore.canPrependCachedPage(sessionID: sessionId), let chatHistoryCache,
           let before = events[sessionId]?.first?.seq {
            guard offlineHistoryLoadingOlderSessionIds[sessionId] == nil else {
                if let presentationOperationID { historyPresentationAdmission.cancel(sessionID: sessionId, operationID: presentationOperationID) }
                return
            }
            if let presentationOperationID { historyPresentationAdmission.accept(sessionID: sessionId, operationID: presentationOperationID) }
            let operation = UUID()
            offlineHistoryLoadingOlderSessionIds[sessionId] = operation
            let selection = chatCacheSelectionGeneration
            Task { [weak self] in
                guard let self else { return }
                defer {
                    finishCachedHistoryPresentation(sessionID: sessionId, operationID: presentationOperationID)
                    if offlineHistoryLoadingOlderSessionIds[sessionId] == operation {
                        offlineHistoryLoadingOlderSessionIds[sessionId] = nil
                    }
                }
                let page = await chatHistoryCache.loadTyped(gatewayID: gatewayLocalID, sessionID: sessionId, before: before,
                    limit: historyReadingBatches[sessionId] == nil ? 80 : 160)
                guard presentationOperationID.map({ historyPresentationAdmission.matches(sessionID: sessionId, operationID: $0) }) ?? true,
                      selection == chatCacheSelectionGeneration, selectedSessionId == sessionId else { return }
                guard !gateway.state.isConnected else { return }
                guard let page, !page.events.isEmpty,
                      !chatCacheHelloReceived || page.historyFormatVersion?.intValue == chatCacheHelloFormat else {
                    offlineHistoryBoundarySessionIds.insert(sessionId)
                    return
                }
                searchContextRecords = events[sessionId, default: []]
                presentSearchContext(page, prepend: true)
            }
            return
        }
        if older, let chatHistoryCache, kmpHistoryStore.canPrependCachedPage(sessionID: sessionId),
           let before = events[sessionId]?.first?.seq {
            if let presentationOperationID { historyPresentationAdmission.accept(sessionID: sessionId, operationID: presentationOperationID) }
            let selection = chatCacheSelectionGeneration
            Task { [weak self] in
                guard let self else { return }
                defer { finishCachedHistoryPresentation(sessionID: sessionId, operationID: presentationOperationID) }
                let epoch = await chatHistoryCache.epoch()
                let page = await chatHistoryCache.loadTyped(gatewayID: gatewayLocalID, sessionID: sessionId, before: before,
                    limit: historyReadingBatches[sessionId] == nil ? 80 : 160)
                let currentEpoch = await chatHistoryCache.epoch()
                guard presentationOperationID.map({ historyPresentationAdmission.matches(sessionID: sessionId, operationID: $0) }) ?? true else { return }
                if let window = page,
                   !window.events.isEmpty, selection == chatCacheSelectionGeneration,
                   epoch == currentEpoch,
                   kmpHistoryStore.canPrependCachedPage(sessionID: sessionId) {
                    try? kmpHistoryStore.prependCachedPage(sessionID: sessionId, window: window)
                } else if selection == chatCacheSelectionGeneration,
                          epoch == currentEpoch, gateway.state.isConnected {
                    startHistorySync(for: sessionId, older: true, presentationOperationID: presentationOperationID)
                } else if selection == chatCacheSelectionGeneration, epoch == currentEpoch {
                    offlineHistoryBoundarySessionIds.insert(sessionId)
                }
            }
            return
        }
        guard gateway.state.isConnected else {
            historyPresentationAdmission.cancel(sessionID: sessionId, outcome: .failed, operationID: presentationOperationID)
            historyLoadErrors[sessionId] = events[sessionId, default: []].isEmpty
                ? "此会话尚未缓存，连接后可加载聊天内容" : "已到达本地缓存边界，连接后可加载更早内容"
            return
        }
        if !older, sessionId == selectedSessionId, gateway.subscriptionRecoveryExhausted {
            // 用户点击重试后开启新的恢复周期，并隔离上一连接的迟到确认。
            connect()
            return
        }
        if usesAssistantStream && !older && sessionId == selectedSessionId {
            subscribeToSession(sessionId)
        } else {
            startHistorySync(for: sessionId, older: older, presentationOperationID: presentationOperationID)
            if let presentationOperationID, !kmpHistoryStore.isLoading(sessionID: sessionId) {
                finishCachedHistoryPresentation(sessionID: sessionId, operationID: presentationOperationID)
            }
        }
    }

    private func startHistorySync(for sessionId: String, older: Bool = false, presentationOperationID: UUID? = nil) {
        historyLoadErrors[sessionId] = nil
        do {
            try kmpHistoryStore.start(
                sessionID: sessionId,
                older: older,
                hasLocalEvents: !events[sessionId, default: []].isEmpty,
                earliestLocalSequence: events[sessionId]?.first?.seq,
                onRequestAccepted: { [weak self] in
                    if let presentationOperationID { self?.historyPresentationAdmission.accept(sessionID: sessionId, operationID: presentationOperationID) }
                }
            )
        } catch {
            if let presentationOperationID { historyPresentationAdmission.cancel(sessionID: sessionId, outcome: .failed, operationID: presentationOperationID) }
            lastError = error.localizedDescription
        }
    }

    private func updateChatPrefetchState(sessionsReceived: Bool = false) {
        guard chatHistoryCache != nil else { return }
        let candidates = historySessions.filter { !archivedSessionIds.contains($0.id) }.map { session in
            ChatSessionPrefetcher.Candidate(id: session.id, lastActivityEpochSeconds: session.lastActivity.timeIntervalSince1970,
                isRunning: session.isRunning, hasUnread: session.hasUnread, hasConversation: session.isVisibleInHistory)
        }
        let selectedHistoryLoading = selectedSessionId.map { historyLoadingSessionIds.contains($0) } ?? false
        let busy = messageSubmissionPending || pendingSnapshotSessionID != nil || selectedHistoryLoading
            || selectedSession?.isRunning == true || isPreparingNewConversation
            || selectedSessionId.map { historyPresentationAdmission.isPending(sessionID: $0) } == true
        chatPrefetcher.update(candidates: candidates, selected: selectedSessionId, busy: busy, sessionsReceived: sessionsReceived)
    }

    private func requestHistoryPage(for sessionId: String, beforeSeq: Int?) {
        beginHistoryRequestTimeout(for: sessionId)
        guard chatPrefetcher.shouldSendForeground(sessionID: sessionId, isOlder: beforeSeq != nil) else { return }
        gateway.requestHistory(
            sessionId: sessionId,
            beforeSeq: beforeSeq,
            maxMessages: beforeSeq != nil && historyReadingBatches[sessionId] != nil
                ? 120 : historySyncEngine.configuration.pageMessageLimit,
            maxBytes: historySyncEngine.configuration.pageByteBudget,
            view: "conversation",
            historyFormatVersion: assistantStreamState.formatVersion(sessionId: sessionId)?.intValue
        )
    }

    private func beginHistoryRequestTimeout(for sessionId: String) {
        historySyncEngine.beginRequest(sessionID: sessionId, timeout: historyRequestTimeout) { [weak self] in
            guard let self else { return }
            self.chatPrefetcher.foregroundTimedOut(sessionID: sessionId)
            if self.pendingSnapshotSessionID == sessionId { self.pendingSnapshotSessionID = nil }
            let hasUsableLocalContent = !self.events[sessionId, default: []].isEmpty
                || !self.renderedConversationItems[sessionId, default: []].isEmpty
            do { try self.kmpHistoryStore.timedOut(sessionID: sessionId) }
            catch { self.lastError = error.localizedDescription }
            self.scheduleConversationProjection(for: sessionId)
            if hasUsableLocalContent {
                self.notice(
                    String(localized: "历史记录刷新超时"),
                    String(localized: "已保留本地内容并继续接收实时事件"),
                    sessionId: sessionId
                )
            } else {
                let message = String(localized: "历史记录加载超时，请重试")
                self.historyLoadErrors[sessionId] = message
                self.lastError = message
            }
        }
    }
    func resumeWorkspace() {
        gateway.cancelSubscriptionRecovery()
        if let id = selectedSessionId { historyPresentationAdmission.cancel(sessionID: id, outcome: .detached) }
        preparedConversationActivationKey = nil
        activeConversationActivationKey = nil
        guard gateway.state.isConnected else { return }
        if let sessionID = selectedSessionId,
           backgroundExecutionController.isAgentWorkActive(sessionID: sessionID)
            || sessions.first(where: { $0.id == sessionID })?.isRunning == true
            || pendingApprovalRequests.contains(where: { $0.sessionId == sessionID }) {
            backgroundMonitoredSessionID = sessionID
            // 返回手势可能早于目标页的订阅激活；显式订阅保证主页也能接收事件。
            subscribeToSession(sessionID)
        } else {
            backgroundMonitoredSessionID = nil
            subscribeToSession(nil)
        }
        refreshRemoteState()
    }
    func addKnownSession(_ id: String) {
        let normalized = id.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalized.isEmpty else { return }
        dispatchSessionListIntent(.knownSessionAdded(normalized))
    }
    func search(_ query: String) {
        let normalized = query.trimmingCharacters(in: .whitespacesAndNewlines)
        localSearchTask?.cancel()
        localSearchQuery = normalized
        localSearchResults = []
        localSearchSequences = [:]
        searchResults = []
        searchCoverage = normalized.isEmpty ? "" : String(localized: "本地结果仅涵盖已缓存聊天")
        guard !normalized.isEmpty else { return }
        localSearchTask = Task { [weak self] in
            do { try await Task.sleep(for: .milliseconds(180)) } catch { return }
            guard let self else { return }
            let json = await chatHistoryCache?.search(gatewayID: gatewayLocalID, query: normalized)
            guard !Task.isCancelled, localSearchQuery == normalized else { return }
            if let json, let data = json.data(using: .utf8),
               let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               let hits = object["results"] as? [[String: Any]] {
                var seen = Set<String>()
                localSearchResults = hits.compactMap { hit in
                    guard let id = hit["sessionId"] as? String, seen.insert(id).inserted else { return nil }
                    self.localSearchSequences[id] = hit["seq"] as? Int
                    return GatewaySearchItem(sessionId: id, snippet: hit["snippet"] as? String ?? "")
                }
                searchResults = localSearchResults
            }
            requestRemoteSearchIfPossible()
        }
    }

    private func requestRemoteSearchIfPossible() {
        guard gateway.state.isConnected, !localSearchQuery.isEmpty,
              remoteSearchGate.begin(localSearchQuery) else { return }
        let query = localSearchQuery
        gateway.searchSessions(query)
        remoteSearchTimeout?.cancel()
        remoteSearchTimeout = Task { [weak self] in
            do { try await Task.sleep(for: .seconds(10)) } catch { return }
            guard let self, remoteSearchGate.inFlight == query else { return }
            // 未回显 query 的旧网关无法识别超时后的迟到响应，因此继续本地检索并等待旧请求消化或重连。
            remoteSearchGate.timedOut()
        }
    }

    func refreshCacheStorage(clear: Bool = false, category: CacheStorageCategory? = nil) async {
        guard !cacheStorageBusy else { return }
        cacheStorageBusy = true
        defer { cacheStorageBusy = false }
        cacheStorageNotice = nil
        let previousBytes = storageUsage?.totalBytes
        if clear || category == .chats {
            chatPrefetcher.stop(reason: "clear")
            chatCacheSelectionGeneration &+= 1
            chatCacheWrites.values.forEach { $0.cancel() }
            chatCacheWrites.removeAll()
            ConversationViewportController.clearReusableHeights()
        }
        if clear { _ = await AppCacheMaintenance.shared.run(cache: chatHistoryCache, clear: true) }
        let usage = await AppCacheMaintenance.shared.storageUsage(cache: chatHistoryCache, clearing: category)
        storageUsage = usage
        if clear || category != nil {
            let reclaimed = max(0, (previousBytes ?? usage.totalBytes) - usage.totalBytes)
            cacheStorageNotice = reclaimed > 0
                ? String(localized: "storage.cleaned", defaultValue: "已释放 \(ByteCountFormatter.string(fromByteCount: reclaimed, countStyle: .file))")
                : String(localized: "没有更多可回收内容，正在使用的文件会保留。")
        }
    }

    private func scheduleInactiveConversationMemoryTrim(replacing: Bool = false) {
        if replacing { conversationMemoryTrimTask?.cancel(); conversationMemoryTrimTask = nil }
        guard conversationMemoryTrimTask == nil else { return }
        let generation = chatCacheSelectionGeneration
        let id = UUID()
        conversationMemoryTrimID = id
        conversationMemoryTrimTask = Task { [weak self] in
            await Task.yield()
            guard !Task.isCancelled, let self, generation == chatCacheSelectionGeneration else { return }
            defer { if conversationMemoryTrimID == id { conversationMemoryTrimTask = nil } }
            await trimInactiveConversationMemory()
        }
    }

    func handleMemoryWarning() {
        chatPrefetcher.stop(reason: "memory")
        imageAttachmentCache.removeAllFromMemory()
        ConversationViewportController.clearReusableHeights()
        let generation = chatCacheSelectionGeneration
        Task { [weak self] in
            guard let self, generation == chatCacheSelectionGeneration else { return }
            await trimInactiveConversationMemory(force: true)
            await chatHistoryCache?.trimMemory()
        }
    }

    private func trimInactiveConversationMemory(force: Bool = false) async {
        guard let chatHistoryCache else { return }
        let selection = chatCacheSelectionGeneration
        let epoch = await chatHistoryCache.epoch()
        guard selection == chatCacheSelectionGeneration else { return }
        var keep = Set(sessions.filter(\.isRunning).map(\.id))
            .union([selectedSessionId].compactMap { $0 })
            .union(pendingApprovalRequests.map(\.sessionId))
            .union(pendingQuestionRequests.map(\.sessionId))
            .union(queueState.queues.filter { !$0.value.isEmpty }.keys)
            .union(historyLoadingSessionIds)
        let sorted = renderedConversationItems.keys.sorted { conversationMemoryAccess[$0, default: 0] > conversationMemoryAccess[$1, default: 0] }
        var bytes: Int64 = 0
        for id in sorted {
            let cost = kmpHistoryStore.estimatedRetainedBytes(sessionID: id)
                + kmpConversationStore.estimatedRetainedBytes(sessionID: id)
            if keep.contains(id) || !force && keep.count < 6 && bytes + cost <= 24 * 1024 * 1024 {
                keep.insert(id); bytes += cost
            }
        }
        for id in sorted where !keep.contains(id) {
            chatCacheWrites.removeValue(forKey: id)?.cancel()
            if let json = kmpHistoryStore.cachedWindowJSON(sessionID: id,
                historyFormatVersion: assistantStreamState.formatVersion(sessionId: id)?.intValue,
                persistentCursor: assistantStreamState.persistentCursorFor(sessionId: id)?.intValue) {
                _ = await chatHistoryCache.save(gatewayID: gatewayLocalID, sessionID: id, json: json,
                    generation: DispatchTime.now().uptimeNanoseconds, epoch: epoch)
                guard selection == chatCacheSelectionGeneration else { return }
            }
        }
        keep.formUnion([selectedSessionId].compactMap { $0 })
        keep.formUnion(sessions.filter(\.isRunning).map(\.id))
        keep.formUnion(pendingApprovalRequests.map(\.sessionId))
        keep.formUnion(pendingQuestionRequests.map(\.sessionId))
        keep.formUnion(queueState.queues.filter { !$0.value.isEmpty }.keys)
        keep.formUnion(historyLoadingSessionIds)
        evictedConversationSessionIDs.formUnion(Set(sorted).subtracting(keep))
        let removed = kmpHistoryStore.retainSessions(keep)
        evictedConversationSessionIDs.formIntersection(Set(removed).union(evictedConversationSessionIDs.subtracting(Set(sorted))))
        assistantStreamState.retainSessions(sessionIds: Array(keep))
        for id in removed {
            discardPendingConversationHistory(sessionID: id)
            try? kmpConversationStore.clear(sessionID: id)
            kmpConversationStore.releaseRetainedState(sessionID: id)
            try? kmpTrajectoryStore.clear(sessionID: id)
            kmpTrajectoryStore.releaseRetainedState(sessionID: id)
            trajectoryTimelines[id] = nil
            trajectoryProjectionDrivers.removeValue(forKey: id)?.stop()
            pendingTrajectoryEvents[id] = nil
            trajectoryProjectedVersions[id] = nil
            trajectoryHistoryRevisions[id] = nil
            activeTrajectorySessionIDs.remove(id)
            renderedConversationItems[id] = nil
            events[id] = nil
            conversationTimelines[id] = nil
            conversationProjectionDrivers.removeValue(forKey: id)?.stop()
            conversationMemoryAccess[id] = nil
            conversationContentSessionIds.remove(id)
        }
    }

    func browseDirectories(path: String? = nil) {
        guard gateway.state.isConnected else {
            lastError = String(localized: "请先连接 DeepSeek Harness")
            return
        }
        directoryIsLoading = true
        gateway.requestDirectories(path: path)
    }
    func browseWorkspaceFiles(path: String? = nil) {
        guard let sessionID = selectedSessionId else {
            lastError = String(localized: "请先打开一个已有会话")
            return
        }
        guard gateway.state.isConnected else {
            lastError = String(localized: "请先连接 DeepSeek Harness")
            return
        }
        guard supportsFileDownloads else {
            lastError = String(localized: "当前 Mobile Gateway 不支持文件下载，请升级并重启网关。")
            return
        }
        kmpWorkspaceFileStore.setWorkspaceSession(
            sessionId: sessionID,
            workspaceSessionId: teamStore.workspaceSessionId(sessionId: sessionID)
        )
        let transition = kmpWorkspaceFileStore.load(
            sessionId: sessionID,
            path: path,
            requestId: UUID().uuidString
        )
        applyWorkspaceFileTransition(transition)
    }

    func openWorkspaceFile(_ item: GatewayDirectoryItem, purpose: String) {
        guard item.kind == "file", let sessionID = selectedSessionId,
              workspaceFilePreviewRequest == nil, !conversationFileIsOpening else { return }
        kmpWorkspaceFileStore.setWorkspaceSession(
            sessionId: sessionID,
            workspaceSessionId: teamStore.workspaceSessionId(sessionId: sessionID)
        )
        conversationFilePreviewRequested = false
        completedWorkspaceFile = nil
        workspaceFileOperationCache = WorkspacePreviewCache(gatewayID: gatewayLocalID)
        if purpose == "preview" {
            let request = WorkspaceFilePreviewRequest(sessionID: sessionID, path: item.path)
            startWorkspaceFilePreview(request, conversation: false, needsConnection: true) { [weak self] requestID in
                guard let self else { return }
                self.beginWorkspaceFileTransition(self.kmpWorkspaceFileStore.resolve(
                    sessionId: sessionID, path: item.path, requestId: requestID,
                    supportsResolve: self.supportsFileReferences, useCache: true
                ))
            }
            return
        }
        let transition = kmpWorkspaceFileStore.download(
            sessionId: sessionID,
            path: item.path,
            requestId: UUID().uuidString,
            purpose: purpose
        )
        beginWorkspaceFileTransition(transition)
    }

    func openUploadedFile(gatewayID: String, sessionID: String, file: PresentedFile) {
        guard gatewayID == gatewayLocalID, sessionID == selectedSessionId,
              workspaceFilePreviewRequest == nil, !conversationFileIsOpening,
              let attachmentID = file.attachmentId, !attachmentID.isEmpty else { return }
        let name = file.name ?? String(localized: "文件")
        let request = WorkspaceFilePreviewRequest(sessionID: sessionID, path: name)
        startWorkspaceFilePreview(request, conversation: true, needsConnection: false) { [weak self] _ in
            guard let self else { return }
            let operationID = self.workspaceFileOperationID
            Task {
                let url = await self.fileUploads.previewURL(for: file, sessionID: sessionID)
                guard operationID == self.workspaceFileOperationID, sessionID == self.selectedSessionId,
                      self.offlineCatalogActive else {
                    if let url { WorkspaceFileLifetime.shared.release(url) }
                    return
                }
                self.conversationFileIsOpening = false
                guard let url else {
                    self.failWorkspaceFilePreview(String(localized: "找不到此文件的本地副本，文件可能已被移动或删除。"))
                    return
                }
                self.workspaceFilePreviewProgress = 1
                let previewName = file.name ?? url.lastPathComponent
                self.completedWorkspaceFile = WorkspaceLocalFile(url: url, sessionID: sessionID,
                    remotePath: attachmentID, name: previewName,
                    mediaType: UTType(filenameExtension: (previewName as NSString).pathExtension)?.preferredMIMEType
                        ?? "application/octet-stream", purpose: "preview")
            }
        }
    }

    func openConversationFile(gatewayID: String, sessionID: String, path: String) {
        guard gatewayID == gatewayLocalID, sessionID == selectedSessionId else { return }
        guard workspaceFilePreviewRequest == nil, !conversationFileIsOpening else { return }
        kmpWorkspaceFileStore.setWorkspaceSession(
            sessionId: sessionID,
            workspaceSessionId: teamStore.workspaceSessionId(sessionId: sessionID)
        )
        let load: (String) -> Void = { [weak self] requestID in
            guard let self else { return }
            let workspaceSessionID = self.teamStore.workspaceSessionId(sessionId: sessionID)
            self.beginWorkspaceFileTransition(self.kmpWorkspaceFileStore.resolveReference(
                sessionId: sessionID, path: path, requestId: requestID,
                supportsResolve: self.supportsFileReferences,
                workspaceRoot: self.workspaces.first { $0.sessionIds.contains(workspaceSessionID) }?.path
            ))
        }
        // 明确的目录链接直接打开浏览器；其他目标立即呈现，类型识别也在面板中等待。
        if path.hasSuffix("/") { load(UUID().uuidString); return }
        startWorkspaceFilePreview(WorkspaceFilePreviewRequest(sessionID: sessionID, path: path),
                                  conversation: true, needsConnection: true, load: load)
    }

    private func startWorkspaceFilePreview(_ request: WorkspaceFilePreviewRequest, conversation: Bool,
                                          needsConnection: Bool, load: @escaping (String) -> Void) {
        conversationFilePreviewRequested = conversation
        workspaceFilePreviewNeedsConnection = needsConnection
        workspaceFilePreviewLoad = load
        workspaceFilePreviewRequest = request
        loadWorkspaceFilePreview(requestID: request.id.uuidString)
    }

    private func loadWorkspaceFilePreview(requestID: String) {
        guard let request = workspaceFilePreviewRequest, request.sessionID == selectedSessionId else { return }
        completedWorkspaceFile = nil
        lastError = nil
        workspaceFilePreviewError = nil
        workspaceFilePreviewProgress = 0
        workspaceFileOperationID = UUID()
        workspaceFileOperationCache = WorkspacePreviewCache(gatewayID: gatewayLocalID)
        conversationFileIsOpening = true
        if workspaceFilePreviewNeedsConnection && (!gateway.state.isConnected || !supportsFileDownloads) {
            failWorkspaceFilePreview(String(localized: "请先连接支持文件下载的 Mobile Gateway。"))
            return
        }
        if workspaceFilePreviewNeedsConnection {
            kmpWorkspaceFileStore.setWorkspaceSession(sessionId: request.sessionID,
                workspaceSessionId: teamStore.workspaceSessionId(sessionId: request.sessionID))
        }
        watchWorkspaceFilePreview()
        workspaceFilePreviewLoad?(requestID)
    }

    func retryWorkspaceFilePreview() {
        guard !conversationFileIsOpening, workspaceFilePreviewLoad != nil,
              workspaceFilePreviewRequest?.sessionID == selectedSessionId else { return }
        applyWorkspaceFileTransition(kmpWorkspaceFileStore.cancel())
        closeWorkspaceTemporaryFile(remove: true)
        // Sheet 身份不变，协议请求使用新 ID，拒绝上一次尝试的迟到响应。
        loadWorkspaceFilePreview(requestID: UUID().uuidString)
    }

    private func watchWorkspaceFilePreview() {
        workspaceFilePreviewTimeoutTask?.cancel()
        guard conversationFileIsOpening, let request = workspaceFilePreviewRequest else { return }
        workspaceFilePreviewTimeoutTask = Task { [weak self, filePreviewTimeout] in
            do { try await Task.sleep(for: filePreviewTimeout) } catch { return }
            guard let self, !Task.isCancelled, self.workspaceFilePreviewRequest?.id == request.id,
                  self.conversationFileIsOpening else { return }
            self.failWorkspaceFilePreview(String(localized: "文件加载超时，请检查网络后重试。"))
        }
    }

    private func failWorkspaceFilePreview(_ message: String) {
        workspaceFileOperationID = UUID()
        conversationFileIsOpening = false
        if workspaceFilePreviewRequest != nil { workspaceFilePreviewError = message }
        else { lastError = message }
        applyWorkspaceFileTransition(kmpWorkspaceFileStore.cancel())
        closeWorkspaceTemporaryFile(remove: true)
    }

    func cancelWorkspaceFileDownload() {
        workspaceFilePreviewLoad = nil
        workspaceFilePreviewError = nil
        workspaceFilePreviewNeedsConnection = false
        workspaceFileOperationID = UUID()
        conversationFileIsOpening = false
        workspaceFilePreviewRequest = nil
        workspaceFilePreviewProgress = 0
        if completedWorkspaceFile?.purpose == "preview" { completedWorkspaceFile = nil }
        applyWorkspaceFileTransition(kmpWorkspaceFileStore.cancel())
        closeWorkspaceTemporaryFile(remove: true)
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
        if teamStore.isChild(sessionId: sessionId) {
            if supportsTasks { gateway.requestTasks(sessionId: sessionId) }
            if supportsGoals { gateway.requestGoal(sessionId: sessionId) }
            return
        }
        if sessionId == selectedSessionId { refreshSessionAgentPreset() }
        dispatchSessionControl(.requestModels(sessionID: sessionId, isConnected: true))
        dispatchSessionControl(.requestPermissionOptions(sessionID: sessionId, isConnected: true))
        dispatchSessionControl(.requestContextUsage(sessionID: sessionId, isConnected: true))
        dispatchSessionControl(.requestSessionStats(sessionID: sessionId, isConnected: true))
        if supportsTasks { gateway.requestTasks(sessionId: sessionId) }
        if supportsGoals { gateway.requestGoal(sessionId: sessionId) }
    }

    func refreshSessionAgentPreset() {
        guard !isViewingTeamMember else { leaveSessionAgentPreset(); return }
        applySessionAgentPresetTransition(sessionAgentPresetStore.open(
            sessionId: selectedSessionId, supported: supportsSessionAgentPreset,
            connected: gateway.state.isConnected
        ))
    }

    func leaveSessionAgentPreset() {
        sessionAgentPresetTimeout?.cancel()
        applySessionAgentPresetTransition(sessionAgentPresetStore.leave())
    }

    func selectSessionAgentPreset(_ presetId: String) {
        guard gateway.state.isConnected else { return }
        applySessionAgentPresetTransition(sessionAgentPresetStore.select(presetId: presetId))
    }

    private func applySessionAgentPresetTransition(_ transition: SharedSessionAgentPresetTransition) {
        sessionAgentPreset = transition.snapshot
        if !sessionAgentPreset.loading && !sessionAgentPreset.saving { sessionAgentPresetTimeout?.cancel() }
        if let error = transition.error { lastError = error }
        if transition.refreshCommands, let id = transition.snapshot.sessionId {
            applySlashCommandTransition(kmpSlashCommandStore.invalidateCatalog(
                sessionId: id, isSupported: supportsSlashCommands, locale: Locale.current.identifier
            ))
        }
        if transition.invalidSession {
            dispatchSessionListIntent(.select(nil))
            gateway.requestSessions()
        }
        for request in transition.requests {
            if request.requestType == "agent-presets" {
                dispatchSessionControl(.requestAgentPresets(isConnected: gateway.state.isConnected))
                continue
            }
            gateway.sendRequestPayload(request.payload)
            sessionAgentPresetTimeout?.cancel()
            sessionAgentPresetTimeout = Task { [weak self] in
                do { try await Task.sleep(for: .seconds(15)) } catch { return }
                guard let self else { return }
                self.applySessionAgentPresetTransition(self.sessionAgentPresetStore.requestFailed(
                    type: request.requestType, sessionId: request.targetSessionId,
                    requestId: request.correlationId, message: "模式请求超时，请重试"
                ))
            }
        }
    }

    private func beginCommandPresetSubmission() {
        applySessionAgentPresetTransition(sessionAgentPresetStore.beginMessage())
        guard sessionAgentPreset.submitting else { return }
        let sessionID = sessionAgentPreset.sessionId
        sessionAgentPresetTimeout = Task { [weak self] in
            do { try await Task.sleep(for: .seconds(20)) } catch { return }
            guard let self else { return }
            self.applySessionAgentPresetTransition(self.sessionAgentPresetStore.requestFailed(
                type: "command-execute", sessionId: sessionID, requestId: nil, message: nil
            ))
        }
    }

    private func acceptSessionAgentPresetFrame(_ frame: GatewayFrame) {
        let kinds = ["agent-presets", "session-agent-preset", "select-agent-preset",
                     "session-agent-preset-updated", "sent", "command-executed", "history", "session-snapshot"]
        guard kinds.contains(frame.kind) || (frame.kind == "error" &&
            ["session-agent-preset", "select-agent-preset", "message", "command-execute"].contains(frame.requestType)) else { return }
        // 共享模式状态仅需要历史投影，避免重复序列化整页对话。
        var presetFrame = frame
        if (frame.kind == "command-executed" || (frame.kind == "error" && ["message", "command-execute"].contains(frame.requestType))) && presetFrame.sessionId == nil {
            presetFrame.sessionId = sessionAgentPreset.sessionId
        }
        if frame.kind == "history" || frame.kind == "session-snapshot" {
            presetFrame = GatewayFrame(kind: frame.kind, sessionId: frame.sessionId)
            presetFrame.projections = frame.projections
        }
        if let data = try? JSONEncoder().encode(presetFrame) {
            applySessionAgentPresetTransition(sessionAgentPresetStore.acceptJson(json: String(decoding: data, as: UTF8.self)))
        }
    }

    func editGoal(objective: String) {
        submitGoalMutation("goal-edit") { sessionID, ref in
            gateway.editGoal(sessionId: sessionID, ref: ref, objective: objective)
        }
    }

    func pauseGoal() {
        submitGoalMutation("goal-pause") { sessionID, ref in
            gateway.pauseGoal(sessionId: sessionID, ref: ref)
        }
    }

    func resumeGoal() {
        submitGoalMutation("goal-resume") { sessionID, ref in
            gateway.resumeGoal(sessionId: sessionID, ref: ref)
        }
    }

    func clearGoal() {
        submitGoalMutation("goal-clear") { sessionID, ref in
            gateway.clearGoal(sessionId: sessionID, ref: ref)
        }
    }

    private func submitGoalMutation(
        _ kind: String,
        send: (String, GatewayGoalReference) -> Void
    ) {
        guard let sessionID = selectedSessionId,
              let goal = selectedGoalProjection?.goal?.goal else { return }
        guard goal.phase.lowercased() != "complete" else { return }
        guard gateway.state.isConnected else {
            lastError = String(localized: "请先连接 DeepSeek Harness")
            return
        }
        guard supportsGoals else {
            lastError = String(localized: "当前 Mobile Gateway 不支持目标管理，请升级并重启网关。")
            return
        }
        guard goalMutationKind == nil else { return }
        goalMutationKind = kind
        send(sessionID, goal.ref)
    }
    func selectModel(provider: String, model: String, reasoningEffort: String?) {
        guard let sessionId = selectedSessionId else { return }
        dispatchSessionControl(.selectModel(
            sessionID: sessionId,
            selection: GatewayModelSelection(
                provider: provider,
                model: model,
                reasoningEffort: reasoningEffort
            ),
            isConnected: gateway.state.isConnected
        ))
    }
    func setPermission(_ name: String) {
        guard let sessionId = selectedSessionId else { return }
        dispatchSessionControl(.setPermission(
            sessionID: sessionId,
            value: name,
            isConnected: gateway.state.isConnected
        ))
    }
    func updateSlashCommandInput(_ text: String) {
        applySlashCommandTransition(kmpSlashCommandStore.updateInput(
            sessionId: selectedSessionId,
            text: text,
            isConnected: gateway.state.isConnected,
            isSupported: supportsSlashCommands && !isViewingTeamMember,
            locale: Locale.current.identifier
        ))
    }
    @discardableResult
    func selectSlashCommand(_ name: String) -> String? {
        let transition = kmpSlashCommandStore.selectCommand(name: name)
        applySlashCommandTransition(transition)
        return slashCommandReplacementText(from: transition)
    }
    @discardableResult
    func selectSlashCatalogItem(_ id: String) -> String? {
        let transition = kmpSlashCommandStore.selectItem(id: id)
        applySlashCommandTransition(transition)
        return slashCommandReplacementText(from: transition)
    }
    @discardableResult
    func selectSlashCommandOption(_ optionID: String) -> String? {
        let transition = kmpSlashCommandStore.selectOption(optionId: optionID)
        applySlashCommandTransition(transition)
        return slashCommandReplacementText(from: transition)
    }
    func clearActiveSlashCommand() {
        applySlashCommandTransition(kmpSlashCommandStore.clearActiveCommand())
    }
    func composedSlashMessage(arguments: String) -> String {
        arguments
    }
    @discardableResult
    func send(_ text: String, images: [GatewayOutgoingImage] = [], mode: String = "queue") -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let hasFiles = !fileUploads.drafts.isEmpty
        guard !trimmed.isEmpty || !images.isEmpty || hasFiles else { return false }
        guard !fileUploads.preparing, !hasFiles || (fileUploads.sessionID == selectedSessionId && fileUploads.canSend && !isViewingTeamMember) else {
            lastError = String(localized: "请等待附件上传完成，失败的附件可重试或移除")
            return false
        }
        guard selectedMemberCanSend else { lastError = "此子智能体会话为只读，无法继续对话"; return false }
        guard gateway.state.isConnected else { lastError = String(localized: "请先在设置中连接 DeepSeek Harness"); return false }
        guard images.isEmpty || supportsImages else {
            lastError = String(localized: "当前 Mobile Gateway 不支持图片，请升级并重启 dsh web。")
            return false
        }
        guard gateway.prepareMessageTransport(text: trimmed, images: images) else {
            lastError = String(localized: "正在切换到 WebSocket 以发送大消息，草稿已保留，请连接后重新发送。")
            return false
        }
        guard !sessionAgentPreset.blocksSending else { return false }
        if isShowingSearchContext { clearSearchContext(); conversationSearchTarget = nil }
        if !isViewingTeamMember, let command = kmpSlashCommandStore.commandExecutionForInput(text: trimmed) {
            guard !hasFiles else { lastError = String(localized: "斜杠命令不支持文件，请使用普通消息发送"); return false }
            guard images.isEmpty || command.allowsImages else {
                lastError = String(localized: "此命令不支持图片")
                return false
            }
            beginCommandPresetSubmission()
            commandSubmissionPending = true
            gateway.executeCommand(line: command.line, images: images, sessionId: selectedSessionId)
            return true
        }
        guard !messageSubmissionPending else { return false }
        applySessionAgentPresetTransition(sessionAgentPresetStore.beginMessage())
        messageSubmissionPending = true
        messageSubmissionRequestID = hasFiles
            ? fileUploads.submission(text: trimmed, imageKey: images.map { $0.id.uuidString }.joined(separator: ",") + "|" + mode)
            : UUID().uuidString
        submittedUploadIDs = fileUploads.drafts.map { $0.id }
        messageSubmissionSessionID = selectedSessionId
        updateChatPrefetchState()
        messageSubmissionTimeout?.cancel()
        messageSubmissionTimeout = Task { [weak self] in
            do { try await Task.sleep(for: .seconds(20)) } catch { return }
            guard let self, self.messageSubmissionPending else { return }
            self.messageSubmissionPending = false
            self.updateChatPrefetchState()
            self.applySessionAgentPresetTransition(self.sessionAgentPresetStore.requestFailed(
                type: "message", sessionId: self.messageSubmissionSessionID, requestId: self.messageSubmissionRequestID, message: nil
            ))
            self.waitingForNewSession = false
            self.lastError = "发送确认超时，请确认队列状态后重试"
        }
        waitingForNewSession = selectedSessionId == nil
        beginAgentBackgroundExecution(for: selectedSessionId, startsNewTurn: true)
        gateway.sendMessage(
            text: trimmed,
            images: images,
            receipts: fileUploads.receipts,
            sessionId: selectedSessionId,
            workspaceId: selectedSessionId == nil ? activeWorkspace?.id : nil,
            mode: mode,
            requestId: messageSubmissionRequestID
        )
        return true
    }

    func cancelSelectedSession() {
        guard supportsSessionCancel, selectedMemberCanSend,
              gateway.state.isConnected,
              let sessionID = selectedSessionId,
              selectedSession?.isRunning == true,
              !cancellingSessionIDs.contains(sessionID) else { return }
        cancellingSessionIDs.insert(sessionID)
        gateway.cancelSession(sessionId: sessionID)
    }

    private func applySlashCommandTransition(_ transition: SharedSlashCommandTransition) {
        slashCommands = transition.snapshot
        if let error = transition.snapshot.lastError {
            lastError = String(localized: "斜杠命令操作失败") + "：\(error)"
        }
        if transition.selectedModel != nil ||
            (transition.clearDraft && !transition.snapshot.selections.isEmpty) {
            if let sessionID = selectedSessionId { refreshSessionControls(for: sessionID) }
        }
        if let payload = transition.request?.payload {
            gateway.sendRequestPayload(payload)
        }
        if let text = transition.submitText {
            _ = send(text)
        }
        if let command = transition.commandExecution, !sessionAgentPreset.blocksSending {
            beginCommandPresetSubmission()
            gateway.executeCommand(line: command.line, sessionId: selectedSessionId)
        }
    }

    private func slashCommandReplacementText(from transition: SharedSlashCommandTransition) -> String? {
        if let replacementText = transition.replacementText { return replacementText }
        return transition.clearDraft ? "" : nil
    }

    func answerQuestion(_ request: GatewayPendingQuestionRequest, answers: [GatewayQuestionAnswer]) {
        let transition = dispatchQuestionIntent(.submitAnswer(
            rpcID: request.rpcId,
            answers: answers,
            isConnected: gateway.state.isConnected
        ))
        if transition.effect == nil,
           questionRequestStatuses[request.rpcId]?.isAnswerInFlight != true {
            backgroundExecutionController.releaseQuestionAnswer(rpcID: request.rpcId)
        }
    }

    func cancelQuestion(_ request: GatewayPendingQuestionRequest) {
        let transition = dispatchQuestionIntent(.submitCancel(
            rpcID: request.rpcId,
            isConnected: gateway.state.isConnected
        ))
        _ = transition
    }

    func respondToApproval(
        _ request: GatewayPendingApprovalRequest,
        outcome: GatewayApprovalOutcome
    ) {
        let command = commandPreview(for: request)
        let transition = dispatchApprovalIntent(.submitDecision(
            rpcID: request.rpcId,
            outcome: outcome,
            isConnected: gateway.state.isConnected
        ))
        if transition.effect != nil {
            AgentLiveActivityManager.shared.approvalSubmitting(
                gatewayID: gatewayLocalID,
                request: request,
                outcome: outcome,
                title: title(for: request.sessionId),
                command: command,
                sourceLabel: liveActivitySourceLabel(for: request.sessionId)
            )
        } else {
            AgentLiveActivityManager.shared.approvalFailed(
                gatewayID: gatewayLocalID,
                sessionID: request.sessionId,
                rpcID: request.rpcId,
                title: title(for: request.sessionId),
                reason: transition.error?.localizedDescription,
                sourceLabel: liveActivitySourceLabel(for: request.sessionId)
            )
        }
    }
    func title(for sessionId: String) -> String { sessions.first(where: { $0.id == sessionId })?.title ?? "DeepSeek Harness" }
    private func liveActivitySourceLabel(for sessionID: String) -> String {
        let host = gatewayDisplayName.isEmpty ? "Whaleness" : gatewayDisplayName
        guard let workspace = workspaces.first(where: { $0.sessionIds.contains(sessionID) }) else { return host }
        let workspaceName = workspace.title.isEmpty
            ? URL(fileURLWithPath: workspace.path).lastPathComponent
            : workspace.title
        return workspaceName.isEmpty ? host : "\(host) · \(workspaceName)"
    }

    private func beginSnapshotWait(for sessionID: String) {
        historyLoadErrors[sessionID] = nil
        if pendingSnapshotSessionID != sessionID { cancelSnapshotWait() }
        do {
            try kmpHistoryStore.awaitSnapshot(sessionID: sessionID)
            pendingSnapshotSessionID = sessionID
            beginHistoryRequestTimeout(for: sessionID)
        } catch {
            lastError = error.localizedDescription
        }
    }

    private func cancelSnapshotWait() {
        guard let id = pendingSnapshotSessionID else { return }
        pendingSnapshotSessionID = nil
        finishHistoryLoading(id)
    }

    private func subscribeToSession(_ sessionID: String?) {
        if conversationSearchTarget != nil, let sessionID,
           assistantStreamState.hasBaseline(sessionId: sessionID) { return }
        if let previous = selectedSessionId, kmpConversationStore.hasAssistantChunks(sessionID: previous) {
            try? flushPendingConversationHistory(sessionID: previous)
            try? kmpConversationStore.clearAssistantChunks(sessionID: previous)
        }
        assistantStreamState.selectSession(sessionId: sessionID)
        if usesAssistantStream, let sessionID {
            beginSnapshotWait(for: sessionID)
        } else {
            cancelSnapshotWait()
        }
        gateway.subscribe(sessionId: sessionID)
    }

    private func handle(_ frame: GatewayFrame) {
        if frame.kind == "error", frame.code == "subscription-timeout",
           frame.sessionId == nil || frame.sessionId != selectedSessionId || preparedConversationActivationKey != frame.sessionId {
            // 后台监控或已离开的会话不能通过旧订阅超时打断当前页面。
            return
        }
        if frame.kind == "error", frame.code == "subscription-timeout", let sessionID = frame.sessionId {
            if gateway.recoverSubscription(sessionID: sessionID) {
                historyLoadErrors[sessionID] = nil
                return
            }
            // 自动恢复耗尽后保留聊天内容，通过现有历史错误条提供手动重试。
            recordHistoryFailure(frame.message ?? String(localized: "历史记录加载超时，请重试"), sessionID: sessionID)
            return
        }
        if frame.kind.hasPrefix("file-upload-") || frame.requestType?.hasPrefix("file-upload-") == true {
            fileUploads.accept(frame)
            return
        }
        if frame.kind == "hello" {
            teamMemberProfileTimeouts.values.forEach { $0.cancel() }
            teamMemberProfileTimeouts.removeAll()
        }
        if ["history", "error"].contains(frame.kind), let requestID = frame.requestId,
           requestID.hasPrefix("team-profile-") {
            if let data = try? JSONEncoder().encode(frame),
               teamStore.acceptFrameJson(json: String(decoding: data, as: UTF8.self)) { publishTeamSnapshot() }
            if !teamStore.memberProfileRequestPending(requestId: requestID) {
                teamMemberProfileTimeouts.removeValue(forKey: requestID)?.cancel()
            }
            prepareTeamMemberProfiles()
            return
        }
        if frame.kind == "hello" { gateway.resetSubscriptionRequests() }
        if frame.kind == "hello", let serverTime = frame.at ?? frame.time {
            turnTiming.observeServerTime(epochSeconds: serverTime, nowMonotonicSeconds: AgentTurnMonotonicClock.now)
        }
        if frame.kind == "subscribed", !gateway.acceptSubscriptionAck(sessionID: frame.sessionId) { return }
        if frame.kind == "error", frame.requestType == "subscribe" { gateway.resetSubscriptionRequests() }
        if ["hello", "sessions", "session-status", "projection-baseline", "agent-team", "agent-team-updated", "subagents", "subagents-updated", "subagent-identity-updated", "subagent-timing-updated", "session-snapshot", "history", "error"].contains(frame.kind),
           let data = try? JSONEncoder().encode(frame),
           teamStore.acceptFrameJson(json: String(decoding: data, as: UTF8.self)) {
            publishTeamSnapshot()
            let routing = teamStore.routingJson()
            if routing != persistedTeamRouting {
                objectWillChange.send()
                persistedTeamRouting = routing
                persistOfflineCatalog()
            }
            if frame.kind == "hello" { refreshTeam() }
        }
        if ["agent-team", "agent-team-updated", "subagents", "subagents-updated", "subagent-identity-updated", "subagent-timing-updated", "session-status"].contains(frame.kind) { return }

        if chatPrefetcher.consume(frame) || chatPrefetcher.consumeHistoryError(frame,
            foregroundHistoryPending: !historyLoadingSessionIds.isEmpty) { return }
        defer { updateChatPrefetchState() }
        if frame.kind == "hello" {
            remoteSearchTimeout?.cancel()
            remoteSearchGate.reset()
            voiceInput.hello(frame.capabilities ?? [])
        }
        if frame.kind == "search" {
            guard let accepted = remoteSearchGate.complete(echoedQuery: frame.query, desiredQuery: localSearchQuery) else { return }
            remoteSearchTimeout?.cancel()
            guard accepted else { requestRemoteSearchIfPossible(); return }
        }
        if frame.kind == "plugin-catalog" || frame.kind == "plugins-changed" ||
            frame.kind == "plugin-bundle-set-enabled" || frame.kind == "plugin-entry-set-enabled" ||
            (frame.kind == "error" && frame.requestType?.hasPrefix("plugin-") == true) {
            acceptPluginFrame(frame)
            return
        }
        if frame.kind == scheduledTaskMutationKind,
           frame.requestId == scheduledTaskMutationRequestID {
            let succeeded = frame.kind == "schedule-update" ? frame.updated == true : frame.deleted == true
            finishScheduledTaskMutation(error: succeeded ? nil : scheduledTaskFailureMessage(frame.code, frame.message))
            return
        }
        if frame.kind == "error", frame.requestType == scheduledTaskMutationKind,
           (frame.requestId == nil || frame.requestId == scheduledTaskMutationRequestID) {
            finishScheduledTaskMutation(error: scheduledTaskFailureMessage(frame.code, frame.message))
            return
        }
        if frame.kind == "schedule-catalog" {
            scheduledTasks = (frame.items ?? []).compactMap(ScheduledTask.init).sorted {
                if $0.status != $1.status { return $0.status == "active" }
                return $0.scheduledAt < $1.scheduledAt
            }
            scheduledTasksLoading = false
            scheduledTasksError = nil
            return
        }
        if frame.kind == "schedule-changed" {
            if gateway.state.isConnected { gateway.requestScheduleCatalog() }
            return
        }
        if frame.kind == "error" && frame.requestType == "schedule-catalog" {
            scheduledTasksLoading = false
            scheduledTasksError = frame.message ?? "定时任务加载失败"
            return
        }
        acceptSessionAgentPresetFrame(frame)
        if ["session-agent-preset", "select-agent-preset", "session-agent-preset-updated"].contains(frame.kind) ||
            (frame.kind == "error" && ["session-agent-preset", "select-agent-preset"].contains(frame.requestType)) { return }
        acceptQueueFrame(frame)
        let matchesSubmission = (frame.requestId == messageSubmissionRequestID || (submittedUploadIDs.isEmpty && frame.requestId == nil)) &&
            (messageSubmissionSessionID == nil || frame.sessionId == nil || frame.sessionId == messageSubmissionSessionID)
        if frame.kind == "sent" && messageSubmissionPending && matchesSubmission {
            messageSubmissionPending = false
            messageSubmissionTimeout?.cancel()
            fileUploads.accepted(submittedUploadIDs)
            submittedUploadIDs = []
            messageAcceptedRevision &+= 1
        }
        if frame.kind == "error" && (frame.requestType == "message" || frame.requestType == nil) && messageSubmissionPending && matchesSubmission {
            messageSubmissionPending = false
            messageSubmissionTimeout?.cancel()
            waitingForNewSession = false
            if frame.code == "session/attachment-invalid" { fileUploads.invalidReceipts() }
        }
        if ["session-queues", "session-queue", "queue-item-updated"].contains(frame.kind) { return }
        if frame.kind == "hello" {
            usesAssistantStream = frame.capabilities?.contains("assistant-stream-v1") == true
            if pluginPageVisited { refreshPlugins() }
        }
        if frame.subscriptionId != nil { drainKMPEventDeliveries() }
        defer { if frame.subscriptionId != nil { drainKMPEventDeliveries() } }
        if frame.kind == "hello" {
            chatCacheHelloReceived = true
            chatCacheHelloFormat = frame.historyFormatVersion
        }
        if frame.kind == "history", let id = frame.sessionId,
           assistantStreamState.hasBaseline(sessionId: id),
           !historyLoadingOlderSessionIds.contains(id) { return }
        if let encoded = try? JSONEncoder().encode(frame.assistantStreamValidationFrame) {
            let update = assistantStreamState.acceptJson(json: String(decoding: encoded, as: UTF8.self))
            do {
                for id in update.invalidatedSessionIds {
                    turnTiming.clear(sessionId: id)
                    invalidateChatCache(sessionID: id)
                    historySyncEngine.finish(sessionID: id)
                    try kmpHistoryStore.clear(sessionID: id)
                    discardPendingConversationHistory(sessionID: id)
                    try kmpConversationStore.clear(sessionID: id)
                    try kmpTrajectoryStore.clear(sessionID: id)
                    trajectoryProjectedVersions[id] = nil
                    taskProjections[id] = nil
                    goalProjections[id] = nil
                }
                if update.clearTransient, let id = update.sessionId,
                   kmpConversationStore.hasAssistantChunks(sessionID: id) {
                    try flushPendingConversationHistory(sessionID: id)
                    try kmpConversationStore.clearAssistantChunks(sessionID: id)
                    if activeTrajectorySessionIDs.contains(id) { scheduleTrajectoryProjection(for: id) }
                }
                if let error = update.error {
                    lastError = error
                    if !update.resubscribe, let id = update.sessionId {
                        recordHistoryFailure(error, sessionID: id)
                    }
                }
                if update.resubscribe, let id = update.sessionId { subscribeToSession(id) }
                guard update.accepted else { return }
                if frame.kind == "assistant-stream", let id = frame.sessionId {
                    if let serverTime = frame.frame?["time"]?.doubleValue {
                        turnTiming.observeServerTime(epochSeconds: serverTime, nowMonotonicSeconds: AgentTurnMonotonicClock.now)
                    }
                    let streamType = frame.frame?["type"]?.stringValue
                    if streamType == "start" {
                        cancelLiveActivityStreamUpdate(sessionID: id)
                    } else if streamType == "chunk", let chunk = frame.frame?["chunk"] {
                        enqueueLiveActivityStreamChunk(
                            sessionID: id,
                            attemptID: frame.frame?["attemptId"]?.stringValue ?? "assistant-stream",
                            chunkType: chunk["type"]?.stringValue ?? "",
                            text: chunk["text"]?.stringValue ?? chunk["argumentsDelta"]?.stringValue,
                            toolName: chunk["name"]?.stringValue,
                            step: frame.frame?["step"]?.doubleValue.map(Int.init)
                        )
                    }
                }
                if frame.kind == "session-snapshot", let id = frame.sessionId {
                    // 到达这里的快照已通过订阅、流标识和格式校验，可以证明订阅生效。
                    _ = gateway.acceptSubscriptionSnapshot(sessionID: id)
                    if isShowingSearchContext, conversationSearchTarget == nil { clearSearchContext() }
                    historyLoadErrors[id] = nil
                    lastError = nil
                    historySyncEngine.finish(sessionID: id)
                    let records = (frame.events ?? []).map { $0.normalized(sessionId: id) }
                    if assistantStreamState.activeAttemptId() == nil {
                        dispatchSessionListIntent(.historyReceived(sessionID: id, events: records))
                    }
                    let replaced = try kmpHistoryStore.installSnapshot(sessionID: id,
                        events: records,
                        hasMore: frame.hasMore == true, nextBeforeSequence: frame.nextBeforeSeq)
                    if pendingSnapshotSessionID == id { pendingSnapshotSessionID = nil }
                    applyHistoryProjections(frame.projections, sessionId: id)
                    installTaskGoalBaseline(frame.projections, sessionID: id)
                    drainKMPEventDeliveries()
                    // 相同持久历史不再发布 replace；新的生成前缀仍须恢复。
                    if !replaced, let attemptID = assistantStreamState.activeAttemptId() {
                        try flushPendingConversationHistory(sessionID: id, replayTransient: false)
                        try kmpConversationStore.assistantChunks(sessionID: id, attemptID: attemptID,
                            chunksJSON: assistantStreamState.replayChunksJson())
                        drainKMPEventDeliveries()
                    }
                    return
                }
                if let attemptID = update.attemptId, let id = update.sessionId, update.chunksJson != "[]" {
                    try flushPendingConversationHistory(sessionID: id, replayTransient: false)
                    try kmpConversationStore.assistantChunks(sessionID: id, attemptID: attemptID,
                        chunksJSON: update.chunksJson)
                    if activeTrajectorySessionIDs.contains(id) { scheduleTrajectoryProjection(for: id) }
                }
                if ["assistant-stream", "session-stream-reset"].contains(frame.kind) { return }
            } catch {
                lastError = error.localizedDescription
                if frame.sessionId == pendingSnapshotSessionID { cancelSnapshotWait() }
                return
            }
        }
        if frame.kind == "projection-baseline" {
            taskProjections = [:]
            goalProjections = [:]
            for (id, projection) in frame.projections?.objectValue ?? [:] {
                installTaskGoalBaseline(projection, sessionID: id)
            }
            return
        }
        if frame.kind == "error", frame.requestType == "subscribe",
           frame.sessionId == nil || frame.sessionId == pendingSnapshotSessionID {
            cancelSnapshotWait()
        }
        if frame.kind == "tasks" || frame.kind == "tasks-updated" {
            applyTasksProjection(frame)
            return
        }
        if frame.kind == "goal" || frame.kind == "goal-updated" {
            applyGoalProjection(frame)
            return
        }
        if Self.goalMutationResponseKinds.contains(frame.kind) {
            goalMutationKind = nil
            if let sessionID = frame.sessionId { gateway.requestGoal(sessionId: sessionID) }
            return
        }
        if frame.kind == "error", let requestType = frame.requestType,
           Self.goalMutationResponseKinds.contains(requestType) {
            goalMutationKind = nil
            if let sessionID = frame.sessionId ?? selectedSessionId {
                gateway.requestGoal(sessionId: sessionID)
            }
        }
        if frame.kind == "command-executed" {
            handleCommandExecutionResult(frame)
            return
        }
        if frame.kind == "error", frame.requestType == "command-execute" {
            commandSubmissionPending = false
        }
        if Self.workspaceFileFrameKinds.contains(frame.kind) ||
            (frame.kind == "error" && frame.requestType?.hasPrefix("file-") == true) {
            if let data = try? JSONEncoder().encode(frame) {
                let transition = kmpWorkspaceFileStore.acceptFrame(
                    json: String(decoding: data, as: UTF8.self)
                )
                applyWorkspaceFileTransition(transition)
            }
            // 文件错误由文件状态机处理；目录识别响应不能再弹成会话错误。
            return
        }
        let context = GatewayFrameRoutingContext(
            selectedSessionID: selectedSessionId,
            pendingHistorySessionID: kmpHistoryStore.pendingSessionID,
            pendingModelsSessionID: pendingModelsSessionId,
            isPendingGlobalModelsRequest: isPendingGlobalModelsRequest,
            pendingModelSelectionSessionID: pendingModelSelectionSessionId,
            pendingPermissionOptionsSessionID: pendingPermissionOptionsSessionId
        )
        let route = GatewayFrameRouter.route(frame, context: context)
        handle(route)
    }

    private func scheduledTaskFailureMessage(_ code: String?, _ message: String?) -> String {
        switch code {
        case "schedule_conflict": "任务已被其他设备修改，列表已刷新，请重新打开编辑"
        case "schedule_ended": "任务已结束，无法再编辑"
        case "schedule_not_found": "任务已不存在，列表已刷新"
        default: message ?? "操作失败，请稍后重试"
        }
    }

    private func handleCommandExecutionResult(_ frame: GatewayFrame) {
        let result = frame.result?.objectValue
        let succeeded = result?["kind"]?.stringValue == "success"
        let detail = result?["text"]?.stringValue
        commandSubmissionPending = false
        if succeeded {
            commandDraftClearToken &+= 1
            clearActiveSlashCommand()
        } else {
            lastError = detail ?? frame.message ?? String(localized: "命令执行失败")
        }
    }

    private func handle(_ route: GatewayFrameRoute) {
        switch route {
        case .connection(let route): handleConnectionRoute(route)
        case .content(let route): handleContentRoute(route)
        case .control(let route): handleControlRoute(route)
        case .workspace(let route): handleWorkspaceRoute(route)
        case .question(let route): handleQuestionRoute(route)
        case .approval(let route): handleApprovalRoute(route)
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
            offlineHistoryBoundarySessionIds.removeAll()
            offlineHistoryLoadingOlderSessionIds.removeAll()
            // hello 定义新的连接代际。必须先清除上一代 request identity/quarantine，
            // 再发送本代 refresh，避免旧 token 或迟到响应跨连接污染新请求。
            resetOutstandingRequests()
            chatPrefetcher.connectedHello()
            resumeSessionControlRequests()
            // 主动后台挂起不触发 onConnectionFailure，也必须重新激活订阅。
            activeConversationActivationKey = nil
            backgroundExecutionController.releaseAllQuestionAnswers()
            dispatchQuestionIntent(.reset)
            dispatchApprovalIntent(.reset)
            // 本代上传能力必须在旧连接清理后初始化，避免被 disconnected() 再次关闭。
            fileUploads.connected(sessionID: selectedSessionId,
                supported: payload.protocolVersion >= 3 && payload.capabilities.contains("file-upload-v1"))
            supportsImages = payload.protocolVersion >= 3 && payload.capabilities.contains("images")
            supportsFileDownloads = payload.protocolVersion >= 3 && payload.capabilities.contains("file-downloads")
            supportsFileReferences = payload.capabilities.contains("file-references-v1")
            supportsSlashCommands = payload.capabilities.contains("commands")
            supportsSessionCreation = payload.capabilities.contains("session-create")
            supportsSessionAgentPreset = payload.capabilities.contains("session-agent-preset")
            supportsTasks = payload.capabilities.contains("tasks")
            supportsGoals = payload.capabilities.contains("goals")
            supportsSessionCancel = payload.capabilities.contains("session-cancel")
            supportsQueueControl = payload.capabilities.contains("queue-control")
            queueActionTimeout?.cancel()
            queueState = queueStore.resetConnection()
            queueRevision &+= 1
            cancellingSessionIDs = []
            applySlashCommandTransition(kmpSlashCommandStore.reset(sessionId: selectedSessionId))
            if conversationFileIsOpening, workspaceFilePreviewNeedsConnection {
                failWorkspaceFilePreview(String(localized: "连接已中断，请恢复网络后重试。"))
            }
            if workspaceFilePreviewNeedsConnection { workspaceFileOperationID = UUID() }
            applyWorkspaceFileTransition(kmpWorkspaceFileStore.reset(sessionId: selectedSessionId))
            attachmentLoader.reset()
            presentsNextConnectionFailureAsAlert = false
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
            // 主页恢复的 selectedSessionId 只是目录元数据，不能据此打开会话。
            // 尤其旧缓存中的子智能体可能尚未恢复直接父地址。
            if let selectedSessionId,
               preparedConversationActivationKey != nil || backgroundMonitoredSessionID == selectedSessionId {
                refreshSessionControls(for: selectedSessionId)
            }
            // 恢复旧预加载槽时，即使当前只选中而尚未激活视图，也必须重发真实前台请求。
            let recoverySessionID = chatPrefetchRecoverySessionID
            chatPrefetchRecoverySessionID = nil
            if let recoverySessionID, selectedSessionId == recoverySessionID,
               preparedConversationActivationKey == nil {
                if usesAssistantStream { subscribeToSession(recoverySessionID) }
                else { startHistorySync(for: recoverySessionID) }
            }
            // rc.2 由订阅原子快照恢复历史和生成前缀。
            if preparedConversationActivationKey != nil, let selectedSessionId {
                // Receiving hello is itself the transport-connected boundary. Use
                // the unguarded sync entry so tests and connection recovery cannot
                // race the separately published GatewayClient state mirror.
                if usesAssistantStream {
                    beginSnapshotWait(for: selectedSessionId)
                } else {
                    cancelSnapshotWait()
                    startHistorySync(for: selectedSessionId)
                }
            }
            if preparedConversationActivationKey != nil {
                Task { [weak self] in
                    guard let self else { return }
                    await self.activatePreparedConversation(sessionID: self.selectedSessionId)
                }
            } else if let backgroundMonitoredSessionID {
                subscribeToSession(backgroundMonitoredSessionID)
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

    private func beginWorkspaceFileTransition(_ transition: DeepSeekHarnessShared.SharedWorkspaceFileTransition) {
        if transition.request != nil, transition.snapshot.lastError == nil {
            workspaceFileOperationID = UUID()
        }
        applyWorkspaceFileTransition(transition)
    }

    private func applyWorkspaceFileTransition(
        _ transition: DeepSeekHarnessShared.SharedWorkspaceFileTransition,
        cachedURL: URL? = nil
    ) {
        if let directory = transition.directory {
            guard directory.sessionId == selectedSessionId else { return }
            conversationFileIsOpening = false
            workspaceFilePreviewRequest = nil
            workspaceFilePreviewLoad = nil
            workspaceFilePreviewError = nil
            conversationFilePreviewRequested = false
            workspaceFilePreviewProgress = 0
            workspaceFilePath = directory.path
            workspaceFileEntries = []
            workspaceFilesAreLoading = true
            conversationDirectorySessionID = directory.sessionId
            conversationDirectoryPath = directory.path
            return
        }
        if conversationFileIsOpening,
           transition.request != nil || transition.appendBase64Data != nil || transition.cacheLookup != nil {
            watchWorkspaceFilePreview()
        }
        let cache = workspaceFileOperationCache ?? WorkspacePreviewCache(gatewayID: gatewayLocalID)
        if let lookup = transition.cacheLookup {
            let entry = WorkspacePreviewCacheEntry(lookup)
            let operationID = workspaceFileOperationID
            Task {
                let url = await Task.detached(priority: .userInitiated) { cache.find(entry) }.value
                guard operationID == workspaceFileOperationID else {
                    if let url { WorkspaceFileLifetime.shared.release(url) }
                    return
                }
                let next = kmpWorkspaceFileStore.completeCacheLookup(requestId: lookup.requestId, available: url != nil)
                applyWorkspaceFileTransition(next, cachedURL: url)
            }
            return
        }
        if let hit = transition.cacheHit, let url = cachedURL, hit.sessionId == selectedSessionId {
            conversationFileIsOpening = false
            completedWorkspaceFile = WorkspaceLocalFile(url: url, sessionID: hit.sessionId,
                remotePath: hit.path, name: hit.name, mediaType: hit.mediaType, purpose: "preview")
        }
        if transition.discardTransferId != nil {
            closeWorkspaceTemporaryFile(remove: true)
        }
        let snapshot = transition.snapshot
        if workspaceFilePreviewRequest != nil {
            if let active = snapshot.activeDownload, active.purpose == "preview", active.size > 0 {
                workspaceFilePreviewProgress = min(1, max(0, Double(active.receivedBytes) / Double(active.size)))
            } else if snapshot.lastError == nil,
                      transition.appendBase64Data != nil || transition.completion?.purpose == "preview" || transition.cacheHit != nil {
                // 最后一块已校验完成，远端版本检查和缓存复制期间保持 100%。
                workspaceFilePreviewProgress = 1
            }
        }
        if snapshot.activeDownload != nil, workspaceFileHandle == nil {
            do {
                try openWorkspaceTemporaryFile(name: snapshot.activeDownload?.name ?? "download")
            } catch {
                conversationFileIsOpening = false
                failWorkspaceFilePreview(String(localized: "无法创建文件下载临时文件：\(error.localizedDescription)"))
                return
            }
        }
        if let base64 = transition.appendBase64Data {
            guard let bytes = Data(base64Encoded: base64), let workspaceFileHandle else {
                failWorkspaceFilePreview(String(localized: "文件分块无法写入临时文件"))
                return
            }
            do {
                let free = (try? URL(fileURLWithPath: NSHomeDirectory()).resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage) ?? Int64.max
                guard free - Int64(bytes.count) >= WorkspacePreviewCache.minimumFreeBytes else {
                    throw CocoaError(.fileWriteOutOfSpace)
                }
                try workspaceFileHandle.seekToEnd()
                try workspaceFileHandle.write(contentsOf: bytes)
            } catch {
                failWorkspaceFilePreview(String(localized: "写入下载文件失败：\(error.localizedDescription)"))
                return
            }
        }
        workspaceFilePath = snapshot.path
        workspaceFileEntries = snapshot.entries.map { item in
            GatewayDirectoryItem(
                name: item.name,
                path: item.path,
                hidden: item.hidden,
                kind: item.kind,
                bytes: item.bytes?.int64Value,
                modifiedAt: item.modifiedAt?.doubleValue,
                mediaType: item.mediaType
            )
        }
        workspaceFilesAreLoading = snapshot.isLoading
        if let active = snapshot.activeDownload, active.size > 0 {
            workspaceFileDownloadProgress = min(1, Double(active.receivedBytes) / Double(active.size))
        } else {
            workspaceFileDownloadProgress = snapshot.activeDownload == nil ? nil : 0
        }
        workspaceFileDownloadPath = snapshot.activeDownload?.path
        workspaceFileDownloadPurpose = snapshot.activeDownload?.purpose
        if let message = snapshot.lastError {
            conversationFileIsOpening = false
            failWorkspaceFilePreview(workspaceFileErrorMessage(message))
            return
        }
        if let completion = transition.completion,
           let url = workspaceFileTemporaryURL {
            closeWorkspaceTemporaryFile(remove: false)
            let fileSize = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?
                .int64Value
            guard fileSize == completion.size else {
                WorkspaceFileLifetime.shared.release(url, deleteTemporary: true)
                workspaceFileTemporaryURL = nil
                conversationFileIsOpening = false
                failWorkspaceFilePreview(String(localized: "下载文件的本地大小校验失败"))
                return
            }
            guard completion.sessionId == selectedSessionId else {
                WorkspaceFileLifetime.shared.release(url, deleteTemporary: true)
                workspaceFileTemporaryURL = nil
                return
            }
            let operationID = workspaceFileOperationID
            workspaceFileTemporaryURL = nil
            let entry = WorkspacePreviewCacheEntry(completion)
            // 完整性校验和缓存复制在后台执行，避免大文件阻塞聊天界面。
            Task {
                let saved = await Task.detached(priority: .userInitiated) {
                    entry.flatMap { cache.store(url, entry: $0) }
                }.value
                if saved != nil { WorkspaceFileLifetime.shared.release(url, deleteTemporary: true) }
                guard operationID == workspaceFileOperationID, completion.sessionId == selectedSessionId else {
                    if let saved { WorkspaceFileLifetime.shared.release(saved) }
                    else { WorkspaceFileLifetime.shared.release(url, deleteTemporary: true) }
                    return
                }
                conversationFileIsOpening = false
                completedWorkspaceFile = WorkspaceLocalFile(url: saved ?? url, sessionID: completion.sessionId,
                    remotePath: completion.path, name: completion.name,
                    mediaType: completion.mediaType, purpose: completion.purpose)
                let historyCache = chatHistoryCache
                Task { _ = await AppCacheMaintenance.shared.run(cache: historyCache) }
            }
        }
        if let request = transition.request {
            gateway.sendRequestPayload(request.payload)
        }
    }

    private func openWorkspaceTemporaryFile(name: String) throws {
        closeWorkspaceTemporaryFile(remove: true)
        try WorkspaceFileLifetime.shared.synchronized {
            guard isSafeManagedCacheDirectory(WorkspacePreviewCache.temporaryRoot) else { throw CocoaError(.fileWriteInvalidFileName) }
            let candidate = URL(fileURLWithPath: name).lastPathComponent
            let safeName = ["", ".", ".."].contains(candidate) ? "download" : candidate
            let directory = WorkspacePreviewCache.temporaryRoot
                .appendingPathComponent(UUID().uuidString, isDirectory: true)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let url = directory.appendingPathComponent(safeName)
            guard FileManager.default.createFile(atPath: url.path, contents: nil) else {
                throw CocoaError(.fileWriteUnknown)
            }
            WorkspaceFileLifetime.shared.retain(url)
            workspaceFileTemporaryURL = url
            workspaceFileHandle = try FileHandle(forWritingTo: url)
        }
    }

    private func closeWorkspaceTemporaryFile(remove: Bool) {
        try? workspaceFileHandle?.close()
        workspaceFileHandle = nil
        if remove, let url = workspaceFileTemporaryURL {
            WorkspaceFileLifetime.shared.release(url, deleteTemporary: true)
            workspaceFileTemporaryURL = nil
        }
    }

    private func workspaceFileErrorMessage(_ code: String) -> String {
        switch code {
        case "file-references-upgrade-required": "此文件需要新版网关解析，请升级 Mobile Gateway。"
        case "file-download-integrity-failed": String(localized: "文件完整性校验失败，请重新下载。")
        case "download-busy": String(localized: "已有文件正在下载，请稍后再试。")
        case "file-download-offset-mismatch": String(localized: "文件分块顺序异常，下载已取消。")
        default: String(localized: "工作区文件请求失败：\(code)")
        }
    }

    func archiveSession(_ sessionID: String) {
        guard gateway.state.isConnected else {
            lastError = String(localized: "请先在设置中连接 DeepSeek Harness")
            return
        }
        gateway.archiveSession(sessionId: sessionID)
    }

    func renameSession(_ sessionID: String, title: String) {
        guard gateway.state.isConnected else {
            lastError = String(localized: "请先在设置中连接 DeepSeek Harness")
            return
        }
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        gateway.renameSession(sessionId: sessionID, title: trimmed)
    }

    private func handleContentRoute(_ route: GatewayContentRoute) {
        switch route {
        case .sent(let sessionID, let command):
            handleSent(sessionID: sessionID, command: command)
        case .liveEvent(let record):
            merge(record)
            enqueueImageAttachments(record.event.images ?? [], sessionId: record.sessionId)
        case .workspaces(let received, let archivedSessionIDs):
            offlineWorkspaceRevision &+= 1
            workspaces = received
            if selectedWorkspaceId == nil || (
                selectedWorkspaceId != Self.ungroupedWorkspaceID &&
                selectedWorkspaceId != Self.allWorkspacesID &&
                !workspaces.contains(where: { $0.id == selectedWorkspaceId })
            ) {
                selectedWorkspaceId = workspaces.first?.id ?? Self.ungroupedWorkspaceID
            }
            if let archivedSessionIDs { dispatchSessionListIntent(.setArchivedSessionIDs(archivedSessionIDs)) }
            persistOfflineCatalog()
            notice(
                String(localized: "notice.workspaces.synced", defaultValue: "工作区已同步"),
                String(localized: "workspaces.count", defaultValue: "\(workspaces.count) 个工作区")
            )
        case .sessionArchives(let ids):
            dispatchSessionListIntent(.setArchivedSessionIDs(ids))
            gateway.requestSessions()
        case .sessionTitle(let id, let title, let sequence, let time):
            dispatchSessionListIntent(.eventReceived(SessionEvent(
                sessionId: id,
                seq: sequence,
                time: time ?? sessions.first(where: { $0.id == id })?.lastActivity.timeIntervalSince1970 ?? 0,
                event: GatewayEvent(type: "session/title", text: title)
            )))
            AgentLiveActivityManager.shared.sessionTitleUpdated(
                gatewayID: gatewayLocalID,
                sessionID: id,
                title: title
            )
        case .sessions(let received):
            dispatchSessionListIntent(.remoteSessionsReceived(received))
            updateChatPrefetchState(sessionsReceived: true)
            let runningBySessionID = received.reduce(into: [String: Bool]()) { result, session in
                result[session.sessionId] = session.running
            }
            AgentLiveActivityManager.shared.reconcileSessions(
                gatewayID: gatewayLocalID,
                runningBySessionID: runningBySessionID
            )
            backgroundExecutionController.reconcileSessions(
                runningBySessionID: runningBySessionID
            )
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
            guard !localSearchQuery.isEmpty else { break }
            let localIDs = Set(localSearchResults.map(\.sessionId))
            searchResults = localSearchResults + received.filter { !localIDs.contains($0.sessionId) }
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
            let tracksPresets = finishRequest == "agent-presets" &&
                kmpSessionControlStore.snapshot.requestTokens["agent-presets"] != nil
            if tracksPresets {
                notice("Agent 预设诊断", "收到响应，开始处理")
            }
            let transition = dispatchSessionControlAction(action)
            if tracksPresets {
                if transition.applied { agentPresetsLoadError = nil }
                notice("Agent 预设诊断", "响应处理完成 applied=\(transition.applied)")
            }
            // KMP 响应事务已原子完成 active 并可能启动 queued；
            // 这里不能再按 kind finish，否则会误结束新 generation。
            if let finishRequest { cancelCompletedSessionControlTracker(finishRequest, transition: transition) }
        case .sessionCancelled(let sessionID, let accepted):
            if !accepted {
                cancellingSessionIDs.remove(sessionID)
                let message = String(
                    localized: "session.cancel.not-accepted",
                    defaultValue: "停止请求未被服务端接受"
                )
                lastError = message
                notice(
                    String(localized: "session.cancel.failed-title", defaultValue: "停止生成失败"),
                    message,
                    sessionId: sessionID,
                    isError: true
                )
            }
        case .saveDefaultModel(let saved):
            if let saved {
                let transition = submitSessionControlIntent(.defaultModelSaved(saved))
                cancelCompletedSessionControlTracker("save-default-model", transition: transition)
                if transition.completed("save-default-model") {
                    notice(
                        String(localized: "默认模型已更新"),
                        [saved.model, saved.reasoningEffort].compactMap { $0 }.joined(separator: " · ")
                    )
                }
            } else {
                if failDefaultConfigurationRequest("save-default-model") {
                    let message = String(localized: "服务端未确认默认模型更新。")
                    lastError = message
                    notice(String(localized: "默认模型更新失败"), message, isError: true)
                }
            }
        case .setDefault(let applied, let target, let value):
            if applied, let target, let value {
                let transition = dispatchSessionControlAction(.globalDefaultApplied(target: target, value: value))
                cancelCompletedSessionControlTracker("set-default", transition: transition)
                if transition.completed("set-default") {
                    notice(
                        String(localized: "notice.defaults.updated", defaultValue: "默认配置已更新"),
                        String(localized: "defaults.updated.detail", defaultValue: "\(target) · \(value)")
                    )
                    dispatchSessionControl(.requestDefaults(isConnected: gateway.state.isConnected))
                }
            } else {
                if failDefaultConfigurationRequest(
                    "set-default",
                    responseTarget: target,
                    responseValue: value
                ) {
                    let message = String(localized: "服务端未确认默认配置更新。")
                    lastError = message
                    notice(String(localized: "默认配置更新失败"), message, isError: true)
                }
            }
        case .modelSelected(let sessionID, let selection):
            if let selection {
                let targetSessionID = sessionID
                    ?? kmpSessionControlStore.snapshot.activeRequestTargets["select-model"]?.sessionId
                let transition = dispatchSessionControlAction(.modelSelected(sessionID: sessionID, selection: selection))
                cancelCompletedSessionControlTracker("select-model", transition: transition)
                if transition.completed("select-model"), let targetSessionID {
                    notice(
                        String(localized: "模型已切换"),
                        [selection.model, selection.reasoningEffort].compactMap { $0 }.joined(separator: " · "),
                        sessionId: targetSessionID
                    )
                }
            } else {
                if failSessionControlRequest("select-model", responseSessionID: sessionID) {
                    reportNegativeSessionControlResponse("select-model", sessionID: sessionID)
                }
            }
        case .permissionOptions(let sessionID, let permissions):
            if let permissions {
                let transition = dispatchSessionControlAction(.permissionsReceived(sessionID: sessionID, permissions: permissions))
                cancelCompletedSessionControlTracker("permission-options", transition: transition)
            } else {
                if failSessionControlRequest("permission-options", responseSessionID: sessionID) {
                    reportNegativeSessionControlResponse("permission-options", sessionID: sessionID)
                }
            }
        case .permissionSelected(let sessionID, let value):
            if let value {
                let targetSessionID = sessionID
                    ?? kmpSessionControlStore.snapshot.activeRequestTargets["permission"]?.sessionId
                let transition = dispatchSessionControlAction(.permissionSelected(sessionID: sessionID, value: value))
                cancelCompletedSessionControlTracker("permission", transition: transition)
                if transition.completed("permission"), let targetSessionID {
                    notice(String(localized: "权限已切换"), value, sessionId: targetSessionID)
                }
            } else {
                if failSessionControlRequest("permission", responseSessionID: sessionID) {
                    reportNegativeSessionControlResponse("permission", sessionID: sessionID)
                }
            }
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

    private func notifyPendingAgentRequests() {
        // Question 的界面投影异步发布；使用 Store 当前快照，避免切后台时漏掉刚到达的请求。
        let questions = kmpQuestionStore.snapshot
        for request in questions.pendingRequests {
            if questions.platformStatuses[request.rpcId]?.isBusy == true { continue }
            agentNotifications.notifyQuestionRequired(
                gatewayID: gatewayLocalID,
                requestID: request.rpcId,
                sessionID: request.sessionId,
                sessionTitle: title(for: request.sessionId),
                prompt: request.questions.first?.question
            )
        }
        for request in pendingApprovalRequests {
            if approvalRequestStatuses[request.rpcId]?.isBusy == true { continue }
            agentNotifications.notifyApprovalRequired(
                gatewayID: gatewayLocalID,
                requestID: request.rpcId,
                sessionID: request.sessionId,
                sessionTitle: title(for: request.sessionId),
                detail: request.localizedReason ?? request.toolName
            )
        }
    }

    private func handleQuestionRoute(_ route: GatewayQuestionRoute) {
        switch route {
        case .requested(let request, let sessionID, let preview, let replay):
            dispatchQuestionIntent(.requestReceived(request))
            agentNotifications.notifyQuestionRequired(
                gatewayID: gatewayLocalID,
                requestID: request.rpcId,
                sessionID: request.sessionId,
                sessionTitle: title(for: request.sessionId),
                prompt: preview
            )
            AgentLiveActivityManager.shared.awaitingChoice(
                gatewayID: gatewayLocalID,
                request: request,
                title: title(for: request.sessionId),
                prompt: preview,
                sourceLabel: liveActivitySourceLabel(for: request.sessionId)
            )
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
        case .response(let rpcID, let action, let accepted, let reason, let wasNotPending):
            if wasNotPending,
               let request = pendingQuestionRequests.first(where: { $0.rpcId == rpcID }) {
                notice(
                    String(localized: "问题已在其他端处理"),
                    String(localized: "当前响应未生效。"),
                    sessionId: request.sessionId
                )
            }
            dispatchQuestionIntent(.responseReceived(
                rpcID: rpcID,
                action: action,
                accepted: accepted,
                reason: reason
            ))
            if action == .answer {
                if accepted {
                    backgroundExecutionController.questionAnswerAccepted(rpcID: rpcID)
                } else {
                    backgroundExecutionController.releaseQuestionAnswer(rpcID: rpcID)
                }
            }
        case .resolved(let rpcID, let sessionID, let cancelled):
            let pendingSessionID = pendingQuestionRequests.first { $0.rpcId == rpcID }?.sessionId
            let wasAnswerInFlight = questionRequestStatuses[rpcID]?.isAnswerInFlight == true
            dispatchQuestionIntent(.resolved(rpcID: rpcID))
            if let resolvedSessionID = sessionID ?? pendingSessionID {
                AgentLiveActivityManager.shared.choiceResolved(
                    gatewayID: gatewayLocalID,
                    sessionID: resolvedSessionID,
                    rpcID: rpcID,
                    cancelled: cancelled,
                    title: title(for: resolvedSessionID),
                    sourceLabel: liveActivitySourceLabel(for: resolvedSessionID)
                )
            }
            if wasAnswerInFlight && !cancelled {
                backgroundExecutionController.questionAnswerAccepted(rpcID: rpcID)
            } else {
                backgroundExecutionController.releaseQuestionAnswer(rpcID: rpcID)
            }
            let outcome = cancelled ? String(localized: "已跳过") : String(localized: "已提交")
            notice(
                String(localized: "q.outcome.title", defaultValue: "Agent 问题\(outcome)"),
                String(localized: "等待 Agent 继续执行。"),
                sessionId: sessionID ?? pendingSessionID
            )
        }
    }

    private func handleApprovalRoute(_ route: GatewayApprovalRoute) {
        switch route {
        case .requested(let request):
            let isNewRequest = !pendingApprovalRequests.contains { $0.rpcId == request.rpcId }
            gatewayApprovalTrace(
                "router requested replay=\(request.replay) isNew=\(isNewRequest) " +
                "selectedMatch=\(selectedSessionId == request.sessionId) pendingBefore=\(pendingApprovalRequests.count)"
            )
            dispatchApprovalIntent(.requestReceived(request))
            AgentLiveActivityManager.shared.awaitingApproval(
                gatewayID: gatewayLocalID,
                request: request,
                title: title(for: request.sessionId),
                command: commandPreview(for: request),
                sourceLabel: liveActivitySourceLabel(for: request.sessionId)
            )
            agentNotifications.notifyApprovalRequired(
                gatewayID: gatewayLocalID,
                requestID: request.rpcId,
                sessionID: request.sessionId,
                sessionTitle: title(for: request.sessionId),
                detail: request.localizedReason ?? request.toolName
            )
            if isNewRequest {
                notice(
                    request.replay ? String(localized: "待审批操作已恢复") : String(localized: "Agent 正在等待审批"),
                    request.localizedReason ?? request.toolName,
                    sessionId: request.sessionId
                )
            }
        case .invalidRequest(let sessionID):
            gatewayApprovalTrace("router invalid-request hasSession=\(sessionID?.isEmpty == false)")
            notice(
                String(localized: "审批请求无效"),
                String(localized: "缺少 rpcId、sessionId、approvalId 或 toolName。"),
                sessionId: sessionID,
                isError: true
            )
        case .response(let rpcID, let outcome, let accepted, let reason):
            gatewayApprovalTrace("router response accepted=\(accepted) outcome=\(outcome.rawValue)")
            let pendingRequest = pendingApprovalRequests.first { $0.rpcId == rpcID }
            let pendingSessionID = pendingRequest?.sessionId
            dispatchApprovalIntent(.responseReceived(
                rpcID: rpcID,
                outcome: outcome,
                accepted: accepted,
                reason: reason
            ))
            if let pendingRequest {
                if accepted {
                    AgentLiveActivityManager.shared.approvalResolved(
                        gatewayID: gatewayLocalID,
                        sessionID: pendingRequest.sessionId,
                        rpcID: rpcID,
                        outcome: outcome,
                        title: title(for: pendingRequest.sessionId),
                        sourceLabel: liveActivitySourceLabel(for: pendingRequest.sessionId)
                    )
                } else {
                    AgentLiveActivityManager.shared.approvalFailed(
                        gatewayID: gatewayLocalID,
                        sessionID: pendingRequest.sessionId,
                        rpcID: rpcID,
                        title: title(for: pendingRequest.sessionId),
                        reason: reason,
                        sourceLabel: liveActivitySourceLabel(for: pendingRequest.sessionId)
                    )
                }
            }
            if !accepted && reason == "not-pending" {
                notice(
                    String(localized: "审批已在其他端处理"),
                    String(localized: "当前决定未生效。"),
                    sessionId: pendingSessionID
                )
            }
        case .resolved(let rpcID, let sessionID, let outcome):
            gatewayApprovalTrace("router resolved outcome=\(outcome ?? "none")")
            let pendingRequest = pendingApprovalRequests.first { $0.rpcId == rpcID }
            let pendingSessionID = pendingRequest?.sessionId
            dispatchApprovalIntent(.resolved(rpcID: rpcID))
            if let resolvedSessionID = sessionID ?? pendingSessionID {
                if let resolvedOutcome = outcome.flatMap(GatewayApprovalOutcome.init(rawValue:)) {
                    AgentLiveActivityManager.shared.approvalResolved(
                        gatewayID: gatewayLocalID,
                        sessionID: resolvedSessionID,
                        rpcID: rpcID,
                        outcome: resolvedOutcome,
                        title: title(for: resolvedSessionID),
                        sourceLabel: liveActivitySourceLabel(for: resolvedSessionID)
                    )
                } else {
                    AgentLiveActivityManager.shared.approvalFailed(
                        gatewayID: gatewayLocalID,
                        sessionID: resolvedSessionID,
                        rpcID: rpcID,
                        title: title(for: resolvedSessionID),
                        reason: nil,
                        sourceLabel: liveActivitySourceLabel(for: resolvedSessionID)
                    )
                }
            }
            let detail = outcome == GatewayApprovalOutcome.allowedOnce.rawValue
                ? String(localized: "已允许本次操作。")
                : String(localized: "操作未获允许。")
            notice(String(localized: "审批已处理"), detail, sessionId: sessionID ?? pendingSessionID)
        }
    }

    private func recordHistoryFailure(_ message: String, sessionID: String) {
        historyLoadErrors[sessionID] = message
        conversationPreparingSessionIDs.remove(sessionID)
        if pendingSnapshotSessionID == sessionID { pendingSnapshotSessionID = nil }
        finishHistoryLoading(sessionID)
    }

    private func handleFailure(_ payload: GatewayFailurePayload) {
        waitingForNewSession = false
        let onlyCancellingSessionID = cancellingSessionIDs.count == 1 ? cancellingSessionIDs.first : nil
        if payload.requestType == "session-cancel", let sessionID = payload.sessionID ?? onlyCancellingSessionID {
            cancellingSessionIDs.remove(sessionID)
        }
        if payload.requestType == "directories" { directoryIsLoading = false }
        if payload.requestType == "directory-create" {
            directoryCreationIsLoading = false
            pendingDirectoryCreationParentPath = nil
        }
        if payload.requestType == "workspace-create" { workspaceCreationIsLoading = false }
        var correlatedControlFailure: Bool?
        if let requestType = payload.requestType,
           Self.defaultConfigurationRequestKinds.contains(requestType) {
            correlatedControlFailure = failDefaultConfigurationRequest(requestType)
        }
        if ["history", "subscribe"].contains(payload.requestType ?? ""),
           let sessionID = payload.sessionID ?? pendingSnapshotSessionID ?? kmpHistoryStore.pendingSessionID {
            recordHistoryFailure([payload.code, payload.message].compactMap { $0 }.joined(separator: ": "), sessionID: sessionID)
        }
        let detail = [payload.code, payload.message].compactMap { $0 }.joined(separator: ": ")
        if let requestType = payload.requestType,
           ["question-answer", "question-cancel"].contains(requestType) {
            if let rpcID = payload.rpcID {
                dispatchQuestionIntent(.requestFailed(rpcID: rpcID, message: detail))
                backgroundExecutionController.releaseQuestionAnswer(rpcID: rpcID)
            } else if let sessionID = payload.sessionID {
                // 旧网关可能省略 rpcId；此时按 session 原子失败全部相关请求，
                // 不能从多个 pending request 中猜测任意一个 rpcId。
                dispatchQuestionIntent(.sessionRequestsFailed(sessionID: sessionID, message: detail))
                backgroundExecutionController.releaseQuestionAnswers(sessionID: sessionID)
            }
        }
        if payload.requestType == "approval-response" {
            if let rpcID = payload.rpcID {
                dispatchApprovalIntent(.requestFailed(rpcID: rpcID, message: detail))
            } else if let sessionID = payload.sessionID {
                dispatchApprovalIntent(.sessionRequestsFailed(sessionID: sessionID, message: detail))
            }
        }
        let failedRequest = payload.requestType.flatMap { requestType in
            Self.defaultConfigurationRequestKinds.contains(requestType) ? nil
                : sessionControlKind(from: requestType)
        } ?? payload.code.flatMap(sessionControlKind(from:))
            ?? payload.message.flatMap(sessionControlKind(from:))
        if let failedRequest {
            correlatedControlFailure = failSessionControlRequest(
                failedRequest,
                responseSessionID: payload.sessionID
            )
        }
        if payload.requestType == nil, failedRequest == nil {
            let outstanding = Array(sessionControlLoadingKinds)
            if !outstanding.isEmpty {
                correlatedControlFailure = outstanding.reduce(false) { applied, kind in
                    failSessionControlRequest(kind, responseSessionID: payload.sessionID) || applied
                }
            }
        }
        // 已识别为 SessionControl 的迟到/无关联 error 不属于当前 generation，零 UI 提示。
        guard correlatedControlFailure != false else { return }
        if payload.requestType == "agent-presets" {
            agentPresetsLoadError = String(localized: "Agent 预设加载失败，请重试。")
            notice("Agent 预设诊断", "网关返回错误 code=\(payload.code ?? "unknown")", isError: true)
            return
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
        if sessions.first(where: { $0.id == sessionID })?.isRunning != true,
           turnTiming.snapshot(sessionId: sessionID) == nil {
            turnTiming.awaitStart(sessionId: sessionID, afterSequence: Int32(events[sessionID]?.last?.seq ?? -1))
        }
        backgroundExecutionController.messageAccepted(sessionID: sessionID)
        waitingForNewSession = false
        dispatchSessionListIntent(.messageSent(sessionID: sessionID, agentPreset: agentPresetDefault))
        notice(
            command == nil ? String(localized: "消息已发送") : String(localized: "命令已执行"),
            command?.displayText
                ?? String(localized: "session.preview.id", defaultValue: "\(sessionID.prefix(12))…"),
            sessionId: sessionID
        )
        if selectedSessionId == sessionID && !assistantStreamState.hasBaseline(sessionId: sessionID) {
            subscribeToSession(sessionID)
        }
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
        do {
            try kmpHistoryStore.processingStarted(
                sessionID: id,
                rawEventCount: rawEvents.count,
                hasMore: payload.hasMore
            )
        } catch {
            let primaryError = error.localizedDescription
            lastError = primaryError
            historySyncEngine.finish(sessionID: id)
            do { try kmpHistoryStore.cancel(sessionID: id) }
            catch { lastError = "\(primaryError)\n\(error.localizedDescription)" }
            return
        }

        Task { [weak self] in
            let prepared = await Task.detached(priority: .userInitiated) { () -> Result<KMPPreparedHistoryPage, Error> in
                Result {
                    let normalized = rawEvents.map { $0.normalized(sessionId: id) }
                    return try KMPHistoryStoreAdapter.preparePage(sessionID: id, events: normalized)
                }
            }.value
            guard let self, self.historySyncEngine.isCurrent(processingToken, sessionID: id) else { return }
            do {
                let page = try prepared.get()
                try self.kmpHistoryStore.pageReceived(
                    sessionID: id,
                    preparedPage: page,
                    byteCount: payload.byteCount,
                    hasMore: payload.hasMore,
                    nextBeforeSequence: payload.nextBeforeSequence,
                    remoteActivityTimestamp: self.sessions.first(where: { $0.id == id })?.lastActivity.timeIntervalSince1970
                )
                self.drainKMPEventDeliveries()
                // 使用合并后的历史，避免旧分页中的 end 误停较新的实时 turn/start。
                if self.assistantStreamState.activeAttemptId() == nil {
                    self.dispatchSessionListIntent(.historyReceived(sessionID: id, events: self.events[id] ?? [],
                        snapshot: self.kmpHistoryStore.projectionSnapshot(for: id)))
                }
            } catch {
                let primaryError = error.localizedDescription
                self.lastError = primaryError
                self.historySyncEngine.finish(sessionID: id)
                do { try self.kmpHistoryStore.cancel(sessionID: id) }
                catch { self.lastError = "\(primaryError)\n\(error.localizedDescription)" }
            }
        }
    }
    private func finishHistoryLoading(_ sessionId: String) {
        historySyncEngine.finish(sessionID: sessionId)
        do { try kmpHistoryStore.cancel(sessionID: sessionId) }
        catch { lastError = error.localizedDescription }
    }
    private func applyHistoryProjections(_ projections: JSONValue?, sessionId: String) {
        guard let values = projections?["values"] else { return }
        dispatchSessionControlProjection(.contextReceived(
            sessionID: sessionId,
            asOfSequence: projections?["asOfSeq"]?.doubleValue.map(Int.init),
            tokenUsage: values["tokenUsage"]?.decode(GatewayTokenUsage.self),
            pressure: values["contextPressure"]?.decode(GatewayContextPressure.self),
            breakdown: values["contextBreakdown"]?.decode(GatewayContextBreakdown.self)
        ))
        if let permissions = values["permissions"]?.decode(GatewaySessionPermissions.self) {
            dispatchSessionControlProjection(.permissionsReceived(sessionID: sessionId, permissions: permissions))
        }
    }
    private func merge(_ record: SessionEvent) {
        if record.event.type == "turn/end" {
            cancellingSessionIDs.remove(record.sessionId)
        }
        do { try kmpHistoryStore.receive(record) }
        catch {
            lastError = error.localizedDescription
            return
        }
        applyEvent(record)
    }

    private func enqueueImageAttachments(in records: [SessionEvent], sessionId: String) {
        enqueueImageAttachments(records.flatMap { $0.event.images ?? [] }, sessionId: sessionId)
    }

    private func enqueueImageAttachments(_ attachments: [GatewayImageAttachment], sessionId: String) {
        guard supportsImages else { return }
        let cache = imageAttachmentCache
        Task { [weak self] in
            for attachment in attachments { _ = await cache.loadData(for: attachment.id) }
            guard let self, !self.evictedConversationSessionIDs.contains(sessionId) else { return }
            if let items = self.renderedConversationItems[sessionId] { self.conversationTimeline(for: sessionId).publish(items) }
            let requests = self.attachmentLoader.enqueue(attachments, sessionID: sessionId) {
                cache.data(for: $0) != nil
            }
            self.requestImageAttachments(requests)
        }
    }

    private func requestImageAttachments(_ requests: [AttachmentLoadRequest]) {
        for request in requests {
            imageAttachmentRequestEpochs[request.attachment.id] = imageAttachmentCache.epoch
            gateway.requestAttachment(
                sessionId: request.sessionID,
                attachmentId: request.attachment.id
            )
        }
    }

    private func handleImageAttachment(_ payload: GatewayAttachmentPayload) {
        let attachment = payload.attachment
        let requestEpoch = imageAttachmentRequestEpochs.removeValue(forKey: attachment.id)
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
        guard let requestEpoch else { return }
        imageAttachmentCache.store(decoded, for: attachment.id, expectedEpoch: requestEpoch)
        if isShowingSearchContext, let timeline = searchContextTimeline { timeline.publish(timeline.currentSnapshot.items) }
        guard let sessionId = payload.sessionID,
              let items = renderedConversationItems[sessionId] else { return }
        // The row revision also includes cache presence, so this publication
        // replaces only rows whose attachment just became renderable.
        conversationTimeline(for: sessionId).publish(items)
    }

    private func scheduleConversationProjection(for sessionId: String) {
        guard !evictedConversationSessionIDs.contains(sessionId) else { return }
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

    private func canPrepareStaticHistory(_ change: KMPHistoryChange) -> Bool {
        change.eventPatchKind == "replace" && change.eventRecord == nil
            && sessions.first(where: { $0.id == change.sessionID })?.isRunning == false
            && assistantStreamState.activeAttemptId() == nil
            && change.events.last?.event.type != "assistant/chunk"
            && !activeTrajectorySessionIDs.contains(change.sessionID)
    }

    private func enqueueStaticConversationHistory(_ change: KMPHistoryChange) -> Bool {
        guard canPrepareStaticHistory(change), let snapshot = change.projectionSnapshot,
              let input = kmpConversationStore.historyPreparation(sessionID: change.sessionID, snapshot: snapshot) else { return false }
        let id = change.sessionID
        pendingConversationHistory[id] = ConversationHistoryRequest(records: change.events, snapshot: snapshot,
            input: input, epoch: conversationProjectionEpochs[id, default: 0])
        guard conversationHistoryWorkerIDs[id] == nil else { return true }
        let operationID = UUID()
        conversationHistoryWorkerIDs[id] = operationID
        Task { @MainActor [weak self] in
            guard let self else { return }
            defer {
                if self.conversationHistoryWorkerIDs[id] == operationID {
                    self.conversationHistoryWorkerIDs[id] = nil
                    self.activeConversationHistory[id] = nil
                }
            }
            while let request = self.pendingConversationHistory.removeValue(forKey: id) {
                self.activeConversationHistory[id] = request
                let started = DispatchTime.now().uptimeNanoseconds
                appLog("conversation-history-prepare-start events=%ld", request.records.count)
                let input = request.input
#if DEBUG
                let testPreparation = self.conversationHistoryPreparationForTesting
#endif
                let prepared = await Task.detached(priority: .userInitiated) { () -> Result<KMPPreparedConversationHistory, Error> in
                    do {
#if DEBUG
                        if let testPreparation { return Result.success(try await testPreparation(input)) }
#endif
                        return Result.success(try KMPConversationStoreAdapter.prepareHistory(input))
                    } catch { return Result<KMPPreparedConversationHistory, Error>.failure(error) }
                }.value
                self.activeConversationHistory[id] = nil
                guard !self.evictedConversationSessionIDs.contains(id),
                      self.conversationProjectionEpochs[id, default: 0] == request.epoch,
                      self.kmpHistoryStore.projectionSnapshot(for: id)?.eventSequence == request.snapshot.eventSequence else {
                    if !self.reuseCoveredConversationHistory(prepared, request: request, sessionID: id) {
                        appLog("conversation-history-prepare-discard")
                    }
                    continue
                }
                do {
                    if self.sessions.first(where: { $0.id == id })?.isRunning != false ||
                        self.assistantStreamState.activeAttemptId() != nil {
                        appLog("conversation-history-prepare-fallback reason=became-active")
                        try self.installConversationHistorySynchronously(request, sessionID: id)
                        self.scheduleConversationProjection(for: id)
                        continue
                    }
                    let result = try prepared.get()
                    let committed = try self.kmpConversationStore.commitPreparedHistory(result, sessionID: id,
                        lastSequence: request.records.last?.seq ?? -1)
                    if !committed {
                        appLog("conversation-history-prepare-fallback reason=revision")
                        try self.installConversationHistorySynchronously(request, sessionID: id)
                    } else {
                        self.historyPresentationCommittedEpochs[id] = self.historyPresentationRequiredEpochs[id, default: 0]
                        appLog("conversation-history-prepare-commit elapsed_ms=%.2f",
                            Double(DispatchTime.now().uptimeNanoseconds - started) / 1_000_000)
                        try self.replayConversationTransientChunks(sessionID: id)
                    }
                    self.scheduleConversationProjection(for: id)
                } catch {
                    // 纯准备失败也不能遗失 pending 历史或一直显示 loading。
                    do {
                        appLog("conversation-history-prepare-fallback reason=prepare-error")
                        try self.installConversationHistorySynchronously(request, sessionID: id)
                        self.scheduleConversationProjection(for: id)
                    } catch {
                        self.conversationPreparingSessionIDs.remove(id)
                        self.lastError = error.localizedDescription
                        self.historyPresentationAdmission.cancel(sessionID: id, outcome: .failed)
                    }
                }
            }
        }
        return true
    }

    /// 新权威窗口完整保留旧输入时，已算好的缓存仍可作为展示基线；修订和删除必须丢弃旧 worker。
    private func reuseCoveredConversationHistory(_ prepared: Result<KMPPreparedConversationHistory, Error>,
        request: ConversationHistoryRequest, sessionID: String) -> Bool {
        guard !evictedConversationSessionIDs.contains(sessionID),
              sessions.first(where: { $0.id == sessionID })?.isRunning == false,
              assistantStreamState.activeAttemptId() == nil,
              !activeTrajectorySessionIDs.contains(sessionID),
              let next = pendingConversationHistory[sessionID],
              next.epoch == conversationProjectionEpochs[sessionID, default: 0],
              next.snapshot.eventSequence == kmpHistoryStore.projectionSnapshot(for: sessionID)?.eventSequence,
              let first = request.records.first,
              let start = next.records.firstIndex(where: { $0.seq == first.seq }),
              start + request.records.count <= next.records.count,
              next.records[start..<(start + request.records.count)].elementsEqual(request.records) else { return false }
        do {
            guard try kmpConversationStore.commitPreparedHistory(prepared.get(), sessionID: sessionID,
                lastSequence: request.records.last?.seq ?? -1) else { return false }
            if try kmpConversationStore.appendHistory(sessionID: sessionID, snapshot: next.snapshot,
                lastSequence: next.records.last?.seq ?? -1) {
                pendingConversationHistory[sessionID] = nil
                historyPresentationCommittedEpochs[sessionID] = historyPresentationRequiredEpochs[sessionID, default: 0]
                try replayConversationTransientChunks(sessionID: sessionID)
            } else if let input = kmpConversationStore.historyPreparation(sessionID: sessionID, snapshot: next.snapshot) {
                // 差量基于刚提交的缓存行和新 revision，不能沿用排队时的空投影。
                pendingConversationHistory[sessionID] = ConversationHistoryRequest(records: next.records,
                    snapshot: next.snapshot, input: input, epoch: next.epoch)
            }
            // 中间缓存不推进最终呈现 epoch；只有最新窗口完成后才确认分页呈现。
            scheduleConversationProjection(for: sessionID)
#if DEBUG
            conversationHistoryReusedPreparationCountForTesting += 1
#endif
            appLog("conversation-history-prepare-reuse cached_events=%ld latest_events=%ld", request.records.count, next.records.count)
            return true
        } catch { return false }
    }

    private func replayConversationTransientChunks(sessionID: String) throws {
        if assistantStreamState.hasBaseline(sessionId: sessionID), let attemptID = assistantStreamState.activeAttemptId() {
            try kmpConversationStore.assistantChunks(sessionID: sessionID, attemptID: attemptID,
                chunksJSON: assistantStreamState.replayChunksJson())
        }
    }

    private func installConversationHistorySynchronously(_ request: ConversationHistoryRequest, sessionID: String,
        replayTransient: Bool = true) throws {
        try kmpConversationStore.replace(sessionID: sessionID, events: request.records, historySnapshot: request.snapshot)
        historyPresentationCommittedEpochs[sessionID] = historyPresentationRequiredEpochs[sessionID, default: 0]
        if replayTransient { try replayConversationTransientChunks(sessionID: sessionID) }
    }

    /// 先补齐静态历史，再按原入口处理 live/transient；持续 token 不会饿死历史提交。
    private func flushPendingConversationHistory(sessionID: String, replayTransient: Bool = true) throws {
        guard let request = pendingConversationHistory.removeValue(forKey: sessionID) ?? activeConversationHistory[sessionID] else { return }
        activeConversationHistory[sessionID] = nil
        conversationProjectionEpochs[sessionID, default: 0] &+= 1
        guard !evictedConversationSessionIDs.contains(sessionID) else { return }
        appLog("conversation-history-prepare-fallback reason=live events=%ld", request.records.count)
        try installConversationHistorySynchronously(request, sessionID: sessionID, replayTransient: replayTransient)
        scheduleConversationProjection(for: sessionID)
    }

    private func discardPendingConversationHistory(sessionID: String, cancelPresentation: Bool = true) {
        if cancelPresentation { historyPresentationAdmission.cancel(sessionID: sessionID) }
        pendingConversationHistory[sessionID] = nil
        activeConversationHistory[sessionID] = nil
        conversationProjectionEpochs[sessionID, default: 0] &+= 1
    }
    private func scheduleTrajectoryProjection(for sessionId: String) {
        let driver: ConversationProjectionDriver
        if let existing = trajectoryProjectionDrivers[sessionId] {
            driver = existing
        } else {
            driver = ConversationProjectionDriver(preferredFramesPerSecond: 10) { [weak self] in
                self?.flushTrajectoryProjection(for: sessionId)
            }
            trajectoryProjectionDrivers[sessionId] = driver
        }
        driver.invalidate()
    }
    private func flushTrajectoryProjection(for sessionId: String) {
        guard activeTrajectorySessionIDs.contains(sessionId) else {
            pendingTrajectoryEvents[sessionId] = nil
            return
        }
        defer { publishTrajectory(sessionID: sessionId) }
        let records = pendingTrajectoryEvents.removeValue(forKey: sessionId) ?? []
        guard !records.isEmpty else { return }
        trajectoryBatchSessionID = sessionId
        defer { trajectoryBatchSessionID = nil }
        do {
            try kmpTrajectoryStore.receive(records)
            trajectoryProjectedVersions[sessionId] = trajectoryHistoryVersion(for: sessionId)
        } catch {
            lastError = error.localizedDescription
        }
    }
    /// KMP 已同步应用所有 token patch；display link 只负责把最新镜像按一帧最多
    /// 一次发布给 UIKit timeline，避免高频 token 让 SwiftUI 根视图失效。
    private func projectIncrementally(for sessionId: String) async {
        guard !Task.isCancelled else { return }
        let items = kmpConversationStore.items(for: sessionId)
        if let batch = historyReadingBatches[sessionId] {
            guard batch.responseReady, !kmpHistoryStore.isLoading(sessionID: sessionId),
                  pendingConversationHistory[sessionId] == nil, activeConversationHistory[sessionId] == nil else { return }
            let epoch = conversationProjectionEpochs[sessionId, default: 0]
            let window = await Task.detached(priority: .userInitiated) { batch.window(in: items) }.value
            guard !Task.isCancelled, historyReadingBatches[sessionId] == batch,
                  !kmpHistoryStore.isLoading(sessionID: sessionId),
                  conversationProjectionEpochs[sessionId, default: 0] == epoch,
                  historyPresentationAdmission.matches(sessionID: sessionId, operationID: batch.operationID) else { return }
            let earliest = events[sessionId]?.first?.seq
            let reason = batch.stopReason(window: window, earliestSequence: earliest,
                hasMore: selectedHistoryHasMore, now: ProcessInfo.processInfo.systemUptime)
            if reason == nil, sessionId == selectedSessionId, selectedSession?.isRunning != true {
                var next = batch
                next.beforeSequence = earliest ?? batch.beforeSequence
                next.minimumEventSequence = (kmpHistoryStore.lastEventSequence ?? 0) + 1
                next.responseReady = false
                historyReadingBatches[sessionId] = next
                appLog("history-read-batch stage=continue rounds=%ld messages=%ld height=%.1f", batch.rounds, window.messages, window.height)
                loadHistory(for: sessionId, older: true, presentationOperationID: batch.operationID)
                return
            }
            historyReadingBatches[sessionId] = nil
            appLog("history-read-batch stage=ready rounds=%ld messages=%ld height=%.1f reason=%@", batch.rounds,
                window.messages, window.height, reason ?? "active")
            publishConversationItems(items, for: sessionId)
            historyPresentationAdmission.terminal(sessionID: sessionId,
                domainLoading: kmpHistoryStore.isLoading(sessionID: sessionId), failure: batch.failed ? .failed : nil,
                operationID: batch.operationID)
            return
        }
        publishConversationItems(items, for: sessionId)
    }
    private func publishConversationItems(_ items: [ConversationItem], for sessionId: String) {
        guard !evictedConversationSessionIDs.contains(sessionId) else { return }
        let previouslyHadContent = conversationContentSessionIds.contains(sessionId)
        let isNewRetainedSession = renderedConversationItems[sessionId] == nil
        renderedConversationItems[sessionId] = items
        if isNewRetainedSession || renderedConversationItems.count > 6 ||
            sessionId != selectedSessionId && events[sessionId]?.last?.event.type != "assistant/chunk" {
            scheduleInactiveConversationMemoryTrim()
        }
        conversationTimeline(for: sessionId).publish(items, historyPresentationStamp: historyPresentationAdmission.stamp(
            sessionID: sessionId, committedEpoch: historyPresentationCommittedEpochs[sessionId, default: 0]))
        if let request = pendingApprovalRequests.last(where: { $0.sessionId == sessionId }) {
            AgentLiveActivityManager.shared.enrichApprovalCommand(
                gatewayID: gatewayLocalID,
                sessionID: sessionId,
                rpcID: request.rpcId,
                command: commandPreview(for: request)
            )
        }
        if items.isEmpty {
            if previouslyHadContent { conversationContentSessionIds.remove(sessionId) }
        } else if !previouslyHadContent {
            conversationContentSessionIds.insert(sessionId)
        }
        if conversationPreparingSessionIDs.contains(sessionId) {
            conversationPreparingSessionIDs.remove(sessionId)
        }
    }
    /// 只有 UI 投影发布完成后，空列表才可以被解释为真正的空会话。
    func isPreparingConversation(_ sessionID: String) -> Bool {
        if isShowingSearchContext, sessionID == selectedSessionId { return false }
        return !conversationContentSessionIds.contains(sessionID) && (
            historyLoadingSessionIds.contains(sessionID)
                || conversationPreparingSessionIDs.contains(sessionID)
        )
    }

    private func applyEvent(_ record: SessionEvent) {
        let event = record.event
        turnTiming.receiveLiveEvent(
            sessionId: record.sessionId, sequence: Int32(record.seq), epochSeconds: record.time,
            eventType: event.type, nowMonotonicSeconds: AgentTurnMonotonicClock.now
        )
        if event.type == "turn/start" {
            cancelLiveActivityStreamUpdate(sessionID: record.sessionId)
            liveActivityToolLabels[record.sessionId] = [:]
            backgroundExecutionController.begin(sessionID: record.sessionId, startsNewTurn: false)
            AgentLiveActivityManager.shared.sessionStarted(
                gatewayID: gatewayLocalID,
                sessionID: record.sessionId,
                title: title(for: record.sessionId),
                turn: event.turn,
                sourceLabel: liveActivitySourceLabel(for: record.sessionId)
            )
        }
        if event.type == "assistant/chunk", let chunkType = event.chunkType {
            enqueueLiveActivityStreamChunk(
                sessionID: record.sessionId,
                attemptID: "legacy-\(event.turn ?? 0)-\(event.step ?? 0)",
                chunkType: chunkType,
                text: event.text ?? event.tool?.argumentsDelta,
                toolName: event.tool?.name,
                step: event.step
            )
        } else if event.type != "turn/start" && event.type != "turn/end" {
            cancelLiveActivityStreamUpdate(sessionID: record.sessionId)
            let callID = event.callId ?? event.subCallId
            let completedToolLabel = callID.flatMap { liveActivityToolLabels[record.sessionId]?[$0] }
            if let progress = AgentLiveActivityEventProjection.progress(
                for: event,
                completedToolLabel: completedToolLabel
            ) {
                publishLiveActivityProgress(progress, sessionID: record.sessionId, step: event.step)
                if ["tool/call", "tool/code-dispatch-start"].contains(event.type), let callID {
                    liveActivityToolLabels[record.sessionId, default: [:]][callID] = progress.headline
                }
            }
            if ["tool/result", "tool/code-dispatch"].contains(event.type), let callID {
                liveActivityToolLabels[record.sessionId]?[callID] = nil
            }
        }
        if event.type == "turn/end" {
            cancelLiveActivityStreamUpdate(sessionID: record.sessionId)
            liveActivityToolLabels[record.sessionId] = nil
            backgroundExecutionController.turnEnded(sessionID: record.sessionId)
            let failureReasons = Set(["error", "failed", "cancelled", "canceled", "interrupted", "aborted"])
            let failed = event.isError == true
                || event.interrupted == true
                || event.error?.isEmpty == false
                || event.reason.map { failureReasons.contains($0.lowercased()) } == true
            AgentLiveActivityManager.shared.sessionEnded(
                gatewayID: gatewayLocalID,
                sessionID: record.sessionId,
                title: title(for: record.sessionId),
                failed: failed,
                sourceLabel: liveActivitySourceLabel(for: record.sessionId)
            )
            agentNotifications.notifyExecutionEnded(
                gatewayID: gatewayLocalID,
                eventID: record.id,
                sessionID: record.sessionId,
                sessionTitle: title(for: record.sessionId),
                failed: failed
            )
            if backgroundMonitoredSessionID == record.sessionId,
               !backgroundExecutionController.isAgentWorkActive(sessionID: record.sessionId) {
                backgroundMonitoredSessionID = nil
                subscribeToSession(nil)
            }
        }
        // turn/end 可能在应用切回前台或重连期间从投递队列中到达。
        // 此时只完成本地状态与通知更新；会话统计会在 hello 后由
        // refreshSessionControls 统一刷新，避免把短暂未连接显示成错误弹窗。
        if event.type == "turn/end",
           record.sessionId == selectedSessionId,
           gateway.state.isConnected {
            dispatchSessionControl(.requestContextUsage(
                sessionID: record.sessionId,
                isConnected: true
            ))
            dispatchSessionControl(.requestSessionStats(
                sessionID: record.sessionId,
                isConnected: true
            ))
        }
        if event.type == "permission/preset",
           let preset = event.raw?["preset"]?.stringValue ?? event.raw?["name"]?.stringValue {
            dispatchSessionControlProjection(.permissionSelected(sessionID: record.sessionId, value: preset))
        }
        if event.type == "request/header", let config = event.raw?["header"]?["config"] {
            let provider = config["provider"]?.stringValue
            let model = config["model"]?.stringValue
            if let provider, let model {
                dispatchSessionControlProjection(.modelSelected(
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
        dispatchSessionListIntent(.eventReceived(record))
    }

    private func enqueueLiveActivityStreamChunk(
        sessionID: String,
        attemptID: String,
        chunkType: String,
        text: String?,
        toolName: String?,
        step: Int?
    ) {
        guard AgentLiveActivityEventProjection.streamingProgress(
            chunkType: chunkType,
            text: text,
            toolName: toolName
        ) != nil else { return }

        var buffer = liveActivityStreamBuffers[sessionID]
        if buffer?.attemptID != attemptID || buffer?.chunkType != chunkType {
            liveActivityStreamTasks.removeValue(forKey: sessionID)?.cancel()
            buffer = AgentLiveActivityStreamBuffer(
                attemptID: attemptID,
                chunkType: chunkType,
                text: "",
                toolName: toolName,
                step: step
            )
        }
        guard var currentBuffer = buffer else { return }
        if let text, !text.isEmpty {
            currentBuffer.text = String((currentBuffer.text + text).suffix(240))
        }
        currentBuffer.toolName = toolName ?? currentBuffer.toolName
        currentBuffer.step = step ?? currentBuffer.step
        liveActivityStreamBuffers[sessionID] = currentBuffer

        guard liveActivityStreamTasks[sessionID] == nil else { return }
        liveActivityStreamTasks[sessionID] = Task { @MainActor [weak self] in
            do { try await Task.sleep(for: .milliseconds(650)) } catch { return }
            guard let self, let latest = self.liveActivityStreamBuffers[sessionID] else { return }
            self.liveActivityStreamTasks[sessionID] = nil
            guard let progress = AgentLiveActivityEventProjection.streamingProgress(
                chunkType: latest.chunkType,
                text: latest.text,
                toolName: latest.toolName
            ) else { return }
            self.publishLiveActivityProgress(progress, sessionID: sessionID, step: latest.step)
        }
    }

    private func cancelLiveActivityStreamUpdate(sessionID: String) {
        liveActivityStreamTasks.removeValue(forKey: sessionID)?.cancel()
        liveActivityStreamBuffers[sessionID] = nil
    }

    private func publishLiveActivityProgress(
        _ progress: AgentLiveActivityProgress,
        sessionID: String,
        step: Int?
    ) {
        let detail: String?
        if let step, let progressDetail = progress.detail, !progressDetail.hasPrefix("第 \(step) 步") {
            detail = "第 \(step) 步 · \(progressDetail)"
        } else if let step, progress.detail == nil {
            detail = "第 \(step) 步"
        } else {
            detail = progress.detail
        }
        if let request = pendingApprovalRequests.last(where: { $0.sessionId == sessionID }) {
            AgentLiveActivityManager.shared.enrichApprovalCommand(
                gatewayID: gatewayLocalID,
                sessionID: sessionID,
                rpcID: request.rpcId,
                command: commandPreview(for: request)
            )
        }
        AgentLiveActivityManager.shared.progressUpdated(
            gatewayID: gatewayLocalID,
            sessionID: sessionID,
            title: title(for: sessionID),
            headline: progress.headline,
            detail: detail,
            kind: progress.kind,
            toolName: progress.toolName,
            sourceLabel: liveActivitySourceLabel(for: sessionID)
        )
    }
    private func notice(_ title: String, _ text: String, sessionId: String? = nil, isError: Bool = false) {
        protocolNotices.append(GatewayNotice(sessionId: sessionId, title: title, text: text, isError: isError))
        if protocolNotices.count > 100 { protocolNotices.removeFirst(protocolNotices.count - 100) }
    }

    private func handleConnectionFailure(_ detail: String) {
        if conversationFileIsOpening, workspaceFilePreviewNeedsConnection {
            failWorkspaceFilePreview(detail)
        }
        applyWorkspaceFileTransition(kmpWorkspaceFileStore.reset(sessionId: selectedSessionId))
        if pluginToggleRequestID != nil {
            pluginNotice = pluginPendingName == "dsh-plugin-mobile-gateway"
                ? "网关连接已断开，停用结果待重连确认。若已停用，请从 DSH Web 或 CLI 重新开启。"
                : "连接已断开，操作结果待重连确认"
            pluginToggleRequestID = nil
            pluginPendingName = nil
        }
        messageSubmissionTimeout?.cancel()
        messageSubmissionPending = false
        queueActionTimeout?.cancel()
        if queueState.pendingItemId != nil { queueState = queueStore.failPending(message: "连接已中断，请重新连接后确认队列状态") }
        assistantStreamState.disconnect()
        resetRemoteSearchForOffline()
        // Keep the prepared destination, but require a fresh activation after
        // the transport reconnects and emits its next hello frame.
        activeConversationActivationKey = nil
        cancellingSessionIDs = []
        // A transport/authentication failure invalidates every outstanding
        // business request. Cancel their timeout tokens first so an unrelated
        // "agent-presets 请求超时" cannot replace the real WebSocket cause.
        resetOutstandingRequests()
        showOfflineHistoryBoundary()
        if presentsNextConnectionFailureAsAlert {
            lastError = detail
        }
        presentsNextConnectionFailureAsAlert = false
        backgroundExecutionController.cancel()
    }

    private func beginAgentBackgroundExecution(for sessionID: String?, startsNewTurn: Bool) {
        backgroundExecutionController.begin(sessionID: sessionID, startsNewTurn: startsNewTurn)
    }

    private func resetOutstandingRequests() {
        if messageSubmissionPending || commandSubmissionPending {
            // 服务端可能已经执行，只是确认留在旧连接；切换承载不能自动重放写操作。
            messageSubmissionTimeout?.cancel()
            messageSubmissionPending = false
            commandSubmissionPending = false
        }
        deferredSessionControlEffects.removeAll()
        fileUploads.disconnected()
        for (id, task) in teamMemberProfileTimeouts {
            task.cancel()
            teamStore.finishMemberProfileRequest(requestId: id)
        }
        teamMemberProfileTimeouts.removeAll()
        chatPrefetcher.stop(reason: "reset")
        sessionAgentPresetTimeout?.cancel()
        applySessionAgentPresetTransition(sessionAgentPresetStore.disconnected())
        cancelSnapshotWait()
        backgroundExecutionController.releaseAllQuestionAnswers()
        sessionControlRequestTracker.finishAll()
        defaultConfigurationRequestTracker.finishAll()
        submitSessionControlIntent(.requestsDisconnected)
        for id in Array(historyLoadingSessionIds) { finishHistoryLoading(id) }
        directoryIsLoading = false
        directoryCreationIsLoading = false
        pendingDirectoryCreationParentPath = nil
        workspaceCreationIsLoading = false
        isRefreshing = false
        waitingForNewSession = false
    }

    @discardableResult
    private func failSessionControlRequest(_ kind: String, responseSessionID: String? = nil) -> Bool {
        guard let token = kmpSessionControlStore.snapshot.requestTokens[kind],
              let active = kmpSessionControlStore.snapshot.activeRequestTargets[kind],
              responseSessionID == nil || responseSessionID == active.sessionId else { return false }
        let transition = submitSessionControlIntent(.requestFailed(
            kind: kind, isDefault: false, requestToken: token
        ))
        guard transition.applied else { return false }
        sessionControlRequestTracker.finish(kind)
        return true
    }

    private func setGlobalDefault(target: String, value: String) {
        guard gateway.state.isConnected else {
            lastError = String(localized: "请先连接 DeepSeek Harness")
            return
        }
        dispatchSessionControl(.setDefault(target: target, value: value, isConnected: true))
    }

    @discardableResult
    private func failDefaultConfigurationRequest(
        _ kind: String,
        responseTarget: String? = nil,
        responseValue: String? = nil
    ) -> Bool {
        guard let token = kmpSessionControlStore.snapshot.requestTokens[kind] else { return false }
        if responseTarget != nil || responseValue != nil {
            guard let active = kmpSessionControlStore.snapshot.activeRequestTargets[kind],
                  responseTarget == nil || responseTarget == active.target,
                  responseValue == nil || responseValue == active.value else { return false }
        }
        let transition = submitSessionControlIntent(.requestFailed(
            kind: kind, isDefault: true, requestToken: token
        ))
        guard transition.applied else { return false }
        defaultConfigurationRequestTracker.finish(kind)
        return true
    }

    private func reportNegativeSessionControlResponse(_ kind: String, sessionID: String?) {
        let message = String(
            localized: "control.request.rejected",
            defaultValue: "服务端未确认 \(kind) 请求。"
        )
        lastError = message
        notice(String(localized: "控制请求失败"), message, sessionId: sessionID, isError: true)
    }

    private func sessionControlKind(from value: String) -> String? {
        ["permission-options", "select-model", "context-usage", "session-stats", "permission", "models"]
            .first { value.localizedCaseInsensitiveContains($0) }
    }

    private func installTaskGoalBaseline(_ projection: JSONValue?, sessionID: String) {
        let sequence = projection?["asOfSeq"]?.doubleValue.map(Int.init)
        let values = projection?["values"]
        taskProjections[sessionID] = GatewayTasksProjection(asOfSequence: sequence,
            todos: values?["todos"]?.decode([GatewayTodoItem].self))
        goalProjections[sessionID] = GatewayGoalProjection(asOfSequence: sequence,
            goal: values?["goal"]?.decode(GatewayGoalPayload.self))
    }

    private func applyTasksProjection(_ frame: GatewayFrame) {
        guard let sessionID = frame.sessionId else { return }
        let candidate = GatewayTasksProjection(asOfSequence: frame.asOfSeq, todos: frame.todos)
        guard projection(candidate.asOfSequence, isNotOlderThan: taskProjections[sessionID]?.asOfSequence) else {
            return
        }
        taskProjections[sessionID] = candidate
    }

    private func applyGoalProjection(_ frame: GatewayFrame) {
        guard let sessionID = frame.sessionId else { return }
        let candidate = GatewayGoalProjection(asOfSequence: frame.asOfSeq, goal: frame.goal)
        guard projection(candidate.asOfSequence, isNotOlderThan: goalProjections[sessionID]?.asOfSequence) else {
            return
        }
        goalProjections[sessionID] = candidate
    }

    private func projection(_ candidate: Int?, isNotOlderThan current: Int?) -> Bool {
        guard let candidate, let current else { return true }
        return candidate >= current
    }
    private func markRead(_ id: String) {
        dispatchSessionListIntent(.markRead(id))
    }
    @discardableResult
    private func dispatchSessionListIntent(_ action: KMPSessionListIntent) -> Bool {
        do {
            switch action {
            case .restore: break
            case .select: offlineSessionSelectionRevision &+= 1
            case .setArchivedSessionIDs: offlineArchiveRevision &+= 1
            case .markRead: break
            default: offlineSessionRevision &+= 1
            }
            try kmpSessionListStore.reduce(action)
            return true
        } catch {
            lastError = error.localizedDescription
            return false
        }
    }
    private func handleSessionListEvent(
        snapshot: KMPSessionListSnapshot?,
        error: KMPSessionListStoreError?
    ) {
        if let error {
            lastError = error.localizedDescription
            return
        }
        guard let snapshot else { return }
        defer { updateChatPrefetchState(); persistOfflineCatalog() }
        let previousSessionIDs = Set(sessions.map(\.id))
        let previousArchivedSessionIDs = archivedSessionIds
        // stage9-kmp-write-scope: session-list-begin
        let mappedSessions = snapshot.persistedSessions
        if mappedSessions != sessions {
            sessions = mappedSessions
            persistSessions()
        }
        let runningSessionIDs = Set(mappedSessions.filter(\.isRunning).map(\.id))
        cancellingSessionIDs.formIntersection(runningSessionIDs)
        let mappedArchivedSessionIDs = snapshot.archivedSessionIDSet
        if mappedArchivedSessionIDs != archivedSessionIds {
            archivedSessionIds = mappedArchivedSessionIDs
        }
        if snapshot.selectedSessionId != selectedSessionId {
            if let id = selectedSessionId { historyPresentationAdmission.cancel(sessionID: id, outcome: .detached) }
            cancelWorkspaceFileDownload()
            completedWorkspaceFile = nil
            selectedSessionId = snapshot.selectedSessionId
        }
        // stage9-kmp-write-scope: session-list-end
        let removedSessionIDs = previousSessionIDs.subtracting(mappedSessions.map(\.id))
        let newlyArchivedSessionIDs = mappedArchivedSessionIDs.subtracting(previousArchivedSessionIDs)
        let clearedSessionIDs = removedSessionIDs.union(newlyArchivedSessionIDs)
        if !clearedSessionIDs.isEmpty {
            dispatchSessionControl(.clearSessionsData(sessionIDs: clearedSessionIDs))
            for sessionID in clearedSessionIDs {
                if sessionID == selectedSessionId { clearSearchContext(); conversationSearchTarget = nil }
                invalidateChatCache(sessionID: sessionID)
                do {
                    historySyncEngine.finish(sessionID: sessionID)
                    try kmpHistoryStore.clear(sessionID: sessionID)
                    discardPendingConversationHistory(sessionID: sessionID)
                    try kmpConversationStore.clear(sessionID: sessionID)
                    try kmpTrajectoryStore.clear(sessionID: sessionID)
                    trajectoryProjectedVersions[sessionID] = nil
                    trajectoryHistoryRevisions[sessionID] = nil
                    activeTrajectorySessionIDs.remove(sessionID)
                    pendingTrajectoryEvents[sessionID] = nil
                    trajectoryProjectionDrivers[sessionID]?.stop()
                } catch {
                    lastError = error.localizedDescription
                }
            }
        }
    }
    @discardableResult
    private func dispatchQuestionIntent(_ intent: KMPQuestionIntent) -> KMPQuestionTransition {
        let transition = kmpQuestionStore.reduce(intent)
        return transition
    }
    private func handleQuestionEvent(_ transition: KMPQuestionTransition) {
        if let error = transition.error {
            lastError = error.localizedDescription
            return
        }
        // stage9-kmp-write-scope: question-begin
        if transition.snapshot.pendingRequests != pendingQuestionRequests {
            pendingQuestionRequests = transition.snapshot.pendingRequests
        }
        let statuses = transition.snapshot.platformStatuses
        if statuses != questionRequestStatuses { questionRequestStatuses = statuses }
        // stage9-kmp-write-scope: question-end
        executeQuestionEffect(transition.effect)
    }
    private func executeQuestionEffect(_ effect: KMPQuestionEffect?) {
        guard let effect else { return }
        switch effect.action {
        case GatewayQuestionAction.answer.rawValue:
            guard let answers = effect.answers, !answers.isEmpty else { return }
            backgroundExecutionController.beginQuestionAnswer(
                rpcID: effect.rpcId,
                sessionID: effect.sessionId
            )
            questionEffectExecutor.answerQuestion(
                rpcId: effect.rpcId,
                sessionId: effect.sessionId,
                answers: answers
            )
        case GatewayQuestionAction.cancel.rawValue:
            questionEffectExecutor.cancelQuestion(rpcId: effect.rpcId, sessionId: effect.sessionId)
        default:
            lastError = "KMP Question 返回未知 effect：\(effect.action)"
        }
    }

    @discardableResult
    private func dispatchApprovalIntent(_ intent: KMPApprovalIntent) -> KMPApprovalTransition {
        let transition = kmpApprovalStore.reduce(intent)
        if let error = transition.error {
            gatewayApprovalTrace("shared rejected error=\(error.localizedDescription)")
            lastError = error.localizedDescription
            return transition
        }
        pendingApprovalRequests = transition.snapshot.pendingRequests
        approvalRequestStatuses = transition.snapshot.platformStatuses
        gatewayApprovalTrace(
            "shared accepted pending=\(pendingApprovalRequests.count) " +
            "selectedVisible=\(selectedPendingApprovalRequest != nil) hasEffect=\(transition.effect != nil)"
        )
        if let effect = transition.effect,
           let outcome = GatewayApprovalOutcome(rawValue: effect.outcome) {
            approvalEffectExecutor.respondToApproval(
                rpcId: effect.rpcId,
                sessionId: effect.sessionId,
                approvalId: effect.approvalId,
                outcome: outcome
            )
        }
        return transition
    }

    private func invalidateChatCache(sessionID: String) {
        chatCacheWrites.removeValue(forKey: sessionID)?.cancel()
        guard let chatHistoryCache else { return }
        let generation = DispatchTime.now().uptimeNanoseconds
        let gatewayID = gatewayLocalID
        Task { await chatHistoryCache.remove(gatewayID: gatewayID, sessionID: sessionID, generation: generation) }
    }

    private func scheduleChatCacheWrite(sessionID: String, immediate: Bool) {
        guard !restoringChatCache, let chatHistoryCache, let protectionOwner = chatCacheProtectionOwner else { return }
        chatCacheWrites.removeValue(forKey: sessionID)?.cancel()
        let generation = DispatchTime.now().uptimeNanoseconds
        chatCacheWrites[sessionID] = Task { [weak self] in
            if !immediate {
                do { try await Task.sleep(for: .milliseconds(500)) } catch { return }
            }
            guard !Task.isCancelled, let self,
                  let json = self.kmpHistoryStore.cachedWindowJSON(sessionID: sessionID,
                    historyFormatVersion: self.assistantStreamState.formatVersion(sessionId: sessionID)?.intValue,
                    persistentCursor: self.assistantStreamState.persistentCursorFor(sessionId: sessionID)?.intValue) else { return }
            let protected = Set(self.sessions.filter(\.isRunning).map(\.id)).union([self.selectedSessionId].compactMap { $0 })
            let gatewayID = self.gatewayLocalID
            let epoch = await chatHistoryCache.epoch()
            await chatHistoryCache.protect(owner: protectionOwner, sessionIDs: protected)
            guard !Task.isCancelled else { return }
            await chatHistoryCache.save(gatewayID: gatewayID, sessionID: sessionID, json: json, generation: generation, epoch: epoch)
        }
    }

    private func handleHistoryChange(_ change: KMPHistoryChange) {
        defer { updateChatPrefetchState() }
        if evictedConversationSessionIDs.contains(change.sessionID) {
            guard !change.events.isEmpty else { return }
            evictedConversationSessionIDs.remove(change.sessionID)
        }
        if change.eventPatchKind != nil, !conversationContentSessionIds.contains(change.sessionID),
           !conversationPreparingSessionIDs.contains(change.sessionID) {
            conversationPreparingSessionIDs.insert(change.sessionID)
        }
        // stage10-kmp-write-scope: history-begin
        if change.hasMore != historyHasMore { historyHasMore = change.hasMore }
        if change.loadingSessionIDs != historyLoadingSessionIds {
            historyLoadingSessionIds = change.loadingSessionIDs
        }
        if change.loadingOlderSessionIDs != historyLoadingOlderSessionIds {
            historyLoadingOlderSessionIds = change.loadingOlderSessionIDs
        }
        if change.progress != historyLoadProgress { historyLoadProgress = change.progress }
        // stage10-kmp-write-scope: history-end

        if let patchKind = change.eventPatchKind {
            historyLoadErrors[change.sessionID] = nil
            events[change.sessionID] = change.events
            if change.eventRecord == nil {
                turnTiming.receiveHistory(sessionId: change.sessionID, boundaries: change.events.compactMap { record in
                    guard record.event.type == "turn/start" || record.event.type == "turn/end" else { return nil }
                    return SharedTurnBoundary(sequence: Int32(record.seq), epochSeconds: record.time,
                                              isStart: record.event.type == "turn/start")
                })
            }
            if isShowingSearchContext, change.sessionID == selectedSessionId,
               let sequence = localSearchSequences[change.sessionID],
               searchTargetWasInvalidated(sessionID: change.sessionID, sequence: sequence) {
                invalidateSearchContext(sessionID: change.sessionID)
            }
            trajectoryHistoryRevisions[change.sessionID, default: 0] &+= 1
            // Only the baseline path can surface events the client has never
            // scanned (a new image attachment, a new tool row). A streaming chunk
            // only extends a row that already exists, so rescanning the whole
            // retained list for it would reintroduce an O(history) cost on every
            // token.
            var rebaselined = false
            var queuedHistory = false
            do {
                if change.eventRecord != nil { try flushPendingConversationHistory(sessionID: change.sessionID) }
                if patchKind == "append", let record = change.eventRecord {
                    try kmpConversationStore.receive(record)
                    if activeTrajectorySessionIDs.contains(change.sessionID) {
                        pendingTrajectoryEvents[change.sessionID, default: []].append(record)
                        scheduleTrajectoryProjection(for: change.sessionID)
                    }
                } else if change.eventPatchIsStreamDelta, let record = change.eventRecord,
                          // The trajectory projection is only kept live while its
                          // page is on screen and has no same-sequence append path
                          // yet, so a visible trajectory page keeps the rebaseline
                          // that already existed for these chunks.
                          !activeTrajectorySessionIDs.contains(change.sessionID),
                          // No baseline means no row to mutate; fall through to
                          // the rebaseline rather than dropping content.
                          kmpConversationStore.hasProjection(sessionID: change.sessionID) {
                    try kmpConversationStore.receiveStreamDelta(record)
                } else {
                    rebaselined = true
                    conversationProjectionEpochs[change.sessionID, default: 0] &+= 1
                    requireHistoryPresentation(sessionID: change.sessionID, historyEventSequence: change.eventSequence)
                    let appended: Bool
                    if let snapshot = change.projectionSnapshot {
                        appended = try kmpConversationStore.appendHistory(sessionID: change.sessionID,
                            snapshot: snapshot, lastSequence: change.events.last?.seq ?? -1)
                    } else { appended = false }
                    if appended {
                        discardPendingConversationHistory(sessionID: change.sessionID, cancelPresentation: false)
                        historyPresentationCommittedEpochs[change.sessionID] = historyPresentationRequiredEpochs[change.sessionID, default: 0]
                        try replayConversationTransientChunks(sessionID: change.sessionID)
                    } else {
                        queuedHistory = enqueueStaticConversationHistory(change)
                    }
                    if !appended && !queuedHistory {
                        discardPendingConversationHistory(sessionID: change.sessionID, cancelPresentation: false)
                        try kmpConversationStore.replace(sessionID: change.sessionID, events: change.events,
                            historySnapshot: change.projectionSnapshot)
                        historyPresentationCommittedEpochs[change.sessionID] = historyPresentationRequiredEpochs[change.sessionID, default: 0]
                        try replayConversationTransientChunks(sessionID: change.sessionID)
                    }
                    if activeTrajectorySessionIDs.contains(change.sessionID) {
                        pendingTrajectoryEvents[change.sessionID] = nil
                        trajectoryProjectionDrivers[change.sessionID]?.stop()
                        try kmpTrajectoryStore.replace(sessionID: change.sessionID, events: change.events)
                        trajectoryProjectedVersions[change.sessionID] = trajectoryHistoryVersion(for: change.sessionID)
                    }
                }
            } catch {
                conversationPreparingSessionIDs.remove(change.sessionID)
                historyPresentationAdmission.cancel(sessionID: change.sessionID, outcome: .failed)
                lastError = error.localizedDescription
                return
            }
            // 包括空快照：即使 KMP 没有发出行变化，也必须完成首次呈现。
            if !queuedHistory { scheduleConversationProjection(for: change.sessionID) }
            if rebaselined {
                enqueueImageAttachments(in: change.events, sessionId: change.sessionID)
            }
        }

        if let effect = change.effect {
            guard effect.action == "request-page" else {
                lastError = "KMP History 返回未知 effect：\(effect.action)"
                return
            }
            requestHistoryPage(for: effect.sessionId, beforeSeq: effect.beforeSequence)
        }

        if !restoringChatCache, (change.eventPatchKind != nil || change.outcome == "completed"),
           change.eventRecord?.event.type != "assistant/chunk" {
            scheduleChatCacheWrite(sessionID: change.sessionID,
                immediate: change.eventRecord?.event.type == "assistant/message" || change.outcome == "completed")
        }

        if ["completed", "stopped", "failed"].contains(change.outcome) {
            if !completeHistoryReadingPage(sessionID: change.sessionID, failed: change.outcome == "failed",
                historyEventSequence: change.eventSequence) {
                historyPresentationAdmission.terminal(sessionID: change.sessionID,
                    domainLoading: change.loadingSessionIDs.contains(change.sessionID),
                    failure: change.outcome == "failed" ? .failed : nil, historyEventSequence: change.eventSequence)
            }
        }
        switch change.outcome {
        case "none", "request-page":
            break
        case "stopped":
            historySyncEngine.finish(sessionID: change.sessionID)
        case "failed":
            historySyncEngine.finish(sessionID: change.sessionID)
            let message: String
            switch change.failureCode {
            case "MISSING_NEXT_CURSOR":
                message = String(localized: "history.pagination.stopped.hasmore", defaultValue: "网关返回 hasMore:true，但缺少 nextBeforeSeq，已停止自动续页。")
            case "REPEATED_CURSOR":
                message = String(localized: "history.cursor.loop", defaultValue: "网关重复返回历史游标，已停止自动续页以避免循环。")
            default:
                message = "KMP History 分页失败（\(change.failureCode ?? "unknown")）"
            }
            lastError = message
            notice(
                String(localized: "notice.history.pagination-failed", defaultValue: "历史记录分页失败"),
                message,
                sessionId: change.sessionID,
                isError: true
            )
        case "completed":
            historySyncEngine.finish(sessionID: change.sessionID)
            let eventCount = change.completedEventCount ?? 0
            let byteCount = change.completedByteCount ?? 0
            let byteDetail = byteCount > 0
                ? " · \(ByteCountFormatter.string(fromByteCount: Int64(byteCount), countStyle: .file))"
                : ""
            let moreDetail = change.completedHasMore == true
                ? String(localized: "history.swipe.up.hint", defaultValue: " · 向上滑动加载更早记录")
                : ""
            notice(
                String(localized: "notice.history.loaded", defaultValue: "历史记录已加载"),
                String(localized: "history.loaded.detail", defaultValue: "\(eventCount) 个事件\(byteDetail)\(moreDetail)"),
                sessionId: change.sessionID
            )
        default:
            lastError = "KMP History 返回未知 outcome：\(change.outcome)"
        }
    }
    @discardableResult
    private func dispatchSessionControlAction(_ action: KMPSessionControlAction) -> KMPSessionControlTransition {
        submitSessionControlIntent(.action(action))
    }
    @discardableResult
    private func dispatchSessionControlProjection(_ action: KMPSessionControlAction) -> KMPSessionControlTransition {
        submitSessionControlIntent(.projection(action))
    }
    private func dispatchSessionControl(_ intent: KMPSessionControlIntent) {
        submitSessionControlIntent(intent)
    }
    @discardableResult
    private func submitSessionControlIntent(
        _ intent: KMPSessionControlIntent
    ) -> KMPSessionControlTransition {
        let transition = kmpSessionControlStore.reduce(intent)
        return transition
    }
    private func handleSessionControlEvent(_ transition: KMPSessionControlTransition) {
        applySessionControlTransition(transition)
        guard transition.error == nil else { return }
        for effect in transition.effects where kmpSessionControlStore.snapshot.requestTokens[effect.requestKey] == effect.requestToken {
            executeSessionControlEffect(effect)
        }
    }
    @discardableResult
    private func applySessionControlTransition(
        _ transition: KMPSessionControlTransition
    ) -> KMPSessionControlTransition {
        if let patch = transition.patch {
            applySessionControlSnapshot(transition.snapshot, patch: patch)
        }
        if let error = transition.error { lastError = error.localizedDescription }
        return transition
    }
    private func applySessionControlSnapshot(
        _ state: KMPSessionControlSnapshot,
        patch: KMPSessionControlPatch? = nil
    ) {
        // stage9-kmp-write-scope: session-control-begin
        if patch == nil
            || patch?.modelCatalogsUpsert.isEmpty == false
            || patch?.modelCatalogsRemove.isEmpty == false {
            modelCatalogs = state.modelCatalogs
        }
        if patch == nil || patch?.globalModelCatalogChanged == true {
            globalModelCatalog = state.globalModelCatalog
        }
        if patch == nil
            || patch?.sessionPermissionsUpsert.isEmpty == false
            || patch?.sessionPermissionsRemove.isEmpty == false {
            sessionPermissions = state.sessionPermissions
        }
        if patch == nil
            || patch?.contextSnapshotsUpsert.isEmpty == false
            || patch?.contextSnapshotsRemove.isEmpty == false {
            contextSnapshots = state.contextSnapshots
        }
        if patch == nil
            || patch?.sessionStatsSnapshotsUpsert.isEmpty == false
            || patch?.sessionStatsSnapshotsRemove.isEmpty == false {
            sessionStatsSnapshots = state.sessionStatsSnapshots
        }
        if patch == nil || patch?.agentPresetsChanged == true { agentPresets = state.agentPresets }
        if patch == nil || patch?.agentPresetsAuthorable != nil {
            agentPresetsAuthorable = state.agentPresetsAuthorable
        }
        if patch == nil || patch?.agentPresetsHasDocument != nil {
            agentPresetsHasDocument = state.agentPresetsHasDocument
        }
        if patch == nil || patch?.agentPresetDefaultChanged == true {
            agentPresetDefault = state.agentPresetDefault
        }
        if patch == nil || patch?.permissionDefaultChanged == true {
            permissionDefault = state.permissionDefault
        }
        if patch == nil || patch?.permissionDefaultOptions != nil {
            permissionDefaultOptions = state.permissionDefaultOptions
        }
        if patch == nil || patch?.defaultModelSelectionChanged == true {
            defaultModelSelection = state.defaultModelSelection
        }
        if patch == nil || patch?.control != nil {
            if sessionControlLoadingKinds != state.loadingKinds {
                sessionControlLoadingKinds = state.loadingKinds
            }
            if defaultConfigurationLoadingKinds != state.defaultConfigurationLoadingKinds {
                defaultConfigurationLoadingKinds = state.defaultConfigurationLoadingKinds
            }
            if pendingModelsSessionId != state.pendingModelsSessionId {
                pendingModelsSessionId = state.pendingModelsSessionId
            }
            if isPendingGlobalModelsRequest != state.isPendingGlobalModelsRequest {
                isPendingGlobalModelsRequest = state.isPendingGlobalModelsRequest
            }
            if pendingModelSelectionSessionId != state.pendingModelSelectionSessionId {
                pendingModelSelectionSessionId = state.pendingModelSelectionSessionId
            }
            if pendingPermissionOptionsSessionId != state.pendingPermissionOptionsSessionId {
                pendingPermissionOptionsSessionId = state.pendingPermissionOptionsSessionId
            }
        }
        // stage9-kmp-write-scope: session-control-end
    }
    private func cancelCompletedSessionControlTracker(
        _ kind: String,
        transition: KMPSessionControlTransition
    ) {
        // queued B 的 effect 在 dispatchSessionControlAction 内已用 tracker.begin 替换了 A；
        // 只在没有新 generation 时取消旧 timeout，避免迟到 A 误取消 B。
        guard transition.completedKind == kind else { return }
        guard transition.effects.isEmpty else { return }
        let tracker = Self.defaultConfigurationRequestKinds.contains(kind)
            ? defaultConfigurationRequestTracker
            : sessionControlRequestTracker
        tracker.finish(kind)
    }
    private func pauseSessionControlRequests() {
        sessionControlRequestsSuspended = true
        sessionControlRequestTracker.pause()
        defaultConfigurationRequestTracker.pause()
    }

    private func resumeSessionControlRequests() {
        sessionControlRequestsSuspended = false
        sessionControlRequestTracker.resume()
        defaultConfigurationRequestTracker.resume()
        let pending = deferredSessionControlEffects
        deferredSessionControlEffects.removeAll()
        for effect in pending where kmpSessionControlStore.snapshot.requestTokens[effect.requestKey] == effect.requestToken {
            executeSessionControlEffect(effect)
        }
    }

    func executeSessionControlEffect(_ effect: KMPSessionControlEffect) {
        guard !sessionControlRequestsSuspended else {
            if let index = deferredSessionControlEffects.firstIndex(where: { $0.requestKey == effect.requestKey }) {
                deferredSessionControlEffects[index] = effect
            } else {
                deferredSessionControlEffects.append(effect)
            }
            return
        }
        let isDefault = Self.defaultConfigurationRequestKinds.contains(effect.requestKey)
        let tracker = isDefault ? defaultConfigurationRequestTracker : sessionControlRequestTracker
        let startedAt = Date()
        if effect.requestKey == "agent-presets" {
            agentPresetsLoadError = nil
            notice("Agent 预设诊断", "开始请求 token=\(effect.requestToken) connected=\(gateway.state.isConnected)")
        }
        tracker.begin(effect.requestKey, timeout: sessionControlRequestTimeout) { [weak self] in
            guard let self,
                  self.kmpSessionControlStore.snapshot.requestTokens[effect.requestKey] == effect.requestToken else {
                return
            }
            _ = self.submitSessionControlIntent(.requestTimedOut(
                kind: effect.requestKey,
                isDefault: isDefault,
                requestToken: effect.requestToken
            ))
            if effect.requestKey == "agent-presets" {
                self.agentPresetsLoadError = String(localized: "Agent 预设加载超时，请重试。")
                self.notice("Agent 预设诊断", "请求超时 token=\(effect.requestToken) elapsed=\(Date().timeIntervalSince(startedAt))s connected=\(self.gateway.state.isConnected)", isError: true)
                return
            }
            if let sessionID = effect.sessionId, self.preparedConversationActivationKey != sessionID { return }
            self.lastError = String(localized: "control.request.timeout", defaultValue: "\(effect.requestKey) 请求超时，请检查 Mobile Gateway。")
        }
        switch effect.kind {
        case "models":
            sessionControlEffectExecutor.requestModels(sessionId: effect.sessionId)
        case "permission-options":
            sessionControlEffectExecutor.requestPermissionOptions(sessionId: effect.sessionId)
        case "context-usage":
            if let sessionID = effect.sessionId { sessionControlEffectExecutor.requestContextUsage(sessionId: sessionID) }
        case "session-stats":
            if let sessionID = effect.sessionId { sessionControlEffectExecutor.requestSessionStats(sessionId: sessionID) }
        case "agent-presets":
            sessionControlEffectExecutor.requestAgentPresets()
        case "defaults":
            sessionControlEffectExecutor.requestDefaults()
        case "default-model":
            sessionControlEffectExecutor.requestDefaultModel()
        case "select-model":
            if let sessionID = effect.sessionId, let provider = effect.provider, let model = effect.model {
                sessionControlEffectExecutor.selectModel(
                    sessionId: sessionID,
                    provider: provider,
                    model: model,
                    reasoningEffort: effect.reasoningEffort
                )
            }
        case "permission":
            if let sessionID = effect.sessionId, let value = effect.value {
                sessionControlEffectExecutor.setPermission(sessionId: sessionID, name: value)
            }
        case "save-default-model":
            if let provider = effect.provider, let model = effect.model {
                sessionControlEffectExecutor.saveDefaultModel(
                    provider: provider,
                    model: model,
                    reasoningEffort: effect.reasoningEffort
                )
            }
        case "set-default":
            if let target = effect.target, let value = effect.value {
                sessionControlEffectExecutor.setDefault(target: target, value: value)
            }
        default:
            lastError = "KMP SessionControl 返回未知 effect：\(effect.kind)"
        }
    }
    private func showOfflineHistoryBoundary() {
        guard let id = selectedSessionId, selectedConversationItems.isEmpty,
              events[id, default: []].isEmpty else { return }
        conversationPreparingSessionIDs.remove(id)
        historyLoadErrors[id] = "此会话尚未缓存，连接后可加载聊天内容"
    }

    private func resetRemoteSearchForOffline() {
        remoteSearchTimeout?.cancel()
        remoteSearchGate.reset()
        searchResults = localSearchResults
    }

    /// 目录恢复只安装导航元数据和父子地址，绝不安装历史/流 baseline 或发送订阅。
    private func restoreOfflineCatalog(_ json: String?) {
        if let json {
            let codec = SharedOfflineCatalogCodec()
            if let avatars = codec.teamAvatarsJson(catalogJson: json) {
                _ = teamStore.restoreAvatarAssignmentsJson(json: avatars)
                persistedTeamAvatars = teamStore.avatarAssignmentsJson()
            }
            if let routing = codec.conversationTargetsJson(catalogJson: json) {
                _ = teamStore.restoreRoutingJson(json: routing)
                persistedTeamRouting = teamStore.routingJson()
            }
            if let value = codec.sessionListJson(catalogJson: json),
               var snapshot = try? JSONDecoder().decode(KMPSessionListSnapshot.self, from: Data(value.utf8)) {
                if offlineSessionRevision == 0 {
                    if offlineSessionSelectionRevision > 0 { snapshot.selectedSessionId = kmpSessionListStore.snapshot.selectedSessionId }
                    if offlineArchiveRevision > 0 { snapshot.archivedSessionIds = kmpSessionListStore.snapshot.archivedSessionIds }
                    _ = dispatchSessionListIntent(.restore(snapshot))
                } else if offlineArchiveRevision == 0 {
                    // sessions 与 archives 由不同响应推进；先来的 sessions 不应阻止补归档目录。
                    _ = dispatchSessionListIntent(.setArchivedSessionIDs(snapshot.archivedSessionIDSet))
                }
                drainKMPEventDeliveries()
            }
            if offlineWorkspaceRevision == 0, let value = codec.workspacesJson(catalogJson: json),
               let decoded = try? JSONDecoder().decode([GatewayWorkspace].self, from: Data(value.utf8)) {
                workspaces = decoded
                if offlineWorkspaceSelectionRevision == 0 {
                    selectedWorkspaceId = codec.selectedWorkspaceId(catalogJson: json)
                }
            }
        }
        offlineCatalogRestored = true
        persistOfflineCatalog()
    }

    private func persistOfflineCatalog() {
        guard offlineCatalogRestored, offlineCatalogActive, let cache = chatHistoryCache,
              let owner = chatCacheProtectionOwner else { return }
        offlineCatalogWriteRevision &+= 1
        let revision = offlineCatalogWriteRevision
        let previous = offlineCatalogWriteTask
        previous?.cancel()
        offlineCatalogWriteTask = Task { [weak self] in
            await previous?.value
            do { try await Task.sleep(for: .milliseconds(200)) } catch { return }
            guard let self, offlineCatalogActive, !Task.isCancelled,
                  revision == offlineCatalogWriteRevision,
                  let encodedSessions = try? JSONEncoder().encode(kmpSessionListStore.snapshot),
                  let encodedWorkspaces = try? JSONEncoder().encode(workspaces),
                  let json = SharedOfflineCatalogCodec().create(
                    sessionListJson: String(decoding: encodedSessions, as: UTF8.self),
                    workspacesJson: String(decoding: encodedWorkspaces, as: UTF8.self),
                    selectedWorkspaceId: selectedWorkspaceId) else { return }
            let codec = SharedOfflineCatalogCodec()
            guard let routed = codec.withConversationTargets(catalogJson: json, targetsJson: teamStore.routingJson()),
                  let catalog = codec.withTeamAvatars(catalogJson: routed, avatarsJson: teamStore.avatarAssignmentsJson()) else { return }
            _ = await cache.saveOfflineCatalog(owner: owner, json: catalog)
        }
    }

    func awaitOfflineCatalogForTesting() async {
        await offlineCatalogRestoreTask?.value
        await offlineCatalogWriteTask?.value
    }

    private func persistSessions() {
        preferences.saveSessions(sessions)
    }
}

/// 旧网关未回显 query 时仍能把响应归属唯一请求；超时请求消化前不再发新请求。
struct RemoteSearchGate {
    private(set) var inFlight: String?
    private var blocked = false
    mutating func begin(_ query: String) -> Bool {
        guard !query.isEmpty, inFlight == nil, !blocked else { return false }
        inFlight = query
        return true
    }
    mutating func timedOut() { blocked = true }
    mutating func reset() { inFlight = nil; blocked = false }
    mutating func complete(echoedQuery: String?, desiredQuery: String) -> Bool? {
        guard let requested = inFlight, echoedQuery == nil || echoedQuery == requested else { return nil }
        inFlight = nil
        blocked = false
        return requested == desiredQuery && !desiredQuery.isEmpty
    }
}
