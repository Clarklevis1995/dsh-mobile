import SwiftUI
import DeepSeekHarnessShared

private final class AgentNotificationAppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        configurationForConnecting connectingSceneSession: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        // 不回传已有配置，其中可能包含 SwiftUI 自身的委托，重复包装会导致递归。
        let configuration = UISceneConfiguration(name: nil, sessionRole: connectingSceneSession.role)
        if #available(iOS 26.0, *),
           UIDevice.current.userInterfaceIdiom == .pad,
           connectingSceneSession.role == .windowApplication {
            configuration.delegateClass = AppWindowSceneDelegate.self
        }
        return configuration
    }

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

/// 让 iPad 窗口控件独立停靠在窗口顶边，导航栏继续遵循系统安全区域。
private final class AppWindowSceneDelegate: NSObject, UIWindowSceneDelegate {
    @available(iOS 26.0, *)
    func preferredWindowingControlStyle(for windowScene: UIWindowScene) -> UIWindowScene.WindowingControlStyle {
        .minimal
    }
}

@main
struct DeepSeekHarnessMobileApp: App {
    @UIApplicationDelegateAdaptor(AgentNotificationAppDelegate.self) private var notificationAppDelegate
    @StateObject private var hosts = MultiGatewayStore()

    var body: some Scene {
        WindowGroup {
            Group {
                #if DEBUG
                if ProcessInfo.processInfo.environment["DSH_CONNECTOR_SHEET_UI_TEST"] == "1" {
                    ConnectorSheetUITestHarness()
                } else if hosts.isReady { RootView() }
                else { ProgressView() }
                #else
                if hosts.isReady { RootView() }
                else { ProgressView() }
                #endif
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
