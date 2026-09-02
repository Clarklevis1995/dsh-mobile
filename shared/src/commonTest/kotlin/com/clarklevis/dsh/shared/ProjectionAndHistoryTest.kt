package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.projection.ConversationItemKind
import com.clarklevis.dsh.shared.projection.ConversationProjectionLabels
import com.clarklevis.dsh.shared.projection.ConversationProjector
import com.clarklevis.dsh.shared.projection.TrajectoryNodeKind
import com.clarklevis.dsh.shared.projection.TrajectoryProjection
import com.clarklevis.dsh.shared.protocol.GatewayEvent
import com.clarklevis.dsh.shared.protocol.JsonValue
import com.clarklevis.dsh.shared.protocol.SessionEvent
import com.clarklevis.dsh.shared.sync.HistoryAction
import com.clarklevis.dsh.shared.sync.HistoryEventMerger
import com.clarklevis.dsh.shared.sync.HistoryFailureCode
import com.clarklevis.dsh.shared.sync.HistoryReducer
import com.clarklevis.dsh.shared.sync.HistoryResult
import com.clarklevis.dsh.shared.sync.HistorySessionState
import com.clarklevis.dsh.shared.sync.HistoryState
import com.clarklevis.dsh.shared.sync.HistorySyncEngine
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProjectionAndHistoryTest {
    @Test
    fun conversationProjectionJoinsChunksAndFinalMessageReplacesStream() {
        val projector = ConversationProjector()
        projector.fold(
            listOf(
                event(1, GatewayEvent("assistant/chunk", turn = 1, step = 1, chunkType = "text-delta", text = "Hel")),
                event(2, GatewayEvent("assistant/chunk", turn = 1, step = 1, chunkType = "text-delta", text = "lo"))
            )
        )
        assertEquals("Hello", projector.items.single().text)
        projector.fold(listOf(event(3, GatewayEvent("assistant/message", turn = 1, step = 1, text = "Hello!"))))
        assertEquals(1, projector.items.size)
        assertEquals("stream-text-1-1", projector.items.single().id)
        assertEquals("Hello!", projector.items.single().text)
        assertEquals(ConversationItemKind.ASSISTANT, projector.items.single().kind)
    }

    @Test
    fun commandLifecycleUpdatesOnePersistentStatusRowInRealTime() {
        val projector = ConversationProjector(
            ConversationProjectionLabels(
                commandRunning = "正在执行…",
                commandCompacting = "正在压缩…",
                commandCompleted = "已完成",
                commandFailed = "执行失败",
                compactedHistory = "已压缩 {items} 条历史记录（约 {tokens} tokens）"
            )
        )

        val started = projector.foldWithOperations(
            listOf(event(1, GatewayEvent("command/run", commandId = "cmd-1", name = "compact")))
        )
        assertEquals("insert", started.single().kind)
        assertEquals("正在执行…", projector.items.single().text)

        val compacting = projector.foldWithOperations(
            listOf(event(2, GatewayEvent("compaction/start", compactionId = "cmp-1", sourceCommandId = "cmd-1")))
        )
        assertEquals("replace", compacting.single().kind)
        assertEquals("正在压缩…", projector.items.single().text)

        projector.fold(
            listOf(event(3, GatewayEvent(
                "compaction/summary",
                compactionId = "cmp-1",
                sourceCommandId = "cmd-1",
                shadowedItemCount = 11,
                shadowedTokenCount = 3441
            )))
        )
        projector.fold(
            listOf(event(4, GatewayEvent(
                "command/done",
                commandId = "cmd-1",
                outcome = "success",
                text = "Compacted 11 history items (~3441 tokens)."
            )))
        )

        assertEquals(1, projector.items.size)
        assertEquals(ConversationItemKind.STATUS, projector.items.single().kind)
        assertEquals("compact", projector.items.single().title)
        assertEquals("已压缩 11 条历史记录（约 3441 tokens）", projector.items.single().text)
    }

    @Test
    fun historyRebasePrefersLiveDuplicateAndAppendsOnlyNewTail() {
        val history = listOf(event(1, GatewayEvent("user/message", text = "history")))
        val current = listOf(event(1, GatewayEvent("user/message", text = "live")), event(2, GatewayEvent("assistant/message", text = "two")))
        val rebase = com.clarklevis.dsh.shared.projection.ConversationHistoryRebase.build(history, current)
        assertEquals("live", rebase.events.first().event.text)
        val appended = rebase.appendingLiveTail(current + event(3, GatewayEvent("assistant/message", text = "three")))
        assertEquals(listOf(1, 2, 3), appended.events.map { it.seq })
    }

    @Test
    fun trajectoryProjectionBuildsRequestAssistantAndToolNodes() {
        val usage = JsonValue.ObjectValue(mapOf("outputTokens" to JsonValue.NumberValue(12.0)))
        val source = listOf(
            event(1, GatewayEvent("user/message", text = "执行")),
            event(2, GatewayEvent("assistant/message", turn = 1, step = 1, text = "开始", usage = usage)),
            event(3, GatewayEvent("tool/call", turn = 1, step = 1, callId = "call-1", name = "shell", arguments = JsonValue.ObjectValue(mapOf("cmd" to JsonValue.StringValue("pwd"))))),
            event(4, GatewayEvent("tool/result", turn = 1, step = 1, callId = "call-1", preview = "/tmp"))
        )
        val nodes = TrajectoryProjection.make(source)
        assertTrue(nodes.any { it.kind == TrajectoryNodeKind.REQUEST && it.request?.usage?.output == 12 })
        assertTrue(nodes.any { it.kind == TrajectoryNodeKind.ASSISTANT })
        assertTrue(nodes.any { it.kind == TrajectoryNodeKind.TOOL && it.subtitle.contains("/tmp") })
    }

    @Test
    fun historyReducerRejectsCursorLoopAndMergerKeepsSequenceOrder() {
        var state = HistoryState(sessions = mapOf("s1" to HistorySessionState(hasMore = true, nextBeforeSequence = 100)))
        state = HistoryReducer.reduce(state, HistoryAction.Start("s1", older = true, hasLocalEvents = true, earliestLocalSequence = 100)).state
        val reduction = HistoryReducer.reduce(
            state,
            HistoryAction.PageCommitted("s1", 10, 100, true, 100, 100, null)
        )
        val failure = assertIs<HistoryResult.Failed>(reduction.result)
        assertEquals(HistoryFailureCode.REPEATED_CURSOR, failure.code)
        assertFalse(reduction.state.sessions.getValue("s1").isLoading)

        var events = listOf(event(1, GatewayEvent("user/message", text = "one")), event(3, GatewayEvent("assistant/message", text = "old")))
        events = HistoryEventMerger.merge(event(2, GatewayEvent("assistant/message", text = "two")), events).events
        events = HistoryEventMerger.merge(event(3, GatewayEvent("assistant/message", text = "new")), events).events
        assertEquals(listOf(1, 2, 3), events.map { it.seq })
        assertEquals("new", events.last().event.text)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun historySyncEngineInvalidatesProcessingTimeoutAndFiresCurrentTimeout() = runTest {
        val engine = HistorySyncEngine(scope = this)
        var timeouts = 0
        engine.beginRequest("s1", 100) { timeouts += 1 }
        val processing = engine.beginProcessing("s1")
        advanceTimeBy(150)
        runCurrent()
        assertEquals(0, timeouts)
        assertTrue(engine.isCurrent(processing, "s1"))

        engine.beginRequest("s1", 100) { timeouts += 1 }
        advanceTimeBy(101)
        runCurrent()
        assertEquals(1, timeouts)
        assertFalse(engine.isActive("s1"))
        engine.close()
    }

    private fun event(sequence: Int, gatewayEvent: GatewayEvent) =
        SessionEvent("s1", sequence, sequence.toDouble(), gatewayEvent)
}
