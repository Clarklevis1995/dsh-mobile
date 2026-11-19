package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.facade.BotChat
import com.clarklevis.dsh.shared.facade.BotMentionCandidate
import com.clarklevis.dsh.shared.facade.BotMentionDraft
import com.clarklevis.dsh.shared.facade.BotMentionSpan
import com.clarklevis.dsh.shared.facade.BotMentions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BotMentionDraftTest {
    @Test
    fun messageQuotesPreserveMentionOffsetsAndSurviveDraftRestore() {
        val file = BotMentionCandidate("file", "file", "调研 报告", "@\"调研 报告.md\"")
        val chat = BotMentionCandidate("chat", "session", "群聊", "@[群聊](dsh-session:chat)")
        val start = BotMentionDraft("🙂 @调")
        val first = start.insert(assertNotNull(start.trigger(start.text.length)), file, file.label).draft
        val anchor = first.text.length
        val quoted = first.insertQuote("先看 @原文\n再核对", anchor, anchor).draft
        val editing = quoted.replacingText(quoted.text + "再看 @群")
        val mixed = editing.insert(assertNotNull(editing.trigger(editing.text.length)), chat, chat.label).draft
        val restored = BotMentions.decodeDraft(mixed.encoded())

        assertEquals("🙂 @调研 报告 \n\n> 先看 @原文\n> 再核对\n\n再看 @群聊 ", restored.displayText())
        assertEquals("🙂 @\"调研 报告.md\" \n\n> 先看 @原文\n> 再核对\n\n再看 @[群聊](dsh-session:chat) ", restored.wireText())
        val withoutQuote = restored.removeQuote(1)
        assertEquals(mixed.text, withoutQuote.text)
        assertEquals(mixed.spans, withoutQuote.spans)
        assertEquals(false, withoutQuote.hasQuotes())
    }

    @Test
    fun insertingAQuoteReplacesTheSelectionAndOnlyRemovesOverlappingMentions() {
        val draft = BotMentionDraft("🙂 删除 @任务 保留 @Chief", listOf(
            BotMentionSpan(6, 9, "@任务"), BotMentionSpan(13, 19, "@Chief")
        ))
        val selected = draft.insertQuote("引用", 3, 10)
        assertEquals(3, selected.cursor)
        assertEquals("🙂 保留 @Chief", selected.draft.text)
        assertEquals(listOf(BotMentionSpan(6, 12, "@Chief")), selected.draft.spans)
        val second = selected.draft.insertQuote("第二条", 3, 3).draft
        assertEquals(listOf("引用", "第二条"), second.quoteParts().filter { it.type == "quote" }.map { it.text })
        assertEquals(listOf("第二条"), second.removeQuote(1).quoteParts().filter { it.type == "quote" }.map { it.text })
        assertEquals("> 引用", BotMentionDraft().insertQuote("引用", 0, 0).draft.wireText())
    }

    @Test
    fun twoDisplayTokensPreserveDistinctWireFormatsAfterEditing() {
        val folder = BotMentionCandidate("中 文/", "directory", "中 文/", "@\"中 文/\"")
        val conversation = BotMentionCandidate("session-id", "session", "聊一聊", "@[聊一聊](dsh-session:InNlc3Npb24taWQi)")
        val draft = BotMentionDraft("🙂 查看 @中")
        val first = draft.insert(assertNotNull(draft.trigger(draft.text.length)), folder, folder.label)
        assertEquals("🙂 查看 @中 文/ ", first.draft.text)
        assertEquals(first.draft.text.length, first.cursor)
        assertNull(first.draft.trigger(first.cursor - 1))
        val next = first.draft.replacingText(first.draft.text + "和 @聊")
        val second = next.insert(assertNotNull(next.trigger(next.text.length)), conversation, conversation.label).draft
        val prefixed = second.replacingText("请" + second.text)
        val edited = prefixed.replacingText(prefixed.text + "谢谢")
        assertEquals("请🙂 查看 @\"中 文/\" 和 @[聊一聊](dsh-session:InNlc3Npb24taWQi) 谢谢", edited.wireText())
        assertEquals(edited.wireText(), BotMentions.decodeDraft(edited.encoded()).wireText())
        assertEquals(1, edited.replacingText(edited.text.replace("聊一聊", "新标题")).spans.size)
    }

    @Test
    fun quotedFolderDrillKeepsTheNextQueryOpen() {
        val draft = BotMentionDraft("@\"测试")
        val next = draft.drill(assertNotNull(draft.trigger(draft.text.length)), "测试 记录/")
        val trigger = assertNotNull(next.draft.trigger(next.cursor))
        assertEquals("测试 记录/", trigger.query)
        assertEquals(true, trigger.quoted)
        assertNull(BotMentionDraft("mail@example").trigger(12))
    }

    @Test
    fun groupOfferedMembersAreLimitedToItsRoster() {
        val chats = listOf(
            BotChat("chief", "Chief"), BotChat("research", "研究员"), BotChat("other", "Other"),
            BotChat("room", "群聊", room = true, memberIds = listOf("chief", "research"))
        )
        assertEquals(listOf("@Chief", "@研究员", "@all"), BotMentions.members("room", chats, "").map { it.wire })
        assertEquals(listOf("@研究员", "@Other"), BotMentions.members("chief", chats, "").map { it.wire })
    }
}
