package com.clarklevis.dsh.android.platform

import android.graphics.Bitmap
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.clarklevis.dsh.shared.gateway.GatewayRequest
import com.clarklevis.dsh.shared.platform.GatewayAttachmentCache
import com.clarklevis.dsh.shared.protocol.GatewayImageAttachment
import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidMarkdownImageLoaderDeviceTest {
    private class MemoryCache : GatewayAttachmentCache {
        val values = mutableMapOf<String, ByteArray>()
        override suspend fun read(attachmentId: String) = values[attachmentId]
        override suspend fun write(attachmentId: String, bytes: ByteArray): Boolean {
            values[attachmentId] = bytes
            return true
        }
        override suspend fun removeExpired() = Unit
    }

    private class Gateway(val scope: CoroutineScope, val cache: MemoryCache = MemoryCache()) {
        val bytes = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888).let { bitmap ->
            try {
                ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            } finally { bitmap.recycle() }
        }
        val requests = mutableListOf<GatewayRequest>()
        var gate: CompletableDeferred<Unit>? = null
        var corrupt = false
        var attachment: ByteArray? = null
        var holdBot = false
        val heldBot = mutableListOf<String>()
        val loader = AndroidMarkdownImageLoader(scope, cache, ::send) { _, _ -> attachment }
        private suspend fun send(request: GatewayRequest): Boolean {
            requests += request
            val payload = Json.parseToJsonElement(request.payload).jsonObject
            fun field(name: String) = payload.getValue(name).jsonPrimitive.content
            if (request.requestType == "bot-call") {
                val body = payload.getValue("payload").jsonObject
                val offset = body.getValue("offset").jsonPrimitive.int
                val next = minOf(bytes.size, offset + (bytes.size + 1) / 2)
                val json = buildJsonObject {
                    put("kind", "bot-result"); put("requestId", field("requestId")); put("endpoint", field("endpoint"))
                    put("value", buildJsonObject {
                        put("mime", "image/png"); put("data", Base64.encodeToString(bytes.copyOfRange(offset, next), Base64.NO_WRAP))
                        put("offset", offset); put("nextOffset", next); put("size", bytes.size); put("done", next == bytes.size); put("rev", "v1")
                    })
                }.toString()
                if (holdBot) heldBot += json else assertTrue(loader.accept(GatewayWireDecoder.decode(json), json))
                return true
            }
            val response = when (request.requestType) {
                "file-resolve" -> buildJsonObject {
                    put("kind", "file-resolved"); put("requestId", field("requestId"))
                    put("sessionId", field("sessionId")); put("path", "images/lake.png")
                    put("name", "lake.png"); put("mediaType", "image/png")
                    put("size", bytes.size); put("modifiedAt", 123.0)
                }
                "file-download-open" -> {
                    gate?.await()
                    buildJsonObject {
                        put("kind", "file-download-opened"); put("requestId", field("requestId"))
                        put("sessionId", field("sessionId")); put("path", field("path"))
                        put("transferId", "transfer-${requests.size}"); put("name", "lake.png")
                        put("mediaType", "image/png"); put("size", bytes.size); put("chunkBytes", bytes.size)
                    }
                }
                "file-download-read" -> buildJsonObject {
                    put("kind", "file-download-chunk"); put("transferId", field("transferId"))
                    put("offset", 0); put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)); put("eof", true)
                    put("sha256", if (corrupt) "0".repeat(64) else MessageDigest.getInstance("SHA-256")
                        .digest(bytes).joinToString("") { "%02x".format(it) })
                }
                "file-download-cancel" -> return true
                else -> error("Unexpected image request: ${request.requestType}")
            }.toString()
            assertTrue(loader.accept(GatewayWireDecoder.decode(response), response))
            return true
        }
    }

    @Test fun botAndWorkspaceShareLoaderButKeepSourcesVariantsAndOwnersSeparate() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            withTimeout(10_000) {
                val gateway = Gateway(owner)
                val first = async { gateway.loader.loadBot("archived", null, "images/lake.png", "researcher", true) }
                val duplicate = async { gateway.loader.loadBot("archived", null, "images/lake.png", "researcher", true) }
                assertSame(first.await(), duplicate.await())
                assertEquals(2, gateway.requests.count { it.requestType == "bot-call" })
                gateway.loader.loadBot("archived", null, "images/lake.png", "researcher", true)
                assertEquals(2, gateway.requests.count { it.requestType == "bot-call" })
                gateway.loader.loadBot("archived", null, "images/lake.png", "researcher", false)
                gateway.loader.loadBot("other-member", null, "images/lake.png", "researcher", false)
                assertEquals(6, gateway.requests.count { it.requestType == "bot-call" })
                val firstPayload = Json.parseToJsonElement(gateway.requests[0].payload).jsonObject.getValue("payload").jsonObject
                assertEquals("archived", firstPayload.getValue("sessionId").jsonPrimitive.content)
                assertEquals("researcher", firstPayload.getValue("botId").jsonPrimitive.content)
                gateway.loader.load("archived", "archived", "images/lake.png", true, null)
                assertEquals(1, gateway.requests.count { it.requestType == "file-download-open" })
                assertEquals(1, gateway.cache.values.size)
                // Bot 图片保持内存缓存，普通文件仍沿用已有磁盘缓存。
                assertTrue(gateway.cache.values.keys.none { it.startsWith("bot:") })
            }
        } finally { owner.cancel() }
    }

    @Test fun disconnectReleasesBotWaitersAndRejectsStaleResponsesBeforeRetry() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            withTimeout(10_000) {
                val gateway = Gateway(owner)
                gateway.holdBot = true
                val first = async { runCatching { gateway.loader.loadBot("s", "photo", null, null, false) } }
                while (gateway.heldBot.isEmpty()) yield()
                val stale = gateway.heldBot.first()
                withContext(Dispatchers.Main) { gateway.loader.disconnected() }
                assertTrue(first.await().isFailure)
                withContext(Dispatchers.Main) {
                    assertFalse(gateway.loader.accept(GatewayWireDecoder.decode(stale), stale))
                    gateway.holdBot = false
                }
                val retry = gateway.loader.loadBot("s", "photo", null, null, false)
                assertArrayEquals(gateway.bytes, retry.bytes)
                assertEquals(3, gateway.requests.count { it.requestType == "bot-call" })
                withContext(Dispatchers.Main) { gateway.loader.close() }
            }
        } finally { owner.cancel() }
    }

    @Test fun resolvedWorkspaceImageCoalescesLoadsAndSurvivesLoaderRecreation() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            withTimeout(10_000) {
                val gateway = Gateway(owner)
                gateway.gate = CompletableDeferred()
                val first = async { gateway.loader.load("child", "lead", "/workspace/images/lake.png", true, null) }
                val duplicate = async { gateway.loader.load("child", "lead", "/workspace/images/lake.png", true, null) }
                withContext(Dispatchers.Main) {
                    val unrelated = """{"kind":"file-resolved","requestId":"manual-file","sessionId":"lead","path":"other.png","name":"other.png","size":5}"""
                    assertFalse(gateway.loader.accept(GatewayWireDecoder.decode(unrelated), unrelated))
                    gateway.gate!!.complete(Unit)
                }
                val asset = first.await()
                assertSame(asset, duplicate.await())
                assertEquals(80, asset.width)
                assertEquals(40, asset.height)
                assertArrayEquals(gateway.bytes, asset.bytes)
                assertEquals(1, gateway.requests.count { it.requestType == "file-download-open" })
                assertTrue(gateway.requests.filter { it.targetSessionId != null }.all { it.targetSessionId == "lead" })
                val reopened = Gateway(owner, gateway.cache)
                assertArrayEquals(asset.bytes, reopened.loader.load("child", "lead", "/workspace/images/lake.png", true, null).bytes)
                assertTrue(reopened.requests.isEmpty())
                // 同一路径在其他会话中不能误用旧会话的位图或文件缓存。
                gateway.loader.load("other", "other", "/workspace/images/lake.png", true, null)
                assertEquals(2, gateway.requests.count { it.requestType == "file-download-open" })
            }
        } finally { owner.cancel() }
    }

    @Test fun legacyDownloadRejectsCorruptBytesAndCanRetry() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            withTimeout(10_000) {
                val gateway = Gateway(owner)
                gateway.corrupt = true
                assertTrue(runCatching { gateway.loader.load("s", "s", "images/lake.png", false, null) }.isFailure)
                assertTrue(gateway.cache.values.isEmpty())
                withContext(Dispatchers.Main) { gateway.corrupt = false }
                val asset = gateway.loader.load("s", "s", "images/lake.png", false, null)
                assertEquals(80, asset.width)
                assertEquals(2, gateway.requests.count { it.requestType == "file-download-open" })
                assertEquals(0, gateway.requests.count { it.requestType == "file-resolve" })
            }
        } finally { owner.cancel() }
    }

    @Test fun existingAttachmentBytesAvoidWorkspaceDownload() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val gateway = Gateway(owner)
            gateway.attachment = gateway.bytes
            val reference = GatewayImageAttachment("generated", "image/png", gateway.bytes.size, 80, 40)
            val image = gateway.loader.load("s", "s", "images/lake.png", true, reference)
            assertEquals(80, image.width)
            assertTrue(gateway.requests.isEmpty())
        } finally { owner.cancel() }
    }
}
