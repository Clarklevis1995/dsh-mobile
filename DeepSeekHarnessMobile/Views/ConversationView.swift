import SwiftUI
import MarkdownUI
import PhotosUI
import UIKit

struct ConversationView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.colorScheme) private var colorScheme
    @State private var activeView = 0
    @State private var draft = ""
    @State private var selectedPhotoItems: [PhotosPickerItem] = []
    @State private var pendingImages: [GatewayOutgoingImage] = []
    @State private var isImportingImages = false
    @State private var showsContextUsage = false
    @State private var showsSessionStats = false
    @State private var showsSessionStatsPopover = false
    @State private var isPinnedToBottom = true
    @State private var composerHeight: CGFloat = 168
    @State private var viewportScrollToBottomToken = 0
    @State private var viewportProxy = ConversationViewportProxy()
    @State private var isPreparingHistoryPresentation = false
    @State private var historyPresentationSessionID: String?
    @State private var bottomSafeAreaInset: CGFloat = 0
    @FocusState private var composerIsFocused: Bool
    private let conversationBottomClearance: CGFloat = 22

    var body: some View {
        ZStack {
            conversationBackground.ignoresSafeArea()
            VStack(spacing: 0) {
                Picker("视图", selection: $activeView) {
                    Text("对话").tag(0)
                    Text("轨迹").tag(1)
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 66).padding(.vertical, 12)

                PersistentSessionPager(selection: $activeView) {
                    chat
                } trajectory: {
                    TrajectoryView(
                        sessionId: store.selectedSessionId,
                        timeline: store.trajectoryTimeline(for: store.selectedSessionId),
                        isActive: activeView == 1
                    )
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .ignoresSafeArea(.container, edges: .bottom)
        .background {
            WindowBottomSafeAreaReader(bottomInset: $bottomSafeAreaInset)
        }
        .foregroundStyle(.primary)
        .onChange(of: activeView) { _, view in
            if view != 0 { composerIsFocused = false }
        }
        .sheet(isPresented: $showsSessionStats) {
            SessionStatsSheet(
                snapshot: store.selectedSessionStatsSnapshot,
                sessionTitle: store.selectedSession?.title ?? String(localized: "session.new.fallback", defaultValue: "新建 DeepSeek Harness")
            )
        }
    }

    private var conversationBackground: Color {
        Color(uiColor: .systemBackground)
    }

    private var glassTint: Color {
        Color(uiColor: .secondarySystemBackground)
            .opacity(colorScheme == .dark ? 0.52 : 0.48)
    }

    private var glassEdge: Color {
        colorScheme == .dark
            ? .white.opacity(0.13)
            : .white.opacity(0.56)
    }

    private var glassShadow: Color {
        .black.opacity(colorScheme == .dark ? 0.34 : 0.10)
    }

    private var chat: some View {
        ZStack(alignment: .bottom) {
            ConversationViewport(
                proxy: viewportProxy,
                sessionID: store.selectedSessionId,
                timeline: store.conversationTimeline(for: store.selectedSessionId ?? "__empty__"),
                supplementalEntries: supplementalViewportEntries,
                makeEntries: { items in
                    makeConversationViewportEntries(from: items)
                },
                bottomInset: composerHeight + conversationBottomClearance,
                scrollToBottomToken: viewportScrollToBottomToken,
                onPinnedToBottomChanged: { pinned in
                    isPinnedToBottom = pinned
                },
                onBottomAlignmentCompleted: {
                    guard isPreparingHistoryPresentation,
                          historyPresentationSessionID == store.selectedSessionId,
                          !isLoadingSelectedHistory else { return }
                    isPreparingHistoryPresentation = false
                },
                onApproachingTop: {
                    guard let sessionID = store.selectedSessionId,
                          store.historyHasMore[sessionID] == true,
                          !store.historyLoadingSessionIds.contains(sessionID) else { return }
                    store.loadHistory(for: sessionID, older: true)
                }
            )
            .padding(.horizontal, 20)
            .padding(.top, 8)
            .opacity(isPreparingHistoryPresentation ? 0 : 1)

            if isPreparingHistoryPresentation {
                historyPresentationMask
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .padding(.bottom, composerHeight)
                    .zIndex(1)
            } else if conversationItems.isEmpty {
                Group {
                    if isLoadingSelectedHistory { historyLoadingState }
                    else { emptyState }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .padding(.bottom, composerHeight)
            }

            composerBackdrop
                .frame(height: composerHeight + 28)
                .allowsHitTesting(false)

            VStack(spacing: 0) {
                if shouldShowScrollToBottom {
                    scrollToBottomButton {
                        viewportScrollToBottomToken &+= 1
                    }
                    .padding(.bottom, 8)
                    .transition(.scale(scale: 0.85).combined(with: .opacity))
                }

                VStack(alignment: .leading, spacing: 0) {
                    if let snapshot = store.selectedSessionStatsSnapshot {
                        sessionStatsBanner(snapshot)
                    }
                    if let request = store.selectedPendingQuestionRequest {
                        HumanQuestionView(
                            request: request,
                            status: store.questionRequestStatuses[request.rpcId] ?? .idle,
                            onAnswer: { store.answerQuestion(request, answers: $0) },
                            onCancel: { store.cancelQuestion(request) }
                        )
                        .id(request.rpcId)
                        .padding(.bottom, composerBottomPadding)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                    } else {
                        composer
                    }
                }
                .background {
                    GeometryReader { composerGeometry in
                        Color.clear.preference(
                            key: ComposerHeightPreferenceKey.self,
                            value: composerGeometry.size.height
                        )
                    }
                }
            }
            .zIndex(2)
        }
        .animation(.easeOut(duration: 0.16), value: shouldShowScrollToBottom)
        .onPreferenceChange(ComposerHeightPreferenceKey.self) { height in
            guard height > 0, abs(height - composerHeight) > 0.5 else { return }
            composerHeight = height
        }
        .onChange(of: composerIsFocused) { _, isFocused in
            guard isFocused else { return }
            viewportScrollToBottomToken &+= 1
        }
        .onChange(of: store.selectedPendingQuestionRequest?.rpcId) { _, rpcId in
            guard rpcId != nil else { return }
            composerIsFocused = false
            if isPinnedToBottom { viewportScrollToBottomToken &+= 1 }
        }
        .onChange(of: isLoadingSelectedHistory) { wasLoading, isLoading in
            guard historyPresentationSessionID == store.selectedSessionId,
                  isPreparingHistoryPresentation,
                  wasLoading,
                  !isLoading else { return }
            guard !conversationItems.isEmpty else {
                isPreparingHistoryPresentation = false
                return
            }
            // History has finished publishing. Ask UIKit to commit the final
            // self-sized layout at the bottom; the mask is removed only from
            // `onBottomAlignmentCompleted` above.
            viewportScrollToBottomToken &+= 1
        }
        .task(id: store.selectedSessionId) {
            guard let sessionID = store.selectedSessionId else {
                historyPresentationSessionID = nil
                isPreparingHistoryPresentation = false
                return
            }
            historyPresentationSessionID = sessionID
            let hasUsableLocalContent = !conversationItems.isEmpty
            // Cached content is immediately presentable. A background refresh
            // must never replace it with a full-screen loading mask; the mask
            // is reserved for a genuinely cold session with no local rows.
            isPreparingHistoryPresentation = isLoadingSelectedHistory && !hasUsableLocalContent
            if hasUsableLocalContent {
                viewportScrollToBottomToken &+= 1
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

    private var isLoadingOlderSelectedHistory: Bool {
        guard let id = store.selectedSessionId else { return false }
        return store.historyLoadingOlderSessionIds.contains(id)
    }

    private var shouldShowScrollToBottom: Bool {
        !isPreparingHistoryPresentation && !conversationItems.isEmpty && !isPinnedToBottom
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

    private var historyPresentationMask: some View {
        ZStack {
            conversationBackground
            VStack(spacing: 14) {
                ProgressView()
                    .controlSize(.large)
                    .tint(DSHColor.ocean)
                Text(isLoadingSelectedHistory ? String(localized: "正在加载历史记录") : String(localized: "正在准备会话"))
                    .font(.title3.weight(.semibold))
                Text(isLoadingSelectedHistory ? historyProgressText : String(localized: "正在定位到最新消息…"))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            .multilineTextAlignment(.center)
            .padding(.horizontal, 28)
        }
        .accessibilityElement(children: .combine)
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
            return String(localized: "history.syncing.gateway", defaultValue: "正在从 Mobile Gateway 同步会话内容…")
        }
        if isLoadingOlderSelectedHistory {
            return progress.loaded > 0
                ? String(localized: "history.loading.loaded", defaultValue: "正在加载更早记录 · 已同步 \(progress.loaded) 个事件")
                : String(localized: "history.loading.earlier", defaultValue: "正在加载更早记录…")
        }
        guard let total = progress.total else {
            return progress.loaded > 0
                ? String(localized: "history.autoloading.loaded", defaultValue: "正在自动加载更早记录 · 已同步 \(progress.loaded) 个事件")
                : String(localized: "history.syncing.gateway", defaultValue: "正在从 Mobile Gateway 同步会话内容…")
        }
        return String(localized: "history.progress.count", defaultValue: "正在加载历史记录 · \(progress.loaded)/\(total)")
    }

    @ViewBuilder
    private func scrollToBottomButton(action: @escaping () -> Void) -> some View {
        let isGenerating = store.selectedSession?.isRunning == true
        if #available(iOS 26.0, *) {
            Button(action: action) {
                scrollToBottomButtonIcon(isGenerating: isGenerating)
            }
            .buttonStyle(.glass)
            .buttonBorderShape(.circle)
            .accessibilityLabel(isGenerating ? String(localized: "a11y.generating.jump-latest", defaultValue: "正在生成，滚动到最新消息") : String(localized: "a11y.jump.latest", defaultValue: "滚动到最新消息"))
        } else {
            Button(action: action) {
                scrollToBottomButtonIcon(isGenerating: isGenerating)
                    .glassSurface(radius: 18)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(isGenerating ? String(localized: "a11y.generating.jump-latest", defaultValue: "正在生成，滚动到最新消息") : String(localized: "a11y.jump.latest", defaultValue: "滚动到最新消息"))
        }
    }

    @ViewBuilder
    private func scrollToBottomButtonIcon(isGenerating: Bool) -> some View {
        Group {
            if isGenerating {
                ProgressView()
                    .controlSize(.regular)
                    .tint(DSHColor.ocean)
            } else {
                Image(systemName: "arrow.down")
                    .font(.system(size: 15, weight: .semibold))
            }
        }
        .frame(width: 36, height: 36)
    }

    private var composer: some View {
        VStack(alignment: .leading, spacing: 12) {
            if !pendingImages.isEmpty {
                pendingImageStrip
            }
            TextField("描述你想要构建的内容", text: $draft, axis: .vertical)
                .lineLimit(1...5)
                .font(.body)
                .textFieldStyle(.plain)
                .focused($composerIsFocused)
                .padding(.horizontal, 3)
                .frame(minHeight: 38, alignment: .top)
            HStack(spacing: 7) {
                PhotosPicker(
                    selection: $selectedPhotoItems,
                    maxSelectionCount: max(1, 20 - pendingImages.count),
                    matching: .images
                ) {
                    Group {
                        if isImportingImages {
                            ProgressView().controlSize(.small)
                        } else {
                            Image(systemName: "photo.on.rectangle.angled")
                                .font(.system(size: 16, weight: .semibold))
                        }
                    }
                    .frame(width: 32, height: 32)
                    .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .disabled(!store.supportsImages || pendingImages.count >= 20 || isImportingImages)
                .accessibilityLabel(store.supportsImages ? String(localized: "添加图片") : String(localized: "当前网关不支持图片"))

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
                .accessibilityLabel(String(localized: "a11y.context.percent", defaultValue: "上下文已用 \(Int(contextProgress * 100))%"))
                .popover(isPresented: $showsContextUsage, arrowEdge: .bottom) {
                    ContextUsagePopover(snapshot: store.selectedContextSnapshot)
                        .presentationCompactAdaptation(.popover)
                }

                Button {
                    let content = draft
                    let images = pendingImages
                    guard store.send(content, images: images) else { return }
                    draft = ""
                    pendingImages = []
                    selectedPhotoItems = []
                    composerIsFocused = false
                    // Sending establishes a new tail-following intent before
                    // the gateway echoes the user event. The subsequent user
                    // bubble and first reasoning block can therefore grow
                    // above the composer without ever being hidden behind it.
                    viewportScrollToBottomToken &+= 1
                } label: {
                    Image(systemName: "arrow.up")
                        .font(.system(size: 17, weight: .bold))
                        .foregroundStyle(.white)
                        .frame(width: 42, height: 42)
                        .background(DSHColor.ocean, in: Circle())
                }
                .buttonStyle(.plain)
                .disabled(!composerHasContent || store.waitingForNewSession || isImportingImages)
                .opacity(composerHasContent ? 1 : 0.48)
            }
        }
        .padding(.horizontal, 14)
        .padding(.top, 14)
        .padding(.bottom, 12)
        .glassSurface(radius: 24, tint: glassTint)
        .overlay {
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .stroke(glassEdge, lineWidth: 0.7)
        }
        .shadow(color: glassShadow, radius: 18, y: 8)
        .padding(.horizontal, 14)
        .padding(.bottom, composerBottomPadding)
        .onChange(of: selectedPhotoItems) { _, items in
            guard !items.isEmpty else { return }
            Task { await importPhotos(items) }
        }
    }

    private var composerHasContent: Bool {
        !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !pendingImages.isEmpty
    }

    private var pendingImageStrip: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(pendingImages) { image in
                    ZStack(alignment: .topTrailing) {
                        if let uiImage = UIImage(data: image.data) {
                            let previewSize = pendingImagePreviewSize(for: uiImage)
                            Image(uiImage: uiImage)
                                .resizable()
                                .scaledToFit()
                                .frame(width: previewSize.width, height: previewSize.height)
                                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                        }
                        Button {
                            pendingImages.removeAll { $0.id == image.id }
                        } label: {
                            Image(systemName: "xmark.circle.fill")
                                .symbolRenderingMode(.palette)
                                .foregroundStyle(.white, .black.opacity(0.68))
                                .font(.system(size: 20))
                        }
                        .buttonStyle(.plain)
                        .offset(x: 5, y: -5)
                        .accessibilityLabel("移除图片")
                    }
                    .padding(.top, 5)
                    .padding(.trailing, 5)
                }
            }
        }
        .frame(height: 82)
    }

    private func pendingImagePreviewSize(for image: UIImage) -> CGSize {
        let sourceSize = image.size
        guard sourceSize.width > 0, sourceSize.height > 0 else {
            return CGSize(width: 72, height: 72)
        }
        let scale = min(72 / sourceSize.width, 72 / sourceSize.height)
        return CGSize(
            width: max(1, sourceSize.width * scale),
            height: max(1, sourceSize.height * scale)
        )
    }

    @MainActor
    private func importPhotos(_ items: [PhotosPickerItem]) async {
        isImportingImages = true
        defer {
            isImportingImages = false
            selectedPhotoItems = []
        }
        var imported: [GatewayOutgoingImage] = []
        for item in items.prefix(max(0, 20 - pendingImages.count)) {
            do {
                guard let data = try await item.loadTransferable(type: Data.self),
                      let mediaType = Self.imageMediaType(for: data) else {
                    store.lastError = String(localized: "所选图片不是受支持的 PNG、JPEG、WebP 或 GIF。")
                    continue
                }
                let prepared = try await Task.detached(priority: .userInitiated) {
                    try GatewayImagePreprocessor.prepare(data: data, mediaType: mediaType)
                }.value
                imported.append(GatewayOutgoingImage(mediaType: prepared.mediaType, data: prepared.data))
            } catch {
                store.lastError = String(localized: "image.process.failed", defaultValue: "处理所选图片失败：\(error.localizedDescription)")
            }
        }
        let existingBytes = pendingImages.reduce(0) { $0 + $1.data.count }
        var acceptedBytes = existingBytes
        for image in imported where !pendingImages.contains(where: { $0.mediaType == image.mediaType && $0.data == image.data }) {
            guard acceptedBytes + image.data.count <= 100 * 1024 * 1024 else {
                store.lastError = String(localized: "一条消息中的图片总大小不能超过 100 MiB。")
                break
            }
            pendingImages.append(image)
            acceptedBytes += image.data.count
        }
    }

    private static func imageMediaType(for data: Data) -> String? {
        let bytes = [UInt8](data.prefix(12))
        if bytes.starts(with: [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]) { return "image/png" }
        if bytes.starts(with: [0xFF, 0xD8, 0xFF]) { return "image/jpeg" }
        if bytes.count >= 6,
           String(bytes: bytes.prefix(6), encoding: .ascii).map({ $0 == "GIF87a" || $0 == "GIF89a" }) == true { return "image/gif" }
        if bytes.count >= 12,
           String(bytes: bytes[0..<4], encoding: .ascii) == "RIFF",
           String(bytes: bytes[8..<12], encoding: .ascii) == "WEBP" { return "image/webp" }
        return nil
    }

    private var composerBottomPadding: CGFloat {
        // Keep the overlay's measured height stable across focus changes.
        // SwiftUI already avoids the keyboard because only the container safe
        // area is ignored; this inset preserves a comfortable visual gap both
        // above the Home Indicator and above the keyboard.
        bottomSafeAreaInset + 10
    }

    private var composerBackdrop: some View {
        ZStack {
            Rectangle()
                .fill(.ultraThinMaterial)
            LinearGradient(
                colors: [
                    conversationBackground.opacity(0.02),
                    conversationBackground.opacity(0.78),
                    conversationBackground
                ],
                startPoint: .top,
                endPoint: .bottom
            )
        }
        .mask {
            LinearGradient(
                stops: [
                    .init(color: .clear, location: 0),
                    .init(color: .black.opacity(0.58), location: 0.34),
                    .init(color: .black, location: 0.68)
                ],
                startPoint: .top,
                endPoint: .bottom
            )
        }
    }

    private func sessionStatsBanner(_ snapshot: GatewaySessionStatsSnapshot) -> some View {
        HStack(spacing: 0) {
            Spacer(minLength: 0)
            Button {
                viewportProxy.prepareForOverlayPresentation()
                showsSessionStatsPopover = true
            } label: {
                HStack(spacing: 7) {
                    Text(SessionStatsFormatter.turnsStepsLine(snapshot.stats) ?? String(localized: "正在读取会话统计…"))
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .truncationMode(.tail)
                    Image(systemName: "chevron.up")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(.tertiary)
                }
                .contentShape(Rectangle())
                .padding(.horizontal, 16)
                .padding(.vertical, 9)
            }
            .buttonStyle(.plain)
            .glassSurface(radius: 16, tint: glassTint.opacity(0.88))
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .stroke(glassEdge.opacity(0.9), lineWidth: 0.6)
            }
            .accessibilityLabel("查看会话执行状态")
            .accessibilityValue(SessionStatsFormatter.compactLine(snapshot))
            .popover(isPresented: $showsSessionStatsPopover, arrowEdge: .bottom) {
                SessionStatsPopover(snapshot: snapshot) {
                    showsSessionStatsPopover = false
                    showsSessionStats = true
                }
                .presentationCompactAdaptation(.popover)
            }
        }
        .padding(.horizontal, 14)
        .padding(.bottom, 6)
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
            .accessibilityLabel(String(localized: "a11y.defaults.permission", defaultValue: "新会话默认权限：\(store.permissionDefault.map(permissionTitle) ?? String(localized: "未读取"))"))
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
            .accessibilityLabel(
                String(
                    localized: "a11y.defaults.model",
                    defaultValue: "新会话默认模型：\(defaultModelTitle)\(defaultModelEffortTitle.map { String(localized: "defaults.effort.suffix", defaultValue: "，推理等级 \($0)") } ?? "")"
                )
            )
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
                // Keep model selection in one native menu. Presenting a
                // submenu before the outer Menu finishes its animation makes
                // UIKit resolve the child against a transient anchor, which
                // intermittently offsets and clips it near the composer.
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
                if !currentEfforts.isEmpty {
                    Section("推理等级") {
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
        guard let selection = store.defaultModelSelection else { return String(localized: "默认模型") }
        return modelTitle(for: selection.model)
    }
    private var defaultModelEffortTitle: String? {
        store.defaultModelSelection?.reasoningEffort.map(reasoningEffortTitle)
    }
    private var defaultAgentPresetTitle: String {
        guard let id = store.agentPresetDefault else { return String(localized: "默认 Agent") }
        return agentPresetTitle(id)
    }
    private func agentPresetTitle(_ id: String) -> String {
        if let preset = store.agentPresets.first(where: { $0.id == id }) {
            return preset.displayName
        }
        return L10n.presetModeName(for: id)
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
        L10n.permissionName(for: value)
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

    private var supplementalViewportEntries: [ConversationViewportEntry] {
        var hasher = Hasher()
        hasher.combine(isLoadingSelectedHistory)
        if let sessionID = store.selectedSessionId {
            hasher.combine(store.historyHasMore[sessionID])
            hasher.combine(store.historyLoadProgress[sessionID]?.loaded)
            hasher.combine(store.historyLoadProgress[sessionID]?.total)
        }
        let revision = hasher.finalize()
        var entries: [ConversationViewportEntry] = []
        if !conversationItems.isEmpty, isLoadingSelectedHistory {
            entries.append(.init(id: "history-loading", revision: revision, content: AnyView(historyLoadingBanner)))
        }
        if !isLoadingSelectedHistory,
           let sessionID = store.selectedSessionId,
           store.historyHasMore[sessionID] == true {
            entries.append(.init(
                id: "history-more",
                revision: revision,
                content: AnyView(
                    Button("加载更早记录") { store.loadHistory(for: sessionID, older: true) }
                        .font(.caption)
                        .frame(maxWidth: .infinity)
                )
            ))
        }
        return entries
    }

    private func makeConversationViewportEntries(from items: [ConversationItem]) -> [ConversationViewportEntry] {
        ConversationDisplayEntry.make(from: items).map { entry in
            let revision = viewportEntryRevision(entry)
            if case .message(let item) = entry.content,
               item.kind == .assistant,
               item.title == L10n.streamingAssistantTitle {
                return ConversationViewportEntry(
                    id: entry.id,
                    revision: revision,
                    streamingAssistant: .init(title: item.title, text: item.text)
                )
            }
            if case .message(let item) = entry.content,
               item.kind == .user {
                return ConversationViewportEntry(
                    id: entry.id,
                    revision: revision,
                    userMessage: .init(
                        text: item.text,
                        images: item.images.map { image in
                            .init(
                                id: image.id,
                                data: store.imageData(for: image.id),
                                width: image.width,
                                height: image.height,
                                name: image.name
                            )
                        },
                        showsCopyButton: entry.showsCopyButton
                    )
                )
            }
            let content: AnyView
            let allowsHeightCaching: Bool
            switch entry.content {
            case .message(let item):
                content = AnyView(ConversationRow(
                    item: item,
                    showsCopyButton: entry.showsCopyButton,
                    imageData: { store.imageData(for: $0) }
                ))
                // Fenced code uses a horizontal ScrollView. Its vertical ideal
                // size is stable after layout, but not necessarily during the
                // first UIHostingConfiguration measurement. Let UIKit measure
                // these rows live instead of replaying a premature short cache.
                allowsHeightCaching = !MarkdownViewportSizing.requiresLiveMeasurement(item.text)
            case .process(let group):
                content = AnyView(
                    ConversationProcessRow(group: group)
                        .environment(\.conversationDisclosureWillToggle) {
                            viewportProxy.invalidateHeight(for: entry.id)
                        }
                )
                // Collapsed process rows are immutable while scrolling. Cache
                // their settled height just like completed Markdown rows; the
                // environment callback evicts and remeasures only this item
                // when a nested disclosure is explicitly toggled.
                allowsHeightCaching = true
            }
            return ConversationViewportEntry(
                id: entry.id,
                revision: revision,
                content: content,
                allowsHeightCaching: allowsHeightCaching
            )
        }
    }

    private func viewportEntryRevision(_ entry: ConversationDisplayEntry) -> Int {
        var hasher = Hasher()
        hasher.combine(entry.id)
        hasher.combine(entry.showsCopyButton)
        switch entry.content {
        case .message(let item):
            hasher.combine(item.id)
            hasher.combine(item.title)
            hasher.combine(item.text)
            hasher.combine(item.isError)
            for image in item.images {
                hasher.combine(image)
                hasher.combine(store.imageData(for: image.id) != nil)
            }
        case .process(let group):
            for item in group.items {
                hasher.combine(item.id)
                hasher.combine(item.title)
                // A collapsed process header does not render the growing
                // reasoning body. Reconfiguring its hosted SwiftUI cell for
                // every reasoning token repeatedly invalidates neighboring
                // self-sized rows and causes visible overlap/flicker. The
                // completed reasoning item receives a new id and refreshes
                // the row once with its final text.
                if !(item.kind == .reasoning && item.title == L10n.streamingReasoningTitle) {
                    hasher.combine(item.text)
                }
                hasher.combine(item.isError)
            }
        }
        return hasher.finalize()
    }
}

/// Keeps both session pages mounted and only changes their horizontal offset.
/// Unlike a page-styled `TabView`, this container does not virtualize the
/// off-screen page, so UIKit viewport state and trajectory projection state
/// survive every tab switch.
private struct PersistentSessionPager<ConversationPage: View, TrajectoryPage: View>: View {
    @Binding var selection: Int
    private let conversationPage: ConversationPage
    private let trajectoryPage: TrajectoryPage
    @State private var dragTranslation: CGFloat = 0
    @State private var isPageSwipeActive = false
    @State private var dragAxis: SessionPageDragAxis?

    init(
        selection: Binding<Int>,
        @ViewBuilder conversation: () -> ConversationPage,
        @ViewBuilder trajectory: () -> TrajectoryPage
    ) {
        _selection = selection
        conversationPage = conversation()
        trajectoryPage = trajectory()
    }

    var body: some View {
        GeometryReader { geometry in
            let pageWidth = max(geometry.size.width, 1)

            HStack(spacing: 0) {
                conversationPage
                    .frame(width: pageWidth, height: geometry.size.height)
                    .allowsHitTesting(!isPageSwipeActive)

                trajectoryPage
                    .frame(width: pageWidth, height: geometry.size.height)
                    .allowsHitTesting(!isPageSwipeActive)
            }
            .frame(width: pageWidth * 2, alignment: .leading)
            .offset(x: -CGFloat(selection) * pageWidth + constrainedDrag)
            .contentShape(Rectangle())
            .simultaneousGesture(
                pageGesture(
                    pageWidth: pageWidth,
                    pagerFrame: geometry.frame(in: .global)
                )
            )
        }
        .clipped()
    }

    private var constrainedDrag: CGFloat {
        let isPastLeadingEdge = selection == 0 && dragTranslation > 0
        let isPastTrailingEdge = selection == 1 && dragTranslation < 0
        return isPastLeadingEdge || isPastTrailingEdge
            ? dragTranslation * 0.18
            : dragTranslation
    }

    private func pageGesture(pageWidth: CGFloat, pagerFrame: CGRect) -> some Gesture {
        DragGesture(minimumDistance: 12, coordinateSpace: .local)
            .onChanged { value in
                if dragAxis == nil {
                    let isSystemBackGesture = value.startLocation.x <= 28
                        && value.translation.width > 0
                    let globalStart = CGPoint(
                        x: pagerFrame.minX + value.startLocation.x,
                        y: pagerFrame.minY + value.startLocation.y
                    )
                    if NestedHorizontalScrollResolver.containsScrollableView(at: globalStart) {
                        // Code blocks own the complete touch sequence. The
                        // pager must not reinterpret the same pan as a tab
                        // transition when the finger reaches the cell edge.
                        dragAxis = .nestedHorizontalScroll
                    } else if isSystemBackGesture {
                        // The NavigationStack's native edge-pop gesture owns
                        // this region. Keeping the pager inert here prevents a
                        // right swipe from moving the pages underneath the
                        // interactive navigation transition.
                        dragAxis = .navigationBack
                    } else {
                        dragAxis = abs(value.translation.width) > abs(value.translation.height)
                            ? .horizontal
                            : .vertical
                    }
                }
                guard dragAxis == .horizontal else { return }
                dragTranslation = value.translation.width
                isPageSwipeActive = true
            }
            .onEnded { value in
                let predicted = value.predictedEndTranslation
                let isHorizontal = isPageSwipeActive
                    && abs(predicted.width) > abs(predicted.height)
                let threshold = min(90, pageWidth * 0.18)
                let target: Int

                if isHorizontal, predicted.width < -threshold {
                    target = 1
                } else if isHorizontal, predicted.width > threshold {
                    target = 0
                } else {
                    target = selection
                }

                withAnimation(.snappy(duration: 0.28)) {
                    selection = target
                    dragTranslation = 0
                }

                // Keep descendant controls disabled until the touch-up event
                // has finished propagating. Otherwise a trajectory row's
                // Button can interpret the end of the page swipe as a tap.
                DispatchQueue.main.async {
                    isPageSwipeActive = false
                    dragAxis = nil
                }
            }
    }
}

private enum SessionPageDragAxis {
    case horizontal
    case vertical
    case navigationBack
    case nestedHorizontalScroll
}

/// SwiftUI's nested horizontal ScrollView is backed by UIScrollView even when
/// it lives inside a UIHostingConfiguration collection cell. Resolve gesture
/// ownership from the actual hit-test chain at touch-down: a child that has
/// horizontal overflow wins; otherwise the session pager may switch tabs.
@MainActor
private enum NestedHorizontalScrollResolver {
    static func containsScrollableView(at globalPoint: CGPoint) -> Bool {
        guard let window = keyWindow else { return false }
        let point = window.convert(globalPoint, from: nil)
        var hitView = window.hitTest(point, with: nil)

        while let view = hitView {
            if let scrollView = view as? UIScrollView,
               scrollView.isScrollEnabled,
               scrollView.contentSize.width > scrollView.bounds.width + 1 {
                return true
            }
            hitView = view.superview
        }
        return false
    }

    private static var keyWindow: UIWindow? {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first(where: \.isKeyWindow)
    }
}

private struct ComposerHeightPreferenceKey: PreferenceKey {
    static var defaultValue: CGFloat = 168

    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = nextValue()
    }
}

/// Reads the physical window inset even when the SwiftUI conversation surface
/// deliberately draws through the container safe area. This keeps glass and
/// shadows continuous while the interactive composer still avoids the Home
/// Indicator on every device shape.
private struct WindowBottomSafeAreaReader: UIViewRepresentable {
    @Binding var bottomInset: CGFloat

    func makeUIView(context: Context) -> SafeAreaReportingView {
        let view = SafeAreaReportingView()
        view.onBottomInsetChange = publish
        return view
    }

    func updateUIView(_ view: SafeAreaReportingView, context: Context) {
        view.onBottomInsetChange = publish
        view.publishBottomInset()
    }

    private func publish(_ inset: CGFloat) {
        guard abs(bottomInset - inset) > 0.5 else { return }
        DispatchQueue.main.async {
            bottomInset = inset
        }
    }

    final class SafeAreaReportingView: UIView {
        var onBottomInsetChange: ((CGFloat) -> Void)?

        override func didMoveToWindow() {
            super.didMoveToWindow()
            publishBottomInset()
        }

        override func safeAreaInsetsDidChange() {
            super.safeAreaInsetsDidChange()
            publishBottomInset()
        }

        func publishBottomInset() {
            let inset = window?.safeAreaInsets.bottom ?? safeAreaInsets.bottom
            onBottomInsetChange?(inset)
        }
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
        if let turnsStepsLine = turnsStepsLine(stats) {
            sections.append(turnsStepsLine)
        }
        let timings = [
            stats?.llmMs.map { "LLM \(duration($0))" },
            stats?.toolMs.map { String(localized: "stats.toolcalls.duration", defaultValue: "工具调用 \(duration($0))") }
        ].compactMap { $0 }.joined(separator: " · ")
        if !timings.isEmpty { sections.append(timings) }
        let averageTTFT = averageTTFT(stats)
        let throughput = throughput(stats)
        let performance = [
            averageTTFT.map { String(localized: "stats.ttft.avg", defaultValue: "首 token 平均 \(duration($0))") },
            throughput.map { "\(compactDecimal($0)) tok/s" }
        ].compactMap { $0 }.joined(separator: " · ")
        if !performance.isEmpty { sections.append(performance) }
        if let cacheRate = cacheHitRate(snapshot.tokenUsage?.totals) {
            sections.append(String(localized: "stats.cachehit.percent", defaultValue: "缓存命中 \(Int((cacheRate * 100).rounded()))%"))
        }
        if let input = snapshot.tokenUsage?.totals?.inputTokens {
            sections.append(String(localized: "stats.input.tok", defaultValue: "输入 \(compact(input)) tok"))
        }
        return sections.isEmpty ? String(localized: "正在读取会话统计…") : sections.joined(separator: "  |  ")
    }

    static func turnsStepsLine(_ stats: GatewaySessionStats?) -> String? {
        guard stats?.turns != nil || stats?.steps != nil else { return nil }
        return String(localized: "stats.turns.steps.line", defaultValue: "\(stats?.turns ?? 0) 轮 · \(stats?.steps ?? 0) 步")
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
                        metricsSection(String(localized: "执行")) {
                            metric(String(localized: "轮次"), String(localized: "metric.turns.value", defaultValue: "\(stats.turns ?? 0) 轮"))
                            metric(String(localized: "步骤"), String(localized: "metric.steps.value", defaultValue: "\(stats.steps ?? 0) 步"))
                            metric("LLM", stats.llmMs.map(SessionStatsFormatter.duration) ?? "—")
                            metric(String(localized: "工具调用"), stats.toolMs.map(SessionStatsFormatter.duration) ?? "—")
                            metric(String(localized: "首 token 平均"), SessionStatsFormatter.averageTTFT(stats).map(SessionStatsFormatter.duration) ?? "—")
                            metric(String(localized: "解码吞吐"), SessionStatsFormatter.throughput(stats).map { String(localized: "value.throughput", defaultValue: "\(SessionStatsFormatter.compactDecimal($0)) tok/s") } ?? "—")
                        }

                        if let totals = snapshot.tokenUsage?.totals {
                            metricsSection(String(localized: "Token 用量")) {
                                metric(String(localized: "输入"), token(totals.inputTokens))
                                metric(String(localized: "输出"), token(totals.outputTokens))
                                metric(String(localized: "缓存读取"), token(totals.cacheReadTokens))
                                metric(String(localized: "缓存写入"), token(totals.cacheWriteTokens))
                                metric(String(localized: "推理"), token(totals.reasoningTokens))
                                metric(String(localized: "缓存命中"), SessionStatsFormatter.cacheHitRate(totals).map { String(localized: "value.percent", defaultValue: "\(Int(($0 * 100).rounded()))%") } ?? "—")
                            }
                        }

                        if let pressure = snapshot.contextPressure {
                            metricsSection(String(localized: "上下文")) {
                                metric(String(localized: "当前压力"), token(pressure.pressureTokens))
                                metric(String(localized: "预计用量"), token(pressure.projectedTokens))
                                metric(String(localized: "上下文窗口"), token(pressure.contextWindow))
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

private struct SessionStatsPopover: View {
    let snapshot: GatewaySessionStatsSnapshot
    let onViewFullStats: () -> Void

    private var stats: GatewaySessionStats? { snapshot.stats }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let turnsSteps = SessionStatsFormatter.turnsStepsLine(stats) {
                row(String(localized: "轮次 · 步骤"), turnsSteps)
            }
            if let llm = stats?.llmMs {
                row("LLM", SessionStatsFormatter.duration(llm))
            }
            if let tool = stats?.toolMs {
                row(String(localized: "工具调用"), SessionStatsFormatter.duration(tool))
            }
            if let ttft = SessionStatsFormatter.averageTTFT(stats) {
                row(String(localized: "首 token 平均"), SessionStatsFormatter.duration(ttft))
            }
            if let throughput = SessionStatsFormatter.throughput(stats) {
                row(String(localized: "解码吞吐"), String(localized: "value.throughput", defaultValue: "\(SessionStatsFormatter.compactDecimal(throughput)) tok/s"))
            }
            if let cacheRate = SessionStatsFormatter.cacheHitRate(snapshot.tokenUsage?.totals) {
                row(String(localized: "缓存命中"), String(localized: "value.percent", defaultValue: "\(Int((cacheRate * 100).rounded()))%"))
            }
            if let input = snapshot.tokenUsage?.totals?.inputTokens {
                row(String(localized: "输入"), String(localized: "row.input.value", defaultValue: "\(SessionStatsFormatter.compact(input)) tok"))
            }

            Divider()

            Button(action: onViewFullStats) {
                HStack(spacing: 6) {
                    Text("查看完整统计")
                    Spacer()
                }
                .font(.subheadline.weight(.medium))
                .foregroundStyle(.primary)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 22)
        .padding(.vertical, 16)
        .frame(width: 300)
        .presentationBackground(.ultraThinMaterial)
    }

    private func row(_ title: String, _ value: String) -> some View {
        HStack {
            Text(title).foregroundStyle(.secondary)
            Spacer()
            Text(value).monospacedDigit().fontWeight(.medium)
        }
        .font(.subheadline)
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
                usageRow(String(localized: "系统提示词"), value: breakdown.systemTokens, color: .gray)
                usageRow(String(localized: "usage.row.tools", defaultValue: "工具"), value: breakdown.toolsTokens, color: DSHColor.purple)
                usageRow(String(localized: "对话消息"), value: breakdown.messageTokens, color: DSHColor.ocean)
            } else if let usage = snapshot?.tokenUsage {
                usageRow(String(localized: "未缓存输入"), value: usage.uncachedInputTokens, color: DSHColor.ocean)
                usageRow(String(localized: "缓存读取"), value: usage.cacheReadTokens, color: DSHColor.purple)
                usageRow(String(localized: "模型输出"), value: usage.outputTokens, color: DSHColor.orange)
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
            return group.contextCount == 1 ? String(localized: "上下文") : String(localized: "context.items.count", defaultValue: "\(group.contextCount) 项上下文")
        }
        let duration = group.duration
        let base: String
        if duration >= 60 {
            base = String(localized: "duration.minutes-seconds", defaultValue: "耗时 \(Int(duration) / 60) 分钟 \(Int(duration) % 60) 秒")
        } else if duration >= 1 {
            base = String(localized: "duration.seconds", defaultValue: "耗时 \(Int(duration.rounded())) 秒")
        } else {
            base = String(localized: "思考过程")
        }
        var details: [String] = []
        if group.contextCount > 0 { details.append(String(localized: "context.items.count", defaultValue: "\(group.contextCount) 项上下文")) }
        if group.toolCount > 0 { details.append(String(localized: "toolcall.count", defaultValue: "\(group.toolCount) 次工具调用")) }
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
        return names.isEmpty ? String(localized: "tools.view-results", defaultValue: "查看 \(tools.count) 个工具结果") : String(localized: "tools.used.summary", defaultValue: "使用了 \(names)\(tools.count > 2 ? String(localized: " 等工具") : "")")
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
                    Text(tool.call?.title ?? tool.result?.title ?? String(localized: "trajectory.tool.fallback", defaultValue: "工具"))
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                    if let result = tool.result {
                        Text(result.isError ? String(localized: "失败") : String(localized: "完成"))
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
                        Text(result.isError ? String(localized: "错误") : String(localized: "结果"))
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

private struct AttachmentImageGrid: View {
    let attachments: [GatewayImageAttachment]
    let imageData: (String) -> Data?

    private var columns: [GridItem] {
        Array(
            repeating: GridItem(.flexible(), spacing: 6),
            count: attachments.count == 1 ? 1 : 2
        )
    }

    var body: some View {
        LazyVGrid(columns: columns, alignment: .trailing, spacing: 6) {
            ForEach(attachments) { attachment in
                Group {
                    if let data = imageData(attachment.id), let image = UIImage(data: data) {
                        Image(uiImage: image)
                            .resizable()
                            .scaledToFill()
                    } else {
                        ZStack {
                            Color(uiColor: .secondarySystemFill)
                            ProgressView().controlSize(.small)
                        }
                    }
                }
                .aspectRatio(
                    attachment.height > 0 ? CGFloat(attachment.width) / CGFloat(attachment.height) : 1,
                    contentMode: .fit
                )
                .frame(maxWidth: attachments.count == 1 ? 320 : 180, maxHeight: 280)
                .clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: 11, style: .continuous)
                        .stroke(.white.opacity(0.16), lineWidth: 0.7)
                }
                .accessibilityLabel(attachment.name ?? String(localized: "图片附件"))
            }
        }
    }
}

private struct ConversationRow: View {
    let item: ConversationItem
    let showsCopyButton: Bool
    let imageData: (String) -> Data?
    @State private var expanded = false
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        switch item.kind {
        case .user:
            VStack(alignment: .trailing, spacing: 5) {
                VStack(alignment: .trailing, spacing: 8) {
                    if !item.images.isEmpty {
                        AttachmentImageGrid(attachments: item.images, imageData: imageData)
                    }
                    if !item.text.isEmpty {
                        Text(item.text).font(.body)
                    }
                }
                .padding(.horizontal, 10).padding(.vertical, 10)
                .background(userBubbleFill, in: RoundedRectangle(cornerRadius: 15, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: 15, style: .continuous)
                        .stroke(userBubbleEdge, lineWidth: 0.7)
                }
                if showsCopyButton && !item.text.isEmpty { CopyMessageButton(text: item.text) }
            }
            // Long pasted payloads should remain a trailing chat bubble, not
            // expand edge-to-edge. This fixed inset also removes a variable
            // width input from self-sizing while the list is being dragged.
            .padding(.leading, 34)
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
                    DeepSeekWhaleIcon(size: 26).foregroundStyle(.primary)
                    Text(item.title)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.secondary)
                }
                .padding(.horizontal, 2)
                .padding(.vertical, 5)
                if !item.images.isEmpty {
                    AttachmentImageGrid(attachments: item.images, imageData: imageData)
                }
                if !item.text.isEmpty {
                    MarkdownContent(item.text)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                if showsCopyButton && !item.text.isEmpty { CopyMessageButton(text: item.text) }
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
                rendersOutput: item.title == L10n.toolResultDoneTitle || item.title == L10n.toolResultFailedTitle
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

    private var userBubbleFill: Color {
        DSHColor.ocean.opacity(colorScheme == .dark ? 0.24 : 0.11)
    }

    private var userBubbleEdge: Color {
        DSHColor.ocean.opacity(colorScheme == .dark ? 0.34 : 0.08)
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
                .frame(width: 16, height: 26)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(copied ? String(localized: "已复制") : String(localized: "复制正文"))
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
        Markdown(ParsedMarkdownCache.document(for: markdownSource))
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

/// Completed messages are immutable, but collection-view reuse can bring the
/// same message on screen many times. Reuse MarkdownUI's parsed block tree so
/// scrolling does not repeatedly parse long responses on the main thread.
private enum ParsedMarkdownCache {
    private final class Box: NSObject {
        let document: MarkdownUI.MarkdownContent

        init(_ document: MarkdownUI.MarkdownContent) {
            self.document = document
        }
    }

    private static let cache: NSCache<NSString, Box> = {
        let cache = NSCache<NSString, Box>()
        cache.countLimit = 256
        cache.totalCostLimit = 16 * 1_024 * 1_024
        return cache
    }()

    static func document(for source: String) -> MarkdownUI.MarkdownContent {
        let key = source as NSString
        if let cached = cache.object(forKey: key) {
            return cached.document
        }
        let document = MarkdownUI.MarkdownContent(source)
        cache.setObject(Box(document), forKey: key, cost: source.utf8.count)
        return document
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
                            .fixedSize(horizontal: true, vertical: true)
                            .markdownTextStyle {
                                FontFamilyVariant(.monospaced)
                                FontSize(compact ? 11 : 13)
                            }
                    }
                    // A horizontal ScrollView has no useful UIKit intrinsic
                    // height of its own. Preserve the code content's ideal
                    // vertical size when the hosting cell is self-sized.
                    .fixedSize(horizontal: false, vertical: true)
                }
                .padding(compact ? 10 : 12)
                .background(Color(uiColor: .secondarySystemFill))
                .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
                .markdownMargin(top: 2, bottom: compact ? 6 : 10)
            }
            .thematicBreak {
                Rectangle()
                    .fill(Color(uiColor: .separator).opacity(0.55))
                    .frame(height: 0.5)
                    .markdownMargin(
                        top: compact ? 10 : 18,
                        bottom: compact ? 10 : 18
                    )
            }
    }
}

private enum MarkdownViewportSizing {
    private static let fencedCode = try! NSRegularExpression(
        pattern: #"(?m)^[\t ]{0,3}(?:```|~~~)"#
    )

    static func requiresLiveMeasurement(_ source: String) -> Bool {
        let range = NSRange(location: 0, length: (source as NSString).length)
        return fencedCode.firstMatch(in: source, range: range) != nil
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
