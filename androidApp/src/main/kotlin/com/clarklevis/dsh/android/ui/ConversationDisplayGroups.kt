package com.clarklevis.dsh.android.ui

import com.clarklevis.dsh.shared.projection.ConversationItem
import com.clarklevis.dsh.shared.projection.ConversationItemKind
import kotlin.math.roundToInt

internal sealed interface ConversationDisplayEntry {
    val id: String

    data class Message(val item: ConversationItem) : ConversationDisplayEntry {
        override val id: String = item.id
    }

    data class Process(val group: ConversationProcessGroup) : ConversationDisplayEntry {
        override val id: String = group.id
    }
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
