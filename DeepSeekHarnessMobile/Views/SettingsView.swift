import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var store: AppStore
    @State private var knownSession = ""
    @State private var directoryInput = ""

    var body: some View {
        NavigationStack {
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
                Section("加入已有会话") {
                    TextField("Session ID", text: $knownSession).textInputAutocapitalization(.never).autocorrectionDisabled()
                    Button("保存并打开") {
                        store.addKnownSession(knownSession)
                        if let session = store.sessions.first(where: { $0.id == knownSession.trimmingCharacters(in: .whitespacesAndNewlines) }) { store.open(session) }
                        knownSession = ""
                    }
                    .disabled(knownSession.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
                Section("外观") {
                    Picker("界面", selection: $store.interfaceStyle) {
                        ForEach(InterfaceStyle.allCases) { Text($0.title).tag($0) }
                    }
                }
                Section("服务端目录与工作区") {
                    TextField("服务端绝对路径（留空为 Home）", text: $directoryInput).textInputAutocapitalization(.never).autocorrectionDisabled()
                    Button("浏览目录") { store.browseDirectories(path: directoryInput.isEmpty ? nil : directoryInput) }
                    if let path = store.directoryPath {
                        Text(path).font(.caption.monospaced()).textSelection(.enabled)
                        Button("将当前目录创建为工作区") { store.createWorkspace(path: path) }
                        ForEach(store.directoryEntries.prefix(20)) { entry in
                            Button {
                                directoryInput = entry.path
                                store.browseDirectories(path: entry.path)
                            } label: {
                                HStack { Image(systemName: "folder"); Text(entry.name); Spacer(); if entry.hidden { Text("隐藏").font(.caption).foregroundStyle(.secondary) } }
                            }
                        }
                    }
                }
                Section("协议说明") {
                    LabeledContent("端点", value: "/ws/mobile")
                    LabeledContent("协议", value: "DSH Mobile v1")
                    Text("客户端连接后自动同步 workspaces、sessions 与 host；进入会话自动拉取 history。queue 启动下一轮，steer 在 Agent 运行时注入当前轮。")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("设置")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("返回", systemImage: "chevron.left") { store.showWorkspace() }
                }
            }
        }
    }
}
