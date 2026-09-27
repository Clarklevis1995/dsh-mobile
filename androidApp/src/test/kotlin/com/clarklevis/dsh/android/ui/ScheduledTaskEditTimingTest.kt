package com.clarklevis.dsh.android.ui

import com.clarklevis.dsh.android.MobileScheduledTask
import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder
import java.util.Calendar
import java.util.Date
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduledTaskEditTimingTest {
    @Test
    fun dailyClockChangeKeepsRepeatRuleAndOriginalTimeZone() {
        val task = task("""{"id":"daily-1","sessionId":"s1","status":"active","kind":"daily","title":"新闻","prompt":"汇总新闻","time":"08:00:37.000","timeZone":"Asia/Shanghai","scheduledAt":"2099-01-01T00:00:00.000Z"}""")
        val zone = TimeZone.getTimeZone("Asia/Shanghai")
        val selected = Calendar.getInstance(zone).apply {
            set(2026, Calendar.JANUARY, 1, 9, 0, 0)
        }.time
        val change = scheduleTimingChange(task, false, Date(), selected, zone, true)
        assertEquals("daily", change?.get("kind")?.stringValue)
        assertEquals("09:00:00", change?.get("daily")?.get("time")?.stringValue)
        assertEquals("Asia/Shanghai", change?.get("daily")?.get("time_zone")?.stringValue)
    }

    @Test
    fun unchangedDailyClockDoesNotReplaceRule() {
        val task = task("""{"id":"daily-1","sessionId":"s1","status":"active","kind":"daily","title":"新闻","prompt":"汇总新闻","time":"08:00:37.000","timeZone":"Asia/Shanghai","scheduledAt":"2099-01-01T00:00:00.000Z"}""")
        val zone = TimeZone.getTimeZone("Asia/Shanghai")
        val selected = Calendar.getInstance(zone).apply {
            set(2026, Calendar.JANUARY, 1, 8, 0, 0)
        }.time
        assertNull(scheduleTimingChange(task, false, Date(), selected, zone, true))
    }

    private fun task(item: String): MobileScheduledTask {
        val frame = GatewayWireDecoder.decode("""{"kind":"schedule-catalog","items":[$item]}""")
        return requireNotNull(frame.items?.single()?.let(MobileScheduledTask::from))
    }
}
