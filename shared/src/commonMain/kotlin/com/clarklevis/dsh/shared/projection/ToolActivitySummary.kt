package com.clarklevis.dsh.shared.projection

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 两端共用工具摘要；不把写入内容行数误报为真实 Git diff。 */
data class ToolActivitySummary(val label: String, val detail: String, val annotation: String)

object ToolActivitySummaryFormatter {
    fun summarize(name: String, arguments: String): ToolActivitySummary {
        val parsed = runCatching { Json.parseToJsonElement(arguments) }.getOrNull()
        val fields = (if (parsed is JsonPrimitive && parsed.isString) {
            runCatching { Json.parseToJsonElement(parsed.content) }.getOrNull()
        } else parsed) as? JsonObject
        fun value(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
            (fields?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        }
        val normalized = name.lowercase().substringAfterLast('.').substringAfterLast('/')
        val path = value("file_path", "filePath", "path", "filename", "file")
        val command = value("command", "cmd", "script", "code")
        val query = value("pattern", "query", "search", "regex")
        val content = value("content", "text")
        val old = value("old_string", "oldText", "old_text")
        val replacement = value("new_string", "newText", "new_text")
        val label = when (normalized) {
            "write", "write_file", "writefile" -> "写入"
            "edit", "edit_file", "editfile", "apply_patch" -> "修改"
            "read", "read_file", "readfile" -> "读取"
            "bash", "shell", "exec", "exec_command", "run_code", "terminal" -> "执行"
            "grep", "search", "ripgrep", "glob", "find" -> "搜索"
            "list", "ls", "list_directory" -> "列出"
            "webfetch", "web_fetch", "fetch" -> "获取"
            else -> name.ifBlank { "工具" }
        }
        val detail = (value("description") ?: when (label) {
            "执行" -> command
            "搜索" -> listOfNotNull(query, path).joinToString(" · ")
            "获取" -> value("url")
            else -> path ?: command ?: query ?: value("url")
        }).orEmpty().replace(Regex("\\s+"), " ").trim().take(240)
        val annotation = when {
            label == "写入" && content != null -> "${lineCount(content)} 行"
            label == "修改" && old != null && replacement != null ->
                "替换 ${lineCount(old)} → ${lineCount(replacement)} 行"
            label == "读取" -> value("limit", "num_lines")?.let { "$it 行" }.orEmpty()
            else -> ""
        }
        return ToolActivitySummary(label, detail, annotation)
    }

    private fun lineCount(text: String): Int = text.trimEnd('\n', '\r').count { it == '\n' } + 1
}
