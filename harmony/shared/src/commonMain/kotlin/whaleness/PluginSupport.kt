package whaleness

import com.clarklevis.dsh.shared.gateway.GatewayRequest
import com.clarklevis.dsh.shared.gateway.GatewayRequests
import com.clarklevis.dsh.shared.protocol.GatewayFrame
import com.clarklevis.dsh.shared.protocol.JsonValue
import kotlinx.serialization.Serializable

@Serializable
data class HarmonyPluginEntry(
    val id: String,
    val rowId: String,
    val name: String,
    val description: String,
    val module: String,
    val enabled: Boolean,
    val phase: String?,
    val readOnlyReason: String?,
)

@Serializable
data class HarmonyPluginBundle(
    val name: String,
    val title: String,
    val description: String,
    val version: String?,
    val enabled: Boolean,
    val installed: Boolean,
    val official: Boolean,
    val gateway: Boolean,
    val readOnlyReason: String?,
    val error: String?,
    val entries: List<HarmonyPluginEntry>,
)

@Serializable
data class HarmonyOfficialPlugin(
    val id: String,
    val title: String,
    val description: String,
    val namespaces: List<String>,
)

@Serializable
data class HarmonyPlugins(
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val fresh: Boolean = false,
    val managementAvailable: Boolean = false,
    val settingsWritable: Boolean = false,
    val bundles: List<HarmonyPluginBundle> = emptyList(),
    val officialItems: List<HarmonyOfficialPlugin> = emptyList(),
    val pending: String? = null,
    val error: String? = null,
    val notice: String? = null,
)

internal data class PluginChange(
    val view: HarmonyPlugins? = null,
    val requests: List<GatewayRequest> = emptyList(),
)

/** 复用共享请求和类型化帧；写入只确认回执，显示状态始终来自重新读取的 Host 目录。 */
internal class PluginSupport {
    private var frame: GatewayFrame? = null
    private var catalogId: String? = null
    private var mutation: GatewayRequest? = null
    private var pendingName: String? = null
    private var ordinal = 0L
    private var visited = false
    private var fresh = false
    private var locale = "zh-CN"
    private var error: String? = null
    private var notice: String? = null
    private var published: HarmonyPlugins? = null

    fun reset() {
        frame = null
        catalogId = null
        mutation = null
        pendingName = null
        visited = false
        fresh = false
        error = null
        notice =
            null
        published = null
    }

    fun disconnected(): PluginChange {
        catalogId = null
        mutation = null
        pendingName = null
        fresh = false
        return changed()
    }

    fun pendingKeys(): List<String> =
        listOfNotNull(
            catalogId?.let {
                "plugins:catalog:$it"
            },
            mutation?.correlationId?.let { "plugins:write:$it" },
        )

    fun command(
        command: HarmonyCommand,
        connected: Boolean,
    ): PluginChange {
        visited = true
        locale = command.locale ?: locale
        if (command.op == "plugins-open") {
            if (!connected) {
                error = "连接网关后才能读取插件目录"
                return changed()
            }
            return refresh()
        }
        require(connected && fresh && frame?.managementAvailable == true && mutation == null && catalogId == null) { "插件状态尚未确认，请刷新目录后重试" }
        val enabled = command.value.toBooleanStrictOrNull() ?: error("插件状态无效")
        val bundles = bundles()
        val bundle =
            if (command.target ==
                "bundle"
            ) {
                bundles.firstOrNull { it.name == command.itemId }
            } else {
                bundles.firstOrNull { value ->
                    value.entries.any {
                        it.id ==
                            command.itemId
                    }
                }
            }
        requireNotNull(bundle) { "插件已变化，请刷新目录" }
        require(bundle.readOnlyReason == null) { bundle.readOnlyReason ?: "插件不可修改" }
        if ((bundle.gateway || bundles.any { it.gateway && it.entries.any { row -> row.id == command.itemId } }) &&
            !enabled
        ) {
            require(command.outcome == "confirmed") { "关闭移动网关会断开连接，请先确认" }
        }
        val requestId = "plugin-write-${++ordinal}"
        val request =
            if (command.target == "bundle") {
                GatewayRequests.pluginBundleSetEnabled(requestId, bundle.name, enabled)
            } else {
                require(command.target == "entry") { "插件操作无效" }
                val entry = requireNotNull(bundle.entries.firstOrNull { it.id == command.itemId && it.id.isNotBlank() })
                require(entry.readOnlyReason == null) { entry.readOnlyReason ?: "组件不可修改" }
                GatewayRequests.pluginEntrySetEnabled(requestId, entry.id, enabled)
            }
        mutation = request
        pendingName = command.target + ":" + command.itemId
        notice = null
        return changed(listOf(request))
    }

    fun timedOut(key: String): PluginChange {
        if (key == catalogId?.let { "plugins:catalog:$it" }) {
            catalogId = null
            fresh = false
            error = "插件目录读取超时，请重试"
        }
        if (key == mutation?.correlationId?.let { "plugins:write:$it" }) {
            mutation = null
            pendingName = null
            fresh = false
            notice = "操作结果未确认，正在重新读取实际状态"
            return refresh()
        }
        return changed()
    }

    fun accept(
        value: GatewayFrame,
        connected: Boolean,
    ): PluginChange? {
        when {
            value.kind == "plugins-changed" && visited && mutation == null && connected -> {
                return refresh()
            }

            value.kind == "plugin-catalog" && value.requestId != null && value.requestId == catalogId -> {
                frame = value
                catalogId = null
                fresh = true
                error = if (value.managementAvailable == true) null else "当前 Host 不支持插件管理"
            }

            value.kind in setOf("plugin-bundle-set-enabled", "plugin-entry-set-enabled") &&
                value.requestId != null &&
                value.requestId == mutation?.correlationId -> {
                notice =
                    when (value.result?.get("application")?.stringValue) {
                        "applied" -> {
                            null
                        }

                        "restart-required" -> {
                            "设置已保存，请重启 DSH Web 后刷新目录"
                        }

                        "overridden" -> {
                            "插件状态被其他配置覆盖，请检查 Host 配置"
                        }

                        else -> {
                            value.result
                                ?.get("error")
                                ?.stringValue
                                ?.take(240) ?: "插件操作未生效"
                        }
                    }
                mutation = null
                pendingName = null
                return if (connected) refresh() else changed()
            }

            value.kind == "error" && value.requestType == "plugin-catalog" && value.requestId != null && value.requestId == catalogId -> {
                catalogId =
                    null
                fresh = false
                error = value.message?.take(240) ?: "插件目录读取失败"
            }

            value.kind == "error" &&
                value.requestType == mutation?.requestType &&
                value.requestId != null &&
                value.requestId == mutation?.correlationId -> {
                mutation = null
                pendingName = null
                notice = value.message?.take(240) ?: "插件操作失败"
                return if (connected) refresh() else changed()
            }

            else -> {
                return null
            }
        }
        return changed()
    }

    private fun refresh(): PluginChange {
        if (catalogId != null) return changed()
        catalogId = "plugin-catalog-${++ordinal}"
        error = null
        return changed(listOf(GatewayRequests.pluginCatalog(requireNotNull(catalogId))))
    }

    private fun localized(value: JsonValue?): String? {
        value?.stringValue?.takeIf(String::isNotBlank)?.let { return it }
        val translations = value?.objectValue ?: return null
        val keys = if (locale.startsWith("zh")) listOf(locale, "zh-Hans", "zh-CN", "zh", "en") else listOf(locale, "en", "zh", "zh-CN")
        return (keys.mapNotNull { translations[it] } + translations.values).firstNotNullOfOrNull {
            it.stringValue?.takeIf(
                String::isNotBlank,
            )
        }
    }

    private fun bundles(): List<HarmonyPluginBundle> {
        val source = frame ?: return emptyList()
        val hidden =
            setOf(
                "@deepseek-ai/dsh-base",
                "@deepseek-ai/dsh-web-app",
                "@deepseek-ai/dsh-headless",
                "@deepseek-ai/dsh-sdk-app",
                "@deepseek-ai/dsh-acp-app",
                "@deepseek-ai/dsh-sdk-minimal",
            )
        val entries = source.plugins.orEmpty().associateBy { it["entryId"]?.stringValue ?: it["id"]?.stringValue }
        return source.bundles
            .orEmpty()
            .mapNotNull { raw ->
                val name = raw["name"]?.stringValue?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val installed = raw["installed"]?.booleanValue == true
                val optional = raw["optional"]?.booleanValue == true
                val failure = raw["error"]?.get("diagnostic")?.stringValue ?: raw["error"]?.get("code")?.stringValue
                if (name in hidden || (!installed && !optional && failure == null)) return@mapNotNull null
                HarmonyPluginBundle(
                    name,
                    localized(raw["meta"]?.get("title")) ?: name,
                    localized(raw["meta"]?.get("description")) ?: raw["description"]?.stringValue.orEmpty(),
                    raw["version"]?.stringValue,
                    raw["enabled"]?.booleanValue == true,
                    installed,
                    optional && !installed,
                    name.substringAfterLast('/') == "dsh-plugin-mobile-gateway",
                    raw["readOnlyReason"]?.stringValue,
                    failure,
                    raw["rows"]?.arrayValue.orEmpty().mapNotNull { row ->
                        val rowId = row["rowId"]?.stringValue ?: return@mapNotNull null
                        val id = row["entryId"]?.stringValue.orEmpty()
                        val live = entries[id]
                        HarmonyPluginEntry(
                            id,
                            rowId,
                            localized(row["meta"]?.get("title")) ?: localized(live?.get("meta")?.get("title"))
                                ?: live?.get("name")?.stringValue
                                ?: rowId,
                            localized(row["meta"]?.get("description")) ?: localized(live?.get("meta")?.get("description"))
                                ?: live?.get("description")?.stringValue.orEmpty(),
                            row["moduleName"]?.stringValue.orEmpty(),
                            live?.get("enabled")?.booleanValue == true,
                            live?.get("fiberPhase")?.stringValue,
                            live?.get("readOnlyReason")?.stringValue,
                        )
                    },
                )
            }.sortedBy { it.name }
    }

    private fun official(): List<HarmonyOfficialPlugin> =
        frame?.officialItems.orEmpty().mapNotNull { value ->
            val id = value["id"]?.stringValue ?: return@mapNotNull null
            val labels =
                when (id) {
                    "shell" -> {
                        "终端" to "限制命令的运行时间和输出内容。"
                    }

                    "agent-loop" -> {
                        "Agent 循环" to "控制工具调用的派发方式。"
                    }

                    "subagent" -> {
                        "子智能体" to
                            "配置子智能体的层级、数量和模型。"
                    }

                    "web-search" -> {
                        "网页搜索" to "配置搜索提供方。"
                    }

                    else -> {
                        id to ""
                    }
                }
            HarmonyOfficialPlugin(id, labels.first, labels.second, value["namespaces"]?.arrayValue.orEmpty().mapNotNull { it.stringValue })
        }

    private fun changed(requests: List<GatewayRequest> = emptyList()): PluginChange {
        val value =
            HarmonyPlugins(
                frame != null,
                catalogId != null,
                fresh,
                frame?.managementAvailable == true,
                frame?.settingsWritable == true,
                bundles(),
                official(),
                pendingName,
                error,
                notice,
            )
        val different = value != published
        published = value
        return PluginChange(value.takeIf { different }, requests)
    }
}
