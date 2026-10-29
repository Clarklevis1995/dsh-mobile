package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Velocity
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.android.AndroidSharedStateHolder
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.android.resolveWorkspaceSelection
import com.clarklevis.dsh.shared.protocol.GatewayWorkspace
import kotlinx.coroutines.delay

@Composable
internal fun WorkspaceSelectionMenu(
    expanded: Boolean,
    workspaces: List<GatewayWorkspace>,
    selectedWorkspaceId: String?,
    onSelect: (String) -> Unit,
    onAddWorkspace: () -> Unit,
    onDismiss: () -> Unit
) {
    val dark = isSystemInDarkTheme()
    val foreground = if (dark) Color(0xFFF2F5FA) else Color(0xFF10141A)
    val effectiveSelection = resolveWorkspaceSelection(selectedWorkspaceId, workspaces)
    DshMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier
            .widthIn(min = 250.dp, max = 330.dp)
            .testTag("workspace-menu"),
        offset = DpOffset(14.dp, (-8).dp)) {
        WorkspaceMenuItem(
            title = appText("全部会话"),
            icon = if (effectiveSelection == AndroidSharedStateHolder.ALL_WORKSPACES_ID) {
                WorkspaceMenuIcon.Selected
            } else {
                WorkspaceMenuIcon.Folder
            },
            foreground = foreground,
            modifier = Modifier.testTag("workspace-all"),
            onClick = { onSelect(AndroidSharedStateHolder.ALL_WORKSPACES_ID) }
        )

        WorkspaceMenuItem(
            title = appText("未分组"),
            icon = if (effectiveSelection == AndroidSharedStateHolder.UNGROUPED_WORKSPACE_ID) {
                WorkspaceMenuIcon.Selected
            } else {
                WorkspaceMenuIcon.Ungrouped
            },
            foreground = foreground,
            modifier = Modifier.testTag("workspace-ungrouped"),
            onClick = { onSelect(AndroidSharedStateHolder.UNGROUPED_WORKSPACE_ID) }
        )

        if (workspaces.isNotEmpty()) {
            HorizontalDivider(
                Modifier.padding(horizontal = 16.dp),
                color = foreground.copy(alpha = 0.11f)
            )
        }

        workspaces.forEach { workspace ->
            WorkspaceMenuItem(
                title = workspace.title,
                icon = if (workspace.workspaceId == effectiveSelection) {
                    WorkspaceMenuIcon.Selected
                } else {
                    WorkspaceMenuIcon.Folder
                },
                foreground = foreground,
                onClick = { onSelect(workspace.workspaceId) }
            )
        }

        HorizontalDivider(
            Modifier.padding(horizontal = 16.dp),
            color = foreground.copy(alpha = 0.11f)
        )
        WorkspaceMenuItem(
            title = appText("添加工作区"),
            icon = WorkspaceMenuIcon.Add,
            foreground = foreground,
            modifier = Modifier.testTag("workspace-add"),
            onClick = onAddWorkspace
        )
    }
}

private enum class WorkspaceMenuIcon { Selected, Ungrouped, Folder, Add }

@Composable
private fun WorkspaceMenuItem(
    title: String,
    icon: WorkspaceMenuIcon,
    foreground: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    DshMenuItem(
        text = {
            Text(
                title,
                color = foreground,
                fontSize = 16.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        },
        onClick = onClick,
        modifier = modifier.height(50.dp),
        leadingIcon = {
            when (icon) {
                WorkspaceMenuIcon.Selected -> Box(
                    Modifier.size(22.dp).background(foreground, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = androidx.compose.ui.res.painterResource(R.drawable.ic_menu_check),
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        colorFilter = ColorFilter.tint(if (foreground.luminance() > 0.5f) Color.Black else Color.White)
                    )
                }
                WorkspaceMenuIcon.Ungrouped -> MenuVectorIcon(R.drawable.ic_workspace_tray, foreground)
                WorkspaceMenuIcon.Folder -> MenuVectorIcon(R.drawable.ic_folder_outline, foreground)
                WorkspaceMenuIcon.Add -> MenuVectorIcon(R.drawable.ic_add, foreground)
            }
        },
        contentPadding = PaddingValues(horizontal = 16.dp)
    )
}

@Composable
private fun MenuVectorIcon(resource: Int, tint: Color) {
    Image(
        painter = androidx.compose.ui.res.painterResource(resource),
        contentDescription = null,
        modifier = Modifier.size(22.dp),
        colorFilter = ColorFilter.tint(tint)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkspaceDirectoryBrowserSheet(
    stateHolder: AndroidSharedStateHolder,
    onDismiss: () -> Unit,
    contentModifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 高度和顶部留白只依赖窗口；拖动柄与系统底部区域均包含在这个固定高度内。
    val windowHeight = adaptiveWindowSize().height
    val statusBarHeight = with(LocalDensity.current) { WindowInsets.statusBars.getTop(this).toDp() }
    val sheetHeight = minOf(windowHeight * 0.94f, (windowHeight - statusBarHeight).coerceAtLeast(1.dp))
    val listState = rememberLazyListState()
    val listBoundary = remember(listState) {
        object : NestedScrollConnection {
            // 列表到底后消耗向上的余量，阻止父弹窗继续接管上滑；向下仍交给原生关闭手势。
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                Offset(0f, available.y.coerceAtMost(0f))

            override suspend fun onPreFling(available: Velocity): Velocity =
                if (!listState.canScrollForward) Velocity(0f, available.y.coerceAtMost(0f)) else Velocity.Zero

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
                Velocity(0f, available.y.coerceAtMost(0f))
        }
    }
    val dark = isSystemInDarkTheme()
    val sheetBackground = if (dark) Color(0xFF171A20) else Color(0xFFF3F3F8)
    val cardBackground = if (dark) Color(0xFF22262D) else Color.White
    val foreground = MaterialTheme.colorScheme.onSurface
    var showCreateDirectoryPrompt by rememberSaveable { mutableStateOf(false) }
    var newDirectoryName by rememberSaveable { mutableStateOf("") }
    var creatingWorkspacePath by rememberSaveable { mutableStateOf<String?>(null) }
    var highlightedPath by remember { mutableStateOf<String?>(null) }
    val parentPath = stateHolder.directoryCrumbs
        .takeIf { it.size > 1 }
        ?.dropLast(1)
        ?.lastOrNull()
        ?.path

    LaunchedEffect(Unit) {
        stateHolder.beginDirectoryBrowsing()
    }
    LaunchedEffect(stateHolder.createdDirectoryPathToReveal, stateHolder.directoryEntries) {
        val createdPath = stateHolder.createdDirectoryPathToReveal ?: return@LaunchedEffect
        val index = stateHolder.directoryEntries.indexOfFirst { it.path == createdPath }
        if (index < 0) return@LaunchedEffect
        highlightedPath = createdPath
        stateHolder.acknowledgeCreatedDirectoryReveal(createdPath)
        listState.animateScrollToItem(index + 1)
        delay(1_600)
        if (highlightedPath == createdPath) highlightedPath = null
    }
    LaunchedEffect(stateHolder.workspaceCreationCompletedPath) {
        val completedPath = stateHolder.workspaceCreationCompletedPath ?: return@LaunchedEffect
        if (creatingWorkspacePath == completedPath) {
            stateHolder.acknowledgeWorkspaceCreation(completedPath)
            onDismiss()
        }
    }

    ModalBottomSheet(
        sheetMaxWidth = adaptiveSheetWidth(),
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = sheetBackground,
        contentColor = foreground,
        dragHandle = null,
        // 自行处理稳定的系统区域，避免 M3 按 sheet offset 消费顶部 Insets 后改变高度。
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        modifier = Modifier.adaptiveSheetBounds().testTag("directory-browser-sheet")
    ) {
        DshMenuColumnHost {
            Column(
                contentModifier
                    .fillMaxWidth()
                    .height(sheetHeight)
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                BottomSheetDefaults.DragHandle(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    width = 38.dp,
                    height = 5.dp,
                    color = foreground.copy(alpha = 0.30f)
                )
                DirectoryBrowserHeader(
                    enabled = stateHolder.directoryPath != null &&
                        !stateHolder.directoryIsLoading &&
                        !stateHolder.directoryCreationIsLoading,
                    onCreateDirectory = {
                        newDirectoryName = ""
                        showCreateDirectoryPrompt = true
                    },
                    onDismiss = onDismiss
                )

                Column(Modifier.padding(horizontal = 22.dp)) {
                    Text(
                        appText("当前目录"),
                        color = foreground.copy(alpha = 0.52f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        stateHolder.directoryPath ?: appText("正在读取…"),
                        color = foreground.copy(alpha = 0.40f),
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.height(14.dp))
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 18.dp),
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    color = cardBackground
                ) {
                    if (stateHolder.directoryIsLoading && stateHolder.directoryEntries.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                Text(appText("正在读取远程目录…"), color = foreground.copy(alpha = 0.58f))
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.nestedScroll(listBoundary).testTag("directory-browser-list"),
                            state = listState,
                            userScrollEnabled = !stateHolder.directoryIsLoading &&
                                !stateHolder.directoryCreationIsLoading,
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
                        ) {
                            item(key = "parent") {
                                DirectoryRow(
                                    title = "..",
                                    subtitle = appText("返回上一级"),
                                    icon = DirectoryIcon.Parent,
                                    enabled = parentPath != null,
                                    highlighted = false,
                                    onClick = { parentPath?.let(stateHolder::browseDirectories) }
                                )
                                DirectoryDivider()
                            }
                            itemsIndexed(
                                items = stateHolder.directoryEntries,
                                key = { _, entry -> entry.path }
                            ) { index, entry ->
                                DirectoryRow(
                                    title = entry.name,
                                    subtitle = if (entry.hidden) appText("隐藏目录") else null,
                                    icon = if (entry.hidden) DirectoryIcon.HiddenFolder else DirectoryIcon.Folder,
                                    enabled = true,
                                    highlighted = highlightedPath == entry.path,
                                    modifier = Modifier.testTag("directory-row-${entry.path}"),
                                    onClick = { stateHolder.browseDirectories(entry.path) }
                                )
                                if (index != stateHolder.directoryEntries.lastIndex) DirectoryDivider()
                            }
                        }
                    }
                }

                DirectoryCreateWorkspaceBar(
                    path = stateHolder.directoryPath,
                    workspaceCreationIsLoading = stateHolder.workspaceCreationIsLoading,
                    directoryCreationIsLoading = stateHolder.directoryCreationIsLoading,
                    directoryIsLoading = stateHolder.directoryIsLoading,
                    onCreateWorkspace = { path ->
                        creatingWorkspacePath = path
                        stateHolder.createWorkspace(path)
                    }
                )
            }
        }
    }

    if (showCreateDirectoryPrompt) {
        DshAlertDialog(
            title = appText("新建文件夹"),
            message = appText("将在当前目录中创建一个新的子文件夹。"),
            onDismissRequest = { showCreateDirectoryPrompt = false },
            dismissLabel = appText("取消"),
            onDismissClick = { showCreateDirectoryPrompt = false },
            confirmLabel = appText("创建"),
            confirmEnabled = newDirectoryName.trim().isNotEmpty(),
            onConfirm = {
                val parent = stateHolder.directoryPath ?: return@DshAlertDialog
                showCreateDirectoryPrompt = false
                stateHolder.createDirectory(parent, newDirectoryName)
            },
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newDirectoryName,
                        onValueChange = { newDirectoryName = it },
                        label = { Text(appText("文件夹名称")) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                        modifier = Modifier.fillMaxWidth().testTag("new-directory-name")
                    )
                }
            }
        )
    }
}

@Composable
private fun DirectoryBrowserHeader(
    enabled: Boolean,
    onCreateDirectory: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        Modifier.fillMaxWidth().height(66.dp).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(appText("选择工作区目录"), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        IconButton(
            onClick = onCreateDirectory,
            enabled = enabled,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))
                .testTag("create-directory")
        ) {
            Box(contentAlignment = Alignment.TopEnd) {
                Image(
                    painter = androidx.compose.ui.res.painterResource(R.drawable.ic_folder_outline),
                    contentDescription = appText("新建文件夹"),
                    modifier = Modifier.size(26.dp),
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface)
                )
                Box(
                    Modifier.size(11.dp).background(MaterialTheme.colorScheme.surface, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("+", fontSize = 11.sp, lineHeight = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        TextButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .height(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))
        ) {
            Text(appText("取消"), color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp)
        }
    }
}

private enum class DirectoryIcon { Parent, Folder, HiddenFolder }

@Composable
private fun DirectoryRow(
    title: String,
    subtitle: String?,
    icon: DirectoryIcon,
    enabled: Boolean,
    highlighted: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val foreground = MaterialTheme.colorScheme.onSurface
    val rowModifier = modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .background(if (highlighted) DshColors.Ocean.copy(alpha = 0.20f) else Color.Transparent)
        .clickable(
            enabled = enabled,
            role = Role.Button,
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick
        )
        .alpha(if (enabled) 1f else 0.30f)
        .padding(horizontal = 8.dp, vertical = 12.dp)
    Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(34.dp), contentAlignment = Alignment.CenterStart) {
            Box(Modifier.size(28.dp), contentAlignment = Alignment.CenterStart) {
                Image(
                    painter = androidx.compose.ui.res.painterResource(
                        if (icon == DirectoryIcon.Parent) R.drawable.ic_parent_directory
                        else R.drawable.ic_folder_outline
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(25.dp),
                    colorFilter = ColorFilter.tint(DshColors.Ocean)
                )
                if (icon == DirectoryIcon.HiddenFolder) {
                    Image(
                        painter = androidx.compose.ui.res.painterResource(R.drawable.ic_question_badge),
                        contentDescription = null,
                        modifier = Modifier.align(Alignment.TopEnd).size(13.dp)
                    )
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, color = foreground, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(it, color = foreground.copy(alpha = 0.46f), fontSize = 12.sp)
            }
        }
        Image(
            painter = androidx.compose.ui.res.painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            colorFilter = ColorFilter.tint(DshColors.Mist.copy(alpha = 0.88f))
        )
    }
}

@Composable
private fun DirectoryDivider() {
    HorizontalDivider(
        Modifier.padding(start = 58.dp),
        thickness = 0.6.dp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    )
}

@Composable
private fun DirectoryCreateWorkspaceBar(
    path: String?,
    workspaceCreationIsLoading: Boolean,
    directoryCreationIsLoading: Boolean,
    directoryIsLoading: Boolean,
    onCreateWorkspace: (String) -> Unit
) {
    val enabled = path != null &&
        !workspaceCreationIsLoading &&
        !directoryCreationIsLoading &&
        !directoryIsLoading
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        path?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black)
                .clickable(enabled = enabled, role = Role.Button) { path?.let(onCreateWorkspace) }
                .testTag("create-workspace"),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (workspaceCreationIsLoading) {
                CircularProgressIndicator(
                    Modifier.size(19.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            } else {
                Image(
                    painter = androidx.compose.ui.res.painterResource(R.drawable.ic_add),
                    contentDescription = null,
                    modifier = Modifier.size(21.dp),
                    colorFilter = ColorFilter.tint(Color.White)
                )
            }
            Spacer(Modifier.width(9.dp))
            Text(appText("在当前目录创建工作区"), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        if (directoryCreationIsLoading) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.7.dp)
                Text(appText("正在创建文件夹…"), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f), fontSize = 12.sp)
            }
        }
    }
}

private fun Color.luminance(): Float =
    (red * 0.299f) + (green * 0.587f) + (blue * 0.114f)
