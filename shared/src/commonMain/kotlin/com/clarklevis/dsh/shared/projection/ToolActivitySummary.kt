package com.clarklevis.dsh.shared.projection

import com.clarklevis.dsh.shared.i18n.UiMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

enum class ToolActivityKind { WRITE, EDIT, READ, EXEC, SEARCH, LIST, FETCH, OTHER }

/**
 * 两端共用工具摘要；不把写入内容行数误报为真实 Git diff。
 * 标签与行数注释由平台按 [kind] 和行数字段本地化；[toolName] 仅在 OTHER 时为非空工具名。
 */
data class ToolActivitySummary(
    val kind: ToolActivityKind,
    val toolName: String?,
    val detail: String,
    /** WRITE 的写入行数，或 READ 请求的行数。 */
    val lineCount: Int?,
    /** EDIT 被替换的行数；与 [replacementLines] 同时出现。 */
    val replacedLines: Int?,
    val replacementLines: Int?,
) {
    /** 行标签：固定类别走 `tool.kind.*`；未知工具显示原名。 */
    val labelMessage: UiMessage get() = when (kind) {
        ToolActivityKind.WRITE -> UiMessage("tool.kind.write")
        ToolActivityKind.EDIT -> UiMessage("tool.kind.edit")
        ToolActivityKind.READ -> UiMessage("tool.kind.read")
        ToolActivityKind.EXEC -> UiMessage("tool.kind.exec")
        ToolActivityKind.SEARCH -> UiMessage("tool.kind.search")
        ToolActivityKind.LIST -> UiMessage("tool.kind.list")
        ToolActivityKind.FETCH -> UiMessage("tool.kind.fetch")
        ToolActivityKind.OTHER -> toolName?.let { UiMessage.raw(it) } ?: UiMessage("tool.kind.other")
    }

    /** 行数注释；无行数时为 null。 */
    val annotationMessage: UiMessage? get() {
        val replaced = replacedLines
        val replacement = replacementLines
        return when {
            replaced != null && replacement != null ->
                UiMessage("tool.replacedLines", listOf(replaced.toString(), replacement.toString()))
            lineCount != null -> UiMessage("tool.lines", listOf(lineCount.toString()))
            else -> null
        }
    }
}

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
            "bash", "shell", "exec", "exec_command", "run_code", "terminal" -> ToolActivityKind.EXEC
            "grep", "search", "ripgrep", "glob", "find" -> ToolActivityKind.SEARCH
            "list", "ls", "list_directory" -> ToolActivityKind.LIST
            "webfetch", "web_fetch", "fetch" -> ToolActivityKind.FETCH
            else -> ToolActivityKind.OTHER
        }
        val detail = (value("description") ?: when (kind) {
            ToolActivityKind.EXEC -> command
            ToolActivityKind.SEARCH -> listOfNotNull(query, path).joinToString(" · ")
            ToolActivityKind.FETCH -> value("url")
            else -> path ?: command ?: query ?: value("url")
        }).orEmpty().replace(Regex("\\s+"), " ").trim().take(240)
        val editLines = old != null && replacement != null && kind == ToolActivityKind.EDIT
        return ToolActivitySummary(
            kind = kind,
            toolName = if (kind == ToolActivityKind.OTHER) name.takeIf { it.isNotBlank() } else null,
            detail = detail,
            lineCount = when (kind) {
                ToolActivityKind.WRITE -> content?.let(::lineCount)
                ToolActivityKind.READ -> value("limit", "num_lines")?.toIntOrNull()
                else -> null
            },
            replacedLines = if (editLines) lineCount(requireNotNull(old)) else null,
            replacementLines = if (editLines) lineCount(requireNotNull(replacement)) else null,
        )
    }

    private fun lineCount(text: String): Int = text.trimEnd('\n', '\r').count { it == '\n' } + 1
}
