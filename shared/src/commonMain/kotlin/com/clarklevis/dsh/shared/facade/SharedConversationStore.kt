package com.clarklevis.dsh.shared.facade

import com.clarklevis.dsh.shared.cache.ChatCacheMemoryEstimator
import com.clarklevis.dsh.shared.cache.chatCacheLog
import com.clarklevis.dsh.shared.projection.ConversationItem
import com.clarklevis.dsh.shared.projection.ConversationItemKind
import com.clarklevis.dsh.shared.projection.ConversationProjectionLabels
import com.clarklevis.dsh.shared.projection.ConversationProjectionOperation
import com.clarklevis.dsh.shared.projection.ConversationProjector
import com.clarklevis.dsh.shared.projection.GenuiActionCodec
import com.clarklevis.dsh.shared.projection.GenuiActionEffect
import com.clarklevis.dsh.shared.projection.GenuiActionIntent
import com.clarklevis.dsh.shared.projection.GenuiSegment
import com.clarklevis.dsh.shared.projection.GenuiSegmentState
import com.clarklevis.dsh.shared.protocol.JsonValue
import com.clarklevis.dsh.shared.protocol.RawSessionEvent
import com.clarklevis.dsh.shared.protocol.SessionEvent
import com.clarklevis.dsh.shared.protocol.wireJson
import com.clarklevis.dsh.shared.sync.AssistantChunk
import kotlin.time.Clock
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
        require(SharedConversationPatchGuard.validateTyped(patch)) { "conversation patch violates wire invariants" }
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
            patch,
            wireJson.encodeToString(patch)
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
    internal val patch: SharedConversationPatch,
    internal val payload: String
)

/** KMP 持有逐 session projector；平台只 dispatch 原始事件并订阅增量 UI patch。 */
class SharedConversationStore(
    private val labels: ConversationProjectionLabels = ConversationProjectionLabels()
) {
    private val projectors = mutableMapOf<String, ConversationProjector>()
    private val projectionRecords = mutableMapOf<String, MutableList<SessionEvent>>()
    private val steeringRecords = mutableMapOf<String, List<SessionEvent>>()
    private val events = SharedMviEventEmitter("conversation")
    private val mutationRevisions = mutableMapOf<String, Long>()
    private val preparationOwner = Any()

    fun subscribe(observer: SharedMviEventObserver): SharedMviSubscription = try {
        events.subscribe(observer, wireJson.encodeToString(SharedConversationBootstrap()))
    } catch (error: Throwable) {
        events.subscribeError(
            observer,
            "conversation-subscribe-failed",
            error.message ?: error::class.simpleName ?: "unknown-error"
        )
    }

    fun receiveEvent(eventJson: String): SharedMviDispatchResult = dispatch("event", preValidated = true) {
        val record = wireJson.decodeFromString<SessionEvent>(eventJson)
        require(record.sessionId.isNotBlank()) { "sessionId must not be blank" }
        val existingProjector = projectors[record.sessionId]
        val projector = existingProjector ?: ConversationProjector(labels)
        require(record.seq > projector.lastSequence) {
            "event sequence ${record.seq} is not newer than ${projector.lastSequence}; replace baseline first"
        }
        if (existingProjector != null) {
            val fast = existingProjector.foldSingleStreamChunkFast(record)
            if (fast != null) {
                return@dispatch try {
                    val patch = SharedConversationPatch(
                        sessionId = record.sessionId,
                        operations = fast.operations,
                        lastSequence = existingProjector.lastSequence
                    )
                    require(SharedConversationPatchGuard.validateTyped(patch)) {
                        "conversation patch violates wire invariants"
                    }
                    projectionRecords[record.sessionId]?.add(record)
                    invalidatePreparation(record.sessionId)
                    patch
                } catch (error: Throwable) {
                    existingProjector.rollbackFastChunk(fast)
                    throw error
                }
            }
        }
        val previous = existingProjector?.snapshot()
        withProjectorRollback(projector, previous) {
            val operations = projector.foldWithOperations(listOf(record))
            // 不可见持久事件仍会推进流水位，必须发布给平台镜像。
            // 否则后续独立流携带正确水位时，iOS 会因镜像滞后而误判协议错误。
            val patch = SharedConversationPatch(
                sessionId = record.sessionId,
                operations = operations,
                lastSequence = projector.lastSequence
            )
            require(SharedConversationPatchGuard.validateTyped(patch)) {
                "conversation patch violates wire invariants"
            }
            if (existingProjector == null) projectors[record.sessionId] = projector
            projectionRecords[record.sessionId]?.add(record)
            invalidatePreparation(record.sessionId)
            patch
        }
    }

    /**
     * 单个 streaming 增量。DSH 的 `session.seq` 是 turn 级 journal watermark，同一 turn
     * 内的所有 chunk 共享同一个 seq，因此 [receiveEvent] 的单调性守卫在这里并不适用。
     *
     * 该路径只做行内变更：projector 用 per-key stream index 定位目标行，既不重建历史，
     * 也不重新序列化整个事件列表。缺少已建立基线的 projector 时 fail closed——
     * 平台据此回退到 [replaceSession]，绝不静默丢弃内容。
     */
    fun receiveStreamDelta(eventJson: String): SharedMviDispatchResult = dispatch("stream", preValidated = true) {
        val record = wireJson.decodeFromString<SessionEvent>(eventJson)
        require(record.sessionId.isNotBlank()) { "sessionId must not be blank" }
        val projector = projectors[record.sessionId]
            ?: error("no conversation baseline for session ${record.sessionId}; replace baseline first")
        val previousSequence = projector.lastSequence
        val fast = projector.foldSingleStreamChunkFast(record)
        if (fast != null) {
            return@dispatch try {
                if (fast.operations.isEmpty() && projector.lastSequence == previousSequence) {
                    invalidatePreparation(record.sessionId)
                    null
                } else {
                    val patch = SharedConversationPatch(
                        sessionId = record.sessionId,
                        operations = fast.operations,
                        lastSequence = projector.lastSequence
                    )
                    require(SharedConversationPatchGuard.validateTyped(patch)) {
                        "conversation patch violates wire invariants"
                    }
                    projectionRecords.remove(record.sessionId)
                    invalidatePreparation(record.sessionId)
                    patch
                }
            } catch (error: Throwable) {
                projector.rollbackFastChunk(fast)
                throw error
            }
        }
        val previous = projector.snapshot()
        withProjectorRollback(projector, previous) {
            val operations = projector.foldWithOperations(listOf(record))
            if (operations.isEmpty() && projector.lastSequence == previousSequence) {
                invalidatePreparation(record.sessionId)
                null
            } else {
                val patch = SharedConversationPatch(
                    sessionId = record.sessionId,
                    operations = operations,
                    lastSequence = projector.lastSequence
                )
                require(SharedConversationPatchGuard.validateTyped(patch)) {
                    "conversation patch violates wire invariants"
                }
                // 同 seq 流式变更不能证明旧基线仍是持久历史的严格前缀。
                projectionRecords.remove(record.sessionId)
                invalidatePreparation(record.sessionId)
                patch
            }
        }
    }

    /** 平台用它决定能否走 streaming 行内路径，否则回退到 baseline。 */
    fun hasProjection(sessionId: String): Boolean = projectors.containsKey(sessionId)

    fun resolveGenuiAction(intent: GenuiActionIntent): GenuiActionEffect? = try {
        if (intent.sessionId.isBlank() || intent.blockId.isBlank() || intent.contentVersion.length != 64) return null
        if (intent.activeSessionId != intent.sessionId) return null
        val hasReadyBlock = projectors[intent.sessionId]?.items.orEmpty().any { item ->
            item.kind == ConversationItemKind.ASSISTANT && item.segments.orEmpty().any { segment ->
                segment is GenuiSegment && segment.id == intent.blockId &&
                    segment.state == GenuiSegmentState.READY && segment.interactive &&
                    segment.contentVersion == intent.contentVersion
            }
        }
        if (!hasReadyBlock) return null
        val action = GenuiActionCodec.decode(intent.actionJson, intent.blockId) ?: return null
        if (action.type == com.clarklevis.dsh.shared.projection.GenuiActionType.SEND_MESSAGE && intent.isSearchContext) {
            return null
        }
        GenuiActionEffect(
            type = action.type,
            sessionId = intent.sessionId,
            blockId = intent.blockId,
            contentVersion = intent.contentVersion,
            text = action.text,
            details = action.details
        )
    } catch (_: Throwable) {
        null
    }

    /** Kotlin/Native 安全的 JSON 入口，绝不让未声明异常跨越桥接边界。 */
    fun dispatchGenuiActionJson(intentJson: String): String? = try {
        if (intentJson.isBlank() || intentJson.length > 24_576) return null
        val intent = wireJson.decodeFromString<GenuiActionIntent>(intentJson)
        val effect = resolveGenuiAction(intent) ?: return null
        wireJson.encodeToString(effect)
    } catch (_: Throwable) {
        null
    }

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
        return dispatch("history-append", preValidated = true) {
            val saved = projector.snapshot()
            withProjectorRollback(projector, saved) {
                val operations = projector.foldWithOperations(added)
                val patch = SharedConversationPatch(sessionId = sessionId, operations = operations, lastSequence = projector.lastSequence)
                require(SharedConversationPatchGuard.validateTyped(patch)) {
                    "conversation patch violates wire invariants"
                }
                invalidatePreparation(sessionId)
                projectionRecords[sessionId] = records.toMutableList()
                chatCacheLog("projection-appended", "reusedEvents=${previous.size} foldedEvents=${added.size} operations=${operations.size}")
                patch
            }
        }
    }

    fun assistantChunks(sessionId: String, attemptId: String, chunksJson: String): SharedMviDispatchResult =
        dispatch("assistant-chunks", preValidated = true) {
            val chunks = wireJson.decodeFromString<List<AssistantChunk>>(chunksJson)
            val existingProjector = projectors[sessionId]
            if (existingProjector != null) {
                val fast = existingProjector.foldExistingAssistantChunksFast(attemptId, chunks)
                if (fast != null) {
                    return@dispatch try {
                        if (fast.operations.isEmpty()) {
                            invalidatePreparation(sessionId)
                            null
                        } else {
                            val patch = SharedConversationPatch(
                                sessionId = sessionId, operations = fast.operations, lastSequence = existingProjector.lastSequence
                            )
                            require(SharedConversationPatchGuard.validateTyped(patch)) {
                                "conversation patch violates wire invariants"
                            }
                            invalidatePreparation(sessionId)
                            patch
                        }
                    } catch (error: Throwable) {
                        existingProjector.rollbackFastTransient(fast)
                        throw error
                    }
                }
            }
            val projector = existingProjector ?: ConversationProjector(labels)
            val saved = existingProjector?.snapshot()
            withProjectorRollback(projector, saved) {
                val operations = projector.foldAssistantChunks(attemptId, chunks)
                if (operations.isEmpty()) {
                    invalidatePreparation(sessionId)
                    null
                } else {
                    val patch = SharedConversationPatch(
                        sessionId = sessionId, operations = operations, lastSequence = projector.lastSequence
                    )
                    require(SharedConversationPatchGuard.validateTyped(patch)) {
                        "conversation patch violates wire invariants"
                    }
                    if (existingProjector == null) projectors[sessionId] = projector
                    invalidatePreparation(sessionId)
                    patch
                }
            }
        }

    fun clearAssistantChunks(sessionId: String): SharedMviDispatchResult = dispatch("assistant-clear", preValidated = true) {
        val projector = projectors[sessionId] ?: run {
            invalidatePreparation(sessionId)
            return@dispatch null
        }
        val saved = projector.snapshot()
        withProjectorRollback(projector, saved) {
            val operations = projector.clearAssistantChunks()
            if (operations.isEmpty()) {
                invalidatePreparation(sessionId)
                null
            } else {
                val patch = SharedConversationPatch(
                    sessionId = sessionId, operations = operations, lastSequence = projector.lastSequence
                )
                require(SharedConversationPatchGuard.validateTyped(patch)) {
                    "conversation patch violates wire invariants"
                }
                invalidatePreparation(sessionId)
                patch
            }
        }
    }

    fun replaceSteeringMessages(sessionId: String, itemsJson: String): SharedMviDispatchResult = dispatch("steering", preValidated = true) {
        require(sessionId.isNotBlank())
        val items = requireNotNull(JsonValue.fromJsonElement(wireJson.parseToJsonElement(itemsJson)).arrayValue)
        val now = Clock.System.now().toEpochMilliseconds().toDouble()
        val records = items.filter { it["placement"]?.stringValue == "steering" }.map { row ->
            val message = requireNotNull(row["message"])
            require(!message["id"]?.stringValue.isNullOrBlank())
            requireNotNull(message["content"]?.arrayValue)
            RawSessionEvent("user/message", 0, now, message).normalized(sessionId)
        }
        val existingProjector = projectors[sessionId]
        val projector = existingProjector ?: ConversationProjector(labels)
        val saved = existingProjector?.snapshot()
        withProjectorRollback(projector, saved) {
            val operations = projector.replaceSteeringMessages(records)
            if (operations.isEmpty()) {
                if (existingProjector == null) projectors[sessionId] = projector
                steeringRecords[sessionId] = records
                invalidatePreparation(sessionId)
                null
            } else {
                val patch = SharedConversationPatch(
                    sessionId = sessionId, operations = operations, lastSequence = projector.lastSequence
                )
                require(SharedConversationPatchGuard.validateTyped(patch)) {
                    "conversation patch violates wire invariants"
                }
                if (existingProjector == null) projectors[sessionId] = projector
                steeringRecords[sessionId] = records
                invalidatePreparation(sessionId)
                patch
            }
        }
    }

    fun replaceSession(sessionId: String, eventsJson: String): SharedMviDispatchResult = dispatch("replace", preValidated = true) {
        replaceSessionPatch(sessionId, wireJson.decodeFromString<List<SessionEvent>>(eventsJson))
    }

    fun replaceTypedSession(sessionId: String, records: List<SessionEvent>): SharedMviDispatchResult = dispatch("replace", preValidated = true) {
        replaceSessionPatch(sessionId, records)
    }

    fun replaceHistorySnapshot(sessionId: String, snapshot: SharedHistoryProjectionSnapshot): SharedMviDispatchResult = dispatch("replace", preValidated = true) {
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
        if (!SharedConversationPatchGuard.validateTyped(prepared.patch)) {
            val code = "conversation-prepared-failed"
            val message = "conversation patch violates wire invariants"
            val event = events.emitError("$code:${events.currentSequence + 1}", code, message)
            return SharedMviDispatchResult(false, event.transactionId, event.sequence, code, message)
        }
        projectors[prepared.sessionId] = prepared.projector
        projectionRecords[prepared.sessionId] = prepared.records.toMutableList()
        mutationRevisions[prepared.sessionId] = prepared.revision + 1
        val event = events.emitTransition(
            transactionId = "conversation-prepared:${events.currentSequence + 1}",
            statePayloadJson = prepared.payload
        )
        return SharedMviDispatchResult(true, event.transactionId, event.sequence)
    }

    private fun replaceSessionPatch(sessionId: String, records: List<SessionEvent>): SharedConversationPatch {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(records.all { it.sessionId == sessionId }) { "baseline contains another session" }
        val normalized = records.associateBy(SessionEvent::seq).values.sortedBy(SessionEvent::seq)
        val projector = ConversationProjector(labels).apply { rebuild(normalized) }
        projector.replaceSteeringMessages(steeringRecords[sessionId].orEmpty())
        val patch = SharedConversationPatch(
            sessionId = sessionId,
            replacesAll = true,
            replacementItems = projector.items,
            lastSequence = projector.lastSequence
        )
        require(SharedConversationPatchGuard.validateTyped(patch)) { "conversation patch violates wire invariants" }
        invalidatePreparation(sessionId)
        projectors[sessionId] = projector
        projectionRecords[sessionId] = normalized.toMutableList()
        return patch
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

    private inline fun <T> withProjectorRollback(
        projector: ConversationProjector,
        saved: ConversationProjector.Snapshot?,
        block: () -> T
    ): T = try {
        block()
    } catch (error: Throwable) {
        if (saved != null) projector.restore(saved)
        throw error
    }

    private companion object {
        const val MAXIMUM_INLINE_EVENTS = 32
        const val MAXIMUM_INLINE_CONTENT_BYTES = 256L * 1024
    }

    private inline fun dispatch(
        operation: String,
        preValidated: Boolean = false,
        block: () -> SharedConversationPatch?
    ): SharedMviDispatchResult = try {
        val patch = block()
        if (patch == null) {
            SharedMviDispatchResult(true, null, null)
        } else {
            if (!preValidated) {
                require(SharedConversationPatchGuard.validateTyped(patch)) { "conversation patch violates wire invariants" }
            }
            mutationRevisions[patch.sessionId] = (mutationRevisions[patch.sessionId] ?: 0L) + 1
            val event = events.emitTransition(
                transactionId = "conversation-$operation:${events.currentSequence + 1}",
                statePayloadJson = wireJson.encodeToString(patch)
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
