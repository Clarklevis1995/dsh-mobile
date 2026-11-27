package com.clarklevis.dsh.shared.projection

import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
enum class GenuiActionType { SEND_MESSAGE, COPY_TEXT, EXPAND_DETAILS }

@Serializable
data class GenuiAction(
    val type: GenuiActionType,
    val blockId: String = "",
    val text: String? = null,
    val details: String? = null,
    val action: String? = null,
    val payloadJson: String? = null
)

@Serializable
data class GenuiActionIntent(
    val sessionId: String,
    val activeSessionId: String?,
    val blockId: String,
    val contentVersion: String,
    val actionJson: String,
    val isSearchContext: Boolean = false
)

@Serializable
data class GenuiActionEffect(
    val type: GenuiActionType,
    val sessionId: String,
    val blockId: String,
    val contentVersion: String,
    val text: String? = null,
    val details: String? = null
)

object GenuiActionCodec {
    const val MAX_ACTION_CHARS = 16_384
    const val MAX_ACTION_NAME_CHARS = 128
    const val MAX_TEXT_CHARS = 8_192
    const val MAX_PAYLOAD_CHARS = 7_000
    private const val MAX_DEPTH = 32

    fun decode(actionJson: String, expectedBlockId: String? = null): GenuiAction? = try {
        if (actionJson.length > MAX_ACTION_CHARS || actionJson.isBlank() ||
            (expectedBlockId != null && (expectedBlockId.isBlank() || expectedBlockId.length > 512)) ||
            !safeJsonStructure(actionJson)
        ) {
            null
        } else {
            val root = Json.parseToJsonElement(actionJson) as? JsonObject
            val version = root?.primitive("version")
            val rawAction = root?.string("action")
            val payload = root?.get("payload") as? JsonObject
            if (root == null || version == null || version.isString || version.content != "1" ||
                rawAction == null || payload == null
            ) {
                null
            } else {
                val action = rawAction.replace("\u0000", "").trim()
                if (action.isEmpty() || action.length > MAX_ACTION_NAME_CHARS ||
                    action.any { it == '\n' || it == '\r' }
                ) {
                    null
                } else {
                    val payloadJson = payload.toString()
                    if (payloadJson.length > MAX_PAYLOAD_CHARS) {
                        null
                    } else {
                        val suppliedText = payload.textValue()
                        val blockId = expectedBlockId.orEmpty()
                        when (action.lowercase()) {
                            "copy", "copy-text" -> cleanText(suppliedText)?.let { text ->
                                GenuiAction(
                                    type = GenuiActionType.COPY_TEXT,
                                    blockId = blockId,
                                    text = text,
                                    action = action,
                                    payloadJson = payloadJson
                                )
                            }
                            "expand", "expand-details", "details" -> cleanText(payload.detailsValue() ?: suppliedText)?.let { details ->
                                GenuiAction(
                                    type = GenuiActionType.EXPAND_DETAILS,
                                    blockId = blockId,
                                    details = details,
                                    action = action,
                                    payloadJson = payloadJson
                                )
                            }
                            else -> {
                                // Preserve both the machine-readable action name and the bounded structured
                                // payload (choices, switch, slider, button, submit answers & fields without
                                // collapsing same-named keys). Always prefixed with `[genui-action]` so slash
                                // commands can never be injected.
                                val semanticText = if (payload.isEmpty()) {
                                    "[genui-action] $action"
                                } else {
                                    "[genui-action] $action $payloadJson"
                                }
                                cleanText(semanticText)?.let { message ->
                                    GenuiAction(
                                        type = GenuiActionType.SEND_MESSAGE,
                                        blockId = blockId,
                                        text = message,
                                        action = action,
                                        payloadJson = payloadJson
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    } catch (_: Throwable) {
        null
    }

    fun encode(action: GenuiAction): String = wireJson.encodeToString(action)

    private fun JsonObject.string(name: String): String? =
        (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.primitive(name: String): JsonPrimitive? = get(name) as? JsonPrimitive

    private fun JsonObject.textValue(): String? = listOf("text", "value", "content", "label", "message")
        .firstNotNullOfOrNull { name ->
            (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
        }

    private fun JsonObject.detailsValue(): String? = when (val value = get("details")) {
        is JsonPrimitive -> value.takeIf { it.isString }?.content
        is JsonObject, is JsonArray -> value.toString()
        else -> null
    }

    private fun cleanText(value: String?, maxChars: Int = MAX_TEXT_CHARS): String? {
        if (value == null) return null
        val cleaned = value.replace("\u0000", "").trim()
        return cleaned.takeIf { it.isNotEmpty() && it.length <= maxChars }
    }

    private fun safeJsonStructure(rawJson: String): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (char in rawJson) {
            if (inString) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') inString = false
                continue
            }
            when (char) {
                '"' -> inString = true
                '{', '[' -> {
                    depth += 1
                    if (depth > MAX_DEPTH) return false
                }
                '}', ']' -> {
                    depth -= 1
                    if (depth < 0) return false
                }
            }
        }
        return !inString && depth == 0
    }
}
