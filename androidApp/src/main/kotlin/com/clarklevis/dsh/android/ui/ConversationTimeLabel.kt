package com.clarklevis.dsh.android.ui

import com.clarklevis.dsh.android.localization.AndroidStrings
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 使用设备时区；日期规则与 iOS 会话时间分隔保持一致。 */
internal fun conversationTimeLabel(
    epochSeconds: Double,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault()
): String {
    val date = Instant.ofEpochMilli((epochSeconds * 1_000).toLong()).atZone(zone)
    val today = now.atZone(zone)
    val pattern = when {
        date.toLocalDate() == today.toLocalDate() -> "H:mm"
        date.year == today.year -> AndroidStrings.text("conversation.time.date", locale)
        else -> AndroidStrings.text("conversation.time.yearDate", locale)
    }
    return date.format(DateTimeFormatter.ofPattern(pattern, locale))
}
