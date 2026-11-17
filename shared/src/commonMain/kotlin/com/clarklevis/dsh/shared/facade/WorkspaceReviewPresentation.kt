package com.clarklevis.dsh.shared.facade

import com.clarklevis.dsh.shared.protocol.WorkspaceChangesDiff
import com.clarklevis.dsh.shared.i18n.UiMessage

/** 单一逻辑行：续行不重复行号，协议前缀与正文分离。 */
data class WorkspaceReviewRow(val kind: String, val lineNumber: Int?, val text: String) {
    val uiMessage: UiMessage? get() = when (kind) {
        "omitted" -> UiMessage("workspace.review.omitted", listOf(text))
        "hunk" -> if ('–' in text) UiMessage("workspace.review.range", text.split('–'))
                  else UiMessage("workspace.review.line", listOf(text))
        else -> null
    }
}

object WorkspaceReviewPresentation {
    /** 仅用于展示 Host 路径；读取文件仍使用协议中的原始 path，由网关检查权限。 */
    fun absolutePath(path: String, workspaceRoot: String?): String? {
        fun isAbsolute(value: String) = value.startsWith('/') ||
            (value.length >= 3 && value[0].isLetter() && value[1] == ':' && value[2] == '/')
        if (path.isEmpty() || '\u0000' in path) return null
        val normalized = path.replace('\\', '/')
        if (isAbsolute(normalized)) return normalized
        val root = workspaceRoot?.replace('\\', '/') ?: return null
        if (!isAbsolute(root) || '\u0000' in root) return null
        return root.trimEnd('/') + "/" + normalized.removePrefix("./")
    }

    fun rows(diff: WorkspaceChangesDiff): List<WorkspaceReviewRow> = buildList {
        var previousNewEnd: Int? = null
        diff.hunks.forEach { hunk ->
            previousNewEnd?.let { end ->
                val gap = hunk.newStart - end
                if (gap > 0) add(WorkspaceReviewRow("omitted", null, gap.toString()))
            }
            val start = if (hunk.newLines > 0) hunk.newStart else hunk.oldStart
            val length = if (hunk.newLines > 0) hunk.newLines else hunk.oldLines
            val end = start + (length - 1).coerceAtLeast(0)
            add(WorkspaceReviewRow("hunk", null, if (start == end) start.toString() else "$start–$end"))
            var old = hunk.oldStart
            var new = hunk.newStart
            hunk.lines.forEach { line ->
                add(when (line.firstOrNull()) {
                    '+' -> WorkspaceReviewRow("added", new++, line.drop(1))
                    '-' -> WorkspaceReviewRow("deleted", old++, line.drop(1))
                    ' ' -> WorkspaceReviewRow("context", new++, line.drop(1)).also { old++ }
                    else -> WorkspaceReviewRow("annotation", null, line)
                })
            }
            previousNewEnd = hunk.newStart + hunk.newLines
        }
    }
}
