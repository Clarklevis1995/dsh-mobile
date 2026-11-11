package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.protocol.*
import kotlinx.serialization.json.Json
import kotlin.test.*

class ScheduleTimingEditorTest {
    private val daily = ScheduleTimingDraft("daily", "1", "2099-01-31", "08:00:37.125", "Asia/Shanghai", "1,3,5", "31", "")
    private fun wire(draft: ScheduleTimingDraft) = JsonValue.fromJsonElement(Json.parseToJsonElement(requireNotNull(ScheduleTimingEditor.timingJson(draft))))

    @Test fun dailyRetainsSecondsMillisecondsAndTimeZone() {
        val json = wire(daily)
        assertEquals("daily", json["kind"]?.stringValue)
        assertEquals("08:00:37.125", json["daily"]?.get("time")?.stringValue)
        assertEquals("Asia/Shanghai", json["daily"]?.get("time_zone")?.stringValue)
    }
    @Test fun weeklyRetainsSelectedDays() {
        val json = wire(daily.copy(frequency = "weekly"))
        assertEquals(listOf(1.0, 3.0, 5.0), json["weekly"]?.get("weekdays")?.arrayValue?.map { it.doubleValue })
    }
    @Test fun calendarIntervalUsesZonedRuleInsteadOfFixedSeconds() {
        val json = wire(daily.copy(interval = "2", time = "08:00:37", timeZone = "America/New_York"))
        assertEquals("rrule", json["kind"]?.stringValue)
        assertEquals("DTSTART;TZID=America/New_York:20990131T080037\nRRULE:FREQ=DAILY;INTERVAL=2", json["rrule"]?.stringValue)
    }
    @Test fun monthlyClampsToLastDayLikeOfficialUI() {
        assertTrue(wire(daily.copy(frequency = "monthly", time = "08:00:00"))["rrule"]!!.stringValue!!.endsWith("FREQ=MONTHLY;INTERVAL=1;BYMONTHDAY=31,-1;BYSETPOS=1"))
    }
    @Test fun customRuleIsLosslessWhenOnlyOtherTaskFieldsChange() {
        val rule = "DTSTART;TZID=America/New_York:20990131T080000\nRRULE:FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1;COUNT=12"
        val raw = JsonValue.ObjectValue(mapOf("kind" to JsonValue.StringValue("rrule"), "rrule" to JsonValue.StringValue(rule)))
        val draft = ScheduleTimingEditor.fromRecord(raw.displayText(), "Asia/Shanghai", "2099-01-01T08:00:00")
        assertEquals("advanced", draft.frequency)
        assertEquals(rule, draft.advanced)
        assertNull(ScheduleTimingEditor.changeJson(draft, draft))
    }
    @Test fun ownGuidedRulesCanBeReopened() {
        for (frequency in listOf("daily", "weekly", "monthly", "minutely", "hourly")) {
            val original = daily.copy(frequency = frequency, interval = "2", time = "08:00:37")
            val restored = ScheduleTimingEditor.fromRecord(wire(original).displayText(), "UTC", "2099-01-31T08:00:37")
            assertEquals(frequency, restored.frequency)
            assertEquals("2", restored.interval)
            assertEquals("Asia/Shanghai", restored.timeZone)
            assertEquals("08:00:37", restored.time)
            assertEquals(ScheduleTimingEditor.timingJson(original), ScheduleTimingEditor.timingJson(restored))
        }
    }
    @Test fun officialDailyRuleProjectsTimeAndInterval() {
        val raw = """{"kind":"rrule","rrule":"DTSTART;TZID=Asia/Shanghai:20990131T080000\nRRULE:FREQ=DAILY;INTERVAL=3;BYHOUR=9;BYMINUTE=30"}"""
        val draft = ScheduleTimingEditor.fromRecord(raw, "UTC", "2099-01-31T00:00:00")
        assertEquals("daily", draft.frequency)
        assertEquals("09:30:00", draft.time)
        assertEquals("3", draft.interval)
    }
    @Test fun officialOneShotRuleCanUseGuidedEditorWithoutChangingUntouchedTiming() {
        val draft = ScheduleTimingEditor.fromRecord("""{"kind":"rrule","rrule":"DTSTART;TZID=Asia/Shanghai:20990131T080037\nRRULE:FREQ=DAILY;COUNT=1"}""", "UTC", "2099-01-01T00:00:00")
        assertEquals("once", draft.frequency)
        assertEquals("2099-01-31", draft.date)
        assertEquals("08:00:37", draft.time)
        assertNull(ScheduleTimingEditor.changeJson(draft, draft))
    }
    @Test fun nestedTypedTimingReadsAllFields() {
        val draft = ScheduleTimingEditor.fromRecord("""{"timing":{"kind":"weekly","weekly":{"time":"12:30:45","time_zone":"Europe/Berlin","weekdays":[2,4]}}}""", "UTC", "2099-01-01T00:00:00")
        assertEquals("weekly", draft.frequency)
        assertEquals("2,4", draft.weekdays)
        assertEquals("Europe/Berlin", draft.timeZone)
    }
    @Test fun invalidInputsDoNotProduceWirePatch() {
        val invalid = listOf(daily.copy(interval = "0"), daily.copy(interval = "-1"), daily.copy(interval = "1.5"),
            daily.copy(interval = "999999999999999"), daily.copy(time = "25:00:00"), daily.copy(time = "08:00"),
            daily.copy(date = "2099-02-29"), daily.copy(date = "2099-00-01"), daily.copy(frequency = "weekly", weekdays = ""),
            daily.copy(frequency = "weekly", weekdays = "1,1"), daily.copy(frequency = "monthly", monthDay = "32"),
            daily.copy(timeZone = "GMT+8"), daily.copy(interval = "2"))
        invalid.forEach { assertNotNull(ScheduleTimingEditor.validationError(it)); assertNull(ScheduleTimingEditor.timingJson(it)) }
    }
    @Test fun cronSupportsZoneChangesAndKeepsExpression() {
        val draft = daily.copy(frequency = "advanced", advanced = "0 9 * * 1-5", timeZone = "Europe/London")
        assertEquals("Europe/London", wire(draft)["cron"]?.get("time_zone")?.stringValue)
        assertEquals("0 9 * * 1-5", wire(draft)["cron"]?.get("expression")?.stringValue)
    }
    @Test fun sameDraftDoesNotSendTimingPatch() { assertNull(ScheduleTimingEditor.changeJson(daily, daily)) }
    @Test fun oneShotKeepsChosenLocalDateAndZone() {
        assertEquals("2099-01-31", wire(daily.copy(frequency = "once"))["at"]?.get("date")?.stringValue)
    }
}
