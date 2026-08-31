package com.clarklevis.dsh.shared.facade

import com.clarklevis.dsh.shared.domain.ApprovalAction
import com.clarklevis.dsh.shared.domain.ApprovalReducer
import com.clarklevis.dsh.shared.domain.ApprovalRequestStatus
import com.clarklevis.dsh.shared.domain.ApprovalState
import com.clarklevis.dsh.shared.protocol.GatewayApprovalOutcome
import com.clarklevis.dsh.shared.protocol.GatewayPendingApprovalRequest
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
data class SharedApprovalStatusSnapshot(
    val kind: String,
    val outcome: String? = null,
    val failureCode: String? = null,
    val failureArgument: String? = null
)

@Serializable
data class SharedApprovalSnapshot(
    val pendingRequests: List<GatewayPendingApprovalRequest> = emptyList(),
    val requestStatuses: Map<String, SharedApprovalStatusSnapshot> = emptyMap()
)

@Serializable
data class SharedApprovalEffect(
    val action: String,
    val rpcId: String,
    val sessionId: String,
    val approvalId: String,
    val outcome: String
)

data class SharedApprovalResult(
    val snapshotJson: String?,
    val effectJson: String?,
    val errorCode: String?,
    val errorMessage: String?
) {
    val isSuccess: Boolean get() = errorCode == null
}

class SharedApprovalStore {
    private var state = ApprovalState()
    private val events = SharedMviEventEmitter("approval")

    fun subscribe(observer: SharedMviEventObserver): SharedMviSubscription = try {
        events.subscribe(observer, wireJson.encodeToString(state.toSnapshot()))
    } catch (error: Throwable) {
        events.subscribeError(observer, "approval-subscribe-failed", error.safeMessage())
    }

    fun snapshot(): SharedApprovalResult = result(state, null, alwaysSnapshot = true, commit = false)

    fun reset(): SharedApprovalResult = mutate("reset") {
        Transition(ApprovalReducer.reduce(state, ApprovalAction.Reset))
    }

    fun requestReceived(requestJson: String): SharedApprovalResult = mutate("request-received") {
        val request = wireJson.decodeFromString<GatewayPendingApprovalRequest>(requestJson)
        require(request.rpcId.isNotBlank() && request.sessionId.isNotBlank() &&
            request.approvalId.isNotBlank() && request.toolName.isNotBlank())
        Transition(ApprovalReducer.reduce(state, ApprovalAction.RequestReceived(request)))
    }

    fun submitDecision(rpcId: String, outcome: String, isConnected: Boolean): SharedApprovalResult =
        mutate("submit-decision") {
            val request = state.pendingRequests.firstOrNull { it.rpcId == rpcId }
                ?: return@mutate Transition(state, errorCode = "approval-not-pending")
            if (state.requestStatuses[rpcId] is ApprovalRequestStatus.Submitting ||
                state.requestStatuses[rpcId] is ApprovalRequestStatus.Accepted) {
                return@mutate Transition(state)
            }
            val parsed = outcome.toApprovalOutcome()
            val next = ApprovalReducer.reduce(state, ApprovalAction.Submit(request, parsed, isConnected))
            val effect = if (next.requestStatuses[rpcId] is ApprovalRequestStatus.Submitting) {
                SharedApprovalEffect(
                    action = "respond",
                    rpcId = request.rpcId,
                    sessionId = request.sessionId,
                    approvalId = request.approvalId,
                    outcome = parsed.toWireValue()
                )
            } else null
            Transition(next, effect)
        }

    fun responseReceived(
        rpcId: String,
        outcome: String,
        accepted: Boolean,
        reason: String?
    ): SharedApprovalResult = mutate("response-received") {
        Transition(ApprovalReducer.reduce(
            state,
            ApprovalAction.ResponseReceived(rpcId, outcome.toApprovalOutcome(), accepted, reason)
        ))
    }

    fun resolved(rpcId: String): SharedApprovalResult = mutate("resolved") {
        Transition(ApprovalReducer.reduce(state, ApprovalAction.Resolved(rpcId)))
    }

    fun requestFailed(rpcId: String, message: String?): SharedApprovalResult = mutate("request-failed") {
        Transition(ApprovalReducer.reduce(state, ApprovalAction.RequestFailed(rpcId, message)))
    }

    fun sessionRequestsFailed(sessionId: String, message: String?): SharedApprovalResult =
        mutate("session-requests-failed") {
            Transition(ApprovalReducer.reduce(state, ApprovalAction.SessionRequestsFailed(sessionId, message)))
        }

    private inline fun mutate(operation: String, transform: () -> Transition): SharedApprovalResult = try {
        val transition = transform()
        transition.errorCode?.let { code ->
            val message = "No pending approval for the supplied rpcId"
            events.emitError("$code:${events.currentSequence + 1}", code, message)
            return SharedApprovalResult(null, null, code, message)
        }
        result(transition.state, transition.effect, transition.state != state, commit = true)
    } catch (error: Throwable) {
        val code = "approval-$operation-failed"
        val message = error.safeMessage()
        events.emitError("$code:${events.currentSequence + 1}", code, message)
        SharedApprovalResult(null, null, code, message)
    }

    private fun result(
        next: ApprovalState,
        effect: SharedApprovalEffect?,
        alwaysSnapshot: Boolean,
        commit: Boolean
    ): SharedApprovalResult {
        val snapshotJson = if (alwaysSnapshot) wireJson.encodeToString(next.toSnapshot()) else null
        val effectJson = effect?.let(wireJson::encodeToString)
        if (commit) {
            state = next
            if (snapshotJson != null || effectJson != null) {
                events.emitTransition(
                    "approval:${events.currentSequence + 1}",
                    snapshotJson,
                    effectJson?.let { "[$it]" } ?: "[]"
                )
            }
        }
        return SharedApprovalResult(snapshotJson, effectJson, null, null)
    }

    private data class Transition(
        val state: ApprovalState,
        val effect: SharedApprovalEffect? = null,
        val errorCode: String? = null
    )
}

private fun ApprovalState.toSnapshot() = SharedApprovalSnapshot(
    pendingRequests,
    requestStatuses.mapValues { (_, status) -> status.toSharedApprovalStatusSnapshot() }
)

internal fun ApprovalRequestStatus.toSharedApprovalStatusSnapshot(): SharedApprovalStatusSnapshot = when (this) {
    ApprovalRequestStatus.Idle -> SharedApprovalStatusSnapshot("idle")
    is ApprovalRequestStatus.Submitting -> SharedApprovalStatusSnapshot("submitting", outcome.toWireValue())
    is ApprovalRequestStatus.Accepted -> SharedApprovalStatusSnapshot("accepted", outcome.toWireValue())
    is ApprovalRequestStatus.Rejected -> SharedApprovalStatusSnapshot(
        "rejected", failureCode = failure.code.name, failureArgument = failure.argument
    )
}

private fun String.toApprovalOutcome(): GatewayApprovalOutcome = when (this) {
    "allowed-once" -> GatewayApprovalOutcome.ALLOWED_ONCE
    "rejected" -> GatewayApprovalOutcome.REJECTED
    else -> error("Unsupported approval outcome: $this")
}

private fun GatewayApprovalOutcome.toWireValue(): String = when (this) {
    GatewayApprovalOutcome.ALLOWED_ONCE -> "allowed-once"
    GatewayApprovalOutcome.REJECTED -> "rejected"
}

private fun Throwable.safeMessage(): String = message ?: this::class.simpleName ?: "unknown-error"
