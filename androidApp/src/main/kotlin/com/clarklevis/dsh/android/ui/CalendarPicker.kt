package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.android.localization.appText
import java.text.DateFormat
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

internal fun pickerDayStart(value: Long, zone: TimeZone = TimeZone.getDefault()): Long =
    GregorianCalendar(zone).apply {
        timeInMillis = value
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

private fun pickerMonthStart(value: Long, zone: TimeZone): Long = GregorianCalendar(zone).apply {
    timeInMillis = pickerDayStart(value, zone)
    set(Calendar.DAY_OF_MONTH, 1)
}.timeInMillis

private fun pickerDate(year: Int, month: Int, zone: TimeZone): Long = GregorianCalendar(zone).apply {
    clear(); set(year, month, 1)
}.timeInMillis

/** 公共日期入口：就近弹出日历，保留所在时区的日期语义，不使用平台日期弹窗。 */
@Composable
internal fun DshDatePickerField(
    label: String,
    value: Long,
    enabled: Boolean,
    tag: String,
    timeZone: TimeZone = TimeZone.getDefault(),
    onChange: (Long) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val dateText = remember(value, timeZone.id, Locale.getDefault()) {
        DateFormat.getDateInstance(DateFormat.MEDIUM).apply { this.timeZone = timeZone }.format(Date(value))
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(tag)
        .clickable(enabled = enabled, role = Role.Button) { expanded = true },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Box {
            Text(dateText, fontSize = 17.sp,
                color = if (expanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), CircleShape)
                    .padding(horizontal = 12.dp, vertical = 6.dp))
            DshMenu(expanded, onDismissRequest = { expanded = false }, modifier = Modifier.width(320.dp)) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    DshCalendar(value, timeZone = timeZone, tag = "dsh-date-calendar") {
                        onChange(it); expanded = false
                    }
                }
            }
        }
    }
}

/** 任务日期和日志日期复用同一日历；范围、时区和提交方式由调用方控制。 */
@Composable
internal fun DshCalendar(
    value: Long,
    earliest: Long? = null,
    latest: Long? = null,
    timeZone: TimeZone = TimeZone.getDefault(),
    tag: String = "dsh-calendar",
    selectionColor: Color = MaterialTheme.colorScheme.onSurface,
    selectionTextColor: Color = MaterialTheme.colorScheme.surface,
    rowHeight: Dp = 44.dp,
    highlightInset: Dp = 4.dp,
    onSelect: (Long) -> Unit
) {
    val zone = timeZone
    val firstDay = earliest?.let { pickerDayStart(it, zone) } ?: pickerDate(1, Calendar.JANUARY, zone)
    val lastDay = latest?.let { pickerDayStart(it, zone) } ?: GregorianCalendar(zone).apply {
        clear(); set(9999, Calendar.DECEMBER, 31)
    }.timeInMillis
    val firstMonth = pickerMonthStart(firstDay, zone)
    val lastMonth = pickerMonthStart(lastDay, zone)
    var month by rememberSaveable(zone.id) { mutableLongStateOf(pickerMonthStart(value.coerceIn(firstDay, lastDay), zone)) }
    var choosingMonth by rememberSaveable { mutableStateOf(false) }
    val calendar = GregorianCalendar(zone).apply { timeInMillis = month }
    val ink = MaterialTheme.colorScheme.onSurface
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val blue = MaterialTheme.colorScheme.primary
    val selectedDay = pickerDayStart(value, zone)
    val today = pickerDayStart(System.currentTimeMillis(), zone)
    Column(Modifier.fillMaxWidth().testTag(tag)) {
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clip(CircleShape).clickable(role = Role.Button) { choosingMonth = !choosingMonth }
                .padding(horizontal = 8.dp, vertical = 8.dp).testTag("$tag-month-toggle"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(SimpleDateFormat(if (Locale.getDefault().language == "zh") "yyyy年M月" else "MMMM yyyy", Locale.getDefault()) // i18n-ignore: 中文分支使用中文日期格式
                    .apply { this.timeZone = zone }.format(Date(month)), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = ink)
                Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(16.dp).graphicsLayer {
                    rotationZ = if (choosingMonth) 90f else 0f
                }, tint = blue)
            }
            CalendarArrow(false, !choosingMonth && month > firstMonth) {
                month = GregorianCalendar(zone).apply { timeInMillis = month; add(Calendar.MONTH, -1) }.timeInMillis
            }
            CalendarArrow(true, !choosingMonth && month < lastMonth) {
                month = GregorianCalendar(zone).apply { timeInMillis = month; add(Calendar.MONTH, 1) }.timeInMillis
            }
        }
        if (choosingMonth) {
            DshMonthYearWheel(month, firstDay, lastDay, zone, tag) { month = it }
        } else {
            val weekdays = DateFormatSymbols.getInstance().shortWeekdays
            Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                for (weekday in Calendar.SUNDAY..Calendar.SATURDAY) {
                    Text(weekdays[weekday], Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 13.sp, color = secondary)
                }
            }
            val offset = calendar.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY
            val count = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
            repeat((offset + count + 6) / 7) { week ->
                Row(Modifier.fillMaxWidth().height(rowHeight)) {
                    repeat(7) { weekday ->
                        val day = week * 7 + weekday - offset + 1
                        Box(Modifier.weight(1f).height(rowHeight), contentAlignment = Alignment.Center) {
                            if (day in 1..count) {
                                val date = GregorianCalendar(zone).apply { timeInMillis = month; set(Calendar.DAY_OF_MONTH, day) }.timeInMillis
                                val allowed = date in firstDay..lastDay
                                val picked = date == selectedDay
                                val background = when {
                                    picked -> selectionColor
                                    date == today && allowed -> blue.copy(alpha = 0.12f)
                                    else -> Color.Transparent
                                }
                                // 只收紧可见圆形，保留原有点击范围，避免相邻高亮贴在一起。
                                Box(Modifier.size(42.dp).drawBehind {
                                    drawCircle(background, radius = (size.minDimension / 2 - highlightInset.toPx()).coerceAtLeast(0f))
                                }.clip(CircleShape).clickable(enabled = allowed, role = Role.Button) { onSelect(date) }
                                    .semantics {
                                        selected = picked
                                        contentDescription = DateFormat.getDateInstance(DateFormat.FULL).apply { this.timeZone = zone }.format(Date(date))
                                    }.testTag("$tag-day-$day"), contentAlignment = Alignment.Center) {
                                    Text(day.toString(), fontSize = 20.sp, color = when {
                                        picked -> selectionTextColor
                                        !allowed -> secondary.copy(alpha = 0.3f)
                                        date == today -> blue
                                        else -> ink
                                    })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarArrow(next: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = appText(if (next) "下个月" else "上个月") }, contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_back_chevron), null,
            Modifier.size(20.dp).graphicsLayer { rotationZ = if (next) 180f else 0f },
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.3f))
    }
}

@Composable
private fun DshMonthYearWheel(month: Long, earliest: Long, latest: Long, zone: TimeZone, tag: String, onChange: (Long) -> Unit) {
    val current = GregorianCalendar(zone).apply { timeInMillis = month }
    val first = GregorianCalendar(zone).apply { timeInMillis = earliest }
    val last = GregorianCalendar(zone).apply { timeInMillis = latest }
    val firstYear = first.get(Calendar.YEAR)
    val lastYear = last.get(Calendar.YEAR)
    var year by rememberSaveable { mutableIntStateOf(current.get(Calendar.YEAR)) }
    var pickedMonth by rememberSaveable { mutableIntStateOf(current.get(Calendar.MONTH)) }
    val minimum = if (year == firstYear) first.get(Calendar.MONTH) else 0
    val maximum = if (year == lastYear) last.get(Calendar.MONTH) else 11
    val allowedMonth = pickedMonth.coerceIn(minimum, maximum)
    val monthNames = DateFormatSymbols.getInstance().shortMonths
    Row(Modifier.fillMaxWidth().height(260.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.weight(1f)) {
            key(year, minimum, maximum) {
                DshNumberWheel(allowedMonth, minimum, maximum, appText("月"), "$tag-month-picker", selectionInset = 2.dp,
                    format = { monthNames[it] }, onChange = { pickedMonth = it })
            }
        }
        Box(Modifier.weight(1f)) {
            DshNumberWheel(year, firstYear, lastYear, appText("年"), "$tag-year-picker", selectionInset = 2.dp,
                format = { it.toString() }, onChange = { year = it })
        }
    }
    LaunchedEffect(year, pickedMonth, minimum, maximum) {
        pickedMonth = allowedMonth
        onChange(pickerDate(year, allowedMonth, zone))
    }
}
