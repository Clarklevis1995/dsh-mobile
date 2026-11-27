package com.clarklevis.dsh.shared.projection

import com.clarklevis.dsh.shared.util.Sha256
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

@Serializable
sealed interface ConversationSegment

@Serializable
@SerialName("markdown")
data class MarkdownSegment(val text: String) : ConversationSegment

@Serializable
enum class GenuiSegmentState { STREAMING, READY, INVALID }

@Serializable
@SerialName("genui")
data class GenuiSegment(
    val id: String,
    val rawJson: String,
    val state: GenuiSegmentState,
    val fallbackMarkdown: String = "",
    val fallbackStart: Int = 0,
    val fallbackEnd: Int = 0,
    val contentVersion: String = "",
    val interactive: Boolean = false
) : ConversationSegment

object GenuiBlockParser {
    const val MAX_MARKDOWN_CHARS = 2_000_000
    const val MAX_JSON_CHARS = 512_000
    const val MAX_INLINE_FALLBACK_CHARS = 32_768
    const val MAX_BLOCKS = 16
    const val MAX_JSON_DEPTH = 64
    const val MAX_JSON_CONTAINERS = 20_000
    const val MAX_JSON_ELEMENTS = 20_000

    private val fencePattern = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")
    private val trailingTagPunctuation = setOf('>', ':', ';', ',', '.')

    // Excludes unbundled engines (`mermaid`, `scene3d`) so blocks requiring them
    // fail closed deterministically to their original Markdown code fence.
    private val supportedTypes = setOf(
        "text", "row", "col", "grid", "card", "button", "input", "select", "date-picker",
        "segmented-control", "checkbox", "link", "carousel", "image-group", "entity-card",
        "popover", "compare", "choices", "image", "audio", "video", "badge", "hero", "stat",
        "progress", "divider", "list", "table", "chart", "tabs", "avatar", "spacer", "icon",
        "blocks", "arrow", "plot", "callout", "steps", "keyvalue", "diff", "json", "code",
        "radio", "submit", "switch", "slider", "textarea", "accordion", "copy",
        "svg", "timeline", "file-tree", "breadcrumb", "quiz", "diagram", "editor",
        "echart", "map"
    )
    private val nodeContainerTypes = setOf("row", "col", "grid", "card", "carousel")
    // Mirrors official `@changfenhuang/dsh-genui` `COMPONENT_SCHEMAS` entries that declare their own `title` field.
    private val componentTypesWithOwnTitle = setOf(
        "callout", "card", "carousel", "image-group", "choices", "compare",
        "diagram", "echart", "blocks", "map", "svg", "plot", "hero"
    )
    private val envelopeKeys = setOf("format", "version", "spec")
    private val strippedSpecKeys = setOf("panel", "append", "state", "presentation")

    fun parse(messageId: String, markdown: String, isFinal: Boolean = false): List<ConversationSegment>? {
        if (markdown.length > MAX_MARKDOWN_CHARS ||
            (!markdown.contains("dsh-ui", ignoreCase = true) && !markdown.contains("genui", ignoreCase = true))
        ) return null
        return parseFrom(messageId, markdown, startOffset = 0, startBlockIndex = 0, isFinal = isFinal)
    }

    private fun parseFrom(
        messageId: String,
        markdown: String,
        startOffset: Int,
        startBlockIndex: Int,
        isFinal: Boolean
    ): List<ConversationSegment>? {
        val segments = mutableListOf<ConversationSegment>()
        var markdownStart = startOffset
        var cursor = startOffset
        var ordinaryFence: Fence? = null
        var blockIndex = startBlockIndex

        while (cursor < markdown.length) {
            val lineStart = cursor
            val newline = markdown.indexOf('\n', lineStart)
            val lineEnd = if (newline < 0) markdown.length else newline
            val nextLine = if (newline < 0) markdown.length else newline + 1
            val line = markdown.substring(lineStart, lineEnd).removeSuffix("\r")
            val fence = parseFence(line)

            if (ordinaryFence != null) {
                if (fence.closes(ordinaryFence)) ordinaryFence = null
            } else if (fence.isOpening) {
                if (isGenuiFenceInfo(fence.info)) {
                    if (blockIndex >= MAX_BLOCKS) return null
                    appendMarkdown(segments, markdown.substring(markdownStart, lineStart))
                    val closing = findClosingFence(markdown, nextLine, fence)
                    val blockEnd = closing?.second ?: markdown.length
                    val contentEnd = closing?.first ?: markdown.length
                    val sourceJson = markdown.substring(nextLine, contentEnd).trim()
                    val rendered = if (closing != null) renderArtifact(sourceJson) else null
                    val segmentState = when {
                        closing == null && !isFinal -> GenuiSegmentState.STREAMING
                        rendered != null -> GenuiSegmentState.READY
                        else -> GenuiSegmentState.INVALID
                    }
                    val fallback = markdown.substring(lineStart, blockEnd)
                    segments.add(
                        GenuiSegment(
                            id = "$messageId-genui-$blockIndex",
                            rawJson = if (segmentState == GenuiSegmentState.READY) rendered.orEmpty() else "",
                            state = segmentState,
                            fallbackMarkdown = fallback.takeIf { it.length <= MAX_INLINE_FALLBACK_CHARS }.orEmpty(),
                            fallbackStart = lineStart,
                            fallbackEnd = blockEnd,
                            contentVersion = if (segmentState == GenuiSegmentState.READY) contentVersion(rendered.orEmpty()) else "",
                            interactive = segmentState == GenuiSegmentState.READY && isFinal
                        )
                    )
                    blockIndex += 1
                    cursor = blockEnd
                    markdownStart = blockEnd
                    continue
                }
                ordinaryFence = fence
            }
            cursor = nextLine
        }

        if (blockIndex == startBlockIndex && startBlockIndex == 0) return null
        appendMarkdown(segments, markdown.substring(markdownStart))
        return segments
    }

    fun parseAppend(
        messageId: String,
        previousText: String,
        delta: String,
        previousSegments: List<ConversationSegment>?
    ): List<ConversationSegment>? {
        if (delta.isEmpty()) return previousSegments
        if (previousText.length + delta.length > MAX_MARKDOWN_CHARS) return null
        val fullText = previousText + delta
        val previous = previousSegments ?: return if (mayOpenGenuiFence(previousText, delta)) {
            parse(messageId, fullText)
        } else null

        val lastGenuiIndex = previous.indexOfLast { it is GenuiSegment }
        if (lastGenuiIndex < 0) {
            return if (mayOpenGenuiFence(previousText, delta)) parse(messageId, fullText) else null
        }

        val lastGenui = previous[lastGenuiIndex] as GenuiSegment
        val priorBlockCount = previous.count { it is GenuiSegment }

        return when (val last = previous.lastOrNull()) {
            is MarkdownSegment -> {
                if (mayOpenGenuiFence(previousText, delta)) {
                    // Cursor-based tail re-parse: never re-scan or re-validate already-closed blocks.
                    val tail = parseFrom(
                        messageId = messageId,
                        markdown = fullText,
                        startOffset = lastGenui.fallbackEnd,
                        startBlockIndex = priorBlockCount,
                        isFinal = false
                    ) ?: return null
                    previous.subList(0, lastGenuiIndex + 1) + tail
                } else {
                    previous.dropLast(1) + last.copy(text = last.text + delta)
                }
            }
            is GenuiSegment -> when {
                last.state == GenuiSegmentState.STREAMING -> {
                    val openingNewline = previousText.indexOf('\n', last.fallbackStart)
                    if (openingNewline < 0 && !delta.startsWith("\n") && !delta.startsWith("\r\n")) {
                        val tail = parseFrom(
                            messageId = messageId,
                            markdown = fullText,
                            startOffset = last.fallbackStart,
                            startBlockIndex = priorBlockCount - 1,
                            isFinal = false
                        )
                        return if (tail == null) null else previous.subList(0, lastGenuiIndex) + tail
                    }
                    if (mayCloseStreamingFence(previousText, delta, last)) {
                        val tail = parseFrom(
                            messageId = messageId,
                            markdown = fullText,
                            startOffset = last.fallbackStart,
                            startBlockIndex = priorBlockCount - 1,
                            isFinal = false
                        ) ?: return null
                        previous.subList(0, lastGenuiIndex) + tail
                    } else {
                        previous
                    }
                }
                last.fallbackEnd != previousText.length -> {
                    if (mayOpenGenuiFence(previousText, delta)) {
                        val tail = parseFrom(
                            messageId = messageId,
                            markdown = fullText,
                            startOffset = last.fallbackEnd,
                            startBlockIndex = priorBlockCount,
                            isFinal = false
                        ) ?: return null
                        previous.subList(0, lastGenuiIndex + 1) + tail
                    } else {
                        appendMarkdownSegment(previous, delta)
                    }
                }
                delta.startsWith("\r\n") -> {
                    val afterNewline = delta.drop(2)
                    if (mayOpenGenuiFence("", afterNewline)) {
                        val tail = parseFrom(
                            messageId = messageId,
                            markdown = fullText,
                            startOffset = last.fallbackEnd + 2,
                            startBlockIndex = priorBlockCount,
                            isFinal = false
                        ) ?: return null
                        previous.subList(0, lastGenuiIndex + 1) + tail
                    } else {
                        appendMarkdownSegment(previous, afterNewline)
                    }
                }
                delta.startsWith("\n") -> {
                    val afterNewline = delta.drop(1)
                    if (mayOpenGenuiFence("", afterNewline)) {
                        val tail = parseFrom(
                            messageId = messageId,
                            markdown = fullText,
                            startOffset = last.fallbackEnd + 1,
                            startBlockIndex = priorBlockCount,
                            isFinal = false
                        ) ?: return null
                        previous.subList(0, lastGenuiIndex + 1) + tail
                    } else {
                        appendMarkdownSegment(previous, afterNewline)
                    }
                }
                else -> {
                    // Text appended directly onto an EOF closing fence line may invalidate the fence.
                    val tail = parseFrom(
                        messageId = messageId,
                        markdown = fullText,
                        startOffset = last.fallbackStart,
                        startBlockIndex = priorBlockCount - 1,
                        isFinal = false
                    ) ?: return null
                    previous.subList(0, lastGenuiIndex) + tail
                }
            }
            null -> previous
        }
    }

    fun isValidArtifact(rawJson: String): Boolean {
        if (rawJson.isBlank() || rawJson.length > MAX_JSON_CHARS || !hasSafeJsonStructure(rawJson)) return false
        val root = try {
            Json.parseToJsonElement(rawJson) as? JsonObject ?: return false
        } catch (_: Throwable) {
            return false
        }
        return validArtifact(root)
    }

    fun contentVersion(rawJson: String): String =
        Sha256().apply { update(rawJson.encodeToByteArray()) }.digestHex()

    fun isValidPayload(rawJson: String): Boolean =
        renderArtifact(rawJson) != null

    fun fallbackMarkdown(segment: GenuiSegment, source: String): String =
        segment.fallbackMarkdown.ifEmpty {
            val start = segment.fallbackStart.coerceIn(0, source.length)
            val end = segment.fallbackEnd.coerceIn(start, source.length)
            source.substring(start, end)
        }

    fun changedSegments(
        previous: List<ConversationSegment>,
        next: List<ConversationSegment>
    ): List<ConversationSegmentChange> = next.indices.mapNotNull { index ->
        val segment = next[index]
        if (index >= previous.size || !sameSegment(previous[index], segment)) {
            ConversationSegmentChange(index, segment)
        } else null
    }

    fun isMarkdownAppendOnly(
        previous: List<ConversationSegment>,
        next: List<ConversationSegment>,
        delta: String
    ): Boolean {
        if (previous.isEmpty() || previous.size != next.size) return false
        val old = previous.last() as? MarkdownSegment ?: return false
        val new = next.last() as? MarkdownSegment ?: return false
        return new.text == old.text + delta && previous.dropLast(1) == next.dropLast(1)
    }

    internal fun renderArtifact(rawJson: String): String? {
        if (rawJson.isBlank() || rawJson.length > MAX_JSON_CHARS || !hasSafeJsonStructure(rawJson)) return null
        val root = try {
            Json.parseToJsonElement(rawJson) as? JsonObject ?: return null
        } catch (_: Throwable) {
            return null
        }
        if (envelopeKeys.any { root.containsKey(it) }) {
            val spec = root["spec"] as? JsonObject ?: return null
            if (!isComponentRoot(spec)) {
                return rawJson.takeIf { validArtifact(root) }
            }
            val normalizedSpec = wrapSingleComponentRoot(spec) ?: return null
            val normalizedArtifact = JsonObject(root + ("spec" to normalizedSpec))
            val rendered = normalizedArtifact.toString()
            return rendered.takeIf {
                it.length <= MAX_JSON_CHARS && hasSafeJsonStructure(it) && validArtifact(normalizedArtifact)
            }
        }
        val state = if (root.containsKey("state")) {
            val rawState = root["state"]
            if (!validState(rawState)) return null
            rawState as JsonObject
        } else {
            JsonObject(emptyMap())
        }
        val sourcePresentation = if (root.containsKey("presentation")) {
            root["presentation"] as? JsonObject ?: return null
        } else {
            null
        }
        if (sourcePresentation != null) {
            if (sourcePresentation.containsKey("locale") && sourcePresentation.string("locale") !in setOf("en", "zh")) return null
            if (sourcePresentation.containsKey("theme") && sourcePresentation.string("theme") !in setOf("light", "dark")) return null
        }
        val locale = sourcePresentation?.string("locale") ?: "en"
        val theme = sourcePresentation?.string("theme") ?: "light"
        val cleanRoot = JsonObject(root.filterKeys { it !in strippedSpecKeys })
        val cleanSpec = if (isComponentRoot(cleanRoot)) {
            wrapSingleComponentRoot(cleanRoot) ?: return null
        } else {
            cleanRoot
        }
        if (!validNodeArray(cleanSpec["items"])) return null
        val artifact = JsonObject(
            mapOf(
                "format" to JsonPrimitive("dsh-genui"),
                "version" to JsonPrimitive(1),
                "spec" to cleanSpec,
                "state" to state,
                "presentation" to JsonObject(
                    mapOf(
                        "locale" to JsonPrimitive(locale),
                        "theme" to JsonPrimitive(theme)
                    )
                )
            )
        )
        val rendered = artifact.toString()
        return rendered.takeIf {
            it.length <= MAX_JSON_CHARS && hasSafeJsonStructure(it) && validArtifact(artifact)
        }
    }

    // Mirrors official `@changfenhuang/dsh-genui` `isComponentRoot` / `wrapSingleComponentRoot`.
    private fun isComponentRoot(root: JsonObject): Boolean {
        val type = root.string("type")
        if (type.isNullOrEmpty()) return false
        if (root["items"] !is JsonArray) return true
        return type != "genui" && !(type == "stack" && root["children"] is JsonArray)
    }

    private fun wrapSingleComponentRoot(root: JsonObject): JsonObject? {
        val type = root.string("type")
        if (type.isNullOrEmpty()) return null
        val hoistedTitle = root.string("title")?.takeIf {
            type in supportedTypes && type !in componentTypesWithOwnTitle
        }
        val node = JsonObject(
            root.filterKeys { key ->
                key !in strippedSpecKeys && (hoistedTitle == null || key != "title")
            }
        )
        return JsonObject(
            buildMap {
                if (hoistedTitle != null) put("title", JsonPrimitive(hoistedTitle))
                put("items", JsonArray(listOf(node)))
            }
        )
    }

    private fun validArtifact(root: JsonObject): Boolean {
        if (root.string("format") != "dsh-genui") return false
        val version = root.primitive("version")
        if (version == null || version.isString || version.content != "1") return false
        val presentation = root["presentation"] as? JsonObject ?: return false
        if (presentation.string("locale") !in setOf("en", "zh")) return false
        if (presentation.string("theme") !in setOf("light", "dark")) return false
        if (root.containsKey("state") && !validState(root["state"])) return false
        val spec = root["spec"] as? JsonObject ?: return false
        return validNodeArray(spec["items"])
    }

    private fun validState(value: JsonElement?): Boolean {
        if (value !is JsonObject) return false
        if (value.containsKey("answers") && !validStringMap(value["answers"])) return false
        if (value.containsKey("fields") && !validStringMap(value["fields"])) return false
        if (value.containsKey("multiAnswers") && !validMultiAnswers(value["multiAnswers"])) return false
        if (value.containsKey("locked")) {
            val locked = value["locked"] as? JsonPrimitive ?: return false
            if (locked.isString || locked.booleanOrNull == null) return false
        }
        return true
    }

    private fun validStringMap(value: JsonElement?): Boolean {
        if (value !is JsonObject) return false
        return value.values.all { (it as? JsonPrimitive)?.isString == true }
    }

    private fun validMultiAnswers(value: JsonElement?): Boolean {
        if (value !is JsonObject) return false
        return value.values.all { entry ->
            entry is JsonArray && entry.all { (it as? JsonPrimitive)?.isString == true }
        }
    }

    private fun validNodeArray(value: JsonElement?): Boolean {
        if (value !is JsonArray || value.isEmpty()) return false
        val pending = ArrayDeque<JsonElement>()
        pending.addAll(value)
        var visited = 0
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst() as? JsonObject ?: return false
            val type = node.string("type")
            if (type !in supportedTypes || ++visited > MAX_JSON_CONTAINERS) return false
            if (type in nodeContainerTypes) {
                val items = node["items"] as? JsonArray ?: return false
                pending.addAll(items)
            }
            if (type == "tabs") {
                val holders = node["tabs"] as? JsonArray ?: return false
                for (holderElement in holders) {
                    val holder = holderElement as? JsonObject ?: return false
                    if (holder.containsKey("items")) {
                        val items = holder["items"] as? JsonArray ?: return false
                        pending.addAll(items)
                    }
                }
            }
            if (type == "accordion") {
                val holders = node["items"] as? JsonArray ?: return false
                for (holderElement in holders) {
                    val holder = holderElement as? JsonObject ?: return false
                    val items = holder["items"] as? JsonArray ?: return false
                    pending.addAll(items)
                }
            }
            if (type == "list") {
                val items = node["items"] as? JsonArray ?: return false
                for (item in items) {
                    if (item is JsonObject && item.containsKey("type")) pending.add(item)
                }
            }
            if (type == "table") {
                if (node.containsKey("columns") && node["columns"] !is JsonArray) return false
                if (node.containsKey("rows") && node["rows"] !is JsonArray) return false
                if (node.containsKey("details")) {
                    val details = node["details"] as? JsonArray ?: return false
                    for (row in details) {
                        if (row is JsonNull) continue
                        if (row !is JsonArray) return false
                        pending.addAll(row)
                    }
                }
            }
        }
        return true
    }

    private fun hasSafeJsonStructure(rawJson: String): Boolean {
        var depth = 0
        var containers = 0
        var elements = 0
        var inString = false
        var escaped = false
        for (char in rawJson) {
            if (inString) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') inString = false
                continue
            }
            when (char) {
                '"' -> inString = true
                '{', '[' -> {
                    depth += 1
                    containers += 1
                    elements += 1
                    if (depth > MAX_JSON_DEPTH || containers > MAX_JSON_CONTAINERS ||
                        elements > MAX_JSON_ELEMENTS
                    ) return false
                }
                ',', ':' -> {
                    elements += 1
                    if (elements > MAX_JSON_ELEMENTS) return false
                }
                '}', ']' -> {
                    depth -= 1
                    if (depth < 0) return false
                }
            }
        }
        return !inString && depth == 0
    }

    private fun appendMarkdown(segments: MutableList<ConversationSegment>, text: String) {
        if (text.isNotEmpty()) segments += MarkdownSegment(text)
    }

    private fun appendMarkdownSegment(previous: List<ConversationSegment>, text: String): List<ConversationSegment> =
        if (text.isEmpty()) previous else previous + MarkdownSegment(text)

    private fun mayOpenGenuiFence(previousText: String, delta: String): Boolean {
        val lastLineStart = previousText.lastIndexOf('\n').let { if (it < 0) 0 else it + 1 }
        val linePrefix = if (previousText.length - lastLineStart <= 64) {
            previousText.substring(lastLineStart)
        } else {
            previousText.takeLast(64)
        }
        val boundary = linePrefix + delta
        return boundary.lineSequence().any { line ->
            val text = line.removeSuffix("\r")
            val fence = parseFence(text)
            fence.isOpening && isGenuiFenceInfo(fence.info)
        }
    }

    private fun mayCloseStreamingFence(previousText: String, delta: String, segment: GenuiSegment): Boolean {
        val firstNewline = previousText.indexOf('\n', segment.fallbackStart)
        val firstLineEnd = if (firstNewline < 0) previousText.length else firstNewline
        val opening = parseFence(previousText.substring(segment.fallbackStart, firstLineEnd).removeSuffix("\r"))
        if (!opening.isOpening) return false
        val lastLineStart = previousText.lastIndexOf('\n').let { if (it < segment.fallbackStart) segment.fallbackStart else it + 1 }
        val boundary = previousText.substring(lastLineStart) + delta
        return boundary.lineSequence().any { parseFence(it.removeSuffix("\r")).closes(opening) }
    }

    private fun isGenuiFenceInfo(info: String): Boolean {
        val tokens = info.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        if (tokens.isEmpty()) return false
        val first = normalizeFenceTag(tokens[0])
        if (first == "dsh-ui" || first == "genui") return true
        if (first == "json" && tokens.size >= 2) {
            val second = normalizeFenceTag(tokens[1])
            return second == "dsh-ui" || second == "genui"
        }
        return false
    }

    private fun normalizeFenceTag(token: String): String =
        token.lowercase().trimEnd { it in trailingTagPunctuation }

    private fun sameSegment(old: ConversationSegment, new: ConversationSegment): Boolean = when {
        old is MarkdownSegment && new is MarkdownSegment -> old.text == new.text
        old is GenuiSegment && new is GenuiSegment -> old.id == new.id && old.state == new.state &&
            old.fallbackStart == new.fallbackStart && old.fallbackEnd == new.fallbackEnd &&
            old.fallbackMarkdown == new.fallbackMarkdown &&
            old.contentVersion == new.contentVersion &&
            old.interactive == new.interactive &&
            (old.state == GenuiSegmentState.STREAMING || old.rawJson == new.rawJson)
        else -> false
    }

    private fun findClosingFence(markdown: String, start: Int, opening: Fence): Pair<Int, Int>? {
        var cursor = start
        while (cursor < markdown.length) {
            val lineStart = cursor
            val newline = markdown.indexOf('\n', cursor)
            val lineEnd = if (newline < 0) markdown.length else newline
            val nextLine = if (newline < 0) markdown.length else newline + 1
            val line = markdown.substring(lineStart, lineEnd).removeSuffix("\r")
            if (parseFence(line).closes(opening)) return lineStart to nextLine
            cursor = nextLine
        }
        return null
    }

    private fun parseFence(line: String): Fence {
        val match = fencePattern.matchEntire(line) ?: return Fence(' ', 0, "")
        val run = match.groupValues[1]
        val info = match.groupValues[2]
        return Fence(run[0], run.length, info)
    }

    private data class Fence(val character: Char, val length: Int, val info: String) {
        val isOpening: Boolean
            get() = length >= 3 && (character != '`' || '`' !in info)

        fun closes(other: Fence): Boolean =
            character == other.character && length >= other.length && info.isBlank()
    }

    private fun JsonObject.string(name: String): String? =
        (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.primitive(name: String): JsonPrimitive? = get(name) as? JsonPrimitive
}
