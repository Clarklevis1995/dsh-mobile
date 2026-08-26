package com.clarklevis.dsh.shared.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class HistoryGeneration(val value: Long)

/**
 * 平台无关的 history 请求代际与超时协调器。
 * 所有调用由 owning UI/store 串行提交；网络请求与状态发布仍留在平台 effect 层。
 */
class HistorySyncEngine(
    val configuration: HistorySyncConfiguration = HistorySyncConfiguration(),
    scope: CoroutineScope? = null
) {
    private val ownsScope = scope == null
    private val scope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val generations = mutableMapOf<String, HistoryGeneration>()
    private val timeoutJobs = mutableMapOf<String, Job>()
    private var nextGeneration = 0L

    fun beginRequest(sessionId: String, timeoutMillis: Long, onTimeout: () -> Unit): HistoryGeneration {
        finishTimeout(sessionId)
        val generation = newGeneration()
        generations[sessionId] = generation
        timeoutJobs[sessionId] = scope.launch {
            delay(timeoutMillis)
            if (generations[sessionId] == generation) {
                generations.remove(sessionId)
                timeoutJobs.remove(sessionId)
                onTimeout()
            }
        }
        return generation
    }

    fun beginProcessing(sessionId: String): HistoryGeneration {
        finishTimeout(sessionId)
        return newGeneration().also { generations[sessionId] = it }
    }

    fun isCurrent(generation: HistoryGeneration, sessionId: String): Boolean = generations[sessionId] == generation
    fun isActive(sessionId: String): Boolean = sessionId in generations

    fun finish(sessionId: String) {
        finishTimeout(sessionId)
        generations.remove(sessionId)
    }

    fun cancelAll() {
        timeoutJobs.values.forEach(Job::cancel)
        timeoutJobs.clear()
        generations.clear()
    }

    fun close() {
        cancelAll()
        if (ownsScope) scope.cancel()
    }

    private fun newGeneration() = HistoryGeneration(++nextGeneration)

    private fun finishTimeout(sessionId: String) {
        timeoutJobs.remove(sessionId)?.cancel()
    }
}
