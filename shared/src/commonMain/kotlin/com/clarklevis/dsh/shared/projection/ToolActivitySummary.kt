package com.clarklevis.dsh.shared.projection

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 工具摘要的稳定分类；平台据此选择本地化标签，禁止解析中文文案。 */
object ToolActivityKind {
    const val WRITE = "write"
    const val EDIT = "edit"
    const val READ = "read"
    const val RUN = "run"
    const val SEARCH = "search"
    const val LIST = "list"
    const val FETCH = "fetch"
    const val TOOL = "tool"
}

/**
 * 两端共用工具摘要；不把写入内容行数误报为真实 Git diff。
 *
 * 共享层不再生成用户文案：`kind` 与各项计数交给平台本地化，
 * 未知工具保留原始名称（`toolName`），避免两端各自解析中文标签。
 */
data class ToolActivitySummary(
    val kind: String,
    val toolName: String,
    val detail: String,
    val lineCount: Int,
    val replacedLineCount: Int,
    val replacementLineCount: Int,
    val requestedLineCount: Int,
)

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
        val kind = when (normalized) {
            "write", "write_file", "writefile" -> ToolActivityKind.WRITE
            "edit", "edit_file", "editfile", "apply_patch" -> ToolActivityKind.EDIT
            "read", "read_file", "readfile" -> ToolActivityKind.READ
            "bash", "shell", "exec", "exec_command", "run_code", "terminal" -> ToolActivityKind.RUN
            "grep", "search", "ripgrep", "glob", "find" -> ToolActivityKind.SEARCH
            "list", "ls", "list_directory" -> ToolActivityKind.LIST
            "webfetch", "web_fetch", "fetch" -> ToolActivityKind.FETCH
            else -> ToolActivityKind.TOOL
        }
        val detail = (value("description") ?: when (kind) {
            ToolActivityKind.RUN -> command
            ToolActivityKind.SEARCH -> listOfNotNull(query, path).joinToString(" · ")
            ToolActivityKind.FETCH -> value("url")
            else -> path ?: command ?: query ?: value("url")
        }).orEmpty().replace(Regex("\\s+"), " ").trim().take(240)
        return ToolActivitySummary(
            kind = kind,
            toolName = if (kind == ToolActivityKind.TOOL) name.trim() else "",
            detail = detail,
            lineCount = lineCountWhen(kind == ToolActivityKind.WRITE, content),
            replacedLineCount = lineCountWhen(kind == ToolActivityKind.EDIT, old),
            replacementLineCount = lineCountWhen(kind == ToolActivityKind.EDIT, replacement),
            requestedLineCount = if (kind == ToolActivityKind.READ) {
                value("limit", "num_lines")?.toIntOrNull() ?: 0
            } else {
                0
            },
        )
    }

    private fun lineCountWhen(enabled: Boolean, text: String?): Int =
        if (enabled && text != null) text.trimEnd('\n', '\r').count { it == '\n' } + 1 else 0
}
