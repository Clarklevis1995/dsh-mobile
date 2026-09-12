package com.clarklevis.dsh.shared.gateway

import com.clarklevis.dsh.shared.protocol.GatewayPairingPayload
import kotlinx.serialization.Serializable

@Serializable
data class GatewayProfile(
    val localId: String,
    val gatewayId: String? = null,
    val gatewayName: String,
    val alias: String? = null,
    val endpoints: List<String>,
    val preferredEndpoint: String? = null,
    val remoteDeviceId: String? = null,
    val lastConnectedAt: Long? = null,
    val server: Boolean = false
) {
    val displayName: String get() = alias?.takeIf { it.isNotBlank() } ?: gatewayName
    val connectionEndpoints: List<String>
        get() = (listOfNotNull(preferredEndpoint) + endpoints).filter { it in endpoints }.distinct()
}

// 直接保留协议数量/长度限制和 RFC 私网地址段边界，便于核对。
@Suppress("MagicNumber")
object GatewayIdentity {
    private val uuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")

    fun validate(expected: String?, received: String?) {
        require(received == null || uuid.matches(received)) { "网关身份格式无效" }
        require(expected == null || expected.equals(received, ignoreCase = true)) {
            "网关身份不一致或缺失，请确认主机后重新配对"
        }
    }

    fun endpoints(payload: GatewayPairingPayload): List<String> {
        require(payload.endpoints.orEmpty().size <= 16) { "候选地址超过 16 个" }
        val endpoints = (listOf(payload.publicUrl) + payload.endpoints.orEmpty()).distinct()
        require(endpoints.size <= 16) { "候选地址超过 16 个" }
        endpoints.forEach(::validateEndpoint)
        return endpoints
    }

    fun validateEndpoint(endpoint: String) {
        requireWebSocketEndpoint(endpoint)
        require(endpoint.length <= 2048 && '?' !in endpoint && '#' !in endpoint && '\\' !in endpoint) {
            "地址无效，不能包含查询参数或片段"
        }
        val host = host(endpoint)
        require(host !in setOf("0.0.0.0", "::") && host.isNotBlank()) { "监听地址不能用于连接" }
        require(endpoint.startsWith("wss://") || isLocal(host)) { "公网连接必须使用 WSS" }
    }

    fun host(endpoint: String): String {
        val authority = endpoint.substringAfter("://").substringBefore('/')
        return if (authority.startsWith('[')) {
            authority.substringAfter('[').substringBefore(']').lowercase()
        } else {
            authority.substringBefore(':').lowercase()
        }
    }

    fun isLocal(host: String): Boolean = when {
        host == "localhost" || host == "::1" || host.endsWith(".local") -> true
        ':' in host -> listOf("fc", "fd", "fe80:").any(host::startsWith)
        else -> isLocalIpv4(host)
    }

    private fun isLocalIpv4(host: String): Boolean {
        val parts = host.split('.')
        val octets = parts.mapNotNull(String::toIntOrNull)
        return when {
            parts.size != 4 || octets.size != 4 || octets.any { it !in 0..255 } -> false
            else -> when (octets[0]) {
                10, 127 -> true
                192 -> octets[1] == 168
                172 -> octets[1] in 16..31
                169 -> octets[1] == 254
                else -> false
            }
        }
    }
}
