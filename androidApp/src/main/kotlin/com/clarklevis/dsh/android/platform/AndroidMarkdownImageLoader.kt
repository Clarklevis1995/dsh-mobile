package com.clarklevis.dsh.android.platform

import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.clarklevis.dsh.shared.facade.ConversationFileReferences
import com.clarklevis.dsh.shared.facade.SharedWorkspaceFileStore
import com.clarklevis.dsh.shared.facade.SharedWorkspaceFileTransition
import com.clarklevis.dsh.shared.gateway.GatewayRequest
import com.clarklevis.dsh.shared.platform.GatewayAttachmentCache
import com.clarklevis.dsh.shared.protocol.GatewayFrame
import com.clarklevis.dsh.shared.protocol.GatewayImageAttachment
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class MarkdownImageAsset(val image: ImageBitmap, val bytes: ByteArray, val width: Int, val height: Int, val mediaType: String)

/** 与手动文件预览分开关联请求；同一图片单飞，图片字节与位图缓存均有界。 */
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
                     reference: GatewayImageAttachment?): MarkdownImageAsset = withContext(Dispatchers.Main.immediate) {
        val key = key(session, source)
        images.get(key)?.let { return@withContext it }
        val job = pending[key] ?: scope.async {
            val bytes = reference?.let { readAttachment(session, it.attachmentId) } ?: cache.read(key)
                ?: if (source.startsWith("https://", true) || source.startsWith("http://", true)) remote(source)
                else download(session, workspaceSession, source, supportsResolve)
            val asset = withContext(Dispatchers.Default) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val bitmap = AndroidAttachmentThumbnailer().decode(bytes, 1024, 1024)
                    ?: error("Invalid image")
                MarkdownImageAsset(bitmap.asImageBitmap(), bytes, bounds.outWidth, bounds.outHeight, bounds.outMimeType ?: "image/png")
            }
            cache.write(key, bytes)
            images.put(key, asset)
            asset
        }.also { created -> pending[key] = created; created.invokeOnCompletion { scope.launch { if (pending[key] === created) pending.remove(key) } } }
        job.await()
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
        val transfer = active ?: return false
        val id = store.snapshot().activeDownload?.transferId
        if (frame.requestId !in transfer.requestIds &&
            !(id != null && frame.transferId == id)) return false
        apply(store.acceptFrame(json))
        return true
    }

    fun failed(requestId: String?): Boolean {
        val transfer = active ?: return false
        if (requestId !in transfer.requestIds) return false
        transfer.result.completeExceptionally(IllegalStateException("Image download failed"))
        return true
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
}
