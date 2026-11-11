package com.clarklevis.dsh.shared.protocol

import com.clarklevis.dsh.shared.i18n.UiMessage
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.contentOrNull

object GatewayWireDecoder {
    private data class DecodeFailure(
        val header: String,
        val missingFields: List<String>,
        val expected: String?,
        val actual: String?,
    )

    /**
     * Report schema metadata without copying message text or credentials from the wire frame.
     * args[0] is the technical header (`decode-failed [kind/event $.path] Exception`).
     */
    fun failureSummary(text: String, cause: Throwable): UiMessage {
        val failure = analyze(text, cause)
        return when {
            failure.missingFields.isNotEmpty() ->
                UiMessage("wire.decodeFailed.missingFields", listOf(failure.header, failure.missingFields.joinToString(", ")))
            failure.expected != null && failure.actual != null ->
                UiMessage("wire.decodeFailed.typeMismatch", listOf(failure.header, failure.expected, failure.actual))
            failure.expected != null -> UiMessage("wire.decodeFailed.typeExpected", listOf(failure.header, failure.expected))
            else -> UiMessage("wire.decodeFailed.invalidField", listOf(failure.header))
        }
    }

    /** English single-line form of [failureSummary] for diagnostics logs. */
    fun failureLogLine(text: String, cause: Throwable): String {
        val failure = analyze(text, cause)
        val detail = when {
            failure.missingFields.isNotEmpty() -> "missing required fields ${failure.missingFields.joinToString(", ")}"
            failure.expected != null && failure.actual != null ->
                "type mismatch: expected ${failure.expected}, got ${failure.actual}"
            failure.expected != null -> "type mismatch: expected ${failure.expected}"
            else -> "field does not match the protocol"
        }
        return "${failure.header}: $detail"
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun analyze(text: String, cause: Throwable): DecodeFailure {
        val root = runCatching { wireJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
        val kind = (root?.get("kind") as? JsonPrimitive)?.contentOrNull.safeLabel()
        val event = ((root?.get("event") as? JsonObject)?.get("type") as? JsonPrimitive)
            ?.contentOrNull.safeLabel()
        val location = listOfNotNull(kind, event).joinToString("/").ifEmpty { "unknown-frame" }
        val rawPath = Regex("at path:?\\s*(\\$[A-Za-z0-9_.\\[\\]-]+)")
            .find(cause.message.orEmpty())?.groupValues?.get(1)?.safePath()
        val path = rawPath?.let { candidate ->
            val first = candidate.removePrefix("\$.").substringBefore('.').substringBefore('[')
            val eventObject = root?.get("event") as? JsonObject
            val statsObject = root?.get("sessionStats") as? JsonObject
            if (first !in root.orEmpty() && first in eventObject.orEmpty()) {
                "\$.event${candidate.removePrefix("\$")}"
            } else if (kind == "session-stats" && first !in root.orEmpty() && first in statsObject.orEmpty()) {
                "\$.sessionStats${candidate.removePrefix("\$")}"
            } else candidate
        }
        val missingFields = (cause as? MissingFieldException)?.missingFields
            ?.mapNotNull { it.safeLabel() }
            .orEmpty().ifEmpty {
                listOfNotNull(Regex("Field '([A-Za-z][A-Za-z0-9_]{0,48})' is required")
                    .find(cause.message.orEmpty())?.groupValues?.get(1))
            }
        val expected = if (missingFields.isNotEmpty()) null else {
            Regex("Expected (?:an? )?(string|int|integer|boolean|number|array|object)", RegexOption.IGNORE_CASE)
                .find(cause.message.orEmpty())?.groupValues?.get(1)?.lowercase()
                ?.let { if (it == "int") "integer" else it }
                ?: when (path?.substringAfterLast('.')) {
                    "turn", "step", "seq", "sourceEventSeq", "shadowedItemCount", "shadowedTokenCount" -> "integer"
                    "turns", "steps", "ttftSteps", "lastTurn" -> "integer"
                    "llmMs", "toolMs", "ttftMs", "decodeMs", "decodeTokens", "startTime", "firstTokenTime" -> "number"
                    "isError", "interrupted", "replay", "accepted", "applied" -> "boolean"
                    else -> null
                }
        }
        val actual = if (expected == null) null else path?.let { findJsonType(root, it) }
        val header = "decode-failed [$location${path?.let { " $it" } ?: ""}] ${cause::class.simpleName ?: "DecodeException"}"
        return DecodeFailure(header, missingFields, expected, actual)
    }

    private fun String?.safeLabel(): String? = this?.takeIf {
        it.length in 1..64 && it.all { char -> char.isLetterOrDigit() || char == '-' || char == '/' || char == '_' }
    }

    private fun String.safePath(): String {
        val known = setOf("event", "error", "events", "data", "items", "queues", "sessionStats",
            "tokenUsage", "contextPressure", "todos", "goal", "workspace", "presets", "groups",
            "current", "selection", "message", "source", "images", "attachment", "questions",
            "options", "sessionPermissions", "ui", "turn", "step", "text", "toolCalls", "arguments", "content",
            "sessionId", "seq", "time", "kind", "type", "projections", "values",
            "turns", "steps", "llmMs", "toolMs", "ttftMs", "ttftSteps", "decodeMs", "decodeTokens",
            "lastTurn", "openStep", "pendingCalls", "startTime", "firstTokenTime")
        return Regex("[A-Za-z_][A-Za-z0-9_]*|\\[\\d+]").findAll(this)
            .map { token -> if (token.value.startsWith("[")) token.value else if (token.value in known) token.value else "?" }
            .joinToString(".") { it }
            .replace(".[", "[")
            .let { "\$.$it" }
    }

    private fun findJsonType(root: JsonObject?, path: String): String? {
        var value: kotlinx.serialization.json.JsonElement = root ?: return null
        for (segment in Regex("[A-Za-z_][A-Za-z0-9_]*|\\[\\d+]").findAll(path).map { it.value }) {
            value = when {
                segment.startsWith("[") -> (value as? JsonArray)?.getOrNull(segment.drop(1).dropLast(1).toIntOrNull() ?: return null) ?: return null
                else -> (value as? JsonObject)?.get(segment) ?: return null
            }
        }
        return when (value) {
            is JsonObject -> "object"
            is JsonArray -> "array"
            is JsonPrimitive -> when {
                value.isString -> "string"
                value.content == "true" || value.content == "false" -> "boolean"
                value.content == "null" -> "null"
                else -> "number"
            }
        }
    }

    fun decode(text: String): GatewayFrame {
        val parsed = wireJson.parseToJsonElement(text)
        val objectValue = parsed as? JsonObject
            ?: return wireJson.decodeFromJsonElement(GatewayFrame.serializer(), parsed)
        var normalized = if (
            "kind" !in objectValue &&
            objectValue["sessionId"] is JsonPrimitive &&
            objectValue["seq"] is JsonPrimitive &&
            objectValue["event"] is JsonObject
        ) {
            JsonObject(objectValue + ("kind" to JsonPrimitive("event")))
        } else {
            objectValue
        }
        // 语音 selection 是识别偏好；原 JSON 由专用公共 Store 完整解码。
        if ((normalized["kind"] as? JsonPrimitive)?.contentOrNull in setOf("speech-catalog", "speech-state")) {
            normalized = JsonObject(normalized - "selection")
        }
        val kind = (normalized["kind"] as? JsonPrimitive)?.contentOrNull
        // 新管理响应平铺完整任务，target 为对象，与旧设置响应的字符串 target 不同。
        if (kind in setOf("schedule-view", "schedule-create", "schedule-edit", "schedule-run")) {
            normalized = JsonObject((normalized - "target") + ("schedule" to normalized))
        }
        if (kind == "schedule-delete-inactive") {
            normalized = JsonObject((normalized - "deleted") + ("deletedCount" to (normalized["deleted"] ?: JsonPrimitive(0))))
        }
        val presets = normalized["presets"] as? JsonArray
        if (presets != null) {
            normalized = JsonObject(normalized + ("presets" to JsonArray(presets.map { item ->
                val preset = item as? JsonObject ?: return@map item
                val broken = preset["broken"] as? JsonPrimitive
                if (broken?.isString == true) JsonObject(preset + mapOf(
                    "broken" to JsonPrimitive(broken.content.isNotBlank()),
                    "brokenReason" to broken
                )) else preset
            })))
        }
        // 图片是可选增强：单张元数据损坏不能丢掉整条工具结果/助手回复。
        val event = normalized["event"] as? JsonObject
        if (event != null && "images" in event) {
            val images = (event["images"] as? JsonArray).orEmpty().filter { value ->
                runCatching { wireJson.decodeFromJsonElement<GatewayImageAttachment>(value) }
                    .getOrNull()?.isValidToolImage() == true
            }
            normalized = JsonObject(normalized + ("event" to JsonObject(event + ("images" to JsonArray(images)))))
        }
        return wireJson.decodeFromJsonElement(GatewayFrame.serializer(), normalized)
    }
}
