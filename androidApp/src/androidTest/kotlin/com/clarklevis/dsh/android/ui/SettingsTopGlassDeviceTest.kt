package com.clarklevis.dsh.android.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.platform.app.InstrumentationRegistry
import com.clarklevis.dsh.android.AndroidSharedStateHolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class SettingsTopGlassDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun listScrollsBehindToolbarAndBackRemainsClickable() {
        val originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        val holder = AndroidSharedStateHolder()
        var returned = false
        try {
            compose.setContent {
                DshTheme {
                    SettingsScreen(
                        holder,
                        onBack = { returned = true },
                        onOpenAgentPresets = {},
                        onOpenDefaultModel = {},
                        onOpenFontSize = {},
                    )
                }
            }
            val list = compose.onNodeWithTag("settings-list")
            val toolbar = compose.onNodeWithTag("settings-toolbar")
            val glass = compose.onNodeWithTag("settings-top-glass")
            val toolbarBounds = toolbar.fetchSemanticsNode().boundsInRoot
            val listBounds = list.fetchSemanticsNode().boundsInRoot
            val glassBounds = glass.fetchSemanticsNode().boundsInRoot
            assertTrue("列表视口必须延伸到顶栏后方", listBounds.top < toolbarBounds.bottom)
            assertEquals("毛玻璃下沿必须与顶栏边界齐平，不向列表渐隐", toolbarBounds.bottom, glassBounds.bottom, 1f)
            val root = compose.onNodeWithTag("settings-screen")
            val rootBounds = root.fetchSemanticsNode().boundsInRoot
            val lineY = (glassBounds.bottom - rootBounds.top).roundToInt() - 1
            fun assertNoSeparator() {
                val pixels = root.captureToImage().toPixelMap()
                for (x in listOf(0, pixels.width - 1)) {
                    assertEquals("列表位于顶部时不应显示分割线", pixels[x, lineY - 1].red, pixels[x, lineY].red, 0.005f)
                }
            }
            assertNoSeparator()
            list.performTouchInput { swipeUp() }
            assertEquals(toolbarBounds, toolbar.fetchSemanticsNode().boundsInRoot)
            val image = root.captureToImage()
            val pixels = image.toPixelMap()
            for (x in listOf(0, pixels.width - 1)) {
                val line = pixels[x, lineY].red
                val above = pixels[x, lineY - 1].red
                val below = pixels[x, lineY + 1].red
                assertTrue("分割线必须延伸至屏幕左右边缘", abs(line - above) > 0.03f)
                assertTrue("下沿分割线必须只有一个物理像素", abs(line - below) > 0.03f)
            }
            InstrumentationRegistry
                .getInstrumentation()
                .targetContext.cacheDir
                .resolve("settings-top-glass.png")
                .outputStream()
                .use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
            list.performScrollToIndex(0)
            assertNoSeparator()
            compose.onNodeWithContentDescription("返回").performClick()
            compose.runOnIdle { assertTrue(returned) }
        } finally {
            compose.runOnIdle { holder.close() }
            Locale.setDefault(originalLocale)
        }
    }
}
