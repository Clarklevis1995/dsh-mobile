package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clarklevis.dsh.android.AndroidSharedStateHolder
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.shared.domain.SessionSummary
import com.clarklevis.dsh.shared.protocol.GatewayWorkspace

private const val COLLAPSED_SECTION_LIMIT = 5

/** 只用于全部会话；分区显示工作区名称，所有元素都有跨展开状态稳定的 key。 */
internal sealed interface WorkspaceSessionListEntry {
    val key: String

    data class Header(val workspaceId: String, val title: String, val number: Int, val count: Int) : WorkspaceSessionListEntry {
        override val key: String get() = "section-$workspaceId"
    }

    data class Session(val session: SessionSummary) : WorkspaceSessionListEntry {
        override val key: String get() = "session-${session.id}"
    }

    data class Toggle(val workspaceId: String, val remainingCount: Int, val expanded: Boolean) : WorkspaceSessionListEntry {
        override val key: String get() = "toggle-$workspaceId"
    }
}

/** 输入已经过工作区和搜索过滤；此处一次性建立归属索引，避免逐行扫描所有工作区。 */
internal fun allWorkspaceSessionEntries(
    sessions: List<SessionSummary>,
    workspaces: List<GatewayWorkspace>,
    expandedSectionIds: Set<String> = emptySet()
): List<WorkspaceSessionListEntry> {
    val owners = buildMap {
        workspaces.forEach { workspace ->
            workspace.sessionIds.forEach { id ->
                if (id !in this) put(id, workspace.workspaceId)
            }
        }
    }
    val titles = workspaces.associate { workspace ->
        workspace.workspaceId to workspace.title.ifBlank {
            workspace.path.trimEnd('/').substringAfterLast('/').ifBlank { appText("工作区") }
        }
    }
    val sorted = sessions.sortedWith(compareByDescending<SessionSummary> { it.lastActivityEpochSeconds }.thenBy { it.id })
    val groups = sorted.groupBy { owners[it.id] ?: AndroidSharedStateHolder.UNGROUPED_WORKSPACE_ID }
    val ordered = groups.entries.sortedWith(
        compareByDescending<Map.Entry<String, List<SessionSummary>>> { it.value.first().lastActivityEpochSeconds }
            .thenBy { it.key }
    )
    return buildList {
        ordered.forEachIndexed { index, (id, rows) ->
            val expanded = id in expandedSectionIds
            add(WorkspaceSessionListEntry.Header(id, titles[id] ?: appText("未分组"), index + 1, rows.size))
            val visible = if (expanded) rows else rows.take(COLLAPSED_SECTION_LIMIT)
            visible.forEach { add(WorkspaceSessionListEntry.Session(it)) }
            if (rows.size > COLLAPSED_SECTION_LIMIT) {
                add(WorkspaceSessionListEntry.Toggle(id, rows.size - COLLAPSED_SECTION_LIMIT, expanded))
            }
        }
    }
}

@Composable
internal fun WorkspaceSessionSectionHeader(entry: WorkspaceSessionListEntry.Header) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .padding(top = if (entry.number == 1) 8.dp else 24.dp, bottom = 8.dp)
            .semantics(mergeDescendants = true) {
                heading()
                contentDescription = if (entry.count == 1) appText("工作区 %1\$s，1 个会话", entry.title)
                    else appText("工作区 %1\$s，%2\$s 个会话", entry.title, entry.count)
            }
            .testTag("workspace-section-${entry.workspaceId}"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(entry.title, modifier = Modifier.weight(1f), color = Color.White.copy(alpha = 0.62f),
            fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        HorizontalDivider(Modifier.width(28.dp), thickness = 0.5.dp, color = Color.White.copy(alpha = 0.12f))
        Text(appText("%1\$s 个会话", entry.count), color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp)
    }
}

@Composable
internal fun WorkspaceSessionSectionToggle(entry: WorkspaceSessionListEntry.Toggle, onToggle: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(role = Role.Button) { onToggle(entry.workspaceId) }
            .padding(start = 18.dp)
            .testTag("workspace-section-toggle-${entry.workspaceId}"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (entry.expanded) appText("收起") else appText("展开其余 %1\$s 个会话", entry.remainingCount),
            color = Color.White.copy(alpha = 0.62f), fontSize = 13.sp)
        Icon(
            painter = painterResource(if (entry.expanded) R.drawable.ic_chevron_up else R.drawable.ic_question_chevron_down),
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.62f),
            modifier = Modifier.size(14.dp)
        )
    }
}
