package com.clarklevis.dsh.android.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.clarklevis.dsh.android.AndroidSharedStateHolder
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ConversationPagerChromeDeviceTest {
    @get:Rule val locale = ChineseDeviceLocaleRule()

    @get:Rule val compose = createComposeRule()

    @Test
    fun staticTitleSwipeAndAccessibilityPreserveBothPages() {
        val holder = AndroidSharedStateHolder().apply {
            loadFixture()
            wirePayload = """{"kind":"question-resolved","rpcId":"android-question"}"""
            submitWirePayload()
            messageDraft = "切页后保留草稿"
        }
        compose.setContent { DshTheme { ConversationScreen(holder, onPickImage = {}, onBack = {}) } }
        try {
            val title = compose.onNodeWithTag("conversation-page-title")
            val pager = compose.onNodeWithTag("conversation-pager")
            compose.onNodeWithTag("conversation-tabs").assertDoesNotExist()
            val listBounds = compose.onNodeWithTag("conversation-message-list").fetchSemanticsNode().boundsInRoot
            assertTrue("正文应延伸到顶部玻璃后方", listBounds.top < title.fetchSemanticsNode().boundsInRoot.top)
            val preview = compose.onNodeWithTag("conversation-screen").captureToImage()
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
                .resolve("conversation-pager-aligned.png").outputStream().use {
                    preview.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            title.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
            compose.onNodeWithTag("conversation-title-chevron", useUnmergedTree = true).assertDoesNotExist()
            title.performTouchInput { click() }
            title.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
            compose.onNodeWithTag("conversation-sidebar").assertDoesNotExist()
            title.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "对话"))
            compose.onNodeWithTag("conversation-page-menu").assertDoesNotExist()
            pager.performTouchInput { swipe(Offset(width * 0.85f, height * 0.7f), Offset(width * 0.25f, height * 0.7f)) }
            title.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "轨迹"))
            val duration = compose.onNodeWithTag("trajectory-duration-toggle")
            assertTrue(duration.fetchSemanticsNode().boundsInRoot.top >= title.fetchSemanticsNode().boundsInRoot.bottom)
            duration.performClick().assertIsSelected()
            pager.performTouchInput { swipe(Offset(width * 0.25f, height * 0.7f), Offset(width * 0.85f, height * 0.7f)) }
            title.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "对话"))
            pager.performTouchInput { swipe(Offset(width * 0.85f, height * 0.7f), Offset(width * 0.25f, height * 0.7f)) }
            duration.assertIsSelected()
            pager.performTouchInput { swipe(Offset(width * 0.25f, height * 0.7f), Offset(width * 0.85f, height * 0.7f)) }
            title.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "对话"))
            val actions = pager.fetchSemanticsNode().config[SemanticsActions.CustomActions]
            compose.runOnIdle {
                assertEquals("切换到轨迹", actions.single().label)
                assertTrue(actions.single().action())
            }
            title.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "轨迹"))
            duration.assertIsSelected()
            compose.runOnIdle { assertEquals("切页后保留草稿", holder.messageDraft) }
        } finally {
            compose.runOnIdle { holder.close() }
        }
    }

    @Test
    fun indicatorFadesAndRestartsForPageAndSessionChanges() {
        var sessionId by mutableStateOf("first")
        compose.setContent {
            DshTheme {
                val pager = rememberPagerState(pageCount = { 2 })
                val scope = rememberCoroutineScope()
                val select: (Int) -> Unit = { scope.launch { pager.scrollToPage(it) } }
                ConversationPagerChrome(
                    pager,
                    sessionId,
                    select,
                    topBar = { ConversationPageTitle("测试会话", pager.currentPage) }
                ) { _, _ -> }
            }
        }
        compose.onNodeWithTag("conversation-page-indicator").assertIsDisplayed()
        fun waitForFade() {
            compose.mainClock.advanceTimeBy(2_400)
            compose.waitForIdle()
            compose.onNodeWithTag("conversation-page-indicator").assertDoesNotExist()
        }
        waitForFade()
        val switchPage = compose.onNodeWithTag("conversation-pager").fetchSemanticsNode().config[SemanticsActions.CustomActions].single()
        compose.runOnIdle { assertTrue(switchPage.action()) }
        compose.onNodeWithTag("conversation-page-indicator").assertIsDisplayed()
        waitForFade()
        compose.runOnIdle { sessionId = "second" }
        compose.onNodeWithTag("conversation-page-indicator").assertIsDisplayed()
    }

    @Test
    fun darkTitleAndBodyInheritReadableForeground() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(surface = Color.Black, onSurface = Color.White)) {
                val pager = rememberPagerState(pageCount = { 2 })
                ConversationPagerChrome(
                    pager,
                    "dark",
                    {},
                    topBar = { ConversationPageTitle("深色标题", 0) }
                ) { index, _ ->
                    if (index == 0) Text("深色正文")
                }
            }
        }
        for (text in listOf("深色标题", "深色正文")) {
            val action = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
                .config[SemanticsActions.GetTextLayoutResult].action!!
            val layouts = mutableListOf<TextLayoutResult>()
            compose.runOnIdle { assertTrue(action(layouts)) }
            assertTrue("$text 在深色背景上必须保持可读", layouts.single().layoutInput.style.color.luminance() > 0.5f)
        }
    }

    @Test
    fun softEdgeProtectsTitleAndFadesBelowToolbar() {
        compose.setContent {
            MaterialTheme {
                val pager = rememberPagerState(pageCount = { 2 })
                ConversationPagerChrome(
                    pager,
                    "edge-contrast",
                    {},
                    Modifier.testTag("edge-contrast-root"),
                    topBar = { Box(Modifier.fillMaxWidth().height(64.dp)) }
                ) { _, _ ->
                    Canvas(Modifier.fillMaxSize()) {
                        val stripe = 6.dp.toPx()
                        repeat((size.width / stripe).toInt() + 1) { index ->
                            drawRect(
                                if (index % 2 == 0) Color.Black else Color.White,
                                topLeft = Offset(index * stripe, 0f),
                                size = Size(stripe, size.height)
                            )
                        }
                    }
                }
            }
        }
        val image = compose.onNodeWithTag("edge-contrast-root").captureToImage()
        val pixels = image.toPixelMap()
        fun contrast(yDp: Int): Float {
            val y = with(compose.density) { yDp.dp.roundToPx() }
            val row = (pixels.width / 4 until pixels.width * 3 / 4).map { pixels[it, y].red }
            return row.max() - row.min()
        }
        val body = contrast(160)
        for (yDp in listOf(16, 36, 48)) {
            assertTrue("标题和模式文字后应充分遮挡正文", contrast(yDp) < body * 0.05f)
        }
        assertTrue("顶栏底部仍应保留较强遮挡", contrast(64) < body * 0.30f)
        assertTrue("栏底以下应保持连续渐隐", contrast(60) < contrast(68) && contrast(68) < contrast(80) && contrast(80) < contrast(92))
        assertTrue("原有遮罩下方也应进入渐变范围", contrast(80) < body * 0.80f)
        assertTrue("模糊和底色在渐变起点不能形成接缝", kotlin.math.abs(contrast(56) - contrast(55)) < body * 0.015f)
        assertTrue("渐变终点应平缓接回正文", kotlin.math.abs(contrast(96) - contrast(95)) < body * 0.015f)
        for (yDp in listOf(96, 104, 120)) {
            assertEquals("渐变结束后正文不能继续被蒙层覆盖", body, contrast(yDp), 0.01f)
        }
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
            .resolve("conversation-soft-edge-contrast.png").outputStream().use {
                image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
    }

    @Test
    fun glassSamplesTheContentAndLeavesTheBodyUntinted() {
        var color by mutableStateOf(Color.Red)
        compose.setContent {
            DshTheme {
                val pager = rememberPagerState(pageCount = { 2 })
                ConversationPagerChrome(
                    pager,
                    "glass",
                    {},
                    Modifier.testTag("glass-root"),
                    topBar = { Box(Modifier.fillMaxWidth().height(64.dp)) }
                ) { _, _ ->
                    Box(Modifier.fillMaxSize().background(color))
                }
            }
        }
        fun topSample(): Color {
            val root = compose.onNodeWithTag("glass-root")
            val pixels = root.captureToImage().toPixelMap()
            return pixels[pixels.width / 4, with(compose.density) { 56.dp.roundToPx() }]
        }
        val red = topSample()
        assertTrue("玻璃应保留轻微正文色彩采样", red.red > red.blue + 0.005f)
        compose.runOnIdle { color = Color.Blue }
        val blue = topSample()
        assertTrue("正文变化后玻璃应同步更新采样", blue.blue > blue.red + 0.005f)
        val root = compose.onNodeWithTag("glass-root").captureToImage().toPixelMap()
        val body = root[root.width / 4, with(compose.density) { 104.dp.roundToPx() }]
        assertTrue("玻璃不能改变下方正文的颜色", body.blue > 0.95f && body.red < 0.05f)
    }

    @Test
    fun fadeHasNoSeamInLightDarkAndReducedEffectsModes() {
        var dark by mutableStateOf(false)
        var reduced by mutableStateOf(false)
        compose.setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme(surface = Color.Black) else lightColorScheme(surface = Color.White)) {
                CompositionLocalProvider(LocalUiEffectsReduced provides reduced) {
                    val pager = rememberPagerState(pageCount = { 2 })
                    ConversationPagerChrome(pager, "seam", {}, Modifier.testTag("seam-root"),
                        topBar = { Box(Modifier.fillMaxWidth().height(64.dp)) }) { _, _ ->
                        Canvas(Modifier.fillMaxSize()) {
                            val stripe = 6.dp.toPx()
                            repeat((size.width / stripe).toInt() + 1) { index ->
                                drawRect(if (index % 2 == 0) Color.Black else Color.White,
                                    Offset(index * stripe, 0f), Size(stripe, size.height))
                            }
                        }
                    }
                }
            }
        }
        for ((isDark, effectsReduced) in listOf(false to false, true to false, false to true, true to true)) {
            compose.runOnIdle { dark = isDark; reduced = effectsReduced }
            val image = compose.onNodeWithTag("seam-root").captureToImage()
            val pixels = image.toPixelMap()
            fun contrast(yDp: Int): Float {
                val y = with(compose.density) { yDp.dp.roundToPx() }
                // 中央分页指示器有自己的底色；只测左侧未叠加控件的遮罩区域。
                val row = (pixels.width / 8 until pixels.width / 4).map { pixels[it, y].red }
                return row.max() - row.min()
            }
            val label = "dark=$isDark reduced=$effectsReduced"
            assertTrue("渐变起点不能发生突变：$label", kotlin.math.abs(contrast(56) - contrast(55)) < 0.015f)
            assertTrue("渐变终点不能形成边线：$label", kotlin.math.abs(contrast(96) - contrast(95)) < 0.015f)
            for (y in 57..96) {
                val jump = kotlin.math.abs(contrast(y) - contrast(y - 1))
                assertTrue("整段渐变必须保持连续：$label y=$y jump=$jump", jump < 0.06f)
            }
            assertEquals("遮罩之外的正文不受影响：$label", contrast(160), contrast(104), 0.01f)
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
                .resolve("conversation-fade-dark-$isDark-reduced-$effectsReduced.png").outputStream().use {
                    image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
                }
        }
    }
}
