package com.clarklevis.dsh.android.ui

import org.commonmark.ext.gfm.tables.internal.TableBlockParser
import org.commonmark.internal.BlockStartImpl
import org.commonmark.node.Paragraph
import org.commonmark.parser.InlineParser
import org.commonmark.parser.block.AbstractBlockParserFactory
import org.commonmark.parser.block.BlockParser
import org.commonmark.parser.block.BlockStart
import org.commonmark.parser.block.MatchedBlockParser
import org.commonmark.parser.block.ParserState

/**
 * Markwon 4.6.2 使用的 commonmark 0.13 只允许单行段落成为表头。这里让段落最后一行
 * 参与原有表格识别，并将前文保留为段落，不要求模型在小标题和表格之间额外生成空行。
 *
 * 复用原解析器处理对齐、转义和单元格；在块解析阶段处理，代码块和 HTML 不受影响。
 * BlockStartImpl 是该版本的内部返回类型；升级 commonmark 时需同时验证此兼容适配。
 */
internal class DshTableBlockParserFactory : AbstractBlockParserFactory() {
    private val tables = TableBlockParser.Factory()

    override fun tryStart(state: ParserState, matchedBlockParser: MatchedBlockParser): BlockStart? {
        val paragraph = matchedBlockParser.paragraphContent?.toString() ?: return BlockStart.none()
        val headerStart = paragraph.lastIndexOf('\n') + 1
        val header = paragraph.substring(headerStart)
        if ('|' !in header) return BlockStart.none()
        val headerMatch = object : MatchedBlockParser by matchedBlockParser {
            override fun getParagraphContent(): CharSequence = header
        }
        val start = tables.tryStart(state, headerMatch) as? BlockStartImpl
            ?: return BlockStart.none()
        val tableParser = start.blockParsers.single()
        val prefix = paragraph.substring(0, (headerStart - 1).coerceAtLeast(0))
        val parser = object : BlockParser by tableParser {
            // 非表格行结束当前表格，避免紧随其后的小标题和第二个表格被吞进表体。
            override fun canHaveLazyContinuationLines(): Boolean = false

            override fun parseInlines(inlineParser: InlineParser) {
                if (prefix.isNotEmpty()) {
                    val precedingParagraph = Paragraph()
                    block.insertBefore(precedingParagraph)
                    inlineParser.parse(prefix, precedingParagraph)
                }
                tableParser.parseInlines(inlineParser)
            }
        }
        return BlockStart.of(parser).atIndex(start.newIndex).replaceActiveBlockParser()
    }
}
