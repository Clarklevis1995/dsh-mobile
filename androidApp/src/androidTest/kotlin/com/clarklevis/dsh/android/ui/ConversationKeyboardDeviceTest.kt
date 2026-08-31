package com.clarklevis.dsh.android.ui

import android.view.View
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.clarklevis.dsh.android.AndroidSharedStateHolder
import org.junit.Rule
import org.junit.Test

class ConversationKeyboardDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun inputFocusMovesLatestMessageAboveImeAndTimelineTapHidesIme() {
        val fixture = showConversation()
        val latestMessage = compose.onNode(
            hasTestTag("user-text-bubble") and hasText("末尾标记", substring = true)
        )
        latestMessage.assertIsDisplayed()
        val messageTopBeforeIme = latestMessage.fetchSemanticsNode().boundsInRoot.top

        compose.onNodeWithTag("composer-input").performClick().assertIsFocused()
        waitForIme(fixture.view, visible = true)
        compose.waitUntil(timeoutMillis = 5_000) {
            latestMessage.fetchSemanticsNode().boundsInRoot.top < messageTopBeforeIme - 100f
        }

        compose.onNodeWithTag("conversation-timeline").performTouchInput {
            click(Offset(center.x, 40f))
        }

        compose.onNodeWithTag("composer-input").assertIsNotFocused()
        waitForIme(fixture.view, visible = false)
        fixture.holder.close()
    }

    @Test
    fun successfulSendClearsFocusAndHidesIme() {
        val fixture = showConversation()
        compose.runOnIdle { fixture.holder.messageDraft = "你好" }
        compose.onNodeWithTag("composer-input").performClick().assertIsFocused()
        waitForIme(fixture.view, visible = true)
        val submission = compose.runOnIdle {
            fixture.holder.captureMessageSubmissionForTest()
        }

        compose.runOnIdle {
            fixture.holder.applyMessageSendResultForTest(submission, sent = true)
        }

        compose.onNodeWithTag("composer-input").assertIsNotFocused()
        waitForIme(fixture.view, visible = false)
        fixture.holder.close()
    }

    private fun showConversation(): Fixture {
        val holder = AndroidSharedStateHolder().apply {
            wirePayload =
                """{"kind":"sessions","items":[{"sessionId":"keyboard-session","updatedAt":1786937352000,"running":false,"blank":false,"cwd":"/tmp/keyboard","agentPreset":"standard"}]}"""
            submitWirePayload()
            selectSession("keyboard-session")
            repeat(14) { index ->
                val text = if (index == 13) {
                    "末尾标记 ${"最后一条内容".repeat(8)}"
                } else {
                    "第${index + 1}条消息 ${"用于填充会话列表".repeat(8)}"
                }
                wirePayload =
                    """{"kind":"event","sessionId":"keyboard-session","seq":${index + 1},"time":${1786937353 + index},"event":{"type":"user/message","source":"user","text":"$text"}}"""
                submitWirePayload()
            }
        }
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            DshTheme {
                ConversationScreen(
                    stateHolder = holder,
                    onPickImage = {},
                    onBack = {}
                )
            }
        }
        return Fixture(holder, view)
    }

    private fun waitForIme(view: View, visible: Boolean) {
        compose.waitUntil(timeoutMillis = 5_000) {
            ViewCompat.getRootWindowInsets(view)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == visible
        }
    }

    private data class Fixture(
        val holder: AndroidSharedStateHolder,
        val view: View
    )
}
