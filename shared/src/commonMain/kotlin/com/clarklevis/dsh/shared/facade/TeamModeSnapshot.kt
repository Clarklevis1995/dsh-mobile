package com.clarklevis.dsh.shared.facade

import com.clarklevis.dsh.shared.i18n.UiMessage
import kotlinx.serialization.Serializable

/** 根会话的真实模式；pending 只描述请求，不替代 Host 投影中的 enabled。 */
@Serializable
data class TeamModeSnapshot(
    val sessionId: String = "",
    val supported: Boolean = false,
    val enabled: Boolean? = null,
    val canChange: Boolean = false,
    val pending: Boolean = false,
    val requiresDisableConfirmation: Boolean = false,
    val error: UiMessage? = null
)

@Serializable
data class TeamModeCommand(val requestId: String, val payload: String)
