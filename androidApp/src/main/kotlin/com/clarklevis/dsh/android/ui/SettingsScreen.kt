package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clarklevis.dsh.android.AndroidSharedStateHolder
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.shared.gateway.GatewayConnectionState
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(stateHolder: AndroidSharedStateHolder, onBack: () -> Unit) {
    var picker by remember { mutableStateOf<SettingsPicker?>(null) }
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
                        SettingsValueRow("Agent 预设", selectedPreset(stateHolder), enabled = isConnected(stateHolder)) {
                            picker = SettingsPicker.PRESET
                        }
                        SettingsDivider()
                        SettingsValueRow("默认模型", selectedModel(stateHolder), enabled = isConnected(stateHolder)) {
                            picker = SettingsPicker.MODEL
                        }
                        SettingsDivider()
                        SettingsValueRow("权限", permissionName(stateHolder.snapshot.permissionDefault), enabled = isConnected(stateHolder)) {
                            picker = SettingsPicker.PERMISSION
                        }
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
    picker?.let { active ->
        ModalBottomSheet(onDismissRequest = { picker = null }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when (active) {
                        SettingsPicker.PRESET -> "选择 Agent 预设"
                        SettingsPicker.MODEL -> "选择默认模型"
                        SettingsPicker.PERMISSION -> "选择默认权限"
                    },
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold
                )
                when (active) {
                    SettingsPicker.PRESET -> stateHolder.snapshot.agentPresets.filter { it.broken != true }.forEach { preset ->
                        PickerRow(agentPresetDisplayName(preset.id, preset.name), preset.description, preset.id == stateHolder.snapshot.agentPresetDefault) {
                            stateHolder.setDefault("agent-preset", preset.id)
                            picker = null
                        }
                    }
                    SettingsPicker.MODEL -> stateHolder.snapshot.modelCatalog?.groups.orEmpty().forEach { group ->
                        Text(group.name.uppercase(), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        group.models.forEach { model ->
                            PickerRow(
                                model.name,
                                model.reasoning?.defaultEffort?.let { "默认推理等级：$it" },
                                stateHolder.snapshot.defaultModel?.provider == group.id && stateHolder.snapshot.defaultModel?.model == model.id
                            ) {
                                stateHolder.saveDefaultModel(group.id, model.id, model.reasoning?.defaultEffort)
                                picker = null
                            }
                        }
                    }
                    SettingsPicker.PERMISSION -> DEFAULT_PERMISSIONS.forEach { permission ->
                        PickerRow(permission.second, permission.third, permission.first == stateHolder.snapshot.permissionDefault) {
                            pendingPermission = permission.first
                            picker = null
                        }
                    }
                }
                Spacer(Modifier.padding(bottom = 12.dp))
            }
        }
    }
    pendingPermission?.let { value ->
        AlertDialog(
            onDismissRequest = { pendingPermission = null },
            title = { Text("修改全局默认权限？") },
            text = { Text("将新会话的默认权限改为“${permissionName(value)}”。这会更新部署级设置，并同步影响 WebUI。") },
            confirmButton = {
                TextButton(onClick = {
                    stateHolder.setDefault("permission", value)
                    pendingPermission = null
                }) { Text("确认修改") }
            },
            dismissButton = { TextButton(onClick = { pendingPermission = null }) { Text("取消") } }
        )
    }
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
        Text(
            value,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.52f else 0.3f),
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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

@Composable
private fun PickerRow(title: String, description: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(
            if (selected) DshColors.Ocean.copy(alpha = 0.10f) else Color.Transparent,
            RoundedCornerShape(12.dp)
        ).clickable(onClick = onClick).padding(13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (selected) "●" else "○", color = if (selected) DshColors.Ocean else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
        Spacer(Modifier.padding(horizontal = 6.dp))
        Column {
            Text(title, fontWeight = FontWeight.Medium)
            description?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)) }
        }
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
    return selection.reasoningEffort?.let { "$base · ${it.replaceFirstChar(Char::uppercase)}" } ?: base
}

private fun permissionName(value: String?) = when (value) {
    "ask" -> "每次询问"
    "read-only" -> "只读"
    "workspace-write" -> "工作区写入"
    "danger-full-access" -> "完全访问"
    null -> "未读取"
    else -> value
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

private enum class SettingsPicker { PRESET, MODEL, PERMISSION }

private val DEFAULT_PERMISSIONS = listOf(
    Triple("ask", "每次询问", "需要写入或执行敏感操作时请求确认"),
    Triple("read-only", "只读", "允许读取工作区，不允许修改文件"),
    Triple("workspace-write", "工作区写入", "允许在当前工作区内读取和写入"),
    Triple("danger-full-access", "完全访问", "允许不受沙箱限制地访问宿主环境")
)
