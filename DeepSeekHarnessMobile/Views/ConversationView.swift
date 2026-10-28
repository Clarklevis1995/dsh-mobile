import SwiftUI
import MarkdownUI
import PhotosUI
import UniformTypeIdentifiers
import UIKit
import WebKit
import DeepSeekHarnessShared

struct SlashCommandComposerPresentation: Equatable {
    let token: String
    let argument: String
    let hint: String?

    var showsHint: Bool {
        argument.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            !(hint?.isEmpty ?? true)
    }

    var hintLeadingWhitespace: String {
        argument.isEmpty ? " " : argument
    }
}

func slashCommandComposerPresentation(
    draft: String,
    token: String?,
    hint: String?
) -> SlashCommandComposerPresentation? {
    guard let token,
          draft == token || draft.hasPrefix("\(token) ") else { return nil }
    return SlashCommandComposerPresentation(
        token: token,
        argument: String(draft.dropFirst(token.count)),
        hint: hint
    )
}

/// 原生 down 即预采集；短按/失败也会收到结束通知，不能依赖 longpress 的 action 收尾。
final class VoiceHoldGestureRecognizer: UILongPressGestureRecognizer {
    var onTouchDown: (() -> Void)?
    var onTouchFinished: (() -> Void)?
    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
        super.touchesBegan(touches, with: event)
        onTouchDown?()
    }
    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent) {
        super.touchesEnded(touches, with: event)
        onTouchFinished?()
    }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent) {
        super.touchesCancelled(touches, with: event)
        onTouchFinished?()
    }
}

struct SlashCommandTextView: UIViewRepresentable {
    @AppStorage(AppFontScale.key) private var fontScale = 1.0
    @AppStorage(AppFontScale.followsSystemKey) private var fontFollowsSystem = false
    @Binding var text: String
    let presentation: SlashCommandComposerPresentation?
    let placeholder: String
    @Binding var isFocused: Bool
    func makeUIView(context: Context) -> CommandTextView {
        let textView = CommandTextView()
        textView.delegate = context.coordinator
        textView.backgroundColor = .clear
        textView.isScrollEnabled = false
        textView.textContainerInset = .zero
        textView.textContainer.lineFragmentPadding = 0
        textView.font = .appPreferredFont(forTextStyle: .body)
        textView.adjustsFontForContentSizeCategory = false
        textView.tintColor = .systemBlue
        textView.textColor = .label
        textView.apply(text: text, presentation: presentation, placeholder: placeholder)
        return textView
    }

    func updateUIView(_ textView: CommandTextView, context: Context) {
        textView.font = .appPreferredFont(forTextStyle: .body)
        context.coordinator.update(parent: self)
        textView.apply(text: text, presentation: presentation, placeholder: placeholder)
        context.coordinator.synchronizeFocus(textView)
    }

    func sizeThatFits(
        _ proposal: ProposedViewSize,
        uiView: CommandTextView,
        context: Context
    ) -> CGSize? {
        guard let width = proposal.width, width > 0 else { return nil }
        let lineHeight = uiView.font?.lineHeight ?? UIFont.appPreferredFont(forTextStyle: .body).lineHeight
        let minimumHeight = ceil(lineHeight)
        let maximumHeight = ceil(lineHeight * 5)
        let measured = uiView.sizeThatFits(
            CGSize(width: width, height: CGFloat.greatestFiniteMagnitude)
        ).height
        let height = min(max(measured, minimumHeight), maximumHeight)
        uiView.isScrollEnabled = measured > maximumHeight
        return CGSize(width: width, height: height)
    }

    static func dismantleUIView(_ uiView: CommandTextView, coordinator: Coordinator) {
        uiView.delegate = nil
    }

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, UITextViewDelegate {
        private var focusRevision = 0
        private var lastRenderedFocus: Bool?
        private var parent: SlashCommandTextView

        init(_ parent: SlashCommandTextView) { self.parent = parent }

        func update(parent: SlashCommandTextView) {
            self.parent = parent
        }

        func synchronizeFocus(_ textView: UITextView) {
            let requestedFocus = parent.isFocused
            // 只执行发生变化的显式请求；普通刷新可能仍携带原生编辑前的旧 Binding 值。
            guard lastRenderedFocus != requestedFocus else { return }
            lastRenderedFocus = requestedFocus
            focusRevision += 1
            let revision = focusRevision
            guard requestedFocus != textView.isFirstResponder else { return }
            DispatchQueue.main.async { [weak self, weak textView] in
                guard let self, let textView, self.focusRevision == revision,
                      self.parent.isFocused == requestedFocus, textView.window != nil else { return }
                if requestedFocus && !textView.isFirstResponder { textView.becomeFirstResponder() }
                else if !requestedFocus && textView.isFirstResponder { textView.resignFirstResponder() }
            }
        }

        func textViewDidChange(_ textView: UITextView) {
            guard parent.text != textView.text else { return }
            parent.text = textView.text
        }

        func textViewDidBeginEditing(_ textView: UITextView) {
            focusRevision += 1
            if !parent.isFocused { parent.isFocused = true }
        }

        func textViewDidEndEditing(_ textView: UITextView) {
            focusRevision += 1
            if parent.isFocused { parent.isFocused = false }
        }
    }
}

struct VoiceComposerTextInput: View {
    @Binding var text: String
    @Binding var isFocused: Bool
    let presentation: SlashCommandComposerPresentation?
    let state: SharedVoiceInputSnapshot
    let voice: VoiceInputAdapter
    var onHoldingChanged: (Bool) -> Void = { _ in }

    var body: some View {
        Group {
            if state.voiceMode {
                VoiceHoldInput(state: state, voice: voice, onHoldingChanged: onHoldingChanged)
                    .frame(maxWidth: .infinity, minHeight: 38)
            } else {
                SlashCommandTextView(
                    text: $text,
                    presentation: presentation,
                    placeholder: String(localized: String.LocalizationValue(state.hint)),
                    isFocused: $isFocused
                )
                .padding(.horizontal, 3)
                .frame(minHeight: 38, alignment: .top)
            }
        }
    }
}

/// 语音手势只安装在专用区域，文字编辑器保留原生选词、粘贴与长按菜单。
struct VoiceHoldInput: UIViewRepresentable {
    let state: SharedVoiceInputSnapshot
    let voice: VoiceInputAdapter
    var onHoldingChanged: (Bool) -> Void = { _ in }

    func makeUIView(context: Context) -> UILabel {
        let label = UILabel()
        label.isUserInteractionEnabled = true
        label.textAlignment = .center
        label.textColor = .label
        label.isAccessibilityElement = true
        label.accessibilityTraits = .button
        label.accessibilityIdentifier = "composer-voice-hold"
        let gesture = VoiceHoldGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.changed(_:)))
        gesture.minimumPressDuration = Double(VoiceInputInteraction.shared.LONG_PRESS_MILLISECONDS) / 1000
        gesture.onTouchDown = { [weak coordinator = context.coordinator] in
            guard let coordinator, coordinator.canBegin else { return }
            coordinator.parent.voice.touchDown()
        }
        gesture.onTouchFinished = { [weak coordinator = context.coordinator] in coordinator?.parent.voice.touchFinished() }
        gesture.delegate = context.coordinator
        label.addGestureRecognizer(gesture)
        return label
    }

    func updateUIView(_ label: UILabel, context: Context) {
        context.coordinator.parent = self
        label.text = String(localized: "按住说话")
        label.accessibilityLabel = label.text
        label.font = .appPreferredFont(forTextStyle: .body)
    }

    // 保留整行触摸宽度，但高度只取单行内容，避免吸收会话页面的剩余空间。
    func sizeThatFits(_ proposal: ProposedViewSize, uiView: UILabel, context: Context) -> CGSize? {
        guard let width = proposal.width, width > 0 else { return nil }
        return CGSize(width: width, height: max(38, ceil(uiView.font.lineHeight)))
    }

    static func dismantleUIView(_ uiView: UILabel, coordinator: Coordinator) {
        coordinator.parent.voice.touchFinished()
    }

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        var parent: VoiceHoldInput
        private var origin = CGPoint.zero
        init(_ parent: VoiceHoldInput) { self.parent = parent }
        var canBegin: Bool {
            let state = parent.voice.state
            return state.voiceMode && (!state.active || state.inputBlocked || state.touchPending)
        }

        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
            origin = touch.location(in: nil)
            return canBegin
        }

        @objc func changed(_ gesture: UILongPressGestureRecognizer) {
            switch gesture.state {
            case .began: parent.onHoldingChanged(true); parent.voice.press()
            case .changed: parent.voice.move(gesture.location(in: nil).y - origin.y)
            case .ended: parent.voice.release(); parent.onHoldingChanged(false)
            case .cancelled, .failed: parent.voice.cancel(); parent.onHoldingChanged(false)
            default: break
            }
        }
    }
}

final class CommandTextView: UITextView {
    private var renderedText = ""
    private var renderedPresentation: SlashCommandComposerPresentation?
    private var renderedPlaceholder = ""
    private var renderedFontSize: CGFloat?
    private var lastDrawnSize: CGSize = .zero

    // 占位提示是在 draw(_:) 里自绘的。UIView 默认 contentMode 是 scaleToFill，
    // 尺寸变化时系统只会拉伸旧的绘制结果而不会重绘：折叠屏从展开变为折叠后，
    // 输入框变窄，提示文字就被横向压扁。尺寸一变就主动重绘。
    override func layoutSubviews() {
        super.layoutSubviews()
        if bounds.size != lastDrawnSize {
            lastDrawnSize = bounds.size
            setNeedsDisplay()
        }
    }

    func apply(text: String, presentation: SlashCommandComposerPresentation?, placeholder: String) {
        let fontChanged = renderedFontSize != font?.pointSize
        renderedFontSize = font?.pointSize
        let needsTextSynchronization = self.text != text
        let textChanged = renderedText != text
        let presentationChanged = renderedPresentation != presentation
        let placeholderChanged = renderedPlaceholder != placeholder
        guard needsTextSynchronization || textChanged || presentationChanged || placeholderChanged || fontChanged else { return }

        let selection = selectedRange
        let attributes = baseAttributes()

        // 用户输入已经存在于 UITextView 的 textStorage 中。SwiftUI 将 Binding
        // 回传到这里时不能再次替换 attributedText，否则会打断第一响应者和
        // marked-text（中文输入法）状态。只有菜单选中等真正的外部赋值才同步文本。
        if needsTextSynchronization {
            attributedText = NSAttributedString(string: text, attributes: attributes)
            selectedRange = NSRange(
                location: min(selection.location, (text as NSString).length),
                length: 0
            )
        }

        typingAttributes = attributes
        renderedText = text
        renderedPresentation = presentation
        renderedPlaceholder = placeholder
        if needsTextSynchronization || presentationChanged || fontChanged {
            applyCommandHighlight(presentation)
        }
        setNeedsDisplay()
        invalidateIntrinsicContentSize()
    }

    private func applyCommandHighlight(_ presentation: SlashCommandComposerPresentation?) {
        let fullRange = NSRange(location: 0, length: textStorage.length)
        let selection = selectedRange
        textStorage.beginEditing()
        textStorage.setAttributes(baseAttributes(), range: fullRange)
        if let presentation, text.hasPrefix(presentation.token) {
            let tokenLength = min((presentation.token as NSString).length, textStorage.length)
            if tokenLength > 0 {
                textStorage.addAttribute(
                    .foregroundColor,
                    value: UIColor.systemBlue,
                    range: NSRange(location: 0, length: tokenLength)
                )
            }
        }
        textStorage.endEditing()
        selectedRange = selection
    }

    override func draw(_ rect: CGRect) {
        super.draw(rect)
        if renderedText.isEmpty {
            (renderedPlaceholder as NSString).draw(
                at: CGPoint(x: textContainerInset.left, y: textContainerInset.top),
                withAttributes: [
                    .font: font ?? UIFont.appPreferredFont(forTextStyle: .body),
                    .foregroundColor: UIColor.secondaryLabel.withAlphaComponent(0.62)
                ]
            )
            return
        }
        guard let presentation = renderedPresentation,
              presentation.showsHint,
              let hint = presentation.hint else { return }

        let textLength = (renderedText as NSString).length
        let glyphRange = layoutManager.glyphRange(
            forCharacterRange: NSRange(location: 0, length: textLength),
            actualCharacterRange: nil
        )
        let usedRect = layoutManager.boundingRect(forGlyphRange: glyphRange, in: textContainer)
        let needsLeadingSpace = presentation.argument.isEmpty
        let hintText = (needsLeadingSpace ? " " : "") + hint
        let attributes: [NSAttributedString.Key: Any] = [
            .font: font ?? UIFont.appPreferredFont(forTextStyle: .body),
            .foregroundColor: UIColor.secondaryLabel.withAlphaComponent(0.62)
        ]
        (hintText as NSString).draw(
            at: CGPoint(x: textContainerInset.left + usedRect.maxX, y: textContainerInset.top + usedRect.minY),
            withAttributes: attributes
        )
    }

    private func baseAttributes() -> [NSAttributedString.Key: Any] {
        [
            .font: font ?? UIFont.appPreferredFont(forTextStyle: .body),
            .foregroundColor: UIColor.label
        ]
    }
}

struct ConversationView: View {
    @Environment(\.dismiss) private var dismissConversation
    @EnvironmentObject private var store: AppStore
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Binding var activeView: Int
    var topContentInset: CGFloat = 0
    @State private var draft = ""
    @State private var memberDrafts: [String: String] = [:]
    @State private var memberImages: [String: [GatewayOutgoingImage]] = [:]
    @State private var voiceSnapshot: SharedVoiceInputSnapshot?
    @State private var voiceTouchActive = false
    @State private var voiceToast: String?
    @State private var showsPhotoPicker = false
    @State private var showsFilePicker = false
    @State private var showsFileUploadUpgradeAlert = false
    @State private var filePickerSessionID: String?
    @State private var selectedPhotoItems: [PhotosPickerItem] = []
    @State private var pendingImages: [GatewayOutgoingImage] = []
    @State private var isImportingImages = false
    @State private var showsContextUsage = false
    @State private var showsSessionStats = false
    @State private var showsSessionStatsPopover = false
    @State private var tasksExpanded = false
    @State private var showsGoalEditor = false
    @State private var goalObjectiveDraft = ""
    @State private var confirmsGoalClear = false
    @State private var isPinnedToBottom = true
    @State private var queueExpanded = false
    @State private var submittedQueueDraft: String?
    @State private var submittedQueueImages: [GatewayOutgoingImage] = []
    @State private var submittedQueueSessionID: String?
    @State private var composerHeight: CGFloat = 168
    @State private var viewportScrollToBottomToken = 0
    @State private var viewportProxy = ConversationViewportProxy()
    @State private var expandedConversationNodeIDsBySession: [String: Set<String>] = [:]
    @State private var isPreparingHistoryPresentation = false
    @State private var historyPresentationSessionID: String?
    @State private var viewportHasConversationContent = false
    @State private var viewportContentSessionID: String?
    @State private var bottomSafeAreaInset: CGFloat = 0
    @State private var voiceWindowHeight: CGFloat = 0
    // 输入主体是 UIKit UITextView，不需要 SwiftUI FocusState 参与焦点仲裁。
    // 普通 State 作为单向的显式聚焦/失焦请求，UITextView 自己持有第一响应者。
    @State private var composerIsFocused = false
    @State private var showsAgentPresetMenu = false
    @ScaledMetric(relativeTo: .caption) private var composerCapsuleHeight: CGFloat = 34
    private let conversationBottomClearance: CGFloat = 22

    var body: some View {
        ZStack {
            conversationBackground.ignoresSafeArea()
            VStack(spacing: 0) {
                if store.isViewingTeamMember {
                    Text("团队 › \(store.selectedSession?.title ?? "成员")" + (store.selectedMemberCanSend ? "" : " · 只读"))
                        .appFont(.caption).foregroundStyle(.secondary).lineLimit(1).padding(.bottom, 4)
                        .padding(.top, topContentInset)
                }
                PersistentSessionPager(selection: $activeView) {
                    chat
                } trajectory: {
                    TrajectoryView(
                        sessionId: store.selectedSessionId,
                        timeline: store.trajectoryTimeline(for: store.selectedSessionId),
                        isActive: activeView == 1
                    )
                    .padding(.top, pageTopInset)
                }
                .overlay(alignment: .top) {
                    SessionPageIndicator(selection: activeView, sessionID: store.selectedSessionId)
                        .padding(.top, pageTopInset + 20)
                        .allowsHitTesting(false)
                        .accessibilityHidden(true)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .overlay(alignment: .top) {
            if topContentInset > 0 {
                ConversationTopGlass()
                    .frame(height: topContentInset + 18)
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
            }
        }
        .ignoresSafeArea(.container, edges: .bottom)
        .background {
            WindowBottomSafeAreaReader(bottomInset: $bottomSafeAreaInset, windowHeight: $voiceWindowHeight)
        }
        .foregroundStyle(.primary)
        .onChangeCompat(of: activeView) { _, view in
            if view != 0 { composerIsFocused = false }
            showsSessionStatsPopover = false
            showsAgentPresetMenu = false
        }
        .onChangeCompat(of: store.selectedSessionId) { _, _ in
            showsSessionStatsPopover = false
            showsAgentPresetMenu = false
        }
        .onDisappear {
            if store.conversationFileIsOpening { store.cancelWorkspaceFileDownload() }
        }
        .sheet(item: conversationPreviewBinding) { request in
            WorkspaceFilePreviewLoadingSheet(store: store, request: request)
        }
        .sheet(isPresented: $showsSessionStats) {
            SessionStatsSheet(
                snapshot: store.selectedSessionStatsSnapshot,
                sessionTitle: store.selectedSession?.title ?? String(localized: "session.new.fallback", defaultValue: "新建 DeepSeek Harness")
            )
        }
    }

    private var conversationPreviewBinding: Binding<WorkspaceFilePreviewRequest?> {
        Binding(
            get: {
                guard store.conversationFilePreviewRequested,
                      store.workspaceFilePreviewRequest?.sessionID == store.selectedSessionId else { return nil }
                return store.workspaceFilePreviewRequest
            },
            set: { if $0 == nil { store.cancelWorkspaceFileDownload() } }
        )
    }

    private var conversationBackground: Color {
        Color(uiColor: .systemBackground)
    }

    private var glassTint: Color {
        Color(uiColor: .secondarySystemBackground)
            .opacity(colorScheme == .dark ? 0.52 : 0.48)
    }

    private var glassEdge: Color {
        colorScheme == .dark
            ? .white.opacity(0.13)
            : .white.opacity(0.56)
    }

    private var glassShadow: Color {
        .black.opacity(colorScheme == .dark ? 0.34 : 0.10)
    }

    private var pageTopInset: CGFloat {
        store.isViewingTeamMember ? 0 : topContentInset
    }

    private var chat: some View {
        let sessionID = store.selectedSessionId
        return ZStack(alignment: .bottom) {
            ConversationViewport(
                proxy: viewportProxy,
                sessionID: sessionID,
                cacheScope: store.gatewayLocalID,
                searchTargetID: searchTargetViewportID,
                timeline: store.displayedConversationTimeline(for: sessionID ?? "__empty__"),
                supplementalEntries: supplementalViewportEntries,
                makeEntries: { items in
                    makeConversationViewportEntries(from: items)
                },
                topInset: pageTopInset + 8,
                bottomInset: composerHeight + conversationBottomClearance,
                scrollToBottomToken: viewportScrollToBottomToken,
                onContentAvailabilityChanged: { sessionID, hasContent in
                    Task { @MainActor in
                        guard sessionID == store.selectedSessionId else { return }
                        viewportContentSessionID = sessionID
                        viewportHasConversationContent = hasContent
                    }
                },
                onPinnedToBottomChanged: { pinned in
                    isPinnedToBottom = pinned
                },
                onScrolledFromTopChanged: { _ in },
                onBottomAlignmentCompleted: {
                    guard isPreparingHistoryPresentation,
                          historyPresentationSessionID == store.selectedSessionId,
                          !isLoadingSelectedHistory else { return }
                    isPreparingHistoryPresentation = false
                },
                onApproachingTop: {
                    guard let sessionID = store.selectedSessionId,
                          let consumerID = viewportProxy.historyPresentationConsumerID else { return }
                    store.requestOlderHistoryPresentation(sessionID: sessionID, consumerID: consumerID,
                        readingWindow: viewportProxy.historyReadingWindowSize)
                },
                onHistoryPresentationConsumerAttached: { sessionID, consumer in
                    store.attachHistoryPresentationConsumer(sessionID: sessionID, consumer: consumer)
                },
                onHistoryPresentationConsumerDetached: { sessionID, consumerID in
                    store.detachHistoryPresentationConsumer(sessionID: sessionID, consumerID: consumerID)
                },
                onHistoryPresented: { sessionID, consumerID, stamp in
                    store.acknowledgeHistoryPresentation(sessionID: sessionID, consumerID: consumerID, stamp: stamp)
                }
            )
            // Session 是 UIKit viewport 的身份边界。切换时直接销毁旧
            // controller，不能让上一 Session 的 diffable snapshot 参与新页面首帧。
            .id(sessionID ?? "__empty__")
            .padding(.horizontal, 20)
            .opacity(shouldShowHistoryPresentationMask ? 0 : 1)
            .readableContentWidth()

            if !hasVisibleConversationContent, let error = selectedHistoryError {
                historyFailureState(error)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .padding(.top, pageTopInset)
                    .padding(.bottom, composerHeight)
                    .readableContentWidth()
                    .zIndex(1)
            } else if shouldShowHistoryPresentationMask {
                historyPresentationMask
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .padding(.top, pageTopInset)
                    .padding(.bottom, composerHeight)
                    .readableContentWidth()
                    .zIndex(1)
            } else if !hasVisibleConversationContent {
                Group {
                    if isLoadingSelectedHistory { historyLoadingState }
                    else { emptyState }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .padding(.top, pageTopInset)
                .padding(.bottom, composerHeight)
                .readableContentWidth()
            }

            // 底部材质铺满页面，避免在正文宽度边界留下垂直截断。
            composerBackdrop
                .frame(height: composerHeight + 28)
                .allowsHitTesting(false)

            VStack(spacing: 0) {
                Group {
                    if shouldShowScrollToBottom {
                        scrollToBottomButton {
                            viewportScrollToBottomToken &+= 1
                        }
                        .padding(.bottom, 8)
                        .transition(.scale(scale: 0.85).combined(with: .opacity))
                    }
                }
                .animation(.easeOut(duration: 0.16), value: shouldShowScrollToBottom)

                VStack(alignment: .leading, spacing: 0) {
                    if let request = store.selectedPendingApprovalRequest {
                        ApprovalRequestView(
                            request: request,
                            status: store.approvalRequestStatuses[request.rpcId] ?? .idle,
                            commandPreview: store.commandPreview(for: request),
                            details: store.approvalArguments(for: request),
                            onDecision: { store.respondToApproval(request, outcome: $0) }
                        )
                        .id(request.rpcId)
                        .padding(.bottom, composerBottomPadding)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                    } else if let request = store.selectedPendingQuestionRequest {
                        HumanQuestionView(
                            request: request,
                            status: store.questionRequestStatuses[request.rpcId] ?? .idle,
                            onAnswer: { store.answerQuestion(request, answers: $0) },
                            onCancel: { store.cancelQuestion(request) }
                        )
                        .id(request.rpcId)
                        .padding(.bottom, composerBottomPadding)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                    } else {
                        // Approval and human-question cards are high-priority
                        // interaction layers. Lower-priority composer chrome is
                        // only mounted when neither one is waiting, so task and
                        // goal panels cannot push an action card off screen.
                        HStack(spacing: 8) {
                            sessionAgentPresetControl
                            if let snapshot = store.selectedSessionStatsSnapshot {
                                sessionStatsBanner(snapshot)
                            } else {
                                Spacer(minLength: 0)
                            }
                        }
                        .padding(.horizontal, 14)
                        .padding(.bottom, 6)
                        if let tasks = store.selectedTaskProjection?.todos,
                           let goal = store.selectedGoalProjection?.goal {
                            TaskGoalPanels(
                                tasks: tasks,
                                goal: goal,
                                isMutatingGoal: store.goalMutationKind != nil,
                                tasksExpanded: $tasksExpanded,
                                onPauseResume: {
                                    if goal.goal.phase == "active" { store.pauseGoal() }
                                    else { store.resumeGoal() }
                                },
                                onEdit: {
                                    goalObjectiveDraft = goal.goal.objective
                                    showsGoalEditor = true
                                },
                                onClear: { confirmsGoalClear = true }
                            )
                            .padding(.horizontal, 14)
                            .padding(.bottom, 8)
                        } else if let tasks = store.selectedTaskProjection?.todos {
                            TaskGoalPanels(
                                tasks: tasks,
                                goal: nil,
                                isMutatingGoal: false,
                                tasksExpanded: $tasksExpanded,
                                onPauseResume: {},
                                onEdit: {},
                                onClear: {}
                            )
                            .padding(.horizontal, 14)
                            .padding(.bottom, 8)
                        } else if let goal = store.selectedGoalProjection?.goal {
                            TaskGoalPanels(
                                tasks: nil,
                                goal: goal,
                                isMutatingGoal: store.goalMutationKind != nil,
                                tasksExpanded: $tasksExpanded,
                                onPauseResume: {
                                    if goal.goal.phase == "active" { store.pauseGoal() }
                                    else { store.resumeGoal() }
                                },
                                onEdit: {
                                    goalObjectiveDraft = goal.goal.objective
                                    showsGoalEditor = true
                                },
                                onClear: { confirmsGoalClear = true }
                            )
                            .padding(.horizontal, 14)
                            .padding(.bottom, 8)
                        }
                        VStack(spacing: 10) {
                            slashCommandMenus
                                .padding(.horizontal, 14)
                            composer
                        }
                    }
                }
                .background {
                    GeometryReader { composerGeometry in
                        Color.clear.preference(
                            key: ComposerHeightPreferenceKey.self,
                            value: composerGeometry.size.height
                        )
                    }
                }
            }
            // 只约束控件的排版宽度，阴影仍可绘制到页面两侧留白。
            .readableContentWidth()
            .opacity(voiceState.overlayVisible ? 0 : 1)
            .zIndex(2)
            ZStack {
                if voiceState.overlayVisible {
                    VoiceRipplePanel(state: voiceState)
                    .transition(voiceOverlayTransition(reduceMotion: reduceMotion))
                }
            }
            .frame(height: max(1, voiceWindowHeight * VoiceInputInteraction.shared.OVERLAY_HEIGHT_FRACTION))
            .ignoresSafeArea(.container, edges: .bottom)
            .allowsHitTesting(voiceState.overlayVisible && !voiceTouchActive)
            .animation(voiceOverlayAnimation(active: voiceState.overlayVisible, reduceMotion: reduceMotion), value: voiceState.overlayVisible)
            .zIndex(3)
        }
        .overlayPreferenceValue(SessionStatsPopoverAnchorKey.self) { anchor in
            if let anchor,
               let snapshot = store.selectedSessionStatsSnapshot {
                GeometryReader { geometry in
                    ComposerPopoverOverlay(
                        sourceFrame: geometry[anchor],
                        availableSize: geometry.size,
                        isPresented: showsSessionStatsPopover,
                        onDismiss: { showsSessionStatsPopover = false }
                    ) {
                        SessionStatsPopover(snapshot: snapshot) {
                            showsSessionStatsPopover = false
                            showsSessionStats = true
                        }
                    }
                }
            }
        }
        .overlayPreferenceValue(SessionAgentPresetPopoverAnchorKey.self) { anchor in
            if let anchor {
                GeometryReader { geometry in
                    ComposerPopoverOverlay(
                        sourceFrame: geometry[anchor],
                        availableSize: geometry.size,
                        isPresented: showsAgentPresetMenu,
                        onDismiss: { showsAgentPresetMenu = false },
                        preferredWidth: 292,
                        alignsToTrailing: false,
                        maximumContentHeight: 380
                    ) {
                        sessionAgentPresetMenuContent
                    }
                }
            }
        }
        .overlay(alignment: .bottom) {
            if let voiceToast {
                Text(LocalizedStringKey(voiceToast))
                    .appFont(.system(size: 14, weight: .medium))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 18).padding(.vertical, 11)
                    .background(Color.black.opacity(0.78), in: Capsule())
                    .padding(.bottom, composerHeight + 16)
                    .allowsHitTesting(false)
                    .accessibilityIdentifier("voice-input-toast")
            }
        }
        .onPreferenceChange(ComposerHeightPreferenceKey.self) { height in
            guard height > 0, abs(height - composerHeight) > 0.5 else { return }
            composerHeight = height
        }
        .onChangeCompat(of: composerIsFocused) { _, isFocused in
            store.voiceInput.keyboardChanged(isFocused)
            guard isFocused else { return }
            showsSessionStatsPopover = false
            viewportScrollToBottomToken &+= 1
        }
        .onChangeCompat(of: store.selectedPendingQuestionRequest?.rpcId) { _, rpcId in
            guard rpcId != nil else { return }
            composerIsFocused = false
            if isPinnedToBottom { viewportScrollToBottomToken &+= 1 }
        }
        .onChangeCompat(of: store.selectedPendingApprovalRequest?.rpcId) { _, rpcId in
            guard rpcId != nil else { return }
            composerIsFocused = false
            if isPinnedToBottom { viewportScrollToBottomToken &+= 1 }
        }
        .alert("编辑目标", isPresented: $showsGoalEditor) {
            TextField("目标", text: $goalObjectiveDraft, axis: .vertical)
            Button("取消", role: .cancel) {}
            Button("保存") { store.editGoal(objective: goalObjectiveDraft.trimmingCharacters(in: .whitespacesAndNewlines)) }
                .disabled(goalObjectiveDraft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
        .alert("删除当前目标？", isPresented: $confirmsGoalClear) {
            Button("取消", role: .cancel) {}
            Button("删除", role: .destructive) { store.clearGoal() }
        } message: {
            Text("删除后，智能体不再持有这个持续目标。")
        }
        .onChangeCompat(of: isLoadingSelectedHistory) { wasLoading, isLoading in
            guard historyPresentationSessionID == store.selectedSessionId,
                  isPreparingHistoryPresentation,
                  wasLoading,
                  !isLoading else { return }
            guard !conversationItems.isEmpty else {
                isPreparingHistoryPresentation = false
                return
            }
            // History has finished publishing. Ask UIKit to commit the final
            // self-sized layout at the bottom; the mask is removed only from
            // `onBottomAlignmentCompleted` above.
            viewportScrollToBottomToken &+= 1
        }
        .alert("语音输入暂不可用", isPresented: Binding(
            get: { voiceState.alertMessage != nil },
            set: { if !$0 { store.voiceInput.dismissAlert() } }
        )) {
            Button("知道了", role: .cancel) { store.voiceInput.dismissAlert() }
        } message: {
            Text(LocalizedStringKey(voiceState.alertMessage ?? ""))
        }
        .onReceive(store.voiceInput.$state) { voiceSnapshot = $0 }
        .onReceive(store.voiceInput.$toastMessage) { voiceToast = $0 }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardDidHideNotification)) { _ in
            // 键盘隐藏可能来自上一轮动画或其他输入框，不能用它发出失焦请求。
            // 当前编辑会话由 UITextViewDelegate 回报；只在已结束编辑时同步提示状态。
            guard !composerIsFocused else { return }
            store.voiceInput.keyboardChanged(false)
        }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.willResignActiveNotification)) { _ in
            voiceTouchActive = false
            store.voiceInput.cancel()
        }
        .onAppear {
            store.voiceInput.onDraftUpdate = { draft = $0 }
            store.voiceInput.open(context: store.selectedSessionId ?? "new-session", draft: draft)
            store.voiceInput.keyboardChanged(composerIsFocused)
        }
        .onDisappear {
            voiceTouchActive = false
            store.voiceInput.leave()
            store.leaveSessionAgentPreset()
        }
        .onChangeCompat(of: store.selectedSessionId) { previous, current in
            if let previous {
                memberDrafts[previous] = draft
                memberImages[previous] = pendingImages
            }
            draft = current.flatMap { memberDrafts[$0] } ?? ""
            pendingImages = current.flatMap { memberImages[$0] } ?? []
            selectedPhotoItems = []
            store.voiceInput.open(context: current ?? "new-session", draft: draft)
            if previous != nil && current == nil { dismissConversation() }
        }
        .onChangeCompat(of: store.sessionAgentPreset.canSelect) { _, canSelect in
            if !canSelect { showsAgentPresetMenu = false }
        }
        .task(id: store.selectedSessionId) {
            store.refreshSessionAgentPreset()
            // 每次进入会话先收起历史任务；用户在当前会话手动展开后不会被后续推送重置。
            tasksExpanded = false
            guard let sessionID = store.selectedSessionId else {
                historyPresentationSessionID = nil
                isPreparingHistoryPresentation = false
                return
            }
            historyPresentationSessionID = sessionID
            let hasUsableLocalContent = !conversationItems.isEmpty
            // Cached content is immediately presentable. A background refresh
            // must never replace it with a full-screen loading mask; the mask
            // is reserved for a genuinely cold session with no local rows.
            isPreparingHistoryPresentation = isLoadingSelectedHistory && !hasUsableLocalContent
            if hasUsableLocalContent {
                viewportScrollToBottomToken &+= 1
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 14) {
            WhalenessOrcaIcon(size: 52)
                .foregroundStyle(colorScheme == .dark ? Color.white : Color.black)
            Text("操作远端 DSH Agent").appFont(.title3.weight(.semibold))
            Text("发送任务后，工具调用、推理进度和最终回复会通过 Mobile Gateway 实时返回。")
                .appFont(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 28)
    }

    private var selectedHistoryError: String? {
        store.selectedSessionId.flatMap { store.historyLoadErrors[$0] }
    }

    private func historyFailureState(_ error: String) -> some View {
        VStack(spacing: 14) {
            Image(systemName: "exclamationmark.triangle").appFont(.largeTitle).foregroundStyle(.orange)
            Text(error.contains("尚未缓存") ? "离线内容不可用" : "历史记录加载失败").appFont(.title3.weight(.semibold))
            Text(error.contains("refuses this format")
                 ? "Host 无法读取旧格式会话，请修复或升级 Host 后重试。原始记录未修改。"
                 : error)
                .appFont(.subheadline).foregroundStyle(.secondary)
                .multilineTextAlignment(.center).lineLimit(6)
            Button("重新加载历史") {
                if let id = store.selectedSessionId { store.loadHistory(for: id) }
            }
            .buttonStyle(.bordered)
            .disabled(!store.gateway.state.isConnected)
        }
        .padding(.horizontal, 28)
        .accessibilityIdentifier("history-load-failure")
    }

    private var isLoadingSelectedHistory: Bool {
        guard let id = store.selectedSessionId else { return false }
        return store.isPreparingConversation(id)
    }

    private var isLoadingOlderSelectedHistory: Bool {
        guard let id = store.selectedSessionId else { return false }
        return store.isShowingSearchContext ? store.searchContextLoadingOlder : store.historyLoadingOlderSessionIds.contains(id)
    }

    private var shouldShowScrollToBottom: Bool {
        !isPreparingHistoryPresentation && !conversationItems.isEmpty && !isPinnedToBottom
    }

    /// Timeline 的高频发布直接进入 UIKit，不会让整个 SwiftUI View
    /// 在每个 token 重算。Viewport 只在空/非空边界通知这里，
    /// 确保首批历史行可见时立即撤掉全屏遮罩。
    private var hasVisibleConversationContent: Bool {
        if store.selectedSession?.isRunning == true && !isLoadingSelectedHistory {
            return true
        }
        guard historyPresentationSessionID == store.selectedSessionId else {
            // Session 身份刚切换时，不得沿用上一个 viewport 的可用性。
            return !conversationItems.isEmpty
        }
        return (viewportContentSessionID == store.selectedSessionId && viewportHasConversationContent) || !conversationItems.isEmpty
    }

    private var shouldShowHistoryPresentationMask: Bool {
        isPreparingHistoryPresentation
            && (viewportContentSessionID != store.selectedSessionId || !viewportHasConversationContent)
    }

    private var historyLoadingState: some View {
        ZStack {
            // 加载态必须是完整不透明的呈现层；即使 SwiftUI/UIKit 的边缘
            // 通知相差一个 layout pass，也不允许它与旧 cell 同时可见。
            conversationBackground
            VStack(spacing: 14) {
                ProgressView()
                    .controlSize(.large)
                    .tint(DSHColor.ocean)
                Text("正在加载历史记录").appFont(.title3.weight(.semibold))
                Text(historyProgressText)
                    .appFont(.subheadline)
                    .foregroundStyle(.secondary)
            }
            .padding(.horizontal, 28)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var historyPresentationMask: some View {
        ZStack {
            conversationBackground
            VStack(spacing: 14) {
                ProgressView()
                    .controlSize(.large)
                    .tint(DSHColor.ocean)
                Text(isLoadingSelectedHistory ? String(localized: "正在加载历史记录") : String(localized: "正在准备会话"))
                    .appFont(.title3.weight(.semibold))
                Text(isLoadingSelectedHistory ? historyProgressText : String(localized: "正在定位到最新消息…"))
                    .appFont(.subheadline)
                    .foregroundStyle(.secondary)
            }
            .multilineTextAlignment(.center)
            .padding(.horizontal, 28)
        }
        .accessibilityElement(children: .combine)
    }

    private var historyLoadingBanner: some View {
        HStack(spacing: 9) {
            ProgressView().tint(DSHColor.ocean)
            Text(historyProgressText)
                .appFont(.caption)
                .foregroundStyle(.secondary)
            Spacer()
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
    }

    private var historyProgressText: String {
        guard let id = store.selectedSessionId,
              let progress = store.historyLoadProgress[id] else {
            return String(localized: "history.syncing.gateway", defaultValue: "正在从 Mobile Gateway 同步会话内容…")
        }
        if isLoadingOlderSelectedHistory {
            return progress.loaded > 0
                ? String(localized: "history.loading.loaded", defaultValue: "正在加载更早记录 · 已同步 \(progress.loaded) 个事件")
                : String(localized: "history.loading.earlier", defaultValue: "正在加载更早记录…")
        }
        guard let total = progress.total else {
            return progress.loaded > 0
                ? String(localized: "history.autoloading.loaded", defaultValue: "正在自动加载更早记录 · 已同步 \(progress.loaded) 个事件")
                : String(localized: "history.syncing.gateway", defaultValue: "正在从 Mobile Gateway 同步会话内容…")
        }
        return String(localized: "history.progress.count", defaultValue: "正在加载历史记录 · \(progress.loaded)/\(total)")
    }

    @ViewBuilder
    private func scrollToBottomButton(action: @escaping () -> Void) -> some View {
        let isGenerating = store.selectedSession?.isRunning == true
        if #available(iOS 26.0, *) {
            Button(action: action) {
                scrollToBottomButtonIcon(isGenerating: isGenerating)
            }
            .buttonStyle(.glass)
            .buttonBorderShape(.circle)
            .accessibilityLabel(isGenerating ? String(localized: "a11y.generating.jump-latest", defaultValue: "正在生成，滚动到最新消息") : String(localized: "a11y.jump.latest", defaultValue: "滚动到最新消息"))
        } else {
            Button(action: action) {
                scrollToBottomButtonIcon(isGenerating: isGenerating)
                    .glassSurface(radius: 18)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(isGenerating ? String(localized: "a11y.generating.jump-latest", defaultValue: "正在生成，滚动到最新消息") : String(localized: "a11y.jump.latest", defaultValue: "滚动到最新消息"))
        }
    }

    @ViewBuilder
    private func scrollToBottomButtonIcon(isGenerating: Bool) -> some View {
        Group {
            if isGenerating {
                ProgressView()
                    .controlSize(.regular)
                    .tint(DSHColor.ocean)
            } else {
                Image(systemName: "arrow.down")
                    .appFont(.system(size: 15, weight: .semibold))
            }
        }
        .frame(width: 36, height: 36)
    }

    private var composer: some View {
        VStack(spacing: store.selectedQueueItems.isEmpty ? 0 : -24) {
            if !store.selectedQueueItems.isEmpty {
                queueDock
                    .padding(.horizontal, 14)
                    .transition(reduceMotion ? .opacity : .move(edge: .bottom).combined(with: .opacity))
            }
            composerCard
                .zIndex(1)
        }
        .animation(queueLayoutAnimation, value: store.selectedQueueItems.isEmpty)
        .animation(queueLayoutAnimation, value: queueExpanded)
    }

    private var queueLayoutAnimation: Animation {
        reduceMotion ? .easeOut(duration: 0.15) : .easeInOut(duration: 0.25)
    }

    private var voiceState: SharedVoiceInputSnapshot { voiceSnapshot ?? store.voiceInput.state }

    private var attachmentMenu: some View {
        Menu {
            Button { showsPhotoPicker = true } label: {
                Label("图片", systemImage: "photo")
            }
            .disabled(!store.supportsImages || pendingImages.count >= 20 || isImportingImages)
            Button {
                guard store.fileUploads.supported else {
                    showsFileUploadUpgradeAlert = true
                    return
                }
                filePickerSessionID = store.selectedSessionId
                showsFilePicker = true
            } label: {
                Label("文件", systemImage: "doc")
            }
            .disabled(store.isViewingTeamMember || store.fileUploads.drafts.count >= 20 || store.fileUploads.preparing)
        } label: {
            Image(systemName: "plus.circle")
                .appFont(.system(size: 21, weight: .regular))
                // SF Symbol 自身含侧边留白，补偿后与语音圆按钮保持相同可见边距。
                .offset(x: -2)
                // 可见图标贴齐输入框的内容边缘，点击区域仍保留 32pt。
                .frame(width: 32, height: 32, alignment: .leading)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .disabled(store.messageSubmissionPending)
        .accessibilityLabel("添加附件")
        .photosPicker(isPresented: $showsPhotoPicker, selection: $selectedPhotoItems,
            maxSelectionCount: max(1, 20 - pendingImages.count), matching: .images)
        .fileImporter(isPresented: $showsFilePicker, allowedContentTypes: [.item], allowsMultipleSelection: true,
            onCompletion: importPickedFiles)
        .alert("请升级网关", isPresented: $showsFileUploadUpgradeAlert) {
            Button("好", role: .cancel) {}
        } message: {
            Text("当前网关不支持文件上传，请升级到支持文件上传的 Mobile Gateway，并重新连接。")
        }
    }

    private func importPickedFiles(_ result: Result<[URL], Error>) {
        store.voiceInput.setVoiceMode(false)
        guard store.selectedSessionId == filePickerSessionID else { return }
        switch result {
        case .success(let urls):
            Task {
                guard await store.ensureFileUploadSession() else { return }
                store.fileUploads.select(sessionID: store.selectedSessionId, allowed: !store.isViewingTeamMember)
                await store.fileUploads.importFiles(urls)
            }
        case .failure(let error): store.lastError = error.localizedDescription
        }
    }

    private var composerCard: some View {
        let slashPresentation = slashCommandComposerPresentation(
            draft: draft,
            token: store.slashCommands.commandToken,
            hint: store.slashCommands.argumentHint
        )
        return VStack(alignment: .leading, spacing: 12) {
            if !pendingImages.isEmpty {
                pendingImageStrip
            }
            if store.fileUploads.preparing { ProgressView("正在准备文件…").appFont(.caption) }
            if !store.fileUploads.drafts.isEmpty { pendingFileStrip }
            VoiceComposerTextInput(
                text: $draft,
                isFocused: $composerIsFocused,
                presentation: slashPresentation,
                state: voiceState,
                voice: store.voiceInput,
                onHoldingChanged: { voiceTouchActive = $0 }
            )
            .offset(y: voiceState.voiceMode ? -4 : 0)
            if let pending = voiceState.pendingTranscript {
                VStack(alignment: .leading, spacing: 6) {
                    Text(pending).appFont(.caption).lineLimit(3)
                    HStack {
                        Text("草稿已更改，请确认插入识别文字").appFont(.caption2).foregroundStyle(.secondary)
                        Spacer()
                        Button("插入") { store.voiceInput.insertPending() }
                        Button("丢弃") { store.voiceInput.dismissFeedback() }
                    }.appFont(.caption)
                }
            }
            HStack(spacing: 7) {
                attachmentMenu

                if !store.isViewingTeamMember { permissionControl }

                Spacer(minLength: 2)

                if !store.isViewingTeamMember { modelControl }
                else {
                    Text(store.selectedSession?.title ?? "成员会话").appFont(.caption).foregroundStyle(.secondary).lineLimit(1)
                }

                Button { showsContextUsage = true } label: {
                    if contextIsLoading && store.selectedContextSnapshot == nil {
                        ProgressView().controlSize(.small).frame(width: 26, height: 26)
                    } else {
                        ContextUsageRing(progress: contextProgress)
                    }
                }
                .buttonStyle(.plain)
                .disabled(store.selectedContextSnapshot == nil)
                .accessibilityLabel(String(localized: "a11y.context.percent", defaultValue: "上下文已用 \(Int(contextProgress * 100))%"))
                .popover(isPresented: $showsContextUsage, arrowEdge: .bottom) {
                    ContextUsagePopover(snapshot: store.selectedContextSnapshot)
                }

                Button {
                    if showsSessionStopButton {
                        store.cancelSelectedSession()
                        return
                    }
                    if !composerHasContent {
                        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                        let enteringVoice = !voiceState.voiceMode
                        composerIsFocused = !enteringVoice
                        store.voiceInput.setVoiceMode(enteringVoice)
                        return
                    }
                    let content = store.composedSlashMessage(arguments: draft)
                    let images = pendingImages
                    guard store.send(content, images: images) else { return }
                    if store.messageSubmissionPending {
                        submittedQueueDraft = draft
                        submittedQueueImages = images
                        submittedQueueSessionID = store.selectedSessionId
                        return
                    }
                    if store.commandSubmissionPending {
                        composerIsFocused = false
                        return
                    }
                    draft = ""
                    store.clearActiveSlashCommand()
                    pendingImages = []
                    selectedPhotoItems = []
                    composerIsFocused = false
                    // Sending establishes a new tail-following intent before
                    // the gateway echoes the user event. The subsequent user
                    // bubble and first reasoning block can therefore grow
                    // above the composer without ever being hidden behind it.
                    viewportScrollToBottomToken &+= 1
                } label: {
                    Group {
                        if showsSessionStopButton {
                            RoundedRectangle(cornerRadius: 3, style: .continuous)
                                .fill(.white)
                                .frame(width: 14, height: 14)
                        } else {
                            Image(systemName: composerHasContent ? "arrow.up" : (voiceState.voiceMode ? "keyboard" : "waveform"))
                                .appFont(.system(size: 17, weight: .bold))
                                .foregroundStyle(.white)
                        }
                    }
                    .frame(width: 42, height: 42)
                    .background(DSHColor.ocean, in: Circle())
                }
                .buttonStyle(.plain)
                .disabled(
                    !store.selectedMemberCanSend || (showsSessionStopButton
                        ? store.isCancellingSelectedSession
                        : (composerHasContent && (store.sessionAgentPreset.blocksSending || store.waitingForNewSession || isImportingImages || store.fileUploads.preparing || (!store.fileUploads.drafts.isEmpty && !store.fileUploads.canSend) || store.commandSubmissionPending || store.messageSubmissionPending)))
                )
                .opacity(
                    showsSessionStopButton
                        ? (store.isCancellingSelectedSession ? 0.66 : 1)
                        : 1
                )
                .accessibilityLabel(
                    showsSessionStopButton
                        ? String(localized: "a11y.stop-generation", defaultValue: "停止生成")
                        : (composerHasContent ? String(localized: "a11y.send-message", defaultValue: "发送")
                           : (voiceState.voiceMode ? String(localized: "切换到文字输入") : String(localized: "切换到语音输入")))
                )
                .accessibilityIdentifier("composer-action")
            }
            .overlay(alignment: .top) {
                if voiceState.voiceMode {
                    // 复用原有 12pt 间隔绘制分割线，不增加输入卡片高度。
                    Rectangle()
                        .fill(Color.primary.opacity(0.08))
                        .frame(height: 0.5)
                        .offset(y: -6)
                        .accessibilityHidden(true)
                        .allowsHitTesting(false)
                }
            }
        }
        .padding(.horizontal, 14)
        .padding(.top, 14)
        .padding(.bottom, 12)
        .glassSurface(radius: 24, tint: glassTint, shadowOpacity: 0)
        // 先收住玻璃自带的向下投影，再单独控制输入框阴影。
        .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .stroke(glassEdge, lineWidth: 0.7)
        }
        // 轻微下移增强底部层次，保持原有扩散范围。
        .shadow(color: .black.opacity(colorScheme == .dark ? 0.24 : 0.10), radius: 10, x: 0, y: 2)
        .padding(.horizontal, 14)
        .padding(.bottom, composerBottomPadding)
        .onChangeCompat(of: store.messageAcceptedRevision) { _, _ in
            guard let submitted = submittedQueueDraft else { return }
            defer { submittedQueueDraft = nil; submittedQueueImages = [] }
            if let target = submittedQueueSessionID, target != store.selectedSessionId {
                if memberDrafts[target] == submitted, memberImages[target] == submittedQueueImages {
                    memberDrafts[target] = ""
                    memberImages[target] = []
                }
                return
            }
            guard (submittedQueueSessionID == nil || submittedQueueSessionID == store.selectedSessionId),
                  draft == submitted, pendingImages == submittedQueueImages else { return }
            draft = ""
            pendingImages = []
            selectedPhotoItems = []
            store.clearActiveSlashCommand()
            composerIsFocused = false
            viewportScrollToBottomToken &+= 1
        }
        .onChangeCompat(of: store.queueRevision) { _, _ in restoreQueuedDraft() }
        .onChangeCompat(of: store.selectedSessionId) { _, _ in
            store.fileUploads.select(sessionID: store.selectedSessionId, allowed: !store.isViewingTeamMember)
            queueExpanded = false
            restoreQueuedDraft()
        }
        .onAppear {
            store.fileUploads.select(sessionID: store.selectedSessionId, allowed: !store.isViewingTeamMember)
            restoreQueuedDraft()
        }
        .onChangeCompat(of: selectedPhotoItems) { _, items in
            guard !items.isEmpty else { return }
            store.voiceInput.setVoiceMode(false)
            let target = store.selectedSessionId
            Task { await importPhotos(items, targetSessionID: target) }
        }
        .onChangeCompat(of: draft) { _, value in
            store.voiceInput.draftChanged(value)
            store.updateSlashCommandInput(value)
        }
        .onChangeCompat(of: store.commandDraftClearToken) { _, _ in
            draft = ""
            pendingImages = []
            selectedPhotoItems = []
            composerIsFocused = false
        }
    }

    @ViewBuilder
    private var slashCommandMenus: some View {
        let state = store.slashCommands
        if state.catalogVisible || state.optionsCommand != nil {
            ContentSizedCommandScrollView {
                if let optionsCommand = state.optionsCommand {
                    Text("/\(optionsCommand.name)")
                        .appFont(.caption)
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 9)
                }
                if state.catalogLoading || state.optionsLoading || state.selectionLoading {
                    ProgressView().progressViewStyle(.linear)
                }
                VStack(spacing: 0) {
                    if state.optionsCommand == nil {
                        ForEach(state.filteredGroups, id: \.id) { group in
                            Text(group.title)
                                .appFont(.subheadline.weight(.medium))
                                .foregroundStyle(.secondary)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(.horizontal, 14)
                                .padding(.vertical, 9)
                            ForEach(group.items, id: \.stableId) { command in
                                Button {
                                    if let replacement = store.selectSlashCatalogItem(command.stableId) {
                                        draft = replacement
                                    }
                                } label: {
                                    HStack(spacing: 10) {
                                        Text(command.name)
                                            .appFont(.body.weight(.medium))
                                            .foregroundStyle(.primary)
                                        Text(command.description_)
                                            .appFont(.subheadline)
                                            .foregroundStyle(.secondary)
                                            .lineLimit(1)
                                        Spacer(minLength: 4)
                                        if command.ui.kind == "select" {
                                            Image(systemName: "chevron.right")
                                                .appFont(.caption.weight(.semibold))
                                                .foregroundStyle(.tertiary)
                                        }
                                    }
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .padding(.horizontal, 14)
                                    .padding(.vertical, 11)
                                    .contentShape(Rectangle())
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    } else {
                        ForEach(state.options, id: \.id) { option in
                            Button {
                                if let replacement = store.selectSlashCommandOption(option.id) {
                                    draft = replacement
                                }
                            } label: {
                                HStack(spacing: 10) {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(option.label)
                                            .appFont(.body.weight(.medium))
                                            .foregroundStyle(.primary)
                                        if let detail = option.description_ ?? option.detail {
                                            Text(detail)
                                                .appFont(.caption)
                                                .foregroundStyle(.secondary)
                                                .lineLimit(1)
                                        }
                                    }
                                    Spacer(minLength: 4)
                                    if option.selected == true {
                                        Image(systemName: "checkmark")
                                            .appFont(.body.weight(.semibold))
                                            .foregroundStyle(DSHColor.ocean)
                                    }
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(.horizontal, 14)
                                .padding(.vertical, 10)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .disabled(state.selectionLoading)
                        }
                    }
                }
            }
            .glassSurface(radius: 22, tint: glassTint)
            .overlay {
                RoundedRectangle(cornerRadius: 22, style: .continuous)
                    .stroke(glassEdge, lineWidth: 0.7)
            }
            .shadow(color: glassShadow, radius: 18, y: 8)
        }
    }

    private func restoreQueuedDraft() {
        guard let text = store.takeQueuedDraft() else { return }
        // 编辑队列期间用户可能继续输入，保留已有草稿。
        draft = draft.isEmpty ? text : draft + "\n\n" + text
        store.clearActiveSlashCommand()
        composerIsFocused = true
    }

    @ViewBuilder
    private var queueDock: some View {
        let items = store.selectedQueueItems
        if !items.isEmpty {
            let shape = UnevenRoundedRectangle(
                topLeadingRadius: 24,
                bottomLeadingRadius: 0,
                bottomTrailingRadius: 0,
                topTrailingRadius: 24
            )
            VStack(spacing: 0) {
                if items.count > 1 {
                    Button { queueExpanded.toggle() } label: {
                        HStack {
                            Image(systemName: "text.bubble")
                            Text("排队消息 · \(items.count)")
                            Spacer()
                            Image(systemName: "chevron.up")
                                .rotationEffect(.degrees(queueExpanded ? 180 : 0))
                        }
                        .appFont(.subheadline)
                        .padding(10)
                    }
                    .buttonStyle(.plain)
                }
                if items.count == 1 || queueExpanded {
                    Group {
                        if items.count <= 3 {
                            queueRows
                        } else {
                            ScrollView { queueRows }
                                .frame(maxHeight: 192)
                        }
                    }
                    .transition(.opacity)
                }
            }
            // 底部延伸到输入框圆角后方，队列操作区域完整位于输入框外。
            .padding(.bottom, 24)
            .background(Color(uiColor: .secondarySystemBackground), in: shape)
            .overlay {
                shape.strokeBorder(Color.primary.opacity(colorScheme == .dark ? 0.22 : 0.14), lineWidth: 0.5)
                    .allowsHitTesting(false)
            }
            .clipped()
            // 让标题与背景使用同一份逐帧几何尺寸，避免父布局和内部位置分别插值。
        }
    }

    // 少量条目按内容高度布局，避免 ScrollView 撑满 maxHeight 留出空白。
    private var queueRows: some View {
        let items = store.selectedQueueItems
        return VStack(spacing: 0) {
            ForEach(items, id: \.id) { item in
                HStack(spacing: 4) {
                    if items.count == 1 {
                        Image(systemName: "text.bubble").foregroundStyle(.secondary)
                    }
                    VStack(alignment: .leading, spacing: 3) {
                        Text(item.preview).appFont(.subheadline).lineLimit(2)
                        if item.attachmentCount > 0 {
                            Text("\(item.attachmentCount) 个附件").appFont(.caption).foregroundStyle(.secondary)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    if store.queueState.pendingItemId == item.id {
                        ProgressView().frame(width: 36, height: 40)
                    } else {
                        queueActionButton("pencil", label: "编辑排队消息", itemID: item.id, action: "edit", enabled: item.editable)
                        queueActionButton("trash", label: "删除排队消息", itemID: item.id, action: "remove")
                        queueActionButton("arrow.up", label: "立即插话", itemID: item.id, action: "steer", enabled: store.selectedSession?.isRunning == true)
                    }
                }
                .padding(.leading, 10)
                if item.id != items.last?.id {
                    Rectangle()
                        .fill(Color.primary.opacity(colorScheme == .dark ? 0.14 : 0.08))
                        .frame(height: 0.5)
                        .padding(.horizontal, 10)
                        .accessibilityHidden(true)
                }
            }
        }
    }

    private func queueActionButton(_ symbol: String, label: String, itemID: String, action: String, enabled: Bool = true) -> some View {
        Button { store.updateQueuedMessage(itemID, action: action) } label: {
            Image(systemName: symbol).appFont(.system(size: 15)).frame(width: 36, height: 40)
        }
        .buttonStyle(.plain)
        .foregroundStyle(.secondary)
        .accessibilityLabel(label)
        .disabled(!enabled || !store.supportsQueueControl || !store.gateway.state.isConnected || store.queueState.pendingItemId != nil)
    }

    private var composerHasContent: Bool {
        !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !pendingImages.isEmpty || !store.fileUploads.drafts.isEmpty || store.fileUploads.preparing
    }

    private var showsSessionStopButton: Bool {
        store.selectedMemberCanSend && store.supportsSessionCancel && store.selectedSession?.isRunning == true && !composerHasContent
    }

    private var pendingFileStrip: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(store.fileUploads.drafts, id: \.id) { file in
                    PendingFileCard(
                        file: file,
                        status: uploadStatus(file),
                        enabled: !store.messageSubmissionPending,
                        onRetry: { store.fileUploads.retry(file.id) },
                        onRemove: { store.fileUploads.remove(file.id) }
                    )
                }
            }
        }
    }

    private func uploadStatus(_ file: SharedUploadDraft) -> String {
        switch file.status {
        case "waiting": String(localized: "等待上传")
        case "opening": String(localized: "开始上传…")
        case "uploading": "\(String(localized: "上传中")) \(file.size > 0 ? file.confirmedBytes * 100 / file.size : 0)%"
        case "committing": String(localized: "处理中…")
        case "ready": "\(String(localized: "可发送")) · \(ByteCountFormatter.string(fromByteCount: file.size, countStyle: .file))"
        default: String(localized: String.LocalizationValue(file.error ?? "上传失败"))
        }
    }

    private var pendingImageStrip: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(pendingImages) { image in
                    ZStack(alignment: .topTrailing) {
                        if let uiImage = UIImage(data: image.data) {
                            let previewSize = pendingImagePreviewSize(for: uiImage)
                            Image(uiImage: uiImage)
                                .resizable()
                                .scaledToFit()
                                .frame(width: previewSize.width, height: previewSize.height)
                                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                        }
                        Button {
                            pendingImages.removeAll { $0.id == image.id }
                        } label: {
                            Image(systemName: "xmark.circle.fill")
                                .symbolRenderingMode(.palette)
                                .foregroundStyle(.white, .black.opacity(0.68))
                                .appFont(.system(size: 20))
                        }
                        .buttonStyle(.plain)
                        .offset(x: 5, y: -5)
                        .accessibilityLabel("移除图片")
                    }
                    .padding(.top, 5)
                    .padding(.trailing, 5)
                }
            }
        }
        .frame(height: 82)
    }

    private func pendingImagePreviewSize(for image: UIImage) -> CGSize {
        let sourceSize = image.size
        guard sourceSize.width > 0, sourceSize.height > 0 else {
            return CGSize(width: 72, height: 72)
        }
        let scale = min(72 / sourceSize.width, 72 / sourceSize.height)
        return CGSize(
            width: max(1, sourceSize.width * scale),
            height: max(1, sourceSize.height * scale)
        )
    }

    @MainActor
    private func importPhotos(_ items: [PhotosPickerItem], targetSessionID: String?) async {
        isImportingImages = true
        defer {
            isImportingImages = false
            if store.selectedSessionId == targetSessionID { selectedPhotoItems = [] }
        }
        var imported: [GatewayOutgoingImage] = []
        for item in items.prefix(max(0, 20 - pendingImages.count)) {
            do {
                guard let data = try await item.loadTransferable(type: Data.self) else {
                    store.lastError = String(localized: "所选图片不是受支持的 PNG、JPEG、WebP 或 GIF。")
                    continue
                }
                let prepared = try await Task.detached(priority: .userInitiated) {
                    try GatewayImagePreprocessor.prepare(data: data)
                }.value
                imported.append(GatewayOutgoingImage(mediaType: prepared.mediaType, data: prepared.data))
            } catch {
                store.lastError = String(localized: "image.process.failed", defaultValue: "处理所选图片失败：\(error.localizedDescription)")
            }
        }
        var destination = store.selectedSessionId == targetSessionID ? pendingImages : targetSessionID.flatMap { memberImages[$0] } ?? []
        let existingBytes = destination.reduce(0) { $0 + $1.data.count }
        var acceptedBytes = existingBytes
        for image in imported where !destination.contains(where: { $0.mediaType == image.mediaType && $0.data == image.data }) {
            guard destination.count < 20 else { break }
            guard acceptedBytes + image.data.count <= 100 * 1024 * 1024 else {
                store.lastError = String(localized: "一条消息中的图片总大小不能超过 100 MiB。")
                break
            }
            destination.append(image)
            acceptedBytes += image.data.count
        }
        if store.selectedSessionId == targetSessionID { pendingImages = destination }
        else if let targetSessionID { memberImages[targetSessionID] = destination }
    }

    private var composerBottomPadding: CGFloat {
        // Keep the overlay's measured height stable across focus changes.
        // SwiftUI already avoids the keyboard because only the container safe
        // area is ignored; this inset preserves a comfortable visual gap both
        // above the Home Indicator and above the keyboard.
        bottomSafeAreaInset + 10
    }

    private var composerBackdrop: some View {
        ZStack {
            Rectangle()
                .fill(.ultraThinMaterial)
            LinearGradient(
                colors: [
                    conversationBackground.opacity(0.02),
                    conversationBackground.opacity(0.78),
                    conversationBackground
                ],
                startPoint: .top,
                endPoint: .bottom
            )
        }
        .mask {
            LinearGradient(
                stops: [
                    .init(color: .clear, location: 0),
                    .init(color: .black.opacity(0.58), location: 0.34),
                    .init(color: .black, location: 0.68)
                ],
                startPoint: .top,
                endPoint: .bottom
            )
        }
    }

    @ViewBuilder
    private var sessionAgentPresetControl: some View {
        let state = store.sessionAgentPreset
        if state.visible {
            let current = state.presets.first { $0.id == state.agentPreset }
            let retry = !state.loading && !state.saving && (!state.known || !state.catalogLoaded)
            Button {
                if retry { store.refreshSessionAgentPreset() }
                else {
                    viewportProxy.prepareForOverlayPresentation()
                    showsSessionStatsPopover = false
                    showsAgentPresetMenu = true
                }
            } label: {
                HStack(spacing: 6) {
                    Image("DshAgentPreset")
                        .renderingMode(.template)
                        .resizable()
                        .scaledToFit()
                        .frame(width: 16, height: 16)
                        .accessibilityHidden(true)
                    Text(state.saving ? "切换中…" : current?.name ?? state.agentPreset.map(agentPresetTitle)
                         ?? (state.loading ? "读取模式…" : "重试模式"))
                        .lineLimit(1)
                        .truncationMode(.tail)
                    Image(systemName: "chevron.down")
                        .appFont(.caption2.weight(.semibold))
                        .foregroundStyle(.secondary)
                }
                .appFont(.subheadline.weight(.medium))
                .padding(.horizontal, 12)
                .frame(height: composerCapsuleHeight)
                .contentShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            }
            .buttonStyle(.plain)
            .glassSurface(radius: 16, tint: glassTint.opacity(0.88))
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .stroke(glassEdge.opacity(0.9), lineWidth: 0.6)
            }
            .disabled(!store.gateway.state.isConnected || !(state.canSelect || retry))
            .accessibilityLabel("选择会话模式")
            .accessibilityIdentifier("session-agent-preset-capsule")
            .anchorPreference(key: SessionAgentPresetPopoverAnchorKey.self, value: .bounds) { $0 }
        }
    }

    private var sessionAgentPresetMenuContent: some View {
        let state = store.sessionAgentPreset
        return VStack(spacing: 0) {
            ForEach(state.presets, id: \.id) { preset in
                Button {
                    showsAgentPresetMenu = false
                    store.selectSessionAgentPreset(preset.id)
                } label: {
                    HStack(spacing: 10) {
                        VStack(alignment: .leading, spacing: 5) {
                            Text(preset.name ?? agentPresetTitle(preset.id))
                                .appFont(.body.weight(.medium))
                            Text(preset.broken?.boolValue == true
                                 ? preset.brokenReason ?? "模式不可用"
                                 : agentPresetCompactDescription(preset.id, description: preset.description_))
                                .appFont(.caption)
                                .foregroundStyle(.secondary)
                        }
                        .lineLimit(1)
                        .truncationMode(.tail)
                        Spacer(minLength: 0)
                        if preset.id == state.agentPreset {
                            Image(systemName: "checkmark").appFont(.body.weight(.medium))
                        }
                    }
                    .foregroundStyle(.primary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(preset.broken?.boolValue == true)
                .opacity(preset.broken?.boolValue == true ? 0.45 : 1)
                .accessibilityIdentifier("session-agent-preset-\(preset.id)")
            }
        }
        .padding(.vertical, 6)
    }

    private func agentPresetCompactDescription(_ id: String, description: String?) -> String {
        switch id {
        case "standard": return "完整工具，适合日常开发"
        case "code": return "用代码组合多步工具操作"
        case "minimal": return "仅用 Shell，轻量直接"
        case "cordis": return "创建和调试自定义 Agent"
        default: return description ?? "自定义 Agent 模式"
        }
    }

    private func sessionStatsBanner(_ snapshot: GatewaySessionStatsSnapshot) -> some View {
        HStack(spacing: 0) {
            Spacer(minLength: 0)
            Button {
                viewportProxy.prepareForOverlayPresentation()
                showsAgentPresetMenu = false
                showsSessionStatsPopover.toggle()
            } label: {
                HStack(spacing: 7) {
                    Text(SessionStatsFormatter.turnsStepsLine(snapshot.stats) ?? String(localized: "正在读取会话统计…"))
                        .appFont(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .truncationMode(.tail)
                    Image(systemName: "chevron.up")
                        .appFont(.caption2.weight(.semibold))
                        .foregroundStyle(.tertiary)
                }
                .contentShape(Rectangle())
                .padding(.horizontal, 16)
                .frame(height: composerCapsuleHeight)
            }
            .buttonStyle(.plain)
            .glassSurface(radius: 16, tint: glassTint.opacity(0.88))
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .stroke(glassEdge.opacity(0.9), lineWidth: 0.6)
            }
            .accessibilityLabel("查看会话执行状态")
            .accessibilityValue(SessionStatsFormatter.compactLine(snapshot))
            .anchorPreference(key: SessionStatsPopoverAnchorKey.self, value: .bounds) { $0 }
        }

    }

    @ViewBuilder
    private var permissionControl: some View {
        if store.selectedSessionId == nil {
            HStack(spacing: 5) {
                if defaultConfigurationIsLoading && store.permissionDefault == nil {
                    ProgressView().controlSize(.mini)
                    Text("读取默认权限…")
                } else if let permission = store.permissionDefault {
                    Image(systemName: permissionIcon(permission))
                    Text(permissionTitle(permission))
                } else {
                    Image(systemName: "shield")
                    Text("默认权限")
                }
            }
            .appFont(.caption.weight(.medium))
            .foregroundStyle(.secondary)
            .lineLimit(1)
            .frame(minWidth: 92, alignment: .leading)
            .accessibilityElement(children: .combine)
            .accessibilityLabel(String(localized: "a11y.defaults.permission", defaultValue: "新会话默认权限：\(store.permissionDefault.map(permissionTitle) ?? String(localized: "未读取"))"))
        } else {
            permissionMenu
        }
    }

    @ViewBuilder
    private var modelControl: some View {
        // 独立 View 为泛型视图建立边界，避免菜单的完整类型嵌入输入框构建链。
        if store.selectedSessionId == nil {
            ConversationDefaultModelSummary(
                title: defaultModelTitle,
                effortTitle: defaultModelEffortTitle,
                isLoading: defaultModelIsLoading && store.defaultModelSelection == nil
            )
        } else {
            ConversationModelMenu(
                groups: modelGroups,
                selection: currentModelSelection,
                efforts: currentEfforts,
                title: currentModelTitle,
                effortTitle: currentEffortTitle,
                isLoading: modelIsLoading,
                isDisabled: modelGroups.isEmpty || store.selectedModelCatalog?.routable == false,
                onSelectModel: { group, model in select(group: group, model: model) },
                onSelectEffort: { effort in select(effort: effort) }
            )
        }
    }

    private var permissionMenu: some View {
        Menu {
            if permissionOptions.isEmpty {
                Text("正在读取权限…")
            } else {
                ForEach(permissionOptions) { option in
                    Button {
                        store.setPermission(option.value)
                    } label: {
                        Label(
                            option.name,
                            systemImage: option.value == currentPermission ? "checkmark" : permissionIcon(option.value)
                        )
                    }
                }
            }
        } label: {
            HStack(spacing: 5) {
                if permissionIsLoading {
                    ProgressView().controlSize(.mini)
                } else {
                    Image(systemName: permissionIcon(currentPermission))
                }
                Text(permissionTitle(currentPermission)).lineLimit(1)
            }
            .appFont(.caption.weight(.medium))
            .foregroundStyle(.secondary)
            .frame(minWidth: 78, alignment: .leading)
            .contentShape(Rectangle())
        }
        .disabled(permissionOptions.isEmpty)
    }

    private var permissionOptions: [GatewayPermissionOption] { store.selectedPermissions?.options ?? [] }
    private var currentPermission: String {
        store.selectedPermissions?.currentValue ?? store.selectedPermissions?.preset ?? store.permissionDefault ?? ""
    }
    private var defaultConfigurationIsLoading: Bool {
        !store.defaultConfigurationLoadingKinds.isDisjoint(with: ["defaults", "agent-presets"])
    }
    private var defaultModelIsLoading: Bool {
        store.defaultConfigurationLoadingKinds.contains("default-model")
    }
    private var defaultModelTitle: String {
        guard let selection = store.defaultModelSelection else { return String(localized: "默认模型") }
        return modelTitle(for: selection.model)
    }
    private var defaultModelEffortTitle: String? {
        store.defaultModelSelection?.reasoningEffort.map(reasoningEffortTitle)
    }
    private var defaultAgentPresetTitle: String {
        guard let id = store.agentPresetDefault else { return String(localized: "默认 Agent") }
        return agentPresetTitle(id)
    }
    private func agentPresetTitle(_ id: String) -> String {
        if let preset = store.agentPresets.first(where: { $0.id == id }) {
            return preset.displayName
        }
        return L10n.presetModeName(for: id)
    }
    private var permissionIsLoading: Bool {
        store.sessionControlLoadingKinds.contains("permission-options") || store.sessionControlLoadingKinds.contains("permission")
    }
    private var modelIsLoading: Bool {
        store.sessionControlLoadingKinds.contains("models") || store.sessionControlLoadingKinds.contains("select-model")
    }
    private var contextIsLoading: Bool { store.sessionControlLoadingKinds.contains("context-usage") }
    private var modelGroups: [GatewayModelGroup] { store.selectedModelCatalog?.groups ?? [] }
    private var currentModelSelection: GatewayModelSelection? { store.selectedModelCatalog?.current }
    private var currentModelItem: GatewayModelItem? {
        guard let selection = currentModelSelection else { return nil }
        return modelGroups.first(where: { $0.id == selection.provider })?.models.first(where: { $0.id == selection.model })
    }
    private var currentModelTitle: String { currentModelItem?.name ?? currentModelSelection?.model ?? "DeepSeek Agent" }
    private var currentEfforts: [GatewayReasoningEffort] { currentModelItem?.reasoning?.efforts ?? [] }
    private var currentEffortTitle: String? {
        guard let id = currentModelSelection?.reasoningEffort else { return nil }
        return currentEfforts.first(where: { $0.id == id })?.name ?? id.capitalized
    }
    private func modelTitle(for id: String) -> String {
        switch id {
        case "deepseek-chat": return "DeepSeek Chat"
        case "deepseek-reasoner": return "DeepSeek Reasoner"
        default: return id
        }
    }
    private func reasoningEffortTitle(_ id: String) -> String {
        switch id.lowercased() {
        case "low": return "Low"
        case "medium": return "Medium"
        case "high": return "High"
        default: return id.capitalized
        }
    }
    private var contextProgress: Double {
        guard let pressure = store.selectedContextSnapshot?.pressure,
              let used = pressure.pressureTokens,
              let window = pressure.contextWindow,
              window > 0 else { return 0 }
        return min(1, max(0, Double(used) / Double(window)))
    }

    private func permissionTitle(_ value: String) -> String {
        permissionOptions.first(where: { $0.value == value })?.name
            ?? store.permissionDefaultOptions.first(where: { $0.value == value })?.name
            ?? L10n.permissionName(for: value)
    }
    private func permissionIcon(_ value: String) -> String {
        switch value {
        case "read-only": "checkmark.shield"
        case "workspace-write": "pencil.and.outline"
        case "danger-full-access": "exclamationmark.shield"
        default: "shield"
        }
    }
    private func select(group: GatewayModelGroup, model: GatewayModelItem) {
        let efforts = model.reasoning?.efforts ?? []
        let retainedEffort = currentModelSelection?.reasoningEffort.flatMap { current in
            efforts.contains(where: { $0.id == current }) ? current : nil
        }
        store.selectModel(
            provider: group.id,
            model: model.id,
            reasoningEffort: retainedEffort ?? model.reasoning?.defaultEffort
        )
    }
    private func select(effort: GatewayReasoningEffort) {
        guard let current = currentModelSelection else { return }
        store.selectModel(provider: current.provider, model: current.model, reasoningEffort: effort.id)
    }

    private var conversationItems: [ConversationItem] {
        store.selectedConversationItems
    }

    private var supplementalViewportEntries: [ConversationViewportEntry] {
        var hasher = Hasher()
        hasher.combine(isLoadingSelectedHistory)
        if let sessionID = store.selectedSessionId {
            hasher.combine(store.selectedHistoryHasMore)
            hasher.combine(store.historyLoadProgress[sessionID]?.loaded)
            hasher.combine(store.historyLoadProgress[sessionID]?.total)
        }
        let revision = hasher.finalize()
        var entries: [ConversationViewportEntry] = []
        if store.conversationSearchTarget != nil {
            entries.append(.init(id: "search-context", revision: revision,
                content: AnyView(HStack {
                    Text("本地缓存片段").appFont(.caption).foregroundStyle(.secondary)
                    Spacer()
                    Button("返回最新消息") {
                        Task {
                            await store.returnToLatestConversation()
                            viewportScrollToBottomToken &+= 1
                        }
                    }.appFont(.caption)
                }.padding(.vertical, 8)), allowsScrollAnchoring: false))
        }
        if !conversationItems.isEmpty, isLoadingSelectedHistory {
            entries.append(.init(id: "history-loading", revision: revision, content: AnyView(historyLoadingBanner), allowsScrollAnchoring: false))
        }
        if !isLoadingSelectedHistory,
           let sessionID = store.selectedSessionId,
           store.selectedHistoryHasMore {
            entries.append(.init(
                id: "history-more",
                revision: revision,
                content: AnyView(
                    Button("加载更早记录") { store.loadHistory(for: sessionID, older: true) }
                        .appFont(.caption)
                        .frame(maxWidth: .infinity)
                ),
                allowsScrollAnchoring: false
            ))
        }
        return entries
    }

    private var searchTargetViewportID: String? {
        guard let target = store.conversationSearchTarget else { return nil }
        let eventID = String(target.dropFirst("message-".count))
        return ConversationDisplayEntry.make(from: conversationItems).first { entry in
            switch entry.content {
            case .message(let item): item.id == eventID || item.id.hasPrefix(eventID + "-")
            case .process(let group): group.items.contains { $0.id == eventID || $0.id.hasPrefix(eventID + "-") }
            }
        }?.id ?? target
    }

    private func makeConversationViewportEntries(from items: [ConversationItem]) -> [ConversationViewportEntry] {
        var entries = ConversationDisplayEntry.make(from: items).flatMap { entry in
            let revision = viewportEntryRevision(entry)
            if case .message(let item) = entry.content,
               item.kind == .assistant,
               item.images.isEmpty,
               item.files.isEmpty,
               item.knownFiles.isEmpty,
               item.title == L10n.streamingAssistantTitle {
                return [ConversationViewportEntry(
                    id: entry.id,
                    revision: revision,
                    streamingAssistant: .init(title: item.title, text: item.text)
                )]
            }
            if case .message(let item) = entry.content,
               item.kind == .user {
                return [ConversationViewportEntry(
                    id: entry.id,
                    revision: revision,
                    userMessage: .init(item: item, imageData: { store.imageData(for: $0) },
                        showsCopyButton: entry.showsCopyButton, onOpenFile: uploadedFileOpener())
                )]
            }
            switch entry.content {
            case .message(let item):
                return [ConversationViewportEntry(
                    id: entry.id,
                    revision: revision,
                    content: AnyView(ConversationRow(
                        item: item,
                        showsCopyButton: entry.showsCopyButton,
                        imageData: { store.imageData(for: $0) },
                        onOpenFile: conversationFileOpener(),
                        onOpenUploadedFile: uploadedFileOpener()
                    )),
                    // Completed responses are immutable for this revision.
                    // The viewport measures their SwiftUI root directly before
                    // caching, including fenced-code horizontal scroll views.
                    clipsContentToBounds: true
                )]
            case .process(let group):
                return processViewportEntries(for: group)
            }
        }
        if store.selectedSession?.isRunning == true {
            let previousReplyIndex = items.lastIndex {
                $0.kind == .assistant && $0.title != L10n.streamingAssistantTitle
            } ?? -1
            let latestUser = items.dropFirst(previousReplyIndex + 1).first { $0.kind == .user }
                ?? items.last { $0.kind == .user }
            let latestUserIndex = items.lastIndex { $0.kind == .user } ?? -1
            let cues = items.dropFirst(latestUserIndex + 1).compactMap(DeepSearchProgressCue.init)
            var hasher = Hasher()
            hasher.combine(latestUser?.id)
            for cue in cues {
                hasher.combine(cue.id)
                hasher.combine(cue.kind)
                hasher.combine(cue.isError)
            }
            entries.append(ConversationViewportEntry(
                id: "deep-search-progress",
                revision: hasher.finalize(),
                content: AnyView(DeepSearchProgressRow(startDate: latestUser?.date ?? .now, cues: cues))
            ))
        }
        return entries
    }

    private func processViewportEntries(for group: ConversationProcessGroup) -> [ConversationViewportEntry] {
        ConversationProcessNode.make(
            from: group,
            expandedNodeIDs: expandedConversationNodeIDs
        ).map { node in
            ConversationViewportEntry(
                id: node.id,
                revision: node.revision,
                content: AnyView(processNodeView(node)),
                // Process descendants are independently self-sized cells.
                // Clipping is a final safety boundary while an estimated
                // height is settling, so Markdown can never paint over a
                // sibling row during an animated insertion.
                clipsContentToBounds: true
            )
        }
    }

    private var expandedConversationNodeIDs: Set<String> {
        guard let sessionID = store.selectedSessionId else { return [] }
        return expandedConversationNodeIDsBySession[sessionID] ?? []
    }

    private func toggleConversationNode(_ id: String) {
        guard let sessionID = store.selectedSessionId else { return }
        viewportProxy.prepareForDisclosureUpdate(anchorID: id)
        var expanded = expandedConversationNodeIDsBySession[sessionID] ?? []
        if !expanded.insert(id).inserted {
            expanded.remove(id)
        }
        expandedConversationNodeIDsBySession[sessionID] = expanded
    }

    @ViewBuilder
    private func processNodeView(_ node: ConversationProcessNode) -> some View {
        switch node.content {
        case .groupHeader(let group, let isExpanded, let isExpandable):
            ConversationProcessHeaderRow(
                group: group,
                expanded: isExpanded,
                isExpandable: isExpandable,
                onToggle: { toggleConversationNode(node.id) }
            )
        case .commandDetail(let command):
            ConversationCommandDetailRow(command: command)
        case .eventHeader(let item, let role, let isExpanded, let isExpandable):
            ConversationEventHeaderRow(
                item: item,
                role: role,
                expanded: isExpanded,
                isExpandable: isExpandable,
                onToggle: { toggleConversationNode(node.id) }
            )
        case .eventDetail(let item, let role):
            ConversationEventDetailRow(item: item, role: role)
        case .reasoningHeader(let text, let isExpanded):
            ConversationReasoningHeaderRow(
                text: text,
                expanded: isExpanded,
                onToggle: { toggleConversationNode(node.id) }
            )
        case .reasoningDetail(let text):
            ConversationReasoningDetailRow(text: text)
        case .toolBundleHeader(let tools, let isExpanded):
            ConversationToolBundleHeaderRow(
                tools: tools,
                expanded: isExpanded,
                onToggle: { toggleConversationNode(node.id) }
            )
        case .toolHeader(let tool, let isExpanded, let isExpandable):
            ConversationProcessToolHeaderRow(
                tool: tool,
                expanded: isExpanded,
                isExpandable: isExpandable,
                onToggle: { toggleConversationNode(node.id) }
            )
        case .toolDetail(let tool):
            ConversationProcessToolDetailRow(tool: tool)
        }
    }

    private func uploadedFileOpener() -> (PresentedFile) -> Void {
        let gatewayID = store.gatewayLocalID
        let sessionID = store.selectedSessionId
        return { file in
            guard let sessionID else { return }
            store.openUploadedFile(gatewayID: gatewayID, sessionID: sessionID, file: file)
        }
    }

    private func conversationFileOpener() -> (String) -> Void {
        let gatewayID = store.gatewayLocalID
        let sessionID = store.selectedSessionId
        return { path in
            guard let sessionID else { return }
            store.openConversationFile(gatewayID: gatewayID, sessionID: sessionID, path: path)
        }
    }

    private func viewportEntryRevision(_ entry: ConversationDisplayEntry) -> Int {
        var hasher = Hasher()
        hasher.combine(entry.id)
        hasher.combine(entry.showsCopyButton)
        switch entry.content {
        case .message(let item):
            hasher.combine(item.id)
            hasher.combine(item.title)
            hasher.combine(item.text)
            hasher.combine(item.isError)
            hasher.combine(item.files)
            hasher.combine(item.knownFiles)
            for image in item.images {
                hasher.combine(image)
                hasher.combine(store.imageData(for: image.id) != nil)
            }
        case .process(let group):
            for item in group.items {
                hasher.combine(item.id)
                hasher.combine(item.title)
                // A collapsed process header does not render the growing
                // reasoning body. Reconfiguring its hosted SwiftUI cell for
                // every reasoning token repeatedly invalidates neighboring
                // self-sized rows and causes visible overlap/flicker. The
                // completed reasoning item receives a new id and refreshes
                // the row once with its final text.
                if !(item.kind == .reasoning && item.title == L10n.streamingReasoningTitle) {
                    hasher.combine(item.text)
                }
                hasher.combine(item.isError)
            }
        }
        return hasher.finalize()
    }
}

private struct DeepSearchProgressCue: Hashable {
    enum Kind: Hashable { case context, reasoning, tool, toolResult, assistant, status }

    let id: String
    let kind: Kind
    let date: Date
    let isError: Bool

    init?(item: ConversationItem) {
        switch item.kind {
        case .context: kind = .context
        case .reasoning: kind = .reasoning
        case .tool, .jsonTool: kind = .tool
        case .toolResult: kind = .toolResult
        case .assistant: kind = .assistant
        case .status: kind = .status
        case .user, .system: return nil
        }
        id = item.id
        date = item.date
        isError = item.isError
    }
}

private struct DeepSearchProgressRow: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorScheme) private var colorScheme

    let startDate: Date
    let cues: [DeepSearchProgressCue]

    private enum Activity: Hashable { case thinking, gathering, exploring, reviewing, revisiting, composing, working }
    private static let phraseIntervalSeconds = 30
    private static let phrases: [Activity: [String]] = [
        .thinking: ["梳理脉络中", "推敲细节中", "酝酿下一步中", "沿着思路前行中"],
        .gathering: ["翻阅已有线索中", "收拢相关信息中", "寻找切入点中", "铺开问题脉络中"],
        .exploring: ["向外寻找线索中", "打捞新线索中", "沿途探查中", "搜集更多拼图中"],
        .reviewing: ["对照新发现中", "拼合新线索中", "检视所得中", "把发现串起来中"],
        .revisiting: ["回看已有线索中", "重新校准方向中", "再推敲一遍中", "换个角度审视中"],
        .composing: ["组织回应中", "整理表达中", "将思路落成文字中", "勾勒答案中"],
        .working: ["继续推进中", "稳步探索中", "梳理当前进展中", "逐步展开中"]
    ]

    private func phrase(for elapsed: Int) -> String {
        let bucket = elapsed / Self.phraseIntervalSeconds
        let cutoff = startDate.addingTimeInterval(TimeInterval(bucket * Self.phraseIntervalSeconds))
        var activity = Activity.thinking
        var hasReasoned = false
        var hasToolResult = false
        for cue in cues where cue.date < cutoff {
            switch cue.kind {
            case .context: activity = .gathering
            case .reasoning:
                activity = hasReasoned || hasToolResult ? .revisiting : .thinking
                hasReasoned = true
            case .tool: activity = .exploring
            case .toolResult:
                activity = cue.isError ? .revisiting : .reviewing
                hasToolResult = true
            case .assistant: activity = .composing
            case .status: activity = .working
            }
        }
        let options = Self.phrases[activity] ?? ["梳理脉络中"]
        return options[(Int(startDate.timeIntervalSince1970) + bucket) % options.count]
    }

    var body: some View {
        let base = colorScheme == .dark
            ? Color(red: 0.659, green: 0.745, blue: 0.859)
            : Color(red: 0.094, green: 0.212, blue: 0.353)
        let highlight = colorScheme == .dark
            ? Color(red: 0.835, green: 0.890, blue: 0.957)
            : Color(red: 0.357, green: 0.498, blue: 0.663)
        VStack(alignment: .leading, spacing: 0) {
            Rectangle()
                .fill(Color.primary.opacity(0.025))
                .frame(height: 0.5)

            TimelineView(.animation(minimumInterval: reduceMotion ? 1 : 1.0 / 30.0)) { timeline in
                let elapsed = max(0, Int(timeline.date.timeIntervalSince(startDate)))
                let phrase = phrase(for: elapsed)
                let phase = reduceMotion ? -1.0 :
                    timeline.date.timeIntervalSinceReferenceDate
                        .truncatingRemainder(dividingBy: 2.2) / 2.2 * 1.8 - 0.4

                HStack(spacing: 8) {
                    // 与 Web UI 的 APNG 鲸尾共用逐帧素材；减少动态效果时停在首帧。
                    Image(uiImage: RunningWhaleTailFrames.images[
                        reduceMotion ? 0 : Int(timeline.date.timeIntervalSinceReferenceDate * 20) % RunningWhaleTailFrames.images.count
                    ])
                        .resizable()
                        .renderingMode(.template)
                        .foregroundStyle(base)
                        .frame(width: 18, height: 18)
                        .accessibilityHidden(true)
                    Text("\(phrase)，用时 \(elapsed) 秒…")
                        .appFont(.system(size: 14, weight: .medium))
                        .foregroundStyle(
                            LinearGradient(
                                colors: [base, reduceMotion ? base : highlight, base],
                                startPoint: UnitPoint(x: phase - 0.24, y: 0.5),
                                endPoint: UnitPoint(x: phase + 0.24, y: 0.5)
                            )
                        )
                        .lineLimit(1)
                    Spacer(minLength: 0)
                }
                .padding(.top, 13)
                .accessibilityElement(children: .combine)
            }
        }
        .padding(.top, 8)
        .frame(height: 56, alignment: .top)
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityIdentifier("deep-search-progress")
    }
}

private enum RunningWhaleTailFrames {
    static let images: [UIImage] = {
        guard let sprite = UIImage(named: "RunningWhaleTailSprite")?.cgImage else { return [UIImage()] }
        return (0..<60).map { index in
            let frame = sprite.cropping(to: CGRect(x: index * 28, y: 0, width: 28, height: 28)) ?? sprite
            return UIImage(cgImage: frame)
        }
    }()
}

/// Keeps both session pages mounted and only changes their horizontal offset.
/// Unlike a page-styled `TabView`, this container does not virtualize the
/// off-screen page, so UIKit viewport state and trajectory projection state
/// survive every tab switch.
/// 与 WebUI 的任务 / Goal 区块保持同一信息层级：显示在输入框上方，任务可折叠。
private struct TaskGoalPanels: View {
    @Environment(\.colorScheme) private var colorScheme

    let tasks: [GatewayTodoItem]?
    let goal: GatewayGoalPayload?
    let isMutatingGoal: Bool
    @Binding var tasksExpanded: Bool
    let onPauseResume: () -> Void
    let onEdit: () -> Void
    let onClear: () -> Void

    private var completedCount: Int { tasks?.count { $0.status == "completed" } ?? 0 }
    private var activeCount: Int { tasks?.count { $0.status == "in_progress" } ?? 0 }
    private var pendingCount: Int { max(0, (tasks?.count ?? 0) - completedCount - activeCount) }

    /// 与输入框使用完全相同的 glass tint，避免面板透出下方的聊天文本。
    private var panelGlassTint: Color {
        Color(uiColor: .secondarySystemBackground)
            .opacity(colorScheme == .dark ? 0.52 : 0.48)
    }

    private var panelGlassEdge: Color {
        colorScheme == .dark ? .white.opacity(0.22) : .black.opacity(0.13)
    }

    private var panelShadow: Color {
        .black.opacity(colorScheme == .dark ? 0.30 : 0.10)
    }

    var body: some View {
        VStack(spacing: 8) {
            if let tasks { taskPanel(tasks) }
            if let goal { goalPanel(goal) }
        }
    }

    private func taskPanel(_ tasks: [GatewayTodoItem]) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Button { tasksExpanded.toggle() } label: {
                HStack(spacing: 10) {
                    Image(systemName: "list.bullet")
                        .appFont(.system(size: 18, weight: .medium))
                        .foregroundStyle(.secondary)
                    Text("任务").appFont(.headline)
                    Text(taskSummary)
                        .appFont(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                    Spacer(minLength: 0)
                    Image(systemName: tasksExpanded ? "chevron.up" : "chevron.down")
                        .appFont(.subheadline.weight(.semibold))
                        .foregroundStyle(.secondary)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(tasksExpanded ? "收起任务" : "展开任务")

            if tasksExpanded {
                VStack(alignment: .leading, spacing: 9) {
                    ForEach(Array(tasks.enumerated()), id: \.offset) { _, task in
                        HStack(spacing: 11) {
                            taskStatusIcon(task.status)
                            Text(task.content)
                                .appFont(.body)
                                .foregroundStyle(task.status == "completed" ? .secondary : .primary)
                                .multilineTextAlignment(.leading)
                            Spacer(minLength: 0)
                        }
                    }
                }
                .padding(.top, 12)
            }
        }
        .padding(15)
        .glassSurface(radius: 18, tint: panelGlassTint)
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .strokeBorder(panelGlassEdge, lineWidth: 1)
        }
        .shadow(color: panelShadow, radius: 8, y: 3)
    }

    private func goalPanel(_ goal: GatewayGoalPayload) -> some View {
        let active = goal.goal.phase == "active"
        return HStack(spacing: 10) {
            Image(systemName: "target")
                .appFont(.system(size: 21, weight: .medium))
                .foregroundStyle(.secondary)
            HStack(spacing: 10) {
                Text(goalPhaseLabel(goal.goal.phase))
                    .appFont(.headline)
                    .fixedSize(horizontal: true, vertical: false)
                Text(goal.goal.objective)
                    .appFont(.body)
                    .lineLimit(1)
                    .truncationMode(.tail)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if isMutatingGoal {
                ProgressView().controlSize(.small).tint(DSHColor.ocean)
            } else {
                goalActionButton(
                    active ? "pause.circle" : "play.circle",
                    active ? "暂停目标" : "继续目标",
                    onPauseResume
                )
                goalActionButton("pencil", "编辑目标", onEdit)
                goalActionButton("trash", "删除目标", onClear)
            }
        }
        .padding(15)
        .glassSurface(radius: 18, tint: panelGlassTint)
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .strokeBorder(panelGlassEdge, lineWidth: 1)
        }
        .shadow(color: panelShadow, radius: 8, y: 3)
    }

    private var taskSummary: String {
        [
            completedCount > 0 ? "\(completedCount) 已完成" : nil,
            activeCount > 0 ? "\(activeCount) 进行中" : nil,
            pendingCount > 0 ? "\(pendingCount) 待处理" : nil
        ]
        .compactMap { $0 }
        .joined(separator: " · ")
    }

    @ViewBuilder
    private func taskStatusIcon(_ status: String) -> some View {
        ZStack {
            switch status {
            case "completed":
                Image(systemName: "checkmark.circle")
                    .resizable()
                    .scaledToFit()
                    .foregroundStyle(.green)
                    .frame(width: 16, height: 16)
            case "in_progress":
                ProgressView()
                    .controlSize(.small)
                    .tint(DSHColor.ocean)
                    .frame(width: 16, height: 16)
            default:
                Image(systemName: "circle.dotted")
                    .resizable()
                    .scaledToFit()
                    .foregroundStyle(.tertiary)
                    .frame(width: 16, height: 16)
            }
        }
        // Every state owns the same layout slot. Changing status must only
        // replace the glyph, never alter the width available to task text.
        .frame(width: 20, height: 20)
    }

    private func goalActionButton(_ image: String, _ label: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: image)
                .appFont(.system(size: 20, weight: .medium))
                .frame(width: 30, height: 30)
        }
        .buttonStyle(.plain)
        .foregroundStyle(.secondary)
        .accessibilityLabel(label)
    }

    private func goalPhaseLabel(_ phase: String) -> String {
        switch phase {
        case "active": "进行中的目标"
        case "paused": "已暂停的目标"
        case "blocked": "受阻的目标"
        default: "当前目标"
        }
    }
}

private struct ConversationDefaultModelSummary: View {
    let title: String
    let effortTitle: String?
    let isLoading: Bool

    var body: some View {
        HStack(spacing: 5) {
            if isLoading {
                ProgressView().controlSize(.mini)
                Text("读取默认模型…")
            } else {
                Text(title)
                    .lineLimit(1)
                    .truncationMode(.tail)
                if let effortTitle {
                    Text(effortTitle)
                        .appFont(.caption2.weight(.semibold))
                        .foregroundStyle(.primary)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 3)
                        .background(DSHColor.purple.opacity(0.14), in: Capsule())
                }
            }
        }
        .appFont(.caption.weight(.medium))
        .foregroundStyle(.secondary)
        .frame(maxWidth: .infinity, alignment: .trailing)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(
            String(
                localized: "a11y.defaults.model",
                defaultValue: "新会话默认模型：\(title)\(effortTitle.map { String(localized: "defaults.effort.suffix", defaultValue: "，推理等级 \($0)") } ?? "")"
            )
        )
    }
}

private struct ConversationModelMenu: View {
    let groups: [GatewayModelGroup]
    let selection: GatewayModelSelection?
    let efforts: [GatewayReasoningEffort]
    let title: String
    let effortTitle: String?
    let isLoading: Bool
    let isDisabled: Bool
    let onSelectModel: (GatewayModelGroup, GatewayModelItem) -> Void
    let onSelectEffort: (GatewayReasoningEffort) -> Void

    var body: some View {
        Menu {
            ConversationModelMenuContent(
                groups: groups,
                selection: selection,
                efforts: efforts,
                onSelectModel: onSelectModel,
                onSelectEffort: onSelectEffort
            )
        } label: {
            ConversationModelMenuLabel(title: title, effortTitle: effortTitle, isLoading: isLoading)
        }
        .disabled(isDisabled)
    }
}

private struct ConversationModelMenuContent: View {
    let groups: [GatewayModelGroup]
    let selection: GatewayModelSelection?
    let efforts: [GatewayReasoningEffort]
    let onSelectModel: (GatewayModelGroup, GatewayModelItem) -> Void
    let onSelectEffort: (GatewayReasoningEffort) -> Void

    var body: some View {
        if groups.isEmpty {
            Text("正在读取模型…")
        } else {
            // 保持单层原生菜单，避免子菜单在外层动画结束前解析临时锚点而偏移或裁切。
            ForEach(groups) { group in
                Section(group.name) {
                    ForEach(group.models) { model in
                        Button {
                            onSelectModel(group, model)
                        } label: {
                            Label(
                                model.name,
                                systemImage: selection?.provider == group.id && selection?.model == model.id
                                    ? "checkmark" : "circle"
                            )
                        }
                    }
                }
            }
            if !efforts.isEmpty {
                Section("推理等级") {
                    ForEach(efforts) { effort in
                        Button {
                            onSelectEffort(effort)
                        } label: {
                            Label(
                                effort.name,
                                systemImage: effort.id == selection?.reasoningEffort ? "checkmark" : "circle"
                            )
                        }
                    }
                }
            }
        }
    }
}

private struct ConversationModelMenuLabel: View {
    let title: String
    let effortTitle: String?
    let isLoading: Bool

    var body: some View {
        HStack(spacing: 5) {
            if isLoading {
                ProgressView().controlSize(.mini)
            } else {
                Text(title)
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .layoutPriority(1)
                if let effortTitle {
                    Text(effortTitle)
                        .appFont(.caption2.weight(.semibold))
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                        .fixedSize(horizontal: true, vertical: false)
                        .layoutPriority(2)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 3)
                        .background(DSHColor.purple.opacity(0.16), in: Capsule())
                }
            }
            Image(systemName: "chevron.up.chevron.down")
                .appFont(.system(size: 8, weight: .semibold))
                .foregroundStyle(.tertiary)
        }
        .appFont(.caption.weight(.medium))
        .foregroundStyle(.secondary)
        .frame(maxWidth: .infinity, alignment: .trailing)
        .contentShape(Rectangle())
    }
}

/// 在 UIKit 消息视口上方直接采样背景，渐变只作用于玻璃层，不栅格化正文。
private struct ConversationTopGlass: UIViewRepresentable {
    final class GlassView: UIVisualEffectView {
        private let fadeMask = CAGradientLayer()

        init() {
            super.init(effect: UIBlurEffect(style: .systemUltraThinMaterial))
            isUserInteractionEnabled = false
            accessibilityElementsHidden = true
            fadeMask.colors = [UIColor.black.cgColor, UIColor.black.cgColor, UIColor.clear.cgColor]
            layer.mask = fadeMask
        }

        required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

        override func layoutSubviews() {
            super.layoutSubviews()
            CATransaction.begin()
            CATransaction.setDisableActions(true)
            fadeMask.frame = bounds
            fadeMask.locations = [0, NSNumber(value: max(0, 1 - 18 / max(bounds.height, 1))), 1]
            CATransaction.commit()
        }
    }

    func makeUIView(context: Context) -> GlassView { GlassView() }
    func updateUIView(_ view: GlassView, context: Context) {}
}

private struct SessionPageIndicator: View {
    let selection: Int
    let sessionID: String?
    @State private var isVisible = false

    var body: some View {
        HStack(spacing: 5) {
            ForEach(0..<2) { page in
                Circle()
                    .fill(Color.primary.opacity(selection == page ? 0.65 : 0.2))
                    .frame(width: 5, height: 5)
            }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
        .background(.regularMaterial, in: Capsule())
        .opacity(isVisible ? 1 : 0)
        .task(id: "\(sessionID ?? "__new__")-\(selection)") {
            isVisible = true
            do {
                try await Task.sleep(for: .seconds(2))
            } catch {
                return
            }
            withAnimation(.easeOut(duration: 0.3)) { isVisible = false }
        }
    }
}

private struct PersistentSessionPager<ConversationPage: View, TrajectoryPage: View>: View {
    @Binding var selection: Int
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    private let conversationPage: ConversationPage
    private let trajectoryPage: TrajectoryPage
    @State private var dragTranslation: CGFloat = 0
    @State private var isPageSwipeActive = false
    @State private var dragAxis: SessionPageDragAxis?
    @State private var horizontalScrollRegions: [CGRect] = []

    init(
        selection: Binding<Int>,
        @ViewBuilder conversation: () -> ConversationPage,
        @ViewBuilder trajectory: () -> TrajectoryPage
    ) {
        _selection = selection
        conversationPage = conversation()
        trajectoryPage = trajectory()
    }

    var body: some View {
        GeometryReader { geometry in
            let pageWidth = max(geometry.size.width, 1)

            HStack(spacing: 0) {
                conversationPage
                    .frame(width: pageWidth, height: geometry.size.height)
                    .allowsHitTesting(selection == 0 && !isPageSwipeActive)
                    .accessibilityHidden(selection != 0)

                trajectoryPage
                    .frame(width: pageWidth, height: geometry.size.height)
                    .allowsHitTesting(selection == 1 && !isPageSwipeActive)
                    .accessibilityHidden(selection != 1)
            }
            .frame(width: pageWidth * 2, alignment: .leading)
            .offset(x: -CGFloat(selection) * pageWidth + constrainedDrag)
            .contentShape(Rectangle())
            .simultaneousGesture(
                pageGesture(
                    pageWidth: pageWidth,
                    pagerFrame: geometry.frame(in: .global)
                )
            )
        }
        .clipped()
        .accessibilityElement(children: .contain)
        .accessibilityLabel("会话页面")
        .accessibilityValue(selection == 0 ? Text("对话") : Text("轨迹"))
        .accessibilityAction(named: selection == 0 ? Text("切换到轨迹") : Text("切换到对话")) {
            withAnimation(reduceMotion ? nil : .snappy(duration: 0.28)) {
                selection = selection == 0 ? 1 : 0
            }
        }
        .onPreferenceChange(SessionPagerHorizontalScrollRegionsKey.self) { horizontalScrollRegions = $0 }
    }

    private var constrainedDrag: CGFloat {
        let isPastLeadingEdge = selection == 0 && dragTranslation > 0
        let isPastTrailingEdge = selection == 1 && dragTranslation < 0
        return isPastLeadingEdge || isPastTrailingEdge
            ? dragTranslation * 0.18
            : dragTranslation
    }

    private func pageGesture(pageWidth: CGFloat, pagerFrame: CGRect) -> some Gesture {
        DragGesture(minimumDistance: 12, coordinateSpace: .global)
            .onChanged { value in
                if dragAxis == nil {
                    let isSystemBackGesture = value.startLocation.x - pagerFrame.minX <= 28
                        && value.translation.width > 0
                    // 翻页 HStack 本身有整页偏移，局部触点不能再叠加视口原点。
                    // 时间轴从触点开始独占整段手势，内容未溢出或滚到边界也不交给翻页。
                    if horizontalScrollRegions.contains(where: { $0.contains(value.startLocation) })
                        || NestedHorizontalScrollResolver.containsScrollableView(at: value.startLocation) {
                        dragAxis = .nestedHorizontalScroll
                    } else if isSystemBackGesture {
                        // The NavigationStack's native edge-pop gesture owns
                        // this region. Keeping the pager inert here prevents a
                        // right swipe from moving the pages underneath the
                        // interactive navigation transition.
                        dragAxis = .navigationBack
                    } else {
                        dragAxis = abs(value.translation.width) > abs(value.translation.height)
                            ? .horizontal
                            : .vertical
                    }
                }
                guard dragAxis == .horizontal else { return }
                dragTranslation = value.translation.width
                isPageSwipeActive = true
            }
            .onEnded { value in
                let predicted = value.predictedEndTranslation
                let isHorizontal = isPageSwipeActive
                    && abs(predicted.width) > abs(predicted.height)
                let threshold = min(90, pageWidth * 0.18)
                let target: Int

                if isHorizontal, predicted.width < -threshold {
                    target = 1
                } else if isHorizontal, predicted.width > threshold {
                    target = 0
                } else {
                    target = selection
                }

                withAnimation(.snappy(duration: 0.28)) {
                    selection = target
                    dragTranslation = 0
                }

                // Keep descendant controls disabled until the touch-up event
                // has finished propagating. Otherwise a trajectory row's
                // Button can interpret the end of the page swipe as a tap.
                DispatchQueue.main.async {
                    isPageSwipeActive = false
                    dragAxis = nil
                }
            }
    }
}

private struct SessionPagerHorizontalScrollRegionsKey: PreferenceKey {
    static let defaultValue: [CGRect] = []

    static func reduce(value: inout [CGRect], nextValue: () -> [CGRect]) {
        value.append(contentsOf: nextValue())
    }
}

extension View {
    /// 横向交互区从触点开始持有手势，禁止父级把边界拖动解释成翻页。
    func sessionPagerHorizontalScrollRegion() -> some View {
        background {
            GeometryReader { geometry in
                Color.clear.preference(
                    key: SessionPagerHorizontalScrollRegionsKey.self,
                    value: [geometry.frame(in: .global)]
                )
            }
        }
    }
}

private enum SessionPageDragAxis {
    case horizontal
    case vertical
    case navigationBack
    case nestedHorizontalScroll
}

/// SwiftUI's nested horizontal ScrollView is backed by UIScrollView even when
/// it lives inside a UIHostingConfiguration collection cell. Resolve gesture
/// ownership from the actual hit-test chain at touch-down: a child that has
/// horizontal overflow wins; otherwise the session pager may switch tabs.
@MainActor
private enum NestedHorizontalScrollResolver {
    static func containsScrollableView(at globalPoint: CGPoint) -> Bool {
        guard let window = keyWindow else { return false }
        let point = window.convert(globalPoint, from: nil)
        var hitView = window.hitTest(point, with: nil)

        while let view = hitView {
            if let scrollView = view as? UIScrollView,
               scrollView.isScrollEnabled,
               scrollView.contentSize.width > scrollView.bounds.width + 1 {
                return true
            }
            hitView = view.superview
        }
        return false
    }

    private static var keyWindow: UIWindow? {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first(where: \.isKeyWindow)
    }
}

/// 按实际内容收缩；目录较长时保留滚动，标题和加载条也计入高度上限。
private struct ContentSizedCommandScrollView<Content: View>: View {
    @State private var contentHeight: CGFloat = 0
    @ViewBuilder var content: () -> Content

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0, content: content)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background {
                    // iOS 16.1 无 onGeometryChange，改用 GeometryReader 上报高度。
                    GeometryReader { proxy in
                        Color.clear
                            .onAppear { contentHeight = proxy.size.height }
                            .onChangeCompat(of: proxy.size.height) { _, height in
                                contentHeight = height
                            }
                    }
                }
        }
        .frame(height: min(contentHeight, 300))
    }
}

private struct ComposerHeightPreferenceKey: PreferenceKey {
    static var defaultValue: CGFloat = 168

    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = nextValue()
    }
}

/// Reads the physical window inset even when the SwiftUI conversation surface
/// deliberately draws through the container safe area. This keeps glass and
/// shadows continuous while the interactive composer still avoids the Home
/// Indicator on every device shape.
private struct WindowBottomSafeAreaReader: UIViewRepresentable {
    @Binding var bottomInset: CGFloat
    @Binding var windowHeight: CGFloat

    func makeUIView(context: Context) -> SafeAreaReportingView {
        let view = SafeAreaReportingView()
        view.onBottomInsetChange = publish
        view.onWindowHeightChange = publishHeight
        return view
    }

    func updateUIView(_ view: SafeAreaReportingView, context: Context) {
        view.onBottomInsetChange = publish
        view.onWindowHeightChange = publishHeight
        view.publishBottomInset()
    }

    private func publish(_ inset: CGFloat) {
        guard abs(bottomInset - inset) > 0.5 else { return }
        DispatchQueue.main.async {
            bottomInset = inset
        }
    }

    private func publishHeight(_ height: CGFloat) {
        guard height > 0, abs(windowHeight - height) > 0.5 else { return }
        DispatchQueue.main.async { windowHeight = height }
    }

    final class SafeAreaReportingView: UIView {
        var onBottomInsetChange: ((CGFloat) -> Void)?
        var onWindowHeightChange: ((CGFloat) -> Void)?

        override func layoutSubviews() {
            super.layoutSubviews()
            publishBottomInset()
        }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            publishBottomInset()
        }

        override func safeAreaInsetsDidChange() {
            super.safeAreaInsetsDidChange()
            publishBottomInset()
        }

        func publishBottomInset() {
            let inset = window?.safeAreaInsets.bottom ?? safeAreaInsets.bottom
            onBottomInsetChange?(inset)
            if let height = window?.bounds.height { onWindowHeightChange?(height) }
        }
    }
}

private struct ContextUsageRing: View {
    let progress: Double

    var body: some View {
        ZStack {
            Circle()
                .stroke(Color.secondary.opacity(0.22), lineWidth: 3)
            Circle()
                .trim(from: 0, to: progress)
                .stroke(DSHColor.ocean, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                .rotationEffect(.degrees(-90))
        }
        .frame(width: 18, height: 18)
        .padding(2)
        .contentShape(Circle())
    }
}

private enum SessionStatsFormatter {
    static func compactLine(_ snapshot: GatewaySessionStatsSnapshot) -> String {
        let stats = snapshot.stats
        var sections: [String] = []
        if let turnsStepsLine = turnsStepsLine(stats) {
            sections.append(turnsStepsLine)
        }
        let timings = [
            stats?.llmMs.map { "LLM \(duration($0))" },
            stats?.toolMs.map { String(localized: "stats.toolcalls.duration", defaultValue: "工具调用 \(duration($0))") }
        ].compactMap { $0 }.joined(separator: " · ")
        if !timings.isEmpty { sections.append(timings) }
        let averageTTFT = averageTTFT(stats)
        let throughput = throughput(stats)
        let performance = [
            averageTTFT.map { String(localized: "stats.ttft.avg", defaultValue: "首 token 平均 \(duration($0))") },
            throughput.map { "\(compactDecimal($0)) tok/s" }
        ].compactMap { $0 }.joined(separator: " · ")
        if !performance.isEmpty { sections.append(performance) }
        if let cacheRate = cacheHitRate(snapshot.tokenUsage?.totals) {
            sections.append(String(localized: "stats.cachehit.percent", defaultValue: "缓存命中 \(Int((cacheRate * 100).rounded()))%"))
        }
        if let input = snapshot.tokenUsage?.totals?.inputTokens {
            sections.append(String(localized: "stats.input.tok", defaultValue: "输入 \(compact(input)) tok"))
        }
        return sections.isEmpty ? String(localized: "正在读取会话统计…") : sections.joined(separator: "  |  ")
    }

    static func turnsStepsLine(_ stats: GatewaySessionStats?) -> String? {
        guard stats?.turns != nil || stats?.steps != nil else { return nil }
        return String(localized: "stats.turns.steps.line", defaultValue: "\(stats?.turns ?? 0) 轮 · \(stats?.steps ?? 0) 步")
    }

    static func duration(_ milliseconds: Double) -> String {
        let seconds = max(0, milliseconds) / 1_000
        if seconds >= 60 {
            let total = Int(seconds.rounded())
            return "\(total / 60)m\(total % 60)s"
        }
        if seconds >= 1 { return "\(compactDecimal(seconds))s" }
        return "\(Int(milliseconds.rounded()))ms"
    }

    static func averageTTFT(_ stats: GatewaySessionStats?) -> Double? {
        guard let total = stats?.ttftMs, let count = stats?.ttftSteps, count > 0 else { return nil }
        return total / Double(count)
    }

    static func throughput(_ stats: GatewaySessionStats?) -> Double? {
        guard let milliseconds = stats?.decodeMs, let tokens = stats?.decodeTokens, milliseconds > 0 else { return nil }
        return Double(tokens) / (milliseconds / 1_000)
    }

    static func cacheHitRate(_ totals: GatewaySessionTokenUsageTotals?) -> Double? {
        guard let totals else { return nil }
        let values = [totals.inputTokens, totals.outputTokens, totals.cacheReadTokens, totals.cacheWriteTokens]
            .compactMap { $0 }
        let total = values.reduce(0, +)
        guard total > 0, let cacheRead = totals.cacheReadTokens else { return nil }
        return min(1, max(0, Double(cacheRead) / Double(total)))
    }

    static func compact(_ value: Int) -> String {
        switch value {
        case 1_000_000...:
            return String(format: value.isMultiple(of: 1_000_000) ? "%.0fM" : "%.1fM", Double(value) / 1_000_000)
        case 1_000...:
            return String(format: "%.1fK", Double(value) / 1_000)
        default:
            return "\(value)"
        }
    }

    static func compactDecimal(_ value: Double) -> String {
        value.rounded() == value ? "\(Int(value))" : String(format: "%.1f", value)
    }
}

private struct SessionStatsSheet: View {
    let snapshot: GatewaySessionStatsSnapshot?
    let sessionTitle: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    if let snapshot, let stats = snapshot.stats {
                        metricsSection(String(localized: "执行")) {
                            metric(String(localized: "轮次"), String(localized: "metric.turns.value", defaultValue: "\(stats.turns ?? 0) 轮"))
                            metric(String(localized: "步骤"), String(localized: "metric.steps.value", defaultValue: "\(stats.steps ?? 0) 步"))
                            metric("LLM", stats.llmMs.map(SessionStatsFormatter.duration) ?? "—")
                            metric(String(localized: "工具调用"), stats.toolMs.map(SessionStatsFormatter.duration) ?? "—")
                            metric(String(localized: "首 token 平均"), SessionStatsFormatter.averageTTFT(stats).map(SessionStatsFormatter.duration) ?? "—")
                            metric(String(localized: "解码吞吐"), SessionStatsFormatter.throughput(stats).map { String(localized: "value.throughput", defaultValue: "\(SessionStatsFormatter.compactDecimal($0)) tok/s") } ?? "—")
                        }

                        if let totals = snapshot.tokenUsage?.totals {
                            metricsSection(String(localized: "Token 用量")) {
                                metric(String(localized: "输入"), token(totals.inputTokens))
                                metric(String(localized: "输出"), token(totals.outputTokens))
                                metric(String(localized: "缓存读取"), token(totals.cacheReadTokens))
                                metric(String(localized: "缓存写入"), token(totals.cacheWriteTokens))
                                metric(String(localized: "推理"), token(totals.reasoningTokens))
                                metric(String(localized: "缓存命中"), SessionStatsFormatter.cacheHitRate(totals).map { String(localized: "value.percent", defaultValue: "\(Int(($0 * 100).rounded()))%") } ?? "—")
                            }
                        }

                        if let pressure = snapshot.contextPressure {
                            metricsSection(String(localized: "上下文")) {
                                metric(String(localized: "当前压力"), token(pressure.pressureTokens))
                                metric(String(localized: "预计用量"), token(pressure.projectedTokens))
                                metric(String(localized: "上下文窗口"), token(pressure.contextWindow))
                            }
                        }

                        if let asOfSeq = snapshot.asOfSeq {
                            Text("数据截至事件 #\(asOfSeq)")
                                .appFont(.caption.monospacedDigit())
                                .foregroundStyle(.tertiary)
                        }
                    } else {
                        DshEmptyState("正在读取会话统计", systemImage: "chart.bar.xaxis", description: Text("统计会在本轮结束后自动更新。"))
                            .frame(maxWidth: .infinity, minHeight: 220)
                    }
                }
                .padding(20)
            }
            .navigationTitle("会话状态")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("完成") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .background(.regularMaterial)
    }

    @ViewBuilder
    private func metricsSection<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title).appFont(.headline)
            VStack(spacing: 0) { content() }
                .padding(.horizontal, 14)
                .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
    }

    private func metric(_ title: String, _ value: String) -> some View {
        HStack {
            Text(title).foregroundStyle(.secondary)
            Spacer()
            Text(value).appFont(.body.monospacedDigit()).fontWeight(.medium)
        }
        .padding(.vertical, 10)
    }

    private func token(_ value: Int?) -> String {
        value.map { "\(SessionStatsFormatter.compact($0)) tok" } ?? "—"
    }
}

private struct SessionStatsPopoverAnchorKey: PreferenceKey {
    static var defaultValue: Anchor<CGRect>? { nil }

    static func reduce(value: inout Anchor<CGRect>?, nextValue: () -> Anchor<CGRect>?) {
        value = nextValue() ?? value
    }
}

private struct SessionAgentPresetPopoverAnchorKey: PreferenceKey {
    static var defaultValue: Anchor<CGRect>? { nil }

    static func reduce(value: inout Anchor<CGRect>?, nextValue: () -> Anchor<CGRect>?) {
        value = nextValue() ?? value
    }
}

/// 模式与统计共用液态玻璃和箭头，原生玻璃自行处理高光与投影。
private struct ComposerPopoverOverlay<Content: View>: View {
    let sourceFrame: CGRect
    let availableSize: CGSize
    let isPresented: Bool
    let onDismiss: () -> Void
    let preferredWidth: CGFloat
    let alignsToTrailing: Bool
    let maximumContentHeight: CGFloat
    let content: Content

    init(
        sourceFrame: CGRect,
        availableSize: CGSize,
        isPresented: Bool,
        onDismiss: @escaping () -> Void,
        preferredWidth: CGFloat = 300,
        alignsToTrailing: Bool = true,
        maximumContentHeight: CGFloat = .infinity,
        @ViewBuilder content: () -> Content
    ) {
        self.sourceFrame = sourceFrame
        self.availableSize = availableSize
        self.isPresented = isPresented
        self.onDismiss = onDismiss
        self.preferredWidth = preferredWidth
        self.alignsToTrailing = alignsToTrailing
        self.maximumContentHeight = maximumContentHeight
        self.content = content()
    }

    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var contentHeight: CGFloat = 240

    private var presentationAnimation: Animation {
        if reduceMotion { return .easeOut(duration: 0.15) }
        return isPresented
            ? .spring(response: 0.32, dampingFraction: 0.88)
            : .easeInOut(duration: 0.18)
    }

    var body: some View {
        let margin: CGFloat = 14
        let gap: CGFloat = 6
        let arrowHeight: CGFloat = 8
        let width = min(preferredWidth, max(1, availableSize.width - margin * 2))
        let above = max(0, sourceFrame.minY - margin - gap)
        let below = max(0, availableSize.height - sourceFrame.maxY - margin - gap)
        let bodyHeight = min(contentHeight, maximumContentHeight)
        let displaysAbove = above >= bodyHeight + arrowHeight || above >= below
        let height = min(bodyHeight + arrowHeight, max(arrowHeight + 1, displaysAbove ? above : below))
        let preferredCenterX = alignsToTrailing ? sourceFrame.maxX - width / 2 : sourceFrame.minX + width / 2
        let centerX = min(max(preferredCenterX, margin + width / 2), availableSize.width - margin - width / 2)
        let centerY = displaysAbove
            ? sourceFrame.minY - gap - height / 2
            : sourceFrame.maxY + gap + height / 2
        let shape = ComposerPopoverShape(
            arrowX: sourceFrame.midX - (centerX - width / 2),
            pointsUp: !displaysAbove
        )
        // 缩放原点使用胶囊中心，即使弹窗显示在胶囊下方，也从触发处展开。
        let scaleAnchor = UnitPoint(
            x: sourceFrame.midX / max(1, availableSize.width),
            y: sourceFrame.midY / max(1, availableSize.height)
        )

        ZStack(alignment: .topLeading) {
            if isPresented {
                Color.clear
                    .contentShape(Rectangle())
                    .onTapGesture(perform: onDismiss)
                    .accessibilityLabel("关闭")
                    .accessibilityAddTraits(.isButton)

                ScrollView {
                    content
                        .background {
                            GeometryReader { proxy in
                                Color.clear
                                    .onAppear { contentHeight = proxy.size.height }
                                    .onChangeCompat(of: proxy.size.height) { _, height in
                                        contentHeight = height
                                    }
                            }
                        }
                }
                .frame(width: width, height: height - arrowHeight)
                .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
                .padding(displaysAbove ? .bottom : .top, arrowHeight)
                .background {
                    if #available(iOS 26.0, *) {
                        Color.clear.glassEffect(.regular, in: shape)
                    } else {
                        shape.fill(.ultraThinMaterial)
                            .overlay {
                                shape.stroke(Color.primary.opacity(0.06), lineWidth: 0.5)
                            }
                            .shadow(color: .black.opacity(colorScheme == .dark ? 0.24 : 0.12), radius: 10, x: 0, y: 3)
                    }
                }
                .accessibilityElement(children: .contain)
                .accessibilityAddTraits(.isModal)
                .accessibilityAction(.escape, onDismiss)
                .position(x: centerX, y: centerY)
                .transition(reduceMotion ? .opacity : .scale(scale: 0.16, anchor: scaleAnchor).combined(with: .opacity))
            }
        }
        .animation(presentationAnimation, value: isPresented)
        .allowsHitTesting(isPresented)
    }
}

/// 箭头与卡片共用一条轮廓，材质、描边和紧凑阴影不会在连接处叠加。
private struct ComposerPopoverShape: Shape {
    let arrowX: CGFloat
    let pointsUp: Bool

    func path(in rect: CGRect) -> Path {
        let arrowHeight = min(8, rect.height / 3)
        let top = rect.minY + arrowHeight
        let bottom = rect.maxY
        let radius = min(24, (bottom - top) / 2, rect.width / 2)
        let halfArrow = min(10, max(0, (rect.width - radius * 2) / 2))
        let center = min(max(arrowX, rect.minX + radius + halfArrow), rect.maxX - radius - halfArrow)
        var path = Path()
        path.move(to: CGPoint(x: rect.minX + radius, y: top))
        path.addLine(to: CGPoint(x: center - halfArrow, y: top))
        path.addQuadCurve(
            to: CGPoint(x: center - 2, y: rect.minY + 2),
            control: CGPoint(x: center - 4, y: top)
        )
        path.addQuadCurve(
            to: CGPoint(x: center + 2, y: rect.minY + 2),
            control: CGPoint(x: center, y: rect.minY - 2)
        )
        path.addQuadCurve(
            to: CGPoint(x: center + halfArrow, y: top),
            control: CGPoint(x: center + 4, y: top)
        )
        path.addLine(to: CGPoint(x: rect.maxX - radius, y: top))
        path.addQuadCurve(to: CGPoint(x: rect.maxX, y: top + radius), control: CGPoint(x: rect.maxX, y: top))
        path.addLine(to: CGPoint(x: rect.maxX, y: bottom - radius))
        path.addQuadCurve(to: CGPoint(x: rect.maxX - radius, y: bottom), control: CGPoint(x: rect.maxX, y: bottom))
        path.addLine(to: CGPoint(x: rect.minX + radius, y: bottom))
        path.addQuadCurve(to: CGPoint(x: rect.minX, y: bottom - radius), control: CGPoint(x: rect.minX, y: bottom))
        path.addLine(to: CGPoint(x: rect.minX, y: top + radius))
        path.addQuadCurve(to: CGPoint(x: rect.minX + radius, y: top), control: CGPoint(x: rect.minX, y: top))
        path.closeSubpath()
        if pointsUp { return path }
        return path.applying(CGAffineTransform(translationX: 0, y: rect.minY + rect.maxY).scaledBy(x: 1, y: -1))
    }
}

private struct SessionStatsPopover: View {
    let snapshot: GatewaySessionStatsSnapshot
    let onViewFullStats: () -> Void

    private var stats: GatewaySessionStats? { snapshot.stats }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let turnsSteps = SessionStatsFormatter.turnsStepsLine(stats) {
                row(String(localized: "轮次 · 步骤"), turnsSteps)
            }
            if let llm = stats?.llmMs {
                row("LLM", SessionStatsFormatter.duration(llm))
            }
            if let tool = stats?.toolMs {
                row(String(localized: "工具调用"), SessionStatsFormatter.duration(tool))
            }
            if let ttft = SessionStatsFormatter.averageTTFT(stats) {
                row(String(localized: "首 token 平均"), SessionStatsFormatter.duration(ttft))
            }
            if let throughput = SessionStatsFormatter.throughput(stats) {
                row(String(localized: "解码吞吐"), String(localized: "value.throughput", defaultValue: "\(SessionStatsFormatter.compactDecimal(throughput)) tok/s"))
            }
            if let cacheRate = SessionStatsFormatter.cacheHitRate(snapshot.tokenUsage?.totals) {
                row(String(localized: "缓存命中"), String(localized: "value.percent", defaultValue: "\(Int((cacheRate * 100).rounded()))%"))
            }
            if let input = snapshot.tokenUsage?.totals?.inputTokens {
                row(String(localized: "输入"), String(localized: "row.input.value", defaultValue: "\(SessionStatsFormatter.compact(input)) tok"))
            }

            Divider()

            Button(action: onViewFullStats) {
                HStack(spacing: 6) {
                    Text("查看完整统计")
                    Spacer()
                }
                .appFont(.subheadline.weight(.medium))
                .foregroundStyle(.primary)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 22)
        .padding(.vertical, 16)
        .frame(maxWidth: .infinity)
    }

    private func row(_ title: String, _ value: String) -> some View {
        HStack {
            Text(title).foregroundStyle(.secondary)
            Spacer()
            Text(value).monospacedDigit().fontWeight(.medium)
        }
        .appFont(.subheadline)
    }
}

private struct ContextUsagePopover: View {
    let snapshot: GatewayContextSnapshot?

    private var pressureTokens: Int { snapshot?.pressure?.pressureTokens ?? 0 }
    private var contextWindow: Int { snapshot?.pressure?.contextWindow ?? 0 }
    private var progress: Double {
        guard contextWindow > 0 else { return 0 }
        return min(1, max(0, Double(pressureTokens) / Double(contextWindow)))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .firstTextBaseline) {
                Text("上下文已用")
                    .foregroundStyle(.secondary)
                Text("\(Int((progress * 100).rounded()))%")
                    .fontWeight(.semibold)
                Spacer()
                Text("~\(compact(pressureTokens)) / \(compact(contextWindow))")
                    .appFont(.subheadline.monospacedDigit())
            }

            ProgressView(value: progress)
                .tint(DSHColor.ocean)

            if let breakdown = snapshot?.breakdown {
                usageRow(String(localized: "系统提示词"), value: breakdown.systemTokens, color: .gray)
                usageRow(String(localized: "usage.row.tools", defaultValue: "工具"), value: breakdown.toolsTokens, color: DSHColor.purple)
                usageRow(String(localized: "对话消息"), value: breakdown.messageTokens, color: DSHColor.ocean)
            } else if let usage = snapshot?.tokenUsage {
                usageRow(String(localized: "未缓存输入"), value: usage.uncachedInputTokens, color: DSHColor.ocean)
                usageRow(String(localized: "缓存读取"), value: usage.cacheReadTokens, color: DSHColor.purple)
                usageRow(String(localized: "模型输出"), value: usage.outputTokens, color: DSHColor.orange)
            } else {
                HStack(spacing: 8) {
                    ProgressView().controlSize(.small)
                    Text("正在读取上下文用量…")
                        .appFont(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .padding(18)
        .frame(width: 300)
        .background(.ultraThinMaterial)
    }

    private func usageRow(_ title: String, value: Int?, color: Color) -> some View {
        HStack(spacing: 10) {
            RoundedRectangle(cornerRadius: 3, style: .continuous)
                .fill(color)
                .frame(width: 12, height: 12)
            Text(title).foregroundStyle(.secondary)
            Spacer()
            Text(value.map { "~\(compact($0))" } ?? "—")
                .monospacedDigit()
        }
        .appFont(.subheadline)
    }

    private func compact(_ value: Int) -> String {
        switch value {
        case 1_000_000...:
            return String(format: value.isMultiple(of: 1_000_000) ? "%.0fM" : "%.1fM", Double(value) / 1_000_000)
        case 1_000...:
            return String(format: "%.1fK", Double(value) / 1_000)
        default:
            return "\(value)"
        }
    }
}

struct ConversationProcessGroup: Identifiable {
    let id: String
    let items: [ConversationItem]

    var duration: TimeInterval {
        guard let first = items.first?.date, let last = items.last?.date else { return 0 }
        return max(0, last.timeIntervalSince(first))
    }
}

private struct ConversationDisplayEntry: Identifiable {
    enum Content {
        case message(ConversationItem)
        case process(ConversationProcessGroup)
    }

    let id: String
    let content: Content
    var showsCopyButton: Bool

    static func make(from items: [ConversationItem]) -> [ConversationDisplayEntry] {
        var result: [ConversationDisplayEntry] = []
        var processItems: [ConversationItem] = []

        func flushProcess() {
            guard let first = processItems.first else { return }
            let group = ConversationProcessGroup(
                id: first.kind == .status ? "message-\(first.id)" : "process-\(first.id)",
                items: processItems
            )
            result.append(.init(id: group.id, content: .process(group), showsCopyButton: false))
            processItems.removeAll(keepingCapacity: true)
        }

        for item in items {
            switch item.kind {
            case .context, .reasoning, .tool, .jsonTool, .toolResult:
                processItems.append(item)
            case .status:
                flushProcess()
                processItems.append(item)
            case .user, .assistant, .system:
                flushProcess()
                result.append(.init(id: "message-\(item.id)", content: .message(item), showsCopyButton: false))
            }
        }
        flushProcess()

        // User messages are always copyable.  Agent messages are copyable only
        // when they are the last formal response before the next user message;
        // intermediate narration followed by another assistant response stays
        // visually quiet, matching WebUI's final-answer action placement.
        for index in result.indices {
            guard case .message(let item) = result[index].content else { continue }
            if item.kind == .user {
                result[index].showsCopyButton = true
                continue
            }
            guard item.kind == .assistant else { continue }
            let nextConversationalMessage = result[(index + 1)...].lazy.compactMap { entry -> ConversationItem.Kind? in
                guard case .message(let following) = entry.content,
                      following.kind == .user || following.kind == .assistant else { return nil }
                return following.kind
            }.first
            result[index].showsCopyButton = nextConversationalMessage != .assistant
        }
        return result
    }
}

struct ConversationProcessTool: Identifiable {
    let id: String
    let call: ConversationItem?
    let result: ConversationItem?

    var activitySummary: ToolActivitySummary {
        ToolActivitySummaryFormatter.shared.summarize(
            name: call?.title ?? result?.title ?? "工具",
            arguments: call?.text ?? ""
        )
    }

    var isExpandable: Bool {
        call?.text.isEmpty == false || result?.text.isEmpty == false
    }
}

enum ConversationProcessContent: Identifiable {
    case context(ConversationItem)
    case reasoning(ConversationItem)
    case tool(ConversationProcessTool)

    var id: String {
        switch self {
        case .context(let item): "context-\(item.id)"
        case .reasoning(let item): "reason-\(item.id)"
        case .tool(let tool): "tool-\(tool.id)"
        }
    }
}

extension ConversationProcessGroup {
    var command: ConversationItem? {
        items.first?.kind == .status ? items.first : nil
    }

    var detailItems: ArraySlice<ConversationItem> {
        command == nil ? items[...] : items.dropFirst()
    }

    var contents: [ConversationProcessContent] {
        var contents: [ConversationProcessContent] = []
        var tools: [ConversationProcessTool] = []

        for item in detailItems {
            switch item.kind {
            case .context:
                contents.append(.context(item))
            case .reasoning:
                contents.append(.reasoning(item))
            case .tool, .jsonTool:
                tools.append(.init(id: item.id, call: item, result: nil))
            case .toolResult:
                if let index = tools.firstIndex(where: { $0.result == nil }) {
                    let existing = tools[index]
                    tools[index] = .init(id: existing.id, call: existing.call, result: item)
                } else {
                    tools.append(.init(id: item.id, call: nil, result: item))
                }
            case .user, .assistant, .status, .system:
                break
            }
        }
        contents.append(contentsOf: tools.map(ConversationProcessContent.tool))
        return contents
    }

    var toolCount: Int {
        contents.reduce(0) { count, content in
            if case .tool = content { count + 1 } else { count }
        }
    }

    var contextCount: Int {
        contents.reduce(0) { count, content in
            if case .context = content { count + 1 } else { count }
        }
    }

    var reasoningText: String {
        contents.compactMap { content in
            if case .reasoning(let item) = content { item.text } else { nil }
        }.filter { !$0.isEmpty }.joined(separator: "\n\n")
    }

    var contexts: [ConversationItem] {
        contents.compactMap { content in
            if case .context(let item) = content { item } else { nil }
        }
    }

    var tools: [ConversationProcessTool] {
        contents.compactMap { content in
            if case .tool(let tool) = content { tool } else { nil }
        }
    }

    var commandHasDetailedText: Bool {
        guard let text = command?.text else { return false }
        return text.contains("\n") || text.count > 96
    }

    var isExpandable: Bool {
        command == nil || commandHasDetailedText || !detailItems.isEmpty
    }

    var processTitle: String {
        if let command {
            let preview = commandPreview(command.text)
            return preview.isEmpty ? command.title : "\(command.title) · \(preview)"
        }
        if contextCount > 0, toolCount == 0, reasoningText.isEmpty {
            return contextCount == 1
                ? String(localized: "上下文")
                : String(localized: "context.items.count", defaultValue: "\(contextCount) 项上下文")
        }
        let base: String
        if duration >= 60 {
            base = String(
                localized: "duration.minutes-seconds",
                defaultValue: "耗时 \(Int(duration) / 60) 分钟 \(Int(duration) % 60) 秒"
            )
        } else if duration >= 1 {
            base = String(
                localized: "duration.seconds",
                defaultValue: "耗时 \(Int(duration.rounded())) 秒"
            )
        } else {
            base = String(localized: "思考过程")
        }
        var details: [String] = []
        if contextCount > 0 {
            details.append(String(localized: "context.items.count", defaultValue: "\(contextCount) 项上下文"))
        }
        if toolCount > 0 {
            details.append(String(localized: "toolcall.count", defaultValue: "\(toolCount) 次工具调用"))
        }
        return details.isEmpty ? base : "\(base) · \(details.joined(separator: " · "))"
    }

    func commandPreview(_ text: String) -> String {
        text.replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

enum ConversationProcessEventRole {
    case context
}

/// A process group is projected into independent list items. Disclosure
/// headers never change their own height; expanding a node only inserts or
/// removes descendants after that stable header item.
struct ConversationProcessNode: Identifiable {
    enum Content {
        case groupHeader(ConversationProcessGroup, isExpanded: Bool, isExpandable: Bool)
        case commandDetail(ConversationItem)
        case eventHeader(
            ConversationItem,
            role: ConversationProcessEventRole,
            isExpanded: Bool,
            isExpandable: Bool
        )
        case eventDetail(ConversationItem, role: ConversationProcessEventRole)
        case reasoningHeader(String, isExpanded: Bool)
        case reasoningDetail(String)
        case toolBundleHeader([ConversationProcessTool], isExpanded: Bool)
        case toolHeader(ConversationProcessTool, isExpanded: Bool, isExpandable: Bool)
        case toolDetail(ConversationProcessTool)
    }

    let id: String
    let parentID: String?
    let depth: Int
    let revision: Int
    let content: Content

    static func make(
        from group: ConversationProcessGroup,
        expandedNodeIDs: Set<String>
    ) -> [ConversationProcessNode] {
        let groupExpanded = expandedNodeIDs.contains(group.id)
        var nodes: [ConversationProcessNode] = [
            .init(
                id: group.id,
                parentID: nil,
                depth: 0,
                revision: revision(group.processTitle, group.command?.isError == true, groupExpanded),
                content: .groupHeader(
                    group,
                    isExpanded: groupExpanded,
                    isExpandable: group.isExpandable
                )
            )
        ]
        guard group.isExpandable, groupExpanded else { return nodes }

        if let command = group.command, group.commandHasDetailedText {
            nodes.append(.init(
                id: "\(group.id)/command-detail",
                parentID: group.id,
                depth: 1,
                revision: revision(command.title, command.text, command.isError),
                content: .commandDetail(command)
            ))
        }

        for context in group.contexts {
            let headerID = "\(group.id)/context/\(context.id)"
            let isExpandable = !context.text.isEmpty
            let isExpanded = expandedNodeIDs.contains(headerID)
            nodes.append(.init(
                id: headerID,
                parentID: group.id,
                depth: 1,
                revision: revision(context.title, preview(context.text), isExpanded),
                content: .eventHeader(
                    context,
                    role: .context,
                    isExpanded: isExpanded,
                    isExpandable: isExpandable
                )
            ))
            if isExpanded, isExpandable {
                nodes.append(.init(
                    id: "\(headerID)/detail",
                    parentID: headerID,
                    depth: 2,
                    revision: revision(context.text),
                    content: .eventDetail(context, role: .context)
                ))
            }
        }

        if !group.reasoningText.isEmpty {
            let headerID = "\(group.id)/reasoning"
            let isExpanded = expandedNodeIDs.contains(headerID)
            nodes.append(.init(
                id: headerID,
                parentID: group.id,
                depth: 1,
                revision: revision(preview(group.reasoningText), isExpanded),
                content: .reasoningHeader(group.reasoningText, isExpanded: isExpanded)
            ))
            if isExpanded {
                nodes.append(.init(
                    id: "\(headerID)/detail",
                    parentID: headerID,
                    depth: 2,
                    revision: revision(group.reasoningText),
                    content: .reasoningDetail(group.reasoningText)
                ))
            }
        }

        if !group.tools.isEmpty {
            let bundleID = "\(group.id)/tools"
            let bundleExpanded = expandedNodeIDs.contains(bundleID)
            nodes.append(.init(
                id: bundleID,
                parentID: group.id,
                depth: 1,
                revision: revision(group.tools.map(toolSummary).joined(separator: "|"), bundleExpanded),
                content: .toolBundleHeader(group.tools, isExpanded: bundleExpanded)
            ))
            if bundleExpanded {
                for tool in group.tools {
                    let headerID = "\(bundleID)/\(tool.id)"
                    let toolExpanded = tool.isExpandable && expandedNodeIDs.contains(headerID)
                    nodes.append(.init(
                        id: headerID,
                        parentID: bundleID,
                        depth: 2,
                        revision: revision(toolSummary(tool), toolExpanded),
                        content: .toolHeader(
                            tool,
                            isExpanded: toolExpanded,
                            isExpandable: tool.isExpandable
                        )
                    ))
                    if toolExpanded, tool.isExpandable {
                        nodes.append(.init(
                            id: "\(headerID)/detail",
                            parentID: headerID,
                            depth: 3,
                            revision: revision(tool.call?.text ?? "", tool.result?.text ?? "", tool.result?.isError == true),
                            content: .toolDetail(tool)
                        ))
                    }
                }
            }
        }
        return nodes
    }

    private static func preview(_ text: String) -> String {
        text.replacingOccurrences(of: "\n", with: " ")
            .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func toolSummary(_ tool: ConversationProcessTool) -> String {
        [
            tool.call?.title,
            tool.result?.title,
            tool.call?.text,
            tool.result == nil ? "pending" : "finished",
            tool.result?.isError == true ? "error" : "ok"
        ].compactMap { $0 }.joined(separator: "|")
    }

    private static func revision(_ values: Any...) -> Int {
        var hasher = Hasher()
        values.forEach { hasher.combine(String(describing: $0)) }
        return hasher.finalize()
    }
}

private struct ConversationProcessHeaderRow: View {
    let group: ConversationProcessGroup
    let expanded: Bool
    let isExpandable: Bool
    let onToggle: () -> Void

    var body: some View {
        Group {
            if isExpandable {
                Button(action: onToggle) { header.contentShape(Rectangle()) }
                    .buttonStyle(.plain)
            } else {
                header
            }
        }
        .padding(.vertical, 7)
        .overlay(alignment: .bottom) {
            Rectangle().fill(.gray.opacity(0.16)).frame(height: 1)
        }
    }

    private var header: some View {
        HStack(spacing: 7) {
            if let command = group.command {
                Image(systemName: "terminal")
                    .appFont(.system(size: 13, weight: .medium))
                    .foregroundStyle(command.isError ? .red : .secondary)
                Text(command.title)
                    .appFont(.subheadline.weight(.medium))
                    .foregroundStyle(command.isError ? .red : .primary)
                    .lineLimit(1)
                Text("·").appFont(.subheadline).foregroundStyle(.tertiary)
                Text(group.commandPreview(command.text))
                    .appFont(.subheadline)
                    .foregroundStyle(command.isError ? .red : .secondary)
                    .lineLimit(1)
            } else {
                Text(group.processTitle)
                    .appFont(.subheadline.weight(.medium))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            if isExpandable {
                Image(systemName: "chevron.right")
                    .appFont(.caption.weight(.semibold))
                    .foregroundStyle(.tertiary)
                    .rotationEffect(.degrees(expanded ? 90 : 0))
            }
        }
    }
}

private struct ConversationCommandDetailRow: View {
    let command: ConversationItem

    var body: some View {
        Text(command.text)
            .appFont(.system(.subheadline, design: .monospaced))
            .foregroundStyle(command.isError ? .red : .primary)
            .textSelection(.enabled)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .stroke(.primary.opacity(0.08), lineWidth: 1)
            }
            .padding(.leading, 2)
            .padding(.vertical, 4)
    }
}

private struct ConversationEventHeaderRow: View {
    let item: ConversationItem
    let role: ConversationProcessEventRole
    let expanded: Bool
    let isExpandable: Bool
    let onToggle: () -> Void

    var body: some View {
        Group {
            if isExpandable {
                Button(action: onToggle) { header.contentShape(Rectangle()) }
                    .buttonStyle(.plain)
            } else {
                header
            }
        }
        .padding(.leading, 2)
        .padding(.vertical, 2)
    }

    private var header: some View {
        HStack(spacing: 8) {
            ConversationGlyphImage(glyph: glyph)
                .foregroundStyle(tint)
                .frame(width: 18)
            Text(item.title).foregroundStyle(tint).lineLimit(1)
            let summary = preview(item.text)
            if !summary.isEmpty {
                Text("·").foregroundStyle(.tertiary)
                Text(summary).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer(minLength: 8)
            if isExpandable {
                Image(systemName: "chevron.right")
                    .appFont(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .rotationEffect(.degrees(expanded ? 90 : 0))
            }
        }
        .appFont(.subheadline)
    }

    private var glyph: ConversationGlyph {
        switch role {
        case .context: .asset("DshContextInjection")
        }
    }

    private var tint: Color {
        switch role {
        case .context: .green
        }
    }
}

struct ConversationEventDetailRow: View {
    let item: ConversationItem
    let role: ConversationProcessEventRole

    private var needsViewport: Bool {
        item.text.count > 900 || item.text.components(separatedBy: .newlines).count > 20
    }

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Rectangle().fill(.gray.opacity(0.24)).frame(width: 1)
            Group {
                if needsViewport {
                    ScrollView(.vertical, showsIndicators: true) {
                        MarkdownContent(item.text, compact: true)
                            .foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(10)
                    }
                    .frame(height: 320)
                    .background(
                        Color(uiColor: .secondarySystemFill),
                        in: RoundedRectangle(cornerRadius: 10, style: .continuous)
                    )
                } else {
                    MarkdownContent(item.text, compact: true)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
        .padding(.leading, 7)
        .padding(.bottom, 5)
    }
}

private struct ConversationReasoningHeaderRow: View {
    let text: String
    let expanded: Bool
    let onToggle: () -> Void

    var body: some View {
        Button(action: onToggle) {
            HStack(spacing: 8) {
                ConversationGlyphImage(glyph: .asset("DshThink"))
                    .foregroundStyle(DSHColor.purple)
                    .frame(width: 18)
                Text("Think")
                    .appFont(.subheadline.weight(.medium))
                    .foregroundStyle(DSHColor.purple)
                let summary = preview(text)
                if !summary.isEmpty {
                    Text("·").foregroundStyle(.tertiary)
                    Text(summary)
                        .appFont(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                Spacer(minLength: 8)
                Image(systemName: "chevron.right")
                    .appFont(.caption.weight(.semibold))
                    .foregroundStyle(.tertiary)
                    .rotationEffect(.degrees(expanded ? 90 : 0))
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .padding(.leading, 2)
        .padding(.vertical, 2)
    }
}

private struct ConversationReasoningDetailRow: View {
    let text: String

    private var needsViewport: Bool {
        text.count > 4_000 || text.components(separatedBy: .newlines).count > 60
    }

    var body: some View {
        Group {
            if needsViewport {
                ScrollView(.vertical, showsIndicators: true) {
                    MarkdownContent(text, compact: true)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(10)
                }
                .frame(height: 320)
                .background(Color(uiColor: .secondarySystemFill), in: RoundedRectangle(cornerRadius: 10))
            } else {
                MarkdownContent(text, compact: true)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(.leading, 28)
        .padding(.bottom, 5)
    }
}

private struct ConversationToolBundleHeaderRow: View {
    let tools: [ConversationProcessTool]
    let expanded: Bool
    let onToggle: () -> Void

    var body: some View {
        Button(action: onToggle) {
            HStack(spacing: 8) {
                Image(systemName: "wrench.and.screwdriver")
                    .foregroundStyle(DSHColor.orange)
                    .frame(width: 18)
                Text(bundleTitle)
                    .appFont(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                Spacer(minLength: 8)
                Image(systemName: "chevron.right")
                    .appFont(.caption.weight(.semibold))
                    .foregroundStyle(.tertiary)
                    .rotationEffect(.degrees(expanded ? 90 : 0))
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .padding(.leading, 2)
        .padding(.vertical, 2)
    }

    private var bundleTitle: String {
        let names = tools.compactMap { $0.call?.title }.prefix(2).joined(separator: "、")
        return names.isEmpty
            ? String(localized: "tools.view-results", defaultValue: "查看 \(tools.count) 个工具结果")
            : String(
                localized: "tools.used.summary",
                defaultValue: "使用了 \(names)\(tools.count > 2 ? String(localized: " 等工具") : "")"
            )
    }
}

private struct ConversationProcessToolHeaderRow: View {
    let tool: ConversationProcessTool
    let expanded: Bool
    let isExpandable: Bool
    let onToggle: () -> Void

    var body: some View {
        Group {
            if isExpandable {
                Button(action: onToggle) { label }
                    .buttonStyle(.plain)
            } else {
                label
            }
        }
        .padding(.leading, 16)
        .padding(.vertical, 6)
    }

    private var label: some View {
        let summary = tool.activitySummary
        return HStack(spacing: 7) {
            ConversationGlyphImage(
                glyph: tool.result?.isError == true
                    ? .system("exclamationmark.triangle")
                    : conversationToolGlyph(tool.call?.title)
            )
                .foregroundStyle(tool.result?.isError == true ? .red : DSHColor.orange)
                .frame(width: 17)
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 7) {
                    Text(summary.label)
                        .appFont(.subheadline.weight(.medium))
                        .foregroundStyle(.secondary)
                    if !summary.annotation.isEmpty {
                        Text(summary.annotation)
                            .appFont(.caption.monospacedDigit())
                            .foregroundStyle(.tertiary)
                    }
                    if tool.result?.isError == true {
                        Text("失败").appFont(.caption2.weight(.semibold)).foregroundStyle(.red)
                    } else if tool.result == nil {
                        Text("等待结果").appFont(.caption2).foregroundStyle(.secondary)
                    }
                }
                if !summary.detail.isEmpty {
                    Text(summary.detail)
                        .appFont(.caption.monospaced())
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                        .truncationMode(.middle)
                        .multilineTextAlignment(.leading)
                }
            }
            Spacer(minLength: 8)
            if isExpandable {
                Image(systemName: "chevron.right")
                    .appFont(.caption.weight(.semibold))
                    .foregroundStyle(.tertiary)
                    .rotationEffect(.degrees(expanded ? 90 : 0))
            }
        }
        .contentShape(Rectangle())
    }
}

private struct ConversationProcessToolDetailRow: View {
    let tool: ConversationProcessTool

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            if let call = tool.call, !call.text.isEmpty {
                Text("调用参数")
                    .appFont(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                ToolArgumentsView(text: call.text)
            }
            if let result = tool.result, !result.text.isEmpty {
                Text(result.isError ? String(localized: "错误") : String(localized: "结果"))
                    .appFont(.caption.weight(.semibold))
                    .foregroundStyle(result.isError ? .red : .secondary)
                ToolOutputView(text: result.text)
            }
        }
        .padding(.leading, 40)
        .padding(.bottom, 5)
    }
}

private func preview(_ text: String) -> String {
    text.replacingOccurrences(of: "\n", with: " ")
        .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
        .trimmingCharacters(in: .whitespacesAndNewlines)
}

private enum ConversationGlyph {
    case system(String)
    case asset(String)
}

private struct ConversationGlyphImage: View {
    let glyph: ConversationGlyph

    @ViewBuilder
    var body: some View {
        switch glyph {
        case .system(let name):
            Image(systemName: name)
        case .asset(let name):
            Image(name)
                .renderingMode(.template)
                .resizable()
                .scaledToFit()
                .frame(width: 14, height: 14)
        }
    }
}

private func conversationToolGlyph(_ toolName: String?) -> ConversationGlyph {
    let normalized = toolName?
        .lowercased()
        .filter { $0.isLetter || $0.isNumber } ?? ""
    switch normalized {
    case "write", "edit", "writefile", "editfile", "applypatch":
        return .system("pencil")
    case "glob", "grep", "websearch":
        return .asset("DshSearch")
    case "read", "webfetch", "cordispackageinspect", "cordisruntimeinspect":
        return .asset("DshRead")
    case "bash", "pwsh":
        return .asset("DshBash")
    default:
        return .system("wrench.and.screwdriver")
    }
}

private struct ToolArgumentsView: View {
    let text: String

    var body: some View {
        Group {
            if text.count > 3_000 {
                ScrollView([.horizontal, .vertical], showsIndicators: true) {
                    MarkdownContent("```json\n\(text)\n```", compact: true)
                        .padding(8)
                }
                .frame(height: 220)
            } else {
                MarkdownContent("```json\n\(text)\n```", compact: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .clipShape(RoundedRectangle(cornerRadius: 9))
    }
}

private struct AttachmentImageGrid: View {
    let attachments: [GatewayImageAttachment]
    let imageData: (String) -> Data?

    private var columns: [GridItem] {
        Array(
            repeating: GridItem(.flexible(), spacing: 6),
            count: attachments.count == 1 ? 1 : 2
        )
    }

    var body: some View {
        LazyVGrid(columns: columns, alignment: .trailing, spacing: 6) {
            ForEach(attachments) { attachment in
                Group {
                    if let data = imageData(attachment.id), let image = UIImage(data: data) {
                        Image(uiImage: image)
                            .resizable()
                            .scaledToFill()
                    } else {
                        ZStack {
                            Color(uiColor: .secondarySystemFill)
                            ProgressView().controlSize(.small)
                        }
                    }
                }
                .aspectRatio(
                    attachment.height > 0 ? CGFloat(attachment.width) / CGFloat(attachment.height) : 1,
                    contentMode: .fit
                )
                .frame(maxWidth: attachments.count == 1 ? 320 : 180, maxHeight: 280)
                .clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: 11, style: .continuous)
                        .stroke(.white.opacity(0.16), lineWidth: 0.7)
                }
                .accessibilityLabel(attachment.name ?? String(localized: "图片附件"))
            }
        }
    }
}

struct ConversationRow: View {
    let item: ConversationItem
    let showsCopyButton: Bool
    let imageData: (String) -> Data?
    var onOpenFile: ((String) -> Void)? = nil
    var onOpenUploadedFile: ((PresentedFile) -> Void)? = nil
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        switch item.kind {
        case .user:
            VStack(alignment: .trailing, spacing: 5) {
                VStack(alignment: .trailing, spacing: 8) {
                    if !item.images.isEmpty {
                        AttachmentImageGrid(attachments: item.images, imageData: imageData)
                    }
                    ForEach(item.files.filter { $0.attachmentId != nil }, id: \.attachmentId) { file in
                        UploadedFileCard(file: file) { onOpenUploadedFile?(file) }
                    }
                    if !item.text.isEmpty {
                        Text(item.text).appFont(.body)
                    }
                }
                .padding(.horizontal, 10).padding(.vertical, 10)
                .background(userBubbleFill, in: RoundedRectangle(cornerRadius: 15, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: 15, style: .continuous)
                        .stroke(userBubbleEdge, lineWidth: 0.7)
                }
                if showsCopyButton && !item.text.isEmpty { CopyMessageButton(text: item.text) }
            }
            // Long pasted payloads should remain a trailing chat bubble, not
            // expand edge-to-edge. This fixed inset also removes a variable
            // width input from self-sizing while the list is being dragged.
            .padding(.leading, 34)
            .frame(maxWidth: .infinity, alignment: .trailing)
            .padding(.vertical, 12)
        case .context:
            ConversationFallbackEventRow(
                title: item.title,
                text: item.text,
                icon: .asset("DshContextInjection"),
                tint: .green
            )
        case .assistant:
            VStack(alignment: .leading, spacing: 7) {
                if !item.images.isEmpty {
                    AttachmentImageGrid(attachments: item.images, imageData: imageData)
                }
                if !item.text.isEmpty {
                    MarkdownContent(item.text, knownFiles: item.knownFiles, onOpenFile: onOpenFile)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                ForEach(item.files, id: \.path) { file in
                    FileReferenceCard(path: file.path, description: file.description) {
                        onOpenFile?(file.path)
                    }
                    .frame(maxWidth: .infinity, alignment: .trailing)
                }
                if showsCopyButton && !item.text.isEmpty {
                    CopyMessageButton(text: ParsedMarkdownCache.document(for: item.text).renderPlainText())
                        .padding(.bottom, 8)
                }
            }
            .padding(.vertical, 3)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.vertical, 12)
        case .reasoning:
            ConversationFallbackEventRow(
                title: "Think",
                text: item.text,
                icon: .asset("DshThink"),
                tint: DSHColor.purple
            )
        case .tool, .toolResult:
            ConversationFallbackEventRow(
                title: item.title,
                text: item.text,
                icon: item.isError
                    ? .system("exclamationmark.triangle")
                    : item.kind == .tool
                        ? conversationToolGlyph(item.title)
                        : .system("wrench.and.screwdriver"),
                tint: item.isError ? .red : DSHColor.orange,
                rendersOutput: item.title == L10n.toolResultDoneTitle || item.title == L10n.toolResultFailedTitle
            )
        case .jsonTool:
            ConversationFallbackEventRow(
                title: item.title,
                text: item.text,
                icon: .system("curlybraces.square"),
                tint: DSHColor.orange,
                rendersJSON: true
            )
        case .status:
            HStack(alignment: .firstTextBaseline, spacing: 9) {
                Image(systemName: "terminal")
                    .appFont(.system(size: 13, weight: .medium))
                    .foregroundStyle(item.isError ? .red : .secondary)
                Text(item.title)
                    .foregroundStyle(item.isError ? .red : .primary)
                Text("·")
                    .foregroundStyle(.tertiary)
                Text(item.text)
                    .foregroundStyle(item.isError ? .red : .secondary)
            }
            .appFont(.subheadline)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.vertical, 6)
        case .system:
            HStack(alignment: .top, spacing: 9) {
                Image(systemName: item.isError ? "exclamationmark.circle.fill" : "antenna.radiowaves.left.and.right")
                    .foregroundStyle(item.isError ? .red : DSHColor.ocean)
                VStack(alignment: .leading, spacing: 3) {
                    Text(item.title).appFont(.caption.weight(.semibold))
                    if !item.text.isEmpty { Text(item.text).appFont(.caption).foregroundStyle(.secondary).textSelection(.enabled) }
                }
            }
            .padding(10).frame(maxWidth: .infinity, alignment: .leading)
            .background((item.isError ? Color.red : DSHColor.ocean).opacity(0.06), in: RoundedRectangle(cornerRadius: 11))
        }
    }

    private var userBubbleFill: Color {
        DSHColor.ocean.opacity(colorScheme == .dark ? 0.24 : 0.11)
    }

    private var userBubbleEdge: Color {
        DSHColor.ocean.opacity(colorScheme == .dark ? 0.34 : 0.08)
    }
}

struct CopyMessageButton: View {
    let text: String
    var isCode = false
    @State private var copied = false

    var body: some View {
        Button {
            UIPasteboard.general.string = text
            copied = true
            Task { @MainActor in
                try? await Task.sleep(for: .seconds(1.4))
                copied = false
            }
        } label: {
            Group {
                if copied {
                    Image(systemName: "checkmark")
                        .appFont(.system(size: 14, weight: .semibold))
                } else {
                    Image("CopyMessage")
                        .renderingMode(.template)
                        .resizable()
                        .scaledToFit()
                        .frame(width: 16, height: 16)
                }
            }
                .foregroundStyle(copied ? DSHColor.ocean : Color.secondary)
                .frame(width: isCode ? 44 : 16, height: isCode ? 44 : 26)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(copied ? String(localized: "已复制") : String(localized: isCode ? "复制代码" : "复制正文"))
    }
}

/// Defensive renderer for an isolated process event. The normal conversation
/// path projects process groups into independent collection items before they
/// reach `ConversationRow`. Keeping this fallback stateless ensures an
/// unexpected standalone event can never resize itself behind the collection
/// layout's back.
private struct ConversationFallbackEventRow: View {
    let title: String
    let text: String
    let icon: ConversationGlyph
    let tint: Color
    var rendersJSON = false
    var rendersOutput = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 8) {
                ConversationGlyphImage(glyph: icon)
                    .foregroundStyle(tint)
                    .frame(width: 18)
                Text(title).foregroundStyle(tint).lineLimit(1)
                if !preview.isEmpty {
                    Text("·").foregroundStyle(.tertiary)
                    Text(preview).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer(minLength: 8)
            }
            .appFont(.subheadline)

            if !text.isEmpty {
                HStack(alignment: .top, spacing: 10) {
                    Rectangle().fill(.gray.opacity(0.24)).frame(width: 1)
                    if rendersOutput {
                        ToolOutputView(text: text)
                    } else if shouldRenderJSON {
                        MarkdownContent("```json\n\(text)\n```", compact: true)
                    } else {
                        MarkdownContent(text, compact: true).foregroundStyle(.secondary)
                    }
                }
                .padding(.leading, 5).padding(.top, 5).padding(.bottom, 3)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, 2)
    }

    private var preview: String {
        text.replacingOccurrences(of: "\n", with: " ")
            .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var shouldRenderJSON: Bool {
        if rendersJSON { return true }
        guard let data = text.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) else { return false }
        return JSONSerialization.isValidJSONObject(object)
    }
}

private struct ToolOutputView: View {
    let text: String

    private var lines: [String] {
        text.components(separatedBy: .newlines)
    }

    private var needsViewport: Bool {
        text.count > 1_500 || lines.count > 24
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            Text("OUT")
                .appFont(.caption2.monospaced())
                .foregroundStyle(.secondary)

            if needsViewport {
                ScrollView([.horizontal, .vertical], showsIndicators: true) {
                    LazyVStack(alignment: .leading, spacing: 2) {
                        ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                            Text(line.isEmpty ? " " : line)
                                .appFont(.caption.monospaced())
                                .foregroundStyle(.primary)
                                .fixedSize(horizontal: true, vertical: false)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(10)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .frame(height: 280)
            } else {
                Text(text)
                    .appFont(.caption.monospaced())
                    .foregroundStyle(.primary)
                    .multilineTextAlignment(.leading)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .textSelection(.enabled)
                    .padding(10)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(uiColor: .secondarySystemFill), in: RoundedRectangle(cornerRadius: 10))
        .clipped()
    }
}

struct MarkdownContent: View {
    @AppStorage(AppFontScale.key) private var fontScale = 1.0
    @AppStorage(AppFontScale.followsSystemKey) private var fontFollowsSystem = false
    let text: String
    var compact = false
    var knownFiles: [PresentedFile] = []
    var onOpenFile: ((String) -> Void)?
    @State private var selectionDocument: MarkdownSelectionDocument?

    init(_ text: String, compact: Bool = false, knownFiles: [PresentedFile] = [], onOpenFile: ((String) -> Void)? = nil) {
        self.text = text
        self.compact = compact
        self.knownFiles = knownFiles
        self.onOpenFile = onOpenFile
    }

    var body: some View {
        renderedMarkdown
            .background {
                MarkdownLongPressMenu(onCopy: {
                    UIPasteboard.general.string = ParsedMarkdownCache.document(for: text).renderPlainText()
                }, onSelectText: openTextSelection)
                .accessibilityHidden(true)
            }
            .accessibilityAction(named: Text("复制")) {
                UIPasteboard.general.string = ParsedMarkdownCache.document(for: text).renderPlainText()
            }
            .accessibilityAction(named: Text("选择文字"), openTextSelection)
            .fullScreenCover(item: $selectionDocument) { document in
                MarkdownSelectionPage(document: document)
            }
    }

    private func openTextSelection() {
        selectionDocument = MarkdownSelectionDocument(
            text: text,
            fontSize: UIFontMetrics.default.scaledValue(for: 17)
                * CGFloat(AppFontScale.effective(fontScale, followsSystem: fontFollowsSystem))
        )
    }

    private var renderedMarkdown: some View {
        Markdown(PreparedMarkdownCache.document(for: text, filePaths: knownFiles.map(\.path)))
            .environment(\.openURL, OpenURLAction { url in
                guard let onOpenFile else { return .systemAction }
                if url.scheme == "dsh-file-reference", let index = Int(url.lastPathComponent), knownFiles.indices.contains(index) {
                    onOpenFile(knownFiles[index].path)
                    return .handled
                }
                if let path = ConversationFileReferences.shared.filePath(link: url.absoluteString) {
                    onOpenFile(path)
                    return .handled
                }
                return .systemAction
            })
            .markdownTheme(.deepSeek(compact: compact, scale: CGFloat(AppFontScale.effective(fontScale, followsSystem: fontFollowsSystem))))
            .markdownCodeSyntaxHighlighter(DSHCodeSyntaxHighlighter())
            // MarkdownUI 会自行应用 Dynamic Type。主题已合并系统与应用倍率，避免重复缩放。
            .dynamicTypeSize(.large)
    }

}

private struct MarkdownLongPressMenu: UIViewRepresentable {
    let onCopy: () -> Void
    let onSelectText: () -> Void

    func makeUIView(context: Context) -> MarkdownLongPressMenuView {
        MarkdownLongPressMenuView()
    }

    func updateUIView(_ view: MarkdownLongPressMenuView, context: Context) {
        view.onCopy = onCopy
        view.onSelectText = onSelectText
    }

    static func dismantleUIView(_ view: MarkdownLongPressMenuView, coordinator: ()) {
        view.detachMenu()
        view.onCopy = nil
        view.onSelectText = nil
    }
}

/// 背景只提供菜单锚点，手势在窗口监听且限于正文范围，不遮挡链接与代码复制按钮。
/// 使用编辑浮层，避免 contextMenu 对整段 Markdown 生成预览、抬升和缩放。
final class MarkdownLongPressMenuView: UIView, UIGestureRecognizerDelegate, UIEditMenuInteractionDelegate {
    var onCopy: (() -> Void)?
    var onSelectText: (() -> Void)?
    private lazy var menu = UIEditMenuInteraction(delegate: self)
    private lazy var longPress = UILongPressGestureRecognizer(target: self, action: #selector(pressed(_:)))

    override init(frame: CGRect) {
        super.init(frame: frame)
        longPress.delegate = self
        addInteraction(menu)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func point(inside point: CGPoint, with event: UIEvent?) -> Bool { false }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        detachMenu()
        // SwiftUI 的背景容器不是正文触摸视图的祖先。挂到窗口才能收到正文触摸，
        // 再由 shouldReceive 将事件限制在当前 Markdown 的范围内。
        window?.addGestureRecognizer(longPress)
    }

    func detachMenu() {
        longPress.view?.removeGestureRecognizer(longPress)
        menu.dismissMenu()
    }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
        bounds.contains(touch.location(in: self))
    }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                           shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer) -> Bool {
        // 不阻断 SwiftUI 内部手势和会话滚动；手指移动超过阈值时长按自行失败。
        true
    }

    @objc private func pressed(_ gesture: UILongPressGestureRecognizer) {
        guard gesture.state == .began else { return }
        presentMenu(at: gesture.location(in: self))
    }

    func presentMenu(at point: CGPoint) {
        guard window != nil, bounds.contains(point) else { return }
        menu.presentEditMenu(with: UIEditMenuConfiguration(identifier: nil, sourcePoint: point))
    }

    func editMenuInteraction(_ interaction: UIEditMenuInteraction, menuFor configuration: UIEditMenuConfiguration,
                             suggestedActions: [UIMenuElement]) -> UIMenu? {
        UIMenu(children: [
            UIAction(title: String(localized: "复制"), image: UIImage(systemName: "doc.on.doc")) { [weak self] _ in
                self?.onCopy?()
            },
            UIAction(title: String(localized: "选择文字"), image: UIImage(systemName: "selection.pin.in.out")) { [weak self] _ in
                self?.onSelectText?()
            }
        ])
    }
}

/// 选择页持有打开时的正文快照，流式更新不重置用户正在拖动的选区。
struct MarkdownSelectionDocument: Identifiable {
    let id = UUID()
    let text: String
    let fontSize: CGFloat
}

struct MarkdownSelectionPage: View {
    @Environment(\.dismiss) private var dismiss
    let document: MarkdownSelectionDocument

    var body: some View {
        VStack(spacing: 0) {
            DocumentPageHeader(title: "选择文字", closeIdentifier: "markdown-selection-close", onClose: { dismiss() }) {
                EmptyView()
            }
            let parsed = ParsedMarkdownCache.document(for: document.text)
            if parsed.containsTable {
                MarkdownTableTextSelection(document: parsed, fontSize: document.fontSize)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                MarkdownTextSelection(document: parsed, font: UIFont.systemFont(ofSize: document.fontSize))
            }
        }
        .background(DocumentPageAppearance.background)
    }
}

/// HTML 表格和周围正文处于同一个可选择文档，保留跨单元格与跨段落的原生选区。
struct MarkdownTableTextSelection: UIViewRepresentable {
    @Environment(\.colorScheme) private var colorScheme
    let document: MarkdownUI.MarkdownContent
    let fontSize: CGFloat

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .nonPersistent()
        configuration.preferences.javaScriptCanOpenWindowsAutomatically = false
        let view = WKWebView(frame: .zero, configuration: configuration)
        view.isOpaque = false
        view.backgroundColor = .clear
        view.scrollView.backgroundColor = .clear
        view.scrollView.contentInsetAdjustmentBehavior = .never
        view.navigationDelegate = context.coordinator
        view.accessibilityIdentifier = "markdown-table-text-selection"
        return view
    }

    func updateUIView(_ view: WKWebView, context: Context) {
        let body = document.renderSelectionHTML()
        let appearance = "window.dshSetAppearance(\(fontSize),\(colorScheme == .dark ? "true" : "false"));"
        let coordinator = context.coordinator
        coordinator.appearance = appearance
        if coordinator.body != body {
            coordinator.body = body
            view.loadHTMLString(MarkdownSelectionHtml.shared.document(body: body, fontSize: Double(fontSize),
                dark: colorScheme == .dark, codeLabel: String(localized: "代码")), baseURL: nil)
        } else {
            view.evaluateJavaScript(appearance, completionHandler: nil)
        }
    }

    static func dismantleUIView(_ view: WKWebView, coordinator: Coordinator) {
        view.stopLoading()
        view.navigationDelegate = nil
    }

    final class Coordinator: NSObject, WKNavigationDelegate {
        var body: String?
        var appearance = ""

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            webView.becomeFirstResponder()
            webView.evaluateJavaScript(appearance + "window.dshSelectInitial();", completionHandler: nil)
        }

        func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
                     decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            decisionHandler(navigationAction.navigationType == .other && navigationAction.request.url?.scheme == "about" ? .allow : .cancel)
        }
    }
}

/// 原生 TextView 独立滚动，聊天页的 MarkdownUI 始终保留在原来的布局中。
struct MarkdownTextSelection: UIViewRepresentable {
    let document: MarkdownUI.MarkdownContent
    let font: UIFont

    func makeUIView(context: Context) -> MarkdownSelectionTextView {
        let view = MarkdownSelectionTextView()
        view.isEditable = false
        view.isSelectable = true
        view.isScrollEnabled = true
        view.alwaysBounceVertical = true
        view.backgroundColor = .clear
        view.tintColor = .systemBlue
        view.textContainerInset = UIEdgeInsets(top: 16, left: 20, bottom: 24, right: 20)
        view.textContainer.lineFragmentPadding = 0
        view.adjustsFontForContentSizeCategory = false
        view.accessibilityIdentifier = "markdown-text-selection"
        return view
    }

    func updateUIView(_ view: MarkdownSelectionTextView, context: Context) {
        let value = document.renderSelectableText(font: font)
        if !view.attributedText.isEqual(to: value) { view.attributedText = value }
    }
}

final class MarkdownSelectionTextView: UITextView, UITextViewDelegate, UIEditMenuInteractionDelegate {
    private var hasSelected = false
    private lazy var selectionMenu = UIEditMenuInteraction(delegate: self)

    override init(frame: CGRect, textContainer: NSTextContainer?) {
        let container = textContainer ?? NSTextContainer(size: .zero)
        let storage = container.layoutManager?.textStorage ?? NSTextStorage()
        if container.layoutManager == nil {
            let manager = MarkdownCodeBlockLayoutManager()
            manager.addTextContainer(container)
            storage.addLayoutManager(manager)
        }
        super.init(frame: frame, textContainer: container)
        withExtendedLifetime(storage) {}
        delegate = self
        addInteraction(selectionMenu)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func layoutSubviews() {
        super.layoutSubviews()
        guard window != nil, bounds.width > 0, !hasSelected, !text.isEmpty else { return }
        hasSelected = true
        DispatchQueue.main.async { [weak self] in
            guard let self, self.window != nil else { return }
            self.becomeFirstResponder()
            var start = 0
            if self.attributedText.attribute(SelectableCodeBlockStyle.decoration, at: 0, effectiveRange: nil) != nil {
                start = 1
            }
            let source = self.text as NSString
            var range = source.paragraphRange(for: NSRange(location: min(start, source.length - 1), length: 0))
            // 初始选中正文，不把结构换行包含在内，避免终点手柄落到下一段。
            if range.length > 1, source.substring(with: NSRange(location: NSMaxRange(range) - 1, length: 1)) == "\n" {
                range.length -= 1
            }
            self.selectedRange = range
            if let range = self.selectedTextRange {
                self.selectionMenu.presentEditMenu(with: UIEditMenuConfiguration(
                    identifier: nil, sourcePoint: self.firstRect(for: range).origin
                ))
            }
        }
    }

    override func copy(_ sender: Any?) {
        let range = selectedRange
        guard range.location != NSNotFound, range.length > 0, NSMaxRange(range) <= attributedText.length else { return }
        var selected = ""
        attributedText.enumerateAttribute(SelectableCodeBlockStyle.decoration, in: range) { decoration, part, _ in
            if decoration == nil { selected += (attributedText.string as NSString).substring(with: part) }
        }
        UIPasteboard.general.string = selected.replacingOccurrences(of: "\u{2028}", with: "\n")
        selectionMenu.dismissMenu()
    }

    func textView(_ textView: UITextView, editMenuForTextIn range: NSRange,
                  suggestedActions: [UIMenuElement]) -> UIMenu? {
        selectionActions()
    }

    func editMenuInteraction(_ interaction: UIEditMenuInteraction, menuFor configuration: UIEditMenuConfiguration,
                             suggestedActions: [UIMenuElement]) -> UIMenu? {
        selectionActions()
    }

    private func selectionActions() -> UIMenu {
        UIMenu(children: [
            UIAction(title: String(localized: "复制"), image: UIImage(systemName: "doc.on.doc")) { [weak self] _ in
                self?.copy(nil)
            },
            UIAction(title: String(localized: "全选")) { [weak self] _ in self?.selectAll(nil) }
        ])
    }
}

/// 在同一个可选择的 TextView 中绘制代码卡片，空行与折行共用连续背景。
/// 代码仍由 TextKit 绘制为文字，保持系统手柄、跨段选择与原始缩进复制。
final class MarkdownCodeBlockLayoutManager: NSLayoutManager {
    func codeBlockRect(for characters: NSRange, in container: NSTextContainer) -> CGRect {
        let glyphs = glyphRange(forCharacterRange: characters, actualCharacterRange: nil)
        guard glyphs.length > 0 else { return .zero }
        let first = lineFragmentRect(forGlyphAt: glyphs.location, effectiveRange: nil)
        let last = lineFragmentUsedRect(forGlyphAt: NSMaxRange(glyphs) - 1, effectiveRange: nil)
        let top = first.minY
        return CGRect(
            x: container.lineFragmentPadding, y: top,
            width: max(0, container.size.width - container.lineFragmentPadding * 2),
            height: last.maxY + SelectableCodeBlockStyle.bottomPadding - top
        )
    }

    override func drawBackground(forGlyphRange glyphsToShow: NSRange, at origin: CGPoint) {
        guard let storage = textStorage else { return }
        let visibleCharacters = characterRange(forGlyphRange: glyphsToShow, actualGlyphRange: nil)
        storage.enumerateAttribute(SelectableCodeBlockStyle.blockID,
                                   in: NSRange(location: 0, length: storage.length)) { value, range, _ in
            guard value != nil, NSIntersectionRange(range, visibleCharacters).length > 0 else { return }
            let glyph = self.glyphIndexForCharacter(at: range.location)
            guard let container = self.textContainer(forGlyphAt: glyph, effectiveRange: nil) else { return }
            let rect = self.codeBlockRect(for: range, in: container).offsetBy(dx: origin.x, dy: origin.y)
            SelectableCodeBlockStyle.backgroundColor.setFill()
            UIBezierPath(roundedRect: rect, cornerRadius: 9).fill()
            let language = storage.attribute(SelectableCodeBlockStyle.language, at: range.location,
                                             effectiveRange: nil) as? String ?? ""
            let label = language.isEmpty ? String(localized: "代码") : language.uppercased()
            (label as NSString).draw(in: CGRect(x: rect.minX + SelectableCodeBlockStyle.horizontalPadding,
                                              y: rect.minY + 10, width: rect.width - 24, height: 16),
                                    withAttributes: [.font: UIFont.monospacedSystemFont(ofSize: 11, weight: .regular),
                                                     .foregroundColor: UIColor.systemGray])
        }
        super.drawBackground(forGlyphRange: glyphsToShow, at: origin)
    }
}

/// Reuses only render products; callbacks, font sizes and themes stay on the
/// current view. File order owns the synthetic URL indices and belongs in the key.
enum PreparedMarkdownCache {
    private struct Key: Hashable {
        let source: String
        let filePaths: [String]
    }

    private static let cache = MarkdownRenderProductCache<Key, MarkdownUI.MarkdownContent>(
        countLimit: 128, costLimit: 8 * 1_024 * 1_024
    )

    static func document(for source: String, filePaths: [String]) -> MarkdownUI.MarkdownContent {
        let key = Key(source: source, filePaths: filePaths)
        return cache.value(for: key) {
            // File references keep the original inline-code text for AST linking.
            let padded = filePaths.isEmpty ? InlineCodePadding.apply(to: source) : source
            var destinations: [String: String] = [:]
            for (index, path) in filePaths.enumerated() {
                let destination = "dsh-file-reference:///\(index)"
                destinations[path] = destination
                let name = path.replacingOccurrences(of: "\\", with: "/")
                    .split(separator: "/").last.map(String.init) ?? path
                if ConversationFileReferences.shared.knownPath(code: name, paths: filePaths) != nil {
                    destinations[name] = destination
                }
            }
            let document = ParsedMarkdownCache.document(for: padded).linkingInlineCode(destinations)
            let bytes = source.utf16.count * 2 + filePaths.reduce(0) { $0 + $1.utf16.count * 2 }
            // Account for the retained key/source and parsed/re-written tree.
            return (document, bytes * 4)
        }
    }
}

/// A small, bounded LRU for immutable rendering products. Oversized entries are
/// rendered normally without displacing the whole conversation's cached work.
final class MarkdownRenderProductCache<Key: Hashable, Value>: @unchecked Sendable {
    private struct Entry {
        let value: Value
        let cost: Int
    }

    private let lock = NSLock()
    private let countLimit: Int
    private let costLimit: Int
    private var entries: [Key: Entry] = [:]
    private var recency: [Key] = []
    private var totalCost = 0
    private var generation: UInt64 = 0
    private var memoryWarningObserver: NSObjectProtocol?

    init(countLimit: Int, costLimit: Int) {
        self.countLimit = max(0, countLimit)
        self.costLimit = max(0, costLimit)
        memoryWarningObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didReceiveMemoryWarningNotification, object: nil, queue: nil
        ) { [weak self] _ in
            self?.removeAll()
        }
    }

    deinit {
        if let memoryWarningObserver {
            NotificationCenter.default.removeObserver(memoryWarningObserver)
        }
    }

    func value(for key: Key, makeValue: () -> (Value, Int)) -> Value {
        lock.lock()
        if let entry = entries[key] {
            touch(key)
            lock.unlock()
            return entry.value
        }
        let startedGeneration = generation
        lock.unlock()
        let (value, cost) = makeValue()
        guard countLimit > 0, cost >= 0, cost <= costLimit else { return value }
        lock.lock()
        defer { lock.unlock() }
        guard generation == startedGeneration else { return value }
        if let entry = entries[key] {
            touch(key)
            return entry.value
        }
        while entries.count >= countLimit || cost > costLimit - totalCost {
            guard let oldest = recency.first else { break }
            recency.removeFirst()
            if let removed = entries.removeValue(forKey: oldest) {
                totalCost -= removed.cost
            }
        }
        entries[key] = Entry(value: value, cost: cost)
        totalCost += cost
        recency.append(key)
        return value
    }

    func removeAll() {
        lock.lock()
        defer { lock.unlock() }
        entries.removeAll(keepingCapacity: false)
        recency.removeAll(keepingCapacity: false)
        totalCost = 0
        generation &+= 1
    }

    private func touch(_ key: Key) {
        recency.removeAll { $0 == key }
        recency.append(key)
    }
}

/// Completed messages are immutable, but collection-view reuse can bring the
/// same message on screen many times. Reuse MarkdownUI's parsed block tree so
/// scrolling does not repeatedly parse long responses on the main thread.
private enum ParsedMarkdownCache {
    private final class Box: NSObject {
        let document: MarkdownUI.MarkdownContent

        init(_ document: MarkdownUI.MarkdownContent) {
            self.document = document
        }
    }

    private static let cache: NSCache<NSString, Box> = {
        let cache = NSCache<NSString, Box>()
        cache.countLimit = 256
        cache.totalCostLimit = 16 * 1_024 * 1_024
        return cache
    }()

    static func document(for source: String) -> MarkdownUI.MarkdownContent {
        let key = source as NSString
        if let cached = cache.object(forKey: key) {
            return cached.document
        }
        let document = MarkdownUI.MarkdownContent(source)
        cache.setObject(Box(document), forKey: key, cost: source.utf8.count)
        return document
    }
}

private extension Theme {
    static func deepSeek(compact: Bool, scale: CGFloat) -> Theme {
        let inlineCodeFallback: Color? = if #available(iOS 18.0, *) {
            nil
        } else {
            DSHInlineCodeStyle.fallbackBackground
        }
        return Theme.gitHub
            .text {
                ForegroundColor(.primary)
                BackgroundColor(nil)
                FontSize(UIFontMetrics.default.scaledValue(for: compact ? 13 : 17) * scale)
            }
            .code {
                FontFamilyVariant(.monospaced)
                FontSize(.em(0.86))
                ForegroundColor(.primary)
                // AttributedString backgrounds are rectangular, so only use
                // this compatibility path on systems without TextRenderer.
                BackgroundColor(inlineCodeFallback)
            }
            .paragraph { configuration in
                configuration.label
                    .fixedSize(horizontal: false, vertical: true)
                    .relativeLineSpacing(.em(0.22))
                    .markdownMargin(top: 0, bottom: compact ? 6 : 10)
            }
            .listItem { configuration in
                configuration.label.markdownMargin(top: .em(0.18))
            }
            .codeBlock { configuration in
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Text(configuration.language?.isEmpty == false ? configuration.language!.uppercased() : String(localized: "代码"))
                            .appFont(.caption2.monospaced())
                            .foregroundStyle(.secondary)
                        Spacer(minLength: 12)
                        CopyMessageButton(text: configuration.content, isCode: true)
                    }
                    ScrollView(.horizontal, showsIndicators: false) {
                        configuration.label
                            .fixedSize(horizontal: true, vertical: true)
                            .markdownTextStyle {
                                FontFamilyVariant(.monospaced)
                                FontSize(UIFontMetrics.default.scaledValue(for: compact ? 11 : 13) * scale)
                            }
                    }
                    // A horizontal ScrollView has no useful UIKit intrinsic
                    // height of its own. Preserve the code content's ideal
                    // vertical size when the hosting cell is self-sized.
                    .fixedSize(horizontal: false, vertical: true)
                }
                .padding(.horizontal, compact ? 10 : 12)
                .padding(.bottom, compact ? 10 : 12)
                .background(Color(uiColor: .secondarySystemFill))
                .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
                .markdownMargin(top: 2, bottom: compact ? 6 : 10)
            }
            .table { configuration in
                MarkdownTableCard(content: configuration.content,
                    fontSize: UIFontMetrics.default.scaledValue(for: compact ? 13 : 17) * scale)
                    .markdownMargin(top: 2, bottom: compact ? 6 : 10)
            }
            .thematicBreak {
                Rectangle()
                    .fill(Color(uiColor: .separator).opacity(0.55))
                    .frame(height: 0.5)
                    .markdownMargin(
                        top: compact ? 10 : 18,
                        bottom: compact ? 10 : 18
                    )
            }
    }
}

private enum DSHInlineCodeStyle {
    // Close to the neutral blue-gray chip used by Harness WebUI, but adaptive
    // enough to remain legible when the app later gains a dark conversation UI.
    static let fallbackBackground = Color(uiColor: .systemGray5).opacity(0.68)
}

enum InlineCodePadding {
    private static let expression = try! NSRegularExpression(pattern: #"(?<!`)`([^`\n]+)`(?!`)"#)
    private static let softBreak = "\u{200B}"
    private static let softBreakDelimiters: Set<Character> = ["/", "\\", "_", ".", "-", ":"]

    static func apply(to markdown: String) -> String {
        var insideFence = false
        return markdown.components(separatedBy: .newlines).map { line in
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if trimmed.hasPrefix("```") || trimmed.hasPrefix("~~~") {
                insideFence.toggle()
                return line
            }
            guard !insideFence else { return line }
            let result = NSMutableString(string: line)
            let range = NSRange(location: 0, length: result.length)
            for match in expression.matches(in: line, range: range).reversed() {
                let source = result.substring(with: match.range(at: 1))
                let rendered = addingSoftBreaks(to: source)
                result.replaceCharacters(
                    in: match.range,
                    with: "`\u{2009}\(rendered)\u{2009}`"
                )
            }
            return result as String
        }.joined(separator: "\n")
    }

    private static func addingSoftBreaks(to source: String) -> String {
        guard source.utf16.count > 24 else { return source }
        var rendered = ""
        rendered.reserveCapacity(source.count + 8)
        for character in source {
            rendered.append(character)
            if softBreakDelimiters.contains(character) {
                rendered.append(contentsOf: softBreak)
            }
        }
        return rendered
    }
}

private struct DSHCodeSyntaxHighlighter: CodeSyntaxHighlighter {
    func highlightCode(_ code: String, language: String?) -> Text {
        if isJSON(code, language: language) {
            return Text(JSONSyntaxHighlighter.highlight(code))
        }
        return Text(code)
    }

    private func isJSON(_ code: String, language: String?) -> Bool {
        if let language, ["json", "jsonc"].contains(language.lowercased()) { return true }
        return JSONSyntaxHighlighter.isJSON(code)
    }
}

enum JSONSyntaxHighlighter {
    private static let expression = try! NSRegularExpression(
        pattern: #"\"(?:\\.|[^\"\\])*\"|-?\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b|\b(?:true|false|null)\b"#
    )
    private static let keySuffixExpression = try! NSRegularExpression(pattern: #"\s*:"#)
    private static let highlighted = MarkdownRenderProductCache<String, AttributedString>(
        countLimit: 128, costLimit: 4 * 1_024 * 1_024
    )
    private static let classifications = MarkdownRenderProductCache<String, Bool>(
        countLimit: 128, costLimit: 1_024 * 1_024
    )

    static func isJSON(_ source: String) -> Bool {
        classifications.value(for: source) {
            let object = source.data(using: .utf8).flatMap { try? JSONSerialization.jsonObject(with: $0) }
            return (object.map(JSONSerialization.isValidJSONObject) ?? false, source.utf16.count * 2)
        }
    }

    static func highlight(_ source: String) -> AttributedString {
        highlighted.value(for: source) {
            // Store semantic colors without theme-resolved UIColor/font metrics.
            (makeHighlight(source), source.utf16.count * 12)
        }
    }

    private static func makeHighlight(_ source: String) -> AttributedString {
        let string = source as NSString
        let fullRange = NSRange(location: 0, length: string.length)
        let matches = expression.matches(in: source, range: fullRange)
        var result = AttributedString()
        var cursor = 0

        for match in matches {
            if match.range.location > cursor {
                result += styled(string.substring(with: NSRange(location: cursor, length: match.range.location - cursor)), color: .primary)
            }
            let token = string.substring(with: match.range)
            result += styled(token, color: color(for: token, in: source, utf16Length: string.length, after: NSMaxRange(match.range)))
            cursor = NSMaxRange(match.range)
        }
        if cursor < string.length {
            result += styled(string.substring(from: cursor), color: .primary)
        }
        return result
    }

    private static func color(for token: String, in source: String, utf16Length: Int, after location: Int) -> Color {
        if token.hasPrefix("\"") {
            // Match the original text range: copying every remaining suffix
            // makes key detection quadratic on long JSON blocks.
            let isKey = keySuffixExpression.firstMatch(
                in: source, options: .anchored,
                range: NSRange(location: location, length: utf16Length - location)
            ) != nil
            return isKey ? DSHColor.ocean : Color(red: 0.10, green: 0.55, blue: 0.38)
        }
        if token == "true" || token == "false" { return DSHColor.purple }
        if token == "null" { return .red.opacity(0.8) }
        return DSHColor.orange
    }

    private static func styled(_ value: String, color: Color) -> AttributedString {
        var result = AttributedString(value)
        result.foregroundColor = color
        return result
    }
}

/// 与更多菜单等大的圆形团队入口，保持独立的弹层锚点。
struct TeamNavigationEntry: View {
    @ObservedObject var store: AppStore
    @State private var showsSheet = false
    @State private var menuIdentity = UUID()

    var body: some View {
        HStack(spacing: 0) {
            if store.teamSnapshot.visible {
                TeamCachedMenuButton(
                    members: store.teamSnapshot.members,
                    title: store.teamSessionTitle,
                    selectedID: store.selectedSessionId,
                    select: { member in Task { await store.selectTeamMember(member) } },
                    showDetails: {
                        // 非动画退出 zoom 后重建源按钮，清除系统保留的源快照隐藏状态。
                        menuIdentity = UUID()
                        showsSheet = true
                    }
                )
                .id(menuIdentity)
                .frame(width: 44, height: 44)
            }
        }
        // 面板由稳定容器呈现，不随入口按钮重建而销毁。
        .sheet(isPresented: $showsSheet) {
            TeamContentSheet { member in
                Task { await store.selectTeamMember(member) }
            }.environmentObject(store)
        }
    }
}

/// 提前布局完整成员行，避免系统 Menu 的 subtitle 独立延迟入场。
private struct TeamCachedMenuButton: UIViewRepresentable {
    let members: [MobileTeamMember]
    let title: String
    let selectedID: String?
    let select: (MobileTeamMember) -> Void
    let showDetails: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> UIButton {
        var configuration: UIButton.Configuration
        if #available(iOS 26.0, *) { configuration = .glass() }
        else { configuration = .plain() }
        configuration.cornerStyle = .capsule
        configuration.contentInsets = .zero
        configuration.image = Self.glyph
        configuration.baseForegroundColor = .label
        let button = UIButton(configuration: configuration)
        button.layer.cornerRadius = 22
        button.clipsToBounds = true
        button.accessibilityLabel = "查看智能体团队"
        button.accessibilityIdentifier = "team-entry"
        button.addTarget(context.coordinator, action: #selector(Coordinator.open(_:)), for: .touchUpInside)
        updateUIView(button, context: context)
        return button
    }

    func updateUIView(_ button: UIButton, context: Context) {
        let coordinator = context.coordinator
        coordinator.select = select
        coordinator.showDetails = showDetails
        guard coordinator.members != members || coordinator.title != title || coordinator.selectedID != selectedID else { return }
        coordinator.members = members
        coordinator.title = title
        coordinator.selectedID = selectedID
        coordinator.prepareContent()
    }

    static func dismantleUIView(_ button: UIButton, coordinator: Coordinator) {
        coordinator.content?.dismiss(animated: false)
        coordinator.clearSource()
    }

    static let glyph: UIImage = {
        let renderer = ImageRenderer(content: TeamGlyph().frame(width: 18, height: 18))
        renderer.scale = 3
        return (renderer.uiImage ?? UIImage(systemName: "person.2") ?? UIImage()).withRenderingMode(.alwaysTemplate)
    }()

    final class Coordinator: NSObject, UIPopoverPresentationControllerDelegate {
        var members: [MobileTeamMember] = []
        var title: String?
        var selectedID: String?
        var select: ((MobileTeamMember) -> Void)?
        var showDetails: (() -> Void)?
        var content: UIHostingController<TeamMemberPopover>?

        func prepareContent() {
            let root = TeamMemberPopover(members: members, title: title ?? "Lead 会话", selectedID: selectedID,
                select: { [weak self] member in
                    self?.content?.dismiss(animated: true) { [weak self] in
                        self?.clearSource()
                        self?.select?(member)
                    }
                }, showDetails: { [weak self] in
                    // 完整面板直接接替菜单，避免先等待 zoom 收回再播放 sheet 入场。
                    self?.content?.dismiss(animated: false) { [weak self] in
                        self?.clearSource()
                        // 脱离 UIKit 的无动画退出事务，保留 sheet 自身的入场动画。
                        DispatchQueue.main.async { [weak self] in self?.showDetails?() }
                    }
                })
            let host: UIHostingController<TeamMemberPopover>
            if let content { host = content; host.rootView = root }
            else {
                host = UIHostingController(rootView: root)
                host.modalPresentationStyle = .popover
                host.view.backgroundColor = .clear
                content = host
            }
            // 在数据变化时完成布局，点击只负责呈现已经准备好的内容。
            let size = host.sizeThatFits(in: CGSize(width: 300, height: 480))
            host.preferredContentSize = size
            host.view.frame = CGRect(origin: .zero, size: size)
            host.view.setNeedsLayout()
            host.view.layoutIfNeeded()
        }

        @objc func open(_ button: UIButton) {
            guard let content, content.presentingViewController == nil, button.window != nil else { return }
            var responder: UIResponder? = button
            while let current = responder {
                if let presenter = current as? UIViewController {
                    guard presenter.presentedViewController == nil else { return }
                    let barItem = sourceBarButton(containing: button, presenter: presenter)
                    if #available(iOS 26.0, *), let barItem {
                        content.preferredTransition = .zoom(sourceBarButtonItemProvider: { [weak barItem] _ in barItem })
                    } else if #available(iOS 18.0, *) {
                        content.preferredTransition = .zoom(sourceViewProvider: { [weak button] _ in button })
                    }
                    let popover = content.popoverPresentationController
                    // 使用真实玻璃按钮，避免 SwiftUI 宿主矩形进入源快照。
                    if let barItem { popover?.sourceItem = barItem }
                    else { popover?.sourceItem = button }
                    popover?.sourceView = button
                    popover?.sourceRect = button.bounds
                    popover?.permittedArrowDirections = .up
                    popover?.canOverlapSourceViewRect = true
                    popover?.delegate = self
                    presenter.present(content, animated: true)
                    return
                }
                responder = current.next
            }
        }

        private func sourceBarButton(containing button: UIButton, presenter: UIViewController) -> UIBarButtonItem? {
            var controller: UIViewController? = presenter
            while let current = controller {
                let items = current.navigationItem.rightBarButtonItems ?? []
                if let item = items.first(where: { item in
                    item.customView.map { button.isDescendant(of: $0) } == true
                }) { return item }
                controller = current.parent
            }
            return nil
        }

        func clearSource() {
            content?.popoverPresentationController?.sourceItem = nil
            content?.popoverPresentationController?.sourceView = nil
            if #available(iOS 18.0, *) { content?.preferredTransition = nil }
        }

        func presentationControllerDidDismiss(_ presentationController: UIPresentationController) {
            clearSource()
        }

        func adaptivePresentationStyle(for controller: UIPresentationController,
            traitCollection: UITraitCollection) -> UIModalPresentationStyle { .none }
    }
}

struct TeamMemberPopover: View {
    let members: [MobileTeamMember]
    let title: String
    let selectedID: String?
    let select: (MobileTeamMember) -> Void
    let showDetails: () -> Void
    @AppStorage(AppFontScale.key) private var fontScale: Double = 1
    @AppStorage(AppFontScale.followsSystemKey) private var followsSystemFont = false

    private var memberListHeight: CGFloat {
        let scale = TeamMemberRow.scale(fontScale: fontScale, followsSystemFont: followsSystemFont)
        let rows = CGFloat(members.count) * TeamMemberRow.height * scale
        let gaps = CGFloat(max(0, members.count - 1)) * 2
        return min(300, rows + gaps)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).appFont(.caption).foregroundStyle(.secondary).lineLimit(2).padding(.horizontal, 12)
            Text("成员 \(String(members.count))").appFont(.headline).padding(.horizontal, 12)
            VStack(spacing: 0) {
                ScrollView {
                    VStack(spacing: 2) {
                        ForEach(members) { member in
                            TeamMemberRow(member: member, selected: member.id == selectedID) { select(member) }
                        }
                    }
                }
                // ScrollView 不会按短列表收缩；直接复用固定行高，首帧即可得到正确尺寸。
                .frame(height: memberListHeight)
                .clipped()
                Divider()
            }
            Button(action: showDetails) {
                HStack {
                    Text("查看完整团队内容").appFont(.subheadline.weight(.medium))
                    Spacer()
                    Image(systemName: "chevron.right").appFont(.caption)
                }.padding(12)
            }.buttonStyle(.plain)
        }
        .padding(12)
        .frame(width: 300)
        .transaction { $0.animation = nil }
    }
}

/// 与 Web 团队入口一致的双人线框图标，使用矢量避免位图缩放模糊。
private struct TeamGlyph: View {
    var body: some View {
        Canvas { context, size in
            var path = Path()
            path.addEllipse(in: CGRect(x: 5, y: 3, width: 8, height: 8))
            path.move(to: CGPoint(x: 2, y: 22))
            path.addCurve(to: CGPoint(x: 16, y: 22), control1: CGPoint(x: 2, y: 12), control2: CGPoint(x: 16, y: 12))
            path.move(to: CGPoint(x: 17, y: 3))
            path.addCurve(to: CGPoint(x: 17, y: 11), control1: CGPoint(x: 22, y: 3), control2: CGPoint(x: 22, y: 11))
            path.move(to: CGPoint(x: 20, y: 15))
            path.addQuadCurve(to: CGPoint(x: 23, y: 22), control: CGPoint(x: 23, y: 16))
            let transform = CGAffineTransform(scaleX: size.width / 24, y: size.height / 24)
            context.stroke(path.applying(transform), with: .foreground, style: StrokeStyle(lineWidth: 1.5, lineCap: .round, lineJoin: .round))
        }
        .accessibilityHidden(true)
    }
}

private struct TeamMemberAvatar: View {
    let index: Int?
    var size: CGFloat = 32

    var body: some View {
        Image(String(format: "AgentAvatar%03d", min(143, max(0, index ?? 0))))
            .renderingMode(.original)
            .resizable()
            .scaledToFit()
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}

private struct TeamMemberRow: View {
    let member: MobileTeamMember
    let selected: Bool
    let action: () -> Void
    @AppStorage(AppFontScale.key) private var fontScale: Double = 1
    @AppStorage(AppFontScale.followsSystemKey) private var followsSystemFont = false

    static let height: CGFloat = 72

    static func scale(fontScale: Double, followsSystemFont: Bool) -> CGFloat {
        CGFloat(AppFontScale.effective(fontScale, followsSystem: followsSystemFont))
            * max(1, UIFont.preferredFont(forTextStyle: .body).pointSize / 17)
    }

    private var rowScale: CGFloat {
        Self.scale(fontScale: fontScale, followsSystemFont: followsSystemFont)
    }

    var body: some View {
        Button(action: action) {
            HStack(alignment: .center, spacing: 10) {
                TeamMemberAvatar(index: member.avatarIndex)
                VStack(alignment: .leading, spacing: 3) {
                    HStack {
                        Text(member.name).appFont(.body.weight(.medium)).lineLimit(1).truncationMode(.tail)
                        if selected {
                            Text("当前会话").appFont(.caption2).foregroundStyle(.blue).fixedSize()
                        }
                    }
                    Text([member.model, member.statusLabel].compactMap { $0 }.joined(separator: " · "))
                        .appFont(.caption).foregroundStyle(.secondary).lineLimit(1).truncationMode(.tail)
                    Text(member.error ?? member.detail ?? " ")
                        .appFont(.caption).foregroundStyle(.secondary).lineLimit(1).truncationMode(.tail)
                        .accessibilityHidden(member.error == nil && member.detail == nil)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "checkmark").foregroundStyle(.blue)
                    .frame(width: 18).opacity(selected ? 1 : 0).accessibilityHidden(!selected)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .frame(height: Self.height * rowScale)
            .padding(.horizontal, 10)
            .contentShape(Rectangle())
            .background(selected ? Color.blue.opacity(0.08) : Color.clear, in: RoundedRectangle(cornerRadius: 14))
        }
        .buttonStyle(.plain)
        .disabled(!member.canOpen)
        .opacity(member.canOpen ? 1 : 0.55)
        .accessibilityIdentifier("team-member-\(member.id)")
    }
}

struct TeamContentSheet: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @State private var filter = "all"
    @State private var expanded: Set<String> = []
    let select: (MobileTeamMember) -> Void

    private var tasks: [MobileTeamTask] {
        let current = store.teamSnapshot.members.first { $0.id == store.selectedSessionId }
        return store.teamSnapshot.tasks.filter {
            filter == "all" || (filter == "unassigned" ? $0.ownerName == nil : current != nil && $0.ownerName == current?.name)
        }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(store.teamSessionTitle).appFont(.subheadline.weight(.medium)).lineLimit(2)
                            .accessibilityIdentifier("team-root-session-title")
                        if !store.teamSnapshot.isTeam && store.teamSnapshot.state == "ready" {
                            Text("当前会话使用普通子智能体").appFont(.caption).foregroundStyle(.secondary)
                        }
                    }
                    if let error = store.teamSnapshot.error { Text(error).foregroundStyle(.orange).appFont(.callout) }
                    if store.teamSnapshot.state != "ready" {
                        HStack {
                            Text(store.teamSnapshot.state == "migration-required" ? String(localized: "团队数据待刷新，请在桌面端确认会话格式") : String(localized: "团队数据暂不可用"))
                            Spacer()
                            Button("重试") { store.refreshTeam() }
                        }.appFont(.callout).foregroundStyle(.secondary)
                    }
                    Text("成员 \(String(store.teamSnapshot.members.count))").appFont(.headline)
                    LazyVStack(spacing: 2) {
                        ForEach(store.teamSnapshot.members) { member in
                            TeamMemberRow(member: member, selected: member.id == store.selectedSessionId) {
                                dismiss(); select(member)
                            }
                        }
                    }
                    Text(store.teamSnapshot.state == "ready" || !store.teamSnapshot.tasks.isEmpty
                         ? String(format: String(localized: "共享任务 %@"), String(store.teamSnapshot.tasks.count)) : String(localized: "共享任务 · 待同步")).appFont(.headline)
                    Picker("任务筛选", selection: $filter) {
                        Text("全部").tag("all")
                        Text("当前成员").tag("current")
                        Text("未分配").tag("unassigned")
                    }.pickerStyle(.segmented)
                    if tasks.isEmpty {
                        Text(store.teamSnapshot.state != "ready" ? String(localized: "共享任务尚未同步，请重试")
                             : !store.teamSnapshot.tasks.isEmpty ? String(localized: "当前筛选下暂无共享任务")
                             : store.teamSnapshot.isTeam ? String(localized: "当前团队尚未创建共享任务")
                             : String(localized: "此会话尚无团队共享任务；普通子智能体的内部待办显示在各自会话中。"))
                            .foregroundStyle(.secondary).padding(.vertical)
                    }
                    ForEach(tasks) { task in taskCard(task) }
                }
                .padding(20)
                .readableContentWidth()
            }
            .navigationTitle(store.teamSnapshot.isTeam ? String(localized: "智能体团队") : String(localized: "子智能体"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    private func taskCard(_ task: MobileTeamTask) -> some View {
        let isExpanded = expanded.contains(task.id)
        let owner = store.teamSnapshot.members.first { $0.name == task.ownerName }
        let openOwner: () -> Void = {
            if let owner, owner.canOpen { dismiss(); select(owner) }
        }
        let toggleDetails: () -> Void = {
            withAnimation(.easeInOut(duration: 0.18)) {
                if isExpanded { expanded.remove(task.id) } else { expanded.insert(task.id) }
            }
        }
        return VStack(alignment: .leading, spacing: 12) {
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .top, spacing: 12) {
                    Text(task.subject).appFont(.body.weight(.semibold)).frame(maxWidth: .infinity, alignment: .leading)
                    HStack(spacing: 6) {
                        Circle().fill(taskColor(task)).frame(width: 7, height: 7)
                        Text(task.statusLabel).appFont(.caption)
                    }.foregroundStyle(.secondary).fixedSize()
                }
                if !task.description.isEmpty {
                    Text(task.description).appFont(.subheadline).foregroundStyle(.secondary)
                        .lineLimit(isExpanded ? nil : 2).frame(maxWidth: .infinity, alignment: .leading)
                }
            }.allowsHitTesting(false)
            HStack {
                Text(task.id).appFont(.caption.monospaced()).foregroundStyle(.secondary).allowsHitTesting(false)
                Spacer()
                Button(action: toggleDetails) {
                    HStack(spacing: 4) {
                        Text(isExpanded ? String(localized: "收起") : String(localized: "展开")).appFont(.caption)
                        Image(systemName: isExpanded ? "chevron.up" : "chevron.down").appFont(.caption2)
                    }
                    .foregroundStyle(.secondary)
                    .padding(.vertical, 6)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(isExpanded ? String(localized: "收起任务详情") : String(localized: "展开任务详情"))
                .accessibilityIdentifier("team-task-toggle-\(task.id)")
            }
            Button(action: openOwner) {
                HStack(spacing: 8) {
                    if let owner {
                        TeamMemberAvatar(index: owner.avatarIndex, size: 20)
                    } else {
                        Image(systemName: "person.crop.circle").frame(width: 20, height: 20).accessibilityHidden(true)
                    }
                    Text("负责人：\(task.ownerName ?? String(localized: "未分配"))")
                }
                .appFont(.caption).foregroundStyle(owner?.canOpen == true ? Color.blue : Color.secondary)
            }
            .buttonStyle(.plain)
            .disabled(owner?.canOpen != true)
            .accessibilityIdentifier("team-task-owner-\(task.id)")
            if !task.writeScopes.isEmpty {
                Text("写入范围：\(task.writeScopes.joined(separator: String(localized: "、")))").appFont(.caption).foregroundStyle(.secondary)
                    .allowsHitTesting(false)
            }
            if isExpanded {
                Group {
                    Text("版本：\(String(task.revision))").appFont(.caption).foregroundStyle(.secondary)
                    if !task.blockedBy.isEmpty {
                        Text(String(localized: "依赖：") + task.blockedBy.map { id in store.teamSnapshot.tasks.first { $0.id == id }?.subject ?? id }.joined(separator: String(localized: "、")))
                            .appFont(.caption).foregroundStyle(.secondary)
                    }
                    if task.status == "pending" {
                        Text(task.ready ? String(localized: "可领取") : String(localized: "等待前置任务完成")).appFont(.caption).foregroundStyle(.secondary)
                    }
                    ForEach(task.writeScopeWarnings, id: \.self) { Text($0).appFont(.caption).foregroundStyle(.orange) }
                }.allowsHitTesting(false)
            }
        }
        .padding(16)
        // 卡片与展开按钮共享展开动作，负责人按钮单独处理会话导航。
        .background {
            Button(action: toggleDetails) {
                RoundedRectangle(cornerRadius: 18).fill(Color(uiColor: .secondarySystemGroupedBackground))
            }
            .buttonStyle(.plain)
            .accessibilityLabel("任务：\(task.subject)，\(isExpanded ? String(localized: "收起任务详情") : String(localized: "展开任务详情"))")
            .accessibilityIdentifier("team-task-body-\(task.id)")
        }
        .overlay(RoundedRectangle(cornerRadius: 18).stroke(Color.primary.opacity(0.08), lineWidth: 0.7).allowsHitTesting(false))
        .accessibilityIdentifier("team-task-\(task.id)")
    }

    private func taskColor(_ task: MobileTeamTask) -> Color {
        switch task.status {
        case "completed": return .green
        case "in_progress": return .blue
        case "pending": return task.ready ? .gray : .orange
        default: return .gray
        }
    }
}


/// 与 Android 待发送文件卡片保持相同尺寸、排版和中性背景。
private struct PendingFileCard: View {
    let file: SharedUploadDraft
    let status: String
    let enabled: Bool
    let onRetry: () -> Void
    let onRemove: () -> Void
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "doc")
                .appFont(.system(size: 16))
                .foregroundStyle(Color.primary.opacity(0.6))
                .frame(width: 16, height: 16)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                Text(file.file?.name ?? file.name)
                    .appFont(.system(size: 13))
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                    .truncationMode(.tail)
                Text(status)
                    .appFont(.system(size: 12))
                    .foregroundStyle(file.status == "error" ? Color.red : Color.primary.opacity(0.6))
                    .lineLimit(2)
                if file.status == "uploading" {
                    ProgressView(value: Double(file.confirmedBytes), total: Double(max(1, file.size)))
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if file.status == "error" {
                action("arrow.clockwise", label: "重试上传", tint: .primary.opacity(0.6), onRetry)
            }
            action("xmark.circle.fill", label: "移除文件", tint: .primary, onRemove)
        }
        .padding(10)
        .frame(width: 240)
        .frame(minHeight: 56)
        .background(background, in: RoundedRectangle(cornerRadius: 12))
        .disabled(!enabled)
    }

    private var background: Color {
        colorScheme == .dark
            ? Color(red: 43 / 255, green: 48 / 255, blue: 56 / 255)
            : Color(red: 233 / 255, green: 233 / 255, blue: 237 / 255)
    }

    private func action(_ symbol: String, label: LocalizedStringKey, tint: Color, _ callback: @escaping () -> Void) -> some View {
        Button(action: callback) {
            Image(systemName: symbol)
                .appFont(.system(size: 16))
                .foregroundStyle(tint)
                .frame(width: 32, height: 32)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(label))
    }
}
