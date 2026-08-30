package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.shared.protocol.GatewaySessionStats
import com.clarklevis.dsh.shared.protocol.GatewaySessionStatsSnapshot
import com.clarklevis.dsh.shared.protocol.GatewaySessionTokenUsageTotals
import java.util.Locale
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal object SessionStatsFormatter {
    fun turnsStepsLine(stats: GatewaySessionStats?): String? {
        if (stats?.turns == null && stats?.steps == null) return null
        return "${stats.turns ?: 0} 轮 · ${stats.steps ?: 0} 步"
    }

    fun duration(milliseconds: Double): String {
        val seconds = milliseconds.coerceAtLeast(0.0) / 1_000.0
        if (seconds >= 60) {
            val total = seconds.roundToInt().coerceAtLeast(0)
            return "${total / 60}m${total % 60}s"
        }
        return if (seconds >= 1) {
            "${compactDecimal(seconds)}s"
        } else {
            "${milliseconds.coerceAtLeast(0.0).roundToInt()}ms"
        }
    }

    fun averageTtft(stats: GatewaySessionStats?): Double? {
        val total = stats?.ttftMs ?: return null
        val steps = stats.ttftSteps ?: return null
        return if (steps > 0) total / steps else null
    }

    fun throughput(stats: GatewaySessionStats?): Double? {
        val milliseconds = stats?.decodeMs ?: return null
        val tokens = stats.decodeTokens ?: return null
        return if (milliseconds > 0) tokens / (milliseconds / 1_000.0) else null
    }

    fun cacheHitRate(totals: GatewaySessionTokenUsageTotals?): Double? {
        totals ?: return null
        val total = listOfNotNull(
            totals.inputTokens,
            totals.outputTokens,
            totals.cacheReadTokens,
            totals.cacheWriteTokens
        ).sum()
        val cacheRead = totals.cacheReadTokens ?: return null
        return if (total > 0) (cacheRead.toDouble() / total).coerceIn(0.0, 1.0) else null
    }

    fun compact(value: Int): String = when {
        value >= 1_000_000 -> {
            if (value % 1_000_000 == 0) "${value / 1_000_000}M"
            else String.format(Locale.US, "%.1fM", value / 1_000_000.0)
        }
        value >= 1_000 -> String.format(Locale.US, "%.1fK", value / 1_000.0)
        else -> value.toString()
    }

    fun compactDecimal(value: Double): String = if (value.toLong().toDouble() == value) {
        value.toLong().toString()
    } else {
        String.format(Locale.US, "%.1f", value)
    }
}

@Composable
internal fun SessionStatsBanner(
    snapshot: GatewaySessionStatsSnapshot,
    sessionId: String?,
    onViewFullStats: () -> Unit
) {
    var expanded by remember(sessionId) { mutableStateOf(false) }
    val isDark = isSystemInDarkTheme()
    val glassEdge = dshGlassEdge(isDark)
    val shadowColor = dshFloatingSurfaceShadow(isDark)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 2.dp),
        horizontalArrangement = Arrangement.End
    ) {
        Box {
            val shape = RoundedCornerShape(13.dp)
            Surface(
                modifier = Modifier
                    .dropShadow(
                        shape = shape,
                        shadow = Shadow(
                            radius = 10.dp,
                            spread = 0.dp,
                            color = shadowColor,
                            offset = DpOffset(0.dp, 4.dp)
                        )
                    )
                    .clickable(role = Role.Button) { expanded = true }
                    .semantics {
                        contentDescription = "查看会话执行状态"
                    }
                    .testTag("session-stats-capsule"),
                shape = shape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                border = BorderStroke(0.8.dp, glassEdge),
                shadowElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        SessionStatsFormatter.turnsStepsLine(snapshot.stats) ?: "正在读取会话统计…",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 12.sp,
                            fontFeatureSettings = "tnum"
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_up),
                        contentDescription = null,
                        modifier = Modifier.size(10.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.30f)
                    )
                }
            }
            SessionStatsPopover(
                expanded = expanded,
                snapshot = snapshot,
                onDismiss = { expanded = false },
                onViewFullStats = {
                    expanded = false
                    onViewFullStats()
                }
            )
        }
    }
}

@Composable
private fun SessionStatsPopover(
    expanded: Boolean,
    snapshot: GatewaySessionStatsSnapshot,
    onDismiss: () -> Unit,
    onViewFullStats: () -> Unit
) {
    if (!expanded) return
    val density = LocalDensity.current
    val isDark = isSystemInDarkTheme()
    val glassEdge = dshGlassEdge(isDark)
    val shadowColor = dshFloatingSurfaceShadow(isDark)
    Popup(
        alignment = Alignment.BottomEnd,
        offset = with(density) { IntOffset(0, (-20).dp.roundToPx()) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, clippingEnabled = false)
    ) {
        Box(Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 24.dp)) {
            val shape = RoundedCornerShape(24.dp)
            Surface(
                modifier = Modifier.width(300.dp).dropShadow(
                    shape = shape,
                    shadow = Shadow(
                        radius = 10.dp,
                        spread = 0.dp,
                        color = shadowColor,
                        offset = DpOffset(0.dp, 4.dp)
                    )
                ).testTag("session-stats-popover"),
                shape = shape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                border = BorderStroke(0.8.dp, glassEdge),
                shadowElevation = 0.dp
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val stats = snapshot.stats
                    SessionStatsFormatter.turnsStepsLine(stats)?.let { AgentStatusRow("轮次 · 步骤", it) }
                    stats?.llmMs?.let { AgentStatusRow("LLM", SessionStatsFormatter.duration(it)) }
                    stats?.toolMs?.let { AgentStatusRow("工具调用", SessionStatsFormatter.duration(it)) }
                    SessionStatsFormatter.averageTtft(stats)?.let {
                        AgentStatusRow("首 token 平均", SessionStatsFormatter.duration(it))
                    }
                    SessionStatsFormatter.throughput(stats)?.let {
                        AgentStatusRow("解码吞吐", "${SessionStatsFormatter.compactDecimal(it)} tok/s")
                    }
                    SessionStatsFormatter.cacheHitRate(snapshot.tokenUsage?.totals)?.let {
                        AgentStatusRow("缓存命中", "${(it * 100).roundToInt()}%")
                    }
                    snapshot.tokenUsage?.totals?.inputTokens?.let {
                        AgentStatusRow("输入", "${SessionStatsFormatter.compact(it)} tok")
                    }
                    HorizontalDivider(color = Color.Black.copy(alpha = 0.08f))
                    Text(
                        "查看完整统计",
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onViewFullStats)
                            .padding(vertical = 2.dp).testTag("view-full-session-stats"),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentStatusRow(title: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color(0xFF7A7A80), fontSize = 14.sp)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionStatsSheet(
    snapshot: GatewaySessionStatsSnapshot?,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val coroutineScope = rememberCoroutineScope()
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val expandedTopInset = with(LocalDensity.current) { windowHeight.toDp() * 0.12f }
    val dismissWithAnimation = {
        coroutineScope.launch {
            sheetState.hide()
            onDismiss()
        }
        Unit
    }
    ModalBottomSheet(
        onDismissRequest = dismissWithAnimation,
        // Keep the modal root full-height so Material can calculate drag anchors
        // against the whole window. The inner top padding limits the visible sheet
        // to 88% while keeping its lower edge pinned to the navigation bar.
        modifier = Modifier.fillMaxHeight().padding(top = expandedTopInset),
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp),
        containerColor = Color(0xFFF2F2F7),
        contentColor = Color(0xFF111217),
        scrimColor = Color.Black.copy(alpha = 0.28f),
        dragHandle = {
            Box(
                modifier = Modifier.fillMaxWidth().height(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier.width(36.dp).height(5.dp)
                        .background(Color(0xFF8E8E93), CircleShape)
                )
            }
        }
    ) {
        Column(
            modifier = Modifier.fillMaxSize().testTag("session-stats-sheet")
                .navigationBarsPadding()
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("会话状态", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Surface(
                    modifier = Modifier.align(Alignment.CenterEnd).height(44.dp)
                        .clickable(role = Role.Button, onClick = dismissWithAnimation),
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.72f),
                    border = BorderStroke(0.7.dp, Color.Black.copy(alpha = 0.06f))
                ) {
                    Box(Modifier.padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                        Text("完成", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                val stats = snapshot?.stats
                if (stats == null) {
                    Box(Modifier.fillMaxWidth().heightIn(min = 220.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("正在读取会话统计", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))
                            Text("统计会在本轮结束后自动更新。", color = Color(0xFF8E8E93), fontSize = 14.sp)
                        }
                    }
                } else {
                    StatsMetricsSection(
                        title = "执行",
                        metrics = listOf(
                            "轮次" to "${stats.turns ?: 0} 轮",
                            "步骤" to "${stats.steps ?: 0} 步",
                            "LLM" to (stats.llmMs?.let(SessionStatsFormatter::duration) ?: "—"),
                            "工具调用" to (stats.toolMs?.let(SessionStatsFormatter::duration) ?: "—"),
                            "首 token 平均" to (SessionStatsFormatter.averageTtft(stats)?.let(SessionStatsFormatter::duration) ?: "—"),
                            "解码吞吐" to (SessionStatsFormatter.throughput(stats)?.let { "${SessionStatsFormatter.compactDecimal(it)} tok/s" } ?: "—")
                        )
                    )
                    snapshot.tokenUsage?.totals?.let { totals ->
                        StatsMetricsSection(
                            title = "Token 用量",
                            metrics = listOf(
                                "输入" to token(totals.inputTokens),
                                "输出" to token(totals.outputTokens),
                                "缓存读取" to token(totals.cacheReadTokens),
                                "缓存写入" to token(totals.cacheWriteTokens),
                                "推理" to token(totals.reasoningTokens),
                                "缓存命中" to (SessionStatsFormatter.cacheHitRate(totals)?.let { "${(it * 100).roundToInt()}%" } ?: "—")
                            )
                        )
                    }
                    snapshot.contextPressure?.let { pressure ->
                        StatsMetricsSection(
                            title = "上下文",
                            metrics = listOf(
                                "当前压力" to token(pressure.pressureTokens),
                                "预计用量" to token(pressure.projectedTokens),
                                "上下文窗口" to token(pressure.contextWindow)
                            )
                        )
                    }
                    snapshot.asOfSeq?.let {
                        Text(
                            "数据截至事件 #$it",
                            color = Color(0xFFAEAEB2),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun StatsMetricsSection(title: String, metrics: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Column(
            modifier = Modifier.fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.025f), RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp)
        ) {
            metrics.forEach { (label, value) ->
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(label, color = Color(0xFF8E8E93), fontSize = 16.sp)
                    Spacer(Modifier.weight(1f))
                    Text(
                        value,
                        style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

private fun token(value: Int?): String = value?.let { "${SessionStatsFormatter.compact(it)} tok" } ?: "—"
