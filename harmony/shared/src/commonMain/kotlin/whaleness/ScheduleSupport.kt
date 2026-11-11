package whaleness

import com.clarklevis.dsh.shared.gateway.GatewayRequest
import com.clarklevis.dsh.shared.gateway.GatewayRequests
import com.clarklevis.dsh.shared.protocol.GatewayFrame
import com.clarklevis.dsh.shared.protocol.JsonValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class HarmonyScheduledTask(
    val id: String,
    val sessionId: String,
    val title: String,
    val prompt: String,
    val kind: String,
    val status: String,
    val scheduledAt: String,
    val clock: String?,
    val timeZone: String?,
    val expression: String?,
    val weekdays: List<Int>,
    val lastDelivery: String?,
    val everySeconds: Long? = null,
)

@Serializable
data class HarmonyScheduleEditor(
    val token: String,
    val task: HarmonyScheduledTask,
    val eligible: Boolean,
    val pending: Boolean,
    val completed: Boolean,
    val error: String?,
)

@Serializable
data class HarmonySchedules(
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val fresh: Boolean = false,
    val uncertain: Boolean = false,
    val items: List<HarmonyScheduledTask> = emptyList(),
    val pendingTask: String? = null,
    val pendingSession: String? = null,
    val editor: HarmonyScheduleEditor? = null,
    val error: String? = null,
    val notice: String? = null,
)

internal data class ScheduleChange(
    val view: HarmonySchedules? = null,
    val requests: List<GatewayRequest> = emptyList(),
)

/** 编辑使用原始 Host 记录作为 expected，拒绝覆盖已变化的任务；不虚构创建／暂停协议。 */
internal class ScheduleSupport {
    private val records = mutableMapOf<Pair<String, String>, JsonValue>()
    private var ordinal = 0L
    private var catalogKey: String? = null
    private var catalogQueued = false
    private var uncertain = false
    private var visited = false
    private var loaded = false
    private var fresh = false
    private var mutation: GatewayRequest? = null
    private var mutationTask: Pair<String, String>? = null
    private var editor: Triple<String, Pair<String, String>, JsonValue>? = null
    private var editorCompleted = false
    private var editorError: String? = null
    private var error: String? = null
    private var notice: String? = null
    private var published: HarmonySchedules? = null

    fun reset() {
        records.clear()
        catalogKey = null
        catalogQueued = false
        uncertain = false
        visited = false
        loaded = false
        fresh =
            false
        mutation = null
        mutationTask = null
        editor = null
        editorCompleted = false
        editorError = null
        error = null
        notice = null
        published =
            null
    }

    fun disconnected(): ScheduleChange {
        catalogKey = null
        catalogQueued = false
        uncertain = false
        fresh = false
        mutation = null
        mutationTask =
            null
        return changed()
    }

    fun pendingKeys(): List<String> = listOfNotNull(catalogKey, mutation?.correlationId?.let { "schedules:write:$it" })

    fun command(
        command: HarmonyCommand,
        connected: Boolean,
    ): ScheduleChange {
        visited = true
        if (command.op == "schedules-open") {
            if (!connected) {
                error = "连接网关后才能读取计划任务"
                return changed()
            }
            return refresh()
        }
        require(connected && fresh && !uncertain && mutation == null && catalogKey == null) { "任务状态尚未确认，请刷新后重试" }
        val key = requireNotNull(command.sessionId) to requireNotNull(command.itemId)
        val raw = requireNotNull(records[key]) { "任务已不存在，请刷新列表" }
        if (command.op == "schedule-edit-open") {
            require(raw["status"]?.stringValue == "active") { "已结束的任务不可编辑" }
            editor = Triple("schedule-editor-${++ordinal}", key, raw)
            editorCompleted = false
            editorError = null
            return changed()
        }
        val requestId = "schedule-write-${++ordinal}"
        val request =
            when (command.op) {
                "schedule-save" -> {
                    val editing = requireNotNull(editor) { "编辑已失效，请重新打开" }
                    require(command.requestKey == editing.first && key == editing.second && raw == editing.third) { "任务已变化，请返回列表后重新编辑" }
                    require(
                        command.title.trim().isNotEmpty() && command.title.length <= 120 && command.text.trim().isNotEmpty() &&
                            command.text.length <= 100_000,
                    ) { "标题或任务内容为空或过长" }
                    GatewayRequests.scheduleUpdate(
                        key.first,
                        key.second,
                        editing.third,
                        command.title.trim(),
                        command.text.trim(),
                        timingChange(command, raw),
                        requestId,
                    )
                }

                "schedule-delete" -> {
                    GatewayRequests.scheduleDelete(key.first, key.second, requestId)
                }

                else -> {
                    error("不支持的计划任务操作")
                }
            }
        mutation = request
        mutationTask = key
        editorError = null
        notice = null
        return changed(listOf(request))
    }

    fun timedOut(key: String): ScheduleChange {
        if (key == catalogKey) {
            catalogKey = null
            catalogQueued = false
            uncertain = true
            fresh = false
            error = "计划任务读取超时，请重新连接后重试"
        }
        if (key == mutation?.correlationId?.let { "schedules:write:$it" }) {
            editorError = "操作结果尚未确认，请刷新列表核对"
            notice = editorError
            mutation = null
            mutationTask = null
            return refresh(true)
        }
        return changed()
    }

    fun accept(
        frame: GatewayFrame,
        connected: Boolean,
    ): ScheduleChange? {
        when {
            frame.kind == "schedule-changed" && visited && connected -> {
                return refresh(true)
            }

            frame.kind == "schedule-catalog" && catalogKey != null -> {
                catalogKey = null
                if (catalogQueued) {
                    catalogQueued = false
                    return refresh()
                }
                records.clear()
                var invalid = false
                frame.items.orEmpty().forEach { raw ->
                    val parsed = parse(raw)
                    if (parsed !=
                        null
                    ) {
                        records[parsed.sessionId to parsed.id] = raw
                    } else {
                        invalid = true
                    }
                }
                loaded = true
                fresh = !invalid
                error = if (invalid) "部分任务格式不受支持，请升级主机后刷新" else null
            }

            frame.kind == "error" && frame.requestType == "schedule-catalog" && catalogKey != null -> {
                catalogKey = null
                catalogQueued =
                    false
                fresh = false
                error = frame.message?.take(240) ?: "计划任务读取失败"
            }

            frame.kind in setOf("schedule-update", "schedule-delete") && frame.kind == mutation?.requestType && frame.requestId != null &&
                frame.requestId == mutation?.correlationId -> {
                if (frame.sessionId != null && frame.sessionId != mutationTask?.first) return null
                val success = if (frame.kind == "schedule-update") frame.updated == true else frame.deleted == true
                if (success) {
                    if (frame.kind == "schedule-delete") mutationTask?.let(records::remove)
                    editorCompleted = frame.kind == "schedule-update"
                    editorError = null
                    notice = if (frame.kind == "schedule-update") "已保存，正在刷新列表" else "已删除，正在刷新列表"
                } else {
                    editorError = frame.message?.take(240) ?: "任务已变化或操作未生效，请刷新后核对"
                    notice = editorError
                }
                mutation = null
                mutationTask = null
                return if (connected) refresh(true) else changed()
            }

            frame.kind == "error" && frame.requestType == mutation?.requestType && frame.requestId != null &&
                frame.requestId == mutation?.correlationId -> {
                editorError = frame.message?.take(240) ?: "计划任务操作失败"
                notice = editorError
                mutation = null
                mutationTask = null
                return if (connected) refresh(true) else changed()
            }

            else -> {
                return null
            }
        }
        return changed()
    }

    private fun refresh(afterCurrent: Boolean = false): ScheduleChange {
        if (uncertain) {
            error = "上次目录请求尚未确认，请重新连接"
            return changed()
        }
        if (catalogKey != null) {
            catalogQueued = catalogQueued || afterCurrent
            return changed()
        }
        catalogKey = "schedules:catalog:${++ordinal}"
        error = null
        return changed(listOf(GatewayRequests.scheduleCatalog()))
    }

    private fun parse(raw: JsonValue): HarmonyScheduledTask? {
        val id = raw["id"]?.stringValue ?: return null
        val session = raw["sessionId"]?.stringValue ?: return null
        return HarmonyScheduledTask(
            id,
            session,
            raw["title"]?.stringValue ?: return null,
            raw["prompt"]?.stringValue ?: return null,
            raw["kind"]?.stringValue ?: return null,
            raw["status"]?.stringValue ?: return null,
            raw["scheduledAt"]?.stringValue ?: return null,
            raw["time"]?.stringValue,
            raw["timeZone"]?.stringValue,
            raw["expression"]?.stringValue,
            raw["weekdays"]?.arrayValue.orEmpty().mapNotNull {
                it.doubleValue?.toInt()
            },
            raw["lastDelivery"]?.get("deliveredAt")?.stringValue,
            raw["everySeconds"]?.doubleValue?.toLong(),
        )
    }

    private fun timingChange(
        command: HarmonyCommand,
        raw: JsonValue,
    ): JsonValue? =
        when (command.target) {
            "", "keep" -> {
                null
            }

            "at" -> {
                val at = requireNotNull(command.path)
                require(Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{3})?Z$").matches(at)) { "指定时间无效" }
                if (raw["kind"]?.stringValue in setOf("at", "after") && at.take(16) == raw["scheduledAt"]?.stringValue?.take(16)) {
                    null
                } else {
                    JsonValue.fromJsonElement(
                        buildJsonObject {
                            put("kind", "at")
                            put("at", at)
                        },
                    )
                }
            }

            "clock" -> {
                val kind = raw["kind"]?.stringValue
                require(kind in setOf("daily", "weekly")) { "此规则只支持保留原规则或改为单次执行" }
                val time = command.query
                require(Regex("^([01]\\d|2[0-3]):[0-5]\\d$").matches(time)) { "时间格式应为 HH:mm" }
                val zone = raw["timeZone"]?.stringValue ?: command.timeZone
                require(zone.isNotBlank() && zone.length <= 128) { "时区无效" }
                if (kind == "weekly") require(raw["weekdays"]?.arrayValue?.isNotEmpty() == true) { "星期信息不完整，请改用指定日期" }
                if (time == raw["time"]?.stringValue?.take(5)) {
                    null
                } else {
                    JsonValue.fromJsonElement(
                        buildJsonObject {
                            put("kind", kind)
                            put(
                                requireNotNull(kind),
                                buildJsonObject {
                                    put("time", "$time:00")
                                    put("time_zone", zone)
                                    if (kind == "weekly") put("weekdays", requireNotNull(raw["weekdays"]).toJsonElement())
                                },
                            )
                        },
                    )
                }
            }

            else -> {
                error("计划时间选项无效")
            }
        }

    private fun changed(requests: List<GatewayRequest> = emptyList()): ScheduleChange {
        val editing =
            editor?.let { value ->
                parse(value.third)?.let { task ->
                    HarmonyScheduleEditor(
                        value.first,
                        task,
                        fresh && catalogKey == null && value.third == records[value.second],
                        mutationTask == value.second,
                        editorCompleted,
                        editorError,
                    )
                }
            }
        val state =
            HarmonySchedules(
                loaded,
                catalogKey != null,
                fresh,
                uncertain,
                records.values.mapNotNull(::parse).sortedWith(
                    compareBy<HarmonyScheduledTask> {
                        it.status !=
                            "active"
                    }.thenBy { it.scheduledAt },
                ),
                mutationTask?.second,
                mutationTask?.first,
                editing,
                error,
                notice,
            )
        val different = state != published
        published = state
        return ScheduleChange(state.takeIf { different }, requests)
    }
}
