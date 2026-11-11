package com.clarklevis.dsh.shared.gateway

import com.clarklevis.dsh.shared.i18n.UiMessage
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

// 直接保留协议数量/长度限制和私网、RFC 6598 共享地址段边界，便于核对。
@Suppress("MagicNumber")
object GatewayIdentity {
    private val uuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")

    fun validate(expected: String?, received: String?) {
        if (received != null && !uuid.matches(received)) {
            throw GatewayPairingPayloadException(UiMessage("gateway.identityInvalid"), "Invalid gateway identity format")
        }
        if (expected != null && !expected.equals(received, ignoreCase = true)) {
            throw GatewayPairingPayloadException(
                UiMessage("gateway.identityMismatch"),
                "Gateway identity mismatch or missing; confirm the host and pair again"
            )
        }
    }

    fun endpoints(payload: GatewayPairingPayload): List<String> {
        val endpoints = (listOf(payload.publicUrl) + payload.endpoints.orEmpty()).distinct()
        if (payload.endpoints.orEmpty().size > 16 || endpoints.size > 16) {
            throw GatewayPairingPayloadException(UiMessage("gateway.tooManyEndpoints"), "More than 16 candidate addresses")
        }
        endpoints.forEach(::validateEndpoint)
        return endpoints
    }

    fun validateEndpoint(endpoint: String) {
        requireWebSocketEndpoint(endpoint)
        if (endpoint.length > 2048 || '?' in endpoint || '#' in endpoint || '\\' in endpoint) {
            throw GatewayPairingPayloadException(
                UiMessage("gateway.endpointHasQuery"),
                "Invalid address: query parameters or fragments are not allowed"
            )
        }
        val host = host(endpoint)
        if (host in setOf("0.0.0.0", "::") || host.isBlank()) {
            throw GatewayPairingPayloadException(UiMessage("gateway.listenAddress"), "Listen addresses cannot be used to connect")
        }
        if (!endpoint.startsWith("wss://") && !isLocal(host)) {
            throw GatewayPairingPayloadException(UiMessage("gateway.wssRequired"), "Public connections must use WSS")
        }
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
                // Tailscale 的 100.64.0.0/10 共享地址段，与 iOS 的入口校验一致。
                100 -> octets[1] in 64..127
                192 -> octets[1] == 168
                172 -> octets[1] in 16..31
                169 -> octets[1] == 254
                else -> false
            }
        }
    }
}
