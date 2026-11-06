package com.clarklevis.dsh.shared.protocol

import com.clarklevis.dsh.shared.i18n.UiMessage

/** 两端共用状态文案与可编辑性，暂停和结束不能混为一谈。 */
object ScheduleTaskPresentation {
    fun statusLabel(status: String): UiMessage = UiMessage(when (status) {
        "active" -> "schedule.status.active"
        "paused" -> "schedule.status.paused"
        "ended" -> "schedule.status.ended"
        else -> "schedule.status.unknown"
    })
    fun canEdit(status: String): Boolean = status == "active" || status == "paused"
}
