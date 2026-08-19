import Foundation
import SwiftUI
import UIKit

struct ConversationItem: Identifiable, Sendable {
    enum Kind: Equatable, Sendable { case user, context, assistant, reasoning, tool, jsonTool, toolResult, status, system }
    let id: String
    let kind: Kind
    let title: String
    let text: String
    let isError: Bool
    let date: Date

    /// Full (non-incremental) projection, used by callers that don't need to
    /// keep a `ConversationProjector` around (e.g. one-shot history commits).
    static func make(from events: [SessionEvent]) -> [ConversationItem] {
        let projector = ConversationProjector()
        projector.rebuild(from: events)
        return projector.items
    }

    private static func contextSourceName(_ event: GatewayEvent) -> String {
        if let plugin = event.raw?["source"]?["plugin"]?.stringValue, !plugin.isEmpty {
            return plugin
        }
        return event.source ?? "context"
    }

    /// 合并单条会话事件。
    static func fold(
        _ record: SessionEvent,
        into items: inout [ConversationItem],
        streamIndexes: inout [String: Int],
        finalizedKeys: inout Set<String>
    ) {
        let event = record.event
        let key = "\(event.turn ?? -1)-\(event.step ?? -1)"
        switch event.type {
        case "user/message" where event.source == nil || event.source == "user":
            if let text = event.text, !text.isEmpty {
                items.append(.init(id: record.id, kind: .user, title: "你", text: text, isError: false, date: record.date))
            }
        case "user/message":
            if let text = event.text, !text.isEmpty {
                items.append(.init(
                    id: record.id,
                    kind: .context,
                    title: "上下文注入 · \(contextSourceName(event))",
                    text: text,
                    isError: false,
                    date: record.date
                ))
            }
        case "assistant/chunk" where event.chunkType == "text-delta" && !finalizedKeys.contains(key):
            appendStream(id: "stream-text-\(key)", key: "text-\(key)", kind: .assistant, title: "DeepSeek · 正在生成", delta: event.text ?? "", date: record.date, result: &items, indexes: &streamIndexes)
        case "assistant/chunk" where event.chunkType == "reasoning-delta" && !finalizedKeys.contains(key):
            appendStream(id: "stream-reason-\(key)", key: "reason-\(key)", kind: .reasoning, title: "Think · 正在推理", delta: event.text ?? "", date: record.date, result: &items, indexes: &streamIndexes)
        case "assistant/chunk" where event.chunkType == "tool-call-delta" && !finalizedKeys.contains(key):
            let toolKey = event.tool?.id ?? key
            let name = event.tool?.name ?? "Tool Call · 正在组装"
            let kind: Kind = name.caseInsensitiveCompare("run_code") == .orderedSame ? .jsonTool : .tool
            appendStream(id: "stream-tool-\(toolKey)", key: "tool-\(toolKey)", kind: kind, title: name, delta: event.tool?.argumentsDelta ?? "", date: record.date, result: &items, indexes: &streamIndexes)
        case "assistant/message":
            // A completed message supersedes any in-flight streaming bubble
            // for the same turn/step: drop the partial entry so only the
            // clean final text remains, and remember the key so any
            // straggling deltas for it (a rare race on reconnect) are
            // ignored instead of resurrecting a stale bubble.
            finalizedKeys.insert(key)
            removeStreamEntry(forKey: "text-\(key)", items: &items, indexes: &streamIndexes)
            removeStreamEntry(forKey: "reason-\(key)", items: &items, indexes: &streamIndexes)
            if let reasoning = event.reasoning, !reasoning.isEmpty {
                items.append(.init(id: record.id + "-reason", kind: .reasoning, title: "Think", text: reasoning, isError: false, date: record.date))
            }
            if let text = event.text, !text.isEmpty {
                items.append(.init(id: record.id, kind: .assistant, title: "DeepSeek", text: text, isError: false, date: record.date))
            }
        case "tool/call":
            let name = event.name ?? "Tool Call"
            let kind: Kind = name.caseInsensitiveCompare("run_code") == .orderedSame ? .jsonTool : .tool
            items.append(.init(id: record.id, kind: kind, title: name, text: event.arguments?.jsonDisplayText ?? "", isError: false, date: record.date))
        case "tool/result":
            items.append(.init(id: record.id, kind: .toolResult, title: event.isError == true ? "工具失败" : "工具完成", text: event.preview ?? "", isError: event.isError == true, date: record.date))
        default:
            break
        }
    }

    private static func appendStream(id: String, key: String, kind: Kind, title: String, delta: String, date: Date, result: inout [ConversationItem], indexes: inout [String: Int]) {
        guard !delta.isEmpty else { return }
        if let index = indexes[key] {
            let old = result[index]
            result[index] = .init(id: old.id, kind: old.kind, title: old.title, text: old.text + delta, isError: old.isError, date: date)
        } else {
            indexes[key] = result.count
            result.append(.init(id: id, kind: kind, title: title, text: delta, isError: false, date: date))
        }
    }

    private static func removeStreamEntry(forKey key: String, items: inout [ConversationItem], indexes: inout [String: Int]) {
        guard let index = indexes.removeValue(forKey: key) else { return }
        items.remove(at: index)
        for (otherKey, otherIndex) in indexes where otherIndex > index {
            indexes[otherKey] = otherIndex - 1
        }
    }
}

/// Incrementally projects the compact `ConversationItem` timeline from the
/// raw, seq-ordered event log for one session. A cold `rebuild(from:)`
/// replays every event once — paid the first time a session is opened in a
/// process, or right after a history reload invalidates the cache — and
/// every subsequent live tick only needs to `fold(_:)` the handful of events
/// that arrived since the previous tick. This is what keeps rendering
/// keeping pace with the WebSocket even as a session's total event count
/// grows into the thousands; re-running a full rebuild on every streaming
/// chunk (the previous approach) made each tick progressively more
/// expensive, which is why playback used to fall further and further behind
/// as a long reply generated.
///
/// `@unchecked Sendable` is safe here because instances are only ever
/// touched serially from the main actor, with `await` boundaries separating
/// any hand-off to a background task (see `AppStore.projectIncrementally`).
final class ConversationProjector: @unchecked Sendable {
    private(set) var items: [ConversationItem] = []
    private var streamIndexes: [String: Int] = [:]
    private var finalizedKeys: Set<String> = []
    private(set) var lastSeq: Int = -1

    func reset() {
        items = []
        streamIndexes = [:]
        finalizedKeys = []
        lastSeq = -1
    }

    func rebuild(from events: [SessionEvent]) {
        reset()
        fold(events)
    }

    /// `newEvents` must be seq-ascending and every seq must exceed `lastSeq`
    /// (i.e. genuinely new, append-only live events).
    func fold(_ newEvents: [SessionEvent]) {
        for record in newEvents {
            ConversationItem.fold(record, into: &items, streamIndexes: &streamIndexes, finalizedKeys: &finalizedKeys)
            lastSeq = max(lastSeq, record.seq)
        }
    }

    /// Binary search over a seq-ascending array for the first index whose
    /// seq exceeds `seq` — the start of the "not yet folded" tail.
    static func firstIndexAfter(_ seq: Int, in events: [SessionEvent]) -> Int {
        var low = 0
        var high = events.count
        while low < high {
            let mid = (low + high) / 2
            if events[mid].seq <= seq { low = mid + 1 } else { high = mid }
        }
        return low
    }
}

/// A fully projected history baseline built away from the main actor while the
/// currently installed projector continues consuming live WebSocket events.
/// The baseline is committed only after the caller has folded every live event
/// that arrived during the build, so history loading never owns or pauses the
/// realtime tail.
struct ConversationHistoryRebase: @unchecked Sendable {
    var events: [SessionEvent]
    let projector: ConversationProjector

    static func build(history: [SessionEvent], current: [SessionEvent]) -> ConversationHistoryRebase {
        var records = Dictionary(uniqueKeysWithValues: current.map { ($0.seq, $0) })
        for record in history {
            // Prefer the live copy when the same immutable sequence appears in
            // both lanes. It is the freshest decoded representation and keeps
            // reconnect duplicates from replacing an event already displayed.
            if records[record.seq] == nil {
                records[record.seq] = record
            }
        }
        let merged = records.values.sorted { $0.seq < $1.seq }
        let projector = ConversationProjector()
        projector.rebuild(from: merged)
        return ConversationHistoryRebase(
            events: merged,
            projector: projector
        )
    }

    /// Folds only the strictly newer suffix that arrived while `build` was
    /// running. The caller guards this with its non-append mutation epoch, so
    /// a reconnect correction or other out-of-order change requests a fresh
    /// background rebase instead of entering this fast path.
    mutating func appendLiveTail(from latest: [SessionEvent]) {
        let start = ConversationProjector.firstIndexAfter(projector.lastSeq, in: latest)
        guard start < latest.count else { return }
        let tail = Array(latest[start...])
        projector.fold(tail)
        events.append(contentsOf: tail)
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
