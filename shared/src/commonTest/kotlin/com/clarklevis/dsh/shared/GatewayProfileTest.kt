package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.gateway.GatewayIdentity
import com.clarklevis.dsh.shared.gateway.GatewayProfile
import com.clarklevis.dsh.shared.protocol.GatewayPairingPayload
import kotlin.test.*

class GatewayProfileTest {
    private val id = "d56a1098-8519-43a1-9dce-fb99863bf5bb"

    @Test fun identityAcceptsLegacyAndCaseInsensitiveUuid() {
        GatewayIdentity.validate(null, null)
        GatewayIdentity.validate(id, id.uppercase())
    }

    @Test fun identityRejectsDowngradeAndInvalidVersion() {
        assertFailsWith<IllegalArgumentException> { GatewayIdentity.validate(id, null) }
        assertFailsWith<IllegalArgumentException> { GatewayIdentity.validate(null, id.replace("43a1", "13a1")) }
    }

    @Test fun addressTrustRejectsPublicPlaintextAndEmbeddedCredentials() {
        listOf("ws://10.bad.0.0.1/ws/mobile", "ws://example.com/ws/mobile", "wss://user:pass@example.com/ws/mobile", "wss://example.com/ws/mobile?q=x", "wss://0.0.0.0/ws/mobile").forEach {
            assertFailsWith<IllegalArgumentException> { GatewayIdentity.validateEndpoint(it) }
        }
        GatewayIdentity.validateEndpoint("ws://192.168.1.10:3081/ws/mobile")
        GatewayIdentity.validateEndpoint("wss://example.com/ws/mobile")
    }

    @Test fun pairingAddressesDeduplicateAndLimitMergedCount() {
        val payload = GatewayPairingPayload(2, "wss://a.example/ws/mobile", "once", 4102444800000.0,
            endpoints = listOf("wss://a.example/ws/mobile", "ws://192.168.1.10/ws/mobile"))
        assertEquals(2, GatewayIdentity.endpoints(payload).size)
        assertFailsWith<IllegalArgumentException> {
            GatewayIdentity.endpoints(payload.copy(endpoints = (1..16).map { "wss://host$it.example/ws/mobile" }))
        }
    }

    @Test fun preferredEndpointCannotIntroduceAnUntrustedAddress() {
        val profile = GatewayProfile("local", gatewayName = "电脑", endpoints = listOf("wss://a.example/ws/mobile"), preferredEndpoint = "wss://evil.example/ws/mobile")
        assertEquals(profile.endpoints, profile.connectionEndpoints)
    }
}
