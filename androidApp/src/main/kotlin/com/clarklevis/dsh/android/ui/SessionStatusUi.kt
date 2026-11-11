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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.safeDrawing
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clarklevis.dsh.android.localization.appText
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
        return appText("%1\$s 轮 · %2\$s 步", stats.turns ?: 0, stats.steps ?: 0)
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

    fun compact(value: Long): String = when {
        value >= 1_000_000 -> {
            if (value % 1_000_000 == 0L) "${value / 1_000_000}M"
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
    modifier: Modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
    onViewFullStats: () -> Unit
) {
    var expanded by remember(sessionId) { mutableStateOf(false) }
    val isDark = isSystemInDarkTheme()
    val glassEdge = dshGlassEdge(isDark)
    val shadowColor = dshFloatingSurfaceShadow(isDark)
    Row(
        modifier = modifier,
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
                        contentDescription = appText("查看会话执行状态")
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
                        SessionStatsFormatter.turnsStepsLine(snapshot.stats) ?: appText("正在读取会话统计…"),
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
    ComposerGlassPopover(
        expanded = expanded,
        onDismiss = onDismiss,
        testTag = "session-stats-popover"
    ) {
        val stats = snapshot.stats
        SessionStatsFormatter.turnsStepsLine(stats)?.let { AgentStatusRow(appText("轮次 · 步骤"), it) }
        stats?.llmMs?.let { AgentStatusRow("LLM", SessionStatsFormatter.duration(it)) }
        stats?.toolMs?.let { AgentStatusRow(appText("工具调用"), SessionStatsFormatter.duration(it)) }
        SessionStatsFormatter.averageTtft(stats)?.let {
            AgentStatusRow(appText("首 token 平均"), SessionStatsFormatter.duration(it))
        }
        SessionStatsFormatter.throughput(stats)?.let {
            AgentStatusRow(appText("解码吞吐"), "${SessionStatsFormatter.compactDecimal(it)} tok/s")
        }
        SessionStatsFormatter.cacheHitRate(snapshot.tokenUsage?.totals)?.let {
            AgentStatusRow(appText("缓存命中"), "${(it * 100).roundToInt()}%")
        }
        snapshot.tokenUsage?.totals?.inputTokens?.let {
            AgentStatusRow(appText("输入"), "${SessionStatsFormatter.compact(it)} tok")
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            appText("查看完整统计"),
            modifier = Modifier.fillMaxWidth().clickable(onClick = onViewFullStats)
                .padding(vertical = 2.dp).testTag("view-full-session-stats"),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun AgentStatusRow(title: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
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
    val colors = MaterialTheme.colorScheme
    val (sheetColor, cardColor) = sessionStatsSheetColors()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    val dragHandleHeight = 24.dp
    val sheetContentHeight = (adaptiveWindowSize().height * 0.88f - dragHandleHeight).coerceAtLeast(0.dp)
    val dismissWithAnimation = {
        coroutineScope.launch {
            sheetState.hide()
            onDismiss()
        }
        Unit
    }
    ModalBottomSheet(
        sheetMaxWidth = adaptiveSheetWidth(),
        onDismissRequest = dismissWithAnimation,
        // 外层保留窗口约束，实际高度由内容决定，背景与内容共用同一组拖动锚点。
        modifier = Modifier.adaptiveSheetBounds(),
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp),
        containerColor = sheetColor,
        contentColor = colors.onBackground,
        scrimColor = Color.Black.copy(alpha = 0.28f),
        // 底部由内容的 navigationBarsPadding 处理，避免重复占用高度。
        contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal) },
        dragHandle = {
            Box(
                modifier = Modifier.fillMaxWidth().height(dragHandleHeight),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier.width(36.dp).height(5.dp)
                        .background(colors.outline, CircleShape)
                )
            }
        }
    ) {
        DshMenuColumnHost {
            Column(
                modifier = Modifier.fillMaxWidth().height(sheetContentHeight).testTag("session-stats-sheet")
                    .navigationBarsPadding()
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(appText("会话状态"), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Surface(
                        modifier = Modifier.align(Alignment.CenterEnd).height(44.dp)
                            .clickable(role = Role.Button, onClick = dismissWithAnimation),
                        shape = CircleShape,
                        color = cardColor,
                        contentColor = colors.onSurface,
                        border = BorderStroke(0.7.dp, colors.outlineVariant)
                    ) {
                        Box(Modifier.padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                            Text(appText("完成"), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp)
                ) {
                    val stats = snapshot?.stats
                    if (stats == null) {
                        Box(Modifier.fillMaxWidth().heightIn(min = 220.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(appText("正在读取会话统计"), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(8.dp))
                                Text(appText("统计会在本轮结束后自动更新。"), color = colors.onSurfaceVariant, fontSize = 14.sp)
                            }
                        }
                    } else {
                        StatsMetricsSection(
                            title = appText("执行"),
                            containerColor = cardColor,
                            metrics = listOf(
                                appText("轮次") to appText("%1\$s 轮", stats.turns ?: 0),
                                appText("步骤") to appText("%1\$s 步", stats.steps ?: 0),
                                "LLM" to (stats.llmMs?.let(SessionStatsFormatter::duration) ?: "—"),
                                appText("工具调用") to (stats.toolMs?.let(SessionStatsFormatter::duration) ?: "—"),
                                appText("首 token 平均") to (SessionStatsFormatter.averageTtft(stats)?.let(SessionStatsFormatter::duration) ?: "—"),
                                appText("解码吞吐") to (SessionStatsFormatter.throughput(stats)?.let { "${SessionStatsFormatter.compactDecimal(it)} tok/s" } ?: "—")
                            )
                        )
                        snapshot.tokenUsage?.totals?.let { totals ->
                            StatsMetricsSection(
                                title = appText("Token 用量"),
                                containerColor = cardColor,
                                metrics = listOf(
                                    appText("输入") to token(totals.inputTokens),
                                    appText("输出") to token(totals.outputTokens),
                                    appText("缓存读取") to token(totals.cacheReadTokens),
                                    appText("缓存写入") to token(totals.cacheWriteTokens),
                                    appText("推理") to token(totals.reasoningTokens),
                                    appText("缓存命中") to (SessionStatsFormatter.cacheHitRate(totals)?.let { "${(it * 100).roundToInt()}%" } ?: "—")
                                )
                            )
                        }
                        snapshot.contextPressure?.let { pressure ->
                            StatsMetricsSection(
                                title = appText("上下文"),
                                containerColor = cardColor,
                                metrics = listOf(
                                    appText("当前压力") to token(pressure.pressureTokens?.toLong()),
                                    appText("预计用量") to token(pressure.projectedTokens?.toLong()),
                                    appText("上下文窗口") to token(pressure.contextWindow?.toLong())
                                )
                            )
                        }
                        snapshot.asOfSeq?.let {
                            Text(
                                appText("数据截至事件 #%1\$s", it),
                                color = colors.onSurfaceVariant,
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
}

private const val DARK_STATS_SHEET_BACKGROUND = 0xFF2A2A2B
private const val DARK_STATS_SHEET_CARD = 0xFF2F2F2F

@Composable
private fun sessionStatsSheetColors(): Pair<Color, Color> {
    val colors = MaterialTheme.colorScheme
    // 对齐 iOS regularMaterial 弹层与 thinMaterial 卡片的中性灰层次。
    return if (isSystemInDarkTheme()) {
        Color(DARK_STATS_SHEET_BACKGROUND) to Color(DARK_STATS_SHEET_CARD)
    } else {
        colors.background to colors.surface
    }
}

@Composable
private fun StatsMetricsSection(
    title: String,
    containerColor: Color,
    metrics: List<Pair<String, String>>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Column(
            modifier = Modifier.fillMaxWidth()
                .background(containerColor, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp)
        ) {
            metrics.forEach { (label, value) ->
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
                    Spacer(Modifier.weight(1f))
                    Text(
                        value,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

private fun token(value: Long?): String = value?.let { "${SessionStatsFormatter.compact(it)} tok" } ?: "—"
