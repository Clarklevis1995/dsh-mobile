package com.clarklevis.dsh.android.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import com.clarklevis.dsh.shared.tailscale.TailscaleAccount
import com.clarklevis.dsh.shared.tailscale.TailscaleAddition
import com.clarklevis.dsh.shared.tailscale.TailscaleNode
import com.clarklevis.dsh.shared.tailscale.TailscaleSessionManager
import com.clarklevis.dsh.shared.tailscale.TailscaleStage
import com.clarklevis.dsh.shared.tailscale.TailscaleStatus
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TailscaleTunnelDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tappingAccountNeedingLoginReusesURLDialogAndExistingSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("tunnel-reauth-device-test", Context.MODE_PRIVATE)
        val locale = Locale.getDefault()
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        prefs.edit().clear().commit()
        val url = "https://login.tailscale.com/a/existing"
        var response = """{"state":"NeedsLogin","authURL":"$url"}"""
        var starts = 0
        var closes = 0
        var opened: String? = null
        lateinit var manager: TailscaleSessionManager
        lateinit var settings: TailscaleTunnelSettings
        val node = object : TailscaleNode {
            override suspend fun begin(authKey: String, hostname: String): String {
                starts++
                return response
            }
            override suspend fun status() = response
            override suspend fun close(): Boolean {
                closes++
                return true
            }
        }
        try {
            compose.setContent {
                val scope = rememberCoroutineScope()
                settings = remember {
                    TailscaleTunnelSettings(prefs).apply {
                        saveAuthenticatedAccount(
                            TailscaleAccount("00000000-0000-0000-0000-000000000003", name = "待登录账号"),
                            null,
                            null
                        )
                        selectEnabled(true)
                    }
                }
                manager = remember {
                    TailscaleSessionManager(scope, { node }, { account, status ->
                        settings.saveAuthenticatedAccount(account, status.loginName, status.tailnetName)
                    }, {}, {})
                }
                val state by manager.state.collectAsState()
                val addition by manager.addition.collectAsState()
                LaunchedEffect(manager) { manager.start(settings.selectedAccount, "", "dsh-existing") }
                DisposableEffect(manager) { onDispose { manager.stop() } }
                DshTheme(tunnelSettings = settings) {
                    TailscaleTunnelContent(
                        settings, state, addition, onBack = {}, onEnabled = {}, onAccount = { manager.promptCurrentLogin() },
                        onAdd = { error("已有账号登录不应添加新账号") }, onCancel = manager::cancelAddition,
                        onConfirm = { opened = manager.confirmAddition() }, onRetry = {}, onOpen = {}, onCopy = {}, onDelete = {}
                    )
                }
            }
            compose.onNodeWithText("需要登录").assertIsDisplayed()
            compose.onNodeWithText("待登录账号").performClick()
            compose.onNodeWithText("登录 Tailscale 账号").assertIsDisplayed()
            compose.onNodeWithTag("tunnel-login-url").assertTextEquals(url)
            capture(context, "tailscale-android-existing-login")
            compose.onNodeWithTag("tunnel-login-cancel").performClick()
            compose.runOnIdle {
                assertEquals(1, starts)
                assertEquals(0, closes)
            }
            compose.onNodeWithText("待登录账号").performClick()
            compose.onNodeWithTag("tunnel-login-confirm").performClick()
            compose.runOnIdle {
                assertEquals(url, opened)
                response = """{"state":"Running","peers":1,"routesReady":true}"""
                manager.refresh()
            }
            compose.onNodeWithText("已连接").assertIsDisplayed()
            compose.onNodeWithTag("tunnel-login-dialog").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(1, starts)
                assertEquals(0, closes)
                assertEquals(1, settings.accounts.size)
            }
        } finally {
            prefs.edit().clear().commit()
            Locale.setDefault(locale)
        }
    }

    @Test fun savedAccountsAndLiveLoginDialogMatchIOSFlow() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val locale = Locale.getDefault()
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        val prefs = context.getSharedPreferences("tunnel-device-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        try {
            lateinit var settings: TailscaleTunnelSettings
            val addition = mutableStateOf<TailscaleAddition?>(null)
            val active =
                TailscaleStatus(
                    stage = TailscaleStage.READY,
                    peerCount = 5,
                    nodeAddress = "100.96.53.9",
                    backendRunning = true,
                    routesReady = true
                )
            val candidate = TailscaleAccount("00000000-0000-0000-0000-000000000002", nodeName = "dsh-new")
            val url = "https://login.tailscale.com/a/7bcb114017a83"
            var copied: String? = null
            var opened: String? = null
            var deleteCalls = 0
            compose.setContent {
                settings = androidx.compose.runtime.remember {
                    TailscaleTunnelSettings(prefs).apply {
                        saveAuthenticatedAccount(TailscaleAccount("00000000-0000-0000-0000-000000000001"), "1321520426@qq.com", null)
                        selectEnabled(true)
                    }
                }
                val appearance = androidx.compose.runtime.remember {
                    val arguments = InstrumentationRegistry.getArguments()
                    AppearanceSettings(context.getSharedPreferences("tunnel-device-appearance", Context.MODE_PRIVATE)).apply {
                        selectInterfaceStyle(
                            if (arguments.getString("tailscaleDark") ==
                                "true"
                            ) {
                                InterfaceStyle.DARK
                            } else {
                                InterfaceStyle.LIGHT
                            }
                        )
                        selectFontFollowsSystem(false)
                        selectFontScale(arguments.getString("tailscaleFontScale")?.toFloatOrNull() ?: 1f)
                    }
                }
                DshTheme(appearance, settings) {
                    TailscaleTunnelContent(
                        settings, active, addition.value,
                        onBack = {}, onEnabled = settings::selectEnabled, onAccount = { settings.selectAccount(it) },
                        onAdd = { addition.value = TailscaleAddition(candidate) }, onCancel = { addition.value = null },
                        onConfirm = {
                            opened = addition.value?.loginURL
                            addition.value = addition.value?.copy(confirmed = true)
                        },
                        onRetry = {}, onOpen = { opened = it }, onCopy = { copied = it },
                        onDelete = {
                            deleteCalls++
                            settings.removeAccount(it)
                        }
                    )
                }
            }
            compose.onNodeWithText("1321520426@qq.com").assertIsDisplayed()
            compose.onNodeWithText("待登录账号").assertDoesNotExist()
            compose.onNodeWithTag("tunnel-status-row").performScrollTo().assertIsDisplayed()
            capture(context, "tailscale-android-accounts")
            compose.onNodeWithTag("tunnel-add-account").performClick()
            compose.onNodeWithTag("tunnel-login-confirm").assertIsNotEnabled()
            compose.onNodeWithTag("tunnel-login-url").assertDoesNotExist()
            compose.onNodeWithText("正在获取登录链接…").assertIsDisplayed()
            compose.runOnIdle { addition.value = addition.value?.copy(loginURL = url) }
            compose.onNodeWithTag("tunnel-login-confirm").assertIsEnabled()
            compose.onNodeWithTag("tunnel-login-url").assertTextEquals(url)
            capture(context, "tailscale-android-login")
            compose.onNodeWithTag("tunnel-login-copy").performClick()
            compose.runOnIdle { assertEquals(url, copied) }
            compose.onNodeWithText("复制").assertIsDisplayed()
            compose.onNodeWithTag("tunnel-login-cancel").performClick()
            compose.onNodeWithTag("tunnel-login-dialog").assertDoesNotExist()
            compose.runOnIdle {
                assertTrue(settings.enabled)
                assertEquals(1, settings.accounts.size)
            }
            compose.onNodeWithText("已连接").assertIsDisplayed()
            compose.onNodeWithTag("tunnel-add-account").performClick()
            compose.runOnIdle { addition.value = addition.value?.copy(loginURL = url) }
            compose.onNodeWithTag("tunnel-login-confirm").performClick()
            compose.runOnIdle {
                assertEquals(url, opened)
                assertEquals(1, settings.accounts.size)
            }
            compose.onNodeWithTag("tunnel-login-dialog").assertDoesNotExist()
            compose.onNodeWithText("等待新账号登录…").assertIsDisplayed()
            compose.onNodeWithText("已连接").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("取消添加").performScrollTo().performClick()
            compose.onNodeWithText("1321520426@qq.com").performScrollTo().performTouchInput { longClick() }
            capture(context, "tailscale-android-menu")
            compose.onNodeWithText("重命名").performClick()
            compose.onNodeWithTag("tunnel-account-editor").assertIsDisplayed()
            compose.onNodeWithTag("tunnel-account-name").performClick().performTextReplacement("未保存的备注")
            compose.runOnIdle { assertEquals("", settings.selectedAccount.name) }
            compose.onNodeWithTag("tunnel-account-cancel").performClick()
            compose.onNodeWithTag("tunnel-account-editor").assertDoesNotExist()
            compose.onNodeWithText("1321520426@qq.com").assertIsDisplayed()
            compose.onNodeWithText("1321520426@qq.com").performTouchInput { longClick() }
            compose.onNodeWithText("重命名").performClick()
            capture(context, "tailscale-android-rename")
            compose.onNodeWithTag("tunnel-account-name").performClick().performTextReplacement("工作账号")
            compose.onNodeWithTag("tunnel-account-save").assertTextEquals("确定").performClick()
            compose.onNodeWithText("工作账号").assertIsDisplayed()
            compose.runOnIdle { assertEquals("工作账号", settings.selectedAccount.name) }
            compose.onNodeWithText("工作账号").performTouchInput { longClick() }
            compose.onNodeWithText("删除账号").performClick()
            compose.onNodeWithTag("tunnel-delete-dialog").assertIsDisplayed()
            assertDeleteSurfaceAppearance()
            capture(context, "tailscale-android-delete")
            compose.runOnIdle {
                assertEquals(0, deleteCalls)
                assertEquals(1, settings.accounts.size)
                assertTrue(settings.enabled)
            }
            compose.onNodeWithTag("tunnel-delete-cancel").performClick()
            compose.onNodeWithTag("tunnel-delete-dialog").assertDoesNotExist()
            compose.onNodeWithText("工作账号").assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(0, deleteCalls)
                assertTrue(settings.enabled)
            }
            compose.onNodeWithText("工作账号").performTouchInput { longClick() }
            compose.onNodeWithText("删除账号").performClick()
            compose.onNodeWithTag("tunnel-delete-confirm").performClick()
            compose.onNodeWithText("工作账号").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(1, deleteCalls)
                assertTrue(settings.accounts.isEmpty())
                assertTrue(!settings.enabled)
                val restored = TailscaleTunnelSettings(prefs)
                assertTrue(restored.accounts.isEmpty())
                assertTrue(!restored.shouldAutoStart())
                assertTrue(!restored.selectedAccount.usesLegacyState)
            }
        } finally {
            prefs.edit().clear().commit()
            Locale.setDefault(locale)
        }
    }

    private fun capture(context: Context, name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        android.os.SystemClock.sleep(300)
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val suffix = if (InstrumentationRegistry.getArguments().getString("tailscaleDark") == "true") "-dark-large" else ""
        File(context.getExternalFilesDir(null), "$name$suffix.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun assertDeleteSurfaceAppearance() {
        val pixels = compose.onNodeWithTag("tunnel-delete-dialog").captureToImage().toPixelMap()
        val brightness = pixels[pixels.width / 2, 10].luminance()
        val dark = InstrumentationRegistry.getArguments().getString("tailscaleDark") == "true"
        assertTrue("删除确认背景必须跟随应用外观", if (dark) brightness < .2f else brightness > .6f)
    }
}
