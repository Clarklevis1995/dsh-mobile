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
            .alert("DeepSeek Harness", isPresented: Binding(get: { store.lastError != nil }, set: { if !$0 { store.lastError = nil } })) {
                Button(String(localized: "好"), role: .cancel) { store.lastError = nil }
            } message: { Text(store.lastError ?? "") }
            .onChange(of: scenePhase) { _, phase in
                store.handleScenePhase(phase)
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
                    newConversationTask = Task { @MainActor in
                        defer { newConversationTask = nil }
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
                store.connectOnColdLaunchIfPaired()
            }
        }
        .onChange(of: navigationPath) { _, path in
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
                onActivate: {
                    await store.activatePreparedConversation(sessionID: header.sessionID)
                }
            ) {
                ConversationView()
            }
        case .settings:
            SettingsView()
        case .plugins:
            WorkspaceDrawerDestination(title: "插件", message: "插件功能尚未接入")
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
                newConversationTask = Task { @MainActor in
                    defer { newConversationTask = nil }
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

    private func openLiveActivitySession(_ session: SessionSummary) {
        pendingLiveActivitySessionID = nil
        newConversationTask?.cancel()
        let header = conversationHeader(for: session)
        newConversationTask = Task { @MainActor in
            defer { newConversationTask = nil }
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

private struct ScheduledTasksView: View {
    @Environment(\.colorScheme) private var colorScheme
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
    @Environment(\.dismiss) private var dismiss
    @State private var expandedTaskIDs: Set<String> = []
    @State private var revealedTaskID: String?
    @State private var editingTask: ScheduledTask?
    @State private var activeAlert: ActiveAlert?
    private var headerButtonBackground: Color {
        colorScheme == .dark ? Color(uiColor: .secondarySystemBackground) : .white
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            ScrollView {
                LazyVStack(spacing: 16) {
                    if store.scheduledTasksLoading && store.scheduledTasks.isEmpty {
                        ProgressView("正在加载定时任务…")
                            .frame(maxWidth: .infinity)
                            .padding(.top, 72)
                    } else if let error = store.scheduledTasksError, store.scheduledTasks.isEmpty {
                        ContentUnavailableView {
                            Label("无法显示定时任务", systemImage: "wifi.exclamationmark")
                        } description: {
                            Text(error)
                        } actions: {
                            Button("重试") { store.refreshScheduledTasks() }
                        }
                        .padding(.top, 40)
                    } else if store.scheduledTasks.isEmpty {
                        ContentUnavailableView("暂无定时任务", systemImage: "clock", description: Text("在会话中创建的定时任务会显示在这里"))
                            .padding(.top, 40)
                    } else {
                        ForEach(store.scheduledTasks) { task in
                            ScheduledTaskCard(
                                task: task,
                                sessionTitle: store.sessions.first(where: { $0.id == task.sessionID })?.title ?? task.sessionID,
                                expanded: expandedTaskIDs.contains(task.id),
                                revealedTaskID: $revealedTaskID,
                                onOpen: {
                                    if revealedTaskID == task.id {
                                        withAnimation(.spring(response: 0.28, dampingFraction: 0.84)) {
                                            revealedTaskID = nil
                                        }
                                    } else {
                                        revealedTaskID = nil
                                        onOpenSession(task.sessionID)
                                    }
                                },
                                onEdit: {
                                    withAnimation(.spring(response: 0.28, dampingFraction: 0.84)) {
                                        revealedTaskID = nil
                                    }
                                    editingTask = task
                                },
                                onDelete: {
                                    withAnimation(.spring(response: 0.28, dampingFraction: 0.84)) {
                                        revealedTaskID = nil
                                    }
                                    activeAlert = .delete(task)
                                },
                                busy: store.scheduledTaskPendingID == task.id,
                                onExpand: {
                                    if revealedTaskID == task.id {
                                        withAnimation(.spring(response: 0.28, dampingFraction: 0.84)) {
                                            revealedTaskID = nil
                                        }
                                        return
                                    }
                                    withAnimation(.easeInOut(duration: 0.22)) {
                                        if !expandedTaskIDs.insert(task.id).inserted { expandedTaskIDs.remove(task.id) }
                                    }
                                }
                            )
                        }
                    }
                }
                .padding(.horizontal, 20)
                .padding(.top, 18)
                .padding(.bottom, 28)
            }
            .refreshable { store.refreshScheduledTasks() }
        }
        .background(Color(uiColor: .systemBackground))
        .navigationBarBackButtonHidden()
        .toolbar(.hidden, for: .navigationBar)
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
        .onChange(of: store.scheduledTaskMutationError) { _, error in
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

    private var header: some View {
        HStack {
            Button(action: { dismiss() }) {
                Image(systemName: "chevron.left")
                    .font(.system(size: 18, weight: .semibold))
                    .frame(width: 46, height: 46)
                    .background(headerButtonBackground.opacity(0.92), in: Circle())
                    .glassSurface(radius: 23, clear: true)
                    .overlay(Circle().stroke(Color.primary.opacity(0.08), lineWidth: 0.7))
                    .shadow(color: .black.opacity(0.06), radius: 10, y: 4)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("返回")
            Spacer()
            Text("定时任务")
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(.primary)
            Spacer()
            Color.clear.frame(width: 46, height: 46)
                .accessibilityHidden(true)
        }
        .foregroundStyle(.primary)
        .padding(.horizontal, 20)
        .padding(.top, 8)
        .padding(.bottom, 12)
    }
}

private struct ScheduledTaskCard: View {
    @Environment(\.colorScheme) private var colorScheme
    let task: ScheduledTask
    let sessionTitle: String
    let expanded: Bool
    @Binding var revealedTaskID: String?
    let onOpen: () -> Void
    let onEdit: () -> Void
    let onDelete: () -> Void
    let busy: Bool
    let onExpand: () -> Void
    @State private var dragOrigin: CGFloat?
    @State private var dragOffset: CGFloat?
    @State private var settledOpen = false
    @State private var suppressCardTapUntil = Date.distantPast
    @State private var detailsHeight: CGFloat = 0

    private let revealWidth: CGFloat = 128
    private var isRevealed: Bool { revealedTaskID == task.id }
    private var currentOffset: CGFloat { dragOffset ?? (settledOpen ? -revealWidth : 0) }
    private var revealProgress: CGFloat { -currentOffset / revealWidth }
    private var cardBackground: Color {
        colorScheme == .dark ? Color(uiColor: .secondarySystemBackground) : .white
    }

    var body: some View {
        ZStack(alignment: .trailing) {
            HStack(spacing: 10) {
                Button(action: onEdit) {
                    Image(systemName: "pencil")
                        .font(.system(size: 19, weight: .medium))
                        .foregroundStyle(.primary)
                        .frame(width: 50, height: 50)
                        .background(Color(uiColor: .tertiarySystemBackground), in: Circle())
                }
                .disabled(task.status != "active" || busy || revealProgress < 0.95)
                .accessibilityLabel("编辑\(task.title)")
                Button(action: onDelete) {
                    Image(systemName: "trash")
                        .font(.system(size: 19, weight: .medium))
                        .foregroundStyle(.white)
                        .frame(width: 50, height: 50)
                        .background(Color(red: 0.84, green: 0.31, blue: 0.32), in: Circle())
                }
                .disabled(busy || revealProgress < 0.95)
                .accessibilityLabel("删除\(task.title)")
            }
            .buttonStyle(.plain)
            .padding(.trailing, 9)
            .opacity(revealProgress)
            .scaleEffect(0.72 + 0.28 * revealProgress, anchor: .trailing)
            .offset(x: 18 * (1 - revealProgress))
            .accessibilityHidden(revealProgress < 0.95)

            cardContent.offset(x: currentOffset)
        }
        .frame(maxWidth: .infinity)
        .simultaneousGesture(swipeGesture)
        .onAppear { settledOpen = isRevealed }
        .onChange(of: revealedTaskID) { _, revealedID in
            if revealedID != task.id && settledOpen {
                withAnimation(.spring(response: 0.28, dampingFraction: 0.84)) {
                    settledOpen = false
                }
            }
        }
    }

    private var cardContent: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button {
                guard Date.now >= suppressCardTapUntil else { return }
                onOpen()
            } label: {
                VStack(alignment: .leading, spacing: 8) {
                    Text(ScheduledTaskFormat.shortRule(task))
                        .font(.system(size: 15, weight: .medium))
                        .foregroundStyle(Color(red: 0.03, green: 0.51, blue: 0.97))
                    Text(task.title)
                        .font(.system(size: 21, weight: .semibold))
                        .foregroundStyle(.primary)
                        .multilineTextAlignment(.leading)
                    Text(task.prompt)
                        .font(.system(size: 16))
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

            Rectangle().fill(Color.primary.opacity(0.09)).frame(height: 1)
                .padding(.horizontal, 20)
            Button {
                guard Date.now >= suppressCardTapUntil else { return }
                onExpand()
            } label: {
                HStack {
                    Text(task.status == "active" ? ScheduledTaskFormat.date(task.scheduledAt) : "已结束")
                        .font(.system(size: 15))
                        .foregroundStyle(.secondary)
                    Spacer()
                    Image(systemName: expanded ? "chevron.up" : "chevron.down")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(.secondary)
                        .frame(width: 36, height: 34)
                }
                .frame(maxWidth: .infinity)
                .padding(.leading, 20)
                .padding(.trailing, 12)
                .padding(.vertical, 10)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(expanded ? "收起任务详情" : "展开任务详情")

            detailsContent
                .fixedSize(horizontal: false, vertical: true)
                .background {
                    GeometryReader { geometry in
                        Color.clear.preference(key: ScheduledTaskDetailsHeightKey.self, value: geometry.size.height)
                    }
                }
                .frame(height: expanded ? detailsHeight : 0, alignment: .top)
                .clipped()
                .accessibilityHidden(!expanded)
                .allowsHitTesting(expanded)
        }
        .background(cardBackground, in: RoundedRectangle(cornerRadius: 28))
        .overlay(RoundedRectangle(cornerRadius: 28).stroke(Color.primary.opacity(0.07)))
        .shadow(color: .black.opacity(0.045), radius: 18, y: 8)
        .onPreferenceChange(ScheduledTaskDetailsHeightKey.self) { height in
            guard height > 0, abs(height - detailsHeight) > 0.5 else { return }
            if expanded {
                withAnimation(.easeInOut(duration: 0.22)) { detailsHeight = height }
            } else {
                detailsHeight = height
            }
        }
    }

    private var detailsContent: some View {
        VStack(alignment: .leading, spacing: 13) {
            detail("执行规则", ScheduledTaskFormat.fullRule(task))
            detail("下次计划时间", task.status == "active" ? ScheduledTaskFormat.date(task.scheduledAt) : "无")
            detail("所属会话", sessionTitle)
            detail("状态", task.status == "active" ? "运行中" : "已结束")
            detail("最近投递", task.lastDelivery?["deliveredAt"]?.stringValue.map { "已投递 · \(ScheduledTaskFormat.date($0))" } ?? "暂无投递记录")
        }
        .padding(.horizontal, 20)
        .padding(.top, 18)
        .padding(.bottom, 22)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var swipeGesture: some Gesture {
        DragGesture(minimumDistance: 10)
            .onChanged { value in
                if dragOrigin == nil {
                    guard abs(value.translation.width) > abs(value.translation.height) * 1.15 else { return }
                    dragOrigin = settledOpen ? -revealWidth : 0
                    suppressCardTapUntil = .now.addingTimeInterval(0.35)
                    if revealedTaskID != nil && !isRevealed {
                        withAnimation(.spring(response: 0.28, dampingFraction: 0.84)) {
                            revealedTaskID = nil
                        }
                    }
                }
                guard let dragOrigin else { return }
                dragOffset = min(0, max(-revealWidth, dragOrigin + value.translation.width))
            }
            .onEnded { value in
                guard let dragOrigin else { return }
                let settledOffset = dragOffset ?? min(0, max(-revealWidth,
                    dragOrigin + value.translation.width))
                let shouldReveal: Bool
                if value.velocity.width < -280 {
                    shouldReveal = true
                } else if value.velocity.width > 280 {
                    shouldReveal = false
                } else {
                    let threshold = dragOrigin == 0 ? 0.25 : 0.75
                    shouldReveal = settledOffset <= -revealWidth * threshold
                }
                let response = max(0.18, 0.34 - abs(value.velocity.width) / 6_000)
                suppressCardTapUntil = .now.addingTimeInterval(0.35)
                withAnimation(.spring(response: response, dampingFraction: 0.84)) {
                    settledOpen = shouldReveal
                    revealedTaskID = shouldReveal ? task.id : nil
                    dragOffset = nil
                }
                self.dragOrigin = nil
            }
    }

    private func detail(_ label: String, _ value: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Text(label).foregroundStyle(.secondary).frame(width: 92, alignment: .leading)
            Text(value).foregroundStyle(.primary).frame(maxWidth: .infinity, alignment: .leading)
        }
        .font(.system(size: 14))
    }
}

private struct ScheduledTaskDetailsHeightKey: PreferenceKey {
    static var defaultValue: CGFloat = 0

    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = max(value, nextValue())
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
                Text("编辑定时任务").font(.system(size: 17, weight: .semibold))
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
                        Text("标题").font(.subheadline.weight(.medium))
                        TextField("任务标题", text: $title)
                            .textInputAutocapitalization(.never)
                            .padding(13)
                            .background(Color(uiColor: .secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
                        Text("最多 120 个字").font(.caption).foregroundStyle(.secondary)
                    }
                    VStack(alignment: .leading, spacing: 8) {
                        Text("任务内容").font(.subheadline.weight(.medium))
                        TextEditor(text: $prompt)
                            .scrollContentBackground(.hidden)
                            .frame(minHeight: 130)
                            .padding(8)
                            .background(Color(uiColor: .secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
                    }
                    VStack(alignment: .leading, spacing: 12) {
                        Text("执行时间").font(.subheadline.weight(.medium))
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
                                .font(.caption).foregroundStyle(.secondary)
                            if timingChange != nil && selectedDate <= .now {
                                Text("请选择未来的日期和时间。")
                                    .font(.caption).foregroundStyle(.red)
                            }
                        } else if task.kind == "daily" || task.kind == "weekly" {
                            DatePicker("执行时刻", selection: $selectedTime, displayedComponents: .hourAndMinute)
                                .datePickerStyle(.wheel)
                                .environment(\.timeZone, zone)
                            Text("保留原有\(task.kind == "daily" ? "每日" : "每周")规则和时区 \(zone.identifier)。")
                                .font(.caption).foregroundStyle(.secondary)
                        } else {
                            Text("当前规则：\(ScheduledTaskFormat.fullRule(task))。选择上方选项后，可用日期和时间选择器改为单次执行。")
                                .font(.subheadline).foregroundStyle(.secondary)
                        }
                    }
                    if let error = localError ?? (pendingRequestID != nil ? store.scheduledTaskMutationError : nil) {
                        Text(error).font(.subheadline).foregroundStyle(.red)
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
        .onChange(of: store.scheduledTaskCompletedRequestID) { _, completedID in
            if completedID != nil && completedID == pendingRequestID { dismiss() }
        }
        .onChange(of: store.scheduledTaskPendingID) { _, pendingID in
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
        else { localError = store.scheduledTaskMutationError ?? "操作正在进行，请稍后重试" }
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
        case "daily": "每天"
        case "weekly": "每周"
        case "every": "每 \(duration(Int(task.raw["everySeconds"]?.doubleValue ?? 0)))"
        case "cron": "Cron"
        default: "一次"
        }
    }

    static func fullRule(_ task: ScheduledTask) -> String {
        switch task.kind {
        case "daily": return "每天 \(task.raw["time"]?.stringValue ?? "") · \(task.raw["timeZone"]?.stringValue ?? "")"
        case "weekly":
            let days = (task.raw["weekdays"]?.arrayValue ?? []).compactMap { $0.doubleValue.map(Int.init) }
            let names = ["一", "二", "三", "四", "五", "六", "日"]
            return "每周\(days.compactMap { (1...7).contains($0) ? names[$0 - 1] : nil }.joined(separator: "、周")) · \(task.raw["time"]?.stringValue ?? "") · \(task.raw["timeZone"]?.stringValue ?? "")"
        case "every": return "每 \(duration(Int(task.raw["everySeconds"]?.doubleValue ?? 0)))执行一次"
        case "cron": return "\(task.raw["expression"]?.stringValue ?? "") · \(task.raw["timeZone"]?.stringValue ?? "")"
        case "after": return "\(Int(task.raw["afterSeconds"]?.doubleValue ?? 0)) 秒后执行一次"
        default: return "指定时间执行一次"
        }
    }

    private static func duration(_ seconds: Int) -> String {
        if seconds > 0 && seconds % 86_400 == 0 { return "\(seconds / 86_400) 天" }
        if seconds > 0 && seconds % 3_600 == 0 { return "\(seconds / 3_600) 小时" }
        if seconds > 0 && seconds % 60 == 0 { return "\(seconds / 60) 分钟" }
        return "\(seconds) 秒"
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

/// Owns only navigation chrome. The content below it may observe the complete
/// app store and update at WebSocket frequency without invalidating the toolbar.
private struct ConversationNavigationShell<Content: View>: View {
    let header: ConversationNavigationHeader
    let gateway: GatewayClient
    let store: AppStore
    let onActivate: () async -> Void
    @ViewBuilder let content: () -> Content
    @State private var showsWorkspaceFiles = false
    @State private var liveTitle: String?
    @State private var livePresetTitle: String?

    var body: some View {
        content()
            .navigationTitle(liveTitle ?? header.title)
            .onReceive(store.$sessions) { sessions in
                let sessionID = header.sessionID ?? store.selectedSessionId
                liveTitle = sessions.first { $0.id == sessionID }?.title
            }
            .onReceive(store.$sessionAgentPreset) { state in
                guard state.sessionId == (header.sessionID ?? store.selectedSessionId), let id = state.agentPreset else { return }
                livePresetTitle = state.presets.first { $0.id == id }?.name ?? L10n.presetModeName(for: id)
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbarRole(.editor)
            .toolbarBackground(.hidden, for: .navigationBar)
            .toolbar {
                if #available(iOS 26.0, *) {
                    ToolbarItem(placement: .topBarTrailing) {
                        ConversationNavigationStatus(
                            gateway: gateway,
                            agentPresetTitle: livePresetTitle ?? header.agentPresetTitle
                        )
                    }
                    .sharedBackgroundVisibility(.hidden)

                    ToolbarSpacer(.fixed, placement: .topBarTrailing)
                } else {
                    ToolbarItem(placement: .topBarTrailing) {
                        ConversationNavigationStatus(
                            gateway: gateway,
                            agentPresetTitle: livePresetTitle ?? header.agentPresetTitle
                        )
                    }
                }

                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button(String(localized: "工作区文件"), systemImage: "folder", action: {
                            showsWorkspaceFiles = true
                        })
                        .disabled(header.sessionID == nil)
                    } label: {
                        Image(systemName: "ellipsis")
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
            .sheet(isPresented: $showsWorkspaceFiles) {
                WorkspaceFilesSheet(store: store, sessionID: header.sessionID)
            }
    }
}

private struct WorkspaceFilesSheet: View {
    @ObservedObject var store: AppStore
    let sessionID: String?
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
                    ContentUnavailableView(
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
                if let progress = store.workspaceFileDownloadProgress {
                    VStack(spacing: 9) {
                        ProgressView(value: progress)
                        HStack {
                            Text("正在下载 · \(Int(progress * 100))%")
                                .font(.caption.monospacedDigit())
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
            store.browseWorkspaceFiles()
            refreshDownloadedPaths()
        }
        .onChange(of: store.workspaceFileEntries) { _, _ in
            refreshDownloadedPaths()
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { refreshDownloadedPaths() }
        }
        .onDisappear {
            if store.workspaceFileDownloadProgress != nil { store.cancelWorkspaceFileDownload() }
        }
        .sheet(item: previewBinding) { file in
            WorkspaceQuickLookPreview(url: file.url)
                .ignoresSafeArea()
        }
        .fullScreenCover(item: codePreviewBinding) { file in
            WorkspaceCodePreview(file: file)
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
        .font(.subheadline)
        .foregroundStyle(.secondary)
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(.thinMaterial)
    }

    private var previewBinding: Binding<WorkspaceLocalFile?> {
        Binding(
            get: {
                guard let file = store.completedWorkspaceFile,
                      file.purpose == "preview",
                      !WorkspaceCodePreviewSupport.shared.isSupported(
                        name: file.name,
                        mediaType: file.mediaType
                      ) else { return nil }
                return file
            },
            set: { if $0 == nil { store.completedWorkspaceFile = nil } }
        )
    }

    private var codePreviewBinding: Binding<WorkspaceLocalFile?> {
        Binding(
            get: {
                guard let file = store.completedWorkspaceFile,
                      file.purpose == "preview",
                      WorkspaceCodePreviewSupport.shared.isSupported(
                        name: file.name,
                        mediaType: file.mediaType
                      ) else { return nil }
                return file
            },
            set: { if $0 == nil { store.completedWorkspaceFile = nil } }
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
                        Image(systemName: workspaceFileIcon(item))
                            .font(.title3)
                            .foregroundStyle(DSHColor.ocean)
                            .frame(width: 28)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(item.name)
                                .foregroundStyle(.primary)
                                .lineLimit(1)
                            Text(workspaceFileDetail(item))
                                .font(.caption)
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
                        .font(.system(size: 9, weight: .semibold))
                }
        } else {
            Image(systemName: isDownloaded ? "checkmark.circle" : "arrow.down.circle")
                .font(.title3)
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

    private func workspaceFileIcon(_ item: GatewayDirectoryItem) -> String {
        let type = item.mediaType ?? ""
        if type.hasPrefix("image/") { return "photo" }
        if type == "application/pdf" { return "doc.richtext" }
        if type.hasPrefix("text/") || item.name.hasSuffix(".md") { return "doc.text" }
        if item.name.hasSuffix(".ipa") || item.name.hasSuffix(".apk") { return "shippingbox" }
        return "doc"
    }
}

private struct WorkspaceQuickLookPreview: UIViewControllerRepresentable {
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
        init(url: URL) { self.url = url }
        func numberOfPreviewItems(in controller: QLPreviewController) -> Int { 1 }
        func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> QLPreviewItem {
            url as NSURL
        }
    }
}

private struct WorkspaceCodePreview: View {
    let file: WorkspaceLocalFile
    @Environment(\.dismiss) private var dismiss
    @State private var document: WorkspaceCodeDocument?
    @State private var errorMessage: String?
    @State private var showsSystemPreview = false

    private let maximumPreviewBytes = 2 * 1_024 * 1_024

    var body: some View {
        NavigationStack {
            Group {
                if let document {
                    WorkspaceCodeDocumentView(document: document)
                } else if let errorMessage {
                    ContentUnavailableView(
                        "无法在应用内预览",
                        systemImage: "doc.text.magnifyingglass",
                        description: Text(errorMessage)
                    )
                } else {
                    ProgressView("正在准备代码预览…")
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            .navigationTitle(file.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("关闭") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("系统打开", systemImage: "arrow.up.forward.app") {
                        showsSystemPreview = true
                    }
                }
            }
        }
        .task(id: file.id) { await loadDocument() }
        .sheet(isPresented: $showsSystemPreview) {
            WorkspaceQuickLookPreview(url: file.url)
                .ignoresSafeArea()
        }
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

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text(document.languageDisplayName)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(DSHColor.ocean)
                Spacer()
                Text("\(document.lineCount) 行 · UTF-8")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .padding(.horizontal, 14)
            .frame(height: 38)
            .background(Color(uiColor: .secondarySystemBackground))

            WorkspaceCodeTextKitView(document: document, colorScheme: colorScheme)
        }
    }
}

private struct WorkspaceCodeTextKitView: UIViewRepresentable {
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

final class WorkspaceCodeTextKitContainer: UIView, UITextViewDelegate {
    private let gutterView = UIView()
    private let lineNumberLabel = UILabel()
    private let codeTextView = UITextView(frame: .zero, textContainer: nil)
    private let codeFont = UIFont.monospacedSystemFont(ofSize: 13, weight: .regular)
    private let lineSpacing: CGFloat = 4
    private var lineCount = 1
    private var maximumLineWidth: CGFloat = 0
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

        codeTextView.translatesAutoresizingMaskIntoConstraints = false
        codeTextView.delegate = self
        codeTextView.isEditable = false
        codeTextView.isSelectable = true
        codeTextView.isScrollEnabled = true
        codeTextView.alwaysBounceVertical = true
        codeTextView.showsVerticalScrollIndicator = true
        codeTextView.showsHorizontalScrollIndicator = true
        codeTextView.contentInsetAdjustmentBehavior = .never
        codeTextView.contentInset = .zero
        codeTextView.textContainerInset = UIEdgeInsets(top: 14, left: 14, bottom: 24, right: 28)
        codeTextView.textContainer.lineFragmentPadding = 0
        codeTextView.textContainer.widthTracksTextView = false
        codeTextView.textContainer.heightTracksTextView = false
        codeTextView.textContainer.lineBreakMode = .byClipping
        codeTextView.layoutManager.allowsNonContiguousLayout = true
        addSubview(codeTextView)

        NSLayoutConstraint.activate([
            gutterView.leadingAnchor.constraint(equalTo: leadingAnchor),
            gutterView.topAnchor.constraint(equalTo: topAnchor),
            gutterView.bottomAnchor.constraint(equalTo: bottomAnchor),
            gutterView.widthAnchor.constraint(equalToConstant: 52),
            codeTextView.leadingAnchor.constraint(equalTo: gutterView.trailingAnchor),
            codeTextView.trailingAnchor.constraint(equalTo: trailingAnchor),
            codeTextView.topAnchor.constraint(equalTo: topAnchor),
            codeTextView.bottomAnchor.constraint(equalTo: bottomAnchor)
        ])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func configure(document: WorkspaceCodeDocument, isDark: Bool) {
        let revision = "\(document.text.hashValue)-\(document.tokens.count)-\(isDark)"
        guard configuredRevision != revision else { return }
        configuredRevision = revision
        lineCount = max(1, Int(document.lineCount))

        let codeBackground = isDark
            ? UIColor(red: 0.05, green: 0.07, blue: 0.09, alpha: 1)
            : UIColor(red: 0.98, green: 0.985, blue: 0.99, alpha: 1)
        let gutterBackground = isDark
            ? UIColor(red: 0.086, green: 0.106, blue: 0.133, alpha: 1)
            : UIColor.secondarySystemBackground
        backgroundColor = codeBackground
        codeTextView.backgroundColor = codeBackground
        gutterView.backgroundColor = gutterBackground
        codeTextView.tintColor = UIColor(DSHColor.ocean)
        codeTextView.attributedText = WorkspaceCodeAttributedText.highlight(document, isDark: isDark)
        codeTextView.textContainer.widthTracksTextView = false
        codeTextView.textContainer.lineBreakMode = .byClipping

        let paragraph = NSMutableParagraphStyle()
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
        let visibleWidth = max(1, codeTextView.bounds.width - insets.left - insets.right)
        let containerWidth = max(visibleWidth, ceil(maximumLineWidth) + 1)
        if abs(codeTextView.textContainer.size.width - containerWidth) > 0.5 {
            codeTextView.textContainer.size = CGSize(
                width: containerWidth,
                height: CGFloat.greatestFiniteMagnitude
            )
        }
        codeTextView.textContainer.widthTracksTextView = false
        updateLineNumberPosition()
    }

    func scrollViewDidScroll(_ scrollView: UIScrollView) {
        guard scrollView === codeTextView else { return }
        updateLineNumberPosition()
    }

    private func updateLineNumberPosition() {
        let rowHeight = codeFont.lineHeight + lineSpacing
        let labelHeight = CGFloat(lineCount) * codeFont.lineHeight +
            CGFloat(max(0, lineCount - 1)) * lineSpacing
        lineNumberLabel.frame = CGRect(
            x: 0,
            y: 14 - codeTextView.contentOffset.y,
            width: 40,
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
                .font: UIFont.monospacedSystemFont(ofSize: 13, weight: .regular),
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
    let onExported: (URL) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onExported: onExported) }

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let controller = UIDocumentPickerViewController(forExporting: [url], asCopy: true)
        controller.delegate = context.coordinator
        return controller
    }
    func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) {}

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let onExported: (URL) -> Void

        init(onExported: @escaping (URL) -> Void) {
            self.onExported = onExported
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

/// Connection changes are scoped to this tiny view instead of rebuilding the
/// navigation shell (or the toolbar declaration containing it).
private struct ConversationNavigationStatus: View {
    @ObservedObject var gateway: GatewayClient
    let agentPresetTitle: String

    var body: some View {
        HStack(spacing: 7) {
            ConnectionDot(state: gateway.state)
            Text(agentPresetTitle)
                .font(.caption.weight(.medium))
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .fixedSize(horizontal: true, vertical: false)
        }
    }
}
