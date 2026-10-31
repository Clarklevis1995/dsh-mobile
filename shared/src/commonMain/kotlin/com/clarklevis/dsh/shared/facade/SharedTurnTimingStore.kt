package com.clarklevis.dsh.shared.facade

import com.clarklevis.dsh.shared.domain.normalizeEpochSeconds

data class SharedTurnBoundary(val sequence: Int, val epochSeconds: Double, val isStart: Boolean)

data class SharedTurnTimingSnapshot(
    val startSequence: Int,
    val startEpochSeconds: Double,
    val elapsedAtAnchor: Double,
    val anchorMonotonicSeconds: Double
) {
    fun elapsedSeconds(nowMonotonicSeconds: Double): Double =
        (elapsedAtAnchor + nowMonotonicSeconds - anchorMonotonicSeconds).coerceAtLeast(0.0)
}

/** 轮次生命周期决定起点，单调时钟负责推进；正文、分页和用户消息不参与计时。 */
class SharedTurnTimingStore {
    private data class Entry(
        val sequence: Int,
        val startEpochSeconds: Double? = null,
        val timing: SharedTurnTimingSnapshot? = null
    )
    private val sessions = linkedMapOf<String, Entry>()
    private var serverEpochSeconds: Double? = null
    private var serverObservedAt = 0.0

    fun snapshot(sessionId: String): SharedTurnTimingSnapshot? = sessions[sessionId]?.timing

    fun clear(sessionId: String) {
        sessions.remove(sessionId)
    }

    fun awaitStart(sessionId: String, afterSequence: Int) {
        put(sessionId, Entry(maxOf(sessions[sessionId]?.sequence ?: -1, afterSequence)))
    }

    fun observeServerTime(epochSeconds: Double, nowMonotonicSeconds: Double) {
        if (!epochSeconds.isFinite() || epochSeconds <= 0 || !nowMonotonicSeconds.isFinite()) return
        serverEpochSeconds = normalizeEpochSeconds(epochSeconds)
        serverObservedAt = nowMonotonicSeconds
        sessions.keys.toList().forEach { id ->
            val entry = sessions.getValue(id)
            if (entry.startEpochSeconds != null && entry.timing == null) {
                sessions[id] = entry.copy(timing = restoredTiming(entry.sequence, entry.startEpochSeconds))
            }
        }
    }

    fun receiveLiveEvent(
        sessionId: String,
        sequence: Int,
        epochSeconds: Double,
        eventType: String,
        nowMonotonicSeconds: Double
    ) {
        if (eventType == "assistant/chunk") return
        val previous = sessions[sessionId]
        if (sequence <= (previous?.sequence ?: -1)) return
        observeServerTime(epochSeconds, nowMonotonicSeconds)
        when (eventType) {
            "turn/start" -> {
                val start = normalizeEpochSeconds(epochSeconds)
                if (!start.isFinite() || start <= 0) {
                    put(sessionId, Entry(sequence))
                    return
                }
                put(sessionId, Entry(sequence, start,
                    SharedTurnTimingSnapshot(sequence, start, 0.0, nowMonotonicSeconds)))
            }
            "turn/end" -> put(sessionId, Entry(sequence))
        }
    }

    fun receiveHistory(sessionId: String, boundaries: List<SharedTurnBoundary>) {
        val boundary = boundaries.maxByOrNull { it.sequence } ?: return
        if (boundary.sequence <= (sessions[sessionId]?.sequence ?: -1)) return
        if (!boundary.isStart) {
            put(sessionId, Entry(boundary.sequence))
            return
        }
        val start = normalizeEpochSeconds(boundary.epochSeconds)
        if (!start.isFinite() || start <= 0) {
            put(sessionId, Entry(boundary.sequence))
            return
        }
        put(sessionId, Entry(boundary.sequence, start, restoredTiming(boundary.sequence, start)))
    }

    private fun restoredTiming(sequence: Int, start: Double): SharedTurnTimingSnapshot? {
        val serverNow = serverEpochSeconds ?: return null
        return SharedTurnTimingSnapshot(sequence, start, serverNow - start, serverObservedAt)
    }

    private fun put(sessionId: String, entry: Entry) {
        sessions[sessionId] = entry
        if (sessions.size > 256) sessions.remove(sessions.keys.first())
    }
}
