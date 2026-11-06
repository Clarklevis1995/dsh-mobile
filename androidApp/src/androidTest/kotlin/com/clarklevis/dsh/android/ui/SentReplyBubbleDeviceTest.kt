package com.clarklevis.dsh.android.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.clarklevis.dsh.shared.protocol.GatewayTextPart
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SentReplyBubbleDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun bubbleFitsShortContentAndExpandsForLongReplyWithinAvailableWidth() {
        var parts by mutableStateOf(listOf(GatewayTextPart("quote", "引用"), GatewayTextPart("text", "嗯")))
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(300.dp).testTag("message-container"), horizontalAlignment = Alignment.End) {
                    SentReplyBubble(parts)
                }
            }
        }
        val bubble = compose.onNodeWithTag("sent-reply-bubble")
        val narrow = bubble.fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            parts = listOf(GatewayTextPart("quote", "引用"), GatewayTextPart("text", "这是比较长的回复内容，需要随着正文扩展气泡宽度。".repeat(4)))
        }
        val wide = bubble.fetchSemanticsNode().boundsInRoot
        val container = compose.onNodeWithTag("message-container").fetchSemanticsNode().boundsInRoot
        assertTrue(wide.width > narrow.width)
        assertTrue(wide.width <= container.width + 1)
        val quote = compose.onNodeWithTag("sent-reply-quote-0").fetchSemanticsNode().boundsInRoot
        val body = compose.onNodeWithTag("sent-reply-body").fetchSemanticsNode().boundsInRoot
        assertTrue(quote.bottom < body.top)
    }

    @Test fun longQuoteCanExpandAndCollapseInsideOneBubble() {
        val text = "搞定，118 页的内容已整理完成。这是你刚才引用的回复内容，可以展开查看全文。".repeat(6)
        var dark by mutableStateOf(false)
        compose.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme(onSurface = Color(0xFFF1F4FA))
                    else lightColorScheme(onSurface = DshColors.Ink)) {
                    val background = if (dark) Color(0xFF090D13) else Color.White
                    Column(Modifier.width(350.dp).background(background).padding(16.dp), horizontalAlignment = Alignment.End) {
                        SentReplyBubble(listOf(GatewayTextPart("quote", text), GatewayTextPart("text", "你好啊你好啊。")))
                    }
                }
            }
        }
        val quote = compose.onNodeWithTag("sent-reply-quote-0")
        val bubble = compose.onNodeWithTag("sent-reply-bubble")
        val layouts = mutableListOf<TextLayoutResult>()
        quote.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(2, layouts.single().lineCount)
        assertTrue(layouts.single().hasVisualOverflow)
        val height = bubble.fetchSemanticsNode().boundsInRoot.height
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "sent-reply-bubble.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        quote.performClick()
        val expandedHeight = bubble.fetchSemanticsNode().boundsInRoot.height
        assertTrue(expandedHeight > height)
        compose.runOnIdle { dark = true }
        assertEquals(expandedHeight, bubble.fetchSemanticsNode().boundsInRoot.height, 1f)
        quote.performClick()
        assertEquals(height, bubble.fetchSemanticsNode().boundsInRoot.height, 1f)
        File(context.getExternalFilesDir(null), "sent-reply-bubble-night.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun quoteOnlyMessageDoesNotCreateAnEmptyReplyRow() {
        compose.setContent { MaterialTheme { SentReplyBubble(listOf(GatewayTextPart("quote", "只回复这一段"))) } }
        compose.onNodeWithTag("sent-reply-quote-0").assertExists()
        compose.onNodeWithTag("sent-reply-body").assertDoesNotExist()
    }
}
