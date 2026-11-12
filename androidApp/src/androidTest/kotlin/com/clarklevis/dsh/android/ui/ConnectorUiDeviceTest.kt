package com.clarklevis.dsh.android.ui

import android.util.Base64
import androidx.compose.foundation.background
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
            compose.onNodeWithText("音乐播放控制").assertDoesNotExist()
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
            compose.onNodeWithTag("connector-registered-module-calendar").assertDoesNotExist()
            compose.onNodeWithTag("connector-registered-capabilities").performClick()
            compose.onNodeWithTag("connector-capabilities-sheet").assertIsDisplayed()
            compose.onNodeWithTag("connector-registered-module-calendar").performClick()
            compose.onNodeWithText("查询日历忙闲").assertIsDisplayed()
            compose.onNodeWithTag("connector-capabilities-back").performClick()
            compose.onNodeWithText("查询日历忙闲").assertDoesNotExist()
            compose.onNodeWithTag("connector-capabilities-close").performClick()
            compose.onNodeWithTag("connector-capabilities-sheet").assertDoesNotExist()
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

    @Test fun registeredSheetUsesPlatformLogosLivePermissionsAndIndependentScrolling() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val frames = mutableListOf<JSONObject>()
        val tools = mapOf(
            "calendar" to listOf("calendar.busy", "calendar.create"),
            "reminders" to listOf("reminders.list", "reminders.create"),
            "location" to listOf("location.current", "location.precise", "maps.geocode", "maps.reverse-geocode", "maps.open", "maps.markers.create", "maps.markers.list", "maps.markers.update", "maps.markers.delete"),
            "photos" to listOf("photos.select"),
            "music" to listOf("music.search", "music.state", "music.play", "music.pause", "music.seek", "music.library", "music.play-library"),
            "alarms" to listOf("alarms.open-create")
        )
        val platform = object : AndroidConnectorPlatform {
            override fun supported(module: String) = module in tools
            override fun authorized(module: String) = supported(module)
            override fun permissions(module: String) = emptyArray<String>()
            override fun tools(module: String) = tools[module].orEmpty()
            override suspend fun execute(request: JSONObject) = JSONObject()
        }
        val model = AndroidDeviceConnectors(context, "sheet-" + UUID.randomUUID(), scope, platform) { frames += JSONObject(it); true }
        val appearance = AppearanceSettings(context.getSharedPreferences("sheet-theme-" + UUID.randomUUID(), android.content.Context.MODE_PRIVATE))
            .apply { selectInterfaceStyle(InterfaceStyle.LIGHT) }
        fun screenshot(name: String) {
            java.io.File(context.cacheDir, name).outputStream().use {
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        try {
            compose.setContent { DshTheme(appearance) { ConnectorStatusScreen(model, {}) } }
            compose.runOnIdle { model.setEnabled("music", true); model.visibility(true); model.connection(true) }
            compose.waitUntil { frames.any { it.optString("type") == "device-tools-register" } }
            compose.runOnIdle {
                val frame = frames.last { it.optString("type") == "device-tools-register" }
                model.receive(JSONObject().put("kind", "device-tools-registered").put("version", 1)
                    .put("requestId", frame.getString("requestId")).put("capabilityRevision", frame.getInt("capabilityRevision"))
                    .put("connectionLeaseId", "sheet-lease").put("permissionSync", true).put("statusQuery", true)
                    .put("permissions", JSONObject().put("music", true)).put("modules", frame.getJSONArray("modules")).toString())
            }
            compose.onNodeWithTag("connector-registered-module-music").assertDoesNotExist()
            screenshot("connector-capabilities-entry.png")
            compose.onNodeWithTag("connector-registered-capabilities").performClick()
            compose.onNodeWithText("Android · 6 个连接器 · 22 项能力").assertIsDisplayed()
            tools.keys.forEach { compose.onNodeWithTag("connector-icon-$it", useUnmergedTree = true).assertIsDisplayed() }
            compose.onNodeWithTag("connector-registered-module-health").assertDoesNotExist()
            screenshot("connector-capabilities-list.png")
            // 手指不抬起时上下拖动，标题与拖动条始终属于同一个面板。
            val handle = compose.onNodeWithTag("connector-capabilities-handle", useUnmergedTree = true)
            val header = compose.onNodeWithTag("connector-capabilities-header")
            val gap = header.fetchSemanticsNode().boundsInRoot.top - handle.fetchSemanticsNode().boundsInRoot.top
            val before = handle.fetchSemanticsNode().boundsInRoot.top
            handle.performTouchInput { down(center); moveBy(androidx.compose.ui.geometry.Offset(0f, 100f)) }
            compose.waitForIdle()
            val down = handle.fetchSemanticsNode().boundsInRoot.top
            assertTrue(down > before)
            assertEquals(gap, header.fetchSemanticsNode().boundsInRoot.top - down, 2f)
            handle.performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(0f, -40f)) }
            compose.waitForIdle()
            assertTrue(handle.fetchSemanticsNode().boundsInRoot.top < down)
            assertEquals(gap, header.fetchSemanticsNode().boundsInRoot.top - handle.fetchSemanticsNode().boundsInRoot.top, 2f)
            handle.performTouchInput { up() }
            compose.waitForIdle()
            compose.onNodeWithTag("connector-registered-module-music").performClick()
            compose.onNodeWithText("本机音乐").assertIsDisplayed()
            compose.onNodeWithTag("connector-capability-music.state").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "已允许"))
            screenshot("connector-capabilities-detail.png")
            androidx.test.espresso.Espresso.pressBack()
            compose.onNodeWithTag("connector-registered-module-music").assertIsDisplayed()
            compose.onNodeWithTag("connector-registered-module-music").performClick()
            compose.runOnIdle { model.receive(JSONObject().put("kind", "device-tools-permissions").put("version", 1).put("connectionLeaseId", "sheet-lease").put("permissions", JSONObject().put("music", false)).toString()) }
            compose.onNodeWithTag("connector-capability-music.state").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "主机未允许"))
            compose.onNodeWithTag("connector-capabilities-back").performClick()
            compose.onNodeWithTag("connector-registered-module-location").performClick()
            compose.runOnIdle { appearance.selectFontFollowsSystem(false); appearance.selectFontScale(1.6f) }
            compose.waitForIdle()
            val fixedTop = header.fetchSemanticsNode().boundsInRoot.top
            val list = compose.onNodeWithTag("connector-capabilities-list")
            repeat(4) { list.performTouchInput { swipeUp() } }
            compose.onNodeWithTag("connector-capability-maps.markers.delete").assertIsDisplayed()
            assertEquals(fixedTop, header.fetchSemanticsNode().boundsInRoot.top, 2f)
            repeat(4) { list.performTouchInput { swipeDown() } }
            compose.onNodeWithTag("connector-capability-location.current").assertIsDisplayed()
            assertEquals(fixedTop, header.fetchSemanticsNode().boundsInRoot.top, 2f)
            compose.runOnIdle { appearance.selectFontScale(1f); appearance.selectInterfaceStyle(InterfaceStyle.DARK) }
            compose.onNodeWithTag("connector-capabilities-back").performClick()
            screenshot("connector-capabilities-dark.png")
            compose.runOnIdle { model.connection(false) }
            compose.onNodeWithTag("connector-capabilities-sheet").assertDoesNotExist()
            compose.onNodeWithTag("connector-registered-capabilities").assertDoesNotExist()
        } finally { scope.cancel() }
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

    @Test fun mapCardOpensPickerAndOnlyInstalledPluginsAreListed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val link = "whaleness-map://place?v=1&latitude=31.2&longitude=121.5&coordinateSystem=wgs84&name=Cafe&mode=show&transport=driving"
        val card = requireNotNull(connectorAppCard(link, "咖啡馆"))
        val destination = com.clarklevis.dsh.shared.facade.SharedMapLinks().parse(link, "咖啡馆")!!
        assertNull(card.transport)
        assertEquals(listOf("amap", "baidu", "google"), androidMapPlugins.map { it.id })
        androidMapPlugins.forEach { plugin ->
            val intent = plugin.intent(destination)!!
            assertEquals(plugin.packageName, intent.`package`)
            assertEquals(android.content.Intent.ACTION_VIEW, intent.action)
            when (plugin.id) {
                "amap" -> { assertEquals("route", intent.data?.host); assertEquals("0", intent.data?.getQueryParameter("t")) }
                "baidu" -> assertEquals("/direction", intent.data?.path)
                "google" -> assertEquals("/maps/dir/", intent.data?.path)
            }
        }
        compose.setContent { DshTheme { DshMenuHost { ConnectorAppCardView(card) } } }
        compose.onNodeWithTag("map-app-picker").assertDoesNotExist()
        compose.onNodeWithTag("maps-app-card").performClick()
        compose.onNodeWithTag("map-app-picker").assertIsDisplayed()
        compose.onNodeWithText("选择地图应用").assertIsDisplayed()
        androidMapPlugins.forEach { plugin ->
            if (plugin.installed(context, destination)) compose.onNodeWithTag("map-provider-${plugin.id}").assertIsDisplayed()
            else compose.onNodeWithTag("map-provider-${plugin.id}").assertDoesNotExist()
        }
        compose.onNodeWithTag("map-provider-browser").assertIsDisplayed()
        val screenshot = java.io.File(context.cacheDir, "map-app-picker.png")
        screenshot.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithTag("map-app-picker").assertDoesNotExist()
        compose.onNodeWithTag("maps-app-card").assertIsDisplayed()
    }

    @Test fun chatCardsUseNeutralLightGlassUnderPurpleMaterialTheme() {
        assertChatCardColors(dark = false)
    }

    @Test fun chatCardsUseNeutralDarkGlassUnderPurpleMaterialTheme() {
        assertChatCardColors(dark = true)
    }

    private fun assertChatCardColors(dark: Boolean) {
        val purple = androidx.compose.ui.graphics.Color(0xFFBB77EE)
        val scheme = if (dark) androidx.compose.material3.darkColorScheme(
            background = androidx.compose.ui.graphics.Color.Black, surfaceContainerLow = purple,
            onSurface = purple, onSurfaceVariant = purple, surfaceTint = purple
        ) else androidx.compose.material3.lightColorScheme(
            surfaceContainerLow = purple, onSurface = purple, onSurfaceVariant = purple, surfaceTint = purple
        )
        compose.setContent {
            androidx.compose.material3.MaterialTheme(colorScheme = scheme) {
                DshLiquidGlassHost(Modifier.fillMaxSize(), background = {
                    Box(it.fillMaxSize().background(if (dark) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.White))
                }) {
                    Column(Modifier.width(380.dp).statusBarsPadding().padding(20.dp)) {
                        ConnectorAppCardView(ConnectorAppCard("https://maps.apple.com/?ll=31.2,121.5", "测试地点", "maps", latitude = 31.2, longitude = 121.5))
                        Spacer(Modifier.height(20.dp))
                        ConnectorAppCardView(ConnectorAppCard("whaleness-music://local/songs/42", "测试歌曲", "local", artist = "测试歌手"))
                    }
                }
            }
        }
        for (tag in listOf("maps-app-card", "music-app-card")) {
            val pixels = compose.onNodeWithTag(tag).assertIsDisplayed().captureToImage().toPixelMap()
            // 取避开圆角、边框与内容的内侧像素，直接检查最终渲染颜色。
            val background = pixels[pixels.width / 2, 6]
            val channels = listOf(background.red, background.green, background.blue)
            assertTrue("卡片必须为中性灰，不能被 Material 紫色着色", channels.max() - channels.min() < .04f)
            if (dark) assertTrue(channels.all { it in .04f.. .24f })
            else assertTrue(channels.all { it in .90f..1f })
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        java.io.File(context.cacheDir, "connector-cards-glass-${if (dark) "dark" else "light"}.png").outputStream().use {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun mapPickerMatchesDesignEvenUnderPurpleMaterialTheme() {
        val destination = com.clarklevis.dsh.shared.facade.SharedMapDestination(31.2, 121.5, "上海博物馆")
        var selected: String? = null
        var closed = false
        compose.setContent {
            androidx.compose.material3.MaterialTheme(colorScheme = androidx.compose.material3.lightColorScheme(
                background = androidx.compose.ui.graphics.Color(0xFFF9F0FF),
                surface = androidx.compose.ui.graphics.Color(0xFFEADDFF),
                primary = androidx.compose.ui.graphics.Color.Magenta
            )) {
                MapAppPickerSheet(destination, androidMapPlugins, onDismiss = { closed = true },
                    onApp = { selected = it.id }, onWeb = { selected = "web" })
            }
        }
        compose.onNodeWithText("从当前位置出发 · 驾车").assertIsDisplayed()
        androidMapPlugins.forEach {
            compose.onNodeWithTag("map-icon-${it.id}", useUnmergedTree = true).assertWidthIsEqualTo(44.dp).assertHeightIsEqualTo(44.dp)
        }
        val pixels = compose.onNodeWithTag("map-app-picker").captureToImage().toPixelMap()
        val background = pixels[2, 2]
        org.junit.Assert.assertEquals(242f / 255f, background.red, .01f)
        org.junit.Assert.assertEquals(242f / 255f, background.green, .01f)
        org.junit.Assert.assertEquals(247f / 255f, background.blue, .01f)
        // 图标行与文字不跟随紫色主题，整行入口仍能选择应用。
        compose.onNodeWithTag("map-provider-amap").performClick()
        assertEquals("amap", selected)
        compose.onNodeWithTag("map-provider-browser").performClick()
        assertEquals("web", selected)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        java.io.File(context.cacheDir, "map-picker-replica-light.png").outputStream().use {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        // 保持手指按下，向下再向上移动：标题和拖动条必须同步移动。
        val handleBefore = compose.onNodeWithTag("map-picker-handle", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
        val headerBefore = compose.onNodeWithTag("map-picker-header").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("map-picker-handle", useUnmergedTree = true).performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(0f, 100f))
        }
        compose.waitForIdle()
        val handleDuring = compose.onNodeWithTag("map-picker-handle", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
        val headerDuring = compose.onNodeWithTag("map-picker-header").fetchSemanticsNode().boundsInRoot.top
        java.io.File(context.cacheDir, "map-picker-drag-down.png").outputStream().use {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        assertTrue(handleDuring > handleBefore)
        assertEquals(headerBefore - handleBefore, headerDuring - handleDuring, 2f)
        compose.onNodeWithTag("map-picker-handle", useUnmergedTree = true).performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(0f, -40f)) }
        compose.waitForIdle()
        val handleUp = compose.onNodeWithTag("map-picker-handle", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
        val headerUp = compose.onNodeWithTag("map-picker-header").fetchSemanticsNode().boundsInRoot.top
        java.io.File(context.cacheDir, "map-picker-drag-up.png").outputStream().use {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        assertTrue(handleUp < handleDuring)
        assertEquals(headerBefore - handleBefore, headerUp - handleUp, 2f)
        compose.onNodeWithTag("map-picker-handle", useUnmergedTree = true).performTouchInput { up() }
        compose.waitForIdle()
        compose.onNodeWithTag("map-picker-close").performClick()
        assertTrue(closed)
    }

    @Test fun mapPickerDarkPaletteAndTransitLabelRemainReadable() {
        val destination = com.clarklevis.dsh.shared.facade.SharedMapDestination(31.2, 121.5, "上海博物馆", "directions", "transit")
        compose.setContent {
            androidx.compose.material3.MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
                MapAppPickerSheet(destination, androidMapPlugins, onDismiss = {}, onApp = {}, onWeb = {})
            }
        }
        compose.onNodeWithText("从当前位置出发 · 公交").assertIsDisplayed()
        val pixels = compose.onNodeWithTag("map-app-picker").captureToImage().toPixelMap()
        assertTrue(pixels[2, 2].red < .02f && pixels[2, 2].blue < .02f)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        java.io.File(context.cacheDir, "map-picker-replica-dark.png").outputStream().use {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
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
