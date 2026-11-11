package com.clarklevis.dsh.android.ui

import android.text.Spanned
import android.text.SpannedString
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonVisitor
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.node.Node

internal data class DshTableCell(val text: Spanned, val plainText: String, val alignment: TableCell.Alignment?) {
    val hasImages: Boolean get() = text.getSpans(0, text.length, DshMarkdownImageMarker::class.java).isNotEmpty()
}
internal data class DshTableData(val rows: List<List<DshTableCell>>) {
    val columnCount: Int get() = rows.maxOfOrNull { it.size } ?: 0
    val plainRows: List<List<String>>
        get() {
            val columns = columnCount
            return rows.map { row -> List(columns) { row.getOrNull(it)?.plainText.orEmpty() } }
        }
}

/** 不参与 TextView 排版，阅读层将此标记拆成独立的横向表格视图。 */
internal class DshTableMarker(val table: DshTableData)

/** 复用当前解析器与行内样式，表格不再被 TableRowSpan 强制均分屏幕宽度。 */
internal class DshMarkdownTablePlugin : AbstractMarkwonPlugin() {
    override fun configureVisitor(builder: MarkwonVisitor.Builder) {
        builder.on(TableBlock::class.java) { visitor, table ->
            visitor.blockStart(table)
            val rows = mutableListOf<List<DshTableCell>>()
            fun collect(node: Node) {
                if (node.firstChild is TableCell) {
                    val cells = mutableListOf<DshTableCell>()
                    var child = node.firstChild
                    while (child is TableCell) {
                        val start = visitor.length()
                        visitor.visitChildren(child)
                        val text = SpannedString(visitor.builder().removeFromEnd(start))
                        cells += DshTableCell(text, tableCellPlainText(child), child.alignment)
                        child = child.next
                    }
                    rows += cells
                } else {
                    var child = node.firstChild
                    while (child != null) { collect(child); child = child.next }
                }
            }
            collect(table)
            val start = visitor.length()
            visitor.builder().append('\uFFFC')
            visitor.setSpans(start, DshTableMarker(DshTableData(rows)))
            visitor.blockEnd(table)
        }
    }
}


private fun tableCellPlainText(node: Node): String = when (node) {
    is org.commonmark.node.Text -> node.literal
    is org.commonmark.node.Code -> node.literal
    is org.commonmark.node.SoftLineBreak, is org.commonmark.node.HardLineBreak -> "\n"
    is org.commonmark.node.HtmlInline -> if (node.literal.matches(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE))) "\n" else ""
    else -> buildString {
        var child = node.firstChild
        while (child != null) { append(tableCellPlainText(child)); child = child.next }
    }
}
