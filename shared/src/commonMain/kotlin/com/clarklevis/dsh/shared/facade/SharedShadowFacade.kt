package com.clarklevis.dsh.shared.facade

import com.clarklevis.dsh.shared.protocol.GatewayFrame
import com.clarklevis.dsh.shared.protocol.GatewaySearchItem
import com.clarklevis.dsh.shared.protocol.GatewaySessionSummary
import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder
import com.clarklevis.dsh.shared.protocol.GatewayWorkspace
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromJsonElement

/** 平台在路由时拥有的临时请求关联信息。 */
@Serializable
data class SharedFrameRoutingContext(
    val selectedSessionId: String? = null,
    val pendingHistorySessionId: String? = null,
    val pendingModelsSessionId: String? = null,
    val pendingGlobalModelsRequest: Boolean = false,
    val pendingModelSelectionSessionId: String? = null,
    val pendingPermissionOptionsSessionId: String? = null
)

/** 只包含协议语义的稳定值；平台展示文案和 I/O 状态不参与比较。 */
@Serializable
data class SharedRouteFingerprint(
    val category: String,
    val route: String,
    val sessionId: String? = null,
    val rpcId: String? = null,
    val requestType: String? = null,
    val finishRequest: String? = null,
    val action: String? = null,
    val accepted: Boolean? = null,
    val hasMore: Boolean? = null,
    val itemCount: Int? = null,
    val replay: Boolean? = null,
    val outcome: String? = null,
    val target: String? = null,
    val value: String? = null,
    val applied: Boolean? = null,
    val malformedReason: String? = null
)

/** 阶段 8 只观察该动作描述，平台不会执行影子 effect。 */
@Serializable
data class SharedPlatformEffect(
    val kind: String,
    val sessionId: String? = null,
    val requestType: String? = null,
    val rpcId: String? = null
)

/** 平台用户操作的稳定输入；复杂 payload 继续由各端网络适配器拥有。 */
@Serializable
data class SharedUserIntent(
    val kind: String,
    val sessionId: String? = null,
    val requestType: String? = null,
    val rpcId: String? = null,
    val value: String? = null
)

/** Objective-C/Swift 友好的非抛出结果。 */
data class SharedShadowRouteResult(
    val routeJson: String?,
    val effectsJson: String,
    val errorCode: String?,
    val errorMessage: String?
) {
    val isSuccess: Boolean get() = errorCode == null
}

/**
 * 生产 wire frame 的纯路由 Facade。
 *
 * 对外方法将 Kotlin 异常转为结构化错误；本类不持有状态、不执行 I/O。
 */
class SharedShadowFacade {
    fun routeFrame(frameJson: String, contextJson: String): SharedShadowRouteResult = try {
        val frame = GatewayWireDecoder.decode(frameJson)
        val context = wireJson.decodeFromString<SharedFrameRoutingContext>(contextJson)
        val outcome = route(frame, context)
        SharedShadowRouteResult(
            routeJson = wireJson.encodeToString(outcome.fingerprint),
            effectsJson = wireJson.encodeToString(outcome.effects),
            errorCode = null,
            errorMessage = null
        )
    } catch (error: Throwable) {
        SharedShadowRouteResult(
            routeJson = null,
            effectsJson = "[]",
            errorCode = "invalid-input",
            errorMessage = error.message ?: error::class.simpleName ?: "unknown-error"
        )
    }

    /**
     * 将用户 intent 归一为平台 effect。阶段 8 只验证契约，调用方不得执行影子结果。
     */
    fun routeIntent(intentJson: String): SharedShadowRouteResult = try {
        val intent = wireJson.decodeFromString<SharedUserIntent>(intentJson)
        val effect = when (intent.kind) {
            "select-session" -> SharedPlatformEffect("select-session", intent.sessionId)
            "send-message" -> SharedPlatformEffect("send-message", intent.sessionId)
            "load-history" -> SharedPlatformEffect("request-history", intent.sessionId)
            "request-models" -> SharedPlatformEffect("request-models", intent.sessionId)
            "select-model" -> SharedPlatformEffect("select-model", intent.sessionId)
            "request-permission-options" -> SharedPlatformEffect("request-permission-options", intent.sessionId)
            "select-permission" -> SharedPlatformEffect("select-permission", intent.sessionId)
            "answer-question" -> SharedPlatformEffect("answer-question", intent.sessionId, rpcId = intent.rpcId)
            "cancel-question" -> SharedPlatformEffect("cancel-question", intent.sessionId, rpcId = intent.rpcId)
            else -> SharedPlatformEffect("unsupported-intent", intent.sessionId, intent.requestType, intent.rpcId)
        }
        SharedShadowRouteResult(
            routeJson = wireJson.encodeToString(
                SharedRouteFingerprint(
                    category = "intent",
                    route = intent.kind,
                    sessionId = intent.sessionId,
                    rpcId = intent.rpcId,
                    requestType = intent.requestType,
                    value = intent.value
                )
            ),
            effectsJson = wireJson.encodeToString(listOf(effect)),
            errorCode = null,
            errorMessage = null
        )
    } catch (error: Throwable) {
        SharedShadowRouteResult(
            routeJson = null,
            effectsJson = "[]",
            errorCode = "invalid-input",
            errorMessage = error.message ?: error::class.simpleName ?: "unknown-error"
        )
    }

    private fun route(frame: GatewayFrame, context: SharedFrameRoutingContext): RouteOutcome = when (frame.kind) {
        "paired" -> outcome("connection", "paired", effects = effects("record-pairing"))
        "hello" -> outcome(
            "connection",
            "hello",
            effects = effects("reset-question-state", "refresh-remote-state", "refresh-default-configuration")
        )
        "pong" -> outcome("connection", "pong", effects = effects("record-pong"))
        "subscribed" -> outcome(
            "connection",
            "subscribed",
            sessionId = frame.sessionId,
            effects = effects(SharedPlatformEffect("record-subscription", frame.sessionId))
        )
        "sent" -> frame.sessionId?.let { sessionId ->
            outcome(
                "content",
                "sent",
                sessionId = sessionId,
                effects = effects(
                    SharedPlatformEffect("associate-background-session", sessionId),
                    SharedPlatformEffect("subscribe-session", sessionId),
                    SharedPlatformEffect("request-session-list", sessionId),
                    SharedPlatformEffect("refresh-session-controls", sessionId)
                )
            )
        } ?: malformed("sent", "missing-session-id")
        "event" -> {
            val missing = listOfNotNull(
                "session-id".takeIf { frame.sessionId == null },
                "sequence".takeIf { frame.seq == null },
                "time".takeIf { frame.time == null },
                "event".takeIf { frame.event == null }
            )
            if (missing.isNotEmpty()) {
                malformed("event", "missing-${missing.joinToString("-")}")
            } else {
                outcome(
                    "content",
                    "live-event",
                    sessionId = frame.sessionId,
                    effects = effects(
                        SharedPlatformEffect("reduce-live-event", frame.sessionId),
                        SharedPlatformEffect("enqueue-attachments", frame.sessionId)
                    )
                )
            }
        }
        "workspaces" -> outcome(
            "content",
            "workspaces",
            itemCount = decodedItemCount(frame, GatewayWorkspace.serializer()),
            effects = effects("apply-workspaces", "apply-archived-session-ids")
        )
        "sessions" -> outcome(
            "content",
            "sessions",
            itemCount = decodedItemCount(frame, GatewaySessionSummary.serializer()),
            effects = effects("reduce-session-list")
        )
        "history" -> {
            val sessionId = frame.sessionId ?: context.pendingHistorySessionId ?: context.selectedSessionId
            outcome(
                "content",
                "history",
                sessionId = sessionId,
                hasMore = frame.hasMore ?: false,
                itemCount = frame.events.orEmpty().size,
                effects = effects(SharedPlatformEffect("reduce-history", sessionId))
            )
        }
        "attachment" -> if (frame.attachment == null) {
            malformed("attachment", "missing-attachment")
        } else {
            outcome(
                "content",
                "attachment",
                sessionId = frame.sessionId,
                effects = effects(SharedPlatformEffect("store-attachment", frame.sessionId))
            )
        }
        "search" -> outcome(
            "content",
            "search",
            hasMore = frame.hasMore == true,
            itemCount = decodedItemCount(frame, GatewaySearchItem.serializer()),
            effects = effects("apply-search-results")
        )
        "host" -> outcome("content", "host", effects = effects("apply-host-snapshot"))
        "agent-presets" -> control(
            route = "agent-presets",
            finishRequest = "agent-presets",
            itemCount = frame.presets.orEmpty().size
        )
        "defaults" -> control("defaults", finishRequest = "defaults")
        "default-model" -> control("default-model", finishRequest = "default-model")
        "save-default-model" -> control("save-default-model")
        "set-default" -> outcome(
            category = "control",
            route = "set-default",
            target = frame.target,
            value = frame.value,
            applied = frame.applied == true,
            effects = effects("apply-default-selection")
        )
        "models" -> control(
            route = "models",
            sessionId = frame.sessionId,
            finishRequest = "models",
            itemCount = frame.groups.orEmpty().size,
            action = if (context.pendingGlobalModelsRequest) "global" else "session"
        )
        "select-model" -> control(
            route = "select-model",
            sessionId = frame.sessionId
        )
        "permission-options" -> control(
            route = "permission-options",
            sessionId = frame.sessionId,
            itemCount = frame.sessionPermissions?.options.orEmpty().size
        )
        "permission" -> control(
            route = "permission",
            sessionId = frame.sessionId,
            value = frame.set
        )
        "context-usage" -> {
            val sessionId = frame.sessionId
            control(
                route = "context-usage",
                sessionId = sessionId,
                finishRequest = "context-usage",
                action = "context-received"
            )
        }
        "session-stats" -> {
            val sessionId = frame.sessionId
            control(
                route = "session-stats",
                sessionId = sessionId,
                finishRequest = "session-stats",
                action = "stats-received"
            )
        }
        "directories" -> outcome(
            "workspace",
            "directories",
            itemCount = frame.entries.orEmpty().size,
            effects = effects("apply-directory-list")
        )
        "directory-create" -> outcome("workspace", "directory-create", effects = effects("reveal-created-directory"))
        "workspace-create" -> outcome(
            "workspace",
            "workspace-create",
            applied = frame.created == true,
            effects = effects("apply-created-workspace")
        )
        "question-requested" -> {
            val rpcId = frame.rpcId?.takeIf(String::isNotEmpty)
            val sessionId = frame.sessionId?.takeIf(String::isNotEmpty)
            val questions = frame.questions.orEmpty()
            if (rpcId == null || sessionId == null || questions.isEmpty()) {
                outcome(
                    "question",
                    "invalid-request",
                    sessionId = frame.sessionId,
                    malformedReason = "missing-request-fields",
                    effects = effects(SharedPlatformEffect("reject-question-request", frame.sessionId, rpcId = frame.rpcId))
                )
            } else {
                outcome(
                    "question",
                    "requested",
                    sessionId = sessionId,
                    rpcId = rpcId,
                    replay = frame.replay == true,
                    itemCount = questions.size,
                    effects = effects(
                        SharedPlatformEffect("reduce-question-request", sessionId, rpcId = rpcId),
                        SharedPlatformEffect("present-question", sessionId, rpcId = rpcId)
                    )
                )
            }
        }
        "question-response" -> frame.rpcId?.let { rpcId ->
            val action = frame.action?.takeIf { it == "answer" || it == "cancel" } ?: "answer"
            val reason = frame.reason ?: "bad-response"
            outcome(
                "question",
                "response",
                rpcId = rpcId,
                action = action,
                accepted = frame.accepted == true,
                outcome = "not-pending".takeIf { frame.accepted != true && reason == "not-pending" },
                effects = effects(SharedPlatformEffect("reduce-question-response", rpcId = rpcId))
            )
        } ?: malformed("question-response", "missing-rpc-id")
        "question-resolved" -> frame.rpcId?.let { rpcId ->
            outcome(
                "question",
                "resolved",
                sessionId = frame.sessionId,
                rpcId = rpcId,
                outcome = frame.outcome,
                effects = effects(SharedPlatformEffect("reduce-question-resolved", frame.sessionId, rpcId = rpcId))
            )
        } ?: malformed("question-resolved", "missing-rpc-id")
        "error" -> outcome(
            "failure",
            "error",
            sessionId = frame.sessionId,
            rpcId = frame.rpcId,
            requestType = frame.requestType,
            effects = effects(SharedPlatformEffect("handle-gateway-failure", frame.sessionId, frame.requestType, frame.rpcId))
        )
        else -> outcome("unknown", frame.kind, effects = effects("record-unknown-frame"))
    }

    private fun control(
        route: String,
        sessionId: String? = null,
        finishRequest: String? = null,
        itemCount: Int? = null,
        action: String? = null,
        value: String? = null
    ): RouteOutcome = outcome(
        category = "control",
        route = route,
        sessionId = sessionId,
        finishRequest = finishRequest,
        itemCount = itemCount,
        action = action,
        value = value,
        effects = buildList {
            add(SharedPlatformEffect("reduce-session-control", sessionId, finishRequest))
            finishRequest?.let { add(SharedPlatformEffect("finish-request", sessionId, it)) }
        }
    )

    private fun malformed(route: String, reason: String): RouteOutcome = outcome(
        category = "ignored",
        route = route,
        malformedReason = reason
    )

    private fun outcome(
        category: String,
        route: String,
        sessionId: String? = null,
        rpcId: String? = null,
        requestType: String? = null,
        finishRequest: String? = null,
        action: String? = null,
        accepted: Boolean? = null,
        hasMore: Boolean? = null,
        itemCount: Int? = null,
        replay: Boolean? = null,
        outcome: String? = null,
        target: String? = null,
        value: String? = null,
        applied: Boolean? = null,
        malformedReason: String? = null,
        effects: List<SharedPlatformEffect> = emptyList()
    ) = RouteOutcome(
        SharedRouteFingerprint(
            category = category,
            route = route,
            sessionId = sessionId,
            rpcId = rpcId,
            requestType = requestType,
            finishRequest = finishRequest,
            action = action,
            accepted = accepted,
            hasMore = hasMore,
            itemCount = itemCount,
            replay = replay,
            outcome = outcome,
            target = target,
            value = value,
            applied = applied,
            malformedReason = malformedReason
        ),
        effects
    )

    private fun effects(vararg kinds: String): List<SharedPlatformEffect> = kinds.map(::SharedPlatformEffect)

    private fun effects(vararg values: SharedPlatformEffect): List<SharedPlatformEffect> = values.toList()

    private fun <T> decodedItemCount(frame: GatewayFrame, serializer: KSerializer<T>): Int =
        frame.items.orEmpty().count { item ->
            runCatching { wireJson.decodeFromJsonElement(serializer, item.toJsonElement()) }.isSuccess
        }

    private data class RouteOutcome(
        val fingerprint: SharedRouteFingerprint,
        val effects: List<SharedPlatformEffect>
    )
}
