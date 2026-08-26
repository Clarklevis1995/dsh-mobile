import Foundation

struct HistoryLoadProgress: Equatable {
    var loaded: Int
    var total: Int?
}

enum HistoryBatchKind: Equatable {
    case latest
    case older
}

struct HistorySessionState: Equatable {
    var hasMore = false
    var isLoading = false
    var isLoadingOlder = false
    var progress: HistoryLoadProgress?
    var seenCursors: Set<Int> = []
    var nextBeforeSequence: Int?
    var pageCount = 0
    var batchKind: HistoryBatchKind?
    var loadedEventCount = 0
    var loadedByteCount = 0
    var syncedActivityTimestamp: Double?
}

struct HistoryState: Equatable {
    var sessions: [String: HistorySessionState] = [:]
    var pendingSessionID: String?
}

enum HistoryResult: Equatable {
    case none
    case requestPage(beforeSequence: Int?)
    case stopped
    case completed(eventCount: Int, byteCount: Int, hasMore: Bool)
    case failed(String)
}

enum HistoryAction {
    case start(
        sessionID: String,
        older: Bool,
        hasLocalEvents: Bool,
        earliestLocalSequence: Int?
    )
    case processingStarted(sessionID: String, rawEventCount: Int, hasMore: Bool)
    case pageCommitted(
        sessionID: String,
        eventCount: Int,
        byteCount: Int,
        hasMore: Bool,
        nextBeforeSequence: Int?,
        earliestLocalSequence: Int?,
        remoteActivityTimestamp: Double?
    )
    case liveEventReceived(sessionID: String, activityTimestamp: Double)
    case timedOut(sessionID: String)
    case cancelled(sessionID: String)
}

enum HistoryReducer {
    static func reduce(state: inout HistoryState, action: HistoryAction) -> HistoryResult {
        switch action {
        case .start(let sessionID, let older, let hasLocalEvents, let earliestLocalSequence):
            var session = state.sessions[sessionID] ?? HistorySessionState()
            guard !session.isLoading else { return .none }
            if older, !session.hasMore { return .none }

            session.isLoading = true
            session.isLoadingOlder = older
            session.progress = HistoryLoadProgress(loaded: 0, total: nil)
            session.seenCursors = []
            session.pageCount = 0
            session.batchKind = older ? .older : .latest
            session.loadedEventCount = 0
            session.loadedByteCount = 0
            if !older, !hasLocalEvents {
                session.hasMore = false
                session.nextBeforeSequence = nil
            }
            let before = older ? (session.nextBeforeSequence ?? earliestLocalSequence) : nil
            if older, before == nil {
                session.hasMore = false
                finishTransientState(&session)
                state.sessions[sessionID] = session
                return .stopped
            }
            if let before { session.seenCursors = [before] }
            state.sessions[sessionID] = session
            state.pendingSessionID = sessionID
            return .requestPage(beforeSequence: before)

        case .processingStarted(let sessionID, let rawEventCount, let hasMore):
            guard var session = state.sessions[sessionID], session.isLoading else { return .none }
            session.progress = HistoryLoadProgress(
                loaded: session.loadedEventCount,
                total: hasMore ? nil : session.loadedEventCount + rawEventCount
            )
            state.sessions[sessionID] = session
            return .none

        case .pageCommitted(
            let sessionID,
            let eventCount,
            let byteCount,
            let hasMore,
            let nextBeforeSequence,
            let earliestLocalSequence,
            let remoteActivityTimestamp
        ):
            guard var session = state.sessions[sessionID], session.isLoading else { return .none }
            session.loadedEventCount += eventCount
            session.loadedByteCount += byteCount
            session.hasMore = hasMore
            session.pageCount += 1
            session.progress = HistoryLoadProgress(
                loaded: session.loadedEventCount,
                total: hasMore ? nil : session.loadedEventCount
            )

            if hasMore, let earliestLocalSequence {
                session.nextBeforeSequence = earliestLocalSequence
            } else if let nextBeforeSequence {
                session.nextBeforeSequence = nextBeforeSequence
            } else if !hasMore {
                session.nextBeforeSequence = nil
            }

            if hasMore, session.pageCount < HistorySyncConfiguration.default.pagesPerBatch {
                guard let cursor = nextBeforeSequence else {
                    return fail(
                        sessionID: sessionID,
                        session: &session,
                        state: &state,
                        message: String(localized: "history.pagination.stopped.hasmore", defaultValue: "网关返回 hasMore:true，但缺少 nextBeforeSeq，已停止自动续页。")
                    )
                }
                guard !session.seenCursors.contains(cursor) else {
                    return fail(
                        sessionID: sessionID,
                        session: &session,
                        state: &state,
                        message: String(localized: "history.cursor.loop", defaultValue: "网关重复返回历史游标 \(cursor)，已停止自动续页以避免循环。")
                    )
                }
                session.seenCursors.insert(cursor)
                state.sessions[sessionID] = session
                return .requestPage(beforeSequence: cursor)
            }

            if session.batchKind == .latest, let remoteActivityTimestamp {
                session.syncedActivityTimestamp = remoteActivityTimestamp
            }
            let result = HistoryResult.completed(
                eventCount: session.loadedEventCount,
                byteCount: session.loadedByteCount,
                hasMore: session.hasMore
            )
            finishTransientState(&session)
            state.sessions[sessionID] = session
            if state.pendingSessionID == sessionID { state.pendingSessionID = nil }
            return result

        case .liveEventReceived(let sessionID, let activityTimestamp):
            guard var session = state.sessions[sessionID], let synced = session.syncedActivityTimestamp else {
                return .none
            }
            session.syncedActivityTimestamp = max(synced, activityTimestamp)
            state.sessions[sessionID] = session
            return .none

        case .timedOut(let sessionID), .cancelled(let sessionID):
            guard var session = state.sessions[sessionID] else { return .none }
            finishTransientState(&session)
            state.sessions[sessionID] = session
            if state.pendingSessionID == sessionID { state.pendingSessionID = nil }
            return .stopped
        }
    }

    private static func fail(
        sessionID: String,
        session: inout HistorySessionState,
        state: inout HistoryState,
        message: String
    ) -> HistoryResult {
        session.hasMore = false
        session.nextBeforeSequence = nil
        finishTransientState(&session)
        state.sessions[sessionID] = session
        if state.pendingSessionID == sessionID { state.pendingSessionID = nil }
        return .failed(message)
    }

    private static func finishTransientState(_ session: inout HistorySessionState) {
        session.isLoading = false
        session.isLoadingOlder = false
        session.progress = nil
        session.seenCursors = []
        session.pageCount = 0
        session.batchKind = nil
        session.loadedEventCount = 0
        session.loadedByteCount = 0
    }
}

struct HistoryEventMergeResult: Equatable {
    var replacedOrInsertedOutOfOrder: Bool
}

enum HistoryEventMerger {
    @discardableResult
    static func merge(_ record: SessionEvent, into events: inout [SessionEvent]) -> HistoryEventMergeResult {
        if let lastSequence = events.last?.seq, record.seq > lastSequence {
            events.append(record)
            return HistoryEventMergeResult(replacedOrInsertedOutOfOrder: false)
        }
        var low = 0
        var high = events.count
        while low < high {
            let middle = (low + high) / 2
            if events[middle].seq < record.seq { low = middle + 1 } else { high = middle }
        }
        if low < events.count, events[low].seq == record.seq {
            events[low] = record
        } else {
            events.insert(record, at: low)
        }
        return HistoryEventMergeResult(replacedOrInsertedOutOfOrder: true)
    }
}
