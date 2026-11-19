package com.clarklevis.dsh.android.platform

import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.clarklevis.dsh.shared.facade.SharedBotImageTransfer
import com.clarklevis.dsh.shared.facade.ConversationFileReferences
import com.clarklevis.dsh.shared.facade.SharedWorkspaceFileStore
import com.clarklevis.dsh.shared.facade.SharedWorkspaceFileTransition
import com.clarklevis.dsh.shared.gateway.GatewayRequest
import com.clarklevis.dsh.shared.gateway.GatewayRequests
import com.clarklevis.dsh.shared.platform.GatewayAttachmentCache
import com.clarklevis.dsh.shared.protocol.GatewayFrame
import com.clarklevis.dsh.shared.protocol.GatewayImageAttachment
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*

internal data class MarkdownImageAsset(val image: ImageBitmap, val bytes: ByteArray, val width: Int, val height: Int, val mediaType: String)

/** 普通会话与 Bot 共用请求合并、并发、缓存和解码；仅字节读取协议不同。 */
internal class AndroidMarkdownImageLoader(
    private val scope: CoroutineScope,
    private val cache: GatewayAttachmentCache,
    private val send: suspend (GatewayRequest) -> Boolean,
    private val readAttachment: suspend (String, String) -> ByteArray?
) {
    private val images = object : LruCache<String, MarkdownImageAsset>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: MarkdownImageAsset) = value.bytes.size + value.image.width * value.image.height * 4
    }
    private val pending = mutableMapOf<String, Deferred<MarkdownImageAsset>>()
    private val permits = Semaphore(4)
    private data class BotReply(val endpoint: String, val result: CompletableDeferred<JsonObject>)
    private val botReplies = mutableMapOf<String, BotReply>()
    private val downloadMutex = Mutex()
    private val store = SharedWorkspaceFileStore()
    private data class Transfer(
        val id: String,
        val result: CompletableDeferred<ByteArray>,
        val bytes: ByteArrayOutputStream,
        val requestIds: MutableSet<String> = mutableSetOf()
    )
    private var active: Transfer? = null
    private fun key(session: String, source: String) = "markdown:${session.length}:$session$source"
    fun cached(session: String, source: String): MarkdownImageAsset? = images.get(key(session, source))

    suspend fun load(session: String, workspaceSession: String, source: String, supportsResolve: Boolean,
                     reference: GatewayImageAttachment?): MarkdownImageAsset = loadImage(key(session, source), 1024, persist = true) {
        reference?.let { readAttachment(session, it.attachmentId) } ?: cache.read(key(session, source))
            ?: if (source.startsWith("https://", true) || source.startsWith("http://", true)) remote(source)
            else download(session, workspaceSession, source, supportsResolve)
    }

    suspend fun loadBot(session: String, attachment: String?, path: String?, author: String?, preview: Boolean): MarkdownImageAsset {
        val transfer = SharedBotImageTransfer(session, attachment, path, author, preview)
        return loadImage("bot:" + transfer.endpoint + transfer.payloadJson(), if (preview) 1024 else 4096, persist = false) {
            while (!transfer.finished) {
                val part = callBot(transfer.endpoint, Json.parseToJsonElement(transfer.payloadJson()).jsonObject)
                check(transfer.acceptJson(part.toString())) { "Invalid image transfer" }
            }
            Base64.decode(requireNotNull(transfer.dataBase64), Base64.DEFAULT)
        }
    }

    private suspend fun loadImage(key: String, maximumSide: Int, persist: Boolean, read: suspend () -> ByteArray): MarkdownImageAsset =
        withContext(Dispatchers.Main.immediate) {
            images.get(key)?.let { return@withContext it }
            val job = pending[key] ?: scope.async {
                withTimeout(60_000) { permits.withPermit {
                    val bytes = read()
                    val asset = decode(bytes, maximumSide)
                    if (persist) cache.write(key, bytes)
                    images.put(key, asset)
                    asset
                } }
            }.also { created ->
                pending[key] = created
                created.invokeOnCompletion { scope.launch { if (pending[key] === created) pending.remove(key) } }
            }
            try { job.await() } finally { if (job.isCompleted && pending[key] === job) pending.remove(key) }
        }

    private suspend fun callBot(endpoint: String, payload: JsonObject): JsonObject = withTimeout(32_000) {
        val id = "bot-image-${UUID.randomUUID()}"
        val reply = BotReply(endpoint, CompletableDeferred())
        botReplies[id] = reply
        try {
            check(send(GatewayRequests.botCall(endpoint, payload.toString(), id))) { "Not connected" }
            reply.result.await()
        } finally { botReplies.remove(id) }
    }

    private suspend fun download(session: String, workspaceSession: String, source: String, supportsResolve: Boolean): ByteArray =
        downloadMutex.withLock {
            val path = ConversationFileReferences.filePath(source) ?: error("Unsupported image source")
            val transfer = Transfer("markdown-image-${UUID.randomUUID()}", CompletableDeferred(), ByteArrayOutputStream())
            active = transfer
            store.reset(session)
            store.setWorkspaceSession(session, workspaceSession)
            try {
                withTimeout(30_000) {
                    apply(store.resolve(session, path, transfer.id, supportsResolve, useCache = false))
                    transfer.result.await()
                }
            } finally {
                store.cancel().request?.let(::dispatch)
                if (active === transfer) active = null
            }
        }

    fun accept(frame: GatewayFrame, json: String): Boolean {
        val reply = frame.requestId?.let(botReplies::remove)
        if (reply != null) {
            val value = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull()
            val result = value?.get("value") as? JsonObject
            if (frame.kind == "bot-result" && value?.get("endpoint")?.jsonPrimitive?.content == reply.endpoint && result != null) {
                reply.result.complete(result)
            } else reply.result.completeExceptionally(IllegalStateException("Image unavailable"))
            return true
        }
        val transfer = active ?: return false
        val id = store.snapshot().activeDownload?.transferId
        if (frame.requestId !in transfer.requestIds &&
            !(id != null && frame.transferId == id)) return false
        apply(store.acceptFrame(json))
        return true
    }

    fun failed(requestId: String?): Boolean {
        botReplies.remove(requestId)?.let {
            it.result.completeExceptionally(IllegalStateException("Image download failed"))
            return true
        }
        val transfer = active ?: return false
        if (requestId !in transfer.requestIds) return false
        transfer.result.completeExceptionally(IllegalStateException("Image download failed"))
        return true
    }

    fun disconnected() {
        val replies = botReplies.values.toList()
        botReplies.clear()
        replies.forEach { it.result.completeExceptionally(IllegalStateException("Not connected")) }
        active?.result?.completeExceptionally(IllegalStateException("Not connected"))
    }

    fun close() {
        disconnected()
        val abandoned = pending.values.toList()
        pending.clear()
        abandoned.forEach { it.cancel() }
        images.evictAll()
    }

    private fun dispatch(request: GatewayRequest) {
        request.correlationId?.let { active?.requestIds?.add(it) }
        scope.launch { if (!send(request)) failed(request.correlationId) }
    }

    private fun apply(transition: SharedWorkspaceFileTransition) {
        val transfer = active ?: return
        if (transition.snapshot.lastError != null) {
            transition.request?.let(::dispatch)
            transfer.result.completeExceptionally(IllegalStateException(transition.snapshot.lastError))
            return
        }
        if ((transition.snapshot.activeDownload?.size ?: 0) > 32 * 1024 * 1024) {
            transfer.result.completeExceptionally(IllegalStateException("Image too large")); return
        }
        transition.appendBase64Data?.let { encoded ->
            val chunk = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull()
            if (chunk == null || transfer.bytes.size() + chunk.size > 32 * 1024 * 1024) {
                transfer.result.completeExceptionally(IllegalStateException("Invalid image data")); return
            }
            transfer.bytes.write(chunk)
        }
        val completion = transition.completion
        if (completion != null) {
            val bytes = transfer.bytes.toByteArray()
            if (bytes.size.toLong() == completion.size) transfer.result.complete(bytes)
            else transfer.result.completeExceptionally(IllegalStateException("Image size mismatch"))
        } else transition.request?.let(::dispatch)
    }

    private suspend fun remote(source: String): ByteArray = withContext(Dispatchers.IO) {
        val connection = java.net.URL(source).openConnection() as java.net.HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            require(connection.responseCode in 200..299 && connection.contentLengthLong <= 32 * 1024 * 1024)
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 32 * 1024 * 1024)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally { connection.disconnect() }
    }
    companion object {
        suspend fun decode(bytes: ByteArray, maximumSide: Int = 1024): MarkdownImageAsset = withContext(Dispatchers.Default) {
            check(bytes.isNotEmpty() && bytes.size <= AndroidAttachmentThumbnailer.MAXIMUM_COMPRESSED_BYTES)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            check(bounds.outWidth in 1..AndroidAttachmentThumbnailer.MAXIMUM_SOURCE_SIDE &&
                bounds.outHeight in 1..AndroidAttachmentThumbnailer.MAXIMUM_SOURCE_SIDE &&
                bounds.outWidth.toLong() * bounds.outHeight <= AndroidAttachmentThumbnailer.MAXIMUM_SOURCE_PIXELS)
            val bitmap = if (maximumSide > 1024 && android.os.Build.VERSION.SDK_INT >= 28) {
                android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                    val scale = minOf(1.0, maximumSide.toDouble() / maxOf(info.size.width, info.size.height))
                    decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
                }
            } else if (maximumSide > 1024) {
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > maximumSide) inSampleSize *= 2
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: error("Invalid image")
            } else AndroidAttachmentThumbnailer().decode(bytes, maximumSide, maximumSide) ?: error("Invalid image")
            MarkdownImageAsset(bitmap.asImageBitmap(), bytes, bounds.outWidth, bounds.outHeight, bounds.outMimeType ?: "image/png")
        }
    }
}
