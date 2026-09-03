package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.facade.WorkspaceCodePreviewSupport
import com.clarklevis.dsh.shared.facade.WorkspaceCodeTokenKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class WorkspaceCodePreviewSupportTest {
    @Test
    fun detectsCodeFilesByExtensionAndMediaType() {
        assertEquals("python", WorkspaceCodePreviewSupport.languageFor("generate_pdf.py", null))
        assertEquals("html", WorkspaceCodePreviewSupport.languageFor("snake.html", "application/octet-stream"))
        assertTrue(WorkspaceCodePreviewSupport.isSupported("payload", "application/json; charset=utf-8"))
        assertFalse(WorkspaceCodePreviewSupport.isSupported("archive.zip", "application/zip"))
    }

    @Test
    fun normalizesLineEndingsAndExpandsTabsToFourColumnStops() {
        val source = "def main():\r\n\tvalue = 1\r\n\t\treturn value"
        assertEquals(
            "def main():\n    value = 1\n        return value",
            WorkspaceCodePreviewSupport.normalizeIndentation(source)
        )
    }

    @Test
    fun highlightsPythonWithoutColoringKeywordInsideString() {
        val document = WorkspaceCodePreviewSupport.prepare(
            "def greet():\n    value = \"return\"  # comment\n    return 42",
            "sample.py",
            "text/x-python"
        )
        assertEquals("Python", document.languageDisplayName)
        assertTrue(document.tokens.any { it.kind == WorkspaceCodeTokenKind.KEYWORD && document.text.substring(it.start, it.endExclusive) == "def" })
        assertTrue(document.tokens.any { it.kind == WorkspaceCodeTokenKind.STRING && document.text.substring(it.start, it.endExclusive) == "\"return\"" })
        assertTrue(document.tokens.any { it.kind == WorkspaceCodeTokenKind.COMMENT })
        assertTrue(document.tokens.any { it.kind == WorkspaceCodeTokenKind.NUMBER })
        assertEquals(
            1,
            document.tokens.count { it.kind == WorkspaceCodeTokenKind.KEYWORD && document.text.substring(it.start, it.endExclusive) == "return" }
        )
    }

    @Test
    fun highlightsHtmlTagsAttributesAndStrings() {
        val document = WorkspaceCodePreviewSupport.prepare(
            "<canvas id=\"game\"></canvas>",
            "snake.html",
            "text/html"
        )
        assertTrue(document.tokens.any { it.kind == WorkspaceCodeTokenKind.TAG && document.text.substring(it.start, it.endExclusive) == "canvas" })
        assertTrue(document.tokens.any { it.kind == WorkspaceCodeTokenKind.ATTRIBUTE && document.text.substring(it.start, it.endExclusive) == "id" })
        assertTrue(document.tokens.any { it.kind == WorkspaceCodeTokenKind.STRING && document.text.substring(it.start, it.endExclusive) == "\"game\"" })
    }

    @Test
    fun preparesReportSizedHtmlWithoutPathologicalSlowdown() {
        val source = buildString {
            appendLine("<!DOCTYPE html>")
            appendLine("<html lang=\"zh-CN\"><head><style>")
            repeat(590) { index ->
                appendLine(
                    ".report-$index { color: #8a8f99; margin: ${index % 24}px; " +
                        "content: \"DeepSeek 深度调研报告 $index\"; }"
                )
            }
            appendLine("</style></head><body><main class=\"report\">报告</main></body></html>")
        }

        lateinit var document: com.clarklevis.dsh.shared.facade.WorkspaceCodeDocument
        val elapsed = measureTime {
            document = WorkspaceCodePreviewSupport.prepare(source, "deepseek_report.html", "text/html")
        }

        assertEquals(594, document.lineCount)
        assertTrue(document.tokens.isNotEmpty())
        assertTrue(elapsed < 10.seconds, "34 KB 级 HTML 解析耗时异常：$elapsed")
    }
}
