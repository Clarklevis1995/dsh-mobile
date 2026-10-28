package com.clarklevis.dsh.android.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.clarklevis.dsh.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt

class ConversationTopBarDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun buttonShadowsExtendBelowBarInNarrowAndWideLayouts() {
        var width by mutableStateOf(360.dp)
        val clicks = mutableSetOf<Int>()
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme(surface = Color.White)) {
                Box(Modifier.fillMaxSize().background(Color.White).testTag("shadow-root")) {
                    Column(Modifier.width(width)) {
                        Box(Modifier.testTag("shadow-bar")) {
                            ConversationTopBar(
                                title = { Text("长会话标题 Long conversation title", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                navigationIcon = {
                                    Row {
                                        TopBarCircleButton(R.drawable.ic_back_chevron, "返回", { clicks.add(0) }, Modifier.testTag("shadow-0"))
                                        TopBarCircleButton(R.drawable.ic_conversation_sidebar, "侧边栏", { clicks.add(1) }, Modifier.testTag("shadow-1"))
                                    }
                                },
                                actions = {
                                    TopBarCircleButton(R.drawable.ic_drawer_plugin, "团队", { clicks.add(2) }, Modifier.testTag("shadow-2"))
                                    TopBarCircleButton(R.drawable.ic_more_horizontal, "更多", { clicks.add(3) }, Modifier.testTag("shadow-3"))
                                }
                            )
                        }
                    }
                }
            }
        }
        for (layoutWidth in listOf(360.dp, 1100.dp)) {
            compose.runOnIdle { width = layoutWidth }
            val root = compose.onNodeWithTag("shadow-root")
            val rootBounds = root.fetchSemanticsNode().boundsInRoot
            val barBounds = compose.onNodeWithTag("shadow-bar").fetchSemanticsNode().boundsInRoot
            val image = root.captureToImage()
            val pixels = image.toPixelMap()
            val y = (barBounds.bottom - rootBounds.top + with(compose.density) { 1.dp.toPx() }).roundToInt()
            repeat(4) { index ->
                val button = compose.onNodeWithTag("shadow-$index").assertIsDisplayed()
                val bounds = button.fetchSemanticsNode().boundsInRoot
                val x = (bounds.center.x - rootBounds.left).roundToInt()
                assertTrue("按钮 $index 的阴影必须延伸到顶部栏下方（$layoutWidth）", pixels[x, y].red < 0.999f)
                assertEquals(with(compose.density) { 46.dp.toPx() }, bounds.height, 1f)
                button.performClick()
            }
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            context.cacheDir.resolve("conversation-topbar-shadow-${layoutWidth.value.toInt()}.png").outputStream().use {
                image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        compose.runOnIdle { assertEquals(setOf(0, 1, 2, 3), clicks) }
    }
}
