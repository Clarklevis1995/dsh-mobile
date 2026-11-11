package com.clarklevis.dsh.shared.facade

import kotlinx.serialization.json.*

/** 手机能力固定白名单；通讯录不属于连接器能力。 */
class SharedDeviceConnectors {
    fun catalogJson(): String = """[{"id":"calendar","title":"日历"},{"id":"reminders","title":"提醒事项"},{"id":"location","title":"位置"},{"id":"photos","title":"照片"},{"id":"music","title":"音乐"},{"id":"health","title":"健康"},{"id":"alarms","title":"闹钟"}]"""

    fun toolModule(tool: String): String? = when (tool) {
        "calendar.busy", "calendar.create" -> "calendar"
        "reminders.list", "reminders.create" -> "reminders"
        "location.current", "location.precise", "maps.search", "maps.geocode", "maps.reverse-geocode", "maps.directions", "maps.open", "maps.markers.create", "maps.markers.list", "maps.markers.update", "maps.markers.delete" -> "location"
        "photos.select" -> "photos"
        "music.search", "music.state", "music.play", "music.pause", "music.seek", "music.library", "music.play-library", "music.catalog-search" -> "music"
        "health.daily-summary" -> "health"
        "alarms.create", "alarms.list", "alarms.cancel", "alarms.open-create" -> "alarms"
        else -> null
    }

    fun validateInvocationJson(json: String): String {
        if (json.length > 16_384) return "invalid_request"
        val value = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return "invalid_request"
        val version = value["version"] as? JsonPrimitive
        if (version == null || version.isString || version.intOrNull != 1) return "invalid_request"
        for (key in listOf("invocationId", "connectionLeaseId", "tool")) {
            val text = (value[key] as? JsonPrimitive)?.contentOrNull
            if ((value[key] as? JsonPrimitive)?.isString != true || text.isNullOrBlank() || text.length > 128) return "invalid_request"
        }
        if (toolModule(value["tool"]!!.jsonPrimitive.content) == null) return "tool_unsupported"
        if (value["arguments"] !is JsonObject) return "invalid_request"
        return ""
    }
}
