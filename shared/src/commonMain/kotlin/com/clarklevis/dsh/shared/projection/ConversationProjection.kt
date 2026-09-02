package com.clarklevis.dsh.shared.projection

import com.clarklevis.dsh.shared.protocol.GatewayEvent
import com.clarklevis.dsh.shared.protocol.GatewayImageAttachment
import com.clarklevis.dsh.shared.protocol.SessionEvent
import kotlinx.serialization.Serializable

@Serializable
enum class ConversationItemKind { USER, CONTEXT, ASSISTANT, REASONING, TOOL, JSON_TOOL, TOOL_RESULT, STATUS, SYSTEM }

@Serializable
data class ConversationProjectionLabels(
    val userMessage: String = "You",
    /** 已包含末尾分隔符，例如 `Context · `；事件 source 会在 KMP 内追加。 */
    val context: String = "Context · ",
    val streamingAssistant: String = "DeepSeek",
    val streamingReasoning: String = "Think",
    val finalAssistant: String = "DeepSeek",
    val finalReasoning: String = "Think",
    val assemblingTool: String = "Tool",
    val toolResultDone: String = "Tool result",
    val toolResultFailed: String = "Tool failed",
    val commandRunning: String = "Running…",
    val commandCompacting: String = "Compacting…",
    val commandCompleted: String = "Completed",
    val commandFailed: String = "Failed",
    val compactedHistory: String = "Compacted {items} history items (~{tokens} tokens)"
)

@Serializable
data class ConversationItem(
    val id: String,
    val kind: ConversationItemKind,
    val title: String,
    val text: String,
    val images: List<GatewayImageAttachment> = emptyList(),
    val isError: Boolean = false,
    val epochSeconds: Double
)

/** 高频 token 流只发送有序操作，不重复发送已经累计的完整消息文本。 */
@Serializable
data class ConversationProjectionOperation(
    val kind: String,
    val item: ConversationItem? = null,
    val itemId: String? = null,
    val delta: String? = null,
    val epochSeconds: Double? = null
)

class ConversationProjector(
    private val labels: ConversationProjectionLabels = ConversationProjectionLabels()
) {
    private val mutableItems = mutableListOf<ConversationItem>()
    private val streamIndexes = mutableMapOf<String, Int>()
    private val finalizedKeys = mutableSetOf<String>()
    private val commandItemIds = mutableMapOf<String, String>()
    private val compactionCommandIds = mutableMapOf<String, String>()

    val items: List<ConversationItem> get() = mutableItems.toList()
    var lastSequence: Int = -1
        private set

    fun reset() {
        mutableItems.clear()
        streamIndexes.clear()
        finalizedKeys.clear()
        commandItemIds.clear()
        compactionCommandIds.clear()
        lastSequence = -1
    }

    fun rebuild(events: List<SessionEvent>) {
        reset()
        fold(events)
    }

    fun fold(events: List<SessionEvent>) {
        foldWithOperations(events)
    }

    fun foldWithOperations(events: List<SessionEvent>): List<ConversationProjectionOperation> {
        val operations = mutableListOf<ConversationProjectionOperation>()
        events.forEach { record ->
            fold(record, operations)
            lastSequence = maxOf(lastSequence, record.seq)
        }
        return operations
    }

    private fun fold(
        record: SessionEvent,
        operations: MutableList<ConversationProjectionOperation>
    ) {
        val event = record.event
        val key = "${event.turn ?: -1}-${event.step ?: -1}"
        val date = normalizeEpoch(record.time)
        when {
            event.type == "user/message" && (event.source == null || event.source == "user") -> {
                if (!event.text.isNullOrEmpty() || !event.images.isNullOrEmpty()) {
                    insert(ConversationItem(
                        id = eventId(record),
                        kind = ConversationItemKind.USER,
                        title = labels.userMessage,
                        text = event.text.orEmpty(),
                        images = event.images.orEmpty(),
                        epochSeconds = date
                    ), operations)
                }
            }
            event.type == "user/message" && !event.text.isNullOrEmpty() -> insert(ConversationItem(
                id = eventId(record),
                kind = ConversationItemKind.CONTEXT,
                title = labels.context + contextSourceName(event),
                text = event.text,
                images = event.images.orEmpty(),
                epochSeconds = date
            ), operations)
            event.type == "assistant/chunk" && event.chunkType == "text-delta" && key !in finalizedKeys ->
                appendStream("stream-text-$key", "text-$key", ConversationItemKind.ASSISTANT, labels.streamingAssistant, event.text.orEmpty(), date, operations)
            event.type == "assistant/chunk" && event.chunkType == "reasoning-delta" && key !in finalizedKeys ->
                appendStream("stream-reason-$key", "reason-$key", ConversationItemKind.REASONING, labels.streamingReasoning, event.text.orEmpty(), date, operations)
            event.type == "assistant/chunk" && event.chunkType == "tool-call-delta" && key !in finalizedKeys -> {
                val toolKey = event.tool?.id ?: key
                val name = event.tool?.name ?: labels.assemblingTool
                val kind = if (name.equals("run_code", ignoreCase = true)) ConversationItemKind.JSON_TOOL else ConversationItemKind.TOOL
                appendStream("stream-tool-$toolKey", "tool-$toolKey", kind, name, event.tool?.argumentsDelta.orEmpty(), date, operations)
            }
            event.type == "assistant/message" -> {
                finalizedKeys += key
                val finalReasoning = event.reasoning?.takeIf(String::isNotEmpty)?.let { reasoning ->
                    ConversationItem(
                        "${eventId(record)}-reason",
                        ConversationItemKind.REASONING,
                        labels.finalReasoning,
                        reasoning,
                        epochSeconds = date
                    )
                }
                val finalAssistant = if (!event.text.isNullOrEmpty() || !event.images.isNullOrEmpty()) {
                    ConversationItem(
                        eventId(record),
                        ConversationItemKind.ASSISTANT,
                        labels.finalAssistant,
                        event.text.orEmpty(),
                        event.images.orEmpty(),
                        epochSeconds = date
                    )
                } else {
                    null
                }

                if (finalReasoning != null && streamIndexes["reason-$key"] == null) {
                    // A final-only reasoning payload must still precede the
                    // answer. Since insert operations append, rebuild this
                    // uncommon pair in order instead of leaving reasoning
                    // below an already-streamed answer.
                    removeStream("text-$key", operations)
                    insert(finalReasoning, operations)
                    finalAssistant?.let { insert(it, operations) }
                } else {
                    // Keep a streamed response's identity through finalization.
                    // Platform renderers own their parser/source by item id; a
                    // remove+insert here tears down that source before its final
                    // buffered snapshot can be rendered. Replacing in place also
                    // avoids a structural list update at the end of every reply.
                    finalizeStream("reason-$key", finalReasoning, operations)
                    finalizeStream("text-$key", finalAssistant, operations)
                }
            }
            event.type == "tool/call" -> {
                val name = event.name ?: "Tool Call"
                val kind = if (name.equals("run_code", ignoreCase = true)) ConversationItemKind.JSON_TOOL else ConversationItemKind.TOOL
                insert(ConversationItem(eventId(record), kind, name, event.arguments?.jsonDisplayText().orEmpty(), epochSeconds = date), operations)
            }
            event.type == "tool/result" -> insert(ConversationItem(
                id = eventId(record),
                kind = ConversationItemKind.TOOL_RESULT,
                title = if (event.isError == true) labels.toolResultFailed else labels.toolResultDone,
                text = event.preview.orEmpty(),
                isError = event.isError == true,
                epochSeconds = date
            ), operations)
            event.type == "command/run" -> commandStarted(record, date, operations)
            event.type == "compaction/start" -> compactionStarted(record, date, operations)
            event.type == "compaction/summary" -> compactionSummarized(record, date, operations)
            event.type == "compaction/end" -> compactionEnded(record, date, operations)
            event.type == "command/done" -> commandFinished(record, date, operations)
        }
    }

    private fun commandStarted(
        record: SessionEvent,
        date: Double,
        operations: MutableList<ConversationProjectionOperation>
    ) {
        val event = record.event
        val commandId = event.commandId ?: event.raw?.get("commandId")?.stringValue ?: return
        val itemId = "${record.sessionId}-command-$commandId"
        commandItemIds[commandId] = itemId
        insert(
            ConversationItem(
                id = itemId,
                kind = ConversationItemKind.STATUS,
                title = event.name ?: event.raw?.get("name")?.stringValue ?: "command",
                text = labels.commandRunning,
                epochSeconds = date
            ),
            operations
        )
    }

    private fun compactionStarted(
        record: SessionEvent,
        date: Double,
        operations: MutableList<ConversationProjectionOperation>
    ) {
        val event = record.event
        val compactionId = event.compactionId ?: event.raw?.get("compactionId")?.stringValue
        val commandId = event.sourceCommandId ?: event.raw?.get("sourceCommandId")?.stringValue
        if (compactionId != null && commandId != null) compactionCommandIds[compactionId] = commandId
        updateCommand(record, commandId, date, operations) { old ->
            old.copy(title = old.title.ifBlank { "compact" }, text = labels.commandCompacting, epochSeconds = date)
        }
    }

    private fun compactionSummarized(
        record: SessionEvent,
        date: Double,
        operations: MutableList<ConversationProjectionOperation>
    ) {
        val event = record.event
        val compactionId = event.compactionId ?: event.raw?.get("compactionId")?.stringValue
        val commandId = event.sourceCommandId
            ?: event.raw?.get("sourceCommandId")?.stringValue
            ?: compactionId?.let(compactionCommandIds::get)
        val itemCount = event.shadowedItemCount
            ?: event.raw?.get("shadowedItemCount")?.doubleValue?.toInt()
        val tokenCount = event.shadowedTokenCount
            ?: event.raw?.get("shadowedTokenCount")?.doubleValue?.toInt()
        val summary = if (itemCount != null && tokenCount != null) {
            labels.compactedHistory
                .replace("{items}", itemCount.toString())
                .replace("{tokens}", tokenCount.toString())
        } else {
            labels.commandCompleted
        }
        updateCommand(record, commandId, date, operations) { old ->
            old.copy(title = old.title.ifBlank { "compact" }, text = summary, epochSeconds = date)
        }
    }

    private fun compactionEnded(
        record: SessionEvent,
        date: Double,
        operations: MutableList<ConversationProjectionOperation>
    ) {
        val event = record.event
        val error = event.error ?: event.raw?.get("error")?.stringValue
        if (error == null) return
        val compactionId = event.compactionId ?: event.raw?.get("compactionId")?.stringValue
        val commandId = event.sourceCommandId
            ?: event.raw?.get("sourceCommandId")?.stringValue
            ?: compactionId?.let(compactionCommandIds::get)
        updateCommand(record, commandId, date, operations) { old ->
            old.copy(text = error, isError = true, epochSeconds = date)
        }
    }

    private fun commandFinished(
        record: SessionEvent,
        date: Double,
        operations: MutableList<ConversationProjectionOperation>
    ) {
        val event = record.event
        val commandId = event.commandId ?: event.raw?.get("commandId")?.stringValue
        val outcome = event.outcome ?: event.raw?.get("outcome")?.stringValue
        val failed = event.isError == true || outcome == "error" || outcome == "failed" || outcome == "failure"
        updateCommand(record, commandId, date, operations) { old ->
            val hasCompactionSummary = old.title == "compact" &&
                old.text != labels.commandRunning && old.text != labels.commandCompacting
            old.copy(
                text = if (hasCompactionSummary && !failed) old.text
                    else event.text?.takeIf(String::isNotBlank)
                        ?: if (failed) labels.commandFailed else labels.commandCompleted,
                isError = failed,
                epochSeconds = date
            )
        }
    }

    private fun updateCommand(
        record: SessionEvent,
        commandId: String?,
        date: Double,
        operations: MutableList<ConversationProjectionOperation>,
        transform: (ConversationItem) -> ConversationItem
    ) {
        val resolvedCommandId = commandId ?: return
        val itemId = commandItemIds[resolvedCommandId] ?: "${record.sessionId}-command-$resolvedCommandId"
        val index = mutableItems.indexOfFirst { it.id == itemId }
        if (index < 0) {
            commandItemIds[resolvedCommandId] = itemId
            val fallback = ConversationItem(
                id = itemId,
                kind = ConversationItemKind.STATUS,
                title = "compact",
                text = labels.commandRunning,
                epochSeconds = date
            )
            insert(transform(fallback), operations)
            return
        }
        val updated = transform(mutableItems[index])
        mutableItems[index] = updated
        operations += ConversationProjectionOperation(kind = "replace", item = updated, itemId = itemId)
    }

    private fun insert(
        item: ConversationItem,
        operations: MutableList<ConversationProjectionOperation>
    ) {
        mutableItems += item
        operations += ConversationProjectionOperation(kind = "insert", item = item)
    }

    private fun appendStream(
        id: String,
        key: String,
        kind: ConversationItemKind,
        title: String,
        delta: String,
        date: Double,
        operations: MutableList<ConversationProjectionOperation>
    ) {
        if (delta.isEmpty()) return
        val index = streamIndexes[key]
        if (index != null) {
            val old = mutableItems[index]
            mutableItems[index] = old.copy(text = old.text + delta, epochSeconds = date)
            operations += ConversationProjectionOperation(
                kind = "append-text",
                itemId = old.id,
                delta = delta,
                epochSeconds = date
            )
        } else {
            streamIndexes[key] = mutableItems.size
            insert(ConversationItem(id, kind, title, delta, epochSeconds = date), operations)
        }
    }

    private fun removeStream(
        key: String,
        operations: MutableList<ConversationProjectionOperation>
    ) {
        val index = streamIndexes.remove(key) ?: return
        val removed = mutableItems.removeAt(index)
        operations += ConversationProjectionOperation(kind = "remove", itemId = removed.id)
        streamIndexes.entries.forEach { entry -> if (entry.value > index) entry.setValue(entry.value - 1) }
    }

    private fun finalizeStream(
        key: String,
        finalItem: ConversationItem?,
        operations: MutableList<ConversationProjectionOperation>
    ) {
        val index = streamIndexes.remove(key)
        if (index == null) {
            finalItem?.let { insert(it, operations) }
            return
        }
        if (finalItem == null) {
            val removed = mutableItems.removeAt(index)
            operations += ConversationProjectionOperation(kind = "remove", itemId = removed.id)
            streamIndexes.entries.forEach { entry ->
                if (entry.value > index) entry.setValue(entry.value - 1)
            }
            return
        }

        val stableFinalItem = finalItem.copy(id = mutableItems[index].id)
        mutableItems[index] = stableFinalItem
        operations += ConversationProjectionOperation(
            kind = "replace",
            item = stableFinalItem,
            itemId = stableFinalItem.id
        )
    }

    companion object {
        fun firstIndexAfter(sequence: Int, events: List<SessionEvent>): Int {
            var low = 0
            var high = events.size
            while (low < high) {
                val middle = (low + high) / 2
                if (events[middle].seq <= sequence) low = middle + 1 else high = middle
            }
            return low
        }

        fun make(events: List<SessionEvent>, labels: ConversationProjectionLabels = ConversationProjectionLabels()): List<ConversationItem> =
            ConversationProjector(labels).apply { rebuild(events) }.items
    }
}

data class ConversationHistoryRebase(
    val events: List<SessionEvent>,
    val projector: ConversationProjector
) {
    fun appendingLiveTail(latest: List<SessionEvent>): ConversationHistoryRebase {
        val start = ConversationProjector.firstIndexAfter(projector.lastSequence, latest)
        if (start >= latest.size) return this
        val tail = latest.subList(start, latest.size)
        projector.fold(tail)
        return copy(events = events + tail)
    }

    companion object {
        fun build(history: List<SessionEvent>, current: List<SessionEvent>): ConversationHistoryRebase {
            val records = history.associateBy(SessionEvent::seq).toMutableMap().apply {
                current.forEach { put(it.seq, it) }
            }
            val merged = records.values.sortedBy(SessionEvent::seq)
            return ConversationHistoryRebase(merged, ConversationProjector().apply { rebuild(merged) })
        }
    }
}

private fun contextSourceName(event: GatewayEvent): String =
    event.raw?.get("source")?.get("plugin")?.stringValue?.takeIf(String::isNotEmpty)
        ?: event.source
        ?: "Context"

private fun eventId(record: SessionEvent): String = "${record.sessionId}-${record.seq}"
private fun normalizeEpoch(value: Double): Double = if (value > 10_000_000_000) value / 1_000 else value
