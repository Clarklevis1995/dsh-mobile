package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import com.clarklevis.dsh.shared.protocol.GatewayImageAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.launch

class ConversationChromeUiDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun moreButtonOpensIosParityActions() {
        var reloadCount = 0
        var pingCount = 0
        compose.setContent {
            DshTheme {
                ConversationMoreMenu(
                    canReloadHistory = true,
                    canPing = true,
                    onReloadHistory = { reloadCount += 1 },
                    onPing = { pingCount += 1 }
                )
            }
        }

        compose.onNode(hasContentDescription("更多")).performClick()
        compose.onNode(hasTestTag("conversation-more-menu")).assertIsDisplayed()
        compose.onNodeWithText("重新加载历史").performClick()
        assertEquals(1, reloadCount)

        compose.onNode(hasContentDescription("更多")).performClick()
        compose.onNodeWithText("发送 Ping").performClick()
        assertEquals(1, pingCount)
    }

    @Test
    fun compactEffortTagAndLargerJumpButtonKeepIosProportions() {
        compose.setContent {
            DshTheme {
                Column {
                    ReasoningEffortTag("Low")
                    ScrollToBottomButton(isGenerating = false, onClick = {})
                }
            }
        }

        val effortTagHeightDp = compose.onNode(hasTestTag("reasoning-effort-tag"))
            .fetchSemanticsNode().boundsInRoot.height / compose.density.density
        assertTrue("Reasoning tag should stay compact", effortTagHeightDp <= 22f)
        compose.onNode(hasTestTag("scroll-to-bottom"))
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun historyLoadingOverlayShowsGatewayProgress() {
        compose.setContent {
            DshTheme {
                HistoryLoadingOverlay(
                    loadedEventCount = 0,
                    totalEventCount = null
                )
            }
        }

        compose.onNode(hasTestTag("history-loading-overlay")).assertIsDisplayed()
        compose.onNodeWithText("正在加载历史记录").assertIsDisplayed()
        compose.onNodeWithText("正在从 Mobile Gateway 同步会话内容…").assertIsDisplayed()
    }

    @Test
    fun imagePreviewPagesAndClosesLikeIos() {
        val attachments = listOf(
            GatewayImageAttachment("image-a", "image/jpeg", 12, 4, 3, "第一张"),
            GatewayImageAttachment("image-b", "image/jpeg", 12, 4, 3, "第二张")
        )
        compose.setContent {
            var visible by remember { mutableStateOf(true) }
            if (visible) {
                ConversationImagePreviewDialog(
                    request = ConversationImagePreviewRequest(attachments, initialIndex = 0),
                    thumbnails = mapOf(
                        "image-a" to ImageBitmap(4, 3),
                        "image-b" to ImageBitmap(4, 3)
                    ),
                    onDismiss = { visible = false }
                )
            }
        }

        compose.onNode(hasTestTag("conversation-image-preview")).assertIsDisplayed()
        compose.onNodeWithText("1 / 2").assertIsDisplayed()
        compose.onNode(hasTestTag("conversation-image-preview")).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithText("2 / 2").assertIsDisplayed()
        compose.onNode(hasContentDescription("关闭图片预览")).performClick()
        compose.onNode(hasTestTag("conversation-image-preview")).assertDoesNotExist()
    }

    @Test
    fun jumpToBottomConsumesTheEntireTallLastMessage() {
        lateinit var listState: LazyListState
        compose.setContent {
            listState = rememberLazyListState()
            val scope = rememberCoroutineScope()
            Column {
                Box(
                    Modifier
                        .size(48.dp)
                        .testTag("jump-to-absolute-bottom")
                        .clickable {
                            scope.launch {
                                listState.scrollToTimelineBottom(lastItemIndex = 1, animated = true)
                            }
                        }
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier.height(260.dp),
                    contentPadding = PaddingValues(bottom = 96.dp)
                ) {
                    item { Spacer(Modifier.height(120.dp)) }
                    item { Spacer(Modifier.height(1_200.dp)) }
                }
            }
        }

        compose.waitForIdle()
        compose.onNode(hasTestTag("jump-to-absolute-bottom")).performClick()
        compose.waitUntil(timeoutMillis = 5_000) { !listState.canScrollForward }
        assertFalse(listState.canScrollForward)
    }
}
