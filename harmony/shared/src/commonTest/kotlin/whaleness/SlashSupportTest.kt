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

class SlashSupportTest {
    private val engine = HarmonyEngine()

    private fun command(value: HarmonyCommand): HarmonyResult = wireJson.decodeFromString(engine.dispatch(wireJson.encodeToString(value)))

    private fun frame(value: String) = command(HarmonyCommand("frame", frame = value))

    private fun ready() {
        command(HarmonyCommand("prepare", endpoint = "ws://127.0.0.1/ws/mobile"))
        frame("""{"kind":"hello","authenticated":true,"capabilities":["commands"]}""")
        command(HarmonyCommand("select", sessionId = "s"))
        command(HarmonyCommand("slash-input", sessionId = "s", text = "/", draftRevision = 1))
        frame(
            """{"kind":"commands","sessionId":"s","groups":[{"id":"g","title":"Host commands","items":[{"id":"input","name":"anything","description":"input","ui":{"kind":"input","insertText":"/anything ","submitRequest":"command-execute"}},{"id":"selection","name":"unrelated","description":"select","ui":{"kind":"select","optionsRequest":"command-options","selectionRequest":"command-select"}},{"id":"immediate","name":"arbitrary","description":"now","ui":{"kind":"immediate","submitRequest":"message","submitText":"exact Host text"}}]}]}""",
        )
    }

    @Test fun hostMetadataDeterminesImmediateActionWithoutGuessingCommandName() {
        ready()
        val chosen = command(HarmonyCommand("slash-pick", sessionId = "s", text = "/ar", itemId = "immediate", draftRevision = 2))
        val payload = wireJson.parseToJsonElement(chosen.outbound.single().text).jsonObject
        assertEquals("message", payload["type"]!!.jsonPrimitive.content)
        assertEquals("exact Host text", payload["text"]!!.jsonPrimitive.content)
        assertEquals("/ar", chosen.draftMutation!!.expectedText)
        assertEquals(2, chosen.draftMutation!!.expectedRevision)
        assertEquals("", chosen.draftMutation!!.replacement)
    }

    @Test fun typedInputExecutesOnlyDiscoveredCommandAndReceiptTargetsOriginalSession() {
        ready()
        val selected = command(HarmonyCommand("slash-pick", sessionId = "s", itemId = "input", text = "/any", draftRevision = 2))
        assertEquals("/anything ", selected.draftMutation!!.replacement)
        val sending = command(HarmonyCommand("send", text = "/anything 参数", draftRevision = 3))
        assertTrue(
            sending.outbound
                .single()
                .text
                .contains("\"command-execute\""),
        )
        assertEquals("s", sending.state!!.pendingMessageSessionId)
        command(HarmonyCommand("select", sessionId = "other"))
        val received = frame("""{"kind":"command-executed","sessionId":"s"}""")
        assertTrue(received.sent)
        assertEquals("s", received.sentSessionId)
        assertEquals("other", received.state!!.selectedSessionId)
        assertFalse(frame("""{"kind":"command-executed","sessionId":"s"}""").sent)
    }

    @Test fun lateOptionReceiptUsesOriginalDraftRevisionAndCannotChangeAnotherSession() {
        ready()
        command(HarmonyCommand("slash-pick", sessionId = "s", itemId = "selection", text = "/un", draftRevision = 2))
        frame("""{"kind":"command-options","sessionId":"s","command":"unrelated","options":[{"id":"p","label":"choice"}]}""")
        val selected = command(HarmonyCommand("slash-option", sessionId = "s", itemId = "p", text = "/unrelated", draftRevision = 3))
        assertEquals(3, selected.draftMutation!!.expectedRevision)
        command(HarmonyCommand("slash-input", sessionId = "s", text = "new draft", draftRevision = 5))
        val received =
            frame(
                """{"kind":"command-selected","sessionId":"s","command":"unrelated","selected":{"id":"p","label":"choice"},"value":{"provider":"p","model":"m"}}""",
            )
        assertEquals(3, received.draftMutation!!.expectedRevision)
        assertEquals("/unrelated", received.draftMutation!!.expectedText)
        assertFalse(received.slash!!.selecting)
        command(HarmonyCommand("select", sessionId = "other"))
        assertNull(
            frame(
                """{"kind":"command-selected","sessionId":"s","command":"unrelated","selected":{"id":"p","label":"choice"}}""",
            ).draftMutation,
        )
    }

    @Test fun timeoutRejectsLateExecutionReceiptAndNeverResendsBusinessCommand() {
        ready()
        command(HarmonyCommand("slash-pick", sessionId = "s", itemId = "input", text = "/any"))
        val sending = command(HarmonyCommand("send", text = "/anything 参数"))
        val key = sending.state!!.pendingOperations.first { it.startsWith("features:slash-execute:") }
        val timedOut = command(HarmonyCommand("operation-timeout", requestKey = key))
        assertTrue(timedOut.slash!!.uncertain)
        assertTrue(timedOut.outbound.isEmpty())
        assertFalse(frame("""{"kind":"command-executed","sessionId":"s"}""").sent)
        val retry = command(HarmonyCommand("send", text = "new message"))
        assertFalse(retry.ok)
        assertTrue(retry.outbound.isEmpty())
    }

    @Test fun missingCatalogCannotSilentlyTurnExecutableInputIntoOrdinaryMessage() {
        command(HarmonyCommand("prepare", endpoint = "ws://127.0.0.1/ws/mobile"))
        frame("""{"kind":"hello","capabilities":["commands"]}""")
        command(HarmonyCommand("select", sessionId = "s"))
        val blocked = command(HarmonyCommand("send", text = "/not-loaded 参数"))
        assertFalse(blocked.ok)
        assertTrue(
            blocked.outbound
                .single()
                .text
                .contains("\"commands\""),
        )
        assertFalse(blocked.outbound.any { it.text.contains("\"type\":\"message\"") })
    }
}
