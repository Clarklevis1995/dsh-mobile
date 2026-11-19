package com.clarklevis.dsh.shared.facade

import com.clarklevis.dsh.shared.protocol.ComposerQuoteDraft
import com.clarklevis.dsh.shared.protocol.GatewayTextPart
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class BotMentionCandidate(
    val id: String,
    val kind: String,
    val label: String,
    val wire: String,
    val detail: String = "",
    val path: String? = null
)

@Serializable
data class BotMentionSpan(val start: Int, val end: Int, val wire: String)

data class BotMentionTrigger(val start: Int, val end: Int, val query: String, val quoted: Boolean)

data class BotMentionEdit(val draft: BotMentionDraft, val cursor: Int)

@Serializable
data class BotMentionDraft(
    val text: String = "",
    val spans: List<BotMentionSpan> = emptyList(),
    private val quotes: List<ComposerQuoteDraft.Quote> = emptyList()
) {
    /** 位置统一使用 UTF-16，与 UIKit 和 Compose 文本选区一致。 */
    fun trigger(cursor: Int): BotMentionTrigger? {
        if (cursor !in 1..text.length || spans.any { cursor > it.start && cursor <= it.end }) return null
        val start = text.lastIndexOf('@', cursor - 1)
        if (start < 0 || start > 0 && text[start - 1] in ADDRESS_CHARACTERS) return null
        val suffix = text.substring(start + 1, cursor)
        val quoted = suffix.startsWith('"')
        val query = if (quoted) suffix.drop(1) else suffix
        if (query.length > 256 || query.any { it == '"' || it.isISOControl() || !quoted && it.isWhitespace() }) return null
        return BotMentionTrigger(start, cursor, query, quoted)
    }

    /** 保留编辑区间两侧的引用，用户改动引用正文后，该片段成为普通文字。 */
    fun replacingText(value: String): BotMentionDraft {
        if (text == value) return this
        val prefix = text.commonPrefixWith(value).length
        val ending = text.substring(prefix).commonSuffixWith(value.substring(prefix)).length
        val oldEnd = text.length - ending
        return replaceRange(prefix, oldEnd, value.substring(prefix, value.length - ending))
    }

    private fun replaceRange(start: Int, end: Int, value: String): BotMentionDraft {
        val delta = value.length - (end - start)
        return copy(text = text.replaceRange(start, end, value), spans = spans.mapNotNull { span ->
            when {
                span.end <= start -> span
                span.start >= end -> span.copy(start = span.start + delta, end = span.end + delta)
                else -> null
            }
        }, quotes = ComposerQuoteDraft.shiftQuotes(quotes, start, end, delta))
    }

    fun insert(trigger: BotMentionTrigger, candidate: BotMentionCandidate, label: String): BotMentionEdit {
        if (this.trigger(trigger.end) != trigger || candidate.wire.isBlank()) return BotMentionEdit(this, trigger.end)
        val visible = "@$label"
        val newEnd = trigger.start + visible.length
        val separator = if (text.getOrNull(trigger.end)?.isWhitespace() == true) "" else " "
        val updated = replaceRange(trigger.start, trigger.end, visible + separator)
        val span = BotMentionSpan(trigger.start, newEnd, candidate.wire)
        return BotMentionEdit(updated.copy(spans = (updated.spans + span).sortedBy { it.start }), newEnd + separator.length)
    }

    fun drill(trigger: BotMentionTrigger, path: String): BotMentionEdit {
        if (this.trigger(trigger.end) != trigger || !path.endsWith('/')) return BotMentionEdit(this, trigger.end)
        val prefix = if (trigger.quoted || path.any { it.isWhitespace() }) "@\"" else "@"
        val value = prefix + path
        return BotMentionEdit(replaceRange(trigger.start, trigger.end, value), trigger.start + value.length)
    }

    fun insertQuote(quote: String, selectionStart: Int, selectionEnd: Int): BotMentionEdit {
        val start = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        val end = maxOf(selectionStart, selectionEnd).coerceIn(start, text.length)
        if (quote.isBlank()) return BotMentionEdit(this, start)
        val cursor = if (start == end) spans.firstOrNull { start > it.start && start < it.end }?.end ?: start else start
        val updated = if (start == end) this else replaceRange(start, end, "")
        return BotMentionEdit(updated.copy(
            quotes = (updated.quotes + ComposerQuoteDraft.Quote(cursor, quote)).sortedBy { it.offset }
        ), cursor)
    }

    fun quoteParts(): List<GatewayTextPart> =
        if (quotes.isEmpty()) emptyList() else ComposerQuoteDraft.renderParts(text, quotes)

    fun removeQuote(partIndex: Int): BotMentionDraft {
        val parts = quoteParts()
        if (parts.getOrNull(partIndex)?.type != "quote") return this
        val quoteIndex = parts.take(partIndex).count { it.type == "quote" }
        return copy(quotes = quotes.filterIndexed { index, _ -> index != quoteIndex })
    }

    fun hasQuotes(): Boolean = quotes.isNotEmpty()

    fun displayText(): String = ComposerQuoteDraft.render(text, quotes)

    fun wireText(): String {
        var wireText = text
        var wireQuotes = quotes
        for (span in spans.asReversed()) {
            wireText = wireText.replaceRange(span.start, span.end, span.wire)
            wireQuotes = ComposerQuoteDraft.shiftQuotes(wireQuotes, span.start, span.end, span.wire.length - (span.end - span.start))
        }
        return ComposerQuoteDraft.render(wireText, wireQuotes)
    }

    fun encoded(): String = Json.encodeToString(this)

    private companion object {
        const val ADDRESS_CHARACTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_./"
    }
}

object BotMentions {
    private val ALL_NAMES = listOf("all", "everyone", "所有人", "全体") // i18n-ignore: DS Bot 协议识别的关键词

    fun emptyDraft() = BotMentionDraft()

    fun decodeDraft(json: String): BotMentionDraft =
        runCatching { Json.decodeFromString<BotMentionDraft>(json) }.getOrDefault(BotMentionDraft())

    fun members(chatId: String, chats: List<BotChat>, query: String): List<BotMentionCandidate> {
        val chat = chats.firstOrNull { it.id == chatId } ?: return emptyList()
        val eligible = if (chat.room) chat.memberIds.mapNotNull { id -> chats.firstOrNull { it.id == id && !it.room } }
            else chats.filter { !it.room && it.id != chatId }
        val needle = query.trim()
        val matching = eligible.filter { it.name.contains(needle, ignoreCase = true) }.map {
            BotMentionCandidate(it.id, "member", it.name, "@${it.name}", it.role)
        }
        val everyone = chat.room && eligible.size > 1 && ALL_NAMES.any { it.startsWith(needle, ignoreCase = true) }
        return if (everyone) matching + BotMentionCandidate("everyone", "everyone", "everyone", "@all") else matching
    }
}
