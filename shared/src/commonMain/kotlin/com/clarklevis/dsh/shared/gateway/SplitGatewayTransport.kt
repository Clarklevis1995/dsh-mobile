package com.clarklevis.dsh.shared.gateway

import com.clarklevis.dsh.shared.platform.GatewayConnectionSpec
import com.clarklevis.dsh.shared.platform.GatewaySplitTransport
import com.clarklevis.dsh.shared.platform.GatewayTransport
import com.clarklevis.dsh.shared.platform.GatewayTransportEvent
import com.clarklevis.dsh.shared.platform.GatewayTransportState
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString

/** 配对只在控制连接完成；对话连接复用长期凭据，旧网关仍使用单连接。 */
class SplitGatewayTransport(
    private val control: GatewayTransport,
    private val conversation: GatewayTransport
) : GatewaySplitTransport {
    private var spec: GatewayConnectionSpec? = null
    private var split = false
    private var receivedHello = false
    private var conversationReady = false

    override val state = control.state
    override val events: Flow<GatewayTransportEvent> = control.events.transform { event ->
        if (event is GatewayTransportEvent.Frame && event.value.generation == spec?.generation && !receivedHello) {
            val header = runCatching { wireJson.decodeFromString<Header>(event.value.text) }.getOrNull()
            if (header == null) {
                emit(event)
                return@transform
            }
            if (header.kind == "paired") spec = spec?.copy(bearerToken = header.token, pairingCode = null)
            if (header.kind == "hello") {
                receivedHello = true
                split = "split-channels" in header.capabilities
                if (split) {
                    try {
                        conversation.open(requireNotNull(spec).copy(channel = "conversation", pairingCode = null))
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        emit(GatewayTransportEvent.State(GatewayTransportState.Failed(
                            generation = event.value.generation, reason = "conversation-open-failed"
                        )))
                        return@transform
                    }
                }
            }
        }
        emit(event)
    }

    override val conversationEvents: Flow<GatewayTransportEvent> = conversation.events.transform { event ->
        val generation = when (event) {
            is GatewayTransportEvent.Frame -> event.value.generation
            is GatewayTransportEvent.State -> event.value.generation
        }
        if (!split || generation != spec?.generation) return@transform
        when (event) {
            is GatewayTransportEvent.Frame -> {
                if (!conversationReady) {
                    val header = runCatching { wireJson.decodeFromString<Header>(event.value.text) }.getOrNull()
                    if (header?.kind != "hello" || "split-channels" !in header.capabilities) {
                        emit(GatewayTransportEvent.State(GatewayTransportState.Failed(
                            generation = generation, reason = "conversation-handshake-failed"
                        )))
                        return@transform
                    }
                    conversationReady = true
                } else emit(event)
            }
            is GatewayTransportEvent.State -> {
                if (event.value is GatewayTransportState.Failed) {
                    conversationReady = false
                    emit(event)
                }
            }
        }
    }

    override suspend fun open(spec: GatewayConnectionSpec) {
        conversationReady = false
        this.spec = spec
        split = false
        receivedHello = false
        conversation.close()
        control.open(spec.copy(channel = "control"))
    }

    override suspend fun send(text: String) {
        val type = wireJson.decodeFromString<Header>(text).type
        if (split && type in CONVERSATION_REQUESTS) {
            // 平台 socket 会在握手完成前缓冲 send；此处不能等待对话 hello，
            // 否则调用方 Runtime 的发送锁会再次阻塞控制线路。
            conversation.send(text)
        } else control.send(text)
    }

    override suspend fun close() {
        split = false
        spec = null
        conversationReady = false
        conversation.close()
        control.close()
    }

    @Serializable
    private data class Header(
        val kind: String? = null,
        val type: String? = null,
        val token: String? = null,
        val capabilities: List<String> = emptyList()
    )

    companion object {
        private val CONVERSATION_REQUESTS = setOf("message", "history", "subscribe", "unsubscribe")
    }
}
