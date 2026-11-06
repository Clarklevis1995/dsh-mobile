package com.clarklevis.dsh.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clarklevis.dsh.android.AndroidSharedStateHolder
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.shared.facade.TeamMemberView
import com.clarklevis.dsh.shared.facade.TeamTaskView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TeamEntryButton(holder: AndroidSharedStateHolder) {
    var menu by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(false) }
    val selected = holder.snapshot.selectedSessionId
    LaunchedEffect(selected) { menu = false }
    Box {
        TopBarCircleButton(
            description = appText("查看智能体团队"),
            onClick = { menu = !menu },
            modifier = Modifier.testTag("team-entry").semantics { role = Role.Button },
        ) {
            val color = MaterialTheme.colorScheme.onSurface
            Canvas(Modifier.size(18.dp)) {
                scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
                    drawCircle(
                        color,
                        4f,
                        Offset(9f, 7f),
                        style = Stroke(1.5f),
                    )
                    val p =
                        Path().apply {
                            moveTo(2f, 22f)
                            cubicTo(2f, 12f, 16f, 12f, 16f, 22f)
                            moveTo(17f, 3f)
                            cubicTo(22f, 3f, 22f, 11f, 17f, 11f)
                            moveTo(20f, 15f)
                            quadraticTo(23f, 16f, 23f, 22f)
                        }
                    drawPath(p, color, style = Stroke(1.5f))
                }
            }
        }
        DshMenu(
            expanded = menu,
            onDismissRequest = { menu = false },
            modifier = Modifier.width(300.dp).testTag("team-menu"),
        ) {
            Text(
                holder.teamSessionTitle,
                Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 6.dp),
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                appText("成员 %1\$s", holder.teamSnapshot.members.size),
                Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
                fontSize = 17.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Column(Modifier.heightIn(max = 300.dp).clipToBounds().verticalScroll(rememberScrollState())) {
                holder.teamSnapshot.members.forEach { member ->
                    TeamMemberItem(member, member.id == selected) {
                        menu = false
                        holder.selectTeamMember(member)
                    }
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clickable {
                        menu = false
                        sheet = true
                    }.padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(appText("查看完整团队内容"), Modifier.weight(1f), fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
                Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
    }
    if (sheet) {
        val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
        ModalBottomSheet(
            modifier = Modifier.adaptiveSheetBounds(),
            sheetMaxWidth = adaptiveSheetWidth(),
            onDismissRequest = { sheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal) },
            properties = ModalBottomSheetProperties(isAppearanceLightStatusBars = !dark, isAppearanceLightNavigationBars = !dark),
            dragHandle = {
                Box(
                    Modifier
                        .padding(top = 8.dp, bottom = 4.dp)
                        .size(36.dp, 5.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f), CircleShape),
                )
            },
        ) {
            DshMenuColumnHost {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.94f)
                        .navigationBarsPadding()
                        .testTag("team-sheet"),
                ) {
                    Box(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                        Text(
                            if (holder.teamSnapshot.isTeam) appText("智能体团队") else appText("子智能体"),
                            Modifier.padding(horizontal = 70.dp),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Surface(
                            onClick = { sheet = false },
                            modifier = Modifier.align(Alignment.CenterEnd).testTag("team-sheet-done"),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface,
                            shadowElevation = 2.dp,
                        ) {
                            Text(appText("完成"), Modifier.padding(horizontal = 16.dp, vertical = 10.dp), fontSize = 16.sp)
                        }
                    }
                    TeamSheetContent(holder, Modifier.weight(1f)) { member ->
                        sheet = false
                        holder.selectTeamMember(member)
                    }
                }
            }
        }
    }
}

@Composable
internal fun TeamMemberItem(
    member: TeamMemberView,
    selected: Boolean,
    horizontalInset: Dp = 12.dp,
    select: () -> Unit,
) {
    val rowHeight = 72.dp * maxOf(1f, LocalDensity.current.fontScale)
    val blue = Color(0xFF007AFF)
    val secondary = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    val status =
        when {
            member.phase == "provisioning" -> appText("正在创建")
            member.phase == "failed" -> appText("创建失败")
            member.mode == "external" -> appText("外部智能体")
            member.mode == "one-shot" -> appText("只读 · 单次执行")
            member.mode == "unknown" -> appText("只读 · 身份待确认")
            member.running == true -> appText("运行中")
            member.running == false -> appText("未运行")
            else -> appText("状态同步中")
        }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalInset, vertical = 1.dp)
            .height(rowHeight)
            .background(if (selected) blue.copy(alpha = 0.08f) else Color.Transparent, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .selectable(selected = selected, enabled = member.canOpen, role = Role.Button, onClick = select)
            .padding(horizontal = 10.dp, vertical = 2.dp)
            .testTag("team-member-${member.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AgentAvatar(member.avatarIndex, Modifier.size(32.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    member.name,
                    Modifier.weight(1f, fill = false),
                    fontSize = 17.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (selected) Text(appText("当前会话"), fontSize = 10.sp, lineHeight = 14.sp, color = blue, maxLines = 1)
            }
            Text(
                listOfNotNull(member.model, status).joinToString(" · "),
                fontSize = 13.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Normal,
                color = secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                member.error ?: member.detail ?: " ",
                fontSize = 13.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Normal,
                color = secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 对勾保留固定列宽，切换成员不改变标题、模型和描述的可用宽度。
        Canvas(Modifier.size(18.dp).testTag(if (selected) "team-member-selected-${member.id}" else "team-member-unselected-${member.id}")) {
            if (selected) {
                drawPath(
                    Path().apply {
                        moveTo(size.width * 0.15f, size.height * 0.52f)
                        lineTo(size.width * 0.42f, size.height * 0.79f)
                        lineTo(size.width * 0.9f, size.height * 0.14f)
                    },
                    blue,
                    style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
    }
}

@Composable
private fun TeamSheetContent(
    holder: AndroidSharedStateHolder,
    modifier: Modifier,
    select: (TeamMemberView) -> Unit,
) {
    val team = holder.teamSnapshot
    var filter by remember { mutableIntStateOf(0) }
    val current = team.members.firstOrNull { it.id == holder.snapshot.selectedSessionId }
    val tasks =
        team.tasks.filter {
            filter == 0 ||
                if (filter == 1) current != null && it.ownerName == current.name else it.ownerName == null
        }
    LazyColumn(
        modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(holder.teamSessionTitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        }
        team.error?.let { error -> item { Text(appText(error), color = MaterialTheme.colorScheme.error) } }
        if (team.state != "ready") {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (team.state ==
                            "migration-required"
                        ) {
                            appText("团队数据待刷新，请在桌面端确认会话格式")
                        } else {
                            appText("团队数据暂不可用")
                        },
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = holder::refreshTeam) { Text(appText("重试")) }
                }
            }
        }
        item {
            Column {
                Text(appText("成员 %1\$s", team.members.size), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                team.members.forEach { member ->
                    TeamMemberItem(member, member.id == holder.snapshot.selectedSessionId, horizontalInset = 0.dp) { select(member) }
                }
            }
        }
        item {
            Text(
                if (team.state == "ready" ||
                    team.tasks.isNotEmpty()
                ) {
                    appText("共享任务 %1\$s", team.tasks.size)
                } else {
                    appText("共享任务 · 待同步")
                },
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        item { TeamTaskFilter(filter) { filter = it } }
        if (tasks.isEmpty()) {
            item {
                Text(
                    when {
                        team.state != "ready" -> appText("共享任务尚未同步，请重试")
                        team.tasks.isNotEmpty() -> appText("当前筛选下暂无共享任务")
                        team.isTeam -> appText("当前团队尚未创建共享任务")
                        else -> appText("此会话尚无团队共享任务；普通子智能体的内部待办显示在各自会话中。")
                    },
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
        }
        items(tasks, key = { "task-${it.id}" }) { task ->
            TeamTaskCard(
                task,
                team.members.firstOrNull { it.name == task.ownerName },
                task.blockedBy.map { id -> team.tasks.firstOrNull { it.id == id }?.subject ?: id },
                select,
            )
        }
    }
}

@Composable
internal fun TeamTaskFilter(
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Row(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f),
                CircleShape,
            ).padding(2.dp),
    ) {
        listOf(appText("全部"), appText("当前成员"), appText("未分配")).forEachIndexed { index, label ->
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(if (selected == index) (if (dark) Color(0xFF55555A) else Color.White) else Color.Transparent)
                    .selectable(selected == index, role = Role.Tab, onClick = { onSelect(index) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    fontSize = 13.sp,
                    fontWeight = if (selected == index) FontWeight.Medium else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
internal fun TeamTaskCard(
    task: TeamTaskView,
    owner: TeamMemberView?,
    dependencies: List<String>,
    select: (TeamMemberView) -> Unit,
) {
    var expanded by remember(task.id) { mutableStateOf(false) }
    val disclosureRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "team-task-disclosure",
    )
    val secondary = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    val status =
        when (task.status) {
            "completed" -> appText("已完成")
            "in_progress" -> appText("进行中")
            "pending" -> if (task.ready) appText("待处理") else appText("受阻")
            else -> task.status
        }
    val color =
        when (task.status) {
            "completed" -> Color(0xFF22C55E)
            "in_progress" -> Color(0xFF4285F4)
            "pending" -> if (task.ready) Color.Gray else Color(0xFFEBA53B)
            else -> Color.Gray
        }
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp))
            .border(0.6.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
            .clickable(
                role = Role.Button,
                onClickLabel = if (expanded) appText("收起任务详情") else appText("展开任务详情")
            ) { expanded = !expanded }
            .padding(16.dp)
            .testTag("team-task-${task.id}"),
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Text(task.subject, Modifier.weight(1f), fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).background(color, CircleShape))
                        Text(status, fontSize = 12.sp, lineHeight = 16.sp, color = secondary)
                    }
                }
                if (task.description.isNotEmpty()) {
                    Text(
                        task.description,
                        // 只动画正文高度，底部负责人和范围会随正文连续移动。
                        modifier =
                            Modifier.fillMaxWidth().animateContentSize(
                                animationSpec = tween(220, easing = FastOutSlowInEasing),
                                alignment = Alignment.TopStart,
                            ),
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 15.sp,
                        lineHeight = 20.sp,
                        color = secondary,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    task.id,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = secondary,
                )
                Row(
                    Modifier
                        .testTag("team-task-toggle-${task.id}")
                        .clickable(
                            role = Role.Button,
                            onClickLabel = if (expanded) appText("收起任务详情") else appText("展开任务详情")
                        ) { expanded = !expanded }
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (expanded) appText("收起") else appText("展开"), fontSize = 12.sp, lineHeight = 16.sp, color = secondary)
                    Icon(
                        painterResource(R.drawable.ic_question_chevron_down),
                        contentDescription = null,
                        modifier = Modifier.size(12.dp).rotate(disclosureRotation),
                        tint = secondary,
                    )
                }
            }
            Row(
                Modifier
                    .testTag("team-task-owner-${task.id}")
                    .clickable(enabled = owner?.canOpen == true, role = Role.Button) { owner?.let(select) },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                owner?.let { AgentAvatar(it.avatarIndex, Modifier.size(20.dp)) }
                Text(
                    appText("负责人：%1\$s", task.ownerName ?: appText("未分配")),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = if (owner?.canOpen == true) Color(0xFF007AFF) else secondary,
                )
            }
            if (task.writeScopes.isNotEmpty()) {
                Text(
                    appText("写入范围：%1\$s", task.writeScopes.joinToString(appText("、"))),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = secondary,
                )
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(220, easing = FastOutSlowInEasing), expandFrom = Alignment.Top) + fadeIn(tween(150)),
            exit = shrinkVertically(tween(220, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top) + fadeOut(tween(120)),
        ) {
            // 顶部间距也参与收起，避免最后一帧突然跳动。
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(appText("版本：%1\$s", task.revision), fontSize = 12.sp, lineHeight = 16.sp, color = secondary)
                if (dependencies.isNotEmpty()) {
                    Text(
                        appText("依赖：%1\$s", dependencies.joinToString(appText("、"))),
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = secondary,
                    )
                }
                if (task.status ==
                    "pending"
                ) {
                    Text(if (task.ready) appText("可领取") else appText("等待前置任务完成"), fontSize = 12.sp, lineHeight = 16.sp, color = secondary)
                }
                task.writeScopeWarnings.forEach { Text(it, fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
