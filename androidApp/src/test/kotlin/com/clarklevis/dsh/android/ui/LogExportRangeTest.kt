package com.clarklevis.dsh.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class LogExportRangeTest {
    @Test fun wholeHourPresetsIncludeCurrentHourAndTodayStartsAtMidnight() =
        inShanghai {
            val now = date(2026, 11, 3, 9, 53)
            val end = logHourEnd(now)
            assertEquals(date(2026, 11, 3, 9), logHourStart(now))
            assertEquals(date(2026, 11, 3, 10), end)
            assertEquals(date(2026, 11, 3, 9), logPresetStart(end, 1, now))
            assertEquals(date(2026, 11, 2, 10), logPresetStart(end, 24, now))
            assertEquals(date(2026, 11, 3, 0), logPresetStart(end, null, now))
        }

    @Test fun midnightEndBoundaryIsAvailableOnTheNextDay() =
        inShanghai {
            val now = date(2026, 11, 3, 23, 59)
            val end = logHourEnd(now)
            assertEquals(date(2026, 11, 4, 0), end)
            assertEquals(date(2026, 11, 3, 0), logPresetStart(end, null, now))
            assertEquals(listOf(end), logHoursOnDay(end, end))
        }

    @Test fun dateEditsKeepWholeHoursAndClampToLatestBoundary() =
        inShanghai {
            val now = date(2026, 11, 3, 9, 53)
            assertEquals(date(2026, 11, 2, 8), logDateWithHour(date(2026, 11, 2, 0), date(2026, 11, 3, 8), now))
            assertEquals(date(2026, 11, 3, 10), logDateWithHour(logDayStart(now), date(2026, 11, 2, 23), logHourEnd(now)))
            assertEquals((0..9).map { date(2026, 11, 3, it) }, logHoursOnDay(now, logHourStart(now)))
        }

    private fun date(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int = 0,
    ): Long =
        Calendar
            .getInstance()
            .apply {
                clear()
                set(year, month - 1, day, hour, minute)
            }.timeInMillis

    private fun inShanghai(test: () -> Unit) {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            test()
        } finally {
            TimeZone.setDefault(previous)
        }
    }
}
