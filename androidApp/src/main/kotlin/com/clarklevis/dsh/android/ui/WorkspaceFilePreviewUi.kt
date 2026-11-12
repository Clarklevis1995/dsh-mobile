package com.clarklevis.dsh.android.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clarklevis.dsh.android.AndroidSharedStateHolder
import com.clarklevis.dsh.android.AndroidWorkspaceDownloadRegistry
import com.clarklevis.dsh.android.AndroidWorkspaceLocalFile
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.shared.facade.FileIconCatalog
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 聊天和目录共用唯一预览监听器，下载导出仍由目录处理。 */
@Composable
internal fun WorkspaceFilePreviewHost(stateHolder: AndroidSharedStateHolder) {
    val context = LocalContext.current
    val sessionId = stateHolder.snapshot.selectedSessionId
    val scope = rememberCoroutineScope()
    var pendingExport by remember { mutableStateOf<AndroidWorkspaceLocalFile?>(null) }
    var pendingExportLease by remember { mutableStateOf<java.io.Closeable?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val exported = pendingExport
        pendingExport = null
        val lease = pendingExportLease
        pendingExportLease = null
        if (uri != null && exported != null) {
            scope.launch {
                try {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            requireNotNull(context.contentResolver.openOutputStream(uri, "w")).use { output ->
                                exported.file.inputStream().use { it.copyTo(output) }
                            }
                            AndroidWorkspaceDownloadRegistry(context, stateHolder.gatewayLocalId)
                                .record(exported.sessionId, exported.remotePath, uri)
                        }
                    }.onFailure { stateHolder.showPlatformError(appText("保存文件失败：%1\$s", it.localizedMessage)) }
                } finally {
                    lease?.close()
                    stateHolder.releaseWorkspaceTemporaryFile(exported.file)
                }
            }
        } else {
            lease?.close()
            exported?.let { stateHolder.releaseWorkspaceTemporaryFile(it.file) }
        }
    }
    DisposableEffect(stateHolder, sessionId) {
        onDispose { stateHolder.cancelWorkspaceFileDownload() }
    }
    val request = stateHolder.workspaceFilePreviewRequest ?: return
    if (request.sessionId != sessionId) return
    val file = stateHolder.completedWorkspaceFile?.takeIf {
        it.purpose == "preview" && it.sessionId == request.sessionId
    }
    WorkspaceFilePreviewSheet(
        file = file,
        fileName = file?.name ?: request.name,
        onDismiss = stateHolder::cancelWorkspaceFileDownload,
        onSave = file?.let { ready ->
            {
                pendingExport = ready
                pendingExportLease = stateHolder.leaseWorkspaceFile(ready.file)
                exporter.launch(ready.name)
            }
        }
    ) { contentPadding ->
        if (file == null) {
            WorkspaceFilePreviewLoadingContent(
                stateHolder.conversationFileIsOpening,
                stateHolder.workspaceFilePreviewError ?: stateHolder.platformError,
                contentPadding,
                stateHolder.workspaceFilePreviewProgress
            )
        } else {
            val external = { openWorkspaceFileExternally(context, stateHolder, file) }
            val images = rememberDshMarkdownImages(stateHolder, file.sessionId)
            androidx.compose.runtime.CompositionLocalProvider(LocalDshMarkdownImages provides images) {
                WorkspaceFilePreviewContent(file, external, contentPadding)
            }
        }
    }
}

@Composable
internal fun WorkspaceFilePreviewContent(file: AndroidWorkspaceLocalFile, onOpenExternal: () -> Unit, contentPadding: PaddingValues) {
    androidx.compose.runtime.key(file.file) {
        when (workspacePreviewKind(file.name, file.mediaType)) {
            WorkspacePreviewKind.IMAGE -> WorkspaceImagePreviewContent(file, onOpenExternal, contentPadding)
            WorkspacePreviewKind.VIDEO -> WorkspaceVideoPreviewContent(file, onOpenExternal, contentPadding)
            WorkspacePreviewKind.CODE -> WorkspaceCodePreviewContent(file, onOpenExternal, contentPadding)
            WorkspacePreviewKind.PDF -> WorkspacePdfPreviewContent(file, onOpenExternal, contentPadding)
            WorkspacePreviewKind.EXTERNAL -> Column(
                Modifier.fillMaxSize().padding(contentPadding).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                FileTypeIcon(FileIconCatalog.resolve(file.name, file.mediaType), Modifier.size(64.dp))
                Text(appText("此文件可使用其他应用打开或保存。"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onOpenExternal) { Text(appText("使用其他应用打开")) }
            }
        }
    }
}

@Composable
internal fun WorkspaceFilePreviewLoadingContent(isLoading: Boolean, error: String?, contentPadding: PaddingValues, progress: Float) {
    Column(
        Modifier.fillMaxSize().padding(contentPadding).padding(24.dp).testTag("workspace-preview-loading"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
    ) {
        Text(
            if (isLoading) appText("正在加载中") else error ?: appText("文件加载失败"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (isLoading) {
            WorkspaceLoadingLogo()
            Text(
                "${(progress.coerceIn(0f, 1f) * 100).toInt()}%",
                modifier = Modifier.testTag("workspace-preview-progress"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")
            )
        }
    }
}

/** 与 iOS 一致的预览栏：关闭、居中可换行文件名，以及保存菜单。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkspaceFilePreviewSheet(
    file: AndroidWorkspaceLocalFile?,
    onDismiss: () -> Unit,
    onSave: (() -> Unit)? = null,
    fileName: String = file?.name.orEmpty(),
    overlay: @Composable BoxScope.(PaddingValues) -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val closeWithAnimation = rememberWorkspaceSheetDismiss(sheetState, onDismiss)
    val density = LocalDensity.current
    var headerHeight by remember { mutableStateOf(76.dp) }
    var showsMenu by remember(file?.file) { mutableStateOf(false) }
    ModalBottomSheet(
        modifier = Modifier.adaptiveSheetBounds(),
        sheetMaxWidth = adaptiveSheetWidth(),
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = true,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        properties = ModalBottomSheetProperties(
            isAppearanceLightStatusBars = !dark,
            isAppearanceLightNavigationBars = !dark
        )
    ) {
        DshMenuColumnHost {
            Box(Modifier.fillMaxWidth().fillMaxHeight(0.94f).testTag("workspace-file-preview-sheet")) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                    content(PaddingValues(top = headerHeight))
                }
                overlay(PaddingValues(top = headerHeight))
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .onSizeChanged { headerHeight = with(density) { it.height.toDp() } }
                        .background(MaterialTheme.colorScheme.surface)
                        .testTag("workspace-preview-header")
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    WorkspaceSheetIconButton(R.drawable.ic_close, appText("关闭文件预览"), closeWithAnimation)
                    Text(
                        fileName,
                        modifier = Modifier.weight(1f).testTag("workspace-preview-title"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                    Box {
                        WorkspaceSheetIconButton(R.drawable.ic_more_horizontal, appText("文件菜单"), { showsMenu = true })
                        val menuShape = RoundedCornerShape(28.dp)
                        DshMenu(
                            expanded = showsMenu,
                            onDismissRequest = { showsMenu = false },
                            modifier = Modifier.width(232.dp).testTag("workspace-preview-menu"),
                            offset = DpOffset(0.dp, 8.dp)
                        ) {
                            DshMenuItem(
                                text = { Text(appText("保存"), fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface) },
                                trailingIcon = {
                                    Icon(
                                        painterResource(R.drawable.ic_save_to_files),
                                        appText("保存到文件"),
                                        Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                enabled = onSave != null,
                                onClick = {
                                    showsMenu = false
                                    onSave?.invoke()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun WorkspacePdfPreviewSheet(
    file: AndroidWorkspaceLocalFile,
    onDismiss: () -> Unit,
    onOpenExternal: () -> Unit,
    onSave: (() -> Unit)? = null
) {
    WorkspaceFilePreviewSheet(file, onDismiss, onSave) { contentPadding ->
        WorkspacePdfPreviewContent(file, onOpenExternal, contentPadding)
    }
}

@Composable
internal fun WorkspacePdfPreviewContent(file: AndroidWorkspaceLocalFile, onOpenExternal: () -> Unit, contentPadding: PaddingValues) {
    var pageCount by remember(file.file) { mutableIntStateOf(0) }
    var error by remember(file.file) { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val currentPage by remember(listState) {
        derivedStateOf {
            val layout = listState.layoutInfo
            val page = layout.visibleItemsInfo.maxByOrNull { item ->
                (min(item.offset + item.size, layout.viewportEndOffset) - maxOf(item.offset, 0)).coerceAtLeast(0)
            }
            (page?.index ?: 0) + 1
        }
    }
    LaunchedEffect(file.file) {
        try {
            pageCount = withContext(Dispatchers.IO) {
                ParcelFileDescriptor.open(file.file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { it.pageCount }
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = appText("无法预览 PDF：%1\$s", failure.localizedMessage)
        }
    }
    Box(Modifier.fillMaxSize()) {
        when {
            error != null -> Column(
                Modifier.fillMaxSize().padding(contentPadding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(error.orEmpty())
                TextButton(onClick = onOpenExternal) { Text(appText("使用系统应用打开")) }
            }
            pageCount == 0 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            else -> Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().testTag("workspace-pdf-pages"),
                    contentPadding = PaddingValues(top = contentPadding.calculateTopPadding(), bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(pageCount, key = { it }) { page -> WorkspacePdfPage(file, page) }
                }
            }
        }
        if (pageCount > 0 && error == null) {
            Row(
                modifier = Modifier.align(Alignment.TopStart)
                    .padding(start = 16.dp, top = contentPadding.calculateTopPadding() + 12.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("workspace-pdf-page-indicator"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(painterResource(R.drawable.ic_pdf_pages), null, Modifier.size(18.dp))
                Text("$currentPage/$pageCount", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun WorkspacePdfPage(file: AndroidWorkspaceLocalFile, page: Int) {
    var image by remember(file.file, page) { mutableStateOf<Bitmap?>(null) }
    var error by remember(file.file, page) { mutableStateOf<String?>(null) }
    LaunchedEffect(file.file, page) {
        try {
            image = withContext(Dispatchers.IO) {
                // LazyColumn 仅为可见及预取页生成位图，离屏页随组合回收；避免整份 PDF 常驻内存。
                ParcelFileDescriptor.open(file.file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        renderer.openPage(page).use { pdfPage ->
                            val scale = min(1600f / pdfPage.width, 2200f / pdfPage.height).coerceAtMost(2f)
                            val bitmap = Bitmap.createBitmap(
                                (pdfPage.width * scale).toInt().coerceAtLeast(1),
                                (pdfPage.height * scale).toInt().coerceAtLeast(1),
                                Bitmap.Config.ARGB_8888
                            )
                            bitmap.eraseColor(AndroidColor.WHITE)
                            pdfPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bitmap
                        }
                    }
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = appText("第 %1\$s 页无法预览：%2\$s", page + 1, failure.localizedMessage)
        }
    }
    val bitmap = image
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 2.dp)
            .aspectRatio(bitmap?.let { it.width.toFloat() / it.height } ?: (1f / 1.414f))
            .background(androidx.compose.ui.graphics.Color.White),
        contentAlignment = Alignment.Center
    ) {
        when {
            bitmap != null -> Image(bitmap.asImageBitmap(), appText("第 %1\$s 页", page + 1), Modifier.fillMaxSize())
            error != null -> Text(error.orEmpty(), Modifier.padding(16.dp), color = androidx.compose.ui.graphics.Color.DarkGray)
            else -> CircularProgressIndicator()
        }
    }
}
