package com.clarklevis.dsh.shared.gateway

import com.clarklevis.dsh.shared.protocol.GatewayPairingPayload
import com.clarklevis.dsh.shared.protocol.GatewayQuestionAnswer
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class GatewayPairingPayloadException(message: String) : IllegalArgumentException(message)

/** 与 iOS PairingPayloadParser 相同的 version 2、严格 Base64URL 和过期语义。 */
object GatewayPairingPayloadParser {
    fun parse(rawValue: String, nowEpochMilliseconds: Long): GatewayPairingPayload {
        val normalized = rawValue.trim()
        val decoded = decodeBase64Url(normalized)
            ?: throw GatewayPairingPayloadException("配对内容不是有效的 Base64URL 字符串")
        val payload = runCatching {
            wireJson.decodeFromString<GatewayPairingPayload>(decoded.decodeToString())
        }.getOrElse {
            throw GatewayPairingPayloadException("Base64URL 解码后的内容不是有效的配对 JSON")
        }
        if (payload.version != 2) {
            throw GatewayPairingPayloadException("不支持的配对协议版本 ${payload.version}")
        }
        requireWebSocketEndpoint(payload.publicUrl)
        if (
            payload.pairingCode.isEmpty() ||
            ',' in payload.pairingCode ||
            payload.pairingCode.any { it.isWhitespace() || it.isISOControl() }
        ) {
            throw GatewayPairingPayloadException("二维码中的一次性 pairingCode 无效")
        }
        if (payload.expiresAt <= nowEpochMilliseconds) {
            throw GatewayPairingPayloadException("二维码配对码已经过期")
        }
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
    val payload: String
) {
    override fun toString(): String =
        "GatewayRequest(requestType=$requestType, responseKind=$responseKind, " +
            "targetSessionId=$targetSessionId, correlationId=<redacted>, " +
            "lanePolicy=$lanePolicy, payload=<redacted>)"
}

/** 请求字段逐项复制现有 iOS GatewayClient 与 Mobile Gateway 已验证协议。 */
object GatewayRequests {
    fun simple(type: String, responseKind: String = type): GatewayRequest =
        request(type, responseKind, lanePolicy = GatewayRequestLanePolicy.COALESCE_LATEST)

    fun ping(): GatewayRequest =
        request("ping", "pong", lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY)

    fun sessionControl(type: String, sessionId: String): GatewayRequest = request(
        type,
        type,
        targetSessionId = sessionId,
        lanePolicy = GatewayRequestLanePolicy.COALESCE_LATEST
    ) { put("sessionId", sessionId) }

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
        view: String? = null
    ): GatewayRequest = request(
        "history",
        "history",
        sessionId,
        lanePolicy = GatewayRequestLanePolicy.COALESCE_LATEST
    ) {
        put("sessionId", sessionId)
        put("maxMessages", maxMessages)
        beforeSequence?.let { put("beforeSeq", it) }
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

    fun subscribe(sessionId: String?): GatewayRequest = if (sessionId.isNullOrBlank()) {
        request("unsubscribe", "subscribed", lanePolicy = GatewayRequestLanePolicy.COALESCE_LATEST)
    } else {
        request(
            "subscribe",
            "subscribed",
            sessionId,
            lanePolicy = GatewayRequestLanePolicy.COALESCE_LATEST
        ) { put("sessionId", sessionId) }
    }

    fun message(
        text: String,
        images: List<GatewayOutgoingImage>,
        sessionId: String?,
        workspaceId: String?,
        clientTimeZone: String
    ): GatewayRequest = request(
        "message",
        "sent",
        sessionId,
        lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY
    ) {
        sessionId?.takeIf(String::isNotBlank)?.let { put("sessionId", it) }
        put("text", text)
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
        put("clientTimeZone", clientTimeZone)
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
}

internal fun requireWebSocketEndpoint(endpoint: String) {
    if (endpoint.any { it.isWhitespace() || it.isISOControl() }) {
        throw GatewayPairingPayloadException("publicUrl 不是有效的 ws:// 或 wss:// 地址")
    }
    val separator = endpoint.indexOf("://")
    if (separator <= 0) {
        throw GatewayPairingPayloadException("publicUrl 不是有效的 ws:// 或 wss:// 地址")
    }
    val scheme = endpoint.take(separator).lowercase()
    val authority = endpoint.drop(separator + 3).substringBefore('/').substringBefore('?')
    if (scheme !in setOf("ws", "wss") || authority.isBlank() || '@' in authority) {
        throw GatewayPairingPayloadException("publicUrl 不是有效的 ws:// 或 wss:// 地址")
    }
}

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
