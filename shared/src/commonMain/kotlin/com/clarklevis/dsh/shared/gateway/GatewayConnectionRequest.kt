package com.clarklevis.dsh.shared.gateway

import com.clarklevis.dsh.shared.platform.GatewayConnectionSpec
import com.clarklevis.dsh.shared.platform.GatewayTransport
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString

/**
 * 候选地址集合：优先地址在前，其余按既有顺序，去重。
 * `candidates` 为空表示只有 [GatewayConnectionSpec.endpoint] 一个候选。
 */
fun GatewayConnectionSpec.connectionCandidates(): List<String> =
    (listOf(endpoint) + candidates).distinct()

/**
 * 一次性配对码只能在一个地址上兑换：配对请求必须退化为单候选，
 * 否则 N 条并发 socket 会各自尝试消耗同一个配对码。
 */
fun GatewayConnectionSpec.pairingCandidates(): List<String> =
    if (pairingCode.isNullOrBlank()) connectionCandidates() else listOf(endpoint)

/** 平台唯一职责：为一个候选地址造一条空闲的有序通道。纯装配，不做任何 I/O 与判断。 */
fun interface GatewaySocketFactory {
    fun createChannel(spec: GatewayConnectionSpec, endpoint: String): GatewayTransport
}

/** 单个候选的失败记录；endpoint 已脱敏（无 query / 无凭据）。 */
data class GatewayCandidateFailure(
    val endpoint: String,
    val httpStatus: Int? = null,
    val closeCode: Int? = null,
    val reason: String,
    val recoverable: Boolean = true
)

/** 全部候选都失败时的聚合记录：这是“为什么都连不上”的可诊断证据。 */
data class GatewayRaceFailure(
    val attempted: Int,
    val winnerEndpoint: String?,
    val failures: List<GatewayCandidateFailure>
)

/** 判据：这一个候选的通道是否已经完成“应用层握手”，即真的可用。 */
sealed interface GatewayHandshakeVerdict {
    /** 不是握手帧，继续等待。 */
    data object Pending : GatewayHandshakeVerdict

    /** 应用层握手完成且身份校验通过：该候选可用。 */
    data class Admitted(val kind: String, val gatewayId: String?) : GatewayHandshakeVerdict

    /** 本候选不可用；fatal 表示无需再问别的地址（身份不符 / 认证被拒）。 */
    data class Rejected(
        val code: String,
        val fatal: Boolean,
        val httpStatus: Int? = null,
        val closeCode: Int? = null
    ) : GatewayHandshakeVerdict
}

/**
 * “可用”的单一定义：只有 application-level 的 `hello` / `paired` 且身份校验通过才算。
 * TCP/WebSocket 握手成功不算——那时网关可能还不知道我们是谁。
 *
 * 该判据同时被竞赛层（挑赢家）与 `SplitGatewayTransport`（会话通道就绪）使用，
 * 避免出现两份会各自漂移的“什么算可用”。
 */
object GatewayHandshake {
    @Serializable
    data class Header(
        val kind: String? = null,
        val token: String? = null,
        val gatewayId: String? = null,
        val capabilities: List<String> = emptyList()
    )

    /** 轻量头部读取：只解码判据需要的字段，不产出业务对象。 */
    fun header(text: String): Header? =
        runCatching { wireJson.decodeFromString<Header>(text) }.getOrNull()

    fun evaluate(text: String, expectedGatewayId: String?): GatewayHandshakeVerdict {
        val header = header(text) ?: return GatewayHandshakeVerdict.Pending
        val kind = header.kind ?: return GatewayHandshakeVerdict.Pending
        if (kind != "hello" && kind != "paired") return GatewayHandshakeVerdict.Pending
        val identity = runCatching {
            GatewayIdentity.validate(expectedGatewayId, header.gatewayId)
        }
        if (identity.isFailure) {
            return GatewayHandshakeVerdict.Rejected("gateway-identity-mismatch", fatal = true)
        }
        return GatewayHandshakeVerdict.Admitted(kind, header.gatewayId)
    }

    /** `hello` 是否要求开启独立会话通道。 */
    fun requiresConversationChannel(text: String): Boolean =
        header(text)?.let { it.kind == "hello" && "split-channels" in it.capabilities } == true

    /** 配对响应中携带的一次性长期 token。 */
    fun pairedToken(text: String): String? =
        header(text)?.takeIf { it.kind == "paired" }?.token
}
