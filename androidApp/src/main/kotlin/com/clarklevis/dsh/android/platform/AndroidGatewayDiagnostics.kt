package com.clarklevis.dsh.android.platform

import android.app.Application
import android.content.pm.ApplicationInfo
import android.util.Log
import com.clarklevis.dsh.shared.gateway.GatewayRuntimeEvent
import com.clarklevis.dsh.shared.gateway.GatewayRuntimeState
import com.clarklevis.dsh.shared.platform.GatewayTransportState
import java.util.concurrent.atomic.AtomicLong

/**
 * 仅在 debuggable 构建中输出 Gateway 关键路径元数据。
 *
 * API 刻意不接收 endpoint、凭据、消息正文、Session ID、附件 ID 或 Base64，避免调用方误打敏感值。
 */
internal class AndroidGatewayDiagnostics private constructor(
    private val enabled: Boolean,
    private val writer: (priority: Int, tag: String, message: String) -> Unit
) {
    private val transportFrameCount = AtomicLong()
    private val runtimeChunkCount = AtomicLong()

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
                        "correlated=${event.correlatedSessionId != null} chunks=$chunks"
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
                message = "runtime request-rejected type=${safeProtocolValue(event.requestType)} " +
                    "reason=${safeProtocolValue(event.reason)} hasTarget=${event.targetSessionId != null} " +
                    "hasCorrelation=${event.correlationId != null}"
            }
        }
        emit(priority, message)
    }

    fun lifecycle(event: GatewayLifecycleEvent, keepAlive: Boolean? = null) {
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
            enabled = application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
            writer = Log::println
        )

        fun disabled(): AndroidGatewayDiagnostics = AndroidGatewayDiagnostics(false) { _, _, _ -> }

        internal fun forTest(
            writer: (priority: Int, tag: String, message: String) -> Unit
        ): AndroidGatewayDiagnostics = AndroidGatewayDiagnostics(true, writer)
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
    "history", "attachment", "search", "host", "agent-presets", "defaults", "default-model",
    "save-default-model", "set-default", "models", "select-model", "permission-options", "permission",
    "context-usage", "session-stats", "directories", "directory-create", "workspace-create",
    "question-requested", "question-response", "question-resolved", "question-answer", "question-cancel",
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
