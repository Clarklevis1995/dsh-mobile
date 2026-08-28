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
import com.clarklevis.dsh.shared.platform.GatewayTransportFrame
import com.clarklevis.dsh.shared.platform.GatewayTransportEvent
import com.clarklevis.dsh.shared.platform.GatewayTransportState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidGatewayRuntimeIntegrationTest {
    @Test
    fun androidHostReplaysFakeGatewayWithoutLosingStateAcrossReconnect() = runTest {
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
        assertEquals(listOf("history", "final"), projection.snapshot().conversation.map { it.text })
        assertTrue(transport.sentPayloads.count { "\"type\":\"subscribe\"" in it } >= 2)
        projection.close()
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
