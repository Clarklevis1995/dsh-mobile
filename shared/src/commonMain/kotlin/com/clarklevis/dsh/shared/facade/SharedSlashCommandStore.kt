package com.clarklevis.dsh.shared.facade

import com.clarklevis.dsh.shared.gateway.GatewayRequest
import com.clarklevis.dsh.shared.gateway.GatewayRequests
import com.clarklevis.dsh.shared.protocol.GatewayCommandOption
import com.clarklevis.dsh.shared.protocol.GatewayFrame
import com.clarklevis.dsh.shared.protocol.GatewayModelSelection
import com.clarklevis.dsh.shared.protocol.GatewaySlashCommand
import com.clarklevis.dsh.shared.protocol.GatewaySlashCommandGroup
import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder
import com.clarklevis.dsh.shared.protocol.JsonValue
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.json.decodeFromJsonElement

data class SharedSlashCommandSelection(
    val command: String,
    val option: GatewayCommandOption
)

data class SharedSlashCommandSnapshot(
    val sessionId: String? = null,
    val groups: List<GatewaySlashCommandGroup> = emptyList(),
    val commands: List<GatewaySlashCommand> = emptyList(),
    val inputText: String = "",
    val query: String = "",
    val catalogVisible: Boolean = false,
    val catalogLoading: Boolean = false,
    val activeCommand: GatewaySlashCommand? = null,
    val optionsCommand: GatewaySlashCommand? = null,
    val options: List<GatewayCommandOption> = emptyList(),
    val optionsLoading: Boolean = false,
    val selectionLoading: Boolean = false,
    val selections: List<SharedSlashCommandSelection> = emptyList(),
    val lastError: String? = null
) {
    val filteredGroups: List<GatewaySlashCommandGroup>
        get() {
            val needle = query.trim().lowercase()
            if (needle.isEmpty()) return groups
            return groups.mapNotNull { group ->
                val items = group.items.filter { item ->
                    item.name.lowercase().contains(needle) ||
                        item.description.lowercase().contains(needle) ||
                        item.whenToUse?.lowercase()?.contains(needle) == true
                }
                group.copy(items = items).takeIf { items.isNotEmpty() }
            }
        }

    val filteredCommands: List<GatewaySlashCommand>
        get() = filteredGroups.flatMap(GatewaySlashCommandGroup::items)

    val commandToken: String?
        get() = activeCommand?.let { it.ui.insertText?.trimEnd().orEmpty().ifBlank { "/${it.name}" } }
    val argumentHint: String?
        get() = activeCommand?.let { command ->
            command.ui.displayHint ?: command.ui.hint ?: command.input?.hint
        }
    val commandAllowsImages: Boolean
        get() = activeCommand?.ui?.images == true

    fun selectedLabel(command: String): String? =
        selections.lastOrNull { it.command == command }?.option?.label
}

data class SharedSlashCommandTransition(
    val snapshot: SharedSlashCommandSnapshot,
    val request: GatewayRequest? = null,
    val submitText: String? = null,
    val commandExecution: SharedSlashCommandExecution? = null,
    val replacementText: String? = null,
    val clearDraft: Boolean = false,
    val selectedModel: GatewayModelSelection? = null
)

data class SharedSlashCommandExecution(
    val line: String,
    val allowsImages: Boolean
)

/**
 * Mobile Gateway 斜杠命令的唯一协议状态机。平台只映射文本输入、菜单点击和 effect。
 * 命令行为严格读取服务端的 ui.kind/request 字段，不依据命令名决定菜单类型。
 */
class SharedSlashCommandStore {
    private var state = SharedSlashCommandSnapshot()
    private var catalogRequested = false

    fun snapshot(): SharedSlashCommandSnapshot = state

    fun reset(sessionId: String?): SharedSlashCommandTransition {
        catalogRequested = false
        state = SharedSlashCommandSnapshot(sessionId = sessionId?.takeIf(String::isNotBlank))
        return SharedSlashCommandTransition(state)
    }

    fun updateInput(
        sessionId: String?,
        text: String,
        isConnected: Boolean,
        isSupported: Boolean,
        locale: String? = null
    ): SharedSlashCommandTransition {
        val validSession = sessionId?.takeIf(String::isNotBlank)
        if (validSession != state.sessionId) reset(validSession)
        // iOS owns its draft in SwiftUI. Selecting a secondary option clears
        // that draft immediately; keep the correlated command alive until the
        // command-selected response arrives.
        if (state.selectionLoading && text.isEmpty()) return SharedSlashCommandTransition(state)
        state = state.copy(inputText = text)
        state.activeCommand?.let { activeCommand ->
            if (matchesCommandInput(activeCommand, text)) {
                state = state.copy(query = "", catalogVisible = false, lastError = null)
                return SharedSlashCommandTransition(state)
            }
            val wasSelecting = state.optionsCommand == activeCommand
            state = state.copy(
                activeCommand = null,
                optionsCommand = if (wasSelecting) null else state.optionsCommand,
                options = if (wasSelecting) emptyList() else state.options,
                optionsLoading = if (wasSelecting) false else state.optionsLoading,
                selectionLoading = if (wasSelecting) false else state.selectionLoading
            )
        }
        state.commands.firstOrNull { matchesCommandInput(it, text) }?.let { command ->
            state = state.copy(
                activeCommand = command,
                query = "",
                catalogVisible = false,
                lastError = null
            )
            return SharedSlashCommandTransition(state)
        }
        val isLookup = text.startsWith('/') && text.drop(1).none(Char::isWhitespace)
        if (!isLookup) {
            state = state.copy(query = "", catalogVisible = false, lastError = null)
            if (text.startsWith('/') && state.commands.isEmpty() && !catalogRequested &&
                isSupported && validSession != null && isConnected
            ) {
                catalogRequested = true
                state = state.copy(catalogLoading = true)
                return SharedSlashCommandTransition(state, GatewayRequests.slashCommands(validSession, locale))
            }
            return SharedSlashCommandTransition(state)
        }
        if (!isSupported || validSession == null) {
            state = state.copy(query = "", catalogVisible = false, lastError = null)
            return SharedSlashCommandTransition(state)
        }
        state = state.copy(query = text.drop(1), catalogVisible = true, lastError = null)
        if (!catalogRequested && isConnected) {
            catalogRequested = true
            state = state.copy(catalogLoading = true)
            return SharedSlashCommandTransition(state, GatewayRequests.slashCommands(validSession, locale))
        }
        return SharedSlashCommandTransition(state)
    }

    fun dismissMenus(): SharedSlashCommandTransition {
        state = state.copy(
            catalogVisible = false,
            activeCommand = state.activeCommand?.takeUnless { it == state.optionsCommand },
            optionsCommand = null,
            options = emptyList()
        )
        return SharedSlashCommandTransition(state)
    }

    fun clearActiveCommand(): SharedSlashCommandTransition {
        state = state.copy(activeCommand = null, inputText = "", query = "", catalogVisible = false)
        return SharedSlashCommandTransition(state)
    }

    fun commandExecutionForInput(text: String): SharedSlashCommandExecution? {
        val command = state.activeCommand ?: return null
        if (command.ui.submitRequest != "command-execute" || !matchesCommandInput(command, text)) return null
        return SharedSlashCommandExecution(
            line = text.trim(),
            allowsImages = commandAllowsImages(command)
        )
    }

    fun selectCommand(name: String): SharedSlashCommandTransition {
        val command = state.commands.firstOrNull { it.name == name } ?: return fail("command-unknown")
        return selectCommand(command)
    }

    fun selectItem(id: String): SharedSlashCommandTransition {
        val command = state.commands.firstOrNull { it.stableId == id } ?: return fail("command-unknown")
        return selectCommand(command)
    }

    private fun selectCommand(command: GatewaySlashCommand): SharedSlashCommandTransition {
        return when (command.ui.kind) {
            "immediate" -> {
                val submitRequest = command.ui.submitRequest ?: return fail("command-submit-unsupported")
                state = state.copy(catalogVisible = false, query = "")
                val line = command.ui.submitText?.takeIf(String::isNotBlank) ?: "/${command.name}"
                if (submitRequest !in setOf("message", "command-execute")) return fail("command-submit-unsupported")
                SharedSlashCommandTransition(
                    state,
                    submitText = line.takeIf { submitRequest == "message" },
                    commandExecution = if (submitRequest == "command-execute") {
                        SharedSlashCommandExecution(line, commandAllowsImages(command))
                    } else null,
                    clearDraft = true
                )
            }
            "input" -> {
                if (command.ui.submitRequest !in setOf("message", "command-execute")) {
                    return fail("command-submit-unsupported")
                }
                state = state.copy(
                    activeCommand = command,
                    catalogVisible = false,
                    query = "",
                    lastError = null
                )
                SharedSlashCommandTransition(
                    state,
                    replacementText = command.ui.insertText?.takeIf(String::isNotBlank)
                        ?: "/${command.name} ",
                    clearDraft = true
                )
            }
            "select" -> {
                if (command.ui.optionsRequest != "command-options") return fail("command-options-unsupported")
                val sessionId = state.sessionId ?: return fail("command-session-missing")
                state = state.copy(
                    activeCommand = command,
                    catalogVisible = false,
                    query = "",
                    optionsCommand = command,
                    options = emptyList(),
                    optionsLoading = true,
                    lastError = null
                )
                SharedSlashCommandTransition(
                    state,
                    request = GatewayRequests.slashCommandOptions(sessionId, command.name),
                    replacementText = command.ui.insertText?.takeIf(String::isNotBlank)
                        ?: "/${command.name}",
                    clearDraft = true
                )
            }
            else -> fail("command-ui-unsupported")
        }
    }

    fun selectOption(optionId: String): SharedSlashCommandTransition {
        val command = state.optionsCommand ?: return fail("command-options-not-open")
        if (command.ui.selectionRequest != "command-select") return fail("command-selection-unsupported")
        if (state.options.none { it.id == optionId }) return fail("command-option-unknown")
        val sessionId = state.sessionId ?: return fail("command-session-missing")
        state = state.copy(selectionLoading = true, lastError = null)
        return SharedSlashCommandTransition(
            state,
            GatewayRequests.selectSlashCommandOption(sessionId, command.name, optionId),
            replacementText = "",
            clearDraft = true
        )
    }

    fun requestFailed(requestType: String, message: String?): SharedSlashCommandTransition {
        if (requestType !in REQUEST_TYPES) return SharedSlashCommandTransition(state)
        if (requestType == "commands") catalogRequested = false
        state = state.copy(
            catalogLoading = if (requestType == "commands") false else state.catalogLoading,
            optionsLoading = if (requestType == "command-options") false else state.optionsLoading,
            selectionLoading = if (requestType == "command-select") false else state.selectionLoading,
            lastError = message?.takeIf(String::isNotBlank) ?: "$requestType-failed"
        )
        return SharedSlashCommandTransition(state)
    }

    fun acceptFrame(json: String): SharedSlashCommandTransition = try {
        acceptFrame(GatewayWireDecoder.decode(json))
    } catch (_: Throwable) {
        fail("command-frame-invalid")
    }

    private fun acceptFrame(frame: GatewayFrame): SharedSlashCommandTransition = when (frame.kind) {
        "commands" -> acceptCatalog(frame)
        "command-options" -> acceptOptions(frame)
        "command-selected" -> acceptSelected(frame)
        "error" -> frame.requestType?.let { requestFailed(it, frame.message ?: frame.code) }
            ?: SharedSlashCommandTransition(state)
        else -> SharedSlashCommandTransition(state)
    }

    private fun acceptCatalog(frame: GatewayFrame): SharedSlashCommandTransition {
        if (frame.sessionId != state.sessionId) return SharedSlashCommandTransition(state)
        val groups = decodeCatalogGroups(frame) ?: return fail("command-catalog-invalid")
        val commands = groups.flatMap(GatewaySlashCommandGroup::items)
        if (commands.any { command ->
                command.name.isBlank() || command.name.any(Char::isWhitespace) ||
                    command.ui.kind !in setOf("immediate", "input", "select")
            }
        ) return fail("command-catalog-invalid")
        val manuallyTypedCommand = commands.firstOrNull { matchesCommandInput(it, state.inputText) }
        state = state.copy(
            groups = groups,
            commands = commands,
            activeCommand = manuallyTypedCommand ?: state.activeCommand,
            query = if (manuallyTypedCommand == null) state.query else "",
            catalogVisible = if (manuallyTypedCommand == null) state.catalogVisible else false,
            catalogLoading = false,
            lastError = null
        )
        return SharedSlashCommandTransition(state)
    }

    private fun matchesCommandInput(command: GatewaySlashCommand, text: String): Boolean {
        if (command.ui.kind != "input") return false
        val token = command.ui.insertText?.trimEnd().orEmpty().ifBlank { "/${command.name}" }
        return text == token || text.startsWith("$token ")
    }

    private fun commandAllowsImages(command: GatewaySlashCommand): Boolean =
        command.ui.images == true

    private fun decodeCatalogGroups(frame: GatewayFrame): List<GatewaySlashCommandGroup>? {
        val rawGroups = frame.groups ?: return null
        return rawGroups.map { rawGroup ->
            runCatching {
                wireJson.decodeFromJsonElement(
                    GatewaySlashCommandGroup.serializer(),
                    rawGroup.toJsonElement()
                )
            }.getOrNull() ?: return null
        }.takeIf { groups -> groups.all { it.id.isNotBlank() && it.title.isNotBlank() } }
    }

    private fun acceptOptions(frame: GatewayFrame): SharedSlashCommandTransition {
        val command = state.optionsCommand ?: return SharedSlashCommandTransition(state)
        if (frame.sessionId != state.sessionId || frame.command?.stringValue != command.name) {
            return SharedSlashCommandTransition(state)
        }
        val options = frame.options.orEmpty()
        if (options.any { it.id.isBlank() || it.label.isBlank() }) return fail("command-options-invalid")
        state = state.copy(options = options, optionsLoading = false, lastError = null)
        return SharedSlashCommandTransition(state)
    }

    private fun acceptSelected(frame: GatewayFrame): SharedSlashCommandTransition {
        val command = state.optionsCommand ?: return SharedSlashCommandTransition(state)
        if (frame.sessionId != state.sessionId || frame.command?.stringValue != command.name) {
            return SharedSlashCommandTransition(state)
        }
        val selected = frame.selected.toCommandOption() ?: return fail("command-selection-invalid")
        val nextSelections = state.selections.filterNot { it.command == command.name } +
            SharedSlashCommandSelection(command.name, selected)
        val selectedModel = frame.value.toModelSelection()
        state = state.copy(
            activeCommand = null,
            optionsCommand = null,
            options = emptyList(),
            optionsLoading = false,
            selectionLoading = false,
            selections = nextSelections,
            lastError = null
        )
        return SharedSlashCommandTransition(state, clearDraft = true, selectedModel = selectedModel)
    }

    private fun fail(code: String): SharedSlashCommandTransition {
        state = state.copy(
            catalogLoading = false,
            optionsLoading = false,
            selectionLoading = false,
            lastError = code
        )
        return SharedSlashCommandTransition(state)
    }

    private fun JsonValue?.toCommandOption(): GatewayCommandOption? {
        val value = this?.objectValue ?: return null
        return GatewayCommandOption(
            id = value["id"]?.stringValue ?: return null,
            label = value["label"]?.stringValue ?: return null,
            detail = value["detail"]?.stringValue,
            description = value["description"]?.stringValue,
            selected = value["selected"]?.booleanValue
        )
    }

    private fun JsonValue?.toModelSelection(): GatewayModelSelection? {
        val value = this?.objectValue ?: return null
        return GatewayModelSelection(
            provider = value["provider"]?.stringValue ?: return null,
            model = value["model"]?.stringValue ?: return null,
            reasoningEffort = value["reasoningEffort"]?.stringValue
        )
    }

    companion object {
        private val REQUEST_TYPES = setOf("commands", "command-options", "command-select")
    }
}
