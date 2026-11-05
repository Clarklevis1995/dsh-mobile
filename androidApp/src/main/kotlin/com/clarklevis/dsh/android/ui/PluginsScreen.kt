package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.android.AndroidSharedStateHolder
import com.clarklevis.dsh.android.MobileOfficialPlugin
import com.clarklevis.dsh.android.MobilePluginBundle
import com.clarklevis.dsh.android.MobilePluginEntry
import com.clarklevis.dsh.android.MobilePluginRow
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.shared.gateway.GatewayConnectionState

@Composable
internal fun PluginsScreen(
    stateHolder: AndroidSharedStateHolder,
    onBack: () -> Unit,
    onOpenBundle: (String) -> Unit,
    onOpenOfficial: (String) -> Unit
) {
    var confirmGatewayDisable by remember { mutableStateOf(false) }
    val connection = stateHolder.gatewayState.connection
    LaunchedEffect(connection) {
        if (connection == GatewayConnectionState.CONNECTED || stateHolder.pluginCatalog == null) {
            stateHolder.refreshPlugins()
        }
    }
    PluginPage(appText("插件"), onBack, onRefresh = stateHolder::refreshPlugins) {
        PluginCatalogContent(stateHolder, onOpenBundle, onOpenOfficial) {
            confirmGatewayDisable = true
        }
    }
    if (confirmGatewayDisable) PluginGatewayDisableDialog(stateHolder) {
        confirmGatewayDisable = false
    }
}

@Composable
internal fun PluginBundleDetailScreen(
    stateHolder: AndroidSharedStateHolder,
    bundleName: String,
    onBack: () -> Unit
) {
    var confirmGatewayDisable by remember { mutableStateOf(false) }
    LaunchedEffect(bundleName) {
        if (stateHolder.pluginCatalog == null) stateHolder.refreshPlugins()
        stateHolder.loadPluginInformation(bundleName)
    }
    val bundle = stateHolder.pluginCatalog?.bundles?.firstOrNull { it.name == bundleName }
    PluginPage(appText("插件详情"), onBack) {
        if (bundle != null) {
            PluginBundleDetail(stateHolder, bundle) { confirmGatewayDisable = true }
        } else {
            PluginDetailUnavailable(stateHolder)
        }
    }
    if (confirmGatewayDisable) PluginGatewayDisableDialog(stateHolder) {
        confirmGatewayDisable = false
    }
}

@Composable
internal fun OfficialPluginDetailScreen(
    stateHolder: AndroidSharedStateHolder,
    pluginId: String,
    onBack: () -> Unit
) {
    LaunchedEffect(pluginId) {
        if (stateHolder.pluginCatalog == null) stateHolder.refreshPlugins()
    }
    val official = stateHolder.pluginCatalog?.officialItems?.firstOrNull { it.id == pluginId }
    PluginPage(appText("插件详情"), onBack) {
        if (official != null) OfficialPluginDetail(official)
        else PluginDetailUnavailable(stateHolder)
    }
}

@Composable
private fun PluginPage(
    title: String,
    onBack: () -> Unit,
    onRefresh: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .statusBarsPadding().navigationBarsPadding()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TopBarCircleButton(R.drawable.ic_back_chevron, appText("返回"), onBack)
            Spacer(Modifier.weight(1f))
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (onRefresh != null) {
                TopBarCircleButton(R.drawable.ic_refresh, appText("刷新插件"), onRefresh)
            } else {
                Spacer(Modifier.size(46.dp))
            }
        }
        Box(Modifier.weight(1f).adaptiveContentWidth().testTag("plugin-content")) { content() }
    }
}

@Composable
private fun PluginDetailUnavailable(stateHolder: AndroidSharedStateHolder) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (stateHolder.pluginsLoading) CircularProgressIndicator()
        else Text(appText("插件详情暂不可用"), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PluginGatewayDisableDialog(stateHolder: AndroidSharedStateHolder, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(appText("停用移动网关？")) },
        text = { DshMenuHost { Text(appText("停用后手机会断开连接。只能从 DSH Web 或 CLI 重新开启此插件。"))  } },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                stateHolder.setPluginEnabled("dsh-plugin-mobile-gateway", false)
            }) { Text(appText("停用"), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(appText("取消")) } }
    )
}

@Composable
private fun PluginCatalogContent(
    stateHolder: AndroidSharedStateHolder,
    onBundle: (String) -> Unit,
    onOfficial: (String) -> Unit,
    onGatewayDisable: () -> Unit
) {
    val catalog = stateHolder.pluginCatalog
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = AdaptiveLayout.pluginColumnCount(maxWidth.value, LocalDensity.current.fontScale)
        LazyVerticalGrid(columns = GridCells.Fixed(columns), modifier = Modifier.fillMaxSize().testTag("plugin-grid"), contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp
        ), horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) { Text(appText("安装、启用和配置插件"), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (stateHolder.gatewayState.connection != GatewayConnectionState.CONNECTED) {
                item(span = { GridItemSpan(maxLineSpan) }) { Text(appText("网关已断开，插件状态待重新连接确认"), color = MaterialTheme.colorScheme.tertiary) }
            }
            stateHolder.pluginNotice?.let { notice ->
                item(span = { GridItemSpan(maxLineSpan) }) { Text(notice, color = MaterialTheme.colorScheme.tertiary) }
            }
            stateHolder.pluginsError?.let { error ->
                item(span = { GridItemSpan(maxLineSpan) }) { Text(error, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (stateHolder.pluginsLoading && catalog == null) {
                item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.fillMaxWidth().padding(top = 90.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                } }
            }
            if (catalog?.managementAvailable == true) {
                item(span = { GridItemSpan(maxLineSpan) }) { PluginSectionHeader(appText("官方"), catalog.officialBundles.size + catalog.officialItems.size) }
                items(catalog.officialBundles, key = { "bundle:${it.name}" }) { bundle ->
                    PluginBundleRow(stateHolder, bundle, onBundle, onGatewayDisable)
                }
                items(catalog.officialItems, key = { "official:${it.id}" }) { item ->
                    PluginLine(null, item.id, item.title, item.description,
                        Modifier.fillMaxWidth().clickable { onOfficial(item.id) }, experimental = false)
                }
                item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(10.dp)); PluginSectionHeader(appText("已安装"), catalog.installedBundles.size) }
                items(catalog.installedBundles, key = { "bundle:${it.name}" }) { bundle ->
                    PluginBundleRow(stateHolder, bundle, onBundle, onGatewayDisable)
                }
            }
        }
    }
}

@Composable
private fun PluginSectionHeader(title: String, count: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text("$count", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PluginBundleRow(
    stateHolder: AndroidSharedStateHolder,
    bundle: MobilePluginBundle,
    onBundle: (String) -> Unit,
    onGatewayDisable: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 68.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PluginLine(bundle.icon, bundle.name, bundle.title, bundle.description,
            Modifier.weight(1f).clickable { onBundle(bundle.name) }, experimental = bundle.experimental)
        if (stateHolder.pluginPendingName == bundle.name) CircularProgressIndicator(Modifier.size(18.dp))
        Switch(bundle.enabled, onCheckedChange = { enabled ->
            if (bundle.name == "dsh-plugin-mobile-gateway" && !enabled) onGatewayDisable()
            else stateHolder.setPluginEnabled(bundle.name, enabled)
        }, colors = dshSwitchColors(stateHolder.pluginPendingName != null &&
            stateHolder.gatewayState.connection == GatewayConnectionState.CONNECTED && bundle.readOnlyReason == null),
            enabled = stateHolder.gatewayState.connection == GatewayConnectionState.CONNECTED &&
            bundle.readOnlyReason == null && stateHolder.pluginPendingName == null)
    }
}

@Composable
private fun PluginBundleDetail(
    stateHolder: AndroidSharedStateHolder,
    bundle: MobilePluginBundle,
    onGatewayDisable: () -> Unit
) {
    var selectedEntry by remember { mutableStateOf<MobilePluginRow?>(null) }
    val entries = bundle.rows.map { row ->
        val id = row["entryId"]?.stringValue
        MobilePluginRow(row, stateHolder.pluginCatalog?.plugins?.firstOrNull { it.entryId == id })
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PluginIcon(bundle.icon, bundle.name, Modifier.size(60.dp), artworkOffsetY = 4.dp)
                Spacer(Modifier.weight(1f))
                Switch(bundle.enabled, onCheckedChange = { enabled ->
                    if (bundle.name == "dsh-plugin-mobile-gateway" && !enabled) onGatewayDisable()
                    else stateHolder.setPluginEnabled(bundle.name, enabled)
                }, colors = dshSwitchColors(stateHolder.pluginPendingName != null &&
                    stateHolder.gatewayState.connection == GatewayConnectionState.CONNECTED && bundle.readOnlyReason == null),
                    enabled = stateHolder.gatewayState.connection == GatewayConnectionState.CONNECTED &&
                    bundle.readOnlyReason == null && stateHolder.pluginPendingName == null)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(bundle.title, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                bundle.version?.let { Text("v$it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(bundle.name, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (bundle.description.isNotBlank()) Text(bundle.description)
                bundle.readOnlyReason?.let { Text(it, color = MaterialTheme.colorScheme.tertiary) }
                bundle.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                stateHolder.pluginNotice?.let { Text(it, color = MaterialTheme.colorScheme.tertiary) }
                if (stateHolder.gatewayState.connection != GatewayConnectionState.CONNECTED) {
                    Text(appText("网关已断开，插件状态待重新连接确认"), color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
        if ("plugin-updates-v1" in stateHolder.gatewayState.capabilities) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (bundle.raw["updatable"]?.booleanValue == true) {
                        TextButton(onClick = { stateHolder.loadPluginInformation(bundle.name, versions = true) },
                            enabled = stateHolder.pluginPendingName == null) { Text("检查更新") }
                    }
                    stateHolder.pluginVersions?.let { result ->
                        if (result["status"]?.stringValue != "listed") Text(result.toString())
                        result["versions"]?.arrayValue.orEmpty().forEach { entry ->
                            val version = entry["version"]?.stringValue ?: entry.stringValue
                            if (version != null) TextButton(onClick = { stateHolder.updatePlugin(bundle.name, version) },
                                enabled = stateHolder.pluginPendingName == null && entry["selectable"]?.booleanValue != false && entry["compatible"]?.booleanValue != false) {
                                Text("更新到 $version")
                            }
                        }
                    }
                    stateHolder.pluginUpdateStep?.let { Text("更新进度 · $it") }
                    stateHolder.pluginUpdateResult?.let { Text(it.toJsonElement().toString()) }
                    if (stateHolder.pluginUpdateLogs.isNotEmpty()) Text(stateHolder.pluginUpdateLogs.joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    stateHolder.pluginDetail?.get("markdown")?.stringValue?.let { markdown ->
                        // 使用原生 Markdown 渲染，图片保留替代文字，避免未鉴权的远端读取。
                        DshMarkdownText(markdown.replace(Regex("!\\[([^]]*)]\\([^)]*\\)"), "[图片 · $1]"))
                    }
                }
            }
        }
        item { Text(appText("包含的组件  共 %1\$s 个 · %2\$s 运行中", entries.size, entries.count { it.enabled && it.phase == "active" }),
            fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
        items(entries, key = { it.rowId }) { entry ->
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable { selectedEntry = entry },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PluginIcon(entry.icon, "subagent", Modifier.size(44.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(entry.name, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("● ${entry.statusText}", fontSize = 12.sp,
                        color = when (entry.phase.takeIf { entry.enabled }) {
                            "active" -> Color(0xFF22C55E)
                            "failed" -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        })
                }
                entry.entryId?.let { entryId ->
                    Switch(entry.enabled, onCheckedChange = {
                        stateHolder.setPluginEnabled(bundle.name, it, entryId)
                    }, colors = dshSwitchColors(stateHolder.pluginPendingName != null &&
                        stateHolder.gatewayState.connection == GatewayConnectionState.CONNECTED && entry.readOnlyReason == null),
                        enabled = stateHolder.gatewayState.connection == GatewayConnectionState.CONNECTED &&
                        entry.readOnlyReason == null && stateHolder.pluginPendingName == null)
                }
                IconButton(onClick = { selectedEntry = entry }, modifier = Modifier.size(40.dp)) {
                    Icon(painterResource(R.drawable.ic_chevron_right), appText("查看%1\$s详情", entry.name),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    selectedEntry?.let { entry ->
        PluginComponentSheet(entry) { selectedEntry = null }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PluginComponentSheet(entry: MobilePluginRow, onDismiss: () -> Unit) {
    ModalBottomSheet(
        modifier = Modifier.adaptiveSheetBounds(),
        sheetMaxWidth = adaptiveSheetWidth(),
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        DshMenuColumnHost {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState())
                    .padding(start = 22.dp, end = 22.dp, top = 8.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    PluginIcon(entry.icon, "subagent", Modifier.size(56.dp))
                    Text(entry.name, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                }
                Text("● ${entry.statusText}", color = when (entry.phase.takeIf { entry.enabled }) {
                    "active" -> Color(0xFF22C55E)
                    "failed" -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                })
                if (entry.description.isNotBlank()) Text(entry.description)
                ComponentField(appText("组件 ID"), entry.rowId)
                if (entry.moduleName.isNotBlank()) ComponentField(appText("模块"), entry.moduleName)
                entry.entryId?.let { ComponentField(appText("运行条目 ID"), it) }
                entry.readOnlyReason?.let { ComponentField(appText("不可修改原因"), it) }
            }
        }
    }
}

@Composable
private fun ComponentField(title: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun OfficialPluginDetail(item: MobileOfficialPlugin) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        PluginIcon(null, item.id, Modifier.size(60.dp))
        Text(item.title, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(item.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(appText("配置项"), fontWeight = FontWeight.SemiBold)
        item.namespaces.forEach { Text(it, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun PluginLine(
    icon: String?, fallback: String, title: String, subtitle: String,
    modifier: Modifier, experimental: Boolean
) {
    Row(modifier.heightIn(min = 68.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PluginIcon(icon, fallback, Modifier.size(50.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f, fill = false), fontSize = 17.sp,
                    lineHeight = 20.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (experimental) ExperimentalPluginBadge()
            }
            if (subtitle.isNotBlank()) Text(subtitle, fontSize = 13.sp, lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ExperimentalPluginBadge() {
    val dark = isSystemInDarkTheme()
    Text(appText("实验性"), modifier = Modifier.background(
        if (dark) Color(0xFF233451) else Color(0xFFEAF0FF), RoundedCornerShape(100.dp)
    ).padding(horizontal = 8.dp, vertical = 3.dp), fontSize = 12.sp,
        fontWeight = FontWeight.Medium, color = if (dark) Color(0xFF8DADFF) else Color(0xFF356FF0))
}

@Composable
internal fun PluginIcon(
    dataURL: String?,
    fallback: String,
    modifier: Modifier = Modifier,
    artworkOffsetY: Dp = 0.dp
) {
    val context = LocalContext.current
    val cache = remember(context) { PluginIconImageCache.shared(context) }
    var bitmap by remember(dataURL, fallback) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(cache, dataURL, fallback) {
        bitmap = cache.load(dataURL, fallback)?.asImageBitmap()
    }
    val image = bitmap
    Box(modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(13.dp))
        .padding(8.dp), contentAlignment = Alignment.Center) {
        when {
            image != null -> Image(image, null, Modifier.fillMaxSize().offset(y = artworkOffsetY))
            else -> {
                val resource = when (fallback) {
                    "shell" -> R.drawable.ic_dsh_bash
                    "web-search" -> R.drawable.ic_dsh_search
                    "agent-loop" -> R.drawable.ic_drawer_plugin
                    "subagent" -> R.drawable.ic_drawer_plugin
                    else -> R.drawable.ic_drawer_plugin
                }
                Icon(painterResource(resource), null, tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(27.dp).offset(y = artworkOffsetY))
            }
        }
    }
}
