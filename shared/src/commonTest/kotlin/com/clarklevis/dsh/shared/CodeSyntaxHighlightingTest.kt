package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.facade.CodeSyntaxHighlighting
import com.clarklevis.dsh.shared.facade.WorkspaceCodeTokenKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodeSyntaxHighlightingTest {
    @Test
    fun normalizesFenceLanguagesAndAliases() {
        mapOf(
            " Kotlin title=sample.kt " to "kotlin",
            "{.python}" to "python",
            "language-js" to "javascript",
            "tsx" to "typescript",
            "c++" to "cpp",
            "c#" to "csharp",
            "bash" to "shell",
            "jsonc" to "json",
            "tex" to "latex",
            "yml" to "yaml",
            "objc" to "objective-c",
            "patch" to "diff",
        ).forEach { (info, expected) -> assertEquals(expected, CodeSyntaxHighlighting.language(info)) }
    }

    @Test
    fun appliesEachLanguagesKeywordRules() {
        mapOf(
            "kotlin" to "fun",
            "swift" to "func",
            "python" to "def",
            "java" to "public",
            "c" to "typedef",
            "cpp" to "namespace",
            "csharp" to "namespace",
            "dart" to "mixin",
            "javascript" to "function",
            "typescript" to "interface",
            "go" to "package",
            "rust" to "impl",
            "ruby" to "unless",
            "php" to "echo",
            "shell" to "export",
            "sql" to "select",
            "groovy" to "def",
            "lua" to "local",
            "objective-c" to "interface",
        ).forEach { (language, keyword) ->
            assertEquals(
                listOf(WorkspaceCodeTokenKind.KEYWORD),
                CodeSyntaxHighlighting.highlight(keyword, language).map { it.kind },
                "$language: $keyword",
            )
        }
        assertTrue(CodeSyntaxHighlighting.highlight("def", "swift").isEmpty())
        assertTrue(CodeSyntaxHighlighting.highlight("func", "python").isEmpty())
    }

    @Test
    fun highlightsMarkupConfigurationAndLatex() {
        val fixtures =
            mapOf(
                "html" to "<div class=\"card\">Hello</div>",
                "xml" to "<item id=\"42\" />",
                "css" to ".card {\n color: red;\n}",
                "yaml" to "enabled: true # comment",
                "toml" to "enabled = true",
                "ini" to "theme = \"dark\"",
                "json" to "{\"name\": \"中文😀\", \"active\": true, \"count\": 1e3}",
                "latex" to "\\frac{1}{2} % comment",
                "diff" to "@@ -1 +1 @@\n-old\n+new",
            )
        fixtures.forEach { (language, source) ->
            val tokens = CodeSyntaxHighlighting.highlight(source, language)
            assertTrue(tokens.isNotEmpty(), language)
            assertTrue(tokens.all { it.start >= 0 && it.endExclusive <= source.length && it.endExclusive > it.start })
            assertTrue(tokens.zipWithNext().all { (first, next) -> first.endExclusive <= next.start })
        }
    }

    @Test
    fun preservesUtf16OffsetsTabsAndLineEndings() {
        val source = "// 中文😀\r\n\tval value = \"你好😀 return\"\r\n\treturn 1.25e-3\r\n"
        val tokens = CodeSyntaxHighlighting.highlight(source, "kt")
        assertEquals(
            listOf("val", "return"),
            tokens
                .filter { it.kind == WorkspaceCodeTokenKind.KEYWORD }
                .map { source.substring(it.start, it.endExclusive) },
        )
        assertEquals(
            listOf("1.25e-3"),
            tokens
                .filter { it.kind == WorkspaceCodeTokenKind.NUMBER }
                .map { source.substring(it.start, it.endExclusive) },
        )
        assertEquals(
            "\"你好😀 return\"",
            tokens
                .first { it.kind == WorkspaceCodeTokenKind.STRING }
                .let { source.substring(it.start, it.endExclusive) },
        )
    }

    @Test
    fun doesNotTreatPythonDivisionOrRustLifetimesAsCommentsOrStrings() {
        assertFalse(
            CodeSyntaxHighlighting
                .highlight("value = 10 // 3\nreturn value", "python")
                .any { it.kind == WorkspaceCodeTokenKind.COMMENT },
        )
        assertFalse(
            CodeSyntaxHighlighting
                .highlight("fn borrow<'a>(x: &'a str) -> &'a str { x }", "rust")
                .any { it.kind == WorkspaceCodeTokenKind.STRING },
        )
        assertTrue(
            CodeSyntaxHighlighting
                .highlight("local x = 1 -- comment", "lua")
                .any { it.kind == WorkspaceCodeTokenKind.COMMENT },
        )
        assertTrue(
            CodeSyntaxHighlighting
                .highlight("echo \\% % comment", "latex")
                .any { it.kind == WorkspaceCodeTokenKind.COMMENT },
        )
    }

    @Test
    fun infersOnlyUnlabelledJsonAndFallsBackForUnknownLanguages() {
        val source = "{\"enabled\": true}"
        assertTrue(CodeSyntaxHighlighting.highlight(source, null).isNotEmpty())
        assertTrue(CodeSyntaxHighlighting.highlight(source, "unknown").isEmpty())
        assertTrue(CodeSyntaxHighlighting.highlight(source, "text").isEmpty())
        assertTrue(CodeSyntaxHighlighting.highlight("just plain text", null).isEmpty())
        assertTrue(CodeSyntaxHighlighting.highlight("\"" + "x".repeat(200_001), "python").isEmpty())
    }

    @Test
    fun palettesHaveDistinctTokenColorsAndAdaptToTheme() {
        val kinds = WorkspaceCodeTokenKind.entries
        assertEquals(kinds.size - 1, kinds.map { CodeSyntaxHighlighting.color(it, false) }.distinct().size)
        kinds.forEach { kind -> assertTrue(CodeSyntaxHighlighting.color(kind, true) != CodeSyntaxHighlighting.color(kind, false)) }
    }
}
