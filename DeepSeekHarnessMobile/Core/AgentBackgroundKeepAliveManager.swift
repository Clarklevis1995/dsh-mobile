import ActivityKit
import AVFoundation
import CoreLocation
import UIKit

@MainActor
protocol AgentLongRunningKeepAlive: AnyObject {
    var isKeepingAlive: Bool { get }
    var onPulse: (() -> Void)? { get set }

    func setAgentWorkActive(_ active: Bool)
    func applicationDidBecomeActive()
    func applicationDidEnterBackground()
    func prepareForTermination()
}

/// 在 Agent 工作期间组合使用后台定位与静音音频，延长本地 WebSocket
/// 获得执行时间的机会。所有资源只在确有 Agent 工作时创建，并在工作结束后释放。
@MainActor
final class AgentBackgroundKeepAliveManager: NSObject, AgentLongRunningKeepAlive {
    static let shared = AgentBackgroundKeepAliveManager()

    private let locationStartDelay: Duration = .seconds(15)
    private let pulseInterval: TimeInterval = 25

    private var agentWorkIsActive = false
    private var applicationIsInBackground = false
    private var didRequestAlwaysAuthorization = false
    private var locationStartTask: Task<Void, Never>?
    private var backgroundActivitySession: CLBackgroundActivitySession?
    private var isUpdatingLocation = false

    private var audioEngine: AVAudioEngine?
    private var audioPlayer: AVAudioPlayerNode?
    private var silentAudioBuffer: AVAudioPCMBuffer?
    private var audioSessionIsActive = false
    private var isStartingSilentAudio = false
    private var pulseTimer: Timer?

    var onPulse: (() -> Void)?

    var isKeepingAlive: Bool {
        silentAudioIsRunning || isUpdatingLocation
    }

    private var silentAudioIsRunning: Bool {
        audioEngine?.isRunning == true && audioPlayer?.isPlaying == true
    }

    private lazy var locationManager: CLLocationManager = {
        let manager = CLLocationManager()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyThreeKilometers
        manager.distanceFilter = 1_000
        manager.activityType = .other
        manager.pausesLocationUpdatesAutomatically = false
        manager.allowsBackgroundLocationUpdates = true
        // Live Activity 已经向用户明确展示 Agent 正在后台运行。额外的定位
        // 指示会占用主岛，并在定位停止后继续留下可点击的“最近使用”入口，
        // 造成 Agent 卡片已结束但点击空岛仍返回 App 的错觉。
        manager.showsBackgroundLocationIndicator = false
        return manager
    }()

    private override init() {
        super.init()
        let center = NotificationCenter.default
        center.addObserver(
            self,
            selector: #selector(handleAudioInterruption(_:)),
            name: AVAudioSession.interruptionNotification,
            object: nil
        )
        center.addObserver(
            self,
            selector: #selector(handleAudioRouteChange(_:)),
            name: AVAudioSession.routeChangeNotification,
            object: nil
        )
        center.addObserver(
            self,
            selector: #selector(handleMediaServicesReset),
            name: AVAudioSession.mediaServicesWereResetNotification,
            object: nil
        )
        center.addObserver(
            self,
            selector: #selector(handleApplicationTermination),
            name: UIApplication.willTerminateNotification,
            object: nil
        )
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    func setAgentWorkActive(_ active: Bool) {
        guard agentWorkIsActive != active else { return }
        agentWorkIsActive = active

        guard active else {
            stopAllResources()
            return
        }

        if applicationIsInBackground {
            startBackgroundResources()
        } else {
            prepareLocationAuthorizationAndSession()
        }
    }

    func applicationDidBecomeActive() {
        applicationIsInBackground = false
        locationStartTask?.cancel()
        locationStartTask = nil
        stopLocationUpdates()
        stopSilentAudio()
        stopPulseTimer()
        if agentWorkIsActive { prepareLocationAuthorizationAndSession() }
    }

    func applicationDidEnterBackground() {
        applicationIsInBackground = true
        guard agentWorkIsActive else { return }
        startBackgroundResources()
    }

    func prepareForTermination() {
        agentWorkIsActive = false
        stopAllResources()
    }

    private func prepareLocationAuthorizationAndSession() {
        guard CLLocationManager.locationServicesEnabled() else { return }

        switch locationManager.authorizationStatus {
        case .notDetermined:
            guard !didRequestAlwaysAuthorization else { return }
            didRequestAlwaysAuthorization = true
            locationManager.requestAlwaysAuthorization()
        case .authorizedWhenInUse:
            ensureBackgroundActivitySession()
            guard !didRequestAlwaysAuthorization else { return }
            didRequestAlwaysAuthorization = true
            locationManager.requestAlwaysAuthorization()
        case .authorizedAlways:
            ensureBackgroundActivitySession()
        case .restricted, .denied:
            break
        @unknown default:
            break
        }
    }

    private func ensureBackgroundActivitySession() {
        guard agentWorkIsActive,
              !applicationIsInBackground,
              backgroundActivitySession == nil else { return }
        backgroundActivitySession = CLBackgroundActivitySession()
    }

    private func startBackgroundResources() {
        // A playback audio session owns iOS's primary Now Playing island even
        // when its buffer is silent. Running it beside our Live Activity pushes
        // the Agent card into the detached minimal bubble and leaves the main
        // island looking empty. Location is the keep-alive path while the Agent
        // Live Activity is visible; silent audio remains a fallback for devices
        // where Live Activities are unavailable or disabled.
        reconcileSilentAudio()
        startPulseTimer()
        startLocationUpdatesIfAuthorized()
        if !isUpdatingLocation { scheduleLocationUpdates() }
        pulse()
    }

    private var hasVisibleAgentLiveActivity: Bool {
        !Activity<AgentActivityAttributes>.activities.isEmpty
    }

    private var shouldUseSilentAudio: Bool {
        // ActivityKit 只要已启用，就把主岛完整留给 Agent 实时活动。
        // 否则播放会被系统当作 Now Playing，即使音量为零，也会把 Agent
        // 挤进右侧最小岛。实时活动不可用时才回退到静音音频保活。
        !ActivityAuthorizationInfo().areActivitiesEnabled && !hasVisibleAgentLiveActivity
    }

    private func reconcileSilentAudio() {
        if shouldUseSilentAudio {
            startSilentAudio()
        } else {
            stopSilentAudio()
        }
    }

    private func scheduleLocationUpdates() {
        guard locationStartTask == nil else { return }
        locationStartTask = Task { [weak self] in
            guard let self else { return }
            try? await Task.sleep(for: locationStartDelay)
            guard !Task.isCancelled else { return }
            self.locationStartTask = nil
            self.startLocationUpdatesIfAuthorized()
        }
    }

    private func startLocationUpdatesIfAuthorized() {
        guard agentWorkIsActive,
              applicationIsInBackground,
              !isUpdatingLocation else { return }
        switch locationManager.authorizationStatus {
        case .authorizedAlways, .authorizedWhenInUse:
            locationManager.startUpdatingLocation()
            isUpdatingLocation = true
        case .notDetermined, .restricted, .denied:
            break
        @unknown default:
            break
        }
    }

    private func stopLocationUpdates() {
        guard isUpdatingLocation else { return }
        locationManager.stopUpdatingLocation()
        isUpdatingLocation = false
    }

    private func startSilentAudio() {
        guard agentWorkIsActive, applicationIsInBackground else { return }
        guard !silentAudioIsRunning, !isStartingSilentAudio else { return }
        isStartingSilentAudio = true
        defer { isStartingSilentAudio = false }

        stopSilentAudio(deactivateSession: false)
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .default, options: [.mixWithOthers])
            try session.setActive(true)
            audioSessionIsActive = true

            guard let format = AVAudioFormat(standardFormatWithSampleRate: 44_100, channels: 2) else {
                stopSilentAudio()
                return
            }
            let frameCount = AVAudioFrameCount(format.sampleRate)
            guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frameCount) else {
                try? session.setActive(false, options: .notifyOthersOnDeactivation)
                return
            }
            buffer.frameLength = frameCount
            if let channels = buffer.floatChannelData {
                for channel in 0..<Int(format.channelCount) {
                    channels[channel].initialize(repeating: 0, count: Int(frameCount))
                }
            }

            let engine = AVAudioEngine()
            let player = AVAudioPlayerNode()
            engine.attach(player)
            engine.connect(player, to: engine.mainMixerNode, format: format)
            player.volume = 0
            player.scheduleBuffer(buffer, at: nil, options: .loops)
            engine.prepare()
            try engine.start()
            player.play()

            audioEngine = engine
            audioPlayer = player
            silentAudioBuffer = buffer
        } catch {
            stopSilentAudio()
#if DEBUG
            print("[AgentKeepAlive] 无法启动静音音频：\(error.localizedDescription)")
#endif
        }
    }

    private func stopSilentAudio(deactivateSession: Bool = true) {
        audioPlayer?.stop()
        audioEngine?.stop()
        if let player = audioPlayer, let engine = audioEngine {
            engine.detach(player)
        }
        audioPlayer = nil
        audioEngine = nil
        silentAudioBuffer = nil
        if deactivateSession, audioSessionIsActive {
            try? AVAudioSession.sharedInstance().setActive(
                false,
                options: .notifyOthersOnDeactivation
            )
            audioSessionIsActive = false
        }
    }

    private func startPulseTimer() {
        guard pulseTimer == nil else { return }
        let timer = Timer(timeInterval: pulseInterval, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in self?.pulse() }
        }
        RunLoop.main.add(timer, forMode: .common)
        pulseTimer = timer
    }

    private func stopPulseTimer() {
        pulseTimer?.invalidate()
        pulseTimer = nil
    }

    private func pulse() {
        guard agentWorkIsActive, applicationIsInBackground else { return }
        reconcileSilentAudio()
        onPulse?()
    }

    private func stopAllResources() {
        locationStartTask?.cancel()
        locationStartTask = nil
        stopPulseTimer()
        stopLocationUpdates()
        backgroundActivitySession?.invalidate()
        backgroundActivitySession = nil
        stopSilentAudio()
    }

    @objc private func handleAudioInterruption(_ notification: Notification) {
        guard let rawType = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: rawType) else { return }
        if type == .began {
            stopSilentAudio(deactivateSession: false)
        } else if agentWorkIsActive, applicationIsInBackground {
            reconcileSilentAudio()
        }
    }

    @objc private func handleAudioRouteChange(_ notification: Notification) {
        guard agentWorkIsActive, applicationIsInBackground else { return }
        reconcileSilentAudio()
    }

    @objc private func handleMediaServicesReset() {
        guard agentWorkIsActive, applicationIsInBackground else { return }
        stopSilentAudio(deactivateSession: false)
        reconcileSilentAudio()
    }

    @objc private func handleApplicationTermination() {
        prepareForTermination()
    }
}

extension AgentBackgroundKeepAliveManager: @preconcurrency CLLocationManagerDelegate {
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        guard agentWorkIsActive else { return }
        switch manager.authorizationStatus {
        case .authorizedAlways, .authorizedWhenInUse:
            if applicationIsInBackground {
                startLocationUpdatesIfAuthorized()
            } else {
                prepareLocationAuthorizationAndSession()
            }
        case .restricted, .denied:
            stopLocationUpdates()
            backgroundActivitySession?.invalidate()
            backgroundActivitySession = nil
        case .notDetermined:
            break
        @unknown default:
            break
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        // 不保存或上传位置；定位回调只作为系统授予的后台执行机会。
        pulse()
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
#if DEBUG
        let locationError = error as? CLError
        if locationError?.code != .locationUnknown {
            print("[AgentKeepAlive] 后台定位失败：\(error.localizedDescription)")
        }
#endif
    }
}
