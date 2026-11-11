package whaleness

import com.clarklevis.dsh.shared.facade.SharedQueueStore
import com.clarklevis.dsh.shared.facade.SharedSessionAgentPresetStore
import com.clarklevis.dsh.shared.facade.SharedSessionAgentPresetTransition
import com.clarklevis.dsh.shared.gateway.GatewayRequest
import com.clarklevis.dsh.shared.i18n.UiMessage
import com.clarklevis.dsh.shared.protocol.GatewayAgentPreset
import com.clarklevis.dsh.shared.protocol.GatewayFrame
import kotlinx.serialization.Serializable

@Serializable
data class HarmonyAgent(
    val supported: Boolean = false,
    val preset: String? = null,
    val locked: Boolean = false,
    val known: Boolean = false,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val canSelect: Boolean = false,
    val blocksSending: Boolean = false,
    val presets: List<GatewayAgentPreset> = emptyList(),
)

@Serializable
data class HarmonyQueuedItem(
    val id: String,
    val preview: UiMessage,
    val attachmentCount: Int,
    val editable: Boolean,
)

@Serializable
data class HarmonySessionFeatures(
    val sessionId: String? = null,
    val agent: HarmonyAgent = HarmonyAgent(),
    val queueSupported: Boolean = false,
    val queue: List<HarmonyQueuedItem> = emptyList(),
    val pendingQueueItem: String? = null,
    val pendingQueueSession: String? = null,
    val queueUncertain: Boolean = false,
    val queueError: UiMessage? = null,
    val error: UiMessage? = null,
)

internal data class FeatureChange(
    val view: HarmonySessionFeatures? = null,
    val requests: List<GatewayRequest> = emptyList(),
    val restoredDrafts: Map<String, String> = emptyMap(),
    val refreshCommands: Boolean = false,
    val invalidSession: Boolean = false,
)

/** 只做共享状态机到平台视图／效果的映射；草稿回填必须等待 Host 的成功回执。 */
internal class SessionFeatures {
    private var agent = SharedSessionAgentPresetStore()
    private val queue = SharedQueueStore()
    private var focus: String? = null
    private var agentSupported = false
    private var queueSupported = false
    private var published: HarmonySessionFeatures? = null
    private var lastError: UiMessage? = null
    private var catalogError: String? = null
    private val agentRequests = mutableMapOf<String, GatewayRequest>()
    private var ordinal = 0L
    private var queueKey: String? = null
    private var queueSession: String? = null
    private var queueUncertain = false

    fun reset() {
        agent = SharedSessionAgentPresetStore()
        queue.reset()
        focus = null
        agentSupported = false
        queueSupported = false
        published = null
        lastError = null
        catalogError = null
        agentRequests.clear()
        queueKey = null
        queueSession = null
        queueUncertain =
            false
    }

    fun configure(capabilities: List<String>): FeatureChange {
        agentSupported = "session-agent-preset" in capabilities
        queueSupported = "queue-control" in capabilities
        return change()
    }

    fun open(
        sessionId: String?,
        capabilities: List<String>,
        connected: Boolean,
    ): FeatureChange {
        focus = sessionId
        agentSupported = "session-agent-preset" in capabilities
        queueSupported = "queue-control" in capabilities
        lastError = null
        catalogError = null
        agentRequests.clear()
        return agentChanged(agent.open(sessionId, agentSupported, connected))
    }

    fun disconnected(): FeatureChange {
        agentRequests.clear()
        queueKey = null
        queueSession = null
        queueUncertain = false
        queue.resetConnection()
        return agentChanged(agent.disconnected())
    }

    fun beforeMessage(sessionId: String): FeatureChange {
        check(!agentSupported || (agent.snapshot().sessionId == sessionId && !agent.snapshot().blocksSending)) { "Agent 状态尚未确认，请刷新会话设置后重试" }
        return agentChanged(agent.beginMessage())
    }

    fun messageFailed(sessionId: String?): FeatureChange =
        agentChanged(agent.requestFailed("message", sessionId, null, UiMessage.raw("消息结果尚未确认")))

    fun selectAgent(
        sessionId: String,
        preset: String,
        connected: Boolean,
    ): FeatureChange {
        require(connected && agentSupported && focus == sessionId) { "当前无法切换会话 Agent" }
        check(agent.snapshot().canSelect) { "当前会话 Agent 已锁定或尚未确认" }
        lastError = null
        return agentChanged(agent.select(preset))
    }

    fun queueAction(
        sessionId: String,
        item: String,
        action: String,
        connected: Boolean,
    ): FeatureChange {
        require(connected && queueSupported && focus == sessionId) { "主机未开放队列操作" }
        check(!queueUncertain) { "上次队列操作未确认，请重新连接后重试" }
        val request = requireNotNull(queue.beginAction(sessionId, item, action)) { "该队列消息当前不可操作" }
        queueSession = sessionId
        queueKey = "features:queue:${++ordinal}"
        return change(listOf(request))
    }

    fun pendingKeys(): List<String> = agentRequests.keys.toList() + listOfNotNull(queueKey)

    fun timedOut(key: String): FeatureChange {
        if (queueKey == key) {
            queueKey = null
            queueUncertain = true
            queue.failPending(UiMessage.raw("队列操作超时，结果未确认，请重新连接"))
            return change()
        }
        val request = agentRequests.remove(key) ?: return FeatureChange()
        if (request.requestType == "agent-presets") {
            catalogError = "读取 Agent 预设超时，请刷新"
            return change()
        }
        return agentChanged(
            agent.requestFailed(request.requestType, request.targetSessionId, request.correlationId, UiMessage.raw("Agent 请求超时，请刷新")),
        )
    }

    fun accept(
        frame: GatewayFrame,
        raw: String,
        control: Boolean,
    ): FeatureChange {
        if (frame.resetRequired == true) return FeatureChange()
        val requests = mutableListOf<GatewayRequest>()
        var refreshCommands = false
        var invalidSession = false
        if (agentSupported &&
            frame.kind in
            setOf(
                "agent-presets",
                "session-agent-preset",
                "select-agent-preset",
                "session-agent-preset-updated",
                "sent",
                "command-executed",
                "error",
            )
        ) {
            if (control || frame.kind in setOf("sent", "command-executed", "error")) {
                val matched =
                    agentRequests.values.any {
                        frame.requestId != null &&
                            frame.requestId == it.correlationId &&
                            frame.sessionId == it.targetSessionId
                    }
                if (frame.kind == "agent-presets" && frame.presets != null) catalogError = null
                agentRequests.entries.removeAll { (_, request) ->
                    (frame.kind == "agent-presets" && request.requestType == "agent-presets") ||
                        (frame.sessionId == request.targetSessionId && frame.requestId != null && frame.requestId == request.correlationId)
                }
                val transition = agent.accept(frame)
                trackAgent(transition)
                requests += transition.requests
                refreshCommands = refreshCommands || transition.refreshCommands
                invalidSession =
                    invalidSession ||
                    transition.invalidSession
                if (transition.error != null) {
                    lastError = transition.error
                } else if (matched &&
                    frame.kind in setOf("session-agent-preset", "select-agent-preset") &&
                    transition.snapshot.known
                ) {
                    lastError = null
                }
            }
        }
        var drafts = emptyMap<String, String>()
        if (control &&
            queueSupported &&
            (
                frame.kind in setOf("session-queues", "session-queue", "queue-item-updated") ||
                    (frame.kind == "error" && frame.requestType == "queue-update")
            )
        ) {
            val pending = queue.snapshot().pendingItemId
            queue.acceptFrame(raw)
            if (pending != null && queue.snapshot().pendingItemId == null) {
                queueKey = null
                queueSession?.let { session -> queue.takeDraft(session)?.let { drafts = mapOf(session to it) } }
                queueSession = null
            }
        }
        return change(requests, drafts).copy(refreshCommands = refreshCommands, invalidSession = invalidSession)
    }

    private fun agentChanged(transition: SharedSessionAgentPresetTransition): FeatureChange {
        if (transition.error != null) lastError = transition.error
        trackAgent(transition)
        return change(transition.requests).copy(refreshCommands = transition.refreshCommands, invalidSession = transition.invalidSession)
    }

    private fun trackAgent(transition: SharedSessionAgentPresetTransition) {
        if (!transition.snapshot.loading) agentRequests.entries.removeAll { it.value.requestType == "session-agent-preset" }
        if (!transition.snapshot.saving) agentRequests.entries.removeAll { it.value.requestType == "select-agent-preset" }
        transition.requests.forEach { request ->
            agentRequests["features:agent:${request.correlationId ?: "catalog-${++ordinal}"}"] =
                request
        }
    }

    private fun change(
        requests: List<GatewayRequest> = emptyList(),
        drafts: Map<String, String> = emptyMap(),
    ): FeatureChange {
        val a = agent.snapshot()
        val q = queue.snapshot()
        val value =
            HarmonySessionFeatures(
                focus,
                HarmonyAgent(a.supported, a.agentPreset, a.locked, a.known, a.loading, a.saving, a.canSelect, a.blocksSending, a.presets),
                queueSupported,
                q.queues[focus].orEmpty().map {
                    HarmonyQueuedItem(
                        it.id,
                        it.preview.let { text ->
                            if (text.key ==
                                UiMessage.RAW_KEY
                            ) {
                                text.copy(args = text.args.map { arg -> arg.take(240) })
                            } else {
                                text
                            }
                        },
                        it.attachmentCount,
                        it.editable,
                    )
                },
                q.pendingItemId,
                queueSession,
                queueUncertain,
                q.lastError ?: UiMessage.raw("上次队列操作未确认，请重新连接").takeIf {
                    queueUncertain
                },
                lastError ?: catalogError?.let(UiMessage::raw),
            )
        val changed = value != published
        published = value
        return FeatureChange(value.takeIf { changed }, requests, drafts)
    }
}
