import SwiftUI
import DeepSeekHarnessShared

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
        // Repair the former single-language override for existing installations.
        let language = AppLanguage.load()
        if language != .system { language.apply() }
        ChatCacheDiagnostics.shared.localLogWriter = { LocalAppLog.shared.write($0) }
        LocalAppLog.shared.write("INFO App started")
        _ = AgentUserNotificationManager.shared
        // 隧道要早于任何连接存在：节点不是被连接的打开拉起来的（见控制器的两条硬约束）。
        // 只有「开着」且「这台设备登录过」才自动恢复，其它情况保持关闭。
        TailscaleTunnelController.shared.autostartIfNeeded()
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
