package com.clarklevis.dsh.android.ui

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.clarklevis.dsh.shared.protocol.GatewayPendingQuestionRequest
import com.clarklevis.dsh.shared.protocol.GatewayQuestion
import com.clarklevis.dsh.shared.protocol.GatewayQuestionOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HumanQuestionCardDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun footerStaysVisibleAndFocusedCustomAnswerMovesAboveItWhenImeOpens() {
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            DshTheme {
                Box(Modifier.fillMaxSize()) {
                    HumanQuestionCard(
                        request = questionRequest(),
                        onAnswer = {},
                        onCancel = {}
                    )
                }
            }
        }

        val footer = compose.onNodeWithTag("question-footer").assertIsDisplayed()
        val input = compose.onNodeWithTag("question-custom-input")
        input.performScrollTo().assertIsDisplayed().performClick().assertIsFocused()
        waitForIme(view)
        compose.waitUntil(timeoutMillis = 5_000) {
            input.fetchSemanticsNode().boundsInRoot.bottom <=
                footer.fetchSemanticsNode().boundsInRoot.top
        }

        input.assertIsDisplayed()
        footer.assertIsDisplayed()
        assertTrue(
            input.fetchSemanticsNode().boundsInRoot.bottom <=
                footer.fetchSemanticsNode().boundsInRoot.top
        )
    }

    @Test
    fun panelAnimatesBetweenExpandedAndCollapsedLayouts() {
        compose.setContent {
            DshTheme {
                HumanQuestionCard(
                    request = questionRequest(),
                    onAnswer = {},
                    onCancel = {}
                )
            }
        }

        compose.onNodeWithTag("question-footer").assertIsDisplayed()
        compose.onNode(hasContentDescription("收起问题卡片")).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("question-footer").assertDoesNotExist()
        compose.onNode(hasContentDescription("展开问题卡片")).assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("question-footer").assertIsDisplayed()
    }

    @Test
    fun panelAnimatesInAndOutWhileRetainingOutgoingQuestionContent() {
        lateinit var updateRequest: (GatewayPendingQuestionRequest?) -> Unit
        compose.setContent {
            var request by remember { mutableStateOf<GatewayPendingQuestionRequest?>(null) }
            updateRequest = { request = it }
            DshTheme {
                AnimatedHumanQuestionPanel(
                    request = request,
                    onAnswer = { _, _ -> },
                    onCancel = {}
                ) {
                    Box(Modifier.fillMaxSize().testTag("question-panel-empty"))
                }
            }
        }

        compose.onNodeWithTag("question-panel-empty").assertIsDisplayed()
        compose.runOnIdle { updateRequest(questionRequest()) }
        compose.waitForIdle()
        compose.onNodeWithTag("question-panel").assertIsDisplayed()
        compose.onNodeWithTag("question-panel-empty").assertDoesNotExist()

        compose.runOnIdle { updateRequest(null) }
        compose.waitForIdle()
        compose.onNodeWithTag("question-panel").assertDoesNotExist()
        compose.onNodeWithTag("question-panel-empty").assertIsDisplayed()
    }

    @Test
    fun skipAndCloseUseTheSameCompactIosStyleConfirmationPopover() {
        var cancelCount = 0
        compose.setContent {
            DshTheme {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    HumanQuestionCard(
                        request = questionRequest(),
                        onAnswer = {},
                        onCancel = { cancelCount += 1 }
                    )
                }
            }
        }

        compose.onNodeWithText("跳过问题").performClick()
        compose.onNodeWithTag("question-cancel-popover").assertIsDisplayed()
        compose.onNodeWithText("放弃这组问题？").assertIsDisplayed()
        compose.onNodeWithText("继续回答").assertDoesNotExist()
        val popoverWidthDp = compose.onNodeWithTag("question-cancel-popover")
            .fetchSemanticsNode().boundsInRoot.width / compose.density.density
        assertTrue(popoverWidthDp <= 300.5f)
        compose.onNodeWithTag("question-cancel-confirm").performClick()
        compose.runOnIdle { assertEquals(1, cancelCount) }

        compose.onNode(hasContentDescription("放弃整组问题")).performClick()
        compose.onNodeWithTag("question-cancel-popover").assertIsDisplayed()
        compose.onNodeWithTag("question-cancel-confirm").performClick()
        compose.runOnIdle { assertEquals(2, cancelCount) }
    }

    @Test
    fun footerKeepsSubmitButtonWideEnoughForSingleLineLabel() {
        compose.setContent {
            DshTheme {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    HumanQuestionCard(
                        request = questionRequest(),
                        onAnswer = {},
                        onCancel = {}
                    )
                }
            }
        }

        val submitBounds = compose.onNodeWithTag("question-submit")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(submitBounds.width >= compose.density.density * 68f)
        assertTrue(submitBounds.width > submitBounds.height)
    }

    private fun waitForIme(view: View) {
        compose.waitUntil(timeoutMillis = 5_000) {
            ViewCompat.getRootWindowInsets(view)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
    }

    private fun questionRequest() = GatewayPendingQuestionRequest(
        rpcId = "question-device-test",
        sessionId = "session-device-test",
        replay = true,
        questions = listOf(
            GatewayQuestion(
                id = "task",
                header = "选择任务",
                question = "你想让我接下来帮你做什么？",
                options = listOf(
                    GatewayQuestionOption("写公众号排版文章", "第一项较长的说明文字，用于占据两行空间。"),
                    GatewayQuestionOption("设计或重做页面", "第二项较长的说明文字，用于占据两行空间。"),
                    GatewayQuestionOption("润色去 AI 痕迹", "第三项较长的说明文字，用于占据两行空间。"),
                    GatewayQuestionOption("其他具体任务", "第四项较长的说明文字，用于占据两行空间。")
                )
            )
        )
    )
}
