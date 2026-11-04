package com.clarklevis.dsh.shared.transport

import com.clarklevis.dsh.shared.gateway.GatewayIdentity

/** 内网入口直接 WebSocket；外网入口才尝试 RTC，与手机使用 Wi-Fi 或蜂窝网络无关。 */
object GatewayTransportPolicy {
    fun allowsRtc(endpoint: String): Boolean =
        runCatching {
            GatewayIdentity.validateEndpoint(endpoint)
            !GatewayIdentity.isLocal(GatewayIdentity.host(endpoint).trimEnd('.'))
        }.getOrDefault(false)
}
