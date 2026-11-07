package com.clarklevis.dsh.android.ui

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.shadow.Shadow
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.android.AndroidSharedStateHolder
import com.clarklevis.dsh.android.MobileScheduledTask
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.shared.gateway.GatewayConnectionState
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val scheduleBlue = Color(0xFF0A84FF)

internal enum class ScheduledTaskStatusFilter(private val sourceTitle: String) {
    ACTIVE("已开启"), PAUSED("已暂停"), ENDED("已结束"), ALL("全部");

    val title: String get() = appText(sourceTitle)
}

internal fun filteredScheduledTasks(
    tasks: List<MobileScheduledTask>,
    status: ScheduledTaskStatusFilter,
    query: String
): List<MobileScheduledTask> {
    val search = query.trim()
    return tasks.filter { task ->
        val matchesStatus = when (status) {
            ScheduledTaskStatusFilter.ALL -> true
            ScheduledTaskStatusFilter.ACTIVE -> task.status == "active"
            ScheduledTaskStatusFilter.PAUSED -> task.status == "paused"
            ScheduledTaskStatusFilter.ENDED -> task.status != "active" && task.status != "paused"
        }
        matchesStatus && (search.isEmpty() || task.title.contains(search, ignoreCase = true) ||
            task.prompt.contains(search, ignoreCase = true))
    }
}

@Composable
internal fun ScheduledTasksScreen(
    stateHolder: AndroidSharedStateHolder,
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
    onCreateTask: () -> Unit = {}
) {
    ScheduleTheme { ScheduledTasksContent(stateHolder, onBack, onOpenSession, onCreateTask) }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ScheduledTasksContent(stateHolder: AndroidSharedStateHolder, onBack: () -> Unit,
    onOpenSession: (String) -> Unit, onCreateTask: () -> Unit) {
    val dark = isSystemInDarkTheme()
    var historyTask by remember { mutableStateOf<MobileScheduledTask?>(null) }
    var editingTask by remember { mutableStateOf<MobileScheduledTask?>(null) }
    var deletingTask by remember { mutableStateOf<MobileScheduledTask?>(null) }
    var revealedTaskId by remember { mutableStateOf<String?>(null) }
    var showsMutationError by remember { mutableStateOf(false) }
    var confirmsDeleteEnded by remember { mutableStateOf(false) }
    var statusFilter by rememberSaveable { mutableStateOf(ScheduledTaskStatusFilter.ALL) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isSearching by rememberSaveable { mutableStateOf(false) }
    val visibleTasks = filteredScheduledTasks(stateHolder.scheduledTasks, statusFilter, searchQuery)
    val connection = stateHolder.gatewayState.connection
    LaunchedEffect(connection) {
        if (connection == GatewayConnectionState.CONNECTED) stateHolder.refreshScheduledTasks()
        else if (stateHolder.scheduledTasks.isEmpty()) stateHolder.refreshScheduledTasks()
    }
    Column(
        Modifier.fillMaxSize()
            .background(if (dark) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surface)
            .statusBarsPadding().navigationBarsPadding().testTag("scheduled-tasks-screen")
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(66.dp).padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp)) {
            val controlsMaximumWidth = (maxWidth - 52.dp).coerceAtLeast(44.dp)
            val titleMaximumWidth = (maxWidth - if (isSearching) 96.dp else 192.dp).coerceAtLeast(36.dp)
            Box(Modifier.fillMaxSize()) {
                TopBarCircleButton(R.drawable.ic_back_chevron, appText("返回"), onBack,
                    modifier = Modifier.align(Alignment.CenterStart).testTag("scheduled-back"))
                Text(appText("定时任务"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.align(Alignment.Center).widthIn(max = titleMaximumWidth),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!isSearching) {
                ScheduledTaskSearchAndActions(
                    maximumWidth = controlsMaximumWidth,
                    query = searchQuery,
                    onQueryChange = { searchQuery = it; revealedTaskId = null },
                    isSearching = isSearching,
                    onSearchingChange = { isSearching = it },
                    onCreateTask = onCreateTask,
                    onDeleteEnded = { confirmsDeleteEnded = true },
                    canDeleteEnded = "schedule-management-v2" in stateHolder.gatewayState.capabilities &&
                        connection == GatewayConnectionState.CONNECTED && stateHolder.scheduledTaskPendingId == null,
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
                }
            }
        }
        if (isSearching) {
            BoxWithConstraints(Modifier.adaptiveContentWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                ScheduledTaskSearchAndActions(maxWidth, searchQuery, { searchQuery = it; revealedTaskId = null }, true,
                    { isSearching = it }, onCreateTask, { confirmsDeleteEnded = true }, false)
            }
        }
        ScheduledTaskStatusSwitchBar(statusFilter, onStatusChange = {
            statusFilter = it
            revealedTaskId = null
            stateHolder.filterScheduledTasks(when (it) {
                ScheduledTaskStatusFilter.ALL -> null
                ScheduledTaskStatusFilter.ACTIVE -> true
                ScheduledTaskStatusFilter.PAUSED, ScheduledTaskStatusFilter.ENDED -> false
            })
        })
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = stateHolder.scheduledTasksLoading && stateHolder.scheduledTasks.isNotEmpty(),
            onRefresh = stateHolder::refreshScheduledTasks, modifier = Modifier.weight(1f).fillMaxWidth()) {
        when {
            stateHolder.scheduledTasksLoading && stateHolder.scheduledTasks.isEmpty() -> {
                Column(Modifier.fillMaxWidth().padding(top = 72.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(color = scheduleBlue, modifier = Modifier.size(20.dp))
                    Text(appText("正在加载定时任务…"), fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            stateHolder.scheduledTasksError != null && stateHolder.scheduledTasks.isEmpty() -> {
                ScheduleEmptyState(appText("无法显示定时任务"), stateHolder.scheduledTasksError.orEmpty(), R.drawable.ic_schedule_offline,
                    onRetry = stateHolder::refreshScheduledTasks)
            }
            stateHolder.scheduledTasks.isEmpty() -> {
                ScheduleEmptyState(appText("暂无定时任务"), appText("在会话中创建的定时任务会显示在这里"), R.drawable.ic_drawer_schedule)
            }
            visibleTasks.isEmpty() -> {
                ScheduleEmptyState(appText("没有符合条件的定时任务"),
                    if (searchQuery.isBlank()) appText("试试其他任务状态") else appText("试试其他关键词或任务状态"), R.drawable.ic_filter_list)
            }
            else -> LazyColumn(
                modifier = Modifier.adaptiveContentWidth().fillMaxSize().testTag("scheduled-tasks-content"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 20.dp, end = 20.dp, top = 24.dp, bottom = 20.dp
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if ("schedule-management-v2" in stateHolder.gatewayState.capabilities && stateHolder.scheduledTasks.size < stateHolder.scheduledTasksTotal) {
                    item { TextButton(onClick = stateHolder::loadMoreScheduledTasks, enabled = !stateHolder.scheduledTasksLoading) { Text(appText("加载更多任务")) } }
                }
                items(visibleTasks, key = { it.id }) { task ->
                    Column {
                    ScheduledTaskCard(
                        task = task,
                        sessionTitle = stateHolder.snapshot.sessions.firstOrNull { it.id == task.sessionId }?.title
                            ?: task.sessionId,
                        revealedTaskId = revealedTaskId,
                        onRevealChange = { revealedTaskId = it },
                        onOpenSession = {
                            if (revealedTaskId == task.id) revealedTaskId = null
                            else {
                                revealedTaskId = null
                                if (task.sessionId.isNotBlank()) onOpenSession(task.sessionId)
                            }
                        },
                        onEdit = {
                            revealedTaskId = null
                            editingTask = task
                        },
                        onDelete = {
                            revealedTaskId = null
                            deletingTask = task
                        },
                        onShowHistory = if ("schedule-management-v2" in stateHolder.gatewayState.capabilities) {
                            { historyTask = task; stateHolder.loadScheduleHistory(task) }
                        } else null,
                        busy = stateHolder.scheduledTaskPendingId != null
                    )
                    }
                }
            }
        }
        }
    }
    historyTask?.let { task ->
        ScheduledTaskHistorySheet(stateHolder, task, onDismiss = { historyTask = null }, onOpenSession = {
            historyTask = null
            onOpenSession(it)
        })
    }
    editingTask?.let { task ->
        ScheduledTaskEditSheet(task, stateHolder, onDismiss = { editingTask = null })
    }
    deletingTask?.let { task ->
        AlertDialog(
            onDismissRequest = { deletingTask = null },
            title = { Text(appText("删除定时任务？")) },
            text = { DshMenuHost { Text(appText("删除后任务及投递记录无法恢复；已进入会话队列的消息不会撤回。"))  } },
            confirmButton = {
                TextButton(onClick = {
                    stateHolder.deleteScheduledTask(task)
                    deletingTask = null
                }) { Text(appText("删除任务及投递记录"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deletingTask = null }) { Text(appText("取消")) } }
        )
    }
    LaunchedEffect(stateHolder.scheduledTaskMutationError) {
        if (stateHolder.scheduledTaskMutationError != null && editingTask == null) showsMutationError = true
    }
    if (confirmsDeleteEnded) {
        AlertDialog(
            onDismissRequest = { confirmsDeleteEnded = false },
            title = { Text(appText("删除已结束的定时任务？")) },
            text = { DshMenuHost { Text(appText("已结束任务及其投递记录将被删除，无法恢复。")) } },
            confirmButton = {
                TextButton(onClick = {
                    confirmsDeleteEnded = false
                    stateHolder.clearEndedScheduledTasks()
                }) { Text(appText("删除"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmsDeleteEnded = false }) { Text(appText("取消")) } }
        )
    }
    if (showsMutationError) {
        AlertDialog(
            onDismissRequest = { showsMutationError = false },
            title = { Text(appText("定时任务操作失败")) },
            text = { DshMenuHost { Text(stateHolder.scheduledTaskMutationError ?: appText("请稍后重试"))  } },
            confirmButton = { TextButton(onClick = { showsMutationError = false }) { Text(appText("好")) } }
        )
    }
}

@Composable
private fun ScheduledTaskSearchAndActions(
    maximumWidth: Dp,
    query: String,
    onQueryChange: (String) -> Unit,
    isSearching: Boolean,
    onSearchingChange: (Boolean) -> Unit,
    onCreateTask: () -> Unit,
    onDeleteEnded: () -> Unit,
    canDeleteEnded: Boolean,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var moreExpanded by remember { mutableStateOf(false) }
    val width by animateDpAsState(if (isSearching) maximumWidth else 96.dp, label = "schedule-search-width")
    LaunchedEffect(isSearching) {
        if (isSearching) focusRequester.requestFocus()
    }

    if (isSearching) {
        Row(
            modifier.width(width).height(46.dp).testTag("scheduled-search-controls")
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(23.dp))
                .border(0.7.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), RoundedCornerShape(23.dp)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(painterResource(R.drawable.ic_search), null,
                Modifier.padding(start = 12.dp).size(19.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f).padding(start = 7.dp).focusRequester(focusRequester)
                    .semantics { contentDescription = appText("搜索定时任务") },
                singleLine = true,
                textStyle = TextStyle(fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Text(appText("搜索定时任务"), fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        innerTextField()
                    }
                }
            )
            Box(
                Modifier.size(40.dp).clickable {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                    onQueryChange("")
                    onSearchingChange(false)
                }.semantics { contentDescription = appText("关闭搜索") },
                contentAlignment = Alignment.Center
            ) {
                Icon(painterResource(R.drawable.ic_close), null, Modifier.size(18.dp))
            }
        }
    } else {
        Row(modifier.testTag("scheduled-search-controls"), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TopBarCircleButton(R.drawable.ic_search, appText("搜索定时任务"), { onSearchingChange(true) },
                Modifier.testTag("scheduled-tasks-search"))
            Box {
                TopBarCircleButton(R.drawable.ic_more_horizontal, appText("更多"), { moreExpanded = true },
                    Modifier.testTag("scheduled-tasks-more"))
                DshMenu(expanded = moreExpanded, onDismissRequest = { moreExpanded = false }, modifier = Modifier.width(280.dp)) {
                    DshMenuItem(
                        text = { Text(appText("添加定时任务")) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_schedule_create), null, Modifier.size(20.dp)) },
                        modifier = Modifier.testTag("scheduled-tasks-create"),
                        onClick = { moreExpanded = false; onCreateTask() }
                    )
                    DshMenuItem(
                        text = { Text(appText("删除已结束的定时任务"), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_schedule_trash), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.error) },
                        enabled = canDeleteEnded,
                        modifier = Modifier.testTag("scheduled-tasks-delete-ended"),
                        onClick = { moreExpanded = false; onDeleteEnded() }
                    )
                }
            }
        }
    }
}

@Composable
private fun ScheduledTaskStatusSwitchBar(status: ScheduledTaskStatusFilter, onStatusChange: (ScheduledTaskStatusFilter) -> Unit) {
    val hapticView = LocalView.current
    Row(Modifier.adaptiveContentWidth().padding(horizontal = 20.dp, vertical = 8.dp)
        .clip(CircleShape)
        .background(if (isSystemInDarkTheme()) Color(0xFF1C1C1E) else Color(0xFFEEEEF0))
        .padding(2.dp).selectableGroup().testTag("scheduled-tasks-status-tabs")) {
        ScheduledTaskStatusFilter.entries.forEach { option ->
            val selected = status == option
            Box(Modifier.weight(1f).heightIn(min = 28.dp)
                .clip(CircleShape)
                .background(if (!selected) Color.Transparent else if (isSystemInDarkTheme()) Color(0xFF636366) else Color.White)
                .selectable(selected = selected, role = Role.Tab, onClick = {
                    if (!selected) {
                        // 与 iOS 的选择触感一致：切换时轻振一次，并遵循系统触感开关。
                        hapticView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        onStatusChange(option)
                    }
                })
                .testTag("scheduled-status-${option.name.lowercase(Locale.ROOT)}")
                .padding(horizontal = 4.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
                Text(option.title, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun ScheduledTaskCard(
    task: MobileScheduledTask,
    sessionTitle: String,
    revealedTaskId: String?,
    onRevealChange: (String?) -> Unit,
    onOpenSession: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    busy: Boolean,
    onShowHistory: (() -> Unit)? = null
) {
    val dark = isSystemInDarkTheme()
    var expanded by rememberSaveable(task.id) { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 0f else 180f,
        animationSpec = tween(220),
        label = "schedule-chevron"
    )
    val density = LocalDensity.current
    val canEdit = com.clarklevis.dsh.shared.protocol.ScheduleTaskPresentation.canEdit(task.status)
    val revealWidthPx = with(density) { (if (canEdit) 128.dp else 68.dp).toPx() }
    val flingThresholdPx = with(density) { 280.dp.toPx() }
    var offsetPx by remember(task.id) {
        mutableFloatStateOf(if (revealedTaskId == task.id) -revealWidthPx else 0f)
    }
    var animationJob by remember(task.id) { mutableStateOf<Job?>(null) }
    var localSettleTarget by remember(task.id) { mutableStateOf<Boolean?>(null) }
    var startedOpen by remember(task.id) { mutableStateOf(false) }
    var suppressCardTapUntil by remember(task.id) { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()
    val progress = (-offsetPx / revealWidthPx).coerceIn(0f, 1f)

    fun settle(open: Boolean, velocity: Float = 0f) {
        animationJob?.cancel()
        animationJob = scope.launch {
            animate(
                initialValue = offsetPx,
                targetValue = if (open) -revealWidthPx else 0f,
                initialVelocity = velocity,
                animationSpec = spring(stiffness = 450f, dampingRatio = 0.86f)
            ) { value, _ -> offsetPx = value.coerceIn(-revealWidthPx, 0f) }
        }
    }

    LaunchedEffect(revealedTaskId) {
        val shouldOpen = revealedTaskId == task.id
        if (localSettleTarget == shouldOpen) {
            localSettleTarget = null
        } else if (!shouldOpen && offsetPx != 0f) {
            settle(open = false)
        }
    }

    Box(Modifier.fillMaxWidth().testTag("scheduled-card-${task.id}")) {
        Box(Modifier.matchParentSize()) {
            Box(
                Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                    .requiredWidth(with(density) { (-offsetPx).coerceAtLeast(0f).toDp() })
                    .clipToBounds()
            ) {
                Row(
                    Modifier.align(Alignment.CenterEnd).requiredWidth(if (canEdit) 119.dp else 59.dp)
                        .offset(x = (18 * (1f - progress)).dp)
                        .graphicsLayer {
                            alpha = progress
                            scaleX = 0.72f + 0.28f * progress
                            scaleY = 0.72f + 0.28f * progress
                            transformOrigin = TransformOrigin(1f, 0.5f)
                        }.then(if (progress < 0.95f) Modifier.clearAndSetSemantics {} else Modifier),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (canEdit) IconButton(
                        onClick = onEdit,
                        enabled = progress > 0.95f && !busy,
                        modifier = Modifier.size(50.dp)
                            .clip(CircleShape)
                            .background(
                                scheduleBlue,
                                CircleShape
                            )
                            .border(
                                0.8.dp,
                                MaterialTheme.colorScheme.onSurface.copy(alpha = if (dark) 0.18f else 0.11f),
                                CircleShape
                            )
                    ) {
                        Icon(painterResource(R.drawable.ic_schedule_edit), contentDescription = appText("编辑%1\$s", task.title),
                            modifier = Modifier.size(14.dp), tint = Color.White)
                    }
                    IconButton(
                        onClick = onDelete,
                        enabled = progress > 0.95f && !busy,
                        modifier = Modifier.size(50.dp).background(Color(0xFFD65052), CircleShape)
                    ) {
                        Icon(painterResource(R.drawable.ic_schedule_trash), contentDescription = appText("删除%1\$s", task.title),
                            modifier = Modifier.size(24.dp), tint = Color.White)
                    }
                }
            }
        }

        Column(
            Modifier.fillMaxWidth()
                .offset { IntOffset(offsetPx.roundToInt(), 0) }
                .testTag("scheduled-card-body-${task.id}")
                .dropShadow(
                    shape = RoundedCornerShape(28.dp),
                    shadow = Shadow(
                        radius = 14.dp,
                        color = Color.Black.copy(alpha = 0.08f),
                        offset = DpOffset(0.dp, 4.dp)
                    )
                )
                .background(if (dark) MaterialTheme.colorScheme.surfaceVariant else Color.White, RoundedCornerShape(28.dp))
                .border(0.8.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f), RoundedCornerShape(28.dp))
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        animationJob?.cancel()
                        offsetPx = (offsetPx + delta).coerceIn(-revealWidthPx, 0f)
                    },
                    onDragStarted = {
                        startedOpen = revealedTaskId == task.id
                        suppressCardTapUntil = SystemClock.uptimeMillis() + 350L
                    },
                    onDragStopped = { velocity ->
                        val open = when {
                            velocity < -flingThresholdPx -> true
                            velocity > flingThresholdPx -> false
                            else -> -offsetPx >= revealWidthPx * (if (startedOpen) 0.75f else 0.25f)
                        }
                        suppressCardTapUntil = SystemClock.uptimeMillis() + 350L
                        localSettleTarget = open
                        onRevealChange(if (open) task.id else null)
                        settle(open, velocity)
                    }
                )
        ) {
            Column(
                Modifier.fillMaxWidth().clickable {
                    if (SystemClock.uptimeMillis() >= suppressCardTapUntil) onOpenSession()
                }
                    .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 18.dp)
            ) {
                Text(shortRule(task), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = scheduleBlue)
                Text(
                    task.title, fontSize = 21.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Text(
                    task.prompt, fontSize = 16.sp, lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 11.dp)
                )
            }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().clickable {
                    if (SystemClock.uptimeMillis() >= suppressCardTapUntil) {
                        if (revealedTaskId == task.id) onRevealChange(null)
                        else expanded = !expanded
                    }
                }.padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (task.status == "active") formatScheduleDate(task.scheduledAt) else appText(com.clarklevis.dsh.shared.protocol.ScheduleTaskPresentation.statusLabel(task.status)),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp,
                    modifier = Modifier.weight(1f)
                )
                Box(Modifier.size(width = 36.dp, height = 34.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_up),
                        contentDescription = if (expanded) appText("收起任务详情") else appText("展开任务详情"),
                        modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = chevronRotation },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(expandFrom = Alignment.Top, animationSpec = tween(220)),
                exit = shrinkVertically(shrinkTowards = Alignment.Top, animationSpec = tween(220))
            ) {
                Column(
                    Modifier.fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(13.dp)
                ) {
                    ScheduleDetail(appText("执行规则"), fullRule(task))
                    ScheduleDetail(appText("下次计划时间"), if (task.status == "active") formatScheduleDate(task.scheduledAt) else appText("无"))
                    ScheduleDetail(appText("所属会话"), sessionTitle)
                    ScheduleDetail(appText("状态"), appText(com.clarklevis.dsh.shared.protocol.ScheduleTaskPresentation.statusLabel(task.status)))
                    ScheduleDetail(appText("最近投递"), task.lastDelivery?.get("deliveredAt")?.stringValue?.let { appText("已投递 · %1\$s", formatScheduleDate(it)) } ?: appText("暂无投递记录"))
                    if (onShowHistory != null) {
                        HorizontalDivider()
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .clickable(role = Role.Button, onClick = onShowHistory)
                                .padding(vertical = 8.dp).testTag("scheduled-task-history"),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(painterResource(R.drawable.ic_schedule_history), null, Modifier.size(24.dp), tint = scheduleBlue)
                            Text(appText("查看运行轨迹"), modifier = Modifier.weight(1f),
                                fontSize = 15.sp, lineHeight = 20.sp,
                                fontWeight = FontWeight.Normal, color = scheduleBlue)
                            Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(20.dp), tint = scheduleBlue)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ScheduledTaskHistorySheet(
    stateHolder: AndroidSharedStateHolder,
    task: MobileScheduledTask,
    onDismiss: () -> Unit,
    onOpenSession: (String) -> Unit
) {
    ScheduledTaskHistorySheet(
        task = task,
        history = stateHolder.scheduleHistory,
        loading = stateHolder.scheduleHistoryLoading,
        error = stateHolder.scheduleHistoryError,
        onLoadHistory = { before -> stateHolder.loadScheduleHistory(task, before) },
        onDismiss = onDismiss,
        onOpenSession = onOpenSession
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduledTaskHistorySheet(
    task: MobileScheduledTask,
    history: com.clarklevis.dsh.shared.protocol.JsonValue?,
    loading: Boolean,
    error: String?,
    onLoadHistory: (String?) -> Unit,
    onDismiss: () -> Unit,
    onOpenSession: (String) -> Unit
) {
    val records = history?.get("records")?.arrayValue.orEmpty()
    val before = history?.get("nextBefore")?.stringValue
    ScheduledTaskModalSheet(
        onDismiss = onDismiss,
        modifier = Modifier.testTag("scheduled-task-history-sheet"),
        contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal) }
    ) {
        DshMenuColumnHost {
            val backdrop = rememberLayerBackdrop()
            val density = LocalDensity.current
            val bottomInset = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
            var footerHeight by remember { mutableStateOf(52.dp + bottomInset) }
            val hasSession = task.sessionId.isNotBlank()
            Column(Modifier.fillMaxSize()) {
                DshPickerHeader(appText("运行轨迹"), onDismiss,
                    dismissLabel = appText("关闭"), capsuleActions = true)
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    LazyColumn(Modifier.fillMaxSize().layerBackdrop(backdrop).testTag("scheduled-task-history-records"),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 20.dp, top = 20.dp, end = 20.dp,
                            bottom = 20.dp + if (hasSession) footerHeight else bottomInset
                        ),
                        verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        item { Text(task.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
                        items(records.size) { index ->
                            val record = records[index]
                            var expanded by rememberSaveable(task.id, index) { mutableStateOf(false) }
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(painterResource(R.drawable.ic_drawer_schedule), null, Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    val timestamp = record["scheduledAt"]?.stringValue ?: record["deliveredAt"]?.stringValue
                                    Text(timestamp?.let(::formatScheduleDate) ?: appText("时间未知"),
                                        fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold,
                                        style = TextStyle(platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)))
                                }
                                Column(Modifier.padding(start = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(record["title"]?.stringValue ?: task.title, fontSize = 17.sp)
                                    Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        Text(appText("详情"), color = MaterialTheme.colorScheme.primary, fontSize = 17.sp)
                                        Spacer(Modifier.weight(1f))
                                        Icon(painterResource(R.drawable.ic_chevron_up), appText(if (expanded) "收起" else "展开"), Modifier.size(14.dp).graphicsLayer { rotationZ = if (expanded) 0f else 180f }, tint = MaterialTheme.colorScheme.primary)
                                    }
                                    if (expanded) {
                                        Text(record["prompt"]?.stringValue ?: task.prompt, fontSize = 15.sp)
                                        record["deliveredAt"]?.stringValue?.let { Text(formatScheduleDate(it)) }
                                        record["messageId"]?.stringValue?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                        (record["targetSessionId"] ?: record["sessionId"])?.stringValue?.takeIf { it.isNotBlank() && it != task.sessionId }?.let { session ->
                                            TextButton(onClick = { onOpenSession(session) }) { Text(appText("关联会话")) }
                                        }
                                    }
                                    HorizontalDivider()
                                }
                            }
                        }
                        item {
                            when {
                                loading -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                                error != null -> Column {
                                    Text(error.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    TextButton(onClick = { onLoadHistory(before) }) { Text(appText("重试")) }
                                }
                                records.isEmpty() -> Text(appText("暂无运行轨迹"))
                                before != null -> TextButton(onClick = { onLoadHistory(before) }) { Text(appText("加载更早轨迹")) }
                            }
                        }
                        if (history?.get("retention")?.let { it != com.clarklevis.dsh.shared.protocol.JsonValue.NullValue } == true ||
                            history?.get("earlierRecordsPruned")?.booleanValue == true || history?.get("earlierRecordsUnavailable")?.booleanValue == true) {
                            item { Text(appText("仅显示宿主仍保留的运行记录"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                    if (hasSession) {
                        // Dialog 的系统 Configuration 可能与应用主题不同，以当前面板主题为准。
                        val surface = MaterialTheme.colorScheme.surface
                        val tint = if (surface.luminance() < 0.5f) Color(0xFF242424) else Color(0xFFF3F4F4)
                        val material = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !LocalUiEffectsReduced.current) {
                            Modifier.topBarGlassSurface(backdrop, tint, surfaceAlpha = 0.92f)
                        } else Modifier.background(tint)
                        // 材质覆盖到底部安全区，滚动正文在底栏下方模糊，不穿透按钮。
                        Row(
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                // 先遮住未模糊的原始正文，再叠加采样材质，防止清晰文字透底。
                                .background(surface).clipToBounds()
                                .then(material)
                                .onSizeChanged { footerHeight = with(density) { it.height.toDp() } }
                                .testTag("scheduled-task-history-session-footer")
                                .clickable(role = Role.Button) { onOpenSession(task.sessionId) }
                                .navigationBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(painterResource(R.drawable.ic_schedule_conversation), null,
                                Modifier.size(width = 24.dp, height = 19.dp)
                                    .testTag("scheduled-task-history-session-icon"), tint = scheduleBlue)
                            Text(appText("关联会话"), fontSize = 17.sp, color = scheduleBlue)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleDetail(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(92.dp))
        Text(value, fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f))
    }
}

private fun shortRule(task: MobileScheduledTask): String = when (task.kind) {
    "daily" -> appText("每天")
    "weekly" -> appText("每周")
    "every" -> appText("每 %1\$s", duration(task.everySeconds?.toInt() ?: 0))
    "cron" -> "Cron"
    "rrule" -> {
        val draft = initialTimingDraft(task)
        if (draft.frequency == "once") appText("一次")
        else if (draft.frequency == "advanced") appText("自定义")
        else if (draft.interval == "1") appText(scheduleFrequencyNames[draft.frequency] ?: "自定义")
        else appText("每 %1\$s %2\$s执行", draft.interval, appText(when (draft.frequency) {
            "minutely" -> "分钟"; "hourly" -> "小时"; "daily" -> "天"; "weekly" -> "周"; else -> "个月"
        }))
    }
    else -> appText("一次")
}

private fun fullRule(task: MobileScheduledTask): String = when (task.kind) {
    "daily" -> appText("每天 %1\$s · %2\$s", task.time.orEmpty(), task.timeZone.orEmpty())
    "weekly" -> {
        val days = task.weekdays?.arrayValue.orEmpty().mapNotNull { it.doubleValue?.toInt() }
        val dayNames = listOf(appText("周一"), appText("周二"), appText("周三"), appText("周四"), appText("周五"), appText("周六"), appText("周日"))
        appText("每周 %1\$s · %2\$s · %3\$s", days.mapNotNull { dayNames.getOrNull(it - 1) }.joinToString(appText("、")), task.time.orEmpty(), task.timeZone.orEmpty())
    }
    "every" -> appText("每 %1\$s执行一次", duration(task.everySeconds?.toInt() ?: 0))
    "cron" -> "${task.expression.orEmpty()} · ${task.timeZone.orEmpty()}"
    "after" -> appText("%1\$s 秒后执行一次", task.raw["afterSeconds"]?.doubleValue?.toInt() ?: 0)
    "at" -> appText("指定时间执行一次")
    "rrule" -> (task.raw["timing"] ?: task.raw)["rrule"]?.stringValue.orEmpty()
    else -> task.raw.toJsonElement().toString()
}

private fun duration(seconds: Int): String = when {
    seconds > 0 && seconds % 86_400 == 0 -> appText("%1\$s 天", seconds / 86_400)
    seconds > 0 && seconds % 3_600 == 0 -> appText("%1\$s 小时", seconds / 3_600)
    seconds > 0 && seconds % 60 == 0 -> appText("%1\$s 分钟", seconds / 60)
    else -> appText("%1\$s 秒", seconds)
}

private fun formatScheduleDate(value: String): String {
    val input = listOf("yyyy-MM-dd'T'HH:mm:ss.SSSX", "yyyy-MM-dd'T'HH:mm:ssX")
    val date = input.firstNotNullOfOrNull { pattern ->
        runCatching { SimpleDateFormat(pattern, Locale.US).parse(value) }.getOrNull()
    } ?: return value
    return SimpleDateFormat(appText("yyyy年M月d日 HH:mm"), Locale.getDefault()).format(date)
}

@Composable
private fun ScheduleCreateDialog(stateHolder: AndroidSharedStateHolder, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var timing by remember { mutableStateOf("{\"kind\":\"every\",\"every_seconds\":3600}") }
    var target by remember { mutableStateOf("new_once") }
    var sessionId by remember { mutableStateOf(stateHolder.snapshot.selectedSessionId.orEmpty()) }
    var requestId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(stateHolder.scheduledTaskCompletedRequestId) {
        if (requestId != null && requestId == stateHolder.scheduledTaskCompletedRequestId) onDismiss()
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("创建定时任务") },
        text = {
            DshMenuHost {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.compose.material3.OutlinedTextField(title, { title = it }, label = { Text("标题") })
                    androidx.compose.material3.OutlinedTextField(prompt, { prompt = it }, label = { Text("任务内容") })
                    Text("执行规则使用 Host JSON，支持 after、at、every、daily、weekly、cron、rrule。时区使用 IANA 名称。")
                    androidx.compose.material3.OutlinedTextField(timing, { timing = it }, label = { Text("执行规则 JSON") })
                    listOf("new_once" to "首次新建并复用会话", "new" to "每次新建会话", "session" to "已有会话").forEach { (kind, label) ->
                        TextButton(onClick = { target = kind }) { Text((if (target == kind) "✓ " else "") + label) }
                    }
                    if (target == "session") androidx.compose.material3.OutlinedTextField(sessionId, { sessionId = it }, label = { Text("会话 ID") })
                    val preview = remember(timing) {
                        runCatching { kotlinx.serialization.json.Json.parseToJsonElement(timing).toString() }.getOrNull()
                    }
                    Text("规则预览 · ${preview ?: "执行规则 JSON 无效"}")
                    Text(scheduleNextExecutionPreview(timing))
                    Text("当前时区 · ${java.util.TimeZone.getDefault().id}")
                    stateHolder.scheduledTaskMutationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { requestId = stateHolder.createScheduledTask(title, prompt, timing, target, sessionId) },
            enabled = stateHolder.scheduledTaskPendingId == null) { Text("创建") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

private fun scheduleNextExecutionPreview(json: String): String {
    val rule = runCatching { com.clarklevis.dsh.shared.protocol.JsonValue.fromJsonElement(
        kotlinx.serialization.json.Json.parseToJsonElement(json)) }.getOrNull() ?: return "请填写有效规则"
    val kind = rule["kind"]?.stringValue ?: return "请填写规则类型"
    val spec = rule[kind]
    val zone = java.util.TimeZone.getTimeZone(spec?.get("time_zone")?.stringValue ?: java.util.TimeZone.getDefault().id)
    val now = java.util.Date()
    val next = when (kind) {
        "after", "every" -> rule[if (kind == "after") "after_seconds" else "every_seconds"]?.doubleValue
            ?.takeIf { it > 0 && it < 315_576_000 }?.let { java.util.Date(now.time + (it * 1000).toLong()) }
        "at" -> runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = zone; isLenient = false }
                .parse("${spec?.get("date")?.stringValue} ${spec?.get("time")?.stringValue?.let { if (it.length == 5) "$it:00" else it }}")
        }.getOrNull()
        "daily", "weekly" -> {
            val clock = spec?.get("time")?.stringValue.orEmpty().split(":")
            val hour = clock.getOrNull(0)?.toIntOrNull()
            val minute = clock.getOrNull(1)?.toIntOrNull()
            if (hour == null || minute == null || hour !in 0..23 || minute !in 0..59) null else {
                val calendar = java.util.Calendar.getInstance(zone).apply {
                    set(java.util.Calendar.HOUR_OF_DAY, hour)
                    set(java.util.Calendar.MINUTE, minute)
                    set(java.util.Calendar.SECOND, clock.getOrNull(2)?.toIntOrNull() ?: 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }
                val days = spec?.get("weekdays")?.arrayValue.orEmpty().mapNotNull { it.doubleValue?.toInt() }
                (0..7).firstNotNullOfOrNull {
                    val weekday = (calendar.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7 + 1
                    val date = calendar.time.takeIf { it.after(now) && (kind == "daily" || weekday in days) }
                    calendar.add(java.util.Calendar.DAY_OF_YEAR, 1)
                    date
                }
            }
        }
        else -> null
    }
    if (next == null) return "下次执行时间由 Host 校验并计算"
    val display = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault()).apply { timeZone = zone }
    return "预计下次执行 · ${display.format(next)}\n最终以 Host 返回时间为准"
}
