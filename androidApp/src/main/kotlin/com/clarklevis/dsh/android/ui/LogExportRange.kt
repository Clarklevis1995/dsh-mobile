package com.clarklevis.dsh.android.ui

import java.util.Calendar

internal const val LOG_HOUR_MILLIS = 3_600_000L
private const val SECOND_MILLIS = 1_000L
private const val MINUTE_MILLIS = 60 * SECOND_MILLIS

internal fun logHourStart(value: Long): Long {
    val calendar = Calendar.getInstance().apply { timeInMillis = value }
    return value - calendar.get(Calendar.MINUTE) * MINUTE_MILLIS -
        calendar.get(Calendar.SECOND) * SECOND_MILLIS - calendar.get(Calendar.MILLISECOND)
}

internal fun logHourEnd(value: Long): Long = logHourStart(value) + LOG_HOUR_MILLIS

internal fun logDayStart(value: Long): Long =
    Calendar
        .getInstance()
        .apply {
            timeInMillis = value
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

/** 与 iOS 一致：结束边界为下一整点，区间左闭右开，包含当前正在记录的小时。 */
internal fun logPresetStart(
    end: Long,
    hours: Int?,
    now: Long = System.currentTimeMillis(),
): Long = if (hours == null) logDayStart(now) else end - hours * LOG_HOUR_MILLIS

internal fun logHoursOnDay(
    value: Long,
    latest: Long,
): List<Long> {
    val day = logDayStart(value)
    val nextDay =
        Calendar
            .getInstance()
            .apply {
                timeInMillis = day
                add(Calendar.DAY_OF_MONTH, 1)
            }.timeInMillis
    return generateSequence(day) { it + LOG_HOUR_MILLIS }.takeWhile { it < nextDay && it <= latest }.toList()
}

internal fun logDateWithHour(
    day: Long,
    value: Long,
    latest: Long,
): Long {
    val hour = Calendar.getInstance().apply { timeInMillis = value }.get(Calendar.HOUR_OF_DAY)
    return Calendar
        .getInstance()
        .apply {
            timeInMillis = day
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        .coerceAtMost(latest)
}
