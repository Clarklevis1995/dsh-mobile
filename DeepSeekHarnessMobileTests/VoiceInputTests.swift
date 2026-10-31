import XCTest
import SwiftUI
import Combine
import AVFoundation
import DeepSeekHarnessShared
@testable import DeepSeekHarnessMobile

final class VoiceInputTests: XCTestCase {
    func testNativeAudioSessionEnablesHapticsForColdCachedAndReopenedPreparation() throws {
        let session = AVAudioSession.sharedInstance()
        let originalPermission = session.allowHapticsAndSystemSoundsDuringRecording
        let recorder = IOSVoiceRecorder()
        defer {
            recorder.release()
            try? session.setAllowHapticsAndSystemSoundsDuringRecording(originalPermission)
        }
        for _ in 0..<2 {
            try session.setAllowHapticsAndSystemSoundsDuringRecording(false)
            try recorder.prepare()
            XCTAssertTrue(session.allowHapticsAndSystemSoundsDuringRecording, "录音会话必须允许三个节点的触感")
            // 其他音频配置改回 false 时，准备缓存也不能跳过系统设置。
            try session.setAllowHapticsAndSystemSoundsDuringRecording(false)
            try recorder.prepare()
            XCTAssertTrue(session.allowHapticsAndSystemSoundsDuringRecording, "复用会话仍需检查录音触感开关")
            recorder.release()
        }
    }

    func testNativeRecorderCanPrewarmColdEngineAndAfterReleaseWithoutRecording() throws {
        let engine = AVAudioEngine()
        let recorder = IOSVoiceRecorder(engine: engine)
        defer { recorder.release() }
        // 真实 AVAudioEngine：替身无法检测未建立 I/O 节点时 prepare 的 NSException。
        for _ in 0..<3 {
            try recorder.prepare()
            try recorder.prepare()
            XCTAssertFalse(engine.isRunning, "预热不得启动音频采集")
            XCTAssertTrue(engine.attachedNodes.contains(engine.inputNode), "必须先建立真实输入节点")
            XCTAssertNil(try recorder.stop(id: "not-recording", discard: false))
            recorder.release()
            XCTAssertFalse(engine.isRunning)
        }
    }

    @MainActor
    func testTextInputNeverPrewarmsAndLeavingVoiceModeReleasesHardware() async throws {
        let (shared, _) = try recordingStore()
        _ = shared.cancel()
        _ = shared.setVoiceMode(enabled: false)
        let prepared = expectation(description: "仅进入语音输入时预热")
        let released = expectation(description: "切回文字时释放")
        let recorder = VoiceModeRecorderSpy(prepared: prepared, released: released)
        let voice = VoiceInputAdapter(shared: shared, recorder: recorder, permissionGranted: { true })
        voice.open(context: "swift-test", draft: "")
        voice.keyboardChanged(true)
        voice.keyboardChanged(false)
        voice.setVoiceMode(true)
        await fulfillment(of: [prepared], timeout: 1)
        voice.setVoiceMode(false)
        voice.keyboardChanged(true)
        await fulfillment(of: [released], timeout: 1)
        XCTAssertEqual(recorder.prepareCount, 1, "文字态进入页面、键盘变化不得激活预热")
        XCTAssertEqual(recorder.startCount, 0, "仅切换到语音输入不得采集音频")
    }

    func testEndOfStreamPreservesBufferedTailSamples() throws {
        let source = try XCTUnwrap(AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 48000, channels: 1, interleaved: false))
        let target = try XCTUnwrap(AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 16000, channels: 1, interleaved: true))
        let converter = try XCTUnwrap(AVAudioConverter(from: source, to: target))
        let input = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: source, frameCapacity: 1024))
        input.frameLength = 1024
        let samples = try XCTUnwrap(input.floatChannelData?[0])
        for index in 0..<1024 { samples[index] = index >= 1000 ? 0.7 : 0 }
        let output = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: target, frameCapacity: 406))
        var supplied = false
        var error: NSError?
        let status = converter.convert(to: output, error: &error) { _, inputStatus in
            if supplied { inputStatus.pointee = .noDataNow; return nil }
            supplied = true
            inputStatus.pointee = .haveData
            return input
        }
        XCTAssertNil(error)
        XCTAssertNotEqual(status, .error)
        let tail = try finishVoicePCMConversion(converter, format: target)
        XCTAssertGreaterThan(tail.count, 0, "仅使用 noDataNow 时末尾仍有未输出的采样")
        XCTAssertTrue(tail.contains { $0 != 0 }, "最后的有声样本必须被排空到提交的 PCM 中")
        let frames = Double(output.frameLength) + Double(tail.count / 2)
        XCTAssertEqual(frames, 1024.0 / 3, accuracy: 1)
        XCTAssertTrue(try finishVoicePCMConversion(converter, format: target).isEmpty)
    }

    func testSpeechSelectionIsDeliveredRawWithoutChatModelDecoding() throws {
        let json = #"{"kind":"speech-state","requestId":"watch","selection":{"providerId":"sensevoice-local","language":"zh"}}"#
        let decoded = try GatewayClient.decodeIncomingFrame(Data(json.utf8))
        XCTAssertNil(decoded.0)
        XCTAssertEqual(decoded.1, json)
        let error = #"{"kind":"error","requestType":"speech-transcribe","requestId":"record","message":"model unavailable"}"#
        XCTAssertEqual(try GatewayClient.decodeIncomingFrame(Data(error.utf8)).1, error)
        let ordinary = try GatewayClient.decodeIncomingFrame(Data(#"{"kind":"pong"}"#.utf8))
        XCTAssertEqual(ordinary.0?.kind, "pong")
        XCTAssertNil(ordinary.1)
    }

    @MainActor
    func testAdapterDoesNotPublishUnchangedSharedSnapshots() {
        let adapter = VoiceInputAdapter()
        var updates = 0
        let observation = adapter.$state.dropFirst().sink { _ in updates += 1 }
        adapter.keyboardChanged(false)
        adapter.keyboardChanged(true)
        adapter.keyboardChanged(true)
        adapter.keyboardChanged(false)
        XCTAssertEqual(updates, 2)
        XCTAssertEqual(adapter.state.hint, "描述你想要构建的内容")
        withExtendedLifetime(observation) {}
    }

    @MainActor
    func testEmptySpeechUsesToastAndAutomaticallyDismissesWithoutEditingDraft() async throws {
        let (shared, token) = try recordingStore()
        _ = shared.release(nowMilliseconds: 1500)
        _ = shared.captureFinished(id: token, pcmBase64: Data(repeating: 0, count: 16000).base64EncodedString(), nowMilliseconds: 1501)
        let adapter = VoiceInputAdapter(shared: shared)
        var draftUpdates = 0
        adapter.onDraftUpdate = { _ in draftUpdates += 1 }
        adapter.accept("""
        {"kind":"speech-transcript","requestId":"\(token)","text":""}
        """)
        XCTAssertEqual(adapter.toastMessage, "未识别到人声")
        XCTAssertNil(adapter.state.alertMessage)
        XCTAssertNil(adapter.state.error)
        XCTAssertEqual(draftUpdates, 0)
        XCTAssertFalse(adapter.state.keyboardVisible)
        try await Task.sleep(for: .milliseconds(2150))
        XCTAssertNil(adapter.toastMessage)
    }

    @MainActor
    func testSlowMicrophoneShowsOverlayImmediatelyAndCancellationRemainsResponsive() async throws {
        let shared = SharedVoiceInputStore()
        _ = shared.open(context: "latency", currentDraft: "")
        let opening = shared.connectionChanged(connected: true, supported: true)
        let catalog = try XCTUnwrap(opening.requests.first { $0.requestType == "speech-catalog" }?.correlationId)
        _ = shared.acceptJson(json: """
        {"kind":"speech-catalog","requestId":"\(catalog)","available":true,"selection":{"providerId":"p"},"providers":[{"id":"p","preparation":{"phase":"ready"}}]}
        """)
        let startupFinished = expectation(description: "slow hardware completed")
        let hardwareStopped = expectation(description: "cancelled hardware cleaned up")
        let overlayAppeared = expectation(description: "蒙层不等待麦克风启动即可显示")
        let recorder = SlowVoiceRecorder(startupFinished: startupFinished, stopped: hardwareStopped)
        let voice = VoiceInputAdapter(shared: shared, recorder: recorder, requestPermission: { $0(true) })
        let controller = UIHostingController(rootView: VoiceLatencyHarness(voice: voice, appeared: { overlayAppeared.fulfill() }))
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 390, height: 844)
        window.rootViewController = controller
        window.makeKeyAndVisible()
        defer { window.isHidden = true; voice.leave() }
        controller.view.layoutIfNeeded()
        try await Task.sleep(for: .milliseconds(100))
        let began = ProcessInfo.processInfo.systemUptime
        voice.setVoiceMode(true)
        voice.press()
        XCTAssertTrue(voice.state.overlayVisible)
        await fulfillment(of: [overlayAppeared], timeout: 0.15)
        try await Task.sleep(for: .milliseconds(60))
        let elapsed = ProcessInfo.processInfo.systemUptime - began
        XCTAssertLessThan(elapsed, 0.35, "蒙层与主线程不能等待耗时一秒的麦克风启动")
        XCTAssertEqual(voice.state.phase, "starting")
        XCTAssertTrue(voice.state.overlayVisible)
        XCTAssertEqual(voice.state.status, "正在开启麦克风")
        XCTAssertFalse(recorder.startedOnMain)
        let image = UIGraphicsImageRenderer(size: window.bounds.size).image { _ in
            window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
        }
        let attachment = XCTAttachment(image: image)
        attachment.name = "等待硬件启动时立即显示语音蒙层"
        attachment.lifetime = .keepAlways
        add(attachment)
        voice.cancel()
        XCTAssertEqual(voice.state.phase, "idle")
        XCTAssertFalse(voice.state.overlayVisible)
        await fulfillment(of: [startupFinished, hardwareStopped], timeout: 2)
        try await Task.sleep(for: .milliseconds(50))
        XCTAssertEqual(voice.state.phase, "idle", "迟到的启动结果不能恢复已取消的录音")
        XCTAssertTrue(recorder.discarded)
        print("VoiceInputLatency iOS recognizedToResponsiveMain=\(Int(elapsed * 1000))ms simulatedHardwareStartup=1000ms")
    }

    @MainActor
    func testAuthorizedTouchPreCapturesAndShortTapDiscardsAudio() async throws {
        let (shared, _) = try recordingStore()
        _ = shared.cancel()
        let began = expectation(description: "down 已启动硬件，无需等 longpress")
        let stopped = expectation(description: "短按丢弃预采集")
        let recorder = ControlledVoiceRecorder(began: began, stopped: stopped)
        let voice = VoiceInputAdapter(shared: shared, recorder: recorder, permissionGranted: { true }, requestPermission: { _ in XCTFail("已授权不应重复请求权限") })
        voice.touchDown()
        await fulfillment(of: [began], timeout: 0.5)
        XCTAssertEqual(voice.state.phase, "priming")
        XCTAssertFalse(voice.state.overlayVisible)
        recorder.hardwareStarted()
        try await Task.sleep(for: .milliseconds(40))
        XCTAssertFalse(voice.state.overlayVisible)
        voice.touchFinished()
        await fulfillment(of: [stopped], timeout: 0.5)
        XCTAssertTrue(recorder.discarded)
        XCTAssertFalse(voice.state.active)
        recorder.hardwareStarted()
        try await Task.sleep(for: .milliseconds(40))
        XCTAssertFalse(voice.state.overlayVisible)
        voice.leave()
    }

    @MainActor
    func testShortTapCancelsQueuedStartupBeforeWarmupFinishes() async throws {
        let (shared, _) = try recordingStore()
        _ = shared.cancel()
        let preparing = expectation(description: "预热占用硬件串行队列")
        let released = expectation(description: "退出会话已清理预热")
        let recorder = DelayedPreparationVoiceRecorder(preparing: preparing, released: released)
        let voice = VoiceInputAdapter(shared: shared, recorder: recorder, permissionGranted: { true })
        defer { recorder.finishPreparation() }
        voice.open(context: "swift-test", draft: "")
        voice.setVoiceMode(true)
        await fulfillment(of: [preparing], timeout: 0.5)
        voice.touchDown()
        voice.touchFinished()
        recorder.finishPreparation()
        voice.leave()
        await fulfillment(of: [released], timeout: 1)
        XCTAssertEqual(recorder.startCount, 0, "短按已撤销，排队的硬件启动不得在预热结束后执行")
        XCTAssertFalse(voice.state.active)
    }

    @MainActor
    func testRunningCaptureShowsOverlayBeforeFirstConvertedSamplesWithoutSecondStartup() async throws {
        let (shared, _) = try recordingStore()
        _ = shared.cancel()
        let began = expectation(description: "硬件预采集启动")
        let stopped = expectation(description: "测试结束清理采集")
        let recorder = ControlledVoiceRecorder(began: began, stopped: stopped)
        let voice = VoiceInputAdapter(shared: shared, recorder: recorder, permissionGranted: { true })
        voice.touchDown()
        await fulfillment(of: [began], timeout: 0.5)
        recorder.hardwareStarted()
        try await Task.sleep(for: .milliseconds(40))
        voice.setVoiceMode(true)
        voice.press()
        XCTAssertEqual(voice.state.phase, "recording")
        XCTAssertTrue(voice.state.overlayVisible)
        XCTAssertFalse(voice.state.waveformActive, "没有音量回调也能显示正在运行的录音蒙层")
        XCTAssertEqual(recorder.startCount, 1)
        voice.cancel()
        await fulfillment(of: [stopped], timeout: 0.5)
        voice.leave()
    }

    @MainActor
    func testReleaseRemovesOverlayBeforeTranscriptArrives() async throws {
        let (shared, token) = try recordingStore()
        let voice = VoiceInputAdapter(shared: shared)
        var draft = "原草稿"
        voice.onDraftUpdate = { draft = $0 }
        let appeared = expectation(description: "录音蒙层显示")
        let disappeared = expectation(description: "抬手后蒙层退出，转写仍在后台")
        let controller = UIHostingController(rootView: VoiceLatencyHarness(voice: voice,
            appeared: { appeared.fulfill() }, disappeared: { disappeared.fulfill() }))
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 390, height: 844)
        window.rootViewController = controller
        window.makeKeyAndVisible()
        defer { window.isHidden = true; voice.leave() }
        await fulfillment(of: [appeared], timeout: 0.5)
        voice.release()
        XCTAssertTrue(voice.state.active)
        XCTAssertFalse(voice.state.overlayVisible)
        XCTAssertEqual(voice.state.status, "")
        await fulfillment(of: [disappeared], timeout: 0.7)
        _ = shared.captureFinished(id: token, pcmBase64: Data(repeating: 0, count: 16000).base64EncodedString(), nowMilliseconds: 1700)
        voice.draftChanged("原草稿")
        XCTAssertEqual(voice.state.phase, "transcribing")
        XCTAssertFalse(voice.state.overlayVisible)
        XCTAssertEqual(voice.state.status, "")
        voice.accept("""
        {"kind":"speech-transcript","requestId":"\(token)","text":"后台回填文字"}
        """)
        XCTAssertEqual(draft, "原草稿\n后台回填文字")
        XCTAssertFalse(voice.state.active)
        XCTAssertFalse(voice.state.keyboardVisible)
    }

    private func recordingStore() throws -> (SharedVoiceInputStore, String) {
        let shared = SharedVoiceInputStore()
        _ = shared.open(context: "swift-test", currentDraft: "原草稿")
        let opening = shared.connectionChanged(connected: true, supported: true)
        let catalog = try XCTUnwrap(opening.requests.first { $0.requestType == "speech-catalog" }?.correlationId)
        _ = shared.acceptJson(json: """
        {"kind":"speech-catalog","requestId":"\(catalog)","available":true,"selection":{"providerId":"p","language":"zh"},"providers":[{"id":"p","preparation":{"phase":"ready"}}]}
        """)
        _ = shared.setVoiceMode(enabled: true)
        let token = try XCTUnwrap(shared.press(nowMilliseconds: 1000).effects.first { $0.action == "permission" }?.recordingId)
        _ = shared.permissionResult(id: token, granted: true, nowMilliseconds: 1001)
        _ = shared.captureStarted(id: token, nowMilliseconds: 1002)
        return (shared, token)
    }

    func testKotlinNativeTransitionFillsDraftWithoutAgentRequest() throws {
        let (shared, token) = try recordingStore()
        _ = shared.release(nowMilliseconds: 1500)
        let audio = Data(repeating: 0, count: 16000).base64EncodedString()
        let transition = shared.captureFinished(id: token, pcmBase64: audio, nowMilliseconds: 1501)
        XCTAssertEqual(transition.requests.first?.requestType, "speech-transcribe")
        let result = shared.acceptJson(json: """
        {"kind":"speech-transcript","requestId":"\(token)","text":"构建一个应用"}
        """)
        XCTAssertEqual(result.draftUpdate, "原草稿\n构建一个应用")
        XCTAssertEqual(result.snapshot.phase, "idle")
        XCTAssertTrue(result.requests.isEmpty)
    }

    @MainActor
    func testParticleOverlayRendersSpeechSilenceAndCancellation() throws {
        let (shared, token) = try recordingStore()
        let silent = shared.snapshot()
        for index in 0..<50 { _ = shared.level(id: token, rms: Float(0.01 + Double(index % 9) * 0.006), nowMilliseconds: Int64(2640 + index * 40)) }
        _ = shared.tick(nowMilliseconds: 4600)
        let speaking = shared.snapshot()
        _ = shared.pointerMoved(verticalDistance: -100)
        let cancellation = shared.snapshot()
        _ = shared.pointerMoved(verticalDistance: 0)
        for index in 0..<60 { _ = shared.level(id: token, rms: 0, nowMilliseconds: Int64(4640 + index * 40)) }
        let paused = shared.snapshot()
        XCTAssertTrue(paused.waveformActive)
        for scheme in [ColorScheme.light, .dark] {
            let theme = scheme == .dark ? "dark" : "light"
            for (name, snapshot) in [("speaking", speaking), ("cancel", cancellation), ("silent", silent), ("paused", paused)] {
                let renderer = ImageRenderer(content: VoiceRipplePanel(state: snapshot)
                    .frame(width: 390, height: 844 * VoiceInputInteraction.shared.OVERLAY_HEIGHT_FRACTION)
                    .background(scheme == .dark ? Color.black : Color.white)
                    .environment(\.colorScheme, scheme))
                renderer.scale = 3
                let image = try XCTUnwrap(renderer.uiImage)
                XCTAssertEqual(image.size.height, 844 * VoiceInputInteraction.shared.OVERLAY_HEIGHT_FRACTION, accuracy: 1 / renderer.scale)
                let attachment = XCTAttachment(image: image)
                attachment.name = "voice-theme-\(theme)-\(name)"
                attachment.lifetime = .keepAlways
                add(attachment)
            }
            // 用实际文字背景展示白色外晕如何淡化重叠内容；单色背景无法体现这层效果。
            let underlayRenderer = ImageRenderer(content: ZStack {
                (scheme == .dark ? Color.black : Color.white)
                VStack(alignment: .leading, spacing: 8) {
                    ForEach(0..<11, id: \.self) { _ in
                        Text("会话内容与柔和的椭圆外晕自然衔接")
                            .font(.system(size: 16))
                    }
                }
                .foregroundStyle(scheme == .dark ? Color.white : Color.black)
                VoiceRipplePanel(state: silent)
            }
            .frame(width: 390, height: 844 * VoiceInputInteraction.shared.OVERLAY_HEIGHT_FRACTION)
            .environment(\.colorScheme, scheme))
            underlayRenderer.scale = 3
            let underlayImage = try XCTUnwrap(underlayRenderer.uiImage)
            let underlayAttachment = XCTAttachment(image: underlayImage)
            underlayAttachment.name = "voice-halo-\(theme)-underlay"
            underlayAttachment.lifetime = .keepAlways
            add(underlayAttachment)
        }
    }

    @MainActor
    func testUnchangedFocusMirrorCannotDismissNativeEditingBeforeSwiftUICommits() async throws {
        var committedFocus = false
        var pendingFocus: Bool?
        let representation = SlashCommandTextView(text: .constant("转写内容"), presentation: nil,
            placeholder: "", isFocused: Binding(get: { committedFocus }, set: { pendingFocus = $0 }))
        let coordinator = representation.makeCoordinator()
        let controller = UIViewController()
        let editor = CommandTextView(frame: CGRect(x: 20, y: 80, width: 330, height: 100))
        editor.delegate = coordinator
        controller.view.addSubview(editor)
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 390, height: 844)
        window.rootViewController = controller
        window.makeKeyAndVisible()
        defer { window.isHidden = true }
        coordinator.synchronizeFocus(editor)
        XCTAssertTrue(editor.becomeFirstResponder())
        XCTAssertEqual(pendingFocus, true)
        // 原生短按已聚焦，Binding 的新值尚未进入 SwiftUI 下一帧。
        coordinator.synchronizeFocus(editor)
        try await Task.sleep(for: .milliseconds(40))
        XCTAssertTrue(editor.isFirstResponder, "普通刷新不能把上一帧的 false 当作主动失焦")
        committedFocus = true
        coordinator.synchronizeFocus(editor)
        try await Task.sleep(for: .milliseconds(40))
        XCTAssertTrue(editor.isFirstResponder)
        committedFocus = false
        coordinator.synchronizeFocus(editor)
        try await Task.sleep(for: .milliseconds(40))
        XCTAssertFalse(editor.isFirstResponder, "明确的收起输入操作仍须生效")
    }

    @MainActor
    func testComposerSeparatesNativeTextEditingFromHoldToSpeak() async throws {
        let voice = VoiceInputAdapter()
        let fixture = VoiceEditorFixture(voice: voice)
        fixture.draft = ""
        let controller = UIHostingController(rootView: VoiceEditorHarness(fixture: fixture))
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 390, height: 180)
        window.rootViewController = controller
        window.makeKeyAndVisible()
        defer { window.isHidden = true; voice.leave() }
        controller.view.frame = window.bounds
        controller.view.layoutIfNeeded()
        let editor = try XCTUnwrap(voiceTextView(in: controller.view))
        XCTAssertFalse((editor.gestureRecognizers ?? []).contains { $0 is VoiceHoldGestureRecognizer })
        voice.touchDown()
        voice.press()
        XCTAssertFalse(voice.state.active, "文字态不能启动录音")
        voice.setVoiceMode(true)
        try await Task.sleep(for: .milliseconds(100))
        controller.view.layoutIfNeeded()
        XCTAssertNil(voiceTextView(in: controller.view))
        func holdLabel(in view: UIView) -> UILabel? {
            if let label = view as? UILabel, label.accessibilityIdentifier == "composer-voice-hold" { return label }
            return view.subviews.lazy.compactMap { holdLabel(in: $0) }.first
        }
        let label = try XCTUnwrap(holdLabel(in: controller.view))
        XCTAssertEqual(label.text, String(localized: "按住说话"))
        let gesture = try XCTUnwrap(label.gestureRecognizers?.compactMap { $0 as? VoiceHoldGestureRecognizer }.first)
        XCTAssertEqual(gesture.minimumPressDuration, Double(VoiceInputInteraction.shared.LONG_PRESS_MILLISECONDS) / 1000)
        XCTAssertFalse(voice.state.active, "切换输入方式不能直接录音")
        let image = UIGraphicsImageRenderer(size: window.bounds.size).image { context in
            controller.view.layer.render(in: context.cgContext)
        }
        let attachment = XCTAttachment(image: image)
        attachment.name = "voice-mode-input"
        attachment.lifetime = .keepAlways
        add(attachment)
        voice.setVoiceMode(false)
        try await Task.sleep(for: .milliseconds(100))
        controller.view.layoutIfNeeded()
        XCTAssertNotNil(voiceTextView(in: controller.view))
    }

    @MainActor
    func testConversationVoiceModeKeepsComposerCompactAcrossWindowSizes() async throws {
        // 也需在 iPhone 真机执行：此用例可复现输入卡片泛型过深造成的主线程栈溢出，
        // 只跑模拟器可能漏掉首次进入会话时的崩溃。
        let suiteName = "VoiceComposerLayout.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let preferences = UserDefaultsAppPreferences(userDefaults: defaults)
        preferences.automaticallyConnect = false
        let session = SessionSummary(id: "voice-layout-session", title: "语音输入布局回归",
            lastActivity: Date(timeIntervalSince1970: 1), isRunning: false, hasUnread: false)
        preferences.saveSessions([session])
        let voice = VoiceInputAdapter()
        let appStore = AppStore(preferences: preferences, gatewayLocalID: suiteName, voiceInput: voice)
        defer { appStore.deactivateGateway() }
        let prepared = await appStore.prepareConversation(for: session)
        XCTAssertTrue(prepared)
        let controller = UIHostingController(rootView: ConversationView(activeView: .constant(0)).environmentObject(appStore))
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        window.rootViewController = controller
        window.makeKeyAndVisible()
        defer { window.isHidden = true }
        for size in [CGSize(width: 390, height: 844), CGSize(width: 844, height: 390), CGSize(width: 834, height: 1210)] {
            voice.setVoiceMode(false)
            window.frame = CGRect(origin: .zero, size: size)
            controller.view.frame = window.bounds
            controller.view.layoutIfNeeded()
            try await Task.sleep(for: .milliseconds(180))
            let editor = try XCTUnwrap(voiceTextView(in: controller.view))
            let textRect = editor.convert(editor.bounds, to: window)
            voice.setVoiceMode(true)
            try await Task.sleep(for: .milliseconds(180))
            controller.view.layoutIfNeeded()
            let hold = try XCTUnwrap(voiceHoldLabel(in: controller.view))
            let voiceRect = hold.convert(hold.bounds, to: window)
            let expectedHeight = max(38, ceil(hold.font.lineHeight))
            // 两种输入区沿用相同的顶部留白；扣除语音文字的 4pt 居中偏移后，
            // 顶边必须重合，防止底部卡片因分割线增高而向上移动。
            XCTAssertEqual(voiceRect.minY + 4, textRect.minY, accuracy: 1,
                "增加分割线和切换语音态不能改变输入框默认高度：\(size)")
            print("VoiceComposerTop size=\(size) text=\(textRect.minY) voice=\(voiceRect.minY + 4)")
            XCTAssertEqual(voiceRect.height, expectedHeight, accuracy: 1,
                "语音区必须按内容测量高度，不能占满会话页面：\(size)")
            XCTAssertGreaterThan(voiceRect.width, size.width * 0.5, "整行应可按住说话")
            XCTAssertEqual(voiceRect.midY, textRect.midY, accuracy: 12,
                "切换后输入区应保持在底部，不能推高输入卡片：\(size)")
            if size.width == 390 {
                let image = UIGraphicsImageRenderer(size: window.bounds.size).image { _ in
                    window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
                }
                let attachment = XCTAttachment(image: image)
                attachment.name = "voice-mode-full-conversation-compact"
                attachment.lifetime = .keepAlways
                add(attachment)
            }
            voice.setVoiceMode(false)
            try await Task.sleep(for: .milliseconds(100))
            XCTAssertNotNil(voiceTextView(in: controller.view))
        }
    }

    @MainActor
    func testConversationPageKeepsTranscriptEditableAfterLateKeyboardHide() async throws {
        let suiteName = "VoiceConversationFocus.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let preferences = UserDefaultsAppPreferences(userDefaults: defaults)
        preferences.automaticallyConnect = false
        let session = SessionSummary(id: "voice-focus-session", title: "语音焦点回归",
            lastActivity: Date(timeIntervalSince1970: 1), isRunning: false, hasUnread: false)
        preferences.saveSessions([session])
        let shared = SharedVoiceInputStore()
        let voice = VoiceInputAdapter(shared: shared)
        let appStore = AppStore(preferences: preferences, gatewayLocalID: suiteName, voiceInput: voice)
        defer { appStore.deactivateGateway() }
        let prepared = await appStore.prepareConversation(for: session)
        XCTAssertTrue(prepared)
        let controller = UIHostingController(rootView: ConversationView(activeView: .constant(0)).environmentObject(appStore))
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 390, height: 844)
        window.rootViewController = controller
        window.makeKeyAndVisible()
        defer { window.isHidden = true }
        controller.view.frame = window.bounds
        controller.view.layoutIfNeeded()
        try await Task.sleep(for: .milliseconds(150))
        // 真实页面和回调，不开启硬件、不连接远端；共享状态机产生合法的转写请求 ID。
        _ = shared.open(context: session.id, currentDraft: "")
        let opening = shared.connectionChanged(connected: true, supported: true)
        let catalogID = try XCTUnwrap(opening.requests.first { $0.requestType == "speech-catalog" }?.correlationId)
        _ = shared.acceptJson(json: """
        {"kind":"speech-catalog","requestId":"\(catalogID)","available":true,"selection":{"providerId":"p"},"providers":[{"id":"p","preparation":{"phase":"ready"}}]}
        """)
        _ = shared.setVoiceMode(enabled: true)
        let token = try XCTUnwrap(shared.press(nowMilliseconds: 1000).effects.first { $0.action == "permission" }?.recordingId)
        _ = shared.permissionResult(id: token, granted: true, nowMilliseconds: 1001)
        _ = shared.captureStarted(id: token, nowMilliseconds: 1002)
        _ = shared.release(nowMilliseconds: 1500)
        _ = shared.captureFinished(id: token, pcmBase64: Data(repeating: 0, count: 16000).base64EncodedString(), nowMilliseconds: 1501)
        voice.draftChanged("")
        try await Task.sleep(for: .milliseconds(30))
        voice.accept("""
        {"kind":"speech-transcript","requestId":"\(token)","text":"语音转写内容"}
        """)
        try await Task.sleep(for: .milliseconds(120))
        let editor = try XCTUnwrap(voiceTextView(in: controller.view))
        XCTAssertEqual(editor.text, "语音转写内容")
        XCTAssertFalse(editor.isFirstResponder, "ASR 回填不得主动聚焦或弹出键盘")
        XCTAssertFalse(voice.state.keyboardVisible)
        // 用户点选后的原生聚焦才开始编辑，不能被后续刷新或隐藏通知打断。
        XCTAssertTrue(editor.becomeFirstResponder())
        try await Task.sleep(for: .milliseconds(80))
        XCTAssertTrue(editor.isFirstResponder)
        // 前一轮键盘收起回调晚于新编辑会话；它不能成为新的失焦请求。
        NotificationCenter.default.post(name: UIResponder.keyboardDidHideNotification, object: nil,
            userInfo: [UIResponder.keyboardFrameEndUserInfoKey: NSValue(cgRect: CGRect(x: 0, y: 844, width: 390, height: 0))])
        try await Task.sleep(for: .milliseconds(80))
        XCTAssertTrue(editor.isFirstResponder, "迟到的隐藏通知不能夺走转写编辑焦点")
        editor.selectedRange = NSRange(location: (editor.text as NSString).length, length: 0)
        editor.insertText("修改")
        try await Task.sleep(for: .milliseconds(80))
        XCTAssertEqual(editor.text, "语音转写内容修改")
        XCTAssertTrue(editor.isFirstResponder)
        // 正常结束编辑仍应生效，重新短按聚焦后继续修改。
        window.endEditing(true)
        try await Task.sleep(for: .milliseconds(40))
        XCTAssertFalse(editor.isFirstResponder)
        XCTAssertTrue(editor.becomeFirstResponder())
        try await Task.sleep(for: .milliseconds(80))
        editor.insertText("继续")
        XCTAssertTrue(editor.isFirstResponder)
        XCTAssertTrue(editor.text.hasSuffix("继续"))
    }

    @MainActor
    func testTranscriptReturnsToNativeEditorAndAllowsMarkedTextEditing() async throws {
        let (shared, token) = try recordingStore()
        _ = shared.release(nowMilliseconds: 1500)
        _ = shared.captureFinished(id: token, pcmBase64: Data(repeating: 0, count: 16000).base64EncodedString(), nowMilliseconds: 1501)
        let voice = VoiceInputAdapter(shared: shared)
        let fixture = VoiceEditorFixture(voice: voice)
        let controller = UIHostingController(rootView: VoiceEditorHarness(fixture: fixture))
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 390, height: 844)
        window.rootViewController = controller
        window.makeKeyAndVisible()
        defer { window.isHidden = true }
        controller.view.frame = window.bounds
        controller.view.layoutIfNeeded()
        XCTAssertNil(voiceTextView(in: controller.view))
        voice.accept("""
        {"kind":"speech-transcript","requestId":"\(token)","text":"语音文字"}
        """)
        try await Task.sleep(for: .milliseconds(100))
        controller.view.layoutIfNeeded()
        let editor = try XCTUnwrap(voiceTextView(in: controller.view))
        XCTAssertFalse(voice.state.voiceMode)
        XCTAssertFalse(editor.isFirstResponder, "回填后等待用户点选，不请求焦点")
        XCTAssertFalse(fixture.focused)
        XCTAssertEqual(editor.text, "原草稿\n语音文字")
        XCTAssertTrue(editor.becomeFirstResponder())
        try await Task.sleep(for: .milliseconds(80))
        XCTAssertTrue(editor.isFirstResponder)
        editor.selectedRange = NSRange(location: (editor.text as NSString).length, length: 0)
        editor.setMarkedText("xiugai", selectedRange: NSRange(location: 6, length: 0))
        XCTAssertNotNil(editor.markedTextRange)
        editor.unmarkText()
        editor.insertText("修改")
        XCTAssertTrue(editor.text.hasSuffix("xiugai修改"))
        XCTAssertTrue(editor.isFirstResponder)
    }
}

@MainActor
private final class VoiceEditorFixture: ObservableObject {
    let voice: VoiceInputAdapter
    @Published var draft = "原草稿"
    @Published var focused = false
    init(voice: VoiceInputAdapter) {
        self.voice = voice
        voice.onDraftUpdate = { [weak self] in self?.draft = $0 }
    }
}

private struct VoiceEditorHarness: View {
    @ObservedObject var fixture: VoiceEditorFixture
    @ObservedObject var voice: VoiceInputAdapter
    init(fixture: VoiceEditorFixture) {
        self.fixture = fixture
        self.voice = fixture.voice
    }
    var body: some View {
        VoiceComposerTextInput(text: $fixture.draft, isFocused: $fixture.focused, presentation: nil,
            state: voice.state, voice: voice)
            .frame(width: 350, height: 120)
    }
}

@MainActor
private func voiceHoldLabel(in view: UIView) -> UILabel? {
    if let label = view as? UILabel, label.accessibilityIdentifier == "composer-voice-hold" { return label }
    return view.subviews.lazy.compactMap { voiceHoldLabel(in: $0) }.first
}

@MainActor
private func voiceTextView(in view: UIView) -> UITextView? {
    if let editor = view as? UITextView { return editor }
    return view.subviews.lazy.compactMap { voiceTextView(in: $0) }.first
}


private struct VoiceLatencyHarness: View {
    @ObservedObject var voice: VoiceInputAdapter
    let appeared: () -> Void
    var disappeared: () -> Void = {}
    var body: some View {
        ZStack(alignment: .bottom) {
            Color.white
            if voice.state.overlayVisible {
                VoiceRipplePanel(state: voice.state)
                    .frame(height: 844 * VoiceInputInteraction.shared.OVERLAY_HEIGHT_FRACTION)
                    .transition(voiceOverlayTransition())
                    .onAppear(perform: appeared)
                    .onDisappear(perform: disappeared)
            }
        }
        .animation(voiceOverlayAnimation(active: voice.state.overlayVisible), value: voice.state.overlayVisible)
    }
}

/// 故意模拟一秒的同步硬件调用，用于发现主线程阻塞和启动期间取消的竞态。
private final class SlowVoiceRecorder: VoiceAudioCapturing, @unchecked Sendable {
    private let lock = NSLock()
    private var token: String?
    private var mainThread = false
    private var didDiscard = false
    private let startupFinished: XCTestExpectation
    private let stopped: XCTestExpectation
    init(startupFinished: XCTestExpectation, stopped: XCTestExpectation) {
        self.startupFinished = startupFinished
        self.stopped = stopped
    }
    var startedOnMain: Bool { lock.lock(); defer { lock.unlock() }; return mainThread }
    var discarded: Bool { lock.lock(); defer { lock.unlock() }; return didDiscard }
    func start(id: String, ready: @escaping @Sendable () -> Void, level: @escaping @Sendable (Float) -> Void, failure: @escaping @Sendable () -> Void) throws {
        lock.lock(); token = id; mainThread = Thread.isMainThread; lock.unlock()
        Thread.sleep(forTimeInterval: 1)
        startupFinished.fulfill()
        ready()
    }
    func stop(id: String, discard: Bool) -> Data? {
        lock.lock()
        guard token == id else { lock.unlock(); return nil }
        token = nil; didDiscard = discard
        lock.unlock()
        stopped.fulfill()
        return discard ? nil : Data(repeating: 0, count: 16000)
    }
}


/// 已授权的可控采集器：首批 PCM 就绪由测试单独触发。
private final class ControlledVoiceRecorder: VoiceAudioCapturing, @unchecked Sendable {
    private let lock = NSLock()
    private var ready: (@Sendable () -> Void)?
    private var level: (@Sendable (Float) -> Void)?
    private var token: String?
    private var starts = 0
    private var didDiscard = false
    let began: XCTestExpectation
    let stopped: XCTestExpectation
    init(began: XCTestExpectation, stopped: XCTestExpectation) { self.began = began; self.stopped = stopped }
    var startCount: Int { lock.lock(); defer { lock.unlock() }; return starts }
    var discarded: Bool { lock.lock(); defer { lock.unlock() }; return didDiscard }
    func start(id: String, ready: @escaping @Sendable () -> Void, level: @escaping @Sendable (Float) -> Void, failure: @escaping @Sendable () -> Void) throws {
        lock.lock(); self.ready = ready; self.level = level; token = id; starts += 1; lock.unlock()
        began.fulfill()
    }
    func hardwareStarted() {
        lock.lock(); let ready = ready; lock.unlock()
        ready?()
    }
    func stop(id: String, discard: Bool) -> Data? {
        lock.lock()
        guard token == id else { lock.unlock(); return nil }
        token = nil; didDiscard = discard; lock.unlock()
        stopped.fulfill()
        return nil
    }
}


private final class DelayedPreparationVoiceRecorder: VoiceAudioCapturing, @unchecked Sendable {
    private let gate = DispatchSemaphore(value: 0)
    private let lock = NSLock()
    private var starts = 0
    private let preparing: XCTestExpectation
    private let released: XCTestExpectation
    init(preparing: XCTestExpectation, released: XCTestExpectation) {
        self.preparing = preparing; self.released = released
    }
    var startCount: Int { lock.lock(); defer { lock.unlock() }; return starts }
    func finishPreparation() { gate.signal() }
    func prepare() throws { preparing.fulfill(); _ = gate.wait(timeout: .now() + 2) }
    func release() { released.fulfill() }
    func start(id: String, ready: @escaping @Sendable () -> Void, level: @escaping @Sendable (Float) -> Void, failure: @escaping @Sendable () -> Void) throws {
        lock.lock(); starts += 1; lock.unlock(); ready()
    }
    func stop(id: String, discard: Bool) throws -> Data? { nil }
}

private final class VoiceModeRecorderSpy: VoiceAudioCapturing, @unchecked Sendable {
    private let lock = NSLock()
    private var preparations = 0
    private var starts = 0
    private let prepared: XCTestExpectation
    private let released: XCTestExpectation
    init(prepared: XCTestExpectation, released: XCTestExpectation) {
        self.prepared = prepared
        self.released = released
    }
    var prepareCount: Int { lock.lock(); defer { lock.unlock() }; return preparations }
    var startCount: Int { lock.lock(); defer { lock.unlock() }; return starts }
    func prepare() throws {
        lock.lock(); preparations += 1; lock.unlock()
        prepared.fulfill()
    }
    func release() { released.fulfill() }
    func start(id: String, ready: @escaping @Sendable () -> Void, level: @escaping @Sendable (Float) -> Void, failure: @escaping @Sendable () -> Void) throws {
        lock.lock(); starts += 1; lock.unlock()
    }
    func stop(id: String, discard: Bool) throws -> Data? { nil }
}
