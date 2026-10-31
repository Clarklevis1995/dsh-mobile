import SwiftUI
import UIKit
import ChatLayout
import class DeepSeekHarnessShared.FileIconCatalog
#if DEBUG
import os.signpost
import Darwin
#endif

struct ConversationViewportEntry: Identifiable {
#if DEBUG
    // 仅实验开启时分配；struct 复制保留同一实际 content 的身份。
    let warmHostToken: DebugConversationWarmHostToken? = DebugConversationWarmHostExperiment.enabled
        ? DebugConversationWarmHostToken() : nil
#endif
    fileprivate enum CellKind {
        case hosting
        case streamingAssistant
        case userMessage
    }

    struct StreamingAssistant {
        let title: String
        let text: String
        let showsCopyButton: Bool

        init(title: String, text: String, showsCopyButton: Bool = false) {
            self.title = title
            self.text = text
            self.showsCopyButton = showsCopyButton
        }
    }

    struct UserMessage {
        struct Image {
            let id: String
            let data: Data?
            let width: Int
            let height: Int
            let name: String?
        }

        let text: String
        let images: [Image]
        let files: [PresentedFile]
        var onOpenFile: ((PresentedFile) -> Void)?
        let showsCopyButton: Bool

        init(text: String, images: [Image], files: [PresentedFile] = [], showsCopyButton: Bool, onOpenFile: ((PresentedFile) -> Void)? = nil) {
            self.text = text
            self.images = images
            self.files = files
            self.onOpenFile = onOpenFile
            self.showsCopyButton = showsCopyButton
        }

        init(item: ConversationItem, imageData: (String) -> Data?, showsCopyButton: Bool, onOpenFile: ((PresentedFile) -> Void)? = nil) {
            self.init(text: item.text, images: item.images.map {
                Image(id: $0.id, data: imageData($0.id), width: $0.width, height: $0.height, name: $0.name)
            }, files: item.files, showsCopyButton: showsCopyButton, onOpenFile: onOpenFile)
        }
    }

    let id: String
    let revision: Int
    let content: AnyView
    let streamingAssistant: StreamingAssistant?
    let userMessage: UserMessage?
    let allowsHeightCaching: Bool
    let allowsScrollAnchoring: Bool
    let clipsContentToBounds: Bool

    fileprivate var cellKind: CellKind {
        if streamingAssistant != nil { return .streamingAssistant }
        if userMessage != nil { return .userMessage }
        return .hosting
    }

    init(
        id: String,
        revision: Int,
        content: AnyView,
        allowsHeightCaching: Bool = true,
        allowsScrollAnchoring: Bool = true,
        clipsContentToBounds: Bool = false
    ) {
        self.id = id
        self.revision = revision
        self.content = content
        self.allowsHeightCaching = allowsHeightCaching
        self.allowsScrollAnchoring = allowsScrollAnchoring
        self.clipsContentToBounds = clipsContentToBounds
        streamingAssistant = nil
        userMessage = nil
    }

    init(id: String, revision: Int, streamingAssistant: StreamingAssistant) {
        self.id = id
        self.revision = revision
        content = AnyView(EmptyView())
        self.streamingAssistant = streamingAssistant
        userMessage = nil
        // Streaming rows also use a deterministic TextKit height. Keeping the
        // current revision in the shared cache prevents UIKit from starting a
        // preferred-size invalidation while a diffable update is committing.
        allowsHeightCaching = true
        allowsScrollAnchoring = true
        clipsContentToBounds = false
    }

    init(id: String, revision: Int, userMessage: UserMessage) {
        self.id = id
        self.revision = revision
        content = AnyView(EmptyView())
        streamingAssistant = nil
        self.userMessage = userMessage
        allowsHeightCaching = true
        allowsScrollAnchoring = true
        clipsContentToBounds = false
    }
}

private struct ConversationImagePreviewItem {
    let id: String
    let image: UIImage?
    let name: String?
}

private struct ConversationContentHeightPreferenceKey: PreferenceKey {
    static var defaultValue: CGFloat = 0

    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = max(value, nextValue())
    }
}

/// Gives hosted SwiftUI content one stable identity and a width-constrained
/// ideal height. Do not add a flexible spacer or an infinite-height frame here:
/// either one makes `systemLayoutSizeFitting` absorb ChatLayout's provisional
/// 80pt estimate, which can then be cached as a clipped cell or a large gap.
private struct TopPinnedConversationContent: View {
    let id: String
    let revision: Int
    let content: AnyView
    var onHeightChange: ((CGFloat) -> Void)?

    var body: some View {
        content
            .modifier(AppFontScope())
            .id("\(id)#\(revision)")
            .frame(maxWidth: .infinity, alignment: .leading)
            .fixedSize(horizontal: false, vertical: true)
            // Detached premeasurement is only a starting value. MarkdownUI
            // can choose an additional wrapped line after it enters the real
            // window environment. Read the height of the fixed-size content,
            // not the (possibly stale and clipped) collection-cell bounds.
            .background {
                GeometryReader { proxy in
                    Color.clear.preference(
                        key: ConversationContentHeightPreferenceKey.self,
                        value: proxy.size.height
                    )
                }
            }
            .onPreferenceChange(ConversationContentHeightPreferenceKey.self) { height in
                guard height.isFinite, height > 0 else { return }
                onHeightChange?(height)
            }
            // A collection cell that crosses the window's bottom safe area
            // inherits the Home Indicator inset. That inset changes as the
            // user drags and otherwise shifts the entire hosted message by
            // exactly 34pt relative to its cell. Conversation rows already
            // receive their own explicit viewport padding, so they must never
            // participate in the window safe area.
            .ignoresSafeArea(.container, edges: .all)
    }
}

/// UIKit-backed viewport for streaming conversation timelines.
struct ConversationViewport: UIViewControllerRepresentable {
    @AppStorage(AppFontScale.key) private var fontScale = 1.0
    @AppStorage(AppFontScale.followsSystemKey) private var fontFollowsSystem = false
    let proxy: ConversationViewportProxy
    let sessionID: String?
    var cacheScope: String = ""
    var searchTargetID: String? = nil
    let timeline: ConversationTimeline
    let supplementalEntries: [ConversationViewportEntry]
    let makeEntries: ([ConversationItem]) -> [ConversationViewportEntry]
    var topInset: CGFloat = 0
    let bottomInset: CGFloat
    let scrollToBottomToken: Int
    let onContentAvailabilityChanged: (String?, Bool) -> Void
    let onPinnedToBottomChanged: (Bool) -> Void
    let onScrolledFromTopChanged: (Bool) -> Void
    let onBottomAlignmentCompleted: () -> Void
    let onApproachingTop: () -> Void
    var onHistoryPresentationConsumerAttached: (String, HistoryPresentationConsumer) -> Void = { _, _ in }
    var onHistoryPresentationConsumerDetached: (String, UUID) -> Void = { _, _ in }
    var onHistoryPresented: (String, UUID, HistoryPresentationStamp) -> Void = { _, _, _ in }

    func makeUIViewController(context: Context) -> ConversationViewportController {
        let controller = ConversationViewportController(
            onContentAvailabilityChanged: onContentAvailabilityChanged,
            onPinnedToBottomChanged: onPinnedToBottomChanged,
            onBottomAlignmentCompleted: onBottomAlignmentCompleted,
            onApproachingTop: onApproachingTop
        )
        controller.onScrolledFromTopChanged = onScrolledFromTopChanged
        controller.onHistoryPresentationConsumerAttached = onHistoryPresentationConsumerAttached
        controller.onHistoryPresentationConsumerDetached = onHistoryPresentationConsumerDetached
        controller.onHistoryPresented = onHistoryPresented
        proxy.controller = controller
        return controller
    }

    func updateUIViewController(_ controller: ConversationViewportController, context: Context) {
        controller.onContentAvailabilityChanged = onContentAvailabilityChanged
        controller.onPinnedToBottomChanged = onPinnedToBottomChanged
        controller.onScrolledFromTopChanged = onScrolledFromTopChanged
        controller.onBottomAlignmentCompleted = onBottomAlignmentCompleted
        controller.onApproachingTop = onApproachingTop
        controller.onHistoryPresentationConsumerAttached = onHistoryPresentationConsumerAttached
        controller.onHistoryPresentationConsumerDetached = onHistoryPresentationConsumerDetached
        controller.onHistoryPresented = onHistoryPresented
        controller.cacheScope = cacheScope
        controller.searchTargetID = searchTargetID
        controller.setTopContentInset(topInset)
        controller.configure(
            sessionID: sessionID,
            timeline: timeline,
            supplementalEntries: supplementalEntries,
            makeEntries: makeEntries,
            bottomInset: bottomInset
        )
        if context.coordinator.lastScrollToBottomToken != scrollToBottomToken {
            context.coordinator.lastScrollToBottomToken = scrollToBottomToken
            controller.scrollToBottom()
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    static func dismantleUIViewController(
        _ controller: ConversationViewportController,
        coordinator: Coordinator
    ) {
        controller.detachHistoryPresentationConsumer()
#if DEBUG
        controller.finishHistoryScrollMetrics()
#endif
        controller.persistReusableHeights()
        // Do not clear a proxy that has already been rebound to a replacement
        // controller during a SwiftUI identity transition.
        if controller === controller.proxyOwner?.controller {
            controller.proxyOwner?.controller = nil
        }
    }

    final class Coordinator {
        var lastScrollToBottomToken = 0
    }
}

@MainActor
final class ConversationViewportProxy {
    weak var controller: ConversationViewportController? {
        didSet {
            oldValue?.proxyOwner = nil
            controller?.proxyOwner = self
        }
    }
    var historyPresentationConsumerID: UUID? { controller?.historyPresentationConsumerID }
    var historyReadingWindowSize: CGSize? { controller?.historyReadingWindowSize }

#if DEBUG
    func historyPresentationLifecycle(_ event: HistoryPresentationLifecycleEvent) { controller?.recordHistoryPresentationLifecycle(event) }
#endif

    func prepareForOverlayPresentation() {
        controller?.stopInertialScrolling()
    }

    func prepareForDisclosureUpdate(anchorID: String) {
        controller?.prepareForDisclosureUpdate(anchorID: anchorID)
    }
}

final class ConversationViewportController: UIViewController, UICollectionViewDelegate, ChatLayoutDelegate {
    weak var proxyOwner: ConversationViewportProxy?
    var onContentAvailabilityChanged: (String?, Bool) -> Void
    var onPinnedToBottomChanged: (Bool) -> Void
    var onScrolledFromTopChanged: (Bool) -> Void = { _ in }
    var onBottomAlignmentCompleted: () -> Void
    var onApproachingTop: () -> Void
    let historyPresentationConsumerID = UUID()
    var historyReadingWindowSize: CGSize {
        CGSize(width: max(120, chatLayout.layoutFrame.width),
               height: max(160, collectionView.bounds.height - collectionView.adjustedContentInset.top
                   - collectionView.adjustedContentInset.bottom))
    }
    var onHistoryPresentationConsumerAttached: (String, HistoryPresentationConsumer) -> Void = { _, _ in }
    var onHistoryPresentationConsumerDetached: (String, UUID) -> Void = { _, _ in }
    var onHistoryPresented: (String, UUID, HistoryPresentationStamp) -> Void = { _, _, _ in }

    private lazy var chatLayout: CollectionViewChatLayout = {
        let layout = CollectionViewChatLayout()
        layout.delegate = self
        layout.settings.estimatedItemSize = CGSize(width: 390, height: 80)
        layout.settings.interItemSpacing = 0
        layout.settings.additionalInsets = UIEdgeInsets(top: 0, left: 20, bottom: 0, right: 20)
        layout.keepContentOffsetAtBottomOnBatchUpdates = false
        return layout
    }()
    private lazy var collectionView = UICollectionView(frame: .zero, collectionViewLayout: chatLayout)
    private var dataSource: UICollectionViewDiffableDataSource<Int, String>!
    private var entriesByID: [String: ConversationViewportEntry] = [:]
    private var displayedSupplementalIDs: Set<String> = []
    private var configuredBodyFontSize: CGFloat?
    private var deferredFontReset = false
    private var previousRevisions: [String: Int] = [:]
    private var sessionID: String?
    private var hasPlacedInitialPosition = false
    private var hasAppliedSnapshot = false
    private var isPinnedToBottom = true
    private var hasScrolledFromTop = false
    private var isProgrammaticScroll = false
    private var isApplyingSnapshot = false
    private var activeSnapshotCommitID: UUID?
    private var nativeHistoryOffsetCommitID: UUID?
    private var pendingPresentationFinalization: (generation: Int, finalize: () -> Void)?
    private var requiresFreshEntriesAfterDisclosure = false
    private var needsInitialBottomPlacement = false
    private var needsBottomAlignment = false
    private var bottomAlignmentGeneration = 0
    /// Advanced only by an actual user drag. Layout invalidation can briefly
    /// move the collection view away from its old bottom while a new user or
    /// reasoning row is inserted; that transient offset must not cancel the
    /// tail-follow intent captured before the insertion began.
    private var userInteractionGeneration = 0
    private var lastAppliedRevision = -1
    private var lastReportedContentAvailability: Bool?
    /// Diffable snapshot 的 completion 是异步的。Session 切换时递增代际，
    /// 防止旧 session 的 completion 解锁新 session 的 viewport。
    private var sessionGeneration = 0
    private weak var timeline: ConversationTimeline?
    private var timelineObserverID: UUID?
    private var supplementalEntries: [ConversationViewportEntry] = []
    private var makeEntries: (([ConversationItem]) -> [ConversationViewportEntry])?
    private var bottomInset: CGFloat = 0
    private var pendingApply: (
        entries: [ConversationViewportEntry],
        revision: Int,
        bottomInset: CGFloat,
        hasConversationContent: Bool,
        historyPresentationStamp: HistoryPresentationStamp?
    )?
    var cacheScope = ""
    var searchTargetID: String?
    private var positionedSearchTargetID: String?
    private struct ReusableHeights {
        var heights: [CellMeasurementKey: CGFloat]
        var accessed: UInt64
        var anchor: VisibleAnchor?
        var byteCost: Int { heights.keys.reduce(0) { $0 + $1.id.utf8.count + 96 } }
    }
    private var reusableReadingAnchor: VisibleAnchor?
    private static var reusableHeights: [String: ReusableHeights] = [:]
    private var reusableHeightKey: String {
        "\(cacheScope)|\(sessionID ?? "")|\(UIFont.appPreferredFont(forTextStyle: .body).pointSize)"
    }
    static func clearReusableHeights() { reusableHeights.removeAll() }
    func persistReusableHeights() {
        guard sessionID != nil else { return }
        // 仅保存几何数据，不持有 UIViewController、SwiftUI host 或消息正文。
        let bounded = Dictionary(uniqueKeysWithValues: cellHeightCache.prefix(4096).map { ($0.key, $0.value) })
        let anchor: VisibleAnchor?
        if !isPinnedToBottom, let path = collectionView.indexPathsForVisibleItems.sorted().first,
           let id = dataSource?.itemIdentifier(for: path),
           let frame = collectionView.layoutAttributesForItem(at: path)?.frame {
            anchor = VisibleAnchor(id: id, offsetFromViewportTop: frame.minY - collectionView.contentOffset.y)
        } else { anchor = nil }
        Self.reusableHeights[reusableHeightKey] = ReusableHeights(heights: bounded, accessed: DispatchTime.now().uptimeNanoseconds, anchor: anchor)
        while Self.reusableHeights.count > 6 || Self.reusableHeights.values.reduce(0, { $0 + $1.byteCost }) > 8 * 1024 * 1024 {
            guard let oldest = Self.reusableHeights.min(by: { $0.value.accessed < $1.value.accessed })?.key else { break }
            Self.reusableHeights[oldest] = nil
        }
    }
    private var cellHeightCache: [CellMeasurementKey: CGFloat] = [:]
#if DEBUG
    private let warmHostPool: DebugConversationWarmHostPool? = DebugConversationWarmHostExperiment.enabled
        ? DebugConversationWarmHostPool() : nil
    private var warmHostWidth: CGFloat {
        chatLayout.layoutFrame.width > 0 ? chatLayout.layoutFrame.width : collectionView.bounds.width
    }
    private func warmHostContext(width: CGFloat) -> DebugConversationWarmHostContext {
        .init(owner: ObjectIdentifier(self), sessionID: sessionID, cacheScope: cacheScope,
              sessionGeneration: sessionGeneration, width: width,
              fontSize: UIFont.appPreferredFont(forTextStyle: .body).pointSize,
              ownerTraits: traitCollection, collection: ObjectIdentifier(collectionView),
              window: collectionView.window.map(ObjectIdentifier.init), locale: Locale.current.identifier)
    }
#endif
    private var historyHeightPreparation: (
        entries: [ConversationViewportEntry], nextIndex: Int, width: CGFloat,
        generation: Int, route: HistoryViewportPresentationSchedule.Route, changedExisting: Int, targetEntries: Int
    )?
    private var historyPreparationDisplayLink: CADisplayLink?
    private var presentationSchedule = HistoryViewportPresentationSchedule()
    private var presentationScheduleGeneration = 0
    private var presentationTargetGeneration = 0
    private var latestHistoryProjectionEpoch = -1
    private var carriedHistoryPresentationStamp: HistoryPresentationStamp?
    private var lastAcknowledgedHistoryStamp: HistoryPresentationStamp?
    private struct PresentationBatch {
        let generation: Int
        let entries: Int
        let remaining: Int
        let inserted: Int
        let route: HistoryViewportPresentationSchedule.Route
        let changedExisting: Int
        let insertedBody: Int
        let insertedSupplemental: Int
        let projectionEpoch: Int?
    }
    private struct PreparedPresentation {
        let entries: [ConversationViewportEntry]
        let revision: Int
        let bottomInset: CGFloat
        let hasConversationContent: Bool
        let sourceStamp: HistoryPresentationStamp?
        let batch: PresentationBatch
        let width: CGFloat
        let fontSize: CGFloat
        let sessionGeneration: Int
        let historyEpoch: Int
    }
    private var preparedPresentation: PreparedPresentation?
    private final class HistoryPreparationTarget: NSObject {
        weak var owner: ConversationViewportController?
        var generation = 0

        @MainActor @objc func tick() {
            owner?.prepareNextHistoryHeightBatch(generation: generation)
        }
    }
    private var streamingHeightMeasurers: [String: StreamingTextHeightMeasurer] = [:]
    private var pendingLiveHeightCorrections: [CellMeasurementKey: LiveHeightCorrection] = [:]
    private var isLiveHeightCorrectionScheduled = false
    private var pendingDisclosureAnchor: DisclosureAnchor?
    private var disclosureAnchorGeneration = 0
    private var isStreamingRenderingPausedForUserScroll = false
    private var isUserGestureAwaitingEnd = false
    private var deferredTimelineSnapshot: ConversationTimeline.Snapshot?
    private var isStreamingRenderResumePending = false
    private var historyPresentationOperationID: UUID?
    private var historyPresentationOperationIsTerminal = false
    private var historyPresentationOperationIsPending: Bool { historyPresentationOperationID != nil }
    private var historyScrollIndicatorNeedsRestore = false
    private struct HistoryScrollIndicatorGeometry: Equatable {
        let contentSize: CGSize
        let contentOffset: CGPoint
        let viewportSize: CGSize
        let insets: UIEdgeInsets
    }
    private var historyScrollIndicatorGeometry: HistoryScrollIndicatorGeometry?
    private static let contentRevealAnimationKey = "conversation-content-reveal"
    private var initialContentRevealPending = false
    private var initialContentHasConversation = false
    private var pendingHistoryRevealIDs = Set<String>()
    private var pendingContentRevealAcknowledgement: (() -> Void)?
    private var pendingContentRevealStamp: HistoryPresentationStamp?
    private var contentRevealGeometry: HistoryScrollIndicatorGeometry?
    private var initialContentRevealDisplayLink: CADisplayLink?
    private final class InitialContentRevealTarget: NSObject {
        weak var owner: ConversationViewportController?
        @MainActor @objc func tick() { owner?.advanceInitialContentReveal() }
    }

    private func scheduleInitialContentReveal() {
        guard initialContentRevealPending, initialContentHasConversation,
              initialContentRevealDisplayLink == nil else { return }
        let target = InitialContentRevealTarget(); target.owner = self
        let link = CADisplayLink(target: target, selector: #selector(InitialContentRevealTarget.tick))
        initialContentRevealDisplayLink = link
        link.add(to: .main, forMode: .common)
    }

    private func advanceInitialContentReveal() {
        guard !isApplyingSnapshot, hasPlacedInitialPosition else { return }
        guard revealContentIfStable(initialOnly: true) else { return }
        if !initialContentRevealPending {
            initialContentRevealDisplayLink?.invalidate(); initialContentRevealDisplayLink = nil
        }
    }

    private var reducesContentMotion: Bool {
#if DEBUG
        if let override = reduceContentMotionForTesting { return override }
#endif
        return UIAccessibility.isReduceMotionEnabled
    }

    /// 只合成透明度，不参与布局、不阻挡点击；模型层始终是最终不透明状态。
    private func fadeInContent(_ layer: CALayer) {
        layer.removeAnimation(forKey: Self.contentRevealAnimationKey)
        guard !reducesContentMotion else { return }
        let animation = CABasicAnimation(keyPath: "opacity")
        animation.fromValue = 0
        animation.toValue = 1
        animation.duration = 0.18
        animation.timingFunction = CAMediaTimingFunction(name: .easeOut)
        layer.add(animation, forKey: Self.contentRevealAnimationKey)
    }

    private func setHistoryContentHidden(_ hidden: Bool, in cell: UICollectionViewCell) {
        cell.contentView.layer.removeAnimation(forKey: Self.contentRevealAnimationKey)
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        cell.contentView.layer.opacity = hidden ? 0 : 1
        CATransaction.commit()
        cell.contentView.accessibilityElementsHidden = hidden
        cell.contentView.isUserInteractionEnabled = !hidden
    }

    private func finishContentRevealAnimations() {
        collectionView.layer.removeAnimation(forKey: Self.contentRevealAnimationKey)
        for cell in collectionView.visibleCells {
            cell.contentView.layer.removeAnimation(forKey: Self.contentRevealAnimationKey)
        }
    }

    @objc private func contentMotionPreferenceChanged() {
        if reducesContentMotion { finishContentRevealAnimations() }
    }

    /// 用户开始新的阅读手势时，已插入内容立即可读，不能留下透明空白行。
    private func revealInsertedHistoryImmediately() {
        pendingHistoryRevealIDs.removeAll()
        contentRevealGeometry = nil
        finishContentRevealAnimations()
        for cell in collectionView.visibleCells { setHistoryContentHidden(false, in: cell) }
    }

    /// 完整目标排空后再采两个相同几何的空闲tick；不等待图片字节或后续流式token。
    private func revealContentIfStable(initialOnly: Bool = false) -> Bool {
        let revealInitial = initialOnly && initialContentRevealPending && initialContentHasConversation
            && hasAppliedSnapshot && hasPlacedInitialPosition
        let revealHistory = !initialOnly
            && (!pendingHistoryRevealIDs.isEmpty || pendingContentRevealAcknowledgement != nil)
            && (!historyPresentationOperationIsPending
                || (historyPresentationOperationIsTerminal && pendingContentRevealAcknowledgement != nil))
        guard revealInitial || revealHistory else { return true }
        let geometry = HistoryScrollIndicatorGeometry(contentSize: collectionView.contentSize,
            contentOffset: collectionView.contentOffset, viewportSize: collectionView.bounds.size,
            insets: collectionView.adjustedContentInset)
        // 生成中的首屏只等待视口约束稳定，不能等整个流式回复停止增长。
        let streamingInitial = revealInitial && entriesByID.values.contains { $0.streamingAssistant != nil }
        let stable = contentRevealGeometry == geometry || (streamingInitial
            && contentRevealGeometry?.viewportSize == geometry.viewportSize
            && contentRevealGeometry?.insets == geometry.insets)
        guard stable else {
            contentRevealGeometry = geometry
            return false
        }
        contentRevealGeometry = nil
        if revealInitial {
            initialContentRevealPending = false
            collectionView.isHidden = false
            collectionView.layer.opacity = 1
            collectionView.accessibilityElementsHidden = false
            collectionView.isUserInteractionEnabled = true
            reportContentAvailability(true)
            fadeInContent(collectionView.layer)
#if DEBUG
            os_log("conversation-content-reveal kind=initial rows=%{public}d reduced=%{public}d",
                log: Self.scrollPerformanceLog, type: .debug, entriesByID.count, reducesContentMotion ? 1 : 0)
#endif
            updateTopEdgeVisibility()
        }
        if revealHistory || revealInitial {
            let insertedIDs = pendingHistoryRevealIDs
            pendingHistoryRevealIDs.removeAll()
#if DEBUG
            if !revealInitial {
                os_log("conversation-content-reveal kind=history rows=%{public}d reduced=%{public}d",
                    log: Self.scrollPerformanceLog, type: .debug, insertedIDs.count, reducesContentMotion ? 1 : 0)
            }
#endif
            for path in collectionView.indexPathsForVisibleItems {
                guard let id = dataSource.itemIdentifier(for: path), insertedIDs.contains(id),
                      let cell = collectionView.cellForItem(at: path) else { continue }
                setHistoryContentHidden(false, in: cell)
                if !revealInitial { fadeInContent(cell.contentView.layer) }
            }
        }
        let acknowledgement = pendingContentRevealAcknowledgement
        pendingContentRevealAcknowledgement = nil
        pendingContentRevealStamp = nil
        acknowledgement?()
        return true
    }

    func collectionView(_ collectionView: UICollectionView, willDisplay cell: UICollectionViewCell,
                        forItemAt indexPath: IndexPath) {
        let hidden = dataSource.itemIdentifier(for: indexPath).map { pendingHistoryRevealIDs.contains($0) } ?? false
        setHistoryContentHidden(hidden, in: cell)
    }

    func collectionView(_ collectionView: UICollectionView, didEndDisplaying cell: UICollectionViewCell,
                        forItemAt indexPath: IndexPath) {
        setHistoryContentHidden(false, in: cell)
    }

    func recordHistoryPresentationLifecycle(_ event: HistoryPresentationLifecycleEvent) {
        switch event {
        case .began(let id):
            historyPresentationOperationID = id
            historyPresentationOperationIsTerminal = false
            historyScrollIndicatorNeedsRestore = true
            historyScrollIndicatorGeometry = nil
            collectionView.showsVerticalScrollIndicator = false
        case .terminal(let id) where historyPresentationOperationID == id:
            historyPresentationOperationIsTerminal = true
            schedulePendingPresentation()
        case .finished(let id, _) where historyPresentationOperationID == id:
            historyPresentationOperationID = nil
            historyPresentationOperationIsTerminal = false
            historyScrollIndicatorGeometry = nil
            schedulePendingPresentation()
        default: break
        }
#if DEBUG
        recordHistoryScrollMetrics(event)
#endif
    }

    /// 返回false时再观察一个空闲tick；网络仍在加载时无需保持调度器空转。
    private func restoreHistoryScrollIndicatorIfStable() -> Bool {
        guard historyScrollIndicatorNeedsRestore, historyPresentationOperationID == nil else { return true }
        let geometry = HistoryScrollIndicatorGeometry(contentSize: collectionView.contentSize,
            contentOffset: collectionView.contentOffset, viewportSize: collectionView.bounds.size,
            insets: collectionView.adjustedContentInset)
        guard historyScrollIndicatorGeometry == geometry else {
            historyScrollIndicatorGeometry = geometry
            return false
        }
        historyScrollIndicatorNeedsRestore = false
        historyScrollIndicatorGeometry = nil
        collectionView.showsVerticalScrollIndicator = true
        return true
    }
#if DEBUG
    private var historyScrollMetrics: DebugHistoryScrollMetrics?
    private var historyMetricsRowCount = 0
    private let commitDiagnostics = DebugHistoryCommitDiagnostics(environment: ProcessInfo.processInfo.environment)
    private var currentCommitDiagnostic: DebugHistoryCommitDiagnostics.Record?
    func finishHistoryScrollMetrics() {
        warmHostPool?.report(final: true)
        historyScrollMetrics?.finish()
    }
    private func recordHistoryScrollMetrics(_ event: HistoryPresentationLifecycleEvent) {
        switch event {
        case .began(let id): historyScrollMetrics?.historyOperationBegan(operationID: id, rowCount: historyMetricsRowCount)
        case .required(let id, let epoch): historyScrollMetrics?.historyOperationRequired(operationID: id, projectionEpoch: epoch, rowCount: historyMetricsRowCount)
        case .issued(let id, let epoch): historyScrollMetrics?.historyOperationIssued(operationID: id, projectionEpoch: epoch, rowCount: historyMetricsRowCount)
        case .terminal(let id): historyScrollMetrics?.historyOperationTerminal(operationID: id, rowCount: historyMetricsRowCount)
        case .presented(let id, let epoch): historyScrollMetrics?.historyOperationPresented(operationID: id, projectionEpoch: epoch, rowCount: historyMetricsRowCount)
        case .finished(let id, let outcome): historyScrollMetrics?.historyOperationFinished(operationID: id, outcome: outcome.rawValue, rowCount: historyMetricsRowCount)
        case .rejected(let reason): historyScrollMetrics?.historyAdmissionRejected(rowCount: historyMetricsRowCount, reason: reason)
        }
    }
    private(set) var streamingLayoutInvalidationCount = 0
    func seedPresentationHeightForTesting(_ entries: [ConversationViewportEntry], height: CGFloat) {
        let width = chatLayout.layoutFrame.width > 0 ? chatLayout.layoutFrame.width : collectionView.bounds.width
        for entry in entries {
            cellHeightCache[CellMeasurementKey(id: entry.id, revision: entry.revision, width: width)] = height
        }
    }
    var reduceContentMotionForTesting: Bool?
    var isInitialContentRevealPendingForTesting: Bool { initialContentRevealPending }
    var pendingHistoryRevealIDsForTesting: Set<String> { pendingHistoryRevealIDs }
    var isHistoryPreparationPendingForTesting: Bool { historyHeightPreparation != nil }
    var isHistoryFinalizationPendingForTesting: Bool { pendingPresentationFinalization != nil }
    var isHistorySnapshotCommitInFlightForTesting: Bool { isApplyingSnapshot }
    var isNativeHistoryOffsetCompensationActiveForTesting: Bool { chatLayout.keepContentOffsetAtBottomOnBatchUpdates }
    var historyDiffableCompletionGateForTesting: ((@escaping () -> Void) -> Void)?
    var isHistoryPresentationScheduledForTesting: Bool { historyPreparationDisplayLink != nil }
    private static let scrollPerformanceLog = OSLog(subsystem: "com.clarklevis.dsh", category: "HistoryScroll")
    private var historyPreparationSignpostID: OSSignpostID?
    private var historyPreparationMeasuredRows = 0
    private var lastApproachTopSignpostTime: CFTimeInterval = 0
#endif

    init(
        onContentAvailabilityChanged: @escaping (String?, Bool) -> Void,
        onPinnedToBottomChanged: @escaping (Bool) -> Void,
        onBottomAlignmentCompleted: @escaping () -> Void,
        onApproachingTop: @escaping () -> Void
    ) {
        self.onContentAvailabilityChanged = onContentAvailabilityChanged
        self.onPinnedToBottomChanged = onPinnedToBottomChanged
        self.onBottomAlignmentCompleted = onBottomAlignmentCompleted
        self.onApproachingTop = onApproachingTop
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { nil }

    deinit {
        historyPreparationDisplayLink?.invalidate()
        initialContentRevealDisplayLink?.invalidate()
        let timeline = timeline
        let observerID = timelineObserverID
        Task { @MainActor in
            if let timeline, let observerID {
                timeline.removeObserver(observerID)
            }
        }
    }

    func configure(
        sessionID: String?,
        timeline: ConversationTimeline,
        supplementalEntries: [ConversationViewportEntry],
        makeEntries: @escaping ([ConversationItem]) -> [ConversationViewportEntry],
        bottomInset: CGFloat
    ) {
        loadViewIfNeeded()
        setSessionID(sessionID)
        resetConfiguredFontIfNeeded()
        self.supplementalEntries = supplementalEntries
        self.makeEntries = makeEntries
        self.bottomInset = bottomInset

        if self.timeline !== timeline {
            positionedSearchTargetID = nil
            if let oldTimeline = self.timeline, let timelineObserverID {
                oldTimeline.removeObserver(timelineObserverID)
            }
            self.timeline = timeline
            timelineObserverID = timeline.observe { [weak self] snapshot in
                self?.receive(snapshot)
            }
        } else {
            receive(timeline.currentSnapshot)
        }
    }

    private func resetConfiguredFontIfNeeded() {
        let size = UIFont.appPreferredFont(forTextStyle: .body).pointSize
        guard configuredBodyFontSize != size else { deferredFontReset = false; return }
        guard !isViewportMoving, !isStreamingRenderingPausedForUserScroll, !isApplyingSnapshot,
              presentationSchedule.permitsWork(at: CACurrentMediaTime()) else {
            deferredFontReset = true
            return
        }
        deferredFontReset = false
#if DEBUG
        warmHostPool?.clear()
#endif
        cancelHistoryHeightPreparation(stopScheduling: false)
        preparedPresentation = nil
        configuredBodyFontSize = size
        cellHeightCache = Self.reusableHeights[reusableHeightKey]?.heights ?? [:]
        streamingHeightMeasurers.removeAll()
        previousRevisions = [:]
        lastAppliedRevision = -1
        collectionView.collectionViewLayout.invalidateLayout()
    }

    private func receive(_ snapshot: ConversationTimeline.Snapshot) {
        if let stamp = snapshot.historyPresentationStamp {
            guard stamp.projectionEpoch >= latestHistoryProjectionEpoch else { return }
            latestHistoryProjectionEpoch = stamp.projectionEpoch
            carriedHistoryPresentationStamp = stamp
        }
        guard !isStreamingRenderingPausedForUserScroll,
              !isViewportMoving, presentationSchedule.permitsWork(at: CACurrentMediaTime()),
              historyPreparationDisplayLink == nil, !isApplyingSnapshot else {
            deferredTimelineSnapshot = snapshot
            presentationTargetGeneration &+= 1
#if DEBUG
            historyScrollMetrics?.historySchedulerEvent(.targetBuffered, generation: presentationTargetGeneration,
                entries: 0, batchEntries: 0, moving: isViewportMoving)
#endif
            schedulePendingPresentation()
            return
        }
        render(snapshot)
    }

    private func render(_ snapshot: ConversationTimeline.Snapshot) {
        guard let makeEntries else { return }
        let entries = supplementalEntries + makeEntries(snapshot.items)
        let hasContent = !snapshot.items.isEmpty || entries.contains { $0.id == "deep-search-progress" }
        apply(
            entries: entries,
            revision: snapshot.revision,
            bottomInset: bottomInset,
            hasConversationContent: hasContent,
            historyPresentationStamp: snapshot.historyPresentationStamp
        )
    }

    func setSessionID(_ newSessionID: String?) {
        guard newSessionID != sessionID else { return }
        detachHistoryPresentationConsumer()
        persistReusableHeights()
        sessionGeneration &+= 1
#if DEBUG
        warmHostPool?.clear()
#endif
        cancelHistoryHeightPreparation()
        sessionID = newSessionID
        if let newSessionID {
            onHistoryPresentationConsumerAttached(newSessionID,
                HistoryPresentationConsumer(id: historyPresentationConsumerID, onEvent: { [weak self] event in
                    self?.recordHistoryPresentationLifecycle(event)
                }))
        }
        // 在新 timeline snapshot 原子提交前隐藏旧 cell，
        // 禁止上一个 session 的内容在切换期间闪现一帧。
        collectionView.isHidden = true
        collectionView.layer.opacity = 0
        collectionView.accessibilityElementsHidden = true
        collectionView.isUserInteractionEnabled = false
        initialContentRevealPending = true
        initialContentHasConversation = false
        lastReportedContentAvailability = nil
        reportContentAvailability(false)
        hasPlacedInitialPosition = false
        updateTopEdgeVisibility()
        hasAppliedSnapshot = false
        isPinnedToBottom = false
        setPinned(true)
        needsInitialBottomPlacement = false
        needsBottomAlignment = false
        previousRevisions = [:]
        lastAppliedRevision = -1
        pendingApply = nil
        pendingDisclosureAnchor = nil
        disclosureAnchorGeneration &+= 1
        isStreamingRenderingPausedForUserScroll = false
        deferredTimelineSnapshot = nil
        isStreamingRenderResumePending = false
#if DEBUG
        streamingLayoutInvalidationCount = 0
#endif
        cellHeightCache = Self.reusableHeights[reusableHeightKey]?.heights ?? [:]
        reusableReadingAnchor = Self.reusableHeights[reusableHeightKey]?.anchor
        streamingHeightMeasurers.removeAll(keepingCapacity: true)
        pendingLiveHeightCorrections.removeAll(keepingCapacity: true)
        isLiveHeightCorrectionScheduled = false
    }

    func detachHistoryPresentationConsumer() {
#if DEBUG
        historyScrollMetrics?.historySchedulerEvent(.cancelled, generation: presentationTargetGeneration,
            entries: pendingApply?.entries.count ?? 0, batchEntries: 0, moving: isViewportMoving)
#endif
        pendingContentRevealAcknowledgement = nil
        pendingContentRevealStamp = nil
        revealInsertedHistoryImmediately()
        initialContentRevealDisplayLink?.invalidate(); initialContentRevealDisplayLink = nil
        collectionView.layer.opacity = 1
        collectionView.accessibilityElementsHidden = false
        collectionView.isUserInteractionEnabled = true
        initialContentRevealPending = false
        initialContentHasConversation = false
        presentationScheduleGeneration &+= 1
        sessionGeneration &+= 1
        if pendingPresentationFinalization != nil {
            if let commitID = nativeHistoryOffsetCommitID { finishNativeHistoryOffsetCompensation(commitID) }
            pendingPresentationFinalization = nil
            activeSnapshotCommitID = nil
            isApplyingSnapshot = false
        }
        requiresFreshEntriesAfterDisclosure = false
        cancelHistoryHeightPreparation()
        pendingApply = nil
        deferredTimelineSnapshot = nil
        presentationSchedule = HistoryViewportPresentationSchedule()
        isStreamingRenderingPausedForUserScroll = false
        isStreamingRenderResumePending = false
        isUserGestureAwaitingEnd = false
        latestHistoryProjectionEpoch = -1
        carriedHistoryPresentationStamp = nil
        lastAcknowledgedHistoryStamp = nil
        preparedPresentation = nil
        historyPresentationOperationID = nil
        historyPresentationOperationIsTerminal = false
        historyScrollIndicatorNeedsRestore = false
        historyScrollIndicatorGeometry = nil
        collectionView.showsVerticalScrollIndicator = true
#if DEBUG
        warmHostPool?.clear()
#endif
        if let sessionID { onHistoryPresentationConsumerDetached(sessionID, historyPresentationConsumerID) }
    }

    override func didReceiveMemoryWarning() {
        super.didReceiveMemoryWarning()
        Self.clearReusableHeights()
        cellHeightCache.removeAll()
        streamingHeightMeasurers.removeAll()
#if DEBUG
        warmHostPool?.clear()
#endif
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear
        NotificationCenter.default.addObserver(self, selector: #selector(contentMotionPreferenceChanged),
            name: UIAccessibility.reduceMotionStatusDidChangeNotification, object: nil)
        collectionView.accessibilityIdentifier = "conversation-history-viewport"
        collectionView.alwaysBounceVertical = true
        collectionView.isScrollEnabled = true
        collectionView.keyboardDismissMode = .interactive
        collectionView.backgroundColor = .clear
        collectionView.delegate = self
        collectionView.contentInsetAdjustmentBehavior = .never
        if #available(iOS 26.0, *) {
            // 原生软边缘做渐进模糊；不要再叠整块 material，避免正文被灰色带吞掉。
            collectionView.topEdgeEffect.style = .soft
        }
        // ChatLayout's automatic self-sizing mode is explicitly experimental.
        // During a diffable batch it can recursively re-enter
        // `_updateVisibleCellsNow` when SwiftUI/TextKit invalidates intrinsic
        // content size. All mutable rows below are invalidated explicitly and
        // immutable rows are premeasured, so automatic invalidation must stay off.
        if #available(iOS 16.0, *) {
            collectionView.selfSizingInvalidation = .disabled
            chatLayout.supportSelfSizingInvalidation = false
        }
        let dismissKeyboardTap = UITapGestureRecognizer(
            target: self,
            action: #selector(dismissKeyboardFromConversation)
        )
        // Dismissing focus must not consume the original tap: links, copy
        // buttons and expandable process rows still receive their action.
        dismissKeyboardTap.cancelsTouchesInView = false
        collectionView.addGestureRecognizer(dismissKeyboardTap)
        collectionView.register(
            HostedConversationCell.self,
            forCellWithReuseIdentifier: "ConversationCell"
        )
        collectionView.register(
            StreamingAssistantCell.self,
            forCellWithReuseIdentifier: StreamingAssistantCell.reuseIdentifier
        )
        collectionView.register(
            UserMessageCell.self,
            forCellWithReuseIdentifier: UserMessageCell.reuseIdentifier
        )
        view.addSubview(collectionView)
        collectionView.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            collectionView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            collectionView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            collectionView.topAnchor.constraint(equalTo: view.topAnchor),
            collectionView.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        ])
        dataSource = UICollectionViewDiffableDataSource<Int, String>(collectionView: collectionView) { [weak self] collectionView, indexPath, id in
            guard let self, let entry = self.entriesByID[id] else {
                return collectionView.dequeueReusableCell(withReuseIdentifier: "ConversationCell", for: indexPath)
            }
            if let streamingAssistant = entry.streamingAssistant {
                let cell = collectionView.dequeueReusableCell(
                    withReuseIdentifier: StreamingAssistantCell.reuseIdentifier,
                    for: indexPath
                ) as! StreamingAssistantCell
                // The layout owns every row's vertical boundary. Even though
                // TextKit is measured deterministically, clipping is the last
                // line of defence against a glyph painting into a sibling
                // during a bounds-change transaction.
                cell.clipsToBounds = true
                cell.contentView.clipsToBounds = true
                self.configureStreamingMeasurement(cell, entry: entry)
                cell.apply(streamingAssistant)
                return cell
            }
            if let userMessage = entry.userMessage {
                let cell = collectionView.dequeueReusableCell(
                    withReuseIdentifier: UserMessageCell.reuseIdentifier,
                    for: indexPath
                ) as! UserMessageCell
                cell.clipsToBounds = true
                cell.contentView.clipsToBounds = true
                self.cacheUserMessageHeightIfNeeded(userMessage, entry: entry)
                cell.configureMeasurement(
                    id: entry.id,
                    revision: entry.revision,
                    cachedHeight: { [weak self] width in
                        self?.cellHeightCache[
                            CellMeasurementKey(id: entry.id, revision: entry.revision, width: width)
                        ]
                    },
                    storeHeight: { [weak self] width, height in
                        self?.cellHeightCache[
                            CellMeasurementKey(id: entry.id, revision: entry.revision, width: width)
                        ] = height
                    }
                )
                cell.onOpenImagePreview = { [weak self] items, initialIndex in
                    self?.presentImagePreview(items, initialIndex: initialIndex)
                }
                cell.apply(userMessage, containerWidth: self.chatLayout.layoutFrame.width)
                return cell
            }
            guard let cell = collectionView.dequeueReusableCell(
                withReuseIdentifier: "ConversationCell",
                for: indexPath
            ) as? HostedConversationCell else {
                return UICollectionViewCell()
            }
            // Reused cells must not cache the old SwiftUI tree's height under
            // the incoming entry's id.
            cell.disableMeasurementCaching()
            cell.clipsToBounds = entry.clipsContentToBounds
            cell.contentView.clipsToBounds = entry.clipsContentToBounds
            cell.backgroundColor = .clear
            cell.backgroundConfiguration = UIBackgroundConfiguration.clear()
            cell.configureMeasurement(
                id: entry.id,
                revision: entry.revision,
                cachedHeight: { [weak self] width in
                    guard entry.allowsHeightCaching else { return nil }
                    return self?.cellHeightCache[
                        CellMeasurementKey(id: entry.id, revision: entry.revision, width: width)
                    ]
                },
                storeHeight: { [weak self] width, height in
                    guard entry.allowsHeightCaching else { return }
                    self?.cellHeightCache[
                        CellMeasurementKey(id: entry.id, revision: entry.revision, width: width)
                    ] = height
                },
                measureHeight: { [weak cell] width in
                    cell?.measuredHeight(for: width)
                        ?? Self.measureHostedHeight(for: entry, width: width)
                }
            )
#if DEBUG
            self.currentCommitDiagnostic?.observeHost(cell, created: !cell.hasHostingControllerForCommitDiagnostics)
#endif
            let heightSessionGeneration = self.sessionGeneration
            let reportHeight: (CGFloat, CGFloat) -> Void = { [weak self] width, height in
                guard let self, self.sessionGeneration == heightSessionGeneration else { return }
                self.receiveLiveHostedHeight(
                    id: entry.id,
                    revision: entry.revision,
                    width: width,
                    height: height
                )
            }
#if DEBUG
            if let prepared = self.warmHostPool?.take(token: entry.warmHostToken,
                    diagnosticID: entry.id, diagnosticRevision: entry.revision, context: self.warmHostContext(width: self.warmHostWidth)) {
                let installed = cell.installPreparedHost(prepared, entry: entry, parent: self, collection: collectionView, onHeightChange: reportHeight)
                self.warmHostPool?.recordInstallation(succeeded: installed,
                    failure: cell.lastWarmTransferFailure, traitChanges: cell.lastWarmTraitDifferences,
                    targetWasAttached: cell.lastWarmTargetWasAttached)
            } else {
                cell.apply(entry, parent: self, onHeightChange: reportHeight)
            }
#else
            cell.apply(entry, parent: self, onHeightChange: reportHeight)
#endif
            return cell
        }
    }

    @objc private func dismissKeyboardFromConversation() {
        view.window?.endEditing(true)
    }

    private func presentImagePreview(
        _ items: [ConversationImagePreviewItem],
        initialIndex: Int
    ) {
        guard !items.isEmpty, presentedViewController == nil else { return }
        stopInertialScrolling()
        let preview = ConversationImagePreviewController(
            items: items,
            initialIndex: initialIndex
        )
        preview.modalPresentationStyle = .overFullScreen
        preview.modalTransitionStyle = .crossDissolve
        present(preview, animated: true)
    }

    /// Presenting a popover while a self-sizing collection view is still
    /// decelerating makes UIKit update the content offset and preferred cell
    /// sizes in the same layout pass. Freeze the viewport at its current
    /// visual position before presentation begins.
    func stopInertialScrolling() {
        needsBottomAlignment = false
        bottomAlignmentGeneration &+= 1
        guard collectionView.isDecelerating || collectionView.isDragging || isUserGestureAwaitingEnd else { return }
        isProgrammaticScroll = true
        collectionView.setContentOffset(collectionView.contentOffset, animated: false)
        collectionView.layer.removeAllAnimations()
        isProgrammaticScroll = false
        setPinned(isAtBottom(collectionView))
        isUserGestureAwaitingEnd = false
#if DEBUG
        historyScrollMetrics?.scrollEnded(rowCount: historyMetricsRowCount)
#endif
        resumeStreamingRenderingAfterUserScroll()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        placeInitialPositionIfNeeded()
        alignBottomIfNeeded()
        updateTopEdgeVisibility()
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
#if DEBUG
        finishHistoryScrollMetrics()
#endif
    }

    /// 顶部留白属于滚动内容，正文离开顶端后才能透过导航栏的毛玻璃。
    func setTopContentInset(_ inset: CGFloat) {
        guard collectionView.contentInset.top != inset else { return }
        collectionView.contentInset.top = inset
        collectionView.verticalScrollIndicatorInsets.top = inset
    }

    override func viewWillLayoutSubviews() {
        // 只收窄消息布局，滚动视口和系统软边缘始终覆盖整个页面宽度。
        let inset = 20 + AdaptiveLayout.horizontalInset(width: view.bounds.width, maximumWidth: AdaptiveLayout.pageWidth)
        if chatLayout.settings.additionalInsets.left != inset {
            chatLayout.settings.additionalInsets = UIEdgeInsets(top: 0, left: inset, bottom: 0, right: inset)
        }
        super.viewWillLayoutSubviews()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
#if DEBUG
        warmHostPool?.updateContext(warmHostContext(width: warmHostWidth))
#endif
        if !isApplyingSnapshot,
           historyHeightPreparation == nil,
           collectionView.bounds.width > 0,
           pendingApply != nil {
            schedulePendingPresentation()
        }
        placeInitialPositionIfNeeded()
        alignBottomIfNeeded()
        updateTopEdgeVisibility()
    }

    func apply(
        entries: [ConversationViewportEntry],
        revision: Int,
        bottomInset: CGFloat,
        hasConversationContent: Bool,
        historyPresentationStamp: HistoryPresentationStamp? = nil
    ) {
        requiresFreshEntriesAfterDisclosure = false
        if let stamp = historyPresentationStamp {
            guard stamp.projectionEpoch >= latestHistoryProjectionEpoch else { return }
            latestHistoryProjectionEpoch = stamp.projectionEpoch
            carriedHistoryPresentationStamp = stamp
        }
        guard dataSource != nil, collectionView.bounds.width > 0 else {
            pendingApply = (entries, revision, bottomInset, hasConversationContent, historyPresentationStamp)
            return
        }
        let currentIDs = dataSource.snapshot().itemIdentifiers
        let isHistoryStructure = !currentIDs.isEmpty && currentIDs != entries.map(\.id)
            && (historyPresentationStamp != nil || entries.contains { entriesByID[$0.id] != nil })
        if isViewportMoving || isStreamingRenderingPausedForUserScroll
            || !presentationSchedule.permitsWork(at: CACurrentMediaTime())
            || isApplyingSnapshot || historyHeightPreparation != nil
            || historyPreparationDisplayLink != nil || isHistoryStructure
            || (historyPresentationStamp != nil && !currentIDs.isEmpty) {
            let retainedStamp = historyPresentationStamp ?? pendingApply?.historyPresentationStamp ?? carriedHistoryPresentationStamp
            pendingApply = (entries, revision, bottomInset, hasConversationContent, retainedStamp)
            presentationTargetGeneration &+= 1
#if DEBUG
            historyScrollMetrics?.historySchedulerEvent(.targetBuffered, generation: presentationTargetGeneration,
                entries: entries.count, batchEntries: 0, moving: isViewportMoving)
#endif
            schedulePendingPresentation()
            return
        }
        applyPreparedPresentation(entries: entries, revision: revision, bottomInset: bottomInset,
                                  hasConversationContent: hasConversationContent, historyPresentationStamp: historyPresentationStamp)
    }

    private var isViewportMoving: Bool {
        collectionView.isTracking || collectionView.isDragging || collectionView.isDecelerating
    }

    private func schedulePendingPresentation() {
        // 已识别的拖动停止 scheduler；didEnd 会重新创建。仅 tracking 的轻触由 tick 检查松手。
        guard !isUserGestureAwaitingEnd else { return }
        guard historyPreparationDisplayLink == nil else { return }
        let target = HistoryPreparationTarget()
        target.owner = self; target.generation = presentationScheduleGeneration
        let link = CADisplayLink(target: target, selector: #selector(HistoryPreparationTarget.tick))
        historyPreparationDisplayLink = link
        link.add(to: .main, forMode: .common)
    }

    private func applyPreparedPresentation(
        entries: [ConversationViewportEntry], revision: Int, bottomInset: CGFloat,
        hasConversationContent: Bool, historyPresentationStamp: HistoryPresentationStamp?, batch: PresentationBatch? = nil
    ) {
        let unchangedBottomInset = collectionView.contentInset.bottom == bottomInset
        collectionView.contentInset.bottom = bottomInset
        collectionView.verticalScrollIndicatorInsets.bottom = bottomInset
        guard collectionView.bounds.width > 0 else {
            // History can arrive during UIViewControllerRepresentable's first
            // update, before Auto Layout assigns the viewport its real width.
            // Applying now would seed every history row with the 80pt estimate.
            pendingApply = (entries, revision, bottomInset, hasConversationContent, historyPresentationStamp)
            return
        }
        let ids = entries.map(\.id)

        // Never replace the cell-provider model while an older diffable
        // snapshot is still animating. Otherwise UIKit can ask for a cell from
        // snapshot A and receive the entry from pending snapshot B, which is
        // visible as duplicated/overlapping rows during rapid disclosures.
        guard !isApplyingSnapshot, historyHeightPreparation == nil else {
            pendingApply = (entries, revision, bottomInset, hasConversationContent, historyPresentationStamp)
            return
        }

        let oldIDs = dataSource.snapshot().itemIdentifiers
        let hasStructureChange = oldIDs != ids
        let prependAnchor = hasStructureChange ? capturePrependAnchor(for: entries) : nil
        let oldEntriesByID = entriesByID
        let updatedEntriesByID = Dictionary(uniqueKeysWithValues: entries.map { ($0.id, $0) })
        let preservesVisibleHistoryWindow = hasStructureChange && canPreserveVisibleHistoryWindow(
            oldIDs: oldIDs, newIDs: ids, updatedEntries: updatedEntriesByID)
        entriesByID = updatedEntriesByID
        displayedSupplementalIDs = Set(supplementalEntries.map(\.id)).intersection(entriesByID.keys)
        let changed = entries.compactMap { previousRevisions[$0.id] == $0.revision ? nil : $0.id }
        let activeStreamingIDs = Set(entries.compactMap { entry in
            entry.streamingAssistant == nil ? nil : entry.id
        })
        streamingHeightMeasurers = streamingHeightMeasurers.filter {
            activeStreamingIDs.contains($0.key)
        }
        if hasStructureChange || changed.contains(where: { entriesByID[$0]?.allowsHeightCaching == true }) {
            let cacheableRevisions = Dictionary(
                uniqueKeysWithValues: entries.compactMap { entry in
                    entry.allowsHeightCaching ? (entry.id, entry.revision) : nil
                }
            )
            cellHeightCache = cellHeightCache.filter { key, _ in
                cacheableRevisions[key.id] == key.revision
            }
        }
        // 首帧只预热可见尾部。其他行由 sizeForItem 按需精确测量；历史插入仍按帧准备并保留阅读锚点。
        let prewarmEntries = hasAppliedSnapshot ? entries.filter { changed.contains($0.id) } : Array(entries.suffix(32))
        let prewarmResult = prewarmHeights(in: prewarmEntries)
        guard hasStructureChange || !changed.isEmpty else {
            invalidatePrewarmedItems(prewarmResult.measuredIDs)
            hasAppliedSnapshot = true
            initialContentHasConversation = hasConversationContent
            if !initialContentRevealPending { reportContentAvailability(hasConversationContent) }
            else {
                if hasConversationContent { collectionView.isHidden = false }
                placeInitialPositionIfNeeded()
                scheduleInitialContentReveal()
            }
            if let stamp = historyPresentationStamp, let sessionID {
                acknowledgePresentation(stamp, sessionID: sessionID, entries: entries, revision: revision,
                    bottomInset: bottomInset, hasConversationContent: hasConversationContent, batch: batch)
            }
            return
        }

        let disclosureAnchor = hasStructureChange ? pendingDisclosureAnchor : nil
        if hasStructureChange {
            pendingDisclosureAnchor = nil
        }
        let streamingChanged = changed.filter { id in
            previousRevisions[id] != nil && entriesByID[id]?.streamingAssistant != nil
        }
        // Attachment bytes arrive after their message metadata. The image has
        // already reserved its final size from width/height, so replacing the
        // placeholder must not reconfigure the self-sizing collection cell.
        // Doing so while the assistant row is growing makes diffable layout
        // reconciliation and tail following fight over contentOffset, which
        // is visible as several large up/down jumps.
        let stableUserMessageChanged = changed.filter { id in
            guard previousRevisions[id] != nil,
                  let old = oldEntriesByID[id]?.userMessage,
                  let new = entriesByID[id]?.userMessage else { return false }
            return Self.hasSameUserMessageGeometry(old, new)
        }
        let lightweightChanged = Set(streamingChanged + stableUserMessageChanged)
        let configuredChanged = changed.filter { !lightweightChanged.contains($0) }
        let keepTail = isPinnedToBottom && (
            revision != lastAppliedRevision ||
                (hasStructureChange && ids.last == "deep-search-progress")
        )
        let interactionGeneration = userInteractionGeneration
        lastAppliedRevision = revision
        previousRevisions = Dictionary(uniqueKeysWithValues: entries.map { ($0.id, $0.revision) })
        let streamingLayoutChanged = updateVisibleStreamingCells(
            streamingChanged,
            heightChangedIDs: prewarmResult.streamingHeightChangedIDs
        )
        updateVisibleUserMessageCells(stableUserMessageChanged)

        // Pure token growth never enters diffable reconciliation. The visible
        // streaming Markdown cell already consumed the newest snapshot; its
        // retained TextKit measurer invalidates only when the row gains a
        // line. Completed rows remain untouched.
        if !hasStructureChange, configuredChanged.isEmpty {
            if keepTail,
               streamingLayoutChanged,
               userInteractionGeneration == interactionGeneration,
               !collectionView.isTracking,
               !collectionView.isDragging,
               !collectionView.isDecelerating {
                followStreamingTail()
            }
            if let stamp = historyPresentationStamp, let sessionID {
                acknowledgePresentation(stamp, sessionID: sessionID, entries: entries, revision: revision,
                    bottomInset: bottomInset, hasConversationContent: hasConversationContent, batch: batch)
            }
            return
        }
        isApplyingSnapshot = true
        let applyingCommitID = UUID()
        activeSnapshotCommitID = applyingCommitID
        // 历史合并可能替换屏幕外的分组，并非严格suffix前插。只要当前可见窗口
        // 的条目、顺序与几何不变，也须在异步提交期间保位，不能等completion才拉回。
        if batch?.projectionEpoch != nil, preservesVisibleHistoryWindow,
           prependAnchor != nil, disclosureAnchor == nil, !keepTail, hasAppliedSnapshot,
           unchangedBottomInset, collectionView.contentSize.height > chatLayout.visibleBounds.height {
            nativeHistoryOffsetCommitID = applyingCommitID
            chatLayout.keepContentOffsetAtBottomOnBatchUpdates = true
        }
        if batch?.projectionEpoch != nil, hasAppliedSnapshot, !initialContentRevealPending {
            pendingHistoryRevealIDs.formUnion(Set(ids).subtracting(oldIDs).filter {
                entriesByID[$0]?.allowsScrollAnchoring == true && !displayedSupplementalIDs.contains($0)
            })
            pendingHistoryRevealIDs.formIntersection(ids)
            contentRevealGeometry = nil
        }
        let applyingSessionGeneration = sessionGeneration
        let applyingWidth = collectionView.bounds.width
        let applyingFontSize = UIFont.appPreferredFont(forTextStyle: .body).pointSize
        var snapshot = NSDiffableDataSourceSnapshot<Int, String>()
        snapshot.appendSections([0])
        snapshot.appendItems(ids)
        if #available(iOS 15.0, *) {
            let existingIDs = Set(dataSource.snapshot().itemIdentifiers)
            let reloadedIDs = configuredChanged.filter { id in
                guard existingIDs.contains(id),
                      snapshot.indexOfItem(id) != nil,
                      let old = oldEntriesByID[id],
                      let new = entriesByID[id] else { return false }
                return old.cellKind != new.cellKind
            }
            let reloadedIDSet = Set(reloadedIDs)
            let reconfiguredIDs = configuredChanged.filter {
                existingIDs.contains($0)
                    && snapshot.indexOfItem($0) != nil
                    && !reloadedIDSet.contains($0)
            }
            let reconfiguredIndexPaths = reconfiguredIDs.compactMap { dataSource.indexPath(for: $0) }
            chatLayout.reconfigureItems(at: reconfiguredIndexPaths)
            snapshot.reloadItems(reloadedIDs)
            snapshot.reconfigureItems(reconfiguredIDs)
        }
        let wasEmpty = !hasAppliedSnapshot
#if DEBUG
        let historyCommitSignpostID = prependAnchor.map { _ in OSSignpostID(log: Self.scrollPerformanceLog) }
        let metricsOldIDs = prependAnchor != nil && historyScrollMetrics != nil
            ? Set(dataSource.snapshot().itemIdentifiers) : []
        let metricsInsertedRows = metricsOldIDs.isEmpty ? 0 : ids.filter { !metricsOldIDs.contains($0) }.count
        let metricsLoadOrdinal = prependAnchor != nil
            ? historyScrollMetrics?.historyCommitBegan(rowCount: ids.count, insertedRows: metricsInsertedRows) : nil
        if let signpostID = historyCommitSignpostID {
            let oldIDs = Set(dataSource.snapshot().itemIdentifiers)
            os_signpost(.begin, log: Self.scrollPerformanceLog, name: "HistorySnapshotCommit",
                        signpostID: signpostID,
                        "rows=%{public}d old_rows=%{public}d inserted_rows=%{public}d",
                        ids.count, oldIDs.count, ids.filter { !oldIDs.contains($0) }.count)
        }
        let commitDiagnostic = commitDiagnostics.begin(ordinal: metricsLoadOrdinal, rowCount: ids.count, insertedRows: metricsInsertedRows)
        currentCommitDiagnostic = commitDiagnostic
#endif
        dataSource.apply(snapshot, animatingDifferences: disclosureAnchor != nil) { [weak self] in
            guard let self else { return }
#if DEBUG
            commitDiagnostic?.emit(.completionEnter, since: commitDiagnostic?.startedAt)
#endif
#if DEBUG
            if let signpostID = historyCommitSignpostID {
                os_signpost(.end, log: Self.scrollPerformanceLog, name: "HistorySnapshotCommit",
                            signpostID: signpostID, "rows=%{public}d", ids.count)
            }
#endif
            let finalize: () -> Void = { [weak self] in
                guard let self, self.activeSnapshotCommitID == applyingCommitID else { return }
                defer {
                    self.finishNativeHistoryOffsetCompensation(applyingCommitID,
                        reconcilePinnedState: self.sessionGeneration == applyingSessionGeneration)
                }
                self.pendingPresentationFinalization = nil
                self.activeSnapshotCommitID = nil
                self.isApplyingSnapshot = false
                let changedReadingPosition = self.userInteractionGeneration != interactionGeneration
                let changedGeometry = self.collectionView.bounds.width != applyingWidth
                    || UIFont.appPreferredFont(forTextStyle: .body).pointSize != applyingFontSize
                let finalAnchor = changedReadingPosition || changedGeometry
                    ? self.captureCurrentReadingAnchor() : prependAnchor
#if DEBUG
                let completionStartedAt = commitDiagnostic?.startIfCollecting()
#endif
            guard self.sessionGeneration == applyingSessionGeneration else {
                // 旧 session 的 apply 已经完成，但 viewport 仍保持隐藏。
                // 立即提交切换期间收到的最新 session snapshot。
                if self.pendingApply != nil { self.schedulePendingPresentation() }
                return
            }
#if DEBUG
            self.historyMetricsRowCount = ids.count
#endif
            if self.deferredFontReset { self.resetConfiguredFontIfNeeded() }
#if DEBUG
            let completionFlushStartedAt = commitDiagnostic?.startIfCollecting()
#endif
            self.flushLiveHeightCorrectionsIfNeeded()
#if DEBUG
            commitDiagnostic?.emit(.completionFlush, since: completionFlushStartedAt)
#endif
            self.hasAppliedSnapshot = true
            self.initialContentHasConversation = hasConversationContent
            if hasConversationContent { self.collectionView.isHidden = false }
            self.updateTopEdgeVisibility()
            if !self.initialContentRevealPending { self.reportContentAvailability(hasConversationContent) }
            if let finalAnchor, !keepTail || changedReadingPosition || changedGeometry {
                self.restore(finalAnchor)
            } else if let disclosureAnchor {
                self.stabilizeDisclosureAnchor(disclosureAnchor, remainingPasses: 3)
            }
            self.needsInitialBottomPlacement = self.needsInitialBottomPlacement || wasEmpty || !self.hasPlacedInitialPosition
            if self.needsInitialBottomPlacement {
                self.placeInitialPositionIfNeeded()
            } else if keepTail,
                      self.userInteractionGeneration == interactionGeneration,
                      !self.collectionView.isTracking,
                      !self.collectionView.isDragging,
                      !self.collectionView.isDecelerating {
                self.followStreamingTail()
            }
#if DEBUG
            if prependAnchor != nil {
                let path = finalAnchor.flatMap { self.dataSource.indexPath(for: $0.id) }
                let frame = path.flatMap { self.collectionView.layoutAttributesForItem(at: $0)?.frame }
                let error = frame.flatMap { frame in finalAnchor.map { Double(frame.minY - self.collectionView.contentOffset.y - $0.offsetFromViewportTop) } }
                self.historyScrollMetrics?.historyCommitEnded(
                    rowCount: ids.count, insertedRows: metricsInsertedRows,
                    anchorValid: frame != nil, anchorErrorPt: error, loadOrdinal: metricsLoadOrdinal
                )
            }
#endif
#if DEBUG
            if let batch, batch.inserted > 0 {
                self.historyScrollMetrics?.historySchedulerEvent(.batchPresented, generation: batch.generation,
                    entries: batch.entries, batchEntries: entries.count, insertedEntries: batch.inserted,
                    remainingEntries: batch.remaining, moving: self.isViewportMoving,
                    route: batch.route.rawValue, changedExistingCount: batch.changedExisting,
                    insertedBodyEntries: batch.insertedBody, insertedSupplementalEntries: batch.insertedSupplemental,
                    projectionEpoch: batch.projectionEpoch)
            }
#endif
            if let stamp = historyPresentationStamp, let sessionID = self.sessionID {
                self.acknowledgePresentation(stamp, sessionID: sessionID, entries: entries, revision: revision,
                    bottomInset: bottomInset, hasConversationContent: hasConversationContent, batch: batch)
            }
            if let target = self.searchTargetID, target != self.positionedSearchTargetID,
               let path = self.dataSource.indexPath(for: target) {
                self.needsBottomAlignment = false
                self.bottomAlignmentGeneration &+= 1
                self.collectionView.layoutIfNeeded()
                self.collectionView.scrollToItem(at: path, at: .centeredVertically, animated: false)
                self.onBottomAlignmentCompleted()
                self.positionedSearchTargetID = target
                self.setPinned(false)
            }
#if DEBUG
            if let commitDiagnostic { commitDiagnostic.complete(visibleCells: self.collectionView.visibleCells, completionStartedAt: completionStartedAt) }
            self.warmHostPool?.report()
#endif
            // 即使所有高度命中，也不能在 completion 同一调用栈递归提交下一批。
            self.scheduleInitialContentReveal()
            if self.pendingApply != nil || self.deferredTimelineSnapshot != nil || !self.pendingHistoryRevealIDs.isEmpty {
                self.schedulePendingPresentation()
            }
            self.finishStreamingRenderResumeIfPossible()
            }
            let admitFinalization: () -> Void = { [weak self] in
                guard let self else { return }
                guard self.activeSnapshotCommitID == applyingCommitID else {
                    self.finishNativeHistoryOffsetCompensation(applyingCommitID)
                    return
                }
                guard self.sessionGeneration == applyingSessionGeneration else {
                    self.finishNativeHistoryOffsetCompensation(applyingCommitID)
                    self.activeSnapshotCommitID = nil; self.isApplyingSnapshot = false
                    if self.pendingApply != nil || self.deferredTimelineSnapshot != nil { self.schedulePendingPresentation() }
                    return
                }
                if self.isViewportMoving || self.isUserGestureAwaitingEnd
                    || self.userInteractionGeneration != interactionGeneration
                    || !self.presentationSchedule.permitsWork(at: CACurrentMediaTime())
                    || self.collectionView.bounds.width != applyingWidth
                    || UIFont.appPreferredFont(forTextStyle: .body).pointSize != applyingFontSize {
                    self.pendingPresentationFinalization = (applyingSessionGeneration, finalize)
                    self.schedulePendingPresentation()
                } else { finalize() }
            }
#if DEBUG
            if let gate = self.historyDiffableCompletionGateForTesting { gate(admitFinalization) }
            else { admitFinalization() }
#else
            admitFinalization()
#endif
        }
#if DEBUG
        commitDiagnostic?.emit(.diffableCallReturn, since: commitDiagnostic?.startedAt)
#endif
    }

    private func finishNativeHistoryOffsetCompensation(_ commitID: UUID, reconcilePinnedState: Bool = false) {
        guard nativeHistoryOffsetCommitID == commitID else { return }
        nativeHistoryOffsetCommitID = nil
        chatLayout.keepContentOffsetAtBottomOnBatchUpdates = false
        if reconcilePinnedState { updatePinnedState(for: collectionView) }
    }

    private func acknowledgePresentation(_ stamp: HistoryPresentationStamp, sessionID: String,
                                        entries: [ConversationViewportEntry], revision: Int, bottomInset: CGFloat,
                                        hasConversationContent: Bool, batch: PresentationBatch?) {
        guard stamp.projectionEpoch >= latestHistoryProjectionEpoch else { return }
        if let acknowledged = lastAcknowledgedHistoryStamp, acknowledged.operationID == stamp.operationID,
           acknowledged.projectionEpoch >= stamp.projectionEpoch { return }
        // 布局提交不等于用户已能看到内容。只保留最新确认，待透明度恢复后再释放准入。
        if !pendingHistoryRevealIDs.isEmpty || (initialContentRevealPending && initialContentHasConversation) {
            let generation = sessionGeneration
            pendingContentRevealStamp = stamp
            pendingContentRevealAcknowledgement = { [weak self] in
                guard let self, self.sessionGeneration == generation, self.sessionID == sessionID else { return }
                self.acknowledgePresentation(stamp, sessionID: sessionID, entries: entries, revision: revision,
                    bottomInset: bottomInset, hasConversationContent: hasConversationContent, batch: batch)
            }
            schedulePendingPresentation()
            return
        }
        lastAcknowledgedHistoryStamp = stamp
#if DEBUG
        historyScrollMetrics?.historySchedulerEvent(.finalAck, generation: batch?.generation ?? presentationTargetGeneration,
            entries: entries.count, batchEntries: entries.count, moving: isViewportMoving,
            route: batch?.route.rawValue, changedExistingCount: batch?.changedExisting ?? 0,
            projectionEpoch: stamp.projectionEpoch)
        historyScrollMetrics?.historyOperationCommitted(operationID: stamp.operationID, projectionEpoch: stamp.projectionEpoch, rowCount: entries.count)
#endif
        onHistoryPresented(sessionID, historyPresentationConsumerID, stamp)
        if carriedHistoryPresentationStamp == stamp { carriedHistoryPresentationStamp = nil }
    }

    private func reportContentAvailability(_ hasContent: Bool) {
        guard hasContent != lastReportedContentAvailability else { return }
        lastReportedContentAvailability = hasContent
        onContentAvailabilityChanged(sessionID, hasContent)
    }

    @discardableResult
    private func updateVisibleStreamingCells(
        _ ids: [String],
        heightChangedIDs: Set<String>
    ) -> Bool {
        for id in ids {
            guard let entry = entriesByID[id],
                  let payload = entry.streamingAssistant,
                  let indexPath = dataSource.indexPath(for: id),
                  let cell = collectionView.cellForItem(at: indexPath) as? StreamingAssistantCell else { continue }
            configureStreamingMeasurement(cell, entry: entry)
            cell.apply(payload)
        }
        let invalidatedIndexPaths = ids.compactMap { id -> IndexPath? in
            guard heightChangedIDs.contains(id) else { return nil }
            return dataSource.indexPath(for: id)
        }
        guard !invalidatedIndexPaths.isEmpty else { return false }

#if DEBUG
        streamingLayoutInvalidationCount &+= 1
#endif
        let context = ChatLayoutInvalidationContext()
        // Glyphs are appended for every projected chunk, but the collection
        // layout only changes when TextKit reports that the text crossed a
        // line boundary. This keeps streaming caught up with the gateway
        // instead of re-laying out the full conversation every display frame.
        context.invalidateLayoutMetrics = true
        context.invalidateItems(at: invalidatedIndexPaths)
        chatLayout.invalidateLayout(with: context)
        collectionView.layoutIfNeeded()
        return true
    }

    private func updateVisibleUserMessageCells(_ ids: [String]) {
        for id in ids {
            guard let payload = entriesByID[id]?.userMessage,
                  let indexPath = dataSource.indexPath(for: id),
                  let cell = collectionView.cellForItem(at: indexPath) as? UserMessageCell else { continue }
            // Geometry is deliberately unchanged. Updating only UIImageView.image
            // avoids a collection-layout invalidation and preserves the anchor.
            cell.apply(payload, containerWidth: chatLayout.layoutFrame.width)
        }
    }

    private static func hasSameUserMessageGeometry(
        _ lhs: ConversationViewportEntry.UserMessage,
        _ rhs: ConversationViewportEntry.UserMessage
    ) -> Bool {
        guard lhs.text == rhs.text,
              lhs.showsCopyButton == rhs.showsCopyButton,
              lhs.files == rhs.files,
              lhs.images.count == rhs.images.count else { return false }
        return zip(lhs.images, rhs.images).allSatisfy { old, new in
            old.id == new.id
                && old.width == new.width
                && old.height == new.height
                && old.name == new.name
        }
    }

    /// 首屏尾部提前测量；UICollectionView 请求其他行时 sizeForItem 仍返回精确高度。
    /// 历史前插沿用分帧预热，防止阅读锚点因估计高度发生跳动。
    private func prewarmHeights(in entries: [ConversationViewportEntry]) -> HeightPrewarmResult {
        let width = chatLayout.layoutFrame.width > 0
            ? chatLayout.layoutFrame.width
            : collectionView.bounds.width
        guard width > 0 else { return HeightPrewarmResult() }

        var measuredIDs = Set<String>()
        var streamingHeightChangedIDs = Set<String>()
        for entry in entries where entry.allowsHeightCaching {
            let key = CellMeasurementKey(
                id: entry.id,
                revision: entry.revision,
                width: width
            )
            guard cellHeightCache[key] == nil else { continue }
#if DEBUG
            let measurementKind: String = switch entry.cellKind {
            case .hosting: "hosted"
            case .streamingAssistant: "streaming_assistant"
            case .userMessage: "user_message"
            }
            let rowMeasurement = historyScrollMetrics?.beginRowMeasurement(
                rowID: entry.id, kind: measurementKind, widthPoints: width,
                source: historyHeightPreparation == nil ? "viewport_prewarm" : "history_preparation"
            )
            // 外层完整成本包括 detached host 销毁和高度缓存写入；子段仅供定位。
            defer { rowMeasurement?.finish() }
#endif
            let measurement: (height: CGFloat, changed: Bool)
            if let streaming = entry.streamingAssistant {
                let measurer = streamingHeightMeasurers[entry.id]
                    ?? StreamingTextHeightMeasurer()
                streamingHeightMeasurers[entry.id] = measurer
                measurement = measurer.measure(text: streaming.text, width: width)
            } else {
#if DEBUG
                if let pool = warmHostPool, let token = entry.warmHostToken,
                   entry.cellKind == .hosting {
                    let context = warmHostContext(width: width)
                    let prepared = DebugPreparedConversationHost(entry: entry, token: token, context: context, parent: self, collection: collectionView)
                    let height = prepared.measureHeight()
                    if height.isFinite, height > 0, pool.store(prepared) {
                        measurement = (height, false)
                    } else {
                        prepared.invalidate()
                        measurement = (Self.measureHeight(for: entry, width: width), false)
                    }
                } else {
                    measurement = (Self.measureHeight(for: entry, width: width), false)
                }
#else
                measurement = (Self.measureHeight(for: entry, width: width), false)
#endif
            }
            let height = measurement.height
            guard height.isFinite, height > 0 else { continue }
            cellHeightCache[key] = ceil(height)
            measuredIDs.insert(entry.id)
            if measurement.changed {
                streamingHeightChangedIDs.insert(entry.id)
            }
        }
        return HeightPrewarmResult(
            measuredIDs: measuredIDs,
            streamingHeightChangedIDs: streamingHeightChangedIDs
        )
    }

    /// 只为当前展示批次准备新行/变更行；稳定旧行不会因缓存淘汰被整页重新预热。
    private func prepareHistoryHeightsIfNeeded(in entries: [ConversationViewportEntry],
        route: HistoryViewportPresentationSchedule.Route, changedExisting: Int, targetEntries: Int) -> Bool {
        let width = chatLayout.layoutFrame.width > 0
            ? chatLayout.layoutFrame.width : collectionView.bounds.width
        guard width > 0 else { return false }
        let unmeasuredEntries = entries.filter { entry in
            (entriesByID[entry.id] == nil || previousRevisions[entry.id] != entry.revision)
                && entry.allowsHeightCaching && cellHeightCache[
                    CellMeasurementKey(id: entry.id, revision: entry.revision, width: width)
                ] == nil
        }
        guard !unmeasuredEntries.isEmpty else { return false }
        historyHeightPreparation = (unmeasuredEntries, 0, width, presentationTargetGeneration, route, changedExisting, targetEntries)
#if DEBUG
        historyScrollMetrics?.historyPrepare(rowCount: entries.count)
        let signpostID = OSSignpostID(log: Self.scrollPerformanceLog)
        historyPreparationSignpostID = signpostID
        historyPreparationMeasuredRows = 0
        os_signpost(.begin, log: Self.scrollPerformanceLog, name: "HistoryHeightPreparation",
                    signpostID: signpostID, "rows=%{public}d", unmeasuredEntries.count)
#endif
        return true
    }

    /// 所有恢复和后续批次都只从这个 display tick 入口推进。
    private func prepareNextHistoryHeightBatch(generation: Int) {
        guard generation == presentationScheduleGeneration else { return }
        let now = CACurrentMediaTime()
        guard !isViewportMoving, !isUserGestureAwaitingEnd else {
            presentationSchedule.noteInteraction(at: now)
            return
        }
        guard presentationSchedule.permitsWork(at: now) else { return }
        isStreamingRenderingPausedForUserScroll = false
        if let finalization = pendingPresentationFinalization {
            if finalization.generation == sessionGeneration { finalization.finalize() }
            else {
                if let commitID = activeSnapshotCommitID { finishNativeHistoryOffsetCompensation(commitID) }
                pendingPresentationFinalization = nil; activeSnapshotCommitID = nil; isApplyingSnapshot = false
            }
            return
        }
        guard !isApplyingSnapshot else { return }
        if deferredFontReset { resetConfiguredFontIfNeeded() }
        // 同一历史epoch的live可以继续到来；完整历史已提交后先完成这次视觉收尾，
        // 不能因latest live永远非空而使渐显与最终确认饥饿。新历史epoch仍优先准备。
        if !initialContentRevealPending, historyHeightPreparation == nil, preparedPresentation == nil,
           let stamp = pendingContentRevealStamp, stamp.projectionEpoch >= latestHistoryProjectionEpoch,
           !historyPresentationOperationIsPending || historyPresentationOperationIsTerminal {
            _ = revealContentIfStable()
            return
        }
        if preparedPresentation == nil, let deferred = deferredTimelineSnapshot {
            deferredTimelineSnapshot = nil
            render(deferred)
        }
        if requiresFreshEntriesAfterDisclosure { return }
        guard collectionView.bounds.width > 0 else { return }
        if let candidate = preparedPresentation {
            let width = chatLayout.layoutFrame.width > 0 ? chatLayout.layoutFrame.width : collectionView.bounds.width
            let newest = pendingApply.map { Dictionary($0.entries.map { ($0.id, $0.cellKind) }, uniquingKeysWith: { _, new in new }) }
            let incompatible = newest.map { kinds in candidate.entries.contains { kinds[$0.id] != $0.cellKind } } ?? false
            if candidate.width != width || candidate.fontSize != UIFont.appPreferredFont(forTextStyle: .body).pointSize
                || candidate.sessionGeneration != sessionGeneration || incompatible {
                preparedPresentation = nil
                cancelHistoryHeightPreparation(stopScheduling: false)
                return
            }
        }
        if let candidate = preparedPresentation, candidate.historyEpoch < latestHistoryProjectionEpoch {
            preparedPresentation = nil
            cancelHistoryHeightPreparation(stopScheduling: false)
            return
        }
        if var preparation = historyHeightPreparation {
            let width = chatLayout.layoutFrame.width > 0 ? chatLayout.layoutFrame.width : collectionView.bounds.width
            guard width == preparation.width else {
                preparedPresentation = nil
                cancelHistoryHeightPreparation(stopScheduling: false)
                return
            }
            // 一行 sizeThatFits 是不可抢占的原子工作；不把 3ms 软预算冒充硬上限。
#if DEBUG
            let preparationGeneration = preparation.generation
            historyScrollMetrics?.historySchedulerEvent(.prepareAdmitted, generation: preparationGeneration,
                entries: preparation.targetEntries, batchEntries: 1, moving: isViewportMoving,
                route: preparation.route.rawValue, changedExistingCount: preparation.changedExisting,
                projectionEpoch: preparedPresentation?.sourceStamp?.projectionEpoch)
#endif
            _ = prewarmHeights(in: [preparation.entries[preparation.nextIndex]])
            preparation.nextIndex += 1
#if DEBUG
            historyPreparationMeasuredRows = preparation.nextIndex
            historyScrollMetrics?.historySchedulerEvent(.preparationFinished, generation: preparationGeneration,
                entries: preparation.targetEntries, batchEntries: 1, moving: isViewportMoving,
                route: preparation.route.rawValue, changedExistingCount: preparation.changedExisting,
                projectionEpoch: preparedPresentation?.sourceStamp?.projectionEpoch)
#endif
            if preparation.nextIndex < preparation.entries.count { historyHeightPreparation = preparation }
            else { cancelHistoryHeightPreparation(stopScheduling: false) }
            // 准备结束后仍等待下一 tick，不能把测量与 commit 堆在同一帧。
            return
        }
        if preparedPresentation == nil {
            guard let pending = pendingApply else {
                if !pendingLiveHeightCorrections.isEmpty { flushLiveHeightCorrectionsIfNeeded() }
                if deferredTimelineSnapshot == nil {
                    guard revealContentIfStable() else { return }
                    guard restoreHistoryScrollIndicatorIfStable() else { return }
#if DEBUG
                    historyScrollMetrics?.historySchedulerEvent(.idleDrained, generation: presentationTargetGeneration,
                        entries: entriesByID.count, batchEntries: 0, moving: isViewportMoving)
#endif
                    historyPreparationDisplayLink?.invalidate(); historyPreparationDisplayLink = nil
                    finishStreamingRenderResumeIfPossible()
                }
                return
            }
            let currentIDs = dataSource.snapshot().itemIdentifiers
            let targetSupplementalIDs = Set(supplementalEntries.map(\.id))
            let old = currentIDs.compactMap { id -> HistoryViewportPresentationSchedule.Row? in
                guard let entry = entriesByID[id] else { return nil }
                return .init(id: id, revision: entry.revision, kind: Self.presentationKind(entry),
                             supplemental: displayedSupplementalIDs.contains(id))
            }
            let target = pending.entries.map { HistoryViewportPresentationSchedule.Row(id: $0.id,
                revision: $0.revision, kind: Self.presentationKind($0), supplemental: targetSupplementalIDs.contains($0.id)) }
            let plan = HistoryViewportPresentationSchedule.plan(current: old, target: target, maximumInsertedEntries: 1)
            let entries = plan.indices.map { pending.entries[$0] }
            let existingIDs = Set(currentIDs)
            let insertedRows = entries.filter { !existingIDs.contains($0.id) }
            let inserted = insertedRows.count
            let insertedSupplemental = insertedRows.filter { targetSupplementalIDs.contains($0.id) }.count
            let batch = PresentationBatch(generation: presentationTargetGeneration, entries: pending.entries.count,
                remaining: plan.remaining, inserted: inserted, route: plan.route, changedExisting: plan.changedExisting,
                insertedBody: inserted - insertedSupplemental, insertedSupplemental: insertedSupplemental,
                projectionEpoch: pending.historyPresentationStamp?.projectionEpoch)
            preparedPresentation = PreparedPresentation(entries: entries, revision: pending.revision,
                bottomInset: pending.bottomInset, hasConversationContent: pending.hasConversationContent,
                sourceStamp: pending.historyPresentationStamp, batch: batch,
                width: chatLayout.layoutFrame.width > 0 ? chatLayout.layoutFrame.width : collectionView.bounds.width,
                fontSize: UIFont.appPreferredFont(forTextStyle: .body).pointSize, sessionGeneration: sessionGeneration,
                historyEpoch: latestHistoryProjectionEpoch)
            if prepareHistoryHeightsIfNeeded(in: entries, route: plan.route, changedExisting: plan.changedExisting,
                                            targetEntries: pending.entries.count) { return }
        }
        guard let candidate = preparedPresentation else { return }
        preparedPresentation = nil
        let batch = candidate.batch
        // 同epoch live可保留为唯一latest pending，不让不停生成使完整历史永远无法确认。
        if batch.remaining == 0, presentationTargetGeneration == batch.generation { pendingApply = nil }
#if DEBUG
        historyScrollMetrics?.historySchedulerEvent(.commitAdmitted, generation: batch.generation,
            entries: batch.entries, batchEntries: candidate.entries.count,
            insertedEntries: batch.inserted, remainingEntries: batch.remaining, moving: isViewportMoving,
            route: batch.route.rawValue, changedExistingCount: batch.changedExisting,
            insertedBodyEntries: batch.insertedBody, insertedSupplementalEntries: batch.insertedSupplemental,
            projectionEpoch: candidate.sourceStamp?.projectionEpoch)
#endif
        applyPreparedPresentation(entries: candidate.entries, revision: candidate.revision, bottomInset: candidate.bottomInset,
            hasConversationContent: candidate.hasConversationContent,
            historyPresentationStamp: batch.remaining == 0 ? candidate.sourceStamp : nil, batch: batch)
    }

    private static func presentationKind(_ entry: ConversationViewportEntry) -> Int {
        switch entry.cellKind {
        case .hosting: 0
        case .streamingAssistant: 1
        case .userMessage: 2
        }
    }

    private func cancelHistoryHeightPreparation(stopScheduling: Bool = true) {
#if DEBUG
        if historyHeightPreparation != nil {
            historyScrollMetrics?.historyPreparationEnded(rowCount: historyMetricsRowCount)
        }
        if let signpostID = historyPreparationSignpostID {
            os_signpost(.end, log: Self.scrollPerformanceLog, name: "HistoryHeightPreparation",
                        signpostID: signpostID, "prepared_rows=%{public}d",
                        historyPreparationMeasuredRows)
            historyPreparationSignpostID = nil
        }
#endif
        if stopScheduling {
            historyPreparationDisplayLink?.invalidate()
            historyPreparationDisplayLink = nil
        }
        historyHeightPreparation = nil
    }

    private func configureStreamingMeasurement(
        _ cell: StreamingAssistantCell,
        entry: ConversationViewportEntry
    ) {
        guard let payload = entry.streamingAssistant else {
            cell.disableMeasurementCaching()
            return
        }
        cell.configureMeasurement(
            id: entry.id,
            revision: entry.revision,
            cachedHeight: { [weak self] width in
                self?.cellHeightCache[
                    CellMeasurementKey(id: entry.id, revision: entry.revision, width: width)
                ]
            },
            storeHeight: { [weak self] width, height in
                self?.cellHeightCache[
                    CellMeasurementKey(id: entry.id, revision: entry.revision, width: width)
                ] = height
            },
            measureHeight: { width in
                StreamingAssistantCell.estimatedHeight(for: payload, width: width)
            }
        )
    }

    private func invalidatePrewarmedItems(_ ids: Set<String>) {
        let indexPaths = ids.compactMap { dataSource.indexPath(for: $0) }
        guard !indexPaths.isEmpty else { return }
        let context = ChatLayoutInvalidationContext()
        context.invalidateLayoutMetrics = true
        context.invalidateItems(at: indexPaths)
        chatLayout.invalidateLayout(with: context)
        collectionView.layoutIfNeeded()
    }

    /// A hosted row owns its vertical extent. The following row is positioned
    /// from that resolved extent; no per-cell gap or overlap compensation is
    /// involved. This live value corrects detached SwiftUI premeasurement when
    /// Markdown wraps differently after joining the real window hierarchy.
    private func receiveLiveHostedHeight(
        id: String,
        revision: Int,
        width: CGFloat,
        height: CGFloat
    ) {
        guard width > 0, height.isFinite, height > 0 else { return }
        let key = CellMeasurementKey(id: id, revision: revision, width: width)
        pendingLiveHeightCorrections[key] = LiveHeightCorrection(
            id: id,
            revision: revision,
            width: width,
            height: ceil(height)
        )
        guard !isLiveHeightCorrectionScheduled else { return }
        isLiveHeightCorrectionScheduled = true
        DispatchQueue.main.async { [weak self] in
            self?.flushLiveHeightCorrectionsIfNeeded()
        }
    }

    private func flushLiveHeightCorrectionsIfNeeded() {
        guard !isViewportMoving, !isStreamingRenderingPausedForUserScroll,
              presentationSchedule.permitsWork(at: CACurrentMediaTime()) else {
            isLiveHeightCorrectionScheduled = false
            schedulePendingPresentation()
            return
        }
        guard !isApplyingSnapshot else {
            isLiveHeightCorrectionScheduled = false
            return
        }
        isLiveHeightCorrectionScheduled = false
        let corrections = Array(pendingLiveHeightCorrections.values)
        pendingLiveHeightCorrections.removeAll(keepingCapacity: true)

        var indexPaths: [IndexPath] = []
        for correction in corrections {
            guard entriesByID[correction.id]?.revision == correction.revision else { continue }
            let key = CellMeasurementKey(
                id: correction.id,
                revision: correction.revision,
                width: correction.width
            )
            guard cellHeightCache[key].map({ abs($0 - correction.height) >= 0.5 }) ?? true else {
                continue
            }
#if DEBUG
            currentCommitDiagnostic?.observeCorrection(previous: cellHeightCache[key], height: correction.height)
#endif
            cellHeightCache[key] = correction.height
            if let indexPath = dataSource.indexPath(for: correction.id) {
                indexPaths.append(indexPath)
            }
        }
        guard !indexPaths.isEmpty else { return }

#if DEBUG
        let heightFlushStartedAt = currentCommitDiagnostic?.startIfCollecting()
#endif
        let context = ChatLayoutInvalidationContext()
        context.invalidateLayoutMetrics = true
        context.invalidateItems(at: indexPaths)
        chatLayout.invalidateLayout(with: context)
        collectionView.layoutIfNeeded()
#if DEBUG
        currentCommitDiagnostic?.emit(.liveHeightFlush, since: heightFlushStartedAt)
#endif

        if isPinnedToBottom,
           !collectionView.isTracking,
           !collectionView.isDragging,
           !collectionView.isDecelerating {
            followStreamingTail()
        }
    }

    private static func measureHostedHeight(
        for entry: ConversationViewportEntry,
        width: CGFloat
    ) -> CGFloat {
#if DEBUG
        let rowMeasurement = DebugHistoryRowMeasurements.activeScope
        let createStartedAt = rowMeasurement?.startStage()
#endif
        let host = UIHostingController(
            rootView: TopPinnedConversationContent(
                id: entry.id,
                revision: entry.revision,
                content: entry.content,
                onHeightChange: nil
            )
        )
        // A hosting controller embedded in a scrolling cell otherwise derives
        // safe-area insets from the cell's current intersection with the
        // window. Those insets change while the user drags and make SwiftUI
        // move/reflow content inside an unchanged collection-view item.
        if #available(iOS 16.4, *) {
            host.safeAreaRegions = []
        }
        host.view.backgroundColor = .clear
#if DEBUG
        rowMeasurement?.finishStage("host_create", startedAt: createStartedAt)
        let sizeStartedAt = rowMeasurement?.startStage()
#endif
        let height = host.sizeThatFits(
            in: CGSize(width: width, height: CGFloat.greatestFiniteMagnitude)
        ).height
#if DEBUG
        rowMeasurement?.finishStage("host_size", startedAt: sizeStartedAt)
#endif
        return height
    }

    private static func measureHeight(
        for entry: ConversationViewportEntry,
        width: CGFloat
    ) -> CGFloat {
        if let userMessage = entry.userMessage {
            return UserMessageCell.estimatedHeight(for: userMessage, width: width)
        }
        if let streamingAssistant = entry.streamingAssistant {
            return StreamingAssistantCell.estimatedHeight(
                for: streamingAssistant,
                width: width
            )
        }
        return measureHostedHeight(for: entry, width: width)
    }

    /// `configure` can run before the collection view receives its final
    /// bounds. Repeat the deterministic user-row premeasurement when the cell
    /// provider is invoked so an image row never falls back to another reused
    /// cell's provisional height.
    private func cacheUserMessageHeightIfNeeded(
        _ userMessage: ConversationViewportEntry.UserMessage,
        entry: ConversationViewportEntry
    ) {
        let width = chatLayout.layoutFrame.width
        guard width > 0 else { return }
        let key = CellMeasurementKey(id: entry.id, revision: entry.revision, width: width)
        guard cellHeightCache[key] == nil else { return }
        cellHeightCache[key] = UserMessageCell.estimatedHeight(for: userMessage, width: width)
    }

    /// Capture the stable header before its descendants are inserted or
    /// removed. The header remains its own collection item, so the subsequent
    /// diffable update never needs to infer a position from a resizing host.
    func prepareForDisclosureUpdate(anchorID id: String) {
        preparedPresentation = nil
        cancelHistoryHeightPreparation(stopScheduling: false)
        requiresFreshEntriesAfterDisclosure = true
        schedulePendingPresentation()
        stopInertialScrolling()
        needsBottomAlignment = false
        bottomAlignmentGeneration &+= 1
        guard let indexPath = dataSource.indexPath(for: id),
              let attributes = collectionView.layoutAttributesForItem(at: indexPath) else { return }
        disclosureAnchorGeneration &+= 1
        pendingDisclosureAnchor = DisclosureAnchor(
            anchor: VisibleAnchor(
                id: id,
                offsetFromViewportTop: attributes.frame.minY
                    - collectionView.contentOffset.y
                    - collectionView.adjustedContentInset.top
            ),
            generation: disclosureAnchorGeneration,
            interactionGeneration: userInteractionGeneration
        )
    }

    private func stabilizeDisclosureAnchor(
        _ disclosureAnchor: DisclosureAnchor,
        remainingPasses: Int
    ) {
        guard disclosureAnchor.generation == disclosureAnchorGeneration,
              disclosureAnchor.interactionGeneration == userInteractionGeneration,
              !collectionView.isTracking,
              !collectionView.isDragging,
              !collectionView.isDecelerating,
              let indexPath = dataSource.indexPath(for: disclosureAnchor.anchor.id) else { return }

        chatLayout.restoreContentOffset(with: ChatLayoutPositionSnapshot(
            indexPath: indexPath,
            edge: .top,
            offset: disclosureAnchor.anchor.offsetFromViewportTop
        ))

        guard remainingPasses > 0 else { return }
        DispatchQueue.main.async { [weak self] in
            self?.stabilizeDisclosureAnchor(
                disclosureAnchor,
                remainingPasses: remainingPasses - 1
            )
        }
    }

    func sizeForItem(
        _ chatLayout: CollectionViewChatLayout,
        at indexPath: IndexPath
    ) -> ItemSize {
        guard let id = dataSource?.itemIdentifier(for: indexPath),
              let entry = entriesByID[id] else {
            return .estimated(CGSize(width: chatLayout.layoutFrame.width, height: 80))
        }
        let width = chatLayout.layoutFrame.width
        guard width > 0 else {
            return .estimated(CGSize(width: width, height: 80))
        }
        if entry.allowsHeightCaching {
            let key = CellMeasurementKey(
                id: entry.id,
                revision: entry.revision,
                width: width
            )
            if let height = cellHeightCache[key] {
                return .exact(CGSize(width: width, height: height))
            }

            // Bounds can change without a new timeline revision (rotation,
            // split view, or an interactive page transition). Never fall back
            // to ChatLayout's 80pt estimate for that new width: a following
            // process row would otherwise be placed inside the rendered Agent
            // response until another invalidation happens to correct it.
            let measuredHeight = Self.measureHeight(for: entry, width: width)
            if measuredHeight.isFinite, measuredHeight > 0 {
                let height = ceil(measuredHeight)
                cellHeightCache[key] = height
                return .exact(CGSize(width: width, height: height))
            }
        }
        return .estimated(CGSize(width: width, height: 80))
    }

    func alignmentForItem(
        _ chatLayout: CollectionViewChatLayout,
        at indexPath: IndexPath
    ) -> ChatItemAlignment {
        .fullWidth
    }

    /// Streaming updates only need one lightweight tail adjustment. The
    /// multi-pass stabilizer in `scrollToBottom()` is reserved for initial
    /// history presentation and explicit user requests; running it for every
    /// token competes directly with touch handling and Markdown measurement.
    private func followStreamingTail() {
        guard !dataSource.snapshot().itemIdentifiers.isEmpty else { return }
        collectionView.layoutIfNeeded()
        let bottomOffset = max(
            -collectionView.adjustedContentInset.top,
            collectionView.contentSize.height - collectionView.bounds.height + collectionView.adjustedContentInset.bottom
        )
        isProgrammaticScroll = true
        collectionView.setContentOffset(CGPoint(x: 0, y: bottomOffset), animated: false)
        isProgrammaticScroll = false
        setPinned(true)
    }

    func scrollToBottom() {
        guard !dataSource.snapshot().itemIdentifiers.isEmpty else { return }
        needsBottomAlignment = true
        bottomAlignmentGeneration &+= 1
        let generation = bottomAlignmentGeneration
        alignBottomIfNeeded()

        confirmStableBottomAlignment(
            generation: generation,
            previousContentHeight: nil,
            stablePasses: 0,
            remainingPasses: 10
        )
    }

    private func confirmStableBottomAlignment(
        generation: Int,
        previousContentHeight: CGFloat?,
        stablePasses: Int,
        remainingPasses: Int
    ) {
        // Self-sized Markdown cells may settle over several main-loop passes.
        // Keep the latest generation aligned while measuring its content
        // height, and reveal only after two consecutive stable measurements.
        DispatchQueue.main.async { [weak self] in
            guard let self, self.bottomAlignmentGeneration == generation else { return }
            self.alignBottom()
            let height = self.collectionView.contentSize.height
            let nextStablePasses: Int
            if let previousContentHeight, abs(previousContentHeight - height) < 0.5 {
                nextStablePasses = stablePasses + 1
            } else {
                nextStablePasses = 0
            }
            if nextStablePasses >= 2 || remainingPasses <= 0 {
                self.onBottomAlignmentCompleted()
            } else {
                self.confirmStableBottomAlignment(
                    generation: generation,
                    previousContentHeight: height,
                    stablePasses: nextStablePasses,
                    remainingPasses: remainingPasses - 1
                )
            }
        }
    }

    private func alignBottomIfNeeded() {
        guard needsBottomAlignment else { return }
        alignBottom()
    }

    private func alignBottom() {
        guard !dataSource.snapshot().itemIdentifiers.isEmpty,
              collectionView.bounds.height > 0 else { return }
        needsBottomAlignment = false
        isProgrammaticScroll = true
        collectionView.collectionViewLayout.invalidateLayout()
        collectionView.layoutIfNeeded()

        if collectionView.numberOfSections > 0,
           collectionView.numberOfItems(inSection: 0) > 0 {
            let lastItem = IndexPath(item: collectionView.numberOfItems(inSection: 0) - 1, section: 0)
            collectionView.scrollToItem(at: lastItem, at: .bottom, animated: false)
            collectionView.layoutIfNeeded()
        }

        let bottomOffset = max(
            -collectionView.adjustedContentInset.top,
            collectionView.contentSize.height - collectionView.bounds.height + collectionView.adjustedContentInset.bottom
        )
        collectionView.setContentOffset(CGPoint(x: 0, y: bottomOffset), animated: false)
        isProgrammaticScroll = false
        setPinned(true)
    }

    func scrollViewDidScroll(_ scrollView: UIScrollView) {
        updateTopEdgeVisibility()
        // A drag is not the same thing as leaving the tail. In particular,
        // dragging upward again while already at the bottom only produces the
        // rubber-band overscroll (`contentOffset.y > maximumOffsetY`). Keep the
        // viewport pinned through that bounce and reveal the jump button only
        // after the user has actually moved away from the tail.
        // Native batch compensation can move bounds before UIKit publishes the
        // matching content size. That transient geometry is not a user request
        // to follow the tail. Actual touch/drag/deceleration always wins.
        if nativeHistoryOffsetCommitID == nil || isUserGestureAwaitingEnd
            || scrollView.isTracking || scrollView.isDragging || scrollView.isDecelerating {
            setPinned(isAtBottom(scrollView))
        }
        if !isProgrammaticScroll,
           (scrollView.isDragging || scrollView.isDecelerating),
           scrollView.contentOffset.y + scrollView.adjustedContentInset.top < 420 {
#if DEBUG
            let now = CACurrentMediaTime()
            historyScrollMetrics?.historyApproached(rowCount: historyMetricsRowCount)
            if now - lastApproachTopSignpostTime >= 0.5 {
                lastApproachTopSignpostTime = now
                os_signpost(.event, log: Self.scrollPerformanceLog, name: "HistoryApproachTop",
                            "offset_y=%{public}.2f", Double(scrollView.contentOffset.y))
            }
#endif
            onApproachingTop()
        }
    }

    func scrollViewWillBeginDragging(_ scrollView: UIScrollView) {
#if DEBUG
        if historyScrollMetrics == nil {
            historyScrollMetrics = DebugHistoryScrollMetrics.makeIfEnabled()
            // 只在首个真实阅读手势开始时采样；已有分页准备/提交不能被隐去。
            let initialPipelineInFlight = historyPresentationOperationIsPending || historyHeightPreparation != nil || isApplyingSnapshot
                || pendingApply != nil || deferredTimelineSnapshot != nil
                || isLiveHeightCorrectionScheduled || !pendingLiveHeightCorrections.isEmpty
            historyScrollMetrics?.beginViewport(rowCount: historyMetricsRowCount,
                                               initialPipelineInFlight: initialPipelineInFlight)
        }
        historyScrollMetrics?.scrollBegan(rowCount: historyMetricsRowCount)
#endif
        // User interaction wins immediately over automatic tail following.
        revealInsertedHistoryImmediately()
        pauseStreamingRenderingForUserScroll()
        needsBottomAlignment = false
        bottomAlignmentGeneration &+= 1
        userInteractionGeneration &+= 1
        // 用户主动阅读优先于之前尚未执行的自动搜索定位。
        if searchTargetID != nil { positionedSearchTargetID = searchTargetID }
    }

    func scrollViewDidEndDragging(_ scrollView: UIScrollView, willDecelerate decelerate: Bool) {
        guard !decelerate else { return }
        isUserGestureAwaitingEnd = false
#if DEBUG
        historyScrollMetrics?.scrollEnded(rowCount: historyMetricsRowCount)
#endif
        updatePinnedState(for: scrollView)
        resumeStreamingRenderingAfterUserScroll()
    }

    func scrollViewDidEndDecelerating(_ scrollView: UIScrollView) {
        isUserGestureAwaitingEnd = false
#if DEBUG
        historyScrollMetrics?.scrollEnded(rowCount: historyMetricsRowCount)
#endif
        updatePinnedState(for: scrollView)
        resumeStreamingRenderingAfterUserScroll()
    }

    private func pauseStreamingRenderingForUserScroll() {
        isUserGestureAwaitingEnd = true
        historyScrollIndicatorGeometry = nil
        presentationSchedule.noteInteraction(at: CACurrentMediaTime())
        presentationScheduleGeneration &+= 1
        historyPreparationDisplayLink?.invalidate(); historyPreparationDisplayLink = nil
        isStreamingRenderingPausedForUserScroll = true
        isStreamingRenderResumePending = false
#if DEBUG
        historyScrollMetrics?.historySchedulerEvent(.interrupted, generation: presentationTargetGeneration,
            entries: pendingApply?.entries.count ?? 0, batchEntries: 0, moving: isViewportMoving)
#endif
    }

    private func resumeStreamingRenderingAfterUserScroll() {
        presentationSchedule.noteInteraction(at: CACurrentMediaTime())
        isStreamingRenderResumePending = true
#if DEBUG
        historyScrollMetrics?.historySchedulerEvent(.idleWait, generation: presentationTargetGeneration,
            entries: pendingApply?.entries.count ?? 0, batchEntries: 0, moving: isViewportMoving)
#endif
        schedulePendingPresentation()
    }

    private func finishStreamingRenderResumeIfPossible() {
        guard isStreamingRenderResumePending,
              !isStreamingRenderingPausedForUserScroll,
              !isApplyingSnapshot,
              historyHeightPreparation == nil,
              pendingApply == nil, deferredTimelineSnapshot == nil else { return }
        isStreamingRenderResumePending = false
    }

    private func updatePinnedState(for scrollView: UIScrollView) {
        setPinned(isAtBottom(scrollView))
    }

    private func isAtBottom(_ scrollView: UIScrollView) -> Bool {
        let minimumOffsetY = -scrollView.adjustedContentInset.top
        let maximumOffsetY = max(
            minimumOffsetY,
            scrollView.contentSize.height
                - scrollView.bounds.height
                + scrollView.adjustedContentInset.bottom
        )
        return maximumOffsetY - scrollView.contentOffset.y <= 28
    }

    private struct VisibleAnchor {
        let id: String
        let offsetFromViewportTop: CGFloat
    }

    private struct DisclosureAnchor {
        let anchor: VisibleAnchor
        let generation: Int
        let interactionGeneration: Int
    }

    private func canPreserveVisibleHistoryWindow(
        oldIDs: [String], newIDs: [String], updatedEntries: [String: ConversationViewportEntry]
    ) -> Bool {
        let visibleIDs = collectionView.indexPathsForVisibleItems.sorted().compactMap { path -> String? in
            guard path.item < oldIDs.count, entriesByID[oldIDs[path.item]]?.allowsScrollAnchoring == true,
                  let frame = collectionView.layoutAttributesForItem(at: path)?.frame,
                  frame.intersects(collectionView.bounds) else { return nil }
            return oldIDs[path.item]
        }
        // 拉取更早事件会合并过程分组，旧窗口顶部的分组头可能被替换。
        // 允许移除前导行，但保留库所锚定的末行；窗口内部删除或重排仍须回退。
        guard let firstRetained = visibleIDs.firstIndex(where: { updatedEntries[$0] != nil }) else { return false }
        let retainedVisibleIDs = Array(visibleIDs[firstRetained...])
        guard retainedVisibleIDs.allSatisfy({ updatedEntries[$0] != nil }),
              let first = retainedVisibleIDs.first, let start = newIDs.firstIndex(of: first),
              start + retainedVisibleIDs.count <= newIDs.count,
              Array(newIDs[start..<(start + retainedVisibleIDs.count)]) == retainedVisibleIDs else { return false }
        return retainedVisibleIDs.allSatisfy { id in
            guard let old = entriesByID[id], let new = updatedEntries[id],
                  previousRevisions[id] == old.revision, old.cellKind == new.cellKind,
                  new.allowsScrollAnchoring else { return false }
            if old.revision == new.revision { return true }
            // 图片字节补齐不改变预留尺寸；不能因此撤掉整个窗口的位置补偿。
            guard let oldMessage = old.userMessage, let newMessage = new.userMessage else { return false }
            return Self.hasSameUserMessageGeometry(oldMessage, newMessage)
        }
    }

    private func capturePrependAnchor(for newEntries: [ConversationViewportEntry]) -> VisibleAnchor? {
        let oldIDs = dataSource.snapshot().itemIdentifiers
        let newIDs = newEntries.map(\.id)
        let retainedContentIDs = Set(newEntries.filter(\.allowsScrollAnchoring).map(\.id))
        // 加载按钮始终位于第 0 行，不能用它判断是否追加了更早的消息，
        // 也不能把它当作阅读锚点。比较真实内容前方的条目，兼顾最后一页移除按钮。
        guard let oldFirstIndex = oldIDs.firstIndex(where: {
            entriesByID[$0]?.allowsScrollAnchoring == true && retainedContentIDs.contains($0)
        }),
              let newFirstIndex = newIDs.firstIndex(of: oldIDs[oldFirstIndex]),
              oldIDs[..<oldFirstIndex] != newIDs[..<newFirstIndex],
              let indexPath = collectionView.indexPathsForVisibleItems.sorted().first(where: { path in
                  guard path.item < oldIDs.count,
                        entriesByID[oldIDs[path.item]]?.allowsScrollAnchoring == true,
                        retainedContentIDs.contains(oldIDs[path.item]),
                        let attributes = collectionView.layoutAttributesForItem(at: path) else { return false }
                  return attributes.frame.intersects(collectionView.bounds)
              }),
              let attributes = collectionView.layoutAttributesForItem(at: indexPath) else { return nil }
        return VisibleAnchor(
            id: oldIDs[indexPath.item],
            offsetFromViewportTop: attributes.frame.minY - collectionView.contentOffset.y
        )
    }

    private func captureCurrentReadingAnchor() -> VisibleAnchor? {
        for path in collectionView.indexPathsForVisibleItems.sorted() {
            guard let id = dataSource.itemIdentifier(for: path), entriesByID[id]?.allowsScrollAnchoring == true,
                  let frame = collectionView.layoutAttributesForItem(at: path)?.frame else { continue }
            return VisibleAnchor(id: id, offsetFromViewportTop: frame.minY - collectionView.contentOffset.y)
        }
        return nil
    }

    private func restore(_ anchor: VisibleAnchor) {
        guard let item = dataSource.snapshot().indexOfItem(anchor.id) else { return }
#if DEBUG
        os_signpost(.event, log: Self.scrollPerformanceLog, name: "HistoryAnchorBefore",
                    "offset_y=%{public}.2f", Double(collectionView.contentOffset.y))
#endif
#if DEBUG
        let anchorLayoutStartedAt = currentCommitDiagnostic?.startIfCollecting()
#endif
        collectionView.collectionViewLayout.invalidateLayout()
        collectionView.layoutIfNeeded()
#if DEBUG
        currentCommitDiagnostic?.emit(.anchorLayout, since: anchorLayoutStartedAt)
        let anchorOffsetStartedAt = currentCommitDiagnostic?.startIfCollecting()
#endif
        restorePosition(anchor, item: item)
#if DEBUG
        currentCommitDiagnostic?.emit(.anchorOffset, since: anchorOffsetStartedAt)
#endif
#if DEBUG
        if let frame = collectionView.layoutAttributesForItem(at: IndexPath(item: item, section: 0))?.frame {
            let error = frame.minY - collectionView.contentOffset.y - anchor.offsetFromViewportTop
            os_signpost(.event, log: Self.scrollPerformanceLog, name: "HistoryAnchorAfter",
                        "anchor_valid=1 offset_y=%{public}.2f error_pt=%{public}.2f",
                        Double(collectionView.contentOffset.y), Double(error))
        } else {
            os_signpost(.event, log: Self.scrollPerformanceLog, name: "HistoryAnchorAfter",
                        "anchor_valid=0 offset_y=%{public}.2f", Double(collectionView.contentOffset.y))
        }
#endif
    }

    private func restorePosition(_ anchor: VisibleAnchor, item: Int? = nil) {
        guard let item = item ?? dataSource.snapshot().indexOfItem(anchor.id) else { return }
        let indexPath = IndexPath(item: item, section: 0)
        guard let attributes = collectionView.layoutAttributesForItem(at: indexPath) else { return }
        let minimumY = -collectionView.adjustedContentInset.top
        let maximumY = max(minimumY, collectionView.contentSize.height - collectionView.bounds.height
                           + collectionView.adjustedContentInset.bottom)
        let offsetY = min(maximumY, max(minimumY, attributes.frame.minY - anchor.offsetFromViewportTop))
        isProgrammaticScroll = true
        collectionView.setContentOffset(
            CGPoint(x: 0, y: offsetY),
            animated: false
        )
        isProgrammaticScroll = false
    }

    private func placeInitialPositionIfNeeded() {
        guard !hasPlacedInitialPosition, hasAppliedSnapshot, !isApplyingSnapshot,
              !dataSource.snapshot().itemIdentifiers.isEmpty,
              view.bounds.height > 0 else { return }
        hasPlacedInitialPosition = true
        needsInitialBottomPlacement = false
        if let anchor = reusableReadingAnchor, dataSource.indexPath(for: anchor.id) != nil, searchTargetID == nil {
            reusableReadingAnchor = nil
            restore(anchor)
            setPinned(false)
            onBottomAlignmentCompleted()
        } else if let target = searchTargetID, dataSource.indexPath(for: target) != nil {
            setPinned(false)
        } else {
            scrollToBottom()
        }
    }

    private func setPinned(_ value: Bool) {
        guard value != isPinnedToBottom else { return }
        isPinnedToBottom = value
        DispatchQueue.main.async { [weak self] in
            self?.onPinnedToBottomChanged(value)
        }
    }

    private func updateTopEdgeVisibility() {
        let halfPixel = 0.5 / max(traitCollection.displayScale, 1)
        let scrolled = hasPlacedInitialPosition && !collectionView.isHidden
            && !dataSource.snapshot().itemIdentifiers.isEmpty
            && collectionView.contentOffset.y + collectionView.adjustedContentInset.top > halfPixel
        guard scrolled != hasScrolledFromTop else { return }
        hasScrolledFromTop = scrolled
        // 只在离开或回到顶端时通知 SwiftUI，避免逐帧重组与布局期间修改状态。
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            onScrolledFromTopChanged(hasScrolledFromTop)
        }
    }

}

private struct LiveHeightCorrection {
    let id: String
    let revision: Int
    let width: CGFloat
    let height: CGFloat
}

private struct HeightPrewarmResult {
    var measuredIDs = Set<String>()
    var streamingHeightChangedIDs = Set<String>()
}

/// Retains TextKit's glyph and line-fragment state across streaming revisions.
/// Appending a suffix therefore lays out only the newly received text instead
/// of measuring the entire accumulated response again on every display frame.
private final class StreamingTextHeightMeasurer {
    private let textStorage = NSTextStorage()
    private let layoutManager = NSLayoutManager()
    private let textContainer = NSTextContainer(
        size: CGSize(width: 1, height: CGFloat.greatestFiniteMagnitude)
    )
    private var measuredText = ""
    private var measuredWidthInPixels = 0
    private var measuredFont = UIFont.appPreferredFont(forTextStyle: .body)
    private var lastHeight: CGFloat?

    init() {
        textContainer.lineFragmentPadding = 0
        textContainer.maximumNumberOfLines = 0
        textContainer.lineBreakMode = .byWordWrapping
        layoutManager.addTextContainer(textContainer)
        textStorage.addLayoutManager(layoutManager)
    }

    func measure(text: String, width: CGFloat) -> (height: CGFloat, changed: Bool) {
        let availableWidth = max(1, width - 4)
        let widthInPixels = Int((availableWidth * UIScreen.main.scale).rounded())
        if widthInPixels != measuredWidthInPixels {
            measuredWidthInPixels = widthInPixels
            textContainer.size = CGSize(
                width: availableWidth,
                height: CGFloat.greatestFiniteMagnitude
            )
        }

        let currentFont = UIFont.appPreferredFont(forTextStyle: .body)
        if !currentFont.isEqual(measuredFont) {
            measuredFont = currentFont
            if textStorage.length > 0 {
                textStorage.addAttribute(
                    .font,
                    value: currentFont,
                    range: NSRange(location: 0, length: textStorage.length)
                )
            }
        }

        if text != measuredText {
            if text.hasPrefix(measuredText) {
                let suffix = String(text.dropFirst(measuredText.count))
                textStorage.append(attributed(suffix, font: currentFont))
            } else {
                textStorage.setAttributedString(attributed(text, font: currentFont))
            }
            measuredText = text
        }

        let textHeight: CGFloat
        if textStorage.length == 0 {
            textHeight = 0
        } else {
            layoutManager.ensureLayout(for: textContainer)
            textHeight = ceil(layoutManager.usedRect(for: textContainer).height)
        }
        let height = ceil(15 + 26 + 7 + textHeight + 15)
        let changed = lastHeight.map { abs($0 - height) >= 0.5 } ?? true
        lastHeight = height
        return (height, changed)
    }

    private func attributed(_ text: String, font: UIFont) -> NSAttributedString {
        NSAttributedString(string: text, attributes: [.font: font])
    }
}

private struct CellMeasurementKey: Hashable {
    let id: String
    let revision: Int
    let widthInPixels: Int

    init(id: String, revision: Int, width: CGFloat) {
        self.id = id
        self.revision = revision
        widthInPixels = Int((width * UIScreen.main.scale).rounded())
    }
}

/// SwiftUI and TextKit can report adjacent fractional heights while the
/// collection view is moving (for example 34⅓pt and 34⅔pt). ChatLayout treats
/// each value as a new preferred size. Quantize the explicit result so every
/// request in one diffable transaction receives the same height.
class StableSelfSizingCollectionViewCell: UICollectionViewCell {
    private var measurementID: String?
    private var cachedHeight: ((CGFloat) -> CGFloat?)?
    private var storeHeight: ((CGFloat, CGFloat) -> Void)?
    private var measureHeight: ((CGFloat) -> CGFloat)?

    func configureMeasurement(
        id: String,
        revision: Int,
        cachedHeight: @escaping (CGFloat) -> CGFloat?,
        storeHeight: @escaping (CGFloat, CGFloat) -> Void,
        measureHeight: ((CGFloat) -> CGFloat)? = nil
    ) {
        measurementID = "\(id)#\(revision)"
        self.cachedHeight = cachedHeight
        self.storeHeight = storeHeight
        self.measureHeight = measureHeight
    }

    func disableMeasurementCaching() {
        measurementID = nil
        cachedHeight = nil
        storeHeight = nil
        measureHeight = nil
    }

    override func prepareForReuse() {
        super.prepareForReuse()
        disableMeasurementCaching()
    }

    override func preferredLayoutAttributesFitting(
        _ layoutAttributes: UICollectionViewLayoutAttributes
    ) -> UICollectionViewLayoutAttributes {
        let width = layoutAttributes.size.width
        if measurementID != nil,
           let height = cachedHeight?(width),
           let stable = layoutAttributes.copy() as? UICollectionViewLayoutAttributes {
            stable.size.height = height
            return stable
        }

        // A view can initially inherit ChatLayout's estimate while its content
        // already draws at the ideal height. Use the deterministic measurement
        // supplied by the controller so cell bounds and rendered pixels agree.
        let explicitHeight = measureHeight?(width)
        let measured: CGSize
        if let explicitHeight, explicitHeight.isFinite, explicitHeight > 0 {
            measured = CGSize(width: width, height: explicitHeight)
        } else {
            contentView.bounds.size.width = width
            contentView.setNeedsLayout()
            contentView.layoutIfNeeded()
            measured = contentView.systemLayoutSizeFitting(
                CGSize(width: width, height: UIView.layoutFittingCompressedSize.height),
                withHorizontalFittingPriority: .required,
                verticalFittingPriority: .fittingSizeLevel
            )
        }
        let measuredHeight: CGFloat
        if measured.height.isFinite, measured.height > 0 {
            measuredHeight = measured.height
        } else {
            measuredHeight = super.preferredLayoutAttributesFitting(layoutAttributes).size.height
        }
        guard let stable = layoutAttributes.copy() as? UICollectionViewLayoutAttributes else {
            return layoutAttributes
        }
        stable.size.width = width
        stable.size.height = ceil(measuredHeight)
        if measurementID != nil {
            storeHeight?(width, stable.size.height)
        }
        return stable
    }
}

/// A deterministic SwiftUI host for ChatLayout cells. UIHostingConfiguration
/// owns a private content view whose bounds origin can survive reconfiguration
/// while a historical snapshot is self-sizing. Keeping one explicit hosting
/// controller lets the cell reset the root identity and pins its rendered view
/// to `contentView.bounds` on every layout pass.
final class HostedConversationCell: StableSelfSizingCollectionViewCell {
    private var hostingController: UIHostingController<AnyView>?
    private var representedIdentity: String?
#if DEBUG
    var hasHostingControllerForCommitDiagnostics: Bool { hostingController != nil }
    private var installedWarmRelay: DebugConversationWarmHeightRelay?
    private var warmRootGeneration = 0
    var warmHostIdentityForTesting: ObjectIdentifier? { hostingController.map(ObjectIdentifier.init) }
    var hostedTraitsForTesting: UITraitCollection? { hostingController?.traitCollection }
    private(set) var lastWarmTransferFailure: DebugWarmHostInstallFailure?
    private(set) var lastWarmTraitDifferences: [String] = []
    private(set) var lastWarmTargetWasAttached = false

    @discardableResult
    func installPreparedHost(_ prepared: DebugPreparedConversationHost, entry: ConversationViewportEntry,
                             parent: UIViewController, collection: UICollectionView,
                             onHeightChange: @escaping (CGFloat, CGFloat) -> Void) -> Bool {
        lastWarmTransferFailure = nil
        lastWarmTraitDifferences = []
        lastWarmTargetWasAttached = contentView.window != nil
        func reject(_ failure: DebugWarmHostInstallFailure, actualTraits: UITraitCollection? = nil) -> Bool {
            lastWarmTransferFailure = failure
            if let actualTraits {
                lastWarmTraitDifferences = failure.publicTraitDifferences(actual: actualTraits, expected: failure == .postMoveTraitsMismatch
                        ? (prepared.renderTraits ?? prepared.context.ownerTraits) : prepared.context.ownerTraits)
            }
            prepared.discardPreparation()
            apply(entry, parent: parent, onHeightChange: onHeightChange)
            return false
        }
        guard prepared.consumed, !prepared.installed, prepared.renderTraits != nil else { return reject(.stateMismatch) }
        guard entry.warmHostToken === prepared.token else { return reject(.tokenMismatch) }
        guard prepared.host.parent === parent, prepared.context.owner == ObjectIdentifier(parent),
              prepared.context.collection == ObjectIdentifier(collection),
              prepared.context.window == collection.window.map(ObjectIdentifier.init),
              (window == nil || isDescendant(of: collection)) else { return reject(.ownerMismatch) }
        guard parent.traitCollection == prepared.context.ownerTraits else {
            return reject(.parentContextMismatch, actualTraits: parent.traitCollection)
        }
        guard UIFont.appPreferredFont(forTextStyle: .body).pointSize == prepared.context.fontSize else { return reject(.fontMismatch) }
        warmRootGeneration &+= 1
        let host = prepared.host
        host.view.frame = contentView.bounds
        host.view.bounds.origin = .zero
        contentView.addSubview(host.view)
        if #available(iOS 17.0, *) { host.updateTraitsIfNeeded() }
        guard host.traitCollection == prepared.renderTraits else {
            return reject(.postMoveTraitsMismatch, actualTraits: host.traitCollection)
        }
        installedWarmRelay?.invalidate()
        if let old = hostingController, old !== host {
            old.willMove(toParent: nil)
            old.view.removeFromSuperview()
            old.removeFromParent()
        }
        let identity = "\(entry.id)#\(entry.revision)"
        representedIdentity = identity
        hostingController = host
        installedWarmRelay = prepared.relay
        prepared.relay.sink = { [weak self] height in
            guard let self, self.representedIdentity == identity,
                  self.hostingController === host else { return }
            let width = self.contentView.bounds.width
            guard width > 0 else { return }
            onHeightChange(width, height)
        }
        prepared.markInstalled()
        host.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        host.view.setNeedsLayout()
        setNeedsLayout()
        return true
    }

#endif

    func apply(
        _ entry: ConversationViewportEntry,
        parent: UIViewController,
        onHeightChange: @escaping (CGFloat, CGFloat) -> Void
    ) {
#if DEBUG
        installedWarmRelay?.invalidate()
        installedWarmRelay = nil
        let expectedWarmGeneration: Int?
        if DebugConversationWarmHostExperiment.enabled {
            warmRootGeneration &+= 1
            expectedWarmGeneration = warmRootGeneration
        } else { expectedWarmGeneration = nil }
#endif
        let identity = "\(entry.id)#\(entry.revision)"
        representedIdentity = identity
        let root = AnyView(
            TopPinnedConversationContent(
                id: entry.id,
                revision: entry.revision,
                content: entry.content,
                onHeightChange: { [weak self] height in
                    guard let self, self.representedIdentity == identity else { return }
#if DEBUG
                    if let expectedWarmGeneration, self.warmRootGeneration != expectedWarmGeneration { return }
#endif
                    let width = self.contentView.bounds.width
                    guard width > 0 else { return }
                    onHeightChange(width, height)
                }
            )
        )
        let host: UIHostingController<AnyView>
        if let existing = hostingController {
            host = existing
            if existing.parent !== parent {
                existing.willMove(toParent: nil)
                existing.view.removeFromSuperview()
                existing.removeFromParent()
                parent.addChild(existing)
                contentView.addSubview(existing.view)
                existing.didMove(toParent: parent)
            }
            existing.rootView = root
        } else {
            let created = UIHostingController(rootView: root)
            if #available(iOS 16.4, *) {
                created.safeAreaRegions = []
            }
            created.view.backgroundColor = .clear
            created.view.clipsToBounds = true
            parent.addChild(created)
            contentView.addSubview(created.view)
            created.didMove(toParent: parent)
            hostingController = created
            host = created
        }

        host.view.frame = contentView.bounds
        host.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        host.view.bounds.origin = .zero
        host.view.setNeedsLayout()
        setNeedsLayout()
    }

    func measuredHeight(for width: CGFloat) -> CGFloat {
        guard let hostingController else { return 0 }
        return hostingController.sizeThatFits(
            in: CGSize(width: width, height: CGFloat.greatestFiniteMagnitude)
        ).height
    }

    override func prepareForReuse() {
        super.prepareForReuse()
#if DEBUG
        installedWarmRelay?.invalidate()
        installedWarmRelay = nil
        if DebugConversationWarmHostExperiment.enabled { warmRootGeneration &+= 1 }
#endif
        representedIdentity = nil
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        guard let hostedView = hostingController?.view else { return }
        hostedView.frame = contentView.bounds
        hostedView.bounds.origin = .zero
    }
}

/// User messages are intentionally rendered without `UIHostingConfiguration`.
/// They are sparse in the timeline, so creating a new SwiftUI host exactly as
/// one enters the viewport produced a visible hitch. This lightweight UIKit
/// cell keeps the same bubble appearance while making reuse and measurement
/// inexpensive.
private final class UserMessageCell: StableSelfSizingCollectionViewCell {
    static let reuseIdentifier = "UserMessageCell"

    private static let maximumSingleImageHeight: CGFloat = 320
    private static let stackedCardSide: CGFloat = 164
    private static let stackedCardOffset: CGFloat = 12

    private struct ImageLayout: Equatable {
        let id: String
        let width: Int
        let height: Int
    }

    /// Frame-based thumbnail composition avoids making the collection view
    /// solve a second nested stack of competing width/height constraints.
    /// Multiple images use equal square cards and `scaleAspectFill`, so cards
    /// may crop their thumbnail but never stretch the source image.
    private final class AttachmentPreviewControl: UIControl {
        private let visibleIDs: [String]
        private let preferredSize: CGSize
        private let cardSide: CGFloat
        private let cardOffset: CGFloat
        private let countLabel = UILabel()
        private(set) var imageViewsByID: [String: UIImageView] = [:]

        init(
            images: [ConversationViewportEntry.UserMessage.Image],
            availableWidth: CGFloat
        ) {
            visibleIDs = Array(images.prefix(3).map(\.id))
            preferredSize = UserMessageCell.previewSize(
                for: images,
                availableWidth: availableWidth
            )
            if images.count > 1 {
                let visibleCount = CGFloat(min(3, images.count))
                cardOffset = UserMessageCell.stackedCardOffset
                cardSide = preferredSize.width - cardOffset * (visibleCount - 1)
            } else {
                cardOffset = 0
                cardSide = preferredSize.width
            }
            super.init(frame: CGRect(origin: .zero, size: preferredSize))

            for payload in images.prefix(3).reversed() {
                let imageView = UIImageView()
                imageView.backgroundColor = .secondarySystemFill
                imageView.contentMode = .scaleAspectFill
                imageView.clipsToBounds = true
                imageView.layer.cornerRadius = images.count > 1 ? 18 : 11
                imageView.layer.cornerCurve = .continuous
                imageView.layer.borderWidth = images.count > 1 ? 2 : 0
                imageView.layer.borderColor = UIColor.systemBackground.cgColor
                imageView.accessibilityLabel = payload.name ?? String(localized: "图片附件")
                addSubview(imageView)
                imageViewsByID[payload.id] = imageView
            }

            countLabel.text = "\(images.count)张"
            countLabel.font = .appPreferredFont(forTextStyle: .caption1).withWeight(.semibold)
            countLabel.textColor = .white
            countLabel.textAlignment = .center
            countLabel.backgroundColor = UIColor.black.withAlphaComponent(0.58)
            countLabel.layer.cornerRadius = 12
            countLabel.layer.cornerCurve = .continuous
            countLabel.clipsToBounds = true
            countLabel.isHidden = images.count < 2
            addSubview(countLabel)

            isAccessibilityElement = true
            accessibilityTraits = .button
            accessibilityLabel = images.count > 1
                ? String(localized: "查看\(images.count)张图片")
                : String(localized: "查看图片")
        }

        required init?(coder: NSCoder) { nil }

        override var intrinsicContentSize: CGSize { preferredSize }

        override var isHighlighted: Bool {
            didSet {
                alpha = isHighlighted ? 0.78 : 1
            }
        }

        override func layoutSubviews() {
            super.layoutSubviews()
            guard !visibleIDs.isEmpty else { return }
            if visibleIDs.count == 1 {
                imageViewsByID[visibleIDs[0]]?.frame = bounds
                return
            }

            let count = visibleIDs.count
            for (index, id) in visibleIDs.enumerated() {
                // First attachment stays on top and closest to the message;
                // the remaining cards fan toward the upper trailing edge.
                let x = cardOffset * CGFloat(index)
                let y = cardOffset * CGFloat(count - 1 - index)
                imageViewsByID[id]?.frame = CGRect(x: x, y: y, width: cardSide, height: cardSide)
            }
            if let frontFrame = imageViewsByID[visibleIDs[0]]?.frame {
                countLabel.font = .appPreferredFont(forTextStyle: .caption1).withWeight(.semibold)
                let width = max(45, ceil(countLabel.intrinsicContentSize.width) + 12)
                let height = max(26, ceil(countLabel.font.lineHeight) + 6)
                countLabel.frame = CGRect(
                    x: frontFrame.maxX - width - 6,
                    y: frontFrame.maxY - height - 6,
                    width: width,
                    height: height
                )
                bringSubviewToFront(imageViewsByID[visibleIDs[0]]!)
                bringSubviewToFront(countLabel)
            }
        }
    }

    /// 文件卡片复用原有预览入口；回调由当前 cell payload 提供，避免复用时携带旧会话。
    private final class FileAttachmentCard: UIControl {
        private let icon = UIImageView()
        private let nameLabel = UILabel()
        private let sizeLabel = UILabel()
        private let file: PresentedFile
        private let action: () -> Void

        private static var nameFont: UIFont {
            UIFont.appPreferredFont(forTextStyle: .subheadline).withWeight(.medium)
        }
        private static var sizeFont: UIFont { .appPreferredFont(forTextStyle: .caption1) }

        static func height(for file: PresentedFile, width: CGFloat) -> CGFloat {
            let name = file.name ?? String(localized: "文件")
            let nameWidth = max(1, width - 84)
            let bounds = (name as NSString).boundingRect(
                with: CGSize(width: nameWidth, height: .greatestFiniteMagnitude),
                options: [.usesLineFragmentOrigin, .usesFontLeading],
                attributes: [.font: nameFont], context: nil)
            let nameHeight = min(ceil(bounds.height), ceil(nameFont.lineHeight * 2))
            let detailsHeight = file.bytes == nil ? 0 : 4 + ceil(sizeFont.lineHeight)
            return ceil(24 + max(48, nameHeight + detailsHeight))
        }

        init(file: PresentedFile, width: CGFloat, action: @escaping () -> Void) {
            self.file = file
            self.action = action
            super.init(frame: .zero)
            let name = file.name ?? String(localized: "文件")
            let kind = FileIconCatalog.shared.resolve(name: name, mediaType: nil)
            backgroundColor = UIColor(named: "FileCard_\(kind.colorFamily)") ?? .secondarySystemBackground
            layer.cornerRadius = 12
            layer.cornerCurve = .continuous
            icon.image = UIImage(named: "FileIcon_\(kind.assetKey)")
            icon.contentMode = .scaleAspectFit
            nameLabel.text = name
            nameLabel.font = Self.nameFont
            nameLabel.textColor = .label
            nameLabel.numberOfLines = 2
            nameLabel.lineBreakMode = .byTruncatingMiddle
            sizeLabel.font = Self.sizeFont
            sizeLabel.textColor = .secondaryLabel
            sizeLabel.text = file.bytes.map { ByteCountFormatter.string(fromByteCount: $0, countStyle: .file) }
            sizeLabel.isHidden = file.bytes == nil
            addSubview(icon)
            addSubview(nameLabel)
            addSubview(sizeLabel)
            isAccessibilityElement = true
            accessibilityTraits = .button
            addTarget(self, action: #selector(openFile), for: .touchUpInside)
            accessibilityIdentifier = "user-file-card"
            accessibilityLabel = [name, sizeLabel.text].compactMap { $0 }.joined(separator: ", ")
            translatesAutoresizingMaskIntoConstraints = false
            NSLayoutConstraint.activate([
                widthAnchor.constraint(equalToConstant: width),
                heightAnchor.constraint(equalToConstant: Self.height(for: file, width: width))
            ])
        }

        required init?(coder: NSCoder) { nil }

        @objc private func openFile() { action() }
        override func accessibilityActivate() -> Bool { action(); return true }

        override func layoutSubviews() {
            super.layoutSubviews()
            icon.frame = CGRect(x: 12, y: (bounds.height - 48) / 2, width: 48, height: 48)
            let textWidth = max(1, bounds.width - 84)
            let nameSize = nameLabel.sizeThatFits(CGSize(width: textWidth, height: Self.nameFont.lineHeight * 2))
            let nameHeight = min(ceil(nameSize.height), ceil(Self.nameFont.lineHeight * 2))
            let sizeHeight = sizeLabel.isHidden ? 0 : ceil(Self.sizeFont.lineHeight)
            let textHeight = nameHeight + (sizeLabel.isHidden ? 0 : 4 + sizeHeight)
            let top = (bounds.height - textHeight) / 2
            nameLabel.frame = CGRect(x: 72, y: top, width: textWidth, height: nameHeight)
            sizeLabel.frame = CGRect(x: 72, y: top + nameHeight + 4, width: textWidth, height: sizeHeight)
        }
    }

    private static let decodedImageCache = NSCache<NSString, UIImage>()

    private let stack = UIStackView()
    private let imageGrid = UIStackView()
    private let fileCards = UIStackView()
    private var renderedFiles: [PresentedFile] = []
    private var renderedFileWidth: CGFloat = 0
    private var renderedFileFontSize: CGFloat = 0
    private let bubbleView = UIView()
    private let messageLabel = UILabel()
    private let copyButton = UIButton(type: .system)
    private var copyResetWorkItem: DispatchWorkItem?
    private var renderedImageLayout: [ImageLayout] = []
    private var renderedContainerWidthInPixels = 0
    private var renderedImages: [ConversationViewportEntry.UserMessage.Image] = []
    private weak var imagePreviewControl: AttachmentPreviewControl?
    private var imageViewsByID: [String: UIImageView] = [:]
    private var renderedImageAvailability: [String: Bool] = [:]
    var onOpenImagePreview: (([ConversationImagePreviewItem], Int) -> Void)?
    private var onOpenFile: ((PresentedFile) -> Void)?

    static func estimatedHeight(for payload: ConversationViewportEntry.UserMessage, width: CGFloat) -> CGFloat {
        let horizontalBubbleInset: CGFloat = 34
        let horizontalTextPadding: CGFloat = 28
        let maximumTextWidth = max(1, width - horizontalBubbleInset - horizontalTextPadding)
        let textBounds = (payload.text as NSString).boundingRect(
            with: CGSize(width: maximumTextWidth, height: .greatestFiniteMagnitude),
            options: [.usesLineFragmentOrigin, .usesFontLeading],
            attributes: [.font: UIFont.appPreferredFont(forTextStyle: .body)],
            context: nil
        )
        let imageHeight = previewSize(
            for: payload.images,
            availableWidth: width - horizontalBubbleInset
        ).height
        var contentHeight: CGFloat = 0
        if imageHeight > 0 { contentHeight += imageHeight }
        let cardWidth = min(320, max(1, width - horizontalBubbleInset))
        for file in payload.files {
            if contentHeight > 0 { contentHeight += 8 }
            contentHeight += FileAttachmentCard.height(for: file, width: cardWidth)
        }
        if !payload.text.isEmpty {
            if contentHeight > 0 { contentHeight += 8 }
            contentHeight += ceil(textBounds.height) + 20
            if payload.showsCopyButton { contentHeight += 5 + 26 }
        }
        return ceil(24 + contentHeight)
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        backgroundConfiguration = UIBackgroundConfiguration.clear()

        stack.axis = .vertical
        stack.alignment = .trailing
        stack.spacing = 8
        stack.translatesAutoresizingMaskIntoConstraints = false

        imageGrid.axis = .vertical
        imageGrid.alignment = .trailing
        imageGrid.spacing = 6
        fileCards.axis = .vertical
        fileCards.alignment = .trailing
        fileCards.spacing = 8
        fileCards.isHidden = true

        bubbleView.layer.cornerRadius = 15
        bubbleView.layer.cornerCurve = .continuous
        bubbleView.layer.borderWidth = 0.7
        bubbleView.translatesAutoresizingMaskIntoConstraints = false

        messageLabel.font = .appPreferredFont(forTextStyle: .body)
        messageLabel.adjustsFontForContentSizeCategory = false
        messageLabel.textColor = .label
        messageLabel.numberOfLines = 0
        messageLabel.translatesAutoresizingMaskIntoConstraints = false

        copyButton.setImage(UIImage(named: "CopyMessage")?.withRenderingMode(.alwaysTemplate), for: .normal)
        copyButton.tintColor = .secondaryLabel
        copyButton.imageView?.contentMode = .scaleAspectFit
        copyButton.accessibilityLabel = String(localized: "复制正文")
        copyButton.addTarget(self, action: #selector(copyMessage), for: .touchUpInside)
        copyButton.translatesAutoresizingMaskIntoConstraints = false

        contentView.addSubview(stack)
        stack.addArrangedSubview(imageGrid)
        stack.addArrangedSubview(fileCards)
        stack.addArrangedSubview(bubbleView)
        bubbleView.addSubview(messageLabel)
        stack.addArrangedSubview(copyButton)
        stack.setCustomSpacing(5, after: bubbleView)

        let provisionalBottom = stack.bottomAnchor.constraint(
            equalTo: contentView.bottomAnchor,
            constant: -12
        )
        // UICollectionView first installs the layout's 80pt estimated height
        // before asking this deterministic cell for its cached final height.
        // A text row with its copy button has a taller required vertical chain.
        // Let only the outer edge yield during that provisional pass so UIKit
        // never has to break the label's content constraints; at the cached
        // final height this constraint is satisfied normally.
        provisionalBottom.priority = .init(999)

        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 12),
            stack.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
            stack.leadingAnchor.constraint(greaterThanOrEqualTo: contentView.leadingAnchor, constant: 34),
            provisionalBottom,

            messageLabel.topAnchor.constraint(equalTo: bubbleView.topAnchor, constant: 10),
            messageLabel.bottomAnchor.constraint(equalTo: bubbleView.bottomAnchor, constant: -10),
            messageLabel.leadingAnchor.constraint(equalTo: bubbleView.leadingAnchor, constant: 14),
            messageLabel.trailingAnchor.constraint(equalTo: bubbleView.trailingAnchor, constant: -14),

            copyButton.widthAnchor.constraint(equalToConstant: 16),
            copyButton.heightAnchor.constraint(equalToConstant: 26)
        ])
        updateBubbleColors()
    }

    required init?(coder: NSCoder) { nil }

    override func prepareForReuse() {
        super.prepareForReuse()
        copyResetWorkItem?.cancel()
        copyResetWorkItem = nil
        messageLabel.text = nil
        onOpenImagePreview = nil
        onOpenFile = nil
        // Keep an already-built attachment grid until the next payload is
        // known. UICollectionView commonly dequeues the same cell for the
        // same row when it re-enters the viewport; retaining the hierarchy
        // avoids rebuilding every image view and constraint during a fling.
        showCopied(false)
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        guard previousTraitCollection?.userInterfaceStyle != traitCollection.userInterfaceStyle else { return }
        updateBubbleColors()
    }

    func apply(_ payload: ConversationViewportEntry.UserMessage, containerWidth: CGFloat) {
        onOpenFile = payload.onOpenFile
        messageLabel.font = .appPreferredFont(forTextStyle: .body)
        if messageLabel.text != payload.text {
            messageLabel.text = payload.text
        }
        bubbleView.isHidden = payload.text.isEmpty
        copyButton.isHidden = payload.text.isEmpty || !payload.showsCopyButton
        applyImages(payload.images, containerWidth: containerWidth)
        applyFiles(payload.files, containerWidth: containerWidth)
    }

    private func applyFiles(_ files: [PresentedFile], containerWidth: CGFloat) {
        fileCards.isHidden = files.isEmpty
        let width = min(320, max(1, containerWidth - 34))
        let fontSize = UIFont.appPreferredFont(forTextStyle: .subheadline).pointSize
        guard files != renderedFiles || width != renderedFileWidth || fontSize != renderedFileFontSize else { return }
        for card in fileCards.arrangedSubviews {
            fileCards.removeArrangedSubview(card)
            card.removeFromSuperview()
        }
        renderedFiles = files
        renderedFileWidth = width
        renderedFileFontSize = fontSize
        for file in files {
            fileCards.addArrangedSubview(FileAttachmentCard(file: file, width: width) { [weak self] in
                self?.onOpenFile?(file)
            })
        }
    }

    private func applyImages(
        _ images: [ConversationViewportEntry.UserMessage.Image],
        containerWidth: CGFloat
    ) {
        imageGrid.isHidden = images.isEmpty
        renderedImages = images
        let layout = images.map { ImageLayout(id: $0.id, width: $0.width, height: $0.height) }
        let widthInPixels = Int((containerWidth * UIScreen.main.scale).rounded())

        // The gateway sends attachment metadata first and bytes later. Keep
        // the exact same views and constraints when only bytes have changed;
        // tearing down the grid here causes a transient zero-height pass in a
        // self-sizing UICollectionView cell and makes the bottom anchor jump.
        if layout == renderedImageLayout,
           widthInPixels == renderedContainerWidthInPixels {
            for image in images { updateImageContent(image) }
            return
        }

        clearImageGrid()
        renderedImageLayout = layout
        renderedContainerWidthInPixels = widthInPixels
        guard !images.isEmpty else { return }
        let availableWidth = max(1, containerWidth - 34)
        let preview = AttachmentPreviewControl(images: images, availableWidth: availableWidth)
        preview.addTarget(self, action: #selector(openImagePreview), for: .touchUpInside)
        imageGrid.addArrangedSubview(preview)
        imagePreviewControl = preview
        imageViewsByID = preview.imageViewsByID
        for image in images { updateImageContent(image) }
    }

    private func updateImageContent(_ payload: ConversationViewportEntry.UserMessage.Image) {
        guard let imageView = imageViewsByID[payload.id] else { return }
        let hasData = payload.data != nil
        guard renderedImageAvailability[payload.id] != hasData else { return }
        renderedImageAvailability[payload.id] = hasData
        guard let data = payload.data else {
            imageView.image = nil
            return
        }
        let key = payload.id as NSString
        if let cached = Self.decodedImageCache.object(forKey: key) {
            imageView.image = cached
        } else if let decoded = UIImage(data: data) {
            Self.decodedImageCache.setObject(decoded, forKey: key)
            imageView.image = decoded
        }
    }

    private func clearImageGrid() {
        for preview in imageGrid.arrangedSubviews {
            imageGrid.removeArrangedSubview(preview)
            preview.removeFromSuperview()
        }
        imagePreviewControl = nil
        imageViewsByID.removeAll(keepingCapacity: true)
        renderedImageAvailability.removeAll(keepingCapacity: true)
    }

    private static func previewSize(
        for images: [ConversationViewportEntry.UserMessage.Image],
        availableWidth: CGFloat
    ) -> CGSize {
        guard let first = images.first else { return .zero }
        if images.count == 1 {
            return displaySize(for: first, availableWidth: availableWidth)
        }
        let visibleCount = CGFloat(min(3, images.count))
        let side = min(
            stackedCardSide,
            max(96, floor(availableWidth - stackedCardOffset * (visibleCount - 1)))
        )
        let length = side + stackedCardOffset * (visibleCount - 1)
        return CGSize(width: length, height: length)
    }

    private static func displaySize(
        for image: ConversationViewportEntry.UserMessage.Image,
        availableWidth: CGFloat
    ) -> CGSize {
        let sourceWidth = CGFloat(max(1, image.width))
        let sourceHeight = CGFloat(max(1, image.height))
        let maximumWidth = min(320, availableWidth)
        let maximumHeight = maximumSingleImageHeight
        let scale = min(1, min(maximumWidth / sourceWidth, maximumHeight / sourceHeight))
        return CGSize(
            width: max(1, floor(sourceWidth * scale)),
            height: max(1, floor(sourceHeight * scale))
        )
    }

    @objc private func openImagePreview() {
        guard !renderedImages.isEmpty else { return }
        let items = renderedImages.map { payload in
            let key = payload.id as NSString
            let image = imageViewsByID[payload.id]?.image
                ?? Self.decodedImageCache.object(forKey: key)
                ?? payload.data.flatMap(UIImage.init(data:))
            if let image, Self.decodedImageCache.object(forKey: key) == nil {
                Self.decodedImageCache.setObject(image, forKey: key)
            }
            return ConversationImagePreviewItem(
                id: payload.id,
                image: image,
                name: payload.name
            )
        }
        onOpenImagePreview?(items, 0)
    }

    @objc private func copyMessage() {
        guard let text = messageLabel.text else { return }
        UIPasteboard.general.string = text
        showCopied(true)
        copyResetWorkItem?.cancel()
        let workItem = DispatchWorkItem { [weak self] in self?.showCopied(false) }
        copyResetWorkItem = workItem
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.4, execute: workItem)
    }

    private func showCopied(_ copied: Bool) {
        let image = copied
            ? UIImage(systemName: "checkmark", withConfiguration: UIImage.SymbolConfiguration(weight: .semibold))
            : UIImage(named: "CopyMessage")?.withRenderingMode(.alwaysTemplate)
        copyButton.setImage(image, for: .normal)
        copyButton.tintColor = copied
            ? UIColor(red: 0.18, green: 0.42, blue: 0.9, alpha: 1)
            : .secondaryLabel
        copyButton.accessibilityLabel = copied ? String(localized: "已复制") : String(localized: "复制正文")
    }

    private func updateBubbleColors() {
        let dark = traitCollection.userInterfaceStyle == .dark
        bubbleView.backgroundColor = UIColor(
            red: 0.18,
            green: 0.42,
            blue: 0.9,
            alpha: dark ? 0.24 : 0.11
        )
        bubbleView.layer.borderColor = UIColor(
            red: 0.18,
            green: 0.42,
            blue: 0.9,
            alpha: dark ? 0.34 : 0.08
        ).cgColor
    }
}

private final class ConversationImagePreviewController: UIViewController,
    UIPageViewControllerDataSource,
    UIPageViewControllerDelegate {

    private let items: [ConversationImagePreviewItem]
    private let initialIndex: Int
    private let pageController = UIPageViewController(
        transitionStyle: .scroll,
        navigationOrientation: .horizontal
    )
    private let countLabel = UILabel()

    init(items: [ConversationImagePreviewItem], initialIndex: Int) {
        self.items = items
        self.initialIndex = min(max(0, initialIndex), max(0, items.count - 1))
        super.init(nibName: nil, bundle: nil)
        modalPresentationCapturesStatusBarAppearance = true
    }

    required init?(coder: NSCoder) { nil }

    override var prefersStatusBarHidden: Bool { true }
    override var preferredStatusBarStyle: UIStatusBarStyle { .lightContent }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black

        addChild(pageController)
        pageController.view.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(pageController.view)
        pageController.didMove(toParent: self)
        pageController.dataSource = self
        pageController.delegate = self

        let closeButton = UIButton(type: .system)
        closeButton.setImage(
            UIImage(systemName: "xmark", withConfiguration: UIImage.SymbolConfiguration(weight: .semibold)),
            for: .normal
        )
        closeButton.tintColor = .white
        closeButton.backgroundColor = UIColor.white.withAlphaComponent(0.16)
        closeButton.layer.cornerRadius = 19
        closeButton.layer.cornerCurve = .continuous
        closeButton.accessibilityLabel = String(localized: "关闭图片预览")
        closeButton.addTarget(self, action: #selector(close), for: .touchUpInside)
        closeButton.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(closeButton)

        countLabel.font = .appPreferredFont(forTextStyle: .subheadline).withWeight(.semibold)
        countLabel.textColor = .white
        countLabel.textAlignment = .center
        countLabel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(countLabel)

        NSLayoutConstraint.activate([
            pageController.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            pageController.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            pageController.view.topAnchor.constraint(equalTo: view.topAnchor),
            pageController.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),

            closeButton.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 16),
            closeButton.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 10),
            closeButton.widthAnchor.constraint(equalToConstant: 38),
            closeButton.heightAnchor.constraint(equalToConstant: 38),

            countLabel.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            countLabel.centerYAnchor.constraint(equalTo: closeButton.centerYAnchor)
        ])

        let initial = page(at: initialIndex)
        pageController.setViewControllers([initial], direction: .forward, animated: false)
        updateCount(initialIndex)
    }

    private func page(at index: Int) -> ZoomingImageViewController {
        ZoomingImageViewController(item: items[index], index: index)
    }

    private func updateCount(_ index: Int) {
        countLabel.text = items.count > 1 ? "\(index + 1) / \(items.count)" : "1 / 1"
    }

    @objc private func close() {
        dismiss(animated: true)
    }

    func pageViewController(
        _ pageViewController: UIPageViewController,
        viewControllerBefore viewController: UIViewController
    ) -> UIViewController? {
        guard let page = viewController as? ZoomingImageViewController,
              page.index > 0 else { return nil }
        return self.page(at: page.index - 1)
    }

    func pageViewController(
        _ pageViewController: UIPageViewController,
        viewControllerAfter viewController: UIViewController
    ) -> UIViewController? {
        guard let page = viewController as? ZoomingImageViewController,
              page.index + 1 < items.count else { return nil }
        return self.page(at: page.index + 1)
    }

    func pageViewController(
        _ pageViewController: UIPageViewController,
        didFinishAnimating finished: Bool,
        previousViewControllers: [UIViewController],
        transitionCompleted completed: Bool
    ) {
        guard completed,
              let page = pageViewController.viewControllers?.first as? ZoomingImageViewController else { return }
        updateCount(page.index)
    }
}

private final class ZoomingImageViewController: UIViewController, UIScrollViewDelegate {
    let index: Int
    private let item: ConversationImagePreviewItem
    private let scrollView = UIScrollView()
    private let imageView = UIImageView()

    init(item: ConversationImagePreviewItem, index: Int) {
        self.item = item
        self.index = index
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { nil }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black

        scrollView.delegate = self
        scrollView.minimumZoomScale = 1
        scrollView.maximumZoomScale = 5
        scrollView.bouncesZoom = true
        scrollView.showsHorizontalScrollIndicator = false
        scrollView.showsVerticalScrollIndicator = false
        scrollView.contentInsetAdjustmentBehavior = .never
        view.addSubview(scrollView)

        imageView.image = item.image
        imageView.contentMode = .scaleAspectFit
        imageView.backgroundColor = .black
        imageView.accessibilityLabel = item.name ?? String(localized: "图片")
        scrollView.addSubview(imageView)

        let doubleTap = UITapGestureRecognizer(target: self, action: #selector(handleDoubleTap(_:)))
        doubleTap.numberOfTapsRequired = 2
        scrollView.addGestureRecognizer(doubleTap)

        if item.image == nil {
            let placeholder = UIImageView(image: UIImage(systemName: "photo"))
            placeholder.tintColor = UIColor.white.withAlphaComponent(0.42)
            placeholder.contentMode = .scaleAspectFit
            placeholder.translatesAutoresizingMaskIntoConstraints = false
            view.addSubview(placeholder)
            NSLayoutConstraint.activate([
                placeholder.centerXAnchor.constraint(equalTo: view.centerXAnchor),
                placeholder.centerYAnchor.constraint(equalTo: view.centerYAnchor),
                placeholder.widthAnchor.constraint(equalToConstant: 54),
                placeholder.heightAnchor.constraint(equalToConstant: 54)
            ])
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        scrollView.frame = view.bounds
        guard scrollView.zoomScale == scrollView.minimumZoomScale else {
            centerImage()
            return
        }
        imageView.frame = scrollView.bounds
        scrollView.contentSize = imageView.bounds.size
    }

    func viewForZooming(in scrollView: UIScrollView) -> UIView? {
        imageView
    }

    func scrollViewDidZoom(_ scrollView: UIScrollView) {
        centerImage()
    }

    private func centerImage() {
        let boundsSize = scrollView.bounds.size
        var frame = imageView.frame
        frame.origin.x = frame.size.width < boundsSize.width
            ? (boundsSize.width - frame.size.width) / 2
            : 0
        frame.origin.y = frame.size.height < boundsSize.height
            ? (boundsSize.height - frame.size.height) / 2
            : 0
        imageView.frame = frame
    }

    @objc private func handleDoubleTap(_ gesture: UITapGestureRecognizer) {
        if scrollView.zoomScale > scrollView.minimumZoomScale {
            scrollView.setZoomScale(scrollView.minimumZoomScale, animated: true)
            return
        }
        let targetScale = min(2.5, scrollView.maximumZoomScale)
        let point = gesture.location(in: imageView)
        let width = scrollView.bounds.width / targetScale
        let height = scrollView.bounds.height / targetScale
        scrollView.zoom(
            to: CGRect(
                x: point.x - width / 2,
                y: point.y - height / 2,
                width: width,
                height: height
            ),
            animated: true
        )
    }
}

/// The active assistant response is the only row that changes for text
/// deltas. TextKit appends only the suffix to `NSTextStorage`, preserving all
/// previously laid-out content. When the response finishes, the row switches
/// back to the project's existing MarkdownUI renderer.
final class StreamingAssistantCell: StableSelfSizingCollectionViewCell {
    static let reuseIdentifier = "StreamingAssistantCell"

    private let textView: UITextView = {
        let view = UITextView()
        view.backgroundColor = .clear
        view.isEditable = false
        view.isSelectable = false
        view.isScrollEnabled = false
        view.textContainerInset = .zero
        view.textContainer.lineFragmentPadding = 0
        view.font = .appPreferredFont(forTextStyle: .body)
        view.textColor = .label
        view.adjustsFontForContentSizeCategory = false
        view.translatesAutoresizingMaskIntoConstraints = false
        view.setContentCompressionResistancePriority(.required, for: .vertical)
        return view
    }()

    private var renderedText = ""
    var renderedCharacterCount: Int { renderedText.count }

    static func estimatedHeight(
        for payload: ConversationViewportEntry.StreamingAssistant,
        width: CGFloat
    ) -> CGFloat {
        let font = UIFont.appPreferredFont(forTextStyle: .body)
        let availableWidth = max(1, width - 4)
        let textHeight: CGFloat
        if payload.text.isEmpty {
            textHeight = 0
        } else {
            textHeight = ceil((payload.text as NSString).boundingRect(
                with: CGSize(width: availableWidth, height: .greatestFiniteMagnitude),
                options: [.usesLineFragmentOrigin, .usesFontLeading],
                attributes: [.font: font],
                context: nil
            ).height)
        }
        return ceil(15 + textHeight + 15 + 2)
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        contentView.backgroundColor = .clear

        contentView.addSubview(textView)

        NSLayoutConstraint.activate([
            textView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 2),
            textView.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -2),
            textView.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 15),
            textView.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -15)
        ])
    }

    required init?(coder: NSCoder) { nil }

    override func prepareForReuse() {
        super.prepareForReuse()
        renderedText = ""
        textView.textStorage.setAttributedString(NSAttributedString())
    }

    @discardableResult
    func apply(_ payload: ConversationViewportEntry.StreamingAssistant) -> Bool {
        let font = UIFont.appPreferredFont(forTextStyle: .body)
        let fontChanged = textView.font?.isEqual(font) != true
        guard payload.text != renderedText || fontChanged else { return false }
        textView.font = font

        if !fontChanged && payload.text.hasPrefix(renderedText) {
            let suffix = String(payload.text.dropFirst(renderedText.count))
            textView.textStorage.append(attributed(suffix))
        } else {
            textView.textStorage.setAttributedString(attributed(payload.text))
        }
        renderedText = payload.text
        textView.setNeedsLayout()
        contentView.setNeedsLayout()
        setNeedsLayout()
        return true
    }

    private func attributed(_ text: String) -> NSAttributedString {
        NSAttributedString(
            string: text,
            attributes: [
                .font: UIFont.appPreferredFont(forTextStyle: .body),
                .foregroundColor: UIColor.label
            ]
        )
    }
}

private extension UIFont {
    func withWeight(_ weight: UIFont.Weight) -> UIFont {
        let descriptor = fontDescriptor.addingAttributes([
            .traits: [UIFontDescriptor.TraitKey.weight: weight]
        ])
        return UIFont(descriptor: descriptor, size: pointSize)
    }
}

#if DEBUG
/// 独立于 ROW/schema3 的显式 opt-in 诊断；只记录匿名计数，不触发布局。
@MainActor
final class DebugHistoryCommitDiagnostics {
    enum Stage: String {
        case begin, diffableCallReturn = "diffable_call_return", completionEnter = "completion_enter"
        case completionFlush = "completion_flush", anchorLayout = "anchor_layout", anchorOffset = "anchor_offset"
        case completionEnd = "completion_end", liveHeightFlush = "live_height_flush", observationEnd = "observation_end"
    }
    final class Record {
        let sequence: Int
        let ordinal: Int?
        let startedAt: CFTimeInterval
        private let clock: () -> CFTimeInterval
        private let sink: (String) -> Void
        private let rowCount: Int
        private let insertedRows: Int
        private var completedAt: CFTimeInterval?
        private var created: [ObjectIdentifier: Int] = [:]
        private var reconfigured: [ObjectIdentifier: Int] = [:]
        private var visibleCreated = 0
        private var visibleReconfigured = 0
        private(set) var correctionCount = 0
        private var missingPreviousHeightCount = 0
        private var maximumDelta: CGFloat = 0
        private var emitted = 0
        private(set) var droppedLogs = 0

        init(sequence: Int, ordinal: Int?, rowCount: Int, insertedRows: Int,
             clock: @escaping () -> CFTimeInterval, sink: @escaping (String) -> Void) {
            self.sequence = sequence; self.ordinal = ordinal; self.rowCount = rowCount; self.insertedRows = insertedRows
            self.clock = clock; self.sink = sink; startedAt = clock()
            emit(.begin, since: startedAt)
        }
        func startIfCollecting() -> CFTimeInterval? {
            let now = clock()
            guard completedAt.map({ now - $0 <= 1 }) ?? true else { return nil }
            return now
        }
        func observeHost(_ cell: AnyObject, created isCreated: Bool) {
            guard startIfCollecting() != nil else { return }
            if isCreated { created[ObjectIdentifier(cell), default: 0] += 1 }
            else { reconfigured[ObjectIdentifier(cell), default: 0] += 1 }
        }
        func observeCorrection(previous: CGFloat?, height: CGFloat) {
            guard startIfCollecting() != nil else { return }
            correctionCount += 1
            if let previous { maximumDelta = max(maximumDelta, abs(previous - height)) }
            else { missingPreviousHeightCount += 1 }
        }
        func emit(_ stage: Stage, since: CFTimeInterval?) {
            guard let since else { return }
            // 每个 commit 最多 12 条诊断；不占用 schema3 生命周期预算。
            guard emitted < 12 else {
                droppedLogs += 1
                if droppedLogs == 1 { sink("history-commit-diagnostic stage=log_limit sequence=\(sequence) ordinal=\(ordinal ?? -1)") }
                return
            }
            emitted += 1
            let now = clock()
            sink(String(format: "history-commit-diagnostic stage=%@ sequence=%ld ordinal=%ld mono=%.6f duration_ms=%.3f rows=%ld inserted=%ld host_create=%ld host_reconfigure=%ld visible_host_create=%ld visible_host_reconfigure=%ld height_changes=%ld missing_previous_height=%ld max_delta_pt=%.3f",
                stage.rawValue, sequence, ordinal ?? -1, now, (now - since) * 1000, rowCount, insertedRows,
                created.values.reduce(0, +), reconfigured.values.reduce(0, +), visibleCreated, visibleReconfigured, correctionCount,
                missingPreviousHeightCount, Double(maximumDelta)))
        }
        func complete(visibleCells: [UICollectionViewCell], completionStartedAt: CFTimeInterval?) {
            let visible = Set(visibleCells.map(ObjectIdentifier.init))
            visibleCreated = visible.reduce(0) { $0 + created[$1, default: 0] }
            visibleReconfigured = visible.reduce(0) { $0 + reconfigured[$1, default: 0] }
            completedAt = clock()
            emit(.completionEnd, since: completionStartedAt)
            // 仅显式诊断时保留一秒匿名计数窗口，不读 cell/不请求 layout。
            DispatchQueue.main.asyncAfter(deadline: .now() + 1) { [self] in
                emit(.observationEnd, since: completedAt)
            }
        }
    }
    private let enabled: Bool
    private let clock: () -> CFTimeInterval
    private let sink: (String) -> Void
    private var count = 0
    private var didReportRecordLimit = false
    private let recordLimit: Int
    init(environment: [String: String], recordLimit: Int = 16,
         clock: @escaping () -> CFTimeInterval = CACurrentMediaTime,
         sink: @escaping (String) -> Void = { appLog("%@", $0) }) {
        enabled = environment["DSH_HISTORY_COMMIT_DIAGNOSTICS"] == "1"
        self.recordLimit = max(0, recordLimit); self.clock = clock; self.sink = sink
    }
    func begin(ordinal: Int?, rowCount: Int, insertedRows: Int) -> Record? {
        // 默认关：不读取 clock，不创建 Record，不计算 cell identity/hash。
        guard enabled else { return nil }
        guard count < recordLimit else {
            if !didReportRecordLimit {
                didReportRecordLimit = true
                sink("history-commit-diagnostic stage=record_limit limit=\(recordLimit)")
            }
            return nil
        }
        count += 1
        return Record(sequence: count, ordinal: ordinal, rowCount: rowCount, insertedRows: insertedRows, clock: clock, sink: sink)
    }
}
#endif

#if DEBUG
/// 有界、一次性、仅显式 DEBUG 实验。绝不按业务 ID 猜测 AnyView 等价。
enum DebugConversationWarmHostExperiment {
    static let enabled = ProcessInfo.processInfo.environment["DSH_HISTORY_WARM_HOST_TRANSFER"] == "1"
}
final class DebugConversationWarmHostToken {}
enum DebugWarmHostInstallFailure: String {
    case stateMismatch, tokenMismatch, ownerMismatch, parentContextMismatch, fontMismatch, postMoveTraitsMismatch
    func publicTraitDifferences(actual: UITraitCollection, expected: UITraitCollection) -> [String] {
        // 旧系统没有公开的 trait 差异枚举接口，只记录匿名差异。
        guard #available(iOS 17.0, *) else { return actual == expected ? [] : ["other"] }
        let allowed: [UITrait] = [UITraitUserInterfaceStyle.self, UITraitPreferredContentSizeCategory.self,
            UITraitHorizontalSizeClass.self, UITraitVerticalSizeClass.self, UITraitDisplayScale.self,
            UITraitLayoutDirection.self, UITraitAccessibilityContrast.self, UITraitLegibilityWeight.self]
        let names = Set(allowed.map { String(describing: $0) })
        // 未知/private trait 仅计 other，不输出任意类型名或内容。
        return Array(Set(actual.changedTraits(from: expected).map { trait in
            let name = String(describing: trait)
            return names.contains(name) ? name : "other"
        })).sorted().prefix(9).map { $0 }
    }
}
struct DebugConversationWarmHostContext: Equatable {
    let owner: ObjectIdentifier
    let sessionID: String?
    let cacheScope: String
    let sessionGeneration: Int
    let width: CGFloat
    let fontSize: CGFloat
    let ownerTraits: UITraitCollection
    let collection: ObjectIdentifier
    let window: ObjectIdentifier?
    let locale: String
}
@MainActor
final class DebugConversationWarmHeightRelay {
    var sink: ((CGFloat) -> Void)?
    func report(_ height: CGFloat) { sink?(height) }
    func invalidate() { sink = nil }
}
@MainActor
final class DebugPreparedConversationHost {
    let token: DebugConversationWarmHostToken
    let diagnosticID: String
    let diagnosticRevision: Int
    let context: DebugConversationWarmHostContext
    let host: UIHostingController<AnyView>
    let relay: DebugConversationWarmHeightRelay
    private let carrier: HostedConversationCell
    private weak var owner: UIViewController?
    private weak var collection: UICollectionView?
    private(set) var renderTraits: UITraitCollection?
    private var measuredHeight: CGFloat?
    private(set) var consumed = false
    private(set) var installed = false
    init(entry: ConversationViewportEntry, token: DebugConversationWarmHostToken,
         context: DebugConversationWarmHostContext, parent: UIViewController, collection: UICollectionView) {
        self.token = token; self.context = context; owner = parent; self.collection = collection
        diagnosticID = entry.id; diagnosticRevision = entry.revision
        let relay = DebugConversationWarmHeightRelay()
        self.relay = relay
        let carrier = HostedConversationCell(frame: CGRect(x: 0, y: 0, width: context.width, height: 1))
        self.carrier = carrier
        carrier.contentView.frame = carrier.bounds
        carrier.isHidden = true
        carrier.accessibilityElementsHidden = true
        carrier.isUserInteractionEnabled = false
        host = UIHostingController(rootView: AnyView(TopPinnedConversationContent(id: entry.id,
            revision: entry.revision, content: entry.content,
            onHeightChange: { [weak relay] in relay?.report($0) })))
        if #available(iOS 16.4, *) { host.safeAreaRegions = [] }
        host.view.backgroundColor = .clear
        host.view.clipsToBounds = true
        host.view.frame = carrier.contentView.bounds
        // 使用同一 collection 下的同类 cell 环境；ownerTraits 不冒充 renderTraits。
        parent.addChild(host)
        collection.addSubview(carrier)
        carrier.contentView.addSubview(host.view)
        host.didMove(toParent: parent)
        if #available(iOS 17.0, *) { host.updateTraitsIfNeeded() }
    }
    func measureHeight() -> CGFloat {
        if let measuredHeight { return measuredHeight }
        let height = host.sizeThatFits(in: CGSize(width: context.width, height: CGFloat.greatestFiniteMagnitude)).height
        if #available(iOS 17.0, *) { host.updateTraitsIfNeeded() }
        measuredHeight = height
        renderTraits = host.traitCollection
        return height
    }
    func hasValidPreparation() -> Bool {
        guard !consumed, !installed, let owner, let collection, let renderTraits else { return false }
        return ObjectIdentifier(owner) == context.owner && host.parent === owner
            && owner.traitCollection == context.ownerTraits
            && ObjectIdentifier(collection) == context.collection
            && collection.window.map(ObjectIdentifier.init) == context.window
            && carrier.superview === collection && carrier.window.map(ObjectIdentifier.init) == context.window
            && carrier.isHidden && carrier.accessibilityElementsHidden
            && host.view.superview === carrier.contentView && host.traitCollection == renderTraits
    }
    func consume() -> Bool {
        guard hasValidPreparation() else { return false }
        consumed = true
        host.view.removeFromSuperview()
        carrier.removeFromSuperview()
        return true
    }
    func markInstalled() { installed = true }
    func discardPreparation() {
        guard !installed else { return }
        relay.invalidate()
        host.willMove(toParent: nil)
        host.view.removeFromSuperview()
        host.removeFromParent()
        carrier.removeFromSuperview()
    }
    func invalidate() { discardPreparation() }
}
@MainActor
final class DebugConversationWarmHostPool {
    private var items: [DebugPreparedConversationHost] = []
    private var context: DebugConversationWarmHostContext?
    private(set) var hits = 0, poolTakeHits = 0, transferFallbacks = 0
    private var preflightFailures = 0, postMoveFailures = 0, targetUnattached = 0, sameRevisionTokenMismatch = 0
    private var traitDifferences: [String: Int] = [:]
    private(set) var misses = 0, evictions = 0, peak = 0, environmentMismatches = 0
    var count: Int { items.count }
    private var reports = 0
    private var didReportFinal = false
    private let sink: (String) -> Void
    private let initialRSS: UInt64
    init(sink: @escaping (String) -> Void = { appLog("%@", $0) }) {
        self.sink = sink
        initialRSS = Self.residentBytes()
    }
    func updateContext(_ next: DebugConversationWarmHostContext) {
        if let context, context != next { clear() }
        context = next
    }
    @discardableResult
    func store(_ prepared: DebugPreparedConversationHost) -> Bool {
        // 已移交的 host 不再归 pool，拒绝重入但不清理 consumer 的 view。
        guard !prepared.consumed else { return false }
        updateContext(prepared.context)
        guard prepared.hasValidPreparation() else {
            environmentMismatches += 1
            prepared.invalidate()
            return false
        }
        if let index = items.firstIndex(where: { $0.token === prepared.token }) {
            items.remove(at: index).invalidate()
        }
        items.append(prepared)
        while items.count > 4 { items.removeFirst().invalidate(); evictions += 1 }
        peak = max(peak, items.count)
        return true
    }
    func take(token: DebugConversationWarmHostToken?, diagnosticID: String? = nil, diagnosticRevision: Int? = nil,
              context next: DebugConversationWarmHostContext) -> DebugPreparedConversationHost? {
        updateContext(next)
        guard let token, let index = items.firstIndex(where: { $0.token === token }) else {
            misses += 1
            if let diagnosticID, let diagnosticRevision,
               items.contains(where: { $0.diagnosticID == diagnosticID && $0.diagnosticRevision == diagnosticRevision && $0.token !== token }) {
                sameRevisionTokenMismatch += 1
            }
            return nil
        }
        let prepared = items.remove(at: index)
        guard prepared.context == next, prepared.hasValidPreparation(), prepared.consume() else {
            prepared.invalidate(); misses += 1; environmentMismatches += 1
            return nil
        }
        poolTakeHits += 1
        return prepared
    }
    func recordInstallation(succeeded: Bool, failure: DebugWarmHostInstallFailure? = nil,
                            traitChanges: [String] = [], targetWasAttached: Bool = true) {
        if !targetWasAttached { targetUnattached += 1 }
        if succeeded { hits += 1 } else {
            transferFallbacks += 1
            switch failure {
            case .postMoveTraitsMismatch: postMoveFailures += 1
            default: preflightFailures += 1
            }
            for name in traitChanges { traitDifferences[name, default: 0] += 1 }
        }
    }
    func clear() {
        for item in items { item.invalidate() }
        items.removeAll(); context = nil
    }
    func report(final: Bool = false) {
        if final {
            guard !didReportFinal else { return }
            didReportFinal = true
        } else {
            guard reports < 24 else { return }
            reports += 1
        }
        let differences = traitDifferences.keys.sorted().map { "\($0):\(traitDifferences[$0, default: 0])" }.joined(separator: ",")
        sink("history-warm-host-experiment stage=\(final ? "final" : "commit") preflight_failure=\(preflightFailures) post_move_failure=\(postMoveFailures) target_unattached=\(targetUnattached) same_revision_token_mismatch=\(sameRevisionTokenMismatch) trait_changes=\(differences.isEmpty ? "none" : differences) warm_host_hit=\(hits) pool_take_hit=\(poolTakeHits) transfer_fallback=\(transferFallbacks) warm_host_miss=\(misses) count=\(count) peak=\(peak) evictions=\(evictions) environment_mismatch=\(environmentMismatches) initial_rss_bytes=\(initialRSS) rss_bytes=\(Self.residentBytes())")
    }
    private static func residentBytes() -> UInt64 {
        var info = mach_task_basic_info()
        var count = mach_msg_type_number_t(MemoryLayout<mach_task_basic_info>.size / MemoryLayout<integer_t>.size)
        let result = withUnsafeMutablePointer(to: &info) { pointer in
            pointer.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(MACH_TASK_BASIC_INFO), $0, &count)
            }
        }
        return result == KERN_SUCCESS ? UInt64(info.resident_size) : 0
    }
}
#endif

/// 只决定何时及哪段展示数据可进入 UIKit；不存领域数据，不做消息合并。
struct HistoryViewportPresentationSchedule {
    struct Row: Equatable {
        let id: String
        let revision: Int
        let kind: Int
        var supplemental = false
    }
    enum Route: String { case prepend, replacement, inPlace = "in_place", initial }
    struct Plan { let indices: [Int]; let remaining: Int; let route: Route; let changedExisting: Int }
    private(set) var idleNotBefore: Double = 0
    mutating func noteInteraction(at time: Double) { idleNotBefore = time + 0.12 }
    func permitsWork(at time: Double) -> Bool { time >= idleNotBefore }
    static func plan(current: [Row], target: [Row], maximumInsertedEntries: Int) -> Plan {
        let existing = Dictionary(current.map { ($0.id, $0) }, uniquingKeysWith: { _, newest in newest })
        let changed = target.filter { row in existing[row.id].map { $0.revision != row.revision || $0.kind != row.kind } ?? false }.count
        func full(_ route: Route) -> Plan {
            Plan(indices: Array(target.indices), remaining: 0, route: route, changedExisting: changed)
        }
        guard !current.isEmpty else { return full(.initial) }
        let oldIDs = current.map(\.id), newIDs = target.map(\.id)
        if oldIDs == newIDs { return full(.inPlace) }
        let oldHeaderCount = current.prefix { $0.supplemental }.count
        let newHeaderCount = target.prefix { $0.supplemental }.count
        let oldBodyIDs = Array(oldIDs.dropFirst(oldHeaderCount))
        let newBodyIDs = Array(newIDs.dropFirst(newHeaderCount))
        let newHeaderInsertions = target.prefix(newHeaderCount).filter { existing[$0.id] == nil }.count
        let limit = max(1, maximumInsertedEntries)
        guard !oldBodyIDs.isEmpty, newBodyIDs.count >= oldBodyIDs.count,
              Array(newBodyIDs.suffix(oldBodyIDs.count)) == oldBodyIDs,
              newHeaderInsertions <= limit else { return full(.replacement) }
        let insertedBody = newBodyIDs.count - oldBodyIDs.count
        let remaining = max(0, insertedBody - (limit - newHeaderInsertions))
        let indices = Array(0..<newHeaderCount) + Array((newHeaderCount + remaining)..<target.count)
        return Plan(indices: indices, remaining: remaining, route: .prepend, changedExisting: changed)
    }
}
