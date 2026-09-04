import SwiftUI
import UIKit
import ChatLayout

struct ConversationViewportEntry: Identifiable {
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
        let showsCopyButton: Bool
    }

    let id: String
    let revision: Int
    let content: AnyView
    let streamingAssistant: StreamingAssistant?
    let userMessage: UserMessage?
    let allowsHeightCaching: Bool
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
        clipsContentToBounds: Bool = false
    ) {
        self.id = id
        self.revision = revision
        self.content = content
        self.allowsHeightCaching = allowsHeightCaching
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
        clipsContentToBounds = false
    }

    init(id: String, revision: Int, userMessage: UserMessage) {
        self.id = id
        self.revision = revision
        content = AnyView(EmptyView())
        streamingAssistant = nil
        self.userMessage = userMessage
        allowsHeightCaching = true
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
    let proxy: ConversationViewportProxy
    let sessionID: String?
    let timeline: ConversationTimeline
    let supplementalEntries: [ConversationViewportEntry]
    let makeEntries: ([ConversationItem]) -> [ConversationViewportEntry]
    let bottomInset: CGFloat
    let scrollToBottomToken: Int
    let onContentAvailabilityChanged: (String?, Bool) -> Void
    let onPinnedToBottomChanged: (Bool) -> Void
    let onBottomAlignmentCompleted: () -> Void
    let onApproachingTop: () -> Void

    func makeUIViewController(context: Context) -> ConversationViewportController {
        let controller = ConversationViewportController(
            onContentAvailabilityChanged: onContentAvailabilityChanged,
            onPinnedToBottomChanged: onPinnedToBottomChanged,
            onBottomAlignmentCompleted: onBottomAlignmentCompleted,
            onApproachingTop: onApproachingTop
        )
        proxy.controller = controller
        return controller
    }

    func updateUIViewController(_ controller: ConversationViewportController, context: Context) {
        controller.onContentAvailabilityChanged = onContentAvailabilityChanged
        controller.onPinnedToBottomChanged = onPinnedToBottomChanged
        controller.onBottomAlignmentCompleted = onBottomAlignmentCompleted
        controller.onApproachingTop = onApproachingTop
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
    var onBottomAlignmentCompleted: () -> Void
    var onApproachingTop: () -> Void

    private lazy var chatLayout: CollectionViewChatLayout = {
        let layout = CollectionViewChatLayout()
        layout.delegate = self
        layout.settings.estimatedItemSize = CGSize(width: 390, height: 80)
        layout.settings.interItemSpacing = 0
        layout.keepContentOffsetAtBottomOnBatchUpdates = false
        return layout
    }()
    private lazy var collectionView = UICollectionView(frame: .zero, collectionViewLayout: chatLayout)
    private var dataSource: UICollectionViewDiffableDataSource<Int, String>!
    private var entriesByID: [String: ConversationViewportEntry] = [:]
    private var previousRevisions: [String: Int] = [:]
    private var sessionID: String?
    private var hasPlacedInitialPosition = false
    private var hasAppliedSnapshot = false
    private var isPinnedToBottom = true
    private var isProgrammaticScroll = false
    private var isApplyingSnapshot = false
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
        hasConversationContent: Bool
    )?
    private var cellHeightCache: [CellMeasurementKey: CGFloat] = [:]
    private var pendingLiveHeightCorrections: [CellMeasurementKey: LiveHeightCorrection] = [:]
    private var isLiveHeightCorrectionScheduled = false
    private var pendingDisclosureAnchor: DisclosureAnchor?
    private var disclosureAnchorGeneration = 0
    private var isStreamingRenderingPausedForUserScroll = false
    private var deferredTimelineSnapshot: ConversationTimeline.Snapshot?
    private var isStreamingRenderResumePending = false

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
        self.supplementalEntries = supplementalEntries
        self.makeEntries = makeEntries
        self.bottomInset = bottomInset

        if self.timeline !== timeline {
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

    private func receive(_ snapshot: ConversationTimeline.Snapshot) {
        guard !isStreamingRenderingPausedForUserScroll else {
            deferredTimelineSnapshot = snapshot
            gatewayStreamingTrace(
                "viewport-buffer",
                "session=\(sessionID ?? "-") revision=\(snapshot.revision)"
            )
            return
        }
        render(snapshot)
    }

    private func render(_ snapshot: ConversationTimeline.Snapshot) {
        let hasContent = !snapshot.items.isEmpty
        guard let makeEntries else { return }
        let entries = supplementalEntries + makeEntries(snapshot.items)
        for entry in entries {
            if let streaming = entry.streamingAssistant {
                gatewayStreamingTrace(
                    "viewport",
                    "session=\(sessionID ?? "-") revision=\(snapshot.revision) " +
                    "item=\(entry.id) chars=\(streaming.text.count)"
                )
            }
        }
        apply(
            entries: entries,
            revision: snapshot.revision,
            bottomInset: bottomInset,
            hasConversationContent: hasContent
        )
    }

    func setSessionID(_ newSessionID: String?) {
        guard newSessionID != sessionID else { return }
        sessionGeneration &+= 1
        sessionID = newSessionID
        // 在新 timeline snapshot 原子提交前隐藏旧 cell，
        // 禁止上一个 session 的内容在切换期间闪现一帧。
        collectionView.isHidden = true
        lastReportedContentAvailability = nil
        reportContentAvailability(false)
        hasPlacedInitialPosition = false
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
        cellHeightCache.removeAll(keepingCapacity: true)
        pendingLiveHeightCorrections.removeAll(keepingCapacity: true)
        isLiveHeightCorrectionScheduled = false
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear
        collectionView.alwaysBounceVertical = true
        collectionView.isScrollEnabled = true
        collectionView.keyboardDismissMode = .interactive
        collectionView.backgroundColor = .clear
        collectionView.delegate = self
        collectionView.contentInsetAdjustmentBehavior = .never
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
                cell.apply(userMessage, containerWidth: collectionView.bounds.width)
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
            cell.apply(entry, parent: self) { [weak self] width, height in
                self?.receiveLiveHostedHeight(
                    id: entry.id,
                    revision: entry.revision,
                    width: width,
                    height: height
                )
            }
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
        guard collectionView.isDecelerating || collectionView.isDragging else { return }
        isProgrammaticScroll = true
        collectionView.setContentOffset(collectionView.contentOffset, animated: false)
        collectionView.layer.removeAllAnimations()
        isProgrammaticScroll = false
        setPinned(isAtBottom(collectionView))
        resumeStreamingRenderingAfterUserScroll()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        placeInitialPositionIfNeeded()
        alignBottomIfNeeded()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        if !isApplyingSnapshot,
           collectionView.bounds.width > 0,
           let pending = pendingApply {
            pendingApply = nil
            apply(
                entries: pending.entries,
                revision: pending.revision,
                bottomInset: pending.bottomInset,
                hasConversationContent: pending.hasConversationContent
            )
        }
        placeInitialPositionIfNeeded()
        alignBottomIfNeeded()
    }

    func apply(
        entries: [ConversationViewportEntry],
        revision: Int,
        bottomInset: CGFloat,
        hasConversationContent: Bool
    ) {
        collectionView.contentInset.bottom = bottomInset
        collectionView.verticalScrollIndicatorInsets.bottom = bottomInset
        guard collectionView.bounds.width > 0 else {
            // History can arrive during UIViewControllerRepresentable's first
            // update, before Auto Layout assigns the viewport its real width.
            // Applying now would seed every history row with the 80pt estimate.
            pendingApply = (entries, revision, bottomInset, hasConversationContent)
            return
        }
        let ids = entries.map(\.id)

        // Never replace the cell-provider model while an older diffable
        // snapshot is still animating. Otherwise UIKit can ask for a cell from
        // snapshot A and receive the entry from pending snapshot B, which is
        // visible as duplicated/overlapping rows during rapid disclosures.
        guard !isApplyingSnapshot else {
            pendingApply = (entries, revision, bottomInset, hasConversationContent)
            return
        }

        let prependAnchor = capturePrependAnchor(for: ids)
        let oldEntriesByID = entriesByID
        entriesByID = Dictionary(uniqueKeysWithValues: entries.map { ($0.id, $0) })
        let changed = entries.compactMap { previousRevisions[$0.id] == $0.revision ? nil : $0.id }
        let hasStructureChange = dataSource.snapshot().itemIdentifiers != ids
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
        // Finish every height calculation before entering UIKit's diffable
        // commit. `sizeForItem` and `preferredLayoutAttributesFitting` will
        // therefore return the same exact value throughout the batch.
        let newlyPrewarmedIDs = prewarmHeights(in: entries)
        guard hasStructureChange || !changed.isEmpty else {
            invalidatePrewarmedItems(newlyPrewarmedIDs)
            hasAppliedSnapshot = true
            collectionView.isHidden = false
            reportContentAvailability(hasConversationContent)
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
        let keepTail = isPinnedToBottom && revision != lastAppliedRevision
        let interactionGeneration = userInteractionGeneration
        lastAppliedRevision = revision
        previousRevisions = Dictionary(uniqueKeysWithValues: entries.map { ($0.id, $0.revision) })
        updateVisibleStreamingCells(streamingChanged)
        updateVisibleUserMessageCells(stableUserMessageChanged)

        // Pure token growth never enters diffable reconciliation. The visible
        // streaming Markdown cell already consumed the newest snapshot; its
        // render callback invalidates only that item's estimated height.
        // Completed rows remain untouched.
        if !hasStructureChange, configuredChanged.isEmpty {
            if keepTail,
               userInteractionGeneration == interactionGeneration,
               !collectionView.isTracking,
               !collectionView.isDragging,
               !collectionView.isDecelerating {
                followStreamingTail()
            }
            return
        }
        isApplyingSnapshot = true
        let applyingSessionGeneration = sessionGeneration
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
        dataSource.apply(snapshot, animatingDifferences: disclosureAnchor != nil) { [weak self] in
            guard let self else { return }
            self.isApplyingSnapshot = false
            guard self.sessionGeneration == applyingSessionGeneration else {
                // 旧 session 的 apply 已经完成，但 viewport 仍保持隐藏。
                // 立即提交切换期间收到的最新 session snapshot。
                if let pending = self.pendingApply {
                    self.pendingApply = nil
                    self.apply(
                        entries: pending.entries,
                        revision: pending.revision,
                        bottomInset: pending.bottomInset,
                        hasConversationContent: pending.hasConversationContent
                    )
                }
                return
            }
            self.flushLiveHeightCorrectionsIfNeeded()
            self.hasAppliedSnapshot = true
            self.collectionView.isHidden = false
            // 只有 diffable snapshot 已经真正提交、cell 可以显示以后，才允许
            // SwiftUI 撤下冷加载遮罩。
            self.reportContentAvailability(hasConversationContent)
            if let prependAnchor, !keepTail {
                self.restore(prependAnchor)
            } else if let disclosureAnchor {
                self.stabilizeDisclosureAnchor(disclosureAnchor, remainingPasses: 3)
            }
            self.needsInitialBottomPlacement = self.needsInitialBottomPlacement || wasEmpty
            if self.needsInitialBottomPlacement {
                self.placeInitialPositionIfNeeded()
            } else if keepTail,
                      self.userInteractionGeneration == interactionGeneration,
                      !self.collectionView.isTracking,
                      !self.collectionView.isDragging,
                      !self.collectionView.isDecelerating {
                self.followStreamingTail()
            }
            if let pending = self.pendingApply {
                self.pendingApply = nil
                self.apply(
                    entries: pending.entries,
                    revision: pending.revision,
                    bottomInset: pending.bottomInset,
                    hasConversationContent: pending.hasConversationContent
                )
                self.finishStreamingRenderResumeIfPossible()
            } else {
                self.finishStreamingRenderResumeIfPossible()
            }
        }
    }

    private func reportContentAvailability(_ hasContent: Bool) {
        guard hasContent != lastReportedContentAvailability else { return }
        lastReportedContentAvailability = hasContent
        onContentAvailabilityChanged(sessionID, hasContent)
    }

    private func updateVisibleStreamingCells(_ ids: [String]) {
        var invalidatedIndexPaths: [IndexPath] = []
        for id in ids {
            guard let entry = entriesByID[id],
                  let payload = entry.streamingAssistant,
                  let indexPath = dataSource.indexPath(for: id),
                  let cell = collectionView.cellForItem(at: indexPath) as? StreamingAssistantCell else { continue }
            configureStreamingMeasurement(cell, entry: entry)
            guard cell.apply(payload) else { continue }
            invalidatedIndexPaths.append(indexPath)
        }
        guard !invalidatedIndexPaths.isEmpty else { return }

        let context = ChatLayoutInvalidationContext()
        context.invalidateLayoutMetrics = false
        context.invalidateItems(at: invalidatedIndexPaths)
        chatLayout.invalidateLayout(with: context)
        collectionView.layoutIfNeeded()
    }

    private func updateVisibleUserMessageCells(_ ids: [String]) {
        for id in ids {
            guard let payload = entriesByID[id]?.userMessage,
                  let indexPath = dataSource.indexPath(for: id),
                  let cell = collectionView.cellForItem(at: indexPath) as? UserMessageCell else { continue }
            // Geometry is deliberately unchanged. Updating only UIImageView.image
            // avoids a collection-layout invalidation and preserves the anchor.
            cell.apply(payload, containerWidth: collectionView.bounds.width)
        }
    }

    private static func hasSameUserMessageGeometry(
        _ lhs: ConversationViewportEntry.UserMessage,
        _ rhs: ConversationViewportEntry.UserMessage
    ) -> Bool {
        guard lhs.text == rhs.text,
              lhs.showsCopyButton == rhs.showsCopyButton,
              lhs.images.count == rhs.images.count else { return false }
        return zip(lhs.images, rhs.images).allSatisfy { old, new in
            old.id == new.id
                && old.width == new.width
                && old.height == new.height
                && old.name == new.name
        }
    }

    /// Premeasure every cacheable row before diffable data source starts a
    /// batch update. Measuring only the visible tail was insufficient for
    /// restored history: UICollectionView can provisionally create a row from
    /// either end while calculating its new content offset.
    private func prewarmHeights(in entries: [ConversationViewportEntry]) -> Set<String> {
        let width = chatLayout.layoutFrame.width > 0
            ? chatLayout.layoutFrame.width
            : collectionView.bounds.width
        guard width > 0 else { return [] }

        var measuredIDs = Set<String>()
        for entry in entries where entry.allowsHeightCaching {
            let key = CellMeasurementKey(
                id: entry.id,
                revision: entry.revision,
                width: width
            )
            guard cellHeightCache[key] == nil else { continue }
            let height = Self.measureHeight(for: entry, width: width)
            guard height.isFinite, height > 0 else { continue }
            cellHeightCache[key] = ceil(height)
            measuredIDs.insert(entry.id)
        }
        return measuredIDs
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
            cellHeightCache[key] = correction.height
            if let indexPath = dataSource.indexPath(for: correction.id) {
                indexPaths.append(indexPath)
            }
        }
        guard !indexPaths.isEmpty else { return }

        let context = ChatLayoutInvalidationContext()
        context.invalidateLayoutMetrics = true
        context.invalidateItems(at: indexPaths)
        chatLayout.invalidateLayout(with: context)
        collectionView.layoutIfNeeded()

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
        host.safeAreaRegions = []
        host.view.backgroundColor = .clear
        return host.sizeThatFits(
            in: CGSize(width: width, height: CGFloat.greatestFiniteMagnitude)
        ).height
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
        let width = collectionView.bounds.width
        guard width > 0 else { return }
        let key = CellMeasurementKey(id: entry.id, revision: entry.revision, width: width)
        guard cellHeightCache[key] == nil else { return }
        cellHeightCache[key] = UserMessageCell.estimatedHeight(for: userMessage, width: width)
    }

    /// Capture the stable header before its descendants are inserted or
    /// removed. The header remains its own collection item, so the subsequent
    /// diffable update never needs to infer a position from a resizing host.
    func prepareForDisclosureUpdate(anchorID id: String) {
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
        // A drag is not the same thing as leaving the tail. In particular,
        // dragging upward again while already at the bottom only produces the
        // rubber-band overscroll (`contentOffset.y > maximumOffsetY`). Keep the
        // viewport pinned through that bounce and reveal the jump button only
        // after the user has actually moved away from the tail.
        setPinned(isAtBottom(scrollView))
        if !isProgrammaticScroll,
           (scrollView.isDragging || scrollView.isDecelerating),
           scrollView.contentOffset.y + scrollView.adjustedContentInset.top < 420 {
            onApproachingTop()
        }
    }

    func scrollViewWillBeginDragging(_ scrollView: UIScrollView) {
        // User interaction wins immediately over automatic tail following.
        pauseStreamingRenderingForUserScroll()
        needsBottomAlignment = false
        bottomAlignmentGeneration &+= 1
        userInteractionGeneration &+= 1
    }

    func scrollViewDidEndDragging(_ scrollView: UIScrollView, willDecelerate decelerate: Bool) {
        guard !decelerate else { return }
        updatePinnedState(for: scrollView)
        resumeStreamingRenderingAfterUserScroll()
    }

    func scrollViewDidEndDecelerating(_ scrollView: UIScrollView) {
        updatePinnedState(for: scrollView)
        resumeStreamingRenderingAfterUserScroll()
    }

    private func pauseStreamingRenderingForUserScroll() {
        guard !isStreamingRenderingPausedForUserScroll else { return }
        isStreamingRenderingPausedForUserScroll = true
        isStreamingRenderResumePending = false
        gatewayStreamingTrace("viewport-pause", "session=\(sessionID ?? "-")")
    }

    private func resumeStreamingRenderingAfterUserScroll() {
        guard isStreamingRenderingPausedForUserScroll else { return }
        isStreamingRenderingPausedForUserScroll = false
        isStreamingRenderResumePending = true
        let deferredSnapshot = deferredTimelineSnapshot
        deferredTimelineSnapshot = nil
        if let deferredSnapshot {
            render(deferredSnapshot)
        }
        gatewayStreamingTrace(
            "viewport-resume-request",
            "session=\(sessionID ?? "-") revision=\(deferredSnapshot?.revision ?? -1)"
        )
        finishStreamingRenderResumeIfPossible()
    }

    private func finishStreamingRenderResumeIfPossible() {
        guard isStreamingRenderResumePending,
              !isStreamingRenderingPausedForUserScroll,
              !isApplyingSnapshot,
              pendingApply == nil else { return }
        isStreamingRenderResumePending = false
        gatewayStreamingTrace("viewport-resume", "session=\(sessionID ?? "-")")
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

    private func capturePrependAnchor(for newIDs: [String]) -> VisibleAnchor? {
        let oldIDs = dataSource.snapshot().itemIdentifiers
        guard let oldFirst = oldIDs.first,
              let retainedIndex = newIDs.firstIndex(of: oldFirst),
              retainedIndex > 0,
              let indexPath = collectionView.indexPathsForVisibleItems.min(),
              indexPath.item < oldIDs.count,
              let attributes = collectionView.layoutAttributesForItem(at: indexPath) else { return nil }
        return VisibleAnchor(
            id: oldIDs[indexPath.item],
            offsetFromViewportTop: attributes.frame.minY - collectionView.contentOffset.y
        )
    }

    private func restore(_ anchor: VisibleAnchor) {
        guard let item = dataSource.snapshot().indexOfItem(anchor.id) else { return }
        collectionView.collectionViewLayout.invalidateLayout()
        collectionView.layoutIfNeeded()
        restorePosition(anchor, item: item)
    }

    private func restorePosition(_ anchor: VisibleAnchor, item: Int? = nil) {
        guard let item = item ?? dataSource.snapshot().indexOfItem(anchor.id) else { return }
        let indexPath = IndexPath(item: item, section: 0)
        guard let attributes = collectionView.layoutAttributesForItem(at: indexPath) else { return }
        isProgrammaticScroll = true
        collectionView.setContentOffset(
            CGPoint(x: 0, y: attributes.frame.minY - anchor.offsetFromViewportTop),
            animated: false
        )
        isProgrammaticScroll = false
    }

    private func placeInitialPositionIfNeeded() {
        guard !hasPlacedInitialPosition,
              !dataSource.snapshot().itemIdentifiers.isEmpty,
              view.bounds.height > 0 else { return }
        hasPlacedInitialPosition = true
        needsInitialBottomPlacement = false
        scrollToBottom()
    }

    private func setPinned(_ value: Bool) {
        guard value != isPinnedToBottom else { return }
        isPinnedToBottom = value
        DispatchQueue.main.async { [weak self] in
            self?.onPinnedToBottomChanged(value)
        }
    }

}

private struct LiveHeightCorrection {
    let id: String
    let revision: Int
    let width: CGFloat
    let height: CGFloat
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
private final class HostedConversationCell: StableSelfSizingCollectionViewCell {
    private var hostingController: UIHostingController<AnyView>?
    private var representedIdentity: String?

    func apply(
        _ entry: ConversationViewportEntry,
        parent: UIViewController,
        onHeightChange: @escaping (CGFloat, CGFloat) -> Void
    ) {
        let identity = "\(entry.id)#\(entry.revision)"
        representedIdentity = identity
        let root = AnyView(
            TopPinnedConversationContent(
                id: entry.id,
                revision: entry.revision,
                content: entry.content,
                onHeightChange: { [weak self] height in
                    guard let self, self.representedIdentity == identity else { return }
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
            created.safeAreaRegions = []
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
            countLabel.font = .preferredFont(forTextStyle: .caption1).withWeight(.semibold)
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
                countLabel.frame = CGRect(
                    x: frontFrame.maxX - 51,
                    y: frontFrame.maxY - 32,
                    width: 45,
                    height: 26
                )
                bringSubviewToFront(imageViewsByID[visibleIDs[0]]!)
                bringSubviewToFront(countLabel)
            }
        }
    }

    private static let decodedImageCache = NSCache<NSString, UIImage>()

    private let stack = UIStackView()
    private let imageGrid = UIStackView()
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

    static func estimatedHeight(for payload: ConversationViewportEntry.UserMessage, width: CGFloat) -> CGFloat {
        let horizontalBubbleInset: CGFloat = 34
        let horizontalTextPadding: CGFloat = 28
        let maximumTextWidth = max(1, width - horizontalBubbleInset - horizontalTextPadding)
        let textBounds = (payload.text as NSString).boundingRect(
            with: CGSize(width: maximumTextWidth, height: .greatestFiniteMagnitude),
            options: [.usesLineFragmentOrigin, .usesFontLeading],
            attributes: [.font: UIFont.preferredFont(forTextStyle: .body)],
            context: nil
        )
        let imageHeight = previewSize(
            for: payload.images,
            availableWidth: width - horizontalBubbleInset
        ).height
        var contentHeight: CGFloat = 0
        if imageHeight > 0 { contentHeight += imageHeight }
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

        bubbleView.layer.cornerRadius = 15
        bubbleView.layer.cornerCurve = .continuous
        bubbleView.layer.borderWidth = 0.7
        bubbleView.translatesAutoresizingMaskIntoConstraints = false

        messageLabel.font = .preferredFont(forTextStyle: .body)
        messageLabel.adjustsFontForContentSizeCategory = true
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
        if messageLabel.text != payload.text {
            messageLabel.text = payload.text
        }
        bubbleView.isHidden = payload.text.isEmpty
        copyButton.isHidden = payload.text.isEmpty || !payload.showsCopyButton
        applyImages(payload.images, containerWidth: containerWidth)
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

        countLabel.font = .preferredFont(forTextStyle: .subheadline).withWeight(.semibold)
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

    private let whaleView: UIImageView = {
        let view = UIImageView(image: UIImage(named: "DeepSeekWhale")?.withRenderingMode(.alwaysTemplate))
        view.tintColor = .secondaryLabel
        view.contentMode = .scaleAspectFit
        view.translatesAutoresizingMaskIntoConstraints = false
        return view
    }()

    private let titleLabel: UILabel = {
        let label = UILabel()
        label.font = .preferredFont(forTextStyle: .subheadline).withWeight(.semibold)
        label.textColor = .secondaryLabel
        label.adjustsFontForContentSizeCategory = true
        return label
    }()

    private let textView: UITextView = {
        let view = UITextView()
        view.backgroundColor = .clear
        view.isEditable = false
        view.isSelectable = false
        view.isScrollEnabled = false
        view.textContainerInset = .zero
        view.textContainer.lineFragmentPadding = 0
        view.font = .preferredFont(forTextStyle: .body)
        view.textColor = .label
        view.adjustsFontForContentSizeCategory = true
        view.setContentCompressionResistancePriority(.required, for: .vertical)
        return view
    }()

    private var renderedText = ""
    var renderedCharacterCount: Int { renderedText.count }

    static func estimatedHeight(
        for payload: ConversationViewportEntry.StreamingAssistant,
        width: CGFloat
    ) -> CGFloat {
        let font = UIFont.preferredFont(forTextStyle: .body)
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
        return ceil(15 + 26 + 7 + textHeight + 15)
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        contentView.backgroundColor = .clear

        let header = UIStackView(arrangedSubviews: [whaleView, titleLabel])
        header.axis = .horizontal
        header.alignment = .center
        header.spacing = 9

        let stack = UIStackView(arrangedSubviews: [header, textView])
        stack.axis = .vertical
        stack.alignment = .fill
        stack.spacing = 7
        stack.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(stack)

        NSLayoutConstraint.activate([
            whaleView.widthAnchor.constraint(equalToConstant: 26),
            whaleView.heightAnchor.constraint(equalToConstant: 26),
            stack.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 2),
            stack.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -2),
            stack.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 15),
            stack.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -15)
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
        titleLabel.text = payload.title
        guard payload.text != renderedText else { return false }

        gatewayStreamingTrace(
            "cell-apply",
            "renderer=textkit chars=\(payload.text.count)"
        )

        if payload.text.hasPrefix(renderedText) {
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
                .font: UIFont.preferredFont(forTextStyle: .body),
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
