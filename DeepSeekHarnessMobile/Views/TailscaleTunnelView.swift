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

    var body: some View {
        if TailscaleTunnelSettings.shared.enabled {
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
    @ObservedObject private var tunnel = TailscaleTunnelController.shared
    @Environment(\.openURL) private var openURL

    @State private var enabled = TailscaleTunnelSettings.shared.enabled
    @State private var nodeName = TailscaleTunnelSettings.shared.nodeName
    @State private var authKey = TailscaleTunnelSettings.shared.authKey
    @State private var invalidName = false
    @State private var copied = false

    var body: some View {
        Form {
            Section {
                Toggle("启用内嵌隧道", isOn: $enabled)
                    .accessibilityIdentifier("tunnel-enabled-switch")
            } header: {
                Text("Tailscale 内嵌隧道")
            } footer: {
                Text("在应用内建立一个 Tailscale 节点：不需要安装 Tailscale，也不会占用系统 VPN。节点只在你自己的 tailnet 内可达。")
            }

            if enabled {
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
                            // 与 Android 同形：先用词条，再拼数字，不把插值当成一条本地化键。
                            Text(verbatim: "\(String(localized: "对端数量")) \(tunnel.state.peerCount)")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .accessibilityIdentifier("tunnel-status-row")

                    if let address = tunnel.state.nodeAddress {
                        LabeledContent("本机地址", value: address)
                    }
                    if let reason = tunnelFailureText(tunnel.state.failureKind) {
                        LabeledContent("失败原因", value: reason)
                    }
                    if let authUrl = tunnel.state.authUrl {
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
                    if tunnel.state.stage != .disabled {
                        Button(role: .destructive) {
                            enabled = false
                            TailscaleTunnelSettings.shared.selectEnabled(false)
                            tunnel.stop()
                        } label: {
                            Text(tunnel.state.stage == .starting || tunnel.state.stage == .needsLogin
                                ? String(localized: "停止启动") : String(localized: "停止隧道"))
                        }
                    }
                } header: {
                    Text("隧道状态")
                } footer: {
                    if tunnel.state.stage == .needsLogin {
                        Text("等待你在浏览器里完成登录。")
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
                            .font(.footnote)
                            .foregroundStyle(.red)
                    }
                    TextField("登录密钥（可选）", text: $authKey,
                              prompt: Text("tskey-auth-…"))
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .accessibilityIdentifier("tunnel-field-auth-key")
                } header: {
                    Text("节点身份")
                } footer: {
                    Text("节点名称只允许小写字母、数字和连字符。登录时默认在浏览器里完成；也可以粘贴 Tailscale 控制台生成的 Auth Key。")
                }
            }
        }
        .navigationTitle("Tailscale 内嵌隧道")
        // 单参数的 onChange 是有意的：双参数形式要 iOS 17，而这个 app 的下限是 16.1。
        .onChange(of: enabled) { value in
            TailscaleTunnelSettings.shared.selectEnabled(value)
            tunnel.setEnabled(value)
        }
        .onChange(of: nodeName) { value in
            invalidName = !TailscaleTunnelSettings.shared.selectNodeName(value)
        }
        .onChange(of: authKey) { value in
            TailscaleTunnelSettings.shared.selectAuthKey(value)
        }
    }
}

/// 设置页里的主机侧入口（与 Android 的 TailscaleTunnelRow 同形态）。
struct TailscaleTunnelSettingsRow: View {
    @ObservedObject private var tunnel = TailscaleTunnelController.shared

    var body: some View {
        NavigationLink {
            TailscaleTunnelSettingsView()
        } label: {
            HStack {
                Text("内嵌隧道")
                Spacer()
                Text(verbatim: TailscaleTunnelSettings.shared.enabled
                    ? tunnelStageLabel(tunnel.state.stage) : String(localized: "未启用"))
                    .foregroundStyle(.secondary)
            }
        }
        .accessibilityIdentifier("tailscale-tunnel-settings-row")
    }
}
