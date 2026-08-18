import SwiftUI
import UIKit

struct ConversationViewportEntry: Identifiable {
    let id: String
    let revision: Int
    let content: AnyView
}

/// UIKit-backed viewport for streaming conversation timelines.
struct ConversationViewport: UIViewControllerRepresentable {
    let sessionID: String?
    let entries: [ConversationViewportEntry]
    let bottomInset: CGFloat
    let revision: Int
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
        controller.setSessionID(sessionID)
        controller.apply(entries: entries, revision: revision, bottomInset: bottomInset)
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
    private var lastAppliedRevision = -1

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
        collectionView.register(UICollectionViewCell.self, forCellWithReuseIdentifier: "ConversationCell")
        view.addSubview(collectionView)
        collectionView.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            collectionView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            collectionView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            collectionView.topAnchor.constraint(equalTo: view.topAnchor),
            collectionView.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        ])
        dataSource = UICollectionViewDiffableDataSource<Int, String>(collectionView: collectionView) { [weak self] collectionView, indexPath, id in
            let cell = collectionView.dequeueReusableCell(withReuseIdentifier: "ConversationCell", for: indexPath)
            guard let entry = self?.entriesByID[id] else { return cell }
            cell.backgroundColor = .clear
            cell.backgroundConfiguration = UIBackgroundConfiguration.clear()
            cell.contentConfiguration = UIHostingConfiguration {
                entry.content.frame(maxWidth: .infinity, alignment: .leading)
            }.margins(.all, 0)
            return cell
        }
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

        // UICollectionView forbids applying a diffable snapshot while it is
        // already reconciling an earlier one. Streaming chunks can reach this
        // method faster than UICollectionView finishes that reconciliation;
        // coalesce the pending update onto the next main-loop turn instead of
        // issuing a nested apply (which caused the enter-session crash).
        guard !isApplyingSnapshot else {
            DispatchQueue.main.async { [weak self] in
                self?.apply(entries: entries, revision: revision, bottomInset: bottomInset)
            }
            return
        }
        isApplyingSnapshot = true
        previousRevisions = Dictionary(uniqueKeysWithValues: entries.map { ($0.id, $0.revision) })
        var snapshot = NSDiffableDataSourceSnapshot<Int, String>()
        snapshot.appendSections([0])
        snapshot.appendItems(ids)
        if #available(iOS 15.0, *) {
            snapshot.reconfigureItems(changed.filter { snapshot.indexOfItem($0) != nil })
        }
        let keepTail = isPinnedToBottom && revision != lastAppliedRevision
        lastAppliedRevision = revision
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
            } else if keepTail {
                self.scrollToBottom()
            }
        }
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
        let atBottom = scrollView.contentOffset.y + scrollView.bounds.height + scrollView.adjustedContentInset.bottom >= scrollView.contentSize.height - 28
        setPinned(atBottom)
        if !isProgrammaticScroll,
           (scrollView.isDragging || scrollView.isDecelerating),
           scrollView.contentOffset.y + scrollView.adjustedContentInset.top < 420 {
            onApproachingTop()
        }
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
