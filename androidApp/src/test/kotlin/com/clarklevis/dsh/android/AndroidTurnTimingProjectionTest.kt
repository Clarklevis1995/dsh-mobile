package com.clarklevis.dsh.android

import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidTurnTimingProjectionTest {
    @Test
    fun projectionUsesCurrentTurnBoundaryInsteadOfOldUserMessage() {
        var now = 100.0
        val projection = AndroidGatewayProjection(nowMonotonicSeconds = { now })
        projection.selectSession("s", offline = true)
        accept(projection, """{"kind":"event","sessionId":"s","seq":1,"time":1000,"event":{"type":"user/message","text":"old"}}""")
        accept(projection, """{"kind":"event","sessionId":"s","seq":2,"time":2054,"event":{"type":"turn/start","turn":25}}""")
        now += 8
        val timing = requireNotNull(projection.snapshot().activeTurnTiming)
        assertEquals(2, timing.startSequence)
        assertEquals(8.0, timing.elapsedSeconds(now), 0.001)
        accept(projection, """{"kind":"event","sessionId":"s","seq":3,"time":2062,"event":{"type":"turn/end","turn":25}}""")
        assertNull(projection.snapshot().activeTurnTiming)
    }

    @Test
    fun userMessageAloneDoesNotInventTurnTiming() {
        val projection = AndroidGatewayProjection(nowMonotonicSeconds = { 100.0 })
        projection.selectSession("s", offline = true)
        accept(projection, """{"kind":"event","sessionId":"s","seq":1,"time":1000,"event":{"type":"user/message","text":"old"}}""")
        assertNull(projection.snapshot().activeTurnTiming)
    }

    private fun accept(projection: AndroidGatewayProjection, raw: String) {
        projection.acceptFrame(raw, GatewayWireDecoder.decode(raw), null)
    }
}
