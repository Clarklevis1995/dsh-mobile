import XCTest

final class BotModeUITests: XCTestCase {
    @MainActor
    func testRealHostHomeChatAndNativeControls() throws {
        guard ProcessInfo.processInfo.environment["DSH_BOT_PROBE_URL"] == "http://127.0.0.1:3098" else {
            throw XCTSkip("需要隔离的 DS-Bot 验证主机")
        }
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchEnvironment["DSH_BOT_UI_TEST"] = "1"
        app.launchEnvironment["DSH_BOT_UI_TEST_URL"] = "http://127.0.0.1:3098"
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        if ProcessInfo.processInfo.environment["DSH_BOT_LARGE_TEXT"] == "1" {
            app.launchArguments += ["-appearance.fontScale", "1.5", "-appearance.fontFollowsSystem", "NO"]
        }
        app.launch()
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        if springboard.buttons["不允许"].waitForExistence(timeout: 2) { springboard.buttons["不允许"].tap() }
        if app.alerts.firstMatch.exists { app.alerts.firstMatch.buttons.firstMatch.tap() }
        let chief = app.buttons["bot.chief"]
        XCTAssertTrue(chief.waitForExistence(timeout: 15), "真实主机的 Chief 应出现在首页")
        capture(app, "Bot 首页 · 原生列表")

        app.buttons["bot.schedules"].tap()
        XCTAssertTrue(app.staticTexts["定时任务"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.staticTexts["还没有定时任务"].exists)
        capture(app, "定时任务 · 苹果原生面板")
        app.buttons["bot.schedules-close"].tap()
        chief.tap()
        let roleSettings = app.buttons["角色设置"]
        XCTAssertTrue(roleSettings.waitForExistence(timeout: 4))
        capture(app, "Chief 名片")
        roleSettings.tap()
        XCTAssertTrue(app.navigationBars["角色设置"].waitForExistence(timeout: 4))
        capture(app, "Chief 设置")
        app.navigationBars.buttons.firstMatch.tap()
        app.buttons["关闭"].tap()
        app.buttons["bot.start-chat"].tap()
        let composer = app.descendants(matching: .any)["bot.composer"].firstMatch
        XCTAssertTrue(composer.waitForExistence(timeout: 8))
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "本地验证模型")).firstMatch.waitForExistence(timeout: 8))
        capture(app, "Chief 私聊 · 真实历史气泡")
        composer.tap()
        composer.typeText("输入框验证")
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 3))
        capture(app, "Chief 私聊 · 键盘与安全区")
        XCUIDevice.shared.orientation = .landscapeLeft
        capture(app, "Chief 私聊 · 横屏")
        XCUIDevice.shared.orientation = .portrait
    }

    @MainActor
    func testRealHostRoleSettingsDismissesKeyboardOnOutsideTap() throws {
        let design = ProcessInfo.processInfo.environment["DSH_BOT_DESIGN_STATE"] == "role-keyboard"
        guard design || ProcessInfo.processInfo.environment["DSH_BOT_PROBE_URL"] == "http://127.0.0.1:3098" else {
            throw XCTSkip("需要隔离的 DS-Bot 验证主机")
        }
        continueAfterFailure = false
        let app = XCUIApplication()
        if design {
            app.launchEnvironment["DSH_BOT_DESIGN_UI_TEST"] = "1"
            app.launchEnvironment["DSH_BOT_DESIGN_STATE"] = "role-keyboard"
        } else {
            app.launchEnvironment["DSH_BOT_UI_TEST"] = "1"
            app.launchEnvironment["DSH_BOT_UI_TEST_URL"] = "http://127.0.0.1:3098"
        }
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        let isTablet = UIDevice.current.userInterfaceIdiom == .pad
        XCUIDevice.shared.orientation = isTablet ? .landscapeLeft : .portrait
        defer { XCUIDevice.shared.orientation = .portrait }
        app.launch()
        let chief = app.buttons["bot.chief"]
        XCTAssertTrue(chief.waitForExistence(timeout: 15))
        chief.tap()
        let settings = app.buttons["角色设置"]
        XCTAssertTrue(settings.waitForExistence(timeout: 5))
        settings.tap()
        let form = app.descendants(matching: .any)["bot.role-settings-form"].firstMatch
        XCTAssertTrue(form.waitForExistence(timeout: 5))
        let editor = form.textViews["说明"].firstMatch
        for _ in 0..<6 where !editor.isHittable { form.swipeUp(velocity: .slow) }
        XCTAssertTrue(editor.isHittable)

        let keyboard = app.keyboards.firstMatch
        editor.tap()
        XCTAssertTrue(keyboard.waitForExistence(timeout: 5))
        let draft = isTablet ? " " : "Focus draft"
        if isTablet {
            // 横屏 iPad 上，XCTest 的 typeText 会让软键盘消失。
            let space = keyboard.keys.matching(NSPredicate(format: "label IN %@", ["space", "空格键"])).firstMatch
            XCTAssertTrue(space.isHittable)
            space.tap()
        } else {
            app.typeText(draft)
        }
        XCTAssertTrue(keyboard.exists)
        capture(app, "说明字段编辑中")

        let sectionTitle = form.staticTexts["说明"].firstMatch
        XCTAssertTrue(sectionTitle.isHittable)
        sectionTitle.tap()
        XCTAssertTrue(keyboard.waitForNonExistence(timeout: 5), "点击说明框外应收起键盘")
        capture(app, "轻触表单后键盘收起")

        editor.tap()
        XCTAssertTrue(keyboard.waitForExistence(timeout: 5))
        XCTAssertTrue((editor.value as? String)?.hasSuffix(draft) == true)
    }

    @MainActor
    func testRealHostLongMemoryEditor() throws {
        guard ProcessInfo.processInfo.environment["DSH_BOT_PROBE_URL"] == "http://127.0.0.1:3098" else {
            throw XCTSkip("需要隔离的 DS-Bot 验证主机")
        }
        continueAfterFailure = false
        let app = XCUIApplication()
        let isTablet = UIDevice.current.userInterfaceIdiom == .pad
        app.launchEnvironment["DSH_BOT_UI_TEST"] = "1"
        app.launchEnvironment["DSH_BOT_UI_TEST_URL"] = "http://127.0.0.1:3098"
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        if isTablet { app.launchArguments += ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryXXXL"] }
        let tabletPortrait = ProcessInfo.processInfo.environment["DSH_BOT_TABLET_PORTRAIT"] == "1"
        XCUIDevice.shared.orientation = isTablet && !tabletPortrait ? .landscapeLeft : .portrait
        defer { XCUIDevice.shared.orientation = .portrait }
        app.launch()
        let chief = app.buttons["bot.chief"]
        XCTAssertTrue(chief.waitForExistence(timeout: 15))
        chief.tap()
        let memories = app.staticTexts["记忆"].firstMatch
        XCTAssertTrue(memories.waitForExistence(timeout: 8))
        memories.tap()

        let entry = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Long memory preview.")).firstMatch
        XCTAssertTrue(entry.waitForExistence(timeout: 5))
        entry.tap()
        capture(app, "长记忆编辑")
        let editor = app.textViews["bot.memory-editor"]
        XCTAssertTrue(editor.waitForExistence(timeout: 4))
        XCTAssertGreaterThan(editor.frame.height, 160)
        XCTAssertTrue((editor.value as? String)?.hasSuffix("End of memory.") == true)

        editor.tap()
        let keyboard = app.keyboards.firstMatch
        XCTAssertTrue(keyboard.waitForExistence(timeout: 5))
        let editorClearsKeyboard = XCTNSPredicateExpectation(
            predicate: NSPredicate { _, _ in
                let overlap = editor.frame.intersection(keyboard.frame).intersection(app.frame)
                return overlap.isNull || overlap.height < 2
            },
            object: nil
        )
        XCTAssertEqual(XCTWaiter.wait(for: [editorClearsKeyboard], timeout: 5), .completed)
        XCTAssertTrue(app.navigationBars["编辑记忆"].buttons["保存"].isHittable)
        capture(app, "编辑记忆 · 键盘")
        app.navigationBars["编辑记忆"].buttons.firstMatch.tap()

        let add = app.buttons["添加记忆"]
        XCTAssertTrue(add.waitForExistence(timeout: 5))
        add.tap()
        XCTAssertTrue(editor.waitForExistence(timeout: 5))
        let save = app.navigationBars["添加记忆"].buttons["保存"]
        XCTAssertFalse(save.isEnabled)
        if !isTablet {
            let draft = "Memory editor proof " + String(UUID().uuidString.prefix(8))
            editor.tap()
            app.typeText(draft)
            XCTAssertTrue(save.isEnabled)
            save.tap()
            XCTAssertTrue(app.buttons[draft].waitForExistence(timeout: 8))
        }
    }

    @MainActor
    func testRealHostDeleteBot() throws {
        guard ProcessInfo.processInfo.environment["DSH_BOT_PROBE_URL"] == "http://127.0.0.1:3098" else {
            throw XCTSkip("需要隔离的 DS-Bot 验证主机")
        }
        continueAfterFailure = false
        let app = XCUIApplication()
        let tablet = UIDevice.current.userInterfaceIdiom == .pad
        app.launchEnvironment["DSH_BOT_UI_TEST"] = "1"
        app.launchEnvironment["DSH_BOT_UI_TEST_URL"] = "http://127.0.0.1:3098"
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        if tablet { app.launchArguments += ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryXXXL"] }
        let portrait = ProcessInfo.processInfo.environment["DSH_BOT_TABLET_PORTRAIT"] == "1"
        XCUIDevice.shared.orientation = tablet && !portrait ? .landscapeLeft : .portrait
        defer { XCUIDevice.shared.orientation = .portrait }
        app.launch()
        let roster = app.buttons["bot.roster"]
        XCTAssertTrue(roster.waitForExistence(timeout: 15))
        roster.tap()
        let chief = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label BEGINSWITH %@", "bot.roster.", "Chief")).firstMatch
        let copy = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label BEGINSWITH %@", "bot.roster.", "Chief copy")).firstMatch
        XCTAssertTrue(chief.waitForExistence(timeout: 5))
        XCTAssertFalse(copy.exists, "隔离主机应没有上次测试的临时副本")

        func reveal(_ button: XCUIElement) {
            let settings = app.buttons["角色设置"]
            XCTAssertTrue(settings.waitForExistence(timeout: 5))
            settings.tap()
            let form = app.descendants(matching: .any)["bot.role-settings-form"].firstMatch
            XCTAssertTrue(form.waitForExistence(timeout: 6))
            for _ in 0..<8 where !button.isHittable { form.swipeUp(velocity: .fast) }
            XCTAssertTrue(button.isHittable)
        }

        let delete = app.buttons["bot.role-delete"]
        let duplicate = app.buttons["复制一份"]
        chief.tap()
        reveal(duplicate)
        XCTAssertFalse(delete.exists)
        duplicate.tap()
        let duplicateAlert = app.alerts["复制这个角色？"]
        XCTAssertTrue(duplicateAlert.waitForExistence(timeout: 5))
        duplicateAlert.buttons["复制一份"].tap()
        app.navigationBars["角色设置"].buttons.firstMatch.tap()
        app.buttons["关闭"].tap()
        XCTAssertTrue(copy.waitForExistence(timeout: 12))
        copy.tap()
        reveal(delete)
        capture(app, "角色设置 · 删除普通 Bot")

        delete.tap()
        let alert = app.alerts["删除 Bot？"]
        XCTAssertTrue(alert.waitForExistence(timeout: 5))
        XCTAssertTrue(alert.staticTexts["将“Chief copy”移出团队和所在群聊。"].exists)
        capture(app, "删除 Bot · 二次确认")
        alert.buttons["取消"].tap()
        XCTAssertTrue(delete.isHittable)

        delete.tap()
        alert.buttons["删除"].tap()
        XCTAssertTrue(app.buttons["bot.new"].waitForExistence(timeout: 12))
        XCTAssertTrue(copy.waitForNonExistence(timeout: 10))
        XCTAssertTrue(chief.exists)
    }

    @MainActor
    func testRealHostBotCreation() throws {
        guard ProcessInfo.processInfo.environment["DSH_BOT_PROBE_URL"] == "http://127.0.0.1:3098" else {
            throw XCTSkip("需要隔离的 DS-Bot 验证主机")
        }
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchEnvironment["DSH_BOT_UI_TEST"] = "1"
        app.launchEnvironment["DSH_BOT_UI_TEST_URL"] = "http://127.0.0.1:3098"
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        let tablet = UIDevice.current.userInterfaceIdiom == .pad
        XCUIDevice.shared.orientation = tablet ? .landscapeLeft : .portrait
        defer { XCUIDevice.shared.orientation = .portrait }
        if ProcessInfo.processInfo.environment["DSH_BOT_UI_APPEARANCE"] == "dark" {
            app.launchEnvironment["DSH_BOT_UI_APPEARANCE"] = "dark"
        }
        if ProcessInfo.processInfo.environment["DSH_BOT_LARGE_TEXT"] == "1" {
            app.launchArguments += ["-appearance.fontScale", "1.5", "-appearance.fontFollowsSystem", "NO"]
        }
        app.launch()
        if app.alerts.firstMatch.exists { app.alerts.firstMatch.buttons.firstMatch.tap() }
        let roster = app.buttons["bot.roster"]
        XCTAssertTrue(roster.waitForExistence(timeout: 15))
        XCTAssertFalse(app.staticTexts["正在进行"].exists)
        XCTAssertTrue(app.buttons["bot.schedules"].exists)
        capture(app, "Bot 首页")
        roster.tap()
        let add = app.buttons["bot.new"]
        XCTAssertTrue(add.waitForExistence(timeout: 5))
        XCTAssertTrue(add.isEnabled)
        capture(app, "我的 Bot")
        add.tap()
        let appearance = app.buttons["bot.create.appearance"]
        let submit = app.buttons["bot.create.submit"]
        expectation(for: NSPredicate(format: "enabled == true"), evaluatedWith: appearance)
        waitForExpectations(timeout: 12)
        capture(app, "新建 Bot")
        appearance.tap()
        XCTAssertTrue(app.staticTexts["形状"].waitForExistence(timeout: 5))
        app.buttons["头像库"].tap()
        XCTAssertTrue(app.buttons["导入照片"].waitForExistence(timeout: 5))
        let libraryPhoto = app.buttons["avatar-test.png"]
        if libraryPhoto.waitForExistence(timeout: 2) { libraryPhoto.tap() }
        else { app.buttons["取消"].firstMatch.tap() }
        XCTAssertTrue(app.staticTexts["形状"].waitForExistence(timeout: 5))
        let star = app.buttons["star"]
        XCTAssertTrue(star.exists)
        star.tap()
        app.buttons["violet"].tap()
        capture(app, "编辑形象")
        app.buttons["bot.appearance.done"].tap()
        let local = app.buttons["本地 Agent"]
        XCTAssertTrue(local.waitForExistence(timeout: 4))
        local.tap()
        XCTAssertFalse(submit.isEnabled)
        capture(app, "选择本地 Agent")
        let agent = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "bot.create.agent.")).firstMatch
        if agent.waitForExistence(timeout: 8), agent.isEnabled {
            agent.tap()
            XCTAssertTrue(submit.isEnabled)
            app.buttons["bot.create.model"].tap()
            XCTAssertTrue(app.staticTexts["可用模型"].waitForExistence(timeout: 5))
            capture(app, "选择本地 Agent 模型")
            app.buttons["取消"].firstMatch.tap()
        }
        app.buttons["DS Bot 模型"].tap()
        XCTAssertTrue(submit.isEnabled)
        let name = "iOS " + String(UUID().uuidString.prefix(8))
        let field = app.textFields["bot.create.name"]
        field.tap()
        field.typeText(name)
        submit.tap()
        XCTAssertTrue(app.staticTexts[name].waitForExistence(timeout: 20))
        capture(app, "创建成功")
    }

    @MainActor
    private func capture(_ app: XCUIApplication, _ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}

/// 固定业务快照只用于布局验收；生产页面和导航、抽屉、原生控件完全复用。
final class BotDesignUITests: XCTestCase {
    @MainActor
    func testNewConversationMenuAndOrderedGroupSelection() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchEnvironment["DSH_BOT_DESIGN_UI_TEST"] = "1"
        app.launchEnvironment["DSH_BOT_DESIGN_STATE"] = "conversation"
        let large = ProcessInfo.processInfo.environment["DSH_CONVERSATION_LARGE"] == "1"
        app.launchArguments = ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN",
            "-appearance.fontScale", large ? "1.5" : "1.0", "-appearance.fontFollowsSystem", "NO"]
        let pad = UIDevice.current.userInterfaceIdiom == .pad
        XCUIDevice.shared.orientation = .portrait
        defer { XCUIDevice.shared.orientation = .portrait }
        app.launch()
        if app.alerts.firstMatch.waitForExistence(timeout: 1) { app.alerts.firstMatch.buttons.firstMatch.tap() }
        let new = app.buttons["bot.conversation.new"]
        XCTAssertTrue(new.waitForExistence(timeout: 10))
        if !new.isHittable { app.swipeUp() }
        new.tap()
        let group = app.buttons["新建群聊"]
        XCTAssertTrue(group.waitForExistence(timeout: 5))
        capture(app, "新建会话 · 原生可滚动菜单")
        // 手势位于实际 Menu 内；平板不能以整屏中心滑动，否则会关闭菜单并滚动主页。
        let anchorY = new.frame.minY
        let menuRow = group.frame
        let startY = min(menuRow.maxY + 280, app.frame.maxY - 80)
        let endY = menuRow.minY + 40
        let last = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@ AND NOT identifier BEGINSWITH %@",
            "测试成员 24", "bot.chat.")).firstMatch
        for _ in 0..<12 {
            if last.exists && last.isHittable { break }
            let start = app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: menuRow.midX, dy: startY))
            let end = app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: menuRow.midX, dy: endY))
            start.press(forDuration: 0.05, thenDragTo: end)
        }
        XCTAssertTrue(last.exists && last.isHittable)
        capture(app, "新建会话 · 菜单滚动到底")
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.01, dy: 0.8)).tap()
        XCTAssertEqual(new.frame.minY, anchorY, accuracy: 2)
        new.tap()
        group.tap()
        XCTAssertTrue(app.staticTexts["bot.group.admin-note"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["bot.group.create"].isEnabled)
        let chief = app.buttons["bot.conversation.member.chief"]
        let researcher = app.buttons["bot.conversation.member.researcher"]
        chief.tap(); researcher.tap()
        XCTAssertEqual(chief.value as? String, "管理员")
        XCTAssertTrue(app.buttons["bot.group.create"].isEnabled)
        app.buttons["bot.group.remove.chief"].tap()
        XCTAssertEqual(researcher.value as? String, "管理员")
        chief.tap()
        XCTAssertEqual(researcher.value as? String, "管理员")
        capture(app, "新建群聊 · 第一位成员为管理员")
        if pad {
            XCUIDevice.shared.orientation = .landscapeLeft
            capture(app, "新建群聊 · iPad 横屏")
        }
        app.buttons["bot.group.create"].tap()
        XCTAssertTrue(app.buttons["bot.chat-identity"].waitForExistence(timeout: 10))
        capture(app, "新建群聊 · 已进入聊天")
    }

    @MainActor
    func testNewGroupFailurePreservesDraft() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchEnvironment["DSH_BOT_DESIGN_UI_TEST"] = "1"
        app.launchEnvironment["DSH_BOT_DESIGN_STATE"] = "conversation-error"
        app.launchArguments = ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        if app.alerts.firstMatch.waitForExistence(timeout: 1) { app.alerts.firstMatch.buttons.firstMatch.tap() }
        let new = app.buttons["bot.conversation.new"]
        XCTAssertTrue(new.waitForExistence(timeout: 10))
        if !new.isHittable { app.swipeUp() }
        new.tap(); app.buttons["新建群聊"].tap()
        app.buttons["bot.conversation.member.researcher"].tap()
        app.buttons["bot.conversation.member.chief"].tap()
        let message = app.descendants(matching: .any).matching(identifier: "bot.group.message").firstMatch
        for _ in 0..<4 { if message.isHittable { break }; app.swipeUp() }
        message.tap(); message.typeText("保留这条首条消息")
        app.buttons["bot.group.create"].tap()
        let error = app.staticTexts["主机暂时无法创建群聊"]
        for _ in 0..<4 { if error.exists { break }; app.swipeUp() }
        XCTAssertTrue(error.waitForExistence(timeout: 5))
        XCTAssertEqual(message.value as? String, "保留这条首条消息")
        XCTAssertTrue(app.buttons["bot.group.remove.researcher"].exists)
        XCTAssertTrue(app.buttons["bot.group.create"].isEnabled)
        capture(app, "新建群聊 · 失败保留草稿")
    }

    @MainActor
    func testNewConversationSearchRestoresHiddenBot() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchEnvironment["DSH_BOT_DESIGN_UI_TEST"] = "1"
        app.launchEnvironment["DSH_BOT_DESIGN_STATE"] = "conversation"
        app.launchArguments = ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        if app.alerts.firstMatch.waitForExistence(timeout: 1) { app.alerts.firstMatch.buttons.firstMatch.tap() }
        let new = app.buttons["bot.conversation.new"]
        XCTAssertTrue(new.waitForExistence(timeout: 10))
        if !new.isHittable { app.swipeUp() }
        new.tap(); app.buttons["查找 Bot…"].tap()
        let search = app.textFields["bot.conversation.search"]
        XCTAssertTrue(search.waitForExistence(timeout: 5)); search.tap(); search.typeText("研究")
        let researcher = app.buttons["bot.conversation.member.researcher"]
        XCTAssertTrue(researcher.exists)
        capture(app, "新建会话 · 搜索隐藏 Bot")
        researcher.tap()
        XCTAssertTrue(app.buttons["bot.chat-identity"].waitForExistence(timeout: 10))
    }

    @MainActor
    func testHiddenBotRemainsInGroupMembers() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchEnvironment["DSH_BOT_DESIGN_UI_TEST"] = "1"
        app.launchEnvironment["DSH_BOT_DESIGN_STATE"] = "hidden-member"
        app.launchArguments = ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        if app.alerts.firstMatch.waitForExistence(timeout: 1) { app.alerts.firstMatch.buttons.firstMatch.tap() }
        XCTAssertTrue(app.buttons["bot.chief"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["bot.chat.researcher"].exists)
        let room = app.buttons["bot.chat.room"]
        if !room.isHittable { app.swipeUp() }
        XCTAssertTrue(room.exists)
        capture(app, "隐藏研究员后 · 群聊仍在")
        room.tap()
        let identity = app.buttons["bot.chat-identity"]
        XCTAssertTrue(identity.waitForExistence(timeout: 5))
        identity.tap()
        XCTAssertTrue(app.buttons["bot.member.researcher"].waitForExistence(timeout: 5))
        capture(app, "隐藏研究员后 · 保留群成员")
    }

    @MainActor
    func testBotUnreadBadgeClearsAfterOpeningChat() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchEnvironment["DSH_BOT_DESIGN_UI_TEST"] = "1"
        app.launchEnvironment["DSH_BOT_DESIGN_APPEARANCE"] = "dark"
        let unread = ProcessInfo.processInfo.environment["DSH_BOT_UNREAD_OVERFLOW"] == "1" ? 100 : 2
        if unread > 99 { app.launchEnvironment["DSH_BOT_DESIGN_STATE"] = "long" }
        let largeText = ProcessInfo.processInfo.environment["DSH_BOT_LARGE_TEXT"] == "1"
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN",
                                "-appearance.fontScale", largeText ? "1.5" : "1.0", "-appearance.fontFollowsSystem", "NO"]
        let tablet = UIDevice.current.userInterfaceIdiom == .pad
        XCUIDevice.shared.orientation = tablet ? .landscapeLeft : .portrait
        defer { XCUIDevice.shared.orientation = .portrait }
        app.launch()
        let room = app.buttons["bot.chat.room"]
        XCTAssertTrue(room.waitForExistence(timeout: 10))
        if !room.isHittable { app.swipeUp() }
        let badge = app.descendants(matching: .any)["bot.unread.room"].firstMatch
        XCTAssertTrue(badge.exists || room.label.contains("未读消息：\(unread)"))
        capture(app, "Bot · 未读角标")
        room.tap()
        XCTAssertTrue(app.buttons["bot.chat-more"].waitForExistence(timeout: 5))
        app.navigationBars.buttons.firstMatch.tap()
        XCTAssertTrue(room.waitForExistence(timeout: 5))
        XCTAssertFalse(badge.exists)
        XCTAssertFalse(room.label.contains("未读消息"))
        capture(app, "Bot · 读后清零")
    }

    @MainActor
    func testBotSecondaryPages() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchEnvironment["DSH_BOT_DESIGN_UI_TEST"] = "1"
        app.launchEnvironment["DSH_BOT_DESIGN_STATE"] = "page-parity"
        let night = ProcessInfo.processInfo.environment["DSH_BOT_UI_APPEARANCE"] == "dark"
        if night { app.launchEnvironment["DSH_BOT_DESIGN_APPEARANCE"] = "dark" }
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN",
                                "-appearance.fontScale", ProcessInfo.processInfo.environment["DSH_BOT_LARGE_TEXT"] == "1" ? "1.5" : "1.0",
                                "-appearance.fontFollowsSystem", "NO"]
        let tablet = UIDevice.current.userInterfaceIdiom == .pad
        XCUIDevice.shared.orientation = tablet ? .landscapeLeft : .portrait
        defer { XCUIDevice.shared.orientation = .portrait }
        app.launch()

        let chief = app.buttons["bot.chief"]
        XCTAssertTrue(chief.waitForExistence(timeout: 10))
        chief.tap()
        if night && !tablet {
            let settings = app.buttons["角色设置"]
            XCTAssertTrue(settings.waitForExistence(timeout: 5))
            let compactTop = settings.frame.minY
            let origin = app.coordinate(withNormalizedOffset: .zero)
            let handle = origin.withOffset(CGVector(dx: app.frame.midX, dy: compactTop - 12))
            handle.press(forDuration: 0.05,
                         thenDragTo: handle.withOffset(CGVector(dx: 0, dy: -130)),
                         withVelocity: .slow, thenHoldForDuration: 0.1)
            let expandedTop = settings.frame.minY
            XCTAssertLessThan(expandedTop, compactTop - 50)
            capture(app, "Bot · 角色面板展开")
            let expandedHandle = origin.withOffset(CGVector(dx: app.frame.midX, dy: expandedTop - 12))
            expandedHandle.press(forDuration: 0.05,
                                 thenDragTo: expandedHandle.withOffset(CGVector(dx: 0, dy: compactTop - expandedTop)),
                                 withVelocity: .slow, thenHoldForDuration: 0.1)
            XCTAssertEqual(settings.frame.minY, compactTop, accuracy: 3)
            capture(app, "Bot · 角色面板紧凑")
        }
        let memoryLink = app.buttons.containing(NSPredicate(format: "label BEGINSWITH %@", "记忆")).firstMatch
        XCTAssertTrue(memoryLink.waitForExistence(timeout: 5))
        if !memoryLink.isHittable { app.swipeUp() }
        memoryLink.tap()
        let memory = app.buttons["bot.role-memory.1"]
        let add = app.buttons["添加记忆"]
        XCTAssertTrue(memory.waitForExistence(timeout: 5))
        XCTAssertTrue(add.isEnabled)
        capture(app, "Bot · 记忆列表")
        memory.swipeLeft(velocity: .slow)
        let delete = app.buttons["删除"]
        XCTAssertTrue(delete.waitForExistence(timeout: 5))
        capture(app, "Bot · 记忆左滑")
        delete.tap()
        XCTAssertTrue(app.alerts.firstMatch.waitForExistence(timeout: 5))
        app.alerts.firstMatch.buttons["取消"].tap()
        memory.tap()
        XCTAssertTrue(app.textViews["bot.memory-editor"].waitForExistence(timeout: 5))
        capture(app, "Bot · 编辑记忆")
        app.navigationBars["编辑记忆"].buttons.firstMatch.tap()
        add.tap()
        XCTAssertFalse(app.navigationBars["添加记忆"].buttons["保存"].isEnabled)
        capture(app, "Bot · 添加记忆")
        app.navigationBars["添加记忆"].buttons.firstMatch.tap()
        app.navigationBars["记忆"].buttons.firstMatch.tap()
        app.buttons["关闭"].tap()

        let room = app.buttons["bot.chat.room"]
        if !room.isHittable { app.swipeUp() }
        XCTAssertTrue(room.waitForExistence(timeout: 5))
        room.tap()
        let identity = app.buttons["bot.chat-identity"]
        XCTAssertTrue(identity.waitForExistence(timeout: 5))
        identity.tap()
        let member = app.buttons["bot.member.researcher"]
        XCTAssertTrue(member.waitForExistence(timeout: 5))
        capture(app, "Bot · 群成员")
        member.tap()
        XCTAssertTrue(app.buttons["角色设置"].waitForExistence(timeout: 5))
        app.buttons.matching(identifier: "关闭").allElementsBoundByIndex.last?.tap()
        XCTAssertTrue(member.isHittable)
        app.navigationBars["群成员"].buttons["关闭"].tap()
        let more = app.buttons["bot.chat-more"]
        more.tap()
        XCTAssertTrue(app.buttons["重新同步"].waitForExistence(timeout: 5))
        capture(app, "Bot · 会话更多")
        app.buttons["定时任务"].tap()
        app.buttons["bot.schedules-close"].tap()
        app.navigationBars.buttons.element(boundBy: 0).tap()

        let chat = app.buttons["bot.start-chat"]
        XCTAssertTrue(chat.waitForExistence(timeout: 5))
        if !chat.isHittable { app.swipeDown() }
        chat.tap()
        let answer = app.buttons["回答"]
        XCTAssertTrue(answer.waitForExistence(timeout: 5))
        answer.tap()
        let submit = app.navigationBars["回答助理"].buttons["提交"]
        XCTAssertTrue(submit.waitForExistence(timeout: 5))
        XCTAssertFalse(submit.isEnabled)
        capture(app, "Bot · 回答助理")
        app.buttons["交互"].tap()
        XCTAssertTrue(submit.isEnabled)
        capture(app, "Bot · 已选回答")
        app.navigationBars["回答助理"].buttons["取消"].tap()
        XCTAssertTrue(answer.isHittable)
        app.navigationBars.buttons.element(boundBy: 0).tap()

        let roster = app.buttons["bot.roster"]
        if !roster.isHittable { app.swipeUp() }
        roster.tap()
        let newBot = app.buttons["bot.new"]
        XCTAssertTrue(newBot.waitForExistence(timeout: 5))
        XCTAssertTrue(newBot.isEnabled)
        capture(app, "Bot · 我的 Bot")
        newBot.tap()
        let appearance = app.buttons["bot.create.appearance"]
        XCTAssertTrue(appearance.waitForExistence(timeout: 5))
        capture(app, "Bot · 新建 Bot")
        appearance.tap()
        XCTAssertTrue(app.staticTexts["形状"].waitForExistence(timeout: 5))
        capture(app, "Bot · 编辑形象")
        app.buttons["头像库"].tap()
        let photo = app.buttons["parity-avatar.png"]
        XCTAssertTrue(photo.waitForExistence(timeout: 5))
        capture(app, "Bot · 头像库")
        photo.tap()
        app.buttons["bot.appearance.done"].tap()
        app.buttons["bot.create.model"].tap()
        XCTAssertTrue(app.staticTexts["可用模型"].waitForExistence(timeout: 5))
        capture(app, "Bot · 模型选择")
        let model = app.buttons.containing(NSPredicate(format: "label CONTAINS %@", "DeepSeek-V4.1-Flash")).firstMatch
        XCTAssertTrue(model.exists)
        model.tap()
        XCTAssertTrue(app.staticTexts["可用模型"].isHittable)
        app.buttons["取消"].tap()

        let local = app.buttons["本地 Agent"]
        XCTAssertTrue(local.waitForExistence(timeout: 5))
        if !local.isHittable { app.swipeUp() }
        local.tap()
        let create = app.buttons["bot.create.submit"]
        XCTAssertFalse(create.isEnabled)
        let agent = app.buttons["bot.create.agent.local-parity"]
        if !agent.isHittable { app.swipeUp() }
        XCTAssertTrue(agent.isHittable)
        XCTAssertFalse(app.buttons["bot.create.agent.local-offline"].isEnabled)
        capture(app, "Bot · 本地 Agent")
        agent.tap()
        XCTAssertTrue(create.isEnabled)
        let modelPicker = app.buttons["bot.create.model"]
        if !modelPicker.isHittable { app.swipeUp() }
        modelPicker.tap()
        let cliDefault = app.buttons.containing(NSPredicate(format: "label CONTAINS %@", "CLI 默认")).firstMatch
        XCTAssertTrue(cliDefault.waitForExistence(timeout: 5))
        capture(app, "Bot · 本地模型")
        cliDefault.tap()
        XCTAssertTrue(create.isHittable)
    }

    @MainActor
    func testBotRoleScheduleLayout() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchEnvironment["DSH_BOT_DESIGN_UI_TEST"] = "1"
        app.launchEnvironment["DSH_BOT_DESIGN_STATE"] = "role-profile"
        let largeText = ProcessInfo.processInfo.environment["DSH_BOT_LARGE_TEXT"] == "1"
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN",
                                "-appearance.fontScale", largeText ? "1.5" : "1.0", "-appearance.fontFollowsSystem", "NO"]
        let tablet = UIDevice.current.userInterfaceIdiom == .pad
        XCUIDevice.shared.orientation = .portrait
        defer { XCUIDevice.shared.orientation = .portrait }
        app.launch()
        let chief = app.buttons["bot.chief"]
        XCTAssertTrue(chief.waitForExistence(timeout: 10))
        chief.tap()
        let schedules = app.buttons["bot.role-schedules"]
        XCTAssertTrue(schedules.waitForExistence(timeout: 5))
        if !schedules.isHittable { app.swipeUp() }
        schedules.tap()
        let add = app.buttons["新建定时任务"]
        XCTAssertTrue(add.waitForExistence(timeout: 5))
        capture(app, "Bot 定时任务 · 竖屏")
        if tablet {
            XCUIDevice.shared.orientation = .landscapeLeft
            XCTAssertTrue(waitForWindow(app, landscape: true))
            capture(app, "Bot 定时任务 · 横屏")
        }
        let task = app.descendants(matching: .any)["bot.role-schedule.morning"].firstMatch
        XCTAssertTrue(task.isHittable)
        task.swipeLeft(velocity: .slow)
        let delete = app.buttons["删除"]
        XCTAssertTrue(delete.waitForExistence(timeout: 5))
        capture(app, "Bot 定时任务 · 左滑")
        delete.tap()
        XCTAssertTrue(app.alerts.firstMatch.waitForExistence(timeout: 5))
        app.alerts.firstMatch.buttons["取消"].tap()
        add.tap()
        XCTAssertTrue(app.navigationBars["新建定时任务"].waitForExistence(timeout: 5))
        capture(app, "Bot 新建定时任务")
        let time = app.descendants(matching: .any)["bot.role-schedule-time"].firstMatch
        XCTAssertTrue(time.isHittable)
        time.coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: 0.5)).tap()
        capture(app, "Bot 定时任务 · 选择时间")
    }

    @MainActor
    func testBotRoleProfileLayout() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchEnvironment["DSH_BOT_DESIGN_UI_TEST"] = "1"
        app.launchEnvironment["DSH_BOT_DESIGN_STATE"] = "role-profile"
        let largeText = ProcessInfo.processInfo.environment["DSH_BOT_LARGE_TEXT"] == "1"
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN",
                                "-appearance.fontScale", largeText ? "1.5" : "1.0", "-appearance.fontFollowsSystem", "NO"]
        let tablet = UIDevice.current.userInterfaceIdiom == .pad
        XCUIDevice.shared.orientation = .portrait
        defer { XCUIDevice.shared.orientation = .portrait }
        app.launch()
        let chief = app.buttons["bot.chief"]
        XCTAssertTrue(chief.waitForExistence(timeout: 10))
        chief.tap()
        let settings = app.buttons["角色设置"]
        XCTAssertTrue(settings.waitForExistence(timeout: 5))
        capture(app, "角色名片 · 竖屏")
        let model = app.descendants(matching: .any).containing(NSPredicate(
            format: "label CONTAINS %@ OR value CONTAINS %@", "GPT-6.1 Terra", "GPT-6.1 Terra"
        )).firstMatch
        XCTAssertTrue(model.exists)
        if tablet {
            XCUIDevice.shared.orientation = .landscapeLeft
            XCTAssertTrue(waitForWindow(app, landscape: true))
            XCTAssertTrue(settings.isHittable)
            capture(app, "角色名片 · 横屏")
        }
        settings.tap()
        XCTAssertTrue(app.navigationBars["角色设置"].waitForExistence(timeout: 5))
        capture(app, tablet ? "角色设置 · 横屏" : "角色设置 · 竖屏")
        if tablet {
            XCUIDevice.shared.orientation = .portrait
            XCTAssertTrue(waitForWindow(app, landscape: false))
            capture(app, "角色设置 · 竖屏")
        }
    }

    @MainActor
    func testBotRosterLayout() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchEnvironment["DSH_BOT_DESIGN_UI_TEST"] = "1"
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN",
                                "-appearance.fontScale", ProcessInfo.processInfo.environment["DSH_BOT_LARGE_TEXT"] == "1" ? "1.5" : "1.0",
                                "-appearance.fontFollowsSystem", "NO"]
        let tablet = UIDevice.current.userInterfaceIdiom == .pad
        XCUIDevice.shared.orientation = .portrait
        defer { XCUIDevice.shared.orientation = .portrait }
        app.launch()
        let rosterCard = app.buttons["bot.roster"]
        XCTAssertTrue(rosterCard.waitForExistence(timeout: 10))
        if !rosterCard.isHittable { app.swipeUp() }
        rosterCard.tap()
        let chief = app.buttons["bot.roster.chief"]
        XCTAssertTrue(chief.waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["bot.roster.researcher"].exists)
        capture(app, "我的 Bot · 竖屏")
        if tablet {
            XCUIDevice.shared.orientation = .landscapeLeft
            XCTAssertTrue(waitForWindow(app, landscape: true))
            XCTAssertTrue(chief.isHittable)
            capture(app, "我的 Bot · 横屏")
        }
        chief.tap()
        XCTAssertTrue(app.buttons["角色设置"].waitForExistence(timeout: 5))
    }

    @MainActor
    func testWorkspaceModeSwitchesBothWays() {
        verifyWorkspaceModeSwitchesBothWays(dark: false)
    }

    @MainActor
    func testNightWorkspaceModeSwitchesBothWays() {
        verifyWorkspaceModeSwitchesBothWays(dark: true)
    }

    @MainActor
    private func verifyWorkspaceModeSwitchesBothWays(dark: Bool) {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchEnvironment["DSH_BOT_DESIGN_UI_TEST"] = "1"
        if dark { app.launchEnvironment["DSH_BOT_DESIGN_APPEARANCE"] = "dark" }
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        let tablet = UIDevice.current.userInterfaceIdiom == .pad
        XCUIDevice.shared.orientation = tablet ? .landscapeLeft : .portrait
        defer { XCUIDevice.shared.orientation = .portrait }
        app.launch()
        let chief = app.buttons["bot.chief"]
        XCTAssertTrue(chief.waitForExistence(timeout: 10))

        let drawer = app.buttons["打开侧边栏"].firstMatch
        let modeItem = app.buttons["workspace-mode-switch"]
        drawer.tap()
        XCTAssertTrue(modeItem.waitForExistence(timeout: 4))
        XCTAssertEqual(modeItem.label, "切换到工作模式")
        capture(app, dark ? "深色抽屉 · 工作图标" : "浅色抽屉 · 工作图标")
        modeItem.coordinate(withNormalizedOffset: CGVector(dx: 0.15, dy: 0.5)).tap()
        XCTAssertTrue(app.staticTexts["探索未至之境"].waitForExistence(timeout: 5))
        capture(app, "普通模式首页")

        drawer.tap()
        XCTAssertTrue(modeItem.waitForExistence(timeout: 4))
        XCTAssertEqual(modeItem.label, "切换到 Bot 模式")
        capture(app, dark ? "深色抽屉 · Bot 图标" : "浅色抽屉 · Bot 图标")
        modeItem.coordinate(withNormalizedOffset: CGVector(dx: 0.75, dy: 0.5)).tap()
        XCTAssertTrue(chief.waitForExistence(timeout: 5))
        capture(app, "Bot 模式首页")
    }

    @MainActor
    func testBotLiveWorkAtConversationTail() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchEnvironment["DSH_BOT_DESIGN_UI_TEST"] = "1"
        app.launchEnvironment["DSH_BOT_DESIGN_STATE"] = "live-work"
        if ProcessInfo.processInfo.environment["DSH_BOT_DARK_TEST"] == "1" {
            app.launchEnvironment["DSH_BOT_DESIGN_APPEARANCE"] = "dark"
        }
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        XCUIDevice.shared.orientation = UIDevice.current.userInterfaceIdiom == .pad ? .landscapeLeft : .portrait
        defer { XCUIDevice.shared.orientation = .portrait }
        app.launch()
        let chat = app.buttons["bot.start-chat"]
        XCTAssertTrue(chat.waitForExistence(timeout: 10))
        XCTAssertTrue(app.descendants(matching: .any)["bot.running.chief"].firstMatch.exists || chat.label.contains("运行中"))
        let room = app.buttons["bot.chat.room"]
        XCTAssertTrue(room.waitForExistence(timeout: 5))
        if !room.isHittable { app.swipeUp() }
        XCTAssertTrue(app.descendants(matching: .any)["bot.running.room"].firstMatch.exists || room.label.contains("运行中"))
        XCTAssertFalse(app.descendants(matching: .any)["bot.unread.room"].firstMatch.exists)
        XCTAssertFalse(room.label.contains("未读消息"))
        capture(app, "Bot 会话列表 · 运行中")
        if !chat.isHittable { app.swipeDown() }
        chat.tap()
        XCTAssertTrue(app.staticTexts["上网搜索…"].waitForExistence(timeout: 8))
        let elapsed = app.staticTexts["bot.live-work-elapsed"]
        XCTAssertTrue(elapsed.waitForExistence(timeout: 5))
        let initial = elapsed.label
        let advances = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label != %@", initial), object: elapsed)
        XCTAssertEqual(XCTWaiter.wait(for: [advances], timeout: 4), .completed)
        capture(app, "Bot 实时工作状态")
    }

    @MainActor
    func testNightModeSurfaces() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchEnvironment["DSH_BOT_DESIGN_UI_TEST"] = "1"
        app.launchEnvironment["DSH_BOT_DESIGN_APPEARANCE"] = "dark"
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        let tablet = UIDevice.current.userInterfaceIdiom == .pad
        XCUIDevice.shared.orientation = tablet ? .landscapeLeft : .portrait
        defer { XCUIDevice.shared.orientation = .portrait }
        app.launch()
        let chief = app.buttons["bot.chief"]
        XCTAssertTrue(chief.waitForExistence(timeout: 10))
        if tablet {
            XCTAssertTrue(app.textFields["bot.chat-search"].exists || app.searchFields["bot.chat-search"].exists)
        }
        capture(app, "01 夜间首页")
        let schedules = app.buttons["bot.schedules"]
        if !schedules.isHittable { app.swipeUp() }
        schedules.tap()
        XCTAssertTrue(app.staticTexts["每日简报"].waitForExistence(timeout: 5))
        capture(app, "02 夜间定时任务")
        app.buttons["bot.schedules-close"].tap()
        if !chief.isHittable { app.swipeDown() }
        chief.tap()
        let settings = app.buttons["角色设置"]
        XCTAssertTrue(settings.waitForExistence(timeout: 5))
        capture(app, "03 夜间角色名片")
        settings.tap()
        XCTAssertTrue(app.navigationBars["角色设置"].waitForExistence(timeout: 5))
        capture(app, "04 夜间角色设置")
        app.buttons["关闭"].firstMatch.tap()
        let start = app.buttons["bot.start-chat"]
        XCTAssertTrue(start.waitForExistence(timeout: 5))
        start.tap()
        XCTAssertTrue(app.textViews["bot.composer"].firstMatch.waitForExistence(timeout: 5))
        capture(app, "05 夜间私聊")
        app.navigationBars.buttons.firstMatch.tap()
        let room = app.buttons["bot.chat.room"]
        if !room.isHittable { app.swipeUp() }
        room.tap()
        XCTAssertTrue(app.staticTexts["研究员"].firstMatch.waitForExistence(timeout: 5))
        capture(app, "06 夜间群聊")
        if tablet {
            XCUIDevice.shared.orientation = .portrait
            XCTAssertTrue(waitForWindow(app, landscape: false))
            capture(app, "07 夜间群聊竖屏")
        }
    }

    @MainActor
    func testQuoteBotRepliesInPrivateAndGroupChats() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchEnvironment["DSH_BOT_DESIGN_UI_TEST"] = "1"
        let largeText = ProcessInfo.processInfo.environment["DSH_BOT_LARGE_TEXT"] == "1"
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN",
                                "-appearance.fontScale", largeText ? "1.5" : "1.0",
                                "-appearance.fontFollowsSystem", "NO"]
        let tablet = UIDevice.current.userInterfaceIdiom == .pad
        XCUIDevice.shared.orientation = tablet ? .landscapeLeft : .portrait
        defer { XCUIDevice.shared.orientation = .portrait }
        app.launch()

        for target in ["bot.start-chat", "bot.chat.room"] {
            let open = app.buttons[target].firstMatch
            XCTAssertTrue(open.waitForExistence(timeout: 8))
            if !open.isHittable { app.swipeUp() }
            open.tap()
            let timeline = app.collectionViews["conversation-history-viewport"]
            XCTAssertTrue(timeline.waitForExistence(timeout: 5))
            func visibleReply() -> XCUIElement? {
                app.buttons.matching(NSPredicate(format: "label == %@", "回复")).allElementsBoundByIndex.last(where: \.isHittable)
            }
            if visibleReply() == nil { timeline.swipeDown(velocity: .slow) }
            guard let reply = visibleReply() else { XCTFail("找不到 Bot 回复按钮"); return }
            reply.tap()
            let remove = app.buttons["移除引用"].firstMatch
            XCTAssertTrue(remove.waitForExistence(timeout: 5))
            let input = app.textViews["bot.composer"].firstMatch
            input.typeText("Please continue.")
            capture(app, "Bot 引用草稿 · " + target)
            if target == "bot.start-chat" {
                remove.tap()
                XCTAssertTrue(remove.waitForNonExistence(timeout: 4))
                XCTAssertEqual(input.value as? String, "Please continue.")
            } else {
                if tablet {
                    XCUIDevice.shared.orientation = .portrait
                    XCTAssertTrue(waitForWindow(app, landscape: false))
                    XCTAssertTrue(remove.isHittable)
                    XCTAssertEqual(input.value as? String, "Please continue.")
                    capture(app, "Bot 群聊 · 平板竖屏引用")
                }
                let send = app.buttons["发送消息"].firstMatch
                XCTAssertTrue(send.isEnabled)
                send.tap()
                XCTAssertTrue(remove.waitForNonExistence(timeout: 4))
                let sent = app.descendants(matching: .any).matching(identifier: "sent-reply-bubble").firstMatch
                XCTAssertTrue(sent.waitForExistence(timeout: 5))
                capture(app, "Bot 群聊 · 发送引用")
            }
            if target == "bot.start-chat" {
                app.navigationBars.buttons.firstMatch.tap()
            }
        }
    }

    @MainActor
    func testBotTextBubbleWidths() {
        checkBotTextBubbleWidths(scale: "1.0")
    }

    @MainActor
    func testBotTextBubbleWidthsAtLargeFontSize() {
        checkBotTextBubbleWidths(scale: "1.5")
    }

    @MainActor
    private func checkBotTextBubbleWidths(scale: String) {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchEnvironment["DSH_BOT_DESIGN_UI_TEST"] = "1"
        app.launchEnvironment["DSH_BOT_DESIGN_STATE"] = "bubble-widths"
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN",
                                "-appearance.fontScale", scale, "-appearance.fontFollowsSystem", "NO"]
        XCUIDevice.shared.orientation = UIDevice.current.userInterfaceIdiom == .pad ? .landscapeLeft : .portrait
        app.launch()
        let start = app.buttons["bot.start-chat"]
        XCTAssertTrue(start.waitForExistence(timeout: 8))
        start.tap()
        let short = app.staticTexts["好"].firstMatch
        let shortReply = app.staticTexts["收到。"].firstMatch
        let long = app.staticTexts["这条消息用来检查长文字能否按屏幕可用宽度自然换行。"].firstMatch
        XCTAssertTrue(short.waitForExistence(timeout: 5))
        XCTAssertTrue(shortReply.exists)
        XCTAssertTrue(long.exists)
        XCTAssertLessThan(short.frame.width, 80, "短消息应按内容收缩")
        if UIDevice.current.userInterfaceIdiom == .pad {
            for landscape in [true, false] {
                XCUIDevice.shared.orientation = landscape ? .landscapeLeft : .portrait
                XCTAssertTrue(waitForWindow(app, landscape: landscape))
                XCTAssertGreaterThan(long.frame.width, 400, "平板长消息不能继续受手机 292 点上限限制")
                XCTAssertLessThanOrEqual(long.frame.width, 720)
                XCTAssertGreaterThanOrEqual(long.frame.minX, app.windows.firstMatch.frame.minX)
                XCTAssertLessThanOrEqual(long.frame.maxX, app.windows.firstMatch.frame.maxX)
                capture(app, "Bot 气泡宽度 · \(landscape ? "横屏" : "竖屏") · 字号\(scale)")
            }
        } else {
            XCTAssertGreaterThan(long.frame.height, short.frame.height)
            XCTAssertLessThanOrEqual(long.frame.width, 292)
            capture(app, "Bot 手机气泡 · 字号\(scale)")
        }
        XCUIDevice.shared.orientation = .portrait
    }

    @MainActor
    func testBotFileReplyOpensStandardFilePreview() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchEnvironment["DSH_BOT_DESIGN_UI_TEST"] = "1"
        app.launchEnvironment["DSH_BOT_DESIGN_STATE"] = "files"
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN",
                                "-appearance.fontScale", "1.5", "-appearance.fontFollowsSystem", "NO"]
        XCUIDevice.shared.orientation = .portrait
        app.launch()
        let start = app.buttons["bot.start-chat"]
        XCTAssertTrue(start.waitForExistence(timeout: 8))
        start.tap()
        let card = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "ds-bot-research.md")).firstMatch
        XCTAssertTrue(card.waitForExistence(timeout: 5))
        XCTAssertTrue(card.label.contains("调研报告"))
        let lastReply = app.links["ds-bot-research.md"].firstMatch
        let nextReply = app.links["跨工作区报告"].firstMatch
        XCTAssertTrue(lastReply.exists && nextReply.exists)
        XCTAssertEqual(card.frame.minX, lastReply.frame.minX - 14, accuracy: 3)
        XCTAssertGreaterThanOrEqual(card.frame.minY, lastReply.frame.maxY)
        XCTAssertLessThan(card.frame.maxY, nextReply.frame.minY)
        capture(app, "Bot 回复 · 普通模式文件卡片")
        card.tap()
        let title = app.staticTexts["workspace-preview-title"]
        XCTAssertTrue(title.waitForExistence(timeout: 5))
        XCTAssertEqual(title.label, "ds-bot-research.md")
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label == %@", "重试")).firstMatch.exists)
        capture(app, "Bot 回复 · 普通模式文件预览")
        if UIDevice.current.userInterfaceIdiom == .pad {
            XCUIDevice.shared.orientation = .landscapeLeft
            XCTAssertTrue(waitForWindow(app, landscape: true))
            XCTAssertTrue(title.isHittable)
            capture(app, "Bot 回复 · 横屏文件预览")
        }
        app.buttons["workspace-preview-close"].tap()
        XCTAssertTrue(card.waitForExistence(timeout: 5))
        if UIDevice.current.userInterfaceIdiom == .pad {
            XCTAssertEqual(card.frame.minX, lastReply.frame.minX - 14, accuracy: 3)
            capture(app, "Bot 回复 · 横屏文件卡片")
        }
        for (label, filename) in [("reports/ds-bot-research.md", "ds-bot-research.md"),
                                  ("ds-bot-research.md", "ds-bot-research.md"),
                                  ("跨工作区报告", "cross-workspace.md")] {
            let link = app.links[label].firstMatch
            XCTAssertTrue(link.waitForExistence(timeout: 5), label)
            link.coordinate(withNormalizedOffset: CGVector(dx: 0.2, dy: 0.25)).tap()
            XCTAssertTrue(title.waitForExistence(timeout: 5), label)
            XCTAssertEqual(title.label, filename)
            app.buttons["workspace-preview-close"].tap()
        }
        XCUIDevice.shared.orientation = .portrait
    }

    @MainActor
    func testBotHistoryShowsCollaborationAnswersAndMessageTime() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchEnvironment["DSH_BOT_DESIGN_UI_TEST"] = "1"
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN",
                                "-appearance.fontScale", "1.5", "-appearance.fontFollowsSystem", "NO"]
        XCUIDevice.shared.orientation = .portrait
        app.launch()
        let start = app.buttons["bot.start-chat"]
        XCTAssertTrue(start.waitForExistence(timeout: 8))
        start.tap()
        let timeline = app.collectionViews["conversation-history-viewport"]
        let timestamp = app.staticTexts["bot.message-time.4"]
        let sent = app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@ AND label CONTAINS %@", "已发消息给", "研究员")).firstMatch
        XCTAssertTrue(timestamp.waitForExistence(timeout: 5))
        XCTAssertTrue(sent.exists)
        let question = app.staticTexts["你最关心这个项目的哪一面？"]
        if !question.isHittable { timeline.swipeDown(velocity: .slow) }
        XCTAssertTrue(question.isHittable)
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label BEGINSWITH %@ AND label CONTAINS %@", "B", "架构和技术实现")).firstMatch.exists)
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label BEGINSWITH %@ AND label CONTAINS %@", "D", "跟别的方案比")).firstMatch.exists)
        let created = app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@ AND label CONTAINS %@", "已创建", "研究员")).firstMatch
        if !created.isHittable { timeline.swipeDown(velocity: .slow) }
        XCTAssertTrue(created.isHittable)
        capture(app, "Bot 聊天历史 · 成员动态与已回答问答")
        XCUIDevice.shared.orientation = .landscapeLeft
        XCTAssertTrue(waitForWindow(app, landscape: UIDevice.current.userInterfaceIdiom == .pad))
        XCTAssertTrue(timeline.exists)
        capture(app, "Bot 聊天历史 · 旋转验证")
        XCUIDevice.shared.orientation = .portrait
    }

    @MainActor
    func testBotComposerJumpsBackToLatest() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchEnvironment["DSH_BOT_DESIGN_UI_TEST"] = "1"
        let profile = ProcessInfo.processInfo.environment["DSH_BOT_SCROLL_PROFILE"] == "1"
        app.launchEnvironment["DSH_BOT_DESIGN_STATE"] = profile ? "scroll-profile" : "scroll"
        if profile { app.launchEnvironment["DSH_HISTORY_SCROLL_METRICS"] = "1" }
        let scale = ProcessInfo.processInfo.environment["DSH_BOT_LARGE_TEXT"] == "1" ? "1.5" : "1.0"
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN",
                                "-appearance.fontScale", scale, "-appearance.fontFollowsSystem", "NO"]
        XCUIDevice.shared.orientation = UIDevice.current.userInterfaceIdiom == .pad ? .landscapeLeft : .portrait
        app.launch()
        let start = app.buttons["bot.start-chat"]
        XCTAssertTrue(start.waitForExistence(timeout: 8))
        start.tap()
        let timeline = app.collectionViews["conversation-history-viewport"]
        let latest = app.staticTexts[profile ? "历史消息 96" : "历史消息 24"].firstMatch
        XCTAssertTrue(timeline.waitForExistence(timeout: 5))
        let more = app.buttons["bot.chat-more"]
        XCTAssertTrue(more.exists)
        XCTAssertLessThan(timeline.frame.minY, more.frame.minY, "消息视口应延伸到导航栏后方")
        XCTAssertTrue(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in latest.isHittable }, object: nil)], timeout: 5) == .completed)
        let jump = app.buttons["scroll-to-bottom"]
        XCTAssertTrue(jump.waitForNonExistence(timeout: 3))
        if profile {
            for _ in 0..<5 { timeline.swipeDown(velocity: .fast) }
            XCTAssertFalse(latest.isHittable)
        }
        timeline.swipeDown(velocity: .slow)
        XCTAssertTrue(jump.waitForExistence(timeout: 4))
        let composer = app.textViews["bot.composer"].firstMatch
        XCTAssertLessThan(jump.frame.maxY, composer.frame.minY)
        capture(app, "Bot 历史消息 · 底部遮罩与一键到底")
        XCTAssertTrue(jump.isHittable)
        let point = jump.frame
        let origin = app.coordinate(withNormalizedOffset: .zero)
        origin.withOffset(CGVector(dx: point.midX - app.frame.minX, dy: point.midY - app.frame.minY)).tap()
        XCTAssertTrue(jump.waitForNonExistence(timeout: 4))
        XCTAssertTrue(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in latest.isHittable }, object: nil)], timeout: 5) == .completed)
        capture(app, "Bot 最新消息 · 贴底")
        if profile {
            app.navigationBars.buttons.element(boundBy: 0).tap()
            XCTAssertTrue(timeline.waitForNonExistence(timeout: 5))
        }
    }

    @MainActor
    func testHomeScrollAcrossOrientationsAndFontSizes() {
        continueAfterFailure = false
        defer { XCUIDevice.shared.orientation = .portrait }
        let app = XCUIApplication()
        app.launchEnvironment["DSH_BOT_DESIGN_UI_TEST"] = "1"
        for scale in ["1.0", "1.5"] {
            app.launchArguments = ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN",
                "-appearance.fontScale", scale, "-appearance.fontFollowsSystem", "NO"]
            XCUIDevice.shared.orientation = .portrait
            app.launch()
            if app.alerts.firstMatch.waitForExistence(timeout: 1) { app.alerts.firstMatch.buttons.firstMatch.tap() }
            XCTAssertTrue(app.buttons["bot.chief"].waitForExistence(timeout: 10))
            for landscape in [false, true] {
                XCUIDevice.shared.orientation = landscape ? .landscapeLeft : .portrait
                XCTAssertTrue(waitForWindow(app, landscape: landscape))
                app.swipeUp()
                capture(app, "Bot-首页-滚动后-\(landscape ? "横屏" : "竖屏")-字号\(scale)")
                app.swipeDown()
                XCTAssertTrue(app.buttons["bot.chief"].exists)
            }
            app.terminate()
        }
    }

    @MainActor
    func testDesignHomeDrawerChatsAndSchedules() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchEnvironment["DSH_BOT_DESIGN_UI_TEST"] = "1"
        app.launchEnvironment["DSH_BOT_DESIGN_STATE"] = ProcessInfo.processInfo.environment["DSH_BOT_DESIGN_STATE"] ?? "normal"
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN", "-appearance.fontScale", "1.0"]
        if ProcessInfo.processInfo.environment["DSH_BOT_LARGE_TEXT"] == "1" {
            app.launchArguments += ["-appearance.fontScale", "1.5", "-appearance.fontFollowsSystem", "NO"]
        }
        XCUIDevice.shared.orientation = .portrait
        app.launch()
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        if springboard.buttons["不允许"].waitForExistence(timeout: 1) { springboard.buttons["不允许"].tap() }
        let chief = app.buttons["bot.chief"]
        XCTAssertTrue(chief.waitForExistence(timeout: 15))
        XCTAssertTrue(app.buttons["设置"].exists)
        capture(app, "01-Bot-首页-竖屏")
        app.buttons["打开侧边栏"].tap()
        XCTAssertTrue(app.buttons["切换到工作模式"].waitForExistence(timeout: 3))
        capture(app, "02-Bot-抽屉-黑色矢量")
        app.buttons["切换到工作模式"].tap()
        XCTAssertTrue(app.staticTexts["探索未至之境"].waitForExistence(timeout: 5))
        app.buttons["打开侧边栏"].tap()
        app.buttons["切换到 Bot 模式"].tap()
        XCTAssertTrue(chief.waitForExistence(timeout: 5))
        let schedules = app.buttons["bot.schedules"]
        if !schedules.isHittable { app.swipeUp() }
        schedules.tap()
        XCTAssertTrue(app.staticTexts["每日简报"].waitForExistence(timeout: 3))
        capture(app, "03-Bot-原生定时任务面板")
        app.buttons["bot.schedules-close"].tap()
        if !chief.isHittable { app.swipeDown() }
        chief.tap()
        let roleSettings = app.buttons["角色设置"]
        XCTAssertTrue(roleSettings.waitForExistence(timeout: 4))
        capture(app, "Chief 名片")
        roleSettings.tap()
        XCTAssertTrue(app.navigationBars["角色设置"].waitForExistence(timeout: 4))
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@",
            "标签为选填，会显示在 Bot 名字下方。")).firstMatch.waitForExistence(timeout: 4))
        capture(app, "Chief 设置")
        let settingsForm = app.descendants(matching: .any)["bot.role-settings-form"].firstMatch
        let copyHelp = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@",
            "复制分工和模型，新 Bot 从空白记忆开始。")).firstMatch
        for _ in 0..<4 { settingsForm.swipeUp(velocity: .fast) }
        XCTAssertTrue(copyHelp.isHittable)
        let copyAction = settingsForm.buttons["复制一份"].firstMatch
        XCTAssertTrue(copyAction.exists)
        XCTAssertGreaterThanOrEqual(copyHelp.frame.minY, copyAction.frame.maxY)
        XCTAssertLessThanOrEqual(copyHelp.frame.maxY, settingsForm.frame.maxY + 1)
        capture(app, "Chief 设置 · 管理说明")
        if UIDevice.current.userInterfaceIdiom == .pad {
            XCUIDevice.shared.orientation = .landscapeLeft
            XCTAssertTrue(waitForWindow(app, landscape: true))
            XCTAssertTrue(settingsForm.exists)
            settingsForm.swipeUp(velocity: .fast)
            XCTAssertTrue(copyHelp.isHittable)
            XCTAssertLessThanOrEqual(copyHelp.frame.maxY, settingsForm.frame.maxY + 1)
            capture(app, "Chief 设置 · 横屏说明")
            XCUIDevice.shared.orientation = .portrait
            XCTAssertTrue(waitForWindow(app, landscape: false))
        }
        app.navigationBars.buttons.firstMatch.tap()
        app.buttons["关闭"].tap()
        app.buttons["bot.start-chat"].tap()
        let composer = app.textFields["bot.composer"].firstMatch
        let multiline = app.textViews["bot.composer"].firstMatch
        XCTAssertTrue(composer.waitForExistence(timeout: 4) || multiline.exists)
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "完成后在这里告诉你")).firstMatch.waitForExistence(timeout: 3))
        let more = app.buttons["bot.chat-more"]
        XCTAssertTrue(more.exists)
        XCTAssertEqual(more.frame.width, more.frame.height, accuracy: 2, "更多按钮应为正圆")
        capture(app, "04-Chief-私聊气泡")
        let input = composer.exists ? composer : multiline
        input.tap()
        input.typeText("@")
        let member = app.buttons["bot.mention.member.researcher"]
        XCTAssertTrue(member.waitForExistence(timeout: 4))
        capture(app, "04-Chief-引用候选")
        member.tap()
        let keyboard = app.keyboards.firstMatch
        XCTAssertTrue(keyboard.waitForExistence(timeout: 2))
        input.typeText("检查输入框")
        capture(app, "05-Chief-键盘安全区")
        XCTAssertEqual(input.value as? String, "@研究员 检查输入框")
        // iPad 接入硬件键盘时，辅助输入栏的可访问范围可能覆盖空白区域。
        let keyboardTop = keyboard.keys.allElementsBoundByIndex.filter(\.isHittable).map(\.frame.minY).min()
        if let keyboardTop {
            XCTAssertLessThanOrEqual(input.frame.maxY, keyboardTop + 2, "输入框必须在软件键盘上方")
        } else {
            XCTAssertLessThanOrEqual(input.frame.maxY, app.frame.maxY + 1)
        }
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.84, dy: 0.36)).tap()
        XCTAssertTrue(keyboard.waitForNonExistence(timeout: 4), "轻触聊天区应收起键盘")
        XCTAssertEqual(input.value as? String, "@研究员 检查输入框")
        XCTAssertTrue(app.buttons["发送消息"].exists, "收起键盘后仍可发送草稿")
        XCUIDevice.shared.orientation = .landscapeLeft
        XCTAssertTrue(waitForWindow(app, landscape: UIDevice.current.userInterfaceIdiom == .pad))
        XCTAssertTrue(input.waitForExistence(timeout: 3))
        XCTAssertLessThanOrEqual(input.frame.maxX, app.frame.maxX + 1)
        XCTAssertLessThanOrEqual(input.frame.maxY, app.frame.maxY + 1)
        capture(app, "06-Chief-横屏")
        XCUIDevice.shared.orientation = .portrait
        XCTAssertTrue(waitForWindow(app, landscape: false))
        let room = app.buttons["bot.chat.room"]
        if !room.exists {
            app.navigationBars.buttons.element(boundBy: 0).tap()
        }
        if !room.isHittable { app.swipeUp() }
        room.tap()
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "Bot 接入讨论")).firstMatch.waitForExistence(timeout: 3))
        capture(app, "07-Bot-群聊")
    }

    @MainActor
    func testAgentImagePreviewInPrivateAndGroupChats() throws {
        try verifyImageScenario("images", identifier: "bot.image.fixture-plot")
    }

    @MainActor
    func testAgentMarkdownImagesUseExistingPreview() throws {
        try verifyImageScenario("markdown-images", identifier: "markdown-image-preview-button")
    }

    @MainActor
    func testAgentImageOriginalCanRetry() throws {
        try verifyImageScenario("image-error", identifier: "bot.image.fixture-plot")
    }

    @MainActor
    func testAgentImagesWithLargeText() throws {
        try verifyImageScenario("images", identifier: "bot.image.fixture-plot", fontScale: "1.5")
    }

    @MainActor
    private func verifyImageScenario(_ scenario: String, identifier: String, fontScale: String = "1.0") throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchEnvironment["DSH_BOT_DESIGN_UI_TEST"] = "1"
        app.launchEnvironment["DSH_BOT_DESIGN_STATE"] = scenario == "image-error" ? "images" : scenario
        if scenario == "image-error" { app.launchEnvironment["DSH_BOT_IMAGE_FAIL_ONCE"] = "1" }
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN", "-appearance.fontScale", fontScale, "-appearance.fontFollowsSystem", "NO"]
        XCUIDevice.shared.orientation = .portrait
        app.launch()
        XCTAssertTrue(app.buttons["bot.chief"].waitForExistence(timeout: 10))
        app.buttons["bot.start-chat"].tap()
        let image = app.buttons[identifier].firstMatch
        XCTAssertTrue(image.waitForExistence(timeout: 10))
        capture(app, "18-Agent-图片气泡")
        image.tap()
        if scenario == "image-error" {
            XCTAssertTrue(app.alerts["图片加载失败"].waitForExistence(timeout: 4))
            capture(app, "19-Agent-原图重试提示")
            app.alerts.buttons["重试"].tap()
        }
        XCTAssertTrue(app.descendants(matching: .any)["image-gallery"].firstMatch.waitForExistence(timeout: 5))
        capture(app, "19-Agent-原生大图预览")
        XCUIDevice.shared.orientation = .landscapeLeft
        XCTAssertTrue(waitForWindow(app, landscape: UIDevice.current.userInterfaceIdiom == .pad))
        capture(app, UIDevice.current.userInterfaceIdiom == .pad ? "20-Agent-横屏大图预览" : "20-Agent-小屏保持竖屏")
        app.buttons["关闭"].firstMatch.tap()
        app.navigationBars.buttons.element(boundBy: 0).tap()
        let room = app.buttons["bot.chat.room"]
        if !room.isHittable { app.swipeUp() }
        room.tap()
        XCTAssertTrue(image.waitForExistence(timeout: 10))
        image.tap()
        XCTAssertTrue(app.descendants(matching: .any)["image-gallery"].firstMatch.waitForExistence(timeout: 5))
        capture(app, "21-Agent-群聊图片预览")
        app.buttons["关闭"].firstMatch.tap()
        XCUIDevice.shared.orientation = .portrait
    }

    @MainActor
    func testCompactHomeSearchFindsCachedMessagesAndSurvivesChatNavigation() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchEnvironment["DSH_BOT_DESIGN_UI_TEST"] = "1"
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN",
                                "-appearance.fontScale", UIDevice.current.userInterfaceIdiom == .pad ? "1.5" : "1.0",
                                "-appearance.fontFollowsSystem", "NO"]
        if ProcessInfo.processInfo.environment["DSH_BOT_DARK_TEST"] == "1" {
            app.launchEnvironment["DSH_BOT_DESIGN_APPEARANCE"] = "dark"
        }
        XCUIDevice.shared.orientation = .portrait
        app.launch()

        let search = app.textFields["bot.chat-search"]
        XCTAssertTrue(search.waitForExistence(timeout: 10))
        for _ in 0..<3 where !search.isHittable { app.swipeUp() }
        XCTAssertTrue(search.isHittable)
        search.tap()
        search.typeText("沿用现有通知方式\n")

        let result = app.buttons["bot.chat.chief"]
        XCTAssertTrue(result.waitForExistence(timeout: 3))
        XCTAssertTrue(app.staticTexts["1 个结果"].exists)
        XCTAssertFalse(app.buttons["bot.chat.room"].exists)
        capture(app, "Bot 紧凑布局 · 聊天内容搜索")
        if !result.isHittable { app.swipeUp() }
        result.tap()
        XCTAssertTrue(app.buttons["bot.chat-more"].waitForExistence(timeout: 4))
        app.navigationBars.buttons.firstMatch.tap()
        XCTAssertTrue(search.waitForExistence(timeout: 4))
        XCTAssertEqual(search.value as? String, "沿用现有通知方式")
    }

    @MainActor
    func testWideHomeSearchAndIndependentChat() throws {
        guard UIDevice.current.userInterfaceIdiom == .pad else { throw XCTSkip("大屏主页在 iPad 验证") }
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchEnvironment["DSH_BOT_DESIGN_UI_TEST"] = "1"
        app.launchEnvironment["DSH_BOT_DESIGN_STATE"] = "normal"
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN", "-appearance.fontScale", "1.0", "-appearance.fontFollowsSystem", "NO"]
        XCUIDevice.shared.orientation = .landscapeLeft
        app.launch()
        XCTAssertTrue(waitForWindow(app, landscape: UIDevice.current.userInterfaceIdiom == .pad))
        let search = app.textFields["bot.chat-search"]
        XCTAssertTrue(search.waitForExistence(timeout: 10))
        XCTAssertFalse(app.descendants(matching: .any)["bot.composer"].exists)
        let chief = app.buttons["bot.chief"]
        let room = app.buttons["bot.chat.room"]
        XCTAssertGreaterThan(search.frame.minX, chief.frame.maxX + 40)
        capture(app, "13-R2-大屏主页")
        search.tap(); search.typeText("Bot 接入讨论")
        XCTAssertTrue(room.waitForExistence(timeout: 3))
        XCTAssertFalse(app.buttons["bot.chat.researcher"].exists)
        XCTAssertTrue(app.staticTexts["1 个结果"].exists)
        capture(app, "14-R2-名称搜索")
        search.buttons.firstMatch.tap()
        search.typeText("汇总")
        XCTAssertTrue(room.waitForExistence(timeout: 3), "应按最近消息摘要搜索")
        XCTAssertFalse(app.buttons["bot.chat.researcher"].exists)
        search.typeText("\n")
        room.tap()
        XCTAssertTrue(app.descendants(matching: .any)["bot.composer"].firstMatch.waitForExistence(timeout: 4))
        XCTAssertFalse(search.exists)
        capture(app, "15-R2-独立群聊")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(search.waitForExistence(timeout: 4))
        XCTAssertEqual(search.value as? String, "汇总", "返回主页应保留搜索条件")
        search.tap(); search.buttons.firstMatch.tap(); search.typeText("会议纪要")
        XCTAssertTrue(app.staticTexts["没有找到相关聊天"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.staticTexts["0 个结果"].exists)
        search.typeText("\n")
        capture(app, "16-R2-搜索无结果")
        search.tap(); search.buttons.firstMatch.tap(); search.typeText("\n")
        XCTAssertTrue(room.waitForExistence(timeout: 3))
        XCTAssertTrue(app.buttons["bot.chat.researcher"].exists)
        XCUIDevice.shared.orientation = .portrait
        XCTAssertTrue(waitForWindow(app, landscape: false))
        XCTAssertTrue(search.exists)
        capture(app, "17-R2-大屏竖屏主页")
    }

    @MainActor
    func testEmptyOfflineHomeAndSchedule() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchEnvironment["DSH_BOT_DESIGN_UI_TEST"] = "1"
        app.launchEnvironment["DSH_BOT_DESIGN_STATE"] = "empty"
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN", "-appearance.fontScale", "1.0"]
        XCUIDevice.shared.orientation = .portrait
        app.launch()
        XCTAssertTrue(app.staticTexts["连接主机后查看个人助理"].waitForExistence(timeout: 10))
        capture(app, "08-Bot-离线空态")
        let schedules = app.buttons["bot.schedules"]
        if !schedules.isHittable { app.swipeUp() }
        schedules.tap()
        XCTAssertTrue(app.staticTexts["还没有定时任务"].waitForExistence(timeout: 3))
        capture(app, "09-Bot-空任务面板")
        XCUIDevice.shared.orientation = .landscapeLeft
        XCTAssertTrue(waitForWindow(app, landscape: UIDevice.current.userInterfaceIdiom == .pad))
        XCTAssertTrue(app.buttons["bot.schedules-close"].isHittable)
        capture(app, "10-Bot-横屏空任务面板")
        let close = app.buttons["bot.schedules-close"]
        close.tap()
        XCTAssertTrue(close.waitForNonExistence(timeout: 3))
        XCUIDevice.shared.orientation = .portrait
    }

    @MainActor
    func testLoadingAndErrorPanelsKeepBounds() throws {
        continueAfterFailure = false
        for scenario in ["loading", "error"] {
            let app = XCUIApplication()
            app.launchEnvironment["DSH_BOT_DESIGN_UI_TEST"] = "1"
            app.launchEnvironment["DSH_BOT_DESIGN_STATE"] = scenario
            app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN", "-appearance.fontScale", "1.5", "-appearance.fontFollowsSystem", "NO"]
            XCUIDevice.shared.orientation = .portrait
            app.launch()
            let schedules = app.buttons["bot.schedules"]
            XCTAssertTrue(schedules.waitForExistence(timeout: 10))
            if !schedules.isHittable { app.swipeUp() }
            schedules.tap()
            XCTAssertTrue(app.descendants(matching: .any)["bot.panel-state"].firstMatch.waitForExistence(timeout: 3))
            let text = scenario == "loading" ? "正在同步助理" : "同步超时，请稍后重试；发送中的消息请先核对"
            XCTAssertTrue(app.staticTexts[text].exists)
            capture(app, "11-\(scenario)-大字号面板")
            XCUIDevice.shared.orientation = .landscapeLeft
            XCTAssertTrue(waitForWindow(app, landscape: UIDevice.current.userInterfaceIdiom == .pad))
            XCTAssertTrue(app.buttons["bot.schedules-close"].isHittable)
            capture(app, "12-\(scenario)-横屏面板")
            app.buttons["bot.schedules-close"].tap()
            app.terminate()
        }
        XCUIDevice.shared.orientation = .portrait
    }

    @MainActor private func waitForWindow(_ app: XCUIApplication, landscape: Bool) -> Bool {
        let expectedLandscape = landscape && UIDevice.current.userInterfaceIdiom == .pad
        let expected = NSPredicate { _, _ in
            let frame = app.windows.firstMatch.frame
            return expectedLandscape ? frame.width > frame.height : frame.height > frame.width
        }
        let result = XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: expected, object: app)], timeout: 5)
        // 原生旋转动画结束后再量取控件边界。
        Thread.sleep(forTimeInterval: 0.8)
        return result == .completed
    }

    @MainActor private func capture(_ app: XCUIApplication, _ name: String) {
        let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); shot.name = name; shot.lifetime = .keepAlways; add(shot)
    }
}
