package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import com.clarklevis.dsh.android.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.clarklevis.dsh.android.MobileScheduledTask
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.shared.protocol.ScheduleTimingDraft
import com.clarklevis.dsh.shared.protocol.ScheduleTimingEditor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal val scheduleFrequencyNames = linkedMapOf("once" to "不重复", "minutely" to "每分钟", "hourly" to "每小时",
    "daily" to "每天", "weekly" to "每周", "monthly" to "每月", "advanced" to "自定义")

internal fun initialTimingDraft(task: MobileScheduledTask): ScheduleTimingDraft {
    val zone = TimeZone.getTimeZone(task.timeZone ?: TimeZone.getDefault().id)
    val start = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).apply { timeZone = zone }
        .format(parseTimingDate(task.scheduledAt) ?: Date(System.currentTimeMillis() + 3_600_000))
    return ScheduleTimingEditor.fromRecord(task.raw.displayText(), zone.id, start)
}

internal fun timingValidationError(draft: ScheduleTimingDraft): String? {
    ScheduleTimingEditor.validationError(draft)?.let { return it }
    if (draft.timeZone !in TimeZone.getAvailableIDs() && draft.timeZone != "UTC") return "请选择有效的 IANA 时区。"
    if (draft.frequency == "once") {
        val zone = TimeZone.getTimeZone(draft.timeZone)
        val value = "${draft.date}T${draft.time}"
        val instant = listOf("yyyy-MM-dd'T'HH:mm:ss.SSS", "yyyy-MM-dd'T'HH:mm:ss").firstNotNullOfOrNull { pattern ->
            runCatching { SimpleDateFormat(pattern, Locale.US).apply { timeZone = zone; isLenient = false }.parse(value) }.getOrNull()
        } ?: return "请选择有效的日期和时间。"
        if (instant.time <= System.currentTimeMillis()) return "请选择未来的日期和时间。"
    }
    return null
}

@Composable
internal fun ScheduleTimingControls(draft: ScheduleTimingDraft, enabled: Boolean, onChange: (ScheduleTimingDraft) -> Unit) {
    var frequencyMenu by remember { mutableStateOf(false) }
    var selectingTime by remember { mutableStateOf(false) }
    var selectingInterval by remember { mutableStateOf(false) }
    val intervalUnit = appText(when (draft.frequency) { "minutely" -> "分钟"; "hourly" -> "小时"; "daily" -> "天"; "weekly" -> "周"; else -> "个月" })
    if (selectingInterval) ScheduleIntervalPicker(draft.interval, intervalUnit, onDismiss = { selectingInterval = false }) {
        onChange(draft.copy(interval = it)); selectingInterval = false
    }
    if (selectingTime) {
        DshClockPicker(draft.time, onDismiss = { selectingTime = false }) { time ->
            onChange(draft.copy(time = time)); selectingTime = false
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(appText("执行时间"), fontSize = 15.sp, fontWeight = FontWeight.Medium)
        ScheduleSection(spacing = 0.dp) {
            Box {
                ScheduleSettingRow(appText("重复"), appText(scheduleFrequencyNames[draft.frequency] ?: "自定义"), enabled,
                    tag = "schedule-frequency-picker", rowHeight = 52.dp) { frequencyMenu = true }
                DshSelectionPopup(frequencyMenu, scheduleFrequencyNames.map { DshSelectionOption(it.key, appText(it.value)) }, draft.frequency,
                    onDismissRequest = { frequencyMenu = false }, onSelect = { onChange(draft.copy(frequency = it)); frequencyMenu = false })
            }
            if (draft.frequency == "advanced") {
                Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(appText("Cron / RRULE"), fontSize = 15.sp)
                    ScheduleFilledField(draft.advanced, { onChange(draft.copy(advanced = it)) }, enabled = enabled, modifier = Modifier.height(110.dp))
                    Text(appText("Cron 使用 5 个字段；RRULE 保留 DTSTART 中的时区。规则由宿主校验，出错后可继续编辑。"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                if (draft.frequency != "once") {
                    ScheduleSettingRow(appText("重复间隔"), "${draft.interval} $intervalUnit", enabled,
                        tag = "schedule-interval-picker", rowHeight = 52.dp) { selectingInterval = true }
                }
                val dateZone = TimeZone.getTimeZone(draft.timeZone)
                val dateFormat = remember(dateZone.id) {
                    SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = dateZone; isLenient = false }
                }
                val dateValue = remember(draft.date, dateZone.id) {
                    runCatching { dateFormat.parse(draft.date)?.time }.getOrNull() ?: System.currentTimeMillis()
                }
                DshDatePickerField(appText(if (draft.frequency == "once") "执行日期" else "开始日期"),
                    dateValue, enabled, "schedule-date-picker", timeZone = dateZone) {
                    onChange(draft.copy(date = dateFormat.format(Date(it))))
                }
                ScheduleSettingRow(appText("时间"), draft.time, enabled, tag = "schedule-time-picker", icon = R.drawable.ic_drawer_schedule, rowHeight = 52.dp, divider = true) { selectingTime = true }
                if (draft.frequency == "weekly") {
                    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(appText("重复日期"), fontSize = 15.sp)
                        val selected = draft.weekdays.split(',').mapNotNull(String::toIntOrNull).toSet()
                        for (row in listOf(listOf(1, 2, 3, 4), listOf(5, 6, 7))) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { day ->
                                    Box(Modifier.weight(1f).heightIn(min = 36.dp).clip(RoundedCornerShape(10.dp))
                                        .background(if (day in selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface)
                                        .selectable(selected = day in selected, enabled = enabled, role = Role.Button, onClick = {
                                            val days = if (day in selected) selected - day else selected + day
                                            onChange(draft.copy(weekdays = days.sorted().joinToString(",")))
                                        }), contentAlignment = Alignment.Center) {
                                        Text(appText(listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[day - 1]), fontSize = 15.sp,
                                            color = if (day in selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                                if (row.size == 3) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
                if (draft.frequency == "monthly") {
                    ScheduleValueRow(appText("每月几号"), draft.monthDay, { onChange(draft.copy(monthDay = it)) }, enabled = enabled)
                    Text(appText("当月不足该日期时，在月末执行。"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (draft.frequency != "advanced" || !draft.advanced.trimStart().startsWith("DTSTART")) {
                ScheduleTimeZoneField(draft.timeZone, enabled) { onChange(draft.copy(timeZone = it)) }
            }
        }
    }
}

private fun parseTimingDate(value: String): Date? = listOf("yyyy-MM-dd'T'HH:mm:ss.SSSX", "yyyy-MM-dd'T'HH:mm:ssX").firstNotNullOfOrNull { pattern ->
    runCatching { SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }.parse(value) }.getOrNull()
}

@Composable
private fun ScheduleTimeZoneField(zone: String, enabled: Boolean, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val zones = remember { (listOf("UTC", TimeZone.getDefault().id) + TimeZone.getAvailableIDs().filter { '/' in it }.sorted()).distinct() }
    ScheduleSettingRow(appText("时区"), zone, enabled, tag = "schedule-zone-picker", rowHeight = 52.dp) { expanded = true }
    if (expanded) {
        ScheduleChoiceSheet(appText("时区"), appText("搜索时区"), zone, zones.map { ScheduleChoice(it, it) },
            onDismiss = { expanded = false }, onSelect = onChange)
    }
}

@Composable
internal fun ScheduleIntervalPicker(value: String, unit: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var selected by remember(value) { mutableStateOf(value.toIntOrNull()?.coerceIn(1, 10_000) ?: 1) }
    DshPickerSheet(appText("重复间隔"), onDismiss, onConfirm = { onConfirm(selected.toString()) }, tag = "schedule-wheel-sheet") {
        DshNumberWheel(selected, 1, 10_000, appText("重复间隔"), "schedule-interval-wheel",
            format = { "$it $unit" }, onChange = { selected = it })
    }
}
