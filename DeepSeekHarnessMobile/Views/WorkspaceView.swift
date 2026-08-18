import SwiftUI

struct WorkspaceView: View {
    @EnvironmentObject private var store: AppStore
    let onOpenSession: (SessionSummary) -> Void
    let onNewSession: () -> Void
    let onSettings: () -> Void
    @State private var searchQuery = ""
    @State private var showsDirectoryBrowser = false

    var body: some View {
        ZStack {
            DeepOceanBackground()
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 18) {
                    header.id("workspace-header")
                    Spacer(minLength: 108)
                    VStack(alignment: .leading, spacing: 7) {
                        Text("探索未至之境")
                            .font(.system(size: 32, weight: .bold))
                        Text("DeepSeek Harness 预览版")
                            .font(.subheadline).foregroundStyle(.white.opacity(0.65))
                    }
                    .id("workspace-hero")
                    workspaceCard.id("workspace-card")
                    newSessionButton.id("workspace-new-session")
                    sessionsHeader.id("workspace-sessions-header")
                    sessionSearch.id("workspace-session-search")
                    if displayedSessions.isEmpty {
                        emptySessions.id("workspace-sessions-empty")
                    } else {
                        sessionsList
                    }
                    Spacer(minLength: 24)
                }
                .scrollTargetLayout()
                .padding(.horizontal, 22).padding(.top, 18)
            }
            .defaultScrollAnchor(.top)
            .scrollPosition(id: $store.workspaceScrollAnchor, anchor: .top)
        }
        .foregroundStyle(.white)
        .onAppear {
            store.refreshRemoteState()
        }
        .onChange(of: searchQuery) { _, value in store.search(value) }
        .sheet(isPresented: $showsDirectoryBrowser) {
            DirectoryBrowserSheet()
                .environmentObject(store)
        }
    }

    private var header: some View {
        HStack {
            HarnessMark()
            Spacer()
            settingsButton
        }
    }

    @ViewBuilder
    private var settingsButton: some View {
        if #available(iOS 26.0, *) {
            Button(action: onSettings) { settingsButtonLabel }
                .buttonStyle(.glass)
                .buttonBorderShape(.circle)
                .accessibilityLabel("设置")
        } else {
            Button(action: onSettings) { settingsButtonLabel }
                .buttonStyle(.plain)
                .background(.ultraThinMaterial, in: Circle())
                .overlay(Circle().stroke(.white.opacity(0.25), lineWidth: 0.8))
                .accessibilityLabel("设置")
        }
    }

    private var settingsButtonLabel: some View {
        Image(systemName: "gearshape.fill")
            .font(.system(size: 17, weight: .semibold))
            .frame(width: 40, height: 40)
            .contentShape(Circle())
    }

    private var workspaceCard: some View {
        Menu {
            Button {
                store.selectUngroupedWorkspace()
            } label: {
                Label {
                    Text("未分组")
                } icon: {
                    Image(systemName: store.isUngroupedWorkspaceSelected ? "checkmark.circle.fill" : "tray")
                }
            }

            if !store.workspaces.isEmpty { Divider() }

            ForEach(store.workspaces) { workspace in
                Button {
                    store.selectWorkspace(workspace)
                } label: {
                    Label {
                        Text(workspace.title)
                    } icon: {
                        Image(systemName: workspace.id == store.activeWorkspace?.id ? "checkmark.circle.fill" : "folder")
                    }
                }
            }

            Divider()

            Button {
                showsDirectoryBrowser = true
            } label: {
                Label("添加工作区", systemImage: "plus")
            }
        } label: {
            workspaceCardLabel
        }
        .buttonStyle(.plain)
        .accessibilityLabel("选择工作区")
    }

    private var workspaceCardLabel: some View {
        HStack(spacing: 12) {
            Image(systemName: "folder").foregroundStyle(.blue).font(.title3)
            VStack(alignment: .leading, spacing: 2) {
                Text(workspaceDisplayTitle).font(.subheadline.weight(.semibold))
                Text(workspaceDisplayPath).font(.caption).foregroundStyle(.white.opacity(0.55)).lineLimit(1)
            }
            Spacer()
            ConnectionDot(state: store.gateway.state)
            Image(systemName: "chevron.down").font(.caption).foregroundStyle(.white.opacity(0.55))
        }
        .padding(16)
        .background(.white.opacity(0.055), in: RoundedRectangle(cornerRadius: 15))
        .overlay(RoundedRectangle(cornerRadius: 15).stroke(.white.opacity(0.14)))
        .contentShape(RoundedRectangle(cornerRadius: 15))
    }

    private var sessionsHeader: some View {
        HStack {
            Text("最近会话").font(.headline)
            Spacer()
        }
    }

    private var sessionSearch: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass").foregroundStyle(.white.opacity(0.5))
            TextField("搜索会话内容", text: $searchQuery).textInputAutocapitalization(.never)
        }
        .padding(.horizontal, 12).frame(height: 42)
        .background(.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 13))
        .overlay(RoundedRectangle(cornerRadius: 13).stroke(.white.opacity(0.1)))
    }

    private var emptySessions: some View {
        Text("暂无已知会话。连接服务后创建第一个任务。")
            .font(.subheadline).foregroundStyle(.white.opacity(0.5))
            .frame(maxWidth: .infinity, alignment: .leading).padding(18)
            .background(.white.opacity(0.04), in: RoundedRectangle(cornerRadius: 15))
    }

    private var displayedSessions: [SessionSummary] {
        let workspaceSessions: [SessionSummary]
        if store.isUngroupedWorkspaceSelected {
            workspaceSessions = store.ungroupedSessions
        } else if let workspace = store.activeWorkspace {
            let ids = Set(workspace.sessionIds)
            workspaceSessions = store.sessions.filter { ids.contains($0.id) }
        } else {
            workspaceSessions = store.sessions
        }
        guard !searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return workspaceSessions
        }
        let ids = Set(store.searchResults.map(\.sessionId))
        return workspaceSessions.filter { ids.contains($0.id) || $0.title.localizedCaseInsensitiveContains(searchQuery) }
    }

    private var workspaceDisplayTitle: String {
        store.isUngroupedWorkspaceSelected ? "未分组" : (store.activeWorkspace?.title ?? "DeepseekHarnessProject")
    }

    private var workspaceDisplayPath: String {
        if store.isUngroupedWorkspaceSelected {
            return "\(store.ungroupedSessions.count) 个未归属会话"
        }
        return store.activeWorkspace?.path ?? "通过 Mobile Gateway 连接"
    }

    private var sessionsList: some View {
        LazyVStack(spacing: 0) {
            ForEach(displayedSessions.prefix(12)) { session in
                Button { onOpenSession(session) } label: { sessionRow(session) }
                    .buttonStyle(.plain)
                    .id("workspace-session-\(session.id)")

                Divider()
                    .overlay(.white.opacity(0.1))
                    .padding(.leading, 18)
            }
        }
    }

    private func sessionRow(_ session: SessionSummary) -> some View {
        HStack(spacing: 11) {
            Circle()
                .fill(session.isRunning ? DSHColor.success : (session.hasUnread ? DSHColor.ocean : .white.opacity(0.35)))
                .frame(width: 7, height: 7)
                .shadow(color: session.isRunning ? DSHColor.success : .clear, radius: 5)
            VStack(alignment: .leading, spacing: 3) {
                Text(session.title).lineLimit(1).font(.subheadline.weight(.medium))
                Text(String(session.id.prefix(16))).font(.caption2.monospaced()).foregroundStyle(.white.opacity(0.42))
            }
            Spacer()
            Text(session.isRunning ? "运行中" : session.lastActivity.formatted(.relative(presentation: .named)))
                .font(.caption).foregroundStyle(session.isRunning ? .blue : .white.opacity(0.48))
        }
        .padding(.horizontal, 4)
        .padding(.vertical, 10)
        .contentShape(Rectangle())
    }

    @ViewBuilder
    private var newSessionButton: some View {
        if #available(iOS 26.0, *) {
            Button(action: onNewSession) {
                newSessionButtonLabel
            }
            .buttonStyle(.glass(.clear.tint(DSHColor.navy.opacity(0.22))))
            .buttonBorderShape(.roundedRectangle(radius: 18))
            .buttonSizing(.flexible)
        } else {
            Button(action: onNewSession) {
                newSessionButtonLabel
            }
            .buttonStyle(.plain)
            .glassSurface(radius: 18, dark: true, tint: .white.opacity(0.08))
        }
    }

    private var newSessionButtonLabel: some View {
        HStack(spacing: 10) {
            Image(systemName: "plus").font(.system(size: 15, weight: .semibold))
                .frame(width: 28, height: 28)
                .background(.white.opacity(0.1), in: Circle())
            Text("新建会话").font(.headline)
        }
        .frame(maxWidth: .infinity).frame(height: 38)
        .contentShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
    }
}

private struct DirectoryBrowserSheet: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @State private var creatingPath: String?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button {
                        if let parentPath { store.browseDirectories(path: parentPath) }
                    } label: {
                        directoryRow(icon: "arrowshape.turn.up.left", title: "..", subtitle: "返回上一级")
                    }
                    .disabled(parentPath == nil)

                    ForEach(store.directoryEntries) { entry in
                        Button {
                            store.browseDirectories(path: entry.path)
                        } label: {
                            directoryRow(
                                icon: entry.hidden ? "folder.badge.questionmark" : "folder",
                                title: entry.name,
                                subtitle: entry.hidden ? "隐藏目录" : nil
                            )
                        }
                    }
                } header: {
                    VStack(alignment: .leading, spacing: 5) {
                        Text("当前目录")
                        Text(store.directoryPath ?? "正在读取…")
                            .font(.caption.monospaced())
                            .textCase(nil)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .allowsHitTesting(!store.directoryIsLoading)
            .overlay {
                if store.directoryIsLoading && store.directoryEntries.isEmpty {
                    ProgressView("正在读取远程目录…")
                }
            }
            .safeAreaInset(edge: .bottom) {
                createWorkspaceBar
            }
            .navigationTitle("选择工作区目录")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("取消") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationBackground(.ultraThinMaterial)
        .onAppear {
            store.directoryEntries = []
            store.browseDirectories()
        }
        .onChange(of: store.selectedWorkspaceId) { _, _ in
            guard let creatingPath,
                  store.activeWorkspace?.path == creatingPath else { return }
            dismiss()
        }
    }

    private var parentPath: String? {
        guard store.directoryCrumbs.count > 1 else { return nil }
        return store.directoryCrumbs.dropLast().last?.path
    }

    private func directoryRow(icon: String, title: String, subtitle: String?) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .foregroundStyle(DSHColor.ocean)
                .frame(width: 24)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).foregroundStyle(Color(uiColor: .label))
                if let subtitle {
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(Color(uiColor: .secondaryLabel))
                }
            }
            Spacer()
            Image(systemName: "chevron.right")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.tertiary)
        }
        .contentShape(Rectangle())
    }

    private var createWorkspaceBar: some View {
        VStack(spacing: 8) {
            if let path = store.directoryPath {
                Text(path)
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Button {
                guard let path = store.directoryPath else { return }
                creatingPath = path
                store.createWorkspace(path: path)
            } label: {
                HStack(spacing: 9) {
                    if store.workspaceCreationIsLoading {
                        ProgressView().tint(.white)
                    } else {
                        Image(systemName: "plus")
                    }
                    Text("在当前目录创建工作区")
                        .fontWeight(.semibold)
                }
                .frame(maxWidth: .infinity)
                .frame(height: 44)
                .foregroundStyle(.white)
                .background {
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .fill(Color.black)
                }
                .overlay {
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .stroke(Color.white.opacity(0.18), lineWidth: 0.8)
                }
                .contentShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
            .buttonStyle(.plain)
            .disabled(store.directoryPath == nil || store.workspaceCreationIsLoading)
            .allowsHitTesting(!store.directoryIsLoading)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 9)
        .background(.ultraThinMaterial)
    }
}
