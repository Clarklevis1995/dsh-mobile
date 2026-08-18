import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var store: AppStore
    @State private var pendingPermission: DefaultPermissionChoice?

    private var selectedPresetName: String {
        guard let id = store.agentPresetDefault else { return "未读取" }
        return store.agentPresets.first(where: { $0.id == id })?.displayName ?? id
    }

    private var selectedPermissionName: String {
        guard let id = store.permissionDefault else { return "未读取" }
        return DefaultPermissionChoice.option(id: id)?.title ?? id
    }

    private var defaultsAreLoading: Bool {
        !store.defaultConfigurationLoadingKinds.isDisjoint(with: ["defaults", "agent-presets"])
    }

    var body: some View {
        Form {
            Section {
                NavigationLink {
                    AgentPresetSelectionView()
                } label: {
                    DefaultConfigurationRow(
                        title: "Agent 预设",
                        detail: "对之后新建的会话生效",
                        value: selectedPresetName,
                        isLoading: defaultsAreLoading
                    )
                }
                .disabled(!store.gateway.state.isConnected)

                Menu {
                    ForEach(DefaultPermissionChoice.options) { option in
                        Button {
                            pendingPermission = option
                        } label: {
                            if option.id == store.permissionDefault {
                                Label(option.title, systemImage: "checkmark")
                            } else {
                                Text(option.title)
                            }
                        }
                    }
                } label: {
                    DefaultConfigurationRow(
                        title: "权限",
                        detail: "选择新会话的默认权限模式",
                        value: selectedPermissionName,
                        isLoading: defaultsAreLoading
                    )
                    .contentShape(Rectangle())
                }
                .tint(.primary)
                .disabled(!store.gateway.state.isConnected || store.defaultConfigurationLoadingKinds.contains("set-default"))
            } header: {
                Text("新会话默认配置")
            } footer: {
                Text("与 WebUI 使用同一份部署级设置。修改只影响之后新建的会话，运行中的会话保持启动时的配置。")
            }

            Section("Mobile Gateway") {
                TextField("ws://host:3080/ws/mobile", text: $store.endpoint)
                    .textInputAutocapitalization(.never)
                    .keyboardType(.URL)
                    .autocorrectionDisabled()
                HStack {
                    ConnectionDot(state: store.gateway.state)
                    Text(store.gateway.state.label)
                    Spacer()
                    if let port = store.gateway.serverPort {
                        Text("Port \(port)").foregroundStyle(.secondary)
                    }
                }
                Button(store.gateway.state.isConnected ? "断开连接" : "连接") {
                    if store.gateway.state.isConnected {
                        store.gateway.disconnect()
                    } else {
                        store.connect()
                    }
                }
                Button("Ping 网关") { store.gateway.ping() }
            }

            if let host = store.hostSnapshot {
                Section("DSH Host") {
                    LabeledContent("版本", value: host.version ?? "—")
                    LabeledContent("Provider", value: host.provider ?? "—")
                    LabeledContent("Model", value: host.model ?? "—")
                    LabeledContent("已连接会话", value: "\(host.attachedSessions ?? 0)")
                    if let cwd = host.cwd {
                        LabeledContent {
                            Text(cwd)
                                .font(.caption.monospaced())
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                                .truncationMode(.middle)
                                .textSelection(.enabled)
                        } label: {
                            Text("cwd")
                        }
                    }
                }
            }

            Section("外观") {
                Picker("界面", selection: $store.interfaceStyle) {
                    ForEach(InterfaceStyle.allCases) {
                        Text($0.title).tag($0)
                    }
                }
            }
        }
        .navigationTitle("设置")
        .task {
            store.refreshDefaultConfiguration()
        }
        .onChange(of: store.gateway.state) { _, state in
            if state.isConnected {
                store.refreshDefaultConfiguration()
            }
        }
        .alert(item: $pendingPermission) { option in
            Alert(
                title: Text("修改全局默认权限？"),
                message: Text("将新会话的默认权限改为“\(option.title)”。这会更新部署级设置，并同步影响 WebUI。"),
                primaryButton: .cancel(Text("取消")),
                secondaryButton: .default(Text("确认修改")) {
                    store.setDefaultPermission(option.id)
                }
            )
        }
    }
}

private struct DefaultConfigurationRow: View {
    let title: String
    let detail: String
    let value: String
    let isLoading: Bool

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .foregroundStyle(.primary)
                Text(detail)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 12)
            if isLoading {
                ProgressView()
                    .controlSize(.small)
            } else {
                Text(value)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        }
        .padding(.vertical, 3)
    }
}

private struct DefaultPermissionChoice: Identifiable, Hashable {
    let id: String
    let title: String
    let description: String

    static let options = [
        DefaultPermissionChoice(id: "ask", title: "每次询问", description: "需要写入或执行敏感操作时请求确认"),
        DefaultPermissionChoice(id: "read-only", title: "只读", description: "允许读取工作区，不允许修改文件"),
        DefaultPermissionChoice(id: "workspace-write", title: "工作区写入", description: "允许在当前工作区内读取和写入"),
        DefaultPermissionChoice(id: "danger-full-access", title: "完全访问", description: "允许不受沙箱限制地访问宿主环境")
    ]

    static func option(id: String) -> DefaultPermissionChoice? {
        options.first { $0.id == id }
    }
}

private struct AgentPresetSelectionView: View {
    @EnvironmentObject private var store: AppStore
    @State private var pendingPreset: GatewayAgentPreset?

    private var isChangingDefault: Bool {
        store.defaultConfigurationLoadingKinds.contains("set-default")
    }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 14) {
                VStack(alignment: .leading, spacing: 7) {
                    Text("Agent 预设")
                        .font(.title2.bold())
                    Text("预设决定 Agent 使用的工具、提示词与能力。选择后只对新建会话生效。")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.bottom, 4)

                if store.defaultConfigurationLoadingKinds.contains("agent-presets") && store.agentPresets.isEmpty {
                    HStack(spacing: 10) {
                        ProgressView()
                        Text("正在读取 Agent 预设…")
                            .foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 48)
                } else if store.agentPresets.isEmpty {
                    ContentUnavailableView(
                        "没有可用的 Agent 预设",
                        systemImage: "point.3.filled.connected.trianglepath.dotted",
                        description: Text("请确认 Mobile Gateway 已升级到 v0.1.11 并保持连接。")
                    )
                    .padding(.vertical, 24)
                } else {
                    ForEach(store.agentPresets) { preset in
                        AgentPresetCard(
                            preset: preset,
                            isSelected: preset.id == store.agentPresetDefault,
                            isBusy: isChangingDefault
                        ) {
                            pendingPreset = preset
                        }
                    }
                }

                if store.agentPresetsAuthorable || store.agentPresetsHasDocument {
                    Label(presetCapabilityText, systemImage: "info.circle")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .padding(.top, 4)
                }
            }
            .padding(20)
        }
        .background(Color(uiColor: .systemGroupedBackground))
        .navigationTitle("Agent 预设")
        .navigationBarTitleDisplayMode(.inline)
        .refreshable {
            store.refreshDefaultConfiguration()
        }
        .task {
            if store.agentPresets.isEmpty {
                store.refreshDefaultConfiguration()
            }
        }
        .alert(item: $pendingPreset) { preset in
            Alert(
                title: Text("设为全局默认预设？"),
                message: Text("将“\(preset.displayName)”设为新会话的默认 Agent 预设。这会更新部署级设置，并同步影响 WebUI。"),
                primaryButton: .cancel(Text("取消")),
                secondaryButton: .default(Text("设为默认")) {
                    store.setDefaultAgentPreset(preset.id)
                }
            )
        }
    }

    private var presetCapabilityText: String {
        switch (store.agentPresetsAuthorable, store.agentPresetsHasDocument) {
        case (true, true): return "服务端支持编写自定义预设，并提供预设配置文档。"
        case (true, false): return "服务端支持编写自定义 Agent 预设。"
        case (false, true): return "服务端提供 Agent 预设配置文档。"
        case (false, false): return ""
        }
    }
}

private struct AgentPresetCard: View {
    let preset: GatewayAgentPreset
    let isSelected: Bool
    let isBusy: Bool
    let select: () -> Void

    var body: some View {
        Button(action: select) {
            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(preset.displayName)
                        .font(.headline)
                        .foregroundStyle(.primary)
                    Text(preset.id)
                        .font(.caption.monospaced())
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 7)
                        .padding(.vertical, 3)
                        .background(.secondary.opacity(0.1), in: Capsule())
                    Spacer()
                    if isSelected {
                        Text("当前使用")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 5)
                            .background(.black, in: Capsule())
                    }
                }

                Text(preset.displayDescription)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)

                if preset.broken == true {
                    Label("该预设存在配置错误，暂时不能设为默认值", systemImage: "exclamationmark.triangle.fill")
                        .font(.caption)
                        .foregroundStyle(.orange)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
            .background(Color(uiColor: .secondarySystemGroupedBackground))
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .stroke(isSelected ? Color.primary : Color.secondary.opacity(0.18), lineWidth: isSelected ? 1.5 : 1)
            }
        }
        .buttonStyle(.plain)
        .allowsHitTesting(!isSelected && !isBusy && preset.broken != true)
        .opacity(preset.broken == true ? 0.68 : 1)
    }
}
