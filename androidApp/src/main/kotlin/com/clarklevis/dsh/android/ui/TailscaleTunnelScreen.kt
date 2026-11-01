package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clarklevis.dsh.android.DshAndroidApplication
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.android.platform.TailscaleTunnelUiState
import com.clarklevis.dsh.android.platform.TunnelFailureKind
import com.clarklevis.dsh.android.platform.TunnelUiStage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 内嵌 Tailscale 隧道页。
 * 版面沿用设置页的节奏：节标题 + 一张分组卡 + 卡内行间分隔 + 节尾说明，
 * 不自己发明卡片样式（SettingsScreen 里的同类容器是文件私有的，这里按同一规格实现）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TailscaleTunnelScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = LocalTailscaleTunnelSettings.current
    val controller = remember(context) { (context.applicationContext as? DshAndroidApplication)?.tunnel }
    val flow = controller?.state ?: MutableStateFlow(TailscaleTunnelUiState())
    val tunnelState by flow.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    var copiedAt by remember { mutableStateOf(0) }
    var invalidName by remember { mutableStateOf(false) }
    LaunchedEffect(copiedAt) {
        if (copiedAt > 0) {
            delay(2_000)
            copied = false
        }
    }

    Scaffold(
        containerColor = if (isSystemInDarkTheme()) Color(0xFF0D1118) else Color(0xFFF7F7F8),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(appText("Tailscale 内嵌隧道"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    TopBarCircleButton(
                        iconRes = R.drawable.ic_back_chevron,
                        description = appText("返回"),
                        onClick = onBack,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (isSystemInDarkTheme()) Color(0xFF0D1118) else Color(0xFFF7F7F8)
                )
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .adaptiveContentWidth().testTag("tunnel-content").padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            TunnelSection(
                title = appText("Tailscale 内嵌隧道"),
                footer = appText("在应用内建立一个 Tailscale 节点：不需要安装 Tailscale，也不会占用系统 VPN。节点只在你自己的 tailnet 内可达。")
            ) {
                Row(Modifier.fillMaxWidth().heightIn(min = 54.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(appText("启用内嵌隧道"), fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Switch(
                        checked = settings.enabled,
                        onCheckedChange = { enabled ->
                            settings.selectEnabled(enabled)
                            controller?.setEnabled(enabled)
                        },
                        colors = dshSwitchColors(),
                        modifier = Modifier.semantics { contentDescription = appText("启用内嵌隧道") }
                            .testTag("tunnel-enabled-switch")
                    )
                }
            }

            if (settings.enabled) {
                TunnelSection(
                    title = appText("隧道状态"),
                    footer = if (tunnelState.stage == TunnelUiStage.NEEDS_LOGIN) {
                        appText("等待你在浏览器里完成登录。")
                    } else {
                        null
                    }
                ) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("tunnel-status-row"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val transient = tunnelState.stage == TunnelUiStage.STARTING ||
                            tunnelState.stage == TunnelUiStage.NEEDS_LOGIN ||
                            tunnelState.stage == TunnelUiStage.WAITING_ROUTES
                        StatusIndicatorDot(
                            color = stageColor(tunnelState),
                            modifier = Modifier.size(8.dp),
                            glowing = transient
                        )
                        Spacer(Modifier.size(10.dp))
                        Text(stageLabel(tunnelState), fontSize = 16.sp, modifier = Modifier.weight(1f))
                        if (tunnelState.isPolling) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp).testTag("tunnel-starting-progress"),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)
                            )
                        } else if (tunnelState.peerCount > 0) {
                            Text(
                                "${appText("对端数量")} ${tunnelState.peerCount}",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f)
                            )
                        }
                    }
                    tunnelState.nodeAddress?.let { address ->
                        DividerRow()
                        ValueRow(appText("本机地址"), address)
                    }
                    failureText(tunnelState)?.let { reason ->
                        DividerRow()
                        ValueRow(appText("失败原因"), reason)
                    }
                    tunnelState.authUrl?.let { url ->
                        DividerRow()
                        // 一行里两个动作：主动作占剩余宽度，次动作自然宽度——
                        // 之前两个都写 fillMaxWidth，中文被逐字换行成一竖条。
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 54.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(20.dp)
                        ) {
                            Text(
                                appText("打开登录链接"),
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                modifier = Modifier.weight(1f).clickable { uriHandler.openUri(url) }
                            )
                            Text(
                                if (copied) appText("已复制") else appText("复制链接"),
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                modifier = Modifier.clickable {
                                    clipboard.setText(AnnotatedString(url))
                                    copied = true
                                    copiedAt += 1
                                }
                            )
                        }
                    }
                    if (tunnelState.stage != TunnelUiStage.DISABLED) {
                        DividerRow()
                        // 行内容必须靠 Row 的垂直居中定位：直接在 Text 上用 heightIn
                        // 只会把盒子撑高，文字仍贴在顶部（实测就是你看到的这个）。
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 54.dp)
                                .clickable {
                                    settings.selectEnabled(false)
                                    controller?.stop()
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (tunnelState.stage == TunnelUiStage.STARTING ||
                                    tunnelState.stage == TunnelUiStage.NEEDS_LOGIN
                                ) {
                                    appText("停止启动")
                                } else {
                                    appText("停止隧道")
                                },
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1
                            )
                        }
                    }
                }

                TunnelSection(
                    title = appText("节点身份"),
                    footer = appText("节点名称只允许小写字母、数字和连字符。登录时默认在浏览器里完成；也可以粘贴 Tailscale 控制台生成的 Auth Key。")
                ) {
                    FieldRow(
                        label = appText("节点名称"),
                        value = settings.nodeName,
                        placeholder = TailscaleTunnelSettings.defaultNodeName(),
                        tag = "tunnel-field-node-name"
                    ) { raw -> invalidName = !settings.selectNodeName(raw) }
                    if (invalidName) {
                        Text(
                            appText("只允许小写字母、数字和连字符，最长 63 个字符。"),
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    DividerRow()
                    FieldRow(
                        label = appText("登录密钥（可选）"),
                        value = settings.authKey,
                        placeholder = "tskey-auth-…",
                        tag = "tunnel-field-auth-key"
                    ) { settings.selectAuthKey(it) }
                }
            }
        }
    }
}

@Composable
private fun TunnelSection(title: String, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
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
                .padding(horizontal = 16.dp)
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
private fun DividerRow() {
    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
}

@Composable
private fun ValueRow(title: String, value: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 54.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Text(
            value,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun FieldRow(label: String, value: String, placeholder: String, tag: String, onValueChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 16.sp, modifier = Modifier.weight(1f))
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag(tag),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(placeholder, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.30f))
                }
                inner()
            }
        )
    }
}

private fun failureText(state: TailscaleTunnelUiState): String? = when (state.failureKind) {
    TunnelFailureKind.INTERFACE_SOURCE -> appText("接口枚举失败")
    TunnelFailureKind.NATIVE_LOAD -> appText("运行库加载失败")
    TunnelFailureKind.BACKEND_START -> appText("节点启动失败")
    TunnelFailureKind.LOGIN_WAIT -> appText("登录等待超时")
    TunnelFailureKind.STATUS -> appText("状态查询失败")
    TunnelFailureKind.UNKNOWN -> appText("未知错误")
    else -> null
}

private fun stageLabel(state: TailscaleTunnelUiState): String = when (state.stage) {
    TunnelUiStage.DISABLED -> appText("未启用")
    TunnelUiStage.STARTING -> appText("启动中")
    TunnelUiStage.NEEDS_LOGIN -> appText("需要登录")
    TunnelUiStage.WAITING_ROUTES -> appText("正在建立连接")
    TunnelUiStage.READY -> appText("已连接")
    TunnelUiStage.FAILED -> appText("连接失败")
}

@Composable
private fun stageColor(state: TailscaleTunnelUiState): Color = when (state.stage) {
    TunnelUiStage.READY -> DshColors.Success
    TunnelUiStage.FAILED -> MaterialTheme.colorScheme.error
    TunnelUiStage.DISABLED -> Color.Red
    TunnelUiStage.NEEDS_LOGIN, TunnelUiStage.STARTING, TunnelUiStage.WAITING_ROUTES -> DshColors.Ocean
}
