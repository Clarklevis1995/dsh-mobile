import SwiftUI

@main
struct DeepSeekHarnessMobileApp: App {
    @StateObject private var hosts = MultiGatewayStore()

    var body: some Scene {
        WindowGroup {
            RootView()
                .id(ObjectIdentifier(hosts.activeStore))
                .environmentObject(hosts.activeStore)
                .environmentObject(hosts)
                .preferredColorScheme(hosts.activeStore.interfaceStyle.colorScheme)
                .alert("主机连接", isPresented: Binding(get: { hosts.error != nil }, set: { if !$0 { hosts.error = nil } })) {
                    Button("好", role: .cancel) { hosts.error = nil; hosts.cancelPairing() }
                } message: { Text(hosts.error ?? "") }
        }
    }
}
