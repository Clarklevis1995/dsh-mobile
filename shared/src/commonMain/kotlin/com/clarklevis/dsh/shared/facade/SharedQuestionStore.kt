package com.clarklevis.dsh.shared.facade

import com.clarklevis.dsh.shared.domain.QuestionAction
import com.clarklevis.dsh.shared.domain.QuestionReducer
import com.clarklevis.dsh.shared.domain.QuestionRequestStatus
import com.clarklevis.dsh.shared.domain.QuestionState
import com.clarklevis.dsh.shared.domain.QuestionSubmission
import com.clarklevis.dsh.shared.protocol.GatewayPendingQuestionRequest
import com.clarklevis.dsh.shared.protocol.GatewayQuestionAction
import com.clarklevis.dsh.shared.protocol.GatewayQuestionAnswer
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
data class SharedQuestionStatusSnapshot(
    val kind: String,
    val action: String? = null,
    val failureCode: String? = null,
    val failureArgument: String? = null
)

@Serializable
data class SharedQuestionSnapshot(
    val pendingRequests: List<GatewayPendingQuestionRequest> = emptyList(),
    val requestStatuses: Map<String, SharedQuestionStatusSnapshot> = emptyMap()
)

/** 平台层应且仅应执行一次的 Question I/O 描述。 */
@Serializable
data class SharedQuestionEffect(
    val action: String,
    val rpcId: String,
    val sessionId: String,
    val answers: List<GatewayQuestionAnswer>? = null
)

/**
 * 非抛出的粗粒度桥接结果。Kotlin 在完成快照和 effect 序列化后才提交状态，
 * Swift 因而可以在任何结构化失败上安全地 fail closed。
 */
data class SharedQuestionResult(
    val snapshotJson: String?,
    val effectJson: String?,
    val errorCode: String?,
    val errorMessage: String?
) {
    val isSuccess: Boolean get() = errorCode == null
}

/** iOS Human Question 的唯一业务状态来源；网络发送和后台任务仍由平台 effect 层执行。 */
class SharedQuestionStore {
    private var state = QuestionState()
    private val events = SharedMviEventEmitter("question")

    fun subscribe(observer: SharedMviEventObserver): SharedMviSubscription = try {
        events.subscribe(observer, wireJson.encodeToString(state.toSnapshot()))
    } catch (error: Throwable) {
        events.subscribeError(
            observer,
            "question-subscribe-failed",
            error.message ?: error::class.simpleName ?: "unknown-error"
        )
    }

    fun snapshot(): SharedQuestionResult = try {
        success(state, effect = null, alwaysSnapshot = true, commit = false)
    } catch (error: Throwable) {
        failure("snapshot", error)
    }

    fun reset(): SharedQuestionResult = mutate("reset") {
        Transition(QuestionReducer.reduce(state, QuestionAction.Reset))
    }

    fun requestReceived(requestJson: String): SharedQuestionResult = mutate("request-received") {
        val request = wireJson.decodeFromString<GatewayPendingQuestionRequest>(requestJson)
        Transition(QuestionReducer.reduce(state, QuestionAction.RequestReceived(request)))
    }

    fun submitAnswer(
        rpcId: String,
        answersJson: String,
        isConnected: Boolean
    ): SharedQuestionResult = submit(rpcId, "answer") { request ->
        val answers = wireJson.decodeFromString<List<GatewayQuestionAnswer>>(answersJson)
        val next = QuestionReducer.reduce(
            state,
            QuestionAction.Submit(request, QuestionSubmission.Answer(answers), isConnected)
        )
        val effect = if (
            next.requestStatuses[rpcId] == QuestionRequestStatus.Submitting(GatewayQuestionAction.ANSWER)
        ) {
            SharedQuestionEffect("answer", rpcId, request.sessionId, answers)
        } else {
            null
        }
        Transition(next, effect)
    }

    fun submitCancel(rpcId: String, isConnected: Boolean): SharedQuestionResult =
        submit(rpcId, "cancel") { request ->
            val next = QuestionReducer.reduce(
                state,
                QuestionAction.Submit(request, QuestionSubmission.Cancel, isConnected)
            )
            val effect = if (
                next.requestStatuses[rpcId] == QuestionRequestStatus.Submitting(GatewayQuestionAction.CANCEL)
            ) {
                SharedQuestionEffect("cancel", rpcId, request.sessionId)
            } else {
                null
            }
            Transition(next, effect)
        }

    fun responseReceived(
        rpcId: String,
        action: String,
        accepted: Boolean,
        reason: String?
    ): SharedQuestionResult = mutate("response-received") {
        val parsedAction = action.toQuestionAction()
        Transition(
            QuestionReducer.reduce(
                state,
                QuestionAction.ResponseReceived(rpcId, parsedAction, accepted, reason)
            )
        )
    }

    fun resolved(rpcId: String): SharedQuestionResult = mutate("resolved") {
        Transition(QuestionReducer.reduce(state, QuestionAction.Resolved(rpcId)))
    }

    fun requestFailed(rpcId: String, message: String?): SharedQuestionResult = mutate("request-failed") {
        Transition(QuestionReducer.reduce(state, QuestionAction.RequestFailed(rpcId, message)))
    }

    fun sessionRequestsFailed(sessionId: String, message: String?): SharedQuestionResult =
        mutate("session-requests-failed") {
            Transition(QuestionReducer.reduce(state, QuestionAction.SessionRequestsFailed(sessionId, message)))
        }

    private inline fun submit(
        rpcId: String,
        operation: String,
        transform: (GatewayPendingQuestionRequest) -> Transition
    ): SharedQuestionResult = mutate("submit-$operation") {
        val request = state.pendingRequests.firstOrNull { it.rpcId == rpcId }
            ?: return@mutate Transition(state, errorCode = "question-not-pending")
        // 同一提交在 Submitting/Accepted 期间不再次产生 effect，确保平台 I/O 至多执行一次。
        when (state.requestStatuses[rpcId]) {
            is QuestionRequestStatus.Submitting,
            is QuestionRequestStatus.Accepted -> Transition(state)
            else -> transform(request)
        }
    }

    private inline fun mutate(operation: String, transform: () -> Transition): SharedQuestionResult = try {
        val transition = transform()
        transition.errorCode?.let {
            val message = "No pending question for the supplied rpcId"
            events.emitError("$it:${events.currentSequence + 1}", it, message)
            return SharedQuestionResult(null, null, it, message)
        }
        success(
            next = transition.state,
            effect = transition.effect,
            alwaysSnapshot = transition.state != state,
            commit = true
        )
    } catch (error: Throwable) {
        failure(operation, error)
    }

    private fun success(
        next: QuestionState,
        effect: SharedQuestionEffect?,
        alwaysSnapshot: Boolean,
        commit: Boolean
    ): SharedQuestionResult {
        val snapshotJson = if (alwaysSnapshot) wireJson.encodeToString(next.toSnapshot()) else null
        val effectJson = effect?.let { wireJson.encodeToString(it) }
        if (commit) {
            state = next
            if (snapshotJson != null || effectJson != null) {
                events.emitTransition(
                    transactionId = "question:${events.currentSequence + 1}",
                    statePayloadJson = snapshotJson,
                    effectsJson = effectJson?.let { "[$it]" } ?: "[]"
                )
            }
        }
        return SharedQuestionResult(snapshotJson, effectJson, null, null)
    }

    private fun failure(operation: String, error: Throwable): SharedQuestionResult {
        val code = "question-$operation-failed"
        val message = error.message ?: error::class.simpleName ?: "unknown-error"
        events.emitError("$code:${events.currentSequence + 1}", code, message)
        return SharedQuestionResult(null, null, code, message)
    }

    private data class Transition(
        val state: QuestionState,
        val effect: SharedQuestionEffect? = null,
        val errorCode: String? = null
    )
}

private fun String.toQuestionAction(): GatewayQuestionAction = when (this) {
    "answer" -> GatewayQuestionAction.ANSWER
    "cancel" -> GatewayQuestionAction.CANCEL
    else -> error("Unsupported question action: $this")
}

private fun QuestionState.toSnapshot(): SharedQuestionSnapshot = SharedQuestionSnapshot(
    pendingRequests = pendingRequests,
    requestStatuses = requestStatuses.mapValues { (_, status) -> status.toSnapshot() }
)

private fun QuestionRequestStatus.toSnapshot(): SharedQuestionStatusSnapshot = when (this) {
    QuestionRequestStatus.Idle -> SharedQuestionStatusSnapshot(kind = "idle")
    is QuestionRequestStatus.Submitting -> SharedQuestionStatusSnapshot(
        kind = "submitting",
        action = action.toWireValue()
    )
    is QuestionRequestStatus.Accepted -> SharedQuestionStatusSnapshot(
        kind = "accepted",
        action = action.toWireValue()
    )
    is QuestionRequestStatus.Rejected -> SharedQuestionStatusSnapshot(
        kind = "rejected",
        failureCode = failure.code.name,
        failureArgument = failure.argument
    )
}

private fun GatewayQuestionAction.toWireValue(): String = when (this) {
    GatewayQuestionAction.ANSWER -> "answer"
    GatewayQuestionAction.CANCEL -> "cancel"
}
