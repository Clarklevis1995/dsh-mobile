package com.clarklevis.dsh.android.ui

import com.clarklevis.dsh.android.MobileScheduledTask
import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder
import com.clarklevis.dsh.shared.protocol.ScheduleTimingEditor
import org.junit.Assert.*
import org.junit.Test

class ScheduledTaskEditTimingTest {
    @Test fun dailyClockEditPreservesSecondsAndZone() {
        val draft = initialTimingDraft(task("""{"id":"daily-1","sessionId":"s1","status":"active","kind":"daily","title":"新闻","prompt":"汇总新闻","time":"08:00:37.125","timeZone":"Asia/Shanghai","scheduledAt":"2099-01-01T00:00:00.000Z"}"""))
        assertEquals("08:00:37.125", draft.time)
        val change = ScheduleTimingEditor.changeJson(draft.copy(time = "09:00:37.125"), draft)
        assertTrue(change!!.contains("09:00:37.125"))
        assertTrue(change.contains("Asia/Shanghai"))
    }
    @Test fun untouchedClockDoesNotReplaceRule() {
        val draft = initialTimingDraft(task("""{"id":"daily-1","status":"active","kind":"daily","title":"新闻","prompt":"新闻","time":"08:00:37.000","timeZone":"Asia/Shanghai","scheduledAt":"2099-01-01T00:00:00Z"}"""))
        assertNull(ScheduleTimingEditor.changeJson(draft, draft))
    }
    @Test fun oneShotUsesChosenZoneAndRejectsPastDate() {
        val draft = initialTimingDraft(task("""{"id":"at-1","status":"active","kind":"at","title":"新闻","prompt":"新闻","scheduledAt":"2099-01-01T00:00:00Z"}"""))
        assertNull(timingValidationError(draft.copy(timeZone = "America/New_York")))
        assertNotNull(timingValidationError(draft.copy(date = "2000-01-01")))
        assertNotNull(timingValidationError(draft.copy(timeZone = "Mars/Olympus")))
    }
    @Test fun oneShotPreservesFractionalAnchorWhenChangingZone() {
        val draft = initialTimingDraft(task("""{"id":"at-1","status":"active","kind":"at","title":"新闻","prompt":"新闻","scheduledAt":"2099-01-01T00:00:37.125Z"}"""))
        assertTrue(draft.time.endsWith("37.125"))
        val change = ScheduleTimingEditor.changeJson(draft.copy(timeZone = "Europe/Berlin"), draft)
        assertTrue(change!!.contains("37.125"))
    }
    @Test fun gapInOneShotWallTimeIsNotSilentlyMoved() {
        val draft = initialTimingDraft(task("""{"id":"at-1","status":"active","kind":"at","title":"新闻","prompt":"新闻","scheduledAt":"2099-01-01T00:00:00Z"}"""))
        assertNotNull(timingValidationError(draft.copy(date = "2099-03-08", time = "02:30:00", timeZone = "America/New_York")))
    }
    private fun task(item: String): MobileScheduledTask {
        val frame = GatewayWireDecoder.decode("""{"kind":"schedule-catalog","items":[$item]}""")
        return requireNotNull(frame.items?.single()?.let(MobileScheduledTask::from))
    }
}
