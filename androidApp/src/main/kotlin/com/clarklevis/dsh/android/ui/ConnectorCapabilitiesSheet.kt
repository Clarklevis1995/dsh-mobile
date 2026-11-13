package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.android.platform.devicetools.AndroidDeviceConnectors
import com.clarklevis.dsh.android.platform.devicetools.RegisteredConnectorModule

/** 只使用当前网关确认的清单；进入详情后仍随主机权限和本机开关更新。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConnectorCapabilitiesSheet(connectors: AndroidDeviceConnectors, onDismiss: () -> Unit) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val modules = connectors.registeredModules.filter { it.id in connectors.modules }
    val selected = modules.firstOrNull { it.id == selectedId }
    val palette = mapPickerPalette(isSystemInDarkTheme())
    val maximumHeight = adaptiveWindowSize().height * .85f - 31.dp
    CapabilityModalSheet(palette, detail = false, onDismiss = onDismiss) { close ->
        CapabilitySheetPage(connectors, modules, null, palette, maximumHeight,
            onSelect = { selectedId = it }, onDismiss = close)
    }
    if (selected != null) {
        CapabilityModalSheet(palette, detail = true, onDismiss = { selectedId = null }) { close ->
            CapabilitySheetPage(connectors, modules, selected, palette, maximumHeight,
                onSelect = {}, onDismiss = close)
        }
    }
}

/** 每一层均使用独立的原生 Sheet；关闭顶层只移除详情，底层列表继续保留。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CapabilityModalSheet(
    palette: MapPickerPalette,
    detail: Boolean,
    onDismiss: () -> Unit,
    content: @Composable (onClose: () -> Unit) -> Unit
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = state,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        containerColor = palette.background, contentColor = palette.ink, tonalElevation = 0.dp,
        scrimColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = .32f),
        contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top) },
        dragHandle = {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 16.dp)
                .testTag(if (detail) "connector-capabilities-detail-handle" else "connector-capabilities-handle"), contentAlignment = Alignment.Center) {
                Box(Modifier.size(36.dp, 5.dp).background(palette.handle, CircleShape))
            }
        }
    ) {
        DshMenuHost {
            Box(Modifier.fillMaxWidth().testTag(if (detail) "connector-capabilities-detail-sheet" else "connector-capabilities-sheet")) {
                content {
                    scope.launch {
                        state.hide()
                        if (!state.isVisible) onDismiss()
                    }
                }
            }
        }
    }
}

@Composable
private fun CapabilitySheetPage(
    connectors: AndroidDeviceConnectors,
    modules: List<RegisteredConnectorModule>,
    module: RegisteredConnectorModule?,
    palette: MapPickerPalette,
    maximumHeight: androidx.compose.ui.unit.Dp,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Column(Modifier.fillMaxWidth().heightIn(max = maximumHeight)
        .testTag("connector-capabilities-page-${module?.id ?: "list"}")
        .navigationBarsPadding().padding(bottom = if (module == null) 16.dp else 32.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag(if (module == null) "connector-capabilities-header" else "connector-capabilities-detail-header"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(appText(if (module == null) "已登记的能力" else "能力详情"), Modifier.weight(1f),
                style = SettingsTypography.item.copy(fontSize = 21.sp, lineHeight = 29.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium))
            CapabilitySheetButton(R.drawable.ic_close, appText("关闭"), if (module == null) "connector-capabilities-close" else "connector-capabilities-detail-close", palette, onDismiss)
        }
        if (module == null) Text(
            appText("Android · %1\$s 个连接器 · %2\$s 项能力", modules.size, modules.sumOf { it.tools.size }),
            Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 18.dp),
            style = SettingsTypography.description.copy(fontSize = 14.sp, lineHeight = 20.sp), color = palette.secondary
        )
        key(module?.id) {
            val scroll = rememberScrollState()
            val boundary = remember(scroll) {
                object : NestedScrollConnection {
                    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) = Offset(0f, available.y)
                    override suspend fun onPreFling(available: Velocity) =
                        if ((available.y < 0 && !scroll.canScrollForward) || (available.y > 0 && !scroll.canScrollBackward)) Velocity(0f, available.y) else Velocity.Zero
                    override suspend fun onPostFling(consumed: Velocity, available: Velocity) = Velocity(0f, available.y)
                }
            }
            Column(Modifier.weight(1f, fill = false).nestedScroll(boundary).verticalScroll(scroll)
                .padding(horizontal = 20.dp).testTag(if (module == null) "connector-capabilities-list" else "connector-capabilities-detail-list")) {
                if (module != null) {
                    Spacer(Modifier.height(16.dp))
                    Surface(shape = RoundedCornerShape(20.dp), color = palette.row, contentColor = palette.ink) {
                        CapabilityModuleRow(module, connectors, palette, detail = true)
                    }
                    Text(appText("已登记的控制能力"), Modifier.padding(start = 4.dp, top = 14.dp, bottom = 12.dp),
                        style = SettingsTypography.description.copy(fontSize = 14.sp, lineHeight = 20.sp), color = palette.secondary)
                    Surface(shape = RoundedCornerShape(20.dp), color = palette.row, contentColor = palette.ink) {
                        Column {
                            if (module.tools.isEmpty()) Text(appText("网关未接入该能力"), Modifier.padding(16.dp), color = palette.secondary)
                            val status = connectorModuleStatus(module, connectors)
                            module.tools.forEachIndexed { index, tool ->
                                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = .5.dp, color = palette.separator)
                                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 12.dp)
                                    .testTag("connector-capability-$tool").semantics { stateDescription = status }, verticalAlignment = Alignment.CenterVertically) {
                                    Text(connectorCapabilityTitle(tool), Modifier.weight(1f),
                                        style = SettingsTypography.item.copy(fontSize = 15.sp, lineHeight = 21.sp))
                                    Spacer(Modifier.width(12.dp))
                                    if (connectorModuleAllowed(module, connectors)) Icon(painterResource(R.drawable.ic_menu_check), status, Modifier.size(17.dp), tint = DshColors.Success)
                                    else Text("−", color = palette.secondary)
                                }
                            }
                        }
                    }
                } else {
                    Surface(shape = RoundedCornerShape(20.dp), color = palette.row, contentColor = palette.ink) {
                        Column {
                            modules.forEachIndexed { index, module ->
                                if (index > 0) HorizontalDivider(Modifier.padding(start = 72.dp), thickness = .5.dp, color = palette.separator)
                                CapabilityModuleRow(module, connectors, palette, detail = false, onClick = { onSelect(module.id) })
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun connectorModuleAllowed(module: RegisteredConnectorModule, connectors: AndroidDeviceConnectors) =
    module.enabled && module.available && connectors.available(module.id) && connectors.enabled[module.id] == true &&
        connectors.hostPermissions[module.id] == true && module.tools.isNotEmpty()

internal fun connectorModuleStatus(module: RegisteredConnectorModule, connectors: AndroidDeviceConnectors) = appText(when {
    !connectors.supported(module.id) -> "设备不支持"
    !module.available || !connectors.available(module.id) -> "系统未授权"
    !module.enabled || connectors.enabled[module.id] != true -> "手机未开启"
    connectors.hostPermissions[module.id] != true -> "主机未允许"
    module.tools.isEmpty() -> "网关未接入"
    else -> "已允许"
})

@Composable
private fun CapabilityModuleRow(module: RegisteredConnectorModule, connectors: AndroidDeviceConnectors, palette: MapPickerPalette,
    detail: Boolean, onClick: () -> Unit = {}) {
    val interactive = if (detail) Modifier else Modifier.clickable(role = Role.Button, onClick = onClick).testTag("connector-registered-module-${module.id}")
    Row(Modifier.fillMaxWidth().then(interactive).heightIn(min = if (detail) 80.dp else 66.dp)
        .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ConnectorIcon(module.id, if (detail) 48.dp else 44.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(if (detail && module.id == "music") appText("本机音乐") else connectorModuleTitle(module.id), style = SettingsTypography.item)
            Text(appText("%1\$s 项能力", module.tools.size) + if (detail) " · " + appText("网关已确认") else "",
                style = SettingsTypography.description.copy(fontSize = 14.sp, lineHeight = 20.sp), color = palette.secondary)
        }
        Text(connectorModuleStatus(module, connectors), style = SettingsTypography.description.copy(fontSize = 14.sp, lineHeight = 20.sp), color = palette.secondary)
        if (!detail) Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(18.dp), tint = palette.secondary)
    }
}

@Composable
private fun CapabilitySheetButton(icon: Int, label: String, tag: String, palette: MapPickerPalette, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clickable(role = Role.Button, onClickLabel = label, onClick = onClick).testTag(tag), contentAlignment = Alignment.Center) {
        Box(Modifier.size(32.dp).background(palette.close, CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), label, Modifier.size(18.dp), tint = palette.secondary)
        }
    }
}
