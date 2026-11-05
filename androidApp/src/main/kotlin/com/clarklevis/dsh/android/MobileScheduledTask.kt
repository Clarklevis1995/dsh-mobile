package com.clarklevis.dsh.android

import com.clarklevis.dsh.shared.protocol.JsonValue

data class MobileScheduledTask(
    val id: String,
    val sessionId: String,
    val title: String,
    val prompt: String,
    val kind: String,
    val status: String,
    val scheduledAt: String,
    val raw: JsonValue,
    val lastDelivery: JsonValue?
) {
    val everySeconds: Double? get() = raw["every_seconds"]?.doubleValue ?: raw["everySeconds"]?.doubleValue
    val expression: String? get() = raw["cron"]?.get("expression")?.stringValue ?: raw["expression"]?.stringValue
    val time: String? get() = raw[kind]?.get("time")?.stringValue ?: raw["time"]?.stringValue
    val timeZone: String? get() = raw[kind]?.get("time_zone")?.stringValue ?: raw["timeZone"]?.stringValue
    val weekdays: JsonValue? get() = raw["weekly"]?.get("weekdays") ?: raw["weekdays"]
    companion object {
        fun from(raw: JsonValue): MobileScheduledTask? {
            return MobileScheduledTask(
                id = raw["id"]?.stringValue ?: return null,
                sessionId = raw["target"]?.get("sessionId")?.stringValue ?: raw["sessionId"]?.stringValue
                    ?: raw["involvedSessionIds"]?.arrayValue?.firstOrNull()?.stringValue.orEmpty(),
                title = raw["title"]?.stringValue ?: return null,
                prompt = raw["prompt"]?.stringValue ?: return null,
                kind = raw["kind"]?.stringValue ?: raw["timing"]?.get("kind")?.stringValue ?: "unknown",
                status = raw["status"]?.stringValue ?: return null,
                scheduledAt = raw["scheduledAt"]?.stringValue.orEmpty(),
                raw = raw,
                lastDelivery = raw["lastDelivery"]
            )
        }
    }
}
