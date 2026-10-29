package com.clarklevis.dsh.android.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class WorkspaceDrawerDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun brandFollowsAppAppearanceWhenSystemResourceThemeDiffers() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("drawer-brand-appearance-test", Context.MODE_PRIVATE)
        val appearance = AppearanceSettings(preferences).apply { selectInterfaceStyle(InterfaceStyle.LIGHT) }
        var systemDark by mutableStateOf(false)
        try {
            compose.setContent {
                val baseContext = LocalContext.current
                val systemConfiguration = Configuration(LocalConfiguration.current).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (systemDark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                }
                val resourcesContext = remember(baseContext, systemDark) { baseContext.createConfigurationContext(systemConfiguration) }
                CompositionLocalProvider(LocalContext provides resourcesContext, LocalConfiguration provides systemConfiguration) {
                    DshTheme(appearance) {
                        WorkspaceDrawer({}, {}) { open, _ ->
                            Button(onClick = open, modifier = Modifier.testTag("open-drawer")) { Text("打开") }
                        }
                    }
                }
            }
            compose.onNodeWithTag("open-drawer").performClick()
            for (resourceDark in listOf(false, true)) {
                for (appDark in listOf(false, true)) {
                    compose.runOnIdle {
                        systemDark = resourceDark
                        appearance.selectInterfaceStyle(if (appDark) InterfaceStyle.DARK else InterfaceStyle.LIGHT)
                    }
                    compose.waitForIdle()
                    val image = compose.onNodeWithContentDescription("Whaleness Harness").captureToImage()
                    val pixels = image.toPixelMap()
                    val glyphs = buildList {
                        for (x in image.width * 22 / 100 until image.width * 60 / 100) {
                            for (y in 0 until image.height) {
                                if (pixels[x, y].alpha > 0.95f) add(pixels[x, y].luminance())
                            }
                        }
                    }
                    assertTrue("品牌文字必须可见", glyphs.size > 20)
                    assertTrue("品牌文字应跟随应用主题：systemDark=$resourceDark appDark=$appDark",
                        glyphs.count { if (appDark) it > 0.8f else it < 0.2f } > glyphs.size * 0.02f)
                    context.cacheDir.resolve("drawer-brand-system-$resourceDark-app-$appDark.png").outputStream().use {
                        image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
            }
        } finally {
            preferences.edit().clear().commit()
        }
    }

    @Test
    fun settlingDoesNotRecomposeHomeOnEveryAnimationFrame() {
        var contentCompositions = 0
        compose.setContent {
            MaterialTheme {
                WorkspaceDrawer(onPlugins = {}, onScheduledTasks = {}) { openDrawer, canScroll ->
                    SideEffect { contentCompositions++ }
                    Box(Modifier.fillMaxSize()) {
                        Button(onClick = openDrawer, modifier = Modifier.testTag("open-drawer")) {
                            Text("打开")
                        }
                        Text(if (canScroll) "可滚动" else "暂停滚动")
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val initialCompositions = contentCompositions
        compose.onNodeWithTag("open-drawer").performClick()
        repeat(12) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        compose.runOnIdle {
            val animationCompositions = contentCompositions - initialCompositions
            assertTrue(
                "首页只能在交互状态变化时重组，12 个动画帧实际重组 $animationCompositions 次",
                animationCompositions <= 3
            )
        }
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun swipingOpensDrawerAndClosingRestoresVerticalScroll() {
        compose.setContent {
            MaterialTheme {
                WorkspaceDrawer(onPlugins = {}, onScheduledTasks = {}) { _, canScroll ->
                    Text(
                        if (canScroll) "可滚动" else "暂停滚动",
                        modifier = Modifier.testTag(if (canScroll) "scroll-enabled" else "scroll-disabled")
                    )
                }
            }
        }
        compose.onNodeWithTag("workspace-drawer-main").performTouchInput { swipeRight() }
        compose.onNodeWithTag("drawer-scheduled-tasks").assertIsDisplayed()
        compose.onNodeWithTag("scroll-disabled").assertIsDisplayed()
        compose.onNodeWithTag("workspace-drawer-main").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("scroll-enabled").assertIsDisplayed()
    }

    @Test
    fun tappingExposedHomeClosesDrawerWithoutInvokingHomeAction() {
        var homeClicks = 0
        var pluginClicks = 0
        compose.setContent {
            MaterialTheme {
                WorkspaceDrawer(onPlugins = { pluginClicks++ }, onScheduledTasks = {}) { openDrawer, _ ->
                    Column {
                        Button(onClick = openDrawer, modifier = Modifier.testTag("open-drawer")) {
                            Text("打开")
                        }
                        Button(onClick = { homeClicks++ }, modifier = Modifier.testTag("home-action")) {
                            Text("首页操作")
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("open-drawer").performClick()
        compose.onNodeWithTag("workspace-drawer-close").performTouchInput {
            down(center)
            up()
        }
        compose.onNodeWithTag("open-drawer").performClick()
        compose.onNodeWithTag("drawer-plugins").performClick()
        compose.runOnIdle {
            assertEquals(0, homeClicks)
            assertEquals(1, pluginClicks)
        }
    }
}
