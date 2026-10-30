import XCTest
import SwiftUI
import Combine
import Darwin
@testable import DeepSeekHarnessMobile

final class WorkspaceSessionListTests: XCTestCase {
    func testHeadersUseWorkspaceNamesAndStableIDsIncludingRenameAndUngroupedFallback() {
        var input = WorkspaceSessionListInput(sessions: [session("a"), session("free")],
            workspaces: [workspace("w1", ["a"])], selection: "__all_sessions__")
        input.workspaces[0].title = "移动端工程"
        let original = input.project()
        XCTAssertTrue(original.entries.contains(.section(id: "w1", title: "移动端工程", number: 2, count: 1)))
        input.workspaces[0].title = "网关服务"
        let renamed = input.project()
        XCTAssertTrue(renamed.entries.contains(.section(id: "w1", title: "网关服务", number: 2, count: 1)))
        XCTAssertEqual(original.entries.map(\.id), renamed.entries.map(\.id))
        input.workspaces[0].title = " "
        input.workspaces[0].path = "/repo/mobile-client"
        XCTAssertTrue(input.project().entries.contains(.section(id: "w1", title: "mobile-client", number: 2, count: 1)))
    }

    func testBackgroundAnimationClockPausesAndResumesWithoutJumping() {
        var clock = HarnessBackgroundAnimationClock()
        func date(_ seconds: Double) -> Date { Date(timeIntervalSinceReferenceDate: seconds) }
        clock.setPaused(true, at: date(102))
        clock.setPaused(true, at: date(105))
        XCTAssertEqual(clock.elapsed(at: date(110)), 102)
        clock.setPaused(false, at: date(110))
        XCTAssertEqual(clock.elapsed(at: date(110)), 102)
        XCTAssertEqual(clock.elapsed(at: date(111)), 103)
    }

    func testAllSessionsGroupsOnceAndHidesDraftsArchivedAndBothKindsOfChildren() {
        let sessions = [session("a", time: 10), session("b", time: 30), session("free", time: 20),
            session("blank", hasConversation: false), session("archived"),
            session("child", origin: "subagent"), session("routed-child"),
            session("fork", time: 9, parent: "a")]
        let input = WorkspaceSessionListInput(sessions: sessions,
            workspaces: [workspace("w1", ["a", "fork"]), workspace("w2", ["b", "a"])],
            archivedIDs: ["archived"], selection: "__all_sessions__",
            routingJSON: #"[{"sessionId":"routed-child","parentSessionId":"a"}]"#)
        let result = input.project()
        XCTAssertEqual(result.matchingCount, 4)
        XCTAssertEqual(result.visibleCount, 4)
        XCTAssertEqual(sessionIDs(result), ["b", "free", "a", "fork"])
        XCTAssertEqual(result.entries.filter { if case .section = $0 { return true }; return false }, [
            .section(id: "w2", title: "w2", number: 1, count: 1),
            .section(id: "__ungrouped__", title: String(localized: "未分组"), number: 2, count: 1),
            .section(id: "w1", title: "w1", number: 3, count: 2)
        ])
        XCTAssertEqual(Set(result.entries.map(\.id)).count, result.entries.count)
    }

    func testSingleWorkspaceAndUngroupedScopesStaySeparateIncludingSearchHits() {
        var input = WorkspaceSessionListInput(sessions: [session("a"), session("b"), session("free")],
            workspaces: [workspace("w1", ["a", "cache-only"]), workspace("w2", ["b"])],
            selection: "w1")
        XCTAssertEqual(sessionIDs(input.project()), ["a"])
        input.selection = "__ungrouped__"
        XCTAssertEqual(sessionIDs(input.project()), ["free"])
        input.selection = "w1"
        input.query = "needle"
        input.searchResults = [.init(sessionId: "b", snippet: "other workspace"),
            .init(sessionId: "cache-only", snippet: "needle"),
            .init(sessionId: "cache-only", snippet: "duplicate")]
        let result = input.project()
        XCTAssertEqual(sessionIDs(result), ["cache-only"])
        XCTAssertEqual(result.entries, [.session(SessionSummary(id: "cache-only", title: String(localized: "已缓存聊天"),
            lastActivity: .distantPast, isRunning: false, hasUnread: false), snippet: "needle")])
    }

    func testAllSearchMatchesTitlesIDsAndCachedContentWithoutLosingGroupOrAddingHiddenSessions() {
        var input = WorkspaceSessionListInput(sessions: [session("a", title: "Needle title"),
            session("needle-id"), session("content"), session("blank", hasConversation: false)],
            workspaces: [workspace("w1", ["a", "needle-id"]), workspace("w2", ["content"])],
            archivedIDs: ["archived"], selection: "__all_sessions__", query: "needle")
        input.searchResults = [.init(sessionId: "content", snippet: "needle content"),
            .init(sessionId: "blank", snippet: "needle"), .init(sessionId: "archived", snippet: "needle"),
            .init(sessionId: "cached", snippet: "needle"), .init(sessionId: "cached", snippet: "duplicate")]
        let result = input.project()
        XCTAssertEqual(Set(sessionIDs(result)), ["a", "needle-id", "content", "cached"])
        XCTAssertEqual(result.matchingCount, 4)
        XCTAssertEqual(result.entries.count, 7)
    }

    func testTenThousandSessionsAreNotTruncatedAndOrderIsDeterministic() {
        var input = largeInput()
        input.expandedSectionIDs = Set(input.workspaces.map(\.id))
        let result = input.project()
        XCTAssertEqual(result.matchingCount, 10_000)
        XCTAssertEqual(sessionIDs(result).count, 10_000)
        XCTAssertEqual(result.entries.count, 10_200)
        XCTAssertEqual(result, input.project())
        XCTAssertEqual(Set(result.entries.map(\.id)).count, 10_200)
    }

    func testOnlyGroupsLargerThanFiveCollapseAndExpansionIsIndependent() {
        let small = (0..<5).map { session("small-\($0)") }
        let large = (0..<6).map { session("large-\($0)") }
        let ungrouped = (0..<7).map { session("free-\($0)") }
        var input = WorkspaceSessionListInput(sessions: small + large + ungrouped,
            workspaces: [workspace("small", small.map(\.id)), workspace("large", large.map(\.id))],
            selection: "__all_sessions__")
        let collapsed = input.project()
        XCTAssertEqual(collapsed.matchingCount, 18, "总数不应因折叠而减少")
        XCTAssertEqual(sessionIDs(collapsed).count, 15)
        XCTAssertTrue(collapsed.entries.contains(.sectionToggle(id: "large", remainingCount: 1, isExpanded: false)))
        XCTAssertTrue(collapsed.entries.contains(.sectionToggle(id: "__ungrouped__", remainingCount: 2, isExpanded: false)))
        XCTAssertFalse(collapsed.entries.contains { if case .sectionToggle(id: "small", _, _) = $0 { return true }; return false })
        input.expandedSectionIDs = ["large"]
        let expanded = input.project()
        XCTAssertEqual(sessionIDs(expanded).count, 16, "只展开指定分区")
        XCTAssertTrue(expanded.entries.contains(.sectionToggle(id: "large", remainingCount: 1, isExpanded: true)))
        XCTAssertTrue(expanded.entries.contains(.sectionToggle(id: "__ungrouped__", remainingCount: 2, isExpanded: false)))
        input.selection = "large"
        input.expandedSectionIDs = []
        XCTAssertEqual(sessionIDs(input.project()).count, 6, "单个工作区不应用五条折叠限制")
        input.selection = "__ungrouped__"
        XCTAssertEqual(sessionIDs(input.project()).count, 7, "未分组视图不应用五条折叠限制")
    }

    func testTenThousandSessionsDefaultToFiveRowsPerGroupWhileKeepingFullCounts() {
        let input = largeInput()
        let result = input.project()
        XCTAssertEqual(result.matchingCount, 10_000)
        XCTAssertEqual(result.visibleCount, 10_000)
        XCTAssertEqual(sessionIDs(result).count, 500)
        XCTAssertEqual(result.entries.count, 700)
        XCTAssertEqual(Set(result.entries.map(\.id)).count, result.entries.count)
    }

    func testTenThousandSessionProjectionPerformance() {
        let input = largeInput()
        measure {
            XCTAssertEqual(input.project().matchingCount, 10_000)
        }
    }

    private func largeInput() -> WorkspaceSessionListInput {
        let sessions = (0..<10_000).map { session("s\($0)", time: Double($0 % 500)) }
        let workspaces = (0..<100).map { group in
            workspace("w\(group)", (0..<100).map { "s\(group * 100 + $0)" })
        }
        return WorkspaceSessionListInput(sessions: sessions, workspaces: workspaces, selection: "__all_sessions__")
    }

    private func sessionIDs(_ snapshot: WorkspaceSessionListSnapshot) -> [String] {
        snapshot.entries.compactMap { if case .session(let session, _) = $0 { return session.id }; return nil }
    }

    private func session(_ id: String, title: String? = nil, time: Double = 0,
                         hasConversation: Bool = true, parent: String? = nil, origin: String? = nil) -> SessionSummary {
        SessionSummary(id: id, title: title ?? id, lastActivity: Date(timeIntervalSince1970: time),
            isRunning: false, hasUnread: false, hasConversation: hasConversation, parentSessionId: parent, origin: origin)
    }

    private func workspace(_ id: String, _ sessions: [String]) -> GatewayWorkspace {
        GatewayWorkspace(workspaceId: id, path: "/\(id)", title: id, sessionIds: sessions, createdAt: "", updatedAt: "")
    }
}

@MainActor
final class WorkspaceSessionListIntegrationTests: XCTestCase {
    func testAllScopeRejectsCreationBeforeChangingSelectionOrStartingNetworkWork() async throws {
        let fixture = Fixture(count: 30)
        defer { fixture.cleanUp() }
        let selection = fixture.store.selectedSessionId
        let sessions = fixture.store.sessions
        let created = await fixture.store.prepareNewConversation()
        XCTAssertFalse(created)
        XCTAssertEqual(fixture.store.lastError, WorkspaceSessionCopy.creationPrompt)
        XCTAssertEqual(fixture.store.selectedSessionId, selection)
        XCTAssertEqual(fixture.store.sessions, sessions)
    }

    func testNativeScrollPhaseReportsActivityAndIdleWithoutPerFrameUpdates() async throws {
        guard #available(iOS 18.0, *) else { throw XCTSkip("原生滚动阶段通知需要 iOS 18") }
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let oldKey = scene.windows.first { $0.isKeyWindow }
        let window = UIWindow(windowScene: scene)
        let target = PassthroughSubject<Int, Never>()
        var activity: [Bool] = []
        let view = ScrollViewReader { proxy in
            ScrollView {
                LazyVStack {
                    ForEach(0..<100) { value in Text("Row \(value)").frame(height: 50).id(value) }
                }
            }
            .modifier(WorkspaceScrollPhaseObserver { active in
                if activity.last != active { activity.append(active) }
            })
            .onReceive(target) { value in
                withAnimation(.linear(duration: 0.6)) { proxy.scrollTo(value, anchor: .top) }
            }
        }
        window.rootViewController = UIHostingController(rootView: view)
        window.makeKeyAndVisible()
        defer { window.isHidden = true; window.rootViewController = nil; oldKey?.makeKey() }
        try await Task.sleep(for: .milliseconds(300))
        target.send(80)
        try await waitFor { activity.contains(true) }
        try await waitFor { activity.last == false }
        XCTAssertLessThanOrEqual(activity.count, 3, "只需要活动和空闲边界，不应逐帧发布")
    }

    func testScrollingDefersListPublicationsAndAppliesOnlyTheLatestSearchWhenIdle() async throws {
        let fixture = Fixture(count: 3_000)
        defer { fixture.cleanUp() }
        let model = WorkspaceSessionListModel()
        model.bind(to: fixture.store)
        try await waitFor { model.snapshot.matchingCount == 3_000 }
        var publications = 0
        let token = model.$snapshot.dropFirst().sink { _ in publications += 1 }
        model.setScrolling(true)
        model.search("任务 1")
        try await Task.sleep(for: .milliseconds(150))
        model.search("任务 2999")
        try await Task.sleep(for: .milliseconds(150))
        XCTAssertEqual(model.snapshot.matchingCount, 3_000)
        XCTAssertEqual(publications, 0)
        model.setScrolling(false)
        try await waitFor { model.snapshot.matchingCount == 1 }
        XCTAssertEqual(publications, 1)
        withExtendedLifetime(token) {}
    }

    func testToggleExpandsAndCollapsesOnlyItsOwnGroupAndSurvivesSearch() async throws {
        let fixture = Fixture(count: 300)
        defer { fixture.cleanUp() }
        let model = WorkspaceSessionListModel()
        model.bind(to: fixture.store)
        func rowCount() -> Int {
            model.snapshot.entries.filter { if case .session = $0 { return true }; return false }.count
        }
        try await waitFor { rowCount() == 15 }
        model.toggleSection("w0")
        try await waitFor { rowCount() == 110 }
        XCTAssertTrue(model.snapshot.entries.contains(.sectionToggle(id: "w1", remainingCount: 95, isExpanded: false)))
        model.search("任务 299")
        try await waitFor { model.snapshot.matchingCount == 1 }
        model.search("")
        try await waitFor { rowCount() == 110 }
        model.toggleSection("w0")
        try await waitFor { rowCount() == 15 }
        XCTAssertEqual(model.snapshot.matchingCount, 300)
    }

    func testAllScopeSurvivesWorkspaceRefreshAndPersistsSelection() async throws {
        let fixture = Fixture(count: 30)
        defer { fixture.cleanUp() }
        let store = fixture.store
        store.selectAllWorkspaces()
        let frame = try GatewayWireDecoder.decode(JSONSerialization.data(withJSONObject: [
            "kind": "workspaces", "items": [["workspaceId": "new", "path": "/new", "title": "new",
                "sessionIds": ["s0"], "createdAt": "", "updatedAt": ""]]
        ]))
        store.gateway.onFrame?(frame)
        try await waitFor { store.workspaces.first?.id == "new" }
        XCTAssertEqual(store.selectedWorkspaceId, AppStore.allWorkspacesID)
        XCTAssertEqual(fixture.preferences.selectedWorkspaceID, AppStore.allWorkspacesID)
        XCTAssertNil(store.activeWorkspace)
        store.selectWorkspace(store.workspaces[0])
        XCTAssertEqual(store.activeWorkspace?.id, "new")
        store.selectUngroupedWorkspace()
        XCTAssertTrue(store.isUngroupedWorkspaceSelected)
    }

    func testProjectionTracksLatestScopeAndQueryWithoutUnrelatedStoreRefreshes() async throws {
        let fixture = Fixture(count: 3_000)
        defer { fixture.cleanUp() }
        let model = WorkspaceSessionListModel()
        model.bind(to: fixture.store)
        try await waitFor { model.snapshot.matchingCount == 3_000 }
        model.search("任务 2999")
        try await waitFor { model.snapshot.matchingCount == 1 }
        fixture.store.selectWorkspace(fixture.store.workspaces[0])
        model.search("")
        try await waitFor { model.snapshot.matchingCount == 100 }
        XCTAssertEqual(model.snapshot.entries.count, 12)
        fixture.store.selectAllWorkspaces()
        try await waitFor { model.snapshot.matchingCount == 3_000 }
        var publications = 0
        let token = model.$snapshot.dropFirst().sink { _ in publications += 1 }
        fixture.store.lastError = "无关错误状态"
        try await Task.sleep(for: .milliseconds(200))
        XCTAssertEqual(publications, 0)
        withExtendedLifetime(token) {}
    }

    /// 挂载真实首页并驱动长列表；模拟器回调间隔仅用于诊断，不冒充真机绘制帧率。
    func testThreeThousandSessionLayoutAndScroll() async throws {
        let fixture = Fixture(count: 3_000)
        defer { fixture.cleanUp() }
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let oldKey = scene.windows.first { $0.isKeyWindow }
        let window = UIWindow(windowScene: scene)
        let view = WorkspaceView(onOpenSession: { _ in }, onNewSession: {}, onSettings: {},
            onPlugins: {}, onScheduledTasks: {})
            .environmentObject(fixture.store)
            .environmentObject(fixture.hosts)
            .modifier(AppFontScope())
            .environment(\.locale, Locale(identifier: "zh_CN"))
        let host = UIHostingController(rootView: view)
        window.rootViewController = host
        window.makeKeyAndVisible()
        defer {
            window.isHidden = true
            window.rootViewController = nil
            oldKey?.makeKey()
        }
        try await Task.sleep(for: .milliseconds(600))
        host.view.layoutIfNeeded()
        func findScroll(_ view: UIView) -> UIScrollView? {
            if let scroll = view as? UIScrollView { return scroll }
            return view.subviews.lazy.compactMap { findScroll($0) }.first
        }
        let scroll = try XCTUnwrap(findScroll(host.view))
        XCTAssertGreaterThan(scroll.contentSize.height, window.bounds.height * 10)
        let first = XCTAttachment(image: UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
            window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
        })
        first.name = "全部会话-3000条-首屏"
        first.lifetime = .keepAlways
        add(first)
        let sampler = WorkspaceScrollSampler(scroll: scroll)
        let cpuStart = processCPUSeconds()
        sampler.start()
        defer { sampler.stop() }
        try await Task.sleep(for: .seconds(3))
        sampler.stop()
        let scrollCPU = processCPUSeconds() - cpuStart
        XCTAssertGreaterThan(scroll.contentOffset.y, 1_000)
        let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
            window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
        }
        let scrolled = XCTAttachment(image: image)
        scrolled.name = "全部会话-3000条-滚动后"
        scrolled.lifetime = .keepAlways
        add(scrolled)
        let gaps = sampler.gaps.sorted()
        let p95 = gaps.isEmpty ? 0 : gaps[min(gaps.count - 1, Int(Double(gaps.count) * 0.95))]
        let report = "3000 sessions, 30 workspaces; scroll CPU=\(scrollCPU) s; display callbacks=\(gaps.count); p95 callback gap=\(p95 * 1000) ms; scroll offset=\(scroll.contentOffset.y). Simulator diagnostics only; not rendered FPS."
        print(report)
        let metrics = XCTAttachment(string: report)
        metrics.name = "全部会话-模拟器滚动诊断"
        metrics.lifetime = .keepAlways
        add(metrics)
    }

    private func waitFor(_ condition: () -> Bool) async throws {
        for _ in 0..<200 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTFail("会话列表未在两秒内更新")
    }

    private func processCPUSeconds() -> Double {
        var usage = rusage()
        getrusage(RUSAGE_SELF, &usage)
        return Double(usage.ru_utime.tv_sec + usage.ru_stime.tv_sec)
            + Double(usage.ru_utime.tv_usec + usage.ru_stime.tv_usec) / 1_000_000
    }

    @MainActor
    private final class Fixture {
        let suite = "workspace-list-test-\(UUID())"
        let defaults: UserDefaults
        let preferences: UserDefaultsAppPreferences
        let store: AppStore
        let hosts: MultiGatewayStore

        init(count: Int) {
            defaults = UserDefaults(suiteName: suite)!
            preferences = UserDefaultsAppPreferences(userDefaults: defaults)
            preferences.automaticallyConnect = false
            preferences.selectedWorkspaceID = AppStore.allWorkspacesID
            preferences.saveSessions((0..<count).map {
                SessionSummary(id: "s\($0)", title: "任务 \($0)", lastActivity: Date().addingTimeInterval(Double(-$0) * 60),
                    isRunning: false, hasUnread: false, hasConversation: true)
            })
            store = AppStore(preferences: preferences, gatewayLocalID: suite, chatHistoryCache: nil)
            store.workspaces = (0..<((count + 99) / 100)).map { group in
                GatewayWorkspace(workspaceId: "w\(group)", path: "/private/workspace-\(group)",
                    title: ["dsh-mobile", "mobile-gateway", "swift-markdown-ui", "agent-runtime", "design-system"][group % 5]
                        + (group < 5 ? "" : "-\(group)"),
                    sessionIds: (group * 100..<min(count, (group + 1) * 100)).map { "s\($0)" }, createdAt: "", updatedAt: "")
            }
            hosts = MultiGatewayStore(defaults: defaults)
        }

        func cleanUp() {
            store.deactivateGateway()
            hosts.activeStore.deactivateGateway()
            defaults.removePersistentDomain(forName: suite)
        }
    }
}

@MainActor
private final class WorkspaceScrollSampler: NSObject {
    private let scroll: UIScrollView
    private var link: CADisplayLink?
    private var previous: CFTimeInterval?
    private(set) var gaps: [CFTimeInterval] = []

    init(scroll: UIScrollView) { self.scroll = scroll }

    func start() {
        let link = CADisplayLink(target: self, selector: #selector(tick(_:)))
        link.preferredFrameRateRange = CAFrameRateRange(minimum: 60, maximum: 60, preferred: 60)
        link.add(to: .main, forMode: .common)
        self.link = link
    }

    func stop() { link?.invalidate(); link = nil }

    @objc private func tick(_ link: CADisplayLink) {
        if let previous { gaps.append(link.timestamp - previous) }
        previous = link.timestamp
        let y = min(scroll.contentOffset.y + 31, max(0, scroll.contentSize.height - scroll.bounds.height))
        scroll.setContentOffset(CGPoint(x: 0, y: y), animated: false)
    }
}
