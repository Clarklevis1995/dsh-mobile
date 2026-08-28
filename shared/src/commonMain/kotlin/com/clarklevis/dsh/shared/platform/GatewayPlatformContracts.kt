package com.clarklevis.dsh.shared.platform

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 平台 WebSocket 的最小连接参数。凭据只允许传给 transport，禁止序列化、持久化或日志输出。
 */
data class GatewayConnectionSpec(
    val generation: Long,
    val endpoint: String,
    val deviceId: String,
    val bearerToken: String? = null,
    val pairingCode: String? = null
) {
    override fun toString(): String =
        "GatewayConnectionSpec(generation=$generation, endpoint=<redacted>, deviceId=<redacted>, " +
            "bearerToken=${bearerToken?.let { "<redacted>" }}, " +
            "pairingCode=${pairingCode?.let { "<redacted>" }})"
}

sealed interface GatewayTransportState {
    val generation: Long

    data class Closed(override val generation: Long = 0) : GatewayTransportState
    data class Opening(override val generation: Long) : GatewayTransportState
    data class Open(override val generation: Long) : GatewayTransportState

    data class Failed(
        override val generation: Long,
        val httpStatus: Int? = null,
        val closeCode: Int? = null,
        val reason: String? = null,
        val recoverable: Boolean = true
    ) : GatewayTransportState
}

/** 入站文本携带不可复用的连接代次；文本内容不得进入 toString 或日志。 */
data class GatewayTransportFrame(
    val generation: Long,
    val text: String,
    val byteCount: Int
) {
    override fun toString(): String =
        "GatewayTransportFrame(generation=$generation, byteCount=$byteCount, text=<redacted>)"
}

sealed interface GatewayTransportEvent {
    data class State(val value: GatewayTransportState) : GatewayTransportEvent
    data class Frame(val value: GatewayTransportFrame) : GatewayTransportEvent {
        override fun toString(): String = value.toString()
    }
}

/** 平台 transport 每次只拥有一个 socket，并保持单连接内帧的原始顺序。 */
interface GatewayTransport {
    val state: StateFlow<GatewayTransportState>
    /** frame 与 failure 必须共享同一有序事件流。 */
    val events: Flow<GatewayTransportEvent>

    suspend fun open(spec: GatewayConnectionSpec)
    suspend fun send(text: String)
    suspend fun close()
}

enum class GatewayNetworkState {
    AVAILABLE,
    UNAVAILABLE
}

interface GatewayNetworkMonitor {
    val state: StateFlow<GatewayNetworkState>
}

@kotlinx.serialization.Serializable
data class GatewayPreferencesSnapshot(
    val endpoint: String = "ws://127.0.0.1:3080/ws/mobile",
    val selectedWorkspaceId: String? = null,
    val sessionsJson: String? = null
)

/** 仅保存非敏感配置；token 与 device id 不得进入该存储。 */
interface GatewayPreferences {
    val snapshots: Flow<GatewayPreferencesSnapshot>

    suspend fun load(): GatewayPreferencesSnapshot
    suspend fun update(snapshot: GatewayPreferencesSnapshot)
}

/**
 * 凭据存储必须由平台安全设施保护。endpoint 可作为索引，token/device id 本身不得明文落盘。
 */
interface GatewayCredentialStore {
    suspend fun loadOrCreateDeviceId(): String
    suspend fun loadToken(endpoint: String): String?
    suspend fun saveToken(endpoint: String, token: String)
    suspend fun deleteToken(endpoint: String)
}

interface GatewayAttachmentCache {
    suspend fun read(attachmentId: String): ByteArray?
    /** 仅当内存和磁盘提交均成功时返回 true。 */
    suspend fun write(attachmentId: String, bytes: ByteArray): Boolean
    suspend fun removeExpired()
}

interface GatewayClock {
    fun nowEpochMilliseconds(): Long
    suspend fun delay(milliseconds: Long)
}
