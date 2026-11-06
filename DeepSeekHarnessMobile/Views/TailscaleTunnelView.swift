// 内嵌 Tailscale 隧道的界面：九点标记、首页指示、设置页。
//
// 文案沿用 Android 侧同一批中文键（androidApp/.../ui/TailscaleTunnelScreen.kt 与
// localization/AndroidStrings.kt），两边不该有两套说法；英文译文加在
// Resources/Localizable.xcstrings 里。
//
// 九点标记的几何与时序是从本机安装的官方 macOS 客户端素材里量出来的，与
// androidApp/.../ui/TailscaleMarkIcon.kt 同一份测量结果：
//   /Applications/Tailscale.app/Contents/Resources/Assets.car
//     StatusBarIconDot1…16（连接中动画 16 帧）、StatusBarIcon（已连接）、StatusBarIconDimmed（未连接）
//   176px 画布上九个点中心在 39.5/87.5/135.5、点半径约 16.2 → 48 单位视口 10.8/23.9/37.0、r=4.4；
//   未点亮的点 alpha=77/255。帧间隔官方没有公开依据（各平台 GUI 不开源），100ms 是我们自己的取值。
// 刻意不变色：连接中与已连接都用同一个颜色，只靠"闪/不闪"区分。
import SwiftUI
import Combine

enum TailscaleMark {
    /// 48 单位视口里的九点中心，索引 = 行*3 + 列，左上为 0。
    static let centers: [CGPoint] = [
        [10.8, 10.8], [23.9, 10.8], [37.0, 10.8],
        [10.8, 23.9], [23.9, 23.9], [37.0, 23.9],
        [10.8, 37.0], [23.9, 37.0], [37.0, 37.0]
    ].map { CGPoint(x: $0[0], y: $0[1]) }
    static let radius: CGFloat = 4.4
    static let viewport: CGFloat = 48
    /// 未点亮的点的不透明度：实测 77/255。
    static let dimAlpha: Double = 77.0 / 255.0
    static let frameMillis: Int = 100

    /// 连接中的 16 帧（0 起算的点索引），逐帧采样官方素材得到。
    static let connectingFrames: [Set<Int>] = [
        [1, 2, 3, 5, 8], [1, 2, 5, 7], [1, 2, 8], [2, 4], [1], [5], [], [2],
        [6], [6], [6, 7], [3, 6, 7], [3, 4, 7], [3, 4, 7, 8], [3, 4, 5, 8], [1, 4, 5, 6, 8]
    ].map { Set($0) }

    /// 已连接时的静态点亮：官方 StatusBarIcon 实测为 4、5、6、8（1 起算）。
    static let connectedFrame: Set<Int> = [3, 4, 5, 7]
}

/// 画九点标记。connected 为真时静态点亮，为假时按官方 16 帧的时序闪。
struct TailscaleMarkIcon: View {
    var connected: Bool
    var tint: Color = .white

    var body: some View {
        // 帧号由时间推导，不用 @State：TimelineView 只在需要动画时刷新，
        // 已连接的静态标记不会挂着一个每 100ms 的时钟。
        Group {
            if connected {
                mark(TailscaleMark.connectedFrame)
            } else {
                TimelineView(.periodic(from: Date(timeIntervalSince1970: 0), by: Double(TailscaleMark.frameMillis) / 1000)) { context in
                    let index = Int(context.date.timeIntervalSince1970 * 1000
                        / Double(TailscaleMark.frameMillis)) % TailscaleMark.connectingFrames.count
                    mark(TailscaleMark.connectingFrames[index])
                }
            }
        }
        .accessibilityHidden(true)
    }

    private func mark(_ lit: Set<Int>) -> some View {
        Canvas { context, size in
            let scale = min(size.width, size.height) / TailscaleMark.viewport
            let radius = TailscaleMark.radius * scale
            for (index, center) in TailscaleMark.centers.enumerated() {
                let alpha = lit.contains(index) ? 1.0 : TailscaleMark.dimAlpha
                let point = CGPoint(x: center.x * scale, y: center.y * scale)
                context.fill(
                    Path(ellipseIn: CGRect(x: point.x - radius, y: point.y - radius,
                                          width: radius * 2, height: radius * 2)),
                    with: .color(tint.opacity(alpha))
                )
            }
        }
    }
}

/// 首页主机切换条左侧的隧道标记：未打开隧道就完全不显示（用户要求）。
/// connected 只有在节点真的 Running 时才成立，连接中就按官方素材闪。
struct TailscaleTunnelIndicator: View {
    @ObservedObject private var tunnel = TailscaleTunnelController.shared
    @ObservedObject private var settings = TailscaleTunnelSettings.shared

    var body: some View {
        if settings.enabled {
            TailscaleMarkIcon(connected: tunnel.state.stage == .ready,
                              tint: Color.white.opacity(0.85))
                .frame(width: 18, height: 18)
                .accessibilityLabel("内嵌隧道")
        }
    }
}

// MARK: - 状态文案（与 Android 的 stageLabel / failureText 同词）

private func tunnelStageLabel(_ stage: TunnelUiStage) -> String {
    switch stage {
    case .disabled: return String(localized: "未启用")
    case .starting: return String(localized: "启动中")
    case .needsLogin: return String(localized: "需要登录")
    case .waitingRoutes: return String(localized: "正在建立连接")
    case .ready: return String(localized: "已连接")
    case .failed: return String(localized: "连接失败")
    }
}

private func tunnelFailureText(_ kind: TunnelFailureKind?) -> String? {
    guard let kind else { return nil }
    switch kind {
    case .interfaceSource: return String(localized: "网络接口读取失败")
    case .nativeLoad: return String(localized: "运行库加载失败")
    case .backendStart: return String(localized: "节点启动失败")
    case .loginWait: return String(localized: "登录等待超时")
    case .status: return String(localized: "状态查询失败")
    case .shutdown: return String(localized: "旧隧道未能关闭，请重试或重启应用。")
    case .unknown: return String(localized: "未知错误")
    }
}

private func tunnelStageColor(_ stage: TunnelUiStage) -> Color {
    switch stage {
    case .ready: return DSHColor.success
    case .failed: return Color.red
    case .disabled: return Color.red
    case .needsLogin, .starting, .waitingRoutes: return DSHColor.ocean
    }
}

// MARK: - 设置页

struct TailscaleTunnelSettingsView: View {
    @ObservedObject private var tunnel: TailscaleTunnelController
    @Environment(\.openURL) private var openURL
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.colorScheme) private var colorScheme

    @ObservedObject private var settings: TailscaleTunnelSettings
    @State private var editingAccount: TailscaleAccount?
    @State private var deletingAccount: TailscaleAccount?
    @State private var nodeName: String
    @State private var authKey: String
    @State private var invalidName = false
    @State private var copied = false

    init(settings: TailscaleTunnelSettings? = nil, tunnel: TailscaleTunnelController? = nil) {
        let settings = settings ?? .shared
        self.settings = settings
        self.tunnel = tunnel ?? .shared
        _nodeName = State(initialValue: settings.nodeName)
        _authKey = State(initialValue: settings.authKey)
    }

    var body: some View {
        Form {
            Section {
                Toggle("启用内嵌隧道", isOn: Binding(
                    get: { settings.enabled }, set: { tunnel.setEnabled($0) }))
                    .tint(DSHColor.switchTint(for: colorScheme))
                    .accessibilityIdentifier("tunnel-enabled-switch")
                    .disabled(tunnel.accountAddition != nil)
            } header: {
                Text("Tailscale 内嵌隧道")
                    .appFont(SettingsTypography.description)
            } footer: {
                Text("在应用内建立一个 Tailscale 节点：不需要安装 Tailscale，也不会占用系统 VPN。节点只在你自己的 tailnet 内可达。")
                    .appFont(SettingsTypography.description)
            }

            Section {
                ForEach(settings.accounts) { account in
                    Button {
                        tunnel.selectAccount(account.id)
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(verbatim: account.displayName)
                                    .foregroundStyle(.primary)
                                if !account.name.isEmpty, let login = account.loginName,
                                   !login.isEmpty, login != account.displayName {
                                    Text(verbatim: login).appFont(SettingsTypography.description).foregroundStyle(.secondary)
                                }
                                if let tailnet = account.tailnetName, !tailnet.isEmpty,
                                   tailnet != account.displayName, tailnet != account.loginName {
                                    Text(verbatim: tailnet).appFont(SettingsTypography.description).foregroundStyle(.secondary)
                                }
                            }
                            Spacer()
                            if account.id == settings.selectedAccountID {
                                Image(systemName: "checkmark").foregroundStyle(DSHColor.ocean)
                            }
                        }
                        .contentShape(Rectangle())
                    }
                    .accessibilityIdentifier("tunnel-account-\(account.id.uuidString)")
                    .accessibilityValue(account.id == settings.selectedAccountID
                        ? String(localized: "当前账号") : "")
                    .contextMenu {
                        Button {
                            editingAccount = account
                        } label: {
                            Label("重命名", systemImage: "pencil")
                        }
                        Button(role: .destructive) {
                            deletingAccount = account
                        } label: {
                            Label("删除账号", systemImage: "trash")
                        }
                    }
                }
                Button {
                    tunnel.beginAccountAddition()
                } label: {
                    Label("添加 Tailscale 账号", systemImage: "plus")
                }
                .accessibilityIdentifier("tunnel-add-account")
                .disabled(tunnel.accountAddition != nil)
            } header: {
                Text("账号与网络")
                    .appFont(SettingsTypography.description)
            } footer: {
                Text("仅显示已登录账号。点击账号可切换网络，登录状态会分别保留。")
                    .appFont(SettingsTypography.description)
            }

            if let addition = tunnel.accountAddition, addition.confirmed {
                Section {
                    HStack {
                        if !addition.failed { ProgressView().controlSize(.mini) }
                        Text(addition.failed ? String(localized: "新账号连接失败")
                            : addition.stage == .waitingRoutes ? String(localized: "正在准备新账号的路由…")
                            : String(localized: "等待新账号登录…"))
                            .appFont(SettingsTypography.description)
                            .foregroundStyle(.secondary)
                    }
                    Button("取消添加", role: .cancel) { tunnel.cancelAccountAddition() }
                }
            }

            if settings.enabled {
                Section {
                    HStack {
                        Circle()
                            .fill(tunnelStageColor(tunnel.state.stage))
                            .frame(width: 8, height: 8)
                        Text(verbatim: tunnelStageLabel(tunnel.state.stage))
                        Spacer()
                        if tunnel.state.isPolling {
                            ProgressView().controlSize(.mini)
                                .accessibilityIdentifier("tunnel-starting-progress")
                        } else if tunnel.state.peerCount > 0 {
                            Text(verbatim: String(localized: "tunnel.peers.count", defaultValue: "对端数量 \(tunnel.state.peerCount)"))
                                .appFont(SettingsTypography.item)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .accessibilityIdentifier("tunnel-status-row")

                    if let address = tunnel.state.nodeAddress {
                        LabeledContent(LocalizedStringKey("本机地址"), value: address)
                    }
                    if let reason = tunnelFailureText(tunnel.state.failureKind) {
                        LabeledContent(LocalizedStringKey("失败原因"), value: reason)
                    }
                    if let authUrl = tunnel.state.authUrl, tunnel.accountAddition?.confirmed != false {
                        Button("打开登录链接") {
                            if let link = URL(string: authUrl) { openURL(link) }
                        }
                        Button {
                            UIPasteboard.general.string = authUrl
                            copied = true
                            Task {
                                try? await Task.sleep(for: .seconds(2))
                                copied = false
                            }
                        } label: {
                            Text(copied ? String(localized: "已复制") : String(localized: "复制链接"))
                        }
                    }
                    if tunnel.state.stage == .disabled || tunnel.state.stage == .failed {
                        Button("继续连接") { tunnel.start() }
                            .accessibilityIdentifier("tunnel-retry")
                    }
                    if tunnel.state.stage != .disabled {
                        Button(role: .destructive) {
                            tunnel.setEnabled(false)
                        } label: {
                            Text(tunnel.state.stage == .starting || tunnel.state.stage == .needsLogin
                                ? String(localized: "停止启动") : String(localized: "停止隧道"))
                        }
                    }
                } header: {
                    Text("隧道状态")
                    .appFont(SettingsTypography.description)
                } footer: {
                    if tunnel.state.stage == .needsLogin {
                        Text("等待你在浏览器里完成登录。")
                    .appFont(SettingsTypography.description)
                    }
                }

                Section {
                    // 留空时用默认名（与 Android 的 placeholder 同义）：不要把设备名原文硬塞进输入框。
                    TextField("节点名称", text: $nodeName,
                              prompt: Text(verbatim: TailscaleTunnelSettings.defaultNodeName()))
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .accessibilityIdentifier("tunnel-field-node-name")
                    if invalidName {
                        Text("只允许小写字母、数字和连字符，最长 63 个字符。")
                            .appFont(SettingsTypography.description)
                            .foregroundStyle(.red)
                    }
                    SecureField("登录密钥（可选）", text: $authKey,
                              prompt: Text(verbatim: "tskey-auth-…"))
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .accessibilityIdentifier("tunnel-field-auth-key")
                    if !authKey.isEmpty {
                        Button("使用登录密钥连接") { tunnel.start() }
                    }
                } header: {
                    Text("节点身份")
                    .appFont(SettingsTypography.description)
                } footer: {
                    Text("节点名称只允许小写字母、数字和连字符。登录时默认在浏览器里完成；也可以粘贴 Tailscale 控制台生成的 Auth Key。")
                    .appFont(SettingsTypography.description)
                }
                .disabled(tunnel.accountAddition != nil)
            }
        }
        .appFont(SettingsTypography.item)
        .navigationTitle("Tailscale 内嵌隧道")
        .navigationBarTitleDisplayMode(.inline)
        // 单参数的 onChange 是有意的：双参数形式要 iOS 17，而这个 app 的下限是 16.1。
        .onChange(of: settings.selectedAccountID) { _ in
            nodeName = settings.nodeName
            authKey = settings.authKey
            invalidName = false
        }
        .onChange(of: settings.authKey) { authKey = $0 }
        .background {
            TailscaleAccountLoginAlert(tunnel: tunnel, confirm: {
                if let url = tunnel.confirmAccountAddition() { openURL(url) }
            }, cancel: {
                tunnel.cancelAccountAddition()
            })
            .frame(width: 0, height: 0)
        }
        .task(id: scenePhase) {
            if scenePhase == .active { await tunnel.refreshAfterReturningToApp() }
        }
        .sheet(item: $editingAccount) { account in
            TailscaleAccountEditor(title: LocalizedStringKey("重命名"), name: account.name) { name in
                settings.renameAccount(account.id, name: name)
            }
        }
        .alert("删除账号？", isPresented: Binding(
            get: { deletingAccount != nil },
            set: { if !$0 { deletingAccount = nil } }
        ), presenting: deletingAccount) { account in
            Button("取消", role: .cancel) { deletingAccount = nil }
            Button("删除账号", role: .destructive) {
                tunnel.deleteAccount(account.id)
                deletingAccount = nil
            }
        } message: { account in
            Text(verbatim: account.displayName) + Text(verbatim: "\n\n")
                + Text("此账号及其登录状态将从本机删除；若正在使用，会停止对应隧道。再次使用需重新登录。")
        }
        .onChange(of: nodeName) { value in
            invalidName = !settings.selectNodeName(value)
        }
        .onChange(of: authKey) { value in
            settings.selectAuthKey(value)
        }
    }
}

/// 登录弹窗使用普通 URL 文本与复制文字按钮，不创建可编辑输入框。
struct TailscaleAccountLoginAlert: UIViewControllerRepresentable {
    let tunnel: TailscaleTunnelController
    let confirm: () -> Void
    let cancel: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIViewController(context: Context) -> Presenter {
        let presenter = Presenter()
        presenter.view.backgroundColor = .clear
        context.coordinator.presenter = presenter
        context.coordinator.bind(to: tunnel)
        presenter.onAppear = { [weak coordinator = context.coordinator] in
            coordinator?.presentIfNeeded()
        }
        return presenter
    }

    func updateUIViewController(_ presenter: Presenter, context: Context) {
        context.coordinator.confirm = confirm
        context.coordinator.cancel = cancel
        context.coordinator.bind(to: tunnel)
        context.coordinator.presentIfNeeded()
    }

    static func dismantleUIViewController(_ presenter: Presenter, coordinator: Coordinator) {
        coordinator.tearDown()
    }

    final class Presenter: UIViewController {
        var onAppear: (() -> Void)?
        override func viewDidAppear(_ animated: Bool) {
            super.viewDidAppear(animated)
            onAppear?()
        }
    }

    @MainActor
    final class Coordinator {
        weak var presenter: UIViewController?
        private weak var model: TailscaleTunnelController?
        private var observation: AnyCancellable?
        var addition: TailscaleAccountAddition?
        var confirm: () -> Void = {}
        var cancel: () -> Void = {}
        private(set) var alert: TailscaleLoginDialogController?
        var writeClipboard: (String) -> Void = { UIPasteboard.general.string = $0 }

        func bind(to model: TailscaleTunnelController) {
            guard self.model !== model else { return }
            observation?.cancel()
            self.model = model
            // 弹窗已呈现时也直接更新 UIKit，不依赖被遮挡页面的 SwiftUI 重绘时机。
            observation = model.$accountAddition.sink { [weak self] addition in
                self?.addition = addition
                self?.presentIfNeeded()
            }
        }

        func presentIfNeeded() {
            guard let addition, !addition.confirmed else {
                alert?.dismiss(animated: true)
                alert = nil
                return
            }
            if let alert {
                alert.update(addition)
            } else if var host = presenter?.view.window?.rootViewController {
                while let presented = host.presentedViewController { host = presented }
                guard !(host is UIAlertController), !(host is TailscaleLoginDialogController) else { return }
                let alert = makeAlert()
                self.alert = alert
                host.present(alert, animated: true)
            }
        }

        func makeAlert() -> TailscaleLoginDialogController {
            let alert = TailscaleLoginDialogController()
            alert.onCopy = { [weak self] in self?.copyLoginURL() }
            alert.onConfirm = { [weak self] in
                guard let self, self.addition?.loginURL != nil, self.addition?.failed == false else { return }
                self.addition?.confirmed = true
                let dialog = self.alert
                self.alert = nil
                if let dialog { dialog.dismiss(animated: true, completion: self.confirm) }
                else { self.confirm() }
            }
            alert.onCancel = { [weak self] in
                guard let self else { return }
                self.addition = nil
                self.alert?.dismiss(animated: true)
                self.alert = nil
                self.cancel()
            }
            alert.loadViewIfNeeded()
            if let addition { alert.update(addition) }
            return alert
        }

        func copyLoginURL() {
            guard let url = addition?.loginURL else { return }
            writeClipboard(url.absoluteString)
            UIAccessibility.post(notification: .announcement, argument: String(localized: "已复制"))
        }

        func tearDown() {
            observation?.cancel()
            observation = nil
            alert?.dismiss(animated: false)
            alert = nil
            if addition?.confirmed == false { cancel() }
        }
    }
}

@MainActor
final class TailscaleLoginDialogController: UIViewController {
    let loginURLLabel = UILabel()
    let copyButton = UIButton(type: .system)
    let confirmButton = UIButton(type: .system)
    let cancelButton = UIButton(type: .system)
    private let titleLabel = UILabel()
    private let messageLabel = UILabel()
    private let urlRow = UIStackView()
    var onCopy: () -> Void = {}
    var onConfirm: () -> Void = {}
    var onCancel: () -> Void = {}

    init() {
        super.init(nibName: nil, bundle: nil)
        modalPresentationStyle = .overFullScreen
        modalTransitionStyle = .crossDissolve
        isModalInPresentation = true
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor.black.withAlphaComponent(0.28)
        view.accessibilityViewIsModal = true
        view.accessibilityIdentifier = "tunnel-login-alert"
        let card = UIView()
        card.backgroundColor = .secondarySystemGroupedBackground
        card.layer.cornerRadius = 24
        card.layer.cornerCurve = .continuous
        card.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(card)

        titleLabel.text = String(localized: "添加 Tailscale 账号")
        titleLabel.font = UIFont.appPreferredFont(forTextStyle: .headline)
        titleLabel.textColor = .label
        titleLabel.numberOfLines = 0
        messageLabel.font = UIFont.appPreferredFont(forTextStyle: .footnote)
        messageLabel.textColor = .secondaryLabel
        messageLabel.numberOfLines = 0
        loginURLLabel.font = UIFont.appPreferredFont(forTextStyle: .caption1)
        loginURLLabel.textColor = .secondaryLabel
        loginURLLabel.numberOfLines = 1
        loginURLLabel.lineBreakMode = .byTruncatingMiddle
        loginURLLabel.accessibilityIdentifier = "tunnel-login-url"
        loginURLLabel.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)

        copyButton.setTitle(String(localized: "复制"), for: .normal)
        copyButton.setTitleColor(.systemBlue, for: .normal)
        copyButton.setTitleColor(.tertiaryLabel, for: .disabled)
        copyButton.titleLabel?.font = UIFont.appPreferredFont(forTextStyle: .footnote)
        copyButton.setContentHuggingPriority(.required, for: .horizontal)
        copyButton.setContentCompressionResistancePriority(.required, for: .horizontal)
        copyButton.accessibilityIdentifier = "tunnel-login-copy-url"
        copyButton.addTarget(self, action: #selector(copyTapped), for: .touchUpInside)
        urlRow.axis = .horizontal
        urlRow.alignment = .center
        urlRow.spacing = 8
        urlRow.addArrangedSubview(loginURLLabel)
        urlRow.addArrangedSubview(copyButton)
        urlRow.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
        copyButton.widthAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
        copyButton.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true

        cancelButton.setTitle(String(localized: "取消"), for: .normal)
        confirmButton.setTitle(String(localized: "确认"), for: .normal)
        cancelButton.accessibilityIdentifier = "tunnel-login-cancel"
        confirmButton.accessibilityIdentifier = "tunnel-login-confirm"
        for button in [cancelButton, confirmButton] {
            button.titleLabel?.font = UIFont.appPreferredFont(forTextStyle: .body)
            button.backgroundColor = .tertiarySystemFill
            button.layer.cornerRadius = 18
            button.setTitleColor(.label, for: .normal)
            button.setTitleColor(.tertiaryLabel, for: .disabled)
        }
        cancelButton.addTarget(self, action: #selector(cancelTapped), for: .touchUpInside)
        confirmButton.addTarget(self, action: #selector(confirmTapped), for: .touchUpInside)
        let actions = UIStackView(arrangedSubviews: [cancelButton, confirmButton])
        actions.axis = .horizontal
        actions.spacing = 10
        actions.distribution = .fillEqually
        actions.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
        let content = UIStackView(arrangedSubviews: [titleLabel, messageLabel, urlRow, actions])
        content.axis = .vertical
        content.spacing = 14
        content.translatesAutoresizingMaskIntoConstraints = false
        card.addSubview(content)
        let preferredWidth = card.widthAnchor.constraint(equalToConstant: 340)
        preferredWidth.priority = .defaultHigh
        NSLayoutConstraint.activate([
            card.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            card.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            preferredWidth,
            card.leadingAnchor.constraint(greaterThanOrEqualTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 24),
            card.trailingAnchor.constraint(lessThanOrEqualTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -24),
            content.topAnchor.constraint(equalTo: card.topAnchor, constant: 22),
            content.leadingAnchor.constraint(equalTo: card.leadingAnchor, constant: 22),
            content.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -22),
            content.bottomAnchor.constraint(equalTo: card.bottomAnchor, constant: -22)
        ])
    }

    func update(_ addition: TailscaleAccountAddition) {
        loadViewIfNeeded()
        titleLabel.text = addition.isReauthentication
            ? String(localized: "登录 Tailscale 账号") : String(localized: "添加 Tailscale 账号")
        messageLabel.text = addition.failed
            ? String(localized: "无法获取登录链接，请取消后重试。")
            : addition.loginURL == nil
                ? String(localized: "正在获取登录链接…")
                : String(localized: "即将跳转到 Tailscale 登录页面。你可以复制下方链接；点击确认打开页面，登录完成后应用会自动连接。")
        loginURLLabel.text = addition.loginURL?.absoluteString
        urlRow.isHidden = addition.loginURL == nil
        confirmButton.isEnabled = addition.loginURL != nil && !addition.failed
        copyButton.isEnabled = addition.loginURL != nil
    }

    @objc private func copyTapped() { onCopy() }
    @objc private func confirmTapped() { onConfirm() }
    @objc private func cancelTapped() { onCancel() }
}

private struct TailscaleAccountEditor: View {
    let title: LocalizedStringKey
    @State var name: String
    let save: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                TextField("账号备注（可选）", text: $name)
                    .accessibilityIdentifier("tunnel-account-name")
            }
            .navigationTitle(title)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("确定") {
                        save(name)
                        dismiss()
                    }
                    .accessibilityIdentifier("tunnel-account-save")
                }
            }
        }
    }
}

/// 设置页里的主机侧入口（与 Android 的 TailscaleTunnelRow 同形态）。
struct TailscaleTunnelSettingsRow: View {
    @ObservedObject private var tunnel = TailscaleTunnelController.shared
    @ObservedObject private var settings = TailscaleTunnelSettings.shared
    let open: () -> Void

    var body: some View {
        Button(action: open) {
            HStack {
                Text("内嵌隧道")
                Spacer()
                Text(verbatim: settings.enabled
                    ? tunnelStageLabel(tunnel.state.stage) : String(localized: "未启用"))
                    .foregroundStyle(.secondary)
                Image(systemName: "chevron.right")
                    .foregroundStyle(.tertiary)
                    .accessibilityHidden(true)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("tailscale-tunnel-settings-row")
    }
}
