package com.clarklevis.dsh.android.ui

import com.clarklevis.dsh.shared.projection.ConversationItem
import com.clarklevis.dsh.shared.protocol.GatewayImageAttachment

internal data class ConversationGalleryEntry(
    val attachment: GatewayImageAttachment,
    val time: Double,
    val messageId: String,
    val position: Int
)

/** 仅保留图片目录；历史窗口变化时仍可浏览已发现的图片，不持有原图字节。 */
internal fun mergeConversationGallery(
    previous: List<ConversationGalleryEntry>,
    items: List<ConversationItem>
): List<ConversationGalleryEntry> = mergeGalleryEntries(previous, conversationGalleryEntries(items))

internal fun conversationGalleryEntries(items: List<ConversationItem>): List<ConversationGalleryEntry> = items.flatMap { item ->
    item.images.mapIndexed { index, attachment -> ConversationGalleryEntry(attachment, item.epochSeconds, item.id, index) }
}

internal fun mergeGalleryEntries(
    previous: List<ConversationGalleryEntry>,
    current: List<ConversationGalleryEntry>
): List<ConversationGalleryEntry> = (previous + current).distinctBy { it.attachment.attachmentId }
    .sortedWith(compareBy<ConversationGalleryEntry> { it.time }.thenBy { it.messageId.substringAfterLast('-').substringBefore(':').toLongOrNull() ?: Long.MAX_VALUE }
        .thenBy { it.messageId }.thenBy { it.position })
