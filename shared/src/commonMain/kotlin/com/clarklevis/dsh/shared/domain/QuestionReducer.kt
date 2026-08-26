package com.clarklevis.dsh.shared.domain

import com.clarklevis.dsh.shared.protocol.GatewayPendingQuestionRequest
import com.clarklevis.dsh.shared.protocol.GatewayQuestionAction
import com.clarklevis.dsh.shared.protocol.GatewayQuestionAnswer

enum class QuestionFailureCode {
    DISCONNECTED_ANSWER,
    DISCONNECTED_CANCEL,
    INVALID_ANSWER_ORDER,
    INVALID_OR_DUPLICATE_OPTIONS,
    SINGLE_SELECTION_REQUIRED,
    SERVER_REJECTED
}

data class QuestionFailure(
    val code: QuestionFailureCode,
    val argument: String? = null
)

sealed interface QuestionRequestStatus {
    data object Idle : QuestionRequestStatus
    data class Submitting(val action: GatewayQuestionAction) : QuestionRequestStatus
    data class Accepted(val action: GatewayQuestionAction) : QuestionRequestStatus
    data class Rejected(val failure: QuestionFailure) : QuestionRequestStatus
}

data class QuestionState(
    val pendingRequests: List<GatewayPendingQuestionRequest> = emptyList(),
    val requestStatuses: Map<String, QuestionRequestStatus> = emptyMap()
)

sealed interface QuestionSubmission {
    data class Answer(val answers: List<GatewayQuestionAnswer>) : QuestionSubmission
    data object Cancel : QuestionSubmission
}

sealed interface QuestionAction {
    data object Reset : QuestionAction
    data class RequestReceived(val request: GatewayPendingQuestionRequest) : QuestionAction
    data class Submit(
        val request: GatewayPendingQuestionRequest,
        val submission: QuestionSubmission,
        val isConnected: Boolean
    ) : QuestionAction
    data class ResponseReceived(
        val rpcId: String,
        val action: GatewayQuestionAction,
        val accepted: Boolean,
        val reason: String?
    ) : QuestionAction
    data class Resolved(val rpcId: String) : QuestionAction
    data class RequestFailed(val rpcId: String, val message: String?) : QuestionAction
}

object QuestionReducer {
    fun reduce(state: QuestionState, action: QuestionAction): QuestionState = when (action) {
        QuestionAction.Reset -> QuestionState()
        is QuestionAction.RequestReceived -> {
            val existingIndex = state.pendingRequests.indexOfFirst { it.rpcId == action.request.rpcId }
            if (existingIndex >= 0) {
                state.copy(pendingRequests = state.pendingRequests.toMutableList().apply {
                    this[existingIndex] = action.request
                })
            } else {
                state.copy(
                    pendingRequests = state.pendingRequests + action.request,
                    requestStatuses = state.requestStatuses + (action.request.rpcId to QuestionRequestStatus.Idle)
                )
            }
        }
        is QuestionAction.Submit -> submit(state, action)
        is QuestionAction.ResponseReceived -> when {
            action.accepted -> state.withStatus(action.rpcId, QuestionRequestStatus.Accepted(action.action))
            action.reason == "not-pending" -> state.copy(
                pendingRequests = state.pendingRequests.filterNot { it.rpcId == action.rpcId },
                requestStatuses = state.requestStatuses - action.rpcId
            )
            else -> state.withStatus(
                action.rpcId,
                QuestionRequestStatus.Rejected(
                    QuestionFailure(QuestionFailureCode.SERVER_REJECTED, action.reason ?: "bad-response")
                )
            )
        }
        is QuestionAction.Resolved -> state.copy(
            pendingRequests = state.pendingRequests.filterNot { it.rpcId == action.rpcId },
            requestStatuses = state.requestStatuses - action.rpcId
        )
        is QuestionAction.RequestFailed -> state.withStatus(
            action.rpcId,
            QuestionRequestStatus.Rejected(
                QuestionFailure(QuestionFailureCode.SERVER_REJECTED, action.message?.takeIf(String::isNotBlank))
            )
        )
    }

    private fun submit(state: QuestionState, action: QuestionAction.Submit): QuestionState {
        val status = when (val submission = action.submission) {
            is QuestionSubmission.Answer -> when {
                !action.isConnected -> rejected(QuestionFailureCode.DISCONNECTED_ANSWER)
                else -> validate(action.request, submission.answers)?.let { QuestionRequestStatus.Rejected(it) }
                    ?: QuestionRequestStatus.Submitting(GatewayQuestionAction.ANSWER)
            }
            QuestionSubmission.Cancel -> if (action.isConnected) {
                QuestionRequestStatus.Submitting(GatewayQuestionAction.CANCEL)
            } else {
                rejected(QuestionFailureCode.DISCONNECTED_CANCEL)
            }
        }
        return state.withStatus(action.request.rpcId, status)
    }

    private fun validate(
        request: GatewayPendingQuestionRequest,
        answers: List<GatewayQuestionAnswer>
    ): QuestionFailure? {
        if (request.questions.map { it.id } != answers.map { it.id }) {
            return QuestionFailure(QuestionFailureCode.INVALID_ANSWER_ORDER)
        }
        request.questions.zip(answers).forEach { (question, answer) ->
            val allowed = question.options.orEmpty().map { it.label }.toSet()
            if (answer.selected.toSet().size != answer.selected.size || answer.selected.any { it !in allowed }) {
                return QuestionFailure(QuestionFailureCode.INVALID_OR_DUPLICATE_OPTIONS, question.question)
            }
            if (!question.allowsMultipleSelections &&
                (answer.selected.size > 1 || (answer.selected.size == 1 && answer.normalizedCustom != null))
            ) {
                return QuestionFailure(QuestionFailureCode.SINGLE_SELECTION_REQUIRED, question.question)
            }
        }
        return null
    }

    private fun rejected(code: QuestionFailureCode) =
        QuestionRequestStatus.Rejected(QuestionFailure(code))

    private fun QuestionState.withStatus(id: String, status: QuestionRequestStatus): QuestionState =
        copy(requestStatuses = requestStatuses + (id to status))
}
