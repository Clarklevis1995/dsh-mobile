package com.clarklevis.dsh.android.ui

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class RelativeTimeTest {
    private val timeZone = TimeZone.getTimeZone("Asia/Shanghai")

    @Test
    fun `uses named calendar days like iOS`() {
        val now = timestamp(2026, Calendar.AUGUST, 28, 16, 0)

        assertEquals(
            "3 小时前",
            relativeTime(timestamp(2026, Calendar.AUGUST, 28, 13, 0) / 1_000.0, now, timeZone)
        )
        assertEquals(
            "昨天",
            relativeTime(timestamp(2026, Calendar.AUGUST, 27, 15, 0) / 1_000.0, now, timeZone)
        )
        assertEquals(
            "前天",
            relativeTime(timestamp(2026, Calendar.AUGUST, 26, 20, 0) / 1_000.0, now, timeZone)
        )
        assertEquals(
            "3 天前",
            relativeTime(timestamp(2026, Calendar.AUGUST, 25, 20, 0) / 1_000.0, now, timeZone)
        )
    }

    @Test
    fun `crossing midnight is yesterday even within twenty four hours`() {
        val now = timestamp(2026, Calendar.AUGUST, 28, 0, 30)
        val event = timestamp(2026, Calendar.AUGUST, 27, 23, 30)

        assertEquals("昨天", relativeTime(event / 1_000.0, now, timeZone))
    }

    private fun timestamp(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(timeZone).run {
            clear()
            set(year, month, day, hour, minute)
            timeInMillis
        }
}
