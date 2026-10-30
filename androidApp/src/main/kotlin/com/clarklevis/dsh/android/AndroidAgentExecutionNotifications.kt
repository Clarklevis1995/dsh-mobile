package com.clarklevis.dsh.android

import com.clarklevis.dsh.shared.protocol.GatewayEvent
import com.clarklevis.dsh.shared.protocol.GatewayFrame
import java.util.Locale
import java.util.UUID

internal data class AndroidExecutionEndedNotification(
    val sessionId: String,
    val eventId: String,
    val failed: Boolean
)

/** 通知不依赖正文投影要求的时间字段；快照只补报本次连接中观察到的运行任务。 */
internal class AndroidAgentExecutionNotifications {
    private data class RunningTurn(val identifier: String, val startSequence: Int? = null)
    private val running = linkedMapOf<String, RunningTurn>()

    fun accept(frame: GatewayFrame, monitoredRunningSessionIds: Set<String>): AndroidExecutionEndedNotification? {
        if (frame.kind == "sessions") {
            monitoredRunningSessionIds.forEach { begin(it) }
            return null
        }
        val sessionId = frame.sessionId?.takeIf(String::isNotBlank) ?: return null
        val event = frame.event
        when (frame.kind) {
            "sent" -> begin(sessionId)
            "event" -> when (event?.type) {
                "turn/start" -> begin(sessionId, frame.seq)
                "turn/end" -> return ended(sessionId, frame.seq, event)
            }
            "session-snapshot" -> {
                val lifecycle = frame.events.orEmpty()
                    .filter { it.type == "turn/start" || it.type == "turn/end" }
                    .maxByOrNull { it.seq }
                if (frame.assistantStream?.get("activeAttempt")?.objectValue != null ||
                    lifecycle?.type == "turn/start"
                ) {
                    begin(sessionId, frame.cursor ?: lifecycle?.seq)
                } else if (lifecycle?.type == "turn/end") {
                    val active = running[sessionId] ?: return null
                    if (active.startSequence != null && lifecycle.seq <= active.startSequence) return null
                    return ended(sessionId, lifecycle.seq, lifecycle.normalized(sessionId).event)
                } else if (sessionId in monitoredRunningSessionIds) {
                    begin(sessionId)
                }
            }
        }
        return null
    }

    private fun begin(sessionId: String, sequence: Int? = null) {
        val previous = running[sessionId]
        running[sessionId] = RunningTurn(previous?.identifier ?: "run-${UUID.randomUUID()}", sequence ?: previous?.startSequence)
        if (running.size > 256) running.remove(running.keys.first())
    }

    private fun ended(sessionId: String, sequence: Int?, event: GatewayEvent): AndroidExecutionEndedNotification? {
        val current = running[sessionId]
        if (sequence != null && current?.startSequence != null && sequence <= current.startSequence) return null
        val active = running.remove(sessionId)
        val identifier = sequence?.toString() ?: event.turn?.let { "turn-$it" } ?: active?.identifier ?: return null
        val failed = event.isError == true || event.interrupted == true || !event.error.isNullOrBlank() ||
            event.reason?.lowercase(Locale.ROOT) in setOf("error", "failed", "cancelled", "canceled", "interrupted", "aborted")
        return AndroidExecutionEndedNotification(sessionId, identifier, failed)
    }
}
