package com.clarklevis.dsh.android

import android.app.Activity
import android.app.Application
import android.content.pm.ActivityInfo
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clarklevis.dsh.android.ui.*
import com.clarklevis.dsh.shared.platform.*
import java.util.UUID
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 使用与 iOS 相同的快照验证真实组件、抽屉、输入和面板；无主机通信。 */
@RunWith(AndroidJUnit4::class)
class BotDesignDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<BotDesignTestActivity>()

    @Test fun designHomeDrawerChatAndSchedule() {
        val args = InstrumentationRegistry.getArguments()
        val label = args.getString("botCaptureLabel")?.takeIf { it.matches(Regex("[a-zA-Z0-9-]{1,64}")) } ?: "design"
        val application = ApplicationProvider.getApplicationContext<Application>()
        val device = InstrumentationRegistry.getInstrumentation().uiAutomation
        val largeDevice = compose.activity.resources.configuration.smallestScreenWidthDp >= 600
        check(compose.activity.requestedOrientation == if (largeDevice) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) { "启动方向必须按设备大小配置" }
        val currentLandscape = compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val naturalLandscape = currentLandscape == (compose.activity.windowManager.defaultDisplay.rotation % 2 == 0)
        val landscapeRotation = if (naturalLandscape) android.app.UiAutomation.ROTATION_FREEZE_0 else android.app.UiAutomation.ROTATION_FREEZE_90
        val portraitRotation = if (naturalLandscape) android.app.UiAutomation.ROTATION_FREEZE_90 else android.app.UiAutomation.ROTATION_FREEZE_0
        val directory = java.io.File(application.getExternalFilesDir(null), "bot-design/$label").apply { mkdirs() }
        fun capture(name: String) {
            compose.waitForIdle()
            android.os.SystemClock.sleep(300)
            // 全新平板可能出现系统窗口操作教学，不把系统遮罩当成应用验收截图。
            InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
                ?.findAccessibilityNodeInfosByText("Got it")?.forEach { it.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) }
            android.os.SystemClock.sleep(200)
            val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            java.io.File(directory, "$name.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
        fun assertPanelFontScale(lineHeight: Float = 25f) {
            if ((args.getString("botFontScale")?.toFloatOrNull() ?: 1f) >= 1.5f) {
                val height = compose.onNodeWithTag("bot.panel-heading").fetchSemanticsNode().boundsInRoot.height
                check(height >= lineHeight * 1.35f * compose.density.density) { "独立面板必须继承应用字号，实际标题高度 $height" }
            }
        }
        val locale = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.SIMPLIFIED_CHINESE)
        val graph = AndroidAppGraph(application, preferencesOverride = MemoryPreferences(),
            credentialStoreOverride = MemoryCredentials(), gatewayLocalId = "bot-design-${UUID.randomUUID()}",
            networkMonitorOverride = object : GatewayNetworkMonitor { override val state = MutableStateFlow(GatewayNetworkState.AVAILABLE) }, storedConnectionPrerequisite = {})
        val appearancePrefs = application.getSharedPreferences("bot-design-appearance-${UUID.randomUUID()}", android.content.Context.MODE_PRIVATE)
        val appearance = AppearanceSettings(appearancePrefs)
        appearance.selectFontScale(args.getString("botFontScale")?.toFloatOrNull() ?: 1f)
        val holder = graph.stateHolder
        val scenario = args.getString("botDesignState") ?: "normal"
        val homeProfile = scenario == "home-scroll-profile"
        val profileBotMode = args.getString("botProfileMode") != "standard"
        val liveWork = scenario == "live-work" || scenario == "live-work-night"
        if (scenario in setOf("night", "mode-wave-night", "live-work-night") || args.getString("botNight") == "1") {
            appearance.selectInterfaceStyle(InterfaceStyle.DARK)
        }
        val glassApi = args.getString("botGlassApi")?.toIntOrNull() ?: android.os.Build.VERSION.SDK_INT
        val suffix = when (scenario) {
            "empty", "loading", "error" -> "-empty"
            "unread-overflow" -> "-long"
            "long", "markdown-images", "files", "bubble-widths", "role-profile", "page-parity" -> "-$scenario"
            "role-schedule" -> "-role-profile"
            "images", "image-error" -> "-images"
            else -> ""
        }
        val baseFixture = InstrumentationRegistry.getInstrumentation().context.assets
            .open("bot-design-fixture$suffix.json").bufferedReader().use { it.readText() }
        val scrollProfile = scenario == "scroll-profile"
        val scrollFixture = scenario == "scroll" || scrollProfile
        val messageCount = if (scrollProfile) 96 else 24
        val fixture = if (scrollFixture) {
            val messages = org.json.JSONArray()
            for (index in 1..messageCount) {
                val body = if (scrollProfile) {
                    "历史消息 $index\n\n- **验证 Markdown 布局**，打开 `reports/report-$index.md`。\n" +
                        "- [报告](https://example.com/report/$index) 包含两段正文。\n\n" +
                        "历史消息滚动时，标题、列表、链接和行内代码应保持同样的格式。"
                } else "历史消息 $index\n\n用于验证离开会话底部后，可以一键回到最新消息。"
                messages.put(org.json.JSONObject()
                    .put("id", "scroll-$index")
                    .put("kind", "bot")
                    .put("botId", "chief")
                    .put("text", body))
            }
            org.json.JSONObject(baseFixture).put("histories", org.json.JSONObject().put("chief", messages)).toString()
        } else if (scenario.startsWith("conversation")) {
            org.json.JSONObject(baseFixture).apply {
                put("conversationTest", scenario)
                val chats = getJSONObject("snapshot").getJSONArray("chats")
                for (index in 0 until chats.length()) {
                    if (chats.getJSONObject(index).optString("id") == "researcher") chats.getJSONObject(index).put("hidden", true)
                }
                repeat(24) { index -> chats.put(org.json.JSONObject().put("id", "extra-${index + 1}")
                    .put("name", "测试成员 ${index + 1}").put("role", "菜单滚动验证")) }
            }.toString()
        } else if (homeProfile) {
            org.json.JSONObject(baseFixture).apply {
                val chats = getJSONObject("snapshot").getJSONArray("chats")
                repeat(36) { index ->
                    chats.put(org.json.JSONObject().put("id", "profile-$index")
                        .put("name", "项目讨论 $index").put("preview", "移动端验收与性能记录")
                        .put("lastAt", 1794810480000L - index * 60_000))
                }
            }.toString()
        } else if (scenario == "role-keyboard") {
            org.json.JSONObject(baseFixture).apply {
                getJSONObject("snapshot").put("roleSettingsSupported", true)
            }.toString()
        } else if (scenario == "schedule-clock") {
            org.json.JSONObject(baseFixture).apply {
                getJSONObject("snapshot").getJSONArray("schedules").getJSONObject(0)
                    .put("title", "播报每日十条新闻")
                    .put("next", "2026-11-23T00:00:00.000Z")
            }.toString()
        } else if (scenario == "role-schedule") {
            org.json.JSONObject(baseFixture).apply {
                getJSONObject("snapshot").getJSONArray("schedules").getJSONObject(0)
                    .put("title", "播报每日十条新闻")
            }.toString()
        } else baseFixture
        var activity: Activity? = null
        try {
            compose.runOnIdle {
                holder.restoreBotDesignPreview(fixture, connected = scenario != "empty",
                    errorCode = if (scenario == "error") "bot-timeout" else null, showLiveWork = liveWork)
                holder.botDesignImageFailOnce = scenario == "image-error"
                if (homeProfile && !profileBotMode) {
                    val items = org.json.JSONArray()
                    repeat(36) { index ->
                        items.put(org.json.JSONObject().put("sessionId", "profile-$index")
                            .put("updatedAt", 1794810480L - index * 60).put("running", false).put("blank", false)
                            .put("projections", org.json.JSONObject().put("values",
                                org.json.JSONObject().put("title", "项目讨论 $index"))))
                    }
                    holder.wirePayload = org.json.JSONObject().put("kind", "sessions").put("items", items).toString()
                    holder.submitWirePayload()
                }
            }
            compose.setContent {
                val view = LocalView.current
                val configuration = android.content.res.Configuration(LocalConfiguration.current).apply { setLocale(java.util.Locale.SIMPLIFIED_CHINESE) }
                val context = LocalContext.current.createConfigurationContext(configuration)
                SideEffect {
                    java.util.Locale.setDefault(java.util.Locale.SIMPLIFIED_CHINESE)
                    activity = view.context as? Activity
                    activity?.window?.let {
                        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(it, false)
                        it.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                    }
                }
                var bot by remember { mutableStateOf(!homeProfile || profileBotMode) }
                var showAlert by remember { mutableStateOf(scenario == "alert") }
                CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration,
                    LocalDshCardGlassApi provides glassApi,
                    androidx.activity.compose.LocalActivityResultRegistryOwner provides compose.activity) {
                    DshTheme(appearance = appearance) {
                        WorkspaceScreen(holder, {}, {}, {}, {}, {}, {}, { bot = true }, bot, { bot = false })
                        if (showAlert) BotModeTheme {
                            DshAlertDialog(title = "Whaleness", message = "同步超时，请稍后重试；发送中的消息请先核对",
                                onDismissRequest = { showAlert = false }, dismissLabel = "好", onDismissClick = { showAlert = false })
                        }
                    }
                }
            }
            if (homeProfile) {
                device.setRotation(if (largeDevice) landscapeRotation else portraitRotation)
                val orientation = if (largeDevice) android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    else android.content.res.Configuration.ORIENTATION_PORTRAIT
                compose.waitUntil(5_000) { activity?.resources?.configuration?.orientation == orientation }
                val tag = if (!profileBotMode) "workspace-session-list" else if (
                    compose.onAllNodesWithTag("bot.wide-chats").fetchSemanticsNodes().isNotEmpty()
                ) "bot.wide-chats" else "bot.home"
                val list = compose.onNodeWithTag(tag)
                val viewport = list.fetchSemanticsNode().boundsInRoot
                if (tag == "bot.home" && args.getString("botAssertEdgeToEdge") == "1") {
                    check(viewport.top <= 1f) { "Bot 首页列表应延伸到状态栏背后，viewport=$viewport" }
                }
                fun shell(command: String) = android.os.ParcelFileDescriptor
                    .AutoCloseInputStream(device.executeShellCommand(command)).bufferedReader().use { it.readText() }
                val x = viewport.center.x.toInt()
                val top = (viewport.top + viewport.height * .22f).toInt()
                val bottom = (viewport.bottom - viewport.height * .22f).toInt()
                fun swipe(up: Boolean) {
                    val from = if (up) bottom else top
                    val to = if (up) top else bottom
                    shell("input swipe $x $from $x $to 230")
                    android.os.SystemClock.sleep(260)
                }
                swipe(true)
                swipe(false)
                shell("dumpsys gfxinfo ${application.packageName} reset")
                repeat(12) { index -> swipe(index % 2 == 0) }
                println(shell("dumpsys gfxinfo ${application.packageName}").lineSequence()
                    .filter { it.startsWith("Total frames rendered") || it.startsWith("Janky frames:") ||
                        it.startsWith("90th percentile") || it.startsWith("95th percentile") ||
                        it.startsWith("99th percentile") }.joinToString(" | ", prefix = "HOME_SCROLL_PROFILE $tag "))
                capture("home-profile")
                if (tag == "bot.home") {
                    list.performScrollToIndex(0)
                    list.performTouchInput {
                        swipe(center, center - androidx.compose.ui.geometry.Offset(0f, 68.dp.toPx()), durationMillis = 600)
                    }
                    capture("scrolled-under-status")
                }
                return
            }
            if (scenario.startsWith("conversation")) {
                compose.onNodeWithTag("bot.conversation.new").performScrollTo().performClick()
                capture("new-conversation-menu")
                compose.onNodeWithTag("bot.conversation.open.extra-24").performScrollTo().assertIsDisplayed()
                capture("new-conversation-menu-scrolled")
                androidx.test.espresso.Espresso.pressBack()
                compose.onNodeWithTag("bot.conversation.new").performClick()
                compose.onNodeWithTag("bot.conversation.group").performClick()
                compose.onNodeWithTag("bot.group.admin-note").assertIsDisplayed()
                val draft = { holder.botSnapshot.conversation }
                compose.runOnIdle { check(draft().memberIds.isEmpty()) }
                compose.onNodeWithTag("bot.conversation.member.chief").performScrollTo().performClick()
                compose.onNodeWithTag("bot.conversation.member.researcher").performScrollTo().performClick()
                compose.runOnIdle { check(draft().adminId == "chief") }
                compose.onNodeWithTag("bot.group.remove.chief").performScrollTo().performClick()
                compose.runOnIdle { check(draft().adminId == "researcher") }
                compose.onNodeWithTag("bot.conversation.member.chief").performScrollTo().performClick()
                compose.runOnIdle { check(draft().memberIds == listOf("researcher", "chief")) }
                capture("group-ordered-members")
                if (largeDevice) {
                    device.setRotation(portraitRotation)
                    compose.waitUntil(8_000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
                    capture("group-portrait")
                    device.setRotation(landscapeRotation)
                    compose.waitUntil(8_000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
                    capture("group-landscape")
                }
                compose.onNodeWithTag("bot.group.message").performScrollTo().performTextInput("请一起整理调研结论。")
                androidx.test.espresso.Espresso.closeSoftKeyboard()
                capture("group-first-message")
                compose.onNodeWithTag("bot.group.create").performClick()
                if (scenario == "conversation-error") {
                    compose.waitUntil(10_000) { holder.botSnapshot.conversation.error != null }
                    compose.runOnIdle {
                        check(draft().message == "请一起整理调研结论。")
                        check(draft().memberIds == listOf("researcher", "chief"))
                    }
                    compose.onNodeWithTag("bot.conversation.error").performScrollTo().assertIsDisplayed()
                    capture("group-error-preserves-draft")
                } else {
                    compose.waitUntil(10_000) { holder.botSnapshot.selectedChatId == "created-room" }
                    compose.onNodeWithTag("bot.chat-identity").assertIsDisplayed()
                    capture("group-created-chat")
                }
                return
            }
            if (scenario == "schedule-clock") {
                val landscape = largeDevice && args.getString("botTabletPortrait") != "1"
                device.setRotation(if (landscape) landscapeRotation else portraitRotation)
                val orientation = if (landscape) android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    else android.content.res.Configuration.ORIENTATION_PORTRAIT
                compose.waitUntil(5_000) { activity?.resources?.configuration?.orientation == orientation }
                compose.onNodeWithTag("bot.schedules").performScrollTo().performClick()
                val expected = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(1795392000000L))
                compose.onNodeWithText("播报每日十条新闻").assertIsDisplayed()
                compose.onNodeWithTag("bot.schedule-clock.s1").assertTextEquals(expected).assertIsDisplayed()
                compose.onNodeWithTag("bot.schedule-clock.s2").assertTextEquals("18:00").assertIsDisplayed()
                assertPanelFontScale()
                capture("schedule-clock")
                compose.onNodeWithTag("bot.schedules-close").performTouchInput { click() }
                compose.onNodeWithTag("bot.schedules-close").assertDoesNotExist()
                compose.onNodeWithTag("bot.schedules").assertIsDisplayed()
                return
            }
            if (scenario == "search") {
                val landscape = largeDevice && args.getString("botTabletPortrait") != "1"
                device.setRotation(if (landscape) landscapeRotation else portraitRotation)
                val orientation = if (landscape) android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    else android.content.res.Configuration.ORIENTATION_PORTRAIT
                compose.waitUntil(5_000) { activity?.resources?.configuration?.orientation == orientation }
                val wide = compose.onAllNodesWithTag("bot.wide-chats").fetchSemanticsNodes().isNotEmpty()
                val list = compose.onNodeWithTag(if (wide) "bot.wide-chats" else "bot.home")
                if (!wide) list.performScrollToNode(hasTestTag("bot.chat-search"))
                compose.onNodeWithTag("bot.chat-search").assertIsDisplayed()
                capture("search-home")

                val search = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("bot.chat-search")))
                search.performTextInput("沿用现有通知方式")
                search.performImeAction()
                val result = compose.onNodeWithTag("bot.chat.chief").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("1 个结果").assertIsDisplayed()
                compose.onNodeWithTag("bot.chat.room").assertDoesNotExist()
                capture("search-history")

                result.performClick()
                compose.onNodeWithTag("bot.composer").assertIsDisplayed()
                compose.onNodeWithTag("bot.chat-back").performClick()
                if (!wide) list.performScrollToNode(hasTestTag("bot.chat-search"))
                search.assertTextEquals("沿用现有通知方式")
                search.performClick()
                compose.onNodeWithTag("search.clear").performClick()
                search.performTextInput("会议纪要")
                search.performImeAction()
                compose.onNodeWithTag("bot.search-empty").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("0 个结果").assertIsDisplayed()
                return
            }
            if (scenario == "unread" || scenario == "unread-overflow") {
                val unread = if (scenario == "unread-overflow") 100 else 2
                val landscape = largeDevice && args.getString("botTabletPortrait") != "1"
                device.setRotation(if (landscape) landscapeRotation else portraitRotation)
                val orientation = if (landscape) android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    else android.content.res.Configuration.ORIENTATION_PORTRAIT
                compose.waitUntil(5_000) { activity?.resources?.configuration?.orientation == orientation }
                val room = compose.onNodeWithTag("bot.chat.room").performScrollTo().assertIsDisplayed()
                val badge = compose.onNodeWithTag("bot.unread.room", useUnmergedTree = true)
                badge.assertIsDisplayed()
                check(compose.onAllNodesWithContentDescription("未读消息：$unread").fetchSemanticsNodes().isNotEmpty())
                compose.onNode(hasText(if (unread > 99) "99+" else unread.toString()) and
                    hasAnyAncestor(hasTestTag("bot.unread.room")), useUnmergedTree = true).assertIsDisplayed()
                capture("unread")
                room.performClick()
                compose.onNodeWithTag("bot.chat-more").assertIsDisplayed()
                compose.onNodeWithTag("bot.chat-back").performClick()
                room.performScrollTo().assertIsDisplayed()
                badge.assertDoesNotExist()
                capture("read")
                return
            }
            if (scenario == "page-parity") {
                device.setRotation(if (largeDevice && args.getString("botTabletPortrait") != "1") landscapeRotation else portraitRotation)
                val orientation = if (largeDevice && args.getString("botTabletPortrait") != "1") {
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE
                } else android.content.res.Configuration.ORIENTATION_PORTRAIT
                compose.waitUntil(5_000) { activity?.resources?.configuration?.orientation == orientation }
                val chief = compose.onNodeWithTag("bot.chief")
                chief.performScrollTo().assertIsDisplayed()
                capture("avatars-home")
                chief.performClick()
                if (args.getString("botNight") == "1" && !largeDevice) {
                    val handle = compose.onNodeWithTag("bot.panel-handle")
                    val heading = compose.onNodeWithTag("bot.panel-title")
                    val initialTop = heading.fetchSemanticsNode().boundsInRoot.top
                    handle.performTouchInput {
                        down(center)
                        moveBy(androidx.compose.ui.geometry.Offset(0f, -95f * compose.density.density), delayMillis = 400)
                    }
                    val heldTop = heading.fetchSemanticsNode().boundsInRoot.top
                    check(heldTop < initialTop - 32.dp.value * compose.density.density)
                    handle.performTouchInput { up() }
                    compose.waitUntil(5_000) { heading.fetchSemanticsNode().boundsInRoot.top < heldTop }
                    capture("role-expanded")
                    handle.performTouchInput {
                        down(center)
                        moveBy(androidx.compose.ui.geometry.Offset(0f, 95f * compose.density.density), delayMillis = 400)
                        up()
                    }
                    compose.waitUntil(5_000) {
                        kotlin.math.abs(heading.fetchSemanticsNode().boundsInRoot.top - initialTop) < 2
                    }
                    capture("role-compact")
                }
                val panel = compose.onNodeWithTag("bot.role-panel")
                val inRole = hasAnyAncestor(hasTestTag("bot.role-panel"))
                val memoryLink = hasText("记忆") and inRole
                panel.performScrollToNode(memoryLink)
                compose.onNode(memoryLink).performClick()
                val add = compose.onNodeWithTag("bot.role-memory-add").assertIsEnabled()
                val longMemory = "用户正在调研 DS-Bot"
                val memory = compose.onNodeWithTag("bot.role-memory.1").assertIsDisplayed()
                compose.onNodeWithText("删除").assertDoesNotExist()
                capture("memory-list")
                memory.performTouchInput { swipe(percentOffset(.8f, .5f), percentOffset(.48f, .5f), durationMillis = 375) }
                val delete = compose.onNodeWithTag("bot.role-row-delete").assertIsDisplayed()
                capture("memory-swipe")
                delete.performClick()
                compose.onNodeWithText("删除这条记忆？").assertIsDisplayed()
                compose.onNodeWithText("取消").performClick()
                memory.performTouchInput { swipe(percentOffset(.94f, .5f), percentOffset(.04f, .5f), durationMillis = 600) }
                compose.onNodeWithText("删除这条记忆？").assertIsDisplayed()
                compose.onNodeWithText("取消").performClick()
                compose.onNode(hasText(longMemory, substring = true) and hasClickAction()).performClick()
                val editor = compose.onNodeWithTag("bot.memory-editor").assertHeightIsAtLeast(160.dp)
                editor.assertTextContains(longMemory, substring = true)
                capture("memory-edit")
                androidx.test.espresso.Espresso.pressBack()
                add.assertIsDisplayed().performClick()
                compose.onNodeWithTag("bot.memory-save").assertIsNotEnabled()
                capture("memory-add")
                compose.onNodeWithTag("bot.role-back").performClick()
                compose.onNodeWithTag("bot.role-back").performClick()
                compose.onNodeWithTag("bot.role-close").performClick()

                compose.onNodeWithTag("bot.chat.room").performScrollTo().performClick()
                val identity = compose.onNodeWithTag("bot.chat-identity")
                identity.performClick()
                val member = compose.onNodeWithTag("bot.member.researcher").assertIsDisplayed()
                capture("members")
                member.performClick()
                compose.onNodeWithTag("bot.role-settings").assertIsDisplayed()
                compose.onNodeWithTag("bot.role-close").performClick()
                member.assertIsDisplayed()
                compose.onNodeWithTag("bot.members-close").performClick()
                compose.onNodeWithTag("bot.chat-more").performClick()
                compose.onNodeWithText("重新同步").assertIsDisplayed()
                capture("chat-more")
                compose.onNodeWithText("定时任务").performClick()
                compose.onNodeWithTag("bot.schedules-close").performClick()
                compose.onNodeWithTag("bot.chat-back").performClick()

                compose.onNodeWithTag("bot.start-chat").performScrollTo().performClick()
                val answer = compose.onNodeWithText("回答").assertIsDisplayed()
                answer.performClick()
                val submit = compose.onNodeWithTag("bot.question.submit").assertIsNotEnabled()
                val custom = compose.onNodeWithTag("bot.question.custom")
                capture("question")
                custom.performTextInput("补充内容")
                submit.assertIsEnabled()
                compose.onNodeWithText("交互").performClick().assertIsSelected()
                custom.assertTextContains("补充回答")
                submit.assertIsEnabled()
                capture("question-selected")
                compose.onNodeWithText("布局").performClick().assertIsSelected()
                compose.onNodeWithText("交互").assertIsNotSelected()
                compose.onNodeWithText("布局").performClick()
                submit.assertIsNotEnabled()
                compose.onNodeWithText("取消").performClick()
                answer.assertIsDisplayed()
                compose.onNodeWithTag("bot.chat-back").performClick()

                compose.onNodeWithTag("bot.roster").performScrollTo().performClick()
                val newBot = compose.onNodeWithTag("bot.new").assertIsEnabled()
                capture("roster")
                newBot.performClick()
                val appearance = compose.onNodeWithTag("bot.create.appearance").assertIsEnabled()
                capture("create")
                appearance.performClick()
                compose.onNodeWithText("形状").assertIsDisplayed()
                capture("appearance")
                compose.onNodeWithText("头像库").performScrollTo().performClick()
                val photo = compose.onNodeWithContentDescription("parity-avatar.png").assertIsDisplayed()
                capture("avatar-library")
                photo.performClick()
                compose.onNodeWithTag("bot.appearance.done").assertIsDisplayed().performClick()
                val modelPicker = compose.onNodeWithTag("bot.create.model")
                modelPicker.performScrollTo().performClick()
                compose.onNodeWithText("可用模型").assertIsDisplayed()
                capture("model")
                compose.onNodeWithText("DeepSeek-V4.1-Flash").performScrollTo().performClick()
                compose.onNodeWithText("可用模型").assertIsDisplayed()
                compose.onNodeWithText("取消").performClick()
                modelPicker.assertTextContains("DeepSeek-V4.1-Flash")
                compose.onNodeWithText("本地 Agent").performScrollTo().performClick()
                val create = compose.onNodeWithTag("bot.create.submit").assertIsNotEnabled()
                val agent = compose.onNodeWithTag("bot.create.agent.local-parity")
                    .performScrollTo().assertIsEnabled().assertTextContains("可用")
                compose.onNodeWithTag("bot.create.agent.local-offline").assertIsNotEnabled()
                capture("local-agent")
                agent.performClick()
                create.assertIsEnabled()
                modelPicker.performScrollTo().performClick()
                val cliDefault = compose.onNodeWithText("CLI 默认").assertIsDisplayed()
                capture("local-model")
                cliDefault.performClick()
                create.assertIsDisplayed()
                return
            }
            if (scenario == "role-schedule") {
                device.setRotation(portraitRotation)
                val portrait = android.content.res.Configuration.ORIENTATION_PORTRAIT
                compose.waitUntil(5_000) { activity?.resources?.configuration?.orientation == portrait }
                compose.onNodeWithTag("bot.chief").performScrollTo().performClick()
                val panel = compose.onNodeWithTag("bot.role-panel")
                val inPanel = hasAnyAncestor(hasTestTag("bot.role-panel"))
                panel.performScrollToNode(hasText("定时任务"))
                compose.onNode(hasText("定时任务") and inPanel).performClick()
                val heading = compose.onNodeWithTag("bot.panel-heading").assertTextEquals("定时任务")
                val add = compose.onNodeWithTag("bot.role-schedule-add").assertIsDisplayed().assertIsEnabled()
                assertPanelFontScale(SettingsTypography.title.lineHeight.value)
                val headerCenter = compose.onNodeWithTag("bot.panel-title").fetchSemanticsNode().boundsInRoot.center.x
                check(kotlin.math.abs(heading.fetchSemanticsNode().boundsInRoot.center.x - headerCenter) <= 1f)
                val task = compose.onNodeWithTag("bot.role-schedule.morning").assertIsDisplayed()
                val title = compose.onNode(hasText("播报每日十条新闻") and inPanel).assertIsDisplayed()
                val time = compose.onNode(hasText("2026-11-22T00:00:00.000Z") and inPanel).assertIsDisplayed()
                if (!largeDevice) {
                    check(title.fetchSemanticsNode().boundsInRoot.bottom <= time.fetchSemanticsNode().boundsInRoot.top)
                }
                compose.onNodeWithText("删除").assertDoesNotExist()
                capture("role-schedule-list")
                if (largeDevice) {
                    device.setRotation(landscapeRotation)
                    val landscape = android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    compose.waitUntil(5_000) { activity?.resources?.configuration?.orientation == landscape }
                    add.assertIsDisplayed()
                    capture("role-schedule-landscape")
                }
                task.performTouchInput { swipe(percentOffset(.8f, .5f), percentOffset(.48f, .5f), durationMillis = 375) }
                val delete = compose.onNodeWithTag("bot.role-row-delete").assertIsDisplayed()
                capture("role-schedule-swipe")
                delete.performClick()
                compose.onNodeWithText("删除这个定时任务？").assertIsDisplayed()
                compose.onNodeWithText("取消").performClick()
                task.assertIsDisplayed()
                compose.onNodeWithText("删除").assertDoesNotExist()
                add.performClick()
                heading.assertTextEquals("新建定时任务")
                val save = compose.onNodeWithTag("bot.role-schedule-save").assertTextEquals("保存").assertIsNotEnabled()
                val name = compose.onNodeWithTag("bot.role-schedule-name").assertIsDisplayed()
                val prompt = compose.onNodeWithTag("bot.role-schedule-prompt").assertIsDisplayed()
                val timeField = compose.onNodeWithTag("bot.role-schedule-time").assertIsDisplayed()
                capture("role-schedule-new")
                timeField.performClick()
                val timePopup = compose.onNodeWithTag("dsh-compact-time-popup").assertIsDisplayed()
                compose.onNodeWithTag("dsh-compact-time-hour").performSemanticsAction(SemanticsActions.SetProgress) { it(8f) }
                compose.onNodeWithTag("dsh-compact-time-minute").performSemanticsAction(SemanticsActions.SetProgress) { it(30f) }
                compose.onNodeWithText("确定").assertDoesNotExist()
                timeField.assertTextContains("8:30", substring = true)
                capture("role-schedule-time")
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                compose.waitUntil(5_000) { compose.onAllNodesWithTag("dsh-compact-time-popup").fetchSemanticsNodes().isEmpty() }
                timePopup.assertDoesNotExist()
                timeField.assertTextContains("8:30", substring = true)
                name.performClick().performTextInput("每日简报")
                save.assertIsNotEnabled()
                prompt.performClick().performTextInput("整理今天的新闻")
                save.assertIsEnabled()
                return
            }
            if (scenario == "role-profile") {
                device.setRotation(portraitRotation)
                val portrait = android.content.res.Configuration.ORIENTATION_PORTRAIT
                compose.waitUntil(5_000) { activity?.resources?.configuration?.orientation == portrait }
                compose.onNodeWithTag("bot.chief").performScrollTo().performClick()
                val panel = compose.onNodeWithTag("bot.role-panel")
                val inPanel = hasAnyAncestor(hasTestTag("bot.role-panel"))
                val settings = compose.onNodeWithTag("bot.role-settings")
                fun checkProfile(captureName: String) {
                    settings.assertIsDisplayed()
                    panel.performScrollToIndex(0)
                    compose.onNode(hasText("GPT-6.1 Terra") and inPanel, useUnmergedTree = true).assertExists()
                    capture(captureName)
                    panel.performScrollToNode(hasText("近 30 天用量"))
                    val centers = listOf("记忆", "定时任务", "近 30 天用量").map { title ->
                        compose.onNode(hasText(title) and inPanel, useUnmergedTree = true).assertIsDisplayed()
                            .fetchSemanticsNode().boundsInRoot.center.y / compose.density.density
                    }
                    check(centers.zipWithNext().all { (above, below) -> kotlin.math.abs(below - above - 52.5f) <= 1.5f }) {
                        "记忆、定时任务和用量的行高应一致，且分割线之间无额外留白"
                    }
                }
                checkProfile("role-profile-portrait")
                if (largeDevice) {
                    device.setRotation(landscapeRotation)
                    val landscape = android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    compose.waitUntil(5_000) { activity?.resources?.configuration?.orientation == landscape }
                    checkProfile("role-profile-landscape")
                }
                compose.onNode(hasText("记忆") and inPanel).performClick()
                panel.performScrollToNode(hasText("记忆测试一"))
                compose.onNodeWithContentDescription("返回").performClick()
                panel.performScrollToNode(hasText("定时任务"))
                compose.onNode(hasText("定时任务") and inPanel).performClick()
                panel.performScrollToNode(hasText("每日简报"))
                compose.onNodeWithContentDescription("返回").performClick()
                settings.performClick()
                val heading = compose.onNodeWithTag("bot.panel-heading").assertTextEquals("角色设置")
                val save = compose.onNodeWithTag("bot.role-save").assertTextEquals("保存").assertIsNotEnabled()
                val picker = compose.onNodeWithTag("bot.role-model-picker")
                fun checkSettings(captureName: String) {
                    panel.performScrollToIndex(0)
                    val x = heading.fetchSemanticsNode().boundsInRoot.center.x
                    val panelCenter = panel.fetchSemanticsNode().boundsInRoot.center.x
                    check(kotlin.math.abs(x - panelCenter) <= 2 * compose.density.density) { "角色设置标题应水平居中" }
                    val identityTag = hasTestTag("bot.role-settings-identity")
                    val identity = compose.onNode(identityTag).fetchSemanticsNode().boundsInRoot
                    val name = compose.onNode(hasText("大主管") and hasAnyAncestor(identityTag)).fetchSemanticsNode().boundsInRoot
                    check(name.left - identity.left >= 66 * compose.density.density) { "设置页姓名应位于头像右侧" }
                    capture(captureName)
                    panel.performScrollToNode(hasTestTag("bot.role-model-picker"))
                    val rowHeight = picker.fetchSemanticsNode().boundsInRoot.height / compose.density.density
                    check(kotlin.math.abs(rowHeight - 52) <= 1) { "模型设置行应为 52 dp" }
                }
                checkSettings(if (largeDevice) "role-settings-landscape" else "role-settings-portrait")
                if (largeDevice) {
                    device.setRotation(portraitRotation)
                    compose.waitUntil(5_000) { activity?.resources?.configuration?.orientation == portrait }
                    checkSettings("role-settings-portrait")
                }
                picker.performClick()
                val inMenu = hasAnyAncestor(hasTestTag("dsh-selection-popup"))
                compose.onNode(hasText("GPT-6.1 Terra") and inMenu).assertIsSelected()
                capture("role-settings-model-menu")
                compose.onNode(hasText("DeepSeek-V4.1-Flash") and inMenu).performClick()
                picker.assertTextContains("DeepSeek-V4.1-Flash")
                save.assertIsEnabled()
                return
            }
            if (scenario == "roster-layout") {
                device.setRotation(portraitRotation)
                val portrait = android.content.res.Configuration.ORIENTATION_PORTRAIT
                compose.waitUntil(5_000) { activity?.resources?.configuration?.orientation == portrait }
                compose.onNodeWithTag("bot.roster").performScrollTo().performClick()
                val chief = compose.onNodeWithTag("bot.roster.chief")
                fun checkRoster(name: String) {
                    chief.assertIsDisplayed()
                    compose.onNodeWithTag("bot.roster.researcher").assertIsDisplayed()
                    compose.onNodeWithText("主 Bot", useUnmergedTree = true).assertIsDisplayed()
                    val tag = compose.onNodeWithTag("bot.roster.main-badge", useUnmergedTree = true)
                    val tagHeight = tag.fetchSemanticsNode().boundsInRoot.height / compose.density.density
                    val expectedHeight = 12 * appearance.effectiveFontScale + 6
                    check(kotlin.math.abs(tagHeight - expectedHeight) <= 1) {
                        "主 Bot 标签高度应为 $expectedHeight dp，实际 $tagHeight dp"
                    }
                    if (appearance.effectiveFontScale == 1f) {
                        val rowHeight = chief.fetchSemanticsNode().boundsInRoot.height / compose.density.density
                        check(kotlin.math.abs(rowHeight - 72) <= 1) { "普通字号下成员行应为 72 dp" }
                    }
                    capture(name)
                }
                checkRoster("roster-portrait")
                if (largeDevice) {
                    device.setRotation(landscapeRotation)
                    val landscape = android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    compose.waitUntil(5_000) { activity?.resources?.configuration?.orientation == landscape }
                    checkRoster("roster-landscape")
                }
                chief.performClick()
                compose.onNodeWithTag("bot.role-settings").assertIsDisplayed()
                return
            }
            if (scenario == "role-keyboard") {
                device.setRotation(if (largeDevice && args.getString("botTabletPortrait") != "1") landscapeRotation
                    else portraitRotation)
                withBotSoftwareKeyboard { keyboard ->
                    compose.onNodeWithTag("bot.chief").performScrollTo().performClick()
                    compose.onNodeWithTag("bot.role-settings").performClick()
                    val header = compose.onNodeWithTag("bot.panel-title")
                    val settledTop = header.fetchSemanticsNode().boundsInRoot.top
                    val name = compose.onNodeWithContentDescription("名字")
                    name.performTouchInput { click() }
                    compose.waitUntil(5_000) { keyboard() != null }
                    name.assertIsFocused().performTextInput(" draft")
                    capture("role-keyboard-shown")
                    if (!largeDevice) {
                        val keyboardBounds = requireNotNull(keyboard())
                        val screenshot = device.takeScreenshot()
                        try {
                            val inset = (3 * compose.density.density).toInt().coerceAtLeast(2)
                            check(keyboardBounds.width() >= screenshot.width - inset * 2) { "需要底部停靠的软键盘" }
                            val row = keyboardBounds.top - inset
                            val center = screenshot.getPixel(screenshot.width / 2, row)
                            check(screenshot.getPixel(inset, row) == center &&
                                screenshot.getPixel(screenshot.width - inset - 1, row) == center) { "面板底边应平直地贴合键盘" }
                        } finally {
                            screenshot.recycle()
                        }
                        check(header.fetchSemanticsNode().boundsInRoot.top >= settledTop - 2) { "键盘出现后，面板顶部应避开状态栏" }
                    }
                    compose.onNodeWithText("名字").performTouchInput { click() }
                    compose.waitUntil(5_000) { keyboard() == null }
                    name.assertIsNotFocused().assertTextContains(" draft", substring = true)
                    capture("role-keyboard-dismissed")
                    if (!largeDevice) {
                        val top = header.fetchSemanticsNode().boundsInRoot.top
                        header.performTouchInput {
                            down(center)
                            moveBy(androidx.compose.ui.geometry.Offset(0f, 36.dp.toPx()), delayMillis = 180)
                        }
                        try {
                            check(header.fetchSemanticsNode().boundsInRoot.top > top) { "拖动时标题应跟随面板" }
                            capture("role-sheet-held-drag")
                        } finally {
                            header.performTouchInput { advanceEventTime(500); up() }
                        }
                        header.assertIsDisplayed()
                    }
                }
                return
            }
            if (scenario == "role-model") {
                val model = "DeepSeek-V4.1-Flash"
                val rolePanel = compose.onNodeWithTag("bot.role-panel")
                fun checkModel(captureName: String) {
                    rolePanel.performScrollToNode(hasText(model))
                    val results = mutableListOf<TextLayoutResult>()
                    compose.onNodeWithText(model, useUnmergedTree = true).assertIsDisplayed()
                        .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
                    val layout = results.single()
                    check(layout.lineCount == 1) { "模型名必须单行显示" }
                    if (appearance.fontScale == 1f) {
                        check(!layout.isLineEllipsized(0)) { "空间充足时应完整显示模型名" }
                    }
                    capture(captureName)
                }
                device.setRotation(portraitRotation)
                compose.waitUntil(5_000) {
                    activity?.resources?.configuration?.orientation ==
                        android.content.res.Configuration.ORIENTATION_PORTRAIT
                }
                compose.onNodeWithTag("bot.chief").performScrollTo().performClick()
                checkModel("role-profile")
                compose.onNodeWithTag("bot.role-settings").performClick()
                checkModel("role-settings")
                if (largeDevice) {
                    device.setRotation(landscapeRotation)
                    compose.waitUntil(5_000) {
                        activity?.resources?.configuration?.orientation ==
                            android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    }
                    checkModel("role-settings-landscape")
                    compose.onNodeWithContentDescription("返回").performClick()
                    checkModel("role-profile-landscape")
                }
                return
            }
            if (liveWork) {
                device.setRotation(if (largeDevice) landscapeRotation else portraitRotation)
                val orientation = if (largeDevice) android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    else android.content.res.Configuration.ORIENTATION_PORTRAIT
                compose.waitUntil(5_000) { activity?.resources?.configuration?.orientation == orientation }
                compose.onNodeWithTag("bot.chat.room").performScrollTo()
                compose.onNodeWithTag("bot.running.room", useUnmergedTree = true).assertIsDisplayed()
                compose.onNodeWithTag("bot.unread.room", useUnmergedTree = true).assertDoesNotExist()
                capture("list-running")
                val chief = compose.onNodeWithTag("bot.start-chat").performScrollTo()
                compose.onNodeWithTag("bot.running.chief", useUnmergedTree = true).assertIsDisplayed()
                chief.performClick()
                compose.onNodeWithText("上网搜索…").assertIsDisplayed()
                val elapsed = compose.onNodeWithTag("bot.live-work-elapsed").assertIsDisplayed()
                fun secondsLabel() = elapsed.fetchSemanticsNode().config[SemanticsProperties.Text].single().text
                val initial = secondsLabel()
                compose.waitUntil(4_000) { secondsLabel() != initial }
                capture("bot-live-work")
                return
            }
            if (scenario == "mode-wave-night" || scenario == "mode-wave-light") {
                device.setRotation(if (largeDevice) landscapeRotation else portraitRotation)
                val initialOrientation = if (largeDevice) android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    else android.content.res.Configuration.ORIENTATION_PORTRAIT
                compose.waitUntil(5_000) { activity?.resources?.configuration?.orientation == initialOrientation }
                switchWorkspaceModes(
                    drawerOpened = { capture("work-entry-drawer") },
                    captureWave = ::capture,
                    beforeEnteringBot = { capture("bot-entry-drawer") },
                )
                capture("03-night-mode-restored")
                return
            }
            if (scenario == "night") {
                device.setRotation(if (largeDevice) landscapeRotation else portraitRotation)
                val initialOrientation = if (largeDevice) android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    else android.content.res.Configuration.ORIENTATION_PORTRAIT
                compose.waitUntil(5_000) { activity?.resources?.configuration?.orientation == initialOrientation }
                val wide = compose.onAllNodesWithTag("bot.wide-chats").fetchSemanticsNodes().isNotEmpty()
                if (wide) compose.onNodeWithTag("bot.chat-search").assertIsDisplayed()
                capture("01-night-home")
                compose.onNodeWithTag("bot.schedules").performScrollTo().performClick()
                compose.onNodeWithText("每日简报").assertIsDisplayed()
                capture("02-night-schedules")
                compose.onNodeWithTag("bot.schedules-close").performClick()
                compose.onNodeWithTag("bot.chief").performScrollTo().performClick()
                compose.onNodeWithTag("bot.role-panel").assertIsDisplayed()
                capture("03-night-role")
                compose.onNodeWithTag("bot.role-settings").performClick()
                compose.onNodeWithTag("bot.panel-heading").assertTextEquals("角色设置")
                capture("04-night-role-settings")
                compose.onNodeWithContentDescription("关闭").performClick()
                compose.onNodeWithTag("bot.start-chat").performScrollTo().performClick()
                compose.onNodeWithTag("bot.composer").assertIsDisplayed()
                capture("05-night-private")
                compose.onNodeWithTag("bot.chat-back").performClick()
                compose.onNodeWithTag(if (wide) "bot.wide-chats" else "bot.home")
                    .performScrollToNode(hasTestTag("bot.chat.room"))
                compose.onNodeWithTag("bot.chat.room").performClick()
                compose.onNodeWithTag("bot.message.r4").assertIsDisplayed()
                capture("06-night-group")
                if (largeDevice) {
                    device.setRotation(portraitRotation)
                    compose.waitUntil(5_000) {
                        activity?.resources?.configuration?.orientation ==
                            android.content.res.Configuration.ORIENTATION_PORTRAIT
                    }
                    compose.onNodeWithTag("bot.composer").assertIsDisplayed()
                    capture("07-night-group-portrait")
                }
                return
            }
            if (scenario == "message-quotes") {
                device.setRotation(if (largeDevice) landscapeRotation else portraitRotation)
                val initialOrientation = if (largeDevice) android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    else android.content.res.Configuration.ORIENTATION_PORTRAIT
                compose.waitUntil(5_000) { activity?.resources?.configuration?.orientation == initialOrientation }
                compose.onNodeWithTag("bot.start-chat").performScrollTo().performClick()
                val timeline = compose.onNodeWithTag("bot.timeline")
                val input = compose.onNodeWithTag("bot.composer")
                val remove = compose.onNodeWithTag("composer-reply-remove")
                fun quoteLast(messageId: String, index: Int) {
                    timeline.performScrollToIndex(index)
                    compose.onNode(hasTestTag("message-reply") and hasAnyAncestor(hasTestTag("bot.message.$messageId")))
                        .assertIsDisplayed().performClick()
                    input.assertIsFocused().performTextInput("Please continue.")
                    remove.assertIsDisplayed()
                }
                quoteLast("4", 6)
                capture("01-private-quote")
                remove.performClick()
                remove.assertDoesNotExist()
                input.assertTextContains("Please continue.")
                compose.onNodeWithTag("bot.chat-back").performClick()
                if (compose.onAllNodesWithTag("bot.wide-chats").fetchSemanticsNodes().isNotEmpty()) {
                    compose.onNodeWithTag("bot.wide-chats").performScrollToNode(hasTestTag("bot.chat.room"))
                } else {
                    compose.onNodeWithTag("bot.home").performScrollToNode(hasTestTag("bot.chat.room"))
                }
                compose.onNodeWithTag("bot.chat.room").performClick()
                quoteLast("r4", 3)
                capture("02-group-quote")
                if (largeDevice) {
                    device.setRotation(portraitRotation)
                    compose.waitUntil(5_000) {
                        activity?.resources?.configuration?.orientation ==
                            android.content.res.Configuration.ORIENTATION_PORTRAIT
                    }
                    remove.assertIsDisplayed()
                    input.assertTextContains("Please continue.")
                    capture("03-group-quote-portrait")
                }
                compose.onNodeWithContentDescription("发送消息").assertIsEnabled().performClick()
                remove.assertDoesNotExist()
                compose.onNodeWithTag("sent-reply-bubble").assertIsDisplayed()
                compose.onNodeWithTag("sent-reply-quote-0").assertTextContains("收到，结论我会整理进接入方案。")
                capture("04-group-sent-quote")
                return
            }
            if (scenario == "bubble-widths") {
                device.setRotation(if (largeDevice) landscapeRotation else portraitRotation)
                compose.onNodeWithTag("bot.start-chat").performScrollTo().performClick()
                fun bubble(id: String) = compose.onNodeWithTag("bot.bubble.$id", useUnmergedTree = true)
                    .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val shortUser = bubble("short-user")
                val shortBot = bubble("short-bot")
                val mention = bubble("mention")
                val longUser = bubble("long-user")
                val longBot = bubble("long-bot")
                check(shortUser.width < mention.width && shortUser.width < longUser.width / 2)
                check(shortBot.width < longBot.width / 2)
                if (largeDevice) {
                    val density = compose.activity.resources.displayMetrics.density
                    check(longUser.width / density > 400f) { "平板长消息不能继续使用手机气泡上限" }
                    check(longBot.width / density > 400f) { "平板 Markdown 回复应使用阅读宽度" }
                    check(longBot.width / density <= 720f)
                } else {
                    check(longUser.height > shortUser.height && longBot.height > shortBot.height)
                }
                check(kotlin.math.abs(shortUser.right - longUser.right) < 1f)
                check(kotlin.math.abs(shortBot.left - longBot.left) < 1f)
                capture("01-bubble-widths")
                if (largeDevice) {
                    device.setRotation(landscapeRotation)
                    compose.waitForIdle()
                    val density = compose.activity.resources.displayMetrics.density
                    check(bubble("long-bot").width / density > 400f)
                    check(bubble("long-bot").width / density <= 720f)
                    check(bubble("short-user").width < bubble("long-user").width / 2)
                    capture("02-bubble-widths-landscape")
                    device.setRotation(portraitRotation)
                }
                return
            }
            if (scenario == "files") {
                device.setRotation(portraitRotation)
                compose.onNodeWithTag("bot.start-chat").performScrollTo().performClick()
                val card = compose.onNode(hasText("ds-bot-research.md") and hasClickAction())
                card.assertIsDisplayed()
                compose.onNodeWithText("DS Bot 调研报告", substring = true).assertIsDisplayed()
                val cardBounds = card.fetchSemanticsNode().boundsInRoot
                val lastReply = compose.onNodeWithTag("bot.bubble.inline-file").fetchSemanticsNode().boundsInRoot
                val nextReply = compose.onNodeWithTag("bot.bubble.external-file").fetchSemanticsNode().boundsInRoot
                check(kotlin.math.abs(cardBounds.left - lastReply.left) <= 1f) { "文件卡片与 Agent 气泡左缘未对齐" }
                check(cardBounds.top >= lastReply.bottom && cardBounds.bottom < nextReply.top)
                capture("01-file-card")
                card.performClick()
                val title = compose.onNodeWithTag("workspace-preview-title")
                title.assertTextEquals("ds-bot-research.md").assertIsDisplayed()
                compose.onNodeWithTag("workspace-preview-loading").assertIsDisplayed()
                capture("02-file-preview")
                device.setRotation(landscapeRotation)
                compose.waitUntil(5_000) {
                    val orientation = activity?.resources?.configuration?.orientation
                    orientation == if (largeDevice) android.content.res.Configuration.ORIENTATION_LANDSCAPE
                        else android.content.res.Configuration.ORIENTATION_PORTRAIT
                }
                title.assertIsDisplayed()
                capture("03-file-preview-rotation")
                compose.onNodeWithContentDescription("关闭文件预览").performClick()
                card.assertIsDisplayed()
                if (largeDevice) {
                    val landscapeCard = card.fetchSemanticsNode().boundsInRoot
                    val landscapeReply = compose.onNodeWithTag("bot.bubble.inline-file").fetchSemanticsNode().boundsInRoot
                    check(kotlin.math.abs(landscapeCard.left - landscapeReply.left) <= 1f) { "横屏文件卡片与 Agent 气泡左缘未对齐" }
                    capture("04-landscape-file-card")
                }
                listOf("reply" to "ds-bot-research.md", "inline-file" to "ds-bot-research.md",
                    "external-file" to "cross-workspace.md").forEach { (id, name) ->
                    compose.onNodeWithTag("bot.bubble.$id").performTouchInput { click(percentOffset(.25f, .5f)) }
                    title.assertTextEquals(name).assertIsDisplayed()
                    compose.onNodeWithContentDescription("关闭文件预览").performClick()
                }
                return
            }
            if (scenario == "history-details") {
                device.setRotation(portraitRotation)
                compose.onNodeWithTag("bot.start-chat").performScrollTo().performClick()
                compose.onNodeWithTag("bot.message-time.4").assertIsDisplayed()
                compose.onNodeWithText("已发消息给").assertIsDisplayed()
                compose.onNodeWithText("架构和技术实现").assertIsDisplayed()
                compose.onNodeWithText("跟别的方案比").assertIsDisplayed()
                compose.onNodeWithText("B").assertIsDisplayed()
                compose.onNodeWithText("D").assertIsDisplayed()
                compose.onNodeWithText("你最关心这个项目的哪一面？").assertIsDisplayed()
                compose.onNodeWithText("已创建").assertIsDisplayed()
                capture("01-history-details")
                device.setRotation(landscapeRotation)
                compose.waitUntil(5_000) {
                    val orientation = activity?.resources?.configuration?.orientation
                    orientation == if (largeDevice) android.content.res.Configuration.ORIENTATION_LANDSCAPE
                        else android.content.res.Configuration.ORIENTATION_PORTRAIT
                }
                val timeline = compose.onNodeWithTag("bot.timeline")
                timeline.assertIsDisplayed()
                if (largeDevice) timeline.performScrollToIndex(6)
                compose.onNodeWithTag("bot.message-time.4").assertIsDisplayed()
                capture("02-history-rotation")
                return
            }
            if (scrollFixture) {
                device.setRotation(if (largeDevice) landscapeRotation else portraitRotation)
                compose.onNodeWithTag("bot.start-chat").performClick()
                val latest = compose.onNodeWithTag("bot.message.scroll-$messageCount")
                latest.assertIsDisplayed()
                if (scrollProfile) {
                    fun shell(command: String) = android.os.ParcelFileDescriptor
                        .AutoCloseInputStream(device.executeShellCommand(command)).bufferedReader().use { it.readText() }
                    val timeline = compose.onNodeWithTag("bot.timeline").fetchSemanticsNode().boundsInRoot
                    val x = timeline.center.x.toInt()
                    val top = (timeline.top + timeline.height * 0.23f).toInt()
                    val bottom = (timeline.bottom - timeline.height * 0.23f).toInt()
                    shell("dumpsys gfxinfo ${application.packageName} reset")
                    repeat(12) { index ->
                        val towardHistory = index < 6
                        val from = if (towardHistory) top else bottom
                        val to = if (towardHistory) bottom else top
                        shell("input swipe $x $from $x $to 180")
                        android.os.SystemClock.sleep(240)
                    }
                    println(shell("dumpsys gfxinfo ${application.packageName}").lineSequence()
                        .filter { it.startsWith("Total frames rendered") || it.startsWith("Janky frames:") ||
                            it.startsWith("90th percentile") || it.startsWith("95th percentile") ||
                            it.startsWith("99th percentile") }.joinToString(" | ", prefix = "BOT_SCROLL_PROFILE "))
                    return
                }
                val jump = compose.onNodeWithTag("scroll-to-bottom")
                jump.assertDoesNotExist()
                compose.onNodeWithTag("bot.timeline").performTouchInput { swipeDown() }
                compose.waitUntil(4_000) { compose.onAllNodesWithTag("scroll-to-bottom").fetchSemanticsNodes().isNotEmpty() }
                val input = compose.onNodeWithTag("bot.composer")
                check(jump.fetchSemanticsNode().boundsInRoot.bottom < input.fetchSemanticsNode().boundsInRoot.top)
                capture("26-history-jump")
                jump.performClick()
                compose.waitUntil(4_000) { compose.onAllNodesWithTag("scroll-to-bottom").fetchSemanticsNodes().isEmpty() }
                latest.assertIsDisplayed()
                capture("27-latest-jump")
                return
            }
            if (scenario == "alert") {
                compose.onNodeWithText("好").assertIsDisplayed()
                capture("01-shared-alert")
                compose.onNodeWithText("好").performClick()
                return
            }
            compose.onNodeWithTag("bot.chief").assertIsDisplayed()
            capture("01-home")
            if (scenario in listOf("images", "markdown-images", "image-error")) {
                val imageTag = if (scenario == "markdown-images") "markdown-image:./charts/plot.png" else "markdown-image:/__bot_image__/fixture-plot"
                compose.onNodeWithTag("bot.start-chat").performScrollTo().performClick()
                compose.waitUntil(10000) { compose.onAllNodesWithTag(imageTag).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag(imageTag).performScrollTo().assertIsDisplayed()
                capture("11-agent-image-bubble")
                compose.onNodeWithTag(imageTag).performClick()
                if (scenario == "image-error") {
                    compose.onNodeWithText("图片加载失败").assertIsDisplayed()
                    capture("12-agent-image-retry")
                    compose.onNodeWithText("重试").performClick()
                }
                compose.onNodeWithTag("conversation-image-preview").assertIsDisplayed()
                capture("12-agent-image-preview")
                if (largeDevice) compose.runOnIdle { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE } else device.setRotation(landscapeRotation)
                compose.waitUntil(5000) { activity?.resources?.configuration?.orientation == if (largeDevice) android.content.res.Configuration.ORIENTATION_LANDSCAPE else android.content.res.Configuration.ORIENTATION_PORTRAIT }
                capture(if (largeDevice) "13-agent-image-landscape" else "13-agent-image-portrait-locked")
                compose.onNodeWithContentDescription("关闭图片预览").performClick()
                compose.onNodeWithTag("bot.chat-back").performClick()
                if (compose.onAllNodesWithTag("bot.wide-chats").fetchSemanticsNodes().isNotEmpty()) {
                    compose.onNodeWithTag("bot.wide-chats").performScrollToNode(hasTestTag("bot.chat.room"))
                } else compose.onNodeWithTag("bot.home").performScrollToNode(hasTestTag("bot.chat.room"))
                compose.onNodeWithTag("bot.chat.room").performClick()
                compose.waitUntil(10000) { compose.onAllNodesWithTag(imageTag).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag(imageTag).performScrollTo().performClick()
                compose.onNodeWithTag("conversation-image-preview").assertIsDisplayed()
                capture("14-agent-image-group")
                compose.onNodeWithContentDescription("关闭图片预览").performClick()
                return
            }
            if (scenario in listOf("empty", "loading", "error")) {
                if (scenario == "empty") compose.onNodeWithText("连接主机后查看个人助理").assertIsDisplayed()
                compose.onNodeWithTag("bot.schedules").performScrollTo().performClick()
                val status = when (scenario) {
                    "loading" -> "正在同步助理"
                    "error" -> "同步超时，请稍后重试；发送中的消息请先核对"
                    else -> "还没有定时任务"
                }
                compose.onNodeWithTag("bot.panel-state").assertIsDisplayed()
                assertPanelFontScale()
                check(compose.onAllNodesWithText(status).fetchSemanticsNodes().isNotEmpty())
                capture("03-empty-schedules")
                compose.onNodeWithTag("bot.panel-title").performTouchInput {
                    down(center); moveBy(androidx.compose.ui.geometry.Offset(0f, 30f), delayMillis = 180)
                }
                capture("04-held-panel")
                compose.onNodeWithTag("bot.panel-title").performTouchInput { up() }
                if (largeDevice) compose.runOnIdle { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE } else device.setRotation(landscapeRotation)
                compose.waitUntil(5000) { activity?.resources?.configuration?.orientation == if (largeDevice) android.content.res.Configuration.ORIENTATION_LANDSCAPE else android.content.res.Configuration.ORIENTATION_PORTRAIT }
                android.os.SystemClock.sleep(700)
                compose.onNodeWithTag("bot.panel-state").assertIsDisplayed()
                capture("05-landscape-panel")
                compose.onNodeWithTag("bot.schedules-close").performClick()
                return
            }
            if (compose.onAllNodesWithTag("bot.wide-chats").fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithTag("bot.composer").assertDoesNotExist()
                val left = compose.onNodeWithTag("bot.chief").fetchSemanticsNode().boundsInRoot
                val searchBounds = compose.onNodeWithTag("bot.chat-search").fetchSemanticsNode().boundsInRoot
                check(searchBounds.left > left.right + 40f * compose.density.density)
                val search = compose.onNode(hasSetTextAction())
                search.performTextInput("Bot 接入讨论")
                compose.onNodeWithTag("bot.chat.room").assertIsDisplayed()
                compose.onNodeWithTag("bot.chat.researcher").assertDoesNotExist()
                compose.onNodeWithText("1 个结果").assertIsDisplayed()
                capture("08-search-name")
                compose.onNodeWithTag("search.clear").performClick()
                search.performTextInput(" 汇总 ")
                compose.onNodeWithTag("bot.chat.room").assertIsDisplayed()
                compose.onNodeWithTag("bot.chat.researcher").assertDoesNotExist()
                search.performImeAction()
                compose.onNodeWithTag("bot.chat.room").performClick()
                compose.onNodeWithTag("bot.composer").assertIsDisplayed()
                compose.onNodeWithTag("bot.chief").assertDoesNotExist()
                capture("09-independent-group")
                compose.onNodeWithTag("bot.chat-back").performClick()
                search.assertTextEquals(" 汇总 ")
                search.performClick()
                compose.onNodeWithTag("search.clear").performClick()
                search.performTextInput("会议纪要")
                compose.onNodeWithTag("bot.search-empty").assertIsDisplayed()
                compose.onNodeWithText("0 个结果").assertIsDisplayed()
                search.performImeAction()
                capture("10-search-empty")
                search.performClick()
                compose.onNodeWithTag("search.clear").performClick()
                search.performImeAction()
                compose.onNodeWithTag("bot.chat.room").assertIsDisplayed()
                compose.onNodeWithTag("bot.chat.researcher").assertIsDisplayed()
            }
            switchWorkspaceModes(
                drawerOpened = { capture("02-drawer") },
                beforeEnteringBot = { capture("bot-entry-drawer") },
            )
            compose.onNodeWithTag("bot.schedules").performScrollTo().performClick()
            compose.onNodeWithText("每日简报").assertIsDisplayed()
            assertPanelFontScale()
            capture("03-schedules")
            compose.onNodeWithTag("bot.schedules-close").performClick()
            compose.onNodeWithTag("bot.chief").performScrollTo().performClick()
            compose.onNodeWithTag("bot.role-panel").assertIsDisplayed()
            capture("03a-role-profile")
            compose.onNodeWithTag("bot.role-settings").performClick()
            compose.onNodeWithTag("bot.panel-heading").assertTextEquals("角色设置")
            val settings = compose.onNodeWithTag("bot.role-panel")
            val tagHelp = "标签为选填，会显示在 Bot 名字下方。"
            settings.performScrollToNode(hasText(tagHelp))
            compose.onNodeWithText(tagHelp).assertIsDisplayed()
            capture("03b-role-settings")
            val privacyHelp = "主 Bot 不能开启隐私。"
            settings.performScrollToNode(hasText(privacyHelp))
            compose.onNodeWithText(privacyHelp).assertIsDisplayed()
            val copyHelp = "复制分工和模型，新 Bot 从空白记忆开始。"
            settings.performScrollToNode(hasText(copyHelp))
            val copyFooter = compose.onNodeWithText(copyHelp).assertIsDisplayed().fetchSemanticsNode()
            val copyAction = compose.onNodeWithText("复制一份").fetchSemanticsNode()
            check(copyFooter.boundsInRoot.top >= copyAction.boundsInRoot.bottom) { "复制说明应位于操作卡片下方" }
            capture("03c-role-settings-help")
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithContentDescription("关闭").performClick()
            compose.onNodeWithTag("bot.start-chat").performScrollTo().performClick()
            compose.onNodeWithTag("bot.composer").assertIsDisplayed()
            check(compose.onAllNodesWithText("Chief", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
            val more = compose.onNodeWithTag("bot.chat-more").fetchSemanticsNode().boundsInRoot
            check(kotlin.math.abs(more.width - more.height) < 2) { "更多按钮应为正圆" }
            capture("04-chat")
            compose.onNodeWithTag("bot.composer").performClick().performTextInput("@")
            compose.onNodeWithTag("bot.mention.member.researcher").assertIsDisplayed().performClick()
            compose.onNodeWithTag("bot.composer").assertIsFocused().performTextInput("检查输入框")
            capture("05-keyboard")
            compose.onNodeWithTag("bot.timeline").performTouchInput { click(percentOffset(.82f, .30f)) }
            compose.onNodeWithTag("bot.composer").assertIsNotFocused().assertTextContains("@研究员 检查输入框")
            compose.waitForIdle()
            android.os.SystemClock.sleep(500)
            if (largeDevice) compose.runOnIdle { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE } else device.setRotation(landscapeRotation)
            compose.waitUntil(5000) { activity?.resources?.configuration?.orientation == if (largeDevice) android.content.res.Configuration.ORIENTATION_LANDSCAPE else android.content.res.Configuration.ORIENTATION_PORTRAIT }
            android.os.SystemClock.sleep(700)
            compose.onNodeWithTag("bot.composer").assertIsDisplayed()
            capture("06-landscape")
            if (largeDevice) compose.runOnIdle { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT } else device.setRotation(portraitRotation)
            compose.waitForIdle()
            compose.waitUntil(5000) { activity?.resources?.configuration?.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
            android.os.SystemClock.sleep(700)
            if (compose.onAllNodesWithTag("bot.chat-back").fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithTag("bot.chat-back").performClick()
            }
            if (compose.onAllNodesWithTag("bot.wide-chats").fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithTag("bot.wide-chats").performScrollToNode(hasTestTag("bot.chat.room"))
            } else compose.onNodeWithTag("bot.home").performScrollToNode(hasTestTag("bot.chat.room"))
            compose.onNodeWithTag("bot.chat.room").performClick()
            check(compose.onAllNodesWithText("研究员", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
            capture("07-group")
        } finally {
            device.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE)
            compose.runOnIdle { if (largeDevice) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED; holder.close() }
            runBlocking { graph.gatewayRuntime.disconnect() }
            graph.applicationScope.cancel(); graph.gatewayScope.cancel()
            appearancePrefs.edit().clear().commit()
            java.util.Locale.setDefault(locale)
        }
    }

    private fun switchWorkspaceModes(
        drawerOpened: () -> Unit = {},
        captureWave: ((String) -> Unit)? = null,
        beforeEnteringBot: () -> Unit,
    ) {
        val drawer = compose.onNodeWithContentDescription("打开侧边栏")
        val modeItem = compose.onNodeWithTag("drawer-bot")
        fun tapModeItem(horizontalFraction: Float, name: String) {
            if (captureWave != null) compose.mainClock.autoAdvance = false
            try {
                modeItem.performTouchInput { click(percentOffset(horizontalFraction, 0.5f)) }
                if (captureWave != null) {
                    compose.mainClock.advanceTimeBy(240)
                    captureWave(name)
                }
            } finally {
                compose.mainClock.autoAdvance = true
            }
        }
        drawer.performClick()
        compose.onNodeWithText("切换到工作模式").assertIsDisplayed()
        drawerOpened()
        tapModeItem(0.15f, "01-wave-to-standard")
        compose.onNodeWithTag("workspace-hero-title").assertIsDisplayed()

        drawer.performClick()
        compose.onNodeWithText("切换到 Bot 模式").assertIsDisplayed()
        beforeEnteringBot()
        tapModeItem(0.75f, "02-wave-to-bot")
        compose.onNodeWithTag("bot.chief").assertIsDisplayed()
    }

    private class MemoryPreferences : GatewayPreferences {
        private val value = MutableStateFlow(GatewayPreferencesSnapshot())
        override val snapshots = value
        override suspend fun load() = value.value
        override suspend fun update(snapshot: GatewayPreferencesSnapshot) { value.value = snapshot }
    }
    private class MemoryCredentials : GatewayCredentialStore {
        private val tokens = mutableMapOf<String, String>()
        private val id = UUID.randomUUID().toString()
        override suspend fun loadOrCreateDeviceId() = id
        override suspend fun loadToken(endpoint: String) = tokens[endpoint]
        override suspend fun saveToken(endpoint: String, token: String) { tokens[endpoint] = token }
        override suspend fun deleteToken(endpoint: String) { tokens.remove(endpoint) }
    }
}
