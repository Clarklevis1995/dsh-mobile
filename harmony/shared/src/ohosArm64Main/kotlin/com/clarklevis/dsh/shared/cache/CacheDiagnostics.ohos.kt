package com.clarklevis.dsh.shared.cache

internal actual fun writeChatCacheLog(
    message: String,
    warning: Boolean,
) {
    // 鸿蒙首版不启用磁盘缓存，业务正文和凭据不写入日志。
}
