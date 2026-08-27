import Foundation
import SwiftUI
import UIKit

struct ConversationItem: Identifiable, Sendable {
    enum Kind: Equatable, Sendable { case user, context, assistant, reasoning, tool, jsonTool, toolResult, status, system }
    let id: String
    let kind: Kind
    let title: String
    let text: String
    let images: [GatewayImageAttachment]
    let isError: Bool
    let date: Date

    init(
        id: String,
        kind: Kind,
        title: String,
        text: String,
        images: [GatewayImageAttachment] = [],
        isError: Bool,
        date: Date
    ) {
        self.id = id
        self.kind = kind
        self.title = title
        self.text = text
        self.images = images
        self.isError = isError
        self.date = date
    }


}

/// A session-scoped presentation channel. It is deliberately not an
/// `ObservableObject`: streaming text must update the timeline without
/// invalidating the whole `ConversationView` (composer, pager and header).
/// The UIKit viewport subscribes directly and owns all scroll decisions.
@MainActor
final class ConversationTimeline {
    struct Snapshot {
        let items: [ConversationItem]
        let revision: Int
    }

    private var items: [ConversationItem] = []
    private var revision = 0
    private var observers: [UUID: (Snapshot) -> Void] = [:]

    var currentSnapshot: Snapshot {
        Snapshot(items: items, revision: revision)
    }

    func publish(_ items: [ConversationItem]) {
        self.items = items
        revision &+= 1
        let snapshot = currentSnapshot
        for observer in observers.values {
            observer(snapshot)
        }
    }

    @discardableResult
    func observe(_ observer: @escaping (Snapshot) -> Void) -> UUID {
        let id = UUID()
        observers[id] = observer
        observer(currentSnapshot)
        return id
    }

    func removeObserver(_ id: UUID) {
        observers[id] = nil
    }
}

/// Coalesces an arbitrarily bursty WebSocket stream to the device's display
/// cadence. Every packet is retained in the raw event log, while projection
/// always consumes the newest tail once per frame. There is no timer backlog
/// and no relationship to scroll state; tracking and deceleration continue to
/// receive touches while the model keeps advancing behind them.
@MainActor
final class ConversationProjectionDriver {
    private final class DisplayLinkTarget: NSObject {
        weak var owner: ConversationProjectionDriver?

        @MainActor @objc func tick() {
            owner?.displayLinkDidFire()
        }
    }

    private let action: () async -> Void
    private let target = DisplayLinkTarget()
    private lazy var displayLink: CADisplayLink = {
        let link = CADisplayLink(target: target, selector: #selector(DisplayLinkTarget.tick))
        link.add(to: .main, forMode: .common)
        link.isPaused = true
        return link
    }()
    private var isDirty = false
    private var isProjecting = false

    init(action: @escaping () async -> Void) {
        self.action = action
        target.owner = self
        _ = displayLink
    }

    func invalidate() {
        isDirty = true
        displayLink.isPaused = false
    }

    func stop() {
        isDirty = false
        displayLink.isPaused = true
    }

    private func displayLinkDidFire() {
        guard isDirty, !isProjecting else { return }
        isDirty = false
        isProjecting = true
        Task { @MainActor [weak self] in
            guard let self else { return }
            await self.action()
            self.isProjecting = false
            self.displayLink.isPaused = !self.isDirty
        }
    }
}
