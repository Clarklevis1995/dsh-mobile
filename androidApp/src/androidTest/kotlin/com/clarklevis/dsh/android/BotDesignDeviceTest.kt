package com.clarklevis.dsh.android

import android.app.Activity
import android.app.Application
import android.content.pm.ActivityInfo
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
        fun assertPanelFontScale() {
            if ((args.getString("botFontScale")?.toFloatOrNull() ?: 1f) >= 1.5f) {
                val height = compose.onNodeWithTag("bot.panel-heading").fetchSemanticsNode().boundsInRoot.height
                check(height >= 28f * 1.35f * compose.density.density) { "独立面板必须继承应用字号，实际标题高度 $height" }
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
        if (scenario == "night" || scenario == "mode-wave-night") appearance.selectInterfaceStyle(InterfaceStyle.DARK)
        val glassApi = args.getString("botGlassApi")?.toIntOrNull() ?: android.os.Build.VERSION.SDK_INT
        val suffix = when (scenario) {
            "empty", "loading", "error" -> "-empty"
            "long", "markdown-images", "files", "bubble-widths" -> "-$scenario"
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
        } else baseFixture
        var activity: Activity? = null
        try {
            compose.runOnIdle { holder.restoreBotDesignPreview(fixture, connected = scenario != "empty", errorCode = if (scenario == "error") "bot-timeout" else null); holder.botDesignImageFailOnce = scenario == "image-error" }
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
                var bot by remember { mutableStateOf(true) }
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
            if (scenario == "mode-wave-night") {
                device.setRotation(if (largeDevice) landscapeRotation else portraitRotation)
                val initialOrientation = if (largeDevice) android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    else android.content.res.Configuration.ORIENTATION_PORTRAIT
                compose.waitUntil(5_000) { activity?.resources?.configuration?.orientation == initialOrientation }
                switchWorkspaceModes(
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
                compose.onNodeWithContentDescription("完成").performClick()
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
                check(longUser.height > shortUser.height && longBot.height > shortBot.height)
                check(kotlin.math.abs(shortUser.right - longUser.right) < 1f)
                check(kotlin.math.abs(shortBot.left - longBot.left) < 1f)
                capture("01-bubble-widths")
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
                compose.onNodeWithContentDescription("完成").performClick()
                return
            }
            if (compose.onAllNodesWithTag("bot.chat-search").fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithTag("bot.composer").assertDoesNotExist()
                val left = compose.onNodeWithTag("bot.chief").fetchSemanticsNode().boundsInRoot
                val searchBounds = compose.onNodeWithTag("bot.chat-search").fetchSemanticsNode().boundsInRoot
                check(searchBounds.left > left.right + 40f * compose.density.density)
                val search = compose.onNode(hasSetTextAction())
                search.performTextInput("研究员")
                compose.onNodeWithTag("bot.chat.researcher").assertIsDisplayed()
                compose.onNodeWithTag("bot.chat.room").assertDoesNotExist()
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
            compose.onNodeWithContentDescription("完成").performClick()
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
