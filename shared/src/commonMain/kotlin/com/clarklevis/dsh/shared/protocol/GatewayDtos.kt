package com.clarklevis.dsh.shared.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
data class GatewayFrame(
    val kind: String,
    @SerialName("protocol") val protocolVersion: Int? = null,
    val capabilities: List<String>? = null,
    val authenticated: Boolean? = null,
    val token: String? = null,
    val device: GatewayDevice? = null,
    val port: Int? = null,
    val clients: Int? = null,
    val at: Double? = null,
    val sessionId: String? = null,
    val seq: Int? = null,
    val time: Double? = null,
    val event: GatewayEvent? = null,
    val code: String? = null,
    val message: String? = null,
    val mode: String? = null,
    val command: JsonValue? = null,
    val line: String? = null,
    val commandId: String? = null,
    val result: JsonValue? = null,
    val commands: List<GatewaySlashCommand>? = null,
    val skills: List<GatewaySlashCommand>? = null,
    val options: List<GatewayCommandOption>? = null,
    val requestType: String? = null,
    val items: List<JsonValue>? = null,
    val events: List<RawSessionEvent>? = null,
    val hasMore: Boolean? = null,
    val nextBeforeSeq: Int? = null,
    val bytes: Int? = null,
    val view: String? = null,
    val query: String? = null,
    val archivedSessionIds: List<String>? = null,
    val workspace: GatewayWorkspace? = null,
    val created: Boolean? = null,
    val version: String? = null,
    val cwd: String? = null,
    val provider: String? = null,
    val model: String? = null,
    val attachedSessions: Int? = null,
    val canOpenPath: Boolean? = null,
    val path: String? = null,
    val requestId: String? = null,
    val transferId: String? = null,
    val name: String? = null,
    val mediaType: String? = null,
    val size: Long? = null,
    val chunkBytes: Int? = null,
    val offset: Long? = null,
    val eof: Boolean? = null,
    val sha256: String? = null,
    val home: String? = null,
    val crumbs: List<GatewayDirectoryItem>? = null,
    val entries: List<GatewayDirectoryItem>? = null,
    val truncated: Boolean? = null,
    val current: GatewayModelSelection? = null,
    val routable: Boolean? = null,
    // `groups` is polymorphic: model catalogs contain GatewayModelGroup values,
    // while slash catalogs contain GatewaySlashCommandGroup values.
    // Decode it lazily after inspecting `kind` so a valid frame of one kind
    // cannot fail because the other kind has a different group schema.
    val groups: List<JsonValue>? = null,
    val failures: List<JsonValue>? = null,
    val selected: JsonValue? = null,
    val selection: GatewayModelSelection? = null,
    val saved: GatewayModelSelection? = null,
    val namespace: JsonValue? = null,
    val sessionPermissions: GatewaySessionPermissions? = null,
    val set: String? = null,
    val asOfSeq: Long? = null,
    val todos: List<GatewayTask>? = null,
    val goal: GatewayGoalSnapshot? = null,
    val ref: GatewayGoalRef? = null,
    val cleared: Boolean? = null,
    val sessionStats: GatewaySessionStats? = null,
    val tokenUsage: GatewayTokenUsage? = null,
    val contextPressure: GatewayContextPressure? = null,
    val projections: JsonValue? = null,
    val presets: List<GatewayAgentPreset>? = null,
    val authorable: Boolean? = null,
    val hasDocument: Boolean? = null,
    val agentPresetDefault: String? = null,
    val permissionDefault: String? = null,
    val target: String? = null,
    val value: JsonValue? = null,
    val applied: Boolean? = null,
    val rpcId: String? = null,
    val questions: List<GatewayQuestion>? = null,
    val replay: Boolean? = null,
    val action: String? = null,
    val accepted: Boolean? = null,
    val reason: String? = null,
    val outcome: String? = null,
    val approvalId: String? = null,
    val toolName: String? = null,
    val callId: String? = null,
    val attachment: GatewayImageAttachment? = null,
    val data: String? = null
) {
    override fun toString(): String =
        "GatewayFrame(kind=$kind, sessionId=$sessionId, seq=$seq, requestType=$requestType, " +
            "rpcId=<redacted>, token=<redacted>, data=<redacted>, message=<redacted>)"
}

/** WebUI 的 todo_write 投影；移动端仅展示，不直接修改。 */
@Serializable
data class GatewayTask(
    val content: String,
    val status: String
)

/** Goal 写入的 compare-and-set 引用，防止多端以旧版本覆盖新修改。 */
@Serializable
data class GatewayGoalRef(
    val id: String,
    val revision: Int
)

@Serializable
data class GatewayGoalDefinition(
    val id: String,
    val revision: Int,
    val objective: String,
    val phase: String,
    val maxGoalRounds: Int? = null
) {
    val ref: GatewayGoalRef get() = GatewayGoalRef(id, revision)
}

@Serializable
data class GatewayGoalSnapshot(
    val goal: GatewayGoalDefinition,
    val roundsStarted: Int = 0,
    val createdAt: Double? = null,
    val updatedAt: Double? = null
)

@Serializable
data class GatewaySlashCommand(
    val id: String = "",
    val name: String,
    val description: String,
    val source: String? = null,
    val action: String? = null,
    val modelInvocable: Boolean? = null,
    val whenToUse: String? = null,
    val input: GatewaySlashCommandInput? = null,
    val ui: GatewaySlashCommandUi
) {
    val stableId: String get() = id.ifBlank { "${source ?: "item"}:$name" }
}

/**
 * 事件流中的 `source` 既可能是 WebUI 简化后的字符串，也可能是 Host 原样下发的
 * `{ kind: ... }` 对象。统一归一化为 kind，避免一条新事件破坏整个 WebSocket 解码。
 */
@OptIn(ExperimentalSerializationApi::class)
object GatewayEventSourceSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
        "GatewayEventSource",
        PrimitiveKind.STRING
    )

    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value)
    }

    override fun deserialize(decoder: Decoder): String? {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeString()
        return when (val value = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> value.contentOrNull
            is JsonObject -> value["kind"]?.let { kind ->
                (kind as? JsonPrimitive)?.contentOrNull
            }
            else -> null
        }
    }
}

@Serializable
data class GatewaySlashCommandGroup(
    val id: String,
    val title: String,
    val items: List<GatewaySlashCommand>
)

@Serializable
data class GatewaySlashCommandInput(
    val hint: String? = null,
    val images: Boolean? = null
)

@Serializable
data class GatewaySlashCommandUi(
    val kind: String,
    val insertText: String? = null,
    val hint: String? = null,
    val displayHint: String? = null,
    val images: Boolean? = null,
    val submitRequest: String? = null,
    val submitText: String? = null,
    val optionsRequest: String? = null,
    val selectionRequest: String? = null
)

@Serializable
data class GatewayCommandOption(
    val id: String,
    val label: String,
    val detail: String? = null,
    val description: String? = null,
    val selected: Boolean? = null
)

@Serializable data class GatewayDevice(val id: String, val name: String? = null, val createdAt: Double? = null)

@Serializable
data class GatewayPairingPayload(
    val version: Int,
    val publicUrl: String,
    val pairingCode: String,
    val expiresAt: Double
) {
    override fun toString(): String =
        "GatewayPairingPayload(version=$version, publicUrl=<redacted>, " +
            "pairingCode=<redacted>, expiresAt=$expiresAt)"
}

@Serializable
data class GatewayImageAttachment(
    val attachmentId: String,
    val mediaType: String,
    val bytes: Int,
    val width: Int,
    val height: Int,
    val name: String? = null
) {
    override fun toString(): String =
        "GatewayImageAttachment(attachmentId=$attachmentId, mediaType=$mediaType, bytes=$bytes, " +
            "width=$width, height=$height, name=<redacted>)"
}

@Serializable data class GatewayQuestionOption(val label: String, val description: String? = null)
@Serializable data class GatewayQuestionIntent(val kind: String, val approve: String? = null)

@Serializable
data class GatewayQuestion(
    val id: String,
    val header: String? = null,
    val question: String,
    val detail: String? = null,
    val options: List<GatewayQuestionOption>? = null,
    val multiSelect: Boolean? = null,
    val intent: GatewayQuestionIntent? = null
) {
    override fun toString(): String =
        "GatewayQuestion(id=$id, question=<redacted>, detail=<redacted>, options=<redacted>)"

    val allowsMultipleSelections: Boolean get() = multiSelect == true
}

@Serializable
data class GatewayPendingQuestionRequest(
    val rpcId: String,
    val sessionId: String,
    val questions: List<GatewayQuestion>,
    val replay: Boolean
) {
    override fun toString(): String =
        "GatewayPendingQuestionRequest(rpcId=<redacted>, sessionId=$sessionId, " +
            "questions=<redacted>, replay=$replay)"
}

@Serializable
data class GatewayQuestionAnswer(
    val id: String,
    val selected: List<String>,
    val custom: String? = null
) {
    override fun toString(): String =
        "GatewayQuestionAnswer(id=$id, selected=<redacted>, custom=<redacted>)"

    val normalizedCustom: String? get() = custom?.trim()?.takeIf(String::isNotEmpty)
}

@Serializable enum class GatewayQuestionAction { @SerialName("answer") ANSWER, @SerialName("cancel") CANCEL }

@Serializable
data class GatewayWorkspace(
    val workspaceId: String,
    val path: String,
    val title: String,
    val sessionIds: List<String>,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class GatewayPendingApprovalRequest(
    val rpcId: String,
    val sessionId: String,
    val approvalId: String,
    val toolName: String,
    val callId: String? = null,
    val reason: String? = null,
    val replay: Boolean = false
) {
    override fun toString(): String =
        "GatewayPendingApprovalRequest(rpcId=<redacted>, sessionId=$sessionId, " +
            "approvalId=<redacted>, toolName=$toolName, callId=<redacted>, reason=<redacted>, replay=$replay)"
}

@Serializable
enum class GatewayApprovalOutcome {
    @SerialName("allowed-once") ALLOWED_ONCE,
    @SerialName("rejected") REJECTED
}

@Serializable
data class GatewaySessionSummary(
    val sessionId: String,
    val updatedAt: Double,
    val running: Boolean,
    val blank: Boolean,
    val parentSessionId: String? = null,
    val origin: String? = null,
    val cwd: String? = null,
    val agentPreset: String? = null,
    val projections: JsonValue? = null
) {
    val projectedTitle: String? get() = projections?.get("values")?.get("title")?.stringValue
}

@Serializable data class GatewaySearchItem(val sessionId: String, val snippet: String)
@Serializable
data class GatewayDirectoryItem(
    val name: String,
    val path: String,
    val hidden: Boolean = false,
    val kind: String? = null,
    val bytes: Long? = null,
    val modifiedAt: Double? = null,
    val mediaType: String? = null
)

@Serializable
data class GatewayHostSnapshot(
    val version: String? = null,
    val cwd: String? = null,
    val provider: String? = null,
    val model: String? = null,
    val attachedSessions: Int? = null,
    val canOpenPath: Boolean? = null
)

@Serializable data class GatewayModelSelection(val provider: String, val model: String, val reasoningEffort: String? = null)
@Serializable data class GatewayReasoningEffort(val id: String, val name: String)
@Serializable data class GatewayModelReasoning(val efforts: List<GatewayReasoningEffort>, val defaultEffort: String? = null)
@Serializable data class GatewayModelItem(val id: String, val name: String, val reasoning: GatewayModelReasoning? = null)
@Serializable data class GatewayModelGroup(val id: String, val name: String, val models: List<GatewayModelItem>)
@Serializable data class GatewayModelCatalog(val current: GatewayModelSelection?, val routable: Boolean, val groups: List<GatewayModelGroup>)
@Serializable data class GatewayPermissionOption(val value: String, val name: String)

@Serializable
data class GatewayAgentPreset(
    val id: String,
    val trust: JsonValue? = null,
    val isDefault: Boolean,
    val name: String? = null,
    val description: String? = null,
    val broken: Boolean? = null
)

@Serializable
data class GatewaySessionPermissions(
    val options: List<GatewayPermissionOption>? = null,
    val currentValue: String? = null,
    val preset: String? = null,
    val sandbox: String? = null,
    val approval: String? = null
)

@Serializable
data class GatewayTokenUsage(
    val uncachedInputTokens: Int? = null,
    val outputTokens: Int? = null,
    val cacheReadTokens: Int? = null,
    val cacheWriteTokens: Int? = null,
    val totals: GatewaySessionTokenUsageTotals? = null
)

@Serializable data class GatewayContextPressure(val pressureTokens: Int? = null, val projectedTokens: Int? = null, val contextWindow: Int? = null)
@Serializable data class GatewayContextBreakdown(val systemTokens: Int? = null, val toolsTokens: Int? = null, val messageTokens: Int? = null)
@Serializable data class GatewayContextSnapshot(val asOfSeq: Long? = null, val tokenUsage: GatewayTokenUsage? = null, val pressure: GatewayContextPressure? = null, val breakdown: GatewayContextBreakdown? = null)

@Serializable
data class GatewaySessionStats(
    val turns: Int? = null,
    val steps: Int? = null,
    val llmMs: Double? = null,
    val toolMs: Double? = null,
    val ttftMs: Double? = null,
    val ttftSteps: Int? = null,
    val decodeMs: Double? = null,
    val decodeTokens: Int? = null,
    val lastTurn: Int? = null,
    val openStep: Int? = null,
    val pendingCalls: JsonValue? = null
)

@Serializable data class GatewaySessionTokenUsage(val totals: GatewaySessionTokenUsageTotals? = null)

@Serializable
data class GatewaySessionTokenUsageTotals(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val cacheReadTokens: Int? = null,
    val cacheWriteTokens: Int? = null,
    val reasoningTokens: Int? = null
)

@Serializable
data class GatewaySessionStatsSnapshot(
    val asOfSeq: Long? = null,
    val stats: GatewaySessionStats? = null,
    val tokenUsage: GatewaySessionTokenUsage? = null,
    val contextPressure: GatewayContextPressure? = null
)

@Serializable data class ToolDelta(val id: String? = null, val name: String? = null, val argumentsDelta: String? = null)
@Serializable data class ToolCall(val id: String, val name: String, val arguments: JsonValue? = null)
@Serializable data class FinishInfo(val kind: String? = null)

@Serializable
data class GatewayEvent(
    val type: String,
    val turn: Int? = null,
    val step: Int? = null,
    val text: String? = null,
    @Serializable(with = GatewayEventSourceSerializer::class)
    val source: String? = null,
    val chunkType: String? = null,
    val tool: ToolDelta? = null,
    val usage: JsonValue? = null,
    val finish: FinishInfo? = null,
    val reasoning: String? = null,
    val toolCalls: List<ToolCall>? = null,
    val images: List<GatewayImageAttachment>? = null,
    val callId: String? = null,
    val name: String? = null,
    val arguments: JsonValue? = null,
    val isError: Boolean? = null,
    val preview: String? = null,
    val reason: String? = null,
    val rootCallId: String? = null,
    val parentCallId: String? = null,
    val subCallId: String? = null,
    val commandId: String? = null,
    val args: String? = null,
    val outcome: String? = null,
    val sourceEventSeq: Int? = null,
    val compactionId: String? = null,
    val sourceCommandId: String? = null,
    val shadowedItemCount: Int? = null,
    val shadowedTokenCount: Int? = null,
    val error: String? = null,
    val raw: JsonValue? = null
) {
    override fun toString(): String =
        "GatewayEvent(type=$type, turn=$turn, step=$step, text=<redacted>, tool=<redacted>, " +
            "reasoning=<redacted>, toolCalls=<redacted>, arguments=<redacted>, " +
            "preview=<redacted>, reason=<redacted>, raw=<redacted>)"
}

@Serializable
data class SessionEvent(val sessionId: String, val seq: Int, val time: Double, val event: GatewayEvent) {
    override fun toString(): String =
        "SessionEvent(sessionId=$sessionId, seq=$seq, time=$time, event=$event)"
}

@Serializable
data class RawSessionEvent(val type: String, val seq: Int, val time: Double, val data: JsonValue) {
    override fun toString(): String =
        "RawSessionEvent(type=$type, seq=$seq, time=$time, data=<redacted>)"

    fun normalized(sessionId: String): SessionEvent = SessionEvent(sessionId, seq, time, normalizedEvent())

    private fun normalizedEvent(): GatewayEvent {
        val turn = data["turn"]?.doubleValue?.toInt()
        val step = data["step"]?.doubleValue?.toInt()
        return when (type) {
            "user/message" -> GatewayEvent(
                type = type,
                text = textBlocks(data["content"]),
                source = data["source"]?.get("kind")?.stringValue,
                images = imageBlocks(data["content"]),
                raw = data
            )
            "assistant/chunk" -> {
                val chunk = data["chunk"]
                val chunkType = chunk?.get("type")?.stringValue
                GatewayEvent(
                    type = type,
                    turn = turn,
                    step = step,
                    text = chunk?.get("text")?.stringValue,
                    chunkType = chunkType,
                    tool = if (chunkType == "tool-call-delta") ToolDelta(
                        chunk["id"]?.stringValue,
                        chunk["name"]?.stringValue,
                        chunk["argumentsDelta"]?.stringValue
                    ) else null,
                    usage = chunk?.get("usage"),
                    finish = FinishInfo(chunk?.get("reason")?.get("kind")?.stringValue),
                    raw = data
                )
            }
            "assistant/message" -> {
                val blocks = data["message"]?.get("content")?.arrayValue.orEmpty()
                GatewayEvent(
                    type = type,
                    turn = turn,
                    step = step,
                    text = blocks.filter { it["type"]?.stringValue == "text" }.mapNotNull { it["text"]?.stringValue }.joinToString(""),
                    reasoning = blocks.filter { it["type"]?.stringValue == "reasoning" }.mapNotNull { it["text"]?.stringValue }.joinToString(""),
                    toolCalls = blocks.mapNotNull { block ->
                        if (block["type"]?.stringValue != "tool-call") return@mapNotNull null
                        val id = block["id"]?.stringValue ?: return@mapNotNull null
                        val name = block["name"]?.stringValue ?: return@mapNotNull null
                        ToolCall(id, name, block["arguments"])
                    },
                    images = imageBlocks(data["message"]?.get("content")),
                    usage = data["usage"],
                    raw = data
                )
            }
            "tool/call" -> GatewayEvent(type, turn, step, callId = data["callId"]?.stringValue, name = data["name"]?.stringValue, arguments = data["arguments"], raw = data)
            "tool/result" -> GatewayEvent(
                type,
                turn,
                step,
                callId = data["message"]?.get("source")?.get("callId")?.stringValue,
                isError = data["error"] != null && data["error"] != JsonValue.NullValue,
                preview = toolResultText(data["message"]),
                raw = data
            )
            "tool/code-dispatch-start", "tool/code-dispatch" -> GatewayEvent(
                type = type,
                name = data["name"]?.stringValue,
                arguments = data["arguments"],
                isError = data["isError"]?.booleanValue,
                preview = textBlocks(data["content"]),
                rootCallId = data["rootCallId"]?.stringValue,
                parentCallId = data["parentCallId"]?.stringValue,
                subCallId = data["subCallId"]?.stringValue,
                raw = data
            )
            "turn/start", "turn/end", "step/start", "step/end" -> GatewayEvent(type, turn, step, reason = data["reason"]?.get("kind")?.stringValue, raw = data)
            "session/title" -> GatewayEvent(type, text = data["title"]?.stringValue, raw = data)
            else -> GatewayEvent(
                type = type,
                turn = turn,
                step = step,
                text = data["text"]?.stringValue,
                name = data["name"]?.stringValue,
                isError = data["error"] != null && data["error"] != JsonValue.NullValue,
                commandId = data["commandId"]?.stringValue,
                args = data["args"]?.stringValue,
                outcome = data["outcome"]?.stringValue,
                sourceEventSeq = data["sourceEventSeq"]?.doubleValue?.toInt(),
                compactionId = data["compactionId"]?.stringValue,
                sourceCommandId = data["sourceCommandId"]?.stringValue,
                shadowedItemCount = data["shadowedItemCount"]?.doubleValue?.toInt(),
                shadowedTokenCount = data["shadowedTokenCount"]?.doubleValue?.toInt(),
                error = data["error"]?.stringValue,
                raw = data
            )
        }
    }

    private fun textBlocks(value: JsonValue?): String = value?.arrayValue.orEmpty()
        .filter { it["type"]?.stringValue == "text" }
        .mapNotNull { it["text"]?.stringValue }
        .joinToString("")

    private fun imageBlocks(value: JsonValue?): List<GatewayImageAttachment> = value?.arrayValue.orEmpty().mapNotNull { block ->
        if (block["type"]?.stringValue != "image") return@mapNotNull null
        val attachment = block["attachment"] ?: return@mapNotNull null
        GatewayImageAttachment(
            attachmentId = attachment["attachmentId"]?.stringValue ?: return@mapNotNull null,
            mediaType = attachment["mediaType"]?.stringValue ?: return@mapNotNull null,
            bytes = attachment["bytes"]?.doubleValue?.toInt() ?: return@mapNotNull null,
            width = attachment["width"]?.doubleValue?.toInt() ?: return@mapNotNull null,
            height = attachment["height"]?.doubleValue?.toInt() ?: return@mapNotNull null,
            name = attachment["name"]?.stringValue
        )
    }

    private fun toolResultText(message: JsonValue?): String = message?.get("content")?.arrayValue.orEmpty()
        .flatMap { it["content"]?.arrayValue.orEmpty() }
        .filter { it["type"]?.stringValue == "text" }
        .mapNotNull { it["text"]?.stringValue }
        .joinToString("")
}
