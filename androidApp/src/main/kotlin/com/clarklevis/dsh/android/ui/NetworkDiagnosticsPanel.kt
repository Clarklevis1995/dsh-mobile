// Compose 组件沿用项目 PascalCase；图表索引、颜色和尺寸是展示参数。
@file:Suppress("FunctionNaming", "MagicNumber", "TooManyFunctions", "ktlint:standard:function-naming")

package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.clarklevis.dsh.android.AndroidSharedStateHolder
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.shared.diagnostics.NetworkDiagnosticsSnapshot
import com.clarklevis.dsh.shared.transport.RtcSelectedPath
import kotlinx.coroutines.flow.Flow
import java.util.Locale

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun NetworkDiagnosticsScreen(
    stateHolder: AndroidSharedStateHolder,
    onBack: () -> Unit,
) {
    val background = MaterialTheme.colorScheme.surfaceContainerLow
    androidx.compose.material3.Scaffold(
        containerColor = background,
        topBar = {
            androidx.compose.material3.CenterAlignedTopAppBar(
                title = { Text(appText("网络检测"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    TopBarCircleButton(
                        iconRes = com.clarklevis.dsh.android.R.drawable.ic_back_chevron,
                        description = appText("返回"),
                        onClick = onBack,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                },
                colors =
                    androidx.compose.material3.TopAppBarDefaults
                        .topAppBarColors(containerColor = background),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxWidth()
                .padding(padding)
                .adaptiveContentWidth()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))) {
                NetworkDiagnosticsPanel(stateHolder)
            }
        }
    }
}

@Composable
internal fun NetworkDiagnosticsPanel(stateHolder: AndroidSharedStateHolder) {
    val paths by stateHolder.connectionPaths.collectAsStateWithLifecycle()
    NetworkDiagnosticsPanel(remember(stateHolder) { stateHolder::observeNetworkDiagnostics }, paths)
}

@Composable
internal fun NetworkDiagnosticsPanel(
    observe: () -> Flow<NetworkDiagnosticsSnapshot>,
    paths: Map<String, RtcSelectedPath> = emptyMap(),
) {
    var snapshot by remember(observe) { mutableStateOf(NetworkDiagnosticsSnapshot()) }
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(observe, lifecycle) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            observe().collect { snapshot = it }
        }
    }
    NetworkDiagnosticsContent(snapshot, paths)
}

@Composable
internal fun NetworkDiagnosticsContent(
    snapshot: NetworkDiagnosticsSnapshot,
    paths: Map<String, RtcSelectedPath> = emptyMap(),
) {
    var selectedMetric by remember { mutableIntStateOf(0) }
    val primary = MaterialTheme.colorScheme.primary
    val orange = Color(0xFFD68A2E)
    Column(Modifier.fillMaxWidth().padding(16.dp).testTag("network-diagnostics"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        NetworkConnectionHeader(snapshot, primary)
        if (snapshot.connected && snapshot.connectionMethod.startsWith("WebRTC")) RtcConnectionPathDetails(paths)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NetworkMetric(appText("往返延迟"), networkNumber(snapshot.roundTripMs, " ms"), Modifier.weight(1f))
            NetworkMetric(appText("探测丢包率"), networkNumber(snapshot.lossPercent, "%"), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NetworkMetric(appText("延迟抖动"), networkNumber(snapshot.jitterMs, " ms"), Modifier.weight(1f))
            NetworkMetric(appText("接收速率"), networkRate(snapshot.receivedBytesPerSecond), Modifier.weight(1f))
        }
        NetworkMetricSelector(selectedMetric) { selectedMetric = it }
        NetworkLineChart(snapshot.samples, selectedMetric, primary, orange)
        NetworkChartLegend(selectedMetric, primary, orange)
        Text(
            appText(
                "平均 %1\$s · P95 %2\$s",
                networkNumber(snapshot.averageRoundTripMs, " ms"),
                networkNumber(snapshot.p95RoundTripMs, " ms"),
            ),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            appText(
                "发送 %1\$s · 成功 %2\$s / 超时 %3\$s",
                networkRate(snapshot.sentBytesPerSecond),
                snapshot.successfulProbes,
                snapshot.timedOutProbes,
            ),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (snapshot.connected && !snapshot.supported) {
            Text(
                appText("请更新网关以启用可关联的网络探测；旧网关不会被误判为丢包。"),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun RtcConnectionPathDetails(
    paths: Map<String, RtcSelectedPath>,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().testTag("gateway-connection-path"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(appText("传输路径"), fontSize = 14.sp)
        for (channel in listOf("control", "conversation")) {
            val title = appText(if (channel == "control") "控制通道" else "会话通道")
            val path = paths[channel]
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (path == null) {
                    Text("$title：${appText("正在确认路径")}", fontSize = 12.sp)
                } else {
                    val kind =
                        when (path.kind) {
                            "local" -> "内网直连"
                            "nat" -> "NAT 穿透直连"
                            "relay" -> "TURN 中继"
                            else -> "直连"
                        }
                    Text("$title：${appText(kind)} · ${path.protocol}", fontSize = 12.sp)
                    Text("${path.localEndpoint} → ${path.remoteEndpoint}", fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun NetworkConnectionHeader(
    snapshot: NetworkDiagnosticsSnapshot,
    primary: Color,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(appText(if (snapshot.connected) snapshot.connectionMethod.ifEmpty { "未连接" } else "未连接"), fontSize = 13.sp, color = primary)
        Text(
            appText(
                if (!snapshot.connected) {
                    "等待连接"
                } else if (!snapshot.supported) {
                    "网关需更新"
                } else {
                    "每秒检测 · 最近 60 秒"
                },
            ),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NetworkChartLegend(
    selectedMetric: Int,
    primary: Color,
    orange: Color,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(appText("60 秒前"), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (selectedMetric == 3) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(appText("接收"), fontSize = 10.sp, color = primary)
                Text(appText("发送"), fontSize = 10.sp, color = orange)
                Text("KiB/s", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Text(
                appText(if (selectedMetric == 1) "探测超时 · %" else "毫秒 · ms"),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(appText("现在"), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NetworkMetricSelector(
    selectedMetric: Int,
    onSelect: (Int) -> Unit,
) {
    val titles = listOf("延迟", "丢包", "抖动", "流量")
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(10.dp)).padding(3.dp)) {
        titles.forEachIndexed { index, title ->
            Box(
                Modifier
                    .weight(1f)
                    .background(
                        if (selectedMetric == index) {
                            MaterialTheme.colorScheme.surface
                        } else {
                            Color.Transparent
                        },
                        RoundedCornerShape(8.dp),
                    ).selectable(
                        selected = selectedMetric == index,
                        role = Role.Tab,
                        onClick = { onSelect(index) },
                    ).padding(vertical = 7.dp)
                    .semantics { contentDescription = appText(title) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    appText(title),
                    Modifier.padding(horizontal = 12.dp),
                    fontSize = 12.sp,
                    fontWeight = if (selectedMetric == index) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun NetworkMetric(
    title: String,
    value: String,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

internal fun networkNumber(
    value: Double,
    unit: String,
): String = if (value < 0 || !value.isFinite()) "—" else String.format(Locale.getDefault(), "%.1f%s", value, unit)

internal fun networkRate(value: Double): String = networkNumber(value / 1024.0, " KiB/s")
