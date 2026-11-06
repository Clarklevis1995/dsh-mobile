package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.protocol.ComposerQuoteDraft
import com.clarklevis.dsh.shared.protocol.GatewayQuoteTextParts
import kotlin.test.Test
import kotlin.test.assertEquals

class ComposerQuoteDraftTest {
    @Test fun quoteOnlyKeepsEditorEmptyAndSupportsTypingNewlines() {
        val draft = ComposerQuoteDraft.insertQuote("", "原文\n第二行🙂", 0, 0)
        assertEquals("", ComposerQuoteDraft.editableText(draft))
        val edited = ComposerQuoteDraft.replaceText(draft, "请解释\n")
        assertEquals("请解释\n", ComposerQuoteDraft.editableText(edited))
        assertEquals("原文\n第二行🙂", GatewayQuoteTextParts.parse(edited)?.first()?.text)
    }

    @Test fun insertingAndRemovingMiddleQuotePreservesBody() {
        val draft = ComposerQuoteDraft.insertQuote("前文后文", "选中内容", 2, 2)
        assertEquals("前文后文", ComposerQuoteDraft.editableText(draft))
        assertEquals(listOf("text", "quote", "text"), GatewayQuoteTextParts.parse(draft)?.map { it.type })
        assertEquals("前文后文", ComposerQuoteDraft.removeQuote(draft, 1))
        val edited = ComposerQuoteDraft.replaceText(draft, "新前文后文")
        assertEquals("新前文后文", ComposerQuoteDraft.editableText(edited))
        assertEquals("新前文\n", GatewayQuoteTextParts.parse(edited)?.first()?.text)
    }

    @Test fun multipleQuotesRetainOrderAndCanBeRemovedIndependently() {
        val first = ComposerQuoteDraft.insertQuote("正文", "引用一", 0, 0)
        val draft = ComposerQuoteDraft.insertQuote(first, "引用二", 2, 2)
        assertEquals("正文", ComposerQuoteDraft.editableText(draft))
        assertEquals(listOf("引用一", "引用二"), GatewayQuoteTextParts.parse(draft)?.filter { it.type == "quote" }?.map { it.text })
        assertEquals("正文", ComposerQuoteDraft.editableText(ComposerQuoteDraft.removeQuote(draft, 0)))
    }

    @Test fun selectionReplacementAndCodeFenceRemainEditable() {
        val draft = ComposerQuoteDraft.insertQuote("🙂替换正文", "引用\r\n快照", 2, 4)
        assertEquals("🙂正文", ComposerQuoteDraft.editableText(draft))
        val plain = "```text\n> 代码内容\n```\n"
        assertEquals(plain, ComposerQuoteDraft.editableText(plain))
        assertEquals(plain, ComposerQuoteDraft.replaceText(draft, plain).let { ComposerQuoteDraft.editableText(it) })
    }
}
