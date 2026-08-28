package com.clarklevis.dsh.android.platform

import com.clarklevis.dsh.shared.gateway.GatewayConnectionState
import com.clarklevis.dsh.shared.gateway.GatewayRuntimeEvent
import com.clarklevis.dsh.shared.gateway.GatewayRuntimeState
import com.clarklevis.dsh.shared.protocol.GatewayFrame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidGatewayDiagnosticsTest {
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
}
