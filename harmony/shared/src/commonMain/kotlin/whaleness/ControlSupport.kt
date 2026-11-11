package whaleness

import com.clarklevis.dsh.shared.domain.SessionControlState
import com.clarklevis.dsh.shared.facade.SharedSessionControlEffect
import com.clarklevis.dsh.shared.facade.SharedSessionControlResult
import com.clarklevis.dsh.shared.facade.SharedSessionControlStore
import com.clarklevis.dsh.shared.gateway.GatewayRequest
import com.clarklevis.dsh.shared.gateway.GatewayRequests
import com.clarklevis.dsh.shared.protocol.GatewayAgentPreset
import com.clarklevis.dsh.shared.protocol.GatewayContextSnapshot
import com.clarklevis.dsh.shared.protocol.GatewayFrame
import com.clarklevis.dsh.shared.protocol.GatewayModelCatalog
import com.clarklevis.dsh.shared.protocol.GatewayModelSelection
import com.clarklevis.dsh.shared.protocol.GatewayPermissionOption
import com.clarklevis.dsh.shared.protocol.GatewaySessionPermissions
import com.clarklevis.dsh.shared.protocol.GatewaySessionStatsSnapshot
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
data class HarmonyControlMetric(
    val id: String,
    val label: String,
    val value: String,
)

@Serializable
data class HarmonyControls(
    val sessionId: String? = null,
    val models: GatewayModelCatalog? = null,
    val permissions: GatewaySessionPermissions? = null,
    val context: GatewayContextSnapshot? = null,
    val stats: GatewaySessionStatsSnapshot? = null,
    val presets: List<GatewayAgentPreset> = emptyList(),
    val agentPresetDefault: String? = null,
    val permissionDefault: String? = null,
    val permissionDefaultOptions: List<GatewayPermissionOption> = emptyList(),
    val defaultModel: GatewayModelSelection? = null,
    val loading: Set<String> = emptySet(),
    val error: String? = null,
    val metrics: List<HarmonyControlMetric> = emptyList(),
    val agentPresetsAuthorable: Boolean = false,
    val agentPresetsHasDocument: Boolean = false,
)

internal data class ControlChange(
    val view: HarmonyControls?,
    val requests: List<GatewayRequest> = emptyList(),
)

/** 请求关联、排队、回滚、过期隔离只由 SharedSessionControlStore 决定。 */
internal class ControlSupport {
    private var store = SharedSessionControlStore()
    private var focus: String? = null
    private var mirror = SessionControlState()
    private var opened = false
    private var lastError: String? = null

    private fun state(): SessionControlState = mirror

    fun reset() {
        store = SharedSessionControlStore()
        mirror = SessionControlState()
        opened = false
        focus = null
        lastError = null
    }

    fun disconnected(): ControlChange = apply(store.requestsDisconnected())

    fun commandSelectedModel(
        sessionId: String,
        selection: GatewayModelSelection,
    ): ControlChange = apply(store.mergeModelProjection(sessionId, wireJson.encodeToString(selection)))

    fun pendingKeys(): List<String> = state().requestTokens.map { (kind, token) -> "controls:$kind:$token" }

    fun timedOut(key: String): ControlChange? {
        val state = state()
        val entry = state.requestTokens.entries.firstOrNull { "controls:${it.key}:${it.value}" == key } ?: return null
        val target = state.activeRequestTargets[entry.key] ?: return null
        lastError = "配置请求超时，结果尚未确认；请重新连接后重试"
        return apply(store.requestTimedOut(entry.key, target.isDefault, entry.value))
    }

    fun command(
        command: HarmonyCommand,
        connected: Boolean,
    ): ControlChange {
        lastError = null
        if (command.op == "controls-open" || command.op == "controls-defaults") {
            focus = if (command.op == "controls-open") requireNotNull(command.sessionId) else null
            opened = true
            lastError = null
            val results =
                if (focus != null) {
                    listOf(
                        store.requestModels(focus, connected),
                        store.requestPermissionOptions(requireNotNull(focus), connected),
                        store.requestContextUsage(requireNotNull(focus), connected),
                        store.requestSessionStats(requireNotNull(focus), connected),
                    )
                } else {
                    listOf(
                        store.requestModels(null, connected),
                        store.requestDefaults(connected),
                        store.requestDefaultModel(connected),
                        store.requestAgentPresets(connected),
                    )
                }
            val requests = results.flatMap { apply(it).requests }
            return ControlChange(view(), requests)
        }
        val selection = GatewayModelSelection(command.provider.orEmpty(), command.model.orEmpty(), command.reasoningEffort)
        return apply(
            when (command.op) {
                "controls-model" -> store.selectModel(requireNotNull(command.sessionId), wireJson.encodeToString(selection), connected)
                "controls-permission" -> store.setPermission(requireNotNull(command.sessionId), command.value, connected)
                "controls-save-model" -> store.saveDefaultModel(wireJson.encodeToString(selection), connected)
                "controls-default" -> store.setDefault(command.target, command.value, connected)
                else -> error("不支持的会话配置操作")
            },
        )
    }

    fun accept(frame: GatewayFrame): ControlChange? {
        if (frame.kind == "error" && frame.resetRequired == true) return null
        if (frame.kind == "error" &&
            frame.requestType in
            setOf(
                "models",
                "select-model",
                "permission-options",
                "permission",
                "context-usage",
                "session-stats",
                "defaults",
                "default-model",
                "save-default-model",
                "agent-presets",
                "set-default",
            )
        ) {
            return failed(requireNotNull(frame.requestType), frame.sessionId, frame.message ?: "主机拒绝了配置请求")?.let(::apply)
                ?: ControlChange(null)
        }
        val result =
            when (frame.kind) {
                "models" -> {
                    store.modelsReceived(
                        frame.sessionId,
                        frame.current?.let { wireJson.encodeToString(it) },
                        frame.routable == true,
                        wireJson.encodeToString(frame.groups.orEmpty()),
                        state().isPendingGlobalModelsRequest,
                    )
                }

                "select-model" -> {
                    val selection =
                        frame.selected?.toJsonElement()?.toString() ?: frame.selection?.let { wireJson.encodeToString(it) } ?: return null
                    store.modelSelected(frame.sessionId, selection)
                }

                "permission-options" -> {
                    store.permissionsReceived(
                        frame.sessionId,
                        wireJson.encodeToString(frame.sessionPermissions ?: return null),
                    )
                }

                "permission" -> {
                    store.permissionSelected(frame.sessionId, frame.set ?: return null)
                }

                "context-usage" -> {
                    store.contextReceived(
                        frame.sessionId,
                        frame.asOfSeq,
                        frame.tokenUsage?.let {
                            wireJson.encodeToString(it)
                        },
                        frame.contextPressure?.let { wireJson.encodeToString(it) },
                        null,
                    )
                }

                "session-stats" -> {
                    store.statsReceived(
                        frame.sessionId,
                        frame.asOfSeq,
                        frame.sessionStats?.let {
                            wireJson.encodeToString(it)
                        },
                        frame.tokenUsage?.totals?.let {
                            wireJson.encodeToString(
                                it,
                            )
                        },
                        frame.contextPressure?.let { wireJson.encodeToString(it) },
                    )
                }

                "agent-presets" -> {
                    store.agentPresetsReceived(
                        wireJson.encodeToString(frame.presets.orEmpty()),
                        frame.authorable == true,
                        frame.hasDocument == true,
                    )
                }

                "defaults" -> {
                    store.defaultsReceived(
                        frame.agentPresetDefault,
                        frame.permissionDefault,
                        frame.permissionDefaultOptions?.let {
                            wireJson.encodeToString(it)
                        },
                    )
                }

                "default-model" -> {
                    store.defaultModelReceived(frame.selection?.let { wireJson.encodeToString(it) })
                }

                "save-default-model" -> {
                    store.defaultModelSaved(frame.saved?.let { wireJson.encodeToString(it) })
                }

                "set-default" -> {
                    if (frame.applied == true && frame.target != null && frame.value?.stringValue != null) {
                        store.globalDefaultApplied(frame.target, requireNotNull(frame.value.stringValue))
                    } else {
                        failed("set-default", frame.sessionId, "默认配置未获主机确认") ?: return null
                    }
                }

                "error" -> {
                    failed(frame.requestType ?: return null, frame.sessionId, frame.message ?: "主机拒绝了配置请求") ?: return null
                }

                else -> {
                    return null
                }
            }
        return apply(result)
    }

    private fun failed(
        kind: String,
        sessionId: String?,
        message: String,
    ): SharedSessionControlResult? {
        val current = state()
        val target = current.activeRequestTargets[kind] ?: return null
        if (sessionId != null && sessionId != target.sessionId) return null
        lastError = message.take(160)
        return store.requestFailed(kind, target.isDefault, current.requestTokens[kind])
    }

    private fun apply(result: SharedSessionControlResult): ControlChange {
        if (result.applied || result.committed) {
            mirror = wireJson.decodeFromString(requireNotNull(store.snapshot().snapshotJson))
        }
        if (result.errorCode != null) {
            lastError =
                when (result.errorCode) {
                    "not-connected" -> "连接已断开，请重新连接后操作"
                    "request-quarantined" -> "上次配置结果尚未确认，请重新连接后重试"
                    else -> "此配置当前不可用，请刷新后重试"
                }
        }
        val effects = wireJson.decodeFromString<List<SharedSessionControlEffect>>(result.effectsJson)
        return ControlChange(
            if (opened &&
                (result.applied || result.committed || result.errorCode != null)
            ) {
                view()
            } else {
                null
            },
            effects.map(::request),
        )
    }

    private fun request(effect: SharedSessionControlEffect): GatewayRequest =
        when (effect.kind) {
            "models" -> {
                effect.sessionId?.let { GatewayRequests.sessionControl("models", it) } ?: GatewayRequests.simple("models")
            }

            "permission-options", "context-usage", "session-stats" -> {
                GatewayRequests.sessionControl(
                    effect.kind,
                    requireNotNull(effect.sessionId),
                )
            }

            "agent-presets", "defaults", "default-model" -> {
                GatewayRequests.simple(effect.kind)
            }

            "select-model" -> {
                GatewayRequests.selectModel(
                    requireNotNull(effect.sessionId),
                    requireNotNull(effect.provider),
                    requireNotNull(effect.model),
                    effect.reasoningEffort,
                )
            }

            "permission" -> {
                GatewayRequests.setPermission(requireNotNull(effect.sessionId), requireNotNull(effect.value))
            }

            "save-default-model" -> {
                GatewayRequests.saveDefaultModel(
                    requireNotNull(effect.provider),
                    requireNotNull(effect.model),
                    effect.reasoningEffort,
                )
            }

            "set-default" -> {
                GatewayRequests.setDefault(requireNotNull(effect.target), requireNotNull(effect.value))
            }

            else -> {
                error("无效的共享配置效果")
            }
        }

    private fun metrics(
        context: GatewayContextSnapshot?,
        snapshot: GatewaySessionStatsSnapshot?,
    ): List<HarmonyControlMetric> {
        val totals = snapshot?.tokenUsage?.totals
        val stats = snapshot?.stats

        fun metric(
            id: String,
            label: String,
            value: Any?,
        ): HarmonyControlMetric = HarmonyControlMetric(id, label, value?.toString() ?: "—")
        return listOf(
            metric("window", "上下文窗口", context?.pressure?.contextWindow),
            metric("pressure", "当前压力", context?.pressure?.pressureTokens),
            metric("projected", "预计上下文", context?.pressure?.projectedTokens),
            metric("input", "输入 Token", totals?.inputTokens),
            metric("output", "输出 Token", totals?.outputTokens),
            metric("cache-read", "缓存读取 Token", totals?.cacheReadTokens),
            metric("cache-write", "缓存写入 Token", totals?.cacheWriteTokens),
            metric("reasoning", "思考 Token", totals?.reasoningTokens),
            metric("turns", "轮次", stats?.turns),
            metric("steps", "步骤", stats?.steps),
            metric("llm-ms", "模型耗时", stats?.llmMs?.let { "$it ms" }),
            metric("tool-ms", "工具耗时", stats?.toolMs?.let { "$it ms" }),
            metric("ttft-ms", "首字耗时", stats?.ttftMs?.let { "$it ms" }),
        )
    }

    private fun view(): HarmonyControls {
        val state = state()
        return HarmonyControls(
            focus,
            if (focus == null) state.globalModelCatalog else state.modelCatalogs[focus],
            focus?.let { state.sessionPermissions[it] },
            focus?.let { state.contextSnapshots[it] },
            focus?.let { state.sessionStatsSnapshots[it] },
            state.agentPresets,
            state.agentPresetDefault,
            state.permissionDefault,
            state.permissionDefaultOptions,
            state.defaultModelSelection,
            state.loadingKinds + state.defaultConfigurationLoadingKinds,
            lastError,
            metrics(focus?.let { state.contextSnapshots[it] }, focus?.let { state.sessionStatsSnapshots[it] }),
            state.agentPresetsAuthorable,
            state.agentPresetsHasDocument,
        )
    }
}
