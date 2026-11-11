import Foundation
import DeepSeekHarnessShared
import WebRTC
import OSLog

enum GatewayRtcLog {
    private static let logger = LocalLogger(subsystem: "ai.dsh.mobile.ios", category: "gateway-rtc")
    static func write(_ message: String) { logger.notice("\(message)") }
}

/// MGW1 的整数头在 Swift 中直接读取，避免 NSNumber/Double 损失 64 位序号。
final class GatewayMgwCodec {
    enum Failure: Error { case invalidFrame, tooLarge, closed }
    private let channel: UInt8
    private var sendSequence: UInt64 = 1
    private var receiveSequence: UInt64 = 1
    private var nextChunk: UInt64 = 0
    private var chunkCount: UInt64 = 0
    private var totalBytes: UInt64 = 0
    private var partial = Data()
    private var touchedAt: TimeInterval = 0
    var hasPartial: Bool { nextChunk != 0 }
    init(channel: UInt8) { self.channel = channel }

    func encode(_ text: String, frameLimit: Int, messageLimit: Int) throws -> [Data] {
        let bytes = Data(text.utf8)
        guard !bytes.isEmpty, bytes.count <= messageLimit else { throw Failure.tooLarge }
        guard (33...16384).contains(frameLimit), sendSequence <= UInt64(Int64.max) else { throw Failure.invalidFrame }
        let size = frameLimit - 32
        let count = (bytes.count + size - 1) / size
        var result: [Data] = []
        for index in 0..<count {
            let start = index * size
            let length = min(size, bytes.count - start)
            var frame = Data([77, 71, 87, 49, 1, 0, channel, 0])
            func append(_ value: UInt64, _ width: Int) {
                for offset in (0..<width).reversed() { frame.append(UInt8(truncatingIfNeeded: value >> (offset * 8))) }
            }
            append(sendSequence, 8); append(UInt64(index), 4); append(UInt64(count), 4)
            append(UInt64(bytes.count), 4); append(UInt64(length), 4)
            frame.append(bytes[start..<(start + length)])
            result.append(frame)
        }
        sendSequence += 1
        return result
    }
    func receive(_ input: Data, now: TimeInterval = ProcessInfo.processInfo.systemUptime) throws -> String? {
        try checkIdle(now: now)
        let frame = [UInt8](input)
        guard (33...16384).contains(frame.count), Array(frame[0..<8]) == [77, 71, 87, 49, 1, 0, channel, 0] else { throw Failure.invalidFrame }
        func integer(_ offset: Int, _ width: Int) -> UInt64 {
            frame[offset..<(offset + width)].reduce(0) { ($0 << 8) | UInt64($1) }
        }
        let sequence = integer(8, 8), index = integer(16, 4), count = integer(20, 4)
        let total = integer(24, 4), length = integer(28, 4)
        guard sequence == receiveSequence, sequence <= UInt64(Int64.max), index == nextChunk,
              (1...16777216).contains(total), count > 0, count <= total, index < count,
              length == frame.count - 32 else { throw Failure.invalidFrame }
        if nextChunk == 0 { chunkCount = count; totalBytes = total; partial.reserveCapacity(Int(total)) }
        guard chunkCount == count, totalBytes == total, partial.count + Int(length) <= Int(total) else { throw Failure.invalidFrame }
        partial.append(contentsOf: frame[32...]); nextChunk += 1; touchedAt = now
        guard nextChunk == count else {
            guard partial.count < Int(total) else { throw Failure.invalidFrame }
            return nil
        }
        guard partial.count == Int(total), let text = String(data: partial, encoding: .utf8) else { throw Failure.invalidFrame }
        partial = Data(); nextChunk = 0; receiveSequence += 1
        return text
    }
    func checkIdle(now: TimeInterval = ProcessInfo.processInfo.systemUptime) throws {
        if hasPartial && now - touchedAt >= 30 { throw Failure.invalidFrame }
    }
}

private struct RtcNativeEvent {
    let channel: String
    let kind: String
    var text = ""
    var data: Data? = nil
    var weight: Int { (data?.count ?? text.utf8.count) + 256 }
}

/// 原生回调线程到 MainActor 的有界队列；避免每个分片创建一个无限排队的 Task。
private final class RtcNativeInbox: @unchecked Sendable {
    let stream: AsyncStream<RtcNativeEvent>
    private let continuation: AsyncStream<RtcNativeEvent>.Continuation
    private let lock = NSLock()
    private var bytes = 0
    private var ended = false
    init() {
        var captured: AsyncStream<RtcNativeEvent>.Continuation!
        stream = AsyncStream(bufferingPolicy: .bufferingOldest(512)) { captured = $0 }
        continuation = captured
    }
    func offer(_ event: RtcNativeEvent) {
        lock.lock(); defer { lock.unlock() }
        guard !ended else { return }
        bytes += event.weight
        if bytes > 4 * 1024 * 1024 { ended = true; continuation.finish(); return }
        if case .dropped = continuation.yield(event) { ended = true; continuation.finish() }
    }
    func consumed(_ event: RtcNativeEvent) { lock.lock(); bytes -= event.weight; lock.unlock() }
    func close() { lock.lock(); ended = true; continuation.finish(); lock.unlock() }
}

private final class IOSRtcPeer: NSObject, RTCPeerConnectionDelegate, RTCDataChannelDelegate {
    private static let factory = RTCPeerConnectionFactory()
    let codec: GatewayMgwCodec
    private let name: String
    private let inbox: RtcNativeInbox
    private var pc: RTCPeerConnection!
    private var dc: RTCDataChannel!
    private let lock = NSRecursiveLock()
    private var closed = false
    private var localReady = false
    private var remoteReady = false
    private var localCandidates: [RTCIceCandidate] = []
    private var remoteCandidates: [RTCIceCandidate] = []
    private var gatheringDone = false
    private var statsPending = false

    func requestPathStats() {
        lock.lock()
        guard !closed, !statsPending else { lock.unlock(); return }
        statsPending = true
        let peer = pc!
        lock.unlock()
        peer.statistics { [weak self] report in
            guard let self else { return }
            self.lock.lock(); defer { self.lock.unlock() }
            self.statsPending = false
            guard !self.closed else { return }
            let types = ["transport", "candidate-pair", "local-candidate", "remote-candidate"]
            let keys = ["selectedCandidatePairId", "localCandidateId", "remoteCandidateId", "state", "selected",
                        "candidateType", "address", "ip", "port", "protocol"]
            let values: [[String: Any]] = report.statistics.values.filter { types.contains($0.type) }.map { stat in
                var value: [String: Any] = ["id": stat.id, "type": stat.type]
                for key in keys { value[key] = stat.values[key] }
                return value
            }
            if let data = try? JSONSerialization.data(withJSONObject: values),
               let text = String(data: data, encoding: .utf8) { self.emit("path-stats", text: text) }
        }
    }

    init(name: String, iceJSON: String, inbox: RtcNativeInbox) throws {
        self.name = name; self.inbox = inbox
        codec = GatewayMgwCodec(channel: name == "control" ? 1 : 2)
        super.init()
        let configuration = RTCConfiguration()
        configuration.sdpSemantics = .unifiedPlan
        configuration.continualGatheringPolicy = .gatherOnce
        let servers = try JSONSerialization.jsonObject(with: Data(iceJSON.utf8)) as? [[String: Any]] ?? []
        configuration.iceServers = servers.map { item in
            RTCIceServer(urlStrings: item["urls"] as? [String] ?? (item["urls"] as? String).map { [$0] } ?? [])
        }
        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        guard let peer = Self.factory.peerConnection(with: configuration, constraints: constraints, delegate: self) else {
            throw GatewayMgwCodec.Failure.closed
        }
        pc = peer
        let dataConfiguration = RTCDataChannelConfiguration()
        dataConfiguration.isOrdered = true
        guard let data = peer.dataChannel(forLabel: "dsh-mobile-\(name)-v1", configuration: dataConfiguration) else {
            peer.close(); throw GatewayMgwCodec.Failure.closed
        }
        dc = data; data.delegate = self
    }
    func start() {
        pc.offer(for: RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)) { [weak self] sdp, error in
            guard let self else { return }
            guard let sdp, error == nil else { self.emit("unavailable", text: "sdp-offer-failed"); return }
            self.pc.setLocalDescription(sdp) { [weak self] error in
                guard let self else { return }
                self.lock.lock(); defer { self.lock.unlock() }
                guard error == nil else { self.emit("security", text: "sdp-set-failed"); return }
                self.emit("offer", text: sdp.sdp); self.localReady = true
                for candidate in self.localCandidates { self.emitCandidate(candidate) }
                self.localCandidates.removeAll()
                if self.gatheringDone { self.emit("end-of-candidates") }
            }
        }
    }
    func signal(_ text: String) throws {
        let frame = try JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any] ?? [:]
        if frame["signalType"] as? String == "answer", let sdp = frame["sdp"] as? String {
            pc.setRemoteDescription(RTCSessionDescription(type: .answer, sdp: sdp)) { [weak self] error in
                guard let self else { return }
                self.lock.lock(); defer { self.lock.unlock() }
                guard error == nil else { self.emit("security", text: "sdp-set-failed"); return }
                self.remoteReady = true
                for candidate in self.remoteCandidates { self.add(candidate) }
                self.remoteCandidates.removeAll()
            }
        } else if frame["signalType"] as? String == "candidate", let value = frame["candidate"] as? [String: Any],
                  let sdp = value["candidate"] as? String {
            let candidate = RTCIceCandidate(sdp: sdp, sdpMLineIndex: (value["sdpMLineIndex"] as? NSNumber)?.int32Value ?? 0,
                                            sdpMid: value["sdpMid"] as? String)
            lock.lock(); defer { lock.unlock() }
            if remoteReady { add(candidate) }
            else if remoteCandidates.count >= 256 { emit("security") } else { remoteCandidates.append(candidate) }
        }
    }
    private func add(_ candidate: RTCIceCandidate) {
        pc.add(candidate) { [weak self] error in if error != nil { self?.emit("security") } }
    }
    func sendText(_ text: String) throws {
        guard !closed, dc.sendData(RTCDataBuffer(data: Data(text.utf8), isBinary: false)) else { throw GatewayMgwCodec.Failure.closed }
    }
    @MainActor func send(_ text: String, frameLimit: Int, messageLimit: Int) async throws {
        let frames = try codec.encode(text, frameLimit: frameLimit, messageLimit: messageLimit)
        let deadline = ProcessInfo.processInfo.systemUptime + 30
        for frame in frames {
            if dc.bufferedAmount > 256 * 1024 {
                while !closed && dc.bufferedAmount > 64 * 1024 {
                    try Task.checkCancellation()
                    guard ProcessInfo.processInfo.systemUptime < deadline else { throw GatewayMgwCodec.Failure.closed }
                    try await Task.sleep(for: .milliseconds(10))
                }
            }
            try Task.checkCancellation()
            guard !closed, dc.sendData(RTCDataBuffer(data: frame, isBinary: true)) else { throw GatewayMgwCodec.Failure.closed }
        }
    }
    func close() {
        lock.lock()
        guard !closed else { lock.unlock(); return }
        closed = true
        localCandidates.removeAll(); remoteCandidates.removeAll()
        lock.unlock()
        // 原生 close 可能等待信令线程，不在回调锁内调用，避免与在途 SDP 完成回调互相等待。
        dc.delegate = nil; pc.delegate = nil; dc.close(); pc.close()
    }
    private func emit(_ kind: String, text: String = "", data: Data? = nil) {
        lock.lock(); defer { lock.unlock() }
        if !closed { inbox.offer(RtcNativeEvent(channel: name, kind: kind, text: text, data: data)) }
    }
    private func emitCandidate(_ candidate: RTCIceCandidate) {
        let value: [String: Any] = ["candidate": candidate.sdp.hasPrefix("a=") ? String(candidate.sdp.dropFirst(2)) : candidate.sdp,
                                  "sdpMid": candidate.sdpMid as Any? ?? NSNull(), "sdpMLineIndex": candidate.sdpMLineIndex]
        guard let data = try? JSONSerialization.data(withJSONObject: value), let text = String(data: data, encoding: .utf8) else { return }
        emit("candidate", text: text)
    }
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) { emit("security") }
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
    func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {
        if newState == .failed { emit("unavailable", text: "ice-failed") }
        if newState == .closed { emit("unavailable", text: "ice-closed") }
    }
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCPeerConnectionState) {
        if newState == .failed { emit("unavailable", text: "connection-lost") }
    }
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {
        if newState == .complete {
            lock.lock(); defer { lock.unlock() }
            gatheringDone = true
            if localReady { emit("end-of-candidates") }
        }
    }
    func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
        lock.lock(); defer { lock.unlock() }
        if localReady { emitCandidate(candidate) }
        else if localCandidates.count >= 256 { emit("security") } else { localCandidates.append(candidate) }
    }
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) { dataChannel.close(); emit("security") }
    func dataChannelDidChangeState(_ dataChannel: RTCDataChannel) {
        if dataChannel.readyState == .open { emit("open") }
        if dataChannel.readyState == .closed { emit("unavailable", text: "datachannel-closed") }
    }
    func dataChannel(_ dataChannel: RTCDataChannel, didReceiveMessageWith buffer: RTCDataBuffer) {
        guard !buffer.data.isEmpty, buffer.data.count <= 16384 else { emit("security"); return }
        emit(buffer.isBinary ? "binary" : "text", data: buffer.data)
    }
}

@MainActor
protocol GatewayRtcConnection: AnyObject {
    var isRtc: Bool { get }
    var maximumMessageBytes: Int { get }
    var elapsedMilliseconds: Int64 { get }
    func webSocket(_ data: Data) async throws
    func send(_ text: String, channel: String) async throws
    func checkHealth(timeout: Duration) async throws
    func close()
}

/// 必须确认两条业务通道的关联 Pong；信令 WebSocket 在线不能证明 RTC 在线。
@MainActor
final class GatewayRtcHealthProbe {
    enum Failure: Error { case timeout(channels: [String]) }
    private var pendingID: String?
    private var channels: Set<String> = []
    private var continuation: CheckedContinuation<Void, Error>?
    private var tasks: [Task<Void, Never>] = []

    func check(timeout: Duration, send: @escaping (String, String) async throws -> Void) async throws {
        // 新一轮探活取代已取消的旧轮次；旧取消回调仍必须匹配自己的 ID。
        cancel()
        let id = "rtc-resume:" + UUID().uuidString
        try await withTaskCancellationHandler {
            try Task.checkCancellation()
            try await withCheckedThrowingContinuation { continuation in
                pendingID = id
                channels = ["control", "conversation"]
                self.continuation = continuation
                tasks.append(Task { [weak self] in
                    do { try await Task.sleep(for: timeout) } catch { return }
                    guard let self else { return }
                    self.finish(id: id, result: .failure(Failure.timeout(channels: self.channels.sorted())))
                })
                for channel in ["control", "conversation"] {
                    tasks.append(Task { [weak self] in
                        do {
                            let data = try JSONSerialization.data(withJSONObject: ["type": "ping", "requestId": id + ":" + channel])
                            try await send(String(decoding: data, as: UTF8.self), channel)
                        } catch { self?.finish(id: id, result: .failure(error)) }
                    })
                }
            }
        } onCancel: {
            Task { @MainActor [weak self] in self?.finish(id: id, result: .failure(CancellationError())) }
        }
    }

    /// 只消费保留前缀的探活响应，迟到或其他代际的 Pong 不得确认新连接。
    func receive(_ text: String, channel: String) -> Bool {
        guard text.utf8.count <= 1024,
              let frame = try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any],
              frame["kind"] as? String == "pong", let requestID = frame["requestId"] as? String,
              requestID.hasPrefix("rtc-resume:") else { return false }
        if let id = pendingID, requestID == id + ":" + channel, channels.remove(channel) != nil, channels.isEmpty {
            finish(id: id, result: .success(()))
        }
        return true
    }

    func cancel() {
        if let id = pendingID { finish(id: id, result: .failure(CancellationError())) }
    }

    private func finish(id: String, result: Result<Void, Error>) {
        guard id == pendingID else { return }
        let completion = continuation
        continuation = nil
        pendingID = nil
        channels.removeAll()
        tasks.forEach { $0.cancel() }
        tasks.removeAll()
        completion?.resume(with: result)
    }
}

@MainActor
final class GatewayRtcLink: GatewayRtcConnection {
    private let startedAt = ProcessInfo.processInfo.systemUptime
    private var lastDiagnostic = ""
    private let diagnostic: (String) -> Void
    private let machine: RtcUpgradeMachine
    private var peers: [String: IOSRtcPeer] = [:]
    private let inbox = RtcNativeInbox()
    private var receiveTask: Task<Void, Never>?
    private var deadlineTask: Task<Void, Never>?
    private var idleTask: Task<Void, Never>?
    private var closed = false
    private var tails: [String: Task<Void, Error>] = [:]
    private var queuedBytes: [String: Int] = [:]
    private var paths: [String: RtcSelectedPath] = [:]
    private let healthProbe = GatewayRtcHealthProbe()
    private let wsSend: (String) async throws -> Void
    private let deliver: (String, Data) async -> Void
    private let selected: (String) -> Void
    private let pathChanged: ([String: RtcSelectedPath]) -> Void
    private let failure: (Bool) -> Void
    var maximumMessageBytes: Int { Int(machine.maxLogicalMessageBytes) }
    var isRtc: Bool { machine.mode == "webrtc" && machine.phase == "active" }
    var elapsedMilliseconds: Int64 { Int64((ProcessInfo.processInfo.systemUptime - startedAt) * 1000) }

    init(expectedGatewayID: String, wsSend: @escaping (String) async throws -> Void,
         deliver: @escaping (String, Data) async -> Void, selected: @escaping (String) -> Void,
         pathChanged: @escaping ([String: RtcSelectedPath]) -> Void = { _ in },
         diagnostic: @escaping (String) -> Void = GatewayRtcLog.write,
         failure: @escaping (Bool) -> Void) {
        machine = RtcUpgradeMachine(expectedGatewayId: expectedGatewayID, nonce: UUID().uuidString)
        self.wsSend = wsSend; self.deliver = deliver; self.selected = selected; self.failure = failure
        self.pathChanged = pathChanged
        self.diagnostic = diagnostic
        trace("upgrade-requested", reason: "none")
        receiveTask = Task { [weak self, inbox] in
            for await event in inbox.stream {
                inbox.consumed(event)
                guard let self, !self.closed else { return }
                if !["negotiating", "selecting-webrtc", "active"].contains(self.machine.phase) { continue }
                do {
                    let actions: [RtcUpgradeAction]
                    switch event.kind {
                    case "path-stats":
                        guard self.isRtc else { continue }
                        let path = RtcPathInspector.shared.inspect(statsJson: event.text)
                        guard self.paths[event.channel] != path else { continue }
                        self.paths[event.channel] = path
                        self.pathChanged(self.paths)
                        continue
                    case "offer", "candidate", "end-of-candidates":
                        actions = self.machine.localSignal(channel: event.channel, signalType: event.kind, payload: event.text)
                    case "open": actions = self.machine.rtcOpened(channel: event.channel)
                    case "text":
                        guard let data = event.data, let text = String(data: data, encoding: .utf8) else { throw GatewayMgwCodec.Failure.invalidFrame }
                        actions = self.machine.rtcText(channel: event.channel, text: text)
                    case "binary":
                        if let data = event.data, let text = try self.peers[event.channel]?.codec.receive(data) {
                            actions = self.machine.rtcMessage(channel: event.channel, text: text)
                        } else { actions = [] }
                    case "unavailable":
                        self.trace("native-failure", reason: event.text.isEmpty ? "native-rtc-unavailable" : event.text, channel: event.channel)
                        actions = self.machine.rtcUnavailable()
                    default:
                        self.trace("native-failure", reason: event.text.isEmpty ? "protocol-validation-failed" : event.text, channel: event.channel)
                        actions = self.machine.securityFailure()
                    }
                    try await self.execute(actions)
                } catch { await self.stopWithFailure(recoverable: false, reason: "frame-handling-failed") }
            }
            if let self, !self.closed { await self.stopWithFailure(recoverable: false, reason: "callback-queue-ended") }
        }
    }
    deinit {
        inbox.close()
        for peer in peers.values { peer.close() }
        receiveTask?.cancel(); deadlineTask?.cancel(); idleTask?.cancel()
        for tail in tails.values { tail.cancel() }
    }

    func webSocket(_ data: Data) async throws {
        guard !closed, let text = String(data: data, encoding: .utf8) else { throw GatewayMgwCodec.Failure.invalidFrame }
        try await execute(machine.webSocket(text: text))
    }
    func send(_ text: String, channel: String) async throws {
        guard !closed, isRtc, let peer = peers[channel] else { throw GatewayMgwCodec.Failure.closed }
        let count = text.utf8.count
        guard count <= machine.maxLogicalMessageBytes else { throw GatewayMgwCodec.Failure.tooLarge }
        guard (queuedBytes[channel] ?? 0) + count <= 16 * 1024 * 1024 else { throw GatewayMgwCodec.Failure.closed }
        queuedBytes[channel, default: 0] += count
        defer { queuedBytes[channel, default: 0] -= count }
        let previous = tails[channel]
        let task = Task { [weak self] in
            try await previous?.value
            guard let self, !self.closed else { throw GatewayMgwCodec.Failure.closed }
            try await peer.send(text, frameLimit: Int(self.machine.maxFrameBytes), messageLimit: Int(self.machine.maxLogicalMessageBytes))
        }
        tails[channel] = task
        try await task.value
    }
    func checkHealth(timeout: Duration) async throws {
        guard !closed, isRtc else { throw GatewayMgwCodec.Failure.closed }
        try await healthProbe.check(timeout: timeout) { [weak self] text, channel in
            guard let self else { throw CancellationError() }
            try await self.send(text, channel: channel)
        }
    }
    private func execute(_ actions: [RtcUpgradeAction]) async throws {
        let key = machine.diagnosticReason + ":" + machine.diagnosticPhase
        if !machine.diagnosticReason.isEmpty && key != lastDiagnostic {
            lastDiagnostic = key
            let event = actions.contains(where: { $0.kind == "security-failure" }) ? "blocked"
                : (actions.contains(where: { $0.kind == "rtc-close" }) ? "fallback-requested" : "upgrade-skipped")
            trace(event, reason: machine.diagnosticReason, phase: machine.diagnosticPhase)
        }
        for action in actions {
            if closed { return }
            switch action.kind {
            case "ws-send":
                do { try await wsSend(action.text) }
                catch { trace("native-failure", reason: "signaling-send-failed"); throw error }
            case "deadline":
                let total = max(0.1, Double(action.text) ?? 12000) / 1000
                deadlineTask = Task { [weak self] in
                    do {
                        try await Task.sleep(for: .seconds(max(0.001, total - 1.5)))
                        guard let self, !self.closed else { return }
                        try await self.execute(self.machine.deadline())
                        try await Task.sleep(for: .seconds(min(total, 1.5)))
                        try await self.execute(self.machine.deadline())
                    } catch is CancellationError { return } catch { await self?.stopWithFailure(recoverable: true, reason: "signaling-send-failed") }
                }
            case "cancel-deadline": deadlineTask?.cancel(); deadlineTask = nil
            case "rtc-start":
                do {
                    let peer = try IOSRtcPeer(name: action.channel, iceJSON: action.text, inbox: inbox)
                    peers[action.channel] = peer; peer.start()
                } catch {
                    trace("native-failure", reason: "runtime-unavailable", channel: action.channel)
                    try await execute(machine.rtcUnavailable()); return
                }
            case "rtc-signal": try peers[action.channel]?.signal(action.text)
            case "rtc-text": try peers[action.channel]?.sendText(action.text)
            case "rtc-send": try await send(action.text, channel: action.channel)
            case "rtc-close": closePeers()
            case "selected":
                trace("selected", reason: machine.diagnosticReason.isEmpty ? "none" : machine.diagnosticReason)
                selected(action.text == "webrtc" ? "WebRTC" : "WebSocket")
                if action.text == "webrtc" {
                    for peer in peers.values { peer.requestPathStats() }
                    idleTask = Task { [weak self] in
                        do {
                            var ticks = 0
                            while !Task.isCancelled {
                                try await Task.sleep(for: .seconds(1))
                                guard let self, !self.closed else { return }
                                for peer in self.peers.values { try peer.codec.checkIdle() }
                                ticks += 1
                                // 只读取原生统计，不发起网络探测；同时捕获切网后的候选对变化。
                                if ticks <= 3 || ticks % 5 == 0 {
                                    for peer in self.peers.values { peer.requestPathStats() }
                                }
                            }
                        } catch is CancellationError { return } catch { await self?.stopWithFailure(recoverable: false, reason: "idle-check-failed") }
                    }
                }
            case "deliver":
                if !healthProbe.receive(action.text, channel: action.channel) {
                    await deliver(action.channel, Data(action.text.utf8))
                }
            case "retry-websocket": await stopWithFailure(recoverable: true)
            case "security-failure": await stopWithFailure(recoverable: false)
            default: break
            }
        }
    }
    private func trace(_ event: String, reason: String, channel: String = "group", phase: String? = nil) {
        diagnostic(RtcDiagnostics.shared.message(event: event, reason: reason, phase: phase ?? machine.phase, channel: channel,
            mode: machine.mode.isEmpty ? "none" : machine.mode,
            elapsedMs: elapsedMilliseconds))
    }
    private func stopWithFailure(recoverable: Bool, reason: String? = nil) async {
        guard !closed else { return }
        if let reason { trace(recoverable ? "fallback-requested" : "blocked", reason: reason) }
        close(); failure(recoverable)
    }
    private func closePeers() {
        healthProbe.cancel()
        for peer in peers.values { peer.close() }
        peers.removeAll(); paths.removeAll(); pathChanged([:]); idleTask?.cancel(); idleTask = nil
    }
    func close() {
        guard !closed else { return }
        closed = true; machine.close(); inbox.close(); closePeers()
        receiveTask?.cancel(); deadlineTask?.cancel()
        for tail in tails.values { tail.cancel() }; tails.removeAll()
    }
}
