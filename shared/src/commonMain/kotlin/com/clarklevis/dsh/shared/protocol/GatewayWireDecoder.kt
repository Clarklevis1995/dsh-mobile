package com.clarklevis.dsh.shared.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

object GatewayWireDecoder {
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
        return wireJson.decodeFromJsonElement(GatewayFrame.serializer(), normalized)
    }
}
