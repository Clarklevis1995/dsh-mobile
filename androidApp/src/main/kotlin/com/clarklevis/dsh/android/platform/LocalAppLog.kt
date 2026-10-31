package com.clarklevis.dsh.android.platform

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 串行、有限队列写入；原始日志与导出副本共用 200 MB 预算。 */
internal object LocalAppLog {
    private const val MAX_PENDING_ENTRIES = 1024
    private val executor =
        ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(MAX_PENDING_ENTRIES),
            ThreadPoolExecutor.AbortPolicy(),
        )

    @Volatile private var store: HourlyLogStore? = null

    fun start(context: Context) {
        com.clarklevis.dsh.shared.cache.ChatCacheDiagnostics.localLogWriter = { i("ChatHistoryCache", it) }
        val directory = File(context.filesDir, "diagnostic-logs")
        executor.execute {
            runCatching {
                store = HourlyLogStore(directory)
                store?.append("INFO App started")
            }
        }
    }

    fun println(
        priority: Int,
        tag: String,
        message: String,
    ): Int {
        val result = Log.println(priority, tag, message)
        val capturedAt = Date()
        runCatching { executor.execute { runCatching { store?.append("$priority $tag $message", capturedAt) } } }
        return result
    }

    fun d(
        tag: String,
        message: String,
    ) = println(Log.DEBUG, tag, message)

    fun i(
        tag: String,
        message: String,
    ) = println(Log.INFO, tag, message)

    fun w(
        tag: String,
        message: String,
    ) = println(Log.WARN, tag, message)

    /** 排在已有写入之后创建一致的快照；只能从后台线程调用。 */
    fun export(): File =
        executor
            .submit<File> {
                checkNotNull(store) { "日志尚未初始化" }.export()
            }.get()
}

internal class HourlyLogStore(
    private val directory: File,
    private val limit: Long = 200_000_000,
) {
    private companion object {
        const val MAX_DIRECTORY_BYTES = 200_000_000L
        const val MAX_ENTRY_CHARACTERS = 16_384
        const val MAX_CHUNK_BYTES = 4_000_000L
        const val CHUNK_BUDGET_DIVISOR = 4
        const val PART_DIGITS = 5
        const val ZIP_HEADROOM_BYTES = 1_000_000
    }

    private val exports = File(directory, "exports")
    private val hour = SimpleDateFormat("yyyyMMddHH'00'", Locale.ROOT)
    private val timestamp = SimpleDateFormat("yyyyMMddHHmm", Locale.ROOT)
    private val lineTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.ROOT)

    init {
        require(limit in 1..MAX_DIRECTORY_BYTES)
        check(directory.isDirectory || directory.mkdirs())
        exports.deleteRecursively()
        trim(limit)
    }

    fun append(
        message: String,
        now: Date = Date(),
    ) {
        val data = "${lineTime.format(now)} ${message.take(MAX_ENTRY_CHARACTERS)}\n".toByteArray(Charsets.UTF_8)
        val prefix = hour.format(now)
        val chunks =
            logs()
                .filter { it.nameWithoutExtension == prefix || it.name.startsWith("$prefix-part-") }
                .sortedBy { it.nameWithoutExtension }
        var file = chunks.lastOrNull() ?: File(directory, "$prefix.log")
        if (file.length() + data.size > minOf(MAX_CHUNK_BYTES, limit / CHUNK_BUDGET_DIVISOR)) {
            val next = (file.nameWithoutExtension.substringAfterLast("-part-").toIntOrNull() ?: 0) + 1
            val part = next.toString().padStart(PART_DIGITS, '0')
            file = File(directory, "$prefix-part-$part.log")
        }
        require(data.size <= limit) { "单条日志超过目录容量上限" }
        trim(limit - data.size)
        file.appendBytes(data)
    }

    private fun logs() =
        directory
            .listFiles()
            .orEmpty()
            .filter { it.extension == "log" }
            .sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })

    private fun directoryFiles(): List<File> =
        directory
            .walkTopDown()
            .onFail { _, error -> throw error }
            .filter { it.isFile }
            .toList()

    private fun directoryBytes(): Long = directoryFiles().sumOf { it.length() }

    private fun trim(budget: Long) {
        require(budget >= 0)
        // 递归统计整个目录，包含旧版本、嵌套目录及失败导出的残留文件。
        val files =
            directoryFiles().sortedWith(
                compareBy<File> { it.parentFile == exports }.thenBy { it.lastModified() }.thenBy { it.path },
            )
        var bytes = files.sumOf { it.length() }
        for (file in files) {
            if (bytes <= budget) break
            val size = file.length()
            check(file.delete()) { "无法清理旧日志" }
            bytes -= size
        }
        check(directoryBytes() <= budget) { "日志目录容量无法降至上限以内" }
    }

    private fun addToZip(
        zip: ZipOutputStream,
        file: File,
    ) {
        zip.putNextEntry(ZipEntry(file.name))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    fun export(): File {
        // 为不易压缩的日志和 ZIP 文件头预留空间，导出期间仍保持总量上限。
        exports.deleteRecursively()
        check(exports.mkdirs())
        trim((limit - ZIP_HEADROOM_BYTES).coerceAtLeast(0) / 2)
        val target = File(exports, "${timestamp.format(Date())}.zip")
        var completed = false
        try {
            // ZIP 文件头和不可压缩内容也受实时字节预算约束，不能等写完后才检查。
            val remaining = limit - directoryBytes()
            ZipOutputStream(BudgetOutputStream(target.outputStream().buffered(), remaining)).use { zip ->
                logs().forEach { file -> addToZip(zip, file) }
            }
            check(directoryBytes() <= limit)
            completed = true
            return target
        } finally {
            if (!completed) target.delete()
        }
    }
}

/** 在实际写入前检查剩余预算，覆盖 ZIP 的正文、文件头和结束记录。 */
internal class BudgetOutputStream(
    private val output: OutputStream,
    private var remaining: Long,
) : OutputStream() {
    override fun write(value: Int) {
        reserve(1)
        output.write(value)
    }

    override fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        reserve(length)
        output.write(bytes, offset, length)
    }

    private fun reserve(bytes: Int) {
        if (bytes > remaining) throw IOException("日志目录已达到容量上限")
        remaining -= bytes
    }

    override fun flush() = output.flush()

    override fun close() = output.close()
}
