package com.clarklevis.dsh.shared.gateway

import com.clarklevis.dsh.shared.platform.GatewayConnectionSpec
import com.clarklevis.dsh.shared.platform.GatewayTransport
import com.clarklevis.dsh.shared.platform.GatewayTransportEvent
import com.clarklevis.dsh.shared.platform.GatewayTransportState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 并发候选采纳：同时尝试同一主机的全部候选地址，第一个完成**应用层握手**的候选
 * 直接转正为那条连接，其余候选关闭。
 *
 * 关键不变量：
 * - **转正即复用**：胜者就是已经握好手的那条 socket，转正过程中不重连、不二次握手。
 * - **败者静默**：转正后其余候选的事件既不出现在 [events]，也不改变状态。
 * - **一条逻辑通道**：对外只有一个代次、一条有序事件流；胜者帧被打上外层代次，
 *   于是 `GatewayRuntime` 既有的代次过滤继续生效。
 * - **有界并发与有界时间**：任一时刻最多 [maximumConcurrentCandidates] 条候选在拨号；
 *   整场竞速不超过 [totalBudgetMilliseconds]，因此一个永久挂起的候选不会拖死连接。
 */
class RacingGatewayTransport(
    private val factory: GatewaySocketFactory,
    private val scope: CoroutineScope,
    private val maximumConcurrentCandidates: Int = DEFAULT_MAXIMUM_CONCURRENT_CANDIDATES,
    private val totalBudgetMilliseconds: Long = DEFAULT_TOTAL_BUDGET_MILLISECONDS,
    private val channelCapacity: Int = DEFAULT_CHANNEL_CAPACITY
) : GatewayTransport {

    private val mutableState = MutableStateFlow<GatewayTransportState>(GatewayTransportState.Closed())
    override val state: StateFlow<GatewayTransportState> = mutableState.asStateFlow()

    /**
     * 单消费者、有序。只有被采纳候选的帧会进入这里。
     *
     * 这里**只保留一条长活通道**，不做"每轮换流"：运行时在 Runtime.init 里就开始收集，
     * 任何"收集者绑定到某一轮 channel"的写法都会在换流后失效或抛 ClosedReceiveChannelException，
     * 结果是运行时一帧都收不到、连接永远停在 CONNECTING。
     * 轮次隔离改为按代次过滤（运行时本来也有同样的门禁）。
     */
    private val outbound = Channel<GatewayTransportEvent>(capacity = channelCapacity)

    @Volatile
    private var currentGeneration: Long = 0

    override val events: Flow<GatewayTransportEvent> = outbound.receiveAsFlow()
        .filter { event ->
            val eventGeneration = when (event) {
                is GatewayTransportEvent.Frame -> event.value.generation
                is GatewayTransportEvent.State -> event.value.generation
            }
            eventGeneration == currentGeneration
        }

    private val lock = Mutex()
    private var generation: Long = 0
    private var adopted: Adopted? = null
    private var raceOutcome: GatewayRaceFailure? = null
    private var lastAdoptedEndpoint: String? = null
    private var raceJobs: List<Job> = emptyList()

    /** 被采纳的候选：它就是那条已经握好手的连接，转正不重连。 */
    private class Adopted(
        val endpoint: String,
        val channel: GatewayTransport,
        /** 胜者的握手帧：由 open() 在转正后立即交付，保证"先宣告、后数据"且不丢帧。 */
        val handshake: com.clarklevis.dsh.shared.platform.GatewayTransportFrame
    )

    /**
     * 并发尝试候选集，直到某个候选完成应用层握手（返回 true）或全部候选失败/超预算（返回 false）。
     *
     * 返回时胜者**已经**是本通道的连接：后续 [send] 直接写它。
     * 该调用不回调任何外部逻辑，因此在调用方的串行锁内调用不会自锁。
     */
    /**
     * 并发尝试候选集。返回时若已有胜者，它**已经**是本通道的连接（[adoptedEndpoint]）；
     * 全部失败/超预算时失败详情进 [lastRaceFailure]。
     */
    override suspend fun open(spec: GatewayConnectionSpec) {
        val previous = lock.withLock {
            val winner = adopted
            adopted = null
            winner
        }
        withContext(NonCancellable) { runCatching { previous?.channel?.close() } }

        lock.withLock {
            generation = spec.generation
            raceOutcome = null
        }
        // 代次一旦推进，上一轮残留的事件在 events 过滤处即被丢弃。
        currentGeneration = spec.generation
        mutableState.value = GatewayTransportState.Opening(spec.generation)

        val endpoints = spec.pairingCandidates()
        val admitted = Channel<Adopted>(capacity = 1)
        val semaphore = Semaphore(maximumConcurrentCandidates.coerceAtLeast(1))
        val failures = mutableListOf<GatewayCandidateFailure>()
        val failuresLock = Mutex()

        val jobs = endpoints.associateWith { endpoint ->
            scope.launch {
                semaphore.withPermit {
                    runCandidate(spec, endpoint, admitted, failures, failuresLock)
                }
            }
        }
        lock.withLock { raceJobs = jobs.values.toList() }

        // 预算内等待第一个胜者；超预算或全部失败则按目前收集到的失败聚合，绝不无限等待。
        val winner = withTimeoutOrNull(totalBudgetMilliseconds) { admitted.receiveCatching().getOrNull() }

        // 胜负已定：只取消并 join **败者**。胜者的收集协程必须继续存活，
        // 它是这条通道后续所有帧的唯一转发者；连它一起取消会把握手帧一起吞掉。
        val losers = jobs.filterKeys { it != winner?.endpoint }.values
        losers.forEach { it.cancel() }
        withContext(NonCancellable) { losers.joinAll() }

        if (winner == null) {
            val collected = failuresLock.withLock { failures.toList() }
            val aggregate = GatewayRaceFailure(
                attempted = collected.size,
                winnerEndpoint = null,
                failures = collected
            )
            lock.withLock {
                raceOutcome = aggregate
                raceJobs = emptyList()
            }
            mutableState.value = aggregateState(spec.generation, aggregate)
            return
        }

        lock.withLock {
            adopted = winner
            lastAdoptedEndpoint = winner.endpoint
        }
        mutableState.value = GatewayTransportState.Open(spec.generation, winner.endpoint)
        // 转正完成后再交付握手帧：运行时据此进入 CONNECTED，顺序与单通道实现一致。
        runCatching {
            outbound.send(
                GatewayTransportEvent.Frame(winner.handshake.copy(generation = spec.generation))
            )
        }
    }

    /** 最近一次竞速被采纳的地址；null 表示没有胜者。 */
    fun adoptedEndpoint(): String? = lastAdoptedEndpoint

    /** 最近一次竞速全部失败时的逐候选记录。 */
    fun lastRaceFailure(): GatewayRaceFailure? = raceOutcome


    /** 在已采纳的那条连接上发送；胜者已转正，因此这里没有任何重连语义。 */
    override suspend fun send(text: String) {
        val winner = lock.withLock { adopted } ?: throw IllegalStateException("not-connected")
        winner.channel.send(text)
    }

    override suspend fun close() {
        val (winner, jobs) = lock.withLock {
            val value = adopted
            val pending = raceJobs
            adopted = null
            raceJobs = emptyList()
            value to pending
        }
        // 先取消再 join：close() 返回时必须没有任何候选收集协程还在跑，
        // 否则它们会向已关闭的事件通道继续发送。
        jobs.forEach { it.cancel() }
        withContext(NonCancellable) {
            jobs.joinAll()
            runCatching { winner?.channel?.close() }
        }
        currentGeneration = 0
        mutableState.value = GatewayTransportState.Closed(generation)
    }

    /**
     * 存在性探测：跑同一套竞速，采纳第一个可用候选后**立刻关闭**并返回结局。
     * 供主机列表的在线判定使用——探活与真实连接从此共用同一个实现。
     */
    suspend fun raceOnce(spec: GatewayConnectionSpec): GatewayRaceFailure {
        open(spec)
        val winnerEndpoint = lock.withLock { lastAdoptedEndpoint }
        val known = winnerEndpoint != null
        close()
        if (known) {
            return GatewayRaceFailure(
                attempted = spec.connectionCandidates().size,
                winnerEndpoint = winnerEndpoint,
                failures = emptyList()
            )
        }
        return lock.withLock { raceOutcome } ?: GatewayRaceFailure(
            attempted = spec.connectionCandidates().size,
            winnerEndpoint = null,
            failures = emptyList()
        )
    }

    /**
     * 单个候选的一生：打开、收集、判定。返回被采纳的候选，未中选返回 null。
     *
     * 被选为胜者的候选由本协程继续转发后续帧，因此不存在"转正后重新订阅"的窗口。
     * 未被选中的候选只读取到判定所需的事件，其帧永远不进入事件流。
     */
    private suspend fun runCandidate(
        spec: GatewayConnectionSpec,
        endpoint: String,
        admitted: Channel<Adopted>,
        failures: MutableList<GatewayCandidateFailure>,
        failuresLock: Mutex
    ): Adopted? {
        val channel = factory.createChannel(spec, endpoint)
        var won: Adopted? = null
        try {
            channel.open(spec.toCandidateSpec(endpoint))
            channel.events.collect { event ->
                if (won != null) {
                    forward(spec, event)
                    return@collect
                }
                when (event) {
                    is GatewayTransportEvent.State -> {
                        val value = event.value
                        if (value is GatewayTransportState.Failed) {
                            recordFailure(failures, failuresLock, endpoint, value)
                            return@collect
                        }
                    }
                    is GatewayTransportEvent.Frame -> {
                        when (val verdict =
                            GatewayHandshake.evaluate(event.value.text, spec.expectedGatewayId)) {
                            is GatewayHandshakeVerdict.Admitted -> {
                                val candidate = Adopted(endpoint, channel, event.value)
                                if (admitted.trySend(candidate).isSuccess) {
                                    // 不要在收集协程里交付握手帧：交付权归 open()，
                                    // 否则会与转正后的清理竞争，可能把握手帧吞掉。
                                    won = candidate
                                } else {
                                    // 已有别的候选先完成握手：本候选出局。
                                    return@collect
                                }
                            }
                            is GatewayHandshakeVerdict.Rejected -> {
                                failuresLock.withLock {
                                    failures += GatewayCandidateFailure(
                                        endpoint = endpoint,
                                        httpStatus = verdict.httpStatus,
                                        closeCode = verdict.closeCode,
                                        reason = verdict.code,
                                        recoverable = !verdict.fatal
                                    )
                                }
                                return@collect
                            }
                            GatewayHandshakeVerdict.Pending -> Unit
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            failuresLock.withLock {
                failures += GatewayCandidateFailure(
                    endpoint = endpoint,
                    reason = error.message ?: "candidate-failed"
                )
            }
        } finally {
            if (won == null) {
                withContext(NonCancellable) { runCatching { channel.close() } }
            }
        }
        return won
    }

    /** 胜者的后续事件：同一收集协程转发，代次改写为外层代次。 */
    private suspend fun forward(spec: GatewayConnectionSpec, event: GatewayTransportEvent) {
        val translated = when (event) {
            is GatewayTransportEvent.Frame ->
                GatewayTransportEvent.Frame(event.value.copy(generation = spec.generation))
            is GatewayTransportEvent.State -> GatewayTransportEvent.State(
                when (val value = event.value) {
                    is GatewayTransportState.Failed -> value.copy(generation = spec.generation)
                    is GatewayTransportState.Closed -> GatewayTransportState.Closed(spec.generation)
                    is GatewayTransportState.Open ->
                        GatewayTransportState.Open(spec.generation, value.endpoint)
                    is GatewayTransportState.Opening -> GatewayTransportState.Opening(spec.generation)
                }
            )
        }
        // 通道可能在 close() 与最后一个帧之间被关闭；这不是错误，静默丢弃即可。
        runCatching { outbound.send(translated) }
    }

    private suspend fun recordFailure(
        failures: MutableList<GatewayCandidateFailure>,
        failuresLock: Mutex,
        endpoint: String,
        failure: GatewayTransportState.Failed
    ) {
        failuresLock.withLock {
            failures += GatewayCandidateFailure(
                endpoint = endpoint,
                httpStatus = failure.httpStatus,
                closeCode = failure.closeCode,
                reason = failure.reason ?: "transport-failed",
                recoverable = failure.recoverable
            )
        }
    }

    private fun aggregateState(generation: Long, aggregate: GatewayRaceFailure): GatewayTransportState {
        // 最决定性的失败成为聚合码：认证/身份类失败必须让运行时继续阻断重连并清理 token。
        val decisive = aggregate.failures.firstOrNull { !it.recoverable }
            ?: aggregate.failures.firstOrNull { it.httpStatus != null }
            ?: aggregate.failures.firstOrNull()
        // 只要有一条候选是致命失败（认证/身份），整场竞速就不可恢复：
        // 否则运行时会保留失效凭据并按既有节奏反复重连。
        val fatal = aggregate.failures.any { !it.recoverable }
        return GatewayTransportState.Failed(
            generation = generation,
            httpStatus = decisive?.httpStatus,
            closeCode = decisive?.closeCode,
            reason = decisive?.reason ?: "all-candidates-failed",
            recoverable = !fatal,
            race = aggregate
        )
    }

    companion object {
        /** 与主机列表的存在性探测并发上限保持一致。 */
        const val DEFAULT_MAXIMUM_CONCURRENT_CANDIDATES = 4

        /**
         * 必须小于 `GatewayRuntime` 的单次尝试超时（15s），否则运行时的兜底会先触发，
         * 竞速的诊断信息永远到不了 `lastError`。
         */
        const val DEFAULT_TOTAL_BUDGET_MILLISECONDS = 12_000L

        private const val DEFAULT_CHANNEL_CAPACITY = 256
    }
}

/** 候选通道只认一个地址：把该地址写进 `endpoint`，候选集置空避免重复展开。 */
private fun GatewayConnectionSpec.toCandidateSpec(endpoint: String): GatewayConnectionSpec =
    copy(endpoint = endpoint, candidates = emptyList())
