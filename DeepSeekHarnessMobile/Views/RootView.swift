import SwiftUI
import QuickLook
import UIKit
import DeepSeekHarnessShared

struct RootView: View {
    @EnvironmentObject private var store: AppStore
    @EnvironmentObject private var hosts: MultiGatewayStore
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        RootNavigationHost(store: store)
            .equatable()
            // 直接观察当前 AppStore，避免外层主机容器漏掉外观设置更新。
            .preferredColorScheme(store.interfaceStyle.colorScheme)
            .alert("Whaleness", isPresented: Binding(get: { store.lastError != nil }, set: { if !$0 { store.lastError = nil } })) {
                Button(String(localized: "好"), role: .cancel) { store.lastError = nil }
            } message: { Text(store.lastError ?? "") }
            .onChangeCompat(of: scenePhase) { _, phase in
                store.handleScenePhase(phase)
            }
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
        .task {
            if case .disconnected = store.gateway.state {
                await store.connectOnColdLaunchIfPaired()
            }
        }
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
                ConversationView(activeView: activeView, topContentInset: topInset)
            }
        case .settings:
            SettingsView()
        case .plugins:
            PluginsView(store: store)
        case .scheduledTasks:
            ScheduledTasksView(store: store) { sessionID in
                let session = store.sessions.first(where: { $0.id == sessionID }) ?? SessionSummary(
                    id: sessionID,
                    title: store.scheduledTasks.first(where: { $0.sessionID == sessionID })?.title ?? "会话",
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
    case all, active, ended

    var title: String {
        switch self {
        case .all: String(localized: "全部")
        case .active: String(localized: "已开启")
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
        case .ended: matchesStatus = task.status != "active"
        }
        return matchesStatus && (search.isEmpty || task.title.localizedStandardContains(search) ||
            task.prompt.localizedStandardContains(search))
    }
}

private struct ScheduledTasksView: View {
    private enum ActiveAlert: Identifiable {
        case delete(ScheduledTask)
        case error(String)

        var id: String {
            switch self {
            case .delete(let task): "delete-\(task.id)"
            case .error(let message): "error-\(message)"
            }
        }
    }

    @ObservedObject var store: AppStore
    let onOpenSession: (String) -> Void
    @State private var expandedTaskIDs: Set<String> = []
    @State private var editingTask: ScheduledTask?
    @State private var activeAlert: ActiveAlert?
    @State private var statusFilter: ScheduledTaskStatusFilter = .all
    @State private var searchQuery = ""
    @State private var isSearching = false
    @FocusState private var searchIsFocused: Bool
    private var visibleTasks: [ScheduledTask] {
        filteredScheduledTasks(store.scheduledTasks, status: statusFilter, query: searchQuery)
    }

    var body: some View {
        List {
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
                DshEmptyState("暂无定时任务", systemImage: "clock", description: Text("在会话中创建的定时任务会显示在这里"))
                    .padding(.top, 40)
                    .listRowSeparator(.hidden)
                    .listRowBackground(Color.clear)
            } else if visibleTasks.isEmpty {
                DshEmptyState(
                    "没有符合条件的定时任务",
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
                    ScheduledTaskCard(
                        task: task,
                        sessionTitle: store.sessions.first(where: { $0.id == task.sessionID })?.title ?? task.sessionID,
                        expanded: expandedTaskIDs.contains(task.id),
                        onOpen: { onOpenSession(task.sessionID) },
                        onExpand: {
                            if !expandedTaskIDs.insert(task.id).inserted { expandedTaskIDs.remove(task.id) }
                        }
                    )
                    .listRowInsets(EdgeInsets(top: 8, leading: 20, bottom: 8, trailing: 20))
                    .listRowSeparator(.hidden)
                    .listRowBackground(Color.clear)
                    .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                        Button(role: .destructive) { activeAlert = .delete(task) } label: {
                            Label("删除", systemImage: "trash")
                        }
                        .disabled(store.scheduledTaskPendingID == task.id)
                        if task.status == "active" {
                            Button { editingTask = task } label: {
                                Label("编辑", systemImage: "pencil")
                            }
                            .tint(.blue)
                            .disabled(store.scheduledTaskPendingID == task.id)
                        }
                    }
                }
            }
        }
        .listStyle(.plain)
        .adaptiveListWidth()
        .scrollContentBackground(.hidden)
        .refreshable { store.refreshScheduledTasks() }
        .background(Color(uiColor: .systemBackground))
        .safeAreaInset(edge: .top, spacing: 0) {
            if isSearching {
                searchField
                    .padding(.horizontal, 20)
                    .padding(.vertical, 8)
                    .readableContentWidth()
                    .background(Color(uiColor: .systemBackground))
            }
        }
        .navigationTitle(String(localized: "定时任务"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if !isSearching {
                // 独立工具栏项由系统按横向或竖向排列，不能共用固定宽度的 HStack。
                ToolbarItem(placement: .topBarTrailing) {
                    searchButton
                }
                ToolbarItem(placement: .topBarTrailing) {
                    statusFilterMenu
                }
            }
        }
        .task {
            if !store.gateway.state.isConnected { store.refreshScheduledTasks() }
        }
        .onReceive(store.gateway.$state) { state in
            if state.isConnected { store.refreshScheduledTasks() }
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
            }
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

    private var statusFilterMenu: some View {
        Menu {
            ForEach(ScheduledTaskStatusFilter.allCases, id: \.self) { option in
                Button {
                    statusFilter = option
                } label: {
                    if statusFilter == option {
                        Label(option.title, systemImage: "checkmark")
                    } else {
                        Text(option.title)
                    }
                }
            }
        } label: {
            Label("筛选定时任务", systemImage: "line.3.horizontal.decrease")
                .labelStyle(.iconOnly)
        }
        .accessibilityLabel("筛选定时任务，当前\(statusFilter.title)")
        .accessibilityIdentifier("scheduled-tasks-filter")
    }
}

struct ScheduledTaskCard: View {
    let task: ScheduledTask
    let sessionTitle: String
    let expanded: Bool
    let onOpen: () -> Void
    let onExpand: () -> Void
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
                    Text(task.status == "active" ? ScheduledTaskFormat.date(task.scheduledAt) : String(localized: "已结束"))
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
            detail(String(localized: "状态"), task.status == "active" ? String(localized: "运行中") : String(localized: "已结束"))
            detail(String(localized: "最近投递"), task.lastDelivery?["deliveredAt"]?.stringValue.map {
                String(localized: "schedule.last-delivery", defaultValue: "已投递 · \(ScheduledTaskFormat.date($0))")
            } ?? String(localized: "暂无投递记录"))
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
    @State private var selectedDate: Date
    @State private var selectedTime: Date
    @State private var useSpecificDate: Bool
    @State private var pendingRequestID: String?
    @State private var localError: String?

    init(task: ScheduledTask, store: AppStore) {
        self.task = task
        self.store = store
        _title = State(initialValue: task.title)
        _prompt = State(initialValue: task.prompt)
        _selectedDate = State(initialValue: Self.parseDate(task.scheduledAt) ?? Date().addingTimeInterval(3_600))
        _selectedTime = State(initialValue: Self.initialClock(task))
        _useSpecificDate = State(initialValue: task.kind == "at" || task.kind == "after")
    }

    private var isRepeating: Bool { !["at", "after"].contains(task.kind) }
    private var zone: TimeZone { TimeZone(identifier: task.raw["timeZone"]?.stringValue ?? "") ?? .current }
    private var clock: String {
        let formatter = DateFormatter()
        formatter.timeZone = zone
        formatter.dateFormat = "HH:mm:ss"
        return formatter.string(from: selectedTime)
    }
    private var timingChange: JSONValue? {
        if useSpecificDate {
            let original = Self.parseDate(task.scheduledAt)
            guard isRepeating || original == nil || abs(selectedDate.timeIntervalSince(original!)) > 1 else { return nil }
            let formatter = ISO8601DateFormatter()
            formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            return .object(["kind": .string("at"), "at": .string(formatter.string(from: selectedDate))])
        }
        guard String(clock.prefix(5)) != String((task.raw["time"]?.stringValue ?? "").prefix(5)) else { return nil }
        let timeZone = task.raw["timeZone"]?.stringValue ?? zone.identifier
        if task.kind == "daily" {
            return .object(["kind": .string("daily"), "daily": .object([
                "time": .string(clock), "time_zone": .string(timeZone)
            ])])
        }
        if task.kind == "weekly" {
            return .object(["kind": .string("weekly"), "weekly": .object([
                "time": .string(clock), "time_zone": .string(timeZone),
                "weekdays": task.raw["weekdays"] ?? .array([])
            ])])
        }
        return nil
    }
    private var canSave: Bool {
        let cleanTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanPrompt = prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanTitle.isEmpty, cleanTitle.count <= 120, !cleanPrompt.isEmpty else { return false }
        guard cleanTitle != task.title || cleanPrompt != task.prompt || timingChange != nil else { return false }
        if useSpecificDate && timingChange != nil && selectedDate <= .now { return false }
        return pendingRequestID == nil
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Button("取消") { dismiss() }
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
                            .textInputAutocapitalization(.never)
                            .padding(13)
                            .background(Color(uiColor: .secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
                        Text("最多 120 个字").appFont(.caption).foregroundStyle(.secondary)
                    }
                    VStack(alignment: .leading, spacing: 8) {
                        Text("任务内容").appFont(.subheadline.weight(.medium))
                        TextEditor(text: $prompt)
                            .scrollContentBackground(.hidden)
                            .frame(minHeight: 130)
                            .padding(8)
                            .background(Color(uiColor: .secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
                    }
                    VStack(alignment: .leading, spacing: 12) {
                        Text("执行时间").appFont(.subheadline.weight(.medium))
                        if isRepeating {
                            Toggle("改为指定日期执行一次", isOn: $useSpecificDate)
                                .tint(Color(red: 0.03, green: 0.51, blue: 0.97))
                        }
                        if useSpecificDate {
                            DatePicker("执行日期", selection: $selectedDate, in: Date()..., displayedComponents: .date)
                                .datePickerStyle(.graphical)
                            DatePicker("执行时刻", selection: $selectedDate, displayedComponents: .hourAndMinute)
                                .datePickerStyle(.wheel)
                            Text("按当前设备时区 \(TimeZone.current.identifier) 选择，保存后按该时间执行一次。")
                                .appFont(.caption).foregroundStyle(.secondary)
                            if timingChange != nil && selectedDate <= .now {
                                Text("请选择未来的日期和时间。")
                                    .appFont(.caption).foregroundStyle(.red)
                            }
                        } else if task.kind == "daily" || task.kind == "weekly" {
                            DatePicker("执行时刻", selection: $selectedTime, displayedComponents: .hourAndMinute)
                                .datePickerStyle(.wheel)
                                .environment(\.timeZone, zone)
                            Text("保留原有\(task.kind == "daily" ? "每日" : "每周")规则和时区 \(zone.identifier)。")
                                .appFont(.caption).foregroundStyle(.secondary)
                        } else {
                            Text("当前规则：\(ScheduledTaskFormat.fullRule(task))。选择上方选项后，可用日期和时间选择器改为单次执行。")
                                .appFont(.subheadline).foregroundStyle(.secondary)
                        }
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
        }
        .background(Color(uiColor: .systemBackground))
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
        let requestID = store.updateScheduledTask(task, title: title, prompt: prompt, change: timingChange)
        if let requestID { pendingRequestID = requestID }
        else { localError = store.scheduledTaskMutationError ?? String(localized: "操作正在进行，请稍后重试") }
    }

    private static func parseDate(_ value: String) -> Date? {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = formatter.date(from: value) { return date }
        formatter.formatOptions = [.withInternetDateTime]
        return formatter.date(from: value)
    }

    private static func initialClock(_ task: ScheduledTask) -> Date {
        let parts = (task.raw["time"]?.stringValue ?? "").split(separator: ":")
        guard parts.count >= 2, let hour = Int(parts[0]), let minute = Int(parts[1]) else { return .now }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: task.raw["timeZone"]?.stringValue ?? "") ?? .current
        return calendar.date(from: DateComponents(year: 2026, month: 1, day: 1, hour: hour, minute: minute)) ?? .now
    }
}

private enum ScheduledTaskFormat {
    static func shortRule(_ task: ScheduledTask) -> String {
        switch task.kind {
        case "daily": String(localized: "每天")
        case "weekly": String(localized: "每周")
        case "every": String(
            localized: "schedule.every.short",
            defaultValue: "每 \(duration(Int(task.raw["everySeconds"]?.doubleValue ?? 0)))"
        )
        case "cron": "Cron"
        default: String(localized: "一次")
        }
    }

    static func fullRule(_ task: ScheduledTask) -> String {
        let time = task.raw["time"]?.stringValue ?? ""
        let zone = task.raw["timeZone"]?.stringValue ?? ""
        switch task.kind {
        case "daily":
            return String(localized: "schedule.daily.detail", defaultValue: "每天 \(time) · \(zone)")
        case "weekly":
            let days = (task.raw["weekdays"]?.arrayValue ?? []).compactMap { $0.doubleValue.map(Int.init) }
            let names = [
                String(localized: "周一"), String(localized: "周二"), String(localized: "周三"),
                String(localized: "周四"), String(localized: "周五"), String(localized: "周六"),
                String(localized: "周日")
            ]
            let separator = String(localized: "schedule.weekday.separator", defaultValue: "、")
            let weekdays = days.compactMap { (1...7).contains($0) ? names[$0 - 1] : nil }.joined(separator: separator)
            return String(localized: "schedule.weekly.detail", defaultValue: "每周 \(weekdays) · \(time) · \(zone)")
        case "every":
            return String(localized: "schedule.every.detail", defaultValue: "每 \(duration(Int(task.raw["everySeconds"]?.doubleValue ?? 0)))执行一次")
        case "cron":
            return "\(task.raw["expression"]?.stringValue ?? "") · \(zone)"
        case "after":
            let seconds = Int(task.raw["afterSeconds"]?.doubleValue ?? 0)
            return String(localized: "schedule.after.detail", defaultValue: "\(seconds) 秒后执行一次")
        default:
            return String(localized: "指定时间执行一次")
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
        return date.formatted(.dateTime.year().month().day().hour().minute())
    }
}

/// Immutable route chrome. Keeping this separate from `AppStore` is what makes
/// the title and trailing items enter in the same navigation transition as the
/// system back button.
private struct ConversationNavigationHeader: Hashable {
    let sessionID: String?
    let title: String
    let agentPresetTitle: String
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
                        "此目录为空",
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
                            Text("正在下载 · \(Int(progress * 100))%")
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
            Text(store.workspaceFilePath == "." ? "工作区根目录" : store.workspaceFilePath)
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
                    isDownloaded ? "\(item.name) 已下载，点击重新下载" : "下载 \(item.name)"
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
        } ?? "文件"
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
            errorMessage = "文件超过 2 MB，请使用系统应用打开"
        } catch {
            guard !Task.isCancelled else { return }
            errorMessage = "读取代码失败：\(error.localizedDescription)"
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
