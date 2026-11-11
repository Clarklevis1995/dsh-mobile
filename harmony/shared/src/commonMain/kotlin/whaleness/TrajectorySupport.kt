package whaleness

import com.clarklevis.dsh.shared.facade.SharedHistoryProjectionSnapshot
import com.clarklevis.dsh.shared.facade.SharedTrajectoryStore
import com.clarklevis.dsh.shared.projection.TrajectoryNode
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class HarmonyTrajectoryNode(
    val id: String,
    val kind: String,
    val title: String,
    val subtitle: String,
    val startSequence: Int,
    val endSequence: Int,
    val duration: Double,
    val live: Boolean,
    val model: String? = null,
    val provider: String? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
)

@Serializable
data class HarmonyTrajectory(
    val sessionId: String,
    val nodes: List<HarmonyTrajectoryNode> = emptyList(),
    val detail: String? = null,
    val detailId: String? = null,
)

/** 保存共享历史的不可变快照引用；只有轨迹可见并被请求时才构建投影。 */
internal class TrajectorySupport {
    private val sources = mutableMapOf<String, SharedHistoryProjectionSnapshot>()
    private var store = SharedTrajectoryStore()
    private var selected = ""
    private var projectedVersion = -1L
    private var projectedNodes = emptyList<TrajectoryNode>()

    fun received(snapshot: SharedHistoryProjectionSnapshot) {
        sources[snapshot.sessionId] = snapshot
    }

    fun remove(sessionId: String) {
        sources.remove(sessionId)
        if (selected == sessionId) {
            store.clearSession(sessionId)
            selected = ""
            projectedVersion = -1
            projectedNodes = emptyList()
        }
    }

    fun reset() {
        sources.clear()
        store = SharedTrajectoryStore()
        selected = ""
        projectedVersion = -1
        projectedNodes = emptyList()
    }

    fun read(
        sessionId: String,
        transient: List<TrajectoryNode>,
        detailId: String?,
    ): HarmonyTrajectory {
        if (selected != sessionId) {
            store = SharedTrajectoryStore()
            selected = sessionId
            projectedVersion = -1
        }
        val source = sources[sessionId]
        if (source != null && source.eventSequence != projectedVersion) {
            check(store.replaceSession(sessionId, source.eventsJson()).accepted) { "轨迹投影失败" }
            projectedVersion = source.eventSequence
        }
        projectedNodes = store.projectedNodes(sessionId) + transient
        if (detailId != null) {
            val node = requireNotNull(projectedNodes.firstOrNull { it.id == detailId }) { "节点已更新，请返回刷新" }
            val detail = if (node.records.isEmpty()) node.subtitle else wireJson.encodeToString(node.records)
            return HarmonyTrajectory(sessionId, detail = detail, detailId = detailId)
        }
        val transientIds = transient.map { it.id }.toSet()
        return HarmonyTrajectory(
            sessionId,
            projectedNodes.map { node ->
                HarmonyTrajectoryNode(
                    id = node.id,
                    kind = node.kind.name,
                    title = node.title,
                    subtitle = node.subtitle.take(500),
                    startSequence = node.startSequence,
                    endSequence = node.endSequence,
                    duration = (node.endEpochSeconds - node.startEpochSeconds).coerceAtLeast(0.0),
                    live = node.id in transientIds,
                    model = node.request?.model,
                    provider = node.request?.provider,
                    inputTokens = node.request?.usage?.totalInput,
                    outputTokens = node.request?.usage?.output,
                )
            },
        )
    }
}
