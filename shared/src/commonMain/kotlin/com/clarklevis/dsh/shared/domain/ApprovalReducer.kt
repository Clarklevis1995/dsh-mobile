package com.clarklevis.dsh.shared.domain

import com.clarklevis.dsh.shared.protocol.GatewayApprovalOutcome
import com.clarklevis.dsh.shared.protocol.GatewayPendingApprovalRequest

enum class ApprovalFailureCode {
    DISCONNECTED,
    SERVER_REJECTED,
    REQUEST_FAILED
}

data class ApprovalFailure(val code: ApprovalFailureCode, val argument: String? = null)

sealed interface ApprovalRequestStatus {
    data object Idle : ApprovalRequestStatus
    data class Submitting(val outcome: GatewayApprovalOutcome) : ApprovalRequestStatus
    data class Accepted(val outcome: GatewayApprovalOutcome) : ApprovalRequestStatus
    data class Rejected(val failure: ApprovalFailure) : ApprovalRequestStatus
}

data class ApprovalState(
    val pendingRequests: List<GatewayPendingApprovalRequest> = emptyList(),
    val requestStatuses: Map<String, ApprovalRequestStatus> = emptyMap()
)

sealed interface ApprovalAction {
    data object Reset : ApprovalAction
    data class RequestReceived(val request: GatewayPendingApprovalRequest) : ApprovalAction
    data class Submit(
        val request: GatewayPendingApprovalRequest,
        val outcome: GatewayApprovalOutcome,
        val isConnected: Boolean
    ) : ApprovalAction
    data class ResponseReceived(
        val rpcId: String,
        val outcome: GatewayApprovalOutcome,
        val accepted: Boolean,
        val reason: String?
    ) : ApprovalAction
    data class Resolved(val rpcId: String) : ApprovalAction
    data class RequestFailed(val rpcId: String, val message: String?) : ApprovalAction
    data class SessionRequestsFailed(val sessionId: String, val message: String?) : ApprovalAction
}

object ApprovalReducer {
    fun reduce(state: ApprovalState, action: ApprovalAction): ApprovalState = when (action) {
        ApprovalAction.Reset -> ApprovalState()
        is ApprovalAction.RequestReceived -> {
            val index = state.pendingRequests.indexOfFirst { it.rpcId == action.request.rpcId }
            if (index >= 0) {
                state.copy(pendingRequests = state.pendingRequests.toMutableList().apply {
                    this[index] = action.request
                })
            } else {
                state.copy(
                    pendingRequests = state.pendingRequests + action.request,
                    requestStatuses = state.requestStatuses +
                        (action.request.rpcId to ApprovalRequestStatus.Idle)
                )
            }
        }
        is ApprovalAction.Submit -> state.withStatus(
            action.request.rpcId,
            if (action.isConnected) ApprovalRequestStatus.Submitting(action.outcome)
            else ApprovalRequestStatus.Rejected(ApprovalFailure(ApprovalFailureCode.DISCONNECTED))
        )
        is ApprovalAction.ResponseReceived -> when {
            action.accepted -> state.withStatus(
                action.rpcId,
                ApprovalRequestStatus.Accepted(action.outcome)
            )
            action.reason == "not-pending" -> state.remove(action.rpcId)
            else -> state.withStatus(
                action.rpcId,
                ApprovalRequestStatus.Rejected(
                    ApprovalFailure(ApprovalFailureCode.SERVER_REJECTED, action.reason ?: "bad-response")
                )
            )
        }
        is ApprovalAction.Resolved -> state.remove(action.rpcId)
        is ApprovalAction.RequestFailed -> state.withStatus(
            action.rpcId,
            ApprovalRequestStatus.Rejected(
                ApprovalFailure(ApprovalFailureCode.REQUEST_FAILED, action.message?.takeIf(String::isNotBlank))
            )
        )
        is ApprovalAction.SessionRequestsFailed -> {
            val ids = state.pendingRequests.filter { it.sessionId == action.sessionId }.map { it.rpcId }
            val failure = ApprovalRequestStatus.Rejected(
                ApprovalFailure(ApprovalFailureCode.REQUEST_FAILED, action.message?.takeIf(String::isNotBlank))
            )
            state.copy(requestStatuses = state.requestStatuses + ids.associateWith { failure })
        }
    }

    private fun ApprovalState.withStatus(id: String, status: ApprovalRequestStatus): ApprovalState =
        copy(requestStatuses = requestStatuses + (id to status))

    private fun ApprovalState.remove(id: String): ApprovalState = copy(
        pendingRequests = pendingRequests.filterNot { it.rpcId == id },
        requestStatuses = requestStatuses - id
    )
}
