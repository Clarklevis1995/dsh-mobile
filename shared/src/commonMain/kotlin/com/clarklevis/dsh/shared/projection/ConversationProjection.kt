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
    val requestId: String? = null,
    val segments: List<ConversationSegment>? = null,
    val sourceEventId: String? = null
)

/** 单个分段的变化；稳定分段不会出现在高频 token patch 中。 */
@Serializable
data class ConversationSegmentChange(
    val index: Int,
    val segment: ConversationSegment
)

/** 高频 token 流只发送有序操作，不重复发送已经累计的完整消息文本。 */
@Serializable
data class ConversationProjectionOperation(
    val kind: String,
    val item: ConversationItem? = null,
    val itemId: String? = null,
    val delta: String? = null,
    val epochSeconds: Double? = null,
    val segments: List<ConversationSegment>? = null,
    val segmentChanges: List<ConversationSegmentChange>? = null
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
    private val questionAnswers = QuestionAnswerProjection()

    internal data class Snapshot(
        val items: List<ConversationItem>,
        val filesByTurn: Map<Int, LinkedHashMap<String, PresentedFile>>,
        val presentedSequences: Set<Int>,
        val toolImagesByCall: Map<String, Set<String>>,
        val injectedImageCalls: Map<String, Set<String>>,
        val imageWindowBySession: Map<String, Int>,
        val streamIndexes: Map<String, Int>,
        val finalizedKeys: Set<String>,
        val commandItemIds: Map<String, String>,
        val compactionCommandIds: Map<String, String>,
        val attemptKeys: Map<String, String>,
        val retryKeys: Map<Int, String>,
        val transientKeys: Set<String>,
        val steeringMessages: LinkedHashMap<String, SessionEvent>,
        val committedUserMessageIds: Set<String>,
        val questionAnswers: QuestionAnswerProjection.Snapshot,
        val lastSequence: Int
    )

    internal fun snapshot(): Snapshot = Snapshot(
        items = mutableItems.toList(),
        filesByTurn = filesByTurn.mapValues { LinkedHashMap(it.value) },
        presentedSequences = presentedSequences.toSet(),
        toolImagesByCall = toolImagesByCall.mapValues { it.value.toSet() },
        injectedImageCalls = injectedImageCalls.toMap(),
        imageWindowBySession = imageWindowBySession.toMap(),
        streamIndexes = streamIndexes.toMap(),
        finalizedKeys = finalizedKeys.toSet(),
        commandItemIds = commandItemIds.toMap(),
        compactionCommandIds = compactionCommandIds.toMap(),
        attemptKeys = attemptKeys.toMap(),
        retryKeys = retryKeys.toMap(),
        transientKeys = transientKeys.toSet(),
        steeringMessages = LinkedHashMap(steeringMessages),
        committedUserMessageIds = committedUserMessageIds.toSet(),
        questionAnswers = questionAnswers.snapshot(),
        lastSequence = lastSequence
    )

    internal fun restore(snapshot: Snapshot) {
        mutableItems.clear()
        mutableItems.addAll(snapshot.items)
        filesByTurn.clear()
        snapshot.filesByTurn.forEach { (turn, files) -> filesByTurn[turn] = LinkedHashMap(files) }
        presentedSequences.clear()
        presentedSequences.addAll(snapshot.presentedSequences)
        toolImagesByCall.clear()
        snapshot.toolImagesByCall.forEach { (key, value) -> toolImagesByCall[key] = value.toMutableSet() }
        injectedImageCalls.clear()
        injectedImageCalls.putAll(snapshot.injectedImageCalls)
        imageWindowBySession.clear()
        imageWindowBySession.putAll(snapshot.imageWindowBySession)
        streamIndexes.clear()
        streamIndexes.putAll(snapshot.streamIndexes)
        finalizedKeys.clear()
        finalizedKeys.addAll(snapshot.finalizedKeys)
        commandItemIds.clear()
        commandItemIds.putAll(snapshot.commandItemIds)
        compactionCommandIds.clear()
        compactionCommandIds.putAll(snapshot.compactionCommandIds)
        attemptKeys.clear()
        attemptKeys.putAll(snapshot.attemptKeys)
        retryKeys.clear()
        retryKeys.putAll(snapshot.retryKeys)
        transientKeys.clear()
        transientKeys.addAll(snapshot.transientKeys)
        steeringMessages.clear()
        steeringMessages.putAll(snapshot.steeringMessages)
        committedUserMessageIds.clear()
        committedUserMessageIds.addAll(snapshot.committedUserMessageIds)
        questionAnswers.restore(snapshot.questionAnswers)
        lastSequence = snapshot.lastSequence
    }

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
        questionAnswers.reset()
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
            if (turn != null && record.event.type != "user/message") {
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

    internal data class FastChunkMutation(
        val operations: List<ConversationProjectionOperation>,
        val itemIndex: Int,
        val previousItem: ConversationItem,
        val previousLastSequence: Int
    )

    /**
     * O(1) fast path for appending a single streaming chunk to an already-open stream row.
     * Avoids copying the full conversation history on every token while preserving exact rollback
     * semantics if downstream patch validation rejects the candidate patch.
     */
    internal fun foldSingleStreamChunkFast(record: SessionEvent): FastChunkMutation? {
        val event = record.event
        if (event.type != "assistant/chunk") return null
        val (streamKey, kind, delta) = when (event.chunkType) {
            "text-delta" -> {
                val k = assistantStreamKey(record)
                if (k in finalizedKeys) return null
                Triple(k, ConversationItemKind.ASSISTANT, event.text.orEmpty())
            }
            "reasoning-delta" -> {
                val k = reasoningStreamKey(record)
                if (k in finalizedKeys) return null
                Triple(k, ConversationItemKind.REASONING, event.text.orEmpty())
            }
            "tool-call-delta" -> {
                val turnStepKey = "${event.turn ?: -1}-${event.step ?: -1}"
                if (turnStepKey in finalizedKeys) return null
                val toolKey = event.tool?.id ?: turnStepKey
                val name = event.tool?.name ?: labels.assemblingTool
                val k = if (name.equals("run_code", ignoreCase = true)) ConversationItemKind.JSON_TOOL else ConversationItemKind.TOOL
                Triple("tool-$toolKey", k, event.tool?.argumentsDelta.orEmpty())
            }
            else -> return null
        }
        val index = streamIndexes[streamKey] ?: return null
        val previousItem = mutableItems[index]
        if (kind == ConversationItemKind.ASSISTANT && event.turn != null && previousItem.turn != event.turn) {
            return null
        }
        val previousLastSequence = lastSequence
        val operations = mutableListOf<ConversationProjectionOperation>()
        if (delta.isNotEmpty()) {
            appendToExistingStream(index, kind, delta, normalizeEpoch(record.time), operations)
        }
        lastSequence = maxOf(lastSequence, record.seq)
        return FastChunkMutation(operations, index, previousItem, previousLastSequence)
    }

    internal fun rollbackFastChunk(mutation: FastChunkMutation) {
        mutableItems[mutation.itemIndex] = mutation.previousItem
        lastSequence = mutation.previousLastSequence
    }

    internal data class FastTransientMutation(
        val operations: List<ConversationProjectionOperation>,
        val savedItems: List<Pair<Int, ConversationItem>>
    )

    internal fun foldExistingAssistantChunksFast(
        attemptId: String,
        chunks: List<AssistantChunk>
    ): FastTransientMutation? {
        if (chunks.isEmpty()) return FastTransientMutation(emptyList(), emptyList())
        // Ensure every actionable chunk targets an already-open transient stream row.
        val resolved = ArrayList<Triple<Int, ConversationItemKind, Pair<String, Double>>>(chunks.size)
        for (entry in chunks) {
            fun field(name: String) = entry.chunk[name]?.jsonPrimitive?.contentOrNull
            val type = field("type")
            val kind = when (type) {
                "text-delta" -> ConversationItemKind.ASSISTANT
                "reasoning-delta" -> ConversationItemKind.REASONING
                "tool-call-delta" -> ConversationItemKind.TOOL
                else -> continue
            }
            val key = "live-$attemptId-$type-${field("index") ?: field("id") ?: "0"}"
            val index = streamIndexes[key] ?: return null
            if (key !in transientKeys) return null
            val delta = field(if (type == "tool-call-delta") "argumentsDelta" else "text").orEmpty()
            val epoch = normalizeEpoch(entry.time)
            require(epoch.isFinite()) { "assistant chunk time must be finite" }
            resolved += Triple(index, kind, delta to epoch)
        }
        val savedByIndex = LinkedHashMap<Int, ConversationItem>()
        val operations = mutableListOf<ConversationProjectionOperation>()
        for ((index, kind, payload) in resolved) {
            if (index !in savedByIndex) savedByIndex[index] = mutableItems[index]
            if (payload.first.isNotEmpty()) {
                appendToExistingStream(index, kind, payload.first, payload.second, operations)
            }
        }
        return FastTransientMutation(operations, savedByIndex.entries.map { it.key to it.value })
    }

    internal fun rollbackFastTransient(mutation: FastTransientMutation) {
        for ((index, item) in mutation.savedItems) {
            mutableItems[index] = item
        }
    }

    /** 独立流只改变展示行，绝不推进 lastSequence。 */
    fun foldAssistantChunks(attemptId: String, chunks: List<AssistantChunk>): List<ConversationProjectionOperation> {
        data class ParsedChunk(
            val key: String,
            val kind: ConversationItemKind,
            val explicitName: String?,
            val delta: String,
            val epochSeconds: Double
        )
        // Parse and validate every chunk up front so a malformed trailing entry never mutates rows.
        val parsed = ArrayList<ParsedChunk>(chunks.size)
        for (entry in chunks) {
            fun field(name: String) = entry.chunk[name]?.jsonPrimitive?.contentOrNull
            val type = field("type")
            val kind = when (type) {
                "text-delta" -> ConversationItemKind.ASSISTANT
                "reasoning-delta" -> ConversationItemKind.REASONING
                "tool-call-delta" -> ConversationItemKind.TOOL
                else -> continue
            }
            val key = "live-$attemptId-$type-${field("index") ?: field("id") ?: "0"}"
            val explicitName = if (kind == ConversationItemKind.TOOL) field("name") else null
            val delta = field(if (type == "tool-call-delta") "argumentsDelta" else "text").orEmpty()
            val epoch = normalizeEpoch(entry.time)
            require(epoch.isFinite()) { "assistant chunk time must be finite" }
            parsed += ParsedChunk(key, kind, explicitName, delta, epoch)
        }
        val operations = mutableListOf<ConversationProjectionOperation>()
        for (entry in parsed) {
            val title = when (entry.kind) {
                ConversationItemKind.ASSISTANT -> labels.streamingAssistant
                ConversationItemKind.REASONING -> labels.streamingReasoning
                else -> entry.explicitName ?: streamIndexes[entry.key]?.let { mutableItems[it].title } ?: labels.assemblingTool
            }
            transientKeys += entry.key
            appendStream(entry.key, entry.key, entry.kind, title, entry.delta, entry.epochSeconds, operations)
        }
        return operations
    }

    fun clearAssistantChunks(): List<ConversationProjectionOperation> {
        val operations = mutableListOf<ConversationProjectionOperation>()
        transientKeys.toList().forEach { removeStream(it, operations) }
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
        val questionAnswer = questionAnswers.fold(record)
        when {
            // 延迟回答由问答气泡替代，避免再次作为 JSON 上下文展示。
            questionAnswer?.replacesUserMessage == true -> {
                finalizeOpenLegacyStreams(operations)
                clearTurnlessFinalizedKeys()
            }
            event.type == "user/message" && (event.source == null || event.source == "user") -> {
                finalizeOpenLegacyStreams(operations)
                clearTurnlessFinalizedKeys()
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
            event.type == "assistant/chunk" && event.chunkType == "text-delta" && assistantStreamKey(record) !in finalizedKeys ->
                appendStream(assistantStreamKey(record), ConversationItemKind.ASSISTANT, labels.streamingAssistant, event.text.orEmpty(), date, operations) {
                    uniqueStreamId(assistantStreamBaseId(record), record.seq)
                }
            event.type == "assistant/chunk" && event.chunkType == "reasoning-delta" && reasoningStreamKey(record) !in finalizedKeys ->
                appendStream(reasoningStreamKey(record), ConversationItemKind.REASONING, labels.streamingReasoning, event.text.orEmpty(), date, operations) {
                    uniqueStreamId(reasoningStreamBaseId(record), record.seq)
                }
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
                val streamTitle = when {
                    cancelled -> labels.generationCancelled
                    failed -> labels.generationFailed
                    else -> labels.interrupted
                }
                if (failed || cancelled || interrupted) {
                    finalizeOpenTurnStreams("text-", event.turn, operations) { old ->
                        old.copy(title = streamTitle, isError = failed || interrupted)
                    }
                    finalizeOpenTurnStreams("reason-", event.turn, operations) { old ->
                        old.copy(title = streamTitle, isError = failed || interrupted)
                    }
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
                    val title = streamTitle
                    val details = event.reasonDetails ?: event.raw?.get("reason")
                    insert(ConversationItem(eventId(record), ConversationItemKind.SYSTEM, title,
                        failureText(details?.get("error") ?: details?.get("failure")) ?: event.error.orEmpty(),
                        isError = failed || interrupted, epochSeconds = date), operations)
                } else {
                    finalizeOpenTurnStreams("text-", event.turn, operations) { old ->
                        old.copy(title = labels.finalAssistant)
                    }
                    finalizeOpenTurnStreams("reason-", event.turn, operations) { old ->
                        old.copy(title = labels.finalReasoning)
                    }
                    retryKeys.remove(event.turn)
                }
                attemptKeys.keys.filter { it.startsWith("${event.turn}-") }.toList().forEach { attemptKeys.remove(it) }
            }
            event.type == "assistant/message" -> {
                val exactAssistantKey = assistantStreamKey(record)
                val exactReasoningKey = reasoningStreamKey(record)
                val hasFinalReasoning = !event.reasoning.isNullOrEmpty()
                val hasExplicitAssistantPayload = !event.text.isNullOrEmpty() || !event.images.isNullOrEmpty()
                val rawAssistantKey = resolveStreamKeyForFinal("text-", exactAssistantKey, event.turn, event.step)
                val rawReasoningKey = resolveStreamKeyForFinal("reason-", exactReasoningKey, event.turn, event.step)
                val rawAssistantParsed = if (rawAssistantKey in streamIndexes) parseStreamKey("text-", rawAssistantKey) else null
                val rawReasoningParsed = if (rawReasoningKey in streamIndexes) parseStreamKey("reason-", rawReasoningKey) else null
                val rawAssistantTurn = streamIndexes[rawAssistantKey]?.let { rawAssistantParsed?.first ?: mutableItems[it].turn }
                val rawAssistantStep = rawAssistantParsed?.second
                val rawReasoningTurn = streamIndexes[rawReasoningKey]?.let { rawReasoningParsed?.first ?: mutableItems[it].turn }
                val rawReasoningStep = rawReasoningParsed?.second
                val pairTurnConflict = rawAssistantTurn != null && rawReasoningTurn != null && rawAssistantTurn != rawReasoningTurn
                val pairStepConflict = rawAssistantStep != null && rawReasoningStep != null && rawAssistantStep != rawReasoningStep
                val resolvedTurn = when {
                    event.turn != null -> event.turn
                    hasExplicitAssistantPayload && !hasFinalReasoning -> rawAssistantTurn
                    hasFinalReasoning && !hasExplicitAssistantPayload -> rawReasoningTurn
                    pairTurnConflict || pairStepConflict -> null
                    else -> rawAssistantTurn ?: rawReasoningTurn
                }
                val resolvedStep = when {
                    event.step != null -> event.step
                    hasExplicitAssistantPayload && !hasFinalReasoning -> rawAssistantStep
                    hasFinalReasoning && !hasExplicitAssistantPayload -> rawReasoningStep
                    pairTurnConflict || pairStepConflict -> null
                    else -> rawAssistantStep ?: rawReasoningStep
                }
                val assistantKey = when {
                    event.turn != null && event.step != null -> rawAssistantKey
                    (rawAssistantTurn != null && resolvedTurn != rawAssistantTurn) ||
                        (rawAssistantStep != null && resolvedStep != rawAssistantStep) -> exactAssistantKey
                    else -> resolveStreamKeyForFinal(
                        "text-",
                        streamKey("text-", resolvedTurn, resolvedStep),
                        resolvedTurn,
                        resolvedStep
                    )
                }
                val reasoningKey = when {
                    event.turn != null && event.step != null -> rawReasoningKey
                    (rawReasoningTurn != null && resolvedTurn != rawReasoningTurn) ||
                        (rawReasoningStep != null && resolvedStep != rawReasoningStep) -> exactReasoningKey
                    else -> resolveStreamKeyForFinal(
                        "reason-",
                        streamKey("reason-", resolvedTurn, resolvedStep),
                        resolvedTurn,
                        resolvedStep
                    )
                }
                finalizedKeys += key
                finalizedKeys += exactAssistantKey
                finalizedKeys += exactReasoningKey
                if (assistantKey in streamIndexes || parseStreamKey("text-", assistantKey)?.first != null) {
                    finalizedKeys += assistantKey
                }
                if (reasoningKey in streamIndexes || parseStreamKey("reason-", reasoningKey)?.first != null) {
                    finalizedKeys += reasoningKey
                }
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
                val existingAssistantIndex = streamIndexes[assistantKey]
                val existingAssistant = existingAssistantIndex?.let { mutableItems[it] }
                val effectiveTurn = event.turn ?: existingAssistant?.turn
                val finalAssistant = if (!event.text.isNullOrEmpty() || !event.images.isNullOrEmpty() || !filesByTurn[effectiveTurn].isNullOrEmpty()) {
                    ConversationItem(
                        eventId(record),
                        ConversationItemKind.ASSISTANT,
                        if (event.interrupted == true) labels.interrupted else labels.finalAssistant,
                        event.text.orEmpty(),
                        event.images.orEmpty(),
                        isError = event.interrupted == true,
                        epochSeconds = date,
                        turn = effectiveTurn,
                        files = existingAssistant?.files.orEmpty(),
                        knownFiles = existingAssistant?.knownFiles.orEmpty()
                    )
                } else {
                    null
                }

                if (finalReasoning != null && streamIndexes[reasoningKey] == null) {
                    if (existingAssistantIndex != null && finalAssistant != null) {
                        // Insert final-only reasoning directly before the streamed answer,
                        // then finalize the answer in place so its id, turn, and deliverables stay intact.
                        insertBefore(existingAssistantIndex, finalReasoning, operations, isFinal = true)
                        finalizeStream(assistantKey, finalAssistant, operations)
                    } else {
                        removeStream(assistantKey, operations)
                        insert(finalReasoning, operations, isFinal = true)
                        finalAssistant?.let { insert(it, operations, isFinal = true) }
                    }
                } else {
                    // Keep a streamed response's identity through finalization.
                    // Platform renderers own their parser/source by item id; a
                    // remove+insert here tears down that source before its final
                    // buffered snapshot can be rendered. Replacing in place also
                    // avoids a structural list update at the end of every reply.
                    finalizeStream(reasoningKey, finalReasoning, operations)
                    finalizeStream(assistantKey, finalAssistant, operations)
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
        questionAnswer?.text?.let { text ->
            insert(ConversationItem("${eventId(record)}-answer", ConversationItemKind.USER,
                labels.userMessage, text, epochSeconds = date), operations)
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
        operations: MutableList<ConversationProjectionOperation>,
        isFinal: Boolean = false
    ) {
        val projected = projectSegments(item, isFinal)
        mutableItems += projected
        operations += ConversationProjectionOperation(kind = "insert", item = projected)
    }

    private fun insertBefore(
        index: Int,
        item: ConversationItem,
        operations: MutableList<ConversationProjectionOperation>,
        isFinal: Boolean = false
    ) {
        val anchorId = mutableItems[index].id
        val projected = projectSegments(item, isFinal)
        mutableItems.add(index, projected)
        streamIndexes.entries.forEach { entry -> if (entry.value >= index) entry.setValue(entry.value + 1) }
        operations += ConversationProjectionOperation(kind = "insert-before", item = projected, itemId = anchorId)
    }

    private fun projectSegments(item: ConversationItem, isFinal: Boolean = false): ConversationItem =
        if (item.kind == ConversationItemKind.ASSISTANT) {
            GenuiBlockParser.parse(item.id, item.text, isFinal = isFinal)?.let { item.copy(segments = it) } ?: item
        } else item

    private fun appendStream(
        id: String,
        key: String,
        kind: ConversationItemKind,
        title: String,
        delta: String,
        date: Double,
        operations: MutableList<ConversationProjectionOperation>
    ) = appendStream(key, kind, title, delta, date, operations) { id }

    private inline fun appendStream(
        key: String,
        kind: ConversationItemKind,
        title: String,
        delta: String,
        date: Double,
        operations: MutableList<ConversationProjectionOperation>,
        idProvider: () -> String
    ) {
        if (delta.isEmpty()) return
        val index = streamIndexes[key]
        if (index != null) {
            appendToExistingStream(index, kind, delta, date, operations)
        } else {
            streamIndexes[key] = mutableItems.size
            insert(ConversationItem(idProvider(), kind, title, delta, epochSeconds = date), operations)
        }
    }

    private fun appendToExistingStream(
        index: Int,
        kind: ConversationItemKind,
        delta: String,
        date: Double,
        operations: MutableList<ConversationProjectionOperation>
    ) {
        val old = mutableItems[index]
        val text = old.text + delta
        val parsed = if (kind == ConversationItemKind.ASSISTANT) {
            GenuiBlockParser.parseAppend(old.id, old.text, delta, old.segments)
        } else null
        if (old.segments != null && parsed == null) {
            val updated = old.copy(text = text, epochSeconds = date, segments = null)
            mutableItems[index] = updated
            operations += ConversationProjectionOperation("replace", item = updated, itemId = old.id)
            return
        }
        val markdownAppendOnly = old.segments != null && parsed != null &&
            GenuiBlockParser.isMarkdownAppendOnly(old.segments, parsed, delta)
        val sendFullSegments = (old.segments == null && parsed != null) ||
            (old.segments != null && parsed != null && parsed.size < old.segments.size)
        val segmentChanges = if (!sendFullSegments && old.segments != null && parsed != null && !markdownAppendOnly) {
            GenuiBlockParser.changedSegments(old.segments, parsed)
        } else null
        val segments = parsed ?: old.segments
        val updated = old.copy(text = text, epochSeconds = date, segments = segments)
        mutableItems[index] = updated
        operations += ConversationProjectionOperation(
            kind = "append-text",
            itemId = old.id,
            delta = delta,
            epochSeconds = date,
            segments = if (sendFullSegments) parsed else null,
            segmentChanges = segmentChanges?.takeIf { it.isNotEmpty() }
        )
    }

    private fun removeStream(
        key: String,
        operations: MutableList<ConversationProjectionOperation>
    ) {
        val index = streamIndexes.remove(key) ?: return
        transientKeys.remove(key)
        val removed = mutableItems.removeAt(index)
        operations += ConversationProjectionOperation(kind = "remove", itemId = removed.id)
        streamIndexes.entries.forEach { entry -> if (entry.value > index) entry.setValue(entry.value - 1) }
    }

    /** Resolves exact key first, falling back only when there is a single unambiguous compatible open stream. */
    private fun resolveStreamKeyForFinal(prefix: String, exactKey: String, turn: Int?, step: Int?): String {
        if (exactKey in streamIndexes) return exactKey
        val safeStep = step?.takeIf { it >= 0 }
        val openStreams = streamIndexes.keys.mapNotNull { key ->
            if (key in transientKeys) return@mapNotNull null
            parseStreamKey(prefix, key)?.let { (candTurn, candStep) -> Triple(key, candTurn, candStep) }
        }
        fun stepCompatible(candStep: Int?): Boolean = safeStep == null || candStep == null || candStep == safeStep
        if (turn != null) {
            val sameTurn = openStreams.filter { it.second == turn }
            val compatibleSameTurn = sameTurn.filter { stepCompatible(it.third) }
            if (compatibleSameTurn.size == 1) return compatibleSameTurn.single().first
            if (compatibleSameTurn.isEmpty()) {
                val compatibleNoTurn = openStreams.filter { it.second == null && stepCompatible(it.third) }
                if (compatibleNoTurn.size == 1) return compatibleNoTurn.single().first
            }
            return exactKey
        }
        val compatible = openStreams.filter { stepCompatible(it.third) }
        return if (compatible.size == 1) compatible.single().first else exactKey
    }

    private inline fun finalizeOpenTurnStreams(
        prefix: String,
        turn: Int?,
        operations: MutableList<ConversationProjectionOperation>,
        transform: (ConversationItem) -> ConversationItem
    ) {
        val keysToFinalize = streamIndexes.keys.filter { key ->
            key !in transientKeys && parseStreamKey(prefix, key)?.let { (candTurn, _) ->
                candTurn == null || (turn != null && candTurn == turn)
            } == true
        }.toList()
        keysToFinalize.forEach { streamKey ->
            val index = streamIndexes[streamKey] ?: return@forEach
            val old = mutableItems[index]
            if (parseStreamKey(prefix, streamKey)?.first != null) finalizedKeys += streamKey
            finalizeStream(streamKey, transform(old), operations)
        }
    }

    /** Closes any unfinalized legacy assistant/reasoning streams from the previous user turn. */
    private fun finalizeOpenLegacyStreams(operations: MutableList<ConversationProjectionOperation>) {
        val reasonKeys = streamIndexes.keys.filter { it.startsWith("reason-") && it !in transientKeys }.toList()
        reasonKeys.forEach { streamKey ->
            val index = streamIndexes[streamKey] ?: return@forEach
            val old = mutableItems[index]
            if (parseStreamKey("reason-", streamKey)?.first != null) finalizedKeys += streamKey
            finalizeStream(streamKey, old.copy(title = labels.finalReasoning), operations)
        }
        val textKeys = streamIndexes.keys.filter { it.startsWith("text-") && it !in transientKeys }.toList()
        textKeys.forEach { streamKey ->
            val index = streamIndexes[streamKey] ?: return@forEach
            val old = mutableItems[index]
            if (parseStreamKey("text-", streamKey)?.first != null) finalizedKeys += streamKey
            finalizeStream(streamKey, old.copy(title = labels.finalAssistant), operations)
        }
    }

    private fun clearTurnlessFinalizedKeys() {
        finalizedKeys.removeAll { key ->
            key.startsWith("-1-") ||
                parseStreamKey("text-", key)?.let { it.first == null } == true ||
                parseStreamKey("reason-", key)?.let { it.first == null } == true
        }
    }

    private fun uniqueStreamId(baseId: String, seq: Int): String {
        if (mutableItems.none { it.id == baseId }) return baseId
        val withSeq = "$baseId-$seq"
        if (mutableItems.none { it.id == withSeq }) return withSeq
        var suffix = 2
        while (mutableItems.any { it.id == "$withSeq-$suffix" }) suffix += 1
        return "$withSeq-$suffix"
    }

    private fun finalizeStream(
        key: String,
        finalItem: ConversationItem?,
        operations: MutableList<ConversationProjectionOperation>
    ) {
        val index = streamIndexes.remove(key)
        if (index == null) {
            finalItem?.let { insert(it, operations, isFinal = true) }
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

        val existing = mutableItems[index]
        val existingId = existing.id
        val alias = finalItem.id.takeIf { it != existingId }
        val preserveAssistantFiles = finalItem.kind == ConversationItemKind.ASSISTANT
        val stableFinalItem = projectSegments(
            finalItem.copy(
                id = existingId,
                turn = finalItem.turn ?: existing.turn,
                files = if (preserveAssistantFiles && finalItem.files.isEmpty()) existing.files else finalItem.files,
                knownFiles = if (preserveAssistantFiles && finalItem.knownFiles.isEmpty()) existing.knownFiles else finalItem.knownFiles,
                sourceEventId = alias ?: finalItem.sourceEventId
            ),
            isFinal = true
        )
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
private fun streamKey(prefix: String, turn: Int?, step: Int?): String {
    val safeStep = step?.takeIf { it >= 0 }
    return when {
        turn != null -> "$prefix$turn-${safeStep ?: -1}"
        safeStep != null -> "${prefix}unindexed-$safeStep"
        else -> "${prefix}unindexed"
    }
}
private fun parseStreamKey(prefix: String, key: String): Pair<Int?, Int?>? {
    if (!key.startsWith(prefix)) return null
    val rest = key.removePrefix(prefix)
    if (rest == "unindexed") return null to null
    if (rest.startsWith("unindexed-")) {
        val step = rest.removePrefix("unindexed-").toIntOrNull()?.takeIf { it >= 0 } ?: return null
        return null to step
    }
    val dash = rest.indexOf('-')
    if (dash <= 0) return null
    val turn = rest.substring(0, dash).toIntOrNull() ?: return null
    val step = rest.substring(dash + 1).toIntOrNull()?.takeIf { it >= 0 }
    return turn to step
}
private fun assistantStreamKey(record: SessionEvent): String =
    streamKey("text-", record.event.turn, record.event.step)
private fun reasoningStreamKey(record: SessionEvent): String =
    streamKey("reason-", record.event.turn, record.event.step)
private fun assistantStreamBaseId(record: SessionEvent): String = when {
    record.event.turn != null -> "stream-text-${record.event.turn}-${record.event.step ?: -1}"
    record.event.step != null -> "stream-text-unindexed-${record.event.step}-${record.seq}"
    else -> "stream-text-unindexed-${record.seq}"
}
private fun reasoningStreamBaseId(record: SessionEvent): String = when {
    record.event.turn != null -> "stream-reason-${record.event.turn}-${record.event.step ?: -1}"
    record.event.step != null -> "stream-reason-unindexed-${record.event.step}-${record.seq}"
    else -> "stream-reason-unindexed-${record.seq}"
}
private fun normalizeEpoch(value: Double): Double = if (value > 10_000_000_000) value / 1_000 else value
