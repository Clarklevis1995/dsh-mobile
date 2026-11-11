package whaleness

import com.clarklevis.dsh.shared.facade.SharedTeamStore
import com.clarklevis.dsh.shared.facade.TeamMemberProfileRequest
import com.clarklevis.dsh.shared.facade.TeamSnapshot
import com.clarklevis.dsh.shared.gateway.GatewayRequest
import com.clarklevis.dsh.shared.protocol.GatewayFrame
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Serializable
data class HarmonyTeam(
    val lead: TeamSnapshot = TeamSnapshot(),
    val connected: Boolean = false,
)

internal data class TeamChange(
    val view: HarmonyTeam? = null,
    val requests: List<GatewayRequest> = emptyList(),
)

/** 原生层仅负责请求生命周期，成员、任务水位和子会话地址均由共享 Store 管理。 */
internal class TeamSupport {
    private var store = SharedTeamStore()
    private var lead: String? = null
    private var connected = false
    private var published: HarmonyTeam? = null
    private val pending = mutableMapOf<String, GatewayRequest>()

    fun reset() {
        store = SharedTeamStore()
        lead = null
        connected = false
        published = null
        pending.clear()
    }

    fun disconnected(): TeamChange {
        connected = false
        pending.clear()
        return changed()
    }

    fun sessionsRequested() = store.sessionsRequested()

    fun pendingKeys(): List<String> = pending.keys.map { "team:$it" }

    fun isChild(id: String?): Boolean = id != null && store.isChild(id)

    fun canSend(id: String?): Boolean = id != null && store.canSend(id)

    fun routing(): String = store.routingJson()

    fun avatars(): String = store.avatarAssignmentsJson()

    fun restore(
        targets: String?,
        avatars: String?,
    ) {
        targets?.let(store::restoreRoutingJson)
        avatars?.let(store::restoreAvatarAssignmentsJson)
    }

    fun workspaceSession(id: String): String = store.workspaceSessionId(id)

    fun decorate(payload: String): String? = store.decorateRequestJson(payload)

    fun child(id: String): Boolean =
        connected && lead?.let { store.snapshot(it).members.any { row -> row.id == id && row.id != it && row.canOpen } } == true

    fun open(
        id: String,
        refresh: Boolean,
    ): TeamChange {
        require(connected && !store.isChild(id)) { "连接主会话后才能读取团队" }
        lead = id
        val requests =
            listOf("agent-team", "subagents").mapNotNull { kind ->
                if (refresh && pending.values.any { it.targetSessionId == id && it.requestType == kind }) {
                    null
                } else {
                    (if (refresh) store.queryJson(id, kind) else store.queryIfNeededJson(id, kind))?.let { register(it) }
                }
            }
        return changed(requests + profiles())
    }

    fun accept(
        raw: String,
        frame: GatewayFrame,
        handshake: Boolean = false,
    ): TeamChange {
        if (frame.kind !in
            setOf(
                "hello",
                "session-status",
                "sessions",
                "projection-baseline",
                "agent-team",
                "subagents",
                "agent-team-updated",
                "subagents-updated",
                "subagent-identity-updated",
                "subagent-timing-updated",
                "session-snapshot",
                "history",
                "error",
            )
        ) {
            return TeamChange()
        }
        val profile = frame.requestId?.startsWith("team-profile-") == true
        if (handshake) {
            connected = true
            pending.clear()
        }
        var released = false
        val changed = store.acceptFrameJson(raw)
        if (profile || frame.kind in setOf("agent-team", "subagents", "error")) {
            val request = pending[frame.requestId]
            if (request != null &&
                frame.sessionId == request.targetSessionId &&
                (frame.kind == request.responseKind || (frame.kind == "error" && frame.requestType == request.requestType))
            ) {
                pending.remove(frame.requestId)
                released = true
            }
        }
        if (handshake && lead != null) return open(requireNotNull(lead), true)
        return if (changed || released) changed(profiles()) else TeamChange()
    }

    fun timedOut(key: String): TeamChange {
        val id = key.removePrefix("team:")
        val request = pending.remove(id) ?: return TeamChange()
        if (store.memberProfileRequestPending(id)) {
            store.finishMemberProfileRequest(id)
        } else {
            store.acceptFrameJson(
                buildJsonObject {
                    put("kind", "error")
                    put("requestType", request.requestType)
                    put("requestId", id)
                    put("sessionId", request.targetSessionId)
                    put("message", "团队读取超时，请刷新后重试")
                }.toString(),
            )
        }
        return changed(profiles())
    }

    private fun profiles(): List<GatewayRequest> =
        lead
            ?.let { id ->
                wireJson
                    .decodeFromString<List<TeamMemberProfileRequest>>(
                        store.memberProfileRequestsJson(id),
                    ).map { register(it.payload, true) }
            }.orEmpty()

    private fun register(
        payload: String,
        profile: Boolean = false,
    ): GatewayRequest {
        val row = wireJson.parseToJsonElement(payload).jsonObject
        val kind = row.getValue("type").jsonPrimitive.content
        val request =
            GatewayRequest(
                kind,
                if (profile) "history" else kind,
                row["sessionId"]?.jsonPrimitive?.content,
                row["requestId"]?.jsonPrimitive?.content,
                payload = payload,
                isBackgroundHistory = profile,
            )
        pending[requireNotNull(request.correlationId)] = request
        return request
    }

    private fun changed(requests: List<GatewayRequest> = emptyList()): TeamChange {
        val view = lead?.let { HarmonyTeam(store.snapshot(it), connected) }
        val update = view?.takeIf { it != published }
        published = view
        return TeamChange(update, requests)
    }
}
