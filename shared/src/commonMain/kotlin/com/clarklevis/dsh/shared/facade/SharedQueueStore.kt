package com.clarklevis.dsh.shared.facade

import com.clarklevis.dsh.shared.gateway.GatewayRequest
import com.clarklevis.dsh.shared.gateway.GatewayRequests
import com.clarklevis.dsh.shared.protocol.JsonValue
import com.clarklevis.dsh.shared.protocol.wireJson

/** 只展示 Host 尚未执行的 queued 项；不把 steering/context 再显示成排队消息。 */
data class SharedQueueItem(
    val id: String,
    val text: String,
    val preview: String,
    val attachmentCount: Int,
    val editable: Boolean
)

data class SharedQueueSnapshot(
    val queues: Map<String, List<SharedQueueItem>> = emptyMap(),
    val pendingItemId: String? = null,
    val lastError: String? = null
)

/** 两端在各自 UI 串行上下文调用。队列以 Host 全量快照替换，操作不做乐观删除。 */
class SharedQueueStore {
    private var state = SharedQueueSnapshot()
    private var pending: Pending? = null
    private val drafts = mutableMapOf<String, String>()
    private data class Pending(val sessionId: String, val item: SharedQueueItem, val action: String)

    fun snapshot(): SharedQueueSnapshot = state

    fun reset(): SharedQueueSnapshot {
        drafts.clear()
        return resetConnection()
    }

    /** 同一 Host 重连只失效网络状态，保留已成功移出的待回填草稿。 */
    fun resetConnection(): SharedQueueSnapshot {
        pending = null
        state = SharedQueueSnapshot()
        return state
    }

    fun beginAction(sessionId: String, itemId: String, action: String): GatewayRequest? {
        if (pending != null || action !in setOf("edit", "remove", "steer")) return null
        val item = state.queues[sessionId]?.firstOrNull { it.id == itemId } ?: return null
        if (action == "edit" && !item.editable) return null
        pending = Pending(sessionId, item, action)
        state = state.copy(pendingItemId = itemId, lastError = null)
        // 铅笔按移动端交互要求：Host 确认移出后恢复到输入框，再次发送走 queue。
        return GatewayRequests.queueUpdate(sessionId, itemId, if (action == "edit") "remove" else action)
    }

    fun failPending(message: String): SharedQueueSnapshot {
        pending = null
        state = state.copy(pendingItemId = null, lastError = message)
        return state
    }

    fun takeDraft(sessionId: String): String? = drafts.remove(sessionId)

    fun acceptFrame(json: String): SharedQueueSnapshot {
        try {
            val frame = JsonValue.fromJsonElement(wireJson.parseToJsonElement(json))
            when (frame["kind"]?.stringValue) {
                "session-queues" -> {
                    val queues = requireNotNull(frame["queues"]?.objectValue)
                    state = state.copy(queues = queues.mapValues { parseItems(it.value) }, lastError = null)
                }
                "session-queue" -> {
                    val sessionId = requireNotNull(frame["sessionId"]?.stringValue)
                    state = state.copy(queues = state.queues + (sessionId to parseItems(requireNotNull(frame["items"]))), lastError = null)
                }
                "queue-item-updated" -> {
                    val operation = pending ?: return state
                    if (frame["sessionId"]?.stringValue != operation.sessionId ||
                        frame["itemId"]?.stringValue != operation.item.id ||
                        frame["action"]?.stringValue != (if (operation.action == "edit") "remove" else operation.action)
                    ) return state
                    if (frame["accepted"]?.booleanValue != true) return failPending("服务端未接受队列操作")
                    if (operation.action == "edit") {
                        drafts[operation.sessionId] = listOfNotNull(drafts[operation.sessionId], operation.item.text)
                            .joinToString("\n\n")
                    }
                    // 成功回执也移除条目，避免广播稍晚到达时仍可重复点击。
                    val remaining = state.queues[operation.sessionId].orEmpty().filterNot { it.id == operation.item.id }
                    pending = null
                    state = state.copy(queues = state.queues + (operation.sessionId to remaining), pendingItemId = null, lastError = null)
                }
                "error" -> {
                    val operation = pending ?: return state
                    if (frame["requestType"]?.stringValue == "queue-update" &&
                        frame["sessionId"]?.stringValue == operation.sessionId &&
                        (frame["itemId"] == null || frame["itemId"]?.stringValue == operation.item.id)
                    ) return failPending(frame["message"]?.stringValue ?: "队列操作失败")
                }
            }
        } catch (_: Exception) {
            state = state.copy(lastError = "队列状态格式无效")
        }
        return state
    }

    private fun parseItems(value: JsonValue): List<SharedQueueItem> =
        requireNotNull(value.arrayValue).mapNotNull { row ->
            if (row["placement"]?.stringValue != "queued") return@mapNotNull null
            val id = requireNotNull(row["id"]?.stringValue).also { require(it.isNotBlank()) }
            val content = requireNotNull(row["message"]?.get("content")?.arrayValue)
            val text = content.mapNotNull { if (it["type"]?.stringValue == "text") it["text"]?.stringValue else null }
                .joinToString("\n")
            val attachments = content.count { it["type"]?.stringValue != "text" }
            SharedQueueItem(id, text, text.ifBlank { "附件消息" }, attachments, attachments == 0 && text.isNotBlank())
        }.distinctBy { it.id }
}
