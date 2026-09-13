package com.clarklevis.dsh.android.ui

import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DshTableBlockParserFactoryTest {
    private val extensions = listOf(TablesExtension.create())
    private val parser = Parser.builder()
        .customBlockParserFactory(DshTableBlockParserFactory())
        .extensions(extensions)
        .build()
    private val standardParser = Parser.builder().extensions(extensions).build()
    private val renderer = HtmlRenderer.builder().extensions(extensions).build()

    @Test
    fun preservesPrecedingParagraphAndConsecutiveTables() {
        val first = "| 用途 | 色值 |\n|---|---|\n| 页面背景 | `#151517` |"
        val second = "用途 | 色值\n--- | ---\n文字 | **纯白**"
        val source = "前文\n**背景与容器**\n$first\n**文字与图标**\n$second"
        val separated = "前文\n**背景与容器**\n\n$first\n\n**文字与图标**\n\n$second"

        assertEquals(renderStandard(separated), render(source))
    }

    @Test
    fun preservesAlignmentEscapedPipesAndReferenceLinks() {
        val table = "| 左 | 中 | 右 |\n|:--|:-:|--:|\n| a\\|b | `code` | [链接][ref] |"
        val definitions = "\n\n[ref]: https://example.com"
        assertEquals(
            renderStandard("**标题**\n\n$table$definitions"),
            render("**标题**\n$table$definitions")
        )
    }

    @Test
    fun handlesTablesInsideQuotesAndListItems() {
        val lines = listOf("**标题**", "| A | B |", "|---|---|", "| 1 | 2 |")
        val quote = lines.joinToString("\n") { "> $it" }
        val list = "- " + lines.first() + "\n" + lines.drop(1).joinToString("\n") { "  $it" }
        assertTrue(render(quote).contains("<blockquote>\n<p><strong>标题</strong></p>\n<table>"))
        assertTrue(render(list).contains("<table>"))
        assertTrue(render(list).contains("<strong>标题</strong>"))
    }

    @Test
    fun leavesCodeHtmlAndNonTablesUnchanged() {
        val text = "**示例**\n| A | B |\n|---|---|\n| 1 | 2 |"
        val cases = listOf(
            "```markdown\n$text\n```",
            "~~~~\n$text\n~~~~",
            text.lines().joinToString("\n") { "    $it" },
            "<div>\n$text\n</div>",
            "普通段落\nA | B\n不是分隔行\n正文",
            "普通段落\n| A | B | C |\n|---|---|\n| 1 | 2 |",
            "标题\n---\n正文",
            "| A | B |\n|---|---|\n| 1 | 2 |"
        )
        cases.forEach { assertEquals(it, renderStandard(it), render(it)) }
    }

    private fun render(source: String): String = renderer.render(parser.parse(source))

    private fun renderStandard(source: String): String = renderer.render(standardParser.parse(source))
}
