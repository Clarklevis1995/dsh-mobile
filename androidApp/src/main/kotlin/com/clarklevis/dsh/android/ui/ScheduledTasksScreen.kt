package com.clarklevis.dsh.android.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.shadow.Shadow
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

@Composable
internal fun ScheduledTasksScreen(
    stateHolder: AndroidSharedStateHolder,
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit
) {
    val dark = isSystemInDarkTheme()
    var editingTask by remember { mutableStateOf<MobileScheduledTask?>(null) }
    var deletingTask by remember { mutableStateOf<MobileScheduledTask?>(null) }
    var revealedTaskId by remember { mutableStateOf<String?>(null) }
    var showsMutationError by remember { mutableStateOf(false) }
    val connection = stateHolder.gatewayState.connection
    LaunchedEffect(connection) {
        if (connection == GatewayConnectionState.CONNECTED) stateHolder.refreshScheduledTasks()
        else if (stateHolder.scheduledTasks.isEmpty()) stateHolder.refreshScheduledTasks()
    }
    Column(
        Modifier.fillMaxSize()
            .background(if (dark) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surface)
            .statusBarsPadding().navigationBarsPadding()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TopBarCircleButton(R.drawable.ic_back_chevron, "返回", onBack)
            Spacer(Modifier.weight(1f))
            Text("定时任务", fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.size(46.dp))
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
                            Text("重试")
                        }
                    }
                }
            }
            stateHolder.scheduledTasks.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无定时任务", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else -> LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 20.dp, end = 20.dp, top = 18.dp, bottom = 28.dp
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(stateHolder.scheduledTasks, key = { it.id }) { task ->
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
                                onOpenSession(task.sessionId)
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
                }
            }
        }
    }
    editingTask?.let { task ->
        ScheduledTaskEditSheet(task, stateHolder, onDismiss = { editingTask = null })
    }
    deletingTask?.let { task ->
        AlertDialog(
            onDismissRequest = { deletingTask = null },
            title = { Text("删除定时任务？") },
            text = { Text("删除后任务及投递记录无法恢复；已进入会话队列的消息不会撤回。") },
            confirmButton = {
                TextButton(onClick = {
                    stateHolder.deleteScheduledTask(task)
                    deletingTask = null
                }) { Text("删除任务及投递记录", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deletingTask = null }) { Text("取消") } }
        )
    }
    LaunchedEffect(stateHolder.scheduledTaskMutationError) {
        if (stateHolder.scheduledTaskMutationError != null && editingTask == null) showsMutationError = true
    }
    if (showsMutationError) {
        AlertDialog(
            onDismissRequest = { showsMutationError = false },
            title = { Text("定时任务操作失败") },
            text = { Text(stateHolder.scheduledTaskMutationError ?: "请稍后重试") },
            confirmButton = { TextButton(onClick = { showsMutationError = false }) { Text("好") } }
        )
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
                        Icon(painterResource(R.drawable.ic_pencil_line), contentDescription = "编辑${task.title}",
                            modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(
                        onClick = onDelete,
                        enabled = progress > 0.95f && !busy,
                        modifier = Modifier.size(50.dp).background(Color(0xFFD65052), CircleShape)
                    ) {
                        Icon(painterResource(R.drawable.ic_trash), contentDescription = "删除${task.title}",
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
                    if (task.status == "active") formatScheduleDate(task.scheduledAt) else "已结束",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp,
                    modifier = Modifier.weight(1f)
                )
                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_up),
                        contentDescription = if (expanded) "收起任务详情" else "展开任务详情",
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
                    ScheduleDetail("执行规则", fullRule(task))
                    ScheduleDetail("下次计划时间", if (task.status == "active") formatScheduleDate(task.scheduledAt) else "无")
                    ScheduleDetail("所属会话", sessionTitle)
                    ScheduleDetail("状态", if (task.status == "active") "运行中" else "已结束")
                    ScheduleDetail("最近投递", task.lastDelivery?.get("deliveredAt")?.stringValue?.let { "已投递 · ${formatScheduleDate(it)}" } ?: "暂无投递记录")
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
    "daily" -> "每天"
    "weekly" -> "每周"
    "every" -> "每 ${duration(task.raw["everySeconds"]?.doubleValue?.toInt() ?: 0)}"
    "cron" -> "Cron"
    else -> "一次"
}

private fun fullRule(task: MobileScheduledTask): String = when (task.kind) {
    "daily" -> "每天 ${task.raw["time"]?.stringValue.orEmpty()} · ${task.raw["timeZone"]?.stringValue.orEmpty()}"
    "weekly" -> {
        val days = task.raw["weekdays"]?.arrayValue.orEmpty().mapNotNull { it.doubleValue?.toInt() }
        val dayNames = listOf("一", "二", "三", "四", "五", "六", "日")
        "每周${days.mapNotNull { dayNames.getOrNull(it - 1) }.joinToString("、周")} · ${task.raw["time"]?.stringValue.orEmpty()} · ${task.raw["timeZone"]?.stringValue.orEmpty()}"
    }
    "every" -> "每 ${duration(task.raw["everySeconds"]?.doubleValue?.toInt() ?: 0)}执行一次"
    "cron" -> "${task.raw["expression"]?.stringValue.orEmpty()} · ${task.raw["timeZone"]?.stringValue.orEmpty()}"
    "after" -> "${task.raw["afterSeconds"]?.doubleValue?.toInt() ?: 0} 秒后执行一次"
    else -> "指定时间执行一次"
}

private fun duration(seconds: Int): String = when {
    seconds > 0 && seconds % 86_400 == 0 -> "${seconds / 86_400} 天"
    seconds > 0 && seconds % 3_600 == 0 -> "${seconds / 3_600} 小时"
    seconds > 0 && seconds % 60 == 0 -> "${seconds / 60} 分钟"
    else -> "$seconds 秒"
}

private fun formatScheduleDate(value: String): String {
    val input = listOf("yyyy-MM-dd'T'HH:mm:ss.SSSX", "yyyy-MM-dd'T'HH:mm:ssX")
    val date = input.firstNotNullOfOrNull { pattern ->
        runCatching { SimpleDateFormat(pattern, Locale.US).parse(value) }.getOrNull()
    } ?: return value
    return SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.CHINA).format(date)
}
