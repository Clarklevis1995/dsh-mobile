package com.clarklevis.dsh.android.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.hypot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HarnessAnimatedBackgroundDeviceTest {
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
