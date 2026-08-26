package com.clarklevis.dsh.shared.projection

import com.clarklevis.dsh.shared.protocol.JsonValue
import com.clarklevis.dsh.shared.protocol.SessionEvent

enum class TrajectoryNodeKind { INPUT, CONTEXT, REQUEST, ASSISTANT, TOOL, SUBTOOL }

data class RequestTokenUsage(
    val uncachedInput: Int = 0,
    val cachedInput: Int = 0,
    val output: Int = 0,
    val reasoning: Int = 0
) {
    val totalInput: Int get() = uncachedInput + cachedInput
    val content: Int get() = maxOf(0, output - reasoning)
    operator fun plus(other: RequestTokenUsage) = RequestTokenUsage(
        uncachedInput + other.uncachedInput,
        cachedInput + other.cachedInput,
        output + other.output,
        reasoning + other.reasoning
    )
    operator fun minus(other: RequestTokenUsage) = RequestTokenUsage(
        uncachedInput - other.uncachedInput,
        cachedInput - other.cachedInput,
        output - other.output,
        reasoning - other.reasoning
    )
}

data class TrajectoryRequest(
    val number: Int,
    val turn: Int?,
    val step: Int?,
    val provider: String?,
    val model: String?,
    val options: JsonValue?,
    val usage: RequestTokenUsage,
    val cumulativeUsage: RequestTokenUsage,
    val toolCalls: Int,
    val subtoolCalls: Int
)

data class TrajectoryTool(val hierarchy: String, val schema: JsonValue?)

data class TrajectoryNode(
    val id: String,
    val kind: TrajectoryNodeKind,
    val title: String,
    val subtitle: String,
    val startSequence: Int,
    val endSequence: Int,
    val startEpochSeconds: Double,
    val endEpochSeconds: Double,
    val records: List<SessionEvent>,
    val request: TrajectoryRequest? = null,
    val tool: TrajectoryTool? = null
)

object TrajectoryProjection {
    fun make(source: List<SessionEvent>): List<TrajectoryNode> {
        val events = source.sortedBy(SessionEvent::seq)
        val completedSteps = events.filter { it.event.type == "assistant/message" }.map(::stepKey).toSet()
        val chunks = events.filter {
            it.event.type == "assistant/chunk" && it.event.chunkType in setOf(
                "reasoning-delta", "text-delta", "tool-call-delta", "block-start", "block-end", "usage", "finish"
            )
        }.groupBy(::stepKey)
        val metadata = events.filter { it.event.type in setOf("request/header", "request/context") }
        val options = metadata.firstOrNull { it.event.type == "request/header" }?.event?.raw?.get("header")?.get("config")
        val context = metadata.firstOrNull { it.event.type == "request/context" }?.event?.raw
        val definitions = metadata.firstOrNull { it.event.type == "request/header" }?.event?.raw
            ?.get("header")?.get("tools")?.arrayValue.orEmpty()
        val nodes = mutableListOf<TrajectoryNode>()
        val assistantIndexes = mutableMapOf<String, Int>()
        val requestIndexes = mutableMapOf<String, Int>()
        val toolIndexes = mutableMapOf<String, Int>()
        val subtoolIndexes = mutableMapOf<String, Int>()
        var requestNumber = 0
        var cumulativeUsage = RequestTokenUsage()

        events.forEach { record ->
            val event = record.event
            val key = stepKey(record)
            when {
                event.type == "user/message" && (event.source == null || event.source == "user") && !event.text.isNullOrEmpty() ->
                    nodes += node("input-${eventId(record)}", TrajectoryNodeKind.INPUT, "User", event.text, record)
                event.type == "user/message" && !event.text.isNullOrEmpty() ->
                    nodes += node("context-${eventId(record)}", TrajectoryNodeKind.CONTEXT, event.source ?: "Context", event.text, record)
                event.type == "assistant/chunk" && key !in completedSteps && event.chunkType in setOf("reasoning-delta", "text-delta") && !event.text.isNullOrEmpty() -> {
                    if (requestIndexes[key] == null) {
                        requestNumber += 1
                        val usage = requestUsage(event.usage)
                        cumulativeUsage += usage
                        requestIndexes[key] = nodes.size
                        nodes += requestNode(requestNumber, key, record, record, metadata + record, options, context, usage, cumulativeUsage, null, events)
                    } else {
                        val index = requestIndexes.getValue(key)
                        nodes[index] = nodes[index].copy(endSequence = record.seq, endEpochSeconds = epoch(record), records = nodes[index].records + record)
                    }
                    val index = assistantIndexes[key]
                    if (index == null) {
                        assistantIndexes[key] = nodes.size
                        nodes += node("assistant-stream-$key", TrajectoryNodeKind.ASSISTANT, "Assistant", event.text, record)
                    } else {
                        nodes[index] = nodes[index].copy(
                            subtitle = nodes[index].subtitle + event.text,
                            endSequence = record.seq,
                            endEpochSeconds = epoch(record),
                            records = nodes[index].records + record
                        )
                    }
                }
                event.type == "assistant/message" -> {
                    val stepChunks = chunks[key].orEmpty()
                    val start = stepChunks.firstOrNull() ?: record
                    val usage = requestUsage(event.usage ?: event.raw?.get("usage"))
                    val existing = requestIndexes[key]
                    if (existing == null) {
                        requestNumber += 1
                        cumulativeUsage += usage
                        requestIndexes[key] = nodes.size
                        nodes += requestNode(requestNumber, key, start, record, metadata + stepChunks + record, options, context, usage, cumulativeUsage, event, events)
                    } else {
                        val oldUsage = nodes[existing].request?.usage ?: RequestTokenUsage()
                        cumulativeUsage = cumulativeUsage - oldUsage + usage
                        nodes[existing] = requestNode(
                            nodes[existing].request?.number ?: requestNumber,
                            key, start, record, metadata + stepChunks + record, options, context, usage, cumulativeUsage, event, events
                        )
                    }
                    nodes += TrajectoryNode(
                        "assistant-${eventId(record)}", TrajectoryNodeKind.ASSISTANT, "Assistant",
                        event.reasoning?.takeIf(String::isNotEmpty) ?: event.text?.takeIf(String::isNotEmpty) ?: "(tool call only)",
                        start.seq, record.seq, epoch(start), epoch(record), stepChunks + record
                    )
                }
                event.type == "tool/call" -> {
                    val callKey = event.callId ?: eventId(record)
                    toolIndexes[callKey] = nodes.size
                    nodes += node(
                        "tool-$callKey", TrajectoryNodeKind.TOOL, event.name?.lowercase() ?: "tool",
                        event.arguments?.jsonDisplayText()?.replace("\n", " ").orEmpty(), record
                    ).copy(tool = TrajectoryTool("Assistant Message", definitions.firstOrNull { it["name"]?.stringValue == event.name }))
                }
                event.type == "tool/result" -> {
                    val callKey = event.callId ?: eventId(record)
                    val index = toolIndexes[callKey]
                    if (index == null) {
                        nodes += node("tool-result-${eventId(record)}", TrajectoryNodeKind.TOOL, if (event.isError == true) "tool error" else "tool result", event.preview.orEmpty(), record)
                    } else {
                        nodes[index] = appendResult(nodes[index], event.preview.orEmpty(), record)
                    }
                }
                event.type == "tool/code-dispatch-start" -> {
                    val callKey = event.subCallId ?: eventId(record)
                    subtoolIndexes[callKey] = nodes.size
                    val parent = event.parentCallId?.let(toolIndexes::get)?.let { nodes[it].title } ?: "Tool"
                    nodes += node(
                        "subtool-$callKey", TrajectoryNodeKind.SUBTOOL, event.name?.lowercase() ?: "subtool",
                        event.arguments?.jsonDisplayText()?.replace("\n", " ").orEmpty(), record
                    ).copy(tool = TrajectoryTool(parent, null))
                }
                event.type == "tool/code-dispatch" -> {
                    val callKey = event.subCallId ?: eventId(record)
                    val index = subtoolIndexes[callKey]
                    if (index == null) {
                        nodes += node("subtool-result-${eventId(record)}", TrajectoryNodeKind.SUBTOOL, event.name?.lowercase() ?: "subtool", event.preview.orEmpty(), record)
                            .copy(tool = TrajectoryTool("Tool", null))
                    } else {
                        nodes[index] = appendResult(nodes[index], event.preview.orEmpty(), record)
                    }
                }
            }
        }
        return nodes.sortedWith(compareBy(TrajectoryNode::startSequence, { priority(it.kind) }))
    }

    private fun requestNode(
        number: Int,
        key: String,
        start: SessionEvent,
        end: SessionEvent,
        records: List<SessionEvent>,
        options: JsonValue?,
        context: JsonValue?,
        usage: RequestTokenUsage,
        cumulative: RequestTokenUsage,
        finalEvent: com.clarklevis.dsh.shared.protocol.GatewayEvent?,
        allEvents: List<SessionEvent>
    ): TrajectoryNode {
        val source = finalEvent?.raw?.get("message")?.get("source")
        val provider = source?.get("provider")?.stringValue ?: context?.get("provider")?.stringValue ?: options?.get("provider")?.stringValue
        val model = source?.get("model")?.stringValue ?: context?.get("model")?.stringValue ?: options?.get("model")?.stringValue
        val turn = finalEvent?.turn ?: start.event.turn
        val step = finalEvent?.step ?: start.event.step
        val callIds = finalEvent?.toolCalls.orEmpty().map { it.id }.toSet()
        val subtools = allEvents.count {
            it.event.type == "tool/code-dispatch" && (it.event.rootCallId in callIds || it.event.parentCallId in callIds)
        }
        return TrajectoryNode(
            id = "request-$key",
            kind = TrajectoryNodeKind.REQUEST,
            title = "Request #$number",
            subtitle = listOfNotNull(turn?.let { "Turn $it" }, step?.let { "Step $it" }, provider, model).joinToString(" · "),
            startSequence = start.seq,
            endSequence = end.seq,
            startEpochSeconds = epoch(start),
            endEpochSeconds = epoch(end),
            records = records,
            request = TrajectoryRequest(number, turn, step, provider, model, options, usage, cumulative, callIds.size, subtools)
        )
    }

    private fun node(id: String, kind: TrajectoryNodeKind, title: String, subtitle: String, record: SessionEvent) =
        TrajectoryNode(id, kind, title, subtitle, record.seq, record.seq, epoch(record), epoch(record), listOf(record))

    private fun appendResult(node: TrajectoryNode, result: String, record: SessionEvent): TrajectoryNode = node.copy(
        subtitle = if (result.isEmpty()) node.subtitle else node.subtitle + (if (node.subtitle.isEmpty()) "" else "  →  ") + result.replace("\n", " "),
        endSequence = record.seq,
        endEpochSeconds = epoch(record),
        records = node.records + record
    )

    private fun requestUsage(value: JsonValue?) = RequestTokenUsage(
        uncachedInput = value?.firstInteger(setOf("inputTokens", "input_tokens", "uncachedInputTokens")) ?: 0,
        cachedInput = value?.firstInteger(setOf("cacheReadTokens", "cachedInputTokens", "cached_tokens")) ?: 0,
        output = value?.firstInteger(setOf("outputTokens", "output_tokens", "completionTokens")) ?: 0,
        reasoning = value?.firstInteger(setOf("reasoningTokens", "reasoning_tokens")) ?: 0
    )

    private fun stepKey(record: SessionEvent) = "${record.event.turn ?: -1}-${record.event.step ?: -1}"
    private fun eventId(record: SessionEvent) = "${record.sessionId}-${record.seq}"
    private fun epoch(record: SessionEvent) = if (record.time > 10_000_000_000) record.time / 1_000 else record.time
    private fun priority(kind: TrajectoryNodeKind) = kind.ordinal
}
