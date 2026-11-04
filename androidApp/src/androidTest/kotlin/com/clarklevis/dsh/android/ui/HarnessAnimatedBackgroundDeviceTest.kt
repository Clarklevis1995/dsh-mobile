package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HarnessAnimatedBackgroundDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun meshFlowsAndKeepsOpaqueEdgesAcrossPhoneAndTabletLayouts() {
        val time = mutableFloatStateOf(0f)
        val layout = mutableIntStateOf(0)
        val clock = { time.floatValue }
        compose.setContent {
            OceanMeshBackground(
                time = clock,
                modifier = Modifier.size(
                    width = if (layout.intValue > 0) 600.dp else 280.dp,
                    height = when (layout.intValue) {
                        1 -> 280.dp
                        2 -> 800.dp
                        else -> 600.dp
                    }
                ).testTag("ocean-mesh"),
                largeWindow = layout.intValue > 0
            )
        }
        val before = compose.onNodeWithTag("ocean-mesh").captureToImage().toPixelMap()
        compose.runOnIdle { time.floatValue = 30f }
        val after = compose.onNodeWithTag("ocean-mesh").captureToImage().toPixelMap()
        var changes = 0
        for (row in 1..8) for (column in 1..8) {
            val x = before.width * column / 9
            val y = before.height * row / 9
            val first = before[x, y]
            val second = after[x, y]
            if (kotlin.math.abs(first.blue - second.blue) > 0.005f) changes++
        }
        assertTrue("控制点动画应产生实际可见的颜色流动", changes > 4)
        compose.runOnIdle { layout.intValue = 1 }
        val landscape = compose.onNodeWithTag("ocean-mesh").captureToImage().toPixelMap()
        compose.runOnIdle { layout.intValue = 2 }
        val portraitTablet = compose.onNodeWithTag("ocean-mesh").captureToImage().toPixelMap()
        for (pixels in listOf(before, after, landscape, portraitTablet)) {
            for (x in listOf(0, pixels.width / 2, pixels.width - 1)) {
                for (y in listOf(0, pixels.height / 2, pixels.height - 1)) {
                    assertTrue("网格动画和宽高变化不能露出透明边缘", pixels[x, y].alpha > 0.99f)
                }
            }
        }
        compose.runOnIdle { layout.intValue = 0 }
        val restoredPhone = compose.onNodeWithTag("ocean-mesh").captureToImage().toPixelMap()
        for (row in 1..4) for (column in 1..4) {
            val x = after.width * column / 5
            val y = after.height * row / 5
            assertEquals("窗口缩回手机宽度后应恢复手机构图", after[x, y].blue, restoredPhone[x, y].blue, 0.005f)
        }
    }

    @Test
    fun pausingBackgroundStopsGlassInvalidationAndResumesFromCurrentFrame() {
        val animationEnabled = mutableStateOf(true)
        val frameSignal = mutableLongStateOf(0L)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDshGlassFrameSignal provides frameSignal) {
                HarnessAnimatedBackground(animationEnabled = animationEnabled.value)
            }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        val beforePause = frameSignal.longValue
        assertTrue("背景应正常播放", beforePause > 0L)
        compose.runOnIdle { animationEnabled.value = false }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val pausedFrame = frameSignal.longValue
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertEquals("手势期间不应重新采样玻璃背景", pausedFrame, frameSignal.longValue)
        compose.runOnIdle { animationEnabled.value = true }
        compose.mainClock.advanceTimeBy(200)
        compose.waitForIdle()
        assertTrue("恢复时应接着播放而非归零", frameSignal.longValue > pausedFrame)
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun whaleParticlesKeepTheSourceAspectRatioAndInteriorBellyDetail() {
        val particles = makeWhaleParticles()
        assertTrue("whale mask should contain a dense point field", particles.size > 500)

        val width = particles.maxOf { it.x } - particles.minOf { it.x }
        val height = particles.maxOf { it.y } - particles.minOf { it.y }
        val sourceAspect = 24f / 18f
        assertTrue(
            "point field should preserve the 24:18 SVG aspect ratio",
            kotlin.math.abs(width / height - sourceAspect) < 0.10f
        )

        // The full source path contains a broad belly cutout around SVG (7, 12).
        // Its absence catches both an outer-contour-only regression and a filled-in mask;
        // unlike the sub-pixel eye, this detail intentionally survives the site's 60px grid.
        val detailX = 7f / 24f
        val detailY = (7.5f + 12f * 2.5f) / 60f
        val aroundDetail = particles.filter {
            hypot(it.x - detailX, it.y - detailY) < 0.24f
        }
        assertTrue("belly detail should be surrounded by whale particles", aroundDetail.size >= 20)
        assertFalse(
            "belly detail should remain cut out of the particle mask",
            aroundDetail.any { hypot(it.x - detailX, it.y - detailY) < 0.025f }
        )
    }
}
