import SwiftUI

struct ConnectorsView: View {
    @ObservedObject var model: IOSDeviceConnectors
    var body: some View {
        List {
            Section {
                NavigationLink {
                    ConnectorRegistrationStatusView(model: model)
                } label: {
                    HStack {
                        Text("连接器状态")
                        Spacer()
                        Circle().fill(model.registrationState.indicatorColor)
                            .frame(width: 8, height: 8)
                            .accessibilityHidden(true)
                    }
                }
                .accessibilityValue(Text(model.registrationState.title))
                .accessibilityIdentifier("connector-registration-link")
            }

            Section {
                ForEach(IOSDeviceConnectors.modules) { module in
                    Toggle(isOn: Binding(get: { model.isOn(module.id) }, set: { value in
                        Task { await model.setEnabled(module.id, value) }
                    })) {
                        HStack(spacing: 12) {
                            ConnectorModuleIcon(id: module.id, size: 36)
                            Text(module.title)
                        }
                    }
                    .accessibilityIdentifier("connector-toggle-\(module.id)")
                    .disabled(!model.isAvailable(module.id) || model.authorizing || model.syncing)
                    .padding(.vertical, 5)
                }
            } header: { Text("本机能力") }
            Section {
                Button("打开系统权限设置") {
                    if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                }
            }
        }
        .navigationTitle("连接器")
        .task {
            while !Task.isCancelled {
                model.refreshRegistrationStatus()
                do { try await Task.sleep(for: .seconds(10)) } catch { return }
            }
        }
        .alert("连接器", isPresented: Binding(get: { model.notice != nil }, set: { if !$0 { model.notice = nil } })) {
            Button("好", role: .cancel) { model.notice = nil }
        } message: { Text(model.notice ?? "") }
    }

}

struct ConnectorRegistrationStatusView: View {
    @ObservedObject var model: IOSDeviceConnectors
    @State private var showingCapabilities = false
    @State private var selectedModuleID: String?

    var body: some View {
        List {
            Section {
                HStack(spacing: 9) {
                    Circle().fill(model.registrationState.indicatorColor).frame(width: 8, height: 8)
                    Text(model.registrationState.title)
                    Spacer()
                    if model.checkingRegistration || model.registrationState == .registering {
                        ProgressView().controlSize(.small)
                    }
                }
                .accessibilityIdentifier("connector-registration-status")
                if model.registrationState == .registered {
                    LabeledContent(String(localized: "网关已确认"), value: String.localizedStringWithFormat(String(localized: "%d 项能力"), model.registeredToolCount))
                } else {
                    Text(model.registrationState.detail).font(.subheadline).foregroundStyle(.secondary)
                }
            } header: {
                Text("网关注册")
            } footer: {
                if model.registrationState == .registered, let date = model.registrationConfirmedAt {
                    HStack(spacing: 6) {
                        Text("最近确认")
                        Text(date, style: .time)
                    }
                    .font(.footnote).foregroundStyle(.secondary)
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier("connector-last-confirmed")
                }
            }

            if model.registrationState == .registered {
                Section {
                    Button { selectedModuleID = nil; showingCapabilities = true } label: {
                        HStack(spacing: 12) {
                            Image("ConnectorRegisteredCapabilities")
                                .renderingMode(.template).resizable().scaledToFit()
                                .frame(width: 24, height: 24)
                                .foregroundStyle(Color(red: 46.0 / 255, green: 107.0 / 255, blue: 230.0 / 255))
                                .frame(width: 40, height: 40)
                                .background(Color(red: 46.0 / 255, green: 107.0 / 255, blue: 230.0 / 255).opacity(0.08), in: RoundedRectangle(cornerRadius: 11))
                                .accessibilityHidden(true)
                            VStack(alignment: .leading, spacing: 3) {
                                Text("网关已登记的能力").foregroundStyle(.primary)
                                Text(connectorCount(model.registeredModules.count)).font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(.tertiary)
                        }.padding(.vertical, 6)
                    }
                    .accessibilityIdentifier("connector-registered-capabilities")
                }
            }
            Section {
                HStack {
                    Button("刷新状态") { model.refreshRegistrationStatus() }
                        .disabled(!model.canCheckRegistration || model.checkingRegistration)
                        .accessibilityIdentifier("connector-refresh-registration")
                    Spacer()
                    Button("重新注册") { model.retryRegistration() }
                        .disabled(!model.canRetryRegistration)
                        .accessibilityIdentifier("connector-retry-registration")
                }.buttonStyle(.borderless)
            }

        }
        .navigationTitle("连接器状态")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showingCapabilities, onDismiss: { selectedModuleID = nil }) {
            ConnectorCapabilitiesSheet(model: model, selectedID: $selectedModuleID)
        }
        .onChange(of: model.registrationState) { state in
            if state != .registered { showingCapabilities = false }
        }
        .task {
            while !Task.isCancelled {
                model.refreshRegistrationStatus()
                do { try await Task.sleep(for: .seconds(10)) } catch { return }
            }
        }
    }

}

private func connectorCapabilityTitle(_ tool: String) -> String {
    let titles = [
        "calendar.busy": String(localized: "查询日历忙闲"), "calendar.create": String(localized: "创建日历日程"),
        "reminders.list": String(localized: "查询提醒事项"), "reminders.create": String(localized: "新建提醒事项"),
        "location.current": String(localized: "获取近似位置"), "location.precise": String(localized: "获取精确位置"),
        "maps.search": String(localized: "搜索地点"), "maps.geocode": String(localized: "解析地址"), "maps.reverse-geocode": String(localized: "查询坐标地址"),
        "maps.directions": String(localized: "规划路线"), "maps.open": String(localized: "打开苹果地图"),
        "maps.markers.create": String(localized: "保存应用内地点"), "maps.markers.list": String(localized: "查询应用内地点"),
        "maps.markers.update": String(localized: "修改应用内地点"), "maps.markers.delete": String(localized: "删除应用内地点"),
        "photos.select": String(localized: "选择照片"), "music.search": String(localized: "搜索本地音乐"), "music.state": String(localized: "读取播放状态"),
        "music.play": String(localized: "播放音乐"), "music.pause": String(localized: "暂停音乐"), "music.seek": String(localized: "调整播放进度"),
        "music.library": String(localized: "浏览 Apple Music 曲库"), "music.play-library": String(localized: "播放曲库歌曲或歌单"), "music.catalog-search": String(localized: "搜索 Apple Music 歌曲"),
        "health.daily-summary": String(localized: "读取步数与睡眠摘要"), "alarms.create": String(localized: "创建系统闹钟"),
        "alarms.list": String(localized: "查询应用闹钟"), "alarms.cancel": String(localized: "取消应用闹钟")
    ]
    return titles[tool] ?? tool
}

private func connectorCount(_ count: Int) -> String {
    String.localizedStringWithFormat(String(localized: "%d 个连接器"), count)
}

private func connectorToolCount(_ count: Int) -> String {
    String.localizedStringWithFormat(String(localized: "%d 项能力"), count)
}

private struct ConnectorModuleIcon: View {
    let id: String
    let size: CGFloat
    var body: some View {
        Group {
            if id == "alarms" {
                Image(systemName: "alarm.fill").font(.system(size: size * 0.78)).foregroundStyle(.orange)
            } else {
                Image("Connector" + id.capitalized).renderingMode(.original).resizable().scaledToFit()
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: size / 4, style: .continuous))
        .accessibilityHidden(true)
    }
}

/// 登记清单和权限始终从当前模型读取；打开详情不会冻结旧的授权状态。
struct ConnectorCapabilitiesSheet: View {
    @ObservedObject var model: IOSDeviceConnectors
    @Environment(\.dismiss) private var dismiss
    @Binding var selectedID: String?
    private let moduleID: String?
    init(model: IOSDeviceConnectors, selectedID: Binding<String?>, moduleID: String? = nil) {
        self.model = model
        _selectedID = selectedID
        self.moduleID = moduleID
    }
    @ScaledMetric private var moduleRowHeight: CGFloat = 66
    @ScaledMetric private var toolRowHeight: CGFloat = 52
    private var selected: PhoneConnectorRegistrationModule? {
        model.registeredModules.first { $0.id == moduleID }
    }
    @State private var sheetDetent: PresentationDetent = .medium

    private struct DetailRoute: Identifiable { let id: String }
    @State private var presentedDetail: DetailRoute?

    var body: some View {
        page(module: selected)
        .modifier(ConnectorSheetPresentation())
        .presentationDragIndicator(.visible)
        .presentationDetents([.medium, .large], selection: $sheetDetent)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(moduleID == nil ? "connector-capabilities-sheet" : "connector-capabilities-detail-sheet")
        .sheet(item: $presentedDetail, onDismiss: { selectedID = nil }) { route in
            ConnectorCapabilitiesSheet(model: model, selectedID: .constant(nil), moduleID: route.id)
        }
        .onChange(of: selectedID) { id in
            guard moduleID == nil else { return }
            presentedDetail = id.map { DetailRoute(id: $0) }
        }
        .onChange(of: model.registrationState) { state in
            if state != .registered { dismiss() }
        }
    }

    private func page(module: PhoneConnectorRegistrationModule?) -> some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                Text(module == nil ? "已登记的能力" : "能力详情")
                    .font(.system(size: 21, weight: .medium)).frame(maxWidth: .infinity, alignment: .leading)
                circleButton("xmark", label: String(localized: "关闭"), id: module == nil ? "connector-capabilities-close" : "connector-capabilities-detail-close") { dismiss() }
            }
            .padding(.horizontal, 20).padding(.top, 8)
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier(module == nil ? "connector-capabilities-header" : "connector-capabilities-detail-header")
            if module == nil {
                Text(String.localizedStringWithFormat(String(localized: "iOS · %d 个连接器 · %d 项能力"), model.registeredModules.count, model.registeredToolCount))
                    .font(.system(size: 14)).foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 20).padding(.top, 6).padding(.bottom, 18)
            }
            List {
                if let module {
                    Section {
                        moduleRow(module, detail: true)
                            .listRowInsets(EdgeInsets(top: 12, leading: 16, bottom: 12, trailing: 16))
                    }
                    Section {
                        if module.tools.isEmpty {
                            Text("网关未接入该能力").foregroundStyle(.secondary)
                        }
                        ForEach(module.tools, id: \.self) { tool in
                            HStack {
                                Text(connectorCapabilityTitle(tool))
                                Spacer(minLength: 12)
                                Image(systemName: allowed(module) ? "checkmark" : "minus")
                                    .foregroundStyle(allowed(module) ? Color.green : Color.secondary)
                                    .accessibilityLabel(Text(status(module)))
                            }
                            .frame(minHeight: toolRowHeight - 20)
                            .accessibilityIdentifier("connector-capability-\(tool)")
                            .listRowInsets(EdgeInsets(top: 10, leading: 16, bottom: 10, trailing: 16))
                        }
                    } header: { Text("已登记的控制能力").font(.system(size: 14)).textCase(nil) }
                } else {
                    Section {
                        ForEach(model.registeredModules) { module in
                            Button {
                                selectedID = module.id
                                presentedDetail = DetailRoute(id: module.id)
                            } label: { moduleRow(module, detail: false) }
                                .buttonStyle(.plain)
                                .listRowInsets(EdgeInsets(top: 10, leading: 16, bottom: 10, trailing: 14))
                                .accessibilityIdentifier("connector-registered-module-\(module.id)")
                        }
                    }
                }
            }
            .listStyle(.insetGrouped)
            .modifier(ConnectorSheetListMargins(detail: module != nil))
            .scrollContentBackground(.hidden)
            .padding(.bottom, module == nil ? 0 : 32)
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("connector-capabilities-page-\(module?.id ?? "list")")
    }

    private func allowed(_ module: PhoneConnectorRegistrationModule) -> Bool {
        model.isRegisteredModuleAllowed(module) && !module.tools.isEmpty
    }
    private func status(_ module: PhoneConnectorRegistrationModule) -> String {
        if !module.available || !model.isAvailable(module.id) { return String(localized: "设备不支持") }
        if !module.enabled || model.enabled[module.id] != true { return String(localized: "手机未开启") }
        if model.permissions[module.id] != true { return String(localized: "主机未允许") }
        if module.tools.isEmpty { return String(localized: "网关未接入") }
        return String(localized: "已允许")
    }
    private func moduleRow(_ module: PhoneConnectorRegistrationModule, detail: Bool) -> some View {
        HStack(spacing: 12) {
            ConnectorModuleIcon(id: module.id, size: detail ? 48 : 44)
            VStack(alignment: .leading, spacing: 2) {
                Text(detail && module.id == "music" ? "Apple Music" : (IOSDeviceConnectors.modules.first { $0.id == module.id }?.title ?? module.id))
                    .font(.system(size: 17, weight: detail ? .medium : .regular)).foregroundStyle(.primary)
                Text(connectorToolCount(module.tools.count) + (detail ? " · " + String(localized: "网关已确认") : ""))
                    .font(.system(size: 14)).foregroundStyle(.secondary)
            }.frame(maxWidth: .infinity, alignment: .leading)
            Text(status(module)).font(.system(size: 14)).foregroundStyle(.secondary)
            if !detail { Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(.tertiary) }
        }
        .frame(minHeight: detail ? 56 : moduleRowHeight - 20)
        // plain 按钮默认只命中文字/图标；让包含 Spacer 的整行都可点击。
        .contentShape(Rectangle())
    }
    private func circleButton(_ symbol: String, label: String, id: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol).font(.system(size: 15, weight: .medium)).foregroundStyle(.secondary)
                .frame(width: 32, height: 32).background(Color(.quaternarySystemFill), in: Circle())
                .frame(width: 44, height: 44)
        }.buttonStyle(.plain).accessibilityLabel(Text(label)).accessibilityIdentifier(id)
    }
}

private struct ConnectorSheetListMargins: ViewModifier {
    let detail: Bool
    @ViewBuilder func body(content: Content) -> some View {
        if #available(iOS 17.0, *) {
            content.contentMargins(.top, detail ? 16 : 0, for: .scrollContent)
                .contentMargins(.horizontal, 20, for: .scrollContent)
        } else { content }
    }
}

private struct ConnectorSheetPresentation: ViewModifier {
    @ViewBuilder func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            // 原生半高 Sheet 自动使用 Liquid Glass，不覆盖材质和系统圆角。
            content
        } else if #available(iOS 16.4, *) {
            content.background(Color(.systemGroupedBackground))
                .presentationBackground(Color(.systemGroupedBackground)).presentationCornerRadius(32)
        } else { content.background(Color(.systemGroupedBackground)) }
    }
}

#if DEBUG
/// UI 测试使用内存中的网关确认，不连接主机，也不读取系统数据。
struct ConnectorSheetUITestHarness: View {
    @StateObject private var model = makeModel()
    var body: some View {
        NavigationStack { ConnectorRegistrationStatusView(model: model) }
    }
    private static func makeModel() -> IOSDeviceConnectors {
        var registration: [String: Any] = [:]
        let model = IOSDeviceConnectors(hostID: "ui-test-connector-sheet") { raw in
            if let frame = try? JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any],
               frame["type"] as? String == "device-tools-register" { registration = frame }
        }
        model.connected(["device-tools-v1"])
        let tools = ["calendar": ["calendar.busy", "calendar.create"], "photos": ["photos.select"],
                     "location": ["location.current", "location.precise", "maps.search", "maps.geocode", "maps.reverse-geocode", "maps.directions", "maps.open", "maps.markers.create", "maps.markers.list", "maps.markers.update", "maps.markers.delete"],
                     "music": ["music.search", "music.state", "music.play", "music.pause", "music.seek", "music.library", "music.play-library", "music.catalog-search"]]
        let frame: [String: Any] = ["kind": "device-tools-registered", "version": 1,
            "requestId": registration["requestId"] ?? "", "capabilityRevision": registration["capabilityRevision"] ?? "",
            "connectionLeaseId": "ui-test", "permissions": [:],
            "modules": IOSDeviceConnectors.modules.map {
                ["id": $0.id, "available": true, "enabled": false, "tools": tools[$0.id] ?? []] as [String: Any]
            }]
        if let data = try? JSONSerialization.data(withJSONObject: frame) {
            model.receive(String(decoding: data, as: UTF8.self))
        }
        return model
    }
}
#endif

private extension PhoneConnectorRegistrationState {
    var indicatorColor: Color {
        switch self {
        case .registered: .green
        case .disconnected: .gray
        case .failed, .timedOut, .notRegistered: .red
        case .registering, .unsupported, .unverified: .orange
        }
    }
    var title: String {
        switch self {
        case .disconnected: String(localized: "网关未连接")
        case .unsupported: String(localized: "网关不支持连接器")
        case .registering: String(localized: "正在注册连接器")
        case .registered: String(localized: "网关已注册")
        case .unverified: String(localized: "注册状态未确认")
        case .failed: String(localized: "连接器注册失败")
        case .timedOut: String(localized: "连接器注册超时")
        case .notRegistered: String(localized: "已连接，连接器未注册")
        }
    }
    var detail: String {
        switch self {
        case .disconnected: String(localized: "连接主机后，将显示网关确认的能力。")
        case .unsupported: String(localized: "请更新并重启主机端网关，然后重新连接。")
        case .registering: String(localized: "正在等待网关确认本机能力。")
        case .unverified: String(localized: "网关尚未返回有效的能力清单。请刷新状态；旧版网关需要更新后重连。")
        case .failed: String(localized: "网关未接受本次注册。请检查网关版本并重新注册。")
        case .timedOut: String(localized: "未收到网关确认，请检查连接并重新注册。")
        case .notRegistered: String(localized: "聊天连接正常，但网关没有当前连接的能力登记。请重新注册。")
        case .registered: ""
        }
    }

}
