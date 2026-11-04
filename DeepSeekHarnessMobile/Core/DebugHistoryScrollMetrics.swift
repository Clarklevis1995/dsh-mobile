#if DEBUG
import Foundation
import UIKit

/// 显式启用的模拟器主线程回调诊断。它不测 GPU 呈现帧率，也不读取任何会话标识或正文。
@MainActor
final class DebugHistoryScrollMetrics {
    private struct Sample: Codable, Sendable {
        let elapsedSeconds: Double
        let displayTimestampElapsedSeconds: Double?
        let previousArrivalElapsedSeconds: Double?
        let callbackArrivalIntervalMs: Double
        let displayTimestampIntervalMs: Double?
        let expectedDurationMs: Double
        let callbackDeadlineLatenessMs: Double
        let phase: String
        let rowCount: Int
        let isScrolling: Bool
        let isHistoryLoading: Bool
        let loadOrdinal: Int?
        let activationClipped: Bool
        let isFirstCallback: Bool
    }

    private struct Event: Codable, Sendable {
        let elapsedSeconds: Double
        let phase: String
        let rowCount: Int
        let insertedRows: Int
        let anchorValid: Bool?
        let anchorErrorPt: Double?
        let loadOrdinal: Int?
        let acceptedCallOrdinal: Int?
        let coalesced: Bool?
        let outcome: String?
        let reason: String?
        let projectionEpoch: Int?
    }

    enum SchedulerStage: String {
        case accepted, targetBuffered = "target_buffered", idleWait = "idle_wait", interrupted
        case prepareAdmitted = "prepare_admitted", preparationFinished = "preparation_finished"
        case commitAdmitted = "commit_admitted", batchPresented = "batch_presented", finalAck = "final_ack"
        case cancelled, idleDrained = "idle_drained"
    }
    private struct SchedulerEvent: Codable, Sendable {
        let elapsedSeconds: Double
        let stage: String
        let loadOrdinal: Int?
        let generation: Int
        let entries: Int
        let batchEntries: Int
        let insertedEntries: Int
        let remainingEntries: Int
        let moving: Bool
        let acceptedAgeMs: Double?
        let bufferedAgeMs: Double?
        let firstPresentation: Bool?
        let route: String?
        let changedExistingCount: Int
        let insertedBodyEntries: Int
        let insertedSupplementalEntries: Int
        let projectionEpoch: Int?
    }

    private struct AggregateKey: Hashable {
        let phase: String
        let reason: String?
        let loadOrdinal: Int?
    }

    private struct DiagnosticAggregate: Encodable, Sendable {
        let phase: String
        let reason: String?
        let loadOrdinal: Int?
        let firstElapsedSeconds: Double
        var lastElapsedSeconds: Double
        var count: Int
    }

    private struct Report: Encodable, Sendable {
        let schemaVersion = 3
        let measurement = "CADisplayLink callback pacing; not GPU/rendered FPS"
        let renderFPSStatus = "unsupported"
        let measurementStart = "first_user_drag"
        let initialPipelineInFlight: Bool
        let endReason: String
        let durationSeconds: Double
        let maximumFramesPerSecond: Int
        let sampleLimitReached: Bool
        let eventLimitReached: Bool
        let unfinishedPagination: Bool
        let unfinishedPreparation: Bool
        let unfinishedCommit: Bool
        let unfinishedGesture: Bool
        let lifecycleMismatch: Bool
        let acceptedCallCount: Int
        let coalescedAcceptedCallCount: Int
        let rejectedCallCount: Int
        let approachCallCount: Int
        let diagnosticAggregateCapacity = 128
        let diagnosticAggregateLimitReached: Bool
        let droppedApproachAggregateCallCount: Int
        let droppedRejectedAggregateCallCount: Int
        let diagnosticAggregates: [DiagnosticAggregate]
        let staleOperationFinishCount: Int
        let operationTrackingMode: String
        let loadingSemantics = "accepted-pagination operation pending until terminal and final required projection presentation; not network I/O"
        let trailingActiveUnobservedMs: Double
        let samples: [Sample]
        let events: [Event]
        let schedulerEvents: [SchedulerEvent]?
        let schedulerEventLimitReached: Bool?
        let rowMeasurements: [DebugHistoryRowMeasurements.Measurement]?
        let rowMeasurementLimitReached: Bool?
    }

    private final class Target: NSObject {
        weak var owner: DebugHistoryScrollMetrics?
        @MainActor @objc func tick(_ link: CADisplayLink) { owner?.tick(link) }
    }

    static func makeIfEnabled() -> DebugHistoryScrollMetrics? {
        ProcessInfo.processInfo.environment["DSH_HISTORY_SCROLL_METRICS"] == "1" ? .init() : nil
    }

    private let target = Target()
    private var link: CADisplayLink?
    private var timer: Timer?
    private var startedAt: CFTimeInterval?
    private var previousArrival: CFTimeInterval?
    private var previousDisplayTimestamp: CFTimeInterval?
    private var activeSince: CFTimeInterval?
    private var settlingUntil: CFTimeInterval = 0
    private var scrolling = false
    private var currentLoadOrdinal: Int?
    private var nextLoadOrdinal = 0
    private var currentOperationID: UUID?
    private var operationOrdinals: [UUID: Int] = [:]
    private var acceptedCallCount = 0
    private var coalescedAcceptedCallCount = 0
    private var rejectedCallCount = 0
    private var approachCallCount = 0
    private var diagnosticAggregateLimitReached = false
    private var droppedApproachAggregateCallCount = 0
    private var droppedRejectedAggregateCallCount = 0
    private var diagnosticAggregates: [DiagnosticAggregate] = []
    private var aggregateIndexes: [AggregateKey: Int] = [:]
    private var committedEpochs: [UUID: Set<Int>] = [:]
    private var committedEpochCount = 0
    private static let rejectionReasons: Set<String> = ["presentation_pending", "domain_loading",
                                                        "no_more_history", "projection_pending", "other"]
    private var staleOperationFinishCount = 0
    private let operationTrackingMode = ProcessInfo.processInfo.environment["DSH_HISTORY_SCROLL_OPERATION_MODE"]
        == "observational_coalescing" ? "observational_coalescing" : "owned_admission"
    private var eventLimitReached = false
    private var lifecycleMismatch = false
    private var initialPipelineInFlight = false
    private var preparing = false
    private var committing = false
    private var rows = 0
    private var samples: [Sample] = []
    private var events: [Event] = []
    private var schedulerEvents: [SchedulerEvent] = []
    private var schedulerEventLimitReached = false
    private var operationAcceptedAt: Double?
    private var operationBufferedAt: Double?
    private var operationHasPresented = false
    private var finished = false
    private let rowMeasurements = DebugHistoryRowMeasurements.makeIfEnabled()
    private static let writer = DispatchQueue(label: "com.dsh.debug-history-scroll-export", qos: .utility)

    func beginRowMeasurement(rowID: String, kind: String, widthPoints: CGFloat,
                             source: String) -> DebugHistoryRowMeasurements.Scope? {
        guard let start = startedAt, !finished else { return nil }
        return rowMeasurements?.begin(rowID: rowID, kind: kind, widthPoints: Double(widthPoints),
                                      source: source, reportStartedAt: start, loadOrdinal: currentLoadOrdinal)
    }

    func beginViewport(rowCount: Int, initialPipelineInFlight: Bool = false) {
        guard startedAt == nil, !finished else { return }
        startedAt = CACurrentMediaTime()
        self.initialPipelineInFlight = initialPipelineInFlight
        rows = rowCount
        samples.reserveCapacity(16_000)
        diagnosticAggregates.reserveCapacity(128)
        aggregateIndexes.reserveCapacity(128)
        target.owner = self
        let displayLink = CADisplayLink(target: target, selector: #selector(Target.tick(_:)))
        link = displayLink
        displayLink.add(to: .main, forMode: .common)
        let configured = Double(ProcessInfo.processInfo.environment["DSH_HISTORY_SCROLL_METRICS_SECONDS"] ?? "") ?? 60
        let duration = min(120, max(15, configured))
        let timer = Timer(timeInterval: duration, repeats: false) { [weak self] _ in
            MainActor.assumeIsolated { self?.finish(reason: "duration") }
        }
        self.timer = timer
        RunLoop.main.add(timer, forMode: .common)
        event("viewport", rowCount: rowCount)
    }

    func scrollBegan(rowCount: Int) {
        activate()
        scrolling = true
        rows = rowCount
        event("scroll_begin", rowCount: rowCount)
    }

    func scrollEnded(rowCount: Int) {
        scrolling = false
        settle()
        event("scroll_end", rowCount: rowCount)
    }

    func historyApproached(rowCount: Int) {
        // 高频触顶不占关键生命周期的 256 槽，也不把闲置时间当作加载。
        guard startedAt != nil, !finished else { return }
        rows = rowCount
        approachCallCount += 1
        aggregate("history_approach")
    }

    /// 每次真正通过既有领域准入的 UI 调用都记录，不用 operation 去重掩盖 accepted 次数。
    /// baseline 只观测时可将多个原本允许的调用合并在同一观测 operation 下。
    func historyOperationBegan(operationID: UUID, rowCount: Int) {
        guard startedAt != nil, !finished else { return }
        activate()
        acceptedCallCount += 1
        let ordinal = ordinal(for: operationID)
        let coalesced = currentOperationID != nil
        if coalesced { coalescedAcceptedCallCount += 1 }
        if let currentOperationID {
            if currentOperationID != operationID { lifecycleMismatch = true }
        } else {
            currentOperationID = operationID
            currentLoadOrdinal = ordinal
            operationAcceptedAt = CACurrentMediaTime()
            operationBufferedAt = nil
            operationHasPresented = false
        }
        event("history_load_requested", rowCount: rowCount, loadOrdinal: ordinal,
              acceptedCallOrdinal: acceptedCallCount, coalesced: coalesced)
        historySchedulerEvent(.accepted, generation: 0, entries: rowCount, batchEntries: 0, moving: scrolling)
    }

    /// 只有 owner 已确认 KMP terminal 与最后 required projection 真正呈现后才能结束。
    /// 早期 page 的 commitEnd、网络 loading=false 都不能调用本方法。
    func historyOperationFinished(operationID: UUID, outcome: String, rowCount: Int) {
        guard startedAt != nil, !finished else { return }
        guard currentOperationID == operationID else {
            staleOperationFinishCount += 1
            event("history_operation_finish_ignored", rowCount: rowCount,
                  loadOrdinal: operationOrdinals[operationID], outcome: safeOutcome(outcome), useCurrentOrdinal: false)
            return
        }
        event("history_operation_complete", rowCount: rowCount,
              outcome: safeOutcome(outcome))
        currentOperationID = nil
        currentLoadOrdinal = nil
        operationAcceptedAt = nil
        operationBufferedAt = nil
        settle()
    }

    func historyOperationRequired(operationID: UUID, projectionEpoch: Int, rowCount: Int) {
        operationEvent("history_operation_required", operationID: operationID,
                       projectionEpoch: projectionEpoch, rowCount: rowCount)
    }

    func historySchedulerEvent(_ stage: SchedulerStage, generation: Int, entries: Int, batchEntries: Int,
                               insertedEntries: Int = 0, remainingEntries: Int = 0, moving: Bool,
                               route: String? = nil, changedExistingCount: Int = 0,
                               insertedBodyEntries: Int = 0, insertedSupplementalEntries: Int = 0,
                               projectionEpoch: Int? = nil) {
        guard let start = startedAt, !finished else { return }
        guard schedulerEvents.count < 256 else { schedulerEventLimitReached = true; return }
        let now = CACurrentMediaTime()
        if stage == .targetBuffered, operationBufferedAt == nil { operationBufferedAt = now }
        let first = stage == .batchPresented ? (!operationHasPresented && insertedBodyEntries > 0) : nil
        if stage == .batchPresented, insertedBodyEntries > 0 { operationHasPresented = true }
        schedulerEvents.append(SchedulerEvent(elapsedSeconds: now - start, stage: stage.rawValue,
            loadOrdinal: currentLoadOrdinal, generation: generation, entries: entries, batchEntries: batchEntries,
            insertedEntries: insertedEntries, remainingEntries: remainingEntries, moving: moving,
            acceptedAgeMs: operationAcceptedAt.map { (now - $0) * 1_000 },
            bufferedAgeMs: operationBufferedAt.map { (now - $0) * 1_000 }, firstPresentation: first,
            route: route, changedExistingCount: changedExistingCount,
            insertedBodyEntries: insertedBodyEntries, insertedSupplementalEntries: insertedSupplementalEntries,
            projectionEpoch: projectionEpoch))
    }

    func historyOperationIssued(operationID: UUID, projectionEpoch: Int, rowCount: Int) {
        operationEvent("history_operation_issued", operationID: operationID,
                       projectionEpoch: projectionEpoch, rowCount: rowCount)
    }

    /// 仅由实际 apply/anchor 完成或明确 no-change 已呈现路径调用；同历史 epoch 的 live 合并去重。
    func historyOperationCommitted(operationID: UUID, projectionEpoch: Int, rowCount: Int) {
        guard startedAt != nil, !finished else { return }
        if committedEpochs[operationID]?.contains(projectionEpoch) == true { return }
        guard committedEpochCount < 256 else { eventLimitReached = true; return }
        committedEpochs[operationID, default: []].insert(projectionEpoch)
        committedEpochCount += 1
        operationEvent("history_operation_committed", operationID: operationID,
                       projectionEpoch: projectionEpoch, rowCount: rowCount)
    }

    func historyOperationTerminal(operationID: UUID, rowCount: Int) {
        operationEvent("history_operation_terminal", operationID: operationID, rowCount: rowCount)
    }

    func historyOperationPresented(operationID: UUID, projectionEpoch: Int, rowCount: Int) {
        operationEvent("history_operation_presented", operationID: operationID,
                       projectionEpoch: projectionEpoch, rowCount: rowCount)
    }

    private func operationEvent(_ phase: String, operationID: UUID, projectionEpoch: Int? = nil,
                                rowCount: Int) {
        guard startedAt != nil, !finished else { return }
        // 迟到的旧 operation 只能成为 ignored 证据，不能修改当前 operation 的阶段。
        event(currentOperationID == operationID ? phase : phase + "_ignored",
              rowCount: rowCount, loadOrdinal: operationOrdinals[operationID],
              projectionEpoch: projectionEpoch, useCurrentOrdinal: false)
    }

    func historyAdmissionRejected(rowCount: Int, reason: String) {
        guard startedAt != nil, !finished else { return }
        rejectedCallCount += 1
        rows = rowCount
        // 不写调用者任意字符串；每帧拒绝只累加独立有界聚合，不挤占关键事件。
        aggregate("history_admission_rejected", reason: Self.rejectionReasons.contains(reason) ? reason : "other")
    }

    private func aggregate(_ phase: String, reason: String? = nil) {
        guard let start = startedAt, !finished else { return }
        let elapsed = CACurrentMediaTime() - start
        let key = AggregateKey(phase: phase, reason: reason, loadOrdinal: currentLoadOrdinal)
        if let index = aggregateIndexes[key] {
            diagnosticAggregates[index].lastElapsedSeconds = elapsed
            diagnosticAggregates[index].count += 1
            return
        }
        guard diagnosticAggregates.count < 128 else {
            diagnosticAggregateLimitReached = true
            if phase == "history_approach" { droppedApproachAggregateCallCount += 1 }
            else { droppedRejectedAggregateCallCount += 1 }
            return
        }
        aggregateIndexes[key] = diagnosticAggregates.count
        diagnosticAggregates.append(DiagnosticAggregate(phase: phase, reason: reason,
            loadOrdinal: currentLoadOrdinal, firstElapsedSeconds: elapsed, lastElapsedSeconds: elapsed, count: 1))
    }

    private func ordinal(for operationID: UUID) -> Int? {
        if let ordinal = operationOrdinals[operationID] { return ordinal }
        guard operationOrdinals.count < 256 else { eventLimitReached = true; return nil }
        nextLoadOrdinal += 1
        operationOrdinals[operationID] = nextLoadOrdinal
        return nextLoadOrdinal
    }

    private func safeOutcome(_ outcome: String) -> String {
        let outcomes: Set<String> = ["presented", "empty", "failed", "cancelled", "detached"]
        guard outcomes.contains(outcome) else { lifecycleMismatch = true; return "invalid" }
        return outcome
    }

    func historyPrepare(rowCount: Int) {
        activate()
        preparing = true
        event("history_prepare", rowCount: rowCount)
    }

    func historyPreparationEnded(rowCount: Int) {
        preparing = false
        settle()
        event("history_prepare_end", rowCount: rowCount)
    }

    func historyCommitBegan(rowCount: Int, insertedRows: Int) -> Int? {
        activate()
        committing = true
        event("history_commit_begin", rowCount: rowCount, insertedRows: insertedRows)
        return currentLoadOrdinal
    }

    func historyCommitEnded(rowCount: Int, insertedRows: Int, anchorValid: Bool,
                            anchorErrorPt: Double?, loadOrdinal: Int?) {
        committing = false
        preparing = false
        settle()
        if let loadOrdinal, currentLoadOrdinal != loadOrdinal { lifecycleMismatch = true }
        event("history_commit_end", rowCount: rowCount, insertedRows: insertedRows,
              anchorValid: anchorValid, anchorErrorPt: anchorErrorPt, loadOrdinal: loadOrdinal, useCurrentOrdinal: false)
        // 一次 operation 可以分多页/多次 commit；只有 owner 的 complete 才能清 pipeline。
    }

    func finish(reason: String = "viewport_disappeared") {
        guard let start = startedAt, !finished else { return }
        finished = true
        link?.invalidate()
        link = nil
        timer?.invalidate()
        timer = nil
        let now = CACurrentMediaTime()
        let tailIsActive = activeSince != nil && (scrolling || currentLoadOrdinal != nil || preparing || committing
            || previousArrival.map { $0 <= settlingUntil } == true)
        let unobservedTail = tailIsActive ? max(0, now - (previousArrival ?? activeSince ?? start)) * 1_000 : 0
        let report = Report(initialPipelineInFlight: initialPipelineInFlight,
                            endReason: reason, durationSeconds: now - start,
                            maximumFramesPerSecond: UIScreen.main.maximumFramesPerSecond,
                            sampleLimitReached: samples.count >= 20_000,
                            eventLimitReached: eventLimitReached,
                            unfinishedPagination: currentLoadOrdinal != nil,
                            unfinishedPreparation: preparing, unfinishedCommit: committing,
                            unfinishedGesture: scrolling, lifecycleMismatch: lifecycleMismatch,
                            acceptedCallCount: acceptedCallCount,
                            coalescedAcceptedCallCount: coalescedAcceptedCallCount,
                            rejectedCallCount: rejectedCallCount,
                            approachCallCount: approachCallCount,
                            diagnosticAggregateLimitReached: diagnosticAggregateLimitReached,
                            droppedApproachAggregateCallCount: droppedApproachAggregateCallCount,
                            droppedRejectedAggregateCallCount: droppedRejectedAggregateCallCount,
                            diagnosticAggregates: diagnosticAggregates,
                            staleOperationFinishCount: staleOperationFinishCount,
                            operationTrackingMode: operationTrackingMode,
                            trailingActiveUnobservedMs: unobservedTail, samples: samples, events: events,
                            schedulerEvents: schedulerEvents, schedulerEventLimitReached: schedulerEventLimitReached,
                            rowMeasurements: rowMeasurements?.seal(),
                            rowMeasurementLimitReached: rowMeasurements?.limitReached)
        guard let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return }
        let directory = documents.appendingPathComponent("DebugHistoryScrollMetrics", isDirectory: true)
        Self.writer.async { Self.export(report, directory: directory) }
    }

    private func activate() {
        guard startedAt != nil, !finished else { return }
        if activeSince == nil { activeSince = CACurrentMediaTime() }
    }

    private func settle() { settlingUntil = CACurrentMediaTime() + 1 }

    private func event(_ phase: String, rowCount: Int, insertedRows: Int = 0,
                       anchorValid: Bool? = nil, anchorErrorPt: Double? = nil, loadOrdinal: Int? = nil,
                       acceptedCallOrdinal: Int? = nil, coalesced: Bool? = nil, outcome: String? = nil,
                       reason: String? = nil, projectionEpoch: Int? = nil, useCurrentOrdinal: Bool = true) {
        rows = rowCount
        guard let start = startedAt, !finished else { return }
        guard events.count < 256 else { eventLimitReached = true; return }
        events.append(Event(elapsedSeconds: CACurrentMediaTime() - start, phase: phase,
                            rowCount: rowCount, insertedRows: insertedRows, anchorValid: anchorValid,
                            anchorErrorPt: anchorErrorPt.flatMap { $0.isFinite ? $0 : nil },
                            loadOrdinal: useCurrentOrdinal ? (loadOrdinal ?? currentLoadOrdinal) : loadOrdinal,
                            acceptedCallOrdinal: acceptedCallOrdinal, coalesced: coalesced,
                            outcome: outcome, reason: reason, projectionEpoch: projectionEpoch))
    }

    private func tick(_ link: CADisplayLink) {
        let now = CACurrentMediaTime()
        defer {
            previousArrival = now
            previousDisplayTimestamp = link.timestamp
        }
        guard let start = startedAt, !finished else { return }
        let phase: String
        if committing { phase = "commit" }
        else if preparing { phase = "prepare" }
        else if scrolling { phase = "scroll" }
        else if currentLoadOrdinal != nil { phase = "waiting" }
        else if now <= settlingUntil || (activeSince != nil && previousArrival.map { $0 <= settlingUntil } == true) {
            // 第一个越过 settle 期限的回调仍采集，不能把跨越期限的长停顿当作闲置丢弃。
            phase = "settle"
        }
        else { activeSince = nil; return }
        guard let activeSince else { return }
        guard samples.count < 20_000 else { finish(reason: "sample_limit"); return }
        // 切入活跃区间时仅从事件实际发生时计时；活跃期间的卡顿绝不能用 timestamp 跳帧过滤。
        // 首个回调也保留首滑到此刻的真实停顿；没有前一个 display timestamp 时明确留空。
        let arrivalInterval = now - max(previousArrival ?? start, activeSince)
        samples.append(Sample(elapsedSeconds: now - start, displayTimestampElapsedSeconds: link.timestamp - start,
                              previousArrivalElapsedSeconds: previousArrival.map { $0 - start },
                              callbackArrivalIntervalMs: arrivalInterval * 1_000,
                              displayTimestampIntervalMs: previousDisplayTimestamp.map { (link.timestamp - $0) * 1_000 },
                              expectedDurationMs: (link.targetTimestamp - link.timestamp) * 1_000,
                              callbackDeadlineLatenessMs: max(0, now - link.targetTimestamp) * 1_000,
                              phase: phase, rowCount: rows, isScrolling: scrolling,
                              isHistoryLoading: currentLoadOrdinal != nil, loadOrdinal: currentLoadOrdinal,
                              activationClipped: previousArrival.map { $0 < activeSince } ?? true,
                              isFirstCallback: previousArrival == nil))
    }

    nonisolated private static func export(_ report: Report, directory: URL) {
        do {
            let manager = FileManager.default
            try manager.createDirectory(at: directory, withIntermediateDirectories: true)
            let name = "history-scroll-\(Int(Date().timeIntervalSince1970))-\(UUID().uuidString.prefix(8))"
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
            try encoder.encode(report).write(to: directory.appendingPathComponent(name + ".json"), options: .atomic)
            var csv = "elapsed_seconds,callback_arrival_interval_ms,display_timestamp_interval_ms,expected_duration_ms,callback_deadline_lateness_ms,phase,row_count,is_scrolling,is_history_pipeline_pending,load_ordinal,activation_clipped,is_first_callback,display_timestamp_elapsed_seconds,previous_arrival_elapsed_seconds\n"
            for sample in report.samples {
                let values = [
                    String(sample.elapsedSeconds), String(sample.callbackArrivalIntervalMs),
                    sample.displayTimestampIntervalMs.map { String($0) } ?? "",
                    String(sample.expectedDurationMs), String(sample.callbackDeadlineLatenessMs),
                    sample.phase, String(sample.rowCount), String(sample.isScrolling),
                    String(sample.isHistoryLoading), sample.loadOrdinal.map { String($0) } ?? "",
                    String(sample.activationClipped), String(sample.isFirstCallback),
                    sample.displayTimestampElapsedSeconds.map { String($0) } ?? "",
                    sample.previousArrivalElapsedSeconds.map { String($0) } ?? ""
                ]
                csv += values.joined(separator: ",") + "\n"
            }
            try Data(csv.utf8).write(to: directory.appendingPathComponent(name + ".csv"), options: .atomic)
            // 每组最多 20,000 样本 / 256 事件，固定保留最近四组，防止诊断磁盘无限增长。
            let files = try manager.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.creationDateKey])
                .filter { $0.lastPathComponent.hasPrefix("history-scroll-") && ["json", "csv"].contains($0.pathExtension) }
                .sorted { $0.lastPathComponent > $1.lastPathComponent }
            for file in files.dropFirst(8) { try? manager.removeItem(at: file) }
        } catch {
            // 仅诊断导出失败，不更改产品状态，不写会话数据到日志。
            appLog("DebugHistoryScrollMetrics export failed")
        }
    }
}

/// 仅用于显式启用的逐行诊断；缓冲与帧样本、分页事件分别计数。
/// 同步 MainActor scope 可嵌套，不跨 await，也不保存原始会话或消息 ID。
@MainActor
final class DebugHistoryRowMeasurements {
    struct Measurement: Codable, Sendable {
        let startedElapsedSeconds: Double
        let durationMs: Double
        let rowHash: String
        let kind: String
        let widthPoints: Double
        let source: String
        let stage: String
        let loadOrdinal: Int?
    }

    @MainActor
    final class Scope {
        private weak var recorder: DebugHistoryRowMeasurements?
        private let previous: Scope?
        private let startedAt: CFTimeInterval
        private let reportStartedAt: CFTimeInterval
        private let rowHash: String
        private let kind: String
        private let widthPoints: Double
        private let source: String
        private let loadOrdinal: Int?
        private var finished = false

        fileprivate init(recorder: DebugHistoryRowMeasurements, rowID: String, kind: String,
                         widthPoints: Double, source: String, reportStartedAt: CFTimeInterval,
                         loadOrdinal: Int?) {
            self.recorder = recorder
            previous = DebugHistoryRowMeasurements.activeScope
            // Swift 的进程随机 hash 不提供跨启动关联，也不保留原 ID。
            rowHash = String(UInt(bitPattern: rowID.hashValue), radix: 16)
            self.kind = kind
            self.widthPoints = widthPoints
            self.source = source
            self.reportStartedAt = reportStartedAt
            self.loadOrdinal = loadOrdinal
            startedAt = CACurrentMediaTime()
            DebugHistoryRowMeasurements.activeScope = self
        }

        func startStage() -> CFTimeInterval { CACurrentMediaTime() }

        func finishStage(_ stage: String, startedAt: CFTimeInterval?) {
            guard !finished, let startedAt else { return }
            record(stage: stage, startedAt: startedAt)
        }

        func finish() {
            guard !finished else { return }
            record(stage: "total", startedAt: startedAt)
            finished = true
            DebugHistoryRowMeasurements.activeScope = previous
        }

        private func record(stage: String, startedAt: CFTimeInterval) {
            let duration = (CACurrentMediaTime() - startedAt) * 1_000
            guard duration.isFinite, duration >= 0 else { return }
            recorder?.append(Measurement(startedElapsedSeconds: startedAt - reportStartedAt,
                                         durationMs: duration, rowHash: rowHash, kind: kind,
                                         widthPoints: widthPoints, source: source,
                                         stage: stage, loadOrdinal: loadOrdinal))
        }
    }

    private(set) static var activeScope: Scope?
    private(set) var measurements: [Measurement] = []
    private(set) var limitReached = false
    private let enabled: Bool
    private let countLimit: Int
    private var sealed = false

    static func makeIfEnabled() -> DebugHistoryRowMeasurements? {
        ProcessInfo.processInfo.environment["DSH_HISTORY_ROW_METRICS"] == "1" ? .init(enabled: true) : nil
    }

    init(enabled: Bool, countLimit: Int = 2_048) {
        self.enabled = enabled
        self.countLimit = max(0, countLimit)
    }

    func begin(rowID: String, kind: String, widthPoints: Double, source: String,
               reportStartedAt: CFTimeInterval, loadOrdinal: Int?) -> Scope? {
        guard enabled, !sealed, widthPoints.isFinite, widthPoints > 0 else { return nil }
        guard measurements.count < countLimit else { limitReached = true; return nil }
        return Scope(recorder: self, rowID: rowID, kind: kind, widthPoints: widthPoints,
                     source: source, reportStartedAt: reportStartedAt, loadOrdinal: loadOrdinal)
    }

    fileprivate func append(_ measurement: Measurement) {
        guard !sealed else { return }
        guard measurements.count < countLimit else { limitReached = true; return }
        measurements.append(measurement)
    }

    func seal() -> [Measurement] {
        sealed = true
        return measurements
    }
}
#endif
