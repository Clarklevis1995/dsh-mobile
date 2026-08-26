package com.clarklevis.dsh.shared.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

object GatewayWireDecoder {
    fun decode(text: String): GatewayFrame {
        val parsed = wireJson.parseToJsonElement(text)
        val objectValue = parsed as? JsonObject
            ?: return wireJson.decodeFromJsonElement(GatewayFrame.serializer(), parsed)
        val normalized = if (
            "kind" !in objectValue &&
            objectValue["sessionId"] is JsonPrimitive &&
            objectValue["seq"] is JsonPrimitive &&
            objectValue["event"] is JsonObject
        ) {
            JsonObject(objectValue + ("kind" to JsonPrimitive("event")))
        } else {
            objectValue
        }
        return wireJson.decodeFromJsonElement(GatewayFrame.serializer(), normalized)
    }
}
