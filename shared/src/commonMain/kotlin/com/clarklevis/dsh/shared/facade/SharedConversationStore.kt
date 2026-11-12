package com.clarklevis.dsh.shared.facade

import com.clarklevis.dsh.shared.cache.ChatCacheMemoryEstimator
import com.clarklevis.dsh.shared.cache.chatCacheLog
import com.clarklevis.dsh.shared.projection.ConversationItem
import com.clarklevis.dsh.shared.projection.ConversationProjectionLabels
import com.clarklevis.dsh.shared.projection.ConversationProjectionOperation
import com.clarklevis.dsh.shared.projection.ConversationProjector
import com.clarklevis.dsh.shared.protocol.JsonValue
import com.clarklevis.dsh.shared.protocol.RawSessionEvent
import com.clarklevis.dsh.shared.protocol.SessionEvent
import com.clarklevis.dsh.shared.protocol.wireJson
import com.clarklevis.dsh.shared.sync.AssistantChunk
import com.clarklevis.dsh.shared.platform.platformNowEpochMilliseconds
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
data class SharedConversationBootstrap(val schema: Int = 1)

/**
 * 高频 Conversation 专用 patch。live token 只发送有序 operation；历史基线才使用
 * replacementItems，禁止复用低频 SessionControl 的字典快照协议。
 */
@Serializable
data class SharedConversationPatch(
    val schema: Int = 1,
    val sessionId: String,
    val operations: List<ConversationProjectionOperation> = emptyList(),
    val replacesAll: Boolean = false,
    val replacementItems: List<ConversationItem>? = null,
    val lastSequence: Int = -1
)

/** 只包含不可变输入，prepare 在 worker 中创建并独占全新的 projector。 */
class SharedConversationHistoryPreparation internal constructor(
    private val snapshot: SharedHistoryProjectionSnapshot,
    private val labels: ConversationProjectionLabels,
    private val steering: List<SessionEvent>,
    private val revision: Long,
    private val owner: Any,
    private val previousItems: List<ConversationItem>?
) {
    @Throws(Exception::class)
    fun prepare(): SharedPreparedConversationHistory {
        val projector = ConversationProjector(labels).apply { rebuild(snapshot.records) }
        projector.replaceSteeringMessages(steering)
        val patch = conversationHistoryPatch(snapshot.sessionId, previousItems, projector.items, projector.lastSequence)
        chatCacheLog(
            "projection-prepared",
            "events=${snapshot.records.size} replacesAll=${patch.replacesAll} operations=${patch.operations.size}"
        )
        return SharedPreparedConversationHistory(
            snapshot.sessionId,
            snapshot.eventSequence,
            revision,
            owner,
            projector,
            snapshot.records,
            wireJson.encodeToString(patch),
            patch,
        )
    }
}

/** prepare 返回后 worker 不再访问 projector；仅成功提交的 owner 可以接管。 */
class SharedPreparedConversationHistory internal constructor(
    val sessionId: String,
    val historyEventSequence: Long,
    internal val revision: Long,
    internal val owner: Any,
    internal val projector: ConversationProjector,
    internal val records: List<SessionEvent>,
    internal val payload: String,
    internal val patch: SharedConversationPatch,
)

/** KMP 持有逐 session projector；平台只 dispatch 原始事件并订阅增量 UI patch。 */
class SharedConversationStore(
    private val labels: ConversationProjectionLabels = ConversationProjectionLabels()
) {
    private val projectors = mutableMapOf<String, ConversationProjector>()
    private val projectionRecords = mutableMapOf<String, MutableList<SessionEvent>>()
    private val steeringRecords = mutableMapOf<String, List<SessionEvent>>()
    private val events = SharedMviEventEmitter("conversation")
    private val patchObservers = SharedMviPatchObservers<SharedConversationPatch>()
    private val mutationRevisions = mutableMapOf<String, Long>()
    private val preparationOwner = Any()

    internal fun subscribePatches(observer: (SharedMviEvent, SharedConversationPatch) -> Unit): SharedMviSubscription =
        patchObservers.subscribe(events.currentSequence, observer)

    private fun needsWirePayload(): Boolean = events.hasObservers || !patchObservers.hasObservers

    fun subscribe(observer: SharedMviEventObserver): SharedMviSubscription = try {
        events.subscribe(observer, wireJson.encodeToString(SharedConversationBootstrap()))
    } catch (error: Throwable) {
        events.subscribeError(
            observer,
            "conversation-subscribe-failed",
            error.message ?: error::class.simpleName ?: "unknown-error"
        )
    }

    fun receiveEvent(eventJson: String): SharedMviDispatchResult =
        receiveRecord {
            wireJson.decodeFromString<SessionEvent>(eventJson)
        }

    internal fun receiveTypedEvent(record: SessionEvent): SharedMviDispatchResult = receiveRecord { record }

    private fun receiveRecord(recordSource: () -> SessionEvent): SharedMviDispatchResult =
        dispatch("event") {
            val record = recordSource()
            require(record.sessionId.isNotBlank()) { "sessionId must not be blank" }
            invalidatePreparation(record.sessionId)
            val projector = projectors.getOrPut(record.sessionId) { ConversationProjector(labels) }
            require(record.seq > projector.lastSequence) {
                "event sequence ${record.seq} is not newer than ${projector.lastSequence}; replace baseline first"
            }
            val operations = projector.foldWithOperations(listOf(record))
            projectionRecords[record.sessionId]?.add(record)
            // 不可见持久事件仍会推进流水位，必须发布给平台镜像。
            // 否则后续独立流携带正确水位时，iOS 会因镜像滞后而误判协议错误。
            SharedConversationPatch(
                sessionId = record.sessionId,
                operations = operations,
                lastSequence = projector.lastSequence
            )
        }

    /**
     * 单个 streaming 增量。DSH 的 `session.seq` 是 turn 级 journal watermark，同一 turn
     * 内的所有 chunk 共享同一个 seq，因此 [receiveEvent] 的单调性守卫在这里并不适用。
     *
     * 该路径只做行内变更：projector 用 per-key stream index 定位目标行，既不重建历史，
     * 也不重新序列化整个事件列表。缺少已建立基线的 projector 时 fail closed——
     * 平台据此回退到 [replaceSession]，绝不静默丢弃内容。
     */
    fun receiveStreamDelta(eventJson: String): SharedMviDispatchResult =
        receiveStreamRecord {
            wireJson.decodeFromString<SessionEvent>(eventJson)
        }

    internal fun receiveTypedStreamDelta(record: SessionEvent): SharedMviDispatchResult = receiveStreamRecord { record }

    private fun receiveStreamRecord(recordSource: () -> SessionEvent): SharedMviDispatchResult =
        dispatch("stream") {
            val record = recordSource()
            require(record.sessionId.isNotBlank()) { "sessionId must not be blank" }
            invalidatePreparation(record.sessionId)
            val projector = projectors[record.sessionId]
                ?: error("no conversation baseline for session ${record.sessionId}; replace baseline first")
            val previousSequence = projector.lastSequence
            // 同 seq 流式变更不能证明旧基线仍是持久历史的严格前缀。
            projectionRecords.remove(record.sessionId)
            val operations = projector.foldWithOperations(listOf(record))
            if (operations.isEmpty() && projector.lastSequence == previousSequence) return@dispatch null
            SharedConversationPatch(
                sessionId = record.sessionId,
                operations = operations,
                lastSequence = projector.lastSequence
            )
        }

    /** 平台用它决定能否走 streaming 行内路径，否则回退到 baseline。 */
    /** 只读附件引用查询，不复制会话投影。 */
    fun projectedItems(sessionId: String): List<ConversationItem> = projectors[sessionId]?.items.orEmpty()

    fun hasProjection(sessionId: String): Boolean = projectors.containsKey(sessionId)

    fun hasAssistantChunks(sessionId: String): Boolean = projectors[sessionId]?.hasAssistantChunks == true

    /** 只接受完整相等的前缀；历史修订、删除、裁窗和向前补页保留重新校验路径。 */
    fun appendHistorySnapshot(sessionId: String, snapshot: SharedHistoryProjectionSnapshot): SharedMviDispatchResult? {
        if (sessionId != snapshot.sessionId) return null
        val previous = projectionRecords[sessionId] ?: return null
        val projector = projectors[sessionId] ?: return null
        val records = snapshot.records
        if (records.any { it.sessionId != sessionId || !it.time.isFinite() } ||
            records.zipWithNext().any { (left, right) -> left.seq >= right.seq }
        ) {
            return null
        }
        if (projector.hasAssistantChunks ||
            previous.size > records.size ||
            previous.indices.any { previous[it] != records[it] } ||
            records.asSequence().drop(previous.size).any { it.seq <= projector.lastSequence }
        ) {
            return null
        }
        val added = records.subList(previous.size, records.size)
        // 大段补回和超大单条仍交给后台 worker，避免增量路径反而阻塞主线程。
        if (added.size > MAXIMUM_INLINE_EVENTS ||
            added.sumOf(ChatCacheMemoryEstimator::contentLowerBound) > MAXIMUM_INLINE_CONTENT_BYTES
        ) {
            return null
        }
        return dispatch("history-append") {
            invalidatePreparation(sessionId)
            val operations = projector.foldWithOperations(added)
            projectionRecords[sessionId] = records.toMutableList()
            chatCacheLog("projection-appended", "reusedEvents=${previous.size} foldedEvents=${added.size} operations=${operations.size}")
            SharedConversationPatch(sessionId = sessionId, operations = operations, lastSequence = projector.lastSequence)
        }
    }

    fun assistantChunks(sessionId: String, attemptId: String, chunksJson: String): SharedMviDispatchResult =
        dispatch("assistant-chunks") {
            val chunks = wireJson.decodeFromString<List<AssistantChunk>>(chunksJson)
            invalidatePreparation(sessionId)
            val projector = projectors.getOrPut(sessionId) { ConversationProjector(labels) }
            val operations = projector.foldAssistantChunks(attemptId, chunks)
            if (operations.isEmpty()) null else SharedConversationPatch(
                sessionId = sessionId, operations = operations, lastSequence = projector.lastSequence
            )
        }

    fun clearAssistantChunks(sessionId: String): SharedMviDispatchResult = dispatch("assistant-clear") {
        invalidatePreparation(sessionId)
        val projector = projectors[sessionId] ?: return@dispatch null
        val operations = projector.clearAssistantChunks()
        if (operations.isEmpty()) null else SharedConversationPatch(
            sessionId = sessionId, operations = operations, lastSequence = projector.lastSequence
        )
    }

    fun replaceSteeringMessages(sessionId: String, itemsJson: String): SharedMviDispatchResult = dispatch("steering") {
        require(sessionId.isNotBlank())
        val items = requireNotNull(JsonValue.fromJsonElement(wireJson.parseToJsonElement(itemsJson)).arrayValue)
        val now = platformNowEpochMilliseconds().toDouble()
        val records = items.filter { it["placement"]?.stringValue == "steering" }.map { row ->
            val message = requireNotNull(row["message"])
            require(!message["id"]?.stringValue.isNullOrBlank())
            requireNotNull(message["content"]?.arrayValue)
            RawSessionEvent("user/message", 0, now, message).normalized(sessionId)
        }
        mutationRevisions[sessionId] = (mutationRevisions[sessionId] ?: 0L) + 1
        steeringRecords[sessionId] = records
        val projector = projectors.getOrPut(sessionId) { ConversationProjector(labels) }
        val operations = projector.replaceSteeringMessages(records)
        if (operations.isEmpty()) null else SharedConversationPatch(
            sessionId = sessionId, operations = operations, lastSequence = projector.lastSequence
        )
    }

    fun replaceSession(sessionId: String, eventsJson: String): SharedMviDispatchResult = dispatch("replace") {
        replaceSessionPatch(sessionId, wireJson.decodeFromString<List<SessionEvent>>(eventsJson))
    }

    fun replaceTypedSession(sessionId: String, records: List<SessionEvent>): SharedMviDispatchResult = dispatch("replace") {
        replaceSessionPatch(sessionId, records)
    }

    fun replaceHistorySnapshot(sessionId: String, snapshot: SharedHistoryProjectionSnapshot): SharedMviDispatchResult = dispatch("replace") {
        require(sessionId == snapshot.sessionId) { "history snapshot contains another session" }
        replaceSessionPatch(sessionId, snapshot.records)
    }

    fun prepareHistorySnapshot(sessionId: String, snapshot: SharedHistoryProjectionSnapshot): SharedConversationHistoryPreparation? {
        if (sessionId != snapshot.sessionId) return null
        return SharedConversationHistoryPreparation(snapshot, labels, steeringRecords[sessionId].orEmpty().toList(),
            mutationRevisions[sessionId] ?: 0L, preparationOwner, projectors[sessionId]?.items)
    }

    /** 失配不是领域错误：调用方保留原投影并同步处理最新 pending 历史。 */
    fun commitPreparedHistory(prepared: SharedPreparedConversationHistory): SharedMviDispatchResult? {
        if (prepared.owner !== preparationOwner || (mutationRevisions[prepared.sessionId] ?: 0L) != prepared.revision) return null
        projectors[prepared.sessionId] = prepared.projector
        projectionRecords[prepared.sessionId] = prepared.records.toMutableList()
        mutationRevisions[prepared.sessionId] = prepared.revision + 1
        val event = events.emitTransition(
            transactionId = "conversation-prepared:${events.currentSequence + 1}",
            statePayloadJson = if (needsWirePayload()) prepared.payload else null,
            onDelivery = if (patchObservers.hasObservers) { event -> patchObservers.deliver(event, prepared.patch) } else null,
        )
        return SharedMviDispatchResult(true, event.transactionId, event.sequence)
    }

    private fun replaceSessionPatch(sessionId: String, records: List<SessionEvent>): SharedConversationPatch {
            require(sessionId.isNotBlank()) { "sessionId must not be blank" }
            require(records.all { it.sessionId == sessionId }) { "baseline contains another session" }
            invalidatePreparation(sessionId)
            val normalized = records.associateBy(SessionEvent::seq).values.sortedBy(SessionEvent::seq)
            val projector = ConversationProjector(labels).apply { rebuild(normalized) }
            projector.replaceSteeringMessages(steeringRecords[sessionId].orEmpty())
            projectors[sessionId] = projector
            projectionRecords[sessionId] = normalized.toMutableList()
            return SharedConversationPatch(
                sessionId = sessionId,
                replacesAll = true,
                replacementItems = projector.items,
                lastSequence = projector.lastSequence
            )
    }

    fun estimatedRetainedBytes(sessionId: String): Long =
        ChatCacheMemoryEstimator.estimateProjection(projectors[sessionId]?.items.orEmpty()) +
            projectionRecords[sessionId].orEmpty().size.toLong() * Long.SIZE_BYTES

    fun clearSession(sessionId: String): SharedMviDispatchResult = dispatch("clear") {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        invalidatePreparation(sessionId)
        projectors.remove(sessionId)
        projectionRecords.remove(sessionId)
        steeringRecords.remove(sessionId)
        SharedConversationPatch(
            sessionId = sessionId,
            replacesAll = true,
            replacementItems = emptyList(),
            lastSequence = -1
        )
    }

    private fun invalidatePreparation(sessionId: String) {
        mutationRevisions[sessionId] = (mutationRevisions[sessionId] ?: 0L) + 1
    }

    private companion object {
        const val MAXIMUM_INLINE_EVENTS = 32
        const val MAXIMUM_INLINE_CONTENT_BYTES = 256L * 1024
    }

    private inline fun dispatch(
        operation: String,
        block: () -> SharedConversationPatch?
    ): SharedMviDispatchResult = try {
        val patch = block()
        if (patch == null) {
            SharedMviDispatchResult(true, null, null)
        } else {
            mutationRevisions[patch.sessionId] = (mutationRevisions[patch.sessionId] ?: 0L) + 1
            val event = events.emitTransition(
                transactionId = "conversation-$operation:${events.currentSequence + 1}",
                statePayloadJson = if (needsWirePayload()) wireJson.encodeToString(patch) else null,
                onDelivery = if (patchObservers.hasObservers) { event -> patchObservers.deliver(event, patch) } else null,
            )
            SharedMviDispatchResult(true, event.transactionId, event.sequence)
        }
    } catch (error: Throwable) {
        val code = "conversation-$operation-failed"
        val message = error.message ?: error::class.simpleName ?: "unknown-error"
        val event = events.emitError("$code:${events.currentSequence + 1}", code, message)
        SharedMviDispatchResult(false, event.transactionId, event.sequence, code, message)
    }
}
