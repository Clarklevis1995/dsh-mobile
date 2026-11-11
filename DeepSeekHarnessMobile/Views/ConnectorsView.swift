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
                            if module.id == "alarms" {
                                Image(systemName: "alarm.fill").font(.system(size: 25)).foregroundStyle(.orange)
                                    .frame(width: 36, height: 36).accessibilityHidden(true)
                            } else {
                            Image("Connector" + module.id.capitalized)
                                .renderingMode(.original).resizable().scaledToFit()
                                .frame(width: 36, height: 36)
                                .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                                .accessibilityHidden(true)
                            }
                            Text(LocalizedStringKey(module.title))
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
                    LabeledContent("网关已确认", value: String.localizedStringWithFormat(String(localized: "%d 项能力"), model.registeredToolCount))
                    if let date = model.registrationConfirmedAt {
                        HStack {
                            Text("最近确认")
                            Spacer()
                            Text(date, style: .time).foregroundStyle(.secondary)
                        }.font(.subheadline)
                    }
                    DisclosureGroup("网关已登记的能力") {
                        ForEach(model.registeredModules) { module in
                            DisclosureGroup {
                                if module.tools.isEmpty {
                                    Text("网关未接入该能力").foregroundStyle(.secondary)
                                }
                                ForEach(module.tools, id: \.self) { tool in
                                    Text(capabilityTitle(tool)).font(.subheadline)
                                }
                            } label: {
                                HStack {
                                    Text(LocalizedStringKey(IOSDeviceConnectors.modules.first { $0.id == module.id }?.title ?? module.id))
                                    Spacer()
                                    Text(moduleStatus(module)).font(.caption).foregroundStyle(.secondary)
                                }
                            }
                            .accessibilityIdentifier("connector-registered-module-\(module.id)")
                        }
                    }.accessibilityIdentifier("connector-registered-capabilities")
                } else {
                    Text(model.registrationState.detail).font(.subheadline).foregroundStyle(.secondary)
                }
                HStack {
                    Button("刷新状态") { model.refreshRegistrationStatus() }
                        .disabled(!model.canCheckRegistration || model.checkingRegistration)
                        .accessibilityIdentifier("connector-refresh-registration")
                    Spacer()
                    Button("重新注册") { model.retryRegistration() }
                        .disabled(!model.canRetryRegistration)
                        .accessibilityIdentifier("connector-retry-registration")
                }.buttonStyle(.borderless)
            } header: { Text("网关注册") }

        }
        .navigationTitle("连接器状态")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            while !Task.isCancelled {
                model.refreshRegistrationStatus()
                do { try await Task.sleep(for: .seconds(10)) } catch { return }
            }
        }
    }

    private func moduleStatus(_ module: PhoneConnectorRegistrationModule) -> String {
        if !module.available { return String(localized: "设备不支持") }
        if !module.enabled || model.enabled[module.id] != true { return String(localized: "手机未开启") }
        if model.permissions[module.id] != true { return String(localized: "主机未允许") }
        if module.tools.isEmpty { return String(localized: "网关未接入") }
        return String(localized: "已允许")
    }
    private func capabilityTitle(_ tool: String) -> String {
        let titles = [
            "calendar.busy": "查询日历忙闲", "calendar.create": "创建日历日程",
            "reminders.list": "查询提醒事项", "reminders.create": "新建提醒事项",
            "location.current": "获取近似位置", "location.precise": "获取精确位置",
            "maps.search": "搜索地点", "maps.geocode": "解析地址", "maps.reverse-geocode": "查询坐标地址",
            "maps.directions": "规划路线", "maps.open": "打开苹果地图",
            "maps.markers.create": "保存应用内地点", "maps.markers.list": "查询应用内地点",
            "maps.markers.update": "修改应用内地点", "maps.markers.delete": "删除应用内地点",
            "photos.select": "选择照片", "music.search": "搜索本地音乐", "music.state": "读取播放状态",
            "music.play": "播放音乐", "music.pause": "暂停音乐", "music.seek": "调整播放进度",
            "music.library": "浏览 Apple Music 曲库", "music.play-library": "播放曲库歌曲或歌单", "music.catalog-search": "搜索 Apple Music 歌曲",
            "health.daily-summary": "读取步数与睡眠摘要", "alarms.create": "创建系统闹钟",
            "alarms.list": "查询应用闹钟", "alarms.cancel": "取消应用闹钟"
        ]
        return titles[tool].map { String(localized: String.LocalizationValue($0)) } ?? tool
    }
}

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
