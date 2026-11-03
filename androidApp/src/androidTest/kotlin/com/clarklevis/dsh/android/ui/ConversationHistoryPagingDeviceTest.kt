package com.clarklevis.dsh.android.ui

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import com.clarklevis.dsh.android.AndroidSharedStateHolder
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource

class ConversationHistoryPagingDeviceTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val locale = object : ExternalResource() {
        private lateinit var previous: Locale
        override fun before() { previous = Locale.getDefault(); Locale.setDefault(Locale.SIMPLIFIED_CHINESE) }
        override fun after() { Locale.setDefault(previous) }
    }

    @Test fun edgeShowsLoadingImmediatelyAndPrependedPagePreservesReadingPixel() {
        val holder = showConversation()
        try {
            beginPage()
            val before = top("s-101")
            compose.onNodeWithTag("history-paging-progress").assertIsDisplayed()
            assertEquals(100, holder.snapshot.selectedHistoryEarliestSequence)
            compose.runOnIdle { holder.frame(history(60..99, hasMore = true)) }
            waitForPresentation()
            assertEquals(60, holder.snapshot.selectedHistoryEarliestSequence)
            assertEquals(before, top("s-101"), 1f)
            // 再读上一页时，已读正文仍然保持，而非回到刚加载窗口的第一条。
            compose.onNodeWithTag("conversation-message-list").performScrollToIndex(0)
            beginPage()
            val second = top("s-61")
            compose.runOnIdle { holder.frame(history(20..59, hasMore = false)) }
            waitForPresentation()
            assertEquals(second, top("s-61"), 1f)
        } finally { compose.runOnIdle { holder.close() } }
    }

    @Test fun historyReceivedDuringTouchWaitsButLoadingInformationStaysVisible() {
        val holder = showConversation()
        try {
            beginPage()
            val before = top("s-101")
            val list = compose.onNodeWithTag("conversation-message-list")
            list.performTouchInput { down(center) }
            compose.runOnIdle { holder.frame(history(60..99, hasMore = true)) }
            compose.mainClock.advanceTimeBy(250)
            compose.onNodeWithTag("history-paging-progress").assertIsDisplayed()
            assertEquals(before, top("s-101"), 1f)
            list.performTouchInput { up() }
            waitForPresentation()
            assertEquals(before, top("s-101"), 1f)
        } finally { compose.runOnIdle { holder.close() } }
    }

    @Test fun quickTouchDuringPreparationCannotLeaveThePendingTargetStranded() {
        val holder = showConversation()
        try {
            beginPage()
            val before = top("s-101")
            val list = compose.onNodeWithTag("conversation-message-list")
            list.performTouchInput { down(center) }
            compose.runOnIdle { holder.frame(history(60..99, hasMore = true)) }
            list.performTouchInput { up() }
            waitForPresentation()
            assertEquals(before, top("s-101"), 1f)
        } finally { compose.runOnIdle { holder.close() } }
    }

    @Test fun failedOrEmptyPageKeepsExistingMessagesAndCanRetry() {
        val holder = showConversation()
        try {
            beginPage()
            val before = top("s-101")
            compose.runOnIdle {
                holder.frame("""{"kind":"error","sessionId":"s","requestType":"history","message":"fixture failure"}""")
            }
            waitForPresentation()
            compose.onNodeWithTag("history-load-more").assertIsDisplayed()
            captureVisual("edge-retry-text")
            compose.onNodeWithTag("history-load-more").performClick()
            compose.onNodeWithTag("history-paging-progress").assertIsDisplayed()
            compose.runOnIdle { holder.frame(history(IntRange.EMPTY, hasMore = false)) }
            waitForPresentation()
            assertEquals(before, top("s-101"), 1f)
            compose.onNodeWithTag("history-start-reached").assertDoesNotExist()
            compose.onNodeWithTag("history-load-more").assertDoesNotExist()
            assertTrue(holder.snapshot.conversation.isNotEmpty())
        } finally { compose.runOnIdle { holder.close() } }
    }

    @Test fun mergedReasoningGroupKeepsTheVisibleMarkdownBody() {
        val reasoning = """{"type":"assistant/chunk","seq":100,"time":100,"data":{"chunkType":"reasoning-delta","turn":1,"step":1,"text":"原来的思考"}}"""
        val assistant = """{"type":"assistant/message","seq":101,"time":101,"data":{"turn":1,"step":2,"message":{"content":[{"type":"text","text":"**测试表格**\n\n|项目|值|\n|---|---|\n|原正文|保持阅读位置|\n\n这里是保持稳定的正文。"}]}}}"""
        val holder = showConversation(listOf(reasoning, assistant, events(102..119)).joinToString(","))
        try {
            beginPage()
            val bodyId = "assistant:s-101:markdown:0"
            val before = top(bodyId)
            val older = """{"type":"assistant/chunk","seq":99,"time":99,"data":{"chunkType":"reasoning-delta","turn":1,"step":1,"text":"新增的更早思考"}}"""
            compose.runOnIdle { holder.frame("""{"kind":"history","sessionId":"s","historyFormatVersion":3,"events":[$older],"hasMore":false}""") }
            waitForPresentation()
            assertEquals(before, top(bodyId), 1f)
        } finally { compose.runOnIdle { holder.close() } }
    }

    @Test fun metadataOnlyPageCannotLeavePreparingForever() {
        val holder = showConversation()
        try {
            beginPage()
            val before = top("s-101")
            compose.runOnIdle { holder.frame("""{"kind":"history","sessionId":"s","historyFormatVersion":3,"events":[{"type":"diagnostic/ignored","seq":60,"time":60,"data":{}}],"hasMore":false}""") }
            waitForPresentation()
            assertEquals(60, holder.snapshot.selectedHistoryEarliestSequence)
            assertEquals(before, top("s-101"), 1f)
            compose.onNodeWithTag("history-start-reached").assertDoesNotExist()
            compose.onNodeWithTag("history-load-more").assertDoesNotExist()
        } finally { compose.runOnIdle { holder.close() } }
    }

    @Test fun switchingSessionDuringPagingCannotRevealPreviousContentOrSpinner() {
        val holder = showConversation()
        try {
            beginPage()
            compose.runOnIdle {
                holder.selectSession("b")
                holder.frame("""{"kind":"subscribed","sessionId":"b","subscriptionId":"b-sub","assistantStream":true}""")
                holder.frame("""{"kind":"session-snapshot","sessionId":"b","subscriptionId":"b-sub","streamId":"b-stream","historyFormatVersion":3,"cursor":1,"events":[{"type":"user/message","seq":1,"time":1,"data":{"content":[{"type":"text","text":"新会话"}]}}],"hasMore":false}""")
                holder.frame(history(60..99, hasMore = true))
            }
            waitForPresentation()
            compose.onNodeWithTag("conversation-entry:b-1").assertIsDisplayed()
            compose.onNodeWithTag("conversation-entry:s-101").assertDoesNotExist()
        } finally { compose.runOnIdle { holder.close() } }
    }

    @Test fun entryIsHiddenOnOpenAndMerelyReachingTheEdgeDoesNotLoad() {
        val holder = showConversation()
        try {
            compose.onNodeWithTag("history-load-more").assertDoesNotExist()
            compose.onNodeWithTag("history-paging-progress").assertDoesNotExist()
            compose.onNodeWithTag("conversation-message-list").performScrollToIndex(0)
            compose.waitForIdle()
            compose.onNodeWithTag("history-load-more").assertDoesNotExist()
            compose.onNodeWithTag("history-paging-progress").assertDoesNotExist()
            assertTrue(holder.historyPagingSessionIds.isEmpty())
            beginPage()
            assertTrue("s" in holder.historyPagingSessionIds)
            captureVisual("edge-loading")
        } finally { compose.runOnIdle { holder.close() } }
    }

    @Test fun realDraggingNearTheTopDoesNotLoadBeforeReachingTheEdge() {
        val holder = showConversation()
        try {
            compose.onNodeWithTag("conversation-message-list").performScrollToIndex(2)
            compose.onNodeWithTag("conversation-message-list").performTouchInput {
                down(center)
                moveTo(Offset(center.x, center.y + 60f), delayMillis = 400)
                up()
            }
            compose.onNodeWithTag("history-load-more").assertDoesNotExist()
            compose.onNodeWithTag("history-paging-progress").assertDoesNotExist()
            assertTrue(holder.historyPagingSessionIds.isEmpty())
        } finally { compose.runOnIdle { holder.close() } }
    }

    @Test fun continuousDragReachingTheTopCanLoadWithoutLiftingTheFinger() {
        val holder = showConversation()
        try {
            compose.onNodeWithTag("conversation-message-list").performScrollToIndex(1)
            compose.onNodeWithTag("conversation-message-list").performTouchInput {
                val start = Offset(center.x, height * 0.20f)
                down(start)
                // 分多帧先滚动正文再触边，覆盖真实拖动中列表消费位置变化的情况。
                repeat(30) { step ->
                    moveTo(Offset(start.x, start.y + height * 0.55f * (step + 1) / 30), delayMillis = 16)
                }
                up()
            }
            compose.onNodeWithTag("history-paging-progress").assertIsDisplayed()
            assertTrue("s" in holder.historyPagingSessionIds)
        } finally { compose.runOnIdle { holder.close() } }
    }

    @Test fun generatingReplyAllowsDraggingAwayFromBottomWithoutBeingPulledBack() {
        val holder = showConversation(running = true)
        try {
            val bottom = timelineScrollPosition()
            val list = compose.onNodeWithTag("conversation-message-list")
            list.performTouchInput {
                val start = Offset(center.x, height * 0.20f)
                down(start)
                repeat(30) { step ->
                    moveTo(Offset(start.x, start.y + height * 0.45f * (step + 1) / 30), delayMillis = 16)
                }
            }
            val reading = timelineScrollPosition()
            assertTrue("生成过程中应能向更早的正文滚动", reading < bottom)
            compose.runOnIdle { holder.frame(streamDelta(1, "，拖动中继续生成")) }
            assertEquals(reading, timelineScrollPosition(), 1f)
            list.performTouchInput { up() }
            compose.waitForIdle()
            val released = timelineScrollPosition()
            compose.runOnIdle { holder.frame(streamDelta(2, "，松手后继续生成")) }
            compose.waitForIdle()
            assertEquals(released, timelineScrollPosition(), 1f)
            assertTrue(holder.selectedSessionIsRunning)
            assertTrue(holder.snapshot.conversation.last().text.endsWith("松手后继续生成"))
        } finally { compose.runOnIdle { holder.close() } }
    }

    @Test fun generatingReplyAllowsDraggingAndRepeatedHistoryPagingWithoutLosingStream() {
        val holder = showConversation(running = true)
        try {
            assertTrue(holder.selectedSessionIsRunning)
            compose.onNodeWithTag("conversation-message-list").performScrollToIndex(1)
            val list = compose.onNodeWithTag("conversation-message-list")
            list.performTouchInput {
                val start = Offset(center.x, height * 0.20f)
                down(start)
                repeat(30) { step ->
                    moveTo(Offset(start.x, start.y + height * 0.55f * (step + 1) / 30), delayMillis = 16)
                }
            }
            compose.onNodeWithTag("history-paging-progress").assertIsDisplayed()
            val before = top("s-101")
            compose.runOnIdle {
                holder.frame(streamDelta(1, "，分页期间继续生成"))
                holder.frame(history(99..99, hasMore = true))
            }
            waitForNextPage(holder, 99)
            compose.runOnIdle {
                holder.frame(streamDelta(2, "，补页期间继续生成"))
                holder.frame(history(60..98, hasMore = true))
            }
            compose.mainClock.advanceTimeBy(250)
            assertEquals(before, top("s-101"), 1f)
            list.performTouchInput { up() }
            waitForPresentation()
            assertEquals(before, top("s-101"), 1f)
            assertTrue(holder.selectedSessionIsRunning)

            beginPage()
            val second = top("s-61")
            compose.runOnIdle {
                holder.frame(streamDelta(3, "，第二次分页继续生成"))
                holder.frame(history(20..59, hasMore = false))
            }
            waitForPresentation()
            assertEquals(second, top("s-61"), 1f)
            assertEquals(20, holder.snapshot.selectedHistoryEarliestSequence)
            assertTrue(holder.selectedSessionIsRunning)
            assertTrue(holder.historyPagingSessionIds.isEmpty())
            assertEquals("正在生成，分页期间继续生成，补页期间继续生成，第二次分页继续生成",
                holder.snapshot.conversation.last().text)
            compose.runOnIdle { holder.frame(streamDelta(4, "，分页后仍在生成")) }
            compose.waitForIdle()
            assertEquals(second, top("s-61"), 1f)
            assertTrue(holder.snapshot.conversation.last().text.endsWith("分页后仍在生成"))
        } finally { compose.runOnIdle { holder.close() } }
    }

    @Test fun shortConversationWithoutEarlierHistoryNeverShowsAnEndBanner() {
        val holder = showConversation(events(100..100), hasMore = false)
        try {
            compose.onNodeWithTag("conversation-message-list").performTouchInput {
                down(Offset(center.x, height * 0.25f))
                moveTo(Offset(center.x, height * 0.55f), delayMillis = 400)
                up()
            }
            compose.onNodeWithTag("history-start-reached").assertDoesNotExist()
            compose.onNodeWithTag("history-load-more").assertDoesNotExist()
            compose.onNodeWithTag("history-paging-progress").assertDoesNotExist()
            assertTrue(holder.historyPagingSessionIds.isEmpty())
            captureVisual("short-no-banner")
        } finally { compose.runOnIdle { holder.close() } }
    }

    @Test fun entryHidesWhenLeavingTheEdgeAndOnlyReappearsAfterAnotherPull() {
        val holder = showConversation()
        try {
            beginPage()
            compose.runOnIdle {
                holder.frame("""{"kind":"error","sessionId":"s","requestType":"history","message":"fixture failure"}""")
            }
            waitForPresentation()
            compose.onNodeWithTag("history-load-more").assertIsDisplayed()
            compose.onNodeWithTag("conversation-message-list").performScrollToIndex(5)
            compose.onNodeWithTag("history-load-more").assertDoesNotExist()
            compose.onNodeWithTag("conversation-message-list").performScrollToIndex(0)
            compose.onNodeWithTag("history-load-more").assertDoesNotExist()
            beginPage()
        } finally { compose.runOnIdle { holder.close() } }
    }

    @Test fun pullingAShortCachedWindowCanStillLoadEarlierHistory() {
        val holder = showConversation(events(100..100))
        try {
            beginPage()
            assertTrue("s" in holder.historyPagingSessionIds)
            compose.runOnIdle { holder.frame(history(60..99, hasMore = true)) }
            waitForPresentation()
            assertEquals(60, holder.snapshot.selectedHistoryEarliestSequence)
            compose.onNodeWithTag("history-load-more").assertDoesNotExist()
            assertTrue(holder.historyPagingSessionIds.isEmpty())
        } finally { compose.runOnIdle { holder.close() } }
    }

    @Test fun sparsePagesContinueWithoutAnotherGestureAndPreserveTheReadingPosition() {
        val holder = showConversation()
        try {
            beginPage()
            val before = top("s-101")
            val originalRange = timelineScrollRange()
            compose.runOnIdle { holder.frame(history(99..99, hasMore = true)) }
            waitForNextPage(holder, 99)
            assertEquals(originalRange, timelineScrollRange(), 0.001f)
            compose.runOnIdle { holder.frame(history(98..98, hasMore = true)) }
            waitForNextPage(holder, 98)
            assertEquals(originalRange, timelineScrollRange(), 0.001f)
            compose.runOnIdle { holder.frame(history(60..97, hasMore = true)) }
            waitForPresentation()
            assertEquals(before, top("s-101"), 1f)
            assertTrue(holder.historyPagingSessionIds.isEmpty())
            assertTrue(timelineScrollRange() > originalRange)
            assertTrue(holder.snapshot.selectedHistoryHasMore)
        } finally { compose.runOnIdle { holder.close() } }
    }

    @Test fun toolOnlyPageContinuesToReadableMessagesWithoutAnotherGesture() {
        val holder = showConversation()
        try {
            beginPage()
            val before = top("s-101")
            val tools = (40..99).joinToString(",") { seq ->
                """{"type":"tool/call","seq":$seq,"time":$seq,"data":{"name":"fixture","arguments":{"value":"tool use"}}}"""
            }
            compose.runOnIdle { holder.frame("""{"kind":"history","sessionId":"s","historyFormatVersion":3,"events":[$tools],"hasMore":true}""") }
            waitForNextPage(holder, 40)
            assertEquals(60, holder.snapshot.conversation.count {
                it.kind == com.clarklevis.dsh.shared.projection.ConversationItemKind.TOOL
            })
            compose.runOnIdle { holder.frame(history(1..39, hasMore = true)) }
            waitForPresentation()
            assertEquals(before, top("s-101"), 1f)
            assertTrue(holder.historyPagingSessionIds.isEmpty())
        } finally { compose.runOnIdle { holder.close() } }
    }

    @Test fun emptyPageWithUnchangedCursorStopsRatherThanLoopingForever() {
        val holder = showConversation()
        try {
            beginPage()
            compose.runOnIdle { holder.frame(history(IntRange.EMPTY, hasMore = true)) }
            waitForPresentation()
            compose.onNodeWithTag("history-load-more").assertIsDisplayed()
            assertTrue(holder.historyPagingSessionIds.isEmpty())
        } finally { compose.runOnIdle { holder.close() } }
    }

    @Test fun errorDuringAutomaticSupplementKeepsTheAlreadyReceivedPage() {
        val holder = showConversation()
        try {
            beginPage()
            val before = top("s-101")
            compose.runOnIdle { holder.frame(history(99..99, hasMore = true)) }
            waitForNextPage(holder, 99)
            compose.runOnIdle { holder.frame("""{"kind":"error","sessionId":"s","requestType":"history","message":"fixture failure"}""") }
            waitForPresentation()
            assertEquals(99, holder.snapshot.selectedHistoryEarliestSequence)
            assertEquals(before, top("s-101"), 1f)
            assertTrue(holder.historyPagingSessionIds.isEmpty())
            compose.onNodeWithTag("conversation-message-list").performScrollToIndex(0)
            compose.onNodeWithTag("conversation-entry:s-99").assertIsDisplayed()
        } finally { compose.runOnIdle { holder.close() } }
    }

    @Test fun automaticSupplementDuringTouchKeepsTheDisplayedWindowFrozen() {
        val holder = showConversation()
        try {
            beginPage()
            val before = top("s-101")
            val originalRange = timelineScrollRange()
            val list = compose.onNodeWithTag("conversation-message-list")
            list.performTouchInput { down(center) }
            compose.runOnIdle { holder.frame(history(99..99, hasMore = true)) }
            waitForNextPage(holder, 99)
            compose.runOnIdle { holder.frame(history(60..98, hasMore = true)) }
            compose.mainClock.advanceTimeBy(250)
            assertEquals(originalRange, timelineScrollRange(), 0.001f)
            assertEquals(before, top("s-101"), 1f)
            list.performTouchInput { up() }
            waitForPresentation()
            assertEquals(before, top("s-101"), 1f)
        } finally { compose.runOnIdle { holder.close() } }
    }

    private fun timelineScrollRange() = compose.onNodeWithTag("conversation-message-list")
        .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].maxValue()

    private fun timelineScrollPosition() = compose.onNodeWithTag("conversation-message-list")
        .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun waitForNextPage(holder: AndroidSharedStateHolder, earliest: Int) {
        compose.waitUntil(10_000) {
            holder.snapshot.selectedHistoryEarliestSequence == earliest && holder.snapshot.selectedHistoryIsLoadingOlder
        }
        compose.onNodeWithTag("history-paging-progress").assertIsDisplayed()
    }

    private fun captureVisual(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png")
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun beginPage() {
        compose.onNodeWithTag("conversation-message-list").performScrollToIndex(0)
        compose.waitForIdle()
        compose.onNodeWithTag("conversation-message-list").performTouchInput {
            // 已到顶后继续向较早记录拖动，不能用程序定位冒充用户手势。
            down(Offset(center.x, height * 0.25f))
            moveTo(Offset(center.x, height * 0.55f), delayMillis = 400)
            up()
        }
        compose.onNodeWithTag("history-paging-progress").assertIsDisplayed()
    }

    private fun top(id: String): Float = compose.onNodeWithTag("conversation-entry:$id")
        .fetchSemanticsNode().boundsInRoot.top

    private fun waitForPresentation() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("history-paging-progress").fetchSemanticsNodes().isEmpty()
        }
        compose.waitForIdle()
    }

    private fun showConversation(initialEvents: String = events(100..119), hasMore: Boolean = true, running: Boolean = false): AndroidSharedStateHolder {
        val holder = AndroidSharedStateHolder().apply {
            frame("""{"kind":"hello","historyFormatVersion":3,"capabilities":["assistant-stream-v1"]}""")
            selectSession("s")
            frame("""{"kind":"subscribed","sessionId":"s","subscriptionId":"sub","assistantStream":true}""")
            val stream = if (running) """, "assistantStream":{"revision":2,"activeAttempt":{"attemptId":"s:1","turn":2,"step":3,"nextIndex":1,"startedAfterSeq":119,"stream":[{"type":"text-chunks","index":0,"time0":120,"texts":["正在生成"],"dt":[]}]}}""" else ""
            frame("""{"kind":"session-snapshot","sessionId":"s","subscriptionId":"sub","streamId":"stream","historyFormatVersion":3,"cursor":119,"events":[$initialEvents],"hasMore":$hasMore,"nextBeforeSeq":100$stream}""")
            if (running) frame("""{"kind":"sessions","items":[{"sessionId":"s","updatedAt":120,"running":true,"blank":false}]}""")
        }
        compose.setContent { DshTheme { ConversationScreen(holder, onPickImage = {}, onBack = {}) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("conversation-message-list").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        return holder
    }

    private fun events(range: IntRange) = range.joinToString(",") { seq ->
        """{"type":"user/message","seq":$seq,"time":$seq,"data":{"content":[{"type":"text","text":"记录 $seq：这是用于检查阅读锚点的测试消息。"}],"source":{"kind":"user"}}}"""
    }
    private fun history(range: IntRange, hasMore: Boolean) =
        """{"kind":"history","sessionId":"s","historyFormatVersion":3,"events":[${events(range)}],"hasMore":$hasMore}"""
    private fun streamDelta(index: Int, text: String) =
        """{"kind":"assistant-stream","sessionId":"s","subscriptionId":"sub","streamId":"stream","frame":{"type":"chunk","attemptId":"s:1","revision":${index + 2},"index":$index,"time":${120 + index},"turn":2,"step":3,"chunk":{"type":"text-delta","index":0,"text":"$text"}}}"""
    private fun AndroidSharedStateHolder.frame(raw: String) { wirePayload = raw; submitWirePayload() }
}
