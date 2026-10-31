package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.transport.RtcPathInspector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RtcSelectedPathTest {
    private fun stats(
        localType: String = "host",
        remoteType: String = "host",
        local: String = "192.168.1.20",
        remote: String = "192.168.1.94",
        transport: String = """{"id":"t","type":"transport","selectedCandidatePairId":"used"},""",
        selected: String = "",
    ) = """[
        $transport
        {"id":"unused","type":"candidate-pair","state":"succeeded","nominated":true,"localCandidateId":"relay","remoteCandidateId":"r"},
        {"id":"relay","type":"local-candidate","candidateType":"relay","address":"203.0.113.1"},
        {"id":"used","type":"candidate-pair","state":"succeeded",$selected"localCandidateId":"l","remoteCandidateId":"r"},
        {"id":"l","type":"local-candidate","candidateType":"$localType","address":"$local","port":50000,"protocol":"udp"},
        {"id":"r","type":"remote-candidate","candidateType":"$remoteType","address":"$remote","port":51000,"protocol":"udp"}
    ]"""

    @Test fun selectedPairIgnoresUnusedRelayAndFormatsBothEndpoints() {
        val path = RtcPathInspector.inspect(stats())!!
        assertEquals("local", path.kind)
        assertEquals("UDP", path.protocol)
        assertEquals("192.168.1.20:50000", path.localEndpoint)
        assertEquals("192.168.1.94:51000", path.remoteEndpoint)
    }

    @Test fun turnOnEitherSideIsRelay() {
        assertEquals("relay", RtcPathInspector.inspect(stats(localType = "relay"))?.kind)
        assertEquals("relay", RtcPathInspector.inspect(stats(remoteType = "relay"))?.kind)
    }

    @Test fun missingCandidateTypeNeverClaimsDirect() {
        assertNull(RtcPathInspector.inspect(stats(localType = "")))
        assertNull(RtcPathInspector.inspect(stats(remoteType = "unknown")))
    }

    @Test fun reflexivePairsAreNatDirectEvenWithPrivateLocalInterface() {
        for (type in listOf("srflx", "prflx")) {
            assertEquals("nat", RtcPathInspector.inspect(stats(remoteType = type, remote = "203.0.113.5"))?.kind)
        }
    }

    @Test fun publicIPv6AndHiddenAddressesDoNotClaimLan() {
        for (address in listOf("2001:db8::10", "device.local", "", "192.168.1.999")) {
            assertEquals("direct", RtcPathInspector.inspect(stats(local = address))?.kind)
        }
        assertEquals("—", RtcPathInspector.inspect(stats(local = ""))?.localEndpoint)
        assertEquals("[2001:db8::10]:50000", RtcPathInspector.inspect(stats(local = "2001:db8::10"))?.localEndpoint)
    }

    @Test fun privateIPv6AndMappedIPv4RecognizeInternalAddressPairs() {
        for (address in listOf("fd12::1", "fc00::1", "fe80::1%en0", "::ffff:192.168.1.20")) {
            assertEquals("local", RtcPathInspector.inspect(stats(local = address))?.kind)
        }
    }

    @Test fun onlyExplicitUniqueSelectionCanReplaceTransportReference() {
        assertNull(RtcPathInspector.inspect(stats(transport = "")))
        assertEquals("local", RtcPathInspector.inspect(stats(transport = "", selected = "\"selected\":true,"))?.kind)
        assertNull(RtcPathInspector.inspect(stats().replace("\"used\"},", "\"missing\"},")))
        assertNull(RtcPathInspector.inspect(stats().replace("\"state\":\"succeeded\"", "\"state\":\"failed\"")))
        assertNull(RtcPathInspector.inspect("not json"))
    }
}
