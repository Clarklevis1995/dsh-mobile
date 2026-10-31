import AVFoundation
import Combine
import DeepSeekHarnessShared
import Foundation
import UIKit
import OSLog

/// 平台层只执行共享 Store 的 effect；不自行判定 ASR 状态或拼接草稿。
@MainActor
final class VoiceInputAdapter: ObservableObject {
    private let shared: SharedVoiceInputStore
    @Published private(set) var toastMessage: String?
    private var toastDismissal: Task<Void, Never>?
    @Published private(set) var state: SharedVoiceInputSnapshot
    var onDraftUpdate: ((String) -> Void)?
    private weak var gateway: GatewayClient?
    private var connectionObservation: AnyCancellable?
    private var ticker: Task<Void, Never>?
    private var activityObservations = Set<AnyCancellable>()
    private let recorder: any VoiceAudioCapturing
    // AVAudioSession 激活和 AVAudioEngine 启停会阻塞；硬件操作串行执行，不能占用 UI 线程。
    private let recorderQueue = DispatchQueue(label: "ai.dsh.mobile.voice-capture", qos: .userInitiated)
    private let permissionGranted: () -> Bool
    private let captureRequests = VoiceCaptureRequestGate()
    private let requestPermission: (@escaping @Sendable (Bool) -> Void) -> Void
    private let startFeedback = UIImpactFeedbackGenerator(style: .soft)
    private let cancelFeedback = UISelectionFeedbackGenerator()
    private let releaseFeedback = UIImpactFeedbackGenerator(style: .light)

    init(
        shared: SharedVoiceInputStore = SharedVoiceInputStore(),
        recorder: any VoiceAudioCapturing = IOSVoiceRecorder(),
        permissionGranted: @escaping () -> Bool = {
            if #available(iOS 17.0, *) {
                return AVAudioApplication.shared.recordPermission == .granted
            }
            return AVAudioSession.sharedInstance().recordPermission == .granted
        },
        requestPermission: @escaping (@escaping @Sendable (Bool) -> Void) -> Void = { completion in
            if #available(iOS 17.0, *) {
                AVAudioApplication.requestRecordPermission { completion($0) }
            } else {
                AVAudioSession.sharedInstance().requestRecordPermission { granted in
                    completion(granted)
                }
            }
        }
    ) {
        self.permissionGranted = permissionGranted
        self.recorder = recorder
        self.requestPermission = requestPermission
        self.shared = shared
        state = shared.snapshot()
        NotificationCenter.default.publisher(for: UIApplication.didEnterBackgroundNotification).sink { [weak self] _ in
            Task { @MainActor in guard let self else { return }; self.apply(self.shared.suspendCapture()) }
        }.store(in: &activityObservations)
        NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification).sink { [weak self] _ in
            Task { @MainActor in guard let self else { return }; self.apply(self.shared.resumeCapture()) }
        }.store(in: &activityObservations)
    }
    private var now: Int64 { Int64(ProcessInfo.processInfo.systemUptime * 1000) }
    func bind(_ gateway: GatewayClient) {
        self.gateway = gateway
        gateway.onSpeechFrame = { [weak self] json in self?.accept(json) }
        connectionObservation = gateway.$state.sink { [weak self] connection in
            guard let self, !connection.isConnected else { return }
            self.apply(self.shared.connectionChanged(connected: false, supported: false))
        }
    }
    func hello(_ capabilities: [String]) {
        apply(shared.connectionChanged(connected: true, supported: capabilities.contains("speech-input-v1")))
    }
    func open(context: String, draft: String) {
        apply(shared.open(context: context, currentDraft: draft))
        apply(shared.connectionChanged(connected: state.connected, supported: state.supported))
    }
    func draftChanged(_ text: String) { apply(shared.draftChanged(value: text)) }
    func keyboardChanged(_ visible: Bool) { apply(shared.keyboardChanged(visible: visible)) }
    func setVoiceMode(_ enabled: Bool) { apply(shared.setVoiceMode(enabled: enabled)) }
    func touchDown() {
        startFeedback.prepare()
        apply(shared.touchDown(nowMilliseconds: now, microphoneGranted: permissionGranted()))
    }
    func touchFinished() { apply(shared.touchFinished()) }
    func press() { traceVoiceInputLatency("gesture-dispatched"); apply(shared.press(nowMilliseconds: now)) }
    func move(_ distance: CGFloat) { apply(shared.pointerMoved(verticalDistance: Float(distance))) }
    func release() { apply(shared.release(nowMilliseconds: now)) }
    func cancel() { apply(shared.cancel()) }
    func leave() { apply(shared.leave()); toastDismissal?.cancel(); toastMessage = nil; onDraftUpdate = nil }
    func prepare() { apply(shared.prepare()) }
    func insertPending() { apply(shared.insertPending()) }
    func dismissAlert() { apply(shared.dismissAlert()) }
    func dismissFeedback() { apply(shared.dismissFeedback()) }
    func accept(_ json: String) { apply(shared.acceptJson(json: json)) }

    private func apply(_ transition: SharedVoiceInputTransition) {
        // 等值 snapshot 不重新发布，避免转写等待期的时钟重绘整个对话页面。
        let previousPhase = state.phase
        captureRequests.setVoiceMode(transition.snapshot.voiceMode && !transition.snapshot.keyboardVisible)
        if state != transition.snapshot { state = transition.snapshot }
        if previousPhase != state.phase { traceVoiceInputLatency("state-\(state.phase)") }
        if let text = transition.draftUpdate { onDraftUpdate?(text) }
        for request in transition.requests { gateway?.sendRequestPayload(request.payload) }
        for effect in transition.effects {
            switch effect.action {
            case "hapticStart":
                traceVoiceHaptic("hapticStart")
                startFeedback.impactOccurred(intensity: 0.8)
                cancelFeedback.prepare()
                releaseFeedback.prepare()
            case "hapticCancel":
                traceVoiceHaptic("hapticCancel")
                cancelFeedback.selectionChanged()
                cancelFeedback.prepare()
            case "hapticRelease":
                traceVoiceHaptic("hapticRelease")
                releaseFeedback.impactOccurred(intensity: 0.6)
            case "showToast":
                toastDismissal?.cancel()
                toastMessage = effect.message
                toastDismissal = Task { [weak self] in
                    do { try await Task.sleep(for: .seconds(2)) } catch { return }
                    self?.toastMessage = nil
                }
            case "warmCapture":
                if state.voiceMode && !state.keyboardVisible && permissionGranted() {
                    let recorder = self.recorder
                    let captureRequests = self.captureRequests
                    recorderQueue.async {
                        guard captureRequests.allowsWarmup else { return }
                        try? recorder.prepare()
                    }
                }
            case "releaseCapture":
                captureRequests.invalidate()
                let recorder = self.recorder
                recorderQueue.async { recorder.release() }
            case "permission":
                let token = effect.recordingId
                traceVoiceInputLatency("permission-request")
                if permissionGranted() {
                    apply(shared.permissionResult(id: token, granted: true, nowMilliseconds: now))
                    continue
                }
                requestPermission { [weak self] granted in
                    traceVoiceInputLatency("permission-result")
                    Task { @MainActor in
                        guard let self else { return }
                        self.apply(self.shared.permissionResult(id: token, granted: granted, nowMilliseconds: self.now))
                    }
                }
            case "start":
                let token = effect.recordingId
                let recorder = self.recorder
                let captureRequests = self.captureRequests
                captureRequests.schedule(token)
                recorderQueue.async { [weak self] in
                    guard captureRequests.contains(token) else { return }
                    let startedAt = ProcessInfo.processInfo.systemUptime
                    traceVoiceInputLatency("capture-start-begin")
                    do {
                        try recorder.start(id: token, ready: { [weak self] in
                            Task { @MainActor in
                                guard let self else { return }
                                self.apply(self.shared.captureStarted(id: token, nowMilliseconds: self.now))
                            }
                        }, level: { [weak self] rms in
                            Task { @MainActor in
                                guard let self else { return }
                                self.apply(self.shared.level(id: token, rms: rms, nowMilliseconds: self.now))
                            }
                        }, failure: { [weak self] in
                            Task { @MainActor in
                                guard let self else { return }
                                self.apply(self.shared.captureFailed(id: token, message: "麦克风录音失败，请重试"))
                            }
                        })
                        traceVoiceInputLatency("capture-start-end", since: startedAt)
                    } catch {
                        Task { @MainActor in
                            guard let self else { return }
                            self.apply(self.shared.captureFailed(id: token, message: "无法开启麦克风，请检查权限或通话状态"))
                        }
                    }
                }
            case "stop":
                let token = effect.recordingId
                let discard = effect.discard
                if discard { captureRequests.cancel(token) }
                let recorder = self.recorder
                let delay = discard ? 0 : Int(effect.delayMilliseconds)
                recorderQueue.asyncAfter(deadline: .now() + .milliseconds(delay)) { [weak self] in
                    do {
                        guard let pcm = try recorder.stop(id: token, discard: discard) else { return }
                        let encoded = pcm.base64EncodedString()
                        Task { @MainActor in
                            guard let self else { return }
                            self.apply(self.shared.captureFinished(id: token, pcmBase64: encoded, nowMilliseconds: self.now))
                        }
                    } catch {
                        guard !discard else { return }
                        Task { @MainActor in
                            guard let self else { return }
                            self.apply(self.shared.captureFailed(id: token, message: "无法完成录音数据转换，请重试"))
                        }
                    }
                }
            case "startTicker":
                ticker?.cancel()
                ticker = Task { [weak self] in
                    while !Task.isCancelled {
                        do { try await Task.sleep(for: .milliseconds(100)) } catch { return }
                        guard let self else { return }
                        self.apply(self.shared.tick(nowMilliseconds: self.now))
                    }
                }
            case "stopTicker": ticker?.cancel(); ticker = nil
            default: break
            }
        }
    }
}

/// 平台硬件命令令牌：短按撤销无需等待串行队列中的预热或旧启动完成。
private final class VoiceCaptureRequestGate: @unchecked Sendable {
    private let lock = NSLock()
    private var token: String?
    private var voiceModeEnabled = false
    var allowsWarmup: Bool { lock.lock(); defer { lock.unlock() }; return voiceModeEnabled }
    func setVoiceMode(_ enabled: Bool) { lock.lock(); voiceModeEnabled = enabled; lock.unlock() }
    func schedule(_ token: String) { lock.lock(); self.token = token; lock.unlock() }
    func contains(_ token: String) -> Bool { lock.lock(); defer { lock.unlock() }; return self.token == token }
    func cancel(_ token: String) { lock.lock(); if self.token == token { self.token = nil }; lock.unlock() }
    func invalidate() { lock.lock(); token = nil; lock.unlock() }
}

/// 可替换的硬件边界；ready 表示引擎已启动音频流，不等待 PCM 批次交付或格式转换。
/// 调用方保证 start/stop 只在同一串行队列执行。
protocol VoiceAudioCapturing: AnyObject, Sendable {
    func start(id: String, ready: @escaping @Sendable () -> Void, level: @escaping @Sendable (Float) -> Void, failure: @escaping @Sendable () -> Void) throws
    func stop(id: String, discard: Bool) throws -> Data?
    func prepare() throws
    func release()
}

extension VoiceAudioCapturing {
    func prepare() throws {}
    func release() {}
}

/// 唯一音频采集器：把设备格式转换为 16 kHz 单声道 PCM16；WAV 封装在 KMM。
/// 引擎和 capture 由 adapter 的串行队列隔离；音频 tap 的 bytes 由 Capture.lock 保护。
final class IOSVoiceRecorder: VoiceAudioCapturing, @unchecked Sendable {
    private final class Capture: @unchecked Sendable {
        let id: String
        let lock = NSLock()
        var bytes = Data()
        var stopped = false
        var reportedReady = false
        let requestedAt: TimeInterval
        let converter: AVAudioConverter
        let format: AVAudioFormat
        init(id: String, converter: AVAudioConverter, format: AVAudioFormat, requestedAt: TimeInterval) {
            self.id = id
            self.converter = converter
            self.format = format
            self.requestedAt = requestedAt
        }
    }
    private let engine: AVAudioEngine
    private var capture: Capture?
    private var notification: NSObjectProtocol?
    private let sessionStateLock = NSLock()
    private var sessionPrepared = false
    private var sessionObservers: [NSObjectProtocol] = []

    init(engine: AVAudioEngine = AVAudioEngine()) {
        self.engine = engine
        for name in [AVAudioSession.interruptionNotification, AVAudioSession.routeChangeNotification, AVAudioSession.mediaServicesWereResetNotification] {
            sessionObservers.append(NotificationCenter.default.addObserver(forName: name, object: nil, queue: nil) { [weak self] _ in
                self?.invalidateSessionPreparation()
            })
        }
    }

    deinit { for observer in sessionObservers { NotificationCenter.default.removeObserver(observer) } }

    private func invalidateSessionPreparation() {
        sessionStateLock.lock(); sessionPrepared = false; sessionStateLock.unlock()
    }

    private func prepareSession() throws {
        let session = AVAudioSession.sharedInstance()
        sessionStateLock.lock(); let prepared = sessionPrepared; sessionStateLock.unlock()
        guard !prepared || session.category != .record || session.mode != .measurement ||
              !session.allowHapticsAndSystemSoundsDuringRecording else { return }
        let began = ProcessInfo.processInfo.systemUptime
        try session.setCategory(.record, mode: .measurement, options: [])
        // 默认 false 会在音频输入期间抑制 UIKit 触感；必须在预采集/录音启动前启用。
        try session.setAllowHapticsAndSystemSoundsDuringRecording(true)
        try? session.setPreferredIOBufferDuration(0.01)
        try session.setActive(true)
        sessionStateLock.lock(); sessionPrepared = true; sessionStateLock.unlock()
        traceVoiceInputLatency("session-prepared", since: began)
    }

    func prepare() throws {
        guard capture == nil else { return }
        do { try prepareSession() }
        catch { release(); throw error }
        // I/O 节点按需创建；空引擎 prepare 会抛出 Swift try? 无法捕获的 NSException。
        let input = engine.inputNode
        let hardwareFormat = input.inputFormat(forBus: 0)
        guard hardwareFormat.sampleRate > 0, hardwareFormat.channelCount > 0 else { release(); return }
        engine.prepare()
    }

    func release() {
        if let old = capture { _ = try? stop(id: old.id, discard: true) }
        engine.stop()
        engine.reset()
        // 切回文字输入或离开页面后，让系统听写和其他音频会话恢复。
        invalidateSessionPreparation()
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    func start(id: String, ready: @escaping @Sendable () -> Void, level: @escaping @Sendable (Float) -> Void, failure: @escaping @Sendable () -> Void) throws {
        let requestedAt = ProcessInfo.processInfo.systemUptime
        if let old = capture { _ = try? stop(id: old.id, discard: true) }
        let session = AVAudioSession.sharedInstance()
        do { try prepareSession() }
        catch { release(); throw error }
        let input = engine.inputNode
        let source = input.outputFormat(forBus: 0)
        guard source.sampleRate > 0, source.channelCount > 0,
              let target = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 16000, channels: 1, interleaved: true),
              let converter = AVAudioConverter(from: source, to: target) else {
            invalidateSessionPreparation()
            try? session.setActive(false, options: .notifyOthersOnDeactivation)
            throw NSError(domain: "VoiceRecorder", code: 1)
        }
        let current = Capture(id: id, converter: converter, format: target, requestedAt: requestedAt)
        capture = current
        input.installTap(onBus: 0, bufferSize: AVAudioFrameCount(max(128, source.sampleRate * Double(VoiceInputInteraction.shared.CAPTURE_WINDOW_MILLISECONDS) / 1000)), format: source) { buffer, _ in
            current.lock.lock()
            defer { current.lock.unlock() }
            guard !current.stopped else { return }
            let capacity = AVAudioFrameCount(ceil(Double(buffer.frameLength) * 16000 / source.sampleRate) + 64)
            guard let output = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: capacity) else { failure(); return }
            var supplied = false
            var error: NSError?
            let status = converter.convert(to: output, error: &error) { _, inputStatus in
                if supplied { inputStatus.pointee = .noDataNow; return nil }
                supplied = true; inputStatus.pointee = .haveData; return buffer
            }
            guard status != .error, error == nil, let samples = output.int16ChannelData?[0] else { failure(); return }
            let count = Int(output.frameLength)
            guard count > 0 else { return }
            // 定时停止与末尾采样可能交错；保留上限内的音频，不丢弃整段录音。
            let retainedBytes = min(count * 2, Int(VoiceAudioFormat.shared.MAX_CAPTURE_BYTES) - current.bytes.count)
            current.bytes.append(UnsafeRawPointer(samples).assumingMemoryBound(to: UInt8.self), count: retainedBytes)
            if !current.reportedReady {
                current.reportedReady = true
                traceVoiceInputLatency("first-pcm", since: current.requestedAt)
            }
            var energy: Double = 0
            for index in 0..<count { let value = Double(samples[index]) / 32768; energy += value * value }
            level(Float(sqrt(energy / Double(count))))
        }
        notification = NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { _ in failure() }
        do {
            let prepareStartedAt = ProcessInfo.processInfo.systemUptime
            engine.prepare()
            traceVoiceInputLatency("engine-prepare", since: prepareStartedAt)
            let engineStartedAt = ProcessInfo.processInfo.systemUptime
            try engine.start()
            guard engine.isRunning else { throw NSError(domain: "VoiceRecorder", code: 6) }
            traceVoiceInputLatency("engine-start", since: engineStartedAt)
            // start 成功后音频已流入 tap 的系统缓冲；不用等第一批 PCM 或转换器输出才反馈。
            ready()
        }
        catch { _ = try? stop(id: id, discard: true); invalidateSessionPreparation(); throw error }
    }
    func stop(id: String, discard: Bool) throws -> Data? {
        guard let current = capture, current.id == id else { return nil }
        capture = nil
        current.lock.lock(); current.stopped = true; current.lock.unlock()
        engine.inputNode.removeTap(onBus: 0); engine.stop()
        if let notification { NotificationCenter.default.removeObserver(notification) }
        notification = nil
        // 停止后即释放音频会话；转换失败、取消也必须执行。
        defer {
            invalidateSessionPreparation()
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        }
        current.lock.lock(); defer { current.lock.unlock() }
        guard !discard else { return nil }
        let tail = try finishVoicePCMConversion(current.converter, format: current.format)
        let remainingBytes = Int(VoiceAudioFormat.shared.MAX_CAPTURE_BYTES) - current.bytes.count
        current.bytes.append(tail.prefix(remainingBytes))
        return current.bytes
    }
}


/// noDataNow 只表示暂时没有输入；结束录音后必须送 endOfStream，取出转换器缓存的末尾样本。
/// 调用方在音频 tap 停止后持有 Capture.lock，避免与实时转换并发。
func finishVoicePCMConversion(_ converter: AVAudioConverter, format: AVAudioFormat) throws -> Data {
    var tail = Data()
    for _ in 0..<8 {
        guard let output = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 2048) else {
            throw NSError(domain: "VoiceRecorder", code: 3)
        }
        var error: NSError?
        let status = converter.convert(to: output, error: &error) { _, inputStatus in
            inputStatus.pointee = .endOfStream
            return nil
        }
        if let error { throw error }
        guard status != .error else { throw NSError(domain: "VoiceRecorder", code: 4) }
        if output.frameLength > 0, let samples = output.int16ChannelData?[0] {
            tail.append(UnsafeRawPointer(samples).assumingMemoryBound(to: UInt8.self), count: Int(output.frameLength) * 2)
        }
        if status == .endOfStream { return tail }
    }
    throw NSError(domain: "VoiceRecorder", code: 5)
}

#if DEBUG
private let voiceInputLatencyLogger = LocalLogger(subsystem: "ai.dsh.mobile.ios", category: "voice-input-latency")
#endif

/// 系统单调时钟便于对齐 UI 与硬件线程日志；不输出用户音频或文字。
func traceVoiceInputLatency(_ stage: String, since: TimeInterval? = nil) {
#if DEBUG
    let uptime = ProcessInfo.processInfo.systemUptime
    let elapsed = since.map { (uptime - $0) * 1000 } ?? 0
    voiceInputLatencyLogger.notice("VoiceInputLatency stage=\(stage) uptime=\(uptime) durationMs=\(elapsed)")
#endif
}


/// 记录已请求的触感及系统录音开关，不将请求日志视为实体马达已执行的证明。
private func traceVoiceHaptic(_ cue: String) {
#if DEBUG
    let allowed = AVAudioSession.sharedInstance().allowHapticsAndSystemSoundsDuringRecording
    voiceInputLatencyLogger.notice("VoiceInputHaptic cue=\(cue) recordingAllowed=\(allowed)")
#endif
}
