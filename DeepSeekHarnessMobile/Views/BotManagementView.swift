import SwiftUI
import PhotosUI
import DeepSeekHarnessShared

struct BotRosterView: View {
    @ObservedObject var model: BotModeModel
    @Environment(\.dismiss) private var dismiss
    @State private var creating = false
    @State private var role: BotRoleSelection?

    private var roster: [BotChat] { model.snapshot.roster }

    var body: some View {
        BotManagementPage(title: LocalizedStringKey("我的 Bot"), back: { dismiss() }, iconBack: true) {
            Button { creating = true } label: {
                Label("新建 Bot", systemImage: "plus").appFont(.system(size: 13, weight: .medium))
                    .padding(.horizontal, 14).frame(minHeight: 40).botManagementPill()
            }.buttonStyle(.plain).disabled(!model.canCreateBot)
                .opacity(model.canCreateBot ? 1 : 0.45).accessibilityIdentifier("bot.new")
        } content: {
            HStack {
                BotManagementCaption(LocalizedStringKey("团队成员"))
                Spacer()
                Text(String(format: String(localized: "%lld 个 Bot"), roster.count))
                    .appFont(.system(size: 13)).foregroundStyle(BotManagementStyle.secondary)
            }
            if !model.canCreateBot {
                let status: LocalizedStringKey = !model.snapshot.connected ? LocalizedStringKey("连接主机后可以创建 Bot") :
                    (model.snapshot.writable ? LocalizedStringKey("请更新主机上的移动网关和 DS Bot 后再创建 Bot") : LocalizedStringKey("当前主机为只读"))
                BotManagementCaption(status)
            }
            if roster.isEmpty {
                if model.snapshot.loading { ProgressView().frame(maxWidth: .infinity) }
                else { Text("还没有 Bot").frame(maxWidth: .infinity).padding(16).botManagementCard() }
            } else {
                VStack(spacing: 0) {
                    ForEach(Array(roster.enumerated()), id: \.element.id) { index, bot in
                        Button { role = BotRoleSelection(id: bot.id) } label: {
                            HStack(spacing: 12) {
                                BotAvatar(chat: bot, size: 44, main: bot.id == model.snapshot.mainBotId)
                                    .background(Color("BotLavenderSoft"), in: RoundedRectangle(cornerRadius: 14))
                                VStack(alignment: .leading, spacing: 3) {
                                    HStack(spacing: 8) {
                                        Text(bot.name).appFont(.system(size: 17, weight: .semibold))
                                        if bot.id == model.snapshot.mainBotId {
                                            Text("主 Bot").appFont(.system(size: 10, weight: .medium))
                                                .padding(.horizontal, 7).padding(.vertical, 3)
                                                .foregroundStyle(BotManagementStyle.accent)
                                                .background(Color("BotLavenderSoft"), in: RoundedRectangle(cornerRadius: 6))
                                        }
                                    }
                                    if !bot.role.isEmpty { Text(bot.role).appFont(.system(size: 12)).foregroundStyle(BotManagementStyle.secondary) }
                                }
                                Spacer(minLength: 8)
                                Image(systemName: "chevron.right").font(.system(size: 12, weight: .semibold))
                            }.padding(.horizontal, 16).padding(.vertical, 12).frame(minHeight: 72).contentShape(Rectangle())
                        }.buttonStyle(.plain).accessibilityIdentifier("bot.roster." + bot.id)
                        if index + 1 < roster.count { BotManagementStyle.divider.opacity(0.45) }
                    }
                }.botManagementCard()
            }
        } footer: { EmptyView() }
        .navigationDestination(isPresented: $creating) { BotCreationView(model: model) }
        .sheet(item: $role) { selected in BotRoleSheet(model: model, id: selected.id) }
    }
}

private extension BotModeModel {
    var canCreateBot: Bool { snapshot.canCreateBot }
}

private enum BotManagementStyle {
    static let canvas = Color(uiColor: UIColor { traits in
        traits.userInterfaceStyle == .dark ? UIColor(red: 27/255, green: 25/255, blue: 36/255, alpha: 1) :
            UIColor(red: 245/255, green: 243/255, blue: 249/255, alpha: 1)
    })
    static let card = Color(uiColor: UIColor { traits in
        traits.userInterfaceStyle == .dark ? UIColor(red: 41/255, green: 39/255, blue: 51/255, alpha: 1) : .white
    })
    static let secondary = Color(uiColor: UIColor { traits in
        traits.userInterfaceStyle == .dark ? UIColor(red: 216/255, green: 210/255, blue: 225/255, alpha: 1) :
            UIColor(red: 116/255, green: 114/255, blue: 126/255, alpha: 1)
    })
    static let rim = Color(uiColor: UIColor { traits in
        traits.userInterfaceStyle == .dark ? UIColor(red: 119/255, green: 113/255, blue: 132/255, alpha: 1) : .clear
    })
    static let action = Color(red: 108/255, green: 80/255, blue: 167/255)
    static let accent = Color("BotPurple")
    static var divider: some View { Rectangle().fill(Color("BotLine")).frame(height: 1).padding(.horizontal, 16) }

    static func hex(_ hex: String) -> Color {
        guard let number = UInt32(hex.dropFirst(), radix: 16) else { return .clear }
        return Color(red: Double(number >> 16 & 255)/255, green: Double(number >> 8 & 255)/255, blue: Double(number & 255)/255)
    }
}

private extension View {
    func botManagementPill() -> some View {
        background(BotManagementStyle.card, in: Capsule())
            .overlay { Capsule().strokeBorder(BotManagementStyle.rim, lineWidth: 0.75).allowsHitTesting(false) }
    }

    func botManagementCard() -> some View {
        frame(maxWidth: .infinity).background(BotManagementStyle.card, in: RoundedRectangle(cornerRadius: 20))
            .overlay { RoundedRectangle(cornerRadius: 20).strokeBorder(BotManagementStyle.rim, lineWidth: 1).allowsHitTesting(false) }
    }
}

private struct BotManagementCaption: View {
    let title: LocalizedStringKey
    init(_ title: LocalizedStringKey) { self.title = title }
    var body: some View {
        Text(title).appFont(.system(size: 13)).foregroundStyle(BotManagementStyle.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct BotManagementPage<Action: View, Content: View, Footer: View>: View {
    let title: LocalizedStringKey
    let back: () -> Void
    var iconBack = false
    var backDisabled = false
    @ViewBuilder let action: () -> Action
    @ViewBuilder let content: () -> Content
    @ViewBuilder let footer: () -> Footer

    var body: some View {
        VStack(spacing: 0) {
            ViewThatFits(in: .horizontal) {
                ZStack {
                    Text(title).appFont(.system(size: 17, weight: .semibold))
                        .padding(.horizontal, 106).fixedSize(horizontal: true, vertical: false)
                    HStack { backButton; Spacer(); action() }
                }
                HStack {
                    backButton
                    Text(title).appFont(.system(size: 17, weight: .semibold))
                    Spacer(minLength: 4)
                    action()
                }
            }.frame(minHeight: 60).padding(.horizontal, 24)
            ScrollView {
                VStack(alignment: .leading, spacing: 16, content: content)
                    .padding(.horizontal, 24).padding(.top, 8).padding(.bottom, 16)
                    .frame(maxWidth: 560)
                    .frame(maxWidth: .infinity)
            }.scrollDismissesKeyboard(.interactively)
            footer().padding(.horizontal, 24).frame(maxWidth: 560)
        }
        .foregroundStyle(Color("BotInk"))
        .background(BotManagementStyle.canvas.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
    }

    private var backButton: some View {
        Button(action: back) {
            if iconBack {
                Image(systemName: "chevron.left").font(.system(size: 15, weight: .semibold))
                    .frame(width: 44, height: 44).botManagementPill()
                    .shadow(color: .black.opacity(0.09), radius: 4, y: 2)
            } else {
                Text("取消").appFont(.system(size: 13, weight: .medium))
                    .padding(.horizontal, 14).frame(minHeight: 40).botManagementPill()
            }
        }.buttonStyle(.plain).disabled(backDisabled)
            .accessibilityLabel(iconBack ? Text("返回") : Text("取消"))
    }
}

private struct BotManagementAction: View {
    let title: LocalizedStringKey
    var enabled = true
    var busy = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Group {
                if busy { ProgressView().tint(.white).accessibilityLabel(Text(title)) }
                else { Text(title).appFont(.system(size: 16, weight: .semibold)) }
            }.foregroundStyle(.white).padding(.horizontal, 14).padding(.vertical, 10)
                .frame(maxWidth: .infinity, minHeight: 50)
                .background(BotManagementStyle.action.opacity(enabled || busy ? 1 : 0.45), in: Capsule())
        }.buttonStyle(.plain).disabled(!enabled || busy)
    }
}

private struct BotManagementRow: View {
    let title: String
    var value: String = ""
    var symbol: String? = nil
    var selected = false
    var chevron = true
    var enabled = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                if let symbol { Image(systemName: symbol).foregroundStyle(BotManagementStyle.accent) }
                Text(title).appFont(.system(size: 15, weight: symbol == nil ? .regular : .semibold))
                Spacer(minLength: 4)
                if !value.isEmpty {
                    Text(value).appFont(.system(size: 13)).foregroundStyle(BotManagementStyle.secondary)
                        .lineLimit(2).multilineTextAlignment(.trailing)
                }
                if selected {
                    Image(systemName: "checkmark.circle.fill").foregroundStyle(BotManagementStyle.accent)
                } else if chevron {
                    Image(systemName: "chevron.right").font(.system(size: 12, weight: .semibold)).foregroundStyle(BotManagementStyle.secondary)
                }
            }.padding(.horizontal, 16).padding(.vertical, 10).frame(minHeight: 52).contentShape(Rectangle())
        }.buttonStyle(.plain).disabled(!enabled).opacity(enabled ? 1 : 0.56)
            .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

private enum BotCreationDestination: String, Identifiable {
    case appearance, model
    var id: String { rawValue }
}

private struct BotCreationView: View {
    @ObservedObject var model: BotModeModel
    @Environment(\.dismiss) private var dismiss
    @State private var look: BotCreationLook?
    @State private var photo: String?
    @State private var customLook = false
    @State private var name = ""
    @State private var nameEdited = false
    @State private var local = false
    @State private var agentID: String?
    @State private var chosenCloudModel: String?
    @State private var chosenLocalModel: String?
    @State private var effort: String?
    @State private var destination: BotCreationDestination?

    private var state: BotCreationState { model.snapshot.creation }
    private var agent: BotCreationAgent? { state.agents.first { $0.id == agentID && $0.available } }
    private var cloudModel: String? { chosenCloudModel ?? model.snapshot.defaultCreationModel }
    private var localModel: String? { chosenLocalModel ?? agent.map { $0.id + "/default" } }
    private var suggestedName: String { BotCreationRules.shared.freshName(base: String(localized: "新 Bot"), roster: model.snapshot.chats) }
    private var finalName: String { name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? suggestedName : name }
    private var problem: String? { BotCreationRules.shared.nameProblem(name: finalName, roster: model.snapshot.chats) }
    private var canSubmit: Bool { model.canCreateBot && problem == nil && look != nil && (!local || agent != nil) && state.error != "uncertain" }
    private var models: [BotRoleOption] { local ? state.agentModels : model.snapshot.creationModels }
    private var modelTitle: String {
        let ref = local ? localModel : cloudModel
        return models.first { $0.id == ref }?.title ?? String(localized: local ? "CLI 默认" : "系统默认")
    }

    var body: some View {
        BotManagementPage(title: LocalizedStringKey("新建 Bot"), back: { dismiss() }, backDisabled: state.saving) { EmptyView() } content: {
            appearanceRow
            nameField
            VStack(alignment: .leading, spacing: 8) {
                BotManagementCaption(LocalizedStringKey("运行方式"))
                Picker(LocalizedStringKey("运行方式"), selection: $local) {
                    Text("DS Bot 模型").tag(false)
                    Text("本地 Agent").tag(true)
                }.pickerStyle(.segmented).accessibilityIdentifier("bot.create.mode")
            }
            if local { agentSection }
            if !local || agent != nil { modelSection }
            if let error = state.error {
                Text(error == "uncertain" ? "暂时无法确认创建结果，请返回列表查看。" : "创建 Bot 失败，请检查主机连接和设置后重试。")
                    .appFont(.system(size: 13)).foregroundStyle(.red)
            }
            if !model.canCreateBot { BotManagementCaption(LocalizedStringKey("连接主机后可以创建 Bot")) }
        } footer: {
            BotManagementAction(title: LocalizedStringKey("创建 Bot"), enabled: canSubmit, busy: state.saving) {
                guard let look else { return }
                model.createBot(name: finalName, look: look, image: photo,
                    model: local ? localModel : cloudModel, effort: local ? nil : effort, agentAppearance: local && !customLook)
            }.accessibilityIdentifier("bot.create.submit")
        }
        .disabled(state.saving)
        .navigationDestination(isPresented: Binding(get: { destination != nil }, set: { if !$0 { destination = nil } })) {
            if destination == .appearance, let look {
                BotAppearanceView(model: model, initialLook: look, initialPhoto: photo) { selected, image in
                    self.look = selected
                    photo = image
                    customLook = true
                }
            } else if destination == .model {
                BotModelPickerView(model: model, local: local, options: models,
                    selected: local ? localModel : cloudModel, effort: effort, pick: pickModel, pickEffort: { effort = $0 })
            }
        }
        .task { model.openCreation(); initializeLook() }
        .onChange(of: model.canCreateBot) { available in
            if available && state.appearance == nil && !state.appearanceFailed { model.openCreation() }
        }
        .onChange(of: state.appearance != nil) { _ in initializeLook() }
        .onChange(of: local) { active in if active { model.loadCreationAgents() } }
        .onChange(of: state.createdId) { id in if id != nil { dismiss() } }
    }

    private func initializeLook() {
        if look == nil { look = state.appearance?.randomLook(previous: nil) }
    }

    private var appearanceRow: some View {
        Button {
            if state.appearanceFailed { model.openCreation() }
            else { destination = .appearance }
        } label: {
            HStack(spacing: 12) {
                BotCreationFace(image: photo ?? (local && !customLook ? agent?.image : nil) ??
                    look.flatMap { state.appearance?.image(shape: $0.shape, color: $0.color) }, size: 52)
                VStack(alignment: .leading, spacing: 4) {
                    Text("形象").appFont(.system(size: 15, weight: .medium))
                    if local, let agent, !customLook {
                        Text(String(format: String(localized: "使用 %@ 形象"), agent.title)).appFont(.system(size: 12)).foregroundStyle(BotManagementStyle.secondary)
                    } else { BotManagementCaption(customLook ? LocalizedStringKey("使用自定义形象") : LocalizedStringKey("使用自动分配的形象")) }
                }
                Spacer(minLength: 4)
                if state.loadingAppearance { ProgressView() }
                else {
                    Text(state.appearanceFailed ? "重试" : "编辑").appFont(.system(size: 13, weight: .semibold))
                        .foregroundStyle(BotManagementStyle.accent)
                    Image(systemName: "chevron.right").font(.system(size: 12, weight: .semibold))
                }
            }.padding(16).frame(minHeight: 84).contentShape(Rectangle()).botManagementCard()
        }.buttonStyle(.plain).disabled(!model.canCreateBot || (look == nil && !state.appearanceFailed))
            .accessibilityIdentifier("bot.create.appearance")
    }

    private var nameField: some View {
        VStack(alignment: .leading, spacing: 8) {
            BotManagementCaption(LocalizedStringKey("名字"))
            TextField(
                suggestedName,
                text: $name,
                prompt: Text(suggestedName).foregroundColor(BotManagementStyle.secondary)
            )
                .appFont(.system(size: 16)).padding(.horizontal, 16).padding(.vertical, 10)
                .frame(minHeight: 52)
                .background(BotManagementStyle.card, in: RoundedRectangle(cornerRadius: 16))
                .overlay { RoundedRectangle(cornerRadius: 16).strokeBorder(Color("BotLine"), lineWidth: 1) }
                .onChange(of: name) { _ in nameEdited = true }
                .accessibilityLabel("名字").accessibilityIdentifier("bot.create.name")
            if nameEdited, let problem {
                Text(nameHelp(problem)).appFont(.system(size: 13)).foregroundStyle(.red)
            } else { BotManagementCaption(LocalizedStringKey("留空会使用自动生成的名字。")) }
        }
    }

    private func nameHelp(_ problem: String) -> LocalizedStringKey {
        switch problem {
        case "duplicate": LocalizedStringKey("已经有一个同名 Bot。")
        case "length": LocalizedStringKey("名字最多 40 个字符。")
        case "reserved": LocalizedStringKey("这个名字在群聊中有保留用途。")
        default: LocalizedStringKey("名字不能包含 @、反引号或控制字符。")
        }
    }

    @ViewBuilder private var agentSection: some View {
        VStack(spacing: 8) {
            BotManagementCaption(LocalizedStringKey("本地 Agent"))
            if state.loadingAgents && state.agents.isEmpty {
                ProgressView().frame(maxWidth: .infinity).padding(14).botManagementCard()
            } else if state.agentsFailed || state.agents.isEmpty {
                BotManagementRow(title: String(localized: state.agentsFailed ? "无法读取本地 Agent" : "未发现本地 Agent"),
                    value: String(localized: "重试")) { model.loadCreationAgents() }.botManagementCard()
            } else {
                VStack(spacing: 0) {
                    ForEach(Array(state.agents.enumerated()), id: \.element.id) { index, item in
                        BotManagementRow(title: item.title, value: item.signedIn?.boolValue == true ? String(localized: "已登录") :
                                String(localized: item.available ? "可用" : "未登录"),
                            symbol: "terminal", selected: item.id == agentID, chevron: false, enabled: item.available) {
                            agentID = item.id
                            chosenLocalModel = nil
                            model.loadCreationAgentModels(item.id)
                        }.accessibilityIdentifier("bot.create.agent." + item.id)
                        if index + 1 < state.agents.count { BotManagementStyle.divider }
                    }
                }.botManagementCard()
            }
            if agent == nil { BotManagementCaption(LocalizedStringKey("请选择主机上可用的 Agent。")) }
        }
    }

    private var modelSection: some View {
        VStack(spacing: 8) {
            BotManagementCaption(LocalizedStringKey("模型"))
            BotManagementRow(title: local ? String(localized: "模型") : String(localized: "所选模型"), value: modelTitle) {
                if !local, let cloudModel { model.loadRoleEfforts(cloudModel) }
                destination = .model
            }.botManagementCard().accessibilityIdentifier("bot.create.model")
            if local, let agent {
                Text(String(format: String(localized: "沿用 %@ 的默认模型。"), agent.title))
                    .appFont(.system(size: 13)).foregroundStyle(BotManagementStyle.secondary).frame(maxWidth: .infinity, alignment: .leading)
            } else { BotManagementCaption(LocalizedStringKey("沿用主机预选模型，可在 Bot 详情中修改。")) }
        }
    }

    private func pickModel(_ ref: String?) {
        if local { chosenLocalModel = ref }
        else {
            if ref != cloudModel { effort = nil }
            chosenCloudModel = ref
            if let ref { model.loadRoleEfforts(ref) }
        }
    }
}

private struct BotModelPickerView: View {
    @ObservedObject var model: BotModeModel
    @Environment(\.dismiss) private var dismiss
    let local: Bool
    let options: [BotRoleOption]
    let selected: String?
    var localDefault: String? { model.snapshot.creation.agentRoute.map { $0 + "/default" } }
    let effort: String?
    let pick: (String?) -> Void
    let pickEffort: (String?) -> Void

    var body: some View {
        BotManagementPage(title: LocalizedStringKey("选择模型"), back: { dismiss() }) { EmptyView() } content: {
            BotManagementCaption(LocalizedStringKey("可用模型"))
            VStack(spacing: 0) {
                let listed = options.filter { !local || !$0.id.hasSuffix("/default") }
                if local {
                    modelRow(String(localized: "CLI 默认"), id: localDefault)
                }
                ForEach(Array(listed.enumerated()), id: \.element.id) { index, option in
                    if index > 0 || local { BotManagementStyle.divider }
                    modelRow(option.title, id: option.id)
                }
                if local && model.snapshot.creation.loadingAgentModels {
                    ProgressView().frame(maxWidth: .infinity).padding(10)
                }
                if !local && options.isEmpty { BotManagementCaption(LocalizedStringKey("主机暂无可选模型")).padding(16) }
            }.botManagementCard()
            if !local {
                BotManagementCaption(LocalizedStringKey("思考强度"))
                let options = model.snapshot.effortModel == selected ? model.snapshot.efforts : []
                Menu {
                    Button("系统默认") { pickEffort(nil); dismiss() }
                    ForEach(options, id: \.id) { option in Button(option.title) { pickEffort(option.id); dismiss() } }
                } label: {
                    HStack {
                        Text("思考强度").appFont(.system(size: 15))
                        Spacer()
                        Text(options.first { $0.id == effort }?.title ?? String(localized: "系统默认"))
                            .appFont(.system(size: 13)).foregroundStyle(BotManagementStyle.secondary)
                        Image(systemName: "chevron.right").font(.system(size: 12, weight: .semibold)).foregroundStyle(BotManagementStyle.secondary)
                    }.padding(.horizontal, 16).frame(minHeight: 52).contentShape(Rectangle())
                }.buttonStyle(.plain).botManagementCard().disabled(options.isEmpty)
                BotManagementCaption(LocalizedStringKey("主机会提供模型和它所支持的思考强度。"))
            }
        } footer: { EmptyView() }
    }

    private func modelRow(_ title: String, id: String?) -> some View {
        BotManagementRow(title: title, value: String(localized: selected == id ? "已选" : "选择")) {
            pick(id)
            if local { dismiss() }
        }
    }
}

private struct BotCreationFace: View {
    var image: String?
    var size: CGFloat
    var tile = true

    var body: some View {
        ZStack {
            if tile { RoundedRectangle(cornerRadius: size * 0.28).fill(Color("BotLavenderSoft")) }
            if let image {
                BotFace(chat: BotChat(id: "creation-preview", name: "", room: false, role: "", preview: "",
                    image: image, lastAt: 0, running: false, part: 0, imageRef: nil, memberIds: [], sessionId: nil, profile: nil), size: size)
            }
        }.frame(width: size, height: size).accessibilityHidden(true)
    }
}

private struct BotAppearanceView: View {
    @ObservedObject var model: BotModeModel
    @Environment(\.dismiss) private var dismiss
    @State private var look: BotCreationLook
    @State private var photo: String?
    @State private var photoPicker: PhotosPickerItem?
    @State private var library = false
    @State private var processing = false
    @State private var photoError = false
    let commit: (BotCreationLook, String?) -> Void

    init(model: BotModeModel, initialLook: BotCreationLook, initialPhoto: String?, commit: @escaping (BotCreationLook, String?) -> Void) {
        self.model = model
        _look = State(initialValue: initialLook)
        _photo = State(initialValue: initialPhoto)
        self.commit = commit
    }

    private var catalog: BotCreationAppearance? { model.snapshot.creation.appearance }

    var body: some View {
        BotManagementPage(title: LocalizedStringKey("编辑形象"), back: { dismiss() }) { EmptyView() } content: {
            BotCreationFace(image: photo ?? catalog?.image(shape: look.shape, color: look.color), size: 72, tile: false)
                .frame(minHeight: 84).botManagementCard()
            if let catalog {
                VStack(alignment: .leading, spacing: 8) {
                    BotManagementCaption(LocalizedStringKey("形状"))
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 5), spacing: 8) {
                        ForEach(catalog.shapes, id: \.id) { shape in
                            let selected = photo == nil && look.shape == shape.id
                            Button { look = BotCreationLook(shape: shape.id, color: look.color); photo = nil } label: {
                                BotCreationFace(image: shape.images[look.color], size: 40, tile: false)
                                    .frame(maxWidth: .infinity, minHeight: 52)
                                    .background(selected ? Color("BotLavenderSoft") : BotManagementStyle.card,
                                        in: RoundedRectangle(cornerRadius: 14))
                                    .overlay { RoundedRectangle(cornerRadius: 14).strokeBorder(selected ? BotManagementStyle.accent : .clear, lineWidth: 1.5) }
                            }.buttonStyle(.plain).accessibilityLabel(shape.id).accessibilityAddTraits(selected ? .isSelected : [])
                        }
                    }
                }
                VStack(alignment: .leading, spacing: 4) {
                    BotManagementCaption(LocalizedStringKey("颜色"))
                    LazyVGrid(columns: Array(repeating: GridItem(.fixed(37), spacing: 10), count: 6), alignment: .leading, spacing: 2) {
                        ForEach(catalog.colors, id: \.id) { color in
                            let selected = photo == nil && look.color == color.id
                            Button { look = BotCreationLook(shape: look.shape, color: color.id); photo = nil } label: {
                                Circle().fill(LinearGradient(colors: color.stops.map(BotManagementStyle.hex), startPoint: .topLeading, endPoint: .bottomTrailing))
                                    .frame(width: 31, height: 31)
                                    .overlay { Circle().strokeBorder(selected ? BotManagementStyle.accent : .white, lineWidth: selected ? 2 : 0.75) }
                                    .frame(width: 37, height: 38).contentShape(Rectangle())
                            }.buttonStyle(.plain).accessibilityLabel(color.id).accessibilityAddTraits(selected ? .isSelected : [])
                        }
                    }
                }
            }
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 8) { randomAction; uploadAction; libraryAction }
                VStack(alignment: .leading, spacing: 8) { randomAction; uploadAction; libraryAction }
            }
            if photoError { BotManagementCaption(LocalizedStringKey("无法读取或处理所选照片。")) }
        } footer: {
            BotManagementAction(title: LocalizedStringKey("完成"), enabled: !processing) { commit(look, photo); dismiss() }
                .accessibilityIdentifier("bot.appearance.done")
        }
        .navigationDestination(isPresented: $library) {
            BotAvatarLibraryView(model: model) { photo = $0 }
        }
        .task(id: photoPicker) {
            guard let photoPicker else { return }
            processing = true
            photoError = false
            defer { if !Task.isCancelled { processing = false; self.photoPicker = nil } }
            do { if let image = try await botPhotoData(photoPicker), !Task.isCancelled { photo = image } }
            catch { if !Task.isCancelled { photoError = true } }
        }
    }

    private var randomAction: some View {
        Button {
            if let next = catalog?.randomLook(previous: look) { look = next; photo = nil }
        } label: { pill(LocalizedStringKey("随机"), symbol: "shuffle") }.buttonStyle(.plain)
    }

    private var uploadAction: some View {
        PhotosPicker(selection: $photoPicker, matching: .images) {
            pill(LocalizedStringKey("上传照片"), symbol: "photo")
        }.buttonStyle(.plain).disabled(processing)
    }

    private var libraryAction: some View {
        Button { library = true } label: { pill(LocalizedStringKey("头像库"), symbol: "archivebox") }.buttonStyle(.plain)
    }

    private func pill(_ text: LocalizedStringKey, symbol: String) -> some View {
        Label(text, systemImage: symbol).appFont(.system(size: 13, weight: .medium)).fixedSize()
            .padding(.horizontal, 13).frame(minHeight: 40).botManagementPill()
    }
}

private func botPhotoData(_ item: PhotosPickerItem) async throws -> String? {
    guard let data = try await item.loadTransferable(type: Data.self) else { throw GatewayImagePreprocessorError.cannotDecode }
    try Task.checkCancellation()
    return try await Task.detached(priority: .userInitiated) {
        let image = try GatewayImagePreprocessor.prepare(data: data, byteLimit: Int(BotCreationRules.shared.PHOTO_BYTES),
            pixelSideLimit: Int(BotCreationRules.shared.PHOTO_SIDE))
        return "data:\(image.mediaType);base64,\(image.data.base64EncodedString())"
    }.value
}

private struct BotAvatarLibraryView: View {
    @ObservedObject var model: BotModeModel
    @Environment(\.dismiss) private var dismiss
    @State private var picker: PhotosPickerItem?
    @State private var processing = false
    @State private var photoError = false
    let pick: (String) -> Void

    private var state: BotCreationState { model.snapshot.creation }

    var body: some View {
        BotManagementPage(title: LocalizedStringKey("头像库"), back: { dismiss() }) {
            PhotosPicker(selection: $picker, matching: .images) {
                Label("导入照片", systemImage: "plus").appFont(.system(size: 13, weight: .medium))
                    .padding(.horizontal, 14).frame(minHeight: 40).botManagementPill()
            }.buttonStyle(.plain).disabled(processing || state.loadingPhotos || !model.canCreateBot)
        } content: {
            if photoError { BotManagementCaption(LocalizedStringKey("无法读取或处理所选照片。")) }
            if state.photosFailed {
                BotManagementRow(title: String(localized: "头像库操作失败"), value: String(localized: "重试")) {
                    model.loadCreationPhotos(reset: true)
                }.botManagementCard()
            }
            if state.photos.isEmpty && !state.loadingPhotos && !state.photosFailed {
                BotManagementCaption(LocalizedStringKey("头像库还没有照片。")).padding(16).botManagementCard()
            }
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 72), spacing: 10)], spacing: 10) {
                ForEach(state.photos, id: \.id) { item in
                    Button { pick(item.image); dismiss() } label: {
                        BotCreationFace(image: item.image, size: 68).padding(6).botManagementCard()
                    }.buttonStyle(.plain).accessibilityLabel(item.name)
                }
            }
            if state.loadingPhotos || processing { ProgressView().frame(maxWidth: .infinity) }
            else if state.hasMorePhotos {
                BotManagementRow(title: String(localized: "加载更多"), chevron: false) { model.loadCreationPhotos(reset: false) }
                    .botManagementCard()
            }
        } footer: { EmptyView() }
        .task { model.loadCreationPhotos(reset: true) }
        .task(id: picker) {
            guard let picker else { return }
            processing = true
            photoError = false
            defer { if !Task.isCancelled { processing = false; self.picker = nil } }
            do { if let photo = try await botPhotoData(picker), !Task.isCancelled {
                model.importCreationPhoto(photo, name: String(localized: "照片"))
            } } catch { if !Task.isCancelled { photoError = true } }
        }
    }
}
