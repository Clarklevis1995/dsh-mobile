package com.clarklevis.dsh.shared.gateway

import com.clarklevis.dsh.shared.i18n.UiMessage
import com.clarklevis.dsh.shared.i18n.UiMessageException
import com.clarklevis.dsh.shared.protocol.GatewayPairingPayload
import com.clarklevis.dsh.shared.protocol.GatewayApprovalOutcome
import com.clarklevis.dsh.shared.protocol.GatewayGoalRef
import com.clarklevis.dsh.shared.protocol.GatewayQuestionAnswer
import com.clarklevis.dsh.shared.protocol.JsonValue
import com.clarklevis.dsh.shared.protocol.GatewayTextPart
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.encodeToJsonElement
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 配对/地址校验失败；[uiMessage] 供界面本地化，`message` 为英文日志文本。 */
class GatewayPairingPayloadException(uiMessage: UiMessage, logMessage: String) : UiMessageException(uiMessage, logMessage)

/** 与 iOS PairingPayloadParser 相同的 version 2、严格 Base64URL 和过期语义。 */
object GatewayPairingPayloadParser {
    fun parse(rawValue: String, nowEpochMilliseconds: Long): GatewayPairingPayload {
        val normalized = rawValue.trim()
        val decoded = decodeBase64Url(normalized)
            ?: throw GatewayPairingPayloadException(UiMessage("pairing.notBase64Url"), "Pairing content is not a valid Base64URL string")
        val payload = runCatching {
            wireJson.decodeFromString<GatewayPairingPayload>(decoded.decodeToString())
        }.getOrElse {
            throw GatewayPairingPayloadException(UiMessage("pairing.invalidJson"), "Decoded Base64URL content is not valid pairing JSON")
        }
        if (payload.version != 2) {
            throw GatewayPairingPayloadException(
                UiMessage("pairing.unsupportedVersion", listOf(payload.version.toString())),
                "Unsupported pairing protocol version ${payload.version}"
            )
        }
        requireWebSocketEndpoint(payload.publicUrl)
        if (
            payload.pairingCode.isEmpty() ||
            ',' in payload.pairingCode ||
            payload.pairingCode.any { it.isWhitespace() || it.isISOControl() }
        ) {
            throw GatewayPairingPayloadException(UiMessage("pairing.invalidCode"), "The QR code's one-time pairingCode is invalid")
        }
        if (payload.expiresAt <= nowEpochMilliseconds) {
            throw GatewayPairingPayloadException(UiMessage("pairing.expired"), "The QR pairing code has expired")
        }
        GatewayIdentity.validate(null, payload.gatewayId)
        GatewayIdentity.endpoints(payload)
        return payload
    }
}

data class GatewayOutgoingImage(
    val mediaType: String,
    val base64Data: String,
    val name: String? = null
) {
    override fun toString(): String =
        "GatewayOutgoingImage(mediaType=$mediaType, base64Data=<redacted>, name=<redacted>)"
}

enum class GatewayRequestLanePolicy { COALESCE_LATEST, FIFO, REJECT_IF_BUSY }

data class GatewayRequest(
    val requestType: String,
    val responseKind: String,
    val targetSessionId: String? = null,
    val correlationId: String? = null,
    val lanePolicy: GatewayRequestLanePolicy = GatewayRequestLanePolicy.COALESCE_LATEST,
    val payload: String,
    val timeoutMilliseconds: Long? = null,
    // 仅 Runtime 本地关联元数据，永不写入网关 payload。
    val isBackgroundHistory: Boolean = false
) {
    override fun toString(): String =
        "GatewayRequest(requestType=$requestType, responseKind=$responseKind, " +
            "targetSessionId=$targetSessionId, correlationId=<redacted>, " +
            "lanePolicy=$lanePolicy, payload=<redacted>)"
}

/** 请求字段逐项复制现有 iOS GatewayClient 与 Mobile Gateway 已验证协议。 */
object GatewayRequests {
    private fun managementRequest(type: String, requestId: String, mutation: Boolean = false,
        responseKind: String = type, fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit
    ): GatewayRequest {
        require(requestId.isNotBlank() && requestId.length <= 128)
        return request(type, responseKind, correlationId = requestId,
            lanePolicy = if (mutation) GatewayRequestLanePolicy.REJECT_IF_BUSY else GatewayRequestLanePolicy.COALESCE_LATEST
        ) {
            put("requestId", requestId)
            fields()
        }.copy(timeoutMilliseconds = if (type == "plugin-update") 600_000 else null)
    }

    fun pluginVersions(requestId: String, name: String): GatewayRequest =
        managementRequest("plugin-versions", requestId) { put("name", name) }

    fun pluginDetail(requestId: String, name: String, language: String): GatewayRequest =
        managementRequest("plugin-detail", requestId) {
            put("name", name)
            put("language", language)
        }

    fun pluginUpdate(requestId: String, name: String, version: String, registry: String? = null): GatewayRequest =
        managementRequest("plugin-update", requestId, true, "plugin-update-result") {
            put("name", name)
            put("version", version)
            put("registry", registry?.let(::JsonPrimitive) ?: JsonNull)
        }

    fun hostScheduleList(requestId: String, active: Boolean? = null, limit: Int = 100, offset: Int = 0): GatewayRequest {
        require(limit in 1..500 && offset >= 0)
        return managementRequest("schedule-list", requestId) {
            active?.let { put("active", it) }
            put("limit", limit)
            put("offset", offset)
        }
    }

    fun scheduleView(requestId: String, id: String): GatewayRequest =
        managementRequest("schedule-view", requestId) { put("id", id) }

    fun scheduleCreate(requestId: String, title: String, prompt: String, timing: JsonValue,
        target: JsonValue? = null): GatewayRequest {
        require(title.trim().length in 1..120 && prompt.isNotBlank())
        return managementRequest("schedule-create", requestId, true) {
            put("title", title.trim())
            put("prompt", prompt)
            put("timing", timing.toJsonElement())
            target?.let { put("target", it.toJsonElement()) }
        }
    }

    fun scheduleEdit(requestId: String, id: String, expectedUpdatedAt: JsonValue?,
        patch: JsonValue? = null, delete: Boolean = false): GatewayRequest {
        require(delete || !patch?.objectValue.isNullOrEmpty())
        return managementRequest("schedule-edit", requestId, true) {
            put("id", id)
            put("action", if (delete) "delete" else "update")
            put("expectedUpdatedAt", expectedUpdatedAt?.toJsonElement() ?: JsonNull)
            if (!delete) put("patch", requireNotNull(patch).toJsonElement())
        }
    }

    fun hostScheduleHistory(requestId: String, id: String, limit: Int = 100, before: String? = null): GatewayRequest =
        managementRequest("schedule-history", requestId) {
            put("id", id)
            put("limit", limit)
            before?.let { put("before", it) }
        }

    fun scheduleRun(requestId: String, id: String): GatewayRequest =
        managementRequest("schedule-run", requestId, true) { put("id", id) }

    fun scheduleDeleteInactive(requestId: String, confirmed: Boolean): GatewayRequest {
        require(confirmed)
        return managementRequest("schedule-delete-inactive", requestId, true) { put("confirm", true) }
    }

    fun pluginCatalog(requestId: String): GatewayRequest = request(
        "plugin-catalog", "plugin-catalog", correlationId = requestId
    ) { put("requestId", requestId) }

    fun pluginBundleSetEnabled(requestId: String, name: String, enabled: Boolean): GatewayRequest = request(
        "plugin-bundle-set-enabled", "plugin-bundle-set-enabled", correlationId = requestId,
        lanePolicy = GatewayRequestLanePolicy.FIFO
    ) {
        put("requestId", requestId)
        put("name", name)
        put("enabled", enabled)
    }

    fun pluginEntrySetEnabled(requestId: String, entryId: String, enabled: Boolean): GatewayRequest = request(
        "plugin-entry-set-enabled", "plugin-entry-set-enabled", correlationId = requestId,
        lanePolicy = GatewayRequestLanePolicy.FIFO
    ) {
        put("requestId", requestId)
        put("entryId", entryId)
        put("enabled", enabled)
    }

    fun sessionAgentPreset(sessionId: String, requestId: String): GatewayRequest = request(
        "session-agent-preset", "session-agent-preset", targetSessionId = sessionId,
        correlationId = requestId
    ) {
        put("sessionId", sessionId)
        put("requestId", requestId)
    }

    fun selectAgentPreset(sessionId: String, agentPreset: String, requestId: String): GatewayRequest = request(
        "select-agent-preset", "select-agent-preset", targetSessionId = sessionId,
        correlationId = requestId, lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY
    ) {
        put("sessionId", sessionId)
        put("agentPreset", agentPreset)
        put("requestId", requestId)
    }

    fun simple(type: String, responseKind: String = type): GatewayRequest =
        request(type, responseKind, lanePolicy = GatewayRequestLanePolicy.COALESCE_LATEST)

    fun createSession(requestId: String, workspaceId: String?, agentPreset: String? = null): GatewayRequest = request(
        "session-create",
        "session-created",
        correlationId = requestId,
        lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY
    ) {
        put("requestId", requestId)
        workspaceId?.takeIf(String::isNotBlank)?.let { put("workspaceId", it) }
        agentPreset?.takeIf(String::isNotBlank)?.let { put("agentPreset", it) }
    }

    fun archiveSession(sessionId: String): GatewayRequest = request(
        "session-archive", "session-archived", targetSessionId = sessionId
    ) { put("sessionId", sessionId) }

    fun renameSession(sessionId: String, title: String): GatewayRequest = request(
        "session-rename", "session-renamed", targetSessionId = sessionId
    ) {
        put("sessionId", sessionId)
        put("title", title.trim())
    }

    fun ping(): GatewayRequest =
        request("ping", "pong", lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY)

    fun sessionControl(type: String, sessionId: String): GatewayRequest = request(
        type,
        type,
        targetSessionId = sessionId,
        lanePolicy = GatewayRequestLanePolicy.COALESCE_LATEST
    ) { put("sessionId", sessionId) }

    fun tasks(sessionId: String): GatewayRequest = sessionControl("tasks", sessionId)

    fun scheduleCatalog(): GatewayRequest = simple("schedule-catalog")

    fun scheduleList(sessionId: String): GatewayRequest = sessionControl("schedule-list", sessionId)

    fun scheduleHistory(sessionId: String, id: String, limit: Int, before: String? = null): GatewayRequest = request(
        "schedule-history", "schedule-history", targetSessionId = sessionId
    ) {
        put("sessionId", sessionId)
        put("id", id)
        put("limit", limit)
        before?.let { put("before", it) }
    }

    fun scheduleUpdate(
        sessionId: String,
        id: String,
        expected: JsonValue,
        title: String? = null,
        prompt: String? = null,
        change: JsonValue? = null,
        requestId: String? = null
    ): GatewayRequest = request(
        "schedule-update", "schedule-update", targetSessionId = sessionId,
        correlationId = requestId, lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY
    ) {
        put("sessionId", sessionId)
        put("id", id)
        put("expected", expected.toJsonElement())
        title?.let { put("title", it) }
        prompt?.let { put("prompt", it) }
        change?.let { put("change", it.toJsonElement()) }
        requestId?.let { put("requestId", it) }
    }

    fun scheduleDelete(sessionId: String, id: String, requestId: String? = null): GatewayRequest = request(
        "schedule-delete", "schedule-delete", targetSessionId = sessionId,
        correlationId = requestId, lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY
    ) {
        put("sessionId", sessionId)
        put("id", id)
        requestId?.let { put("requestId", it) }
    }

    fun goal(sessionId: String): GatewayRequest = sessionControl("goal", sessionId)

    fun editGoal(
        sessionId: String,
        ref: GatewayGoalRef,
        objective: String? = null,
        maxGoalRounds: Int? = null
    ): GatewayRequest = request(
        "goal-edit",
        "goal-edit",
        targetSessionId = sessionId,
        lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY
    ) {
        put("sessionId", sessionId)
        put("ref", buildJsonObject {
            put("id", ref.id)
            put("revision", ref.revision)
        })
        objective?.trim()?.takeIf(String::isNotEmpty)?.let { put("objective", it) }
        maxGoalRounds?.takeIf { it > 0 }?.let { put("maxGoalRounds", it) }
    }

    fun goalAction(
        type: String,
        sessionId: String,
        ref: GatewayGoalRef
    ): GatewayRequest {
        require(type in GOAL_ACTION_TYPES)
        return request(
            type,
            type,
            targetSessionId = sessionId,
            lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY
        ) {
            put("sessionId", sessionId)
            put("ref", buildJsonObject {
                put("id", ref.id)
                put("revision", ref.revision)
            })
        }
    }

    fun search(query: String): GatewayRequest = request(
        "search",
        "search",
        lanePolicy = GatewayRequestLanePolicy.COALESCE_LATEST
    ) { put("query", query) }

    fun directories(path: String? = null): GatewayRequest = request(
        "directories",
        "directories",
        lanePolicy = GatewayRequestLanePolicy.COALESCE_LATEST
    ) {
        path?.takeIf(String::isNotBlank)?.let { put("path", it) }
    }

    fun createDirectory(path: String, name: String): GatewayRequest = request(
        "directory-create",
        "directory-create",
        lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY
    ) {
        put("path", path)
        put("name", name)
    }

    fun createWorkspace(path: String): GatewayRequest = request(
        "workspace-create",
        "workspace-create",
        lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY
    ) {
        put("path", path)
    }

    fun setDefault(target: String, value: String): GatewayRequest = request(
        "set-default",
        "set-default",
        lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY
    ) {
        put("target", target)
        put("value", value)
    }

    fun selectModel(
        sessionId: String,
        provider: String,
        model: String,
        reasoningEffort: String?
    ): GatewayRequest = request(
        "select-model",
        "select-model",
        targetSessionId = sessionId,
        lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY
    ) {
        put("sessionId", sessionId)
        put("provider", provider)
        put("model", model)
        reasoningEffort?.takeIf(String::isNotBlank)?.let { put("reasoningEffort", it) }
    }

    fun setPermission(sessionId: String, name: String): GatewayRequest = request(
        "permission",
        "permission",
        targetSessionId = sessionId,
        lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY
    ) {
        put("sessionId", sessionId)
        put("name", name)
    }

    fun slashCommands(sessionId: String, locale: String? = null): GatewayRequest = request(
        "commands",
        "commands",
        targetSessionId = sessionId,
        lanePolicy = GatewayRequestLanePolicy.COALESCE_LATEST
    ) {
        put("sessionId", sessionId)
        locale?.takeIf(String::isNotBlank)?.let { put("locale", it) }
    }

    fun slashCommandOptions(sessionId: String, command: String): GatewayRequest = request(
        "command-options",
        "command-options",
        targetSessionId = sessionId,
        correlationId = command,
        lanePolicy = GatewayRequestLanePolicy.COALESCE_LATEST
    ) {
        put("sessionId", sessionId)
        put("command", command)
    }

    fun selectSlashCommandOption(
        sessionId: String,
        command: String,
        optionId: String
    ): GatewayRequest = request(
        "command-select",
        "command-selected",
        targetSessionId = sessionId,
        correlationId = command,
        lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY
    ) {
        put("sessionId", sessionId)
        put("command", command)
        put("optionId", optionId)
    }

    fun saveDefaultModel(
        provider: String,
        model: String,
        reasoningEffort: String?
    ): GatewayRequest = request(
        "save-default-model",
        "save-default-model",
        lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY
    ) {
        put("provider", provider)
        put("model", model)
        reasoningEffort?.takeIf(String::isNotBlank)?.let { put("reasoningEffort", it) }
    }

    fun history(
        sessionId: String,
        beforeSequence: Int? = null,
        maxMessages: Int = 50,
        maxBytes: Int? = null,
        view: String? = null,
        historyFormatVersion: Int? = null
    ): GatewayRequest = request(
        "history",
        "history",
        sessionId,
        lanePolicy = GatewayRequestLanePolicy.COALESCE_LATEST
    ) {
        put("sessionId", sessionId)
        put("maxMessages", maxMessages)
        beforeSequence?.let {
            require(it >= 0 && historyFormatVersion != null) { "History cursor must carry the format version it was read with" }
            put("beforeSeq", it)
            put("historyFormatVersion", historyFormatVersion)
        }
        maxBytes?.let { put("maxBytes", it) }
        view?.takeIf(String::isNotBlank)?.let { put("view", it) }
    }

    fun attachment(sessionId: String, attachmentId: String): GatewayRequest =
        request(
            "attachment",
            "attachment",
            sessionId,
            correlationId = attachmentId,
            lanePolicy = GatewayRequestLanePolicy.FIFO
        ) {
            put("sessionId", sessionId)
            put("attachmentId", attachmentId)
        }

    fun fileList(sessionId: String, path: String?, requestId: String): GatewayRequest = request(
        "file-list",
        "file-list",
        targetSessionId = sessionId,
        correlationId = requestId,
        lanePolicy = GatewayRequestLanePolicy.COALESCE_LATEST
    ) {
        put("requestId", requestId)
        put("sessionId", sessionId)
        path?.takeIf(String::isNotBlank)?.let { put("path", it) }
    }

    fun fileResolve(sessionId: String, path: String, requestId: String): GatewayRequest = request(
        "file-resolve", "file-resolved", targetSessionId = sessionId, correlationId = requestId,
        lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY
    ) {
        put("requestId", requestId)
        put("sessionId", sessionId)
        put("path", path)
    }

    fun fileDownloadOpen(
        sessionId: String,
        path: String,
        requestId: String
    ): GatewayRequest = request(
        "file-download-open",
        "file-download-opened",
        targetSessionId = sessionId,
        correlationId = requestId,
        lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY
    ) {
        put("requestId", requestId)
        put("sessionId", sessionId)
        put("path", path)
    }

    fun fileDownloadRead(transferId: String, offset: Long): GatewayRequest = request(
        "file-download-read",
        "file-download-chunk",
        correlationId = transferId,
        lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY
    ) {
        put("transferId", transferId)
        put("offset", offset)
    }

    fun fileDownloadCancel(transferId: String): GatewayRequest = request(
        "file-download-cancel",
        "file-download-cancelled",
        correlationId = transferId,
        lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY
    ) {
        put("transferId", transferId)
    }

    fun subscribe(sessionId: String?): GatewayRequest = if (sessionId.isNullOrBlank()) {
        request("unsubscribe", "subscribed", lanePolicy = GatewayRequestLanePolicy.COALESCE_LATEST)
    } else {
        request(
            "subscribe",
            "subscribed",
            sessionId,
            lanePolicy = GatewayRequestLanePolicy.COALESCE_LATEST
        ) {
            put("sessionId", sessionId)
            put("assistantStream", true)
        }
    }

    /** Stops only the active turn; a later ordinary message resumes the same session. */
    fun sessionCancel(sessionId: String): GatewayRequest = request(
        "session-cancel",
        "session-cancelled",
        targetSessionId = sessionId,
        lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY
    ) {
        put("sessionId", sessionId)
    }

    fun message(
        text: String,
        images: List<GatewayOutgoingImage>,
        sessionId: String?,
        workspaceId: String?,
        clientTimeZone: String,
        mode: String = "queue",
        receipts: List<String> = emptyList(),
        requestId: String? = null,
        textParts: List<GatewayTextPart>? = null
    ): GatewayRequest = request(
        "message",
        "sent",
        sessionId,
        correlationId = requestId,
        lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY
    ) {
        sessionId?.takeIf(String::isNotBlank)?.let { put("sessionId", it) }
        require(receipts.size <= 20 && receipts.distinct().size == receipts.size)
        require(receipts.isEmpty() || (!sessionId.isNullOrBlank() && !requestId.isNullOrBlank()))
        requestId?.let { put("requestId", it) }
        if (receipts.isNotEmpty()) put("files", buildJsonArray {
            receipts.forEach { add(buildJsonObject { put("receiptId", it) }) }
        })
        if (textParts == null) put("text", text) else {
            GatewayTextPart.validate(textParts)
            put("textParts", wireJson.encodeToJsonElement(textParts))
        }
        put("images", buildJsonArray {
            images.forEach { image ->
                add(buildJsonObject {
                    put("mediaType", image.mediaType)
                    put("data", image.base64Data)
                    image.name?.takeIf(String::isNotBlank)?.let { put("name", it) }
                })
            }
        })
        if (sessionId.isNullOrBlank()) {
            workspaceId?.takeIf(String::isNotBlank)?.let { put("workspaceId", it) }
        }
        require(mode == "queue" || mode == "steer")
        put("mode", mode)
        put("clientTimeZone", clientTimeZone)
    }

    fun queueEdit(sessionId: String, itemId: String, requestId: String, text: String,
        textParts: List<GatewayTextPart>? = null): GatewayRequest = request(
        "queue-update", "queue-item-updated", targetSessionId = sessionId, correlationId = itemId,
        lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY
    ) {
        put("sessionId", sessionId); put("itemId", itemId); put("requestId", requestId); put("action", "edit")
        if (textParts == null) put("text", text) else {
            GatewayTextPart.validate(textParts)
            put("textParts", wireJson.encodeToJsonElement(textParts))
        }
    }

    fun queueUpdate(sessionId: String, itemId: String, action: String): GatewayRequest = request(
        "queue-update", "queue-item-updated", targetSessionId = sessionId, correlationId = itemId,
        lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY
    ) {
        require(action == "remove" || action == "steer")
        put("sessionId", sessionId)
        put("itemId", itemId)
        put("action", action)
    }

    fun commandExecute(
        sessionId: String,
        line: String,
        images: List<GatewayOutgoingImage>
    ): GatewayRequest = request(
        "command-execute",
        "command-executed",
        sessionId,
        lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY
    ) {
        put("sessionId", sessionId)
        put("line", line)
        put("images", buildJsonArray {
            images.forEach { image ->
                add(buildJsonObject {
                    put("mediaType", image.mediaType)
                    put("data", image.base64Data)
                    image.name?.takeIf(String::isNotBlank)?.let { put("name", it) }
                })
            }
        })
    }

    fun questionAnswer(
        rpcId: String,
        sessionId: String,
        answers: List<GatewayQuestionAnswer>
    ): GatewayRequest = request(
        "question-answer",
        "question-response",
        sessionId,
        correlationId = rpcId,
        lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY
    ) {
        put("rpcId", rpcId)
        put("sessionId", sessionId)
        put("answers", JsonArray(answers.map { answer ->
            JsonObject(buildMap {
                put("id", JsonPrimitive(answer.id))
                put("selected", JsonArray(answer.selected.map(::JsonPrimitive)))
                answer.normalizedCustom?.let { put("custom", JsonPrimitive(it)) }
            })
        }))
    }

    fun questionCancel(rpcId: String, sessionId: String): GatewayRequest =
        request(
            "question-cancel",
            "question-response",
            sessionId,
            correlationId = rpcId,
            lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY
        ) {
            put("rpcId", rpcId)
            put("sessionId", sessionId)
        }

    fun approvalResponse(
        rpcId: String,
        sessionId: String,
        approvalId: String,
        outcome: GatewayApprovalOutcome
    ): GatewayRequest = request(
        "approval-response",
        "approval-response",
        sessionId,
        correlationId = rpcId,
        lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY
    ) {
        put("rpcId", rpcId)
        put("sessionId", sessionId)
        put("approvalId", approvalId)
        put("outcome", when (outcome) {
            GatewayApprovalOutcome.ALLOWED_ONCE -> "allowed-once"
            GatewayApprovalOutcome.REJECTED -> "rejected"
        })
    }

    private fun request(
        type: String,
        responseKind: String,
        targetSessionId: String? = null,
        correlationId: String? = null,
        lanePolicy: GatewayRequestLanePolicy = GatewayRequestLanePolicy.COALESCE_LATEST,
        content: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}
    ): GatewayRequest {
        val json = buildJsonObject {
            put("type", type)
            content()
        }
        return GatewayRequest(
            type,
            responseKind,
            targetSessionId,
            correlationId,
            lanePolicy,
            wireJson.encodeToString(json)
        )
    }

    private val GOAL_ACTION_TYPES = setOf("goal-pause", "goal-resume", "goal-clear")
}

internal fun requireWebSocketEndpoint(endpoint: String) {
    if (endpoint.any { it.isWhitespace() || it.isISOControl() }) {
        throw invalidWebSocketEndpoint()
    }
    val separator = endpoint.indexOf("://")
    if (separator <= 0) {
        throw invalidWebSocketEndpoint()
    }
    val scheme = endpoint.take(separator).lowercase()
    val authority = endpoint.drop(separator + 3).substringBefore('/').substringBefore('?')
    if (scheme !in setOf("ws", "wss") || authority.isBlank() || '@' in authority) {
        throw invalidWebSocketEndpoint()
    }
}

private fun invalidWebSocketEndpoint() = GatewayPairingPayloadException(
    UiMessage("pairing.invalidPublicUrl"),
    "publicUrl is not a valid ws:// or wss:// address"
)

internal fun decodeBase64(value: String): ByteArray? = decodeBase64Alphabet(value, urlSafe = false)

private fun decodeBase64Url(value: String): ByteArray? {
    if (
        value.isEmpty() ||
        '=' in value ||
        value.length % 4 == 1 ||
        value.any { !(it.isLetterOrDigit() || it == '-' || it == '_') }
    ) {
        return null
    }
    return decodeBase64Alphabet(value, urlSafe = true)
}

private fun decodeBase64Alphabet(value: String, urlSafe: Boolean): ByteArray? {
    val alphabet = if (urlSafe) {
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    } else {
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    }
    val clean = if (urlSafe) value else value.trimEnd('=')
    if (clean.length % 4 == 1 || clean.any { alphabet.indexOf(it) < 0 }) return null
    val output = ByteArray((clean.length * 6) / 8)
    var accumulator = 0
    var bitCount = 0
    var outputIndex = 0
    clean.forEach { character ->
        accumulator = (accumulator shl 6) or alphabet.indexOf(character)
        bitCount += 6
        if (bitCount >= 8) {
            bitCount -= 8
            output[outputIndex++] = ((accumulator shr bitCount) and 0xff).toByte()
        }
    }
    return output.copyOf(outputIndex)
}
