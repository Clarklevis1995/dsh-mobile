import SwiftUI

struct RootView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        RootNavigationHost(store: store)
            .equatable()
            .alert("DeepSeek Harness", isPresented: Binding(get: { store.lastError != nil }, set: { if !$0 { store.lastError = nil } })) {
                Button("好", role: .cancel) { store.lastError = nil }
            } message: { Text(store.lastError ?? "") }
            .onChange(of: scenePhase) { _, phase in
                store.handleScenePhase(phase)
            }
    }
}

/// The navigation tree deliberately holds `AppStore` as an unobserved
/// reference. `RootView` can still present global errors, while session/history
/// publications no longer invalidate the `NavigationStack` that owns the bar.
private struct RootNavigationHost: View, Equatable {
    let store: AppStore
    @State private var navigationPath: [AppRoute] = []

    static func == (lhs: Self, rhs: Self) -> Bool {
        lhs.store === rhs.store
    }

    var body: some View {
        NavigationStack(path: $navigationPath) {
            WorkspaceView(
                onOpenSession: { session in
                    let header = conversationHeader(for: session)
                    store.prepareConversation(for: session)
                    navigate(to: .conversation(header))
                },
                onNewSession: {
                    let header = conversationHeader(for: nil)
                    store.prepareNewConversation()
                    navigate(to: .conversation(header))
                },
                onSettings: {
                    navigate(to: .settings)
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
    }

    @ViewBuilder
    private func destination(for route: AppRoute) -> some View {
        switch route {
        case .conversation(let header):
            ConversationNavigationShell(
                header: header,
                gateway: store.gateway,
                onReloadHistory: {
                    if let id = header.sessionID ?? store.selectedSessionId {
                        store.loadHistory(for: id)
                    }
                },
                onPing: {
                    store.gateway.ping()
                },
                onActivate: {
                    await store.activatePreparedConversation(sessionID: header.sessionID)
                }
            ) {
                ConversationView()
            }
        case .settings:
            SettingsView()
        }
    }

    /// Resolves the small amount of route chrome before the push begins.
    /// Conversation content can then load and stream independently without
    /// participating in navigation-bar preference resolution.
    private func conversationHeader(for session: SessionSummary?) -> ConversationNavigationHeader {
        let presetID = session?.agentPreset ?? store.agentPresetDefault
        return ConversationNavigationHeader(
            sessionID: session?.id,
            title: session?.title ?? "新建 DeepSeek Harness",
            agentPresetTitle: agentPresetDisplayName(for: presetID)
        )
    }

    private func agentPresetDisplayName(for id: String?) -> String {
        guard let id else { return "Agent" }
        if let preset = store.agentPresets.first(where: { $0.id == id }) {
            return preset.displayName
        }
        switch id {
        case "standard": return "标准模式"
        case "code": return "PTC 模式"
        case "minimal": return "极简模式"
        case "cordis": return "创造模式"
        default: return id
        }
    }

    private func navigate(to route: AppRoute) {
        guard navigationPath.last != route else { return }
        navigationPath.append(route)
    }

    private enum AppRoute: Hashable {
        case conversation(ConversationNavigationHeader)
        case settings
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
    let onReloadHistory: () -> Void
    let onPing: () -> Void
    let onActivate: () async -> Void
    @ViewBuilder let content: () -> Content

    var body: some View {
        content()
            .navigationTitle(header.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbarRole(.editor)
            .toolbarBackground(.hidden, for: .navigationBar)
            .toolbar {
                if #available(iOS 26.0, *) {
                    ToolbarItem(placement: .topBarTrailing) {
                        ConversationNavigationStatus(
                            gateway: gateway,
                            agentPresetTitle: header.agentPresetTitle
                        )
                    }
                    .sharedBackgroundVisibility(.hidden)

                    ToolbarSpacer(.fixed, placement: .topBarTrailing)
                } else {
                    ToolbarItem(placement: .topBarTrailing) {
                        ConversationNavigationStatus(
                            gateway: gateway,
                            agentPresetTitle: header.agentPresetTitle
                        )
                    }
                }

                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button("重新加载历史", systemImage: "clock.arrow.circlepath", action: onReloadHistory)
                        Button("发送 Ping", systemImage: "wave.3.right", action: onPing)
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
