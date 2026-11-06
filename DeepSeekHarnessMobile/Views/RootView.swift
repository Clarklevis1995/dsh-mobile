import SwiftUI
import QuickLook
import UIKit
import DeepSeekHarnessShared

struct RootView: View {
    @EnvironmentObject private var store: AppStore
    @EnvironmentObject private var hosts: MultiGatewayStore

    var body: some View {
        RootNavigationHost(store: store)
            .equatable()
            // 直接观察当前 AppStore，避免外层主机容器漏掉外观设置更新。
            .preferredColorScheme(store.interfaceStyle.colorScheme)
            .alert("Whaleness", isPresented: Binding(get: { store.lastError != nil }, set: { if !$0 { store.lastError = nil } })) {
                Button(String(localized: "好"), role: .cancel) { store.lastError = nil }
            } message: { Text(store.lastError ?? "") }
            .modifier(AppConnectionLifecycleModifier(tunnel: .shared, phaseChanged: store.handleScenePhase))
            .onReceive(NotificationCenter.default.publisher(for: UIApplication.didReceiveMemoryWarningNotification)) { _ in
                store.handleMemoryWarning()
            }
            .onReceive(AgentUserNotificationManager.shared.$pendingSessionRoute) { route in
                guard let route, route.gatewayID != hosts.activeID,
                      let profile = hosts.profiles.first(where: { $0.id == route.gatewayID }) else { return }
                hosts.select(profile)
            }
    }
}

/// Scene 生命周期是前后台恢复的唯一入口；先标记隧道，再通知网关，建连时共用恢复屏障。
struct AppConnectionLifecycleModifier: ViewModifier {
    @Environment(\.scenePhase) private var scenePhase
    let tunnel: TailscaleTunnelController
    let phaseChanged: (ScenePhase) -> Void

    func body(content: Content) -> some View {
        content.onChangeCompat(of: scenePhase) { _, phase in
            switch phase {
            case .background:
                tunnel.applicationDidEnterBackground()
            case .active:
                Task { await tunnel.refreshAfterReturningToApp() }
            default:
                break
            }
            phaseChanged(phase)
        }
    }
}

/// The navigation tree deliberately holds `AppStore` as an unobserved
/// reference. `RootView` can still present global errors, while session/history
/// publications no longer invalidate the `NavigationStack` that owns the bar.
private struct RootNavigationHost: View, Equatable {
    let store: AppStore
    @State private var navigationPath: [AppRoute] = []
    @State private var newConversationTask: Task<Void, Never>?
    /// 侧栏切换 / 新建会话的当前操作编号：被取消的旧任务收尾时，
    /// 只有编号仍是自己的才清空 newConversationTask，避免误清掉新任务的句柄。
    @State private var conversationOperationID = UUID()
    @State private var pendingLiveActivitySessionID: String?

    static func == (lhs: Self, rhs: Self) -> Bool {
        lhs.store === rhs.store
    }

    var body: some View {
        NavigationStack(path: $navigationPath) {
            WorkspaceView(
                onOpenSession: { session in
                    newConversationTask?.cancel()
                    let header = conversationHeader(for: session)
                    Task { @MainActor in
                        guard await store.prepareConversation(for: session),
                              !Task.isCancelled else { return }
                        navigate(to: .conversation(header))
                    }
                },
                onNewSession: {
                    guard newConversationTask == nil else { return }
                    let operationID = UUID()
                    conversationOperationID = operationID
                    newConversationTask = Task { @MainActor in
                        defer { if conversationOperationID == operationID { newConversationTask = nil } }
                        guard await store.prepareNewConversation(),
                              !Task.isCancelled else { return }
                        let header = ConversationNavigationHeader(
                            sessionID: store.selectedSessionId,
                            title: String(localized: "session.new.fallback", defaultValue: "新建 DeepSeek Harness"),
                            agentPresetTitle: agentPresetDisplayName(for: store.agentPresetDefault)
                        )
                        navigate(to: .conversation(header))
                    }
                },
                onSettings: {
                    newConversationTask?.cancel()
                    navigate(to: .settings)
                },
                onPlugins: {
                    navigate(to: .plugins)
                },
                onScheduledTasks: {
                    navigate(to: .scheduledTasks)
                }
            )
                .navigationDestination(for: AppRoute.self) { route in
                    destination(for: route)
                }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .onChangeCompat(of: navigationPath) { _, path in
            if path.isEmpty { store.resumeWorkspace() }
        }
        .onOpenURL(perform: openLiveActivityURL)
        .onReceive(AgentUserNotificationManager.shared.$pendingSessionRoute) { route in
            guard let route, route.gatewayID == store.gatewayLocalID else { return }
            pendingLiveActivitySessionID = route.sessionID
            guard let session = store.sessions.first(where: { $0.id == route.sessionID }) else { return }
            AgentUserNotificationManager.shared.clearPendingSessionRoute(route)
            openLiveActivitySession(session)
        }
        .onReceive(store.$sessions) { sessions in
            guard let sessionID = pendingLiveActivitySessionID,
                  let session = sessions.first(where: { $0.id == sessionID }) else { return }
            if let route = AgentUserNotificationManager.shared.pendingSessionRoute,
               route.gatewayID == store.gatewayLocalID, route.sessionID == sessionID {
                AgentUserNotificationManager.shared.clearPendingSessionRoute(route)
            }
            openLiveActivitySession(session)
        }
    }

    @ViewBuilder
    private func destination(for route: AppRoute) -> some View {
        switch route {
        case .conversation(let header):
            ConversationNavigationShell(
                header: header,
                gateway: store.gateway,
                store: store,
                sidebarActions: sidebarActions,
                onActivate: {
                    await store.activatePreparedConversation(sessionID: header.sessionID)
                }
            ) { activeView, topInset in
                ConversationView(activeView: activeView, topContentInset: topInset, initialDraft: header.initialDraft)
            }
        case .settings:
            // 设置及其子页共用根路径，避免 iOS 17 真机的目标式导航反复布局。
            SettingsView(open: openSettingsPage)
        case .settingsDetail(let route):
            SettingsDestinationView(route: route, open: openSettingsPage, close: {
                guard navigationPath.last == .settingsDetail(route) else { return }
                navigationPath.removeLast()
            })
        case .plugins:
            PluginsView(store: store)
        case .scheduledTasks:
            ScheduledTasksView(store: store, onCreateTask: createScheduledTaskConversation) { sessionID in
                let session = store.sessions.first(where: { $0.id == sessionID }) ?? SessionSummary(
                    id: sessionID,
                    title: store.scheduledTasks.first(where: { $0.sessionID == sessionID })?.title ?? String(localized: "会话"),
                    lastActivity: .now,
                    isRunning: false,
                    hasUnread: false
                )
                newConversationTask?.cancel()
                let header = conversationHeader(for: session)
                let operationID = UUID()
                conversationOperationID = operationID
                newConversationTask = Task { @MainActor in
                    defer { if conversationOperationID == operationID { newConversationTask = nil } }
                    guard await store.prepareConversation(for: session), !Task.isCancelled else { return }
                    navigationPath = [.conversation(header)]
                }
            }
        }
    }

    private func createScheduledTaskConversation() {
        guard newConversationTask == nil else { return }
        let operationID = UUID()
        conversationOperationID = operationID
        newConversationTask = Task { @MainActor in
            defer { if conversationOperationID == operationID { newConversationTask = nil } }
            guard await store.prepareNewConversation(useDefaultWorkspace: true), !Task.isCancelled else { return }
            let header = ConversationNavigationHeader(
                sessionID: store.selectedSessionId,
                title: String(localized: "session.new.fallback", defaultValue: "新建 DeepSeek Harness"),
                agentPresetTitle: agentPresetDisplayName(for: store.agentPresetDefault),
                initialDraft: String(localized: "请根据需求创建一个自动化任务。")
            )
            navigationPath = [.conversation(header)]
        }
    }

    /// Resolves the small amount of route chrome before the push begins.
    /// Conversation content can then load and stream independently without
    /// participating in navigation-bar preference resolution.
    private func conversationHeader(for session: SessionSummary?) -> ConversationNavigationHeader {
        let presetID = (store.sessionAgentPreset.sessionId == session?.id ? store.sessionAgentPreset.agentPreset : nil)
            ?? session?.agentPreset ?? store.agentPresetDefault
        return ConversationNavigationHeader(
            sessionID: session?.id,
            title: session?.title ?? String(localized: "session.new.fallback", defaultValue: "新建 DeepSeek Harness"),
            agentPresetTitle: agentPresetDisplayName(for: presetID)
        )
    }

    private func agentPresetDisplayName(for id: String?) -> String {
        guard let id else { return "Agent" }
        if let preset = store.agentPresets.first(where: { $0.id == id }) {
            return preset.displayName
        }
        return L10n.presetModeName(for: id)
    }

    private func navigate(to route: AppRoute) {
        guard navigationPath.last != route else { return }
        navigationPath.append(route)
    }

    private func openSettingsPage(_ route: SettingsRoute) {
        navigate(to: .settingsDetail(route))
    }

    private func openLiveActivityURL(_ url: URL) {
        guard url.scheme == "dshmobile", url.host == "session",
              let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let gatewayID = components.queryItems?.first(where: { $0.name == "gateway" })?.value,
              gatewayID == store.gatewayLocalID,
              let sessionID = components.queryItems?.first(where: { $0.name == "id" })?.value else { return }
        pendingLiveActivitySessionID = sessionID
        guard let session = store.sessions.first(where: { $0.id == sessionID }) else { return }
        openLiveActivitySession(session)
    }

    /// 会话页悬浮侧栏的导航动作；展示状态由会话页自己持有。
    private var sidebarActions: ConversationSidebarActions {
        ConversationSidebarActions(
            openSession: { session in
                // 已经停在这个会话页就不重复切换。
                // 有切换还没完成时不能忽略（例如在 A 点了 B 又点回 A），交给 switchToSession 取消并重来。
                if newConversationTask == nil,
                   case .conversation(let header)? = navigationPath.last,
                   header.sessionID == session.id {
                    return
                }
                switchToSession(session)
            },
            openPlugins: { navigate(to: .plugins) },
            openScheduledTasks: { navigate(to: .scheduledTasks) },
            openSettings: {
                newConversationTask?.cancel()
                navigate(to: .settings)
            }
        )
    }

    private func openLiveActivitySession(_ session: SessionSummary) {
        pendingLiveActivitySessionID = nil
        switchToSession(session)
    }

    /// 在当前导航栈上直接换成另一个会话（Live Activity 深链接与侧栏点会话共用）。
    private func switchToSession(_ session: SessionSummary) {
        newConversationTask?.cancel()
        let header = conversationHeader(for: session)
        let operationID = UUID()
        conversationOperationID = operationID
        newConversationTask = Task { @MainActor in
            defer { if conversationOperationID == operationID { newConversationTask = nil } }
            guard await store.prepareConversation(for: session), !Task.isCancelled else { return }
            // Replace the route in one published mutation. Clearing the stack first
            // briefly made `onChange` call `resumeWorkspace()`, which sent an
            // unsubscribe immediately after a Live Activity deep link opened the
            // conversation. When SwiftUI reused the same destination, its `.task`
            // did not necessarily run again, leaving both chat and Live Activity
            // without the Host -> Mobile event stream.
            navigationPath = [.conversation(header)]

            // A deep link may target the conversation already on screen. In that
            // case the destination is reused and has no lifecycle callback to
            // reactivate it, so complete activation from the deep-link transaction
            // as well. The store method is idempotent when the destination task has
            // already activated the same session.
            await Task.yield()
            guard !Task.isCancelled else { return }
            await store.activatePreparedConversation(sessionID: session.id)
        }
    }

    private enum AppRoute: Hashable {
        case conversation(ConversationNavigationHeader)
        case settings
        case settingsDetail(SettingsRoute)
        case plugins
        case scheduledTasks
    }
}

private struct WorkspaceDrawerDestination: View {
    let title: String
    let message: String

    var body: some View {
        Text(message)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color(uiColor: .systemBackground))
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
    }
}

enum ScheduledTaskStatusFilter: String, CaseIterable {
    case active, paused, ended, all

    var title: String {
        switch self {
        case .all: String(localized: "全部")
        case .active: String(localized: "已开启")
        case .paused: String(localized: "已暂停")
        case .ended: String(localized: "已结束")
        }
    }
}

func filteredScheduledTasks(
    _ tasks: [ScheduledTask], status: ScheduledTaskStatusFilter, query: String
) -> [ScheduledTask] {
    let search = query.trimmingCharacters(in: .whitespacesAndNewlines)
    return tasks.filter { task in
        let matchesStatus: Bool
        switch status {
        case .all: matchesStatus = true
        case .active: matchesStatus = task.status == "active"
        case .paused: matchesStatus = task.status == "paused"
        case .ended: matchesStatus = task.status != "active" && task.status != "paused"
        }
        return matchesStatus && (search.isEmpty || task.title.localizedStandardContains(search) ||
            task.prompt.localizedStandardContains(search))
    }
}

private struct ScheduledTasksView: View {
    private enum ActiveAlert: Identifiable {
        case delete(ScheduledTask)
        case deleteEnded
        case error(String)

        var id: String {
            switch self {
            case .delete(let task): "delete-\(task.id)"
            case .deleteEnded: "delete-ended"
            case .error(let message): "error-\(message)"
            }
        }
    }

    @ObservedObject var store: AppStore
    let onCreateTask: () -> Void
    let onOpenSession: (String) -> Void
    @State private var historyTask: ScheduledTask?
    @State private var expandedTaskIDs: Set<String> = []
    @State private var editingTask: ScheduledTask?
    @State private var activeAlert: ActiveAlert?
    @State private var statusFilter: ScheduledTaskStatusFilter = .all
    @State private var statusSelectionFeedback = UISelectionFeedbackGenerator()
    @State private var searchQuery = ""
    @State private var isSearching = false
    @FocusState private var searchIsFocused: Bool
    private var visibleTasks: [ScheduledTask] {
        filteredScheduledTasks(store.scheduledTasks, status: statusFilter, query: searchQuery)
    }

    var body: some View {
        navigationContent
        .sheet(item: $historyTask) { task in
            historySheet(for: task)
        }
        .sheet(item: $editingTask) { task in
            ScheduledTaskEditSheet(task: task, store: store)
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
        }
        .onChangeCompat(of: store.scheduledTaskMutationError) { _, error in
            if let error, editingTask == nil { activeAlert = .error(error) }
        }
        .alert(item: $activeAlert) { alert in
            taskAlert(alert)
        }
    }

    private var taskList: some View {
        List {
            if store.supportsScheduleManagement {
                if store.scheduledTasks.count < store.scheduledTasksTotal {
                    Button("加载更多任务") { store.loadMoreScheduledTasks() }.disabled(store.scheduledTasksLoading)
                }
            }
            if store.scheduledTasksLoading && store.scheduledTasks.isEmpty {
                ProgressView("正在加载定时任务…")
                    .frame(maxWidth: .infinity)
                    .padding(.top, 72)
                    .listRowSeparator(.hidden)
                    .listRowBackground(Color.clear)
            } else if let error = store.scheduledTasksError, store.scheduledTasks.isEmpty {
                DshEmptyState {
                    Label("无法显示定时任务", systemImage: "wifi.exclamationmark")
                } description: {
                    Text(error)
                } actions: {
                    Button("重试") { store.refreshScheduledTasks() }
                }
                .padding(.top, 40)
                .listRowSeparator(.hidden)
                .listRowBackground(Color.clear)
            } else if store.scheduledTasks.isEmpty {
                DshEmptyState(String(localized: "暂无定时任务"), systemImage: "clock", description: Text("在会话中创建的定时任务会显示在这里"))
                    .padding(.top, 40)
                    .listRowSeparator(.hidden)
                    .listRowBackground(Color.clear)
            } else if visibleTasks.isEmpty {
                DshEmptyState(
                    String(localized: "没有符合条件的定时任务"),
                    systemImage: "line.3.horizontal.decrease.circle",
                    description: Text(searchQuery.isEmpty
                        ? String(localized: "试试其他任务状态")
                        : String(localized: "试试其他关键词或任务状态"))
                )
                    .padding(.top, 40)
                    .listRowSeparator(.hidden)
                    .listRowBackground(Color.clear)
            } else {
                ForEach(visibleTasks) { task in
                    taskRow(for: task)
                }
            }
        }
    }

    private var listContent: some View {
        taskList
        .listStyle(.plain)
        .adaptiveListWidth()
        .scrollContentBackground(.hidden)
        .refreshable { store.refreshScheduledTasks() }
        .onChangeCompat(of: statusFilter) { _, filter in
            store.filterScheduledTasks(active: filter == .all ? nil : filter == .active)
        }
        .background(Color(uiColor: .systemBackground))
    }

    private var navigationContent: some View {
        listContent
        .safeAreaInset(edge: .top, spacing: 0) {
            VStack(spacing: 0) {
                if isSearching {
                    searchField.padding(.vertical, 8)
                }
                statusSwitchBar
            }
            .padding(.horizontal, 20)
            .readableContentWidth()
            .background(Color(uiColor: .systemBackground))
        }
        .navigationTitle(String(localized: "定时任务"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { taskToolbar }
        .task {
            if !store.gateway.state.isConnected { store.refreshScheduledTasks() }
        }
        .onReceive(store.gateway.$state) { state in
            if state.isConnected { store.refreshScheduledTasks() }
        }
    }

    @ToolbarContentBuilder
    private var taskToolbar: some ToolbarContent {
        if !isSearching {
            if #available(iOS 26.0, *) {
                ToolbarItem(placement: .topBarTrailing) { searchButton }
                ToolbarSpacer(.fixed, placement: .topBarTrailing)
                ToolbarItem(placement: .topBarTrailing) { moreMenu }
            } else {
                ToolbarItem(placement: .topBarTrailing) { searchButton.buttonStyle(ScheduledTaskToolbarButtonStyle()) }
                ToolbarItem(placement: .topBarTrailing) { moreMenu.buttonStyle(ScheduledTaskToolbarButtonStyle()) }
            }
        }
    }

    private func taskRow(for task: ScheduledTask) -> some View {
        ScheduledTaskCard(
            task: task,
            sessionTitle: store.sessions.first(where: { $0.id == task.sessionID })?.title ?? task.sessionID,
            expanded: expandedTaskIDs.contains(task.id),
            onOpen: { if !task.sessionID.isEmpty { onOpenSession(task.sessionID) } },
            onExpand: {
                if !expandedTaskIDs.insert(task.id).inserted { expandedTaskIDs.remove(task.id) }
            },
            onShowHistory: store.supportsScheduleManagement ? { historyTask = task; store.loadScheduleHistory(task) } : nil
        )
        .listRowInsets(EdgeInsets(top: 8, leading: 20, bottom: 8, trailing: 20))
        .listRowSeparator(.hidden)
        .listRowBackground(Color.clear)
        .swipeActions(edge: .leading, allowsFullSwipe: false) {
            if store.supportsScheduleManagement {
                Button("执行记录") { historyTask = task; store.loadScheduleHistory(task) }
                Button("立即执行") { store.runScheduledTask(task) }.disabled(store.scheduledTaskPendingID != nil)
                if task.status != "ended" {
                    Button(task.status == "active" ? "暂停" : "恢复") {
                        store.setScheduledTaskActive(task, active: task.status != "active")
                    }
                    .disabled(store.scheduledTaskPendingID != nil)
                }
            }
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            Button(role: .destructive) { activeAlert = .delete(task) } label: {
                Image(systemName: "trash")
            }
            .accessibilityLabel("删除")
            .disabled(store.scheduledTaskPendingID != nil)
            if ScheduleTaskPresentation.shared.canEdit(status: task.status) {
                Button { editingTask = task } label: {
                    Image(systemName: "pencil")
                }
                .accessibilityLabel("编辑")
                .tint(.blue)
                .disabled(store.scheduledTaskPendingID != nil)
            }
        }
    }

    private func historySheet(for task: ScheduledTask) -> some View {
        ScheduledTaskHistorySheet(store: store, task: task, onOpenSession: { sessionID in
            historyTask = nil
            onOpenSession(sessionID)
        })
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    private func taskAlert(_ alert: ActiveAlert) -> Alert {
        switch alert {
        case .delete(let task):
            Alert(
                title: Text("删除定时任务？"),
                message: Text("删除后任务及投递记录无法恢复；已进入会话队列的消息不会撤回。"),
                primaryButton: .destructive(Text("删除任务及投递记录")) {
                    store.deleteScheduledTask(task)
                },
                secondaryButton: .cancel(Text("取消"))
            )
        case .error(let message):
            Alert(title: Text("定时任务操作失败"), message: Text(message), dismissButton: .default(Text("好")))
        case .deleteEnded:
            Alert(
                title: Text("删除已结束的定时任务？"),
                message: Text("已结束任务及其投递记录将被删除，无法恢复。"),
                primaryButton: .destructive(Text("删除")) { store.clearEndedScheduledTasks() },
                secondaryButton: .cancel(Text("取消"))
            )
        }
    }

    private var searchField: some View {
        HStack(spacing: 0) {
            Image(systemName: "magnifyingglass")
                .appFont(.system(size: 17, weight: .medium))
                .foregroundStyle(.secondary)
                .padding(.leading, 14)
            TextField("搜索定时任务", text: $searchQuery)
                .appFont(.system(size: 15))
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.search)
                .focused($searchIsFocused)
                .padding(.leading, 8)
                .accessibilityLabel("搜索定时任务")
                .onAppear { searchIsFocused = true }
            Button {
                searchIsFocused = false
                withAnimation(.spring(response: 0.32, dampingFraction: 0.86)) {
                    searchQuery = ""
                    isSearching = false
                }
            } label: {
                Image(systemName: "xmark.circle.fill")
                    .appFont(.system(size: 18))
                    .foregroundStyle(.secondary)
                    .frame(width: 40, height: 46)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("关闭搜索")
        }
        .frame(maxWidth: .infinity, minHeight: 46, maxHeight: 46)
        .background(Color(uiColor: .secondarySystemBackground), in: Capsule())
    }

    private var searchButton: some View {
        Button {
            withAnimation(.spring(response: 0.32, dampingFraction: 0.86)) {
                isSearching = true
            }
        } label: {
            Label("搜索定时任务", systemImage: "magnifyingglass")
                .labelStyle(.iconOnly)
        }
        .accessibilityIdentifier("scheduled-tasks-search")
    }

    private var moreMenu: some View {
        Menu {
            Button(action: onCreateTask) {
                Label {
                    Text("添加定时任务")
                } icon: {
                    Image("ScheduleCreate").renderingMode(.template)
                }
            }
            .accessibilityIdentifier("scheduled-tasks-create")
            Button(role: .destructive) { activeAlert = .deleteEnded } label: {
                Label("删除已结束的定时任务", systemImage: "trash")
            }
            .disabled(!store.supportsScheduleManagement || !store.gateway.state.isConnected || store.scheduledTaskPendingID != nil)
            .accessibilityIdentifier("scheduled-tasks-delete-ended")
        } label: {
            Label("更多", systemImage: "ellipsis").labelStyle(.iconOnly)
        }
        .accessibilityIdentifier("scheduled-tasks-more")
    }

    private var statusSwitchBar: some View {
        Picker("定时任务状态", selection: Binding(get: { statusFilter }, set: { next in
            guard next != statusFilter else { return }
            // 仅用户切换选项时反馈，刷新和状态恢复不触发震动。
            statusSelectionFeedback.selectionChanged()
            statusSelectionFeedback.prepare()
            statusFilter = next
        })) {
            ForEach(ScheduledTaskStatusFilter.allCases, id: \.self) { option in
                Text(option.title).tag(option)
            }
        }
        .pickerStyle(.segmented)
        .padding(.vertical, 8)
        .accessibilityIdentifier("scheduled-tasks-status-tabs")
        .onAppear { statusSelectionFeedback.prepare() }
    }


}

struct ScheduledTaskHistorySheet: View {
    @ObservedObject var store: AppStore
    let task: ScheduledTask
    let onOpenSession: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    private var records: [JSONValue] { store.scheduleHistory?["records"]?.arrayValue ?? [] }
    private var before: String? { store.scheduleHistory?["nextBefore"]?.stringValue }

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 20) {
                    Text(task.title).appFont(.headline)
                    ForEach(Array(records.enumerated()), id: \.offset) { _, record in
                        historyRow(record)
                    }
                    if store.scheduleHistoryLoading {
                        ProgressView().frame(maxWidth: .infinity)
                    } else if let error = store.scheduleHistoryError {
                        Text(error).foregroundStyle(.secondary)
                        Button("重试") { store.loadScheduleHistory(task, before: before) }
                    } else if records.isEmpty {
                        Text("暂无运行轨迹").foregroundStyle(.secondary)
                    }
                    if let before, !store.scheduleHistoryLoading, store.scheduleHistoryError == nil {
                        Button("加载更早轨迹") { store.loadScheduleHistory(task, before: before) }
                            .frame(maxWidth: .infinity)
                    }
                    if store.scheduleHistory?["retention"] != nil && store.scheduleHistory?["retention"] != .null ||
                        store.scheduleHistory?["earlierRecordsPruned"]?.boolValue == true ||
                        store.scheduleHistory?["earlierRecordsUnavailable"]?.boolValue == true {
                        Text("仅显示宿主仍保留的运行记录").appFont(.caption).foregroundStyle(.secondary)
                    }
                }
                .padding(20)
            }
            .navigationTitle("运行轨迹")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { dismiss() } } }
            .safeAreaInset(edge: .bottom) {
                if !task.sessionID.isEmpty {
                    Button { onOpenSession(task.sessionID) } label: {
                        Label("关联会话", systemImage: "bubble.left.and.bubble.right")
                            .frame(maxWidth: .infinity).padding()
                    }
                    .background(.regularMaterial)
                }
            }
        }
        .accessibilityIdentifier("scheduled-task-history-sheet")
    }

    private func historyRow(_ record: JSONValue) -> some View {
        let timestamp = record["scheduledAt"]?.stringValue ?? record["deliveredAt"]?.stringValue
        return HStack(alignment: .top, spacing: 12) {
            Image(systemName: "clock").foregroundStyle(.secondary)
            VStack(alignment: .leading, spacing: 10) {
                Text(timestamp.map(ScheduledTaskFormat.date) ?? String(localized: "时间未知"))
                    .appFont(.headline)
                Text(record["title"]?.stringValue ?? task.title).appFont(.body)
                DisclosureGroup(String(localized: "详情")) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(record["prompt"]?.stringValue ?? task.prompt)
                        if let delivered = record["deliveredAt"]?.stringValue {
                            Text(ScheduledTaskFormat.date(delivered)).foregroundStyle(.secondary)
                        }
                        if let message = record["messageId"]?.stringValue {
                            Text(message).appFont(.caption).foregroundStyle(.secondary).textSelection(.enabled)
                        }
                        if let session = (record["targetSessionId"] ?? record["sessionId"])?.stringValue, !session.isEmpty, session != task.sessionID {
                            Button("关联会话") { onOpenSession(session) }
                        }
                    }.frame(maxWidth: .infinity, alignment: .leading).padding(.top, 8)
                }
                Divider()
            }
        }
    }
}

/// iOS 16 使用显式圆形材质，避免调用仅 iOS 17 起提供的 ButtonBorderShape.circle。
private struct ScheduledTaskToolbarButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .frame(width: 44, height: 44)
            .background(.ultraThinMaterial, in: Circle())
            .overlay { Circle().strokeBorder(Color.primary.opacity(0.10), lineWidth: 0.5) }
            .opacity(configuration.isPressed ? 0.65 : 1)
    }
}

struct ScheduledTaskCard: View {
    let task: ScheduledTask
    let sessionTitle: String
    let expanded: Bool
    let onOpen: () -> Void
    let onExpand: () -> Void
    var onShowHistory: (() -> Void)? = nil
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ScheduledTaskCardLayout(expansion: expanded ? 1 : 0) {
            summaryContent
                .transaction { $0.animation = nil }
            detailsContent
                .opacity(expanded ? 1 : 0)
                .accessibilityHidden(!expanded)
                .allowsHitTesting(expanded)
        }
        .clipped()
        .glassSurface(radius: 28)
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.26), value: expanded)
        // List 更新行高时可能暂时给出更大的高度，卡片始终从顶部布局。
        .frame(maxHeight: .infinity, alignment: .top)
    }

    private var summaryContent: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button {
                onOpen()
            } label: {
                VStack(alignment: .leading, spacing: 8) {
                    Text(ScheduledTaskFormat.shortRule(task))
                        .appFont(.system(size: 15, weight: .medium))
                        .foregroundStyle(Color(red: 0.03, green: 0.51, blue: 0.97))
                    Text(task.title)
                        .appFont(.system(size: 21, weight: .semibold))
                        .foregroundStyle(.primary)
                        .multilineTextAlignment(.leading)
                    Text(task.prompt)
                        .appFont(.system(size: 16))
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.leading)
                        .lineLimit(3)
                        .padding(.top, 3)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .padding(.horizontal, 20)
            .padding(.top, 20)
            .padding(.bottom, 18)

            Divider()
                .padding(.horizontal, 20)
            Button {
                onExpand()
            } label: {
                HStack {
                    Text(task.status == "active" ? ScheduledTaskFormat.date(task.scheduledAt) : ScheduleTaskPresentation.shared.statusLabel(status: task.status).localized)
                        .appFont(.system(size: 15))
                        .foregroundStyle(.secondary)
                    Spacer()
                    Image(systemName: "chevron.down")
                        .appFont(.system(size: 14, weight: .semibold))
                        .foregroundStyle(.secondary)
                        .rotationEffect(.degrees(expanded ? 180 : 0))
                        .animation(reduceMotion ? nil : .easeInOut(duration: 0.26), value: expanded)
                        .frame(width: 36, height: 34)
                }
                .frame(maxWidth: .infinity)
                .padding(.leading, 20)
                .padding(.trailing, 12)
                .padding(.vertical, 10)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(expanded ? String(localized: "收起任务详情") : String(localized: "展开任务详情"))
        }
    }

    private var detailsContent: some View {
        VStack(alignment: .leading, spacing: 13) {
            detail(String(localized: "执行规则"), ScheduledTaskFormat.fullRule(task))
            detail(String(localized: "下次计划时间"), task.status == "active" ? ScheduledTaskFormat.date(task.scheduledAt) : String(localized: "无"))
            detail(String(localized: "所属会话"), sessionTitle)
            detail(String(localized: "状态"), ScheduleTaskPresentation.shared.statusLabel(status: task.status).localized)
            detail(String(localized: "最近投递"), task.lastDelivery?["deliveredAt"]?.stringValue.map {
                String(localized: "schedule.last-delivery", defaultValue: "已投递 · \(ScheduledTaskFormat.date($0))")
            } ?? String(localized: "暂无投递记录"))
            if let onShowHistory {
                Divider()
                Button(action: onShowHistory) {
                    HStack {
                        Label("查看运行轨迹", systemImage: "clock.arrow.circlepath")
                        Spacer()
                        Image(systemName: "chevron.right")
                    }
                    .appFont(.system(size: 15))
                    .padding(.vertical, 8)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .foregroundStyle(.blue)
                .accessibilityIdentifier("scheduled-task-history")
            }
        }
        .padding(.horizontal, 20)
        .padding(.top, 18)
        .padding(.bottom, 22)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func detail(_ label: String, _ value: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Text(label).foregroundStyle(.secondary).frame(width: 92, alignment: .leading)
            Text(value).foregroundStyle(.primary).frame(maxWidth: .infinity, alignment: .leading)
        }
        .appFont(.system(size: 14))
    }
}

/// 摘要始终从顶部布局，详情保持自然尺寸，仅动画改变其占用高度。
private struct ScheduledTaskCardLayout: Layout {
    var expansion: CGFloat

    var animatableData: CGFloat {
        get { expansion }
        set { expansion = newValue }
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? subviews.map { $0.sizeThatFits(.unspecified).width }.max() ?? 0
        let contentProposal = ProposedViewSize(width: width, height: nil)
        let summary = subviews[0].sizeThatFits(contentProposal)
        let details = subviews[1].sizeThatFits(contentProposal)
        return CGSize(width: width, height: summary.height + details.height * min(max(expansion, 0), 1))
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let contentProposal = ProposedViewSize(width: bounds.width, height: nil)
        let summary = subviews[0].sizeThatFits(contentProposal)
        subviews[0].place(at: bounds.origin, anchor: .topLeading, proposal: contentProposal)
        subviews[1].place(at: CGPoint(x: bounds.minX, y: bounds.minY + summary.height),
                          anchor: .topLeading, proposal: contentProposal)
    }
}

private struct ScheduledTaskEditSheet: View {
    let task: ScheduledTask
    @ObservedObject var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @State private var title: String
    @State private var prompt: String
    @State private var timing: ScheduleTimingForm
    @State private var target: ScheduleTargetForm
    private let originalTiming: ScheduleTimingForm
    @State private var pendingRequestID: String?
    @State private var localError: String?

    init(task: ScheduledTask, store: AppStore) {
        self.task = task
        self.store = store
        _title = State(initialValue: task.title)
        _prompt = State(initialValue: task.prompt)
        let form = ScheduleTimingForm(task: task)
        originalTiming = form
        _timing = State(initialValue: form)
        _target = State(initialValue: ScheduleTargetForm(task: task))
    }

    private var timingChange: JSONValue? {
        guard let json = ScheduleTimingEditor.shared.changeJson(draft: timing.shared, original: originalTiming.shared) else { return nil }
        return try? JSONDecoder().decode(JSONValue.self, from: Data(json.utf8))
    }
    private var timingError: String? { timing == originalTiming ? nil : timing.validationError }
    private var targetChange: JSONValue? { target.change(task: task) }
    private var targetError: String? {
        target == ScheduleTargetForm(task: task) ? nil : target.validationError(task: task)
    }
    private var canSave: Bool {
        let cleanTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanPrompt = prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanTitle.isEmpty, cleanTitle.utf16.count <= 120, !cleanPrompt.isEmpty else { return false }
        guard cleanTitle != task.title || cleanPrompt != task.prompt || timingChange != nil || targetChange != nil else { return false }
        if timingError != nil || targetError != nil { return false }
        return pendingRequestID == nil
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Button("取消") { dismiss() }
                    .disabled(pendingRequestID != nil)
                Spacer()
                Text("编辑定时任务").appFont(.system(size: 17, weight: .semibold))
                Spacer()
                Button("保存") { save() }
                    .fontWeight(.semibold)
                    .disabled(!canSave)
            }
            .padding(.horizontal, 20)
            .padding(.top, 32)
            .padding(.bottom, 24)
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("标题").appFont(.subheadline.weight(.medium))
                        TextField("任务标题", text: $title)
                            .disabled(pendingRequestID != nil)
                            .textInputAutocapitalization(.never)
                            .padding(13)
                            .background(Color(uiColor: .secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
                        Text("最多 120 个字").appFont(.caption).foregroundStyle(.secondary)
                    }
                    VStack(alignment: .leading, spacing: 8) {
                        Text("任务内容").appFont(.subheadline.weight(.medium))
                        TextEditor(text: $prompt)
                            .disabled(pendingRequestID != nil)
                            .scrollContentBackground(.hidden)
                            .frame(height: 160)
                            .padding(8)
                            .background(Color(uiColor: .secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
                    }
                    if store.supportsScheduleManagement {
                        ScheduleTargetControls(form: $target, task: task, workspaces: store.workspaces)
                            .disabled(pendingRequestID != nil)
                    }
                    if let targetError {
                        Text(targetError).appFont(.caption).foregroundStyle(.red)
                    }
                    ScheduleTimingControls(form: $timing)
                        .disabled(pendingRequestID != nil)
                    if let timingError {
                        Text(timingError).appFont(.caption).foregroundStyle(.red)
                    }
                    if let error = localError ?? (pendingRequestID != nil ? store.scheduledTaskMutationError : nil) {
                        Text(error).appFont(.subheadline).foregroundStyle(.red)
                    }
                    if pendingRequestID != nil { ProgressView("正在保存…") }
                }
                .padding(.horizontal, 20)
                .padding(.top, 28)
                .padding(.bottom, 20)
            }
            .scrollDismissesKeyboard(.interactively)
        }
        .background(Color(uiColor: .systemBackground))
        .background(ScheduleKeyboardDismissProbe())
        .interactiveDismissDisabled(pendingRequestID != nil)
        .onChangeCompat(of: store.scheduledTaskCompletedRequestID) { _, completedID in
            if completedID != nil && completedID == pendingRequestID { dismiss() }
        }
        .onChangeCompat(of: store.scheduledTaskPendingID) { _, pendingID in
            if pendingRequestID != nil && pendingID == nil {
                if store.scheduledTaskCompletedRequestID == pendingRequestID {
                    dismiss()
                    return
                }
                localError = store.scheduledTaskMutationError
                pendingRequestID = nil
            }
        }
    }

    private func save() {
        localError = nil
        guard canSave else { return }
        let requestID = store.updateScheduledTask(task, title: title, prompt: prompt, change: timingChange, target: targetChange)
        if let requestID { pendingRequestID = requestID }
        else { localError = store.scheduledTaskMutationError ?? String(localized: "操作正在进行，请稍后重试") }
    }

}

struct ScheduleTargetForm: Equatable {
    var kind: String
    var cwd: String

    init(task: ScheduledTask) {
        let draft = ScheduleTargetEditor.shared.fromRecord(recordJson: task.raw.displayText)
        kind = draft.kind
        cwd = draft.cwd
    }
    private var shared: ScheduleTargetDraft { ScheduleTargetDraft(kind: kind, cwd: cwd) }
    func change(task: ScheduledTask) -> JSONValue? {
        guard let json = ScheduleTargetEditor.shared.changeJson(draft: shared, recordJson: task.raw.displayText) else { return nil }
        return try? JSONDecoder().decode(JSONValue.self, from: Data(json.utf8))
    }
    /// Localized text of the validation failure, or nil when the target is valid.
    func validationError(task: ScheduledTask) -> String? {
        ScheduleTargetEditor.shared.validationError(draft: shared, recordJson: task.raw.displayText)?.localized
    }
}

struct ScheduleTargetControls: View {
    @Binding var form: ScheduleTargetForm
    let task: ScheduledTask
    let workspaces: [GatewayWorkspace]
    @State private var selectingWorkspace = false
    private var workspaceTitle: String {
        if form.cwd.isEmpty { return String(localized: "使用默认工作区") }
        return workspaces.first(where: { $0.path == form.cwd }).map { scheduleWorkspaceTitle($0.title) } ?? form.cwd
    }
    private var currentSessionAvailable: Bool {
        ScheduleTargetEditor.shared.currentSessionId(recordJson: task.raw.displayText) != nil
    }
    private var targetTitle: String {
        switch form.kind {
        case "session": String(localized: "当前关联会话")
        case "new_once": String(localized: "新建会话并复用")
        case "new": String(localized: "每次新建会话")
        default: form.kind
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("执行位置")
                    .fixedSize(horizontal: true, vertical: false)
                Menu {
                    Picker("执行位置", selection: $form.kind) {
                        Text("当前关联会话").tag("session").disabled(!currentSessionAvailable)
                        Text("新建会话并复用").tag("new_once")
                        Text("每次新建会话").tag("new")
                    }
                    .pickerStyle(.inline)
                } label: {
                    HStack(spacing: 6) {
                        Text(targetTitle).lineLimit(1).minimumScaleFactor(0.8)
                        Image(systemName: "chevron.down").appFont(.caption).foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, minHeight: 44, alignment: .trailing)
                }
                .foregroundStyle(.primary)
                .accessibilityIdentifier("schedule-target-picker")
            }
            if form.kind == "new_once" || form.kind == "new" {
                Text(form.kind == "new_once" ? LocalizedStringKey("首次运行时创建，之后沿用这个会话") : LocalizedStringKey("每次运行都创建独立会话"))
                    .appFont(.caption).foregroundStyle(.secondary)
                Divider()
                Button { selectingWorkspace = true } label: {
                    HStack {
                        Text("关联工作区")
                            .fixedSize(horizontal: true, vertical: false)
                        Spacer()
                        Text(workspaceTitle).lineLimit(2).truncationMode(.middle).multilineTextAlignment(.trailing)
                        Image(systemName: "chevron.down").foregroundStyle(.secondary)
                    }
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("schedule-workspace-picker")
            }
        }
        .padding(14)
        .background(Color(uiColor: .secondarySystemBackground), in: RoundedRectangle(cornerRadius: 16))
        .sheet(isPresented: $selectingWorkspace) {
            ScheduleWorkspacePicker(selection: $form.cwd, workspaces: workspaces)
        }
    }

}

private func scheduleWorkspaceTitle(_ title: String) -> String {
    title == "default-workspace" ? String(localized: "默认工作区") : title
}

private struct ScheduleWorkspacePicker: View {
    @Binding var selection: String
    let workspaces: [GatewayWorkspace]
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    private var choices: [GatewayWorkspace] {
        var paths = Set<String>()
        return workspaces.filter { !$0.path.isEmpty && paths.insert($0.path).inserted &&
            (query.isEmpty || scheduleWorkspaceTitle($0.title).localizedCaseInsensitiveContains(query) || $0.path.localizedCaseInsensitiveContains(query)) }
    }
    var body: some View {
        NavigationStack {
            List {
                Button { selection = ""; dismiss() } label: {
                    HStack(spacing: 12) { ScheduleSelectionMark(isSelected: selection.isEmpty); Text("使用默认工作区"); Spacer() }
                }
                if !selection.isEmpty && !workspaces.contains(where: { $0.path == selection }) {
                    HStack(spacing: 12) { ScheduleSelectionMark(isSelected: true); Text(selection); Spacer() }
                }
                ForEach(choices) { workspace in
                    Button { selection = workspace.path; dismiss() } label: {
                        HStack(spacing: 12) {
                            ScheduleSelectionMark(isSelected: selection == workspace.path)
                            VStack(alignment: .leading, spacing: 4) {
                                Text(scheduleWorkspaceTitle(workspace.title))
                                Text(workspace.path).appFont(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                        }
                    }
                }
            }
            .foregroundStyle(.primary)
            .searchable(text: $query, prompt: Text("搜索工作区"))
            .navigationTitle("关联工作区").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } } }
        }
        .background(ScheduleKeyboardDismissProbe())
    }
}

/// 仅将共享规则草稿桥接为 SwiftUI 可绑定的值；编码和无损投影由 KMP 统一处理。
struct ScheduleTimingForm: Equatable {
    var frequency: String
    var interval: String
    var date: String
    var time: String
    var timeZone: String
    var weekdays: String
    var monthDay: String
    var advanced: String

    init(task: ScheduledTask) {
        let zone = TimeZone(identifier: task.timeZone ?? "") ?? .current
        let instant = Self.parseInstant(task.scheduledAt) ?? Date().addingTimeInterval(3600)
        let formatter = Self.formatter("yyyy-MM-dd'T'HH:mm:ss.SSS", zone: zone)
        let draft = ScheduleTimingEditor.shared.fromRecord(recordJson: task.raw.displayText,
            defaultZone: zone.identifier, localStart: formatter.string(from: instant))
        frequency = draft.frequency; interval = draft.interval; date = draft.date; time = draft.time
        timeZone = draft.timeZone; weekdays = draft.weekdays; monthDay = draft.monthDay; advanced = draft.advanced
    }
    var shared: ScheduleTimingDraft {
        ScheduleTimingDraft(frequency: frequency, interval: interval, date: date, time: time,
            timeZone: timeZone, weekdays: weekdays, monthDay: monthDay, advanced: advanced)
    }
    /// Localized text of the first validation failure, or nil when the draft is valid.
    var validationError: String? {
        if let error = ScheduleTimingEditor.shared.validationError(draft: shared) { return error.localized }
        guard let zone = TimeZone(identifier: timeZone) else { return String(localized: "请选择有效的 IANA 时区。") }
        if frequency == "once" {
            guard let instant = Self.formatter("yyyy-MM-dd'T'HH:mm:ss.SSS", zone: zone).date(from: "\(date)T\(time)")
                ?? Self.formatter("yyyy-MM-dd'T'HH:mm:ss", zone: zone).date(from: "\(date)T\(time)") else { return String(localized: "请选择有效的日期和时间。") }
            if instant <= .now { return String(localized: "请选择未来的日期和时间。") }
        }
        return nil
    }
    static func formatter(_ pattern: String, zone: TimeZone) -> DateFormatter {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.timeZone = zone; formatter.dateFormat = pattern; formatter.isLenient = false
        return formatter
    }
    static func parseInstant(_ value: String) -> Date? {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = formatter.date(from: value) { return date }
        formatter.formatOptions = [.withInternetDateTime]
        return formatter.date(from: value)
    }
}

struct ScheduleTimingControls: View {
    @Binding var form: ScheduleTimingForm
    @State private var selectingZone = false
    @State private var selectingTime = false
    @State private var selectingInterval = false
    private let frequencies: [(String, LocalizedStringKey)] = [("once", LocalizedStringKey("不重复")),
        ("minutely", LocalizedStringKey("每分钟")), ("hourly", LocalizedStringKey("每小时")),
        ("daily", LocalizedStringKey("每天")), ("weekly", LocalizedStringKey("每周")),
        ("monthly", LocalizedStringKey("每月")), ("advanced", LocalizedStringKey("自定义"))]
    private var zone: TimeZone { TimeZone(identifier: form.timeZone) ?? .current }
    private var dateBinding: Binding<Date> {
        Binding(get: { ScheduleTimingForm.formatter("yyyy-MM-dd", zone: zone).date(from: form.date) ?? .now },
            set: { form.date = ScheduleTimingForm.formatter("yyyy-MM-dd", zone: zone).string(from: $0) })
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("执行时间").appFont(.subheadline.weight(.medium))
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    Text("重复")
                    Spacer()
                    Picker("重复", selection: $form.frequency) {
                        ForEach(frequencies, id: \.0) { value, label in Text(label).tag(value) }
                    }
                    .pickerStyle(.menu)
                    .labelsHidden()
                    .tint(.primary)
                }
                .frame(height: 52)
                .accessibilityIdentifier("schedule-frequency-picker")
                if form.frequency == "advanced" { advancedControls } else { guidedControls }
                if form.frequency != "advanced" || !form.advanced.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("DTSTART") {
                    Button { selectingZone = true } label: {
                        HStack { Text("时区"); Spacer(); Text(form.timeZone).multilineTextAlignment(.trailing); Image(systemName: "chevron.down") }
                            .frame(height: 52)
                    }
                    .foregroundStyle(.primary)
                    .accessibilityIdentifier("schedule-zone-picker")
                }
            }
            .padding(14)
            .background(Color(uiColor: .secondarySystemBackground), in: RoundedRectangle(cornerRadius: 16))
        }
        .sheet(isPresented: $selectingZone) {
            ScheduleTimeZonePicker(selection: $form.timeZone)
        }
        .sheet(isPresented: $selectingTime) {
            ScheduleClockPicker(selection: $form.time)
                .presentationDetents([.height(340)])
        }
        .sheet(isPresented: $selectingInterval) {
            ScheduleIntervalPicker(selection: $form.interval,
                unit: ScheduledTaskFormat.intervalUnit(form.frequency))
                .presentationDetents([.height(340)])
        }
    }
    @ViewBuilder private var guidedControls: some View {
        if form.frequency != "once" {
            Button { selectingInterval = true } label: {
                HStack {
                    Text("重复间隔")
                    Spacer()
                    Text(form.interval)
                    Text(LocalizedStringKey(["minutely":"分钟", "hourly":"小时", "daily":"天", "weekly":"周", "monthly":"个月"][form.frequency] ?? ""))
                    Image(systemName: "chevron.down").appFont(.caption).foregroundStyle(.secondary)
                }
                .frame(height: 52).contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("schedule-interval-picker")
        }
        DatePicker(LocalizedStringKey(form.frequency == "once" ? "执行日期" : "开始日期"), selection: dateBinding, displayedComponents: .date)
            .datePickerStyle(.compact)
            .environment(\.timeZone, zone)
            .frame(height: 52)
            .accessibilityIdentifier("schedule-date-picker")
        Button { selectingTime = true } label: {
            HStack {
                Text("时间")
                Spacer()
                Text(form.time).monospacedDigit()
                Image(systemName: "clock").foregroundStyle(.secondary)
            }
            .frame(height: 52)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .overlay(alignment: .bottom) { Divider() }
        .accessibilityIdentifier("schedule-time-picker")
        if form.frequency == "weekly" { weekdayControls.padding(.vertical, 8) }
        if form.frequency == "monthly" {
            HStack {
                Text("每月几号")
                Spacer()
                TextField("1–31", text: $form.monthDay).keyboardType(.numberPad)
                    .multilineTextAlignment(.trailing).frame(width: 80)
            }
            .frame(height: 52)
            Text("当月不足该日期时，在月末执行。").appFont(.caption).foregroundStyle(.secondary)
        }
    }
    private var weekdayControls: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("重复日期").appFont(.subheadline)
            LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 4), spacing: 8) {
                ForEach(1...7, id: \.self) { day in
                    let selected = form.weekdays.split(separator: ",").compactMap { Int($0) }.contains(day)
                    Button {
                        var days = Set(form.weekdays.split(separator: ",").compactMap { Int($0) })
                        if days.contains(day) { days.remove(day) } else { days.insert(day) }
                        form.weekdays = days.sorted().map(String.init).joined(separator: ",")
                    } label: {
                        Text(LocalizedStringKey(["周一", "周二", "周三", "周四", "周五", "周六", "周日"][day - 1]))
                            .frame(maxWidth: .infinity, minHeight: 36)
                            .background(selected ? Color.accentColor.opacity(0.16) : Color(uiColor: .tertiarySystemBackground), in: RoundedRectangle(cornerRadius: 10))
                    }
                    .foregroundStyle(selected ? Color.accentColor : .primary)
                    .accessibilityAddTraits(selected ? .isSelected : [])
                }
            }
        }
    }
    private var advancedControls: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Cron / RRULE").appFont(.subheadline)
            TextEditor(text: $form.advanced).frame(height: 110).scrollContentBackground(.hidden)
                .textInputAutocapitalization(.never).autocorrectionDisabled()
            Text("Cron 使用 5 个字段；RRULE 保留 DTSTART 中的时区。规则由宿主校验，出错后可继续编辑。")
                .appFont(.caption).foregroundStyle(.secondary)
        }
    }
}

private struct ScheduleClockPicker: View {
    @Binding var selection: String
    @Environment(\.dismiss) private var dismiss
    @State private var hour: Int
    @State private var minute: Int
    @State private var second: Int
    @State private var keepFraction = true
    private let fraction: String

    init(selection: Binding<String>) {
        _selection = selection
        let parts = selection.wrappedValue.prefix(8).split(separator: ":")
        _hour = State(initialValue: Int(parts.first ?? "") ?? 8)
        _minute = State(initialValue: parts.count > 1 ? Int(parts[1]) ?? 0 : 0)
        _second = State(initialValue: parts.count > 2 ? Int(parts[2]) ?? 0 : 0)
        fraction = selection.wrappedValue.dropFirst(8).description
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                HStack(spacing: 0) {
                    wheel(LocalizedStringKey("小时"), value: $hour, count: 24)
                    wheel(LocalizedStringKey("分钟"), value: $minute, count: 60)
                    wheel(LocalizedStringKey("秒"), value: $second, count: 60)
                }
                .padding(.horizontal, 20)
                if fraction.dropFirst().contains(where: { $0 != "0" }) {
                    Toggle("保留毫秒", isOn: $keepFraction).padding(.horizontal, 24)
                }
            }
            .navigationTitle("时间")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("确定") {
                        selection = String(format: "%02d:%02d:%02d", hour, minute, second) + (keepFraction ? fraction : "")
                        dismiss()
                    }
                }
            }
        }
    }

    private func wheel(_ title: LocalizedStringKey, value: Binding<Int>, count: Int) -> some View {
        VStack(spacing: 0) {
            Text(title).appFont(.caption).foregroundStyle(.secondary)
            Picker(title, selection: value) {
                ForEach(0..<count, id: \.self) { value in
                    Text(String(format: "%02d", value)).monospacedDigit().tag(value)
                }
            }
            .pickerStyle(.wheel)
            .labelsHidden()
            .frame(maxWidth: .infinity)
            .frame(height: 220)
            .clipped()
        }
    }
}

private struct ScheduleTimeZonePicker: View {
    @Binding var selection: String
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    private var zones: [String] {
        let values = ["UTC", TimeZone.current.identifier] + TimeZone.knownTimeZoneIdentifiers.filter { $0.contains("/") }.sorted()
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted && (query.isEmpty || $0.localizedCaseInsensitiveContains(query)) }
    }
    var body: some View {
        NavigationStack {
            List(zones, id: \.self) { zone in
                Button { selection = zone; dismiss() } label: {
                    HStack(spacing: 12) { ScheduleSelectionMark(isSelected: zone == selection); Text(zone); Spacer() }
                }
                .foregroundStyle(.primary)
            }
            .searchable(text: $query, prompt: Text("搜索时区"))
            .navigationTitle("时区").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } } }
        }
        .background(ScheduleKeyboardDismissProbe())
    }
}

private enum ScheduledTaskFormat {
    static func shortRule(_ task: ScheduledTask) -> String {
        switch task.kind {
        case "daily": String(localized: "每天")
        case "weekly": String(localized: "每周")
        case "every": String(
            localized: "schedule.every.short",
            defaultValue: "每 \(duration(Int(task.everySeconds ?? 0)))"
        )
        case "cron": "Cron"
        case "rrule": recurrenceLabel(ScheduleTimingForm(task: task))
        default: String(localized: "一次")
        }
    }

    private static func recurrenceLabel(_ form: ScheduleTimingForm) -> String {
        let labels = ["once": String(localized: "一次"), "minutely": String(localized: "每分钟"), "hourly": String(localized: "每小时"),
            "daily": String(localized: "每天"), "weekly": String(localized: "每周"), "monthly": String(localized: "每月")]
        guard let label = labels[form.frequency] else { return String(localized: "自定义") }
        if form.interval == "1" { return label }
        return String(localized: "schedule.repeat.interval", defaultValue: "每 \(form.interval) \(intervalUnit(form.frequency))执行")
    }

    static func intervalUnit(_ frequency: String) -> String {
        switch frequency {
        case "minutely": String(localized: "分钟")
        case "hourly": String(localized: "小时")
        case "daily": String(localized: "天")
        case "weekly": String(localized: "周")
        case "monthly": String(localized: "个月")
        default: ""
        }
    }

    static func fullRule(_ task: ScheduledTask) -> String {
        let time = task.clock ?? ""
        let zone = task.timeZone ?? ""
        switch task.kind {
        case "daily":
            return String(localized: "schedule.daily.detail", defaultValue: "每天 \(time) · \(zone)")
        case "weekly":
            let days = (task.weekdays?.arrayValue ?? []).compactMap { $0.doubleValue.map(Int.init) }
            let names = [
                String(localized: "周一"), String(localized: "周二"), String(localized: "周三"),
                String(localized: "周四"), String(localized: "周五"), String(localized: "周六"),
                String(localized: "周日")
            ]
            let separator = String(localized: "schedule.weekday.separator", defaultValue: "、")
            let weekdays = days.compactMap { (1...7).contains($0) ? names[$0 - 1] : nil }.joined(separator: separator)
            return String(localized: "schedule.weekly.detail", defaultValue: "每周 \(weekdays) · \(time) · \(zone)")
        case "every":
            return String(localized: "schedule.every.detail", defaultValue: "每 \(duration(Int(task.everySeconds ?? 0)))执行一次")
        case "cron":
            return "\(task.expression ?? "") · \(zone)"
        case "after":
            let seconds = Int(task.raw["afterSeconds"]?.doubleValue ?? 0)
            return String(localized: "schedule.after.detail", defaultValue: "\(seconds) 秒后执行一次")
        case "at":
            return String(localized: "指定时间执行一次")
        case "rrule":
            return (task.raw["timing"] ?? task.raw)["rrule"]?.stringValue ?? ""
        default:
            return task.raw.displayText
        }
    }

    private static func duration(_ seconds: Int) -> String {
        let formatter = DateComponentsFormatter()
        formatter.unitsStyle = .full
        formatter.maximumUnitCount = 1
        formatter.allowedUnits = seconds > 0 && seconds % 86_400 == 0 ? .day
            : seconds > 0 && seconds % 3_600 == 0 ? .hour
            : seconds > 0 && seconds % 60 == 0 ? .minute : .second
        return formatter.string(from: TimeInterval(seconds)) ?? String(localized: "schedule.duration.seconds", defaultValue: "\(seconds) 秒")
    }

    static func date(_ value: String) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let parsed = formatter.date(from: value)
        formatter.formatOptions = [.withInternetDateTime]
        guard let date = parsed ?? formatter.date(from: value) else { return value }
        let display = DateFormatter()
        display.locale = .current
        display.calendar = Calendar(identifier: .gregorian)
        display.dateFormat = String(localized: "schedule.date.pattern", defaultValue: "yyyy年M月d日 HH:mm")
        return display.string(from: date)
    }
}

/// Immutable route chrome. Keeping this separate from `AppStore` is what makes
/// the title and trailing items enter in the same navigation transition as the
/// system back button.
private struct ConversationNavigationHeader: Hashable {
    let sessionID: String?
    let title: String
    let agentPresetTitle: String
    var initialDraft: String = ""
}

private struct WorkspaceFilesPresentation: Identifiable {
    let id = UUID()
    let sessionID: String
    let initialPath: String?
}

/// Owns only navigation chrome. The content below it may observe the complete
/// app store and update at WebSocket frequency without invalidating the toolbar.
private struct ConversationNavigationShell<Content: View>: View {
    let header: ConversationNavigationHeader
    let gateway: GatewayClient
    let store: AppStore
    let sidebarActions: ConversationSidebarActions
    let onActivate: () async -> Void
    @ViewBuilder let content: (Binding<Int>, CGFloat) -> Content
    @State private var activeView = 0
    @State private var workspaceFilesPresentation: WorkspaceFilesPresentation?
    @State private var liveTitle: String?
    @State private var livePresetTitle: String?
    @State private var showsTeamEntry = false
    @State private var showsSidebar = false
    @State private var sidebarAvailableHeight: CGFloat = 0
    @State private var compactHeader = false

    var body: some View {
        GeometryReader { geometry in
            // 视口可滚入导航栏和状态栏后方，首屏的可读边界仍由内容 inset 保留。
            content($activeView, geometry.safeAreaInsets.top)
                .ignoresSafeArea(.container, edges: .top)
        }
            .onGeometryChange(for: CGSize.self) { geometry in
                geometry.size
            } action: { size in
                sidebarAvailableHeight = size.height
                compactHeader = size.width < 600
            }
            .navigationTitle("")
            .toolbar {
                if !compactHeader {
                    ToolbarItem(placement: .topBarLeading) { sidebarButton }
                }
                if #available(iOS 26.0, *) {
                    ToolbarItem(placement: .principal) { pageTitle }
                        .sharedBackgroundVisibility(.hidden)
                } else {
                    ToolbarItem(placement: .principal) { pageTitle }
                }
            }
            .onChangeCompat(of: header.sessionID) { _, _ in
                showsSidebar = false
                liveTitle = nil
                livePresetTitle = nil
            }
            .onDisappear {
                showsSidebar = false
            }
            .onReceive(store.$sessions) { sessions in
                let sessionID = header.sessionID ?? store.selectedSessionId
                liveTitle = sessions.first { $0.id == sessionID }?.title
            }
            .onReceive(store.$sessionAgentPreset) { state in
                guard state.sessionId == (header.sessionID ?? store.selectedSessionId), let id = state.agentPreset else { return }
                livePresetTitle = state.presets.first { $0.id == id }?.name ?? L10n.presetModeName(for: id)
            }
            .onReceive(store.$teamSnapshot) { snapshot in
                showsTeamEntry = snapshot.visible
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbarRole(.editor)
            .toolbarBackground(.hidden, for: .navigationBar)
            .modifier(ConversationScrollEdgeStyle())
            .toolbar {
                if #available(iOS 26.0, *) {
                    if !compactHeader {
                        ToolbarItem(placement: .topBarTrailing) {
                            conversationStatus
                                .padding(.trailing, showsTeamEntry ? -8 : 0)
                        }
                        .sharedBackgroundVisibility(.hidden)
                    }
                    if showsTeamEntry {
                        ToolbarItem(placement: .topBarTrailing) {
                            TeamNavigationEntry(store: store)
                                .fixedSize(horizontal: true, vertical: false)
                        }
                        .sharedBackgroundVisibility(.hidden)
                        ToolbarSpacer(.fixed, placement: .topBarTrailing)
                    }
                    if compactHeader {
                        ToolbarItem(placement: .topBarTrailing) { sidebarButton }
                        ToolbarSpacer(.fixed, placement: .topBarTrailing)
                    }
                    ToolbarItem(placement: .topBarTrailing) {
                        moreMenu
                    }
                } else {
                    if !compactHeader {
                        ToolbarItem(placement: .topBarTrailing) { conversationStatus }
                    }
                    if showsTeamEntry {
                        ToolbarItem(placement: .topBarTrailing) {
                            TeamNavigationEntry(store: store)
                        }
                    }
                    if compactHeader {
                        ToolbarItem(placement: .topBarTrailing) { sidebarButton }
                    }
                    ToolbarItem(placement: .topBarTrailing) {
                        moreMenu
                    }
                }
            }
            .task(id: header.sessionID ?? "__new-conversation__") {
                // Finish the NavigationStack transaction before beginning any
                // subscription, history, or session-control work.
                await Task.yield()
                guard !Task.isCancelled else { return }
                await onActivate()
            }
            .onReceive(store.$conversationDirectoryPath) { path in
                guard let path, let sessionID = store.selectedSessionId,
                      store.conversationDirectorySessionID == sessionID else { return }
                workspaceFilesPresentation = WorkspaceFilesPresentation(sessionID: sessionID, initialPath: path)
            }
            .sheet(item: $workspaceFilesPresentation, onDismiss: { store.conversationDirectoryPath = nil }) { request in
                WorkspaceFilesSheet(store: store, sessionID: request.sessionID, initialPath: request.initialPath)
            }
    }

    private var sidebarButton: some View {
        Button {
            UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
            showsSidebar.toggle()
        } label: {
            Image(systemName: "sidebar.left")
                .resizable()
                .scaledToFit()
                .frame(width: 22, height: 22)
        }
        .accessibilityLabel("展开侧边栏")
        .accessibilityIdentifier("conversation-sidebar-toggle")
        .popover(isPresented: $showsSidebar, arrowEdge: .top) {
            if #available(iOS 16.4, *) {
                sidebarPanel.presentationCompactAdaptation(.popover)
            } else {
                sidebarPanel
            }
        }
    }

    private var sidebarPanel: some View {
        ConversationSidebar(store: store, actions: dismissingSidebarActions)
            .frame(idealHeight: sidebarAvailableHeight, maxHeight: sidebarAvailableHeight)
    }

    private var pageTitle: some View {
        ConversationNavigationTitle(
            title: liveTitle ?? header.title,
            gateway: gateway,
            agentPresetTitle: livePresetTitle ?? header.agentPresetTitle,
            selectedPage: activeView,
            compact: compactHeader
        )
    }

    /// 先关闭悬浮面板，再复用现有会话切换或导航操作；不改变正文宽度。
    private var dismissingSidebarActions: ConversationSidebarActions {
        ConversationSidebarActions(
            openSession: { session in
                showsSidebar = false
                sidebarActions.openSession(session)
            },
            openPlugins: {
                showsSidebar = false
                sidebarActions.openPlugins()
            },
            openScheduledTasks: {
                showsSidebar = false
                sidebarActions.openScheduledTasks()
            },
            openSettings: {
                showsSidebar = false
                sidebarActions.openSettings()
            }
        )
    }

    private var moreMenu: some View {
        Menu {
            Button(String(localized: "工作区文件"), systemImage: "folder") {
                guard let sessionID = store.selectedSessionId else { return }
                workspaceFilesPresentation = WorkspaceFilesPresentation(sessionID: sessionID, initialPath: nil)
            }
            .disabled(header.sessionID == nil)
        } label: {
            Image(systemName: "ellipsis")
                .resizable()
                .scaledToFit()
                .frame(width: 22, height: 22)
        }
        .accessibilityLabel("更多")
    }

    private var conversationStatus: some View {
        ConversationNavigationStatus(gateway: gateway, agentPresetTitle: livePresetTitle ?? header.agentPresetTitle)
    }

}

private struct WorkspaceFilesSheet: View {
    @ObservedObject var store: AppStore
    let sessionID: String?
    var initialPath: String? = nil
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @State private var downloadedPaths: Set<String> = []

    var body: some View {
        NavigationStack {
            Group {
                if store.workspaceFilesAreLoading && store.workspaceFileEntries.isEmpty {
                    ProgressView("正在读取工作区文件…")
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if store.workspaceFileEntries.isEmpty {
                    DshEmptyState(
                        String(localized: "此目录为空"),
                        systemImage: "folder",
                        description: Text(store.workspaceFilePath)
                    )
                } else {
                    List(store.workspaceFileEntries) { item in
                        WorkspaceFileRow(
                            store: store,
                            item: item,
                            isDownloaded: downloadedPaths.contains(item.path)
                        )
                    }
                    .listStyle(.plain)
                    .disabled(store.workspaceFilesAreLoading)
                    .refreshable { store.browseWorkspaceFiles(path: store.workspaceFilePath) }
                }
            }
            .navigationTitle("工作区文件")
            .navigationBarTitleDisplayMode(.inline)
            .safeAreaInset(edge: .top, spacing: 0) {
                workspaceFilePathBar
            }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    if store.workspaceFilePath != "." {
                        Button("上一级", systemImage: "chevron.left") {
                            store.browseWorkspaceFiles(path: parentPath(of: store.workspaceFilePath))
                        }
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("完成") { dismiss() }
                }
            }
            .overlay(alignment: .bottom) {
                if store.workspaceFileDownloadPurpose == "download",
                   let progress = store.workspaceFileDownloadProgress {
                    VStack(spacing: 9) {
                        ProgressView(value: progress)
                        HStack {
                            Text(String(localized: "download.progress", defaultValue: "正在下载 · \(Int(progress * 100))%"))
                                .appFont(.caption.monospacedDigit())
                                .foregroundStyle(.secondary)
                            Spacer()
                            Button("取消", role: .cancel) { store.cancelWorkspaceFileDownload() }
                        }
                    }
                    .padding(16)
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                    .padding()
                }
            }
        }
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
        .task(id: sessionID) {
            guard sessionID != nil else { return }
            store.browseWorkspaceFiles(path: initialPath)
            refreshDownloadedPaths()
        }
        .onChangeCompat(of: store.workspaceFileEntries) { _, _ in
            refreshDownloadedPaths()
        }
        .onChangeCompat(of: scenePhase) { _, phase in
            if phase == .active { refreshDownloadedPaths() }
        }
        .onDisappear {
            if store.workspaceFileDownloadProgress != nil { store.cancelWorkspaceFileDownload() }
        }
        .sheet(item: previewBinding) { request in
            WorkspaceFilePreviewLoadingSheet(store: store, request: request) {
                if request.sessionID == sessionID,
                   let file = store.completedWorkspaceFile { downloadedPaths.insert(file.remotePath) }
            }
        }
        .sheet(item: exportBinding) { file in
            WorkspaceFileExporter(url: file.url) { localURL in
                WorkspaceDownloadRegistry.shared.record(
                    sessionID: "\(store.gatewayLocalID):\(file.sessionID)",
                    remotePath: file.remotePath,
                    localURL: localURL
                )
                if file.sessionID == sessionID {
                    downloadedPaths.insert(file.remotePath)
                }
            }
        }
    }

    private var workspaceFilePathBar: some View {
        HStack(spacing: 8) {
            Image(systemName: "folder")
            Text(store.workspaceFilePath == "." ? String(localized: "工作区根目录") : store.workspaceFilePath)
                .lineLimit(1)
                .truncationMode(.middle)
            Spacer()
            if store.workspaceFilesAreLoading { ProgressView().controlSize(.small) }
        }
        .appFont(.subheadline)
        .foregroundStyle(.secondary)
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(.thinMaterial)
    }

    private var previewBinding: Binding<WorkspaceFilePreviewRequest?> {
        Binding(
            get: {
                guard !store.conversationFilePreviewRequested,
                      store.workspaceFilePreviewRequest?.sessionID == sessionID else { return nil }
                return store.workspaceFilePreviewRequest
            },
            set: { if $0 == nil { store.cancelWorkspaceFileDownload() } }
        )
    }

    private var exportBinding: Binding<WorkspaceLocalFile?> {
        Binding(
            get: { store.completedWorkspaceFile?.purpose == "download" ? store.completedWorkspaceFile : nil },
            set: { if $0 == nil { store.completedWorkspaceFile = nil } }
        )
    }

    private func parentPath(of path: String) -> String? {
        let components = path.split(separator: "/").dropLast()
        return components.isEmpty ? nil : components.joined(separator: "/")
    }

    private func refreshDownloadedPaths() {
        guard let sessionID else {
            downloadedPaths = []
            return
        }
        downloadedPaths = WorkspaceDownloadRegistry.shared.existingRemotePaths(
            sessionID: "\(store.gatewayLocalID):\(sessionID)",
            remotePaths: store.workspaceFileEntries
                .filter { $0.kind == "file" }
                .map(\.path)
        )
    }
}

private struct WorkspaceFileRow: View {
    @ObservedObject var store: AppStore
    let item: GatewayDirectoryItem
    let isDownloaded: Bool

    private var isDownloading: Bool {
        store.workspaceFileDownloadPurpose == "download" &&
            store.workspaceFileDownloadPath == item.path
    }

    var body: some View {
        if item.kind == "directory" {
            Button {
                store.browseWorkspaceFiles(path: item.path)
            } label: {
                Label {
                    Text(item.name).foregroundStyle(.primary)
                } icon: {
                    Image(systemName: "folder.fill").foregroundStyle(DSHColor.ocean)
                }
            }
        } else {
            HStack(spacing: 12) {
                Button {
                    store.openWorkspaceFile(item, purpose: "preview")
                } label: {
                    HStack(spacing: 12) {
                        FileTypeIcon(kind: FileIconCatalog.shared.resolve(name: item.name, mediaType: item.mediaType), size: 40)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(item.name)
                                .foregroundStyle(.primary)
                                .lineLimit(1)
                            Text(workspaceFileDetail(item))
                                .appFont(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                        Spacer()
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                Button {
                    store.openWorkspaceFile(item, purpose: "download")
                } label: {
                    workspaceDownloadIcon
                }
                .buttonStyle(.plain)
                .disabled(isDownloading)
                .accessibilityLabel(
                    isDownloaded
                        ? String(localized: "\(item.name) 已下载，点击重新下载")
                        : String(localized: "下载 \(item.name)")
                )
            }
            .listRowBackground(FileIconCatalog.shared.resolve(name: item.name, mediaType: item.mediaType).cardBackground)
        }
    }

    @ViewBuilder
    private var workspaceDownloadIcon: some View {
        if isDownloading, let progress = store.workspaceFileDownloadProgress {
            ProgressView(value: progress)
                .progressViewStyle(.circular)
                .frame(width: 24, height: 24)
                .overlay {
                    Image(systemName: "arrow.down")
                        .appFont(.system(size: 9, weight: .semibold))
                }
        } else {
            Image(systemName: isDownloaded ? "checkmark.circle" : "arrow.down.circle")
                .appFont(.title3)
                .foregroundStyle(isDownloaded ? DSHColor.success : Color.primary)
        }
    }

    private func workspaceFileDetail(_ item: GatewayDirectoryItem) -> String {
        let size = item.bytes.map {
            ByteCountFormatter.string(fromByteCount: $0, countStyle: .file)
        } ?? String(localized: "文件")
        guard let modifiedAt = item.modifiedAt else { return size }
        let date = Date(timeIntervalSince1970: modifiedAt / 1_000)
        return "\(size) · \(date.formatted(date: .abbreviated, time: .shortened))"
    }

}

/// 同一个 Sheet 从加载切换为预览；关闭即停止当前文件请求。
struct WorkspaceFilePreviewLoadingSheet: View {
    @ObservedObject var store: AppStore
    let request: WorkspaceFilePreviewRequest
    var onSaved: (() -> Void)? = nil
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        Group {
            if let file = store.completedWorkspaceFile,
               file.purpose == "preview", file.sessionID == request.sessionID {
                WorkspaceFilePreview(file: file, gatewayID: store.gatewayLocalID, onSaved: onSaved)
            } else {
                VStack(spacing: 0) {
                    WorkspaceFilePreviewHeader(fileName: request.name, onClose: close, onSave: nil)
                    VStack(spacing: 12) {
                        if store.conversationFileIsOpening {
                            Text("正在加载中")
                            WorkspaceLoadingLogo()
                            Text(store.workspaceFilePreviewProgress, format: .percent.precision(.fractionLength(0)))
                                .appFont(.caption.monospacedDigit())
                                .accessibilityIdentifier("workspace-preview-progress")
                        } else {
                            Text(store.workspaceFilePreviewError ?? String(localized: "文件加载失败"))
                                .multilineTextAlignment(.center)
                            Button(String(localized: "重试")) { store.retryWorkspaceFilePreview() }
                                .buttonStyle(.borderedProminent)
                                .accessibilityIdentifier("workspace-preview-retry")
                        }
                    }
                    .foregroundStyle(.secondary)
                    .padding(24)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .accessibilityIdentifier("workspace-preview-loading")
                }
                .background(Color(uiColor: .systemBackground))
            }
        }
        .presentationDetents([.large])
        .presentationDragIndicator(.hidden)
        .onDisappear {
            // 保存面板等子页面不结束外层预览；关闭按钮和交互式关闭均由绑定取消请求。
            if store.workspaceFilePreviewRequest?.id == request.id,
               store.conversationFileIsOpening { store.cancelWorkspaceFileDownload() }
        }
    }

    private func close() {
        store.cancelWorkspaceFileDownload()
        dismiss()
    }
}

/// 聊天和工作区共用的预览容器，Header 始终位于可滚动内容上方。
struct WorkspaceFilePreview: View {
    let file: WorkspaceLocalFile
    let gatewayID: String
    var onSaved: (() -> Void)? = nil
    @Environment(\.dismiss) private var dismiss
    @State private var showsExporter = false
    @State private var usesSystemPreview = false

    var body: some View {
        previewWithHeader
        .background(Color(uiColor: .systemBackground))
        .presentationDetents([.large])
        .presentationDragIndicator(.hidden)
        .sheet(isPresented: $showsExporter) {
            WorkspaceFileExporter(url: file.url, onCancelled: { showsExporter = false }, onExported: { localURL in
                showsExporter = false
                WorkspaceDownloadRegistry.shared.record(
                    sessionID: "\(gatewayID):\(file.sessionID)",
                    remotePath: file.remotePath,
                    localURL: localURL
                )
                onSaved?()
            })
        }
    }

    @ViewBuilder
    private var previewWithHeader: some View {
        if #available(iOS 26.0, *) {
            // 保留真实滚动内容的磨砂效果，不复制或镜像文档边缘。
            previewContent.safeAreaBar(edge: .top, spacing: 0) { header }
        } else {
            previewContent.safeAreaInset(edge: .top, spacing: 0) { header }
        }
    }

    private var header: some View {
        WorkspaceFilePreviewHeader(
            fileName: file.name,
            onClose: { dismiss() },
            onSave: { showsExporter = true }
        )
    }

    private var previewContent: some View {
        Group {
            if !usesSystemPreview,
               WorkspaceCodePreviewSupport.shared.isSupported(name: file.name, mediaType: file.mediaType) {
                WorkspaceCodePreview(file: file, onOpenSystem: { usesSystemPreview = true })
            } else {
                WorkspaceQuickLookPreview(url: file.url)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .ignoresSafeArea(.container, edges: .bottom)
    }

}

struct WorkspaceFilePreviewHeader: View {
    let fileName: String
    let onClose: () -> Void
    let onSave: (() -> Void)?

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            Button(action: onClose) {
                Image(systemName: "xmark")
                    .font(.system(size: 17, weight: .semibold))
                    .frame(width: 44, height: 44)
            }
            .glassCircleButton()
            .accessibilityLabel("关闭文件预览")
            .accessibilityIdentifier("workspace-preview-close")

            Text(fileName)
                .appFont(.headline)
                .multilineTextAlignment(.center)
                .lineLimit(nil)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity)
                .accessibilityAddTraits(.isHeader)
                .accessibilityIdentifier("workspace-preview-title")

            Menu {
                Button("保存", systemImage: "square.and.arrow.down") { onSave?() }
                    .disabled(onSave == nil)
                    .accessibilityIdentifier("workspace-preview-save")
            } label: {
                Image(systemName: "ellipsis")
                    .font(.system(size: 19, weight: .semibold))
                    .frame(width: 44, height: 44)
            }
            .glassCircleButton()
            .accessibilityLabel("文件菜单")
            .accessibilityIdentifier("workspace-preview-menu")
        }
        .foregroundStyle(.primary)
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(.ultraThinMaterial)
    }
}

struct WorkspaceQuickLookPreview: UIViewControllerRepresentable {
    let url: URL

    func makeCoordinator() -> Coordinator { Coordinator(url: url) }
    func makeUIViewController(context: Context) -> QLPreviewController {
        let controller = QLPreviewController()
        controller.dataSource = context.coordinator
        return controller
    }
    func updateUIViewController(_ controller: QLPreviewController, context: Context) {}

    final class Coordinator: NSObject, QLPreviewControllerDataSource {
        let url: URL
        init(url: URL) {
            self.url = url
            WorkspaceFileLifetime.shared.retain(url)
        }
        deinit {
            WorkspaceFileLifetime.shared.release(url, deleteTemporary: url.path.hasPrefix(WorkspacePreviewCache.temporaryRoot.path + "/"))
        }
        func numberOfPreviewItems(in controller: QLPreviewController) -> Int { 1 }
        func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> QLPreviewItem {
            url as NSURL
        }
    }
}

struct WorkspaceCodePreview: View {
    let file: WorkspaceLocalFile
    let onOpenSystem: () -> Void
    @State private var document: WorkspaceCodeDocument?
    @State private var errorMessage: String?

    private let maximumPreviewBytes = 2 * 1_024 * 1_024

    var body: some View {
        Group {
            if let document {
                WorkspaceCodeDocumentView(document: document)
            } else if let errorMessage {
                DshEmptyState {
                    Label("无法在应用内预览", systemImage: "doc.text.magnifyingglass")
                } description: {
                    Text(errorMessage)
                } actions: {
                    Button("使用系统预览", action: onOpenSystem)
                }
            } else {
                ProgressView("正在准备代码预览…")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .task(id: file.id) { await loadDocument() }
    }

    @MainActor
    private func loadDocument() async {
        document = nil
        errorMessage = nil
        let url = file.url
        let name = file.name
        let mediaType = file.mediaType
        let maximumBytes = maximumPreviewBytes
        do {
            let loaded = try await Task.detached(priority: .userInitiated) {
                let values = try url.resourceValues(forKeys: [.fileSizeKey])
                if let size = values.fileSize, size > maximumBytes {
                    throw WorkspaceCodePreviewLoadError.fileTooLarge
                }
                let source = try String(contentsOf: url, encoding: .utf8)
                return SendableWorkspaceCodeDocument(
                    WorkspaceCodePreviewSupport.shared.prepare(
                        source: source,
                        name: name,
                        mediaType: mediaType
                    )
                )
            }.value
            guard !Task.isCancelled else { return }
            document = loaded.value
        } catch WorkspaceCodePreviewLoadError.fileTooLarge {
            errorMessage = String(localized: "文件超过 2 MB，请使用系统应用打开")
        } catch {
            guard !Task.isCancelled else { return }
            errorMessage = String(localized: "读取代码失败：\(error.localizedDescription)")
        }
    }
}

private enum WorkspaceCodePreviewLoadError: Error {
    case fileTooLarge
}

private struct SendableWorkspaceCodeDocument: @unchecked Sendable {
    let value: WorkspaceCodeDocument

    init(_ value: WorkspaceCodeDocument) {
        self.value = value
    }
}

private struct WorkspaceCodeDocumentView: View {
    let document: WorkspaceCodeDocument
    @Environment(\.colorScheme) private var colorScheme
    @State private var previewsMarkdown = false
    @State private var hasRenderedMarkdown = false

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text(document.languageDisplayName)
                    .appFont(.caption.weight(.semibold))
                    .foregroundStyle(DSHColor.ocean)
                Spacer()
                Text("\(document.lineCount) 行 · UTF-8")
                    .appFont(.caption)
                    .foregroundStyle(.secondary)
            }
            .padding(.horizontal, 14)
            .frame(minHeight: 38)
            .background(Color(uiColor: .secondarySystemBackground))

            if document.language == "markdown" {
                Picker("Markdown", selection: $previewsMarkdown) {
                    Text("源码").tag(false)
                    Text("预览").tag(true)
                }
                .pickerStyle(.segmented)
                .frame(maxWidth: 320)
                .padding(.horizontal, 14)
                .padding(.bottom, 10)
                .frame(maxWidth: .infinity)
                .background(Color(uiColor: .secondarySystemBackground))
                .accessibilityIdentifier("workspace-markdown-mode")
            }

            ZStack {
                WorkspaceCodeTextKitView(document: document, colorScheme: colorScheme)
                    .opacity(previewsMarkdown ? 0 : 1)
                    .allowsHitTesting(!previewsMarkdown)
                    .accessibilityHidden(previewsMarkdown)

                // 首次切换时再解析 Markdown；保留两种视图以保存各自的滚动位置。
                if previewsMarkdown || hasRenderedMarkdown {
                    ScrollView {
                        MarkdownContent(document.text)
                            .textSelection(.enabled)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(18)
                    }
                    .background(Color(uiColor: .systemBackground))
                    .opacity(previewsMarkdown ? 1 : 0)
                    .allowsHitTesting(previewsMarkdown)
                    .accessibilityHidden(!previewsMarkdown)
                    .accessibilityIdentifier("workspace-markdown-preview")
                    .onAppear { hasRenderedMarkdown = true }
                }
            }
        }
    }
}

private struct WorkspaceCodeTextKitView: UIViewRepresentable {
    @AppStorage(AppFontScale.key) private var fontScale = 1.0
    @AppStorage(AppFontScale.followsSystemKey) private var fontFollowsSystem = false
    let document: WorkspaceCodeDocument
    let colorScheme: ColorScheme

    func makeUIView(context: Context) -> WorkspaceCodeTextKitContainer {
        let view = WorkspaceCodeTextKitContainer()
        view.configure(document: document, isDark: colorScheme == .dark)
        return view
    }

    func updateUIView(_ view: WorkspaceCodeTextKitContainer, context: Context) {
        view.configure(document: document, isDark: colorScheme == .dark)
    }
}

final class WorkspaceCodeTextKitContainer: UIView, UIScrollViewDelegate {
    private let gutterView = UIView()
    private var gutterWidthConstraint: NSLayoutConstraint?
    private let lineNumberLabel = UILabel()
    private let codeScrollView = UIScrollView()
    private let codeTextView = UITextView(frame: .zero, textContainer: nil)
    private var codeFont: UIFont { UIFont.appMonospacedFont(ofSize: 13, weight: .regular) }
    private let lineSpacing: CGFloat = 4
    private var lineCount = 1
    private var maximumLineWidth: CGFloat = 0
    private var measuredTextHeight: CGFloat = 0
    private var configuredRevision: String?

    override init(frame: CGRect) {
        super.init(frame: frame)
        clipsToBounds = true

        gutterView.translatesAutoresizingMaskIntoConstraints = false
        gutterView.clipsToBounds = true
        addSubview(gutterView)

        lineNumberLabel.numberOfLines = 0
        lineNumberLabel.textAlignment = .right
        gutterView.addSubview(lineNumberLabel)

        codeScrollView.translatesAutoresizingMaskIntoConstraints = false
        codeScrollView.delegate = self
        codeScrollView.alwaysBounceVertical = true
        codeScrollView.showsHorizontalScrollIndicator = true
        codeScrollView.contentInsetAdjustmentBehavior = .never
        codeScrollView.accessibilityIdentifier = "workspace-code-scroll"
        addSubview(codeScrollView)

        codeTextView.isEditable = false
        codeTextView.isSelectable = true
        // 保留 UITextView 的文字布局机制，拖动统一交给外层双轴视口。
        codeTextView.isScrollEnabled = true
        codeTextView.panGestureRecognizer.isEnabled = false
        codeTextView.showsVerticalScrollIndicator = false
        codeTextView.showsHorizontalScrollIndicator = false
        codeTextView.accessibilityIdentifier = "workspace-code-text"
        codeTextView.contentInsetAdjustmentBehavior = .never
        codeTextView.contentInset = .zero
        codeTextView.textContainerInset = UIEdgeInsets(top: 14, left: 14, bottom: 24, right: 28)
        codeTextView.textContainer.lineFragmentPadding = 0
        codeTextView.textContainer.widthTracksTextView = false
        codeTextView.textContainer.heightTracksTextView = false
        codeTextView.textContainer.lineBreakMode = .byClipping
        codeTextView.layoutManager.allowsNonContiguousLayout = true
        codeScrollView.addSubview(codeTextView)

        let gutterWidth = gutterView.widthAnchor.constraint(equalToConstant: 52)
        gutterWidthConstraint = gutterWidth
        NSLayoutConstraint.activate([
            gutterView.leadingAnchor.constraint(equalTo: leadingAnchor),
            gutterView.topAnchor.constraint(equalTo: topAnchor),
            gutterView.bottomAnchor.constraint(equalTo: bottomAnchor),
            gutterWidth,
            codeScrollView.leadingAnchor.constraint(equalTo: gutterView.trailingAnchor),
            codeScrollView.trailingAnchor.constraint(equalTo: trailingAnchor),
            codeScrollView.topAnchor.constraint(equalTo: topAnchor),
            codeScrollView.bottomAnchor.constraint(equalTo: bottomAnchor)
        ])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func configure(document: WorkspaceCodeDocument, isDark: Bool) {
        let revision = "\(document.text.hashValue)-\(document.tokens.count)-\(isDark)-\(AppFontScale.current)"
        guard configuredRevision != revision else { return }
        configuredRevision = revision
        lineCount = max(1, Int(document.lineCount))
        measuredTextHeight = CGFloat(lineCount) * codeFont.lineHeight + CGFloat(lineCount - 1) * lineSpacing
        let numberWidth = (String(lineCount) as NSString).size(withAttributes: [.font: codeFont]).width
        gutterWidthConstraint?.constant = max(52, ceil(numberWidth) + 24)

        let codeBackground = isDark
            ? UIColor(red: 0.05, green: 0.07, blue: 0.09, alpha: 1)
            : UIColor(red: 0.98, green: 0.985, blue: 0.99, alpha: 1)
        let gutterBackground = isDark
            ? UIColor(red: 0.086, green: 0.106, blue: 0.133, alpha: 1)
            : UIColor.secondarySystemBackground
        backgroundColor = codeBackground
        codeTextView.backgroundColor = codeBackground
        codeScrollView.backgroundColor = codeBackground
        gutterView.backgroundColor = gutterBackground
        codeTextView.tintColor = UIColor(DSHColor.ocean)
        codeTextView.attributedText = WorkspaceCodeAttributedText.highlight(document, isDark: isDark)
        codeTextView.textContainer.widthTracksTextView = false
        codeTextView.textContainer.lineBreakMode = .byClipping

        let paragraph = NSMutableParagraphStyle()
        paragraph.alignment = .right
        paragraph.lineSpacing = lineSpacing
        lineNumberLabel.attributedText = NSAttributedString(
            string: (1...lineCount).map(String.init).joined(separator: "\n"),
            attributes: [
                .font: codeFont,
                .foregroundColor: UIColor.tertiaryLabel,
                .paragraphStyle: paragraph
            ]
        )
        maximumLineWidth = document.text
            .split(separator: "\n", omittingEmptySubsequences: false)
            .reduce(CGFloat.zero) { width, line in
                max(
                    width,
                    (String(line) as NSString).size(withAttributes: [.font: codeFont]).width
                )
            }
        setNeedsLayout()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let insets = codeTextView.textContainerInset
        let visibleWidth = max(1, codeScrollView.bounds.width - insets.left - insets.right)
        let containerWidth = max(visibleWidth, ceil(maximumLineWidth) + 1)
        if abs(codeTextView.textContainer.size.width - containerWidth) > 0.5 {
            codeTextView.textContainer.size = CGSize(
                width: containerWidth,
                height: CGFloat.greatestFiniteMagnitude
            )
        }
        codeTextView.textContainer.widthTracksTextView = false
        codeTextView.layoutManager.ensureLayout(for: codeTextView.textContainer)
        let usedHeight = codeTextView.layoutManager.usedRect(for: codeTextView.textContainer).maxY
        // 非连续布局在隐藏/重新显示时可能暂时返回空区域，不能因此收缩滚动范围。
        measuredTextHeight = max(measuredTextHeight, ceil(usedHeight))
        let contentSize = CGSize(
            width: containerWidth + insets.left + insets.right,
            height: max(codeScrollView.bounds.height, measuredTextHeight + insets.top + insets.bottom)
        )
        codeTextView.frame = CGRect(origin: .zero, size: contentSize)
        codeScrollView.contentSize = contentSize
        if !codeScrollView.isDragging && !codeScrollView.isDecelerating {
            let offset = CGPoint(
                x: min(max(0, codeScrollView.contentOffset.x), max(0, contentSize.width - codeScrollView.bounds.width)),
                y: min(max(0, codeScrollView.contentOffset.y), max(0, contentSize.height - codeScrollView.bounds.height))
            )
            if codeScrollView.contentOffset != offset { codeScrollView.contentOffset = offset }
        }
        updateLineNumberPosition()
    }

    func scrollViewDidScroll(_ scrollView: UIScrollView) {
        guard scrollView === codeScrollView else { return }
        updateLineNumberPosition()
    }

    private func updateLineNumberPosition() {
        let rowHeight = codeFont.lineHeight + lineSpacing
        let labelHeight = CGFloat(lineCount) * codeFont.lineHeight +
            CGFloat(max(0, lineCount - 1)) * lineSpacing
        lineNumberLabel.frame = CGRect(
            x: 0,
            y: 14 - codeScrollView.contentOffset.y,
            width: gutterView.bounds.width - 12,
            height: max(labelHeight, rowHeight)
        )
    }
}

private enum WorkspaceCodeAttributedText {
    static func highlight(_ document: WorkspaceCodeDocument, isDark: Bool) -> NSAttributedString {
        let source = document.text as NSString
        let paragraph = NSMutableParagraphStyle()
        paragraph.lineSpacing = 4
        let result = NSMutableAttributedString(
            string: document.text,
            attributes: [
                .font: UIFont.appMonospacedFont(ofSize: 13, weight: .regular),
                .foregroundColor: baseColor(isDark),
                .paragraphStyle: paragraph
            ]
        )

        for token in document.tokens {
            let start = Int(token.start)
            let end = Int(token.endExclusive)
            guard start >= 0, end >= start, end <= source.length else { continue }
            result.addAttribute(
                .foregroundColor,
                value: tokenColor(token.kind, isDark: isDark),
                range: NSRange(location: start, length: end - start)
            )
        }
        return result
    }

    private static func baseColor(_ isDark: Bool) -> UIColor {
        isDark
            ? UIColor(red: 0.90, green: 0.93, blue: 0.95, alpha: 1)
            : UIColor(red: 0.12, green: 0.14, blue: 0.16, alpha: 1)
    }

    private static func tokenColor(_ kind: WorkspaceCodeTokenKind, isDark: Bool) -> UIColor {
        if kind == WorkspaceCodeTokenKind.comment {
            return isDark
                ? UIColor(red: 0.55, green: 0.58, blue: 0.62, alpha: 1)
                : UIColor(red: 0.43, green: 0.47, blue: 0.51, alpha: 1)
        }
        if kind == WorkspaceCodeTokenKind.string {
            return isDark
                ? UIColor(red: 0.65, green: 0.84, blue: 1.0, alpha: 1)
                : UIColor(red: 0.04, green: 0.48, blue: 0.24, alpha: 1)
        }
        if kind == WorkspaceCodeTokenKind.keyword {
            return isDark
                ? UIColor(red: 1.0, green: 0.48, blue: 0.45, alpha: 1)
                : UIColor(red: 0.51, green: 0.31, blue: 0.87, alpha: 1)
        }
        if kind == WorkspaceCodeTokenKind.number {
            return isDark
                ? UIColor(red: 0.47, green: 0.75, blue: 1.0, alpha: 1)
                : UIColor(red: 0.02, green: 0.31, blue: 0.68, alpha: 1)
        }
        if kind == WorkspaceCodeTokenKind.type {
            return isDark
                ? UIColor(red: 0.82, green: 0.66, blue: 1.0, alpha: 1)
                : UIColor(red: 0.58, green: 0.22, blue: 0.0, alpha: 1)
        }
        if kind == WorkspaceCodeTokenKind.tag {
            return isDark
                ? UIColor(red: 0.49, green: 0.91, blue: 0.53, alpha: 1)
                : UIColor(red: 0.81, green: 0.13, blue: 0.18, alpha: 1)
        }
        return isDark
            ? UIColor(red: 1.0, green: 0.65, blue: 0.34, alpha: 1)
            : UIColor(red: 0.58, green: 0.22, blue: 0.0, alpha: 1)
    }
}

private struct WorkspaceFileExporter: UIViewControllerRepresentable {
    let url: URL
    var onCancelled: (() -> Void)? = nil
    let onExported: (URL) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(url: url, onExported: onExported, onCancelled: onCancelled)
    }

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let controller = UIDocumentPickerViewController(forExporting: [url], asCopy: true)
        controller.delegate = context.coordinator
        return controller
    }
    func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) {}

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let onExported: (URL) -> Void
        let onCancelled: (() -> Void)?
        let sourceURL: URL

        init(url: URL, onExported: @escaping (URL) -> Void, onCancelled: (() -> Void)?) {
            sourceURL = url
            WorkspaceFileLifetime.shared.retain(url)
            self.onExported = onExported
            self.onCancelled = onCancelled
        }

        deinit {
            WorkspaceFileLifetime.shared.release(sourceURL, deleteTemporary: sourceURL.path.hasPrefix(WorkspacePreviewCache.temporaryRoot.path + "/"))
        }
        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
            onCancelled?()
        }

        func documentPicker(
            _ controller: UIDocumentPickerViewController,
            didPickDocumentsAt urls: [URL]
        ) {
            guard let url = urls.first else { return }
            onExported(url)
        }
    }
}

private struct WorkspaceDownloadLocation: Codable {
    let bookmark: Data?
    let localPath: String
}

private final class WorkspaceDownloadRegistry {
    static let shared = WorkspaceDownloadRegistry()

    private let defaults: UserDefaults
    private let storageKey = "workspaceDownloadLocations.v1"
    private var locations: [String: WorkspaceDownloadLocation]

    private init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        if let data = defaults.data(forKey: storageKey),
           let decoded = try? JSONDecoder().decode(
               [String: WorkspaceDownloadLocation].self,
               from: data
           ) {
            locations = decoded
        } else {
            locations = [:]
        }
    }

    func record(sessionID: String, remotePath: String, localURL: URL) {
        let isScoped = localURL.startAccessingSecurityScopedResource()
        defer { if isScoped { localURL.stopAccessingSecurityScopedResource() } }
        guard FileManager.default.fileExists(atPath: localURL.path) else { return }
        let bookmark = try? localURL.bookmarkData(
            options: [],
            includingResourceValuesForKeys: nil,
            relativeTo: nil
        )
        locations[identity(sessionID: sessionID, remotePath: remotePath)] =
            WorkspaceDownloadLocation(bookmark: bookmark, localPath: localURL.path)
        persist()
    }

    func existingRemotePaths(sessionID: String, remotePaths: [String]) -> Set<String> {
        var result: Set<String> = []
        var removedStaleLocation = false
        for remotePath in remotePaths {
            let key = identity(sessionID: sessionID, remotePath: remotePath)
            guard let location = locations[key] else { continue }
            if locationExists(location) {
                result.insert(remotePath)
            } else {
                locations.removeValue(forKey: key)
                removedStaleLocation = true
            }
        }
        if removedStaleLocation { persist() }
        return result
    }

    private func locationExists(_ location: WorkspaceDownloadLocation) -> Bool {
        var isStale = false
        let bookmarkedURL = location.bookmark.flatMap { bookmark in
            try? URL(
                resolvingBookmarkData: bookmark,
                options: [.withoutUI],
                relativeTo: nil,
                bookmarkDataIsStale: &isStale
            )
        }
        guard !isStale else { return false }
        let url = bookmarkedURL ?? URL(fileURLWithPath: location.localPath)
        let isScoped = url.startAccessingSecurityScopedResource()
        defer { if isScoped { url.stopAccessingSecurityScopedResource() } }
        return FileManager.default.fileExists(atPath: url.path)
    }

    private func identity(sessionID: String, remotePath: String) -> String {
        Data("\(sessionID)\u{0}\(remotePath)".utf8).base64EncodedString()
    }

    private func persist() {
        guard let data = try? JSONEncoder().encode(locations) else { return }
        defaults.set(data, forKey: storageKey)
    }
}

/// 会话标题和模式共用导航栏的标题区域，不参与侧栏的点击交互。
private struct ConversationNavigationTitle: View {
    let title: String
    let gateway: GatewayClient
    let agentPresetTitle: String
    let selectedPage: Int
    let compact: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .appFont(compact ? .system(size: 16, weight: .semibold) : .headline)
                .lineLimit(1)
                .accessibilityIdentifier("conversation-page-title")
                .accessibilityValue(selectedPage == 0 ? Text("对话") : Text("轨迹"))
            if compact {
                ConversationNavigationStatus(gateway: gateway, agentPresetTitle: agentPresetTitle, compact: true)
            }
        }
        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
        .accessibilityElement(children: .contain)
    }
}

/// Connection changes are scoped to this tiny view instead of rebuilding the
/// navigation shell (or the toolbar declaration containing it).
private struct ConversationNavigationStatus: View {
    @ObservedObject var gateway: GatewayClient
    let agentPresetTitle: String
    var compact = false

    var body: some View {
        HStack(spacing: compact ? 6 : 7) {
            ConnectionDot(state: gateway.state)
            Text(agentPresetTitle)
                .appFont(compact ? .system(size: 11, weight: .medium) : .caption.weight(.medium))
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .fixedSize(horizontal: !compact, vertical: false)
                .accessibilityIdentifier("conversation-mode-label")
        }
    }
}

private struct ScheduleCreateForm: View {
    @ObservedObject var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @State private var title = ""
    @State private var prompt = ""
    @State private var timing = "{\"kind\":\"every\",\"every_seconds\":3600}"
    @State private var target = "new_once"
    @State private var sessionID = ""
    @State private var requestID: String?
    private var nextExecutionPreview: String {
        guard let rule = try? JSONDecoder().decode(JSONValue.self, from: Data(timing.utf8)),
              let kind = rule["kind"]?.stringValue else { return String(localized: "请填写有效规则") }
        let spec = rule[kind]
        let zone = TimeZone(identifier: spec?["time_zone"]?.stringValue ?? "") ?? .current
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = zone
        let now = Date()
        var next: Date?
        if kind == "after" || kind == "every" {
            if let seconds = rule[kind == "after" ? "after_seconds" : "every_seconds"]?.doubleValue,
               seconds > 0, seconds < 315_576_000 { next = now.addingTimeInterval(seconds) }
        } else if kind == "at" {
            let formatter = DateFormatter()
            formatter.timeZone = zone
            let time = spec?["time"]?.stringValue ?? ""
            formatter.dateFormat = time.count == 5 ? "yyyy-MM-dd HH:mm" : "yyyy-MM-dd HH:mm:ss"
            next = formatter.date(from: (spec?["date"]?.stringValue ?? "") + " " + time)
        } else if kind == "daily" || kind == "weekly" {
            let clock = (spec?["time"]?.stringValue ?? "").split(separator: ":").compactMap { Int($0) }
            if clock.count >= 2, (0...23).contains(clock[0]), (0...59).contains(clock[1]) {
                let components = DateComponents(hour: clock[0], minute: clock[1], second: clock.count > 2 ? clock[2] : 0)
                let days = (spec?["weekdays"]?.arrayValue ?? []).compactMap { $0.doubleValue.map(Int.init) }
                var candidate = now
                for _ in 0...7 {
                    guard let date = calendar.nextDate(after: candidate, matching: components, matchingPolicy: .nextTime) else { break }
                    if kind == "daily" || days.contains((calendar.component(.weekday, from: date) + 5) % 7 + 1) { next = date; break }
                    candidate = date
                }
            }
        }
        guard let next else { return String(localized: "下次执行时间由 Host 校验并计算") }
        let formatter = DateFormatter()
        formatter.timeZone = zone
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss z"
        return String(localized: "schedule.next.estimate", defaultValue: "预计下次执行 · \(formatter.string(from: next))\n最终以 Host 返回时间为准")
    }

    var body: some View {
        NavigationStack {
            Form {
                TextField("标题", text: $title)
                TextField("任务内容", text: $prompt, axis: .vertical)
                Section("执行规则 JSON") {
                    TextEditor(text: $timing).frame(minHeight: 100)
                    Text("支持 after、at、every、daily、weekly、cron、rrule，时区使用 IANA 名称。")
                    if let data = timing.data(using: .utf8),
                       let value = try? JSONDecoder().decode(JSONValue.self, from: data) {
                        Text("规则预览 · \(value.displayText)").appFont(.footnote.monospaced())
                    }
                    Text(nextExecutionPreview)
                    Text("当前时区 · \(TimeZone.current.identifier)")
                }
                Picker("投递目标", selection: $target) {
                    Text("首次新建并复用会话").tag("new_once")
                    Text("每次新建会话").tag("new")
                    Text("已有会话").tag("session")
                }
                if target == "session" { TextField("会话 ID", text: $sessionID) }
                if let error = store.scheduledTaskMutationError { Text(error).foregroundStyle(.red) }
                Button("创建") {
                    requestID = store.createScheduledTask(title: title, prompt: prompt, timingJSON: timing, targetKind: target, sessionID: sessionID)
                }.disabled(store.scheduledTaskPendingID != nil)
            }
            .navigationTitle("创建定时任务")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } } }
            .onChangeCompat(of: store.scheduledTaskCompletedRequestID) { _, id in
                if requestID != nil && id == requestID { dismiss() }
            }
        }
    }
}

/// 只处理当前编辑页内的非输入区轻触，不拦截输入、选区、滚动或按钮事件。
struct ScheduleKeyboardDismissProbe: UIViewRepresentable {
    func makeCoordinator() -> Coordinator { Coordinator() }
    func makeUIView(context: Context) -> ProbeView {
        let view = ProbeView()
        view.isUserInteractionEnabled = false
        view.isAccessibilityElement = false
        view.windowChanged = { [weak coordinator = context.coordinator] view in coordinator?.attach(to: view) }
        return view
    }
    func updateUIView(_ view: ProbeView, context: Context) { context.coordinator.attach(to: view) }
    static func dismantleUIView(_ view: ProbeView, coordinator: Coordinator) {
        coordinator.detach()
        view.windowChanged = nil
    }
    final class ProbeView: UIView {
        var windowChanged: ((ProbeView) -> Void)?
        override func didMoveToWindow() { super.didMoveToWindow(); windowChanged?(self) }
    }
    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        private weak var scope: UIView?
        private weak var window: UIWindow?
        private lazy var tap: UITapGestureRecognizer = {
            let gesture = UITapGestureRecognizer(target: self, action: #selector(handleTap))
            gesture.cancelsTouchesInView = false
            gesture.delegate = self
            return gesture
        }()
        func attach(to view: UIView) {
            scope = view
            guard window !== view.window else { return }
            window?.removeGestureRecognizer(tap)
            window = view.window
            window?.addGestureRecognizer(tap)
        }
        func detach() { window?.removeGestureRecognizer(tap); window = nil; scope = nil }
        func isOutsideEditor(_ view: UIView?) -> Bool {
            var current = view
            while let candidate = current {
                if candidate is UITextField || candidate is UITextView { return false }
                current = candidate.superview
            }
            return true
        }
        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
            guard let scope, scope.window === touch.view?.window,
                  scope.bounds.contains(touch.location(in: scope)) else { return false }
            return isOutsideEditor(touch.view)
        }
        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                               shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer) -> Bool { true }
        @objc private func handleTap() {
            // 先让菜单、复制和按钮处理当前轻触，再收键盘，不抢走它们的操作。
            DispatchQueue.main.async { [weak self] in self?.dismissKeyboard() }
        }
        func dismissKeyboard() { window?.endEditing(true) }
    }
}

struct ScheduleIntervalPicker: View {
    @Binding var selection: String
    let unit: String
    @State private var draft: Int
    @Environment(\.dismiss) private var dismiss
    init(selection: Binding<String>, unit: String) {
        _selection = selection
        self.unit = unit
        _draft = State(initialValue: min(10_000, max(1, Int(selection.wrappedValue) ?? 1)))
    }
    var body: some View {
        NavigationStack {
            ScheduleIntervalWheel(selection: $draft, unit: unit)
                .frame(height: 220).padding(.horizontal, 20)
                .navigationTitle("重复间隔").navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("确定") { selection = String(draft); dismiss() }
                            .accessibilityIdentifier("schedule-interval-confirm")
                    }
                }
        }
    }
}

/// UIPickerView 按需创建行，避免为 10000 个间隔一次生成 SwiftUI 子视图。
struct ScheduleIntervalWheel: UIViewRepresentable {
    @Binding var selection: Int
    let unit: String
    func makeCoordinator() -> Coordinator { Coordinator(self) }
    func makeUIView(context: Context) -> UIPickerView {
        let picker = UIPickerView()
        picker.dataSource = context.coordinator
        picker.delegate = context.coordinator
        picker.accessibilityIdentifier = "schedule-interval-wheel"
        return picker
    }
    func updateUIView(_ picker: UIPickerView, context: Context) {
        let unitChanged = context.coordinator.parent.unit != unit
        context.coordinator.parent = self
        if unitChanged { picker.reloadAllComponents() }
        let row = min(9999, max(0, selection - 1))
        if picker.selectedRow(inComponent: 0) != row { picker.selectRow(row, inComponent: 0, animated: false) }
    }
    final class Coordinator: NSObject, UIPickerViewDataSource, UIPickerViewDelegate {
        var parent: ScheduleIntervalWheel
        init(_ parent: ScheduleIntervalWheel) { self.parent = parent }
        func numberOfComponents(in pickerView: UIPickerView) -> Int { 1 }
        func pickerView(_ pickerView: UIPickerView, numberOfRowsInComponent component: Int) -> Int { 10_000 }
        func pickerView(_ pickerView: UIPickerView, rowHeightForComponent component: Int) -> CGFloat { 40 }
        func pickerView(_ pickerView: UIPickerView, titleForRow row: Int, forComponent component: Int) -> String? {
            "\(row + 1) \(parent.unit)"
        }
        func pickerView(_ pickerView: UIPickerView, didSelectRow row: Int, inComponent component: Int) {
            parent.selection = row + 1
        }
    }
}

private struct ScheduleSelectionMark: View {
    let isSelected: Bool
    var body: some View {
        Image(systemName: "checkmark").font(.system(size: 16))
            .frame(width: 18).foregroundStyle(.primary)
            .opacity(isSelected ? 1 : 0).accessibilityHidden(true)
    }
}
