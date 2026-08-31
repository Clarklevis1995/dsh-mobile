package com.clarklevis.dsh.android.ui

import com.clarklevis.dsh.shared.protocol.GatewayImageAttachment
import com.clarklevis.dsh.shared.projection.ConversationItem
import com.clarklevis.dsh.shared.projection.ConversationItemKind
import kotlin.math.roundToInt

internal sealed interface ConversationDisplayEntry {
    val id: String
    val contentType: ConversationDisplayContentType

    data class Message(val item: ConversationItem) : ConversationDisplayEntry {
        override val id: String = item.id
        override val contentType: ConversationDisplayContentType = when (item.kind) {
            ConversationItemKind.USER -> ConversationDisplayContentType.USER
            ConversationItemKind.ASSISTANT -> ConversationDisplayContentType.ASSISTANT
            ConversationItemKind.STATUS -> ConversationDisplayContentType.STATUS
            else -> ConversationDisplayContentType.SYSTEM
        }
    }

    data class Process(val group: ConversationProcessGroup) : ConversationDisplayEntry {
        override val id: String = group.id
        override val contentType: ConversationDisplayContentType =
            ConversationDisplayContentType.PROCESS
    }
}

internal enum class ConversationDisplayContentType {
    USER,
    ASSISTANT,
    ASSISTANT_HEADER,
    ASSISTANT_MARKDOWN,
    ASSISTANT_FOOTER,
    PROCESS,
    STATUS,
    SYSTEM
}

internal sealed interface ConversationTimelineEntry {
    val id: String
    val contentType: ConversationDisplayContentType
    val images: List<GatewayImageAttachment>

    data class Display(val entry: ConversationDisplayEntry) : ConversationTimelineEntry {
        override val id: String = entry.id
        override val contentType: ConversationDisplayContentType = entry.contentType
        override val images: List<GatewayImageAttachment> =
            (entry as? ConversationDisplayEntry.Message)?.item?.images.orEmpty()
    }

    data class AssistantHeader(val item: ConversationItem) : ConversationTimelineEntry {
        override val id: String = "assistant:${item.id}:header"
        override val contentType = ConversationDisplayContentType.ASSISTANT_HEADER
        override val images = item.images
    }

    data class AssistantMarkdown(
        val messageId: String,
        val blockIndex: Int,
        val markdown: String
    ) : ConversationTimelineEntry {
        override val id: String = "assistant:$messageId:markdown:$blockIndex"
        override val contentType = ConversationDisplayContentType.ASSISTANT_MARKDOWN
        override val images = emptyList<GatewayImageAttachment>()
    }

    data class AssistantFooter(
        val messageId: String,
        val text: String
    ) : ConversationTimelineEntry {
        override val id: String = "assistant:$messageId:footer"
        override val contentType = ConversationDisplayContentType.ASSISTANT_FOOTER
        override val images = emptyList<GatewayImageAttachment>()
    }
}

internal fun makeConversationTimelineEntries(
    entries: List<ConversationDisplayEntry>
): List<ConversationTimelineEntry> = buildList {
    entries.forEach { entry ->
        val item = (entry as? ConversationDisplayEntry.Message)?.item
        if (
            item?.kind != ConversationItemKind.ASSISTANT ||
            item.text.isEmpty()
        ) {
            add(ConversationTimelineEntry.Display(entry))
            return@forEach
        }

        add(ConversationTimelineEntry.AssistantHeader(item))
        splitMarkdownForLazyLayout(item.text).forEachIndexed { index, markdown ->
            add(
                ConversationTimelineEntry.AssistantMarkdown(
                    messageId = item.id,
                    blockIndex = index,
                    markdown = markdown
                )
            )
        }
        if (!isStreamingConversationItem(item)) {
            add(ConversationTimelineEntry.AssistantFooter(item.id, item.text))
        }
    }
}

/**
 * 只在 Markdown 块边界拆分，围栏代码、表格和列表项自身不会被截断。这样 LazyColumn
 * 无需在用户气泡出现的同一帧测量整篇长回复。
 */
internal fun splitMarkdownForLazyLayout(
    markdown: String,
    targetCharacters: Int = 320
): List<String> {
    if (markdown.length <= targetCharacters) return listOf(markdown)

    val logicalBlocks = mutableListOf<String>()
    val currentBlock = StringBuilder()
    var fenceMarker: String? = null

    fun flushBlock() {
        currentBlock.toString().trimEnd().takeIf(String::isNotEmpty)?.let(logicalBlocks::add)
        currentBlock.clear()
    }

    markdown.lineSequence().forEach { line ->
        val trimmed = line.trimStart()
        val openingFence = when {
            trimmed.startsWith("```") -> "```"
            trimmed.startsWith("~~~") -> "~~~"
            else -> null
        }
        if (openingFence != null) {
            fenceMarker = if (fenceMarker == null) openingFence else {
                fenceMarker.takeUnless { marker -> marker == openingFence }
            }
        }

        if (line.isBlank() && fenceMarker == null) {
            flushBlock()
        } else {
            if (currentBlock.isNotEmpty()) currentBlock.append('\n')
            currentBlock.append(line)
        }
    }
    flushBlock()

    if (logicalBlocks.size <= 1) return listOf(markdown)
    val chunks = mutableListOf<String>()
    val chunk = StringBuilder()
    logicalBlocks.forEach { block ->
        val separatorSize = if (chunk.isEmpty()) 0 else 2
        if (chunk.isNotEmpty() && chunk.length + separatorSize + block.length > targetCharacters) {
            chunks += chunk.toString()
            chunk.clear()
        }
        if (chunk.isNotEmpty()) chunk.append("\n\n")
        chunk.append(block)
    }
    if (chunk.isNotEmpty()) chunks += chunk.toString()
    return chunks.ifEmpty { listOf(markdown) }
}

internal data class ConversationProcessGroup(
    val id: String,
    val items: List<ConversationItem>
) {
    val contexts: List<ConversationItem> = items.filter { it.kind == ConversationItemKind.CONTEXT }

    val reasoningText: String = items.asSequence()
        .filter { it.kind == ConversationItemKind.REASONING }
        .map(ConversationItem::text)
        .filter(String::isNotEmpty)
        .joinToString("\n\n")

    val tools: List<ConversationProcessTool> = pairProcessTools(items)

    val durationSeconds: Double
        get() = when {
            items.size < 2 -> 0.0
            else -> (items.last().epochSeconds - items.first().epochSeconds).coerceAtLeast(0.0)
        }

    val title: String
        get() {
            if (contexts.isNotEmpty() && tools.isEmpty() && reasoningText.isEmpty()) {
                return if (contexts.size == 1) "上下文" else "${contexts.size} 项上下文"
            }
            val base = when {
                durationSeconds >= 60.0 -> {
                    val totalSeconds = durationSeconds.toInt()
                    "耗时 ${totalSeconds / 60} 分钟 ${totalSeconds % 60} 秒"
                }
                durationSeconds >= 1.0 -> "耗时 ${durationSeconds.roundToInt()} 秒"
                else -> "思考过程"
            }
            val details = buildList {
                if (contexts.isNotEmpty()) add("${contexts.size} 项上下文")
                if (tools.isNotEmpty()) add("${tools.size} 次工具调用")
            }
            return if (details.isEmpty()) base else "$base · ${details.joinToString(" · ")}"
        }
}

internal data class ConversationProcessTool(
    val id: String,
    val call: ConversationItem?,
    val result: ConversationItem?
)

internal fun makeConversationDisplayEntries(
    items: List<ConversationItem>
): List<ConversationDisplayEntry> {
    val result = mutableListOf<ConversationDisplayEntry>()
    val processItems = mutableListOf<ConversationItem>()

    fun flushProcess() {
        val first = processItems.firstOrNull() ?: return
        result += ConversationDisplayEntry.Process(
            ConversationProcessGroup(
                id = "process-${first.id}",
                items = processItems.toList()
            )
        )
        processItems.clear()
    }

    items.forEach { item ->
        when (item.kind) {
            ConversationItemKind.CONTEXT,
            ConversationItemKind.REASONING,
            ConversationItemKind.TOOL,
            ConversationItemKind.JSON_TOOL,
            ConversationItemKind.TOOL_RESULT -> processItems += item

            ConversationItemKind.USER,
            ConversationItemKind.ASSISTANT,
            ConversationItemKind.STATUS,
            ConversationItemKind.SYSTEM -> {
                flushProcess()
                result += ConversationDisplayEntry.Message(item)
            }
        }
    }
    flushProcess()
    return result
}

private fun pairProcessTools(
    items: List<ConversationItem>
): List<ConversationProcessTool> {
    val tools = mutableListOf<ConversationProcessTool>()
    items.forEach { item ->
        when (item.kind) {
            ConversationItemKind.TOOL,
            ConversationItemKind.JSON_TOOL -> tools += ConversationProcessTool(
                id = item.id,
                call = item,
                result = null
            )

            ConversationItemKind.TOOL_RESULT -> {
                val pendingIndex = tools.indexOfFirst { it.result == null }
                if (pendingIndex >= 0) {
                    val pending = tools[pendingIndex]
                    tools[pendingIndex] = pending.copy(result = item)
                } else {
                    tools += ConversationProcessTool(
                        id = item.id,
                        call = null,
                        result = item
                    )
                }
            }

            else -> Unit
        }
    }
    return tools
}
