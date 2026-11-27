package com.clarklevis.dsh.android

import com.clarklevis.dsh.shared.facade.SharedMviEvent
import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidGatewayProjectionTest {
    @Test
    fun lateAcceptanceForOtherMemberDoesNotResetCurrentStream() {
        val projection = AndroidGatewayProjection()
        projection.selectSession("s")
        fun accept(raw: String) = projection.acceptFrame(raw, GatewayWireDecoder.decode(raw), null)
        accept("""{"kind":"hello","historyFormatVersion":4,"capabilities":["assistant-stream-v1"]}""")
        accept("""{"kind":"subscribed","sessionId":"s","subscriptionId":"sub"}""")
        accept("""{"kind":"session-snapshot","sessionId":"s","subscriptionId":"sub","streamId":"stream","historyFormatVersion":4,"cursor":0,"events":[],"hasMore":false,"assistantStream":{"revision":0}}""")
        accept("""{"kind":"sent","sessionId":"other-member"}""")
        accept("""{"kind":"event","sessionId":"s","subscriptionId":"sub","streamId":"stream","seq":1,"time":100,"event":{"type":"assistant/message","text":"当前成员仍在输出"}}""")
        assertEquals("s", projection.snapshot().selectedSessionId)
        assertEquals("当前成员仍在输出", projection.snapshot().conversation.last().text)
        projection.close()
    }

    @Test
    fun steeringQueueDisplaysUserBubbleAndPromotesWithoutDuplicate() {
        val projection = AndroidGatewayProjection()
        projection.selectSession("s")
        fun accept(raw: String) = projection.acceptFrame(raw, GatewayWireDecoder.decode(raw), "s")
        accept("""{"kind":"session-queue","sessionId":"s","items":[{"id":"q","placement":"steering","message":{"id":"m","content":[{"type":"text","text":"Go"}]}}]}""")
        val pending = projection.snapshot().conversation.single()
        assertEquals("Go", pending.text)
        assertEquals(com.clarklevis.dsh.shared.projection.ConversationItemKind.USER, pending.kind)
        accept("""{"kind":"event","sessionId":"s","seq":1,"time":100,"event":{"type":"user/message","text":"Go","raw":{"id":"m"}}}""")
        accept("""{"kind":"session-queue","sessionId":"s","items":[]}""")
        assertEquals(pending.id, projection.snapshot().conversation.single().id)
        assertEquals(null, projection.snapshot().lastError)
        projection.close()
    }

    @Test
    fun fullQueueBaselineClearsAbsentSessionAndHostResetCannotRestoreOldSteering() {
        val projection = AndroidGatewayProjection()
        projection.selectSession("s")
        fun accept(raw: String) = projection.acceptFrame(raw, GatewayWireDecoder.decode(raw), "s")
        val queue = """{"kind":"session-queues","queues":{"s":[{"id":"q","placement":"steering","message":{"id":"m","content":[{"type":"text","text":"Go"}]}}]}}"""
        accept(queue)
        assertEquals(1, projection.snapshot().conversation.size)
        accept("""{"kind":"session-queues","queues":{}}""")
        assertTrue(projection.snapshot().conversation.isEmpty())
        accept(queue)
        projection.reset()
        projection.selectSession("s")
        accept("""{"kind":"history","sessionId":"s","events":[],"hasMore":false}""")
        assertTrue(projection.snapshot().conversation.isEmpty())
        projection.close()
    }

    @Test
    fun assistantChunkBypassesLegacyFullConversationProjection() {
        val projection = AndroidGatewayProjection()
        projection.selectSession("session-a")
        val chunk = GatewayWireDecoder.decode(
            """{"sessionId":"session-a","seq":1,"time":100,"event":{"type":"assistant/chunk","turn":1,"step":1,"chunkType":"text-delta","text":"增量"}}"""
        )

        // rawJson 只供旧 MobileStore 使用；chunk 应完全由增量 Store 消费。
        projection.acceptFrame("not-json", chunk, "session-a")

        assertEquals("增量", projection.snapshot().conversation.single().text)
        assertEquals(null, projection.snapshot().lastError)
        projection.close()
    }

    @Test
    fun burstOfTinyChunksKeepsExactTextWithoutFallingBackToFullReprojection() {
        val projection = AndroidGatewayProjection()
        projection.selectSession("session-a")
        val expected = buildString {
            repeat(1_200) { index ->
                val delta = ('a'.code + index % 26).toChar().toString()
                append(delta)
                val raw =
                    """{"sessionId":"session-a","seq":${index + 1},"time":${index + 1},"event":{"type":"assistant/chunk","turn":1,"step":1,"chunkType":"text-delta","text":"$delta"}}"""
                projection.acceptFrame(raw, GatewayWireDecoder.decode(raw), "session-a")
            }
        }
        val finalRaw =
            """{"sessionId":"session-a","seq":1201,"time":1201,"event":{"type":"assistant/message","turn":1,"step":1,"text":"$expected"}}"""
        projection.acceptFrame(finalRaw, GatewayWireDecoder.decode(finalRaw), "session-a")

        assertEquals(expected, projection.snapshot().conversation.single().text)
        // 共享层终帧使用 replace 保持列表项 ID，避免 Compose 将同一消息视作新行。
        assertEquals("stream-text-1-1", projection.snapshot().conversation.single().id)
        projection.close()
    }

    @Test
    fun newSessionSentResponseBindsLiveConversationWithoutLeavingTheScreen() {
        val projection = AndroidGatewayProjection()
        projection.selectSession(null)

        val sent = """{"kind":"sent","sessionId":"session-new"}"""
        projection.acceptFrame(sent, GatewayWireDecoder.decode(sent), "session-new")
        assertEquals("session-new", projection.snapshot().selectedSessionId)

        val user =
            """{"kind":"event","sessionId":"session-new","seq":1,"time":100,"event":{"type":"user/message","text":"123"}}"""
        projection.acceptFrame(user, GatewayWireDecoder.decode(user), "session-new")
        val assistant =
            """{"kind":"event","sessionId":"session-new","seq":2,"time":101,"event":{"type":"assistant/message","turn":1,"step":1,"text":"reply"}}"""
        projection.acceptFrame(assistant, GatewayWireDecoder.decode(assistant), "session-new")

        assertEquals(listOf("123", "reply"), projection.snapshot().conversation.map { it.text })
        projection.close()
    }

    @Test
    fun correlatedHistoryCannotWriteIntoNewSelectionAndLivePatchesRemainIncremental() {
        val requested = mutableListOf<Pair<String, Int?>>()
        val projection = AndroidGatewayProjection(
            onHistoryPageRequested = { sessionId, before, _ -> requested += sessionId to before }
        )
        projection.selectSession("session-a")
        projection.selectSession("session-b")
        assertEquals(listOf("session-a" to null, "session-b" to null), requested)

        val history =
            """{"kind":"history","events":[{"type":"user/message","seq":1,"time":100,"data":{"content":[{"type":"text","text":"history-a"}],"source":{"kind":"user"}}}],"hasMore":true,"nextBeforeSeq":0,"bytes":128}"""
        projection.acceptFrame(history, GatewayWireDecoder.decode(history), "session-a")
        assertTrue(projection.snapshot().conversation.isEmpty())
        assertEquals(listOf("session-a" to null, "session-b" to null), requested)

        projection.loadHistory("session-a", older = true)
        assertEquals("session-a" to 1, requested.last())
        val older = """{"kind":"history","events":[],"hasMore":false,"bytes":0}"""
        projection.acceptFrame(older, GatewayWireDecoder.decode(older), "session-a")

        projection.selectSession("session-a")
        assertEquals("history-a", projection.snapshot().conversation.single().text)
        val chunk =
            """{"sessionId":"session-a","seq":2,"time":101,"event":{"type":"assistant/chunk","turn":1,"step":1,"chunkType":"text-delta","text":"partial"}}"""
        projection.acceptFrame(chunk, GatewayWireDecoder.decode(chunk), "session-a")
        assertEquals("partial", projection.snapshot().conversation.last().text)
        val final =
            """{"sessionId":"session-a","seq":3,"time":102,"event":{"type":"assistant/message","turn":1,"step":1,"text":"final"}}"""
        projection.acceptFrame(final, GatewayWireDecoder.decode(final), "session-a")
        assertEquals(listOf("history-a", "final"), projection.snapshot().conversation.map { it.text })

        val hello = """{"kind":"hello","authenticated":true}"""
        projection.acceptFrame(hello, GatewayWireDecoder.decode(hello), null)
        // 无格式版本的缓存不能跨握手复用。
        assertTrue(projection.snapshot().conversation.isEmpty())
        projection.close()
    }

    @Test
    fun historyCancellationEndsLoadingSoSelectionCanRequestAgain() {
        val requested = mutableListOf<Pair<String, Int?>>()
        val projection = AndroidGatewayProjection(
            onHistoryPageRequested = { sessionId, before, _ -> requested += sessionId to before }
        )
        projection.selectSession("session-a")
        assertTrue(projection.snapshot().selectedHistoryIsLoading)
        assertFalse(projection.snapshot().selectedHistoryIsLoadingOlder)
        projection.historyCancelled("session-a")
        assertFalse(projection.snapshot().selectedHistoryIsLoading)
        projection.selectSession("session-a")
        assertEquals(listOf("session-a" to null, "session-a" to null), requested)
        projection.close()
    }

    @Test
    fun reconnectHistoryBackfillsMissedUserMessageBeforeLiveAssistantReply() {
        val requested = mutableListOf<Pair<String, Int?>>()
        val projection = AndroidGatewayProjection(
            onHistoryPageRequested = { sessionId, before, _ -> requested += sessionId to before }
        )
        projection.selectSession("session-a")
        val emptyBaseline = """{"kind":"history","events":[],"hasMore":false,"bytes":0}"""
        projection.acceptFrame(
            emptyBaseline,
            GatewayWireDecoder.decode(emptyBaseline),
            "session-a"
        )

        // The app reconnects after the other client sent the user message. Only the
        // later assistant event reaches the restored live subscription at first.
        val liveAssistant =
            """{"kind":"event","sessionId":"session-a","seq":3,"time":103,"event":{"type":"assistant/message","turn":2,"step":1,"text":"Agent 回复"}}"""
        projection.acceptFrame(
            liveAssistant,
            GatewayWireDecoder.decode(liveAssistant),
            "session-a"
        )
        assertEquals(listOf("Agent 回复"), projection.snapshot().conversation.map { it.text })

        projection.catchUpSelectedHistoryAfterReconnect()
        val catchUpHistory =
            """{"kind":"history","events":[{"type":"user/message","seq":2,"time":102,"data":{"content":[{"type":"text","text":"后台期间的问题"}],"source":{"kind":"user"}}},{"type":"assistant/message","seq":3,"time":103,"data":{"content":[{"type":"text","text":"过期的历史回复"}]}}],"hasMore":false,"bytes":128}"""
        projection.acceptFrame(
            catchUpHistory,
            GatewayWireDecoder.decode(catchUpHistory),
            "session-a"
        )

        assertEquals(
            listOf("后台期间的问题", "Agent 回复"),
            projection.snapshot().conversation.map { it.text }
        )
        assertEquals(listOf("session-a" to null, "session-a" to null), requested)
        projection.close()
    }

    @Test
    fun replayedApprovalUsesCommandFromSharedHistoryProjection() {
        val projection = AndroidGatewayProjection()
        projection.selectSession("session-a")
        val history =
            """{"kind":"history","events":[{"type":"tool/call","seq":1,"time":100,"data":{"callId":"call-1","name":"Bash","arguments":"{\"command\":\"sw_vers && uname -a\",\"description\":\"读取系统版本\"}"}}],"hasMore":false}"""
        projection.acceptFrame(history, GatewayWireDecoder.decode(history), "session-a")
        val approval =
            """{"kind":"approval-requested","rpcId":"rpc-1","sessionId":"session-a","approvalId":"approval-1","toolName":"Bash","callId":"call-1","reason":"读取系统版本","replay":true}"""
        projection.acceptFrame(approval, GatewayWireDecoder.decode(approval), null)

        assertEquals(
            "sw_vers && uname -a",
            projection.snapshot().approvalCommandPreviews["rpc-1"]
        )
        assertEquals(
            "读取系统版本",
            projection.snapshot().approvalDetails["rpc-1"]?.get("description")?.stringValue
        )
        projection.close()
    }

    @Test
    fun initialHistoryStopsAfterOnePageAndLeavesOlderPagesForUserScroll() {
        val projection = AndroidGatewayProjection()
        projection.selectSession("session-a")
        assertTrue(projection.snapshot().selectedHistoryIsLoading)
        assertEquals(0, projection.snapshot().selectedHistoryLoadedEventCount)
        assertEquals(null, projection.snapshot().selectedHistoryTotalEventCount)

        val firstPage =
            """{"kind":"history","events":[{"type":"user/message","seq":1,"time":100,"data":{"content":[{"type":"text","text":"history"}],"source":{"kind":"user"}}}],"hasMore":true,"nextBeforeSeq":0,"bytes":64}"""
        projection.acceptFrame(firstPage, GatewayWireDecoder.decode(firstPage), "session-a")
        assertFalse(projection.snapshot().selectedHistoryIsLoading)
        assertTrue(projection.snapshot().selectedHistoryHasMore)
        assertEquals(1, projection.snapshot().selectedHistoryEarliestSequence)

        projection.loadHistory("session-a", older = true)
        assertTrue(projection.snapshot().selectedHistoryIsLoadingOlder)
        val finalPage = """{"kind":"history","events":[],"hasMore":false,"bytes":0}"""
        projection.acceptFrame(finalPage, GatewayWireDecoder.decode(finalPage), "session-a")
        assertFalse(projection.snapshot().selectedHistoryIsLoading)
        assertEquals(0, projection.snapshot().selectedHistoryLoadedEventCount)
        assertEquals(null, projection.snapshot().selectedHistoryTotalEventCount)
        projection.close()
    }

    @Test
    fun mviEnvelopeRejectsDuplicateGapWrongDomainAndStaysFailedClosed() {
        val valid = MviEnvelopeValidator("history")
        val snapshot = event(sequence = 7, transaction = "snapshot", kind = "snapshot")
        assertTrue(valid.validate(snapshot))
        valid.commit(snapshot)
        val next = event(sequence = 8, transaction = "next")
        assertTrue(valid.validate(next))
        valid.commit(next)
        assertFalse(valid.validate(event(sequence = 9, transaction = "next")))
        assertFalse(valid.validate(event(sequence = 10, transaction = "after-duplicate")))

        val gap = MviEnvelopeValidator("history")
        val gapSnapshot = event(sequence = 0, transaction = "snapshot", kind = "snapshot")
        assertTrue(gap.validate(gapSnapshot))
        gap.commit(gapSnapshot)
        assertFalse(gap.validate(event(sequence = 2, transaction = "gap")))

        val wrongDomain = MviEnvelopeValidator("history")
        assertFalse(
            wrongDomain.validate(
                event(sequence = 0, transaction = "wrong", kind = "snapshot", domain = "conversation")
            )
        )

        val badPatch = MviEnvelopeValidator("history")
        val badSnapshot = event(sequence = 0, transaction = "snapshot", kind = "snapshot")
        assertTrue(badPatch.validate(badSnapshot))
        badPatch.commit(badSnapshot)
        badPatch.reject()
        assertFalse(badPatch.validate(event(sequence = 1, transaction = "valid-after-bad-patch")))

        val bounded = MviEnvelopeValidator("history")
        repeat(100) { index ->
            val value = event(
                sequence = index.toLong(),
                transaction = "transaction-$index",
                kind = if (index == 0) "snapshot" else "transition"
            )
            assertTrue(bounded.validate(value))
            bounded.commit(value)
        }
        assertEquals(64, bounded.retainedTransactionCountForTest)
    }

    @Test
    fun malformedHistoryPayloadAndEffectCommitNeitherStateNorIoAndStayFailedClosed() {
        val requested = mutableListOf<Pair<String, Int?>>()
        val projection = AndroidGatewayProjection(
            onHistoryPageRequested = { sessionId, before, _ -> requested += sessionId to before }
        )
        val before = projection.snapshot()
        projection.acceptHistoryMviEventForTest(
            SharedMviEvent(
                sequence = 1,
                transactionId = "malformed:1",
                domain = "history",
                kind = "transition",
                statePayloadJson =
                    """{"schema":1,"sessionId":"session-a","eventPatch":{"kind":"unknown"},"outcome":"request-page"}""",
                effectsJson =
                    """[{"action":"request-page","sessionId":"session-a","beforeSequence":9}]"""
            )
        )
        assertEquals(before.conversation, projection.snapshot().conversation)
        assertTrue(requested.isEmpty())
        assertEquals("history-adapter-failed", projection.snapshot().lastError)

        projection.acceptHistoryMviEventForTest(
            SharedMviEvent(
                sequence = 1,
                transactionId = "valid-after-failure:1",
                domain = "history",
                kind = "transition",
                statePayloadJson =
                    """{"schema":1,"sessionId":"session-a","outcome":"request-page"}""",
                effectsJson =
                    """[{"action":"request-page","sessionId":"session-a","beforeSequence":9}]"""
            )
        )
        assertTrue(requested.isEmpty())
        assertEquals("history-envelope-invalid", projection.snapshot().lastError)
        projection.close()

        val badEffectRequests = mutableListOf<Pair<String, Int?>>()
        val badEffectProjection = AndroidGatewayProjection(
            onHistoryPageRequested = { sessionId, before, _ -> badEffectRequests += sessionId to before }
        )
        badEffectProjection.acceptHistoryMviEventForTest(
            SharedMviEvent(
                sequence = 1,
                transactionId = "bad-effect:1",
                domain = "history",
                kind = "transition",
                statePayloadJson =
                    """{"schema":1,"sessionId":"session-a","outcome":"request-page"}""",
                effectsJson =
                    """[{"action":"request-page","sessionId":"session-b","beforeSequence":9}]"""
            )
        )
        assertTrue(badEffectRequests.isEmpty())
        assertEquals("history-adapter-failed", badEffectProjection.snapshot().lastError)
        badEffectProjection.close()
    }

    @Test
    fun unknownConversationOperationCommitsNoPartialItemsAndClosesAdapter() {
        val projection = AndroidGatewayProjection()
        projection.acceptConversationMviEventForTest(
            SharedMviEvent(
                sequence = 1,
                transactionId = "bad-operation:1",
                domain = "conversation",
                kind = "transition",
                statePayloadJson =
                    """{"schema":1,"sessionId":"session-a","operations":[{"kind":"move","itemId":"missing"}],"replacesAll":false,"lastSequence":1}"""
            )
        )
        assertTrue(projection.snapshot().conversation.isEmpty())
        assertEquals("conversation-adapter-failed", projection.snapshot().lastError)
        projection.acceptConversationMviEventForTest(
            SharedMviEvent(
                sequence = 1,
                transactionId = "after-bad-operation:1",
                domain = "conversation",
                kind = "transition",
                statePayloadJson =
                    """{"schema":1,"sessionId":"session-a","operations":[],"replacesAll":false,"lastSequence":1}"""
            )
        )
        assertEquals("conversation-envelope-invalid", projection.snapshot().lastError)
        projection.close()
    }

    @Test
    fun conversationPatchesPreserveGenuiSegmentsAndInsertBeforeHistory() {
        val projection = AndroidGatewayProjection()
        projection.selectSession("session-a")
        val artifactJson = """{"format":"dsh-genui","version":1,"spec":{"items":[{"type":"text","content":"Demo"}]},"state":{},"presentation":{"locale":"en","theme":"light"}}"""
        val contentVersion = com.clarklevis.dsh.shared.projection.GenuiBlockParser.contentVersion(artifactJson)
        val escapedArtifact = artifactJson.replace("\"", "\\\"")
        val openText = "Before\\n```dsh-ui\\n$escapedArtifact"
        val openFallback = "```dsh-ui\\n$escapedArtifact"
        val closedFallback = "$openFallback\\n```"
        val openStart = "Before\n".length
        val openEnd = openStart + "```dsh-ui\n$artifactJson".length
        val closedEnd = openStart + "```dsh-ui\n$artifactJson\n```".length

        projection.acceptConversationMviEventForTest(
            SharedMviEvent(
                sequence = 1,
                transactionId = "genui-baseline:1",
                domain = "conversation",
                kind = "transition",
                statePayloadJson =
                    """{"schema":1,"sessionId":"session-a","operations":[],"replacesAll":true,"replacementItems":[{"id":"stream-text-1-1","kind":"ASSISTANT","title":"Agent","text":"$openText","images":[],"isError":false,"epochSeconds":1.0,"segments":[{"type":"markdown","text":"Before\n"},{"type":"genui","id":"stream-text-1-1-genui-0","rawJson":"","state":"STREAMING","fallbackMarkdown":"$openFallback","fallbackStart":$openStart,"fallbackEnd":$openEnd}]}],"lastSequence":0}"""
            )
        )
        projection.acceptConversationMviEventForTest(
            SharedMviEvent(
                sequence = 2,
                transactionId = "genui-append:2",
                domain = "conversation",
                kind = "transition",
                statePayloadJson =
                    """{"schema":1,"sessionId":"session-a","operations":[{"kind":"append-text","itemId":"stream-text-1-1","delta":"\n```","epochSeconds":2.0,"segmentChanges":[{"index":1,"segment":{"type":"genui","id":"stream-text-1-1-genui-0","rawJson":"$escapedArtifact","state":"READY","fallbackMarkdown":"$closedFallback","fallbackStart":$openStart,"fallbackEnd":$closedEnd,"contentVersion":"$contentVersion","interactive":false}}]}],"replacesAll":false,"lastSequence":1}"""
            )
        )
        projection.acceptConversationMviEventForTest(
            SharedMviEvent(
                sequence = 3,
                transactionId = "genui-append-tail:3",
                domain = "conversation",
                kind = "transition",
                statePayloadJson =
                    """{"schema":1,"sessionId":"session-a","operations":[{"kind":"append-text","itemId":"stream-text-1-1","delta":"\nAfter","epochSeconds":3.0,"segmentChanges":[{"index":2,"segment":{"type":"markdown","text":"After"}}]}],"replacesAll":false,"lastSequence":2}"""
            )
        )
        projection.acceptConversationMviEventForTest(
            SharedMviEvent(
                sequence = 4,
                transactionId = "genui-insert-before:4",
                domain = "conversation",
                kind = "transition",
                statePayloadJson =
                    """{"schema":1,"sessionId":"session-a","operations":[{"kind":"insert-before","itemId":"stream-text-1-1","item":{"id":"older-user","kind":"USER","title":"You","text":"older","images":[],"isError":false,"epochSeconds":0.5}}],"replacesAll":false,"lastSequence":3}"""
            )
        )

        val items = projection.snapshot().conversation
        assertEquals(listOf("older-user", "stream-text-1-1"), items.map { it.id })
        val block = items.last().segments!!.filterIsInstance<com.clarklevis.dsh.shared.projection.GenuiSegment>().single()
        assertEquals(com.clarklevis.dsh.shared.projection.GenuiSegmentState.READY, block.state)
        assertEquals("After", (items.last().segments!!.last() as com.clarklevis.dsh.shared.projection.MarkdownSegment).text)
        assertEquals(null, projection.snapshot().lastError)
        projection.close()
    }

    @Test
    fun conversationPatchesRejectUserSegmentsAndExtraOperationFields() {
        val invalidPayloads = listOf(
            """{"schema":1,"sessionId":"session-a","operations":[],"replacesAll":true,"replacementItems":[{"id":"user-1","kind":"USER","title":"You","text":"hi","images":[],"isError":false,"epochSeconds":1.0,"segments":[{"type":"markdown","text":"hi"}]}],"lastSequence":0}""",
            """{"schema":1,"sessionId":"session-a","operations":[{"kind":"insert","item":{"id":"u2","kind":"USER","title":"You","text":"hi","images":[],"isError":false,"epochSeconds":1.0},"epochSeconds":1.0}],"replacesAll":false,"lastSequence":1}""",
            """{"schema":1,"sessionId":"session-a","operations":[{"kind":"remove","itemId":"reply","epochSeconds":2.0}],"replacesAll":false,"lastSequence":1}"""
        )
        invalidPayloads.forEach { badPayload ->
            val projection = AndroidGatewayProjection()
            projection.acceptConversationMviEventForTest(
                SharedMviEvent(
                    sequence = 1,
                    transactionId = "bad-transaction:1",
                    domain = "conversation",
                    kind = "transition",
                    statePayloadJson = badPayload
                )
            )
            assertEquals("conversation-adapter-failed", projection.snapshot().lastError)
            projection.close()
        }
    }

    @Test
    fun oversizedGenuiBlockDegradesCleanlyAndAcceptsSubsequentNormalMessages() {
        val projection = AndroidGatewayProjection()
        projection.selectSession("session-a")
        val oversizedBody = """{"items":[{"type":"text","content":"${"x".repeat(512_001)}"}]}"""
        val oversizedFence = "```dsh-ui\n$oversizedBody\n```"
        val escapedFence = oversizedFence.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val firstRaw = """{"kind":"event","sessionId":"session-a","seq":1,"time":100,"event":{"type":"assistant/message","text":"$escapedFence"}}"""
        projection.acceptFrame(firstRaw, GatewayWireDecoder.decode(firstRaw), "session-a")

        val degraded = projection.snapshot().conversation.single()
        val block = degraded.segments!!.filterIsInstance<com.clarklevis.dsh.shared.projection.GenuiSegment>().single()
        assertEquals(com.clarklevis.dsh.shared.projection.GenuiSegmentState.INVALID, block.state)
        assertEquals("", block.rawJson)
        assertEquals("", block.fallbackMarkdown)
        assertEquals(oversizedFence, com.clarklevis.dsh.shared.projection.GenuiBlockParser.fallbackMarkdown(block, degraded.text))
        assertEquals(null, projection.snapshot().lastError)

        val secondRaw = """{"kind":"event","sessionId":"session-a","seq":2,"time":101,"event":{"type":"assistant/message","text":"Normal follow-up"}}"""
        projection.acceptFrame(secondRaw, GatewayWireDecoder.decode(secondRaw), "session-a")
        assertEquals(2, projection.snapshot().conversation.size)
        assertEquals("Normal follow-up", projection.snapshot().conversation.last().text)
        assertEquals(null, projection.snapshot().lastError)
        projection.close()
    }

    @Test
    fun malformedActiveStreamBatchRollsBackSoSubsequentChunkAndSnapshotKeepAdapterAlive() {
        val projection = AndroidGatewayProjection()
        projection.selectSession("session-a")
        fun accept(raw: String) = projection.acceptFrame(raw, GatewayWireDecoder.decode(raw), "session-a")
        accept("""{"kind":"hello","historyFormatVersion":4,"capabilities":["assistant-stream-v1"]}""")
        accept("""{"kind":"subscribed","sessionId":"session-a","subscriptionId":"sub-1"}""")
        // Active stream snapshot contains valid chunk #1 followed by malformed chunk #2 (object `type`)
        accept(
            """{"kind":"session-snapshot","sessionId":"session-a","subscriptionId":"sub-1","streamId":"stream-1","historyFormatVersion":4,"cursor":1,"events":[{"type":"user/message","seq":1,"time":100,"data":{"content":[{"type":"text","text":"Hi"}]}}],"hasMore":false,"assistantStream":{"revision":1,"activeAttempt":{"attemptId":"att-1","nextIndex":2,"stream":[{"type":"chunk","time":101,"chunk":{"type":"text-delta","index":0,"text":"First"}},{"type":"chunk","time":102,"chunk":{"type":{"bad":true},"text":"boom"}}]}}}"""
        )
        assertEquals(listOf("Hi"), projection.snapshot().conversation.map { it.text })
        assertEquals("conversation-assistant-chunks-failed", projection.snapshot().lastError)

        // Subsequent valid delta on the exact same attempt/index must insert cleanly rather than fail-closing the adapter
        accept(
            """{"kind":"assistant-stream","sessionId":"session-a","subscriptionId":"sub-1","streamId":"stream-1","frame":{"type":"chunk","revision":2,"attemptId":"att-1","index":2,"time":103,"chunk":{"type":"text-delta","index":0,"text":"Recovered"}}}"""
        )
        assertEquals(listOf("Hi", "Recovered"), projection.snapshot().conversation.map { it.text })
        assertNotEquals("conversation-adapter-failed", projection.snapshot().lastError)

        // Subsequent normal session-snapshot still installs cleanly without conversation-envelope-invalid
        accept(
            """{"kind":"session-snapshot","sessionId":"session-a","subscriptionId":"sub-1","streamId":"stream-2","historyFormatVersion":4,"cursor":2,"events":[{"type":"user/message","seq":1,"time":100,"data":{"content":[{"type":"text","text":"Hi"}]}},{"type":"assistant/message","seq":2,"time":104,"data":{"message":{"content":[{"type":"text","text":"Final reply"}]}}}],"hasMore":false,"assistantStream":{"revision":0}}"""
        )
        assertEquals(listOf("Hi", "Final reply"), projection.snapshot().conversation.map { it.text })
        assertNotEquals("conversation-adapter-failed", projection.snapshot().lastError)
        assertNotEquals("conversation-envelope-invalid", projection.snapshot().lastError)
        projection.close()
    }

    @Test
    fun stepOnlyFramesIsolateStepsAndFinalOnlyReasoningWithoutTurnPreservesDeliverables() {
        val stepOnly = AndroidGatewayProjection()
        stepOnly.selectSession("session-step")
        fun acceptStep(raw: String) = stepOnly.acceptFrame(raw, GatewayWireDecoder.decode(raw), "session-step")
        acceptStep("""{"kind":"hello","historyFormatVersion":4,"capabilities":["assistant-stream-v1"]}""")
        acceptStep("""{"kind":"subscribed","sessionId":"session-step","subscriptionId":"sub-step"}""")
        acceptStep(
            """{"kind":"session-snapshot","sessionId":"session-step","subscriptionId":"sub-step","streamId":"stream-step","historyFormatVersion":4,"cursor":3,"events":[{"type":"assistant/chunk","seq":1,"time":100,"data":{"step":0,"chunk":{"type":"text-delta","text":"Step zero"}}},{"type":"assistant/message","seq":2,"time":101,"data":{"step":1,"message":{"content":[{"type":"text","text":"Step one"}]}}},{"type":"assistant/chunk","seq":3,"time":102,"data":{"step":0,"chunk":{"type":"text-delta","text":" continues"}}}],"hasMore":false,"assistantStream":{"revision":0}}"""
        )
        assertEquals(listOf("Step zero continues", "Step one"), stepOnly.snapshot().conversation.map { it.text })
        assertEquals(null, stepOnly.snapshot().lastError)
        stepOnly.close()

        val withFiles = AndroidGatewayProjection()
        withFiles.selectSession("session-files")
        fun acceptFiles(raw: String) = withFiles.acceptFrame(raw, GatewayWireDecoder.decode(raw), "session-files")
        acceptFiles("""{"kind":"hello","historyFormatVersion":4,"capabilities":["assistant-stream-v1"]}""")
        acceptFiles("""{"kind":"subscribed","sessionId":"session-files","subscriptionId":"sub-files"}""")
        acceptFiles(
            """{"kind":"session-snapshot","sessionId":"session-files","subscriptionId":"sub-files","streamId":"stream-files","historyFormatVersion":4,"cursor":2,"events":[{"type":"deliverables/presented","seq":1,"time":100,"data":{"turn":6,"files":[{"path":"chart.png","name":"chart.png"}]}},{"type":"assistant/chunk","seq":2,"time":101,"data":{"turn":6,"step":0,"chunk":{"type":"text-delta","text":"Partial"}}}],"hasMore":false,"assistantStream":{"revision":0}}"""
        )
        acceptFiles(
            """{"kind":"event","sessionId":"session-files","subscriptionId":"sub-files","streamId":"stream-files","seq":3,"time":102,"event":{"type":"assistant/message","step":0,"reasoning":"Final reasoning","text":"Final answer"}}"""
        )
        val rows = withFiles.snapshot().conversation
        assertEquals(2, rows.size)
        assertEquals(com.clarklevis.dsh.shared.projection.ConversationItemKind.REASONING, rows[0].kind)
        assertEquals("Final reasoning", rows[0].text)
        assertEquals("stream-text-6-0", rows[1].id)
        assertEquals("Final answer", rows[1].text)
        assertEquals(6, rows[1].turn)
        assertEquals(listOf("chart.png"), rows[1].files.map { it.path })
        assertEquals(listOf("chart.png"), rows[1].knownFiles.map { it.path })
        assertEquals(null, withFiles.snapshot().lastError)
        withFiles.close()
    }

    @Test
    fun gatewayFramesHandleMixedTurnFallbackCrossTurnReasoningAndLateToolChunks() {
        // 1. Conflicting same-turn step (6,1) does not block unique compatible step-only stream (null,0)
        val mixed = AndroidGatewayProjection()
        mixed.selectSession("session-mixed")
        fun acceptMixed(raw: String) = mixed.acceptFrame(raw, GatewayWireDecoder.decode(raw), "session-mixed")
        acceptMixed("""{"kind":"hello","historyFormatVersion":4,"capabilities":["assistant-stream-v1"]}""")
        acceptMixed("""{"kind":"subscribed","sessionId":"session-mixed","subscriptionId":"sub-m"}""")
        acceptMixed(
            """{"kind":"session-snapshot","sessionId":"session-mixed","subscriptionId":"sub-m","streamId":"stream-m","historyFormatVersion":4,"cursor":2,"events":[{"type":"assistant/chunk","seq":1,"time":100,"data":{"step":0,"chunk":{"type":"text-delta","text":"```dsh-ui\n"}}},{"type":"assistant/chunk","seq":2,"time":101,"data":{"turn":6,"step":1,"chunk":{"type":"text-delta","text":"Other step"}}}],"hasMore":false,"assistantStream":{"revision":0}}"""
        )
        acceptMixed(
            """{"kind":"event","sessionId":"session-mixed","subscriptionId":"sub-m","streamId":"stream-m","seq":3,"time":102,"event":{"type":"assistant/message","turn":6,"step":0,"text":"Final step zero"}}"""
        )
        val mixedRows = mixed.snapshot().conversation
        assertEquals(listOf("stream-text-unindexed-0-1", "stream-text-6-1"), mixedRows.map { it.id })
        assertEquals(listOf("Final step zero", "Other step"), mixedRows.map { it.text })
        assertEquals(null, mixedRows[0].segments)
        assertEquals(null, mixed.snapshot().lastError)
        mixed.close()

        // 2. Turn-less final message settling turn 6 text does not delete open turn 7 reasoning
        val crossTurn = AndroidGatewayProjection()
        crossTurn.selectSession("session-cross")
        fun acceptCross(raw: String) = crossTurn.acceptFrame(raw, GatewayWireDecoder.decode(raw), "session-cross")
        acceptCross("""{"kind":"hello","historyFormatVersion":4,"capabilities":["assistant-stream-v1"]}""")
        acceptCross("""{"kind":"subscribed","sessionId":"session-cross","subscriptionId":"sub-c"}""")
        acceptCross(
            """{"kind":"session-snapshot","sessionId":"session-cross","subscriptionId":"sub-c","streamId":"stream-c","historyFormatVersion":4,"cursor":2,"events":[{"type":"assistant/chunk","seq":1,"time":100,"data":{"turn":6,"step":0,"chunk":{"type":"text-delta","text":"Turn six preview"}}},{"type":"assistant/chunk","seq":2,"time":101,"data":{"turn":7,"step":0,"chunk":{"type":"reasoning-delta","text":"Turn seven reasoning"}}}],"hasMore":false,"assistantStream":{"revision":0}}"""
        )
        acceptCross(
            """{"kind":"event","sessionId":"session-cross","subscriptionId":"sub-c","streamId":"stream-c","seq":3,"time":102,"event":{"type":"assistant/message","step":0,"text":"Turn six final"}}"""
        )
        acceptCross(
            """{"kind":"event","sessionId":"session-cross","subscriptionId":"sub-c","streamId":"stream-c","seq":4,"time":103,"event":{"type":"assistant/chunk","turn":7,"step":0,"chunkType":"reasoning-delta","text":" continues"}}"""
        )
        val crossRows = crossTurn.snapshot().conversation
        assertEquals(listOf("Turn six final", "Turn seven reasoning continues"), crossRows.map { it.text })
        assertEquals(com.clarklevis.dsh.shared.projection.ConversationItemKind.REASONING, crossRows[1].kind)
        assertEquals(null, crossTurn.snapshot().lastError)
        crossTurn.close()

        // 3. Late tool-call-delta after fully-indexed assistant/message(6,0) neither creates nor appends to tool rows
        val lateTool = AndroidGatewayProjection()
        lateTool.selectSession("session-tool")
        fun acceptTool(raw: String) = lateTool.acceptFrame(raw, GatewayWireDecoder.decode(raw), "session-tool")
        acceptTool("""{"kind":"hello","historyFormatVersion":4,"capabilities":["assistant-stream-v1"]}""")
        acceptTool("""{"kind":"subscribed","sessionId":"session-tool","subscriptionId":"sub-t"}""")
        acceptTool(
            """{"kind":"session-snapshot","sessionId":"session-tool","subscriptionId":"sub-t","streamId":"stream-t","historyFormatVersion":4,"cursor":2,"events":[{"type":"assistant/chunk","seq":1,"time":100,"data":{"turn":6,"step":0,"chunk":{"type":"tool-call-delta","id":"existing-tool","name":"run_code","argumentsDelta":"early"}}},{"type":"assistant/message","seq":2,"time":101,"data":{"turn":6,"step":0,"message":{"content":[{"type":"text","text":"Done"}]}}}],"hasMore":false,"assistantStream":{"revision":0}}"""
        )
        acceptTool(
            """{"kind":"event","sessionId":"session-tool","subscriptionId":"sub-t","streamId":"stream-t","seq":3,"time":102,"event":{"type":"assistant/chunk","turn":6,"step":0,"chunkType":"tool-call-delta","tool":{"id":"late-tool","name":"run_code","argumentsDelta":"late args"}}}"""
        )
        acceptTool(
            """{"kind":"event","sessionId":"session-tool","subscriptionId":"sub-t","streamId":"stream-t","seq":4,"time":103,"event":{"type":"assistant/chunk","turn":6,"step":0,"chunkType":"tool-call-delta","tool":{"id":"existing-tool","name":"run_code","argumentsDelta":" more"}}}"""
        )
        assertEquals(listOf("early", "Done"), lateTool.snapshot().conversation.map { it.text })
        assertEquals(null, lateTool.snapshot().lastError)
        lateTool.close()

        // 4. Explicit-turn final message omitting step blocks late step-less tool/text/reasoning deltas,
        // while still accepting another explicit step in turn 6 and turn 7 deltas
        val stepLessFinal = AndroidGatewayProjection()
        stepLessFinal.selectSession("session-stepless")
        fun acceptStepLess(raw: String) = stepLessFinal.acceptFrame(raw, GatewayWireDecoder.decode(raw), "session-stepless")
        acceptStepLess("""{"kind":"hello","historyFormatVersion":4,"capabilities":["assistant-stream-v1"]}""")
        acceptStepLess("""{"kind":"subscribed","sessionId":"session-stepless","subscriptionId":"sub-sl"}""")
        acceptStepLess(
            """{"kind":"session-snapshot","sessionId":"session-stepless","subscriptionId":"sub-sl","streamId":"stream-sl","historyFormatVersion":4,"cursor":2,"events":[{"type":"assistant/chunk","seq":1,"time":100,"data":{"turn":6,"chunk":{"type":"tool-call-delta","id":"existing","name":"run_code","argumentsDelta":"early"}}},{"type":"assistant/message","seq":2,"time":101,"data":{"turn":6,"message":{"content":[{"type":"text","text":"Done"}]}}}],"hasMore":false,"assistantStream":{"revision":0}}"""
        )
        acceptStepLess(
            """{"kind":"event","sessionId":"session-stepless","subscriptionId":"sub-sl","streamId":"stream-sl","seq":3,"time":102,"event":{"type":"assistant/chunk","turn":6,"chunkType":"tool-call-delta","tool":{"id":"existing","name":"run_code","argumentsDelta":" more"}}}"""
        )
        acceptStepLess(
            """{"kind":"event","sessionId":"session-stepless","subscriptionId":"sub-sl","streamId":"stream-sl","seq":4,"time":103,"event":{"type":"assistant/chunk","turn":6,"chunkType":"tool-call-delta","tool":{"id":"late","name":"run_code","argumentsDelta":"late args"}}}"""
        )
        acceptStepLess(
            """{"kind":"event","sessionId":"session-stepless","subscriptionId":"sub-sl","streamId":"stream-sl","seq":5,"time":104,"event":{"type":"assistant/chunk","turn":6,"chunkType":"text-delta","text":"```dsh-ui\n"}}"""
        )
        acceptStepLess(
            """{"kind":"event","sessionId":"session-stepless","subscriptionId":"sub-sl","streamId":"stream-sl","seq":6,"time":105,"event":{"type":"assistant/chunk","turn":6,"chunkType":"reasoning-delta","text":"late reasoning"}}"""
        )
        acceptStepLess(
            """{"kind":"event","sessionId":"session-stepless","subscriptionId":"sub-sl","streamId":"stream-sl","seq":7,"time":106,"event":{"type":"assistant/chunk","turn":6,"step":1,"chunkType":"text-delta","text":"Turn 6 step 1"}}"""
        )
        acceptStepLess(
            """{"kind":"event","sessionId":"session-stepless","subscriptionId":"sub-sl","streamId":"stream-sl","seq":8,"time":107,"event":{"type":"assistant/chunk","turn":7,"chunkType":"text-delta","text":"Next turn"}}"""
        )
        assertEquals(listOf("early", "Done", "Turn 6 step 1", "Next turn"), stepLessFinal.snapshot().conversation.map { it.text })
        assertEquals(null, stepLessFinal.snapshot().lastError)
        stepLessFinal.close()

        // 5. Turn-less final message (both step=0 and step omitted) blocks late tool/text/reasoning deltas
        // within the same reply, and re-enables the same index once a new user/message begins the next reply
        for (stepField in listOf("\"step\":0,", "")) {
            val turnLess = AndroidGatewayProjection()
            turnLess.selectSession("session-turnless")
            fun acceptTurnLess(raw: String) = turnLess.acceptFrame(raw, GatewayWireDecoder.decode(raw), "session-turnless")
            acceptTurnLess("""{"kind":"hello","historyFormatVersion":4,"capabilities":["assistant-stream-v1"]}""")
            acceptTurnLess("""{"kind":"subscribed","sessionId":"session-turnless","subscriptionId":"sub-tl"}""")
            acceptTurnLess(
                """{"kind":"session-snapshot","sessionId":"session-turnless","subscriptionId":"sub-tl","streamId":"stream-tl","historyFormatVersion":4,"cursor":4,"events":[{"type":"user/message","seq":1,"time":100,"data":{"content":[{"type":"text","text":"Question"}]}},{"type":"assistant/chunk","seq":2,"time":101,"data":{${stepField}"chunk":{"type":"tool-call-delta","id":"existing","name":"run_code","argumentsDelta":"early"}}},{"type":"assistant/chunk","seq":3,"time":102,"data":{${stepField}"chunk":{"type":"text-delta","text":"Preview"}}},{"type":"assistant/message","seq":4,"time":103,"data":{${stepField}"message":{"content":[{"type":"text","text":"Done"}]}}}],"hasMore":false,"assistantStream":{"revision":0}}"""
            )
            acceptTurnLess(
                """{"kind":"event","sessionId":"session-turnless","subscriptionId":"sub-tl","streamId":"stream-tl","seq":5,"time":104,"event":{"type":"assistant/chunk",${stepField}"chunkType":"tool-call-delta","tool":{"id":"existing","name":"run_code","argumentsDelta":" more"}}}"""
            )
            acceptTurnLess(
                """{"kind":"event","sessionId":"session-turnless","subscriptionId":"sub-tl","streamId":"stream-tl","seq":6,"time":105,"event":{"type":"assistant/chunk",${stepField}"chunkType":"tool-call-delta","tool":{"id":"new","name":"run_code","argumentsDelta":"late args"}}}"""
            )
            acceptTurnLess(
                """{"kind":"event","sessionId":"session-turnless","subscriptionId":"sub-tl","streamId":"stream-tl","seq":7,"time":106,"event":{"type":"assistant/chunk",${stepField}"chunkType":"text-delta","text":"```dsh-ui\n"}}"""
            )
            acceptTurnLess(
                """{"kind":"event","sessionId":"session-turnless","subscriptionId":"sub-tl","streamId":"stream-tl","seq":8,"time":107,"event":{"type":"assistant/chunk",${stepField}"chunkType":"reasoning-delta","text":"late reasoning"}}"""
            )
            assertEquals(listOf("Question", "early", "Done"), turnLess.snapshot().conversation.map { it.text })

            acceptTurnLess(
                """{"kind":"event","sessionId":"session-turnless","subscriptionId":"sub-tl","streamId":"stream-tl","seq":9,"time":108,"event":{"type":"user/message","text":"Next question"}}"""
            )
            acceptTurnLess(
                """{"kind":"event","sessionId":"session-turnless","subscriptionId":"sub-tl","streamId":"stream-tl","seq":10,"time":109,"event":{"type":"assistant/chunk",${stepField}"chunkType":"text-delta","text":"Next preview"}}"""
            )
            assertEquals(listOf("Question", "early", "Done", "Next question", "Next preview"), turnLess.snapshot().conversation.map { it.text })
            acceptTurnLess(
                """{"kind":"event","sessionId":"session-turnless","subscriptionId":"sub-tl","streamId":"stream-tl","seq":11,"time":110,"event":{"type":"assistant/message",${stepField}"text":"Next done"}}"""
            )
            assertEquals(listOf("Question", "early", "Done", "Next question", "Next done"), turnLess.snapshot().conversation.map { it.text })
            assertEquals(null, turnLess.snapshot().lastError)
            turnLess.close()
        }

        // 6. Parallel tool/result and turn/end do NOT re-open finalized step=0 for late chunks; step=1 and post-user step=0 both work
        val boundaryCases = listOf(
            Pair("""{"type":"tool/result","step":0,"callId":"existing","output":"First result"}""", listOf("Question", "early", "Done", "First result")),
            Pair("""{"type":"turn/end","reason":"done"}""", listOf("Question", "early", "Done"))
        )
        for ((boundaryEventJson, expectedAfterLate) in boundaryCases) {
            val guarded = AndroidGatewayProjection()
            guarded.selectSession("session-boundary")
            fun acceptGuarded(raw: String) = guarded.acceptFrame(raw, GatewayWireDecoder.decode(raw), "session-boundary")
            acceptGuarded("""{"kind":"hello","historyFormatVersion":4,"capabilities":["assistant-stream-v1"]}""")
            acceptGuarded("""{"kind":"subscribed","sessionId":"session-boundary","subscriptionId":"sub-b"}""")
            acceptGuarded(
                """{"kind":"session-snapshot","sessionId":"session-boundary","subscriptionId":"sub-b","streamId":"stream-b","historyFormatVersion":4,"cursor":4,"events":[{"type":"user/message","seq":1,"time":100,"data":{"content":[{"type":"text","text":"Question"}]}},{"type":"assistant/chunk","seq":2,"time":101,"data":{"step":0,"chunk":{"type":"tool-call-delta","id":"existing","name":"run_code","argumentsDelta":"early"}}},{"type":"assistant/chunk","seq":3,"time":102,"data":{"step":0,"chunk":{"type":"text-delta","text":"Preview"}}},{"type":"assistant/message","seq":4,"time":103,"data":{"step":0,"message":{"content":[{"type":"text","text":"Done"},{"type":"tool-call","id":"existing","name":"run_code","arguments":{}},{"type":"tool-call","id":"pending","name":"run_code","arguments":{}}]}}}],"hasMore":false,"assistantStream":{"revision":0}}"""
            )
            acceptGuarded(
                """{"kind":"event","sessionId":"session-boundary","subscriptionId":"sub-b","streamId":"stream-b","seq":5,"time":104,"event":$boundaryEventJson}"""
            )
            acceptGuarded(
                """{"kind":"event","sessionId":"session-boundary","subscriptionId":"sub-b","streamId":"stream-b","seq":6,"time":105,"event":{"type":"assistant/chunk","step":0,"chunkType":"tool-call-delta","tool":{"id":"existing","name":"run_code","argumentsDelta":" more"}}}"""
            )
            acceptGuarded(
                """{"kind":"event","sessionId":"session-boundary","subscriptionId":"sub-b","streamId":"stream-b","seq":7,"time":106,"event":{"type":"assistant/chunk","step":0,"chunkType":"tool-call-delta","tool":{"id":"new","name":"run_code","argumentsDelta":"late args"}}}"""
            )
            acceptGuarded(
                """{"kind":"event","sessionId":"session-boundary","subscriptionId":"sub-b","streamId":"stream-b","seq":8,"time":107,"event":{"type":"assistant/chunk","step":0,"chunkType":"text-delta","text":"```dsh-ui\n"}}"""
            )
            acceptGuarded(
                """{"kind":"event","sessionId":"session-boundary","subscriptionId":"sub-b","streamId":"stream-b","seq":9,"time":108,"event":{"type":"assistant/chunk","step":0,"chunkType":"reasoning-delta","text":"late reasoning"}}"""
            )
            assertEquals(expectedAfterLate, guarded.snapshot().conversation.map { it.text })

            acceptGuarded(
                """{"kind":"event","sessionId":"session-boundary","subscriptionId":"sub-b","streamId":"stream-b","seq":10,"time":109,"event":{"type":"assistant/chunk","step":1,"chunkType":"text-delta","text":"Step one"}}"""
            )
            assertEquals(expectedAfterLate + "Step one", guarded.snapshot().conversation.map { it.text })

            acceptGuarded(
                """{"kind":"event","sessionId":"session-boundary","subscriptionId":"sub-b","streamId":"stream-b","seq":11,"time":110,"event":{"type":"user/message","text":"Next question"}}"""
            )
            acceptGuarded(
                """{"kind":"event","sessionId":"session-boundary","subscriptionId":"sub-b","streamId":"stream-b","seq":12,"time":111,"event":{"type":"assistant/chunk","step":0,"chunkType":"text-delta","text":"Next preview"}}"""
            )
            assertEquals(expectedAfterLate + listOf("Step one", "Next question", "Next preview"), guarded.snapshot().conversation.map { it.text })
            assertEquals(null, guarded.snapshot().lastError)
            guarded.close()
        }

        // 7. Blocking ask_user_question tool/result keeps step=0 blocked for late chunks, while delayed user-question-reply starts a new turn
        val blockingAsk = AndroidGatewayProjection()
        blockingAsk.selectSession("session-ask-block")
        fun acceptAsk(raw: String) = blockingAsk.acceptFrame(raw, GatewayWireDecoder.decode(raw), "session-ask-block")
        acceptAsk("""{"kind":"hello","historyFormatVersion":4,"capabilities":["assistant-stream-v1"]}""")
        acceptAsk("""{"kind":"subscribed","sessionId":"session-ask-block","subscriptionId":"sub-ab"}""")
        acceptAsk(
            """{"kind":"session-snapshot","sessionId":"session-ask-block","subscriptionId":"sub-ab","streamId":"stream-ab","historyFormatVersion":4,"cursor":4,"events":[{"type":"user/message","seq":1,"time":100,"data":{"content":[{"type":"text","text":"Question"}]}},{"type":"assistant/chunk","seq":2,"time":101,"data":{"step":0,"chunk":{"type":"tool-call-delta","id":"parallel","name":"run_code","argumentsDelta":"early"}}},{"type":"assistant/chunk","seq":3,"time":102,"data":{"step":0,"chunk":{"type":"text-delta","text":"Preview"}}},{"type":"assistant/message","seq":4,"time":103,"data":{"step":0,"message":{"content":[{"type":"text","text":"Done"},{"type":"tool-call","id":"parallel","name":"run_code","arguments":{}},{"type":"tool-call","id":"ask","name":"ask_user_question","arguments":{"questions":[{"id":"q","question":"Which option?"}]}}]}}}],"hasMore":false,"assistantStream":{"revision":0}}"""
        )
        acceptAsk(
            """{"kind":"event","sessionId":"session-ask-block","subscriptionId":"sub-ab","streamId":"stream-ab","seq":5,"time":104,"event":{"type":"tool/result","step":0,"callId":"ask","output":"{\"answers\":[{\"id\":\"q\",\"selected\":[\"A\"]}]}"}}"""
        )
        acceptAsk(
            """{"kind":"event","sessionId":"session-ask-block","subscriptionId":"sub-ab","streamId":"stream-ab","seq":6,"time":105,"event":{"type":"assistant/chunk","step":0,"chunkType":"tool-call-delta","tool":{"id":"parallel","name":"run_code","argumentsDelta":" more"}}}"""
        )
        acceptAsk(
            """{"kind":"event","sessionId":"session-ask-block","subscriptionId":"sub-ab","streamId":"stream-ab","seq":7,"time":106,"event":{"type":"assistant/chunk","step":0,"chunkType":"tool-call-delta","tool":{"id":"new","name":"run_code","argumentsDelta":"late args"}}}"""
        )
        acceptAsk(
            """{"kind":"event","sessionId":"session-ask-block","subscriptionId":"sub-ab","streamId":"stream-ab","seq":8,"time":107,"event":{"type":"assistant/chunk","step":0,"chunkType":"text-delta","text":"```dsh-ui\n"}}"""
        )
        acceptAsk(
            """{"kind":"event","sessionId":"session-ask-block","subscriptionId":"sub-ab","streamId":"stream-ab","seq":9,"time":108,"event":{"type":"assistant/chunk","step":0,"chunkType":"reasoning-delta","text":"late reasoning"}}"""
        )
        acceptAsk(
            """{"kind":"event","sessionId":"session-ask-block","subscriptionId":"sub-ab","streamId":"stream-ab","seq":10,"time":109,"event":{"type":"tool/result","step":0,"callId":"parallel","output":"Parallel done"}}"""
        )
        acceptAsk(
            """{"kind":"event","sessionId":"session-ask-block","subscriptionId":"sub-ab","streamId":"stream-ab","seq":11,"time":110,"event":{"type":"assistant/chunk","step":1,"chunkType":"text-delta","text":"Step one"}}"""
        )
        assertEquals(
            listOf("Question", "early", "Done", """{"answers":[{"id":"q","selected":["A"]}]}""", "Which option?\nA", "Parallel done", "Step one"),
            blockingAsk.snapshot().conversation.map { it.text }
        )
        assertEquals(null, blockingAsk.snapshot().lastError)
        blockingAsk.close()

        val delayedAsk = AndroidGatewayProjection()
        delayedAsk.selectSession("session-ask-delay")
        fun acceptDelayed(raw: String) = delayedAsk.acceptFrame(raw, GatewayWireDecoder.decode(raw), "session-ask-delay")
        acceptDelayed("""{"kind":"hello","historyFormatVersion":4,"capabilities":["assistant-stream-v1"]}""")
        acceptDelayed("""{"kind":"subscribed","sessionId":"session-ask-delay","subscriptionId":"sub-ad"}""")
        acceptDelayed(
            """{"kind":"session-snapshot","sessionId":"session-ask-delay","subscriptionId":"sub-ad","streamId":"stream-ad","historyFormatVersion":4,"cursor":2,"events":[{"type":"user/message","seq":1,"time":100,"data":{"content":[{"type":"text","text":"Question"}]}},{"type":"assistant/message","seq":2,"time":101,"data":{"step":0,"message":{"content":[{"type":"text","text":"Done"},{"type":"tool-call","id":"ask2","name":"ask_user_question","arguments":{"questions":[{"id":"q","question":"Which option?"}]}}]}}}],"hasMore":false,"assistantStream":{"revision":0}}"""
        )
        acceptDelayed(
            """{"kind":"event","sessionId":"session-ask-delay","subscriptionId":"sub-ad","streamId":"stream-ad","seq":3,"time":102,"event":{"type":"user/message","source":"user-question-reply","text":"{\"kind\":\"answer_to_pending_question\",\"tool\":\"ask_user_question\",\"callId\":\"ask2\",\"answers\":[{\"id\":\"q\",\"selected\":[\"B\"]}]}"}}"""
        )
        acceptDelayed(
            """{"kind":"event","sessionId":"session-ask-delay","subscriptionId":"sub-ad","streamId":"stream-ad","seq":4,"time":103,"event":{"type":"assistant/chunk","step":0,"chunkType":"text-delta","text":"Next reply"}}"""
        )
        assertEquals(
            listOf("Question", "Done", "Which option?\nB", "Next reply"),
            delayedAsk.snapshot().conversation.map { it.text }
        )
        assertEquals(null, delayedAsk.snapshot().lastError)
        delayedAsk.close()
    }

    @Test
    fun searchFragmentValidatesGenuiCopyAndExpandActionsWhileBlockingSend() {
        val projection = AndroidGatewayProjection()
        projection.selectSession("session-a")
        val spec = """{"type":"button","label":"Copy","action":"copy","text":"Copied from search"}"""
        val fence = "```dsh-ui\n$spec\n```"
        val oldEvent = com.clarklevis.dsh.shared.protocol.SessionEvent(
            "session-a",
            1,
            10.0,
            com.clarklevis.dsh.shared.protocol.GatewayEvent("assistant/message", text = fence)
        )
        val window = com.clarklevis.dsh.shared.facade.CachedHistoryWindow(
            sessionId = "session-a",
            historyFormatVersion = null,
            hasMore = false,
            nextBeforeSequence = null,
            events = listOf(oldEvent)
        )
        assertTrue(projection.showSearchFragment("session-a", 1, window))
        val block = projection.snapshot().conversation.single().segments!!
            .filterIsInstance<com.clarklevis.dsh.shared.projection.GenuiSegment>()
            .single()

        val copyEffect = projection.resolveGenuiAction(
            com.clarklevis.dsh.shared.projection.GenuiActionIntent(
                sessionId = "session-a",
                activeSessionId = "session-a",
                blockId = block.id,
                contentVersion = block.contentVersion,
                actionJson = """{"version":1,"action":"copy","payload":{"text":"Copied from search"}}""",
                isSearchContext = true
            )
        )
        assertEquals(com.clarklevis.dsh.shared.projection.GenuiActionType.COPY_TEXT, copyEffect?.type)
        assertEquals("Copied from search", copyEffect?.text)

        val blockedSend = projection.resolveGenuiAction(
            com.clarklevis.dsh.shared.projection.GenuiActionIntent(
                sessionId = "session-a",
                activeSessionId = "session-a",
                blockId = block.id,
                contentVersion = block.contentVersion,
                actionJson = """{"version":1,"action":"submit","payload":{"label":"Go"}}""",
                isSearchContext = true
            )
        )
        assertEquals(null, blockedSend)

        // Normal-context intent must be rejected while search fragment is displayed
        val mismatchedContext = projection.resolveGenuiAction(
            com.clarklevis.dsh.shared.projection.GenuiActionIntent(
                sessionId = "session-a",
                activeSessionId = "session-a",
                blockId = block.id,
                contentVersion = block.contentVersion,
                actionJson = """{"version":1,"action":"copy","payload":{"text":"Copied from search"}}""",
                isSearchContext = false
            )
        )
        assertEquals(null, mismatchedContext)
        projection.close()
    }

    @Test
    fun conversationPatchesRejectInvalidAppendBoundsAtomically() {
        val oversizedRaw = "x".repeat(
            com.clarklevis.dsh.shared.projection.GenuiBlockParser.MAX_JSON_CHARS + 1
        )
        val invalidOperations = listOf(
            """{"kind":"append-text","itemId":"reply","delta":"","epochSeconds":2.0}""",
            """{"kind":"append-text","itemId":"reply","delta":"x"}""",
            """{"kind":"append-text","itemId":"reply","delta":"x","epochSeconds":NaN}""",
            """{"kind":"append-text","itemId":"reply","delta":"x","epochSeconds":2.0,"segments":[{"type":"genui","id":"reply-genui-0","rawJson":"$oversizedRaw","state":"STREAMING","fallbackMarkdown":"```dsh-ui"}]}""",
            """{"kind":"append-text","itemId":"reply","delta":"\n```","epochSeconds":2.0,"segments":[{"type":"genui","id":"reply-genui-0","rawJson":"{}","state":"READY","fallbackMarkdown":"Before\n```","fallbackStart":0,"fallbackEnd":10,"contentVersion":"${com.clarklevis.dsh.shared.projection.GenuiBlockParser.contentVersion("{}")}","interactive":false}]}"""
        )
        invalidOperations.forEach { operation ->
            val projection = AndroidGatewayProjection()
            projection.selectSession("session-a")
            projection.acceptConversationMviEventForTest(
                SharedMviEvent(
                    sequence = 1,
                    transactionId = "bounds-baseline:1",
                    domain = "conversation",
                    kind = "transition",
                    statePayloadJson =
                        """{"schema":1,"sessionId":"session-a","operations":[],"replacesAll":true,"replacementItems":[{"id":"reply","kind":"ASSISTANT","title":"Agent","text":"Before","images":[],"isError":false,"epochSeconds":1.0}],"lastSequence":0}"""
                )
            )
            projection.acceptConversationMviEventForTest(
                SharedMviEvent(
                    sequence = 2,
                    transactionId = "bounds-append:2",
                    domain = "conversation",
                    kind = "transition",
                    statePayloadJson =
                        """{"schema":1,"sessionId":"session-a","operations":[$operation],"replacesAll":false,"lastSequence":1}"""
                )
            )
            assertEquals("Before", projection.snapshot().conversation.single().text)
            assertEquals("conversation-adapter-failed", projection.snapshot().lastError)
            projection.close()
        }
    }

    @Test
    fun perSessionConversationAndHistorySequencesRejectRegressionAndDuplicateAppend() {
        val conversation = AndroidGatewayProjection()
        conversation.acceptConversationMviEventForTest(
            SharedMviEvent(
                sequence = 1,
                transactionId = "conversation-baseline:1",
                domain = "conversation",
                kind = "transition",
                statePayloadJson =
                    """{"schema":1,"sessionId":"session-a","operations":[],"replacesAll":true,"replacementItems":[{"id":"one","kind":"USER","title":"You","text":"one","images":[],"isError":false,"epochSeconds":1.0}],"lastSequence":5}"""
            )
        )
        conversation.selectSession("session-a")
        assertEquals("one", conversation.snapshot().conversation.firstOrNull()?.text)
        conversation.acceptConversationMviEventForTest(
            SharedMviEvent(
                sequence = 2,
                transactionId = "conversation-regression:2",
                domain = "conversation",
                kind = "transition",
                statePayloadJson =
                    """{"schema":1,"sessionId":"session-a","operations":[],"replacesAll":true,"replacementItems":[],"lastSequence":4}"""
            )
        )
        assertEquals("conversation-adapter-failed", conversation.snapshot().lastError)
        assertEquals("one", conversation.snapshot().conversation.firstOrNull()?.text)
        conversation.close()

        val history = AndroidGatewayProjection()
        history.acceptHistoryMviEventForTest(
            SharedMviEvent(
                sequence = 1,
                transactionId = "history-append:1",
                domain = "history",
                kind = "transition",
                statePayloadJson =
                    """{"schema":1,"sessionId":"session-a","eventPatch":{"kind":"append","record":{"sessionId":"session-a","seq":5,"time":1.0,"event":{"type":"user/message","text":"one"}},"index":0},"outcome":"none"}"""
            )
        )
        history.acceptHistoryMviEventForTest(
            SharedMviEvent(
                sequence = 2,
                transactionId = "history-duplicate-append:2",
                domain = "history",
                kind = "transition",
                statePayloadJson =
                    """{"schema":1,"sessionId":"session-a","eventPatch":{"kind":"append","record":{"sessionId":"session-a","seq":5,"time":2.0,"event":{"type":"user/message","text":"duplicate"}},"index":1},"outcome":"none"}"""
            )
        )
        assertEquals("history-adapter-failed", history.snapshot().lastError)
        history.close()

        val replaceRegression = AndroidGatewayProjection()
        replaceRegression.acceptHistoryMviEventForTest(
            SharedMviEvent(
                sequence = 1,
                transactionId = "history-replace-baseline:1",
                domain = "history",
                kind = "transition",
                statePayloadJson =
                    """{"schema":1,"sessionId":"session-a","eventPatch":{"kind":"replace","replacementEvents":[{"sessionId":"session-a","seq":5,"time":1.0,"event":{"type":"user/message","text":"newer"}}]},"outcome":"none"}"""
            )
        )
        replaceRegression.acceptHistoryMviEventForTest(
            SharedMviEvent(
                sequence = 2,
                transactionId = "history-replace-regression:2",
                domain = "history",
                kind = "transition",
                statePayloadJson =
                    """{"schema":1,"sessionId":"session-a","eventPatch":{"kind":"replace","replacementEvents":[{"sessionId":"session-a","seq":4,"time":2.0,"event":{"type":"user/message","text":"older"}}]},"outcome":"none"}"""
            )
        )
        assertEquals("history-adapter-failed", replaceRegression.snapshot().lastError)
        replaceRegression.close()
    }

    private fun event(
        sequence: Long,
        transaction: String,
        kind: String = "transition",
        domain: String = "history"
    ) = SharedMviEvent(
        sequence = sequence,
        transactionId = transaction,
        domain = domain,
        kind = kind,
        statePayloadJson = "{}"
    )
}
