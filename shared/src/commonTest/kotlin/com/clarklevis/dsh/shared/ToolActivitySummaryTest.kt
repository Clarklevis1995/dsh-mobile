package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.projection.ToolActivityKind
import com.clarklevis.dsh.shared.projection.ToolActivitySummaryFormatter
import kotlin.test.Test
import kotlin.test.assertEquals

class ToolActivitySummaryTest {
    @Test
    fun fileWriteShowsPathAndContentLineCountWithoutInventingDiff() {
        val summary = ToolActivitySummaryFormatter.summarize("write", """{"file_path":"app/src/Main.kt","content":"one\ntwo\n"}""")
        assertEquals(ToolActivityKind.WRITE, summary.kind)
        assertEquals("", summary.toolName)
        assertEquals("app/src/Main.kt", summary.detail)
        assertEquals(2, summary.lineCount)
        assertEquals(0, summary.replacedLineCount)
        assertEquals(0, summary.requestedLineCount)
    }

    @Test
    fun editAndReadExposeStructuredCountsInsteadOfLocalizedText() {
        val edit = ToolActivitySummaryFormatter.summarize(
            "edit", """{"path":"Main.kt","old_string":"a","new_string":"a\nb\nc"}"""
        )
        assertEquals(ToolActivityKind.EDIT, edit.kind)
        assertEquals("Main.kt", edit.detail)
        assertEquals(1, edit.replacedLineCount)
        assertEquals(3, edit.replacementLineCount)
        assertEquals(0, edit.lineCount)

        val read = ToolActivitySummaryFormatter.summarize(
            "read_file", """{"path":"Main.kt","limit":50}"""
        )
        assertEquals(ToolActivityKind.READ, read.kind)
        assertEquals(50, read.requestedLineCount)
        assertEquals(0, read.lineCount)
    }

    @Test
    fun commandAndSearchSummariesExposeUsefulArguments() {
        val run = ToolActivitySummaryFormatter.summarize("bash", """{"command":"./gradlew\nassembleDebug"}""")
        assertEquals(ToolActivityKind.RUN, run.kind)
        assertEquals("./gradlew assembleDebug", run.detail)
        val search = ToolActivitySummaryFormatter.summarize("grep", """{"pattern":"class Main","path":"src"}""")
        assertEquals(ToolActivityKind.SEARCH, search.kind)
        assertEquals("class Main · src", search.detail)
    }

    @Test
    fun descriptionTakesPriorityOverCommandAndFilePath() {
        val description = "Copy APK to workspace root and verify it"
        val arguments = """{"command":"cp app.apk output.apk","description":"$description"}"""
        assertEquals(description, ToolActivitySummaryFormatter.summarize("bash", arguments).detail)
        assertEquals("创建入口文件", ToolActivitySummaryFormatter.summarize(
            "write", """{"path":"Main.kt","description":"创建入口文件","content":"main()"}"""
        ).detail)
    }

    @Test
    fun blankDescriptionFallsBackAndEncodedArgumentsPreserveDescription() {
        assertEquals("pwd", ToolActivitySummaryFormatter.summarize(
            "bash", """{"command":"pwd","description":"  "}"""
        ).detail)
        val encoded = kotlinx.serialization.json.JsonPrimitive(
            """{"command":"pwd","description":"Check workspace"}"""
        ).toString()
        assertEquals("Check workspace", ToolActivitySummaryFormatter.summarize("bash", encoded).detail)
    }

    @Test
    fun invalidOrUnknownToolArgumentsRemainSafe() {
        val summary = ToolActivitySummaryFormatter.summarize("custom_tool", "partial {")
        assertEquals(ToolActivityKind.TOOL, summary.kind)
        assertEquals("custom_tool", summary.toolName)
        assertEquals("", summary.detail)
        assertEquals(0, summary.lineCount)
        assertEquals(0, summary.replacedLineCount)
        assertEquals("", ToolActivitySummaryFormatter.summarize("", "{}").toolName)
    }
}
