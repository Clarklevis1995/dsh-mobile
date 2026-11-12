package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.projection.ConversationItemKind
import com.clarklevis.dsh.shared.projection.ConversationProjector
import com.clarklevis.dsh.shared.protocol.GatewayEvent
import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder
import com.clarklevis.dsh.shared.protocol.JsonValue
import com.clarklevis.dsh.shared.protocol.RawSessionEvent
import com.clarklevis.dsh.shared.protocol.SessionEvent
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QuestionAnswerProjectionTest {
    private val questions = """[{"id":"subject","question":"小样的主角选哪个？"},{"id":"concept","question":"选择概念"}]"""
    private val arguments = """{"questions":$questions}"""
    private val answers = """{"answers":[{"id":"concept","selected":["A","B"],"custom":"补充说明"},{"id":"subject","selected":[],"custom":"这个项目"}]}"""
    private val expected = "小样的主角选哪个？\n这个项目\n\n选择概念\nA\nB\n补充说明"

    private fun quoted(text: String) = JsonValue.StringValue(text).displayText()
    private fun raw(type: String, data: String, seq: Int = 1) = Json.decodeFromString<RawSessionEvent>(
        """{"type":"$type","seq":$seq,"time":100,"data":$data}"""
    ).normalized("session")
    private fun call(id: String = "ask", seq: Int = 1) = raw("tool/call",
        """{"callId":"$id","name":"ask_user_question","arguments":${quoted(arguments)}}""", seq)
    private fun result(text: String = answers, extra: String = "", id: String = "ask", seq: Int = 2) = raw(
        "tool/result", """{"message":{"toolCallId":"$id","content":[{"type":"text","text":${quoted(text)}}]}$extra}""", seq
    )
    private fun late(seq: Int = 3): SessionEvent {
        val payload = """{"kind":"answer_to_pending_question","tool":"ask_user_question","callId":"ask","questions":$questions,"answers":${JsonValue.StringValue(answers).normalizedJsonValue()["answers"]!!.displayText()}}"""
        return raw("user/message", """{"source":{"kind":"user-question-reply","callId":"ask"},"content":[{"type":"text","text":${quoted(payload)}}]}""", seq)
    }

    @Test fun historyAndIncrementalResultsCreateTheSameBubbleAtAnswerPosition() {
        val events = listOf(call(), result(), raw("assistant/message",
            """{"turn":1,"step":2,"message":{"content":[{"type":"text","text":"继续制作"}]}}""", 3))
        val projector = ConversationProjector()
        projector.fold(listOf(events.first()))
        val operations = projector.foldWithOperations(events.drop(1))
        assertEquals(ConversationProjector.make(events), projector.items)
        assertEquals(listOf(ConversationItemKind.TOOL, ConversationItemKind.TOOL_RESULT,
            ConversationItemKind.USER, ConversationItemKind.ASSISTANT), projector.items.map { it.kind })
        assertEquals(expected, projector.items[2].text)
        assertEquals(expected, operations.single { it.item?.kind == ConversationItemKind.USER }.item?.text)
    }

    @Test fun realtimeGatewayFramesUseCallIdsAndStructuredOrStringArguments() {
        for (args in listOf(arguments, quoted(arguments))) {
            fun wire(seq: Int, event: String): SessionEvent {
                val frame = GatewayWireDecoder.decode("""{"kind":"event","sessionId":"session","seq":$seq,"time":100,"event":$event}""")
                return SessionEvent("session", seq, 100.0, frame.event!!)
            }
            val items = ConversationProjector.make(listOf(
                wire(1, """{"type":"tool/call","callId":"ask","name":"ask_user_question","arguments":$args}"""),
                wire(2, """{"type":"tool/result","callId":"ask","preview":"truncated preview","output":${quoted(answers)}}""")
            ))
            assertEquals(expected, items.last().text)
            assertEquals(ConversationItemKind.USER, items.last().kind)
        }
    }

    @Test fun ptcAndCodeDispatchProduceAnswerBubblesWithoutNativeToolCalls() {
        for (type in listOf("tool/ptc-dispatch", "tool/code-dispatch")) {
            val record = raw(type, """{"rootCallId":"run","subCallId":"ask","name":"ask_user_question","arguments":$arguments,"content":[{"type":"text","text":${quoted(answers)}}]}""")
            assertEquals(expected, ConversationProjector.make(listOf(record)).single().text)
        }
    }

    @Test fun lateReplyRestoresQuestionsEvenWhenCallIsOutsideHistoryWindow() {
        val items = ConversationProjector.make(listOf(late()))
        assertEquals(ConversationItemKind.USER, items.single().kind)
        assertEquals(expected, items.single().text)
    }

    @Test fun assistantToolCallsAndNestedResultsRestoreBlockingAnswers() {
        val assistant = raw("assistant/message", """{"turn":1,"step":1,"message":{"content":[{"type":"tool-call","id":"ask","name":"ask_user_question","arguments":$arguments}]}}""")
        val nested = raw("tool/result", """{"message":{"toolCallId":"ask","content":[{"type":"tool-result","content":[{"type":"text","text":${quoted(answers)}}]}]}}""", 2)
        val items = ConversationProjector.make(listOf(assistant, nested))
        assertEquals(expected, items.last().text)
        assertEquals(ConversationItemKind.USER, items.last().kind)
    }

    @Test fun timedOutQuestionsShowOnlyTheRecordedLateAnswer() {
        val projector = ConversationProjector()
        projector.fold(listOf(call(), result("""{"pending":true,"callId":"ask"}""")))
        assertTrue(projector.items.none { it.kind == ConversationItemKind.USER })
        projector.fold(listOf(late()))
        assertEquals(expected, projector.items.last().text)
        assertEquals(1, projector.items.count { it.kind == ConversationItemKind.USER })
    }

    @Test fun concurrentCallsMatchTheirOwnAnswersAndTruncatedWireResultsStayAsTools() {
        val items = ConversationProjector.make(listOf(call(), call("second", 2),
            result(id = "second", seq = 3), result(seq = 4)))
        assertEquals(2, items.count { it.kind == ConversationItemKind.USER })
        val truncated = SessionEvent("session", 2, 100.0, GatewayEvent("tool/result",
            callId = "ask", output = answers, outputTruncated = true))
        assertTrue(ConversationProjector.make(listOf(call(), truncated)).none { it.kind == ConversationItemKind.USER })
    }

    @Test fun pendingFailedMalformedAndUnrelatedResultsKeepOriginalPresentation() {
        val invalid = listOf("not json", """{"pending":true}""", """{"answers":[]}""",
            """{"answers":[{"id":"subject","selected":[]},{"id":"subject","selected":[]}]}""")
        for (text in invalid) {
            assertTrue(ConversationProjector.make(listOf(call(), result(text))).none { it.kind == ConversationItemKind.USER })
        }
        assertTrue(ConversationProjector.make(listOf(call(), result(extra = """, "error":{"message":"cancelled"}""")))
            .none { it.kind == ConversationItemKind.USER })
        assertTrue(ConversationProjector.make(listOf(call(), result(id = "different")))
            .none { it.kind == ConversationItemKind.USER })
        assertTrue(ConversationProjector.make(listOf(result())).none { it.kind == ConversationItemKind.USER })
    }

    @Test fun duplicateAnswersAreSuppressedButDifferentCallsAndSessionsRemainVisible() {
        val projector = ConversationProjector()
        projector.fold(listOf(call(), result(), late()))
        assertEquals(1, projector.items.count { it.kind == ConversationItemKind.USER })
        projector.fold(listOf(call("second", 4), result(id = "second", seq = 5)))
        projector.fold(listOf(call().copy(sessionId = "other"), result().copy(sessionId = "other")))
        assertEquals(3, projector.items.count { it.kind == ConversationItemKind.USER })
        projector.rebuild(listOf(call(), result()))
        assertEquals(1, projector.items.count { it.kind == ConversationItemKind.USER })
    }

    @Test fun skippedAnswersRemainVisibleAndNormalUserJsonIsNotRewritten() {
        val skipped = """{"answers":[{"id":"subject","selected":[]},{"id":"concept","selected":[]}]}"""
        assertEquals("小样的主角选哪个？\n—\n\n选择概念\n—",
            ConversationProjector.make(listOf(call(), result(skipped))).last().text)
        val ordinary = late().copy(event = GatewayEvent("user/message", source = "user", text = answers))
        assertEquals(answers, ConversationProjector.make(listOf(ordinary)).single().text)
    }
}
