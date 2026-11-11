package whaleness

import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScheduleSupportTest {
    private val engine = HarmonyEngine()

    private fun command(value: HarmonyCommand): HarmonyResult = wireJson.decodeFromString(engine.dispatch(wireJson.encodeToString(value)))

    private fun frame(value: String) = command(HarmonyCommand("frame", frame = value))

    private fun record(
        title: String = "原任务",
        kind: String = "weekly",
    ) =
        """{"id":"t","sessionId":"s","title":"$title","prompt":"原内容","kind":"$kind","status":"active","scheduledAt":"2030-01-01T00:00:00.000Z","time":"09:00:45.123","timeZone":"America/New_York","weekdays":[1,3]}"""

    private fun catalog(title: String = "原任务") = frame("""{"kind":"schedule-catalog","items":[${record(title)}]}""")

    private fun ready(): String {
        command(HarmonyCommand("prepare", endpoint = "ws://127.0.0.1/ws/mobile"))
        frame("""{"kind":"hello","authenticated":true}""")
        command(HarmonyCommand("schedules-open"))
        catalog()
        return command(HarmonyCommand("schedule-edit-open", sessionId = "s", itemId = "t")).schedules!!.editor!!.token
    }

    private fun save(
        token: String,
        target: String = "keep",
        clock: String = "09:00",
    ) = command(
        HarmonyCommand(
            "schedule-save",
            sessionId = "s",
            itemId = "t",
            requestKey = token,
            title = "新任务",
            text = "新内容",
            target = target,
            query = clock,
        ),
    )

    @Test fun editIncludesCompleteExpectedRecordAndWaitsForMatchingSuccess() {
        val saved = save(ready())
        val payload = wireJson.parseToJsonElement(saved.outbound.single().text).jsonObject
        assertEquals("09:00:45.123", payload["expected"]!!.jsonObject["time"]!!.jsonPrimitive.content)
        assertNull(payload["change"])
        assertEquals(
            "原任务",
            saved.schedules!!
                .items
                .single()
                .title,
        )
        assertTrue(saved.schedules!!.editor!!.pending)
        assertNull(frame("""{"kind":"schedule-update","requestId":"wrong","updated":true}""").schedules)
        val id = payload["requestId"]!!.jsonPrimitive.content
        val ack = frame("""{"kind":"schedule-update","requestId":"$id","sessionId":"s","updated":true}""")
        assertTrue(ack.schedules!!.editor!!.completed)
        assertTrue(
            ack.outbound
                .single()
                .text
                .contains("schedule-catalog"),
        )
        assertEquals(
            "新任务",
            catalog("新任务")
                .schedules!!
                .items
                .single()
                .title,
        )
    }

    @Test fun weeklyClockChangePreservesHostTimezoneAndWeekdays() {
        val result = save(ready(), "clock", "14:32")
        val change = wireJson.parseToJsonElement(result.outbound.single().text).jsonObject["change"]!!.jsonObject
        assertEquals("weekly", change["kind"]!!.jsonPrimitive.content)
        assertEquals("America/New_York", change["weekly"]!!.jsonObject["time_zone"]!!.jsonPrimitive.content)
        assertEquals(listOf(1.0, 3.0), change["weekly"]!!.jsonObject["weekdays"]!!.jsonArray.map { it.jsonPrimitive.double })
        assertEquals("14:32:00", change["weekly"]!!.jsonObject["time"]!!.jsonPrimitive.content)
    }

    @Test fun unchangedDisplayedMinuteDoesNotDiscardSecondsOrReanchorSchedule() {
        val result = save(ready(), "clock", "09:00")
        assertNull(wireJson.parseToJsonElement(result.outbound.single().text).jsonObject["change"])
    }

    @Test fun changedCatalogInvalidatesEditorAndRejectsStaleSave() {
        val token = ready()
        command(HarmonyCommand("schedules-open"))
        assertFalse(catalog("外部修改").schedules!!.editor!!.eligible)
        val rejected = save(token)
        assertFalse(rejected.ok)
        assertTrue(rejected.outbound.isEmpty())
    }

    @Test fun noRequestIdCatalogTimeoutRequiresReconnectAndRejectsLateReply() {
        ready()
        val pending = command(HarmonyCommand("schedules-open"))
        val key = pending.state!!.pendingOperations.first { it.startsWith("schedules:catalog:") }
        assertTrue(command(HarmonyCommand("operation-timeout", requestKey = key)).schedules!!.uncertain)
        assertNull(catalog("迟到").schedules)
        assertTrue(command(HarmonyCommand("schedules-open")).outbound.isEmpty())
    }

    @Test fun invalidClockOrOversizedTitleCannotEmitMutation() {
        val token = ready()
        val invalid = save(token, "clock", "25:99")
        assertFalse(invalid.ok)
        assertTrue(invalid.outbound.isEmpty())
        val title =
            command(
                HarmonyCommand("schedule-save", sessionId = "s", itemId = "t", requestKey = token, title = "x".repeat(121), text = "内容"),
            )
        assertFalse(title.ok)
        assertTrue(title.outbound.isEmpty())
    }
}
