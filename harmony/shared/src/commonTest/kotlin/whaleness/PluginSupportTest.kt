package whaleness

import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginSupportTest {
    private val engine = HarmonyEngine()

    private fun command(value: HarmonyCommand): HarmonyResult = wireJson.decodeFromString(engine.dispatch(wireJson.encodeToString(value)))

    private fun frame(value: String) = command(HarmonyCommand("frame", frame = value))

    private fun requestId(value: HarmonyResult): String =
        wireJson
            .parseToJsonElement(value.outbound.single().text)
            .jsonObject["requestId"]!!
            .jsonPrimitive.content

    private fun catalog(
        id: String,
        enabled: Boolean = false,
    ): HarmonyResult =
        frame(
            """{"kind":"plugin-catalog","requestId":"$id","managementAvailable":true,"settingsWritable":true,"bundles":[{"name":"test-plugin","installed":true,"enabled":$enabled,"meta":{"title":{"zh-CN":"测试插件","en":"Test plugin"}},"rows":[{"rowId":"row","entryId":"entry","moduleName":"test-module"}]},{"name":"dsh-plugin-mobile-gateway","installed":true,"enabled":true,"rows":[]}],"plugins":[{"entryId":"entry","name":"组件","enabled":$enabled,"fiberPhase":"active"}],"officialItems":[{"id":"shell","namespaces":["shell.exec"]}]}""",
        )

    private fun ready(): HarmonyResult {
        command(HarmonyCommand("prepare", endpoint = "ws://127.0.0.1/ws/mobile"))
        frame("""{"kind":"hello","authenticated":true}""")
        val opening = command(HarmonyCommand("plugins-open", locale = "zh-CN"))
        return catalog(requestId(opening))
    }

    @Test fun catalogUsesLocalizedMetadataAndComponentIdentityFromHost() {
        val state = ready().plugins!!
        val bundle = state.bundles.first { it.name == "test-plugin" }
        assertEquals("测试插件", bundle.title)
        assertEquals("entry", bundle.entries.single().id)
        assertEquals("test-module", bundle.entries.single().module)
        assertEquals(listOf("shell.exec"), state.officialItems.single().namespaces)
    }

    @Test fun mutationDoesNotOptimisticallyChangeStateAndReadsActualCatalogAfterReceipt() {
        ready()
        val changed = command(HarmonyCommand("plugins-toggle", target = "bundle", itemId = "test-plugin", value = "true"))
        assertFalse(
            changed.plugins!!
                .bundles
                .first { it.name == "test-plugin" }
                .enabled,
        )
        val id = requestId(changed)
        assertNull(frame("""{"kind":"plugin-bundle-set-enabled","requestId":"wrong","result":{"application":"applied"}}""").plugins)
        val receipt = frame("""{"kind":"plugin-bundle-set-enabled","requestId":"$id","result":{"application":"applied"}}""")
        assertTrue(receipt.plugins!!.loading)
        assertFalse(
            receipt.plugins!!
                .bundles
                .first { it.name == "test-plugin" }
                .enabled,
        )
        val actual = catalog(requestId(receipt), true)
        assertTrue(
            actual.plugins!!
                .bundles
                .first { it.name == "test-plugin" }
                .enabled,
        )
        assertTrue(
            actual.plugins!!
                .bundles
                .first { it.name == "test-plugin" }
                .entries
                .single()
                .enabled,
        )
    }

    @Test fun restartRequiredKeepsActualRunningStateAndExplainsSavedConfiguration() {
        ready()
        val changed = command(HarmonyCommand("plugins-toggle", target = "entry", itemId = "entry", value = "true"))
        val receipt =
            frame("""{"kind":"plugin-entry-set-enabled","requestId":"${requestId(changed)}","result":{"application":"restart-required"}}""")
        assertTrue(receipt.plugins!!.notice!!.contains("重启"))
        val actual = catalog(requestId(receipt))
        assertFalse(
            actual.plugins!!
                .bundles
                .first { it.name == "test-plugin" }
                .entries
                .single()
                .enabled,
        )
        assertTrue(actual.plugins!!.notice!!.contains("重启"))
    }

    @Test fun disablingGatewayRequiresExplicitConfirmationAndResetDropsPreviousHostCatalog() {
        ready()
        val denied = command(HarmonyCommand("plugins-toggle", target = "bundle", itemId = "dsh-plugin-mobile-gateway", value = "false"))
        assertFalse(denied.ok)
        assertTrue(denied.outbound.isEmpty())
        val reset = command(HarmonyCommand("prepare", endpoint = "ws://127.0.0.1:18897/ws/mobile"))
        assertNull(reset.plugins)
        frame("""{"kind":"hello","authenticated":true}""")
        val opening = command(HarmonyCommand("plugins-open"))
        assertTrue(opening.plugins!!.bundles.isEmpty())
    }

    @Test fun timeoutMakesCachedStateUnconfirmedAndStaleCatalogCannotCommit() {
        ready()
        val refresh = command(HarmonyCommand("plugins-open"))
        val id = requestId(refresh)
        val key = refresh.state!!.pendingOperations.first { it.startsWith("plugins:catalog:") }
        val timeout = command(HarmonyCommand("operation-timeout", requestKey = key))
        assertFalse(timeout.plugins!!.fresh)
        assertNull(catalog(id, true).plugins)
        val blocked = command(HarmonyCommand("plugins-toggle", target = "bundle", itemId = "test-plugin", value = "true"))
        assertFalse(blocked.ok)
        assertTrue(blocked.outbound.isEmpty())
    }
}
