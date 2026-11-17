import Foundation
import SwiftUI
import UIKit
import MarkdownUI

struct ConversationItem: Identifiable, Sendable {
    enum Kind: Equatable, Sendable { case user, context, assistant, reasoning, tool, jsonTool, toolResult, generatedImages, status, system }
    let id: String
    let kind: Kind
    let title: String
    let text: String
    let images: [GatewayImageAttachment]
    let isError: Bool
    let date: Date
    let files: [PresentedFile]
    let knownFiles: [PresentedFile]
    let textParts: [GatewayTextPart]?

    init(
        id: String,
        kind: Kind,
        title: String,
        text: String,
        images: [GatewayImageAttachment] = [],
        isError: Bool,
        date: Date,
        files: [PresentedFile] = [],
        knownFiles: [PresentedFile] = [],
        textParts: [GatewayTextPart]? = nil
    ) {
        self.id = id
        self.kind = kind
        self.title = title
        self.text = text
        self.images = images
        self.isError = isError
        self.files = files
        self.knownFiles = knownFiles
        self.textParts = textParts
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
        var historyPresentationStamp: HistoryPresentationStamp? = nil
    }

    private var items: [ConversationItem] = []
    private var revision = 0
    private var historyPresentationStamp: HistoryPresentationStamp?
    private var observers: [UUID: (Snapshot) -> Void] = [:]

    var currentSnapshot: Snapshot {
        Snapshot(items: items, revision: revision, historyPresentationStamp: historyPresentationStamp)
    }

    func publish(_ items: [ConversationItem], historyPresentationStamp: HistoryPresentationStamp? = nil) {
        self.items = items
        if let historyPresentationStamp { self.historyPresentationStamp = historyPresentationStamp }
        revision &+= 1
        let snapshot = currentSnapshot
        for observer in observers.values {
            observer(snapshot)
        }
    }

    func clearHistoryPresentationStamp(operationID: UUID) {
        if historyPresentationStamp?.operationID == operationID { historyPresentationStamp = nil }
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
    private let preferredFramesPerSecond: Float?
    private let target = DisplayLinkTarget()
    private lazy var displayLink: CADisplayLink = {
        let link = CADisplayLink(target: target, selector: #selector(DisplayLinkTarget.tick))
        link.add(to: .main, forMode: .common)
        if let preferredFramesPerSecond {
            link.preferredFrameRateRange = CAFrameRateRange(
                minimum: preferredFramesPerSecond,
                maximum: preferredFramesPerSecond,
                preferred: preferredFramesPerSecond
            )
        }
        link.isPaused = true
        return link
    }()
    private var isDirty = false
    private var isProjecting = false

    init(preferredFramesPerSecond: Float? = nil, action: @escaping () async -> Void) {
        self.action = action
        self.preferredFramesPerSecond = preferredFramesPerSecond
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

/// 相册只保留图片元数据；历史正文窗口裁剪后，已发现的图片仍可翻阅。
struct ConversationGalleryEntry: Equatable {
    let attachment: GatewayImageAttachment
    let date: Date
    let messageID: String
    let position: Int
    var sourceURL: URL? = nil

    static func merge(_ previous: [Self], items: [ConversationItem],
                      reference: (URL) -> GatewayImageAttachment? = { _ in nil }) -> [Self] {
        var byID = Dictionary(uniqueKeysWithValues: previous.map { ($0.attachment.id, $0) })
        for item in items {
            for (position, attachment) in item.images.enumerated() {
                byID[attachment.id] = Self(attachment: attachment, date: item.date,
                                          messageID: item.id, position: position)
            }
            guard item.text.contains("![") else { continue }
            for (position, source) in MarkdownUI.MarkdownContent(item.text).imageSources.enumerated() {
                guard let url = URL(string: source), MarkdownMath.Formula(url: url) == nil else { continue }
                let linked = reference(url)
                let attachment = linked ?? GatewayImageAttachment(attachmentId: url.absoluteString,
                    mediaType: "image/png", bytes: 0, width: 1, height: 1, name: url.lastPathComponent)
                if byID[attachment.id] == nil {
                    byID[attachment.id] = Self(attachment: attachment, date: item.date, messageID: item.id,
                        position: item.images.count + position, sourceURL: linked == nil ? url : nil)
                }
            }
        }
        return byID.values.sorted {
            if $0.date != $1.date { return $0.date < $1.date }
            if $0.messageID != $1.messageID {
                return $0.messageID.compare($1.messageID, options: .numeric) == .orderedAscending
            }
            return $0.position < $1.position
        }
    }
}

@MainActor
final class ConversationImageGalleryModel: ObservableObject {
    @Published private(set) var entries: [ConversationGalleryEntry]
    @Published private(set) var imageRevision = 0
    private let timeline: ConversationTimeline
    private var observerID: UUID?
    private let reference: (URL) -> GatewayImageAttachment?

    init(timeline: ConversationTimeline, seedItems: [ConversationItem] = [],
         reference: @escaping (URL) -> GatewayImageAttachment? = { _ in nil }) {
        self.timeline = timeline
        self.reference = reference
        entries = ConversationGalleryEntry.merge([], items: seedItems + timeline.currentSnapshot.items, reference: reference)
    }

    var attachments: [GatewayImageAttachment] { entries.map(\.attachment) }

    func start() {
        guard observerID == nil else { return }
        observerID = timeline.observe { [weak self] snapshot in
            guard let self else { return }
            let merged = ConversationGalleryEntry.merge(entries, items: snapshot.items, reference: reference)
            if merged != entries { entries = merged }
            // 附件的磁盘回填和网络完成也通过 timeline 通知，不依赖正文变化。
            imageRevision &+= 1
        }
    }

    func stop() {
        if let observerID { timeline.removeObserver(observerID) }
        observerID = nil
    }

    func neighbors(of id: String) -> [GatewayImageAttachment] {
        guard let index = entries.firstIndex(where: { $0.attachment.id == id }) else { return [] }
        return entries[max(0, index - 1)..<min(entries.count, index + 2)].map(\.attachment)
    }
}
