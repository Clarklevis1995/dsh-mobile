import SwiftUI

struct GatewaySwitcherBar: View {
    @EnvironmentObject private var hosts: MultiGatewayStore
    @State private var showsHosts = false

    var body: some View {
        Button { showsHosts = true } label: {
            HStack(spacing: 8) {
                Image(systemName: hosts.activeProfile?.deviceKind ?? "desktopcomputer")
                Text(hosts.activeProfile?.displayName ?? "选择主机")
                    .lineLimit(1)
                Circle()
                    .fill(hosts.activeID.map { hosts.onlineIDs.contains($0) } == true ? Color.green : Color.gray)
                    .frame(width: 6, height: 6)
                Image(systemName: "chevron.down").font(.system(size: 9, weight: .semibold))
            }
            .font(.system(size: 12, weight: .medium))
            .padding(.horizontal, 11).padding(.vertical, 7)
            .background(.white.opacity(0.09), in: Capsule())
            .overlay(Capsule().stroke(.white.opacity(0.14), lineWidth: 0.5))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("当前主机：\(hosts.activeProfile?.displayName ?? "未选择")，切换主机")
        .sheet(isPresented: $showsHosts) { GatewaySwitcherSheet() }
    }
}

private struct GatewaySwitcherSheet: View {
    @EnvironmentObject private var hosts: MultiGatewayStore
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @State private var editMode: EditMode = .inactive
    @State private var selectedIDs: Set<String> = []
    @State private var confirmingDelete = false
    @State private var editing: GatewayProfile?
    @State private var alias = ""
    @State private var kind = "desktopcomputer"

    private var displayedProfiles: [GatewayProfile] {
        guard
            let activeID = hosts.activeID,
            let activeProfile = hosts.profiles.first(where: { $0.id == activeID })
        else { return hosts.profiles }
        return [activeProfile] + hosts.profiles.filter { $0.id != activeID }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(displayedProfiles) { profile in
                        HStack(spacing: 12) {
                            if editMode.isEditing {
                                Button { toggleSelection(profile.id) } label: {
                                    Image(systemName: selectedIDs.contains(profile.id) ? "circle.inset.filled" : "circle")
                                        .font(.system(size: 20, weight: .medium))
                                        .frame(width: 28, height: 44)
                                }
                                .buttonStyle(.borderless)
                                .accessibilityLabel(selectedIDs.contains(profile.id) ? "取消选择 \(profile.displayName)" : "选择 \(profile.displayName)")
                                .transition(.move(edge: .leading).combined(with: .opacity))
                            }
                            Image(systemName: profile.deviceKind)
                                .font(.title3).frame(width: 26)
                            Button {
                                if editMode.isEditing {
                                    toggleSelection(profile.id)
                                } else {
                                    hosts.select(profile)
                                    dismiss()
                                }
                            } label: {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(profile.displayName).foregroundStyle(Color(uiColor: .label)).lineLimit(1)
                                    Text(hosts.activeID == profile.id ? "当前主机" : "点击连接")
                                        .font(.caption)
                                        .foregroundStyle(
                                            hosts.activeID == profile.id
                                                ? Color.accentColor
                                                : Color(uiColor: .secondaryLabel)
                                        )
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                            }
                            .buttonStyle(.plain)
                            Circle().fill(hosts.onlineIDs.contains(profile.id) ? Color.green : Color.gray.opacity(0.45))
                                .frame(width: 8, height: 8)
                                .accessibilityLabel(hosts.onlineIDs.contains(profile.id) ? "在线" : "未连接或不可用")
                            if editMode.isEditing {
                                Button {
                                    alias = profile.alias ?? ""
                                    kind = profile.deviceKind
                                    editing = profile
                                } label: {
                                    Image(systemName: "pencil")
                                        .font(.system(size: 18, weight: .medium))
                                        .frame(width: 44, height: 44)
                                        .contentShape(Rectangle())
                                }
                                .buttonStyle(.borderless)
                                .accessibilityLabel("编辑 \(profile.displayName)")
                                .transition(.move(edge: .trailing).combined(with: .opacity))
                            }
                        }
                        .padding(.vertical, 2)
                        .animation(.easeInOut(duration: 0.2), value: editMode.isEditing)
                    }
                } header: { Text("我的主机").foregroundStyle(Color(uiColor: .secondaryLabel)) }
                if hosts.profiles.isEmpty {
                    Text("暂无已连接的主机")
                        .foregroundStyle(Color(uiColor: .secondaryLabel))
                }
            }
            .environment(\.editMode, $editMode)
            .alert(selectedIDs.count == 1 ? "删除主机？" : "批量删除主机？", isPresented: $confirmingDelete) {
                Button("取消", role: .cancel) {}
                Button("删除", role: .destructive) {
                    hosts.remove(ids: selectedIDs)
                    selectedIDs.removeAll()
                }
            } message: {
                Text("从 App 移除选中的 \(selectedIDs.count) 台主机及连接凭证；网关上的会话和工作区不会被删除。再次连接需重新配对。")
            }
            .navigationTitle("切换主机")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(editMode.isEditing ? "完成编辑" : "编辑") {
                        withAnimation {
                            if editMode.isEditing { selectedIDs.removeAll() }
                            editMode = editMode.isEditing ? .inactive : .active
                        }
                    }
                }
                if editMode.isEditing, !selectedIDs.isEmpty {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button(selectedIDs.count == 1 ? "删除" : "批量删除", role: .destructive) {
                            confirmingDelete = true
                        }
                        .foregroundStyle(.red)
                        .transition(.move(edge: .trailing).combined(with: .opacity))
                    }
                }
            }
            .task(id: scenePhase) {
                guard scenePhase == .active else { hosts.stopProbes(); return }
                while !Task.isCancelled {
                    await hosts.refreshPresence()
                    do { try await Task.sleep(for: .seconds(30)) } catch { return }
                }
            }
            .onDisappear { hosts.stopProbes() }
            .sheet(item: $editing) { profile in
                NavigationStack {
                    Form {
                        Section {
                            TextField(profile.gatewayName, text: $alias)
                            HStack {
                                Text("主机类型")
                                Spacer()
                                Menu {
                                    Picker("主机类型", selection: $kind) {
                                        Label("电脑", systemImage: "desktopcomputer").tag("desktopcomputer")
                                        Label("服务器", systemImage: "server.rack").tag("server.rack")
                                    }
                                } label: {
                                    HStack(alignment: .center, spacing: 4) {
                                        Image(systemName: kind)
                                            .font(.system(size: 15))
                                            .frame(width: 20, height: 20)
                                        Text(kind == "server.rack" ? "服务器" : "电脑")
                                            .font(.body)
                                        Image(systemName: "chevron.up.chevron.down")
                                            .font(.system(size: 11, weight: .medium))
                                    }
                                }
                                .accessibilityLabel("主机类型")
                                .accessibilityValue(kind == "server.rack" ? "服务器" : "电脑")
                            }
                        } header: {
                            Text("留空使用网关提供的名称：\(profile.gatewayName)")
                                .font(.caption)
                                .foregroundStyle(Color(uiColor: .secondaryLabel))
                                .textCase(nil)
                        }
                    }
                    .navigationTitle("编辑主机")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) { Button("取消") { editing = nil } }
                        ToolbarItem(placement: .confirmationAction) {
                            Button("保存") { hosts.edit(profile, alias: alias, kind: kind); editing = nil }
                        }
                    }
                }
                .foregroundStyle(Color(uiColor: .label))
                .tint(Color(uiColor: .label))
                .presentationDetents([.medium])
            }
        }
        .foregroundStyle(Color(uiColor: .label))
        .tint(Color(uiColor: .label))
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    private func toggleSelection(_ id: String) {
        if selectedIDs.contains(id) {
            selectedIDs.remove(id)
        } else {
            selectedIDs.insert(id)
        }
    }
}
