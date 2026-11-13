package com.clarklevis.dsh.android.ui

import android.content.ClipboardManager
import android.content.Context
import android.text.TextPaint
import android.text.Spanned
import android.text.style.MetricAffectingSpan
import android.text.style.ClickableSpan
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import com.clarklevis.dsh.shared.projection.ConversationItem
import com.clarklevis.dsh.shared.projection.ConversationItemKind
import com.clarklevis.dsh.shared.protocol.GatewayTextPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class UserMarkdownBubbleDeviceTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val locale = ChineseDeviceLocaleRule()

    @Test fun userBubbleRendersFormattingAndCopyKeepsOriginalSource() {
        val source = "这是 **重点**，以及 [链接](https://example.com) 和 `code`。"
        var context: Context? = null
        compose.setContent {
            val view = LocalView.current
            SideEffect { context = view.context }
            DshTheme {
                Column(Modifier.width(320.dp)) {
                    UserMessage(ConversationItem("user", ConversationItemKind.USER, "", source, epochSeconds = 1.0),
                        emptyMap(), emptyMap(), {}, { _, _ -> })
                }
            }
        }
        onView(isAssignableFrom(DshMarkdownTextView::class.java)).check { view, _ ->
            val text = (view as DshMarkdownTextView).text as Spanned
            assertFalse(text.toString().contains("**"))
            assertTrue(text.getSpans(0, text.length, MetricAffectingSpan::class.java).any { span ->
                val paint = TextPaint()
                span.updateMeasureState(paint)
                paint.isFakeBoldText || paint.typeface?.isBold == true
            })
            assertTrue(text.getSpans(0, text.length, ClickableSpan::class.java).isNotEmpty())
        }
        compose.onNodeWithContentDescription("复制正文").performClick()
        compose.runOnIdle {
            val clipboard = context!!.getSystemService(ClipboardManager::class.java)
            assertEquals(source, clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
    }

    @Test fun quotedUserBodySupportsCodeBlocksWithoutIntrinsicMeasurementFailure() {
        compose.setContent {
            DshTheme {
                Column(Modifier.width(280.dp)) {
                    SentReplyBubble(listOf(GatewayTextPart("quote", "引用内容"),
                        GatewayTextPart("text", "**请检查**下面的代码：\n\n```kotlin\nval answer = 42\n```")))
                }
            }
        }
        compose.onNodeWithTag("sent-reply-quote-0").assertIsDisplayed()
        compose.onNodeWithTag("markdown-code-card").assertIsDisplayed()
        val bubble = compose.onNodeWithTag("sent-reply-bubble").fetchSemanticsNode().boundsInRoot
        assertTrue(bubble.width <= with(compose.density) { 280.dp.toPx() } + 1)
    }
}
