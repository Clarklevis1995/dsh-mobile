package com.clarklevis.dsh.shared.transport

/** 仅允许固定元数据；不接收或输出地址、SDP、票据、token、业务正文或原始异常。 */
object RtcDiagnostics {
    private val events = setOf("upgrade-requested", "upgrade-skipped", "native-failure", "fallback-requested", "blocked", "selected")
    private val phases =
        setOf("initial", "negotiating", "selecting-webrtc", "selecting-websocket", "active", "websocket", "closed", "connecting")
    private val reasons =
        setOf(
            "none",
            "gateway-no-upgrade",
            "gateway-rtc-unavailable",
            "gateway-rtc-lost",
            "gateway-security-failure",
            "protocol-validation-failed",
            "native-rtc-unavailable",
            "rtc-connection-lost",
            "negotiation-timeout",
            "selection-timeout",
            "ice-failed",
            "ice-closed",
            "datachannel-closed",
            "sdp-offer-failed",
            "sdp-set-failed",
            "runtime-unavailable",
            "signaling-send-failed",
            "frame-handling-failed",
            "idle-check-failed",
            "callback-queue-ended",
            "message-too-large",
            "connection-lost",
            "pairing",
            "probe",
            "no-credential",
            "no-gateway-identity",
            "websocket-recovery",
            "lan-websocket",
        )

    // 字段对应固定日志格式，避免接收会携带敏感值的任意上下文对象。
    @Suppress("LongParameterList")
    fun message(
        event: String,
        reason: String,
        phase: String,
        channel: String,
        mode: String,
        elapsedMs: Long,
    ): String =
        "[GatewayRTC] event=${event.takeIf { it in events } ?: "unknown"} " +
            "reason=${reason.takeIf { it in reasons } ?: "unknown"} " +
            "phase=${phase.takeIf { it in phases } ?: "unknown"} " +
            "channel=${channel.takeIf { it in setOf("group", "control", "conversation") } ?: "unknown"} " +
            "mode=${mode.takeIf { it in setOf("none", "webrtc", "websocket") } ?: "unknown"} " +
            "elapsedMs=${elapsedMs.coerceAtLeast(0)}"
}
