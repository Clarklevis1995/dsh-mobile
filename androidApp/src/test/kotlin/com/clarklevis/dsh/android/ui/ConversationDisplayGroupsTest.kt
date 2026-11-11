package com.clarklevis.dsh.android.ui

import com.clarklevis.dsh.android.localization.LocaleTestRule
import org.junit.Rule
import com.clarklevis.dsh.shared.projection.ConversationItem
import com.clarklevis.dsh.shared.projection.ConversationItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotSame
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ConversationDisplayGroupsTest {
    @get:Rule val locale = LocaleTestRule()

    @Test
    fun toolImageCardsAreHiddenWhileAssistantBodyAndUserUploadsRemain() {
        val image = com.clarklevis.dsh.shared.protocol.GatewayImageAttachment("image", "image/png", 10, 100, 80)
        val generated = item("result-images", ConversationItemKind.GENERATED_IMAGES, text = "").copy(images = listOf(image))
        val body = "已生成。\n\n![风景](/workspace/image.png)"
        val reply = item("reply", ConversationItemKind.ASSISTANT, text = body)
        val user = item("user", ConversationItemKind.USER).copy(images = listOf(image))
        val entries = makeConversationDisplayEntries(listOf(
            user, item("call", ConversationItemKind.TOOL), generated,
            item("result", ConversationItemKind.TOOL_RESULT), reply
        ))
        assertEquals(3, entries.size)
        assertEquals(user, (entries.first() as ConversationDisplayEntry.Message).item)
        assertEquals(2, (entries[1] as ConversationDisplayEntry.Process).group.items.size)
        assertEquals(reply, (entries.last() as ConversationDisplayEntry.Message).item)
        val timeline = makeConversationTimelineEntries(entries)
        assertEquals(listOf(image), timeline.first().images)
        assertEquals(body, timeline.filterIsInstance<ConversationTimelineEntry.AssistantMarkdown>().joinToString("") { it.markdown })
        assertTrue(timeline.none { it.id == generated.id })
        assertTrue(makeConversationDisplayEntries(listOf(generated)).isEmpty())
    }

    @Test
    fun fileCardsRemainInFooterAndKnownReferencesReachMarkdown() {
        val files = listOf(com.clarklevis.dsh.shared.protocol.PresentedFile("build/报告.pdf", "8 页"))
        val reply = item("reply", ConversationItemKind.ASSISTANT, text = "请看 `报告.pdf`")
            .copy(files = files, knownFiles = files)
        val timeline = makeConversationTimelineEntries(makeConversationDisplayEntries(listOf(reply)))
        assertEquals(files, (timeline.first() as ConversationTimelineEntry.AssistantMarkdown).knownFiles)
        assertEquals(files, (timeline.last() as ConversationTimelineEntry.AssistantFooter).files)
        val emptyReply = reply.copy(text = "")
        val emptyTimeline = makeConversationTimelineEntries(makeConversationDisplayEntries(listOf(emptyReply)))
        assertEquals(files, (emptyTimeline.last() as ConversationTimelineEntry.AssistantFooter).files)
    }

    @Test
    fun `only the current turn assistant is treated as streaming`() {
        val oldAssistant = item("stream-text-1-1", ConversationItemKind.ASSISTANT)
        val currentUser = item("user-2", ConversationItemKind.USER)
        val currentAssistant = item("stream-text-2-1", ConversationItemKind.ASSISTANT)

        assertEquals(
            "stream-text-2-1",
            activeStreamingAssistantMessageId(
                listOf(oldAssistant, currentUser, currentAssistant),
                isSessionRunning = true
            )
        )
        assertNull(
            activeStreamingAssistantMessageId(
                listOf(oldAssistant, currentUser),
                isSessionRunning = true
            )
        )
        assertNull(
            activeStreamingAssistantMessageId(
                listOf(oldAssistant, currentUser, currentAssistant),
                isSessionRunning = false
            )
        )
    }

    @Test
    fun `streaming assistant is markdown virtualized without a final copy footer`() {
        val text = List(5) { index ->
            "第${index + 1}段：${"流式 Markdown".repeat(40)}"
        }.joinToString("\n\n")

        val timeline = makeConversationTimelineEntries(
            makeConversationDisplayEntries(
                listOf(item("stream-text-7-9", ConversationItemKind.ASSISTANT, text = text))
            ),
            activeStreamingAssistantMessageId = "stream-text-7-9"
        )

        assertTrue(timeline.first() is ConversationTimelineEntry.AssistantMarkdown)
        assertTrue(timeline.any { it is ConversationTimelineEntry.AssistantMarkdown })
        assertFalse(timeline.any { it is ConversationTimelineEntry.AssistantFooter })
        assertEquals(
            text,
            timeline.filterIsInstance<ConversationTimelineEntry.AssistantMarkdown>()
                .joinToString("\n\n", transform = { it.markdown })
        )
    }

    @Test
    fun `completed assistant retaining stream id has a copy footer`() {
        val completed = item(
            "stream-text-7-9",
            ConversationItemKind.ASSISTANT,
            text = "已完成回复"
        )

        val timeline = makeConversationTimelineEntries(
            makeConversationDisplayEntries(listOf(completed)),
            activeStreamingAssistantMessageId = null
        )

        val footer = timeline.last() as ConversationTimelineEntry.AssistantFooter
        assertEquals("已完成回复", footer.text)
    }

    @Test
    fun `older completed reply keeps copy footer while next reply streams`() {
        val oldAssistant = item("stream-text-1-1", ConversationItemKind.ASSISTANT, text = "上一轮回复")
        val currentUser = item("user-2", ConversationItemKind.USER, text = "下一轮问题")
        val currentAssistant = item("stream-text-2-1", ConversationItemKind.ASSISTANT, text = "生成中")
        val items = listOf(oldAssistant, currentUser, currentAssistant)
        val activeId = activeStreamingAssistantMessageId(items, isSessionRunning = true)

        val timeline = makeConversationTimelineEntries(
            makeConversationDisplayEntries(items),
            activeStreamingAssistantMessageId = activeId
        )

        assertTrue(timeline.any { it.id == "assistant:stream-text-1-1:footer" })
        assertFalse(timeline.any { it.id == "assistant:stream-text-2-1:footer" })
    }

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
    fun commandOwnsFollowingContextAndReasoningAsNestedDetails() {
        val entries = makeConversationDisplayEntries(
            listOf(
                item("context-a", ConversationItemKind.CONTEXT),
                item("status", ConversationItemKind.STATUS),
                item("reason-b", ConversationItemKind.REASONING),
                item("system", ConversationItemKind.SYSTEM)
            )
        )

        assertEquals(
            listOf("process-context-a", "status", "system"),
            entries.map(ConversationDisplayEntry::id)
        )
        val command = (entries[1] as ConversationDisplayEntry.Process).group
        assertEquals("status", command.command?.id)
        assertEquals("reason-b", command.items.last().id)
        assertTrue(command.isExpandable)
    }

    @Test
    fun `lazy list content types keep incompatible message layouts separate`() {
        val entries = makeConversationDisplayEntries(
            listOf(
                item("user", ConversationItemKind.USER),
                item("assistant", ConversationItemKind.ASSISTANT),
                item("reason", ConversationItemKind.REASONING),
                item("status", ConversationItemKind.STATUS),
                item("system", ConversationItemKind.SYSTEM)
            )
        )

        assertEquals(
            listOf(
                ConversationDisplayContentType.USER,
                ConversationDisplayContentType.ASSISTANT,
                ConversationDisplayContentType.PROCESS,
                ConversationDisplayContentType.PROCESS,
                ConversationDisplayContentType.SYSTEM
            ),
            entries.map(ConversationDisplayEntry::contentType)
        )
    }

    @Test
    fun `final assistant markdown is virtualized into stable timeline rows`() {
        val text = List(6) { index ->
            "第${index + 1}段：${"长回复内容".repeat(45)}"
        }.joinToString("\n\n")
        val timeline = makeConversationTimelineEntries(
            makeConversationDisplayEntries(
                listOf(item("assistant-42", ConversationItemKind.ASSISTANT, text = text))
            )
        )

        assertTrue(timeline.first() is ConversationTimelineEntry.AssistantMarkdown)
        assertTrue(timeline.last() is ConversationTimelineEntry.AssistantFooter)
        val markdownRows = timeline.filterIsInstance<ConversationTimelineEntry.AssistantMarkdown>()
        assertTrue(markdownRows.size > 1)
        assertEquals(
            markdownRows.indices.map { "assistant:assistant-42:markdown:$it" },
            markdownRows.map(ConversationTimelineEntry::id)
        )
        assertEquals(text, markdownRows.joinToString("\n\n", transform = { it.markdown }))
    }

    @Test
    fun `markdown virtualization never splits a fenced code block`() {
        val markdown = """
            开始

            ```kotlin
            val first = 1

            val second = 2
            ```

            结束段落
        """.trimIndent()

        val blocks = splitMarkdownForLazyLayout(markdown, targetCharacters = 20)

        assertTrue(blocks.size > 1)
        val codeBlock = blocks.single { it.contains("```kotlin") }
        assertTrue(codeBlock.contains("val first = 1\n\nval second = 2"))
        assertTrue(codeBlock.endsWith("```"))
    }

    @Test
    fun `markdown blocks reuse unchanged history and separate layout targets`() {
        val cache = MarkdownLayoutBlocksCache()
        val markdown = "第一段内容\n\n第二段内容\n\n第三段内容"
        val small = cache.getOrPut(markdown, 8)
        assertSame(small, cache.getOrPut(markdown, 8))
        val large = cache.getOrPut(markdown, 100)
        assertEquals(3, small.size)
        assertEquals(listOf(markdown), large)
        assertEquals(markdown, small.joinToString("\n\n"))
        cache.clear()
        assertNotSame(small, cache.getOrPut(markdown, 8))
    }

    @Test
    fun `markdown block cache evicts least recently used within both budgets`() {
        val cache = MarkdownLayoutBlocksCache(maxSourceCharacters = 30, maxEntries = 2)
        val first = cache.getOrPut("first\n\nreply", 5)
        val second = cache.getOrPut("second\n\nreply", 5)
        assertSame(first, cache.getOrPut("first\n\nreply", 5))
        cache.getOrPut("third\n\nreply", 5)
        assertSame(first, cache.getOrPut("first\n\nreply", 5))
        assertNotSame(second, cache.getOrPut("second\n\nreply", 5))

        val characterBound = MarkdownLayoutBlocksCache(maxSourceCharacters = 15, maxEntries = 20)
        val retained = characterBound.getOrPut("one\n\ntwo", 3)
        characterBound.getOrPut("x".repeat(100), 3)
        assertSame("单条超预算不能挤掉已预热窗口", retained, characterBound.getOrPut("one\n\ntwo", 3))
        characterBound.getOrPut("new\n\nreply", 3)
        assertNotSame(retained, characterBound.getOrPut("one\n\ntwo", 3))
    }

    @Test
    fun `slow splitting does not block hits and cannot refill after memory trim`() {
        val started = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val slowComputations = AtomicInteger()
        val cache = MarkdownLayoutBlocksCache { markdown, _ ->
            if (markdown == "slow" && slowComputations.incrementAndGet() == 1) {
                started.countDown()
                check(resume.await(5, TimeUnit.SECONDS))
            }
            listOf(markdown)
        }
        val workers = Executors.newFixedThreadPool(2)
        try {
            val cached = cache.getOrPut("cached", 320)
            val pending = workers.submit<List<String>> { cache.getOrPut("slow", 320) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val hit = workers.submit<List<String>> { cache.getOrPut("cached", 320) }
            assertSame("慢分块期间命中不能等待分块完成", cached, hit.get(2, TimeUnit.SECONDS))
            cache.clear()
            resume.countDown()
            val stale = pending.get(5, TimeUnit.SECONDS)
            assertNotSame("清理后的缓存不能接受迟到计算", stale, cache.getOrPut("slow", 320))
            assertEquals(2, slowComputations.get())
        } finally {
            resume.countDown()
            workers.shutdownNow()
        }
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
