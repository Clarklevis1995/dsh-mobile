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
    private val publish: (SharedMobileSnapshot) -> Unit
) {
    private val mutationLock = Mutex()

    val initialSnapshot: SharedMobileSnapshot = projection.snapshot()

    suspend fun acceptFrame(
        rawJson: String,
        frame: GatewayFrame,
        correlatedSessionId: String?,
        afterPublish: () -> Unit = {}
    ) = mutate(afterPublish) { projection.acceptFrame(rawJson, frame, correlatedSessionId) }

    suspend fun selectSession(sessionId: String?, afterPublish: () -> Unit = {}) =
        mutate(afterPublish) { projection.selectSession(sessionId) }

    suspend fun loadFixture(afterPublish: () -> Unit = {}) =
        mutate(afterPublish, projection::loadFixture)

    suspend fun reset(afterPublish: () -> Unit = {}) =
        mutate(afterPublish, projection::reset)

    suspend fun historyTimedOut(sessionId: String) = mutate {
        projection.historyTimedOut(sessionId)
        projection.snapshot()
    }

    suspend fun historyCancelled(sessionId: String) = mutate {
        projection.historyCancelled(sessionId)
        projection.snapshot()
    }

    suspend fun trajectory(sessionId: String?): List<TrajectoryNode> = mutationLock.withLock {
        projection.trajectory(sessionId)
    }

    fun acceptFrameImmediate(rawJson: String, frame: GatewayFrame, correlatedSessionId: String?) {
        publish(projection.acceptFrame(rawJson, frame, correlatedSessionId))
    }

    fun selectSessionImmediate(sessionId: String?, afterPublish: () -> Unit = {}) {
        publish(projection.selectSession(sessionId))
        afterPublish()
    }

    fun loadFixtureImmediate(afterPublish: () -> Unit = {}) {
        publish(projection.loadFixture())
        afterPublish()
    }

    fun resetImmediate(afterPublish: () -> Unit = {}) {
        publish(projection.reset())
        afterPublish()
    }

    fun close() = projection.close()

    private suspend fun mutate(
        afterPublish: () -> Unit = {},
        mutation: () -> SharedMobileSnapshot
    ) {
        mutationLock.withLock {
            val next = mutation()
            withContext(uiDispatcher) {
                publish(next)
                afterPublish()
            }
        }
    }
}
