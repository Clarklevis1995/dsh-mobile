package com.clarklevis.dsh.android

import com.clarklevis.dsh.shared.gateway.GatewayRaceFailure
import com.clarklevis.dsh.shared.gateway.GatewaySocketFactory
import com.clarklevis.dsh.shared.gateway.RacingGatewayTransport
import com.clarklevis.dsh.shared.platform.GatewayConnectionSpec
import com.clarklevis.dsh.shared.platform.GatewayTransport
import com.clarklevis.dsh.shared.platform.GatewayTransportEvent
import com.clarklevis.dsh.shared.platform.GatewayTransportFrame
import com.clarklevis.dsh.shared.platform.GatewayTransportState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归：并发候选采纳。
 *
 * 现场问题是串行轮转——候选里有 9 条内网地址在蜂窝网下各要等满 10 秒连接超时，
 * 自动重连迟迟走不到那条可用地址，用户看到"一直连接中"，手动点一下才连上。
 *
 * 这些测试用脚本化假通道固定竞速语义：候选在 `open()` 内即可完成握手，
 * 因此胜者顺序由候选列表顺序确定，不依赖真实网络时序。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RacingGatewayTransportTest {

    private val gatewayId = "0c5bafb0-5f7e-416b-af94-a6bc39be7204"
    private val hello = """{"kind":"hello","protocol":3,"gatewayId":"$gatewayId","capabilities":[]}"""

    private fun spec(vararg candidates: String) = GatewayConnectionSpec(
        generation = 1,
        endpoint = candidates.first(),
        deviceId = "device",
        bearerToken = "token",
        expectedGatewayId = gatewayId,
        candidates = candidates.drop(1)
    )

    @Test
    fun firstCandidateToCompleteHandshakeIsAdoptedAndLosersAreClosed() = runTest {
        val factory = ScriptedFactory(handshakeOnOpen = setOf("wss://public"))
        val racer = RacingGatewayTransport(
            factory = factory,
            scope = backgroundScope,
            maximumConcurrentCandidates = 4,
            totalBudgetMilliseconds = 5_000
        )

        racer.open(spec("ws://lan-a", "ws://lan-b", "wss://public"))
        runCurrent()

        assertEquals("三条候选必须全部拨号", 3, factory.created.size)
        assertEquals("胜者就是那条已握好手的连接", "wss://public", racer.adoptedEndpoint())
        assertTrue(factory.transport("wss://public").closeCount == 0)
    }

    @Test
    fun losingCandidatesAreClosedOnceAWinnerExists() = runTest {
        val factory = ScriptedFactory(handshakeOnOpen = setOf("ws://lan-a"))
        val racer = RacingGatewayTransport(factory, backgroundScope, totalBudgetMilliseconds = 5_000)

        racer.open(spec("ws://lan-a", "wss://public"))
        runCurrent()

        assertEquals("ws://lan-a", racer.adoptedEndpoint())
        assertEquals("未中选的候选必须被关闭", 1, factory.transport("wss://public").closeCount)
        assertEquals("胜者必须保持打开", 0, factory.transport("ws://lan-a").closeCount)
    }

    @Test
    fun winnerHandshakeFrameIsForwardedExactlyOnce() = runTest {
        val factory = ScriptedFactory(handshakeOnOpen = setOf("wss://public"))
        val racer = RacingGatewayTransport(factory, backgroundScope, totalBudgetMilliseconds = 5_000)
        val frames = mutableListOf<String>()
        val collector = backgroundScope.launch {
            racer.events.collect { if (it is GatewayTransportEvent.Frame) frames += it.value.text }
        }

        racer.open(spec("ws://lan-a", "wss://public"))
        runCurrent()

        assertEquals("胜者的握手帧必须原样转发一次", listOf(hello), frames)
        collector.cancel()
    }

    @Test
    fun losingCandidateFramesNeverReachTheRuntime() = runTest {
        val factory = ScriptedFactory(handshakeOnOpen = setOf("wss://public"))
        val racer = RacingGatewayTransport(factory, backgroundScope, totalBudgetMilliseconds = 5_000)
        racer.open(spec("ws://lan-a", "wss://public"))
        runCurrent()

        val frames = mutableListOf<String>()
        val collector = backgroundScope.launch {
            racer.events.collect { if (it is GatewayTransportEvent.Frame) frames += it.value.text }
        }
        runCurrent()

        factory.transport("ws://lan-a").receive("""{"kind":"event","seq":1,"time":1}""")
        factory.transport("wss://public").receive("""{"kind":"event","seq":2,"time":2}""")
        runCurrent()

        assertEquals(
            "只有胜者的帧可以进入事件流（握手帧优先，败者帧必须缺席）",
            listOf(hello, """{"kind":"event","seq":2,"time":2}"""),
            frames
        )
        collector.cancel()
    }

    @Test
    fun authenticationFailureIsReportedAsNonRecoverableAggregate() = runTest {
        // 一条网络失败 + 一条认证失败：整场竞速必须按"认证失败"收敛，且不可恢复，
        // 否则运行时会拿已被吊销的 token 反复重连。
        val factory = ScriptedFactory(
            failOnOpen = mapOf(
                "ws://lan-a" to "websocket-failure",
                "wss://public" to "authentication-required"
            ),
            failStatusOnOpen = mapOf("wss://public" to 401)
        )
        val racer = RacingGatewayTransport(factory, backgroundScope, totalBudgetMilliseconds = 5_000)

        racer.open(spec("ws://lan-a", "wss://public"))
        runCurrent()

        val failed = racer.state.value as GatewayTransportState.Failed
        assertEquals("最决定性的失败成为聚合码", 401, failed.httpStatus)
        assertFalse("认证失败必须不可恢复，否则会拿失效 token 反复重连", failed.recoverable)
        val race = failed.race
        assertEquals(2, race?.attempted)
        assertEquals(
            "逐候选失败原因必须保留，便于诊断",
            setOf("websocket-failure", "authentication-required"),
            race?.failures?.map { it.reason }?.toSet()
        )
        assertNull("没有胜者", racer.adoptedEndpoint())
    }

    @Test
    fun allCandidatesFailingEndsTheRaceAndReleasesEverySocket() = runTest {
        val factory = ScriptedFactory(
            failOnOpen = mapOf(
                "ws://lan-a" to "websocket-failure",
                "wss://public" to "websocket-failure"
            )
        )
        val racer = RacingGatewayTransport(factory, backgroundScope, totalBudgetMilliseconds = 5_000)

        racer.open(spec("ws://lan-a", "wss://public"))
        runCurrent()

        val failed = racer.state.value as GatewayTransportState.Failed
        // 原因取最决定性的真实失败码，运行时据此展示与单通道实现一致的错误。
        assertEquals("websocket-failure", failed.reason)
        assertTrue("全部失败必须可恢复，让运行时按既有节奏重试", failed.recoverable)
        assertTrue("所有通道都必须释放", factory.created.all { it.closeCount >= 1 })
    }

    @Test
    fun raceOnceReportsTheWinnerWithoutLeavingSocketsOpen() = runTest {
        val factory = ScriptedFactory(handshakeOnOpen = setOf("wss://public"))
        val racer = RacingGatewayTransport(factory, backgroundScope, totalBudgetMilliseconds = 5_000)

        val outcome: GatewayRaceFailure = racer.raceOnce(spec("ws://lan-a", "wss://public"))
        runCurrent()

        assertEquals("探活必须报告实际可用的那条地址", "wss://public", outcome.winnerEndpoint)
        assertTrue("探活跑完必须释放所有通道", factory.created.all { it.closeCount >= 1 })
    }

    /**
     * 回归：收集者**先于** open() 启动（真实运行时就是如此——Runtime.init 里开始收集）。
     *
     * 此前的实现每轮竞速都换一条新事件流，先启动的收集者会永久绑在已关闭的旧流上，
     * 结果运行时一帧都收不到、连接永远停在 CONNECTING，真机表现为"根本连不上"。
     */
    @Test
    fun collectorStartedBeforeOpenStillReceivesFrames() = runTest {
        val factory = ScriptedFactory(handshakeOnOpen = setOf("wss://public"))
        val racer = RacingGatewayTransport(factory, backgroundScope, totalBudgetMilliseconds = 5_000)

        // 关键：先开始收集，再 open —— 与运行时一致。
        val frames = mutableListOf<String>()
        val collector = backgroundScope.launch {
            racer.events.collect { if (it is GatewayTransportEvent.Frame) frames += it.value.text }
        }
        runCurrent()

        racer.open(spec("ws://lan-a", "wss://public"))
        runCurrent()
        assertEquals("先启动的收集者必须收到胜者握手帧", listOf(hello), frames)

        // 再跑一轮（新代次）：同一收集者必须继续收到新一轮的帧。
        factory.transport("wss://public").receive("""{"kind":"event","seq":3,"time":3}""")
        runCurrent()
        assertEquals(
            "同一收集者必须跨轮次继续收帧",
            listOf(hello, """{"kind":"event","seq":3,"time":3}"""),
            frames
        )
        collector.cancel()
    }

    @Test
    fun pairingCodeIsNeverRacedAcrossCandidates() = runTest {
        val factory = ScriptedFactory(handshakeOnOpen = setOf("ws://lan-a"))
        val racer = RacingGatewayTransport(factory, backgroundScope, totalBudgetMilliseconds = 5_000)

        racer.open(
            GatewayConnectionSpec(
                generation = 1,
                endpoint = "ws://lan-a",
                deviceId = "device",
                pairingCode = "once-only",
                expectedGatewayId = gatewayId,
                candidates = listOf("wss://public")
            )
        )
        runCurrent()

        assertEquals("一次性配对码只能在一个地址上兑换", 1, factory.created.size)
        assertEquals("ws://lan-a", racer.adoptedEndpoint())
    }

    // MARK: - scripted fixtures

    /** 脚本化通道工厂：按地址决定开通道时是否立刻完成握手、或立刻失败。 */
    private class ScriptedFactory(
        private val handshakeOnOpen: Set<String> = emptySet(),
        private val failOnOpen: Map<String, String> = emptyMap(),
        private val failStatusOnOpen: Map<String, Int> = emptyMap()
    ) : GatewaySocketFactory {
        val created = mutableListOf<ScriptedTransport>()

        override fun createChannel(spec: GatewayConnectionSpec, endpoint: String): GatewayTransport =
            ScriptedTransport(
                endpoint = endpoint,
                handshakeOnOpen = endpoint in handshakeOnOpen,
                failReasonOnOpen = failOnOpen[endpoint],
                failStatusOnOpen = failStatusOnOpen[endpoint]
            ).also { created += it }

        fun transport(endpoint: String): ScriptedTransport = created.first { it.endpoint == endpoint }
    }

    private class ScriptedTransport(
        val endpoint: String,
        private val handshakeOnOpen: Boolean,
        private val failReasonOnOpen: String?,
        private val failStatusOnOpen: Int? = null
    ) : GatewayTransport {
        private val mutableState = MutableStateFlow<GatewayTransportState>(GatewayTransportState.Closed())
        private val channel = Channel<GatewayTransportEvent>(Channel.UNLIMITED)
        override val state: StateFlow<GatewayTransportState> = mutableState
        override val events: Flow<GatewayTransportEvent> = channel.receiveAsFlow()
        var closeCount = 0
            private set

        override suspend fun open(spec: GatewayConnectionSpec) {
            mutableState.value = GatewayTransportState.Opening(spec.generation)
            channel.trySend(GatewayTransportEvent.State(mutableState.value))
            val failure = failReasonOnOpen
            if (failure != null) {
                // 带 HTTP 状态的失败（401/4003）按不可恢复处理，与真实网关行为一致。
                fail(
                    reason = failure,
                    httpStatus = failStatusOnOpen,
                    recoverable = failStatusOnOpen == null
                )
                return
            }
            if (handshakeOnOpen) {
                receive("""{"kind":"hello","protocol":3,"gatewayId":"0c5bafb0-5f7e-416b-af94-a6bc39be7204","capabilities":[]}""")
            }
        }

        fun receive(text: String) {
            channel.trySend(GatewayTransportEvent.Frame(GatewayTransportFrame(1, text, text.length)))
        }

        fun fail(
            reason: String,
            httpStatus: Int? = null,
            closeCode: Int? = null,
            recoverable: Boolean = true
        ) {
            val value = GatewayTransportState.Failed(1, httpStatus, closeCode, reason, recoverable)
            mutableState.value = value
            channel.trySend(GatewayTransportEvent.State(value))
        }

        override suspend fun send(text: String) = Unit

        override suspend fun close() {
            closeCount += 1
            channel.close()
        }
    }
}
