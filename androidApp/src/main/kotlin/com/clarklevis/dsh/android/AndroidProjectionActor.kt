package com.clarklevis.dsh.android

import com.clarklevis.dsh.shared.cache.ChatCacheDiagnostics
import com.clarklevis.dsh.shared.cache.ChatCacheRestoreSkipReason
import com.clarklevis.dsh.shared.cache.SharedChatHistoryCache
import com.clarklevis.dsh.shared.facade.SharedApprovalEffect
import com.clarklevis.dsh.shared.facade.SharedMobileSnapshot
import com.clarklevis.dsh.shared.projection.GenuiActionEffect
import com.clarklevis.dsh.shared.projection.TrajectoryNode
import com.clarklevis.dsh.shared.protocol.GatewayFrame
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Projection mutation、结果发布和对应 UI 清理共用一个有序提交边界。 */
internal class AndroidProjectionActor(
    private val projection: AndroidGatewayProjection,
    private val uiDispatcher: CoroutineDispatcher,
    private val publish: (snapshot: SharedMobileSnapshot, coalesceWithDisplayFrame: Boolean) -> Unit,
    private val nowNanos: () -> Long = System::nanoTime,
    backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val snapshotTimeoutMillis: Long = 20_000,
    private val chatCache: SharedChatHistoryCache? = null,
    private val gatewayCacheId: String = "legacy",
    private val onDiskChanged: () -> Unit = {},
    private val onSearchNavigationResolved: (found: Boolean, fragment: Boolean) -> Unit = { _, _ -> }
) {
    private val mutationLock = Mutex()
    private val workScope = CoroutineScope(SupervisorJob() + backgroundDispatcher)
    private var snapshotTimeoutJob: Job? = null
    private var activeSnapshotWait: Pair<String, Long>? = null
    private var pendingStreamingFrame: PendingStreamingFrame? = null
    private var lastStreamingFlushNanos = 0L

    private val cacheWriteJobs = mutableMapOf<String, Job>()
    private var selectionGeneration = 0L
    private var closed = false
    private var closeJob: Job? = null
    private val protectionOwner = Any()
    private val protectionKey = chatCache?.let { CacheProtectionKey(it, gatewayCacheId) }

    init {
        protectionKey?.let { CACHE_PROTECTION_OWNERS[it] = protectionOwner }
        projection.onCacheChanged = { sessionId, immediate -> scheduleCacheWrite(sessionId, immediate) }
        projection.onCacheInvalidated = { sessionId ->
            cacheWriteJobs.remove(sessionId)?.cancel()
            val generation = System.nanoTime()
            workScope.launch { chatCache?.remove(gatewayCacheId, sessionId, generation) }
        }
    }

    val initialSnapshot: SharedMobileSnapshot = projection.snapshot()

    private fun scheduleCacheWrite(sessionId: String, immediate: Boolean) {
        if (chatCache == null) return
        cacheWriteJobs.remove(sessionId)?.cancel()
        val generation = System.nanoTime()
        cacheWriteJobs[sessionId] = workScope.launch {
            val epoch = chatCache.epoch()
            if (!immediate) delay(CHAT_CACHE_WRITE_DEBOUNCE_MILLIS)
            val payload = mutationLock.withLock { projection.cachedWindowJson(sessionId) } ?: return@launch
            val protected = mutationLock.withLock {
                val state = projection.snapshot()
                state.sessions.filter { it.isRunning }.map { it.id }.toSet() + listOfNotNull(state.selectedSessionId)
            }
            withContext(Dispatchers.IO) {
                updateCacheProtection(protected.toList())
                chatCache.saveAtEpoch(gatewayCacheId, sessionId, payload, generation, epoch)
                runCatching(onDiskChanged)
            }
        }
    }

    suspend fun acceptFrame(
        rawJson: String,
        frame: GatewayFrame,
        correlatedSessionId: String?,
        afterPublish: () -> Unit = {}
    ) = mutate(
        afterPublish = afterPublish,
        coalesceWithDisplayFrame = frame.kind == "assistant-stream" || (frame.kind == "event" && frame.event?.type == "assistant/chunk")
    ) { projection.acceptFrame(rawJson, frame, correlatedSessionId) }

    /**
     * 将同一步骤的微小文本 token 合成一次增量投影。Runtime 仍无损处理每个协议帧；这里只
     * 降低 history/conversation MVI、JSON patch 和 Main dispatcher 的提交频率。
     */
    suspend fun acceptStreamingFrame(
        rawJson: String,
        frame: GatewayFrame,
        correlatedSessionId: String?
    ): Boolean = mutationLock.withLock {
        if (!frame.isBatchableStreamingChunk()) {
            flushPendingStreamingFrameLocked()
            publishMutationLocked(
                projection.acceptFrame(rawJson, frame, correlatedSessionId),
                coalesceWithDisplayFrame = false
            )
            return@withLock true
        }

        var flushed = false
        val current = pendingStreamingFrame
        pendingStreamingFrame = if (current == null) {
            PendingStreamingFrame(rawJson, frame, correlatedSessionId, count = 1)
        } else if (current.canMerge(frame, correlatedSessionId)) {
            current.merge(rawJson, frame)
        } else {
            flushPendingStreamingFrameLocked()
            flushed = true
            PendingStreamingFrame(rawJson, frame, correlatedSessionId, count = 1)
        }

        val now = nowNanos()
        if (
            lastStreamingFlushNanos == 0L ||
            requireNotNull(pendingStreamingFrame).count >= MAXIMUM_STREAMING_BATCH_SIZE ||
            now - lastStreamingFlushNanos >= STREAMING_PROJECTION_INTERVAL_NANOS
        ) {
            flushPendingStreamingFrameLocked()
            lastStreamingFlushNanos = now
            flushed = true
        }
        flushed
    }

    suspend fun selectSession(sessionId: String?, offline: Boolean = false, targetSequence: Int? = null, afterPublish: () -> Unit = {}) {
        val cacheEpoch = chatCache?.epoch()
        val generation = mutationLock.withLock {
            selectionGeneration += 1
            flushPendingStreamingFrameLocked()
            val next = if (targetSequence != null && projection.snapshot().selectedSessionId == sessionId) {
                projection.closeSearchFragment()
            } else {
                projection.selectSession(sessionId, offline)
            }
            publishMutationLocked(next, false, afterPublish)
            selectionGeneration
        }
        if (sessionId == null || chatCache == null) return
        workScope.launch {
            val protected = mutationLock.withLock {
                val state = projection.snapshot()
                state.sessions.filter { it.isRunning }.map { it.id } + listOfNotNull(state.selectedSessionId)
            }
            updateCacheProtection(protected)
            val before = targetSequence?.takeIf { it < Int.MAX_VALUE }?.plus(1)
            val alreadyVisible = targetSequence != null && mutationLock.withLock { projection.containsSequence(sessionId, targetSequence) }
            val cached = if (alreadyVisible) null else chatCache.loadTypedPage(gatewayCacheId, sessionId, before, 80)
            mutationLock.withLock {
                if (generation == selectionGeneration && projection.snapshot().selectedSessionId == sessionId) {
                    val targetVisible = targetSequence != null && projection.containsSequence(sessionId, targetSequence)
                    val found = if (targetSequence != null) {
                        targetVisible || (cached != null && projection.showSearchFragment(sessionId, targetSequence, cached))
                    } else {
                        if (cached != null) projection.hydrateTypedWindow(sessionId, cached)
                        true
                    }
                    if (offline) projection.historyCancelled(sessionId)
                    publishMutationLocked(projection.snapshot(), false)
                    if (targetSequence != null) {
                        withContext(uiDispatcher) {
                            onSearchNavigationResolved(found, found && !targetVisible)
                        }
                    }
                } else {
                    ChatCacheDiagnostics.restoreSkipped(ChatCacheRestoreSkipReason.SELECTION_CHANGED)
                }
            }
            if (targetSequence != null) return@launch
            val epoch = cacheEpoch ?: return@launch
            val windows = mutationLock.withLock {
                projection.cacheSessionIds().filter { it in cacheWriteJobs }.mapNotNull { id ->
                    projection.cachedWindowJson(id)?.let { Triple(id, it, System.nanoTime()) }
                }
            }
            val failed = mutableListOf<String>()
            withContext(Dispatchers.IO) {
                windows.forEach { (id, payload, ticket) ->
                    if (!chatCache.saveAtEpoch(gatewayCacheId, id, payload, ticket, epoch)) failed += id
                }
            }
            withContext(Dispatchers.IO) { runCatching(onDiskChanged) }
            mutationLock.withLock { projection.trimInactive() }
        }
    }

    suspend fun offlineCatalogRestoreGeneration(): Long = mutationLock.withLock { selectionGeneration }

    suspend fun hasOfflineCatalogAuthority(): Boolean = mutationLock.withLock { projection.hasOfflineCatalogAuthority() }

    suspend fun exportOfflineCatalog(selectedWorkspaceId: String?): String = mutationLock.withLock {
        projection.exportOfflineCatalog(selectedWorkspaceId)
    }

    suspend fun restoreOfflineCatalog(
        json: String,
        generation: Long,
        afterPublish: (restoredWorkspaces: Boolean) -> Unit = {}
    ): Boolean = mutationLock.withLock {
        if (closed || !projection.canRestoreOfflineCatalog()) return@withLock false
        if (com.clarklevis.dsh.shared.facade.SharedOfflineCatalogCodec().normalize(json) == null) return@withLock false
        val restoreWorkspaces = projection.canRestoreOfflineWorkspaces()
        publishMutationLocked(projection.restoreOfflineCatalog(json), false) { afterPublish(restoreWorkspaces) }
        val id = projection.snapshot().selectedSessionId
        if (id != null && chatCache != null) workScope.launch {
            val cached = chatCache.loadTypedPage(gatewayCacheId, id, null, 80)
            mutationLock.withLock {
                if (!closed && generation == selectionGeneration && projection.snapshot().selectedSessionId == id &&
                    !projection.hasNormalHistory(id)
                ) {
                    if (cached != null) projection.hydrateTypedWindow(id, cached)
                    projection.historyCancelled(id)
                    publishMutationLocked(projection.snapshot(), false)
                }
            }
        }
        true
    }

    suspend fun trimMemory() {
        mutationLock.withLock { projection.trimInactive(maximumSessions = 0) }
        chatCache?.trimMemory()
    }

    suspend fun returnToLatestCached(sessionId: String): Boolean {
        val selection = mutationLock.withLock {
            selectionGeneration += 1
            projection.closeSearchFragment()
            val hasNormal = projection.hasNormalHistory(sessionId)
            if (hasNormal) publishMutationLocked(projection.snapshot(), false)
            selectionGeneration to hasNormal
        }
        if (selection.second) return true
        val page = chatCache?.loadTypedPage(gatewayCacheId, sessionId, null, 80) ?: return false
        mutationLock.withLock {
            if (selection.first == selectionGeneration && projection.snapshot().selectedSessionId == sessionId) {
                publishMutationLocked(projection.hydrateTypedWindow(sessionId, page), false)
            }
        }
        return false
    }

    suspend fun clearDiskCache() = mutationLock.withLock {
        cacheWriteJobs.values.forEach(Job::cancel)
        cacheWriteJobs.clear()
        projection.trimInactive(maximumSessions = 0)
        chatCache?.clear(null)
    }

    suspend fun loadHistory(sessionId: String, older: Boolean, offline: Boolean = false, afterPublish: () -> Unit = {}) {
        val before = mutationLock.withLock {
            projection.snapshot().selectedHistoryEarliestSequence.takeIf { older && projection.canPrependCachedPage(sessionId) }
        }
        if (before != null && chatCache != null) {
            val page = chatCache.loadTypedPage(gatewayCacheId, sessionId, before, 160)
            if (page != null && page.events.isNotEmpty()) {
                val accepted = mutationLock.withLock {
                    if (projection.snapshot().selectedSessionId == sessionId && projection.canPrependCachedPage(sessionId, page)) {
                        publishMutationLocked(projection.prependCachedPage(sessionId, page), false, afterPublish)
                        true
                    } else {
                        false
                    }
                }
                if (accepted) return
            }
        }
        mutate(afterPublish) {
            if (offline) projection.offlineHistoryUnavailable(sessionId, older) else projection.loadHistory(sessionId, older)
        }
    }

    suspend fun disconnected() {
        val epoch = chatCache?.epoch()
        val windows = mutationLock.withLock {
            flushPendingStreamingFrameLocked()
            val pending = projection.cacheSessionIds().filter { it in cacheWriteJobs }.mapNotNull { id ->
                projection.cachedWindowJson(id)?.let { Triple(id, it, System.nanoTime()) }
            }
            publishMutationLocked(projection.disconnected(), false)
            pending
        }
        if (chatCache != null && epoch != null) withContext(Dispatchers.IO) {
            windows.forEach { (id, payload, ticket) -> chatCache.saveAtEpoch(gatewayCacheId, id, payload, ticket, epoch) }
        }
    }

    suspend fun catchUpSelectedHistoryAfterReconnect() = mutate {
        projection.catchUpSelectedHistoryAfterReconnect()
    }

    suspend fun loadFixture(afterPublish: () -> Unit = {}) =
        mutate(afterPublish = afterPublish, mutation = projection::loadFixture)

    suspend fun reset(afterPublish: () -> Unit = {}) = mutate(afterPublish = afterPublish) {
        selectionGeneration += 1
        cacheWriteJobs.values.forEach(Job::cancel)
        cacheWriteJobs.clear()
        projection.reset()
    }

    suspend fun resolveGenuiAction(
        intent: com.clarklevis.dsh.shared.projection.GenuiActionIntent
    ): GenuiActionEffect? =
        mutationLock.withLock {
            flushPendingStreamingFrameLocked()
            projection.resolveGenuiAction(intent)
        }

    suspend fun submitApprovalDecision(
        rpcId: String,
        outcome: String,
        isConnected: Boolean
    ): SharedApprovalEffect? = mutationLock.withLock {
        flushPendingStreamingFrameLocked()
        val result = projection.submitApprovalDecision(rpcId, outcome, isConnected)
        publishMutationLocked(result.snapshot, coalesceWithDisplayFrame = false)
        result.effect
    }

    suspend fun approvalRequestFailed(rpcId: String, message: String?) = mutate {
        projection.approvalRequestFailed(rpcId, message)
    }

    suspend fun approvalSessionRequestsFailed(sessionId: String, message: String?) = mutate {
        projection.approvalSessionRequestsFailed(sessionId, message)
    }

    suspend fun historyTimedOut(sessionId: String) = mutate {
        projection.historyTimedOut(sessionId)
        projection.snapshot()
    }

    suspend fun historyCancelled(sessionId: String) = mutate {
        projection.historyCancelled(sessionId)
        projection.snapshot()
    }

    suspend fun trajectory(sessionId: String?): List<TrajectoryNode> = mutationLock.withLock {
        flushPendingStreamingFrameLocked()
        projection.trajectory(sessionId)
    }

    fun acceptFrameImmediate(rawJson: String, frame: GatewayFrame, correlatedSessionId: String?) {
        publish(projection.acceptFrame(rawJson, frame, correlatedSessionId), false)
    }

    fun selectSessionImmediate(sessionId: String?, afterPublish: () -> Unit = {}) {
        publish(projection.selectSession(sessionId), false)
        afterPublish()
    }

    fun loadHistoryImmediate(sessionId: String, older: Boolean) {
        publish(projection.loadHistory(sessionId, older), false)
    }

    fun loadFixtureImmediate(afterPublish: () -> Unit = {}) {
        publish(projection.loadFixture(), false)
        afterPublish()
    }

    fun resetImmediate(afterPublish: () -> Unit = {}) {
        publish(projection.reset(), false)
        afterPublish()
    }

    @Synchronized
    fun close(): Job? {
        if (closed) return closeJob
        closed = true
        workScope.cancel()
        pendingStreamingFrame = null
        projection.close()
        val cache = chatCache ?: return null
        // 独立于已取消的网关 scope；先等旧写入退出，避免旧 protect 覆盖最终释放。
        return CACHE_CLEANUP_SCOPE.launch {
            workScope.coroutineContext[Job]?.join()
            CACHE_PROTECTION_LOCK.withLock {
                val key = protectionKey ?: return@withLock
                if (CACHE_PROTECTION_OWNERS[key] === protectionOwner) {
                    cache.protect(gatewayCacheId, emptyList())
                    CACHE_PROTECTION_OWNERS.remove(key, protectionOwner)
                }
            }
        }.also { closeJob = it }
    }

    private suspend fun updateCacheProtection(sessionIds: List<String>) {
        val key = protectionKey ?: return
        CACHE_PROTECTION_LOCK.withLock {
            if (CACHE_PROTECTION_OWNERS[key] === protectionOwner) {
                key.cache.protect(gatewayCacheId, sessionIds)
            }
        }
    }

    private suspend fun mutate(
        afterPublish: () -> Unit = {},
        coalesceWithDisplayFrame: Boolean = false,
        mutation: () -> SharedMobileSnapshot
    ) {
        mutationLock.withLock {
            flushPendingStreamingFrameLocked()
            val next = mutation()
            publishMutationLocked(next, coalesceWithDisplayFrame, afterPublish)
        }
    }

    private suspend fun flushPendingStreamingFrameLocked() {
        val pending = pendingStreamingFrame ?: return
        pendingStreamingFrame = null
        publishMutationLocked(
            projection.acceptFrame(pending.rawJson, pending.frame, pending.correlatedSessionId),
            coalesceWithDisplayFrame = true
        )
    }

    private suspend fun publishMutationLocked(
        next: SharedMobileSnapshot,
        coalesceWithDisplayFrame: Boolean,
        afterPublish: () -> Unit = {}
    ) {
        val disposition = projection.dropObsoleteSearchFragment()
        val visible = if (disposition == null) next else projection.snapshot()
        withContext(uiDispatcher) {
            publish(visible, coalesceWithDisplayFrame)
            if (disposition !=
                null
            ) {
                onSearchNavigationResolved(disposition == AndroidGatewayProjection.SearchFragmentDisposition.REPLACED, false)
            }
            afterPublish()
        }
        reconcileSnapshotTimeout()
    }

    private fun reconcileSnapshotTimeout() {
        val wait = projection.snapshotWait
        if (wait == activeSnapshotWait) return
        snapshotTimeoutJob?.cancel()
        activeSnapshotWait = wait
        snapshotTimeoutJob = wait?.let { expected ->
            workScope.launch {
                delay(snapshotTimeoutMillis)
                mutationLock.withLock {
                    if (projection.snapshotWait == expected) {
                        snapshotTimeoutJob = null
                        activeSnapshotWait = null
                        projection.historyTimedOut(expected.first)
                        publishMutationLocked(projection.snapshot(), false)
                    }
                }
            }
        }
    }

    private fun GatewayFrame.isBatchableStreamingChunk(): Boolean {
        val value = event ?: return false
        return kind == "event" &&
            value.type == "assistant/chunk" &&
            value.chunkType in BATCHABLE_CHUNK_TYPES &&
            !value.text.isNullOrEmpty()
    }

    private data class PendingStreamingFrame(
        val rawJson: String,
        val frame: GatewayFrame,
        val correlatedSessionId: String?,
        val count: Int
    ) {
        fun canMerge(next: GatewayFrame, nextCorrelatedSessionId: String?): Boolean {
            val currentEvent = requireNotNull(frame.event)
            val nextEvent = next.event ?: return false
            return correlatedSessionId == nextCorrelatedSessionId &&
                frame.sessionId == next.sessionId &&
                currentEvent.turn == nextEvent.turn &&
                currentEvent.step == nextEvent.step &&
                currentEvent.chunkType == nextEvent.chunkType
        }

        fun merge(nextRawJson: String, next: GatewayFrame): PendingStreamingFrame {
            val currentEvent = requireNotNull(frame.event)
            val nextEvent = requireNotNull(next.event)
            return copy(
                rawJson = nextRawJson,
                frame = next.copy(
                    event = nextEvent.copy(text = currentEvent.text.orEmpty() + nextEvent.text.orEmpty())
                ),
                count = count + 1
            )
        }
    }

    private data class CacheProtectionKey(val cache: SharedChatHistoryCache, val gatewayId: String)

    companion object {
        private val CACHE_PROTECTION_LOCK = Mutex()
        private val CACHE_PROTECTION_OWNERS = ConcurrentHashMap<CacheProtectionKey, Any>()
        private val CACHE_CLEANUP_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private const val CHAT_CACHE_WRITE_DEBOUNCE_MILLIS = 500L
        private val BATCHABLE_CHUNK_TYPES = setOf("text-delta", "reasoning-delta")
        private const val MAXIMUM_STREAMING_BATCH_SIZE = 32
        private const val STREAMING_PROJECTION_INTERVAL_NANOS = 50_000_000L
    }
}
