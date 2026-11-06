package com.clarklevis.dsh.shared.protocol

/** 输入框只编辑正文，引用仍按插入位置保存在原草稿中。坐标采用两端原生输入框的 UTF-16 偏移。 */
object ComposerQuoteDraft {
    private data class Quote(val offset: Int, val text: String)

    fun editableText(draft: String): String = parts(draft).filter { it.type == "text" }.joinToString("") { it.text }

    fun replaceText(draft: String, text: String): String {
        val parts = parts(draft)
        val previous = parts.filter { it.type == "text" }.joinToString("") { it.text }
        if (previous == text) return draft
        var prefix = 0
        while (prefix < minOf(previous.length, text.length) && previous[prefix] == text[prefix]) prefix++
        var suffix = 0
        while (suffix < minOf(previous.length, text.length) - prefix &&
            previous[previous.lastIndex - suffix] == text[text.lastIndex - suffix]) suffix++
        val end = previous.length - suffix
        val delta = text.length - previous.length
        val quotes = quotes(parts).map { quote ->
            quote.copy(offset = when {
                quote.offset <= prefix -> quote.offset
                quote.offset >= end -> quote.offset + delta
                else -> prefix
            }.coerceIn(0, text.length))
        }
        return render(text, quotes)
    }

    fun insertQuote(draft: String, quote: String, selectionStart: Int, selectionEnd: Int): String {
        if (quote.isBlank()) return draft
        val text = editableText(draft)
        val start = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        val end = maxOf(selectionStart, selectionEnd).coerceIn(start, text.length)
        val updated = replaceText(draft, text.removeRange(start, end))
        return render(editableText(updated), (quotes(parts(updated)) + Quote(start, quote)).sortedBy { it.offset })
    }

    fun removeQuote(draft: String, index: Int): String {
        val remaining = parts(draft).filterIndexed { partIndex, part -> partIndex != index || part.type != "quote" }
        return render(remaining.filter { it.type == "text" }.joinToString("") { it.text }, quotes(remaining))
    }

    private fun parts(draft: String): List<GatewayTextPart> {
        val parts = GatewayQuoteTextParts.parse(draft) ?: return listOf(GatewayTextPart("text", draft))
        return parts.mapIndexed { index, part ->
            if (part.type != "text") part else {
                // Markdown 在正文与引用之间使用空行，这些分隔符不属于可编辑正文。
                var text = part.text
                if (parts.getOrNull(index - 1)?.type == "quote") text = text.removePrefix("\n")
                if (parts.getOrNull(index + 1)?.type == "quote") text = text.removeSuffix("\n")
                part.copy(text = text)
            }
        }
    }

    private fun quotes(parts: List<GatewayTextPart>): List<Quote> {
        var offset = 0
        return parts.mapNotNull { part ->
            if (part.type == "quote") Quote(offset, part.text) else {
                offset += part.text.length
                null
            }
        }
    }

    private fun render(text: String, quotes: List<Quote>): String {
        if (quotes.isEmpty()) return text
        val segments = mutableListOf<String>()
        var offset = 0
        for (quote in quotes) {
            if (quote.offset > offset) segments += text.substring(offset, quote.offset)
            segments += quote.text.replace("\r\n", "\n").replace('\r', '\n').split("\n").joinToString("\n") { "> $it" }
            offset = quote.offset
        }
        if (offset < text.length) segments += text.substring(offset)
        return segments.joinToString("\n\n")
    }
}
