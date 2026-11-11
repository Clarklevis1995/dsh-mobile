package com.clarklevis.dsh.shared.facade

import com.clarklevis.dsh.shared.protocol.GatewayFrame
import kotlinx.serialization.Serializable

/** 只携带导航和类型，不把问题正文、工具参数或路径放入通知。 */
@Serializable
data class SharedAgentNotification(
    val key: String,
    val sessionId: String,
    val kind: String,
    val failed: Boolean = false,
)

/** 在历史／交互仲裁接收帧之后调用；历史快照不会生成一批旧通知。 */
class SharedAgentNotificationStore {
    private val seen = linkedSetOf<String>()

    fun accept(
        frame: GatewayFrame,
        questionIds: Set<String>,
        approvalIds: Set<String>,
    ): SharedAgentNotification? {
        val session = frame.sessionId?.takeIf { it.isNotBlank() && it.length <= 1024 } ?: return null
        val notice =
            when {
                frame.kind == "question-requested" && (frame.rpcId?.length ?: 0) in 1..1024 && frame.rpcId in questionIds -> {
                    SharedAgentNotification("question:$session:${frame.rpcId}", session, "question")
                }

                frame.kind == "approval-requested" && (frame.rpcId?.length ?: 0) in 1..1024 && frame.rpcId in approvalIds -> {
                    SharedAgentNotification("approval:$session:${frame.rpcId}", session, "approval")
                }

                frame.kind == "event" && frame.event?.type == "turn/end" -> {
                    val sequence = frame.seq ?: return null
                    if (frame.time == null || sequence < 0) return null
                    val event = frame.event ?: return null
                    val failed =
                        event.isError == true || event.interrupted == true || !event.error.isNullOrBlank() ||
                            event.reason?.lowercase() in setOf("error", "failed", "cancelled", "canceled", "interrupted", "aborted")
                    SharedAgentNotification("ended:$session:$sequence", session, "ended", failed)
                }

                else -> {
                    return null
                }
            }
        if (!seen.add(notice.key)) return null
        while (seen.size > 512) seen.remove(seen.first())
        return notice
    }
}
