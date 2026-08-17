import SwiftUI

struct WorkspaceView: View {
    @EnvironmentObject private var store: AppStore
    let onOpenSession: (SessionSummary) -> Void
    let onNewSession: () -> Void
    let onSettings: () -> Void
    @State private var searchQuery = ""

    var body: some View {
        ZStack {
            DeepOceanBackground()
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 22) {
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
                        ForEach(displayedSessions.prefix(12)) { session in
                            Button { onOpenSession(session) } label: { sessionRow(session) }
                                .buttonStyle(.plain)
                                .id("workspace-session-\(session.id)")
                        }
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
        HStack(spacing: 12) {
            Image(systemName: "folder").foregroundStyle(.blue).font(.title3)
            VStack(alignment: .leading, spacing: 2) {
                Text(store.activeWorkspace?.title ?? "DeepseekHarnessProject").font(.subheadline.weight(.semibold))
                Text(store.activeWorkspace?.path ?? "通过 Mobile Gateway 连接").font(.caption).foregroundStyle(.white.opacity(0.55)).lineLimit(1)
            }
            Spacer()
            ConnectionDot(state: store.gateway.state)
            Image(systemName: "chevron.down").font(.caption).foregroundStyle(.white.opacity(0.55))
        }
        .padding(16)
        .background(.white.opacity(0.055), in: RoundedRectangle(cornerRadius: 15))
        .overlay(RoundedRectangle(cornerRadius: 15).stroke(.white.opacity(0.14)))
    }

    private var sessionsHeader: some View {
        HStack {
            Text("最近会话").font(.headline)
            Spacer()
            Text(store.gateway.state.label).font(.caption).foregroundStyle(.white.opacity(0.55))
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
        guard !searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return store.sessions }
        let ids = Set(store.searchResults.map(\.sessionId))
        return store.sessions.filter { ids.contains($0.id) || $0.title.localizedCaseInsensitiveContains(searchQuery) }
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
        .padding(15)
        .background(.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 15))
        .overlay(RoundedRectangle(cornerRadius: 15).stroke(.white.opacity(0.11)))
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
