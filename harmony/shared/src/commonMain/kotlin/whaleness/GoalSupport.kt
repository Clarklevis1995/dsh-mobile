package whaleness

import com.clarklevis.dsh.shared.facade.SharedMobileStore
import com.clarklevis.dsh.shared.gateway.GatewayRequest
import com.clarklevis.dsh.shared.gateway.GatewayRequests
import com.clarklevis.dsh.shared.protocol.GatewayFrame
import com.clarklevis.dsh.shared.protocol.GatewayGoalSnapshot
import com.clarklevis.dsh.shared.protocol.GatewayTask
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class HarmonyGoalTasks(
    val sessionId: String? = null,
    val goalSupported: Boolean = false,
    val tasks: List<GatewayTask>? = null,
    val goal: GatewayGoalSnapshot? = null,
    val loading: Boolean = false,
    val mutation: String? = null,
    val uncertain: Boolean = false,
    val readOnly: Boolean = false,
    val error: String? = null,
)

internal data class GoalChange(
    val view: HarmonyGoalTasks,
    val requests: List<GatewayRequest> = emptyList(),
)

/** 任务与目标水位沿用 SharedMobileStore；平台不实现第二个投影。 */
internal class GoalSupport {
    private var store = SharedMobileStore()
    private var focus: String? = null
    private var supported = emptyList<String>()
    private var online = false
    private var readOnly = false
    private val reads = mutableMapOf<String, String>()
    private var mutation: Pair<String, String>? = null
    private val quarantined = mutableSetOf<String>()
    private var ordinal = 0L
    private var error: String? = null

    fun reset() {
        store = SharedMobileStore()
        focus = null
        reads.clear()
        mutation = null
        quarantined.clear()
        supported = emptyList()
        online =
            false
        error = null
    }

    fun rejected(message: String): GoalChange {
        error = message.take(120)
        return change()
    }

    fun pendingKeys(): List<String> = reads.values + listOfNotNull(mutation?.second)

    fun disconnected(): GoalChange {
        reads.clear()
        mutation = null
        quarantined.clear()
        online = false
        return change()
    }

    fun open(
        id: String?,
        connected: Boolean,
        capabilities: List<String>,
        child: Boolean,
    ): GoalChange {
        if (focus != id) {
            if (mutation != null) focus?.let(quarantined::add)
            store = SharedMobileStore()
            store.selectSession(id)
            reads.clear()
            mutation = null
        }
        focus = id
        online = connected
        supported = capabilities
        readOnly = child
        error = null
        val requests = mutableListOf<GatewayRequest>()
        if (id != null && connected) {
            if ("tasks" in supported && "tasks" !in reads) {
                reads["tasks"] = token()
                requests += GatewayRequests.tasks(id)
            }
            if ("goals" in supported && "goal" !in reads) {
                reads["goal"] = token()
                requests += GatewayRequests.goal(id)
            }
        }
        return change(requests)
    }

    fun command(command: HarmonyCommand): GoalChange {
        require(command.sessionId == focus && online) { "会话已切换或尚未连接" }
        if (command.op == "goal-refresh") return open(focus, online, supported, readOnly)
        require(
            !readOnly && "goals" in supported && reads["goal"] == null && mutation == null && focus !in quarantined,
        ) { "目标尚未确认，请重新连接后重试" }
        val goal =
            requireNotNull(
                store
                    .snapshot()
                    .goalSnapshot
                    ?.goal
                    ?.goal,
            ) { "当前目标已不存在" }
        require(
            goal.phase != "complete" && command.itemId == goal.id && command.draftRevision == goal.revision.toLong(),
        ) { "目标已更新，请重新打开后操作" }
        val kind = command.op
        val request =
            if (kind == "goal-edit") {
                val text = command.text.trim()
                require(text.isNotEmpty() && text.length <= 100000) { "目标内容长度无效" }
                GatewayRequests.editGoal(requireNotNull(focus), goal.ref, text)
            } else {
                GatewayRequests.goalAction(kind, requireNotNull(focus), goal.ref)
            }
        mutation = kind to token()
        error = null
        return change(listOf(request))
    }

    fun accept(
        raw: String,
        frame: GatewayFrame,
    ): GoalChange? {
        val id = focus ?: return null
        if (frame.kind == "projection-baseline" || frame.kind == "session-snapshot") {
            val projection =
                if (frame.kind ==
                    "projection-baseline"
                ) {
                    frame.projections?.objectValue?.get(id)
                } else {
                    frame.projections.takeIf { frame.sessionId == id }
                }
            if (projection != null) {
                val filtered =
                    buildJsonObject {
                        put("kind", "session-snapshot")
                        put("sessionId", id)
                        put("projections", projection.toJsonElement())
                    }
                store.acceptFrame(filtered.toString())
                return change()
            }
            return null
        }
        if (frame.sessionId != id) return null
        if (frame.kind in setOf("goal", "goal-updated", "tasks", "tasks-updated")) {
            store.acceptFrame(raw)
            if (frame.kind == "goal" || frame.kind == "tasks") reads.remove(frame.kind)
            return change()
        }
        val pending = mutation
        if (pending != null && (frame.kind == pending.first || (frame.kind == "error" && frame.requestType == pending.first))) {
            mutation = null
            if (frame.kind == "error") error = frame.message?.take(120) ?: "目标操作未完成"
            reads["goal"] = token()
            return change(listOf(GatewayRequests.goal(id)))
        }
        if (frame.kind == "error" && frame.requestType in reads) {
            reads.remove(frame.requestType)
            error = "目标或任务读取失败，请重试"
            return change()
        }
        return null
    }

    fun timedOut(key: String): GoalChange? {
        if (mutation?.second == key) {
            mutation = null
            focus?.let(quarantined::add)
            error = "目标操作结果尚未确认，请重新连接后核对"
            val id = focus ?: return change()
            reads["goal"] = token()
            return change(listOf(GatewayRequests.goal(id)))
        }
        val kind = reads.entries.firstOrNull { it.value == key }?.key ?: return null
        reads.remove(kind)
        error = "目标或任务读取超时，请重试"
        return change()
    }

    private fun token(): String = "goal-io:${++ordinal}"

    private fun change(requests: List<GatewayRequest> = emptyList()): GoalChange {
        val snapshot = store.snapshot()
        return GoalChange(
            HarmonyGoalTasks(
                focus,
                "goals" in supported,
                snapshot.taskSnapshot?.tasks,
                snapshot.goalSnapshot?.goal,
                reads.isNotEmpty(),
                mutation?.first,
                focus in quarantined,
                readOnly,
                error,
            ),
            requests,
        )
    }
}
