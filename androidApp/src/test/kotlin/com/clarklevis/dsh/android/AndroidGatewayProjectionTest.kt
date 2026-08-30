package com.clarklevis.dsh.android

import com.clarklevis.dsh.shared.facade.SharedMviEvent
import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidGatewayProjectionTest {
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
            onHistoryPageRequested = { sessionId, before -> requested += sessionId to before }
        )
        projection.selectSession("session-a")
        projection.selectSession("session-b")
        assertEquals(listOf("session-a" to null, "session-b" to null), requested)

        val history =
            """{"kind":"history","events":[{"type":"user/message","seq":1,"time":100,"data":{"content":[{"type":"text","text":"history-a"}],"source":{"kind":"user"}}}],"hasMore":true,"nextBeforeSeq":0,"bytes":128}"""
        projection.acceptFrame(history, GatewayWireDecoder.decode(history), "session-a")
        assertTrue(projection.snapshot().conversation.isEmpty())
        assertEquals("session-a" to 0, requested.last())
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
        assertEquals(listOf("history-a", "final"), projection.snapshot().conversation.map { it.text })
        projection.close()
    }

    @Test
    fun historyCancellationEndsLoadingSoSelectionCanRequestAgain() {
        val requested = mutableListOf<Pair<String, Int?>>()
        val projection = AndroidGatewayProjection(
            onHistoryPageRequested = { sessionId, before -> requested += sessionId to before }
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
    fun initialHistoryProgressPublishesUntilTheFinalPageCompletes() {
        val projection = AndroidGatewayProjection()
        projection.selectSession("session-a")
        assertTrue(projection.snapshot().selectedHistoryIsLoading)
        assertEquals(0, projection.snapshot().selectedHistoryLoadedEventCount)
        assertEquals(null, projection.snapshot().selectedHistoryTotalEventCount)

        val firstPage =
            """{"kind":"history","events":[{"type":"user/message","seq":1,"time":100,"data":{"content":[{"type":"text","text":"history"}],"source":{"kind":"user"}}}],"hasMore":true,"nextBeforeSeq":0,"bytes":64}"""
        projection.acceptFrame(firstPage, GatewayWireDecoder.decode(firstPage), "session-a")
        assertTrue(projection.snapshot().selectedHistoryIsLoading)
        assertEquals(1, projection.snapshot().selectedHistoryLoadedEventCount)
        assertEquals(null, projection.snapshot().selectedHistoryTotalEventCount)

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
            onHistoryPageRequested = { sessionId, before -> requested += sessionId to before }
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
            onHistoryPageRequested = { sessionId, before -> badEffectRequests += sessionId to before }
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
