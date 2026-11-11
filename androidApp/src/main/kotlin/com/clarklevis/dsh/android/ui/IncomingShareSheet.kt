package com.clarklevis.dsh.android.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Velocity
import androidx.exifinterface.media.ExifInterface
import com.clarklevis.dsh.android.IncomingShare
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.shared.domain.SessionSummary
import com.clarklevis.dsh.shared.facade.FileIconCatalog
import com.clarklevis.dsh.shared.protocol.GatewayWorkspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val SHARE_UNGROUPED = "__ungrouped__"

/** 与主页筛选独立；归属重复时按网关提供的第一个工作区处理。 */
internal class ShareDestinations(
    sessions: List<SessionSummary>,
    val workspaces: List<GatewayWorkspace>,
    childSessionIds: Set<String> = emptySet()
) {
    val sessions = sessions.filter { it.isVisibleInHistory && it.id !in childSessionIds }
        .distinctBy { it.id }.sortedWith(compareByDescending<SessionSummary> { it.lastActivityEpochSeconds }.thenBy { it.id })
    private val owners = buildMap<String, String> {
        workspaces.forEach { workspace -> workspace.sessionIds.forEach { putIfAbsent(it, workspace.workspaceId) } }
    }
    val recent = this.sessions.filter { it.isRunning }.take(3)
    fun owner(id: String): String = owners[id] ?: SHARE_UNGROUPED
    fun containsWorkspace(id: String): Boolean = id == SHARE_UNGROUPED || workspaces.any { it.workspaceId == id }
    fun initialWorkspace(preferred: String?): String = preferred?.takeIf(::containsWorkspace)
        ?: workspaces.firstOrNull()?.workspaceId ?: SHARE_UNGROUPED
    fun title(id: String): String = workspaces.firstOrNull { it.workspaceId == id }
        ?.let { it.title.ifBlank { it.path } } ?: appText("未分组")
    fun rows(workspace: String, query: String = ""): List<SessionSummary> = sessions.filter {
        owner(it.id) == workspace && (query.isBlank() || it.title.contains(query.trim(), true) || it.id.contains(query.trim(), true))
    }
}

private data class SharePalette(val sheet: Color, val card: Color, val ink: Color, val secondary: Color, val blue: Color, val dark: Boolean)
@Composable private fun sharePalette(): SharePalette = if (isSystemInDarkTheme()) {
    SharePalette(Color(0xFF1C1C1E), Color(0xFF2C2C2E), Color.White, Color(0xFFABABAF), Color(0xFF0A84FF), true)
} else {
    SharePalette(Color(0xFFF2F2F7), Color.White, Color.Black, Color(0xFF85858B), Color(0xFF0088FF), false)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IncomingShareSheet(
    share: IncomingShare,
    destinations: ShareDestinations,
    initialWorkspace: String?,
    creating: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onCreate: (String?) -> Unit
) {
    val palette = sharePalette()
    val busy by rememberUpdatedState(creating)
    var workspace by rememberSaveable(share.id) { mutableStateOf(destinations.initialWorkspace(initialWorkspace)) }
    var page by rememberSaveable(share.id) { mutableStateOf("sessions") }
    var sessionQuery by rememberSaveable(share.id) { mutableStateOf("") }
    var workspaceQuery by rememberSaveable(share.id) { mutableStateOf("") }
    val listState = key(page) { rememberLazyListState() }
    val listBoundary = remember(listState) {
        object : NestedScrollConnection {
            // 列表消耗完后截住两个方向的剩余位移，面板拖动只由列表以外的区域发起。
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                Offset(0f, available.y)

            override suspend fun onPreFling(available: Velocity): Velocity =
                if ((available.y < 0 && !listState.canScrollForward) ||
                    (available.y > 0 && !listState.canScrollBackward)) Velocity(0f, available.y)
                else Velocity.Zero

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
                Velocity(0f, available.y)
        }
    }
    LaunchedEffect(destinations, workspace) {
        if (!destinations.containsWorkspace(workspace)) workspace = destinations.initialWorkspace(initialWorkspace)
    }
    val close = { if (!creating) { if (page == "sessions") onDismiss() else page = "sessions" } }
    ModalBottomSheet(
        onDismissRequest = { if (!creating) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !busy || it != SheetValue.Hidden }),
        modifier = Modifier.adaptiveSheetBounds(),
        sheetMaxWidth = adaptiveSheetWidth(),
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        containerColor = palette.sheet,
        contentColor = palette.ink,
        scrimColor = Color.Black.copy(alpha = .28f),
        contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal) },
        dragHandle = { Box(Modifier.fillMaxWidth().height(24.dp).testTag("share-sheet-handle"), contentAlignment = Alignment.Center) {
            Box(Modifier.size(36.dp, 5.dp).background(palette.secondary.copy(alpha = .3f), CircleShape))
        } }
    ) {
        DshMenuColumnHost {
            BackHandler(enabled = page != "sessions" && !creating) { page = "sessions" }
            Column(Modifier.fillMaxWidth().height(adaptiveWindowSize().height * .91f - 24.dp).navigationBarsPadding().testTag("incoming-share-sheet")) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 12.dp).testTag("share-sheet-header"),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(appText(when (page) { "workspaces" -> "选择工作区"; "preview" -> "分享内容"; else -> "分享到会话" }),
                        modifier = Modifier.weight(1f), fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = close, enabled = !creating,
                        modifier = Modifier.size(44.dp).background(if (palette.dark) Color.Transparent else palette.card, CircleShape).testTag("share-close")) {
                        ShareIcon(if (page == "sessions") R.drawable.ic_share_close else R.drawable.ic_share_back,
                            appText(if (page == "sessions") "关闭" else "返回"), palette.secondary, 18)
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp)) }
                if (creating) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("share-creating"), color = palette.blue)
                LazyColumn(Modifier.weight(1f).fillMaxWidth().nestedScroll(listBoundary).testTag("share-destinations-$page"),
                    state = listState,
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.Top) {
                    when (page) {
                        "workspaces" -> {
                            shareSection { Text(appText("分享内容会保留，切换后查看对应会话"), color = palette.secondary, fontSize = 13.sp, lineHeight = 18.sp) }
                            shareSection { ShareSearch(workspaceQuery, { workspaceQuery = it }, appText("搜索工作区"), palette, "share-workspace-search") }
                            val ids = (destinations.workspaces.map { it.workspaceId } + SHARE_UNGROUPED)
                                .filter { workspaceQuery.isBlank() || destinations.title(it).contains(workspaceQuery.trim(), true) }
                            itemsIndexed(ids, key = { _, id -> "workspace:$id" }) { index, id ->
                                ShareDestinationRow(destinations.title(id),
                                    appText(if (workspace == id) "%1\$s 个会话 · 当前工作区" else "%1\$s 个会话", destinations.rows(id).size),
                                    palette, "share-workspace-$id", enabled = !creating, first = index == 0, last = index == ids.lastIndex) {
                                    workspace = id; sessionQuery = ""; page = "sessions"
                                }
                            }
                            if (ids.isEmpty()) shareSection { ShareEmpty(palette) }
                            shareSection {
                                Text(appText("最近会话入口可跨工作区直达"), color = palette.secondary,
                                    fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 14.dp))
                            }
                        }
                        "preview" -> {
                            if (share.text.isNotBlank()) shareSection {
                                Text(share.text, color = palette.ink, fontSize = 15.sp,
                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(palette.card).padding(16.dp))
                            }
                            items(share.files, key = Uri::toString) { uri ->
                                Row(Modifier.fillMaxWidth().background(palette.card, RoundedCornerShape(18.dp)).padding(14.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    ShareThumbnail(uri, 52, palette)
                                    Text(uri.lastPathSegment.orEmpty(), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        else -> {
                            shareSection {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    ShareThumbnail(share.files.firstOrNull(), 52, palette, share.text.isNotBlank())
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(appText("%1\$s 个项目", share.files.size + if (share.text.isNotBlank()) 1 else 0), fontSize = 17.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium)
                                        Text(share.files.joinToString(" · ") { it.lastPathSegment.orEmpty() }.ifBlank { share.text },
                                            fontSize = 12.sp, lineHeight = 16.sp, color = palette.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    TextButton(onClick = { page = "preview" }, enabled = !creating, modifier = Modifier.testTag("share-preview")) {
                                        Text(appText("预览"), color = palette.blue, fontSize = 13.sp, lineHeight = 18.sp)
                                    }
                                }
                            }
                            shareSection {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(appText("最近进行中"), fontSize = 13.sp, lineHeight = 18.sp, color = palette.secondary)
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        destinations.recent.forEach { session ->
                                            ShareShortcut(Modifier.weight(1f), session.title, destinations.title(destinations.owner(session.id)),
                                                palette, !creating, "share-recent-${session.id}", session.title.take(session.title.offsetByCodePoints(0, if (session.title.isEmpty()) 0 else 1))) { onSelect(session.id) }
                                        }
                                        ShareShortcut(Modifier.weight(1f), appText("新会话"), appText("当前工作区"), palette,
                                            !creating, "share-new", null) { onCreate(workspace.takeUnless { it == SHARE_UNGROUPED }) }
                                        repeat(3 - destinations.recent.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                    if (destinations.recent.isEmpty()) Text(appText("暂无进行中的会话"), fontSize = 12.sp, lineHeight = 16.sp, color = palette.secondary)
                                }
                            }
                            shareSection {
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(palette.card)
                                    .clickable(enabled = !creating, role = Role.Button) { page = "workspaces" }
                                    .testTag("share-workspace-selector").padding(horizontal = 14.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    ShareIcon(R.drawable.ic_folder_outline, null, palette.blue, 24)
                                    Column(Modifier.weight(1f)) {
                                        Text(appText("工作区"), fontSize = 11.sp, lineHeight = 14.sp, color = palette.secondary)
                                        Text(destinations.title(workspace), fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Text(appText("%1\$s 个会话", destinations.rows(workspace).size), fontSize = 12.sp, lineHeight = 16.sp, color = palette.secondary)
                                    ShareIcon(R.drawable.ic_share_chevron, null, palette.secondary, 16)
                                }
                            }
                            shareSection { ShareSearch(sessionQuery, { sessionQuery = it }, appText("搜索此工作区的会话"), palette, "share-session-search") }
                            val rows = destinations.rows(workspace, sessionQuery)
                            itemsIndexed(rows, key = { _, session -> "session:${session.id}" }) { index, session ->
                                ShareDestinationRow(session.title, if (session.isRunning) appText("正在进行") else relativeTime(session.lastActivityEpochSeconds),
                                    palette, "share-session-${session.id}", !creating, first = index == 0, last = index == rows.lastIndex) { onSelect(session.id) }
                            }
                            if (rows.isEmpty()) shareSection { ShareEmpty(palette) }
                            shareSection { Text(appText("选择后进入会话，内容加入输入框草稿"), color = palette.secondary, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 14.dp)) }
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.shareSection(content: @Composable () -> Unit) {
    item { Column(Modifier.padding(bottom = 14.dp)) { content() } }
}

@Composable private fun ShareIcon(resource: Int, description: String?, color: Color, size: Int) {
    Icon(painterResource(resource), description, tint = color, modifier = Modifier.size(size.dp))
}

@Composable private fun ShareSearch(value: String, onChange: (String) -> Unit, hint: String, palette: SharePalette, tag: String) {
    Row(Modifier.fillMaxWidth().background(palette.card, RoundedCornerShape(14.dp)).heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ShareIcon(R.drawable.ic_share_search, null, palette.secondary, 18)
        BasicTextField(value, onChange, modifier = Modifier.weight(1f).testTag(tag), singleLine = true,
            textStyle = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, color = palette.ink), cursorBrush = SolidColor(palette.blue),
            decorationBox = { field -> Box { if (value.isEmpty()) Text(hint, fontSize = 15.sp, lineHeight = 20.sp, color = palette.secondary); field() } })
    }
}

@Composable private fun ShareShortcut(modifier: Modifier, title: String, workspace: String, palette: SharePalette,
                                     enabled: Boolean, tag: String, letter: String?, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .testTag(tag), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(60.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxSize().background(if (letter == null) { if (palette.dark) Color.Transparent else palette.card } else Color(0xFFDCEAFF), CircleShape), contentAlignment = Alignment.Center) {
                if (letter == null) ShareIcon(R.drawable.ic_share_plus, null, palette.blue, 24)
                else Text(letter.uppercase(), fontSize = 24.sp, fontWeight = FontWeight.Medium, color = palette.blue)
            }
            if (letter != null) Box(Modifier.align(Alignment.BottomEnd).size(12.dp).background(palette.card, CircleShape).padding(2.dp)
                .background(Color(0xFF30B779), CircleShape))
        }
        Text(title, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(workspace, fontSize = 10.sp, lineHeight = 14.sp, color = palette.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun ShareDestinationRow(title: String, subtitle: String, palette: SharePalette, tag: String,
                                          enabled: Boolean, first: Boolean = true, last: Boolean = true, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(
        topStart = if (first) 20.dp else 0.dp, topEnd = if (first) 20.dp else 0.dp,
        bottomStart = if (last) 20.dp else 0.dp, bottomEnd = if (last) 20.dp else 0.dp
    )).background(palette.card)) {
    Row(Modifier.fillMaxWidth()
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).testTag(tag).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = palette.secondary, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ShareIcon(R.drawable.ic_share_chevron, null, palette.secondary, 16)
    }
    if (!last) HorizontalDivider(Modifier.padding(start = 16.dp), color = palette.secondary.copy(alpha = .15f))
    }
}

@Composable private fun ShareEmpty(palette: SharePalette) {
    Text(appText("暂无匹配会话"), color = palette.secondary, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
}

/** 只解码小缩略图，不在 UI 线程加载原图；其他文件复用项目的类型图标。 */
@Composable private fun ShareThumbnail(uri: Uri?, size: Int, palette: SharePalette, link: Boolean = false) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (uri == null) return@runCatching null
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                if (options.outWidth < 1 || options.outHeight < 1) return@runCatching null
                var sample = 1
                while (maxOf(options.outWidth, options.outHeight) / sample > 256) sample *= 2
                val decoded = context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                } ?: return@runCatching null
                val matrix = runCatching {
                    context.contentResolver.openInputStream(uri)?.use {
                        val exif = ExifInterface(it)
                        Matrix().apply {
                            if (exif.isFlipped) postScale(-1f, 1f)
                            postRotate(exif.rotationDegrees.toFloat())
                        }
                    }
                }.getOrNull()
                val oriented = if (matrix == null || matrix.isIdentity) decoded else
                    Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                if (oriented !== decoded) decoded.recycle()
                oriented.asImageBitmap()
            }.getOrNull()
        }
    }
    val image = bitmap
    if (image != null) Image(image, null, Modifier.size(size.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
    else if (uri == null && link) Box(Modifier.size(size.dp).background(palette.card, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        ShareIcon(R.drawable.ic_link, null, palette.blue, 24)
    } else FileTypeIcon(FileIconCatalog.resolve(uri?.lastPathSegment.orEmpty(), null), Modifier.size(size.dp))
}
