package com.clarklevis.dsh.android

import android.app.Application
import java.util.UUID
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

import kotlinx.coroutines.async

@RunWith(AndroidJUnit4::class)
class IncomingShareConversationDeviceTest {
    @Test fun completedDraftReconnectDoesNotUploadAgain() = runBlocking<Unit> {
        exerciseUploadReconnect(pauseFirstUpload = false)
    }

    @Test fun interruptedDraftReconnectUploadsPrivateCopyAutomatically() = runBlocking<Unit> {
        exerciseUploadReconnect(pauseFirstUpload = true)
    }

    private suspend fun exerciseUploadReconnect(pauseFirstUpload: Boolean) {
        val transport = FakeTransport()
        val application = ApplicationProvider.getApplicationContext<Application>()
        val graph = AndroidAppGraph(application = application, transportOverride = transport,
            preferencesOverride = FakePreferences(), credentialStoreOverride = FakeCredentials,
            attachmentCacheOverride = FakeCache(), networkMonitorOverride = FakeNetwork, clockOverride = FakeClock,
            gatewayLocalId = "upload-reconnect-${UUID.randomUUID()}")
        val holder = graph.stateHolder
        val source = java.io.File(application.cacheDir, "workspace-files/upload-test-${UUID.randomUUID()}/chat.zip")
        var sourceLease: java.io.Closeable? = null
        val original = byteArrayOf(0, 1, 2, 0, -1, 42, -128)
        val opened = java.util.concurrent.atomic.AtomicInteger()
        val received = java.io.ByteArrayOutputStream()
        var digest = ""
        var upload = ""
        transport.onSend = handler@ { text ->
            val request = Json.parseToJsonElement(text).jsonObject
            val id = request["requestId"]?.jsonPrimitive?.content ?: ""
            when (request["type"]?.jsonPrimitive?.content) {
                "session-create" -> transport.receive("""{"kind":"session-created","requestId":"$id","sessionId":"upload-session"}""")
                "file-upload-open" -> {
                    val number = opened.incrementAndGet()
                    if (pauseFirstUpload && number == 1) return@handler
                    received.reset()
                    digest = request.getValue("sha256").jsonPrimitive.content
                    upload = "upload-$number"
                    transport.receive("""{"kind":"file-upload-opened","requestId":"$id","sessionId":"upload-session","uploadId":"$upload","size":7,"chunkBytes":3}""")
                }
                "file-upload-chunk" -> {
                    val bytes = android.util.Base64.decode(request.getValue("data").jsonPrimitive.content, android.util.Base64.DEFAULT)
                    received.write(bytes)
                    val offset = request.getValue("offset").jsonPrimitive.content.toLong()
                    transport.receive("""{"kind":"file-upload-chunk-accepted","requestId":"$id","sessionId":"upload-session","uploadId":"$upload","nextOffset":${offset + bytes.size}}""")
                }
                "file-upload-finish" -> transport.receive("""{"kind":"file-upload-completed","requestId":"$id","sessionId":"upload-session","uploadId":"$upload","receiptId":"receipt-${opened.get()}","sha256":"$digest","file":{"attachmentId":"attachment-${opened.get()}","name":"chat.zip","bytes":7}}""")
            }
        }
        fun hello() = transport.receive("""{"kind":"hello","authenticated":true,"protocol":3,"capabilities":["session-create","file-upload-v1"]}""")
        try {
            waitUntil("开始连接") { transport.specs.isNotEmpty() }
            transport.open()
            hello()
            waitUntil("连接及上传能力就绪") { holder.gatewayState.connection.name == "CONNECTED" && holder.fileUploads.supported }
            val creation = graph.gatewayScope.async { holder.prepareIncomingShareConversation(null) }
            assertEquals("upload-session", withTimeout(5_000) { creation.await() })
            val storage = AndroidCacheStorage.shared(application)
            sourceLease = storage.access {
                source.parentFile!!.mkdirs()
                source.writeBytes(original)
                storage.lease(source.parentFile!!)
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(application,
                "${application.packageName}.workspace-files", source)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                holder.fileUploads.select("upload-session", true)
                holder.fileUploads.importFiles(listOf(uri))
            }
            waitUntil("初次上传") { opened.get() == 1 && (pauseFirstUpload || holder.fileUploads.canSend) }
            val draftId = holder.fileUploads.drafts.single().id
            val submission = holder.fileUploads.submission("分析文件", "")
            source.delete()
            transport.fail()
            waitUntil("处理断线") { !holder.fileUploads.supported }
            if (!pauseFirstUpload) assertEquals("ready", holder.fileUploads.drafts.single().status)
            val previousConnections = transport.specs.size
            graph.gatewayRuntime.connect("wss://gateway.example/ws/mobile")
            waitUntil("替换连接") { transport.specs.size > previousConnections }
            transport.open()
            hello()
            waitUntil("重连恢复附件") { holder.fileUploads.canSend }
            assertEquals(if (pauseFirstUpload) 2 else 1, opened.get())
            assertEquals(listOf("receipt-${opened.get()}"), holder.fileUploads.receipts)
            assertEquals(draftId, holder.fileUploads.drafts.single().id)
            org.junit.Assert.assertArrayEquals(original, received.toByteArray())
            if (!pauseFirstUpload) assertEquals(submission, holder.fileUploads.submission("分析文件", ""))

            val beforeRenewal = opened.get()
            onMain { holder.fileUploads.invalidReceipts() }
            waitUntil("失效凭证恢复") { opened.get() == beforeRenewal + 1 && holder.fileUploads.canSend }
            assertEquals(listOf("receipt-${beforeRenewal + 1}"), holder.fileUploads.receipts)
            org.junit.Assert.assertArrayEquals(original, received.toByteArray())
            assertTrue(transport.payloadsOfType("message").isEmpty())
            assertTrue(transport.payloadsOfType("prompt").isEmpty())
        } catch (failure: Throwable) {
            throw AssertionError("附件恢复失败: connection=${holder.gatewayState.connection} supported=${holder.fileUploads.supported} " +
                "session=${holder.fileUploads.sessionId} selected=${holder.snapshot.selectedSessionId} drafts=${holder.fileUploads.drafts} " +
                "opened=${opened.get()} requests=${transport.sentTypes} error=${holder.platformError}", failure)
        } finally {
            sourceLease?.close()
            source.delete()
            source.parentFile?.delete()
            onMain { holder.close() }
        }
    }

    @Test fun newShareUsesExplicitWorkspaceAndReturnsCreatedConversation() = runBlocking<Unit> {
        val transport = FakeTransport()
        val graph = AndroidAppGraph(
            application = ApplicationProvider.getApplicationContext<Application>(),
            transportOverride = transport, preferencesOverride = FakePreferences(),
            credentialStoreOverride = FakeCredentials, attachmentCacheOverride = FakeCache(),
            networkMonitorOverride = FakeNetwork, clockOverride = FakeClock,
            gatewayLocalId = "share-create-${UUID.randomUUID()}"
        )
        val holder = graph.stateHolder
        try {
            waitUntil { transport.specs.isNotEmpty() }
            transport.open()
            transport.receive("""{"kind":"hello","authenticated":true,"protocol":3,"historyFormatVersion":1,"capabilities":["session-create","message-quotes-v1"]}""")
            waitUntil { holder.gatewayState.connection.name == "CONNECTED" }
            transport.receive("""{"kind":"workspaces","items":[
                {"workspaceId":"home","path":"/home","title":"Home","sessionIds":[],"createdAt":"","updatedAt":""},
                {"workspaceId":"target","path":"/target","title":"Target","sessionIds":[],"createdAt":"","updatedAt":""}]}""")
            waitUntil { holder.availableWorkspaces.size == 2 }
            onMain { holder.selectWorkspace("home") }
            val result = graph.gatewayScope.async { holder.prepareIncomingShareConversation("target") }
            waitUntil { transport.payloadsOfType("session-create").isNotEmpty() }
            val request = transport.payloadsOfType("session-create").last()
            assertEquals("target", request.getValue("workspaceId").jsonPrimitive.content)
            val requestId = request.getValue("requestId").jsonPrimitive.content
            transport.receive("""{"kind":"session-created","requestId":"$requestId","sessionId":"created-for-share"}""")
            assertEquals("created-for-share", withTimeout(5_000) { result.await() })
            waitUntil { holder.snapshot.selectedSessionId == "created-for-share" }
            assertEquals("home", holder.selectedWorkspaceId)
            onMain { holder.messageDraft = "existing draft" }
            val shared = IncomingShare("id", "https://example.com/research", emptyList())
            assertTrue(graph.gatewayScope.async { holder.applyIncomingShare(shared) }.await())
            assertTrue(holder.messageDraft.contains("existing draft"))
            assertTrue(holder.messageDraft.contains("https://example.com/research"))
            assertTrue(transport.payloadsOfType("prompt").isEmpty())
            assertTrue(transport.payloadsOfType("message").isEmpty())
        } finally { onMain { holder.close() } }
    }

    private suspend fun waitUntil(description: String = "状态更新", predicate: () -> Boolean) {
        try {
            withTimeout(5_000) {
                while (!predicate()) delay(20)
            }
        } catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("等待超时：$description", failure)
        }
    }

    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    private class FakeTransport : GatewayTransport {
        private val mutableState = MutableStateFlow<GatewayTransportState>(GatewayTransportState.Closed())
        private val mutableEvents = MutableSharedFlow<GatewayTransportEvent>(extraBufferCapacity = 2_048)
        val specs = mutableListOf<GatewayConnectionSpec>()
        private val payloads = mutableListOf<String>()
        var onSend: ((String) -> Unit)? = null
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
            onSend?.invoke(text)
        }

        override suspend fun close() {
            emit(GatewayTransportState.Closed(specs.lastOrNull()?.generation ?: 0))
        }

        fun open() = emit(GatewayTransportState.Open(specs.last().generation))
        fun fail() = emit(GatewayTransportState.Failed(specs.last().generation))

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
