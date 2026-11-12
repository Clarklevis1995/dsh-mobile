package com.clarklevis.dsh.shared.projection

import com.clarklevis.dsh.shared.protocol.JsonValue
import com.clarklevis.dsh.shared.protocol.SessionEvent

/** 从持久化事件还原问答；不依赖当前设备是否提交过回答。 */
internal class QuestionAnswerProjection {
    data class Answer(val text: String?, val replacesUserMessage: Boolean)

    private val questionsByCall = mutableMapOf<Pair<String, String>, List<JsonValue>>()
    private val answeredCalls = mutableSetOf<Pair<String, String>>()

    fun reset() {
        questionsByCall.clear()
        answeredCalls.clear()
    }

    fun fold(record: SessionEvent): Answer? {
        val event = record.event
        fun remember(callId: String?, name: String?, arguments: JsonValue?) {
            if (callId == null || name != "ask_user_question") return
            val questions = runCatching { arguments?.normalizedJsonValue() }.getOrNull()
                ?.get("questions")?.arrayValue ?: return
            if (questions.isNotEmpty() && questions.all {
                    it["id"]?.stringValue != null && it["question"]?.stringValue != null
                }) {
                questionsByCall[record.sessionId to callId] = questions
            }
        }
        when (event.type) {
            "assistant/message" -> event.toolCalls.orEmpty().forEach { remember(it.id, it.name, it.arguments) }
            "tool/call" -> remember(event.callId, event.name, event.arguments)
            "tool/code-dispatch-start", "tool/code-dispatch", "tool/ptc-dispatch" ->
                remember(event.subCallId, event.name, event.arguments)
        }

        val lateReply = event.type == "user/message" && event.source == "user-question-reply"
        val resultCallId = event.subCallId ?: event.callId
        val payload = when {
            lateReply -> parse(event.text)
            event.type in setOf("tool/result", "tool/code-dispatch", "tool/ptc-dispatch") &&
                (record.sessionId to resultCallId) in questionsByCall &&
                event.isError != true && event.error.isNullOrBlank() && event.outputTruncated != true ->
                parse(event.output ?: event.preview)
            else -> null
        } ?: return null
        if (lateReply && (payload["kind"]?.stringValue != "answer_to_pending_question" ||
                payload["tool"]?.stringValue != "ask_user_question")) return null
        val callId = if (lateReply) payload["callId"]?.stringValue
            else resultCallId
        val key = record.sessionId to (callId ?: return null)
        val questions = if (lateReply) payload["questions"]?.arrayValue ?: questionsByCall[key]
            else questionsByCall[key]
        if (questions.isNullOrEmpty()) return null
        val answers = payload["answers"]?.arrayValue ?: return null
        val ids = questions.map { it["id"]?.stringValue ?: return null }
        if (ids.toSet().size != ids.size || answers.size != ids.size ||
            answers.map { it["id"]?.stringValue }.toSet() != ids.toSet()) return null
        val text = questions.map { question ->
            val title = question["question"]?.stringValue ?: return null
            val answer = answers.first { it["id"]?.stringValue == question["id"]?.stringValue }
            val selected = answer["selected"]?.arrayValue ?: return null
            val lines = selected.map { it.stringValue ?: return null } +
                listOfNotNull(answer["custom"]?.stringValue?.takeIf(String::isNotBlank))
            title + "\n" + lines.joinToString("\n").ifEmpty { "—" }
        }.joinToString("\n\n")
        return Answer(text.takeIf { answeredCalls.add(key) }, lateReply)
    }

    private fun parse(text: String?): JsonValue? = text?.let {
        runCatching { JsonValue.StringValue(it).normalizedJsonValue() }.getOrNull()
            ?.takeIf { value -> value.objectValue != null }
    }
}
