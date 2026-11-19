package com.clarklevis.dsh.shared.facade

import com.clarklevis.dsh.shared.gateway.GatewayRequests
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** 两端共用网关图片分块校验；字节由网关复用官方附件/工作区接口读取，单图最多 16 MiB。 */
class SharedBotImageTransfer(
    private val sessionId: String,
    private val attachmentId: String? = null,
    private val path: String? = null,
    private val author: String? = null,
    private val preview: Boolean = true
) {
    val endpoint: String = if (attachmentId == null) "markdown-image" else "image"
    var offset: Int = 0
        private set
    var finished: Boolean = false
        private set
    var error: String? = null
        private set
    var mediaType: String? = null
        private set
    var dataBase64: String? = null
        private set
    private var revision: String? = null
    private var expectedSize: Int? = null
    private val chunks = mutableListOf<ByteArray>()

    fun payloadJson(): String = buildJsonObject {
        put("sessionId", sessionId)
        put("preview", preview)
        put("offset", offset)
        attachmentId?.let { put("attachmentId", it) }
        path?.let { put("path", it) }
        author?.let { put("botId", it) }
    }.toString()

    fun requestJson(requestId: String): String = GatewayRequests.botCall(endpoint, payloadJson(), requestId).payload

    fun acceptJson(json: String): Boolean {
        if (finished || error != null || json.length > 4 * 1024 * 1024) return fail()
        val part = runCatching { wireJson.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return fail()
        fun text(key: String) = (part[key] as? JsonPrimitive)?.contentOrNull
        fun number(key: String) = (part[key] as? JsonPrimitive)?.intOrNull
        val mime = text("mime") ?: return fail()
        if (mime !in setOf("image/png", "image/jpeg", "image/webp", "image/gif", "image/bmp") ||
            (mediaType != null && mediaType != mime)) return fail()
        val encoded = text("data") ?: text("bytes") ?: return fail()
        val bytes = runCatching { Base64.decode(encoded) }.getOrNull() ?: return fail()
        if (bytes.isEmpty() || bytes.size > 16 * 1024 * 1024 - offset || chunks.size >= 44) return fail()
        val rev = text("rev")
        if (offset != 0 && rev != revision) return fail()
        revision = rev
        var done = true
        if (part.containsKey("nextOffset")) {
            val next = number("nextOffset") ?: return fail()
            val size = number("size") ?: return fail()
            if (expectedSize != null && expectedSize != size) return fail()
            expectedSize = size
            done = (part["done"] as? JsonPrimitive)?.booleanOrNull ?: return fail()
            if (number("offset") != offset || next != offset + bytes.size || size !in 1..16 * 1024 * 1024 ||
                next > size || done != (next == size)) return fail()
        }
        mediaType = mime
        chunks += bytes
        offset += bytes.size
        if (done) {
            val result = ByteArray(offset)
            var position = 0
            for (chunk in chunks) { chunk.copyInto(result, position); position += chunk.size }
            dataBase64 = Base64.encode(result)
            chunks.clear()
            finished = true
        }
        return true
    }

    private fun fail(): Boolean {
        error = "bot-image-invalid"
        chunks.clear()
        dataBase64 = null
        return false
    }
}
