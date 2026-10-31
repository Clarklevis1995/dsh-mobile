package com.clarklevis.dsh.android.platform

import android.app.Application
import android.os.SystemClock
import android.util.Log
import com.clarklevis.dsh.shared.gateway.GatewayRuntimeEvent
import com.clarklevis.dsh.shared.gateway.GatewayRuntimeState
import com.clarklevis.dsh.shared.platform.GatewayTransportState
import com.clarklevis.dsh.shared.transport.RtcDiagnostics
import java.util.concurrent.atomic.AtomicLong

/**
 * Gateway 和 RTC 固定字段诊断同时写入控制台与本地文件，支持发布包导出。
 *
 * API 刻意不接收 endpoint、凭据、消息正文、Session ID、附件 ID 或 Base64，避免调用方误打敏感值。
 */
internal class AndroidGatewayDiagnostics private constructor(
    private val enabled: Boolean,
    private val writer: (priority: Int, tag: String, message: String) -> Unit,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private val transportFrameCount = AtomicLong()
    private val runtimeChunkCount = AtomicLong()
    private val backgroundSince = AtomicLong(-1)

    fun transportFailure(
        generation: Long,
        channel: String?,
        cause: GatewayTransportFailureCause,
        closeCode: Int? = null
    ) {
        val since = backgroundSince.get()
        val backgroundMillis = if (since >= 0) (monotonicMillis() - since).coerceAtLeast(0) else -1
        val safeChannel = when (channel) {
            "control", "conversation" -> channel
            null -> "none"
            else -> "unknown"
        }
        emit(Log.WARN, "transport failure-detail generation=$generation channel=$safeChannel " +
            "cause=${cause.wireName} background=${since >= 0} backgroundMillis=$backgroundMillis " +
            "closeCode=${closeCode ?: "none"}")
    }

    fun offlineCatalog(stage: String, sessionCount: Int) {
        val safeStage = stage.takeIf { it in setOf("restore", "miss", "skip-late", "saved", "skip-write") } ?: "unknown"
        emit(Log.DEBUG, "offline-catalog stage=$safeStage sessions=${sessionCount.coerceIn(0, 4096)}")
    }

    @Suppress("LongParameterList")
    fun rtc(event: String, reason: String, phase: String, channel: String, mode: String, elapsedMs: Long) {
        writer(Log.INFO, TAG, RtcDiagnostics.message(event, reason, phase, channel, mode, elapsedMs))
    }

    fun chatPrefetch(stage: String, count: Int) {
        val safeStage = stage.takeIf {
            it in setOf(
                "start", "hit", "complete", "skip-write", "skip-send", "skip-timeout", "skip-late", "skip-error", "skip-failure",
                "cancel-disconnect", "cancel-foreground", "cancel-clear", "cancel-memory", "cancel-close", "cancel-timeout"
            )
        } ?: "unknown"
        emit(Log.DEBUG, "chat-prefetch stage=$safeStage count=${count.coerceIn(0, 6)}")
    }

    fun intent(action: GatewayDiagnosticAction, hasSession: Boolean = false, imageCount: Int = 0) {
        emit(Log.INFO, "intent action=${action.wireName} hasSession=$hasSession imageCount=$imageCount")
    }

    fun transportOpening(generation: Long, hasBearer: Boolean, hasPairingCode: Boolean) {
        transportFrameCount.set(0)
        val authentication = when {
            hasPairingCode -> "pairing"
            hasBearer -> "bearer"
            else -> "none"
        }
        emit(Log.INFO, "transport opening generation=$generation authentication=$authentication")
    }

    fun transportSend(generation: Long, characterCount: Int, accepted: Boolean) {
        emit(Log.DEBUG, "transport send generation=$generation chars=$characterCount accepted=$accepted")
    }

    fun transportFrame(generation: Long, byteCount: Int) {
        val count = transportFrameCount.incrementAndGet()
        if (count == 1L || count % FRAME_LOG_SAMPLE_INTERVAL == 0L) {
            emit(
                Log.DEBUG,
                "transport frame-sample generation=$generation count=$count bytes=$byteCount"
            )
        }
    }

    fun transportState(state: GatewayTransportState) {
        val message = when (state) {
            is GatewayTransportState.Closed -> "transport closed generation=${state.generation}"
            is GatewayTransportState.Opening -> "transport opening-state generation=${state.generation}"
            is GatewayTransportState.Open -> "transport open generation=${state.generation}"
            is GatewayTransportState.Failed -> "transport failed generation=${state.generation} " +
                "httpStatus=${state.httpStatus ?: "none"} closeCode=${state.closeCode ?: "none"} " +
                "recoverable=${state.recoverable} reason=${safeProtocolValue(state.reason)}"
        }
        emit(if (state is GatewayTransportState.Failed) Log.WARN else Log.INFO, message)
    }

    fun runtimeState(state: GatewayRuntimeState) {
        emit(
            if (state.lastError == null) Log.INFO else Log.WARN,
            "runtime state=${state.connection.name} network=${state.networkAvailable} " +
                "reconnectAttempt=${state.reconnectAttempt} activeTurns=${state.activeTurnSessionIds.size} " +
                "unassociatedTurn=${state.hasUnassociatedTurn} error=${safeProtocolValue(state.lastError)}"
        )
    }

    fun runtimeEvent(event: GatewayRuntimeEvent) {
        val priority: Int
        val message: String
        when (event) {
            is GatewayRuntimeEvent.Frame -> {
                priority = Log.DEBUG
                val eventType = event.frame.event?.type
                if (eventType == "assistant/chunk") {
                    val count = runtimeChunkCount.incrementAndGet()
                    if (count != 1L && count % FRAME_LOG_SAMPLE_INTERVAL != 0L) return
                    message = "runtime chunk-sample count=$count " +
                        "correlated=${event.correlatedSessionId != null}"
                } else {
                    val chunks = when (eventType) {
                        "turn/start" -> runtimeChunkCount.getAndSet(0)
                        "turn/end" -> runtimeChunkCount.getAndSet(0)
                        else -> runtimeChunkCount.get()
                    }
                    message = "runtime frame kind=${safeProtocolValue(event.frame.kind)} " +
                        "correlated=${event.correlatedSessionId != null} chunks=$chunks" +
                        if (event.frame.kind == "error") {
                            " requestType=${safeProtocolValue(event.frame.requestType)} " +
                                "code=${safeProtocolValue(event.frame.code)} backgroundHistory=${event.isBackgroundHistory}"
                        } else ""
                }
            }
            is GatewayRuntimeEvent.AttachmentCached -> {
                priority = Log.INFO
                message = "runtime attachment-cached"
            }
            is GatewayRuntimeEvent.RequestQueued -> {
                priority = Log.DEBUG
                message = "runtime request-queued type=${safeProtocolValue(event.requestType)} " +
                    "response=${safeProtocolValue(event.responseKind)}"
            }
            is GatewayRuntimeEvent.RequestCancelled -> {
                priority = Log.INFO
                message = "runtime request-cancelled type=${safeProtocolValue(event.requestType)} " +
                    "reason=${safeProtocolValue(event.reason)} hasTarget=${event.targetSessionId != null} " +
                    "hasCorrelation=${event.correlationId != null}"
            }
            is GatewayRuntimeEvent.RequestTimedOut -> {
                priority = Log.WARN
                message = "runtime request-timeout type=${safeProtocolValue(event.requestType)} " +
                    "hasTarget=${event.targetSessionId != null} hasCorrelation=${event.correlationId != null}"
            }
            is GatewayRuntimeEvent.RequestRejected -> {
                priority = Log.WARN
                val reason = if (event.requestType == "decode" &&
                    event.reason.startsWith("decode-failed [")) {
                    event.reason
                } else {
                    safeProtocolValue(event.reason)
                }
                message = "runtime request-rejected type=${safeProtocolValue(event.requestType)} " +
                    "reason=$reason hasTarget=${event.targetSessionId != null} " +
                    "hasCorrelation=${event.correlationId != null}"
            }
        }
        emit(priority, message)
    }

    fun approval(
        stage: String,
        frameKind: String? = null,
        hasRpc: Boolean = false,
        hasSession: Boolean = false,
        hasApprovalId: Boolean = false,
        hasTool: Boolean = false,
        replay: Boolean = false,
        pendingCount: Int = 0,
        selectedVisible: Boolean = false
    ) {
        emit(
            Log.INFO,
            "approval stage=$stage kind=${safeProtocolValue(frameKind)} hasRpc=$hasRpc " +
                "hasSession=$hasSession hasApprovalId=$hasApprovalId hasTool=$hasTool " +
                "replay=$replay pending=$pendingCount selectedVisible=$selectedVisible"
        )
    }

    fun lifecycle(event: GatewayLifecycleEvent, keepAlive: Boolean? = null) {
        when (event) {
            GatewayLifecycleEvent.BACKGROUND -> backgroundSince.set(monotonicMillis())
            GatewayLifecycleEvent.FOREGROUND -> backgroundSince.set(-1)
            else -> Unit
        }
        emit(
            Log.INFO,
            "lifecycle event=${event.wireName}" + (keepAlive?.let { " keepAlive=$it" } ?: "")
        )
    }

    private fun emit(priority: Int, message: String) {
        if (enabled) writer(priority, TAG, message)
    }

    companion object {
        const val TAG = "DshGateway"
        private const val FRAME_LOG_SAMPLE_INTERVAL = 128L

        fun forApplication(application: Application): AndroidGatewayDiagnostics = AndroidGatewayDiagnostics(
            enabled = true,
            writer = LocalAppLog::println,
            monotonicMillis = SystemClock::elapsedRealtime
        )

        fun disabled(): AndroidGatewayDiagnostics = AndroidGatewayDiagnostics(false, writer = { _, _, _ -> })

        internal fun forTest(
            monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
            enabled: Boolean = true,
            writer: (priority: Int, tag: String, message: String) -> Unit
        ): AndroidGatewayDiagnostics = AndroidGatewayDiagnostics(enabled, writer, monotonicMillis)
    }
}

internal enum class GatewayDiagnosticAction(val wireName: String) {
    CONNECT("connect"),
    PAIR("pair"),
    DISCONNECT("disconnect"),
    REFRESH_SESSIONS("refresh-sessions"),
    SELECT_SESSION("select-session"),
    SEND_MESSAGE("send-message"),
    PREPARE_IMAGE("prepare-image"),
    RETRY_ATTACHMENT("retry-attachment")
}

internal enum class GatewayLifecycleEvent(val wireName: String) {
    APPLICATION_CREATED("application-created"),
    FOREGROUND("foreground"),
    BACKGROUND("background"),
    KEEP_ALIVE_START("keep-alive-start"),
    KEEP_ALIVE_STOP("keep-alive-stop"),
    SERVICE_CREATED("service-created"),
    SERVICE_STARTED("service-started"),
    SERVICE_DISCONNECT("service-disconnect")
}

private fun safeProtocolValue(value: String?): String = when {
    value == null -> "none"
    value in SAFE_PROTOCOL_VALUES -> value
    else -> "unknown"
}

private val SAFE_PROTOCOL_VALUES = setOf(
    "none", "paired", "hello", "pong", "subscribed", "sent", "event", "workspaces", "sessions",
    "session-create", "session-created",
    "history", "attachment", "search", "host", "agent-presets", "defaults", "default-model",
    "save-default-model", "set-default", "models", "select-model", "permission-options", "permission",
    "context-usage", "session-stats", "directories", "directory-create", "workspace-create",
    "question-requested", "question-response", "question-resolved", "question-answer", "question-cancel",
    "approval-requested", "approval-response", "approval-resolved",
    "error", "message", "unsubscribe", "subscribe", "transport", "pair", "decode", "gateway",
    "authentication-required", "policy-violation", "websocket-failure", "incoming-overflow",
    "incoming-message-too-large", "not-connected", "send-failed", "open-failed", "decode-failed",
    "stale-frame", "no-active-request", "session-mismatch", "pair-token-missing",
    "credential-access-failed", "attachment-invalid", "attachment-cache-write-failed",
    "incoming-flow-failed", "network-flow-failed", "gateway-unavailable", "gateway-disabled",
    "transport-failed", "gateway-request-failed", "image-limits-exceeded", "request-busy",
    "request-coalesced", "connection-replaced", "connection-closed", "connection-recycled",
    "background-suspended", "network-lost", "recovery-timeout"
)
