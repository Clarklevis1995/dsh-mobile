package com.clarklevis.dsh.android.ui

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clarklevis.dsh.android.AndroidSharedStateHolder
import com.clarklevis.dsh.android.AndroidStorageUsage
import com.clarklevis.dsh.android.CacheStorageCategory
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.android.localization.appTextKey

private val CacheStorageCategory.title: String
    get() = when (this) {
        CacheStorageCategory.CHATS -> appTextKey("聊天记录")
        CacheStorageCategory.IMAGES -> appTextKey("图片")
        CacheStorageCategory.PREVIEWS -> appTextKey("文件预览")
        CacheStorageCategory.TEMPORARY -> appTextKey("临时文件")
        CacheStorageCategory.UPDATES -> appTextKey("安装包")
    }

private val CacheStorageCategory.detail: String
    get() = when (this) {
        CacheStorageCategory.CHATS -> appTextKey("已缓存的聊天内容与搜索索引")
        CacheStorageCategory.IMAGES -> appTextKey("聊天中已下载的图片")
        CacheStorageCategory.PREVIEWS -> appTextKey("已打开的文档、音视频等附件")
        CacheStorageCategory.TEMPORARY -> appTextKey("预览、下载和分享产生的临时副本")
        CacheStorageCategory.UPDATES -> appTextKey("已下载的应用更新文件")
    }

private val CacheStorageCategory.color: Color
    get() = when (this) {
        CacheStorageCategory.CHATS -> Color(0xFF3D7AC7)
        CacheStorageCategory.IMAGES -> Color(0xFF3BA38C)
        CacheStorageCategory.PREVIEWS -> Color(0xFFDBA347)
        CacheStorageCategory.TEMPORARY -> Color(0xFFA182C2)
        CacheStorageCategory.UPDATES -> Color(0xFF82939E)
    }

private val CacheStorageCategory.icon: Int
    get() = when (this) {
        CacheStorageCategory.CHATS -> R.drawable.ic_storage_chat
        CacheStorageCategory.IMAGES -> R.drawable.ic_storage_images
        CacheStorageCategory.PREVIEWS -> R.drawable.ic_storage_document
        CacheStorageCategory.TEMPORARY -> R.drawable.ic_folder_outline
        CacheStorageCategory.UPDATES -> R.drawable.ic_download_arrow
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StorageSpaceScreen(stateHolder: AndroidSharedStateHolder, onBack: () -> Unit) {
    val context = LocalContext.current
    val usage = stateHolder.storageUsage
    val busy = stateHolder.cacheMaintenanceRunning
    var selectedCategory by rememberSaveable { mutableStateOf<CacheStorageCategory?>(null) }
    var sortsBySize by rememberSaveable { mutableStateOf(true) }
    val background = if (isSystemInDarkTheme()) Color.Black else Color(0xFFF2F2F7)
    fun size(bytes: Long): String = Formatter.formatFileSize(context, bytes)
    val goBack = { if (selectedCategory != null) selectedCategory = null else onBack() }
    BackHandler(enabled = selectedCategory != null) { selectedCategory = null }
    LaunchedEffect(stateHolder) { stateHolder.refreshCacheStorage() }
    Scaffold(
        modifier = Modifier.testTag("storage-space-page"),
        containerColor = background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(appText(selectedCategory?.title ?: "存储空间"), style = SettingsTypography.title) },
                navigationIcon = {
                    TopBarCircleButton(
                        R.drawable.ic_back_chevron,
                        appText("返回"),
                        goBack,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                },
                actions = {
                    if (busy) {
                        CircularProgressIndicator(Modifier.padding(16.dp).size(20.dp), strokeWidth = 2.dp)
                    } else if (selectedCategory == null) {
                        TopBarCircleButton(
                            R.drawable.ic_refresh,
                            appText("刷新存储统计"),
                            { stateHolder.refreshCacheStorage() },
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = background)
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val selected = selectedCategory
            if (selected != null) {
                item {
                    StorageSection {
                        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                            StorageCategoryIcon(selected, Modifier.size(56.dp))
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(appText(selected.title), style = SettingsTypography.title)
                                Text(
                                    appText(selected.detail),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = SettingsTypography.description
                                )
                            }
                        }
                        HorizontalDivider(Modifier.padding(start = 20.dp))
                        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(appText("占用空间"), modifier = Modifier.weight(1f), style = SettingsTypography.item)
                            Text(
                                size(usage?.bytes(selected) ?: 0),
                                style = SettingsTypography.item,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                item {
                    StorageSection {
                        val clearLabel = appText("清理%1\$s", appText(selected.title))
                        TextButton(
                            onClick = { stateHolder.refreshCacheStorage(category = selected) },
                            enabled = !busy && (usage?.bytes(selected) ?: 0) > 0,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)
                                .testTag("storage-clear-${selected.name.lowercase()}")
                                .semantics { contentDescription = clearLabel }
                        ) {
                            if (busy) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Text(
                                    appText("清理本机缓存"),
                                    style = SettingsTypography.item,
                                    color = if ((usage?.bytes(selected) ?: 0) > 0) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                    }
                                )
                            }
                        }
                    }
                }
            } else {
                item {
                    StorageSection(appText("磁盘占用")) {
                        if (usage != null) {
                            StorageUsageChart(usage, size(usage.totalBytes))
                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(appText("设备可用空间"), color = MaterialTheme.colorScheme.onSurfaceVariant, style = SettingsTypography.item)
                                Spacer(Modifier.weight(1f))
                                Text(
                                    size(usage.freeBytes),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = SettingsTypography.item
                                )
                            }
                        } else if (busy) {
                            Box(Modifier.fillMaxWidth().heightIn(min = 240.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                }
                if (usage != null) {
                    item {
                        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                                StorageSortMenu(sortsBySize) { sortsBySize = it }
                            }
                            val categories = if (sortsBySize) {
                                CacheStorageCategory.entries.sortedWith(
                                    compareByDescending<CacheStorageCategory> { usage.bytes(it) }.thenBy { it.ordinal }
                                )
                            } else {
                                CacheStorageCategory.entries.sortedBy { appText(it.title) }
                            }
                            StorageSection {
                                categories.forEachIndexed { index, category ->
                                    if (index > 0) HorizontalDivider(Modifier.padding(start = 62.dp))
                                    Row(
                                        Modifier.fillMaxWidth().testTag("storage-category-${category.name.lowercase()}")
                                            .clickable { selectedCategory = category }
                                            .heightIn(min = 76.dp).padding(horizontal = 16.dp, vertical = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        StorageCategoryIcon(category, Modifier.size(32.dp))
                                        Spacer(Modifier.width(14.dp))
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(appText(category.title), style = SettingsTypography.item, maxLines = 1)
                                            Text(
                                                appText(category.detail),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                style = SettingsTypography.description,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                            )
                                        }
                                        Spacer(Modifier.width(12.dp))
                                        Text(
                                            size(usage.bytes(category)),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = SettingsTypography.item
                                        )
                                        Icon(
                                            painterResource(R.drawable.ic_chevron_right),
                                            null,
                                            Modifier.padding(start = 12.dp).size(12.dp),
                                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (usage != null && usage.protectedBytes > 0) {
                item {
                    Text(
                        appText("其中 %1\$s 正在使用，关闭文件后可释放。", size(usage.protectedBytes)),
                        modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = SettingsTypography.description
                    )
                }
            }
            stateHolder.cacheStorageNotice?.let { message ->
                item { Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = SettingsTypography.description) }
            }
            stateHolder.cacheStorageError?.let { message ->
                item { Text(message, color = MaterialTheme.colorScheme.error, style = SettingsTypography.description) }
            }
        }
    }
}

@Composable
private fun StorageCategoryIcon(category: CacheStorageCategory, modifier: Modifier = Modifier) {
    Box(
        modifier.background(
            Brush.verticalGradient(listOf(category.color, category.color.copy(alpha = 0.85f))),
            RoundedCornerShape(9.dp)
        ),
        contentAlignment = Alignment.Center
    ) {
        Icon(painterResource(category.icon), null, Modifier.fillMaxSize(0.56f), tint = Color.White)
    }
}

@Composable
private fun StorageSortMenu(sortsBySize: Boolean, onChange: (Boolean) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, modifier = Modifier.testTag("storage-sort-menu")) {
            Text(appText(if (sortsBySize) "大小" else "名称"), style = SettingsTypography.item)
            Icon(painterResource(R.drawable.ic_chevrons_up_down), null, Modifier.padding(start = 5.dp).size(14.dp))
        }
        DshMenu(expanded, onDismissRequest = { expanded = false }) {
            DshMenuItem(text = { Text(appText("大小")) }, onClick = {
                onChange(true)
                expanded = false
            })
            DshMenuItem(text = { Text(appText("名称")) }, onClick = {
                onChange(false)
                expanded = false
            })
        }
    }
}

@Composable
private fun StorageSection(title: String? = null, content: @Composable () -> Unit) {
    Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (title != null) {
            Text(
                title,
                modifier = Modifier.padding(start = 16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = SettingsTypography.section
            )
        }
        Column(
            Modifier.fillMaxWidth().background(
                if (isSystemInDarkTheme()) Color(0xFF1C1C1E) else Color.White,
                RoundedCornerShape(26.dp)
            )
        ) { content() }
    }
}

@Composable
private fun StorageUsageChart(usage: AndroidStorageUsage, total: String) {
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(200.dp).clearAndSetSemantics { }) {
            val stroke = 28.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            if (usage.totalBytes == 0L) {
                drawCircle(trackColor, radius = (size.minDimension - stroke) / 2, style = Stroke(stroke))
            } else {
                var start = -90f
                CacheStorageCategory.entries.forEach { category ->
                    val sweep = (usage.bytes(category).toDouble() / usage.totalBytes * 360).toFloat()
                    if (sweep > 0) {
                        drawArc(
                            category.color,
                            start + 0.75f,
                            (sweep - 1.5f).coerceAtLeast(0f),
                            false,
                            Offset(inset, inset),
                            arcSize,
                            style = Stroke(stroke)
                        )
                        start += sweep
                    }
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(total, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Text(appText("本机缓存"), color = MaterialTheme.colorScheme.onSurfaceVariant, style = SettingsTypography.description)
        }
    }
}
