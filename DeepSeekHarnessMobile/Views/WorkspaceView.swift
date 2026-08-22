import SwiftUI
import AVFoundation

struct WorkspaceView: View {
    @EnvironmentObject private var store: AppStore
    let onOpenSession: (SessionSummary) -> Void
    let onNewSession: () -> Void
    let onSettings: () -> Void
    @State private var searchQuery = ""
    @State private var showsDirectoryBrowser = false
    @State private var showsQRScanner = false
    @State private var showsManualPairing = false

    var body: some View {
        ZStack {
            DeepOceanBackground()
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 18) {
                    header.id("workspace-header")
                    Spacer(minLength: 108)
                    VStack(alignment: .leading, spacing: 7) {
                        Text("探索未至之境")
                            .font(.system(size: 32, weight: .bold))
                        Text("DeepSeek Harness 预览版")
                            .font(.subheadline).foregroundStyle(.white.opacity(0.65))
                    }
                    .id("workspace-hero")
                    workspaceCard.id("workspace-card")
                    newSessionButton.id("workspace-new-session")
                    sessionsHeader.id("workspace-sessions-header")
                    sessionSearch.id("workspace-session-search")
                    if displayedSessions.isEmpty {
                        emptySessions.id("workspace-sessions-empty")
                    } else {
                        sessionsList
                    }
                    Spacer(minLength: 24)
                }
                .scrollTargetLayout()
                .padding(.horizontal, 22).padding(.top, 18)
            }
            .scrollIndicators(.hidden)
            .defaultScrollAnchor(.top)
            .scrollPosition(id: $store.workspaceScrollAnchor, anchor: .top)
        }
        .foregroundStyle(.white)
        .onAppear {
            store.refreshRemoteState()
        }
        .onChange(of: searchQuery) { _, value in store.search(value) }
        .sheet(isPresented: $showsDirectoryBrowser) {
            DirectoryBrowserSheet()
                .environmentObject(store)
        }
        .fullScreenCover(isPresented: $showsQRScanner) {
            GatewayQRScannerView(
                onCode: handleScannedCode,
                onCancel: { showsQRScanner = false },
                onFailure: { message in
                    showsQRScanner = false
                    store.lastError = message
                }
            )
        }
        .sheet(isPresented: $showsManualPairing) {
            ManualGatewayPairingSheet(gateway: store.gateway)
                .environmentObject(store)
        }
    }

    private var header: some View {
        HStack {
            HarnessMark()
            Spacer()
            authenticationMenu
            settingsButton
                // Match the outer glass circle to the workspace cards' trailing edge.
                .padding(.trailing, -4)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private var authenticationMenu: some View {
        GatewayAuthenticationMenu(
            gateway: store.gateway,
            onScan: {
                store.lastError = nil
                showsQRScanner = true
            },
            onManualEntry: {
                store.lastError = nil
                showsManualPairing = true
            }
        )
    }

    @ViewBuilder
    private var settingsButton: some View {
        headerButton(systemName: "gearshape.fill", accessibilityLabel: "设置", action: onSettings)
    }

    @ViewBuilder
    private func headerButton(systemName: String, accessibilityLabel: String, action: @escaping () -> Void) -> some View {
        let label = headerButtonLabel(systemName: systemName)
        if #available(iOS 26.0, *) {
            Button(action: action) { label }
                .buttonStyle(.glass)
                .buttonBorderShape(.circle)
                .accessibilityLabel(accessibilityLabel)
        } else {
            Button(action: action) { label }
                .buttonStyle(.plain)
                .background(.ultraThinMaterial, in: Circle())
                .overlay(Circle().stroke(.white.opacity(0.25), lineWidth: 0.8))
                .accessibilityLabel(accessibilityLabel)
        }
    }

    private func headerButtonLabel(systemName: String) -> some View {
        Image(systemName: systemName)
            .font(.system(size: 17, weight: .semibold))
            .frame(width: 40, height: 40)
            .contentShape(Circle())
    }

    private var workspaceCard: some View {
        Menu {
            Button {
                store.selectUngroupedWorkspace()
            } label: {
                Label {
                    Text("未分组")
                } icon: {
                    Image(systemName: store.isUngroupedWorkspaceSelected ? "checkmark.circle.fill" : "tray")
                }
            }

            if !store.workspaces.isEmpty { Divider() }

            ForEach(store.workspaces) { workspace in
                Button {
                    store.selectWorkspace(workspace)
                } label: {
                    Label {
                        Text(workspace.title)
                    } icon: {
                        Image(systemName: workspace.id == store.activeWorkspace?.id ? "checkmark.circle.fill" : "folder")
                    }
                }
            }

            Divider()

            Button {
                showsDirectoryBrowser = true
            } label: {
                Label("添加工作区", systemImage: "plus")
            }
        } label: {
            workspaceCardLabel
        }
        .buttonStyle(.plain)
        .accessibilityLabel("选择工作区")
    }

    private var workspaceCardLabel: some View {
        HStack(spacing: 12) {
            Image(systemName: "folder").foregroundStyle(.blue).font(.title3)
            VStack(alignment: .leading, spacing: 2) {
                Text(workspaceDisplayTitle).font(.subheadline.weight(.semibold))
                Text(workspaceDisplayPath).font(.caption).foregroundStyle(.white.opacity(0.55)).lineLimit(1)
            }
            Spacer()
            GatewayConnectionIndicator(gateway: store.gateway)
            Image(systemName: "chevron.down").font(.caption).foregroundStyle(.white.opacity(0.55))
        }
        .padding(16)
        .background(.white.opacity(0.055), in: RoundedRectangle(cornerRadius: 15))
        .overlay(RoundedRectangle(cornerRadius: 15).stroke(.white.opacity(0.14)))
        .contentShape(RoundedRectangle(cornerRadius: 15))
    }

    private var sessionsHeader: some View {
        HStack {
            Text("最近会话").font(.headline)
            Spacer()
            GatewayConnectionStatusText(gateway: store.gateway)
        }
    }

    private func handleScannedCode(_ rawValue: String) {
        do {
            try store.pair(usingQRCode: rawValue)
            showsQRScanner = false
        } catch {
            showsQRScanner = false
            store.lastError = error.localizedDescription
        }
    }

    private var sessionSearch: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(.white.opacity(0.68))
            TextField(
                "",
                text: $searchQuery,
                prompt: Text("搜索会话内容")
                    .foregroundStyle(.white.opacity(0.58))
            )
            .foregroundStyle(.white.opacity(0.92))
            .tint(.white)
            .textInputAutocapitalization(.never)
        }
        .padding(.horizontal, 12).frame(height: 42)
        .background(.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 13))
        .overlay(RoundedRectangle(cornerRadius: 13).stroke(.white.opacity(0.1)))
    }

    private var emptySessions: some View {
        Text("暂无已知会话。连接服务后创建第一个任务。")
            .font(.subheadline).foregroundStyle(.white.opacity(0.5))
            .frame(maxWidth: .infinity, alignment: .leading).padding(18)
            .background(.white.opacity(0.04), in: RoundedRectangle(cornerRadius: 15))
    }

    private var displayedSessions: [SessionSummary] {
        let workspaceSessions: [SessionSummary]
        if store.isUngroupedWorkspaceSelected {
            workspaceSessions = store.ungroupedSessions
        } else if let workspace = store.activeWorkspace {
            let ids = Set(workspace.sessionIds)
            workspaceSessions = store.sessions.filter { ids.contains($0.id) }
        } else {
            workspaceSessions = store.sessions
        }
        guard !searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return workspaceSessions
        }
        let ids = Set(store.searchResults.map(\.sessionId))
        return workspaceSessions.filter { ids.contains($0.id) || $0.title.localizedCaseInsensitiveContains(searchQuery) }
    }

    private var workspaceDisplayTitle: String {
        store.isUngroupedWorkspaceSelected ? "未分组" : (store.activeWorkspace?.title ?? "DeepseekHarnessProject")
    }

    private var workspaceDisplayPath: String {
        if store.isUngroupedWorkspaceSelected {
            return "\(store.ungroupedSessions.count) 个未归属会话"
        }
        return store.activeWorkspace?.path ?? "通过 Mobile Gateway 连接"
    }

    private var sessionsList: some View {
        LazyVStack(spacing: 0) {
            ForEach(displayedSessions.prefix(12)) { session in
                Button { onOpenSession(session) } label: { sessionRow(session) }
                    .buttonStyle(.plain)
                    .id("workspace-session-\(session.id)")

                Divider()
                    .overlay(.white.opacity(0.1))
                    .padding(.leading, 18)
            }
        }
    }

    private func sessionRow(_ session: SessionSummary) -> some View {
        HStack(spacing: 11) {
            Circle()
                .fill(session.isRunning ? DSHColor.success : (session.hasUnread ? DSHColor.ocean : .white.opacity(0.35)))
                .frame(width: 7, height: 7)
                .shadow(color: session.isRunning ? DSHColor.success : .clear, radius: 5)
            VStack(alignment: .leading, spacing: 3) {
                Text(session.title).lineLimit(1).font(.subheadline.weight(.medium))
                Text(String(session.id.prefix(16))).font(.caption2.monospaced()).foregroundStyle(.white.opacity(0.42))
            }
            Spacer()
            Text(session.isRunning ? "运行中" : session.lastActivity.formatted(.relative(presentation: .named)))
                .font(.caption).foregroundStyle(session.isRunning ? .blue : .white.opacity(0.48))
        }
        .padding(.horizontal, 4)
        .padding(.vertical, 10)
        .contentShape(Rectangle())
    }

    @ViewBuilder
    private var newSessionButton: some View {
        if #available(iOS 26.0, *) {
            Button(action: onNewSession) {
                newSessionButtonLabel
            }
            .buttonStyle(.glass(.clear.tint(DSHColor.navy.opacity(0.22))))
            .buttonBorderShape(.roundedRectangle(radius: 18))
            .buttonSizing(.flexible)
        } else {
            Button(action: onNewSession) {
                newSessionButtonLabel
            }
            .buttonStyle(.plain)
            .glassSurface(radius: 18, dark: true, tint: .white.opacity(0.08))
        }
    }

    private var newSessionButtonLabel: some View {
        HStack(spacing: 10) {
            Image(systemName: "plus").font(.system(size: 15, weight: .semibold))
                .frame(width: 28, height: 28)
                .background(.white.opacity(0.1), in: Circle())
            Text("新建会话").font(.headline)
        }
        .frame(maxWidth: .infinity).frame(height: 38)
        .contentShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
    }
}

private struct DirectoryBrowserSheet: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @State private var creatingPath: String?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button {
                        if let parentPath { store.browseDirectories(path: parentPath) }
                    } label: {
                        directoryRow(icon: "arrowshape.turn.up.left", title: "..", subtitle: "返回上一级")
                    }
                    .disabled(parentPath == nil)

                    ForEach(store.directoryEntries) { entry in
                        Button {
                            store.browseDirectories(path: entry.path)
                        } label: {
                            directoryRow(
                                icon: entry.hidden ? "folder.badge.questionmark" : "folder",
                                title: entry.name,
                                subtitle: entry.hidden ? "隐藏目录" : nil
                            )
                        }
                    }
                } header: {
                    VStack(alignment: .leading, spacing: 5) {
                        Text("当前目录")
                        Text(store.directoryPath ?? "正在读取…")
                            .font(.caption.monospaced())
                            .textCase(nil)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .allowsHitTesting(!store.directoryIsLoading)
            .overlay {
                if store.directoryIsLoading && store.directoryEntries.isEmpty {
                    ProgressView("正在读取远程目录…")
                }
            }
            .safeAreaInset(edge: .bottom) {
                createWorkspaceBar
            }
            .navigationTitle("选择工作区目录")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("取消") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationBackground(.ultraThinMaterial)
        .onAppear {
            store.directoryEntries = []
            store.browseDirectories()
        }
        .onChange(of: store.selectedWorkspaceId) { _, _ in
            guard let creatingPath,
                  store.activeWorkspace?.path == creatingPath else { return }
            dismiss()
        }
    }

    private var parentPath: String? {
        guard store.directoryCrumbs.count > 1 else { return nil }
        return store.directoryCrumbs.dropLast().last?.path
    }

    private func directoryRow(icon: String, title: String, subtitle: String?) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .foregroundStyle(DSHColor.ocean)
                .frame(width: 24)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).foregroundStyle(Color(uiColor: .label))
                if let subtitle {
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(Color(uiColor: .secondaryLabel))
                }
            }
            Spacer()
            Image(systemName: "chevron.right")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.tertiary)
        }
        .contentShape(Rectangle())
    }

    private var createWorkspaceBar: some View {
        VStack(spacing: 8) {
            if let path = store.directoryPath {
                Text(path)
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Button {
                guard let path = store.directoryPath else { return }
                creatingPath = path
                store.createWorkspace(path: path)
            } label: {
                HStack(spacing: 9) {
                    if store.workspaceCreationIsLoading {
                        ProgressView().tint(.white)
                    } else {
                        Image(systemName: "plus")
                    }
                    Text("在当前目录创建工作区")
                        .fontWeight(.semibold)
                }
                .frame(maxWidth: .infinity)
                .frame(height: 44)
                .foregroundStyle(.white)
                .background {
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .fill(Color.black)
                }
                .overlay {
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .stroke(Color.white.opacity(0.18), lineWidth: 0.8)
                }
                .contentShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
            .buttonStyle(.plain)
            .disabled(store.directoryPath == nil || store.workspaceCreationIsLoading)
            .allowsHitTesting(!store.directoryIsLoading)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 9)
        .background(.ultraThinMaterial)
    }
}

private struct GatewayConnectionIndicator: View {
    @ObservedObject var gateway: GatewayClient

    var body: some View {
        ConnectionDot(state: gateway.state)
    }
}

private struct GatewayAuthenticationMenu: View {
    @ObservedObject var gateway: GatewayClient
    let onScan: () -> Void
    let onManualEntry: () -> Void

    var body: some View {
        if #available(iOS 26.0, *) {
            menu
                .buttonStyle(.glass)
                .buttonBorderShape(.circle)
        } else {
            menu
                .buttonStyle(.plain)
                .background(.ultraThinMaterial, in: Circle())
                .overlay(Circle().stroke(.white.opacity(0.25), lineWidth: 0.8))
        }
    }

    private var menu: some View {
        Menu {
            Button(action: onScan) {
                Label("扫描二维码", systemImage: "qrcode.viewfinder")
            }
            Button(action: onManualEntry) {
                Label("手动输入配对信息", systemImage: "keyboard")
            }
        } label: {
            Image(systemName: "person.badge.key.fill")
                .font(.system(size: 17, weight: .semibold))
                .frame(width: 40, height: 40)
                .contentShape(Circle())
        }
        .accessibilityLabel("设备认证，\(gateway.state.label)")
    }

}

private struct GatewayConnectionStatusText: View {
    @ObservedObject var gateway: GatewayClient

    var body: some View {
        Text(gateway.state.label)
            .font(.caption.weight(.medium))
            .foregroundStyle(color)
            .animation(.easeInOut(duration: 0.18), value: gateway.state)
    }

    private var color: Color {
        switch gateway.state {
        case .connected: DSHColor.success
        case .connecting: DSHColor.amber
        case .failed: .red.opacity(0.9)
        case .disconnected: .white.opacity(0.5)
        }
    }
}

private struct ManualGatewayPairingSheet: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var gateway: GatewayClient

    @State private var pairingText = ""
    @State private var validationError: String?
    @State private var didAttemptConnection = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("手动输入配对信息")
                            .font(.title2.bold())
                        Text("粘贴 Harness WebUI 提供的 Base64URL 配对字符串。长期设备 token 仍只会保存到 Keychain。")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }

                    ZStack(alignment: .topLeading) {
                        if pairingText.isEmpty {
                            Text(Self.placeholder)
                                .font(.system(.footnote, design: .monospaced))
                                .foregroundStyle(.tertiary)
                                .padding(.horizontal, 16)
                                .padding(.vertical, 18)
                                .allowsHitTesting(false)
                        }
                        TextEditor(text: $pairingText)
                            .font(.system(.footnote, design: .monospaced))
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .scrollContentBackground(.hidden)
                            .padding(10)
                    }
                    .frame(minHeight: 210)
                    .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                    .overlay {
                        RoundedRectangle(cornerRadius: 20, style: .continuous)
                            .stroke(.primary.opacity(0.1), lineWidth: 0.8)
                    }

                    connectionResult

                    Button(action: connect) {
                        HStack(spacing: 9) {
                            if case .connecting = gateway.state {
                                ProgressView().tint(.white)
                            } else {
                                Image(systemName: "link")
                            }
                            Text(gateway.state.isConnected ? "重新配对并连接" : "连接")
                                .fontWeight(.semibold)
                        }
                        .frame(maxWidth: .infinity)
                        .frame(height: 50)
                        .foregroundStyle(.white)
                        .background(DSHColor.ocean, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                    }
                    .buttonStyle(.plain)
                    .disabled(pairingText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || gateway.state == .connecting)
                    .opacity(pairingText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? 0.45 : 1)
                }
                .padding(22)
            }
            .background(Color(uiColor: .systemGroupedBackground))
            .navigationTitle("设备认证")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("完成") { dismiss() }
                }
            }
        }
        .presentationDetents([.large])
        .presentationBackground(.regularMaterial)
    }

    @ViewBuilder
    private var connectionResult: some View {
        if let validationError {
            resultCard(
                title: "配对信息无效",
                detail: validationError,
                color: .red,
                symbol: "exclamationmark.triangle.fill"
            )
        } else if didAttemptConnection {
            switch gateway.state {
            case .connecting:
                resultCard(
                    title: "正在连接",
                    detail: "正在提交一次性配对码并等待 Mobile Gateway 完成设备鉴权…",
                    color: DSHColor.amber,
                    symbol: "arrow.triangle.2.circlepath"
                )
            case .connected:
                resultCard(
                    title: "连接成功",
                    detail: "设备鉴权已完成，长期 token 已安全保存到 Keychain。",
                    color: DSHColor.success,
                    symbol: "checkmark.circle.fill"
                )
            case .failed(let reason):
                resultCard(
                    title: "连接失败",
                    detail: reason,
                    color: .red,
                    symbol: "xmark.octagon.fill"
                )
            case .disconnected:
                resultCard(
                    title: "未连接",
                    detail: "请检查配对信息后重新连接。",
                    color: .secondary,
                    symbol: "network.slash"
                )
            }
        } else {
            resultCard(
                title: gateway.state.label,
                detail: "输入 Base64URL 配对字符串后点击连接，结果会显示在这里。",
                color: .secondary,
                symbol: "person.badge.key.fill"
            )
        }
    }

    private func resultCard(title: String, detail: String, color: Color, symbol: String) -> some View {
        HStack(alignment: .top, spacing: 13) {
            Image(systemName: symbol)
                .font(.title3.weight(.semibold))
                .foregroundStyle(color)
                .frame(width: 26)
            VStack(alignment: .leading, spacing: 5) {
                Text(title).font(.headline)
                Text(detail)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)
            }
            Spacer(minLength: 0)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(color.opacity(0.08), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(color.opacity(0.18), lineWidth: 0.8)
        }
    }

    private func connect() {
        validationError = nil
        didAttemptConnection = true
        do {
            try store.pair(usingQRCode: pairingText, presentsFailureAlert: false)
        } catch {
            validationError = error.localizedDescription
        }
    }

    private static let placeholder = "eyJ2ZXJzaW9uIjoyLCJwdWJsaWNVcmwiOiJ3c3M6Ly9nYXRld2F5LmV4YW1wbGUuY29tL3dzL21vYmlsZSIsLi4ufQ"
}

private struct GatewayQRScannerView: View {
    let onCode: (String) -> Void
    let onCancel: () -> Void
    let onFailure: (String) -> Void

    var body: some View {
        ZStack {
            GatewayCameraPreview(onCode: onCode, onFailure: onFailure)
                .ignoresSafeArea()

            LinearGradient(
                colors: [.black.opacity(0.58), .clear, .black.opacity(0.72)],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()
            .allowsHitTesting(false)

            VStack(spacing: 0) {
                HStack {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("扫描设备配对码")
                            .font(.title2.bold())
                        Text("请扫描 Harness WebUI 生成的二维码")
                            .font(.subheadline)
                            .foregroundStyle(.white.opacity(0.72))
                    }
                    Spacer()
                    Button(action: onCancel) {
                        Image(systemName: "xmark")
                            .font(.system(size: 16, weight: .semibold))
                            .frame(width: 44, height: 44)
                    }
                    .buttonStyle(.plain)
                    .background(.ultraThinMaterial, in: Circle())
                    .accessibilityLabel("取消扫描")
                }
                .padding(.horizontal, 22)
                .padding(.top, 18)

                Spacer()

                RoundedRectangle(cornerRadius: 28, style: .continuous)
                    .stroke(.white, lineWidth: 3)
                    .frame(width: 272, height: 272)
                    .overlay(alignment: .bottom) {
                        Text("将二维码完整放入框内")
                            .font(.subheadline.weight(.medium))
                            .padding(.horizontal, 16)
                            .padding(.vertical, 9)
                            .background(.ultraThinMaterial, in: Capsule())
                            .offset(y: 58)
                    }

                Spacer()
            }
            .foregroundStyle(.white)
        }
    }
}

private struct GatewayCameraPreview: UIViewControllerRepresentable {
    let onCode: (String) -> Void
    let onFailure: (String) -> Void

    func makeUIViewController(context: Context) -> GatewayScannerController {
        let controller = GatewayScannerController()
        controller.onCode = onCode
        controller.onFailure = onFailure
        controller.start()
        return controller
    }

    func updateUIViewController(_ uiViewController: GatewayScannerController, context: Context) {}

    static func dismantleUIViewController(_ uiViewController: GatewayScannerController, coordinator: Void) {
        uiViewController.stop()
    }
}

private final class GatewayScannerController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onCode: ((String) -> Void)?
    var onFailure: ((String) -> Void)?

    private let captureSession = AVCaptureSession()
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var didFinish = false

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
    }

    func start() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            configureAndRun()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                DispatchQueue.main.async {
                    guard let self else { return }
                    if granted {
                        self.configureAndRun()
                    } else {
                        self.finish(with: "未获得相机权限。请在系统设置中允许 DeepSeek Harness 使用相机后重试。")
                    }
                }
            }
        case .denied, .restricted:
            finish(with: "相机权限不可用。请在系统设置中允许 DeepSeek Harness 使用相机后重试。")
        @unknown default:
            finish(with: "无法确定当前相机权限状态。")
        }
    }

    func stop() {
        guard captureSession.isRunning else { return }
        DispatchQueue.global(qos: .userInitiated).async { [captureSession] in
            captureSession.stopRunning()
        }
    }

    private func configureAndRun() {
        guard !captureSession.isRunning, captureSession.inputs.isEmpty else { return }
        guard let camera = AVCaptureDevice.default(for: .video) else {
            finish(with: "此设备没有可用的相机。")
            return
        }
        do {
            let input = try AVCaptureDeviceInput(device: camera)
            guard captureSession.canAddInput(input) else {
                finish(with: "无法把相机接入扫码会话。")
                return
            }
            captureSession.addInput(input)

            let output = AVCaptureMetadataOutput()
            guard captureSession.canAddOutput(output) else {
                finish(with: "当前设备不支持二维码识别。")
                return
            }
            captureSession.addOutput(output)
            output.setMetadataObjectsDelegate(self, queue: .main)
            guard output.availableMetadataObjectTypes.contains(.qr) else {
                finish(with: "当前相机不支持二维码元数据识别。")
                return
            }
            output.metadataObjectTypes = [.qr]

            let preview = AVCaptureVideoPreviewLayer(session: captureSession)
            preview.videoGravity = .resizeAspectFill
            preview.frame = view.bounds
            view.layer.insertSublayer(preview, at: 0)
            previewLayer = preview

            DispatchQueue.global(qos: .userInitiated).async { [captureSession] in
                captureSession.startRunning()
            }
        } catch {
            finish(with: "相机启动失败：\(error.localizedDescription)")
        }
    }

    func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        guard !didFinish,
              let code = metadataObjects.compactMap({ ($0 as? AVMetadataMachineReadableCodeObject)?.stringValue }).first else { return }
        didFinish = true
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        stop()
        onCode?(code)
    }

    private func finish(with message: String) {
        guard !didFinish else { return }
        didFinish = true
        stop()
        onFailure?(message)
    }
}
