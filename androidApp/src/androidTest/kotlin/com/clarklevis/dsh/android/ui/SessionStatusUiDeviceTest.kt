package com.clarklevis.dsh.android.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.swipeUp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import com.clarklevis.dsh.shared.protocol.GatewayContextPressure
import com.clarklevis.dsh.shared.protocol.GatewaySessionStats
import com.clarklevis.dsh.shared.protocol.GatewaySessionStatsSnapshot
import com.clarklevis.dsh.shared.protocol.GatewaySessionTokenUsage
import com.clarklevis.dsh.shared.protocol.GatewaySessionTokenUsageTotals
import org.junit.Rule
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.Locale
import java.io.File

class SessionStatusUiDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var previousLocale: Locale

    @Before
    fun useChineseTestCopy() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun draggingSheetKeepsItsBackgroundEdgeAndHeaderTogether() {
        showMotionTestSheet(GatewaySessionStatsSnapshot(stats = GatewaySessionStats(turns = 10, steps = 15)))
        val sheet = compose.onNode(hasTestTag("session-stats-sheet"))
        val title = compose.onNode(hasText("会话状态"))
        for ((index, distance) in listOf(-180f, 100f).withIndex()) {
            val beforeEdge = sheetTopOnScreen("stats-$index-before.png")
            val beforeTitle = title.fetchSemanticsNode().boundsInRoot.top
            sheet.performTouchInput {
                down(center)
                moveTo(Offset(center.x, center.y + distance), delayMillis = 500)
            }
            try {
                compose.waitForIdle()
                val edgeDelta = sheetTopOnScreen("stats-$index-dragging.png") - beforeEdge
                val titleDelta = title.fetchSemanticsNode().boundsInRoot.top - beforeTitle
                assertEquals("背景上边缘与标题必须同步位移，不能单独拉伸顶部留白", edgeDelta.toDouble(), titleDelta.toDouble(), 3.0)
            } finally {
                sheet.performTouchInput { advanceEventTime(500); up() }
                compose.waitForIdle()
            }
        }
    }

    @Test
    fun overflowingStatsScrollWithoutMovingHeaderOrSheetEdge() {
        showMotionTestSheet(GatewaySessionStatsSnapshot(
            asOfSeq = 146,
            stats = GatewaySessionStats(turns = 10, steps = 15),
            tokenUsage = GatewaySessionTokenUsage(GatewaySessionTokenUsageTotals(
                inputTokens = 10_000, outputTokens = 2_000, cacheReadTokens = 3_000,
                cacheWriteTokens = 500, reasoningTokens = 1_000)),
            contextPressure = GatewayContextPressure(pressureTokens = 24_300, projectedTokens = 24_400, contextWindow = 272_000)
        ))
        val title = compose.onNode(hasText("会话状态"))
        val beforeTitle = title.fetchSemanticsNode().boundsInRoot.top
        val beforeEdge = sheetTopOnScreen("stats-scroll-before.png")
        compose.onNode(hasTestTag("session-stats-sheet")).performTouchInput { swipeUp(durationMillis = 800) }
        compose.onNode(hasText("数据截至事件 #146")).performScrollTo().assertIsDisplayed()
        assertEquals(beforeTitle.toDouble(), title.fetchSemanticsNode().boundsInRoot.top.toDouble(), 2.0)
        assertEquals(beforeEdge, sheetTopOnScreen("stats-scroll-after.png"))
    }

    private fun showMotionTestSheet(snapshot: GatewaySessionStatsSnapshot) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("session-stats-motion-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val appearance = AppearanceSettings(preferences).apply { selectInterfaceStyle(InterfaceStyle.LIGHT) }
        compose.setContent {
            DshTheme(appearance) {
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF113344)))
                SessionStatsSheet(snapshot, onDismiss = {})
            }
        }
        compose.onNode(hasText("会话状态")).assertIsDisplayed()
    }

    /** 直接读取屏幕上的圆角背景边缘，避免只测内部内容而漏掉背景单独移动。 */
    private fun sheetTopOnScreen(name: String): Int {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = instrumentation.uiAutomation.takeScreenshot()
        try {
            File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
                image.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            val x = image.width * 2 / 5
            return (0 until image.height).first { y ->
                val color = image.getPixel(x, y)
                kotlin.math.abs(Color.red(color) - 249) <= 2 &&
                    kotlin.math.abs(Color.green(color) - 250) <= 2 &&
                    kotlin.math.abs(Color.blue(color) - 252) <= 2
            }
        } finally { image.recycle() }
    }

    @Test
    fun arrowIsPartOfTheCardOutlineForBothPlacementDirections() {
        for (pointsUp in listOf(false, true)) {
            val outline = SessionStatsPopoverShape(arrowX = 220f, pointsUp = pointsUp)
                .createOutline(Size(300f, 220f), LayoutDirection.Ltr, Density(1f)) as Outline.Generic
            val bitmap = Bitmap.createBitmap(300, 220, Bitmap.Config.ARGB_8888)
            try {
                Canvas(bitmap).drawPath(outline.path.asAndroidPath(), Paint().apply { color = Color.BLACK })
                val tipY = if (pointsUp) 1 else 218
                assertNotEquals("Arrow tip must be filled", Color.TRANSPARENT, bitmap.getPixel(220, tipY))
                assertEquals("Arrow must be narrower than the card", Color.TRANSPARENT, bitmap.getPixel(180, tipY))
                assertNotEquals("Card body must join the arrow", Color.TRANSPARENT, bitmap.getPixel(220, 110))
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test
    fun closingPopoverWaitsForShrinkAnimationAndAllowsReopening() {
        compose.setContent {
            DshTheme {
                SessionStatsBanner(
                    snapshot = GatewaySessionStatsSnapshot(stats = GatewaySessionStats(turns = 2, steps = 3)),
                    sessionId = "animation-test",
                    onViewFullStats = {}
                )
            }
        }
        compose.onNode(hasTestTag("session-stats-capsule")).performClick()
        compose.onNode(hasTestTag("session-stats-popover")).assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        try {
            pressBack()
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(64)
            compose.onNode(hasTestTag("session-stats-popover")).assertExists()
            compose.mainClock.advanceTimeBy(300)
            compose.onNode(hasTestTag("session-stats-popover")).assertDoesNotExist()
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.onNode(hasTestTag("session-stats-capsule")).performClick()
        compose.onNode(hasTestTag("session-stats-popover")).assertIsDisplayed()
    }

    @Test
    fun capsuleOpensPopoverAndFullStatsSheet() {
        val snapshot = GatewaySessionStatsSnapshot(
            asOfSeq = 2_809,
            stats = GatewaySessionStats(
                turns = 2,
                steps = 3,
                llmMs = 27_100.0,
                toolMs = 36_200.0,
                ttftMs = 1_941.0,
                ttftSteps = 3,
                decodeMs = 900.0,
                decodeTokens = 100.0
            ),
            tokenUsage = GatewaySessionTokenUsage(
                GatewaySessionTokenUsageTotals(
                    inputTokens = 9_400,
                    outputTokens = 1_000,
                    cacheReadTokens = 2_000,
                    cacheWriteTokens = 100,
                    reasoningTokens = 500
                )
            ),
            contextPressure = GatewayContextPressure(
                pressureTokens = 9_400,
                projectedTokens = 10_400,
                contextWindow = 1_000_000
            )
        )
        compose.setContent {
            var showSheet by remember { mutableStateOf(false) }
            DshTheme {
                SessionStatsBanner(snapshot, "session-test") { showSheet = true }
                if (showSheet) SessionStatsSheet(snapshot) { showSheet = false }
            }
        }

        compose.onNode(hasTestTag("session-stats-capsule") and hasText("2 轮 · 3 步"))
            .assertIsDisplayed()
            .performClick()
        compose.onNode(hasTestTag("session-stats-popover")).assertIsDisplayed()
        compose.onNode(hasText("首 token 平均")).assertIsDisplayed()
        compose.onNode(hasTestTag("view-full-session-stats")).performClick()
        compose.onNode(hasTestTag("session-stats-sheet")).assertIsDisplayed()
        compose.onNode(hasText("会话状态")).assertIsDisplayed()
        compose.onNode(hasText("执行")).assertIsDisplayed()

        val sheetBottom = compose.onNode(hasTestTag("session-stats-sheet"))
            .fetchSemanticsNode().boundsInRoot.bottom
        val windowBottom = compose.onAllNodes(isRoot(), useUnmergedTree = true)
            .fetchSemanticsNodes().maxOf { it.boundsInRoot.bottom }
        assertTrue(
            "Bottom sheet must cover the window bottom: sheet=$sheetBottom, window=$windowBottom",
            sheetBottom >= windowBottom
        )
    }

    @Test
    fun fullStatsSheetFollowsAppAppearanceWithAndWithoutStats() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("session-stats-theme-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val appearance = AppearanceSettings(preferences)
        var snapshot by mutableStateOf<GatewaySessionStatsSnapshot?>(null)
        try {
            compose.setContent {
                // 系统保持浅色，验证应用主动选择深色时弹窗也能正确切换。
                val configuration = Configuration(LocalConfiguration.current).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        Configuration.UI_MODE_NIGHT_NO
                }
                CompositionLocalProvider(LocalConfiguration provides configuration) {
                    DshTheme(appearance) {
                        SessionStatsSheet(snapshot, onDismiss = {})
                    }
                }
            }
            for (hasStats in listOf(true, false)) {
                for (style in listOf(InterfaceStyle.LIGHT, InterfaceStyle.DARK)) {
                    compose.runOnIdle {
                        snapshot = if (hasStats) {
                            GatewaySessionStatsSnapshot(stats = GatewaySessionStats(turns = 2, steps = 3))
                        } else {
                            null
                        }
                        appearance.selectInterfaceStyle(style)
                    }
                    compose.onNode(hasText(if (hasStats) "执行" else "正在读取会话统计"))
                        .assertIsDisplayed()
                    val pixels = compose.onNode(hasTestTag("session-stats-sheet"))
                        .captureToImage().toPixelMap()
                    // 侧边留白处避开卡片与文字，检查实际绘制的弹窗底色。
                    val backgroundLuminance = pixels[4, pixels.height / 2].luminance()
                    val dark = style == InterfaceStyle.DARK
                    assertTrue(
                        "Stats background must follow $style (hasStats=$hasStats): $backgroundLuminance",
                        if (dark) backgroundLuminance in 0.02f..0.04f else backgroundLuminance > 0.8f
                    )
                    val title = compose.onNode(hasText("会话状态")).captureToImage().toPixelMap()
                    val titleLuminances = (0 until title.height).flatMap { y ->
                        (0 until title.width).map { x -> title[x, y].luminance() }
                    }
                    assertTrue(
                        "Stats title must contrast with $style background",
                        if (dark) titleLuminances.max() > 0.7f else titleLuminances.min() < 0.1f
                    )
                }
            }
        } finally {
            preferences.edit().clear().commit()
        }
    }
}
