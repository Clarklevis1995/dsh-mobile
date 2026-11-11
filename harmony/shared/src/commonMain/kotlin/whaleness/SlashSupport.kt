package whaleness

import com.clarklevis.dsh.shared.facade.SharedSlashCommandStore
import com.clarklevis.dsh.shared.facade.SharedSlashCommandTransition
import com.clarklevis.dsh.shared.gateway.GatewayRequest
import com.clarklevis.dsh.shared.gateway.GatewayRequests
import com.clarklevis.dsh.shared.protocol.GatewayCommandOption
import com.clarklevis.dsh.shared.protocol.GatewayFrame
import com.clarklevis.dsh.shared.protocol.GatewayModelSelection
import kotlinx.serialization.Serializable

@Serializable
data class HarmonySlashItem(
    val id: String,
    val name: String,
    val description: String,
    val kind: String,
)

@Serializable
data class HarmonySlashGroup(
    val id: String,
    val title: String,
    val items: List<HarmonySlashItem>,
)

@Serializable
data class HarmonySlash(
    val sessionId: String? = null,
    val supported: Boolean = false,
    val visible: Boolean = false,
    val groups: List<HarmonySlashGroup> = emptyList(),
    val loading: Boolean = false,
    val optionsTitle: String? = null,
    val options: List<GatewayCommandOption> = emptyList(),
    val optionsLoading: Boolean = false,
    val selecting: Boolean = false,
    val hint: String? = null,
    val executing: Boolean = false,
    val uncertain: Boolean = false,
    val error: String? = null,
)

@Serializable
data class HarmonyDraftMutation(
    val sessionId: String,
    val expectedRevision: Long,
    val expectedText: String,
    val replacement: String,
)

internal data class SlashChange(
    val view: HarmonySlash? = null,
    val request: GatewayRequest? = null,
    val mutation: HarmonyDraftMutation? = null,
    val submit: String? = null,
    val execution: String? = null,
    val model: GatewayModelSelection? = null,
    val acknowledgedSession: String? = null,
)

/** 命令种类与请求由共享状态机读取 Host 的 ui 元数据；平台不按命令名猜测行为。 */
internal class SlashSupport {
    private var store = SharedSlashCommandStore()
    private var supported = false
    private var focus: String? = null
    private var ordinal = 0L
    private var ready = false
    private var uncertain = false
    private var lastError: String? = null
    private var published: HarmonySlash? = null
    private var context: HarmonyCommand? = null
    private var selectingContext: HarmonyCommand? = null
    private var executing: Pair<String, String>? = null

    private data class Pending(
        val key: String,
        val request: GatewayRequest,
    )

    private val pending = mutableMapOf<String, Pending>()

    fun reset() {
        store = SharedSlashCommandStore()
        focus = null
        supported = false
        disconnected()
        published = null
    }

    fun configure(capabilities: List<String>) {
        supported = "commands" in capabilities
    }

    fun open(sessionId: String?): SlashChange {
        if (focus !=
            sessionId
        ) {
            focus = sessionId
            store.reset(sessionId)
            pending.clear()
            ready = false
            context = null
            selectingContext = null
        }
        return changed()
    }

    fun disconnected(): SlashChange {
        store.reset(focus)
        pending.clear()
        ready = false
        uncertain = false
        executing = null
        lastError = null
        context = null
        selectingContext =
            null
        return changed()
    }

    fun invalidate(sessionId: String): SlashChange {
        if (sessionId != focus) return SlashChange()
        ready = false
        return transition(store.invalidateCatalog(sessionId, supported, context?.locale))
    }

    fun command(
        command: HarmonyCommand,
        connected: Boolean,
    ): SlashChange {
        require(command.sessionId == focus) { "会话已切换" }
        if (command.op == "slash-input") {
            context = command
            if (store.snapshot().selectionLoading) return changed()
            return transition(store.updateInput(focus, command.text, connected, supported, command.locale))
        }
        if (command.op == "slash-dismiss") return transition(store.dismissMenus())
        require(connected && supported && !uncertain && executing == null && !store.snapshot().selectionLoading) { "命令当前不可用，请重新连接后重试" }
        context = command
        return when (command.op) {
            "slash-pick" -> {
                transition(store.selectItem(requireNotNull(command.itemId)))
            }

            "slash-option" -> {
                selectingContext = command
                transition(store.selectOption(requireNotNull(command.itemId)))
            }

            else -> {
                error("不支持的命令操作")
            }
        }
    }

    fun requireCanSend(text: String) {
        check(executing == null && !uncertain && !store.snapshot().selectionLoading) { "上一条命令尚未确认，请重新连接后重试" }
        check(!supported || !text.startsWith('/') || ready) { "命令目录尚未加载，请稍后重试" }
    }

    fun executionFor(text: String): String? = if (supported && ready) store.commandExecutionForInput(text)?.line else null

    fun beginExecution(
        sessionId: String,
        line: String,
    ): SlashChange {
        check(executing == null && !uncertain) { "上一条命令尚未确认" }
        val request = GatewayRequests.commandExecute(sessionId, line, emptyList())
        val key = "features:slash-execute:${++ordinal}"
        executing = sessionId to key
        return changed(request)
    }

    fun executingSession(): String? = executing?.first

    fun pendingKeys(): List<String> = pending.values.map { it.key } + listOfNotNull(executing?.second)

    fun timedOut(key: String): SlashChange {
        if (executing?.second == key) {
            executing = null
            uncertain = true
            lastError = "命令执行结果未确认，请重新连接"
            return changed()
        }
        val kind = pending.entries.firstOrNull { it.value.key == key }?.key ?: return SlashChange()
        pending.remove(kind)
        uncertain = true
        lastError = "命令请求超时，请重新连接后重试"
        return transition(store.requestFailed(kind, lastError))
    }

    fun accept(
        frame: GatewayFrame,
        raw: String,
    ): SlashChange {
        if (frame.resetRequired == true) return SlashChange()
        if (frame.kind == "command-executed" && executing?.first == frame.sessionId) {
            val session = executing!!.first
            executing = null
            return changed().copy(acknowledgedSession = session)
        }
        if (frame.kind == "error" && frame.requestType == "command-execute" && executing?.first == frame.sessionId) {
            executing = null
            uncertain = true
            lastError = frame.message ?: "命令结果尚未确认，请重新连接"
            return changed()
        }
        if (frame.sessionId != focus) return SlashChange()
        if (frame.kind !in setOf("commands", "command-options", "command-selected", "error")) return SlashChange()
        if (frame.kind == "error" && frame.requestType !in pending) return SlashChange()
        val expected =
            when (frame.kind) {
                "commands" -> "commands"
                "command-options" -> "command-options"
                "command-selected" -> "command-select"
                else -> frame.requestType
            }
        if (expected !in pending) return SlashChange()
        val transition = store.acceptFrame(raw)
        if (frame.kind == "commands" && !transition.snapshot.catalogLoading && transition.snapshot.lastError == null) ready = true
        if (frame.kind == "error" && expected == "command-select") uncertain = true
        val result = transition(transition, if (frame.kind == "command-selected") selectingContext else null)
        if (frame.kind == "command-selected" && !transition.snapshot.selectionLoading && context != selectingContext && context != null) {
            val refreshed = transition(store.updateInput(focus, requireNotNull(context).text, true, supported, context?.locale), null)
            return result.copy(view = refreshed.view ?: result.view, request = refreshed.request ?: result.request)
        }
        return result
    }

    private fun transition(
        value: SharedSlashCommandTransition,
        mutationContext: HarmonyCommand? = context,
    ): SlashChange {
        val state = value.snapshot
        if (!state.catalogLoading) pending.remove("commands")
        if (!state.optionsLoading) pending.remove("command-options")
        if (!state.selectionLoading) pending.remove("command-select")
        value.request?.let { pending[it.requestType] = Pending("features:slash:${++ordinal}", it) }
        if (state.lastError != null ||
            !uncertain
        ) {
            lastError = state.lastError?.let { if (it.startsWith("command-")) "主机命令不可用，请重新加载" else it.take(240) }
        }
        val replacement = value.replacementText ?: "".takeIf { value.clearDraft }
        val mutation =
            if (replacement != null &&
                mutationContext?.sessionId != null
            ) {
                HarmonyDraftMutation(
                    requireNotNull(mutationContext.sessionId),
                    mutationContext.draftRevision,
                    mutationContext.text,
                    replacement,
                )
            } else {
                null
            }
        return changed(
            value.request,
        ).copy(mutation = mutation, submit = value.submitText, execution = value.commandExecution?.line, model = value.selectedModel)
    }

    private fun changed(request: GatewayRequest? = null): SlashChange {
        val state = store.snapshot()
        val view =
            HarmonySlash(
                focus,
                supported,
                state.catalogVisible || state.optionsCommand != null || uncertain,
                state.filteredGroups.map { group ->
                    HarmonySlashGroup(
                        group.id,
                        group.title,
                        group.items.map { HarmonySlashItem(it.stableId, it.name, it.description, it.ui.kind) },
                    )
                },
                state.catalogLoading,
                state.optionsCommand?.name,
                state.options,
                state.optionsLoading,
                state.selectionLoading,
                state.argumentHint,
                executing != null,
                uncertain,
                lastError,
            )
        val changed = view != published
        published = view
        return SlashChange(view.takeIf { changed }, request)
    }
}
