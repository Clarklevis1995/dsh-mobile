package com.clarklevis.dsh.android.ui

import com.clarklevis.dsh.shared.projection.ConversationItem
import com.clarklevis.dsh.shared.projection.ConversationItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationDisplayGroupsTest {
    @Test
    fun `provisional stream ids are detected independently from rendering`() {
        assertTrue(isStreamingConversationItem(item("stream-text-1-1", ConversationItemKind.ASSISTANT)))
        assertTrue(isStreamingConversationItem(item("stream-reason-1-1", ConversationItemKind.REASONING)))
        assertFalse(isStreamingConversationItem(item("session-a-42", ConversationItemKind.ASSISTANT)))
    }

    @Test
    fun `streaming assistant is markdown virtualized without a final copy footer`() {
        val text = List(5) { index ->
            "第${index + 1}段：${"流式 Markdown".repeat(40)}"
        }.joinToString("\n\n")

        val timeline = makeConversationTimelineEntries(
            makeConversationDisplayEntries(
                listOf(item("stream-text-7-9", ConversationItemKind.ASSISTANT, text = text))
            )
        )

        assertTrue(timeline.first() is ConversationTimelineEntry.AssistantHeader)
        assertTrue(timeline.any { it is ConversationTimelineEntry.AssistantMarkdown })
        assertFalse(timeline.any { it is ConversationTimelineEntry.AssistantFooter })
        assertEquals(
            text,
            timeline.filterIsInstance<ConversationTimelineEntry.AssistantMarkdown>()
                .joinToString("\n\n", transform = { it.markdown })
        )
    }

    @Test
    fun consecutiveProcessEventsBecomeOneNestedGroup() {
        val entries = makeConversationDisplayEntries(
            listOf(
                item("user", ConversationItemKind.USER, seconds = 1.0),
                item("context", ConversationItemKind.CONTEXT, seconds = 2.0),
                item("reason", ConversationItemKind.REASONING, text = "先分析", seconds = 3.0),
                item("call", ConversationItemKind.TOOL, title = "ask_user_question", seconds = 4.0),
                item("result", ConversationItemKind.TOOL_RESULT, seconds = 7.0),
                item("assistant", ConversationItemKind.ASSISTANT, seconds = 8.0)
            )
        )

        assertEquals(3, entries.size)
        assertTrue(entries[0] is ConversationDisplayEntry.Message)
        val process = (entries[1] as ConversationDisplayEntry.Process).group
        assertEquals("耗时 5 秒 · 1 项上下文 · 1 次工具调用", process.title)
        assertEquals("先分析", process.reasoningText)
        assertEquals("call", process.tools.single().call?.id)
        assertEquals("result", process.tools.single().result?.id)
        assertTrue(entries[2] is ConversationDisplayEntry.Message)
    }

    @Test
    fun commandOwnsFollowingContextAndReasoningAsNestedDetails() {
        val entries = makeConversationDisplayEntries(
            listOf(
                item("context-a", ConversationItemKind.CONTEXT),
                item("status", ConversationItemKind.STATUS),
                item("reason-b", ConversationItemKind.REASONING),
                item("system", ConversationItemKind.SYSTEM)
            )
        )

        assertEquals(
            listOf("process-context-a", "status", "system"),
            entries.map(ConversationDisplayEntry::id)
        )
        val command = (entries[1] as ConversationDisplayEntry.Process).group
        assertEquals("status", command.command?.id)
        assertEquals("reason-b", command.items.last().id)
        assertTrue(command.isExpandable)
    }

    @Test
    fun `lazy list content types keep incompatible message layouts separate`() {
        val entries = makeConversationDisplayEntries(
            listOf(
                item("user", ConversationItemKind.USER),
                item("assistant", ConversationItemKind.ASSISTANT),
                item("reason", ConversationItemKind.REASONING),
                item("status", ConversationItemKind.STATUS),
                item("system", ConversationItemKind.SYSTEM)
            )
        )

        assertEquals(
            listOf(
                ConversationDisplayContentType.USER,
                ConversationDisplayContentType.ASSISTANT,
                ConversationDisplayContentType.PROCESS,
                ConversationDisplayContentType.PROCESS,
                ConversationDisplayContentType.SYSTEM
            ),
            entries.map(ConversationDisplayEntry::contentType)
        )
    }

    @Test
    fun `final assistant markdown is virtualized into stable timeline rows`() {
        val text = List(6) { index ->
            "第${index + 1}段：${"长回复内容".repeat(45)}"
        }.joinToString("\n\n")
        val timeline = makeConversationTimelineEntries(
            makeConversationDisplayEntries(
                listOf(item("assistant-42", ConversationItemKind.ASSISTANT, text = text))
            )
        )

        assertTrue(timeline.first() is ConversationTimelineEntry.AssistantHeader)
        assertTrue(timeline.last() is ConversationTimelineEntry.AssistantFooter)
        val markdownRows = timeline.filterIsInstance<ConversationTimelineEntry.AssistantMarkdown>()
        assertTrue(markdownRows.size > 1)
        assertEquals(
            markdownRows.indices.map { "assistant:assistant-42:markdown:$it" },
            markdownRows.map(ConversationTimelineEntry::id)
        )
        assertEquals(text, markdownRows.joinToString("\n\n", transform = { it.markdown }))
    }

    @Test
    fun `markdown virtualization never splits a fenced code block`() {
        val markdown = """
            开始

            ```kotlin
            val first = 1

            val second = 2
            ```

            结束段落
        """.trimIndent()

        val blocks = splitMarkdownForLazyLayout(markdown, targetCharacters = 20)

        assertTrue(blocks.size > 1)
        val codeBlock = blocks.single { it.contains("```kotlin") }
        assertTrue(codeBlock.contains("val first = 1\n\nval second = 2"))
        assertTrue(codeBlock.endsWith("```"))
    }

    @Test
    fun unmatchedToolResultRemainsInspectable() {
        val process = (makeConversationDisplayEntries(
            listOf(item("orphan-result", ConversationItemKind.TOOL_RESULT, seconds = 3.0))
        ).single() as ConversationDisplayEntry.Process).group

        assertEquals(1, process.tools.size)
        assertNull(process.tools.single().call)
        assertEquals("orphan-result", process.tools.single().result?.id)
    }

    private fun item(
        id: String,
        kind: ConversationItemKind,
        title: String = kind.name,
        text: String = id,
        seconds: Double = 0.0
    ) = ConversationItem(
        id = id,
        kind = kind,
        title = title,
        text = text,
        epochSeconds = seconds
    )
}
