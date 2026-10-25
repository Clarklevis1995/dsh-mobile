import XCTest
@testable import DeepSeekHarnessMobile

/// 覆盖折叠屏适配涉及的两处纯逻辑：
/// - `AdaptiveLayout.workspaceUsesColumns`：首页 / 会话列表何时分栏
/// - `SessionGroups.group`：会话页侧栏的工作区分组
final class AdaptiveLayoutTests: XCTestCase {

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
