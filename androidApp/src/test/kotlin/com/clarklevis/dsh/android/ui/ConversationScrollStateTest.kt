package com.clarklevis.dsh.android.ui

import com.clarklevis.dsh.android.localization.LocaleTestRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ConversationScrollStateTest {
    @get:Rule val locale = LocaleTestRule()

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
    fun `history needs continued pulling after reaching the edge`() {
        val pull = HistoryEdgePull(threshold = 8f)
        assertFalse(pull.shouldRequest(100f, atStart = false, userInput = true, canLoad = true))
        assertFalse(pull.shouldRequest(4f, atStart = true, userInput = true, canLoad = true))
        assertTrue(pull.shouldRequest(4f, atStart = true, userInput = true, canLoad = true))
        // 同一次手势即便收包完成，也不能连续拉取下一页。
        assertFalse(pull.shouldRequest(100f, atStart = true, userInput = true, canLoad = true))
        pull.reset()
        assertTrue(pull.shouldRequest(8f, atStart = true, userInput = true, canLoad = true))
    }

    @Test
    fun `inertia programmatic motion busy state and reversed pulls do not load`() {
        val pull = HistoryEdgePull(threshold = 8f)
        assertFalse(pull.shouldRequest(100f, atStart = true, userInput = false, canLoad = true))
        assertFalse(pull.shouldRequest(100f, atStart = true, userInput = true, canLoad = false))
        assertFalse(pull.shouldRequest(4f, atStart = true, userInput = true, canLoad = true))
        assertFalse(pull.shouldRequest(-10f, atStart = true, userInput = true, canLoad = true))
        assertFalse(pull.shouldRequest(4f, atStart = true, userInput = true, canLoad = true))
        assertTrue(pull.shouldRequest(4f, atStart = true, userInput = true, canLoad = true))
    }

    @Test
    fun `initial history overlay only covers a cold baseline load`() {
        assertTrue(
            shouldShowInitialHistoryOverlay(
                isLoading = true,
                isLoadingOlder = false,
                hasLocalContent = false
            )
        )
        assertFalse(
            shouldShowInitialHistoryOverlay(
                isLoading = true,
                isLoadingOlder = false,
                hasLocalContent = true
            )
        )
        assertFalse(
            shouldShowInitialHistoryOverlay(
                isLoading = true,
                isLoadingOlder = true,
                hasLocalContent = true
            )
        )
        assertFalse(
            shouldShowInitialHistoryOverlay(
                isLoading = false,
                isLoadingOlder = false,
                hasLocalContent = false
            )
        )
        assertFalse(
            shouldShowInitialHistoryOverlay(
                isLoading = true,
                isLoadingOlder = false,
                hasLocalContent = true
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
