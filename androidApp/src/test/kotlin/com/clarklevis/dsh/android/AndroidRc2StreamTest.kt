package com.clarklevis.dsh.android

import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder
import org.junit.Assert.*
import org.junit.Test

class AndroidRc2StreamTest {
    @Test
    fun invisiblePersistentEventsDoNotBreakFollowingTransientUpdates() {
        val projection = ready()
        projection.deliver(snapshot)
        projection.deliver("""{"kind":"event","sessionId":"s","subscriptionId":"sub","streamId":"stream","seq":42,"time":100,"event":{"type":"step/start","turn":2,"step":3}}""")
        assertNull(projection.snapshot().lastError)
        projection.deliver(delta)
        assertEquals("Hello world", projection.snapshot().conversation.single().text)
        assertNull(projection.snapshot().lastError)
        projection.deliver("""{"kind":"session-stream-reset","sessionId":"s","subscriptionId":"sub","streamId":"stream","retrying":true}""")
        assertTrue(projection.snapshot().conversation.isEmpty())
        assertNull(projection.snapshot().lastError)
    }

    @Test
    fun snapshotPrefixDuplicateDeltaAndCommitProduceOneReply() {
        val projection = ready()
        projection.deliver(snapshot)
        assertEquals("Hello", projection.snapshot().conversation.single().text)
        projection.deliver(delta)
        projection.deliver(delta)
        assertEquals("Hello world", projection.snapshot().conversation.single().text)
        assertTrue(projection.trajectory("s").all { it.records.isEmpty() }) // 临时帧不进入历史。
        projection.deliver(final)
        projection.deliver(final)
        assertEquals("Hello world", projection.snapshot().conversation.single().text)
        assertNull(projection.snapshot().lastError)
        projection.deliver(end)
        assertEquals(1, projection.snapshot().conversation.size)
        assertNull(projection.snapshot().lastError)
    }

    @Test
    fun olderHistoryPreservesActivePrefixAndLateLatestCannotRollbackSnapshot() {
        val projection = ready()
        projection.deliver(snapshot)
        projection.deliver(delta)
        projection.deliver("""{"kind":"history","sessionId":"s","historyFormatVersion":3,"events":[],"hasMore":false}""")
        assertEquals("Hello world", projection.snapshot().conversation.single().text)
        projection.loadHistory("s", older = true)
        projection.deliver("""{"kind":"history","sessionId":"s","historyFormatVersion":3,"events":[{"type":"user/message","seq":1,"time":1,"data":{"content":[{"type":"text","text":"Hi"}]}}],"hasMore":false}""")
        assertEquals(listOf("Hi", "Hello world"), projection.snapshot().conversation.map { it.text })
        assertNull(projection.snapshot().lastError)
    }

    @Test
    fun resetAndSwitchRemoveTemporaryRowsAndRejectLateFrames() {
        val projection = ready()
        projection.deliver(snapshot)
        projection.deliver("""{"kind":"session-stream-reset","sessionId":"s","subscriptionId":"sub","streamId":"stream","retrying":false,"message":"Unsupported session"}""")
        assertTrue(projection.snapshot().conversation.isEmpty())
        assertEquals("Unsupported session", projection.snapshot().lastError)
        projection.deliver(delta)
        assertTrue(projection.snapshot().conversation.isEmpty())
        projection.deliver(snapshot.replace("\"streamId\":\"stream\"", "\"streamId\":\"new\""))
        projection.selectSession("other")
        projection.deliver(delta)
        projection.selectSession("s")
        assertTrue(projection.snapshot().conversation.isEmpty())
    }

    @Test
    fun reconnectSnapshotCanReplaceWithEarlierCursorAndEmptyGoalBaselineClearsValues() {
        val projection = ready()
        projection.deliver(snapshot)
        projection.deliver(final)
        projection.deliver(snapshot.replace("\"cursor\":41", "\"cursor\":20"))
        assertEquals("Hello", projection.snapshot().conversation.single().text)
        assertNull(projection.snapshot().lastError)
        projection.deliver("""{"kind":"tasks-updated","sessionId":"s","asOfSeq":10,"todos":[{"content":"Old","status":"pending"}]}""")
        projection.deliver("""{"kind":"projection-baseline","projections":{}}""")
        assertNull(projection.snapshot().taskSnapshot)
    }

    private fun ready() = AndroidGatewayProjection().apply {
        selectSession("s")
        deliver("""{"kind":"hello","historyFormatVersion":3,"capabilities":["assistant-stream-v1"]}""")
        deliver("""{"kind":"subscribed","sessionId":"s","subscriptionId":"sub","assistantStream":true}""")
    }
    private fun AndroidGatewayProjection.deliver(json: String) = acceptFrame(json, GatewayWireDecoder.decode(json), "s")
    private val snapshot = """{"kind":"session-snapshot","sessionId":"s","subscriptionId":"sub","streamId":"stream","historyFormatVersion":3,"cursor":41,"hasMore":true,"nextBeforeSeq":20,"events":[],"assistantStream":{"revision":2,"activeAttempt":{"attemptId":"s:1","turn":2,"step":3,"nextIndex":1,"startedAfterSeq":41,"stream":[{"type":"text-chunks","index":0,"time0":100,"texts":["Hello"],"dt":[]}]}}}"""
    private val delta = """{"kind":"assistant-stream","sessionId":"s","subscriptionId":"sub","streamId":"stream","frame":{"type":"chunk","attemptId":"s:1","revision":3,"index":1,"time":101,"turn":2,"step":3,"chunk":{"type":"text-delta","index":0,"text":" world"}}}"""
    private val final = """{"kind":"event","sessionId":"s","subscriptionId":"sub","streamId":"stream","seq":42,"time":102,"event":{"type":"assistant/message","turn":2,"step":3,"text":"Hello world"}}"""
    private val end = """{"kind":"assistant-stream","sessionId":"s","subscriptionId":"sub","streamId":"stream","frame":{"type":"end","attemptId":"s:1","revision":4,"index":2,"turn":2,"step":3,"outcome":{"kind":"committed","eventType":"assistant/message","seq":42}}}"""
}
