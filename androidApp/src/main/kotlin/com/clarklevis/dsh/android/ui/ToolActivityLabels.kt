package com.clarklevis.dsh.android.ui

import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.shared.projection.ToolActivityKind
import com.clarklevis.dsh.shared.projection.ToolActivitySummary

/** 平台侧工具摘要文案；共享层只提供 kind 与计数，两端都不再解析中文标签。 */
internal data class ToolActivityDisplay(val label: String, val detail: String, val annotation: String)

internal fun localizedToolActivitySummary(summary: ToolActivitySummary): ToolActivityDisplay {
    val label = when (summary.kind) {
        ToolActivityKind.WRITE -> appText("写入")
        ToolActivityKind.EDIT -> appText("修改")
        ToolActivityKind.READ -> appText("读取")
        ToolActivityKind.RUN -> appText("运行")
        ToolActivityKind.SEARCH -> appText("搜索")
        ToolActivityKind.LIST -> appText("列出")
        ToolActivityKind.FETCH -> appText("获取")
        else -> summary.toolName.ifBlank { appText("未命名工具") }
    }
    val annotation = when {
        summary.kind == ToolActivityKind.WRITE -> lineCountText(summary.lineCount)
        summary.kind == ToolActivityKind.READ -> lineCountText(summary.requestedLineCount)
        summary.kind == ToolActivityKind.EDIT && summary.replacedLineCount > 0 && summary.replacementLineCount > 0 ->
            appText("替换 %1\$s → %2\$s 行", summary.replacedLineCount, summary.replacementLineCount)
        else -> ""
    }
    return ToolActivityDisplay(label, summary.detail, annotation)
}

private fun lineCountText(count: Int): String = if (count > 0) appText("%1\$s 行", count) else ""
