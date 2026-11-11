package com.clarklevis.dsh.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.clarklevis.dsh.android.localization.appText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

internal data class IncomingShare(val id: String, val text: String, val files: List<Uri>)

/** 先复制提供方文件，避免切换会话后丢失临时读取授权。 */
internal class IncomingShareInbox(private val context: Context) {
    private val manifest get() = File(context.filesDir, "incoming-share.json")
    private var pendingState by mutableStateOf<IncomingShare?>(restore())
    var pending: IncomingShare?
        get() = pendingState
        set(value) {
            if (value == null) {
                manifest.delete()
                pendingState?.let { File(context.filesDir, "incoming-shares/${it.id}").deleteRecursively() }
            }
            else {
                val json = org.json.JSONObject().put("id", value.id).put("text", value.text)
                    .put("files", org.json.JSONArray(value.files.map(Uri::toString)))
                val atomic = android.util.AtomicFile(manifest)
                val output = atomic.startWrite()
                try { output.write(json.toString().toByteArray()); atomic.finishWrite(output) }
                catch (failure: Exception) { atomic.failWrite(output); throw failure }
            }
            pendingState = value
        }
    private fun restore(): IncomingShare? = runCatching {
        val json = org.json.JSONObject(manifest.readText())
        val files = json.getJSONArray("files")
        IncomingShare(json.getString("id"), json.getString("text"), (0 until files.length()).map { Uri.parse(files.getString(it)) })
    }.getOrNull()
    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
    suspend fun receive(context: Context, intent: Intent) {
        if (intent.action !in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return
        if (loading) { error = appText("正在读取分享内容，请稍后重试"); return }
        loading = true
        try {
            check(pending == null) { appText("请先处理当前分享内容") }
            val share = withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                val streams = if (intent.action == Intent.ACTION_SEND_MULTIPLE)
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
                else listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
                val clips = intent.clipData?.let { data -> (0 until data.itemCount).mapNotNull { data.getItemAt(it).uri } }.orEmpty()
                val uris = (streams + clips).distinct()
                require(uris.size <= 20) { appText("一次最多分享 20 个附件") }
                val id = UUID.randomUUID().toString()
                val directory = File(context.filesDir, "incoming-shares/$id").apply { mkdirs() }
                try {
                    var total = 0L
                    val files = uris.mapIndexed { index, uri ->
                        require(uri.scheme == "content") { appText("无法读取分享附件") }
                        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                            if (it.moveToFirst()) it.getString(0) else null
                        } ?: "attachment-$index"
                        val folder = File(directory, index.toString()).apply { mkdirs() }
                        val suffix = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(context.contentResolver.getType(uri))
                        val displayName = if (File(name).extension.isBlank() && suffix != null) "$name.$suffix" else name
                        val file = File(folder, displayName.substringAfterLast('/').substringAfterLast('\\').ifBlank { "attachment" })
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            file.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    require(total <= 100L * 1024 * 1024) { appText("分享附件总大小不能超过 100 MiB") }
                                    output.write(buffer, 0, count)
                                }
                            }
                        } ?: error(appText("无法读取分享附件"))
                        Uri.fromFile(file)
                    }
                    val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
                    require(text.isNotBlank() || files.isNotEmpty()) { appText("没有可分享的内容") }
                    IncomingShare(id, text, files)
                } catch (failure: Exception) {
                    directory.deleteRecursively()
                    throw failure
                }
            }
            pending = share
        } catch (failure: Exception) {
            error = failure.message ?: appText("读取分享内容失败")
        } finally { loading = false }
    }
}
