import Foundation

struct SessionListState: Equatable {
    var sessions: [SessionSummary] = []
    var archivedSessionIDs: Set<String> = []
    var selectedSessionID: String?
}

enum SessionListAction {
    case select(String?)
    case setArchivedSessionIDs(Set<String>)
    case remoteSessionsReceived([GatewaySessionSummary])
    case messageSent(sessionID: String, agentPreset: String?)
    case knownSessionAdded(String)
    case eventReceived(SessionEvent)
    case markRead(String)
}

enum SessionListReducer {
    static func reduce(state: inout SessionListState, action: SessionListAction) {
        switch action {
        case .select(let sessionID):
            state.selectedSessionID = sessionID
        case .setArchivedSessionIDs(let sessionIDs):
            state.archivedSessionIDs = sessionIDs
        case .remoteSessionsReceived(let remote):
            mergeRemoteSessions(remote, into: &state)
        case .messageSent(let sessionID, let agentPreset):
            if state.selectedSessionID == nil { state.selectedSessionID = sessionID }
            upsertSession(in: &state.sessions, id: sessionID, title: L10n.newSessionPlaceholderTitle)
            if let index = state.sessions.firstIndex(where: { $0.id == sessionID }) {
                state.sessions[index].agentPreset = agentPreset
            }
        case .knownSessionAdded(let sessionID):
            upsertSession(in: &state.sessions, id: sessionID, title: L10n.remoteSessionTitle(sessionID.prefix(8)))
        case .eventReceived(let record):
            applyEvent(record, to: &state)
        case .markRead(let sessionID):
            guard let index = state.sessions.firstIndex(where: { $0.id == sessionID }) else { return }
            state.sessions[index].hasUnread = false
        }
    }

    private static func mergeRemoteSessions(_ remote: [GatewaySessionSummary], into state: inout SessionListState) {
        for item in remote where !state.archivedSessionIDs.contains(item.sessionId) {
            let fallback = item.cwd.map { URL(fileURLWithPath: $0).lastPathComponent }.flatMap { $0.isEmpty ? nil : $0 }
            let title = item.projectedTitle ?? fallback ?? L10n.remoteSessionTitle(item.sessionId.prefix(8))
            let timestamp = item.updatedAt > 10_000_000_000 ? item.updatedAt / 1_000 : item.updatedAt
            let date = Date(timeIntervalSince1970: timestamp)
            if let index = state.sessions.firstIndex(where: { $0.id == item.sessionId }) {
                state.sessions[index].title = title
                state.sessions[index].lastActivity = date
                state.sessions[index].isRunning = item.running
                state.sessions[index].agentPreset = item.agentPreset ?? state.sessions[index].agentPreset
            } else {
                state.sessions.append(SessionSummary(
                    id: item.sessionId,
                    title: item.blank ? L10n.blankSessionTitle(item.sessionId.prefix(8)) : title,
                    lastActivity: date,
                    isRunning: item.running,
                    hasUnread: false,
                    agentPreset: item.agentPreset
                ))
            }
        }
        state.sessions.removeAll { state.archivedSessionIDs.contains($0.id) }
        state.sessions.sort { $0.lastActivity > $1.lastActivity }
    }

    private static func applyEvent(_ record: SessionEvent, to state: inout SessionListState) {
        let event = record.event
        let title = event.type == "user/message"
            ? event.text.map { String($0.prefix(28)) }
            : (event.type == "session/title" ? event.text : nil)
        upsertSession(in: &state.sessions, id: record.sessionId, title: title)

        let updatesMetadata = event.type == "user/message"
            || event.type == "session/title"
            || event.type == "turn/start"
            || event.type == "turn/end"
            || event.type == "assistant/message"
        guard updatesMetadata,
              let index = state.sessions.firstIndex(where: { $0.id == record.sessionId }) else { return }
        state.sessions[index].lastActivity = record.date
        if event.type == "turn/start" { state.sessions[index].isRunning = true }
        if event.type == "turn/end" { state.sessions[index].isRunning = false }
        if state.selectedSessionID != record.sessionId { state.sessions[index].hasUnread = true }
        state.sessions.sort { $0.lastActivity > $1.lastActivity }
    }

    private static func upsertSession(in sessions: inout [SessionSummary], id: String, title: String?) {
        if let index = sessions.firstIndex(where: { $0.id == id }) {
            if let title,
               !title.isEmpty,
               (sessions[index].title == L10n.newSessionPlaceholderTitle
                    || sessions[index].title.hasPrefix(L10n.remoteSessionTitlePrefix)) {
                sessions[index].title = title
            }
        } else {
            sessions.insert(SessionSummary(
                id: id,
                title: title ?? L10n.remoteSessionTitle(id.prefix(8)),
                lastActivity: .now,
                isRunning: false,
                hasUnread: false
            ), at: 0)
        }
    }
}
