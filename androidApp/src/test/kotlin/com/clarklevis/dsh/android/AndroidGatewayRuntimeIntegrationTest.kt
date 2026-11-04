package com.clarklevis.dsh.android

import com.clarklevis.dsh.shared.gateway.GatewayConnectionState
import com.clarklevis.dsh.shared.gateway.GatewayRequests
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
import com.clarklevis.dsh.shared.platform.GatewayTransportFrame
import com.clarklevis.dsh.shared.platform.GatewayTransportState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidGatewayRuntimeIntegrationTest {
    @Test
    fun coldStoredConnectionWaitsForTunnelRoutesBeforeStartingHandshake() = runTest {
        val tunnel = MutableStateFlow(com.clarklevis.dsh.shared.tailscale.TailscaleStatus())
        var route: String? = null
        val transport = FakeTransport().apply {
            onOpening = { route = if (tunnel.value.canConnect) "tailscale" else "direct" }
        }
        val runtime = GatewayRuntime(
            transport,
            FakePreferences(),
            FakeCredentials(),
            FakeAttachmentCache(),
            FakeNetworkMonitor(),
            ImmediateClock,
            backgroundScope
        )
        val startup = async { runtime.connectStoredWhenReady { tunnel.first { it.canConnect } } }
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(transport.specs.isEmpty())
        assertFalse(startup.isCompleted)
        tunnel.value = com.clarklevis.dsh.shared.tailscale.TailscaleStatus(
            stage = com.clarklevis.dsh.shared.tailscale.TailscaleStage.WAITING_ROUTES,
            backendRunning = true,
            peerCount = 0
        )
        runCurrent()
        assertTrue(transport.specs.isEmpty())
        tunnel.value = tunnel.value.copy(routesReady = true)
        runCurrent()
        assertTrue(transport.specs.isEmpty())
        assertFalse(startup.isCompleted)
        tunnel.value = tunnel.value.copy(stage = com.clarklevis.dsh.shared.tailscale.TailscaleStage.READY, peerCount = 5)
        runCurrent()
        assertTrue(startup.await())
        assertEquals("tailscale", route)
        assertEquals("stored-token", transport.specs.single().bearerToken)
        transport.open()
        transport.receive("""{"kind":"hello","protocol":3,"authenticated":true}""")
        runCurrent()
        assertEquals(GatewayConnectionState.CONNECTED, runtime.state.value.connection)
        assertTrue(runtime.connectStoredWhenReady {})
        assertEquals(1, transport.specs.size)
    }

    @Test
    fun manualDisconnectWhileTunnelStartsPreventsLateAutomaticConnection() = runTest {
        val ready = CompletableDeferred<Unit>()
        val transport = FakeTransport()
        val runtime = GatewayRuntime(
            transport,
            FakePreferences(),
            FakeCredentials(),
            FakeAttachmentCache(),
            FakeNetworkMonitor(),
            ImmediateClock,
            backgroundScope
        )
        val startup = async { runtime.connectStoredWhenReady { ready.await() } }
        runCurrent()
        runtime.disconnect()
        ready.complete(Unit)
        runCurrent()
        assertFalse(startup.await())
        assertTrue(transport.specs.isEmpty())
    }

    @Test
    fun sameHostReplacementCleanupPreservesAutomaticConnectionForNextColdLaunch() = runTest {
        val preferences = FakePreferences()
        fun runtime(transport: FakeTransport) = GatewayRuntime(
            transport,
            preferences,
            FakeCredentials(),
            FakeAttachmentCache(),
            FakeNetworkMonitor(),
            ImmediateClock,
            backgroundScope
        )
        val old = runtime(FakeTransport())
        val replacement = runtime(FakeTransport())
        old.connect(preferences.load().endpoint)
        replacement.connect(preferences.load().endpoint)
        // 重新配对同一主机会先在 paired 回调清理，再在 activate 时清理旧图。
        repeat(2) { old.retireConnection() }
        assertTrue(preferences.load().automaticallyConnect)
        replacement.retireConnection()
        val coldTransport = FakeTransport()
        val coldRuntime = runtime(coldTransport)
        val tunnelReady = CompletableDeferred<Unit>()
        val startup = async { coldRuntime.connectStoredWhenReady { tunnelReady.await() } }
        runCurrent()
        assertTrue(coldTransport.specs.isEmpty())
        tunnelReady.complete(Unit)
        runCurrent()
        assertTrue(startup.await())
        assertEquals("stored-token", coldTransport.specs.single().bearerToken)
        coldTransport.open()
        coldTransport.receive("""{"kind":"hello","protocol":3,"authenticated":true}""")
        runCurrent()
        assertEquals(GatewayConnectionState.CONNECTED, coldRuntime.state.value.connection)
    }

    @Test
    fun retiringGraphInvalidatesItsPendingStartupWithoutDisablingReplacement() = runTest {
        val preferences = FakePreferences()
        val ready = CompletableDeferred<Unit>()
        val oldTransport = FakeTransport()
        val replacementTransport = FakeTransport()
        fun runtime(transport: FakeTransport) = GatewayRuntime(
            transport,
            preferences,
            FakeCredentials(),
            FakeAttachmentCache(),
            FakeNetworkMonitor(),
            ImmediateClock,
            backgroundScope
        )
        val old = runtime(oldTransport)
        val replacement = runtime(replacementTransport)
        val oldStartup = async { old.connectStoredWhenReady { ready.await() } }
        runCurrent()
        old.retireConnection()
        val replacementStartup = async { replacement.connectStoredWhenReady { ready.await() } }
        runCurrent()
        ready.complete(Unit)
        runCurrent()
        assertFalse(oldStartup.await())
        assertTrue(oldTransport.specs.isEmpty())
        assertTrue(replacementStartup.await())
        assertEquals(1, replacementTransport.specs.size)
        assertTrue(preferences.load().automaticallyConnect)
    }

    @Test
    fun retiringGraphDoesNotUndoUsersManualDisconnectPreference() = runTest {
        val preferences = FakePreferences()
        val transport = FakeTransport()
        val runtime = GatewayRuntime(
            transport,
            preferences,
            FakeCredentials(),
            FakeAttachmentCache(),
            FakeNetworkMonitor(),
            ImmediateClock,
            backgroundScope
        )
        runtime.disconnect()
        runtime.retireConnection()
        assertFalse(preferences.load().automaticallyConnect)
        assertFalse(runtime.connectStoredWhenReady { error("用户手动断开后不应等待或自动连接") })
        assertTrue(transport.specs.isEmpty())
    }

    @Test
    fun manualConnectionWhileTunnelStartsSupersedesPendingAutomaticConnection() = runTest {
        val preferences = FakePreferences()
        val transport = FakeTransport()
        val runtime = GatewayRuntime(
            transport,
            preferences,
            FakeCredentials(),
            FakeAttachmentCache(),
            FakeNetworkMonitor(),
            ImmediateClock,
            backgroundScope
        )
        val ready = CompletableDeferred<Unit>()
        val startup = async { runtime.connectStoredWhenReady { ready.await() } }
        runCurrent()
        runtime.connect("wss://other-gateway.example/ws/mobile")
        ready.complete(Unit)
        runCurrent()
        assertFalse(startup.await())
        assertEquals("wss://other-gateway.example/ws/mobile", transport.specs.single().endpoint)
    }

    @Test
    fun cancelledColdStartCannotConnectOldGraphAfterTunnelBecomesReady() = runTest {
        val ready = CompletableDeferred<Unit>()
        val transport = FakeTransport()
        val runtime = GatewayRuntime(
            transport,
            FakePreferences(),
            FakeCredentials(),
            FakeAttachmentCache(),
            FakeNetworkMonitor(),
            ImmediateClock,
            backgroundScope
        )
        val startup = async { runtime.connectStoredWhenReady { ready.await() } }
        runCurrent()
        startup.cancel()
        ready.complete(Unit)
        runCurrent()
        assertTrue(startup.isCancelled)
        assertTrue(transport.specs.isEmpty())
    }

    @Test
    fun disabledAutoConnectDoesNotStartOrWaitForTunnel() = runTest {
        val preferences = FakePreferences()
        preferences.update(preferences.load().copy(automaticallyConnect = false))
        val transport = FakeTransport()
        val runtime = GatewayRuntime(
            transport,
            preferences,
            FakeCredentials(),
            FakeAttachmentCache(),
            FakeNetworkMonitor(),
            ImmediateClock,
            backgroundScope
        )
        assertFalse(runtime.connectStoredWhenReady { error("自动连接已关闭，不应触发隧道等待") })
        assertTrue(transport.specs.isEmpty())
    }

    @Test
    fun tailscaleQrPairingAuthenticatesAndConnectsWithoutReopeningTransport() = runTest {
        val endpoint = "ws://100.100.1.19:3081/ws/mobile"
        val raw = FakeTransport()
        val transport = object : com.clarklevis.dsh.shared.platform.GatewayRtcTransport, GatewayTransport by raw {
            override val conversationEvents: Flow<GatewayTransportEvent> = kotlinx.coroutines.flow.emptyFlow()
            override fun resetRtcRecovery() = Unit
        }
        var savedToken: String? = null
        val credentials = object : GatewayCredentialStore by FakeCredentials() {
            override suspend fun saveToken(endpoint: String, token: String) {
                savedToken = token
            }
        }
        val runtime = GatewayRuntime(
            transport,
            FakePreferences(),
            credentials,
            FakeAttachmentCache(),
            FakeNetworkMonitor(),
            ImmediateClock,
            backgroundScope
        )
        runCurrent()
        val json =
            """{"version":2,"publicUrl":"$endpoint","pairingCode":"once","expiresAt":60000,"endpoints":["ws://192.168.1.19:3081/ws/mobile"]}"""
        runtime.pair(kotlin.io.encoding.Base64.UrlSafe.encode(json.encodeToByteArray()).trimEnd('='))
        assertEquals(endpoint, raw.specs.single().endpoint)
        assertEquals("once", raw.specs.single().pairingCode)
        raw.open()
        raw.receive("""{"kind":"paired","token":"paired-token"}""")
        runCurrent()
        raw.receive("""{"kind":"hello","protocol":3,"authenticated":true}""")
        runCurrent()
        assertEquals("paired-token", savedToken)
        assertEquals(GatewayConnectionState.CONNECTED, runtime.state.value.connection)
        assertEquals(1, raw.specs.size)
    }

    @Test
    fun fileFollowUpDoesNotDeadlockWhenConversationFillsRuntimeQueue() = runTest {
        val transport = FakeTransport()
        val runtime = GatewayRuntime(
            transport,
            FakePreferences(),
            FakeCredentials(),
            FakeAttachmentCache(),
            FakeNetworkMonitor(),
            ImmediateClock,
            backgroundScope
        )
        val releaseFileConsumer = CompletableDeferred<Unit>()
        val followUps = AndroidGatewayFollowUpQueue(backgroundScope, onFailure = { throw it })
        val receivedSequences = mutableListOf<Int>()
        var receivedChunks = 0
        backgroundScope.launch {
            runtime.events.collect { event ->
                if (event is GatewayRuntimeEvent.Frame) {
                    if (event.frame.kind == "event") receivedSequences += requireNotNull(event.frame.seq)
                    if (event.frame.kind == "file-download-chunk") {
                        receivedChunks += 1
                        if (receivedChunks == 1) {
                            releaseFileConsumer.await()
                            followUps.submit {
                                runtime.sendRequest(GatewayRequests.fileDownloadRead("transfer-1", 524_288))
                            }
                        }
                    }
                }
            }
        }
        runCurrent()
        assertTrue(runtime.connectStoredIfPaired())
        runCurrent()
        transport.open()
        transport.receive("""{"kind":"hello","protocol":3,"authenticated":true}""")
        runCurrent()
        runtime.sendRequest(GatewayRequests.fileDownloadRead("transfer-1", 0))
        transport.receive("""{"kind":"file-download-chunk","transferId":"transfer-1","offset":0,"data":"YQ==","eof":false}""")
        runCurrent()
        assertEquals(1, receivedChunks)
        // Runtime 的事件队列容量为 8；消费者停在写文件后的下一块请求前。
        repeat(24) { index ->
            transport.receive(
                """{"sessionId":"s1","seq":${index + 1},"time":1,"event":{"type":"assistant/chunk","turn":1,"step":1,"chunkType":"text-delta","text":"x"}}"""
            )
        }
        runCurrent()
        assertTrue(receivedSequences.isEmpty())
        releaseFileConsumer.complete(Unit)
        runCurrent()
        assertEquals((1..24).toList(), receivedSequences)
        assertEquals(2, transport.sentPayloads.count { "file-download-read" in it })
        transport.receive("""{"kind":"file-download-chunk","transferId":"transfer-1","offset":524288,"data":"Yg==","eof":true}""")
        runCurrent()
        assertEquals(2, receivedChunks)
        followUps.close()
    }

    @Test
    fun androidHostReconnectResubscribesAndInvalidatesUnversionedHistory() = runTest {
        val transport = FakeTransport()
        val preferences = FakePreferences()
        val network = FakeNetworkMonitor()
        val runtime = GatewayRuntime(
            transport = transport,
            preferences = preferences,
            credentials = FakeCredentials(),
            attachmentCache = FakeAttachmentCache(),
            networkMonitor = network,
            clock = ImmediateClock,
            scope = backgroundScope
        )
        val projection = AndroidGatewayProjection()
        val frames = mutableListOf<GatewayRuntimeEvent.Frame>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            runtime.events.collect {
                if (it is GatewayRuntimeEvent.Frame) {
                    frames += it
                    projection.acceptFrame(it.rawJson, it.frame, it.correlatedSessionId)
                }
            }
        }
        runCurrent()

        assertTrue(runtime.connectStoredIfPaired())
        runCurrent()
        assertEquals("Bearer token is passed out of ordinary preferences", "stored-token", transport.specs.single().bearerToken)
        transport.open()
        transport.receive("""{"kind":"hello","protocol":3,"authenticated":true}""")
        runCurrent()
        transport.receive("""{"kind":"sessions","items":[]}""")
        runCurrent()
        assertEquals(GatewayConnectionState.CONNECTED, runtime.state.value.connection)
        assertTrue(frames.any { it.frame.kind == "sessions" })

        projection.selectSession("session-a")
        assertTrue(runtime.subscribe("session-a"))
        assertTrue(runtime.requestHistory("session-a"))
        transport.receive(
            """{"kind":"history","events":[{"type":"user/message","seq":1,"time":100,"data":{"content":[{"type":"text","text":"history"}],"source":{"kind":"user"}}}],"hasMore":false,"bytes":64}"""
        )
        transport.receive(
            """{"sessionId":"session-a","seq":2,"time":101,"event":{"type":"assistant/chunk","turn":1,"step":1,"chunkType":"text-delta","text":"partial"}}"""
        )
        transport.receive(
            """{"sessionId":"session-a","seq":3,"time":102,"event":{"type":"assistant/message","turn":1,"step":1,"text":"final"}}"""
        )
        runCurrent()
        assertEquals(listOf("history", "final"), projection.snapshot().conversation.map { it.text })
        assertTrue(transport.sentPayloads.any { "\"type\":\"subscribe\"" in it && "session-a" in it })

        network.mutable.value = GatewayNetworkState.UNAVAILABLE
        runCurrent()
        network.mutable.value = GatewayNetworkState.AVAILABLE
        runCurrent()
        assertEquals(2, transport.specs.size)
        assertEquals("wss://gateway.example/ws/mobile", preferences.load().endpoint)
        transport.open()
        transport.receive("""{"kind":"hello","protocol":3,"authenticated":true}""")
        runCurrent()
        assertTrue(projection.snapshot().conversation.isEmpty())
        assertTrue(transport.sentPayloads.count { "\"type\":\"subscribe\"" in it } >= 2)
        projection.close()
    }

    private class FakeTransport : GatewayTransport {
        private val mutableState = MutableStateFlow<GatewayTransportState>(GatewayTransportState.Closed())
        private val eventsFlow = MutableSharedFlow<GatewayTransportEvent>(extraBufferCapacity = 32)
        val specs = mutableListOf<GatewayConnectionSpec>()
        val sentPayloads = mutableListOf<String>()
        var onOpening: (() -> Unit)? = null
        override val state: StateFlow<GatewayTransportState> = mutableState
        override val events: Flow<GatewayTransportEvent> = eventsFlow
        override suspend fun open(spec: GatewayConnectionSpec) {
            onOpening?.invoke()
            specs += spec
            emitState(GatewayTransportState.Opening(spec.generation))
        }
        override suspend fun send(text: String) {
            sentPayloads += text
        }
        override suspend fun close() {
            emitState(GatewayTransportState.Closed(specs.lastOrNull()?.generation ?: 0))
        }
        fun open() {
            emitState(GatewayTransportState.Open(specs.last().generation))
        }
        fun receive(json: String) {
            assertTrue(
                eventsFlow.tryEmit(
                    GatewayTransportEvent.Frame(
                        GatewayTransportFrame(
                            specs.last().generation,
                            json,
                            json.encodeToByteArray().size
                        )
                    )
                )
            )
        }

        private fun emitState(value: GatewayTransportState) {
            mutableState.value = value
            assertTrue(eventsFlow.tryEmit(GatewayTransportEvent.State(value)))
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

    private class FakeCredentials : GatewayCredentialStore {
        override suspend fun loadOrCreateDeviceId(): String = "android-installation"
        override suspend fun loadToken(endpoint: String): String = "stored-token"
        override suspend fun saveToken(endpoint: String, token: String) = Unit
        override suspend fun deleteToken(endpoint: String) = Unit
    }

    private class FakeAttachmentCache : GatewayAttachmentCache {
        override suspend fun read(attachmentId: String): ByteArray? = null
        override suspend fun write(attachmentId: String, bytes: ByteArray): Boolean = true
        override suspend fun removeExpired() = Unit
    }

    private class FakeNetworkMonitor : GatewayNetworkMonitor {
        val mutable = MutableStateFlow(GatewayNetworkState.AVAILABLE)
        override val state: StateFlow<GatewayNetworkState> = mutable
    }

    private object ImmediateClock : GatewayClock {
        override fun nowEpochMilliseconds(): Long = 0
        override suspend fun delay(milliseconds: Long) = Unit
    }
}
