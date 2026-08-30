package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clarklevis.dsh.shared.projection.RequestTokenUsage
import com.clarklevis.dsh.shared.projection.TrajectoryNode
import com.clarklevis.dsh.shared.projection.TrajectoryNodeKind
import com.clarklevis.dsh.shared.protocol.JsonValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class GenericDetailTab(val title: String) {
    SUMMARY("摘要"), PREVIEW("预览"), RAW("原始")
}

private enum class RequestDetailTab(val title: String) {
    SUMMARY("摘要"), OPTIONS("选项"), USAGE("用量"), TIMING("耗时")
}

private enum class ToolDetailTab(val title: String) {
    SUMMARY("摘要"), PAYLOAD("参数"), RESULT("结果"), SCHEMA("Schema"), TIMING("耗时")
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun TrajectoryEventDetailSheet(
    node: TrajectoryNode,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        modifier = Modifier.fillMaxHeight(),
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
        dragHandle = null
    ) {
        Column(Modifier.fillMaxSize().testTag("trajectory-detail-sheet")) {
            DetailSheetHeader(node.title, onDismiss)
            when (node.kind) {
                TrajectoryNodeKind.REQUEST -> RequestDetail(node)
                TrajectoryNodeKind.TOOL, TrajectoryNodeKind.SUBTOOL -> ToolDetail(node)
                else -> GenericDetail(node)
            }
        }
    }
}

@Composable
private fun DetailSheetHeader(title: String, onDismiss: () -> Unit) {
    val buttonShape = RoundedCornerShape(22.dp)
    Box(
        modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            title,
            modifier = Modifier.padding(horizontal = 72.dp),
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Button(
            onClick = onDismiss,
            modifier = Modifier.align(Alignment.CenterEnd).height(44.dp).shadow(
                elevation = 10.dp,
                shape = buttonShape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.07f),
                spotColor = Color.Black.copy(alpha = 0.10f)
            ),
            shape = buttonShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = Color(0xFF111318)
            ),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
        ) {
            Text("完成", fontSize = 15.sp)
        }
    }
}

@Composable
private fun GenericDetail(node: TrajectoryNode) {
    var tab by remember(node.id) { mutableStateOf(GenericDetailTab.SUMMARY) }
    DetailTabs(GenericDetailTab.entries.toList(), tab, { it.title }) { tab = it }
    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
    DetailScroll {
        when (tab) {
            GenericDetailTab.SUMMARY -> GenericSummary(node)
            GenericDetailTab.PREVIEW -> GenericPreview(node)
            GenericDetailTab.RAW -> GenericRaw(node)
        }
    }
}

@Composable
private fun RequestDetail(node: TrajectoryNode) {
    var tab by remember(node.id) { mutableStateOf(RequestDetailTab.SUMMARY) }
    DetailTabs(RequestDetailTab.entries.toList(), tab, { it.title }) { tab = it }
    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
    DetailScroll {
        when (tab) {
            RequestDetailTab.SUMMARY -> RequestSummary(node)
            RequestDetailTab.OPTIONS -> JsonOrUnavailable(node.request?.options, "没有记录模型选项")
            RequestDetailTab.USAGE -> RequestUsage(node)
            RequestDetailTab.TIMING -> RequestTiming(node)
        }
    }
}

@Composable
private fun ToolDetail(node: TrajectoryNode) {
    var tab by remember(node.id) { mutableStateOf(ToolDetailTab.SUMMARY) }
    val tabs = remember(node.kind) {
        ToolDetailTab.entries.filter { node.kind == TrajectoryNodeKind.TOOL || it != ToolDetailTab.SCHEMA }
    }
    DetailTabs(tabs, tab, { it.title }) { tab = it }
    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
    DetailScroll {
        when (tab) {
            ToolDetailTab.SUMMARY -> ToolSummary(node)
            ToolDetailTab.PAYLOAD -> JsonOrUnavailable(toolArguments(node), "没有参数")
            ToolDetailTab.RESULT -> ToolResult(node)
            ToolDetailTab.SCHEMA -> ToolSchema(node)
            ToolDetailTab.TIMING -> ToolTiming(node)
        }
    }
}

@Composable
private fun <T> DetailTabs(
    tabs: List<T>,
    selected: T,
    title: (T) -> String,
    onSelect: (T) -> Unit
) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        tabs.forEach { tab ->
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 2.dp)
                    .testTag("trajectory-detail-tab-${title(tab)}")
                    .clickable { onSelect(tab) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    title(tab),
                    color = if (tab == selected) DshColors.Ocean else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                    fontSize = 14.sp,
                    fontWeight = if (tab == selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1
                )
                Box(
                    Modifier.fillMaxWidth().height(2.dp).background(
                        if (tab == selected) DshColors.Ocean else Color.Transparent,
                        RoundedCornerShape(1.dp)
                    )
                )
            }
        }
    }
}

@Composable
private fun DetailScroll(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        content()
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun GenericSummary(node: TrajectoryNode) {
    val usage = usageSummary(node)
    DetailRow("Source", node.records.firstNotNullOfOrNull { it.event.turn }?.let { "Request #$it" } ?: "#${node.startSequence}")
    DetailRow("Status", statusText(node))
    usage?.output?.let { DetailRow("Tokens", "$it tok") }
    usage?.reasoning?.let { DetailRow("Reasoning", "$it tok", true) }
    usage?.content?.let { DetailRow("Content", "$it tok", true) }
    DetailDivider()
    DetailSectionTitle("Preview")
    GenericPreview(node)
    DetailDivider()
    DetailSectionTitle("Request Timing")
    RequestTiming(node)
}

@Composable
private fun GenericPreview(node: TrajectoryNode) {
    when (node.kind) {
        TrajectoryNodeKind.INPUT -> PreviewSection("Message", node.subtitle)
        TrajectoryNodeKind.CONTEXT -> PreviewSection("Context", node.subtitle)
        TrajectoryNodeKind.ASSISTANT -> {
            val final = node.records.lastOrNull { it.event.type == "assistant/message" }?.event
            val reasoning = final?.reasoning.orEmpty()
            val content = final?.text.orEmpty()
            if (reasoning.isNotEmpty()) PreviewSection("Thinking", reasoning)
            if (content.isNotEmpty()) PreviewSection("Content", content)
            if (reasoning.isEmpty() && content.isEmpty()) PreviewSection("Content", node.subtitle)
        }
        else -> PreviewSection(node.title, node.subtitle)
    }
}

@Composable
private fun GenericRaw(node: TrajectoryNode) {
    when (node.kind) {
        TrajectoryNodeKind.ASSISTANT -> {
            val final = node.records.lastOrNull { it.event.type == "assistant/message" }?.event
            var index = 1
            final?.reasoning?.takeIf(String::isNotEmpty)?.let { RawBlock(index++, "thinking", it) }
            final?.text?.takeIf(String::isNotEmpty)?.let { RawBlock(index++, "text", it) }
            final?.toolCalls.orEmpty().forEach { call ->
                RawBlock(index++, "tool-call ${call.name}", call.arguments?.jsonDisplayText().orEmpty())
            }
            if (index == 1) RawBlock(1, "text", node.subtitle)
        }
        TrajectoryNodeKind.CONTEXT -> RawBlock(
            1,
            "context-event",
            node.records.firstOrNull()?.event?.raw?.jsonDisplayText() ?: node.subtitle
        )
        else -> RawBlock(1, "text", node.subtitle)
    }
}

@Composable
private fun RequestSummary(node: TrajectoryNode) {
    val request = node.request
    DetailRow("Status", statusText(node))
    DetailRow("Provider", request?.provider ?: "—")
    DetailRow("Model", request?.model ?: "—")
    DetailRow("Tool calls", "${request?.toolCalls ?: 0}")
    DetailRow("Subtool calls", "${request?.subtoolCalls ?: 0}")
    DetailRow("Result", if (isCompleted(node)) "Assistant Message" else "—")
    DetailDivider()
    DetailSectionTitle("Options")
    JsonOrUnavailable(request?.options, "没有记录模型选项")
    DetailDivider()
    DetailSectionTitle("Usage")
    RequestUsage(node)
    DetailDivider()
    DetailSectionTitle("Timing")
    RequestTiming(node)
}

@Composable
private fun RequestUsage(node: TrajectoryNode) {
    TokenUsageSection("This request", node.request?.usage)
    Spacer(Modifier.height(2.dp))
    TokenUsageSection("Session cumulative", node.request?.cumulativeUsage)
}

@Composable
private fun TokenUsageSection(title: String, usage: RequestTokenUsage?) {
    DetailSectionTitle(title)
    DetailRow("Input", "${usage?.totalInput ?: 0} tok")
    DetailRow("Cached", "${usage?.cachedInput ?: 0} tok", true)
    DetailRow("Other", "${usage?.uncachedInput ?: 0} tok", true)
    DetailRow("Output", "${usage?.output ?: 0} tok")
    DetailRow("Reasoning", "${usage?.reasoning ?: 0} tok", true)
    DetailRow("Content", "${usage?.content ?: 0} tok", true)
}

@Composable
private fun RequestTiming(node: TrajectoryNode) {
    DetailRow("Started", startedText(node))
    DetailRow("Total duration", durationText(node))
    firstTokenSeconds(node)?.let { first ->
        DetailRow("TTFT", formatInterval(first - node.startEpochSeconds))
        val generation = (node.endEpochSeconds - first).coerceAtLeast(0.0)
        DetailRow("Generation", formatInterval(generation))
        val output = node.request?.usage?.output ?: usageSummary(node)?.output
        if (output != null && generation > 0.0) {
            DetailRow("Throughput", String.format(Locale.US, "%.1f tok/s", output / generation))
        }
    }
}

@Composable
private fun ToolSummary(node: TrajectoryNode) {
    DetailRow("Hierarchy", node.tool?.hierarchy ?: if (node.kind == TrajectoryNodeKind.SUBTOOL) "Tool" else "Assistant Message")
    DetailRow("Status", statusText(node))
    DetailDivider()
    DetailSectionTitle("Payload")
    JsonOrUnavailable(toolArguments(node), "没有参数")
    DetailDivider()
    DetailSectionTitle("Result")
    ToolResult(node)
    if (node.kind == TrajectoryNodeKind.TOOL) {
        DetailDivider()
        DetailSectionTitle("Schema")
        ToolSchema(node)
    }
    DetailDivider()
    DetailSectionTitle("Timing")
    ToolTiming(node)
}

@Composable
private fun ToolResult(node: TrajectoryNode) {
    val result = toolResult(node)
    if (result.isEmpty()) {
        Unavailable(if (isCompleted(node)) "(no output)" else "等待工具返回…")
    } else {
        SelectionContainer {
            Text(result, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun ToolSchema(node: TrajectoryNode) {
    val schema = node.tool?.schema
    if (schema == null) {
        Unavailable("没有记录工具 Schema")
        return
    }
    Text(
        schema["name"]?.stringValue ?: node.title,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold
    )
    schema["description"]?.stringValue?.takeIf(String::isNotEmpty)?.let {
        DshMarkdownText(it, Modifier.fillMaxWidth(), compact = true)
    }
    schema["parameters"]?.let {
        DetailSectionTitle("Parameters")
        JsonCode(it)
    }
}

@Composable
private fun ToolTiming(node: TrajectoryNode) {
    DetailRow("Started", startedText(node))
    DetailRow("Duration", durationText(node))
    DetailRow("Timing source", "Session timestamps")
}

@Composable
private fun PreviewSection(title: String, text: String) {
    DetailSectionTitle(title)
    DshMarkdownText(text, Modifier.fillMaxWidth(), compact = title != "Content")
}

@Composable
private fun RawBlock(index: Int, type: String, text: String) {
    Text(
        "Block #$index $type",
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
        fontSize = 14.sp,
        fontFamily = FontFamily.Monospace
    )
    SelectionContainer {
        Text(text, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun JsonOrUnavailable(value: JsonValue?, unavailable: String) {
    if (value == null) Unavailable(unavailable) else JsonCode(value)
}

@Composable
private fun JsonCode(value: JsonValue) {
    DshMarkdownText("```json\n${value.jsonDisplayText()}\n```", Modifier.fillMaxWidth(), compact = true)
}

@Composable
private fun Unavailable(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f), fontSize = 14.sp)
}

@Composable
private fun DetailSectionTitle(title: String) {
    Text(
        title,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun DetailDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
}

@Composable
private fun DetailRow(label: String, value: String, indented: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            modifier = Modifier.width(158.dp).padding(start = if (indented) 16.dp else 0.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
            fontSize = 14.sp
        )
        SelectionContainer {
            Text(value, modifier = Modifier.weight(1f), fontSize = 14.sp)
        }
    }
}

private data class DetailTokenSummary(
    val input: Int?,
    val output: Int?,
    val reasoning: Int?,
    val content: Int?
)

private fun statusText(node: TrajectoryNode): String = when {
    node.records.any { it.event.isError == true } -> "Failed"
    isCompleted(node) -> "Completed"
    else -> "Running"
}

private fun isCompleted(node: TrajectoryNode): Boolean = when (node.kind) {
    TrajectoryNodeKind.INPUT, TrajectoryNodeKind.CONTEXT -> true
    TrajectoryNodeKind.REQUEST, TrajectoryNodeKind.ASSISTANT ->
        node.records.any { it.event.type == "assistant/message" }
    TrajectoryNodeKind.TOOL -> node.records.any { it.event.type == "tool/result" }
    TrajectoryNodeKind.SUBTOOL -> node.records.any { it.event.type == "tool/code-dispatch" }
}

private fun toolArguments(node: TrajectoryNode): JsonValue? = node.records.firstOrNull {
    it.event.type in setOf("tool/call", "tool/code-dispatch-start", "tool/code-dispatch")
}?.event?.arguments

private fun toolResult(node: TrajectoryNode): String = node.records.firstOrNull {
    it.event.type == "tool/result" || it.event.type == "tool/code-dispatch"
}?.event?.preview.orEmpty()

private fun usageSummary(node: TrajectoryNode): DetailTokenSummary? {
    val usage = node.records.asReversed().firstNotNullOfOrNull { it.event.usage ?: it.event.raw?.get("usage") }
        ?: return null
    val input = usage.firstInteger(setOf("inputTokens", "input_tokens", "uncachedInputTokens", "promptTokens"))
    val output = usage.firstInteger(setOf("outputTokens", "output_tokens", "completionTokens"))
    val reasoning = usage.firstInteger(setOf("reasoningTokens", "reasoning_tokens"))
    if (input == null && output == null && reasoning == null) return null
    return DetailTokenSummary(input, output, reasoning, output?.let { (it - (reasoning ?: 0)).coerceAtLeast(0) })
}

private fun firstTokenSeconds(node: TrajectoryNode): Double? = node.records.firstOrNull {
    it.event.chunkType in setOf("reasoning-delta", "text-delta") && !it.event.text.isNullOrEmpty()
}?.time?.let { if (it > 10_000_000_000) it / 1_000 else it }

private fun startedText(node: TrajectoryNode): String = SimpleDateFormat(
    "yyyy-MM-dd HH:mm:ss.SSS",
    Locale.getDefault()
).format(Date((node.startEpochSeconds * 1_000).toLong()))

private fun durationText(node: TrajectoryNode): String = String.format(
    Locale.US,
    "%.2f s",
    (node.endEpochSeconds - node.startEpochSeconds).coerceAtLeast(0.0)
)

private fun formatInterval(seconds: Double): String = if (seconds < 1.0) {
    "${(seconds.coerceAtLeast(0.0) * 1_000).toInt()} ms"
} else {
    String.format(Locale.US, "%.2f s", seconds)
}
