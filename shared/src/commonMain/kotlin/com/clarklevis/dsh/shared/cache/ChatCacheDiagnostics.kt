package com.clarklevis.dsh.shared.cache

enum class ChatCacheRestoreSkipReason { ALREADY_LOADED, SELECTION_CHANGED, FORMAT_CHANGED, INVALID_WINDOW }

/** 平台恢复入口也使用同一标签；仅记录固定原因与数量，不接收正文或会话标识。 */
object ChatCacheDiagnostics {
    /** 应用启动时安装，回调只负责入队，不能阻塞缓存线程。 */
    var localLogWriter: ((String) -> Unit)? = null

    fun databaseUnavailable() {
        chatCacheLog("database-unavailable", "reason=initialization", warning = true)
    }

    fun restoreSkipped(reason: ChatCacheRestoreSkipReason) {
        chatCacheLog("restore-skipped", "reason=${reason.name.lowercase()}")
    }
}

internal fun chatCacheLog(event: String, details: String = "", warning: Boolean = false) {
    // 日志后端不可用（例如 JVM 测试中的 Android stub）不得影响缓存与聊天。
    runCatching { ChatCacheDiagnostics.localLogWriter?.invoke("[ChatHistoryCache] event=$event $details".trimEnd()) }
    runCatching { writeChatCacheLog("[ChatHistoryCache] event=$event $details".trimEnd(), warning) }
}

internal expect fun writeChatCacheLog(message: String, warning: Boolean)
