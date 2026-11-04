package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clarklevis.dsh.android.AndroidSharedStateHolder
import com.clarklevis.dsh.android.DshAndroidApplication
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.android.platform.TunnelUiStage
import com.clarklevis.dsh.shared.gateway.GatewayConnectionState
import com.clarklevis.dsh.shared.protocol.GatewayAgentPreset
import com.clarklevis.dsh.shared.protocol.GatewayModelGroup
import com.clarklevis.dsh.shared.protocol.GatewayModelItem
import com.clarklevis.dsh.shared.protocol.GatewayPermissionOption
import com.clarklevis.dsh.shared.protocol.GatewayReasoningEffort
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    stateHolder: AndroidSharedStateHolder,
    onBack: () -> Unit,
    onOpenAgentPresets: () -> Unit,
    onOpenDefaultModel: () -> Unit,
    onOpenFontSize: () -> Unit,
    onOpenStorage: () -> Unit = {},
    onOpenLogs: () -> Unit = {},
    onOpenNetworkDiagnostics: () -> Unit = {},
    onOpenTunnel: () -> Unit = {},
    updateModel: com.clarklevis.dsh.android.update.AppUpdateViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val updateState by updateModel.state.collectAsStateWithLifecycle()
    val connectionMethod by stateHolder.connectionMethod.collectAsStateWithLifecycle()
    val connectionPaths by stateHolder.connectionPaths.collectAsStateWithLifecycle()
    var showPermissionPicker by remember { mutableStateOf(false) }
    var pendingPermission by remember { mutableStateOf<String?>(null) }
    var showPrivacyPolicy by remember { mutableStateOf(false) }
    var showReleaseNotes by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val releaseInfo = remember(context) { AppReleaseInfo.load(context) }
    val connectedGatewayVersion = if (isConnected(stateHolder) && !stateHolder.pluginsLoading && stateHolder.pluginsError == null) {
        stateHolder.pluginCatalog?.bundles?.firstOrNull { it.name == "dsh-plugin-mobile-gateway" }?.version
    } else {
        null
    }
    val releaseScope = rememberCoroutineScope()
    fun openRelease(repository: String, version: String?) {
        releaseScope.launch {
            val url = withContext(Dispatchers.IO) { resolveReleaseUrl(repository, version) }
            runCatching { uriHandler.openUri(url) }
        }
    }
    val dark = isSystemInDarkTheme()
    val pageBackground = if (dark) Color(0xFF0D1118) else Color(0xFFF1F1F6)
    val topBarSeparator = if (dark) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.12f)
    val backdrop = rememberLayerBackdrop()
    val listState = rememberLazyListState()
    val layoutDirection = LocalLayoutDirection.current
    LaunchedEffect(stateHolder.gatewayState.connection) {
        stateHolder.refreshProductState()
        stateHolder.refreshCacheStorage()
        if (isConnected(stateHolder)) stateHolder.refreshPlugins()
    }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(appText("设置"), style = SettingsTypography.title) },
                modifier = Modifier.testTag("settings-toolbar"),
                navigationIcon = {
                    TopBarCircleButton(
                        iconRes = R.drawable.ic_back_chevron,
                        description = appText("返回"),
                        onClick = onBack,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent)
            )
        },
        containerColor = pageBackground
    ) { padding ->
        Box(Modifier.fillMaxSize().testTag("settings-screen")) {
            LazyColumn(
                Modifier.fillMaxSize().layerBackdrop(backdrop).background(pageBackground).testTag("settings-list"),
                state = listState,
                contentPadding = PaddingValues(
                    start = padding.calculateStartPadding(layoutDirection),
                    top = padding.calculateTopPadding(),
                    end = padding.calculateEndPadding(layoutDirection),
                    bottom = padding.calculateBottomPadding()
                ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                item {
                    Column(
                        Modifier.adaptiveContentWidth().testTag("settings-content")
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(22.dp)
                    ) {
                        SettingsSection(
                            title = appText("新会话默认配置"),
                            footer = appText("与 WebUI 使用同一份部署级设置。修改只影响之后新建的会话，运行中的会话保持启动时的配置。")
                        ) {
                            SettingsValueRow(
                                title = appText("Agent 预设"),
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
                                title = appText("默认模型"),
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
                                value = stateHolder.snapshot.permissionDefaultOptions
                                    .firstOrNull { it.value == stateHolder.snapshot.permissionDefault }?.name
                                    ?: permissionName(stateHolder.snapshot.permissionDefault),
                                selectedPermission = stateHolder.snapshot.permissionDefault,
                                options = stateHolder.snapshot.permissionDefaultOptions,
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
                            SettingsValueRow(
                                appText("连接方式"),
                                if (isConnected(stateHolder)) appText(connectionMethod.ifEmpty { "WebSocket" }) else appText("未连接"),
                                modifier = Modifier.testTag("gateway-connection-method")
                            )
                            if (isConnected(stateHolder) && connectionMethod == "WebRTC") {
                                RtcConnectionPathDetails(connectionPaths, Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                            }
                            SettingsDivider()
                            SettingsValueRow(
                                appText("网络检测"),
                                appText("实时延迟与丢包"),
                                modifier = Modifier.testTag("network-diagnostics-entry"),
                                onClick = onOpenNetworkDiagnostics
                            )
                            SettingsDivider()
                            SettingsActionRow(if (isConnected(stateHolder)) appText("断开连接") else appText("连接")) {
                                if (isConnected(stateHolder)) stateHolder.disconnect() else stateHolder.connect()
                            }
                            SettingsDivider()
                            SettingsActionRow(appText("Ping 网关"), enabled = isConnected(stateHolder), onClick = stateHolder::pingGateway)
                        }
                        SettingsSection(appText("Tailscale 内嵌隧道")) {
                            TailscaleTunnelRow(onOpenTunnel)
                        }
                        stateHolder.snapshot.hostSnapshot?.let { host ->
                            SettingsSection("DSH Host") {
                                SettingsValueRow(appText("版本"), dshHostVersion(host.dshVersion, host.version))
                                SettingsDivider()
                                SettingsValueRow(appText("已连接会话"), "${host.attachedSessions ?: 0}")
                                host.cwd?.let { cwd ->
                                    SettingsDivider()
                                    SettingsValueRow("cwd", cwd)
                                }
                            }
                        }
                        SettingsSection(
                            title = appText("外观"),
                            footer = appText("语言设置将在重新启动应用后生效。支持简体中文和英文。")
                        ) {
                            InterfaceStyleSettingsRow()
                            SettingsDivider()
                            LanguageSettingsRow()
                            SettingsDivider()
                            FontSizeSettingsRow(onOpenFontSize)
                            SettingsDivider()
                            UiEffectsSettingsRow()
                        }
                        SettingsSection(appText("本地存储")) {
                            SettingsValueRow(
                                title = appText("存储空间"),
                                value = stateHolder.storageUsage?.let {
                                    android.text.format.Formatter.formatFileSize(context, it.totalBytes)
                                }.orEmpty(),
                                modifier = Modifier.testTag("storage-space-settings-row"),
                                onClick = onOpenStorage
                            )
                        }
                        SettingsSection(appText("日志")) {
                            SettingsValueRow(appText("日志导出与分享"), "", onClick = onOpenLogs)
                        }
                        SettingsSection(appText("关于")) {
                            SettingsValueRow(
                                title = appText("当前版本（去给作者Star🌟）"),
                                value = updateModel.currentVersion,
                                enabled = !updateState.busy,
                                isLoading = updateState.busy,
                                onClick = { openRelease("dsh-mobile", updateModel.currentVersion) }
                            )
                            SettingsDivider()
                            SettingsValueRow(appText("要求网关版本"), releaseInfo?.minimumGatewayVersion?.let { "≥ $it" } ?: appText("未提供")) {
                                openRelease("dsh-plugin-mobile-gateway", releaseInfo?.minimumGatewayVersion)
                            }
                            SettingsDivider()
                            SettingsValueRow(
                                appText("已连接网关版本"),
                                if (!isConnected(stateHolder)) appText("未连接") else connectedGatewayVersion ?: appText("未提供"),
                                isLoading = isConnected(stateHolder) && stateHolder.pluginsLoading
                            ) { openRelease("dsh-plugin-mobile-gateway", connectedGatewayVersion) }
                            SettingsDivider()
                            SettingsValueRow(appText("本版本新功能"), appText("查看发布说明")) { showReleaseNotes = true }
                            SettingsDivider()
                            SettingsActionRow(appText("检查更新"), enabled = !updateState.busy, onClick = updateModel::check)
                            SettingsDivider()
                            SettingsActionRow(appText("隐私政策")) {
                                showPrivacyPolicy = true
                            }
                        }
                        stateHolder.platformError?.let { error ->
                            Text(error, color = MaterialTheme.colorScheme.error, style = SettingsTypography.description)
                        }
                        Spacer(Modifier.padding(bottom = 20.dp))
                    }
                }
            }
            Box(
                Modifier.fillMaxWidth().height(padding.calculateTopPadding())
                    .clipToBounds()
                    .drawWithContent {
                        drawContent()
                        if (listState.canScrollBackward) {
                            // 离开列表顶部后才显示整屏细线，使用物理像素避免高密度屏幕上变粗。
                            drawRect(topBarSeparator, Offset(0f, size.height - 1f), Size(size.width, 1f))
                        }
                    }
                    .topBarGlassSurface(backdrop, pageBackground, surfaceAlpha = 0.5f)
                    .semantics { hideFromAccessibility() }
                    .testTag("settings-top-glass")
            )
        }
    }
    pendingPermission?.let { value ->
        DshAlertDialog(
            title = appText("修改全局默认权限？"),
            message = appText(
                "将新会话的默认权限改为“%1\$s”。这会更新部署级设置，并同步影响 WebUI。",
                stateHolder.snapshot.permissionDefaultOptions.firstOrNull { it.value == value }?.name ?: permissionName(value)
            ),
            confirmLabel = appText("确认修改"),
            onDismissRequest = { pendingPermission = null },
            onConfirm = {
                stateHolder.setDefault("permission", value)
                pendingPermission = null
            }
        )
    }
    if (showReleaseNotes) {
        DshAlertDialog(
            title = appText("本版本新功能 %1\$s", updateModel.currentVersion),
            onDismissRequest = { showReleaseNotes = false },
            dismissLabel = appText("完成"),
            confirmLabel = appText("GitHub 发布页"),
            onConfirm = { openRelease("dsh-mobile", null) },
            content = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    DshMarkdownText(
                        markdown = if (releaseInfo?.version == updateModel.currentVersion) {
                            releaseInfo.notes
                        } else {
                            appText("本版本发布说明暂未提供，请查看 GitHub 发布页。")
                        },
                        compact = true
                    )
                }
            }
        )
    }
    if (showPrivacyPolicy) {
        PrivacyPolicyBottomSheet { showPrivacyPolicy = false }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentPresetSelectionScreen(stateHolder: AndroidSharedStateHolder, onBack: () -> Unit) {
    var pendingPreset by remember { mutableStateOf<GatewayAgentPreset?>(null) }
    val presets = stateHolder.snapshot.agentPresets
    val isLoading = "agent-presets" in stateHolder.defaultConfigurationLoadingKinds && presets.isEmpty()
    val isBusy = "set-default" in stateHolder.defaultConfigurationLoadingKinds
    LaunchedEffect(Unit) {
        if (presets.isEmpty()) stateHolder.refreshDefaultConfiguration()
    }

    SettingsSelectionScaffold(title = appText("Agent 预设"), onBack = onBack) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                SettingsSelectionHeader(
                    title = appText("Agent 预设"),
                    description = appText("预设决定 Agent 使用的工具、提示词与能力。选择后只对新建会话生效。")
                )
            }
            when {
                isLoading -> item { SettingsLoadingState(appText("正在读取 Agent 预设…")) }
                presets.isEmpty() -> item {
                    SettingsEmptyState(
                        title = appText("没有可用的 Agent 预设"),
                        description = appText("请确认 Mobile Gateway 已升级到 v0.1.11 并保持连接。")
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
                        style = SettingsTypography.description,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }

    pendingPreset?.let { preset ->
        DshAlertDialog(
            title = appText("设为全局默认预设？"),
            message = appText("将“%1\$s”设为新会话的默认 Agent 预设。这会更新部署级设置，并同步影响 WebUI。", agentPresetDisplayName(preset.id, preset.name)),
            confirmLabel = appText("设为默认"),
            onDismissRequest = { pendingPreset = null },
            onConfirm = {
                stateHolder.setDefault("agent-preset", preset.id)
                pendingPreset = null
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DefaultModelSelectionScreen(stateHolder: AndroidSharedStateHolder, onBack: () -> Unit) {
    var pendingChange by remember { mutableStateOf<PendingDefaultModelChange?>(null) }
    val groups = stateHolder.snapshot.modelCatalog?.groups.orEmpty()
    val isLoading = "models" in stateHolder.defaultConfigurationLoadingKinds && groups.isEmpty()
    val isBusy = "save-default-model" in stateHolder.defaultConfigurationLoadingKinds
    LaunchedEffect(Unit) { stateHolder.ensureDefaultModelConfiguration() }

    SettingsSelectionScaffold(title = appText("默认模型"), onBack = onBack) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                SettingsSelectionHeader(
                    title = appText("默认模型"),
                    description = appText("为之后新建的会话设置默认模型与思考等级。这会更新部署级设置，同步影响 WebUI，运行中的会话保持启动时的配置。")
                )
            }
            when {
                isLoading -> item { SettingsLoadingState(appText("正在读取模型列表…")) }
                groups.isEmpty() -> item {
                    SettingsEmptyState(
                        title = appText("暂无可用模型"),
                        description = appText("未能读取到模型列表，请检查网关连接后重试。")
                    )
                }
                else -> groups.forEach { group ->
                    item(key = "group-${group.id}") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                group.name,
                                style = SettingsTypography.section,
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
        DshAlertDialog(
            title = appText("修改默认模型？"),
            message = appText("将新会话的默认模型改为“%1\$s”。这会更新部署级设置，并同步影响 WebUI。", change.summary),
            confirmLabel = appText("确认修改"),
            onDismissRequest = { pendingChange = null },
            onConfirm = {
                stateHolder.saveDefaultModel(change.provider, change.model, change.reasoningEffort)
                pendingChange = null
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSelectionScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val background = if (dark) Color(0xFF0D1118) else Color(0xFFF1F1F6)
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title, style = SettingsTypography.title) },
                navigationIcon = {
                    TopBarCircleButton(
                        iconRes = R.drawable.ic_back_chevron,
                        description = appText("返回"),
                        onClick = onBack,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = background)
            )
        },
        containerColor = background
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).adaptiveContentWidth().testTag("settings-selection-content")) { content() }
    }
}

@Composable
private fun SettingsSelectionHeader(title: String, description: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text(title, style = SettingsTypography.title)
        Text(
            description,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.52f),
            style = SettingsTypography.description
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
        Text(label, style = SettingsTypography.description, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.52f))
    }
}

@Composable
private fun SettingsEmptyState(title: String, description: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, style = SettingsTypography.title)
        Text(
            description,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.52f),
            style = SettingsTypography.description
        )
    }
}

@Composable
private fun AgentPresetCard(preset: GatewayAgentPreset, selected: Boolean, busy: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val enabled = !selected && !busy && preset.broken != true
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
                },
                shape = shape
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                agentPresetDisplayName(preset.id, preset.name),
                style = SettingsTypography.item
            )
            Text(
                preset.id,
                modifier = Modifier.background(
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f),
                    RoundedCornerShape(50)
                ).padding(horizontal = 7.dp, vertical = 3.dp),
                style = SettingsTypography.description,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f)
            )
            Spacer(Modifier.weight(1f))
            if (selected) CurrentDefaultBadge()
        }
        Text(
            agentPresetDisplayDescription(preset.id, preset.description),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
            style = SettingsTypography.description
        )
        if (preset.broken == true) {
            Text(
                appText("⚠ 该预设存在配置错误，暂时不能设为默认值"),
                color = Color(0xFFF08A16),
                style = SettingsTypography.description
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
                color = if (selected) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
                },
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
            Text(model.name, style = SettingsTypography.item, modifier = Modifier.weight(1f))
            if (selected) CurrentDefaultBadge()
        }
        ReasoningEffortOptions(model.reasoning?.efforts.orEmpty(), selected, currentEffort, busy, onSelectEffort)
    }
}

@Composable
private fun ReasoningEffortOptions(
    efforts: List<GatewayReasoningEffort>,
    selected: Boolean,
    currentEffort: String?,
    busy: Boolean,
    onSelectEffort: (GatewayReasoningEffort) -> Unit
) {
    if (efforts.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                appText("思考等级"),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
                style = SettingsTypography.item
            )
            efforts.forEach { effort ->
                val active = selected && effort.id == currentEffort
                Text(
                    effort.name,
                    modifier = Modifier.clip(RoundedCornerShape(50))
                        .background(
                            if (active) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                            }
                        )
                        .clickable(enabled = !busy) { onSelectEffort(effort) }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    color = if (active) {
                        MaterialTheme.colorScheme.surface
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    style = SettingsTypography.item
                )
            }
        }
    }
}

@Composable
private fun CurrentDefaultBadge() {
    Text(
        appText("当前使用"),
        modifier = Modifier.background(Color.Black, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        color = Color.White,
        style = SettingsTypography.description,
        fontWeight = FontWeight.SemiBold
    )
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
        appText("服务端支持编写自定义预设，并提供预设配置文档。")
    holder.agentPresetsAuthorable -> appText("服务端支持编写自定义 Agent 预设。")
    holder.agentPresetsHasDocument -> appText("服务端提供 Agent 预设配置文档。")
    else -> ""
}

@Composable
private fun SettingsSection(title: String, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            modifier = Modifier.padding(start = 20.dp),
            style = SettingsTypography.section,
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
                style = SettingsTypography.description,
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
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val rowAlpha = if (enabled) 1f else 0.4f
    Row(
        modifier.fillMaxWidth().heightIn(min = 54.dp)
            .clickable(enabled = enabled && onClick != null) { onClick?.invoke() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = rowAlpha),
            style = SettingsTypography.item
        )
        Spacer(Modifier.width(12.dp))
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
                style = SettingsTypography.item,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (onClick != null) {
            Icon(
                painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.30f else 0.15f),
                modifier = Modifier.padding(start = 8.dp).size(12.dp)
            )
        }
    }
}

@Composable
internal fun TailscaleTunnelRow(onClick: () -> Unit) {
    val settings = LocalTailscaleTunnelSettings.current
    val flow = (LocalContext.current.applicationContext as? DshAndroidApplication)?.tunnel?.state
    val state = flow?.collectAsStateWithLifecycle()?.value
    SettingsValueRow(
        title = appText("内嵌隧道"),
        value = if (settings.enabled) tailscaleStageLabel(state?.stage ?: TunnelUiStage.DISABLED) else appText("未启用"),
        onClick = onClick,
        modifier = Modifier.testTag("tailscale-tunnel-settings-row")
    )
}

@Composable
internal fun FontSizeSettingsRow(onClick: () -> Unit) {
    val appearance = LocalAppearanceSettings.current
    SettingsValueRow(
        title = appText("字体大小"),
        value = if (appearance.fontFollowsSystem) appText("跟随系统") else "${(appearance.fontScale * 100).roundToInt()}%",
        onClick = onClick
    )
}

@Composable
internal fun UiEffectsSettingsRow() {
    val appearance = LocalAppearanceSettings.current
    Row(
        Modifier.fillMaxWidth()
            .testTag("ui-effects-reduced-switch")
            .toggleable(
                value = appearance.uiEffectsReduced,
                role = Role.Switch,
                onValueChange = appearance::selectUiEffectsReduced
            )
            .heightIn(min = 54.dp)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(appText("UI 降级"), style = SettingsTypography.item)
            Text(
                appText("停止主页背景动画，降低毛玻璃效果"),
                style = SettingsTypography.description,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DshSwitch(
            checked = appearance.uiEffectsReduced,
            onCheckedChange = null
        )
    }
}

@Composable
internal fun InterfaceStyleSettingsRow() {
    val appearance = LocalAppearanceSettings.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        SettingsValueRow(appText("界面"), appearance.interfaceStyle.title) { expanded = true }
        DshSelectionPopup(
            expanded = expanded,
            options = InterfaceStyle.entries.map { DshSelectionOption(it.name, it.title) },
            selectedKey = appearance.interfaceStyle.name,
            onDismissRequest = { expanded = false },
            onSelect = { key ->
                appearance.selectInterfaceStyle(InterfaceStyle.valueOf(key))
                expanded = false
            }
        )
    }
}

@Composable
internal fun LanguageSettingsRow() {
    val appearance = LocalAppearanceSettings.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        SettingsValueRow(appText("语言"), appearance.language.title) { expanded = true }
        DshSelectionPopup(
            expanded = expanded,
            options = AppLanguage.entries.map { DshSelectionOption(it.name, it.title) },
            selectedKey = appearance.language.name,
            onDismissRequest = { expanded = false },
            onSelect = { key ->
                appearance.selectLanguage(AppLanguage.valueOf(key))
                expanded = false
            }
        )
    }
}

@Composable
private fun PermissionSettingsRow(
    value: String,
    selectedPermission: String?,
    options: List<GatewayPermissionOption>,
    expanded: Boolean,
    enabled: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onPermissionSelected: (String) -> Unit
) {
    Box {
        SettingsValueRow(
            title = appText("权限"),
            value = value,
            enabled = enabled,
            onClick = { onExpandedChange(true) }
        )
        DshSelectionPopup(
            expanded = expanded,
            options = options.map { permission ->
                DshSelectionOption(
                    permission.value,
                    permission.name,
                    when (permission.value) {
                        "read-only" -> R.drawable.ic_permission_read
                        "workspace-write" -> R.drawable.ic_menu_check
                        else -> R.drawable.ic_permission_warning
                    }
                )
            },
            selectedKey = selectedPermission,
            onDismissRequest = { onExpandedChange(false) },
            onSelect = { key ->
                onExpandedChange(false)
                onPermissionSelected(key)
            }
        )
    }
}

@Composable
internal fun SettingsDivider() {
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
        textStyle = SettingsTypography.item.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        decorationBox = { innerTextField ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (stateHolder.endpoint.isBlank()) {
                    Text(
                        "ws://host:3080/ws/mobile",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f),
                        style = SettingsTypography.item
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
        Text(connectionLabel(stateHolder), style = SettingsTypography.item)
        Spacer(Modifier.weight(1f))
        stateHolder.gatewayState.serverPort?.let { port ->
            Text(
                "Port $port",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
                style = SettingsTypography.item
            )
        }
    }
}

@Composable
internal fun SettingsActionRow(title: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 54.dp).clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            color = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.35f),
            style = SettingsTypography.item
        )
    }
}

private fun isConnected(holder: AndroidSharedStateHolder) = holder.gatewayState.connection == GatewayConnectionState.CONNECTED

private fun selectedPreset(holder: AndroidSharedStateHolder): String {
    val id = holder.snapshot.agentPresetDefault ?: return appText("未读取")
    val gatewayName = holder.snapshot.agentPresets.firstOrNull { it.id == id }?.name
    return agentPresetDisplayName(id, gatewayName)
}

private fun selectedModel(holder: AndroidSharedStateHolder): String {
    val selection = holder.snapshot.defaultModel ?: return appText("未读取")
    val item = holder.snapshot.modelCatalog?.groups?.flatMap { it.models }?.firstOrNull { it.id == selection.model }
    val base = item?.name ?: when (selection.model) {
        "deepseek-chat" -> "DeepSeek Chat"
        "deepseek-reasoner" -> "DeepSeek Reasoner"
        else -> selection.model
    }
    val effortName = selection.reasoningEffort?.let { effortId ->
        item?.reasoning?.efforts?.firstOrNull { it.id == effortId }?.name
            ?: when (effortId.lowercase()) {
                "low" -> appText("低")
                "medium" -> appText("中")
                "high" -> appText("高")
                else -> effortId.replaceFirstChar(Char::uppercase)
            }
    }
    return effortName?.let { "$base · $it" } ?: base
}

private fun permissionName(value: String?) = when (value) {
    "read-only" -> appText("只读")
    "workspace-write" -> appText("工作区写入")
    "danger-full-access" -> appText("完全访问")
    else -> appText("未读取")
}

private fun connectionLabel(holder: AndroidSharedStateHolder) = when (holder.gatewayState.connection) {
    GatewayConnectionState.CONNECTED -> appText("已连接")
    GatewayConnectionState.CONNECTING, GatewayConnectionState.AUTHENTICATING -> appText("连接中")
    GatewayConnectionState.WAITING_FOR_NETWORK -> appText("等待网络")
    GatewayConnectionState.FAILED -> appText("连接失败")
    GatewayConnectionState.SUSPENDED -> appText("已挂起")
    GatewayConnectionState.DISCONNECTED -> appText("未连接")
}

@Composable
private fun statusColor(holder: AndroidSharedStateHolder) = when (holder.gatewayState.connection) {
    GatewayConnectionState.CONNECTED -> DshColors.Success
    GatewayConnectionState.FAILED -> Color.Red
    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)
}
