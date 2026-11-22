package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.test.filters.SdkSuppress
import org.junit.Assert.assertEquals
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

    @Test
    @SdkSuppress(minSdkVersion = 31)
    fun workspaceGlassRetainsBackdropBlurDuringTouch() {
        compose.setContent {
            DshLiquidGlassHost(
                modifier = Modifier.size(240.dp, 192.dp),
                workspaceScrollState = rememberScrollableState { it },
                background = { modifier ->
                    Canvas(modifier.fillMaxSize()) {
                        drawRect(Color.Black)
                        drawRect(Color.White, Offset(size.width / 2f, 0f), Size(size.width / 2f, size.height))
                    }
                },
            ) {
                Box(Modifier.align(Alignment.Center).size(160.dp, 100.dp)
                    .dshWorkspaceGlass(RoundedCornerShape(20.dp)).testTag("workspace-glass"))
            }
        }
        val card = compose.onNodeWithTag("workspace-glass")
        val still = card.captureToImage().toPixelMap()
        val x = still.width / 2
        val y = still.height / 2
        assertTrue("卡片应模糊黑白交界", still[x, y].red in 0.10f..0.90f)

        card.performTouchInput { down(center) }
        try {
            val touched = card.captureToImage().toPixelMap()
            assertEquals("手指按住时应继续显示模糊的背景", still[x, y].red, touched[x, y].red, 0.07f)
        } finally {
            card.performTouchInput { up() }
        }
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
