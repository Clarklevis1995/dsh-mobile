package com.clarklevis.dsh.android

import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSessionStatsProtocolTest {
    @Test
    fun statsAcceptNumericDecodeTokensWithoutLosingOtherMetrics() {
        for (tokens in listOf("385", "385.0", "385.5", "2147483648")) {
            val frame = GatewayWireDecoder.decode(
                """{"kind":"session-stats","sessionId":"s1","sessionStats":{
                    "turns":4,"steps":158,"decodeMs":1000.5,"decodeTokens":$tokens,
                    "openStep":{"turn":5,"step":159,"startTime":1000,"firstTokenTime":null}
                }}"""
            )

            assertEquals(tokens.toDouble(), frame.sessionStats?.decodeTokens?.toDouble())
            assertEquals(158, frame.sessionStats?.steps)
            assertEquals(159.0, frame.sessionStats?.openStep?.get("step")?.doubleValue)
        }
    }

    @Test
    fun statsFailureNamesTheProtocolFieldWithoutExposingPayload() {
        val payload = """{"kind":"session-stats","sessionId":"private-session",
            "sessionStats":{"turns":4,"decodeTokens":"private-value"}}"""
        val failure = requireNotNull(runCatching { GatewayWireDecoder.decode(payload) }.exceptionOrNull())
        val summary = GatewayWireDecoder.failureSummary(payload, failure)

        val rendered = summary.args.joinToString(" ")
        assertEquals("wire.decodeFailed.typeMismatch", summary.key)
        assertTrue(rendered, rendered.contains("\$.sessionStats.decodeTokens"))
        assertEquals(listOf("number", "string"), summary.args.drop(1))
        assertFalse(rendered.contains("private-session"))
        assertFalse(rendered.contains("private-value"))
    }

    @Test
    fun statsAcceptInFlightStepObject() {
        for (firstTokenTime in listOf("null", "1200.5")) {
            val frame =
                GatewayWireDecoder.decode(
                    """{"kind":"session-stats","sessionId":"s1","asOfSeq":42,
                    "sessionStats":{"turns":3,"steps":157,"decodeTokens":385,
                        "openStep":{"turn":4,"step":158,"startTime":1000,"firstTokenTime":$firstTokenTime},
                        "pendingCalls":{}},
                    "tokenUsage":{"totals":{"inputTokens":100,"outputTokens":50}}}"""
                )

            assertEquals("session-stats", frame.kind)
            assertEquals(157, frame.sessionStats?.steps)
            assertEquals(
                158.0,
                frame.sessionStats
                    ?.openStep
                    ?.get("step")
                    ?.doubleValue
            )
            assertEquals(100L, frame.tokenUsage?.totals?.inputTokens)
        }
    }

    @Test
    fun statsAcceptCompletedStepAndPublicProjectionWithoutInternalState() {
        for (state in listOf("", ",\"openStep\":null", ",\"openStep\":158")) {
            val frame =
                GatewayWireDecoder.decode(
                    """{"kind":"session-stats","sessionId":"s1","sessionStats":{"turns":4,"steps":158$state}}"""
                )

            assertEquals(158, frame.sessionStats?.steps)
            assertNull(frame.tokenUsage)
        }
    }
}
