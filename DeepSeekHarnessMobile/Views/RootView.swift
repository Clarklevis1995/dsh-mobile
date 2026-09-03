import SwiftUI
import QuickLook
import UIKit
import DeepSeekHarnessShared

struct RootView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        RootNavigationHost(store: store)
            .equatable()
            .alert("DeepSeek Harness", isPresented: Binding(get: { store.lastError != nil }, set: { if !$0 { store.lastError = nil } })) {
                Button(String(localized: "好"), role: .cancel) { store.lastError = nil }
            } message: { Text(store.lastError ?? "") }
            .onChange(of: scenePhase) { _, phase in
                store.handleScenePhase(phase)
            }
    }
}

/// The navigation tree deliberately holds `AppStore` as an unobserved
/// reference. `RootView` can still present global errors, while session/history
/// publications no longer invalidate the `NavigationStack` that owns the bar.
private struct RootNavigationHost: View, Equatable {
    let store: AppStore
    @State private var navigationPath: [AppRoute] = []

    static func == (lhs: Self, rhs: Self) -> Bool {
        lhs.store === rhs.store
    }

    var body: some View {
        NavigationStack(path: $navigationPath) {
            WorkspaceView(
                onOpenSession: { session in
                    let header = conversationHeader(for: session)
                    Task { @MainActor in
                        guard await store.prepareConversation(for: session),
                              !Task.isCancelled else { return }
                        navigate(to: .conversation(header))
                    }
                },
                onNewSession: {
                    let header = conversationHeader(for: nil)
                    Task { @MainActor in
                        guard await store.prepareNewConversation(),
                              !Task.isCancelled else { return }
                        navigate(to: .conversation(header))
                    }
                },
                onSettings: {
                    navigate(to: .settings)
                }
            )
                .navigationDestination(for: AppRoute.self) { route in
                    destination(for: route)
                }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .task {
            if case .disconnected = store.gateway.state {
                store.connectOnColdLaunchIfPaired()
            }
        }
        .onChange(of: navigationPath) { _, path in
            if path.isEmpty { store.resumeWorkspace() }
        }
    }

    @ViewBuilder
    private func destination(for route: AppRoute) -> some View {
        switch route {
        case .conversation(let header):
            ConversationNavigationShell(
                header: header,
                gateway: store.gateway,
                store: store,
                onReloadHistory: {
                    if let id = header.sessionID ?? store.selectedSessionId {
                        store.loadHistory(for: id)
                    }
                },
                onPing: {
                    store.gateway.ping()
                },
                onActivate: {
                    await store.activatePreparedConversation(sessionID: header.sessionID)
                }
            ) {
                ConversationView()
            }
        case .settings:
            SettingsView()
        }
    }

    /// Resolves the small amount of route chrome before the push begins.
    /// Conversation content can then load and stream independently without
    /// participating in navigation-bar preference resolution.
    private func conversationHeader(for session: SessionSummary?) -> ConversationNavigationHeader {
        let presetID = session?.agentPreset ?? store.agentPresetDefault
        return ConversationNavigationHeader(
            sessionID: session?.id,
            title: session?.title ?? String(localized: "session.new.fallback", defaultValue: "新建 DeepSeek Harness"),
            agentPresetTitle: agentPresetDisplayName(for: presetID)
        )
    }

    private func agentPresetDisplayName(for id: String?) -> String {
        guard let id else { return "Agent" }
        if let preset = store.agentPresets.first(where: { $0.id == id }) {
            return preset.displayName
        }
        return L10n.presetModeName(for: id)
    }

    private func navigate(to route: AppRoute) {
        guard navigationPath.last != route else { return }
        navigationPath.append(route)
    }

    private enum AppRoute: Hashable {
        case conversation(ConversationNavigationHeader)
        case settings
    }
}

/// Immutable route chrome. Keeping this separate from `AppStore` is what makes
/// the title and trailing items enter in the same navigation transition as the
/// system back button.
private struct ConversationNavigationHeader: Hashable {
    let sessionID: String?
    let title: String
    let agentPresetTitle: String
}

/// Owns only navigation chrome. The content below it may observe the complete
/// app store and update at WebSocket frequency without invalidating the toolbar.
private struct ConversationNavigationShell<Content: View>: View {
    let header: ConversationNavigationHeader
    let gateway: GatewayClient
    let store: AppStore
    let onReloadHistory: () -> Void
    let onPing: () -> Void
    let onActivate: () async -> Void
    @ViewBuilder let content: () -> Content
    @State private var showsWorkspaceFiles = false

    var body: some View {
        content()
            .navigationTitle(header.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbarRole(.editor)
            .toolbarBackground(.hidden, for: .navigationBar)
            .toolbar {
                if #available(iOS 26.0, *) {
                    ToolbarItem(placement: .topBarTrailing) {
                        ConversationNavigationStatus(
                            gateway: gateway,
                            agentPresetTitle: header.agentPresetTitle
                        )
                    }
                    .sharedBackgroundVisibility(.hidden)

                    ToolbarSpacer(.fixed, placement: .topBarTrailing)
                } else {
                    ToolbarItem(placement: .topBarTrailing) {
                        ConversationNavigationStatus(
                            gateway: gateway,
                            agentPresetTitle: header.agentPresetTitle
                        )
                    }
                }

                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button(String(localized: "工作区文件"), systemImage: "folder", action: {
                            showsWorkspaceFiles = true
                        })
                        .disabled(header.sessionID == nil)
                        Button(String(localized: "重新加载历史"), systemImage: "clock.arrow.circlepath", action: onReloadHistory)
                        Button(String(localized: "发送 Ping"), systemImage: "wave.3.right", action: onPing)
                    } label: {
                        Image(systemName: "ellipsis")
                    }
                }
            }
            .task(id: header.sessionID ?? "__new-conversation__") {
                // Finish the NavigationStack transaction before beginning any
                // subscription, history, or session-control work.
                await Task.yield()
                guard !Task.isCancelled else { return }
                await onActivate()
            }
            .sheet(isPresented: $showsWorkspaceFiles) {
                WorkspaceFilesSheet(store: store, sessionID: header.sessionID)
            }
    }
}

private struct WorkspaceFilesSheet: View {
    @ObservedObject var store: AppStore
    let sessionID: String?
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @State private var downloadedPaths: Set<String> = []

    var body: some View {
        NavigationStack {
            Group {
                if store.workspaceFilesAreLoading && store.workspaceFileEntries.isEmpty {
                    ProgressView("正在读取工作区文件…")
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if store.workspaceFileEntries.isEmpty {
                    ContentUnavailableView(
                        "此目录为空",
                        systemImage: "folder",
                        description: Text(store.workspaceFilePath)
                    )
                } else {
                    List(store.workspaceFileEntries) { item in
                        WorkspaceFileRow(
                            store: store,
                            item: item,
                            isDownloaded: downloadedPaths.contains(item.path)
                        )
                    }
                    .listStyle(.plain)
                    .disabled(store.workspaceFilesAreLoading)
                    .refreshable { store.browseWorkspaceFiles(path: store.workspaceFilePath) }
                }
            }
            .navigationTitle("工作区文件")
            .navigationBarTitleDisplayMode(.inline)
            .safeAreaInset(edge: .top, spacing: 0) {
                workspaceFilePathBar
            }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    if store.workspaceFilePath != "." {
                        Button("上一级", systemImage: "chevron.left") {
                            store.browseWorkspaceFiles(path: parentPath(of: store.workspaceFilePath))
                        }
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("完成") { dismiss() }
                }
            }
            .overlay(alignment: .bottom) {
                if let progress = store.workspaceFileDownloadProgress {
                    VStack(spacing: 9) {
                        ProgressView(value: progress)
                        HStack {
                            Text("正在下载 · \(Int(progress * 100))%")
                                .font(.caption.monospacedDigit())
                                .foregroundStyle(.secondary)
                            Spacer()
                            Button("取消", role: .cancel) { store.cancelWorkspaceFileDownload() }
                        }
                    }
                    .padding(16)
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                    .padding()
                }
            }
        }
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
        .task(id: sessionID) {
            guard sessionID != nil else { return }
            store.browseWorkspaceFiles()
            refreshDownloadedPaths()
        }
        .onChange(of: store.workspaceFileEntries) { _, _ in
            refreshDownloadedPaths()
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { refreshDownloadedPaths() }
        }
        .onDisappear {
            if store.workspaceFileDownloadProgress != nil { store.cancelWorkspaceFileDownload() }
        }
        .sheet(item: previewBinding) { file in
            WorkspaceQuickLookPreview(url: file.url)
                .ignoresSafeArea()
        }
        .fullScreenCover(item: codePreviewBinding) { file in
            WorkspaceCodePreview(file: file)
        }
        .sheet(item: exportBinding) { file in
            WorkspaceFileExporter(url: file.url) { localURL in
                WorkspaceDownloadRegistry.shared.record(
                    sessionID: file.sessionID,
                    remotePath: file.remotePath,
                    localURL: localURL
                )
                if file.sessionID == sessionID {
                    downloadedPaths.insert(file.remotePath)
                }
            }
        }
    }

    private var workspaceFilePathBar: some View {
        HStack(spacing: 8) {
            Image(systemName: "folder")
            Text(store.workspaceFilePath == "." ? "工作区根目录" : store.workspaceFilePath)
                .lineLimit(1)
                .truncationMode(.middle)
            Spacer()
            if store.workspaceFilesAreLoading { ProgressView().controlSize(.small) }
        }
        .font(.subheadline)
        .foregroundStyle(.secondary)
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(.thinMaterial)
    }

    private var previewBinding: Binding<WorkspaceLocalFile?> {
        Binding(
            get: {
                guard let file = store.completedWorkspaceFile,
                      file.purpose == "preview",
                      !WorkspaceCodePreviewSupport.shared.isSupported(
                        name: file.name,
                        mediaType: file.mediaType
                      ) else { return nil }
                return file
            },
            set: { if $0 == nil { store.completedWorkspaceFile = nil } }
        )
    }

    private var codePreviewBinding: Binding<WorkspaceLocalFile?> {
        Binding(
            get: {
                guard let file = store.completedWorkspaceFile,
                      file.purpose == "preview",
                      WorkspaceCodePreviewSupport.shared.isSupported(
                        name: file.name,
                        mediaType: file.mediaType
                      ) else { return nil }
                return file
            },
            set: { if $0 == nil { store.completedWorkspaceFile = nil } }
        )
    }

    private var exportBinding: Binding<WorkspaceLocalFile?> {
        Binding(
            get: { store.completedWorkspaceFile?.purpose == "download" ? store.completedWorkspaceFile : nil },
            set: { if $0 == nil { store.completedWorkspaceFile = nil } }
        )
    }

    private func parentPath(of path: String) -> String? {
        let components = path.split(separator: "/").dropLast()
        return components.isEmpty ? nil : components.joined(separator: "/")
    }

    private func refreshDownloadedPaths() {
        guard let sessionID else {
            downloadedPaths = []
            return
        }
        downloadedPaths = WorkspaceDownloadRegistry.shared.existingRemotePaths(
            sessionID: sessionID,
            remotePaths: store.workspaceFileEntries
                .filter { $0.kind == "file" }
                .map(\.path)
        )
    }
}

private struct WorkspaceFileRow: View {
    @ObservedObject var store: AppStore
    let item: GatewayDirectoryItem
    let isDownloaded: Bool

    private var isDownloading: Bool {
        store.workspaceFileDownloadPurpose == "download" &&
            store.workspaceFileDownloadPath == item.path
    }

    var body: some View {
        if item.kind == "directory" {
            Button {
                store.browseWorkspaceFiles(path: item.path)
            } label: {
                Label {
                    Text(item.name).foregroundStyle(.primary)
                } icon: {
                    Image(systemName: "folder.fill").foregroundStyle(DSHColor.ocean)
                }
            }
        } else {
            HStack(spacing: 12) {
                Button {
                    store.openWorkspaceFile(item, purpose: "preview")
                } label: {
                    HStack(spacing: 12) {
                        Image(systemName: workspaceFileIcon(item))
                            .font(.title3)
                            .foregroundStyle(DSHColor.ocean)
                            .frame(width: 28)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(item.name)
                                .foregroundStyle(.primary)
                                .lineLimit(1)
                            Text(workspaceFileDetail(item))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                        Spacer()
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                Button {
                    store.openWorkspaceFile(item, purpose: "download")
                } label: {
                    workspaceDownloadIcon
                }
                .buttonStyle(.plain)
                .disabled(isDownloading)
                .accessibilityLabel(
                    isDownloaded ? "\(item.name) 已下载，点击重新下载" : "下载 \(item.name)"
                )
            }
        }
    }

    @ViewBuilder
    private var workspaceDownloadIcon: some View {
        if isDownloading, let progress = store.workspaceFileDownloadProgress {
            ProgressView(value: progress)
                .progressViewStyle(.circular)
                .frame(width: 24, height: 24)
                .overlay {
                    Image(systemName: "arrow.down")
                        .font(.system(size: 9, weight: .semibold))
                }
        } else {
            Image(systemName: isDownloaded ? "checkmark.circle" : "arrow.down.circle")
                .font(.title3)
                .foregroundStyle(isDownloaded ? DSHColor.success : Color.primary)
        }
    }

    private func workspaceFileDetail(_ item: GatewayDirectoryItem) -> String {
        let size = item.bytes.map {
            ByteCountFormatter.string(fromByteCount: $0, countStyle: .file)
        } ?? "文件"
        guard let modifiedAt = item.modifiedAt else { return size }
        let date = Date(timeIntervalSince1970: modifiedAt / 1_000)
        return "\(size) · \(date.formatted(date: .abbreviated, time: .shortened))"
    }

    private func workspaceFileIcon(_ item: GatewayDirectoryItem) -> String {
        let type = item.mediaType ?? ""
        if type.hasPrefix("image/") { return "photo" }
        if type == "application/pdf" { return "doc.richtext" }
        if type.hasPrefix("text/") || item.name.hasSuffix(".md") { return "doc.text" }
        if item.name.hasSuffix(".ipa") || item.name.hasSuffix(".apk") { return "shippingbox" }
        return "doc"
    }
}

private struct WorkspaceQuickLookPreview: UIViewControllerRepresentable {
    let url: URL

    func makeCoordinator() -> Coordinator { Coordinator(url: url) }
    func makeUIViewController(context: Context) -> QLPreviewController {
        let controller = QLPreviewController()
        controller.dataSource = context.coordinator
        return controller
    }
    func updateUIViewController(_ controller: QLPreviewController, context: Context) {}

    final class Coordinator: NSObject, QLPreviewControllerDataSource {
        let url: URL
        init(url: URL) { self.url = url }
        func numberOfPreviewItems(in controller: QLPreviewController) -> Int { 1 }
        func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> QLPreviewItem {
            url as NSURL
        }
    }
}

private struct WorkspaceCodePreview: View {
    let file: WorkspaceLocalFile
    @Environment(\.dismiss) private var dismiss
    @State private var document: WorkspaceCodeDocument?
    @State private var errorMessage: String?
    @State private var showsSystemPreview = false

    private let maximumPreviewBytes = 2 * 1_024 * 1_024

    var body: some View {
        NavigationStack {
            Group {
                if let document {
                    WorkspaceCodeDocumentView(document: document)
                } else if let errorMessage {
                    ContentUnavailableView(
                        "无法在应用内预览",
                        systemImage: "doc.text.magnifyingglass",
                        description: Text(errorMessage)
                    )
                } else {
                    ProgressView("正在准备代码预览…")
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            .navigationTitle(file.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("关闭") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("系统打开", systemImage: "arrow.up.forward.app") {
                        showsSystemPreview = true
                    }
                }
            }
        }
        .task(id: file.id) { await loadDocument() }
        .sheet(isPresented: $showsSystemPreview) {
            WorkspaceQuickLookPreview(url: file.url)
                .ignoresSafeArea()
        }
    }

    @MainActor
    private func loadDocument() async {
        document = nil
        errorMessage = nil
        let url = file.url
        let name = file.name
        let mediaType = file.mediaType
        let maximumBytes = maximumPreviewBytes
        do {
            let loaded = try await Task.detached(priority: .userInitiated) {
                let values = try url.resourceValues(forKeys: [.fileSizeKey])
                if let size = values.fileSize, size > maximumBytes {
                    throw WorkspaceCodePreviewLoadError.fileTooLarge
                }
                let source = try String(contentsOf: url, encoding: .utf8)
                return SendableWorkspaceCodeDocument(
                    WorkspaceCodePreviewSupport.shared.prepare(
                        source: source,
                        name: name,
                        mediaType: mediaType
                    )
                )
            }.value
            guard !Task.isCancelled else { return }
            document = loaded.value
        } catch WorkspaceCodePreviewLoadError.fileTooLarge {
            errorMessage = "文件超过 2 MB，请使用系统应用打开"
        } catch {
            guard !Task.isCancelled else { return }
            errorMessage = "读取代码失败：\(error.localizedDescription)"
        }
    }
}

private enum WorkspaceCodePreviewLoadError: Error {
    case fileTooLarge
}

private struct SendableWorkspaceCodeDocument: @unchecked Sendable {
    let value: WorkspaceCodeDocument

    init(_ value: WorkspaceCodeDocument) {
        self.value = value
    }
}

private struct WorkspaceCodeDocumentView: View {
    let document: WorkspaceCodeDocument
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text(document.languageDisplayName)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(DSHColor.ocean)
                Spacer()
                Text("\(document.lineCount) 行 · UTF-8")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .padding(.horizontal, 14)
            .frame(height: 38)
            .background(Color(uiColor: .secondarySystemBackground))

            WorkspaceCodeTextKitView(document: document, colorScheme: colorScheme)
        }
    }
}

private struct WorkspaceCodeTextKitView: UIViewRepresentable {
    let document: WorkspaceCodeDocument
    let colorScheme: ColorScheme

    func makeUIView(context: Context) -> WorkspaceCodeTextKitContainer {
        let view = WorkspaceCodeTextKitContainer()
        view.configure(document: document, isDark: colorScheme == .dark)
        return view
    }

    func updateUIView(_ view: WorkspaceCodeTextKitContainer, context: Context) {
        view.configure(document: document, isDark: colorScheme == .dark)
    }
}

final class WorkspaceCodeTextKitContainer: UIView, UITextViewDelegate {
    private let gutterView = UIView()
    private let lineNumberLabel = UILabel()
    private let codeTextView = UITextView(frame: .zero, textContainer: nil)
    private let codeFont = UIFont.monospacedSystemFont(ofSize: 13, weight: .regular)
    private let lineSpacing: CGFloat = 4
    private var lineCount = 1
    private var maximumLineWidth: CGFloat = 0
    private var configuredRevision: String?

    override init(frame: CGRect) {
        super.init(frame: frame)
        clipsToBounds = true

        gutterView.translatesAutoresizingMaskIntoConstraints = false
        gutterView.clipsToBounds = true
        addSubview(gutterView)

        lineNumberLabel.numberOfLines = 0
        lineNumberLabel.textAlignment = .right
        gutterView.addSubview(lineNumberLabel)

        codeTextView.translatesAutoresizingMaskIntoConstraints = false
        codeTextView.delegate = self
        codeTextView.isEditable = false
        codeTextView.isSelectable = true
        codeTextView.isScrollEnabled = true
        codeTextView.alwaysBounceVertical = true
        codeTextView.showsVerticalScrollIndicator = true
        codeTextView.showsHorizontalScrollIndicator = true
        codeTextView.contentInsetAdjustmentBehavior = .never
        codeTextView.contentInset = .zero
        codeTextView.textContainerInset = UIEdgeInsets(top: 14, left: 14, bottom: 24, right: 28)
        codeTextView.textContainer.lineFragmentPadding = 0
        codeTextView.textContainer.widthTracksTextView = false
        codeTextView.textContainer.heightTracksTextView = false
        codeTextView.textContainer.lineBreakMode = .byClipping
        codeTextView.layoutManager.allowsNonContiguousLayout = true
        addSubview(codeTextView)

        NSLayoutConstraint.activate([
            gutterView.leadingAnchor.constraint(equalTo: leadingAnchor),
            gutterView.topAnchor.constraint(equalTo: topAnchor),
            gutterView.bottomAnchor.constraint(equalTo: bottomAnchor),
            gutterView.widthAnchor.constraint(equalToConstant: 52),
            codeTextView.leadingAnchor.constraint(equalTo: gutterView.trailingAnchor),
            codeTextView.trailingAnchor.constraint(equalTo: trailingAnchor),
            codeTextView.topAnchor.constraint(equalTo: topAnchor),
            codeTextView.bottomAnchor.constraint(equalTo: bottomAnchor)
        ])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func configure(document: WorkspaceCodeDocument, isDark: Bool) {
        let revision = "\(document.text.hashValue)-\(document.tokens.count)-\(isDark)"
        guard configuredRevision != revision else { return }
        configuredRevision = revision
        lineCount = max(1, Int(document.lineCount))

        let codeBackground = isDark
            ? UIColor(red: 0.05, green: 0.07, blue: 0.09, alpha: 1)
            : UIColor(red: 0.98, green: 0.985, blue: 0.99, alpha: 1)
        let gutterBackground = isDark
            ? UIColor(red: 0.086, green: 0.106, blue: 0.133, alpha: 1)
            : UIColor.secondarySystemBackground
        backgroundColor = codeBackground
        codeTextView.backgroundColor = codeBackground
        gutterView.backgroundColor = gutterBackground
        codeTextView.tintColor = UIColor(DSHColor.ocean)
        codeTextView.attributedText = WorkspaceCodeAttributedText.highlight(document, isDark: isDark)
        codeTextView.textContainer.widthTracksTextView = false
        codeTextView.textContainer.lineBreakMode = .byClipping

        let paragraph = NSMutableParagraphStyle()
        paragraph.lineSpacing = lineSpacing
        lineNumberLabel.attributedText = NSAttributedString(
            string: (1...lineCount).map(String.init).joined(separator: "\n"),
            attributes: [
                .font: codeFont,
                .foregroundColor: UIColor.tertiaryLabel,
                .paragraphStyle: paragraph
            ]
        )
        maximumLineWidth = document.text
            .split(separator: "\n", omittingEmptySubsequences: false)
            .reduce(CGFloat.zero) { width, line in
                max(
                    width,
                    (String(line) as NSString).size(withAttributes: [.font: codeFont]).width
                )
            }
        setNeedsLayout()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let insets = codeTextView.textContainerInset
        let visibleWidth = max(1, codeTextView.bounds.width - insets.left - insets.right)
        let containerWidth = max(visibleWidth, ceil(maximumLineWidth) + 1)
        if abs(codeTextView.textContainer.size.width - containerWidth) > 0.5 {
            codeTextView.textContainer.size = CGSize(
                width: containerWidth,
                height: CGFloat.greatestFiniteMagnitude
            )
        }
        codeTextView.textContainer.widthTracksTextView = false
        updateLineNumberPosition()
    }

    func scrollViewDidScroll(_ scrollView: UIScrollView) {
        guard scrollView === codeTextView else { return }
        updateLineNumberPosition()
    }

    private func updateLineNumberPosition() {
        let rowHeight = codeFont.lineHeight + lineSpacing
        let labelHeight = CGFloat(lineCount) * codeFont.lineHeight +
            CGFloat(max(0, lineCount - 1)) * lineSpacing
        lineNumberLabel.frame = CGRect(
            x: 0,
            y: 14 - codeTextView.contentOffset.y,
            width: 40,
            height: max(labelHeight, rowHeight)
        )
    }
}

private enum WorkspaceCodeAttributedText {
    static func highlight(_ document: WorkspaceCodeDocument, isDark: Bool) -> NSAttributedString {
        let source = document.text as NSString
        let paragraph = NSMutableParagraphStyle()
        paragraph.lineSpacing = 4
        let result = NSMutableAttributedString(
            string: document.text,
            attributes: [
                .font: UIFont.monospacedSystemFont(ofSize: 13, weight: .regular),
                .foregroundColor: baseColor(isDark),
                .paragraphStyle: paragraph
            ]
        )

        for token in document.tokens {
            let start = Int(token.start)
            let end = Int(token.endExclusive)
            guard start >= 0, end >= start, end <= source.length else { continue }
            result.addAttribute(
                .foregroundColor,
                value: tokenColor(token.kind, isDark: isDark),
                range: NSRange(location: start, length: end - start)
            )
        }
        return result
    }

    private static func baseColor(_ isDark: Bool) -> UIColor {
        isDark
            ? UIColor(red: 0.90, green: 0.93, blue: 0.95, alpha: 1)
            : UIColor(red: 0.12, green: 0.14, blue: 0.16, alpha: 1)
    }

    private static func tokenColor(_ kind: WorkspaceCodeTokenKind, isDark: Bool) -> UIColor {
        if kind == WorkspaceCodeTokenKind.comment {
            return isDark
                ? UIColor(red: 0.55, green: 0.58, blue: 0.62, alpha: 1)
                : UIColor(red: 0.43, green: 0.47, blue: 0.51, alpha: 1)
        }
        if kind == WorkspaceCodeTokenKind.string {
            return isDark
                ? UIColor(red: 0.65, green: 0.84, blue: 1.0, alpha: 1)
                : UIColor(red: 0.04, green: 0.48, blue: 0.24, alpha: 1)
        }
        if kind == WorkspaceCodeTokenKind.keyword {
            return isDark
                ? UIColor(red: 1.0, green: 0.48, blue: 0.45, alpha: 1)
                : UIColor(red: 0.51, green: 0.31, blue: 0.87, alpha: 1)
        }
        if kind == WorkspaceCodeTokenKind.number {
            return isDark
                ? UIColor(red: 0.47, green: 0.75, blue: 1.0, alpha: 1)
                : UIColor(red: 0.02, green: 0.31, blue: 0.68, alpha: 1)
        }
        if kind == WorkspaceCodeTokenKind.type {
            return isDark
                ? UIColor(red: 0.82, green: 0.66, blue: 1.0, alpha: 1)
                : UIColor(red: 0.58, green: 0.22, blue: 0.0, alpha: 1)
        }
        if kind == WorkspaceCodeTokenKind.tag {
            return isDark
                ? UIColor(red: 0.49, green: 0.91, blue: 0.53, alpha: 1)
                : UIColor(red: 0.81, green: 0.13, blue: 0.18, alpha: 1)
        }
        return isDark
            ? UIColor(red: 1.0, green: 0.65, blue: 0.34, alpha: 1)
            : UIColor(red: 0.58, green: 0.22, blue: 0.0, alpha: 1)
    }
}

private struct WorkspaceFileExporter: UIViewControllerRepresentable {
    let url: URL
    let onExported: (URL) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onExported: onExported) }

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let controller = UIDocumentPickerViewController(forExporting: [url], asCopy: true)
        controller.delegate = context.coordinator
        return controller
    }
    func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) {}

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let onExported: (URL) -> Void

        init(onExported: @escaping (URL) -> Void) {
            self.onExported = onExported
        }

        func documentPicker(
            _ controller: UIDocumentPickerViewController,
            didPickDocumentsAt urls: [URL]
        ) {
            guard let url = urls.first else { return }
            onExported(url)
        }
    }
}

private struct WorkspaceDownloadLocation: Codable {
    let bookmark: Data?
    let localPath: String
}

private final class WorkspaceDownloadRegistry {
    static let shared = WorkspaceDownloadRegistry()

    private let defaults: UserDefaults
    private let storageKey = "workspaceDownloadLocations.v1"
    private var locations: [String: WorkspaceDownloadLocation]

    private init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        if let data = defaults.data(forKey: storageKey),
           let decoded = try? JSONDecoder().decode(
               [String: WorkspaceDownloadLocation].self,
               from: data
           ) {
            locations = decoded
        } else {
            locations = [:]
        }
    }

    func record(sessionID: String, remotePath: String, localURL: URL) {
        let isScoped = localURL.startAccessingSecurityScopedResource()
        defer { if isScoped { localURL.stopAccessingSecurityScopedResource() } }
        guard FileManager.default.fileExists(atPath: localURL.path) else { return }
        let bookmark = try? localURL.bookmarkData(
            options: [],
            includingResourceValuesForKeys: nil,
            relativeTo: nil
        )
        locations[identity(sessionID: sessionID, remotePath: remotePath)] =
            WorkspaceDownloadLocation(bookmark: bookmark, localPath: localURL.path)
        persist()
    }

    func existingRemotePaths(sessionID: String, remotePaths: [String]) -> Set<String> {
        var result: Set<String> = []
        var removedStaleLocation = false
        for remotePath in remotePaths {
            let key = identity(sessionID: sessionID, remotePath: remotePath)
            guard let location = locations[key] else { continue }
            if locationExists(location) {
                result.insert(remotePath)
            } else {
                locations.removeValue(forKey: key)
                removedStaleLocation = true
            }
        }
        if removedStaleLocation { persist() }
        return result
    }

    private func locationExists(_ location: WorkspaceDownloadLocation) -> Bool {
        var isStale = false
        let bookmarkedURL = location.bookmark.flatMap { bookmark in
            try? URL(
                resolvingBookmarkData: bookmark,
                options: [.withoutUI],
                relativeTo: nil,
                bookmarkDataIsStale: &isStale
            )
        }
        guard !isStale else { return false }
        let url = bookmarkedURL ?? URL(fileURLWithPath: location.localPath)
        let isScoped = url.startAccessingSecurityScopedResource()
        defer { if isScoped { url.stopAccessingSecurityScopedResource() } }
        return FileManager.default.fileExists(atPath: url.path)
    }

    private func identity(sessionID: String, remotePath: String) -> String {
        Data("\(sessionID)\u{0}\(remotePath)".utf8).base64EncodedString()
    }

    private func persist() {
        guard let data = try? JSONEncoder().encode(locations) else { return }
        defaults.set(data, forKey: storageKey)
    }
}

/// Connection changes are scoped to this tiny view instead of rebuilding the
/// navigation shell (or the toolbar declaration containing it).
private struct ConversationNavigationStatus: View {
    @ObservedObject var gateway: GatewayClient
    let agentPresetTitle: String

    var body: some View {
        HStack(spacing: 7) {
            ConnectionDot(state: gateway.state)
            Text(agentPresetTitle)
                .font(.caption.weight(.medium))
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .fixedSize(horizontal: true, vertical: false)
        }
    }
}
