import SwiftUI
import UIKit

struct RootView: View {
    @EnvironmentObject private var store: AppStore

    var body: some View {
        NavigationStack(path: navigationPath) {
            WorkspaceView()
                .navigationDestination(for: AppRoute.self) { route in
                    destination(for: route)
                }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .task {
            if case .disconnected = store.gateway.state { store.connect() }
        }
        .alert("DeepSeek Harness", isPresented: Binding(get: { store.lastError != nil }, set: { if !$0 { store.lastError = nil } })) {
            Button("好", role: .cancel) { store.lastError = nil }
        } message: { Text(store.lastError ?? "") }
    }

    @ViewBuilder
    private func destination(for route: AppRoute) -> some View {
        Group {
            switch route {
            case .conversation:
                ConversationView(
                    initialScrollAnchor: store.selectedSessionId.flatMap { store.conversationScrollAnchor(for: $0) },
                    initiallyManual: store.selectedSessionId.map { store.hasManualConversationPosition(for: $0) } ?? false
                )
            case .settings:
                SettingsView()
            }
        }
        .toolbar(.hidden, for: .navigationBar)
        .background(NavigationSwipeBackEnabler())
    }

    private var navigationPath: Binding<[AppRoute]> {
        Binding(
            get: {
                switch store.selectedTab {
                case 1: [.conversation]
                case 2: [.settings]
                default: []
                }
            },
            set: { path in
                guard let route = path.last else {
                    if store.selectedTab != 0 { store.showWorkspace() }
                    return
                }
                switch route {
                case .conversation: store.selectedTab = 1
                case .settings: store.selectedTab = 2
                }
            }
        )
    }

    private enum AppRoute: Hashable {
        case conversation
        case settings
    }
}

/// SwiftUI disables the navigation controller's edge-pop gesture when its
/// navigation bar/back item is hidden. The app draws its own header, so restore
/// that system gesture without replacing it with a custom full-screen drag.
private struct NavigationSwipeBackEnabler: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> Controller {
        Controller()
    }

    func updateUIViewController(_ controller: Controller, context: Context) {
        controller.enableSwipeBack()
    }

    final class Controller: UIViewController, UIGestureRecognizerDelegate {
        override func viewDidAppear(_ animated: Bool) {
            super.viewDidAppear(animated)
            enableSwipeBack()
        }

        func enableSwipeBack() {
            guard let gesture = navigationController?.interactivePopGestureRecognizer else { return }
            gesture.isEnabled = true
            gesture.delegate = self
        }

        func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
            (navigationController?.viewControllers.count ?? 0) > 1
        }
    }
}
