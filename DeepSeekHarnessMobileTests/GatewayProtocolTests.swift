import XCTest
import UIKit
import SwiftUI
import ChatLayout
import ImageIO
import UniformTypeIdentifiers
import class DeepSeekHarnessShared.SharedQuestionResult
import class DeepSeekHarnessShared.SharedMviEvent
import class DeepSeekHarnessShared.SharedMviDispatchResult
import class DeepSeekHarnessShared.SharedSessionControlResult
import class DeepSeekHarnessShared.SharedSessionControlStore
import class DeepSeekHarnessShared.SharedSessionListResult
import class DeepSeekHarnessShared.KotlinLong
import class DeepSeekHarnessShared.KotlinInt
import class DeepSeekHarnessShared.KotlinDouble
import class DeepSeekHarnessShared.WorkspaceCodePreviewSupport
@testable import DeepSeekHarnessMobile

private enum GatewayProtocolParityFixtures {
    // 这些样本与 shared/commonTest/GatewayProtocolFixtures.kt 逐字保持一致。
    static let liveEventWithoutKind = #"{"sessionId":"s1","seq":7,"time":1001,"event":{"type":"assistant/chunk","turn":1,"step":1,"chunkType":"reasoning-delta","text":"thinking"}}"#
    static let replayedQuestionRequest = #"{"kind":"question-requested","rpcId":"rpc-1","sessionId":"s1","replay":true,"questions":[{"id":"direction","header":"研究方向","question":"你想研究哪个方向？","detail":"请选择最感兴趣的方向","options":[{"label":"核心架构 (推荐)","description":"了解插件分层"},{"label":"移动端"}],"multiSelect":true},{"id":"notes","question":"还有什么要求？","multiSelect":false}]}"#
    static let replayedApprovalRequest = #"{"kind":"approval-requested","rpcId":"rpc-approval-1","sessionId":"s1","approvalId":"approval-1","toolName":"Bash","callId":"call-1","reason":"需要读取系统版本","replay":true}"#
    static let imageAttachment = #"{"kind":"attachment","sessionId":"s1","attachment":{"attachmentId":"att-1","mediaType":"image/png","bytes":8,"width":1,"height":1},"data":"iVBORw0K"}"#
    static let historyImage = #"{"kind":"history","events":[{"type":"user/message","seq":1,"time":1786937352,"data":{"content":[{"type":"image","attachment":{"attachmentId":"att-history","mediaType":"image/webp","bytes":42,"width":100,"height":80,"name":"image.webp"}}],"source":{"kind":"user"}}}],"hasMore":false}"#
}

private func swiftAuditRange(
    from startMarker: String,
    through endMarker: String,
    in source: NSString
) throws -> NSRange {
    let startRange = source.range(of: startMarker)
    guard startRange.location != NSNotFound else {
        throw NSError(
            domain: "Stage9SourceAudit",
            code: 1,
            userInfo: [NSLocalizedDescriptionKey: "找不到源码审计起始标记：\(startMarker)"]
        )
    }
    let searchRange = NSRange(
        location: NSMaxRange(startRange),
        length: source.length - NSMaxRange(startRange)
    )
    let endRange = source.range(of: endMarker, options: [], range: searchRange)
    guard endRange.location != NSNotFound else {
        throw NSError(
            domain: "Stage9SourceAudit",
            code: 2,
            userInfo: [NSLocalizedDescriptionKey: "找不到源码审计结束标记：\(endMarker)"]
        )
    }
    return NSRange(
        location: startRange.location,
        length: NSMaxRange(endRange) - startRange.location
    )
}

private func stage9WriteMatches(for property: String, in source: String) throws -> [NSTextCheckingResult] {
    let escapedProperty = NSRegularExpression.escapedPattern(for: property)
    let nestedAccess = "(?:(?:\\s*\\[[^\\]\\n]+\\])|(?:\\s*[?!]?\\.\\s*[A-Za-z_][A-Za-z0-9_]*))*"
    let writePatterns = [
        "(?<![A-Za-z0-9_\\.])(?:self\\.)?\(escapedProperty)\(nestedAccess)\\s*(?:=|[+\\-*/%&|^]=)(?!=)",
        "&\\s*(?:self\\.)?\(escapedProperty)\(nestedAccess)(?![A-Za-z0-9_])",
        "(?<![A-Za-z0-9_\\.])(?:self\\.)?\(escapedProperty)\(nestedAccess)\\s*\\.\\s*(?:append|appendContentsOf|insert|popFirst|popLast|remove|removeAll|removeFirst|removeLast|removeSubrange|replaceSubrange|removeValue|updateValue|update|merge|swapAt|sort|reverse|shuffle|partition|reserveCapacity|formUnion|formIntersection|formSymmetricDifference|subtract|toggle|withUnsafeMutableBytes|withUnsafeMutablePointer|withUnsafeMutableBufferPointer|withContiguousMutableStorageIfAvailable)\\s*\\("
    ]
    return try writePatterns.flatMap { pattern in
        try NSRegularExpression(pattern: pattern).matches(
            in: source,
            range: NSRange(source.startIndex..., in: source)
        )
    }
}

final class SlashCommandComposerPresentationTests: XCTestCase {
    func testActiveCommandPresentationRendersOneCompleteTextLayer() {
        let hint = slashCommandComposerPresentation(
            draft: "/plan ",
            token: "/plan",
            hint: "描述你的任务以生成计划"
        )
        XCTAssertEqual(hint?.token, "/plan")
        XCTAssertEqual(hint?.argument, " ")
        XCTAssertTrue(hint?.showsHint == true)
        XCTAssertEqual(hint?.hintLeadingWhitespace, " ")

        let argument = slashCommandComposerPresentation(
            draft: "/plan 重构移动端",
            token: "/plan",
            hint: "描述你的任务以生成计划"
        )
        XCTAssertEqual(argument?.argument, " 重构移动端")
        XCTAssertFalse(argument?.showsHint == true)

        XCTAssertNil(
            slashCommandComposerPresentation(
                draft: "/planning",
                token: "/plan",
                hint: "描述你的任务以生成计划"
            )
        )
        XCTAssertNil(
            slashCommandComposerPresentation(
                draft: "/pla",
                token: "/plan",
                hint: "描述你的任务以生成计划"
            )
        )
        XCTAssertNil(
            slashCommandComposerPresentation(
                draft: "",
                token: "/plan",
                hint: "描述你的任务以生成计划"
            )
        )
    }

    @MainActor
    func testTypingKeepsTheSameTextViewAndFirstResponderAcrossMenuUpdates() async throws {
        let controller = UIHostingController(rootView: SlashCommandFocusHarness())
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        window.rootViewController = controller
        window.makeKeyAndVisible()
        defer { window.isHidden = true }

        controller.view.frame = window.bounds
        controller.view.layoutIfNeeded()
        let original = try XCTUnwrap(firstDescendant(of: UITextView.self, in: controller.view))
        XCTAssertTrue(original.becomeFirstResponder())

        // 第一个字符会令命令菜单从 EmptyView 切成真实内容。这个状态更新不能
        // 替换输入控件，也不能让 SwiftUI 的旧焦点值反向结束 UIKit 编辑会话。
        original.insertText("/")
        try await Task.sleep(for: .milliseconds(80))
        controller.view.layoutIfNeeded()

        let afterFirstCharacter = try XCTUnwrap(
            firstDescendant(of: UITextView.self, in: controller.view)
        )
        XCTAssertTrue(original === afterFirstCharacter)
        XCTAssertTrue(afterFirstCharacter.isFirstResponder)

        afterFirstCharacter.insertText("p")
        try await Task.sleep(for: .milliseconds(40))
        XCTAssertEqual(afterFirstCharacter.text, "/p")
        XCTAssertTrue(afterFirstCharacter.isFirstResponder)
    }
}

private struct SlashCommandFocusHarness: View {
    @State private var text = ""
    @State private var isFocused = false

    var body: some View {
        VStack {
            if text.hasPrefix("/") {
                Text("命令")
                    .frame(height: 120)
            }
            SlashCommandTextView(
                text: $text,
                presentation: slashCommandComposerPresentation(
                    draft: text,
                    token: text.hasPrefix("/plan") ? "/plan" : nil,
                    hint: "描述你的任务以生成计划"
                ),
                placeholder: "描述你想要构建的内容",
                isFocused: $isFocused
            )
        }
        .frame(width: 390, height: 400)
    }
}

private struct ExpandingConversationCellTestView: View {
    @State private var usesResolvedHeight = false

    var body: some View {
        Text("主要产出目录： `/Users/lichaofan/测试 2/MyFirstApp/`。")
            .frame(maxWidth: .infinity, alignment: .leading)
            .frame(height: usesResolvedHeight ? 120 : 24, alignment: .top)
            .onAppear {
                DispatchQueue.main.async {
                    usesResolvedHeight = true
                }
            }
    }
}

private func firstDescendant<T: UIView>(of type: T.Type, in view: UIView) -> T? {
    if let match = view as? T { return match }
    for subview in view.subviews {
        if let match = firstDescendant(of: type, in: subview) { return match }
    }
    return nil
}

final class WorkspaceCodeTextKitPerformanceTests: XCTestCase {
    @MainActor
    func testReportSizedHtmlConfiguresWithoutEagerSwiftUILayout() throws {
        var lines = ["<!DOCTYPE html>", "<html lang=\"zh-CN\"><head><style>"]
        for index in 0..<590 {
            lines.append(
                ".report-\(index) { color: #8a8f99; margin: \(index % 24)px; " +
                    "content: \"DeepSeek 深度调研报告 \(index)\"; }"
            )
        }
        lines.append("</style></head><body><main class=\"report\">报告</main></body></html>")
        let source = lines.joined(separator: "\n")
        let document = WorkspaceCodePreviewSupport.shared.prepare(
            source: source,
            name: "deepseek_report.html",
            mediaType: "text/html"
        )
        let view = WorkspaceCodeTextKitContainer(
            frame: CGRect(x: 0, y: 0, width: 390, height: 760)
        )

        let started = CFAbsoluteTimeGetCurrent()
        view.configure(document: document, isDark: false)
        view.layoutIfNeeded()
        let elapsed = CFAbsoluteTimeGetCurrent() - started

        let textView = try XCTUnwrap(firstDescendant(of: UITextView.self, in: view))
        let lineNumberLabel = try XCTUnwrap(firstDescendant(of: UILabel.self, in: view))
        XCTAssertEqual(textView.attributedText.length, (source as NSString).length)
        XCTAssertFalse(textView.textContainer.widthTracksTextView)
        XCTAssertTrue(textView.isSelectable)
        let initialLineNumberY = lineNumberLabel.frame.minY
        textView.contentOffset = CGPoint(x: 80, y: 120)
        view.scrollViewDidScroll(textView)
        XCTAssertEqual(lineNumberLabel.frame.minY, initialLineNumberY - 120, accuracy: 0.5)
        XCTAssertLessThan(elapsed, 1.0, "TextKit 预览配置耗时异常：\(elapsed)s")
    }
}

final class ConversationViewportLayoutTests: XCTestCase {
    func testLongInlineCodeCanWrapAtPathSeparatorsWithoutChangingSourceText() {
        let source = "主要产出目录：`/Users/lichaofan/测试 2/My_FirstApp/`。"

        let rendered = InlineCodePadding.apply(to: source)

        XCTAssertTrue(rendered.contains("/\u{200B}"))
        XCTAssertTrue(rendered.contains("_\u{200B}"))
        XCTAssertEqual(
            rendered
                .replacingOccurrences(of: "\u{200B}", with: "")
                .replacingOccurrences(of: "\u{2009}", with: ""),
            source
        )
    }

    @MainActor
    func testLiveHostedHeightPushesFollowingCellInsteadOfClippingContent() async throws {
        let timeline = ConversationTimeline()
        let controller = ConversationViewportController(
            onContentAvailabilityChanged: { _, _ in },
            onPinnedToBottomChanged: { _ in },
            onBottomAlignmentCompleted: {},
            onApproachingTop: {}
        )
        controller.loadViewIfNeeded()
        // iPhone 17 app width (402pt) minus ConversationView's 20pt
        // horizontal padding on each side.
        controller.view.frame = CGRect(x: 0, y: 0, width: 362, height: 300)
        controller.view.layoutIfNeeded()
        let window = UIWindow(frame: controller.view.frame)
        window.rootViewController = controller
        window.makeKeyAndVisible()
        defer { window.isHidden = true }

        controller.configure(
            sessionID: "live-hosted-height",
            timeline: timeline,
            supplementalEntries: [
                ConversationViewportEntry(
                    id: "assistant",
                    revision: 1,
                    content: AnyView(ExpandingConversationCellTestView()),
                    clipsContentToBounds: true
                ),
                ConversationViewportEntry(
                    id: "process",
                    revision: 1,
                    content: AnyView(Text("耗时 9 秒 · 1 项上下文 · 1 次工具调用").frame(height: 34)),
                    clipsContentToBounds: true
                )
            ],
            makeEntries: { _ in [] },
            bottomInset: 0
        )

        let collectionView = try XCTUnwrap(
            firstDescendant(of: UICollectionView.self, in: controller.view)
        )
        for _ in 0..<100 {
            controller.view.layoutIfNeeded()
            let height = collectionView.layoutAttributesForItem(
                at: IndexPath(item: 0, section: 0)
            )?.frame.height ?? 0
            if height >= 120 { break }
            try await Task.sleep(for: .milliseconds(10))
        }

        let assistant = try XCTUnwrap(
            collectionView.layoutAttributesForItem(at: IndexPath(item: 0, section: 0))?.frame
        )
        let process = try XCTUnwrap(
            collectionView.layoutAttributesForItem(at: IndexPath(item: 1, section: 0))?.frame
        )
        XCTAssertGreaterThanOrEqual(assistant.height, 120)
        XCTAssertEqual(process.minY, assistant.maxY, accuracy: 0.5)
    }

    @MainActor
    func testViewportDisablesRecursiveAutomaticSelfSizingInvalidation() throws {
        let controller = ConversationViewportController(
            onContentAvailabilityChanged: { _, _ in },
            onPinnedToBottomChanged: { _ in },
            onBottomAlignmentCompleted: {},
            onApproachingTop: {}
        )
        controller.loadViewIfNeeded()
        controller.view.frame = CGRect(x: 0, y: 0, width: 390, height: 760)
        controller.view.layoutIfNeeded()

        let collectionView = try XCTUnwrap(
            firstDescendant(of: UICollectionView.self, in: controller.view)
        )
        let layout = try XCTUnwrap(
            collectionView.collectionViewLayout as? CollectionViewChatLayout
        )
        if #available(iOS 16.0, *) {
            XCTAssertEqual(collectionView.selfSizingInvalidation, .disabled)
            XCTAssertFalse(layout.supportSelfSizingInvalidation)
        }
    }

    @MainActor
    func testStreamingHeightEstimateDoesNotClipTextKitContent() {
        let payload = ConversationViewportEntry.StreamingAssistant(
            title: "DeepSeek · 正在生成",
            text: """
            第一段正在生成的中文内容，用于验证自动换行后的真实高度。
            第二段继续增长，并包含 a/very/long/path/with/separators/file.txt。
            第三段用于覆盖多行 TextKit 的字体行高。
            """
        )
        let width: CGFloat = 390
        let cell = StreamingAssistantCell(frame: CGRect(x: 0, y: 0, width: width, height: 80))
        cell.contentView.bounds.size.width = width
        cell.apply(payload)
        cell.contentView.setNeedsLayout()
        cell.contentView.layoutIfNeeded()

        let measured = cell.contentView.systemLayoutSizeFitting(
            CGSize(width: width, height: UIView.layoutFittingCompressedSize.height),
            withHorizontalFittingPriority: .required,
            verticalFittingPriority: .fittingSizeLevel
        ).height
        let estimated = StreamingAssistantCell.estimatedHeight(for: payload, width: width)

        XCTAssertGreaterThanOrEqual(estimated + 1, measured)
    }

    @MainActor
    func testStreamingChunksGrowVisibleCellBeforeFinalMarkdownReplacement() async throws {
        let timeline = ConversationTimeline()
        let controller = ConversationViewportController(
            onContentAvailabilityChanged: { _, _ in },
            onPinnedToBottomChanged: { _ in },
            onBottomAlignmentCompleted: {},
            onApproachingTop: {}
        )
        controller.loadViewIfNeeded()
        controller.view.frame = CGRect(x: 0, y: 0, width: 362, height: 300)
        controller.view.layoutIfNeeded()
        let window = UIWindow(frame: controller.view.frame)
        window.rootViewController = controller
        window.makeKeyAndVisible()
        defer { window.isHidden = true }

        controller.configure(
            sessionID: "streaming-chunk-growth",
            timeline: timeline,
            supplementalEntries: [],
            makeEntries: { items in
                items.map { item in
                    ConversationViewportEntry(
                        id: item.id,
                        revision: item.text.count,
                        streamingAssistant: .init(title: item.title, text: item.text)
                    )
                }
            },
            bottomInset: 0
        )

        func item(_ text: String) -> ConversationItem {
            ConversationItem(
                id: "streaming-response",
                kind: .assistant,
                title: "DeepSeek · 正在生成",
                text: text,
                isError: false,
                date: Date(timeIntervalSince1970: 1)
            )
        }

        let firstChunk = "好嘞，先输出第一行。"
        timeline.publish([item(firstChunk)])
        let collectionView = try XCTUnwrap(
            firstDescendant(of: UICollectionView.self, in: controller.view)
        )
        for _ in 0..<100 {
            controller.view.layoutIfNeeded()
            if let cell = collectionView.cellForItem(
                at: IndexPath(item: 0, section: 0)
            ) as? StreamingAssistantCell,
               cell.renderedCharacterCount == firstChunk.count {
                break
            }
            try await Task.sleep(for: .milliseconds(10))
        }
        let firstHeight = try XCTUnwrap(
            collectionView.layoutAttributesForItem(at: IndexPath(item: 0, section: 0))
        ).frame.height

        let laterChunks = firstChunk + String(
            repeating: "程序员点外卖的故事仍在逐个 chunk 持续输出，界面必须同步增长。",
            count: 18
        )
        timeline.publish([item(laterChunks)])
        for _ in 0..<100 {
            controller.view.layoutIfNeeded()
            let renderedCount = (collectionView.cellForItem(
                at: IndexPath(item: 0, section: 0)
            ) as? StreamingAssistantCell)?.renderedCharacterCount ?? 0
            let height = collectionView.layoutAttributesForItem(
                at: IndexPath(item: 0, section: 0)
            )?.frame.height ?? 0
            if renderedCount == laterChunks.count, height > firstHeight + 40 {
                break
            }
            try await Task.sleep(for: .milliseconds(10))
        }

        let streamingCell = try XCTUnwrap(
            collectionView.cellForItem(at: IndexPath(item: 0, section: 0))
                as? StreamingAssistantCell
        )
        let grownHeight = try XCTUnwrap(
            collectionView.layoutAttributesForItem(at: IndexPath(item: 0, section: 0))
        ).frame.height
        XCTAssertEqual(streamingCell.renderedCharacterCount, laterChunks.count)
        XCTAssertGreaterThan(grownHeight, firstHeight + 40)
    }

    @MainActor
    func testLongStreamingReplyDoesNotRelayoutForEveryChunk() throws {
        let timeline = ConversationTimeline()
        let controller = ConversationViewportController(
            onContentAvailabilityChanged: { _, _ in },
            onPinnedToBottomChanged: { _ in },
            onBottomAlignmentCompleted: {},
            onApproachingTop: {}
        )
        controller.loadViewIfNeeded()
        controller.view.frame = CGRect(x: 0, y: 0, width: 362, height: 300)
        controller.view.layoutIfNeeded()
        let window = UIWindow(frame: controller.view.frame)
        window.rootViewController = controller
        window.makeKeyAndVisible()
        defer { window.isHidden = true }

        controller.configure(
            sessionID: "streaming-layout-frequency",
            timeline: timeline,
            supplementalEntries: [],
            makeEntries: { items in
                items.map { item in
                    ConversationViewportEntry(
                        id: item.id,
                        revision: item.text.utf16.count,
                        streamingAssistant: .init(title: item.title, text: item.text)
                    )
                }
            },
            bottomInset: 0
        )

        func publish(_ text: String) {
            timeline.publish([
                ConversationItem(
                    id: "streaming-response",
                    kind: .assistant,
                    title: "DeepSeek · 正在生成",
                    text: text,
                    isError: false,
                    date: Date(timeIntervalSince1970: 1)
                )
            ])
        }

        var text = ""
        for _ in 0..<300 {
            text.append("abcdefghij")
            publish(text)
        }

        let collectionView = try XCTUnwrap(
            firstDescendant(of: UICollectionView.self, in: controller.view)
        )
        let cell = try XCTUnwrap(
            collectionView.cellForItem(at: IndexPath(item: 0, section: 0))
                as? StreamingAssistantCell
        )
        XCTAssertEqual(cell.renderedCharacterCount, text.count)
        XCTAssertLessThan(
            controller.streamingLayoutInvalidationCount,
            120,
            "流式布局应按实际新增行更新，不能重新退化为每个 chunk 一次"
        )
    }

    @MainActor
    func testRapidStreamingStructureUpdatesKeepExactNonOverlappingFrames() async throws {
        let timeline = ConversationTimeline()
        let controller = ConversationViewportController(
            onContentAvailabilityChanged: { _, _ in },
            onPinnedToBottomChanged: { _ in },
            onBottomAlignmentCompleted: {},
            onApproachingTop: {}
        )
        controller.loadViewIfNeeded()
        controller.view.frame = CGRect(x: 0, y: 0, width: 390, height: 420)
        controller.view.layoutIfNeeded()

        func makeEntries(_ items: [ConversationItem]) -> [ConversationViewportEntry] {
            items.map { item in
                ConversationViewportEntry(
                    id: item.id,
                    revision: item.text.count,
                    streamingAssistant: .init(title: item.title, text: item.text)
                )
            }
        }

        for revision in 1...24 {
            let processRows = (0..<min(revision, 6)).map { index in
                ConversationViewportEntry(
                    id: "process-\(index)",
                    revision: revision,
                    content: AnyView(
                        Text("耗时 \(revision) 秒 · \(index + 1) 次工具调用")
                            .frame(height: 34)
                    ),
                    clipsContentToBounds: true
                )
            }
            controller.configure(
                sessionID: "rapid-layout-updates",
                timeline: timeline,
                supplementalEntries: processRows,
                makeEntries: makeEntries,
                bottomInset: 0
            )
            timeline.publish([
                ConversationItem(
                    id: "streaming-response",
                    kind: .assistant,
                    title: "DeepSeek · 正在生成",
                    text: String(repeating: "第\(revision)轮内容 ", count: revision),
                    isError: false,
                    date: Date(timeIntervalSince1970: 1)
                )
            ])
            try await Task.sleep(for: .milliseconds(4))
        }

        let collectionView = try XCTUnwrap(
            firstDescendant(of: UICollectionView.self, in: controller.view)
        )
        for _ in 0..<100 where collectionView.numberOfItems(inSection: 0) != 7 {
            try await Task.sleep(for: .milliseconds(10))
            controller.view.layoutIfNeeded()
        }

        XCTAssertEqual(collectionView.numberOfItems(inSection: 0), 7)
        let frames = try (0..<7).map { item in
            try XCTUnwrap(
                collectionView.layoutAttributesForItem(
                    at: IndexPath(item: item, section: 0)
                )?.frame
            )
        }
        for (previous, next) in zip(frames, frames.dropFirst()) {
            XCTAssertEqual(next.minY, previous.maxY, accuracy: 1)
        }
    }

    @MainActor
    func testHostedRowsKeepStableBoundariesWhileWidthAndScrollPositionChange() async throws {
        let timeline = ConversationTimeline()
        let controller = ConversationViewportController(
            onContentAvailabilityChanged: { _, _ in },
            onPinnedToBottomChanged: { _ in },
            onBottomAlignmentCompleted: {},
            onApproachingTop: {}
        )
        controller.loadViewIfNeeded()
        controller.view.frame = CGRect(x: 0, y: 0, width: 390, height: 360)
        controller.view.layoutIfNeeded()

        let markdown = """
        Agent 输出必须按照当前列表宽度稳定换行。拖动聊天列表或改变容器宽度时，
        前一行的高度不能重新退回估算值，也不能让后面的思考过程覆盖正文。

        ## 验证内容

        - 第一段用于制造多行布局。
        - 第二段包含 `active/armed/complete` 等行内代码。
        - 第三段继续增加高度，确保测试过程能够实际滚动。
        """
        let entries: [ConversationViewportEntry] = (0..<4).flatMap { index in
            let response = ConversationItem(
                id: "assistant-\(index)",
                kind: .assistant,
                title: "DeepSeek",
                text: markdown,
                isError: false,
                date: Date(timeIntervalSince1970: TimeInterval(index))
            )
            return [
                ConversationViewportEntry(
                    id: "process-\(index)",
                    revision: 0,
                    content: AnyView(Text("思考过程 · 1 次工具调用").frame(height: 34)),
                    clipsContentToBounds: true
                ),
                ConversationViewportEntry(
                    id: "assistant-\(index)",
                    revision: 0,
                    content: AnyView(ConversationRow(
                        item: response,
                        showsCopyButton: false,
                        imageData: { _ in nil }
                    )),
                    clipsContentToBounds: true
                )
            ]
        }
        controller.configure(
            sessionID: "bounds-and-scroll-stability",
            timeline: timeline,
            supplementalEntries: entries,
            makeEntries: { _ in [] },
            bottomInset: 0
        )
        try await Task.sleep(for: .milliseconds(200))

        let collectionView = try XCTUnwrap(
            firstDescendant(of: UICollectionView.self, in: controller.view)
        )
        controller.view.frame.size.width = 326
        controller.view.setNeedsLayout()
        controller.view.layoutIfNeeded()
        collectionView.collectionViewLayout.invalidateLayout()
        collectionView.layoutIfNeeded()

        let frames = try (0..<entries.count).map { item in
            try XCTUnwrap(
                collectionView.layoutAttributesForItem(
                    at: IndexPath(item: item, section: 0)
                )?.frame
            )
        }
        for (previous, next) in zip(frames, frames.dropFirst()) {
            XCTAssertEqual(next.minY, previous.maxY, accuracy: 1)
        }

        let maximumOffset = max(0, collectionView.contentSize.height - collectionView.bounds.height)
        for progress in stride(from: CGFloat.zero, through: 1, by: 0.1) {
            collectionView.contentOffset.y = maximumOffset * progress
            collectionView.layoutIfNeeded()
            for cell in collectionView.visibleCells {
                let hostedView = try XCTUnwrap(cell.contentView.subviews.first)
                XCTAssertEqual(hostedView.frame, cell.contentView.bounds)
                XCTAssertEqual(hostedView.bounds.origin, .zero)
                let idealHeight = hostedView.sizeThatFits(
                    CGSize(width: cell.contentView.bounds.width, height: .greatestFiniteMagnitude)
                ).height
                XCTAssertGreaterThanOrEqual(cell.bounds.height + 1, idealHeight)
            }
        }
    }

    @MainActor
    func testStreamingTextPublishesLatestBufferedTextAfterUserScrollingStops() async throws {
        let timeline = ConversationTimeline()
        let controller = ConversationViewportController(
            onContentAvailabilityChanged: { _, _ in },
            onPinnedToBottomChanged: { _ in },
            onBottomAlignmentCompleted: {},
            onApproachingTop: {}
        )
        controller.loadViewIfNeeded()
        controller.view.frame = CGRect(x: 0, y: 0, width: 390, height: 760)
        controller.view.layoutIfNeeded()
        controller.configure(
            sessionID: "streaming-scroll-pause",
            timeline: timeline,
            supplementalEntries: [],
            makeEntries: { items in
                items.map { item in
                    ConversationViewportEntry(
                        id: item.id,
                        revision: item.text.count,
                        streamingAssistant: .init(title: item.title, text: item.text)
                    )
                }
            },
            bottomInset: 0
        )

        let initialText = "第一段"
        timeline.publish([
            ConversationItem(
                id: "streaming-response",
                kind: .assistant,
                title: "DeepSeek · 正在生成",
                text: initialText,
                isError: false,
                date: Date(timeIntervalSince1970: 1)
            )
        ])
        var initialCell: StreamingAssistantCell?
        for _ in 0..<200 where initialCell == nil {
            controller.view.layoutIfNeeded()
            if let collectionView = firstDescendant(
                of: UICollectionView.self,
                in: controller.view
            ), let cell = collectionView.cellForItem(
                at: IndexPath(item: 0, section: 0)
            ) as? StreamingAssistantCell,
               cell.renderedCharacterCount == initialText.count {
                initialCell = cell
            } else {
                try await Task.sleep(for: .milliseconds(10))
            }
        }

        let collectionView = try XCTUnwrap(
            firstDescendant(of: UICollectionView.self, in: controller.view)
        )
        let cell = try XCTUnwrap(initialCell)
        let intermediateText = "第一段，滑动期间收到第二段"
        let latestText = "第一段，滑动期间收到第二段，并继续累计到最终一段"
        controller.scrollViewWillBeginDragging(collectionView)
        timeline.publish([
            ConversationItem(
                id: "streaming-response",
                kind: .assistant,
                title: "DeepSeek · 正在生成",
                text: intermediateText,
                isError: false,
                date: Date(timeIntervalSince1970: 1)
            )
        ])
        timeline.publish([
            ConversationItem(
                id: "streaming-response",
                kind: .assistant,
                title: "DeepSeek · 正在生成",
                text: latestText,
                isError: false,
                date: Date(timeIntervalSince1970: 1)
            )
        ])
        try await Task.sleep(for: .milliseconds(150))
        XCTAssertEqual(cell.renderedCharacterCount, initialText.count)

        // Finger release with remaining inertia must stay paused.
        controller.scrollViewDidEndDragging(collectionView, willDecelerate: true)
        try await Task.sleep(for: .milliseconds(100))
        XCTAssertEqual(cell.renderedCharacterCount, initialText.count)

        controller.scrollViewDidEndDecelerating(collectionView)
        for _ in 0..<200 where cell.renderedCharacterCount != latestText.count {
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertEqual(cell.renderedCharacterCount, latestText.count)
    }

    @MainActor
    func testExpandedProcessAndRealMarkdownAssistantNeverOverlap() async throws {
        let timeline = ConversationTimeline()
        let controller = ConversationViewportController(
            onContentAvailabilityChanged: { _, _ in },
            onPinnedToBottomChanged: { _ in },
            onBottomAlignmentCompleted: {},
            onApproachingTop: {}
        )
        controller.loadViewIfNeeded()
        controller.view.frame = CGRect(x: 0, y: 0, width: 390, height: 760)
        controller.view.layoutIfNeeded()

        let response = ConversationItem(
            id: "formal-response",
            kind: .assistant,
            title: "DeepSeek",
            text: """
            我把当前会话压缩成一份可持续使用的存档，并保留了完整的任务信息。

            1. 第一项包含较长的中文说明，用来验证 Markdown 段落会按真实宽度换行。
            2. 第二项继续补充内容，确保正式回复的高度明显超过布局的初始估算值。
            3. 第三项包含 `inline code`，并继续追加多行文本用于覆盖截图中的真实结构。

            ## 下一步

            这里还有一段正式回复。它必须完整位于过程节点下方，而且后续 cell 必须等它结束以后再开始布局。
            """,
            isError: false,
            date: Date(timeIntervalSince1970: 2)
        )
        let assistantView = ConversationRow(
            item: response,
            showsCopyButton: true,
            imageData: { _ in nil }
        )
        let expectedHeight = UIHostingController(rootView: assistantView).sizeThatFits(
            in: CGSize(width: 390, height: CGFloat.greatestFiniteMagnitude)
        ).height

        let collapsedEntries = [
            ConversationViewportEntry(
                id: "process-header",
                revision: 0,
                content: AnyView(Text("思考过程 · 1 次工具调用").frame(height: 34)),
                clipsContentToBounds: true
            ),
            ConversationViewportEntry(
                id: "assistant",
                revision: 0,
                content: AnyView(assistantView),
                clipsContentToBounds: true
            ),
            ConversationViewportEntry(
                id: "following",
                revision: 0,
                content: AnyView(Text("下一条消息").frame(height: 32)),
                clipsContentToBounds: true
            )
        ]
        controller.configure(
            sessionID: "process-assistant-boundary",
            timeline: timeline,
            supplementalEntries: collapsedEntries,
            makeEntries: { _ in [] },
            bottomInset: 0
        )
        try await Task.sleep(for: .milliseconds(150))
        controller.view.layoutIfNeeded()

        let collectionView = try XCTUnwrap(
            firstDescendant(of: UICollectionView.self, in: controller.view)
        )
        let collapsedHeader = try XCTUnwrap(
            collectionView.layoutAttributesForItem(at: IndexPath(item: 0, section: 0))?.frame
        )
        let collapsedAssistant = try XCTUnwrap(
            collectionView.layoutAttributesForItem(at: IndexPath(item: 1, section: 0))?.frame
        )
        let headerScreenY = collapsedHeader.minY - collectionView.contentOffset.y

        controller.prepareForDisclosureUpdate(anchorID: "process-header")
        controller.configure(
            sessionID: "process-assistant-boundary",
            timeline: timeline,
            supplementalEntries: [
                ConversationViewportEntry(
                    id: "process-header",
                    revision: 1,
                    content: AnyView(Text("思考过程 · 1 次工具调用").frame(height: 34)),
                    clipsContentToBounds: true
                ),
                ConversationViewportEntry(
                    id: "process-detail",
                    revision: 0,
                    content: AnyView(Text("compact · 已完成").frame(height: 42)),
                    clipsContentToBounds: true
                ),
                ConversationViewportEntry(
                    id: "assistant",
                    revision: 0,
                    content: AnyView(assistantView),
                    clipsContentToBounds: true
                ),
                ConversationViewportEntry(
                    id: "following",
                    revision: 0,
                    content: AnyView(Text("下一条消息").frame(height: 32)),
                    clipsContentToBounds: true
                )
            ],
            makeEntries: { _ in [] },
            bottomInset: 0
        )
        try await Task.sleep(for: .milliseconds(500))
        controller.view.layoutIfNeeded()

        let expandedHeader = try XCTUnwrap(
            collectionView.layoutAttributesForItem(at: IndexPath(item: 0, section: 0))?.frame
        )
        let processDetail = try XCTUnwrap(
            collectionView.layoutAttributesForItem(at: IndexPath(item: 1, section: 0))?.frame
        )
        let assistant = try XCTUnwrap(
            collectionView.layoutAttributesForItem(at: IndexPath(item: 2, section: 0))?.frame
        )
        let following = try XCTUnwrap(
            collectionView.layoutAttributesForItem(at: IndexPath(item: 3, section: 0))?.frame
        )

        XCTAssertEqual(expandedHeader.minY - collectionView.contentOffset.y, headerScreenY, accuracy: 1)
        XCTAssertGreaterThan(assistant.minY, collapsedAssistant.minY + 40)
        XCTAssertGreaterThanOrEqual(assistant.minY + 0.5, processDetail.maxY)
        XCTAssertGreaterThanOrEqual(assistant.height + 1, expectedHeight)
        XCTAssertGreaterThanOrEqual(following.minY + 0.5, assistant.maxY)
    }

    @MainActor
    func testFencedMarkdownKeepsExactHeightAndZeroPhantomSpacingAfterReuse() async throws {
        let timeline = ConversationTimeline()
        let controller = ConversationViewportController(
            onContentAvailabilityChanged: { _, _ in },
            onPinnedToBottomChanged: { _ in },
            onBottomAlignmentCompleted: {},
            onApproachingTop: {}
        )
        controller.loadViewIfNeeded()
        controller.view.frame = CGRect(x: 0, y: 0, width: 390, height: 280)
        controller.view.layoutIfNeeded()

        let response = ConversationItem(
            id: "markdown-with-code",
            kind: .assistant,
            title: "DeepSeek",
            text: """
            构建结果如下，代码块和后续正文都必须完整显示。

            ```yaml
            launchable-activity: com.example.myfirstapp.MainActivity
            build-type: debug
            min-sdk: 24
            compile-sdk: 36
            ```

            ## 说明与下一步

            - 包含 debug 变体。
            - 产物已经完成验证。
            """,
            isError: false,
            date: Date(timeIntervalSince1970: 1)
        )
        let responseView = ConversationRow(
            item: response,
            showsCopyButton: true,
            imageData: { _ in nil }
        )
        let expectedResponseHeight = UIHostingController(rootView: responseView).sizeThatFits(
            in: CGSize(width: 390, height: CGFloat.greatestFiniteMagnitude)
        ).height
        let entries = [
            ConversationViewportEntry(
                id: "assistant-code",
                revision: 1,
                content: AnyView(responseView),
                clipsContentToBounds: true
            ),
            ConversationViewportEntry(
                id: "process-header",
                revision: 1,
                content: AnyView(Text("思考过程 · 1 次工具调用").frame(height: 34)),
                clipsContentToBounds: true
            ),
            ConversationViewportEntry(
                id: "next-assistant",
                revision: 1,
                content: AnyView(Text("下一条 DeepSeek 回复").frame(height: 48)),
                clipsContentToBounds: true
            )
        ]
        controller.configure(
            sessionID: "fenced-markdown-reuse",
            timeline: timeline,
            supplementalEntries: entries,
            makeEntries: { _ in [] },
            bottomInset: 0
        )
        try await Task.sleep(for: .milliseconds(300))
        controller.view.layoutIfNeeded()

        let collectionView = try XCTUnwrap(
            firstDescendant(of: UICollectionView.self, in: controller.view)
        )
        collectionView.setContentOffset(.zero, animated: false)
        collectionView.layoutIfNeeded()
        collectionView.scrollToItem(
            at: IndexPath(item: 2, section: 0),
            at: .bottom,
            animated: false
        )
        collectionView.layoutIfNeeded()
        collectionView.setContentOffset(.zero, animated: false)
        collectionView.layoutIfNeeded()

        let assistant = try XCTUnwrap(
            collectionView.layoutAttributesForItem(at: IndexPath(item: 0, section: 0))?.frame
        )
        let process = try XCTUnwrap(
            collectionView.layoutAttributesForItem(at: IndexPath(item: 1, section: 0))?.frame
        )
        let nextAssistant = try XCTUnwrap(
            collectionView.layoutAttributesForItem(at: IndexPath(item: 2, section: 0))?.frame
        )

        XCTAssertGreaterThanOrEqual(assistant.height + 1, expectedResponseHeight)
        XCTAssertLessThanOrEqual(assistant.height, ceil(expectedResponseHeight) + 1)
        XCTAssertEqual(process.minY, assistant.maxY, accuracy: 1)
        XCTAssertEqual(nextAssistant.minY, process.maxY, accuracy: 1)
        for cell in collectionView.visibleCells {
            guard let hostedView = cell.contentView.subviews.first else { continue }
            XCTAssertEqual(hostedView.frame, cell.contentView.bounds)
            XCTAssertEqual(hostedView.bounds.origin, .zero)
        }
    }

    @MainActor
    func testHistorySnapshotWaitsForResolvedViewportWidth() async throws {
        let timeline = ConversationTimeline()
        let controller = ConversationViewportController(
            onContentAvailabilityChanged: { _, _ in },
            onPinnedToBottomChanged: { _ in },
            onBottomAlignmentCompleted: {},
            onApproachingTop: {}
        )
        controller.loadViewIfNeeded()
        controller.view.frame = .zero
        controller.view.layoutIfNeeded()

        let historyResponse = ConversationItem(
            id: "history-response",
            kind: .assistant,
            title: "DeepSeek",
            text: """
            APK 构建并验证成功。更新任务清单并标记目标完成。

            目标当前处于 `paused` / `disabled` 状态，自动 `complete` 被拒。
            打包任务本身已全部完成并验证。
            """,
            isError: false,
            date: Date(timeIntervalSince1970: 1)
        )
        let responseView = ConversationRow(
            item: historyResponse,
            showsCopyButton: false,
            imageData: { _ in nil }
        )
        let entries = [
            ConversationViewportEntry(
                id: "history-process",
                revision: 1,
                content: AnyView(Text("耗时 5 秒 · 3 次工具调用").frame(height: 34)),
                clipsContentToBounds: true
            ),
            ConversationViewportEntry(
                id: "history-assistant",
                revision: 1,
                content: AnyView(responseView),
                clipsContentToBounds: true
            )
        ]
        controller.configure(
            sessionID: "restored-history",
            timeline: timeline,
            supplementalEntries: entries,
            makeEntries: { _ in [] },
            bottomInset: 0
        )

        let collectionView = try XCTUnwrap(
            firstDescendant(of: UICollectionView.self, in: controller.view)
        )
        XCTAssertEqual(collectionView.numberOfSections, 0)

        controller.view.frame = CGRect(x: 0, y: 0, width: 390, height: 280)
        controller.view.setNeedsLayout()
        controller.view.layoutIfNeeded()
        try await Task.sleep(for: .milliseconds(200))
        controller.view.layoutIfNeeded()

        XCTAssertEqual(collectionView.numberOfItems(inSection: 0), 2)
        let process = try XCTUnwrap(
            collectionView.layoutAttributesForItem(at: IndexPath(item: 0, section: 0))?.frame
        )
        let assistant = try XCTUnwrap(
            collectionView.layoutAttributesForItem(at: IndexPath(item: 1, section: 0))?.frame
        )
        let expectedAssistantHeight = UIHostingController(rootView: responseView).sizeThatFits(
            in: CGSize(width: 390, height: CGFloat.greatestFiniteMagnitude)
        ).height

        XCTAssertEqual(assistant.minY, process.maxY, accuracy: 1)
        XCTAssertGreaterThanOrEqual(assistant.height + 1, expectedAssistantHeight)
    }

    @MainActor
    func testDisclosureItemsKeepHeaderStableAndPushFollowingRow() async throws {
        let timeline = ConversationTimeline()
        let controller = ConversationViewportController(
            onContentAvailabilityChanged: { _, _ in },
            onPinnedToBottomChanged: { _ in },
            onBottomAlignmentCompleted: {},
            onApproachingTop: {}
        )
        controller.loadViewIfNeeded()
        controller.view.frame = CGRect(x: 0, y: 0, width: 390, height: 300)
        controller.view.layoutIfNeeded()
        controller.configure(
            sessionID: "layout-test",
            timeline: timeline,
            supplementalEntries: [
                ConversationViewportEntry(
                    id: "header",
                    revision: 0,
                    content: AnyView(Text("Expandable header").frame(height: 32))
                ),
                ConversationViewportEntry(
                    id: "following",
                    revision: 0,
                    content: AnyView(Text("Following row").frame(height: 32))
                )
            ],
            makeEntries: { _ in [] },
            bottomInset: 0
        )
        try await Task.sleep(for: .milliseconds(50))
        controller.view.layoutIfNeeded()

        let collectionView = try XCTUnwrap(
            firstDescendant(of: UICollectionView.self, in: controller.view)
        )
        let firstIndexPath = IndexPath(item: 0, section: 0)
        let secondIndexPath = IndexPath(item: 1, section: 0)
        let oldFirst = try XCTUnwrap(
            collectionView.layoutAttributesForItem(at: firstIndexPath)?.frame
        )
        let oldSecond = try XCTUnwrap(
            collectionView.layoutAttributesForItem(at: secondIndexPath)?.frame
        )
        let oldHeaderScreenY = oldFirst.minY - collectionView.contentOffset.y

        controller.prepareForDisclosureUpdate(anchorID: "header")
        controller.configure(
            sessionID: "layout-test",
            timeline: timeline,
            supplementalEntries: [
                ConversationViewportEntry(
                    id: "header",
                    revision: 1,
                    content: AnyView(Text("Expandable header").frame(height: 32))
                ),
                ConversationViewportEntry(
                    id: "detail",
                    revision: 0,
                    content: AnyView(Color.clear.frame(height: 180))
                ),
                ConversationViewportEntry(
                    id: "following",
                    revision: 0,
                    content: AnyView(Text("Following row").frame(height: 32))
                )
            ],
            makeEntries: { _ in [] },
            bottomInset: 0
        )
        try await Task.sleep(for: .milliseconds(500))
        controller.view.layoutIfNeeded()

        let newFirst = try XCTUnwrap(
            collectionView.layoutAttributesForItem(at: firstIndexPath)?.frame
        )
        let detail = try XCTUnwrap(
            collectionView.layoutAttributesForItem(at: secondIndexPath)?.frame
        )
        let newFollowing = try XCTUnwrap(
            collectionView.layoutAttributesForItem(at: IndexPath(item: 2, section: 0))?.frame
        )
        let newHeaderScreenY = newFirst.minY - collectionView.contentOffset.y

        XCTAssertEqual(newHeaderScreenY, oldHeaderScreenY, accuracy: 1)
        XCTAssertEqual(newFirst.height, oldFirst.height, accuracy: 1)
        XCTAssertGreaterThanOrEqual(detail.minY + 0.5, newFirst.maxY)
        XCTAssertGreaterThanOrEqual(newFollowing.minY + 0.5, detail.maxY)
        XCTAssertGreaterThan(newFollowing.minY, oldSecond.minY + 170)
    }

    @MainActor
    func testCellKindTransitionUsesFinalHeightInsteadOfOldStreamingEstimate() async throws {
        let timeline = ConversationTimeline()
        let controller = ConversationViewportController(
            onContentAvailabilityChanged: { _, _ in },
            onPinnedToBottomChanged: { _ in },
            onBottomAlignmentCompleted: {},
            onApproachingTop: {}
        )
        controller.loadViewIfNeeded()
        controller.view.frame = CGRect(x: 0, y: 0, width: 390, height: 420)
        controller.view.layoutIfNeeded()
        controller.configure(
            sessionID: "cell-kind-transition",
            timeline: timeline,
            supplementalEntries: [
                ConversationViewportEntry(
                    id: "assistant",
                    revision: 0,
                    streamingAssistant: .init(title: "DeepSeek", text: "short")
                ),
                ConversationViewportEntry(
                    id: "following",
                    revision: 0,
                    content: AnyView(Text("Following row").frame(height: 32))
                )
            ],
            makeEntries: { _ in [] },
            bottomInset: 0
        )
        try await Task.sleep(for: .milliseconds(100))

        controller.configure(
            sessionID: "cell-kind-transition",
            timeline: timeline,
            supplementalEntries: [
                ConversationViewportEntry(
                    id: "assistant",
                    revision: 1,
                    content: AnyView(Color.clear.frame(height: 620)),
                    clipsContentToBounds: true
                ),
                ConversationViewportEntry(
                    id: "following",
                    revision: 0,
                    content: AnyView(Text("Following row").frame(height: 32))
                )
            ],
            makeEntries: { _ in [] },
            bottomInset: 0
        )
        try await Task.sleep(for: .milliseconds(500))
        controller.view.layoutIfNeeded()

        let collectionView = try XCTUnwrap(
            firstDescendant(of: UICollectionView.self, in: controller.view)
        )
        let assistant = try XCTUnwrap(
            collectionView.layoutAttributesForItem(at: IndexPath(item: 0, section: 0))?.frame
        )
        let following = try XCTUnwrap(
            collectionView.layoutAttributesForItem(at: IndexPath(item: 1, section: 0))?.frame
        )

        XCTAssertGreaterThanOrEqual(assistant.height, 619)
        XCTAssertGreaterThanOrEqual(following.minY + 0.5, assistant.maxY)
    }

    @MainActor
    func testLongSecondaryContextUsesBoundedScrollableViewport() {
        let item = ConversationItem(
            id: "long-context",
            kind: .context,
            title: "上下文注入",
            text: String(repeating: "A long compacted context line with markdown content. ", count: 80),
            isError: false,
            date: Date(timeIntervalSince1970: 1)
        )
        let host = UIHostingController(
            rootView: ConversationEventDetailRow(item: item, role: .context)
        )

        let size = host.sizeThatFits(
            in: CGSize(width: 390, height: CGFloat.greatestFiniteMagnitude)
        )

        XCTAssertGreaterThan(size.height, 300)
        XCTAssertLessThanOrEqual(size.height, 330)
    }
}

final class ConversationProcessProjectionTests: XCTestCase {
    func testExpansionCreatesStableHeaderAndIndependentDescendantItems() {
        let date = Date(timeIntervalSince1970: 1)
        let command = ConversationItem(
            id: "command",
            kind: .status,
            title: "goal",
            text: String(repeating: "Goal detail ", count: 12),
            isError: false,
            date: date
        )
        let context = ConversationItem(
            id: "context",
            kind: .context,
            title: "上下文注入",
            text: "context body",
            isError: false,
            date: date
        )
        let reasoning = ConversationItem(
            id: "reasoning",
            kind: .reasoning,
            title: "Think",
            text: "reasoning body",
            isError: false,
            date: date
        )
        let call = ConversationItem(
            id: "call",
            kind: .tool,
            title: "Read",
            text: #"{"path":"README.md"}"#,
            isError: false,
            date: date
        )
        let result = ConversationItem(
            id: "result",
            kind: .toolResult,
            title: "完成",
            text: "file contents",
            isError: false,
            date: date
        )
        let group = ConversationProcessGroup(
            id: "process-command",
            items: [command, context, reasoning, call, result]
        )

        let collapsed = ConversationProcessNode.make(from: group, expandedNodeIDs: [])
        XCTAssertEqual(collapsed.map(\.id), ["process-command"])

        let groupExpanded: Set<String> = ["process-command"]
        let firstLevel = ConversationProcessNode.make(from: group, expandedNodeIDs: groupExpanded)
        XCTAssertEqual(firstLevel.map(\.id), [
            "process-command",
            "process-command/command-detail",
            "process-command/context/context",
            "process-command/reasoning",
            "process-command/tools"
        ])
        XCTAssertEqual(firstLevel.first?.id, collapsed.first?.id)
        XCTAssertTrue(firstLevel.dropFirst().allSatisfy { $0.parentID == "process-command" })

        let fullyExpanded = ConversationProcessNode.make(
            from: group,
            expandedNodeIDs: groupExpanded.union([
                "process-command/context/context",
                "process-command/reasoning",
                "process-command/tools",
                "process-command/tools/call"
            ])
        )
        XCTAssertEqual(fullyExpanded.map(\.id), [
            "process-command",
            "process-command/command-detail",
            "process-command/context/context",
            "process-command/context/context/detail",
            "process-command/reasoning",
            "process-command/reasoning/detail",
            "process-command/tools",
            "process-command/tools/call",
            "process-command/tools/call/detail"
        ])
    }

    func testEmptyEventDetailsNeverCreateBlankDescendantRows() {
        let date = Date(timeIntervalSince1970: 1)
        let command = ConversationItem(
            id: "command",
            kind: .status,
            title: "compact",
            text: "完成",
            isError: false,
            date: date
        )
        let context = ConversationItem(
            id: "empty-context",
            kind: .context,
            title: "上下文注入",
            text: "",
            isError: false,
            date: date
        )
        let call = ConversationItem(
            id: "empty-call",
            kind: .tool,
            title: "Read",
            text: "",
            isError: false,
            date: date
        )
        let result = ConversationItem(
            id: "empty-result",
            kind: .toolResult,
            title: "完成",
            text: "",
            isError: false,
            date: date
        )
        let group = ConversationProcessGroup(
            id: "process-command",
            items: [command, context, call, result]
        )
        let expanded: Set<String> = [
            "process-command",
            "process-command/context/empty-context",
            "process-command/tools",
            "process-command/tools/empty-call"
        ]

        let nodes = ConversationProcessNode.make(from: group, expandedNodeIDs: expanded)

        XCTAssertEqual(nodes.map(\.id), [
            "process-command",
            "process-command/context/empty-context",
            "process-command/tools",
            "process-command/tools/empty-call"
        ])
    }
}

final class GatewayProtocolTests: XCTestCase {
    @MainActor
    private func flushDeferredKMPEvents(in store: AppStore) async {
        await store.awaitPendingKMPEventDeliveriesForTesting()
    }

    func testWorkspaceFileProtocolFramesDecodeWithoutDirectoryHiddenField() throws {
        let frame = try GatewayWireDecoder.decode(Data(#"{"kind":"file-list","requestId":"files-1","sessionId":"s1","path":".","entries":[{"name":"build","path":"build","kind":"directory"},{"name":"app.apk","path":"app.apk","kind":"file","bytes":42,"modifiedAt":1787111700000,"mediaType":"application/vnd.android.package-archive"}]}"#.utf8))
        XCTAssertEqual(frame.requestId, "files-1")
        XCTAssertEqual(frame.entries?.map(\.name), ["build", "app.apk"])
        XCTAssertEqual(frame.entries?.first?.hidden, false)
        XCTAssertEqual(frame.entries?.last?.bytes, 42)

        let chunk = try GatewayWireDecoder.decode(Data(#"{"kind":"file-download-chunk","transferId":"t1","offset":0,"data":"YQ==","eof":true,"sha256":"ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb"}"#.utf8))
        XCTAssertEqual(chunk.transferId, "t1")
        XCTAssertEqual(chunk.offset, 0)
        XCTAssertEqual(chunk.eof, true)
    }

    @MainActor
    func testConversationViewportReportsOnlyEmptyContentBoundaries() async throws {
        let timeline = ConversationTimeline()
        var availability: [String] = []
        let controller = ConversationViewportController(
            onContentAvailabilityChanged: { sessionID, hasContent in
                availability.append("\(sessionID ?? "nil"):\(hasContent)")
            },
            onPinnedToBottomChanged: { _ in },
            onBottomAlignmentCompleted: {},
            onApproachingTop: {}
        )
        controller.loadViewIfNeeded()
        controller.view.frame = CGRect(x: 0, y: 0, width: 390, height: 760)
        controller.view.layoutIfNeeded()
        controller.configure(
            sessionID: "history-session",
            timeline: timeline,
            supplementalEntries: [],
            makeEntries: { items in
                items.map {
                    ConversationViewportEntry(
                        id: $0.id,
                        revision: 0,
                        content: AnyView(EmptyView())
                    )
                }
            },
            bottomInset: 0
        )
        XCTAssertEqual(availability, ["history-session:false"])

        let item = ConversationItem(
            id: "message-1",
            kind: .assistant,
            title: "DeepSeek",
            text: "first page",
            isError: false,
            date: Date(timeIntervalSince1970: 1)
        )
        let secondItem = ConversationItem(
            id: "message-2",
            kind: .assistant,
            title: "DeepSeek",
            text: "second page",
            isError: false,
            date: Date(timeIntervalSince1970: 2)
        )
        timeline.publish([item])
        timeline.publish([item, secondItem])

        // 内容可用事件必须晚于 diffable snapshot completion，不能在 cell
        // 真正显示前提前撤掉不透明加载层。
        try await Task.sleep(for: .milliseconds(100))

        XCTAssertEqual(
            availability,
            ["history-session:false", "history-session:true"],
            "Timeline 仅在空/非空边界通知 SwiftUI，流式 token 不得重绘整页"
        )

        let nextTimeline = ConversationTimeline()
        nextTimeline.publish([ConversationItem(
            id: "next-message",
            kind: .user,
            title: "You",
            text: "next session",
            isError: false,
            date: Date(timeIntervalSince1970: 3)
        )])
        controller.configure(
            sessionID: "next-session",
            timeline: nextTimeline,
            supplementalEntries: [],
            makeEntries: { items in
                items.map {
                    ConversationViewportEntry(
                        id: $0.id,
                        revision: 0,
                        content: AnyView(EmptyView())
                    )
                }
            },
            bottomInset: 0
        )
        let collectionView = try XCTUnwrap(
            controller.view.subviews.compactMap { $0 as? UICollectionView }.first
        )
        XCTAssertTrue(collectionView.isHidden, "新 session snapshot 提交前必须隐藏旧 cell")

        try await Task.sleep(for: .milliseconds(100))
        XCTAssertFalse(collectionView.isHidden, "新 session snapshot 提交后应恢复显示")
        XCTAssertEqual(
            Array(availability.suffix(2)),
            ["next-session:false", "next-session:true"],
            "新 Session 必须先报告不可见，自己的 snapshot 提交后才能报告可见"
        )
    }

    @MainActor
    func testConversationPreparationCommitsDeferredSelectionBeforeNavigation() async {
        let first = SessionSummary(
            id: "first-session",
            title: "First",
            lastActivity: Date(timeIntervalSince1970: 1),
            isRunning: false,
            hasUnread: false
        )
        let second = SessionSummary(
            id: "second-session",
            title: "Second",
            lastActivity: Date(timeIntervalSince1970: 2),
            isRunning: false,
            hasUnread: false
        )
        let store = AppStore(preferences: AppPreferencesSpy(
            endpoint: "ws://127.0.0.1:3080/ws/mobile",
            selectedWorkspaceID: nil,
            sessions: [first, second]
        ))

        let preparedFirst = await store.prepareConversation(for: first)
        XCTAssertTrue(preparedFirst)
        XCTAssertEqual(store.selectedSessionId, first.id)

        let preparedSecond = await store.prepareConversation(for: second)
        XCTAssertTrue(preparedSecond)
        XCTAssertEqual(
            store.selectedSessionId,
            second.id,
            "prepare 返回时目标 Session 的延迟 KMP Event 必须已经发布，导航不能看到旧 ID"
        )
    }

    @MainActor
    func testForegroundHelloHistoryCatchUpRestoresUserMessageBeforeLiveReply() async throws {
        let session = SessionSummary(
            id: "session-catch-up",
            title: "Catch Up",
            lastActivity: Date(timeIntervalSince1970: 103),
            isRunning: true,
            hasUnread: false
        )
        let store = AppStore(preferences: AppPreferencesSpy(
            endpoint: "ws://127.0.0.1:3080/ws/mobile",
            selectedWorkspaceID: nil,
            sessions: [session]
        ))
        let prepared = await store.prepareConversation(for: session)
        XCTAssertTrue(prepared)

        // The restored subscription first sees only the Agent reply. The user
        // message was emitted while this client was suspended.
        store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(
            #"{"kind":"event","sessionId":"session-catch-up","seq":3,"time":103,"event":{"type":"assistant/message","turn":2,"step":1,"text":"Agent 回复"}}"#.utf8
        )))
        await flushDeferredKMPEvents(in: store)
        XCTAssertEqual(store.events[session.id]?.map(\.event.text), ["Agent 回复"])

        store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(
            #"{"kind":"hello","protocol":3,"capabilities":[],"authenticated":true,"clients":2}"#.utf8
        )))
        await flushDeferredKMPEvents(in: store)
        XCTAssertTrue(store.historyLoadingSessionIds.contains(session.id))

        store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(
            #"{"kind":"history","events":[{"type":"user/message","seq":2,"time":102,"data":{"content":[{"type":"text","text":"后台期间的问题"}],"source":{"kind":"user"}}},{"type":"assistant/message","seq":3,"time":103,"data":{"content":[{"type":"text","text":"过期的历史回复"}]}}],"hasMore":false,"bytes":128}"#.utf8
        )))
        for _ in 0..<100 where store.historyLoadingSessionIds.contains(session.id) {
            try await Task.sleep(for: .milliseconds(10))
            await flushDeferredKMPEvents(in: store)
        }

        XCTAssertEqual(store.events[session.id]?.map(\.event.text), ["后台期间的问题", "Agent 回复"])
        XCTAssertFalse(store.historyLoadingSessionIds.contains(session.id))
    }

    @MainActor
    func testKMPConversationAdapterConsumesIncrementalPushEvents() throws {
        let adapter = KMPConversationStoreAdapter()
        var publishedTexts: [[String]] = []
        adapter.onChange = { change in
            publishedTexts.append(change.items.map(\.text))
        }

        try adapter.receive(SessionEvent(
            sessionId: "s1", seq: 1, time: 1,
            event: GatewayEvent(
                type: "assistant/chunk", turn: 1, step: 1,
                text: "Hel", chunkType: "text-delta"
            )
        ))
        try adapter.receive(SessionEvent(
            sessionId: "s1", seq: 2, time: 2,
            event: GatewayEvent(
                type: "assistant/chunk", turn: 1, step: 1,
                text: "lo", chunkType: "text-delta"
            )
        ))

        XCTAssertTrue(adapter.isOperational)
        XCTAssertEqual(adapter.items(for: "s1").map(\.text), ["Hello"])
        XCTAssertEqual(publishedTexts, [["Hel"], ["Hello"]])
        let streamingItemID = try XCTUnwrap(adapter.items(for: "s1").first?.id)

        try adapter.receive(SessionEvent(
            sessionId: "s1", seq: 3, time: 3,
            event: GatewayEvent(
                type: "assistant/message", turn: 1, step: 1,
                text: "Hello!"
            )
        ))
        XCTAssertEqual(adapter.items(for: "s1").first?.id, streamingItemID)
        XCTAssertEqual(adapter.items(for: "s1").first?.text, "Hello!")
        XCTAssertEqual(adapter.items(for: "s1").first?.title, "DeepSeek")
        try adapter.receive(SessionEvent(
            sessionId: "s1", seq: 4, time: 4,
            event: GatewayEvent(
                type: "assistant/chunk", turn: 2, step: 1,
                text: "第二轮正在", chunkType: "text-delta"
            )
        ))
        XCTAssertEqual(adapter.items(for: "s1").last?.text, "第二轮正在")
        XCTAssertEqual(adapter.items(for: "s1").last?.title, L10n.streamingAssistantTitle)

        let baseline = [
            SessionEvent(
                sessionId: "s1", seq: 5, time: 5,
                event: GatewayEvent(type: "user/message", text: "新基线", source: "user")
            )
        ]
        try adapter.replace(sessionID: "s1", events: baseline)
        XCTAssertEqual(adapter.items(for: "s1").map(\.text), ["新基线"])

        try adapter.clear(sessionID: "s1")
        XCTAssertTrue(adapter.items(for: "s1").isEmpty)
    }

    @MainActor
    func testKMPConversationAdapterFailsClosedBeforeMalformedPatchPublishes() {
        let bridge = MalformedConversationEventBridge()
        let adapter = KMPConversationStoreAdapter(bridge: bridge)
        var publishCount = 0
        adapter.onChange = { _ in publishCount += 1 }

        XCTAssertThrowsError(try adapter.receive(SessionEvent(
            sessionId: "s1", seq: 1, time: 1,
            event: GatewayEvent(
                type: "assistant/chunk", turn: 1, step: 1,
                text: "x", chunkType: "text-delta"
            )
        )))
        XCTAssertFalse(adapter.isOperational)
        XCTAssertEqual(publishCount, 0)
        XCTAssertTrue(adapter.items(for: "s1").isEmpty)
        XCTAssertEqual(bridge.receiveCallCount, 1)

        XCTAssertThrowsError(try adapter.receive(SessionEvent(
            sessionId: "s1", seq: 2, time: 2,
            event: GatewayEvent(type: "user/message", text: "不会进入 KMP", source: "user")
        )))
        XCTAssertEqual(bridge.receiveCallCount, 1)
    }

    @MainActor
    func testKMPConversationProjectionProducesExpectedPlatformItems() throws {
        let image = GatewayImageAttachment(
            attachmentId: "image-1", mediaType: "image/png", bytes: 4,
            width: 1, height: 1, name: "pixel.png"
        )
        let records = [
            SessionEvent(sessionId: "s1", seq: 1, time: 1, event: GatewayEvent(
                type: "user/message", text: "上下文", source: "plugin",
                raw: .object(["source": .object(["plugin": .string("skill")])])
            )),
            SessionEvent(sessionId: "s1", seq: 2, time: 2, event: GatewayEvent(
                type: "user/message", text: "看图", source: "user", images: [image]
            )),
            SessionEvent(sessionId: "s1", seq: 3, time: 3, event: GatewayEvent(
                type: "assistant/chunk", turn: 1, step: 1,
                text: "临时", chunkType: "text-delta"
            )),
            SessionEvent(sessionId: "s1", seq: 4, time: 4, event: GatewayEvent(
                type: "assistant/message", turn: 1, step: 1,
                text: "完成", reasoning: "思考"
            )),
            SessionEvent(sessionId: "s1", seq: 5, time: 5, event: GatewayEvent(
                type: "tool/call", callId: "call-1", name: "Read",
                arguments: .object(["path": .string("README.md")])
            )),
            SessionEvent(sessionId: "s1", seq: 6, time: 6, event: GatewayEvent(
                type: "tool/result", callId: "call-1", isError: false, preview: "内容"
            ))
        ]
        let adapter = KMPConversationStoreAdapter()

        try adapter.replace(sessionID: "s1", events: records)
        let kmp = adapter.items(for: "s1")

        XCTAssertEqual(kmp.map(\.id), [
            records[0].id,
            records[1].id,
            records[3].id + "-reason",
            records[3].id,
            records[4].id,
            records[5].id
        ])
        XCTAssertEqual(kmp.map(\.kind), [.context, .user, .reasoning, .assistant, .tool, .toolResult])
        XCTAssertEqual(kmp.map(\.title), [
            L10n.contextInjectionTitle("skill"),
            L10n.userMessageTitle,
            "Think",
            "DeepSeek",
            "Read",
            L10n.toolResultDoneTitle
        ])
        XCTAssertEqual(kmp.filter { $0.kind != .tool }.map(\.text), [
            "上下文", "看图", "思考", "完成", "内容"
        ])
        XCTAssertTrue(kmp.first(where: { $0.kind == .tool })?.text.contains("README.md") == true)
        XCTAssertEqual(kmp.map(\.images), [[], [image], [], [], [], []])
        XCTAssertEqual(kmp.map(\.isError), [false, false, false, false, false, false])
        XCTAssertEqual(kmp.map(\.date), [
            records[0].date,
            records[1].date,
            records[3].date,
            records[3].date,
            records[4].date,
            records[5].date
        ])
    }

    @MainActor
    func testKMPTrajectoryAdapterProjectsNodesAndTokenUsage() throws {
        let usage: JSONValue = .object([
            "inputTokens": .number(10),
            "cacheReadTokens": .number(4),
            "outputTokens": .number(8),
            "reasoningTokens": .number(3)
        ])
        let records = [
            SessionEvent(
                sessionId: "s1", seq: 1, time: 1,
                event: GatewayEvent(type: "user/message", text: "执行", source: "user")
            ),
            SessionEvent(
                sessionId: "s1", seq: 2, time: 2,
                event: GatewayEvent(
                    type: "assistant/message", turn: 1, step: 1,
                    text: "完成", usage: usage
                )
            ),
            SessionEvent(
                sessionId: "s1", seq: 3, time: 3,
                event: GatewayEvent(
                    type: "tool/call", turn: 1, step: 1,
                    callId: "c1", name: "Read"
                )
            )
        ]
        let adapter = KMPTrajectoryStoreAdapter()
        var changes: [KMPTrajectoryChange] = []
        adapter.onChange = { changes.append($0) }

        try adapter.replace(sessionID: "s1", events: records)
        let kmp = adapter.nodes(for: "s1")

        XCTAssertFalse(
            kmp.isEmpty,
            "KMP Trajectory 为空：error=\(String(describing: adapter.runtimeError)), changes=\(changes.map { ($0.sessionID, $0.nodes.count) })"
        )
        XCTAssertEqual(kmp.map(\.kind), [.input, .request, .assistant, .tool])
        XCTAssertEqual(kmp.first?.subtitle, "执行")
        XCTAssertEqual(kmp.first(where: { $0.kind == .assistant })?.subtitle, "完成")
        let request = try XCTUnwrap(kmp.first(where: { $0.kind == .request })?.request)
        XCTAssertEqual(request.usage.uncachedInput, 10)
        XCTAssertEqual(request.usage.cachedInput, 4)
        XCTAssertEqual(request.usage.output, 8)
        XCTAssertEqual(request.usage.reasoning, 3)
        XCTAssertEqual(request.usage.content, 5)
    }

    @MainActor
    func testKMPTrajectoryAdapterFailsClosedBeforeMalformedUpdatePublishes() {
        let bridge = MalformedTrajectoryEventBridge()
        let adapter = KMPTrajectoryStoreAdapter(bridge: bridge)
        var publishCount = 0
        adapter.onChange = { _ in publishCount += 1 }

        XCTAssertThrowsError(try adapter.receive(SessionEvent(
            sessionId: "s1", seq: 1, time: 1,
            event: GatewayEvent(
                type: "assistant/chunk", turn: 1, step: 1,
                text: "x", chunkType: "text-delta"
            )
        )))
        XCTAssertFalse(adapter.isOperational)
        XCTAssertEqual(publishCount, 0)
        XCTAssertTrue(adapter.nodes(for: "s1").isEmpty)
    }

    @MainActor
    func testAppStoreActivatesTrajectoryProjectionOnlyForVisiblePage() async {
        let store = AppStore(preferences: AppPreferencesSpy(
            endpoint: "ws://127.0.0.1:3080/ws/mobile",
            selectedWorkspaceID: nil,
            sessions: []
        ))
        store.events["s1"] = [
            SessionEvent(
                sessionId: "s1", seq: 1, time: 1,
                event: GatewayEvent(type: "user/message", text: "one", source: "user")
            )
        ]
        let timeline = store.trajectoryTimeline(for: "s1")
        XCTAssertTrue(timeline.nodes.isEmpty)

        store.setTrajectoryProjectionActive(sessionID: "s1", isActive: true)
        await flushDeferredKMPEvents(in: store)
        XCTAssertEqual(timeline.nodes.map(\.subtitle), ["one"])

        store.setTrajectoryProjectionActive(sessionID: "s1", isActive: false)
        store.events["s1"]?.append(SessionEvent(
            sessionId: "s1", seq: 2, time: 2,
            event: GatewayEvent(type: "user/message", text: "two", source: "user")
        ))
        XCTAssertEqual(timeline.nodes.map(\.subtitle), ["one"])
        store.setTrajectoryProjectionActive(sessionID: "s1", isActive: true)
        await flushDeferredKMPEvents(in: store)
        XCTAssertEqual(timeline.nodes.map(\.subtitle), ["one", "two"])
    }

    @MainActor
    func testKMPHistoryAdapterOwnsPaginationWatermarkAndLiveTailMerge() throws {
        let adapter = KMPHistoryStoreAdapter()
        var changes: [KMPHistoryChange] = []
        adapter.onChange = { changes.append($0) }

        try adapter.start(
            sessionID: "s1",
            older: false,
            hasLocalEvents: false,
            earliestLocalSequence: nil
        )
        XCTAssertEqual(changes.last?.effect, KMPHistoryEffect(action: "request-page", sessionId: "s1", beforeSequence: nil))
        XCTAssertTrue(changes.last?.loadingSessionIDs.contains("s1") == true)

        try adapter.processingStarted(sessionID: "s1", rawEventCount: 2, hasMore: false)
        let live = SessionEvent(
            sessionId: "s1", seq: 2, time: 2,
            event: GatewayEvent(type: "assistant/message", text: "live-final")
        )
        try adapter.receive(live)
        try adapter.pageReceived(
            sessionID: "s1",
            events: [
                SessionEvent(
                    sessionId: "s1", seq: 1, time: 1,
                    event: GatewayEvent(type: "user/message", text: "history")
                ),
                SessionEvent(
                    sessionId: "s1", seq: 2, time: 2,
                    event: GatewayEvent(type: "assistant/message", text: "stale-history")
                )
            ],
            byteCount: 32,
            hasMore: false,
            nextBeforeSequence: nil,
            remoteActivityTimestamp: 2
        )

        XCTAssertEqual(adapter.events(for: "s1").map(\.seq), [1, 2])
        XCTAssertEqual(adapter.events(for: "s1").last?.event.text, "live-final")
        XCTAssertEqual(adapter.syncedActivityTimestamp(for: "s1"), 2)
        XCTAssertEqual(changes.last?.outcome, "completed")
        XCTAssertEqual(changes.last?.completedEventCount, 2)
        XCTAssertFalse(changes.last?.loadingSessionIDs.contains("s1") == true)
    }

    @MainActor
    func testKMPHistoryAdapterFailsClosedBeforeMalformedEventPatchPublishes() {
        let adapter = KMPHistoryStoreAdapter(bridge: MalformedHistoryEventBridge())
        var publishCount = 0
        adapter.onChange = { _ in publishCount += 1 }

        XCTAssertThrowsError(try adapter.receive(SessionEvent(
            sessionId: "s1", seq: 1, time: 1,
            event: GatewayEvent(type: "assistant/message", text: "one")
        )))
        XCTAssertFalse(adapter.isOperational)
        XCTAssertEqual(publishCount, 0)
        XCTAssertTrue(adapter.events(for: "s1").isEmpty)
    }

    func testStage9WriteAuditRecognizesDirectNestedInoutAndCollectionMutations() throws {
        let defects: [(property: String, source: String)] = [
            ("sessions", "sessions = replacement"),
            ("sessions", "sessions += replacement"),
            ("sessions", "sessions.append(candidate)"),
            ("sessions", "sessions.popLast()"),
            ("sessions", "sessions[0].title = replacement"),
            ("modelCatalogs", "modelCatalogs[id]?.current = selection"),
            ("modelCatalogs", "modelCatalogs[id]?.groups[0].models[0].name = replacement"),
            ("modelCatalogs", "modelCatalogs.removeValue(forKey: id)"),
            ("pendingQuestionRequests", "pendingQuestionRequests.swapAt(0, 1)"),
            ("questionRequestStatuses", "consume(&questionRequestStatuses)"),
            ("sessions", "consume(&sessions[0])"),
            ("sessions", "mutate(&sessions[0].title)"),
            ("modelCatalogs", "mutate(&self.modelCatalogs[id]!.groups[0])"),
            ("pendingModelsSessionId", "pendingModelsSessionId = rogueSession"),
            ("isPendingGlobalModelsRequest", "isPendingGlobalModelsRequest = true"),
            ("pendingModelSelectionSessionId", "pendingModelSelectionSessionId = rogueSession"),
            ("pendingPermissionOptionsSessionId", "pendingPermissionOptionsSessionId = rogueSession")
        ]
        for defect in defects {
            XCTAssertFalse(
                try stage9WriteMatches(for: defect.property, in: defect.source).isEmpty,
                "静态写边界门禁必须识别：\(defect.source)"
            )
        }
        XCTAssertTrue(
            try stage9WriteMatches(for: "sessions", in: "let count = sessions.count").isEmpty,
            "只读属性访问不得被误判为写入"
        )
    }

    func testStage11ProductSourcesContainNoParallelBasicDomainImplementation() throws {
        let repositoryRoot = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let productRoot = repositoryRoot.appendingPathComponent("DeepSeekHarnessMobile")
        let enumerator = try XCTUnwrap(
            FileManager.default.enumerator(
                at: productRoot,
                includingPropertiesForKeys: nil
            )
        )
        let sourceURLs = enumerator.compactMap { item -> URL? in
            guard let url = item as? URL, url.pathExtension == "swift" else { return nil }
            return url
        }
        XCTAssertFalse(sourceURLs.isEmpty)

        for removedFile in [
            "Core/SessionListReducer.swift",
            "Core/QuestionReducer.swift",
            "Core/SessionControlReducer.swift",
            "Core/HistoryReducer.swift"
        ] {
            XCTAssertFalse(
                FileManager.default.fileExists(atPath: productRoot.appendingPathComponent(removedFile).path),
                "阶段 11 不得恢复已由 KMP 取代的 Swift 文件：\(removedFile)"
            )
        }
        XCTAssertTrue(
            FileManager.default.fileExists(
                atPath: productRoot.appendingPathComponent("Core/KMPDomainIntents.swift").path
            ),
            "平台输入 DTO 必须与 Swift Reducer 解耦"
        )
        XCTAssertFalse(
            FileManager.default.fileExists(
                atPath: repositoryRoot.appendingPathComponent(
                    "shared/src/commonMain/kotlin/com/clarklevis/dsh/shared/facade/SharedShadowFacade.kt"
                ).path
            ),
            "阶段 11 必须删除迁移期只读影子 Facade"
        )

        for reducerSymbol in [
            "SessionListReducer",
            "QuestionReducer",
            "SessionControlReducer",
            "QuestionState",
            "SessionControlState",
            "SessionListState"
        ] {
            let referencePattern = try NSRegularExpression(
                pattern: "\\b\(NSRegularExpression.escapedPattern(for: reducerSymbol))\\b"
            )
            for sourceURL in sourceURLs {
                let source = try String(contentsOf: sourceURL, encoding: .utf8)
                let fullRange = NSRange(source.startIndex..., in: source)
                XCTAssertNil(
                    referencePattern.firstMatch(in: source, range: fullRange),
                    "阶段 11 已删除 Swift 平行领域实现，产品 Swift 源不得引用 \(reducerSymbol)：\(sourceURL.path)"
                )
            }
        }

        let identifierPattern = try NSRegularExpression(pattern: "\\b[A-Za-z_][A-Za-z0-9_]*\\b")
        let rollbackTerms = ["use", "enable", "disable", "prefer", "force", "flag", "fallback", "rollback", "legacy"]
        for sourceURL in sourceURLs {
            let source = try String(contentsOf: sourceURL, encoding: .utf8)
            let fullRange = NSRange(source.startIndex..., in: source)
            let suspiciousIdentifiers = identifierPattern.matches(in: source, range: fullRange).compactMap { match -> String? in
                guard let range = Range(match.range, in: source) else { return nil }
                let identifier = String(source[range])
                let normalized = identifier.lowercased()
                guard normalized.contains("kmp") || normalized.contains("swift") else { return nil }
                return rollbackTerms.contains(where: normalized.contains) ? identifier : nil
            }
            XCTAssertTrue(
                suspiciousIdentifiers.isEmpty,
                "阶段 9 人工验收后不得引入疑似 KMP/Swift 运行时切换标识符：\(sourceURL.path) \(suspiciousIdentifiers)"
            )
        }

        let appStoreSource = try String(
            contentsOf: productRoot.appendingPathComponent("Core/AppStore.swift"),
            encoding: .utf8
        )
        for requiredKMPWritePath in [
            "kmpSessionListStore.reduce",
            "kmpQuestionStore.reduce",
            "kmpSessionControlStore.reduce"
        ] {
            XCTAssertTrue(
                appStoreSource.contains(requiredKMPWritePath),
                "AppStore 必须继续通过 KMP 提交基础领域状态：\(requiredKMPWritePath)"
            )
        }

        let appStoreNSString = appStoreSource as NSString
        let allowedAssignmentScopes = try Dictionary(
            uniqueKeysWithValues: ["initialization", "session-list", "question", "session-control"].map { name in
                (
                    name,
                    try swiftAuditRange(
                        from: "// stage9-kmp-write-scope: \(name)-begin",
                        through: "// stage9-kmp-write-scope: \(name)-end",
                        in: appStoreNSString
                    )
                )
            }
        )
        let migratedProperties: [String: Set<String>] = [
            "selectedSessionId": ["initialization", "session-list"],
            "sessions": ["initialization", "session-list"],
            "archivedSessionIds": ["initialization", "session-list"],
            "pendingQuestionRequests": ["initialization", "question"],
            "questionRequestStatuses": ["initialization", "question"],
            "modelCatalogs": ["session-control"],
            "globalModelCatalog": ["session-control"],
            "sessionPermissions": ["session-control"],
            "contextSnapshots": ["session-control"],
            "sessionStatsSnapshots": ["session-control"],
            "sessionControlLoadingKinds": ["session-control"],
            "agentPresets": ["session-control"],
            "agentPresetsAuthorable": ["session-control"],
            "agentPresetsHasDocument": ["session-control"],
            "agentPresetDefault": ["session-control"],
            "permissionDefault": ["session-control"],
            "defaultModelSelection": ["session-control"],
            "defaultConfigurationLoadingKinds": ["session-control"],
            "pendingModelsSessionId": ["session-control"],
            "isPendingGlobalModelsRequest": ["session-control"],
            "pendingModelSelectionSessionId": ["session-control"],
            "pendingPermissionOptionsSessionId": ["session-control"]
        ]
        let privateRoutingProperties: Set<String> = [
            "pendingModelsSessionId",
            "isPendingGlobalModelsRequest",
            "pendingModelSelectionSessionId",
            "pendingPermissionOptionsSessionId"
        ]
        for (property, allowedScopes) in migratedProperties {
            if privateRoutingProperties.contains(property) {
                XCTAssertTrue(
                    appStoreSource.contains("private var \(property)"),
                    "已迁移 SessionControl 路由字段必须保持私有：\(property)"
                )
            } else {
                XCTAssertTrue(
                    appStoreSource.contains("@Published private(set) var \(property)"),
                    "已迁移 snapshot 属性必须使用 private(set) 限制外部写入：\(property)"
                )
            }
            let writeMatches = try stage9WriteMatches(for: property, in: appStoreSource)
            for match in writeMatches {
                let lineRange = appStoreNSString.lineRange(for: match.range)
                let line = appStoreNSString.substring(with: lineRange)
                if line.contains("@Published private(set) var \(property)")
                    || line.contains("private var \(property)") {
                    continue
                }
                let enclosingScopes = allowedAssignmentScopes.compactMap { name, range in
                    NSLocationInRange(match.range.location, range) ? name : nil
                }
                XCTAssertTrue(
                    enclosingScopes.contains(where: allowedScopes.contains),
                    "\(property) 只能在初始化或指定 KMP snapshot 发布函数写入，实际位置：\(line.trimmingCharacters(in: .whitespacesAndNewlines))"
                )
            }
        }
    }

    func testStage11ProductBasicDomainsUseIntentDispatchAndEventSubscription() throws {
        let repositoryRoot = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let appStoreSource = try String(
            contentsOf: repositoryRoot.appendingPathComponent("DeepSeekHarnessMobile/Core/AppStore.swift"),
            encoding: .utf8
        )
        let adapterSource = try String(
            contentsOf: repositoryRoot.appendingPathComponent("DeepSeekHarnessMobile/Core/KMPSharedAdapter.swift"),
            encoding: .utf8
        )
        for callback in [
            "kmpSessionListStore.onSnapshot",
            "kmpQuestionStore.onTransition",
            "kmpSessionControlStore.onTransition"
        ] {
            XCTAssertTrue(appStoreSource.contains(callback), "产品必须订阅 KMP Event：\(callback)")
        }
        for subscription in [
            "extension SharedSessionListStore: KMPSessionListEventBridging",
            "extension SharedQuestionStore: KMPQuestionEventBridging",
            "extension SharedSessionControlStore: KMPSessionControlEventBridging"
        ] {
            XCTAssertTrue(adapterSource.contains(subscription), "Adapter 必须接入可取消订阅：\(subscription)")
        }
        for forbidden in [
            "applySessionControlTransition(kmpSessionControlStore.reduce",
            "applySessionControlTransition(self.kmpSessionControlStore.reduce"
        ] {
            XCTAssertFalse(
                appStoreSource.contains(forbidden),
                "产品不得从 mutation 返回值发布状态或执行 effect：\(forbidden)"
            )
        }
        XCTAssertEqual(
            appStoreSource.components(separatedBy: "executeQuestionEffect(transition.effect)").count - 1,
            1,
            "Question effect 只能在 KMP Event 订阅回调中执行一次"
        )
        XCTAssertFalse(appStoreSource.contains("KMPShadowValidator"))
        XCTAssertFalse(adapterSource.contains("SharedShadowFacade"))
    }

    func testStage11ProductSourcesContainNoParallelProjectionOrHistoryImplementation() throws {
        let repositoryRoot = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let productRoot = repositoryRoot.appendingPathComponent("DeepSeekHarnessMobile")
        let enumerator = try XCTUnwrap(FileManager.default.enumerator(
            at: productRoot,
            includingPropertiesForKeys: nil
        ))
        let productSources = enumerator.compactMap { item -> URL? in
            guard let url = item as? URL,
                  url.pathExtension == "swift" else { return nil }
            return url
        }
        for symbol in [
            "ConversationProjector", "ConversationHistoryRebase", "ConversationItem.fold",
            "HistoryReducer", "HistoryEventMerger"
        ] {
            for sourceURL in productSources {
                let source = try String(contentsOf: sourceURL, encoding: .utf8)
                XCTAssertFalse(
                    source.contains(symbol),
                    "阶段 10.2 后产品 Swift 不得恢复 Conversation 领域投影：\(symbol) @ \(sourceURL.path)"
                )
            }
        }
        XCTAssertFalse(
            FileManager.default.fileExists(
                atPath: productRoot.appendingPathComponent("Core/ConversationProjection.swift").path
            ),
            "旧 ConversationProjection 文件必须删除或收敛为纯平台 Timeline"
        )

        let appStoreSource = try String(
            contentsOf: productRoot.appendingPathComponent("Core/AppStore.swift"),
            encoding: .utf8
        )
        for required in [
            "kmpConversationStore.onChange",
            "kmpConversationStore.receive(record)",
            "kmpConversationStore.replace(sessionID:",
            "kmpTrajectoryStore.onChange",
            "kmpTrajectoryStore.receive(records)",
            "kmpTrajectoryStore.replace(sessionID:",
            "kmpHistoryStore.onChange",
            "kmpHistoryStore.receive(record)",
            "kmpHistoryStore.pageReceived("
        ] {
            XCTAssertTrue(appStoreSource.contains(required), "Conversation 产品路径缺少 KMP Intent/Event：\(required)")
        }
        XCTAssertFalse(
            appStoreSource.contains("conversationProjectors"),
            "AppStore 不得继续持有 Swift Conversation projector 状态"
        )
        let trajectoryViewSource = try String(
            contentsOf: productRoot.appendingPathComponent("Views/TrajectoryView.swift"),
            encoding: .utf8
        )
        XCTAssertFalse(
            trajectoryViewSource.contains("TrajectoryProjection.make("),
            "TrajectoryView 不得继续执行 Swift Trajectory 业务投影"
        )

        let appStoreNSString = appStoreSource as NSString
        let historyPublishScope = try swiftAuditRange(
            from: "// stage10-kmp-write-scope: history-begin",
            through: "// stage10-kmp-write-scope: history-end",
            in: appStoreNSString
        )
        for property in [
            "historyHasMore", "historyLoadingSessionIds",
            "historyLoadingOlderSessionIds", "historyLoadProgress"
        ] {
            XCTAssertTrue(
                appStoreSource.contains("@Published private(set) var \(property)"),
                "History UI 镜像必须对 AppStore 外部只读：\(property)"
            )
            let writes = try stage9WriteMatches(for: property, in: appStoreSource)
            XCTAssertFalse(writes.isEmpty, "History UI 镜像必须由 KMP event 发布：\(property)")
            XCTAssertTrue(
                writes.allSatisfy { NSLocationInRange($0.range.location, historyPublishScope) },
                "History UI 镜像只允许在 KMP History change 发布块内写入：\(property)"
            )
        }
    }

    func testDecodesDirectoryCreateResponse() throws {
        let data = #"{"kind":"directory-create","path":"/tmp/workspace/Sources"}"#.data(using: .utf8)!
        let frame = try JSONDecoder().decode(GatewayFrame.self, from: data)

        XCTAssertEqual(frame.kind, "directory-create")
        XCTAssertEqual(frame.path, "/tmp/workspace/Sources")

        let context = GatewayFrameRoutingContext(
            selectedSessionID: nil,
            pendingHistorySessionID: nil,
            pendingModelsSessionID: nil,
            isPendingGlobalModelsRequest: false,
            pendingModelSelectionSessionID: nil,
            pendingPermissionOptionsSessionID: nil
        )
        guard case .workspace(.directoryCreated(let path)) =
                GatewayFrameRouter.route(frame, context: context) else {
            return XCTFail("directory-create 应路由为 workspace.directoryCreated")
        }
        XCTAssertEqual(path, "/tmp/workspace/Sources")
    }

    func testGatewayFrameRouterMapsHelloAndLiveEventPayloads() throws {
        let context = GatewayFrameRoutingContext(
            selectedSessionID: "selected",
            pendingHistorySessionID: nil,
            pendingModelsSessionID: nil,
            isPendingGlobalModelsRequest: false,
            pendingModelSelectionSessionID: nil,
            pendingPermissionOptionsSessionID: nil
        )
        let hello = try GatewayWireDecoder.decode(Data(
            #"{"kind":"hello","protocol":3,"capabilities":["images"],"authenticated":true,"clients":2}"#.utf8
        ))
        guard case .connection(.hello(let payload)) = GatewayFrameRouter.route(hello, context: context) else {
            return XCTFail("hello 应路由为 connection.hello")
        }
        XCTAssertEqual(payload.protocolVersion, 3)
        XCTAssertEqual(payload.capabilities, ["images"])
        XCTAssertTrue(payload.authenticated)
        XCTAssertEqual(payload.clients, 2)

        let event = try GatewayWireDecoder.decode(Data(
            #"{"kind":"event","sessionId":"s1","seq":7,"time":1000,"event":{"type":"assistant/message","text":"done"}}"#.utf8
        ))
        guard case .content(.liveEvent(let record)) = GatewayFrameRouter.route(event, context: context) else {
            return XCTFail("event 应路由为 content.liveEvent")
        }
        XCTAssertEqual(record.sessionId, "s1")
        XCTAssertEqual(record.seq, 7)
        XCTAssertEqual(record.event.text, "done")
    }

    func testGatewayFrameRouterBuildsQuestionPayloadAndRejectsMalformedRequest() throws {
        let context = GatewayFrameRoutingContext(
            selectedSessionID: nil,
            pendingHistorySessionID: nil,
            pendingModelsSessionID: nil,
            isPendingGlobalModelsRequest: false,
            pendingModelSelectionSessionID: nil,
            pendingPermissionOptionsSessionID: nil
        )
        let valid = try GatewayWireDecoder.decode(Data(
            #"{"kind":"question-requested","rpcId":"rpc-1","sessionId":"s1","replay":true,"questions":[{"id":"q1","question":"继续？"}]}"#.utf8
        ))
        guard case .question(.requested(let request, let sessionID, let preview, let replay)) =
                GatewayFrameRouter.route(valid, context: context) else {
            return XCTFail("有效问题应生成 requested action")
        }
        XCTAssertEqual(sessionID, "s1")
        XCTAssertEqual(preview, "继续？")
        XCTAssertTrue(replay)
        XCTAssertEqual(request.rpcId, "rpc-1")

        let invalid = try GatewayWireDecoder.decode(Data(
            #"{"kind":"question-requested","sessionId":"s1","questions":[]}"#.utf8
        ))
        guard case .question(.invalidRequest(let sessionID)) =
                GatewayFrameRouter.route(invalid, context: context) else {
            return XCTFail("缺少 rpcId 的问题必须显式路由为 invalidRequest")
        }
        XCTAssertEqual(sessionID, "s1")
    }

    @MainActor
    func testApprovalRequestRoutesThroughSharedReducerAndBuildsOneShotEffect() throws {
        let context = GatewayFrameRoutingContext(
            selectedSessionID: "s1",
            pendingHistorySessionID: nil,
            pendingModelsSessionID: nil,
            isPendingGlobalModelsRequest: false,
            pendingModelSelectionSessionID: nil,
            pendingPermissionOptionsSessionID: nil
        )
        let frame = try GatewayWireDecoder.decode(Data(
            GatewayProtocolParityFixtures.replayedApprovalRequest.utf8
        ))
        guard case .approval(.requested(let request)) =
                GatewayFrameRouter.route(frame, context: context) else {
            return XCTFail("有效审批请求应路由为 approval.requested")
        }
        XCTAssertEqual(request.rpcId, "rpc-approval-1")
        XCTAssertEqual(request.approvalId, "approval-1")
        XCTAssertEqual(request.toolName, "Bash")
        XCTAssertEqual(request.callId, "call-1")
        XCTAssertEqual(request.reason, "需要读取系统版本")
        XCTAssertTrue(request.replay)

        let adapter = KMPApprovalStoreAdapter()
        let received = adapter.reduce(.requestReceived(request))
        XCTAssertNil(received.error)
        XCTAssertEqual(received.snapshot.pendingRequests, [request])
        let submitted = adapter.reduce(.submitDecision(
            rpcID: request.rpcId,
            outcome: .allowedOnce,
            isConnected: true
        ))
        XCTAssertNil(submitted.error)
        XCTAssertEqual(submitted.effect?.action, "respond")
        XCTAssertEqual(submitted.effect?.approvalId, "approval-1")
        XCTAssertEqual(submitted.effect?.outcome, "allowed-once")
        XCTAssertEqual(submitted.snapshot.requestStatuses[request.rpcId]?.kind, "submitting")

        let duplicate = adapter.reduce(.submitDecision(
            rpcID: request.rpcId,
            outcome: .rejected,
            isConnected: true
        ))
        XCTAssertNil(duplicate.effect, "同一审批只能发出一次响应 effect")
    }

    func testApprovalArgumentsNormalizeJSONStringBeforeRendering() throws {
        let encodedArguments = JSONValue.string(
            #"{"command":"sw_vers && uname -a","description":"读取系统版本"}"#
        )
        let normalized = encodedArguments.normalizedValue

        XCTAssertEqual(normalized["command"]?.stringValue, "sw_vers && uname -a")
        XCTAssertEqual(normalized["description"]?.stringValue, "读取系统版本")
    }

    func testGatewayFrameRouterPreservesMissingSessionForKMPRequestCorrelation() throws {
        let context = GatewayFrameRoutingContext(
            selectedSessionID: "selected",
            pendingHistorySessionID: nil,
            pendingModelsSessionID: "models-session",
            isPendingGlobalModelsRequest: false,
            pendingModelSelectionSessionID: "selection-session",
            pendingPermissionOptionsSessionID: "permission-session"
        )
        let selected = try GatewayWireDecoder.decode(Data(
            #"{"kind":"select-model","selected":{"provider":"openai","model":"gpt-5"}}"#.utf8
        ))
        guard case .control(.modelSelected(let sessionID, let selection)) =
                GatewayFrameRouter.route(selected, context: context) else {
            return XCTFail("select-model 应保留网关原始 target")
        }
        XCTAssertNil(sessionID)
        XCTAssertEqual(selection?.model, "gpt-5")
    }

    func testAttachmentLoaderDeduplicatesAndHonorsConcurrencyLimit() {
        func attachment(_ id: String) -> GatewayImageAttachment {
            GatewayImageAttachment(
                attachmentId: id,
                mediaType: "image/png",
                bytes: 1,
                width: 1,
                height: 1
            )
        }
        var loader = AttachmentLoader(maximumConcurrentRequests: 2)
        let initial = loader.enqueue(
            [attachment("a"), attachment("b"), attachment("a"), attachment("cached"), attachment("c")],
            sessionID: "s1",
            isCached: { $0 == "cached" }
        )
        XCTAssertEqual(initial.map(\.attachment.id), ["a", "b"])
        XCTAssertEqual(loader.inFlightAttachmentIDs, ["a", "b"])
        XCTAssertEqual(loader.queuedAttachmentIDs, ["c"])

        let next = loader.complete(attachmentID: "a", isCached: { _ in false })
        XCTAssertEqual(next.map(\.attachment.id), ["c"])
        XCTAssertEqual(loader.inFlightAttachmentIDs, ["b", "c"])
        loader.reset()
        XCTAssertTrue(loader.inFlightAttachmentIDs.isEmpty)
        XCTAssertTrue(loader.queuedAttachmentIDs.isEmpty)
    }

    @MainActor
    func testAgentBackgroundExecutionControllerTracksTurnsAndEndsTask() {
        let application = BackgroundTaskApplicationSpy()
        let controller = AgentBackgroundExecutionController(application: application)
        var expirationCount = 0
        controller.onBackgroundAllowanceExpired = { expirationCount += 1 }

        controller.begin(sessionID: nil, startsNewTurn: true)
        controller.associateSessionIfNeeded("s1")
        controller.begin(sessionID: "s1", startsNewTurn: true)
        XCTAssertTrue(controller.keepsConnectionAlive)
        XCTAssertEqual(controller.outstandingTurns, 2)
        XCTAssertEqual(application.beginCallCount, 0, "前台不应提前占用后台任务额度")

        controller.applicationDidEnterBackground()
        XCTAssertEqual(application.beginCallCount, 1)
        controller.turnEnded(sessionID: "s1")
        XCTAssertEqual(controller.outstandingTurns, 1)
        controller.turnEnded(sessionID: "s1")
        XCTAssertFalse(controller.keepsConnectionAlive)
        XCTAssertEqual(application.endedIdentifiers.count, 1)
        XCTAssertEqual(expirationCount, 1)
    }

    @MainActor
    func testQuestionBackgroundAllowanceDoesNotEndAnotherTurnAndAcceptedRestoresTurn() {
        let application = BackgroundTaskApplicationSpy()
        let controller = AgentBackgroundExecutionController(application: application)

        controller.begin(sessionID: "s1", startsNewTurn: true)
        controller.beginQuestionAnswer(rpcID: "rpc-1", sessionID: "s1")
        controller.releaseQuestionAnswer(rpcID: "rpc-1")

        XCTAssertTrue(controller.isAgentWorkActive)
        XCTAssertEqual(controller.outstandingTurns, 1)
        XCTAssertTrue(controller.questionAllowanceSessionIDs.isEmpty)
        XCTAssertEqual(application.endedIdentifiers.count, 0)

        controller.turnEnded(sessionID: "s1")
        XCTAssertFalse(controller.isAgentWorkActive)
        XCTAssertEqual(application.endedIdentifiers.count, 0)

        controller.beginQuestionAnswer(rpcID: "rpc-restored", sessionID: "s2")
        XCTAssertEqual(controller.outstandingTurns, 0)
        controller.questionAnswerAccepted(rpcID: "rpc-restored")
        XCTAssertEqual(controller.outstandingTurns, 1)
        XCTAssertTrue(controller.questionAllowanceSessionIDs.isEmpty)
        XCTAssertTrue(controller.keepsConnectionAlive)

        controller.turnEnded(sessionID: "s2")
        XCTAssertFalse(controller.isAgentWorkActive)
        XCTAssertEqual(application.endedIdentifiers.count, 0)
    }

    @MainActor
    func testBackgroundExecutionTracksTurnsAndAcceptedQuestionsPerSession() {
        let application = BackgroundTaskApplicationSpy()
        let controller = AgentBackgroundExecutionController(application: application)

        controller.begin(sessionID: "s1", startsNewTurn: true)
        controller.beginQuestionAnswer(rpcID: "rpc-s2-a", sessionID: "s2")
        controller.beginQuestionAnswer(rpcID: "rpc-s2-b", sessionID: "s2")
        controller.questionAnswerAccepted(rpcID: "rpc-s2-a")
        controller.questionAnswerAccepted(rpcID: "rpc-s2-b")

        XCTAssertEqual(controller.outstandingTurnsBySessionID, ["s1": 1, "s2": 1])
        XCTAssertEqual(controller.outstandingTurns, 2)
        XCTAssertNil(controller.sessionID)
        XCTAssertEqual(application.beginCallCount, 0)

        controller.turnEnded(sessionID: "s1")
        XCTAssertEqual(controller.outstandingTurnsBySessionID, ["s2": 1])
        XCTAssertTrue(controller.keepsConnectionAlive)
        XCTAssertEqual(application.endedIdentifiers.count, 0)

        controller.turnEnded(sessionID: "s2")
        XCTAssertFalse(controller.isAgentWorkActive)
        XCTAssertEqual(application.endedIdentifiers.count, 0)
    }

    @MainActor
    func testBackgroundExecutionReleasesOnlyTargetSessionAllowancesAndCancelClearsAll() {
        let application = BackgroundTaskApplicationSpy()
        let controller = AgentBackgroundExecutionController(application: application)

        controller.beginQuestionAnswer(rpcID: "rpc-s1-a", sessionID: "s1")
        controller.beginQuestionAnswer(rpcID: "rpc-s1-b", sessionID: "s1")
        controller.beginQuestionAnswer(rpcID: "rpc-s2", sessionID: "s2")
        controller.releaseQuestionAnswers(sessionID: "s1")

        XCTAssertEqual(controller.questionAllowanceSessionIDs, ["rpc-s2": "s2"])
        XCTAssertTrue(controller.keepsConnectionAlive)
        XCTAssertEqual(application.endedIdentifiers.count, 0)

        controller.cancel()
        XCTAssertTrue(controller.outstandingTurnsBySessionID.isEmpty)
        XCTAssertEqual(controller.unassociatedOutstandingTurns, 0)
        XCTAssertTrue(controller.questionAllowanceSessionIDs.isEmpty)
        XCTAssertFalse(controller.keepsConnectionAlive)
        XCTAssertEqual(application.endedIdentifiers.count, 0)
    }

    @MainActor
    func testBackgroundAllowanceStartsOnlyInBackgroundAndRestartsAcrossForeground() {
        let application = BackgroundTaskApplicationSpy()
        let controller = AgentBackgroundExecutionController(application: application)

        controller.begin(sessionID: "s1", startsNewTurn: true)
        XCTAssertEqual(application.beginCallCount, 0)

        controller.applicationDidEnterBackground()
        XCTAssertEqual(application.beginCallCount, 1)
        XCTAssertTrue(controller.keepsConnectionAlive)

        controller.applicationDidBecomeActive()
        XCTAssertEqual(application.endedIdentifiers.count, 1)
        XCTAssertTrue(controller.isAgentWorkActive)
        XCTAssertTrue(controller.keepsConnectionAlive)

        controller.applicationDidEnterBackground()
        XCTAssertEqual(application.beginCallCount, 2)
        controller.turnEnded(sessionID: "s1")
        XCTAssertEqual(application.endedIdentifiers.count, 2)
        XCTAssertFalse(controller.keepsConnectionAlive)
    }

    @MainActor
    func testBackgroundTaskExpirationClearsAllInternalActivity() async {
        let application = BackgroundTaskApplicationSpy()
        let controller = AgentBackgroundExecutionController(application: application)
        var expirationCount = 0
        controller.onBackgroundAllowanceExpired = { expirationCount += 1 }

        controller.begin(sessionID: "s1", startsNewTurn: true)
        controller.beginQuestionAnswer(rpcID: "rpc-1", sessionID: "s1")
        controller.applicationDidEnterBackground()
        application.expirationHandler?()
        await Task.yield()

        XCTAssertFalse(controller.isAgentWorkActive)
        XCTAssertFalse(controller.keepsConnectionAlive)
        XCTAssertEqual(controller.outstandingTurns, 0)
        XCTAssertTrue(controller.outstandingTurnsBySessionID.isEmpty)
        XCTAssertEqual(controller.unassociatedOutstandingTurns, 0)
        XCTAssertNil(controller.sessionID)
        XCTAssertTrue(controller.questionAllowanceSessionIDs.isEmpty)
        XCTAssertEqual(application.endedIdentifiers.count, 1)
        XCTAssertEqual(expirationCount, 1)
    }

    func testUserDefaultsAppPreferencesUsesDefaultsAndRoundTripsValues() throws {
        let suiteName = "AppPreferencesTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let preferences = UserDefaultsAppPreferences(userDefaults: defaults)

        XCTAssertEqual(preferences.endpoint, UserDefaultsAppPreferences.defaultEndpoint)
        XCTAssertNil(preferences.selectedWorkspaceID)
        XCTAssertEqual(preferences.loadSessions(), [])

        let sessions = [
            SessionSummary(
                id: "session-1",
                title: "持久化测试",
                lastActivity: Date(timeIntervalSince1970: 1_000),
                isRunning: true,
                hasUnread: true,
                agentPreset: "default"
            )
        ]
        preferences.endpoint = "wss://gateway.example/ws/mobile"
        preferences.selectedWorkspaceID = "workspace-1"
        preferences.saveSessions(sessions)

        let reloaded = UserDefaultsAppPreferences(userDefaults: defaults)
        XCTAssertEqual(reloaded.endpoint, "wss://gateway.example/ws/mobile")
        XCTAssertEqual(reloaded.selectedWorkspaceID, "workspace-1")
        XCTAssertEqual(reloaded.loadSessions(), sessions)

        reloaded.selectedWorkspaceID = nil
        XCTAssertNil(defaults.object(forKey: "gateway.selectedWorkspaceId"))
    }

    func testUserDefaultsAppPreferencesPreservesExistingKeysAndRunsLegacyCleanup() throws {
        let suiteName = "AppPreferencesMigrationTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
        defaults.set("ws://legacy-host:3080/ws/mobile", forKey: "gateway.endpoint")
        defaults.set(["session-1": 42], forKey: "gateway.conversationScrollAnchors")
        defaults.set(["session-1"], forKey: "gateway.manuallyPositionedSessionIds")
        let preferences = UserDefaultsAppPreferences(userDefaults: defaults)

        preferences.performMigrations()

        XCTAssertEqual(preferences.endpoint, "ws://legacy-host:3080/ws/mobile")
        XCTAssertNil(defaults.object(forKey: "gateway.conversationScrollAnchors"))
        XCTAssertNil(defaults.object(forKey: "gateway.manuallyPositionedSessionIds"))
    }

    @MainActor
    func testAppStoreLoadsInjectedPreferencesAndPersistsEachSessionChangeOnce() async {
        let initialSession = SessionSummary(
            id: "existing",
            title: "已有会话",
            lastActivity: Date(timeIntervalSince1970: 900),
            isRunning: false,
            hasUnread: false
        )
        let preferences = AppPreferencesSpy(
            endpoint: "wss://injected.example/ws/mobile",
            selectedWorkspaceID: "workspace-injected",
            sessions: [initialSession]
        )

        let store = AppStore(preferences: preferences)

        XCTAssertEqual(store.endpoint, preferences.endpoint)
        XCTAssertEqual(store.selectedWorkspaceId, preferences.selectedWorkspaceID)
        XCTAssertEqual(store.sessions, [initialSession])
        XCTAssertEqual(preferences.performMigrationsCallCount, 1)
        XCTAssertEqual(preferences.savedSessionSnapshots, [])

        store.addKnownSession("new-session")
        XCTAssertEqual(
            preferences.savedSessionSnapshots,
            [],
            "KMP Event 不得在 UI Intent 的同一调用栈内发布 Swift 镜像"
        )
        await flushDeferredKMPEvents(in: store)
        XCTAssertEqual(preferences.savedSessionSnapshots.count, 1)
        XCTAssertEqual(preferences.savedSessionSnapshots.last?.map(\.id), ["new-session", "existing"])

        store.addKnownSession("new-session")
        await flushDeferredKMPEvents(in: store)
        XCTAssertEqual(preferences.savedSessionSnapshots.count, 1)
    }

    func testImageAttachmentCachePersistsAndExpires() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("image-cache-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        var currentDate = Date(timeIntervalSince1970: 1_000)
        let payload = Data("cached-image".utf8)
        let cache = ImageAttachmentCache(
            directoryURL: directory,
            ttl: 60,
            memoryCostLimit: 1,
            now: { currentDate }
        )

        cache.store(payload, for: "attachment/unsafe-path")
        cache.removeAllFromMemory()
        XCTAssertEqual(cache.data(for: "attachment/unsafe-path"), payload)

        cache.removeAllFromMemory()
        currentDate.addTimeInterval(61)
        XCTAssertNil(cache.data(for: "attachment/unsafe-path"))
        XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: directory.path).isEmpty)
    }

    func testImagePixelLimitPreflight() {
        let accepted = GatewayImageDimensions(width: 2_000, height: 2_000)
        let sideTooLong = GatewayImageDimensions(width: 2_001, height: 1_000)

        XCTAssertTrue(GatewayImageInspector.isWithinPixelLimits(accepted))
        XCTAssertFalse(GatewayImageInspector.isWithinPixelLimits(sideTooLong))
    }

    func testImagePreprocessorDownsizesToDSHLimits() throws {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let image = UIGraphicsImageRenderer(size: CGSize(width: 3_000, height: 1_500), format: format).image { context in
            UIColor.systemBlue.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 3_000, height: 1_500))
        }
        let source = try XCTUnwrap(image.pngData())
        let prepared = try GatewayImagePreprocessor.prepare(data: source, mediaType: "image/png")

        XCTAssertEqual(prepared.mediaType, "image/jpeg")
        XCTAssertLessThanOrEqual(prepared.data.count, GatewayImagePreprocessor.maximumBytes)
        XCTAssertLessThanOrEqual(prepared.dimensions.longestSide, GatewayImagePreprocessor.maximumPixelSide)
        XCTAssertLessThanOrEqual(
            prepared.dimensions.width * prepared.dimensions.height,
            GatewayImagePreprocessor.maximumPixels
        )
        XCTAssertEqual(prepared.dimensions.width, 2_000)
        XCTAssertEqual(prepared.dimensions.height, 1_000)
    }

    func testImagePreprocessorNormalizesOrientationWithoutChangingAspectRatio() throws {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let image = UIGraphicsImageRenderer(size: CGSize(width: 40, height: 20), format: format).image { context in
            UIColor.systemOrange.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 40, height: 20))
        }
        let sourceData = try XCTUnwrap(image.jpegData(compressionQuality: 0.9))
        let source = try XCTUnwrap(CGImageSourceCreateWithData(sourceData as CFData, nil))
        let orientedData = NSMutableData()
        let destination = try XCTUnwrap(
            CGImageDestinationCreateWithData(
                orientedData,
                UTType.jpeg.identifier as CFString,
                1,
                nil
            )
        )
        CGImageDestinationAddImageFromSource(
            destination,
            source,
            0,
            [kCGImagePropertyOrientation: 6] as CFDictionary
        )
        XCTAssertTrue(CGImageDestinationFinalize(destination))

        let input = orientedData as Data
        XCTAssertEqual(GatewayImageInspector.dimensions(of: input), GatewayImageDimensions(width: 20, height: 40))

        let prepared = try GatewayImagePreprocessor.prepare(data: input, mediaType: "image/jpeg")

        XCTAssertEqual(prepared.dimensions, GatewayImageDimensions(width: 20, height: 40))
        XCTAssertEqual(GatewayImageInspector.dimensions(of: prepared.data), GatewayImageDimensions(width: 20, height: 40))
        XCTAssertEqual(GatewayImageInspector.orientation(of: prepared.data), 1)
    }

    func testDecodesProtocolThreeImageCapability() throws {
        let json = #"{"kind":"hello","protocol":3,"capabilities":["images"],"authenticated":true}"#
        let frame = try GatewayWireDecoder.decode(Data(json.utf8))

        XCTAssertEqual(frame.protocol, 3)
        XCTAssertEqual(frame.capabilities, ["images"])
    }

    func testDecodesLiveImageReference() throws {
        let json = #"{"kind":"event","sessionId":"s1","seq":7,"time":1001,"event":{"type":"user/message","text":"描述它","source":"user","images":[{"attachmentId":"att-1","mediaType":"image/jpeg","bytes":12,"width":3,"height":4,"name":"photo.jpg"}]}}"#
        let frame = try GatewayWireDecoder.decode(Data(json.utf8))

        XCTAssertEqual(frame.event?.images?.first?.attachmentId, "att-1")
        XCTAssertEqual(frame.event?.images?.first?.mediaType, "image/jpeg")
    }

    func testDecodesAttachmentPayload() throws {
        let json = GatewayProtocolParityFixtures.imageAttachment
        let frame = try GatewayWireDecoder.decode(Data(json.utf8))

        XCTAssertEqual(frame.attachment?.attachmentId, "att-1")
        XCTAssertEqual(frame.data, "iVBORw0K")
    }

    func testDecodesToolEvent() throws {
        let json = #"{"kind":"event","sessionId":"s1","seq":4,"time":1000,"event":{"type":"tool/call","turn":1,"step":2,"callId":"c1","name":"Bash","arguments":{"cmd":"pwd"}}}"#
        let frame = try JSONDecoder().decode(GatewayFrame.self, from: Data(json.utf8))
        XCTAssertEqual(frame.sessionId, "s1")
        XCTAssertEqual(frame.event?.name, "Bash")
        XCTAssertEqual(frame.event?.arguments?.displayText.contains("pwd"), true)
    }

    func testDecodesAssistantChunk() throws {
        let json = #"{"kind":"event","sessionId":"s1","seq":5,"time":1001,"event":{"type":"assistant/chunk","turn":1,"step":2,"chunkType":"text-delta","text":"hello"}}"#
        let frame = try JSONDecoder().decode(GatewayFrame.self, from: Data(json.utf8))
        XCTAssertEqual(frame.event?.chunkType, "text-delta")
        XCTAssertEqual(frame.event?.text, "hello")
    }

    func testDecodesLiveEventWhenGatewayOmitsKind() throws {
        let json = GatewayProtocolParityFixtures.liveEventWithoutKind
        let frame = try GatewayWireDecoder.decode(Data(json.utf8))
        XCTAssertEqual(frame.kind, "event")
        XCTAssertEqual(frame.event?.type, "assistant/chunk")
        XCTAssertEqual(frame.event?.text, "thinking")
    }

    @MainActor
    func testKMPConversationShowsPluginPromptsAsCompactContextRows() throws {
        let records = [
            SessionEvent(sessionId: "s1", seq: 1, time: 1, event: GatewayEvent(type: "permission/preset")),
            SessionEvent(sessionId: "s1", seq: 2, time: 2, event: GatewayEvent(type: "user/message", text: "runtime", source: "plugin")),
            SessionEvent(sessionId: "s1", seq: 3, time: 3, event: GatewayEvent(type: "user/message", text: "你好", source: "user")),
            SessionEvent(sessionId: "s1", seq: 4, time: 4, event: GatewayEvent(type: "assistant/message", text: "你好！", reasoning: "思考")),
            SessionEvent(sessionId: "s1", seq: 5, time: 5, event: GatewayEvent(type: "tool/call", name: "Read"))
        ]
        let adapter = KMPConversationStoreAdapter()
        try adapter.replace(sessionID: "s1", events: records)
        let items = adapter.items(for: "s1")
        // Expected titles resolve through the same localized constants the
        // projection uses, so this passes under any test locale.
        XCTAssertEqual(items.map(\.title), [
            L10n.contextInjectionTitle("plugin"),
            L10n.userMessageTitle,
            "Think",
            "DeepSeek",
            "Read",
        ])
    }

    @MainActor
    func testKMPConversationRunCodeUsesJSONToolRendering() throws {
        let payload: JSONValue = .string(#"{"language":"python","code":"print(1)"}"#)
        let record = SessionEvent(
            sessionId: "s1",
            seq: 1,
            time: 1,
            event: GatewayEvent(type: "tool/call", name: "run_code", arguments: payload)
        )
        let adapter = KMPConversationStoreAdapter()
        try adapter.replace(sessionID: "s1", events: [record])
        let item = adapter.items(for: "s1").first
        XCTAssertEqual(item?.title, "run_code")
        XCTAssertEqual(item?.kind, .jsonTool)
        XCTAssertTrue(item?.text.contains("\"language\"") == true)
        XCTAssertTrue(item?.text.contains("\"python\"") == true)
    }

    @MainActor
    func testKMPTrajectoryAggregatesChunksAndPairsToolResult() throws {
        let events = [
            SessionEvent(sessionId: "s1", seq: 0, time: 1, event: GatewayEvent(type: "permission/preset")),
            SessionEvent(sessionId: "s1", seq: 1, time: 2, event: GatewayEvent(type: "user/message", text: "查文件", source: "user")),
            SessionEvent(sessionId: "s1", seq: 2, time: 3, event: GatewayEvent(type: "assistant/chunk", turn: 1, step: 1, text: "先", chunkType: "reasoning-delta")),
            SessionEvent(sessionId: "s1", seq: 3, time: 4, event: GatewayEvent(type: "assistant/chunk", turn: 1, step: 1, text: "读取", chunkType: "reasoning-delta")),
            SessionEvent(sessionId: "s1", seq: 4, time: 5, event: GatewayEvent(type: "assistant/message", turn: 1, step: 1, reasoning: "先读取", toolCalls: [])),
            SessionEvent(sessionId: "s1", seq: 5, time: 6, event: GatewayEvent(type: "tool/call", turn: 1, step: 1, callId: "c1", name: "Read", arguments: .object(["path": .string("a.swift")]))),
            SessionEvent(sessionId: "s1", seq: 6, time: 7, event: GatewayEvent(type: "tool/result", turn: 1, step: 1, callId: "c1", isError: false, preview: "contents"))
        ]
        let adapter = KMPTrajectoryStoreAdapter()
        try adapter.replace(sessionID: "s1", events: events)
        let nodes = adapter.nodes(for: "s1")
        XCTAssertEqual(nodes.map(\.kind), [.input, .request, .assistant, .tool])
        XCTAssertEqual(nodes[1].request?.number, 1)
        XCTAssertEqual(nodes[2].subtitle, "先读取")
        XCTAssertTrue(nodes[3].subtitle.contains("contents"))
        XCTAssertEqual(nodes[3].records.count, 2)
    }

    @MainActor
    func testKMPTrajectoryProjectsRequestUsageAndSubtool() throws {
        let headerData: JSONValue = .object([
            "header": .object([
                "config": .object([
                    "provider": .string("deepseek-official"),
                    "model": .string("deepseek-v4-flash"),
                    "reasoningEffort": .string("high")
                ]),
                "tools": .array([
                    .object([
                        "name": .string("run_code"),
                        "description": .string("Run code"),
                        "parameters": .object(["type": .string("object")])
                    ])
                ])
            ])
        ])
        let usage: JSONValue = .object([
            "inputTokens": .number(327),
            "cacheReadTokens": .number(9216),
            "outputTokens": .number(208),
            "reasoningTokens": .number(93)
        ])
        let events = [
            SessionEvent(sessionId: "s1", seq: 1, time: 1, event: GatewayEvent(type: "request/header", raw: headerData)),
            SessionEvent(sessionId: "s1", seq: 2, time: 2, event: GatewayEvent(type: "assistant/chunk", turn: 4, step: 1, text: "thinking", chunkType: "reasoning-delta")),
            SessionEvent(sessionId: "s1", seq: 3, time: 3, event: GatewayEvent(type: "assistant/message", turn: 4, step: 1, text: "done", usage: usage, toolCalls: [ToolCall(id: "c1", name: "run_code")])),
            SessionEvent(sessionId: "s1", seq: 4, time: 4, event: GatewayEvent(type: "tool/call", turn: 4, step: 1, callId: "c1", name: "run_code", arguments: .object(["code": .string("return 1")]))),
            SessionEvent(sessionId: "s1", seq: 5, time: 5, event: GatewayEvent(type: "tool/code-dispatch-start", name: "bash", arguments: .object(["command": .string("pwd")]), rootCallId: "c1", parentCallId: "c1", subCallId: "c1:code:1")),
            SessionEvent(sessionId: "s1", seq: 6, time: 6, event: GatewayEvent(type: "tool/code-dispatch", name: "bash", arguments: .object(["command": .string("pwd")]), isError: false, preview: "/tmp", rootCallId: "c1", parentCallId: "c1", subCallId: "c1:code:1"))
        ]
        let adapter = KMPTrajectoryStoreAdapter()
        try adapter.replace(sessionID: "s1", events: events)
        let nodes = adapter.nodes(for: "s1")
        XCTAssertEqual(nodes.map(\.kind), [.request, .assistant, .tool, .subtool])
        XCTAssertEqual(nodes[0].request?.usage.totalInput, 9543)
        XCTAssertEqual(nodes[0].request?.usage.content, 115)
        XCTAssertEqual(nodes[0].request?.subtoolCalls, 1)
        XCTAssertEqual(nodes[2].tool?.schema?["name"]?.stringValue, "run_code")
        XCTAssertEqual(nodes[3].tool?.hierarchy, "run_code")
        XCTAssertEqual(nodes[3].records.count, 2)
    }

    func testDecodesSessionListItems() throws {
        let json = #"{"kind":"sessions","items":[{"sessionId":"s1","updatedAt":1786937352,"running":true,"blank":false,"cwd":"/tmp/project","agentPreset":"standard"}]}"#
        let frame = try JSONDecoder().decode(GatewayFrame.self, from: Data(json.utf8))
        let item = try XCTUnwrap(frame.items?.first?.decode(GatewaySessionSummary.self))
        XCTAssertEqual(item.sessionId, "s1")
        XCTAssertTrue(item.running)
        XCTAssertEqual(item.cwd, "/tmp/project")
    }

    func testNormalizesRawHistoryMessage() throws {
        let json = #"{"kind":"history","events":[{"type":"user/message","seq":1,"time":1786937352,"data":{"content":[{"type":"text","text":"历史消息"}]}}],"hasMore":false}"#
        let frame = try JSONDecoder().decode(GatewayFrame.self, from: Data(json.utf8))
        let event = try XCTUnwrap(frame.events?.first?.normalized(sessionId: "s1"))
        XCTAssertEqual(event.event.type, "user/message")
        XCTAssertEqual(event.event.text, "历史消息")
    }

    @MainActor
    func testNormalizesRawHistoryImageReferenceAndProjectsWithKMP() throws {
        let json = GatewayProtocolParityFixtures.historyImage
        let frame = try GatewayWireDecoder.decode(Data(json.utf8))
        let event = try XCTUnwrap(frame.events?.first?.normalized(sessionId: "s1"))
        let adapter = KMPConversationStoreAdapter()
        try adapter.replace(sessionID: "s1", events: [event])
        let item = try XCTUnwrap(adapter.items(for: "s1").first)

        XCTAssertEqual(event.event.images?.first?.attachmentId, "att-history")
        XCTAssertEqual(item.kind, .user)
        XCTAssertEqual(item.text, "")
        XCTAssertEqual(item.images.first?.mediaType, "image/webp")
    }

    func testNormalizesRawSubtoolDispatch() throws {
        let json = #"{"kind":"history","events":[{"type":"tool/code-dispatch-start","seq":8,"time":1786937352,"data":{"rootCallId":"root","parentCallId":"root","subCallId":"root:code:1","name":"bash","arguments":{"command":"pwd"}}},{"type":"tool/code-dispatch","seq":9,"time":1786937353,"data":{"rootCallId":"root","parentCallId":"root","subCallId":"root:code:1","name":"bash","arguments":{"command":"pwd"},"isError":false,"content":[{"type":"text","text":"/tmp"}]}}],"hasMore":false}"#
        let frame = try JSONDecoder().decode(GatewayFrame.self, from: Data(json.utf8))
        let events = try XCTUnwrap(frame.events).map { $0.normalized(sessionId: "s1") }
        XCTAssertEqual(events[0].event.subCallId, "root:code:1")
        XCTAssertEqual(events[0].event.arguments?["command"]?.stringValue, "pwd")
        XCTAssertEqual(events[1].event.preview, "/tmp")
        XCTAssertEqual(events[1].event.isError, false)
    }

    func testDecodesSessionControlResponsesAndHistoryProjections() throws {
        let modelsJSON = #"{"kind":"models","current":{"provider":"deepseek-official","model":"deepseek-v4-flash","reasoningEffort":"high"},"routable":true,"groups":[{"id":"deepseek-official","name":"DeepSeek","models":[{"id":"deepseek-v4-flash","name":"DeepSeek-V4-Flash","reasoning":{"efforts":[{"id":"off","name":"Off"},{"id":"high","name":"High"}],"defaultEffort":"high"}}]}],"failures":[]}"#
        let models = try GatewayWireDecoder.decode(Data(modelsJSON.utf8))
        XCTAssertEqual(models.current?.model, "deepseek-v4-flash")
        let firstModelGroup = try XCTUnwrap(models.groups?.first?.decode(GatewayModelGroup.self))
        XCTAssertEqual(firstModelGroup.models.first?.reasoning?.efforts.map(\.id), ["off", "high"])

        let permissionsJSON = #"{"kind":"permission-options","namespace":{"value":{"defaultPreset":"workspace-write"}},"sessionPermissions":{"options":[{"value":"read-only","name":"read-only"},{"value":"danger-full-access","name":"danger-full-access"}],"currentValue":"danger-full-access"}}"#
        let permissions = try GatewayWireDecoder.decode(Data(permissionsJSON.utf8))
        XCTAssertEqual(permissions.sessionPermissions?.currentValue, "danger-full-access")
        XCTAssertEqual(permissions.sessionPermissions?.options?.count, 2)

        let usageJSON = #"{"kind":"context-usage","sessionId":"s1","asOfSeq":260245,"tokenUsage":{"uncachedInputTokens":613950,"outputTokens":268657,"cacheReadTokens":72057728,"cacheWriteTokens":0},"contextPressure":{"pressureTokens":434856,"projectedTokens":435317,"contextWindow":1000000}}"#
        let usage = try GatewayWireDecoder.decode(Data(usageJSON.utf8))
        XCTAssertEqual(usage.contextPressure?.pressureTokens, 434856)
        XCTAssertEqual(usage.contextPressure?.contextWindow, 1_000_000)

        let historyJSON = #"{"kind":"history","sessionId":"s1","events":[],"hasMore":false,"projections":{"asOfSeq":260245,"values":{"contextBreakdown":{"systemTokens":4404,"toolsTokens":8247,"messageTokens":357987},"permissions":{"currentValue":"workspace-write"}}}}"#
        let history = try GatewayWireDecoder.decode(Data(historyJSON.utf8))
        let breakdown = history.projections?["values"]?["contextBreakdown"]?.decode(GatewayContextBreakdown.self)
        XCTAssertEqual(breakdown?.toolsTokens, 8247)
        XCTAssertEqual(history.projections?["values"]?["permissions"]?["currentValue"]?.stringValue, "workspace-write")
    }

    func testDecodesReplayedHumanQuestionRequest() throws {
        let json = GatewayProtocolParityFixtures.replayedQuestionRequest
        let frame = try GatewayWireDecoder.decode(Data(json.utf8))

        XCTAssertEqual(frame.rpcId, "rpc-1")
        XCTAssertEqual(frame.sessionId, "s1")
        XCTAssertEqual(frame.replay, true)
        XCTAssertEqual(frame.questions?.count, 2)
        XCTAssertEqual(frame.questions?.first?.options?.first?.label, "核心架构 (推荐)")
        XCTAssertEqual(frame.questions?.first?.allowsMultipleSelections, true)
    }

    func testDecodesQuestionResponseAndResolution() throws {
        let response = try GatewayWireDecoder.decode(Data(#"{"kind":"question-response","rpcId":"rpc-1","sessionId":"s1","action":"answer","accepted":false,"reason":"not-pending"}"#.utf8))
        XCTAssertEqual(response.action, "answer")
        XCTAssertEqual(response.accepted, false)
        XCTAssertEqual(response.reason, "not-pending")

        let resolved = try GatewayWireDecoder.decode(Data(#"{"kind":"question-resolved","rpcId":"rpc-1","sessionId":"s1","outcome":"answered"}"#.utf8))
        XCTAssertEqual(resolved.outcome, "answered")
    }

    func testPairingPayloadParserAcceptsValidTrimmedPayload() throws {
        let now = Date(timeIntervalSince1970: 1_000)
        let encoded = try pairingPayloadString(
            publicURL: "wss://gateway.example/ws/mobile",
            expiresAt: 1_001_000
        )

        let payload = try PairingPayloadParser.parse("  \n\(encoded)\t", now: now)

        XCTAssertEqual(payload.version, 2)
        XCTAssertEqual(payload.publicUrl, "wss://gateway.example/ws/mobile")
        XCTAssertEqual(payload.pairingCode, "one-time-code")
    }

    func testPairingPayloadParserRejectsInvalidBase64URLAndJSON() throws {
        XCTAssertThrowsError(try PairingPayloadParser.parse("bad=value")) {
            XCTAssertEqual($0 as? PairingPayloadError, .invalidBase64URL)
        }
        let nonJSON = base64URL(Data("not-json".utf8))
        XCTAssertThrowsError(try PairingPayloadParser.parse(nonJSON)) {
            XCTAssertEqual($0 as? PairingPayloadError, .invalidJSON)
        }
    }

    func testPairingPayloadParserRejectsUnsupportedVersion() throws {
        let encoded = try pairingPayloadString(version: 3)
        XCTAssertThrowsError(try PairingPayloadParser.parse(encoded, now: .distantPast)) {
            XCTAssertEqual($0 as? PairingPayloadError, .unsupportedVersion(3))
        }
    }

    func testPairingPayloadParserRejectsInvalidEndpoint() throws {
        for endpoint in ["https://gateway.example/ws/mobile", "ws:///missing-host", "not-a-url"] {
            let encoded = try pairingPayloadString(publicURL: endpoint)
            XCTAssertThrowsError(try PairingPayloadParser.parse(encoded, now: .distantPast)) {
                XCTAssertEqual($0 as? PairingPayloadError, .invalidEndpoint)
            }
        }
    }

    func testPairingPayloadParserRejectsInvalidPairingCode() throws {
        for code in ["", "contains,comma", "contains space", "contains\nnewline"] {
            let encoded = try pairingPayloadString(pairingCode: code)
            XCTAssertThrowsError(try PairingPayloadParser.parse(encoded, now: .distantPast)) {
                XCTAssertEqual($0 as? PairingPayloadError, .invalidCode)
            }
        }
    }

    func testPairingPayloadParserRejectsExpiredPayloadAtBoundary() throws {
        let now = Date(timeIntervalSince1970: 1_000)
        let encoded = try pairingPayloadString(expiresAt: 1_000_000)
        XCTAssertThrowsError(try PairingPayloadParser.parse(encoded, now: now)) {
            XCTAssertEqual($0 as? PairingPayloadError, .expired)
        }
    }

    @MainActor
    func testKMPSessionListAdapterPublishesChangedSnapshotThroughEventStream() throws {
        let adapter = KMPSessionListStoreAdapter(sessions: [])
        var snapshots: [KMPSessionListSnapshot] = []
        adapter.onSnapshot = { snapshot, error in
            XCTAssertNil(error)
            if let snapshot { snapshots.append(snapshot) }
        }

        _ = try adapter.reduce(
            .knownSessionAdded("session-event"),
            now: Date(timeIntervalSince1970: 100)
        )
        _ = try adapter.reduce(
            .knownSessionAdded("session-event"),
            now: Date(timeIntervalSince1970: 100)
        )

        XCTAssertNotNil(adapter.lastEventSequence)
        XCTAssertEqual(snapshots.count, 1)
        XCTAssertEqual(snapshots.first?.sessions.first?.id, "session-event")
    }

    @MainActor
    func testKMPSessionListAdapterMapsPersistenceAndOwnsSessionStateTransitions() throws {
        let persisted = SessionSummary(
            id: "existing",
            title: "旧标题",
            lastActivity: Date(timeIntervalSince1970: 10),
            isRunning: false,
            hasUnread: true,
            agentPreset: "keep"
        )
        let adapter = KMPSessionListStoreAdapter(
            sessions: [persisted],
            selectedSessionId: "selected"
        )
        XCTAssertNil(adapter.initializationError)
        XCTAssertNil(adapter.runtimeError)
        XCTAssertTrue(adapter.isOperational)
        XCTAssertEqual(adapter.snapshot.persistedSessions, [persisted])

        _ = try adapter.reduce(.setArchivedSessionIDs(["archived"]))
        let remote = [
            GatewaySessionSummary(
                sessionId: "existing",
                updatedAt: 2_000,
                running: true,
                blank: false,
                cwd: "/tmp/existing"
            ),
            GatewaySessionSummary(
                sessionId: "new",
                updatedAt: 3_000,
                running: false,
                blank: false,
                cwd: "/tmp/new",
                agentPreset: "standard"
            ),
            GatewaySessionSummary(
                sessionId: "archived",
                updatedAt: 4_000,
                running: false,
                blank: false,
                cwd: "/tmp/archived"
            )
        ]
        var snapshot = try adapter.reduce(.remoteSessionsReceived(remote))
        XCTAssertEqual(snapshot.persistedSessions.map(\.id), ["new", "existing"])
        XCTAssertEqual(snapshot.persistedSessions.last?.agentPreset, "keep")
        XCTAssertEqual(snapshot.persistedSessions.last?.isRunning, true)
        XCTAssertEqual(snapshot.persistedSessions.last?.hasUnread, true)

        snapshot = try adapter.reduce(.eventReceived(SessionEvent(
            sessionId: "new",
            seq: 1,
            time: 3_001,
            event: GatewayEvent(type: "turn/start")
        )), now: Date(timeIntervalSince1970: 9_999))
        XCTAssertEqual(snapshot.persistedSessions.first?.id, "new")
        XCTAssertEqual(snapshot.persistedSessions.first?.isRunning, true)
        XCTAssertEqual(snapshot.persistedSessions.first?.hasUnread, true)

        _ = try adapter.reduce(.select("new"))
        snapshot = try adapter.reduce(.markRead("new"))
        XCTAssertEqual(snapshot.selectedSessionId, "new")
        XCTAssertEqual(snapshot.persistedSessions.first?.hasUnread, false)

        snapshot = try adapter.reduce(
            .knownSessionAdded("known"),
            now: Date(timeIntervalSince1970: 8_000)
        )
        XCTAssertEqual(
            snapshot.persistedSessions.first { $0.id == "known" }?.lastActivity,
            Date(timeIntervalSince1970: 8_000)
        )
    }

    @MainActor
    func testKMPSessionListAdapterFailsClosedAfterRestoreFailure() throws {
        let persisted = SessionSummary(
            id: "persisted",
            title: "必须保留",
            lastActivity: Date(timeIntervalSince1970: 123),
            isRunning: true,
            hasUnread: true
        )
        let bridge = FailingRestoreSessionListBridge()
        let adapter = KMPSessionListStoreAdapter(sessions: [persisted], bridge: bridge)

        XCTAssertFalse(adapter.isInitialized)
        XCTAssertNotNil(adapter.initializationError)
        XCTAssertEqual(adapter.snapshot.persistedSessions, [persisted])

        XCTAssertThrowsError(
            try adapter.reduce(
                .knownSessionAdded("must-not-reduce"),
                now: Date(timeIntervalSince1970: 9_999)
            )
        ) { error in
            guard case KMPSessionListStoreError.initializationFailed = error else {
                return XCTFail("预期 initializationFailed，实际为 \(error)")
            }
        }
        XCTAssertEqual(bridge.nonRestoreCallCount, 0)
        XCTAssertEqual(adapter.snapshot.persistedSessions, [persisted])
    }

    @MainActor
    func testKMPSessionListAdapterFailsClosedAfterMalformedRuntimeSnapshot() throws {
        let persisted = SessionSummary(
            id: "persisted",
            title: "必须保留",
            lastActivity: Date(timeIntervalSince1970: 123),
            isRunning: true,
            hasUnread: true
        )
        let bridge = MalformedMutationSessionListBridge()
        let adapter = KMPSessionListStoreAdapter(sessions: [persisted], bridge: bridge)

        XCTAssertTrue(adapter.isOperational)
        XCTAssertThrowsError(
            try adapter.reduce(
                .knownSessionAdded("hidden-kmp-session"),
                now: Date(timeIntervalSince1970: 9_999)
            )
        ) { error in
            guard case KMPSessionListStoreError.invalidSnapshot = error else {
                return XCTFail("预期 invalidSnapshot，实际为 \(error)")
            }
        }
        XCTAssertEqual(bridge.mutationCallCount, 1)
        XCTAssertNotNil(adapter.runtimeError)
        XCTAssertFalse(adapter.isOperational)
        XCTAssertEqual(adapter.snapshot.persistedSessions, [persisted])

        XCTAssertThrowsError(
            try adapter.reduce(
                .knownSessionAdded("must-not-reach-bridge"),
                now: Date(timeIntervalSince1970: 10_000)
            )
        ) { error in
            guard case KMPSessionListStoreError.runtimeFailed = error else {
                return XCTFail("预期 runtimeFailed，实际为 \(error)")
            }
        }
        XCTAssertEqual(bridge.mutationCallCount, 1)
        XCTAssertEqual(adapter.snapshot.persistedSessions, [persisted])
    }

    @MainActor
    func testAppStoreDoesNotPersistAfterMalformedKMPRuntimeSnapshot() {
        let persisted = SessionSummary(
            id: "persisted",
            title: "必须保留",
            lastActivity: Date(timeIntervalSince1970: 123),
            isRunning: false,
            hasUnread: false
        )
        let preferences = AppPreferencesSpy(
            endpoint: "wss://injected.example/ws/mobile",
            selectedWorkspaceID: nil,
            sessions: [persisted]
        )
        let bridge = MalformedMutationSessionListBridge()
        let store = AppStore(preferences: preferences, sessionListBridge: bridge)

        store.addKnownSession("hidden-kmp-session")

        XCTAssertEqual(store.sessions, [persisted])
        XCTAssertEqual(preferences.savedSessionSnapshots, [])
        XCTAssertEqual(bridge.mutationCallCount, 1)
        XCTAssertNotNil(store.lastError)

        store.addKnownSession("must-not-reach-bridge")

        XCTAssertEqual(store.sessions, [persisted])
        XCTAssertEqual(preferences.savedSessionSnapshots, [])
        XCTAssertEqual(bridge.mutationCallCount, 1)
    }

    @MainActor
    func testKMPQuestionAdapterPublishesStateAndEffectThroughEventStream() {
        let request = questionRequest()
        let answers = [
            GatewayQuestionAnswer(id: "direction", selected: ["架构"]),
            GatewayQuestionAnswer(id: "notes", selected: [], custom: "保持原生 UI")
        ]
        let adapter = KMPQuestionStoreAdapter()
        var pushed: [KMPQuestionTransition] = []
        adapter.onTransition = { pushed.append($0) }

        _ = adapter.reduce(.requestReceived(request))
        let returned = adapter.reduce(.submitAnswer(
            rpcID: request.rpcId,
            answers: answers,
            isConnected: true
        ))
        _ = adapter.reduce(.submitAnswer(
            rpcID: request.rpcId,
            answers: answers,
            isConnected: true
        ))

        XCTAssertNotNil(adapter.lastEventSequence)
        XCTAssertEqual(pushed.count, 2)
        XCTAssertEqual(pushed.last?.snapshot, returned.snapshot)
        XCTAssertEqual(pushed.last?.effect?.action, "answer")
    }

    @MainActor
    func testKMPQuestionAdapterOwnsStateAndEmitsEffectOnce() {
        let request = questionRequest()
        let answers = [
            GatewayQuestionAnswer(id: "direction", selected: ["架构"]),
            GatewayQuestionAnswer(id: "notes", selected: [], custom: "保持原生 UI")
        ]
        let adapter = KMPQuestionStoreAdapter()
        var transition = adapter.reduce(.requestReceived(request))
        XCTAssertNil(transition.error)
        XCTAssertEqual(transition.snapshot.pendingRequests, [request])
        XCTAssertEqual(transition.snapshot.platformStatuses[request.rpcId], .idle)

        transition = adapter.reduce(.submitAnswer(
            rpcID: request.rpcId,
            answers: answers,
            isConnected: true
        ))
        XCTAssertEqual(transition.snapshot.platformStatuses[request.rpcId], .submitting(.answer))
        XCTAssertEqual(transition.effect?.action, "answer")
        XCTAssertEqual(transition.effect?.answers, answers)

        // 第二次相同 intent 不能再次产生网络 effect。
        transition = adapter.reduce(.submitAnswer(
            rpcID: request.rpcId,
            answers: answers,
            isConnected: true
        ))
        XCTAssertNil(transition.effect)

        transition = adapter.reduce(.responseReceived(
            rpcID: request.rpcId,
            action: .answer,
            accepted: true,
            reason: nil
        ))
        XCTAssertEqual(transition.snapshot.platformStatuses[request.rpcId], .accepted(.answer))

        var replay = request
        replay.replay = true
        transition = adapter.reduce(.requestReceived(replay))
        XCTAssertEqual(transition.snapshot.pendingRequests, [replay])
        XCTAssertEqual(transition.snapshot.platformStatuses[request.rpcId], .accepted(.answer))

        transition = adapter.reduce(.resolved(rpcID: request.rpcId))
        XCTAssertTrue(transition.snapshot.pendingRequests.isEmpty)
        XCTAssertNil(transition.snapshot.platformStatuses[request.rpcId])
    }

    @MainActor
    func testKMPQuestionAdapterFailsClosedBeforeMalformedEffectCanExecute() {
        let bridge = MalformedMutationQuestionBridge()
        let adapter = KMPQuestionStoreAdapter(bridge: bridge)
        XCTAssertTrue(adapter.isOperational)

        let transition = adapter.reduce(.requestReceived(questionRequest()))
        XCTAssertNotNil(transition.error)
        XCTAssertNil(transition.effect)
        XCTAssertFalse(adapter.isOperational)
        XCTAssertEqual(bridge.mutationCallCount, 1)

        let second = adapter.reduce(.reset)
        XCTAssertNotNil(second.error)
        XCTAssertNil(second.effect)
        XCTAssertEqual(bridge.mutationCallCount, 1)
    }

    @MainActor
    func testAppStoreNeverExecutesSemanticallyMismatchedQuestionEffects() async {
        let request = questionRequest()
        let answers = [
            GatewayQuestionAnswer(id: "direction", selected: ["架构"]),
            GatewayQuestionAnswer(id: "notes", selected: [], custom: "保持原生 UI")
        ]

        for mismatch in SemanticQuestionEffectMismatch.allCases {
            let bridge = SemanticMismatchQuestionBridge(request: request, mismatch: mismatch)
            let executor = GatewayQuestionEffectExecutorSpy()
            let backgroundApplication = BackgroundTaskApplicationSpy()
            let backgroundController = AgentBackgroundExecutionController(application: backgroundApplication)
            let store = AppStore(
                preferences: AppPreferencesSpy(
                    endpoint: "wss://injected.example/ws/mobile",
                    selectedWorkspaceID: nil,
                    sessions: []
                ),
                questionBridge: bridge,
                questionEffectExecutor: executor,
                backgroundExecutionController: backgroundController
            )

            store.answerQuestion(request, answers: answers)
            await flushDeferredKMPEvents(in: store)

            XCTAssertEqual(executor.answerCalls.count, 0, "\(mismatch)")
            XCTAssertEqual(executor.cancelCallCount, 0, "\(mismatch)")
            XCTAssertEqual(bridge.submitCallCount, 1, "\(mismatch)")
            XCTAssertNotNil(store.lastError, "\(mismatch)")
            XCTAssertFalse(backgroundController.isAgentWorkActive, "\(mismatch)")

            // 一次语义错配后永久 fail closed，后续 intent 不再进入 KMP bridge。
            store.answerQuestion(request, answers: answers)
            XCTAssertEqual(bridge.submitCallCount, 1, "\(mismatch)")
            XCTAssertEqual(executor.answerCalls.count, 0, "\(mismatch)")
        }
    }

    @MainActor
    func testAppStoreCoordinatesQuestionAllowanceAcrossTerminalRoutes() async throws {
        func makeStore() async -> (
            store: AppStore,
            controller: AgentBackgroundExecutionController,
            executor: GatewayQuestionEffectExecutorSpy,
            request: GatewayPendingQuestionRequest
        ) {
            let request = questionRequest()
            let controller = AgentBackgroundExecutionController(application: BackgroundTaskApplicationSpy())
            let executor = GatewayQuestionEffectExecutorSpy()
            let store = AppStore(
                preferences: AppPreferencesSpy(
                    endpoint: "wss://injected.example/ws/mobile",
                    selectedWorkspaceID: nil,
                    sessions: []
                ),
                questionBridge: QuestionLifecycleBridge(request: request),
                questionEffectExecutor: executor,
                backgroundExecutionController: controller
            )
            store.answerQuestion(request, answers: [
                GatewayQuestionAnswer(id: "direction", selected: ["架构"]),
                GatewayQuestionAnswer(id: "notes", selected: [], custom: "保持原生 UI")
            ])
            await flushDeferredKMPEvents(in: store)
            XCTAssertEqual(executor.answerCalls.count, 1)
            XCTAssertEqual(controller.questionAllowanceSessionIDs[request.rpcId], request.sessionId)
            XCTAssertEqual(controller.outstandingTurns, 0)
            return (store, controller, executor, request)
        }

        do {
            let fixture = await makeStore()
            fixture.store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(
                #"{"kind":"question-response","rpcId":"rpc-question","action":"answer","accepted":false,"reason":"not-pending"}"#.utf8
            )))
            await flushDeferredKMPEvents(in: fixture.store)
            XCTAssertFalse(fixture.controller.isAgentWorkActive)
            XCTAssertEqual(fixture.controller.outstandingTurns, 0)
        }

        do {
            let fixture = await makeStore()
            fixture.store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(
                #"{"kind":"question-response","rpcId":"rpc-question","action":"answer","accepted":false,"reason":"bad-response"}"#.utf8
            )))
            await flushDeferredKMPEvents(in: fixture.store)
            XCTAssertFalse(fixture.controller.isAgentWorkActive)
            XCTAssertEqual(fixture.controller.outstandingTurns, 0)
        }

        do {
            let fixture = await makeStore()
            fixture.store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(
                #"{"kind":"error","requestType":"question-answer","rpcId":"rpc-question","sessionId":"session-question","code":"failed"}"#.utf8
            )))
            await flushDeferredKMPEvents(in: fixture.store)
            XCTAssertFalse(fixture.controller.isAgentWorkActive)
            XCTAssertEqual(fixture.controller.outstandingTurns, 0)
        }

        do {
            let fixture = await makeStore()
            fixture.store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(
                #"{"kind":"hello","protocol":3,"capabilities":[],"authenticated":true,"clients":1}"#.utf8
            )))
            await flushDeferredKMPEvents(in: fixture.store)
            XCTAssertFalse(fixture.controller.isAgentWorkActive)
            XCTAssertEqual(fixture.controller.outstandingTurns, 0)
        }

        do {
            let fixture = await makeStore()
            fixture.store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(
                #"{"kind":"question-response","rpcId":"rpc-question","action":"answer","accepted":true}"#.utf8
            )))
            await flushDeferredKMPEvents(in: fixture.store)
            XCTAssertTrue(fixture.controller.questionAllowanceSessionIDs.isEmpty)
            XCTAssertEqual(fixture.controller.outstandingTurns, 1)

            fixture.store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(
                #"{"kind":"question-resolved","rpcId":"rpc-question","sessionId":"session-question","outcome":"answered"}"#.utf8
            )))
            await flushDeferredKMPEvents(in: fixture.store)
            XCTAssertEqual(fixture.controller.outstandingTurns, 1)
            fixture.controller.turnEnded(sessionID: fixture.request.sessionId)
            XCTAssertFalse(fixture.controller.isAgentWorkActive)
        }
    }

    @MainActor
    func testQuestionFailureWithoutRpcIDFailsAndReleasesAllRequestsInSession() async throws {
        let controller = AgentBackgroundExecutionController(application: BackgroundTaskApplicationSpy())
        let store = AppStore(
            preferences: AppPreferencesSpy(
                endpoint: "wss://injected.example/ws/mobile",
                selectedWorkspaceID: nil,
                sessions: []
            ),
            backgroundExecutionController: controller
        )
        for (rpcID, sessionID) in [("rpc-s1-a", "s1"), ("rpc-s1-b", "s1"), ("rpc-s2", "s2")] {
            store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(
                #"{"kind":"question-requested","rpcId":"\#(rpcID)","sessionId":"\#(sessionID)","questions":[{"id":"q1","question":"继续？"}]}"#.utf8
            )))
            controller.beginQuestionAnswer(rpcID: rpcID, sessionID: sessionID)
        }

        store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(
            #"{"kind":"error","requestType":"question-answer","sessionId":"s1","code":"failed"}"#.utf8
        )))
        await flushDeferredKMPEvents(in: store)

        XCTAssertEqual(store.questionRequestStatuses["rpc-s1-a"], .rejected("failed"))
        XCTAssertEqual(store.questionRequestStatuses["rpc-s1-b"], .rejected("failed"))
        XCTAssertEqual(store.questionRequestStatuses["rpc-s2"], .idle)
        XCTAssertEqual(controller.questionAllowanceSessionIDs, ["rpc-s2": "s2"])
        XCTAssertEqual(store.pendingQuestionRequests.count, 3)
    }

    @MainActor
    func testAppStoreQuestionRoutePublishesKMPStateAndHelloResetsIt() async throws {
        let store = AppStore(preferences: AppPreferencesSpy(
            endpoint: "wss://injected.example/ws/mobile",
            selectedWorkspaceID: nil,
            sessions: []
        ))
        let requested = try GatewayWireDecoder.decode(Data(
            #"{"kind":"question-requested","rpcId":"rpc-app","sessionId":"s-app","questions":[{"id":"q1","question":"继续？","options":[{"label":"是"}]}]}"#.utf8
        ))
        store.gateway.onFrame?(requested)
        await flushDeferredKMPEvents(in: store)
        let request = try XCTUnwrap(store.pendingQuestionRequests.first)
        XCTAssertEqual(store.questionRequestStatuses[request.rpcId], .idle)

        store.answerQuestion(request, answers: [GatewayQuestionAnswer(id: "q1", selected: ["是"])])
        await flushDeferredKMPEvents(in: store)
        XCTAssertEqual(
            store.questionRequestStatuses[request.rpcId],
            .rejected(String(localized: "q.rejected.ws.disconnected.answer", defaultValue: "WebSocket 已断开，重连后再提交答案。"))
        )

        let notPending = try GatewayWireDecoder.decode(Data(
            #"{"kind":"question-response","rpcId":"rpc-app","action":"answer","accepted":false,"reason":"not-pending"}"#.utf8
        ))
        store.gateway.onFrame?(notPending)
        await flushDeferredKMPEvents(in: store)
        XCTAssertTrue(store.pendingQuestionRequests.isEmpty)
        XCTAssertNil(store.questionRequestStatuses[request.rpcId])

        store.gateway.onFrame?(requested)
        await flushDeferredKMPEvents(in: store)
        let hello = try GatewayWireDecoder.decode(Data(
            #"{"kind":"hello","protocol":3,"capabilities":[],"authenticated":true,"clients":1}"#.utf8
        ))
        store.gateway.onFrame?(hello)
        await flushDeferredKMPEvents(in: store)
        XCTAssertTrue(store.pendingQuestionRequests.isEmpty)
        XCTAssertTrue(store.questionRequestStatuses.isEmpty)
    }

    @MainActor
    func testKMPSessionControlAdapterPublishesMutationThroughEventStream() {
        let adapter = KMPSessionControlStoreAdapter()
        var pushed: [KMPSessionControlTransition] = []
        adapter.onTransition = { pushed.append($0) }

        let returned = adapter.reduce(.requestModels(
            sessionID: "session-event",
            isConnected: true
        ))

        XCTAssertNotNil(adapter.lastEventSequence)
        XCTAssertEqual(adapter.lastEventSequence, 1)
        XCTAssertEqual(pushed.count, 1)
        XCTAssertEqual(pushed.first?.snapshot, returned.snapshot)
        XCTAssertEqual(pushed.first?.effects.first?.sessionId, "session-event")

        _ = adapter.reduce(.requestModels(sessionID: "session-event", isConnected: true))
        XCTAssertEqual(pushed.count, 1, "unchanged intent 不得重复推送 UI event")
    }

    @MainActor
    func testKMPPushAdaptersFailClosedBeforePublishingDuplicateSequence() {
        let sessionList = KMPSessionListStoreAdapter(sessions: [])
        let question = KMPQuestionStoreAdapter()
        let control = KMPSessionControlStoreAdapter()

        sessionList.receive(SharedMviEvent(
            schema: 2, sequence: sessionList.lastEventSequence ?? 0,
            transactionId: "duplicate-list", domain: "session-list", kind: "transition",
            statePayloadJson: nil, effectsJson: "[]", metadataJson: nil,
            errorCode: nil, errorMessage: nil
        ))
        question.receive(SharedMviEvent(
            schema: 2, sequence: question.lastEventSequence ?? 0,
            transactionId: "duplicate-question", domain: "question", kind: "transition",
            statePayloadJson: nil, effectsJson: "[]", metadataJson: nil,
            errorCode: nil, errorMessage: nil
        ))
        control.receive(SharedMviEvent(
            schema: 2, sequence: control.lastEventSequence ?? 0,
            transactionId: "duplicate-control", domain: "session-control", kind: "transition",
            statePayloadJson: nil, effectsJson: "[]", metadataJson: nil,
            errorCode: nil, errorMessage: nil
        ))

        XCTAssertFalse(sessionList.isOperational)
        XCTAssertFalse(question.isOperational)
        XCTAssertFalse(control.isOperational)
    }

    @MainActor
    func testKMPSessionControlAdapterOwnsStateAndEmitsRequestEffectOnce() {
        let adapter = KMPSessionControlStoreAdapter()

        var transition = adapter.reduce(.requestModels(sessionID: "session-1", isConnected: true))
        XCTAssertNil(transition.error)
        XCTAssertEqual(transition.effects.count, 1)
        XCTAssertEqual(transition.effects.first?.kind, "models")
        XCTAssertEqual(transition.effects.first?.sessionId, "session-1")
        XCTAssertEqual(
            transition.snapshot.requestTokens["models"],
            transition.effects.first?.requestToken
        )

        // 尚未完成的同 kind 请求不得再次生成网络 I/O。
        transition = adapter.reduce(.requestModels(sessionID: "session-2", isConnected: true))
        XCTAssertNil(transition.error)
        XCTAssertTrue(transition.effects.isEmpty)
        XCTAssertEqual(transition.snapshot.pendingModelsSessionId, "session-1")
        XCTAssertEqual(transition.snapshot.queuedRequestTargets["models"]?.sessionId, "session-2")

        let selected = GatewayModelSelection(provider: "openai", model: "gpt-5", reasoningEffort: "high")
        let modelsAction = KMPSessionControlAction.modelsReceived(
            sessionID: "session-1",
            current: selected,
            routable: true,
            groups: [],
            isGlobalRequest: false
        )
        transition = adapter.reduce(.action(modelsAction))
        XCTAssertEqual(transition.snapshot.modelCatalogs["session-1"]?.current, selected)
        XCTAssertEqual(transition.snapshot.modelCatalogs["session-1"]?.routable, true)
        XCTAssertEqual(transition.effects.first?.sessionId, "session-2")
        XCTAssertEqual(transition.snapshot.pendingModelsSessionId, "session-2")
        XCTAssertFalse(transition.snapshot.explicitSessionRequiredKinds.contains("models"))

        transition = adapter.reduce(.action(.modelsReceived(
            sessionID: "session-2",
            current: nil,
            routable: true,
            groups: [],
            isGlobalRequest: false
        )))
        XCTAssertTrue(transition.snapshot.loadingKinds.isEmpty)
        XCTAssertNil(transition.snapshot.requestTokens["models"])
        XCTAssertNil(transition.snapshot.pendingModelsSessionId)

        let permissions = GatewaySessionPermissions(
            options: [
                GatewayPermissionOption(value: "read-only", name: "Read"),
                GatewayPermissionOption(value: "future", name: "Future")
            ],
            currentValue: "read-only"
        )
        let permissionsAction = KMPSessionControlAction.permissionsReceived(
            sessionID: "session-1",
            permissions: permissions
        )
        _ = adapter.reduce(.requestPermissionOptions(sessionID: "session-1", isConnected: true))
        transition = adapter.reduce(.action(permissionsAction))
        XCTAssertEqual(transition.snapshot.sessionPermissions["session-1"]?.currentValue, "read-only")
        XCTAssertEqual(
            transition.snapshot.sessionPermissions["session-1"]?.options?.map(\.value),
            ["read-only"]
        )
    }

    @MainActor
    func testKMPSessionControlAdapterMergesHistoryAndRealtimePartialSnapshots() {
        let adapter = KMPSessionControlStoreAdapter()
        _ = adapter.reduce(.projection(.contextReceived(
            sessionID: "session-1",
            asOfSequence: 10,
            tokenUsage: GatewayTokenUsage(uncachedInputTokens: 12),
            pressure: nil,
            breakdown: GatewayContextBreakdown(systemTokens: 4)
        )))
        var transition = adapter.reduce(.projection(.contextReceived(
            sessionID: "session-1",
            asOfSequence: nil,
            tokenUsage: nil,
            pressure: GatewayContextPressure(pressureTokens: 30, contextWindow: 100),
            breakdown: nil
        )))
        XCTAssertEqual(transition.snapshot.contextSnapshots["session-1"]?.asOfSeq, 10)
        XCTAssertEqual(
            transition.snapshot.contextSnapshots["session-1"]?.tokenUsage?.uncachedInputTokens,
            12
        )
        XCTAssertEqual(transition.snapshot.contextSnapshots["session-1"]?.pressure?.pressureTokens, 30)
        XCTAssertEqual(transition.snapshot.contextSnapshots["session-1"]?.breakdown?.systemTokens, 4)

        _ = adapter.reduce(.projection(.statsReceived(
            sessionID: "session-1",
            asOfSequence: 20,
            stats: GatewaySessionStats(turns: 2, steps: 5),
            tokenUsageTotals: GatewaySessionTokenUsageTotals(inputTokens: 100),
            contextPressure: nil
        )))
        transition = adapter.reduce(.projection(.statsReceived(
            sessionID: "session-1",
            asOfSequence: nil,
            stats: nil,
            tokenUsageTotals: nil,
            contextPressure: GatewayContextPressure(projectedTokens: 80, contextWindow: 1_000)
        )))
        XCTAssertEqual(transition.snapshot.sessionStatsSnapshots["session-1"]?.stats?.turns, 2)
        XCTAssertEqual(
            transition.snapshot.sessionStatsSnapshots["session-1"]?.tokenUsage?.totals?.inputTokens,
            100
        )
        XCTAssertEqual(
            transition.snapshot.sessionStatsSnapshots["session-1"]?.contextPressure?.projectedTokens,
            80
        )

        let largeSequence = Int(Int32.max) + 42
        transition = adapter.reduce(.projection(.contextReceived(
            sessionID: "session-1",
            asOfSequence: largeSequence,
            tokenUsage: nil,
            pressure: nil,
            breakdown: nil
        )))
        XCTAssertEqual(transition.snapshot.contextSnapshots["session-1"]?.asOfSeq, largeSequence)
    }

    @MainActor
    func testKMPSessionControlPatchOnlyPublishesAffectedSessionAndSkipsUnchangedMutation() {
        let adapter = KMPSessionControlStoreAdapter()
        _ = adapter.reduce(.projection(.contextReceived(
            sessionID: "session-a",
            asOfSequence: 1,
            tokenUsage: GatewayTokenUsage(uncachedInputTokens: 1),
            pressure: nil,
            breakdown: nil
        )))
        _ = adapter.reduce(.projection(.contextReceived(
            sessionID: "session-b",
            asOfSequence: 2,
            tokenUsage: GatewayTokenUsage(uncachedInputTokens: 2),
            pressure: nil,
            breakdown: nil
        )))

        var transition = adapter.reduce(.projection(.contextReceived(
            sessionID: "session-a",
            asOfSequence: 3,
            tokenUsage: nil,
            pressure: GatewayContextPressure(pressureTokens: 30, contextWindow: 100),
            breakdown: nil
        )))
        XCTAssertNil(transition.error)
        XCTAssertEqual(
            Set(transition.patch?.contextSnapshotsUpsert.keys.map { $0 } ?? []),
            ["session-a"]
        )
        XCTAssertTrue(transition.patch?.sessionStatsSnapshotsUpsert.isEmpty == true)
        XCTAssertEqual(transition.snapshot.contextSnapshots["session-b"]?.asOfSeq, 2)

        transition = adapter.reduce(.projection(.contextReceived(
            sessionID: "session-a",
            asOfSequence: 3,
            tokenUsage: nil,
            pressure: GatewayContextPressure(pressureTokens: 30, contextWindow: 100),
            breakdown: nil
        )))
        XCTAssertNil(transition.error)
        XCTAssertTrue(transition.applied)
        XCTAssertFalse(transition.committed)
        XCTAssertNil(transition.patch)
    }

    @MainActor
    func testKMPSessionControlClearDrainsOldNilTerminalThenStartsQueuedTarget() {
        let adapter = KMPSessionControlStoreAdapter()
        let active = adapter.reduce(.requestModels(sessionID: "session-a", isConnected: true))
        _ = adapter.reduce(.requestModels(sessionID: "session-b", isConnected: true))

        let cleared = adapter.reduce(.clearSessionData(sessionID: "session-a"))
        XCTAssertNil(cleared.error)
        XCTAssertTrue(cleared.retiredRequestKinds.isEmpty)
        XCTAssertTrue(cleared.effects.isEmpty)
        XCTAssertEqual(active.effects.count, 1)
        XCTAssertEqual(cleared.snapshot.requestTokens["models"], active.effects.first?.requestToken)
        XCTAssertEqual(cleared.snapshot.activeRequestTargets["models"]?.sessionId, "session-a")
        XCTAssertEqual(cleared.snapshot.queuedRequestTargets["models"]?.sessionId, "session-b")
        XCTAssertTrue(cleared.snapshot.drainingRequestKinds.contains("models"))

        let drained = adapter.reduce(.action(.modelsReceived(
            sessionID: nil, current: nil, routable: true, groups: [], isGlobalRequest: false
        )))
        XCTAssertTrue(drained.applied)
        XCTAssertEqual(drained.effects.count, 1)
        XCTAssertEqual(drained.effects.first?.sessionId, "session-b")
        XCTAssertNil(drained.snapshot.modelCatalogs["session-a"])
        XCTAssertFalse(drained.snapshot.drainingRequestKinds.contains("models"))
        XCTAssertFalse(drained.snapshot.explicitSessionRequiredKinds.contains("models"))

        let completed = adapter.reduce(.action(.modelsReceived(
            sessionID: nil, current: nil, routable: true, groups: [], isGlobalRequest: false
        )))
        XCTAssertTrue(completed.applied)
        XCTAssertNil(completed.snapshot.activeRequestTargets["models"])
        XCTAssertNotNil(completed.snapshot.modelCatalogs["session-b"])
    }

    @MainActor
    func testKMPSessionControlPermissionOptionsNilTerminalUsesSameDrainBoundary() {
        let adapter = KMPSessionControlStoreAdapter()
        _ = adapter.reduce(.requestPermissionOptions(sessionID: "session-a", isConnected: true))
        _ = adapter.reduce(.requestPermissionOptions(sessionID: "session-b", isConnected: true))
        _ = adapter.reduce(.clearSessionData(sessionID: "session-a"))
        let permissions = GatewaySessionPermissions(
            options: [], currentValue: "read-only"
        )

        let drained = adapter.reduce(.action(.permissionsReceived(
            sessionID: nil, permissions: permissions
        )))
        XCTAssertEqual(drained.effects.first?.sessionId, "session-b")
        XCTAssertNil(drained.snapshot.sessionPermissions["session-a"])
        let completed = adapter.reduce(.action(.permissionsReceived(
            sessionID: nil, permissions: permissions
        )))
        XCTAssertTrue(completed.applied)
        XCTAssertEqual(completed.snapshot.sessionPermissions["session-b"]?.currentValue, "read-only")
    }

    @MainActor
    func testKMPSessionControlDrainingTimeoutQuarantinesWithoutQueuedIO() {
        let adapter = KMPSessionControlStoreAdapter()
        let active = adapter.reduce(.requestModels(sessionID: "session-a", isConnected: true))
        _ = adapter.reduce(.requestModels(sessionID: "session-b", isConnected: true))
        _ = adapter.reduce(.clearSessionData(sessionID: "session-a"))
        let token = active.effects.first!.requestToken

        let timedOut = adapter.reduce(.requestTimedOut(
            kind: "models", isDefault: false, requestToken: token
        ))
        XCTAssertNil(timedOut.error)
        XCTAssertTrue(timedOut.effects.isEmpty)
        XCTAssertNil(timedOut.snapshot.activeRequestTargets["models"])
        XCTAssertNil(timedOut.snapshot.queuedRequestTargets["models"])
        XCTAssertTrue(timedOut.snapshot.quarantinedRequestKinds.contains("models"))

        for sessionID in [nil, "session-a"] as [String?] {
            let late = adapter.reduce(.action(.modelsReceived(
                sessionID: sessionID, current: nil, routable: true, groups: [], isGlobalRequest: false
            )))
            XCTAssertFalse(late.applied)
            XCTAssertNil(late.snapshot.modelCatalogs["session-a"])
            XCTAssertTrue(late.effects.isEmpty)
        }
        let retry = adapter.reduce(.requestModels(sessionID: "session-b", isConnected: true))
        guard case .bridge(let code, _) = retry.error else {
            return XCTFail("quarantine 中不得发送 queued/retry 请求")
        }
        XCTAssertEqual(code, "request-quarantined")
        XCTAssertTrue(adapter.reduce(.requestsDisconnected).snapshot.quarantinedRequestKinds.isEmpty)
    }

    @MainActor
    func testKMPSessionControlBatchClearDropsAlsoClearedQueuedTargetWithoutEffect() {
        for sessionIDs in [Set(["session-a", "session-b"]), Set(["session-b", "session-a"])] {
            let adapter = KMPSessionControlStoreAdapter()
            _ = adapter.reduce(.requestModels(sessionID: "session-a", isConnected: true))
            _ = adapter.reduce(.requestModels(sessionID: "session-b", isConnected: true))

            let cleared = adapter.reduce(.clearSessionsData(sessionIDs: sessionIDs))
            XCTAssertNil(cleared.error)
            XCTAssertTrue(cleared.effects.isEmpty)
            XCTAssertEqual(cleared.snapshot.activeRequestTargets["models"]?.sessionId, "session-a")
            XCTAssertNil(cleared.snapshot.queuedRequestTargets["models"])
            XCTAssertTrue(cleared.snapshot.drainingRequestKinds.contains("models"))

            let drained = adapter.reduce(.action(.modelsReceived(
                sessionID: nil, current: nil, routable: true, groups: [], isGlobalRequest: false
            )))
            XCTAssertTrue(drained.effects.isEmpty)
            XCTAssertNil(drained.snapshot.activeRequestTargets["models"])
        }
    }

    @MainActor
    func testKMPSessionControlLegacyNoOpsDoNotRequestFullSnapshot() {
        let bridge = MalformedSessionControlBridge()
        let adapter = KMPSessionControlStoreAdapter(bridge: bridge)
        XCTAssertEqual(bridge.snapshotCallCount, 1)

        let resolved = adapter.reduce(.action(.modelSelectionResolved))
        let missingFinish = adapter.reduce(.action(.requestFinished("models")))
        XCTAssertNil(resolved.patch)
        XCTAssertNil(missingFinish.patch)
        XCTAssertFalse(resolved.committed)
        XCTAssertFalse(missingFinish.committed)
        XCTAssertEqual(bridge.snapshotCallCount, 1)
    }

    @MainActor
    func testKMPSessionControlPatchAppliesRemovalAndMalformedPatchFailsAtomically() {
        let adapter = KMPSessionControlStoreAdapter()
        _ = adapter.reduce(.projection(.contextReceived(
            sessionID: "session-a", asOfSequence: 1,
            tokenUsage: nil, pressure: nil, breakdown: nil
        )))
        _ = adapter.reduce(.projection(.contextReceived(
            sessionID: "session-b", asOfSequence: 2,
            tokenUsage: nil, pressure: nil, breakdown: nil
        )))
        let removed = adapter.reduce(.clearSessionData(sessionID: "session-a"))
        XCTAssertNil(removed.error)
        XCTAssertNil(removed.snapshot.contextSnapshots["session-a"])
        XCTAssertEqual(removed.snapshot.contextSnapshots["session-b"]?.asOfSeq, 2)
        XCTAssertEqual(removed.patch?.contextSnapshotsRemove, ["session-a"])

        var malformed = KMPSessionControlPatch.empty
        malformed.contextSnapshotsUpsert = ["session-b": GatewayContextSnapshot(asOfSeq: 9)]
        let malformedAdapter = KMPSessionControlStoreAdapter(
            bridge: PatchQueueSessionControlBridge(patches: [malformed])
        )
        let original = malformedAdapter.snapshot
        let failed = malformedAdapter.reduce(.projection(.contextReceived(
            sessionID: "session-a", asOfSequence: nil,
            tokenUsage: nil, pressure: nil, breakdown: nil
        )))
        guard case .invalidPatch = failed.error else {
            return XCTFail("跨 session 注入必须以 invalidPatch fail-closed")
        }
        XCTAssertFalse(malformedAdapter.isOperational)
        XCTAssertEqual(failed.snapshot, original)
        XCTAssertTrue(failed.effects.isEmpty)
    }

    @MainActor
    func testKMPSessionControlClearRejectsPatchThatOmitsExistingRemoval() {
        let adapter = KMPSessionControlStoreAdapter(
            bridge: ClearOmissionSessionControlBridge()
        )
        let original = adapter.snapshot

        let transition = adapter.reduce(.clearSessionData(sessionID: "session-a"))

        guard case .invalidPatch = transition.error else {
            return XCTFail("clear patch 遗漏既有 session 数据 removal 必须 fail-closed")
        }
        XCTAssertEqual(transition.snapshot, original)
        XCTAssertNotNil(transition.snapshot.contextSnapshots["session-a"])
        XCTAssertTrue(transition.effects.isEmpty)
        XCTAssertFalse(adapter.isOperational)
    }

    @MainActor
    func testKMPSessionControlDrainRejectsBusinessProjectionFromTombstoneResponse() {
        let adapter = KMPSessionControlStoreAdapter(
            bridge: DrainBusinessInjectionSessionControlBridge()
        )
        let original = adapter.snapshot

        let transition = adapter.reduce(.action(.modelsReceived(
            sessionID: nil, current: nil, routable: true, groups: [], isGlobalRequest: false
        )))

        guard case .invalidPatch = transition.error else {
            return XCTFail("drain tombstone 终态携带业务 upsert 必须 fail-closed")
        }
        XCTAssertEqual(transition.snapshot, original)
        XCTAssertNil(transition.snapshot.modelCatalogs["session-a"])
        XCTAssertTrue(transition.effects.isEmpty)
        XCTAssertFalse(adapter.isOperational)
    }

    @MainActor
    func testKMPSessionControlPatchRejectsUnknownSchemaAndUnknownFields() throws {
        func encoded(_ patch: KMPSessionControlPatch, mutate: (inout [String: Any]) -> Void) throws -> String {
            var object = try XCTUnwrap(
                JSONSerialization.jsonObject(with: JSONEncoder().encode(patch)) as? [String: Any]
            )
            mutate(&object)
            return String(decoding: try JSONSerialization.data(withJSONObject: object), as: UTF8.self)
        }
        var valid = KMPSessionControlPatch.empty
        valid.contextSnapshotsUpsert = ["session-a": GatewayContextSnapshot(asOfSeq: 1)]
        let payloads = [
            try encoded(valid) { $0["futureTopLevel"] = true },
            try encoded(valid) { object in
                var contexts = object["contextSnapshotsUpsert"] as! [String: Any]
                var context = contexts["session-a"] as! [String: Any]
                context["futureNested"] = 1
                contexts["session-a"] = context
                object["contextSnapshotsUpsert"] = contexts
            },
            try encoded({
                var patch = valid
                patch.schema = 3
                return patch
            }()) { _ in }
        ]

        for payload in payloads {
            let adapter = KMPSessionControlStoreAdapter(
                bridge: RawPatchSessionControlBridge(payload: payload)
            )
            let original = adapter.snapshot
            let transition = adapter.reduce(.projection(.contextReceived(
                sessionID: "session-a", asOfSequence: 1,
                tokenUsage: nil, pressure: nil, breakdown: nil
            )))
            guard case .invalidPatch = transition.error else {
                return XCTFail("未知 schema/字段必须以 invalidPatch fail-closed")
            }
            XCTAssertEqual(transition.snapshot, original)
            XCTAssertTrue(transition.effects.isEmpty)
            XCTAssertFalse(adapter.isOperational)
        }
    }

    @MainActor
    func testAppStoreArchiveClearsKMPControlStateAndRejectsLateResponse() async throws {
        let bridge = SharedSessionControlStore()
        _ = bridge.mergeContextProjection(
            sessionId: "session-a", asOfSequence: KotlinLong(longLong: 1),
            tokenUsageJson: nil, pressureJson: nil, breakdownJson: nil
        )
        _ = bridge.requestModels(sessionId: "session-a", isConnected: true)
        let store = AppStore(
            preferences: AppPreferencesSpy(
                endpoint: "wss://injected.example/ws/mobile",
                selectedWorkspaceID: nil,
                sessions: [SessionSummary(
                    id: "session-a",
                    title: "A",
                    lastActivity: Date(timeIntervalSince1970: 1),
                    isRunning: false,
                    hasUnread: false
                )]
            ),
            sessionControlBridge: bridge
        )

        store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(
            #"{"kind":"workspaces","items":[],"archivedSessionIds":["session-a"]}"#.utf8
        )))
        await flushDeferredKMPEvents(in: store)
        var snapshot = try JSONDecoder().decode(
            KMPSessionControlSnapshot.self,
            from: Data(try XCTUnwrap(bridge.snapshot().snapshotJson).utf8)
        )
        XCTAssertNil(snapshot.contextSnapshots["session-a"])
        XCTAssertEqual(snapshot.activeRequestTargets["models"]?.sessionId, "session-a")
        XCTAssertNotNil(snapshot.requestTokens["models"])
        XCTAssertTrue(snapshot.drainingRequestKinds.contains("models"))

        _ = bridge.modelsReceived(
            sessionId: nil, currentJson: nil, routable: true,
            groupsJson: "[]", isGlobalRequest: false
        )
        snapshot = try JSONDecoder().decode(
            KMPSessionControlSnapshot.self,
            from: Data(try XCTUnwrap(bridge.snapshot().snapshotJson).utf8)
        )
        XCTAssertNil(snapshot.modelCatalogs["session-a"])
        XCTAssertNil(snapshot.activeRequestTargets["models"])
        XCTAssertFalse(snapshot.drainingRequestKinds.contains("models"))
    }

    @MainActor
    func testAppStoreBatchArchiveDrainsBeforeRoutingReplacementEffect() async throws {
        let bridge = SharedSessionControlStore()
        _ = bridge.requestModels(sessionId: "session-a", isConnected: true)
        _ = bridge.requestModels(sessionId: "session-b", isConnected: true)
        let executor = SessionControlEffectExecutorSpy()
        let store = AppStore(
            preferences: AppPreferencesSpy(
                endpoint: "wss://injected.example/ws/mobile",
                selectedWorkspaceID: nil,
                sessions: [
                    SessionSummary(id: "session-a", title: "A", lastActivity: .now, isRunning: false, hasUnread: false),
                    SessionSummary(id: "session-b", title: "B", lastActivity: .now, isRunning: false, hasUnread: false)
                ]
            ),
            sessionControlBridge: bridge,
            sessionControlEffectExecutor: executor
        )

        store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(
            #"{"kind":"workspaces","items":[],"archivedSessionIds":["session-a"]}"#.utf8
        )))
        await flushDeferredKMPEvents(in: store)
        XCTAssertTrue(executor.modelsTargets.isEmpty)

        // A 的真实 nil-session 终态只解除 drain；随后才发送仍存活的 B。
        store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(
            #"{"kind":"models","groups":[],"routable":true}"#.utf8
        )))
        await flushDeferredKMPEvents(in: store)
        XCTAssertEqual(executor.modelsTargets, ["session-b"])
        XCTAssertNil(store.modelCatalogs["session-a"])

        store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(
            #"{"kind":"models","groups":[],"routable":true}"#.utf8
        )))
        await flushDeferredKMPEvents(in: store)
        XCTAssertNotNil(store.modelCatalogs["session-b"])
    }

    @MainActor
    func testAppStoreBatchArchiveDoesNotStartQueuedSessionAlsoInClearSet() async throws {
        let bridge = SharedSessionControlStore()
        _ = bridge.requestModels(sessionId: "session-a", isConnected: true)
        _ = bridge.requestModels(sessionId: "session-b", isConnected: true)
        let executor = SessionControlEffectExecutorSpy()
        let store = AppStore(
            preferences: AppPreferencesSpy(
                endpoint: "wss://injected.example/ws/mobile",
                selectedWorkspaceID: nil,
                sessions: [
                    SessionSummary(id: "session-a", title: "A", lastActivity: .now, isRunning: false, hasUnread: false),
                    SessionSummary(id: "session-b", title: "B", lastActivity: .now, isRunning: false, hasUnread: false)
                ]
            ),
            sessionControlBridge: bridge,
            sessionControlEffectExecutor: executor
        )

        store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(
            #"{"kind":"workspaces","items":[],"archivedSessionIds":["session-b","session-a"]}"#.utf8
        )))
        await flushDeferredKMPEvents(in: store)
        XCTAssertTrue(executor.modelsTargets.isEmpty)
        store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(
            #"{"kind":"models","groups":[],"routable":true}"#.utf8
        )))
        await flushDeferredKMPEvents(in: store)
        XCTAssertTrue(executor.modelsTargets.isEmpty)
        XCTAssertNil(bridge.snapshot().snapshotJson.flatMap { Data($0.utf8) }.flatMap {
            try? JSONDecoder().decode(KMPSessionControlSnapshot.self, from: $0)
        }?.activeRequestTargets["models"])
    }

    @MainActor
    func testKMPSessionControlRepeatedLegacyResponsesRemainOperational() {
        let adapter = KMPSessionControlStoreAdapter()
        let permissions = GatewaySessionPermissions(
            options: [GatewayPermissionOption(value: "read-only", name: "Read")],
            currentValue: "read-only"
        )

        _ = adapter.reduce(.requestModels(sessionID: "session-1", isConnected: true))
        _ = adapter.reduce(.action(.modelsReceived(
            sessionID: nil,
            current: nil,
            routable: true,
            groups: [],
            isGlobalRequest: false
        )))
        var transition = adapter.reduce(.requestModels(sessionID: "session-1", isConnected: true))
        XCTAssertNil(transition.error)
        XCTAssertFalse(transition.snapshot.explicitSessionRequiredKinds.contains("models"))
        transition = adapter.reduce(.action(.modelsReceived(
            sessionID: nil,
            current: nil,
            routable: true,
            groups: [],
            isGlobalRequest: false
        )))
        XCTAssertNil(transition.error)

        _ = adapter.reduce(.requestPermissionOptions(sessionID: "session-1", isConnected: true))
        _ = adapter.reduce(.action(.permissionsReceived(sessionID: nil, permissions: permissions)))
        transition = adapter.reduce(.requestPermissionOptions(sessionID: "session-1", isConnected: true))
        XCTAssertNil(transition.error)
        XCTAssertFalse(transition.snapshot.explicitSessionRequiredKinds.contains("permission-options"))
        transition = adapter.reduce(.action(.permissionsReceived(sessionID: nil, permissions: permissions)))
        XCTAssertNil(transition.error)
        XCTAssertTrue(adapter.isOperational)
        XCTAssertFalse(transition.snapshot.loadingKinds.contains("permission-options"))
    }

    @MainActor
    func testKMPSessionControlTimeoutKeepsSnapshotInvariantValidAndAllowsRetry() {
        let adapter = KMPSessionControlStoreAdapter()
        _ = adapter.reduce(.requestModels(sessionID: "session-a", isConnected: true))
        _ = adapter.reduce(.action(.modelsReceived(
            sessionID: "session-a",
            current: nil,
            routable: true,
            groups: [],
            isGlobalRequest: false
        )))
        let started = adapter.reduce(.requestModels(sessionID: "session-b", isConnected: true))
        let token = try! XCTUnwrap(started.snapshot.requestTokens["models"])
        XCTAssertFalse(started.snapshot.explicitSessionRequiredKinds.contains("models"))

        let timedOut = adapter.reduce(.requestTimedOut(
            kind: "models",
            isDefault: false,
            requestToken: token
        ))
        XCTAssertNil(timedOut.error)
        XCTAssertTrue(adapter.isOperational)
        XCTAssertFalse(timedOut.snapshot.quarantinedRequestKinds.contains("models"))
        XCTAssertFalse(timedOut.snapshot.explicitSessionRequiredKinds.contains("models"))
        XCTAssertNil(timedOut.snapshot.activeRequestTargets["models"])

        let retried = adapter.reduce(.requestModels(sessionID: "session-b", isConnected: true))
        XCTAssertNil(retried.error)
        XCTAssertEqual(retried.effects.first?.sessionId, "session-b")

        let permissionStarted = adapter.reduce(
            .requestPermissionOptions(sessionID: "session-b", isConnected: true)
        )
        let permissionToken = try! XCTUnwrap(
            permissionStarted.snapshot.requestTokens["permission-options"]
        )
        let permissionTimedOut = adapter.reduce(.requestTimedOut(
            kind: "permission-options",
            isDefault: false,
            requestToken: permissionToken
        ))
        XCTAssertFalse(
            permissionTimedOut.snapshot.quarantinedRequestKinds.contains("permission-options")
        )
        XCTAssertEqual(
            adapter.reduce(.requestPermissionOptions(
                sessionID: "session-b",
                isConnected: true
            )).effects.first?.sessionId,
            "session-b"
        )
    }

    @MainActor
    func testKMPSessionControlAdapterPermanentlyFailsClosedAfterMalformedCommittedSnapshot() {
        let bridge = MalformedSessionControlBridge()
        let adapter = KMPSessionControlStoreAdapter(bridge: bridge)
        XCTAssertTrue(adapter.isOperational)

        var transition = adapter.reduce(.requestModels(sessionID: "session-1", isConnected: true))
        guard case .invalidPatch = transition.error else {
            return XCTFail("结构缺失的已提交 payload 必须报 invalidPatch")
        }
        XCTAssertTrue(transition.effects.isEmpty)
        XCTAssertFalse(adapter.isOperational)
        XCTAssertEqual(bridge.requestCallCount, 1)

        transition = adapter.reduce(.requestModels(sessionID: "session-2", isConnected: true))
        XCTAssertNotNil(transition.error)
        XCTAssertTrue(transition.effects.isEmpty)
        XCTAssertEqual(bridge.requestCallCount, 1)
    }

    @MainActor
    func testKMPSessionControlAdapterPermanentlyFailsClosedOnInconsistentResultSignals() {
        for defect in InvalidSessionControlResultBridge.Defect.allCases {
            let bridge = InvalidSessionControlResultBridge(defect: defect)
            let adapter = KMPSessionControlStoreAdapter(bridge: bridge)
            let original = adapter.snapshot

            var transition = adapter.reduce(.requestModels(sessionID: "session-1", isConnected: true))
            switch defect {
            case .uncommittedSnapshotMutation, .committedWithoutMutation:
                guard case .invalidPatch = transition.error else {
                    XCTFail("\(defect) 必须报 invalidPatch")
                    continue
                }
            case .effectWithoutApplied, .inconsistentCompletion:
                guard case .invalidSnapshot = transition.error else {
                    XCTFail("\(defect) 必须报 invalidSnapshot")
                    continue
                }
            }
            XCTAssertTrue(transition.effects.isEmpty, "\(defect)")
            XCTAssertEqual(transition.snapshot, original, "\(defect)")
            XCTAssertFalse(adapter.isOperational, "\(defect)")

            transition = adapter.reduce(.requestModels(sessionID: "session-2", isConnected: true))
            XCTAssertNotNil(transition.error, "\(defect)")
            XCTAssertTrue(transition.effects.isEmpty, "\(defect)")
            XCTAssertEqual(bridge.requestCallCount, 1, "\(defect)")
        }
    }

    @MainActor
    func testKMPSessionControlRejectsSemanticEffectBeforeAppStoreIO() {
        let executor = SessionControlEffectExecutorSpy()
        let appStore = AppStore(
            preferences: AppPreferencesSpy(
                endpoint: "wss://injected.example/ws/mobile",
                selectedWorkspaceID: nil,
                sessions: []
            ),
            sessionControlEffectExecutor: executor
        )

        let normal = KMPSessionControlStoreAdapter().reduce(
            .requestModels(sessionID: "session-1", isConnected: true)
        )
        XCTAssertNil(normal.error)
        normal.effects.forEach(appStore.executeSessionControlEffect)
        XCTAssertEqual(executor.modelsTargets, ["session-1"])

        let invalidAdapter = KMPSessionControlStoreAdapter(bridge: SemanticInvalidSessionControlEffectBridge())
        let invalid = invalidAdapter.reduce(.requestModels(sessionID: "session-1", isConnected: true))
        guard case .invalidEffect = invalid.error else {
            return XCTFail("语义不匹配 effect 必须报 invalidEffect")
        }
        XCTAssertTrue(invalid.effects.isEmpty)
        invalid.effects.forEach(appStore.executeSessionControlEffect)
        XCTAssertEqual(executor.modelsTargets, ["session-1"])
    }

    @MainActor
    func testKMPSessionControlRejectsIntentSmugglingMissingPayloadAndProjectionEffect() {
        var transition = KMPSessionControlStoreAdapter(
            bridge: IntentSmugglingSessionControlBridge()
        ).reduce(.requestModels(sessionID: "session-1", isConnected: true))
        guard case .invalidEffect = transition.error else {
            return XCTFail("跨 session 请求 effect 必须以 invalidEffect fail-closed")
        }
        XCTAssertTrue(transition.effects.isEmpty)

        let selection = GatewayModelSelection(provider: "openai", model: "gpt-5")
        transition = KMPSessionControlStoreAdapter(
            bridge: MissingPayloadSessionControlBridge()
        ).reduce(.selectModel(sessionID: "session-1", selection: selection, isConnected: true))
        guard case .invalidEffect = transition.error else {
            return XCTFail("缺失 intent payload 的 effect 必须以 invalidEffect fail-closed")
        }
        XCTAssertTrue(transition.effects.isEmpty)

        transition = KMPSessionControlStoreAdapter(
            bridge: ProjectionSmugglingSessionControlBridge()
        ).reduce(.projection(.contextReceived(
            sessionID: "session-1",
            asOfSequence: 1,
            tokenUsage: nil,
            pressure: nil,
            breakdown: nil
        )))
        guard case .invalidEffect = transition.error else {
            return XCTFail("projection 携带平台 effect 必须以 invalidEffect fail-closed")
        }
        XCTAssertTrue(transition.effects.isEmpty)
    }

    @MainActor
    func testKMPSessionControlRejectsCrossKindControlInjectionBeforePublishAndIO() {
        let bridge = ControlCrossKindSmugglingSessionControlBridge()
        let adapter = KMPSessionControlStoreAdapter(bridge: bridge)
        let original = adapter.snapshot
        let executor = SessionControlEffectExecutorSpy()

        let transition = adapter.reduce(.requestModels(sessionID: "session-1", isConnected: true))
        guard case .invalidPatch = transition.error else {
            return XCTFail("当前 models intent 不得修改 permission-options control 字段")
        }
        XCTAssertEqual(transition.snapshot, original)
        XCTAssertEqual(adapter.snapshot, original)
        XCTAssertTrue(transition.effects.isEmpty)
        transition.effects.forEach { effect in
            if effect.kind == "models" { executor.requestModels(sessionId: effect.sessionId) }
        }
        XCTAssertTrue(executor.modelsTargets.isEmpty)
        XCTAssertFalse(adapter.isOperational)
    }

    @MainActor
    func testNegativeControlResponseImmediatelyCompletesCurrentGeneration() async throws {
        let bridge = SharedSessionControlStore()
        let selection = GatewayModelSelection(provider: "openai", model: "gpt-5")
        _ = bridge.selectModel(
            sessionId: "session-1",
            selectionJson: String(decoding: try JSONEncoder().encode(selection), as: UTF8.self),
            isConnected: true
        )
        let store = AppStore(
            preferences: AppPreferencesSpy(
                endpoint: "wss://injected.example/ws/mobile",
                selectedWorkspaceID: nil,
                sessions: []
            ),
            sessionControlBridge: bridge
        )
        XCTAssertTrue(store.sessionControlLoadingKinds.contains("select-model"))

        store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(
            #"{"kind":"select-model","sessionId":"session-1"}"#.utf8
        )))
        await flushDeferredKMPEvents(in: store)

        XCTAssertFalse(store.sessionControlLoadingKinds.contains("select-model"))
        let snapshot = try JSONDecoder().decode(
            KMPSessionControlSnapshot.self,
            from: Data(bridge.snapshot().snapshotJson!.utf8)
        )
        XCTAssertNil(snapshot.requestTokens["select-model"])
    }

    @MainActor
    func testHelloResetsPreviousConnectionGenerationBeforeRefresh() throws {
        let bridge = SharedSessionControlStore()
        let old = bridge.requestModels(sessionId: "old-session", isConnected: true)
        let oldToken = try JSONDecoder().decode(
            [KMPSessionControlEffect].self,
            from: Data(old.effectsJson.utf8)
        ).first!.requestToken
        let store = AppStore(
            preferences: AppPreferencesSpy(
                endpoint: "wss://injected.example/ws/mobile",
                selectedWorkspaceID: nil,
                sessions: []
            ),
            sessionControlBridge: bridge
        )

        store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(
            #"{"kind":"hello","protocol":3,"capabilities":[],"authenticated":true,"clients":1}"#.utf8
        )))

        let snapshot = try JSONDecoder().decode(
            KMPSessionControlSnapshot.self,
            from: Data(bridge.snapshot().snapshotJson!.utf8)
        )
        XCTAssertNotEqual(snapshot.requestTokens["models"], oldToken)
        XCTAssertNil(snapshot.activeRequestTargets["models"]?.sessionId)
        XCTAssertTrue(snapshot.previousCompletedRequestTargets.isEmpty)
    }

    @MainActor
    func testLegacyNilResponseFinishesOnlyTheCurrentNormalGeneration() throws {
        let bridge = SharedSessionControlStore()
        _ = bridge.requestModels(sessionId: "session-a", isConnected: true)
        _ = bridge.modelsReceived(
            sessionId: "session-a",
            currentJson: nil,
            routable: true,
            groupsJson: "[]",
            isGlobalRequest: false
        )
        let second = bridge.requestModels(sessionId: "session-b", isConnected: true)
        let secondToken = try JSONDecoder().decode(
            [KMPSessionControlEffect].self,
            from: Data(second.effectsJson.utf8)
        ).first!.requestToken
        let store = AppStore(
            preferences: AppPreferencesSpy(
                endpoint: "wss://injected.example/ws/mobile",
                selectedWorkspaceID: nil,
                sessions: []
            ),
            sessionControlBridge: bridge
        )

        // 显式回显的旧 session 不能结束当前 generation。
        store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(
            #"{"kind":"models","sessionId":"session-a","groups":[]}"#.utf8
        )))
        var snapshot = try JSONDecoder().decode(
            KMPSessionControlSnapshot.self,
            from: Data(bridge.snapshot().snapshotJson!.utf8)
        )
        XCTAssertEqual(snapshot.requestTokens["models"], secondToken)

        // 真实 Gateway 的 models 帧不回显 sessionId，绑定当前唯一 active request。
        store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(
            #"{"kind":"models","groups":[]}"#.utf8
        )))
        snapshot = try JSONDecoder().decode(
            KMPSessionControlSnapshot.self,
            from: Data(bridge.snapshot().snapshotJson!.utf8)
        )
        XCTAssertNil(snapshot.requestTokens["models"])
    }

    @MainActor
    func testCorrelatedNegativeDefaultResponseFinishesWithoutWaitingForTimeout() async throws {
        let bridge = SharedSessionControlStore()
        _ = bridge.setDefault(target: "permission", value: "read-only", isConnected: true)
        _ = bridge.globalDefaultApplied(target: "permission", value: "read-only")
        _ = bridge.setDefault(target: "permission", value: "workspace-write", isConnected: true)
        let store = AppStore(
            preferences: AppPreferencesSpy(
                endpoint: "wss://injected.example/ws/mobile",
                selectedWorkspaceID: nil,
                sessions: []
            ),
            sessionControlBridge: bridge
        )
        XCTAssertTrue(store.defaultConfigurationLoadingKinds.contains("set-default"))

        store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(
            #"{"kind":"set-default","applied":false,"target":"permission","value":"workspace-write"}"#.utf8
        )))
        await flushDeferredKMPEvents(in: store)

        XCTAssertFalse(store.defaultConfigurationLoadingKinds.contains("set-default"))
        XCTAssertNotNil(store.lastError)
        XCTAssertEqual(store.protocolNotices.last?.isError, true)
        let snapshot = try JSONDecoder().decode(
            KMPSessionControlSnapshot.self,
            from: Data(bridge.snapshot().snapshotJson!.utf8)
        )
        XCTAssertNil(snapshot.requestTokens["set-default"])
    }

    @MainActor
    func testTokenlessNegativeDefaultResponsesFinishTheOnlyActiveGeneration() async throws {
        let selection = GatewayModelSelection(provider: "openai", model: "gpt-5")
        let selectionJSON = String(decoding: try JSONEncoder().encode(selection), as: UTF8.self)

        let saveBridge = SharedSessionControlStore()
        _ = saveBridge.saveDefaultModel(selectionJson: selectionJSON, isConnected: true)
        _ = saveBridge.defaultModelSaved(selectionJson: selectionJSON)
        _ = saveBridge.saveDefaultModel(selectionJson: selectionJSON, isConnected: true)
        let saveStore = AppStore(
            preferences: AppPreferencesSpy(
                endpoint: "wss://injected.example/ws/mobile",
                selectedWorkspaceID: nil,
                sessions: []
            ),
            sessionControlBridge: saveBridge
        )
        saveStore.gateway.onFrame?(try GatewayWireDecoder.decode(Data(
            #"{"kind":"save-default-model"}"#.utf8
        )))
        await flushDeferredKMPEvents(in: saveStore)
        XCTAssertFalse(saveStore.defaultConfigurationLoadingKinds.contains("save-default-model"))
        XCTAssertNotNil(saveStore.lastError)
        XCTAssertEqual(saveStore.protocolNotices.last?.isError, true)

        let defaultBridge = SharedSessionControlStore()
        _ = defaultBridge.setDefault(target: "permission", value: "read-only", isConnected: true)
        _ = defaultBridge.globalDefaultApplied(target: "permission", value: "read-only")
        _ = defaultBridge.setDefault(target: "permission", value: "read-only", isConnected: true)
        let defaultStore = AppStore(
            preferences: AppPreferencesSpy(
                endpoint: "wss://injected.example/ws/mobile",
                selectedWorkspaceID: nil,
                sessions: []
            ),
            sessionControlBridge: defaultBridge
        )
        defaultStore.gateway.onFrame?(try GatewayWireDecoder.decode(Data(
            #"{"kind":"set-default","applied":false,"target":"permission","value":"read-only"}"#.utf8
        )))
        await flushDeferredKMPEvents(in: defaultStore)
        XCTAssertFalse(defaultStore.defaultConfigurationLoadingKinds.contains("set-default"))
        XCTAssertNotNil(defaultStore.lastError)
        XCTAssertEqual(defaultStore.protocolNotices.last?.isError, true)
    }

    @MainActor
    func testAppStoreIgnoresUncorrelatedSessionControlResponses() throws {
        let executor = SessionControlEffectExecutorSpy()
        let store = AppStore(
            preferences: AppPreferencesSpy(
                endpoint: "wss://injected.example/ws/mobile",
                selectedWorkspaceID: nil,
                sessions: []
            ),
            sessionControlEffectExecutor: executor
        )
        let frames = [
            #"{"kind":"agent-presets","presets":[{"id":"standard","isDefault":true}],"authorable":true,"hasDocument":true}"#,
            #"{"kind":"defaults","agentPresetDefault":"standard","permissionDefault":"workspace-write"}"#,
            #"{"kind":"models","sessionId":"session-1","current":{"provider":"openai","model":"gpt-5"},"routable":true,"groups":[]}"#,
            #"{"kind":"permission-options","sessionId":"session-1","sessionPermissions":{"options":[{"value":"read-only","name":"Read"},{"value":"future","name":"Future"}],"currentValue":"read-only"}}"#,
            #"{"kind":"context-usage","sessionId":"session-1","asOfSeq":10,"tokenUsage":{"uncachedInputTokens":12}}"#,
            #"{"kind":"session-stats","sessionId":"session-1","asOfSeq":20,"sessionStats":{"turns":2},"tokenUsage":{"totals":{"inputTokens":100}}}"#,
            #"{"kind":"set-default","applied":true,"target":"permission","value":"workspace-write"}"#
        ]
        for json in frames {
            store.gateway.onFrame?(try GatewayWireDecoder.decode(Data(json.utf8)))
        }

        XCTAssertTrue(store.agentPresets.isEmpty)
        XCTAssertNil(store.agentPresetDefault)
        XCTAssertNil(store.permissionDefault)
        XCTAssertTrue(store.modelCatalogs.isEmpty)
        XCTAssertTrue(store.sessionPermissions.isEmpty)
        XCTAssertTrue(store.contextSnapshots.isEmpty)
        XCTAssertTrue(store.sessionStatsSnapshots.isEmpty)
        XCTAssertEqual(executor.defaultsRequestCount, 0)
        XCTAssertTrue(store.protocolNotices.isEmpty)
    }

    @MainActor
    func testRequestTrackerFiresOnlyLatestGeneration() async throws {
        let tracker = RequestTracker()
        var timeouts: [String] = []
        tracker.begin("models", timeout: .milliseconds(10)) { timeouts.append("stale") }
        tracker.begin("models", timeout: .milliseconds(30)) { timeouts.append("latest") }

        try await Task.sleep(for: .milliseconds(60))

        XCTAssertEqual(timeouts, ["latest"])
        XCTAssertTrue(tracker.activeKeys.isEmpty)
    }

    @MainActor
    func testRequestTrackerFinishAndFinishAllCancelTimeouts() async throws {
        let tracker = RequestTracker()
        var timeoutCount = 0
        tracker.begin("models", timeout: .milliseconds(20)) { timeoutCount += 1 }
        tracker.begin("stats", timeout: .milliseconds(20)) { timeoutCount += 1 }
        tracker.finish("models")
        tracker.finishAll()

        try await Task.sleep(for: .milliseconds(40))

        XCTAssertEqual(timeoutCount, 0)
        XCTAssertTrue(tracker.activeKeys.isEmpty)
    }

    @MainActor
    func testHistorySyncEngineInvalidatesLateGenerationsAndTimeouts() async throws {
        let engine = HistorySyncEngine()
        var timeoutCount = 0
        engine.beginRequest(sessionID: "session-1", timeout: .milliseconds(100)) {
            timeoutCount += 1
        }
        let processing = engine.beginProcessing(sessionID: "session-1")
        XCTAssertTrue(engine.isCurrent(processing, sessionID: "session-1"))
        try await Task.sleep(for: .milliseconds(150))
        XCTAssertEqual(timeoutCount, 0)

        engine.beginRequest(sessionID: "session-1", timeout: .milliseconds(100)) {
            timeoutCount += 1
        }
        try await Task.sleep(for: .milliseconds(200))
        XCTAssertEqual(timeoutCount, 1)
        XCTAssertFalse(engine.isActive(sessionID: "session-1"))
    }

    func testQuestionAnswerTrimsCustomText() {
        let answer = GatewayQuestionAnswer(id: "custom", selected: [], custom: "  自定义回答  ")
        XCTAssertEqual(answer.custom, "自定义回答")
        XCTAssertNil(GatewayQuestionAnswer(id: "empty", selected: [], custom: "  \n ").custom)
    }

    private func questionRequest() -> GatewayPendingQuestionRequest {
        GatewayPendingQuestionRequest(
            rpcId: "rpc-question",
            sessionId: "session-question",
            questions: [
                GatewayQuestion(
                    id: "direction",
                    question: "研究方向",
                    options: [
                        GatewayQuestionOption(label: "架构"),
                        GatewayQuestionOption(label: "界面")
                    ],
                    multiSelect: true
                ),
                GatewayQuestion(
                    id: "notes",
                    question: "补充要求",
                    options: [GatewayQuestionOption(label: "简洁")],
                    multiSelect: false
                )
            ],
            replay: false
        )
    }

    private func pairingPayloadString(
        version: Int = 2,
        publicURL: String = "ws://gateway.example:3080/ws/mobile",
        pairingCode: String = "one-time-code",
        expiresAt: Double = 4_102_444_800_000
    ) throws -> String {
        let payload = GatewayPairingPayload(
            version: version,
            publicUrl: publicURL,
            pairingCode: pairingCode,
            expiresAt: expiresAt
        )
        return base64URL(try JSONEncoder().encode(payload))
    }

    private func base64URL(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}

@MainActor
private final class SessionControlEffectExecutorSpy: GatewaySessionControlEffectExecuting {
    private(set) var modelsTargets: [String?] = []
    private(set) var defaultsRequestCount = 0
    func requestModels(sessionId: String?) { modelsTargets.append(sessionId) }
    func requestPermissionOptions(sessionId: String?) {}
    func requestContextUsage(sessionId: String) {}
    func requestSessionStats(sessionId: String) {}
    func requestAgentPresets() {}
    func requestDefaults() { defaultsRequestCount += 1 }
    func requestDefaultModel() {}
    func selectModel(sessionId: String, provider: String, model: String, reasoningEffort: String?) {}
    func setPermission(sessionId: String, name: String) {}
    func saveDefaultModel(provider: String, model: String, reasoningEffort: String?) {}
    func setDefault(target: String, value: String) {}
}

private final class IntentSmugglingSessionControlBridge: MalformedSessionControlBridge {
    override func requestModels(sessionId: String?, isConnected: Bool) -> SharedSessionControlResult {
        forgedRequestResult(target: .init(kind: "models", isDefault: false, sessionId: "session-2"))
    }
}

private final class MissingPayloadSessionControlBridge: MalformedSessionControlBridge {
    override func selectModel(
        sessionId: String,
        selectionJson: String,
        isConnected: Bool
    ) -> SharedSessionControlResult {
        forgedRequestResult(
            target: .init(
                kind: "select-model",
                isDefault: false,
                sessionId: sessionId,
                provider: "openai",
                model: "gpt-5"
            ),
            effectTarget: .init(
                kind: "select-model",
                isDefault: false,
                sessionId: sessionId,
                provider: "openai",
                model: nil
            )
        )
    }
}

private final class ProjectionSmugglingSessionControlBridge: MalformedSessionControlBridge {
    override func mergeContextProjection(
        sessionId: String,
        asOfSequence: KotlinLong?,
        tokenUsageJson: String?,
        pressureJson: String?,
        breakdownJson: String?
    ) -> SharedSessionControlResult {
        forgedRequestResult(target: .init(kind: "context-usage", isDefault: false, sessionId: sessionId))
    }
}

private final class ControlCrossKindSmugglingSessionControlBridge: MalformedSessionControlBridge {
    override func requestModels(sessionId: String?, isConnected: Bool) -> SharedSessionControlResult {
        let models = KMPSessionControlRequestTarget(
            kind: "models", isDefault: false, sessionId: sessionId
        )
        let permissions = KMPSessionControlRequestTarget(
            kind: "permission-options", isDefault: false, sessionId: "session-2"
        )
        var snapshot = KMPSessionControlSnapshot.empty
        snapshot.loadingKinds = ["models", "permission-options"]
        snapshot.pendingModelsSessionId = sessionId
        snapshot.pendingPermissionOptionsSessionId = "session-2"
        snapshot.requestTokens = ["models": "models:1", "permission-options": "permission-options:1"]
        snapshot.activeRequestTargets = ["models": models, "permission-options": permissions]
        var patch = KMPSessionControlPatch.empty
        patch.control = snapshot.controlPatch
        let effect = KMPSessionControlEffect(
            kind: "models", requestKey: "models", requestToken: "models:1",
            sessionId: sessionId, provider: nil, model: nil, reasoningEffort: nil,
            target: nil, value: nil
        )
        return SharedSessionControlResult(
            snapshotJson: Self.encode(patch),
            effectsJson: Self.encode([effect]),
            errorCode: nil,
            errorMessage: nil,
            applied: true,
            committed: true,
            completedKind: nil,
            completedRequestToken: nil
        )
    }
}

private final class PatchQueueSessionControlBridge: MalformedSessionControlBridge {
    private var patches: [KMPSessionControlPatch]

    init(patches: [KMPSessionControlPatch]) {
        self.patches = patches
    }

    override func mergeContextProjection(
        sessionId: String,
        asOfSequence: KotlinLong?,
        tokenUsageJson: String?,
        pressureJson: String?,
        breakdownJson: String?
    ) -> SharedSessionControlResult {
        guard !patches.isEmpty else { return unimplemented() }
        return SharedSessionControlResult(
            snapshotJson: Self.encode(patches.removeFirst()),
            effectsJson: "[]",
            errorCode: nil,
            errorMessage: nil,
            applied: true,
            committed: true,
            completedKind: nil,
            completedRequestToken: nil
        )
    }
}

private final class ClearOmissionSessionControlBridge: MalformedSessionControlBridge {
    private let initial: KMPSessionControlSnapshot = {
        var snapshot = KMPSessionControlSnapshot.empty
        let target = KMPSessionControlRequestTarget(
            kind: "models", isDefault: false, sessionId: "session-a"
        )
        snapshot.contextSnapshots["session-a"] = GatewayContextSnapshot(asOfSeq: 1)
        snapshot.loadingKinds = ["models"]
        snapshot.pendingModelsSessionId = "session-a"
        snapshot.requestTokens = ["models": "models:1"]
        snapshot.activeRequestTargets = ["models": target]
        return snapshot
    }()

    override func snapshot() -> SharedSessionControlResult {
        SharedSessionControlResult(
            snapshotJson: MalformedSessionControlBridge.encode(initial),
            effectsJson: "[]", errorCode: nil, errorMessage: nil,
            applied: false, committed: false,
            completedKind: nil, completedRequestToken: nil
        )
    }

    override func clearSessionData(sessionId: String) -> SharedSessionControlResult {
        var next = initial
        next.drainingRequestKinds = ["models"]
        var patch = KMPSessionControlPatch.empty
        // 故意遗漏 contextSnapshotsRemove，模拟损坏/不兼容 KMP bridge。
        patch.control = next.controlPatch
        return SharedSessionControlResult(
            snapshotJson: MalformedSessionControlBridge.encode(patch),
            effectsJson: "[]", errorCode: nil, errorMessage: nil,
            applied: true, committed: true,
            completedKind: nil, completedRequestToken: nil
        )
    }
}

private final class DrainBusinessInjectionSessionControlBridge: MalformedSessionControlBridge {
    private let initial: KMPSessionControlSnapshot = {
        var snapshot = KMPSessionControlSnapshot.empty
        let active = KMPSessionControlRequestTarget(
            kind: "models", isDefault: false, sessionId: "session-a"
        )
        let queued = KMPSessionControlRequestTarget(
            kind: "models", isDefault: false, sessionId: "session-b"
        )
        snapshot.loadingKinds = ["models"]
        snapshot.pendingModelsSessionId = "session-a"
        snapshot.requestTokens = ["models": "models:1"]
        snapshot.activeRequestTargets = ["models": active]
        snapshot.queuedRequestTargets = ["models": queued]
        snapshot.drainingRequestKinds = ["models"]
        return snapshot
    }()

    override func snapshot() -> SharedSessionControlResult {
        SharedSessionControlResult(
            snapshotJson: MalformedSessionControlBridge.encode(initial),
            effectsJson: "[]", errorCode: nil, errorMessage: nil,
            applied: false, committed: false,
            completedKind: nil, completedRequestToken: nil
        )
    }

    override func modelsReceived(
        sessionId: String?, currentJson: String?, routable: Bool,
        groupsJson: String, isGlobalRequest: Bool
    ) -> SharedSessionControlResult {
        let replacement = KMPSessionControlRequestTarget(
            kind: "models", isDefault: false, sessionId: "session-b"
        )
        var next = KMPSessionControlSnapshot.empty
        next.loadingKinds = ["models"]
        next.pendingModelsSessionId = "session-b"
        next.requestTokens = ["models": "models:2"]
        next.activeRequestTargets = ["models": replacement]
        var patch = KMPSessionControlPatch.empty
        // 故意在 tombstone 终态重新注入已清理 A 的业务数据。
        patch.modelCatalogsUpsert["session-a"] = GatewayModelCatalog(
            current: nil, routable: true, groups: []
        )
        patch.control = next.controlPatch
        let effect = KMPSessionControlEffect(
            kind: "models", requestKey: "models", requestToken: "models:2",
            sessionId: "session-b", provider: nil, model: nil,
            reasoningEffort: nil, target: nil, value: nil
        )
        return SharedSessionControlResult(
            snapshotJson: MalformedSessionControlBridge.encode(patch),
            effectsJson: MalformedSessionControlBridge.encode([effect]),
            errorCode: nil, errorMessage: nil,
            applied: true, committed: true,
            completedKind: "models", completedRequestToken: "models:1"
        )
    }
}

private final class RawPatchSessionControlBridge: MalformedSessionControlBridge {
    private let payload: String

    init(payload: String) {
        self.payload = payload
    }

    override func mergeContextProjection(
        sessionId: String,
        asOfSequence: KotlinLong?,
        tokenUsageJson: String?,
        pressureJson: String?,
        breakdownJson: String?
    ) -> SharedSessionControlResult {
        SharedSessionControlResult(
            snapshotJson: payload,
            effectsJson: "[]",
            errorCode: nil,
            errorMessage: nil,
            applied: true,
            committed: true,
            completedKind: nil,
            completedRequestToken: nil
        )
    }
}

private final class SemanticInvalidSessionControlEffectBridge: MalformedSessionControlBridge {
    override func requestModels(sessionId: String?, isConnected: Bool) -> SharedSessionControlResult {
        requestCallCount += 1
        var snapshot = KMPSessionControlSnapshot.empty
        let target = KMPSessionControlRequestTarget(
            kind: "models",
            isDefault: false,
            sessionId: sessionId,
            provider: nil,
            model: nil,
            reasoningEffort: nil,
            target: nil,
            value: nil
        )
        snapshot.loadingKinds = ["models"]
        snapshot.pendingModelsSessionId = sessionId
        snapshot.requestTokens = ["models": "models:1"]
        snapshot.activeRequestTargets = ["models": target]
        var patch = KMPSessionControlPatch.empty
        patch.control = snapshot.controlPatch
        let effect = KMPSessionControlEffect(
            kind: "models",
            requestKey: "models",
            requestToken: "models:1",
            sessionId: "wrong-session",
            provider: nil,
            model: nil,
            reasoningEffort: nil,
            target: nil,
            value: nil
        )
        return SharedSessionControlResult(
            snapshotJson: Self.encode(patch),
            effectsJson: Self.encode([effect]),
            errorCode: nil,
            errorMessage: nil,
            applied: true,
            committed: true,
            completedKind: nil,
            completedRequestToken: nil
        )
    }
}

private final class InvalidSessionControlResultBridge: MalformedSessionControlBridge {
    enum Defect: CaseIterable {
        case uncommittedSnapshotMutation
        case effectWithoutApplied
        case committedWithoutMutation
        case inconsistentCompletion
    }

    private let defect: Defect

    init(defect: Defect) {
        self.defect = defect
    }

    override func requestModels(sessionId: String?, isConnected: Bool) -> SharedSessionControlResult {
        requestCallCount += 1
        let valid = forgedRequestResult(
            target: .init(kind: "models", isDefault: false, sessionId: sessionId)
        )
        switch defect {
        case .uncommittedSnapshotMutation:
            return SharedSessionControlResult(
                snapshotJson: valid.snapshotJson,
                effectsJson: "[]",
                errorCode: nil,
                errorMessage: nil,
                applied: true,
                committed: false,
                completedKind: nil,
                completedRequestToken: nil
            )
        case .effectWithoutApplied:
            return SharedSessionControlResult(
                snapshotJson: valid.snapshotJson,
                effectsJson: valid.effectsJson,
                errorCode: nil,
                errorMessage: nil,
                applied: false,
                committed: true,
                completedKind: nil,
                completedRequestToken: nil
            )
        case .committedWithoutMutation:
            return SharedSessionControlResult(
                snapshotJson: Self.encode(KMPSessionControlPatch.empty),
                effectsJson: "[]",
                errorCode: nil,
                errorMessage: nil,
                applied: true,
                committed: true,
                completedKind: nil,
                completedRequestToken: nil
            )
        case .inconsistentCompletion:
            return SharedSessionControlResult(
                snapshotJson: valid.snapshotJson,
                effectsJson: "[]",
                errorCode: nil,
                errorMessage: nil,
                applied: true,
                committed: true,
                completedKind: "models",
                completedRequestToken: "models:missing"
            )
        }
    }
}

private class MalformedSessionControlBridge: KMPSessionControlStoreBridging {
    fileprivate(set) var requestCallCount = 0
    fileprivate(set) var snapshotCallCount = 0

    fileprivate static func encode<T: Encodable>(_ value: T) -> String {
        String(decoding: try! JSONEncoder().encode(value), as: UTF8.self)
    }

    func snapshot() -> SharedSessionControlResult {
        snapshotCallCount += 1
        let json = try! JSONEncoder().encode(KMPSessionControlSnapshot.empty)
        return SharedSessionControlResult(
            snapshotJson: String(decoding: json, as: UTF8.self),
            effectsJson: "[]",
            errorCode: nil,
            errorMessage: nil,
            applied: false,
            committed: false,
            completedKind: nil,
            completedRequestToken: nil
        )
    }

    func requestModels(sessionId: String?, isConnected: Bool) -> SharedSessionControlResult {
        requestCallCount += 1
        return SharedSessionControlResult(
            snapshotJson: "{}",
            effectsJson: "[]",
            errorCode: nil,
            errorMessage: nil,
            applied: true,
            committed: true,
            completedKind: nil,
            completedRequestToken: nil
        )
    }

    func selectModel(
        sessionId: String,
        selectionJson: String,
        isConnected: Bool
    ) -> SharedSessionControlResult { unimplemented() }

    func clearSessionData(sessionId: String) -> SharedSessionControlResult { unimplemented() }

    func modelsReceived(
        sessionId: String?, currentJson: String?, routable: Bool,
        groupsJson: String, isGlobalRequest: Bool
    ) -> SharedSessionControlResult { unimplemented() }

    func mergeContextProjection(
        sessionId: String,
        asOfSequence: KotlinLong?,
        tokenUsageJson: String?,
        pressureJson: String?,
        breakdownJson: String?
    ) -> SharedSessionControlResult { unimplemented() }

    fileprivate func forgedRequestResult(
        target: KMPSessionControlRequestTarget,
        effectTarget: KMPSessionControlRequestTarget? = nil
    ) -> SharedSessionControlResult {
        let token = "\(target.kind):forged"
        var snapshot = KMPSessionControlSnapshot.empty
        if target.isDefault {
            snapshot.defaultConfigurationLoadingKinds = [target.kind]
        } else {
            snapshot.loadingKinds = [target.kind]
        }
        snapshot.requestTokens = [target.kind: token]
        snapshot.activeRequestTargets = [target.kind: target]
        if target.kind == "models" {
            snapshot.pendingModelsSessionId = target.sessionId
            snapshot.isPendingGlobalModelsRequest = target.sessionId == nil
        }
        if target.kind == "select-model" { snapshot.pendingModelSelectionSessionId = target.sessionId }
        if target.kind == "permission-options" { snapshot.pendingPermissionOptionsSessionId = target.sessionId }
        var patch = KMPSessionControlPatch.empty
        patch.control = snapshot.controlPatch
        let effectTarget = effectTarget ?? target
        let effect = KMPSessionControlEffect(
            kind: effectTarget.kind,
            requestKey: effectTarget.kind,
            requestToken: token,
            sessionId: effectTarget.sessionId,
            provider: effectTarget.provider,
            model: effectTarget.model,
            reasoningEffort: effectTarget.reasoningEffort,
            target: effectTarget.target,
            value: effectTarget.value
        )
        return SharedSessionControlResult(
            snapshotJson: Self.encode(patch),
            effectsJson: Self.encode([effect]),
            errorCode: nil,
            errorMessage: nil,
            applied: true,
            committed: true,
            completedKind: nil,
            completedRequestToken: nil
        )
    }
}

private extension KMPSessionControlStoreBridging {
    func unimplemented() -> SharedSessionControlResult {
        SharedSessionControlResult(
            snapshotJson: nil,
            effectsJson: "[]",
            errorCode: "unexpected-test-call",
            errorMessage: nil,
            applied: false,
            committed: false,
            completedKind: nil,
            completedRequestToken: nil
        )
    }
    func requestPermissionOptions(sessionId: String, isConnected: Bool) -> SharedSessionControlResult { unimplemented() }
    func requestContextUsage(sessionId: String, isConnected: Bool) -> SharedSessionControlResult { unimplemented() }
    func requestSessionStats(sessionId: String, isConnected: Bool) -> SharedSessionControlResult { unimplemented() }
    func requestAgentPresets(isConnected: Bool) -> SharedSessionControlResult { unimplemented() }
    func requestDefaults(isConnected: Bool) -> SharedSessionControlResult { unimplemented() }
    func requestDefaultModel(isConnected: Bool) -> SharedSessionControlResult { unimplemented() }
    func setPermission(sessionId: String, value: String, isConnected: Bool) -> SharedSessionControlResult { unimplemented() }
    func saveDefaultModel(selectionJson: String, isConnected: Bool) -> SharedSessionControlResult { unimplemented() }
    func setDefault(target: String, value: String, isConnected: Bool) -> SharedSessionControlResult { unimplemented() }
    func clearSessionData(sessionId: String) -> SharedSessionControlResult { unimplemented() }
    func clearSessionsData(sessionIdsJson: String) -> SharedSessionControlResult { unimplemented() }
    func agentPresetsReceived(presetsJson: String, authorable: Bool, hasDocument: Bool) -> SharedSessionControlResult { unimplemented() }
    func defaultsReceived(agentPreset: String?, permission: String?) -> SharedSessionControlResult { unimplemented() }
    func defaultModelReceived(selectionJson: String?) -> SharedSessionControlResult { unimplemented() }
    func globalDefaultApplied(target: String, value: String) -> SharedSessionControlResult { unimplemented() }
    func modelsReceived(sessionId: String?, currentJson: String?, routable: Bool, groupsJson: String, isGlobalRequest: Bool) -> SharedSessionControlResult { unimplemented() }
    func defaultModelSaved(selectionJson: String?) -> SharedSessionControlResult { unimplemented() }
    func modelSelected(sessionId: String?, selectionJson: String) -> SharedSessionControlResult { unimplemented() }
    func permissionsReceived(sessionId: String?, permissionsJson: String) -> SharedSessionControlResult { unimplemented() }
    func permissionSelected(sessionId: String?, value: String) -> SharedSessionControlResult { unimplemented() }
    func contextReceived(sessionId: String?, asOfSequence: KotlinLong?, tokenUsageJson: String?, pressureJson: String?, breakdownJson: String?) -> SharedSessionControlResult { unimplemented() }
    func statsReceived(sessionId: String?, asOfSequence: KotlinLong?, statsJson: String?, tokenUsageTotalsJson: String?, contextPressureJson: String?) -> SharedSessionControlResult { unimplemented() }
    func mergeContextProjection(sessionId: String, asOfSequence: KotlinLong?, tokenUsageJson: String?, pressureJson: String?, breakdownJson: String?) -> SharedSessionControlResult { unimplemented() }
    func mergeStatsProjection(sessionId: String, asOfSequence: KotlinLong?, statsJson: String?, tokenUsageTotalsJson: String?, contextPressureJson: String?) -> SharedSessionControlResult { unimplemented() }
    func mergePermissionsProjection(sessionId: String, permissionsJson: String) -> SharedSessionControlResult { unimplemented() }
    func mergeModelProjection(sessionId: String, selectionJson: String) -> SharedSessionControlResult { unimplemented() }
    func mergePermissionProjection(sessionId: String, value: String) -> SharedSessionControlResult { unimplemented() }
    func requestFinished(kind: String, isDefault: Bool, requestToken: String?) -> SharedSessionControlResult { unimplemented() }
    func requestTimedOut(kind: String, isDefault: Bool, requestToken: String?) -> SharedSessionControlResult { unimplemented() }
    func requestFailed(kind: String, isDefault: Bool, requestToken: String?) -> SharedSessionControlResult { unimplemented() }
    func requestsDisconnected() -> SharedSessionControlResult { unimplemented() }
}

private final class FailingRestoreSessionListBridge: KMPSessionListStoreBridging {
    private(set) var nonRestoreCallCount = 0

    func snapshot() -> SharedSessionListResult { unexpectedCall() }

    func restore(snapshotJson: String) -> SharedSessionListResult {
        SharedSessionListResult(
            snapshotJson: nil,
            errorCode: "session-list-restore-failed",
            errorMessage: "injected restore failure"
        )
    }

    func selectSession(sessionId: String?) -> SharedSessionListResult { unexpectedCall() }

    func setArchivedSessionIds(sessionIdsJson: String) -> SharedSessionListResult { unexpectedCall() }

    func receiveRemoteSessions(sessionsJson: String) -> SharedSessionListResult { unexpectedCall() }

    func messageSent(
        sessionId: String,
        agentPreset: String?,
        insertedAtEpochSeconds: Double
    ) -> SharedSessionListResult {
        unexpectedCall()
    }

    func addKnownSession(
        sessionId: String,
        insertedAtEpochSeconds: Double
    ) -> SharedSessionListResult {
        unexpectedCall()
    }

    func receiveEvent(
        eventJson: String,
        insertedAtEpochSeconds: Double
    ) -> SharedSessionListResult {
        unexpectedCall()
    }

    func markRead(sessionId: String) -> SharedSessionListResult { unexpectedCall() }

    private func unexpectedCall() -> SharedSessionListResult {
        nonRestoreCallCount += 1
        return SharedSessionListResult(
            snapshotJson: "{\"sessions\":[],\"archivedSessionIds\":[]}",
            errorCode: nil,
            errorMessage: nil
        )
    }
}

private final class MalformedMutationSessionListBridge: KMPSessionListStoreBridging {
    private(set) var mutationCallCount = 0

    func snapshot() -> SharedSessionListResult { malformedMutation() }

    func restore(snapshotJson: String) -> SharedSessionListResult {
        SharedSessionListResult(
            snapshotJson: snapshotJson,
            errorCode: nil,
            errorMessage: nil
        )
    }

    func selectSession(sessionId: String?) -> SharedSessionListResult { malformedMutation() }

    func setArchivedSessionIds(sessionIdsJson: String) -> SharedSessionListResult {
        malformedMutation()
    }

    func receiveRemoteSessions(sessionsJson: String) -> SharedSessionListResult {
        malformedMutation()
    }

    func messageSent(
        sessionId: String,
        agentPreset: String?,
        insertedAtEpochSeconds: Double
    ) -> SharedSessionListResult {
        malformedMutation()
    }

    func addKnownSession(
        sessionId: String,
        insertedAtEpochSeconds: Double
    ) -> SharedSessionListResult {
        malformedMutation()
    }

    func receiveEvent(
        eventJson: String,
        insertedAtEpochSeconds: Double
    ) -> SharedSessionListResult {
        malformedMutation()
    }

    func markRead(sessionId: String) -> SharedSessionListResult { malformedMutation() }

    private func malformedMutation() -> SharedSessionListResult {
        mutationCallCount += 1
        return SharedSessionListResult(
            snapshotJson: "not-json",
            errorCode: nil,
            errorMessage: nil
        )
    }
}

private final class MalformedMutationQuestionBridge: KMPQuestionStoreBridging {
    private(set) var mutationCallCount = 0

    func snapshot() -> SharedQuestionResult {
        SharedQuestionResult(
            snapshotJson: #"{"pendingRequests":[],"requestStatuses":{}}"#,
            effectJson: nil,
            errorCode: nil,
            errorMessage: nil
        )
    }

    func reset() -> SharedQuestionResult { malformedMutation() }
    func requestReceived(requestJson: String) -> SharedQuestionResult { malformedMutation() }
    func submitAnswer(rpcId: String, answersJson: String, isConnected: Bool) -> SharedQuestionResult {
        malformedMutation()
    }
    func submitCancel(rpcId: String, isConnected: Bool) -> SharedQuestionResult { malformedMutation() }
    func responseReceived(
        rpcId: String,
        action: String,
        accepted: Bool,
        reason: String?
    ) -> SharedQuestionResult {
        malformedMutation()
    }
    func resolved(rpcId: String) -> SharedQuestionResult { malformedMutation() }
    func requestFailed(rpcId: String, message: String?) -> SharedQuestionResult { malformedMutation() }
    func sessionRequestsFailed(sessionId: String, message: String?) -> SharedQuestionResult { malformedMutation() }

    private func malformedMutation() -> SharedQuestionResult {
        mutationCallCount += 1
        return SharedQuestionResult(
            snapshotJson: #"{"pendingRequests":[],"requestStatuses":{}}"#,
            effectJson: "not-json",
            errorCode: nil,
            errorMessage: nil
        )
    }
}

private enum SemanticQuestionEffectMismatch: CaseIterable {
    case rpcID
    case sessionID
    case snapshotStatus
    case emptyAnswers
    case answerOrder
}

private final class SemanticMismatchQuestionBridge: KMPQuestionStoreBridging {
    let request: GatewayPendingQuestionRequest
    let mismatch: SemanticQuestionEffectMismatch
    private(set) var submitCallCount = 0

    init(request: GatewayPendingQuestionRequest, mismatch: SemanticQuestionEffectMismatch) {
        self.request = request
        self.mismatch = mismatch
    }

    func snapshot() -> SharedQuestionResult {
        result(snapshot: KMPQuestionSnapshot(
            pendingRequests: [request],
            requestStatuses: [request.rpcId: KMPQuestionStatusSnapshot(kind: "idle")]
        ))
    }

    func reset() -> SharedQuestionResult { snapshot() }
    func requestReceived(requestJson: String) -> SharedQuestionResult { snapshot() }

    func submitAnswer(rpcId: String, answersJson: String, isConnected: Bool) -> SharedQuestionResult {
        submitCallCount += 1
        let submittedAnswers = (try? JSONDecoder().decode(
            [GatewayQuestionAnswer].self,
            from: Data(answersJson.utf8)
        )) ?? []
        let status = mismatch == .snapshotStatus
            ? KMPQuestionStatusSnapshot(kind: "accepted", action: "answer")
            : KMPQuestionStatusSnapshot(kind: "submitting", action: "answer")
        let effectAnswers: [GatewayQuestionAnswer]
        switch mismatch {
        case .emptyAnswers:
            effectAnswers = []
        case .answerOrder:
            effectAnswers = Array(submittedAnswers.reversed())
        default:
            effectAnswers = submittedAnswers
        }
        return result(
            snapshot: KMPQuestionSnapshot(
                pendingRequests: [request],
                requestStatuses: [request.rpcId: status]
            ),
            effect: KMPQuestionEffect(
                action: "answer",
                rpcId: mismatch == .rpcID ? "rpc-other" : request.rpcId,
                sessionId: mismatch == .sessionID ? "session-other" : request.sessionId,
                answers: effectAnswers
            )
        )
    }

    func submitCancel(rpcId: String, isConnected: Bool) -> SharedQuestionResult { snapshot() }
    func responseReceived(
        rpcId: String,
        action: String,
        accepted: Bool,
        reason: String?
    ) -> SharedQuestionResult { snapshot() }
    func resolved(rpcId: String) -> SharedQuestionResult { snapshot() }
    func requestFailed(rpcId: String, message: String?) -> SharedQuestionResult { snapshot() }
    func sessionRequestsFailed(sessionId: String, message: String?) -> SharedQuestionResult { snapshot() }

    private func result(
        snapshot: KMPQuestionSnapshot,
        effect: KMPQuestionEffect? = nil
    ) -> SharedQuestionResult {
        let encoder = JSONEncoder()
        return SharedQuestionResult(
            snapshotJson: String(decoding: try! encoder.encode(snapshot), as: UTF8.self),
            effectJson: effect.map { String(decoding: try! encoder.encode($0), as: UTF8.self) },
            errorCode: nil,
            errorMessage: nil
        )
    }
}

private final class QuestionLifecycleBridge: KMPQuestionStoreBridging {
    private let request: GatewayPendingQuestionRequest
    private var snapshotValue: KMPQuestionSnapshot

    init(request: GatewayPendingQuestionRequest) {
        self.request = request
        snapshotValue = KMPQuestionSnapshot(
            pendingRequests: [request],
            requestStatuses: [request.rpcId: KMPQuestionStatusSnapshot(kind: "idle")]
        )
    }

    func snapshot() -> SharedQuestionResult { result() }

    func reset() -> SharedQuestionResult {
        snapshotValue = .empty
        return result()
    }

    func requestReceived(requestJson: String) -> SharedQuestionResult { result() }

    func submitAnswer(rpcId: String, answersJson: String, isConnected: Bool) -> SharedQuestionResult {
        let answers = (try? JSONDecoder().decode(
            [GatewayQuestionAnswer].self,
            from: Data(answersJson.utf8)
        )) ?? []
        snapshotValue.requestStatuses[rpcId] = KMPQuestionStatusSnapshot(kind: "submitting", action: "answer")
        return result(effect: KMPQuestionEffect(
            action: "answer",
            rpcId: rpcId,
            sessionId: request.sessionId,
            answers: answers
        ))
    }

    func submitCancel(rpcId: String, isConnected: Bool) -> SharedQuestionResult { result() }

    func responseReceived(
        rpcId: String,
        action: String,
        accepted: Bool,
        reason: String?
    ) -> SharedQuestionResult {
        if accepted {
            snapshotValue.requestStatuses[rpcId] = KMPQuestionStatusSnapshot(kind: "accepted", action: action)
        } else if reason == "not-pending" {
            snapshotValue = .empty
        } else {
            snapshotValue.requestStatuses[rpcId] = KMPQuestionStatusSnapshot(
                kind: "rejected",
                failureCode: "SERVER_REJECTED",
                failureArgument: reason
            )
        }
        return result()
    }

    func resolved(rpcId: String) -> SharedQuestionResult {
        snapshotValue = .empty
        return result()
    }

    func requestFailed(rpcId: String, message: String?) -> SharedQuestionResult {
        snapshotValue.requestStatuses[rpcId] = KMPQuestionStatusSnapshot(
            kind: "rejected",
            failureCode: "REQUEST_FAILED",
            failureArgument: message
        )
        return result()
    }

    func sessionRequestsFailed(sessionId: String, message: String?) -> SharedQuestionResult {
        for request in snapshotValue.pendingRequests where request.sessionId == sessionId {
            snapshotValue.requestStatuses[request.rpcId] = KMPQuestionStatusSnapshot(
                kind: "rejected",
                failureCode: "REQUEST_FAILED",
                failureArgument: message
            )
        }
        return result()
    }

    private func result(effect: KMPQuestionEffect? = nil) -> SharedQuestionResult {
        let encoder = JSONEncoder()
        return SharedQuestionResult(
            snapshotJson: String(decoding: try! encoder.encode(snapshotValue), as: UTF8.self),
            effectJson: effect.map { String(decoding: try! encoder.encode($0), as: UTF8.self) },
            errorCode: nil,
            errorMessage: nil
        )
    }
}

@MainActor
private final class GatewayQuestionEffectExecutorSpy: GatewayQuestionEffectExecuting {
    private(set) var answerCalls: [(rpcID: String, sessionID: String, answers: [GatewayQuestionAnswer])] = []
    private(set) var cancelCallCount = 0

    func answerQuestion(rpcId: String, sessionId: String, answers: [GatewayQuestionAnswer]) {
        answerCalls.append((rpcId, sessionId, answers))
    }

    func cancelQuestion(rpcId: String, sessionId: String) {
        cancelCallCount += 1
    }
}

private final class AppPreferencesSpy: AppPreferences {
    var endpoint: String
    var selectedWorkspaceID: String?
    private let sessions: [SessionSummary]
    private(set) var savedSessionSnapshots: [[SessionSummary]] = []
    private(set) var performMigrationsCallCount = 0

    init(endpoint: String, selectedWorkspaceID: String?, sessions: [SessionSummary]) {
        self.endpoint = endpoint
        self.selectedWorkspaceID = selectedWorkspaceID
        self.sessions = sessions
    }

    func loadSessions() -> [SessionSummary] { sessions }

    func saveSessions(_ sessions: [SessionSummary]) {
        savedSessionSnapshots.append(sessions)
    }

    func performMigrations() {
        performMigrationsCallCount += 1
    }
}

private final class MalformedConversationEventBridge:
    KMPConversationStoreBridging,
    KMPConversationEventBridging
{
    private var handler: ((SharedMviEvent) -> Void)?
    private(set) var receiveCallCount = 0

    func observeConversationEvents(
        _ handler: @escaping (SharedMviEvent) -> Void
    ) -> () -> Void {
        self.handler = handler
        handler(SharedMviEvent(
            schema: 2,
            sequence: 0,
            transactionId: "snapshot:conversation:0",
            domain: "conversation",
            kind: "snapshot",
            statePayloadJson: #"{"schema":1}"#,
            effectsJson: "[]",
            metadataJson: nil,
            errorCode: nil,
            errorMessage: nil
        ))
        return { [weak self] in self?.handler = nil }
    }

    func receiveEvent(eventJson: String) -> SharedMviDispatchResult {
        receiveCallCount += 1
        handler?(SharedMviEvent(
            schema: 2,
            sequence: 1,
            transactionId: "conversation-event:1",
            domain: "conversation",
            kind: "transition",
            statePayloadJson: #"{"schema":1,"sessionId":"s1","operations":[{"kind":"append-text","itemId":"missing","delta":"x","epochSeconds":1}],"replacesAll":false,"lastSequence":1}"#,
            effectsJson: "[]",
            metadataJson: nil,
            errorCode: nil,
            errorMessage: nil
        ))
        return SharedMviDispatchResult(
            accepted: true,
            transactionId: "conversation-event:1",
            eventSequence: KotlinLong(longLong: 1),
            errorCode: nil,
            errorMessage: nil
        )
    }

    func replaceSession(sessionId: String, eventsJson: String) -> SharedMviDispatchResult {
        SharedMviDispatchResult(
            accepted: false, transactionId: nil, eventSequence: nil,
            errorCode: "unsupported", errorMessage: nil
        )
    }

    func clearSession(sessionId: String) -> SharedMviDispatchResult {
        SharedMviDispatchResult(
            accepted: false, transactionId: nil, eventSequence: nil,
            errorCode: "unsupported", errorMessage: nil
        )
    }
}

private final class MalformedTrajectoryEventBridge:
    KMPTrajectoryStoreBridging,
    KMPTrajectoryEventBridging
{
    private var handler: ((SharedMviEvent) -> Void)?

    func observeTrajectoryEvents(
        _ handler: @escaping (SharedMviEvent) -> Void
    ) -> () -> Void {
        self.handler = handler
        handler(SharedMviEvent(
            schema: 2, sequence: 0,
            transactionId: "snapshot:trajectory:0",
            domain: "trajectory", kind: "snapshot",
            statePayloadJson: #"{"schema":1}"#,
            effectsJson: "[]", metadataJson: nil,
            errorCode: nil, errorMessage: nil
        ))
        return { [weak self] in self?.handler = nil }
    }

    func receiveEvent(eventJson: String) -> SharedMviDispatchResult {
        handler?(SharedMviEvent(
            schema: 2, sequence: 1,
            transactionId: "trajectory-event:1",
            domain: "trajectory", kind: "transition",
            statePayloadJson: #"{"schema":1,"sessionId":"s1","operations":[{"kind":"update","itemId":"missing","subtitleDelta":"x","endSequence":1,"endEpochSeconds":1,"appendedRecords":[]}],"replacesAll":false,"lastSequence":1}"#,
            effectsJson: "[]", metadataJson: nil,
            errorCode: nil, errorMessage: nil
        ))
        return SharedMviDispatchResult(
            accepted: true, transactionId: "trajectory-event:1",
            eventSequence: KotlinLong(longLong: 1),
            errorCode: nil, errorMessage: nil
        )
    }

    func receiveEvents(eventsJson: String) -> SharedMviDispatchResult {
        receiveEvent(eventJson: eventsJson)
    }

    func replaceSession(sessionId: String, eventsJson: String) -> SharedMviDispatchResult {
        SharedMviDispatchResult(
            accepted: false, transactionId: nil, eventSequence: nil,
            errorCode: "unsupported", errorMessage: nil
        )
    }

    func clearSession(sessionId: String) -> SharedMviDispatchResult {
        SharedMviDispatchResult(
            accepted: false, transactionId: nil, eventSequence: nil,
            errorCode: "unsupported", errorMessage: nil
        )
    }
}

private final class MalformedHistoryEventBridge:
    KMPHistoryStoreBridging,
    KMPHistoryEventBridging
{
    private var handler: ((SharedMviEvent) -> Void)?

    func observeHistoryEvents(_ handler: @escaping (SharedMviEvent) -> Void) -> () -> Void {
        self.handler = handler
        handler(SharedMviEvent(
            schema: 2, sequence: 0,
            transactionId: "snapshot:history:0",
            domain: "history", kind: "snapshot",
            statePayloadJson: #"{"schema":1,"state":{"sessions":{},"pendingSessionId":null},"eventsBySession":{}}"#,
            effectsJson: "[]", metadataJson: nil,
            errorCode: nil, errorMessage: nil
        ))
        return { [weak self] in self?.handler = nil }
    }

    func liveEventReceived(eventJson: String) -> SharedMviDispatchResult {
        handler?(SharedMviEvent(
            schema: 2, sequence: 1,
            transactionId: "history-live:1",
            domain: "history", kind: "transition",
            statePayloadJson: #"{"schema":1,"sessionId":"s1","session":null,"pendingSessionId":null,"pendingSessionChanged":false,"eventPatch":{"kind":"append","record":{"sessionId":"s1","seq":1,"time":1,"event":{"type":"assistant/message","text":"one"}},"index":9,"replacementEvents":null},"outcome":"none","failureCode":null,"completedEventCount":null,"completedByteCount":null,"completedHasMore":null}"#,
            effectsJson: "[]", metadataJson: nil,
            errorCode: nil, errorMessage: nil
        ))
        return accepted("history-live:1")
    }

    func start(sessionId: String, older: Bool, hasLocalEvents: Bool, earliestLocalSequence: KotlinInt?) -> SharedMviDispatchResult { unsupported() }
    func processingStarted(sessionId: String, rawEventCount: Int32, hasMore: Bool) -> SharedMviDispatchResult { unsupported() }
    func pageReceived(
        sessionId: String,
        eventsJson: String,
        byteCount: Int32,
        hasMore: Bool,
        nextBeforeSequence: KotlinInt?,
        remoteActivityTimestamp: KotlinDouble?
    ) -> SharedMviDispatchResult { unsupported() }
    func timedOut(sessionId: String) -> SharedMviDispatchResult { unsupported() }
    func cancelled(sessionId: String) -> SharedMviDispatchResult { unsupported() }
    func clearSession(sessionId: String) -> SharedMviDispatchResult { unsupported() }

    private func accepted(_ transactionID: String) -> SharedMviDispatchResult {
        SharedMviDispatchResult(
            accepted: true, transactionId: transactionID,
            eventSequence: KotlinLong(longLong: 1), errorCode: nil, errorMessage: nil
        )
    }

    private func unsupported() -> SharedMviDispatchResult {
        SharedMviDispatchResult(
            accepted: false, transactionId: nil, eventSequence: nil,
            errorCode: "unsupported", errorMessage: nil
        )
    }
}

@MainActor
private final class BackgroundTaskApplicationSpy: BackgroundTaskApplication {
    private(set) var beginCallCount = 0
    private(set) var endedIdentifiers: [UIBackgroundTaskIdentifier] = []
    private(set) var expirationHandler: (@Sendable () -> Void)?

    func beginBackgroundTask(
        withName taskName: String?,
        expirationHandler handler: (@Sendable () -> Void)?
    ) -> UIBackgroundTaskIdentifier {
        beginCallCount += 1
        expirationHandler = handler
        return UIBackgroundTaskIdentifier(rawValue: beginCallCount)
    }

    func endBackgroundTask(_ identifier: UIBackgroundTaskIdentifier) {
        endedIdentifiers.append(identifier)
    }
}
