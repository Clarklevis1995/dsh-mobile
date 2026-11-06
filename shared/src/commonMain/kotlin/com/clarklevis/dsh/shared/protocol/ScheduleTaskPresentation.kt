package com.clarklevis.dsh.shared.protocol

/** 两端共用状态文案与可编辑性，暂停和结束不能混为一谈。 */
object ScheduleTaskPresentation {
    fun statusSource(status: String): String = when (status) {
        "active" -> "运行中"
        "paused" -> "已暂停"
        "ended" -> "已结束"
        else -> "状态未知"
    }
    fun canEdit(status: String): Boolean = status == "active" || status == "paused"
}
