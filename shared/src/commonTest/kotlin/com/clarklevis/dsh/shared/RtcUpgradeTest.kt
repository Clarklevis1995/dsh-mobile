package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.transport.MgwCodec
import com.clarklevis.dsh.shared.transport.RtcDiagnostics
import com.clarklevis.dsh.shared.transport.RtcUpgradeAction
import com.clarklevis.dsh.shared.transport.RtcUpgradeMachine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RtcUpgradeTest {
    private val gateway = "12345678-1234-4234-8234-123456789abc"
    private val session = "abcdef12-1234-4234-8234-123456789abc"
    private val hello get() = """{"kind":"hello","gatewayId":"$gateway","capabilities":["split-channels"]}"""
    private val bootstrap get() =
        """{"kind":"hello","gatewayId":"$gateway","capabilities":["webrtc-upgrade-v1"],"transport":{"version":1,"bootstrap":true,"negotiateTimeoutMs":12000}}"""
    private val offer get() = """{"kind":"transport-offered","requestId":"n-test","transportSessionId":"$session","channels":["control","conversation"],"iceServers":[],"receiveLimits":{"maxFrameBytes":16384,"maxLogicalMessageBytes":16777216},"bindTickets":{"control":"${"a".repeat(
        43,
    )}","conversation":"${"b".repeat(43)}"}}"""

    private fun machine() =
        RtcUpgradeMachine(gateway, "test").also {
            assertFalse(it.webSocket(bootstrap).any { action -> action.kind == "deliver" })
            assertEquals(2, it.webSocket(offer).count { action -> action.kind == "rtc-start" })
        }

    private fun bound(
        machine: RtcUpgradeMachine,
        channel: String,
    ): List<RtcUpgradeAction> {
        assertEquals("rtc-text", machine.rtcOpened(channel).single().kind)
        return machine.rtcText(
            channel,
            """{"kind":"transport-bound","version":1,"gatewayId":"$gateway","transportSessionId":"$session","channel":"$channel"}""",
        )
    }

    private fun ready() =
        """{"kind":"transport-ready","transportSessionId":"$session","channels":{"control":"bound","conversation":"bound"}}"""

    private fun selected(mode: String) =
        """{"kind":"transport-selected","requestId":"s-test","transportSessionId":"$session","mode":"$mode"}"""

    @Test fun fallbackDiagnosticsRetainOriginalPhaseAndDistinguishTriggers() {
        val timeout = machine()
        timeout.deadline()
        assertEquals("negotiation-timeout", timeout.diagnosticReason)
        assertEquals("negotiating", timeout.diagnosticPhase)
        assertEquals("selecting-websocket", timeout.phase)
        val native = machine()
        native.rtcUnavailable()
        assertEquals("native-rtc-unavailable", native.diagnosticReason)
        val remote = machine()
        remote.webSocket("""{"kind":"transport-failed","transportSessionId":"$session","recoverable":true}""")
        assertEquals("gateway-rtc-unavailable", remote.diagnosticReason)
        val blocked = machine()
        blocked.securityFailure()
        assertEquals("protocol-validation-failed", blocked.diagnosticReason)
        assertEquals("negotiating", blocked.diagnosticPhase)
        val legacy = RtcUpgradeMachine(gateway, "test")
        legacy.webSocket(hello)
        assertEquals("gateway-no-upgrade", legacy.diagnosticReason)
        val active = machine()
        bound(active, "control")
        bound(active, "conversation")
        active.webSocket(ready())
        active.webSocket(selected("webrtc"))
        active.rtcMessage("control", hello)
        active.rtcMessage("conversation", hello)
        active.rtcUnavailable()
        assertEquals("rtc-connection-lost", active.diagnosticReason)
        assertEquals("active", active.diagnosticPhase)
    }

    @Test fun diagnosticFormattingOnlyAllowsFixedMetadata() {
        val line = RtcDiagnostics.message("fallback-requested", "negotiation-timeout", "negotiating", "conversation", "none", 10500)
        assertTrue(line.contains("[GatewayRTC] event=fallback-requested reason=negotiation-timeout phase=negotiating"))
        assertTrue(line.contains("channel=conversation mode=none elapsedMs=10500"))
        val secret = "wss://private.example/token=secret\nSDP-secret"
        val redacted = RtcDiagnostics.message(secret, secret, secret, secret, secret, -1)
        assertFalse(redacted.contains("secret"))
        assertTrue(redacted.contains("elapsedMs=0"))
    }

    @Test fun lateRemoteIceCanArriveAfterReadyBeforeSelectionAcknowledgement() {
        val machine = machine()
        bound(machine, "control")
        bound(machine, "conversation")
        machine.webSocket(ready())
        val candidate =
            """
            {"kind":"transport-signal","transportSessionId":"$session","channel":"control",
             "signalSeq":1,"signalType":"candidate",
             "candidate":{"candidate":"candidate:1 1 UDP 2122260223 203.0.113.10 50000 typ host",
                          "sdpMid":"0","sdpMLineIndex":0}}
            """.trimIndent()
        assertEquals("rtc-signal", machine.webSocket(candidate).single().kind)
        val ended =
            """
            {"kind":"transport-signal","transportSessionId":"$session","channel":"control",
             "signalSeq":2,"signalType":"end-of-candidates"}
            """.trimIndent()
        assertEquals("rtc-signal", machine.webSocket(ended).single().kind)
        assertEquals("selecting-webrtc", machine.phase)
        machine.webSocket(selected("webrtc"))
        machine.rtcMessage("control", hello)
        machine.rtcMessage("conversation", hello)
        assertEquals("active", machine.phase)
    }

    @Test fun lateIceStillRejectsWrongSessionSequenceChannelAndNewAnswer() {
        for (fields in listOf(
            "\"transportSessionId\":\"$gateway\",\"channel\":\"control\",\"signalSeq\":1,\"signalType\":\"end-of-candidates\"",
            "\"transportSessionId\":\"$session\",\"channel\":\"control\",\"signalSeq\":2,\"signalType\":\"end-of-candidates\"",
            "\"transportSessionId\":\"$session\",\"channel\":\"other\",\"signalSeq\":1,\"signalType\":\"end-of-candidates\"",
            "\"transportSessionId\":\"$session\",\"channel\":\"control\",\"signalSeq\":1,\"signalType\":\"answer\",\"sdp\":\"m=application 9 UDP/DTLS/SCTP webrtc-datachannel\"",
        )) {
            val machine = machine()
            bound(machine, "control")
            bound(machine, "conversation")
            machine.webSocket(ready())
            assertEquals("security-failure", machine.webSocket("""{"kind":"transport-signal",$fields}""").last().kind)
        }
    }

    @Test
    fun nativeEndOfCandidatesIsEmittedOnceAndSealsTheLocalCandidateStream() {
        val machine = machine()
        assertEquals(1, machine.localSignal("control", "offer", "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n").size)
        assertEquals(1, machine.localSignal("control", "end-of-candidates", "").size)
        assertTrue(machine.localSignal("control", "end-of-candidates", "").isEmpty())
        assertTrue(machine.localSignal("control", "candidate", "{}").isEmpty())
        assertEquals("negotiating", machine.phase)
    }

    @Test fun legacyHelloPassesThroughWithoutArtificialHandshake() {
        val actions = RtcUpgradeMachine(gateway, "test").webSocket(hello)
        assertEquals(hello, actions.single { it.kind == "deliver" }.text)
        assertFalse(actions.any { it.kind == "ws-send" || it.kind == "rtc-start" })
    }

    @Test fun readyMayOvertakeBoundAndHelloMayOvertakeSelected() {
        val machine = machine()
        assertTrue(machine.webSocket(ready()).isEmpty())
        assertTrue(bound(machine, "control").isEmpty())
        assertEquals(
            "transport-select",
            Json
                .parseToJsonElement(bound(machine, "conversation").single().text)
                .jsonObject["type"]!!
                .jsonPrimitive.content,
        )
        assertTrue(machine.rtcMessage("conversation", hello).isEmpty())
        assertTrue(machine.rtcMessage("control", hello).isEmpty())
        val actions = machine.webSocket(selected("webrtc"))
        assertEquals(listOf("cancel-deadline", "selected", "rtc-send", "rtc-send", "deliver", "deliver"), actions.map { it.kind })
        assertEquals("active", machine.phase)
        assertTrue(machine.webSocket(selected("webrtc")).isEmpty())
    }

    @Test fun selectedBeforeHellosStillGatesBothChannels() {
        val machine = machine()
        bound(machine, "control")
        bound(machine, "conversation")
        machine.webSocket(ready())
        assertTrue(machine.webSocket(selected("webrtc")).isEmpty())
        assertTrue(machine.rtcMessage("control", hello).isEmpty())
        assertEquals(2, machine.rtcMessage("conversation", hello).count { it.kind == "deliver" })
    }

    @Test fun failureNeedsExplicitWebsocketSelectionAndNewBusinessHello() {
        val machine = machine()
        val actions = machine.rtcUnavailable()
        assertEquals(listOf("rtc-close", "ws-send"), actions.map { it.kind })
        assertFalse(actions.any { it.kind == "deliver" })
        machine.webSocket(selected("websocket"))
        assertEquals(hello, machine.webSocket(hello).single().text)
    }

    @Test fun uncertainCommitRetriesNewWebsocketAndNeverConflicts() {
        val machine = machine()
        bound(machine, "control")
        bound(machine, "conversation")
        machine.webSocket(ready())
        assertEquals("retry-websocket", machine.deadline().last().kind)
        assertEquals("selection-timeout", machine.diagnosticReason)
        assertEquals("selecting-webrtc", machine.diagnosticPhase)
    }

    @Test fun securityFailureDoesNotDowngrade() {
        val machine = machine()
        assertEquals(
            "security-failure",
            machine.webSocket("""{"kind":"transport-failed","transportSessionId":"$session","recoverable":false}""").last().kind,
        )
    }

    @Test fun signalOrderAndSessionAreValidated() {
        val machine = machine()
        assertEquals(
            "security-failure",
            machine
                .webSocket(
                    """{"kind":"transport-signal","transportSessionId":"$session","channel":"control","signalSeq":2,"signalType":"end-of-candidates"}""",
                ).last()
                .kind,
        )
    }

    @Test fun duplicateEscapedKeysAndUntrustedIdentityFailClosed() {
        val machine = RtcUpgradeMachine(gateway, "test")
        assertEquals("security-failure", machine.webSocket("""{"kind":"hello","k\u0069nd":"hello"}""").last().kind)
        assertEquals("security-failure", RtcUpgradeMachine(gateway, "test").webSocket(hello.replace(gateway, session)).last().kind)
    }

    @Test fun prematureBusinessAndCrossChannelBindAreRejected() {
        val machine = machine()
        bound(machine, "control")
        assertEquals("security-failure", machine.rtcMessage("control", "{}").last().kind)
        val other = machine()
        other.rtcOpened("control")
        assertEquals(
            "security-failure",
            other
                .rtcText(
                    "control",
                    """{"kind":"transport-bound","version":1,"gatewayId":"$gateway","transportSessionId":"$session","channel":"conversation"}""",
                ).last()
                .kind,
        )
    }

    @Test fun goldenVectorAndAllInvalidHeaderFields() {
        val golden = hex("4d475731010001000000000000000001000000000000000100000002000000027b7d")
        assertContentEquals(golden, MgwCodec(1).encode("{}").single())
        assertEquals("{}", MgwCodec(1).receive(golden, 0))
        for (offset in listOf(0, 4, 5, 6, 7, 15, 19, 23, 27, 31)) {
            val broken = golden.copyOf()
            broken[offset] = (broken[offset].toInt() xor 3).toByte()
            assertFails("offset=$offset") { MgwCodec(1).receive(broken, 0) }
        }
    }

    @Test fun unicodeAndSixteenMiBBoundaryRoundTrip() {
        for (text in listOf("{\"text\":\"你好😀\"}", "x".repeat(16 * 1024 * 1024))) {
            val frames = MgwCodec(2).encode(text)
            val receiver = MgwCodec(2)
            assertEquals(text, frames.mapNotNull { receiver.receive(it, 0) }.single())
            assertTrue(frames.all { it.size <= 16384 })
        }
        assertFails { MgwCodec(1).encode("x".repeat(16 * 1024 * 1024 + 1)) }
    }

    @Test fun duplicateChunkMissingChunkTimeoutInvalidUtf8AndClose() {
        val frames = MgwCodec(1).encode("a".repeat(16384))
        val receiver = MgwCodec(1)
        assertNull(receiver.receive(frames[0], 0))
        assertFails { receiver.receive(frames[0], 1) }
        assertFails { receiver.checkIdle(30000) }
        receiver.close()
        assertFalse(receiver.hasPartialMessage)
        val invalid = MgwCodec(1).encode("x").single()
        invalid[32] = 0xff.toByte()
        assertFails { MgwCodec(1).receive(invalid, 0) }
    }

    private fun hex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
