import SwiftUI

private final class AgentNotificationAppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        AppInterfaceOrientationPolicy.orientations
    }

    func applicationDidBecomeActive(_ application: UIApplication) {
        AgentLiveActivityManager.shared.applicationDidBecomeActive()
    }

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        _ = AgentUserNotificationManager.shared
        return true
    }
}

@main
struct DeepSeekHarnessMobileApp: App {
    @UIApplicationDelegateAdaptor(AgentNotificationAppDelegate.self) private var notificationAppDelegate
    @StateObject private var hosts = MultiGatewayStore()

    var body: some Scene {
        WindowGroup {
            Group {
                if hosts.isReady { RootView() }
                else { ProgressView() }
            }
                .id(ObjectIdentifier(hosts.activeStore))
                .environmentObject(hosts.activeStore)
                .environmentObject(hosts)
                .modifier(AppFontScope())
                .task {
                    await AgentUserNotificationManager.shared.requestAuthorizationIfNeeded()
                }
                .alert("主机连接", isPresented: Binding(get: { hosts.error != nil }, set: { if !$0 { hosts.error = nil } })) {
                    Button("好", role: .cancel) { hosts.error = nil; hosts.cancelPairing() }
                } message: { Text(hosts.error ?? "") }
        }
    }
}
