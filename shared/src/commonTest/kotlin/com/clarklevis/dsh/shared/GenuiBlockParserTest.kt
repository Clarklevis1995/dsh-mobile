package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.facade.SharedConversationPatch
import com.clarklevis.dsh.shared.facade.SharedConversationPatchGuard
import com.clarklevis.dsh.shared.facade.SharedConversationStore
import com.clarklevis.dsh.shared.facade.SharedMviEvent
import com.clarklevis.dsh.shared.facade.SharedMviEventObserver
import com.clarklevis.dsh.shared.projection.ConversationProjector
import com.clarklevis.dsh.shared.projection.ConversationSegment
import com.clarklevis.dsh.shared.projection.GenuiAction
import com.clarklevis.dsh.shared.projection.GenuiActionCodec
import com.clarklevis.dsh.shared.projection.GenuiActionEffect
import com.clarklevis.dsh.shared.projection.GenuiActionIntent
import com.clarklevis.dsh.shared.projection.GenuiActionType
import com.clarklevis.dsh.shared.projection.GenuiBlockParser
import com.clarklevis.dsh.shared.projection.GenuiSegment
import com.clarklevis.dsh.shared.projection.GenuiSegmentState
import com.clarklevis.dsh.shared.projection.MarkdownSegment
import com.clarklevis.dsh.shared.protocol.GatewayEvent
import com.clarklevis.dsh.shared.protocol.SessionEvent
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GenuiBlockParserTest {
    @Test
    fun parsesOfficialChatFenceSpecsIntoValidatedStandaloneArtifacts() {
        val officialSpecs = listOf(
            """{"title":"KPI","items":[{"type":"grid","cols":2,"items":[{"type":"stat","label":"Revenue","value":"1.2M","delta":"+12.4%","spark":[3,5,4,8,6]},{"type":"progress","label":"Target","value":78,"valueLabel":"78%"}]}]}""",
            """{"items":[{"type":"table","columns":["Option","Cost","Risk"],"rows":[["A","High","-5%"],["B","Low","+12%"]],"total":false}]}""",
            """{"items":[{"type":"echart","title":"Trend","height":280,"preset":"line","data":[{"label":"Q1","value":30},{"label":"Q2","value":45}]}]}""",
            """{"items":[{"type":"choices","title":"Deploy","prompt":"Select target","mode":"multi","max":2,"submit":"Confirm","action":"deploy-select","items":[{"label":"Staging","hint":"Fast","recommended":true},{"label":"Prod","hint":"Safe"}]},{"type":"input","id":"note","label":"Note","placeholder":"Optional"},{"type":"submit","label":"Submit","action":"submit-form"}]}"""
        )

        officialSpecs.forEachIndexed { index, spec ->
            val markdown = "Intro $index\n```dsh-ui\n$spec\n```\nOutro"
            val segments = GenuiBlockParser.parse("msg-$index", markdown, isFinal = true)
            assertNotNull(segments, "Official spec #$index must parse")
            assertEquals(3, segments.size)
            val block = assertIs<GenuiSegment>(segments[1])
            assertEquals(GenuiSegmentState.READY, block.state)
            assertTrue(GenuiBlockParser.isValidArtifact(block.rawJson))
            assertTrue(block.rawJson.contains("\"format\":\"dsh-genui\""))
            assertTrue(block.rawJson.contains("\"version\":1"))
        }

        // Tolerant official fence tags per SKILL.md (`dsh-ui>`, `DSH-UI`, `json dsh-ui`)
        val spec = """{"items":[{"type":"text","content":"Hello"}]}"""
        for (fenceTag in listOf("dsh-ui>", "DSH-UI", "json dsh-ui", "genui")) {
            val block = assertIs<GenuiSegment>(
                GenuiBlockParser.parse("m", "```$fenceTag\n$spec\n```", isFinal = true)!!.single()
            )
            assertEquals(GenuiSegmentState.READY, block.state, "Tag '$fenceTag' should render READY")
        }
    }

    @Test
    fun parsesSingleBlockAndPreservesSurroundingMarkdown() {
        val markdown = "Before\n```dsh-ui\n${artifact()}\n```\nAfter"
        val segments = GenuiBlockParser.parse("message-1", markdown)!!

        assertEquals(3, segments.size)
        assertEquals("Before\n", assertIs<MarkdownSegment>(segments[0]).text)
        val block = assertIs<GenuiSegment>(segments[1])
        assertEquals("message-1-genui-0", block.id)
        assertEquals(GenuiSegmentState.READY, block.state)
        assertEquals(artifact(), block.rawJson)
        assertTrue(block.fallbackMarkdown.startsWith("```dsh-ui"))
        assertEquals("After", assertIs<MarkdownSegment>(segments[2]).text)
    }

    @Test
    fun parsesConsecutiveBacktickAndTildeBlocks() {
        val markdown = "```genui\n${artifact()}\n```\n~~~dsh-ui\n${artifact()}\n~~~"
        val segments = GenuiBlockParser.parse("m", markdown)!!

        assertEquals(2, segments.size)
        assertEquals(listOf("m-genui-0", "m-genui-1"), segments.filterIsInstance<GenuiSegment>().map { it.id })
        assertTrue(segments.all { it is GenuiSegment && it.state == GenuiSegmentState.READY })
    }

    @Test
    fun unclosedFenceIsStreamingWhileLiveAndDegradesToInvalidWhenFinalOrAborted() {
        val open = "```dsh-ui\n{\"items\":[{\"type\":\"text\",\"content\":\"partial"
        val streaming = assertIs<GenuiSegment>(GenuiBlockParser.parse("m", open, isFinal = false)!!.single())
        assertEquals(GenuiSegmentState.STREAMING, streaming.state)
        assertEquals("", streaming.rawJson)

        val finalDegraded = assertIs<GenuiSegment>(GenuiBlockParser.parse("m", open, isFinal = true)!!.single())
        assertEquals(GenuiSegmentState.INVALID, finalDegraded.state)
        assertEquals("", finalDegraded.rawJson)
        assertEquals(open, finalDegraded.fallbackMarkdown)

        // 1) Final-only assistant/message with truncated fence
        val store = SharedConversationStore()
        val events = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(events::add))
        assertTrue(store.receiveEvent(wireJson.encodeToString(
            SessionEvent("s1", 1, 1.0, GatewayEvent("assistant/message", text = open))
        )).accepted)
        val finalOnlyPatch = wireJson.decodeFromString<SharedConversationPatch>(events.last().statePayloadJson!!)
        assertEquals(GenuiSegmentState.INVALID, assertIs<GenuiSegment>(finalOnlyPatch.operations.single().item!!.segments!!.single()).state)

        // 2) Streaming -> final transition with truncated fence
        assertTrue(store.receiveEvent(wireJson.encodeToString(
            SessionEvent("s1", 2, 2.0, GatewayEvent("assistant/chunk", turn = 2, step = 1, chunkType = "text-delta", text = open))
        )).accepted)
        assertEquals(
            GenuiSegmentState.STREAMING,
            assertIs<GenuiSegment>(wireJson.decodeFromString<SharedConversationPatch>(events.last().statePayloadJson!!).operations.single().item!!.segments!!.single()).state
        )
        assertTrue(store.receiveEvent(wireJson.encodeToString(
            SessionEvent("s1", 3, 3.0, GatewayEvent("assistant/message", turn = 2, step = 1, text = open))
        )).accepted)
        assertEquals(
            GenuiSegmentState.INVALID,
            assertIs<GenuiSegment>(wireJson.decodeFromString<SharedConversationPatch>(events.last().statePayloadJson!!).operations.single().item!!.segments!!.single()).state
        )

        // 3) History reload via replaceSession
        val history = listOf(SessionEvent("s2", 1, 1.0, GatewayEvent("assistant/message", text = open)))
        assertTrue(store.replaceSession("s2", wireJson.encodeToString(history)).accepted)
        val reloaded = wireJson.decodeFromString<SharedConversationPatch>(events.last().statePayloadJson!!)
        assertEquals(GenuiSegmentState.INVALID, assertIs<GenuiSegment>(reloaded.replacementItems!!.single().segments!!.single()).state)

        // 4) Streaming aborted via turn/end
        assertTrue(store.receiveEvent(wireJson.encodeToString(
            SessionEvent("s3", 1, 1.0, GatewayEvent("assistant/chunk", turn = 1, step = 1, chunkType = "text-delta", text = open))
        )).accepted)
        assertTrue(store.receiveEvent(wireJson.encodeToString(
            SessionEvent("s3", 2, 2.0, GatewayEvent("turn/end", turn = 1, reason = "aborted"))
        )).accepted)
        val abortedPatch = wireJson.decodeFromString<SharedConversationPatch>(events.last().statePayloadJson!!)
        val finalized = abortedPatch.operations.first { it.kind == "replace" }.item!!
        assertEquals(GenuiSegmentState.INVALID, assertIs<GenuiSegment>(finalized.segments!!.single()).state)
    }

    @Test
    fun invalidJsonAndEmptyJsonFallBackToOriginalFence() {
        for (body in listOf("{broken", "", "null", "[]")) {
            val markdown = "```dsh-ui\n$body\n```"
            val block = assertIs<GenuiSegment>(GenuiBlockParser.parse("m", markdown)!!.single())
            assertEquals(GenuiSegmentState.INVALID, block.state)
            assertEquals("", block.rawJson)
            assertEquals(markdown, block.fallbackMarkdown)
        }
    }

    @Test
    fun ignoresCandidateFencesInsideOtherCodeFences() {
        val markdown = "~~~markdown\n```dsh-ui\nnot-json\n```\n~~~"
        assertNull(GenuiBlockParser.parse("m", markdown))
        assertNull(GenuiBlockParser.parse("m", "    ```dsh-ui\n{}\n    ```"))
        assertNull(GenuiBlockParser.parse("m", "ordinary prose only"))
    }

    @Test
    fun closingFenceMustMatchCharacterAndLength() {
        val longer = "````dsh-ui\n${artifact()}\n````"
        assertEquals(GenuiSegmentState.READY, assertIs<GenuiSegment>(GenuiBlockParser.parse("m", longer)!!.single()).state)
        val shorter = assertIs<GenuiSegment>(GenuiBlockParser.parse("m", "````dsh-ui\n${artifact()}\n```")!!.single())
        assertEquals(GenuiSegmentState.STREAMING, shorter.state)
        val wrongCharacter = assertIs<GenuiSegment>(GenuiBlockParser.parse("m", "```dsh-ui\n${artifact()}\n~~~")!!.single())
        assertEquals(GenuiSegmentState.STREAMING, wrongCharacter.state)
    }

    @Test
    fun rejectsMalformedStateStringVersionBadContainersAndUnbundledEngines() {
        // Invalid envelope fields
        assertFalse(GenuiBlockParser.isValidArtifact(artifact().replace("\"dsh-genui\"", "\"other\"")))
        assertFalse(GenuiBlockParser.isValidArtifact(artifact().replace("\"version\":1", "\"version\":2")))
        assertFalse(GenuiBlockParser.isValidArtifact(artifact().replace("\"version\":1", "\"version\":\"1\"")))
        assertFalse(GenuiBlockParser.isValidPayload(artifact().replace("\"dsh-genui\"", "\"other\"")))

        // Malformed state (array, null, string boolean)
        assertFalse(GenuiBlockParser.isValidArtifact(artifact().replace("\"state\":{}", "\"state\":[]")))
        assertFalse(GenuiBlockParser.isValidArtifact(artifact().replace("\"state\":{}", "\"state\":null")))
        assertFalse(GenuiBlockParser.isValidArtifact(artifact().replace("\"state\":{}", "\"state\":{\"locked\":\"true\"}")))
        assertFalse(GenuiBlockParser.isValidPayload("""{"items":[{"type":"text","content":"Hi"}],"state":[]}"""))

        // Bad container items (non-array)
        assertFalse(GenuiBlockParser.isValidPayload("""{"items":[{"type":"card","items":{}}]}"""))
        assertFalse(GenuiBlockParser.isValidPayload("""{"items":[{"type":"row","items":"bad"}]}"""))
        assertFalse(GenuiBlockParser.isValidPayload("""{"items":[{"type":"tabs","tabs":[{"label":"A","items":"bad"}]}]}"""))
        assertFalse(GenuiBlockParser.isValidPayload("""{"items":[{"type":"accordion","items":[{"title":"A","items":{}}]}]}"""))
        assertFalse(GenuiBlockParser.isValidPayload("""{"items":[{"type":"list","items":{}}]}"""))
        assertFalse(GenuiBlockParser.isValidPayload("""{"items":[{"type":"table","columns":["A"],"rows":[["1"]],"details":{}}]}"""))

        // Empty tab omitting items is valid per SKILL.md
        assertTrue(GenuiBlockParser.isValidPayload("""{"items":[{"type":"tabs","tabs":[{"label":"Empty"}]}]}"""))

        // Unknown / unbundled engine types fail closed
        assertFalse(GenuiBlockParser.isValidArtifact(artifact().replace("\"type\":\"text\"", "\"type\":\"script\"")))
        assertFalse(GenuiBlockParser.isValidPayload("""{"items":[{"type":"mermaid","code":"graph TD\nA-->B"}]}"""))
        assertFalse(GenuiBlockParser.isValidPayload("""{"items":[{"type":"scene3d","meshes":[{"shape":"box"}]}]}"""))
    }

    @Test
    fun oversizedClosedAndUnclosedBlocksDegradeCleanlyWithoutBreakingSubsequentMessages() {
        val store = SharedConversationStore()
        val received = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(received::add))

        val hugeSpec = """{"items":[{"type":"text","content":"${"x".repeat(512_001)}"}]}"""
        val hugeClosedFence = "```dsh-ui\n$hugeSpec\n```"
        assertTrue(store.receiveEvent(wireJson.encodeToString(
            SessionEvent("s1", 1, 1.0, GatewayEvent("assistant/message", text = hugeClosedFence))
        )).accepted)
        val closedPayload = received.last().statePayloadJson!!
        assertTrue(SharedConversationPatchGuard.validate(closedPayload))
        val closedPatch = wireJson.decodeFromString<SharedConversationPatch>(closedPayload)
        val closedBlock = assertIs<GenuiSegment>(closedPatch.operations.single().item!!.segments!!.single())
        assertEquals(GenuiSegmentState.INVALID, closedBlock.state)
        assertEquals("", closedBlock.rawJson)
        assertEquals("", closedBlock.fallbackMarkdown)
        assertEquals(hugeClosedFence, GenuiBlockParser.fallbackMarkdown(closedBlock, closedPatch.operations.single().item!!.text))

        // Unclosed oversized chunk also stays within wire bounds
        val hugeUnclosed = "```dsh-ui\n$hugeSpec"
        assertTrue(store.receiveEvent(wireJson.encodeToString(
            SessionEvent("s1", 2, 2.0, GatewayEvent("assistant/chunk", turn = 2, step = 1, chunkType = "text-delta", text = hugeUnclosed))
        )).accepted)
        val streamingPayload = received.last().statePayloadJson!!
        assertTrue(SharedConversationPatchGuard.validate(streamingPayload))
        val streamingBlock = assertIs<GenuiSegment>(
            wireJson.decodeFromString<SharedConversationPatch>(streamingPayload).operations.single().item!!.segments!!.single()
        )
        assertEquals(GenuiSegmentState.STREAMING, streamingBlock.state)
        assertEquals("", streamingBlock.rawJson)

        // Subsequent normal message in the same session still succeeds
        assertTrue(store.receiveEvent(wireJson.encodeToString(
            SessionEvent("s1", 3, 3.0, GatewayEvent("assistant/message", text = "Normal message after oversized block"))
        )).accepted)
        val followUp = wireJson.decodeFromString<SharedConversationPatch>(received.last().statePayloadJson!!)
        assertEquals("Normal message after oversized block", followUp.operations.single().item!!.text)
    }

    @Test
    fun rejectsDeepAndExcessivePayloadsWithoutThrowing() {
        val deep = "[".repeat(GenuiBlockParser.MAX_JSON_DEPTH + 1) + "0" + "]".repeat(GenuiBlockParser.MAX_JSON_DEPTH + 1)
        assertEquals(GenuiSegmentState.INVALID, blockState(deep))
        assertNull(GenuiBlockParser.parse("m", "x".repeat(GenuiBlockParser.MAX_MARKDOWN_CHARS + 1)))
        assertNull(GenuiBlockParser.parse("m", List(GenuiBlockParser.MAX_BLOCKS + 1) { "```dsh-ui\n{}\n```\n" }.joinToString("")))
    }

    @Test
    fun parsesCrlfAndPreservesFenceLineEndings() {
        val markdown = "Before\r\n````dsh-ui\r\n${artifact()}\r\n````\r\nAfter"
        val segments = GenuiBlockParser.parse("m", markdown)!!

        assertEquals("Before\r\n", assertIs<MarkdownSegment>(segments[0]).text)
        val block = assertIs<GenuiSegment>(segments[1])
        assertEquals(GenuiSegmentState.READY, block.state)
        assertEquals(artifact(), block.rawJson)
        assertTrue(block.fallbackMarkdown.startsWith("````dsh-ui\r\n"))
        assertTrue(block.fallbackMarkdown.endsWith("\r\n````\r\n"))
        assertEquals("After", assertIs<MarkdownSegment>(segments[2]).text)
    }

    @Test
    fun incrementalAppendHandlesEofClosingFenceAndFollowingMarkdown() {
        val open = "```dsh-ui\r\n${artifact()}"
        val parsed = GenuiBlockParser.parse("m", open)!!
        val streaming = GenuiBlockParser.parseAppend("m", open, "", parsed)!!
        assertSame(parsed, streaming)

        val payloadDelta = " "
        assertSame(
            streaming,
            GenuiBlockParser.parseAppend("m", open, payloadDelta, streaming)
        )
        val ready = GenuiBlockParser.parseAppend("m", open + payloadDelta, "\r\n```", streaming)!!
        assertEquals(GenuiSegmentState.READY, assertIs<GenuiSegment>(ready.single()).state)
        assertFalse(assertIs<GenuiSegment>(ready.single()).fallbackMarkdown.endsWith("\n"))

        val withTail = GenuiBlockParser.parseAppend(
            "m", open + payloadDelta + "\r\n```", "\r\nAfter", ready
        )!!
        assertEquals("After", assertIs<MarkdownSegment>(withTail.last()).text)
        val extended = GenuiBlockParser.parseAppend(
            "m", open + payloadDelta + "\r\n```\r\nAfter", " later", withTail
        )!!
        assertEquals("After later", assertIs<MarkdownSegment>(extended.last()).text)
    }

    @Test
    fun trailingTokensAfterLargeCardEmitDeltaOnlyPatchesWithoutResendingCardJson() {
        val store = SharedConversationStore()
        val received = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(received::add))

        val largeSpec = """{"title":"BigCard","items":[{"type":"text","content":"${"A".repeat(40_000)}"}]}"""
        val cardFence = "```dsh-ui\n$largeSpec\n```\nStart"
        assertTrue(store.receiveEvent(wireJson.encodeToString(event(1, cardFence))).accepted)

        for (step in 2..101) {
            assertTrue(store.receiveEvent(wireJson.encodeToString(event(step, " t$step"))).accepted)
            val payload = received.last().statePayloadJson!!
            assertTrue(SharedConversationPatchGuard.validate(payload))
            assertTrue(payload.length < 256, "Token patch #$step unexpectedly large: ${payload.length} bytes")
            assertFalse("BigCard" in payload, "Completed card JSON must not be resent on trailing text token")
            val decoded = wireJson.decodeFromString<SharedConversationPatch>(payload)
            assertNull(decoded.operations.single().segments)
            assertNull(decoded.operations.single().segmentChanges)
        }

        // Opening and closing a second small card emits segmentChanges only for the new tail, not block 0
        assertTrue(store.receiveEvent(wireJson.encodeToString(
            event(102, "\n```dsh-ui\n{\"items\":[{\"type\":\"text\",\"content\":\"Second\"}]}\n```")
        )).accepted)
        val secondCardPayload = received.last().statePayloadJson!!
        assertTrue(SharedConversationPatchGuard.validate(secondCardPayload))
        assertFalse("BigCard" in secondCardPayload, "Block 0 must not be resent when block 1 appears")
        val secondPatch = wireJson.decodeFromString<SharedConversationPatch>(secondCardPayload)
        assertNull(secondPatch.operations.single().segments)
        assertNotNull(secondPatch.operations.single().segmentChanges)
    }

    @Test
    fun finalOnlyMessagesKeepUniqueSequenceIdsAndStreamedFinalKeepsSourceAlias() {
        val projector = ConversationProjector()

        // Consecutive unindexed final-only messages
        projector.fold(listOf(
            SessionEvent("s1", 2, 2.0, GatewayEvent("assistant/message", text = "First unindexed")),
            SessionEvent("s1", 4, 4.0, GatewayEvent("assistant/message", text = "Second unindexed")),
            // Same turn/step, different seq
            SessionEvent("s1", 6, 6.0, GatewayEvent("assistant/message", turn = 1, step = 1, text = "Same index A")),
            SessionEvent("s1", 8, 8.0, GatewayEvent("assistant/message", turn = 1, step = 1, text = "Same index B"))
        ))
        assertEquals(listOf("s1-2", "s1-4", "s1-6", "s1-8"), projector.items.map { it.id })

        // Streamed -> final keeps stream ID in place AND records sourceEventId for search
        val streamedProjector = ConversationProjector()
        streamedProjector.fold(listOf(
            SessionEvent("s1", 9, 9.0, GatewayEvent("assistant/chunk", turn = 2, step = 1, chunkType = "text-delta", text = "Hel")),
            SessionEvent("s1", 10, 10.0, GatewayEvent("assistant/message", turn = 2, step = 1, text = "Hello"))
        ))
        val finalized = streamedProjector.items.single()
        assertEquals("stream-text-2-1", finalized.id)
        assertEquals("s1-10", finalized.sourceEventId)

        // Earlier history arriving preserves final-only ID and GenUI block ID
        val finalEvent = SessionEvent(
            "s1", 3, 3.0,
            GatewayEvent(type = "assistant/message", turn = 1, step = 1, text = "```dsh-ui\n${artifact()}\n```")
        )
        val olderEvent = SessionEvent("s1", 2, 2.0, GatewayEvent(type = "user/message", text = "Question"))
        val withoutHistory = ConversationProjector().apply { fold(listOf(finalEvent)) }.items.single()
        val withHistory = ConversationProjector().apply { fold(listOf(olderEvent, finalEvent)) }.items.last()
        assertEquals("s1-3", withoutHistory.id)
        assertEquals(withoutHistory.id, withHistory.id)
        assertEquals("s1-3-genui-0", withHistory.segments!!.filterIsInstance<GenuiSegment>().single().id)
    }

    @Test
    fun consecutiveStreamingBlocksTransitionIndependently() {
        val first = "```dsh-ui\n${artifact()}\n```\n"
        val open = first + "```genui\n${artifact()}"
        val streaming = GenuiBlockParser.parse("m", open)!!
        assertEquals(
            listOf(GenuiSegmentState.READY, GenuiSegmentState.STREAMING),
            streaming.filterIsInstance<GenuiSegment>().map { it.state }
        )

        val ready = GenuiBlockParser.parseAppend("m", open, "\n```", streaming)!!
        assertEquals(
            listOf("m-genui-0", "m-genui-1"),
            ready.filterIsInstance<GenuiSegment>().map { it.id }
        )
        assertTrue(ready.filterIsInstance<GenuiSegment>().all { it.state == GenuiSegmentState.READY })
    }

    @Test
    fun nestedListTableTabsAndAccordionNodesAreValidated() {
        assertTrue(GenuiBlockParser.isValidArtifact(artifactWithItem(
            """{"type":"list","items":["plain",{"title":"Record","desc":"Description"},{"type":"card","items":[{"type":"text","content":"Nested"}]}]}"""
        )))
        assertFalse(GenuiBlockParser.isValidArtifact(artifactWithItem(
            """{"type":"list","items":[{"type":"script","content":"bad"}]}"""
        )))
        assertTrue(GenuiBlockParser.isValidArtifact(artifactWithItem(
            """{"type":"table","columns":["Name"],"rows":[["Ada"]],"details":[null,[{"type":"text","content":"Detail"}]]}"""
        )))
        assertFalse(GenuiBlockParser.isValidArtifact(artifactWithItem(
            """{"type":"table","columns":["Name"],"rows":[["Ada"]],"details":[[{"type":"script","content":"bad"}]]}"""
        )))
        assertTrue(GenuiBlockParser.isValidArtifact(artifactWithItem(
            """{"type":"tabs","tabs":[{"title":"Tab","items":[{"type":"text","content":"Tab body"}]}]}"""
        )))
        assertTrue(GenuiBlockParser.isValidArtifact(artifactWithItem(
            """{"type":"accordion","items":[{"title":"Section","items":[{"type":"text","content":"Section body"}]}]}"""
        )))
    }

    @Test
    fun segmentSerializationPreservesPolymorphicShape() {
        val segments = GenuiBlockParser.parse("m", "Hello\n```dsh-ui\n${artifact()}\n```")!!
        val encoded = wireJson.encodeToString<List<ConversationSegment>>(segments)
        val decoded = wireJson.decodeFromString<List<ConversationSegment>>(encoded)
        assertEquals(segments, decoded)
        assertTrue(encoded.contains("\"type\":\"markdown\""))
        assertTrue(encoded.contains("\"type\":\"genui\""))
    }

    @Test
    fun projectorPublishesIncrementalSegmentChangesWhenStreamingShapeChanges() {
        val projector = ConversationProjector()
        val open = "```dsh-ui\n${artifact()}"
        projector.foldWithOperations(listOf(event(1, open)))
        val streamingId = projector.items.single().id
        assertEquals(GenuiSegmentState.STREAMING, assertIs<GenuiSegment>(projector.items.single().segments!!.single()).state)

        val append = projector.foldWithOperations(listOf(event(2, "\n```"))).single()
        assertEquals("append-text", append.kind)
        assertNull(append.segments)
        assertEquals(GenuiSegmentState.READY, assertIs<GenuiSegment>(append.segmentChanges!!.single().segment).state)

        val final = projector.foldWithOperations(listOf(SessionEvent(
            "s1", 3, 3.0,
            GatewayEvent(type = "assistant/message", turn = 1, step = 1, text = open + "\n```")
        ))).single()
        assertEquals("replace", final.kind)
        assertEquals(streamingId, final.item!!.id)
        assertEquals("s1-3", final.item.sourceEventId)
        assertEquals("$streamingId-genui-0", assertIs<GenuiSegment>(final.item.segments!!.single()).id)
    }

    @Test
    fun projectorClearsSegmentsWhenSeventeenthBlockExceedsBudget() {
        val projector = ConversationProjector()
        val blocks = List(GenuiBlockParser.MAX_BLOCKS) { "```dsh-ui\n${artifact()}\n```\n" }.joinToString("")
        projector.foldWithOperations(listOf(event(1, blocks)))
        assertTrue(projector.items.single().segments!!.filterIsInstance<GenuiSegment>().isNotEmpty())

        val operation = projector.foldWithOperations(
            listOf(event(2, "```dsh-ui\n{}\n```\n"))
        ).single()
        assertEquals("replace", operation.kind)
        assertEquals(null, operation.item!!.segments)
        assertEquals(null, projector.items.single().segments)
    }

    @Test
    fun actionCodecPreservesFidelityForChoicesToggleSliderButtonAndSameNamedFormFields() {
        val choices = action(
            """{"version":1,"action":"choose-plan","payload":{"type":"choices","question":"Plan?","selected":["Pro","Team"],"mode":"multi"}}"""
        )!!
        assertEquals(GenuiActionType.SEND_MESSAGE, choices.type)
        assertEquals("choose-plan", choices.action)
        assertTrue(choices.text!!.startsWith("[genui-action] choose-plan "))
        assertTrue(choices.text.contains("\"selected\":[\"Pro\",\"Team\"]"))
        assertTrue(choices.text.contains("\"mode\":\"multi\""))

        val toggle = action(
            """{"version":1,"action":"toggle-alerts","payload":{"type":"switch","checked":true}}"""
        )!!
        assertTrue(toggle.text!!.contains("toggle-alerts"))
        assertTrue(toggle.text.contains("\"checked\":true"))

        val slider = action(
            """{"version":1,"action":"set-temp","payload":{"type":"slider","value":0.7,"id":"temperature"}}"""
        )!!
        assertTrue(slider.text!!.contains("set-temp"))
        assertTrue(slider.text.contains("\"value\":0.7"))
        assertTrue(slider.text.contains("\"id\":\"temperature\""))

        val button = action(
            """{"version":1,"action":"continue-flow","payload":{"type":"button","label":"Continue"}}"""
        )!!
        assertTrue(button.text!!.contains("continue-flow"))
        assertTrue(button.text.contains("\"label\":\"Continue\""))

        // Same-named keys across answers and fields must NOT overwrite each other
        val submit = action(
            """{"version":1,"action":"submit-quiz","payload":{"type":"submit","answers":{"topic":"AnswerValue"},"fields":{"topic":"FieldValue","tags":["a","b"]}}}"""
        )!!
        assertTrue(submit.text!!.contains("\"answers\":{\"topic\":\"AnswerValue\"}"))
        assertTrue(submit.text.contains("\"fields\":{\"topic\":\"FieldValue\",\"tags\":[\"a\",\"b\"]}"))

        val copy = action("""{"version":1,"action":"copy","payload":{"text":"copy me"}}""")!!
        assertEquals(GenuiActionType.COPY_TEXT, copy.type)
        assertEquals("copy me", copy.text)

        val expand = action("""{"version":1,"action":"expand","payload":{"details":{"count":2}}}""")!!
        assertEquals(GenuiActionType.EXPAND_DETAILS, expand.type)
        assertEquals("{\"count\":2}", expand.details)
    }

    @Test
    fun actionCodecFailsClosedAndPreventsSlashCommandInjection() {
        assertNull(action("not-json"))
        assertNull(action("""{"version":2,"action":"send","payload":{}}"""))
        assertNull(action("""{"version":"1","action":"send","payload":{}}"""))
        assertNull(action("""{"version":1,"action":"","payload":{}}"""))
        assertNull(action("""{"version":1,"action":"line1\nline2","payload":{}}"""))
        assertNull(action("""{"version":1,"action":"copy","payload":{}}"""))
        assertNull(action("""{"version":1,"action":"copy"}"""))
        assertNull(action("x".repeat(GenuiActionCodec.MAX_ACTION_CHARS + 1)))
        val nested = "[".repeat(40) + "0" + "]".repeat(40)
        assertNull(action("""{"version":1,"action":"send","payload":{"value":$nested}}"""))
        val tooLargePayload = """{"version":1,"action":"send","payload":{"text":"${"x".repeat(GenuiActionCodec.MAX_PAYLOAD_CHARS)}"}}"""
        assertNull(action(tooLargePayload))

        val slashAction = action("""{"version":1,"action":"/compact","payload":{"text":"/compact"}}""")!!
        assertFalse(slashAction.text!!.trimStart().startsWith("/"))
        assertTrue(slashAction.text.startsWith("[genui-action] /compact "))
        assertEquals(slashAction, wireJson.decodeFromString<GenuiAction>(GenuiActionCodec.encode(slashAction)))
    }

    @Test
    fun sharedConversationStoreValidatesGenuiActionIntentAndThrowableBoundary() {
        val store = SharedConversationStore()
        val events = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(events::add))
        val readySpec = """{"items":[{"type":"button","label":"Run","action":"run-check"}]}"""
        assertTrue(store.receiveEvent(wireJson.encodeToString(
            SessionEvent("s1", 1, 1.0, GatewayEvent("assistant/message", text = "```dsh-ui\n$readySpec\n```"))
        )).accepted)
        assertTrue(store.receiveEvent(wireJson.encodeToString(
            SessionEvent("s1", 2, 2.0, GatewayEvent("assistant/chunk", turn = 2, step = 1, chunkType = "text-delta", text = "```dsh-ui\n{\"items\":["))
        )).accepted)
        // Closed card inside a live (non-final) chunk is preview-only (`interactive == false`)
        assertTrue(store.receiveEvent(wireJson.encodeToString(
            SessionEvent("s1", 3, 3.0, GatewayEvent("assistant/chunk", turn = 3, step = 1, chunkType = "text-delta", text = "```dsh-ui\n$readySpec\n```"))
        )).accepted)

        val validActionJson = """{"version":1,"action":"run-check","payload":{"type":"button","label":"Run"}}"""
        val readySegment = wireJson.decodeFromString<SharedConversationPatch>(events[1].statePayloadJson!!)
            .operations.single().item!!.segments!!.filterIsInstance<GenuiSegment>().single()
        val readyVersion = readySegment.contentVersion
        assertEquals(64, readyVersion.length)
        assertTrue(readySegment.interactive)
        val previewSegment = wireJson.decodeFromString<SharedConversationPatch>(events[3].statePayloadJson!!)
            .operations.single().item!!.segments!!.filterIsInstance<GenuiSegment>().single()
        assertEquals(GenuiSegmentState.READY, previewSegment.state)
        assertFalse(previewSegment.interactive)

        val validIntent = GenuiActionIntent(
            sessionId = "s1",
            activeSessionId = "s1",
            blockId = "s1-1-genui-0",
            contentVersion = readyVersion,
            actionJson = validActionJson,
            isSearchContext = false
        )

        // Valid READY interactive card in active session -> SEND_MESSAGE effect
        val effect = store.resolveGenuiAction(validIntent)
        assertNotNull(effect)
        assertEquals(GenuiActionType.SEND_MESSAGE, effect.type)
        assertEquals("s1", effect.sessionId)
        assertEquals("s1-1-genui-0", effect.blockId)
        assertTrue(effect.text!!.contains("run-check"))

        // Preview-only READY card on a live streaming row rejects actions until finalized
        assertNull(store.resolveGenuiAction(validIntent.copy(blockId = previewSegment.id, contentVersion = previewSegment.contentVersion)))

        // JSON bridge round-trip for Kotlin/Native
        val encodedEffect = store.dispatchGenuiActionJson(wireJson.encodeToString(validIntent))
        assertNotNull(encodedEffect)
        assertEquals(effect, wireJson.decodeFromString<GenuiActionEffect>(encodedEffect))

        // Switched-away session -> null
        assertNull(store.resolveGenuiAction(validIntent.copy(activeSessionId = "s2")))
        // Stale / unknown card or stale content version -> null
        assertNull(store.resolveGenuiAction(validIntent.copy(blockId = "s1-999-genui-0")))
        assertNull(store.resolveGenuiAction(validIntent.copy(contentVersion = "0".repeat(64))))
        assertNull(store.resolveGenuiAction(validIntent.copy(contentVersion = "")))
        // Non-READY (STREAMING) card -> null
        assertNull(store.resolveGenuiAction(validIntent.copy(blockId = "stream-text-2-1-genui-0")))
        // Search context rejects SEND_MESSAGE
        assertNull(store.resolveGenuiAction(validIntent.copy(isSearchContext = true)))
        // Malformed JSON bridge input fails closed without throwing
        assertNull(store.dispatchGenuiActionJson("not-json"))
        assertNull(store.dispatchGenuiActionJson("x".repeat(30_000)))
    }

    @Test
    fun singleComponentRootsNormalizeForBareSpecAndWrappedArtifact() {
        val bareText = """{"type":"text","content":"Hello"}"""
        val bareSteps = """{"type":"steps","items":[{"title":"One"},{"title":"Two"}]}"""
        val wrappedSingle = """{"format":"dsh-genui","version":1,"spec":{"type":"text","content":"Hello wrapped"},"state":{},"presentation":{"locale":"zh","theme":"dark"}}"""
        for (body in listOf(bareText, bareSteps, wrappedSingle)) {
            val block = assertIs<GenuiSegment>(
                GenuiBlockParser.parse("m", "```dsh-ui\n$body\n```", isFinal = true)!!.single()
            )
            assertEquals(GenuiSegmentState.READY, block.state)
            assertTrue(block.interactive)
            assertTrue(GenuiBlockParser.isValidArtifact(block.rawJson))
            assertTrue(block.rawJson.contains("\"items\":["))
        }
        // Components without own `title` field (e.g., `steps`) hoist `title` to outer spec banner
        val titledSteps = """{"type":"steps","title":"Synthetic workflow title","items":[{"title":"First"}]}"""
        val wrappedTitledSteps = """{"format":"dsh-genui","version":1,"spec":$titledSteps,"state":{},"presentation":{"locale":"en","theme":"light"}}"""
        for (body in listOf(titledSteps, wrappedTitledSteps)) {
            val block = assertIs<GenuiSegment>(
                GenuiBlockParser.parse("m", "```dsh-ui\n$body\n```", isFinal = true)!!.single()
            )
            assertEquals(GenuiSegmentState.READY, block.state)
            val parsed = kotlinx.serialization.json.Json.parseToJsonElement(block.rawJson) as kotlinx.serialization.json.JsonObject
            val spec = parsed["spec"] as kotlinx.serialization.json.JsonObject
            assertEquals("Synthetic workflow title", (spec["title"] as? kotlinx.serialization.json.JsonPrimitive)?.content)
            val node = (spec["items"] as kotlinx.serialization.json.JsonArray).single() as kotlinx.serialization.json.JsonObject
            assertFalse(node.containsKey("title"), "Hoisted title must be removed from steps node")
        }
        // Components with own `title` field (e.g., `callout`) keep `title` on the component node
        val titledCallout = """{"type":"callout","tone":"info","title":"Heads up","content":"Watch out"}"""
        val calloutBlock = assertIs<GenuiSegment>(
            GenuiBlockParser.parse("m", "```dsh-ui\n$titledCallout\n```", isFinal = true)!!.single()
        )
        assertEquals(GenuiSegmentState.READY, calloutBlock.state)
        val calloutParsed = kotlinx.serialization.json.Json.parseToJsonElement(calloutBlock.rawJson) as kotlinx.serialization.json.JsonObject
        val calloutSpec = calloutParsed["spec"] as kotlinx.serialization.json.JsonObject
        assertFalse(calloutSpec.containsKey("title"), "Callout title must stay on callout node")
        val calloutNode = (calloutSpec["items"] as kotlinx.serialization.json.JsonArray).single() as kotlinx.serialization.json.JsonObject
        assertEquals("Heads up", (calloutNode["title"] as? kotlinx.serialization.json.JsonPrimitive)?.content)

        // Unbundled single-component root still fails closed
        val unsupported = assertIs<GenuiSegment>(
            GenuiBlockParser.parse("m", "```dsh-ui\n{\"type\":\"mermaid\",\"code\":\"graph TD\"}\n```", isFinal = true)!!.single()
        )
        assertEquals(GenuiSegmentState.INVALID, unsupported.state)
    }

    @Test
    fun wrappedArtifactBudgetAndLongPlainMarkdownNeverFailClosedAndRejectedEventPreservesWatermark() {
        val store = SharedConversationStore()
        val events = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(events::add))

        // Depth-63 nested metadata inside bare spec becomes depth 65 once wrapped in artifact -> must degrade to INVALID
        val nested63 = "[".repeat(63) + "0" + "]".repeat(63)
        val borderlineSpec = """{"items":[{"type":"text","content":"Hi"}],"metadata":$nested63}"""
        val borderlineFence = "```dsh-ui\n$borderlineSpec\n```"
        assertTrue(store.receiveEvent(wireJson.encodeToString(
            SessionEvent("s1", 1, 1.0, GatewayEvent("assistant/message", text = borderlineFence))
        )).accepted)
        val degradedPatch = wireJson.decodeFromString<SharedConversationPatch>(events.last().statePayloadJson!!)
        assertTrue(SharedConversationPatchGuard.validate(events.last().statePayloadJson!!))
        val degradedBlock = assertIs<GenuiSegment>(degradedPatch.operations.single().item!!.segments!!.single())
        assertEquals(GenuiSegmentState.INVALID, degradedBlock.state)
        assertEquals("", degradedBlock.rawJson)
        assertFalse(degradedBlock.interactive)

        // Plain Markdown exceeding GenUI parser budget (2,000,001 chars) must still be accepted without segments
        val longPlain = "a".repeat(GenuiBlockParser.MAX_MARKDOWN_CHARS + 1)
        assertTrue(store.receiveEvent(wireJson.encodeToString(
            SessionEvent("s1", 2, 2.0, GatewayEvent("assistant/message", text = longPlain))
        )).accepted)
        val longPatch = wireJson.decodeFromString<SharedConversationPatch>(events.last().statePayloadJson!!)
        assertNull(longPatch.operations.single().item!!.segments)
        assertEquals(longPlain.length, longPatch.operations.single().item!!.text.length)

        // Prepared history path also succeeds for both messages without invalid patches
        val preparedRecords = listOf(
            SessionEvent("s2", 1, 1.0, GatewayEvent("assistant/message", text = borderlineFence)),
            SessionEvent("s2", 2, 2.0, GatewayEvent("assistant/message", text = longPlain))
        )
        val prepSnapshot = com.clarklevis.dsh.shared.facade.SharedHistoryProjectionSnapshot("s2", 1L, preparedRecords)
        val prep = store.prepareHistorySnapshot("s2", prepSnapshot)!!
        val prepared = prep.prepare()
        assertTrue(store.commitPreparedHistory(prepared)!!.accepted)

        // Rejected event (e.g. item id exceeding 512 chars) rolls back projector and does not advance lastSequence, so retrying same seq succeeds
        val oversizedIdRaw = com.clarklevis.dsh.shared.protocol.JsonValue.ObjectValue(
            mapOf("id" to com.clarklevis.dsh.shared.protocol.JsonValue.StringValue("x".repeat(600)))
        )
        val badResult = store.receiveEvent(wireJson.encodeToString(
            SessionEvent("s1", 3, 3.0, GatewayEvent("user/message", text = "bad", raw = oversizedIdRaw))
        ))
        assertFalse(badResult.accepted)
        assertTrue(store.receiveEvent(wireJson.encodeToString(
            SessionEvent("s1", 3, 3.0, GatewayEvent("assistant/message", text = "retry seq 3 ok"))
        )).accepted)
    }

    @Test
    fun missingStepStreamsIsolateByTurnAndUnindexedStreamsFinalizeCleanly() {
        val projector = ConversationProjector()
        val open = "```dsh-ui\n{\"items\":[{\"type\":\"text\",\"content\":\"partial"
        // Turn 6 without step -> aborted via turn/end -> must degrade to INVALID and NOT merge turn 7
        projector.fold(listOf(
            SessionEvent("s1", 1, 1.0, GatewayEvent("assistant/chunk", turn = 6, step = null, chunkType = "text-delta", text = open)),
            SessionEvent("s1", 2, 2.0, GatewayEvent("turn/end", turn = 6, reason = "aborted")),
            SessionEvent("s1", 3, 3.0, GatewayEvent("assistant/chunk", turn = 7, step = null, chunkType = "text-delta", text = "Turn 7 reply"))
        ))
        val turn6Item = projector.items.first { it.kind == com.clarklevis.dsh.shared.projection.ConversationItemKind.ASSISTANT }
        assertEquals(GenuiSegmentState.INVALID, assertIs<GenuiSegment>(turn6Item.segments!!.single()).state)
        val turn7Item = projector.items.last { it.kind == com.clarklevis.dsh.shared.projection.ConversationItemKind.ASSISTANT }
        assertEquals("Turn 7 reply", turn7Item.text)
        assertFalse(turn6Item.id == turn7Item.id)

        // Completely unindexed chunks (turn=null, step=null) merge within one reply and finalize cleanly
        val unindexed = ConversationProjector()
        unindexed.fold(listOf(
            SessionEvent("s1", 1, 1.0, GatewayEvent("assistant/chunk", chunkType = "text-delta", text = "```dsh-ui\n")),
            SessionEvent("s1", 2, 2.0, GatewayEvent("assistant/chunk", chunkType = "text-delta", text = "{\"items\":[")),
            SessionEvent("s1", 3, 3.0, GatewayEvent("turn/end", reason = "aborted")),
            SessionEvent("s1", 4, 4.0, GatewayEvent("assistant/chunk", chunkType = "text-delta", text = "Next unindexed"))
        ))
        val firstAssistant = unindexed.items.first { it.kind == com.clarklevis.dsh.shared.projection.ConversationItemKind.ASSISTANT }
        assertEquals(GenuiSegmentState.INVALID, assertIs<GenuiSegment>(firstAssistant.segments!!.single()).state)
        val secondAssistant = unindexed.items.last { it.kind == com.clarklevis.dsh.shared.projection.ConversationItemKind.ASSISTANT }
        assertEquals("Next unindexed", secondAssistant.text)
        assertFalse(firstAssistant.id == secondAssistant.id)

        // Mixed chunk/final turn+step indices finalize in place without leaving duplicate rows or stuck STREAMING cards
        val mixedPairs = listOf(
            Pair(6 to null, 6 to 0),
            Pair(null to null, 6 to 0),
            Pair(6 to 0, null to null)
        )
        for ((chunkIdx, finalIdx) in mixedPairs) {
            val p = ConversationProjector()
            p.fold(listOf(
                SessionEvent("s1", 1, 1.0, GatewayEvent("assistant/chunk", turn = chunkIdx.first, step = chunkIdx.second, chunkType = "text-delta", text = open)),
                SessionEvent("s1", 2, 2.0, GatewayEvent("assistant/message", turn = finalIdx.first, step = finalIdx.second, text = "Plain final reply"))
            ))
            assertEquals(1, p.items.size, "Mixed index $chunkIdx -> $finalIdx must not leave duplicate rows")
            assertEquals("Plain final reply", p.items.single().text)
            assertNull(p.items.single().segments)
        }

        // Next user message settles an unclosed turn-only legacy stream so no spinner leaks across turns
        val crossTurn = ConversationProjector()
        crossTurn.fold(listOf(
            SessionEvent("s1", 1, 1.0, GatewayEvent("assistant/chunk", turn = 6, step = null, chunkType = "text-delta", text = open)),
            SessionEvent("s1", 2, 2.0, GatewayEvent("user/message", text = "New question"))
        ))
        val settledPreview = crossTurn.items.first { it.kind == com.clarklevis.dsh.shared.projection.ConversationItemKind.ASSISTANT }
        assertEquals(GenuiSegmentState.INVALID, assertIs<GenuiSegment>(settledPreview.segments!!.single()).state)

        // Ambiguous unindexed final message does NOT overwrite either of two concurrently open streams
        val ambiguous = ConversationProjector()
        ambiguous.fold(listOf(
            SessionEvent("s1", 1, 1.0, GatewayEvent("assistant/chunk", turn = 6, step = 0, chunkType = "text-delta", text = "Stream A")),
            SessionEvent("s1", 2, 2.0, GatewayEvent("assistant/chunk", turn = 6, step = 1, chunkType = "text-delta", text = "Stream B")),
            SessionEvent("s1", 3, 3.0, GatewayEvent("assistant/message", text = "Independent unindexed"))
        ))
        assertEquals(3, ambiguous.items.size)
        assertEquals(listOf("Stream A", "Stream B", "Independent unindexed"), ambiguous.items.map { it.text })

        // Explicit cross-step isolation: final for step 1 must NOT overwrite or delete an open step 0 stream
        val crossStep = ConversationProjector()
        crossStep.fold(listOf(
            SessionEvent("s1", 1, 1.0, GatewayEvent("assistant/chunk", turn = 6, step = 0, chunkType = "text-delta", text = "Step zero")),
            SessionEvent("s1", 2, 2.0, GatewayEvent("assistant/message", turn = 6, step = 1, text = "Step one")),
            SessionEvent("s1", 3, 3.0, GatewayEvent("assistant/chunk", turn = 6, step = 0, chunkType = "text-delta", text = " continued"))
        ))
        assertEquals(2, crossStep.items.size)
        assertEquals("stream-text-6-0", crossStep.items[0].id)
        assertEquals("Step zero continued", crossStep.items[0].text)
        assertEquals("Step one", crossStep.items[1].text)

        val crossStepEmpty = ConversationProjector()
        crossStepEmpty.fold(listOf(
            SessionEvent("s1", 1, 1.0, GatewayEvent("assistant/chunk", turn = 6, step = 0, chunkType = "text-delta", text = "Step zero")),
            SessionEvent("s1", 2, 2.0, GatewayEvent("assistant/message", turn = 6, step = 1, text = null))
        ))
        assertEquals(1, crossStepEmpty.items.size)
        assertEquals("Step zero", crossStepEmpty.items.single().text)

        // New user/message WITH explicit turn=7 settles turn 6 stream WITHOUT reassigning turn 6 -> 7 or stealing turn 7 deliverables
        val turn7File = com.clarklevis.dsh.shared.protocol.PresentedFile(path = "turn7-report.md", name = "turn7-report.md")
        val turnAttribution = ConversationProjector()
        turnAttribution.fold(listOf(
            SessionEvent("s1", 1, 1.0, GatewayEvent("assistant/chunk", turn = 6, step = null, chunkType = "text-delta", text = open)),
            SessionEvent("s1", 2, 2.0, GatewayEvent("user/message", turn = 7, text = "Next turn")),
            SessionEvent("s1", 3, 3.0, GatewayEvent("deliverables/presented", turn = 7, files = listOf(turn7File))),
            SessionEvent("s1", 4, 4.0, GatewayEvent("assistant/message", turn = 7, step = 0, text = "Turn 7 answer"))
        ))
        val turn6Assistant = turnAttribution.items.first { it.kind == com.clarklevis.dsh.shared.projection.ConversationItemKind.ASSISTANT }
        val turn7Assistant = turnAttribution.items.last { it.kind == com.clarklevis.dsh.shared.projection.ConversationItemKind.ASSISTANT }
        assertEquals(6, turn6Assistant.turn)
        assertEquals(GenuiSegmentState.INVALID, assertIs<GenuiSegment>(turn6Assistant.segments!!.single()).state)
        assertTrue(turn6Assistant.files.isEmpty(), "Turn 6 reply must not receive turn 7 deliverables")
        assertTrue(turn6Assistant.knownFiles.isEmpty())
        assertEquals(7, turn7Assistant.turn)
        assertEquals(listOf(turn7File), turn7Assistant.files)

        // Existing stream in a turn with presented deliverables keeps files intact on subsequent text & reasoning fast-path deltas
        val turn6File = com.clarklevis.dsh.shared.protocol.PresentedFile(path = "turn6-chart.png", name = "turn6-chart.png")
        val fastWithFiles = SharedConversationStore()
        val fastEvents = mutableListOf<SharedMviEvent>()
        fastWithFiles.subscribe(SharedMviEventObserver(fastEvents::add))
        assertTrue(fastWithFiles.replaceTypedSession("s1", listOf(
            SessionEvent("s1", 1, 1.0, GatewayEvent("deliverables/presented", turn = 6, files = listOf(turn6File))),
            SessionEvent("s1", 2, 2.0, GatewayEvent("assistant/chunk", turn = 6, step = 0, chunkType = "reasoning-delta", text = "Think")),
            SessionEvent("s1", 3, 3.0, GatewayEvent("assistant/chunk", turn = 6, step = 0, chunkType = "text-delta", text = "Hello"))
        )).accepted)
        assertTrue(fastWithFiles.receiveStreamDelta(wireJson.encodeToString(
            SessionEvent("s1", 3, 4.0, GatewayEvent("assistant/chunk", turn = 6, step = 0, chunkType = "reasoning-delta", text = "ing"))
        )).accepted)
        assertTrue(fastWithFiles.receiveStreamDelta(wireJson.encodeToString(
            SessionEvent("s1", 3, 5.0, GatewayEvent("assistant/chunk", turn = 6, step = 0, chunkType = "text-delta", text = " world"))
        )).accepted)
        val deltaPatch = wireJson.decodeFromString<SharedConversationPatch>(fastEvents.last().statePayloadJson!!)
        assertEquals("append-text", deltaPatch.operations.single().kind)
        assertEquals(" world", deltaPatch.operations.single().delta)
        // Finalizing keeps turn-6 deliverables intact
        assertTrue(fastWithFiles.receiveEvent(wireJson.encodeToString(
            SessionEvent("s1", 4, 6.0, GatewayEvent("assistant/message", turn = 6, step = 0, text = "Hello world"))
        )).accepted)
        val finalPatch = wireJson.decodeFromString<SharedConversationPatch>(fastEvents.last().statePayloadJson!!)
        val finalAssistantItem = finalPatch.operations.last().item!!
        assertEquals(6, finalAssistantItem.turn)
        assertEquals(listOf(turn6File), finalAssistantItem.files)
        assertEquals(listOf(turn6File), finalAssistantItem.knownFiles)

        // Step-only frames (turn omitted) keep distinct steps isolated instead of colliding on unindexed key
        val stepOnly = ConversationProjector()
        stepOnly.fold(listOf(
            SessionEvent("s1", 1, 1.0, GatewayEvent("assistant/chunk", turn = null, step = 0, chunkType = "text-delta", text = "Step zero")),
            SessionEvent("s1", 2, 2.0, GatewayEvent("assistant/message", turn = null, step = 1, text = "Step one")),
            SessionEvent("s1", 3, 3.0, GatewayEvent("assistant/chunk", turn = null, step = 0, chunkType = "text-delta", text = " continues"))
        ))
        assertEquals(2, stepOnly.items.size)
        assertEquals("Step zero continues", stepOnly.items[0].text)
        assertEquals("Step one", stepOnly.items[1].text)

        // Final assistant/message omitting turn AND introducing final-only reasoning keeps streamed assistant id, turn, and deliverables
        val finalReasoningWithFiles = ConversationProjector()
        val ops = finalReasoningWithFiles.foldWithOperations(listOf(
            SessionEvent("s1", 1, 1.0, GatewayEvent("deliverables/presented", turn = 6, files = listOf(turn6File))),
            SessionEvent("s1", 2, 2.0, GatewayEvent("assistant/chunk", turn = 6, step = 0, chunkType = "text-delta", text = "Partial")),
            SessionEvent("s1", 3, 3.0, GatewayEvent("assistant/message", turn = null, step = 0, reasoning = "Final reasoning", text = "Final answer"))
        ))
        assertTrue(ops.any { it.kind == "insert-before" })
        assertEquals(2, finalReasoningWithFiles.items.size)
        assertEquals(com.clarklevis.dsh.shared.projection.ConversationItemKind.REASONING, finalReasoningWithFiles.items[0].kind)
        assertEquals("Final reasoning", finalReasoningWithFiles.items[0].text)
        val preservedAssistant = finalReasoningWithFiles.items[1]
        assertEquals("stream-text-6-0", preservedAssistant.id)
        assertEquals("Final answer", preservedAssistant.text)
        assertEquals(6, preservedAssistant.turn)
        assertEquals(listOf(turn6File), preservedAssistant.files)
        assertEquals(listOf(turn6File), preservedAssistant.knownFiles)

        // P1-1: Conflicting same-turn step (6,1) must NOT block finalizing unique compatible step-only stream (null,0)
        val mixedFallback = ConversationProjector()
        mixedFallback.fold(listOf(
            SessionEvent("s1", 1, 1.0, GatewayEvent("assistant/chunk", turn = null, step = 0, chunkType = "text-delta", text = open)),
            SessionEvent("s1", 2, 2.0, GatewayEvent("assistant/chunk", turn = 6, step = 1, chunkType = "text-delta", text = "Other step")),
            SessionEvent("s1", 3, 3.0, GatewayEvent("assistant/message", turn = 6, step = 0, text = "Final step zero"))
        ))
        assertEquals(2, mixedFallback.items.size)
        assertEquals("stream-text-unindexed-0-1", mixedFallback.items[0].id)
        assertEquals("Final step zero", mixedFallback.items[0].text)
        assertNull(mixedFallback.items[0].segments)
        assertEquals(6, mixedFallback.items[0].turn)
        assertEquals("stream-text-6-1", mixedFallback.items[1].id)
        assertEquals("Other step", mixedFallback.items[1].text)

        // P1-2: Turn-less final message matching turn 6 text must NOT delete turn 7 reasoning stream
        val crossTurnReasoning = ConversationProjector()
        crossTurnReasoning.fold(listOf(
            SessionEvent("s1", 1, 1.0, GatewayEvent("assistant/chunk", turn = 6, step = 0, chunkType = "text-delta", text = "Turn six preview")),
            SessionEvent("s1", 2, 2.0, GatewayEvent("assistant/chunk", turn = 7, step = 0, chunkType = "reasoning-delta", text = "Turn seven reasoning")),
            SessionEvent("s1", 3, 3.0, GatewayEvent("assistant/message", turn = null, step = 0, text = "Turn six final")),
            SessionEvent("s1", 4, 4.0, GatewayEvent("assistant/chunk", turn = 7, step = 0, chunkType = "reasoning-delta", text = " more"))
        ))
        assertEquals(2, crossTurnReasoning.items.size)
        assertEquals("Turn six final", crossTurnReasoning.items[0].text)
        assertEquals(6, crossTurnReasoning.items[0].turn)
        assertEquals(com.clarklevis.dsh.shared.projection.ConversationItemKind.REASONING, crossTurnReasoning.items[1].kind)
        assertEquals("Turn seven reasoning more", crossTurnReasoning.items[1].text)

        // P1-3: Late tool-call-delta after fully-indexed assistant/message(turn,step) is ignored for both new and existing tool rows
        val lateToolNew = ConversationProjector()
        lateToolNew.fold(listOf(
            SessionEvent("s1", 1, 1.0, GatewayEvent("assistant/message", turn = 6, step = 0, text = "Done")),
            SessionEvent("s1", 2, 2.0, GatewayEvent("assistant/chunk", turn = 6, step = 0, chunkType = "tool-call-delta",
                tool = com.clarklevis.dsh.shared.protocol.ToolDelta("late-tool", "run_code", "late args")))
        ))
        assertEquals(listOf("Done"), lateToolNew.items.map { it.text })

        val lateToolExisting = SharedConversationStore()
        val toolEvents = mutableListOf<SharedMviEvent>()
        lateToolExisting.subscribe(SharedMviEventObserver(toolEvents::add))
        assertTrue(lateToolExisting.replaceTypedSession("s1", listOf(
            SessionEvent("s1", 1, 1.0, GatewayEvent("assistant/chunk", turn = 6, step = 0, chunkType = "tool-call-delta",
                tool = com.clarklevis.dsh.shared.protocol.ToolDelta("t1", "run_code", "early"))),
            SessionEvent("s1", 2, 2.0, GatewayEvent("assistant/message", turn = 6, step = 0, text = "Done"))
        )).accepted)
        assertTrue(lateToolExisting.receiveEvent(wireJson.encodeToString(
            SessionEvent("s1", 3, 3.0, GatewayEvent("assistant/chunk", turn = 6, step = 0, chunkType = "tool-call-delta",
                tool = com.clarklevis.dsh.shared.protocol.ToolDelta("t1", "run_code", " late")))
        )).accepted)
        val lateToolPatch = wireJson.decodeFromString<SharedConversationPatch>(toolEvents.last().statePayloadJson!!)
        assertTrue(lateToolPatch.operations.isEmpty(), "Late tool-call-delta after finalized (6,0) must emit no operations")

        // Explicit-turn final message omitting step blocks late step-less tool/text/reasoning deltas for that turn,
        // while allowing other explicit steps in the same turn and subsequent turns.
        val missingStepFinal = ConversationProjector()
        missingStepFinal.fold(listOf(
            SessionEvent("s1", 1, 1.0, GatewayEvent("assistant/chunk", turn = 6, step = null, chunkType = "tool-call-delta",
                tool = com.clarklevis.dsh.shared.protocol.ToolDelta("existing", "run_code", "early"))),
            SessionEvent("s1", 2, 2.0, GatewayEvent("assistant/message", turn = 6, step = null, text = "Done")),
            SessionEvent("s1", 3, 3.0, GatewayEvent("assistant/chunk", turn = 6, step = null, chunkType = "tool-call-delta",
                tool = com.clarklevis.dsh.shared.protocol.ToolDelta("existing", "run_code", " more"))),
            SessionEvent("s1", 4, 4.0, GatewayEvent("assistant/chunk", turn = 6, step = null, chunkType = "tool-call-delta",
                tool = com.clarklevis.dsh.shared.protocol.ToolDelta("late", "run_code", "late args"))),
            SessionEvent("s1", 5, 5.0, GatewayEvent("assistant/chunk", turn = 6, step = null, chunkType = "text-delta", text = open)),
            SessionEvent("s1", 6, 6.0, GatewayEvent("assistant/chunk", turn = 6, step = null, chunkType = "reasoning-delta", text = "late reasoning")),
            SessionEvent("s1", 7, 7.0, GatewayEvent("assistant/chunk", turn = 6, step = 1, chunkType = "text-delta", text = "Turn 6 step 1")),
            SessionEvent("s1", 8, 8.0, GatewayEvent("assistant/chunk", turn = 7, step = null, chunkType = "text-delta", text = "Next turn"))
        ))
        assertEquals(listOf("early", "Done", "Turn 6 step 1", "Next turn"), missingStepFinal.items.map { it.text })

        // Turn-less final message (with step=0 OR step=null) blocks all 4 late chunk types within the same reply,
        // and allows reusing the exact same index after a new user/message starts the next turn.
        for (stepValue in listOf<Int?>(0, null)) {
            val turnLess = ConversationProjector()
            turnLess.fold(listOf(
                SessionEvent("s1", 1, 1.0, GatewayEvent("user/message", text = "Question")),
                SessionEvent("s1", 2, 2.0, GatewayEvent("assistant/chunk", turn = null, step = stepValue, chunkType = "tool-call-delta",
                    tool = com.clarklevis.dsh.shared.protocol.ToolDelta("existing", "run_code", "early"))),
                SessionEvent("s1", 3, 3.0, GatewayEvent("assistant/chunk", turn = null, step = stepValue, chunkType = "text-delta", text = "Preview")),
                SessionEvent("s1", 4, 4.0, GatewayEvent("assistant/message", turn = null, step = stepValue, text = "Done")),
                SessionEvent("s1", 5, 5.0, GatewayEvent("assistant/chunk", turn = null, step = stepValue, chunkType = "tool-call-delta",
                    tool = com.clarklevis.dsh.shared.protocol.ToolDelta("existing", "run_code", " more"))),
                SessionEvent("s1", 6, 6.0, GatewayEvent("assistant/chunk", turn = null, step = stepValue, chunkType = "tool-call-delta",
                    tool = com.clarklevis.dsh.shared.protocol.ToolDelta("new", "run_code", "late args"))),
                SessionEvent("s1", 7, 7.0, GatewayEvent("assistant/chunk", turn = null, step = stepValue, chunkType = "text-delta", text = open)),
                SessionEvent("s1", 8, 8.0, GatewayEvent("assistant/chunk", turn = null, step = stepValue, chunkType = "reasoning-delta", text = "late reasoning"))
            ))
            assertEquals(listOf("Question", "early", "Done"), turnLess.items.map { it.text }, "Late turn-less chunks (step=$stepValue) must be blocked")

            turnLess.fold(listOf(
                SessionEvent("s1", 9, 9.0, GatewayEvent("user/message", text = "Next question")),
                SessionEvent("s1", 10, 10.0, GatewayEvent("assistant/chunk", turn = null, step = stepValue, chunkType = "text-delta", text = "Next preview"))
            ))
            assertEquals(
                listOf("Question", "early", "Done", "Next question", "Next preview"),
                turnLess.items.map { it.text },
                "Same turn-less index (step=$stepValue) must open stream before final message after next user/message"
            )
            turnLess.fold(listOf(
                SessionEvent("s1", 11, 11.0, GatewayEvent("assistant/message", turn = null, step = stepValue, text = "Next done"))
            ))
            assertEquals(
                listOf("Question", "early", "Done", "Next question", "Next done"),
                turnLess.items.map { it.text },
                "Same turn-less index (step=$stepValue) must finalize cleanly after next user/message"
            )
        }

        // Parallel tool/result and turn/end must NOT prematurely clear finalized step=0 turn-less keys before a new user/message
        for (boundary in listOf(
            GatewayEvent("tool/result", step = 0, callId = "existing", output = "First result"),
            GatewayEvent("turn/end", reason = "done")
        )) {
            val p = ConversationProjector()
            p.fold(listOf(
                SessionEvent("s1", 1, 1.0, GatewayEvent("user/message", text = "Question")),
                SessionEvent("s1", 2, 2.0, GatewayEvent("assistant/chunk", step = 0, chunkType = "tool-call-delta",
                    tool = com.clarklevis.dsh.shared.protocol.ToolDelta("existing", "run_code", "early"))),
                SessionEvent("s1", 3, 3.0, GatewayEvent("assistant/chunk", step = 0, chunkType = "text-delta", text = "Preview")),
                SessionEvent("s1", 4, 4.0, GatewayEvent("assistant/message", step = 0, text = "Done")),
                SessionEvent("s1", 5, 5.0, boundary),
                SessionEvent("s1", 6, 6.0, GatewayEvent("assistant/chunk", step = 0, chunkType = "tool-call-delta",
                    tool = com.clarklevis.dsh.shared.protocol.ToolDelta("existing", "run_code", " more"))),
                SessionEvent("s1", 7, 7.0, GatewayEvent("assistant/chunk", step = 0, chunkType = "tool-call-delta",
                    tool = com.clarklevis.dsh.shared.protocol.ToolDelta("new", "run_code", "late args"))),
                SessionEvent("s1", 8, 8.0, GatewayEvent("assistant/chunk", step = 0, chunkType = "text-delta", text = open)),
                SessionEvent("s1", 9, 9.0, GatewayEvent("assistant/chunk", step = 0, chunkType = "reasoning-delta", text = "late reasoning")),
                SessionEvent("s1", 10, 10.0, GatewayEvent("assistant/chunk", step = 1, chunkType = "text-delta", text = "Step one"))
            ))
            val expectedBeforeNextUser = if (boundary.type == "tool/result") {
                listOf("Question", "early", "Done", "First result", "Step one")
            } else {
                listOf("Question", "early", "Done", "Step one")
            }
            assertEquals(expectedBeforeNextUser, p.items.map { it.text })
            p.fold(listOf(
                SessionEvent("s1", 11, 11.0, GatewayEvent("user/message", text = "Next question")),
                SessionEvent("s1", 12, 12.0, GatewayEvent("assistant/chunk", step = 0, chunkType = "text-delta", text = "Next preview"))
            ))
            assertEquals(expectedBeforeNextUser + listOf("Next question", "Next preview"), p.items.map { it.text })
        }

        // Blocking ask_user_question tool/result must NOT clear finalized step=0, while delayed user-question-reply DOES start a new turn
        val askArgs = com.clarklevis.dsh.shared.protocol.JsonValue.fromJsonElement(
            kotlinx.serialization.json.Json.parseToJsonElement("""{"questions":[{"id":"q","question":"Which option?"}]}""")
        )
        val blockingAsk = ConversationProjector()
        blockingAsk.fold(listOf(
            SessionEvent("s1", 1, 1.0, GatewayEvent("user/message", text = "Question")),
            SessionEvent("s1", 2, 2.0, GatewayEvent("assistant/chunk", step = 0, chunkType = "tool-call-delta",
                tool = com.clarklevis.dsh.shared.protocol.ToolDelta("parallel", "run_code", "early"))),
            SessionEvent("s1", 3, 3.0, GatewayEvent("assistant/chunk", step = 0, chunkType = "text-delta", text = "Preview")),
            SessionEvent("s1", 4, 4.0, GatewayEvent("assistant/message", step = 0, text = "Done", toolCalls = listOf(
                com.clarklevis.dsh.shared.protocol.ToolCall("parallel", "run_code"),
                com.clarklevis.dsh.shared.protocol.ToolCall("ask", "ask_user_question", askArgs)
            ))),
            SessionEvent("s1", 5, 5.0, GatewayEvent("tool/result", step = 0, callId = "ask",
                output = """{"answers":[{"id":"q","selected":["A"]}]}""")),
            SessionEvent("s1", 6, 6.0, GatewayEvent("assistant/chunk", step = 0, chunkType = "tool-call-delta",
                tool = com.clarklevis.dsh.shared.protocol.ToolDelta("parallel", "run_code", " more"))),
            SessionEvent("s1", 7, 7.0, GatewayEvent("assistant/chunk", step = 0, chunkType = "tool-call-delta",
                tool = com.clarklevis.dsh.shared.protocol.ToolDelta("new", "run_code", "late args"))),
            SessionEvent("s1", 8, 8.0, GatewayEvent("assistant/chunk", step = 0, chunkType = "text-delta", text = open)),
            SessionEvent("s1", 9, 9.0, GatewayEvent("assistant/chunk", step = 0, chunkType = "reasoning-delta", text = "late reasoning")),
            SessionEvent("s1", 10, 10.0, GatewayEvent("assistant/chunk", step = 1, chunkType = "text-delta", text = "Step one"))
        ))
        assertEquals(
            listOf("Question", "early", "Done", """{"answers":[{"id":"q","selected":["A"]}]}""", "Which option?\nA", "Step one"),
            blockingAsk.items.map { it.text }
        )

        val delayedAsk = ConversationProjector()
        delayedAsk.fold(listOf(
            SessionEvent("s1", 1, 1.0, GatewayEvent("user/message", text = "Question")),
            SessionEvent("s1", 2, 2.0, GatewayEvent("assistant/message", step = 0, text = "Done", toolCalls = listOf(
                com.clarklevis.dsh.shared.protocol.ToolCall("ask2", "ask_user_question", askArgs)
            ))),
            SessionEvent("s1", 3, 3.0, GatewayEvent("user/message", source = "user-question-reply",
                text = """{"kind":"answer_to_pending_question","tool":"ask_user_question","callId":"ask2","answers":[{"id":"q","selected":["B"]}]}""")),
            SessionEvent("s1", 4, 4.0, GatewayEvent("assistant/chunk", step = 0, chunkType = "text-delta", text = "After delayed answer"))
        ))
        assertEquals(
            listOf("Question", "Done", "Which option?\nB", "After delayed answer"),
            delayedAsk.items.map { it.text }
        )
    }

    @Test
    fun malformedAssistantChunkBatchRollsBackAtomicallySoSubsequentChunksStillInsertCleanly() {
        val store = SharedConversationStore()
        val events = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(events::add))
        assertTrue(store.replaceTypedSession("s1", listOf(
            SessionEvent("s1", 1, 1.0, GatewayEvent("user/message", text = "hi"))
        )).accepted)

        // Chunk #1 is valid text-delta, chunk #2 has an object for `type` -> throws during foldAssistantChunks
        val badBatchJson = """[
            {"time":1000,"chunk":{"type":"text-delta","index":0,"text":"Unpublished"}},
            {"time":1001,"chunk":{"type":{"nested":true},"text":"boom"}}
        ]"""
        val badResult = store.assistantChunks("s1", "att-1", badBatchJson)
        assertFalse(badResult.accepted)
        assertEquals("conversation-assistant-chunks-failed", badResult.errorCode)
        assertFalse(store.hasAssistantChunks("s1"))

        // Next valid chunk for the same attempt/stream MUST emit `insert` (not `append-text` targeting an unpublished row)
        val goodBatchJson = """[{"time":1002,"chunk":{"type":"text-delta","index":0,"text":"Published"}}]"""
        val goodResult = store.assistantChunks("s1", "att-1", goodBatchJson)
        assertTrue(goodResult.accepted)
        val goodPatch = wireJson.decodeFromString<SharedConversationPatch>(events.last().statePayloadJson!!)
        assertEquals("insert", goodPatch.operations.single().kind)
        assertEquals("Published", goodPatch.operations.single().item!!.text)
    }

    @Test
    fun patchGuardRejectsUserSegmentsInvalidAppendSegmentsAndExtraOperationFields() {
        val validPatch = """{"schema":1,"sessionId":"s1","operations":[],"replacesAll":true,"replacementItems":[{"id":"s1-1","kind":"ASSISTANT","title":"DeepSeek","text":"Hi\n```dsh-ui\n${artifact().replace("\"", "\\\"")}\n```","images":[],"isError":false,"epochSeconds":1.0,"segments":[{"type":"markdown","text":"Hi\n"},{"type":"genui","id":"s1-1-genui-0","rawJson":"${artifact().replace("\"", "\\\"")}","state":"READY","fallbackMarkdown":"```dsh-ui\n${artifact().replace("\"", "\\\"")}\n```","fallbackStart":3,"fallbackEnd":${3 + "```dsh-ui\n${artifact()}\n```".length},"contentVersion":"${GenuiBlockParser.contentVersion(artifact())}","interactive":true}]}],"lastSequence":1}"""
        assertTrue(SharedConversationPatchGuard.validate(validPatch))

        // append-text carrying invalid READY rawJson ("{}") in segments or segmentChanges must be rejected by shared guard
        val badAppendFull = """{"schema":1,"sessionId":"s1","operations":[{"kind":"append-text","itemId":"s1-1","delta":"\n```","epochSeconds":2.0,"segments":[{"type":"genui","id":"s1-1-genui-0","rawJson":"{}","state":"READY","fallbackMarkdown":"```dsh-ui\n{}\n```","fallbackStart":0,"fallbackEnd":16,"contentVersion":"${GenuiBlockParser.contentVersion("{}")}","interactive":false}]}],"replacesAll":false,"lastSequence":1}"""
        assertFalse(SharedConversationPatchGuard.validate(badAppendFull))
        val badAppendChange = """{"schema":1,"sessionId":"s1","operations":[{"kind":"append-text","itemId":"s1-1","delta":"\n```","epochSeconds":2.0,"segmentChanges":[{"index":0,"segment":{"type":"genui","id":"s1-1-genui-0","rawJson":"{}","state":"READY","fallbackMarkdown":"```dsh-ui\n{}\n```","fallbackStart":0,"fallbackEnd":16,"contentVersion":"${GenuiBlockParser.contentVersion("{}")}","interactive":false}}]}],"replacesAll":false,"lastSequence":1}"""
        assertFalse(SharedConversationPatchGuard.validate(badAppendChange))

        val userWithSegments = """{"schema":1,"sessionId":"s1","operations":[],"replacesAll":true,"replacementItems":[{"id":"s1-1","kind":"USER","title":"You","text":"Hi","images":[],"isError":false,"epochSeconds":1.0,"segments":[{"type":"markdown","text":"Hi"}]}],"lastSequence":1}"""
        assertFalse(SharedConversationPatchGuard.validate(userWithSegments))

        val insertWithEpoch = """{"schema":1,"sessionId":"s1","operations":[{"kind":"insert","item":{"id":"s1-1","kind":"USER","title":"You","text":"Hi","images":[],"isError":false,"epochSeconds":1.0},"epochSeconds":1.0}],"replacesAll":false,"lastSequence":1}"""
        assertFalse(SharedConversationPatchGuard.validate(insertWithEpoch))

        val removeWithEpoch = """{"schema":1,"sessionId":"s1","operations":[{"kind":"remove","itemId":"s1-1","epochSeconds":1.0}],"replacesAll":false,"lastSequence":1}"""
        assertFalse(SharedConversationPatchGuard.validate(removeWithEpoch))

        val stringSchema = """{"schema":"1","sessionId":"s1","operations":[],"replacesAll":false,"lastSequence":1}"""
        assertFalse(SharedConversationPatchGuard.validate(stringSchema))
    }

    private fun action(json: String): GenuiAction? = GenuiActionCodec.decode(json, "block")

    private fun blockState(rawJson: String): GenuiSegmentState =
        assertIs<GenuiSegment>(GenuiBlockParser.parse("m", "```dsh-ui\n$rawJson\n```")!!.single()).state

    private fun artifact() = """{"format":"dsh-genui","version":1,"spec":{"title":"Demo","items":[{"type":"text","content":"Hello"}]},"state":{},"presentation":{"locale":"en","theme":"light"}}"""

    private fun artifactWithItem(item: String) =
        """{"format":"dsh-genui","version":1,"spec":{"title":"Demo","items":[$item]},"state":{},"presentation":{"locale":"en","theme":"light"}}"""

    private fun event(sequence: Int, text: String) = SessionEvent(
        "s1", sequence, sequence.toDouble(),
        GatewayEvent("assistant/chunk", turn = 1, step = 1, chunkType = "text-delta", text = text)
    )
}
