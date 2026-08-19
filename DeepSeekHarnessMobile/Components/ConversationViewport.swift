import SwiftUI
import UIKit

struct ConversationViewportEntry: Identifiable {
    struct StreamingAssistant {
        let title: String
        let text: String
    }

    let id: String
    let revision: Int
    let content: AnyView
    let streamingAssistant: StreamingAssistant?

    init(id: String, revision: Int, content: AnyView) {
        self.id = id
        self.revision = revision
        self.content = content
        streamingAssistant = nil
    }

    init(id: String, revision: Int, streamingAssistant: StreamingAssistant) {
        self.id = id
        self.revision = revision
        content = AnyView(EmptyView())
        self.streamingAssistant = streamingAssistant
    }
}

/// UIKit-backed viewport for streaming conversation timelines.
struct ConversationViewport: UIViewControllerRepresentable {
    let sessionID: String?
    let timeline: ConversationTimeline
    let supplementalEntries: [ConversationViewportEntry]
    let makeEntries: ([ConversationItem]) -> [ConversationViewportEntry]
    let bottomInset: CGFloat
    let scrollToBottomToken: Int
    let onPinnedToBottomChanged: (Bool) -> Void
    let onBottomAlignmentCompleted: () -> Void
    let onApproachingTop: () -> Void

    func makeUIViewController(context: Context) -> ConversationViewportController {
        ConversationViewportController(
            onPinnedToBottomChanged: onPinnedToBottomChanged,
            onBottomAlignmentCompleted: onBottomAlignmentCompleted,
            onApproachingTop: onApproachingTop
        )
    }

    func updateUIViewController(_ controller: ConversationViewportController, context: Context) {
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

    final class Coordinator {
        var lastScrollToBottomToken = 0
    }
}

final class ConversationViewportController: UIViewController, UICollectionViewDelegate {
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
    private weak var timeline: ConversationTimeline?
    private var timelineObserverID: UUID?
    private var supplementalEntries: [ConversationViewportEntry] = []
    private var makeEntries: (([ConversationItem]) -> [ConversationViewportEntry])?
    private var bottomInset: CGFloat = 0
    private var pendingApply: (entries: [ConversationViewportEntry], revision: Int, bottomInset: CGFloat)?

    init(
        onPinnedToBottomChanged: @escaping (Bool) -> Void,
        onBottomAlignmentCompleted: @escaping () -> Void,
        onApproachingTop: @escaping () -> Void
    ) {
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
        guard let makeEntries else { return }
        apply(
            entries: supplementalEntries + makeEntries(snapshot.items),
            revision: snapshot.revision,
            bottomInset: bottomInset
        )
    }

    func setSessionID(_ newSessionID: String?) {
        guard newSessionID != sessionID else { return }
        sessionID = newSessionID
        hasPlacedInitialPosition = false
        hasAppliedSnapshot = false
        isApplyingSnapshot = false
        isPinnedToBottom = false
        setPinned(true)
        needsInitialBottomPlacement = false
        needsBottomAlignment = false
        previousRevisions = [:]
        lastAppliedRevision = -1
        pendingApply = nil
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
        collectionView.register(UICollectionViewCell.self, forCellWithReuseIdentifier: "ConversationCell")
        collectionView.register(
            StreamingAssistantCell.self,
            forCellWithReuseIdentifier: StreamingAssistantCell.reuseIdentifier
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
            let cell = collectionView.dequeueReusableCell(withReuseIdentifier: "ConversationCell", for: indexPath)
            cell.backgroundColor = .clear
            cell.backgroundConfiguration = UIBackgroundConfiguration.clear()
            cell.contentConfiguration = UIHostingConfiguration {
                entry.content.frame(maxWidth: .infinity, alignment: .leading)
            }.margins(.all, 0)
            return cell
        }
    }

    @objc private func dismissKeyboardFromConversation() {
        view.window?.endEditing(true)
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

    func apply(entries: [ConversationViewportEntry], revision: Int, bottomInset: CGFloat) {
        collectionView.contentInset.bottom = bottomInset
        collectionView.verticalScrollIndicatorInsets.bottom = bottomInset
        let ids = entries.map(\.id)
        let prependAnchor = capturePrependAnchor(for: ids)
        entriesByID = Dictionary(uniqueKeysWithValues: entries.map { ($0.id, $0) })
        let changed = entries.compactMap { previousRevisions[$0.id] == $0.revision ? nil : $0.id }
        let hasStructureChange = dataSource.snapshot().itemIdentifiers != ids
        guard hasStructureChange || !changed.isEmpty else { return }

        // UICollectionView forbids nested diffable applies. Keep exactly one
        // latest pending state: a fast stream can never create a main-queue
        // backlog, and the next apply always contains every packet folded so
        // far.
        guard !isApplyingSnapshot else {
            pendingApply = (entries, revision, bottomInset)
            return
        }
        let streamingChanged = changed.filter { id in
            previousRevisions[id] != nil && entriesByID[id]?.streamingAssistant != nil
        }
        let configuredChanged = changed.filter { !streamingChanged.contains($0) }
        let keepTail = isPinnedToBottom && revision != lastAppliedRevision
        let interactionGeneration = userInteractionGeneration
        lastAppliedRevision = revision
        previousRevisions = Dictionary(uniqueKeysWithValues: entries.map { ($0.id, $0.revision) })
        updateVisibleStreamingCells(streamingChanged)

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
        var snapshot = NSDiffableDataSourceSnapshot<Int, String>()
        snapshot.appendSections([0])
        snapshot.appendItems(ids)
        if #available(iOS 15.0, *) {
            snapshot.reconfigureItems(configuredChanged.filter { snapshot.indexOfItem($0) != nil })
        }
        let wasEmpty = !hasAppliedSnapshot
        dataSource.apply(snapshot, animatingDifferences: false) { [weak self] in
            guard let self else { return }
            self.hasAppliedSnapshot = true
            self.isApplyingSnapshot = false
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
                    bottomInset: pending.bottomInset
                )
            }
        }
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
        let itemSize = NSCollectionLayoutSize(
            widthDimension: .fractionalWidth(1),
            heightDimension: .estimated(44)
        )
        let item = NSCollectionLayoutItem(layoutSize: itemSize)
        let group = NSCollectionLayoutGroup.vertical(layoutSize: itemSize, subitems: [item])
        let section = NSCollectionLayoutSection(group: group)
        section.interGroupSpacing = 0
        return UICollectionViewCompositionalLayout(section: section)
    }
}

/// The active assistant response is the only row that changes for text
/// deltas. TextKit appends the suffix directly to `NSTextStorage`, preserving
/// all previously laid-out cells and avoiding a full SwiftUI/Markdown rebuild
/// for every WebSocket packet.
private final class StreamingAssistantCell: UICollectionViewCell {
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
