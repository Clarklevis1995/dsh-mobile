package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.android.localization.appTextKey
import com.clarklevis.dsh.shared.protocol.GatewayWorkspace
import com.clarklevis.dsh.shared.protocol.ScheduleTargetDraft

@Composable
internal fun ScheduleTargetControls(
    draft: ScheduleTargetDraft,
    currentSessionAvailable: Boolean,
    workspaces: List<GatewayWorkspace>,
    enabled: Boolean,
    onChange: (ScheduleTargetDraft) -> Unit
) {
    var targetMenu by remember { mutableStateOf(false) }
    var workspaceMenu by remember { mutableStateOf(false) }
    val labels = mapOf("session" to appTextKey("当前关联会话"), "new_once" to appTextKey("新建会话并复用"), "new" to appTextKey("每次新建会话"))
    ScheduleSection {
        Box {
            ScheduleSettingRow(appText("执行位置"), appText(labels[draft.kind] ?: draft.kind), enabled,
                tag = "schedule-target-picker") { targetMenu = true }
            DshSelectionPopup(targetMenu,
                options = labels.map { (kind, label) -> DshSelectionOption(kind, appText(label), enabled = kind != "session" || currentSessionAvailable) },
                selectedKey = draft.kind, onDismissRequest = { targetMenu = false },
                onSelect = { onChange(draft.copy(kind = it)); targetMenu = false })
        }
        if (draft.kind == "new_once" || draft.kind == "new") {
            Text(appText(if (draft.kind == "new_once") "首次运行时创建，之后沿用这个会话" else "每次运行都创建独立会话"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box {
                val selected = workspaces.firstOrNull { it.path == draft.cwd }
                val title = if (draft.cwd.isEmpty()) appText("使用默认工作区")
                    else selected?.let { workspaceTitle(it.title) } ?: draft.cwd
                ScheduleSettingRow(appText("关联工作区"), title, enabled, tag = "schedule-workspace-picker") { workspaceMenu = true }
                if (workspaceMenu) {
                    val choices = listOf(ScheduleChoice("", appText("使用默认工作区"))) +
                        (if (draft.cwd.isNotEmpty() && selected == null) listOf(ScheduleChoice(draft.cwd, draft.cwd)) else emptyList()) +
                        workspaces.filter { it.path.isNotEmpty() }.distinctBy { it.path }.map { ScheduleChoice(it.path, workspaceTitle(it.title), it.path) }
                    ScheduleChoiceSheet(appText("关联工作区"), appText("搜索工作区"), draft.cwd, choices,
                        onDismiss = { workspaceMenu = false }, onSelect = { onChange(draft.copy(cwd = it)) })
                }
            }
        }
    }
}

private fun workspaceTitle(title: String): String =
    if (title == "default-workspace") appText("默认工作区") else title
