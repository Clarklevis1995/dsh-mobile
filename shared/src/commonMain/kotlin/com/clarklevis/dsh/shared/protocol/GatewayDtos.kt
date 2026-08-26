package com.clarklevis.dsh.shared.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    val home: String? = null,
    val crumbs: List<GatewayDirectoryItem>? = null,
    val entries: List<GatewayDirectoryItem>? = null,
    val truncated: Boolean? = null,
    val current: GatewayModelSelection? = null,
    val routable: Boolean? = null,
    val groups: List<GatewayModelGroup>? = null,
    val failures: List<JsonValue>? = null,
    val selected: GatewayModelSelection? = null,
    val selection: GatewayModelSelection? = null,
    val saved: GatewayModelSelection? = null,
    val namespace: JsonValue? = null,
    val sessionPermissions: GatewaySessionPermissions? = null,
    val set: String? = null,
    val asOfSeq: Int? = null,
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
    val value: String? = null,
    val applied: Boolean? = null,
    val rpcId: String? = null,
    val questions: List<GatewayQuestion>? = null,
    val replay: Boolean? = null,
    val action: String? = null,
    val accepted: Boolean? = null,
    val reason: String? = null,
    val outcome: String? = null,
    val attachment: GatewayImageAttachment? = null,
    val data: String? = null
)

@Serializable data class GatewayDevice(val id: String, val name: String? = null, val createdAt: Double? = null)

@Serializable
data class GatewayPairingPayload(
    val version: Int,
    val publicUrl: String,
    val pairingCode: String,
    val expiresAt: Double
)

@Serializable
data class GatewayImageAttachment(
    val attachmentId: String,
    val mediaType: String,
    val bytes: Int,
    val width: Int,
    val height: Int,
    val name: String? = null
)

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
    val allowsMultipleSelections: Boolean get() = multiSelect == true
}

@Serializable
data class GatewayPendingQuestionRequest(
    val rpcId: String,
    val sessionId: String,
    val questions: List<GatewayQuestion>,
    val replay: Boolean
)

@Serializable
data class GatewayQuestionAnswer(
    val id: String,
    val selected: List<String>,
    val custom: String? = null
) {
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
@Serializable data class GatewayDirectoryItem(val name: String, val path: String, val hidden: Boolean)

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
data class GatewayModelCatalog(val current: GatewayModelSelection?, val routable: Boolean, val groups: List<GatewayModelGroup>)
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
data class GatewayContextSnapshot(val asOfSeq: Int? = null, val tokenUsage: GatewayTokenUsage? = null, val pressure: GatewayContextPressure? = null, val breakdown: GatewayContextBreakdown? = null)

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

data class GatewaySessionStatsSnapshot(
    val asOfSeq: Int? = null,
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
    val raw: JsonValue? = null
)

@Serializable data class SessionEvent(val sessionId: String, val seq: Int, val time: Double, val event: GatewayEvent)

@Serializable
data class RawSessionEvent(val type: String, val seq: Int, val time: Double, val data: JsonValue) {
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
            else -> GatewayEvent(type, turn, step, text = data["text"]?.stringValue, raw = data)
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
