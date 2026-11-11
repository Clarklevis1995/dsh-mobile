package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class ConversationBottomStabilityDeviceTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var listState: LazyListState

    private fun showTimeline() {
        compose.setContent {
            DshTheme {
                listState = rememberLazyListState()
                val scope = rememberCoroutineScope()
                val density = LocalDensity.current
                val composerHeight = remember { mutableStateOf(80.dp) }
                Box(Modifier.size(320.dp, 500.dp)) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().testTag("timeline"),
                        contentPadding = remember { ComposerViewportPadding(composerHeight, bottomExtra = 22.dp) }
                    ) {
                        item { Spacer(Modifier.height(1_200.dp)) }
                        item { Spacer(Modifier.height(250.dp).testTag("last-message")) }
                    }
                    ConversationComposerChrome(
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                        composerModifier = Modifier.testTag("composer").onSizeChanged {
                            composerHeight.value = with(density) { it.height.toDp() }
                        },
                        jumpVisible = listState.canScrollForward,
                        jumpButton = {
                            ScrollToBottomButton(false) {
                                scope.launch { listState.scrollToTimelineBottom(1, animated = true) }
                            }
                        }
                    ) { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun assertBottomDoesNotMoveDuringButtonExit() {
        compose.waitUntil { !listState.canScrollForward }
        val lastOffset = listState.layoutInfo.visibleItemsInfo.last().offset
        val padding = listState.layoutInfo.afterContentPadding
        val composerBounds = compose.onNodeWithTag("composer").fetchSemanticsNode().boundsInRoot
        // 覆盖整个 160ms 退出动画及之后的静置帧，不能只检查最终状态。
        repeat(16) {
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                assertFalse(listState.canScrollForward)
                assertEquals(padding, listState.layoutInfo.afterContentPadding)
                assertEquals(lastOffset, listState.layoutInfo.visibleItemsInfo.last().offset)
            }
            assertEquals(composerBounds, compose.onNodeWithTag("composer").fetchSemanticsNode().boundsInRoot)
        }
        compose.onNodeWithTag("scroll-to-bottom").assertDoesNotExist()
    }

    @Test fun reachingBottomKeepsMessageAndPaddingStableWhenButtonDisappears() {
        showTimeline()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("timeline").performScrollToIndex(1)
        compose.mainClock.advanceTimeByFrame()
        assertBottomDoesNotMoveDuringButtonExit()
    }

    @Test fun tappingJumpButtonKeepsFinalPositionStableAfterAnimation() {
        showTimeline()
        compose.onNodeWithTag("scroll-to-bottom").performClick()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        assertBottomDoesNotMoveDuringButtonExit()
    }
}
