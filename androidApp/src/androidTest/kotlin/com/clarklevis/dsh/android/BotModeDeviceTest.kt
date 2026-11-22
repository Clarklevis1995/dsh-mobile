package com.clarklevis.dsh.android

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Application
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clarklevis.dsh.android.ui.BotModeScreen
import com.clarklevis.dsh.android.ui.DshTheme
import com.clarklevis.dsh.shared.platform.GatewayCredentialStore
import com.clarklevis.dsh.shared.platform.GatewayPreferences
import com.clarklevis.dsh.shared.platform.GatewayPreferencesSnapshot
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

internal fun withBotSoftwareKeyboard(test: (() -> Rect?) -> Unit) {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    fun shell(command: String) =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader().use { it.readText().trim() }
    fun imeBounds() = automation.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        ?.let { window -> Rect().also(window::getBoundsInScreen) }
    val originalImeSetting = shell("settings get secure show_ime_with_hard_keyboard")
    val originalFlags = automation.serviceInfo.flags
    try {
        shell("settings put secure show_ime_with_hard_keyboard 1")
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        test(::imeBounds)
    } finally {
        automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
        shell(if (originalImeSetting == "null") "settings delete secure show_ime_with_hard_keyboard"
            else "settings put secure show_ime_with_hard_keyboard $originalImeSetting")
    }
}

/** 只接受明确指定的本机隔离主机；不读写用户的连接偏好或凭证。 */
@RunWith(AndroidJUnit4::class)
class BotModeDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun realHostHomeScheduleChatSendAndReconnect() = withHost { holder, capture, args ->
        verifyConversation(holder, capture, args)
    }

    @Test
    fun realHostRoleSettingsDismissesKeyboardOnOutsideTap() = withHost(animatedHome = false) { _, capture, _ ->
        withSoftwareKeyboard { imeVisible ->
            compose.onNode(hasTestTag("bot.chief")).performClick()
            compose.onNode(hasTestTag("bot.role-settings")).performClick()
            val form = compose.onNode(hasTestTag("bot.role-panel"))
            val editorLabel = hasContentDescription("说明")
            form.performScrollToNode(editorLabel)
            val editor = compose.onNode(editorLabel).assertIsEnabled()
            editor.performTouchInput { click() }
            compose.waitUntil(5_000) { imeVisible() }
            val draft = "Focus draft"
            editor.performTextInput(draft)
            editor.assertIsFocused()
            capture("role-description-editing")

            compose.onNode(hasText("说明")).performTouchInput { click() }
            compose.waitUntil(5_000) { !imeVisible() }
            editor.assertIsNotFocused().assertTextContains(draft, substring = true)
            capture("role-description-dismissed")

            editor.performTouchInput { click() }
            compose.waitUntil(5_000) { imeVisible() }
            editor.assertIsFocused().assertTextContains(draft, substring = true)
        }
    }

    @Test
    fun realHostLongMemoryEditor() = withHost(animatedHome = false) { _, capture, _ ->
        withSoftwareKeyboard { imeVisible ->
            compose.onNode(hasTestTag("bot.chief")).performClick()
            val form = compose.onNode(hasTestTag("bot.role-panel"))
            val memory = hasText("记忆")
            form.performScrollToNode(memory)
            compose.onNode(memory).performClick()

            val entry = hasText("Long memory preview.", substring = true)
            form.performScrollToNode(entry)
            compose.onNode(entry).performClick()
            val editor = compose.onNode(hasTestTag("bot.memory-editor"))
            val save = compose.onNode(hasTestTag("bot.memory-save"))
            editor.assertHeightIsAtLeast(160.dp)
                .assertTextContains("End of memory.", substring = true)
            capture("memory-editor")

            editor.performTouchInput { click() }
            compose.waitUntil(5_000) { imeVisible() }
            save.assertIsDisplayed()
            capture("memory-editor-keyboard")
            compose.onNode(hasContentDescription("返回")).performClick()
            compose.waitUntil(5_000) { !imeVisible() }

            compose.onNode(hasTestTag("bot.role-memory-add")).performClick()
            save.assertIsNotEnabled()
            val draft = "Memory editor proof " + UUID.randomUUID().toString().take(8)
            editor.performTextInput(draft)
            save.assertIsEnabled().performClick()
            val addedEntry = hasText(draft)
            compose.waitUntil(8_000) {
                runCatching { form.performScrollToNode(addedEntry) }.isSuccess
            }
            compose.onNode(addedEntry).assertIsDisplayed()
        }
    }

    private fun withSoftwareKeyboard(test: (() -> Boolean) -> Unit) =
        withBotSoftwareKeyboard { bounds -> test { bounds() != null } }

    @Test
    fun realHostDeleteBot() = withHost(animatedHome = false) { holder, capture, _ ->
        val form = compose.onNode(hasTestTag("bot.role-panel"))
        val header = hasAnyAncestor(hasTestTag("bot.panel-title"))
        val dialog = hasAnyAncestor(isDialog()) and !hasAnyAncestor(hasTestTag("bot.role-panel"))
        val delete = hasTestTag("bot.role-delete")
        val duplicate = hasText("复制一份")
        val chief = hasText("Chief")
        val copy = hasText("Chief copy")
        val settings = hasTestTag("bot.role-settings")

        compose.onNode(hasTestTag("bot.roster")).performClick()
        compose.onNode(copy).assertDoesNotExist()
        compose.onNode(chief).performClick()
        compose.onNode(settings).performClick()
        form.performScrollToNode(duplicate)
        compose.onNode(delete).assertDoesNotExist()
        compose.onNode(duplicate).performClick()
        compose.onNode(duplicate and dialog).performClick()
        compose.waitUntil(12_000) { holder.botSnapshot.roster.any { it.name == "Chief copy" } }
        compose.onNode(hasContentDescription("返回") and header).performClick()
        compose.onNode(hasContentDescription("关闭") and header).performClick()

        compose.onNode(copy).performClick()
        compose.onNode(settings).performClick()
        form.performScrollToNode(delete)
        capture("bot-delete-row")
        compose.onNode(delete).performClick()
        compose.onNode(hasText("将“Chief copy”移出团队和所在群聊。") and dialog).assertIsDisplayed()
        capture("bot-delete-confirm")
        compose.onNode(hasText("取消") and dialog).performClick()
        compose.onNode(delete).assertIsDisplayed()

        compose.onNode(delete).performClick()
        compose.onNode(hasText("删除") and dialog).performClick()
        compose.waitUntil(12_000) { holder.botSnapshot.roster.none { it.name == "Chief copy" } }
        compose.onNode(copy).assertDoesNotExist()
        compose.onNode(chief).assertIsDisplayed()
    }

    @Test
    fun realHostBotCreation() = withHost(animatedHome = false) { holder, capture, _ ->
        compose.onNode(hasTestTag("bot.schedules")).assertIsDisplayed()
        capture("01-home")
        compose.onNode(hasTestTag("bot.roster")).performClick()
        compose.onNode(hasTestTag("bot.new")).assertIsDisplayed()
        capture("02-roster")
        compose.onNode(hasTestTag("bot.new")).performClick()
        compose.waitUntil(12_000) { holder.botSnapshot.creation.appearance != null }
        capture("03-create")
        compose.onNode(hasTestTag("bot.create.appearance")).performClick()
        compose.onNode(hasText("头像库")).performClick()
        compose.waitUntil(8_000) { !holder.botSnapshot.creation.loadingPhotos }
        compose.onNode(hasText("导入照片")).assertIsDisplayed()
        if (holder.botSnapshot.creation.photos.any { it.name == "avatar-test.png" }) {
            compose.onNode(hasContentDescription("avatar-test.png")).performClick()
        } else {
            compose.onNode(hasText("取消")).performClick()
        }
        compose.onNode(hasContentDescription("star")).performClick()
        compose.onNode(hasContentDescription("violet")).performClick()
        capture("04-appearance")
        compose.onNode(hasTestTag("bot.appearance.done")).performClick()
        compose.onNode(hasText("本地 Agent")).performClick()
        compose.onNode(hasTestTag("bot.create.submit")).assertIsNotEnabled()
        compose.waitUntil(8_000) { !holder.botSnapshot.creation.loadingAgents }
        capture("05-local-agent")
        holder.botSnapshot.creation.agents.firstOrNull { it.available }?.let { agent ->
            compose.onNode(hasTestTag("bot.create.agent." + agent.id)).performClick()
            compose.onNode(hasTestTag("bot.create.submit")).assertIsEnabled()
            compose.onNode(hasTestTag("bot.create.model")).performClick()
            compose.onNode(hasText("可用模型")).assertIsDisplayed()
            capture("05b-local-models")
            compose.onNode(hasText("取消")).performClick()
        }
        compose.onNode(hasText("DS Bot 模型")).performClick()
        val name = "Android " + UUID.randomUUID().toString().take(8)
        compose.onNode(hasTestTag("bot.create.name")).performTextInput(name)
        compose.onNode(hasTestTag("bot.create.submit")).performClick()
        compose.waitUntil(20_000) { holder.botSnapshot.roster.any { it.name == name } }
        compose.onNode(hasText(name)).assertIsDisplayed()
        capture("06-created")
    }

    private fun withHost(animatedHome: Boolean = true, test: (AndroidSharedStateHolder, (String) -> Unit, android.os.Bundle) -> Unit) {
        val args = InstrumentationRegistry.getArguments()
        val captureLabel = args.getString("botCaptureLabel")?.takeIf { it.matches(Regex("[a-zA-Z0-9-]{1,64}")) } ?: "latest"
        val captureDirectory = java.io.File(ApplicationProvider.getApplicationContext<Application>().getExternalFilesDir(null), "bot-acceptance/$captureLabel")
        captureDirectory.mkdirs()
        fun capture(name: String) {
            compose.waitForIdle()
            android.os.SystemClock.sleep(250)
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            java.io.File(captureDirectory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val previousLocale = java.util.Locale.getDefault()
        assumeTrue(args.getString("botProbeUrl") == "http://127.0.0.1:3098")
        java.util.Locale.setDefault(java.util.Locale.SIMPLIFIED_CHINESE)
        val connection = URI("http://127.0.0.1:3098/mgw/pair").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        connection.outputStream.use { it.write("{}".toByteArray()) }
        val qr = connection.inputStream.bufferedReader().use { JSONObject(it.readText()).getString("qrPayload") }
        val hostTimeOffset = connection.date - System.currentTimeMillis()
        connection.disconnect()
        val graph = AndroidAppGraph(
            ApplicationProvider.getApplicationContext<Application>(), preferencesOverride = MemoryPreferences(),
            networkMonitorOverride = object : com.clarklevis.dsh.shared.platform.GatewayNetworkMonitor {
                override val state = MutableStateFlow(com.clarklevis.dsh.shared.platform.GatewayNetworkState.AVAILABLE)
            },
            clockOverride = object : com.clarklevis.dsh.shared.platform.GatewayClock {
                override fun nowEpochMilliseconds() = System.currentTimeMillis() + hostTimeOffset
                override suspend fun delay(milliseconds: Long) = kotlinx.coroutines.delay(milliseconds)
            },
            credentialStoreOverride = MemoryCredentials(), gatewayLocalId = "bot-test-${UUID.randomUUID()}",
            storedConnectionPrerequisite = {}
        )
        val appearancePreferences = graph.application.getSharedPreferences("bot-test-appearance-${UUID.randomUUID()}", android.content.Context.MODE_PRIVATE)
        val appearance = com.clarklevis.dsh.android.ui.AppearanceSettings(appearancePreferences)
        appearance.selectFontScale(args.getString("botFontScale")?.toFloatOrNull() ?: 1f)
        if (args.getString("botDark") == "1") appearance.selectInterfaceStyle(com.clarklevis.dsh.android.ui.InterfaceStyle.DARK)
        val holder = graph.stateHolder
        try {
            compose.setContent {
                val view = androidx.compose.ui.platform.LocalView.current
                androidx.compose.runtime.SideEffect {
                    (view.context as? android.app.Activity)?.window?.let {
                        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(it, false)
                        it.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                    }
                }
                DshTheme(appearance = appearance) { BotModeScreen(holder, onStandardMode = {}, scrollEnabled = animatedHome) }
            }
            compose.runOnIdle { holder.pair(qr) }
            compose.waitUntil(20_000) { (holder.botSnapshot.chats.isNotEmpty() && holder.botSnapshot.feedActive) || holder.platformError != null }
            org.junit.Assert.assertNull(holder.platformError)
            compose.onNode(hasTestTag("bot.chief")).assertIsDisplayed()
            test(holder, ::capture, args)
        } finally {
            java.util.Locale.setDefault(previousLocale)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { holder.close() }
            runBlocking { graph.gatewayRuntime.disconnect() }
            graph.applicationScope.cancel()
            graph.gatewayScope.cancel()
            appearancePreferences.edit().clear().commit()
        }
    }

    private fun verifyConversation(holder: AndroidSharedStateHolder, capture: (String) -> Unit, args: android.os.Bundle) {
        capture("01-home")
        if (args.getString("botHoldAt") == "home") Thread.sleep(45_000)
        compose.onNode(hasTestTag("bot.schedules")).performClick()
        compose.onNode(hasText("还没有定时任务")).assertIsDisplayed()
        capture("02-schedules")
        val display = ApplicationProvider.getApplicationContext<Application>().resources.configuration
        if (display.smallestScreenWidthDp < 600) {
            compose.onNode(hasTestTag("bot.panel-title")).performTouchInput { down(center); moveBy(androidx.compose.ui.geometry.Offset(0f, 140f), delayMillis = 180) }
            android.os.SystemClock.sleep(200)
            capture("03-schedules-held-drag")
            compose.onNode(hasTestTag("bot.panel-title")).performTouchInput { up() }
            compose.waitForIdle()
        }
        if (args.getString("botHoldAt") == "schedule") Thread.sleep(45_000)
        compose.onNode(androidx.compose.ui.test.hasContentDescription("完成")).performClick()
        compose.onNode(hasTestTag("bot.start-chat")).performClick()
        compose.waitUntil(10_000) { holder.botSnapshot.messages.any { "本地验证模型" in it.text } }
        capture("04-chat")
        val prompt = "Android Bot UI validation ${UUID.randomUUID().toString().take(8)}"
        compose.onNode(hasTestTag("bot.composer")).assertIsDisplayed().performTextInput(prompt)
        capture("05-keyboard")
        compose.onNode(androidx.compose.ui.test.hasContentDescription("发送消息")).performClick()
        compose.waitUntil(15_000) { holder.botSnapshot.messages.any { it.text == prompt && it.delivery == null } }
        compose.waitUntil(15_000) {
            val sentIndex = holder.botSnapshot.messages.indexOfFirst { it.text == prompt && it.delivery == null }
            sentIndex >= 0 && holder.botSnapshot.messages.drop(sentIndex + 1).any { it.kind == "bot" && !it.streaming }
        }
        compose.runOnIdle { holder.disconnect() }
        compose.waitUntil(8_000) { !holder.botSnapshot.connected }
        compose.onNode(hasTestTag("bot.composer")).assertIsDisplayed()
        capture("06-disconnected-cache")
        compose.runOnIdle { holder.connect() }
        compose.waitUntil(15_000) { holder.botSnapshot.feedActive }
        capture("07-reconnected")
        // 给 CLI 截图和边界检查保留界面，只有显式参数才停留。
        if (args.getString("botHold") == "1") Thread.sleep(45_000)
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
