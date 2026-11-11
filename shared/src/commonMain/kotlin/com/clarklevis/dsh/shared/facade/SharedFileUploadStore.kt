package com.clarklevis.dsh.shared.facade

import com.clarklevis.dsh.shared.gateway.GatewayRequest
import com.clarklevis.dsh.shared.gateway.GatewayRequestLanePolicy
import com.clarklevis.dsh.shared.i18n.UiMessage
import com.clarklevis.dsh.shared.i18n.UiMessageException
import com.clarklevis.dsh.shared.protocol.GatewayFrame
import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder
import com.clarklevis.dsh.shared.protocol.PresentedFile
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 只保存元数据。私有文件副本、摘要和逐块读取由平台在后台完成。 */
data class SharedUploadDraft(
    val id: String,
    val name: String,
    val size: Long,
    val sha256: String,
    val status: String = "waiting",
    val confirmedBytes: Long = 0,
    val receiptId: String? = null,
    val file: PresentedFile? = null,
    val error: UiMessage? = null
)

data class SharedUploadRead(val id: String, val offset: Long, val length: Int, val token: String)
data class SharedUploadTransition(
    val drafts: List<SharedUploadDraft>,
    val requests: List<GatewayRequest> = emptyList(),
    val read: SharedUploadRead? = null,
    val watch: GatewayRequest? = null
)

/**
 * 单飞有界队列：同一 uploadId 永远只有一个在途操作，以确认偏移推进。
 * 实例属于一个 gateway；bind 的连接代际或会话变化会使所有凭证失效。
 */
class SharedFileUploadStore {
    private val drafts = mutableListOf<SharedUploadDraft>()
    private var binding: String? = null
    private var connectionKey: String? = null
    private val finishRequestIds = mutableSetOf<String>()
    private val abandonedOpens = linkedMapOf<String, Pair<String?, String?>>()
    private var sessionId: String? = null
    private var available = false
    private var activeId: String? = null
    private var uploadId: String? = null
    private var pending: GatewayRequest? = null
    private var reading: SharedUploadRead? = null
    private var chunkBytes = 0
    private var expectedOffset = 0L
    private var serial = 0L
    private var finishRetries = 0
    private var submissionKey: String? = null
    private var submissionId: String? = null

    fun snapshot(): List<SharedUploadDraft> = drafts.toList()
    fun canSend(): Boolean = available && drafts.isNotEmpty() && drafts.all { it.status == "ready" }
    fun receipts(): List<String> = if (canSend()) drafts.mapNotNull { it.receiptId } else emptyList()

    fun bind(sessionId: String?, connectionKey: String?, available: Boolean): SharedUploadTransition {
        val next = if (sessionId != null && connectionKey != null) "$connectionKey/$sessionId" else null
        val cancels = if ((next != binding || !available) && connectionKey == this.connectionKey && this.available) cancellation() else emptyList()
        if (next != binding || !available) {
            clearActive()
            drafts.indices.forEach { index ->
                drafts[index] = drafts[index].copy(status = "error", receiptId = null, confirmedBytes = 0,
                    error = UiMessage("upload.sessionChanged"))
            }
            submissionKey = null
            submissionId = null
        }
        binding = next
        this.connectionKey = connectionKey
        abandonedOpens.entries.removeAll { it.value.first != connectionKey }
        this.sessionId = sessionId
        this.available = available && next != null
        return transition(cancels)
    }

    fun add(id: String, name: String, size: Long, sha256: String): SharedUploadTransition {
        if (!available || drafts.size >= 20 || drafts.any { it.id == id }) return transition()
        if (name.isBlank() || name.length > 255 || name in setOf(".", "..") ||
            name.any { it == '/' || it == '\\' || it.isISOControl() }) {
            throw UiMessageException(UiMessage("upload.invalidName"), "Invalid file name")
        }
        if (size !in 0..MAX_BYTES) throw UiMessageException(UiMessage("upload.tooLarge"), "File exceeds 512 MiB")
        if (!sha256.matches(Regex("[a-fA-F0-9]{64}"))) {
            throw UiMessageException(UiMessage("upload.invalidDigest"), "Invalid file digest")
        }
        drafts += SharedUploadDraft(id, name, size, sha256.lowercase())
        return pump()
    }

    fun retry(id: String): SharedUploadTransition {
        if (!available) return transition()
        update(id) { if (it.status == "error") it.copy(status = "waiting", error = null, receiptId = null, confirmedBytes = 0) else it }
        return pump()
    }

    fun remove(id: String): SharedUploadTransition {
        val cancels = if (id == activeId) cancellation() else emptyList()
        if (id == activeId) clearActive()
        drafts.removeAll { it.id == id }
        val next = pump()
        return next.copy(requests = cancels + next.requests)
    }

    fun clear(): SharedUploadTransition {
        val cancels = cancellation()
        clearActive()
        drafts.clear()
        submissionKey = null
        submissionId = null
        return transition(cancels)
    }

    /** 同一次失败提交保持 requestId；正文、图片或文件集合变更才建立新提交。 */
    fun submission(text: String, imageKey: String, proposedId: String): String {
        val key = listOf(binding, text, imageKey, drafts.map { it.id to it.receiptId }).toString()
        if (key != submissionKey) { submissionKey = key; submissionId = proposedId }
        return submissionId ?: proposedId
    }

    fun invalidateReceipts(): SharedUploadTransition {
        drafts.indices.forEach { index ->
            if (drafts[index].status == "ready") drafts[index] = drafts[index].copy(
                status = "error", receiptId = null, error = UiMessage("upload.receiptExpired")
            )
        }
        submissionKey = null
        submissionId = null
        return transition()
    }

    fun acceptFrame(json: String): SharedUploadTransition = accept(GatewayWireDecoder.decode(json))
    fun accept(frame: GatewayFrame): SharedUploadTransition {
        val abandoned = abandonedOpens[frame.requestId]
        if (abandoned != null && frame.kind == "file-upload-opened" && frame.uploadId != null &&
            abandoned.first == connectionKey && abandoned.second == frame.sessionId) {
            abandonedOpens.remove(frame.requestId)
            val cancel = request("cancel", "file-upload-cancelled") { put("uploadId", frame.uploadId) }
                .copy(targetSessionId = frame.sessionId)
            return transition(listOf(cancel))
        }
        val request = pending ?: return transition()
        val earlierFinish = frame.kind == "file-upload-completed" && frame.requestId in finishRequestIds
        if (frame.requestId != request.correlationId && !earlierFinish) return transition()
        if (frame.kind == "error") {
            if (frame.code == "file-upload-busy" && request.requestType == "file-upload-finish") {
                return transition().copy(watch = request)
            }
            return fail((frame.message ?: frame.code)?.let { UiMessage.raw(it) } ?: UiMessage("upload.failed"))
        }
        if (frame.kind != request.responseKind || frame.sessionId != sessionId) {
            if (request.requestType == "file-upload-open" && frame.kind == "file-upload-opened") uploadId = frame.uploadId
            return fail(UiMessage("upload.responseMismatch"))
        }
        val id = activeId ?: return transition()
        val draft = drafts.firstOrNull { it.id == id } ?: return transition()
        if (frame.kind != "file-upload-opened" && frame.uploadId != uploadId) return fail(UiMessage("upload.responseMismatch"))
        pending = null
        when (frame.kind) {
            "file-upload-opened" -> {
                uploadId = frame.uploadId
                val length = frame.chunkBytes ?: 0
                if (uploadId.isNullOrBlank() || frame.size != draft.size || length !in 1..MAX_CHUNK_BYTES ||
                    (frame.maxBytes != null && draft.size > frame.maxBytes)) return fail(UiMessage("upload.responseInvalid"))
                chunkBytes = length
                return readOrFinish()
            }
            "file-upload-chunk-accepted" -> {
                if (frame.nextOffset != expectedOffset) return fail(UiMessage("upload.offsetInvalid"))
                update(id) { it.copy(confirmedBytes = expectedOffset) }
                return readOrFinish()
            }
            "file-upload-completed" -> {
                val file = frame.file
                if (frame.receiptId.isNullOrBlank() || frame.sha256?.lowercase() != draft.sha256 ||
                    file?.attachmentId.isNullOrBlank() || file.bytes != draft.size || file.name.isNullOrBlank()) {
                    return fail(UiMessage("upload.verifyFailed"))
                }
                update(id) { it.copy(status = "ready", confirmedBytes = it.size, receiptId = frame.receiptId, file = file, error = null) }
                clearActive()
                return pump()
            }
        }
        return transition()
    }

    fun chunkReady(token: String, data: String, bytes: Int): SharedUploadTransition {
        val read = reading?.takeIf { it.token == token } ?: return transition()
        if (bytes != read.length || data.isEmpty()) return fail(UiMessage("upload.readFailed"))
        reading = null
        expectedOffset = read.offset + bytes
        val request = request("chunk", "file-upload-chunk-accepted") {
            put("uploadId", uploadId); put("offset", read.offset); put("data", data)
        }
        pending = request
        return transition(listOf(request))
    }

    fun readFailed(token: String, message: UiMessage): SharedUploadTransition =
        if (reading?.token == token) fail(message) else transition()

    /** 只有 finish 可幂等重试；chunk 回执丢失必须取消并从头开始。 */
    fun timedOut(requestId: String): SharedUploadTransition {
        val request = pending?.takeIf { it.correlationId == requestId } ?: return transition()
        if (request.requestType == "file-upload-finish" && finishRetries++ < 4 && available) {
            return finish()
        }
        return fail(UiMessage("upload.timeout"))
    }

    private fun pump(): SharedUploadTransition {
        if (!available || activeId != null) return transition()
        val draft = drafts.firstOrNull { it.status == "waiting" } ?: return transition()
        activeId = draft.id
        finishRetries = 0
        update(draft.id) { it.copy(status = "opening") }
        val request = request("open", "file-upload-opened") {
            put("sessionId", sessionId); put("name", draft.name); put("size", draft.size); put("sha256", draft.sha256)
        }
        pending = request
        return transition(listOf(request))
    }

    private fun readOrFinish(): SharedUploadTransition {
        val draft = drafts.first { it.id == activeId }
        if (draft.confirmedBytes == draft.size) return finish()
        update(draft.id) { it.copy(status = "uploading") }
        val read = SharedUploadRead(draft.id, draft.confirmedBytes,
            minOf(chunkBytes.toLong(), draft.size - draft.confirmedBytes).toInt(), nextId())
        reading = read
        return SharedUploadTransition(snapshot(), read = read)
    }

    private fun finish(): SharedUploadTransition {
        activeId?.let { id -> update(id) { it.copy(status = "committing") } }
        val request = request("finish", "file-upload-completed") { put("uploadId", uploadId) }
        pending = request
        finishRequestIds += requireNotNull(request.correlationId)
        return transition(listOf(request))
    }

    private fun fail(message: UiMessage): SharedUploadTransition {
        val cancels = cancellation()
        activeId?.let { id -> update(id) { it.copy(status = "error", receiptId = null, error = message) } }
        clearActive()
        val next = pump()
        return next.copy(requests = cancels + next.requests)
    }

    private fun cancellation(): List<GatewayRequest> = uploadId?.let { upload ->
        listOf(request("cancel", "file-upload-cancelled") { put("uploadId", upload) })
    }.orEmpty()
    private fun clearActive() {
        pending?.takeIf { it.requestType == "file-upload-open" }?.correlationId?.let { id ->
            abandonedOpens[id] = connectionKey to sessionId
            if (abandonedOpens.size > 64) abandonedOpens.remove(abandonedOpens.keys.first())
        }
        finishRequestIds.clear()
        activeId = null
        uploadId = null
        pending = null
        reading = null
    }
    private fun update(id: String, block: (SharedUploadDraft) -> SharedUploadDraft) {
        val index = drafts.indexOfFirst { it.id == id }
        if (index >= 0) drafts[index] = block(drafts[index])
    }
    private fun nextId(): String = "upload-${++serial}"
    private fun request(action: String, kind: String, fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): GatewayRequest {
        val id = nextId()
        val type = "file-upload-$action"
        return GatewayRequest(type, kind, targetSessionId = sessionId, correlationId = id,
            lanePolicy = GatewayRequestLanePolicy.FIFO, timeoutMilliseconds = if (action == "finish") 60_000 else 30_000,
            payload = buildJsonObject { put("type", type); put("requestId", id); fields() }.toString())
    }
    private fun transition(requests: List<GatewayRequest> = emptyList()) = SharedUploadTransition(snapshot(), requests)

    companion object {
        const val MAX_BYTES = 536_870_912L
        private const val MAX_CHUNK_BYTES = 4 * 1024 * 1024
    }
}
