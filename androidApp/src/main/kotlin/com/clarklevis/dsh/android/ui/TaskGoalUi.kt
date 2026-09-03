package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clarklevis.dsh.android.AndroidSharedStateHolder
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.shared.protocol.GatewayTask

/** WebUI 同源的任务 / Goal 区块，固定放在输入框上方。 */
@Composable
internal fun TaskGoalPanels(
    stateHolder: AndroidSharedStateHolder,
    modifier: Modifier = Modifier
) {
    val sessionId = stateHolder.snapshot.selectedSessionId
    val tasks = stateHolder.snapshot.taskSnapshot?.tasks
    val goal = stateHolder.snapshot.goalSnapshot?.goal
    var tasksExpanded by rememberSaveable(sessionId) { mutableStateOf(true) }
    var showGoalEditor by remember(sessionId) { mutableStateOf(false) }
    var confirmGoalClear by remember(sessionId) { mutableStateOf(false) }

    if (tasks != null || goal != null) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            tasks?.let {
                TaskPanel(
                    tasks = it,
                    expanded = tasksExpanded,
                    onExpandedChange = { tasksExpanded = !tasksExpanded }
                )
            }
            goal?.let { projection ->
                GoalPanel(
                    phase = projection.goal.phase,
                    objective = projection.goal.objective,
                    mutationKind = stateHolder.goalMutationKind,
                    onPauseResume = {
                        if (projection.goal.phase == "active") stateHolder.pauseGoal()
                        else stateHolder.resumeGoal()
                    },
                    onEdit = { showGoalEditor = true },
                    onClear = { confirmGoalClear = true }
                )
                if (showGoalEditor) {
                    GoalEditDialog(
                        initialObjective = projection.goal.objective,
                        onDismiss = { showGoalEditor = false },
                        onConfirm = { objective ->
                            showGoalEditor = false
                            stateHolder.editGoal(objective)
                        }
                    )
                }
                if (confirmGoalClear) {
                    AlertDialog(
                        onDismissRequest = { confirmGoalClear = false },
                        title = { Text("删除当前目标？") },
                        text = { Text("删除后，智能体不再持有这个持续目标。") },
                        dismissButton = {
                            TextButton(onClick = { confirmGoalClear = false }) { Text("取消") }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmGoalClear = false
                                stateHolder.clearGoal()
                            }) { Text("删除") }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskPanel(
    tasks: List<GatewayTask>,
    expanded: Boolean,
    onExpandedChange: () -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    // 与 Composer 一致：使用几乎不透明的 surface，而不是叠加在聊天内容上的浅色蒙层。
    val panelSurface = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
    val completed = tasks.count { it.status == "completed" }
    val active = tasks.count { it.status == "in_progress" }
    val pending = tasks.size - completed - active
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = panelSurface,
        border = androidx.compose.foundation.BorderStroke(
            0.8.dp,
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
        )
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onExpandedChange)
                    .semantics { contentDescription = if (expanded) "收起任务" else "展开任务" },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_checklist),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
                )
                Spacer(Modifier.width(10.dp))
                Text("任务", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Spacer(Modifier.width(12.dp))
                Text(
                    taskSummary(completed, active, pending),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.56f),
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_up),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp).rotate(if (expanded) 0f else 180f),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
            if (expanded) {
                Spacer(Modifier.height(10.dp))
                tasks.forEach { task -> TaskRow(task) }
            }
        }
    }
}

@Composable
private fun TaskRow(task: GatewayTask) {
    val iconSize = 24.dp
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (task.status) {
            "completed" -> Icon(
                painter = painterResource(R.drawable.ic_check_circle),
                contentDescription = "已完成",
                modifier = Modifier.size(iconSize),
                tint = DshColors.Success
            )
            "in_progress" -> CircularProgressIndicator(
                modifier = Modifier.size(21.dp).padding(2.dp),
                color = DshColors.Ocean,
                strokeWidth = 2.dp
            )
            else -> Icon(
                painter = painterResource(R.drawable.ic_task_pending),
                contentDescription = "待处理",
                modifier = Modifier.size(iconSize),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            task.content,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (task.status == "completed") 0.67f else 0.78f),
            fontSize = 16.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun GoalPanel(
    phase: String,
    objective: String,
    mutationKind: String?,
    onPauseResume: () -> Unit,
    onEdit: () -> Unit,
    onClear: () -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    // 与 Composer 使用相同的底色透明度，保证任务与目标区块不会透出聊天内容。
    val panelSurface = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
    val isActive = phase == "active"
    val phaseLabel = when (phase) {
        "active" -> "进行中的目标"
        "paused" -> "已暂停的目标"
        "blocked" -> "受阻的目标"
        else -> "当前目标"
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = panelSurface,
        border = androidx.compose.foundation.BorderStroke(
            0.8.dp,
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_goal_target),
                contentDescription = null,
                modifier = Modifier.size(25.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)
            )
            Spacer(Modifier.width(10.dp))
            Text(phaseLabel, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Spacer(Modifier.width(12.dp))
            Text(
                objective,
                modifier = Modifier.weight(1f),
                fontSize = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (mutationKind != null) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = DshColors.Ocean
                )
            } else {
                GoalActionButton(
                    icon = if (isActive) R.drawable.ic_pause_circle else R.drawable.ic_play_circle,
                    description = if (isActive) "暂停目标" else "继续目标",
                    onClick = onPauseResume
                )
                GoalActionButton(R.drawable.ic_pencil_line, "编辑目标", onEdit)
                GoalActionButton(R.drawable.ic_trash, "删除目标", onClear)
            }
        }
    }
}

@Composable
private fun GoalActionButton(icon: Int, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(36.dp).clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        )
    }
}

@Composable
private fun GoalEditDialog(
    initialObjective: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var objective by remember(initialObjective) { mutableStateOf(initialObjective) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑目标") },
        text = {
            OutlinedTextField(
                value = objective,
                onValueChange = { objective = it },
                label = { Text("目标") },
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = {
            TextButton(
                enabled = objective.trim().isNotEmpty(),
                onClick = { onConfirm(objective.trim()) }
            ) { Text("保存") }
        }
    )
}

private fun taskSummary(completed: Int, active: Int, pending: Int): String = buildList {
    if (completed > 0) add("$completed 已完成")
    if (active > 0) add("$active 进行中")
    if (pending > 0) add("$pending 待处理")
}.joinToString(" · ").ifEmpty { "暂无任务" }
