package whaleness

import com.clarklevis.dsh.shared.facade.SharedWorkspaceFileCompletion
import com.clarklevis.dsh.shared.facade.SharedWorkspaceFileDownload
import com.clarklevis.dsh.shared.facade.SharedWorkspaceFileStore
import com.clarklevis.dsh.shared.facade.SharedWorkspaceFileTransition
import com.clarklevis.dsh.shared.gateway.GatewayRequest
import com.clarklevis.dsh.shared.protocol.GatewayDirectoryItem
import com.clarklevis.dsh.shared.protocol.GatewayFrame
import kotlinx.serialization.Serializable

@Serializable
data class HarmonyDownload(
    val transferId: String,
    val name: String,
    val size: Long,
    val receivedBytes: Long,
)

@Serializable
data class HarmonyWorkspaceFiles(
    val sessionId: String? = null,
    val available: Boolean = false,
    val path: String = ".",
    val entries: List<GatewayDirectoryItem> = emptyList(),
    val loading: Boolean = false,
    val busy: Boolean = false,
    val download: HarmonyDownload? = null,
    val error: String? = null,
)

@Serializable
data class HarmonyFileCompletion(
    val sessionId: String,
    val transferId: String,
    val path: String,
    val name: String,
    val mediaType: String,
    val size: Long,
    val sha256: String,
)

@Serializable
data class HarmonyFileIo(
    val token: String,
    val sessionId: String,
    val transferId: String,
    val operation: String,
    val offset: Long,
    val length: Int,
    val size: Long,
)

internal data class WorkspaceChange(
    val view: HarmonyWorkspaceFiles? = null,
    val requests: List<GatewayRequest> = emptyList(),
    val io: HarmonyFileIo? = null,
    val discard: String? = null,
    val completion: HarmonyFileCompletion? = null,
)

/** 共享 Store 负责路径、偏移和 SHA；平台每块落盘确认后才释放下一条网络请求。 */
internal class WorkspaceSupport {
    private var store = SharedWorkspaceFileStore()
    private var session: String? = null
    private var root: String? = null
    private var available = false
    private var resolve = false
    private var serial = 0L
    private var local: SharedWorkspaceFileDownload? = null
    private var written = 0L
    private var io: HarmonyFileIo? = null
    private var held: SharedWorkspaceFileTransition? = null
    private var published: HarmonyWorkspaceFiles? = null
    private var localError: String? = null

    private data class Watch(
        val key: String,
        val request: GatewayRequest,
    )

    private val watches = mutableMapOf<String, Watch>()

    fun reset() {
        store = SharedWorkspaceFileStore()
        session = null
        root = null
        available = false
        watches.clear()
        local = null
        io =
            null
        held = null
        written = 0
        published = null
        localError = null
    }

    fun bind(
        id: String?,
        workspace: String?,
        enabled: Boolean,
        supportsResolve: Boolean,
    ): WorkspaceChange {
        val clear = id != session || !enabled
        session = id
        available = enabled && id != null
        resolve = supportsResolve
        root = workspace
        if (!clear) return apply(SharedWorkspaceFileTransition(store.snapshot()))
        val cancelled = store.cancel()
        val discard = local?.transferId ?: cancelled.discardTransferId
        local = null
        written = 0
        io = null
        held = null
        watches.clear()
        localError = null
        val next = store.reset(id)
        if (id != null && workspace != null) store.setWorkspaceSession(id, workspace)
        return apply(next).copy(
            requests =
                cancelled.request
                    ?.takeIf { enabled }
                    ?.let(::listOf)
                    .orEmpty(),
            discard = discard,
        )
    }

    fun pendingKeys(): List<String> = watches.values.map { it.key } + listOfNotNull(io?.token)

    fun command(
        command: HarmonyCommand,
        workspaceRoot: String?,
    ): WorkspaceChange {
        if (command.op == "files-write-complete") {
            val pending = io?.takeIf { it.token == command.requestKey && it.sessionId == command.sessionId } ?: return WorkspaceChange()
            if (command.outcome != "ok") return failIo("文件写入失败，请检查可用空间后重试")
            val next = requireNotNull(held)
            io = null
            held = null
            written = pending.offset + pending.length
            if (pending.operation == "finish") {
                val completed = requireNotNull(next.completion)
                val transfer = requireNotNull(local)
                local = null
                written = 0
                return apply(SharedWorkspaceFileTransition(next.snapshot)).copy(completion = completion(completed, transfer.transferId))
            }
            return apply(next.copy(appendBase64Data = null))
        }
        if (command.op == "files-cancel" || command.op == "files-close") {
            val cancelled = store.cancel()
            val discard = local?.transferId ?: cancelled.discardTransferId
            local = null
            io = null
            held = null
            watches.clear()
            written = 0
            localError = null
            val next = if (command.op == "files-close") store.reset(session) else cancelled
            return apply(next).copy(discard = discard, requests = cancelled.request?.let(::listOf).orEmpty())
        }
        require(available && command.sessionId == session) { "连接支持文件下载的网关后才能读取工作区" }
        check(io == null && watches.isEmpty()) { "请等待当前文件操作完成" }
        localError = null
        val id = requireNotNull(session)
        val requestId = "workspace-${++serial}"
        val next =
            when (command.op) {
                "files-open", "files-list" -> store.load(id, command.path, requestId)
                "files-resolve" -> store.resolve(id, requireNotNull(command.path), requestId, resolve)
                "files-reference" -> store.resolveReference(id, requireNotNull(command.path), requestId, resolve, workspaceRoot)
                else -> error("不支持的工作区文件操作")
            }
        return apply(next)
    }

    fun accept(
        frame: GatewayFrame,
        raw: String,
    ): WorkspaceChange? {
        if (frame.kind !in RESPONSES && !(frame.kind == "error" && frame.requestType in REQUESTS)) return null
        val id = frame.requestId ?: frame.transferId
        val watch = watches[id]
        if (watch != null &&
            (watch.request.responseKind == frame.kind || (frame.kind == "error" && watch.request.requestType == frame.requestType))
        ) {
            watches.remove(id)
        }
        return apply(store.acceptFrame(raw))
    }

    fun timedOut(key: String): WorkspaceChange {
        if (io?.token == key) return failIo("文件写入超时，请重试")
        val entry = watches.entries.firstOrNull { it.value.key == key } ?: return WorkspaceChange()
        watches.remove(entry.key)
        return apply(store.requestFailed(entry.value.request.requestType, "文件读取超时，请重试", entry.key))
    }

    private fun failIo(message: String): WorkspaceChange {
        val discard = local?.transferId
        val next = store.cancel()
        io = null
        held = null
        local = null
        watches.clear()
        written = 0
        localError =
            message
        return apply(next).copy(discard = discard)
    }

    private fun completion(
        value: SharedWorkspaceFileCompletion,
        transferId: String,
    ) = HarmonyFileCompletion(value.sessionId, transferId, value.path, value.name, value.mediaType, value.size, value.sha256)

    private fun apply(next: SharedWorkspaceFileTransition): WorkspaceChange {
        next.cacheLookup?.let { return apply(store.completeCacheLookup(it.requestId, false)) }
        var effect: HarmonyFileIo? = null
        var requests = next.request?.let(::listOf).orEmpty()
        val active = next.snapshot.activeDownload
        if (next.discardTransferId != null) {
            local = null
            io = null
            held = null
            written = 0
            watches.clear()
        }
        when {
            active != null && local?.transferId != active.transferId -> {
                local = active
                written = 0
                held = next
                effect = HarmonyFileIo("workspace-io:${++serial}", active.sessionId, active.transferId, "create", 0, 0, active.size)
            }

            next.appendBase64Data != null -> {
                val transfer = requireNotNull(local)
                val received = active?.receivedBytes ?: transfer.size
                val length = received - written
                require(length in 0..4_194_304) { "文件写入块大小无效" }
                held = next
                effect =
                    HarmonyFileIo(
                        "workspace-io:${++serial}",
                        transfer.sessionId,
                        transfer.transferId,
                        "append",
                        written,
                        length.toInt(),
                        transfer.size,
                    )
            }

            next.completion != null -> {
                val transfer = requireNotNull(local)
                held = next
                effect =
                    HarmonyFileIo("workspace-io:${++serial}", transfer.sessionId, transfer.transferId, "finish", written, 0, transfer.size)
            }
        }
        if (effect != null) {
            io = effect
            requests = emptyList()
        }
        requests.forEach { request ->
            if (request.requestType !=
                "file-download-cancel"
            ) {
                request.correlationId?.let { watches[it] = Watch("workspace-rpc:${++serial}", request) }
            }
        }
        val snapshot = next.snapshot
        val state =
            HarmonyWorkspaceFiles(
                session,
                available,
                snapshot.path,
                snapshot.entries,
                snapshot.isLoading,
                io != null || watches.isNotEmpty(),
                snapshot.activeDownload?.let { HarmonyDownload(it.transferId, it.name, it.size, it.receivedBytes) },
                localError ?: snapshot.lastError?.let(::friendly),
            )
        val changed = state.takeIf { it != published }
        published = state
        return WorkspaceChange(changed, requests, effect, next.discardTransferId)
    }

    private fun friendly(value: String): String =
        when (value) {
            "file-download-integrity-failed" -> "文件完整性校验失败，请重新下载"
            "file-references-upgrade-required" -> "请升级主机网关后打开此文件引用"
            "path-invalid", "path-outside-workspace", "path-outside-root" -> "文件路径无效或不在当前工作区"
            "download-busy" -> "请等待当前下载完成"
            else -> if (value.startsWith("file-") || value.startsWith("path-")) "文件读取失败，请刷新后重试" else value.take(240)
        }

    companion object {
        private val REQUESTS = setOf("file-list", "file-resolve", "file-download-open", "file-download-read", "file-download-cancel")
        private val RESPONSES =
            setOf("file-list", "file-resolved", "file-download-opened", "file-download-chunk", "file-download-cancelled")
    }
}
