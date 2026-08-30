package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.clarklevis.dsh.android.AndroidSharedStateHolder
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.shared.gateway.GatewayConnectionState
import com.clarklevis.dsh.shared.protocol.GatewayAgentPreset
import com.clarklevis.dsh.shared.protocol.GatewayModelGroup
import com.clarklevis.dsh.shared.protocol.GatewayModelItem
import com.clarklevis.dsh.shared.protocol.GatewayReasoningEffort
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    stateHolder: AndroidSharedStateHolder,
    onBack: () -> Unit,
    onOpenAgentPresets: () -> Unit,
    onOpenDefaultModel: () -> Unit
) {
    var showPermissionPicker by remember { mutableStateOf(false) }
    var pendingPermission by remember { mutableStateOf<String?>(null) }
    val dark = isSystemInDarkTheme()
    val pageBackground = if (dark) Color(0xFF0D1118) else Color(0xFFF1F1F6)
    LaunchedEffect(stateHolder.gatewayState.connection) { stateHolder.refreshProductState() }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("设置", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    TopBarCircleButton(
                        iconRes = R.drawable.ic_back_chevron,
                        description = "返回",
                        onClick = onBack,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = pageBackground)
            )
        },
        containerColor = pageBackground
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Column(
                    Modifier.fillMaxWidth().widthIn(max = 720.dp)
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp)
                ) {
                    SettingsSection(
                        title = "新会话默认配置",
                        footer = "与 WebUI 使用同一份部署级设置。修改只影响之后新建的会话，运行中的会话保持启动时的配置。"
                    ) {
                        SettingsValueRow(
                            title = "Agent 预设",
                            value = selectedPreset(stateHolder),
                            enabled = isConnected(stateHolder),
                            isLoading = stateHolder.defaultConfigurationLoadingKinds.any {
                                it == "defaults" || it == "agent-presets"
                            }
                        ) {
                            onOpenAgentPresets()
                        }
                        SettingsDivider()
                        SettingsValueRow(
                            title = "默认模型",
                            value = selectedModel(stateHolder),
                            enabled = isConnected(stateHolder),
                            isLoading = stateHolder.defaultConfigurationLoadingKinds.any {
                                it == "default-model" || it == "save-default-model"
                            }
                        ) {
                            onOpenDefaultModel()
                        }
                        SettingsDivider()
                        PermissionSettingsRow(
                            value = permissionName(stateHolder.snapshot.permissionDefault),
                            selectedPermission = stateHolder.snapshot.permissionDefault,
                            expanded = showPermissionPicker,
                            enabled = isConnected(stateHolder) &&
                                "set-default" !in stateHolder.defaultConfigurationLoadingKinds,
                            onExpandedChange = { showPermissionPicker = it },
                            onPermissionSelected = { pendingPermission = it }
                        )
                    }
                    SettingsSection("Mobile Gateway") {
                        GatewayEndpointRow(stateHolder)
                        SettingsDivider()
                        GatewayStatusRow(stateHolder)
                        SettingsDivider()
                        SettingsActionRow(if (isConnected(stateHolder)) "断开连接" else "连接") {
                            if (isConnected(stateHolder)) stateHolder.disconnect() else stateHolder.connect()
                        }
                        SettingsDivider()
                        SettingsActionRow("Ping 网关", enabled = isConnected(stateHolder), onClick = stateHolder::pingGateway)
                    }
                    stateHolder.snapshot.hostSnapshot?.let { host ->
                        SettingsSection("DSH Host") {
                            SettingsValueRow("版本", host.version ?: "—")
                            SettingsDivider()
                            SettingsValueRow("Provider", host.provider ?: "—")
                            SettingsDivider()
                            SettingsValueRow("Model", host.model ?: "—")
                            SettingsDivider()
                            SettingsValueRow("已连接会话", "${host.attachedSessions ?: 0}")
                            host.cwd?.let { cwd ->
                                SettingsDivider()
                                SettingsValueRow("cwd", cwd)
                            }
                        }
                    }
                    SettingsSection(
                        title = "外观",
                        footer = "语言设置将在重新启动应用后生效。"
                    ) {
                        SettingsValueRow("界面", "跟随系统")
                        SettingsDivider()
                        SettingsValueRow("语言", Locale.getDefault().displayLanguage.ifBlank { "简体中文" })
                    }
                    stateHolder.platformError?.let { error ->
                        Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    }
                    Spacer(Modifier.padding(bottom = 20.dp))
                }
            }
        }
    }
    pendingPermission?.let { value ->
        SettingsConfirmationDialog(
            title = "修改全局默认权限？",
            message = "将新会话的默认权限改为“${permissionName(value)}”。这会更新部署级设置，并同步影响 WebUI。",
            confirmLabel = "确认修改",
            onDismiss = { pendingPermission = null },
            onConfirm = {
                stateHolder.setDefault("permission", value)
                pendingPermission = null
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentPresetSelectionScreen(
    stateHolder: AndroidSharedStateHolder,
    onBack: () -> Unit
) {
    var pendingPreset by remember { mutableStateOf<GatewayAgentPreset?>(null) }
    val presets = stateHolder.snapshot.agentPresets
    val isLoading = "agent-presets" in stateHolder.defaultConfigurationLoadingKinds && presets.isEmpty()
    val isBusy = "set-default" in stateHolder.defaultConfigurationLoadingKinds
    LaunchedEffect(Unit) {
        if (presets.isEmpty()) stateHolder.refreshDefaultConfiguration()
    }

    SettingsSelectionScaffold(title = "Agent 预设", onBack = onBack) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                SettingsSelectionHeader(
                    title = "Agent 预设",
                    description = "预设决定 Agent 使用的工具、提示词与能力。选择后只对新建会话生效。"
                )
            }
            when {
                isLoading -> item { SettingsLoadingState("正在读取 Agent 预设…") }
                presets.isEmpty() -> item {
                    SettingsEmptyState(
                        title = "没有可用的 Agent 预设",
                        description = "请确认 Mobile Gateway 已升级到 v0.1.11 并保持连接。"
                    )
                }
                else -> items(presets, key = GatewayAgentPreset::id) { preset ->
                    AgentPresetCard(
                        preset = preset,
                        selected = preset.id == stateHolder.snapshot.agentPresetDefault,
                        busy = isBusy,
                        onClick = { pendingPreset = preset }
                    )
                }
            }
            if (stateHolder.agentPresetsAuthorable || stateHolder.agentPresetsHasDocument) {
                item {
                    Text(
                        text = "ⓘ  ${presetCapabilityText(stateHolder)}",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.52f),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }

    pendingPreset?.let { preset ->
        SettingsConfirmationDialog(
            title = "设为全局默认预设？",
            message = "将“${agentPresetDisplayName(preset.id, preset.name)}”设为新会话的默认 Agent 预设。这会更新部署级设置，并同步影响 WebUI。",
            confirmLabel = "设为默认",
            onDismiss = { pendingPreset = null },
            onConfirm = {
                stateHolder.setDefault("agent-preset", preset.id)
                pendingPreset = null
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DefaultModelSelectionScreen(
    stateHolder: AndroidSharedStateHolder,
    onBack: () -> Unit
) {
    var pendingChange by remember { mutableStateOf<PendingDefaultModelChange?>(null) }
    val groups = stateHolder.snapshot.modelCatalog?.groups.orEmpty()
    val isLoading = "models" in stateHolder.defaultConfigurationLoadingKinds && groups.isEmpty()
    val isBusy = "save-default-model" in stateHolder.defaultConfigurationLoadingKinds
    LaunchedEffect(Unit) { stateHolder.ensureDefaultModelConfiguration() }

    SettingsSelectionScaffold(title = "默认模型", onBack = onBack) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                SettingsSelectionHeader(
                    title = "默认模型",
                    description = "为之后新建的会话设置默认模型与思考等级。这会更新部署级设置，同步影响 WebUI，运行中的会话保持启动时的配置。"
                )
            }
            when {
                isLoading -> item { SettingsLoadingState("正在读取模型列表…") }
                groups.isEmpty() -> item {
                    SettingsEmptyState(
                        title = "暂无可用模型",
                        description = "未能读取到模型列表，请检查网关连接后重试。"
                    )
                }
                else -> groups.forEach { group ->
                    item(key = "group-${group.id}") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                group.name,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.52f)
                            )
                            group.models.forEach { model ->
                                val selected = stateHolder.snapshot.defaultModel?.let {
                                    it.provider == group.id && it.model == model.id
                                } == true
                                DefaultModelCard(
                                    model = model,
                                    selected = selected,
                                    currentEffort = stateHolder.snapshot.defaultModel?.reasoningEffort,
                                    busy = isBusy,
                                    onSelectModel = {
                                        pendingChange = pendingDefaultModelChange(
                                            group = group,
                                            model = model,
                                            currentEffort = stateHolder.snapshot.defaultModel?.reasoningEffort
                                        )
                                    },
                                    onSelectEffort = { effort ->
                                        pendingChange = PendingDefaultModelChange(
                                            provider = group.id,
                                            providerName = group.name,
                                            model = model.id,
                                            modelName = model.name,
                                            reasoningEffort = effort.id,
                                            effortName = effort.name
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    pendingChange?.let { change ->
        SettingsConfirmationDialog(
            title = "修改默认模型？",
            message = "将新会话的默认模型改为“${change.summary}”。这会更新部署级设置，并同步影响 WebUI。",
            confirmLabel = "确认修改",
            onDismiss = { pendingChange = null },
            onConfirm = {
                stateHolder.saveDefaultModel(change.provider, change.model, change.reasoningEffort)
                pendingChange = null
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSelectionScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    val dark = isSystemInDarkTheme()
    val background = if (dark) Color(0xFF0D1118) else Color(0xFFF1F1F6)
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    TopBarCircleButton(
                        iconRes = R.drawable.ic_back_chevron,
                        description = "返回",
                        onClick = onBack,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = background)
            )
        },
        containerColor = background
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) { content() }
    }
}

@Composable
private fun SettingsSelectionHeader(title: String, description: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            description,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.52f),
            fontSize = 14.sp,
            lineHeight = 20.sp
        )
    }
}

@Composable
private fun SettingsLoadingState(label: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(label, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.52f))
    }
}

@Composable
private fun SettingsEmptyState(title: String, description: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(
            description,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.52f),
            fontSize = 13.sp,
            lineHeight = 18.sp
        )
    }
}

@Composable
private fun AgentPresetCard(
    preset: GatewayAgentPreset,
    selected: Boolean,
    busy: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    val enabled = !selected && !busy && preset.broken != true
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
                shape = shape
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                agentPresetDisplayName(preset.id, preset.name),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                preset.id,
                modifier = Modifier.background(
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f),
                    RoundedCornerShape(50)
                ).padding(horizontal = 7.dp, vertical = 3.dp),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f)
            )
            Spacer(Modifier.weight(1f))
            if (selected) CurrentDefaultBadge()
        }
        Text(
            agentPresetDisplayDescription(preset.id, preset.description),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
            fontSize = 14.sp,
            lineHeight = 19.sp
        )
        if (preset.broken == true) {
            Text(
                "⚠ 该预设存在配置错误，暂时不能设为默认值",
                color = Color(0xFFF08A16),
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun DefaultModelCard(
    model: GatewayModelItem,
    selected: Boolean,
    currentEffort: String?,
    busy: Boolean,
    onSelectModel: () -> Unit,
    onSelectEffort: (GatewayReasoningEffort) -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
                shape = shape
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(enabled = !busy, onClick = onSelectModel),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(model.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (selected) CurrentDefaultBadge()
        }
        val efforts = model.reasoning?.efforts.orEmpty()
        if (efforts.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "思考等级",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
                    fontSize = 12.sp
                )
                efforts.forEach { effort ->
                    val active = selected && effort.id == currentEffort
                    Text(
                        effort.name,
                        modifier = Modifier.clip(RoundedCornerShape(50))
                            .background(
                                if (active) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                            )
                            .clickable(enabled = !busy) { onSelectEffort(effort) }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        color = if (active) MaterialTheme.colorScheme.surface
                        else MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun CurrentDefaultBadge() {
    Text(
        "当前使用",
        modifier = Modifier.background(Color.Black, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        color = Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsConfirmationDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().widthIn(max = 340.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
            tonalElevation = 8.dp,
            shadowElevation = 18.dp
        ) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    message,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.56f),
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SettingsDialogButton("取消", Modifier.weight(1f), onDismiss)
                    SettingsDialogButton(confirmLabel, Modifier.weight(1f), onConfirm)
                }
            }
        }
    }
}

@Composable
private fun SettingsDialogButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier.height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

internal data class PendingDefaultModelChange(
    val provider: String,
    val providerName: String,
    val model: String,
    val modelName: String,
    val reasoningEffort: String?,
    val effortName: String?
) {
    val summary: String
        get() = listOfNotNull(providerName, modelName, effortName).joinToString(" · ")
}

internal fun pendingDefaultModelChange(
    group: GatewayModelGroup,
    model: GatewayModelItem,
    currentEffort: String?
): PendingDefaultModelChange {
    val efforts = model.reasoning?.efforts.orEmpty()
    val retainedEffort = currentEffort?.takeIf { current -> efforts.any { it.id == current } }
    val effortId = retainedEffort ?: model.reasoning?.defaultEffort
    return PendingDefaultModelChange(
        provider = group.id,
        providerName = group.name,
        model = model.id,
        modelName = model.name,
        reasoningEffort = effortId,
        effortName = effortId?.let { id -> efforts.firstOrNull { it.id == id }?.name }
    )
}

private fun presetCapabilityText(holder: AndroidSharedStateHolder): String = when {
    holder.agentPresetsAuthorable && holder.agentPresetsHasDocument ->
        "服务端支持编写自定义预设，并提供预设配置文档。"
    holder.agentPresetsAuthorable -> "服务端支持编写自定义 Agent 预设。"
    holder.agentPresetsHasDocument -> "服务端提供 Agent 预设配置文档。"
    else -> ""
}

@Composable
private fun SettingsSection(
    title: String,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            modifier = Modifier.padding(start = 20.dp),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.48f)
        )
        Column(
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(22.dp))
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) { content() }
        footer?.let {
            Text(
                it,
                modifier = Modifier.padding(horizontal = 20.dp),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.52f)
            )
        }
    }
}

@Composable
private fun SettingsValueRow(
    title: String,
    value: String,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val rowAlpha = if (enabled) 1f else 0.4f
    Row(
        Modifier.fillMaxWidth().heightIn(min = 54.dp)
            .clickable(enabled = enabled && onClick != null) { onClick?.invoke() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = MaterialTheme.colorScheme.onSurface.copy(alpha = rowAlpha), fontSize = 16.sp)
        Spacer(Modifier.weight(1f).padding(horizontal = 6.dp))
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)
            )
        } else {
            Text(
                value,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.52f else 0.3f),
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (onClick != null) {
            Text(
                "  ›",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.30f else 0.15f),
                fontSize = 20.sp
            )
        }
    }
}

@Composable
private fun PermissionSettingsRow(
    value: String,
    selectedPermission: String?,
    expanded: Boolean,
    enabled: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onPermissionSelected: (String) -> Unit
) {
    Box {
        SettingsValueRow(
            title = "权限",
            value = value,
            enabled = enabled,
            onClick = { onExpandedChange(true) }
        )
        if (expanded) {
            val density = LocalDensity.current
            Popup(
                alignment = Alignment.TopStart,
                offset = with(density) {
                    IntOffset(
                        x = 22.dp.roundToPx(),
                        y = (-20).dp.roundToPx()
                    )
                },
                onDismissRequest = { onExpandedChange(false) },
                properties = PopupProperties(
                    focusable = true,
                    clippingEnabled = false
                )
            ) {
                Box(Modifier.padding(20.dp)) {
                    Surface(
                        modifier = Modifier.width(230.dp),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
                        tonalElevation = 0.dp,
                        shadowElevation = 10.dp,
                        border = androidx.compose.foundation.BorderStroke(
                            0.7.dp,
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.055f)
                        )
                    ) {
                        Column {
                            DEFAULT_PERMISSIONS.forEach { permission ->
                                val isSelected = permission.first == selectedPermission
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = permission.second,
                                            fontSize = 17.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    },
                                    leadingIcon = {
                                        if (isSelected) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_menu_check),
                                                contentDescription = "当前选中",
                                                modifier = Modifier.size(22.dp),
                                                tint = MaterialTheme.colorScheme.onSurface
                                            )
                                        } else {
                                            Spacer(Modifier.size(22.dp))
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().height(54.dp),
                                    contentPadding = PaddingValues(horizontal = 20.dp),
                                    onClick = {
                                        onExpandedChange(false)
                                        onPermissionSelected(permission.first)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    )
}

@Composable
private fun GatewayEndpointRow(stateHolder: AndroidSharedStateHolder) {
    BasicTextField(
        value = stateHolder.endpoint,
        onValueChange = { stateHolder.endpoint = it },
        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        decorationBox = { innerTextField ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (stateHolder.endpoint.isBlank()) {
                    Text(
                        "ws://host:3080/ws/mobile",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f),
                        fontSize = 16.sp
                    )
                }
                innerTextField()
            }
        }
    )
}

@Composable
private fun GatewayStatusRow(stateHolder: AndroidSharedStateHolder) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 54.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(Modifier.size(9.dp).background(statusColor(stateHolder), CircleShape))
        Text(connectionLabel(stateHolder), fontSize = 16.sp)
        Spacer(Modifier.weight(1f))
        stateHolder.gatewayState.serverPort?.let { port ->
            Text(
                "Port $port",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun SettingsActionRow(title: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 54.dp).clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            color = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.35f),
            fontSize = 16.sp
        )
    }
}

private fun isConnected(holder: AndroidSharedStateHolder) = holder.gatewayState.connection == GatewayConnectionState.CONNECTED

private fun selectedPreset(holder: AndroidSharedStateHolder): String {
    val id = holder.snapshot.agentPresetDefault ?: return "未读取"
    val gatewayName = holder.snapshot.agentPresets.firstOrNull { it.id == id }?.name
    return agentPresetDisplayName(id, gatewayName)
}

private fun selectedModel(holder: AndroidSharedStateHolder): String {
    val selection = holder.snapshot.defaultModel ?: return "未读取"
    val item = holder.snapshot.modelCatalog?.groups?.flatMap { it.models }?.firstOrNull { it.id == selection.model }
    val base = item?.name ?: when (selection.model) {
        "deepseek-chat" -> "DeepSeek Chat"
        "deepseek-reasoner" -> "DeepSeek Reasoner"
        else -> selection.model
    }
    val effortName = selection.reasoningEffort?.let { effortId ->
        item?.reasoning?.efforts?.firstOrNull { it.id == effortId }?.name
            ?: when (effortId.lowercase()) {
                "low" -> "低"
                "medium" -> "中"
                "high" -> "高"
                else -> effortId.replaceFirstChar(Char::uppercase)
            }
    }
    return effortName?.let { "$base · $it" } ?: base
}

private fun permissionName(value: String?) = when (value) {
    "read-only" -> "只读"
    "workspace-write" -> "工作区写入"
    "danger-full-access" -> "完全访问"
    else -> "未读取"
}

private fun connectionLabel(holder: AndroidSharedStateHolder) = when (holder.gatewayState.connection) {
    GatewayConnectionState.CONNECTED -> "已连接"
    GatewayConnectionState.CONNECTING, GatewayConnectionState.AUTHENTICATING -> "连接中"
    GatewayConnectionState.WAITING_FOR_NETWORK -> "等待网络"
    GatewayConnectionState.FAILED -> "连接失败"
    GatewayConnectionState.SUSPENDED -> "已挂起"
    GatewayConnectionState.DISCONNECTED -> "未连接"
}

@Composable
private fun statusColor(holder: AndroidSharedStateHolder) = when (holder.gatewayState.connection) {
    GatewayConnectionState.CONNECTED -> DshColors.Success
    GatewayConnectionState.FAILED -> Color.Red
    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)
}

private val DEFAULT_PERMISSIONS = listOf(
    Triple("read-only", "只读", "允许读取工作区，不允许修改文件"),
    Triple("workspace-write", "工作区写入", "允许在当前工作区内读取和写入"),
    Triple("danger-full-access", "完全访问", "允许不受沙箱限制地访问宿主环境")
)
