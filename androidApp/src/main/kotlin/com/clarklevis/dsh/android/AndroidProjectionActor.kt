package com.clarklevis.dsh.android

import com.clarklevis.dsh.shared.facade.SharedMobileSnapshot
import com.clarklevis.dsh.shared.protocol.GatewayFrame
import com.clarklevis.dsh.shared.projection.TrajectoryNode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Projection mutation、结果发布和对应 UI 清理共用一个有序提交边界。 */
internal class AndroidProjectionActor(
    private val projection: AndroidGatewayProjection,
    private val uiDispatcher: CoroutineDispatcher,
    private val publish: (snapshot: SharedMobileSnapshot, coalesceWithDisplayFrame: Boolean) -> Unit,
    private val nowNanos: () -> Long = System::nanoTime
) {
    private val mutationLock = Mutex()
    private var pendingStreamingFrame: PendingStreamingFrame? = null
    private var lastStreamingFlushNanos = 0L

    val initialSnapshot: SharedMobileSnapshot = projection.snapshot()

    suspend fun acceptFrame(
        rawJson: String,
        frame: GatewayFrame,
        correlatedSessionId: String?,
        afterPublish: () -> Unit = {}
    ) = mutate(
        afterPublish = afterPublish,
        coalesceWithDisplayFrame = frame.kind == "event" && frame.event?.type == "assistant/chunk"
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

    suspend fun selectSession(sessionId: String?, afterPublish: () -> Unit = {}) =
        mutate(afterPublish) { projection.selectSession(sessionId) }

    suspend fun loadHistory(
        sessionId: String,
        older: Boolean,
        afterPublish: () -> Unit = {}
    ) = mutate(afterPublish) { projection.loadHistory(sessionId, older) }

    suspend fun loadFixture(afterPublish: () -> Unit = {}) =
        mutate(afterPublish = afterPublish, mutation = projection::loadFixture)

    suspend fun reset(afterPublish: () -> Unit = {}) =
        mutate(afterPublish = afterPublish, mutation = projection::reset)

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

    fun loadFixtureImmediate(afterPublish: () -> Unit = {}) {
        publish(projection.loadFixture(), false)
        afterPublish()
    }

    fun resetImmediate(afterPublish: () -> Unit = {}) {
        publish(projection.reset(), false)
        afterPublish()
    }

    fun close() {
        pendingStreamingFrame = null
        projection.close()
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
        withContext(uiDispatcher) {
            publish(next, coalesceWithDisplayFrame)
            afterPublish()
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

    companion object {
        private val BATCHABLE_CHUNK_TYPES = setOf("text-delta", "reasoning-delta")
        private const val MAXIMUM_STREAMING_BATCH_SIZE = 32
        private const val STREAMING_PROJECTION_INTERVAL_NANOS = 50_000_000L
    }
}
