package com.clarklevis.dsh.android.ui

import com.clarklevis.dsh.android.localization.LocaleTestRule
import com.clarklevis.dsh.shared.gateway.GatewayConnectionState
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class GatewayAuthenticationUiTest {
    @get:Rule val locale = LocaleTestRule(Locale.ENGLISH)

    @Test
    fun connectionStateUsesTheSameLocalizedLabelsAsAuthenticationUi() {
        val expected = mapOf(
            GatewayConnectionState.DISCONNECTED to "Not connected",
            GatewayConnectionState.CONNECTING to "Connecting",
            GatewayConnectionState.AUTHENTICATING to "Connecting",
            GatewayConnectionState.CONNECTED to "Connected",
            GatewayConnectionState.WAITING_FOR_NETWORK to "Waiting for network",
            GatewayConnectionState.SUSPENDED to "Not connected",
            GatewayConnectionState.FAILED to "Connection failed"
        )

        expected.forEach { (state, label) ->
            assertEquals(label, gatewayConnectionTitle(state))
        }
    }
}
