package com.clarklevis.dsh.shared.facade

import com.clarklevis.dsh.shared.projection.ConversationProjectionGuard
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

object SharedConversationPatchGuard {
    private val patchKeys = setOf("schema", "sessionId", "operations", "replacesAll", "replacementItems", "lastSequence")
    private val operationKeys = setOf("kind", "item", "itemId", "delta", "epochSeconds", "segments", "segmentChanges")
    private val itemKeys = setOf(
        "id", "kind", "title", "text", "images", "isError", "epochSeconds", "turn", "files",
        "knownFiles", "textParts", "requestId", "segments", "sourceEventId"
    )
    private val markdownSegmentKeys = setOf("type", "text")
    private val genuiSegmentKeys = setOf("type", "id", "rawJson", "state", "fallbackMarkdown", "fallbackStart", "fallbackEnd", "contentVersion", "interactive")
    private val changeKeys = setOf("index", "segment")

    fun validateTyped(patch: SharedConversationPatch): Boolean {
        if (patch.schema != 1 || patch.sessionId.isBlank() || patch.lastSequence < -1) return false
        if (patch.replacesAll) {
            if (patch.operations.isNotEmpty() || patch.replacementItems == null) return false
            if (patch.replacementItems.map { it.id }.distinct().size != patch.replacementItems.size) return false
            return patch.replacementItems.all(ConversationProjectionGuard::validItem)
        }
        if (patch.replacementItems != null) return false
        return patch.operations.all(ConversationProjectionGuard::validOperation)
    }

    fun validate(json: String): Boolean = validateAndDecode(json) != null

    fun validateAndDecode(json: String): SharedConversationPatch? = try {
        val root = Json.parseToJsonElement(json) as? JsonObject ?: return null
        if (!root.keys.all { it in patchKeys }) return null
        val schema = root["schema"] as? JsonPrimitive
        if (schema == null || schema.isString || schema.content != "1") return null
        val operations = root["operations"] as? JsonArray ?: return null
        for (operation in operations) {
            val value = operation as? JsonObject ?: return null
            if (!value.keys.all { it in operationKeys }) return null
            if (value.containsKey("item") && value["item"] !is JsonObject) return null
            (value["item"] as? JsonObject)?.let { if (!validRawItem(it)) return null }
            if (value.containsKey("segments")) {
                val segments = value["segments"] as? JsonArray ?: return null
                if (!segments.all(::validRawSegment)) return null
            }
            if (value.containsKey("segmentChanges")) {
                val changes = value["segmentChanges"] as? JsonArray ?: return null
                for (change in changes) {
                    val changeValue = change as? JsonObject ?: return null
                    if (!changeValue.keys.all { it in changeKeys }) return null
                    if (!validRawSegment(changeValue["segment"])) return null
                }
            }
        }
        if (root.containsKey("replacementItems")) {
            val replacements = root["replacementItems"] as? JsonArray ?: return null
            for (entry in replacements) {
                val item = entry as? JsonObject ?: return null
                if (!validRawItem(item)) return null
            }
        }
        val decoded = wireJson.decodeFromString<SharedConversationPatch>(json)
        decoded.takeIf(::validateTyped)
    } catch (_: Throwable) {
        null
    }

    private fun validRawItem(value: JsonObject): Boolean {
        if (!value.keys.all { it in itemKeys }) return false
        if (value.containsKey("segments")) {
            val segments = value["segments"] as? JsonArray ?: return false
            if (!segments.all(::validRawSegment)) return false
        }
        return true
    }

    private fun validRawSegment(value: JsonElement?): Boolean {
        val segment = value as? JsonObject ?: return false
        val type = (segment["type"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return false
        return when (type) {
            "markdown" -> segment.keys.all { it in markdownSegmentKeys }
            "genui" -> segment.keys.all { it in genuiSegmentKeys }
            else -> false
        }
    }
}
