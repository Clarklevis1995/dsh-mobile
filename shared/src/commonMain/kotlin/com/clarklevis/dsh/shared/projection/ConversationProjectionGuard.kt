package com.clarklevis.dsh.shared.projection

object ConversationProjectionGuard {
    private val operationKinds = setOf("insert", "insert-before", "append-text", "replace", "remove")

    fun validItem(item: ConversationItem): Boolean {
        if (item.id.isBlank() || item.id.length > 512 || !item.epochSeconds.isFinite()) return false
        if (item.sourceEventId != null && (item.sourceEventId.isBlank() || item.sourceEventId.length > 512)) return false
        if (item.segments == null) return true
        if (item.text.length > GenuiBlockParser.MAX_MARKDOWN_CHARS) return false
        return item.kind == ConversationItemKind.ASSISTANT && validSegments(item.segments, item)
    }

    fun validSegmentContent(segment: ConversationSegment, itemId: String): Boolean = when (segment) {
        is MarkdownSegment -> true
        is GenuiSegment -> {
            if (segment.id.isBlank() || segment.id.length > 512 ||
                !segment.id.startsWith("$itemId-genui-")
            ) {
                false
            } else if (segment.rawJson.length > GenuiBlockParser.MAX_JSON_CHARS ||
                segment.fallbackMarkdown.length > GenuiBlockParser.MAX_INLINE_FALLBACK_CHARS ||
                segment.fallbackStart < 0 ||
                segment.fallbackEnd < segment.fallbackStart ||
                (segment.fallbackMarkdown.isEmpty() && segment.fallbackEnd <= segment.fallbackStart)
            ) {
                false
            } else if (segment.state == GenuiSegmentState.READY) {
                segment.rawJson.isNotBlank() &&
                    GenuiBlockParser.isValidArtifact(segment.rawJson) &&
                    segment.contentVersion == GenuiBlockParser.contentVersion(segment.rawJson)
            } else {
                segment.rawJson.isEmpty() && segment.contentVersion.isEmpty() && !segment.interactive
            }
        }
    }

    fun validSegmentAgainstText(segment: ConversationSegment, itemText: String): Boolean = when (segment) {
        is MarkdownSegment -> segment.text.length <= itemText.length
        is GenuiSegment -> {
            if (segment.fallbackStart < 0 || segment.fallbackEnd < segment.fallbackStart ||
                segment.fallbackEnd > itemText.length
            ) {
                false
            } else {
                val source = itemText.substring(segment.fallbackStart, segment.fallbackEnd)
                if (segment.fallbackMarkdown.isNotEmpty() && segment.fallbackMarkdown != source) {
                    false
                } else if (segment.fallbackMarkdown.isEmpty() &&
                    source.length <= GenuiBlockParser.MAX_INLINE_FALLBACK_CHARS
                ) {
                    false
                } else {
                    true
                }
            }
        }
    }

    fun validSegments(segments: List<ConversationSegment>, item: ConversationItem): Boolean {
        if (segments.isEmpty() || segments.size > GenuiBlockParser.MAX_BLOCKS * 2 + 1) return false
        val blockIds = mutableSetOf<String>()
        for (segment in segments) {
            if (segment is GenuiSegment && !blockIds.add(segment.id)) return false
            if (!validSegmentContent(segment, item.id) || !validSegmentAgainstText(segment, item.text)) return false
        }
        return true
    }

    /**
     * Incremental validation for `append-text` operations on an already-validated item.
     * Unchanged prefix segments carried forward from [previousItem] remain valid because
     * `updatedText` is a strict append extension of `previousItem.text`, avoiding O(payload)
     * JSON re-parsing and SHA-256 hashing of completed READY cards on every trailing token.
     */
    fun validAppendedSegments(
        previousItem: ConversationItem,
        updatedText: String,
        updatedSegments: List<ConversationSegment>,
        changedIndices: Set<Int>,
        contentAlreadyValidated: Boolean = false
    ): Boolean {
        if (previousItem.kind != ConversationItemKind.ASSISTANT) return false
        if (updatedText.length > GenuiBlockParser.MAX_MARKDOWN_CHARS) return false
        if (updatedSegments.isEmpty() || updatedSegments.size > GenuiBlockParser.MAX_BLOCKS * 2 + 1) return false
        val previousSegments = previousItem.segments
        val blockIds = mutableSetOf<String>()
        for (index in updatedSegments.indices) {
            val segment = updatedSegments[index]
            if (segment is GenuiSegment && !blockIds.add(segment.id)) return false
            val unchangedPrefix = index !in changedIndices &&
                previousSegments != null &&
                index < previousSegments.size &&
                previousSegments[index] === segment
            if (unchangedPrefix) continue
            if (!contentAlreadyValidated && !validSegmentContent(segment, previousItem.id)) return false
            if (!validSegmentAgainstText(segment, updatedText)) return false
        }
        return true
    }

    fun validOperation(operation: ConversationProjectionOperation): Boolean {
        if (operation.kind !in operationKinds) return false
        if (operation.segments != null && operation.segmentChanges != null) return false
        if (operation.segments != null && operation.kind != "append-text") return false
        if (operation.segmentChanges != null && operation.kind != "append-text") return false
        return when (operation.kind) {
            "insert" -> operation.item != null && validItem(operation.item) &&
                operation.itemId == null && operation.delta == null && operation.epochSeconds == null &&
                operation.segments == null && operation.segmentChanges == null
            "insert-before" -> operation.item != null && validItem(operation.item) &&
                !operation.itemId.isNullOrBlank() && operation.delta == null && operation.epochSeconds == null &&
                operation.segments == null && operation.segmentChanges == null
            "append-text" -> {
                val itemId = operation.itemId
                if (operation.item != null || itemId.isNullOrBlank() || itemId.length > 512 ||
                    operation.delta.isNullOrEmpty() || operation.epochSeconds?.isFinite() != true
                ) {
                    false
                } else {
                    val fullSegments = operation.segments
                    val changes = operation.segmentChanges
                    when {
                        fullSegments != null -> {
                            if (fullSegments.isEmpty() || fullSegments.size > GenuiBlockParser.MAX_BLOCKS * 2 + 1) {
                                false
                            } else {
                                val blockIds = mutableSetOf<String>()
                                fullSegments.all { segment ->
                                    (segment !is GenuiSegment || blockIds.add(segment.id)) &&
                                        validSegmentContent(segment, itemId)
                                }
                            }
                        }
                        changes != null -> {
                            if (changes.isEmpty() || changes.size > GenuiBlockParser.MAX_BLOCKS * 2 + 1) {
                                false
                            } else {
                                var previousIndex = -1
                                val blockIds = mutableSetOf<String>()
                                changes.all { change ->
                                    val validIndex = change.index > previousIndex &&
                                        change.index < GenuiBlockParser.MAX_BLOCKS * 2 + 1
                                    previousIndex = change.index
                                    validIndex &&
                                        (change.segment !is GenuiSegment || blockIds.add(change.segment.id)) &&
                                        validSegmentContent(change.segment, itemId)
                                }
                            }
                        }
                        else -> true
                    }
                }
            }
            "replace" -> operation.item != null && validItem(operation.item) &&
                operation.itemId == operation.item.id && operation.delta == null && operation.epochSeconds == null &&
                operation.segments == null && operation.segmentChanges == null
            "remove" -> operation.item == null && !operation.itemId.isNullOrBlank() &&
                operation.delta == null && operation.epochSeconds == null &&
                operation.segments == null && operation.segmentChanges == null
            else -> false
        }
    }
}
