package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.android.AndroidSharedStateHolder
import com.clarklevis.dsh.android.MobileScheduledTask
import com.clarklevis.dsh.shared.protocol.JsonValue
import com.clarklevis.dsh.shared.protocol.ScheduleTimingEditor
import com.clarklevis.dsh.shared.protocol.ScheduleTargetEditor
import kotlinx.serialization.json.Json

@Composable
internal fun ScheduledTaskEditSheet(
    task: MobileScheduledTask,
    stateHolder: AndroidSharedStateHolder,
    onDismiss: () -> Unit
) {
    var title by remember(task.id) { mutableStateOf(task.title) }
    var prompt by remember(task.id) { mutableStateOf(task.prompt) }
    val originalTiming = remember(task.id) { initialTimingDraft(task) }
    var timing by remember(task.id) { mutableStateOf(originalTiming) }
    val recordJson = remember(task.id, task.raw) { task.raw.displayText() }
    val originalTarget = remember(recordJson) { ScheduleTargetEditor.fromRecord(recordJson) }
    var target by remember(task.id) { mutableStateOf(originalTarget) }
    var pendingRequestId by remember(task.id) { mutableStateOf<String?>(null) }
    var localError by remember(task.id) { mutableStateOf<String?>(null) }
    val timingDirty = timing != originalTiming
    val timingError = if (timingDirty) timingValidationError(timing) else null
    val timeChange = ScheduleTimingEditor.changeJson(timing, originalTiming)?.let {
        JsonValue.fromJsonElement(Json.parseToJsonElement(it))
    }
    val cleanTitle = title.trim()
    val cleanPrompt = prompt.trim()
    val targetError = if (target != originalTarget) ScheduleTargetEditor.validationError(target, recordJson) else null
    val targetChange = ScheduleTargetEditor.changeJson(target, recordJson)?.let {
        JsonValue.fromJsonElement(Json.parseToJsonElement(it))
    }
    val canSave = pendingRequestId == null && cleanTitle.isNotEmpty() && cleanTitle.length <= 120 &&
        cleanPrompt.isNotEmpty() && (cleanTitle != task.title || cleanPrompt != task.prompt || timeChange != null || targetChange != null) &&
        timingError == null && targetError == null

    LaunchedEffect(stateHolder.scheduledTaskCompletedRequestId, stateHolder.scheduledTaskPendingId) {
        val requestId = pendingRequestId
        if (requestId != null && stateHolder.scheduledTaskCompletedRequestId == requestId) {
            onDismiss()
        } else if (requestId != null && stateHolder.scheduledTaskPendingId == null) {
            localError = stateHolder.scheduledTaskMutationError
            pendingRequestId = null
        }
    }

    ScheduledTaskModalSheet(
        modifier = Modifier.testTag("scheduled-task-edit-sheet"),
        onDismiss = { if (pendingRequestId == null) onDismiss() }
    ) {
        ScheduleKeyboardDismissHost {
        DshMenuColumnHost {
            Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                DshPickerHeader(appText("编辑定时任务"), onDismiss,
                    action = appText("保存"), enabled = canSave, dismissEnabled = pendingRequestId == null, onAction = {
                        localError = null
                        pendingRequestId = stateHolder.updateScheduledTask(task, title, prompt, timeChange, targetChange)
                        if (pendingRequestId == null) localError = stateHolder.scheduledTaskMutationError ?: appText("操作正在进行，请稍后重试")
                    })
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(appText("标题"), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        ScheduleFilledField(title, { title = it }, appText("任务标题"), enabled = pendingRequestId == null, singleLine = true,
                            modifier = Modifier.testTag("scheduled-task-edit-title"))
                        Text(appText("最多 120 个字"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(appText("任务内容"), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        ScheduleFilledField(prompt, { prompt = it }, enabled = pendingRequestId == null, modifier = Modifier.height(176.dp))
                    }
                    if ("schedule-management-v2" in stateHolder.gatewayState.capabilities) {
                        ScheduleTargetControls(target, ScheduleTargetEditor.currentSessionId(recordJson) != null,
                            stateHolder.availableWorkspaces, enabled = pendingRequestId == null) { target = it }
                    }
                    targetError?.let { Text(appText(it), color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                    ScheduleTimingControls(timing, enabled = pendingRequestId == null) { timing = it }
                    timingError?.let { Text(appText(it), color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
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
        }
    }
}
