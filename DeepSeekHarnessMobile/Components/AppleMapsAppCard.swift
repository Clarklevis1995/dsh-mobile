import SwiftUI
import UIKit
import DeepSeekHarnessShared

/// 兼容旧名称；实际由共享协议解析通用卡片及历史苹果/谷歌链接。
struct AppleMapsCardDestination: Equatable {
    let latitude: Double
    let longitude: Double
    let name: String
    let transport: String?

    init?(link: String, title: String) {
        guard let value = SharedMapLinks().parse(link: link, title: title) else { return nil }
        latitude = value.latitude
        longitude = value.longitude
        name = value.name.isEmpty ? String(localized: "地图地点") : value.name
        transport = value.mode == "directions"
            ? ["driving": "d", "walking": "w", "transit": "r"][value.transport] : nil
    }

    var navigation: SharedMapDestination { SharedMapLinks().navigationDestination(destination: shared) }

    var shared: SharedMapDestination {
        SharedMapDestination(latitude: latitude, longitude: longitude, name: name,
            mode: transport == nil ? "show" : "directions",
            transport: ["d": "driving", "w": "walking", "r": "transit"][transport ?? "d"] ?? "driving",
            coordinateSystem: "wgs84")
    }
}

/// 只有应用内登记的插件可以构造跳转；不执行模型传来的任意 scheme。
struct IOSMapAppPlugin: Identifiable {
    let id: String
    let probeScheme: String
    var title: String {
        switch id {
        case "apple": String(localized: "苹果地图")
        case "amap": String(localized: "高德地图")
        case "baidu": String(localized: "百度地图")
        default: String(localized: "谷歌地图")
        }
    }
    static let all: [IOSMapAppPlugin] = [
        .init(id: "apple", probeScheme: "maps://"),
        .init(id: "amap", probeScheme: "iosamap://"),
        .init(id: "baidu", probeScheme: "baidumap://"),
        .init(id: "google", probeScheme: "comgooglemaps://")
    ]
    var iconAsset: String {
        switch id {
        case "apple": "MapAppApple"
        case "amap": "MapAppAmap"
        case "baidu": "MapAppBaidu"
        default: "MapAppGoogle"
        }
    }
    func url(for destination: AppleMapsCardDestination) -> URL? {
        SharedMapLinks().openURL(destination: destination.navigation, provider: id, platform: "ios").flatMap(URL.init(string:))
    }
    @MainActor
    static func installed(canOpen: (URL) -> Bool) -> [IOSMapAppPlugin] {
        all.filter { URL(string: $0.probeScheme).map(canOpen) == true }
    }
}

struct AppleMapsAppCard: View {
    let destination: AppleMapsCardDestination
    @State private var choosingMap = false

    var body: some View {
        AppLinkCard(icon: "ConnectorLocation", name: destination.name, subtitle: String(localized: "选择地图应用")) {
            choosingMap = true
        }
        .accessibilityIdentifier("apple-maps-app-card")
        .accessibilityLabel(Text("\(destination.name)，\(String(localized: "选择地图应用"))"))
        .sheet(isPresented: $choosingMap) { MapApplicationPicker(destination: destination) }
    }
}

@MainActor
struct MapApplicationPicker: View {
    let destination: AppleMapsCardDestination
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @State private var apps: [IOSMapAppPlugin]
    @State private var openFailed = false
    @State private var contentHeight: CGFloat
    private let installedApps: @MainActor () -> [IOSMapAppPlugin]

    init(destination: AppleMapsCardDestination,
         installedApps: @escaping @MainActor () -> [IOSMapAppPlugin] = { IOSMapAppPlugin.installed(canOpen: UIApplication.shared.canOpenURL) }) {
        self.destination = destination
        self.installedApps = installedApps
        let apps = installedApps()
        _apps = State(initialValue: apps)
        // 首帧先按行数估算，排版后用实际尺寸替换，避免单个应用先弹出大面板。
        _contentHeight = State(initialValue: 200 + CGFloat(max(1, apps.count)) * 72)
    }

    var body: some View {
        ScrollView {
            MapApplicationPickerContent(destination: destination, apps: apps, onClose: { dismiss() },
                onApp: { open($0.url(for: destination)) },
                onWeb: { open(SharedMapLinks().browserURL(d: destination.navigation).flatMap(URL.init(string:))) })
                .fixedSize(horizontal: false, vertical: true)
                .onGeometryChange(for: CGFloat.self, of: { ceil($0.size.height) }) { height in
                    if height.isFinite && height > 0 && abs(contentHeight - height) > 0.5 {
                        contentHeight = height
                    }
                }
        }
        .background(Color(.systemGroupedBackground).ignoresSafeArea())
        .presentationDetents([.height(contentHeight)])
        .presentationDragIndicator(.hidden)
        .mapPickerSheetStyle()
        .onAppear(perform: reload)
        .onChange(of: scenePhase) { phase in if phase == .active { reload() } }
        .alert("无法打开地图，请重新选择", isPresented: $openFailed) { Button("好", role: .cancel) { } }
        .accessibilityIdentifier("map-app-picker")
    }

    private func reload() { apps = installedApps() }
    private func open(_ url: URL?) {
        guard let url else { openFailed = true; return }
        UIApplication.shared.open(url, options: [:]) { success in
            Task { @MainActor in
                if success { dismiss() } else { openFailed = true; reload() }
            }
        }
    }
}

private extension View {
    @ViewBuilder func mapPickerSheetStyle() -> some View {
        if #available(iOS 16.4, *) {
            self.presentationBackground(Color(.systemGroupedBackground)).presentationCornerRadius(32)
                .scrollBounceBehavior(.basedOnSize)
        } else { self }
    }
}

/// 与 Figma 的分组面板一致；系统负责底部安全区和 Home 指示条。
struct MapApplicationPickerContent: View {
    let destination: AppleMapsCardDestination
    let apps: [IOSMapAppPlugin]
    var onClose: () -> Void
    var onApp: (IOSMapAppPlugin) -> Void
    var onWeb: () -> Void
    @ScaledMetric private var headingSize: CGFloat = 21
    @ScaledMetric private var captionSize: CGFloat = 13
    @ScaledMetric private var webSize: CGFloat = 14

    private var travelLabel: String {
        switch destination.transport {
        case "w": String(localized: "步行")
        case "r": String(localized: "公交")
        default: String(localized: "驾车")
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            Capsule().fill(Color("MapPickerHandle")).frame(width: 36, height: 5)
                .padding(.top, 10).padding(.bottom, 16)
            HStack {
                Text("选择地图应用").font(.system(size: headingSize, weight: .semibold))
                    .frame(maxWidth: .infinity, alignment: .leading)
                Button(action: onClose) {
                    Image(systemName: "xmark").font(.system(size: 16, weight: .regular))
                        .foregroundStyle(Color("MapPickerSecondary"))
                        .frame(width: 32, height: 32).background(Color("MapPickerClose"), in: Circle())
                }
                .buttonStyle(.plain).accessibilityLabel("关闭").accessibilityIdentifier("map-picker-close")
            }
            .frame(minHeight: 32)
            VStack(alignment: .leading, spacing: 3) {
                Text(destination.name).font(.body.weight(.medium)).frame(minHeight: 25, alignment: .leading)
                Text(String(format: String(localized: "从当前位置出发 · %@"), travelLabel))
                    .font(.system(size: captionSize)).foregroundStyle(Color("MapPickerSecondary"))
                    .frame(minHeight: 19, alignment: .leading)
            }
            .frame(maxWidth: .infinity, alignment: .leading).padding(.top, 14).padding(.bottom, 18)
            VStack(spacing: 0) {
                ForEach(Array(apps.enumerated()), id: \.element.id) { index, plugin in
                    Button { onApp(plugin) } label: {
                        HStack(spacing: 14) {
                            Image(plugin.iconAsset).resizable().scaledToFit().frame(width: 44, height: 44)
                                .clipShape(RoundedRectangle(cornerRadius: 11))
                                .accessibilityIdentifier("map-icon-\(plugin.id)")
                            Text(plugin.title).font(.body).frame(maxWidth: .infinity, alignment: .leading)
                            Image(systemName: "chevron.right").font(.system(size: 14, weight: .regular))
                                .foregroundStyle(Color(.tertiaryLabel)).frame(width: 18, height: 18)
                        }
                        .padding(.leading, 16).padding(.trailing, 14).padding(.vertical, 14)
                        .frame(minHeight: 72).contentShape(Rectangle())
                    }
                    .buttonStyle(.plain).accessibilityIdentifier("map-provider-\(plugin.id)")
                    if index != apps.count - 1 {
                        Color("MapPickerSeparator").frame(height: 0.5).padding(.leading, 74)
                    }
                }
                if apps.isEmpty {
                    Text("未检测到可用地图应用").font(.body).foregroundStyle(Color("MapPickerSecondary"))
                        .frame(maxWidth: .infinity, alignment: .leading).padding(16)
                }
            }
            .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 20))
            .clipShape(RoundedRectangle(cornerRadius: 20))
            Button(action: onWeb) {
                HStack(spacing: 10) {
                    Image(systemName: "globe").font(.system(size: 17))
                    Text("使用网页地图").font(.system(size: webSize))
                }
                .frame(maxWidth: .infinity, minHeight: 59).contentShape(Rectangle())
            }
            .buttonStyle(.plain).foregroundStyle(Color.accentColor).accessibilityIdentifier("map-provider-browser")
        }
        .foregroundStyle(Color("MapPickerInk")).padding(.horizontal, 20)
        .frame(maxWidth: .infinity).background(Color(.systemGroupedBackground))
    }
}
