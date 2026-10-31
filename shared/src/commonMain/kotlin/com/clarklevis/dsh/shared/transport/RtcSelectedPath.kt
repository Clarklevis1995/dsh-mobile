package com.clarklevis.dsh.shared.transport

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** 仅表示当前选中的连接；不依据配对入口、Wi-Fi 状态或未使用的候选推断路径。 */
data class RtcSelectedPath(
    val kind: String,
    val protocol: String,
    val localEndpoint: String,
    val remoteEndpoint: String,
    val localCandidateType: String,
    val remoteCandidateType: String,
)

object RtcPathInspector {
    private const val MAX_PORT = 65535

    fun inspect(statsJson: String): RtcSelectedPath? =
        runCatching {
            val reports = (Json.parseToJsonElement(statsJson) as? JsonArray)?.filterIsInstance<JsonObject>() ?: return null
            val byId = reports.associateBy { it.text("id") }
            val pair = selectedPair(reports, byId) ?: return null
            val local = byId[pair.text("localCandidateId")] ?: return null
            val remote = byId[pair.text("remoteCandidateId")] ?: return null
            path(local, remote)
        }.getOrNull()

    private fun selectedPair(
        reports: List<JsonObject>,
        byId: Map<String?, JsonObject>,
    ): JsonObject? {
        val selectedIds =
            reports
                .filter { it.text("type") == "transport" }
                .mapNotNull { it.text("selectedCandidatePairId") }
                .distinct()
        // nominated/succeeded 本身不代表正在传输。兼容旧统计时也必须唯一且明确 selected。
        val pair =
            if (selectedIds.isNotEmpty()) {
                val selectedId = selectedIds.singleOrNull() ?: return null
                byId[selectedId]
            } else {
                reports.singleOrNull {
                    it.text("type") == "candidate-pair" && it.flag("selected") && it.text("state") == "succeeded"
                }
            }
        return pair?.takeIf { it.text("type") == "candidate-pair" && it.text("state") == "succeeded" }
    }

    private fun path(
        local: JsonObject,
        remote: JsonObject,
    ): RtcSelectedPath? {
        val localType = local.text("candidateType").orEmpty()
        val remoteType = remote.text("candidateType").orEmpty()
        val types = setOf("host", "srflx", "prflx", "relay")
        if (localType !in types || remoteType !in types) return null
        val kind =
            when {
                localType == "relay" || remoteType == "relay" -> "relay"

                localType == "host" && remoteType == "host" &&
                    privateAddress(
                        local.address(),
                    ) && privateAddress(remote.address()) -> "local"

                localType in setOf("srflx", "prflx") || remoteType in setOf("srflx", "prflx") -> "nat"

                else -> "direct"
            }
        return RtcSelectedPath(
            kind = kind,
            protocol = local.text("protocol")?.uppercase() ?: "—",
            localEndpoint = local.endpoint(),
            remoteEndpoint = remote.endpoint(),
            localCandidateType = localType,
            remoteCandidateType = remoteType,
        )
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.flag(key: String) = (get(key) as? JsonPrimitive)?.booleanOrNull == true

    private fun JsonObject.address() = (text("address") ?: text("ip")).orEmpty()

    private fun JsonObject.endpoint(): String {
        val address = address()
        if (address.isBlank()) return "—"
        val host = if (':' in address) "[$address]" else address
        val port = (get("port") as? JsonPrimitive)?.intOrNull?.takeIf { it in 1..MAX_PORT }
        return if (port == null) host else "$host:$port"
    }

    @Suppress("MagicNumber")
    private fun privateAddress(address: String): Boolean {
        val normalized = address.lowercase().substringBefore('%')
        return when {
            normalized.startsWith("::ffff:") -> {
                privateAddress(normalized.removePrefix("::ffff:"))
            }

            ':' in normalized -> {
                normalized.substringBefore(':').toIntOrNull(16)?.let {
                    it in 0xfc00..0xfdff || it in 0xfe80..0xfebf
                } ?: false
            }

            else -> {
                privateIPv4(normalized)
            }
        }
    }

    @Suppress("MagicNumber")
    private fun privateIPv4(address: String): Boolean {
        val parts = address.split('.')
        val bytes = parts.mapNotNull { it.toIntOrNull() }
        if (parts.size != 4 || bytes.size != 4 || bytes.any { it !in 0..255 }) return false
        return bytes[0] == 10 || (bytes[0] == 172 && bytes[1] in 16..31) ||
            (bytes[0] == 192 && bytes[1] == 168) || (bytes[0] == 169 && bytes[1] == 254)
    }
}
