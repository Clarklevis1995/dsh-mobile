package com.clarklevis.dsh.shared.protocol

import kotlinx.serialization.json.Json

/** 两端共用的时间规则草稿。日期和时刻是所选 IANA 时区的墙上时间，不先转换为 UTC。 */
data class ScheduleTimingDraft(
    val frequency: String,
    val interval: String,
    val date: String,
    val time: String,
    val timeZone: String,
    val weekdays: String,
    val monthDay: String,
    val advanced: String
)

object ScheduleTimingEditor {
    private val dayNames = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")

    fun fromRecord(recordJson: String, defaultZone: String, localStart: String): ScheduleTimingDraft {
        val raw = JsonValue.fromJsonElement(Json.parseToJsonElement(recordJson))
        val timing = raw["timing"] ?: raw
        val kind = timing["kind"]?.stringValue ?: raw["kind"]?.stringValue.orEmpty()
        val params = timing[kind]
        val zone = params?.get("time_zone")?.stringValue ?: raw["timeZone"]?.stringValue ?: defaultZone
        val time = params?.get("time")?.stringValue ?: raw["time"]?.stringValue ?: localStart.substringAfter('T')
        val days = (params?.get("weekdays") ?: raw["weekdays"])?.arrayValue
            ?.mapNotNull { it.doubleValue?.toInt() }?.sorted()?.joinToString(",") ?: "1"
        val base = ScheduleTimingDraft("advanced", "1", params?.get("date")?.stringValue ?: localStart.take(10), time, zone, days,
            localStart.substring(8, 10).toInt().toString(), "")
        return when (kind) {
            "at", "after" -> base.copy(frequency = "once")
            "daily", "weekly" -> base.copy(frequency = kind)
            "every" -> {
                val seconds = (timing["every_seconds"] ?: raw["everySeconds"])?.doubleValue ?: 0.0
                when {
                    seconds >= 3600 && seconds % 3600 == 0.0 -> base.copy(frequency = "hourly", interval = (seconds / 3600).toLong().toString())
                    seconds >= 60 && seconds % 60 == 0.0 -> base.copy(frequency = "minutely", interval = (seconds / 60).toLong().toString())
                    else -> base // 无法用整分钟表示时保留原记录，不近似改写。
                }
            }
            "cron" -> base.copy(advanced = params?.get("expression")?.stringValue ?: raw["expression"]?.stringValue.orEmpty())
            "rrule" -> parseRule(base, timing["rrule"]?.stringValue ?: raw["rrule"]?.stringValue.orEmpty())
            else -> base
        }
    }

    private fun parseRule(base: ScheduleTimingDraft, rule: String): ScheduleTimingDraft {
        val fallback = base.copy(advanced = rule)
        val lines = rule.replace("\r\n", "\n").trim().lines()
        if (lines.size != 2) return fallback
        val start = Regex("DTSTART(?:;TZID=([^:]+))?:(\\d{4})(\\d{2})(\\d{2})T(\\d{2})(\\d{2})(\\d{2})(Z?)").matchEntire(lines[0]) ?: return fallback
        // 无 TZID 的浮动时间不猜测时区。
        if (start.groupValues[1].isEmpty() && start.groupValues[8] != "Z") return fallback
        val fields = lines[1].removePrefix("RRULE:").split(';').map { it.split('=', limit = 2) }
        if (!lines[1].startsWith("RRULE:") || fields.any { it.size != 2 }) return fallback
        val values = fields.associate { it[0] to it[1] }
        if (values.size != fields.size) return fallback
        val oneShot = values["FREQ"] == "DAILY" && values["COUNT"] == "1" && values.size == 2
        val frequency = if (oneShot) "once" else when (values["FREQ"]) {
            "MINUTELY" -> "minutely"
            "HOURLY" -> "hourly"
            "DAILY" -> "daily"
            "WEEKLY" -> "weekly"
            "MONTHLY" -> "monthly"
            else -> return fallback
        }
        val allowed = setOf("FREQ", "INTERVAL") + when (frequency) {
            "once" -> setOf("COUNT")
            "daily" -> setOf("BYHOUR", "BYMINUTE")
            "weekly" -> setOf("BYDAY")
            "monthly" -> setOf("BYMONTHDAY", "BYSETPOS")
            else -> emptySet()
        }
        if (values.keys.any { it !in allowed }) return fallback
        if (frequency == "monthly" && (values["BYSETPOS"] != "1" ||
                !Regex("(?:[1-9]|[12]\\d|3[01]),-1").matches(values["BYMONTHDAY"].orEmpty()))) return fallback
        val days = values["BYDAY"]?.split(',')
        if (days != null && (days.any { it !in dayNames } || days.distinct().size != days.size)) return fallback
        if (frequency == "weekly" && days == null) return fallback
        val parts = start.groupValues
        val hour = values["BYHOUR"] ?: parts[5]
        val minute = values["BYMINUTE"] ?: parts[6]
        val draft = base.copy(frequency = frequency, interval = values["INTERVAL"] ?: "1",
            date = "${parts[2]}-${parts[3]}-${parts[4]}",
            time = "${hour.padStart(2, '0')}:${minute.padStart(2, '0')}:${parts[7]}",
            timeZone = parts[1].ifEmpty { "UTC" },
            weekdays = days?.map { dayNames.indexOf(it) + 1 }?.sorted()?.joinToString(",") ?: base.weekdays,
            monthDay = values["BYMONTHDAY"]?.substringBefore(',') ?: base.monthDay)
        return if (validationError(draft) == null) draft else fallback
    }

    fun validationError(draft: ScheduleTimingDraft): String? {
        if (draft.frequency == "advanced") {
            if (draft.advanced.isBlank()) return "请输入 Cron 或 DTSTART/RRULE 规则。"
            if (!draft.advanced.trimStart().startsWith("DTSTART") && draft.advanced.trim().split(Regex("\\s+")).size != 5) return "Cron 需要 5 个字段；RRULE 需要 DTSTART 和 RRULE 两行。"
        } else {
            if (draft.frequency !in listOf("once", "minutely", "hourly", "daily", "weekly", "monthly")) return "请选择重复类型。"
            if (draft.frequency != "once" && (draft.interval.toIntOrNull()?.let { it in 1..10000 } != true)) return "重复间隔请输入 1 到 10000 的整数。"
            if (!Regex("\\d{4}-\\d{2}-\\d{2}").matches(draft.date)) return "请选择有效日期。"
            if (!Regex("(?:[01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d(?:\\.\\d{1,3})?").matches(draft.time)) return "时间格式为 HH:mm:ss，可保留最多 3 位毫秒。"
            val parts = draft.date.split('-').map { it.toInt() }
            val leap = parts[0] % 4 == 0 && (parts[0] % 100 != 0 || parts[0] % 400 == 0)
            val monthDays = listOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
            if (parts[0] !in 1..9999 || parts[1] !in 1..12 || parts[2] !in 1..monthDays[parts[1] - 1]) return "请选择有效日期。"
            val usesRRule = draft.frequency in listOf("minutely", "hourly", "monthly") ||
                (draft.frequency in listOf("daily", "weekly") && draft.interval.toInt() != 1)
            if (usesRRule && draft.time.substringAfter('.', "0").any { it != '0' }) return "此重复规则仅支持整秒，请将毫秒设为 000。"
            if (draft.frequency == "weekly" && (draft.weekdays.split(',').any { it.toIntOrNull()?.let { day -> day in 1..7 } != true } || draft.weekdays.split(',').distinct().size != draft.weekdays.split(',').size)) return "每周至少选择一天。"
            if (draft.frequency == "monthly" && draft.monthDay.toIntOrNull()?.let { it in 1..31 } != true) return "每月日期请输入 1 到 31。"
        }
        if (!Regex("[A-Za-z0-9_+./-]+").matches(draft.timeZone) || (draft.timeZone != "UTC" && !draft.timeZone.contains('/'))) return "请选择有效的 IANA 时区。"
        return null
    }

    /** 未修改时返回 null，以便保留毫秒、锚点和未识别的高级条件。 */
    fun changeJson(draft: ScheduleTimingDraft, original: ScheduleTimingDraft): String? {
        if (draft == original || validationError(draft) != null) return null
        return timingJson(draft)
    }

    fun timingJson(draft: ScheduleTimingDraft): String? {
        if (validationError(draft) != null) return null
        fun text(value: String) = JsonValue.StringValue(value)
        fun obj(vararg entries: Pair<String, JsonValue>) = JsonValue.ObjectValue(mapOf(*entries))
        val clock = arrayOf("time" to text(draft.time), "time_zone" to text(draft.timeZone))
        val timing = when {
            draft.frequency == "once" -> obj("kind" to text("at"), "at" to obj("date" to text(draft.date), *clock))
            draft.frequency == "daily" && draft.interval.toInt() == 1 -> obj("kind" to text("daily"), "daily" to obj(*clock))
            draft.frequency == "weekly" && draft.interval.toInt() == 1 -> obj("kind" to text("weekly"), "weekly" to obj(*clock,
                "weekdays" to JsonValue.ArrayValue(draft.weekdays.split(',').map { JsonValue.NumberValue(it.toDouble()) })))
            draft.frequency == "advanced" && !draft.advanced.trimStart().startsWith("DTSTART") -> obj("kind" to text("cron"), "cron" to obj("expression" to text(draft.advanced.trim()), "time_zone" to text(draft.timeZone)))
            else -> {
                val rule = if (draft.frequency == "advanced") draft.advanced else {
                    val extra = when (draft.frequency) {
                        "weekly" -> ";BYDAY=" + draft.weekdays.split(',').joinToString(",") { dayNames[it.toInt() - 1] }
                        "monthly" -> ";BYMONTHDAY=${draft.monthDay},-1;BYSETPOS=1"
                        else -> ""
                    }
                    val frequency = mapOf("minutely" to "MINUTELY", "hourly" to "HOURLY", "daily" to "DAILY", "weekly" to "WEEKLY", "monthly" to "MONTHLY").getValue(draft.frequency)
                    // 从所选日期的墙上时间开始，跨夏令时按日历推进，不换算为固定秒数。
                    "DTSTART;TZID=${draft.timeZone}:${draft.date.replace("-", "")}T${draft.time.replace(":", "").substringBefore('.')}\nRRULE:FREQ=$frequency;INTERVAL=${draft.interval}$extra"
                }
                obj("kind" to text("rrule"), "rrule" to text(rule))
            }
        }
        return timing.displayText()
    }
}
