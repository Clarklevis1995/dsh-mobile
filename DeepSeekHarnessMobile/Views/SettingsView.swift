import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var store: AppStore

    var body: some View {
        Form {
                Section("Mobile Gateway") {
                    TextField("ws://host:3080/ws/mobile", text: $store.endpoint)
                        .textInputAutocapitalization(.never).keyboardType(.URL).autocorrectionDisabled()
                    HStack {
                        ConnectionDot(state: store.gateway.state)
                        Text(store.gateway.state.label)
                        Spacer()
                        if let port = store.gateway.serverPort { Text("Port \(port)").foregroundStyle(.secondary) }
                    }
                    Button(store.gateway.state.isConnected ? "断开连接" : "连接") {
                        if store.gateway.state.isConnected { store.gateway.disconnect() } else { store.connect() }
                    }
                    Button("Ping 网关") { store.gateway.ping() }
                }
                if let host = store.hostSnapshot {
                    Section("DSH Host") {
                        LabeledContent("版本", value: host.version ?? "—")
                        LabeledContent("Provider", value: host.provider ?? "—")
                        LabeledContent("Model", value: host.model ?? "—")
                        LabeledContent("已连接会话", value: "\(host.attachedSessions ?? 0)")
                        if let cwd = host.cwd { Text(cwd).font(.caption.monospaced()).foregroundStyle(.secondary).textSelection(.enabled) }
                    }
                }
                Section("外观") {
                    Picker("界面", selection: $store.interfaceStyle) {
                        ForEach(InterfaceStyle.allCases) { Text($0.title).tag($0) }
                    }
                }
        }
        .navigationTitle("设置")
    }
}
