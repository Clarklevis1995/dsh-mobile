package com.clarklevis.dsh.shared.i18n

import kotlinx.serialization.Serializable

/**
 * 共享层输出的用户可见文案 ID；平台在渲染时按当前语言解析。
 * [RAW_KEY] 表示主机或平台已提供的原文，原样显示 `args[0]`。
 */
@Serializable
data class UiMessage(val key: String, val args: List<String> = emptyList()) {
    companion object {
        const val RAW_KEY = "raw"
        fun raw(text: String) = UiMessage(RAW_KEY, listOf(text))
    }
}

/** 可展示的校验失败：[uiMessage] 交给平台本地化，`message` 为英文诊断文本，仅供日志。 */
open class UiMessageException(val uiMessage: UiMessage, logMessage: String) : IllegalArgumentException(logMessage)
