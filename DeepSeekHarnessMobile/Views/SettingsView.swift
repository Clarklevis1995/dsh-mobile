import SwiftUI
import WebKit
import MarkdownUI
import Charts
import DeepSeekHarnessShared

struct SettingsView: View {
    @Environment(\.openURL) private var openURL
    @EnvironmentObject private var store: AppStore
    @State private var pendingPermission: DefaultPermissionChoice?
    @State private var observedGatewayState: ConnectionState?
    @State private var observedConnectionMethod = ""
    @State private var observedConnectionPaths: [String: RtcSelectedPath] = [:]
    @State private var showsPrivacyPolicy = false
    @State private var showsReleaseNotes = false
    @AppStorage(AppFontScale.key) private var fontScale = 1.0
    @AppStorage(AppFontScale.followsSystemKey) private var fontFollowsSystem = false
    private let releaseInfo = BundledAppReleaseInfo.load()

    private var appVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "未知"
    }

    private var connectedGatewayVersion: String? {
        guard gatewayState.isConnected, !store.pluginsLoading, store.pluginsError == nil else { return nil }
        return store.pluginCatalog?.bundles.first(where: { $0.name == "dsh-plugin-mobile-gateway" })?.version
    }

    private func openRelease(_ repository: String, version: String?) {
        Task {
            let root = URL(string: "https://github.com/Clarklevis1995/\(repository)")!
            let fallback = repository == "dsh-mobile" ? root.appendingPathComponent("releases") : root
            guard let version, !version.isEmpty else { openURL(fallback); return }
            let tag = version.hasPrefix("v") ? version : "v\(version)"
            let candidate = root.appendingPathComponent("releases/tag").appendingPathComponent(tag)
            var request = URLRequest(url: candidate, timeoutInterval: 8)
            request.httpMethod = "HEAD"
            let response = try? await URLSession.shared.data(for: request)
            let status = (response?.1 as? HTTPURLResponse)?.statusCode ?? 0
            openURL((200..<300).contains(status) ? candidate : fallback)
        }
    }

    private func releaseRow(_ title: String, value: String, repository: String, version: String?) -> some View {
        Button { openRelease(repository, version: version) } label: {
            HStack {
                Text(LocalizedStringKey(title))
                Spacer()
                Text(verbatim: value).foregroundStyle(.secondary)
                Image(systemName: "chevron.right").appFont(.footnote).foregroundStyle(.tertiary)
            }
            .frame(maxWidth: .infinity, minHeight: 32)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private var gatewayState: ConnectionState {
        observedGatewayState ?? store.gateway.state
    }

    private var selectedPresetName: String {
        guard let id = store.agentPresetDefault else { return String(localized: "未读取") }
        return store.agentPresets.first(where: { $0.id == id })?.displayName ?? id
    }

    private var selectedPermissionName: String {
        guard let id = store.permissionDefault else { return String(localized: "未读取") }
        return store.permissionDefaultOptions.first(where: { $0.value == id })?.name ?? id
    }

    private var defaultsAreLoading: Bool {
        !store.defaultConfigurationLoadingKinds.isDisjoint(with: ["defaults", "agent-presets"])
    }

    private var defaultModelIsLoading: Bool {
        store.defaultConfigurationLoadingKinds.contains("default-model")
            || store.defaultConfigurationLoadingKinds.contains("save-default-model")
    }

    private var defaultModelValueText: String {
        guard let selection = store.defaultModelSelection else { return String(localized: "未读取") }
        let item = store.anyModelCatalog?.groups
            .first(where: { $0.id == selection.provider })?
            .models.first(where: { $0.id == selection.model })
        let name = item?.name ?? Self.modelDisplayName(selection.model)
        guard let effort = selection.reasoningEffort else { return name }
        let effortName = item?.reasoning?.efforts.first(where: { $0.id == effort })?.name ?? Self.reasoningEffortDisplayName(effort)
        return "\(name) · \(effortName)"
    }

    static func modelDisplayName(_ id: String) -> String {
        switch id {
        case "deepseek-chat": return "DeepSeek Chat"
        case "deepseek-reasoner": return "DeepSeek Reasoner"
        default: return id
        }
    }

    static func reasoningEffortDisplayName(_ id: String) -> String {
        switch id.lowercased() {
        case "low": return String(localized: "低")
        case "medium": return String(localized: "中")
        case "high": return String(localized: "高")
        default: return id.capitalized
        }
    }

    var body: some View {
        Form {
            Section {
                NavigationLink {
                    AgentPresetSelectionView()
                } label: {
                    DefaultConfigurationRow(
                        title: String(localized: "Agent 预设"),
                        value: selectedPresetName,
                        isLoading: defaultsAreLoading
                    )
                }
                .disabled(!gatewayState.isConnected)

                NavigationLink {
                    DefaultModelSelectionView()
                } label: {
                    DefaultConfigurationRow(
                        title: String(localized: "默认模型"),
                        value: defaultModelValueText,
                        isLoading: defaultModelIsLoading
                    )
                }
                .disabled(!gatewayState.isConnected)

                Menu {
                    ForEach(store.permissionDefaultOptions.map(DefaultPermissionChoice.init)) { option in
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
                        title: String(localized: "权限"),
                        value: selectedPermissionName,
                        isLoading: defaultsAreLoading
                    )
                    .contentShape(Rectangle())
                }
                .tint(.primary)
                .disabled(!gatewayState.isConnected || store.defaultConfigurationLoadingKinds.contains("set-default"))
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
                    ConnectionDot(state: gatewayState)
                    Text(gatewayState.label)
                    Spacer()
                    if let port = store.gateway.serverPort {
                        Text("Port \(port)").foregroundStyle(.secondary)
                    }
                }
                LabeledContent("连接方式", value: gatewayState.isConnected
                    ? (observedConnectionMethod.isEmpty ? "WebSocket" : String(localized: String.LocalizationValue(observedConnectionMethod))) : String(localized: "未连接"))
                    .accessibilityIdentifier("gateway-connection-method")
                if gatewayState.isConnected && observedConnectionMethod == "WebRTC" {
                    GatewayConnectionPathView(paths: observedConnectionPaths)
                }
                NavigationLink {
                    GatewayNetworkDiagnosticsView(gateway: store.gateway)
                        .id(ObjectIdentifier(store.gateway))
                } label: {
                    LabeledContent("网络检测", value: String(localized: "实时延迟与丢包"))
                }
                .accessibilityIdentifier("network-diagnostics-entry")
                Button(gatewayState.isConnected ? String(localized: "断开连接") : String(localized: "连接")) {
                    if gatewayState.isConnected {
                        store.disconnect()
                    } else {
                        store.connect()
                    }
                }
                Button("Ping 网关") { store.gateway.ping() }
            }

            if let host = store.hostSnapshot {
                Section("DSH Host") {
                    LabeledContent("版本", value: [host.dshVersion, host.version].compactMap { $0 }
                        .first { $0.range(of: "^v?[0-9]+\\.[0-9]+", options: .regularExpression) != nil } ?? "未提供")
                    LabeledContent("已连接会话", value: "\(host.attachedSessions ?? 0)")
                    if let cwd = host.cwd {
                        LabeledContent {
                            Text(cwd)
                                .appFont(.caption.monospaced())
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

            Section {
                Picker("界面", selection: $store.interfaceStyle) {
                    ForEach(InterfaceStyle.allCases) {
                        Text($0.title).tag($0)
                    }
                }
                .pickerStyle(.navigationLink)
                Picker("语言", selection: $store.appLanguage) {
                    ForEach(AppLanguage.allCases) { language in
                        Text(verbatim: language.title).tag(language)
                    }
                }
                .pickerStyle(.navigationLink)
                NavigationLink {
                    FontSizeSettingsView()
                } label: {
                    HStack {
                        Text("字体大小")
                        Spacer()
                        Text(verbatim: fontFollowsSystem ? String(localized: "跟随系统")
                            : "\(Int((AppFontScale.normalized(fontScale) * 100).rounded()))%")
                            .foregroundStyle(.secondary)
                    }
                }
                .accessibilityIdentifier("font-size-settings-row")
            } header: {
                Text("外观")
            } footer: {
                Text("语言设置将在重新启动应用后生效。")
            }

            Section {
                NavigationLink {
                    StorageSpaceView()
                } label: {
                    HStack {
                        Text("存储空间")
                        Spacer()
                        if let usage = store.storageUsage {
                            Text(verbatim: ByteCountFormatter.string(fromByteCount: usage.totalBytes, countStyle: .file))
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                .accessibilityIdentifier("storage-space-settings-row")
            }

            LogExportSection()

            Section("关于") {
                releaseRow("当前版本（去给作者Star🌟）", value: appVersion, repository: "dsh-mobile", version: appVersion)
                releaseRow("要求网关版本", value: releaseInfo.map { "≥ \($0.minimumGatewayVersion)" } ?? "未提供",
                           repository: "dsh-plugin-mobile-gateway", version: releaseInfo?.minimumGatewayVersion)
                releaseRow("已连接网关版本", value: !gatewayState.isConnected ? "未连接" :
                    store.pluginsLoading ? "读取中…" : connectedGatewayVersion ?? "未提供",
                    repository: "dsh-plugin-mobile-gateway", version: connectedGatewayVersion)
                Button("本版本新功能") { showsReleaseNotes = true }
                Button("隐私政策") { showsPrivacyPolicy = true }
            }
        }
        .navigationTitle("设置")
        .task {
            store.refreshDefaultConfiguration()
            await store.refreshCacheStorage()
            if gatewayState.isConnected { store.refreshPlugins() }
        }
        .onReceive(store.gateway.$connectionMethod) { observedConnectionMethod = $0 }
        .onReceive(store.gateway.$connectionPaths) { observedConnectionPaths = $0 }
        .onReceive(store.gateway.$state) { state in
            let wasConnected = observedGatewayState?.isConnected ?? state.isConnected
            observedGatewayState = state
            if !wasConnected && state.isConnected {
                store.refreshPlugins()
                store.refreshDefaultConfiguration()
            }
        }
        .alert(item: $pendingPermission) { option in
            Alert(
                title: Text("修改全局默认权限？"),
                message: Text(String(localized: "defaults.permission.confirm.body", defaultValue: "将新会话的默认权限改为“\(option.title)”。这会更新部署级设置，并同步影响 WebUI。")),
                primaryButton: .cancel(Text("取消")),
                secondaryButton: .default(Text("确认修改")) {
                    store.setDefaultPermission(option.id)
                }
            )
        }
        .sheet(isPresented: $showsReleaseNotes) {
            NavigationStack {
                ScrollView {
                    Markdown(releaseInfo?.version == appVersion ? releaseInfo!.notes : "本版本发布说明暂未提供，请查看 GitHub 发布页。")
                        .padding(20)
                }
                .navigationTitle("本版本新功能")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("完成") { showsReleaseNotes = false }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("GitHub 发布页") { openRelease("dsh-mobile", version: nil) }
                    }
                }
            }
        }
        .sheet(isPresented: $showsPrivacyPolicy) {
            PrivacyPolicySheet()
        }
    }
}

struct FontSizeSettingsView: View {
    @AppStorage(AppFontScale.key) private var fontScale = 1.0
    @AppStorage(AppFontScale.followsSystemKey) private var fontFollowsSystem = false
    @Environment(\.colorScheme) private var colorScheme
    @State private var fontFeedback = UIImpactFeedbackGenerator(style: .medium)

    private var sliderFontScale: Binding<Double> {
        Binding(
            get: { fontScale },
            set: { value in
                guard !fontFollowsSystem else { return }
                let oldTick = Int((fontScale * 20).rounded())
                let newTick = Int((value * 20).rounded())
                fontScale = value
                if oldTick != newTick {
                    fontFeedback.impactOccurred()
                    fontFeedback.prepare()
                }
            }
        )
    }

    private var bubbleColor: Color {
        colorScheme == .dark ? Color(red: 0.11, green: 0.18, blue: 0.29)
            : Color(red: 0.93, green: 0.95, blue: 1)
    }

    var body: some View {
        GeometryReader { geometry in
            ScrollView {
                fontSizeContent(previewHeight: max(220, geometry.size.height - 290))
                    .frame(maxWidth: 720)
                    .padding(.horizontal, 20)
                    .padding(.top, 16)
                    .padding(.bottom, 20)
                    .frame(maxWidth: .infinity)
            }
        }
        .background(Color(uiColor: .systemGroupedBackground))
        .navigationTitle("字体大小")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { fontScale = AppFontScale.normalized(fontScale) }
    }

    private func fontSizeContent(previewHeight: CGFloat) -> some View {
        VStack(spacing: 24) {
            ScrollView {
                VStack(alignment: .leading, spacing: 28) {
                    HStack {
                        Spacer(minLength: 0)
                        Text("预览文字大小")
                            .appFont(.system(size: 17))
                            .accessibilityIdentifier("font-size-preview-title")
                            .padding(.horizontal, 18)
                            .padding(.vertical, 14)
                            .background(bubbleColor, in: UnevenRoundedRectangle(
                                topLeadingRadius: 24, bottomLeadingRadius: 24,
                                bottomTrailingRadius: 6, topTrailingRadius: 24
                            ))
                    }
                    Text("你可以拖动滑块来调整字体大小。")
                        .appFont(.system(size: 17))
                        .lineSpacing(6)
                    MarkdownContent(String(localized: "调整后的字体大小会应用到所有页面，包括对话、按钮和 `代码`。"))
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 20)
                .padding(.vertical, 32)
            }
            .frame(height: previewHeight)
            .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 20))

            VStack(spacing: 16) {
                Toggle("跟随系统", isOn: $fontFollowsSystem)
                    .tint(DSHColor.ocean)
                    .appFont(.system(size: 16))
                    .accessibilityIdentifier("font-size-follow-system")
                Divider()
                VStack(spacing: 8) {
                    HStack {
                        Text(verbatim: "A").appFont(.system(size: 14, weight: .medium))
                        Spacer()
                        Text(verbatim: fontFollowsSystem ? String(localized: "跟随系统")
                            : fontScale == 1 ? String(localized: "标准")
                            : "\(Int((AppFontScale.normalized(fontScale) * 100).rounded()))%")
                            .appFont(.system(size: 14))
                        Spacer()
                        Text(verbatim: "A").appFont(.system(size: 22, weight: .medium))
                    }
                    .foregroundStyle(.secondary)
                    Slider(value: sliderFontScale, in: 0.8...1.5, step: 0.05) { editing in
                        if editing { fontFeedback.prepare() }
                    }
                        .disabled(fontFollowsSystem)
                        .tint(Color(uiColor: .systemGray3))
                        .accessibilityIdentifier("font-size-slider")
                        .accessibilityLabel("字体大小")
                        .accessibilityValue("\(Int((AppFontScale.normalized(fontScale) * 100).rounded()))%")
                        .background {
                            GeometryReader { geometry in
                                Canvas { context, size in
                                    var ticks = Path()
                                    let width = max(0, geometry.size.width - 28)
                                    for index in 0...14 {
                                        let x = 14 + width * CGFloat(index) / 14
                                        ticks.move(to: CGPoint(x: x, y: size.height / 2 - 4))
                                        ticks.addLine(to: CGPoint(x: x, y: size.height / 2 + 4))
                                    }
                                    context.stroke(ticks, with: .color(Color(uiColor: .systemGray3)), lineWidth: 1.5)
                                }
                            }
                        }
                    Button("恢复默认") { fontScale = 1 }
                        .appFont(.system(size: 13))
                        .foregroundStyle(.secondary)
                        .accessibilityIdentifier("font-size-reset")
                        .padding(.vertical, 6)
                }
                .padding(.top, 12)
                .disabled(fontFollowsSystem)
                .opacity(fontFollowsSystem ? 0.45 : 1)
            }
            .padding(.horizontal, 12)
        }
    }
}

private struct PrivacyPolicySheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var isLoading = true
    @State private var loadFailed = false
    @State private var reloadID = UUID()

    private let url = URL(string: "https://clarklevis1995.github.io/dsh-mobile/privacy/")!

    var body: some View {
        NavigationStack {
            ZStack {
                PrivacyPolicyWebView(url: url, isLoading: $isLoading, loadFailed: $loadFailed)
                    .id(reloadID)
                    .opacity(loadFailed ? 0 : 1)
                    .allowsHitTesting(!loadFailed)

                if isLoading {
                    ProgressView()
                }

                if loadFailed {
                    VStack(spacing: 12) {
                        Text("无法打开隐私政策")
                        Button("重试") {
                            loadFailed = false
                            isLoading = true
                            reloadID = UUID()
                        }
                    }
                }
            }
            .navigationTitle("隐私政策")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("完成") { dismiss() }
                }
            }
        }
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
    }
}

private struct PrivacyPolicyWebView: UIViewRepresentable {
    @AppStorage(AppFontScale.key) private var fontScale = 1.0
    @AppStorage(AppFontScale.followsSystemKey) private var fontFollowsSystem = false
    let url: URL
    @Binding var isLoading: Bool
    @Binding var loadFailed: Bool

    func makeCoordinator() -> Coordinator {
        Coordinator(isLoading: $isLoading, loadFailed: $loadFailed)
    }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.defaultWebpagePreferences.allowsContentJavaScript = false
        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = context.coordinator
        webView.pageZoom = CGFloat(AppFontScale.effective(fontScale, followsSystem: fontFollowsSystem))
        webView.load(URLRequest(url: url))
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        webView.pageZoom = CGFloat(AppFontScale.effective(fontScale, followsSystem: fontFollowsSystem))
    }

    final class Coordinator: NSObject, WKNavigationDelegate {
        @Binding var isLoading: Bool
        @Binding var loadFailed: Bool

        init(isLoading: Binding<Bool>, loadFailed: Binding<Bool>) {
            _isLoading = isLoading
            _loadFailed = loadFailed
        }

        func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
            isLoading = true
            loadFailed = false
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            isLoading = false
        }

        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
            handleLoadError(error)
        }

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            handleLoadError(error)
        }

        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction,
            decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
        ) {
            if let url = navigationAction.request.url, url.scheme == "mailto" {
                UIApplication.shared.open(url)
                decisionHandler(.cancel)
            } else {
                decisionHandler(.allow)
            }
        }

        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationResponse: WKNavigationResponse,
            decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void
        ) {
            if navigationResponse.isForMainFrame,
               let response = navigationResponse.response as? HTTPURLResponse,
               response.statusCode >= 400 {
                isLoading = false
                loadFailed = true
                decisionHandler(.cancel)
            } else {
                decisionHandler(.allow)
            }
        }

        private func handleLoadError(_ error: Error) {
            guard (error as NSError).code != NSURLErrorCancelled else { return }
            isLoading = false
            loadFailed = true
        }
    }
}

private struct DefaultConfigurationRow: View {
    let title: String
    let value: String
    let isLoading: Bool

    var body: some View {
        HStack(spacing: 12) {
            Text(title)
                .foregroundStyle(.primary)
            Spacer(minLength: 12)
            if isLoading {
                ProgressView()
                    .controlSize(.small)
            } else {
                Text(value)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        }
    }
}

private struct DefaultPermissionChoice: Identifiable, Hashable {
    let id: String
    let title: String
    let description: String

    init(_ option: GatewayPermissionOption) {
        id = option.value
        title = option.name
        description = option.description ?? option.name
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
                        .appFont(.title2.bold())
                    Text("预设决定 Agent 使用的工具、提示词与能力。选择后只对新建会话生效。")
                        .appFont(.subheadline)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.bottom, 4)

                if let error = store.agentPresetsLoadError {
                    VStack(alignment: .leading, spacing: 10) {
                        Label(error, systemImage: "exclamationmark.circle")
                            .foregroundStyle(.secondary)
                        Button("重试") { store.retryAgentPresets() }
                            .disabled(store.defaultConfigurationLoadingKinds.contains("agent-presets"))
                    }
                    .padding(.vertical, 12)
                }

                if store.defaultConfigurationLoadingKinds.contains("agent-presets") && store.agentPresets.isEmpty {
                    HStack(spacing: 10) {
                        ProgressView()
                        Text("正在读取 Agent 预设…")
                            .foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 48)
                } else if store.agentPresets.isEmpty && store.agentPresetsLoadError == nil {
                    DshEmptyState(
                        "没有可用的 Agent 预设",
                        systemImage: "point.3.filled.connected.trianglepath.dotted",
                        description: Text("网关暂无可用预设，可下拉刷新。")
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
                        .appFont(.footnote)
                        .foregroundStyle(.secondary)
                        .padding(.top, 4)
                }
            }
            .padding(20)
            .readableContentWidth()
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
                message: Text(String(localized: "defaults.preset.confirm.body", defaultValue: "将“\(preset.displayName)”设为新会话的默认 Agent 预设。这会更新部署级设置，并同步影响 WebUI。")),
                primaryButton: .cancel(Text("取消")),
                secondaryButton: .default(Text("设为默认")) {
                    store.setDefaultAgentPreset(preset.id)
                }
            )
        }
    }

    private var presetCapabilityText: String {
        switch (store.agentPresetsAuthorable, store.agentPresetsHasDocument) {
        case (true, true): return String(localized: "服务端支持编写自定义预设，并提供预设配置文档。")
        case (true, false): return String(localized: "服务端支持编写自定义 Agent 预设。")
        case (false, true): return String(localized: "服务端提供 Agent 预设配置文档。")
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
                        .appFont(.headline)
                        .foregroundStyle(.primary)
                    Text(preset.id)
                        .appFont(.caption.monospaced())
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 7)
                        .padding(.vertical, 3)
                        .background(.secondary.opacity(0.1), in: Capsule())
                    Spacer()
                    if isSelected {
                        Text("当前使用")
                            .appFont(.caption.weight(.semibold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 5)
                            .background(.black, in: Capsule())
                    }
                }

                Text(preset.displayDescription)
                    .appFont(.subheadline)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)

                if preset.broken == true {
                    Label("该预设存在配置错误，暂时不能设为默认值", systemImage: "exclamationmark.triangle.fill")
                        .appFont(.caption)
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

private struct DefaultModelSelectionView: View {
    @EnvironmentObject private var store: AppStore
    @State private var pendingChange: PendingDefaultModelChange?

    private var isBusy: Bool {
        store.defaultConfigurationLoadingKinds.contains("save-default-model")
    }
    private var modelGroups: [GatewayModelGroup] {
        store.anyModelCatalog?.groups ?? []
    }
    private var isLoadingCatalog: Bool {
        store.sessionControlLoadingKinds.contains("models") && modelGroups.isEmpty
    }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 20) {
                VStack(alignment: .leading, spacing: 7) {
                    Text("默认模型")
                        .appFont(.title2.bold())
                    Text("为之后新建的会话设置默认模型与思考等级。这会更新部署级设置，同步影响 WebUI，运行中的会话保持启动时的配置。")
                        .appFont(.subheadline)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.bottom, 4)

                if isLoadingCatalog {
                    HStack(spacing: 10) {
                        ProgressView()
                        Text("正在读取模型列表…")
                            .foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 48)
                } else if modelGroups.isEmpty {
                    DshEmptyState(
                        "暂无可用模型",
                        systemImage: "cpu",
                        description: Text("未能读取到模型列表，请检查网关连接后下拉重试。")
                    )
                    .padding(.vertical, 24)
                } else {
                    ForEach(modelGroups) { group in
                        VStack(alignment: .leading, spacing: 10) {
                            Text(group.name)
                                .appFont(.subheadline.weight(.semibold))
                                .foregroundStyle(.secondary)
                            ForEach(group.models) { model in
                                DefaultModelCard(
                                    group: group,
                                    model: model,
                                    isSelected: isDefault(group: group, model: model),
                                    currentEffort: store.defaultModelSelection?.reasoningEffort,
                                    isBusy: isBusy,
                                    onSelectModel: { select(group: group, model: model) },
                                    onSelectEffort: { effort in selectEffort(effort, group: group, model: model) }
                                )
                            }
                        }
                    }
                }
            }
            .padding(20)
            .readableContentWidth()
        }
        .background(Color(uiColor: .systemGroupedBackground))
        .navigationTitle("默认模型")
        .navigationBarTitleDisplayMode(.inline)
        .refreshable {
            store.ensureModelCatalogForDefaults()
        }
        .task {
            store.ensureModelCatalogForDefaults()
        }
        .alert(item: $pendingChange) { change in
            Alert(
                title: Text("修改默认模型？"),
                message: Text(String(localized: "defaults.model.confirm.body", defaultValue: "将新会话的默认模型改为“\(change.summary)”。这会更新部署级设置，并同步影响 WebUI。")),
                primaryButton: .cancel(Text("取消")),
                secondaryButton: .default(Text("确认修改")) {
                    store.saveDefaultModel(provider: change.provider, model: change.model, reasoningEffort: change.reasoningEffort)
                }
            )
        }
    }

    private func isDefault(group: GatewayModelGroup, model: GatewayModelItem) -> Bool {
        store.defaultModelSelection?.provider == group.id && store.defaultModelSelection?.model == model.id
    }

    private func select(group: GatewayModelGroup, model: GatewayModelItem) {
        let efforts = model.reasoning?.efforts ?? []
        let retainedEffort = store.defaultModelSelection?.reasoningEffort.flatMap { current in
            efforts.contains(where: { $0.id == current }) ? current : nil
        }
        let effortId = retainedEffort ?? model.reasoning?.defaultEffort
        pendingChange = PendingDefaultModelChange(
            provider: group.id,
            providerName: group.name,
            model: model.id,
            modelName: model.name,
            reasoningEffort: effortId,
            effortName: effortId.flatMap { id in efforts.first(where: { $0.id == id })?.name }
        )
    }

    private func selectEffort(_ effort: GatewayReasoningEffort, group: GatewayModelGroup, model: GatewayModelItem) {
        pendingChange = PendingDefaultModelChange(
            provider: group.id,
            providerName: group.name,
            model: model.id,
            modelName: model.name,
            reasoningEffort: effort.id,
            effortName: effort.name
        )
    }
}

private struct PendingDefaultModelChange: Identifiable {
    let id = UUID()
    let provider: String
    let providerName: String
    let model: String
    let modelName: String
    let reasoningEffort: String?
    let effortName: String?

    var summary: String {
        var text = "\(providerName) · \(modelName)"
        if let effortName { text += " · \(effortName)" }
        return text
    }
}

private struct DefaultModelCard: View {
    let group: GatewayModelGroup
    let model: GatewayModelItem
    let isSelected: Bool
    let currentEffort: String?
    let isBusy: Bool
    let onSelectModel: () -> Void
    let onSelectEffort: (GatewayReasoningEffort) -> Void

    private var efforts: [GatewayReasoningEffort] { model.reasoning?.efforts ?? [] }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(model.name)
                    .appFont(.headline)
                    .foregroundStyle(.primary)
                Spacer()
                if isSelected {
                    Text("当前使用")
                        .appFont(.caption.weight(.semibold))
                        .foregroundStyle(.white)
                        .fixedSize()
                        .padding(.horizontal, 10)
                        .padding(.vertical, 5)
                        .background(.black, in: Capsule())
                }
            }
            .contentShape(Rectangle())
            .onTapGesture { if !isBusy { onSelectModel() } }

            if !efforts.isEmpty {
                ReasoningEffortFlowLayout(spacing: 8) {
                    Text("思考等级")
                        .appFont(.caption)
                        .foregroundStyle(.secondary)
                        .fixedSize()
                    ForEach(efforts) { effort in
                        let isCurrentEffort = isSelected && effort.id == currentEffort
                        Button {
                            onSelectEffort(effort)
                        } label: {
                            Text(effort.name)
                                .appFont(.caption.weight(.medium))
                                .fixedSize()
                                .padding(.horizontal, 10)
                                .padding(.vertical, 5)
                                .background(isCurrentEffort ? Color.primary : Color.secondary.opacity(0.12), in: Capsule())
                                .foregroundStyle(isCurrentEffort ? Color(uiColor: .systemBackground) : .primary)
                        }
                        .buttonStyle(.plain)
                        .disabled(isBusy)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
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
        .opacity(isBusy ? 0.7 : 1)
    }
}

/// 按实际可用宽度排列完整胶囊，空间不足时整颗换行。
private struct ReasoningEffortFlowLayout: Layout {
    let spacing: CGFloat

    private struct Row {
        var indices: [Int] = []
        var width: CGFloat = 0
        var height: CGFloat = 0
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let rows = rows(for: proposal.width, subviews: subviews)
        return CGSize(
            width: rows.map(\.width).max() ?? 0,
            height: rows.reduce(0) { $0 + $1.height } + CGFloat(max(0, rows.count - 1)) * spacing
        )
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for row in rows(for: bounds.width, subviews: subviews) {
            var x = bounds.minX
            for index in row.indices {
                let size = subviews[index].sizeThatFits(.unspecified)
                subviews[index].place(
                    at: CGPoint(x: x, y: y + (row.height - size.height) / 2),
                    anchor: .topLeading,
                    proposal: ProposedViewSize(size)
                )
                x += size.width + spacing
            }
            y += row.height + spacing
        }
    }

    private func rows(for width: CGFloat?, subviews: Subviews) -> [Row] {
        var rows: [Row] = []
        var row = Row()
        for index in subviews.indices {
            let size = subviews[index].sizeThatFits(.unspecified)
            let gap = row.indices.isEmpty ? 0 : spacing
            if !row.indices.isEmpty && row.width + gap + size.width > (width ?? .infinity) {
                rows.append(row)
                row = Row()
            }
            row.width += (row.indices.isEmpty ? 0 : spacing) + size.width
            row.height = max(row.height, size.height)
            row.indices.append(index)
        }
        if !row.indices.isEmpty { rows.append(row) }
        return rows
    }
}


private struct BundledAppReleaseInfo: Decodable {
    let version: String
    let minimumGatewayVersion: String
    var notes: String = ""

    private enum CodingKeys: String, CodingKey { case version, minimumGatewayVersion }

    static func load() -> BundledAppReleaseInfo? {
        guard let metadataURL = Bundle.main.url(forResource: "release-info", withExtension: "json"),
              let notesURL = Bundle.main.url(forResource: "release-notes", withExtension: "md"),
              let data = try? Data(contentsOf: metadataURL),
              var info = try? JSONDecoder().decode(Self.self, from: data),
              let notes = try? String(contentsOf: notesURL, encoding: .utf8) else { return nil }
        info.notes = notes
        return info
    }
}

private extension CacheStorageCategory {
    var title: String {
        switch self {
        case .chats: String(localized: "聊天记录")
        case .images: String(localized: "图片")
        case .previews: String(localized: "文件预览")
        case .temporary: String(localized: "临时文件")
        }
    }
    var detail: String {
        switch self {
        case .chats: String(localized: "已缓存的聊天内容与搜索索引")
        case .images: String(localized: "聊天中已下载的图片")
        case .previews: String(localized: "已打开的文档、音视频等附件")
        case .temporary: String(localized: "预览、下载和分享产生的临时副本")
        }
    }
    var symbol: String {
        switch self {
        case .chats: "bubble.left.and.bubble.right.fill"
        case .images: "photo.fill"
        case .previews: "doc.text.fill"
        case .temporary: "folder.fill"
        }
    }
    var color: Color {
        switch self {
        case .chats: Color(red: 0.24, green: 0.48, blue: 0.78)
        case .images: Color(red: 0.23, green: 0.64, blue: 0.55)
        case .previews: Color(red: 0.86, green: 0.64, blue: 0.28)
        case .temporary: Color(red: 0.63, green: 0.51, blue: 0.76)
        }
    }
}

struct StorageSpaceView: View {
    @EnvironmentObject private var store: AppStore
    @State private var sortsBySize = true

    private func size(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }

    private func sortedCategories(_ usage: AppStorageUsage) -> [CacheStorageCategory] {
        let categories = CacheStorageCategory.allCases
        return categories.sorted { lhs, rhs in
            if sortsBySize, usage.bytes(for: lhs) != usage.bytes(for: rhs) {
                return usage.bytes(for: lhs) > usage.bytes(for: rhs)
            }
            if !sortsBySize { return lhs.title.localizedStandardCompare(rhs.title) == .orderedAscending }
            return lhs.rawValue < rhs.rawValue
        }
    }

    var body: some View {
        List {
            Section {
                if let usage = store.storageUsage {
                    ZStack {
                        if usage.totalBytes > 0 {
                            if #available(iOS 17.0, *) {
                                Chart(CacheStorageCategory.allCases) { category in
                                    if usage.bytes(for: category) > 0 {
                                        SectorMark(angle: .value("磁盘占用", usage.bytes(for: category)),
                                            innerRadius: .ratio(0.72), angularInset: 1.5)
                                            .foregroundStyle(category.color)
                                            .cornerRadius(3)
                                    }
                                }
                                .chartLegend(.hidden)
                                .accessibilityHidden(true)
                            } else {
                                // SectorMark 为 iOS 17 起可用，16.1 退回占位圆环。
                                Circle().stroke(Color.secondary.opacity(0.12), lineWidth: 26)
                                    .padding(13)
                                    .accessibilityHidden(true)
                            }
                        } else {
                            Circle().stroke(Color.secondary.opacity(0.12), lineWidth: 26)
                                .padding(13)
                                .accessibilityHidden(true)
                        }
                        VStack(spacing: 6) {
                            Text(verbatim: size(usage.totalBytes))
                                .appFont(.system(size: 28, weight: .semibold, design: .rounded))
                                .monospacedDigit()
                                .lineLimit(1)
                                .minimumScaleFactor(0.6)
                            Text("本机缓存").appFont(.subheadline).foregroundStyle(.secondary)
                        }
                        .frame(width: 154)
                        .accessibilityElement(children: .combine)
                    }
                    .frame(width: 200, height: 200)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 22)
                    if usage.freeBytes != Int64.max {
                        LabeledContent("设备可用空间", value: size(usage.freeBytes))
                            .appFont(.subheadline).foregroundStyle(.secondary)
                    }
                } else {
                    ProgressView("正在统计…")
                        .frame(maxWidth: .infinity, minHeight: 230)
                }
            } header: {
                Text("磁盘占用")
            }

            if let usage = store.storageUsage {
                Section {
                    ForEach(sortedCategories(usage)) { category in
                        NavigationLink {
                            StorageCategoryDetailView(category: category)
                        } label: {
                            HStack(spacing: 14) {
                                StorageCategoryIcon(category: category)
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(category.title).appFont(.body).foregroundStyle(.primary)
                                    Text(category.detail).appFont(.caption).foregroundStyle(.secondary)
                                        .lineLimit(1)
                                }
                                Spacer(minLength: 8)
                                Text(verbatim: size(usage.bytes(for: category)))
                                    .appFont(.body.monospacedDigit()).foregroundStyle(.secondary)
                                    .lineLimit(1).fixedSize()
                            }
                            .padding(.vertical, 8)
                        }
                        .accessibilityIdentifier("storage-category-\(category.rawValue)")
                        .alignmentGuide(.listRowSeparatorLeading) { $0[.leading] + 46 }
                    }
                } header: {
                    HStack {
                        Spacer()
                        Menu {
                            Picker("排序方式", selection: $sortsBySize) {
                                Text("大小").tag(true)
                                Text("名称").tag(false)
                            }
                        } label: {
                            HStack(spacing: 5) {
                                Text(sortsBySize ? "大小" : "名称")
                                Image(systemName: "chevron.up.chevron.down").imageScale(.small)
                            }
                            .appFont(.body)
                            .foregroundStyle(.tint)
                        }
                        .textCase(nil)
                        .accessibilityIdentifier("storage-sort-menu")
                    }
                    .padding(.bottom, 6)
                } footer: {
                    if usage.resources.protectedBytes > 0 {
                        Text(String(localized: "storage.in-use", defaultValue: "其中 \(size(usage.resources.protectedBytes)) 正在使用，关闭文件后可释放。"))
                    }
                }
            }
            if let notice = store.cacheStorageNotice {
                Section { Text(notice).appFont(.footnote).foregroundStyle(.secondary) }
            }
        }
        .navigationTitle("存储空间")
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("storage-space-page")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                if store.cacheStorageBusy {
                    ProgressView().accessibilityLabel(Text("正在统计…"))
                } else {
                    Button { Task { await store.refreshCacheStorage() } } label: {
                        Image(systemName: "arrow.triangle.2.circlepath")
                            .appFont(.system(size: 17, weight: .medium))
                    }
                    .accessibilityLabel(Text("刷新存储统计"))
                }
            }
        }
        .task { await store.refreshCacheStorage() }
        .refreshable { await store.refreshCacheStorage() }
    }
}

private struct StorageCategoryIcon: View {
    let category: CacheStorageCategory
    var side: CGFloat = 32

    var body: some View {
        Image(systemName: category.symbol)
            .font(.system(size: side * 0.52, weight: .medium))
            .foregroundStyle(.white)
            .frame(width: side, height: side)
            .background(category.color.gradient, in: RoundedRectangle(cornerRadius: side * 0.23))
            .accessibilityHidden(true)
    }
}

private struct StorageCategoryDetailView: View {
    @EnvironmentObject private var store: AppStore
    let category: CacheStorageCategory

    var body: some View {
        List {
            Section {
                HStack(spacing: 16) {
                    StorageCategoryIcon(category: category, side: 56)
                    VStack(alignment: .leading, spacing: 5) {
                        Text(category.title).appFont(.title3.weight(.semibold))
                        Text(category.detail).appFont(.subheadline).foregroundStyle(.secondary)
                    }
                }
                .padding(.vertical, 12)
                LabeledContent("占用空间", value: ByteCountFormatter.string(
                    fromByteCount: store.storageUsage?.bytes(for: category) ?? 0, countStyle: .file))
            }
            Section {
                Button(role: .destructive) {
                    Task { await store.refreshCacheStorage(category: category) }
                } label: {
                    HStack {
                        Spacer()
                        if store.cacheStorageBusy { ProgressView() }
                        else { Text("清理本机缓存") }
                        Spacer()
                    }
                    .padding(.vertical, 4)
                }
                .disabled(store.cacheStorageBusy || (store.storageUsage?.bytes(for: category) ?? 0) == 0)
                .accessibilityLabel(String(localized: "storage.clear.category", defaultValue: "清理\(category.title)"))
                .accessibilityIdentifier("storage-clear-\(category.rawValue)")
            }
            if let notice = store.cacheStorageNotice {
                Section { Text(notice).appFont(.footnote).foregroundStyle(.secondary) }
            }
        }
        .navigationTitle(category.title)
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("storage-category-detail")
    }
}


private struct LogExportSection: View {
    @State private var busy = false
    @State private var presentation: LogExportPresentation?
    @State private var failed = false

    var body: some View {
        Section {
            Button("导出日志") { prepare(share: false) }
                .accessibilityIdentifier("export-logs")
            Button("分享日志") { prepare(share: true) }
                .accessibilityIdentifier("share-logs")
        } header: { Text("日志")
        } footer: {
            Text("按小时保存，日志和导出副本合计不超过 200 MB，自动清理最旧日志。")
            if busy { ProgressView() }
        }
        .disabled(busy)
        .sheet(item: $presentation) { item in
            LogExportSheet(urls: item.urls, share: item.share)
        }
        .alert("日志导出失败，请重试", isPresented: $failed) {
            Button("好", role: .cancel) {}
        }
    }

    private func prepare(share: Bool) {
        busy = true
        Task {
            defer { busy = false }
            do {
                let urls = try await LocalAppLog.shared.export()
                presentation = LogExportPresentation(urls: urls, share: share)
            } catch { failed = true }
        }
    }
}

private struct LogExportPresentation: Identifiable {
    let id = UUID()
    let urls: [URL]
    let share: Bool
}

private struct LogExportSheet: UIViewControllerRepresentable {
    let urls: [URL]
    let share: Bool
    func makeUIViewController(context: Context) -> UIViewController {
        if share { return UIActivityViewController(activityItems: urls, applicationActivities: nil) }
        return UIDocumentPickerViewController(forExporting: urls, asCopy: true)
    }
    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}
