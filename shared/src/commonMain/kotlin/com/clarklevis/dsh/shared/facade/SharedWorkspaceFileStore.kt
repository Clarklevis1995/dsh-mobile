package com.clarklevis.dsh.shared.facade

import com.clarklevis.dsh.shared.gateway.GatewayRequest
import com.clarklevis.dsh.shared.gateway.GatewayRequests
import com.clarklevis.dsh.shared.protocol.GatewayDirectoryItem
import com.clarklevis.dsh.shared.protocol.GatewayFrame
import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder

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
    val purpose: String
)

/**
 * 平台文件 I/O 之外的工作区文件状态机。
 *
 * commonMain 负责请求关联、相对路径边界、分块偏移、Base64、长度与 SHA-256；
 * iOS/Android 只把 [appendBase64Data] 追加到当前临时文件，并在 [completion]
 * 出现后原子提交或交给系统预览/另存为。
 */
data class SharedWorkspaceFileTransition(
    val snapshot: SharedWorkspaceFileSnapshot,
    val request: GatewayRequest? = null,
    val appendBase64Data: String? = null,
    val completion: SharedWorkspaceFileCompletion? = null,
    val discardTransferId: String? = null
)

class SharedWorkspaceFileStore {
    private var state = SharedWorkspaceFileSnapshot()
    private var pendingList: PendingList? = null
    private var pendingOpen: PendingOpen? = null
    private var active: ActiveDownload? = null

    fun snapshot(): SharedWorkspaceFileSnapshot = state

    fun reset(sessionId: String?): SharedWorkspaceFileTransition {
        val discard = active?.transferId
        pendingList = null
        pendingOpen = null
        active = null
        state = SharedWorkspaceFileSnapshot(sessionId = sessionId?.takeIf(String::isNotBlank))
        return SharedWorkspaceFileTransition(state, discardTransferId = discard)
    }

    fun load(sessionId: String, path: String?, requestId: String): SharedWorkspaceFileTransition {
        requireIdentifier(sessionId, "sessionId")
        requireIdentifier(requestId, "requestId")
        val normalizedPath = normalizeRelativePath(path, allowRoot = true)
        pendingList = PendingList(requestId, sessionId, normalizedPath)
        state = if (state.sessionId != sessionId) {
            SharedWorkspaceFileSnapshot(
                sessionId = sessionId,
                path = normalizedPath,
                isLoading = true
            )
        } else {
            state.copy(
                path = normalizedPath,
                isLoading = true,
                lastError = null
            )
        }
        return SharedWorkspaceFileTransition(
            state,
            request = GatewayRequests.fileList(
                sessionId,
                normalizedPath.takeUnless { it == "." },
                requestId
            )
        )
    }

    fun download(
        sessionId: String,
        path: String,
        requestId: String,
        purpose: String
    ): SharedWorkspaceFileTransition {
        requireIdentifier(sessionId, "sessionId")
        requireIdentifier(requestId, "requestId")
        require(purpose in PURPOSES) { "purpose-invalid" }
        val normalizedPath = normalizeRelativePath(path, allowRoot = false)
        if (active != null || pendingOpen != null) return fail("download-busy")
        pendingOpen = PendingOpen(requestId, sessionId, normalizedPath, purpose)
        state = state.copy(lastError = null)
        return SharedWorkspaceFileTransition(
            state,
            request = GatewayRequests.fileDownloadOpen(sessionId, normalizedPath, requestId)
        )
    }

    fun cancel(): SharedWorkspaceFileTransition {
        val transfer = active
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
        val matches = when (requestType) {
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
        "file-list" -> acceptList(frame)
        "file-download-opened" -> acceptOpened(frame)
        "file-download-chunk" -> acceptChunk(frame)
        "file-download-cancelled" -> acceptCancelled(frame)
        "error" -> frame.requestType
            ?.takeIf { it in REQUEST_TYPES }
            ?.let { requestFailed(it, frame.message ?: frame.code) }
            ?: SharedWorkspaceFileTransition(state)
        else -> SharedWorkspaceFileTransition(state)
    }

    private fun acceptList(frame: GatewayFrame): SharedWorkspaceFileTransition {
        val pending = pendingList ?: return fail("file-list-unexpected")
        if (frame.requestId != pending.requestId) return SharedWorkspaceFileTransition(state)
        if (frame.sessionId != pending.sessionId) {
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
        val pending = pendingOpen ?: return fail("file-download-open-unexpected")
        val transferId = frame.transferId
        val size = frame.size
        val chunkBytes = frame.chunkBytes
        val path = runCatching { normalizeRelativePath(frame.path, allowRoot = false) }
            .getOrElse { return fail("file-download-path-invalid") }
        if (
            frame.requestId != pending.requestId || frame.sessionId != pending.sessionId ||
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
            path = path,
            name = frame.name,
            mediaType = mediaType,
            size = size,
            purpose = pending.purpose
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
        val transfer = active ?: return fail("file-download-chunk-unexpected")
        val data = frame.data ?: return abort(transfer, "file-download-data-missing")
        if (frame.transferId != transfer.transferId || frame.offset != transfer.receivedBytes) {
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
        return SharedWorkspaceFileTransition(
            state,
            appendBase64Data = data,
            completion = SharedWorkspaceFileCompletion(
                sessionId = transfer.sessionId,
                path = transfer.path,
                name = transfer.name,
                mediaType = transfer.mediaType,
                size = transfer.size,
                sha256 = actualHash,
                purpose = transfer.purpose
            )
        )
    }

    private fun acceptCancelled(frame: GatewayFrame): SharedWorkspaceFileTransition {
        val transfer = active ?: return SharedWorkspaceFileTransition(state)
        if (frame.transferId != transfer.transferId) return fail("file-download-cancel-mismatch")
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

    private data class PendingList(val requestId: String, val sessionId: String, val path: String)
    private data class PendingOpen(
        val requestId: String,
        val sessionId: String,
        val path: String,
        val purpose: String
    )

    private data class ActiveDownload(
        val transferId: String,
        val sessionId: String,
        val path: String,
        val name: String,
        val mediaType: String,
        val size: Long,
        val purpose: String,
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
        private val PURPOSES = setOf("preview", "download")
        private val REQUEST_TYPES = setOf(
            "file-list", "file-download-open", "file-download-read", "file-download-cancel"
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
    require('\u0000' !in normalized && '\\' !in normalized) { "path-invalid" }
    val segments = normalized.split('/')
    require(segments.none { it.isEmpty() || it == "." || it == ".." }) { "path-segment-invalid" }
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

/** 小型增量 SHA-256，避免把最大 512 MiB 文件保留在 commonMain 内存。 */
private class Sha256 {
    private val state = intArrayOf(
        0x6a09e667, 0xbb67ae85.toInt(), 0x3c6ef372, 0xa54ff53a.toInt(),
        0x510e527f, 0x9b05688c.toInt(), 0x1f83d9ab, 0x5be0cd19
    )
    private val buffer = ByteArray(64)
    private var bufferSize = 0
    private var totalBytes = 0L

    fun update(bytes: ByteArray) {
        totalBytes += bytes.size
        var offset = 0
        while (offset < bytes.size) {
            val copied = minOf(64 - bufferSize, bytes.size - offset)
            bytes.copyInto(buffer, bufferSize, offset, offset + copied)
            bufferSize += copied
            offset += copied
            if (bufferSize == 64) {
                compress(buffer)
                bufferSize = 0
            }
        }
    }

    fun digestHex(): String {
        val bitLength = totalBytes * 8
        buffer[bufferSize++] = 0x80.toByte()
        if (bufferSize > 56) {
            while (bufferSize < 64) buffer[bufferSize++] = 0
            compress(buffer)
            bufferSize = 0
        }
        while (bufferSize < 56) buffer[bufferSize++] = 0
        for (shift in 56 downTo 0 step 8) buffer[bufferSize++] = (bitLength ushr shift).toByte()
        compress(buffer)
        return state.joinToString("") { value -> value.toUInt().toString(16).padStart(8, '0') }
    }

    private fun compress(block: ByteArray) {
        val words = IntArray(64)
        for (index in 0 until 16) {
            val base = index * 4
            words[index] = ((block[base].toInt() and 0xff) shl 24) or
                ((block[base + 1].toInt() and 0xff) shl 16) or
                ((block[base + 2].toInt() and 0xff) shl 8) or
                (block[base + 3].toInt() and 0xff)
        }
        for (index in 16 until 64) {
            val s0 = words[index - 15].rotateRight(7) xor words[index - 15].rotateRight(18) xor
                (words[index - 15] ushr 3)
            val s1 = words[index - 2].rotateRight(17) xor words[index - 2].rotateRight(19) xor
                (words[index - 2] ushr 10)
            words[index] = words[index - 16] + s0 + words[index - 7] + s1
        }
        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        var e = state[4]
        var f = state[5]
        var g = state[6]
        var h = state[7]
        for (index in 0 until 64) {
            val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val choice = (e and f) xor (e.inv() and g)
            val first = h + s1 + choice + SHA256_K[index] + words[index]
            val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val majority = (a and b) xor (a and c) xor (b and c)
            val second = s0 + majority
            h = g; g = f; f = e; e = d + first
            d = c; c = b; b = a; a = first + second
        }
        state[0] += a; state[1] += b; state[2] += c; state[3] += d
        state[4] += e; state[5] += f; state[6] += g; state[7] += h
    }
}

private val SHA256_K = intArrayOf(
    0x428a2f98, 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(), 0x3956c25b, 0x59f111f1,
    0x923f82a4.toInt(), 0xab1c5ed5.toInt(), 0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3,
    0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(), 0xe49b69c1.toInt(),
    0xefbe4786.toInt(), 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(), 0xc6e00bf3.toInt(),
    0xd5a79147.toInt(), 0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
    0x650a7354, 0x766a0abb, 0x81c2c92e.toInt(), 0x92722c85.toInt(), 0xa2bfe8a1.toInt(),
    0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(), 0xd192e819.toInt(), 0xd6990624.toInt(),
    0xf40e3585.toInt(), 0x106aa070, 0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3,
    0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814.toInt(),
    0x8cc70208.toInt(), 0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt()
)
