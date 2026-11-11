package whaleness

import com.clarklevis.dsh.shared.facade.SharedFileUploadStore
import com.clarklevis.dsh.shared.facade.SharedUploadTransition
import com.clarklevis.dsh.shared.gateway.GatewayOutgoingImage
import com.clarklevis.dsh.shared.gateway.GatewayRequest
import com.clarklevis.dsh.shared.protocol.GatewayFrame
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64

@Serializable
data class HarmonyUploadDraft(
    val id: String,
    val name: String,
    val size: Long,
    val sha256: String,
    val status: String,
    val confirmedBytes: Long,
    val error: String?,
)

@Serializable
data class HarmonyPhoto(
    val id: String,
    val name: String,
    val size: Long,
    val width: Int,
    val height: Int,
)

@Serializable
data class HarmonyUploads(
    val sessionId: String? = null,
    val available: Boolean = false,
    val canSend: Boolean = false,
    val drafts: List<HarmonyUploadDraft> = emptyList(),
    val photosAvailable: Boolean = false,
    val photos: List<HarmonyPhoto> = emptyList(),
)

@Serializable
data class HarmonyUploadRead(
    val sessionId: String,
    val id: String,
    val offset: Long,
    val length: Int,
    val token: String,
)

internal data class UploadChange(
    val view: HarmonyUploads? = null,
    val read: HarmonyUploadRead? = null,
    val requests: List<GatewayRequest> = emptyList(),
)

/** 与 Android 相同：切换会话清除附件草稿；平台私有副本不跨会话复用。 */
internal class UploadSupport {
    private var store = SharedFileUploadStore()
    private var session: String? = null
    private var connection: String? = null
    private var available = false
    private var sequence = 0L

    private data class Photo(
        val view: HarmonyPhoto,
        val data: String,
        val sha256: String,
    )

    private data class Submission(
        val session: String,
        val files: List<String>,
        val photos: List<String>,
    )

    private val photos = mutableListOf<Photo>()
    private var photosAvailable = false
    private var submitted: Submission? = null
    private var published: HarmonyUploads? = null

    private data class Watch(
        val key: String,
        val request: GatewayRequest,
    )

    private val watches = mutableMapOf<String, Watch>()

    fun reset() {
        store = SharedFileUploadStore()
        session = null
        connection = null
        available = false
        photosAvailable = false
        watches.clear()
        submitted =
            null
        published = null
        photos.clear()
        photosAvailable = false
    }

    fun connected() {
        connection = "harmony-files-${++sequence}"
    }

    fun disconnected(): UploadChange {
        connection = null
        available = false
        watches.clear()
        return apply(store.bind(session, null, false))
    }

    fun select(
        id: String?,
        enabled: Boolean,
        allowPhotos: Boolean,
    ): UploadChange {
        val clear =
            if (id != session) {
                photos.clear()
                store.clear().requests
            } else {
                emptyList()
            }
        photosAvailable = allowPhotos && id != null && connection != null
        session = id
        available = enabled && connection != null && id != null
        val next = store.bind(id, connection, available)
        return apply(next.copy(requests = clear + next.requests))
    }

    fun command(command: HarmonyCommand): UploadChange {
        require(command.sessionId == session && session != null) { "附件所属会话已变化，请重新选择" }
        if (command.op == "image-add") {
            require(photosAvailable && photos.size < 4 && photos.none { it.view.id == command.itemId }) { "当前最多可选择四张图片" }
            require(
                command.fileName.isNotBlank() && command.fileName.length <= 255 &&
                    command.fileName.none { it == '/' || it == '\\' || it.isISOControl() },
            ) { "图片名称无效" }
            require(command.sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "图片摘要无效" }
            require(
                command.input.length <= 1_398_104 && command.byteSize in 1..1_048_576 && command.imageWidth in 1..2048 &&
                    command.imageHeight in 1..2048,
            ) { "图片大小或尺寸无效" }
            val bytes = Base64.decode(command.input)
            require(
                bytes.size.toLong() == command.byteSize && bytes.size >= 4 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() &&
                    bytes[bytes.lastIndex - 1] == 0xff.toByte() &&
                    bytes.last() == 0xd9.toByte(),
            ) { "图片内容无效" }
            photos +=
                Photo(
                    HarmonyPhoto(
                        requireNotNull(command.itemId),
                        command.fileName,
                        command.byteSize,
                        command.imageWidth,
                        command.imageHeight,
                    ),
                    command.input,
                    command.sha256.lowercase(),
                )
            return apply(SharedUploadTransition(store.snapshot()))
        }
        if (command.op ==
            "image-remove"
        ) {
            photos.removeAll { it.view.id == command.itemId }
            return apply(SharedUploadTransition(store.snapshot()))
        }
        val next =
            when (command.op) {
                "file-add" -> {
                    require(available) { "当前主机未开放文件上传" }
                    store.add(requireNotNull(command.itemId), command.fileName, command.byteSize, command.sha256)
                }

                "file-retry" -> {
                    require(available) { "请连接主机后重试" }
                    store.retry(requireNotNull(command.itemId))
                }

                "file-remove" -> {
                    store.remove(requireNotNull(command.itemId))
                }

                "file-chunk" -> {
                    require(command.text.length <= 6 * 1024 * 1024) { "文件分块过大" }
                    store.chunkReady(command.requestKey, command.text, command.byteCount)
                }

                "file-read-failed" -> {
                    store.readFailed(command.requestKey, "文件读取失败，请重新选择")
                }

                else -> {
                    error("不支持的附件操作")
                }
            }
        return apply(next)
    }

    fun accept(frame: GatewayFrame): UploadChange? {
        if (!frame.kind.startsWith("file-upload-") &&
            !(frame.kind == "error" && frame.requestType?.startsWith("file-upload-") == true)
        ) {
            return null
        }
        watches.remove(frame.requestId)
        return apply(store.accept(frame))
    }

    fun timedOut(key: String): UploadChange {
        val entry = watches.entries.firstOrNull { it.value.key == key } ?: return UploadChange()
        val id = entry.key
        watches.remove(id)
        return apply(store.timedOut(id))
    }

    fun pendingKeys(): List<String> = watches.values.map { it.key }

    fun timeouts(): Map<String, Long> = watches.values.associate { it.key to (it.request.timeoutMilliseconds ?: 30_000L) }

    fun hasFiles(): Boolean = store.snapshot().isNotEmpty() || photos.isNotEmpty()

    fun images(): List<GatewayOutgoingImage> = photos.map { GatewayOutgoingImage("image/jpeg", it.data, it.view.name) }

    fun ready(): Boolean = photosAvailable && hasFiles() && (store.snapshot().isEmpty() || store.canSend())

    fun receipts(): List<String> = store.receipts()

    fun requiresNonce(): Boolean = submitted?.files?.isNotEmpty() == true

    fun submission(
        text: String,
        proposed: String,
    ): String {
        require(!hasFiles() || ready()) { "请等待附件上传完成" }
        if (!hasFiles()) {
            submitted = null
            return proposed
        }
        submitted = Submission(requireNotNull(session), store.snapshot().map { it.id }, photos.map { it.view.id })
        return store.submission(text, photos.map { it.view.id + ":" + it.sha256 }.joinToString(","), proposed)
    }

    fun accepted(sessionId: String?): UploadChange {
        val delivery = submitted?.takeIf { it.session == sessionId } ?: return UploadChange()
        submitted = null
        if (sessionId != session) return UploadChange()
        photos.removeAll { it.view.id in delivery.photos }
        var result = apply(SharedUploadTransition(store.snapshot()))
        delivery.files.forEach { id ->
            val next = apply(store.remove(id))
            result = next.copy(requests = result.requests + next.requests)
        }
        return result
    }

    fun invalidReceipts(): UploadChange = apply(store.invalidateReceipts())

    private fun apply(next: SharedUploadTransition): UploadChange {
        fun watch(request: GatewayRequest) {
            if (request.requestType == "file-upload-cancel") return
            request.correlationId?.let { watches[it] = Watch("uploads:$it:${++sequence}", request) }
        }
        if (next.drafts.none {
                it.status == "committing"
            }
        ) {
            watches.entries.removeAll { it.value.request.requestType == "file-upload-finish" }
        }
        next.requests.forEach(::watch)
        next.watch?.let(::watch)
        val view =
            HarmonyUploads(
                session,
                available,
                ready(),
                next.drafts.map {
                    HarmonyUploadDraft(it.id, it.name, it.size, it.sha256, it.status, it.confirmedBytes, it.error)
                },
                photosAvailable,
                photos.map { it.view },
            )
        val changed = view.takeIf { it != published }
        published = view
        val read = next.read?.let { HarmonyUploadRead(requireNotNull(session), it.id, it.offset, it.length, it.token) }
        return UploadChange(changed, read, next.requests)
    }
}
