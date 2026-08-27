package com.clarklevis.dsh.shared.facade

import com.clarklevis.dsh.shared.projection.TrajectoryNode
import com.clarklevis.dsh.shared.projection.TrajectoryProjection
import com.clarklevis.dsh.shared.protocol.SessionEvent
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
data class SharedTrajectoryBootstrap(val schema: Int = 1)

/** 节点级高频 patch：流式 subtitle 与 records 只携带本次增量。 */
@Serializable
data class SharedTrajectoryOperation(
    val kind: String,
    val item: TrajectoryNode? = null,
    val itemId: String? = null,
    val index: Int? = null,
    val subtitleDelta: String? = null,
    val endSequence: Int? = null,
    val endEpochSeconds: Double? = null,
    val appendedRecords: List<SessionEvent> = emptyList()
)

@Serializable
data class SharedTrajectoryPatch(
    val schema: Int = 1,
    val sessionId: String,
    val operations: List<SharedTrajectoryOperation> = emptyList(),
    val replacesAll: Boolean = false,
    val replacementNodes: List<TrajectoryNode>? = null,
    val lastSequence: Int = -1
)

/**
 * Trajectory 只在平台声明页面活跃时接收事件。KMP 持有原始记录与唯一投影，
 * Swift 订阅节点 patch；离开页面后平台停止 dispatch，重新进入时显式 replace。
 */
class SharedTrajectoryStore {
    private val eventsBySession = mutableMapOf<String, List<SessionEvent>>()
    private val nodesBySession = mutableMapOf<String, List<TrajectoryNode>>()
    private val events = SharedMviEventEmitter("trajectory")

    fun subscribe(observer: SharedMviEventObserver): SharedMviSubscription = try {
        events.subscribe(observer, wireJson.encodeToString(SharedTrajectoryBootstrap()))
    } catch (error: Throwable) {
        events.subscribeError(
            observer,
            "trajectory-subscribe-failed",
            error.message ?: error::class.simpleName ?: "unknown-error"
        )
    }

    fun receiveEvent(eventJson: String): SharedMviDispatchResult = dispatch("event") {
        appendRecords(listOf(wireJson.decodeFromString<SessionEvent>(eventJson)))
    }

    /** 平台按 display cadence 批量提交 burst token，KMP 每帧最多重算一次。 */
    fun receiveEvents(eventsJson: String): SharedMviDispatchResult = dispatch("events") {
        appendRecords(wireJson.decodeFromString<List<SessionEvent>>(eventsJson))
    }

    fun replaceSession(sessionId: String, eventsJson: String): SharedMviDispatchResult =
        dispatch("replace") {
            require(sessionId.isNotBlank()) { "sessionId must not be blank" }
            val decoded = wireJson.decodeFromString<List<SessionEvent>>(eventsJson)
            require(decoded.all { it.sessionId == sessionId }) { "baseline contains another session" }
            val normalized = decoded.associateBy(SessionEvent::seq).values.sortedBy(SessionEvent::seq)
            val nodes = TrajectoryProjection.make(normalized)
            val patch = SharedTrajectoryPatch(
                sessionId = sessionId,
                replacesAll = true,
                replacementNodes = nodes,
                lastSequence = normalized.lastOrNull()?.seq ?: -1
            )
            val payload = wireJson.encodeToString(patch)
            eventsBySession[sessionId] = normalized
            nodesBySession[sessionId] = nodes
            payload
        }

    fun clearSession(sessionId: String): SharedMviDispatchResult = dispatch("clear") {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        val patch = SharedTrajectoryPatch(
            sessionId = sessionId,
            replacesAll = true,
            replacementNodes = emptyList(),
            lastSequence = -1
        )
        val payload = wireJson.encodeToString(patch)
        eventsBySession.remove(sessionId)
        nodesBySession.remove(sessionId)
        payload
    }

    private fun appendRecords(records: List<SessionEvent>): String? {
        require(records.isNotEmpty()) { "event batch must not be empty" }
        val sessionId = records.first().sessionId
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(records.all { it.sessionId == sessionId }) { "event batch contains another session" }
        require(records.zipWithNext().all { (left, right) -> left.seq < right.seq }) {
            "event batch must be strictly sequence-ascending"
        }
        val oldEvents = eventsBySession[sessionId].orEmpty()
        require(records.first().seq > (oldEvents.lastOrNull()?.seq ?: -1)) {
            "event sequence ${records.first().seq} is not append-only; replace baseline first"
        }
        return transition(sessionId, oldEvents + records)
    }

    private fun transition(sessionId: String, nextEvents: List<SessionEvent>): String? {
        val oldNodes = nodesBySession[sessionId].orEmpty()
        val nextNodes = TrajectoryProjection.make(nextEvents)
        val operations = diff(oldNodes, nextNodes)
        if (operations.isEmpty()) {
            eventsBySession[sessionId] = nextEvents
            nodesBySession[sessionId] = nextNodes
            return null
        }
        val patch = SharedTrajectoryPatch(
            sessionId = sessionId,
            operations = operations,
            lastSequence = nextEvents.lastOrNull()?.seq ?: -1
        )
        val payload = wireJson.encodeToString(patch)
        eventsBySession[sessionId] = nextEvents
        nodesBySession[sessionId] = nextNodes
        return payload
    }

    private fun diff(
        oldNodes: List<TrajectoryNode>,
        nextNodes: List<TrajectoryNode>
    ): List<SharedTrajectoryOperation> {
        val operations = mutableListOf<SharedTrajectoryOperation>()
        val nextById = nextNodes.associateBy(TrajectoryNode::id)
        val currentOrder = oldNodes.map(TrajectoryNode::id).toMutableList()
        oldNodes.asReversed().forEach { old ->
            if (old.id !in nextById) {
                operations += SharedTrajectoryOperation(kind = "remove", itemId = old.id)
                currentOrder.remove(old.id)
            }
        }
        val oldById = oldNodes.associateBy(TrajectoryNode::id)
        nextNodes.forEachIndexed { targetIndex, next ->
            val currentIndex = currentOrder.indexOf(next.id)
            if (currentIndex < 0) {
                operations += SharedTrajectoryOperation(kind = "insert", item = next, index = targetIndex)
                currentOrder.add(targetIndex, next.id)
            } else {
                if (currentIndex != targetIndex) {
                    operations += SharedTrajectoryOperation(kind = "move", itemId = next.id, index = targetIndex)
                    currentOrder.removeAt(currentIndex)
                    currentOrder.add(targetIndex, next.id)
                }
                val old = oldById.getValue(next.id)
                if (old != next) operations += updateOperation(old, next)
            }
        }
        return operations
    }

    private fun updateOperation(
        old: TrajectoryNode,
        next: TrajectoryNode
    ): SharedTrajectoryOperation {
        val canAppend = old.id == next.id &&
            old.kind == next.kind &&
            old.title == next.title &&
            old.startSequence == next.startSequence &&
            old.startEpochSeconds == next.startEpochSeconds &&
            old.request == next.request &&
            old.tool == next.tool &&
            next.subtitle.startsWith(old.subtitle) &&
            next.records.size >= old.records.size &&
            next.records.take(old.records.size) == old.records
        if (!canAppend) return SharedTrajectoryOperation(kind = "replace", item = next, itemId = next.id)
        return SharedTrajectoryOperation(
            kind = "update",
            itemId = next.id,
            subtitleDelta = next.subtitle.removePrefix(old.subtitle),
            endSequence = next.endSequence,
            endEpochSeconds = next.endEpochSeconds,
            appendedRecords = next.records.drop(old.records.size)
        )
    }

    private inline fun dispatch(
        operation: String,
        block: () -> String?
    ): SharedMviDispatchResult = try {
        val payload = block()
        if (payload == null) {
            SharedMviDispatchResult(true, null, null)
        } else {
            val event = events.emitTransition(
                transactionId = "trajectory-$operation:${events.currentSequence + 1}",
                statePayloadJson = payload
            )
            SharedMviDispatchResult(true, event.transactionId, event.sequence)
        }
    } catch (error: Throwable) {
        val code = "trajectory-$operation-failed"
        val message = error.message ?: error::class.simpleName ?: "unknown-error"
        val event = events.emitError("$code:${events.currentSequence + 1}", code, message)
        SharedMviDispatchResult(false, event.transactionId, event.sequence, code, message)
    }
}
