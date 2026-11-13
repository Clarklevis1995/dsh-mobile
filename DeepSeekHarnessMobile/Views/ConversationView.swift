import ImageIO
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
    var isCompact = false
    var onSelectionChange: (NSRange) -> Void = { _ in }
    var onPasteAttachments: (([NSItemProvider]) -> Void)? = nil

    func makeUIView(context: Context) -> CommandTextView {
        let textView = CommandTextView()
        textView.accessibilityIdentifier = "composer-input"
        textView.delegate = context.coordinator
        textView.onPasteAttachments = onPasteAttachments
        if onPasteAttachments != nil {
            textView.pasteConfiguration = UIPasteConfiguration(acceptableTypeIdentifiers: [
                UTType.image.identifier, UTType.fileURL.identifier, UTType.data.identifier,
                UTType.text.identifier, UTType.url.identifier
            ])
        }
        textView.onFocusWillChange = { [weak coordinator = context.coordinator] focused in
            coordinator?.nativeFocusWillChange(focused)
        }
        textView.backgroundColor = .clear
        textView.isScrollEnabled = false
        textView.textContainerInset = .zero
        textView.textContainer.lineFragmentPadding = 0
        textView.font = .appPreferredFont(forTextStyle: .body)
        textView.adjustsFontForContentSizeCategory = false
        textView.tintColor = .systemBlue
        textView.textColor = .label
        textView.setCompactPreview(isCompact)
        textView.apply(text: text, presentation: presentation, placeholder: placeholder)
        return textView
    }

    func updateUIView(_ textView: CommandTextView, context: Context) {
        let font = UIFont.appPreferredFont(forTextStyle: .body)
        if textView.font != font { textView.font = font }
        context.coordinator.update(parent: self)
        textView.onPasteAttachments = onPasteAttachments
        textView.setCompactPreview(isCompact)
        textView.apply(text: text, presentation: presentation, placeholder: placeholder)
        if isCompact && !textView.isFirstResponder && textView.contentOffset != .zero {
            textView.setContentOffset(.zero, animated: false)
        }
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
        let maximumHeight = ceil(lineHeight * (isCompact ? 1 : 5))
        let measured = uiView.sizeThatFits(
            CGSize(width: width, height: CGFloat.greatestFiniteMagnitude)
        ).height
        let height = min(max(measured, minimumHeight), maximumHeight)
        let scrollEnabled = measured > maximumHeight
        if uiView.isScrollEnabled != scrollEnabled { uiView.isScrollEnabled = scrollEnabled }
        return CGSize(width: width, height: height)
    }

    static func dismantleUIView(_ uiView: CommandTextView, coordinator: Coordinator) {
        uiView.delegate = nil
        uiView.onFocusWillChange = nil
        uiView.onPasteAttachments = nil
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

        func textViewDidChangeSelection(_ textView: UITextView) { parent.onSelectionChange(textView.selectedRange) }

        func textViewDidChange(_ textView: UITextView) {
            guard parent.text != textView.text else { return }
            parent.text = textView.text
        }

        func nativeFocusWillChange(_ focused: Bool) {
            // 只响应真实的 responder 切换。shouldBegin/End 也会被系统菜单的能力查询调用。
            focusRevision += 1
            if parent.isFocused != focused { parent.isFocused = focused }
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

/// 加号和原生输入节点始终保留；工具栏按焦点显隐，停止操作可在收起时保留。
struct ComposerMorphLayout: Layout {
    var expansion: CGFloat
    var keepActionWhenCollapsed: Bool
    private let targetExpanded: Bool
    private let textLineHeight: CGFloat
    init(expansion: CGFloat, keepActionWhenCollapsed: Bool = false) {
        self.expansion = expansion
        self.keepActionWhenCollapsed = keepActionWhenCollapsed
        targetExpanded = expansion > 0
        textLineHeight = ceil(UIFont.appPreferredFont(forTextStyle: .body).lineHeight)
    }
    var animatableData: CGFloat {
        get { expansion }
        set { expansion = newValue }
    }
    struct Cache {
        var width: CGFloat?
        var editorHeight: CGFloat = 38
        var targetExpanded = false
        var dirty = true
    }
    func makeCache(subviews: Subviews) -> Cache { Cache() }
    func updateCache(_ cache: inout Cache, subviews: Subviews) { cache.dirty = true }

    private func metrics(width: CGFloat, subviews: Subviews, cache: inout Cache) -> (CGFloat, CGFloat, CGFloat) {
        let progress = min(1, max(0, expansion))
        let compactActionSpace: CGFloat = keepActionWhenCollapsed ? 48 : 0
        let editorWidth = max(1, width - (44 + compactActionSpace) * (1 - progress))
        // 只在内容、宽度或展开目标变化时测量自然高度；收起时保留展开高度，
        // 防止原生编辑器切回单行后让外框在第一帧直接变小。
        if targetExpanded && (cache.width != width || !cache.targetExpanded || (cache.dirty && progress >= 1)) {
            cache.editorHeight = max(38, subviews[1].sizeThatFits(ProposedViewSize(width: width, height: nil)).height)
            cache.width = width
            cache.dirty = false
        }
        cache.targetExpanded = targetExpanded
        let height = 42 + progress * (cache.editorHeight + 12 + 10)
        return (editorWidth, cache.editorHeight, height)
    }
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout Cache) -> CGSize {
        let width = proposal.width ?? 320
        return CGSize(width: width, height: metrics(width: width, subviews: subviews, cache: &cache).2)
    }
    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout Cache) {
        let progress = min(1, max(0, expansion))
        let (editorWidth, editorHeight, height) = metrics(width: bounds.width, subviews: subviews, cache: &cache)
        // 外框高度、内边距及加号的位置由同一个进度推导，不能取终点 bounds。
        let rowY = bounds.minY + height - 4 * progress - 42
        subviews[0].place(at: CGPoint(x: bounds.minX, y: rowY + 5), anchor: .topLeading,
                          proposal: ProposedViewSize(width: 32, height: 32))
        // 内部始终顶部对齐，单行居中的偏移也随同一进度插值，不能随焦点瞬间切换。
        let compactTextTop = max(0, (42 - textLineHeight) / 2)
        subviews[1].place(at: CGPoint(x: bounds.minX + 44 * (1 - progress), y: bounds.minY + 6 * progress + compactTextTop * (1 - progress)),
                          anchor: .topLeading, proposal: ProposedViewSize(width: editorWidth, height: editorHeight))
        subviews[2].place(at: CGPoint(x: bounds.minX + 39, y: rowY), anchor: .topLeading,
                          proposal: ProposedViewSize(width: max(1, bounds.width - 88), height: 42))
        subviews[3].place(at: CGPoint(x: bounds.maxX - 42, y: rowY), anchor: .topLeading,
                          proposal: ProposedViewSize(width: 42, height: 42))
    }
}

struct VoiceComposerTextInput: View {
    @Binding var text: String
    @Binding var isFocused: Bool
    let presentation: SlashCommandComposerPresentation?
    let state: SharedVoiceInputSnapshot
    let voice: VoiceInputAdapter
    var isCompact = false
    var onHoldingChanged: (Bool) -> Void = { _ in }
    var onSelectionChange: (NSRange) -> Void = { _ in }
    var onPasteAttachments: (([NSItemProvider]) -> Void)? = nil

    var body: some View {
        Group {
            if state.voiceMode {
                VoiceHoldInput(state: state, voice: voice, onHoldingChanged: onHoldingChanged)
                    .frame(maxWidth: .infinity, minHeight: 38)
            } else {
                SlashCommandTextView(
                    text: $text,
                    presentation: presentation,
                    placeholder: state.hint.localized,
                    isFocused: $isFocused,
                    isCompact: isCompact,
                    onSelectionChange: onSelectionChange,
                    onPasteAttachments: onPasteAttachments
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
    var onFocusWillChange: ((Bool) -> Void)?
    var onPasteAttachments: (([NSItemProvider]) -> Void)?

    override func canPaste(_ itemProviders: [NSItemProvider]) -> Bool {
        if onPasteAttachments != nil && itemProviders.contains(where: ComposerPasteLoader.isAttachment) { return true }
        return super.canPaste(itemProviders)
    }

    override func canPerformAction(_ action: Selector, withSender sender: Any?) -> Bool {
        if action == #selector(paste(_:)), onPasteAttachments != nil {
            let clipboard = UIPasteboard.general
            let hasFile = clipboard.types.contains {
                guard let type = UTType($0) else { return false }
                return type.conforms(to: .data) && !type.conforms(to: .text) && !type.conforms(to: .url)
            }
            // 文本文件的类型与普通文字重叠；是否粘贴文件由 provider 的文件表示和名称判断。
            if clipboard.hasImages || clipboard.hasURLs || hasFile || clipboard.hasStrings { return true }
        }
        return super.canPerformAction(action, withSender: sender)
    }

    override func paste(_ sender: Any?) {
        // 只在用户执行粘贴时读取剪贴板，菜单查询不读取图片或文件内容。
        if !receivePastedProviders(UIPasteboard.general.itemProviders) { super.paste(sender) }
    }

    override func paste(itemProviders: [NSItemProvider]) {
        if !receivePastedProviders(itemProviders) { super.paste(itemProviders: itemProviders) }
    }

    @discardableResult
    func receivePastedProviders(_ providers: [NSItemProvider]) -> Bool {
        guard let onPasteAttachments else { return false }
        let attachments = providers.filter(ComposerPasteLoader.isAttachment)
        guard !attachments.isEmpty else { return false }
        onPasteAttachments(attachments)
        let textProviders = providers.filter { !ComposerPasteLoader.isAttachment($0) }
        Task { @MainActor [weak self] in
            var values: [String] = []
            for provider in textProviders {
                let text: String? = await withCheckedContinuation { continuation in
                    if provider.canLoadObject(ofClass: NSString.self) {
                        _ = provider.loadObject(ofClass: NSString.self) { item, _ in
                            continuation.resume(returning: item as? String)
                        }
                    } else {
                        _ = provider.loadItem(forTypeIdentifier: UTType.url.identifier, options: nil) { item, _ in
                            continuation.resume(returning: (item as? URL)?.absoluteString)
                        }
                    }
                }
                if let text { values.append(text) }
            }
            if !values.isEmpty { self?.insertText(values.joined(separator: "\n")) }
        }
        return true
    }

    override func becomeFirstResponder() -> Bool {
        if !isFirstResponder { onFocusWillChange?(true) }
        let accepted = super.becomeFirstResponder()
        if !accepted && !isFirstResponder { onFocusWillChange?(false) }
        return accepted
    }

    override func resignFirstResponder() -> Bool {
        if isFirstResponder { onFocusWillChange?(false) }
        let accepted = super.resignFirstResponder()
        if !accepted && isFirstResponder { onFocusWillChange?(true) }
        return accepted
    }

    private var renderedText = ""
    private var renderedPresentation: SlashCommandComposerPresentation?
    private var renderedPlaceholder = ""
    private var renderedFontSize: CGFloat?
    private var lastDrawnSize: CGSize = .zero
    private var compactPreview = false

    func setCompactPreview(_ compact: Bool) {
        if compactPreview != compact {
            compactPreview = compact
            setNeedsLayout()
        }
        let lines = compact ? 1 : 0
        let mode: NSLineBreakMode = compact ? .byTruncatingTail : .byWordWrapping
        if textContainer.maximumNumberOfLines != lines { textContainer.maximumNumberOfLines = lines }
        if textContainer.lineBreakMode != mode { textContainer.lineBreakMode = mode }
    }

    // 占位提示是在 draw(_:) 里自绘的。UIView 默认 contentMode 是 scaleToFill，
    // 尺寸变化时系统只会拉伸旧的绘制结果而不会重绘：折叠屏从展开变为折叠后，
    // 输入框变窄，提示文字就被横向压扁。尺寸一变就主动重绘。
    override func layoutSubviews() {
        super.layoutSubviews()
        // UIKit 可能在尺寸落定后补偿 caret 的滚动位置；收紧预览不应带着这段偏移。
        if compactPreview && !isFirstResponder && contentOffset != .zero {
            setContentOffset(.zero, animated: false)
        }
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
    @ObservedObject private var incomingShares = IncomingShareCoordinator.shared
    @State private var importingShareID: UUID?
    @State private var initialDraftFocusPending = false
    @State private var draftSelection = NSRange(location: NSNotFound, length: 0)
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
    @State private var additionalKeyboardInset: CGFloat = 0
    @State private var voiceWindowHeight: CGFloat = 0
    @State private var teamHeaderHeight: CGFloat = 0
    // 输入主体是 UIKit UITextView，不需要 SwiftUI FocusState 参与焦点仲裁。
    // 普通 State 作为单向的显式聚焦/失焦请求，UITextView 自己持有第一响应者。
    @State private var composerIsFocused = false
    @State private var showsAgentPresetMenu = false
    @State private var showsNewWorkspaceMenu = false
    @ScaledMetric(relativeTo: .caption) private var composerCapsuleHeight: CGFloat = 34
    private let conversationBottomClearance: CGFloat = 22

    init(activeView: Binding<Int>, topContentInset: CGFloat = 0, initialDraft: String = "") {
        _activeView = activeView
        self.topContentInset = topContentInset
        _draft = State(initialValue: initialDraft)
        _initialDraftFocusPending = State(initialValue: !initialDraft.isEmpty)
    }

    var body: some View {
        ZStack {
            conversationBackground.ignoresSafeArea()
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
                    .padding(.top, pageTopInset + 4)
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .overlay(alignment: .top) {
            if #unavailable(iOS 26.0) {
                if topContentInset > 0 {
                    ConversationTopGlass()
                        .frame(height: pageTopInset + 32)
                        .allowsHitTesting(false)
                        .accessibilityHidden(true)
                }
            }
        }
        .overlay(alignment: .top) {
            if store.isViewingTeamMember {
                let memberName = store.selectedSession?.title ?? String(localized: "成员")
                Text(store.selectedMemberCanSend
                     ? String(localized: "team.header", defaultValue: "团队 › \(memberName)")
                     : String(localized: "team.header.readOnly", defaultValue: "团队 › \(memberName) · 只读"))
                    .appFont(.caption).foregroundStyle(.secondary).lineLimit(1)
                    .padding(.bottom, 4)
                    .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { teamHeaderHeight = $0 }
                    .padding(.top, topContentInset)
            }
        }
        .ignoresSafeArea(.container, edges: .bottom)
        .background {
            WindowBottomSafeAreaReader(bottomInset: $bottomSafeAreaInset, windowHeight: $voiceWindowHeight,
                                       keyboardInset: $additionalKeyboardInset)
                .ignoresSafeArea(.container, edges: .bottom)
        }
        .foregroundStyle(.primary)
        .onChangeCompat(of: activeView) { _, view in
            if view != 0 { composerIsFocused = false }
            showsAgentPresetMenu = false
        }
        .onChangeCompat(of: store.selectedSessionId) { _, _ in
            showsAgentPresetMenu = false
        }
        .onDisappear {
            if store.conversationFileIsOpening { store.cancelWorkspaceFileDownload() }
        }
        .sheet(item: conversationPreviewBinding) { request in
            WorkspaceFilePreviewLoadingSheet(store: store, request: request)
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
        // 成员标题只覆盖正文，不缩小滚动视口，消息才能从导航栏后方经过。
        topContentInset + (store.isViewingTeamMember ? teamHeaderHeight : 0)
    }

    private var chat: some View {
        GeometryReader { geometry in
            // 使用避让键盘后的页面高度，给输入框和聊天内容保留空间。
            let extraInset = min(additionalKeyboardInset, max(0, geometry.size.height - 1))
            chatContent(taskListMaximumHeight: min(280, max(0, geometry.size.height - extraInset - pageTopInset) * 0.3))
                .padding(.bottom, extraInset)
        }
    }

    private func chatContent(taskListMaximumHeight: CGFloat) -> some View {
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
                        if hasContent, let sessionID { store.conversationContentBecameReadable(sessionID: sessionID) }
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
            .opacity(shouldShowHistoryPresentationMask ? 0 : 1)

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
                if hasVisibleConversationContent, store.gateway.subscriptionRecoveryExhausted,
                   let error = selectedHistoryError {
                    HStack(spacing: 10) {
                        Image(systemName: "exclamationmark.triangle")
                            .foregroundStyle(.orange)
                        Text(error.message).appFont(.footnote).lineLimit(2)
                        Spacer(minLength: 0)
                        Button("重新加载历史") {
                            if let id = store.selectedSessionId { store.loadHistory(for: id) }
                        }
                        .disabled(!store.gateway.state.isConnected)
                    }
                    .padding(12)
                    .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14))
                    .padding(.horizontal, 14)
                    .padding(.bottom, 8)
                    .accessibilityIdentifier("conversation-sync-retry")
                }
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
                            Spacer(minLength: 0)
                            newConversationWorkspaceControl
                        }
                        .padding(.horizontal, 14)
                        .padding(.bottom, 6)
                        if let tasks = store.selectedTaskProjection?.todos,
                           let goal = store.selectedGoalProjection?.goal {
                            TaskGoalPanels(
                                tasks: tasks,
                                taskListMaximumHeight: taskListMaximumHeight,
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
                                taskListMaximumHeight: taskListMaximumHeight,
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
                                taskListMaximumHeight: taskListMaximumHeight,
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
                        .disabled(store.isChangingNewConversationWorkspace)
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
        .overlayPreferenceValue(NewConversationWorkspaceAnchorKey.self) { anchor in
            if let anchor {
                GeometryReader { geometry in
                    ComposerPopoverOverlay(sourceFrame: geometry[anchor], availableSize: geometry.size,
                        isPresented: showsNewWorkspaceMenu && store.showsNewConversationWorkspace,
                        onDismiss: { showsNewWorkspaceMenu = false }, preferredWidth: 292,
                        alignsToTrailing: true, maximumContentHeight: 380) {
                        newConversationWorkspaceMenu
                    }
                }
            }
        }
        .overlay(alignment: .bottom) {
            if let voiceToast {
                Text(voiceToast)
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
            // 焦点布局先提交，语音状态同步和历史贴底不占用动画起步的同一轮主线程。
            DispatchQueue.main.async {
                guard composerIsFocused == isFocused else { return }
                store.voiceInput.keyboardChanged(isFocused)
                if isFocused { viewportScrollToBottomToken &+= 1 }
            }
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
            Text(voiceState.alertMessage?.localized ?? "")
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
            store.voiceInput.onDraftUpdate = { composerTextBinding.wrappedValue = $0 }
            store.voiceInput.open(context: store.selectedSessionId ?? "new-session", draft: composerEditableText)
            store.voiceInput.keyboardChanged(composerIsFocused)
        }
        .onDisappear {
            voiceTouchActive = false
            store.voiceInput.leave()
            store.leaveSessionAgentPreset()
        }
        .onChangeCompat(of: store.selectedSessionId) { previous, current in
            if !store.isDraftWorkspaceReplacement(from: previous, to: current) {
                if let previous {
                    memberDrafts[previous] = draft
                    memberImages[previous] = pendingImages
                }
                draft = current.flatMap { memberDrafts[$0] } ?? ""
                pendingImages = current.flatMap { memberImages[$0] } ?? []
            }
            selectedPhotoItems = []
            store.voiceInput.open(context: current ?? "new-session", draft: composerEditableText)
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

    private var selectedHistoryError: HistoryLoadError? {
        store.selectedSessionId.flatMap { store.historyLoadErrors[$0] }
    }

    private func historyFailureState(_ error: HistoryLoadError) -> some View {
        VStack(spacing: 14) {
            Image(systemName: "exclamationmark.triangle").appFont(.largeTitle).foregroundStyle(.orange)
            Text(error.title).appFont(.title3.weight(.semibold))
            Text(error.message)
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
        guard store.selectedSessionId == filePickerSessionID else { return }
        switch result {
        case .success(let urls):
            guard !urls.isEmpty else { return }
            store.voiceInput.setVoiceMode(false)
            Task {
                guard await store.ensureFileUploadSession() else { return }
                store.fileUploads.select(sessionID: store.selectedSessionId, allowed: !store.isViewingTeamMember)
                await store.fileUploads.importFiles(urls)
            }
        case .failure(let error):
            let cocoaError = error as NSError
            guard cocoaError.domain != NSCocoaErrorDomain || cocoaError.code != NSUserCancelledError else { return }
            store.lastError = error.localizedDescription
        }
    }

    // 真机主线程栈有限；在内容、外观和生命周期之间截断嵌套泛型，
    // 避免首次进入会话时解析整个输入卡片类型导致栈溢出。
    private var composerCard: some View {
        AnyView(composerCardSessionLifecycle)
        .task(id: sharedDraftReadyKey) { await importSharedDraft() }
        .onChangeCompat(of: selectedPhotoItems) { _, items in
            guard !items.isEmpty else { return }
            store.voiceInput.setVoiceMode(false)
            let target = store.selectedSessionId
            Task { await importPhotos(items, targetSessionID: target) }
        }
        .onChangeCompat(of: pendingImages.count) { old, count in
            if count > old { composerIsFocused = true }
        }
        .onChangeCompat(of: store.fileUploads.drafts.count) { old, count in
            if count > old { composerIsFocused = true }
        }
        .onChangeCompat(of: draft) { _, value in
            store.voiceInput.draftChanged(composerEditableText)
            store.updateSlashCommandInput(value)
        }
        .onChangeCompat(of: store.commandDraftClearToken) { _, _ in
            draft = ""
            pendingImages = []
            selectedPhotoItems = []
            composerIsFocused = false
        }
    }

    private var sharedDraftReadyKey: String {
        guard let share = incomingShares.pending, share.target.gatewayID == store.gatewayLocalID,
              share.target.sessionID == store.selectedSessionId,
              !store.historyLoadingSessionIds.contains(share.target.sessionID),
              !store.conversationPreparingSessionIDs.contains(share.target.sessionID),
              !isPreparingHistoryPresentation else { return "" }
        return "\(share.id.uuidString):\(store.fileUploads.supported):\(store.gateway.state.isConnected)"
    }

    @MainActor
    private func importSharedDraft() async {
        guard !sharedDraftReadyKey.isEmpty, let share = incomingShares.pending,
              importingShareID != share.id else { return }
        let urls = ShareInbox.fileURLs(share)
        let files = urls.filter { UTType(filenameExtension: $0.pathExtension)?.conforms(to: .image) != true }
        guard files.isEmpty || (store.fileUploads.supported && store.gateway.state.isConnected) else {
            store.lastError = String(localized: "分享文件需要连接支持文件上传的网关，内容已保留，请连接后重新打开会话。")
            return
        }
        guard store.fileUploads.drafts.count + files.count <= 20, !store.fileUploads.preparing else {
            store.lastError = String(localized: "请先处理已有附件后重新打开会话，分享内容已保留。")
            return
        }
        importingShareID = share.id
        defer { importingShareID = nil }
        var images: [GatewayOutgoingImage] = []
        do {
            for url in urls where !files.contains(url) {
                let image = try await Task.detached(priority: .userInitiated) {
                    try GatewayImagePreprocessor.prepare(data: Data(contentsOf: url))
                }.value
                images.append(GatewayOutgoingImage(mediaType: image.mediaType, data: image.data))
            }
            guard store.selectedSessionId == share.target.sessionID, !Task.isCancelled else { return }
            guard pendingImages.count + images.count <= 20,
                  (pendingImages + images).reduce(0, { $0 + $1.data.count }) <= 100 * 1024 * 1024 else {
                store.lastError = String(localized: "分享图片超出单条消息附件限制，请先处理已有附件。")
                return
            }
            if !files.isEmpty {
                store.fileUploads.select(sessionID: share.target.sessionID, allowed: !store.isViewingTeamMember)
                let previousIDs = Set(store.fileUploads.drafts.map(\.id))
                await store.fileUploads.importFiles(files)
                guard store.selectedSessionId == share.target.sessionID, !Task.isCancelled else {
                    if store.selectedSessionId == share.target.sessionID {
                        for item in store.fileUploads.drafts where !previousIDs.contains(item.id) { store.fileUploads.remove(item.id) }
                    }
                    return
                }
                guard store.fileUploads.drafts.count - previousIDs.count == files.count else {
                    for item in store.fileUploads.drafts where !previousIDs.contains(item.id) { store.fileUploads.remove(item.id) }
                    return
                }
            }
            if !share.text.isEmpty {
                draft = store.supportsMessageQuotes
                    ? ComposerQuoteDraft.shared.insertQuote(draft: draft, quote: share.text, selectionStart: 0, selectionEnd: 0)
                    : [draft, share.text].filter { !$0.isEmpty }.joined(separator: "\n\n")
            }
            pendingImages.append(contentsOf: images)
            store.voiceInput.setVoiceMode(false)
            composerIsFocused = true
            incomingShares.complete(share)
        } catch { store.lastError = error.localizedDescription }
    }

    private var composerCardSessionLifecycle: some View {
        AnyView(composerCardSurface)
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
            if initialDraftFocusPending {
                initialDraftFocusPending = false
                store.voiceInput.setVoiceMode(false)
                composerIsFocused = true
            }
        }
    }

    private var composerExpanded: Bool {
        composerIsFocused || voiceState.voiceMode || voiceState.inputBlocked || voiceState.pendingTranscript != nil
    }

    private var composerLayoutAnimation: Animation {
        .easeOut(duration: reduceMotion ? 0.12 : 0.22)
    }

    private var composerCardSurface: some View {
        AnyView(composerCardContent)
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .glassSurface(radius: 24, tint: glassTint, shadowOpacity: 0)
        // 先收住玻璃自带的向下投影，再单独控制输入框阴影。
        .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .stroke(glassEdge, lineWidth: 0.7)
        }
        // 轻微下移增强底部层次，保持原有扩散范围。
        .shadow(color: .black.opacity(colorScheme == .dark ? 0.24 : 0.10), radius: 10, x: 0, y: 2)
        // 同一事务覆盖玻璃外框、裁剪边界和内部布局，避免外框先跳到终点，
        // 把仍在移动的加号裁掉，再从卡片底部重新露出。
        .animation(composerLayoutAnimation, value: composerExpanded)
        // 原生选择手柄可伸出卡片；从编辑区开始的整段拖动不能交给会话翻页。
        .sessionPagerHorizontalScrollRegion(gestureOutset: 24)
        .padding(.horizontal, 14)
        .padding(.bottom, composerBottomPadding)
    }

    private var composerEditableText: String {
        store.supportsMessageQuotes ? ComposerQuoteDraft.shared.editableText(draft: draft) : draft
    }

    private var composerTextBinding: Binding<String> {
        Binding(get: { composerEditableText }, set: { text in
            draft = store.supportsMessageQuotes ? ComposerQuoteDraft.shared.replaceText(draft: draft, text: text) : text
        })
    }

    private var composerQuoteParts: [GatewayTextPart] {
        guard store.supportsMessageQuotes,
              let json = GatewayQuoteTextParts.shared.parseJson(text: draft),
              let parts = try? JSONDecoder().decode([GatewayTextPart].self, from: Data(json.utf8)) else { return [] }
        return parts
    }

    private var composerCardContent: some View {
        let parts = composerQuoteParts
        let slashPresentation = slashCommandComposerPresentation(
            draft: composerEditableText,
            token: store.slashCommands.commandToken,
            hint: store.slashCommands.argumentHint
        )
        return ComposerQuoteStack(parts: parts, visible: composerExpanded, onRemove: { index in
            draft = ComposerQuoteDraft.shared.removeQuote(draft: draft, index: Int32(index))
        }) {
            ComposerAttachmentStack(shown: composerExpanded,
                hasContent: !pendingImages.isEmpty || store.fileUploads.preparing || !store.fileUploads.drafts.isEmpty) {
                VStack(alignment: .leading, spacing: 12) {
                    if !pendingImages.isEmpty { pendingImageStrip }
                    if store.fileUploads.preparing { ProgressView("正在准备文件…").appFont(.caption) }
                    if !store.fileUploads.drafts.isEmpty { pendingFileStrip }
                }
            } content: {
                VStack(alignment: .leading, spacing: 12) {
                    ComposerMorphLayout(expansion: composerExpanded ? 1 : 0,
                                        keepActionWhenCollapsed: showsSessionStopButton || composerHasContent) {
                        attachmentMenu
                        VoiceComposerTextInput(
                            text: composerTextBinding,
                            isFocused: $composerIsFocused,
                            presentation: slashPresentation,
                            state: voiceState,
                            voice: store.voiceInput,
                            isCompact: !composerExpanded,
                            onHoldingChanged: { voiceTouchActive = $0 },
                            onSelectionChange: { draftSelection = $0 },
                            onPasteAttachments: { providers in
                                let target = store.selectedSessionId
                                Task { await importPastedAttachments(providers, targetSessionID: target) }
                            }
                        )
                        .offset(y: voiceState.voiceMode ? -4 : 0)
                        // 原生文字位置直接跟随本帧布局，避免 UIKit 再补一段位移动画。
                        .transaction { $0.animation = nil }
                        ZStack {
                            if composerExpanded { composerTools.transition(.opacity) }
                        }
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        ZStack {
                            if composerExpanded || showsSessionStopButton || composerHasContent { composerAction.transition(.opacity) }
                        }
                        .frame(width: 42, height: 42)
                    }
                    .animation(composerLayoutAnimation, value: composerExpanded)
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
                }
            }
        }
    }

    private var composerTools: some View {
        HStack(spacing: 7) {
            if !store.isViewingTeamMember { permissionControl }

            Spacer(minLength: 2)

            if !store.isViewingTeamMember { modelControl }
            else {
                Text(store.selectedSession?.title ?? String(localized: "成员会话")).appFont(.caption).foregroundStyle(.secondary).lineLimit(1)
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
                if #available(iOS 16.4, *) {
                    ContextUsagePopover(snapshot: store.selectedContextSnapshot)
                        .presentationCompactAdaptation(.popover)
                } else {
                    ContextUsagePopover(snapshot: store.selectedContextSnapshot)
                        .background(.ultraThinMaterial)
                }
            }
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

    private var composerAction: some View {
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
                : (composerHasContent && (store.isChangingNewConversationWorkspace || store.sessionAgentPreset.blocksSending || store.waitingForNewSession || isImportingImages || store.fileUploads.preparing || (!store.fileUploads.drafts.isEmpty && !store.fileUploads.canSend) || store.commandSubmissionPending || store.messageSubmissionPending)))
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
                        Text(item.preview.localized).appFont(.subheadline).lineLimit(2)
                        if item.attachmentCount > 0 {
                            Text("\(item.attachmentCount) 个附件").appFont(.caption).foregroundStyle(.secondary)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    if store.queueState.pendingItemId == item.id {
                        ProgressView().frame(width: 36, height: 40)
                    } else {
                        queueActionButton("pencil", label: String(localized: "编辑排队消息"), itemID: item.id, action: "edit", enabled: item.editable)
                        queueActionButton("trash", label: String(localized: "删除排队消息"), itemID: item.id, action: "remove")
                        queueActionButton("arrow.up", label: String(localized: "立即插话"), itemID: item.id, action: "steer", enabled: store.selectedSession?.isRunning == true)
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
        case "uploading": String(localized: "upload.progress", defaultValue: "上传中 \(file.size > 0 ? file.confirmedBytes * 100 / file.size : 0)%")
        case "committing": String(localized: "处理中…")
        case "ready": String(localized: "upload.ready", defaultValue: "可发送 · \(ByteCountFormatter.string(fromByteCount: file.size, countStyle: .file))")
        default: file.error?.localized ?? String(localized: "upload.failed")
        }
    }

    private var pendingImageStrip: some View {
        let previews = pendingImages.map { source in
            let image = UIImage(data: source.data)
            return (source: source, image: image,
                    size: image.map(pendingImagePreviewSize) ?? CGSize(width: 72, height: 72))
        }
        let height = previews.map { max(20, $0.size.height) + 5 }.max() ?? 0
        return ScrollView(.horizontal, showsIndicators: false) {
            HStack(alignment: .top, spacing: 8) {
                ForEach(previews, id: \.source.id) { preview in
                    ZStack(alignment: .topTrailing) {
                        if let image = preview.image {
                            Image(uiImage: image)
                                .resizable()
                                .scaledToFit()
                                .frame(width: preview.size.width, height: preview.size.height)
                                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                        }
                        Button {
                            pendingImages.removeAll { $0.id == preview.source.id }
                        } label: {
                            Image(systemName: "xmark")
                                .font(.system(size: 10, weight: .semibold))
                                .foregroundStyle(.white)
                                .frame(width: 20, height: 20, alignment: .center)
                                .background(.black.opacity(0.68), in: Circle())
                                .contentShape(Circle())
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
        .frame(height: height, alignment: .top)
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
    private func importPastedAttachments(_ providers: [NSItemProvider], targetSessionID: String?) async {
        guard store.selectedMemberCanSend else { return }
        guard !isImportingImages, !store.fileUploads.preparing else {
            store.lastError = String(localized: "请等待已有附件准备完成后再粘贴")
            return
        }
        guard providers.count + pendingImages.count + store.fileUploads.drafts.count <= 20 else {
            store.lastError = String(localized: "一次最多添加 20 个附件")
            return
        }
        isImportingImages = true
        defer { isImportingImages = false }
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("composer-paste-\(UUID())")
        defer { try? FileManager.default.removeItem(at: directory) }
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            var files: [URL] = []
            var images: [GatewayOutgoingImage] = []
            var bytes = 0
            for (index, provider) in providers.enumerated() {
                let (url, image) = try await ComposerPasteLoader.copy(provider, to: directory, index: index)
                bytes += try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                guard bytes <= 100 * 1024 * 1024 else { throw CocoaError(.fileReadTooLarge) }
                if image {
                    let prepared = try await Task.detached(priority: .userInitiated) {
                        try GatewayImagePreprocessor.prepare(data: Data(contentsOf: url))
                    }.value
                    images.append(GatewayOutgoingImage(mediaType: prepared.mediaType, data: prepared.data))
                } else { files.append(url) }
            }
            guard store.selectedSessionId == targetSessionID, !Task.isCancelled else { return }
            guard images.isEmpty || store.supportsImages,
                  files.isEmpty || store.fileUploads.supported && !store.isViewingTeamMember else {
                store.lastError = String(localized: "当前会话或网关不支持此类附件")
                return
            }
            guard (pendingImages + images).reduce(0, { $0 + $1.data.count }) <= 100 * 1024 * 1024 else {
                throw CocoaError(.fileReadTooLarge)
            }
            if !files.isEmpty {
                guard await store.ensureFileUploadSession() else { return }
                let destination = store.selectedSessionId
                store.fileUploads.select(sessionID: store.selectedSessionId, allowed: !store.isViewingTeamMember)
                await store.fileUploads.importFiles(files)
                guard store.selectedSessionId == destination, !Task.isCancelled else { return }
            }
            pendingImages.append(contentsOf: images)
            store.voiceInput.setVoiceMode(false)
            composerIsFocused = true
        } catch { store.lastError = String(localized: "无法读取粘贴的附件：\(error.localizedDescription)") }
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
        if store.selectedSessionId == targetSessionID {
            if destination.count > pendingImages.count {
                store.voiceInput.setVoiceMode(false)
                composerIsFocused = true
            }
            pendingImages = destination
        } else if let targetSessionID { memberImages[targetSessionID] = destination }
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
    private var newConversationWorkspaceControl: some View {
        if store.showsNewConversationWorkspace {
            let selected = store.workspaces.first { $0.id == store.newConversationWorkspace.workspaceId }
            Button {
                viewportProxy.prepareForOverlayPresentation()
                showsAgentPresetMenu = false
                showsNewWorkspaceMenu = true
            } label: {
                HStack(spacing: 6) {
                    Image("DshWorkspace").renderingMode(.template).resizable().scaledToFit()
                        .frame(width: 16, height: 16).accessibilityHidden(true)
                    Text(store.isChangingNewConversationWorkspace ? String(localized: "切换中…")
                         : selected?.title ?? String(localized: "未分组"))
                        .lineLimit(1).truncationMode(.middle)
                    Image(systemName: "chevron.down").appFont(.caption2.weight(.semibold)).foregroundStyle(.secondary)
                }
                .appFont(.subheadline.weight(.medium))
                .padding(.horizontal, 12).frame(height: composerCapsuleHeight)
                .contentShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            }
            .buttonStyle(.plain)
            .glassSurface(radius: 16, tint: glassTint.opacity(0.88))
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay { RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(glassEdge.opacity(0.9), lineWidth: 0.6) }
            .disabled(!store.gateway.state.isConnected || store.isChangingNewConversationWorkspace || isImportingImages || store.fileUploads.preparing)
            .accessibilityLabel("选择工作区")
            .accessibilityIdentifier("new-conversation-workspace-capsule")
            .anchorPreference(key: NewConversationWorkspaceAnchorKey.self, value: .bounds) { $0 }
        }
    }

    private var newConversationWorkspaceMenu: some View {
        VStack(spacing: 0) {
            newConversationWorkspaceItem(nil, title: String(localized: "未分组"), path: nil)
            ForEach(store.workspaces) { workspace in
                newConversationWorkspaceItem(workspace.id, title: workspace.title, path: workspace.path)
            }
        }
    }

    private func newConversationWorkspaceItem(_ id: String?, title: String, path: String?) -> some View {
        Button {
            showsNewWorkspaceMenu = false
            Task { await store.selectNewConversationWorkspace(id) }
        } label: {
            HStack(spacing: 10) {
                Image("DshWorkspace").renderingMode(.template).resizable().scaledToFit().frame(width: 18, height: 18)
                VStack(alignment: .leading, spacing: 4) {
                    Text(title).appFont(.body).lineLimit(1)
                    if let path { Text(path).appFont(.caption).foregroundStyle(.secondary).lineLimit(1).truncationMode(.middle) }
                }
                Spacer(minLength: 0)
                if id == store.newConversationWorkspace.workspaceId { Image(systemName: "checkmark").foregroundStyle(DSHColor.ocean) }
            }
            .padding(.horizontal, 18).padding(.vertical, 10).frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("new-conversation-workspace-\(id ?? "ungrouped")")
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
                    showsNewWorkspaceMenu = false
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
                    Text(state.saving ? String(localized: "切换中…") : current?.name ?? state.agentPreset.map(agentPresetTitle)
                         ?? (state.loading ? String(localized: "读取模式…") : String(localized: "重试模式")))
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
            .disabled(!store.gateway.state.isConnected || store.isChangingNewConversationWorkspace || !(state.canSelect || retry))
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
                                 ? preset.brokenReason ?? String(localized: "模式不可用")
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
        case "standard": return String(localized: "完整工具，适合日常开发")
        case "code": return String(localized: "用代码组合多步工具操作")
        case "minimal": return String(localized: "仅用 Shell，轻量直接")
        case "cordis": return String(localized: "创建和调试自定义 Agent")
        default: return description ?? String(localized: "自定义 Agent 模式")
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
                title: currentModelTitle,
                effortTitle: currentEffortTitle,
                isLoading: modelIsLoading,
                isDisabled: modelGroups.isEmpty || store.selectedModelCatalog?.routable == false,
                groups: modelGroups,
                selection: currentModelSelection,
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
                            L10n.permissionName(for: option.value, fallback: option.name),
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
        return currentEfforts.first(where: { $0.id == id })?.name
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
        let serverLabel = permissionOptions.first(where: { $0.value == value })?.name
            ?? store.permissionDefaultOptions.first(where: { $0.value == value })?.name
        return L10n.permissionName(for: value, fallback: serverLabel)
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
        guard let current = currentModelSelection,
              currentEfforts.contains(where: { $0.id == effort.id }) else { return }
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
            case .timeSeparator: false
            case .message(let item): item.id == eventID || item.id.hasPrefix(eventID + "-")
            case .process(let group): group.items.contains { $0.id == eventID || $0.id.hasPrefix(eventID + "-") }
            }
        }?.id ?? target
    }

    private func makeConversationViewportEntries(from items: [ConversationItem]) -> [ConversationViewportEntry] {
        guard let sessionID = store.selectedSessionId else { return [] }
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
               item.kind == .user,
               item.textParts?.contains(where: { $0.type == "quote" }) != true {
                return [ConversationViewportEntry(
                    id: entry.id,
                    revision: revision,
                    userMessage: .init(item: item, imageData: { store.imageData(for: $0, sessionID: sessionID) },
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
                        imageData: { store.imageData(for: $0, sessionID: sessionID) },
                        imageError: { store.imageLoadError(for: $0, sessionID: sessionID) },
                        onRetryImage: { store.retryImageAttachment($0, sessionID: sessionID) },
                        onOpenFile: conversationFileOpener(),
                        onOpenUploadedFile: uploadedFileOpener()
                    ).environment(\.messageQuoteAction, composerQuoteAction)
                        .environment(\.workspaceMarkdownImageContext, store.markdownImageContext(sessionID: sessionID))),
                    // Completed responses are immutable for this revision.
                    // The viewport measures their SwiftUI root directly before
                    // caching, including fenced-code horizontal scroll views.
                    clipsContentToBounds: true,
                    layoutRevision: viewportEntryRevision(entry, includesImageState: false)
                )]
            case .timeSeparator(let date):
                return [ConversationViewportEntry(
                    id: entry.id,
                    revision: revision,
                    content: AnyView(ConversationTimeSeparator(date: date))
                )]
            case .process(let group):
                return processViewportEntries(for: group)
            }
        }
        // 首屏历史尚未合并时不创建进度占位，避免它提前完成首次贴底定位。
        if store.selectedSession?.isRunning == true, !items.isEmpty || !isLoadingSelectedHistory {
            let timing = store.selectedTurnTiming
            let latestUserIndex = items.lastIndex { $0.kind == .user } ?? -1
            let cues = items.dropFirst(latestUserIndex + 1).compactMap(DeepSearchProgressCue.init)
            var hasher = Hasher()
            hasher.combine(timing?.startSequence)
            hasher.combine(timing?.anchorMonotonicSeconds)
            hasher.combine(timing?.elapsedAtAnchor)
            for cue in cues {
                hasher.combine(cue.id)
                hasher.combine(cue.kind)
                hasher.combine(cue.isError)
            }
            entries.append(ConversationViewportEntry(
                id: "deep-search-progress",
                revision: hasher.finalize(),
                content: AnyView(DeepSearchProgressRow(timing: timing, cues: cues))
            ))
        }
        return entries
    }

    private var composerQuoteAction: ((String) -> Void)? {
        guard store.supportsMessageQuotes else { return nil }
        return { selected in
            guard !selected.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
            let count = (composerEditableText as NSString).length
            let start = min(draftSelection.location, count)
            let end = start + min(draftSelection.length, count - start)
            draft = ComposerQuoteDraft.shared.insertQuote(draft: draft, quote: selected,
                selectionStart: Int32(start), selectionEnd: Int32(end))
            draftSelection = NSRange(location: start, length: 0)
            store.voiceInput.setVoiceMode(false)
            composerIsFocused = true
        }
    }

    private func processViewportEntries(for group: ConversationProcessGroup) -> [ConversationViewportEntry] {
        ConversationProcessNode.make(
            from: group,
            expandedNodeIDs: expandedConversationNodeIDs
        ).map { node in
            var revision = Hasher()
            revision.combine(node.revision)
            revision.combine(store.supportsMessageQuotes)
            return ConversationViewportEntry(
                id: node.id,
                revision: revision.finalize(),
                content: AnyView(processNodeView(node).environment(\.messageQuoteAction, composerQuoteAction)),
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

    private func viewportEntryRevision(_ entry: ConversationDisplayEntry, includesImageState: Bool = true) -> Int {
        let sessionID = store.selectedSessionId ?? ""
        var hasher = Hasher()
        hasher.combine(store.supportsMessageQuotes)
        hasher.combine(entry.id)
        hasher.combine(entry.showsCopyButton)
        switch entry.content {
        case .timeSeparator(let date):
            hasher.combine(date)
            hasher.combine(Calendar.current.startOfDay(for: .now))
            hasher.combine(TimeZone.current.identifier)
        case .message(let item):
            hasher.combine(item.id)
            hasher.combine(item.title)
            hasher.combine(item.text)
            hasher.combine(item.textParts)
            hasher.combine(item.isError)
            hasher.combine(item.files)
            hasher.combine(item.knownFiles)
            for image in item.images {
                hasher.combine(image)
                if includesImageState {
                    hasher.combine(store.imageData(for: image.id, sessionID: sessionID) != nil)
                    hasher.combine(store.imageLoadError(for: image.id, sessionID: sessionID))
                }
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
        case .user, .generatedImages, .system: return nil
        }
        id = item.id
        date = item.date
        isError = item.isError
    }
}

private struct DeepSearchProgressRow: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorScheme) private var colorScheme

    let timing: SharedTurnTimingSnapshot?
    let cues: [DeepSearchProgressCue]
    private var startDate: Date { Date(timeIntervalSince1970: timing?.startEpochSeconds ?? 0) }

    private enum Activity: Hashable { case thinking, gathering, exploring, reviewing, revisiting, composing, working }
    private static let phraseIntervalSeconds = 30
    private static let phrases: [Activity: [String]] = [
        .thinking: [String(localized: "梳理脉络中"), String(localized: "推敲细节中"), String(localized: "酝酿下一步中"), String(localized: "沿着思路前行中")],
        .gathering: [String(localized: "翻阅已有线索中"), String(localized: "收拢相关信息中"), String(localized: "寻找切入点中"), String(localized: "铺开问题脉络中")],
        .exploring: [String(localized: "向外寻找线索中"), String(localized: "打捞新线索中"), String(localized: "沿途探查中"), String(localized: "搜集更多拼图中")],
        .reviewing: [String(localized: "对照新发现中"), String(localized: "拼合新线索中"), String(localized: "检视所得中"), String(localized: "把发现串起来中")],
        .revisiting: [String(localized: "回看已有线索中"), String(localized: "重新校准方向中"), String(localized: "再推敲一遍中"), String(localized: "换个角度审视中")],
        .composing: [String(localized: "组织回应中"), String(localized: "整理表达中"), String(localized: "将思路落成文字中"), String(localized: "勾勒答案中")],
        .working: [String(localized: "继续推进中"), String(localized: "稳步探索中"), String(localized: "梳理当前进展中"), String(localized: "逐步展开中")]
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
        let options = Self.phrases[activity] ?? [String(localized: "梳理脉络中")]
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
                let elapsed = Int(min(Double(Int32.max), timing?.elapsedSeconds(
                    nowMonotonicSeconds: AgentTurnMonotonicClock.now
                ) ?? 0))
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
                    (timing == nil ? Text("正在同步执行状态…") : Text("\(phrase)，用时 \(elapsed) 秒…"))
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
    let taskListMaximumHeight: CGFloat
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
            .accessibilityLabel(tasksExpanded ? String(localized: "收起任务") : String(localized: "展开任务"))

            if tasksExpanded {
                ContentSizedCommandScrollView(maximumHeight: taskListMaximumHeight) {
                    VStack(alignment: .leading, spacing: 9) {
                        ForEach(Array(tasks.enumerated()), id: \.offset) { _, task in
                            HStack(spacing: 11) {
                                taskStatusIcon(task.status)
                                Text(task.content)
                                    .appFont(.body)
                                    .foregroundStyle(task.status == "completed" ? .secondary : .primary)
                                    .multilineTextAlignment(.leading)
                                    .fixedSize(horizontal: false, vertical: true)
                                Spacer(minLength: 0)
                            }
                        }
                    }
                }
                .accessibilityIdentifier("conversation-task-list")
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
                    active ? String(localized: "暂停目标") : String(localized: "继续目标"),
                    onPauseResume
                )
                goalActionButton("pencil", String(localized: "编辑目标"), onEdit)
                goalActionButton("trash", String(localized: "删除目标"), onClear)
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
            completedCount > 0 ? String(localized: "\(completedCount) 已完成") : nil,
            activeCount > 0 ? String(localized: "\(activeCount) 进行中") : nil,
            pendingCount > 0 ? String(localized: "\(pendingCount) 待处理") : nil
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
        case "active": String(localized: "进行中的目标")
        case "paused": String(localized: "已暂停的目标")
        case "blocked": String(localized: "受阻的目标")
        default: String(localized: "当前目标")
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
    let title: String
    let effortTitle: String?
    let isLoading: Bool
    let isDisabled: Bool
    let groups: [GatewayModelGroup]
    let selection: GatewayModelSelection?
    let onSelectModel: (GatewayModelGroup, GatewayModelItem) -> Void
    let onSelectEffort: (GatewayReasoningEffort) -> Void

    var body: some View {
        Menu {
            ConversationModelMenuContent(
                groups: groups,
                selection: selection,
                onSelectModel: onSelectModel,
                onSelectEffort: onSelectEffort
            )
        } label: {
            ConversationModelMenuLabel(title: title, effortTitle: effortTitle, isLoading: isLoading)
        }
        .menuOrder(.fixed)
        .disabled(isDisabled || isLoading)
        .accessibilityIdentifier("conversation-model-menu-toggle")
    }
}

private struct ConversationModelMenuContent: View {
    let groups: [GatewayModelGroup]
    let selection: GatewayModelSelection?
    let onSelectModel: (GatewayModelGroup, GatewayModelItem) -> Void
    let onSelectEffort: (GatewayReasoningEffort) -> Void

    private var currentModel: GatewayModelItem? {
        guard let selection else { return nil }
        return groups.first(where: { $0.id == selection.provider })?
            .models.first(where: { $0.id == selection.model })
    }

    private var efforts: [GatewayReasoningEffort] {
        currentModel?.reasoning?.efforts ?? []
    }

    var body: some View {
        Group {
            if groups.isEmpty {
                Text("正在读取模型…")
            } else {
                Menu {
                    ForEach(groups) { group in
                        Section(group.name) {
                            ForEach(group.models) { model in
                                option(model.name,
                                       selected: selection?.provider == group.id && selection?.model == model.id,
                                       identifier: "model-option-\(group.id)-\(model.id)") {
                                    onSelectModel(group, model)
                                }
                            }
                        }
                    }
                } label: {
                    Text("Model")
                    if let modelName = currentModel?.name ?? selection?.model {
                        Text(verbatim: modelName)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .accessibilityIdentifier("conversation-model-selection-submenu")

                if efforts.isEmpty {
                    Button("推理等级") {}
                        .disabled(true)
                        .accessibilityIdentifier("conversation-reasoning-effort-submenu")
                } else {
                    Menu {
                        ForEach(efforts) { effort in
                            option(effort.name, selected: effort.id == selection?.reasoningEffort,
                                   identifier: "model-effort-\(effort.id)") {
                                onSelectEffort(effort)
                            }
                        }
                    } label: {
                        Text(verbatim: submenuTitle(
                            String(localized: "推理等级"),
                            value: efforts.first(where: { $0.id == selection?.reasoningEffort })?.name
                        ))
                    }
                    .accessibilityIdentifier("conversation-reasoning-effort-submenu")
                }
            }
        }
    }

    private func submenuTitle(_ title: String, value: String?) -> String {
        value.map { "\(title)  \($0)" } ?? title
    }

    private func option(_ title: String, selected: Bool, identifier: String,
                        action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label(title, systemImage: selected ? "checkmark" : "circle")
        }
        .accessibilityAddTraits(selected ? [.isSelected] : [])
        .accessibilityIdentifier(identifier)
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
        private let veil = CAGradientLayer()

        init() {
            super.init(effect: UIBlurEffect(style: .light))
            isUserInteractionEnabled = false
            accessibilityElementsHidden = true
            fadeMask.colors = [UIColor.black.cgColor, UIColor.black.cgColor, UIColor.clear.cgColor]
            fadeMask.locations = [0, 0.45, 1]
            layer.mask = fadeMask
            veil.locations = [0, 0.4, 0.7, 1]
            contentView.layer.addSublayer(veil)
            updateAppearance()
        }

        required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

        override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
            super.traitCollectionDidChange(previousTraitCollection)
            updateAppearance()
        }

        private func updateAppearance() {
            let isDark = traitCollection.userInterfaceStyle == .dark
            effect = UIBlurEffect(style: isDark ? .dark : .light)
            let color = isDark ? UIColor.black : UIColor.white
            veil.colors = [0.96, 0.86, 0.70, 0].map { color.withAlphaComponent($0).cgColor }
        }

        override func layoutSubviews() {
            super.layoutSubviews()
            CATransaction.begin()
            CATransaction.setDisableActions(true)
            fadeMask.frame = bounds
            veil.frame = bounds
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
                    // 时间轴、输入卡片等交互区从触点开始独占整段手势，
                    // 内容未溢出或拖到区域之外也不交给翻页。
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
    /// gestureOutset 为伸出视图边界的原生文字选择手柄预留触摸范围。
    func sessionPagerHorizontalScrollRegion(gestureOutset: CGFloat = 0) -> some View {
        background {
            GeometryReader { geometry in
                Color.clear.preference(
                    key: SessionPagerHorizontalScrollRegionsKey.self,
                    value: [geometry.frame(in: .global).insetBy(dx: -gestureOutset, dy: -gestureOutset)]
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

/// 按实际内容收缩，超过调用方指定的高度后在内部滚动。
private struct ContentSizedCommandScrollView<Content: View>: View {
    @State private var contentHeight: CGFloat = 0
    var maximumHeight: CGFloat = 300
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
        .frame(height: min(contentHeight, maximumHeight))
    }
}

private struct ComposerHeightPreferenceKey: PreferenceKey {
    static var defaultValue: CGFloat = 0

    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        // 未提供测量值的分支不能覆盖输入区实际高度。
        value = max(value, nextValue())
    }
}

/// Reads the physical window inset even when the SwiftUI conversation surface
/// deliberately draws through the container safe area. This keeps glass and
/// shadows continuous while the interactive composer still avoids the Home
/// Indicator on every device shape.
/// 只补偿 SwiftUI 尚未避让的底部遮挡，避免重复叠加完整键盘高度。
enum ComposerKeyboardAvoidance {
    static func additionalInset(viewFrame: CGRect, keyboardFrame: CGRect, bottomSafeArea: CGFloat) -> CGFloat {
        guard !viewFrame.isEmpty, !viewFrame.isInfinite, !keyboardFrame.isNull, !keyboardFrame.isInfinite,
              keyboardFrame.height > bottomSafeArea + 0.5,
              keyboardFrame.minX <= viewFrame.minX + 1,
              keyboardFrame.maxX >= viewFrame.maxX - 1,
              keyboardFrame.maxY >= viewFrame.maxY - 1 else { return 0 }
        return min(viewFrame.height, max(0, viewFrame.maxY - keyboardFrame.minY))
    }
}

struct WindowBottomSafeAreaReader: UIViewRepresentable {
    @Binding var bottomInset: CGFloat
    @Binding var windowHeight: CGFloat
    @Binding var keyboardInset: CGFloat

    func makeUIView(context: Context) -> SafeAreaReportingView {
        let view = SafeAreaReportingView()
        view.onBottomInsetChange = publish
        view.onWindowHeightChange = publishHeight
        view.onKeyboardInsetChange = publishKeyboardInset
        return view
    }

    func updateUIView(_ view: SafeAreaReportingView, context: Context) {
        view.onBottomInsetChange = publish
        view.onWindowHeightChange = publishHeight
        view.onKeyboardInsetChange = publishKeyboardInset
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

    private func publishKeyboardInset(_ inset: CGFloat) {
        guard abs(keyboardInset - inset) > 0.5 else { return }
        DispatchQueue.main.async { keyboardInset = inset }
    }

    final class SafeAreaReportingView: UIView {
        var onBottomInsetChange: ((CGFloat) -> Void)?
        var onWindowHeightChange: ((CGFloat) -> Void)?
        var onKeyboardInsetChange: ((CGFloat) -> Void)?
        private var keyboardScreenFrame: CGRect?

        override init(frame: CGRect) {
            super.init(frame: frame)
            isUserInteractionEnabled = false
            // 用约束让布局随系统键盘区域更新，包括候选栏和输入法高度变化。
            // 浮动键盘不应把整页顶起，保留 followsUndockedKeyboard 的默认 false。
            let marker = UIView()
            marker.isUserInteractionEnabled = false
            marker.translatesAutoresizingMaskIntoConstraints = false
            addSubview(marker)
            NSLayoutConstraint.activate([
                marker.topAnchor.constraint(equalTo: keyboardLayoutGuide.topAnchor),
                marker.leadingAnchor.constraint(equalTo: leadingAnchor),
                marker.widthAnchor.constraint(equalToConstant: 0),
                marker.heightAnchor.constraint(equalToConstant: 0)
            ])
            // 部分输入法切换时，通知先于布局指南更新；用系统给出的窗口区域过渡。
            for name in [UIResponder.keyboardWillChangeFrameNotification,
                         UIResponder.keyboardDidChangeFrameNotification,
                         UIResponder.keyboardDidHideNotification] {
                NotificationCenter.default.addObserver(self, selector: #selector(keyboardChanged(_:)), name: name, object: nil)
            }
        }

        required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
        deinit { NotificationCenter.default.removeObserver(self) }

        @objc private func keyboardChanged(_ notification: Notification) {
            guard window != nil, notification.userInfo?[UIResponder.keyboardIsLocalUserInfoKey] as? Bool != false else { return }
            keyboardScreenFrame = notification.name == UIResponder.keyboardDidHideNotification ? nil
                : (notification.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? NSValue)?.cgRectValue
            publishBottomInset()
        }

        override func layoutSubviews() {
            super.layoutSubviews()
            publishBottomInset()
        }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            if window == nil { keyboardScreenFrame = nil }
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
            var keyboardFrame = keyboardLayoutGuide.layoutFrame
            if keyboardFrame.height <= inset + 0.5, let screenFrame = keyboardScreenFrame, let window {
                let windowFrame = window.convert(screenFrame, from: window.screen.coordinateSpace)
                keyboardFrame = convert(windowFrame, from: window)
            }
            onKeyboardInsetChange?(ComposerKeyboardAvoidance.additionalInset(
                viewFrame: bounds, keyboardFrame: keyboardFrame, bottomSafeArea: inset
            ))
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

/// 面板单独订阅统计更新，导航栏不需要随每条会话事件重建。
struct SessionStatsPresentation: View {
    @ObservedObject var store: AppStore

    var body: some View {
        SessionStatsSheet(snapshot: store.selectedSessionStatsSnapshot,
            sessionTitle: store.selectedSession?.title ?? String(localized: "session.new.fallback", defaultValue: "新建 DeepSeek Harness"))
    }
}

private struct SessionStatsSheet: View {
    let snapshot: GatewaySessionStatsSnapshot?
    let sessionTitle: String
    @State private var selectedDetent: PresentationDetent = .large
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
                        DshEmptyState(String(localized: "正在读取会话统计"), systemImage: "chart.bar.xaxis", description: Text("统计会在本轮结束后自动更新。"))
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
        .presentationDetents([.medium, .large], selection: $selectedDetent)
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

private struct SessionAgentPresetPopoverAnchorKey: PreferenceKey {
    static var defaultValue: Anchor<CGRect>? { nil }

    static func reduce(value: inout Anchor<CGRect>?, nextValue: () -> Anchor<CGRect>?) {
        value = nextValue() ?? value
    }
}

private struct NewConversationWorkspaceAnchorKey: PreferenceKey {
    static var defaultValue: Anchor<CGRect>? { nil }
    static func reduce(value: inout Anchor<CGRect>?, nextValue: () -> Anchor<CGRect>?) { value = nextValue() ?? value }
}

/// 模式菜单使用液态玻璃和箭头，原生玻璃自行处理高光与投影。
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

enum ConversationTimeLabel {
    static func text(for date: Date, now: Date = .now, timeZone: TimeZone = .current, locale: Locale = .current) -> String {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        let formatter = DateFormatter()
        formatter.locale = locale
        formatter.calendar = calendar
        formatter.timeZone = timeZone
        if calendar.isDate(date, inSameDayAs: now) {
            formatter.dateFormat = "H:mm"
        } else if calendar.component(.year, from: date) == calendar.component(.year, from: now) {
            formatter.dateFormat = String(localized: "conversation.time.date", locale: locale)
        } else {
            formatter.dateFormat = String(localized: "conversation.time.yearDate", locale: locale)
        }
        return formatter.string(from: date)
    }
}

private struct ConversationTimeSeparator: View {
    let date: Date

    var body: some View {
        Text(ConversationTimeLabel.text(for: date))
            .appFont(.system(size: 12))
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 16)
            .accessibilityIdentifier("conversation-time-separator")
    }
}

struct ConversationDisplayEntry: Identifiable {
    enum Content {
        case message(ConversationItem)
        case timeSeparator(Date)
        case process(ConversationProcessGroup)
    }

    let id: String
    let content: Content
    var showsCopyButton: Bool

    static func make(from items: [ConversationItem]) -> [ConversationDisplayEntry] {
        var result: [ConversationDisplayEntry] = []
        var processItems: [ConversationItem] = []
        var previousMessageDate: Date?

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
            case .generatedImages:
                // 图片仅保留助手正文中的展示，不额外插入工具结果图片卡片。
                continue
            case .user, .assistant, .system:
                flushProcess()
                // 只比较正式对话消息，工具和状态事件不影响半小时间隔。
                if item.kind != .system, item.date.timeIntervalSince1970.isFinite, item.date.timeIntervalSince1970 > 0 {
                    if let previousMessageDate, item.date.timeIntervalSince(previousMessageDate) > 30 * 60 {
                        result.append(.init(id: "time-\(item.id)", content: .timeSeparator(item.date), showsCopyButton: false))
                    }
                    previousMessageDate = item.date
                }
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
            name: call?.title ?? result?.title ?? "",
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
            case .user, .assistant, .generatedImages, .status, .system:
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
                    Text(summary.labelMessage.localized)
                        .appFont(.subheadline.weight(.medium))
                        .foregroundStyle(.secondary)
                    if let annotation = summary.annotationMessage?.localized {
                        Text(annotation)
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

/// 与 Android 使用相同上限；加载、失败和重试都不能改变占位区域。
struct ConversationAttachmentLayout: Layout {
    let sourceWidth: Int
    let sourceHeight: Int
    var maximumWidth: CGFloat = 320

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        Self.size(sourceWidth: sourceWidth, sourceHeight: sourceHeight,
                  availableWidth: min(maximumWidth, proposal.width ?? maximumWidth))
    }

    static func size(sourceWidth: Int, sourceHeight: Int, availableWidth: CGFloat) -> CGSize {
        let width = CGFloat(max(1, sourceWidth))
        let height = CGFloat(max(1, sourceHeight))
        let scale = min(1, max(0, min(320, availableWidth)) / width, 320 / height)
        return CGSize(width: width * scale, height: height * scale)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        for subview in subviews {
            subview.place(at: bounds.origin, anchor: .topLeading,
                          proposal: ProposedViewSize(width: bounds.width, height: bounds.height))
        }
    }
}

/// 列宽和每行高度直接由附件元数据计算，不依赖图片子视图的理想尺寸。
private struct ConversationAttachmentGridLayout: Layout {
    let attachments: [GatewayImageAttachment]

    private func sizes(width: CGFloat) -> [CGSize] {
        let columnWidth = attachments.count == 1 ? width : max(0, (width - 6) / 2)
        return attachments.map {
            ConversationAttachmentLayout.size(sourceWidth: $0.width, sourceHeight: $0.height,
                availableWidth: min(attachments.count == 1 ? 320 : 180, columnWidth))
        }
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? (attachments.count == 1 ? 320 : 366)
        let sizes = sizes(width: width)
        let height = stride(from: 0, to: sizes.count, by: 2).reduce(CGFloat.zero) { total, index in
            total + max(sizes[index].height, index + 1 < sizes.count ? sizes[index + 1].height : 0)
                + (index == 0 ? 0 : 6)
        }
        return CGSize(width: width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let sizes = sizes(width: bounds.width)
        let columnWidth = attachments.count == 1 ? bounds.width : max(0, (bounds.width - 6) / 2)
        var y = bounds.minY
        for index in stride(from: 0, to: min(sizes.count, subviews.count), by: 2) {
            for column in 0..<min(2, subviews.count - index) {
                let size = sizes[index + column]
                subviews[index + column].place(at: CGPoint(x: bounds.minX + CGFloat(column) * (columnWidth + 6), y: y),
                    anchor: .topLeading, proposal: ProposedViewSize(width: size.width, height: size.height))
            }
            y += max(sizes[index].height, index + 1 < sizes.count ? sizes[index + 1].height : 0) + 6
        }
    }
}

private struct AttachmentImageGrid: View {
    let attachments: [GatewayImageAttachment]
    let imageData: (String) -> Data?
    var imageError: (String) -> String? = { _ in nil }
    var onRetry: (GatewayImageAttachment) -> Void = { _ in }
    @State private var preview: GatewayImageAttachment?

    var body: some View {
        ConversationAttachmentGridLayout(attachments: attachments) {
            ForEach(attachments) { attachment in
                ConversationAttachmentLayout(
                    sourceWidth: attachment.width, sourceHeight: attachment.height,
                    maximumWidth: attachments.count == 1 ? 320 : 180
                ) {
                    Color.secondary.opacity(0.12)
                        .overlay {
                            AttachmentBitmap(data: imageData(attachment.id), maximumSide: 900, fillsBounds: true)
                        }
                        .overlay {
                            if let error = imageError(attachment.id) {
                                Button { onRetry(attachment) } label: {
                                    VStack(spacing: 4) {
                                        Text(error).lineLimit(2)
                                        Text("重试")
                                    }.appFont(.caption).padding(8)
                                }
                            } else if imageData(attachment.id) == nil {
                                ProgressView().controlSize(.small)
                            }
                        }
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                        .contentShape(Rectangle())
                        .onTapGesture { preview = attachment }
                        .accessibilityLabel(attachment.name ?? String(localized: "图片附件"))
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .fullScreenCover(item: $preview) { image in
            AttachmentGallery(attachments: attachments, initialID: image.id, imageData: imageData,
                              imageError: imageError, onRetry: onRetry)
        }
    }
}

// 解码在后台执行；列表与全屏分别限制像素大小，关闭预览即释放大图。
private struct AttachmentBitmap: View {
    let data: Data?
    let maximumSide: Int
    var fillsBounds = false
    @State private var bitmap: UIImage?

    var body: some View {
        Group {
            if let bitmap { Image(uiImage: bitmap).resizable().aspectRatio(contentMode: fillsBounds ? .fill : .fit) }
            else { ZStack { Color.secondary.opacity(0.12); Image(systemName: "photo").foregroundStyle(.secondary) } }
        }
        .task(id: data) {
            bitmap = nil
            guard let data else { return }
            let decoded = await Task.detached(priority: .userInitiated) { () -> UIImage? in
                guard let source = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary),
                      let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                        kCGImageSourceCreateThumbnailFromImageAlways: true,
                        kCGImageSourceCreateThumbnailWithTransform: true,
                        kCGImageSourceThumbnailMaxPixelSize: maximumSide,
                        kCGImageSourceShouldCacheImmediately: true
                      ] as CFDictionary) else { return nil }
                return UIImage(cgImage: image)
            }.value
            guard !Task.isCancelled else { return }
            bitmap = decoded
        }
    }
}

/// 原生滚动视图直接处理手指位移，不经过 SwiftUI 状态刷新或分页手势识别。
private struct ZoomableAttachmentImage: UIViewRepresentable {
    let data: Data?
    let attachment: GatewayImageAttachment
    let isActive: Bool

    func makeUIView(context: Context) -> AttachmentZoomScrollView {
        AttachmentZoomScrollView()
    }

    func updateUIView(_ view: AttachmentZoomScrollView, context: Context) {
        view.previewImageView.accessibilityLabel = attachment.name ?? String(localized: "图片")
        view.load(data)
        if !isActive { view.resetZoom() }
    }

    static func dismantleUIView(_ view: AttachmentZoomScrollView, coordinator: ()) {
        view.cancelLoading()
    }
}

final class AttachmentZoomScrollView: UIScrollView, UIScrollViewDelegate {
    let previewImageView = UIImageView()
    private let placeholder = UIImageView(image: UIImage(systemName: "photo"))
    private var sourceData: Data?
    private var decodeTask: Task<Void, Never>?
    private var viewportSize = CGSize.zero
    private var fittedImageSize = CGSize.zero

    init() {
        super.init(frame: .zero)
        delegate = self
        minimumZoomScale = 1
        maximumZoomScale = 5
        bounces = false
        bouncesZoom = false
        showsHorizontalScrollIndicator = false
        showsVerticalScrollIndicator = false
        contentInsetAdjustmentBehavior = .never
        backgroundColor = .clear
        clipsToBounds = true
        accessibilityIdentifier = "attachment-preview-scroll"
        panGestureRecognizer.isEnabled = false
        previewImageView.contentMode = .scaleAspectFit
        previewImageView.layer.cornerRadius = 12
        previewImageView.layer.cornerCurve = .continuous
        previewImageView.clipsToBounds = true
        previewImageView.isAccessibilityElement = true
        previewImageView.accessibilityIdentifier = "attachment-preview-image"
        addSubview(previewImageView)
        placeholder.tintColor = .white.withAlphaComponent(0.42)
        placeholder.contentMode = .scaleAspectFit
        addSubview(placeholder)
        let tap = UITapGestureRecognizer(target: self, action: #selector(doubleTapped(_:)))
        tap.numberOfTapsRequired = 2
        addGestureRecognizer(tap)
    }

    required init?(coder: NSCoder) { nil }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        // 放大时由图片接管拖动；原始比例下禁用内层 pan，翻页手势仍可正常识别。
        var ancestor = superview
        while let parent = ancestor {
            if let pager = parent as? UIScrollView {
                pager.panGestureRecognizer.require(toFail: panGestureRecognizer)
            }
            ancestor = parent.superview
        }
    }

    func load(_ data: Data?) {
        guard sourceData != data else { return }
        sourceData = data
        cancelLoading()
        display(nil)
        guard let data else { return }
        decodeTask = Task { [weak self] in
            let decoded = await Task.detached(priority: .userInitiated) { () -> UIImage? in
                guard let source = CGImageSourceCreateWithData(data as CFData,
                    [kCGImageSourceShouldCache: false] as CFDictionary),
                      let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                        kCGImageSourceCreateThumbnailFromImageAlways: true,
                        kCGImageSourceCreateThumbnailWithTransform: true,
                        kCGImageSourceThumbnailMaxPixelSize: 2048,
                        kCGImageSourceShouldCacheImmediately: true
                      ] as CFDictionary) else { return nil }
                return UIImage(cgImage: image)
            }.value
            guard !Task.isCancelled else { return }
            self?.display(decoded)
        }
    }

    func cancelLoading() {
        decodeTask?.cancel()
        decodeTask = nil
    }

    func display(_ image: UIImage?) {
        resetZoom()
        previewImageView.image = image
        placeholder.isHidden = image != nil
        pinchGestureRecognizer?.isEnabled = image != nil
        viewportSize = .zero
        setNeedsLayout()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        guard bounds.size != viewportSize else { return }
        viewportSize = bounds.size
        resetZoom()
        let imageSize = previewImageView.image?.size ?? CGSize(width: 1, height: 1)
        let fit = min(bounds.width / max(1, imageSize.width), bounds.height / max(1, imageSize.height))
        fittedImageSize = CGSize(width: imageSize.width * fit, height: imageSize.height * fit)
        previewImageView.frame = CGRect(origin: .zero, size: fittedImageSize)
        contentSize = fittedImageSize
        centerImage()
        contentOffset = CGPoint(x: -contentInset.left, y: -contentInset.top)
        placeholder.frame = CGRect(x: bounds.midX - 27, y: bounds.midY - 27, width: 54, height: 54)
    }

    func viewForZooming(in scrollView: UIScrollView) -> UIView? { previewImageView }

    func scrollViewDidZoom(_ scrollView: UIScrollView) {
        centerImage()
        panGestureRecognizer.isEnabled = zoomScale > minimumZoomScale + 0.001
    }

    private func centerImage() {
        let horizontal = max(0, (bounds.width - fittedImageSize.width * zoomScale) / 2)
        let vertical = max(0, (bounds.height - fittedImageSize.height * zoomScale) / 2)
        let inset = UIEdgeInsets(top: vertical, left: horizontal, bottom: vertical, right: horizontal)
        if contentInset != inset { contentInset = inset }
    }

    func resetZoom() {
        if zoomScale != minimumZoomScale { setZoomScale(minimumZoomScale, animated: false) }
        panGestureRecognizer.isEnabled = false
    }

    func toggleZoom(at point: CGPoint, animated: Bool = true) {
        guard previewImageView.image != nil else { return }
        if zoomScale > minimumZoomScale {
            setZoomScale(minimumZoomScale, animated: animated)
        } else {
            let target: CGFloat = 3
            let size = CGSize(width: bounds.width / target, height: bounds.height / target)
            zoom(to: CGRect(x: point.x - size.width / 2, y: point.y - size.height / 2,
                            width: size.width, height: size.height), animated: animated)
        }
    }

    @objc private func doubleTapped(_ gesture: UITapGestureRecognizer) {
        toggleZoom(at: gesture.location(in: previewImageView))
    }
}

struct AttachmentGallery: View {
    let attachments: [GatewayImageAttachment]
    let initialID: String
    let imageData: (String) -> Data?
    let imageError: (String) -> String?
    let onRetry: (GatewayImageAttachment) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var selectedID = ""
    @State private var saving = false
    @State private var saveMessage: String?
    var exportDocument: MarkdownImageAsset? = nil
    @State private var exporting = false

    private func save(_ attachment: GatewayImageAttachment) {
        guard !saving, let data = imageData(attachment.id) else { return }
        saving = true
        Task {
            defer { saving = false }
            do {
                try await PhotoLibraryImageSaver.save(data, mediaType: attachment.mediaType)
                saveMessage = String(localized: "已保存到相册")
            } catch {
                saveMessage = error.localizedDescription
            }
        }
    }

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Color.black.ignoresSafeArea()
            TabView(selection: $selectedID) {
                ForEach(attachments) { attachment in
                    VStack(spacing: 16) {
                        ZoomableAttachmentImage(data: imageData(attachment.id), attachment: attachment,
                                                isActive: selectedID == attachment.id)
                            .contextMenu {
                                Button { save(attachment) } label: {
                                    Label("保存到相册", systemImage: "square.and.arrow.down")
                                }
                                .disabled(saving || imageData(attachment.id) == nil)
                            }
                        if let error = imageError(attachment.id) {
                            Text(error).foregroundStyle(.white)
                        }
                        if imageData(attachment.id) == nil { Button("重试") { onRetry(attachment) } }
                    }
                    .padding(16).tag(attachment.id)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: attachments.count > 1 ? .always : .never))
            HStack(spacing: 20) {
                if exportDocument != nil {
                    Menu {
                        Button { exporting = true } label: {
                            Label(String(localized: "markdown.image.save-file", defaultValue: "存储到文件"), systemImage: "folder")
                        }
                        if let attachment = attachments.first {
                            Button { save(attachment) } label: { Label("保存到相册", systemImage: "photo") }
                                .disabled(saving)
                        }
                    } label: {
                        Image(systemName: "square.and.arrow.down").font(.system(size: 26)).foregroundStyle(.white)
                    }
                    .accessibilityLabel(Text(String(localized: "markdown.image.download", defaultValue: "下载图片")))
                    .accessibilityIdentifier("markdown-image-download")
                }
                Button { dismiss() } label: {
                    Image(systemName: "xmark.circle.fill").font(.system(size: 30)).foregroundStyle(.white)
                }
                .accessibilityLabel("关闭")
                .accessibilityIdentifier("image-gallery-close")
            }.padding(20)
        }
        .onAppear { selectedID = initialID }
        .accessibilityIdentifier("image-gallery")
        .fileExporter(isPresented: $exporting, document: exportDocument,
                      contentType: exportDocument?.contentType ?? .image, defaultFilename: exportDocument?.name) { result in
            if case .failure(let error) = result { saveMessage = error.localizedDescription }
        }
        .alert("保存图片", isPresented: Binding(get: { saveMessage != nil }, set: { if !$0 { saveMessage = nil } })) {
            Button("好", role: .cancel) { saveMessage = nil }
        } message: {
            Text(saveMessage ?? "")
        }
    }
}

struct ConversationRow: View {
    let item: ConversationItem
    let showsCopyButton: Bool
    let imageData: (String) -> Data?
    var imageError: (String) -> String? = { _ in nil }
    var onRetryImage: (GatewayImageAttachment) -> Void = { _ in }
    var onOpenFile: ((String) -> Void)? = nil
    var onOpenUploadedFile: ((PresentedFile) -> Void)? = nil
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.messageQuoteAction) private var onQuote

    var body: some View {
        switch item.kind {
        case .user:
            VStack(alignment: .trailing, spacing: 8) {
                if !item.images.isEmpty {
                    AttachmentImageGrid(attachments: item.images, imageData: imageData, imageError: imageError, onRetry: onRetryImage)
                }
                ForEach(item.files.filter { $0.attachmentId != nil }, id: \.attachmentId) { file in
                    UploadedFileCard(file: file) { onOpenUploadedFile?(file) }
                }
                if let parts = item.textParts, parts.contains(where: { $0.type == "quote" }) {
                    SentReplyBubble(textParts: parts)
                } else if !item.text.isEmpty {
                    Text(item.text).appFont(.body)
                        .padding(.horizontal, 10).padding(.vertical, 10)
                        .background(userBubbleFill, in: RoundedRectangle(cornerRadius: 15, style: .continuous))
                        .overlay {
                            RoundedRectangle(cornerRadius: 15, style: .continuous).stroke(userBubbleEdge, lineWidth: 0.7)
                        }
                }
                if showsCopyButton && !item.text.isEmpty { CopyMessageButton(text: item.text) }
            }
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
        case .assistant, .generatedImages:
            VStack(alignment: .leading, spacing: 7) {
                if !item.images.isEmpty {
                    AttachmentImageGrid(attachments: item.images, imageData: imageData, imageError: imageError, onRetry: onRetryImage)
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
                    AssistantMessageActions(text: item.text, onReply: onQuote)
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

struct AssistantMessageActions: View {
    let text: String
    var onReply: ((String) -> Void)?

    private var plainText: String {
        ParsedMarkdownCache.document(for: MarkdownMath.prepare(text, forSelection: true)).renderPlainText()
    }

    var body: some View {
        HStack(spacing: 8) {
            CopyMessageButton(text: plainText)
            if let onReply {
                Button { onReply(plainText) } label: {
                    Image(systemName: "arrowshape.turn.up.left")
                        .appFont(.system(size: 16))
                        .foregroundStyle(Color.secondary)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(String(localized: "回复"))
                .accessibilityIdentifier("message-reply")
            }
        }
        .accessibilityIdentifier("assistant-message-actions")
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
    @Environment(\.workspaceMarkdownImageContext) private var workspaceImages
    @Environment(\.messageQuoteAction) private var onQuote
    @Environment(\.colorScheme) private var colorScheme
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
                    UIPasteboard.general.string = ParsedMarkdownCache.document(for: MarkdownMath.prepare(text, forSelection: true)).renderPlainText()
                }, onSelectText: openTextSelection, onReply: onQuote == nil ? nil : replyToText)
                .accessibilityHidden(true)
            }
            .accessibilityAction(named: Text("复制")) {
                UIPasteboard.general.string = ParsedMarkdownCache.document(for: MarkdownMath.prepare(text, forSelection: true)).renderPlainText()
            }
            .accessibilityAction(named: Text("选择文字"), openTextSelection)
            .fullScreenCover(item: $selectionDocument) { document in
                MarkdownSelectionPage(document: document)
            }
    }

    private func replyToText() {
        onQuote?(ParsedMarkdownCache.document(for: MarkdownMath.prepare(text, forSelection: true)).renderPlainText())
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
            .markdownImageProvider(MathMarkdownImageProvider(fontSize: mathFontSize, workspace: workspaceImages))
            .markdownInlineImageProvider(MathMarkdownInlineImageProvider(fontSize: mathFontSize, dark: colorScheme == .dark, workspace: workspaceImages))
            .id("\(mathFontSize)-\(colorScheme)")
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
            .markdownCodeSyntaxHighlighter(DSHCodeSyntaxHighlighter(dark: colorScheme == .dark))
            // MarkdownUI 会自行应用 Dynamic Type。主题已合并系统与应用倍率，避免重复缩放。
            .dynamicTypeSize(.large)
    }

    private var mathFontSize: CGFloat {
        UIFontMetrics.default.scaledValue(for: compact ? 13 : 17)
            * CGFloat(AppFontScale.effective(fontScale, followsSystem: fontFollowsSystem))
    }

}

private struct MarkdownLongPressMenu: UIViewRepresentable {
    let onCopy: () -> Void
    let onSelectText: () -> Void
    var onReply: (() -> Void)? = nil

    func makeUIView(context: Context) -> MarkdownLongPressMenuView {
        MarkdownLongPressMenuView()
    }

    func updateUIView(_ view: MarkdownLongPressMenuView, context: Context) {
        view.onCopy = onCopy
        view.onSelectText = onSelectText
        view.onReply = onReply
    }

    static func dismantleUIView(_ view: MarkdownLongPressMenuView, coordinator: ()) {
        view.detachMenu()
        view.onCopy = nil
        view.onSelectText = nil
        view.onReply = nil
    }
}

/// 背景只提供菜单锚点，手势在窗口监听且限于正文范围，不遮挡链接与代码复制按钮。
/// 使用编辑浮层，避免 contextMenu 对整段 Markdown 生成预览、抬升和缩放。
final class MarkdownLongPressMenuView: UIView, UIGestureRecognizerDelegate, UIEditMenuInteractionDelegate {
    var onCopy: (() -> Void)?
    var onSelectText: (() -> Void)?
    var onReply: (() -> Void)?
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
        var actions: [UIMenuElement] = [
            UIAction(title: String(localized: "复制"), image: UIImage(systemName: "doc.on.doc")) { [weak self] _ in
                self?.onCopy?()
            },
            UIAction(title: String(localized: "选择文字"), image: UIImage(systemName: "selection.pin.in.out")) { [weak self] _ in
                self?.onSelectText?()
            }
        ]
        if onReply != nil {
            actions.append(UIAction(title: String(localized: "回复"), image: UIImage(systemName: "arrowshape.turn.up.left")) { [weak self] _ in
                self?.menu.dismissMenu()
                self?.onReply?()
            })
        }
        return UIMenu(children: actions)
    }
}

/// 附件先按自然尺寸测量，但可见区域与占用高度均从零一起展开。
private struct ComposerAttachmentRevealClip: Shape {
    let progress: CGFloat
    func path(in rect: CGRect) -> Path {
        Path(CGRect(x: rect.minX, y: rect.minY, width: rect.width,
                    height: rect.height * min(1, max(0, progress))))
    }
}

private struct ComposerAttachmentRevealLayout: Layout {
    let progress: CGFloat
    private func sizes(width: CGFloat, subviews: Subviews) -> (CGSize, CGSize, CGFloat) {
        let preview = subviews[0].sizeThatFits(ProposedViewSize(width: width, height: nil))
        let content = subviews[1].sizeThatFits(ProposedViewSize(width: width, height: nil))
        return (preview, content, (preview.height + 6) * min(1, max(0, progress)))
    }
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? 320
        let (_, content, offset) = sizes(width: width, subviews: subviews)
        return CGSize(width: width, height: content.height + offset)
    }
    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let (preview, content, offset) = sizes(width: bounds.width, subviews: subviews)
        subviews[0].place(at: bounds.origin, anchor: .topLeading,
            proposal: ProposedViewSize(width: bounds.width, height: preview.height))
        subviews[1].place(at: CGPoint(x: bounds.minX, y: bounds.minY + offset), anchor: .topLeading,
            proposal: ProposedViewSize(width: bounds.width, height: content.height))
    }
}

private final class ComposerAttachmentPreviewCache: ObservableObject {
    private var last = AnyView(EmptyView())
    private(set) var hasSnapshot = false
    func resolve(_ current: AnyView, hasContent: Bool) -> AnyView {
        if hasContent { last = current; hasSnapshot = true }
        return last
    }
    func clear() { last = AnyView(EmptyView()); hasSnapshot = false }
}

private struct ComposerAttachmentReveal<Content: View>: View, Animatable {
    var progress: CGFloat
    let interactive: Bool
    let preview: AnyView
    let content: Content
    var animatableData: CGFloat {
        get { progress }
        set { progress = newValue }
    }
    var body: some View {
        let clip = ComposerAttachmentRevealClip(progress: progress)
        ComposerAttachmentRevealLayout(progress: progress) {
            ZStack(alignment: .topLeading) { preview }
                .fixedSize(horizontal: false, vertical: true)
                .clipShape(clip).contentShape(clip)
                .opacity(min(1, max(0, progress)))
                .allowsHitTesting(interactive && progress > 0)
                .accessibilityHidden(!interactive)
            VStack(spacing: 0) { content }
        }
        // 本帧已包含真实占位高度和遮罩，子节点不再单独补一段布局动画。
        .transaction { $0.animation = nil }
    }
}

struct ComposerAttachmentStack<Preview: View, Content: View>: View {
    let shown: Bool
    let hasContent: Bool
    @ViewBuilder let preview: () -> Preview
    @ViewBuilder let content: () -> Content
    @StateObject private var previewCache = ComposerAttachmentPreviewCache()
    @State private var snapshotRevision = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body: some View {
        let _ = snapshotRevision
        let visible = shown && hasContent
        let snapshot = previewCache.resolve(AnyView(preview()), hasContent: hasContent)
        ComposerAttachmentReveal(progress: visible ? 1 : 0, interactive: visible, preview: snapshot, content: content())
            .animation(.easeOut(duration: reduceMotion ? 0.12 : 0.22), value: visible)
            .task(id: [visible, hasContent]) {
                guard !hasContent, previewCache.hasSnapshot else { return }
                do { try await Task.sleep(nanoseconds: reduceMotion ? 150_000_000 : 250_000_000) }
                catch { return }
                guard !Task.isCancelled else { return }
                // 只为退出动画短暂保留图片，结束后释放压缩数据和解码预览。
                previewCache.clear()
                snapshotRevision &+= 1
            }
    }
}

/// 只插值一份引用高度与间距；下方原生编辑器跟随同一帧的几何结果。
struct ComposerQuotePushModifier<Preview: View>: AnimatableModifier {
    var quoteHeight: CGFloat
    var spacing: CGFloat
    var opacity: CGFloat
    let naturalHeight: CGFloat
    let preview: Preview

    var animatableData: AnimatablePair<AnimatablePair<CGFloat, CGFloat>, CGFloat> {
        get { AnimatablePair(AnimatablePair(quoteHeight, spacing), opacity) }
        set {
            quoteHeight = newValue.first.first
            spacing = newValue.first.second
            opacity = newValue.second
        }
    }

    func body(content: Content) -> some View {
        VStack(alignment: .leading, spacing: max(0, spacing)) {
            preview
                .frame(height: naturalHeight, alignment: .top)
                .opacity(min(1, max(0, opacity)))
                .frame(height: max(0, quoteHeight), alignment: .top)
                .clipped()
                .contentShape(Rectangle())
            content
        }
        // 上面的值已经逐帧插值，子节点不能再对位移做第二次隐式动画。
        .transaction { $0.animation = nil }
    }
}

private final class ComposerQuotePreviewCache: ObservableObject {
    private var parts: [GatewayTextPart] = []
    // 缓存退出内容，不发布额外状态，避免下一次刷新重启动画时序。
    func displayParts(_ current: [GatewayTextPart]) -> [GatewayTextPart] {
        if current.contains(where: { $0.type == "quote" }) { parts = current }
        return parts
    }
}

struct ComposerQuoteStack<Content: View>: View {
    let parts: [GatewayTextPart]
    let visible: Bool
    let onRemove: (Int) -> Void
    @ViewBuilder let content: () -> Content
    @StateObject private var previewCache = ComposerQuotePreviewCache()
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @AppStorage(AppFontScale.key) private var fontScale = 1.0
    @AppStorage(AppFontScale.followsSystemKey) private var followsSystem = false

    var body: some View {
        let quotes = parts.filter { $0.type == "quote" }
        let displayed = previewCache.displayParts(parts)
        let count = displayed.filter { $0.type == "quote" }.count
        let rowHeight = composerQuoteRowHeight(fontScale, followsSystem: followsSystem)
        let columns = min(3, max(1, count))
        let rows = min(3, (count + columns - 1) / columns)
        let height = CGFloat(rows) * rowHeight + CGFloat(max(0, rows - 1)) * 8
        let shown = visible && !quotes.isEmpty
        content()
            .modifier(ComposerQuotePushModifier(
                quoteHeight: shown ? height : 0, spacing: shown ? 12 : 0,
                opacity: shown ? 1 : 0, naturalHeight: height,
                preview: ComposerReplyPreviews(parts: parts, retainForExit: true) { index in
                    guard shown, parts.indices.contains(index), parts[index].type == "quote" else { return }
                    onRemove(index)
                }
                .allowsHitTesting(shown)
                .accessibilityHidden(!shown)
            ))
            .animation(.easeOut(duration: reduceMotion ? 0.12 : 0.22), value: [shown ? height : 0, shown ? 12 : 0])
    }
}

private func composerQuoteRowHeight(_ fontScale: Double, followsSystem: Bool) -> CGFloat {
    let base = UIFont.preferredFont(forTextStyle: .body)
    let scale = CGFloat(AppFontScale.effective(fontScale, followsSystem: followsSystem))
    return max(72, ceil(base.withSize(base.pointSize * scale).lineHeight * 2 + 24))
}

/// UI 标识与协议分段位置分开；正文编辑、前项删除都不能改变存活 Cell 的身份。
final class ComposerQuoteIdentityStore: ObservableObject {
    struct Entry: Identifiable, Equatable {
        let id: UUID
        let text: String
        var partIndex: Int
    }
    private var signature: [(index: Int, text: String)] = []
    private(set) var entries: [Entry] = []
    private(set) var lastVisible: [Entry] = []
    private var removing: Set<UUID> = []

    func update(_ parts: [GatewayTextPart]) -> [Entry] {
        let next = parts.enumerated().compactMap { index, part in
            part.type == "quote" ? (index: index, text: part.text) : nil
        }
        guard next.count != signature.count || zip(next, signature).contains(where: {
            $0.index != $1.index || $0.text != $1.text
        }) else { return entries }
        var pool: [String: [Entry]] = [:]
        for entry in entries where !removing.contains(entry.id) { pool[entry.text, default: []].append(entry) }
        entries = next.map { input in
            if var matches = pool[input.text], !matches.isEmpty {
                var entry = matches.removeFirst()
                pool[input.text] = matches
                entry.partIndex = input.index
                return entry
            }
            return Entry(id: UUID(), text: input.text, partIndex: input.index)
        }
        signature = next
        removing.removeAll()
        if !entries.isEmpty { lastVisible = entries }
        return entries
    }

    func remove(_ id: UUID, from parts: [GatewayTextPart]) -> Int? {
        guard let entry = entries.first(where: { $0.id == id }),
              parts.indices.contains(entry.partIndex), parts[entry.partIndex].type == "quote",
              parts[entry.partIndex].text == entry.text else { return nil }
        removing.insert(id)
        return entry.partIndex
    }
}

/// 整个 Cell 只插值一份矩形，叉号在当前矩形内布局，不再独立补做位移动画。
struct ComposerQuoteCellFrame: AnimatableModifier {
    var rect: CGRect
    var animatableData: AnimatablePair<AnimatablePair<CGFloat, CGFloat>, AnimatablePair<CGFloat, CGFloat>> {
        get { AnimatablePair(AnimatablePair(rect.minX, rect.minY), AnimatablePair(rect.width, rect.height)) }
        set { rect = CGRect(x: newValue.first.first, y: newValue.first.second,
                            width: newValue.second.first, height: newValue.second.second) }
    }
    func body(content: Content) -> some View {
        content.frame(width: max(0, rect.width), height: max(0, rect.height))
            .offset(x: rect.minX, y: rect.minY)
            .transaction { $0.animation = nil }
    }
}

/// 滚动视口和内容高度也使用实时插值，避免换行减少时提前裁掉保留的 Cell。
private struct ComposerQuoteHeightFrame: AnimatableModifier {
    var height: CGFloat
    var animatableData: CGFloat {
        get { height }
        set { height = newValue }
    }
    func body(content: Content) -> some View {
        content.frame(height: max(0, height), alignment: .top).transaction { $0.animation = nil }
    }
}

/// 引用最多占三行，之后只滚动网格，避免持续挤占会话可视区域。
struct ComposerReplyPreviews: View {
    let parts: [GatewayTextPart]
    var retainForExit = false
    let onRemove: (Int) -> Void
    @AppStorage(AppFontScale.key) private var fontScale = 1.0
    @AppStorage(AppFontScale.followsSystemKey) private var followsSystem = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @StateObject private var identities = ComposerQuoteIdentityStore()
    @State private var previousCount = 0

    private var rowHeight: CGFloat {
        composerQuoteRowHeight(fontScale, followsSystem: followsSystem)
    }
    var body: some View {
        let current = identities.update(parts)
        let quotes = current.isEmpty && retainForExit ? identities.lastVisible : current
        let columns = min(3, max(1, quotes.count))
        let rows = (quotes.count + columns - 1) / columns
        let visibleRows = min(3, rows)
        let contentHeight = rowHeight * CGFloat(rows) + 8 * CGFloat(max(0, rows - 1))
        if visibleRows > 0 {
            ScrollViewReader { proxy in
                ScrollView(.vertical, showsIndicators: rows > 3) {
                    GeometryReader { geometry in
                        let width = max(0, (geometry.size.width - CGFloat(columns - 1) * 8) / CGFloat(columns))
                        ZStack(alignment: .topLeading) {
                            ForEach(Array(quotes.enumerated()), id: \.element.id) { slot, quote in
                                let rect = CGRect(x: CGFloat(slot % columns) * (width + 8),
                                    y: CGFloat(slot / columns) * (rowHeight + 8), width: width, height: rowHeight)
                                ComposerReplyPreview(text: quote.text, compact: columns > 1, height: rowHeight) {
                                    if let index = identities.remove(quote.id, from: parts) { onRemove(index) }
                                }
                                .modifier(ComposerQuoteCellFrame(rect: rect))
                                .animation(.easeOut(duration: reduceMotion ? 0.12 : 0.22), value: rect)
                                // 删除时整个节点一起移除，绝不能把旧叉号过渡到另一条引用。
                                .transition(.identity)
                                .id(quote.id)
                            }
                        }
                        .frame(width: geometry.size.width, height: geometry.size.height, alignment: .topLeading)
                    }
                    .modifier(ComposerQuoteHeightFrame(height: contentHeight))
                    .animation(.easeOut(duration: reduceMotion ? 0.12 : 0.22), value: contentHeight)
                }
                .modifier(ComposerQuoteHeightFrame(height: rowHeight * CGFloat(visibleRows) + 8 * CGFloat(visibleRows - 1)))
                .animation(.easeOut(duration: reduceMotion ? 0.12 : 0.22), value: visibleRows)
                .scrollDisabled(rows <= 3)
                .scrollDismissesKeyboard(.never)
                .accessibilityIdentifier("composer-reply-grid")
                .onAppear {
                    previousCount = quotes.count
                    if rows > 3, let last = quotes.last { proxy.scrollTo(last.id, anchor: .bottom) }
                }
                .onChangeCompat(of: quotes.count) { _, count in
                    if count > previousCount && rows > 3, let last = quotes.last {
                        withAnimation(.easeOut(duration: 0.22)) { proxy.scrollTo(last.id, anchor: .bottom) }
                    }
                    previousCount = count
                }
            }
        }
    }
}

private struct ComposerReplyPreview: View {
    let text: String
    var compact = false
    var height: CGFloat? = nil
    var onRemove: () -> Void
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Text(text).appFont(compact ? .system(size: 14) : .body).lineLimit(2).truncationMode(.tail)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.trailing, compact ? 28 : 32).padding(compact ? 10 : 12)
                .frame(height: height)
            Button(action: onRemove) {
                Image(systemName: "xmark").appFont(.system(size: 11, weight: .medium))
                    .foregroundStyle(.primary).frame(width: 24, height: 24)
                    .background(colorScheme == .dark ? Color(white: 0.25) : .white, in: Circle())
                    .frame(width: 44, height: 44).contentShape(Rectangle())
            }
            .buttonStyle(.plain).accessibilityLabel("移除引用")
        }
        .background(colorScheme == .dark ? Color(white: 0.16) : Color(white: 0.945),
            in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .accessibilityIdentifier("composer-reply-preview")
    }
}

/// 引用与正文共用外层消息气泡，按两者中较宽的一项确定宽度。
struct SentReplyBubble: View {
    let textParts: [GatewayTextPart]
    @Environment(\.colorScheme) private var colorScheme

    private var bodyText: String {
        textParts.filter { $0.type == "text" }.map(\.text).joined().trimmingCharacters(in: .whitespacesAndNewlines)
    }

    var body: some View {
        SentReplyContentLayout {
            ForEach(Array(textParts.filter { $0.type == "quote" }.enumerated()), id: \.offset) { _, part in
                SentMessageQuote(text: part.text)
            }
            if !bodyText.isEmpty {
                Text(bodyText).appFont(.body).fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("sent-reply-body")
            }
        }
        .padding(10)
        .foregroundStyle(Color.primary)
        .background(Color("SentReplyBubbleBackground"),
            in: RoundedRectangle(cornerRadius: 15, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 15, style: .continuous)
                .stroke(Color("SentReplyBubbleBorder"), lineWidth: 0.7)
        }
        .accessibilityIdentifier("sent-reply-bubble")
    }
}

private struct SentMessageQuote: View {
    let text: String
    @State private var expanded = false
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        Text(text).appFont(.subheadline).foregroundStyle(Color.primary.opacity(colorScheme == .dark ? 0.88 : 0.78))
            .lineLimit(expanded ? nil : 2).truncationMode(.tail)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 10).padding(.vertical, 8)
            .background(Color("SentReplyQuoteBackground"),
                in: RoundedRectangle(cornerRadius: 9, style: .continuous))
            .onTapGesture { expanded.toggle() }
            .accessibilityAddTraits(.isButton)
            .accessibilityHint(expanded ? String(localized: "收起引用") : String(localized: "展开引用"))
    }
}

/// 限制总宽度为 360 点，同时让短引用和短回复自然收窄。
private struct SentReplyContentLayout: Layout {
    private let spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maximumWidth = max(1, min(340, proposal.width ?? 340))
        let naturalWidth = subviews.map { $0.sizeThatFits(.unspecified).width }.max() ?? 0
        let width = min(maximumWidth, max(1, naturalWidth))
        let height = subviews.reduce(CGFloat.zero) { $0 + $1.sizeThatFits(.init(width: width, height: nil)).height }
        return CGSize(width: width, height: height + spacing * CGFloat(max(0, subviews.count - 1)))
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        let childProposal = ProposedViewSize(width: bounds.width, height: nil)
        for subview in subviews {
            subview.place(at: CGPoint(x: bounds.minX, y: y), anchor: .topLeading, proposal: childProposal)
            y += subview.sizeThatFits(childProposal).height + spacing
        }
    }
}

struct MarkdownSelectionDocument: Identifiable {
    let id = UUID()
    let text: String
    let fontSize: CGFloat
}

private struct MessageQuoteActionKey: EnvironmentKey {
    static let defaultValue: ((String) -> Void)? = nil
}
private extension EnvironmentValues {
    var messageQuoteAction: ((String) -> Void)? {
        get { self[MessageQuoteActionKey.self] }
        set { self[MessageQuoteActionKey.self] = newValue }
    }
}

struct MarkdownSelectionPage: View {
    @Environment(\.dismiss) private var dismiss
    let document: MarkdownSelectionDocument

    var body: some View {
        VStack(spacing: 0) {
            DocumentPageHeader(title: LocalizedStringKey("选择文字"), closeIdentifier: "markdown-selection-close", onClose: { dismiss() }) {
                EmptyView()
            }
            let parsed = ParsedMarkdownCache.document(for: MarkdownMath.prepare(document.text, forSelection: true))
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
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.messageQuoteAction) private var onQuote
    @Environment(\.dismiss) private var dismiss
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
        view.onQuote = onQuote.map { action in { selected in action(selected); dismiss() } }
        let highlighter = DSHCodeSyntaxHighlighter(dark: colorScheme == .dark)
        let value = document.renderSelectableText(font: font, codeHighlighter: highlighter.attributedCode)
        if !view.attributedText.isEqual(to: value) { view.attributedText = value }
    }
}

final class MarkdownSelectionTextView: UITextView, UITextViewDelegate, UIEditMenuInteractionDelegate {
    var onQuote: ((String) -> Void)?
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

    private func selectedPlainText() -> String {
        let range = selectedRange
        guard range.location != NSNotFound, range.length > 0, NSMaxRange(range) <= attributedText.length else { return "" }
        var selected = ""
        attributedText.enumerateAttribute(SelectableCodeBlockStyle.decoration, in: range) { decoration, part, _ in
            if decoration == nil { selected += (attributedText.string as NSString).substring(with: part) }
        }
        selectionMenu.dismissMenu()
        return selected.replacingOccurrences(of: "\u{2028}", with: "\n")
    }

    override func copy(_ sender: Any?) { UIPasteboard.general.string = selectedPlainText() }

    func textView(_ textView: UITextView, editMenuForTextIn range: NSRange,
                  suggestedActions: [UIMenuElement]) -> UIMenu? {
        selectionActions()
    }

    func editMenuInteraction(_ interaction: UIEditMenuInteraction, menuFor configuration: UIEditMenuConfiguration,
                             suggestedActions: [UIMenuElement]) -> UIMenu? {
        selectionActions()
    }

    private func selectionActions() -> UIMenu {
        var actions: [UIMenuElement] = [
            UIAction(title: String(localized: "复制"), image: UIImage(systemName: "doc.on.doc")) { [weak self] _ in self?.copy(nil) },
            UIAction(title: String(localized: "全选")) { [weak self] _ in self?.selectAll(nil) }
        ]
        if onQuote != nil {
            actions.append(UIAction(title: String(localized: "引用"), image: UIImage(systemName: "text.quote")) { [weak self] _ in
                guard let self else { return }
                self.onQuote?(self.selectedPlainText())
            })
        }
        return UIMenu(children: actions)
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
            let math = MarkdownMath.prepare(source)
            let padded = filePaths.isEmpty ? InlineCodePadding.apply(to: math) : math
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
                if !compact, let link = configuration.content.standaloneLink,
                   let destination = AppleMapsCardDestination(link: link.destination, title: link.title) {
                    AppleMapsAppCard(destination: destination)
                        .markdownMargin(top: 2, bottom: 10)
                } else if !compact, let link = configuration.content.standaloneLink,
                          let destination = AppleMusicCardDestination(link: link.destination, title: link.title) {
                    AppleMusicAppCard(destination: destination)
                        .markdownMargin(top: 2, bottom: 10)
                } else {
                    configuration.label
                        .fixedSize(horizontal: false, vertical: true)
                        .relativeLineSpacing(.em(0.22))
                        .markdownMargin(top: 0, bottom: compact ? 6 : 10)
                }
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
        button.accessibilityLabel = String(localized: "查看智能体团队")
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
            let root = TeamMemberPopover(members: members, title: title ?? String(localized: "Lead 会话"), selectedID: selectedID,
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
                    if let error = store.teamSnapshot.error { Text(error.localized).foregroundStyle(.orange).appFont(.callout) }
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
                action("arrow.clockwise", label: LocalizedStringKey("重试上传"), tint: .primary.opacity(0.6), onRetry)
            }
            action("xmark.circle.fill", label: LocalizedStringKey("移除文件"), tint: .primary, onRemove)
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

/// 文件表示的临时 URL 仅在 provider 回调内有效，先复制到私有目录再交给草稿。
enum ComposerPasteLoader {
    static func isAttachment(_ provider: NSItemProvider) -> Bool {
        if provider.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) ||
            provider.hasItemConformingToTypeIdentifier(UTType.image.identifier) { return true }
        let dataTypes = provider.registeredTypeIdentifiers.filter {
            guard let type = UTType($0) else { return false }
            return type.conforms(to: .data) && !type.conforms(to: .url)
        }
        // .log/.txt 等文件可能只公布 public.text；不能仅因可读成文字就丢弃文件身份。
        if dataTypes.contains(where: {
            provider.hasRepresentationConforming(toTypeIdentifier: $0, fileOptions: .openInPlace)
        }) { return true }
        if let name = provider.suggestedName, !(name as NSString).pathExtension.isEmpty,
           !dataTypes.isEmpty { return true }
        return !provider.hasItemConformingToTypeIdentifier(UTType.text.identifier) && !dataTypes.isEmpty
    }

    static func copy(_ provider: NSItemProvider, to directory: URL, index: Int) async throws -> (URL, Bool) {
        let imageType = provider.registeredTypeIdentifiers.first { UTType($0)?.conforms(to: .image) == true }
        let type = imageType ?? provider.registeredTypeIdentifiers.first { UTType($0)?.conforms(to: .data) == true }
            ?? UTType.data.identifier
        return try await withCheckedThrowingContinuation { continuation in
            func save(_ source: URL, isFileURL: Bool) {
                do {
                    guard source.isFileURL else { throw CocoaError(.fileReadUnsupportedScheme) }
                    let access = source.startAccessingSecurityScopedResource()
                    defer { if access { source.stopAccessingSecurityScopedResource() } }
                    let size = try source.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey])
                    guard size.isRegularFile == true, (size.fileSize ?? 0) <= 100 * 1024 * 1024 else {
                        throw CocoaError(.fileReadTooLarge)
                    }
                    var name = (provider.suggestedName as NSString?)?.lastPathComponent ?? source.lastPathComponent
                    if name.isEmpty || name == "." || name == ".." { name = "attachment" }
                    if (name as NSString).pathExtension.isEmpty, let suffix = UTType(type)?.preferredFilenameExtension {
                        name += "." + suffix
                    }
                    let itemDirectory = directory.appendingPathComponent(String(index), isDirectory: true)
                    try FileManager.default.createDirectory(at: itemDirectory, withIntermediateDirectories: true)
                    let destination = itemDirectory.appendingPathComponent(name)
                    try FileManager.default.copyItem(at: source, to: destination)
                    let image = isFileURL ? UTType(filenameExtension: source.pathExtension)?.conforms(to: .image) == true : imageType != nil
                    continuation.resume(returning: (destination, image))
                } catch { continuation.resume(throwing: error) }
            }
            if provider.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) {
                provider.loadItem(forTypeIdentifier: UTType.fileURL.identifier, options: nil) { item, error in
                    if let error { continuation.resume(throwing: error) }
                    else if let url = item as? URL { save(url, isFileURL: true) }
                    else { continuation.resume(throwing: CocoaError(.fileReadUnknown)) }
                }
            } else {
                provider.loadFileRepresentation(forTypeIdentifier: type) { url, error in
                    if let error { continuation.resume(throwing: error) }
                    else if let url { save(url, isFileURL: false) }
                    else { continuation.resume(throwing: CocoaError(.fileReadUnknown)) }
                }
            }
        }
    }
}
