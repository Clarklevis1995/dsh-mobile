import SwiftUI
import UIKit

struct ConversationViewportEntry: Identifiable {
    struct StreamingAssistant {
        let title: String
        let text: String
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

    init(id: String, revision: Int, content: AnyView, allowsHeightCaching: Bool = true) {
        self.id = id
        self.revision = revision
        self.content = content
        self.allowsHeightCaching = allowsHeightCaching
        streamingAssistant = nil
        userMessage = nil
    }

    init(id: String, revision: Int, streamingAssistant: StreamingAssistant) {
        self.id = id
        self.revision = revision
        content = AnyView(EmptyView())
        self.streamingAssistant = streamingAssistant
        userMessage = nil
        allowsHeightCaching = false
    }

    init(id: String, revision: Int, userMessage: UserMessage) {
        self.id = id
        self.revision = revision
        content = AnyView(EmptyView())
        streamingAssistant = nil
        self.userMessage = userMessage
        allowsHeightCaching = true
    }
}

private struct ConversationImagePreviewItem {
    let id: String
    let image: UIImage?
    let name: String?
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

    func invalidateHeight(for id: String) {
        controller?.invalidateHeight(for: id)
    }
}

final class ConversationViewportController: UIViewController, UICollectionViewDelegate {
    weak var proxyOwner: ConversationViewportProxy?
    var onContentAvailabilityChanged: (String?, Bool) -> Void
    var onPinnedToBottomChanged: (Bool) -> Void
    var onBottomAlignmentCompleted: () -> Void
    var onApproachingTop: () -> Void

    private lazy var collectionView = UICollectionView(frame: .zero, collectionViewLayout: Self.makeLayout())
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
        let hasContent = !snapshot.items.isEmpty
        guard let makeEntries else { return }
        apply(
            entries: supplementalEntries + makeEntries(snapshot.items),
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
        cellHeightCache.removeAll(keepingCapacity: true)
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
        let dismissKeyboardTap = UITapGestureRecognizer(
            target: self,
            action: #selector(dismissKeyboardFromConversation)
        )
        // Dismissing focus must not consume the original tap: links, copy
        // buttons and expandable process rows still receive their action.
        dismissKeyboardTap.cancelsTouchesInView = false
        collectionView.addGestureRecognizer(dismissKeyboardTap)
        collectionView.register(
            StableSelfSizingCollectionViewCell.self,
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
            guard let entry = self?.entriesByID[id] else {
                return collectionView.dequeueReusableCell(withReuseIdentifier: "ConversationCell", for: indexPath)
            }
            if let streamingAssistant = entry.streamingAssistant {
                let cell = collectionView.dequeueReusableCell(
                    withReuseIdentifier: StreamingAssistantCell.reuseIdentifier,
                    for: indexPath
                ) as! StreamingAssistantCell
                cell.apply(streamingAssistant)
                return cell
            }
            if let userMessage = entry.userMessage {
                let cell = collectionView.dequeueReusableCell(
                    withReuseIdentifier: UserMessageCell.reuseIdentifier,
                    for: indexPath
                ) as! UserMessageCell
                self?.cacheUserMessageHeightIfNeeded(userMessage, entry: entry)
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
            ) as? StableSelfSizingCollectionViewCell else {
                return UICollectionViewCell()
            }
            if entry.allowsHeightCaching {
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
            } else {
                cell.disableMeasurementCaching()
            }
            cell.backgroundColor = .clear
            cell.backgroundConfiguration = UIBackgroundConfiguration.clear()
            cell.contentConfiguration = UIHostingConfiguration {
                entry.content
                    .id(entry.id)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }.margins(.all, 0)
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
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        placeInitialPositionIfNeeded()
        alignBottomIfNeeded()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
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
        let ids = entries.map(\.id)
        let prependAnchor = capturePrependAnchor(for: ids)
        let oldEntriesByID = entriesByID
        entriesByID = Dictionary(uniqueKeysWithValues: entries.map { ($0.id, $0) })
        prewarmUserMessageHeights(in: entries)
        let changed = entries.compactMap { previousRevisions[$0.id] == $0.revision ? nil : $0.id }
        let hasStructureChange = dataSource.snapshot().itemIdentifiers != ids
        guard hasStructureChange || !changed.isEmpty else {
            hasAppliedSnapshot = true
            collectionView.isHidden = false
            reportContentAvailability(hasConversationContent)
            return
        }

        // UICollectionView forbids nested diffable applies. Keep exactly one
        // latest pending state: a fast stream can never create a main-queue
        // backlog, and the next apply always contains every packet folded so
        // far.
        guard !isApplyingSnapshot else {
            pendingApply = (entries, revision, bottomInset, hasConversationContent)
            return
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
        // TextKit cell has already appended the suffix; only its own estimated
        // height is invalidated. Completed rows are untouched.
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
            snapshot.reconfigureItems(configuredChanged.filter { snapshot.indexOfItem($0) != nil })
        }
        let wasEmpty = !hasAppliedSnapshot
        dataSource.apply(snapshot, animatingDifferences: false) { [weak self] in
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
            self.hasAppliedSnapshot = true
            self.collectionView.isHidden = false
            // 只有 diffable snapshot 已经真正提交、cell 可以显示以后，才允许
            // SwiftUI 撤下冷加载遮罩。
            self.reportContentAvailability(hasConversationContent)
            if let prependAnchor, !keepTail {
                self.restore(prependAnchor)
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
            guard let payload = entriesByID[id]?.streamingAssistant,
                  let indexPath = dataSource.indexPath(for: id),
                  let cell = collectionView.cellForItem(at: indexPath) as? StreamingAssistantCell,
                  cell.apply(payload) else { continue }
            invalidatedIndexPaths.append(indexPath)
        }
        guard !invalidatedIndexPaths.isEmpty else { return }
        let context = UICollectionViewLayoutInvalidationContext()
        context.invalidateItems(at: invalidatedIndexPaths)
        collectionView.collectionViewLayout.invalidateLayout(with: context)
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

    /// User rows are sparse and immutable. Premeasure both their attachment
    /// grid and text bubble so the collection view never has to correct a
    /// provisional height while an assistant row is streaming below them.
    private func prewarmUserMessageHeights(in entries: [ConversationViewportEntry]) {
        let width = collectionView.bounds.width
        guard width > 0 else { return }
        for entry in entries {
            guard let userMessage = entry.userMessage else { continue }
            cacheUserMessageHeightIfNeeded(userMessage, entry: entry)
        }
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

    /// Disclosure rows are normally immutable and can reuse their measured
    /// height while scrolling. A tap is the only event that changes their
    /// intrinsic height, so evict and remeasure just that item after SwiftUI
    /// commits its expanded state instead of keeping every process row in live
    /// measurement mode.
    func invalidateHeight(for id: String) {
        cellHeightCache = cellHeightCache.filter { $0.key.id != id }
        guard let indexPath = dataSource.indexPath(for: id) else { return }
        DispatchQueue.main.async { [weak self] in
            guard let self,
                  self.dataSource.indexPath(for: id) == indexPath else { return }
            let context = UICollectionViewLayoutInvalidationContext()
            context.invalidateItems(at: [indexPath])
            UIView.performWithoutAnimation {
                self.collectionView.collectionViewLayout.invalidateLayout(with: context)
                self.collectionView.layoutIfNeeded()
            }
        }
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
        needsBottomAlignment = false
        bottomAlignmentGeneration &+= 1
        userInteractionGeneration &+= 1
    }

    func scrollViewDidEndDragging(_ scrollView: UIScrollView, willDecelerate decelerate: Bool) {
        guard !decelerate else { return }
        updatePinnedState(for: scrollView)
    }

    func scrollViewDidEndDecelerating(_ scrollView: UIScrollView) {
        updatePinnedState(for: scrollView)
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

    private static func makeLayout() -> UICollectionViewCompositionalLayout {
        // `UserMessageCell` has a required 75pt minimum vertical chain:
        // outer top + bubble padding + copy gap/button + outer bottom. Giving
        // every item a 44pt provisional frame made UIKit lay that cell out in
        // an impossible height before its cached/self-sized height was read,
        // producing two constraint failures for every historical user row.
        // This remains only an estimate; short hosted rows still self-size
        // down and cached rows immediately replace it with their exact height.
        let itemSize = NSCollectionLayoutSize(
            widthDimension: .fractionalWidth(1),
            heightDimension: .estimated(80)
        )
        let item = NSCollectionLayoutItem(layoutSize: itemSize)
        let group = NSCollectionLayoutGroup.vertical(layoutSize: itemSize, subitems: [item])
        let section = NSCollectionLayoutSection(group: group)
        section.interGroupSpacing = 0
        return UICollectionViewCompositionalLayout(section: section)
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

/// `UIHostingConfiguration` can report two adjacent fractional heights while
/// the collection view is moving (for example 34⅓pt and 34⅔pt). A
/// compositional layout treats each value as a new preferred size and can
/// repeatedly invalidate the same cells until UIKit terminates the app for a
/// recursive layout loop. Keep dynamic heights, but quantize the final result
/// to whole points so repeated measurements are deterministic.
private class StableSelfSizingCollectionViewCell: UICollectionViewCell {
    private var measurementID: String?
    private var cachedHeight: ((CGFloat) -> CGFloat?)?
    private var storeHeight: ((CGFloat, CGFloat) -> Void)?

    func configureMeasurement(
        id: String,
        revision: Int,
        cachedHeight: @escaping (CGFloat) -> CGFloat?,
        storeHeight: @escaping (CGFloat, CGFloat) -> Void
    ) {
        measurementID = "\(id)#\(revision)"
        self.cachedHeight = cachedHeight
        self.storeHeight = storeHeight
    }

    func disableMeasurementCaching() {
        measurementID = nil
        cachedHeight = nil
        storeHeight = nil
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

        let fitted = super.preferredLayoutAttributesFitting(layoutAttributes)
        guard let stable = fitted.copy() as? UICollectionViewLayoutAttributes else {
            return fitted
        }
        stable.size.width = width
        stable.size.height = ceil(fitted.size.height)
        if measurementID != nil {
            storeHeight?(width, stable.size.height)
        }
        return stable
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
/// deltas. TextKit appends the suffix directly to `NSTextStorage`, preserving
/// all previously laid-out cells and avoiding a full SwiftUI/Markdown rebuild
/// for every WebSocket packet.
private final class StreamingAssistantCell: StableSelfSizingCollectionViewCell {
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

        if payload.text.hasPrefix(renderedText) {
            let suffix = String(payload.text.dropFirst(renderedText.count))
            textView.textStorage.append(attributed(suffix))
        } else {
            // Reconnect corrections and out-of-order replacement frames are
            // uncommon, but the final accumulated server state still wins.
            textView.textStorage.setAttributedString(attributed(payload.text))
        }
        renderedText = payload.text
        textView.invalidateIntrinsicContentSize()
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
