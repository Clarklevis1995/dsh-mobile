package com.clarklevis.dsh.android.ui

import com.clarklevis.dsh.shared.projection.ConversationItem
import com.clarklevis.dsh.shared.projection.ConversationItemKind
import com.clarklevis.dsh.shared.protocol.GatewayImageAttachment
import kotlin.math.ceil

/** 一次触边补足约两屏正文；工具事件不按消息数占用阅读窗口。 */
internal data class HistoryReadBatch(
    val originalIds: Set<String>,
    val beforeSequence: Int,
    val viewportHeightDp: Float,
    val contentWidthDp: Float,
    val fontScale: Float,
    val startedAtMillis: Long,
    val pages: Int = 0
)

internal data class HistoryReadWindow(
    val messages: Int,
    val estimatedHeightDp: Float,
    val characters: Long
)

/** 仅估算新增历史的可读正文，不把 tool/call、tool/result 和 Markdown 分块算成消息。 */
internal fun historyReadWindow(batch: HistoryReadBatch, items: List<ConversationItem>): HistoryReadWindow {
    var messages = 0
    var height = 0f
    var characters = 0L
    for (item in items) {
        if (item.id in batch.originalIds) break
        characters += item.text.length
        if (item.kind != ConversationItemKind.USER && item.kind != ConversationItemKind.ASSISTANT) continue
        messages++
        val textWidth = (batch.contentWidthDp - 32f).coerceAtLeast(80f)
        val lineHeight = 24f * batch.fontScale
        val lines = item.text.lineSequence().sumOf { line ->
            val width = line.sumOf { if (it.code > 255) 16.0 else 8.0 } * batch.fontScale
            ceil(width / textWidth).toInt().coerceAtLeast(1)
        }
        height += lines * lineHeight + 48f
        if (item.images.isNotEmpty()) height += 160f
        height += item.files.size * 48f
    }
    return HistoryReadWindow(messages, height, characters)
}

/** null 表示继续补页；任何上限只在响应完成后判断，不取消已在途的网络请求。 */
internal fun historyReadBatchStopReason(
    batch: HistoryReadBatch,
    window: HistoryReadWindow,
    earliestSequence: Int?,
    hasMore: Boolean,
    failed: Boolean,
    nowMillis: Long
): String? = when {
    failed -> "error"
    !hasMore -> "end"
    earliestSequence == null || earliestSequence >= batch.beforeSequence -> "no-progress"
    window.estimatedHeightDp >= batch.viewportHeightDp * 2 -> "window-filled"
    window.characters >= 1_048_576 -> "content-budget"
    batch.pages + 1 >= 8 -> "page-budget"
    nowMillis - batch.startedAtMillis >= 2_500 -> "time-budget"
    else -> null
}

/** 一次触摸只发起一个阅读批次；到顶后的持续拖动才算翻页意图。 */
internal class HistoryEdgePull(private val threshold: Float) {
    private var distance = 0f
    private var requested = false

    fun reset() {
        distance = 0f
        requested = false
    }

    fun shouldRequest(deltaY: Float, atStart: Boolean, userInput: Boolean, canLoad: Boolean): Boolean {
        if (!userInput) return false
        if (!atStart || deltaY <= 0f) {
            distance = 0f
            return false
        }
        if (requested || !canLoad) return false
        distance += deltaY
        if (distance < threshold) return false
        requested = true
        return true
    }
}

/** 只承载 Android 原生呈现；不修改共享分页游标和数据归并规则。 */
internal data class ConversationTimelinePresentation(
    val items: List<ConversationItem>,
    val entries: List<ConversationTimelineEntry>,
    val streamingId: String?,
    val earliestSequence: Int? = null,
    val running: Boolean = false,
    val imageReferences: Map<String, GatewayImageAttachment> = emptyMap()
)

internal fun prepareConversationPresentation(
    items: List<ConversationItem>,
    running: Boolean,
    earliestSequence: Int? = null,
    imageReferences: Map<String, GatewayImageAttachment> = emptyMap()
): ConversationTimelinePresentation {
    val streamingId = activeStreamingAssistantMessageId(items, running)
    return ConversationTimelinePresentation(
        items, makeConversationTimelineEntries(makeConversationDisplayEntries(items, imageReferences), streamingId),
        streamingId, earliestSequence, running, imageReferences
    )
}

internal data class HistoryVisibleRow(val id: String, val offset: Int)
internal data class HistoryReadingAnchor(val id: String, val index: Int, val offset: Int)

/** 优先保住未改变的正文；分页合并导致旧分组头消失时，不能回到旧窗口的第一项。 */
internal fun historyReadingAnchor(
    previous: List<ConversationTimelineEntry>,
    target: List<ConversationTimelineEntry>,
    visible: List<HistoryVisibleRow>
): HistoryReadingAnchor? {
    val old = previous.associateBy { it.id }
    val positions = target.withIndex().associate { it.value.id to it.index }
    val retained = visible.filter { row ->
        val index = positions[row.id] ?: return@filter false
        old[row.id] == target[index]
    }
    val body = retained.firstOrNull { row ->
        (old[row.id] as? ConversationTimelineEntry.Display)?.entry !is ConversationDisplayEntry.Process
    } ?: retained.firstOrNull()
    if (body != null) return HistoryReadingAnchor(body.id, positions.getValue(body.id), body.offset)
    // 屏内仅有合并后的过程分组时，按共有事件找到其新分组；保持原屏内偏移。
    for (row in visible) {
        val group = ((old[row.id] as? ConversationTimelineEntry.Display)?.entry as? ConversationDisplayEntry.Process)?.group
            ?: continue
        val eventIds = group.items.mapTo(mutableSetOf()) { it.id }
        val index = target.indexOfFirst {
            val next = ((it as? ConversationTimelineEntry.Display)?.entry as? ConversationDisplayEntry.Process)?.group
            next?.items?.any { event -> event.id in eventIds } == true
        }
        if (index >= 0) return HistoryReadingAnchor(target[index].id, index, row.offset)
    }
    return visible.firstNotNullOfOrNull { row ->
        positions[row.id]?.let { HistoryReadingAnchor(row.id, it, row.offset) }
    }
}

internal data class HistoryPresentationTarget(
    val items: List<ConversationItem>,
    val earliestSequence: Int?,
    val running: Boolean,
    val moving: Boolean,
    val interactionGeneration: Int,
    val loadingOlder: Boolean = false,
    val hasMore: Boolean = false,
    val failed: Boolean = false,
    val readingBatch: Boolean = false,
    val imageReferences: Map<String, GatewayImageAttachment> = emptyMap()
)
