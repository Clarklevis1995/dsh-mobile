package com.clarklevis.dsh.shared.diagnostics

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/** 应用层探测统计；不把可靠传输隐藏的 UDP/TCP 重传误报为物理丢包。调用方负责串行访问。 */
data class NetworkDiagnosticsSample(
    val elapsedSeconds: Double,
    val roundTripMs: Double,
    val jitterMs: Double,
    val lossPercent: Double,
    val receivedBytesPerSecond: Double,
    val sentBytesPerSecond: Double,
    val segment: Int
)

data class NetworkDiagnosticsSnapshot(
    val active: Boolean = false,
    val connected: Boolean = false,
    val supported: Boolean = false,
    val connectionMethod: String = "",
    val samples: List<NetworkDiagnosticsSample> = emptyList(),
    val roundTripMs: Double = -1.0,
    val jitterMs: Double = -1.0,
    val lossPercent: Double = -1.0,
    val averageRoundTripMs: Double = -1.0,
    val p95RoundTripMs: Double = -1.0,
    val receivedBytesPerSecond: Double = 0.0,
    val sentBytesPerSecond: Double = 0.0,
    val successfulProbes: Int = 0,
    val timedOutProbes: Int = 0
)

// 生命周期、相关请求与窗口统计属于同一个串行状态机。
@Suppress("TooManyFunctions")
class NetworkDiagnosticsStore {
    private data class Result(val atMs: Double, val roundTripMs: Double, val jitterMs: Double)

    private data class Pending(val id: String, val atMs: Double)

    private var active = false
    val isActive: Boolean get() = active
    private var connected = false
    private var supported = false
    private var connectionKey = ""
    private var method = ""
    private var runId = ""
    private var sequence = 0L
    private var pending: Pending? = null
    private var lastSentMs = Double.NEGATIVE_INFINITY
    private var startedMs = 0.0
    private var lastTickMs = 0.0
    private var sentBytes = 0L
    private var receivedBytes = 0L
    private var previousRtt: Double? = null
    private var currentRtt = -1.0
    private var segment = 0
    private val results = ArrayDeque<Result>()
    private val samples = ArrayDeque<NetworkDiagnosticsSample>()

    fun start(runId: String, nowMs: Double) {
        this.runId = runId
        active = true
        connected = false
        supported = false
        connectionKey = ""
        method = ""
        reset(nowMs)
    }

    /** 停止和断线丢弃在途探测，不把页面退出或后台暂停记成丢包。 */
    fun stop() {
        active = false
        pending = null
        sentBytes = 0
        receivedBytes = 0
    }

    fun connection(key: String, connectionMethod: String, isConnected: Boolean, isSupported: Boolean, nowMs: Double) {
        val epochChanged = key != connectionKey || connectionMethod != method
        val availabilityChanged = connected != isConnected || supported != isSupported
        if (epochChanged || availabilityChanged) {
            reset(nowMs)
        }
        connectionKey = key
        method = connectionMethod
        connected = isConnected
        supported = isSupported
    }

    fun recordTraffic(sent: Long, received: Long) {
        if (!active || !connected) return
        sentBytes += sent.coerceAtLeast(0)
        receivedBytes += received.coerceAtLeast(0)
    }

    fun nextProbe(nowMs: Double): String? {
        val available = active && connected && supported
        val ready = pending == null && nowMs - lastSentMs >= PROBE_INTERVAL_MS
        if (!available || !ready) return null
        val id = "$PROBE_PREFIX$runId:${++sequence}"
        pending = Pending(id, nowMs)
        lastSentMs = nowMs
        return id
    }

    fun acceptPong(requestId: String, nowMs: Double): Boolean {
        val probe = pending?.takeIf { active && requestId == it.id } ?: return false
        val onTime = nowMs - probe.atMs < PROBE_TIMEOUT_MS
        complete(if (onTime) max(0.0, nowMs - probe.atMs) else -1.0, nowMs)
        return onTime
    }

    fun sendFailed(requestId: String, nowMs: Double) {
        if (active && pending?.id == requestId) complete(-1.0, nowMs)
    }

    fun tick(nowMs: Double): NetworkDiagnosticsSnapshot {
        if (!active) return snapshot()
        pending?.let { if (nowMs - it.atMs >= PROBE_TIMEOUT_MS) complete(-1.0, nowMs) }
        while (results.isNotEmpty() && nowMs - results.first().atMs >= WINDOW_MS) results.removeFirst()
        if (nowMs - lastTickMs >= PROBE_INTERVAL_MS) {
            val seconds = (nowMs - lastTickMs) / MILLIS_PER_SECOND
            val state = snapshot()
            samples.addLast(
                NetworkDiagnosticsSample(
                    elapsedSeconds = max(0.0, (nowMs - startedMs) / MILLIS_PER_SECOND),
                    roundTripMs = if (connected && supported) state.roundTripMs else -1.0,
                    jitterMs = state.jitterMs,
                    lossPercent = state.lossPercent,
                    receivedBytesPerSecond = receivedBytes / seconds,
                    sentBytesPerSecond = sentBytes / seconds,
                    segment = segment
                )
            )
            while (samples.size > MAX_SAMPLES ||
                samples.firstOrNull()?.let { nowMs - startedMs - it.elapsedSeconds * MILLIS_PER_SECOND >= WINDOW_MS } == true
            ) {
                samples.removeFirst()
            }
            lastTickMs = nowMs
            sentBytes = 0
            receivedBytes = 0
        }
        return snapshot()
    }

    fun snapshot(): NetworkDiagnosticsSnapshot {
        val rtts = results.filter { it.roundTripMs >= 0 }.map { it.roundTripMs }.sorted()
        val jitters = results.filter { it.jitterMs >= 0 }.map { it.jitterMs }
        val lost = results.count { it.roundTripMs < 0 }
        val last = samples.lastOrNull()
        return NetworkDiagnosticsSnapshot(
            active = active,
            connected = connected,
            supported = supported,
            connectionMethod = method,
            samples = samples.toList(),
            roundTripMs = currentRtt,
            jitterMs = if (jitters.isEmpty()) -1.0 else jitters.average(),
            lossPercent = if (results.isEmpty()) -1.0 else lost * PERCENT / results.size,
            averageRoundTripMs = if (rtts.isEmpty()) -1.0 else rtts.average(),
            p95RoundTripMs = if (rtts.isEmpty()) -1.0 else rtts[ceil(rtts.size * P95_QUANTILE).toInt() - 1],
            receivedBytesPerSecond = last?.receivedBytesPerSecond ?: 0.0,
            sentBytesPerSecond = last?.sentBytesPerSecond ?: 0.0,
            successfulProbes = rtts.size,
            timedOutProbes = lost
        )
    }

    private fun complete(rtt: Double, nowMs: Double) {
        val jitter = if (rtt >= 0) previousRtt?.let { abs(rtt - it) } ?: -1.0 else -1.0
        results.addLast(Result(nowMs, rtt, jitter))
        while (results.size > MAX_SAMPLES) results.removeFirst()
        previousRtt = rtt.takeIf { it >= 0 }
        currentRtt = rtt
        if (rtt < 0) segment++
        pending = null
    }

    private fun reset(nowMs: Double) {
        pending = null
        results.clear()
        samples.clear()
        currentRtt = -1.0
        previousRtt = null
        startedMs = nowMs
        lastTickMs = nowMs
        lastSentMs = Double.NEGATIVE_INFINITY
        sentBytes = 0
        receivedBytes = 0
        segment++
    }

    companion object {
        const val CAPABILITY = "network-diagnostics-v1"
        const val PROBE_PREFIX = "network-check:"
        const val PROBE_INTERVAL_MS = 1000.0
        const val PROBE_TIMEOUT_MS = 3000.0
        private const val MILLIS_PER_SECOND = 1000.0
        private const val PERCENT = 100.0
        private const val P95_QUANTILE = 0.95
        private const val WINDOW_MS = 60000.0
        private const val MAX_SAMPLES = 60
    }
}
