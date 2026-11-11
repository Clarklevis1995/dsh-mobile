package com.clarklevis.dsh.android.ui

import com.clarklevis.dsh.shared.protocol.GatewayTextPart

/** UI 身份与协议索引分开；重复文本通过明确的删除 ID 消歧。 */
internal class ComposerQuoteIdentity {
    data class Entry(val id: Long, val text: String, val partIndex: Int)

    private var nextId = 0L
    private var signature = emptyList<IndexedValue<GatewayTextPart>>()
    private var entries = emptyList<Entry>()
    private val removing = mutableSetOf<Long>()
    var lastVisible = emptyList<Entry>()
        private set

    fun update(parts: List<GatewayTextPart>): List<Entry> {
        val quotes = parts.withIndex().filter { it.value.type == "quote" }
        if (quotes == signature) return entries
        val pool = entries.filterNot { it.id in removing }.groupBy { it.text }
            .mapValues { (_, entries) -> ArrayDeque(entries) }
        entries = quotes.map { input ->
            val retained = pool[input.value.text]?.removeFirstOrNull()
            retained?.copy(partIndex = input.index) ?: Entry(nextId++, input.value.text, input.index)
        }
        signature = quotes
        removing.clear()
        if (entries.isNotEmpty()) lastVisible = entries
        return entries
    }

    fun remove(id: Long, parts: List<GatewayTextPart>): Int? {
        val entry = entries.firstOrNull { it.id == id } ?: return null
        if (parts.getOrNull(entry.partIndex) != GatewayTextPart("quote", entry.text)) return null
        removing += id
        return entry.partIndex
    }
}
