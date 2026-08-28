package com.clarklevis.dsh.android.ui

import com.clarklevis.dsh.shared.projection.ConversationItem
import com.clarklevis.dsh.shared.projection.ConversationItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationDisplayGroupsTest {
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
    fun conversationalAndStatusRowsSplitProcessGroups() {
        val entries = makeConversationDisplayEntries(
            listOf(
                item("context-a", ConversationItemKind.CONTEXT),
                item("status", ConversationItemKind.STATUS),
                item("reason-b", ConversationItemKind.REASONING),
                item("system", ConversationItemKind.SYSTEM)
            )
        )

        assertEquals(
            listOf("process-context-a", "status", "process-reason-b", "system"),
            entries.map(ConversationDisplayEntry::id)
        )
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
