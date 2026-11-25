package com.clarklevis.dsh.android

import com.clarklevis.dsh.shared.gateway.GatewayProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidNotificationSessionRouteTest {
    private val home = profile(localId = "local-home", gatewayId = "GW-Home")
    private val office = profile(localId = "local-office", gatewayId = "gw-office")
    private val unpaired = profile(localId = "local-unpaired", gatewayId = null)
    private val profiles = listOf(home, office, unpaired)

    private fun parse(link: String, active: String? = home.localId) =
        AndroidNotificationSessionRoute.parseDeepLink(link, profiles, active)

    @Test
    fun gatewayIdentityOpensTheMatchingSavedHost() {
        assertEquals(AndroidNotificationSessionRoute("local-home", "s1"), parse("dshmobile://session?gatewayId=GW-Home&id=s1"))
        assertEquals(AndroidNotificationSessionRoute("local-office", "s2"), parse("dshmobile://session?gatewayId=gw-office&id=s2"))
    }

    @Test
    fun gatewayIdentityMatchesCaseInsensitively() {
        assertEquals(AndroidNotificationSessionRoute("local-home", "s1"), parse("dshmobile://session?gatewayId=gw-home&id=s1"))
        assertEquals(AndroidNotificationSessionRoute("local-office", "s1"), parse("dshmobile://session?gatewayId=GW-OFFICE&id=s1"))
    }

    @Test
    fun legacyLocalIdOpensOnlyTheCurrentHost() {
        assertEquals(AndroidNotificationSessionRoute("local-home", "s1"), parse("dshmobile://session?gateway=local-home&id=s1"))
        assertNull(parse("dshmobile://session?gateway=local-office&id=s1"))
        assertNull(parse("dshmobile://session?gateway=local-home&id=s1", active = null))
    }

    @Test
    fun legacyParameterWinsInEitherOrderWithoutFallback() {
        val route = AndroidNotificationSessionRoute("local-home", "s1")
        assertEquals(route, parse("dshmobile://session?gateway=local-home&gatewayId=gw-office&id=s1"))
        assertEquals(route, parse("dshmobile://session?gatewayId=gw-office&id=s1&gateway=local-home"))
        assertNull(parse("dshmobile://session?gateway=local-office&gatewayId=gw-home&id=s1"))
    }

    @Test
    fun unknownMissingOrEmptyParametersAreRejected() {
        assertNull(parse("dshmobile://session?gatewayId=gw-unknown&id=s1"))
        assertNull(parse("dshmobile://session?gatewayId=gw-home"))
        assertNull(parse("dshmobile://session?gatewayId=gw-home&id="))
        assertNull(parse("dshmobile://session?gatewayId=&id=s1"))
        assertNull(parse("dshmobile://session?id=s1"))
        assertNull(parse("dshmobile://session"))
    }

    @Test
    fun otherSchemesAndHostsAreIgnored() {
        assertNull(parse("dshmobile://share?gatewayId=gw-home&id=s1"))
        assertNull(parse("https://session?gatewayId=gw-home&id=s1"))
        assertNull(parse("paseo://session?gatewayId=gw-home&id=s1"))
        assertNull(parse("not a link"))
    }

    @Test
    fun sessionIdIsPercentDecodedAndPlusStaysLiteral() {
        assertEquals("a/b c+d", parse("dshmobile://session?gatewayId=gw-home&id=a%2Fb%20c+d")?.sessionId)
    }

    private fun profile(localId: String, gatewayId: String?) = GatewayProfile(
        localId = localId,
        gatewayId = gatewayId,
        gatewayName = localId,
        endpoints = listOf("ws://192.168.1.20:3081/ws/mobile"),
    )
}
