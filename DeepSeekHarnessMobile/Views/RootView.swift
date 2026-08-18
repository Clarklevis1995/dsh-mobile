import SwiftUI
import UIKit

struct RootView: View {
    @EnvironmentObject private var store: AppStore
    @State private var navigationPath: [AppRoute] = []

    var body: some View {
        NavigationStack(path: $navigationPath) {
            WorkspaceView(
                onOpenSession: { session in
                    store.open(session)
                    navigate(to: .conversation)
                },
                onNewSession: {
                    store.startNewSession()
                    navigate(to: .conversation)
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
            if case .disconnected = store.gateway.state { store.connect() }
        }
        .onChange(of: navigationPath) { _, path in
            if path.isEmpty { store.resumeWorkspace() }
        }
        .alert("DeepSeek Harness", isPresented: Binding(get: { store.lastError != nil }, set: { if !$0 { store.lastError = nil } })) {
            Button("好", role: .cancel) { store.lastError = nil }
        } message: { Text(store.lastError ?? "") }
    }

    @ViewBuilder
    private func destination(for route: AppRoute) -> some View {
        switch route {
        case .conversation:
            ConversationView(
                initialScrollAnchor: store.selectedSessionId.flatMap { store.conversationScrollAnchor(for: $0) },
                initiallyManual: store.selectedSessionId.map { store.hasManualConversationPosition(for: $0) } ?? false,
                onBack: popToRoot
            )
            .toolbar(.hidden, for: .navigationBar)
            .background(NavigationSwipeBackEnabler())
        case .settings:
            SettingsView()
        }
    }

    private func navigate(to route: AppRoute) {
        guard navigationPath.last != route else { return }
        navigationPath.append(route)
    }

    private func popToRoot() {
        navigationPath.removeAll()
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
            let canBegin = (navigationController?.viewControllers.count ?? 0) > 1
            if canBegin {
                // Resign any active first responder (e.g. the composer text
                // field) before the interactive pop starts, otherwise the
                // keyboard dismissal and the navigation transition can land
                // in the same frame and trigger SwiftUI's
                // "NavigationRequestObserver tried to update multiple times
                // per frame" warning.
                UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
            }
            return canBegin
        }
    }
}
