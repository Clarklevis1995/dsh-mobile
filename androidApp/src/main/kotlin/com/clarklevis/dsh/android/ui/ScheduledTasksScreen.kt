package com.clarklevis.dsh.android.ui

import android.os.SystemClock
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
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
import com.clarklevis.dsh.android.localization.appTextKey
import com.clarklevis.dsh.android.AndroidSharedStateHolder
import com.clarklevis.dsh.android.MobileScheduledTask
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.shared.gateway.GatewayConnectionState
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val scheduleBlue = Color(0xFF0A84FF)

internal enum class ScheduledTaskStatusFilter(private val sourceTitle: String) {
    ALL(appTextKey("全部")), ACTIVE(appTextKey("已开启")), ENDED(appTextKey("已结束"));

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
            ScheduledTaskStatusFilter.ENDED -> task.status != "active"
        }
        matchesStatus && (search.isEmpty() || task.title.contains(search, ignoreCase = true) ||
            task.prompt.contains(search, ignoreCase = true))
    }
}

@Composable
internal fun ScheduledTasksScreen(
    stateHolder: AndroidSharedStateHolder,
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit
) {
    val dark = isSystemInDarkTheme()
    var historyTask by remember { mutableStateOf<MobileScheduledTask?>(null) }
    var creating by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var editingTask by remember { mutableStateOf<MobileScheduledTask?>(null) }
    var deletingTask by remember { mutableStateOf<MobileScheduledTask?>(null) }
    var revealedTaskId by remember { mutableStateOf<String?>(null) }
    var showsMutationError by remember { mutableStateOf(false) }
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
            TopBarCircleButton(R.drawable.ic_back_chevron, appText("返回"), onBack,
                modifier = Modifier.align(Alignment.CenterStart).testTag("scheduled-back"))
            if (!isSearching) {
                Text(appText("定时任务"), fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 104.dp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            ScheduledTaskSearchAndFilter(
                maximumWidth = (maxWidth - 58.dp).coerceAtLeast(92.dp),
                query = searchQuery,
                onQueryChange = { searchQuery = it; revealedTaskId = null },
                isSearching = isSearching,
                onSearchingChange = { isSearching = it },
                status = statusFilter,
                onStatusChange = {
                    statusFilter = it
                    revealedTaskId = null
                    stateHolder.filterScheduledTasks(when (it) {
                        ScheduledTaskStatusFilter.ALL -> null
                        ScheduledTaskStatusFilter.ACTIVE -> true
                        ScheduledTaskStatusFilter.ENDED -> false
                    })
                },
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
        if ("schedule-management-v2" in stateHolder.gatewayState.capabilities) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                TextButton(onClick = { creating = true }) { Text(appText("创建任务")) }
                TextButton(onClick = { confirmClear = true }) { Text(appText("清理已结束任务")) }
            }
            stateHolder.scheduledTaskMutationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        if (stateHolder.scheduledTasks.size < stateHolder.scheduledTasksTotal) {
            TextButton(onClick = stateHolder::loadMoreScheduledTasks, enabled = !stateHolder.scheduledTasksLoading) { Text(appText("加载更多任务")) }
        }
        when {
            stateHolder.scheduledTasksLoading && stateHolder.scheduledTasks.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = scheduleBlue)
                }
            }
            stateHolder.scheduledTasksError != null && stateHolder.scheduledTasks.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stateHolder.scheduledTasksError.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = stateHolder::refreshScheduledTasks, modifier = Modifier.padding(top = 12.dp)) {
                            Text(appText("重试"))
                        }
                    }
                }
            }
            stateHolder.scheduledTasks.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(appText("暂无定时任务"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            visibleTasks.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(appText("没有符合条件的定时任务"), fontWeight = FontWeight.SemiBold)
                        Text(if (searchQuery.isBlank()) appText("试试其他任务状态") else appText("试试其他关键词或任务状态"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            else -> LazyColumn(
                modifier = Modifier.adaptiveContentWidth().fillMaxSize().testTag("scheduled-tasks-content"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 20.dp, end = 20.dp, top = 18.dp, bottom = 28.dp
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
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
                        busy = stateHolder.scheduledTaskPendingId == task.id
                    )
                    if ("schedule-management-v2" in stateHolder.gatewayState.capabilities) {
                        Row {
                            if (task.status != "ended") TextButton(onClick = {
                                stateHolder.setScheduledTaskActive(task, task.status != "active")
                            }, enabled = stateHolder.scheduledTaskPendingId == null) { Text(appText(if (task.status == "active") "暂停" else "恢复")) }
                            TextButton(onClick = { historyTask = task; stateHolder.loadScheduleHistory(task) }) { Text(appText("执行记录")) }
                            TextButton(onClick = { stateHolder.runScheduledTask(task) },
                                enabled = stateHolder.scheduledTaskPendingId == null) { Text(appText("立即执行")) }
                        }
                    }
                    }
                }
            }
        }
    }
    historyTask?.let { task ->
        AlertDialog(onDismissRequest = { historyTask = null }, title = { Text(appText("执行记录 · %1\$s", task.title)) },
            text = { Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text(stateHolder.scheduleHistory?.get("records")?.toJsonElement()?.toString() ?: appText("正在加载…"))
                stateHolder.scheduleHistory?.get("nextBefore")?.stringValue?.let { before ->
                    TextButton(onClick = { stateHolder.loadScheduleHistory(task, before) }) { Text(appText("更早记录")) }
                }
            } }, confirmButton = { TextButton(onClick = { historyTask = null }) { Text(appText("关闭")) } })
    }
    if (creating) ScheduleCreateDialog(stateHolder) { creating = false }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text(appText("清理已结束任务？")) }, text = { Text(appText("清理后无法恢复。")) },
        confirmButton = { TextButton(onClick = { confirmClear = false; stateHolder.clearEndedScheduledTasks() }) { Text(appText("清理")) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(appText("取消")) } })
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
private fun ScheduledTaskSearchAndFilter(
    maximumWidth: Dp,
    query: String,
    onQueryChange: (String) -> Unit,
    isSearching: Boolean,
    onSearchingChange: (Boolean) -> Unit,
    status: ScheduledTaskStatusFilter,
    onStatusChange: (ScheduledTaskStatusFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    val dark = isSystemInDarkTheme()
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var filterExpanded by remember { mutableStateOf(false) }
    val width by animateDpAsState(if (isSearching) minOf(238.dp, maximumWidth) else 92.dp, label = "schedule-search-width")
    LaunchedEffect(isSearching) {
        if (isSearching) focusRequester.requestFocus()
    }

    Row(
        modifier.width(width).height(46.dp).testTag("scheduled-search-controls")
            .dropShadow(RoundedCornerShape(23.dp), Shadow(
                radius = 12.dp,
                color = Color.Black.copy(alpha = if (dark) 0.24f else 0.075f),
                offset = DpOffset(0.dp, 4.dp)
            ))
            .clip(RoundedCornerShape(23.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = if (dark) 0.82f else 0.94f))
            .border(0.7.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = if (dark) 0.13f else 0.055f),
                RoundedCornerShape(23.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSearching) {
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
        } else {
            Box(
                Modifier.size(46.dp).clickable { onSearchingChange(true) }
                    .semantics { contentDescription = appText("搜索定时任务") },
                contentAlignment = Alignment.Center
            ) {
                Icon(painterResource(R.drawable.ic_search), null, Modifier.size(20.dp))
            }
            Box {
                Box(
                    Modifier.size(46.dp).clickable { filterExpanded = true }
                        .semantics { contentDescription = appText("筛选定时任务，当前%1\$s", status.title) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(painterResource(R.drawable.ic_filter_list), null, Modifier.size(21.dp))
                }
                DshMenu(
                    expanded = filterExpanded,
                    onDismissRequest = { filterExpanded = false },
                    modifier = Modifier.width(170.dp)) {
                    ScheduledTaskStatusFilter.entries.forEach { option ->
                        DshMenuItem(
                            text = { Text(option.title) },
                            onClick = {
                                onStatusChange(option)
                                filterExpanded = false
                            },
                            trailingIcon = {
                                if (status == option) Icon(painterResource(R.drawable.ic_menu_check), null,
                                    Modifier.size(18.dp))
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduledTaskCard(
    task: MobileScheduledTask,
    sessionTitle: String,
    revealedTaskId: String?,
    onRevealChange: (String?) -> Unit,
    onOpenSession: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    busy: Boolean
) {
    val dark = isSystemInDarkTheme()
    var expanded by rememberSaveable(task.id) { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 0f else 180f,
        animationSpec = tween(220),
        label = "schedule-chevron"
    )
    val density = LocalDensity.current
    val revealWidthPx = with(density) { 128.dp.toPx() }
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
        } else if (!shouldOpen && offsetPx < 0f) {
            settle(open = false)
        }
    }

    Box(Modifier.fillMaxWidth()) {
        Box(Modifier.matchParentSize()) {
            Box(
                Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                    .requiredWidth(with(density) { (-offsetPx).toDp() })
                    .clipToBounds()
            ) {
                Row(
                    Modifier.align(Alignment.CenterEnd).requiredWidth(119.dp)
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
                    IconButton(
                        onClick = onEdit,
                        enabled = progress > 0.95f && task.status == "active" && !busy,
                        modifier = Modifier.size(50.dp)
                            .clip(CircleShape)
                            .background(
                                MaterialTheme.colorScheme.surface.copy(alpha = if (dark) 0.82f else 0.94f),
                                CircleShape
                            )
                            .border(
                                0.8.dp,
                                MaterialTheme.colorScheme.onSurface.copy(alpha = if (dark) 0.18f else 0.11f),
                                CircleShape
                            )
                    ) {
                        Icon(painterResource(R.drawable.ic_pencil_line), contentDescription = appText("编辑%1\$s", task.title),
                            modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(
                        onClick = onDelete,
                        enabled = progress > 0.95f && !busy,
                        modifier = Modifier.size(50.dp).background(Color(0xFFD65052), CircleShape)
                    ) {
                        Icon(painterResource(R.drawable.ic_trash), contentDescription = appText("删除%1\$s", task.title),
                            modifier = Modifier.size(20.dp), tint = Color.White)
                    }
                }
            }
        }

        Column(
            Modifier.fillMaxWidth()
                .offset { IntOffset(offsetPx.roundToInt(), 0) }
                .dropShadow(
                    shape = RoundedCornerShape(28.dp),
                    shadow = Shadow(
                        radius = 14.dp,
                        color = Color.Black.copy(alpha = 0.08f),
                        offset = DpOffset(0.dp, 4.dp)
                    )
                )
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(28.dp))
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
                            else -> offsetPx <= -revealWidthPx * (if (startedOpen) 0.75f else 0.25f)
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
                    task.prompt, fontSize = 16.sp, lineHeight = 22.sp,
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
                }.padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (task.status == "active") formatScheduleDate(task.scheduledAt) else appText("已结束"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp,
                    modifier = Modifier.weight(1f)
                )
                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
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
                    ScheduleDetail(appText("状态"), if (task.status == "active") appText("运行中") else appText("已结束"))
                    ScheduleDetail(appText("最近投递"), task.lastDelivery?.get("deliveredAt")?.stringValue?.let { appText("已投递 · %1\$s", formatScheduleDate(it)) } ?: appText("暂无投递记录"))
                }
            }
        }
    }
}

@Composable
private fun ScheduleDetail(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(width = 92.dp, height = 22.dp))
        Text(value, fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f))
    }
}

private fun shortRule(task: MobileScheduledTask): String = when (task.kind) {
    "daily" -> appText("每天")
    "weekly" -> appText("每周")
    "every" -> appText("每 %1\$s", duration(task.everySeconds?.toInt() ?: 0))
    "cron" -> "Cron"
    else -> appText("一次")
}

private fun fullRule(task: MobileScheduledTask): String = when (task.kind) {
    "daily" -> appText("每天 %1\$s · %2\$s", task.time.orEmpty(), task.timeZone.orEmpty())
    "weekly" -> {
        val days = task.weekdays?.arrayValue.orEmpty().mapNotNull { it.doubleValue?.toInt() }
        val dayNames = listOf(appText("一"), appText("二"), appText("三"), appText("四"), appText("五"), appText("六"), appText("日"))
        appText("每周%1\$s · %2\$s · %3\$s", days.mapNotNull { dayNames.getOrNull(it - 1) }.joinToString(appText("、周")), task.time.orEmpty(), task.timeZone.orEmpty())
    }
    "every" -> appText("每 %1\$s执行一次", duration(task.everySeconds?.toInt() ?: 0))
    "cron" -> "${task.expression.orEmpty()} · ${task.timeZone.orEmpty()}"
    "after" -> appText("%1\$s 秒后执行一次", task.raw["afterSeconds"]?.doubleValue?.toInt() ?: 0)
    "at" -> appText("指定时间执行一次")
    "rrule" -> task.raw["rrule"]?.stringValue.orEmpty()
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
    AlertDialog(onDismissRequest = onDismiss, title = { Text(appText("创建定时任务")) },
        text = {
            DshMenuHost {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.compose.material3.OutlinedTextField(title, { title = it }, label = { Text(appText("标题")) })
                    androidx.compose.material3.OutlinedTextField(prompt, { prompt = it }, label = { Text(appText("任务内容")) })
                    Text(appText("执行规则使用 Host JSON，支持 after、at、every、daily、weekly、cron、rrule。时区使用 IANA 名称。"))
                    androidx.compose.material3.OutlinedTextField(timing, { timing = it }, label = { Text(appText("执行规则 JSON")) })
                    listOf("new_once" to appTextKey("首次新建并复用会话"), "new" to appTextKey("每次新建会话"), "session" to appTextKey("已有会话")).forEach { (kind, label) ->
                        TextButton(onClick = { target = kind }) { Text((if (target == kind) "✓ " else "") + appText(label)) }
                    }
                    if (target == "session") androidx.compose.material3.OutlinedTextField(sessionId, { sessionId = it }, label = { Text(appText("会话 ID")) })
                    val preview = remember(timing) {
                        runCatching { kotlinx.serialization.json.Json.parseToJsonElement(timing).toString() }.getOrNull()
                    }
                    Text(appText("规则预览 · %1\$s", preview ?: appText("执行规则 JSON 无效")))
                    Text(scheduleNextExecutionPreview(timing))
                    Text(appText("当前时区 · %1\$s", java.util.TimeZone.getDefault().id))
                    stateHolder.scheduledTaskMutationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { requestId = stateHolder.createScheduledTask(title, prompt, timing, target, sessionId) },
            enabled = stateHolder.scheduledTaskPendingId == null) { Text(appText("创建")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(appText("取消")) } })
}

private fun scheduleNextExecutionPreview(json: String): String {
    val rule = runCatching { com.clarklevis.dsh.shared.protocol.JsonValue.fromJsonElement(
        kotlinx.serialization.json.Json.parseToJsonElement(json)) }.getOrNull() ?: return appText("请填写有效规则")
    val kind = rule["kind"]?.stringValue ?: return appText("请填写规则类型")
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
    if (next == null) return appText("下次执行时间由 Host 校验并计算")
    val display = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault()).apply { timeZone = zone }
    return appText("预计下次执行 · %1\$s\n最终以 Host 返回时间为准", display.format(next))
}
