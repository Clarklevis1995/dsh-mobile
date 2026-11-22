package com.clarklevis.dsh.shared.protocol

import com.clarklevis.dsh.shared.i18n.UiMessage

/** 定时任务状态和投递目标的两端共用展示规则。 */
object ScheduleTaskPresentation {
    fun statusLabel(status: String): UiMessage = UiMessage(when (status) {
        "active" -> "schedule.status.active"
        "paused" -> "schedule.status.paused"
        "ended" -> "schedule.status.ended"
        else -> "schedule.status.unknown"
    })
    fun canEdit(status: String): Boolean = status == "active" || status == "paused"

    fun linkedSessionId(targetKind: String?, targetSessionId: String?, legacySessionId: String?): String? =
        when (targetKind) {
            "new" -> null
            "session", "new_once" -> targetSessionId
            else -> targetSessionId ?: legacySessionId
        }?.takeIf(String::isNotBlank)

    fun unavailableSessionLabel(targetKind: String?, hasDelivery: Boolean, archived: Boolean): UiMessage =
        UiMessage(when {
            targetKind == "new" -> "schedule.session.perRun"
            targetKind == "new_once" && !hasDelivery && !archived -> "schedule.session.pending"
            else -> "schedule.session.unavailable"
        })
}
