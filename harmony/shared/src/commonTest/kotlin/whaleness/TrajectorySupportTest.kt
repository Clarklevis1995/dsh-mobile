package whaleness

import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrajectorySupportTest {
    private val engine = HarmonyEngine()

    private fun command(value: HarmonyCommand): HarmonyResult = wireJson.decodeFromString(engine.dispatch(wireJson.encodeToString(value)))

    private fun frame(value: String) = command(HarmonyCommand("frame", frame = value, nowMillis = 100_000))

    private fun prepare() {
        command(HarmonyCommand("prepare", endpoint = "ws://127.0.0.1/ws/mobile"))
        frame("""{"kind":"hello","authenticated":true}""")
        command(HarmonyCommand("select", sessionId = "s"))
    }

    @Test fun onlyExplicitReadBuildsTrajectoryAndDetailKeepsSourceSequence() {
        prepare()
        val history =
            frame(
                """{"kind":"history","sessionId":"s","hasMore":false,"events":[{"seq":1,"time":100,"type":"user/message","data":{"content":[{"type":"text","text":"question"}]}},{"seq":2,"time":101,"type":"assistant/message","data":{"turn":1,"step":1,"message":{"content":[{"type":"text","text":"answer"}]}}}]}""",
            )
        assertNull(history.trajectory)
        val rows = command(HarmonyCommand("trajectory", sessionId = "s")).trajectory!!
        assertTrue(rows.nodes.any { it.kind == "INPUT" && it.subtitle == "question" })
        val assistant = rows.nodes.first { it.kind == "ASSISTANT" }
        assertEquals(2, assistant.endSequence)
        val detail = command(HarmonyCommand("trajectory-detail", sessionId = "s", itemId = assistant.id)).trajectory!!
        assertEquals(assistant.id, detail.detailId)
        assertTrue(detail.detail!!.contains("answer"))
        assertTrue(detail.nodes.isEmpty())
    }

    @Test fun lateReadCannotCrossSelectedSessionBoundary() {
        prepare()
        command(HarmonyCommand("select", sessionId = "other"))
        val result = command(HarmonyCommand("trajectory", sessionId = "s"))
        assertFalse(result.ok)
        assertNull(result.trajectory)
    }

    @Test fun reconnectToAnotherHostClearsPreviousProjectionSources() {
        prepare()
        frame(
            """{"kind":"history","sessionId":"s","hasMore":false,"events":[{"seq":1,"time":100,"type":"user/message","data":{"content":[{"type":"text","text":"old-host"}]}}]}""",
        )
        assertTrue(command(HarmonyCommand("trajectory", sessionId = "s")).trajectory!!.nodes.isNotEmpty())
        prepare()
        assertTrue(command(HarmonyCommand("trajectory", sessionId = "s")).trajectory!!.nodes.isEmpty())
    }
}
