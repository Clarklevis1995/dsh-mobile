import SwiftUI
import MarkdownUI
import UIKit

struct ConversationView: View {
    @EnvironmentObject private var store: AppStore
    private let onBack: () -> Void
    @State private var activeView = 0
    @State private var draft = ""
    @State private var showsContextUsage = false
    @State private var showsSessionStats = false
    @State private var conversationScrollAnchor: String?
    @State private var userHasManuallyPositioned: Bool
    @State private var isUserDragging = false
    @State private var conversationScrollPosition: String?
    @State private var isRestoringManualScrollPosition: Bool
    @State private var composerHeight: CGFloat = 124
    @State private var scrollToBottomRequest = 0
    @FocusState private var composerIsFocused: Bool
    private let conversationBottomClearance: CGFloat = 22

    init(initialScrollAnchor: String? = nil, initiallyManual: Bool = false, onBack: @escaping () -> Void) {
        self.onBack = onBack
        _conversationScrollAnchor = State(initialValue: initialScrollAnchor)
        _userHasManuallyPositioned = State(initialValue: initiallyManual)
        _isRestoringManualScrollPosition = State(initialValue: initiallyManual)
    }

    var body: some View {
        ZStack {
            DSHColor.paper.ignoresSafeArea()
            VStack(spacing: 0) {
                topBar
                Picker("视图", selection: $activeView) {
                    Text("对话").tag(0)
                    Text("轨迹").tag(1)
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 66).padding(.vertical, 12)

                if activeView == 0 { chat }
                else { TrajectoryView(sessionId: store.selectedSessionId, events: store.selectedEvents) }
            }
        }
        .foregroundStyle(DSHColor.ink)
        .sheet(isPresented: $showsSessionStats) {
            SessionStatsSheet(
                snapshot: store.selectedSessionStatsSnapshot,
                sessionTitle: store.selectedSession?.title ?? "新建 DeepSeek Harness"
            )
        }
    }

    private var topBar: some View {
        HStack(spacing: 10) {
            Button(action: onBack) {
                Image(systemName: "chevron.left").frame(width: 38, height: 38).glassSurface(radius: 19)
            }
            .buttonStyle(.plain)
            Text(store.selectedSession.map(\.title) ?? "新建 DeepSeek Harness")
                .font(.system(size: 18, weight: .semibold))
                .lineLimit(1)
                .layoutPriority(1)
            Spacer()
            ConnectionDot(state: store.gateway.state)
            Text(topBarAgentPresetTitle)
                .font(.caption.weight(.medium))
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .fixedSize(horizontal: true, vertical: false)
            Menu {
                Button("重新加载历史", systemImage: "clock.arrow.circlepath") {
                    if let id = store.selectedSessionId { store.loadHistory(for: id) }
                }
                Button("发送 Ping", systemImage: "wave.3.right") { store.gateway.ping() }
            } label: {
                Image(systemName: "ellipsis").frame(width: 38, height: 38).glassSurface(radius: 19)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 16).padding(.top, 8)
    }

    private var chat: some View {
        ZStack(alignment: .bottom) {
            GeometryReader { geometry in
                ScrollViewReader { scrollProxy in
                    ZStack {
                        ScrollView(.vertical) {
                            LazyVStack(alignment: .leading, spacing: 0) {
                                if !conversationItems.isEmpty {
                                    if isLoadingSelectedHistory { historyLoadingBanner }
                                    if !isLoadingSelectedHistory,
                                       let id = store.selectedSessionId,
                                       store.historyHasMore[id] == true {
                                        Button("加载更早记录") { store.loadHistory(for: id, older: true) }
                                            .font(.caption).frame(maxWidth: .infinity)
                                    }
                                    ForEach(displayEntries) { entry in
                                        Group {
                                            switch entry.content {
                                            case .message(let item):
                                                ConversationRow(item: item, showsCopyButton: entry.showsCopyButton)
                                            case .process(let group):
                                                ConversationProcessRow(group: group)
                                            }
                                        }
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                        .id(entry.id)
                                    }
                                    if let snapshot = store.selectedSessionStatsSnapshot {
                                        sessionStatsBar(snapshot)
                                            .padding(.top, 10)
                                            .padding(.bottom, 4)
                                            .id(sessionStatsAnchorID)
                                    }
                                }
                                Color.clear
                                    .frame(height: composerHeight + conversationBottomClearance)
                                    .id(conversationBottomAnchorID)
                            }
                            .scrollTargetLayout()
                            .environment(\.conversationDisclosureWillToggle) {
                                // A non-nil scrollPosition with a bottom anchor makes
                                // SwiftUI compensate for an expanding row by moving the
                                // whole viewport upward. Release that programmatic anchor
                                // before the row changes height so it expands downward from
                                // its current on-screen position.
                                var transaction = Transaction()
                                transaction.disablesAnimations = true
                                withTransaction(transaction) {
                                    conversationScrollPosition = nil
                                }
                            }
                        }
                            .frame(width: max(0, geometry.size.width - 40), alignment: .leading)
                            .padding(.horizontal, 20)
                            .padding(.top, 8)
                        .coordinateSpace(name: "conversation-scroll")
                        .frame(width: geometry.size.width)
                        .clipped()
                        .opacity(isRestoringManualScrollPosition ? 0 : 1)
                        .defaultScrollAnchor(.bottom)
                        .scrollPosition(id: $conversationScrollPosition, anchor: .bottom)
                        .simultaneousGesture(
                            DragGesture(minimumDistance: 2)
                                .onChanged { _ in
                                    guard !isUserDragging else { return }
                                    isUserDragging = true
                                    userHasManuallyPositioned = true
                                }
                                .onEnded { _ in
                                    isUserDragging = false
                                    captureManualConversationAnchor()
                                }
                        )
                        .onChange(of: bottomEntryID) { _, _ in
                            guard !userHasManuallyPositioned, !isLoadingSelectedHistory else { return }
                            scrollWithoutAnimation(scrollProxy, to: conversationBottomAnchorID)
                        }
                        .onChange(of: scrollToBottomRequest) { _, _ in
                            scrollToConversationBottom(scrollProxy)
                        }
                        .onChange(of: isLoadingSelectedHistory) { wasLoading, isLoading in
                            guard wasLoading, !isLoading, !conversationItems.isEmpty else { return }
                            // History arrives progressively. Rebuilding the entire ScrollView here
                            // discards its actual offset and can restore a row behind the floating
                            // composer. Keep the existing scroll container alive. Only sessions that
                            // are still following the latest content are aligned with the dedicated
                            // bottom spacer; a user's manual position is left completely untouched.
                            guard !userHasManuallyPositioned else { return }
                            Task { @MainActor in
                                await Task.yield()
                                await Task.yield()
                                scrollWithoutAnimation(scrollProxy, to: conversationBottomAnchorID)
                            }
                        }
                        .task(id: store.selectedSessionId) {
                            guard let sessionId = store.selectedSessionId else {
                                conversationScrollAnchor = nil
                                conversationScrollPosition = nil
                                userHasManuallyPositioned = false
                                isRestoringManualScrollPosition = false
                                return
                            }
                            userHasManuallyPositioned = store.hasManualConversationPosition(for: sessionId)
                            conversationScrollAnchor = userHasManuallyPositioned
                                ? store.conversationScrollAnchor(for: sessionId)
                                : nil
                            conversationScrollPosition = conversationScrollAnchor ?? conversationBottomAnchorID
                            guard userHasManuallyPositioned else {
                                isRestoringManualScrollPosition = false
                                return
                            }
                            isRestoringManualScrollPosition = true
                            await Task.yield()
                            if let target = validRememberedConversationAnchor() {
                                // A remembered row represents the user's reading position. Center it
                                // in the unobscured viewport instead of aligning it with the physical
                                // bottom edge behind the floating composer.
                                scrollWithoutAnimation(scrollProxy, to: target, anchor: .center)
                            } else {
                                userHasManuallyPositioned = false
                                conversationScrollAnchor = nil
                            }
                            await Task.yield()
                            isRestoringManualScrollPosition = false
                        }

                        if conversationItems.isEmpty {
                            Group {
                                if isLoadingSelectedHistory {
                                    historyLoadingState
                                } else {
                                    emptyState
                                }
                            }
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                            .padding(.bottom, composerHeight)
                        }

                        if shouldShowScrollToBottom {
                            VStack {
                                Spacer()
                                scrollToBottomButton {
                                    scrollToBottomRequest &+= 1
                                }
                            }
                            .padding(.bottom, composerHeight + 8)
                            .transition(.scale(scale: 0.85).combined(with: .opacity))
                            .zIndex(2)
                        }
                    }
                    .animation(
                        .easeOut(duration: 0.16),
                        value: shouldShowScrollToBottom
                    )
                }
            }
            composer
                .background {
                    GeometryReader { composerGeometry in
                        Color.clear.preference(
                            key: ComposerHeightPreferenceKey.self,
                            value: composerGeometry.size.height
                        )
                    }
                }
        }
        .onPreferenceChange(ComposerHeightPreferenceKey.self) { height in
            guard height > 0, abs(height - composerHeight) > 0.5 else { return }
            composerHeight = height
        }
        .onChange(of: composerIsFocused) { _, isFocused in
            guard isFocused else { return }
            Task { @MainActor in
                await Task.yield()
                scrollToBottomRequest &+= 1
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 14) {
            DeepSeekWhaleIcon(size: 52).foregroundStyle(DSHColor.ocean)
            Text("操作远端 DSH Agent").font(.title3.weight(.semibold))
            Text("发送任务后，工具调用、推理进度和最终回复会通过 Mobile Gateway 实时返回。")
                .font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 28)
    }

    private var isLoadingSelectedHistory: Bool {
        guard let id = store.selectedSessionId else { return false }
        return store.historyLoadingSessionIds.contains(id)
    }

    private var bottomEntryID: String? { displayEntries.last?.id }
    private var conversationBottomAnchorID: String {
        "conversation-bottom-\(store.selectedSessionId ?? "new-session")"
    }
    private var sessionStatsAnchorID: String {
        "session-stats-\(store.selectedSessionId ?? "new-session")"
    }

    private func scrollWithoutAnimation(
        _ proxy: ScrollViewProxy,
        to target: String,
        anchor: UnitPoint = .bottom
    ) {
        var transaction = Transaction()
        transaction.animation = nil
        withTransaction(transaction) {
            proxy.scrollTo(target, anchor: anchor)
        }
    }

    private func scrollToConversationBottom(_ proxy: ScrollViewProxy) {
        userHasManuallyPositioned = false
        conversationScrollAnchor = nil
        if let sessionId = store.selectedSessionId {
            store.rememberConversationScrollAnchor(nil, for: sessionId, manual: false)
        }
        withAnimation(.easeOut(duration: 0.22)) {
            proxy.scrollTo(conversationBottomAnchorID, anchor: .bottom)
        }
    }

    private var shouldShowScrollToBottom: Bool {
        guard !conversationItems.isEmpty,
              let conversationScrollPosition else { return false }
        return conversationScrollPosition != conversationBottomAnchorID
    }

    private func validRememberedConversationAnchor() -> String? {
        guard let sessionId = store.selectedSessionId,
              let saved = store.conversationScrollAnchor(for: sessionId),
              displayEntries.contains(where: { $0.id == saved }) else {
            return nil
        }
        return saved
    }

    private func captureManualConversationAnchor() {
        guard let position = conversationScrollPosition,
              position != conversationBottomAnchorID else {
            userHasManuallyPositioned = false
            conversationScrollAnchor = nil
            if let sessionId = store.selectedSessionId {
                store.rememberConversationScrollAnchor(nil, for: sessionId, manual: false)
            }
            return
        }
        conversationScrollAnchor = position
        persistConversationAnchor(manual: true)
    }

    private func persistConversationAnchor(manual: Bool) {
        guard let sessionId = store.selectedSessionId,
              let conversationScrollAnchor else { return }
        store.rememberConversationScrollAnchor(conversationScrollAnchor, for: sessionId, manual: manual)
    }

    private var historyLoadingState: some View {
        VStack(spacing: 14) {
            ProgressView()
                .controlSize(.large)
                .tint(DSHColor.ocean)
            Text("正在加载历史记录").font(.title3.weight(.semibold))
            Text(historyProgressText)
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 28)
    }

    private var historyLoadingBanner: some View {
        HStack(spacing: 9) {
            ProgressView().tint(DSHColor.ocean)
            Text(historyProgressText)
                .font(.caption)
                .foregroundStyle(.secondary)
            Spacer()
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
    }

    private var historyProgressText: String {
        guard let id = store.selectedSessionId,
              let progress = store.historyLoadProgress[id] else {
            return "正在从 Mobile Gateway 同步会话内容…"
        }
        guard let total = progress.total else {
            return progress.loaded > 0
                ? "正在自动加载更早记录 · 已同步 \(progress.loaded) 个事件"
                : "正在从 Mobile Gateway 同步会话内容…"
        }
        return "正在加载历史记录 · \(progress.loaded)/\(total)"
    }

    @ViewBuilder
    private func scrollToBottomButton(action: @escaping () -> Void) -> some View {
        if #available(iOS 26.0, *) {
            Button(action: action) {
                Image(systemName: "arrow.down")
                    .font(.system(size: 15, weight: .semibold))
                    .frame(width: 36, height: 36)
            }
            .buttonStyle(.glass)
            .buttonBorderShape(.circle)
            .accessibilityLabel("滚动到最新消息")
        } else {
            Button(action: action) {
                Image(systemName: "arrow.down")
                    .font(.system(size: 15, weight: .semibold))
                    .frame(width: 36, height: 36)
                    .glassSurface(radius: 18)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("滚动到最新消息")
        }
    }

    private var composer: some View {
        VStack(alignment: .leading, spacing: 12) {
            TextField("描述你想要构建的内容", text: $draft, axis: .vertical)
                .lineLimit(1...5)
                .font(.body)
                .textFieldStyle(.plain)
                .focused($composerIsFocused)
                .padding(.horizontal, 3)
                .frame(minHeight: 38, alignment: .top)
            HStack(spacing: 7) {
                permissionControl

                Spacer(minLength: 2)

                modelControl

                Button { showsContextUsage = true } label: {
                    if contextIsLoading && store.selectedContextSnapshot == nil {
                        ProgressView().controlSize(.small).frame(width: 26, height: 26)
                    } else {
                        ContextUsageRing(progress: contextProgress)
                    }
                }
                .buttonStyle(.plain)
                .disabled(store.selectedContextSnapshot == nil)
                .accessibilityLabel("上下文已用 \(Int(contextProgress * 100))%")
                .popover(isPresented: $showsContextUsage, arrowEdge: .bottom) {
                    ContextUsagePopover(snapshot: store.selectedContextSnapshot)
                        .presentationCompactAdaptation(.popover)
                }

                Button {
                    let content = draft
                    draft = ""
                    store.send(content)
                } label: {
                    Image(systemName: "arrow.up")
                        .font(.system(size: 17, weight: .bold))
                        .foregroundStyle(.white)
                        .frame(width: 42, height: 42)
                        .background(DSHColor.ocean, in: Circle())
                }
                .buttonStyle(.plain)
                .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || store.waitingForNewSession)
                .opacity(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? 0.48 : 1)
            }
        }
        .padding(.horizontal, 14)
        .padding(.top, 14)
        .padding(.bottom, 12)
        .glassSurface(radius: 24, tint: DSHColor.paper.opacity(0.48))
        .overlay {
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .stroke(.white.opacity(0.56), lineWidth: 0.7)
        }
        .shadow(color: DSHColor.navy.opacity(0.11), radius: 18, y: 8)
        .padding(.horizontal, 14).padding(.bottom, 10)
    }

    private func sessionStatsBar(_ snapshot: GatewaySessionStatsSnapshot) -> some View {
        Button { showsSessionStats = true } label: {
            HStack(spacing: 7) {
                Image(systemName: "chart.bar.xaxis")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(DSHColor.ocean)
                Text(SessionStatsFormatter.compactLine(snapshot))
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .truncationMode(.tail)
                Spacer(minLength: 0)
                Image(systemName: "chevron.up")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
            .contentShape(Rectangle())
            .padding(.horizontal, 2)
            .padding(.vertical, 5)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("查看会话执行状态")
        .accessibilityValue(SessionStatsFormatter.compactLine(snapshot))
    }

    @ViewBuilder
    private var permissionControl: some View {
        if store.selectedSessionId == nil {
            HStack(spacing: 5) {
                if defaultConfigurationIsLoading && store.permissionDefault == nil {
                    ProgressView().controlSize(.mini)
                    Text("读取默认权限…")
                } else if let permission = store.permissionDefault {
                    Image(systemName: permissionIcon(permission))
                    Text(permissionTitle(permission))
                } else {
                    Image(systemName: "shield")
                    Text("默认权限")
                }
            }
            .font(.caption.weight(.medium))
            .foregroundStyle(.secondary)
            .lineLimit(1)
            .frame(width: 92, alignment: .leading)
            .accessibilityElement(children: .combine)
            .accessibilityLabel("新会话默认权限：\(store.permissionDefault.map(permissionTitle) ?? "未读取")")
        } else {
            permissionMenu
        }
    }

    @ViewBuilder
    private var modelControl: some View {
        if store.selectedSessionId == nil {
            HStack(spacing: 5) {
                if defaultModelIsLoading && store.defaultModelSelection == nil {
                    ProgressView().controlSize(.mini)
                    Text("读取默认模型…")
                } else {
                    Text(defaultModelTitle)
                        .lineLimit(1)
                        .minimumScaleFactor(0.72)
                        .allowsTightening(true)
                    if let effort = defaultModelEffortTitle {
                        Text(effort)
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(.primary)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 3)
                            .background(DSHColor.purple.opacity(0.14), in: Capsule())
                    }
                }
            }
            .font(.caption.weight(.medium))
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .trailing)
            .accessibilityElement(children: .combine)
            .accessibilityLabel("新会话默认模型：\(defaultModelTitle)\(defaultModelEffortTitle.map { "，推理等级 \($0)" } ?? "")")
        } else {
            modelMenu
        }
    }

    private var permissionMenu: some View {
        Menu {
            if permissionOptions.isEmpty {
                Text("正在读取权限…")
            } else {
                ForEach(permissionOptions) { option in
                    Button {
                        store.setPermission(option.value)
                    } label: {
                        Label(
                            permissionTitle(option.value),
                            systemImage: option.value == currentPermission ? "checkmark" : permissionIcon(option.value)
                        )
                    }
                }
            }
        } label: {
            HStack(spacing: 5) {
                if permissionIsLoading {
                    ProgressView().controlSize(.mini)
                } else {
                    Image(systemName: permissionIcon(currentPermission))
                }
                Text(permissionTitle(currentPermission)).lineLimit(1)
            }
            .font(.caption.weight(.medium))
            .foregroundStyle(.secondary)
            .frame(width: 78, alignment: .leading)
            .contentShape(Rectangle())
        }
        .disabled(permissionOptions.isEmpty)
    }

    private var modelMenu: some View {
        Menu {
            if modelGroups.isEmpty {
                Text("正在读取模型…")
            } else {
                Menu("模型") {
                    ForEach(modelGroups) { group in
                        Section(group.name) {
                            ForEach(group.models) { model in
                                Button {
                                    select(group: group, model: model)
                                } label: {
                                    Label(
                                        model.name,
                                        systemImage: isCurrent(group: group, model: model) ? "checkmark" : "circle"
                                    )
                                }
                            }
                        }
                    }
                }
                if !currentEfforts.isEmpty {
                    Menu("推理等级") {
                        ForEach(currentEfforts) { effort in
                            Button {
                                select(effort: effort)
                            } label: {
                                Label(
                                    effort.name,
                                    systemImage: effort.id == currentModelSelection?.reasoningEffort ? "checkmark" : "circle"
                                )
                            }
                        }
                    }
                }
            }
        } label: {
            HStack(spacing: 5) {
                if modelIsLoading {
                    ProgressView().controlSize(.mini)
                } else {
                    Text(currentModelTitle)
                        .lineLimit(1)
                        .minimumScaleFactor(0.72)
                        .allowsTightening(true)
                        .layoutPriority(1)
                    if let effort = currentEffortTitle {
                        Text(effort)
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(.primary)
                            .lineLimit(1)
                            .fixedSize(horizontal: true, vertical: false)
                            .layoutPriority(2)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 3)
                            .background(DSHColor.purple.opacity(0.16), in: Capsule())
                    }
                }
                Image(systemName: "chevron.up.chevron.down")
                    .font(.system(size: 8, weight: .semibold))
                    .foregroundStyle(.tertiary)
            }
            .font(.caption.weight(.medium))
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .trailing)
            .contentShape(Rectangle())
        }
        .disabled(modelGroups.isEmpty || store.selectedModelCatalog?.routable == false)
    }

    private var permissionOptions: [GatewayPermissionOption] { store.selectedPermissions?.options ?? [] }
    private var currentPermission: String {
        store.selectedPermissions?.currentValue ?? store.selectedPermissions?.preset ?? "workspace-write"
    }
    private var defaultConfigurationIsLoading: Bool {
        !store.defaultConfigurationLoadingKinds.isDisjoint(with: ["defaults", "agent-presets"])
    }
    private var defaultModelIsLoading: Bool {
        store.defaultConfigurationLoadingKinds.contains("default-model")
    }
    private var defaultModelTitle: String {
        guard let selection = store.defaultModelSelection else { return "默认模型" }
        return modelTitle(for: selection.model)
    }
    private var defaultModelEffortTitle: String? {
        store.defaultModelSelection?.reasoningEffort.map(reasoningEffortTitle)
    }
    private var defaultAgentPresetTitle: String {
        guard let id = store.agentPresetDefault else { return "默认 Agent" }
        return agentPresetTitle(id)
    }
    private var topBarAgentPresetTitle: String {
        let id = store.selectedSession?.agentPreset ?? store.agentPresetDefault
        guard let id else { return "Agent" }
        return agentPresetTitle(id)
    }
    private func agentPresetTitle(_ id: String) -> String {
        if let preset = store.agentPresets.first(where: { $0.id == id }) {
            return preset.displayName
        }
        switch id {
        case "standard": return "标准模式"
        case "code": return "PTC 模式"
        case "minimal": return "极简模式"
        case "cordis": return "创造模式"
        default: return id
        }
    }
    private var permissionIsLoading: Bool {
        store.sessionControlLoadingKinds.contains("permission-options") || store.sessionControlLoadingKinds.contains("permission")
    }
    private var modelIsLoading: Bool {
        store.sessionControlLoadingKinds.contains("models") || store.sessionControlLoadingKinds.contains("select-model")
    }
    private var contextIsLoading: Bool { store.sessionControlLoadingKinds.contains("context-usage") }
    private var modelGroups: [GatewayModelGroup] { store.selectedModelCatalog?.groups ?? [] }
    private var currentModelSelection: GatewayModelSelection? { store.selectedModelCatalog?.current }
    private var currentModelItem: GatewayModelItem? {
        guard let selection = currentModelSelection else { return nil }
        return modelGroups.first(where: { $0.id == selection.provider })?.models.first(where: { $0.id == selection.model })
    }
    private var currentModelTitle: String { currentModelItem?.name ?? currentModelSelection?.model ?? "DeepSeek Agent" }
    private var currentEfforts: [GatewayReasoningEffort] { currentModelItem?.reasoning?.efforts ?? [] }
    private var currentEffortTitle: String? {
        guard let id = currentModelSelection?.reasoningEffort else { return nil }
        return currentEfforts.first(where: { $0.id == id })?.name ?? id.capitalized
    }
    private func modelTitle(for id: String) -> String {
        switch id {
        case "deepseek-chat": return "DeepSeek Chat"
        case "deepseek-reasoner": return "DeepSeek Reasoner"
        default: return id
        }
    }
    private func reasoningEffortTitle(_ id: String) -> String {
        switch id.lowercased() {
        case "low": return "Low"
        case "medium": return "Medium"
        case "high": return "High"
        default: return id.capitalized
        }
    }
    private var contextProgress: Double {
        guard let pressure = store.selectedContextSnapshot?.pressure,
              let used = pressure.pressureTokens,
              let window = pressure.contextWindow,
              window > 0 else { return 0 }
        return min(1, max(0, Double(used) / Double(window)))
    }

    private func permissionTitle(_ value: String) -> String {
        switch value {
        case "ask": "每次询问"
        case "read-only": "只读"
        case "workspace-write": "工作区写入"
        case "danger-full-access": "完全访问"
        default: value
        }
    }
    private func permissionIcon(_ value: String) -> String {
        switch value {
        case "ask": "questionmark.shield"
        case "read-only": "checkmark.shield"
        case "workspace-write": "pencil.and.outline"
        case "danger-full-access": "exclamationmark.shield"
        default: "shield"
        }
    }
    private func isCurrent(group: GatewayModelGroup, model: GatewayModelItem) -> Bool {
        currentModelSelection?.provider == group.id && currentModelSelection?.model == model.id
    }
    private func select(group: GatewayModelGroup, model: GatewayModelItem) {
        let efforts = model.reasoning?.efforts ?? []
        let retainedEffort = currentModelSelection?.reasoningEffort.flatMap { current in
            efforts.contains(where: { $0.id == current }) ? current : nil
        }
        store.selectModel(
            provider: group.id,
            model: model.id,
            reasoningEffort: retainedEffort ?? model.reasoning?.defaultEffort
        )
    }
    private func select(effort: GatewayReasoningEffort) {
        guard let current = currentModelSelection else { return }
        store.selectModel(provider: current.provider, model: current.model, reasoningEffort: effort.id)
    }

    private var conversationItems: [ConversationItem] {
        store.selectedConversationItems
    }

    private var displayEntries: [ConversationDisplayEntry] {
        ConversationDisplayEntry.make(from: conversationItems)
    }
}

private struct ComposerHeightPreferenceKey: PreferenceKey {
    static var defaultValue: CGFloat = 124

    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = nextValue()
    }
}

private struct ContextUsageRing: View {
    let progress: Double

    var body: some View {
        ZStack {
            Circle()
                .stroke(Color.secondary.opacity(0.22), lineWidth: 3)
            Circle()
                .trim(from: 0, to: progress)
                .stroke(DSHColor.ocean, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                .rotationEffect(.degrees(-90))
        }
        .frame(width: 18, height: 18)
        .padding(2)
        .contentShape(Circle())
    }
}

private enum SessionStatsFormatter {
    static func compactLine(_ snapshot: GatewaySessionStatsSnapshot) -> String {
        let stats = snapshot.stats
        var sections: [String] = []
        if stats?.turns != nil || stats?.steps != nil {
            sections.append("\(stats?.turns ?? 0) 轮 · \(stats?.steps ?? 0) 步")
        }
        let timings = [
            stats?.llmMs.map { "LLM \(duration($0))" },
            stats?.toolMs.map { "工具调用 \(duration($0))" }
        ].compactMap { $0 }.joined(separator: " · ")
        if !timings.isEmpty { sections.append(timings) }
        let averageTTFT = averageTTFT(stats)
        let throughput = throughput(stats)
        let performance = [
            averageTTFT.map { "首 token 平均 \(duration($0))" },
            throughput.map { "\(compactDecimal($0)) tok/s" }
        ].compactMap { $0 }.joined(separator: " · ")
        if !performance.isEmpty { sections.append(performance) }
        if let cacheRate = cacheHitRate(snapshot.tokenUsage?.totals) {
            sections.append("缓存命中 \(Int((cacheRate * 100).rounded()))%")
        }
        if let input = snapshot.tokenUsage?.totals?.inputTokens {
            sections.append("输入 \(compact(input)) tok")
        }
        return sections.isEmpty ? "正在读取会话统计…" : sections.joined(separator: "  |  ")
    }

    static func duration(_ milliseconds: Double) -> String {
        let seconds = max(0, milliseconds) / 1_000
        if seconds >= 60 {
            let total = Int(seconds.rounded())
            return "\(total / 60)m\(total % 60)s"
        }
        if seconds >= 1 { return "\(compactDecimal(seconds))s" }
        return "\(Int(milliseconds.rounded()))ms"
    }

    static func averageTTFT(_ stats: GatewaySessionStats?) -> Double? {
        guard let total = stats?.ttftMs, let count = stats?.ttftSteps, count > 0 else { return nil }
        return total / Double(count)
    }

    static func throughput(_ stats: GatewaySessionStats?) -> Double? {
        guard let milliseconds = stats?.decodeMs, let tokens = stats?.decodeTokens, milliseconds > 0 else { return nil }
        return Double(tokens) / (milliseconds / 1_000)
    }

    static func cacheHitRate(_ totals: GatewaySessionTokenUsageTotals?) -> Double? {
        guard let totals else { return nil }
        let values = [totals.inputTokens, totals.outputTokens, totals.cacheReadTokens, totals.cacheWriteTokens]
            .compactMap { $0 }
        let total = values.reduce(0, +)
        guard total > 0, let cacheRead = totals.cacheReadTokens else { return nil }
        return min(1, max(0, Double(cacheRead) / Double(total)))
    }

    static func compact(_ value: Int) -> String {
        switch value {
        case 1_000_000...:
            return String(format: value.isMultiple(of: 1_000_000) ? "%.0fM" : "%.1fM", Double(value) / 1_000_000)
        case 1_000...:
            return String(format: "%.1fK", Double(value) / 1_000)
        default:
            return "\(value)"
        }
    }

    static func compactDecimal(_ value: Double) -> String {
        value.rounded() == value ? "\(Int(value))" : String(format: "%.1f", value)
    }
}

private struct SessionStatsSheet: View {
    let snapshot: GatewaySessionStatsSnapshot?
    let sessionTitle: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    if let snapshot, let stats = snapshot.stats {
                        metricsSection("执行") {
                            metric("轮次", "\(stats.turns ?? 0) 轮")
                            metric("步骤", "\(stats.steps ?? 0) 步")
                            metric("LLM", stats.llmMs.map(SessionStatsFormatter.duration) ?? "—")
                            metric("工具调用", stats.toolMs.map(SessionStatsFormatter.duration) ?? "—")
                            metric("首 token 平均", SessionStatsFormatter.averageTTFT(stats).map(SessionStatsFormatter.duration) ?? "—")
                            metric("解码吞吐", SessionStatsFormatter.throughput(stats).map { "\(SessionStatsFormatter.compactDecimal($0)) tok/s" } ?? "—")
                        }

                        if let totals = snapshot.tokenUsage?.totals {
                            metricsSection("Token 用量") {
                                metric("输入", token(totals.inputTokens))
                                metric("输出", token(totals.outputTokens))
                                metric("缓存读取", token(totals.cacheReadTokens))
                                metric("缓存写入", token(totals.cacheWriteTokens))
                                metric("推理", token(totals.reasoningTokens))
                                metric("缓存命中", SessionStatsFormatter.cacheHitRate(totals).map { "\(Int(($0 * 100).rounded()))%" } ?? "—")
                            }
                        }

                        if let pressure = snapshot.contextPressure {
                            metricsSection("上下文") {
                                metric("当前压力", token(pressure.pressureTokens))
                                metric("预计用量", token(pressure.projectedTokens))
                                metric("上下文窗口", token(pressure.contextWindow))
                            }
                        }

                        if let asOfSeq = snapshot.asOfSeq {
                            Text("数据截至事件 #\(asOfSeq)")
                                .font(.caption.monospacedDigit())
                                .foregroundStyle(.tertiary)
                        }
                    } else {
                        ContentUnavailableView("正在读取会话统计", systemImage: "chart.bar.xaxis", description: Text("统计会在本轮结束后自动更新。"))
                            .frame(maxWidth: .infinity, minHeight: 220)
                    }
                }
                .padding(20)
            }
            .navigationTitle("会话状态")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("完成") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationBackground(.regularMaterial)
    }

    @ViewBuilder
    private func metricsSection<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title).font(.headline)
            VStack(spacing: 0) { content() }
                .padding(.horizontal, 14)
                .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
    }

    private func metric(_ title: String, _ value: String) -> some View {
        HStack {
            Text(title).foregroundStyle(.secondary)
            Spacer()
            Text(value).font(.body.monospacedDigit()).fontWeight(.medium)
        }
        .padding(.vertical, 10)
    }

    private func token(_ value: Int?) -> String {
        value.map { "\(SessionStatsFormatter.compact($0)) tok" } ?? "—"
    }
}

private struct ContextUsagePopover: View {
    let snapshot: GatewayContextSnapshot?

    private var pressureTokens: Int { snapshot?.pressure?.pressureTokens ?? 0 }
    private var contextWindow: Int { snapshot?.pressure?.contextWindow ?? 0 }
    private var progress: Double {
        guard contextWindow > 0 else { return 0 }
        return min(1, max(0, Double(pressureTokens) / Double(contextWindow)))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .firstTextBaseline) {
                Text("上下文已用")
                    .foregroundStyle(.secondary)
                Text("\(Int((progress * 100).rounded()))%")
                    .fontWeight(.semibold)
                Spacer()
                Text("~\(compact(pressureTokens)) / \(compact(contextWindow))")
                    .font(.subheadline.monospacedDigit())
            }

            ProgressView(value: progress)
                .tint(DSHColor.ocean)

            if let breakdown = snapshot?.breakdown {
                usageRow("系统提示词", value: breakdown.systemTokens, color: .gray)
                usageRow("工具", value: breakdown.toolsTokens, color: DSHColor.purple)
                usageRow("对话消息", value: breakdown.messageTokens, color: DSHColor.ocean)
            } else if let usage = snapshot?.tokenUsage {
                usageRow("未缓存输入", value: usage.uncachedInputTokens, color: DSHColor.ocean)
                usageRow("缓存读取", value: usage.cacheReadTokens, color: DSHColor.purple)
                usageRow("模型输出", value: usage.outputTokens, color: DSHColor.orange)
            } else {
                HStack(spacing: 8) {
                    ProgressView().controlSize(.small)
                    Text("正在读取上下文用量…")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .padding(18)
        .frame(width: 300)
        .presentationBackground(.ultraThinMaterial)
    }

    private func usageRow(_ title: String, value: Int?, color: Color) -> some View {
        HStack(spacing: 10) {
            RoundedRectangle(cornerRadius: 3, style: .continuous)
                .fill(color)
                .frame(width: 12, height: 12)
            Text(title).foregroundStyle(.secondary)
            Spacer()
            Text(value.map { "~\(compact($0))" } ?? "—")
                .monospacedDigit()
        }
        .font(.subheadline)
    }

    private func compact(_ value: Int) -> String {
        switch value {
        case 1_000_000...:
            return String(format: value.isMultiple(of: 1_000_000) ? "%.0fM" : "%.1fM", Double(value) / 1_000_000)
        case 1_000...:
            return String(format: "%.1fK", Double(value) / 1_000)
        default:
            return "\(value)"
        }
    }
}

struct ConversationItem: Identifiable, Sendable {
    enum Kind: Equatable, Sendable { case user, context, assistant, reasoning, tool, jsonTool, toolResult, status, system }
    let id: String
    let kind: Kind
    let title: String
    let text: String
    let isError: Bool
    let date: Date

    static func make(from events: [SessionEvent]) -> [ConversationItem] {
        var result: [ConversationItem] = []
        var streamIndexes: [String: Int] = [:]
        let completed = Set(events.filter { $0.event.type == "assistant/message" }.map { "\($0.event.turn ?? -1)-\($0.event.step ?? -1)" })
        for record in events {
            let event = record.event
            let key = "\(event.turn ?? -1)-\(event.step ?? -1)"
            switch event.type {
            case "user/message" where event.source == nil || event.source == "user":
                if let text = event.text, !text.isEmpty {
                    result.append(.init(id: record.id, kind: .user, title: "你", text: text, isError: false, date: record.date))
                }
            case "user/message":
                if let text = event.text, !text.isEmpty {
                    result.append(.init(
                        id: record.id,
                        kind: .context,
                        title: "上下文注入 · \(contextSourceName(event))",
                        text: text,
                        isError: false,
                        date: record.date
                    ))
                }
            case "assistant/chunk" where event.chunkType == "text-delta" && !completed.contains(key):
                appendStream(id: "stream-text-\(key)", key: "text-\(key)", kind: .assistant, title: "DeepSeek · 正在生成", delta: event.text ?? "", date: record.date, result: &result, indexes: &streamIndexes)
            case "assistant/chunk" where event.chunkType == "reasoning-delta" && !completed.contains(key):
                appendStream(id: "stream-reason-\(key)", key: "reason-\(key)", kind: .reasoning, title: "Think · 正在推理", delta: event.text ?? "", date: record.date, result: &result, indexes: &streamIndexes)
            case "assistant/chunk" where event.chunkType == "tool-call-delta" && !completed.contains(key):
                let toolKey = event.tool?.id ?? key
                let name = event.tool?.name ?? "Tool Call · 正在组装"
                let kind: Kind = name.caseInsensitiveCompare("run_code") == .orderedSame ? .jsonTool : .tool
                appendStream(id: "stream-tool-\(toolKey)", key: "tool-\(toolKey)", kind: kind, title: name, delta: event.tool?.argumentsDelta ?? "", date: record.date, result: &result, indexes: &streamIndexes)
            case "assistant/message":
                if let reasoning = event.reasoning, !reasoning.isEmpty { result.append(.init(id: record.id + "-reason", kind: .reasoning, title: "Think", text: reasoning, isError: false, date: record.date)) }
                if let text = event.text, !text.isEmpty { result.append(.init(id: record.id, kind: .assistant, title: "DeepSeek", text: text, isError: false, date: record.date)) }
            case "tool/call":
                let name = event.name ?? "Tool Call"
                let kind: Kind = name.caseInsensitiveCompare("run_code") == .orderedSame ? .jsonTool : .tool
                result.append(.init(id: record.id, kind: kind, title: name, text: event.arguments?.jsonDisplayText ?? "", isError: false, date: record.date))
            case "tool/result": result.append(.init(id: record.id, kind: .toolResult, title: event.isError == true ? "工具失败" : "工具完成", text: event.preview ?? "", isError: event.isError == true, date: record.date))
            default: continue
            }
        }
        return result
    }

    private static func contextSourceName(_ event: GatewayEvent) -> String {
        if let plugin = event.raw?["source"]?["plugin"]?.stringValue, !plugin.isEmpty {
            return plugin
        }
        return event.source ?? "context"
    }

    private static func appendStream(id: String, key: String, kind: Kind, title: String, delta: String, date: Date, result: inout [ConversationItem], indexes: inout [String: Int]) {
        guard !delta.isEmpty else { return }
        if let index = indexes[key] {
            let old = result[index]
            result[index] = .init(id: old.id, kind: old.kind, title: old.title, text: old.text + delta, isError: old.isError, date: date)
        } else {
            indexes[key] = result.count
            result.append(.init(id: id, kind: kind, title: title, text: delta, isError: false, date: date))
        }
    }
}

private struct ConversationProcessGroup: Identifiable {
    let id: String
    let items: [ConversationItem]

    var duration: TimeInterval {
        guard let first = items.first?.date, let last = items.last?.date else { return 0 }
        return max(0, last.timeIntervalSince(first))
    }
}

private struct ConversationDisplayEntry: Identifiable {
    enum Content {
        case message(ConversationItem)
        case process(ConversationProcessGroup)
    }

    let id: String
    let content: Content
    var showsCopyButton: Bool

    static func make(from items: [ConversationItem]) -> [ConversationDisplayEntry] {
        var result: [ConversationDisplayEntry] = []
        var processItems: [ConversationItem] = []

        func flushProcess() {
            guard let first = processItems.first else { return }
            let group = ConversationProcessGroup(
                id: "process-\(first.id)",
                items: processItems
            )
            result.append(.init(id: group.id, content: .process(group), showsCopyButton: false))
            processItems.removeAll(keepingCapacity: true)
        }

        for item in items {
            switch item.kind {
            case .context, .reasoning, .tool, .jsonTool, .toolResult:
                processItems.append(item)
            case .user, .assistant, .status, .system:
                flushProcess()
                result.append(.init(id: "message-\(item.id)", content: .message(item), showsCopyButton: false))
            }
        }
        flushProcess()

        // User messages are always copyable.  Agent messages are copyable only
        // when they are the last formal response before the next user message;
        // intermediate narration followed by another assistant response stays
        // visually quiet, matching WebUI's final-answer action placement.
        for index in result.indices {
            guard case .message(let item) = result[index].content else { continue }
            if item.kind == .user {
                result[index].showsCopyButton = true
                continue
            }
            guard item.kind == .assistant else { continue }
            let nextConversationalMessage = result[(index + 1)...].lazy.compactMap { entry -> ConversationItem.Kind? in
                guard case .message(let following) = entry.content,
                      following.kind == .user || following.kind == .assistant else { return nil }
                return following.kind
            }.first
            result[index].showsCopyButton = nextConversationalMessage != .assistant
        }
        return result
    }
}

private struct ConversationProcessTool: Identifiable {
    let id: String
    let call: ConversationItem?
    let result: ConversationItem?
}

private enum ConversationProcessContent: Identifiable {
    case context(ConversationItem)
    case reasoning(ConversationItem)
    case tool(ConversationProcessTool)

    var id: String {
        switch self {
        case .context(let item): "context-\(item.id)"
        case .reasoning(let item): "reason-\(item.id)"
        case .tool(let tool): "tool-\(tool.id)"
        }
    }
}

private extension ConversationProcessGroup {
    var contents: [ConversationProcessContent] {
        var contents: [ConversationProcessContent] = []
        var tools: [ConversationProcessTool] = []

        for item in items {
            switch item.kind {
            case .context:
                contents.append(.context(item))
            case .reasoning:
                contents.append(.reasoning(item))
            case .tool, .jsonTool:
                tools.append(.init(id: item.id, call: item, result: nil))
            case .toolResult:
                if let index = tools.firstIndex(where: { $0.result == nil }) {
                    let existing = tools[index]
                    tools[index] = .init(id: existing.id, call: existing.call, result: item)
                } else {
                    tools.append(.init(id: item.id, call: nil, result: item))
                }
            case .user, .assistant, .status, .system:
                break
            }
        }
        contents.append(contentsOf: tools.map(ConversationProcessContent.tool))
        return contents
    }

    var toolCount: Int {
        contents.reduce(0) { count, content in
            if case .tool = content { count + 1 } else { count }
        }
    }

    var contextCount: Int {
        contents.reduce(0) { count, content in
            if case .context = content { count + 1 } else { count }
        }
    }
}

private struct ConversationProcessRow: View {
    let group: ConversationProcessGroup
    @State private var expanded = false
    @Environment(\.conversationDisclosureWillToggle) private var disclosureWillToggle

    private var reasoningText: String {
        group.contents.compactMap { content in
            if case .reasoning(let item) = content { item.text } else { nil }
        }.filter { !$0.isEmpty }.joined(separator: "\n\n")
    }

    private var contexts: [ConversationItem] {
        group.contents.compactMap { content in
            if case .context(let item) = content { item } else { nil }
        }
    }

    private var tools: [ConversationProcessTool] {
        group.contents.compactMap { content in
            if case .tool(let tool) = content { tool } else { nil }
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Button { toggleWithoutAnimation($expanded, before: disclosureWillToggle) } label: {
                HStack(spacing: 7) {
                    Text(processTitle)
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(.secondary)
                    Spacer(minLength: 8)
                    Image(systemName: "chevron.right")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.tertiary)
                        .rotationEffect(.degrees(expanded ? 90 : 0))
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if expanded {
                VStack(alignment: .leading, spacing: 8) {
                    ForEach(contexts) { item in
                        ConversationContextDisclosure(item: item)
                    }
                    if !reasoningText.isEmpty {
                        ConversationReasoningDisclosure(text: reasoningText)
                    }
                    if !tools.isEmpty {
                        ConversationToolBundle(tools: tools)
                    }
                }
                .padding(.leading, 2)
            }
        }
        .padding(.vertical, 7)
        .overlay(alignment: .bottom) {
            Rectangle().fill(.gray.opacity(0.16)).frame(height: 1)
        }
    }

    private var processTitle: String {
        if group.contextCount > 0, group.toolCount == 0, reasoningText.isEmpty {
            return group.contextCount == 1 ? "上下文" : "\(group.contextCount) 项上下文"
        }
        let duration = group.duration
        let base: String
        if duration >= 60 {
            base = "耗时 \(Int(duration) / 60) 分钟 \(Int(duration) % 60) 秒"
        } else if duration >= 1 {
            base = "耗时 \(Int(duration.rounded())) 秒"
        } else {
            base = "思考过程"
        }
        var details: [String] = []
        if group.contextCount > 0 { details.append("\(group.contextCount) 项上下文") }
        if group.toolCount > 0 { details.append("\(group.toolCount) 次工具调用") }
        return details.isEmpty ? base : "\(base) · \(details.joined(separator: " · "))"
    }
}

private struct ConversationContextDisclosure: View {
    let item: ConversationItem
    @State private var expanded = false

    var body: some View {
        CompactEventDisclosure(
            expanded: $expanded,
            title: item.title,
            text: item.text,
            icon: "doc.text",
            tint: .green
        )
    }
}

private struct ConversationReasoningDisclosure: View {
    let text: String
    @State private var expanded = false
    @Environment(\.conversationDisclosureWillToggle) private var disclosureWillToggle

    private var preview: String {
        text.replacingOccurrences(of: "\n", with: " ")
            .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var needsViewport: Bool {
        text.count > 4_000 || text.components(separatedBy: .newlines).count > 60
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Button { toggleWithoutAnimation($expanded, before: disclosureWillToggle) } label: {
                HStack(spacing: 8) {
                    Image(systemName: "sparkles")
                        .foregroundStyle(DSHColor.purple)
                        .frame(width: 18)
                    Text("Think")
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(DSHColor.purple)
                    if !preview.isEmpty {
                        Text("·").foregroundStyle(.tertiary)
                        Text(preview)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                    Spacer(minLength: 8)
                    Image(systemName: "chevron.right")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.tertiary)
                        .rotationEffect(.degrees(expanded ? 90 : 0))
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if expanded {
                Group {
                    if needsViewport {
                        ScrollView(.vertical, showsIndicators: true) {
                            MarkdownContent(text, compact: true)
                                .foregroundStyle(.secondary)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(10)
                        }
                        .frame(height: 320)
                        .background(Color(uiColor: .secondarySystemFill), in: RoundedRectangle(cornerRadius: 10))
                    } else {
                        MarkdownContent(text, compact: true)
                            .foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .padding(.leading, 26)
            }
        }
    }
}

private struct ConversationToolBundle: View {
    let tools: [ConversationProcessTool]
    @State private var expanded = false
    @Environment(\.conversationDisclosureWillToggle) private var disclosureWillToggle

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Button { toggleWithoutAnimation($expanded, before: disclosureWillToggle) } label: {
                HStack(spacing: 8) {
                    Image(systemName: "wrench.and.screwdriver")
                        .foregroundStyle(DSHColor.orange)
                        .frame(width: 18)
                    Text(bundleTitle)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                    Spacer(minLength: 8)
                    Image(systemName: "chevron.right")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.tertiary)
                        .rotationEffect(.degrees(expanded ? 90 : 0))
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if expanded {
                VStack(alignment: .leading, spacing: 2) {
                    ForEach(tools) { tool in
                        ConversationProcessToolRow(tool: tool)
                    }
                }
                .padding(.leading, 14)
            }
        }
    }

    private var bundleTitle: String {
        let names = tools.compactMap { $0.call?.title }.prefix(2).joined(separator: "、")
        return names.isEmpty ? "查看 \(tools.count) 个工具结果" : "使用了 \(names)\(tools.count > 2 ? " 等工具" : "")"
    }
}

private struct ConversationProcessToolRow: View {
    let tool: ConversationProcessTool
    @State private var expanded = false
    @Environment(\.conversationDisclosureWillToggle) private var disclosureWillToggle

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Button { toggleWithoutAnimation($expanded, before: disclosureWillToggle) } label: {
                HStack(spacing: 7) {
                    Image(systemName: tool.result?.isError == true ? "exclamationmark.triangle" : "terminal")
                        .foregroundStyle(tool.result?.isError == true ? .red : DSHColor.orange)
                        .frame(width: 17)
                    Text(tool.call?.title ?? tool.result?.title ?? "工具")
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                    if let result = tool.result {
                        Text(result.isError ? "失败" : "完成")
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(result.isError ? .red : .secondary)
                    }
                    Spacer(minLength: 8)
                    Image(systemName: "chevron.right")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.tertiary)
                        .rotationEffect(.degrees(expanded ? 90 : 0))
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if expanded {
                VStack(alignment: .leading, spacing: 9) {
                    if let call = tool.call, !call.text.isEmpty {
                        Text("调用参数")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.secondary)
                        ToolArgumentsView(text: call.text)
                    }
                    if let result = tool.result, !result.text.isEmpty {
                        Text(result.isError ? "错误" : "结果")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(result.isError ? .red : .secondary)
                        ToolOutputView(text: result.text)
                    }
                }
                .padding(.leading, 24)
                .padding(.bottom, 5)
            }
        }
        .padding(.vertical, 3)
    }
}

private struct ToolArgumentsView: View {
    let text: String

    var body: some View {
        Group {
            if text.count > 3_000 {
                ScrollView([.horizontal, .vertical], showsIndicators: true) {
                    MarkdownContent("```json\n\(text)\n```", compact: true)
                        .padding(8)
                }
                .frame(height: 220)
            } else {
                MarkdownContent("```json\n\(text)\n```", compact: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .clipShape(RoundedRectangle(cornerRadius: 9))
    }
}

private struct ConversationDisclosureWillToggleKey: EnvironmentKey {
    static let defaultValue: () -> Void = {}
}

private extension EnvironmentValues {
    var conversationDisclosureWillToggle: () -> Void {
        get { self[ConversationDisclosureWillToggleKey.self] }
        set { self[ConversationDisclosureWillToggleKey.self] = newValue }
    }
}

private func toggleWithoutAnimation(_ value: Binding<Bool>, before: () -> Void = {}) {
    before()
    var transaction = Transaction()
    transaction.disablesAnimations = true
    withTransaction(transaction) { value.wrappedValue.toggle() }
}

private struct ConversationRow: View {
    let item: ConversationItem
    let showsCopyButton: Bool
    @State private var expanded = false

    var body: some View {
        switch item.kind {
        case .user:
            VStack(alignment: .trailing, spacing: 5) {
                Text(item.text)
                    .font(.body).padding(.horizontal, 14).padding(.vertical, 10)
                    .background(DSHColor.ocean.opacity(0.11), in: RoundedRectangle(cornerRadius: 15))
                if showsCopyButton { CopyMessageButton(text: item.text) }
            }
            .frame(maxWidth: .infinity, alignment: .trailing)
            .padding(.vertical, 12)
        case .context:
            CompactEventDisclosure(
                expanded: $expanded,
                title: item.title,
                text: item.text,
                icon: "doc.text",
                tint: .green
            )
        case .assistant:
            VStack(alignment: .leading, spacing: 7) {
                HStack(spacing: 9) {
                    DeepSeekWhaleIcon(size: 26).foregroundStyle(DSHColor.ink)
                    Text(item.title)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.secondary)
                }
                .padding(.horizontal, 2)
                .padding(.vertical, 5)
                MarkdownContent(item.text)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if showsCopyButton { CopyMessageButton(text: item.text) }
            }
            .padding(.vertical, 3)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.vertical, 12)
        case .reasoning:
            CompactEventDisclosure(
                expanded: $expanded,
                title: "Think",
                text: item.text,
                icon: "sparkles",
                tint: DSHColor.purple
            )
        case .tool, .toolResult:
            CompactEventDisclosure(
                expanded: $expanded,
                title: item.title,
                text: item.text,
                icon: item.isError ? "exclamationmark.triangle" : "wrench.and.screwdriver",
                tint: item.isError ? .red : DSHColor.orange,
                rendersOutput: item.title == "工具完成" || item.title == "工具失败"
            )
        case .jsonTool:
            CompactEventDisclosure(
                expanded: $expanded,
                title: item.title,
                text: item.text,
                icon: "curlybraces.square",
                tint: DSHColor.orange,
                rendersJSON: true
            )
        case .status:
            HStack { Rectangle().fill(.gray.opacity(0.25)).frame(height: 1); Text([item.title, item.text].filter { !$0.isEmpty }.joined(separator: " · ")).font(.caption).foregroundStyle(.secondary); Rectangle().fill(.gray.opacity(0.25)).frame(height: 1) }
        case .system:
            HStack(alignment: .top, spacing: 9) {
                Image(systemName: item.isError ? "exclamationmark.circle.fill" : "antenna.radiowaves.left.and.right")
                    .foregroundStyle(item.isError ? .red : DSHColor.ocean)
                VStack(alignment: .leading, spacing: 3) {
                    Text(item.title).font(.caption.weight(.semibold))
                    if !item.text.isEmpty { Text(item.text).font(.caption).foregroundStyle(.secondary).textSelection(.enabled) }
                }
            }
            .padding(10).frame(maxWidth: .infinity, alignment: .leading)
            .background((item.isError ? Color.red : DSHColor.ocean).opacity(0.06), in: RoundedRectangle(cornerRadius: 11))
        }
    }
}

private struct CopyMessageButton: View {
    let text: String
    @State private var copied = false

    var body: some View {
        Button {
            UIPasteboard.general.string = text
            copied = true
            Task { @MainActor in
                try? await Task.sleep(for: .seconds(1.4))
                copied = false
            }
        } label: {
            Group {
                if copied {
                    Image(systemName: "checkmark")
                        .font(.system(size: 14, weight: .semibold))
                } else {
                    Image("CopyMessage")
                        .renderingMode(.template)
                        .resizable()
                        .scaledToFit()
                        .frame(width: 16, height: 16)
                }
            }
                .foregroundStyle(copied ? DSHColor.ocean : Color.secondary)
                .frame(width: 30, height: 26)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(copied ? "已复制" : "复制正文")
    }
}

private struct CompactEventDisclosure: View {
    @Binding var expanded: Bool
    let title: String
    let text: String
    let icon: String
    let tint: Color
    var rendersJSON = false
    var rendersOutput = false
    @Environment(\.conversationDisclosureWillToggle) private var disclosureWillToggle

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button {
                // DisclosureGroup animates its cached height inside LazyVStack.
                // Large tool payloads can therefore temporarily overlap the
                // following message. Toggle without a transition so SwiftUI
                // measures the expanded payload before drawing sibling rows.
                toggleWithoutAnimation($expanded, before: disclosureWillToggle)
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: icon).foregroundStyle(tint).frame(width: 18)
                    Text(title).foregroundStyle(tint).lineLimit(1)
                    if !preview.isEmpty {
                        Text("·").foregroundStyle(.tertiary)
                        Text(preview).foregroundStyle(.secondary).lineLimit(1)
                    }
                    Spacer(minLength: 8)
                    Image(systemName: "chevron.right")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                        .rotationEffect(.degrees(expanded ? 90 : 0))
                }
                .font(.subheadline)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if expanded && !text.isEmpty {
                HStack(alignment: .top, spacing: 10) {
                    Rectangle().fill(.gray.opacity(0.24)).frame(width: 1)
                    if rendersOutput {
                        ToolOutputView(text: text)
                    } else if shouldRenderJSON {
                        MarkdownContent("```json\n\(text)\n```", compact: true)
                    } else {
                        MarkdownContent(text, compact: true).foregroundStyle(.secondary)
                    }
                }
                .padding(.leading, 5).padding(.top, 5).padding(.bottom, 3)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, 2)
    }

    private var preview: String {
        text.replacingOccurrences(of: "\n", with: " ")
            .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var shouldRenderJSON: Bool {
        if rendersJSON { return true }
        guard let data = text.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) else { return false }
        return JSONSerialization.isValidJSONObject(object)
    }
}

private struct ToolOutputView: View {
    let text: String

    private var lines: [String] {
        text.components(separatedBy: .newlines)
    }

    private var needsViewport: Bool {
        text.count > 1_500 || lines.count > 24
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            Text("OUT")
                .font(.caption2.monospaced())
                .foregroundStyle(.secondary)

            if needsViewport {
                ScrollView([.horizontal, .vertical], showsIndicators: true) {
                    LazyVStack(alignment: .leading, spacing: 2) {
                        ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                            Text(line.isEmpty ? " " : line)
                                .font(.caption.monospaced())
                                .foregroundStyle(.primary)
                                .fixedSize(horizontal: true, vertical: false)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(10)
                }
                .defaultScrollAnchor(.topLeading)
                .frame(maxWidth: .infinity, alignment: .leading)
                .frame(height: 280)
            } else {
                Text(text)
                    .font(.caption.monospaced())
                    .foregroundStyle(.primary)
                    .multilineTextAlignment(.leading)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .textSelection(.enabled)
                    .padding(10)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(uiColor: .secondarySystemFill), in: RoundedRectangle(cornerRadius: 10))
        .clipped()
    }
}

struct MarkdownContent: View {
    let text: String
    var compact = false

    init(_ text: String, compact: Bool = false) { self.text = text; self.compact = compact }

    var body: some View {
        Markdown(markdownSource)
            .markdownTheme(.deepSeek(compact: compact))
            .markdownCodeSyntaxHighlighter(DSHCodeSyntaxHighlighter())
    }

    private var markdownSource: String {
        // The thin spaces participate in text layout, keeping adjacent
        // punctuation outside the chip. MarkdownUI's renderer merges their
        // separate font runs into one rounded background on modern iOS.
        InlineCodePadding.apply(to: text)
    }
}

private extension Theme {
    static func deepSeek(compact: Bool) -> Theme {
        let inlineCodeFallback: Color? = if #available(iOS 18.0, *) {
            nil
        } else {
            DSHInlineCodeStyle.fallbackBackground
        }
        return Theme.gitHub
            .text {
                ForegroundColor(.primary)
                BackgroundColor(nil)
                FontSize(compact ? 13 : 17)
            }
            .code {
                FontFamilyVariant(.monospaced)
                FontSize(.em(0.86))
                ForegroundColor(.primary)
                // AttributedString backgrounds are rectangular, so only use
                // this compatibility path on systems without TextRenderer.
                BackgroundColor(inlineCodeFallback)
            }
            .paragraph { configuration in
                configuration.label
                    .fixedSize(horizontal: false, vertical: true)
                    .relativeLineSpacing(.em(0.22))
                    .markdownMargin(top: 0, bottom: compact ? 6 : 10)
            }
            .listItem { configuration in
                configuration.label.markdownMargin(top: .em(0.18))
            }
            .codeBlock { configuration in
                VStack(alignment: .leading, spacing: 6) {
                    if let language = configuration.language, !language.isEmpty {
                        Text(language.uppercased())
                            .font(.caption2.monospaced())
                            .foregroundStyle(.secondary)
                    }
                    ScrollView(.horizontal, showsIndicators: false) {
                        configuration.label
                            .fixedSize(horizontal: true, vertical: false)
                            .markdownTextStyle {
                                FontFamilyVariant(.monospaced)
                                FontSize(compact ? 11 : 13)
                            }
                    }
                }
                .padding(compact ? 10 : 12)
                .background(Color(uiColor: .secondarySystemFill))
                .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
                .markdownMargin(top: 2, bottom: compact ? 6 : 10)
            }
    }
}

private enum DSHInlineCodeStyle {
    // Close to the neutral blue-gray chip used by Harness WebUI, but adaptive
    // enough to remain legible when the app later gains a dark conversation UI.
    static let fallbackBackground = Color(uiColor: .systemGray5).opacity(0.68)
}

private enum InlineCodePadding {
    private static let expression = try! NSRegularExpression(pattern: #"(?<!`)`([^`\n]+)`(?!`)"#)

    static func apply(to markdown: String) -> String {
        var insideFence = false
        return markdown.components(separatedBy: .newlines).map { line in
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if trimmed.hasPrefix("```") || trimmed.hasPrefix("~~~") {
                insideFence.toggle()
                return line
            }
            guard !insideFence else { return line }
            let range = NSRange(location: 0, length: (line as NSString).length)
            return expression.stringByReplacingMatches(
                in: line,
                range: range,
                withTemplate: "`\u{2009}$1\u{2009}`"
            )
        }.joined(separator: "\n")
    }
}

private struct DSHCodeSyntaxHighlighter: CodeSyntaxHighlighter {
    func highlightCode(_ code: String, language: String?) -> Text {
        if isJSON(code, language: language) {
            return Text(JSONSyntaxHighlighter.highlight(code))
        }
        return Text(code)
    }

    private func isJSON(_ code: String, language: String?) -> Bool {
        if let language, ["json", "jsonc"].contains(language.lowercased()) { return true }
        guard let data = code.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) else { return false }
        return JSONSerialization.isValidJSONObject(object)
    }
}

enum JSONSyntaxHighlighter {
    private static let expression = try! NSRegularExpression(
        pattern: #"\"(?:\\.|[^\"\\])*\"|-?\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b|\b(?:true|false|null)\b"#
    )

    static func highlight(_ source: String) -> AttributedString {
        let string = source as NSString
        let fullRange = NSRange(location: 0, length: string.length)
        let matches = expression.matches(in: source, range: fullRange)
        var result = AttributedString()
        var cursor = 0

        for match in matches {
            if match.range.location > cursor {
                result += styled(string.substring(with: NSRange(location: cursor, length: match.range.location - cursor)), color: .primary)
            }
            let token = string.substring(with: match.range)
            result += styled(token, color: color(for: token, in: string, after: NSMaxRange(match.range)))
            cursor = NSMaxRange(match.range)
        }
        if cursor < string.length {
            result += styled(string.substring(from: cursor), color: .primary)
        }
        return result
    }

    private static func color(for token: String, in source: NSString, after location: Int) -> Color {
        if token.hasPrefix("\"") {
            let remainder = source.substring(from: location)
            let isKey = remainder.range(of: #"^\s*:"#, options: .regularExpression) != nil
            return isKey ? DSHColor.ocean : Color(red: 0.10, green: 0.55, blue: 0.38)
        }
        if token == "true" || token == "false" { return DSHColor.purple }
        if token == "null" { return .red.opacity(0.8) }
        return DSHColor.orange
    }

    private static func styled(_ value: String, color: Color) -> AttributedString {
        var result = AttributedString(value)
        result.foregroundColor = color
        return result
    }
}
