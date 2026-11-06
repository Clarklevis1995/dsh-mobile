package com.clarklevis.dsh.android.ui

import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.shared.projection.ToolActivitySummary

/** 工具行标题：类别标签与行数注释按当前语言解析；路径、命令和工具描述保持原文。 */
internal fun toolActivityTitle(summary: ToolActivitySummary): String =
    listOfNotNull(appText(summary.labelMessage), summary.annotationMessage?.let { appText(it) })
        .joinToString(" · ")
