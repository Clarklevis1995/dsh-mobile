package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.ui.semantics.Role
import com.clarklevis.dsh.shared.facade.WorkspaceChangesStyle
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clarklevis.dsh.android.AndroidSharedStateHolder
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.LineHeightStyle
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.shared.facade.FileIconCatalog
import com.clarklevis.dsh.shared.facade.WorkspaceReviewPresentation
import com.clarklevis.dsh.shared.facade.WorkspaceReviewRow
import com.clarklevis.dsh.shared.protocol.WorkspaceChangedFile
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.luminance

@OptIn(ExperimentalTextApi::class)
private val reviewFont = FontFamily(
    Font(R.font.workspace_noto, weight = FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.workspace_noto, weight = FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.workspace_noto, weight = FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600)))
)
private val reviewCodeFont = FontFamily(Font(R.font.workspace_mono))
private val reviewCjk = Regex("[\\u2E80-\\uFFFF]+")

private class ReviewColors(private val dark: Boolean) {
    private fun color(light: Long, night: Long) = Color(if (dark) night else light)
    val ink = color(WorkspaceChangesStyle.REVIEW_INK, 0xFFE9EDF3)
    val secondary = color(WorkspaceChangesStyle.REVIEW_SECONDARY, 0xFFA5AEBA)
    val muted = color(WorkspaceChangesStyle.REVIEW_MUTED, 0xFF8B95A3)
    val line = color(WorkspaceChangesStyle.REVIEW_LINE, 0xFF343A44)
    val subtle = color(WorkspaceChangesStyle.REVIEW_SUBTLE, 0xFF22262D)
    val blue = color(WorkspaceChangesStyle.REVIEW_BLUE, 0xFF8DB1FF)
    val blueSoft = color(WorkspaceChangesStyle.REVIEW_BLUE_SOFT, 0xFF243553)
    val added = color(WorkspaceChangesStyle.REVIEW_ADDED_INK, 0xFF8AD5A7)
    val deleted = color(WorkspaceChangesStyle.REVIEW_DELETED_INK, 0xFFF4A0AF)
    val addedBG = color(WorkspaceChangesStyle.REVIEW_ADDED_BG, 0xFF20372B)
    val deletedBG = color(WorkspaceChangesStyle.REVIEW_DELETED_BG, 0xFF40282F)
    val hunkBG = color(WorkspaceChangesStyle.REVIEW_HUNK_BG, 0xFF2A3039)
    val surface = if (dark) Color(0xFF1C1C1E) else Color.White
}

@Composable
private fun ReviewText(
    text: String, modifier: Modifier = Modifier, color: Color = LocalContentColor.current,
    size: Int = 12, line: Int = 18, weight: FontWeight = FontWeight.Normal,
    maxLines: Int = Int.MAX_VALUE, overflow: TextOverflow = TextOverflow.Ellipsis
) {
    Text(text, modifier, color = color, fontFamily = reviewFont, fontSize = size.sp, lineHeight = line.sp,
        fontWeight = weight, maxLines = maxLines, overflow = overflow,
        style = TextStyle(letterSpacing = 0.sp, fontFeatureSettings = "tnum",
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)))
}

/** 固定标题和工具栏，正文独立滚动；手机单文件，大屏目录与正文并列。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkspaceChangesSheet(
    state: SharedWorkspaceChangesSnapshot,
    selectFile: (Int) -> Unit,
    retrySummary: () -> Unit,
    dismiss: () -> Unit,
    workspaceName: String = "工作区",
    workspaceRoot: String? = null,
    openFile: ((String) -> Unit)? = null
) {
    val window = adaptiveWindowSize()
    val wide = window.width.value >= WorkspaceChangesStyle.REVIEW_BREAKPOINT
    val dark = isSystemInDarkTheme()
    val colors = remember(dark) { ReviewColors(dark) }
    val files = state.summary?.files.orEmpty()
    var selected by remember(state.sessionId, state.seq) { mutableIntStateOf(0) }
    var wraps by remember { mutableStateOf(true) }
    val file = files.getOrNull(selected)
    val filePath = file?.let { WorkspaceReviewPresentation.absolutePath(it.path, workspaceRoot) ?: it.display }
    val choose: (Int) -> Unit = { index ->
        if (index in files.indices) {
            if (index != selected || state.selectedIndex != index || state.diffError != null) {
                selected = index
                selectFile(index)
            }
        }
    }
    LaunchedEffect(state.sessionId, state.seq, files.size) {
        if (selected !in files.indices) selected = 0
        if (files.isNotEmpty() && state.selectedIndex == null) selectFile(selected)
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = dismiss, sheetState = sheetState, containerColor = colors.surface,
        contentColor = colors.ink, sheetMaxWidth = minOf(WorkspaceChangesStyle.REVIEW_MAX_WIDTH.dp, window.width),
        modifier = Modifier.adaptiveSheetBounds().testTag("workspace-changes-sheet"),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(Modifier.fillMaxWidth().height(20.dp).testTag("workspace-changes-handle"), contentAlignment = Alignment.Center) {
                Box(Modifier.size(36.dp, 4.dp).background(colors.line, RoundedCornerShape(2.dp)))
            }
        }
    ) {
        DshMenuColumnHost {
            Column(Modifier.fillMaxWidth().height(window.height * .90f).testTag("workspace-changes-content")) {
                Row(Modifier.fillMaxWidth().heightIn(min = if (wide) 60.dp else 64.dp).padding(start = if (wide) 24.dp else 16.dp, end = 8.dp)
                    .testTag("workspace-changes-header"), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        ReviewText("文件改动", size = 20, line = 28, weight = FontWeight.Medium)
                        ReviewText("$workspaceName · 本轮改动", color = colors.secondary, maxLines = 1)
                    }
                    if (wide) ReviewSummary(state, colors)
                    IconButton(onClick = dismiss, modifier = Modifier.size(44.dp)) {
                        Icon(painterResource(R.drawable.ic_workspace_review_close), "关闭文件改动", Modifier.size(20.dp), tint = colors.secondary)
                    }
                }
                if (!wide) Box(Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(horizontal = 16.dp)) { ReviewSummary(state, colors, Modifier.fillMaxWidth()) }
                if (wide) HorizontalDivider(color = colors.line, thickness = 1.dp)
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    if (wide) {
                        val side = if (window.width.value >= WorkspaceChangesStyle.REVIEW_WIDE_BREAKPOINT) 260.dp else 248.dp
                        val list = rememberLazyListState()
                        LazyColumn(Modifier.width(side).fillMaxHeight().background(colors.subtle).isolateReviewScroll(list).padding(horizontal = 12.dp),
                            state = list, verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(top = 16.dp, bottom = 20.dp)) {
                            item { ReviewText("本轮文件", color = colors.secondary, modifier = Modifier.padding(bottom = 4.dp)) }
                            itemsIndexed(files) { index, entry -> ReviewFileOption(entry, index, selected, colors, 28, { choose(index) }) }
                        }
                        VerticalDivider(color = colors.line, thickness = 1.dp)
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Row(Modifier.fillMaxWidth().height(if (wide) 64.dp else 56.dp).padding(horizontal = if (wide) 24.dp else 16.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (wide) {
                                Column(Modifier.weight(1f).clickable(enabled = file != null && openFile != null, role = Role.Button) {
                                    file?.let { openFile?.invoke(it.path) }
                                }.semantics { contentDescription = "查看完整文件"; stateDescription = filePath.orEmpty() }
                                    .testTag("workspace-open-file"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        ReviewText(file?.display?.substringAfterLast('/') ?: "文件改动", Modifier.weight(1f, fill = false),
                                            size = 14, line = 20, weight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                                        file?.let { ReviewText("+${it.added}  −${it.deleted}", color = colors.secondary, maxLines = 1) }
                                    }
                                    filePath?.let { ReviewText(it, color = colors.blue, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) }
                                }
                            } else {
                                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                    key(state.sessionId, state.seq) {
                                        ReviewNativeFileSelector(files, selected, colors, choose)
                                    }
                                }
                            }
                            Box(Modifier.size(44.dp).clickable(role = Role.Button) { wraps = !wraps }
                                .semantics { contentDescription = "自动换行"; stateDescription = if (wraps) "开启" else "关闭" }
                                .testTag("workspace-wrap-lines"), contentAlignment = Alignment.Center) {
                                Icon(painterResource(if (wraps) R.drawable.ic_workspace_review_wrap else R.drawable.ic_workspace_review_no_wrap),
                                    contentDescription = null, tint = if (wraps) colors.blue else colors.secondary,
                                    modifier = Modifier.size(24.dp).testTag(if (wraps) "workspace-wrap-on-icon" else "workspace-wrap-off-icon"))
                            }
                        }
                        if (!wide && file != null && filePath != null) {
                            Box(Modifier.fillMaxWidth().clickable(enabled = openFile != null, role = Role.Button) { openFile?.invoke(file.path) }
                                .semantics { contentDescription = "查看完整文件"; stateDescription = filePath }
                                .testTag("workspace-open-file").padding(horizontal = 16.dp).padding(bottom = 8.dp)
                                .heightIn(min = 32.dp), contentAlignment = Alignment.CenterStart) {
                                ReviewText(filePath, color = colors.blue, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                            }
                        }
                        HorizontalDivider(color = colors.line, thickness = 1.dp)
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            // 换行只更新布局，保留当前文件的列表与滚动状态。
                            key(state.sessionId, state.seq, selected) {
                                ReviewDiff(state, selected, wide, wraps, colors, retrySummary) { selectFile(selected) }
                            }
                        }
                        if (wide) Box(Modifier.fillMaxWidth().height(40.dp).background(colors.subtle).padding(horizontal = 24.dp), contentAlignment = Alignment.CenterStart) {
                            ReviewText("删除行显示旧行号，其余显示新行号", color = colors.secondary)
                        }
                    }
                }
                if (!wide) {
                    HorizontalDivider(color = colors.line, thickness = 1.dp)
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        ReviewNav("上一个文件", selected > 0, colors, Modifier.weight(1f).testTag("workspace-file-previous"), Alignment.CenterStart) { choose(selected - 1) }
                        Box(Modifier.weight(1f).testTag("workspace-file-page"), contentAlignment = Alignment.Center) {
                            ReviewText(if (files.isEmpty()) "0 / 0" else "${selected + 1} / ${files.size}", color = colors.secondary, size = 14, line = 20)
                        }
                        ReviewNav("下一个文件", selected + 1 < files.size, colors, Modifier.weight(1f).testTag("workspace-file-next"), Alignment.CenterEnd) { choose(selected + 1) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewNav(label: String, enabled: Boolean, colors: ReviewColors, modifier: Modifier,
    alignment: Alignment, action: () -> Unit) {
    Box(modifier.heightIn(min = 44.dp).clickable(enabled = enabled, role = Role.Button, onClick = action), contentAlignment = alignment) {
        ReviewText(label, color = if (enabled) colors.blue else colors.muted, size = 16, line = 24, maxLines = 1)
    }
}

/** 用户指定此处使用系统原生菜单；选中状态、返回和点击外部关闭均交给平台。 */
@Composable
private fun ReviewNativeFileSelector(files: List<WorkspaceChangedFile>, selected: Int, colors: ReviewColors,
    choose: (Int) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val menuContext = remember(context, colors.surface) {
        android.view.ContextThemeWrapper(context, if (colors.surface.luminance() < 0.5f)
            android.R.style.Theme_DeviceDefault else android.R.style.Theme_DeviceDefault_Light)
    }
    val anchor = remember(context) { android.view.View(context).apply {
        importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
    } }
    var popup by remember { mutableStateOf<android.widget.PopupMenu?>(null) }
    DisposableEffect(anchor) { onDispose { popup?.dismiss() } }
    Box(modifier) {
        AndroidView(factory = { anchor }, modifier = Modifier.matchParentSize())
        Row(Modifier.background(colors.subtle, RoundedCornerShape(8.dp)).heightIn(min = 40.dp)
            .clickable(enabled = files.isNotEmpty(), role = Role.Button) {
                popup?.dismiss()
                val menu = android.widget.PopupMenu(menuContext, anchor)
                files.forEachIndexed { index, entry ->
                    menu.menu.add(0, index, index, entry.display).apply {
                        icon = context.getDrawable(FileIconCatalog.resolve(entry.display, null).drawableResource())
                        isCheckable = true
                        isChecked = index == selected
                        if (android.os.Build.VERSION.SDK_INT >= 26) {
                            contentDescription = "${entry.display}，新增 ${entry.added} 行，删除 ${entry.deleted} 行"
                        }
                    }
                }
                menu.menu.setGroupCheckable(0, true, true)
                if (android.os.Build.VERSION.SDK_INT >= 29) menu.setForceShowIcon(true)
                menu.setOnMenuItemClickListener { item -> choose(item.itemId); true }
                menu.setOnDismissListener { popup = null }
                popup = menu
                menu.show()
            }.padding(start = 12.dp, end = 8.dp).testTag("workspace-file-selector"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ReviewText(files.getOrNull(selected)?.display?.substringAfterLast('/') ?: "选择文件", Modifier.weight(1f, fill = false),
                size = 14, line = 20, weight = FontWeight.SemiBold, maxLines = 1)
            Icon(painterResource(R.drawable.ic_workspace_review_chevron), null, Modifier.size(16.dp), tint = colors.secondary)
        }
    }
}

@Composable
private fun ReviewSummary(state: SharedWorkspaceChangesSnapshot, colors: ReviewColors, modifier: Modifier = Modifier) {
    Row(modifier.heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val total = state.summary?.total ?: 0
        val listed = state.summary?.files?.size ?: 0
        ReviewText(if (listed < total) "已列出 $listed / $total 个文件" else "$total 个文件", color = colors.secondary)
        if (modifier != Modifier) Spacer(Modifier.weight(1f))
        state.summary?.let {
            ReviewText("+${it.added}", color = colors.added, size = 14, line = 20, weight = FontWeight.SemiBold)
            ReviewText("−${it.deleted}", color = colors.deleted, size = 14, line = 20, weight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ReviewFileOption(file: WorkspaceChangedFile, index: Int, selected: Int, colors: ReviewColors, iconSize: Int,
    action: () -> Unit) {
    val modifier = Modifier.background(if (index == selected) colors.blueSoft else colors.surface, RoundedCornerShape(10.dp))
        .testTag("workspace-file-$index").semantics { this.selected = index == selected }
    val label: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ReviewText(file.display.substringAfterLast('/'), color = if (index == selected) colors.blue else colors.ink,
                size = 14, line = 20, weight = FontWeight.SemiBold, maxLines = 1)
            ReviewText("+${file.added}  −${file.deleted}", color = colors.secondary)
            if ('/' in file.display) ReviewText(file.display.substringBeforeLast('/'), color = colors.secondary, maxLines = 1)
        }
    }
    val icon: @Composable () -> Unit = { FileTypeIcon(FileIconCatalog.resolve(file.display, null), Modifier.size(iconSize.dp)) }
    Row(modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button, onClick = action).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        icon(); Box(Modifier.weight(1f)) { label() }
    }
}

/** 阻断正文边界处传给底部面板的剩余位移和惯性，不影响正文自身滚动。 */
@Composable
private fun Modifier.isolateReviewScroll(state: androidx.compose.foundation.lazy.LazyListState): Modifier {
    val connection = remember(state) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) = Offset(0f, available.y)
            override suspend fun onPreFling(available: Velocity): Velocity =
                if ((available.y < 0 && !state.canScrollForward) || (available.y > 0 && !state.canScrollBackward)) Velocity(0f, available.y) else Velocity.Zero
            override suspend fun onPostFling(consumed: Velocity, available: Velocity) = Velocity(0f, available.y)
        }
    }
    return nestedScroll(connection)
}

@Composable
private fun ReviewDiff(state: SharedWorkspaceChangesSnapshot, selected: Int, wide: Boolean, wraps: Boolean, colors: ReviewColors,
    retrySummary: () -> Unit, retryFile: () -> Unit) {
    when {
        state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        state.error != null -> ChangesFailure(requireNotNull(state.error), retrySummary)
        state.summary?.files.isNullOrEmpty() -> ReviewText("本轮没有可预览的文件改动。", Modifier.padding(16.dp), color = colors.secondary)
        state.selectedIndex != selected && !state.connected -> ChangesFailure("disconnected", retryFile)
        state.selectedIndex != selected || state.diffLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        state.diffError != null -> ChangesFailure(requireNotNull(state.diffError), retryFile)
        state.diff != null -> {
            val diff = requireNotNull(state.diff)
            val rows = remember(diff) { WorkspaceReviewPresentation.rows(diff) }
            val list = rememberLazyListState()
            val gutter = maxOf(if (wide) 40f else 32f,
                (rows.maxOfOrNull { it.lineNumber ?: 0 } ?: 0).toString().length * 8 * LocalDensity.current.fontScale + 8).dp
            // 横向滚动状态属于整个文件，只有一个滚动容器，固定行号列不参与横移。
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                val margin = if (wide) 24.dp else 16.dp
                val width = minOf(maxWidth - margin * 2, WorkspaceChangesStyle.REVIEW_CODE_MAX_WIDTH.dp)
                val longest = remember(rows) { rows.maxOfOrNull { row -> row.text.sumOf { if (it.code > 0x2E7F) 2 else 1 } } ?: 0 }
                val codeWidth = maxOf(width - gutter, ((longest + 2) * (if (wide) 7.8f else 7.2f) + 36).dp)
                var horizontalOffset by remember { mutableFloatStateOf(0f) }
                val maximumOffset = with(LocalDensity.current) { (codeWidth - (width - gutter)).toPx() }.coerceAtLeast(0f)
                val horizontal = rememberScrollableState { delta ->
                    val old = horizontalOffset
                    horizontalOffset = (old - delta).coerceIn(0f, maximumOffset)
                    old - horizontalOffset
                }
                SelectionContainer {
                    LazyColumn(Modifier.width(width).fillMaxHeight()
                        .scrollable(horizontal, Orientation.Horizontal, enabled = !wraps)
                        .isolateReviewScroll(list).testTag("workspace-changes-list"), state = list,
                        contentPadding = PaddingValues(top = if (wide) 20.dp else 12.dp, bottom = 24.dp)) {
                        val message = when {
                            diff.kind == "binary" -> "二进制文件已改动，无法逐行对比。"
                            diff.kind == "oversized" -> "文件过大，无法逐行对比。"
                            diff.coarse -> "比较超时，按整段替换展示。"
                            diff.before == false -> "新建文件"
                            diff.after == false -> "已删除文件"
                            rows.isEmpty() -> "此文件没有可显示的文本差异。"
                            else -> null
                        }
                        message?.let { item { ReviewText(it, Modifier.padding(16.dp), color = colors.secondary) } }
                        itemsIndexed(rows) { index, row ->
                            val endsBlock = index == rows.lastIndex || rows[index + 1].kind == "omitted" || rows[index + 1].kind == "hunk"
                            ReviewDiffLine(row, index, wide, wraps, gutter, codeWidth, horizontalOffset, colors, endsBlock)
                        }
                    }
                }
            }
        }
        else -> ReviewText("正在读取文件改动…", Modifier.padding(16.dp), color = colors.secondary)
    }
}

@Composable
private fun ReviewDiffLine(row: WorkspaceReviewRow, index: Int, wide: Boolean, wraps: Boolean, gutter: androidx.compose.ui.unit.Dp,
    codeWidth: androidx.compose.ui.unit.Dp, horizontalOffset: Float, colors: ReviewColors, endsBlock: Boolean) {
    val background = when (row.kind) { "added" -> colors.addedBG; "deleted" -> colors.deletedBG; "hunk" -> colors.hunkBG; else -> colors.surface }
    val radius = WorkspaceChangesStyle.REVIEW_BLOCK_RADIUS.dp
    val top = if (row.kind == "hunk") radius else 0.dp
    val bottom = if (endsBlock && row.kind != "omitted") radius else 0.dp
    val shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
    if (row.kind == "hunk" || row.kind == "omitted") {
        Box(Modifier.fillMaxWidth().heightIn(min = 32.dp).clip(shape).background(background).padding(horizontal = 12.dp, vertical = 7.dp),
            contentAlignment = if (row.kind == "omitted") Alignment.Center else Alignment.CenterStart) {
            ReviewText(row.text, color = if (row.kind == "omitted") colors.muted else colors.secondary)
        }
    } else {
        val style = TextStyle(fontFamily = reviewCodeFont, fontSize = (if (wide) 13 else 12).sp,
            lineHeight = (if (wide) 22 else 20).sp, letterSpacing = 0.sp,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Top, LineHeightStyle.Trim.None))
        Row(Modifier.fillMaxWidth().clip(shape).background(background).padding(vertical = 3.dp).testTag("workspace-diff-row-$index"), verticalAlignment = Alignment.Top) {
            Text(row.lineNumber?.toString() ?: "", Modifier.width(gutter).testTag("workspace-diff-number-$index"), textAlign = androidx.compose.ui.text.style.TextAlign.End,
                color = colors.muted, style = style.copy(fontSize = 12.sp))
            Box(Modifier.weight(1f).clipToBounds()) {
                Row(if (wraps) Modifier.fillMaxWidth() else Modifier.wrapContentWidth(Alignment.Start, unbounded = true).width(codeWidth)
                    .offset { IntOffset(-horizontalOffset.roundToInt(), 0) }) {
                    Text(when (row.kind) { "added" -> "+"; "deleted" -> "−"; else -> " " }, Modifier.width(24.dp),
                        color = when (row.kind) { "added" -> colors.added; "deleted" -> colors.deleted; else -> colors.muted },
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = style)
                    val content = remember(row.text) { buildAnnotatedString {
                        val raw = row.text.ifEmpty { " " }
                        append(raw)
                        reviewCjk.findAll(raw).forEach { addStyle(SpanStyle(fontFamily = reviewFont), it.range.first, it.range.last + 1) }
                    } }
                    Text(content, Modifier.weight(1f).padding(end = 12.dp).testTag("workspace-diff-text-$index"),
                        color = if (row.kind == "annotation") colors.secondary else colors.ink, style = style,
                        softWrap = wraps, overflow = TextOverflow.Clip)
                }
            }
        }
    }
}

@Composable
private fun ChangesFailure(code: String, retry: () -> Unit) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ReviewText(changesError(code))
        TextButton(onClick = retry) { ReviewText("重试") }
    }
}

private val changeGreen = Color(WorkspaceChangesStyle.ADDED_COLOR_ARGB)
private val changeRed = Color(WorkspaceChangesStyle.DELETED_COLOR_ARGB)

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
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            WorkspaceChangesCapsule(state, onClick = { expanded = true })
        }
    }

    if (expanded && state.sessionId == session && state.visible) WorkspaceChangesSheet(
        state, holder::selectChangedFile, holder::retryWorkspaceChanges,
        dismiss = { expanded = false; holder.closeChangesDiff() },
        workspaceName = holder.activeWorkspace?.title ?: "工作区",
        workspaceRoot = holder.snapshot.workspaces.firstOrNull { session in it.sessionIds }?.path,
        openFile = { path ->
            if (session != null) holder.openConversationFile(holder.gatewayLocalId, session, path)
        }
    )
}

@Composable
internal fun WorkspaceChangesCapsule(
    state: SharedWorkspaceChangesSnapshot,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ComposerGlassSurface(
        modifier = modifier.padding(horizontal = WorkspaceChangesStyle.HORIZONTAL_PADDING.dp)
            .testTag("workspace-changes-capsule")
            .clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(50)
    ) {
        ProvideTextStyle(TextStyle(
            fontSize = WorkspaceChangesStyle.FONT_SIZE.sp,
            lineHeight = WorkspaceChangesStyle.LINE_HEIGHT.sp,
            letterSpacing = 0.sp,
            fontFeatureSettings = "tnum",
            platformStyle = PlatformTextStyle(includeFontPadding = false)
        )) {
            Row(Modifier.padding(
                horizontal = WorkspaceChangesStyle.HORIZONTAL_PADDING.dp,
                vertical = WorkspaceChangesStyle.VERTICAL_PADDING.dp
            ).heightIn(min = WorkspaceChangesStyle.LINE_HEIGHT.dp),
                horizontalArrangement = Arrangement.spacedBy(WorkspaceChangesStyle.ITEM_SPACING.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(state.summary?.let { "${it.total} 个文件" } ?: "文件改动", color = Color(WorkspaceChangesStyle.LABEL_COLOR_ARGB))
                state.summary?.let {
                    Text("+${it.added}", color = changeGreen)
                    Text("−${it.deleted}", color = changeRed)
                }
                if (state.loading) CircularProgressIndicator(Modifier.size(WorkspaceChangesStyle.PROGRESS_SIZE.dp), strokeWidth = 1.5.dp)
                if (state.error != null) Text("查看", color = Color(WorkspaceChangesStyle.LABEL_COLOR_ARGB))
            }
        }
    }
}

/** Android Studio 与 PreviewActivity 可直接比较胶囊和输入框的同源材质。 */
@androidx.compose.ui.tooling.preview.Preview(showBackground = true, widthDp = 393, heightDp = 240)
@Composable
private fun WorkspaceChangesComposerPreview() {
    DshTheme {
        ComposerGlassBackdropHost { backdrop ->
            Box(Modifier.fillMaxWidth().height(240.dp).background(MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxSize().then(backdrop).background(MaterialTheme.colorScheme.surface).padding(14.dp)) {
                    repeat(8) { Text("文件修改预览 · 同一玻璃材质 · +15 −2", color = MaterialTheme.colorScheme.onSurface.copy(alpha = .35f)) }
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 14.dp),
                    horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WorkspaceChangesCapsule(
                        com.clarklevis.dsh.shared.facade.SharedWorkspaceChangesSnapshot(
                            summary = com.clarklevis.dsh.shared.protocol.WorkspaceChangesSummary(2, 4, 15, 2, emptyList())
                        ), onClick = {}
                    )
                    ComposerGlassSurface(Modifier.padding(horizontal = 14.dp).fillMaxWidth(), RoundedCornerShape(24.dp)) {
                        Text("＋    描述你想要构建的内容", Modifier.padding(horizontal = 14.dp, vertical = 18.dp),
                            fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
