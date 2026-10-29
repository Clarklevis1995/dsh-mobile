import SwiftUI

/// 侧栏触发的导航动作，由会话页传入，最终交给持有导航栈的 RootNavigationHost。
/// 默认值都是空操作，侧栏脱离导航栈单独预览时不会崩溃。
struct ConversationSidebarActions {
    var openSession: (SessionSummary) -> Void = { _ in }
    var openPlugins: () -> Void = {}
    var openScheduledTasks: () -> Void = {}
    var openSettings: () -> Void = {}
}

/// 会话页按需弹出的悬浮侧栏：
/// Logo → 插件 / 定时任务 → 工作区（点开显示会话）→ 底部设置。
/// 展示和关闭由会话页管理，不参与根导航容器的宽度分配。
struct ConversationSidebar: View {
    static let width: CGFloat = 300
    private static let ungroupedKey = "__ungrouped__"

    /// 刻意不观察整个 AppStore（与 RootNavigationHost 同样的做法）：会话页流式输出时
    /// store 的其他 @Published 每个 token 都在变，观察整个对象会让侧栏每秒重算几十次。
    /// 只订阅侧栏真正显示的三项，分组结果缓存在 @State 里。
    let store: AppStore
    let actions: ConversationSidebarActions
    @Environment(\.colorScheme) private var colorScheme
    /// 展开的工作区 id（逗号分隔），只用于持久化；渲染读 expandedKeys。
    @AppStorage("conversationSidebar.expandedWorkspaces") private var expandedStorage = ""
    @State private var expandedKeys: Set<String> = []
    @State private var workspaces: [GatewayWorkspace] = []
    @State private var sessions: [SessionSummary] = []
    @State private var selectedSessionID: String?
    @State private var groups = SessionGroups()

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
            navigationRow("插件", action: actions.openPlugins) {
                Image("DshPluginPinwheel")
                    .renderingMode(.template)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 20, height: 20)
            }
            navigationRow("定时任务", action: actions.openScheduledTasks) {
                Image(systemName: "clock")
                    .resizable()
                    .scaledToFit()
                    .fontWeight(.regular)
                    .frame(width: 20, height: 20)
            }

            Text("工作区")
                .appFont(.system(size: 14))
                .foregroundStyle(.secondary)
                .padding(.horizontal, 18)
                .padding(.top, 18)
                .padding(.bottom, 6)

            ScrollView(showsIndicators: false) {
                LazyVStack(alignment: .leading, spacing: 2) {
                    ForEach(workspaces) { workspace in
                        workspaceSection(
                            key: workspace.id,
                            title: workspace.title,
                            icon: "folder",
                            sessions: groups.byWorkspace[workspace.id] ?? []
                        )
                    }
                    workspaceSection(
                        key: Self.ungroupedKey,
                        title: String(localized: "未分组"),
                        icon: "tray",
                        sessions: groups.ungrouped
                    )
                }
                .padding(.horizontal, 8)
                .padding(.bottom, 12)
            }
            .frame(maxHeight: .infinity)

            Divider().opacity(0.3)
            navigationRow("设置", action: actions.openSettings) {
                Image(systemName: "gearshape")
                    .appFont(.system(size: 18, weight: .regular))
            }
            .padding(.vertical, 6)
        }
        .frame(width: Self.width)
        .frame(maxHeight: .infinity, alignment: .top)
        .foregroundStyle(Color(uiColor: .label))
        .background {
            (colorScheme == .dark
                ? Color(red: 28.0 / 255, green: 28.0 / 255, blue: 30.0 / 255)
                : Color(white: 0.96))
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("conversation-sidebar")
        .onAppear {
            expandedKeys = Set(expandedStorage.split(separator: ",").map(String.init))
            workspaces = store.workspaces
            sessions = store.sessions
            selectedSessionID = store.selectedSessionId
            regroup()
            expandCurrentWorkspace()
        }
        // @Published 在 willSet 时发出：必须用回调里的新值，不能回头读 store（那时还是旧值）。
        .onReceive(store.$workspaces) { value in
            workspaces = value
            regroup()
        }
        .onReceive(store.$sessions) { value in
            sessions = value
            regroup()
        }
        .onReceive(store.$selectedSessionId) { selectedSessionID = $0 }
    }

    // MARK: - 顶部

    private var header: some View {
        HarnessMark(onDarkBackground: colorScheme == .dark, width: 170)
            .frame(maxWidth: .infinity, minHeight: 36, alignment: .leading)
            .padding(.horizontal, 18)
            .padding(.vertical, 12)
    }

    // MARK: - 工作区与会话

    @ViewBuilder
    private func workspaceSection(
        key: String,
        title: String,
        icon: String,
        sessions: [SessionSummary]
    ) -> some View {
        let isExpanded = expandedKeys.contains(key)
        let containsCurrent = selectedSessionID.map { id in sessions.contains { $0.id == id } } ?? false
        Button {
            withAnimation(.easeInOut(duration: 0.18)) { toggle(key) }
        } label: {
            HStack(spacing: 10) {
                Image(systemName: icon)
                    .appFont(.system(size: 16))
                    .frame(width: 22)
                    .foregroundStyle(containsCurrent ? DSHColor.ocean : .secondary)
                Text(title)
                    .lineLimit(1)
                    .appFont(.system(size: 16))
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 9)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title)
        .accessibilityValue(isExpanded ? Text("已展开") : Text("已收起"))

        if isExpanded {
            let sessions = sortedByActivity(sessions)
            if sessions.isEmpty {
                Text("暂无会话")
                    .appFont(.system(size: 13))
                    .foregroundStyle(.tertiary)
                    .padding(.leading, 42)
                    .padding(.vertical, 6)
            } else {
                ForEach(sessions) { session in
                    sessionRow(session)
                }
            }
        }
    }

    private func sessionRow(_ session: SessionSummary) -> some View {
        let selected = session.id == selectedSessionID
        // 是否已在该会话页由导航层判断，仍然发送动作以便关闭面板。
        return Button {
            actions.openSession(session)
        } label: {
            HStack(spacing: 8) {
                Circle()
                    .fill(session.isRunning ? Color.green : .clear)
                    .frame(width: 6, height: 6)
                    .padding(.horizontal, 2)
                Text(session.title.isEmpty ? String(localized: "未命名会话") : session.title)
                    .lineLimit(1)
                    .appFont(.system(size: 15, weight: selected ? .medium : .regular))
                Spacer(minLength: 6)
                Text(session.lastActivity.formatted(.relative(presentation: .named)))
                    .lineLimit(1)
                    .appFont(.system(size: 12))
                    .foregroundStyle(.secondary)
            }
            .padding(.leading, 22)
            .padding(.trailing, 10)
            .padding(.vertical, 9)
            .background {
                if selected {
                    RoundedRectangle(cornerRadius: 9, style: .continuous)
                        .fill(Color.primary.opacity(colorScheme == .dark ? 0.12 : 0.07))
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("conversation-sidebar-session-\(session.id)")
    }

    /// 一次遍历把可见会话分到所属工作区（同一会话属于多个工作区时各列一份），
    /// 不属于任何工作区的归入「未分组」，与 AppStore.ungroupedSessions 口径一致。
    /// 只在会话或工作区列表变化时调用。
    private func regroup() {
        groups = SessionGroups.group(sessions: sessions, workspaces: workspaces)
    }

    private func sortedByActivity(_ sessions: [SessionSummary]) -> [SessionSummary] {
        sessions.sorted { $0.lastActivity > $1.lastActivity }
    }

    // MARK: - 展开状态

    private func setExpanded(_ keys: Set<String>) {
        expandedKeys = keys
        expandedStorage = keys.sorted().joined(separator: ",")
    }

    private func toggle(_ key: String) {
        var keys = expandedKeys
        if keys.contains(key) { keys.remove(key) } else { keys.insert(key) }
        setExpanded(keys)
    }

    /// 进入会话页时，保证当前会话所在的工作区是展开的（不改动其他工作区的展开状态）。
    private func expandCurrentWorkspace() {
        let key: String?
        if let sessionID = selectedSessionID {
            key = workspaces.first(where: { $0.sessionIds.contains(sessionID) })?.id ?? Self.ungroupedKey
        } else if store.isUngroupedWorkspaceSelected {
            key = Self.ungroupedKey
        } else {
            key = store.activeWorkspace?.id
        }
        guard let key, !expandedKeys.contains(key) else { return }
        setExpanded(expandedKeys.union([key]))
    }

    // MARK: - 导航行

    private func navigationRow<Icon: View>(
        _ title: String,
        action: @escaping () -> Void,
        @ViewBuilder icon: () -> Icon
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                icon().frame(width: 22)
                Text(title).appFont(.system(size: 16))
                Spacer(minLength: 0)
            }
            .frame(minHeight: 44)
            .padding(.horizontal, 18)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// 侧栏的工作区分组结果。抽成独立类型是为了可以单测 —— 原本是 View 内部的私有逻辑。
struct SessionGroups: Equatable {
    var byWorkspace: [String: [SessionSummary]] = [:]
    var ungrouped: [SessionSummary] = []

    /// 同一会话属于多个工作区时，每个工作区各列一份；
    /// 不属于任何工作区的会话归入「未分组」。没有会话内容的会话不进入任何一组。
    static func group(sessions: [SessionSummary], workspaces: [GatewayWorkspace]) -> SessionGroups {
        var owners: [String: [String]] = [:]
        for workspace in workspaces {
            for sessionID in workspace.sessionIds {
                owners[sessionID, default: []].append(workspace.id)
            }
        }
        var result = SessionGroups()
        for session in sessions where session.isVisibleInHistory {
            if let workspaceIDs = owners[session.id] {
                for workspaceID in workspaceIDs {
                    result.byWorkspace[workspaceID, default: []].append(session)
                }
            } else {
                result.ungrouped.append(session)
            }
        }
        return result
    }
}
