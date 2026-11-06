package com.clarklevis.dsh.shared.protocol

import kotlinx.serialization.json.Json

/** cwd 为空表示使用宿主默认工作区；它与移动端当前浏览的工作区无关。 */
data class ScheduleTargetDraft(val kind: String, val cwd: String)

object ScheduleTargetEditor {
    private fun record(json: String) = JsonValue.fromJsonElement(Json.parseToJsonElement(json))

    fun fromRecord(recordJson: String): ScheduleTargetDraft {
        val raw = record(recordJson)
        val target = raw["target"]
        return ScheduleTargetDraft(
            kind = target?.get("kind")?.stringValue ?: if (!raw["sessionId"]?.stringValue.isNullOrEmpty()) "session" else "new_once",
            cwd = target?.get("create")?.get("cwd")?.stringValue.orEmpty()
        )
    }

    /** new_once 的 sessionId 在首次运行前只是宿主保留的 ID，不能当作已有会话。 */
    fun currentSessionId(recordJson: String): String? {
        val raw = record(recordJson)
        val target = raw["target"]
        val id = if (target == null) raw["sessionId"]?.stringValue
            else if (target["kind"]?.stringValue == "session") target["sessionId"]?.stringValue else null
        return id?.takeIf { it.isNotBlank() }
    }

    fun validationError(draft: ScheduleTargetDraft, recordJson: String): String? = when {
        draft.kind !in listOf("session", "new_once", "new") -> "请选择执行位置。"
        draft.kind == "session" && currentSessionId(recordJson) == null -> "当前任务尚无可用的关联会话，请选择新建会话。"
        else -> null
    }

    fun changeJson(draft: ScheduleTargetDraft, recordJson: String): String? {
        val original = fromRecord(recordJson)
        // 不重发 new_once，避免宿主重置预留会话；session 模式忽略隐藏的工作区草稿。
        if (draft.kind == original.kind && (draft.kind == "session" || draft.cwd == original.cwd)) return null
        if (validationError(draft, recordJson) != null) return null
        val raw = record(recordJson)
        val fields = mutableMapOf<String, JsonValue>("kind" to JsonValue.StringValue(draft.kind))
        if (draft.kind == "session") {
            fields["sessionId"] = JsonValue.StringValue(requireNotNull(currentSessionId(recordJson)))
        } else {
            val previous = raw["target"]?.get("create")
            // 与官方 UI 一致：工作区不变时保留原 agentPreset，换工作区时采用默认预设。
            val create = mutableMapOf<String, JsonValue>()
            if (draft.cwd.isNotEmpty()) create["cwd"] = JsonValue.StringValue(draft.cwd)
            if (draft.cwd == original.cwd) previous?.get("agentPreset")?.let { create["agentPreset"] = it }
            fields["create"] = JsonValue.ObjectValue(create)
        }
        return JsonValue.ObjectValue(fields).displayText()
    }
}
