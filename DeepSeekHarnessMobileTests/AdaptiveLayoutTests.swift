import XCTest
import SwiftUI
import UIKit
@testable import DeepSeekHarnessMobile

/// 覆盖折叠屏适配涉及的纯逻辑：
/// - `AdaptiveLayout.workspaceUsesColumns`：首页 / 会话列表何时分栏
/// - `HarnessBackgroundStyle.resolve`：展开后复用平板背景
/// - `SessionGroups.group`：会话页侧栏的工作区分组
final class AdaptiveLayoutTests: XCTestCase {

    @MainActor
    func testConversationViewportFillsPageWhileMessagesKeepReadingWidth() async throws {
        let controller = ConversationViewportController(
            onContentAvailabilityChanged: { _, _ in }, onPinnedToBottomChanged: { _ in },
            onBottomAlignmentCompleted: {}, onApproachingTop: {}
        )
        controller.loadViewIfNeeded()
        let list = try XCTUnwrap(controller.view.subviews.compactMap { $0 as? UICollectionView }.first)
        // 同一视口从手机宽度切到平板再切回，验证尺寸变化不会留下旧的正文留白。
        for (revision, width) in [CGFloat(390), 1366, 844, 390].enumerated() {
            controller.view.frame = CGRect(x: 0, y: 0, width: width, height: 500)
            controller.view.setNeedsLayout()
            controller.view.layoutIfNeeded()
            controller.apply(entries: [
                ConversationViewportEntry(id: "layout", revision: revision,
                    content: AnyView(Text("消息正文").frame(height: 80)))
            ], revision: revision, bottomInset: 0, hasConversationContent: true)
            try await Task.sleep(for: .milliseconds(100))
            list.layoutIfNeeded()

            XCTAssertEqual(list.frame, controller.view.bounds, "系统毛玻璃所依附的视口必须铺满页面")
            let frame = try XCTUnwrap(list.layoutAttributesForItem(at: IndexPath(item: 0, section: 0))).frame
            XCTAssertEqual(frame.width, min(width, 1040) - 40, accuracy: 0.5)
            XCTAssertEqual(frame.midX, width / 2, accuracy: 0.5)
        }
    }

    // MARK: - workspaceUsesColumns

    /// Duo 折叠 466×678 与展开横拿 890×626 是真机量到的；
    /// 932×430（iPhone 横屏）、1024×768（iPad）、1200×400 是参考尺寸。
    func testWorkspaceUsesColumnsMatchesDeviceShapes() {
        let cases: [(name: String, width: CGFloat, height: CGFloat, expected: Bool)] = [
            ("iPhone Duo 折叠 466×678", 466, 678, false),
            ("iPhone Duo 展开横拿 890×626", 890, 626, true),
            ("iPhone Duo 展开竖拿 626×890", 626, 890, false),
            ("iPhone 16 Pro 横屏 932×430", 932, 430, false),
            ("iPhone 16 Pro 竖屏 430×932", 430, 932, false),
            ("iPad 横屏 1024×768", 1024, 768, true),
            ("iPad 竖屏 768×1024", 768, 1024, false),
            ("宽屏但极矮 1200×400", 1200, 400, false),
        ]

        for item in cases {
            let actual = AdaptiveLayout.workspaceUsesColumns(
                size: CGSize(width: item.width, height: item.height)
            )
            XCTAssertEqual(actual, item.expected, "\(item.name) 分栏判断不符预期")
        }
    }

    /// 900 是作者原本的边界，横屏规则不能顺手把它降低。
    /// 关键是下面「竖着拿、宽度 899 / 860 都不分栏」那两条：如果谁把 900 改回 860，
    /// 这两条会立刻失败 —— 只验「该分栏的分了」是钉不住边界的。
    func testOriginalNineHundredBoundaryIsNotLowered() {
        XCTAssertTrue(AdaptiveLayout.workspaceUsesColumns(size: CGSize(width: 900, height: 700)))
        XCTAssertTrue(AdaptiveLayout.workspaceUsesColumns(size: CGSize(width: 1100, height: 800)))
        // 890×626 只有走横屏规则才分栏，竖着拿同样的宽度不分栏。
        XCTAssertTrue(AdaptiveLayout.workspaceUsesColumns(size: CGSize(width: 890, height: 626)))
        XCTAssertFalse(AdaptiveLayout.workspaceUsesColumns(size: CGSize(width: 626, height: 890)))
        // 竖着拿时，899 和 860 都必须单栏（860 是 #58 提过又被撤回的值）。
        XCTAssertFalse(AdaptiveLayout.workspaceUsesColumns(size: CGSize(width: 899, height: 1000)))
        XCTAssertFalse(AdaptiveLayout.workspaceUsesColumns(size: CGSize(width: 860, height: 1000)))
    }

    /// 无障碍大字号时不分栏，保持作者原来的行为。
    func testAccessibilityTextAlwaysUsesSingleColumn() {
        XCTAssertFalse(
            AdaptiveLayout.workspaceUsesColumns(
                size: CGSize(width: 1024, height: 768),
                accessibilityText: true
            )
        )
    }

    // MARK: - 首页背景

    func testBackgroundStyleFollowsUnfoldedSizeInBothOrientations() {
        let cases: [(size: CGSize, expected: HarnessBackgroundStyle)] = [
            (CGSize(width: 466, height: 678), .phone),
            (CGSize(width: 678, height: 466), .phone),
            (CGSize(width: 890, height: 626), .pad),
            (CGSize(width: 626, height: 890), .pad),
            (CGSize(width: 932, height: 430), .phone),
            (CGSize(width: 430, height: 932), .phone),
            (CGSize(width: 890, height: 599), .phone),
            (CGSize(width: 600, height: 890), .pad),
        ]

        for item in cases {
            XCTAssertEqual(
                HarnessBackgroundStyle.resolve(size: item.size, isPad: false),
                item.expected,
                "背景风格不符预期：\(item.size)"
            )
        }
    }

    func testPadBackgroundStyleIsPreservedForNarrowWindows() {
        for size in [CGSize(width: 744, height: 1133), CGSize(width: 1133, height: 744),
                     CGSize(width: 375, height: 1024)] {
            XCTAssertEqual(HarnessBackgroundStyle.resolve(size: size, isPad: true), .pad)
        }
    }

    // MARK: - SessionGroups

    private func session(_ id: String, hasConversation: Bool? = true) -> SessionSummary {
        SessionSummary(
            id: id,
            title: id,
            lastActivity: .distantPast,
            isRunning: false,
            hasUnread: false,
            hasConversation: hasConversation
        )
    }

    private func workspace(_ id: String, sessions: [String]) -> GatewayWorkspace {
        GatewayWorkspace(
            workspaceId: id,
            path: "/tmp/\(id)",
            title: id,
            sessionIds: sessions,
            createdAt: "",
            updatedAt: ""
        )
    }

    func testSessionBelongingToWorkspaceIsListedUnderIt() {
        let groups = SessionGroups.group(
            sessions: [session("a")],
            workspaces: [workspace("w1", sessions: ["a"])]
        )
        XCTAssertEqual(groups.byWorkspace["w1"]?.map(\.id), ["a"])
        XCTAssertTrue(groups.ungrouped.isEmpty)
    }

    func testSessionWithoutWorkspaceFallsIntoUngrouped() {
        let groups = SessionGroups.group(
            sessions: [session("a"), session("b")],
            workspaces: [workspace("w1", sessions: ["a"])]
        )
        XCTAssertEqual(groups.byWorkspace["w1"]?.map(\.id), ["a"])
        XCTAssertEqual(groups.ungrouped.map(\.id), ["b"])
    }

    /// 同一个会话在两个工作区里时，两边各列一份（与网页版侧栏一致）。
    func testSessionInMultipleWorkspacesAppearsInEach() {
        let groups = SessionGroups.group(
            sessions: [session("a")],
            workspaces: [workspace("w1", sessions: ["a"]), workspace("w2", sessions: ["a"])]
        )
        XCTAssertEqual(groups.byWorkspace["w1"]?.map(\.id), ["a"])
        XCTAssertEqual(groups.byWorkspace["w2"]?.map(\.id), ["a"])
        XCTAssertTrue(groups.ungrouped.isEmpty)
    }

    /// 工作区里登记了不存在、或没有会话内容的历史会话时，不产生空条目。
    func testSessionsWithoutConversationAreExcluded() {
        let groups = SessionGroups.group(
            sessions: [session("a", hasConversation: false)],
            workspaces: [workspace("w1", sessions: ["a"]), workspace("w2", sessions: ["missing"])]
        )
        XCTAssertNil(groups.byWorkspace["w1"])
        XCTAssertNil(groups.byWorkspace["w2"])
        XCTAssertTrue(groups.ungrouped.isEmpty)
    }

    func testEmptyInputProducesEmptyGroups() {
        let groups = SessionGroups.group(sessions: [], workspaces: [])
        XCTAssertTrue(groups.byWorkspace.isEmpty)
        XCTAssertTrue(groups.ungrouped.isEmpty)
    }
}
