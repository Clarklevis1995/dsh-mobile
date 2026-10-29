package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.facade.WorkspaceCodePreviewSupport
import com.clarklevis.dsh.shared.facade.WorkspaceCodeTokenKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import kotlin.time.measureTimedValue

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
    fun preparesLongJsonStringsWithoutRecursiveRegexMatching() {
        val key = "k".repeat(32_768)
        val value = "内容😀\\\"\\\\".repeat(16_384)
        val source = "{\"$key\" : \"$value\", \"count\": 42}"
        val (document, elapsed) = measureTimedValue {
            WorkspaceCodePreviewSupport.prepare(source, "large.json", "application/json")
        }

        assertTrue(elapsed < 10.seconds, "长 JSON 字符串解析耗时异常：$elapsed")
        assertEquals(source, document.text)
        assertEquals(
            listOf(
                WorkspaceCodeTokenKind.ATTRIBUTE,
                WorkspaceCodeTokenKind.STRING,
                WorkspaceCodeTokenKind.ATTRIBUTE,
                WorkspaceCodeTokenKind.NUMBER
            ),
            document.tokens.map { it.kind }
        )
        assertEquals(
            "\"$key\"",
            document.text.substring(document.tokens[0].start, document.tokens[0].endExclusive)
        )
        assertEquals(
            "\"$value\"",
            document.text.substring(document.tokens[1].start, document.tokens[1].endExclusive)
        )
    }

    @Test
    fun scansLongStringsForEverySupportedQuoteDelimiter() {
        val body = "return 42 // # -- /* 内容😀 ".repeat(4_096)
        listOf("\"", "'", "`", "\"\"\"", "'''").forEach { delimiter ->
            val literal = "$delimiter$body$delimiter"
            val document = WorkspaceCodePreviewSupport.prepare("$literal\nreturn 7", "sample.py", null)

            assertEquals(3, document.tokens.size, "字符串分隔符：$delimiter")
            val string = document.tokens.first()
            assertEquals(WorkspaceCodeTokenKind.STRING, string.kind)
            assertEquals(literal, document.text.substring(string.start, string.endExclusive))
            assertEquals(WorkspaceCodeTokenKind.KEYWORD, document.tokens[1].kind)
            assertEquals(WorkspaceCodeTokenKind.NUMBER, document.tokens[2].kind)
        }
    }

    @Test
    fun keepsEscapedQuotesAndCommentMarkersInsideStrings() {
        val literal = "\"escaped \\\" return // # -- /* \\\\\""
        val document = WorkspaceCodePreviewSupport.prepare("$literal\nreturn 42", "sample.py", null)

        assertEquals(3, document.tokens.size)
        assertEquals(WorkspaceCodeTokenKind.STRING, document.tokens.first().kind)
        assertEquals(literal, document.text.substring(0, document.tokens.first().endExclusive))
        assertEquals(WorkspaceCodeTokenKind.KEYWORD, document.tokens[1].kind)
    }

    @Test
    fun recognizesCommentsOnlyOutsideStringsAndForTheirLanguages() {
        listOf(
            Triple("sample.py", "# return \"ignored\"", "return 42"),
            Triple("sample.sql", "-- SELECT 'ignored'", "SELECT 42"),
            Triple("sample.kt", "// return \"ignored\"", "return 42"),
            Triple("sample.kt", "/* return \"ignored\" */", "return 42"),
            Triple("sample.js", "<!-- return \"ignored\" -->", "return 42")
        ).forEach { (name, comment, code) ->
            val document = WorkspaceCodePreviewSupport.prepare("$comment\n$code", name, null)
            assertEquals(3, document.tokens.size, name)
            assertEquals(WorkspaceCodeTokenKind.COMMENT, document.tokens[0].kind)
            assertEquals(comment, document.text.substring(0, document.tokens[0].endExclusive))
            assertEquals(WorkspaceCodeTokenKind.KEYWORD, document.tokens[1].kind)
        }

        val document = WorkspaceCodePreviewSupport.prepare("# \"value\"\n-- \"value\"", "sample.js", null)
        assertEquals(
            listOf(WorkspaceCodeTokenKind.STRING, WorkspaceCodeTokenKind.STRING),
            document.tokens.map { it.kind }
        )
    }

    @Test
    fun handlesLongUnterminatedStringsAndCommentsWithoutBacktracking() {
        val body = "return 42 内容😀 ".repeat(8_192)
        listOf("\"", "'", "`", "\"\"\"", "'''").forEach { delimiter ->
            val source = "$delimiter$body\\"
            val document = WorkspaceCodePreviewSupport.prepare(source, "sample.py", null)
            assertEquals(1, document.tokens.size)
            assertEquals(WorkspaceCodeTokenKind.STRING, document.tokens.single().kind)
            assertEquals(source.length, document.tokens.single().endExclusive)
        }
        listOf("/*" to "sample.kt", "<!--" to "sample.js", "//" to "sample.kt", "#" to "sample.py").forEach { (delimiter, name) ->
            val source = "$delimiter$body"
            val document = WorkspaceCodePreviewSupport.prepare(source, name, null)
            assertEquals(1, document.tokens.size)
            assertEquals(WorkspaceCodeTokenKind.COMMENT, document.tokens.single().kind)
            assertEquals(source.length, document.tokens.single().endExclusive)
        }
    }

    @Test
    fun highlightsNumbersWithoutColoringIdentifiersStringsOrComments() {
        val source = "val item42 = 42; val Item2 = 0xFF; val decimal = -12.5; val _9 = 0\n" +
            "\"123\" // 456\nitem123 123item 0xMissing"
        val document = WorkspaceCodePreviewSupport.prepare(source, "sample.kt", null)

        assertEquals(
            listOf("42", "0xFF", "12.5", "0"),
            document.tokens.filter { it.kind == WorkspaceCodeTokenKind.NUMBER }
                .map { document.text.substring(it.start, it.endExclusive) }
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
