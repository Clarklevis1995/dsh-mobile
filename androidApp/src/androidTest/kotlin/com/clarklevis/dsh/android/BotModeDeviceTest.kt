package com.clarklevis.dsh.android

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextInput
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

/** 只接受明确指定的本机隔离主机；不读写用户的连接偏好或凭证。 */
@RunWith(AndroidJUnit4::class)
class BotModeDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun realHostHomeScheduleChatSendAndReconnect() {
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
                DshTheme(appearance = appearance) { BotModeScreen(holder, onStandardMode = {}) }
            }
            compose.runOnIdle { holder.pair(qr) }
            compose.waitUntil(20_000) { (holder.botSnapshot.chats.isNotEmpty() && holder.botSnapshot.feedActive) || holder.platformError != null }
            org.junit.Assert.assertNull(holder.platformError)
            compose.onNode(hasTestTag("bot.chief")).assertIsDisplayed()
            capture("01-home")
            if (args.getString("botHoldAt") == "home") Thread.sleep(45_000)
            compose.onNode(hasTestTag("bot.schedules")).performClick()
            compose.onNode(hasText("还没有定时任务")).assertIsDisplayed()
            capture("02-schedules")
            val display = graph.application.resources.configuration
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
        } finally {
            java.util.Locale.setDefault(previousLocale)
            compose.runOnIdle { holder.close() }
            runBlocking { graph.gatewayRuntime.disconnect() }
            graph.applicationScope.cancel()
            graph.gatewayScope.cancel()
            appearancePreferences.edit().clear().commit()
        }
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
