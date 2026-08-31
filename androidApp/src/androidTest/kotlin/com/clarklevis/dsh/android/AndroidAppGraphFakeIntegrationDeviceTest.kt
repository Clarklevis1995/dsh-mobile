package com.clarklevis.dsh.android

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clarklevis.dsh.shared.gateway.gatewayAttachmentCacheKey
import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder
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
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidAppGraphFakeIntegrationDeviceTest {
    @Test
    fun injectedProductGraphRunsRuntimeHolderProjectionHistoryAndVisibleAttachment() = runBlocking {
        val transport = FakeTransport()
        val cache = FakeCache()
        val decoderThreads = mutableListOf<Thread>()
        val graph = AndroidAppGraph(
            application = ApplicationProvider.getApplicationContext<Application>(),
            transportOverride = transport,
            preferencesOverride = FakePreferences(),
            credentialStoreOverride = FakeCredentials,
            attachmentCacheOverride = cache,
            networkMonitorOverride = FakeNetwork,
            clockOverride = FakeClock,
            frameDecoderOverride = { raw ->
                synchronized(decoderThreads) { decoderThreads += Thread.currentThread() }
                GatewayWireDecoder.decode(raw)
            }
        )
        val holder = graph.stateHolder
        waitUntil { transport.specs.isNotEmpty() }
        transport.open()
        transport.receive("""{"kind":"hello","authenticated":true,"protocol":3}""")
        waitUntil { holder.gatewayState.connection.name == "CONNECTED" }
        transport.receive("""{"kind":"pong","message":"${"x".repeat(1_000_000)}"}""")
        waitUntil { synchronized(decoderThreads) { decoderThreads.size >= 2 } }
        assertTrue(synchronized(decoderThreads) { decoderThreads.all { it !== Looper.getMainLooper().thread } })

        onMain { holder.loadFixture() }
        val historyBefore = transport.sentTypes.count { it == "history" }
        onMain { holder.selectSession("android-demo") }
        waitUntil { transport.sentTypes.count { it == "history" } > historyBefore }
        val initialHistoryRequest = transport.payloadsOfType("history").last()
        assertEquals(60, initialHistoryRequest.getValue("maxMessages").jsonPrimitive.int)
        assertEquals(4 * 1_024 * 1_024, initialHistoryRequest.getValue("maxBytes").jsonPrimitive.int)
        assertEquals("conversation", initialHistoryRequest.getValue("view").jsonPrimitive.content)
        transport.receive(
            """{"kind":"history","sessionId":"android-demo","events":[{"type":"user/message","seq":1,"time":1,"data":{"content":[{"type":"text","text":"product-history"}],"source":{"kind":"user"}}}],"hasMore":true,"nextBeforeSeq":0,"bytes":64}"""
        )
        waitUntil { holder.snapshot.conversation.any { it.text == "product-history" } }
        delay(100)
        assertEquals(historyBefore + 1, transport.sentTypes.count { it == "history" })

        onMain { holder.loadOlderHistory() }
        waitUntil { transport.sentTypes.count { it == "history" } == historyBefore + 2 }
        val olderHistoryRequest = transport.payloadsOfType("history").last()
        assertEquals(1, olderHistoryRequest.getValue("beforeSeq").jsonPrimitive.int)
        assertEquals("conversation", olderHistoryRequest.getValue("view").jsonPrimitive.content)
        transport.receive(
            """{"kind":"history","sessionId":"android-demo","events":[],"hasMore":false,"bytes":0}"""
        )
        waitUntil { !holder.snapshot.selectedHistoryIsLoading }

        assertTrue(graph.gatewayRuntime.requestHistory("android-demo"))
        waitUntil { transport.sentTypes.count { it == "history" } >= historyBefore + 2 }
        transport.receive(
            """{"kind":"error","requestType":"history","sessionId":"android-demo","code":"synthetic"}"""
        )
        waitUntil { holder.platformError?.startsWith("history:") == true }
        val beforeRetry = transport.sentTypes.count { it == "history" }
        assertTrue(graph.gatewayRuntime.requestHistory("android-demo"))
        waitUntil { transport.sentTypes.count { it == "history" } > beforeRetry }

        val chunks = List(1_200) { "长" }
        chunks.forEachIndexed { index, text ->
            transport.receive(
                """{"sessionId":"android-demo","seq":${index + 2},"time":${index + 2},"event":{"type":"assistant/chunk","turn":2,"step":1,"chunkType":"text-delta","text":"$text"}}"""
            )
        }
        val finalText = chunks.joinToString("")
        transport.receive(
            """{"sessionId":"android-demo","seq":1202,"time":1202,"event":{"type":"assistant/message","turn":2,"step":1,"text":"$finalText"}}"""
        )
        waitUntil {
            holder.snapshot.conversation.any { it.text == finalText } &&
                holder.snapshot.conversation.none { it.id.startsWith("stream-") }
        }
        assertTrue(holder.snapshot.conversation.none { it.id.startsWith("stream-") })

        val png = tinyPng()
        transport.receive(
            """{"sessionId":"android-demo","seq":1300,"time":1300,"event":{"type":"assistant/message","turn":2,"step":1,"text":"image","images":[{"attachmentId":"same-id","mediaType":"image/png","bytes":${png.size},"width":2,"height":2}]}}"""
        )
        waitUntil { holder.snapshot.conversation.any { item -> item.images.any { it.attachmentId == "same-id" } } }
        onMain { holder.updateVisibleAttachments(setOf("same-id")) }
        waitUntil { transport.sentTypes.lastOrNull() == "attachment" }
        transport.receive(
            """{"kind":"attachment","sessionId":"android-demo","attachment":{"attachmentId":"same-id","mediaType":"image/png","bytes":${png.size},"width":2,"height":2},"data":"${Base64.encodeToString(png, Base64.NO_WRAP)}"}"""
        )
        waitUntil { holder.attachmentStates["same-id"] == AttachmentLoadState.LOADED }
        assertTrue("same-id" in holder.attachmentThumbnails)
        assertTrue(gatewayAttachmentCacheKey("android-demo", "same-id") in cache.values)
    }

    private suspend fun waitUntil(predicate: () -> Boolean) {
        withTimeout(5_000) {
            while (!predicate()) delay(20)
        }
    }

    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    private fun tinyPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private class FakeTransport : GatewayTransport {
        private val mutableState = MutableStateFlow<GatewayTransportState>(GatewayTransportState.Closed())
        private val mutableEvents = MutableSharedFlow<GatewayTransportEvent>(extraBufferCapacity = 2_048)
        val specs = mutableListOf<GatewayConnectionSpec>()
        private val payloads = mutableListOf<String>()
        val sentTypes: List<String>
            get() = synchronized(payloads) {
                payloads.map { Json.parseToJsonElement(it).jsonObject.getValue("type").jsonPrimitive.content }
            }
        fun payloadsOfType(type: String) = synchronized(payloads) {
            payloads.map { Json.parseToJsonElement(it).jsonObject }
                .filter { it.getValue("type").jsonPrimitive.content == type }
        }
        override val state: StateFlow<GatewayTransportState> = mutableState
        override val events: Flow<GatewayTransportEvent> = mutableEvents

        override suspend fun open(spec: GatewayConnectionSpec) {
            synchronized(specs) { specs += spec }
            emit(GatewayTransportState.Opening(spec.generation))
        }

        override suspend fun send(text: String) {
            synchronized(payloads) { payloads += text }
        }

        override suspend fun close() {
            emit(GatewayTransportState.Closed(specs.lastOrNull()?.generation ?: 0))
        }

        fun open() = emit(GatewayTransportState.Open(specs.last().generation))

        fun receive(json: String) {
            check(
                mutableEvents.tryEmit(
                    GatewayTransportEvent.Frame(
                        GatewayTransportFrame(specs.last().generation, json, json.toByteArray().size)
                    )
                )
            )
        }

        private fun emit(value: GatewayTransportState) {
            mutableState.value = value
            check(mutableEvents.tryEmit(GatewayTransportEvent.State(value)))
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

    private object FakeCredentials : GatewayCredentialStore {
        override suspend fun loadOrCreateDeviceId(): String = "device"
        override suspend fun loadToken(endpoint: String): String = "token"
        override suspend fun saveToken(endpoint: String, token: String) = Unit
        override suspend fun deleteToken(endpoint: String) = Unit
    }

    private class FakeCache : GatewayAttachmentCache {
        val values = mutableMapOf<String, ByteArray>()
        override suspend fun read(attachmentId: String): ByteArray? = values[attachmentId]
        override suspend fun write(attachmentId: String, bytes: ByteArray): Boolean {
            values[attachmentId] = bytes
            return true
        }
        override suspend fun removeExpired() = Unit
    }

    private object FakeNetwork : GatewayNetworkMonitor {
        override val state: StateFlow<GatewayNetworkState> =
            MutableStateFlow(GatewayNetworkState.AVAILABLE)
    }

    private object FakeClock : GatewayClock {
        override fun nowEpochMilliseconds(): Long = 0
        override suspend fun delay(milliseconds: Long) = kotlinx.coroutines.delay(milliseconds)
    }
}
