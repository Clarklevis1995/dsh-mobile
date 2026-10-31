package com.clarklevis.dsh.android

import android.content.Context
import android.util.Log
import java.io.File

/** 应用级唯一协调器，多个网关共享物理预算和租约。 */
internal object AndroidCacheStorage {
    @Volatile private var manager: AndroidCacheDiskManager? = null

    private fun allocatedSize(file: File): Long = runCatching {
        maxOf(file.length(), android.system.Os.lstat(file.absolutePath).st_blocks * 512L)
    }.getOrDefault(file.length())

    fun shared(context: Context): AndroidCacheDiskManager = manager ?: synchronized(this) {
        manager ?: AndroidCacheDiskManager(
            context.applicationContext.cacheDir,
            databaseBytes = {
                val database = context.getDatabasePath("chat-history-room.db")
                listOf(database, File(database.path + "-wal"), File(database.path + "-shm"), File(database.path + "-journal"))
                    .sumOf { if (it.isFile) allocatedSize(it) else 0 }
            },
            allocatedBytes = ::allocatedSize,
            log = { runCatching { com.clarklevis.dsh.android.platform.LocalAppLog.i("CacheStorage", it) } }
        ).also { manager = it }
    }
}
