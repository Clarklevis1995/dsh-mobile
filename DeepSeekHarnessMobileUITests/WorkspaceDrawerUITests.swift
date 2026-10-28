import XCTest
import UIKit

final class TrajectoryGestureUITests: XCTestCase {
    func testOverviewDragDoesNotSwipePageAndBodyStillCanSwipe() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        let alert = app.alerts.firstMatch
        if alert.waitForExistence(timeout: 2) { alert.buttons.firstMatch.tap() }
        XCTAssertTrue(app.buttons["设置"].waitForExistence(timeout: 10))
        let sessions = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "workspace-session-"))
        try XCTSkipUnless(sessions.firstMatch.waitForExistence(timeout: 10), "需要一个已有会话，只读检查手势")
        let referenceSession = sessions.matching(NSPredicate(format: "label CONTAINS %@", "国内手机")).firstMatch
        (referenceSession.exists ? referenceSession : sessions.firstMatch).tap()
        let viewport = app.collectionViews["conversation-history-viewport"]
        XCTAssertTrue(viewport.waitForExistence(timeout: 10))
        XCTAssertEqual(app.segmentedControls.count, 0)
        let title = app.buttons["conversation-page-title"]
        XCTAssertTrue(title.exists)
        XCTAssertLessThan(viewport.frame.minY, title.frame.minY, "消息视口应延伸到顶部毛玻璃后方")
        let glassPreview = XCTAttachment(screenshot: app.screenshot())
        glassPreview.name = "会话顶部毛玻璃"
        glassPreview.lifetime = .keepAlways
        add(glassPreview)
        XCTAssertEqual(title.value as? String, "对话")
        title.tap()
        XCTAssertEqual(title.value as? String, "轨迹")
        let duration = app.buttons["trajectory-duration-toggle"]
        XCTAssertTrue(duration.waitForExistence(timeout: 10))
        XCTAssertGreaterThanOrEqual(duration.frame.minY, title.frame.maxY, "轨迹操作区不能被导航栏遮挡")
        let original = duration.frame
        let frame = app.frame
        // 从摘要和图表两处向右拖，足以触发外层返回“对话”的翻页阈值。
        for y in [original.midY, original.maxY + 30] {
            let start = app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: frame.width * 0.35, dy: y))
            let end = app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: frame.width * 0.85, dy: y))
            for _ in 0..<2 {
                start.press(forDuration: 0.05, thenDragTo: end)
                XCTAssertEqual(title.value as? String, "轨迹")
                XCTAssertEqual(duration.frame.minX, original.minX, accuracy: 1)
            }
            end.press(forDuration: 0.05, thenDragTo: start)
            XCTAssertEqual(duration.frame.minX, original.minX, accuracy: 1)
        }
        let bodyY = max(original.maxY + 150, frame.height * 0.55)
        let bodyLeft = app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: frame.width * 0.25, dy: bodyY))
        let bodyRight = app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: frame.width * 0.85, dy: bodyY))
        bodyLeft.press(forDuration: 0.05, thenDragTo: bodyRight)
        XCTAssertEqual(title.value as? String, "对话")
        bodyRight.press(forDuration: 0.05, thenDragTo: bodyLeft)
        XCTAssertEqual(title.value as? String, "轨迹")
        XCTAssertEqual(duration.frame.minX, original.minX, accuracy: 1)

        // 标题的精确切换入口，以及两页常驻时的轨迹本地状态。
        let wasSelected = duration.isSelected
        duration.tap()
        XCTAssertNotEqual(duration.isSelected, wasSelected)
        title.press(forDuration: 1)
        let conversation = app.buttons["对话"].firstMatch
        XCTAssertTrue(conversation.waitForExistence(timeout: 3))
        conversation.tap()
        XCTAssertEqual(title.value as? String, "对话")
        title.press(forDuration: 1)
        let trajectory = app.buttons["轨迹"].firstMatch
        XCTAssertTrue(trajectory.waitForExistence(timeout: 3))
        trajectory.tap()
        XCTAssertEqual(title.value as? String, "轨迹")
        XCTAssertEqual(duration.isSelected, !wasSelected)
    }
}

final class WorkspaceDrawerUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launch()
        let alert = app.alerts.firstMatch
        if alert.exists { alert.buttons.firstMatch.tap() }
        XCTAssertTrue(app.buttons["设置"].waitForExistence(timeout: 10))
    }

    func testRightSwipeFromSettingsOpensDrawerWithoutOpeningSettings() {
        dragRight(from: app.buttons["设置"])
        XCTAssertTrue(homeSettings.frame.minX > app.windows.firstMatch.frame.width, "主界面未右移：\(homeSettings.frame)")
        XCTAssertFalse(app.navigationBars["设置"].exists)
    }

    func testRightSwipeFromWorkspaceMenuOpensDrawerWithoutOpeningMenu() {
        let workspace = app.buttons["选择工作区"]
        XCTAssertTrue(workspace.waitForExistence(timeout: 5))
        let originalY = workspace.frame.midY
        dragRight(from: workspace)
        XCTAssertTrue(homeSettings.frame.minX > app.windows.firstMatch.frame.width, "主界面未右移：\(homeSettings.frame)")
        XCTAssertEqual(workspace.frame.midY, originalY, accuracy: 2, "右滑同时触发了纵向滚动")
        XCTAssertFalse(app.buttons["添加工作区"].exists)
    }

    func testRightSwipeFromHostSwitcherOpensDrawerWithoutOpeningHostList() {
        let host = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "当前主机：")).firstMatch
        XCTAssertTrue(host.waitForExistence(timeout: 5))
        dragRight(from: host)
        XCTAssertTrue(homeSettings.frame.minX > app.windows.firstMatch.frame.width, "主界面未右移：\(homeSettings.frame)")
        XCTAssertFalse(app.navigationBars["主机"].exists)
    }

    func testRightSwipeFromNewSessionOpensDrawerWithoutCreatingSession() {
        let newSession = app.buttons["新建会话"]
        XCTAssertTrue(newSession.waitForExistence(timeout: 5))
        dragRight(from: newSession)
        XCTAssertTrue(homeSettings.frame.minX > app.windows.firstMatch.frame.width, "主界面未右移：\(homeSettings.frame)")
        XCTAssertFalse(app.navigationBars["新建会话"].exists)
    }

    func testRightSwipeFromSearchOpensDrawerWithoutKeyboard() {
        let search = app.textFields.firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        dragRight(from: search)
        XCTAssertTrue(homeSettings.frame.minX > app.windows.firstMatch.frame.width, "主界面未右移：\(homeSettings.frame)")
        XCTAssertFalse(app.keyboards.firstMatch.exists)
    }

    func testRightSwipeFromAuthenticationButtonOpensDrawerWithoutMenu() {
        let authentication = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "设备认证")).firstMatch
        XCTAssertTrue(authentication.waitForExistence(timeout: 5))
        dragRight(from: authentication)
        XCTAssertTrue(homeSettings.frame.minX > app.windows.firstMatch.frame.width, "主界面未右移：\(homeSettings.frame)")
    }

    func testVerticalSwipeDoesNotOpenDrawer() {
        let start = app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.45, dy: 0.68))
        let end = app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.45, dy: 0.35))
        start.press(forDuration: 0.05, thenDragTo: end)
        XCTAssertTrue(homeSettings.frame.minX < app.windows.firstMatch.frame.width, "纵向滑动误开侧栏：\(homeSettings.frame)")
        XCTAssertTrue(app.buttons["设置"].exists)
    }

    func testSettingsTapStillOpensSettings() {
        homeSettings.tap()
        XCTAssertTrue(app.navigationBars["设置"].waitForExistence(timeout: 3))
    }

    func testMarkdownLongPressOpensFullScreenSelectionAndRestoresReadingPosition() {
        openFontSizePage()
        let preview = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "调整后的字体大小")).firstMatch
        XCTAssertTrue(preview.waitForExistence(timeout: 3))
        let frame = preview.frame
        preview.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.3)).press(forDuration: 0.8)
        let selectText = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "选择文字")).firstMatch
        XCTAssertTrue(selectText.waitForExistence(timeout: 3), "实际长按必须弹出菜单")
        XCTAssertEqual(preview.frame.minX, frame.minX, accuracy: 1)
        XCTAssertEqual(preview.frame.minY, frame.minY, accuracy: 1)
        XCTAssertEqual(preview.frame.width, frame.width, accuracy: 1)
        XCTAssertEqual(preview.frame.height, frame.height, accuracy: 1)
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "实际长按 Markdown 弹出菜单且正文不缩放"
        attachment.lifetime = .keepAlways
        add(attachment)
        selectText.tap()
        let selection = app.textViews["markdown-text-selection"]
        XCTAssertTrue(selection.waitForExistence(timeout: 3))
        XCTAssertTrue(app.staticTexts["选择文字"].exists)
        XCTAssertGreaterThan(selection.frame.height, app.windows.firstMatch.frame.height * 0.5, "选择页应使用独立的全屏滚动区域")
        XCTAssertFalse(app.keyboards.firstMatch.exists, "只读选择页不能弹出键盘")
        let pageAttachment = XCTAttachment(screenshot: app.screenshot())
        pageAttachment.name = "独立全屏文字选择页"
        pageAttachment.lifetime = .keepAlways
        add(pageAttachment)
        let selectAll = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "全选")).firstMatch
        XCTAssertTrue(selectAll.waitForExistence(timeout: 3))
        selectAll.tap()
        app.buttons["markdown-selection-close"].tap()
        XCTAssertTrue(preview.waitForExistence(timeout: 3))
        XCTAssertEqual(preview.frame.minX, frame.minX, accuracy: 1)
        XCTAssertEqual(preview.frame.minY, frame.minY, accuracy: 1, "关闭后应恢复原来的阅读位置")
        XCTAssertEqual(preview.frame.width, frame.width, accuracy: 1)
        XCTAssertEqual(preview.frame.height, frame.height, accuracy: 1)
    }

    func testFontSizePreviewUpdatesAndPersistsAfterRelaunch() {
        openFontSizePage()
        let slider = app.sliders["font-size-slider"]
        let followsSystem = app.switches["font-size-follow-system"]
        if followsSystem.value as? String == "1" { tapFontSystemSwitch(followsSystem) }
        let reset = app.buttons["font-size-reset"]
        reset.tap()
        let title = app.staticTexts["font-size-preview-title"]
        XCTAssertTrue(title.exists)
        let initialHeight = title.frame.height
        slider.adjust(toNormalizedSliderPosition: 1)
        XCTAssertTrue(title.frame.height > initialHeight * 1.2)
        XCTAssertEqual(slider.value as? String, "150%")

        tapFontSystemSwitch(followsSystem)
        XCTAssertEqual(followsSystem.value as? String, "1")
        let preview = XCTAttachment(screenshot: app.screenshot())
        preview.lifetime = .keepAlways
        add(preview)
        XCTAssertFalse(slider.isEnabled)
        XCTAssertEqual(title.frame.height, initialHeight, accuracy: 1)
        tapFontSystemSwitch(followsSystem)
        XCTAssertEqual(slider.value as? String, "150%")

        app.navigationBars["字体大小"].buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.navigationBars["设置"].exists)
        XCTAssertFalse(slider.exists)
        app.terminate()
        app.launch()
        let alert = app.alerts.firstMatch
        if alert.exists { alert.buttons.firstMatch.tap() }
        openFontSizePage()
        XCTAssertEqual(slider.value as? String, "150%")
        reset.tap()
        XCTAssertEqual(slider.value as? String, "100%")
        let finalPreview = XCTAttachment(screenshot: app.screenshot())
        finalPreview.name = "字体大小预览页"
        finalPreview.lifetime = .keepAlways
        add(finalPreview)
    }

    private func tapFontSystemSwitch(_ element: XCUIElement) {
        element.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap()
    }

    private func openFontSizePage() {
        homeSettings.tap()
        let entry = app.buttons["font-size-settings-row"]
        for _ in 0..<6 {
            if entry.exists && entry.isHittable { break }
            app.swipeUp()
        }
        XCTAssertTrue(entry.waitForExistence(timeout: 3))
        XCTAssertFalse(app.sliders["font-size-slider"].exists, "设置页只应显示入口")
        entry.tap()
        XCTAssertTrue(app.navigationBars["字体大小"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.sliders["font-size-slider"].exists)
    }

    func testLeftSwipeClosesOpenDrawer() {
        let workspace = app.buttons["选择工作区"]
        XCTAssertTrue(workspace.waitForExistence(timeout: 5))
        dragRight(from: workspace)
        XCTAssertTrue(homeSettings.frame.minX > app.windows.firstMatch.frame.width)

        let start = app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5))
        let end = app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.2, dy: 0.5))
        start.press(forDuration: 0.05, thenDragTo: end)
        XCTAssertTrue(homeSettings.frame.minX < app.windows.firstMatch.frame.width, "侧栏未关闭：\(homeSettings.frame)")
    }

    func testDrawerItemRemainsTappableAfterSwipe() {
        let workspace = app.buttons["选择工作区"]
        XCTAssertTrue(workspace.waitForExistence(timeout: 5))
        dragRight(from: workspace)
        XCTAssertTrue(homeSettings.frame.minX > app.windows.firstMatch.frame.width)
        app.buttons["插件"].tap()
        XCTAssertTrue(app.navigationBars["插件"].waitForExistence(timeout: 3))
    }

    func testLeftSwipeOnDrawerSurfaceClosesWithoutOpeningPlugin() {
        app.buttons["打开侧边栏"].tap()
        XCTAssertTrue(homeSettings.frame.minX > app.windows.firstMatch.frame.width)

        let plugin = app.buttons["插件"]
        XCTAssertTrue(plugin.exists)
        let start = plugin.coordinate(withNormalizedOffset: CGVector(dx: 0.8, dy: 0.5))
        let end = plugin.coordinate(withNormalizedOffset: CGVector(dx: 0.1, dy: 0.5))
        start.press(forDuration: 0.05, thenDragTo: end)

        XCTAssertTrue(homeSettings.frame.minX < app.windows.firstMatch.frame.width, "在侧栏上左滑未关闭")
        XCTAssertFalse(app.navigationBars["插件"].exists, "左滑误触发插件入口")
    }

    func testSmallSlowLeftDragKeepsDrawerOpenAndAllowsNextClose() {
        app.buttons["打开侧边栏"].tap()
        let closeControl = app.buttons["关闭侧边栏"]
        XCTAssertTrue(closeControl.exists)
        let openX = closeControl.frame.minX
        XCTAssertTrue(openX > app.windows.firstMatch.frame.width * 0.7)

        let start = app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: 0.6))
        let end = start.withOffset(CGVector(dx: -16, dy: 0))
        start.press(forDuration: 0.05, thenDragTo: end, withVelocity: .slow, thenHoldForDuration: 0.5)
        let returnedToOpen = XCTNSPredicateExpectation(
            predicate: NSPredicate { _, _ in abs(closeControl.frame.minX - openX) <= 2 },
            object: nil
        )
        let result = XCTWaiter.wait(for: [returnedToOpen], timeout: 3)
        if result != .completed {
            let screenshot = XCTAttachment(screenshot: app.screenshot())
            screenshot.lifetime = .keepAlways
            add(screenshot)
        }
        XCTAssertEqual(result, .completed, "短距离左滑应回到打开位置")

        app.buttons["关闭侧边栏"].tap()
        XCTAssertTrue(homeSettings.frame.minX < app.windows.firstMatch.frame.width, "拖动后关闭层丢失")
        homeSettings.tap()
        XCTAssertTrue(app.navigationBars["设置"].waitForExistence(timeout: 3))
    }

    // 拖到实际窗口边缘，避免平板右上角按钮只移动十余点而未达到开栏阈值。
    private func dragRight(from element: XCUIElement) {
        let start = element.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        let end = app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.995, dy: start.screenPoint.y / app.windows.firstMatch.frame.height))
        start.press(forDuration: 0.05, thenDragTo: end)
    }

    private var homeSettings: XCUIElement { app.buttons["设置"] }
}

final class ScheduledTasksToolbarUITests: XCTestCase {
    func testSearchAndFilterUseSeparateToolbarItems() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        let alert = app.alerts.firstMatch
        if alert.waitForExistence(timeout: 2) { alert.buttons.firstMatch.tap() }
        XCTAssertTrue(app.buttons["打开侧边栏"].waitForExistence(timeout: 10))
        app.buttons["打开侧边栏"].tap()
        app.buttons["定时任务"].tap()
        XCTAssertTrue(app.navigationBars["定时任务"].waitForExistence(timeout: 5))

        let search = app.buttons["scheduled-tasks-search"]
        let filter = app.buttons["scheduled-tasks-filter"]
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        XCTAssertTrue(search.isHittable)
        XCTAssertTrue(filter.isHittable)
        XCTAssertFalse(search.frame.intersects(filter.frame), "搜索和筛选不能互相重叠")
        let window = app.windows.firstMatch.frame
        for button in [search, filter] {
            XCTAssertGreaterThanOrEqual(button.frame.minX, window.minX)
            XCTAssertLessThanOrEqual(button.frame.maxX, window.maxX)
        }
        if #available(iOS 27.1, *), UIDevice.current.name.contains("Duo"), window.width > window.height {
            XCTAssertEqual(search.frame.midX, filter.frame.midX, accuracy: 3)
            XCTAssertGreaterThan(abs(search.frame.midY - filter.frame.midY), 35)
        }

        filter.tap()
        XCTAssertTrue(app.buttons["全部"].waitForExistence(timeout: 5))
        app.buttons["全部"].tap()
        search.tap()
        let field = app.textFields["搜索定时任务"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        XCTAssertGreaterThan(field.frame.width, 80, "输入框需要保留横向输入空间")
        XCTAssertGreaterThanOrEqual(field.frame.minX, window.minX)
        XCTAssertLessThanOrEqual(field.frame.maxX, window.maxX)
        app.buttons["关闭搜索"].tap()
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        XCTAssertTrue(filter.isHittable)
    }
}

final class WorkspaceSafeAreaUITests: XCTestCase {
    func testHomeControlsAdaptToAvailableWindowAndRemainUsable() throws {
        continueAfterFailure = false
        // 姿态由 Device Hub 驱动，本用例检查当前真实窗口。
        let isDuo = UIDevice.current.name.contains("Duo")
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        let alert = app.alerts.firstMatch
        if alert.waitForExistence(timeout: 2) { alert.buttons.firstMatch.tap() }
        XCTAssertTrue(app.buttons["设置"].waitForExistence(timeout: 10))

        // Duo 的根应用范围与实际窗口坐标不同，控件边界以窗口为准。
        let window = app.windows.firstMatch.frame
        let settings = app.buttons["设置"]
        let authentication = app.buttons.matching(
            NSPredicate(format: "label BEGINSWITH %@", "设备认证")
        ).firstMatch
        let workspace = app.buttons["选择工作区"]
        let search = app.textFields.firstMatch
        XCTAssertTrue(settings.waitForExistence(timeout: 5))
        XCTAssertTrue(authentication.isHittable)
        XCTAssertTrue(workspace.isHittable)
        XCTAssertTrue(search.exists)
        XCTAssertEqual(
            app.buttons.matching(NSPredicate(format: "label == %@", "设置")).count,
            1, "入口不能同时出现在页头和系统栏"
        )
        for element in [settings, authentication, workspace, search] {
            XCTAssertGreaterThanOrEqual(element.frame.minX, window.minX)
            XCTAssertLessThanOrEqual(element.frame.maxX, window.maxX)
        }
        if #available(iOS 27.1, *), isDuo, window.width > window.height {
            XCTAssertEqual(settings.frame.midX, authentication.frame.midX, accuracy: 3)
            XCTAssertGreaterThan(abs(settings.frame.midY - authentication.frame.midY), 35)
            XCTAssertTrue(
                settings.frame.minX >= max(workspace.frame.maxX, search.frame.maxX) ||
                settings.frame.maxX <= min(workspace.frame.minX, search.frame.minX),
                "竖向系统栏必须与正文分开，左右侧分别适配"
            )
        }
        if !isDuo {
            let image = XCTAttachment(screenshot: app.screenshot())
            image.name = "首页安全区 \(UIDevice.current.name) \(window.size)"
            image.lifetime = .keepAlways
            add(image)
        }

        let settingsX = settings.frame.minX
        app.buttons["打开侧边栏"].tap()
        let close = app.buttons["关闭侧边栏"]
        XCTAssertTrue(close.waitForExistence(timeout: 5))
        close.tap()
        XCTAssertTrue(NSPredicate { _, _ in
            abs(settings.frame.minX - settingsX) <= 1
        }.evaluateAfterWaiting(timeout: 5), "等待抽屉回位后再点击认证入口")
        authentication.tap()
        XCTAssertTrue(app.buttons["手动输入配对信息"].waitForExistence(timeout: 5))
        app.buttons["手动输入配对信息"].tap()
        XCTAssertTrue(app.navigationBars["设备认证"].waitForExistence(timeout: 5))
        app.buttons["完成"].tap()
        settings.tap()
        let settingsBar = app.navigationBars["设置"]
        XCTAssertTrue(settingsBar.waitForExistence(timeout: 5))
        // Duo 的返回按钮位于竖向系统栏，不一定是 NavigationBar 的子元素。
        let back = settingsBar.buttons.firstMatch.exists
            ? settingsBar.buttons.firstMatch
            : app.buttons.matching(NSPredicate(
                format: "identifier == %@ OR identifier == %@ OR label == %@ OR label == %@",
                "BackButton", "Back", "返回", "Back"
            )).firstMatch
        XCTAssertTrue(back.waitForExistence(timeout: 5), app.debugDescription)
        back.tap()
        XCTAssertTrue(app.buttons["选择工作区"].waitForExistence(timeout: 5))
    }
}

/// 在 iPad 上检查实际控件边界和旋转后的重排，避免退回手机兼容窗口。
final class TabletAdaptiveLayoutUITests: XCTestCase {
    func testConversationSidebarFloatsAndSwitchesSessionsWithoutResizing() throws {
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        defer { XCUIDevice.shared.orientation = .portrait }
        let alert = app.alerts.firstMatch
        if alert.waitForExistence(timeout: 2) { alert.buttons.firstMatch.tap() }
        XCTAssertTrue(app.buttons["设置"].waitForExistence(timeout: 10))
        try XCTSkipIf(app.frame.width < 700, "此用例需要宽屏窗口")

        // 只读取已有会话，不创建、发送、删除会话或修改网关配对。
        let homeSessions = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "workspace-session-"))
        try XCTSkipUnless(homeSessions.firstMatch.waitForExistence(timeout: 10), "需要至少一个已有会话")
        let initialSession = homeSessions.firstMatch
        let initialID = String(initialSession.identifier.dropFirst("workspace-session-".count))
        initialSession.tap()

        let viewport = app.collectionViews["conversation-history-viewport"]
        let toggle = app.buttons["conversation-sidebar-toggle"]
        let sidebar = app.otherElements["conversation-sidebar"].firstMatch
        XCTAssertTrue(viewport.waitForExistence(timeout: 10))
        XCTAssertTrue(toggle.waitForExistence(timeout: 5))
        XCTAssertFalse(sidebar.exists, "进入会话时侧栏不应自动出现")
        let originalFrame = viewport.frame

        toggle.tap()
        XCTAssertTrue(sidebar.waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["收起侧边栏"].exists)
        XCTAssertEqual(viewport.frame.minX, originalFrame.minX, accuracy: 1)
        XCTAssertEqual(viewport.frame.width, originalFrame.width, accuracy: 1, "侧栏不能挤压会话正文")
        XCTAssertGreaterThan(app.buttons["设置"].frame.maxY - sidebar.frame.minY,
                             originalFrame.height * 0.85, "侧栏应撑满可用显示高度")
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "会话内悬浮侧栏，正文宽度保持不变"
        screenshot.lifetime = .keepAlways
        add(screenshot)

        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        XCTAssertTrue(NSPredicate { _, _ in !sidebar.exists }.evaluateAfterWaiting(timeout: 5), "点击外部应关闭侧栏")
        XCTAssertEqual(viewport.frame.width, originalFrame.width, accuracy: 1)

        // 点击当前会话也应关闭浮层，不重复进入导航栈。
        toggle.tap()
        let currentRow = app.buttons["conversation-sidebar-session-\(initialID)"].firstMatch
        XCTAssertTrue(currentRow.waitForExistence(timeout: 5))
        currentRow.tap()
        XCTAssertTrue(NSPredicate { _, _ in !sidebar.exists }.evaluateAfterWaiting(timeout: 5))
        XCTAssertTrue(viewport.exists)

        toggle.tap()
        let otherRow = app.buttons.matching(NSPredicate(
            format: "identifier BEGINSWITH %@ AND identifier != %@",
            "conversation-sidebar-session-", "conversation-sidebar-session-\(initialID)"
        )).firstMatch
        if otherRow.waitForExistence(timeout: 3) {
            let otherID = otherRow.identifier
            let otherTitle = otherRow.staticTexts.firstMatch.label
            otherRow.tap()
            XCTAssertTrue(NSPredicate { _, _ in !sidebar.exists }.evaluateAfterWaiting(timeout: 5))
            XCTAssertTrue(viewport.waitForExistence(timeout: 5))
            XCTAssertTrue(app.navigationBars[otherTitle].waitForExistence(timeout: 5), "应切换到所选会话")
            XCTAssertEqual(viewport.frame.width, originalFrame.width, accuracy: 1)
            toggle.tap()
            XCTAssertTrue(app.buttons[otherID].firstMatch.waitForExistence(timeout: 5))
            app.buttons["conversation-sidebar-session-\(initialID)"].firstMatch.tap()
            XCTAssertTrue(NSPredicate { _, _ in !sidebar.exists }.evaluateAfterWaiting(timeout: 5))
        } else {
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        }

        toggle.tap()
        XCTAssertTrue(sidebar.waitForExistence(timeout: 5))
        XCUIDevice.shared.orientation = .landscapeLeft
        XCTAssertTrue(NSPredicate { _, _ in app.frame.width > app.frame.height }.evaluateAfterWaiting(timeout: 5))
        XCTAssertTrue(sidebar.waitForExistence(timeout: 5))
        XCTAssertTrue(sidebar.isHittable)
        XCTAssertTrue(app.buttons["设置"].isHittable, "旋转后浮层底部仍应可访问")
        XCTAssertGreaterThan(app.buttons["设置"].frame.maxY - sidebar.frame.minY,
                             viewport.frame.height * 0.85, "旋转后侧栏高度应跟随可用区域")
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()

        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.buttons["新建会话"].waitForExistence(timeout: 5))
        XCTAssertFalse(toggle.exists, "首页不应持有会话侧栏入口")
        XCTAssertFalse(sidebar.exists)
    }

    func testTabletWorkspaceRotationAndSecondaryPages() throws {
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        defer { XCUIDevice.shared.orientation = .portrait }
        let systemAlert = XCUIApplication(bundleIdentifier: "com.apple.springboard").alerts.firstMatch
        if systemAlert.waitForExistence(timeout: 5) { systemAlert.buttons.firstMatch.tap() }
        let alert = app.alerts.firstMatch
        if alert.exists { alert.buttons.firstMatch.tap() }
        XCTAssertTrue(app.buttons["设置"].waitForExistence(timeout: 10))
        try XCTSkipIf(app.frame.width < 700, "此用例需要 iPad 原生窗口")

        for orientation in [UIDeviceOrientation.portrait, .landscapeLeft, .landscapeRight] {
            XCUIDevice.shared.orientation = orientation
            let landscape = orientation != .portrait
            XCTAssertTrue(NSPredicate { _, _ in
                (app.frame.width > app.frame.height) == landscape
            }.evaluateAfterWaiting(timeout: 5))
            let workspace = app.buttons["选择工作区"]
            let search = app.textFields.firstMatch
            XCTAssertTrue(workspace.waitForExistence(timeout: 3))
            XCTAssertTrue(search.exists)
            for element in [workspace, search, app.buttons["新建会话"], app.buttons["设置"]] {
                XCTAssertTrue(element.isHittable, "旋转后控件不可用：\(element)")
                XCTAssertGreaterThanOrEqual(element.frame.minX, app.frame.minX)
                XCTAssertLessThanOrEqual(element.frame.maxX, app.frame.maxX)
            }
            // 13 英寸横竖屏都应利用原生宽度；横屏两栏的搜索框位于工作区右侧。
            if landscape {
                XCTAssertGreaterThan(search.frame.minX, workspace.frame.maxX)
            }
            let image = XCTAttachment(screenshot: app.screenshot())
            image.name = "iPad 首页 \(orientation.rawValue)"
            image.lifetime = .keepAlways
            add(image)
        }

        app.buttons["设置"].tap()
        let settingsBar = app.navigationBars["设置"]
        XCTAssertTrue(settingsBar.waitForExistence(timeout: 5))
        XCTAssertEqual(settingsBar.frame.width, app.frame.width, accuracy: 2)
        settingsBar.buttons.element(boundBy: 0).tap()
        app.buttons["打开侧边栏"].tap()
        app.buttons["插件"].tap()
        XCTAssertTrue(app.navigationBars["插件"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.navigationBars["插件"].frame.width, app.frame.width, accuracy: 2)
        app.navigationBars["插件"].buttons.element(boundBy: 0).tap()
        app.buttons["打开侧边栏"].tap()
        app.buttons["定时任务"].tap()
        XCTAssertTrue(app.navigationBars["定时任务"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.navigationBars["定时任务"].frame.width, app.frame.width, accuracy: 2)
    }
}

private extension NSPredicate {
    func evaluateAfterWaiting(timeout: TimeInterval) -> Bool {
        XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: self, object: nil)], timeout: timeout) == .completed
    }
}
