package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.shared.projection.TrajectoryNode
import com.clarklevis.dsh.shared.projection.TrajectoryNodeKind
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.delay

internal data class TrajectoryDisplayRow(
    val node: TrajectoryNode,
    val request: TrajectoryNode?,
    val turn: Int?,
    val startsTurn: Boolean
)

internal data class TrajectoryOverviewEntry(
    val node: TrajectoryNode,
    val startFraction: Float,
    val endFraction: Float
)

internal data class TrajectoryOverviewSummary(
    val durationSeconds: Double,
    val turnCount: Int,
    val toolCount: Int,
    val visibleNodes: List<TrajectoryNode>,
    val entries: List<TrajectoryOverviewEntry>
)

internal object TrajectoryUiProjection {
    fun overview(nodes: List<TrajectoryNode>): TrajectoryOverviewSummary {
        val visible = nodes.filter { it.kind != TrajectoryNodeKind.REQUEST }
        val duration = visible.sumOf { (it.endEpochSeconds - it.startEpochSeconds).coerceAtLeast(0.0) }
        return TrajectoryOverviewSummary(
            durationSeconds = duration,
            turnCount = nodes.flatMap(TrajectoryNode::records).mapNotNull { it.event.turn }.toSet().size,
            toolCount = nodes.count {
                it.kind == TrajectoryNodeKind.TOOL || it.kind == TrajectoryNodeKind.SUBTOOL
            },
            visibleNodes = visible,
            entries = layoutEntries(visible)
        )
    }

    fun displayRows(nodes: List<TrajectoryNode>): List<TrajectoryDisplayRow> {
        val visible = nodes.filter { it.kind != TrajectoryNodeKind.REQUEST }
        val requestsByStep = nodes.mapNotNull { node ->
            val request = node.request ?: return@mapNotNull null
            if (node.kind != TrajectoryNodeKind.REQUEST || request.turn == null || request.step == null) {
                return@mapNotNull null
            }
            "${request.turn}-${request.step}" to node
        }.toMap()
        var previousTurn: Int? = null
        return visible.mapIndexed { index, node ->
            val directTurn = node.records.firstNotNullOfOrNull { it.event.turn }
            val turn = directTurn ?: visible.subList(index, visible.size)
                .asSequence().flatMap { it.records.asSequence() }
                .mapNotNull { it.event.turn }.firstOrNull()
            val startsTurn = turn != null && turn != previousTurn
            if (turn != null) previousTurn = turn
            val step = node.records.firstNotNullOfOrNull { it.event.step }
            TrajectoryDisplayRow(
                node = node,
                request = if (node.kind == TrajectoryNodeKind.ASSISTANT && turn != null && step != null) {
                    requestsByStep["$turn-$step"]
                } else {
                    null
                },
                turn = turn,
                startsTurn = startsTurn
            )
        }
    }

    fun nearestEntry(
        entries: List<TrajectoryOverviewEntry>,
        fraction: Float,
        lane: TrajectoryNodeKind
    ): TrajectoryNode? {
        val laneEntries = entries.filter { entry ->
            when (lane) {
                TrajectoryNodeKind.ASSISTANT -> entry.node.kind in setOf(
                    TrajectoryNodeKind.CONTEXT,
                    TrajectoryNodeKind.ASSISTANT
                )
                else -> entry.node.kind == lane
            }
        }
        return (laneEntries.ifEmpty { entries }).minByOrNull { entry ->
            when {
                fraction < entry.startFraction -> entry.startFraction - fraction
                fraction > entry.endFraction -> fraction - entry.endFraction
                else -> 0f
            }
        }?.node
    }

    private fun layoutEntries(nodes: List<TrajectoryNode>): List<TrajectoryOverviewEntry> {
        if (nodes.isEmpty()) return emptyList()
        val durations = nodes.map {
            (it.endEpochSeconds - it.startEpochSeconds).coerceAtLeast(0.0)
        }
        val activeDuration = durations.sum()
        val minimumWeight = maxOf(0.001, activeDuration / 500.0)
        val weights = durations.map { maxOf(it, minimumWeight) }
        val totalWeight = maxOf(0.001, weights.sum())
        var cursor = 0.0
        return nodes.zip(weights).map { (node, weight) ->
            val start = cursor / totalWeight
            cursor += weight
            TrajectoryOverviewEntry(node, start.toFloat(), (cursor / totalWeight).toFloat())
        }
    }
}

@Composable
internal fun TrajectoryPage(
    nodes: List<TrajectoryNode>,
    isActive: Boolean
) {
    val summary = remember(nodes) { TrajectoryUiProjection.overview(nodes) }
    val rows = remember(nodes) { TrajectoryUiProjection.displayRows(nodes) }
    val listState = rememberLazyListState()
    var selectedNode by remember { mutableStateOf<TrajectoryNode?>(null) }
    var highlightedId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(highlightedId) {
        val id = highlightedId ?: return@LaunchedEffect
        delay(1_200)
        if (highlightedId == id) highlightedId = null
    }

    Column(Modifier.fillMaxSize().testTag("trajectory-page")) {
        TrajectoryOverview(summary) { node ->
            val index = rows.indexOfFirst { it.node.id == node.id }
            if (index >= 0) {
                highlightedId = node.id
            }
        }
        LaunchedEffect(highlightedId, rows) {
            val id = highlightedId ?: return@LaunchedEffect
            val index = rows.indexOfFirst { it.node.id == id }
            if (index >= 0) listState.animateScrollToItem(index)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp)
                .testTag("trajectory-list"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 90.dp)
        ) {
            if (isActive && nodes.isEmpty()) {
                item("trajectory-generating") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = DshColors.Ocean,
                            strokeWidth = 2.dp
                        )
                        Text(
                            "正在生成轨迹…",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                            fontSize = 12.sp
                        )
                    }
                }
            }
            itemsIndexed(rows, key = { _, row -> "trajectory:${row.node.id}" }) { _, row ->
                if (row.startsTurn && row.turn != null) TrajectoryTurnHeader(row.turn)
                TrajectoryRow(
                    row = row,
                    highlighted = highlightedId == row.node.id,
                    onSelect = { selectedNode = row.node },
                    onRequest = { selectedNode = it }
                )
            }
        }
    }

    selectedNode?.let { node ->
        TrajectoryEventDetailSheet(node = node, onDismiss = { selectedNode = null })
    }
}

@Composable
private fun TrajectoryOverview(
    summary: TrajectoryOverviewSummary,
    onSelect: (TrajectoryNode) -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val duration = String.format(Locale.US, "%.2f s", summary.durationSeconds)
    Box(
        modifier = Modifier.fillMaxWidth()
            .background(if (isDark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7))
            .testTag("trajectory-overview")
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TrajectoryClockIcon()
                Spacer(Modifier.width(8.dp))
                Text("Duration", fontSize = 12.sp)
                Spacer(Modifier.width(12.dp))
                Text("${summary.turnCount} Turns", fontSize = 12.sp)
                Spacer(Modifier.width(12.dp))
                Text("${summary.toolCount} Calls", fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    duration,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(
                    modifier = Modifier.width(34.dp).height(52.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Text("Input", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.56f), fontSize = 11.sp)
                    Text("Model", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.56f), fontSize = 11.sp)
                    Text("Tools", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.56f), fontSize = 11.sp)
                }
                TimelineOverviewCanvas(
                    entries = summary.entries,
                    onSelect = onSelect,
                    modifier = Modifier.weight(1f).height(52.dp)
                )
            }
            Row {
                Text(
                    "0s",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.weight(1f))
                Text(
                    duration,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(0.7.dp).background(
                if (isDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.10f)
            )
        )
    }
}

@Composable
private fun TrajectoryClockIcon() {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(16.dp)) {
        val stroke = 1.5.dp.toPx()
        drawCircle(color, style = Stroke(stroke))
        drawLine(
            color,
            start = center,
            end = Offset(center.x, center.y - 4.dp.toPx()),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        drawLine(
            color,
            start = center,
            end = Offset(center.x - 3.dp.toPx(), center.y),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun TimelineOverviewCanvas(
    entries: List<TrajectoryOverviewEntry>,
    onSelect: (TrajectoryNode) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val guideColor = if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.07f)
    Canvas(
        modifier.pointerInput(entries) {
            detectTapGestures { position ->
                if (entries.isEmpty() || size.width == 0) return@detectTapGestures
                val fraction = (position.x / size.width).coerceIn(0f, 0.999999f)
                val lane = when {
                    position.y < 15.dp.toPx() -> TrajectoryNodeKind.INPUT
                    position.y < 35.dp.toPx() -> TrajectoryNodeKind.ASSISTANT
                    else -> TrajectoryNodeKind.TOOL
                }
                TrajectoryUiProjection.nearestEntry(entries, fraction, lane)?.let(onSelect)
            }
        }.semantics { contentDescription = "轨迹耗时概览" }
    ) {
        val lanePositions = listOf(6.dp.toPx(), 25.dp.toPx(), 44.dp.toPx())
        lanePositions.forEach { y ->
            drawLine(guideColor, Offset(0f, y), Offset(size.width, y), 0.7.dp.toPx())
        }
        entries.forEach { entry ->
            val height = if (entry.node.kind == TrajectoryNodeKind.ASSISTANT) 9.dp.toPx() else 7.dp.toPx()
            val startX = entry.startFraction * size.width
            val naturalWidth = (entry.endFraction - entry.startFraction) * size.width
            val minimumWidth = if (entry.node.kind == TrajectoryNodeKind.INPUT) 2.dp.toPx() else 1.5.dp.toPx()
            val width = maxOf(minimumWidth, naturalWidth)
            val x = startX.coerceIn(0f, (size.width - width).coerceAtLeast(0f))
            val y = trajectoryLaneY(entry.node.kind).dp.toPx() - height / 2
            drawRoundRect(
                color = trajectoryColor(entry.node.kind, isDark),
                topLeft = Offset(x, y),
                size = Size(width, height),
                cornerRadius = CornerRadius(1.dp.toPx())
            )
        }
    }
}

@Composable
private fun TrajectoryTurnHeader(turn: Int) {
    val isDark = isSystemInDarkTheme()
    Row(
        modifier = Modifier.fillMaxWidth().height(18.dp).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Turn $turn",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
        Box(
            Modifier.weight(1f).height(1.dp).background(
                if (isDark) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.10f)
            )
        )
    }
}

@Composable
private fun TrajectoryRow(
    row: TrajectoryDisplayRow,
    highlighted: Boolean,
    onSelect: () -> Unit,
    onRequest: (TrajectoryNode) -> Unit
) {
    val node = row.node
    val isDark = isSystemInDarkTheme()
    val color = trajectoryColor(node.kind, isDark)
    Row(
        modifier = Modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(10.dp))
            .background(if (highlighted) DshColors.Ocean.copy(alpha = 0.12f) else Color.Transparent)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier.size(width = 10.dp, height = 20.dp)
                    .clickable(
                        enabled = row.request != null,
                        role = Role.Button
                    ) { row.request?.let(onRequest) }
                    .semantics {
                        contentDescription = row.request?.request?.number?.let { "打开 Request #$it" }
                            ?: "${trajectoryLabel(node.kind)} 轨迹节点"
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier.size(if (row.request != null) 10.dp else 7.dp)
                        .background(
                            if (row.request != null) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f) else color,
                            CircleShape
                        )
                )
            }
            Box(
                Modifier.width(if (isDark) 1.5.dp else 1.dp).height(22.dp)
                    .background(
                        if (isDark) Color.White.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.14f)
                    )
            )
        }
        Row(
            modifier = Modifier.weight(1f).height(20.dp)
                .clickable(role = Role.Button, onClick = onSelect)
                .semantics { contentDescription = "打开 ${trajectoryLabel(node.kind)} #${node.startSequence}" },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                trajectoryLabel(node.kind),
                modifier = Modifier.background(
                    color.copy(alpha = if (isDark) 0.24f else 0.10f),
                    RoundedCornerShape(6.dp)
                ).border(
                    0.7.dp,
                    color.copy(alpha = if (isDark) 0.38f else 0.14f),
                    RoundedCornerShape(6.dp)
                ).padding(horizontal = 6.dp, vertical = 3.dp),
                color = color,
                fontSize = 11.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.SemiBold,
                style = TextStyle(
                    platformStyle = PlatformTextStyle(includeFontPadding = false)
                ),
                maxLines = 1
            )
            if (node.kind != TrajectoryNodeKind.INPUT && node.kind != TrajectoryNodeKind.ASSISTANT) {
                Text(
                    node.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (node.subtitle.isNotEmpty()) {
                Text(
                    node.subtitle.replace('\n', ' '),
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            Text(
                "#${formatSequence(node.startSequence)}",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )
            androidx.compose.material3.Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                modifier = Modifier.size(10.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)
            )
        }
    }
}

internal fun trajectoryColor(kind: TrajectoryNodeKind, isDark: Boolean): Color = when (kind) {
    TrajectoryNodeKind.INPUT -> DshColors.Ocean
    TrajectoryNodeKind.CONTEXT -> if (isDark) Color(0xFF30D158) else Color(0xFF34C759)
    TrajectoryNodeKind.REQUEST -> Color(0xFF8E8E93)
    TrajectoryNodeKind.ASSISTANT -> DshColors.Purple
    TrajectoryNodeKind.TOOL, TrajectoryNodeKind.SUBTOOL -> DshColors.Orange
}

internal fun trajectoryLabel(kind: TrajectoryNodeKind): String = when (kind) {
    TrajectoryNodeKind.INPUT -> "USER"
    TrajectoryNodeKind.CONTEXT -> "CONTEXT"
    TrajectoryNodeKind.REQUEST -> "REQUEST"
    TrajectoryNodeKind.ASSISTANT -> "ASSISTANT"
    TrajectoryNodeKind.TOOL -> "TOOL"
    TrajectoryNodeKind.SUBTOOL -> "SUBTOOL"
}

private fun trajectoryLaneY(kind: TrajectoryNodeKind): Int = when (kind) {
    TrajectoryNodeKind.INPUT -> 6
    TrajectoryNodeKind.CONTEXT, TrajectoryNodeKind.REQUEST, TrajectoryNodeKind.ASSISTANT -> 25
    TrajectoryNodeKind.TOOL, TrajectoryNodeKind.SUBTOOL -> 44
}

private fun formatSequence(sequence: Int): String =
    NumberFormat.getIntegerInstance(Locale.US).format(sequence)
