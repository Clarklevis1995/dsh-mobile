package whaleness

import com.clarklevis.dsh.shared.facade.SharedApprovalEffect
import com.clarklevis.dsh.shared.facade.SharedApprovalSnapshot
import com.clarklevis.dsh.shared.facade.SharedApprovalStore
import com.clarklevis.dsh.shared.facade.SharedQuestionEffect
import com.clarklevis.dsh.shared.facade.SharedQuestionSnapshot
import com.clarklevis.dsh.shared.facade.SharedQuestionStore
import com.clarklevis.dsh.shared.gateway.GatewayRequest
import com.clarklevis.dsh.shared.gateway.GatewayRequests
import com.clarklevis.dsh.shared.protocol.GatewayApprovalOutcome
import com.clarklevis.dsh.shared.protocol.GatewayFrame
import com.clarklevis.dsh.shared.protocol.GatewayPendingApprovalRequest
import com.clarklevis.dsh.shared.protocol.GatewayPendingQuestionRequest
import com.clarklevis.dsh.shared.protocol.GatewayQuestion
import com.clarklevis.dsh.shared.protocol.GatewayQuestionAnswer
import com.clarklevis.dsh.shared.protocol.SessionEvent
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
data class HarmonyQuestion(
    val rpcId: String,
    val sessionId: String,
    val questions: List<GatewayQuestion>,
    val status: String,
    val error: String? = null,
)

@Serializable
data class HarmonyApproval(
    val rpcId: String,
    val sessionId: String,
    val toolName: String,
    val reason: String?,
    val arguments: String?,
    val status: String,
    val outcome: String? = null,
    val error: String? = null,
    val reasonEnglish: String? = null,
    val replay: Boolean = false,
)

/** 提交校验、重复点击与服务端确认由已有共享 Store 处理；鸿蒙仅适配视图和 I/O。 */
class InteractionStore {
    private val questions = SharedQuestionStore()
    private val approvals = SharedApprovalStore()
    private var questionSnapshot = SharedQuestionSnapshot()
    private var approvalSnapshot = SharedApprovalSnapshot()
    private val argumentsByCall = linkedMapOf<Pair<String, String>, String>()

    init {
        questions.subscribe { event ->
            event.statePayloadJson?.let {
                questionSnapshot = wireJson.decodeFromString(it)
            }
        }
        approvals.subscribe { event ->
            event.statePayloadJson?.let {
                approvalSnapshot = wireJson.decodeFromString(it)
            }
        }
    }

    fun reset() {
        questions.reset()
        approvals.reset()
        argumentsByCall.clear()
    }

    fun accept(frame: GatewayFrame) {
        when (frame.kind) {
            "question-requested" -> {
                val request =
                    GatewayPendingQuestionRequest(
                        requireNotNull(frame.rpcId),
                        requireNotNull(frame.sessionId),
                        requireNotNull(frame.questions),
                        frame.replay == true,
                    )
                require(request.rpcId.isNotBlank() && request.sessionId.isNotBlank() && request.questions.isNotEmpty())
                check(questions.requestReceived(wireJson.encodeToString(request)).isSuccess)
            }

            "question-response" -> {
                val request = questionSnapshot.pendingRequests.firstOrNull { it.rpcId == frame.rpcId } ?: return
                if (frame.sessionId != null && frame.sessionId != request.sessionId) return
                val status = questionSnapshot.requestStatuses[request.rpcId]
                if ((status?.kind == "submitting" && status.action == frame.action) || frame.reason == "not-pending") {
                    questions.responseReceived(request.rpcId, requireNotNull(frame.action), requireNotNull(frame.accepted), frame.reason)
                }
            }

            "question-resolved" -> {
                questions.resolved(requireNotNull(frame.rpcId))
            }

            "approval-requested" -> {
                val request =
                    GatewayPendingApprovalRequest(
                        requireNotNull(frame.rpcId),
                        requireNotNull(frame.sessionId),
                        requireNotNull(frame.approvalId),
                        requireNotNull(frame.toolName),
                        frame.callId,
                        frame.reason,
                        frame.displayReason,
                        frame.replay == true,
                    )
                require(approvals.requestReceived(wireJson.encodeToString(request)).isSuccess)
            }

            "approval-response" -> {
                val request = approvalSnapshot.pendingRequests.firstOrNull { it.rpcId == frame.rpcId } ?: return
                if (frame.sessionId != null && frame.sessionId != request.sessionId) return
                val status = approvalSnapshot.requestStatuses[request.rpcId]
                if ((status?.kind == "submitting" && status.outcome == frame.outcome) || frame.reason == "not-pending") {
                    approvals.responseReceived(request.rpcId, requireNotNull(frame.outcome), requireNotNull(frame.accepted), frame.reason)
                }
            }

            "approval-resolved" -> {
                approvals.resolved(requireNotNull(frame.rpcId))
            }

            "error" -> {
                frame.rpcId?.let { fail(requestKey(frame.requestType, it), frame.message ?: "请求被网关拒绝") }
            }
        }
    }

    fun capture(record: SessionEvent) {
        val callId = record.event.callId ?: return
        val arguments = record.event.arguments?.normalizedJsonValue() ?: return
        val text = arguments.displayText()
        argumentsByCall[record.sessionId to callId] =
            if (text.length <= 24_000) {
                text
            } else {
                text.take(24_000) + "\n…（参数过长，仅显示前 24000 个字符；完整内容请到桌面端查看）"
            }
        while (argumentsByCall.size > 128) argumentsByCall.remove(argumentsByCall.keys.first())
    }

    fun submitQuestion(
        rpcId: String,
        sessionId: String?,
        answers: List<GatewayQuestionAnswer>,
        cancel: Boolean,
        connected: Boolean,
    ): GatewayRequest? {
        val request =
            questionSnapshot.pendingRequests.firstOrNull { it.rpcId == rpcId }
                ?: error("问题已失效或已处理")
        require(sessionId == request.sessionId) { "问题所属会话已变化，请重新确认" }
        val result =
            if (cancel) {
                questions.submitCancel(rpcId, connected)
            } else {
                questions.submitAnswer(rpcId, wireJson.encodeToString(answers), connected)
            }
        require(result.isSuccess) { "问题已失效或答案格式无效" }
        val effect = result.effectJson?.let { wireJson.decodeFromString<SharedQuestionEffect>(it) }
        if (effect == null) {
            check(questionSnapshot.requestStatuses[rpcId]?.kind != "rejected") { "请检查答案并确认连接状态" }
            return null
        }
        return if (effect.action == "cancel") {
            GatewayRequests.questionCancel(effect.rpcId, effect.sessionId)
        } else {
            GatewayRequests.questionAnswer(effect.rpcId, effect.sessionId, effect.answers.orEmpty())
        }
    }

    fun submitApproval(
        rpcId: String,
        sessionId: String?,
        outcome: String,
        connected: Boolean,
    ): GatewayRequest? {
        val request =
            approvalSnapshot.pendingRequests.firstOrNull { it.rpcId == rpcId }
                ?: error("审批已失效或已处理")
        require(sessionId == request.sessionId) { "审批所属会话已变化，请重新确认" }
        require(outcome in setOf("allowed-once", "rejected")) { "不支持的审批决定" }
        val result = approvals.submitDecision(rpcId, outcome, connected)
        require(result.isSuccess) { "审批已失效或决定格式无效" }
        val effect = result.effectJson?.let { wireJson.decodeFromString<SharedApprovalEffect>(it) }
        if (effect == null) {
            check(approvalSnapshot.requestStatuses[rpcId]?.kind != "rejected") { "连接已断开，请重新连接后确认" }
            return null
        }
        return GatewayRequests.approvalResponse(
            effect.rpcId,
            effect.sessionId,
            effect.approvalId,
            if (effect.outcome == "allowed-once") GatewayApprovalOutcome.ALLOWED_ONCE else GatewayApprovalOutcome.REJECTED,
        )
    }

    fun fail(
        key: String,
        message: String,
    ) {
        val id = key.substringAfter(':', "")
        if (key.startsWith("question:") && questionSnapshot.requestStatuses[id]?.kind == "submitting") {
            questions.requestFailed(id, message)
        }
        if (key.startsWith("approval:") && approvalSnapshot.requestStatuses[id]?.kind == "submitting") {
            approvals.requestFailed(id, message)
        }
    }

    fun disconnected() {
        questionSnapshot.pendingRequests.map { it.rpcId }.forEach {
            fail("question:$it", "连接已断开，提交结果尚未确认")
        }
        approvalSnapshot.pendingRequests.map { it.rpcId }.forEach {
            fail("approval:$it", "连接已断开，提交结果尚未确认")
        }
    }

    fun questionRows(): List<HarmonyQuestion> =
        questionSnapshot.pendingRequests.map { request ->
            val status = questionSnapshot.requestStatuses[request.rpcId]
            HarmonyQuestion(
                request.rpcId,
                request.sessionId,
                request.questions,
                status?.kind ?: "idle",
                status?.takeIf { it.kind == "rejected" }?.let { failureText(it.failureCode, it.failureArgument) },
            )
        }

    fun approvalRows(): List<HarmonyApproval> =
        approvalSnapshot.pendingRequests.map { request ->
            val status = approvalSnapshot.requestStatuses[request.rpcId]
            HarmonyApproval(
                request.rpcId,
                request.sessionId,
                request.toolName,
                request.localizedReason("zh-CN"),
                request.callId?.let { argumentsByCall[request.sessionId to it] },
                status?.kind ?: "idle",
                status?.outcome,
                status?.takeIf { it.kind == "rejected" }?.let { failureText(it.failureCode, it.failureArgument) },
                reasonEnglish = request.localizedReason("en"),
                replay = request.replay,
            )
        }

    companion object {
        fun requestKey(
            type: String?,
            rpcId: String,
        ): String = if (type == "approval-response") "approval:$rpcId" else "question:$rpcId"
    }
}

private fun failureText(
    code: String?,
    argument: String?,
): String =
    when (code) {
        "DISCONNECTED", "DISCONNECTED_ANSWER", "DISCONNECTED_CANCEL" -> "连接已断开，请重新连接后操作"
        "INVALID_ANSWER_ORDER" -> "请按顺序回答当前的所有问题"
        "INVALID_OR_DUPLICATE_OPTIONS" -> "选项已变化，请重新选择"
        "SINGLE_SELECTION_REQUIRED" -> "此题只能选择一个选项或填写自定义答案"
        "SERVER_REJECTED" -> "网关未接受此次提交：${argument ?: "请刷新后重试"}"
        else -> argument ?: "请求失败，请检查连接后重试"
    }
