package com.clarklevis.dsh.shared.sync

import com.clarklevis.dsh.shared.projection.ConversationItemKind
import com.clarklevis.dsh.shared.projection.ConversationProjector
import com.clarklevis.dsh.shared.projection.TrajectoryNode
import com.clarklevis.dsh.shared.projection.TrajectoryNodeKind
import com.clarklevis.dsh.shared.protocol.GatewayFrame
import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/** 临时输出没有持久 seq；完整保留 chunk，包括目前 UI 不展示的 usage/finish。 */
@Serializable
data class AssistantChunk(val time: Double, val chunk: JsonObject)

data class AssistantStreamUpdate(
    val accepted: Boolean = false,
    val sessionId: String? = null,
    val clearTransient: Boolean = false,
    val invalidatedSessionIds: List<String> = emptyList(),
    val chunksJson: String = "[]",
    val attemptId: String? = null,
    val error: String? = null,
    val resubscribe: Boolean = false
)

/** 每个 gateway 实例独占。订阅、上游 stream、attempt 和持久水位分别管理。 */
class AssistantStreamState {
    private var selectedSessionId: String? = null
    private var subscriptionId: String? = null
    private var streamId: String? = null
    private var attempt: JsonObject? = null
    private var revision = -1L
    private var nextIndex = 0L
    private var committed: Pair<String, Int>? = null
    private val chunks = mutableListOf<AssistantChunk>()
    private val formats = mutableMapOf<String, Int?>()
    private val cursors = mutableMapOf<String, Int>()
    private var helloFormat: Int? = null
    private var enabled = false

    fun selectSession(sessionId: String?) {
        selectedSessionId = sessionId
        disconnect()
    }

    fun disconnect() {
        subscriptionId = null
        streamId = null
        clearAttempt()
    }

    fun hasBaseline(sessionId: String): Boolean = selectedSessionId == sessionId && streamId != null

    fun formatVersion(sessionId: String): Int? = formats[sessionId]

    val persistentCursor: Int? get() = cursors[selectedSessionId]

    fun activeAttemptId(): String? = attempt?.string("attemptId")

    fun replayChunksJson(): String = wireJson.encodeToString(chunks)

    fun transientTrajectoryNodes(sessionId: String): List<TrajectoryNode> {
        if (!hasBaseline(sessionId)) return emptyList()
        val attemptId = activeAttemptId() ?: return emptyList()
        val projector = ConversationProjector()
        projector.foldAssistantChunks(attemptId, chunks)
        // 轨迹使用真实的生成起点作锚；records 为空，临时 chunk 不伪装成 SessionEvent。
        val anchor = attempt?.long("startedAfterSeq")?.toInt() ?: cursors[sessionId] ?: -1
        return projector.items.map { item ->
            TrajectoryNode(
                item.id,
                if (item.kind == ConversationItemKind.TOOL) TrajectoryNodeKind.TOOL else TrajectoryNodeKind.ASSISTANT,
                item.title,
                item.text,
                anchor,
                anchor,
                item.epochSeconds,
                item.epochSeconds,
                emptyList()
            )
        }
    }

    fun transientTrajectoryJson(sessionId: String): String = wireJson.encodeToString(transientTrajectoryNodes(sessionId))

    fun acceptJson(json: String): AssistantStreamUpdate = runCatching {
        accept(GatewayWireDecoder.decode(json))
    }.getOrElse { fail(selectedSessionId, "实时协议解析失败，正在重新订阅") }

    fun accept(frame: GatewayFrame): AssistantStreamUpdate = when (frame.kind) {
        "hello" -> acceptHello(frame)
        "subscribed" -> acceptSubscription(frame)
        "error" -> if (frame.resetRequired == true && frame.sessionId != null) invalidateHistory(frame) else passThrough(frame)
        "history" -> if (frame.sessionId != null) acceptHistory(frame) else passThrough(frame)
        "session-snapshot", "assistant-stream", "session-stream-reset" -> acceptScoped(frame)
        "event" -> if (enabled || frame.subscriptionId != null) acceptScoped(frame) else passThrough(frame)
        else -> passThrough(frame)
    }

    private fun passThrough(frame: GatewayFrame): AssistantStreamUpdate {
        val id = frame.sessionId
        if (frame.kind == "event" && id != null && id !in formats) formats[id] = null
        return AssistantStreamUpdate(accepted = true, sessionId = id)
    }

    private fun acceptScoped(frame: GatewayFrame): AssistantStreamUpdate {
        if (frame.sessionId != selectedSessionId || subscriptionId == null || frame.subscriptionId != subscriptionId) {
            return AssistantStreamUpdate()
        }
        return when {
            frame.kind == "session-snapshot" -> acceptSnapshot(frame)
            // Opening can fail before any snapshot establishes streamId. Keep subscription
            // scoping, while still rejecting an obsolete stream reset after a new baseline.
            frame.kind == "session-stream-reset" && (streamId == null || frame.streamId == streamId) -> acceptReset(frame)
            streamId == null || frame.streamId != streamId -> AssistantStreamUpdate()
            frame.kind == "event" -> acceptEvent(frame)
            else -> acceptStream(frame)
        }
    }

    private fun acceptHello(frame: GatewayFrame): AssistantStreamUpdate {
        enabled = "assistant-stream-v1" in frame.capabilities.orEmpty()
        val invalid = formats.filterValues { it == null || it != frame.historyFormatVersion }.keys.toList()
        invalid.forEach {
            formats.remove(it)
            cursors.remove(it)
        }
        helloFormat = frame.historyFormatVersion
        disconnect()
        return AssistantStreamUpdate(true, selectedSessionId, true, invalid)
    }
    private fun invalidateHistory(frame: GatewayFrame): AssistantStreamUpdate {
        val id = requireNotNull(frame.sessionId)
        formats.remove(id)
        cursors.remove(id)
        if (id == selectedSessionId) disconnect()
        return AssistantStreamUpdate(
            false,
            id,
            true,
            listOf(id),
            error = frame.message,
            resubscribe = id == selectedSessionId
        )
    }
    private fun acceptHistory(frame: GatewayFrame): AssistantStreamUpdate {
        val id = requireNotNull(frame.sessionId)
        val version = frame.historyFormatVersion
        val invalidResponse = enabled && (version == null || version != helloFormat)
        val invalidCache = formats.containsKey(id) && formats[id] != version
        if (invalidResponse || invalidCache) {
            return invalidateHistory(frame.copy(message = "历史格式已变化，正在重新加载会话"))
        }
        formats[id] = version
        return AssistantStreamUpdate(true, id)
    }
    private fun acceptSubscription(frame: GatewayFrame): AssistantStreamUpdate {
        val id = frame.sessionId
        if (id != selectedSessionId) return AssistantStreamUpdate()
        subscriptionId = frame.subscriptionId
        streamId = null
        clearAttempt()
        return AssistantStreamUpdate(true, id, true)
    }

    // 保留校验失败的提前返回，避免部分安装状态。
    @Suppress("ReturnCount")
    private fun acceptSnapshot(frame: GatewayFrame): AssistantStreamUpdate {
        val id = frame.sessionId
        val version = frame.historyFormatVersion ?: return fail(id, "快照缺少历史格式版本")
        val cursor = frame.cursor ?: return fail(id, "快照缺少持久流水位")
        if (version != helloFormat || cursor < 0 || frame.streamId.isNullOrBlank()) {
            return fail(id, "会话快照格式无效")
        }
        val prefix = frame.assistantStream?.toJsonElement() as? JsonObject
        val active = prefix?.get("activeAttempt") as? JsonObject
        val restored = active?.get("stream")?.let(::expandAssistantStream).orEmpty()
        streamId = frame.streamId
        formats[id!!] = version
        cursors[id] = cursor // 精简历史的最后一个 event 不代表此水位。
        clearAttempt()
        revision = prefix?.long("revision") ?: 0
        attempt = active
        nextIndex = active?.long("nextIndex") ?: 0
        chunks.addAll(restored)
        return AssistantStreamUpdate(
            true,
            id,
            true,
            chunksJson = wireJson.encodeToString(restored),
            attemptId = activeAttemptId()
        )
    }
    private fun acceptReset(frame: GatewayFrame): AssistantStreamUpdate {
        val id = frame.sessionId
        streamId = null
        clearAttempt()
        return AssistantStreamUpdate(
            true,
            id,
            true,
            error = if (frame.retrying == false) frame.message ?: "会话实时流已停止" else null
        )
    }

    // 保留校验失败的提前返回，避免部分安装状态。
    @Suppress("ReturnCount")
    private fun acceptEvent(frame: GatewayFrame): AssistantStreamUpdate {
        val id = frame.sessionId
        val seq = frame.seq ?: return AssistantStreamUpdate()
        if (seq <= (cursors[id] ?: -1)) return AssistantStreamUpdate()
        cursors[id!!] = seq
        // 同一 follow 保证 committed event 在 end 之前。先移除临时行，避免两份最终内容。
        val settled =
            frame.event?.type in setOf("assistant/message", "assistant/attempt") &&
                frame.event?.turn == attempt?.long("turn")?.toInt() &&
                frame.event?.step == attempt?.long("step")?.toInt()
        if (settled) {
            chunks.clear()
            committed = requireNotNull(frame.event).type to seq
        }
        return AssistantStreamUpdate(true, id, settled)
    }

    // 保留校验失败的提前返回，避免部分安装状态。
    @Suppress("ReturnCount")
    private fun acceptStream(frame: GatewayFrame): AssistantStreamUpdate {
        val id = frame.sessionId
        val value = frame.frame?.toJsonElement() as? JsonObject ?: return fail(id, "流帧缺少内容")
        val incomingRevision = value.long("revision") ?: return fail(id, "流帧缺少 revision")
        if (incomingRevision <= revision) return AssistantStreamUpdate()
        if (incomingRevision != revision + 1) return fail(id, "实时流 revision 断档，正在重新订阅")
        val incomingAttempt = value.string("attemptId") ?: return fail(id, "流帧缺少 attemptId")
        when (value.string("type")) {
            "start" -> {
                clearAttempt()
                attempt = value
                revision = incomingRevision
                return AssistantStreamUpdate(true, id, true, attemptId = incomingAttempt)
            }
            "chunk", "end" -> return acceptChunkOrEnd(id, value, incomingAttempt, incomingRevision)
            else -> return fail(id, "未知实时流帧")
        }
    }

    // 协议校验使用提前返回，拒绝坏帧后不会继续修改状态。
    @Suppress("ReturnCount")
    private fun acceptChunkOrEnd(id: String?, value: JsonObject, incomingAttempt: String, incomingRevision: Long): AssistantStreamUpdate {
        if (incomingAttempt != activeAttemptId()) return fail(id, "实时流 attempt 不匹配")
        val index = value.long("index") ?: return fail(id, "流帧缺少 index")
        if (index < nextIndex) return AssistantStreamUpdate()
        if (index != nextIndex) return fail(id, "实时流 index 断档，正在重新订阅")
        revision = incomingRevision
        if (value.string("type") == "end") {
            val outcome = value["outcome"] as? JsonObject ?: return fail(id, "流结算缺少 outcome")
            if (outcome.string("kind") == "committed" &&
                committed != (outcome.string("eventType") to outcome.long("seq")?.toInt())
            ) {
                return fail(id, "流结算缺少对应的持久消息，正在重新订阅")
            }
            clearAttempt()
            return AssistantStreamUpdate(true, id, true)
        }
        val chunk = value["chunk"] as? JsonObject ?: return fail(id, "流帧缺少 chunk")
        val entry = AssistantChunk(value.double("time") ?: 0.0, chunk)
        chunks += entry
        nextIndex++
        return AssistantStreamUpdate(
            true,
            id,
            chunksJson = wireJson.encodeToString(listOf(entry)),
            attemptId = incomingAttempt
        )
    }

    private fun clearAttempt() {
        attempt = null
        chunks.clear()
        nextIndex = 0
        committed = null
    }

    private fun fail(id: String?, message: String): AssistantStreamUpdate {
        streamId = null
        clearAttempt()
        return AssistantStreamUpdate(false, id, true, error = message, resubscribe = true)
    }
}

/** rc.2 紧凑流只在快照/轨迹恢复时展开，不能把 block index 当作持久事件 seq。 */
fun expandAssistantStream(stream: JsonElement): List<AssistantChunk> = (stream as? JsonArray).orEmpty().flatMap { element ->
    val row = element as? JsonObject ?: return@flatMap emptyList()
    if (row.string("type") == "chunk") {
        val chunk = row["chunk"] as? JsonObject ?: return@flatMap emptyList()
        listOf(AssistantChunk(row.double("time") ?: 0.0, chunk))
    } else {
        expandCompactRow(row)
    }
}

private fun expandCompactRow(row: JsonObject): List<AssistantChunk> {
    val type = when (row.string("type")) {
        "text-chunks" -> "text-delta"
        "reasoning-chunks" -> "reasoning-delta"
        "tool-call-chunks" -> "tool-call-delta"
        else -> return emptyList()
    }
    val texts = (row[if (type == "tool-call-delta") "args" else "texts"] as? JsonArray).orEmpty()
    var time = row.double("time0") ?: 0.0
    val dt = (row["dt"] as? JsonArray).orEmpty()
    return texts.mapIndexed { offset, text ->
        if (offset > 0) time += (dt.getOrNull(offset - 1) as? JsonPrimitive)?.doubleOrNull ?: 0.0
        AssistantChunk(time, compactChunk(row, type, text))
    }
}

private fun compactChunk(row: JsonObject, type: String, text: JsonElement): JsonObject = buildJsonObject {
    put("type", type)
    row["index"]?.let { put("index", it) }
    if (type == "tool-call-delta") {
        row["id"]?.let { put("id", it) }
        row["name"]?.let { put("name", it) }
        put("argumentsDelta", text)
    } else {
        put("text", text)
    }
}

private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

private fun JsonObject.long(key: String): Long? {
    val value = (get(key) as? JsonPrimitive)?.doubleOrNull ?: return null
    return value.takeIf { it.isFinite() && it >= 0 && it <= MAX_SAFE_INTEGER && it == it.toLong().toDouble() }?.toLong()
}

private fun JsonObject.double(key: String): Double? = (get(key) as? JsonPrimitive)?.doubleOrNull

private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991.0
