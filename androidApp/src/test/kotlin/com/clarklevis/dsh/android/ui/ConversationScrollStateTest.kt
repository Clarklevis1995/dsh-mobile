package com.clarklevis.dsh.android.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationScrollStateTest {
    @Test
    fun `bottom delta includes the full last row and composer padding`() {
        assertEquals(
            1_000f,
            timelineBottomScrollDelta(
                lastItemOffset = 0,
                lastItemSize = 1_600,
                viewportEndOffset = 800,
                afterContentPadding = 200
            )
        )
        assertEquals(
            0f,
            timelineBottomScrollDelta(
                lastItemOffset = 500,
                lastItemSize = 100,
                viewportEndOffset = 800,
                afterContentPadding = 200
            )
        )
    }

    @Test
    fun `the list is pinned only when it cannot scroll farther forward`() {
        assertTrue(isTimelinePinnedToBottom(totalItems = 0, canScrollForward = false))
        assertTrue(isTimelinePinnedToBottom(totalItems = 8, canScrollForward = false))
        assertFalse(isTimelinePinnedToBottom(totalItems = 8, canScrollForward = true))
    }

    @Test
    fun `streamed layout changes cannot unpin without a scroll`() {
        assertFalse(
            shouldPublishPinnedState(
                isScrolling = false,
                pinned = false,
                isProgrammaticScroll = false
            )
        )
        assertTrue(
            shouldPublishPinnedState(
                isScrolling = true,
                pinned = false,
                isProgrammaticScroll = false
            )
        )
        assertFalse(
            shouldPublishPinnedState(
                isScrolling = true,
                pinned = false,
                isProgrammaticScroll = true
            )
        )
        assertTrue(
            shouldPublishPinnedState(
                isScrolling = false,
                pinned = true,
                isProgrammaticScroll = true
            )
        )
    }

    @Test
    fun `initial history overlay only covers a cold baseline load`() {
        assertTrue(
            shouldShowInitialHistoryOverlay(
                isLoading = true,
                isLoadingOlder = false,
                hasLocalContent = false,
                coldLoadStarted = false
            )
        )
        assertFalse(
            shouldShowInitialHistoryOverlay(
                isLoading = true,
                isLoadingOlder = false,
                hasLocalContent = true,
                coldLoadStarted = false
            )
        )
        assertFalse(
            shouldShowInitialHistoryOverlay(
                isLoading = true,
                isLoadingOlder = true,
                hasLocalContent = true,
                coldLoadStarted = true
            )
        )
        assertFalse(
            shouldShowInitialHistoryOverlay(
                isLoading = false,
                isLoadingOlder = false,
                hasLocalContent = false,
                coldLoadStarted = true
            )
        )
        assertTrue(
            shouldShowInitialHistoryOverlay(
                isLoading = true,
                isLoadingOlder = false,
                hasLocalContent = true,
                coldLoadStarted = true
            )
        )
    }

    @Test
    fun `history progress text follows the ios states`() {
        assertEquals(
            "正在从 Mobile Gateway 同步会话内容…",
            historyLoadingProgressText(0, null)
        )
        assertEquals(
            "正在自动加载更早记录 · 已同步 12 个事件",
            historyLoadingProgressText(12, null)
        )
        assertEquals(
            "正在加载历史记录 · 12/20",
            historyLoadingProgressText(12, 20)
        )
    }
}
