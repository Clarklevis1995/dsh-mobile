package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.testTag
import com.clarklevis.dsh.shared.facade.SharedWorkspaceChangesSnapshot
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clarklevis.dsh.android.AndroidSharedStateHolder

private val changeGreen = Color(0xFF28843B)
private val changeRed = Color(0xFFC44B51)

internal fun changesError(code: String): String = when (code) {
    "workspace-changes-missing" -> "该次改动记录已不可用，Host 重启后可能无法查看历史改动。"
    "disconnected" -> "连接已断开，请重连后重试。"
    "workspace-changes-unavailable" -> "当前网关暂不支持文件改动查询。"
    "workspace-changes-response-too-large" -> "改动内容超过响应大小限制，无法完整展示。"
    "unauthorized", "forbidden", "auth-required" -> "没有权限读取该次改动，请检查连接认证。"
    else -> "无法读取改动内容，请重试。"
}

@Composable
internal fun WorkspaceChangesDock(holder: AndroidSharedStateHolder) {
    val session = holder.snapshot.selectedSessionId
    val state = holder.workspaceChanges
    var expanded by remember(session) { mutableStateOf(false) }
    LaunchedEffect(session, holder.gatewayState.connection, holder.gatewayState.capabilities) {
        holder.refreshWorkspaceChanges()
    }
    DisposableEffect(session) { onDispose { holder.closeChangesDiff() } }
    if (state.sessionId == session && state.visible) {
        Surface(
            modifier = Modifier.padding(horizontal = 14.dp).clickable { expanded = true },
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .92f),
            border = androidx.compose.foundation.BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(state.summary?.let { "${it.total} 个文件" } ?: "文件改动", fontSize = 14.sp)
                state.summary?.let {
                    Text("+${it.added}", color = changeGreen, fontSize = 14.sp)
                    Text("−${it.deleted}", color = changeRed, fontSize = 14.sp)
                }
                if (state.loading) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
                else if (state.error != null) Text("查看", fontSize = 12.sp)
            }
        }
    }
    if (expanded && state.sessionId == session && state.visible) WorkspaceChangesSheet(state, holder::selectChangedFile, holder::retryWorkspaceChanges) {
        expanded = false
        holder.closeChangesDiff()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkspaceChangesSheet(
    state: SharedWorkspaceChangesSnapshot,
    selectFile: (Int) -> Unit,
    retrySummary: () -> Unit,
    dismiss: () -> Unit
) {
    var opened by remember(state.seq) { mutableStateOf(setOf<Int>()) }
    LaunchedEffect(state.seq, state.summary) {
        if (state.summary?.files?.isNotEmpty() == true && opened.isEmpty()) {
            opened = setOf(0)
            selectFile(0)
        }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()
    val isolateScroll = remember(listState) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) = Offset(0f, available.y)
            override suspend fun onPreFling(available: Velocity): Velocity =
                if ((available.y < 0 && !listState.canScrollForward) ||
                    (available.y > 0 && !listState.canScrollBackward)) Velocity(0f, available.y) else Velocity.Zero
            override suspend fun onPostFling(consumed: Velocity, available: Velocity) = Velocity(0f, available.y)
        }
    }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = sheetState,
        modifier = Modifier.adaptiveSheetBounds().testTag("workspace-changes-sheet"), sheetMaxWidth = adaptiveSheetWidth(),
        dragHandle = {
            Box(Modifier.fillMaxWidth().height(28.dp).testTag("workspace-changes-handle"), contentAlignment = Alignment.Center) {
                Box(Modifier.size(36.dp, 4.dp).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .35f), RoundedCornerShape(50)))
            }
        }) {
        DshMenuColumnHost {
            Column(Modifier.fillMaxWidth().height(adaptiveWindowSize().height * .82f).testTag("workspace-changes-content")) {
                Row(Modifier.fillMaxWidth().testTag("workspace-changes-header").padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("文件改动", style = MaterialTheme.typography.titleLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("本轮改动", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            state.summary?.let {
                                Text("+${it.added}", color = changeGreen)
                                Text("−${it.deleted}", color = changeRed)
                            }
                        }
                    }
                    TextButton(onClick = dismiss) { Text("完成") }
                }
                state.summary?.let { summary ->
                    Text("已列出 ${summary.files.size} / 总计 ${summary.total} 个文件",
                        Modifier.padding(horizontal = 20.dp, vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyColumn(Modifier.weight(1f).nestedScroll(isolateScroll).padding(horizontal = 16.dp).testTag("workspace-changes-list"), state = listState,
                        contentPadding = PaddingValues(bottom = 24.dp)) {
                        summary.files.forEachIndexed { index, file ->
                            val preview = state.previews.firstOrNull { it.index == index }
                            val isOpen = index in opened
                            item(key = "file-$index") {
                                Column(Modifier.padding(top = 12.dp).fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                                    .clickable {
                                        opened = if (isOpen) opened - index else opened + index
                                        if (!isOpen) selectFile(index)
                                    }.padding(16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(file.display.substringAfterLast('/'), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                        Text("+${file.added}", color = changeGreen)
                                        Text(" −${file.deleted}", color = changeRed)
                                    }
                                    if ('/' in file.display) Text(file.display.substringBeforeLast('/'),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(if (isOpen) "收起改动 ⌃" else "展开改动 ⌄", style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (isOpen) {
                                if (state.selectedIndex == index && state.diffLoading) item { CircularProgressIndicator(Modifier.padding(20.dp)) }
                                if (state.selectedIndex == index && state.diffError != null) item {
                                    ChangesFailure(requireNotNull(state.diffError)) { selectFile(index) }
                                }
                                if (preview != null) {
                                    val diff = preview.diff
                                    val message = when {
                                        diff.kind == "binary" -> "二进制文件已改动，无法逐行对比。"
                                        diff.kind == "oversized" -> "文件过大，无法逐行对比。"
                                        diff.coarse -> "比较超时，按整段替换展示。"
                                        diff.before == false && diff.after == true -> "新建文件"
                                        diff.before == true && diff.after == false -> "已删除文件"
                                        else -> null
                                    }
                                    message?.let { item { Text(it, Modifier.padding(16.dp)) } }
                                    itemsIndexed(preview.rows, key = { row, _ -> "line-$index-$row" }) { _, line ->
                                        val tint = when (line.kind) { "added" -> changeGreen; "deleted" -> changeRed; else -> MaterialTheme.colorScheme.onSurfaceVariant }
                                        val horizontal = rememberScrollState()
                                        Row(Modifier.fillMaxWidth().background(tint.copy(alpha = if (line.kind in listOf("added", "deleted")) .14f else .025f))
                                            .horizontalScroll(horizontal).padding(vertical = 3.dp)) {
                                            Text(line.oldLine?.toString() ?: "", Modifier.width(42.dp).padding(start = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                            Text(line.newLine?.toString() ?: "", Modifier.width(42.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                            Text(line.text, Modifier.padding(end = 16.dp), color = tint, fontFamily = FontFamily.Monospace, fontSize = 12.sp, softWrap = false)
                                        }
                                    }
                                } else if (state.selectedIndex != index) item {
                                    TextButton(onClick = { selectFile(index) }) { Text("加载改动内容") }
                                }
                            }
                        }
                    }
                }
                if (state.loading) CircularProgressIndicator(Modifier.padding(24.dp))
                state.error?.let { ChangesFailure(it, retrySummary) }
            }
        }
    }
}

@Composable
private fun ChangesFailure(code: String, retry: () -> Unit) {
    Column(Modifier.padding(20.dp)) {
        Text(changesError(code))
        TextButton(onClick = retry) { Text("重试") }
    }
}
