package com.clarklevis.dsh.shared.protocol

import kotlinx.serialization.Serializable

@Serializable
data class WorkspaceChangesSummary(
    val turn: Int, val total: Int, val added: Int, val deleted: Int,
    val files: List<WorkspaceChangedFile>
)

@Serializable
data class WorkspaceChangedFile(
    val path: String, val display: String, val added: Int, val deleted: Int,
    val binary: Boolean = false, val oversized: Boolean = false
)

@Serializable
data class WorkspaceChangesDiff(
    val kind: String, val path: String, val display: String,
    val before: Boolean? = null, val after: Boolean? = null, val coarse: Boolean = false,
    val hunks: List<WorkspaceDiffHunk> = emptyList()
) {
    fun rows(): List<WorkspaceDiffRow> = buildList {
        hunks.forEach { hunk ->
            add(WorkspaceDiffRow("hunk", null, null,
                "@@ -${hunk.oldStart},${hunk.oldLines} +${hunk.newStart},${hunk.newLines} @@"))
            var old = hunk.oldStart
            var new = hunk.newStart
            hunk.lines.forEach { line ->
                when (line.firstOrNull()) {
                    '+' -> add(WorkspaceDiffRow("added", null, new++, line))
                    '-' -> add(WorkspaceDiffRow("deleted", old++, null, line))
                    ' ' -> add(WorkspaceDiffRow("context", old++, new++, line))
                    else -> add(WorkspaceDiffRow("annotation", null, null, line))
                }
            }
        }
    }
}

@Serializable
data class WorkspaceDiffHunk(
    val oldStart: Int, val oldLines: Int, val newStart: Int, val newLines: Int,
    val lines: List<String>
)

data class WorkspaceDiffRow(val kind: String, val oldLine: Int?, val newLine: Int?, val text: String)
