package com.clarklevis.dsh.android.platform

import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.clarklevis.dsh.android.AndroidAppGraph
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.shared.facade.SharedFileUploadStore
import com.clarklevis.dsh.shared.facade.SharedUploadDraft
import com.clarklevis.dsh.shared.facade.SharedUploadTransition
import com.clarklevis.dsh.shared.i18n.UiMessage
import com.clarklevis.dsh.shared.i18n.UiMessageException
import com.clarklevis.dsh.shared.protocol.GatewayFrame
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 元数据和协议在共享层，Android 只持有私有草稿及逐块文件 I/O。 */
class AndroidFileUploadController(
    private val graph: AndroidAppGraph?,
    private val scope: CoroutineScope?,
    private val error: (String) -> Unit
) {
    private val shared = SharedFileUploadStore()
    private val localFiles = mutableMapOf<String, File>()
    private var connectionKey: String? = null
    private val timeouts = mutableMapOf<String, Job>()
    private var context = UUID.randomUUID()
    var sessionId: String? = null
        private set
    var supported by mutableStateOf(false)
        private set
    var drafts: List<SharedUploadDraft> by mutableStateOf(emptyList())
        private set
    var preparing by mutableStateOf(false)
        private set
    val canSend get() = !preparing && shared.canSend()
    val receipts get() = shared.receipts()

    fun connected(sessionId: String?, supported: Boolean) {
        context = UUID.randomUUID()
        preparing = false
        connectionKey = UUID.randomUUID().toString()
        this.supported = supported
        this.sessionId = sessionId
        apply(shared.bind(sessionId, connectionKey, supported))
    }
    fun disconnected() {
        supported = false
        context = UUID.randomUUID()
        preparing = false
        connectionKey = null
        timeouts.values.forEach { it.cancel() }; timeouts.clear()
        apply(shared.bind(sessionId, null, false))
    }
    fun select(sessionId: String?, allowed: Boolean) {
        if (this.sessionId != sessionId) {
            context = UUID.randomUUID()
            preparing = false
            clear()
            this.sessionId = sessionId
        }
        apply(shared.bind(sessionId, connectionKey, supported && allowed))
    }
    fun retry(id: String) = apply(shared.retry(id))
    fun remove(id: String) {
        apply(shared.remove(id))
        localFiles.remove(id)?.delete()
    }
    fun clear() {
        context = UUID.randomUUID()
        preparing = false
        timeouts.values.forEach { it.cancel() }; timeouts.clear()
        apply(shared.clear())
        localFiles.values.forEach(File::delete)
        localFiles.clear()
    }
    fun accepted(ids: List<String>) = ids.forEach(::remove)
    fun invalidReceipts() = apply(shared.invalidateReceipts())
    fun submission(text: String, imageKey: String): String = shared.submission(text, imageKey, UUID.randomUUID().toString())
    fun accept(frame: GatewayFrame) {
        frame.requestId?.let { timeouts.remove(it)?.cancel() }
        apply(shared.accept(frame))
    }
    fun timedOut(requestId: String) = apply(shared.timedOut(requestId))

    suspend fun importFiles(uris: List<Uri>) {
        if (!supported || connectionKey == null || sessionId == null || preparing) return
        val token = context
        preparing = true
        try {
            for (uri in uris.take((20 - drafts.size).coerceAtLeast(0))) {
                val id = UUID.randomUUID().toString()
                try {
                    val prepared = withContext(Dispatchers.IO) { prepare(uri, id) }
                    if (context != token) { prepared.file.delete(); continue }
                    localFiles[id] = prepared.file
                    apply(shared.add(id, prepared.name, prepared.size, prepared.sha256))
                } catch (failure: Exception) {
                    graph?.application?.cacheDir?.let { File(it, "upload-drafts/$id").delete() }
                    localFiles.remove(id)
                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                    if (context == token) {
                        error(if (failure is UiMessageException) appText(failure.uiMessage) else failure.message ?: appText("文件准备失败"))
                    }
                }
            }
        } finally {
            if (context == token) preparing = false
        }
    }

    private fun apply(transition: SharedUploadTransition) {
        drafts = transition.drafts
        for (request in transition.requests) {
            graph?.gatewayScope?.launch {
                val accepted = graph.gatewayRuntime.sendRequest(request)
                if (!accepted && request.requestType != "file-upload-cancel") {
                    withContext(Dispatchers.Main.immediate) { request.correlationId?.let(::timedOut) }
                }
            }
        }
        transition.watch?.let(::watch)
        val read = transition.read ?: return
        val file = localFiles[read.id] ?: return
        scope?.launch {
            try {
                val data = withContext(Dispatchers.IO) {
                    RandomAccessFile(file, "r").use { input ->
                        input.seek(read.offset)
                        val bytes = ByteArray(read.length)
                        input.readFully(bytes)
                        Base64.encodeToString(bytes, Base64.NO_WRAP)
                    }
                }
                apply(shared.chunkReady(read.token, data, read.length))
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                apply(shared.readFailed(read.token, failure.message?.let { UiMessage.raw(it) } ?: UiMessage("upload.readFailed")))
            }
        }
    }

    private fun watch(request: com.clarklevis.dsh.shared.gateway.GatewayRequest) {
        if (request.requestType == "file-upload-cancel") return
        val id = request.correlationId ?: return
        timeouts.remove(id)?.cancel()
        scope?.launch {
            delay(request.timeoutMilliseconds ?: 30_000)
            timeouts.remove(id)
            timedOut(id)
        }?.let { timeouts[id] = it }
    }

    private data class Prepared(val file: File, val name: String, val size: Long, val sha256: String)
    private fun prepare(uri: Uri, id: String): Prepared {
        val app = requireNotNull(graph).application
        val resolver = app.contentResolver
        var name = appText("附件")
        var declaredSize: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameColumn >= 0) name = cursor.getString(nameColumn) ?: name
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) declaredSize = cursor.getLong(sizeColumn)
            }
        }
        if (!(name.isNotBlank() && name.length <= 255 && name !in setOf(".", "..") &&
            name.none { it == '/' || it == '\\' || it.isISOControl() })) {
            throw UiMessageException(UiMessage("upload.invalidName"), "Invalid file name")
        }
        if ((declaredSize ?: 0) > SharedFileUploadStore.MAX_BYTES) {
            throw UiMessageException(UiMessage("upload.tooLarge"), "File exceeds 512 MiB")
        }
        val folder = File(app.cacheDir, "upload-drafts").apply { mkdirs() }
        val file = File(folder, id)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            (resolver.openInputStream(uri) ?: throw IllegalArgumentException(appText("无法读取所选文件"))).use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        size += count
                        if (size > SharedFileUploadStore.MAX_BYTES) {
                            throw UiMessageException(UiMessage("upload.tooLarge"), "File exceeds 512 MiB")
                        }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            return Prepared(file, name, size, digest.digest().joinToString("") { "%02x".format(it) })
        } catch (failure: Throwable) { file.delete(); throw failure }
    }
}
