package com.clarklevis.dsh.shared.facade

import com.clarklevis.dsh.shared.cache.ChatCacheMemoryEstimator
import com.clarklevis.dsh.shared.cache.ChatCacheWindowPolicy
import com.clarklevis.dsh.shared.cache.ChatCacheDiagnostics
import com.clarklevis.dsh.shared.cache.ChatCacheRestoreSkipReason
import com.clarklevis.dsh.shared.cache.chatCacheLog
import com.clarklevis.dsh.shared.protocol.SessionEvent
import com.clarklevis.dsh.shared.protocol.wireJson
import com.clarklevis.dsh.shared.sync.HistoryAction
import com.clarklevis.dsh.shared.sync.HistoryBatchKind
import com.clarklevis.dsh.shared.sync.HistoryEventMerger
import com.clarklevis.dsh.shared.sync.HistoryFailureCode
import com.clarklevis.dsh.shared.sync.HistoryReducer
import com.clarklevis.dsh.shared.sync.HistoryResult
import com.clarklevis.dsh.shared.sync.HistorySessionState
import com.clarklevis.dsh.shared.sync.HistoryState
import com.clarklevis.dsh.shared.sync.HistorySyncConfiguration
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
data class SharedHistoryBootstrap(
    val schema: Int = 1,
    val state: HistoryState = HistoryState(),
    val eventsBySession: Map<String, List<SessionEvent>> = emptyMap()
)

@Serializable
data class SharedHistoryEventPatch(
    val kind: String,
    val record: SessionEvent? = null,
    val index: Int? = null,
    val replacementEvents: List<SessionEvent>? = null,
    /**
     * True when this patch's `record` is a live assistant delta that continues the
     * row the journal already holds at this `seq`, rather than a structural
     * replacement of it. `kind` stays `"upsert"` so consumers that do not know the
     * marker keep their existing (correct, if slower) upsert handling; defaulted so
     * older payloads and hand-written fixtures decode unchanged.
     */
    val streamDelta: Boolean = false,
    val authoritative: Boolean = false,
    val replacementReferences: List<SharedHistoryEventReference>? = null,
    val referenceBaseEventSequence: Long? = null
)

@Serializable
data class SharedHistoryEventReference(val source: String, val index: Int, val sequence: Int)

/** 同一 MVI 事务的不可变记录；延迟 UI 交付不能重新读取已推进的活 Store。 */
class SharedHistoryProjectionSnapshot internal constructor(
    val sessionId: String,
    val eventSequence: Long,
    internal val records: List<SessionEvent>
) {
    /** 仅引用校验失败的恢复路径使用；正常投影不经过 JSON。 */
    fun eventsJson(): String = wireJson.encodeToString(records)
}

/** 纯输入准备；解码对象不与调用方容器共享，不持有任何活 Store。 */
class SharedPreparedHistoryPage private constructor(
    val sessionId: String,
    internal val records: List<SessionEvent>
) {
    companion object {
        @Throws(Exception::class)
        fun parse(sessionId: String, eventsJson: String): SharedPreparedHistoryPage {
            require(sessionId.isNotBlank()) { "sessionId must not be blank" }
            val records = wireJson.decodeFromString<List<SessionEvent>>(eventsJson)
            require(records.all { it.sessionId == sessionId }) { "history page contains another session" }
            return SharedPreparedHistoryPage(sessionId, records)
        }
    }
}

@Serializable
data class SharedHistoryPatch(
    val schema: Int = 1,
    val sessionId: String,
    val session: HistorySessionState? = null,
    val pendingSessionId: String? = null,
    val pendingSessionChanged: Boolean = false,
    val eventPatch: SharedHistoryEventPatch? = null,
    val outcome: String = "none",
    val failureCode: String? = null,
    val completedEventCount: Int? = null,
    val completedByteCount: Int? = null,
    val completedHasMore: Boolean? = null
)

@Serializable
data class SharedHistoryEffect(
    val action: String,
    val sessionId: String,
    val beforeSequence: Int? = null
)

/**
 * History 的唯一业务状态源。
 *
 * 平台只负责网络请求、超时定时器和 UI 发布；分页 cursor、加载状态、水位以及
 * history/live tail 的有序去重全部在这里完成。跨平台状态与effect先序列化再原子提交；
 * 同模块补丁以不可变对象交付，沿用同一事务队列和精确快照生命周期。
 */
class SharedHistoryStore(
    private val configuration: HistorySyncConfiguration = HistorySyncConfiguration()
) {
    private var state = HistoryState()
    private var eventsBySession: Map<String, List<SessionEvent>> = emptyMap()
    private val projectionSnapshots = mutableMapOf<Long, SharedHistoryProjectionSnapshot>()
    private val events = SharedMviEventEmitter("history") { event -> projectionSnapshots.remove(event.sequence) }
    private var cachedSequences: Map<String, Set<Int>> = emptyMap()
    private val patchObservers = SharedMviPatchObservers<SharedHistoryPatch>()

    internal fun subscribePatches(observer: (SharedMviEvent, SharedHistoryPatch) -> Unit): SharedMviSubscription =
        patchObservers.subscribe(events.currentSequence, observer)

    private fun needsWirePayload(): Boolean = events.hasObservers || !patchObservers.hasObservers

    fun subscribe(observer: SharedMviEventObserver): SharedMviSubscription = try {
        events.subscribe(
            observer,
            wireJson.encodeToString(SharedHistoryBootstrap(state = state, eventsBySession = eventsBySession))
        )
    } catch (error: Throwable) {
        events.subscribeError(
            observer,
            "history-subscribe-failed",
            error.message ?: error::class.simpleName ?: "unknown-error"
        )
    }

    fun start(
        sessionId: String,
        older: Boolean,
        hasLocalEvents: Boolean,
        earliestLocalSequence: Int?
    ): SharedMviDispatchResult = reduce(
        "start",
        sessionId,
        HistoryAction.Start(sessionId, older, hasLocalEvents, earliestLocalSequence)
    )

    /** 订阅快照承担首屏历史请求；仅发布加载状态，不额外发起普通分页请求。 */
    fun awaitSnapshot(sessionId: String): SharedMviDispatchResult =
        reduce("await-snapshot", sessionId, HistoryAction.AwaitSnapshot(sessionId))

    fun processingStarted(
        sessionId: String,
        rawEventCount: Int,
        hasMore: Boolean
    ): SharedMviDispatchResult = reduce(
        "processing",
        sessionId,
        HistoryAction.ProcessingStarted(sessionId, rawEventCount, hasMore)
    )

    fun pageReceived(
        sessionId: String,
        eventsJson: String,
        byteCount: Int,
        hasMore: Boolean,
        nextBeforeSequence: Int?,
        remoteActivityTimestamp: Double?
    ): SharedMviDispatchResult = receivePage(
        sessionId, byteCount, hasMore, nextBeforeSequence, remoteActivityTimestamp, false
    ) { wireJson.decodeFromString<List<SessionEvent>>(eventsJson) }

    /** 同模块已解码记录仍通过同一分页校验、合并与事务。 */
    internal fun pageReceivedTyped(
        sessionId: String,
        records: List<SessionEvent>,
        byteCount: Int,
        hasMore: Boolean,
        nextBeforeSequence: Int?,
        remoteActivityTimestamp: Double?,
    ): SharedMviDispatchResult =
        receivePage(
            sessionId,
            byteCount,
            hasMore,
            nextBeforeSequence,
            remoteActivityTimestamp,
            false,
        ) { records }

    /** iOS 已持有输入及上一事务镜像，只传 KMP 合并指令，避免回传全部正文。 */
    fun pageReceivedWithEventReferences(
        sessionId: String,
        eventsJson: String,
        byteCount: Int,
        hasMore: Boolean,
        nextBeforeSequence: Int?,
        remoteActivityTimestamp: Double?
    ): SharedMviDispatchResult = receivePage(
        sessionId, byteCount, hasMore, nextBeforeSequence, remoteActivityTimestamp, true
    ) { wireJson.decodeFromString<List<SessionEvent>>(eventsJson) }

    fun pageReceivedPreparedWithEventReferences(
        sessionId: String,
        page: SharedPreparedHistoryPage,
        byteCount: Int,
        hasMore: Boolean,
        nextBeforeSequence: Int?,
        remoteActivityTimestamp: Double?
    ): SharedMviDispatchResult = receivePage(
        sessionId, byteCount, hasMore, nextBeforeSequence, remoteActivityTimestamp, true
    ) {
        require(page.sessionId == sessionId) { "prepared history page contains another session" }
        page.records
    }

    /** 只允许同步订阅回调捕获准确事务；快照持有的列表此后不会原地修改。 */
    fun projectionSnapshotForEvent(sessionId: String, eventSequence: Long): SharedHistoryProjectionSnapshot? =
        projectionSnapshots[eventSequence]?.takeIf { it.sessionId == sessionId }

    private fun receivePage(
        sessionId: String,
        byteCount: Int,
        hasMore: Boolean,
        nextBeforeSequence: Int?,
        remoteActivityTimestamp: Double?,
        useReferences: Boolean,
        pageRecords: () -> List<SessionEvent>
    ): SharedMviDispatchResult = dispatch("page", sessionId) {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        val page = pageRecords()
        require(page.all { it.sessionId == sessionId }) { "history page contains another session" }
        require(remoteActivityTimestamp == null || remoteActivityTimestamp.isFinite()) {
            "remoteActivityTimestamp must be finite"
        }
        val oldEvents = eventsBySession[sessionId].orEmpty()
        val hadEvents = eventsBySession.containsKey(sessionId)
        val cached = cachedSequences[sessionId].orEmpty()
        val latest = state.sessions[sessionId]?.batchKind == HistoryBatchKind.LATEST
        val retained = if (latest) oldEvents.filterNot { it.seq in cached } else oldEvents
        val merged = mergeHistoryPage(page, retained.filterNot { it.seq in cached })
            .let { fresh -> if (latest) fresh else mergeHistoryPage(oldEvents.filter { it.seq in cached }, fresh) }
        val reduction = HistoryReducer.reduce(
            state,
            HistoryAction.PageCommitted(
                sessionId = sessionId,
                eventCount = page.size,
                byteCount = byteCount,
                hasMore = hasMore,
                nextBeforeSequence = nextBeforeSequence,
                earliestLocalSequence = merged.firstOrNull()?.seq,
                remoteActivityTimestamp = remoteActivityTimestamp
            ),
            configuration
        )
        if (latest && sessionId in cachedSequences) {
            chatCacheLog("cache-reconciled", "source=history cachedEvents=${cached.size} events=${merged.size}")
        }
        Transition(
            state = reduction.state,
            eventsBySession = if (merged == oldEvents && hadEvents) eventsBySession else eventsBySession + (sessionId to merged),
            eventPatch = if (merged == oldEvents && hadEvents) {
                null
            } else {
                SharedHistoryEventPatch(
                    kind = "replace",
                    replacementEvents = merged.takeUnless { useReferences },
                    authoritative = cached.isNotEmpty(),
                    replacementReferences = if (useReferences) eventReferences(merged, oldEvents, page) else null,
                    referenceBaseEventSequence = events.currentSequence.takeIf { useReferences }
                )
            },
            cacheSequences = when {
                latest -> cachedSequences - sessionId
                sessionId !in cachedSequences -> cachedSequences
                else -> {
                    val remaining = cached - page.map { it.seq }.toSet()
                    if (remaining.isEmpty()) {
                        cachedSequences - sessionId
                    } else {
                        cachedSequences + (sessionId to remaining)
                    }
                }
            },
            result = reduction.result
        )
    }

    private fun eventReferences(
        merged: List<SessionEvent>,
        retained: List<SessionEvent>,
        incoming: List<SessionEvent>
    ): List<SharedHistoryEventReference> {
        val retainedIndexes = retained.withIndex().associate { it.value.seq to it.index }
        val incomingIndexes = incoming.withIndex().associate { it.value.seq to it.index }
        return merged.map { record ->
            val oldIndex = retainedIndexes[record.seq]
            if (oldIndex != null && retained[oldIndex] === record) {
                SharedHistoryEventReference("retained", oldIndex, record.seq)
            } else {
                val index = requireNotNull(incomingIndexes[record.seq])
                check(incoming[index] === record) { "merged event has no input source" }
                SharedHistoryEventReference("incoming", index, record.seq)
            }
        }
    }

    fun liveEventReceived(eventJson: String): SharedMviDispatchResult =
        receiveLiveRecord {
            wireJson.decodeFromString<SessionEvent>(eventJson)
        }

    internal fun liveTypedEventReceived(record: SessionEvent): SharedMviDispatchResult = receiveLiveRecord { record }

    private fun receiveLiveRecord(recordSource: () -> SessionEvent): SharedMviDispatchResult =
        dispatch("live", null) {
            val record = recordSource()
            require(record.sessionId.isNotBlank()) { "sessionId must not be blank" }
            require(record.time.isFinite()) { "event time must be finite" }
            val oldEvents = eventsBySession[record.sessionId].orEmpty()
            val merged = HistoryEventMerger.merge(record, oldEvents)
            val index = merged.events.binarySearchBy(record.seq) { it.seq }
            check(index >= 0) { "merged event missing" }
            // Whether this record continues a live assistant row is decided from the
            // record that occupied the sequence BEFORE the merge. Reading it back out
            // of `merged.events` would compare the incoming record with itself and
            // make the predicate meaningless. A non-null `previousEvent` is exactly
            // "the record superseded a sequence that already existed" — which is why
            // the merge result needs no extra flag for this decision.
            val previousIndex = oldEvents.binarySearchBy(record.seq) { it.seq }
            val previousEvent = if (previousIndex >= 0) oldEvents[previousIndex].event else null
            // A same-sequence record is a live delta only when it supersedes another
            // transient chunk of the SAME turn and step. Anything else — the
            // authoritative `assistant/message`, a chunk from another turn or step, or
            // a gap fill — must keep the full rebaseline path. `chunkType` is
            // deliberately not compared: reasoning, text, tool, block, usage and
            // finish frames legitimately transition while sharing turn, step and seq.
            // Duplicate delivery is still indistinguishable from a legitimately
            // repeated fragment; that needs the host's per-chunk identity and is out of
            // scope here.
            val streamDelta = previousEvent != null &&
                previousEvent.type == "assistant/chunk" &&
                record.event.type == "assistant/chunk" &&
                previousEvent.turn == record.event.turn &&
                previousEvent.step == record.event.step
            val reduction = HistoryReducer.reduce(
                state,
                HistoryAction.LiveEventReceived(record.sessionId, record.time),
                configuration
            )
            Transition(
                state = reduction.state,
                eventsBySession = eventsBySession + (record.sessionId to merged.events),
                cacheSequences = cachedSequences[record.sessionId]?.let {
                    cachedSequences + (record.sessionId to (it - record.seq))
                } ?: cachedSequences,
                eventPatch = SharedHistoryEventPatch(
                    // `replacedOrInsertedOutOfOrder` means the merge was not a plain
                    // append. A record that landed on its OWN sequence is the ordinary
                    // shape of a live turn — DSH gives every chunk of a turn the same
                    // `session.seq` — so it is published as an upsert. A record
                    // inserted at a position it did not occupy is a gap fill. The
                    // `streamDelta` marker is what tells the platform this particular
                    // upsert continues a live assistant row and may be folded into it
                    // instead of triggering a rebaseline.
                    kind = if (merged.replacedOrInsertedOutOfOrder) "upsert" else "append",
                    record = record,
                    index = index,
                    streamDelta = streamDelta
                ),
                result = reduction.result,
                sessionId = record.sessionId
            )
        }

    /** 原子安装订阅窗口。显式 cursor 属于独立流状态，不能从精简 events 推导。 */
    fun installSnapshot(
        sessionId: String,
        eventsJson: String,
        hasMore: Boolean,
        nextBeforeSequence: Int?,
        loadRecentPages: Boolean = false,
    ): SharedMviDispatchResult =
        installRecords(sessionId, hasMore, nextBeforeSequence, loadRecentPages) {
            wireJson.decodeFromString<List<SessionEvent>>(eventsJson)
        }

    internal fun installTypedSnapshot(
        sessionId: String,
        records: List<SessionEvent>,
        hasMore: Boolean,
        nextBeforeSequence: Int?,
        loadRecentPages: Boolean = false,
    ): SharedMviDispatchResult = installRecords(sessionId, hasMore, nextBeforeSequence, loadRecentPages) { records }

    private fun installRecords(
        sessionId: String,
        hasMore: Boolean,
        nextBeforeSequence: Int?,
        loadRecentPages: Boolean,
        recordSource: () -> List<SessionEvent>,
    ): SharedMviDispatchResult =
        dispatch("snapshot", sessionId) {
            val records = recordSource()
            require(records.all { it.sessionId == sessionId })
            val normalized = records.associateBy(SessionEvent::seq).values.sortedBy(SessionEvent::seq)
            val unchanged = eventsBySession[sessionId] == normalized
            chatCacheLog("snapshot-installed", "events=${normalized.size} unchanged=$unchanged")
            val baseline = state.copy(
                sessions = state.sessions + (sessionId to HistorySessionState(
                    hasMore = hasMore, nextBeforeSequence = nextBeforeSequence
                )),
                pendingSessionId = state.pendingSessionId?.takeUnless { it == sessionId }
            )
            val recentPages = if (loadRecentPages && configuration.pagesPerBatch > 1 &&
                hasMore && nextBeforeSequence != null) {
                HistoryReducer.reduce(baseline, HistoryAction.Start(
                    sessionId, older = true, hasLocalEvents = normalized.isNotEmpty(), earliestLocalSequence = null
                ), configuration).let { reduction ->
                    // 订阅快照计为第一页，沿显式游标补齐剩余页，保持实时 tail。
                    val loading = reduction.state.sessions.getValue(sessionId).copy(pageCount = 1)
                    reduction.copy(state = reduction.state.copy(sessions = reduction.state.sessions + (sessionId to loading)))
                }
            } else null
            Transition(
                state = recentPages?.state ?: baseline,
                eventsBySession = if (unchanged) eventsBySession else eventsBySession + (sessionId to normalized),
                eventPatch = if (unchanged) null else SharedHistoryEventPatch("replace", replacementEvents = normalized),
                result = recentPages?.result ?: HistoryResult.None,
                cacheSequences = cachedSequences - sessionId
            )
        }

    /** 缓存只恢复显示内容，不建立网络 baseline，也不恢复请求/订阅状态。 */
    fun hydrateCachedWindow(sessionId: String, windowJson: String): SharedMviDispatchResult = dispatch("cache", sessionId) {
        hydrateCacheTransition(sessionId, wireJson.decodeFromString<CachedHistoryWindow>(windowJson))
    }

    fun hydrateTypedCachedWindow(sessionId: String, window: CachedHistoryWindow): SharedMviDispatchResult = dispatch("cache", sessionId) {
        hydrateCacheTransition(sessionId, window)
    }

    private fun hydrateCacheTransition(sessionId: String, window: CachedHistoryWindow): Transition {
        window.validate(sessionId)
        return if (eventsBySession.containsKey(sessionId)) {
            ChatCacheDiagnostics.restoreSkipped(ChatCacheRestoreSkipReason.ALREADY_LOADED)
            Transition(state, eventsBySession, result = HistoryResult.None)
        } else {
            val current = state.sessions[sessionId] ?: HistorySessionState()
            chatCacheLog(
                "restore-applied",
                "events=${window.events.size} hasMore=${window.hasMore} waitingForNetwork=${current.isLoading}"
            )
            val restoredSession = current.copy(hasMore = window.hasMore, nextBeforeSequence = window.nextBeforeSequence)
            Transition(
                state = state.copy(sessions = state.sessions + (sessionId to restoredSession)),
                eventsBySession = eventsBySession + (sessionId to window.events),
                cacheSequences = cachedSequences + (sessionId to window.events.map { it.seq }.toSet()),
                eventPatch = SharedHistoryEventPatch("replace", replacementEvents = window.events),
                result = HistoryResult.None
            )
        }
    }

    /** 只返回可释放候选，不发事件；平台可逐项 dispatch clearSession 来保持一 intent 一 transaction。 */
    fun inactiveSessionsOutside(sessionIds: List<String>): List<String> {
        val keep = sessionIds.toSet()
        return state.sessions.keys.filter { id ->
            val session = state.sessions[id]
            id !in keep && id != state.pendingSessionId && session?.isLoading != true && session?.batchKind == null
        }
    }

    /** 会逐会话发出独立 clear transaction；有 pendingIntent 配对的 adapter 应改用候选 API 逐项 dispatch。 */
    fun retainSessions(sessionIds: List<String>): List<String> {
        val removed = inactiveSessionsOutside(sessionIds)
        removed.forEach(::clearSession)
        if (removed.isNotEmpty()) chatCacheLog("history-memory-trimmed", "sessions=${removed.size}")
        return removed
    }

    fun canPrependCachedPage(sessionId: String): Boolean = sessionId in cachedSequences

    /** 只向仍由缓存恢复的窗口前插；网络已接管时必须使用网关分页，避免复活已删除的旧消息。 */
    fun prependTypedCachedPage(sessionId: String, window: CachedHistoryWindow): SharedMviDispatchResult = dispatch("cache-page", sessionId) {
        window.validate(sessionId)
        if (sessionId !in cachedSequences) {
            Transition(state, eventsBySession, result = HistoryResult.None)
        } else {
            val records = (window.events + eventsBySession[sessionId].orEmpty()).associateBy { it.seq }.values.sortedBy { it.seq }
            val session = (state.sessions[sessionId] ?: HistorySessionState()).copy(
                hasMore = window.hasMore, nextBeforeSequence = window.nextBeforeSequence
            )
            Transition(
                state = state.copy(sessions = state.sessions + (sessionId to session)),
                eventsBySession = eventsBySession + (sessionId to records),
                cacheSequences = cachedSequences + (sessionId to records.map { it.seq }.toSet()),
                eventPatch = SharedHistoryEventPatch("replace", replacementEvents = records),
                result = HistoryResult.None
            )
        }
    }

    fun cachedWindowJson(sessionId: String, historyFormatVersion: Int?, persistentCursor: Int?): String? =
        cachedWindowJsonWithinBudget(sessionId, historyFormatVersion, persistentCursor, ChatCacheWindowPolicy.DEFAULT_MAXIMUM_BYTES, ChatCacheWindowPolicy.DEFAULT_MAXIMUM_EVENTS)

    /** 平台可降低内存／磁盘窗口预算，原三参数入口和格式保持兼容。 */
    fun cachedWindowJsonWithinBudget(sessionId: String, historyFormatVersion: Int?, persistentCursor: Int?, maximumBytes: Long, maximumEvents: Int): String? {
        if (sessionId in cachedSequences) return null
        val records = eventsBySession[sessionId] ?: return null
        val session = state.sessions[sessionId] ?: return null
        if (session.isLoading || session.batchKind != null) return null
        return ChatCacheWindowPolicy.exportTail(
            CachedHistoryWindow(
                sessionId = sessionId,
                historyFormatVersion = historyFormatVersion,
                persistentCursor = persistentCursor,
                hasMore = session.hasMore,
                nextBeforeSequence = session.nextBeforeSequence
            ),
            records,
            maximumBytes,
            maximumEvents
        )
    }

    fun estimatedRetainedBytes(sessionId: String): Long =
        ChatCacheMemoryEstimator.estimateHistory(eventsBySession[sessionId].orEmpty())

    fun timedOut(sessionId: String): SharedMviDispatchResult =
        reduce("timeout", sessionId, HistoryAction.TimedOut(sessionId))

    fun cancelled(sessionId: String): SharedMviDispatchResult =
        reduce("cancel", sessionId, HistoryAction.Cancelled(sessionId))

    fun clearSession(sessionId: String): SharedMviDispatchResult = dispatch("clear", sessionId) {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        if (sessionId in cachedSequences) chatCacheLog("restore-cleared", "reason=memory-reset")
        val nextSessions = state.sessions - sessionId
        val nextState = state.copy(
            sessions = nextSessions,
            pendingSessionId = state.pendingSessionId?.takeUnless { it == sessionId }
        )
        val hadEvents = sessionId in eventsBySession
        Transition(
            state = nextState,
            eventsBySession = eventsBySession - sessionId,
            cacheSequences = cachedSequences - sessionId,
            eventPatch = if (hadEvents) SharedHistoryEventPatch(
                kind = "replace",
                replacementEvents = emptyList()
            ) else null,
            result = HistoryResult.None
        )
    }

    private fun reduce(
        operation: String,
        sessionId: String,
        action: HistoryAction
    ): SharedMviDispatchResult = dispatch(operation, sessionId) {
        val reduction = HistoryReducer.reduce(state, action, configuration)
        Transition(state = reduction.state, eventsBySession = eventsBySession, result = reduction.result)
    }

    private inline fun dispatch(
        operation: String,
        fallbackSessionId: String?,
        block: () -> Transition
    ): SharedMviDispatchResult {
        return try {
            val transition = block()
            val sessionId = transition.sessionId ?: fallbackSessionId
            require(!sessionId.isNullOrBlank()) { "sessionId must not be blank" }
            val unchangedData = transition.state == state &&
                transition.eventsBySession == eventsBySession &&
                transition.cacheSequences == cachedSequences
            if (unchangedData && transition.result == HistoryResult.None && transition.eventPatch == null) {
                return SharedMviDispatchResult(true, null, null)
            }
            val patch = SharedHistoryPatch(
                sessionId = sessionId,
                session = transition.state.sessions[sessionId],
                pendingSessionId = transition.state.pendingSessionId,
                pendingSessionChanged = transition.state.pendingSessionId != state.pendingSessionId,
                eventPatch = transition.eventPatch,
                outcome = transition.result.outcome(),
                failureCode = (transition.result as? HistoryResult.Failed)?.code?.name,
                completedEventCount = (transition.result as? HistoryResult.Completed)?.eventCount,
                completedByteCount = (transition.result as? HistoryResult.Completed)?.byteCount,
                completedHasMore = (transition.result as? HistoryResult.Completed)?.hasMore
            )
            val effect = transition.result.effect(sessionId)
            val needsWire = needsWirePayload()
            val statePayload = if (needsWire) wireJson.encodeToString(patch) else null
            val effectsPayload = wireJson.encodeToString(effect?.let(::listOf).orEmpty())
            state = transition.state
            eventsBySession = transition.eventsBySession
            cachedSequences = transition.cacheSequences
            if (transition.eventPatch != null) {
                val sequence = events.currentSequence + 1
                projectionSnapshots[sequence] = SharedHistoryProjectionSnapshot(
                    sessionId, sequence, transition.eventsBySession[sessionId].orEmpty()
                )
            }
            val event = events.emitTransition(
                transactionId = "history-$operation:${events.currentSequence + 1}",
                statePayloadJson = statePayload,
                effectsJson = effectsPayload,
                onDelivery = if (patchObservers.hasObservers) { event -> patchObservers.deliver(event, patch) } else null,
            )
            SharedMviDispatchResult(true, event.transactionId, event.sequence)
        } catch (error: Throwable) {
            if (operation == "cache") chatCacheLog("restore-failed", "reason=invalid-window", warning = true)
            val code = "history-$operation-failed"
            val message = error.message ?: error::class.simpleName ?: "unknown-error"
            val event = events.emitError("$code:${events.currentSequence + 1}", code, message)
            SharedMviDispatchResult(false, event.transactionId, event.sequence, code, message)
        }
    }

    private inner class Transition(
        val state: HistoryState,
        val eventsBySession: Map<String, List<SessionEvent>>,
        val eventPatch: SharedHistoryEventPatch? = null,
        val result: HistoryResult,
        val cacheSequences: Map<String, Set<Int>> = cachedSequences,
        val sessionId: String? = null
    )

    private fun mergeHistoryPage(
        page: List<SessionEvent>,
        live: List<SessionEvent>
    ): List<SessionEvent> {
        val records = linkedMapOf<Int, SessionEvent>()
        page.forEach { records[it.seq] = it }
        // 实时 lane 胜出，避免晚到 history 覆盖已收到的最终/增量事件。
        live.forEach { records[it.seq] = it }
        return records.values.sortedBy(SessionEvent::seq)
    }

    private fun HistoryResult.effect(sessionId: String): SharedHistoryEffect? = when (this) {
        is HistoryResult.RequestPage -> SharedHistoryEffect("request-page", sessionId, beforeSequence)
        else -> null
    }

    private fun HistoryResult.outcome(): String = when (this) {
        HistoryResult.None -> "none"
        is HistoryResult.RequestPage -> "request-page"
        HistoryResult.Stopped -> "stopped"
        is HistoryResult.Completed -> "completed"
        is HistoryResult.Failed -> "failed"
    }
}
