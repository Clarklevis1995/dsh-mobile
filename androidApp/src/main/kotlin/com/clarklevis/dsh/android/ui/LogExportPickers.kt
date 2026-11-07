package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clarklevis.dsh.android.localization.appText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 日志页只负责时间范围与整点规则，日历、滚轮和面板均复用公共组件。 */
@Composable
internal fun LogDateTimeSheet(field: String, value: Long, earliest: Long, onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    var draft by rememberSaveable(field) { mutableLongStateOf(value) }
    val isDate = field.endsWith("date")
    val isStart = field.startsWith("start")
    val title = appText(if (isStart) {
        if (isDate) "开始日期" else "开始时间"
    } else {
        if (isDate) "结束日期" else "结束时间"
    })
    val now = remember(field) { System.currentTimeMillis() }
    val latest = if (isStart) logHourStart(now) else logHourEnd(now)
    val hours = remember(pickerDayStart(draft), latest) { logHoursOnDay(draft, latest) }
    DshPickerSheet(title, onDismiss, onConfirm = { onConfirm(logHourStart(draft)) },
        height = (if (isDate) 560 else 408).dp, confirmLabel = appText("完成"),
        confirmEnabled = draft <= latest, tag = "log-editor", contentPadding = 16.dp,
        presentation = DshPickerPresentation.SYSTEM_SHEET) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(SimpleDateFormat(if (Locale.getDefault().language == "zh") "yyyy年M月d日 EEE" else "EEE, d MMM yyyy",
                Locale.getDefault()).format(Date(draft)), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (isDate) {
                DshCalendar(draft, earliest, latest, tag = "log-calendar",
                    selectionColor = MaterialTheme.colorScheme.primary, selectionTextColor = Color.White,
                    rowHeight = 50.dp, highlightInset = 0.dp) {
                    draft = logDateWithHour(it, draft, latest)
                }
            } else {
                DshNumberWheel(hours.indexOf(draft).coerceAtLeast(0), 0, hours.lastIndex.coerceAtLeast(0),
                    appText("时间"), "log-native-hour-picker", format = { logTimeText(hours[it]) }, onChange = { draft = hours[it] })
            }
            Text(appText(if (isDate) "灰色日期不在可导出的日志范围内" else "上下滑动选择整点"),
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

