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
    companion object {
        fun from(raw: JsonValue): MobileScheduledTask? {
            return MobileScheduledTask(
                id = raw["id"]?.stringValue ?: return null,
                sessionId = raw["sessionId"]?.stringValue ?: return null,
                title = raw["title"]?.stringValue ?: return null,
                prompt = raw["prompt"]?.stringValue ?: return null,
                kind = raw["kind"]?.stringValue ?: return null,
                status = raw["status"]?.stringValue ?: return null,
                scheduledAt = raw["scheduledAt"]?.stringValue ?: return null,
                raw = raw,
                lastDelivery = raw["lastDelivery"]
            )
        }
    }
}
