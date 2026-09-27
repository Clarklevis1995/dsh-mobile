package com.clarklevis.dsh.android.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clarklevis.dsh.android.AndroidSharedStateHolder
import com.clarklevis.dsh.android.MobileScheduledTask
import com.clarklevis.dsh.shared.protocol.JsonValue
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduledTaskEditSheet(
    task: MobileScheduledTask,
    stateHolder: AndroidSharedStateHolder,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val repeating = task.kind !in setOf("at", "after")
    val zone = remember(task.id) { TimeZone.getTimeZone(task.raw["timeZone"]?.stringValue ?: TimeZone.getDefault().id) }
    var title by remember(task.id) { mutableStateOf(task.title) }
    var prompt by remember(task.id) { mutableStateOf(task.prompt) }
    var useSpecificDate by remember(task.id) { mutableStateOf(!repeating) }
    var selectedDate by remember(task.id) { mutableStateOf(parseScheduleInstant(task.scheduledAt) ?: Date(System.currentTimeMillis() + 3_600_000)) }
    var selectedTime by remember(task.id) { mutableStateOf(initialScheduleClock(task, zone)) }
    var pendingRequestId by remember(task.id) { mutableStateOf<String?>(null) }
    var localError by remember(task.id) { mutableStateOf<String?>(null) }
    val timeChange = scheduleTimingChange(task, useSpecificDate, selectedDate, selectedTime, zone, repeating)
    val cleanTitle = title.trim()
    val cleanPrompt = prompt.trim()
    val canSave = pendingRequestId == null && cleanTitle.isNotEmpty() && cleanTitle.length <= 120 &&
        cleanPrompt.isNotEmpty() && (cleanTitle != task.title || cleanPrompt != task.prompt || timeChange != null) &&
        (!useSpecificDate || timeChange == null || selectedDate.after(Date()))

    LaunchedEffect(stateHolder.scheduledTaskCompletedRequestId, stateHolder.scheduledTaskPendingId) {
        val requestId = pendingRequestId
        if (requestId != null && stateHolder.scheduledTaskCompletedRequestId == requestId) {
            onDismiss()
        } else if (requestId != null && stateHolder.scheduledTaskPendingId == null) {
            localError = stateHolder.scheduledTaskMutationError
            pendingRequestId = null
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (pendingRequestId == null) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = onDismiss, enabled = pendingRequestId == null) { Text("取消") }
                Text("编辑定时任务", fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 12.dp))
                TextButton(
                    enabled = canSave,
                    onClick = {
                        localError = null
                        pendingRequestId = stateHolder.updateScheduledTask(task, title, prompt, timeChange)
                        if (pendingRequestId == null) {
                            localError = stateHolder.scheduledTaskMutationError ?: "操作正在进行，请稍后重试"
                        }
                    }
                ) { Text("保存") }
            }
            OutlinedTextField(
                value = title, onValueChange = { title = it }, label = { Text("标题") },
                supportingText = { Text("最多 120 个字") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = prompt, onValueChange = { prompt = it }, label = { Text("任务内容") },
                minLines = 4, modifier = Modifier.fillMaxWidth()
            )
            Text("执行时间", fontWeight = FontWeight.Medium)
            if (repeating) {
                OutlinedButton(onClick = { useSpecificDate = !useSpecificDate }) {
                    Text(if (useSpecificDate) "保持原重复规则" else "改为指定日期执行一次")
                }
            }
            if (useSpecificDate) {
                OutlinedButton(
                    onClick = { showScheduleDatePicker(context, selectedDate) { selectedDate = it } },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("执行日期  ${formatSelectedDate(selectedDate)}") }
                OutlinedButton(
                    onClick = { showScheduleTimePicker(context, selectedDate, TimeZone.getDefault()) { selectedDate = it } },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("执行时刻  ${formatSelectedTime(selectedDate, TimeZone.getDefault())}") }
                Text("按当前设备时区 ${TimeZone.getDefault().id} 选择，保存后按该时间执行一次。",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (timeChange != null && !selectedDate.after(Date())) {
                    Text("请选择未来的日期和时间。", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                }
            } else if (task.kind == "daily" || task.kind == "weekly") {
                OutlinedButton(
                    onClick = { showScheduleTimePicker(context, selectedTime, zone) { selectedTime = it } },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("执行时刻  ${formatSelectedTime(selectedTime, zone)}") }
                Text("保留原有${if (task.kind == "daily") "每日" else "每周"}规则和时区 ${zone.id}。",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("当前规则：${scheduleRuleLabel(task)}。可选择指定日期和时间，明确改为单次执行。",
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            (localError ?: if (pendingRequestId != null) stateHolder.scheduledTaskMutationError else null)?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
            }
            if (pendingRequestId != null) {
                CircularProgressIndicator(modifier = Modifier.height(24.dp))
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

internal fun scheduleTimingChange(
    task: MobileScheduledTask,
    specificDate: Boolean,
    selectedDate: Date,
    selectedTime: Date,
    zone: TimeZone,
    repeating: Boolean
): JsonValue? {
    if (specificDate) {
        val original = parseScheduleInstant(task.scheduledAt)
        if (!repeating && original != null && abs(selectedDate.time - original.time) <= 1_000) return null
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return JsonValue.ObjectValue(mapOf(
            "kind" to JsonValue.StringValue("at"),
            "at" to JsonValue.StringValue(formatter.format(selectedDate))
        ))
    }
    val clock = formatSelectedTime(selectedTime, zone) + ":00"
    if (clock.take(5) == task.raw["time"]?.stringValue?.take(5)) return null
    val timeZone = task.raw["timeZone"]?.stringValue ?: zone.id
    val parameters = mutableMapOf<String, JsonValue>(
        "time" to JsonValue.StringValue(clock),
        "time_zone" to JsonValue.StringValue(timeZone)
    )
    if (task.kind == "weekly") {
        parameters["weekdays"] = task.raw["weekdays"] ?: JsonValue.ArrayValue(emptyList())
    }
    if (task.kind !in setOf("daily", "weekly")) return null
    return JsonValue.ObjectValue(mapOf(
        "kind" to JsonValue.StringValue(task.kind),
        task.kind to JsonValue.ObjectValue(parameters)
    ))
}

private fun parseScheduleInstant(value: String): Date? =
    listOf("yyyy-MM-dd'T'HH:mm:ss.SSSX", "yyyy-MM-dd'T'HH:mm:ssX").firstNotNullOfOrNull { pattern ->
        runCatching { SimpleDateFormat(pattern, Locale.US).parse(value) }.getOrNull()
    }

private fun initialScheduleClock(task: MobileScheduledTask, zone: TimeZone): Date {
    val parts = task.raw["time"]?.stringValue.orEmpty().split(":")
    val hour = parts.getOrNull(0)?.toIntOrNull() ?: return Date()
    val minute = parts.getOrNull(1)?.toIntOrNull() ?: return Date()
    return Calendar.getInstance(zone).apply {
        set(2026, Calendar.JANUARY, 1, hour, minute, 0)
        set(Calendar.MILLISECOND, 0)
    }.time
}

private fun formatSelectedDate(date: Date): String = SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(date)

private fun formatSelectedTime(date: Date, zone: TimeZone): String =
    SimpleDateFormat("HH:mm", Locale.CHINA).apply { timeZone = zone }.format(date)

private fun scheduleRuleLabel(task: MobileScheduledTask): String = when (task.kind) {
    "every" -> "每 ${task.raw["everySeconds"]?.doubleValue?.toInt() ?: 0} 秒"
    "cron" -> "Cron ${task.raw["expression"]?.stringValue.orEmpty()}"
    else -> task.kind
}

private fun showScheduleDatePicker(context: Context, current: Date, onPicked: (Date) -> Unit) {
    val currentCalendar = Calendar.getInstance().apply { time = current }
    DatePickerDialog(context, { _, year, month, day ->
        val updated = Calendar.getInstance().apply {
            time = current
            set(year, month, day)
        }
        onPicked(updated.time)
    }, currentCalendar.get(Calendar.YEAR), currentCalendar.get(Calendar.MONTH),
        currentCalendar.get(Calendar.DAY_OF_MONTH)).apply {
        datePicker.minDate = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }.show()
}

private fun showScheduleTimePicker(
    context: Context,
    current: Date,
    zone: TimeZone,
    onPicked: (Date) -> Unit
) {
    val currentCalendar = Calendar.getInstance(zone).apply { time = current }
    TimePickerDialog(context, { _, hour, minute ->
        val updated = Calendar.getInstance(zone).apply {
            time = current
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        onPicked(updated.time)
    }, currentCalendar.get(Calendar.HOUR_OF_DAY), currentCalendar.get(Calendar.MINUTE), true).show()
}
