package com.clarklevis.dsh.android

import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal const val HISTORY_HELLO = """{"kind":"hello","historyFormatVersion":3,"capabilities":["assistant-stream-v1"]}"""
internal const val HISTORY_SUBSCRIBED =
    """{"kind":"subscribed","sessionId":"s","subscriptionId":"sub","assistantStream":true}"""
internal const val HISTORY_SNAPSHOT =
    """{"kind":"session-snapshot","sessionId":"s","subscriptionId":"sub","streamId":"stream","historyFormatVersion":3,"cursor":10,"events":[{"type":"assistant/message","seq":10,"time":100,"data":{"message":{"content":[{"type":"text","text":"最新历史"}]}}}],"hasMore":true,"nextBeforeSeq":10}"""
internal const val EMPTY_HISTORY_SNAPSHOT =
    """{"kind":"session-snapshot","sessionId":"s","subscriptionId":"sub","streamId":"stream","historyFormatVersion":3,"cursor":0,"events":[],"hasMore":false}"""
internal fun AndroidGatewayProjection.frame(raw: String, correlatedSessionId: String? = null) =
    acceptFrame(raw, GatewayWireDecoder.decode(raw), correlatedSessionId)

class AndroidHistoryRecoveryTest {
    @Test
    fun rc2WaitsForMatchingSnapshotAndPublishesContentWithLoadingCompletion() {
        val requests = mutableListOf<String>()
        val projection = AndroidGatewayProjection(onHistoryPageRequested = { id, _, _ -> requests += id })
        projection.frame(HISTORY_HELLO)
        assertTrue(projection.selectSession("s").selectedHistoryIsLoading)
        assertTrue(requests.isEmpty())
        projection.frame(HISTORY_SUBSCRIBED)
        assertTrue(projection.frame(HISTORY_SNAPSHOT.replace("\"sub\"", "\"old\"")).selectedHistoryIsLoading)
        val ready = projection.frame(HISTORY_SNAPSHOT)
        assertFalse(ready.selectedHistoryIsLoading)
        assertEquals("最新历史", ready.conversation.single().text)
        assertTrue(ready.selectedHistoryHasMore)
        assertNull(ready.lastError)
        assertFalse(projection.catchUpSelectedHistoryAfterReconnect().selectedHistoryIsLoading)
        projection.close()
    }

    @Test
    fun smallOpeningPagesBackwardWithoutRollingBackLiveCursorOrDuplicatingRows() {
        val cursors = mutableListOf<Int?>()
        val projection = AndroidGatewayProjection(onHistoryPageRequested = { _, before, _ -> cursors += before })
        projection.frame(HISTORY_HELLO)
        projection.selectSession("s")
        projection.frame(HISTORY_SUBSCRIBED)
        projection.frame(HISTORY_SNAPSHOT.replace("\"cursor\":10", "\"cursor\":15"))
        projection.loadHistory("s", older = true)
        assertEquals(listOf(10), cursors)
        val ready = projection.frame(
            """{"kind":"history","sessionId":"s","historyFormatVersion":3,"events":[{"type":"user/message","seq":5,"time":90,"data":{"content":[{"type":"text","text":"更早的问题"}]}},{"type":"assistant/message","seq":10,"time":100,"data":{"message":{"content":[{"type":"text","text":"过期的重叠回复"}]}}}],"hasMore":false}""",
            correlatedSessionId = "s"
        )
        assertNull(ready.lastError)
        assertEquals(listOf("更早的问题", "最新历史"), ready.conversation.map { it.text })
        assertFalse(ready.selectedHistoryHasMore)
        assertFalse(ready.selectedHistoryIsLoading)
        val live = projection.frame(
            """{"kind":"event","sessionId":"s","subscriptionId":"sub","streamId":"stream","seq":16,"time":110,"event":{"type":"user/message","text":"新问题"}}"""
        )
        assertEquals(listOf("更早的问题", "最新历史", "新问题"), live.conversation.map { it.text })
        assertNull(live.lastError)
        projection.close()
    }

    @Test
    fun failedOpeningPublishesHistoryErrorAndRetryCanRecover() {
        val projection = AndroidGatewayProjection()
        projection.frame(HISTORY_HELLO)
        projection.selectSession("s")
        projection.frame(HISTORY_SUBSCRIBED)
        val failed = projection.frame(
            """{"kind":"session-stream-reset","sessionId":"s","subscriptionId":"sub","streamId":"opening","retrying":false,"message":"Host refuses this format v0 Session"}"""
        )
        assertFalse(failed.selectedHistoryIsLoading)
        assertNotNull(failed.selectedHistoryError)
        assertTrue(failed.conversation.isEmpty())
        assertNull(projection.loadHistory("s", older = false).selectedHistoryError)
        projection.frame(HISTORY_SUBSCRIBED)
        assertNull(projection.frame(HISTORY_SNAPSHOT).selectedHistoryError)
        projection.close()
    }

    @Test
    fun emptySnapshotAndCancellationDoNotLeaveLoadingForever() {
        val projection = AndroidGatewayProjection()
        projection.frame(HISTORY_HELLO)
        projection.selectSession("s")
        projection.frame(HISTORY_SUBSCRIBED)
        val empty = projection.frame(EMPTY_HISTORY_SNAPSHOT)
        assertFalse(empty.selectedHistoryIsLoading)
        assertTrue(empty.conversation.isEmpty())
        projection.selectSession("s")
        assertFalse(projection.disconnected().selectedHistoryIsLoading)
        projection.selectSession("s")
        projection.historyTimedOut("s")
        assertFalse(projection.snapshot().selectedHistoryIsLoading)
        assertNotNull(projection.snapshot().lastError)
        projection.selectSession("s")
        projection.frame("""{"kind":"error","sessionId":"s","requestType":"models","message":"模型读取失败"}""")
        assertTrue(projection.snapshot().selectedHistoryIsLoading)
        projection.frame("""{"kind":"error","sessionId":"s","message":"订阅失败"}""")
        assertFalse(projection.snapshot().selectedHistoryIsLoading)
        assertNull(projection.selectSession(null).selectedSessionId)
        assertNull(projection.snapshotWait)
        projection.close()
    }
}
