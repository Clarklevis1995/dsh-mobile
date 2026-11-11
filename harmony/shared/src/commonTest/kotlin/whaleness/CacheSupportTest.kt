package whaleness

import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CacheSupportTest {
    private val engine = HarmonyEngine()

    private fun call(command: HarmonyCommand): HarmonyResult = wireJson.decodeFromString(engine.dispatch(wireJson.encodeToString(command)))

    private fun frame(json: String) = call(HarmonyCommand("frame", frame = json)).also { assertTrue(it.ok, it.error) }

    private fun prepare() {
        call(HarmonyCommand("prepare", endpoint = "ws://127.0.0.1/ws/mobile"))
    }

    private fun online() {
        prepare()
        frame("""{"kind":"hello","authenticated":true}""")
        frame(
            """{"kind":"sessions","items":[{"sessionId":"s","updatedAt":100,"running":false,"blank":false,"projections":{"values":{"title":"缓存会话"}}}]}""",
        )
        frame(
            """{"kind":"workspaces","items":[{"workspaceId":"w","title":"工作区","path":"/fixture","sessionIds":["s"],"createdAt":"2026-10-30","updatedAt":"2026-10-30"}]}""",
        )
        call(HarmonyCommand("select", sessionId = "s"))
        frame(
            """{"kind":"history","sessionId":"s","events":[{"seq":1,"time":100,"type":"user/message","data":{"content":[{"type":"text","text":"原生中文 English"}]}}],"hasMore":true,"nextBeforeSeq":1}""",
        )
    }

    @Test fun catalogNeedsBothAuthoritativeDirectoriesAndRestoresWithoutNetworkRequests() {
        prepare()
        frame("""{"kind":"hello","authenticated":true}""")
        frame("""{"kind":"sessions","items":[]}""")
        assertNull(call(HarmonyCommand("cache-export")).cache!!.catalog)
        online()
        val saved = call(HarmonyCommand("cache-export")).cache!!
        assertNotNull(saved.catalog)
        val fresh = HarmonyEngine()

        fun restore(command: HarmonyCommand): HarmonyResult = wireJson.decodeFromString(fresh.dispatch(wireJson.encodeToString(command)))
        restore(HarmonyCommand("prepare", endpoint = "ws://127.0.0.1/ws/mobile"))
        val result = restore(HarmonyCommand("cache-import-catalog", input = saved.catalog!!))
        assertTrue(result.ok)
        assertTrue(result.outbound.isEmpty())
        assertFalse(result.state!!.connected)
        assertEquals("s", result.state!!.selectedSessionId)
        assertFalse(
            result.state!!
                .sessions
                .single()
                .running,
        )
        val history = restore(HarmonyCommand("cache-import-window", sessionId = "s", input = saved.windows.single().json))
        assertTrue(history.ok)
        assertTrue(history.state!!.cachedHistory)
        assertFalse(restore(HarmonyCommand("send", text = "offline must not send")).ok)
        assertTrue(
            history.patches.any { it.replacementItems.orEmpty().any { item -> item.text == "原生中文 English" } } ||
                history.patches.flatMap { it.operations }.any { it.item?.text == "原生中文 English" },
        )
        assertNull(restore(HarmonyCommand("cache-export")).cache!!.windows.firstOrNull())
    }

    @Test fun lateDiskWindowCannotOverwriteFreshHistoryAndSessionMismatchIsRejected() {
        online()
        val saved = call(HarmonyCommand("cache-export")).cache!!.windows.single()
        assertEquals("原生中文 English", saved.documents.single().text)
        frame("""{"kind":"event","sessionId":"s","seq":2,"time":101,"event":{"type":"user/message","text":"fresh network"}}""")
        val late = call(HarmonyCommand("cache-import-window", sessionId = "s", input = saved.json))
        assertTrue(late.ok)
        assertTrue(late.patches.isEmpty())
        assertFalse(late.state!!.cachedHistory)
        assertFalse(call(HarmonyCommand("cache-import-window", sessionId = "other", input = saved.json)).ok)
        assertFalse(
            call(
                HarmonyCommand(
                    "cache-import-window",
                    sessionId = "s",
                    input =
                        JsonObject(wireJson.parseToJsonElement(saved.json).jsonObject + ("schema" to JsonPrimitive(9))).toString(),
                ),
            ).ok,
        )
    }

    @Test fun cacheBudgetKeepsOnlyStableTailAndSearchDocumentsMatchIt() {
        online()
        call(HarmonyCommand("select", sessionId = "s"))
        val events =
            (1..500).joinToString(",") {
                """{"seq":$it,"time":100,"type":"user/message","data":{"content":[{"type":"text","text":"record $it"}]}}"""
            }
        frame("""{"kind":"history","sessionId":"s","hasMore":false,"events":[$events]}""")
        val saved = call(HarmonyCommand("cache-export")).cache!!.windows.single()
        val window = wireJson.parseToJsonElement(saved.json).jsonObject
        assertEquals(400, window["events"]!!.jsonArray.size)
        assertEquals(400, saved.documents.size)
        assertEquals("record 101", saved.documents.first().text)
        assertEquals("record 500", saved.documents.last().text)
    }

    @Test fun conversationHeartbeatDoesNotDirtyClearedHistory() {
        online()
        val before = call(HarmonyCommand("cache-export")).state!!.historyRevision
        val pong = call(HarmonyCommand("frame", channel = "conversation", frame = """{"kind":"pong"}"""))
        assertTrue(pong.ok, pong.error)
        assertEquals(before, pong.state!!.historyRevision)
        val changed = frame("""{"kind":"event","sessionId":"s","seq":2,"time":101,"event":{"type":"user/message","text":"after clear"}}""")
        assertTrue(changed.state!!.historyRevision > before)
        assertTrue(
            call(HarmonyCommand("cache-export"))
                .cache!!
                .windows
                .single()
                .documents
                .any { it.text == "after clear" },
        )
    }

    @Test fun visitingManySessionsReleasesInactiveHistoryAndProjectionSources() {
        online()
        repeat(25) { index ->
            val id = "visit-$index"
            call(HarmonyCommand("select", sessionId = id))
            frame(
                """{"kind":"history","sessionId":"$id","hasMore":false,"events":[{"seq":1,"time":100,"type":"user/message","data":{"content":[{"type":"text","text":"bounded $index"}]}}]}""",
            )
            val state = call(HarmonyCommand("cache-export")).state!!
            assertTrue(state.retainedHistorySessions <= 4)
        }
    }

    @Test fun inactiveHostCanReleaseHistoryWithoutLosingSelectionOrDiskRestore() {
        online()
        val saved = call(HarmonyCommand("cache-export")).cache!!.windows.single()
        assertFalse(call(HarmonyCommand("release-history")).ok)
        call(HarmonyCommand("disconnect"))
        val released = call(HarmonyCommand("release-history"))
        assertTrue(released.ok, released.error)
        assertEquals("s", released.state!!.selectedSessionId)
        assertEquals(0, released.state!!.retainedHistorySessions)
        assertEquals(0L, released.state!!.historyMemoryBytes)
        assertTrue(released.patches.any { it.sessionId == "s" && it.replacesAll && it.replacementItems!!.isEmpty() })
        assertTrue(call(HarmonyCommand("release-history")).ok)
        val restored = call(HarmonyCommand("cache-import-window", sessionId = "s", input = saved.json))
        assertTrue(restored.ok, restored.error)
        assertTrue(restored.state!!.cachedHistory)
        assertTrue(restored.patches.any { it.replacesAll && it.replacementItems!!.any { item -> item.text == "原生中文 English" } })
    }
}
