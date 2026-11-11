package com.clarklevis.dsh.android.platform

import com.clarklevis.dsh.shared.gateway.GatewayConnectionState
import com.clarklevis.dsh.shared.gateway.GatewayRuntimeEvent
import com.clarklevis.dsh.shared.gateway.GatewayRuntimeState
import com.clarklevis.dsh.shared.protocol.GatewayEvent
import com.clarklevis.dsh.shared.protocol.GatewayFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidGatewayDiagnosticsTest {
    @Test
    fun rtcFallbackLogsReasonStageAndChannelWithoutRawValues() {
        val lines = mutableListOf<String>()
        val diagnostics = AndroidGatewayDiagnostics.forTest { _, _, message -> lines += message }
        diagnostics.rtc("fallback-requested", "negotiation-timeout", "negotiating", "conversation", "none", 10500)
        assertTrue(lines.single().contains("reason=negotiation-timeout phase=negotiating channel=conversation"))
        diagnostics.rtc("selected", "gateway-rtc-unavailable", "websocket", "group", "websocket", 11000)
        assertTrue(lines.last().contains("mode=websocket elapsedMs=11000"))
        val secret = "token-secret\nSDP-secret"
        diagnostics.rtc(secret, secret, secret, secret, secret, 0)
        assertFalse(lines.last().contains("secret"))
        val releaseLines = mutableListOf<String>()
        val release = AndroidGatewayDiagnostics.forTest(enabled = false) { _, _, message -> releaseLines += message }
        release.rtc("fallback-requested", "ice-failed", "active", "control", "webrtc", 5000)
        release.intent(GatewayDiagnosticAction.CONNECT)
        assertEquals(1, releaseLines.size)
        assertTrue(releaseLines.single().contains("reason=ice-failed"))
    }

    @Test
    fun failureDetailsDistinguishPingTimeoutAndKeepSensitiveExceptionMessagesOut() {
        val lines = mutableListOf<String>()
        var now = 1_000L
        val diagnostics = AndroidGatewayDiagnostics.forTest(monotonicMillis = { now }) { _, _, message ->
            lines += message
        }
        diagnostics.lifecycle(GatewayLifecycleEvent.BACKGROUND)
        now += 30_000
        val secret = "secret-token-endpoint"
        diagnostics.transportFailure(7, "control", GatewayTransportFailureCause.from(
            java.net.SocketTimeoutException("sent ping but didn't receive pong within 30000ms $secret")
        ))
        diagnostics.transportFailure(7, secret, GatewayTransportFailureCause.from(java.io.IOException(secret)))
        diagnostics.lifecycle(GatewayLifecycleEvent.FOREGROUND)
        diagnostics.transportFailure(7, "conversation", GatewayTransportFailureCause.PEER_CLOSED)
        val output = lines.joinToString("\n")
        assertTrue(output.contains("cause=ping-timeout background=true backgroundMillis=30000"))
        assertTrue(output.contains("channel=unknown cause=io-error"))
        assertTrue(output.contains("cause=peer-closed background=false backgroundMillis=-1"))
        assertFalse(output.contains(secret))
        assertEquals(GatewayTransportFailureCause.SOCKET_TIMEOUT,
            GatewayTransportFailureCause.from(java.net.SocketTimeoutException("read timed out")))
    }

    @Test
    fun highFrequencyChunksAreSampledInsteadOfLoggedPerToken() {
        val lines = mutableListOf<String>()
        val diagnostics = AndroidGatewayDiagnostics.forTest { _, _, message -> lines += message }
        repeat(256) {
            diagnostics.runtimeEvent(
                GatewayRuntimeEvent.Frame(
                    rawJson = "redacted",
                    frame = GatewayFrame(
                        kind = "event",
                        event = GatewayEvent("assistant/chunk", chunkType = "text-delta", text = "x")
                    ),
                    correlatedSessionId = "session"
                )
            )
        }

        assertTrue(lines.any { "count=1" in it })
        assertTrue(lines.any { "count=128" in it })
        assertTrue(lines.any { "count=256" in it })
        assertEquals(3, lines.size)
    }

    @Test
    fun diagnosticLinesKeepPayloadAndIdentifiersRedacted() {
        val lines = mutableListOf<String>()
        val diagnostics = AndroidGatewayDiagnostics.forTest { _, _, message -> lines += message }
        val secret = "secret-token-message-session-attachment"

        diagnostics.runtimeState(
            GatewayRuntimeState(
                connection = GatewayConnectionState.FAILED,
                endpoint = secret,
                activeTurnSessionIds = setOf(secret),
                lastError = secret
            )
        )
        diagnostics.runtimeEvent(
            GatewayRuntimeEvent.Frame(
                rawJson = secret,
                frame = GatewayFrame(kind = secret, token = secret, message = secret, data = secret),
                correlatedSessionId = secret
            )
        )
        diagnostics.runtimeEvent(
            GatewayRuntimeEvent.RequestRejected(
                requestType = secret,
                reason = secret,
                targetSessionId = secret,
                correlationId = secret
            )
        )
        diagnostics.runtimeState(GatewayRuntimeState(connection = GatewayConnectionState.CONNECTED))

        val output = lines.joinToString("\n")
        assertFalse(output.contains(secret))
        assertFalse(output.contains("token="))
        assertTrue(output.contains("kind=unknown"))
        assertTrue(output.contains("error=unknown"))
        assertTrue(output.contains("error=none"))
        assertTrue(output.contains("hasTarget=true"))
        assertTrue(output.contains("hasCorrelation=true"))
    }

    @Test
    fun sessionCreationKindsRemainVisibleInDiagnosticMetadata() {
        val lines = mutableListOf<String>()
        val diagnostics = AndroidGatewayDiagnostics.forTest { _, _, message -> lines += message }

        diagnostics.runtimeEvent(
            GatewayRuntimeEvent.Frame(
                rawJson = "redacted",
                frame = GatewayFrame(kind = "session-created", requestId = "redacted"),
                correlatedSessionId = "redacted"
            )
        )

        assertTrue(lines.single().contains("kind=session-created"))
    }
}
