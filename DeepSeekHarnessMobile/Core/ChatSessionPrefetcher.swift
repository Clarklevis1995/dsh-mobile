import Foundation
import OSLog

/// 只调度最新页缓存；不持有前台 HistoryStore，也不创建后台订阅。
@MainActor
final class ChatSessionPrefetcher {
    struct Candidate: Codable, Equatable, Sendable {
        let id: String
        let lastActivityEpochSeconds: Double
        let isRunning: Bool
        let hasUnread: Bool
        let hasConversation: Bool
    }
    private struct Pending {
        let sessionID: String
        let requestID: String
        let ticket: String
        var draining = false
        var foreground = false
        var deferredForeground = false
        var recoveryRequested = false
    }
    private static let logger = LocalLogger(subsystem: "ai.dsh.mobile.ios", category: "chat-prefetch")
    private let rank: ([Candidate], String?) async -> [String]
    private let ticket: (String) async -> String?
    private let accept: (String, String, String) async -> Bool
    private let invalidate: (String, String) async -> Void
    private let request: (String, String) -> Void
    private let foregroundReady: (String) -> Void
    private let reconnectForeground: (String) -> Void
    private let idleDelay: Duration
    private let timeout: Duration
    private var generation = 0
    private var connected = false
    private var stopped = true
    private var ready = false
    private var busy = false
    private var selected: String?
    private var candidates: [Candidate] = []
    private var attempted = Set<String>()
    private var pending: Pending?
    private var work: Task<Void, Never>?
    private var workToken = UUID()
    private var timer: Task<Void, Never>?
    private var writes: [UUID: Task<Void, Never>] = [:]
    private var writeTickets: [UUID: (String, String)] = [:]
    private(set) var requestCount = 0

    init(idleDelay: Duration = .milliseconds(750), timeout: Duration = .seconds(10),
         rank: @escaping ([Candidate], String?) async -> [String],
         ticket: @escaping (String) async -> String?,
         accept: @escaping (String, String, String) async -> Bool,
         invalidate: @escaping (String, String) async -> Void,
         request: @escaping (String, String) -> Void,
         foregroundReady: @escaping (String) -> Void,
         reconnectForeground: @escaping (String) -> Void = { _ in }) {
        self.idleDelay = idleDelay; self.timeout = timeout
        self.rank = rank; self.ticket = ticket; self.accept = accept
        self.invalidate = invalidate; self.request = request; self.foregroundReady = foregroundReady
        self.reconnectForeground = reconnectForeground
    }

    func connectedHello() {
        stop(reason: "connection")
        generation &+= 1
        connected = true; stopped = false; ready = false
        pending = nil; attempted.removeAll(); requestCount = 0
    }

    func update(candidates: [Candidate], selected: String?, busy: Bool, sessionsReceived: Bool = false) {
        if self.candidates != candidates || self.selected != selected { cancelWork() }
        self.candidates = candidates
        self.selected = selected
        self.busy = busy
        ready = ready || sessionsReceived
        if let pending, !candidates.contains(where: { $0.id == pending.sessionID }) {
            drainPending()
        }
        if busy { cancelWork() }
        schedule()
    }

    /// 取消后保留一条旧协议隔离槽。迟到 history 被消化前不向同会话发第二个模糊请求。
    func stop(reason: String) {
        generation &+= 1
        stopped = true
        cancelWork()
        timer?.cancel(); timer = nil
        drainPending()
        for (key, write) in writes {
            write.cancel()
            if let value = writeTickets[key] {
                Task { await invalidate(value.0, value.1) }
            }
        }
        writes.removeAll(); writeTickets.removeAll()
        Self.logger.debug("[ChatPrefetch] event=cancel reason=\(reason) count=\(self.requestCount)")
    }

    /// 返回 true 表示可以发送正常请求；false 表示接管最新页，或等待旧响应消化后重发。
    func shouldSendForeground(sessionID: String, isOlder: Bool) -> Bool {
        cancelWork()
        busy = true
        guard var value = pending, value.sessionID == sessionID else { return true }
        let needsRecovery = (value.draining || isOlder) && !value.recoveryRequested
        if !value.draining && !isOlder {
            value.foreground = true
            timer?.cancel(); timer = nil
            Task { await invalidate(value.sessionID, value.ticket) }
        } else {
            value.draining = true
            value.deferredForeground = true
            value.recoveryRequested = true
        }
        pending = value
        if needsRecovery {
            Self.logger.debug("[ChatPrefetch] event=foreground_reconnect count=\(self.requestCount)")
            reconnectForeground(sessionID)
        }
        Self.logger.debug("[ChatPrefetch] event=foreground_handoff count=\(self.requestCount)")
        return false
    }

    /// 在 AssistantStream 校验和正常 history 路由之前调用；只有精确 session/request 匹配会被截获。
    func consume(_ frame: GatewayFrame) -> Bool {
        guard frame.kind == "history", let id = frame.sessionId else { return false }
        guard let value = pending, value.sessionID == id else {
            // 已结束的带标识预加载不允许落入正常前台路由。
            return frame.requestId?.hasPrefix("chat-prefetch-") == true
        }
        if let requestID = frame.requestId, requestID != value.requestID {
            return requestID.hasPrefix("chat-prefetch-")
        }
        pending = nil
        timer?.cancel(); timer = nil
        if value.deferredForeground { foregroundReady(id) }
        if value.foreground && !value.draining {
            schedule()
            return false
        }
        if !value.draining && connected && !stopped, let events = frame.events, events.count <= 80 {
            let key = UUID()
            let current = generation
            writeTickets[key] = (id, value.ticket)
            writes[key] = Task { [weak self] in
                guard let self else { return }
                defer {
                    writes[key] = nil; writeTickets[key] = nil
                    schedule()
                }
                // 网关允许单个超大事件突破分页预算；序列化在后台，超过预算直接拒绝缓存。
                let json = await Task.detached(priority: .utility) {
                    guard let data = try? JSONEncoder().encode(frame), data.count <= 256 * 1024 else { return Optional<String>.none }
                    return String(data: data, encoding: .utf8)
                }.value
                guard current == generation, !Task.isCancelled, let json else {
                    await invalidate(id, value.ticket)
                    return
                }
                let saved = await accept(id, json, value.ticket)
                if current == generation && !Task.isCancelled {
                    Self.logger.debug("[ChatPrefetch] event=complete saved=\(saved) count=\(self.requestCount)")
                }
            }
        } else {
            Task { await invalidate(id, value.ticket) }
            schedule()
        }
        return true
    }

    func consumeHistoryError(_ frame: GatewayFrame, foregroundHistoryPending: Bool) -> Bool {
        guard frame.kind == "error", frame.requestType == "history", let value = pending else { return false }
        if let requestID = frame.requestId {
            guard requestID == value.requestID else { return false }
        } else if let id = frame.sessionId {
            guard id == value.sessionID else { return false }
        } else if foregroundHistoryPending && !value.foreground { return false }
        pending = nil
        timer?.cancel(); timer = nil
        Task { await invalidate(value.sessionID, value.ticket) }
        if value.deferredForeground && !value.foreground { foregroundReady(value.sessionID) }
        Self.logger.debug("[ChatPrefetch] event=failed count=\(self.requestCount)")
        schedule()
        // 已交接的请求终止，清本地槽但让原错误继续走正常前台处理。
        return !value.foreground
    }

    func foregroundTimedOut(sessionID: String) {
        if pending?.sessionID == sessionID && pending?.foreground == true {
            drainPending()
        }
    }

    private func drainPending() {
        guard var value = pending else { return }
        value.draining = true
        pending = value
        Task { await invalidate(value.sessionID, value.ticket) }
    }

    private func cancelWork() {
        work?.cancel(); work = nil; workToken = UUID()
    }

    func prioritizeSelection(sessionID: String, assistantStream: Bool) {
        cancelWork()
        if assistantStream && pending?.sessionID == sessionID { drainPending() }
    }

    private func schedule() {
        guard connected, !stopped, ready, !busy, pending == nil, work == nil,
              writes.isEmpty, requestCount < 6 else { return }
        let current = generation
        let tokenID = UUID()
        workToken = tokenID
        work = Task { [weak self] in
            guard let self else { return }
            defer { if workToken == tokenID { work = nil } }
            do { try await Task.sleep(for: idleDelay) } catch { return }
            guard !Task.isCancelled, current == generation, !busy, !stopped else { return }
            let remaining = candidates.filter { $0.hasConversation && $0.id != self.selected && !self.attempted.contains($0.id) }
            let ranked = await rank(remaining, selected)
            guard !Task.isCancelled, current == generation, !busy, !stopped else { return }
            guard let id = ranked.first(where: { candidate in remaining.contains { $0.id == candidate } }) else {
                work = nil
                return
            }
            guard let token = await ticket(id) else { work = nil; return }
            guard !Task.isCancelled, current == generation, !busy, !stopped, selected != id,
                  candidates.contains(where: { $0.id == id && $0.hasConversation }) else {
                await invalidate(id, token)
                return
            }
            attempted.insert(id)
            requestCount += 1
            let requestID = "chat-prefetch-" + UUID().uuidString
            pending = Pending(sessionID: id, requestID: requestID, ticket: token)
            work = nil
            Self.logger.debug("[ChatPrefetch] event=request count=\(self.requestCount)")
            request(id, requestID)
            timer = Task { [weak self] in
                guard let self else { return }
                do { try await Task.sleep(for: timeout) } catch { return }
                guard current == generation, pending?.requestID == requestID else { return }
                // 无 requestId 的旧协议超时后无法安全重试；本轮停止，等待响应或下次连接。
                stop(reason: "timeout")
            }
        }
    }
}
