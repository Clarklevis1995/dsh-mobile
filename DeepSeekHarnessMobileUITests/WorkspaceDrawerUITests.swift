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
        let title = app.staticTexts["conversation-page-title"]
        XCTAssertTrue(title.exists)
        XCTAssertLessThan(viewport.frame.minY, title.frame.minY, "消息视口应延伸到顶部毛玻璃后方")
        let glassPreview = XCTAttachment(screenshot: app.screenshot())
        glassPreview.name = "会话顶部毛玻璃"
        glassPreview.lifetime = .keepAlways
        add(glassPreview)
        XCTAssertEqual(title.value as? String, "对话")
        XCTAssertFalse(app.buttons["conversation-page-title"].exists)
        title.tap()
        title.press(forDuration: 1)
        XCTAssertFalse(app.otherElements["conversation-sidebar"].exists)
        XCTAssertEqual(title.value as? String, "对话", "标题不应触发侧栏或切页")
        let firstLeft = app.coordinate(withNormalizedOffset: CGVector(dx: 0.2, dy: 0.55))
        let firstRight = app.coordinate(withNormalizedOffset: CGVector(dx: 0.8, dy: 0.55))
        firstRight.press(forDuration: 0.05, thenDragTo: firstLeft)
        XCTAssertTrue(NSPredicate { _, _ in title.value as? String == "轨迹" }.evaluateAfterWaiting(timeout: 3))
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
        XCTAssertTrue(NSPredicate { _, _ in abs(duration.frame.minX - original.minX) <= 1 }.evaluateAfterWaiting(timeout: 3), "等待分页动画结束后检查位置")

        // 两页仍然常驻，滑动往返后保留轨迹本地状态。
        let wasSelected = duration.isSelected
        duration.tap()
        XCTAssertNotEqual(duration.isSelected, wasSelected)
        bodyLeft.press(forDuration: 0.05, thenDragTo: bodyRight)
        XCTAssertEqual(title.value as? String, "对话")
        bodyRight.press(forDuration: 0.05, thenDragTo: bodyLeft)
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
    func testLargeScreenSidebarButtonWorksAcrossRotation() throws {
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .portrait
        defer { XCUIDevice.shared.orientation = .portrait }
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        let alert = app.alerts.firstMatch
        if alert.waitForExistence(timeout: 2) { alert.buttons.firstMatch.tap() }
        XCTAssertTrue(app.buttons["设置"].waitForExistence(timeout: 10))
        try XCTSkipIf(app.frame.width < 600, "需要大屏窗口")
        let sessions = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "workspace-session-"))
        try XCTSkipUnless(sessions.firstMatch.waitForExistence(timeout: 10), "需要已有会话")
        let currentID = String(sessions.firstMatch.identifier.dropFirst("workspace-session-".count))
        sessions.firstMatch.tap()
        let viewport = app.collectionViews["conversation-history-viewport"]
        XCTAssertTrue(viewport.waitForExistence(timeout: 10))
        for orientation in [UIDeviceOrientation.portrait, .landscapeLeft] {
            XCUIDevice.shared.orientation = orientation
            let landscape = orientation != .portrait
            XCTAssertTrue(NSPredicate { _, _ in (app.frame.width > app.frame.height) == landscape }.evaluateAfterWaiting(timeout: 5))
            let title = app.staticTexts["conversation-page-title"]
            XCTAssertTrue(title.waitForExistence(timeout: 5))
            XCTAssertFalse(app.buttons["conversation-page-title"].exists)
            XCTAssertFalse(app.images["conversation-title-chevron"].exists)
            let original = viewport.frame
            title.tap()
            XCTAssertFalse(app.otherElements["conversation-sidebar"].exists)
            XCTAssertEqual(viewport.frame, original)
            app.buttons["conversation-sidebar-toggle"].tap()
            XCTAssertTrue(app.otherElements["conversation-sidebar"].waitForExistence(timeout: 5))
            app.buttons["conversation-sidebar-session-\(currentID)"].firstMatch.tap()
            XCTAssertTrue(NSPredicate { _, _ in !app.otherElements["conversation-sidebar"].exists }.evaluateAfterWaiting(timeout: 5))
        }
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

/// 手机和平板共用返回按钮旁的侧边栏入口，保留原会话视口和菜单操作。
final class ConversationSidebarToolbarUITests: XCTestCase {
    func testAdaptiveToolbarEntryPreservesConversation() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        let alert = app.alerts.firstMatch
        if alert.waitForExistence(timeout: 2) { alert.buttons.firstMatch.tap() }
        XCTAssertTrue(app.buttons["设置"].waitForExistence(timeout: 10))
        let sessions = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "workspace-session-"))
        try XCTSkipUnless(sessions.firstMatch.waitForExistence(timeout: 10), "需要已有会话")
        let currentID = String(sessions.firstMatch.identifier.dropFirst("workspace-session-".count))
        sessions.firstMatch.tap()
        let viewport = app.collectionViews["conversation-history-viewport"]
        XCTAssertTrue(viewport.waitForExistence(timeout: 10))
        let title = app.staticTexts["conversation-page-title"]
        let mode = app.staticTexts["conversation-mode-label"]
        XCTAssertTrue(title.waitForExistence(timeout: 5))
        XCTAssertTrue(mode.waitForExistence(timeout: 5))
        if app.frame.width < 600 {
            XCTAssertGreaterThanOrEqual(mode.frame.minY, title.frame.maxY, "窄屏模式应在标题下方")
        } else {
            XCTAssertGreaterThanOrEqual(mode.frame.minX, title.frame.maxX, "大屏模式应保留在标题右侧")
            XCTAssertEqual(mode.frame.midY, title.frame.midY, accuracy: 3, "大屏应保留单行导航布局")
        }
        XCTAssertFalse(app.buttons["conversation-page-title"].exists)
        XCTAssertFalse(app.images["conversation-title-chevron"].exists)
        title.tap()
        XCTAssertFalse(app.otherElements["conversation-sidebar"].exists)
        let toggle = app.buttons["conversation-sidebar-toggle"]
        XCTAssertTrue(toggle.waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["conversation-sidebar-floating"].exists)
        // Duo 的返回按钮位于独立系统栏，导航栏里的第一个按钮可能是会话标题。
        let nativeBack = app.buttons["BackButton"]
        let back = nativeBack.exists ? nativeBack : app.navigationBars.buttons.element(boundBy: 0)
        if app.frame.width < 600 {
            let more = app.buttons["更多"]
            XCTAssertGreaterThanOrEqual(toggle.frame.minX, title.frame.maxX)
            XCTAssertLessThanOrEqual(toggle.frame.maxX, more.frame.minX)
            XCTAssertEqual(more.frame.midY, toggle.frame.midY, accuracy: 2)
        }
        XCTAssertTrue(toggle.isHittable)
        let original = viewport.frame
        let sidebar = app.otherElements["conversation-sidebar"].firstMatch
        func capture(_ name: String) {
            let attachment = XCTAttachment(screenshot: app.screenshot())
            attachment.name = name
            attachment.lifetime = .keepAlways
            add(attachment)
        }
        capture("独立侧栏按钮与两行标题模式")
        toggle.tap()
        XCTAssertTrue(sidebar.waitForExistence(timeout: 5))
        capture("导航栏入口展开侧边栏")
        XCTAssertEqual(viewport.frame.width, original.width, accuracy: 1)
        let current = app.buttons["conversation-sidebar-session-\(currentID)"].firstMatch
        XCTAssertTrue(current.waitForExistence(timeout: 5))
        current.tap()
        XCTAssertTrue(NSPredicate { _, _ in !sidebar.exists }.evaluateAfterWaiting(timeout: 5))
        XCTAssertEqual(viewport.frame, original)
        toggle.tap()
        XCTAssertTrue(sidebar.waitForExistence(timeout: 5))
        let leftSpace = max(0, sidebar.frame.minX - viewport.frame.minX)
        let rightSpace = max(0, viewport.frame.maxX - sidebar.frame.maxX)
        let outsideX = leftSpace > rightSpace ? leftSpace / 2 : viewport.frame.width - rightSpace / 2
        viewport.coordinate(withNormalizedOffset: .zero)
            .withOffset(CGVector(dx: outsideX, dy: viewport.frame.height / 2)).tap()
        XCTAssertTrue(NSPredicate { _, _ in !sidebar.exists }.evaluateAfterWaiting(timeout: 5))
        app.buttons["更多"].tap()
        XCTAssertTrue(app.buttons["工作区文件"].waitForExistence(timeout: 3))
        viewport.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.6)).tap()
        back.tap()
        XCTAssertTrue(app.buttons["设置"].waitForExistence(timeout: 5))
    }
}
