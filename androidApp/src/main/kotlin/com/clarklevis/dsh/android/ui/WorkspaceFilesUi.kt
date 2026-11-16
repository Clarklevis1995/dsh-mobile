package com.clarklevis.dsh.android.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.clarklevis.dsh.android.AndroidSharedStateHolder
import com.clarklevis.dsh.android.AndroidWorkspaceDownloadRegistry
import com.clarklevis.dsh.android.AndroidWorkspaceLocalFile
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.shared.facade.FileIconCatalog
import com.clarklevis.dsh.shared.protocol.GatewayDirectoryItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkspaceFilesBottomSheet(
    stateHolder: AndroidSharedStateHolder,
    initialPath: String? = null,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 保持全高根布局供 Material 计算拖动锚点，只用固定留白限制可见面板。
    // 顶部安全区不再参与随 offset 变化的内容高度计算。
    val windowHeight = adaptiveWindowSize().height
    val topGap = maxOf(windowHeight * 0.06f, WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp)
    val sheetHeight = (windowHeight - topGap).coerceAtLeast(0.dp)
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val downloadRegistry = remember(context) { AndroidWorkspaceDownloadRegistry(context, stateHolder.gatewayLocalId) }
    val sessionId = stateHolder.snapshot.selectedSessionId
    var pendingExport by remember { mutableStateOf<AndroidWorkspaceLocalFile?>(null) }
    var pendingExportLease by remember { mutableStateOf<java.io.Closeable?>(null) }
    var downloadedPaths by remember(sessionId) { mutableStateOf(emptySet<String>()) }

    fun refreshDownloadedPaths() {
        downloadedPaths = sessionId?.let { currentSessionId ->
            downloadRegistry.existingRemotePaths(
                currentSessionId,
                stateHolder.workspaceFileEntries.filter { it.kind == "file" }.map { it.path }
            )
        }.orEmpty()
    }

    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val file = pendingExport
        pendingExport = null
        val lease = pendingExportLease
        pendingExportLease = null
        if (uri != null && file != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri, "w")!!.use { output ->
                    file.file.inputStream().use { it.copyTo(output) }
                }
                downloadRegistry.record(file.sessionId, file.remotePath, uri)
                if (file.sessionId == sessionId) downloadedPaths = downloadedPaths + file.remotePath
            }.onFailure { stateHolder.showPlatformError(appText("保存文件失败：%1\$s", it.localizedMessage)) }
        }
        lease?.close()
        file?.let { stateHolder.releaseWorkspaceTemporaryFile(it.file) }
    }

    LaunchedEffect(stateHolder.completedWorkspaceFile) {
        val file = stateHolder.completedWorkspaceFile ?: return@LaunchedEffect
        if (file.purpose == "download") {
            pendingExport = file
            pendingExportLease = stateHolder.leaseWorkspaceFile(file.file)
            exporter.launch(file.name)
        } else {
            return@LaunchedEffect
        }
        stateHolder.consumeCompletedWorkspaceFile()
    }

    LaunchedEffect(stateHolder.snapshot.selectedSessionId) {
        stateHolder.browseWorkspaceFiles(initialPath)
    }

    LaunchedEffect(sessionId, stateHolder.workspaceFileEntries) {
        refreshDownloadedPaths()
    }

    DisposableEffect(lifecycleOwner, sessionId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshDownloadedPaths()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val finishDismiss: () -> Unit = {
        if (stateHolder.workspaceFileDownloadProgress != null) stateHolder.cancelWorkspaceFileDownload()
        onDismiss()
    }
    val closeWithAnimation = rememberWorkspaceSheetDismiss(sheetState, finishDismiss)
    val headerColor = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFF1C1C1E) else Color(0xFFF5F5F5)
    ModalBottomSheet(
        modifier = Modifier.adaptiveSheetBounds().fillMaxHeight().padding(top = topGap),
        sheetMaxWidth = adaptiveSheetWidth(),
        onDismissRequest = finishDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = true,
        contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom) },
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        dragHandle = {
            Box(Modifier.padding(top = 6.dp, bottom = 2.dp).size(width = 36.dp, height = 4.dp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f), CircleShape))
        },
        containerColor = headerColor,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        DshMenuColumnHost {
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(sheetHeight)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(bottom = 12.dp)
            ) {
                WorkspaceFileSheetHeader(
                    path = stateHolder.workspaceFilePath,
                    onBack = { stateHolder.browseWorkspaceFiles(parentWorkspacePath(stateHolder.workspaceFilePath)) },
                    onDismiss = closeWithAnimation,
                    isLoading = stateHolder.workspaceFilesAreLoading
                )
                if (stateHolder.workspaceFilesAreLoading && stateHolder.workspaceFileEntries.isEmpty()) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator()
                            Text(appText("正在读取工作区文件…"), color = secondaryContentColor())
                        }
                    }
                } else if (stateHolder.workspaceFileEntries.isEmpty()) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(appText("此目录为空"), color = secondaryContentColor())
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(bottom = 8.dp)
                    ) {
                        itemsIndexed(
                            items = stateHolder.workspaceFileEntries,
                            key = { _, item -> item.path }
                        ) { _, item ->
                            WorkspaceFileItemRow(
                                stateHolder = stateHolder,
                                item = item,
                                isDownloaded = item.path in downloadedPaths
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 72.dp),
                                color = separatorColor()
                            )
                        }
                    }
                }
                stateHolder.workspaceFileDownloadProgress?.takeIf {
                    stateHolder.workspaceFileDownloadPurpose == "download"
                }?.let { progress ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                appText("正在下载 · %1\$s%", (progress * 100).toInt()),
                                color = secondaryContentColor(),
                                fontSize = 12.sp
                            )
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = stateHolder::cancelWorkspaceFileDownload) { Text(appText("取消")) }
                        }
                    }
                }
            }
        }
    }
}

internal fun openWorkspaceFileExternally(
    context: android.content.Context,
    stateHolder: AndroidSharedStateHolder,
    file: AndroidWorkspaceLocalFile
) {
    runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.workspace-files",
            file.file
        )
        stateHolder.retainExternalWorkspaceFile(file.file)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, file.mediaType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }.onFailure {
        val message = if (it is ActivityNotFoundException) {
            appText("设备上没有可预览 %1\$s 的应用", file.name)
        } else {
            appText("打开文件失败：%1\$s", it.localizedMessage)
        }
        stateHolder.showPlatformError(message)
    }
}

@Composable
internal fun WorkspaceFileSheetHeader(
    path: String,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
    isLoading: Boolean = false
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFF1C1C1E) else Color(0xFFF5F5F5))
            .padding(top = 8.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(54.dp)
                .padding(horizontal = 16.dp)
        ) {
            if (path != ".") {
                WorkspaceSheetIconButton(
                    icon = R.drawable.ic_back_chevron,
                    description = appText("上一级"),
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.CenterStart).testTag("workspace-files-back")
                )
            }
            Text(
                appText("工作区文件"),
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 76.dp),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .height(48.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 3.dp
            ) {
                TextButton(
                    onClick = onDismiss,
                    contentPadding = PaddingValues(horizontal = 20.dp)
                ) {
                    Text(
                        appText("完成"),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_folder_outline),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = secondaryContentColor()
            )
            Text(
                if (path == ".") appText("工作区根目录") else path,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                color = secondaryContentColor(),
                fontSize = 15.sp
            )
            if (isLoading) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }
        HorizontalDivider(color = separatorColor())
    }
}

@Composable
private fun WorkspaceFileItemRow(
    stateHolder: AndroidSharedStateHolder,
    item: GatewayDirectoryItem,
    isDownloaded: Boolean
) {
    val fileIcon = remember(item.name, item.mediaType) { FileIconCatalog.resolve(item.name, item.mediaType) }
    val isDownloading = item.kind == "file" &&
        stateHolder.workspaceFileDownloadPurpose == "download" &&
        stateHolder.workspaceFileDownloadPath == item.path
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(if (item.kind == "directory") Color.Transparent else fileCardColor(fileIcon))
            .clickable(enabled = !stateHolder.workspaceFilesAreLoading) {
                if (item.kind == "directory") stateHolder.browseWorkspaceFiles(item.path)
                else stateHolder.openWorkspaceFile(item, "preview")
            }
            .heightIn(min = 72.dp)
            .padding(start = 24.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (item.kind == "directory") {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_folder_outline),
                    contentDescription = null,
                    modifier = Modifier.size(25.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        } else {
            FileTypeIcon(fileIcon, Modifier.size(40.dp))
        }
        Column(Modifier.padding(start = 12.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                item.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
            if (item.kind == "file") {
                Text(
                    workspaceFileDetail(item),
                    color = secondaryContentColor(),
                    fontSize = 13.sp,
                    maxLines = 1
                )
            }
        }
        if (item.kind == "file") {
            IconButton(
                enabled = !isDownloading,
                onClick = { stateHolder.openWorkspaceFile(item, "download") }
            ) {
                WorkspaceDownloadIcon(
                    progress = stateHolder.workspaceFileDownloadProgress?.takeIf { isDownloading },
                    isDownloaded = isDownloaded,
                    fileName = item.name
                )
            }
        }
    }
}

@Composable
private fun WorkspaceDownloadIcon(
    progress: Float?,
    isDownloaded: Boolean,
    fileName: String
) {
    when {
        progress != null -> Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
            )
            Icon(
                painter = painterResource(R.drawable.ic_download_arrow),
                contentDescription = appText("正在下载 %1\$s", fileName),
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        isDownloaded -> Icon(
            painter = painterResource(R.drawable.ic_check_circle),
            contentDescription = appText("%1\$s 已下载，点击重新下载", fileName),
            modifier = Modifier.size(22.dp),
            tint = DshColors.Success
        )
        else -> Icon(
            painter = painterResource(R.drawable.ic_download_circle),
            contentDescription = appText("下载 %1\$s", fileName),
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun parentWorkspacePath(path: String): String? =
    path.substringBeforeLast('/', missingDelimiterValue = "").ifEmpty { null }

private fun workspaceFileDetail(item: GatewayDirectoryItem): String {
    val size = item.bytes?.let(::formatFileBytes) ?: appText("文件")
    val modified = item.modifiedAt?.let { epoch ->
        SimpleDateFormat(appText("yyyy年M月d日 HH:mm"), Locale.getDefault()).format(Date(epoch.toLong()))
    }
    return listOfNotNull(size, modified).joinToString(" · ")
}

private fun formatFileBytes(bytes: Long): String = when {
    bytes < 1_000 -> "$bytes B"
    bytes < 1_000 * 1_000 -> "${(bytes / 1_000.0).roundedDisplay()} KB"
    bytes < 1_000L * 1_000 * 1_000 -> "${(bytes / 1_000_000.0).roundedDisplay()} MB"
    else -> "${(bytes / 1_000_000_000.0).roundedDisplay()} GB"
}

private fun Double.roundedDisplay(): String =
    if (this < 10) "%.1f".format(Locale.getDefault(), this) else "%.0f".format(Locale.getDefault(), this)


@Composable
private fun secondaryContentColor(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)

@Composable
private fun separatorColor(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f)
