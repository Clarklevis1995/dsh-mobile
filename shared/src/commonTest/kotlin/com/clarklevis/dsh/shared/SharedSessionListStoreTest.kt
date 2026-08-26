package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.facade.SharedSessionListSnapshot
import com.clarklevis.dsh.shared.facade.SharedSessionListStore
import com.clarklevis.dsh.shared.facade.SharedSessionSummarySnapshot
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedSessionListStoreTest {
    private fun makeStore() = SharedSessionListStore(
        newSessionTitle = "新建会话",
        remoteSessionPrefix = "远端会话 ",
        blankSessionPrefix = "空白会话 "
    )

    @Test
    fun restoredPersistenceAndAllSessionIntentsUseOneKmpState() {
        val store = makeStore()
        val restored = SharedSessionListSnapshot(
            sessions = listOf(
                SharedSessionSummarySnapshot(
                    id = "existing",
                    title = "旧标题",
                    lastActivityEpochSeconds = 10.0,
                    isRunning = false,
                    hasUnread = true,
                    agentPreset = "keep"
                )
            ),
            selectedSessionId = "selected"
        )
        assertTrue(store.restore(wireJson.encodeToString(restored)).isSuccess)

        assertTrue(store.setArchivedSessionIds(wireJson.encodeToString(listOf("archived"))).isSuccess)
        val remote = """[
            {"sessionId":"existing","updatedAt":2000,"running":true,"blank":false,"cwd":"/tmp/existing"},
            {"sessionId":"new","updatedAt":3000,"running":false,"blank":false,"cwd":"/tmp/new","agentPreset":"standard"},
            {"sessionId":"archived","updatedAt":4000,"running":false,"blank":false,"cwd":"/tmp/archived"}
        ]"""
        var snapshot = decode(store.receiveRemoteSessions(remote).snapshotJson)
        assertEquals(listOf("new", "existing"), snapshot.sessions.map { it.id })
        assertEquals("new", snapshot.sessions.first().title)
        assertEquals("keep", snapshot.sessions.last().agentPreset)
        assertTrue(snapshot.sessions.last().isRunning)
        assertTrue(snapshot.sessions.last().hasUnread)

        val event = """{
            "sessionId":"new","seq":1,"time":3001,
            "event":{"type":"turn/start"}
        }"""
        snapshot = decode(store.receiveEvent(event, insertedAtEpochSeconds = 9_999.0).snapshotJson)
        assertEquals("new", snapshot.sessions.first().id)
        assertTrue(snapshot.sessions.first().isRunning)
        assertTrue(snapshot.sessions.first().hasUnread)

        snapshot = decode(store.selectSession("new").snapshotJson)
        assertEquals("new", snapshot.selectedSessionId)
        snapshot = decode(store.markRead("new").snapshotJson)
        assertFalse(snapshot.sessions.first().hasUnread)

        snapshot = decode(store.messageSent("created", "review", 8_000.0).snapshotJson)
        assertEquals(8_000.0, snapshot.sessions.first { it.id == "created" }.lastActivityEpochSeconds)
        assertEquals("review", snapshot.sessions.first { it.id == "created" }.agentPreset)
        snapshot = decode(store.addKnownSession("known", 8_001.0).snapshotJson)
        assertEquals(8_001.0, snapshot.sessions.first { it.id == "known" }.lastActivityEpochSeconds)
    }

    @Test
    fun malformedPlatformPayloadReturnsStructuredErrorAndKeepsState() {
        val store = makeStore()
        val initial = SharedSessionListSnapshot(
            sessions = listOf(
                SharedSessionSummarySnapshot("s1", "稳定状态", 10.0, false, false)
            )
        )
        assertTrue(store.restore(wireJson.encodeToString(initial)).isSuccess)

        val failure = store.receiveRemoteSessions("not-json")
        assertFalse(failure.isSuccess)
        assertEquals("session-list-remote-sessions-failed", failure.errorCode)
        assertNotNull(failure.errorMessage)
        assertNull(failure.snapshotJson)

        val unchanged = decode(store.snapshot().snapshotJson)
        assertEquals(initial, unchanged)
    }

    @Test
    fun unchangedStreamingEventDoesNotCopyWholeSnapshotAcrossBridge() {
        val store = makeStore()
        val initial = SharedSessionListSnapshot(
            sessions = listOf(
                SharedSessionSummarySnapshot("s1", "已有标题", 10.0, true, false)
            ),
            selectedSessionId = "s1"
        )
        assertTrue(store.restore(wireJson.encodeToString(initial)).isSuccess)

        val unchanged = store.receiveEvent(
            """{"sessionId":"s1","seq":2,"time":11,"event":{"type":"assistant/chunk","text":"delta"}}""",
            insertedAtEpochSeconds = 12.0
        )

        assertTrue(unchanged.isSuccess)
        assertNull(unchanged.snapshotJson)
        assertEquals(initial, decode(store.snapshot().snapshotJson))
    }

    @Test
    fun snapshotCatchesSerializationFailureAndFailedMutationDoesNotPoisonState() {
        val store = makeStore()
        val initial = SharedSessionListSnapshot(
            sessions = listOf(
                SharedSessionSummarySnapshot("stable", "稳定状态", 10.0, false, false)
            )
        )
        assertTrue(store.restore(wireJson.encodeToString(initial)).isSuccess)

        val failure = store.addKnownSession("invalid", Double.NaN)
        assertFalse(failure.isSuccess)
        assertEquals("session-list-known-session-failed", failure.errorCode)
        assertNull(failure.snapshotJson)

        val snapshot = store.snapshot()
        assertTrue(snapshot.isSuccess)
        assertEquals(initial, decode(snapshot.snapshotJson))
    }

    private fun decode(json: String?): SharedSessionListSnapshot =
        wireJson.decodeFromString(requireNotNull(json))
}
