package com.clarklevis.dsh.android.platform

import android.content.Context
import com.clarklevis.dsh.shared.platform.GatewayAttachmentCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.LinkedHashMap

class AndroidAttachmentCache(
    context: Context,
    private val ttlMilliseconds: Long = DEFAULT_TTL_MILLISECONDS,
    private val now: () -> Long = System::currentTimeMillis
) : GatewayAttachmentCache {
    private val directory = context.cacheDir.resolve("gateway-image-attachments")
    private val memory = LinkedHashMap<String, MemoryEntry>(16, 0.75f, true)
    private var memoryBytes = 0
    private val lock = Any()

    init {
        directory.mkdirs()
    }

    override suspend fun read(attachmentId: String): ByteArray? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val entry = memory[attachmentId]
            if (entry != null && entry.writtenAt + ttlMilliseconds > now()) {
                entry.bytes.copyOf()
            } else {
                memory.remove(attachmentId)?.let { memoryBytes -= it.bytes.size }
                null
            }
        }?.let { return@withContext it }
        val file = fileFor(attachmentId)
        if (!file.isFile || file.lastModified() + ttlMilliseconds <= now()) {
            file.delete()
            return@withContext null
        }
        if (file.length() > DISK_LIMIT_BYTES) {
            file.delete()
            return@withContext null
        }
        runCatching {
            file.inputStream().use { input -> readBytesWithLimit(input, DISK_LIMIT_BYTES.toInt()) }
        }.getOrNull()?.also {
            file.setLastModified(now())
            putMemory(attachmentId, it)
        }
    }

    override suspend fun write(attachmentId: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        if (bytes.size > DISK_LIMIT_BYTES) return@withContext false
        directory.mkdirs()
        val target = fileFor(attachmentId)
        val temporary = File(directory, "${target.name}.tmp")
        val committed = runCatching {
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(target)) {
                target.writeBytes(bytes)
                temporary.delete()
            }
            target.setLastModified(now())
            target.isFile && target.length() == bytes.size.toLong()
        }.getOrDefault(false)
        temporary.delete()
        if (!committed) {
            target.delete()
            return@withContext false
        }
        putMemory(attachmentId, bytes)
        pruneDisk()
        val retained = target.isFile && target.length() == bytes.size.toLong()
        if (!retained) removeMemory(attachmentId)
        retained
    }

    override suspend fun removeExpired() = withContext(Dispatchers.IO) {
        pruneDisk()
    }

    private fun putMemory(attachmentId: String, bytes: ByteArray) {
        synchronized(lock) {
            memory.remove(attachmentId)?.let { memoryBytes -= it.bytes.size }
            memory[attachmentId] = MemoryEntry(bytes.copyOf(), now())
            memoryBytes += bytes.size
            val iterator = memory.entries.iterator()
            while (memoryBytes > MEMORY_LIMIT_BYTES && iterator.hasNext()) {
                memoryBytes -= iterator.next().value.bytes.size
                iterator.remove()
            }
        }
    }

    private fun removeMemory(attachmentId: String) {
        synchronized(lock) {
            memory.remove(attachmentId)?.let { memoryBytes -= it.bytes.size }
        }
    }

    private fun fileFor(attachmentId: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(attachmentId.toByteArray())
        return directory.resolve(digest.joinToString("") { "%02x".format(it) })
    }

    private data class MemoryEntry(val bytes: ByteArray, val writtenAt: Long)

    private fun pruneDisk() {
        val files = directory.listFiles().orEmpty()
        val evictions = attachmentCacheEvictions(
            files.map { file ->
                CacheFileMetadata(file.name, file.length(), file.lastModified(), file.isFile)
            },
            now = now(),
            ttlMilliseconds = ttlMilliseconds,
            maximumBytes = DISK_LIMIT_BYTES
        )
        files.filter { it.name in evictions }.forEach(File::delete)
    }

    companion object {
        private const val DEFAULT_TTL_MILLISECONDS = 7L * 24 * 60 * 60 * 1_000
        private const val MEMORY_LIMIT_BYTES = 32 * 1_024 * 1_024
        private const val DISK_LIMIT_BYTES = 32L * 1_024 * 1_024
    }
}

internal data class CacheFileMetadata(
    val name: String,
    val byteCount: Long,
    val lastModified: Long,
    val isFile: Boolean = true
)

internal fun attachmentCacheEvictions(
    files: List<CacheFileMetadata>,
    now: Long,
    ttlMilliseconds: Long,
    maximumBytes: Long
): Set<String> {
    val cutoff = now - ttlMilliseconds
    val evicted = files.filter {
        !it.isFile || it.name.endsWith(".tmp") || it.lastModified <= cutoff
    }.mapTo(mutableSetOf(), CacheFileMetadata::name)
    val valid = files.filter { it.name !in evicted }.sortedBy(CacheFileMetadata::lastModified)
    var total = valid.sumOf(CacheFileMetadata::byteCount)
    valid.forEach { file ->
        if (total > maximumBytes) {
            evicted += file.name
            total -= file.byteCount
        }
    }
    return evicted
}
