package com.clarklevis.dsh.android.ui

import com.clarklevis.dsh.shared.gateway.GatewayConnectionState
import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayAuthenticationUiTest {
    @Test
    fun connectionStateUsesTheSameLocalizedLabelsAsAuthenticationUi() {
        val expected = mapOf(
            GatewayConnectionState.DISCONNECTED to "未连接",
            GatewayConnectionState.CONNECTING to "正在连接",
            GatewayConnectionState.AUTHENTICATING to "正在连接",
            GatewayConnectionState.CONNECTED to "已连接",
            GatewayConnectionState.WAITING_FOR_NETWORK to "等待网络",
            GatewayConnectionState.SUSPENDED to "未连接",
            GatewayConnectionState.FAILED to "连接失败"
        )

        expected.forEach { (state, label) ->
            assertEquals(label, gatewayConnectionTitle(state))
        }
    }
}
