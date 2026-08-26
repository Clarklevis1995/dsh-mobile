package com.clarklevis.dsh.shared.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

@Serializable(with = JsonValueSerializer::class)
sealed interface JsonValue {
    data class StringValue(val value: String) : JsonValue
    data class NumberValue(val value: Double) : JsonValue
    data class BooleanValue(val value: Boolean) : JsonValue
    data class ObjectValue(val value: Map<String, JsonValue>) : JsonValue
    data class ArrayValue(val value: List<JsonValue>) : JsonValue
    data object NullValue : JsonValue

    val objectValue: Map<String, JsonValue>?
        get() = (this as? ObjectValue)?.value
    val arrayValue: List<JsonValue>?
        get() = (this as? ArrayValue)?.value
    val stringValue: String?
        get() = (this as? StringValue)?.value
    val doubleValue: Double?
        get() = (this as? NumberValue)?.value
    val booleanValue: Boolean?
        get() = (this as? BooleanValue)?.value

    operator fun get(key: String): JsonValue? = objectValue?.get(key)

    fun displayText(): String = prettyJson.encodeToString(JsonElement.serializer(), toJsonElement())

    fun jsonDisplayText(): String {
        val raw = stringValue ?: return displayText()
        val parsed = runCatching { wireJson.parseToJsonElement(raw) }.getOrNull() ?: return displayText()
        return prettyJson.encodeToString(JsonElement.serializer(), parsed)
    }

    fun firstInteger(keys: Set<String>): Int? {
        objectValue?.let { objectValue ->
            keys.firstNotNullOfOrNull { key -> objectValue[key]?.doubleValue?.toInt() }?.let { return it }
            objectValue.values.firstNotNullOfOrNull { it.firstInteger(keys) }?.let { return it }
        }
        arrayValue?.firstNotNullOfOrNull { it.firstInteger(keys) }?.let { return it }
        return null
    }

    fun toJsonElement(): JsonElement = when (this) {
        is StringValue -> JsonPrimitive(value)
        is NumberValue -> JsonPrimitive(value)
        is BooleanValue -> JsonPrimitive(value)
        is ObjectValue -> JsonObject(value.mapValues { it.value.toJsonElement() })
        is ArrayValue -> JsonArray(value.map(JsonValue::toJsonElement))
        NullValue -> JsonNull
    }

    companion object {
        fun fromJsonElement(element: JsonElement): JsonValue = when (element) {
            JsonNull -> NullValue
            is JsonObject -> ObjectValue(element.mapValues { fromJsonElement(it.value) })
            is JsonArray -> ArrayValue(element.map(::fromJsonElement))
            is JsonPrimitive -> when {
                element.isString -> StringValue(element.content)
                element.booleanOrNull != null -> BooleanValue(element.booleanOrNull == true)
                else -> NumberValue(element.doubleOrNull ?: error("Unsupported JSON primitive: $element"))
            }
        }
    }
}

object JsonValueSerializer : KSerializer<JsonValue> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("JsonValue")

    override fun deserialize(decoder: Decoder): JsonValue {
        val jsonDecoder = decoder as? JsonDecoder ?: error("JsonValue requires JsonDecoder")
        return JsonValue.fromJsonElement(jsonDecoder.decodeJsonElement())
    }

    override fun serialize(encoder: Encoder, value: JsonValue) {
        val jsonEncoder = encoder as? JsonEncoder ?: error("JsonValue requires JsonEncoder")
        jsonEncoder.encodeJsonElement(value.toJsonElement())
    }
}

internal val wireJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

private val prettyJson = Json(wireJson) {
    prettyPrint = true
    prettyPrintIndent = "  "
}
