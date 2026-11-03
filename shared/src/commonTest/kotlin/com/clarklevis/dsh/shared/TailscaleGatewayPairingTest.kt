package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.gateway.GatewayIdentity
import com.clarklevis.dsh.shared.gateway.GatewayPairingPayloadParser
import com.clarklevis.dsh.shared.protocol.GatewayPairingPayload
import com.clarklevis.dsh.shared.transport.GatewayTransportPolicy
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class TailscaleGatewayPairingTest {
    private val lan = "ws://192.168.1.19:3081/ws/mobile"
    private val tailscale = "ws://100.100.1.19:3081/ws/mobile"

    @Test fun tailscaleIPv4RangeAcceptsBothBoundariesAndUsesWebSocket() {
        for (host in listOf("100.64.0.0", "100.100.1.19", "100.127.255.255")) {
            assertTrue(GatewayIdentity.isLocal(host), host)
            for (scheme in listOf("ws", "wss")) {
                val endpoint = "$scheme://$host:3081/ws/mobile"
                GatewayIdentity.validateEndpoint(endpoint)
                assertFalse(GatewayTransportPolicy.allowsRtc(endpoint), endpoint)
            }
        }
        GatewayIdentity.validateEndpoint("ws://[fd7a:115c:a1e0::1]:3081/ws/mobile")
        assertFalse(GatewayTransportPolicy.allowsRtc("wss://[fd7a:115c:a1e0::1]:3081/ws/mobile"))
    }

    @Test fun neighboringPublicAddressesAndMalformedHostsRemainRejected() {
        for (host in listOf("100.63.255.255", "100.128.0.0", "100.255.255.255", "100.64.256.1", "100.100.1.19.example.com")) {
            assertFalse(GatewayIdentity.isLocal(host), host)
            assertFailsWith<IllegalArgumentException>(host) {
                GatewayIdentity.validateEndpoint("ws://$host:3081/ws/mobile")
            }
        }
        for (host in listOf("100.63.255.255", "100.128.0.0")) {
            assertTrue(GatewayTransportPolicy.allowsRtc("wss://$host:3081/ws/mobile"))
        }
        for (endpoint in listOf("$tailscale?token=secret", "$tailscale#fragment", "ws://user:pass@100.100.1.19:3081/ws/mobile")) {
            assertFailsWith<IllegalArgumentException> { GatewayIdentity.validateEndpoint(endpoint) }
        }
    }

    @Test fun qrPayloadAcceptsTailscaleAsPrimaryAndAsCandidateWithoutChangingAddresses() {
        for ((primary, candidate) in listOf(lan to tailscale, tailscale to lan)) {
            val payload = payload(primary, candidate)
            val decoded = GatewayPairingPayloadParser.parse(encode(payload), nowEpochMilliseconds = 1_000)
            assertEquals("once", decoded.pairingCode)
            assertEquals(listOf(primary, candidate), GatewayIdentity.endpoints(decoded))
        }
    }

    @Test fun qrPayloadStillRejectsExpiredCodesAndPublicPlaintextCandidates() {
        assertFailsWith<IllegalArgumentException> {
            GatewayPairingPayloadParser.parse(encode(payload(lan, tailscale).copy(expiresAt = 1_000.0)), 1_000)
        }
        assertFailsWith<IllegalArgumentException> {
            GatewayPairingPayloadParser.parse(encode(payload(lan, "ws://100.128.0.0:3081/ws/mobile")), 1_000)
        }
    }

    private fun payload(primary: String, candidate: String) = GatewayPairingPayload(
        version = 2,
        publicUrl = primary,
        pairingCode = "once",
        expiresAt = 4_102_444_800_000.0,
        endpoints = listOf(candidate)
    )

    private fun encode(payload: GatewayPairingPayload): String =
        Base64.UrlSafe.encode(Json.encodeToString(payload).encodeToByteArray()).trimEnd('=')
}
