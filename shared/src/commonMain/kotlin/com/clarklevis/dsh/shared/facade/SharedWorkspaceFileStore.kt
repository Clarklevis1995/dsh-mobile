package com.clarklevis.dsh.shared.facade

import com.clarklevis.dsh.shared.gateway.GatewayRequest
import com.clarklevis.dsh.shared.gateway.GatewayRequests
import com.clarklevis.dsh.shared.protocol.GatewayDirectoryItem
import com.clarklevis.dsh.shared.protocol.GatewayFrame
import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder
import com.clarklevis.dsh.shared.util.Sha256

data class SharedWorkspaceFileDownload(
    val transferId: String,
    val sessionId: String,
    val path: String,
    val name: String,
    val mediaType: String,
    val size: Long,
    val receivedBytes: Long,
    val purpose: String
)

data class SharedWorkspaceFileSnapshot(
    val sessionId: String? = null,
    val path: String = ".",
    val entries: List<GatewayDirectoryItem> = emptyList(),
    val isLoading: Boolean = false,
    val activeDownload: SharedWorkspaceFileDownload? = null,
    val lastError: String? = null
)

data class SharedWorkspaceFileCompletion(
    val sessionId: String,
    val path: String,
    val name: String,
    val mediaType: String,
    val size: Long,
    val sha256: String,
    val purpose: String,
    val modifiedAt: Double? = null
)

/** 已由远端重新解析的版本；requestId 用于拒绝取消或切换会话后的缓存回调。 */
data class SharedWorkspaceFileCacheLookup(
    val requestId: String,
    val sessionId: String,
    val path: String,
    val name: String,
    val mediaType: String,
    val size: Long,
    val modifiedAt: Double,
    val workspaceSessionId: String = sessionId
)

data class SharedWorkspaceDirectory(val sessionId: String, val path: String)

/**
 * 平台文件 I/O 之外的工作区文件状态机。
 *
 * commonMain 负责请求关联、目录边界、文件路径校验、分块偏移、Base64、长度与 SHA-256；
 * iOS/Android 只把 [appendBase64Data] 追加到当前临时文件，并在 [completion]
 * 出现后原子提交或交给系统预览/另存为。
 */
data class SharedWorkspaceFileTransition(
    val snapshot: SharedWorkspaceFileSnapshot,
    val request: GatewayRequest? = null,
    val appendBase64Data: String? = null,
    val completion: SharedWorkspaceFileCompletion? = null,
    val discardTransferId: String? = null,
    val cacheLookup: SharedWorkspaceFileCacheLookup? = null,
    val cacheHit: SharedWorkspaceFileCacheLookup? = null,
    val directory: SharedWorkspaceDirectory? = null
)

class SharedWorkspaceFileStore {
    private var state = SharedWorkspaceFileSnapshot()
    private var pendingList: PendingList? = null
    private var pendingOpen: PendingOpen? = null
    private var pendingResolve: PendingOpen? = null
    private var active: ActiveDownload? = null
    private var pendingCacheLookup: SharedWorkspaceFileCacheLookup? = null
    private var pendingVerification: PendingVerification? = null
    private var workspaceBinding: Pair<String, String>? = null

    /** 只改变文件传输地址；预览、完成结果和缓存键保留当前视图的 sessionId。 */
    fun setWorkspaceSession(sessionId: String, workspaceSessionId: String) {
        requireIdentifier(sessionId, "sessionId")
        requireIdentifier(workspaceSessionId, "workspaceSessionId")
        workspaceBinding = sessionId to workspaceSessionId
    }

    private fun workspaceSessionId(sessionId: String): String =
        workspaceBinding?.takeIf { it.first == sessionId }?.second ?: sessionId

    fun snapshot(): SharedWorkspaceFileSnapshot = state

    fun reset(sessionId: String?): SharedWorkspaceFileTransition {
        val discard = active?.transferId
        workspaceBinding = null
        pendingList = null
        pendingOpen = null
        pendingResolve = null
        pendingCacheLookup = null
        pendingVerification = null
        active = null
        state = SharedWorkspaceFileSnapshot(sessionId = sessionId?.takeIf(String::isNotBlank))
        return SharedWorkspaceFileTransition(state, discardTransferId = discard)
    }

    fun load(sessionId: String, path: String?, requestId: String): SharedWorkspaceFileTransition {
        requireIdentifier(sessionId, "sessionId")
        requireIdentifier(requestId, "requestId")
        val normalizedPath = normalizeRelativePath(path, allowRoot = true)
        val workspaceSessionId = workspaceSessionId(sessionId)
        pendingList = PendingList(requestId, sessionId, normalizedPath, workspaceSessionId)
        state = if (state.sessionId != sessionId) {
            SharedWorkspaceFileSnapshot(
                sessionId = sessionId,
                path = normalizedPath,
                isLoading = true
            )
        } else {
            state.copy(
                path = normalizedPath,
                entries = if (normalizedPath == state.path) state.entries else emptyList(),
                isLoading = true,
                lastError = null
            )
        }
        return SharedWorkspaceFileTransition(
            state,
            request = GatewayRequests.fileList(
                workspaceSessionId,
                normalizedPath.takeUnless { it == "." },
                requestId
            )
        )
    }

    /** 原始交付路径不做 URL 解码；只有 Markdown 链接入口负责解码一次。 */
    fun resolve(
        sessionId: String,
        path: String,
        requestId: String,
        supportsResolve: Boolean,
        useCache: Boolean = false
    ): SharedWorkspaceFileTransition {
        requireIdentifier(sessionId, "sessionId")
        requireIdentifier(requestId, "requestId")
        if (isBusy()) return fail("download-busy")
        if (path.isBlank() || '\u0000' in path) return fail("path-invalid")
        if (!supportsResolve) {
            val relative = runCatching { normalizeRelativePath(path, allowRoot = false) }
                .getOrElse { return fail("file-references-upgrade-required") }
            return download(sessionId, relative, requestId, "preview")
        }
        val workspaceSessionId = workspaceSessionId(sessionId)
        pendingResolve = PendingOpen(requestId, sessionId, path, "preview", workspaceSessionId, useCache = useCache)
        state = state.copy(lastError = null)
        return SharedWorkspaceFileTransition(state, request = GatewayRequests.fileResolve(workspaceSessionId, path, requestId))
    }

    /** 会话链接允许进入目录；文件列表中的文件预览仍只接受普通文件。 */
    fun resolveReference(
        sessionId: String,
        path: String,
        requestId: String,
        supportsResolve: Boolean,
        workspaceRoot: String?
    ): SharedWorkspaceFileTransition {
        if (isBusy()) return fail("download-busy")
        if (path.endsWith('/')) {
            requireIdentifier(sessionId, "sessionId")
            requireIdentifier(requestId, "requestId")
            return directoryTransition(sessionId, path, workspaceRoot)
        }
        val transition = resolve(sessionId, path, requestId, supportsResolve, useCache = true)
        pendingResolve = pendingResolve?.copy(allowDirectory = true, workspaceRoot = workspaceRoot)
        pendingOpen = pendingOpen?.copy(allowDirectory = true, workspaceRoot = workspaceRoot)
        return transition
    }

    private fun directoryTransition(sessionId: String, path: String, workspaceRoot: String?): SharedWorkspaceFileTransition {
        val relative = runCatching {
            val trimmed = path.trim().trimEnd('/').ifEmpty { "/" }
            val root = workspaceRoot?.trimEnd('/')
            val candidate = if (trimmed.startsWith('/')) {
                require(root != null && (trimmed == root || trimmed.startsWith("$root/"))) { "path-absolute" }
                trimmed.removePrefix(root).trimStart('/').ifEmpty { "." }
            } else trimmed.removePrefix("./")
            normalizeRelativePath(candidate, allowRoot = true)
        }.getOrElse { return fail("file-directory-path-invalid") }
        state = SharedWorkspaceFileSnapshot(sessionId = sessionId, path = relative, isLoading = true)
        return SharedWorkspaceFileTransition(state, directory = SharedWorkspaceDirectory(sessionId, relative))
    }

    private fun resolveDirectoryError(frame: GatewayFrame): SharedWorkspaceFileTransition? {
        if (frame.code != "file-not-regular") return null
        val pending = when (frame.requestType) {
            "file-resolve" -> pendingResolve
            "file-download-open" -> pendingOpen
            else -> null
        } ?: return null
        if (!pending.allowDirectory) return null
        if (frame.requestId != pending.requestId || frame.sessionId != pending.workspaceSessionId) {
            return SharedWorkspaceFileTransition(state)
        }
        pendingResolve = null
        pendingOpen = null
        return directoryTransition(pending.sessionId, pending.path, pending.workspaceRoot)
    }

    fun download(
        sessionId: String,
        path: String,
        requestId: String,
        purpose: String
    ): SharedWorkspaceFileTransition = downloadInWorkspace(sessionId, workspaceSessionId(sessionId), path, requestId, purpose)

    private fun downloadInWorkspace(
        sessionId: String,
        workspaceSessionId: String,
        path: String,
        requestId: String,
        purpose: String
    ): SharedWorkspaceFileTransition {
        requireIdentifier(sessionId, "sessionId")
        requireIdentifier(requestId, "requestId")
        require(purpose in PURPOSES) { "purpose-invalid" }
        val normalizedPath = normalizeFilePath(path)
        if (isBusy()) return fail("download-busy")
        pendingOpen = PendingOpen(requestId, sessionId, normalizedPath, purpose, workspaceSessionId)
        state = state.copy(lastError = null)
        return SharedWorkspaceFileTransition(
            state,
            request = GatewayRequests.fileDownloadOpen(workspaceSessionId, normalizedPath, requestId)
        )
    }

    /** 平台必须检查文件存在、大小和 SHA-256；过期回调不会触发预览或下载。 */
    fun completeCacheLookup(requestId: String, available: Boolean): SharedWorkspaceFileTransition {
        val lookup = pendingCacheLookup ?: return SharedWorkspaceFileTransition(state)
        if (lookup.requestId != requestId) return SharedWorkspaceFileTransition(state)
        pendingCacheLookup = null
        state = state.copy(lastError = null)
        if (available) return SharedWorkspaceFileTransition(state, cacheHit = lookup)
        val next = downloadInWorkspace(lookup.sessionId, lookup.workspaceSessionId, lookup.path, requestId + "-download", "preview")
        pendingOpen = pendingOpen?.copy(modifiedAt = lookup.modifiedAt, expectedSize = lookup.size, verifyVersion = true)
        return next
    }

    private fun isBusy(): Boolean = active != null || pendingOpen != null || pendingResolve != null ||
        pendingCacheLookup != null || pendingVerification != null

    fun cancel(): SharedWorkspaceFileTransition {
        val transfer = active
        pendingResolve = null
        pendingCacheLookup = null
        pendingVerification = null
        pendingOpen = null
        active = null
        state = state.copy(activeDownload = null, lastError = null)
        return SharedWorkspaceFileTransition(
            state,
            request = transfer?.let { GatewayRequests.fileDownloadCancel(it.transferId) },
            discardTransferId = transfer?.transferId
        )
    }

    fun requestFailed(
        requestType: String,
        message: String?,
        correlationId: String? = null
    ): SharedWorkspaceFileTransition {
        if (requestType !in REQUEST_TYPES) return SharedWorkspaceFileTransition(state)
        val verification = pendingVerification
        if (requestType == "file-resolve" && verification != null &&
            (correlationId == null || correlationId == verification.requestId)) {
            pendingVerification = null
            return SharedWorkspaceFileTransition(state, completion = verification.completion.copy(modifiedAt = null))
        }
        val matches = when (requestType) {
            "file-resolve" -> pendingResolve != null &&
                (correlationId == null || pendingResolve?.requestId == correlationId)
            "file-list" -> pendingList != null &&
                (correlationId == null || pendingList?.requestId == correlationId)
            "file-download-open" -> pendingOpen != null &&
                (correlationId == null || pendingOpen?.requestId == correlationId)
            "file-download-read", "file-download-cancel" ->
                active != null && (correlationId == null || active?.transferId == correlationId)
            else -> true
        }
        if (!matches) return SharedWorkspaceFileTransition(state)
        val discard = active?.transferId
        when (requestType) {
            "file-resolve" -> pendingResolve = null
            "file-list" -> pendingList = null
            "file-download-open" -> pendingOpen = null
            else -> active = null
        }
        state = state.copy(
            isLoading = if (requestType == "file-list") false else state.isLoading,
            activeDownload = if (requestType.startsWith("file-download")) null else state.activeDownload,
            lastError = message?.takeIf(String::isNotBlank) ?: "$requestType-failed"
        )
        return SharedWorkspaceFileTransition(state, discardTransferId = discard)
    }

    fun acceptFrame(json: String): SharedWorkspaceFileTransition = try {
        acceptFrame(GatewayWireDecoder.decode(json))
    } catch (_: Throwable) {
        fail("file-frame-invalid")
    }

    private fun acceptFrame(frame: GatewayFrame): SharedWorkspaceFileTransition = when (frame.kind) {
        "file-resolved" -> acceptResolved(frame)
        "file-list" -> acceptList(frame)
        "file-download-opened" -> acceptOpened(frame)
        "file-download-chunk" -> acceptChunk(frame)
        "file-download-cancelled" -> acceptCancelled(frame)
        "error" -> resolveDirectoryError(frame) ?: frame.requestType
            ?.takeIf { it in REQUEST_TYPES }
            ?.let { requestFailed(it, frame.code ?: frame.message, frame.requestId ?: frame.transferId) }
            ?: SharedWorkspaceFileTransition(state)
        else -> SharedWorkspaceFileTransition(state)
    }

    private fun acceptResolved(frame: GatewayFrame): SharedWorkspaceFileTransition {
        val verification = pendingVerification
        if (verification != null && frame.requestId == verification.requestId &&
            frame.sessionId == verification.workspaceSessionId) {
            pendingVerification = null
            val completion = verification.completion
            val unchanged = frame.path == completion.path && frame.size == completion.size &&
                frame.modifiedAt == completion.modifiedAt
            return SharedWorkspaceFileTransition(state,
                completion = if (unchanged) completion else completion.copy(modifiedAt = null))
        }
        val pending = pendingResolve ?: return SharedWorkspaceFileTransition(state)
        if (frame.requestId != pending.requestId) return SharedWorkspaceFileTransition(state)
        if (frame.sessionId != pending.workspaceSessionId) return SharedWorkspaceFileTransition(state)
        pendingResolve = null
        val path = runCatching { normalizeFilePath(frame.path) }
            .getOrElse { return fail("file-resolve-invalid") }
        if (frame.path.isNullOrBlank() || frame.name.isNullOrBlank() ||
            frame.size == null || frame.size < 0L || frame.size > MAXIMUM_FILE_BYTES) {
            return fail("file-resolve-invalid")
        }
        val modifiedAt = frame.modifiedAt?.takeIf { it.isFinite() && it >= 0 }
        if (pending.useCache && modifiedAt != null) {
            val lookup = SharedWorkspaceFileCacheLookup(
                pending.requestId, pending.sessionId, path, frame.name,
                frame.mediaType?.takeIf(String::isNotBlank) ?: DEFAULT_MEDIA_TYPE, frame.size, modifiedAt, pending.workspaceSessionId
            )
            pendingCacheLookup = lookup
            return SharedWorkspaceFileTransition(state, cacheLookup = lookup)
        }
        return downloadInWorkspace(pending.sessionId, pending.workspaceSessionId, path, pending.requestId + "-download", pending.purpose)
    }

    private fun acceptList(frame: GatewayFrame): SharedWorkspaceFileTransition {
        val pending = pendingList ?: return fail("file-list-unexpected")
        if (frame.requestId != pending.requestId) return SharedWorkspaceFileTransition(state)
        if (frame.sessionId != pending.workspaceSessionId) {
            return fail("file-list-correlation-mismatch")
        }
        val responsePath = runCatching { normalizeRelativePath(frame.path, allowRoot = true) }
            .getOrElse { return fail("file-list-path-invalid") }
        if (responsePath != pending.path) return fail("file-list-path-mismatch")
        val entries = frame.entries.orEmpty().map { entry ->
            val kind = entry.kind
            val entryPath = runCatching { normalizeRelativePath(entry.path, allowRoot = false) }
                .getOrElse { return fail("file-entry-invalid") }
            if (
                entry.name.isBlank() || '/' in entry.name || '\\' in entry.name ||
                kind !in setOf("file", "directory") ||
                !isDirectChild(responsePath, entryPath) ||
                (kind == "file" && (entry.bytes == null || entry.bytes < 0L))
            ) {
                return fail("file-entry-invalid")
            }
            entry.copy(path = entryPath)
        }.sortedWith(compareBy<GatewayDirectoryItem> { it.kind != "directory" }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        pendingList = null
        state = state.copy(
            sessionId = pending.sessionId,
            path = responsePath,
            entries = entries,
            isLoading = false,
            lastError = null
        )
        return SharedWorkspaceFileTransition(state)
    }

    private fun acceptOpened(frame: GatewayFrame): SharedWorkspaceFileTransition {
        val pending = pendingOpen
        if (pending == null || frame.requestId != pending.requestId) {
            return SharedWorkspaceFileTransition(state, request = frame.transferId?.let(GatewayRequests::fileDownloadCancel))
        }
        val transferId = frame.transferId
        val size = frame.size
        val chunkBytes = frame.chunkBytes
        val path = runCatching { normalizeFilePath(frame.path) }
            .getOrElse { return fail("file-download-path-invalid") }
        if (
            frame.requestId != pending.requestId || frame.sessionId != pending.workspaceSessionId ||
            path != pending.path || transferId.isNullOrBlank() || frame.name.isNullOrBlank() ||
            size == null || size < 0L || size > MAXIMUM_FILE_BYTES ||
            chunkBytes == null || chunkBytes <= 0 || chunkBytes > MAXIMUM_CHUNK_BYTES
        ) {
            pendingOpen = null
            return fail("file-download-open-invalid")
        }
        val mediaType = frame.mediaType?.takeIf(String::isNotBlank) ?: DEFAULT_MEDIA_TYPE
        val transfer = ActiveDownload(
            transferId = transferId,
            sessionId = pending.sessionId,
            workspaceSessionId = pending.workspaceSessionId,
            path = path,
            name = frame.name,
            mediaType = mediaType,
            size = size,
            purpose = pending.purpose,
            modifiedAt = pending.modifiedAt?.takeIf { pending.expectedSize == size },
            verifyVersion = pending.verifyVersion
        )
        pendingOpen = null
        active = transfer
        state = state.copy(activeDownload = transfer.snapshot(), lastError = null)
        return SharedWorkspaceFileTransition(
            state,
            request = GatewayRequests.fileDownloadRead(transferId, 0L)
        )
    }

    private fun acceptChunk(frame: GatewayFrame): SharedWorkspaceFileTransition {
        val transfer = active ?: return SharedWorkspaceFileTransition(state)
        if (frame.transferId != transfer.transferId) return SharedWorkspaceFileTransition(state)
        val data = frame.data ?: return abort(transfer, "file-download-data-missing")
        if (frame.offset != transfer.receivedBytes) {
            return abort(transfer, "file-download-offset-mismatch")
        }
        val decoded = decodeBase64Strict(data)
            ?: return abort(transfer, "file-download-base64-invalid")
        val nextOffset = transfer.receivedBytes + decoded.size
        if (
            nextOffset > transfer.size || decoded.size > MAXIMUM_CHUNK_BYTES ||
            (decoded.isEmpty() && frame.eof != true)
        ) return abort(transfer, "file-download-size-invalid")
        transfer.sha256.update(decoded)
        transfer.receivedBytes = nextOffset
        state = state.copy(activeDownload = transfer.snapshot(), lastError = null)
        if (frame.eof != true) {
            return SharedWorkspaceFileTransition(
                state,
                request = GatewayRequests.fileDownloadRead(transfer.transferId, nextOffset),
                appendBase64Data = data
            )
        }
        val expectedHash = frame.sha256?.lowercase()
        val actualHash = transfer.sha256.digestHex()
        if (
            nextOffset != transfer.size || expectedHash == null ||
            !SHA256_PATTERN.matches(expectedHash) || expectedHash != actualHash
        ) return abort(transfer, "file-download-integrity-failed")
        active = null
        state = state.copy(activeDownload = null, lastError = null)
        val completion = SharedWorkspaceFileCompletion(
            sessionId = transfer.sessionId,
            path = transfer.path,
            name = transfer.name,
            mediaType = transfer.mediaType,
            size = transfer.size,
            sha256 = actualHash,
            purpose = transfer.purpose,
            modifiedAt = transfer.modifiedAt
        )
        // 下载期间文件可能更新。再 stat 一次，只有版本仍一致才允许写入缓存。
        if (transfer.verifyVersion && transfer.modifiedAt != null) {
            val requestId = transfer.transferId + "-verify"
            pendingVerification = PendingVerification(requestId, completion, transfer.workspaceSessionId)
            return SharedWorkspaceFileTransition(state, appendBase64Data = data,
                request = GatewayRequests.fileResolve(transfer.workspaceSessionId, transfer.path, requestId))
        }
        return SharedWorkspaceFileTransition(state, appendBase64Data = data, completion = completion)
    }

    private fun acceptCancelled(frame: GatewayFrame): SharedWorkspaceFileTransition {
        val transfer = active ?: return SharedWorkspaceFileTransition(state)
        if (frame.transferId != transfer.transferId) return SharedWorkspaceFileTransition(state)
        active = null
        state = state.copy(activeDownload = null, lastError = null)
        return SharedWorkspaceFileTransition(state, discardTransferId = transfer.transferId)
    }

    private fun abort(transfer: ActiveDownload, message: String): SharedWorkspaceFileTransition {
        active = null
        state = state.copy(activeDownload = null, lastError = message)
        return SharedWorkspaceFileTransition(
            state,
            request = GatewayRequests.fileDownloadCancel(transfer.transferId),
            discardTransferId = transfer.transferId
        )
    }

    private fun fail(message: String): SharedWorkspaceFileTransition {
        state = state.copy(isLoading = false, lastError = message)
        return SharedWorkspaceFileTransition(state)
    }

    private data class PendingList(val requestId: String, val sessionId: String, val path: String, val workspaceSessionId: String)
    private data class PendingVerification(
        val requestId: String,
        val completion: SharedWorkspaceFileCompletion,
        val workspaceSessionId: String
    )
    private data class PendingOpen(
        val requestId: String,
        val sessionId: String,
        val path: String,
        val purpose: String,
        val workspaceSessionId: String,
        val useCache: Boolean = false,
        val modifiedAt: Double? = null,
        val expectedSize: Long? = null,
        val verifyVersion: Boolean = false,
        val allowDirectory: Boolean = false,
        val workspaceRoot: String? = null
    )

    private data class ActiveDownload(
        val transferId: String,
        val sessionId: String,
        val workspaceSessionId: String,
        val path: String,
        val name: String,
        val mediaType: String,
        val size: Long,
        val purpose: String,
        val modifiedAt: Double? = null,
        val verifyVersion: Boolean = false,
        var receivedBytes: Long = 0L,
        val sha256: Sha256 = Sha256()
    ) {
        fun snapshot() = SharedWorkspaceFileDownload(
            transferId, sessionId, path, name, mediaType, size, receivedBytes, purpose
        )
    }

    companion object {
        private const val MAXIMUM_FILE_BYTES = 512L * 1_024 * 1_024
        private const val MAXIMUM_CHUNK_BYTES = 4 * 1_024 * 1_024
        private const val DEFAULT_MEDIA_TYPE = "application/octet-stream"
        private val PURPOSES = setOf("preview", "download", "install")
        private val REQUEST_TYPES = setOf(
            "file-resolve", "file-list", "file-download-open", "file-download-read", "file-download-cancel"
        )
        private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
    }
}

private fun requireIdentifier(value: String, name: String) {
    require(value.isNotBlank() && value.none { it.isWhitespace() || it.isISOControl() }) { "$name-invalid" }
}

private fun normalizeRelativePath(value: String?, allowRoot: Boolean): String {
    val normalized = value?.trim().orEmpty().ifEmpty { "." }
    if (normalized == ".") {
        require(allowRoot) { "path-empty" }
        return "."
    }
    require(normalized == "." || (!normalized.startsWith('/') && !normalized.startsWith('\\'))) {
        "path-absolute"
    }
    require('\u0000' !in normalized && '\\' !in normalized && !Regex("^[A-Za-z]:/").containsMatchIn(normalized)) { "path-invalid" }
    val segments = normalized.split('/')
    require(segments.none { it.isEmpty() || it == "." || it == ".." }) { "path-segment-invalid" }
    return normalized
}

/** 文件引用可由 Host 授权到工作区外；目录浏览仍只使用 normalizeRelativePath。 */
private fun normalizeFilePath(value: String?): String {
    val trimmed = value?.trim().orEmpty()
    val windowsAbsolute = Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(trimmed)
    val uncAbsolute = trimmed.startsWith("\\\\") || trimmed.startsWith("//")
    if (!windowsAbsolute && !uncAbsolute && !trimmed.startsWith('/')) {
        return normalizeRelativePath(trimmed, allowRoot = false)
    }
    require('\u0000' !in trimmed) { "path-invalid" }
    val normalized = if (windowsAbsolute || uncAbsolute) trimmed.replace('\\', '/') else trimmed
    require('\\' !in normalized) { "path-invalid" }
    val tail = normalized.drop(if (windowsAbsolute) 3 else if (uncAbsolute) 2 else 1)
    require(tail.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "path-segment-invalid" }
    require(!uncAbsolute || tail.split('/').size >= 3) { "path-segment-invalid" }
    return normalized
}

private fun isDirectChild(parent: String, child: String): Boolean {
    val prefix = if (parent == ".") "" else "$parent/"
    val remainder = child.removePrefix(prefix)
    return child.startsWith(prefix) && remainder.isNotEmpty() && '/' !in remainder
}

private fun decodeBase64Strict(value: String): ByteArray? {
    if (value.isEmpty()) return ByteArray(0)
    if (value.length % 4 != 0) return null
    val padding = value.takeLastWhile { it == '=' }.length
    if (padding > 2 || '=' in value.dropLast(padding)) return null
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    val clean = value.dropLast(padding)
    if (clean.any { it !in alphabet }) return null
    val output = ByteArray((value.length / 4) * 3 - padding)
    var accumulator = 0
    var bits = 0
    var index = 0
    clean.forEach { character ->
        accumulator = (accumulator shl 6) or alphabet.indexOf(character)
        bits += 6
        if (bits >= 8) {
            bits -= 8
            if (index < output.size) output[index++] = ((accumulator shr bits) and 0xff).toByte()
        }
    }
    return if (index == output.size) output else null
}
