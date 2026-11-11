package com.clarklevis.dsh.android.ui

import android.util.Base64
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.clarklevis.dsh.android.platform.devicetools.AndroidConnectorPlatform
import com.clarklevis.dsh.android.platform.devicetools.AndroidDeviceConnectors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class ConnectorUiDeviceTest {
    @get:Rule val locale = ChineseDeviceLocaleRule()
    @get:Rule val compose = createComposeRule()

    @Test fun outerPageIsOneStatusItemAndDetailsShowGatewayConfirmedTools() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val frames = mutableListOf<JSONObject>()
        val platform = object : AndroidConnectorPlatform {
            override fun supported(module: String) = module == "calendar"
            override fun authorized(module: String) = supported(module)
            override fun permissions(module: String) = emptyArray<String>()
            override fun tools(module: String) = if (module == "calendar") listOf("calendar.busy") else emptyList()
            override suspend fun execute(request: JSONObject) = JSONObject()
        }
        val model = AndroidDeviceConnectors(context, "ui-" + UUID.randomUUID(), scope, platform) { frames += JSONObject(it); true }
        try {
            val appearance = AppearanceSettings(context.getSharedPreferences("connector-theme-" + UUID.randomUUID(), android.content.Context.MODE_PRIVATE)).apply {
                selectInterfaceStyle(InterfaceStyle.LIGHT)
            }
            lateinit var navigator: NavHostController
            lateinit var systemBack: androidx.activity.OnBackPressedDispatcher
            compose.setContent { DshTheme(appearance) {
                navigator = rememberNavController()
                systemBack = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
                NavHost(navigator, startDestination = CONNECTORS_ROUTE) { connectorDestinations(model, navigator) }
            } }
            compose.onNodeWithTag("connector-registration-link").assertIsDisplayed()
            val pixels = compose.onNodeWithTag("connectors-screen").captureToImage().toPixelMap()
            val background = pixels[2, pixels.height / 2]
            assertEquals(242f / 255f, background.red, 0.005f)
            assertEquals(242f / 255f, background.green, 0.005f)
            assertEquals(247f / 255f, background.blue, 0.005f)
            compose.onNodeWithTag("connector-toggle-calendar").assertWidthIsEqualTo(51.dp).assertHeightIsEqualTo(31.dp)
            compose.onNodeWithTag("connector-toggle-calendar").performClick().assertIsOn()
            java.io.File(context.cacheDir, "connectors-light.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            compose.onNodeWithTag("connector-toggle-calendar").assertIsEnabled()
            compose.onNodeWithTag("connector-toggle-health").assertDoesNotExist()
            compose.onNodeWithTag("connector-toggle-alarms").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("离线时保存在本机，连接后同步本次修改").assertDoesNotExist()
            compose.onNodeWithTag("connector-registration-link").performScrollTo().performClick()
            compose.onNodeWithTag("connector-registration-status").assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(CONNECTOR_STATUS_ROUTE, navigator.currentDestination?.route)
                assertEquals(CONNECTORS_ROUTE, navigator.previousBackStackEntry?.destination?.route)
            }
            compose.onNodeWithText("网关未连接").assertIsDisplayed()
            compose.runOnIdle { model.visibility(true); model.connection(true) }
            compose.waitUntil { frames.any { it.optString("type") == "device-tools-register" } }
            compose.runOnIdle {
                val frame = frames.last { it.optString("type") == "device-tools-register" }
                model.receive(JSONObject().put("kind", "device-tools-registered").put("version", 1).put("requestId", frame.getString("requestId"))
                    .put("capabilityRevision", frame.getInt("capabilityRevision")).put("connectionLeaseId", "ui-lease")
                    .put("permissionSync", true).put("statusQuery", true).put("permissions", JSONObject().put("calendar", true))
                    .put("modules", frame.getJSONArray("modules")).toString())
            }
            compose.onNodeWithText("网关已注册").assertIsDisplayed()
            compose.onNodeWithTag("connector-registered-module-calendar").performClick()
            compose.onNodeWithText("查询日历忙闲").assertIsDisplayed()
            compose.onNodeWithTag("connector-registered-module-calendar").performClick()
            compose.onNodeWithText("查询日历忙闲").assertDoesNotExist()
            java.io.File(context.cacheDir, "connector-status.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            compose.onNodeWithContentDescription("返回").performClick()
            compose.runOnIdle { assertEquals(CONNECTORS_ROUTE, navigator.currentDestination?.route) }
            compose.onNodeWithTag("connector-toggle-calendar").assertIsOn()
            compose.onNodeWithTag("connector-registration-link").performClick()
            compose.runOnIdle { systemBack.onBackPressed() }
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(CONNECTORS_ROUTE, navigator.currentDestination?.route) }
        } finally { scope.cancel() }
    }

    @Test fun darkPageUsesNeutralBackgroundAndWhiteSwitchThumb() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val appearance = AppearanceSettings(context.getSharedPreferences("connector-dark-" + UUID.randomUUID(), android.content.Context.MODE_PRIVATE)).apply {
            selectInterfaceStyle(InterfaceStyle.DARK)
        }
        compose.setContent { DshTheme(appearance) { ConnectorsContent(null, onBack = {}, onOpenStatus = {}) } }
        val page = compose.onNodeWithTag("connectors-screen").captureToImage().toPixelMap()
        assertEquals(0f, page[2, page.height / 2].red, 0.005f)
        val toggle = compose.onNodeWithTag("connector-toggle-calendar").captureToImage().toPixelMap()
        val thumb = toggle[(toggle.width * 0.3f).toInt(), toggle.height / 2]
        assertTrue(thumb.red > 0.95f && thumb.green > 0.95f && thumb.blue > 0.95f)
        val title = compose.onNodeWithText("连接器", substring = false).captureToImage().toPixelMap()
        assertTrue("深色页面标题必须保持可读", (0 until title.height).any { y ->
            (0 until title.width).any { x -> title[x, y].red > 0.8f && title[x, y].green > 0.8f && title[x, y].blue > 0.8f }
        })
        java.io.File(context.cacheDir, "connectors-dark.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun markdownCardsKeepSurroundingTextAndDoNotTransformCodeOrInlineLinks() {
        val metadata = Base64.encodeToString(JSONObject().put("v", 1).put("title", "测试歌曲").put("artist", "测试歌手").put("durationSeconds", 185).toString().toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val music = "whaleness-music://local/songs/42#dsh-music-card=$metadata"
        compose.setContent {
            DshTheme { DshMenuHost {
                Column(Modifier.width(340.dp)) {
                    DshMarkdownText("前面的说明。\n\n[测试歌曲]($music)\n\n[测试地点](https://www.google.com/maps/search/?api=1&query=31.2%2C121.5)\n\n后面的说明。")
                }
            } }
        }
        compose.onNodeWithTag("music-app-card").assertIsDisplayed()
        compose.onNodeWithTag("maps-app-card").assertIsDisplayed()
        compose.onNodeWithText("测试歌手", substring = true).assertIsDisplayed()
        compose.onNodeWithText("3:05", substring = true).assertIsDisplayed()
        val screenshot = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "connector-cards.png")
        screenshot.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        // 原生 TextView 用 Espresso 检查，Compose 只验证 Compose 卡片节点。
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("前面的说明。"))
            .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("后面的说明。"))
            .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
    }

    @Test fun inlineAndCodeLinksRemainText() {
        compose.setContent { DshTheme { DshMenuHost {
            DshMarkdownText("行内 [歌曲](https://music.163.com/song?id=123) 保持文字。\n\n`[歌曲](https://music.163.com/song?id=123)`\n\n[![封面](cover.png)](https://music.163.com/song?id=123)")
        } } }
        compose.onNodeWithTag("music-app-card").assertDoesNotExist()
    }

    @Test fun cardsAcceptOnlyKnownResourcesAndBoundedMetadata() {
        assertNotNull(connectorAppCard("https://music.apple.com/cn/album/test/123?i=456", "Song"))
        assertNotNull(connectorAppCard("https://music.163.com/song?id=123", "Song"))
        assertNotNull(connectorAppCard("https://music.163.com/#/playlist?id=123", "List"))
        assertNotNull(connectorAppCard("https://y.qq.com/n/ryqq/songDetail/001ABC", "Song"))
        assertNotNull(connectorAppCard("https://maps.apple.com/?ll=31.2,121.5&q=Place", "Place"))
        assertNull(connectorAppCard("https://music.apple.com.evil.test/song/123", "Bad"))
        assertNull(connectorAppCard("https://user@music.apple.com/song/123", "Bad"))
        assertNull(connectorAppCard("intent://evil#Intent;component=anything;end", "Bad"))
        assertNull(connectorAppCard("file:///sdcard/music.mp3", "Bad"))
        assertNull(connectorAppCard("whaleness-music://local/../../private", "Bad"))
        assertNull(connectorAppCard("https://maps.apple.com/?ll=NaN,1", "Bad"))
        assertNull(connectorAppCard("https://www.google.com/maps/search/?api=1&query=91,1", "Bad"))
        assertNull(connectorAppCard("https://maps.apple.com/?ll=1,2&ll=3,4", "Bad"))
        assertFalse(validConnectorArtwork("https://private.test/image.jpg", false))
        assertFalse(validConnectorArtwork("content://contacts/people/1", true))
        assertFalse(validConnectorArtwork("content://media/external/audio/albumart/42", false))
        assertTrue(validConnectorArtwork("content://media/external/audio/albumart/42", true))
    }
}
