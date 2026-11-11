package whaleness

import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ControlSupportTest {
    private val engine = HarmonyEngine()

    private fun command(value: HarmonyCommand): HarmonyResult = wireJson.decodeFromString(engine.dispatch(wireJson.encodeToString(value)))

    private fun frame(value: String) = command(HarmonyCommand("frame", frame = value))

    private fun ready() {
        command(HarmonyCommand("prepare", endpoint = "ws://127.0.0.1/ws/mobile"))
        frame("""{"kind":"hello","authenticated":true}""")
        command(HarmonyCommand("select", sessionId = "s"))
    }

    private fun models(
        sessionId: String? = null,
        model: String = "old",
    ): HarmonyResult {
        val sid = sessionId?.let { ",\"sessionId\":\"$it\"" }.orEmpty()
        return frame(
            """{"kind":"models"$sid,"routable":true,"current":{"provider":"p","model":"$model"},"groups":[{"id":"p","name":"Provider","models":[{"id":"old","name":"Old"},{"id":"next","name":"Next"}]}]}""",
        )
    }

    @Test fun modelSelectionOnlyCommitsAfterHostReceiptAndTracksSharedTokens() {
        ready()
        val opened = command(HarmonyCommand("controls-open", sessionId = "s"))
        assertEquals(4, opened.outbound.size)
        assertEquals(4, opened.state!!.pendingOperations.count { it.startsWith("controls:") })
        assertEquals(
            "old",
            models()
                .controls!!
                .models!!
                .current!!
                .model,
        )
        val selecting = command(HarmonyCommand("controls-model", sessionId = "s", provider = "p", model = "next", reasoningEffort = "high"))
        assertEquals(
            "old",
            selecting.controls!!
                .models!!
                .current!!
                .model,
        )
        assertTrue("select-model" in selecting.controls!!.loading)
        assertTrue(
            selecting.outbound
                .single()
                .text
                .contains("\"next\""),
        )
        val confirmed =
            frame("""{"kind":"select-model","sessionId":"s","selected":{"provider":"p","model":"next","reasoningEffort":"high"}}""")
        assertEquals(
            "next",
            confirmed.controls!!
                .models!!
                .current!!
                .model,
        )
        assertEquals(
            "high",
            confirmed.controls!!
                .models!!
                .current!!
                .reasoningEffort,
        )
        assertFalse("select-model" in confirmed.controls!!.loading)
    }

    @Test fun errorRollsBackAndUnrelatedSuccessfulReplyCannotEraseFailure() {
        ready()
        command(HarmonyCommand("controls-open", sessionId = "s"))
        models()
        command(HarmonyCommand("controls-model", sessionId = "s", provider = "p", model = "next"))
        val failed = frame("""{"kind":"error","requestType":"select-model","sessionId":"s","message":"模型当前不可用"}""")
        assertEquals(
            "old",
            failed.controls!!
                .models!!
                .current!!
                .model,
        )
        assertEquals("模型当前不可用", failed.controls!!.error)
        val other = frame("""{"kind":"context-usage","sessionId":"s","contextPressure":{"contextWindow":128000}}""")
        assertEquals("模型当前不可用", other.controls!!.error)
        assertEquals(
            128000,
            other.controls!!
                .context!!
                .pressure!!
                .contextWindow,
        )
    }

    @Test fun timeoutRejectsLateReceiptAndOldTimerCannotCancelReconnectedRequest() {
        ready()
        val opening = command(HarmonyCommand("controls-open", sessionId = "s"))
        val oldKey = opening.state!!.pendingOperations.first { it.startsWith("controls:models:") }
        val timedOut = command(HarmonyCommand("operation-timeout", requestKey = oldKey))
        assertTrue(timedOut.controls!!.error!!.contains("超时"))
        assertNull(models().controls)
        command(HarmonyCommand("disconnect"))
        frame("""{"kind":"hello","authenticated":true}""")
        val reopened = command(HarmonyCommand("controls-open", sessionId = "s"))
        val newKey = reopened.state!!.pendingOperations.first { it.startsWith("controls:models:") }
        assertTrue(newKey != oldKey)
        val stale = command(HarmonyCommand("operation-timeout", requestKey = oldKey))
        assertTrue(newKey in stale.state!!.pendingOperations)
        assertEquals(
            "old",
            models()
                .controls!!
                .models!!
                .current!!
                .model,
        )
    }

    @Test fun changingFocusNeverDisplaysPreviousSessionCatalogOrAcceptsItsLateReply() {
        ready()
        command(HarmonyCommand("controls-open", sessionId = "s"))
        command(HarmonyCommand("select", sessionId = "other"))
        val next = command(HarmonyCommand("controls-open", sessionId = "other"))
        assertNull(next.controls!!.models)
        val first = models("s")
        assertEquals("other", first.controls!!.sessionId)
        assertNull(first.controls!!.models)
        assertTrue(first.outbound.any { it.text.contains("\"other\"") })
        val second = models("other", "next")
        assertEquals(
            "next",
            second.controls!!
                .models!!
                .current!!
                .model,
        )
        val late = models("s")
        assertNull(late.controls)
        assertTrue(late.outbound.isEmpty())
    }

    @Test fun globalDefaultsRemainDistinctAndCannotBeModifiedWhileDisconnected() {
        ready()
        command(HarmonyCommand("controls-defaults"))
        models()
        val initial = frame("""{"kind":"default-model","selection":{"provider":"p","model":"default"}}""")
        assertNull(initial.controls!!.sessionId)
        assertEquals("default", initial.controls!!.defaultModel!!.model)
        assertNull(initial.controls!!.models!!.current)
        assertEquals(
            "p",
            initial.controls!!
                .models!!
                .groups
                .single()
                .id,
        )
        val saving = command(HarmonyCommand("controls-save-model", provider = "p", model = "next"))
        assertEquals("default", saving.controls!!.defaultModel!!.model)
        val confirmed = frame("""{"kind":"save-default-model","saved":{"provider":"p","model":"next"}}""")
        assertEquals("next", confirmed.controls!!.defaultModel!!.model)
        command(HarmonyCommand("disconnect"))
        val offline = command(HarmonyCommand("controls-default", target = "permission", value = "read-only"))
        assertTrue(offline.outbound.isEmpty())
        assertTrue(offline.controls!!.error!!.contains("断开"))
    }

    @Test fun tokenTotalsRemainExactAcrossJavaScriptJsonBoundary() {
        ready()
        command(HarmonyCommand("controls-open", sessionId = "s"))
        val result = frame("""{"kind":"session-stats","sessionId":"s","tokenUsage":{"totals":{"cacheReadTokens":9007199254740993}}}""")
        assertEquals(
            "9007199254740993",
            result.controls!!
                .metrics
                .first { it.id == "cache-read" }
                .value,
        )
    }
}
