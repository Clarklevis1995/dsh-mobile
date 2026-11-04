package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GlassButtonShadowDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun transparentButtonInteriorStaysUntintedWhileOutsideHasShadow() {
        compose.setContent { ShadowFixture() }
        val pixels = compose.onNodeWithTag("shadow-fixture").captureToImage().toPixelMap()
        val centerX = pixels.width / 2f
        val centerY = pixels.height / 2f
        val radius = with(compose.density) { 32.dp.toPx() }
        val safeRadius = radius - 2f
        for (y in pixels.height / 2 - radius.toInt()..pixels.height / 2 + radius.toInt()) {
            for (x in pixels.width / 2 - radius.toInt()..pixels.width / 2 + radius.toInt()) {
                val dx = x + 0.5f - centerX
                val dy = y + 0.5f - centerY
                if (dx * dx + dy * dy < safeRadius * safeRadius) {
                    assertTrue("按钮内部不能被投影染色", pixels[x, y].red > 0.995f)
                }
            }
        }
        val outsideY = (centerY + radius + with(compose.density) { 2.dp.toPx() }).toInt()
        assertTrue("轮廓外应保留轻投影", pixels[centerX.toInt(), outsideY].red < 0.99f)
    }
}

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
private fun ShadowFixture() {
    Box(Modifier.size(200.dp).background(Color.White).testTag("shadow-fixture"), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(64.dp).dshOuterShadow(
                CircleShape,
                Shadow(radius = 6.dp, color = Color.Black.copy(alpha = 0.08f), offset = DpOffset(0.dp, 2.dp)),
            ),
        )
    }
}
