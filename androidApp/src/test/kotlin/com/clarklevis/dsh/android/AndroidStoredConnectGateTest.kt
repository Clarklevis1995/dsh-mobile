package com.clarklevis.dsh.android

import com.clarklevis.dsh.shared.gateway.GatewayConnectionState
import com.clarklevis.dsh.shared.gateway.GatewayRuntime
import com.clarklevis.dsh.shared.gateway.GatewayRuntimeEvent
import com.clarklevis.dsh.shared.platform.GatewayAttachmentCache
import com.clarklevis.dsh.shared.platform.GatewayClock
import com.clarklevis.dsh.shared.platform.GatewayConnectionSpec
import com.clarklevis.dsh.shared.platform.GatewayCredentialStore
import com.clarklevis.dsh.shared.platform.GatewayNetworkMonitor
import com.clarklevis.dsh.shared.platform.GatewayNetworkState
import com.clarklevis.dsh.shared.platform.GatewayPreferences
import com.clarklevis.dsh.shared.platform.GatewayPreferencesSnapshot
import com.clarklevis.dsh.shared.platform.GatewayTransport
import com.clarklevis.dsh.shared.platform.GatewayTransportEvent
import com.clarklevis.dsh.shared.platform.GatewayTransportState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归：Android 冷启动自动连接（stored-connect）。
 *
 * 原实现只调用一次 `connectStoredIfPaired()` 并忽略它的布尔返回值：凭据此刻读不到
 * （Keystore 未就绪、DataStore 首次读取失败、配对写入尚未完成）时静默放弃，
 * `desiredConnection` 保持 false，回前台也不会重连；只有手动点“连接”
 * （`connect(endpoint)`，完全不看凭据）才能连上——即“一直挂起、点一下就秒连”。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidStoredConnectGateTest {
    @Test
    fun storedConnectRetriesWhileTokenIsNotYetWrittenThenConnectsWithBearerToken() = runTest {
        val transport = FakeTransport()
        // 真实时序：DataStore/Keystore 冷启动时读到空值（不抛异常），稍后才可用；
        // 这与 AndroidGatewayCredentialStore 解密失败时静默返回 null 的行为一致。
        val credentials = CountingCredentials(token = "stored-token", blankLoads = 2)
        val runtime = newRuntime(transport, credentials, backgroundScope, testScheduler)

        assertTrue(runtime.connectStoredIfPairedWithRetry())
        runCurrent()

        // 读取凭据与发起连接是两件事：每次重试先读凭据，读到才连。
        // 前三次读到空值（含首次），第四次拿到 token 并带 Bearer 连上。
        assertEquals("未就绪的凭据必须重试而不是静默放弃", 4, credentials.loads)
        assertTrue("重试必须有界", credentials.loads <= 4)
        assertEquals("真正连上时必须带上设备 token", "stored-token", transport.specs.single().bearerToken)
        assertEquals(GatewayConnectionState.CONNECTING, runtime.state.value.connection)
    }

    @Test
    fun storedConnectReportsMissingCredentialInsteadOfSilentlyStayingDisconnected() = runTest {
        val transport = FakeTransport()
        val credentials = CountingCredentials(token = null)
        val runtime = newRuntime(transport, credentials, backgroundScope, testScheduler)

        assertFalse(runtime.connectStoredIfPairedWithRetry())
        runCurrent()

        assertEquals("从未配对的主机只在有界次数内探测，不空转", 4, credentials.loads)
        assertTrue("不得无界自旋", credentials.loads <= 4)
        assertTrue("未发起连接", transport.specs.isEmpty())
        assertEquals(GatewayConnectionState.FAILED, runtime.state.value.connection)
        assertEquals("stored-credential-missing", runtime.state.value.lastError)
    }

    @Test
    fun storedConnectReportsCredentialAccessFailureInsteadOfHungConnectingState() = runTest {
        val transport = FakeTransport()
        val credentials = CountingCredentials(token = "stored-token", throwOnEveryLoad = true)
        val runtime = newRuntime(transport, credentials, backgroundScope, testScheduler)

        assertFalse(runtime.connectStoredIfPairedWithRetry())
        runCurrent()

        assertEquals("凭据读取异常不得被当成“未配对”重试", 1, credentials.loads)
        assertEquals(GatewayConnectionState.FAILED, runtime.state.value.connection)
        assertEquals("credential-access-failed", runtime.state.value.lastError)
    }

    @Test
    fun failedStoredConnectIsNotRescuedByForegroundReturn() = runTest {
        val transport = FakeTransport()
        val credentials = CountingCredentials(token = null)
        val runtime = newRuntime(transport, credentials, backgroundScope, testScheduler)

        assertFalse(runtime.connectStoredIfPairedWithRetry())
        runCurrent()
        // 用户回到前台：原实现下 desiredConnection 始终为 false，因此不会重连。
        runtime.applicationDidBecomeActive()
        advanceTimeBy(5_000)
        runCurrent()

        assertTrue("凭据门禁失败后回前台不会自愈，这正是必须显式上报的原因", transport.specs.isEmpty())
        assertEquals(GatewayConnectionState.FAILED, runtime.state.value.connection)
        assertEquals("stored-credential-missing", runtime.state.value.lastError)
    }

    private fun newRuntime(
        transport: FakeTransport,
        credentials: GatewayCredentialStore,
        scope: kotlinx.coroutines.CoroutineScope,
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler
    ): GatewayRuntime =
        GatewayRuntime(
            transport = transport,
            preferences = FakePreferences(),
            credentials = credentials,
            attachmentCache = FakeAttachmentCache(),
            networkMonitor = FakeNetworkMonitor(),
            clock = VirtualClock(scheduler),
            scope = scope
        )

    /**
     * 回归：握手进行中（socket 已开、hello 未到）提交的请求必须**排队**而不是被拒绝。
     *
     * 前台恢复后 UI 会立刻刷新，而"运行时进入 CONNECTED"与"事件发布到 UI"之间存在窗口。
     * 此前该窗口内的请求被 sendRequestLocked 判为 not-connected（用户看到的
     * "sessions: not-connected"），因此同一个操作有时报错、有时正常。
     */
    @Test
    fun requestsSubmittedDuringHandshakeAreQueuedThenReplayedOnHello() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(
            transport,
            CountingCredentials(token = "stored-token"),
            backgroundScope,
            testScheduler
        )
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        runCurrent()

        // socket 已开、hello 未到：运行时处于 AUTHENTICATING。
        transport.opened()
        runCurrent()
        assertEquals(GatewayConnectionState.AUTHENTICATING, runtime.state.value.connection)

        val rejections = mutableListOf<GatewayRuntimeEvent.RequestRejected>()
        backgroundScope.launch {
            runtime.events.collect { if (it is GatewayRuntimeEvent.RequestRejected) rejections += it }
        }
        runCurrent()

        assertTrue("握手窗口内的请求应当被接受（排队）", runtime.requestSessions())
        runCurrent()
        assertFalse(
            "握手窗口内的请求不得被判为未连接：${rejections.map { it.reason }}",
            rejections.any { it.reason == "not-connected" }
        )

        transport.receive("""{"kind":"hello","protocol":3,"authenticated":true}""")
        runCurrent()
        assertEquals(GatewayConnectionState.CONNECTED, runtime.state.value.connection)
        assertTrue(
            "排队的 sessions 请求必须在 hello 之后补发",
            transport.sentPayloads.any { it.contains("\"type\":\"sessions\"") }
        )
    }

    private class CountingCredentials(
        private val token: String?,
        private val unreadableLoads: Int = 0,
        private val blankLoads: Int = 0,
        private val throwOnEveryLoad: Boolean = false
    ) : GatewayCredentialStore {
        var loads = 0
            private set

        override suspend fun loadOrCreateDeviceId(): String = "android-installation"

        override suspend fun loadToken(endpoint: String): String? {
            loads += 1
            if (throwOnEveryLoad || loads <= unreadableLoads) throw IllegalStateException("keystore unavailable")
            if (loads <= unreadableLoads + blankLoads) return ""
            return token
        }

        override suspend fun saveToken(endpoint: String, token: String) = Unit
        override suspend fun deleteToken(endpoint: String) = Unit
    }

    private class FakeTransport : GatewayTransport {
        private val mutableState = MutableStateFlow<GatewayTransportState>(GatewayTransportState.Closed())
        private val eventsFlow = MutableSharedFlow<GatewayTransportEvent>(extraBufferCapacity = 32)
        val specs = mutableListOf<GatewayConnectionSpec>()
        val sentPayloads = mutableListOf<String>()
        override val state: StateFlow<GatewayTransportState> = mutableState
        override val events: Flow<GatewayTransportEvent> = eventsFlow

        override suspend fun open(spec: GatewayConnectionSpec) {
            specs += spec
            emitState(GatewayTransportState.Opening(spec.generation))
        }

        fun opened() = emitState(GatewayTransportState.Open(specs.last().generation))

        fun receive(json: String) {
            eventsFlow.tryEmit(
                GatewayTransportEvent.Frame(
                    com.clarklevis.dsh.shared.platform.GatewayTransportFrame(
                        specs.last().generation,
                        json,
                        json.encodeToByteArray().size
                    )
                )
            )
        }

        override suspend fun send(text: String) {
            sentPayloads += text
        }
        override suspend fun close() = emitState(GatewayTransportState.Closed(specs.lastOrNull()?.generation ?: 0))

        private fun emitState(value: GatewayTransportState) {
            mutableState.value = value
            eventsFlow.tryEmit(GatewayTransportEvent.State(value))
        }
    }

    private class FakePreferences : GatewayPreferences {
        private val value = MutableStateFlow(
            GatewayPreferencesSnapshot(endpoint = "wss://gateway.example/ws/mobile")
        )
        override val snapshots: Flow<GatewayPreferencesSnapshot> = value
        override suspend fun load(): GatewayPreferencesSnapshot = value.value
        override suspend fun update(snapshot: GatewayPreferencesSnapshot) {
            value.value = snapshot
        }
    }

    private class FakeAttachmentCache : GatewayAttachmentCache {
        override suspend fun read(attachmentId: String): ByteArray? = null
        override suspend fun write(attachmentId: String, bytes: ByteArray): Boolean = true
        override suspend fun removeExpired() = Unit
    }

    private class FakeNetworkMonitor : GatewayNetworkMonitor {
        override val state: StateFlow<GatewayNetworkState> = MutableStateFlow(GatewayNetworkState.AVAILABLE)
    }

    /** 跟随 runTest 虚拟时间的时钟：否则时间上限不可测，重试可能无界自旋。 */
    private class VirtualClock(private val scheduler: kotlinx.coroutines.test.TestCoroutineScheduler) : GatewayClock {
        override fun nowEpochMilliseconds(): Long = scheduler.currentTime
        override suspend fun delay(milliseconds: Long) {
            kotlinx.coroutines.delay(milliseconds)
        }
    }
}
