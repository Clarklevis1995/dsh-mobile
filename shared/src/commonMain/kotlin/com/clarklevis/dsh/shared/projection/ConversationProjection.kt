package com.clarklevis.dsh.shared.projection

import com.clarklevis.dsh.shared.protocol.PresentedFile
import com.clarklevis.dsh.shared.protocol.FinishInfo
import com.clarklevis.dsh.shared.protocol.JsonValue
import com.clarklevis.dsh.shared.protocol.failureText
import com.clarklevis.dsh.shared.protocol.GatewayEvent
import com.clarklevis.dsh.shared.protocol.GatewayImageAttachment
import com.clarklevis.dsh.shared.protocol.toolImages
import com.clarklevis.dsh.shared.protocol.SessionEvent
import com.clarklevis.dsh.shared.sync.AssistantChunk
import com.clarklevis.dsh.shared.sync.expandAssistantStream
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.Serializable

@Serializable
enum class ConversationItemKind { USER, CONTEXT, ASSISTANT, REASONING, TOOL, JSON_TOOL, TOOL_RESULT, GENERATED_IMAGES, STATUS, SYSTEM }

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
    val compactedHistory: String = "Compacted {items} history items (~{tokens} tokens)",
    val interrupted: String = "Generation interrupted",
    val modelAttemptFailed: String = "Model request failed",
    val modelRetrying: String = "Retrying model request",
    val modelRetried: String = "Model request retried",
    val generationFailed: String = "Generation failed",
    val generationCancelled: String = "Generation cancelled",
    val outputTruncated: String = "Output truncated; showing the beginning and end"
)

@Serializable
data class ConversationItem(
    val id: String,
    val kind: ConversationItemKind,
    val title: String,
    val text: String,
    val images: List<GatewayImageAttachment> = emptyList(),
    val isError: Boolean = false,
    val epochSeconds: Double,
    val turn: Int? = null,
    val files: List<PresentedFile> = emptyList(),
    val knownFiles: List<PresentedFile> = emptyList(),
    val textParts: List<com.clarklevis.dsh.shared.protocol.GatewayTextPart>? = null,
    val requestId: String? = null
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
    private val filesByTurn = mutableMapOf<Int, LinkedHashMap<String, PresentedFile>>()
    private val presentedSequences = mutableSetOf<Int>()
    private val toolImagesByCall = mutableMapOf<String, MutableSet<String>>()
    private val injectedImageCalls = mutableMapOf<String, Set<String>>()
    private val imageWindowBySession = mutableMapOf<String, Int>()
    private val streamIndexes = mutableMapOf<String, Int>()
    private val finalizedKeys = mutableSetOf<String>()
    private val commandItemIds = mutableMapOf<String, String>()
    private val compactionCommandIds = mutableMapOf<String, String>()
    private val attemptKeys = mutableMapOf<String, String>()
    private val retryKeys = mutableMapOf<Int, String>()
    private val transientKeys = mutableSetOf<String>()
    private val steeringMessages = linkedMapOf<String, SessionEvent>()
    private val committedUserMessageIds = mutableSetOf<String>()

    val items: List<ConversationItem> get() = mutableItems.toList()
    val hasAssistantChunks: Boolean get() = transientKeys.isNotEmpty()
    var lastSequence: Int = -1
        private set

    fun reset() {
        mutableItems.clear()
        filesByTurn.clear()
        presentedSequences.clear()
        toolImagesByCall.clear()
        injectedImageCalls.clear()
        imageWindowBySession.clear()
        streamIndexes.clear()
        finalizedKeys.clear()
        commandItemIds.clear()
        compactionCommandIds.clear()
        transientKeys.clear()
        attemptKeys.clear()
        retryKeys.clear()
        steeringMessages.clear()
        committedUserMessageIds.clear()
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
            val start = operations.size
            fold(record, operations)
            val turn = record.event.turn
            if (turn != null) {
                val affectedIds = operations.drop(start).mapNotNull { it.item?.takeIf { item ->
                    item.kind == ConversationItemKind.ASSISTANT
                }?.id }.toSet()
                mutableItems.indices.filter { mutableItems[it].id in affectedIds }.forEach { index ->
                    val old = mutableItems[index]
                    if (old.turn != turn) {
                        val updated = old.copy(turn = turn)
                        mutableItems[index] = updated
                        for (operationIndex in start until operations.size) {
                            val operation = operations[operationIndex]
                            if (operation.item?.id == updated.id) operations[operationIndex] = operation.copy(item = updated)
                        }
                    }
                }
                attachFiles(turn, operations)
            }
            lastSequence = maxOf(lastSequence, record.seq)
        }
        return operations
    }

    /** 独立流只改变展示行，绝不推进 lastSequence。 */
    fun foldAssistantChunks(attemptId: String, chunks: List<AssistantChunk>): List<ConversationProjectionOperation> {
        val operations = mutableListOf<ConversationProjectionOperation>()
        chunks.forEach { entry ->
            fun field(name: String) = entry.chunk[name]?.jsonPrimitive?.contentOrNull
            val type = field("type")
            val kind = when (type) {
                "text-delta" -> ConversationItemKind.ASSISTANT
                "reasoning-delta" -> ConversationItemKind.REASONING
                "tool-call-delta" -> ConversationItemKind.TOOL
                else -> return@forEach
            }
            val key = "live-$attemptId-$type-${field("index") ?: field("id") ?: "0"}"
            val title = when (kind) {
                ConversationItemKind.ASSISTANT -> labels.streamingAssistant
                ConversationItemKind.REASONING -> labels.streamingReasoning
                else -> field("name") ?: streamIndexes[key]?.let { mutableItems[it].title } ?: labels.assemblingTool
            }
            transientKeys += key
            appendStream(key, key, kind, title,
                field(if (type == "tool-call-delta") "argumentsDelta" else "text").orEmpty(),
                normalizeEpoch(entry.time), operations)
        }
        return operations
    }

    fun clearAssistantChunks(): List<ConversationProjectionOperation> {
        val operations = mutableListOf<ConversationProjectionOperation>()
        transientKeys.forEach { removeStream(it, operations) }
        transientKeys.clear()
        return operations
    }

    /** 控制流中的 steering 是已提交的用户消息，但尚未进入持久事件流。 */
    fun replaceSteeringMessages(records: List<SessionEvent>): List<ConversationProjectionOperation> {
        val operations = mutableListOf<ConversationProjectionOperation>()
        val next = records.associateBy { requireNotNull(it.event.raw?.get("id")?.stringValue) }
            .filterKeys { it !in committedUserMessageIds }
        (steeringMessages.keys - next.keys).forEach { id ->
            removeStream("steering-$id", operations)
        }
        next.forEach { (id, record) ->
            val key = "steering-$id"
            val item = ConversationItem(
                id = "${record.sessionId}-user-$id", kind = ConversationItemKind.USER,
                title = labels.userMessage, text = record.event.text.orEmpty(),
                images = record.event.images.orEmpty(), files = record.event.files.orEmpty(), epochSeconds = normalizeEpoch(record.time)
            )
            val index = streamIndexes[key]
            if (index == null) {
                streamIndexes[key] = mutableItems.size
                insert(item, operations)
            } else if (mutableItems[index].copy(epochSeconds = item.epochSeconds) != item) {
                // 重复快照保留原显示时间，不让同一条气泡反复更新。
                val updated = item.copy(epochSeconds = mutableItems[index].epochSeconds)
                mutableItems[index] = updated
                operations += ConversationProjectionOperation("replace", item = updated, itemId = updated.id)
            }
        }
        steeringMessages.clear()
        steeringMessages.putAll(next)
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
                imageWindowBySession[record.sessionId] = record.seq
                if (!event.text.isNullOrEmpty() || !event.images.isNullOrEmpty() || !event.files.isNullOrEmpty()) {
                    val messageId = event.raw?.get("id")?.stringValue
                    // 旧网关未转发消息 ID 时，只按到达顺序匹配一条临时消息；不能按正文全局去重。
                    val pendingId = if (messageId != null) messageId.takeIf { it in steeringMessages }
                    else steeringMessages.entries.firstOrNull { (_, pending) ->
                        pending.event.text.orEmpty() == event.text.orEmpty() &&
                            pending.event.images.orEmpty() == event.images.orEmpty() &&
                            pending.event.files.orEmpty() == event.files.orEmpty()
                    }?.key
                    val item = ConversationItem(
                        id = messageId?.let { "${record.sessionId}-user-$it" } ?: eventId(record),
                        kind = ConversationItemKind.USER,
                        title = labels.userMessage,
                        text = event.text.orEmpty(),
                        textParts = event.textParts,
                        requestId = event.requestId,
                        images = event.images.orEmpty(),
                        files = event.files.orEmpty(),
                        epochSeconds = date
                    )
                    if (pendingId != null) {
                        finalizeStream("steering-$pendingId", item, operations)
                        steeringMessages.remove(pendingId)
                        committedUserMessageIds += pendingId
                    } else insert(item, operations)
                    if (messageId != null) committedUserMessageIds += messageId
                }
            }
            event.type == "user/message" -> {
                // 内部工具上下文仍保留原文字；图片独立展示，按调用关联去重。
                val internalTool = event.source == "tool"
                if (!event.text.isNullOrEmpty()) insert(ConversationItem(
                    id = eventId(record), kind = ConversationItemKind.CONTEXT,
                    title = labels.context + contextSourceName(event), text = event.text,
                    images = if (internalTool) emptyList() else event.images.orEmpty(),
                    epochSeconds = date
                ), operations)
                if (internalTool) {
                    // 旧实时协议只给 source=tool，无 callId；只在当前用户轮次内合并。
                    val calls = imageCallKeys(record).ifEmpty { setOf(imageWindowKey(record)) }
                    val existing = calls.flatMap { toolImagesByCall[it].orEmpty() }.toSet()
                    val images = event.images.orEmpty().filter { it.attachmentId !in existing }
                    if (images.isNotEmpty()) {
                        val id = "${eventId(record)}-images"
                        injectedImageCalls[id] = calls
                        insert(ConversationItem(id, ConversationItemKind.GENERATED_IMAGES, "", "",
                            images = images, epochSeconds = date), operations)
                    }
                }
            }
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
            event.type == "assistant/attempt" -> {
                val content = (event.stream ?: event.raw?.get("stream"))?.toJsonElement()?.let(::expandAssistantStream).orEmpty()
                val finish = event.finish ?: content.lastOrNull { it.chunk["type"]?.jsonPrimitive?.contentOrNull == "finish" }
                    ?.chunk?.get("reason")?.let { JsonValue.fromJsonElement(it) }
                    ?.let { FinishInfo(it["kind"]?.stringValue, it["failure"] ?: it["error"]) }
                val cancelled = finish?.kind in setOf("aborted", "cancelled", "canceled")
                val title = if (cancelled) labels.generationCancelled else labels.modelAttemptFailed
                val partial = content.mapNotNull { chunk ->
                    if (chunk.chunk["type"]?.jsonPrimitive?.contentOrNull in setOf("text-delta", "reasoning-delta")) {
                        chunk.chunk["text"]?.jsonPrimitive?.contentOrNull
                    } else null
                }.joinToString("")
                val text = listOfNotNull(partial.takeIf(String::isNotEmpty), failureText(finish?.failure)).joinToString("\n\n")
                val itemKey = "attempt-${eventId(record)}"
                attemptKeys[key] = itemKey
                streamIndexes[itemKey] = mutableItems.size
                insert(ConversationItem(eventId(record), ConversationItemKind.SYSTEM, title,
                    text, isError = !cancelled, epochSeconds = date), operations)
            }
            event.type == "llm/retry" || event.type == "llm/retry-started" -> {
                // A retry settles the attempt notice; keep one status row per provider retry chain.
                attemptKeys.remove(key)?.let { removeStream(it, operations) }
                val itemKey = "retry-${record.sessionId}-${event.retryId ?: key}"
                val old = streamIndexes[itemKey]?.let { mutableItems[it] }
                val count = event.retry?.let { " ($it${event.maxRetries?.let { max -> "/$max" }.orEmpty()})" }.orEmpty()
                val delay = event.delayMs?.let { " · ${it / 1000} s" }.orEmpty()
                val started = event.type == "llm/retry-started"
                val text = failureText(event.failure) ?: old?.text.orEmpty()
                upsertStatus(itemKey, ConversationItem(old?.id ?: eventId(record), ConversationItemKind.SYSTEM,
                    if (started && old != null) old.title.replace(labels.modelRetrying, labels.modelRetried)
                    else (if (started) labels.modelRetried else labels.modelRetrying) + count + delay,
                    text, epochSeconds = date), operations)
                event.turn?.let { retryKeys[it] = itemKey }
            }
            event.type == "turn/end" -> {
                val reason = event.reason ?: event.reasonDetails?.get("kind")?.stringValue
                val failed = reason in setOf("error", "failed")
                val cancelled = reason in setOf("aborted", "cancelled", "canceled")
                val interrupted = reason == "interrupted"
                if (failed || cancelled || interrupted) {
                    attemptKeys.keys.filter { it.startsWith("${event.turn}-") }.toList().forEach { attempt ->
                        attemptKeys.remove(attempt)?.let { removeStream(it, operations) }
                    }
                    retryKeys.remove(event.turn)?.let { retryKey ->
                        val old = streamIndexes[retryKey]?.let { mutableItems[it] }
                        if (old != null && old.title.startsWith(labels.modelRetrying)) {
                            if (cancelled) removeStream(retryKey, operations)
                            else upsertStatus(retryKey, old.copy(
                                title = old.title.replace(labels.modelRetrying, labels.modelRetried)), operations)
                        }
                    }
                    val title = when {
                        cancelled -> labels.generationCancelled
                        failed -> labels.generationFailed
                        else -> labels.interrupted
                    }
                    val details = event.reasonDetails ?: event.raw?.get("reason")
                    insert(ConversationItem(eventId(record), ConversationItemKind.SYSTEM, title,
                        failureText(details?.get("error") ?: details?.get("failure")) ?: event.error.orEmpty(),
                        isError = failed || interrupted, epochSeconds = date), operations)
                } else {
                    retryKeys.remove(event.turn)
                }
                attemptKeys.keys.filter { it.startsWith("${event.turn}-") }.toList().forEach { attemptKeys.remove(it) }
            }
            event.type == "assistant/message" -> {
                finalizedKeys += key
                val finalReasoning = event.reasoning?.takeIf(String::isNotEmpty)?.let { reasoning ->
                    ConversationItem(
                        "${eventId(record)}-reason",
                        ConversationItemKind.REASONING,
                        labels.finalReasoning,
                        reasoning,
                        isError = event.interrupted == true,
                        epochSeconds = date
                    )
                }
                val finalAssistant = if (!event.text.isNullOrEmpty() || !event.images.isNullOrEmpty() || !filesByTurn[event.turn].isNullOrEmpty()) {
                    ConversationItem(
                        eventId(record),
                        ConversationItemKind.ASSISTANT,
                        if (event.interrupted == true) labels.interrupted else labels.finalAssistant,
                        event.text.orEmpty(),
                        event.images.orEmpty(),
                        isError = event.interrupted == true,
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
            event.type == "deliverables/presented" && event.turn != null && presentedSequences.add(record.seq) -> {
                val files = filesByTurn.getOrPut(event.turn) { linkedMapOf() }
                event.files.orEmpty().filter { it.path.isNotBlank() }.forEach { file ->
                    files[file.path] = file
                }
            }
            event.type == "tool/call" -> {
                val name = event.name ?: "Tool Call"
                val kind = if (name.equals("run_code", ignoreCase = true)) ConversationItemKind.JSON_TOOL else ConversationItemKind.TOOL
                insert(ConversationItem(eventId(record), kind, name, event.arguments?.jsonDisplayText().orEmpty(), epochSeconds = date), operations)
            }
            event.type == "tool/result" -> {
                insert(ConversationItem(
                    id = eventId(record),
                    kind = ConversationItemKind.TOOL_RESULT,
                    title = if (toolFailed(event)) labels.toolResultFailed else labels.toolResultDone,
                    text = toolOutput(event),
                    isError = toolFailed(event),
                    epochSeconds = date
                ), operations)
                insertToolImages(record, operations)
            }
            event.type == "tool/ptc-dispatch" || event.type == "tool/code-dispatch" ->
                insertToolImages(record, operations)
            event.type == "command/run" -> commandStarted(record, date, operations)
            event.type == "compaction/start" -> compactionStarted(record, date, operations)
            event.type == "compaction/summary" -> compactionSummarized(record, date, operations)
            event.type == "compaction/end" -> compactionEnded(record, date, operations)
            event.type == "command/done" -> commandFinished(record, date, operations)
        }
    }

    private fun imageWindowKey(record: SessionEvent): String =
        "${record.sessionId}:image-window:${imageWindowBySession[record.sessionId] ?: -1}"

    private fun imageCallKeys(record: SessionEvent): Set<String> {
        val event = record.event
        val source = event.raw?.get("source")
        return listOfNotNull(event.callId, event.rootCallId, event.parentCallId, event.subCallId,
            source?.get("callId")?.stringValue, source?.get("toolCallId")?.stringValue)
            .map { "${record.sessionId}:$it" }.toSet()
    }

    private fun insertToolImages(record: SessionEvent, operations: MutableList<ConversationProjectionOperation>) {
        val calls = imageCallKeys(record)
        val existing = calls.flatMap { toolImagesByCall[it].orEmpty() }.toSet()
        val images = record.event.toolImages().filter { it.attachmentId !in existing }
        if (images.isEmpty()) return
        val imageIds = images.map { it.attachmentId }.toSet()
        val injectionCalls = calls + imageWindowKey(record)
        injectionCalls.forEach { toolImagesByCall.getOrPut(it) { mutableSetOf() }.addAll(imageIds) }
        // 分页重建或较晚到达的工具结果优先于内部注入图片，绝不处理真实用户上传。
        injectedImageCalls.filterValues { it.intersect(injectionCalls).isNotEmpty() }.keys.toList().forEach { injectedId ->
            val index = mutableItems.indexOfFirst { it.id == injectedId }
            if (index >= 0) {
                val old = mutableItems[index]
                val remaining = old.images.filter { it.attachmentId !in imageIds }
                if (remaining.isEmpty()) {
                    mutableItems.removeAt(index)
                    injectedImageCalls.remove(injectedId)
                    streamIndexes.entries.forEach { if (it.value > index) it.setValue(it.value - 1) }
                    operations += ConversationProjectionOperation("remove", itemId = injectedId)
                } else if (remaining != old.images) {
                    val updated = old.copy(images = remaining)
                    mutableItems[index] = updated
                    operations += ConversationProjectionOperation("replace", item = updated, itemId = injectedId)
                }
            }
        }
        val id = "${eventId(record)}-images"
        if (mutableItems.any { it.id == id }) return
        insert(ConversationItem(
            id = id, kind = ConversationItemKind.GENERATED_IMAGES,
            title = "", text = "", images = images,
            epochSeconds = normalizeEpoch(record.time), turn = record.event.turn
        ), operations)
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

    private fun toolFailed(event: GatewayEvent): Boolean {
        if (event.isError == true) return true
        val output = event.output ?: event.preview.orEmpty()
        // Shell tools encode a nonzero process exit in the final line while Host isError may be false.
        val exit = Regex("\\n\\[exit code: (-?\\d+)\\]$").find(output)?.groupValues?.get(1)?.toIntOrNull()
        return (exit != null && exit != 0) || Regex("\\n\\[killed by signal: [^\\]\\n]+\\]$").containsMatchIn(output)
    }

    private fun toolOutput(event: GatewayEvent): String {
        val output = event.output ?: event.preview.orEmpty()
        val detail = event.error?.takeIf { it.isNotBlank() && !output.contains(it) }
        return listOfNotNull(output.takeIf(String::isNotEmpty), detail,
            labels.outputTruncated.takeIf { event.outputTruncated == true }).joinToString("\n\n")
    }

    private fun upsertStatus(key: String, item: ConversationItem, operations: MutableList<ConversationProjectionOperation>) {
        val index = streamIndexes[key]
        if (index == null) {
            streamIndexes[key] = mutableItems.size
            insert(item, operations)
        } else {
            mutableItems[index] = item
            operations += ConversationProjectionOperation("replace", item = item, itemId = item.id)
        }
    }

    private fun attachFiles(turn: Int, operations: MutableList<ConversationProjectionOperation>) {
        val files = filesByTurn[turn]?.values?.toList().orEmpty()
        if (files.isEmpty()) return
        val indexes = mutableItems.indices.filter {
            mutableItems[it].kind == ConversationItemKind.ASSISTANT && mutableItems[it].turn == turn
        }
        indexes.forEach { index ->
            val old = mutableItems[index]
            val cards = if (index == indexes.last()) files else emptyList()
            if (old.files != cards || old.knownFiles != files) {
                val updated = old.copy(files = cards, knownFiles = files)
                mutableItems[index] = updated
                operations += ConversationProjectionOperation("replace", item = updated, itemId = old.id)
            }
        }
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
