import SwiftUI
import AVFoundation

struct WorkspaceView: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @EnvironmentObject private var store: AppStore
    @EnvironmentObject private var hosts: MultiGatewayStore
    let onOpenSession: (SessionSummary) -> Void
    let onNewSession: () -> Void
    let onSettings: () -> Void
    let onPlugins: () -> Void
    let onScheduledTasks: () -> Void
    @State private var searchQuery = ""
    @StateObject private var sessionList = WorkspaceSessionListModel()
    @State private var renamingSession: SessionSummary?
    @State private var renamedTitle = ""
    @State private var archivingSession: SessionSummary?
    @State private var connectionIsReady = false
    @State private var showsDirectoryBrowser = false
    @State private var showsQRScanner = false
    @State private var showsManualPairing = false
    @State private var showsWorkspaceRequiredAlert = false
    @State private var workspaceIsScrolling = false
    @State private var drawerOffset: CGFloat = 0
    @State private var drawerDragStart: CGFloat?
    @State private var drawerFeedback = WorkspaceDrawerFeedback()
    @GestureState private var drawerClosingGestureActive = false
    @FocusState private var sessionSearchIsFocused: Bool

    @ViewBuilder
    var body: some View {
        if #available(iOS 27.1, *) {
            WorkspaceVerticalToolbarLayout { usesVerticalToolbar in
                workspaceBody(usesVerticalToolbar: usesVerticalToolbar)
            } toolbar: {
                ToolbarItem(placement: .topBarTrailing) {
                    authenticationMenu(inToolbar: true)
                        .foregroundStyle(.white)
                }
                .axisBehavior(.verticalPreferred)
                .sharedBackgroundVisibility(.hidden)
                ToolbarItem(placement: .topBarTrailing) {
                    toolbarSettingsButton
                        .foregroundStyle(.white)
                }
                .axisBehavior(.verticalPreferred)
                .sharedBackgroundVisibility(.hidden)
            }
        } else {
            workspaceBody(usesVerticalToolbar: false)
        }
    }

    private func workspaceBody(usesVerticalToolbar: Bool) -> some View {
        GeometryReader { geometry in
            let safeArea = geometry.safeAreaInsets
            let drawerWidth = min(geometry.size.width * 0.76, 360) + safeArea.leading
            let progress = min(max(drawerOffset / max(drawerWidth, 1), 0), 1)
            let dimProgress = min(max((progress - 0.45) / 0.55, 0), 1)
            let dimOpacity = 0.16 * dimProgress * dimProgress * (3 - 2 * dimProgress)
            let topInset = safeArea.top
            let fullHeight = geometry.size.height + topInset + safeArea.bottom
            let fullWidth = geometry.size.width + safeArea.leading + safeArea.trailing

            ZStack(alignment: .leading) {
                (colorScheme == .dark
                    ? Color(red: 36.0 / 255, green: 36.0 / 255, blue: 38.0 / 255)
                    : Color.white)
                drawerContent(progress: progress, topInset: topInset, width: drawerWidth)
                    .padding(.leading, safeArea.leading)
                    .frame(width: drawerWidth, height: fullHeight, alignment: .topLeading)
                    .highPriorityGesture(drawerClosingGesture(width: drawerWidth))

                workspaceContent(
                    openDrawer: { openDrawer(width: drawerWidth) },
                    size: geometry.size,
                    safeArea: safeArea,
                    drawerWidth: drawerWidth,
                    usesVerticalToolbar: usesVerticalToolbar
                )
                    .frame(width: geometry.size.width, height: fullHeight)
                    .padding(.leading, safeArea.leading)
                    .padding(.trailing, safeArea.trailing)
                    // 先扩展到窗口四边，再绘制和裁剪背景；正文仍留在安全区内。
                    .background {
                        DeepOceanBackground(prefersOpenLightArcs: usesVerticalToolbar,
                            animationPaused: workspaceIsScrolling || drawerOffset > 0 || drawerDragStart != nil
                                || showsWorkspaceRequiredAlert)
                    }
                    .overlay {
                        Color.black.opacity(dimOpacity)
                            .allowsHitTesting(false)
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 48 * progress, style: .continuous))
                    .shadow(
                        color: .black.opacity(0.12 * progress),
                        radius: 9 * progress,
                        x: -2 * progress,
                        y: 0
                    )
                    .shadow(
                        color: .black.opacity(0.30 * progress),
                        radius: 26 * progress,
                        x: -4 * progress,
                        y: 0
                    )
                    .overlay(alignment: .leading) {
                        if progress > 0.98 || drawerDragStart != nil {
                            Color.clear
                                .contentShape(Rectangle())
                                .onTapGesture { closeDrawer() }
                                .highPriorityGesture(drawerClosingGesture(width: drawerWidth))
                                .accessibilityLabel("关闭侧边栏")
                                .accessibilityAddTraits(.isButton)
                        }
                    }
                    .offset(x: drawerOffset)
            }
            .frame(width: fullWidth, height: fullHeight)
            .offset(x: -safeArea.leading, y: -topInset)
            .onChangeCompat(of: geometry.size) { _, _ in
                // 旋转或调整分屏后清理旧拖拽距离，避免侧栏停在半开位置。
                drawerFeedback.endDrag(cancelled: true)
                drawerDragStart = nil
                drawerOffset = progress > 0.5 ? drawerWidth : 0
            }
            .onChangeCompat(of: drawerClosingGestureActive) { _, active in
                if !active {
                    finishDrawerDrag(translation: 0, velocity: 0, cancelled: true, width: drawerWidth)
                }
            }
        }
        .background(DSHColor.navy.ignoresSafeArea())
        .onChangeCompat(of: drawerOffset) { _, value in
            if value > 0 { sessionSearchIsFocused = false }
        }
        .foregroundStyle(.white)
        .onAppear {
            sessionList.bind(to: store)
            store.refreshRemoteState()
        }
        .onDisappear {
            sessionList.unbind()
            workspaceIsScrolling = false
            drawerFeedback.endDrag(cancelled: true)
            drawerDragStart = nil
        }
        .onReceive(store.gateway.$state) { connectionIsReady = $0.isConnected }
        .alert("重命名会话", isPresented: Binding(
            get: { renamingSession != nil },
            set: { if !$0 { renamingSession = nil } }
        )) {
            TextField("会话名称", text: $renamedTitle)
            Button("取消", role: .cancel) { renamingSession = nil }
            Button("保存") {
                if let session = renamingSession { store.renameSession(session.id, title: renamedTitle) }
                renamingSession = nil
            }
            .disabled(renamedTitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
        .alert("删除会话？", isPresented: Binding(
            get: { archivingSession != nil },
            set: { if !$0 { archivingSession = nil } }
        )) {
            Button("取消", role: .cancel) { archivingSession = nil }
            Button("删除", role: .destructive) {
                if let session = archivingSession { store.archiveSession(session.id) }
                archivingSession = nil
            }
        } message: {
            Text("会话将被归档并从列表隐藏，历史记录会保留。")
        }
        .alert(String(localized: "请先选择工作区"), isPresented: $showsWorkspaceRequiredAlert) {
            Button(String(localized: "知道了"), role: .cancel) {}
        } message: {
            Text(WorkspaceSessionCopy.creationPrompt)
        }
        .onChangeCompat(of: searchQuery) { _, value in
            sessionList.search(value)
            store.search(value)
        }
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
            ManualGatewayPairingSheet(gateway: hosts.pairingStore?.gateway ?? store.gateway)
                .environmentObject(store)
        }
    }

    private func workspaceContent(
        openDrawer: @escaping () -> Void,
        size: CGSize,
        safeArea: EdgeInsets,
        drawerWidth: CGFloat,
        usesVerticalToolbar: Bool
    ) -> some View {
        let usesColumns = AdaptiveLayout.workspaceUsesColumns(
            size: size, accessibilityText: dynamicTypeSize.isAccessibilitySize
        )
        return ScrollView {
            VStack(alignment: .leading, spacing: usesColumns ? 40 : 18) {
                VStack(alignment: .leading, spacing: 6) {
                    header(openDrawer: openDrawer, usesVerticalToolbar: usesVerticalToolbar)
                        .id("workspace-header")
                    GatewaySwitcherBar()
                }
                if usesColumns {
                    HStack(alignment: .top, spacing: 48) {
                        workspaceActions.frame(width: min(440, size.width * 0.38))
                        sessionListContent.frame(maxWidth: .infinity)
                    }
                } else {
                    Spacer(minLength: 44)
                    workspaceActions
                    sessionListContent
                }
            }
            .padding(.horizontal, usesColumns ? 40 : 22)
            .padding(.top, safeArea.top + 18)
            .padding(.bottom, safeArea.bottom + 24)
            .readableContentWidth(AdaptiveLayout.workspaceWidth)
            .background {
                DrawerPanGestureAttachment(
                    shouldBegin: { translation in
                        guard abs(translation.x) > abs(translation.y) * 1.2 else { return false }
                        return drawerOffset > 0 ? translation.x < 0 : translation.x > 0
                    },
                    onChanged: { translation in updateDrawerDrag(translation: translation, width: drawerWidth) },
                    onEnded: { translation, velocity, cancelled in
                        finishDrawerDrag(translation: translation, velocity: velocity,
                            cancelled: cancelled, width: drawerWidth)
                    }
                )
            }
        }
        .scrollIndicators(.hidden)
        .scrollDismissesKeyboard(.interactively)
        .scrollDisabled(drawerDragStart == nil && drawerOffset > 0)
        .accessibilityIdentifier("workspace-session-list")
        .modifier(WorkspaceScrollPhaseObserver { active in
            guard workspaceIsScrolling != active else { return }
            workspaceIsScrolling = active
            sessionList.setScrolling(active)
        })
        .onTapGesture { sessionSearchIsFocused = false }
    }

    private var workspaceActions: some View {
        VStack(alignment: .leading, spacing: 18) {
            VStack(alignment: .leading, spacing: 7) {
                Text("探索未至之境")
                    .appFont(.system(size: 32, weight: .bold))
                Text("Deepseek Harness预览版")
                    .appFont(.subheadline).foregroundStyle(.white.opacity(0.65))
            }
            .id("workspace-hero")
            workspaceCard.id("workspace-card")
            newSessionButton.id("workspace-new-session")
                .disabled(!connectionIsReady && !store.isAllWorkspacesSelected)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var recentSessions: some View {
        VStack(alignment: .leading, spacing: 18) {
            sessionsHeader.id("workspace-sessions-header")
            sessionSearch.id("workspace-session-search")
            if sessionList.isLoading {
                ProgressView().tint(.white).frame(maxWidth: .infinity)
            } else if sessionList.snapshot.entries.isEmpty {
                emptySessions.id("workspace-sessions-empty")
            }
            if !searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                Text(store.searchCoverage).appFont(.caption).foregroundStyle(.white.opacity(0.55))
            }
        }
    }

    /// 标题、搜索与列表同属一栏，列表起点不受左侧操作区高度影响。
    private var sessionListContent: some View {
        LazyVStack(alignment: .leading, spacing: 0) {
            recentSessions.padding(.bottom, 8)
            ForEach(sessionList.snapshot.entries) { entry in
                sessionListEntry(entry)
            }
        }
    }

    private func header(openDrawer: @escaping () -> Void, usesVerticalToolbar: Bool) -> some View {
        HStack {
            Button(action: openDrawer) {
                HarnessMark(width: dynamicTypeSize.isAccessibilitySize ? 150 : 216)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
                .layoutPriority(-1)
                .buttonStyle(.plain)
                .accessibilityLabel("打开侧边栏")
            Spacer()
            if !usesVerticalToolbar {
                authenticationMenu()
                settingsButton
                    // Match the outer glass circle to the workspace cards' trailing edge.
                    .padding(.trailing, -4)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func drawerContent(progress: CGFloat, topInset: CGFloat, width: CGFloat) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HarnessMark(onDarkBackground: colorScheme == .dark, width: min(width - 48, 216))
                .padding(.leading, 12)
                .padding(.bottom, 16)

            drawerItem("插件", icon: {
                Image("DshPluginPinwheel")
                    .renderingMode(.template)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 22, height: 22)
                    .frame(width: 24)
            }, action: { selectDrawerItem(onPlugins) })
            drawerItem("定时任务", icon: {
                Image(systemName: "clock")
                    .resizable()
                    .scaledToFit()
                    .fontWeight(.medium)
                    .frame(width: 22, height: 22)
                    .frame(width: 24)
            }, action: { selectDrawerItem(onScheduledTasks) })
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 18)
        .padding(.top, topInset + 18)
        .foregroundStyle(Color(uiColor: .label))
        .opacity(0.6 + 0.4 * progress)
        .scaleEffect(0.9 + 0.1 * progress, anchor: .leading)
        .accessibilityHidden(progress == 0)
    }

    private func drawerItem<Icon: View>(
        _ title: String,
        @ViewBuilder icon: () -> Icon,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 10) {
                icon()
                Text(title).appFont(.system(size: 17))
                Spacer(minLength: 0)
            }
            .frame(minHeight: 52)
            .padding(.horizontal, 14)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func openDrawer(width: CGFloat) {
        sessionSearchIsFocused = false
        settleDrawer(to: width)
    }

    private func drawerClosingGesture(width: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 6, coordinateSpace: .global)
            .updating($drawerClosingGestureActive) { value, active, _ in
                active = drawerDragStart != nil || (
                    drawerOffset > 0 && value.translation.width < 0 &&
                    abs(value.translation.width) > abs(value.translation.height) * 1.2
                )
            }
            .onChanged { value in
                // 尚未锁定方向时只接受左滑，拖动开始后允许反向拉回。
                guard drawerDragStart != nil || (
                    drawerOffset > 0 && value.translation.width < 0 &&
                    abs(value.translation.width) > abs(value.translation.height) * 1.2
                ) else { return }
                updateDrawerDrag(translation: value.translation.width, width: width)
            }
            .onEnded { value in
                finishDrawerDrag(
                    translation: value.translation.width, velocity: value.velocity.width,
                    cancelled: false, width: width
                )
            }
    }

    private func updateDrawerDrag(translation: CGFloat, width: CGFloat) {
        if drawerDragStart == nil {
            drawerDragStart = drawerOffset
            drawerFeedback.beginDrag()
        }
        var transaction = Transaction(animation: nil)
        transaction.disablesAnimations = true
        withTransaction(transaction) {
            drawerOffset = min(max((drawerDragStart ?? 0) + translation, 0), width)
        }
    }

    private func finishDrawerDrag(translation: CGFloat, velocity: CGFloat, cancelled: Bool, width: CGFloat) {
        guard let start = drawerDragStart else { return }
        drawerDragStart = nil
        drawerFeedback.endDrag(cancelled: cancelled)
        let open: Bool
        if cancelled {
            open = start > width * 0.5
        } else if start == 0 {
            open = translation > 24 || velocity > 250
        } else {
            open = translation >= -24 && velocity >= -250
        }
        settleDrawer(to: open ? width : 0, feedback: false)
    }

    private func closeDrawer() {
        settleDrawer(to: 0)
    }

    private func settleDrawer(to target: CGFloat, feedback: Bool = true) {
        if feedback { drawerFeedback.tap() }
        if reduceMotion {
            var transaction = Transaction(animation: nil)
            transaction.disablesAnimations = true
            withTransaction(transaction) { drawerOffset = target }
        } else {
            withAnimation(.spring(response: 0.38, dampingFraction: 0.86)) {
                drawerOffset = target
            }
        }
    }

    private func selectDrawerItem(_ action: @escaping () -> Void) {
        // Start the destination push immediately. Reset the workspace behind it
        // without a closing animation so Back reveals the main page.
        drawerFeedback.tap()
        action()
        var transaction = Transaction(animation: nil)
        transaction.disablesAnimations = true
        withTransaction(transaction) {
            drawerOffset = 0
        }
    }

    @ViewBuilder
    private func authenticationMenu(inToolbar: Bool = false) -> some View {
        GatewayAuthenticationMenu(
            gateway: store.gateway,
            inToolbar: inToolbar,
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
        headerButton(systemName: "gearshape.fill", accessibilityLabel: String(localized: "设置"), action: onSettings)
    }

    @available(iOS 27.1, *)
    private var toolbarSettingsButton: some View {
        Button(action: onSettings) {
            headerButtonLabel(systemName: "gearshape.fill")
                .glassEffect(.clear.tint(DSHColor.navy.opacity(0.22)), in: .circle)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(String(localized: "设置"))
    }

    @ViewBuilder
    private func headerButton(systemName: String, accessibilityLabel: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            headerButtonLabel(systemName: systemName)
        }
        .glassCircleButton(onDarkBackground: true)
        .accessibilityLabel(accessibilityLabel)
    }

    private func headerButtonLabel(systemName: String) -> some View {
        Image(systemName: systemName)
            .appFont(.system(size: 17, weight: .semibold))
            .frame(width: 40, height: 40)
            .contentShape(Circle())
    }

    private var workspaceCard: some View {
        Menu {
            Button {
                store.selectAllWorkspaces()
            } label: {
                Label("全部会话", systemImage: store.isAllWorkspacesSelected ? "checkmark.circle.fill" : "square.stack")
            }
            .accessibilityIdentifier("workspace-all-sessions")

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
        .accessibilityLabel(String(localized: "选择工作区"))
    }

    private var workspaceCardLabel: some View {
        HStack(spacing: 12) {
            Image(systemName: "folder").foregroundStyle(.blue).appFont(.title3)
            VStack(alignment: .leading, spacing: 2) {
                Text(workspaceDisplayTitle).appFont(.subheadline.weight(.semibold)).lineLimit(2)
                Text(workspaceDisplayPath).appFont(.caption).foregroundStyle(.white.opacity(0.55)).lineLimit(1)
            }
            Spacer()
            GatewayConnectionIndicator(gateway: store.gateway)
            Image(systemName: "chevron.down").appFont(.caption).foregroundStyle(.white.opacity(0.55))
        }
        .padding(16)
        .background(.white.opacity(0.055), in: RoundedRectangle(cornerRadius: 15))
        .overlay(RoundedRectangle(cornerRadius: 15).stroke(.white.opacity(0.14)))
        .contentShape(RoundedRectangle(cornerRadius: 15))
    }

    private var sessionsHeader: some View {
        HStack {
            Text(store.isAllWorkspacesSelected ? String(localized: "全部会话") : String(localized: "最近会话"))
                .appFont(.headline)
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
                    .foregroundColor(.white.opacity(0.58))
            )
            .foregroundStyle(.white.opacity(0.92))
            .tint(.white)
            .textInputAutocapitalization(.never)
            .focused($sessionSearchIsFocused)
            .submitLabel(.search)
            .onSubmit { sessionSearchIsFocused = false }
        }
        .padding(.horizontal, 12).frame(minHeight: 42)
        .background(.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 13))
        .overlay(RoundedRectangle(cornerRadius: 13).stroke(.white.opacity(0.1)))
    }

    private var emptySessions: some View {
        Text(searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
             ? String(localized: "暂无已知会话。连接服务后创建第一个任务。")
             : String(localized: "已缓存聊天中未找到匹配内容。连接网关后可继续搜索其他聊天。"))
            .appFont(.subheadline).foregroundStyle(.white.opacity(0.5))
            .frame(maxWidth: .infinity, alignment: .leading).padding(18)
            .background(.white.opacity(0.04), in: RoundedRectangle(cornerRadius: 15))
    }

    private var workspaceDisplayTitle: String {
        if store.isAllWorkspacesSelected { return String(localized: "全部会话") }
        return store.isUngroupedWorkspaceSelected ? String(localized: "未分组") : (store.activeWorkspace?.title ?? "DeepseekHarnessProject")
    }

    private var workspaceDisplayPath: String {
        if store.isAllWorkspacesSelected {
            return WorkspaceSessionCopy.count(sessionList.snapshot.visibleCount)
        }
        if store.isUngroupedWorkspaceSelected {
            return String(localized: "workspace.ungrouped.count", defaultValue: "\(store.ungroupedSessions.count) 个未归属会话")
        }
        return store.activeWorkspace?.path ?? String(localized: "通过 Mobile Gateway 连接")
    }

    @ViewBuilder
    private func sessionListEntry(_ entry: WorkspaceSessionListEntry) -> some View {
        switch entry {
        case .section(let id, let title, let number, let count):
            HStack(spacing: 10) {
                Text(title)
                    .appFont(.caption.weight(.medium))
                    .lineLimit(1)
                    .truncationMode(.middle)
                    .layoutPriority(1)
                Rectangle().fill(.white.opacity(0.12)).frame(height: 1)
                Text(WorkspaceSessionCopy.count(count))
                    .appFont(.caption2)
                    .fixedSize()
            }
            .foregroundStyle(.white.opacity(0.55))
            .padding(.top, number == 1 ? 8 : 24)
            .padding(.bottom, 8)
            .accessibilityElement(children: .combine)
            .accessibilityLabel(WorkspaceSessionCopy.sectionAccessibility(title: title, count: count))
            .accessibilityIdentifier("workspace-section-\(id)")
        case .sectionToggle(let id, let remainingCount, let isExpanded):
            Button {
                sessionList.toggleSection(id)
            } label: {
                HStack(spacing: 6) {
                    Text(isExpanded ? String(localized: "收起") : WorkspaceSessionCopy.expand(remainingCount))
                    Image(systemName: isExpanded ? "chevron.up" : "chevron.down")
                }
                .appFont(.caption)
                .foregroundStyle(.white.opacity(0.62))
                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                .padding(.leading, 18)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("workspace-section-toggle-\(id)")
        case .session(let session, let snippet):
            Button {
                sessionSearchIsFocused = false
                onOpenSession(session)
            } label: {
                WorkspaceSessionRow(session: session, snippet: snippet)
                    .equatable()
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("workspace-session-\(session.id)")
            .contextMenu {
                Button {
                    renamedTitle = session.title
                    renamingSession = session
                } label: { Label("重命名", systemImage: "pencil") }
                .disabled(!connectionIsReady)
                Button(role: .destructive) {
                    archivingSession = session
                } label: { Label("删除（归档）", systemImage: "archivebox") }
                .disabled(!connectionIsReady)
            }
        }
    }

    @ViewBuilder
    private var newSessionButton: some View {
        if #available(iOS 26.0, *) {
            Button(action: requestNewSession) {
                newSessionButtonLabel
            }
            .buttonStyle(.glass(.clear.tint(DSHColor.navy.opacity(0.22))))
            .buttonBorderShape(.roundedRectangle(radius: 18))
            .buttonSizing(.flexible)
        } else {
            Button(action: requestNewSession) {
                newSessionButtonLabel
            }
            .buttonStyle(.plain)
            .glassSurface(radius: 18, dark: true, tint: .white.opacity(0.08))
        }
    }

    private func requestNewSession() {
        if store.isAllWorkspacesSelected {
            showsWorkspaceRequiredAlert = true
        } else {
            onNewSession()
        }
    }

    private var newSessionButtonLabel: some View {
        HStack(spacing: 10) {
            Image(systemName: "plus").appFont(.system(size: 15, weight: .semibold))
                .frame(width: 28, height: 28)
                .background(.white.opacity(0.1), in: Circle())
            Text("新建会话").appFont(.headline)
        }
        .frame(maxWidth: .infinity).frame(minHeight: 38)
        .contentShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
    }
}

/// 原生滚动阶段覆盖拖动、惯性与程序动画；只在活动/空闲边界通知，不逐帧刷新 SwiftUI 状态。
struct WorkspaceScrollPhaseObserver: ViewModifier {
    let onChange: (Bool) -> Void

    @ViewBuilder
    func body(content: Content) -> some View {
        if #available(iOS 18.0, *) {
            content.onScrollPhaseChange { _, phase in onChange(phase != .idle) }
        } else {
            content
        }
    }
}

/// 行只接收自己的值，不订阅 AppStore，也不在滚动时扫描搜索结果或工作区。
private struct WorkspaceSessionRow: View, Equatable {
    let session: SessionSummary
    let snippet: String?

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 11) {
                Circle()
                    .fill(session.isRunning ? DSHColor.success : (session.hasUnread ? DSHColor.ocean : .white.opacity(0.35)))
                    .frame(width: 7, height: 7)
                VStack(alignment: .leading, spacing: 3) {
                    Text(session.title).lineLimit(1).appFont(.subheadline.weight(.medium))
                    if let snippet {
                        Text(snippet).lineLimit(2).appFont(.caption).foregroundStyle(.white.opacity(0.62))
                    } else {
                        Text(String(session.id.prefix(16)))
                            .appFont(.caption2.monospaced()).foregroundStyle(.white.opacity(0.42))
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Text(session.isRunning ? String(localized: "运行中") : session.lastActivity.formatted(.relative(presentation: .named)))
                    .appFont(.caption).foregroundStyle(session.isRunning ? .blue : .white.opacity(0.48))
                    .lineLimit(1)
                    .layoutPriority(1)
            }
            .padding(.horizontal, 4)
            .padding(.vertical, 10)
            Divider().overlay(.white.opacity(0.1)).padding(.leading, 18)
        }
        .contentShape(Rectangle())
    }
}

/// 根据系统栏的方向选择入口位置，不按设备型号或横竖屏推测。
@available(iOS 27.1, *)
private struct WorkspaceVerticalToolbarLayout<Content: View, Toolbar: ToolbarContent>: View {
    @Environment(\.toolbarVerticalEdge) private var verticalEdge
    @ViewBuilder let content: (Bool) -> Content
    @ToolbarContentBuilder let toolbar: () -> Toolbar

    var body: some View {
        content(verticalEdge != nil)
            .toolbar {
                if verticalEdge != nil {
                    toolbar()
                }
            }
            .toolbarBackground(.hidden, for: .navigationBar)
    }
}

/// 拖拽开始预热，松手同步反馈；取消和后续动画均不补震。
@MainActor
final class WorkspaceDrawerFeedback {
    private let prepare: () -> Void
    private let impact: () -> Void
    private var dragging = false

    convenience init() {
        let generator = UIImpactFeedbackGenerator(style: .medium)
        self.init(prepare: { generator.prepare() }, impact: { generator.impactOccurred(intensity: 1) })
    }

    init(prepare: @escaping () -> Void, impact: @escaping () -> Void) {
        self.prepare = prepare
        self.impact = impact
    }

    func beginDrag() {
        guard !dragging else { return }
        dragging = true
        prepare()
    }

    func endDrag(cancelled: Bool) {
        guard dragging else { return }
        dragging = false
        if !cancelled { impact() }
    }

    func tap() {
        dragging = false
        prepare()
        impact()
    }
}

/// Installs a directional pan on the workspace scroll view. A rightward pan
/// wins over controls and scrolling; a vertical pan fails here and stays with
/// the scroll view's native recognizer.
private struct DrawerPanGestureAttachment: UIViewRepresentable {
    let shouldBegin: (CGPoint) -> Bool
    let onChanged: (CGFloat) -> Void
    let onEnded: (_ translation: CGFloat, _ velocity: CGFloat, _ cancelled: Bool) -> Void

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> AttachmentView {
        let view = AttachmentView()
        view.isUserInteractionEnabled = false
        let coordinator = context.coordinator
        view.onHierarchyChange = { [weak view, weak coordinator] in
            DispatchQueue.main.async { [weak view, weak coordinator] in
                guard let view else { return }
                coordinator?.attach(from: view)
            }
        }
        return view
    }

    func updateUIView(_ uiView: AttachmentView, context: Context) {
        context.coordinator.shouldBegin = shouldBegin
        context.coordinator.onChanged = onChanged
        context.coordinator.onEnded = onEnded
        context.coordinator.attach(from: uiView)
    }

    static func dismantleUIView(_ uiView: AttachmentView, coordinator: Coordinator) {
        uiView.onHierarchyChange = nil
        coordinator.detach()
    }

    final class AttachmentView: UIView {
        var onHierarchyChange: (() -> Void)?

        override func didMoveToSuperview() {
            super.didMoveToSuperview()
            onHierarchyChange?()
        }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            onHierarchyChange?()
        }

    }

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        var shouldBegin: ((CGPoint) -> Bool)?
        var onChanged: ((CGFloat) -> Void)?
        var onEnded: ((CGFloat, CGFloat, Bool) -> Void)?
        private weak var scrollView: UIScrollView?
        private var pan: UIPanGestureRecognizer?

        func attach(from view: UIView) {
            var ancestor = view.superview
            while let current = ancestor, !(current is UIScrollView) {
                ancestor = current.superview
            }
            guard let scrollView = ancestor as? UIScrollView, self.scrollView !== scrollView else { return }
            detach()
            let pan = UIPanGestureRecognizer(target: self, action: #selector(handlePan(_:)))
            pan.delegate = self
            pan.cancelsTouchesInView = true
            scrollView.addGestureRecognizer(pan)
            scrollView.panGestureRecognizer.require(toFail: pan)
            self.scrollView = scrollView
            self.pan = pan
        }

        func detach() {
            pan?.isEnabled = false
            if let pan { scrollView?.removeGestureRecognizer(pan) }
            pan = nil
            scrollView = nil
        }

        func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
            guard let pan = gestureRecognizer as? UIPanGestureRecognizer else { return false }
            return shouldBegin?(pan.translation(in: pan.view)) ?? false
        }

        @objc private func handlePan(_ pan: UIPanGestureRecognizer) {
            let translation = pan.translation(in: pan.view).x
            switch pan.state {
            case .began, .changed:
                onChanged?(translation)
            case .ended:
                onEnded?(translation, pan.velocity(in: pan.view).x, false)
            case .cancelled, .failed:
                onEnded?(translation, 0, true)
            default:
                break
            }
        }
    }
}

private struct DirectoryBrowserSurface: ViewModifier {
    @ViewBuilder
    func body(content: Content) -> some View {
        if #available(iOS 16.4, *) {
            content.presentationBackground(Color(uiColor: .systemGroupedBackground))
        } else {
            content
        }
    }
}

private struct DirectoryBrowserSheet: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @State private var creatingPath: String?
    @State private var showsCreateDirectoryPrompt = false
    @State private var newDirectoryName = ""
    @State private var highlightedDirectoryPath: String?

    var body: some View {
        NavigationStack {
            ScrollViewReader { proxy in
                List {
                    Section {
                        Button {
                            if let parentPath { store.browseDirectories(path: parentPath) }
                        } label: {
                            directoryRow(icon: "arrowshape.turn.up.left", title: "..", subtitle: String(localized: "返回上一级"))
                        }
                        .disabled(parentPath == nil)
                        .listRowBackground(Color(uiColor: .secondarySystemGroupedBackground))

                        ForEach(store.directoryEntries) { entry in
                            Button {
                                store.browseDirectories(path: entry.path)
                            } label: {
                                directoryRow(
                                    icon: entry.hidden ? "folder.badge.questionmark" : "folder",
                                    title: entry.name,
                                    subtitle: entry.hidden ? String(localized: "隐藏目录") : nil
                                )
                            }
                            .id(entry.path)
                            .listRowBackground(
                                ZStack {
                                    Color(uiColor: .secondarySystemGroupedBackground)
                                    if highlightedDirectoryPath == entry.path {
                                        DSHColor.ocean.opacity(0.12)
                                    }
                                }
                            )
                        }
                    } header: {
                        VStack(alignment: .leading, spacing: 5) {
                            Text("当前目录")
                                .appFont(.caption.weight(.semibold))
                                .foregroundStyle(Color(uiColor: .secondaryLabel))
                            Text(store.directoryPath ?? String(localized: "正在读取…"))
                                .appFont(.caption.monospaced())
                                .textCase(nil)
                                .foregroundStyle(Color(uiColor: .secondaryLabel))
                        }
                        .textCase(nil)
                    }
                }
                .listStyle(.insetGrouped)
                .scrollContentBackground(.hidden)
                .background(Color(uiColor: .systemGroupedBackground))
                .foregroundStyle(Color(uiColor: .label))
                .toolbarBackground(Color(uiColor: .systemGroupedBackground), for: .navigationBar)
                .toolbarBackground(.visible, for: .navigationBar)
                .allowsHitTesting(!store.directoryIsLoading && !store.directoryCreationIsLoading)
                .overlay {
                    if store.directoryIsLoading && store.directoryEntries.isEmpty {
                        ProgressView(String(localized: "正在读取远程目录…"))
                    }
                }
                .safeAreaInset(edge: .bottom) {
                    createWorkspaceBar
                }
                .navigationTitle("选择工作区目录")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) {
                        Button {
                            newDirectoryName = ""
                            showsCreateDirectoryPrompt = true
                        } label: {
                            Label("新建文件夹", systemImage: "folder.badge.plus")
                        }
                        .disabled(store.directoryPath == nil || store.directoryIsLoading || store.directoryCreationIsLoading)
                    }
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("取消") { dismiss() }
                    }
                }
                .onChangeCompat(of: store.createdDirectoryPathToReveal) { _, _ in
                    revealCreatedDirectory(using: proxy)
                }
                .onChangeCompat(of: store.directoryEntries) { _, _ in
                    revealCreatedDirectory(using: proxy)
                }
                .task(id: highlightedDirectoryPath) {
                    guard highlightedDirectoryPath != nil else { return }
                    try? await Task.sleep(for: .seconds(1.6))
                    guard !Task.isCancelled else { return }
                    withAnimation(.easeOut(duration: 0.25)) {
                        highlightedDirectoryPath = nil
                    }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .tint(DSHColor.ocean)
        .background { Color(uiColor: .systemGroupedBackground).ignoresSafeArea() }
        .modifier(DirectoryBrowserSurface())
        .onAppear {
            store.directoryEntries = []
            store.browseDirectories()
        }
        .onChangeCompat(of: store.selectedWorkspaceId) { _, _ in
            guard let creatingPath,
                  store.activeWorkspace?.path == creatingPath else { return }
            dismiss()
        }
        .alert("新建文件夹", isPresented: $showsCreateDirectoryPrompt) {
            TextField("文件夹名称", text: $newDirectoryName)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            Button("取消", role: .cancel) {}
            Button("创建") { createDirectory() }
                .disabled(normalizedDirectoryName.isEmpty)
        } message: {
            Text("将在当前目录中创建一个新的子文件夹。")
        }
    }

    private var parentPath: String? {
        guard store.directoryCrumbs.count > 1 else { return nil }
        return store.directoryCrumbs.dropLast().last?.path
    }

    private var normalizedDirectoryName: String {
        newDirectoryName.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func createDirectory() {
        guard let parentPath = store.directoryPath,
              !normalizedDirectoryName.isEmpty else { return }
        store.createDirectory(parentPath: parentPath, name: normalizedDirectoryName)
    }

    private func revealCreatedDirectory(using proxy: ScrollViewProxy) {
        guard let path = store.createdDirectoryPathToReveal,
              store.directoryEntries.contains(where: { $0.path == path }) else { return }
        highlightedDirectoryPath = path
        store.acknowledgeCreatedDirectoryReveal(path: path)
        DispatchQueue.main.async {
            withAnimation(.easeInOut(duration: 0.35)) {
                proxy.scrollTo(path, anchor: .center)
            }
        }
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
                        .appFont(.caption)
                        .foregroundStyle(Color(uiColor: .secondaryLabel))
                }
            }
            Spacer()
            Image(systemName: "chevron.right")
                .appFont(.caption.weight(.semibold))
                .foregroundStyle(Color(uiColor: .tertiaryLabel))
        }
        .contentShape(Rectangle())
    }

    private var canCreateWorkspace: Bool {
        store.directoryPath != nil &&
            !store.directoryIsLoading &&
            !store.workspaceCreationIsLoading &&
            !store.directoryCreationIsLoading
    }

    private var createWorkspaceBar: some View {
        VStack(spacing: 8) {
            if let path = store.directoryPath {
                Text(path)
                    .appFont(.caption.monospaced())
                    .foregroundStyle(Color(uiColor: .secondaryLabel))
                    .lineLimit(1)
            }
            Button {
                guard let path = store.directoryPath else { return }
                creatingPath = path
                store.createWorkspace(path: path)
            } label: {
                HStack(spacing: 9) {
                    if store.workspaceCreationIsLoading {
                        ProgressView().tint(Color(uiColor: .systemBackground))
                    } else {
                        Image(systemName: "plus")
                    }
                    Text("在当前目录创建工作区")
                        .fontWeight(.semibold)
                }
                .frame(maxWidth: .infinity)
                .frame(height: 44)
                .foregroundStyle(Color(uiColor: .systemBackground))
                .background {
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .fill(Color(uiColor: .label))
                }
                .contentShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
            .buttonStyle(.plain)
            .disabled(!canCreateWorkspace)
            .opacity(canCreateWorkspace ? 1 : 0.45)

            if store.directoryCreationIsLoading {
                HStack(spacing: 8) {
                    ProgressView()
                    Text("正在创建文件夹…")
                }
                .appFont(.caption)
                .foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 9)
        .background { Color(uiColor: .systemGroupedBackground).ignoresSafeArea(edges: .bottom) }
        .overlay(alignment: .top) { Divider() }
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
    var inToolbar = false
    let onScan: () -> Void
    let onManualEntry: () -> Void

    @ViewBuilder
    var body: some View {
        if inToolbar {
            menu.buttonStyle(.plain)
        } else {
            menu.glassCircleButton(onDarkBackground: true)
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
            if #available(iOS 27.1, *), inToolbar {
                label.glassEffect(.clear.tint(DSHColor.navy.opacity(0.22)), in: .circle)
            } else {
                label
            }
        }
        .accessibilityLabel(String(localized: "a11y.device-auth.state", defaultValue: "设备认证，\(gateway.state.label)"))
    }

    private var label: some View {
        Image(systemName: "person.badge.key.fill")
            .appFont(.system(size: 17, weight: .semibold))
            .frame(width: 40, height: 40)
            .contentShape(Circle())
    }

}

private struct GatewayConnectionStatusText: View {
    @ObservedObject var gateway: GatewayClient

    var body: some View {
        Text(gateway.state.label)
            .appFont(.caption.weight(.medium))
            .foregroundStyle(color)
            .animation(.easeInOut(duration: 0.18), value: gateway.state)
    }

    private var color: Color {
        switch gateway.state {
        case .connected: DSHColor.success
        case .connecting, .failed, .disconnected: .red
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
                            .appFont(.title2.bold())
                        Text("粘贴 Harness WebUI 提供的 Base64URL 配对字符串。长期设备 token 仍只会保存到 Keychain。")
                            .appFont(.subheadline)
                            .foregroundStyle(.secondary)
                    }

                    ZStack(alignment: .topLeading) {
                        if pairingText.isEmpty {
                            Text(Self.placeholder)
                                .appFont(.system(.footnote, design: .monospaced))
                                .foregroundStyle(.tertiary)
                                .padding(.horizontal, 16)
                                .padding(.vertical, 18)
                                .allowsHitTesting(false)
                        }
                        TextEditor(text: $pairingText)
                            .appFont(.system(.footnote, design: .monospaced))
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

                    Button(action: primaryConnectionAction) {
                        HStack(spacing: 9) {
                            if isConnecting {
                                ProgressView().tint(.white)
                            } else {
                                Image(systemName: "link")
                            }
                            Text(
                                isConnecting
                                    ? String(localized: "取消连接")
                                    : gateway.state.isConnected
                                        ? String(localized: "重新配对并连接")
                                        : String(localized: "连接")
                            )
                                .fontWeight(.semibold)
                        }
                        .frame(maxWidth: .infinity)
                        .frame(minHeight: 50)
                        .foregroundStyle(.white)
                        .background(DSHColor.ocean, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                    }
                    .buttonStyle(.plain)
                    .disabled(pairingText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !isConnecting)
                    .opacity(pairingText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !isConnecting ? 0.45 : 1)
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
        .background(.regularMaterial)
    }

    @ViewBuilder
    private var connectionResult: some View {
        if let validationError {
            resultCard(
                title: String(localized: "配对信息无效"),
                detail: validationError,
                color: .red,
                symbol: "exclamationmark.triangle.fill"
            )
        } else if didAttemptConnection {
            switch gateway.state {
            case .connecting:
                resultCard(
                    title: String(localized: "正在连接"),
                    detail: String(localized: "pairing.submitting.detail", defaultValue: "正在提交一次性配对码并等待 Mobile Gateway 完成设备鉴权…"),
                    color: DSHColor.amber,
                    symbol: "arrow.triangle.2.circlepath"
                )
            case .connected:
                resultCard(
                    title: String(localized: "连接成功"),
                    detail: String(localized: "设备鉴权已完成，长期 token 已安全保存到 Keychain。"),
                    color: DSHColor.success,
                    symbol: "checkmark.circle.fill"
                )
            case .failed(let reason):
                resultCard(
                    title: String(localized: "连接失败"),
                    detail: reason,
                    color: .red,
                    symbol: "xmark.octagon.fill"
                )
            case .disconnected:
                resultCard(
                    title: String(localized: "未连接"),
                    detail: String(localized: "请检查配对信息后重新连接。"),
                    color: .secondary,
                    symbol: "network.slash"
                )
            }
        } else {
            resultCard(
                title: gateway.state.label,
                detail: String(localized: "输入 Base64URL 配对字符串后点击连接，结果会显示在这里。"),
                color: .secondary,
                symbol: "person.badge.key.fill"
            )
        }
    }

    private func resultCard(title: String, detail: String, color: Color, symbol: String) -> some View {
        HStack(alignment: .top, spacing: 13) {
            Image(systemName: symbol)
                .appFont(.title3.weight(.semibold))
                .foregroundStyle(color)
                .frame(width: 26)
            VStack(alignment: .leading, spacing: 5) {
                Text(title).appFont(.headline)
                Text(detail)
                    .appFont(.subheadline)
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

    private var isConnecting: Bool {
        if case .connecting = gateway.state { return true }
        return false
    }

    private func primaryConnectionAction() {
        if isConnecting {
            gateway.disconnect()
        } else {
            connect()
        }
    }

    private static let placeholder = "eyJ2ZXJzaW9uIjoyLCJwdWJsaWNVcmwiOiJ3c3M6Ly9nYXRld2F5LmV4YW1wbGUuY29tL3dzL21vYmlsZSIsLi4ufQ"
}

private struct GatewayQRScannerView: View {
    let onCode: (String) -> Void
    let onCancel: () -> Void
    let onFailure: (String) -> Void

    var body: some View {
        GeometryReader { geometry in
            scannerContent(side: min(360, max(120, min(geometry.size.width - 44, geometry.size.height - 230))))
        }
    }

    private func scannerContent(side: CGFloat) -> some View {
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
                            .appFont(.title2.bold())
                        Text("请扫描 Harness WebUI 生成的二维码")
                            .appFont(.subheadline)
                            .foregroundStyle(.white.opacity(0.72))
                    }
                    Spacer()
                    Button(action: onCancel) {
                        Image(systemName: "xmark")
                            .appFont(.system(size: 16, weight: .semibold))
                            .frame(width: 44, height: 44)
                    }
                    .buttonStyle(.plain)
                    .background(.ultraThinMaterial, in: Circle())
                    .accessibilityLabel(String(localized: "取消扫描"))
                }
                .padding(.horizontal, 22)
                .padding(.top, 18)

                Spacer()

                RoundedRectangle(cornerRadius: 28, style: .continuous)
                    .stroke(.white, lineWidth: 3)
                    .frame(width: side, height: side)
                    .overlay(alignment: .bottom) {
                        Text("将二维码完整放入框内")
                            .appFont(.subheadline.weight(.medium))
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
        guard let connection = previewLayer?.connection,
              let orientation = view.window?.windowScene?.interfaceOrientation else { return }
        let angle: CGFloat
        switch orientation {
        case .landscapeRight: angle = 0
        case .landscapeLeft: angle = 180
        case .portraitUpsideDown: angle = 270
        default: angle = 90
        }
        if #available(iOS 17.0, *) {
            if connection.isVideoRotationAngleSupported(angle) {
                connection.videoRotationAngle = angle
            }
        } else if connection.isVideoOrientationSupported {
            // iOS 17 之前只有 AVCaptureVideoOrientation，需显式映射。
            switch orientation {
            case .landscapeRight: connection.videoOrientation = .landscapeRight
            case .landscapeLeft: connection.videoOrientation = .landscapeLeft
            case .portraitUpsideDown: connection.videoOrientation = .portraitUpsideDown
            default: connection.videoOrientation = .portrait
            }
        }
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
                        self.finish(with: String(localized: "camera.denied", defaultValue: "未获得相机权限。请在系统设置中允许 Whaleness 使用相机后重试。"))
                    }
                }
            }
        case .denied, .restricted:
            finish(with: String(localized: "camera.restricted", defaultValue: "相机权限不可用。请在系统设置中允许 Whaleness 使用相机后重试。"))
        @unknown default:
            finish(with: String(localized: "无法确定当前相机权限状态。"))
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
            finish(with: String(localized: "此设备没有可用的相机。"))
            return
        }
        do {
            let input = try AVCaptureDeviceInput(device: camera)
            guard captureSession.canAddInput(input) else {
                finish(with: String(localized: "无法把相机接入扫码会话。"))
                return
            }
            captureSession.addInput(input)

            let output = AVCaptureMetadataOutput()
            guard captureSession.canAddOutput(output) else {
                finish(with: String(localized: "当前设备不支持二维码识别。"))
                return
            }
            captureSession.addOutput(output)
            output.setMetadataObjectsDelegate(self, queue: .main)
            guard output.availableMetadataObjectTypes.contains(.qr) else {
                finish(with: String(localized: "当前相机不支持二维码元数据识别。"))
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
            finish(with: String(localized: "camera.start.failed", defaultValue: "相机启动失败：\(error.localizedDescription)"))
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
