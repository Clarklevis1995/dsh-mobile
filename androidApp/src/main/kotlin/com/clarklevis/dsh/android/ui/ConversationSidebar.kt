package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupPositionProvider
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.shared.domain.SessionSummary
import com.clarklevis.dsh.shared.protocol.GatewayWorkspace

internal data class ConversationSidebarGroup(
    val id: String,
    val title: String,
    val sessions: List<SessionSummary>,
    val ungrouped: Boolean = false
)

/** 与 iOS 一致：隐藏空白会话和子智能体，同一会话可以出现在多个所属工作区中。 */
internal fun conversationSidebarGroups(workspaces: List<GatewayWorkspace>, sessions: List<SessionSummary>): List<ConversationSidebarGroup> {
    val visible = sessions.filter { it.isVisibleInHistory }
        .sortedWith(compareByDescending<SessionSummary> { it.lastActivityEpochSeconds }.thenBy { it.id })
    val owned = workspaces.flatMap { it.sessionIds }.toSet()
    return workspaces.map { workspace ->
        val ids = workspace.sessionIds.toSet()
        ConversationSidebarGroup(workspace.workspaceId, workspace.title, visible.filter { it.id in ids })
    } + ConversationSidebarGroup("__ungrouped__", appText("未分组"), visible.filter { it.id !in owned }, true)
}

/** 悬浮侧栏复用统一菜单容器；打开、关闭均不改变正文的宽度和阅读位置。 */
@Suppress("LongParameterList", "LongMethod") // Compose 容器集中声明侧栏内容和导航回调。
@Composable
internal fun ConversationSidebarButton(
    workspaces: List<GatewayWorkspace>,
    sessions: List<SessionSummary>,
    selectedSessionId: String?,
    onOpen: () -> Unit,
    onSelectSession: (String) -> Unit,
    onPlugins: () -> Unit,
    onScheduledTasks: () -> Unit,
    onSettings: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var expandedGroups by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val groups = remember(workspaces, sessions) { conversationSidebarGroups(workspaces, sessions) }
    val window = adaptiveWindowSize()
    val isDark = isSystemInDarkTheme()
    val height = (window.height - 96.dp).coerceAtLeast(200.dp)
    val edgePadding = 12.dp
    val shadowPadding = with(LocalDensity.current) { edgePadding.roundToPx() }
    var popupOrigin by remember { mutableStateOf(TransformOrigin(0f, 0f)) }
    val position = remember(shadowPadding) {
        ConversationSidebarPositionProvider(shadowPadding) { popupOrigin = it }
    }
    LaunchedEffect(selectedSessionId) { expanded = false }

    fun select(action: () -> Unit) {
        expanded = false
        action()
    }

    Box {
        val toggle = {
            onOpen()
            expandedGroups = (
                expandedGroups + groups.filter { group ->
                    group.sessions.any { it.id == selectedSessionId }
                }.map { it.id }
                ).distinct()
            expanded = !expanded
        }
        TopBarCircleButton(
            iconRes = R.drawable.ic_conversation_sidebar,
            description = appText("展开侧边栏"),
            onClick = toggle,
            modifier = Modifier.testTag("conversation-sidebar-toggle")
        )
        DshMenuPopup(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            positionProvider = position,
            modifier = Modifier.width((window.width - edgePadding * 2).coerceAtMost(300.dp))
                .height(height).testTag("conversation-sidebar"),
            shape = RoundedCornerShape(28.dp),
            shadowPadding = edgePadding,
            transformOrigin = popupOrigin
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    WhalenessOrcaIcon(Modifier.size(23.dp))
                    Image(
                        painterResource(if (isDark) R.drawable.dsh_whaleness_wordmark_dark else R.drawable.dsh_whaleness_wordmark),
                        null,
                        Modifier.size(144.dp, 23.dp)
                    )
                }
                SidebarNavigationItem("插件", R.drawable.ic_drawer_plugin, "conversation-sidebar-plugins") { select(onPlugins) }
                SidebarNavigationItem("定时任务", R.drawable.ic_drawer_schedule, "conversation-sidebar-scheduled-tasks") {
                    select(onScheduledTasks)
                }
                Text(
                    appText("工作区"),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.padding(start = 18.dp, top = 18.dp, bottom = 6.dp)
                )
                LazyColumn(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    groups.forEach { group ->
                        val isExpanded = group.id in expandedGroups
                        item("workspace:${group.id}") {
                            DshMenuItem(
                                text = { Text(group.title, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingIcon = {
                                    Icon(
                                        painterResource(
                                            if (group.ungrouped) R.drawable.ic_workspace_tray else R.drawable.ic_folder_outline
                                        ),
                                        null,
                                        Modifier.size(22.dp),
                                        tint = if (group.sessions.any { it.id == selectedSessionId }) {
                                            DshColors.Ocean
                                        } else {
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                        }
                                    )
                                },
                                onClick = {
                                    expandedGroups = if (isExpanded) expandedGroups - group.id else expandedGroups + group.id
                                },
                                modifier = Modifier.testTag("conversation-sidebar-workspace-${group.id}")
                                    .semantics { stateDescription = if (isExpanded) appText("已展开") else appText("已收起") }
                            )
                        }
                        if (isExpanded) {
                            if (group.sessions.isEmpty()) {
                                item("empty:${group.id}") {
                                    Text(
                                        appText("暂无会话"),
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                        modifier = Modifier.padding(start = 42.dp, top = 6.dp, bottom = 6.dp)
                                    )
                                }
                            }
                            items(group.sessions, key = { "session:${group.id}:${it.id}" }) { session ->
                                val isSelected = session.id == selectedSessionId
                                DshMenuItem(
                                    text = {
                                        Text(
                                            session.title.ifEmpty { appText("未命名会话") },
                                            fontSize = 15.sp,
                                            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    leadingIcon = {
                                        Box(
                                            Modifier.size(
                                                6.dp
                                            ).background(if (session.isRunning) Color(0xFF34C759) else Color.Transparent, CircleShape)
                                        )
                                    },
                                    trailingIcon = {
                                        Text(
                                            relativeTime(session.lastActivityEpochSeconds),
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.widthIn(max = 80.dp)
                                        )
                                    },
                                    onClick = { select { onSelectSession(session.id) } },
                                    modifier = Modifier.testTag("conversation-sidebar-session-${session.id}")
                                        .semantics { selected = isSelected }
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f) else Color.Transparent,
                                            RoundedCornerShape(9.dp)
                                        ),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                        start = 24.dp,
                                        end = 10.dp,
                                        top = 6.dp,
                                        bottom = 6.dp
                                    )
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                SidebarNavigationItem("设置", R.drawable.ic_settings, "conversation-sidebar-settings") { select(onSettings) }
            }
        }
    }
}

@Composable
private fun SidebarNavigationItem(title: String, icon: Int, tag: String, onClick: () -> Unit) {
    DshMenuItem(
        text = { Text(appText(title), fontSize = 16.sp) },
        leadingIcon = { Icon(painterResource(icon), null, Modifier.size(22.dp)) },
        onClick = onClick,
        modifier = Modifier.testTag(tag)
    )
}

internal class ConversationSidebarPositionProvider(
    private val shadowPadding: Int,
    private val onTransformOrigin: (TransformOrigin) -> Unit = {}
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        // 跟随按钮所在的实际窗口侧，兼容宽窄屏切换及从右向左布局。
        val right = anchorBounds.center.x >= windowSize.width / 2
        onTransformOrigin(TransformOrigin(if (right) 1f else 0f, 0f))
        return IntOffset(
            if (right) (windowSize.width - popupContentSize.width).coerceAtLeast(0) else 0,
            (anchorBounds.top - shadowPadding).coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
        )
    }
}
