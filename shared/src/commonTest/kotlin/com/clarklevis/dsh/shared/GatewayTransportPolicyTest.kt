package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.transport.GatewayTransportPolicy
import com.clarklevis.dsh.shared.transport.RtcDiagnostics
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GatewayTransportPolicyTest {
    @Test fun privateAndLoopbackEntrancesUseWebSocketEvenWithTls() {
        for (host in listOf(
            "192.168.1.94",
            "10.0.0.1",
            "172.16.0.1",
            "172.31.255.254",
            "169.254.1.2",
            "127.0.0.1",
            "localhost",
            "desktop.local",
        )) {
            for (scheme in listOf("ws", "wss")) {
                assertFalse(GatewayTransportPolicy.allowsRtc("$scheme://$host:3081/ws/mobile"), host)
            }
        }
        for (host in listOf("[::1]", "[fd12::1]", "[fc00::1]", "[fe80::1%25en0]")) {
            assertFalse(GatewayTransportPolicy.allowsRtc("wss://$host/ws/mobile"), host)
        }
    }

    @Test fun externalEntrancesKeepRtcPolicyRegardlessOfDeviceNetwork() {
        for (host in listOf(
            "gateway.example",
            "a.trycloudflare.com",
            "8.8.8.8",
            "172.15.1.1",
            "172.32.0.1",
            "192.169.1.1",
            "[2001:db8::1]",
            "desktop.local.example",
        )) {
            assertTrue(GatewayTransportPolicy.allowsRtc("wss://$host/ws/mobile"), host)
        }
    }

    @Test fun invalidEntrancesCannotRequestRtcAndLanReasonIsLogged() {
        for (endpoint in listOf("", "https://gateway.example/ws/mobile", "wss://user:password@gateway.example/ws/mobile")) {
            assertFalse(GatewayTransportPolicy.allowsRtc(endpoint))
        }
        assertTrue(
            RtcDiagnostics
                .message("upgrade-skipped", "lan-websocket", "connecting", "control", "websocket", 0)
                .contains("reason=lan-websocket"),
        )
    }
}
