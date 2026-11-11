package whaleness

import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionFeaturesTest {
    private val engine = HarmonyEngine()

    private fun command(value: HarmonyCommand): HarmonyResult = wireJson.decodeFromString(engine.dispatch(wireJson.encodeToString(value)))

    private fun frame(value: String) = command(HarmonyCommand("frame", frame = value))

    private fun ready(): HarmonyResult {
        command(HarmonyCommand("prepare", endpoint = "ws://127.0.0.1/ws/mobile"))
        frame("""{"kind":"hello","authenticated":true,"capabilities":["session-agent-preset","queue-control"]}""")
        return command(HarmonyCommand("select", sessionId = "s"))
    }

    private fun requestId(
        result: HarmonyResult,
        type: String,
    ): String =
        result.outbound
            .map { wireJson.parseToJsonElement(it.text).jsonObject }
            .first {
                it["type"]!!.jsonPrimitive.content ==
                    type
            }["requestId"]!!
            .jsonPrimitive.content

    private fun agentReady(open: HarmonyResult) {
        frame("""{"kind":"agent-presets","presets":[{"id":"build","name":"构建"},{"id":"read","name":"阅读"}]}""")
        frame(
            """{"kind":"session-agent-preset","sessionId":"s","requestId":"${requestId(
                open,
                "session-agent-preset",
            )}","agentPreset":"build","locked":false}""",
        )
    }

    private fun queued() =
        frame(
            """{"kind":"session-queue","sessionId":"s","items":[{"id":"q1","placement":"queued","message":{"content":[{"type":"text","text":"恢复中文草稿"}]}},{"id":"attachment","placement":"queued","message":{"content":[{"type":"image","data":"fixture"}]}}]}""",
        )

    @Test fun unknownAgentBlocksSendingAndSelectionWaitsForMatchingReceipt() {
        val open = ready()
        assertTrue(open.features!!.agent.blocksSending)
        val blocked = command(HarmonyCommand("send", text = "hello"))
        assertFalse(blocked.ok)
        assertTrue(blocked.outbound.isEmpty())
        agentReady(open)
        val selecting = command(HarmonyCommand("agent-select", sessionId = "s", value = "read"))
        assertEquals("build", selecting.features!!.agent.preset)
        assertTrue(selecting.features!!.agent.blocksSending)
        assertNull(frame("""{"kind":"select-agent-preset","sessionId":"s","requestId":"wrong","agentPreset":"read"}""").features)
        val confirmed =
            frame(
                """{"kind":"select-agent-preset","sessionId":"s","requestId":"${requestId(
                    selecting,
                    "select-agent-preset",
                )}","agentPreset":"read"}""",
            )
        assertEquals("read", confirmed.features!!.agent.preset)
        assertFalse(confirmed.features!!.agent.blocksSending)
        val sending = command(HarmonyCommand("send", text = "hello"))
        assertTrue(sending.ok)
        assertTrue(sending.features!!.agent.blocksSending)
        val locked = frame("""{"kind":"sent","sessionId":"s"}""")
        assertTrue(locked.features!!.agent.locked)
        assertFalse(locked.features!!.agent.canSelect)
    }

    @Test fun confirmedQueueEditRestoresExactlyOnceToOriginalSessionAfterSwitch() {
        ready()
        val items = queued().features!!.queue
        assertTrue(items.first().editable)
        assertFalse(items.last().editable)
        val editing = command(HarmonyCommand("queue-action", sessionId = "s", itemId = "q1", value = "edit"))
        assertEquals(2, editing.features!!.queue.size)
        assertTrue(editing.restoredDrafts.isEmpty())
        assertTrue(
            editing.outbound
                .single()
                .text
                .contains("\"action\":\"remove\""),
        )
        command(HarmonyCommand("select", sessionId = "other"))
        assertTrue(
            frame(
                """{"kind":"queue-item-updated","sessionId":"other","itemId":"q1","action":"remove","accepted":true}""",
            ).restoredDrafts.isEmpty(),
        )
        val accepted = frame("""{"kind":"queue-item-updated","sessionId":"s","itemId":"q1","action":"remove","accepted":true}""")
        assertEquals(mapOf("s" to "恢复中文草稿"), accepted.restoredDrafts)
        assertTrue(
            frame(
                """{"kind":"queue-item-updated","sessionId":"s","itemId":"q1","action":"remove","accepted":true}""",
            ).restoredDrafts.isEmpty(),
        )
    }

    @Test fun queueTimeoutNeverRestoresDraftOrAllowsAmbiguousRetryUntilReconnect() {
        ready()
        queued()
        val editing = command(HarmonyCommand("queue-action", sessionId = "s", itemId = "q1", value = "edit"))
        val key = editing.state!!.pendingOperations.first { it.startsWith("features:queue:") }
        val timeout = command(HarmonyCommand("operation-timeout", requestKey = key))
        assertTrue(timeout.features!!.queueUncertain)
        assertTrue(
            frame(
                """{"kind":"queue-item-updated","sessionId":"s","itemId":"q1","action":"remove","accepted":true}""",
            ).restoredDrafts.isEmpty(),
        )
        val retry = command(HarmonyCommand("queue-action", sessionId = "s", itemId = "q1", value = "edit"))
        assertFalse(retry.ok)
        assertTrue(retry.outbound.isEmpty())
    }

    @Test fun failedSelectionRequestsActualHostStateAndOldRequestCannotOverrideIt() {
        val open = ready()
        agentReady(open)
        val selection = command(HarmonyCommand("agent-select", sessionId = "s", value = "read"))
        val failed =
            frame(
                """{"kind":"error","requestType":"select-agent-preset","sessionId":"s","requestId":"${requestId(
                    selection,
                    "select-agent-preset",
                )}","message":"请求未确认"}""",
            )
        assertTrue(failed.ok)
        val query = requestId(failed, "session-agent-preset")
        assertFalse(failed.features!!.agent.known)
        assertTrue(failed.features!!.agent.blocksSending)
        assertNull(
            frame(
                """{"kind":"select-agent-preset","sessionId":"s","requestId":"${requestId(
                    selection,
                    "select-agent-preset",
                )}","agentPreset":"read"}""",
            ).features,
        )
        val actual = frame("""{"kind":"session-agent-preset","sessionId":"s","requestId":"$query","agentPreset":"build","locked":false}""")
        assertEquals("build", actual.features!!.agent.preset)
        assertFalse(actual.features!!.agent.blocksSending)
    }

    @Test fun queueSnapshotBeforeSelectingSessionRemainsAvailableOnOpen() {
        command(HarmonyCommand("prepare", endpoint = "ws://127.0.0.1/ws/mobile"))
        frame("""{"kind":"hello","authenticated":true,"capabilities":["queue-control"]}""")
        frame(
            """{"kind":"session-queues","queues":{"s":[{"id":"early","placement":"queued","message":{"content":[{"type":"text","text":"early snapshot"}]}}]}}""",
        )
        val selected = command(HarmonyCommand("select", sessionId = "s"))
        assertEquals(
            "early",
            selected.features!!
                .queue
                .single()
                .id,
        )
        val rejected = command(HarmonyCommand("queue-action", sessionId = "s", itemId = "missing", value = "edit"))
        assertFalse(rejected.ok)
        assertTrue(rejected.outbound.isEmpty())
    }
}
