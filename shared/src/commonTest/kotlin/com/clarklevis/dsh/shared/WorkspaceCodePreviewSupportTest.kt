package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.facade.WorkspaceCodePreviewSupport
import com.clarklevis.dsh.shared.facade.WorkspaceCodeTokenKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
}
